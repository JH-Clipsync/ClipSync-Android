package com.clipsync

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clipsync.clipboard.ClipboardManagerHelper
import com.clipsync.service.SyncService
import com.clipsync.state.ConnectionBus
import com.clipsync.ui.Design
import com.clipsync.ui.HistoryActivity
import com.clipsync.ui.SettingsActivity
import com.clipsync.ui.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面。
 *
 * 布局要点（这版重设计的核心）：
 * - 状态卡用居中同心光环 + 大字状态，服务器地址降级为脚注行
 * - 剪贴板预览区**固定 96dp**，内容再长也不撑开，底部渐隐提示被截断
 * - 操作区是**悬浮层**：压在内容之上贴屏幕底，与内容高度完全解耦，
 *   所以推送按钮的位置永远不会因为剪贴板内容变化而移动
 */
class MainActivity : AppCompatActivity() {

    // 状态光环：三层同心圆，外圈最浅、内核实心
    private lateinit var ringOuter: FrameLayout
    private lateinit var ringMid: FrameLayout
    private lateinit var ringCore: View
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var toggleBtn: Button
    private lateinit var changePwdBtn: Button
    /** 当前服务器地址，供 renderState 拼接到状态提示里 */
    private var serverAddr: String = ""
    /** 通知监听未连接时显示的警告条 */
    private lateinit var notifListenerWarning: View

    // 剪贴板预览
    private lateinit var clipPreviewText: TextView
    private lateinit var clipPreviewImage: ImageView
    private lateinit var clipContentFrame: FrameLayout
    private lateinit var clipCard: LinearLayout
    private lateinit var clipTypeTag: TextView
    private lateinit var clipFootnote: TextView
    private lateinit var pushBtn: Button

    // 在线设备
    private lateinit var onlineCard: LinearLayout
    private lateinit var onlineCount: TextView
    private lateinit var onlineList: LinearLayout

    private val presenceListener: (List<com.clipsync.model.OnlineDevice>) -> Unit = { devices ->
        runOnUiThread { renderPresence(devices) }
    }

    private var dotAnimator: ObjectAnimator? = null
    private var lastWasConnecting = false

    /** 当前展开「重命名」圆形按钮的行；同时只允许一个，点其他行会收起 */
    private var activeRenameFab: View? = null

    /** 顶部轻提示，替代会挡住底部按钮的系统 Toast */
    private lateinit var toast: com.clipsync.ui.DesignToast

    private val stateListener: (String) -> Unit = { state ->
        renderState(state)
        // 断线/重连时刷新在线设备卡的"离线/获取中"提示
        runOnUiThread { renderPresence(com.clipsync.state.PresenceBus.current) }
    }
    private val previewListener: () -> Unit = { refreshClipPreview() }

    private val mediaPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("ClipSync", "图片权限授权结果: $granted")
        refreshClipPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        title = "ClipSync"
        ClipboardManagerHelper.loadPrefs(this)
        // 重要：先初始化 ClipboardManagerHelper，否则主页预览拿不到剪贴板
        // （之前依赖 SyncService 启动，但预览要在服务启动前就能用）
        ClipboardManagerHelper.init(applicationContext, null)
        requestMediaPermissionIfNeeded()

