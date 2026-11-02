package com.x0finch.q5home.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.x0finch.q5home.Icons
import com.x0finch.q5home.R

class MenuItem(val label: String, val icon: Int, val danger: Boolean = false, val action: () -> Unit)

/** Common popup cards: action menu, confirmation dialog, icon picker. */
object Menus {

    /** Action menu: title (optionally with an icon) + one action per row. */
    fun menu(overlay: Overlay, context: Context, title: String, items: List<MenuItem>,
             anchor: View? = null, titleIcon: Drawable? = null) {
        val card = column(context, padDp = 8)
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 10))
        }
        titleIcon?.let {
            header.addView(ImageView(context).apply { setImageDrawable(it) },
                LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36)).apply { marginEnd = Ui.dp(context, 12) })
        }
        header.addView(Ui.text(context, title, 18f, Ui.TEXT, Ui.Font.MEDIUM))
        card.addView(header)
        card.addView(divider(context))
        for (item in items) {
            val color = if (item.danger) Ui.DANGER else Ui.TEXT
            card.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(context, 56)
                setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
                background = Ui.rowBackground(context)
                isFocusable = true
                isClickable = true
                addView(Ui.glyph(context, item.icon, 22, color).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(context, 14)
                })
                addView(Ui.text(context, item.label, 17f, color))
                setOnClickListener { overlay.dismiss(); item.action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 2) })
        }
        overlay.show(card, anchor, widthDp = 300)
    }

    /** Confirmation dialog: title, message, and cancel / confirm buttons. */
    fun confirm(overlay: Overlay, context: Context, title: String, message: String,
                confirmLabel: String, danger: Boolean = false, onConfirm: () -> Unit) {
        val card = column(context, padDp = 24)
        card.addView(Ui.text(context, title, 22f, Ui.TEXT, Ui.Font.LIGHT))
        card.addView(Ui.text(context, message, 15f, Ui.TEXT_DIM, singleLine = false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 12) })
        val buttons = LinearLayout(context).apply { gravity = Gravity.END }
        buttons.addView(Ui.pill(context, "取消") { overlay.dismiss() })
        buttons.addView(Ui.pill(context, confirmLabel, if (danger) Ui.PillStyle.DANGER else Ui.PillStyle.PRIMARY) {
            overlay.dismiss(); onConfirm()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = Ui.dp(context, 12) })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 24) })
        overlay.show(card, widthDp = 440)
    }

    /** Icon picker: a grid showing each icon, with the current one checked; the last cell is "use the app's own icon". */
    fun iconPicker(overlay: Overlay, context: Context, current: String?, appIcon: Drawable?, onPick: (String?) -> Unit) {
        val card = column(context, padDp = 20)
        card.addView(Ui.text(context, "选择图标", 22f, Ui.TEXT, Ui.Font.LIGHT))
        card.addView(Ui.text(context, "只换图标，打开的应用不变", 13f, Ui.TEXT_DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(context, 6); bottomMargin = Ui.dp(context, 12) })

        val cells = Icons.ALL.map { Triple(it.key as String?, it.name, Icons.badge(context, it) as Drawable?) } +
            Triple(null, "应用自带", appIcon)
        val perRow = 7
        cells.chunked(perRow).forEach { rowCells ->
            val row = LinearLayout(context)
            rowCells.forEach { (key, name, drawable) ->
                row.addView(iconCell(context, name, drawable, selected = key == current) {
                    overlay.dismiss(); onPick(key)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            repeat(perRow - rowCells.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            card.addView(row)
        }
        overlay.show(card, widthDp = 640)
    }

    private fun iconCell(context: Context, name: String, drawable: Drawable?, selected: Boolean, onClick: () -> Unit): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10))
            background = Ui.rowBackground(context, 16, selected)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val frame = FrameLayout(context)
        frame.addView(ImageView(context).apply {
            if (drawable != null) setImageDrawable(drawable) else background = Ui.rounded(context, Ui.TRACK, 14)
        }, FrameLayout.LayoutParams(Ui.dp(context, 52), Ui.dp(context, 52)))
        if (selected) {
            frame.addView(Ui.glyph(context, R.drawable.ui_check, 16, Ui.ON_ACCENT).apply {
                background = Ui.rounded(context, Ui.ACCENT, 10)
                setPadding(Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2))
            }, FrameLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20), Gravity.END or Gravity.TOP).apply {
                topMargin = -Ui.dp(context, 4); marginEnd = -Ui.dp(context, 4)
            })
        }
        cell.addView(frame, LinearLayout.LayoutParams(Ui.dp(context, 56), Ui.dp(context, 56)))
        cell.addView(Ui.text(context, name, 13f, if (selected) Ui.ACCENT else Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 6) })
        return cell
    }

    fun column(context: Context, padDp: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.card(context)
        val p = Ui.dp(context, padDp)
        setPadding(p, p, p, p)
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(Ui.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(-1, 1).apply { bottomMargin = Ui.dp(context, 4) }
    }
}
