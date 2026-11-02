package com.x0finch.q5home.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.ceil

/**
 * Numbers that roll instead of being swapped (clock minutes, speed): only the digits that changed roll up out of view while the new
 * ones roll in from below; unchanged digits stay put. If the length changes (e.g. 9 → 10 km/h) the whole string rolls.
 * Draws its own text and runs a single [DURATION] animation per change, so it is cheap on the head unit.
 */
class RollingText(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var current = ""
    private var previous = ""
    private var progress = 1f
    private var animator: ValueAnimator? = null

    val text: String get() = current

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    fun setTextSize(sp: Float) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        requestLayout()
    }

    fun setTypeface(typeface: Typeface?) { paint.typeface = typeface; requestLayout() }

    fun setLetterSpacing(em: Float) { paint.letterSpacing = em; requestLayout() }

    /** With [animate] false the text is replaced immediately (page just shown, back from the background). */
    fun setText(value: String, animate: Boolean) {
        if (value == current) return
        animator?.cancel()
        previous = current
        current = value
        contentDescription = value
        if (textWidth(previous) != textWidth(current)) requestLayout()
        if (!animate || previous.isEmpty() || !isAttachedToWindow) {
            progress = 1f
            invalidate()
            return
        }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        progress = 1f
        super.onDetachedFromWindow()
    }

    /** Text is drawn one character at a time, so its width is measured the same way. */
    private fun textWidth(s: String): Float = s.sumOf { paint.measureText(it.toString()).toDouble() }.toFloat()

    private fun lineHeight(): Float = paint.fontMetrics.let { it.descent - it.ascent }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = ceil(textWidth(current)).toInt() + paddingLeft + paddingRight + 1
        // Same height as a TextView with includeFontPadding=false: ascent + descent only.
        val h = lineHeight().toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = paddingTop - paint.fontMetrics.ascent
        val x0 = paddingLeft.toFloat()
        val alpha = paint.alpha
        if (progress >= 1f || previous.isEmpty()) {
            var x = x0
            for (ch in current) { val c = ch.toString(); canvas.drawText(c, x, baseline, paint); x += paint.measureText(c) }
            return
        }
        val shift = lineHeight() * 0.8f
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        val sameLength = previous.length == current.length
        var x = x0
        for (i in current.indices) {
            val c = current[i].toString()
            if (sameLength && previous[i] == current[i]) {
                canvas.drawText(c, x, baseline, paint)
            } else {
                // The new digit rolls in from below.
                paint.alpha = (alpha * progress).toInt()
                canvas.drawText(c, x, baseline + shift * (1 - progress), paint)
                paint.alpha = alpha
            }
            x += paint.measureText(c)
        }
        // The old digits that changed roll up and out.
        paint.alpha = (alpha * (1 - progress)).toInt()
        x = x0
        for (i in previous.indices) {
            val c = previous[i].toString()
            if (!sameLength || previous[i] != current[i]) canvas.drawText(c, x, baseline - shift * progress, paint)
            x += paint.measureText(c)
        }
        paint.alpha = alpha
        canvas.restore()
    }

    companion object {
        const val DURATION = 360L
    }
}
