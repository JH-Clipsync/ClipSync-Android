package com.clipsync.sms

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.clipsync.service.SyncService

/**
 * 通知监听服务：读取系统弹出的短信通知（绕开 MIUI/HyperOS 的 SMS 广播拦截）。
 * 不做验证码匹配，直接把标题 + 正文全量上行。
 * 用户需在「设置 → 通知使用权」中授权本 App。
 */
class NotificationSmsListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifSmsListener"

        /**
         * 通知监听是否真正被系统绑定。
         * MIUI/HyperOS 在 App 重装/升级后会出现"设置里显示已授权但系统没绑定"的假开启状态，
         * 这个标记由 onListenerConnected/Disconnected 实时更新，供 UI 检测并提示用户。
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        /**
         * 最后一次收到任何通知的时间戳。
         * MIUI/HyperOS 有个 bug：通知监听服务运行久了系统会静默停止投递通知，
         * 但 onListenerDisconnected 不会被调用，isConnected 还停在 true。
         * 通过这个时间戳，外部 Watchdog 可以判断监听是否"假活"并触发重绑。
         */
        @Volatile
        var lastNotifyAt: Long = 0L
            private set

        /**
         * 看门狗调用：检测监听是否可能处于 MIUI 静默断连状态。
         * 如果已连接但超过 staleMs 没收到任何通知，认为是假活，强制重绑。
         * 返回 true 表示已触发重绑。
         */
        fun checkAndRebindIfStale(ctx: Context, staleMs: Long = 10 * 60 * 1000L): Boolean {
            if (!isConnected) {
                ensureConnected(ctx)
                return true
            }
            val idle = System.currentTimeMillis() - lastNotifyAt
            if (idle > staleMs) {
                Log.w(TAG, "🔍 看门狗：监听已 ${idle / 1000}s 无任何通知，疑似 MIUI 静默断连，强制重绑")
                isConnected = false
                ensureConnected(ctx)
                return true
            }
            return false
        }

        /**
         * 检测通知监听是否真正绑定，未绑定时尝试自动重连。
         *
         * 两种方式：
         * 1. Android 7.0+ 直接调 requestRebind()，请求系统重新绑定 Listener；
         * 2. 若 1 无效（部分 MIUI 版本），用 disable → enable 组件的方式强制系统重绑。
         *
         * 返回 true 表示已连接或已发起重连；false 表示重连失败，调用方应提示用户手动处理。
         */
        fun ensureConnected(ctx: Context): Boolean {
            if (isConnected) return true
            val cn = ComponentName(ctx, NotificationSmsListener::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Log.i(TAG, "🔄 尝试 requestRebind() 重新绑定通知监听")
                    requestRebind(cn)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind 失败: ${e.message}")
            }
            try {
                val pm = ctx.packageManager
                Log.i(TAG, "🔄 尝试 toggle 组件状态重新绑定")
                pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "toggle 组件失败: ${e.message}")
            }
            return false
        }

        // 常见短信/通知 App 包名
        private val SMS_APPS = setOf(
            "com.android.mms",                    // AOSP / MIUI 系统短信
            "com.google.android.apps.messaging",  // Google 短信
            "com.samsung.android.messaging"
        )

        // 明确排除的系统管家类 App（这些包的所有通知都不处理）
        private val BLOCKED_PACKAGES = setOf(
            "com.miui.securitycenter",            // 小米安全中心 / 手机管家
            "com.miui.cleanmaster",               // 小米清理大师
            "com.miui.antivirus",                  // 小米杀毒
            "com.miui.guardprovider"               // 小米守卫/安全组件
        )

        // 识别 MIUI/HyperOS 在"短信"通知渠道里发的系统运行状态提示关键词
        // 典型内容：【"短信"正在运行】点按即可了解详情或停止应用。
        private val OP_ANY = listOf("正在运行", "点按即可了解详情", "停止应用")
        private val CHANNEL_ANY = listOf("短信", "Messaging", "Messages")

        // 识别系统硬件/状态通知（非短信），例如：
        //   【退出快充加速】息屏后极致加速，设备温度略有升高
        //   【省电模式已开启】
        //   【电池温度过高】
        //   【"短信"正在运行】
        private val SYSTEM_NOTICE_KEYWORDS = listOf(
            "快充", "充电", "加速", "设备温度", "温度升高",
            "省电模式", "超级省电", "电池", "电量",
            "息屏", "锁屏", "后台运行",
            "流量", "移动数据", "WiFi已", "蓝牙已",
            "正在运行", "点按即可了解详情", "停止应用",
            "退出", "已开启", "已关闭"
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "🟢 [通知监听] onListenerConnected —— 已连接，开始接收通知")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "🔴 [通知监听] onListenerDisconnected —— 已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        lastNotifyAt = System.currentTimeMillis()
        // 同步未启用就不拉服务，与 SmsReceiver 保持一致。
        if (!SyncService.isUserEnabled(this)) {
            // 不打日志避免刷屏
            return
        }

        val pkg = sbn.packageName ?: return
        Log.d(TAG, "📬 [通知监听] 收到通知 pkg=$pkg id=${sbn.id}")

        // 先拉黑安全中心/手机管家类 App：这些的所有通知都不处理
        if (BLOCKED_PACKAGES.contains(pkg) ||
            pkg.contains("securitycenter", ignoreCase = true) ||
            pkg.contains("cleanmaster", ignoreCase = true) ||
            pkg.contains("antivirus", ignoreCase = true) ||
            pkg.contains("guardprovider", ignoreCase = true) ||
            pkg.contains("phoneclean", ignoreCase = true)) {
            Log.i(TAG, "⏸ 跳过系统管家类通知 pkg=$pkg")
            return
        }

        // 只处理短信 App 的通知
        val isSmsApp = SMS_APPS.contains(pkg) ||
            pkg.contains("mms", ignoreCase = true) ||
            pkg.contains("message", ignoreCase = true)
        if (!isSmsApp) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        // 过滤 MIUI/HyperOS 系统在"短信"通知渠道里发的运行状态提示，例如：
        //   title: "短信"
        //   text:  "【\"短信\"正在运行】点按即可了解详情或停止应用。"
        // 这种不是真实短信，必须在任何数据库查询前就拦掉，避免误上行。
        if (isSystemChannelNotice(title, text, bigText)) {
            Log.i(TAG, "⏸ 跳过系统短信渠道状态提示: ${text.take(60)}")
            return
        }

        // 过滤系统硬件/状态通知（通过短信渠道发出的非短信内容），例如：
        //   【退出快充加速】息屏后极致加速，设备温度略有升高
        //   【"短信"正在运行】点按即可了解详情或停止应用
        if (isSystemNotice(title, text, bigText)) {
            Log.i(TAG, "⏸ 跳过系统通知: ${text.take(60)}")
            return
        }

        // 通知里的正文可能被系统折叠：EXTRA_BIG_TEXT 也不一定是全文，
        // 优先从系统 SMS 数据库拉最近 60 秒内的原始短信（拿到即为准）；
        // 拿不到再退回到通知的 bigText / text。
        val notifBody = if (bigText.isNotBlank()) bigText else text
        val (dbBody, dbFrom) = queryLatestSms()

        // 采信优先级：数据库原文 > 通知 bigText > 通知 text
        val body = when {
            !dbBody.isNullOrBlank() -> dbBody
            notifBody.isNotBlank() -> notifBody
            else -> return
        }

        // 检测正文是否被系统"锁屏防偷窥"打码：内容里连续 5 个及以上 * 视为打码
        val looksMasked = Regex("\\*{5,}").containsMatchIn(body)
        if (looksMasked) {
            Log.i(TAG, "⏸ 通知短信正文被系统打码（******），等待 SMS 广播的明文")
            return
        }

        // 检测正文是否被系统"通知栏预览折叠"截断：
        // MIUI/HyperOS 的通知栏对长短信会把开头切掉，只保留最后一段并在最前面加 "..." 或 "…"。
        // 这种情况下 body 是残缺的，也让位给广播的明文版本。
        val looksTruncated = body.trimStart().let { it.startsWith("...") || it.startsWith("…") }
        if (looksTruncated) {
            Log.i(TAG, "⏸ 通知短信被系统折叠（开头 …），等待 SMS 广播的明文")
            return
        }

        // 发件人：优先数据库里查到的（更真实），其次是通知标题（可能是联系人名）
        val sender = when {
            !dbFrom.isNullOrBlank() -> dbFrom
            title.isNotBlank() -> title
            else -> "未知"
        }
        // 完整文本：如果发件人是号码/联系人，前面补【发件人】方便下游识别
        val fullText = "【$sender】$body"
        val preview = "来自 $sender 的短信"
        if (!SmsDeduplicator.shouldUpload(sender, isPlaintext = false)) {
            Log.i(TAG, "⏸ 短信去重（通知）: $sender")
            return
        }
        Log.i(TAG, "↑ 收到短信(通知): ${fullText.take(40)}")

        val svc = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_SEND_SMS_CODE
            putExtra(SyncService.EXTRA_TEXT, fullText)
            putExtra(SyncService.EXTRA_PREVIEW, preview)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 短信转发失败: ${e.message}")
        }
    }

    /**
     * 识别 MIUI/HyperOS 等 ROM 在"短信"通知渠道里发的系统运行状态提示。
     * 典型样式：【"短信"正在运行】点按即可了解详情或停止应用。
     * 这类通知与真实短信无关，必须在最前面就拦掉。
     */
    private fun isSystemChannelNotice(title: String, text: String, bigText: String): Boolean {
        val combined = listOf(title, text, bigText).joinToString(" ")
        // 条件 1: 标题就是短信 channel 名（不是联系人）
        val channelTitle = title.isNotBlank() &&
            CHANNEL_ANY.any { it == title.trim() }
        // 条件 2: 正文里既有 channel 关键词，又有"运行/点按/停止"类操作关键词
        val hasChannel = CHANNEL_ANY.any { combined.contains(it) }
        val hasOp = OP_ANY.any { combined.contains(it) }
        // 两个条件必须同时满足，避免误杀真实短信
        return channelTitle && hasChannel && hasOp
    }

    /**
     * 识别通过短信渠道发出的系统通知（硬件/状态/运行提示）。
     * 只要标题或正文包含任一系统关键词即判为非短信。
     */
    private fun isSystemNotice(title: String, text: String, bigText: String): Boolean {
        val combined = listOf(title, text, bigText).joinToString(" ")
        return SYSTEM_NOTICE_KEYWORDS.any { combined.contains(it) }
    }

    /**
     * 从系统短信数据库查最近 60 秒内的一条收信，返回 (body, address)。
     * 这是绕过 MIUI 通知栏折叠 body 的关键：数据库里存的一定是完整原文。
     * 若未授予 READ_SMS 权限或查询失败，返回 (null, null)，调用方自行退回通知里的内容。
     */
    private fun queryLatestSms(): Pair<String?, String?> {
        return try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            )
            // 只看 60 秒内、且是接收类型的短信，防止拿到旧短信
            val since = System.currentTimeMillis() - 60_000L
            val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = ?"
            val args = arrayOf(since.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 1"

            contentResolver.query(uri, projection, selection, args, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                    val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    val b = if (bodyIdx >= 0) c.getString(bodyIdx) else null
                    val a = if (addrIdx >= 0) c.getString(addrIdx) else null
                    return b to a
                }
            }
            null to null
        } catch (e: Exception) {
            Log.w(TAG, "查询 SMS 数据库失败: ${e.message}")
            null to null
        }
    }
}
