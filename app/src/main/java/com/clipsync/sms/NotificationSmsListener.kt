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

        private const val PREFS_NAME = "clipsync_notification_listener"
        private const val KEY_WAS_CONNECTED = "was_connected"

        /**
         * 记录通知监听是否曾经真正连上过。
         * MIUI/HyperOS 在用户划掉 App（进程被结束）后经常会收回通知使用权，
         * 下次打开时 permission 会直接从设置里消失。用这个标记区分"从来没开过"和
         * "以前开过但被系统收回"，后者可以给出更明确的"一键重新开启"引导。
         */
        fun wasEverConnected(ctx: Context): Boolean {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAS_CONNECTED, false)
        }

        private fun setWasConnected(ctx: Context, connected: Boolean) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WAS_CONNECTED, connected).apply()
        }

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
         * 重连有两级：
         * 1. Android 7.0+ 调 requestRebind()，AOSP/多数 ROM 上有效；
         * 2. 但在 MIUI/HyperOS 上 requestRebind() 会静默无效（不抛异常、也不绑定），
         *    实测即使调用成功系统也不回调 onListenerConnected。
         *
         * 旧代码 requestRebind 一旦没抛异常就 return，第 2 级永远走不到，
         * 导致 MIUI 上通知监听一旦解绑就再也恢复不了。这里改成：requestRebind 后
         * 延迟复查 isConnected，仍未绑定则自动降级到"disable→enable 组件"强制重绑，
         * 并做时间节流，避免反复 toggle。
         *
         * 返回 true 表示已连接或已发起重连；false 表示重连失败，调用方应提示用户手动处理。
         */
        fun ensureConnected(ctx: Context): Boolean {
            if (isConnected) return true
            val cn = ComponentName(ctx, NotificationSmsListener::class.java)
            // 第一级：requestRebind
            var requested = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Log.i(TAG, "🔄 尝试 requestRebind() 重新绑定通知监听")
                    requestRebind(cn)
                    requested = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind 抛异常: ${e.message}")
            }
            // 延迟 3s 复查：若 requestRebind 在 MIUI 上静默无效，降级到组件 toggle
            scheduleRebindVerify(ctx, requested)
            // requestRebind 不可用（< Android 7）时立刻走 toggle
            if (!requested) {
                return forceRebindViaToggle(ctx)
            }
            return true
        }

        /** 上次强制 toggle 的时间，节流用（系统绑定有开销，不能频繁操作） */
        @Volatile
        private var lastToggleAt: Long = 0L
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * requestRebind 后延迟复查；仍未绑定则降级到组件 toggle。
         * 用主线程 Handler 延迟，不阻塞调用方；节流 20s 最多一次 toggle。
         */
        private fun scheduleRebindVerify(ctx: Context, didRequestRebind: Boolean) {
            mainHandler.postDelayed({
                if (isConnected) {
                    Log.i(TAG, "✅ requestRebind 生效，通知监听已连接")
                    return@postDelayed
                }
                Log.w(TAG, "⚠️ requestRebind 后仍未绑定（MIUI 静默无效），降级到组件 toggle 强制重绑")
                // 节流：20s 内只 toggle 一次
                val now = System.currentTimeMillis()
                if (now - lastToggleAt < 20_000L) {
                    Log.i(TAG, "⏸ 距上次 toggle 不足 20s，跳过")
                    return@postDelayed
                }
                lastToggleAt = now
                forceRebindViaToggle(ctx)
            }, 3_000L)
        }

        /**
         * 第二级重绑：把 Listener 组件 disable 再 enable，强制系统解除旧绑定并重新 bindService。
         * 这是不依赖 requestRebind 的兜底手段；部分 MIUI 版本上唯一有效。
         */
        private fun forceRebindViaToggle(ctx: Context): Boolean {
            val cn = ComponentName(ctx, NotificationSmsListener::class.java)
            return try {
                val pm = ctx.packageManager
                Log.i(TAG, "🔄 toggle 组件状态强制重绑（disable→enable）")
                pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
                // 稍作延迟再 enable，确保系统处理了 disable
                mainHandler.postDelayed({
                    try {
                        pm.setComponentEnabledSetting(cn,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP)
                        Log.i(TAG, "🔄 组件已重新 enable，等待系统绑定")
                    } catch (e: Exception) {
                        Log.e(TAG, "重新 enable 组件失败: ${e.message}")
                    }
                }, 500L)
                true
            } catch (e: Exception) {
                Log.e(TAG, "toggle 组件失败（可能被 MIUI 限制）: ${e.message}")
                false
            }
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
        setWasConnected(this, true)
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
        // tickerText 是系统为状态栏滚动预留的完整原文，MIUI/HyperOS 下它不做折叠，
        // 形如「火山引擎: 【火山引擎】验证码： 520314 。您正在…」。
        // 而 android.text/bigText 会把长短信开头签名折叠成「…】」，但验证码和正文完整。
        // tickerText 是拿到完整短信最可靠的通知字段。
        val ticker = sbn.notification.tickerText?.toString().orEmpty()

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

        // tickerText 可能带「标题: 」前缀（如「火山引擎: 【火山引擎】验证码…」），剥掉它得到正文
        val tickerBody = run {
            var t = ticker.trim()
            if (title.isNotBlank() && t.startsWith(title)) {
                t = t.removePrefix(title).trimStart()
                if (t.startsWith(":") || t.startsWith("：")) t = t.drop(1).trimStart()
            }
            t
        }

        // 采信优先级：数据库原文 > tickerText（完整全文）> bigText > text
        val notifBody = when {
            tickerBody.isNotBlank() -> tickerBody
            bigText.isNotBlank() -> bigText
            else -> text
        }
        val (dbBody, dbFrom) = queryLatestSms()

        // 采信优先级：数据库原文 > 通知各字段
        var body: String = when {
            !dbBody.isNullOrBlank() -> dbBody
            notifBody.isNotBlank() -> notifBody
            else -> return
        }

        // 发件人：优先数据库里查到的（更真实），其次是通知标题（可能是联系人名）
        var sender: String = when {
            !dbFrom.isNullOrBlank() -> dbFrom
            title.isNotBlank() -> title
            else -> "未知"
        }

        // 检测正文是否被系统"锁屏防偷窥"打码：内容里连续 5 个及以上 * 视为打码。
        // 这种情况说明拿到的 body 是锁屏隐藏版，绝不能把 ****** 上行。
        // 但注意：MIUI 上 SMS_RECEIVED 广播是被拦的（旧代码指望"等广播明文"永远等不到），
        // 所以这里分两步：
        //   1. 立刻再查一次数据库（短信此刻通常已写入 provider，之前查不到只是回调早于写入）；
        //   2. 若数据库已有明文，用明文继续走落盘；若还没入库，直接 return——
        //      数据库轮询（SmsDatabasePoller 每 20s）稍后入库时会读到完整原文兜底补发。
        val looksMasked = Regex("\\*{5,}").containsMatchIn(body)
        if (looksMasked) {
            val (retryBody, retryFrom) = queryLatestSms()
            if (!retryBody.isNullOrBlank() && !Regex("\\*{5,}").containsMatchIn(retryBody)) {
                // 数据库里已有明文：直接用它，继续往下走落盘流程
                Log.i(TAG, "🔓 通知正文打码，但数据库已查到明文，改用数据库原文")
                body = retryBody
                if (!retryFrom.isNullOrBlank()) sender = retryFrom
            } else {
                Log.i(TAG, "⏸ 通知正文打码且数据库暂未入库，等待数据库轮询兜底（20s 内）")
                return
            }
        }

        // 会话摘要通知：同一号码连发多条时，系统先推一条折叠摘要（形如
        // 【阿里云】[2条]…验证码是：770395…），带 [N条] 计数且内容是几条的混合摘要。
        // 几秒后会推不带计数的完整单条通知。摘要必须丢掉，否则残缺版先上行、
        // 完整版再上行，Mac 上同一条验证码弹两次。
        val isConversationSummary = Regex("\\[\\s*\\d+\\s*条\\s*\\]").containsMatchIn(body)
        if (isConversationSummary) {
            Log.i(TAG, "⏸ 跳过 [N条] 会话摘要，等待完整单条通知")
            return
        }

        // 注意：长短信折叠时 android.text/bigText 开头会是「…】」（签名被截掉一段），
        // 但验证码和正文在折叠处之后是完整的。绝不能因为开头的「…」就丢弃整条——
        // tickerText 通常是完整全文，已优先采信；万一只有折叠版，也清洗开头残片后照发。
        if (body.trimStart().startsWith("…") || body.trimStart().startsWith("...")) {
            // 去掉折叠残片：把开头到第一个完整句子之前的残片清掉。
            // 形如「…】验证码：520314。…此验证码。」→ 找到第一个「。」或正文起点重建
            val cleaned = cleanFoldedHead(body)
            if (cleaned.isNotBlank()) {
                Log.i(TAG, "🧹 通知正文开头被折叠，已清洗残片后继续")
                body = cleaned
            }
        }

        // 完整文本：企业短信正文已带【签名】则直接用原文；个人短信才补【发件人】
        val fullText = com.clipsync.sms.SmsDatabasePoller.composeSmsText(sender, body)
        val preview = "来自 $sender 的短信"
        if (!SmsDeduplicator.shouldUpload(sender, isPlaintext = false)) {
            Log.i(TAG, "⏸ 短信去重（通知）: $sender")
            return
        }
        Log.i(TAG, "↑ 收到短信(通知): ${fullText.take(40)}")

        // 统一走"先落盘、后补发"路径：通知监听里直接 ws.send() 时若连接是半开的
        // （息屏 Doze 下被 NAT 静默掐断），消息写进本地缓冲区返回成功却石沉大海，
        // 这条验证码就丢了。落盘后由 SyncService 补发、确认双向通畅后才删盘。
        PendingSmsQueue.enqueue(this, fullText, preview, sender)
        com.clipsync.history.HistoryStore.addSms(
            this,
            com.clipsync.history.HistoryStore.HistoryItem(
                id = java.util.UUID.randomUUID().toString(),
                kind = "sms",
                text = fullText,
                preview = preview,
                direction = "out",
                ts = System.currentTimeMillis() / 1000
            )
        )
        val svc = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_FLUSH_PENDING_SMS
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 拉起 SyncService 失败: ${e.message}（短信已落盘，稍后自动补发）")
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
     * 清洗折叠短信开头的残片。MIUI/HyperOS 对长短信的通知会把开头截成「…】」或「…」，
     * 但后面验证码和正文完整。这里把开头到第一个实义内容之间的残片去掉：
     *   「…】验证码： 520314 。您正在…」 → 「验证码： 520314 。您正在…」
     * 规则：若开头是省略号，则裁掉开头直到出现「】」后的内容（残片只含被截签名），
     * 或裁掉开头的省略号本身。
     */
    private fun cleanFoldedHead(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("…") && !s.startsWith("...")) return s
        // 去掉开头省略号
        s = s.removePrefix("…").removePrefix("...").trimStart()
        // 若残片是被截的签名（以 】 结尾的前半段），且正文在 】 之后，裁到 】 之后
        val bracketEnd = s.indexOf('】')
        if (bracketEnd in 0..6) {
            // 形如 】验证码：… —— 开头这个 】 是被截断签名的收尾，正文在其后
            val after = s.substring(bracketEnd + 1).trimStart()
            if (after.isNotBlank()) s = after
        }
        return s
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
