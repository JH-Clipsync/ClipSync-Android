package com.clipsync.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 权限页：完成度概览 + 逐项引导。
 *
 * 旧版是五个饱和色按钮竖排（靛蓝/紫/红/橙/红），看着像报警面板，
 * 而且看不出哪几项已经好了。现在改成：顶部一条完成度进度，
 * 下面每项自己报状态——已就绪的收敛成灰勾，未就绪的才给操作按钮。
 */
class PermissionSettingsActivity : AppCompatActivity() {

    private lateinit var summaryTitle: TextView
    private lateinit var summaryDesc: TextView
    private lateinit var progressFill: View
    private lateinit var progressTrack: View
    private lateinit var itemsCard: LinearLayout
    private lateinit var hintCard: View

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            toast("全部权限已授予", Design.Color.SUCCESS)
        } else {
            // MIUI 可能"授予"了但实际 runtime 状态仍是 false（APPLY_RESTRICTION）
            // 重新检查实际权限状态
            val stillMissing = PermissionStatus.missingRuntimePermissions(this)
            if (stillMissing.isNotEmpty()) {
                toast("系统限制未能直接授权，请去系统设置手动开启", Design.Color.WARNING)
                openAppDetails()
            } else {
                toast("全部权限已授予", Design.Color.SUCCESS)
            }
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "权限"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Design.Color.CANVAS)
            val h = Design.dp(this@PermissionSettingsActivity, Design.Space.L)
            setPadding(h, h, h, Design.dp(this@PermissionSettingsActivity, 32f))
        }
        container.addView(buildSummaryCard(), matchWrap(Design.Space.M))
        itemsCard = SettingsRows.group(this, container, "逐项检查")
        hintCard = buildHintCard()
        container.addView(hintCard, matchWrap(0f))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Design.Color.CANVAS)
            addView(container)
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        // 权限开着但监听没绑上（MIUI 杀进程后常见）：进页面就自动重绑并定时刷新，
        // 不用等用户点"重新触发绑定"。ensureConnected 内部有节流，重复调用安全。
        if (PermissionStatus.notificationListenerEnabled(this) &&
            !PermissionStatus.notificationListenerConnected(this)) {
            runCatching { com.clipsync.sms.NotificationSmsListener.ensureConnected(this) }
            scheduleRebindRefresh()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // MARK: - 概览

    private fun buildSummaryCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.outlinedBg(
                this@PermissionSettingsActivity,
                Design.Color.SURFACE, Design.Color.BORDER_CARD, Design.Radius.CARD
            )
            val h = Design.dp(this@PermissionSettingsActivity, 18f)
            setPadding(h, h, h, h)
        }

        summaryTitle = Design.text(this, "", Design.Text.TITLE, Design.Color.INK, bold = true)
        card.addView(summaryTitle)

        summaryDesc = Design.text(this, "", Design.Text.CAPTION, Design.Color.INK_SECONDARY).apply {
            setLineSpacing(0f, 1.4f)
            setPadding(0, Design.dp(this@PermissionSettingsActivity, 5f), 0,
                Design.dp(this@PermissionSettingsActivity, Design.Space.M))
        }
        card.addView(summaryDesc)

        // 进度条：轨道固定宽，填充条按比例改 layout 宽度
        progressTrack = View(this).apply {
            background = Design.roundedBg(
                this@PermissionSettingsActivity, Design.Color.NEUTRAL_TINT, 3f
            )
        }
        progressFill = View(this).apply {
            background = Design.roundedBg(this@PermissionSettingsActivity, Design.Color.PRIMARY, 3f)
        }
        val barHeight = Design.dp(this, 6f)
        val bar = FrameLayout(this).apply {
            addView(progressTrack, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, barHeight
            ))
            addView(progressFill, FrameLayout.LayoutParams(0, barHeight))
        }
        card.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, barHeight
        ))
        return card
    }

    private fun buildHintCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.outlinedBg(
                this@PermissionSettingsActivity,
                Design.Color.WARNING_TINT_SOFT, Design.Color.WARNING_BORDER, Design.Radius.INPUT
            )
            val h = Design.dp(this@PermissionSettingsActivity, 14f)
            setPadding(h, h, h, h)
            visibility = View.GONE
        }
        card.addView(
            Design.text(this, "通知监听没连上", Design.Text.CARD_TITLE, Design.Color.WARNING, bold = true)
        )
        card.addView(
            Design.text(
                this,
                "开关已打开但服务没被系统绑定，通常是自启动被拦。" +
                    "先开自启动，再点下面的按钮重新触发绑定。",
                Design.Text.MICRO, Design.Color.INK_SECONDARY
            ).apply {
                setLineSpacing(0f, 1.45f)
                setPadding(0, Design.dp(this@PermissionSettingsActivity, 5f), 0,
                    Design.dp(this@PermissionSettingsActivity, Design.Space.M))
            }
        )
        card.addView(actionButton("重新触发绑定") { rebindNotificationListener() })
        return card
    }

    /** 描边小按钮，权限项和提示卡共用 */
    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        Design.text(this, label, Design.Text.CAPTION, Design.Color.PRIMARY).apply {
            gravity = Gravity.CENTER
            background = Design.outlinedBg(
                this@PermissionSettingsActivity,
                Design.Color.SURFACE, Design.Color.PRIMARY, Design.Radius.CHIP
            )
            val h = Design.dp(this@PermissionSettingsActivity, 14f)
            val v = Design.dp(this@PermissionSettingsActivity, 8f)
            setPadding(h, v, h, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun matchWrap(bottomDp: Float) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = Design.dp(this@PermissionSettingsActivity, bottomDp) }

    // MARK: - 渲染

    /**
     * 每次进页面/授权回来都整体重画。
     * 权限状态可能在系统设置里被改，逐个增量更新反而更容易漏。
     */
    private fun render() {
        val items = PermissionStatus.all(this)
        val ready = items.count { it.granted }

        summaryTitle.text = if (ready == items.size) "全部就绪" else "$ready / ${items.size} 项已就绪"
        summaryDesc.text = if (ready == items.size) {
            "短信推送和后台保活所需的权限都已开启。"
        } else {
            "缺任意一项，短信推送就可能收不到。按下面顺序开完即可。"
        }

        // 填充宽度按轨道实际宽度算，所以要等 layout 完成
        progressTrack.post {
            val full = progressTrack.width
            if (full <= 0) return@post
            val ratio = ready.toFloat() / items.size
            progressFill.layoutParams = progressFill.layoutParams.apply {
                width = (full * ratio).toInt()
            }
            progressFill.requestLayout()
        }
        (progressFill.background as? android.graphics.drawable.GradientDrawable)?.setColor(
            if (ready == items.size) Design.Color.SUCCESS else Design.Color.PRIMARY
        )

        itemsCard.removeAllViews()
        items.forEachIndexed { index, item ->
            if (index > 0) SettingsRows.separator(this, itemsCard)
            itemsCard.addView(buildItemRow(item), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val notifEnabled = PermissionStatus.notificationListenerEnabled(this)
        val notifLive = PermissionStatus.notificationListenerConnected(this)
        hintCard.visibility = if (notifEnabled && !notifLive) View.VISIBLE else View.GONE
    }

    /**
     * 一项权限一行：状态标记 + 标题 + 说明，未就绪的右侧才给操作按钮。
     * 已就绪的不给按钮，避免用户重复点进系统设置找不到要改什么。
     */
    private fun buildItemRow(item: PermissionStatus.Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = Design.dp(this@PermissionSettingsActivity, 13f)
            setPadding(0, v, 0, v)
        }

        val markSize = Design.dp(this, 18f)
        val mark = Design.text(
            this, if (item.granted) "✓" else "!", 10.5f,
            if (item.granted) Design.Color.SUCCESS else Design.Color.WARNING
        ).apply {
            gravity = Gravity.CENTER
            background = Design.circleBg(
                if (item.granted) Design.Color.SUCCESS_TINT else Design.Color.WARNING_TINT
            )
        }
        row.addView(mark, LinearLayout.LayoutParams(markSize, markSize).apply {
            marginEnd = Design.dp(this@PermissionSettingsActivity, Design.Space.M)
        })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Design.text(this, item.label, Design.Text.LABEL, Design.Color.INK))
        col.addView(
            Design.text(this, item.why, Design.Text.MICRO, Design.Color.INK_MUTED).apply {
                setLineSpacing(0f, 1.35f)
                setPadding(0, Design.dp(this@PermissionSettingsActivity, 3f), 0, 0)
            }
        )
        row.addView(col, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        if (!item.granted) {
            row.addView(actionButton("开启") { openFor(item.label) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = Design.dp(this@PermissionSettingsActivity, Design.Space.M)
                })
        }
        return row
    }

    /** 用标题分发，标题来自 PermissionStatus.all 的固定文案 */
    private fun openFor(label: String) {
        when (label) {
            "短信与通知权限" -> requestRuntimePermissions()
            "通知使用权" -> openNotificationListenerSettings()
            "通知监听已连接" -> rebindNotificationListener()
            "电池策略无限制" -> requestIgnoreBatteryOptimization()
            "通知类短信" -> openMiuiAppPermEditor()
            "后台弹窗" -> openMiuiAppPermEditor()
        }
    }

    // MARK: - 跳转与授权

    private fun requestRuntimePermissions() {
        val missing = PermissionStatus.missingRuntimePermissions(this)
        if (missing.isEmpty()) {
            toast("权限已全部授予", Design.Color.SUCCESS)
            return
        }
        try {
            // 先尝试系统标准授权弹窗
            permissionLauncher.launch(missing)
        } catch (e: Exception) {
            // 某些 ROM 拦截了标准授权流程，退到应用详情页手动开
            openAppDetails()
            toast("请在系统设置中手动开启短信权限", Design.Color.NEUTRAL)
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (PermissionStatus.batteryUnrestricted(this)) {
            toast("已加入电池白名单", Design.Color.SUCCESS)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            toast("打开设置失败：${e.message}", Design.Color.DANGER)
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast("在列表中找到 ClipSync 并打开开关", Design.Color.NEUTRAL)
        } catch (e: Exception) {
            toast("打开设置失败：${e.message}", Design.Color.DANGER)
        }
    }

    /**
     * 跳 MIUI/HyperOS 自启动管理。各版本 Activity 名不同，按优先级试，
     * 都不行就退到安全中心首页。
     *
     * 这是短信推送不工作最常见的根因：MIUI 的 AutoStartManagerService 会拦截
     * NotificationListenerService 的绑定，onListenerConnected 永远不会被调用。
     */
    private fun openMiuiAutoStartSettings() {
        val candidates = listOf(
            // HyperOS / MIUI 14+
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // MIUI 12/13
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.activity.AutoStartManagementActivity"),
            // 旧版 MIUI
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.securitycenter.autostart.AutoStartManagementActivity")
        )
        for (intent in candidates) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (packageManager.resolveActivity(intent, 0) != null) {
                    startActivity(intent)
                    toast("在列表中找到 ClipSync 并打开自启动开关", Design.Color.NEUTRAL)
                    return
                }
            } catch (_: Exception) { /* 试下一个 */ }
        }
        try {
            startActivity(Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.activity.MainActivity").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            toast("安全中心 → 应用管理 → 自启动管理中开启 ClipSync", Design.Color.NEUTRAL)
        } catch (e: Exception) {
            toast("请手动进入：设置 → 应用管理 → ClipSync → 自启动", Design.Color.NEUTRAL)
        }
    }

    /**
     * 跳转 MIUI/HyperOS 应用权限编辑页。
     *
     * 「通知类短信」「后台弹窗」等厂商扩展权限都在这个页面里，
     * 标准 Android API 无法直接跳到具体子项，只能打开整个权限编辑页让用户手动找。
     */
    private fun openMiuiAppPermEditor() {
        val candidates = listOf(
            // HyperOS / MIUI 14+
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("miui.intent.extra.package_name", packageName)
            },
            // MIUI 12/13
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity").apply {
                putExtra("extra_package_name", packageName)
            },
            // 旧版 MIUI
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.activity.PermissionsEditorActivity").apply {
                putExtra("extra_package_name", packageName)
            }
        )
        for (intent in candidates) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (packageManager.resolveActivity(intent, 0) != null) {
                    startActivity(intent)
                    toast("找到「通知类短信」和「后台弹窗」并开启", Design.Color.NEUTRAL)
                    return
                }
            } catch (_: Exception) { /* 试下一个 */ }
        }
        // 兜底：跳应用详情页
        openAppDetails()
        toast("请在应用设置中开启通知类短信和后台弹窗", Design.Color.NEUTRAL)
    }

    /**
     * 主动请系统重新绑定 NotificationListenerService。
     * 刚开完自启动时系统不会自己重连，需要触发一次；
     * 手动「关掉再打开」通知使用权开关也是同样效果。
     */
    private fun rebindNotificationListener() {
        // 统一走 NotificationSmsListener.ensureConnected：它先 requestRebind，
        // 3s 后复查仍未绑定会自动降级到 disable→enable 组件 toggle 强制重绑
        // （MIUI 上 requestRebind 静默无效，只有 toggle 能真正触发绑定）。
        val ok = try {
            com.clipsync.sms.NotificationSmsListener.ensureConnected(this)
        } catch (e: Exception) {
            false
        }
        if (ok) {
            toast("正在重新绑定，几秒后自动刷新状态", Design.Color.NEUTRAL)
            scheduleRebindRefresh()
        } else {
            openNotificationListenerSettings()
            toast("请把 ClipSync 的通知使用权开关关掉再打开", Design.Color.NEUTRAL)
        }
    }

    /** 重绑后定时重画页面，绑定成功（约 3~5s）后"通知监听已连接"自动变绿 */
    private val rebindHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun scheduleRebindRefresh() {
        rebindHandler.removeCallbacksAndMessages(null)
        listOf(1000L, 2500L, 4000L, 6000L, 8000L).forEach { d ->
            rebindHandler.postDelayed({
                if (isDestroyed || isFinishing) return@postDelayed
                render()
            }, d)
        }
    }

    /** 懒初始化：内容根要等 setContentView 之后才存在 */
    private val topToast by lazy { DesignToast.attach(this) }

    private fun toast(msg: String, tone: Int = Design.Color.PRIMARY) {
        topToast.show(msg, tone)
    }
}
