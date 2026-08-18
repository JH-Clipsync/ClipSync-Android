package com.clipsync.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clipsync.BuildConfig
import com.clipsync.clipboard.ClipboardManagerHelper
import com.clipsync.crypto.PayloadCipher
import com.clipsync.net.AuthClient
import com.clipsync.net.ServerAddress
import com.clipsync.service.SyncService
import com.clipsync.state.ConnectionBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 这一版把原来「功能设置」二级页的内容全部并了进来：服务器、账号、
 * 端到端加密、剪贴板开关，改成四个分组列表直接铺开，少一层跳转。
 * 权限设置仍是独立页——它交互重、要跳系统设置，混在这里会很乱。
 *
 * 编辑值用弹窗而不是内嵌输入框：同步密码要跑 20 万轮 PBKDF2，
 * 边打字边保存会卡死输入，弹窗天然就是「填完点确定」的语义。
 */
class SettingsActivity : AppCompatActivity() {

    /** 进入时的连接配置快照，离开时比对决定要不要重连 */
    private var connectionSnapshotAtEntry: List<String> = emptyList()

    private var fingerprintJob: Job? = null

    // 需要在编辑后刷新的行
    private lateinit var serverValue: android.widget.TextView
    private lateinit var accountValue: android.widget.TextView
    private lateinit var syncPwdValue: android.widget.TextView
    private var permBadge: android.widget.TextView? = null

