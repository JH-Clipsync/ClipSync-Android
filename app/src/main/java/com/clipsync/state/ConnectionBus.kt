package com.clipsync.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 连接状态总线：服务（SyncService）和界面（MainActivity 等）之间的唯一桥梁。
 *
 * 服务是唯一能感知 WebSocket 真实状态的地方，但它没有界面；界面想显示状态、
 * 想在"被踢下线"时弹提示，就通过这个单例订阅。
 *
 * 状态用字符串常量表示（[STATE_OPEN] / [STATE_CONNECTING] / [STATE_CLOSED]），
 * 同时在 [stateFlow] 里暴露一个 [State] 枚举流给协程订阅。
 */
object ConnectionBus {

    // 简单字符串状态，MainActivity/SettingsActivity 在用
    const val STATE_OPEN = "connected"
    const val STATE_CONNECTED = "connected"
    const val STATE_CONNECTING = "connecting"
    const val STATE_CLOSED = "closed"

    enum class State { CONNECTED, CONNECTING, DISCONNECTED }

    /**
     * 被踢下线的原因。服务端 server_kick 消息的 payload.reason 映射到这里。
     */
    const val KICKED_PASSWORD_RESET = "password_reset"
    const val KICKED_USER_DISABLED = "user_disabled"
    const val KICKED_USER_DELETED = "user_deleted"
    const val KICKED_DEVICE_BANNED = "device_banned"
    const val KICKED_DEVICE_KICKED = "device_kicked"
    const val KICKED_OTHER = ""

    private val _stateFlow = MutableStateFlow(State.DISCONNECTED)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * 当前状态字符串（STATE_*）。UI 通过它渲染按钮文案。
     */
    val current: String get() = when (_stateFlow.value) {
        State.CONNECTED -> STATE_OPEN
        State.CONNECTING -> STATE_CONNECTING
        State.DISCONNECTED -> STATE_CLOSED
    }

    /**
     * 失败原因（用户可看懂的中文句子）。普通连接失败和被踢下线都写到这里，
     * UI 用它显示在状态提示行。String? 形式给同步代码直接读，
     * [failureReasonFlow] 给协程订阅。
     */
    @Volatile
    var failureReason: String? = null
        private set

    private val _failureReasonFlow = MutableStateFlow<String?>(null)
    val failureReasonFlow: StateFlow<String?> = _failureReasonFlow.asStateFlow()

    /**
     * 被踢下线的原因。服务收到 server_kick 消息时写入；新连接建立成功会清空。
     */
    @Volatile
    var kickedReason: String? = null
        private set

    private val _kickedReasonFlow = MutableStateFlow<String?>(null)
    val kickedReasonFlow: StateFlow<String?> = _kickedReasonFlow.asStateFlow()

    /** 兼容旧 UI：是否处于"被踢下线"状态 */
    val kicked: Boolean get() = kickedReason != null

    /** 旧代码通过 addListener/removeListener 订阅状态变化（只关心状态字符串） */
    fun interface Listener {
        fun onStateChanged(state: String)
    }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyListeners() {
        val s = current
        listeners.forEach { runCatching { it.onStateChanged(s) } }
    }

    fun connected() {
        _stateFlow.value = State.CONNECTED
        failureReason = null
        _failureReasonFlow.value = null
        kickedReason = null
        _kickedReasonFlow.value = null
        notifyListeners()
    }
    fun connecting() {
        _stateFlow.value = State.CONNECTING
        notifyListeners()
    }
    fun disconnected(reason: String? = null) {
        _stateFlow.value = State.DISCONNECTED
        if (reason != null) {
            failureReason = reason
            _failureReasonFlow.value = reason
        }
        notifyListeners()
    }

    /**
     * 发布一个状态变更，可选附带一句失败提示。
     */
    fun publish(state: String, reason: String? = null) {
        when (state) {
            STATE_CONNECTED -> connected()
            STATE_CONNECTING -> connecting()
            STATE_CLOSED -> disconnected(reason)
        }
    }

    /**
     * 被服务端踢下线，携带原因。
     * 兼容旧服务端没有 reason 的情况：默认当成密码重置。
     */
    @JvmOverloads
    fun kicked(reason: String? = KICKED_OTHER) {
        val r = reason ?: KICKED_OTHER
        kickedReason = r
        _kickedReasonFlow.value = r
        val prompt = kickedPrompt(r)
        failureReason = prompt
        _failureReasonFlow.value = prompt
        _stateFlow.value = State.DISCONNECTED
        notifyListeners()
    }

    /**
     * 旧调用方式：直接给一句中文提示。
     */
    fun publishKicked(prompt: String? = null) {
        val reason = when {
            prompt == null -> KICKED_OTHER
            prompt.contains("密码") -> KICKED_PASSWORD_RESET
            else -> KICKED_OTHER
        }
        kicked(reason)
    }

    fun clearKicked() {
        kickedReason = null
        _kickedReasonFlow.value = null
    }

    /**
     * 根据 reason 返回用户能看懂的中文提示。
     */
    fun kickedPrompt(reason: String?): String = when (reason) {
        KICKED_PASSWORD_RESET -> "密码已被管理员重置，请修改密码后重新连接"
        KICKED_USER_DISABLED -> "账号已被管理员禁用，请联系管理员"
        KICKED_USER_DELETED -> "账号已被删除"
        KICKED_DEVICE_BANNED -> "当前设备已被管理员禁用，请联系管理员"
        KICKED_DEVICE_KICKED -> "已被管理员强制下线，可重新连接"
        else -> "已被服务端强制下线，请重新登录"
    }
}
