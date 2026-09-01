package com.clipsync.ui

import android.app.AppOpsManager
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限状态判断，从 PermissionSettingsActivity 里抽出来。
 *
 * 抽出的原因：设置页要在「权限」这一行显示「N 项待开启」的徽章，
 * 需要和权限页用同一套判断标准，否则两处显示会不一致。
 */
object PermissionStatus {

    private const val TAG = "PermStatus"

    /** 一项权限的状态：标题、是否就绪、以及为什么需要它 */
    data class Item(val label: String, val granted: Boolean, val why: String)

    fun runtimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            @Suppress("DEPRECATION")
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    fun missingRuntimePermissions(ctx: Context): Array<String> =
        runtimePermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun hasRuntimePermissions(ctx: Context): Boolean =
        missingRuntimePermissions(ctx).isEmpty()

    fun batteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 用户在系统设置里勾了「通知使用权」 */
    fun notificationListenerEnabled(ctx: Context): Boolean {
        return try {
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        } catch (e: Exception) {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            flat.split(":").any { it.contains(ctx.packageName) }
        }
    }

    /**
     * 监听服务是否真的被系统绑定上了。
     * 只看 enabled 不够：MIUI 会拦截绑定请求，勾了开关但服务没连上。
     *
     * 注意：getActiveNotificationListeners 是隐藏 API，在 MIUI/HyperOS 上反射经常
     * 直接抛 NoSuchMethodException。旧代码在 catch 里乐观回退成 notificationListenerEnabled()
     * （只看权限开关，权限在就返回 true），导致"权限在但服务没绑定"时误判为已连接，
     * UI 直接跳过 requestRebind 重绑，通知监听永远恢复不了。
     * 拿不到真实绑定状态时必须保守返回 false，让调用方去触发重绑。
     */
    fun notificationListenerLive(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
        val method = nm.javaClass.getMethod("getActiveNotificationListeners")
        @Suppress("UNCHECKED_CAST")
        val list = method.invoke(nm) as? List<ComponentName>
        list?.any { it.packageName == ctx.packageName } == true
    } catch (e: Exception) {
        // 反射失败：无法确认真实绑定状态，保守返回 false（不是回退到 enabled！）
        false
    }

    /** 综合判断通知监听是否处于可用状态：设置已开、服务已绑、或最近收到过通知 */
    fun notificationListenerOkay(ctx: Context): Boolean {
        if (notificationListenerLive(ctx)) return true
        if (notificationListenerEnabled(ctx)) return true
        return false
    }

    // MARK: - MIUI/HyperOS 扩展权限

    /** 是否运行在 MIUI/HyperOS 上 */
    fun isMiui(): Boolean {
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val getProp = clz.getMethod("get", String::class.java)
            val version = getProp.invoke(null, "ro.miui.ui.version.name")
            version is String && version.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * MIUI/HyperOS「后台弹窗」权限（允许后台启动 Activity）。
     *
     * 对应 Android hide op OP_BACKGROUND_START_ACTIVITY (code=10011)。
     * 检测失败（非 MIUI 或反射被拦）时乐观返回 true，避免误报。
     */
    fun backgroundPopupAllowed(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            // 旧版签名：checkOpNoThrow(int op, int uid, String packageName)
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            // OP_BACKGROUND_START_ACTIVITY = 10011
            val result = method.invoke(ops, 10011, android.os.Process.myUid(), ctx.packageName) as Int
            result == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.d(TAG, "后台弹窗权限检测失败，乐观返回 true: ${e.message}")
            true
        }
    }

    /**
     * MIUI/HyperOS「通知类短信」权限。
     *
     * 这是 MIUI 自定义的 op，标准 API 无法检测；尝试反射 MIUI 的 op name，
     * 全部失败时乐观返回 true。
     */
    fun notificationSmsAllowed(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            // 尝试用 String op name 调用（MIUI 扩展签名）
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                String::class.java, Int::class.javaPrimitiveType, String::class.java
            )
            for (opName in listOf("OP_NOTIFICATION_SMS", "NOTIFICATION_SMS", "OP_SMS_NOTIFICATION")) {
                try {
                    val result = method.invoke(ops, opName, android.os.Process.myUid(), ctx.packageName) as Int
                    return result == AppOpsManager.MODE_ALLOWED
                } catch (_: Exception) { /* 试下一个 */ }
            }
            // 全部 op name 都不认，乐观返回 true
            true
        } catch (e: Exception) {
            Log.d(TAG, "通知类短信权限检测失败，乐观返回 true: ${e.message}")
            true
        }
    }

    /**
     * 权限检查列表，顺序即引导顺序。
     *
     * MIUI/HyperOS 额外追加「通知类短信」和「后台弹窗」两项：
     * 这两项是厂商扩展权限，不开的话验证码/银行短信收不到、后台无法弹通知。
     * 非 MIUI 系统不显示。
     */
    fun all(ctx: Context): List<Item> {
        val base = mutableListOf(
            Item("短信与通知权限", hasRuntimePermissions(ctx), "读取短信内容、发送本机通知"),
            Item("通知使用权", notificationListenerEnabled(ctx), "绕开厂商的短信广播拦截"),
            Item("通知监听已连接", notificationListenerLive(ctx), "服务真正被系统绑定，短信才会触发"),
            Item("电池策略无限制", batteryUnrestricted(ctx), "防止后台被系统冻结")
        )
        if (isMiui()) {
            base.add(Item("通知类短信", notificationSmsAllowed(ctx),
                "验证码、银行通知等属于通知类短信，不开则收不到"))
            base.add(Item("后台弹窗", backgroundPopupAllowed(ctx),
                "允许 App 在后台弹出通知窗口"))
        }
        return base
    }

    fun pendingCount(ctx: Context): Int = all(ctx).count { !it.granted }
}
