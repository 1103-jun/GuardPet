package com.geekathon.guardpet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.absoluteValue

/** Fixed-size canvas. The child never changes the overlay window dimensions. */
class PetCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val imageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        adjustViewBounds = false
    }
    private var shownPath: String? = null
    private var shownModified: Long = -1L

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        addView(imageView)
    }

    fun show(file: File?) {
        imageView.clearAnimation()
        if (file == null || !file.isFile) {
            shownPath = null
            shownModified = -1L
            imageView.setImageDrawable(null)
            return
        }
        // 同路径覆盖写入时也必须重载：用 path + lastModified 判断
        val modified = file.lastModified()
        val path = file.absolutePath
        if (path == shownPath && modified == shownModified && imageView.drawable != null) {
            return
        }
        val drawable = decode(file)
        imageView.setImageDrawable(drawable)
        if (drawable != null) {
            shownPath = path
            shownModified = modified
        } else {
            shownPath = null
            shownModified = -1L
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
    }

    /** Force reload even if path/mtime look unchanged. */
    fun showForced(file: File?) {
        shownPath = null
        shownModified = -1L
        show(file)
    }

    /** The home preview fills its allotted space without changing desktop-pet scaling. */
    fun fitPreviewToCanvas() {
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.scaleX = 1f
        imageView.scaleY = 1f
    }

    /** Scales only the image layer; the PetCanvas/window dimensions stay fixed. */
    fun setVisualScale(scale: Float, alpha: Float = 1f) {
        imageView.scaleX = scale.coerceIn(0.1f, 2.4f)
        imageView.scaleY = scale.coerceIn(0.1f, 2.4f)
        imageView.alpha = alpha.coerceIn(0.15f, 1f)
    }

    fun setFacingRight(facingRight: Boolean) {
        imageView.scaleX = if (facingRight) imageView.scaleX.absoluteValue else -imageView.scaleX.absoluteValue
    }

    private fun decode(file: File): Drawable? = runCatching {
        // 读字节再解码，避开 ImageDecoder 对同一 File 路径的缓存
        val bytes = file.readBytes()
        require(bytes.isNotEmpty()) { "empty image" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("decode failed")
        BitmapDrawable(resources, bitmap)
    }.getOrNull()
}
