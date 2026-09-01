package com.clipsync.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 兜底保活任务：每 15 分钟被系统唤醒一次，检查 SyncService 是否还活着。
 *
 * MIUI/HyperOS 锁屏时间长了会把后台进程直接杀掉，START_STICKY 也经常拉不起来。
 * JobScheduler 由系统进程调度，杀不死；它醒了就检查一下：用户曾开启同步但服务
 * 没在跑，就把前台服务重新挂回去。
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i("ClipSync", "⏰ 保活任务触发")
        if (SyncService.isUserEnabled(this) && !SyncService.isServiceAlive()) {
            Log.i("ClipSync", "🔁 检测到同步服务未运行，重新拉起")
            val intent = Intent(this, SyncService::class.java).apply {
                action = SyncService.ACTION_CONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // 服务被杀往往伴随通知监听绑定丢失，一并请求重绑（幂等）
            runCatching {
                com.clipsync.sms.NotificationSmsListener.ensureConnected(this)
            }
        } else {
            Log.i("ClipSync", "✓ 同步服务正常运行，无需拉起")
        }
        // 不需要异步处理，false 表示任务已完成
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 0xC11C
        private const val INTERVAL_MS = 15L * 60 * 1000

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            val result = js.schedule(job)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i("ClipSync", "✓ 保活任务已注册（每 15 分钟）")
            } else {
                Log.w("ClipSync", "✗ 保活任务注册失败: $result")
            }
        }

        fun cancel(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(JOB_ID)
        }
    }
}
