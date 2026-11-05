package com.clipsync.sms

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.clipsync.service.SyncService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 短信数据库轮询兜底。MIUI/HyperOS 上 SMS 广播可能被静默拦截、短信通知可能不投递给
 * 第三方监听，但 App 持有 READ_SMS，库里始终能读到完整原文，定时查库即可兜底。
 *
 * 去重：seen 集合统一存三源稳定 id（[SmsFingerprint] 派生的 UUID），实时源 enqueue 后
 * 通过 [markSeen] 写入同一集合并推进水位，轮询不会把实时源已发的短信二次打捞；
 * 同时保留最大短信 date 水位，兜住同一毫秒到达多条的情况。
 */
object SmsDatabasePoller {
    private const val TAG = "SmsDbPoller"
    private const val PREF = "clipsync_sms_poller"
    private const val KEY_LAST_DATE = "last_sms_date"
    private const val KEY_SEEN = "seen_fingerprints"
    private const val MAX_SEEN = 200

    /** 事件驱动查库延迟：通知回调后 2.5s 给系统写库留时间，再一次性查库 */
    private const val EVENT_POLL_DELAY_MS = 2_500L

    /** 广播反查入库时间的时间窗：只看最近 120s 的收件箱行 */
    private const val BROADCAST_LOOKBACK_MS = 120_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 多次通知只保留一次待发的事件驱动查库（合并） */
    private val eventPollScheduled = AtomicBoolean(false)

    /** 查库重入防护：看门狗轮询与事件驱动查库撞车时后到者跳过 */
    @Volatile
    private var pollInFlight = false

    /** 一条候选新短信及其三源稳定 id */
    private class Candidate(val date: Long, val body: String, val addr: String, val id: String)

    /**
     * 组装上行短信全文。企业短信正文已带【签名】时直接用原文，绝不叠加发件人
     * （否则出现「【火山引擎】【火山引擎】…」）；个人短信（无签名）才补【发件人】。
     */
    fun composeSmsText(sender: String, body: String): String {
        val trimmed = body.trim()
        val alreadySigned = trimmed.startsWith("【") && trimmed.contains("】")
        if (alreadySigned) return trimmed
        val who = sender.ifBlank { "未知" }
        return "【$who】$trimmed"
    }

