package com.clipsync.crypto

import android.content.Context
import android.util.Log
import com.clipsync.model.MessagePayload
import kotlinx.serialization.json.Json

/**
 * 在「业务 payload」和「加密信封」之间来回转换。
 *
 * 发送：MessagePayload(明文) → JSON → AES-GCM → MessagePayload(仅 enc + 占位 preview)
 * 接收：MessagePayload(含 enc) → 解密 → MessagePayload(明文)
 *
 * 密钥按密码缓存，避免每条消息都跑一次 20 万轮 PBKDF2。
 * 对应 Mac 端 E2EEEnvelope.swift 的 PayloadCipher。
 */
object PayloadCipher {
    /** 加密消息在 UI / 日志里的占位文案（不含任何真实内容） */
    const val PLACEHOLDER = "🔒 加密消息"

    private const val PREFS = "clipsync"
    private const val KEY_SYNC_PASSWORD = "sync_password"
    private const val KEY_E2EE_ENABLED = "e2ee_enabled"

    /** 密钥缓存最多留几把（够覆盖"连接在用的"+"设置页正在试的"） */
    private const val MAX_CACHED_KEYS = 4

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /**
     * 已派生密钥缓存，key 是同步密码。
     *
     * 用多槽而不是单槽：设置页一边打字算指纹、连接一边在发消息，单槽会被
     * 打字过程反复挤掉，导致每条消息都重新派生。密钥是密码的纯函数（盐写死
     * 在 E2EECrypto 里），所以缓存不需要失效，只需要限制条数。
     */
    private val keyCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) =
            size > MAX_CACHED_KEYS
    }

    private val lock = Any()

    // ===== 设置读写 =====

    /** 本机同步密码；只存在 SharedPreferences，从不上传。 */
    fun syncPassword(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SYNC_PASSWORD, "") ?: ""

    fun setSyncPassword(ctx: Context, password: String) {
        // 值没变就别动：边打字边调用时，无脑清缓存会让每个按键都重新跑
        // 20 万轮 PBKDF2，输入框直接卡住
        if (syncPassword(ctx) == password) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SYNC_PASSWORD, password).apply()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_E2EE_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_E2EE_ENABLED, enabled).apply()
    }

    /**
     * 实际用来派生密钥的密码。
     *
     * 开关关闭 → 空串（明文传输）；
     * 开关打开但用户没填 → 内置默认密码，避免"开了加密却在发明文"；
     * 开关打开且填了 → 用户自己的密码。
     */
    fun effectivePassword(ctx: Context): String {
        if (!isEnabled(ctx)) return ""
        return syncPassword(ctx).ifEmpty { E2EECrypto.BUILTIN_SYNC_PASSWORD }
    }

    /** 当前是否在用内置默认密码（UI 据此提示用户） */
    fun usingBuiltinPassword(ctx: Context): Boolean =
        isEnabled(ctx) && syncPassword(ctx).isEmpty()

    /** 加密是否生效。开关打开就一定生效——没填密码时走内置默认密码。 */
    fun isActive(ctx: Context): Boolean = isEnabled(ctx)

    // ===== 密钥缓存 =====

    fun keyFor(password: String): ByteArray? {
        if (password.isEmpty()) return null
        synchronized(lock) { keyCache[password] }?.let { return it }
        // 派生放在锁外：单次要跑 20 万轮 PBKDF2（手机上约 2.8 秒），持锁会把
        // 正在发消息的线程一起堵住。并发算同一个密码最多白跑一次，无副作用。
        val derived = E2EECrypto.deriveKey(password) ?: return null
        synchronized(lock) { keyCache[password] = derived }
        return derived
    }

    /** 清空密钥缓存（仅测试 / 排查用；正常运行不需要，密钥是密码的纯函数）。 */
    fun invalidateKeyCache() {
        synchronized(lock) { keyCache.clear() }
    }

    /** 当前密钥指纹，设置页展示用 */
    fun fingerprint(password: String): String? =
        keyFor(password)?.let { E2EECrypto.fingerprint(it) }

    // ===== 发送方向 =====

    /**
     * 把明文 payload 封成密文 payload。
     * 未设置密码时原样返回（服务端 e2ee.require=false 才会接受）。
     */
    fun encrypt(payload: MessagePayload, password: String): MessagePayload {
        val key = keyFor(password) ?: return payload
        val plain = runCatching { json.encodeToString(MessagePayload.serializer(), payload) }
            .getOrNull() ?: return payload
        val env = E2EECrypto.seal(plain.toByteArray(Charsets.UTF_8), key)
        if (env == null) {
            Log.w("ClipSync", "⚠ 加密失败，退回明文发送")
            return payload
        }
        // 信封外保留 kind 和明文凭据号码：
        // - kind：收端解密前分类用；
        // - sender/receiver：服务端开放接口按发送/接收号码筛选，必须在密文外可见；
        // 短信正文仍只存在 enc 中，服务端无法解密。
        return MessagePayload(
            text = null,
            mime = null,
            data = null,
            preview = PLACEHOLDER,
            kind = payload.kind,
            sender = payload.sender?.takeIf { it.isNotBlank() },
            receiver = payload.receiver?.takeIf { it.isNotBlank() },
            enc = env
        )
    }

    // ===== 接收方向 =====

    /** 解密结果：区分"本来就是明文"、"解开了"、"解不开"三种情况 */
    sealed interface Outcome {
        data class Plaintext(val payload: MessagePayload) : Outcome
        data class Decrypted(val payload: MessagePayload) : Outcome
        data class Failed(val fingerprint: String) : Outcome
    }

    fun decrypt(payload: MessagePayload, password: String): Outcome {
        val env = payload.enc ?: return Outcome.Plaintext(payload)
        val key = keyFor(password) ?: return Outcome.Failed(env.fp)
        val plain = E2EECrypto.open(env, key) ?: return Outcome.Failed(env.fp)
        val decoded = runCatching {
            json.decodeFromString(MessagePayload.serializer(), plain.toString(Charsets.UTF_8))
        }.getOrNull() ?: return Outcome.Failed(env.fp)
        return Outcome.Decrypted(decoded.copy(enc = null))
    }
}
