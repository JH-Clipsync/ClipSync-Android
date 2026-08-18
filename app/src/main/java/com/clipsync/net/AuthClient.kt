package com.clipsync.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 用用户名 + 密码换 token，取代原来"手填 Token"的流程。
 *
 * 客户端没有登录 / 注册按钮：账号密码存在本地，连接时自动换 token。
 * 账号由管理员在服务端创建（后续做后台管理界面）。
 *
 * 服务端行为：
 *  - 当前账号没有客户端在线 → 新签发 token
 *  - 已有客户端在线 → 返回同一个 token（reused = true）
 * 所以手机和电脑各自登录同一账号，就会自动落进同一个同步分组。
 */
object AuthClient {
    private const val PREFS = "clipsync"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 登录成功后的会话信息 */
    data class Session(
        val token: String,
        val userId: Long,
        val username: String,
        val expiresAt: String?,
        /** true = 复用了已在线客户端的 token */
        val reused: Boolean,
        /** 服务端是否强制要求端到端加密 */
        val e2eeRequired: Boolean,
        val onlineDevices: Int
    )

    /** 业务失败（凭据错误、注册关闭等）带上服务端给的中文提示 */
    class AuthException(message: String) : Exception(message)

    /**
     * 换 token 的结果。失败时带上一句能直接展示给用户的原因，
     * 让"账密不对"和"网络不通"在界面上区分得开。
     */
    sealed interface TokenResult {
        data class Ok(val token: String) : TokenResult
        /** 账号密码没填全 */
        data object MissingCredentials : TokenResult
        /** 服务端明确拒绝（账号或密码错误、账号被禁用等） */
        data class Rejected(val reason: String) : TokenResult
        /** 连不上服务端（网络不通、地址错误、服务未启动） */
        data class Unreachable(val reason: String) : TokenResult
    }

    /**
     * 把 ws:// / wss:// 转成 http:// / https://。
     * 设置页里填的是 WebSocket 地址，认证接口走同一端口的 HTTP。
     */
    fun httpBase(serverUrl: String): String {
        var s = serverUrl.trim().removeSuffix("/")
        return when {
            s.startsWith("wss://") -> "https://" + s.removePrefix("wss://")
            s.startsWith("ws://") -> "http://" + s.removePrefix("ws://")
            s.startsWith("http://") || s.startsWith("https://") -> s
            else -> "http://$s"
        }
    }

