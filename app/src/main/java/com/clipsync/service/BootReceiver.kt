package com.clipsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.clipsync.sms.NotificationSmsListener

/**
 * 开机 / 应用更新后的接收器。
 *
 * 之前为了"用时打开、不用不启动"，开机后不自动拉起同步服务。但这导致用户
 * 每次重启手机都要手动打开一次 App，锁屏后服务也容易被系统清理。
 *
 * 现在恢复自动拉起：只要用户没有主动关闭同步（sync_enabled=true），开机或
 * 应用更新后就把 SyncService 和兜底保活任务都挂上。用户主动点了"停止同步"
 * 的场景由 sync_enabled=false 拦住，不会跟用户对着干。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i("ClipSync", "ℹ️ 收到系统广播: $action")

        if (!SyncService.isUserEnabled(context)) {
            Log.i("ClipSync", "⏸ 同步未开启，跳过自动启动")
            return
        }

        // 应用更新后主动重新绑定通知监听（MIUI 经常保留开关但不绑定服务）
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NotificationSmsListener.ensureConnected(context)
        }

        // 拉起前台同步服务
        val serviceIntent = Intent(context, SyncService::class.java).apply {
            this.action = SyncService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // 兜底保活任务也注册上
        KeepAliveJobService.schedule(context)
    }
}
