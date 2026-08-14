package com.clipsync.net

import android.util.Log
import com.clipsync.crypto.PayloadCipher
import com.clipsync.model.ClientRole
import com.clipsync.model.Message
import com.clipsync.model.MessagePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket 长连接客户端：负责连到 ClipSync-Server，发消息、收消息、断线重连。
 * Android 客户端以 role=mobile 注册。
 *
 * 端到端加密：syncPassword 非空时，发送前把 payload 换成加密信封，
 * 收到密文时用同一密码解开。服务端只转发密文，看不到内容。
 */
class WsClient(
    private val serverUrl: String,
    private val token: String,
    private val deviceID: String,
    /** 端到端加密的同步密码；空串表示不加密 */
    private val syncPassword: String = ""
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var ws: WebSocket? = null
    private var running = false
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 收到的消息流 */
    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    val incoming: SharedFlow<Message> = _incoming

    /** 当前账号下的在线设备列表（服务端 presence 推送实时更新） */
    private val _onlineDevices = MutableStateFlow<List<com.clipsync.model.OnlineDevice>>(emptyList())
    val onlineDevices: StateFlow<List<com.clipsync.model.OnlineDevice>> = _onlineDevices

    /** 连接状态流（StateFlow 保证新订阅者立即拿到当前状态） */
    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> = _state

    /** token 失效时置位，UI 据此提示重新登录 */
    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed

    /** 收到解不开的密文（两端密码不一致）时置位 */
    private val _decryptFailed = MutableStateFlow(false)
    val decryptFailed: StateFlow<Boolean> = _decryptFailed

    enum class State { CONNECTING, OPEN, CLOSED }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 长连接
        .build()

    /**
     * 连接未就绪时的消息缓存队列。
     *
     * 必须是进程级共享，不能是实例字段：SyncService 每次重连都会新建一个
     * WsClient，旧实例一被丢掉，攒在里面的离线消息就跟着没了 —— 服务端不在时
     * 收到的短信会被静默吞掉，正是"短信不同步"的成因。
     */
    private val pendingQueue get() = sharedPendingQueue

    fun start() {
        if (running) return
        running = true
        job = scope.launch { loop() }
    }

    fun stop() {
        running = false
        kickRequested = true
        job?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _onlineDevices.value = emptyList()
    }

    /** 回前台时调用：跳过退避等待立即重连 */
    @Volatile
    private var kickRequested = false

    /** 被服务端踢下线后置 true，阻止自动重连 */
    @Volatile
    var kickStop = false

    fun kick() {
        kickRequested = true
    }

    private suspend fun loop() {
        // 断线自动重连：2s 起步指数退避，封顶 30s；连上后重置退避。
        // stop() 时 running=false 退出循环。
        var backoff = 2_000L
        while (running && !kickStop) {
            try {
                connectOnce()
                backoff = 2_000L
            } catch (e: Exception) {
                Log.w("ClipSync", "✗ 连接异常: ${e.message}")
            }
            if (!running) break
            _state.value = State.CONNECTING
            Log.i("ClipSync", "🔁 ${backoff / 1000}s 后自动重连")
            val deadline = System.currentTimeMillis() + backoff
            while (running && System.currentTimeMillis() < deadline) {
                if (kickRequested) break
                kotlinx.coroutines.delay(200)
            }
            kickRequested = false
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        running = false
        _state.value = State.CLOSED
    }

    /**
     * 建立一次 WebSocket 连接，挂起协程直到连接关闭或失败。
     * 使用 CompletableDeferred 替代 Thread.sleep，确保连接失败/关闭后能正确返回并重试。
     */
    private suspend fun connectOnce() {
        val url = "$serverUrl/ws?token=$token&device=$deviceID&role=${ClientRole.MOBILE}"
        val req = Request.Builder().url(url).build()
        _state.value = State.CONNECTING

        val closed = CompletableDeferred<Unit>()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("ClipSync", "🟢 已连接服务器")
                _state.value = State.OPEN
                flushPending()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    // presence 消息的 payload 是 {"devices":[...]}，跟业务消息结构不同，
                    // 先单独解析并更新在线设备列表，不进业务消息流
                    val presence = json.decodeFromString<com.clipsync.model.PresenceEnvelope>(text)
                    if (presence.type == com.clipsync.model.MessageType.PRESENCE) {
                        _onlineDevices.value = presence.payload.devices
                        Log.i("ClipSync", "👥 在线设备更新：${presence.payload.devices.size} 台")
                        return@runCatching
                    }
                }.onFailure { /* 不是 presence，按普通消息处理 */ }
                runCatching {
                    val msg = json.decodeFromString<Message>(text)
                    _incoming.tryEmit(decryptIncoming(msg) ?: return)
                }.onFailure { Log.w("ClipSync", "✗ 消息解析失败: ${it.message}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("ClipSync", "✗ 连接失败: ${t.message}")
                // 服务端在 token 失效时用 401 拒绝 WS 升级
                if (response?.code == 401) {
                    Log.w("ClipSync", "🔒 token 已失效，需要重新登录")
                    _authFailed.value = true
                }
                _state.value = State.CLOSED
                if (!closed.isCompleted) closed.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("ClipSync", "⚪ 连接已断开 (code=$code)")
                if (!closed.isCompleted) closed.complete(Unit)
            }
        })

        // 挂起协程直到连接关闭或失败，然后 loop() 会进入下一轮重试
        try {
            closed.await()
        } catch (_: Exception) {
            // 协程被取消（如 stop() 调用），静默退出
        }
    }

    /**
     * 通用发送方法。
     * @param type 走 MessageType 里的常量：NOTIFY_PC / NOTIFY_MOBILE / NOTIFY_ALL / CLIPBOARD
     * @param kind 业务子类型，比如 "sms_code" / "text" / "image" / "share"
     */
    /** @return true = 立即发出；false = 未连接，已进离线队列，连接恢复后自动发 */
    fun send(
        type: String,
        payloadText: String? = null,
        payloadData: String? = null,
        mime: String? = null,
        preview: String? = null,
        kind: String? = null,
        to: String = "*"
    ): Boolean {
        val plainPayload = MessagePayload(
            text = payloadText,
            data = payloadData,
            mime = mime,
            preview = preview,
            kind = kind
        )
        // 加密开启时，服务端只会看到信封；关闭时保持原有明文行为
        val outgoingPayload = if (syncPassword.isNotEmpty()) {
            PayloadCipher.encrypt(plainPayload, syncPassword)
        } else {
            plainPayload
        }
        val msg = Message(
            id = UUID.randomUUID().toString(),
            type = type,
            from = deviceID,
            to = to,
            ts = System.currentTimeMillis() / 1000,
            payload = outgoingPayload
        )
        val raw = json.encodeToString(msg)
        val sent = ws?.send(raw)
        if (sent != true) {
            val queued = synchronized(pendingQueue) {
                pendingQueue.add(raw)
                // 超上限丢最旧的，保住最近的消息
                while (pendingQueue.size > PENDING_LIMIT) pendingQueue.removeAt(0)
                pendingQueue.size
            }
            Log.w("ClipSync", "⏸ 暂存消息 (未连接): $type，队列 $queued 条")
            return false
        }
        return true
    }

    /** 连接恢复后，把队列里的消息发出去 */
    private fun flushPending() {
        val toSend = synchronized(pendingQueue) {
            if (pendingQueue.isEmpty()) return
            val copy = pendingQueue.toList()
            pendingQueue.clear()
            copy
        }
        // 发不出去的放回队列头部等下次，不能直接丢
        val failed = toSend.filter { ws?.send(it) != true }
        if (failed.isNotEmpty()) {
            synchronized(pendingQueue) { pendingQueue.addAll(0, failed) }
        }
        Log.i("ClipSync", "↑ 重发暂存消息 ${toSend.size - failed.size}/${toSend.size} 条")
    }

    /**
     * 解密收到的消息。
     * @return 可交给上层的明文消息；解不开时返回 null（不把密文塞进历史）
     */
    private fun decryptIncoming(msg: Message): Message? {
        if (msg.payload.enc == null) return msg
        return when (val outcome = PayloadCipher.decrypt(msg.payload, syncPassword)) {
            is PayloadCipher.Outcome.Plaintext -> msg
            is PayloadCipher.Outcome.Decrypted -> {
                _decryptFailed.value = false
                msg.copy(payload = outcome.payload)
            }
            is PayloadCipher.Outcome.Failed -> {
                val local = PayloadCipher.fingerprint(syncPassword) ?: "未设置"
                Log.w(
                    "ClipSync",
                    "✗ 解密失败：对端 key=${outcome.fingerprint} 本机 key=$local（请检查同步密码是否一致）"
                )
                _decryptFailed.value = true
                null
            }
        }
    }

    companion object {
        /** 离线队列上限：超过就丢最旧的，避免长期离线时无限积压 */
        private const val PENDING_LIMIT = 200

        /**
         * 跨实例共享的离线消息队列。
         *
         * 放在 companion 里而不是实例字段，是因为每次重连都会新建 WsClient。
         * 队列跟着实例走的话，服务端不可用期间攒下的短信会在重连时被连带丢弃。
         */
        private val sharedPendingQueue = mutableListOf<String>()
    }
}
