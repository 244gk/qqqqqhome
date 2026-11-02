package com.x0finch.q5home.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/**
 * Frosted glass: draws a blurred piece of the wallpaper behind a card.
 *
 * Android 8.1 has no system blur (RenderEffect needs 12), so we do it ourselves: scale the page background (center-cropped wallpaper) down to 1/[SCALE],
 * apply three software box-blur passes (≈ Gaussian) and keep a small bitmap; each card draws the matching region for its window position, scaled up.
 * The bitmap is only a few dozen KB and is recomputed only when the wallpaper changes.
 */
object Glass {
    private const val SCALE = 10
    private const val RADIUS = 3

    /** The blurred small bitmap, covering the [width]×[height] window region starting at [origin] (where the page background is). */
    @Volatile var blurred: Bitmap? = null; private set
    private var key: String? = null
    private var originX = 0
    private var originY = 0
    private var width = 0
    private var height = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val loc = IntArray(2)

    /**
     * Call when the background or page size changes: [background] is the full-page wallpaper View (centerCrop), [bitmap] is the image it shows.
     * Not recomputed if [wallpaperKey] and the size are unchanged.
     */
    fun update(background: View, bitmap: Bitmap?, wallpaperKey: String?) {
        if (bitmap == null || background.width == 0 || background.height == 0) {
            if (bitmap == null) { blurred = null; key = null }
            return
        }
        background.getLocationInWindow(loc)
        val k = "$wallpaperKey×${background.width}×${background.height}"
        originX = loc[0]; originY = loc[1]
        if (k == key && blurred != null) return
        width = background.width; height = background.height
        val w = (width / SCALE).coerceAtLeast(4)
        val h = (height / SCALE).coerceAtLeast(4)
        // Center-crop to the page aspect ratio, then scale down.
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val cropW = (width / scale).toInt().coerceAtMost(bitmap.width)
        val cropH = (height / scale).toInt().coerceAtMost(bitmap.height)
        val left = (bitmap.width - cropW) / 2
        val top = (bitmap.height - cropH) / 2
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(bitmap, Rect(left, top, left + cropW, top + cropH), Rect(0, 0, w, h), paint)
        blur(small)
        blurred = small
        key = k
    }

    /** Draws the blurred background at [view]'s position into [bounds] on [canvas] (called by the card's Drawable; clip to the rounded rect first). */
    fun draw(canvas: Canvas, view: View?, bounds: Rect, alpha: Int): Boolean {
        val bmp = blurred ?: return false
        val v = view ?: return false
        if (width == 0) return false
        v.getLocationInWindow(loc)
        val fx = bmp.width.toFloat() / width
        val fy = bmp.height.toFloat() / height
        val x = loc[0] - originX + bounds.left
        val y = loc[1] - originY + bounds.top
        src.set((x * fx).toInt(), (y * fy).toInt(),
            ((x + bounds.width()) * fx).toInt().coerceAtLeast((x * fx).toInt() + 1),
            ((y + bounds.height()) * fy).toInt().coerceAtLeast((y * fy).toInt() + 1))
        dst.set(bounds)
        paint.alpha = alpha
        canvas.drawBitmap(bmp, src, dst, paint)
        return true
    }

    /**
     * When scrolling, cards move within the window but Android doesn't redraw child Views (it only translates them), so the frosted background would misalign:
     * invalidate the inner Views on scroll.
     */
    fun followScroll(scroll: View) {
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateTree(scroll) }
    }

    fun invalidateTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }

    /** Three box-blur passes (horizontal + vertical), approximating a Gaussian. */
    private fun blur(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxPass(px, tmp, w, h, RADIUS, horizontal = true)
            boxPass(tmp, px, w, h, RADIUS, horizontal = false)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun boxPass(input: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = r * 2 + 1
        for (line in 0 until lines) {
            fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = input[idx(i)]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                out[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[idx(i + r + 1)]
                val rem = input[idx(i - r)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
