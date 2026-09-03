package com.clipsync.ui

import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.clipsync.clipboard.ClipboardImageStore
import com.clipsync.history.HistoryStore

/**
 * 历史记录页：顶部 chip 筛选 + 卡片式列表。
 *
 * 旧版三个筛选按钮是等宽实心蓝块，视觉重量压过了内容本身；
 * 现在改成 chip：选中态用深墨色实心，未选是白底描边，
 * 让注意力回到记录内容上。
 */
class HistoryActivity : AppCompatActivity() {

    private var filter: String = FILTER_ALL
    private val chips = mutableMapOf<String, TextView>()
    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        title = "历史记录"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Design.Color.CANVAS)
        }
        root.addView(buildFilterBar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 列表和空态叠在同一块区域，切换时只改可见性
        val stack = FrameLayout(this)
        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            // 列表自己不画选中底色：行卡片已经是白底，系统高亮会显得脏
            setSelector(android.R.color.transparent)
            clipToPadding = false
            val h = Design.dp(this@HistoryActivity, Design.Space.L)
            setPadding(h, 0, h, Design.dp(this@HistoryActivity, 24f))
        }
        emptyView = buildEmptyView()
        stack.addView(listView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        stack.addView(emptyView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        root.addView(stack, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        applyFilter(FILTER_ALL)

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = (listView.adapter as HistoryAdapter).getItem(position)
            // 图片：点击打开全屏查看器（可在里面复制/分享），不再直接复制
            if (item.kind == "image" && item.imageName.isNotEmpty()) {
                ImageViewerActivity.open(this, item.imageName)
            } else {
                copyItem(item)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // MARK: - 筛选栏

    private fun buildFilterBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Design.Color.CANVAS)
            val h = Design.dp(this@HistoryActivity, Design.Space.L)
            setPadding(h, Design.dp(this@HistoryActivity, Design.Space.M), h, h)
        }
        listOf(
            FILTER_ALL to "全部",
            FILTER_SMS to "短信",
            FILTER_CLIP to "剪贴板"
        ).forEachIndexed { index, (key, label) ->
            val chip = buildChip(label) { applyFilter(key) }
            chips[key] = chip
            bar.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) marginStart = Design.dp(this@HistoryActivity, Design.Space.S)
            })
        }

        // 条数放在筛选栏右端：它是筛选结果的注脚，不值得单独占一行
        countText = Design.text(this, "", Design.Text.MICRO, Design.Color.INK_MUTED).apply {
            gravity = Gravity.END
        }
        bar.addView(countText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = Design.dp(this@HistoryActivity, Design.Space.S) })
        return bar
    }

    private fun buildChip(label: String, onClick: () -> Unit): TextView =
        Design.text(this, label, Design.Text.CAPTION, Design.Color.INK_SECONDARY).apply {
            val h = Design.dp(this@HistoryActivity, 14f)
            val v = Design.dp(this@HistoryActivity, 7f)
            setPadding(h, v, h, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun applyFilter(key: String) {
        filter = key
        chips.forEach { (chipKey, chip) ->
            val active = chipKey == key
            chip.setTextColor(if (active) Design.Color.SURFACE else Design.Color.INK_SECONDARY)
            chip.background = if (active) {
                Design.roundedBg(this, Design.Color.INK, Design.Radius.CHIP)
            } else {
                Design.outlinedBg(
                    this, Design.Color.SURFACE, Design.Color.BORDER, Design.Radius.CHIP
                )
            }
        }
        refresh()
    }

    private fun buildEmptyView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        col.addView(View(this).apply {
            background = Design.circleBg(Design.Color.NEUTRAL_TINT)
        }, LinearLayout.LayoutParams(
            Design.dp(this, 44f), Design.dp(this, 44f)
        ))
        col.addView(
            Design.text(this, "暂无记录", Design.Text.CARD_TITLE, Design.Color.INK_SECONDARY).apply {
                gravity = Gravity.CENTER
                setPadding(0, Design.dp(this@HistoryActivity, Design.Space.L), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        col.addView(
            Design.text(
                this, "同步过的短信和剪贴板会出现在这里",
                Design.Text.MICRO, Design.Color.INK_MUTED
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, Design.dp(this@HistoryActivity, 6f), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return col
    }

    // MARK: - 数据

    private fun refresh() {
        val list = when (filter) {
            FILTER_SMS -> HistoryStore.listSms(this)
            FILTER_CLIP -> HistoryStore.listClip(this)
            else -> HistoryStore.listAll(this)
        }
        listView.adapter = HistoryAdapter(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        countText.text = if (list.isEmpty()) "" else "${list.size} 条"
    }

    private fun copyItem(item: HistoryStore.HistoryItem) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (item.kind == "image" && item.imageName.isNotEmpty()) {
            val ok = ClipboardImageStore.writeToClipboard(this, item.imageName)
            toast(
                if (ok) "图片已复制到剪贴板" else "图片文件已丢失，无法复制",
                if (ok) Design.Color.SUCCESS else Design.Color.DANGER
            )
            return
        }
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("ClipSync", item.text)
        )
        toast("已复制到剪贴板", Design.Color.SUCCESS)
    }

    /** 懒初始化：内容根要等 setContentView 之后才存在 */
    private val topToast by lazy { DesignToast.attach(this) }

    private fun toast(msg: String, tone: Int = Design.Color.PRIMARY) {
        topToast.show(msg, tone)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, MENU_CLEAR, 0, "清空")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_CLEAR -> {
            confirmClear()
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** 清空不可撤销，加一道确认；只清当前筛选的那一类 */
    private fun confirmClear() {
        val scopeLabel = when (filter) {
            FILTER_SMS -> "短信记录"
            FILTER_CLIP -> "剪贴板记录"
            else -> "全部记录"
        }
        DesignDialog.show(
            this, "清空$scopeLabel", "清空后无法恢复。",
            confirmLabel = "清空", destructive = true
        ) {
            when (filter) {
                FILTER_SMS -> HistoryStore.clearSms(this)
                FILTER_CLIP -> HistoryStore.clearClip(this)
                else -> HistoryStore.clearAll(this)
            }
            refresh()
            toast("已清空", Design.Color.SUCCESS)
        }
    }

    // MARK: - 列表行

    /** 行内视图引用，避免每次 getView 都 findViewById */
    private class RowHolder(
        val dot: View,
        val meta: TextView,
        val time: TextView,
        val body: TextView,
        val image: ImageView
    )

    private inner class HistoryAdapter(
        private val items: List<HistoryStore.HistoryItem>
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): HistoryStore.HistoryItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView ?: buildRow()
            bind(row.tag as RowHolder, items[position])
            return row
        }
    }

    private fun buildRow(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.outlinedBg(
                this@HistoryActivity, Design.Color.SURFACE, Design.Color.BORDER_CARD, 14f
            )
            val h = Design.dp(this@HistoryActivity, 14f)
            val v = Design.dp(this@HistoryActivity, Design.Space.M)
            setPadding(h, v, h, v)
        }

        // 元信息行：色点 + 类型/方向 + 右侧相对时间
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this)
        header.addView(dot, LinearLayout.LayoutParams(
            Design.dp(this, 6f), Design.dp(this, 6f)
        ))
        val meta = Design.text(this, "", Design.Text.MICRO, Design.Color.INK_SECONDARY).apply {
            setPadding(Design.dp(this@HistoryActivity, 7f), 0, 0, 0)
        }
        header.addView(meta, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        val time = Design.text(this, "", Design.Text.MICRO, Design.Color.INK_MUTED).apply {
            gravity = Gravity.END
        }
        header.addView(time)
        card.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val body = Design.text(this, "", Design.Text.BODY, Design.Color.INK).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.35f)
            setPadding(0, Design.dp(this@HistoryActivity, 7f), 0, 0)
        }
        card.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 缩略图高度固定：图片尺寸各异，按原比例展开会让滚动时行高乱跳
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            background = Design.roundedBg(this@HistoryActivity, Design.Color.SUBTLE, 10f)
            clipToOutline = true
        }
        card.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Design.dp(this, 120f)
        ).apply { topMargin = Design.dp(this@HistoryActivity, Design.Space.S) })

        // ListView 的行不吃 margin，套一层容器撑出行间距
        return FrameLayout(this).apply {
            setPadding(0, 0, 0, Design.dp(this@HistoryActivity, 10f))
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            tag = RowHolder(dot, meta, time, body, image)
        }
    }

    private fun bind(holder: RowHolder, item: HistoryStore.HistoryItem) {
        val isSms = item.kind == "sms" || item.kind == "sms_code"

        holder.dot.background = Design.circleBg(
            if (isSms) Design.Color.WARNING else Design.Color.PRIMARY
        )
        holder.meta.text = buildString {
            append(if (isSms) "短信" else "剪贴板")
            append(" · ")
            append(if (item.direction == "in") "收到" else "发出")
        }
        holder.time.text = DateUtils.getRelativeTimeSpanString(
            HistoryStore.millisOf(item.ts), System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )

        val bitmap = if (item.kind == "image" && item.imageName.isNotEmpty()) {
            ClipboardImageStore.loadBitmap(this, item.imageName)
        } else {
            null
        }
        if (bitmap != null) {
            holder.image.setImageBitmap(bitmap)
            holder.image.visibility = View.VISIBLE
        } else {
            holder.image.setImageBitmap(null)
            holder.image.visibility = View.GONE
        }

        holder.body.text = when {
            item.kind == "image" && bitmap != null -> "图片 · 点击查看大图"
            item.kind == "image" -> item.preview.ifEmpty { "图片已失效" }
            else -> item.text
        }
        holder.body.setTextColor(
            if (item.kind == "image") Design.Color.INK_SECONDARY else Design.Color.INK
        )
    }

    companion object {
        private const val FILTER_ALL = "all"
        private const val FILTER_SMS = "sms"
        private const val FILTER_CLIP = "clip"
        private const val MENU_CLEAR = 1
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.refresh(this)
        recreate()
    }
}