    /** 指纹说明文案，加密开关和密码变更后都要刷新它 */
    private var fingerprintView: android.widget.TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ClipboardManagerHelper.loadPrefs(this)
        connectionSnapshotAtEntry = connectionSnapshot()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Design.Color.CANVAS)
            val h = Design.dp(this@SettingsActivity, Design.Space.L)
            setPadding(h, h, h, Design.dp(this@SettingsActivity, 32f))
        }

        buildConnectionGroup(container)
        buildCryptoGroup(container)
        buildClipboardGroup(container)
        buildOtherGroup(container)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Design.Color.CANVAS)
            addView(container)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBadge()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * 离开设置页时，只要连接相关配置变了就重启同步服务。
     * 地址、账密要重新换 token，加密设置要重新派生密钥——
     * 这些参数都是 WsClient 构造时读的，不重启不生效。
     */
    override fun onPause() {
        super.onPause()
        val now = connectionSnapshot()
        if (now != connectionSnapshotAtEntry) {
            connectionSnapshotAtEntry = now
            if (SyncService.activeWs() != null) {
                SyncService.restart(this)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.refresh(this)
        recreate()
    }

    private fun connectionSnapshot(): List<String> {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        return listOf(
            AuthClient.savedUsername(this),
            AuthClient.savedPassword(this),
            // 比规范化后的值，否则"补上 ws:// 前缀"这个自动回写会被误判成用户改了地址
            ServerAddress.normalize(sp.getString("server", "") ?: ""),
            PayloadCipher.isEnabled(this).toString(),
            PayloadCipher.syncPassword(this)
        )
    }

    // MARK: - 分组：连接

    private fun buildConnectionGroup(parent: LinearLayout) {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val card = SettingsRows.group(this, parent, "连接")

        val server = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        serverValue = SettingsRows.valueRow(
            this, card, "服务器",
            ServerAddress.displayForm(server).ifEmpty { "未填写" },
            mono = true
        ) { editServer() }

        SettingsRows.separator(this, card)
        accountValue = SettingsRows.valueRow(
            this, card, "账号", accountSummary()
        ) { editAccount() }

        SettingsRows.separator(this, card)
        SettingsRows.switchRow(
            this, card, "启动时自动连接", null,
            sp.getBoolean("auto_connect", true)
        ) { checked ->
            sp.edit().putBoolean("auto_connect", checked).apply()
        }
    }

    private fun accountSummary(): String {
        val name = AuthClient.savedUsername(this).ifEmpty { BuildConfig.DEFAULT_USERNAME }
        return when {
            name.isEmpty() -> "未填写"
            AuthClient.isLoggedIn(this) -> "$name · 已登录"
            AuthClient.hasCredentials(this) -> name
            else -> "$name · 缺密码"
        }
    }

    /** 地址只填 host:port，ws:// 由 ServerAddress.normalize 补齐 */
    private fun editServer() {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val current = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        val input = DesignDialog.input(
            this, "例如 127.0.0.1:8080",
            ServerAddress.displayForm(current),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        DesignDialog.show(
            this, "服务器地址", "可填 wss:// 或 ws:// 开头的完整地址，也可只填地址和端口",
            body = { it.addView(input, matchWrap()) }
        ) {
            val normalized = ServerAddress.normalize(input.text.toString())
            sp.edit().putString("server", normalized).apply()
            serverValue.text = ServerAddress.displayForm(normalized).ifEmpty { "未填写" }
            toast(
                if (normalized.isEmpty()) "地址已清空" else "将连接 $normalized",
                if (normalized.isEmpty()) Design.Color.NEUTRAL else Design.Color.SUCCESS
            )
        }
    }

    /**
     * 账号密码边填边存，连接时自动校验，所以没有「登录」按钮。
     * 改了账号或密码就作废本地旧 token，否则下次连接还会拿旧身份去连。
     */
    private fun editAccount() {
        val nameEdit = DesignDialog.input(
            this, "用户名",
            AuthClient.savedUsername(this).ifEmpty { BuildConfig.DEFAULT_USERNAME },
            InputType.TYPE_CLASS_TEXT
        )
        val pwdEdit = DesignDialog.input(
            this, "密码", AuthClient.savedPassword(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        DesignDialog.show(
            this, "账号", "账号由管理员在服务端创建，客户端不提供注册",
            confirmLabel = "保存并连接",
            body = { col ->
                col.addView(nameEdit, matchWrap())
                col.addView(pwdEdit, matchWrap(Design.Space.M))
            }
        ) {
            val name = nameEdit.text.toString().trim()
            val pwd = pwdEdit.text.toString()
            // 账号或密码变了就作废本地旧 token，否则下次连接还会拿旧身份去连
            if (name != AuthClient.savedUsername(this) ||
                pwd != AuthClient.savedPassword(this)
            ) {
                AuthClient.clearSession(this)
            }
            AuthClient.saveCredentials(this, name, pwd)
            accountValue.text = accountSummary()
            // 保存后直接发起连接：填完账号密码最自然的下一步就是连上去。
            // 已在运行就重启服务，让新账号密码 / token 立刻生效。
            SyncService.setUserEnabled(this, true)
            ConnectionBus.clearKicked()
            SyncService.restart(this)
        }
    }

    // MARK: - 分组：端到端加密

    private fun buildCryptoGroup(parent: LinearLayout) {
        val card = SettingsRows.group(this, parent, "端到端加密")

        // 开关排在最前，密码行和指纹说明归入下面的 detail 分组；
        // 两者互相引用（开关要收起 detail，detail 里的指纹要被开关刷新），
        // 所以先声明后赋值。
        lateinit var fingerprint: android.widget.TextView
        lateinit var detail: LinearLayout

        SettingsRows.switchRow(
            this, card, "启用加密", "服务端只转发密文",
            PayloadCipher.isEnabled(this)
        ) { checked ->
            PayloadCipher.setEnabled(this, checked)
            detail.visibility = if (checked) View.VISIBLE else View.GONE
            refreshFingerprint(fingerprint)
        }

        detail = SettingsRows.subgroup(this, card)

        SettingsRows.separator(this, detail)
        syncPwdValue = SettingsRows.valueRow(
            this, detail, "同步密码", syncPasswordSummary()
        ) { editSyncPassword() }
        fingerprint = SettingsRows.note(this, detail, "")
        fingerprintView = fingerprint

        detail.visibility = if (PayloadCipher.isEnabled(this)) View.VISIBLE else View.GONE
        refreshFingerprint(fingerprint)
    }

    private fun syncPasswordSummary(): String =
        if (PayloadCipher.usingBuiltinPassword(this)) "内置默认密码" else "已自设"

    /**
     * 同步密码用弹窗填：派生一次要跑 20 万轮 PBKDF2（真机约 2.8 秒），
     * 边打字边保存会把输入框卡死，弹窗天然是「填完点确定」。
     */
    private fun editSyncPassword() {
        val input = DesignDialog.input(
            this, "留空则用内置默认密码",
            PayloadCipher.syncPassword(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        DesignDialog.show(
            this, "同步密码", "两端密码一致才能互相解密。保存后会重新派生密钥，约需几秒。",
            body = { it.addView(input, matchWrap()) }
        ) {
            val typed = input.text.toString()
            if (typed == PayloadCipher.syncPassword(this)) {
                toast("同步密码未改动", Design.Color.NEUTRAL)
            } else {
                PayloadCipher.setSyncPassword(this, typed)
                syncPwdValue.text = syncPasswordSummary()
                fingerprintView?.let { refreshFingerprint(it) }
                toast(
                    if (typed.isEmpty()) "已清空，将使用内置默认密码" else "同步密码已保存",
                    if (typed.isEmpty()) Design.Color.NEUTRAL else Design.Color.SUCCESS
                )
            }
        }
    }

    /**
     * 指纹是慢活儿：状态文案先立刻显示，算完再把指纹补到后面。
     * 每次重算都取消上一次未完成的任务，避免旧结果覆盖新状态。
     */
    private fun refreshFingerprint(view: android.widget.TextView) {
        fingerprintJob?.cancel()

        if (!PayloadCipher.isEnabled(this)) {
            view.text = "加密已关闭，消息以明文传输"
            view.setTextColor(Design.Color.INK_MUTED)
            return
        }

        val builtin = PayloadCipher.usingBuiltinPassword(this)
        val prefix = if (builtin) {
            "正在使用内置默认密码，各端通用，强度低于自设密码"
        } else {
            "正在使用自设同步密码"
        }
        view.text = "$prefix\n密钥指纹计算中…"
        view.setTextColor(if (builtin) Design.Color.WARNING else Design.Color.INK_MUTED)

        val password = PayloadCipher.effectivePassword(this)
        fingerprintJob = lifecycleScope.launch {
            val fp = withContext(Dispatchers.Default) {
                PayloadCipher.fingerprint(password)
            }
            view.text = when {
                fp == null -> "$prefix\n无法派生密钥，请换一个密码"
                else -> "$prefix\n密钥指纹 $fp"
            }
        }
    }

    // MARK: - 分组：剪贴板同步

    private fun buildClipboardGroup(parent: LinearLayout) {
        val card = SettingsRows.group(this, parent, "剪贴板同步")

        SettingsRows.switchRow(
            this, card, "自动应用远端内容", "电脑复制后直接写入本机剪贴板",
            ClipboardManagerHelper.autoApplyEnabled
        ) { checked ->
            ClipboardManagerHelper.autoApplyEnabled = checked
            ClipboardManagerHelper.savePrefs(this)
        }

        SettingsRows.separator(this, card)
        SettingsRows.switchRow(
            this, card, "自动推送到电脑", "关闭后需在首页手动推送",
            ClipboardManagerHelper.uploadEnabled
        ) { checked ->
            ClipboardManagerHelper.uploadEnabled = checked
            ClipboardManagerHelper.savePrefs(this)
        }
    }

    // MARK: - 分组：其他

    private fun buildOtherGroup(parent: LinearLayout) {
        val card = SettingsRows.group(this, parent, "其他")

        permBadge = SettingsRows.navRow(
            this, card, "权限", "短信监听与后台保活所需",
            badge = pendingPermissionLabel()
        ) {
            startActivity(Intent(this, PermissionSettingsActivity::class.java))
        }

        SettingsRows.separator(this, card)
        SettingsRows.valueRow(this, card, "历史记录", "查看收发明细") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        SettingsRows.separator(this, card)
        SettingsRows.valueRow(this, card, "版本", BuildConfig.VERSION_NAME)
    }

    /**
     * 徽章始终创建（哪怕当前没有待办），否则权限补齐后再变差就没有 View 可更新。
     */
    private fun pendingPermissionLabel(): String {
        val pending = PermissionStatus.pendingCount(this)
        return if (pending == 0) "已就绪" else "$pending 项待开启"
    }

    private fun refreshPermissionBadge() {
        val badge = permBadge ?: return
        val pending = PermissionStatus.pendingCount(this)
        badge.text = pendingPermissionLabel()
        if (pending == 0) {
            badge.setTextColor(Design.Color.SUCCESS)
            badge.background = Design.outlinedBg(
                this, Design.Color.SUCCESS_TINT_SOFT, Design.Color.SUCCESS_TINT, 5f
            )
        } else {
            badge.setTextColor(Design.Color.WARNING)
            badge.background = Design.outlinedBg(
                this, Design.Color.WARNING_TINT_SOFT, Design.Color.WARNING_BORDER, 5f
            )
        }
    }

    // MARK: - 辅助

    /** 弹窗里叠输入框用的布局参数，topDp 撑出行间距 */
    private fun matchWrap(topDp: Float = 0f) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Design.dp(this@SettingsActivity, topDp) }

    /** 懒初始化：内容根要等 setContentView 之后才存在 */
    private val topToast by lazy { DesignToast.attach(this) }

    private fun toast(msg: String, tone: Int = Design.Color.PRIMARY) {
        topToast.show(msg, tone)
    }
}
