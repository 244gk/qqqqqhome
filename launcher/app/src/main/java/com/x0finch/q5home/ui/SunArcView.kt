package com.x0finch.q5home.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI

/**
 * Sunrise/sunset arc at the bottom of the home GPS card: sunrise on the left, sunset on the right, a cosine arc between, with the sun dot placed by how far through the day it is.
 * The elapsed part is a solid accent-color line, the rest is dashed; at night the sun dot sits on the horizon (hollow).
 */
class SunArcView(context: Context) : View(context) {

    private var rise = 0L
    private var set = 0L
    private var now = 0L
    private var approximate = false
    private var riseText = ""
    private var setText = ""

    private val density = resources.displayMetrics.density
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = runCatching { resources.getFont(com.x0finch.q5home.R.font.outfit_regular) }.getOrNull() ?: Typeface.DEFAULT
    }
    private val path = Path()
    private val fmt = SimpleDateFormat("HH:mm", Locale.US)

    /** [approximate]: no location fix yet, so draw using 7:00–19:00. */
    fun set(rise: Long, set: Long, now: Long, approximate: Boolean) {
        if (rise == this.rise && set == this.set && now / 60_000 == this.now / 60_000 && approximate == this.approximate) return
        this.rise = rise; this.set = set; this.now = now; this.approximate = approximate
        val prefix = if (approximate) "约 " else ""
        riseText = "日出 $prefix${fmt.format(Date(rise))}"
        setText = "日落 $prefix${fmt.format(Date(set))}"
        contentDescription = "$riseText，$setText"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (set <= rise) return
        val textH = label.textSize + 4 * density
        val left = paddingLeft + 6 * density
        val right = width - paddingRight - 6 * density
        val horizon = height - paddingBottom - textH - 4 * density
        val top = paddingTop + 8 * density
        val amp = (horizon - top).coerceAtLeast(4 * density)
        // y = horizon - amp * sin(π t), where t is the daytime progress.
        fun y(t: Float) = horizon - amp * kotlin.math.sin(PI * t).toFloat()
        fun x(t: Float) = left + (right - left) * t

        line.color = Ui.DIVIDER
        canvas.drawLine(left - 6 * density, horizon, right + 6 * density, horizon, line)

        dashed.color = Ui.TEXT_FAINT
        path.reset()
        val steps = 48
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            if (i == 0) path.moveTo(x(t), y(t)) else path.lineTo(x(t), y(t))
        }
        canvas.drawPath(path, dashed)

        val progress = (now - rise).toFloat() / (set - rise)
        val day = progress in 0f..1f
        if (day) {
            solid.color = Ui.ACCENT
            path.reset()
            val n = (steps * progress).toInt().coerceAtLeast(1)
            for (i in 0..n) {
                val t = progress * i / n
                if (i == 0) path.moveTo(x(t), y(t)) else path.lineTo(x(t), y(t))
            }
            canvas.drawPath(path, solid)
            val cx = x(progress); val cy = y(progress)
            dot.style = Paint.Style.FILL
            dot.color = Ui.ACCENT and 0x33FFFFFF
            canvas.drawCircle(cx, cy, 9 * density, dot)
            dot.color = Ui.ACCENT
            canvas.drawCircle(cx, cy, 5 * density, dot)
        } else {
            // At night: a hollow dot rests on the horizon — at the left end before sunrise, the right end after sunset.
            val before = progress < 0f
            val cx = if (before) left else right
            dot.style = Paint.Style.STROKE
            dot.strokeWidth = 1.5f * density
            dot.color = Ui.TEXT_DIM
            canvas.drawCircle(cx, horizon, 4.5f * density, dot)
        }

        label.color = Ui.TEXT_DIM
        val base = height - paddingBottom - 2 * density
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(riseText, left - 6 * density, base, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(setText, right + 6 * density, base, label)
    }
}