        setContentView(buildRoot())
    }

    /**
     * 根布局是 FrameLayout 叠两层：
     *  1. 可滚动内容（状态卡 + 剪贴板卡）
     *  2. 悬浮操作层（贴底，压在内容之上）
     *
     * 用叠层而不是竖排 LinearLayout，是为了让按钮脱离内容流——
     * 这样剪贴板内容多长都不会把按钮推走。
     */
    private fun buildRoot(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Design.Color.CANVAS)
        }

        val actionBar = buildActionBar()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Design.dp(this@MainActivity, Design.Space.L),
                Design.dp(this@MainActivity, Design.Space.L),
                Design.dp(this@MainActivity, Design.Space.L),
                // 初值只是占位，真实高度在下面按 actionBar 实测值回填
                Design.dp(this@MainActivity, 130f)
            )
        }
        content.addView(buildStatusCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        notifListenerWarning = buildNotifListenerWarning().also {
            content.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Design.dp(this@MainActivity, Design.Space.M) })
        }
        content.addView(buildClipCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, Design.Space.M) })
        content.addView(buildOnlineCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, Design.Space.M) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            // 悬浮层是半透明的，滚动条压在上面会很脏
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }

        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(actionBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        // 挂到窗口顶层而不是这里的 root，横幅才能盖在悬浮操作层之上。
        // 落点见下方 clearance：紧贴推送按钮上沿。
        toast = com.clipsync.ui.DesignToast.attach(this)

        // 悬浮层盖住内容底部，内容区要留出等高的安全距，否则最后一张卡看不全。
        // 高度用实测值而不是写死：系统字体放大后按钮会变高，写死就会压住内容。
        actionBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val barHeight = bottom - top
            // 提示浮在按钮正上方，用同一个实测高度让开
            toast.clearance = barHeight
            val safeBottom = barHeight + Design.dp(this, Design.Space.M)
            if (content.paddingBottom != safeBottom) {
                content.setPadding(
                    content.paddingLeft, content.paddingTop,
                    content.paddingRight, safeBottom
                )
            }
        }
        return root
    }

    /**
     * 悬浮操作层：贴屏幕底，压在内容之上。
     *
     * 半透明白底 + 上方投影，滚动时内容从它下面穿过，能看出层次。
     * 关键是它不在内容流里，所以剪贴板内容变化影响不到按钮位置。
     */
    private fun buildActionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Design.dp(this@MainActivity, Design.Space.L),
                Design.dp(this@MainActivity, Design.Space.M),
                Design.dp(this@MainActivity, Design.Space.L),
                Design.dp(this@MainActivity, Design.Space.L)
            )
            setBackgroundColor(Design.Color.ACTION_BAR)
            elevation = Design.dp(this@MainActivity, 12f).toFloat()
        }

        pushBtn = Button(this).apply {
            text = "推送到电脑"
            setTextColor(Design.Color.SURFACE)
            background = Design.roundedBg(
                this@MainActivity, Design.Color.PRIMARY, Design.Radius.BUTTON
            )
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // stateListAnimator 会盖掉自定义背景的圆角，必须清掉
            stateListAnimator = null
            val v = Design.dp(this@MainActivity, 14f)
            setPadding(0, v, 0, v)
            setOnClickListener { pushClipboard() }
        }
        bar.addView(pushBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 次要入口：历史 / 设置，描边式，不与主按钮争视觉
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val gap = Design.dp(this, 9f)
        row.addView(
            secondaryButton("历史记录") {
                startActivity(Intent(this, HistoryActivity::class.java))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            secondaryButton("设置") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = gap }
        )
        bar.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap })

        return bar
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Design.Color.INK_SECONDARY)
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SURFACE,
                Design.Color.BORDER,
                Design.Radius.CHIP
            )
            textSize = Design.Text.CAPTION
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            stateListAnimator = null
            val v = Design.dp(this@MainActivity, 10f)
            setPadding(0, v, 0, v)
            setOnClickListener { onClick() }
        }

    override fun onResume() {
        super.onResume()
        ConnectionBus.addListener(stateListener)
        com.clipsync.state.PresenceBus.addListener(presenceListener)
        renderTarget()
        refreshNotifListenerWarning()
        com.clipsync.clipboard.ClipboardManagerHelper.onForeground()
        // 不要在回前台时偷偷拉起服务：用户没点过「启动」就让它空着。
        // 仅在服务还活着时踢一脚加速重连。SyncService 自己是前台服务 + START_STICKY，
        // 切后台不会被杀、断了也会自己指数退避重连，所以无需 App 介入。
        if (SyncService.isServiceAlive()) {
            SyncService.kick()
        } else {
            // 服务没起：用户划进程 / 主动停止后回到这里。把 sync_enabled 清掉，
            // 否则一条短信又会把服务拉起来。点「启动同步」时再写回 true。
            // 注意：判断依据必须是"服务实例是否存活"，而不是 activeWs()——
            // 刚点启动时服务正在换 token，activeWs() 还是 null，若在这里清标记，
            // 连接建好后短信同步就被永久关掉了（只能再手动点一次启动）。
            SyncService.setUserEnabled(this, false)
        }
        ClipboardManagerHelper.addPreviewListener(previewListener)
        // 第一次预览（窗口未获焦点，可能读不到内容，下面 onWindowFocusChanged 会再读）
        refreshClipPreview()
        // 兜底：onWindowFocusChanged 在某些 ROM 上不会触发，所以延迟再读几次
        clipCard.postDelayed({ refreshClipPreview() }, 300)
        clipCard.postDelayed({ refreshClipPreview() }, 800)
        clipCard.postDelayed({ refreshClipPreview() }, 1500)
        renderState(ConnectionBus.current)
        autoConnectIfNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i("ClipSync", "onWindowFocusChanged: hasFocus=$hasFocus")
        if (hasFocus) {
            // 关键：Android 10+ 后台无法读剪贴板内容，必须等窗口真正获焦点后再读。
            // 多数 ROM 还需要再延迟一帧（MIUI 更严格）
            clipCard.postDelayed({ refreshClipPreview() }, 150)
        }
    }

    override fun onPause() {
        super.onPause()
        ClipboardManagerHelper.removePreviewListener(previewListener)
        // 注意：不在 onPause 里 removeListener。
        // startForegroundService 在 MIUI 上可能触发短暂的 onPause → onResume，
        // 如果在这里移除 listener，连接成功时 publish(STATE_OPEN) 就没人收，
        // 界面卡在"连接中"，必须手动切到设置再回来才刷新。
        // listener 改到 onDestroy 里移除，renderState 有 isInitialized 检查保证安全。
        stopDotAnimation()
    }

    override fun onDestroy() {
        ConnectionBus.removeListener(stateListener)
        com.clipsync.state.PresenceBus.removeListener(presenceListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.refresh(this)
        recreate()
    }

    // 历史 / 设置入口已放到底部悬浮操作层，不再占用 ActionBar 菜单

    // MARK: - 通知监听警告条

    /**
     * 构造通知监听假开启警告条。
     * MIUI/HyperOS 上 App 重装/升级后，系统设置里显示已授权但 Listener 未实际绑定，
     * 此时短信通知完全收不到。检测到未连接时显示此横幅，点击跳转到通知使用权设置。
     */
    private fun buildNotifListenerWarning(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Design.roundedBg(this@MainActivity, 0xFFFFF4E5.toInt(), Design.Radius.TAG)
            setPadding(Design.dp(this@MainActivity, 12f), Design.dp(this@MainActivity, 10f),
                Design.dp(this@MainActivity, 12f), Design.dp(this@MainActivity, 10f))
            isClickable = true
            visibility = View.GONE
            setOnClickListener {
                try {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                } catch (_: Exception) {}
            }
            val dot = View(this@MainActivity).apply {
                background = Design.circleBg(0xFFFF9800.toInt())
            }
            addView(dot, LinearLayout.LayoutParams(Design.dp(this@MainActivity, 8f), Design.dp(this@MainActivity, 8f)))
            val text = Design.text(
                this@MainActivity,
                "通知监听未连接，短信可能无法同步。点此去设置关闭再重新开启",
                Design.Text.MICRO, 0xFFE65100.toInt()
            ).apply {
                setPadding(Design.dp(this@MainActivity, 8f), 0, 0, 0)
            }
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun refreshNotifListenerWarning() {
        if (!::notifListenerWarning.isInitialized) return
        if (com.clipsync.sms.NotificationSmsListener.isConnected) {
            notifListenerWarning.visibility = View.GONE
            return
        }
        // 未连接：先尝试自动重连，2.5 秒后再检查，仍未连上才提示用户
        com.clipsync.sms.NotificationSmsListener.ensureConnected(this)
        notifListenerWarning.postDelayed({
            if (!com.clipsync.sms.NotificationSmsListener.isConnected) {
                notifListenerWarning.visibility = View.VISIBLE
            }
        }, 2500)
    }

    // MARK: - 状态卡（重设计：背景随状态变色，大号圆点 + 状态文字 + 服务器地址 + 启动按钮）

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SURFACE,
                Design.Color.BORDER_CARD,
                Design.Radius.CARD
            )
            setPadding(
                Design.dp(this@MainActivity, Design.Space.L),
                Design.dp(this@MainActivity, 22f),
                Design.dp(this@MainActivity, Design.Space.L),
                Design.dp(this@MainActivity, Design.Space.L)
            )
        }

        card.addView(buildStatusRing())

        // 状态主文案 + 副文案
        statusText = Design.text(this, "未连接", Design.Text.DISPLAY, Design.Color.INK, bold = true)
        card.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, Design.Space.M) })

        statusHint = Design.text(this, " ", Design.Text.CAPTION, Design.Color.INK_MUTED).apply {
            gravity = Gravity.CENTER
        }
        card.addView(statusHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, Design.Space.XS) })

        // 启动 / 停止：次要样式，避免和底部主按钮抢注意力
        toggleBtn = Button(this).apply {
            text = "启动同步"
            setTextColor(Design.Color.INK_SECONDARY)
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SURFACE,
                Design.Color.BORDER,
                Design.Radius.CHIP
            )
            textSize = Design.Text.BODY
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            stateListAnimator = null
            val h = Design.dp(this@MainActivity, 22f)
            val v = Design.dp(this@MainActivity, 9f)
            setPadding(h, v, h, v)
            setOnClickListener { toggleSync() }
        }
        card.addView(toggleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, 14f) })

        // 修改密码按钮：仅在被踢下线时显示，和 toggleBtn 同样的描边样式
        changePwdBtn = Button(this).apply {
            text = "修改密码"
            setTextColor(Design.Color.DANGER)
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SURFACE,
                Design.Color.DANGER_TINT,
                Design.Radius.CHIP
            )
            textSize = Design.Text.BODY
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            stateListAnimator = null
            val h = Design.dp(this@MainActivity, 22f)
            val v = Design.dp(this@MainActivity, 9f)
            setPadding(h, v, h, v)
            visibility = View.GONE
            setOnClickListener { showChangePasswordDialog() }
        }
        card.addView(changePwdBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = Design.dp(this@MainActivity, Design.Space.S)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        renderTarget()
        return card
    }

    /**
     * 状态光环：三层同心圆叠出层次，比单个字符圆点更有存在感。
     * 三层颜色随连接状态整体切换（见 applyRingColors）。
     */
    private fun buildStatusRing(): View {
        val size = Design.dp(this, 64f)
        val midSize = Design.dp(this, 44f)
        val coreSize = Design.dp(this, 18f)

        ringOuter = FrameLayout(this).apply {
            background = Design.circleBg(Design.Color.NEUTRAL_TINT_SOFT)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        ringMid = FrameLayout(this).apply {
            background = Design.circleBg(Design.Color.NEUTRAL_TINT)
        }
        ringCore = View(this).apply {
            background = Design.circleBg(Design.Color.NEUTRAL)
        }
        ringMid.addView(ringCore, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
        ringOuter.addView(ringMid, FrameLayout.LayoutParams(midSize, midSize, Gravity.CENTER))
        return ringOuter
    }

    /** 一次性切换光环三层配色 */
    private fun applyRingColors(outer: Int, mid: Int, core: Int) {
        (ringOuter.background as? GradientDrawable)?.setColor(outer)
        (ringMid.background as? GradientDrawable)?.setColor(mid)
        (ringCore.background as? GradientDrawable)?.setColor(core)
    }

    // MARK: - 在线设备卡

    private fun buildOnlineCard(): View {
        onlineCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SURFACE,
                Design.Color.BORDER_CARD,
                Design.Radius.CARD
            )
            val p = Design.dp(this@MainActivity, 15f)
            setPadding(p, p, p, p)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            Design.text(this, "在线设备", Design.Text.CARD_TITLE, Design.Color.INK, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        onlineCount = Design.text(this, "", Design.Text.TAG, Design.Color.SUCCESS).apply {
            background = Design.roundedBg(
                this@MainActivity, Design.Color.SUCCESS_TINT, Design.Radius.TAG
            )
            val h = Design.dp(this@MainActivity, Design.Space.S)
            val v = Design.dp(this@MainActivity, 3f)
            setPadding(h, v, h, v)
        }
        titleRow.addView(onlineCount)
        onlineCard.addView(titleRow)

        onlineList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        onlineCard.addView(onlineList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, 8f) })

        // 先用当前缓存渲染一次（从后台切回前台时不闪空）
        renderPresence(com.clipsync.state.PresenceBus.current)
        return onlineCard
    }

    private fun renderPresence(devices: List<com.clipsync.model.OnlineDevice>) {
        if (!::onlineList.isInitialized || !::onlineCount.isInitialized) return
        onlineList.removeAllViews()

        val connected = ConnectionBus.current == ConnectionBus.STATE_OPEN
        if (!connected) {
            onlineCount.text = "离线"
            onlineCount.setTextColor(Design.Color.INK_MUTED)
            (onlineCount.background as? GradientDrawable)?.setColor(Design.Color.NEUTRAL_TINT)
            addOnlineHint("连接服务器后显示在线设备")
            return
        }
        if (devices.isEmpty()) {
            onlineCount.text = "获取中…"
            onlineCount.setTextColor(Design.Color.INK_MUTED)
            (onlineCount.background as? GradientDrawable)?.setColor(Design.Color.NEUTRAL_TINT)
            addOnlineHint("正在获取在线设备…")
            return
        }

        onlineCount.text = "${devices.size} 台在线"
        onlineCount.setTextColor(Design.Color.SUCCESS)
        (onlineCount.background as? GradientDrawable)?.setColor(Design.Color.SUCCESS_TINT)

        devices.forEach { dev ->
            onlineList.addView(buildOnlineRow(dev), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun addOnlineHint(text: String) {
        onlineList.removeAllViews()
        onlineList.addView(
            Design.text(this, text, Design.Text.CAPTION, Design.Color.INK_MUTED),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun buildOnlineRow(dev: com.clipsync.model.OnlineDevice): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Design.dp(this@MainActivity, 6f), 0, Design.dp(this@MainActivity, 6f))
        }
        // 顶行：圆点 + 平台名 + 本机标签 + 时间
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply { background = Design.circleBg(Design.Color.SUCCESS) }
        row.addView(dot, LinearLayout.LayoutParams(
            Design.dp(this, 8f), Design.dp(this, 8f)
        ))
        val name = Design.text(this, dev.displayName, Design.Text.BODY, Design.Color.INK).apply {
            setPadding(Design.dp(this@MainActivity, 10f), 0, 0, 0)
        }
        row.addView(name, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (dev.name.isNotBlank()) {
            val plat = Design.text(this, dev.platformLabel, Design.Text.MICRO, Design.Color.INK_MUTED).apply {
                setPadding(Design.dp(this@MainActivity, 6f), 0, 0, 0)
            }
            row.addView(plat, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        if (dev.self) {
            val selfTag = Design.text(this, "本机", Design.Text.MICRO, Design.Color.PRIMARY_PRESSED).apply {
                background = Design.roundedBg(this@MainActivity, Design.Color.PRIMARY_TINT, Design.Radius.TAG)
                val h = Design.dp(this@MainActivity, 6f)
                val v = Design.dp(this@MainActivity, 2f)
                setPadding(h, v, h, v)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(selfTag, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Design.dp(this@MainActivity, 8f) })
        }
        // IP · 设备ID 靠右
        val sub = Design.text(
            this,
            "${dev.ip} · ${dev.shortId}",
            Design.Text.MICRO,
            Design.Color.INK_MUTED
        )
        row.addView(sub, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            leftMargin = Design.dp(this@MainActivity, 8f)
            gravity = Gravity.END
        })
        outer.addView(row)

        // 第二行：同步开关标签
        val capsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Design.dp(this@MainActivity, 18f), Design.dp(this@MainActivity, 5f), 0, 0)
        }
        capsRow.addView(buildCapTag("剪贴板", dev.clipUp))
        capsRow.addView(buildCapTag("短信", dev.smsIn))
        capsRow.addView(buildCapTag("自动接收", dev.autoPut))
        outer.addView(capsRow)

        // 长按后在设备行水平居中显示的「重命名」按钮
        val renameBtn = android.widget.Button(this).apply {
            text = "重命名"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            isAllCaps = false
            val padH = Design.dp(this@MainActivity, 16f)
            setPadding(padH, 0, padH, 0)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = Design.dp(this@MainActivity, 100f).toFloat()
                setColor(Design.Color.PRIMARY)
            }
            elevation = Design.dp(this@MainActivity, 3f).toFloat()
            stateListAnimator = null
            visibility = View.GONE
            setOnClickListener { showRenameDialog(dev) }
        }

        val wrapper = android.widget.FrameLayout(this).apply {
            addView(outer, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(renameBtn, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
                val vm = Design.dp(this@MainActivity, 3f)
                topMargin = vm
                bottomMargin = vm
            })
        }

        outer.setOnLongClickListener {
            val wasShown = renameBtn.visibility == View.VISIBLE
            hideActiveRenameFab()
            if (!wasShown) {
                renameBtn.visibility = View.VISIBLE
                renameBtn.alpha = 0f
                renameBtn.animate().alpha(1f).setDuration(140).start()
                activeRenameFab = renameBtn
            }
            true
        }
        return wrapper
    }

    private fun hideActiveRenameFab() {
        activeRenameFab?.let { fab ->
            fab.animate().alpha(0f).setDuration(120).withEndAction { fab.visibility = View.GONE }.start()
        }
        activeRenameFab = null
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val active = activeRenameFab
            if (active != null) {
                val xy = IntArray(2).also { active.getLocationOnScreen(it) }
                val hit = ev.rawX >= xy[0] && ev.rawX <= xy[0] + active.width &&
                          ev.rawY >= xy[1] && ev.rawY <= xy[1] + active.height
                if (!hit) hideActiveRenameFab()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showRenameDialog(dev: com.clipsync.model.OnlineDevice) {
        hideActiveRenameFab()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val pad = Design.dp(this, 20f)
        val gap = Design.dp(this, 12f)

        // 卡片容器
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Design.dp(this@MainActivity, 22f), Design.dp(this@MainActivity, 20f),
                Design.dp(this@MainActivity, 22f), Design.dp(this@MainActivity, 18f))
            background = Design.roundedBg(this@MainActivity, Design.Color.SURFACE, Design.Radius.CARD)
            elevation = Design.dp(this@MainActivity, 12f).toFloat()
            minimumWidth = Design.dp(this@MainActivity, 300f)
        }

        // 标题行：主题色圆形图标 + 标题
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconBadge = android.widget.FrameLayout(this).apply {
            background = Design.circleBg(Design.Color.PRIMARY_TINT)
            val s = Design.dp(this@MainActivity, 36f)
            setPadding(0, 0, 0, 0)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "✎"
                setTextColor(Design.Color.PRIMARY)
                textSize = 18f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, android.widget.FrameLayout.LayoutParams(s, s).apply { gravity = Gravity.CENTER })
        }
        titleRow.addView(iconBadge, LinearLayout.LayoutParams(
            Design.dp(this, 36f), Design.dp(this, 36f)
        ))
        titleRow.addView(Design.text(this, "重命名设备", Design.Text.CARD_TITLE, Design.Color.INK, bold = true).apply {
            setPadding(Design.dp(this@MainActivity, 10f), 0, 0, 0)
        })
        card.addView(titleRow)

        card.addView(Design.text(this, "给这台设备起个好记的名字", Design.Text.CAPTION, Design.Color.INK_MUTED).apply {
            setPadding(0, Design.dp(this@MainActivity, 6f), 0, 0)
        })

        // 输入框
        val input = android.widget.EditText(this).apply {
            setText(dev.name)
            setSelection(text.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
            hint = "新名称"
            setTextColor(Design.Color.INK)
            setHintTextColor(Design.Color.INK_DISABLED)
            textSize = Design.Text.BODY
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SUBTLE,
                Design.Color.BORDER,
                Design.Radius.INPUT,
                1.2f
            )
            val ph = Design.dp(this@MainActivity, 12f)
            val pv = Design.dp(this@MainActivity, 10f)
            setPadding(ph, pv, ph, pv)
        }
        val inputLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap }
        card.addView(input, inputLp)

        // 错误提示
        val errorView = Design.text(this, "", Design.Text.CAPTION, Design.Color.DANGER).apply {
            visibility = View.GONE
        }
        card.addView(errorView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, 6f) })

        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelBtn = android.widget.Button(this).apply {
            text = "取消"
            textSize = Design.Text.CAPTION
            setTextColor(Design.Color.INK_SECONDARY)
            isAllCaps = false
            background = Design.roundedBg(this@MainActivity, Design.Color.SUBTLE, Design.Radius.BUTTON)
            val ph = Design.dp(this@MainActivity, 18f)
            val pv = Design.dp(this@MainActivity, 8f)
            setPadding(ph, pv, ph, pv)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setOnClickListener { dialog.dismiss() }
        }
        val saveBtn = android.widget.Button(this).apply {
            text = "保存"
            textSize = Design.Text.CAPTION
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            isAllCaps = false
            background = Design.roundedBg(this@MainActivity, Design.Color.PRIMARY, Design.Radius.BUTTON)
            val ph = Design.dp(this@MainActivity, 22f)
            val pv = Design.dp(this@MainActivity, 8f)
            setPadding(ph, pv, ph, pv)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Design.dp(this@MainActivity, 8f) })
        card.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, 18f) })

        fun save() {
            val newName = input.text.toString().trim().take(32)
            saveBtn.isEnabled = false
            cancelBtn.isEnabled = false
            saveBtn.text = "保存中…"
            lifecycleScope.launch {
                val server = getSharedPreferences("clipsync", MODE_PRIVATE)
                    .getString("server", null) ?: BuildConfig.DEFAULT_SERVER
                val result = runCatching {
                    com.clipsync.net.AuthClient.renameDevice(
                        serverUrl = server,
                        token = com.clipsync.net.AuthClient.savedToken(this@MainActivity),
                        deviceId = dev.deviceId,
                        name = newName
                    )
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess {
                        // 改的是本机：写入机器级存储，换用户登录时沿用
                        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
                        val myID = sp.getString("device_id", null)
                        if (myID != null && myID == dev.deviceId) {
                            sp.edit().putString("device_name", newName).apply()
                        }
                        dialog.dismiss()
                    }.onFailure {
                            errorView.text = "改名失败：${it.message}"
                            errorView.visibility = View.VISIBLE
                            saveBtn.isEnabled = true
                            cancelBtn.isEnabled = true
                            saveBtn.text = "保存"
                        }
                }
            }
        }
        saveBtn.setOnClickListener { save() }

        // 外层：半透明遮罩 + 居中卡片
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            setPadding(pad, pad, pad, pad)
            addView(card)
            setOnClickListener { dialog.dismiss() }
        }
        card.setOnClickListener { /* 吞掉点击，防止点卡片关闭 */ }

        dialog.setContentView(root)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.show()

        // 弹出键盘
        input.requestFocus()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
    }

    private fun buildCapTag(text: String, on: Boolean): View {
        val tag = Design.text(this, text, Design.Text.MICRO,
            if (on) Design.Color.SUCCESS else Design.Color.INK_MUTED).apply {
            background = Design.roundedBg(
                this@MainActivity,
                if (on) 0x2610B981.toInt() else 0x14000000,
                Design.Radius.TAG
            )
            setPadding(Design.dp(this@MainActivity, 7f), Design.dp(this@MainActivity, 2f),
                Design.dp(this@MainActivity, 7f), Design.dp(this@MainActivity, 2f))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = Design.dp(this, 6f)
        tag.layoutParams = lp
        return tag
    }

    // MARK: - 剪贴板预览卡（占满中间）

    private fun buildClipCard(): View {
        clipCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SURFACE,
                Design.Color.BORDER_CARD,
                Design.Radius.CARD
            )
            val p = Design.dp(this@MainActivity, 15f)
            setPadding(p, p, p, p)
        }

        // 标题行：标题 + 右侧类型标签（文本 / 图片）
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            Design.text(this, "当前剪贴板", Design.Text.CARD_TITLE, Design.Color.INK, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        clipTypeTag = Design.text(this, "文本", Design.Text.TAG, Design.Color.INK_SECONDARY).apply {
            background = Design.roundedBg(
                this@MainActivity, Design.Color.CANVAS, Design.Radius.TAG
            )
            val h = Design.dp(this@MainActivity, Design.Space.S)
            val v = Design.dp(this@MainActivity, 3f)
            setPadding(h, v, h, v)
        }
        titleRow.addView(clipTypeTag)
        clipCard.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 内容区：文本态固定 96dp，图片态按原图比例放大到能看全（见 showImagePreview）。
        // 文本不撑开是为了让一行和一百行的卡片一样高；图片则必须完整显示，
        // 反正按钮在悬浮层，卡片变高也推不动它。
        clipContentFrame = FrameLayout(this).apply {
            background = Design.outlinedBg(
                this@MainActivity,
                Design.Color.SUBTLE,
                Design.Color.BORDER,
                Design.Radius.INPUT
            )
            clipToOutline = true
        }
        clipPreviewText = Design.text(this, "", Design.Text.CAPTION, Design.Color.INK_SECONDARY).apply {
            setLineSpacing(0f, 1.7f)
            gravity = Gravity.TOP
            visibility = View.GONE
            val h = Design.dp(this@MainActivity, 13f)
            val v = Design.dp(this@MainActivity, Design.Space.M)
            setPadding(h, v, h, v)
        }
        clipPreviewImage = ImageView(this).apply {
            // FIT_CENTER：整张图缩放进可视区，不裁边。配合下面按比例算出的高度，
            // 上下不会留出多余空白
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        clipContentFrame.addView(clipPreviewImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        clipContentFrame.addView(clipPreviewText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        clipCard.addView(clipContentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Design.dp(this, Design.CLIP_PREVIEW_HEIGHT)
        ).apply { topMargin = Design.dp(this@MainActivity, 11f) })

        // 脚注：说明预览是截断的，完整内容照样会推送
        clipFootnote = Design.text(
            this, "仅显示前几行，完整内容会一并推送", Design.Text.MICRO, Design.Color.INK_MUTED
        )
        clipCard.addView(clipFootnote, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Design.dp(this@MainActivity, 9f) })

        return clipCard
    }

    // MARK: - 状态渲染

    private fun renderState(state: String) {
        if (isDestroyed) return
        if (!::ringOuter.isInitialized || !::statusText.isInitialized ||
            !::statusHint.isInitialized || !::toggleBtn.isInitialized
        ) {
            return
        }
        // 被踢下线时根据 reason 决定是否显示"修改密码"按钮：
        //  - 密码被重置：显示"修改密码"，弹框让用户填新密码后重连
        //  - 其他（账号禁用、设备禁用、单纯踢一脚）：隐藏按钮，只显示提示
        val kicked = ConnectionBus.kicked
        val kickedReason = ConnectionBus.kickedReason
        val showChangePwd = kickedReason == ConnectionBus.KICKED_PASSWORD_RESET ||
            kickedReason == ConnectionBus.KICKED_OTHER ||
            (kickedReason == null && kicked)
        if (::changePwdBtn.isInitialized) {
            changePwdBtn.visibility = if (kicked && showChangePwd) View.VISIBLE else View.GONE
        }
        when (state) {
            ConnectionBus.STATE_OPEN -> {
                stopDotAnimation()
                applyRingColors(
                    Design.Color.SUCCESS_TINT_SOFT,
                    Design.Color.SUCCESS_TINT,
                    Design.Color.SUCCESS
                )
                statusText.text = "已连接"
                statusHint.text = serverAddr
                statusHint.setTextColor(Design.Color.INK_MUTED)
                toggleBtn.text = "停止同步"
                toggleBtn.isEnabled = true
                // 已连接成功，清掉"曾在连接中"标记，避免之后正常断开也弹失败
                lastWasConnecting = false
                return
            }
            ConnectionBus.STATE_CONNECTING -> {
                startDotAnimation()
                applyRingColors(
                    Design.Color.WARNING_TINT_SOFT,
                    Design.Color.WARNING_TINT,
                    Design.Color.WARNING
                )
                statusText.text = "连接中"
                statusHint.text = serverAddr
                statusHint.setTextColor(Design.Color.INK_MUTED)
                toggleBtn.text = "取消连接"
                toggleBtn.isEnabled = true
                lastWasConnecting = true
                return
            }
            else -> {
                // 被踢下线：固定显示原因，不自动重连，引导用户改密码
                if (kicked) {
                    stopDotAnimation()
                    applyRingColors(
                        Design.Color.NEUTRAL_TINT_SOFT,
                        Design.Color.DANGER_TINT,
                        Design.Color.DANGER
                    )
                    statusText.text = "已下线"
                    statusHint.text = ConnectionBus.failureReason ?: "密码已失效，请修改密码"
                    statusHint.setTextColor(Design.Color.DANGER)
                    toggleBtn.text = "启动同步"
                    toggleBtn.isEnabled = true
                    lastWasConnecting = false
                    return
                }
                // 服务还活着 = 只是瞬时断线，内部在自动重连，不报失败。
                // 但已经有明确失败原因时（账密不对 / 网络不通）就别再假装在重连了。
                val reason = ConnectionBus.failureReason
                val serviceAlive = SyncService.activeWs() != null && reason == null
                if (serviceAlive) {
                    startDotAnimation()
                    applyRingColors(
                        Design.Color.WARNING_TINT_SOFT,
                        Design.Color.WARNING_TINT,
                        Design.Color.WARNING
                    )
                    statusText.text = "重连中"
                    statusHint.text = serverAddr
                    statusHint.setTextColor(Design.Color.INK_MUTED)
                    toggleBtn.text = "取消连接"
                } else {
                    stopDotAnimation()
                    applyRingColors(
                        Design.Color.NEUTRAL_TINT_SOFT,
                        Design.Color.NEUTRAL_TINT,
                        Design.Color.NEUTRAL
                    )
                    statusText.text = "未连接"
                    // 优先展示服务端 / 网络层给出的具体原因，否则显示服务器地址
                    statusHint.text = when {
                        reason != null -> reason
                        else -> serverAddr
                    }
                    statusHint.setTextColor(
                        if (reason != null) Design.Color.DANGER else Design.Color.INK_MUTED
                    )
                    toggleBtn.text = "启动同步"
                }
                toggleBtn.isEnabled = true
                lastWasConnecting = false
            }
        }
    }

    private fun renderTarget() {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val raw = sp.getString("server", null) ?: com.clipsync.BuildConfig.DEFAULT_SERVER
        serverAddr = com.clipsync.net.ServerAddress.normalize(raw).ifEmpty { "未填写服务器地址" }
        // 地址变了就刷新状态提示
        renderState(ConnectionBus.current)
    }

    // MARK: - 剪贴板预览（修复图片显示）

    private fun refreshClipPreview() {
        val shot = com.clipsync.clipboard.ScreenshotWatcher.peekPending()
        val shotNewer = shot != null &&
            com.clipsync.clipboard.ScreenshotWatcher.pendingTimestamp() >=
            ClipboardManagerHelper.clipTimestamp()

        // 1. 有新截图（比剪贴板新）→ 优先显示截图
        if (shot != null && shotNewer) {
            showScreenshot(shot)
            return
        }

        // 2. 真实剪贴板里的图片
        val clipImage = ClipboardManagerHelper.peekClipboardImageUri()
        if (clipImage != null) {
            val bmp = ClipboardManagerHelper.decodeImageUri(clipImage)
            if (bmp != null) {
                showImagePreview(
                    bmp, "图片",
                    "${bmp.width} × ${bmp.height} · 点「推送到电脑」发送原图"
                )
                return
            }
            android.util.Log.w("ClipSync", "refreshClipPreview: 图片解码失败 uri=$clipImage")
            showTextPreview("（图片无法预览，但仍可尝试推送）", "图片", "解码失败，仍可尝试推送原图")
            return
        }

        // 3. 剪贴板文本
        val text = ClipboardManagerHelper.peekText()
        if (!text.isNullOrBlank()) {
            val chars = text.length
            showTextPreview(
                text,
                "文本 · $chars 字",
                if (chars > 120) "仅显示前几行，完整内容会一并推送" else "内容将完整推送"
            )
            return
        }

        // 4. 剪贴板为空 → 回落截图
        if (shot != null) {
            showScreenshot(shot)
            return
        }

        // 5. 什么都没有
        showEmptyPreview()
    }

    /** 文本态：显示文字、隐藏图片，并同步标签与脚注 */
    private fun showTextPreview(body: CharSequence, tag: String, footnote: String) {
        clipPreviewImage.visibility = View.GONE
        clipPreviewImage.setImageDrawable(null)
        // 复位高度：从图片态切回来时若不复位，文字下面会留一大块空白
        setPreviewHeight(Design.dp(this, Design.CLIP_PREVIEW_HEIGHT))
        resetPreviewBackground()
        clipPreviewText.visibility = View.VISIBLE
        clipPreviewText.text = body
        clipTypeTag.visibility = View.VISIBLE
        clipTypeTag.text = tag
        resetTagStyle()
        clipFootnote.visibility = View.VISIBLE
        clipFootnote.text = footnote
        setPushEnabled(true)
    }

    /** 图片态：按原图比例撑到能看全，最多到 CLIP_IMAGE_MAX_HEIGHT */
    private fun showImagePreview(bmp: Bitmap, tag: String, footnote: String) {
        clipPreviewText.visibility = View.GONE
        clipPreviewImage.setImageBitmap(bmp)
        clipPreviewImage.visibility = View.VISIBLE
        applyImageHeight(bmp)
        // 竖图缩进来后两侧会留白，浅底配深色截图显得空，换成中性灰衬底
        (clipContentFrame.background as? GradientDrawable)?.setColor(Design.Color.SUBTLE)
        clipTypeTag.visibility = View.VISIBLE
        clipTypeTag.text = tag
        resetTagStyle()
        clipFootnote.visibility = View.VISIBLE
        clipFootnote.text = footnote
        setPushEnabled(true)
    }

    /**
     * 给图片预览区定高，保证整张图都看得见。
     *
     * 原来是固定 96dp + CENTER_CROP，竖图只能露出中间一条。
     * 现在按原图比例算需要多高：横图和方图能铺满宽度就铺满；
     * 竖图（手机截图都是）铺满宽度要 2000px 以上，屏幕放不下，
     * 所以到上限就封顶，由 FIT_CENTER 把整张图缩进去、两侧留白。
     * 无论哪种情况都不裁切——这是「显示不全」的根治点。
     */
    private fun applyImageHeight(bmp: Bitmap) {
        val maxHeight = Design.dp(this, Design.CLIP_IMAGE_MAX_HEIGHT)
        val minHeight = Design.dp(this, 72f)
        val available = clipContentFrame.width
        if (available <= 0 || bmp.width <= 0) {
            // 首帧还没测量出宽度，先用上限占位，测完再按真实比例收一次
            setPreviewHeight(maxHeight)
            clipContentFrame.post { applyImageHeight(bmp) }
            return
        }
        val needed = available.toFloat() * bmp.height / bmp.width
        setPreviewHeight(needed.toInt().coerceIn(minHeight, maxHeight))
    }

    private fun setPreviewHeight(px: Int) {
        val lp = clipContentFrame.layoutParams
        if (lp.height != px) {
            lp.height = px
            clipContentFrame.layoutParams = lp
        }
    }

    /** 文本/空态复位衬底色，否则会残留图片态的中性灰 */
    private fun resetPreviewBackground() {
        (clipContentFrame.background as? GradientDrawable)?.setColor(Design.Color.SUBTLE)
    }

    /**
     * 复位类型标签配色。
     * 截图分支会把标签改成主色调，不复位的话会残留到下一次普通图片/文本预览。
     */
    private fun resetTagStyle() {
        clipTypeTag.setTextColor(Design.Color.INK_SECONDARY)
        (clipTypeTag.background as? GradientDrawable)?.setColor(Design.Color.CANVAS)
    }

    /** 空态：主按钮置灰，避免用户点了才知道没内容 */
    private fun showEmptyPreview() {
        clipPreviewImage.visibility = View.GONE
        clipPreviewImage.setImageDrawable(null)
        setPreviewHeight(Design.dp(this, Design.CLIP_PREVIEW_HEIGHT))
        resetPreviewBackground()
        clipPreviewText.visibility = View.VISIBLE
        clipPreviewText.text = "剪贴板为空\n复制任意内容后回到这里"
        clipTypeTag.visibility = View.GONE
        clipFootnote.visibility = View.GONE
        setPushEnabled(false)
    }

    /** 主按钮启用态：连背景色一起换，置灰要看得出来 */
    private fun setPushEnabled(enabled: Boolean) {
        pushBtn.isEnabled = enabled
        (pushBtn.background as? GradientDrawable)?.setColor(
            if (enabled) Design.Color.PRIMARY else Design.Color.DISABLED_BG
        )
        pushBtn.setTextColor(
            if (enabled) Design.Color.SURFACE else Design.Color.INK_DISABLED
        )
    }

    private fun showScreenshot(shot: android.net.Uri) {
        val bmp = ClipboardManagerHelper.decodeImageUri(shot)
        if (bmp != null) {
            val size = "${bmp.width} × ${bmp.height}"
            showImagePreview(bmp, "新截图", "$size · 点「推送到电脑」发送原图")
            // 截图标签用主色调，和普通图片区分开
            clipTypeTag.setTextColor(Design.Color.PRIMARY_PRESSED)
            (clipTypeTag.background as? GradientDrawable)?.setColor(Design.Color.PRIMARY_TINT)
            android.util.Log.i("ClipSync", "refreshClipPreview: 回落截图 $shot")
        }
    }

    private fun pushClipboard() {
        if (!ClipboardManagerHelper.hasContent()) {
            toast.show("剪贴板为空", Design.Color.NEUTRAL)
            return
        }
        if (ConnectionBus.current != ConnectionBus.STATE_OPEN) {
            toast.show("未连接，请先启动同步", Design.Color.WARNING)
            return
        }
        when (ClipboardManagerHelper.manualUpload()) {
            com.clipsync.clipboard.ClipboardManagerHelper.UploadResult.SENT ->
                toast.show("已推送到电脑", Design.Color.SUCCESS)
            com.clipsync.clipboard.ClipboardManagerHelper.UploadResult.QUEUED ->
                toast.show("已暂存，连上后自动发送", Design.Color.WARNING)
            com.clipsync.clipboard.ClipboardManagerHelper.UploadResult.FAILED ->
                toast.show("推送失败，请重试", Design.Color.DANGER)
        }
    }

    /** 截图监听需要读媒体库权限，首次启动时请求 */
    private fun requestMediaPermissionIfNeeded() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            @Suppress("DEPRECATION")
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            mediaPermLauncher.launch(perm)
        }
    }

    // MARK: - 启动 / 停止

    /**
     * 启动 / 取消。
     *
     * 判断依据是"同步服务是否还活着"，不是 ConnectionBus 的瞬时状态：
     * 退避重连期间状态会在 connecting 和 closed 之间来回跳，按状态判断的话，
     * 用户点在 closed 那一瞬间反而会再启动一次服务，看起来就是"取消没反应"。
     */
    private fun toggleSync() {
        if (isSyncServiceRunning()) {
            stopSync()
        } else {
            // 用户手动启动时清除踢下线标记
            ConnectionBus.clearKicked()
            renderState(ConnectionBus.STATE_CONNECTING)
            startSync()
        }
    }

    /**
     * 同步服务是否正在连接或已连接。
     *
     * activeWs() 只在 WebSocket 建起来后才非空，"正在换 token"那段它还是 null，
     * 所以要再看一眼总线状态兜住空窗，否则那几百毫秒里「取消」会被当成「启动」。
     * 反过来，已经有明确失败原因时就算是"停下了"，用户该看到「启动」。
     */
    private fun isSyncServiceRunning(): Boolean {
        if (ConnectionBus.failureReason != null) return false
        if (ConnectionBus.kicked) return false
        return SyncService.activeWs() != null ||
            ConnectionBus.current == ConnectionBus.STATE_CONNECTING ||
            ConnectionBus.current == ConnectionBus.STATE_OPEN
    }

    private fun startSync() {
        // 标记用户希望同步运行。SmsReceiver / NotificationSmsListener 看到这个标记才
        // 会拉起服务；用户主动 stopSync() 或划进程后这个标记会被清掉。
        SyncService.setUserEnabled(this, true)
        // 带上 ACTION_CONNECT：服务已存活但连接没建起来时，让它重试而不是空转
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * 启动时自动连接（设置里可关，默认开）。
     *
     * 用户的使用习惯：想用时点开 App，进来就该是连好的，不用再点一下「启动」；
     * 要停就手动把 App 从最近任务划掉（= 停止同步，下次打开再自动连）。
     * 如果某次想静默进 App 不连，设置里关掉「启动时自动连接」即可。
     */
    private fun autoConnectIfNeeded() {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        if (!sp.getBoolean("auto_connect", true)) return
        if (isSyncServiceRunning()) return
        // 被踢下线后不自动重连，等用户改密码
        if (ConnectionBus.kicked) return
        // 上次已经明确失败过（比如密码错了）就别再自动重试，让用户先去改设置
        if (ConnectionBus.failureReason != null) return
        renderState(ConnectionBus.STATE_CONNECTING)
        startSync()
    }

    private fun stopSync() {
        // 同步未启用 → 后续短信直接丢弃，不会因为一条验证码又把服务拉起来
        SyncService.setUserEnabled(this, false)
        stopService(Intent(this, SyncService::class.java))
        // 清掉本地 token：下次启动强制走账号密码登录，
        // 避免后端改了密码但旧 token 还能用的情况
        com.clipsync.net.AuthClient.clearSession(this)
        // 主动停止不是"失败"，把上一次的错误原因清掉，别让红字留在界面上
        ConnectionBus.publish(ConnectionBus.STATE_CLOSED, null)
        renderState(ConnectionBus.STATE_CLOSED)
        lastWasConnecting = false
    }

    /**
     * 弹框修改密码：填完确定后保存新密码，清除 kicked 标记，自动重连。
     */
    private fun showChangePasswordDialog() {
        val input = com.clipsync.ui.DesignDialog.input(
            this, "请输入新密码", "",
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        com.clipsync.ui.DesignDialog.show(
            this, "修改密码", "密码已被管理员重置，请输入新密码后重新连接",
            confirmLabel = "确定并重连",
            body = { it.addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )) }
        ) {
            val newPwd = input.text.toString()
            if (newPwd.isEmpty()) {
                toast.show("请输入新密码", Design.Color.WARNING)
                return@show
            }
            // 保存新密码，作废旧 token
            com.clipsync.net.AuthClient.saveCredentials(
                this,
                com.clipsync.net.AuthClient.savedUsername(this),
                newPwd
            )
            com.clipsync.net.AuthClient.clearSession(this)
            // 清除踢下线标记，重新启动同步
            ConnectionBus.clearKicked()
            toast.show("密码已更新，正在重连…", Design.Color.SUCCESS)
            renderState(ConnectionBus.STATE_CONNECTING)
            startSync()
        }
    }

    /**
     * 连接中的呼吸动画。
     * 缩放中圈而不是改透明度：光环有三层，只淡入淡出内核看不出来，
     * 中圈轻微搏动更接近"正在尝试"的感觉。
     */
    private fun startDotAnimation() {
        if (dotAnimator?.isStarted == true) return
        if (!::ringMid.isInitialized) return
        dotAnimator = ObjectAnimator.ofFloat(ringMid, "alpha", 1f, 0.35f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopDotAnimation() {
        dotAnimator?.cancel()
        dotAnimator = null
        if (::ringMid.isInitialized) ringMid.alpha = 1f
    }
}
