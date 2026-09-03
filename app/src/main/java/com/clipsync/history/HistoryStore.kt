package com.clipsync.history

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 本地历史存储。分别保存短信/剪贴板两类，各自最多 200 条，超出后丢弃最旧。
 * 存储介质：SharedPreferences 里放 JSON 字符串，简单可靠。
 */
object HistoryStore {

    private const val SP_NAME = "clipsync_history"
    private const val KEY_SMS = "sms_history"
    private const val KEY_CLIP = "clip_history"
    private const val MAX_SIZE = 200

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(HistoryItem.serializer())

    @Serializable
    data class HistoryItem(
        val id: String,
        val kind: String,       // "sms" / "text" / "image" ...
        val text: String,       // 主体内容
        val preview: String,    // 短预览
        val direction: String,  // "in" 收到  / "out" 本机发出
        val ts: Long,           // 秒级时间戳
        val imageName: String = "" // 图片相对文件名（clip_images/ 下），非图片为空
    )

    fun addSms(ctx: Context, item: HistoryItem) = append(ctx, KEY_SMS, item)
    fun addClip(ctx: Context, item: HistoryItem) = append(ctx, KEY_CLIP, item)

    fun listSms(ctx: Context): List<HistoryItem> = read(ctx, KEY_SMS)
    fun listClip(ctx: Context): List<HistoryItem> = read(ctx, KEY_CLIP)

    /**
     * 合并短信 + 剪贴板，按时间倒序。
     *
     * 排序前先把 ts 归一到毫秒：本机记录存的是秒，远端消息沿用 msg.ts 存的是毫秒，
     * 直接比大小的话毫秒那批会永远排在最前面，和真实先后无关。
     */
    fun listAll(ctx: Context): List<HistoryItem> {
        val merged = read(ctx, KEY_SMS) + read(ctx, KEY_CLIP)
        return merged.sortedByDescending { millisOf(it.ts) }
    }

    /** 秒级时间戳到 5138 年才会到 13 位，所以 ≥1e12 必然已经是毫秒 */
    fun millisOf(ts: Long): Long = if (ts >= 1_000_000_000_000L) ts else ts * 1000

    fun clearSms(ctx: Context) = write(ctx, KEY_SMS, emptyList())
    fun clearClip(ctx: Context) = write(ctx, KEY_CLIP, emptyList())
    fun clearAll(ctx: Context) {
        clearSms(ctx)
        clearClip(ctx)
    }

    /**
     * 同内容短窗口去重：2 分钟内相同正文 + 相同方向的记录视为同一条。
     *
     * 短信上行有两条路径（SMS 广播 + 通知监听），两者会各自写一次历史；
     * 磁盘待发队列按正文去重挡掉了重复发送，但本地历史在这一层之前写入，
     * 仍可能出现同一条短信两条记录。Mac 端按消息 id 去重，补发的同 id
     * 消息也在这里先按 id 替换，避免历史里堆重复条目。
     */
    private const val DEDUP_WINDOW_MS = 120_000L

    private fun append(ctx: Context, key: String, item: HistoryItem) {
        val list = read(ctx, key).toMutableList()
        val sameIdIdx = list.indexOfFirst { it.id == item.id }
        if (sameIdIdx >= 0) {
            // 1) 同 id 直接替换（消息重发场景）
            list[sameIdIdx] = item
        } else {
            // 2) 短时间窗口内同正文同方向，视为重复，不重复插入
            val itemMs = millisOf(item.ts)
            val dup = list.any {
                it.text.isNotEmpty() && it.text == item.text && it.direction == item.direction &&
                    kotlin.math.abs(millisOf(it.ts) - itemMs) < DEDUP_WINDOW_MS
            }
            if (!dup) list.add(0, item) // 新的放最前面
        }
        while (list.size > MAX_SIZE) list.removeAt(list.size - 1)
        write(ctx, key, list)
    }

    private fun read(ctx: Context, key: String): List<HistoryItem> {
        val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, key: String, list: List<HistoryItem>) {
        val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(key, json.encodeToString(listSerializer, list)).apply()
    }
}
