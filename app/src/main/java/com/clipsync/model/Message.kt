package com.clipsync.model

import kotlinx.serialization.Serializable

/**
 * 消息类型（传输通道 / 推送范围），跟服务端 TypeXxx 常量一致。
 * 决定服务端把这条消息投递给同 token 下的哪些客户端。
 */
object MessageType {
    /** 只通知 PC 端（例：短信验证码同步到电脑） */
    const val NOTIFY_PC = "notify_pc"
    /** 只通知移动端 */
    const val NOTIFY_MOBILE = "notify_mobile"
    /** 通知所有端 */
    const val NOTIFY_ALL = "notify_all"
    /** 剪贴板同步（广播；接收方按开关决定是否自动写入本机剪贴板） */
    const val CLIPBOARD = "clipboard"
    /** 服务端踢下线通知（密码重置/封禁） */
    const val SERVER_KICK = "server_kick"
    /** 在线设备列表变更（服务端上下线时下发） */
    const val PRESENCE = "presence"
}

/**
 * MessageCategory：消息业务大类。用于日志与 UI 分组，跟 MessageType（推送通道）解耦。
 * 三端约定值：sms / clipboard / notification。
 */
object MessageCategory {
    /** 短信（含验证码） */
    const val SMS = "sms"
    /** 剪切板（文本、图片、分享） */
    const val CLIPBOARD = "clipboard"
    /** 其它通知 */
    const val NOTIFICATION = "notification"

    /** 根据传输类型 + payload.kind 判定业务大类，与服务端 categorize() 保持一致 */
    fun of(msgType: String, kind: String?): String {
        val k = kind.orEmpty()
        return when {
            k.startsWith("sms") -> SMS
            msgType == MessageType.CLIPBOARD || k == "text" || k == "image" || k == "share" -> CLIPBOARD
            else -> NOTIFICATION
        }
    }
}

/**
 * MessageContent：内容格式。三端约定值：text / image。
 */
object MessageContent {
    const val TEXT = "text"
    const val IMAGE = "image"

    /** 根据 payload.kind + payload.mime 判定内容格式，与服务端 contentTypeOf() 保持一致 */
    fun of(kind: String?, mime: String?): String {
        val k = kind.orEmpty()
        val m = mime.orEmpty()
        return if (k == IMAGE || m.startsWith("image/")) IMAGE else TEXT
    }
}

/** 客户端角色 */
object ClientRole {
    const val PC = "pc"
    const val MOBILE = "mobile"
}

@Serializable
data class MessagePayload(
    val text: String? = null,
    val mime: String? = null,
    val data: String? = null,      // base64（图片等二进制）
    val preview: String? = null,   // 短预览
    val kind: String? = null,      // 业务子类型：sms_code / text / image / share ...
    // 短信发送方号码（1069 通道号 / 银行短号 / 个人手机号）。有意留在加密信封外、
    // 以明文随 payload 上行：端到端加密只保护正文，号码是供服务端开放接口筛选的元数据。
    val sender: String? = null,
    // 短信接收方号码（本机接码卡手机号），同样明文留在信封外，供按收端卡号筛选。
    // Android 10+ 读不到本机号，由用户在设置里填写后随短信带出。
    val receiver: String? = null,
    /** 加密信封；非空时 text/data 为空，真实内容在 enc.ct 里 */
    val enc: com.clipsync.crypto.EncEnvelope? = null,
    /** server_kick 消息的原因：password_reset / user_disabled / device_banned ... */
    val reason: String? = null
)

@Serializable
data class Message(
    val id: String,
    val type: String,              // MessageType.*
    val from: String,
    val to: String = "*",
    val ts: Long,
    val payload: MessagePayload
) {
    /** 便捷：直接算出业务大类 */
    val category: String get() = MessageCategory.of(type, payload.kind)

    /** 便捷：直接算出内容格式 */
    val content: String get() = MessageContent.of(payload.kind, payload.mime)
}

/** 在线设备（服务端 presence 消息里的一台设备） */
@Serializable
data class OnlineDevice(
    @kotlinx.serialization.SerialName("device_id") val deviceId: String = "",
    val role: String = "",
    val platform: String = "",
    val name: String = "",
    val ip: String = "",
    @kotlinx.serialization.SerialName("online_at") val onlineAt: Long = 0L,
    val self: Boolean = false,
    val caps: Map<String, Boolean> = emptyMap()
) {
    /** 列表展示名：优先自定义名，否则平台标签 */
    val displayName: String
        get() = name.trim().ifEmpty { platformLabel }
    val roleLabel: String
        get() = when (role) {
            ClientRole.MOBILE -> "手机"
            ClientRole.PC -> "电脑"
            else -> role
        }

    val platformLabel: String
        get() = when (platform.lowercase()) {
            "mac" -> "macOS"
            "windows" -> "Windows"
            "linux" -> "Linux"
            "android" -> "Android"
            "ios" -> "iOS"
            else -> if (role == ClientRole.MOBILE) "手机" else "电脑"
        }

    /** 设备 ID 展示：去掉前缀，取前 8 位 */
    val shortId: String
        get() {
            val id = deviceId
            val body = id.substringAfter("-", id)
            return body.take(8)
        }

    val clipUp: Boolean get() = caps["clip_up"] == true
    val smsIn: Boolean get() = caps["sms_in"] == true
    val autoPut: Boolean get() = caps["auto_put"] == true
}

/** presence 消息的 payload 包装 */
@Serializable
data class PresencePayload(val devices: List<OnlineDevice> = emptyList())

/** presence 消息外层信封 */
@Serializable
data class PresenceEnvelope(
    val type: String = "",
    val payload: PresencePayload = PresencePayload()
)
