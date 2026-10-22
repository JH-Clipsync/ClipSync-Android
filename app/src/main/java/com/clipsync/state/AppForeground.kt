package com.clipsync.state

/**
 * 极简的 App 前后台状态标记，由 MainActivity 在 onResume/onPause 维护。
 *
 * 后台服务（SyncService）用它判断权限告警该不该弹：App 在前台时由首页守卫条
 * 提示即可，只有用户看不到界面（息屏/切后台）时才发系统通知。
 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false
}
