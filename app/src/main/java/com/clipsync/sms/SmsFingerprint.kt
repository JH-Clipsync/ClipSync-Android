package com.clipsync.sms

import android.content.Context
import android.provider.ContactsContract
import android.util.LruCache
import java.util.UUID

/**
 * 短信三源（SmsReceiver 广播 / NotificationSmsListener 通知 / SmsDatabasePoller 查库）
 * 统一稳定指纹。id 由「归一化发件人 + 短信 date + 归一化正文」经
 * [UUID.nameUUIDFromBytes] 派生（type 3 确定性 UUID），三源输入一致则 id 必然一致。
 *
 * 约束：绝不依赖短信库 _id（广播 SmsMessage 拿不到），三个输入必须三源均可获得。
 * date 取 Telephony.Sms.DATE：广播路径由 SmsDatabasePoller.findInboxDate 反查同一行
 * DATE 对齐，查不到才退回 SmsMessage.timestampMillis；通知路径优先采信数据库行。
 *
 * 发件人归一化规则（[normalizeSender]）：
 * 1. 空 / "未知" / "未知号码" / "unknown" → 统一 unknown，三源的"未知"收敛同值；
 * 2. 号码（数字，允许前导 +）：剥离空格/连字符/括号；+86、86 前缀的国内 11 位
 *    手机号（13 位 86 开头、后 11 位 1 开头）去掉 86；1069 通道号/银行短号不受影响；
 * 3. 非号码（联系人名/字母短码）：能反查联系人号码就转号码形态；拿不到（本 App 未
 *    声明 READ_CONTACTS，查询稳定抛 SecurityException；或联系人库无此名）才降级为
 *    去空白的名称形态，同名输入至少彼此一致。
 */
object SmsFingerprint {

    private const val PREFIX_TEL = "tel:"
    private const val PREFIX_NAME = "name:"
    private const val UNKNOWN = "unknown"

    /** 号码里允许出现的分隔符：各类空白、连字符、中英文括号 */
    private val SEPARATORS = Regex("[\\s\\-()（）]")

    /** 联系人名 → 号码缓存，空串是"查过没有/无权限"的负缓存，避免反复撞 SecurityException */
    private val contactNumberCache = LruCache<String, String>(64)

    /** 正文归一化：统一换行（CRLF/CR → LF）并 trim；不折叠内部字符，避免误并不同短信 */
    fun normalizeBody(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** 纯字符串发件人归一化（无 Context、无异常路径） */
    fun normalizeSender(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "未知" || s == "未知号码" ||
            s.equals("unknown", ignoreCase = true)
        ) {
            return UNKNOWN
        }
        val stripped = s.replace(SEPARATORS, "")
        val withoutPlus = stripped.removePrefix("+")
        val isNumeric = withoutPlus.isNotEmpty() && withoutPlus.all { it.isDigit() }
        if (isNumeric) {
            var digits = withoutPlus
            if (digits.length == 13 && digits.startsWith("86")) {
                val local = digits.substring(2)
                if (local.length == 11 && local.startsWith("1")) digits = local
            }
            return PREFIX_TEL + digits
        }
        // 联系人名/字母短码：分隔符已剥离，作为名称形态原样保留
        return PREFIX_NAME + stripped
    }

    /** 带 Context：名称形态先尝试联系人反查号码，失败再降级名称形态 */
    fun normalizeSender(ctx: Context?, raw: String?): String {
        val base = normalizeSender(raw)
        if (ctx == null || !base.startsWith(PREFIX_NAME)) return base
        val number = resolveContactNumber(ctx, base.removePrefix(PREFIX_NAME)) ?: return base
        return PREFIX_TEL + number
    }

    /** 联系人显示名反查号码，返回归一化号码（不含 tel: 前缀）；无权限/查不到返回 null */
    fun resolveContactNumber(ctx: Context, displayName: String): String? {
        val key = displayName.trim()
        if (key.isEmpty() || key == "未知" || key == "未知号码") return null
        contactNumberCache.get(key)?.let { return it.ifEmpty { null } }
        var resolved: String? = null
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                arrayOf(key),
                null
            )?.use { c ->
                if (c.moveToFirst()) resolved = c.getString(0)
            }
        } catch (e: Exception) {
            // 未声明 READ_CONTACTS 时稳定抛 SecurityException：负缓存兜底，降级名称形态
            resolved = null
        }
        val normalized = resolved
            ?.let { normalizeSender(it) }
            ?.takeIf { it.startsWith(PREFIX_TEL) }
        contactNumberCache.put(key, normalized.orEmpty())
        return normalized?.removePrefix(PREFIX_TEL)
    }

    /** 派生稳定短信 id，入参须为归一化后的发件人与正文 */
    fun derive(normalizedSender: String, date: Long, normalizedBody: String): String {
        val material = "sms|$normalizedSender|$date|$normalizedBody"
        return UUID.nameUUIDFromBytes(material.toByteArray(Charsets.UTF_8)).toString()
    }

    /** 便捷入口：内部完成归一化（含联系人反查）后派生 id */
    fun derive(ctx: Context?, rawSender: String?, date: Long, rawBody: String): String =
        derive(normalizeSender(ctx, rawSender), date, normalizeBody(rawBody))
}
