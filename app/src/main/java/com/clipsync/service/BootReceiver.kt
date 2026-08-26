package com.clipsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clipsync.sms.NotificationSmsListener

/**
 * 开机 / 应用更新后的接收器。
 *
 * 旧版实现是直接 startForegroundService 拉起同步服务，但这违反了"用时打开、
 * 不用不启动"的诉求。现在只在应用更新后尝试重新绑定通知监听服务：
 * MIUI/HyperOS 在应用更新或重装后经常保留"通知使用权"开关但服务未实际绑定，
 * 主动 requestRebind 一次可以极大减少用户手动进设置再开关一次的概率。
 *
 * 开机广播不再自动拉起 SyncService，用户是否需要同步由他自己决定。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i("ClipSync", "ℹ️ 系统广播 $action 已收到，不会自动启动同步服务")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("ClipSync", "🔄 应用已更新，尝试重新绑定通知监听服务")
                NotificationSmsListener.ensureConnected(context)
            }
        }
    }
}
