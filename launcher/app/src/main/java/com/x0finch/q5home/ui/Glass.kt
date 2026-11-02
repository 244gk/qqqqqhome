package com.x0finch.q5home.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * Liquid glass (modelled on iOS 26 Liquid Glass): each card draws a lightly softened, more saturated piece of the wallpaper behind it.
 *
 * Android 8.1 has no system blur (RenderEffect needs 12), so we do it ourselves: scale the page background (center-cropped wallpaper)
 * down to 1/[SCALE], soften it with two software box-blur passes (the picture behind stays recognisable), boost saturation and keep a
 * small bitmap. Each card draws the region matching its window position, scaled up ([draw]'s zoom is used for the lens effect at the
 * edges). The bitmap is only a few dozen KB and is recomputed only when the wallpaper changes.
 */
object Glass {
    private const val SCALE = 6
    private const val RADIUS = 2
    /** Saturation boost (colors behind liquid glass look more vivid). */
    private const val SATURATION = 1.4f

    /** The softened small bitmap, covering the [width]×[height] window region starting at [originX]/[originY] (the page background). */
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
     * Call when the background or page size changes: [background] is the full-page wallpaper View (centerCrop), [bitmap] the image it shows.
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
        saturate(small)
        blurred = small
        lumaKey = null
        key = k
    }

    /**
     * Computes the glass background before the page is first drawn (after layout, before draw), so a page never flashes solid cards
     * before turning into glass. If the view is already laid out, computes immediately. Then redraws the cards inside [root].
     */
    fun updateBeforeDraw(background: View, bitmap: Bitmap?, wallpaperKey: String?, root: View) {
        if (background.width > 0) {
            update(background, bitmap, wallpaperKey)
            invalidateTree(root)
            return
        }
        background.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (background.viewTreeObserver.isAlive) background.viewTreeObserver.removeOnPreDrawListener(this)
                update(background, bitmap, wallpaperKey)
                invalidateTree(root)
                return true
            }
        })
    }

    /** Region of the small bitmap behind [bounds] of [view]; [zoom] > 1 takes a smaller centered region (magnified = lens). */
    private fun region(bmp: Bitmap, view: View, bounds: Rect, zoom: Float, out: Rect): Boolean {
        if (width == 0) return false
        view.getLocationInWindow(loc)
        val fx = bmp.width.toFloat() / width
        val fy = bmp.height.toFloat() / height
        val cx = (loc[0] - originX + bounds.exactCenterX()) * fx
        val cy = (loc[1] - originY + bounds.exactCenterY()) * fy
        val hw = bounds.width() * fx / 2 / zoom
        val hh = bounds.height() * fy / 2 / zoom
        out.set((cx - hw).toInt(), (cy - hh).toInt(),
            (cx + hw).toInt().coerceAtLeast((cx - hw).toInt() + 1), (cy + hh).toInt().coerceAtLeast((cy - hh).toInt() + 1))
        return true
    }

    /**
     * Draws the background behind [view] into [bounds] on [canvas] (called by the card Drawable after clipping to its shape).
     * [zoom] is the magnification: about 1.03 in the middle of the card and more at the rim, like looking through thick glass.
     */
    fun draw(canvas: Canvas, view: View?, bounds: Rect, alpha: Int, zoom: Float = 1f): Boolean {
        val bmp = blurred ?: return false
        val v = view ?: return false
        if (!region(bmp, v, bounds, zoom, src)) return false
        dst.set(bounds)
        paint.alpha = alpha
        canvas.drawBitmap(bmp, src, dst, paint)
        return true
    }

    private var lumaKey: String? = null
    private var lumaValue = 0.3f
    private val lumaRect = Rect()

    /**
     * Average brightness (0–1) of the background behind a card: a bright background darkens the glass so white text stays readable
     * (the "adaptive" part of liquid glass). Reuses the last result when the position has not changed.
     */
    fun luma(view: View?, bounds: Rect): Float {
        val bmp = blurred ?: return 0.3f
        val v = view ?: return 0.3f
        if (!region(bmp, v, bounds, 1f, lumaRect)) return 0.3f
        val k = "${lumaRect.left},${lumaRect.top},${lumaRect.right},${lumaRect.bottom}@${System.identityHashCode(bmp)}"
        if (k == lumaKey) return lumaValue
        val l = lumaRect.left.coerceIn(0, bmp.width - 1)
        val t = lumaRect.top.coerceIn(0, bmp.height - 1)
        val r = lumaRect.right.coerceIn(l + 1, bmp.width)
        val b = lumaRect.bottom.coerceIn(t + 1, bmp.height)
        var sum = 0f; var n = 0
        val step = maxOf(1, (r - l) / 12)
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = bmp.getPixel(x, y)
                sum += (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
                n++
                x += step
            }
            y += step
        }
        lumaValue = if (n == 0) 0.3f else sum / n
        lumaKey = k
        return lumaValue
    }

    /** Boosts saturation (pushes each color away from its luminance). */
    private fun saturate(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            fun f(v: Int) = (l + (v - l) * SATURATION).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (f(r) shl 16) or (f(g) shl 8) or f(b)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * When scrolling, cards move within the window but Android doesn't redraw child Views (it only translates them), so the glass
     * background would misalign: invalidate the inner Views on scroll.
     */
    fun followScroll(scroll: View) {
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateTree(scroll) }
    }

    fun invalidateTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }

    /** Two box-blur passes (horizontal + vertical): a light softening. */
    private fun blur(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(2) {
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
