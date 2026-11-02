package com.x0finch.q5home.ui

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * In-page popup card (replaces the system AlertDialog / Toast): dims the background; tap outside or press Back to close.
 * With an anchor, the card pops up next to it (e.g. above the long-pressed tile); otherwise it's centered.
 */
class Overlay(private val host: FrameLayout) {

    private var layer: FrameLayout? = null
    private var snack: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val context get() = host.context

    val isShowing get() = layer != null

    fun show(card: View, anchor: View? = null, widthDp: Int? = null) {
        dismiss(animate = false)
        val l = FrameLayout(context).apply {
            setBackgroundColor(Ui.SCRIM)
            isClickable = true
            setOnClickListener { dismiss() }
        }
        card.isClickable = true // Tapping the card itself doesn't close it
        val width = widthDp?.let { Ui.dp(context, it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (anchor == null) lp.gravity = Gravity.CENTER
        l.addView(card, lp)
        host.addView(l, FrameLayout.LayoutParams(-1, -1))
        layer = l

        if (anchor != null) {
            card.alpha = 0f
            card.post { placeNear(card, anchor); reveal(card) }
        } else {
            reveal(card)
        }
        l.alpha = 0f
        l.animate().alpha(1f).setDuration(140).start()
        if (!host.isInTouchMode) card.post { card.focusSearch(View.FOCUS_DOWN)?.requestFocus() ?: card.requestFocus() }
    }

    private fun reveal(card: View) {
        card.scaleX = 0.96f; card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    /**
     * Keep the card from covering the anchor: prefer above, then below; if neither fits (the head unit screen is short), place it beside the anchor,
     * on the left if the anchor is in the right half of the screen, otherwise on the right.
     */
    private fun placeNear(card: View, anchor: View) {
        val a = Rect()
        anchor.getGlobalVisibleRect(a)
        val h = Rect()
        host.getGlobalVisibleRect(h)
        a.offset(-h.left, -h.top)
        val margin = Ui.dp(context, 16)
        val gap = Ui.dp(context, 12)
        val maxX = (host.width - card.width - margin).coerceAtLeast(margin)
        val maxY = (host.height - card.height - margin).coerceAtLeast(margin)
        val (x, y) = when {
            a.top - card.height - gap >= margin -> a.left.coerceIn(margin, maxX) to a.top - card.height - gap
            a.bottom + gap + card.height <= host.height - margin -> a.left.coerceIn(margin, maxX) to a.bottom + gap
            else -> {
                val side = if (a.centerX() > host.width / 2) a.left - card.width - gap else a.right + gap
                side.coerceIn(margin, maxX) to (a.centerY() - card.height / 2).coerceIn(margin, maxY)
            }
        }
        card.x = x.toFloat()
        card.y = y.toFloat()
    }

    fun dismiss(animate: Boolean = true) {
        val l = layer ?: return
        layer = null
        if (animate) l.animate().alpha(0f).setDuration(120).withEndAction { host.removeView(l) }.start()
        else host.removeView(l)
    }

    /** Pill-shaped message at the bottom that disappears after 2.5 seconds (replaces Toast). */
    fun snackbar(message: String) {
        snack?.let { host.removeView(it) }
        val v = Ui.text(context, message, 15f, Ui.TEXT, Ui.Font.MEDIUM, singleLine = false).apply {
            background = Ui.card(context, 22)
            setPadding(Ui.dp(context, 22), Ui.dp(context, 13), Ui.dp(context, 22), Ui.dp(context, 13))
            gravity = Gravity.CENTER
        }
        host.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(context, 28)
        })
        snack = v
        v.alpha = 0f
        v.translationY = Ui.dp(context, 12).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            v.animate().alpha(0f).setDuration(200).withEndAction {
                host.removeView(v)
                if (snack === v) snack = null
            }.start()
        }, 2500)
    }
}
