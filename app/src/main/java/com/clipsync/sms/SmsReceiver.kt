package com.clipsync.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.clipsync.history.HistoryStore
import com.clipsync.service.SyncService
import java.util.UUID

/**
 * 短信接收器：收到短信 → 拼完整短信 → 存本地历史 + 交给 SyncService 上行
 *
 * 关键可靠性设计（解决"划掉 App 后来短信，服务端收不到日志"的问题）：
 * 1. BroadcastReceiver 生命周期只有 ~10s，MIUI/HyperOS 后台启动限制下，startService
 *    可能静默失败；为不丢验证码，先把短信写进 PendingSmsQueue（持久化）
 * 2. 用 goAsync() 拿到 PendingResult，让系统别在 onReceive 返回后立刻杀我们进程
 * 3. 之后不管是 Service 起来还是用户再次打开 App，都能从队列里补发
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("ClipSync", "📨 [短信诊断] SmsReceiver.onReceive action=${intent.action}")
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // 1) 立刻拿 PendingResult：onReceive 返回后进程至少还能活 ~30s
        val pr = goAsync()

        // 2) 用户没启用同步：不丢，只打日志，直接退。MIUI 冷启动场景下会拉起应用，
        //    用户在 App 里没有点"开始同步"就别乱拉后台服务。
        if (!SyncService.isUserEnabled(context)) {
            Log.i("ClipSync", "⏸ [短信诊断] 短信丢弃：同步未启用（sync_enabled=false）")
            pr.finish()
            return
        }
        Log.i("ClipSync", "📨 [短信诊断] 同步已启用，开始解析短信 PDUs")

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null) {
            Log.w("ClipSync", "⚠️ [短信诊断] getMessagesFromIntent 返回 null，无法解析")
            pr.finish()
            return
        }
        val fromNumber = messages.firstOrNull()?.originatingAddress ?: "未知号码"
        val body = buildString {
            for (m in messages) append(m.displayMessageBody ?: "")
        }
        if (body.isBlank()) { pr.finish(); return }

        val fullText = "【$fromNumber】$body"
        val preview = "来自 $fromNumber 的短信"

        if (!SmsDeduplicator.shouldUpload(fromNumber, isPlaintext = true)) {
            Log.i("ClipSync", "⏸ 短信去重（广播）: $fromNumber")
            pr.finish()
            return
        }
        Log.i("ClipSync", "↑ 收到短信(明文广播): ${fullText.take(40)}")

        // 3) 持久化进队列：进程被杀也丢不了
        PendingSmsQueue.enqueue(context, fullText, preview)

        // 4) 存本地历史（出）
        HistoryStore.addSms(
            context,
            HistoryStore.HistoryItem(
                id = UUID.randomUUID().toString(),
                kind = "sms",
                text = fullText,
                preview = preview,
                direction = "out",
                ts = System.currentTimeMillis() / 1000
            )
        )

        // 5) 尽力拉服务：startForegroundService 失败也没事，队列里已经存了，
        //    下次 Service 起或 App 打开会一起发。
        val svc = Intent(context, SyncService::class.java).apply {
            action = SyncService.ACTION_FLUSH_PENDING_SMS
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.i("ClipSync", "📤 已拉起 SyncService 发送短信")
        } catch (e: Exception) {
            Log.e("ClipSync", "✗ 拉起 SyncService 失败: ${e.message}（短信已持久化，稍后自动补发）")
        } finally {
            pr.finish()
        }
    }
}
