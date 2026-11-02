package com.x0finch.q5home.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Bento card: its look comes from three things —
 *  1. layered shadows (one tight, fine shadow + several large, soft shadows offset downward);
 *  2. a 1px stroke;
 *  3. a top inner glow (a bright edge along the card's top that fades out downward).
 *
 * Two styles:
 *  - [Style.LIFTED]: wider shadow spread, offset further down, so the card looks raised (home tiles, popup cards);
 *  - [Style.RESTING]: very light shadow, slightly darker stroke, brighter card background (settings groups, buttons, pressed state).
 *
 * Liquid glass ([drawGlass]): with a wallpaper, the card shows a softened, more saturated piece of it ([Glass]) exactly behind it,
 * one edge highlight catches light top-left / bottom-right, the tint darkens automatically over bright backgrounds ([Glass.luma])
 * and a soft glow follows the finger while pressed ([setHotspot]). No lens bands or stacked outlines: they read as inner frames.
 *
 * Shadows are drawn outside the card (the parent needs clipChildren=false). Android 8.1 hardware acceleration doesn't support blurred shape shadows,
 * so shadows are drawn at 1/4 size into a small cached ALPHA_8 bitmap (software canvas) and scaled up, which is cheap on memory and CPU.
 */
class BentoDrawable(
    private val radiusPx: Float,
    private val style: Style,
    private val density: Float,
    /** Card fill color; null uses the theme color. */
    private val fill: Int? = null,
) : Drawable() {

    enum class Style { LIFTED, RESTING }

    /** One shadow layer: y offset, blur radius (dp), opacity. */
    private class Shadow(val dy: Float, val blur: Float, val alpha: Float)

    private val dark = Ui.dark
    private val shadows = when (style) {
        Style.LIFTED -> listOf(Shadow(1f, 2f, 0.06f), Shadow(10f, 22f, 0.08f), Shadow(26f, 48f, 0.10f))
        Style.RESTING -> listOf(Shadow(1f, 2f, 0.05f), Shadow(4f, 10f, 0.05f))
    }.map { if (dark) Shadow(it.dy, it.blur, (it.alpha * 3f).coerceAtMost(0.45f)) else it }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val clip = android.graphics.Path()
    private var solidShader: android.graphics.Shader? = null
    private var solidStroke = 0
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowFingerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hotspotX = -1f
    private var hotspotY = -1f
    private val src = Rect()
    private val dst = RectF()
    private var shadowBitmap: Bitmap? = null
    private var alphaMul = 255
    private var strokeAlpha = 255

    /** Maximum distance (px) the shadow extends beyond the card; the parent must leave this much unclipped space. */
    val shadowExtent: Int get() = ((shadows.maxOf { it.dy + it.blur }) * density).toInt()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        shadowBitmap = if (bounds.isEmpty) null else shadowFor(bounds.width(), bounds.height())
        val w = Ui.cardColors(dark, style, fill)
        solidShader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            w.top, w.bottom, Shader.TileMode.CLAMP)
        solidStroke = w.stroke
        // Glass: a faint sheen across the top, a diagonal rim highlight (bright top-left, softer bottom-right).
        sheenPaint.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.top + bounds.height() * 0.45f,
            0x1AFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        rimPaint.strokeWidth = density * 1.2f
        rimPaint.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(0x8CFFFFFF.toInt(), 0x14FFFFFF, 0x0AFFFFFF, 0x47FFFFFF), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        clip.reset()
        clip.addRoundRect(RectF(bounds), radiusPx, radiusPx, android.graphics.Path.Direction.CW)
        strokePaint.strokeWidth = density.coerceAtLeast(1f) // 1dp ≈ 1.5px, which looks like a nice thin line on the head unit
        // Inner glow: bright at the top edge, fading to transparent over the top 1/3.
        glowPaint.strokeWidth = density * 1.5f
        glowPaint.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.top + bounds.height() / 3f,
            w.glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    private fun shadowFor(w: Int, h: Int): Bitmap {
        val key = "$w×$h×$radiusPx×$style×$dark"
        cache.get(key)?.let { return it }
        val pad = shadowExtent
        val bw = ((w + pad * 2) / SCALE).coerceAtLeast(1)
        val bh = ((h + pad * 2) / SCALE).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (s in shadows) {
            val blur = s.blur * density / SCALE
            p.maskFilter = if (blur >= 0.5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            p.alpha = (s.alpha * 255).toInt()
            val r = radiusPx / SCALE
            val dy = s.dy * density / SCALE
            c.drawRoundRect(RectF(pad / SCALE.toFloat(), pad / SCALE.toFloat() + dy,
                (pad + w) / SCALE.toFloat(), (pad + h) / SCALE.toFloat() + dy), r, r, p)
        }
        cache.put(key, bmp)
        return bmp
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        shadowBitmap?.let { bmp ->
            val pad = shadowExtent.toFloat()
            src.set(0, 0, bmp.width, bmp.height)
            dst.set(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
            shadowPaint.alpha = alphaMul
            canvas.drawBitmap(bmp, src, dst, shadowPaint)
        }
        if (fill == null && Glass.blurred != null && drawGlass(canvas)) return
        fillPaint.shader = solidShader
        strokePaint.color = solidStroke
        strokeAlpha = Color.alpha(solidStroke)
        fillPaint.alpha = alphaMul
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
        // Draw the stroke and inner glow 0.5px inset so the lines stay crisp.
        val inset = strokePaint.strokeWidth / 2
        rect.inset(inset, inset)
        val r = radiusPx - inset
        glowPaint.alpha = alphaMul
        canvas.drawRoundRect(rect, r, r, glowPaint)
        strokePaint.alpha = strokeAlpha * alphaMul / 255
        canvas.drawRoundRect(rect, r, r, strokePaint)
    }

    /**
     * Liquid glass. Returns false (draw a solid card instead) when the host View or the background is not available.
     */
    private fun drawGlass(canvas: Canvas): Boolean {
        val host = hostView() ?: return false
        val b = bounds
        val save = canvas.save()
        canvas.clipPath(clip)
        // One layer of the softened background, exactly behind the card (no magnification): any second, differently magnified
        // layer near the rim shows the background shifted and reads as an inner frame, however smoothly it is faded.
        if (!Glass.draw(canvas, host, b, alphaMul, 1f)) { canvas.restoreToCount(save); return false }
        // Tint: very transparent, darker over bright backgrounds so white text stays readable; pressed cards are a little brighter.
        val luma = Glass.luma(host, b)
        val tintAlpha = (0x30 + luma * 0x70).toInt().coerceIn(0x30, 0xA0)
        tintPaint.color = if (style == Style.RESTING) 0xFF26262B.toInt() else 0xFF0E0E12.toInt()
        tintPaint.alpha = tintAlpha * alphaMul / 255
        canvas.drawRect(b, tintPaint)
        sheenPaint.alpha = alphaMul
        canvas.drawRect(b, sheenPaint)
        // Pressed: a soft glow under the finger.
        if (hotspotX >= 0f && style == Style.RESTING) {
            val radius = maxOf(b.width(), b.height()) * 0.6f
            glowFingerPaint.shader = android.graphics.RadialGradient(hotspotX, hotspotY, radius,
                0x33FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            glowFingerPaint.alpha = alphaMul
            canvas.drawRect(b, glowFingerPaint)
        }
        canvas.restoreToCount(save)
        // A single edge highlight (one line, no stacked outlines).
        rect.set(b)
        val half = rimPaint.strokeWidth / 2
        rect.inset(half, half)
        rimPaint.alpha = alphaMul
        canvas.drawRoundRect(rect, radiusPx - half, radiusPx - half, rimPaint)
        return true
    }

    override fun setHotspot(x: Float, y: Float) {
        hotspotX = x; hotspotY = y
        invalidateSelf()
    }

    /** The View this is drawn on (a child Drawable inside a StateListDrawable has the outer Drawable as its callback, so walk up). */
    private fun hostView(): android.view.View? {
        var cb: Any? = callback
        var depth = 0
        while (cb is Drawable && depth++ < 4) cb = cb.callback
        return cb as? android.view.View
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Shadow bitmaps are drawn at 1/4 size (they're blurry anyway, so upscaling isn't noticeable). */
        private const val SCALE = 4
        private val cache = LruCache<String, Bitmap>(48)
    }
}