    /**
     * 通知事件驱动：延迟 [EVENT_POLL_DELAY_MS] 做一次性查库。通知回调（尤其正文打码
     * 直接 return 的场景）不再干等下一轮 20s 轮询；多次通知合并为一次，与看门狗轮询
     * 用 [pollInFlight] 互斥防重入。查到新短信落盘后主动拉起 SyncService 补发。
     */
    fun scheduleEventDrivenPoll(ctx: Context, delayMs: Long = EVENT_POLL_DELAY_MS) {
        val appCtx = ctx.applicationContext
        if (!eventPollScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            eventPollScheduled.set(false)
            val count = try {
                pollNewSms(appCtx)
            } catch (e: Exception) {
                Log.w(TAG, "事件驱动查库异常: ${e.message}")
                0
            }
            if (count > 0) {
                Log.i(TAG, "⚡ 事件驱动查库发现 $count 条新短信，已落盘并触发补发")
                val svc = Intent(appCtx, SyncService::class.java).apply {
                    action = SyncService.ACTION_FLUSH_PENDING_SMS
                }
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        appCtx.startForegroundService(svc)
                    } else {
                        appCtx.startService(svc)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "事件查库后拉起 SyncService 失败: ${e.message}（短信已落盘，看门狗稍后补发）")
                }
            }
        }, delayMs)
    }

    /**
     * 查询晚于水位的新短信，逐条落盘并返回条数。由看门狗定时调用或通知事件触发，
     * 内部完成水位推进与去重，[pollInFlight] 防并发重入。
     */
    fun pollNewSms(ctx: Context): Int {
        if (pollInFlight) {
            Log.i(TAG, "⏭ 上一轮查库尚未结束，跳过本次重入")
            return 0
        }
        pollInFlight = true
        try {
            return doPollNewSms(ctx)
        } finally {
            pollInFlight = false
        }
    }

    private fun doPollNewSms(ctx: Context): Int {
        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var lastDate = pref.getLong(KEY_LAST_DATE, 0L)
        val seen = loadSeen(pref)

        val candidates = ArrayList<Candidate>()
        try {
            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE
            )
            // 只看接收类型；取最近 20 条内存过滤，避免水位丢失时扫全表
            val sort = "${Telephony.Sms.DATE} DESC LIMIT 20"
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.TYPE} = ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                sort
            )?.use { c ->
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx).orEmpty() else ""
                    val addr = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else ""
                    if (body.isBlank()) continue
                    // 首次运行：只建水位到当前最新，不补发历史短信
                    if (lastDate == 0L) {
                        if (date > lastDate) lastDate = date
                        continue
                    }
                    if (date <= lastDate) continue
                    // 与实时源同一套稳定 id：seen 三源共享，命中即说明广播/通知已处理
                    val id = SmsFingerprint.derive(ctx, addr, date, body)
                    if (seen.contains(id)) continue
                    candidates.add(Candidate(date, body, addr, id))
                }
                if (c.count == 0) {
                    Log.w(TAG, "⚠️ 查询返回 0 行（READ_SMS 权限或 ROM 限制？），无法兜底新短信")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询短信数据库失败: ${e.message}")
            return 0
        }

        if (candidates.isEmpty()) {
            // 首次运行也要持久化水位，否则下次会补发老短信
            if (pref.getLong(KEY_LAST_DATE, 0L) == 0L && lastDate > 0L) {
                pref.edit().putLong(KEY_LAST_DATE, lastDate).apply()
            }
            return 0
        }

        // 按时间正序处理，旧的先落盘先发
        var maxDate = lastDate
        candidates.sortedBy { it.date }.forEach { cand ->
            val sender = cand.addr.ifBlank { "未知" }
            val fullText = composeSmsText(sender, cand.body)
            val preview = "来自 $sender 的短信"
            PendingSmsQueue.enqueue(ctx, cand.id, fullText, preview, sender)
            com.clipsync.history.HistoryStore.addSms(
                ctx,
                com.clipsync.history.HistoryStore.HistoryItem(
                    id = java.util.UUID.randomUUID().toString(),
                    kind = "sms",
                    text = fullText,
                    preview = preview,
                    direction = "out",
                    ts = System.currentTimeMillis() / 1000
                )
            )
            seen.add(cand.id)
            if (cand.date > maxDate) maxDate = cand.date
            Log.i(TAG, "↑ 轮询发现新短信（数据库兜底）: ${fullText.take(40)}")
        }

        trimSeen(seen)
        pref.edit()
            .putLong(KEY_LAST_DATE, maxDate)
            .putString(KEY_SEEN, seen.joinToString(","))
            .apply()

        return candidates.size
    }

    /**
     * 实时源（广播/通知）enqueue 后登记同一条短信：稳定 id 写入 seen 并把水位推进到
     * 短信 date，下一轮查库扫到该行也会被 seen 命中跳过，消除"实时源已发、轮询再捞一次"
     * 的重复帧。广播兜底时间戳与 provider DATE 可能有秒级偏差：水位不会越过真实行
     * （行时间通常更晚），行被扫到时仍由 seen 拦截；id 也对不上的极端情况退回旧行为。
     */
    fun markSeen(ctx: Context, id: String, smsDate: Long) {
        try {
            val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val seen = loadSeen(pref)
            val previousDate = pref.getLong(KEY_LAST_DATE, 0L)
            val added = seen.add(id)
            if (!added && smsDate <= previousDate) return
            trimSeen(seen)
            pref.edit()
                .putLong(KEY_LAST_DATE, maxOf(previousDate, smsDate))
                .putString(KEY_SEEN, seen.joinToString(","))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "markSeen 失败: ${e.message}")
        }
    }

    /**
     * 广播路径反查刚入库行的 DATE。AOSP 先写 Telephony provider 再派 SMS_RECEIVED 广播，
     * onReceive 时该行通常已在库。用归一化正文精确匹配（验证码等正文熵足够高），
     * 发件人归一化后辅助校验，取最新一条的 DATE，让广播与查库路径指纹时间戳同源。
     * 查不到（ROM 改写时序 / READ_SMS 缺失）返回 null，调用方退回 timestampMillis。
     */
    fun findInboxDate(ctx: Context, rawSender: String, rawBody: String): Long? {
        val wantBody = SmsFingerprint.normalizeBody(rawBody)
        if (wantBody.isEmpty()) return null
        val wantSender = SmsFingerprint.normalizeSender(rawSender)
        val since = System.currentTimeMillis() - BROADCAST_LOOKBACK_MS
        return try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.ADDRESS),
                "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(since.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 10"
            )?.use { c ->
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                while (c.moveToNext()) {
                    val rowBody = if (bodyIdx >= 0) c.getString(bodyIdx).orEmpty() else ""
                    if (SmsFingerprint.normalizeBody(rowBody) != wantBody) continue
                    val rowAddr = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else ""
                    if (rowAddr.isNotBlank() &&
                        SmsFingerprint.normalizeSender(rowAddr) != wantSender
                    ) {
                        continue
                    }
                    return if (dateIdx >= 0) c.getLong(dateIdx) else null
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "反查短信入库时间失败: ${e.message}")
            null
        }
    }

    /** LinkedHashSet 按插入顺序，超限时移除最老的 */
    private fun trimSeen(seen: LinkedHashSet<String>) {
        while (seen.size > MAX_SEEN) {
            val it = seen.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    private fun loadSeen(pref: android.content.SharedPreferences): LinkedHashSet<String> {
        val raw = pref.getString(KEY_SEEN, "") ?: ""
        val set = LinkedHashSet<String>()
        if (raw.isNotBlank()) raw.split(",").filter { it.isNotBlank() }.forEach { set.add(it) }
        return set
    }
}
