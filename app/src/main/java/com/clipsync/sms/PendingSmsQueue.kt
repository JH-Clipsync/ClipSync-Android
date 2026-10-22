package com.clipsync.sms

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * 待发短信的持久化队列。
 *
 * 短信（无论来自 SMS 广播还是通知监听）在进入网络发送前先落盘，
 * 只有在"连接双向通畅"持续 75s 后才把对应条目删掉。这样在息屏 Doze、
 * 半开连接、进程被冻结等场景下短信都不会丢，恢复后自动补发；
 * 每条短信带稳定 id，补发时消息 id 不变，Mac/Windows 端按 id 去重，
 * 补发不会产生重复弹窗。
 */
object PendingSmsQueue {

    private const val PREF = "clipsync_pending_sms"
    private const val KEY = "queue"

    /** 队列上限：超过丢最旧的，避免长期离线无限积压 */
    private const val MAX_ITEMS = 100

    /** 同一号码+正文在此时长内不重复入队（广播和通知监听可能各报一次同一条短信） */
    private const val DEDUP_WINDOW_MS = 60_000L

    data class Item(
        val id: String,
        val text: String,
        val preview: String,
        val ts: Long,
        val sender: String = ""
    )

    /**
     * 入队。自动补 id（旧版无 id 数据在 load 时补齐），并在同号码+正文短时间内去重。
     */
    fun enqueue(context: Context, text: String, preview: String, sender: String = "") {
        val list = load(context).toMutableList()
        val now = System.currentTimeMillis()
        val normalizedSender = sender.trim()
        if (list.any {
                it.text == text &&
                    it.sender == normalizedSender &&
                    now - it.ts < DEDUP_WINDOW_MS
            }
        ) {
            return
        }
        list.add(Item(UUID.randomUUID().toString(), text, preview, now, normalizedSender))
        while (list.size > MAX_ITEMS) list.removeAt(0)
        save(context, list)
    }

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