    suspend fun login(serverUrl: String, username: String, password: String): Session =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
            val json = post(serverUrl, "/auth/login", body, token = null)
            val token = json.optString("token")
            if (token.isEmpty()) throw AuthException("响应缺少 token")
            Session(
                token = token,
                userId = json.optLong("user_id"),
                username = json.optString("username", username),
                expiresAt = json.optString("expires_at").ifEmpty { null },
                reused = json.optBoolean("reused", false),
                e2eeRequired = json.optBoolean("e2ee_required", false),
                onlineDevices = json.optInt("online_devices", 0)
            )
        }

    /** GET /auth/session —— 启动时确认本地 token 还有效 */
    suspend fun checkSession(serverUrl: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(httpBase(serverUrl) + "/auth/session")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            runCatching {
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrElse {
                Log.w("ClipSync", "✗ 会话检查失败: ${it.message}")
                false
            }
        }

    suspend fun logout(serverUrl: String, token: String) = withContext(Dispatchers.IO) {
        runCatching { post(serverUrl, "/auth/logout", JSONObject(), token) }
        Unit
    }

    /**
     * POST /device/name —— 给指定设备设置自定义名称。
     * 服务端落库后会实时广播 presence，所有在线端立即看到新名称。
     */
    suspend fun renameDevice(serverUrl: String, token: String, deviceId: String, name: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("name", name)
            post(serverUrl, "/device/name", body, token)
            Unit
        }

    // ===== 本地会话存取 =====

    fun savedToken(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun savedUsername(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("username", "") ?: ""

    /**
     * 账号密码本地保存，连接时自动用它换 token，所以不需要"登录"按钮。
     *
     * 注意：这里存的是登录密码，和端到端加密用的"同步密码"是两码事——
     * 后者永远不出设备，前者要发给服务端校验。
     */
    fun savedPassword(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("password", "") ?: ""

    fun saveCredentials(ctx: Context, username: String, password: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    /** 账号密码都填了才能自动登录 */
    fun hasCredentials(ctx: Context): Boolean =
        savedUsername(ctx).isNotEmpty() && savedPassword(ctx).isNotEmpty()

    fun saveSession(ctx: Context, session: Session) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", session.token)
            .putString("username", session.username)
            .apply()
    }

    fun clearSession(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("token")
            .apply()
    }

    fun isLoggedIn(ctx: Context): Boolean = savedToken(ctx).isNotEmpty()

    /**
     * 连接前拿一个可用 token：本地有就直接用，没有就用保存的账号密码登录。
     *
     * 只在本地没有 token 时才打 /auth/login，避免每次重连都去撞登录限流；
     * token 失效的场景由 WS 握手返回 401 触发 [clearSession] + 重连来兜住。
     *
     * 失败时区分"账密被拒"和"连不上服务端"，让上层能给出准确提示。
     */
    suspend fun ensureToken(ctx: Context, serverUrl: String): TokenResult {
        savedToken(ctx).takeIf { it.isNotEmpty() }?.let { return TokenResult.Ok(it) }

        val username = savedUsername(ctx)
        val password = savedPassword(ctx)
        if (username.isEmpty() || password.isEmpty()) {
            Log.w("ClipSync", "✗ 未填写账号密码，无法连接")
            return TokenResult.MissingCredentials
        }
        return try {
            val session = login(serverUrl, username, password)
            saveSession(ctx, session)
            val how = if (session.reused)
                "复用在线 Token（${session.onlineDevices} 台设备在线）"
            else
                "已签发新 Token"
            Log.i("ClipSync", "🔑 连接前自动登录成功：$how")
            TokenResult.Ok(session.token)
        } catch (e: AuthException) {
            // 服务端答复了，只是不认这套凭据
            Log.w("ClipSync", "✗ 登录被拒: ${e.message}")
            TokenResult.Rejected(e.message ?: "账号或密码不正确")
        } catch (e: Exception) {
            Log.w("ClipSync", "✗ 无法连接服务器: ${e.message}")
            TokenResult.Unreachable(describeNetworkError(e))
        }
    }

    /**
     * 把 OkHttp / JDK 抛出的网络异常翻成一句用户能看懂的话。
     * 异常类名对用户毫无意义，但"服务器地址填错了"和"没连上网"是他能处理的。
     */
    fun describeNetworkError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "找不到服务器，请检查地址和网络连接"
        is java.net.SocketTimeoutException -> "连接服务器超时，请检查网络或服务器是否已启动"
        is java.net.ConnectException -> "服务器拒绝连接，请确认地址、端口和服务是否已启动"
        is javax.net.ssl.SSLException -> "TLS 握手失败，请确认服务器证书配置"
        else -> e.message?.takeIf { it.isNotBlank() }?.let { "网络错误：$it" }
            ?: "网络不可用，请检查网络连接"
    }

    // ===== 内部 =====

    private fun post(
        serverUrl: String,
        path: String,
        body: JSONObject,
        token: String?
    ): JSONObject {
        val builder = Request.Builder()
            .url(httpBase(serverUrl) + path)
            .post(body.toString().toRequestBody(jsonMedia))
        if (token != null) builder.header("Authorization", "Bearer $token")

        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                val msg = json.optString("error").ifEmpty { "服务端返回 ${resp.code}" }
                throw AuthException(msg)
            }
            return json
        }
    }
}
