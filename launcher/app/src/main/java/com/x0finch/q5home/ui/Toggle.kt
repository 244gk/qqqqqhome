package com.x0finch.q5home.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Toggle switch: green when on, gray when off; display only — clicks are handled by the containing row. */
class Toggle(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f
    private var animator: ValueAnimator? = null

    var checked = false
        private set

    fun setChecked(on: Boolean, animate: Boolean = true) {
        checked = on
        animator?.cancel()
        val target = if (on) 1f else 0f
        if (!animate) {
            fraction = target; invalidate(); return
        }
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 160
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.dp(context, 52), Ui.dp(context, 32))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        paint.color = blend(Ui.TRACK, Ui.ACCENT, fraction)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val r = h / 2 - Ui.dp(context, 3)
        val cx = h / 2 + (width - h) * fraction
        paint.color = Color.WHITE
        canvas.drawCircle(cx, h / 2, r, paint)
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
