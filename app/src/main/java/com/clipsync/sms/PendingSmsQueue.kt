package com.clipsync.sms

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * 待发短信的持久化队列。短信（广播/通知/查库三源）在网络发送前先落盘，连接双向通畅
 * 满 75s 后才删条目，息屏 Doze、半开连接、进程冻结等场景都不丢，恢复后自动补发；
 * 每条短信带稳定 id（见 [SmsFingerprint]），补发 id 不变，桌面端按 id 去重。
 */
object PendingSmsQueue {

    private const val PREF = "clipsync_pending_sms"
    private const val KEY = "queue"

    /** 队列上限：超过丢最旧的，避免长期离线无限积压 */
    private const val MAX_ITEMS = 100

    /** 旧入口（随机 id）的兜底去重窗口：同号码+正文此时长内不重复入队 */
    private const val DEDUP_WINDOW_MS = 60_000L

    /** 入队结果，供调用方打日志 / 决定后续动作 */
    enum class EnqueueResult { ADDED, UPDATED, DUPLICATE }

    data class Item(
        val id: String,
        val text: String,
        val preview: String,
        val ts: Long,
        val sender: String = ""
    )

    /**
     * 稳定 id 入队（三源统一走这里，id 由 [SmsFingerprint] 派生）。
     *
     * 去重/更新规则：
     * - 队列中已存在同 id 且正文、发件人完全相同 → [EnqueueResult.DUPLICATE]，不落盘；
     * - 同 id 但正文不同（通知残缺/打码版先到、广播明文后到的"补明文"）→
     *   原地替换为更完整的正文、id 保持不变，返回 [EnqueueResult.UPDATED]，
     *   下次 flush 会以同一 id 再发一帧，桌面端按正文完整度覆盖；
     * - 不存在同 id → 新条目落盘。
     */
    fun enqueue(
        context: Context,
        id: String,
        text: String,
        preview: String,
        sender: String = "",
        ts: Long = System.currentTimeMillis()
    ): EnqueueResult {
        val list = load(context).toMutableList()
        val normalizedSender = sender.trim()
        val existingIndex = list.indexOfFirst { it.id == id }
        if (existingIndex >= 0) {
            val old = list[existingIndex]
            if (old.text == text && old.sender == normalizedSender) {
                return EnqueueResult.DUPLICATE
            }
            // 同一条物理短信的内容更新（典型：补明文）。保留原 ts，75s 确认时钟不重置。
            list[existingIndex] = old.copy(
                text = text,
                preview = preview,
                sender = normalizedSender
            )
            save(context, list)
            return EnqueueResult.UPDATED
        }
        // 旧入口（随机 UUID）无法参与指纹去重，保留 60s 原文+发件人窗口兜底
        val now = System.currentTimeMillis()
        if (list.any {
                it.text == text &&
                    it.sender == normalizedSender &&
                    now - it.ts < DEDUP_WINDOW_MS
            }
        ) {
            return EnqueueResult.DUPLICATE
        }
        list.add(Item(id, text, preview, ts, normalizedSender))
        while (list.size > MAX_ITEMS) list.removeAt(0)
        save(context, list)
        return EnqueueResult.ADDED
    }

    /**
     * 旧签名入口：无法提供稳定指纹时（理论上仅遗留的 ACTION_SEND_SMS_CODE 路径）
     * 生成随机 UUID 后走统一入队逻辑。
     */
    fun enqueue(context: Context, text: String, preview: String, sender: String = ""): EnqueueResult =
        enqueue(context, UUID.randomUUID().toString(), text, preview, sender)

    fun load(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                Item(
                    id = obj.optString("id", "").ifEmpty { UUID.randomUUID().toString() },
                    text = obj.optString("text", ""),
                    preview = obj.optString("preview", ""),
                    ts = obj.optLong("ts", 0L),
                    sender = obj.optString("sender", "")
                )
            )
        }
        return out
    }

    /** 确认送达后按 id 删除已发送条目 */
    fun removeByIds(context: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val list = load(context).filterNot { ids.contains(it.id) }
        save(context, list)
    }

    private fun save(context: Context, list: List<Item>) {
        val arr = JSONArray()
        for (it in list) {
            val obj = org.json.JSONObject()
            obj.put("id", it.id)
            obj.put("text", it.text)
            obj.put("preview", it.preview)
            obj.put("ts", it.ts)
            obj.put("sender", it.sender)
            arr.put(obj)
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
