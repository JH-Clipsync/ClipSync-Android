package com.clipsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipsync.MainActivity
import com.clipsync.accessibility.ClipSyncAccessibilityService
import com.clipsync.clipboard.ClipboardManagerHelper
import com.clipsync.history.HistoryStore
import com.clipsync.model.Message
import com.clipsync.model.MessageType
import com.clipsync.net.AuthClient
import com.clipsync.net.WsClient
import com.clipsync.state.ConnectionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 前台常驻同步服务：维护 WebSocket 长连接，接收 SmsReceiver / ShareActivity 的发送请求。
 *
 * 通知栏文案会随 WebSocket 连接状态更新：
 *   - 已连接：ClipSync · 已连接
 *   - 连接中：ClipSync · 连接中…
 *   - 未连接：ClipSync · 未连接
 * 但是渠道优先级仍然是 IMPORTANCE_MIN，不会打扰用户。
 */
class SyncService : Service() {
    companion object {
        const val ACTION_SEND_SMS_CODE = "com.clipsync.SEND_SMS_CODE"
        const val ACTION_SEND_SHARE = "com.clipsync.SEND_SHARE"
        /** 显式的"重试连接"请求：服务活着但连接没建起来时用它踢一脚 */
        const val ACTION_CONNECT = "com.clipsync.CONNECT"
        /** 从 PendingSmsQueue（持久化待发短信队列）读所有未发送的短信并发出 */
        const val ACTION_FLUSH_PENDING_SMS = "com.clipsync.FLUSH_PENDING_SMS"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TYPE = "type"

        private const val CHANNEL_ID = "clipsync_fg"
        private const val CHANNEL_ALERTS_ID = "clipsync_alerts"
        private const val NOTIFICATION_ID = 1
        private const val PRESENCE_NOTIF_BASE = 2000
        /** 权限缺失提醒通知（独立于常驻通知，可被用户划掉） */
        private const val PERM_NOTIF_ID = 3000
        /** 权限提醒最小间隔，避免看门狗每 20s 骚扰一次 */
        private const val PERM_ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 小时

        @Volatile
        private var currentWs: WsClient? = null

        /** 当前活跃的 WS 实例（服务没启动时为 null） */
        fun activeWs(): WsClient? = currentWs

        /**
         * 服务实例是否存活（onCreate 到 onDestroy 之间）。
         *
         * 注意它和 activeWs() 的区别：服务启动后要先走网络换 token，这段时间
         * activeWs() 一直是 null，但服务明明在跑。MainActivity 判断"要不要清
         * sync_enabled"必须看这个，否则连接建立前回一次前台就把短信同步关掉。
         */
        @Volatile
        private var serviceAlive = false

        fun isServiceAlive(): Boolean = serviceAlive

        /** 回前台时调用：让 WS 跳过退避等待立即重连 */
        fun kick() { currentWs?.kick() }

        /**
         * 用户是否希望服务运行。
         *
         * 用 SharedPreferences 持久化：进程被划掉、用户主动停止、杀进程后重启都能查到。
         * 短信接收器、通知监听器在收到短信时先看这个标记：
         *   - true  → 拉起服务，正常上行
         *   - false → 直接丢弃，不拉服务（用户没要同步就别骚扰他）
         */
        @Volatile
        private var userEnabledCache: Boolean? = null

        fun isUserEnabled(ctx: android.content.Context): Boolean {
            userEnabledCache?.let { return it }
            val v = ctx.getSharedPreferences("clipsync", MODE_PRIVATE)
                .getBoolean("sync_enabled", false)
            userEnabledCache = v
            return v
        }

        fun setUserEnabled(ctx: android.content.Context, enabled: Boolean) {
            userEnabledCache = enabled
            ctx.getSharedPreferences("clipsync", MODE_PRIVATE)
                .edit().putBoolean("sync_enabled", enabled).apply()
        }

        /**
         * 连接还没建好时收到的短信暂存队列（text 到 preview）。
         *
         * 场景：划掉 App 后来短信，SmsReceiver 冷启动本服务，此时还在走网络
         * 换 token，ws 是 null。若直接 send 会被吞掉，这条验证码就永远发不出去。
         * 先进队列，连接 OPEN 后统一补发。
         */
        /**
         * 已发送、等待"双向通畅"确认的短信（队列项 id → 发出时间）。
         * 75s 后链路仍然通畅（能收到服务端帧）才把磁盘队列里对应条目删掉；
         * 期间若检测到半开/断线，条目还在磁盘上，重连后用同一 id 补发。
         */
        private val inflightSms = LinkedHashMap<String, Long>()

        /**
         * 重启同步服务：登录 / 退出登录 / 改同步密码后调用，
         * 让新的 token 和加密密钥生效（连接参数是在 onCreate 时读取的）。
         */
        fun restart(ctx: android.content.Context) {
            val intent = Intent(ctx, SyncService::class.java)
            ctx.stopService(intent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private var ws: WsClient? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var incomingJob: Job? = null
    private var stateJob: Job? = null
    private var authJob: Job? = null

    /** 上次弹权限缺失提醒的时间，用于节流（见 PERM_ALERT_INTERVAL_MS） */
    private var lastPermAlertAt = 0L
    /** 当前检测到的权限问题数，避免无变化时反复重建常驻通知 */
    private var lastPermIssueCount = -1
    /** 当前权限告警摘要（拼进常驻通知文案），null 表示无未决问题 */
    private var permWarningText: String? = null

    /** 网络恢复监听：没网时连接失败后，联网瞬间自动重试，不必用户手动点 */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * 服务是否已经收到 onDestroy。
     *
     * 换 token 是异步的：用户在"连接中"点取消会 stopService，但那个请求还在飞，
     * 回来后如果照旧建连，就会造出一个没人管的 WsClient —— activeWs() 永远非空，
     * 于是"取消"按钮再也点不动。这个标记让迟到的回调自己退场。
     */
    @Volatile
    private var destroyed = false

    /**
     * 是否有一次换 token 正在进行。
     *
     * 换 token 走网络，期间 ws 还是 null。少了这个标记，用户连点「启动」或
     * 网络回调和 onStartCommand 撞在一起，就会并发打好几次 /auth/login，
     * 直接撞上服务端每分钟 10 次的登录限流。
     */
    @Volatile
    private var connecting = false

    /** 是否曾经进入过 CONNECTING/OPEN；用于区分"服务刚起、还没连"和"已连过又断了" */
    private var hasBeenConnectingOrOpen: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        Log.i("ClipSync", "🟢 同步服务已启动")
        startForeground(buildNotification(ConnectionBus.STATE_CONNECTING))
        // 不再常驻持有 PARTIAL_WAKE_LOCK：永久锁会让 CPU 全程无法休眠，
        // 被 MIUI/HyperOS 等系统的"耗电异常检测"判定为高耗电台式应用而自动杀掉。
        // 后台保活依赖"前台服务(dataSync) + 用户把电池策略设为无限制/加入白名单"，
        // 这两项已足以让 Doze 下网络和长连接存活；真正需要 CPU 的短时任务
        // （处理下行剪贴板、补发短信）改为 acquireBriefWakeLock 短暂拿锁、带超时自动释放。
        com.clipsync.clipboard.ScreenshotWatcher.start(this)
        watchNetwork()
        connectWs()
        // 短信投递确认 + 半开连接看门狗：定时发应用层 ping，
        // 75s 收不到任何服务端帧就强制重连；发出满 75s 且链路通畅才删磁盘队列
        startSmsWatchdog()
        // 通知监听兜底重绑：服务起来时本进程一定活着，此时调 requestRebind()
        // 系统最容易把 NotificationSmsListener 绑定上来。MIUI 杀进程后绑定会丢失，
        // 光靠用户回前台触发不可靠（且旧逻辑误判已连接会跳过），这里不依赖 UI。
        // 权限没开时 ensureConnected 内部 requestRebind 会被系统忽略，无副作用。
        serviceScope.launch {
            kotlinx.coroutines.delay(1500)
            runCatching {
                com.clipsync.sms.NotificationSmsListener.ensureConnected(this@SyncService)
            }
        }
    }

    /**
     * 监听网络可用事件。
     *
     * 场景：用户在飞行模式下点了启动，换 token 直接失败；之后连上 Wi-Fi，
     * 如果没有这个监听，服务就一直干等着，界面停在未连接。
     */
    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (destroyed) return
                if (ws == null) {
                    Log.i("ClipSync", "🌐 网络已恢复，重新尝试连接")
                    serviceScope.launch { connectWs() }
                } else {
                    ws?.kick()
                }
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.w("ClipSync", "✗ 网络监听注册失败: ${it.message}") }
    }

