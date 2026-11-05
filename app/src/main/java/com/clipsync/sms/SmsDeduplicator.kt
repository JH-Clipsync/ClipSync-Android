package com.clipsync.sms

/**
 * 短信上传去重器（进程内、短窗口），以三源稳定 id（[SmsFingerprint]）为键：
 *
 * - 不同 id（哪怕同一发件人 15s 内连发两条不同短信）→ 一律放行。旧实现按"发件人单槽"
 *   去重，会吞掉同号连发的第二条验证码；
 * - 同 id 且上一帧已是明文 → 后续通知帧（可能是星号版）抑制；
 * - 同 id 同形态重复帧 → 抑制；
 * - 同 id 但通知帧先到（非明文）、广播明文后到 → 放行文帧完成"补明文"，两帧同 id，
 *   桌面端按 id 去重并保留正文更完整的一帧。
 *
 * 持久化层最终去重在 [PendingSmsQueue]（同 id 同正文不重复落盘、同 id 异正文原地更新），
 * 本类只在入口处减少无谓的落盘与日志。
 */
object SmsDeduplicator {

    private const val WINDOW_MS = 15_000L
    private const val MAX_ENTRIES = 64

    private class Entry(val at: Long, val plaintext: Boolean)

    // LinkedHashMap 按首次插入排序，过期条目从头部淘汰
    private val recent = LinkedHashMap<String, Entry>()

    /**
     * 判断是否允许上传。
     * @param id 三源统一的稳定短信 id（[SmsFingerprint.derive]）
     * @param isPlaintext true = 来自 SMS 广播（明文）；false = 来自通知（可能打码/残缺）
     * @return true = 上传；false = 抑制
     */
    fun shouldUpload(id: String, isPlaintext: Boolean): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        evictExpired(now)

        val prev = recent[id]
        if (prev == null) {
            recent[id] = Entry(now, isPlaintext)
            trimToMax()
            return true
        }
        // 同一条物理短信的后续帧
        if (prev.plaintext) {
            // 明文已经发过：任何后续帧（星号版/重复通知）都没必要再发
            return false
        }
        if (isPlaintext) {
            // 通知版先到、广播明文后到：放行补明文，并把该 id 标记为已有明文
            recent[id] = Entry(now, true)
            return true
        }
        // 同形态的重复通知帧
        return false
    }

    private fun evictExpired(now: Long) {
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.at <= WINDOW_MS) break
            it.remove()
        }
    }

    private fun trimToMax() {
        while (recent.size > MAX_ENTRIES) {
            val it = recent.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }
}
