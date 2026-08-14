package com.clipsync.state

import android.os.Handler
import android.os.Looper
import com.clipsync.model.OnlineDevice

/**
 * 在线设备总线。SyncService 订阅 WsClient.onlineDevices 后把列表发到这里，
 * MainActivity 注册监听刷新"在线设备"UI。
 */
object PresenceBus {

    @Volatile
    var current: List<OnlineDevice> = emptyList()
        private set

    private val listeners = mutableListOf<(List<OnlineDevice>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun publish(devices: List<OnlineDevice>) {
        current = devices
        val snapshot: List<(List<OnlineDevice>) -> Unit>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post {
            snapshot.forEach { it(devices) }
        }
    }

    fun addListener(l: (List<OnlineDevice>) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
        l(current)
    }

    fun removeListener(l: (List<OnlineDevice>) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }
}