    /**
     * 短暂获取 PARTIAL_WAKE_LOCK，保证一小段关键工作（如处理下行消息、补发短信）
     * 在 Doze/息屏期间能跑完；[timeoutMs] 后由系统强制释放，绝不常驻。
     *
     * 每次新建一把独立、非引用计数的锁，用完即可被 GC/超时回收，
     * 避免旧版"一把永久锁持有整个服务生命周期"导致的持续耗电与被杀。
     */
    private fun acquireBriefWakeLock(tag: String, timeoutMs: Long) {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            val lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipSync::$tag")
            lock?.setReferenceCounted(false)
            lock?.acquire(timeoutMs)
        }.onFailure { Log.w("ClipSync", "✗ 获取短时 WakeLock 失败: ${it.message}") }
    }

    private fun connectWs() {
        // 幂等：已连上、或正在换 token，都不要再发起一次
        if (ws != null || connecting) {
            Log.i("ClipSync", "⏸ 同步服务已在运行或正在连接，忽略重复启动")
            return
        }
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val raw = sp.getString("server", null) ?: com.clipsync.BuildConfig.DEFAULT_SERVER
        // 用户可能只填了 host:port，这里统一补上 ws:// 前缀
        val server = com.clipsync.net.ServerAddress.normalize(raw)
        if (server.isEmpty()) {
            Log.w("ClipSync", "✗ 未填写服务器地址")
            ConnectionBus.publish(ConnectionBus.STATE_CLOSED, "请先在设置里填写服务器地址")
            return
        }
        Log.i("ClipSync", "→ 正在连接: $server")

        if (!AuthClient.isLoggedIn(this) && !AuthClient.hasCredentials(this)) {
            Log.w("ClipSync", "✗ 尚未填写账号密码，无法连接（请在设置里填写）")
            ConnectionBus.publish(
                ConnectionBus.STATE_CLOSED,
                "请先在设置里填写用户名和密码（账号由管理员创建）"
            )
            return
        }

        // 立即通知 UI 进入连接中状态：换 token 也算连接过程的一部分
        ConnectionBus.publish(ConnectionBus.STATE_CONNECTING)
        connecting = true

        // 没有 token 就先用账号密码换一个，所以不需要单独的"登录"按钮。
        // 这一步走网络，必须在协程里做，不能阻塞 onCreate。
        serviceScope.launch {
            try {
                when (val result = AuthClient.ensureToken(this@SyncService, server)) {
                    is AuthClient.TokenResult.Ok -> openWs(server, result.token)
                    is AuthClient.TokenResult.MissingCredentials -> stopWithReason(
                        "请先在设置里填写用户名和密码（账号由管理员创建）"
                    )
                    is AuthClient.TokenResult.Rejected -> stopWithReason(
                        "登录失败：${result.reason}，请到设置里检查用户名和密码"
                    )
                    is AuthClient.TokenResult.Unreachable -> stopWithReason(
                        "连接失败：${result.reason}"
                    )
                }
            } finally {
                connecting = false
            }
        }
    }

    /**
     * 连接不成功时收摊：把原因发给界面，服务继续留着待命。
     *
     * 不 stopSelf 是有意的 —— 网络恢复监听还挂在这个服务上，联网瞬间就能自动
     * 重试。界面判断"是否在连接"看的是 failureReason，所以服务活着也不会让
     * 「取消」按钮失灵。
     */
    private fun stopWithReason(reason: String) {
        Log.w("ClipSync", "✗ $reason")
        // 建连失败留下的半成品要清掉，否则 ws != null 会挡住后续重试
        ws?.stop()
        ws = null
        currentWs = null
        ConnectionBus.publish(ConnectionBus.STATE_CLOSED, reason)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(ConnectionBus.STATE_CLOSED))
    }

    /**
     * 本机设备 ID：首次生成后持久化，之后一直复用。
     *
     * 不能每次建连都随机：服务端按 device_id 在 Redis 里登记在线设备，ID 一直变
     * 会堆出一串再也不会下线的幽灵设备，在线数虚高，进而影响登录时"复用还是
     * 新签发 Token"的判断。
     */
    private fun stableDeviceID(): String {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        sp.getString("device_id", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        sp.edit().putString("device_id", fresh).apply()
        return fresh
    }

    /**
     * 机器级自定义设备名（存在 clipsync SharedPreferences，与登录账号无关）。
     * 换用户登录时沿用作新账号下本机的默认名。
     */
    private fun localDeviceName(): String =
        getSharedPreferences("clipsync", MODE_PRIVATE)
            .getString("device_name", null)?.trim().orEmpty()

    /** 改名成功 / presence 同步到本机新名时写回机器级存储 */
    private fun saveLocalDeviceName(name: String) {
        getSharedPreferences("clipsync", MODE_PRIVATE)
            .edit().putString("device_name", name.trim()).apply()
    }

    /**
     * 本机接码卡号（设置页手填，存在 clipsync prefs）。
     * Android 10+ 无法稳定读取本机号码，只能由用户填写；作为短信 payload.receiver
     * 明文上行，供服务端开放接口按收端号码筛选。
     */
    private fun localPhoneNumber(): String =
        getSharedPreferences("clipsync", MODE_PRIVATE)
            .getString("local_phone_number", null)?.trim().orEmpty()

    /**
     * 读取本机同步开关，拼成上报字符串：
     *   clip_up  = 本机剪贴板变化会自动上行（upload_enabled）
     *   sms_in   = 短信同步上行（同步总开关开启即视为开启，短信由 SmsReceiver/通知监听触发）
     *   auto_put = 收到远端剪贴板自动写入本机（auto_apply）
     */
    private fun buildCaps(): String {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val uploadEnabled = sp.getBoolean("upload_enabled", false)
        val autoApply = sp.getBoolean("auto_apply", true)
        val syncOn = sp.getBoolean("sync_enabled", false)
        val parts = mutableListOf<String>()
        if (uploadEnabled) parts += "clip_up"
        if (syncOn) parts += "sms_in"
        if (autoApply) parts += "auto_put"
        return parts.joinToString(",")
    }

    /** 拿到 token 后真正建立 WebSocket 连接 */
    private fun openWs(server: String, token: String) {
        // 服务已被销毁（用户点了取消）就别再建连，否则留下一个没人回收的连接
        if (destroyed || ws != null) {
            if (destroyed) Log.i("ClipSync", "⏸ 服务已停止，放弃迟到的建连请求")
            return
        }

        val deviceID = stableDeviceID()
        // 机器级自定义设备名：换用户登录也沿用
        val deviceName = localDeviceName()
        // 加密开启时用有效密码（用户没填就是内置默认密码）；关闭时空串走明文
        val syncPassword = com.clipsync.crypto.PayloadCipher.effectivePassword(this)
        // 本机同步开关，上报给服务端用于在线列表展示
        val caps = buildCaps()
        val wsClient = WsClient(
            server, token, deviceID, syncPassword, caps, deviceName, localPhoneNumber()
        ).also { it.start() }
        ws = wsClient
        currentWs = wsClient

        // 预热密钥：派生一次要 2 秒多，放到后台先算好，免得第一条消息发送时
        // 在业务线程上顶着这段延迟（PayloadCipher 内部按密码缓存结果）
        if (syncPassword.isNotEmpty()) {
            serviceScope.launch(Dispatchers.Default) {
                com.clipsync.crypto.PayloadCipher.keyFor(syncPassword)
                Log.i("ClipSync", "🔑 端到端加密密钥已就绪")
            }
        }

        // 绑定 ws 客户端，但不要重复 init（MainActivity 已经初始化过 clipboardManager）
        ClipboardManagerHelper.bindWs(wsClient)
        ClipboardManagerHelper.startListening()
        ClipSyncAccessibilityService.wsClient = wsClient

        subscribeIncoming(wsClient)
        subscribeState(wsClient)
        subscribePresence(wsClient)
        subscribeAuthFailure(wsClient, server)
    }

    /** 订阅在线设备列表，转发给 PresenceBus 供 MainActivity 展示；其他设备上下线弹通知 */
    private fun subscribePresence(wsClient: WsClient) {
        serviceScope.launch {
            var knownOthers: Map<String, com.clipsync.model.OnlineDevice>? = null
            wsClient.onlineDevices.collect { devices ->
                com.clipsync.state.PresenceBus.publish(devices)

                // 本机名可能从其他端被修改：从在线列表里找到本机，同步到机器级存储
                devices.firstOrNull { it.self }?.let { self ->
                    val n = self.name.trim()
                    if (n.isNotEmpty() && n != localDeviceName()) saveLocalDeviceName(n)
                }

                val newOthers = devices.filter { !it.self }.associateBy { it.deviceId }
                val prev = knownOthers
                if (prev != null) {
                    val cameOnline = newOthers.keys - prev.keys
                    val wentOffline = prev.keys - newOthers.keys
                    cameOnline.forEach { id -> newOthers[id]?.let { showPresenceNotification(it, online = true) } }
                    wentOffline.forEach { id -> prev[id]?.let { showPresenceNotification(it, online = false) } }
                }
                knownOthers = newOthers
            }
        }
    }

    private fun showPresenceNotification(device: com.clipsync.model.OnlineDevice, online: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureAlertsChannel()
        val title = if (online) "设备上线" else "设备下线"
        // 有自定义名就显示「名字 · 平台（短ID）」，没名字才 fallback 到「平台 · 短ID」
        val customName = device.name.trim()
        val body = if (customName.isNotEmpty()) {
            "$customName · ${device.platformLabel}（${device.shortId}）"
        } else {
            "${device.platformLabel} · ${device.shortId}"
        }
        val action = if (online) "已连接" else "已断开"
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setContentTitle(title)
            .setContentText("$body $action")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        val notifId = PRESENCE_NOTIF_BASE + (device.deviceId.hashCode() and 0x7fffffff) % 500
        nm.notify(notifId, notif)
    }

    /**
     * token 失效（WS 握手被 401 拒）时自动重新登录一次。
     *
     * 密码存在本地，所以这里能悄悄换一个新 token 接着连，用户不用管。
     * 若重新登录也失败（比如密码被改了），就停在未连接状态等用户去改设置。
     */
    private fun subscribeAuthFailure(wsClient: WsClient, server: String) {
        authJob?.cancel()
        authJob = serviceScope.launch {
            wsClient.authFailed.collect { failed ->
                if (!failed) return@collect
                Log.w("ClipSync", "🔒 token 失效，尝试用已保存的账号密码重新登录")
                AuthClient.clearSession(this@SyncService)
                if (!AuthClient.hasCredentials(this@SyncService)) {
                    stopWithReason("登录已失效，请到设置里填写用户名和密码")
                    return@collect
                }
                val fresh = when (val r = AuthClient.ensureToken(this@SyncService, server)) {
                    is AuthClient.TokenResult.Ok -> r.token
                    is AuthClient.TokenResult.Rejected -> {
                        // 密码被改 → 标记 kicked，让 UI 显示修改密码按钮
                        val reason = r.reason
                        if (reason.contains("密码") || reason.contains("password") ||
                            reason.contains("用户名或密码")
                        ) {
                            ws?.kickStop = true
                            ws?.stop()
                            ws = null
                            currentWs = null
                            ConnectionBus.publishKicked("密码已失效，请修改密码后重新连接")
                        } else {
                            stopWithReason("登录失败：$reason，请到设置里检查用户名和密码")
                        }
                        return@collect
                    }
                    is AuthClient.TokenResult.Unreachable -> {
                        stopWithReason("连接失败：${r.reason}")
                        return@collect
                    }
                    is AuthClient.TokenResult.MissingCredentials -> {
                        stopWithReason("请先在设置里填写用户名和密码")
                        return@collect
                    }
                }
                // 换掉旧连接，用新 token 重新建立
                ws?.stop()
                ws = null
                currentWs = null
                openWs(server, fresh)
            }
        }
    }

    /** 订阅 WS 状态，实时更新通知栏 + 广播给 UI */
    private fun subscribeState(wsClient: WsClient) {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            // StateFlow 会立即发射当前值（初始为 CLOSED），
            // 这会让 UI 误判"连接失败"。用 dropWhile 跳过首个 CLOSED。
            var seenNonClosed = false
            wsClient.state.collect { s ->
                if (!seenNonClosed) {
                    if (s == WsClient.State.CLOSED) {
                        // 还没进入 CONNECTING/OPEN 前的 CLOSED 都是初始值，忽略
                        return@collect
                    }
                    seenNonClosed = true
                }
                val state = when (s) {
                    WsClient.State.OPEN -> ConnectionBus.STATE_OPEN
                    WsClient.State.CONNECTING -> ConnectionBus.STATE_CONNECTING
                    WsClient.State.CLOSED -> ConnectionBus.STATE_CLOSED
                }
                ConnectionBus.publish(state)
                // 更新通知栏文案
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(state))

                // 连上后补发暂存短信：短暂拿锁保证补发动作在息屏时跑完（15s 超时自动释放）。
                // 后台常驻不再依赖 WakeLock，而靠前台服务 + 电池白名单维持长连接。
                if (s == WsClient.State.OPEN) {
                    acquireBriefWakeLock("FlushSms", 15_000L)
                    flushPendingSms()
                }

                // 断线不杀服务：WsClient 内部有指数退避自动重连，
                // 回前台时 MainActivity 会 kick 加速重连
                if (s == WsClient.State.CONNECTING || s == WsClient.State.OPEN) {
                    hasBeenConnectingOrOpen = true
                }
            }
        }
    }

    /**
     * 连接就绪后补发磁盘上的待发短信（冷启动 / 半开重连后都会调）。
     *
     * 短信在发送前已落盘（PendingSmsQueue），这里只负责把磁盘条目用**稳定 id**
     * 发出去并记入 inflight；不立即删盘。75s 后链路仍双向通畅才确认删除，
     * 这样半开连接下"发出去其实丢了"的短信重连后会自动补发，且 id 不变，
     * PC 端按消息 id 去重，不会重复弹窗。
     */
    private fun flushPendingSms() {
        val wsClient = ws ?: return
        if (wsClient.state.value != WsClient.State.OPEN) return
        val items = com.clipsync.sms.PendingSmsQueue.load(this)
        if (items.isEmpty()) return
        var ok = 0
        val now = System.currentTimeMillis()
        synchronized(inflightSms) {
            items.forEach { item ->
                val sent = wsClient.send(
                    type = MessageType.NOTIFY_PC,
                    payloadText = item.text,
                    mime = "text/plain",
                    preview = item.preview,
                    kind = "sms_code",
                    smsSender = item.sender,
                    smsReceiver = localPhoneNumber(),
                    id = item.id
                )
                if (sent) {
                    ok++
                    inflightSms[item.id] = now
                }
            }
        }
        Log.i("ClipSync", "↑ 待发短信已发出 $ok/${items.size} 条（等待 75s 通畅确认后清盘）")
    }

    /**
     * 短信链路权限/组件守卫，由看门狗每 20s 调一次。
     *
     *  - 通知监听掉绑但总开关还在：自动 requestRebind（幂等）；
     *  - 发现需要用户手动处理的权限（MIUI 通知类短信/后台弹窗、NLS 总开关、电池白名单）：
     *      1) 常驻通知文案追加告警，用户任何时候拉下通知栏都能看到；
     *      2) App 在后台且满足节流条件时，再弹一条可点击通知，直达对应系统设置页；
     *  - 问题全部消失：清除告警状态，立刻通知一次（让常驻通知文案恢复正常）。
     */
    private fun runPermissionGuard() {
        val issues = try {
            com.clipsync.ui.PermissionStatus.smsIssues(this)
        } catch (e: Exception) {
            Log.w("ClipSync", "权限守卫检测异常: ${e.message}")
            return
        }

        // 掉绑自动修复（开关在、服务没连）
        if (issues.any { it.key == com.clipsync.ui.PermissionStatus.KEY_NLS_REBIND }) {
            try {
                com.clipsync.sms.NotificationSmsListener.ensureConnected(this)
            } catch (_: Exception) { /* 下轮再试 */ }
        }

        // 运行时权限缺失在后台无法弹系统授权框，交给首页守卫条；这里只统计手动项
        val manual = issues.filter {
            it.fix == com.clipsync.ui.PermissionStatus.Fix.MANUAL
        }

        val signature = manual.joinToString(",") { it.key }
        val warning = if (manual.isEmpty()) null
        else "短信同步权限缺失（${manual.joinToString("、") { it.label }}），点击处理"

        // 状态变化时刷新常驻通知
        if (warning != permWarningText || issues.size != lastPermIssueCount) {
            permWarningText = warning
            lastPermIssueCount = issues.size
            refreshForegroundNotification()
        }

        if (manual.isEmpty()) {
            // 恢复正常：撤掉残留的权限提醒通知，并重置节流
            getSystemService(NotificationManager::class.java)?.cancel(PERM_NOTIF_ID)
            lastPermAlertAt = 0L
            lastAlertedSignature = null
            return
        }

        // App 在前台：首页守卫条已在提示，不弹通知骚扰
        if (com.clipsync.state.AppForeground.isForeground) return

        val now = System.currentTimeMillis()
        val newIssueAppeared = signature != lastAlertedSignature
        val throttlePassed = now - lastPermAlertAt >= PERM_ALERT_INTERVAL_MS
        if (!newIssueAppeared && !throttlePassed) return
        lastPermAlertAt = now
        lastAlertedSignature = signature
        postPermissionAlert(manual)
    }

    private var lastAlertedSignature: String? = null

    /** 弹一条可点击的权限提醒通知，点击直达首要问题的系统设置页 */
    private fun postPermissionAlert(
        manual: List<com.clipsync.ui.PermissionStatus.SmsIssue>
    ) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureAlertsChannel()
        val top = manual.first()

        // 优先直达具体设置页；拿不到 intent 就退到打开 App 首页（守卫条承接）
        val direct = com.clipsync.ui.PermissionStatus.manualFixIntent(this, top.key)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val clickIntent = direct ?: Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, PERM_NOTIF_ID, clickIntent, piFlags)

        val more = if (manual.size > 1) "等 ${manual.size} 项" else ""
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setContentTitle("ClipSync 短信同步可能失效")
            .setContentText("请开启「${top.label}」$more，否则验证码无法同步到电脑")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(manual.joinToString("\n") { "· ${it.label}：${it.why}" })
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        nm.notify(PERM_NOTIF_ID, notif)
    }

    /** 用当前连接状态 + 权限告警摘要重建常驻通知 */
    private fun refreshForegroundNotification() {
        val state = ws?.let { w ->
            when (w.state.value) {
                WsClient.State.OPEN -> ConnectionBus.STATE_OPEN
                WsClient.State.CONNECTING -> ConnectionBus.STATE_CONNECTING
                else -> ConnectionBus.STATE_CLOSED
            }
        } ?: ConnectionBus.STATE_CLOSED
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    /**
     * 短信投递确认 + 半开连接看门狗。
     *
     * 每 20s 跑一次：
     * 1. OPEN 时发一帧应用层 ping；收到服务端 pong 或任何数据帧即把未应答计数清零。
     * 2. 连续 3 个 ping（≈60s）都收不到任何回应 → 判定为半开连接，主动断掉重建。
     *    不用"墙钟 idle 时长"判定：息屏 Doze 时 CPU 被冻结，墙钟会把整个休眠
     *    时长算进去造成误重连（表现为 Mac 反复弹"设备上线"）；而真实半开连接
     *    在唤醒后发 ping 照样收不到 pong，计数判据能正确抓到、又不会误伤休眠。
     * 3. 重连带 90s 最小间隔退避，避免抖动期反复触发服务端上下线广播。
     * 4. 发出满 75s、且当前链路仍双向通畅的短信，从磁盘待发队列确认删除。
     */
    private fun startSmsWatchdog() {
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(20_000L)
                if (destroyed) break
                // 短信链路权限/组件守卫：与连接状态无关，连接中也要监测
                runPermissionGuard()
                val wsClient = ws ?: continue
                // 短信数据库轮询兜底：MIUI 上 SMS 广播被拦、短信通知不投递给第三方监听，
                // 但 READ_SMS 能读到数据库。发现新短信先落盘，OPEN 时立即补发。
                // 放在连接状态判断之前：断连期间也要落盘，重连后 flush 会一起发。
                var newCount = 0
                try {
                    newCount = com.clipsync.sms.SmsDatabasePoller.pollNewSms(this@SyncService)
                } catch (e: Exception) {
                    Log.w("ClipSync", "短信轮询异常: ${e.message}")
                }
                if (newCount > 0) {
                    Log.i("ClipSync", "🔁 轮询发现 $newCount 条新短信已落盘")
                    if (wsClient.state.value == WsClient.State.OPEN) flushPendingSms()
                }
                if (wsClient.state.value != WsClient.State.OPEN) {
                    synchronized(inflightSms) { inflightSms.clear() }
                    continue
                }
                wsClient.sendAppPing()
                // 连续未应答 ping 达阈值，且距上次强制重连已过退避窗口，才重连
                if (wsClient.isLikelyHalfOpen(threshold = 3)) {
                    val sinceLast = System.currentTimeMillis() - wsClient.lastForceReconnectAt
                    if (wsClient.lastForceReconnectAt == 0L || sinceLast >= 90_000L) {
                        Log.w("ClipSync", "🔍 看门狗：连续 ${wsClient.missedPings} 个 ping 无应答，疑似半开连接，强制重连")
                        synchronized(inflightSms) { inflightSms.clear() }
                        // 关掉这条半死不活的连接，WsClient 的重连循环立刻建新连；
                        // 新连接 OPEN 后 flushPendingSms 会用同一 id 补发磁盘上的短信
                        wsClient.forceReconnect()
                    } else {
                        Log.i("ClipSync", "🔍 看门狗：疑似半开但距上次重连仅 ${sinceLast / 1000}s，跳过（退避中）")
                    }
                    continue
                }
                // 确认投递：发出满 75s 且链路一直通（最近能收到帧/pong）
                val deadline = System.currentTimeMillis() - 75_000L
                val confirmed = synchronized(inflightSms) {
                    val done = inflightSms.filterValues { it <= deadline }.keys
                    inflightSms.keys.removeAll(done)
                    done
                }
                if (confirmed.isNotEmpty()) {
                    com.clipsync.sms.PendingSmsQueue.removeByIds(this@SyncService, confirmed)
                    Log.i("ClipSync", "✅ ${confirmed.size} 条短信已确认送达并清盘")
                }
            }
        }
    }

    /** 收到消息 → 自动写入本机剪贴板 + 存历史 */
    private fun subscribeIncoming(wsClient: WsClient) {
        incomingJob?.cancel()
        incomingJob = serviceScope.launch {
            wsClient.incoming.collect { msg ->
                // 服务端踢下线通知：停止重连，提示用户
                if (msg.type == MessageType.SERVER_KICK) {
                    handleServerKick(msg)
                    return@collect
                }
                // 息屏/Doze 下收到下行消息：短暂唤醒 CPU 保证剪贴板写入/通知能跑完，
                // 10s 超时自动释放，避免常驻 WakeLock 带来的耗电杀进程
                acquireBriefWakeLock("IncomingMsg", 10_000L)
                // Mac 端可能会用 clipboard / clipboard_text / clipboard_image 三种 type
                val isClipboardMsg = msg.type == MessageType.CLIPBOARD ||
                    msg.type.startsWith("clipboard")
                when {
                    isClipboardMsg -> handleClipboardIn(msg)
                    msg.type == MessageType.NOTIFY_MOBILE ||
                        msg.type == MessageType.NOTIFY_ALL -> handleGenericIn(msg)
                    else -> handleGenericIn(msg)
                }
            }
        }
    }

    private fun handleClipboardIn(msg: com.clipsync.model.Message) {
        val text = msg.payload.text.orEmpty()
        val isImage = msg.payload.mime?.startsWith("image/") == true ||
            msg.payload.kind == "image" ||
            msg.type == "clipboard_image"

        if (isImage) {
            val data = msg.payload.data
            if (data.isNullOrEmpty()) {
                Log.w("ClipSync", "↓ 收到图片但 data 为空，跳过")
                return
            }
            Log.i("ClipSync", "↓ 收到图片 (${data.length / 1024}KB base64)")
            val fileName = com.clipsync.clipboard.ClipboardImageStore.saveBase64(
                this, data, msg.payload.mime
            )
            if (fileName != null) {
                com.clipsync.clipboard.ClipboardImageStore.markReceived(fileName)
            }
            if (fileName == null) {
                Log.w("ClipSync", "✗ 图片落盘失败，仅记录历史")
                HistoryStore.addClip(
                    this,
                    HistoryStore.HistoryItem(
                        id = msg.id,
                        kind = "image",
                        text = "",
                        preview = msg.payload.preview ?: "[图片]",
                        direction = "in",
                        ts = msg.ts
                    )
                )
                return
            }
            HistoryStore.addClip(
                this,
                HistoryStore.HistoryItem(
                    id = msg.id,
                    kind = "image",
                    text = "",
                    preview = msg.payload.preview ?: "[图片]",
                    direction = "in",
                    ts = msg.ts,
                    imageName = fileName
                )
            )
            if (ClipboardManagerHelper.autoApplyEnabled) {
                com.clipsync.clipboard.ClipboardManagerHelper.suppressNext()
                val ok = com.clipsync.clipboard.ClipboardImageStore.writeToClipboard(this, fileName)
                android.widget.Toast.makeText(
                    this,
                    if (ok) "收到图片，已复制到剪贴板" else "收到图片，可在历史中查看",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                android.widget.Toast.makeText(this, "收到图片，可在历史中查看", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (text.isEmpty()) return
        Log.i("ClipSync", "↓ 收到剪贴板: ${text.take(40)}")

        // 存历史
        HistoryStore.addClip(
            this,
            HistoryStore.HistoryItem(
                id = msg.id,
                kind = msg.payload.kind ?: "text",
                text = text,
                preview = msg.payload.preview ?: text.take(30),
                direction = "in",
                ts = msg.ts
            )
        )

        if (ClipboardManagerHelper.autoApplyEnabled) {
            // 先标记远端文本，防止写入剪贴板后被监听器重新上传（回环）
            ClipboardManagerHelper.markRemoteText(text)
            val a11y = ClipSyncAccessibilityService.instance
            if (a11y != null) a11y.applyRemoteText(text)
            else ClipboardManagerHelper.applyRemoteText(text)
        }
    }

    private fun handleGenericIn(msg: com.clipsync.model.Message) {
        // 兜底：其他类型消息也进剪贴板历史，方便查看
        val text = msg.payload.text ?: msg.payload.preview ?: return
        HistoryStore.addClip(
            this,
            HistoryStore.HistoryItem(
                id = msg.id,
                kind = msg.payload.kind ?: msg.type,
                text = text,
                preview = msg.payload.preview ?: text.take(30),
                direction = "in",
                ts = msg.ts
            )
        )
    }

    /**
     * 收到服务端踢下线通知：停止 WS 重连，清除本地 token，
     * 标记 kicked 让 UI 根据 reason 显示不同提示。
     */
    private fun handleServerKick(msg: Message) {
        val reason = msg.payload.reason?.ifEmpty {
            // 旧服务端没带 reason，按密码重置处理
            ConnectionBus.KICKED_PASSWORD_RESET
        } ?: ConnectionBus.KICKED_PASSWORD_RESET
        Log.w("ClipSync", "👢 收到服务端踢下线通知 reason=$reason")
        ws?.kickStop = true
        ws?.stop()
        ws = null
        currentWs = null
        AuthClient.clearSession(this)
        ConnectionBus.kicked(reason)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(ConnectionBus.STATE_CLOSED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null, ACTION_CONNECT -> {
                // 服务活着但没连上（比如上次换 token 时没网）：这里重试一次。
                // 少了这一步，用户点「启动」会打到一个什么都不做的 onStartCommand，
                // 界面就永远停在"连接中"。
                if (ws == null) {
                    Log.i("ClipSync", "↻ 收到连接请求，重新尝试建立连接")
                    connectWs()
                }
            }
            ACTION_FLUSH_PENDING_SMS -> {
                // 短信在接收侧（SmsReceiver / 通知监听）已落盘，这里只管补发。
                // 冷启动时换 token + 握手可能耗时十几秒，期间 Doze 下 CPU 可能睡着，
                // 从入口先持一把 30s 的锁覆盖整个建连过程。
                acquireBriefWakeLock("FlushSmsCold", 30_000L)
                val wsClient = ws
                when {
                    wsClient == null -> {
                        // ws 还没初始化：connectWs -> 连接 OPEN -> flushPendingSms 自动发
                        Log.i("ClipSync", "📤 短信已在磁盘队列，等待连接就绪后补发")
                        connectWs()
                    }
                    wsClient.state.value == WsClient.State.OPEN -> {
                        // 已经连上，立刻发（发完留在磁盘上，看门狗确认 75s 通畅后才清盘）
                        flushPendingSms()
                    }
                    else -> {
                        // 连接中/退避中：等 OPEN 后 flushPendingSms 自动触发
                        Log.i("ClipSync", "📤 短信已在磁盘队列，连接中稍后补发")
                        wsClient.kick()
                    }
                }
            }
            ACTION_SEND_SMS_CODE -> {
                // 兼容旧入口（现在通知监听已直接落盘并发 FLUSH，这个分支理论上不再走）。
                // 统一落盘后交给 FLUSH 流程，保证不丢。
                acquireBriefWakeLock("SendSms", 30_000L)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: "短信验证码"
                com.clipsync.sms.PendingSmsQueue.enqueue(this, text, preview)
                if (ws == null) connectWs() else flushPendingSms()
                Log.i("ClipSync", "↑ 上传短信: ${text.take(40)} → 已落盘补发")
            }
            ACTION_SEND_SHARE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                val data = intent.getStringExtra(EXTRA_DATA)
                val mime = intent.getStringExtra(EXTRA_MIME) ?: "text/plain"
                val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: "分享内容"
                val kind = if (mime.startsWith("image/")) "image" else "text"
                Log.i("ClipSync", "↑ 上传分享: ${text?.take(40)}")
                ws?.send(
                    type = MessageType.CLIPBOARD,
                    payloadText = text,
                    payloadData = data,
                    mime = mime,
                    preview = preview,
                    kind = kind
                )
                if (!text.isNullOrEmpty()) {
                    HistoryStore.addClip(
                        this,
                        HistoryStore.HistoryItem(
                            id = UUID.randomUUID().toString(),
                            kind = kind,
                            text = text,
                            preview = preview,
                            direction = "out",
                            ts = System.currentTimeMillis() / 1000
                        )
                    )
                } else if (kind == "image" && !data.isNullOrEmpty()) {
                    val fileName = com.clipsync.clipboard.ClipboardImageStore.saveBase64(
                        this, data, mime
                    )
                    HistoryStore.addClip(
                        this,
                        HistoryStore.HistoryItem(
                            id = UUID.randomUUID().toString(),
                            kind = "image",
                            text = "",
                            preview = preview,
                            direction = "out",
                            ts = System.currentTimeMillis() / 1000,
                            imageName = fileName ?: ""
                        )
                    )
                }
            }
        }
        return START_STICKY
    }

    /**
     * 任务被划掉时回调。
     *
     * 用户在最近任务里把 ClipSync 滑掉 → 系统回调这里。区别于 Home 键的
     * onPause / onStop（后者只是切后台，服务要继续保活）。划掉 = 不用了，
     * 这里要立即 stopSelf + 把 sync_enabled 置 false，否则下次短信又会拉起服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("ClipSync", "⛔ 任务被划掉，停止同步服务")
        setUserEnabled(this, false)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i("ClipSync", "⚪ 同步服务已停止")
        // 先立标记：让还在飞的登录请求回来后不要再建连
        destroyed = true
        serviceAlive = false
        connecting = false
        networkCallback?.let { cb ->
            runCatching {
                getSystemService(android.net.ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        incomingJob?.cancel()
        stateJob?.cancel()
        authJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        ws?.stop()
        ws = null
        currentWs = null
        // 短时 WakeLock 都带超时自动释放，服务销毁时无需手动 release
        // 保留已有的失败原因（stopWithReason 刚设过），用户主动停止时它是 null
        ConnectionBus.publish(ConnectionBus.STATE_CLOSED, ConnectionBus.failureReason)
        super.onDestroy()
    }

    // MARK: - 通知构建

    private fun startForeground(notif: Notification) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "同步服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "ClipSync 后台常驻通知，保持消息同步在线"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun ensureAlertsChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "设备上下线提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "其他设备上线或下线时的提醒"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(state: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val stateText = when (state) {
            ConnectionBus.STATE_OPEN -> "已连接"
            ConnectionBus.STATE_CONNECTING -> "连接中…"
            else -> "未连接"
        }

        val contentText = permWarningText ?: "点击打开应用"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipSync · $stateText")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
        // 权限告警文案较长时展开常驻通知
        if (permWarningText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        }
        return builder.build()
    }
}
