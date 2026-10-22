package com.clipsync.ui

import android.app.AppOpsManager
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    /**
     * 仅短信链路必需的运行时权限：收/读短信，以及 Android 13+ 的通知权限。
     * 刻意不含图片/存储权限——那是剪贴板图片功能用的，缺它不该触发短信守卫告警。
     */
    private val SMS_RUNTIME_PERMS by lazy {
        buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun missingSmsRuntimePermissions(ctx: Context): Array<String> =
        SMS_RUNTIME_PERMS.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

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

    /**
     * 通知监听是否真正可用——这是给 UI 判状态用的"以事实为准"的综合判断。
     *
     * 三档信号，任一为真即视为已连接：
     * 1. NotificationSmsListener.isConnected：本进程内 onListenerConnected/Disconnected
     *    回调维护的实时状态，同进程下最可靠（权限页、首页都在同一进程）；
     * 2. notificationListenerLive()：反射隐藏 API 查系统已绑定 listener 列表，
     *    MIUI 上常反射失败返回 false，仅作辅助；
     * 3. 最近 60s 真的收到过通知（lastNotifyAt），说明监听实实在在在工作。
     *
     * 旧代码只认第 2 档，MIUI 上它恒为 false，导致服务其实已绑定、首页短信标签
     * 已变绿，权限页却一直显示"通知监听已连接"未就绪（卡在 5/6）。
     */
    fun notificationListenerConnected(ctx: Context): Boolean {
        if (com.clipsync.sms.NotificationSmsListener.isConnected) return true
        if (notificationListenerLive(ctx)) return true
        val recentlyActive = System.currentTimeMillis() -
            com.clipsync.sms.NotificationSmsListener.lastNotifyAt < 60_000L
        return recentlyActive
    }

    /** 综合判断通知监听是否处于可用状态：设置已开、服务已绑、或最近收到过通知 */
    fun notificationListenerOkay(ctx: Context): Boolean {
        if (notificationListenerConnected(ctx)) return true
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
    /**
     * MIUI 扩展 op 的统一判定：这类 op（后台弹窗 10011、通知类短信 10018…）都是
     * 「默认放行，用户显式拒绝才拦截」——无记录时 checkOpNoThrow 返回 MODE_DEFAULT，
     * 功能实际可用。因此不能只认 MODE_ALLOWED（会把正常状态误报成缺失），
     * 只有明确拒绝（MODE_IGNORED/MODE_ERRORED）才判为不健康。
     */
    private fun miuiOpAllowed(ctx: Context, op: Int): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            val result = method.invoke(ops, op, android.os.Process.myUid(), ctx.packageName) as Int
            result != AppOpsManager.MODE_IGNORED && result != AppOpsManager.MODE_ERRORED
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 澎湃OS 实测：扩展 op 被显式拒绝时 AppOpsService 不返回错误码，而是直接
            // 抛 SecurityException（系统侧 MmsSmsUtils 调 checkOp 同样会抛）。
            // 这是明确的"拒绝"信号，必须判不健康，不能乐观放行。
            if (e.cause is SecurityException) false
            else { Log.d(TAG, "MIUI op($op) 检测异常，乐观 true: ${e.cause}"); true }
        } catch (e: Exception) {
            // 反射入口都找不到（非 MIUI / 被隐藏）：乐观 true 避免误报
            Log.d(TAG, "MIUI op($op) 检测失败，乐观返回 true: ${e.message}")
            true
        }
    }

    /**
     * MIUI/HyperOS「后台弹窗」权限（允许后台启动 Activity）。
     *
     * 对应 Android hide op OP_BACKGROUND_START_ACTIVITY (code=10011)。
     * 检测失败（非 MIUI 或反射被拦）时乐观返回 true，避免误报。
     */
    fun backgroundPopupAllowed(ctx: Context): Boolean = miuiOpAllowed(ctx, 10011)

    /**
     * MIUI/HyperOS「通知类短信」权限。
     *
     * 实机（澎湃OS 1, MIX4）逆向确认：该权限对应 MIUI 扩展 AppOp
     * OP_READ_NOTIFICATION_SMS = 10018（定义在 com.miui.internal.os.MiuiHooks）。
     * 106/银行/验证码短信全部归为通知类，第三方 app 未授权时 SmsProvider 静默过滤，
     * READ_SMS 已授予也只能读到个人号短信、且不报错。系统侧判定见
     * MmsSmsUtils.allowReadNotificationSms（com.android.phone）。
     *
     * 三态实测：显式 deny → SmsProvider 过滤；用户手动开启 = 删除 deny 记录回到
     * MODE_DEFAULT，功能正常。所以判定走 miuiOpAllowed（只在明确拒绝时不健康），
     * 不能只认 MODE_ALLOWED——那会把正常的 default 态误报成缺失。
     *
     * 标准 AppOpsManager 不认这个 op（cmd appops 用名字查报 Unknown operation），
     * 必须用 int 编号走反射 checkOpNoThrow(int,int,String)。检测失败时乐观返回 true，
     * 避免在非 MIUI / 反射被拦的 ROM 上误报。
     */
    fun notificationSmsAllowed(ctx: Context): Boolean = miuiOpAllowed(ctx, 10018)

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
            Item("通知监听已连接", notificationListenerConnected(ctx), "服务真正被系统绑定，短信才会触发"),
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

    // MARK: - 短信同步守卫（首页警告条 / 后台看门狗共用）

    /** 一类会导致短信收不到的问题，以及它能否被 App 自动修复 */
    enum class Fix {
        /** 运行时权限：可直接弹系统授权框自动修复 */
        RUNTIME_PERM,
        /** 通知监听服务掉绑但开关还在：可 requestRebind 自动重绑 */
        REBIND_LISTENER,
        /** 必须用户去系统设置手动操作（MIUI 扩展权限、NLS 总开关、电池白名单） */
        MANUAL
    }

    data class SmsIssue(
        val key: String,
        val label: String,
        val why: String,
        val fix: Fix,
        val granted: Boolean
    )

    /**
     * 返回当前所有"会导致短信收不到/发不出"的未决问题，按处理优先级排序。
     *
     * 只挑与短信链路强相关的项，不把图片/存储等无关权限混进来；
     * 非 MIUI 设备天然没有通知类短信/后台弹窗两项。
     */
    fun smsIssues(ctx: Context): List<SmsIssue> {
        val issues = ArrayList<SmsIssue>()

        val missingSms = missingSmsRuntimePermissions(ctx)
        if (missingSms.isNotEmpty()) {
            val onlyNotify = missingSms.all { it == Manifest.permission.POST_NOTIFICATIONS }
            issues += SmsIssue(
                key = KEY_RUNTIME,
                label = "短信权限",
                why = if (onlyNotify) "需要通知权限才能提示同步状态"
                else "需要读取/接收短信权限才能同步验证码",
                fix = Fix.RUNTIME_PERM,
                granted = false
            )
        }

        // 通知使用权总开关：没开只能去系统设置
        if (!notificationListenerEnabled(ctx)) {
            issues += SmsIssue(
                key = KEY_NLS_SWITCH,
                label = "通知使用权",
                why = "绕开厂商对短信广播的拦截，需要开启通知使用权",
                fix = Fix.MANUAL,
                granted = false
            )
        } else if (!notificationListenerConnected(ctx)) {
            // 开关在但服务没绑：可自动重绑
            issues += SmsIssue(
                key = KEY_NLS_REBIND,
                label = "通知监听未连接",
                why = "监听服务被系统解绑，短信通知无法触发",
                fix = Fix.REBIND_LISTENER,
                granted = false
            )
        }

        if (!batteryUnrestricted(ctx)) {
            issues += SmsIssue(
                key = KEY_BATTERY,
                label = "电池优化",
                why = "息屏后可能被系统冻结，导致短信延迟或断同步",
                fix = Fix.MANUAL,
                granted = false
            )
        }

        if (isMiui()) {
            if (!notificationSmsAllowed(ctx)) {
                issues += SmsIssue(
                    key = KEY_MIUI_NOTIF_SMS,
                    label = "通知类短信",
                    why = "验证码、银行等 106 短信属通知类，MIUI 默认禁止第三方读取",
                    fix = Fix.MANUAL,
                    granted = false
                )
            }
            if (!backgroundPopupAllowed(ctx)) {
                issues += SmsIssue(
                    key = KEY_MIUI_POPUP,
                    label = "后台弹窗",
                    why = "允许 App 在后台拉起同步和提醒页面",
                    fix = Fix.MANUAL,
                    granted = false
                )
            }
        }

        return issues
    }

    const val KEY_RUNTIME = "runtime"
    const val KEY_NLS_SWITCH = "nls_switch"
    const val KEY_NLS_REBIND = "nls_rebind"
    const val KEY_BATTERY = "battery"
    const val KEY_MIUI_NOTIF_SMS = "miui_notif_sms"
    const val KEY_MIUI_POPUP = "miui_popup"

    /**
     * 为"必须手动处理"的问题构造跳转 Intent；返回 null 表示找不到可处理的系统页。
     * MIUI 扩展权限都收敛到应用权限编辑页（通知类短信、后台弹窗在同一页里）。
     * 调用方若在 Activity 之外使用需自行补 FLAG_ACTIVITY_NEW_TASK。
     */
    fun manualFixIntent(ctx: Context, key: String): Intent? {
        val intent = when (key) {
            KEY_NLS_SWITCH -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

            KEY_BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))

            KEY_MIUI_NOTIF_SMS, KEY_MIUI_POPUP -> miuiAppPermEditorIntent(ctx)
                ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))

            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${ctx.packageName}"))
        }
        return intent.takeIf {
            try {
                ctx.packageManager.resolveActivity(it, 0) != null
            } catch (_: Exception) {
                true // 解析失败也返回，让调用方 try-startActivity 兜底
            }
        }
    }

    /** MIUI/HyperOS 应用权限编辑页，按版本试候选 Activity */
    private fun miuiAppPermEditorIntent(ctx: Context): Intent? {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra(
                "miui.intent.extra.package_name", ctx.packageName
            ),
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            ).putExtra("extra_package_name", ctx.packageName),
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.activity.PermissionsEditorActivity"
            ).putExtra("extra_package_name", ctx.packageName)
        )
        return candidates.firstOrNull {
            try {
                ctx.packageManager.resolveActivity(it, 0) != null
            } catch (_: Exception) {
                false
            }
        }
    }
}
