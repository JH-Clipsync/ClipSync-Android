package com.clipsync.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.clipsync.clipboard.ClipboardImageStore
import java.io.File

/**
 * 全屏图片查看器。
 *
 * 历史记录和当前剪贴板里的图片，点击后进入本页面看大图，
 * 而不是直接复制。支持：
 *  - 点击图片/空白处关闭
 *  - 底部「复制到剪贴板」
 *  - 底部「分享」给其他应用
 *
 * 传参二选一：
 *  - EXTRA_IMAGE_NAME：ClipboardImageStore 里的相对文件名（历史记录用）
 *  - EXTRA_URI：任意 content:// / file:// 图片地址（当前剪贴板用）
 */
class ImageViewerActivity : Activity() {

    private var imageUri: Uri? = null
    private var imageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xEE000000.toInt())
        }

        imageName = intent.getStringExtra(EXTRA_IMAGE_NAME)
        val uriExtra: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_URI)
        }
        imageUri = if (imageName != null) {
            val f = ClipboardImageStore.fileFor(this, imageName!!)
            if (f.exists()) Uri.fromFile(f) else uriExtra
        } else {
            uriExtra
        }

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { finish() }
        }
        root.addView(imageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val loaded = loadImage(imageView)

        // 顶部关闭按钮
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(16f)
            marginStart = dp(12f)
        })

        // 底部操作栏：复制 / 分享
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bar.addView(actionButton("复制到剪贴板") { copyImage() })
        bar.addView(actionButton("分享") { shareImage() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12f) })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(28f)
        })

        setContentView(root)

        if (!loaded) {
            Toast.makeText(this, "图片无法加载", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImage(imageView: ImageView): Boolean {
        val uri = imageUri ?: return false
        return try {
            if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                if (!file.exists()) return false
                // 大图先按采样解码，避免 OOM
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                var sample = 1
                val maxEdge = 2048
                while (opts.outWidth / sample > maxEdge || opts.outHeight / sample > maxEdge) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return false
                imageView.setImageBitmap(bmp)
                true
            } else {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input) ?: return false
                    imageView.setImageBitmap(bmp)
                } ?: return false
                true
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipSync", "ImageViewer 加载失败: $e")
            false
        }
    }

    private fun copyImage() {
        val name = imageName
        val ok = if (!name.isNullOrEmpty()) {
            ClipboardImageStore.writeToClipboard(this, name)
        } else {
            val uri = imageUri ?: return
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newUri(contentResolver, "ClipSync", uri))
                true
            } catch (e: Exception) {
                android.util.Log.w("ClipSync", "复制图片失败: $e")
                false
            }
        }
        Toast.makeText(
            this,
            if (ok) "图片已复制到剪贴板" else "复制失败",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareImage() {
        val uri = imageUri ?: return
        // FileProvider 的 uri 才能跨应用授权；file:// 在高版本会被拦
        val shareUri = if (uri.scheme == "file" && !imageName.isNullOrEmpty()) {
            ClipboardImageStore.uriFor(this, imageName!!) ?: uri
        } else {
            uri
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享图片"))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可分享的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(22f), dp(11f), dp(22f), dp(11f))
            setBackgroundResource(android.R.color.transparent)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = dp(22f).toFloat()
                setStroke(dp(1f), 0x66FFFFFF)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_IMAGE_NAME = "image_name"
        const val EXTRA_URI = "uri"

        fun open(context: Context, imageName: String) {
            context.startActivity(Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_NAME, imageName)
            })
        }

        fun open(context: Context, uri: Uri) {
            context.startActivity(Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
            })
        }
    }
}
