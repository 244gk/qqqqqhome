package com.x0finch.q5home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.x0finch.q5home.ui.Ui

/**
 * Thin engine-speed bar under the speed on the home screen's driving card: a track with a fill up to [MAX_RPM], accent colored,
 * red above [RED_RPM]. Only shown while the car reports rpm.
 */
class RpmBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var rpm = 0

    fun set(value: Int) {
        if (value == rpm) return
        rpm = value
        contentDescription = "转速 $value"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = Ui.TRACK
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
        paint.color = if (rpm >= RED_RPM) Ui.DANGER else Ui.ACCENT
        rect.right = width * (rpm.coerceIn(0, MAX_RPM) / MAX_RPM.toFloat())
        if (rect.right > 0f) canvas.drawRoundRect(rect, r, r, paint)
    }

    companion object {
        const val MAX_RPM = 7000
        const val RED_RPM = 5500
    }
}
