package com.clipsync.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * 短信数据库轮询兜底。
 *
 * MIUI/HyperOS 上两条实时路径都可能失效：
 *   1. SMS_RECEIVED 广播被系统静默拦截（logcat 里零条广播痕迹）；
 *   2. 短信通知不实时投递给第三方 NotificationListener（实测能收到淘宝/B站等
 *      App 的通知，唯独 com.android.mms 的短信通知收不到 onNotificationPosted 回调）。
 *
 * 但 App 持有 READ_SMS 权限，短信数据库里始终能读到完整原文。因此看门狗每 20s
 * 主动查一次收件箱最近短信，发现新短信就落盘走统一补发，完全不依赖广播/通知回调。
 *
 * 去重：记录上次处理过的最大短信 date 以及已处理短信的 (date^body) 指纹集合，
 * 既不会重发旧短信，也能兜住同一毫秒到达多条的情况。
 */
object SmsDatabasePoller {
    private const val TAG = "SmsDbPoller"
    private const val PREF = "clipsync_sms_poller"
    private const val KEY_LAST_DATE = "last_sms_date"
    private const val KEY_SEEN = "seen_fingerprints"
    private const val MAX_SEEN = 200

    /**
     * 组装上行短信全文。
     * 企业短信正文自身已带【签名】（如「【火山引擎】到期预警…」），此时直接用原文，
     * 绝不再叠加发件人——否则会出现「【火山引擎】【火山引擎】…」的重复签名。
     * 只有个人短信（正文无【签名】）才在前面补【发件人】标明来源。
     */
    fun composeSmsText(sender: String, body: String): String {
        val trimmed = body.trim()
        val alreadySigned = trimmed.startsWith("【") && trimmed.contains("】")
        if (alreadySigned) return trimmed
        val who = sender.ifBlank { "未知" }
        return "【$who】$trimmed"
    }

    /**
     * 查询收件箱里晚于上次水位的新短信，逐条落盘到待发队列并返回条数。
     * 由 SyncService 看门狗每 20s 调用；内部完成水位推进与去重。
     */
    fun pollNewSms(ctx: Context): Int {
        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var lastDate = pref.getLong(KEY_LAST_DATE, 0L)
        val seen = loadSeen(pref)

        val newItems = ArrayList<Triple<Long, String, String>>() // date, body, address
        try {
            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE
            )
            // 只看接收类型；取最近 20 条在内存里过滤，避免水位丢失时扫全表
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 20"
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.TYPE} = ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                sort
            )?.use { c ->
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx).orEmpty() else ""
                    val addr = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else ""
                    if (body.isBlank()) continue
                    // 首次运行：只把水位建到当前最新，不把历史短信全补发出去
                    if (lastDate == 0L) {
                        if (date > lastDate) lastDate = date
                        continue
                    }
                    if (date <= lastDate) continue
                    val fp = fingerprint(date, body)
                    if (seen.contains(fp)) continue
                    newItems.add(Triple(date, body, addr))
                }
                if (c.count == 0) {
                    Log.w(TAG, "⚠️ 查询返回 0 行（READ_SMS 权限或 ROM 限制？），无法兜底新短信")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询短信数据库失败: ${e.message}")
            return 0
        }

        if (newItems.isEmpty()) {
            // 首次运行也要把水位持久化，否则下次会补发老短信
            if (pref.getLong(KEY_LAST_DATE, 0L) == 0L && lastDate > 0L) {
                pref.edit().putLong(KEY_LAST_DATE, lastDate).apply()
            }
            return 0
        }

        // 按时间正序处理，旧的先落盘先发
        var maxDate = lastDate
        newItems.sortedBy { it.first }.forEach { (date, body, addr) ->
            val sender = addr.ifBlank { "未知" }
            val fullText = composeSmsText(sender, body)
            val preview = "来自 $sender 的短信"
            PendingSmsQueue.enqueue(ctx, fullText, preview)
            com.clipsync.history.HistoryStore.addSms(
                ctx,
                com.clipsync.history.HistoryStore.HistoryItem(
                    id = java.util.UUID.randomUUID().toString(),
                    kind = "sms",
                    text = fullText,
                    preview = preview,
                    direction = "out",
                    ts = System.currentTimeMillis() / 1000
                )
            )
            seen.add(fingerprint(date, body))
            if (date > maxDate) maxDate = date
            Log.i(TAG, "↑ 轮询发现新短信（数据库兜底）: ${fullText.take(40)}")
        }

        // 裁剪指纹集合，防止无限增长
        while (seen.size > MAX_SEEN) {
            // LinkedHashSet 按插入顺序，移除最老的
            val it = seen.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }

        pref.edit()
            .putLong(KEY_LAST_DATE, maxDate)
            .putString(KEY_SEEN, seen.joinToString(","))
            .apply()

        return newItems.size
    }

    private fun fingerprint(date: Long, body: String): String = "$date:${body.hashCode()}"

    private fun loadSeen(pref: android.content.SharedPreferences): LinkedHashSet<String> {
        val raw = pref.getString(KEY_SEEN, "") ?: ""
        val set = LinkedHashSet<String>()
        if (raw.isNotBlank()) raw.split(",").filter { it.isNotBlank() }.forEach { set.add(it) }
        return set
    }
}
