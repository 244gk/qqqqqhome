package com.x0finch.q5home.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.x0finch.q5home.Config
import com.x0finch.q5home.R
import com.x0finch.q5home.Sun
import com.x0finch.q5home.Wallpapers

/**
 * The launcher's unified UI style (no system dialogs): minimal Bento cards (see [BentoDrawable]), rounded corners, the Outfit font, a blue accent color.
 * Light by day, dark at night (switches automatically at sunrise/sunset, or can be fixed; see [Config.appearance]) — an all-white screen is too glaring in the car at night.
 * For in-car use: rows at least 56dp tall, larger text; an accent-color outline when focused via rotary knob / D-pad.
 */
object Ui {
    /** Whether the dark theme is active. Each screen sets it in onCreate via [applyTheme]. */
    @Volatile var dark = true
        private set

    /** Works out from settings whether to use dark or light (auto = dark after sunset). */
    fun resolveDark(context: Context, appearance: String = Config(context).also { it.reload() }.appearance): Boolean =
        when (appearance) {
            Config.APPEARANCE_LIGHT -> false
            Config.APPEARANCE_DARK -> true
            else -> !Sun.isDaylight(context)
        }

    /** Sets the theme for the current screen; returns whether it changed (the home screen recreates itself based on this). */
    fun applyTheme(context: Context, appearance: String? = null): Boolean {
        val d = if (appearance != null) resolveDark(context, appearance) else resolveDark(context)
        val changed = d != dark
        dark = d
        return changed
    }

    /** For tests / screenshots. */
    fun forceDark(value: Boolean) { dark = value }

    // ---- Colors (theme-dependent) ----
    val ACCENT get() = if (dark) 0xFF6E7DFF.toInt() else 0xFF2F48F5.toInt()
    val ON_ACCENT get() = 0xFFFFFFFF.toInt()
    val DANGER get() = if (dark) 0xFFFF6961.toInt() else 0xFFE5484D.toInt()
    val TEXT get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF141414.toInt()
    val TEXT_DIM get() = if (dark) 0x99FFFFFF.toInt() else 0xFF6E6E6E.toInt()
    val TEXT_FAINT get() = if (dark) 0x66FFFFFF else 0xFF9C9C9C.toInt()
    /** Page background color (settings, app list). */
    val PAGE get() = if (dark) 0xFF0F0F11.toInt() else 0xFFECEBE8.toInt()
    val DIVIDER get() = if (dark) 0x14FFFFFF else 0x12000000
    /** Dimming scrim behind popup cards. */
    val SCRIM get() = if (dark) 0x99000000.toInt() else 0x59000000
    /** Track color for progress bars and switches in the off state. */
    val TRACK get() = if (dark) 0x26FFFFFF else 0x1A000000
    /** Pressed-state highlight. */
    val PRESSED get() = if (dark) 0x1FFFFFFF else 0x0F000000

    /** Card top/bottom gradient, stroke and inner-glow colors. Resting style: brighter background, darker stroke. */
    class CardColors(val top: Int, val bottom: Int, val stroke: Int, val glow: Int)

    fun cardColors(dark: Boolean, style: BentoDrawable.Style, fill: Int?): CardColors {
        val resting = style == BentoDrawable.Style.RESTING
        return if (dark) {
            val base = fill ?: if (resting) 0xFF232327.toInt() else 0xFF1C1C1F.toInt()
            CardColors(lighten(base, 0.05f), base, if (resting) 0x24FFFFFF else 0x17FFFFFF, 0x24FFFFFF)
        } else {
            val base = fill ?: if (resting) 0xFFFBFAF8.toInt() else 0xFFF4F3F0.toInt()
            CardColors(lighten(base, 0.6f), base, if (resting) 0x24000000 else 0x14000000, 0xE6FFFFFF.toInt())
        }
    }

    /**
     * Frosted-glass card colors: translucent fill (blurred wallpaper behind), thin white edge (glass edge highlight), brighter top inner glow.
     * RESTING is a bit more opaque (pressed state / groups look more "solid").
     */
    fun glassColors(dark: Boolean, style: BentoDrawable.Style): CardColors {
        val resting = style == BentoDrawable.Style.RESTING
        return if (dark) {
            if (resting) CardColors(0x992C2C31.toInt(), 0x8C202024.toInt(), 0x2EFFFFFF, 0x3DFFFFFF)
            else CardColors(0x80292A2E.toInt(), 0x6B1A1A1D, 0x24FFFFFF, 0x38FFFFFF)
        } else {
            if (resting) CardColors(0xE0FFFFFF.toInt(), 0xCCFBFAF8.toInt(), 0xB3FFFFFF.toInt(), 0xFFFFFFFF.toInt())
            else CardColors(0xCCFFFFFF.toInt(), 0xA8F6F5F2.toInt(), 0x99FFFFFF.toInt(), 0xFFFFFFFF.toInt())
        }
    }

    private fun lighten(c: Int, t: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt(),
    )

    fun dp(context: Context, v: Number): Int = (v.toFloat() * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Number, strokeColor: Int = 0, strokeDp: Number = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(context, strokeDp).coerceAtLeast(1), strokeColor)
        }

    /** Bento card background. Lifted = home tiles, popup cards; resting = settings groups, buttons. */
    fun bento(context: Context, radiusDp: Number = 24, style: BentoDrawable.Style = BentoDrawable.Style.LIFTED,
              fill: Int? = null): BentoDrawable =
        BentoDrawable(dp(context, radiusDp).toFloat(), style, context.resources.displayMetrics.density, fill)

    /** Popup card (menus, confirmation dialogs). */
    fun card(context: Context, radiusDp: Number = 24): Drawable = bento(context, radiusDp)

    /** Settings group. */
    fun group(context: Context, radiusDp: Number = 22): Drawable = bento(context, radiusDp, BentoDrawable.Style.RESTING)

    /** Accent-color outline when focused via rotary knob / D-pad (layered over the card as foreground). */
    fun focusRing(context: Context, radiusDp: Number): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),
            rounded(context, Color.TRANSPARENT, radiusDp, ACCENT, 2.5f))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** Clickable row: darkens slightly when pressed; when selected (left-side category) it's a small resting card; accent outline when focused via knob. */
    fun rowBackground(context: Context, radiusDp: Number = 14, selected: Boolean = false): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                rounded(context, if (selected) cardColors(dark, BentoDrawable.Style.RESTING, null).bottom else PRESSED,
                    radiusDp, ACCENT, 2))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, PRESSED, radiusDp))
            addState(intArrayOf(), if (selected) bento(context, radiusDp, BentoDrawable.Style.RESTING)
                else ColorDrawable(Color.TRANSPARENT))
        }

    enum class Font(val res: Int) { LIGHT(R.font.outfit_light), REGULAR(R.font.outfit_regular), MEDIUM(R.font.outfit_medium) }

    fun text(context: Context, value: CharSequence, sizeSp: Float, color: Int = TEXT, font: Font = Font.REGULAR,
             singleLine: Boolean = true): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = context.resources.getFont(font.res)
        includeFontPadding = false
        if (singleLine) {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        } else {
            setLineSpacing(0f, 1.25f)
        }
    }

    fun glyph(context: Context, res: Int, sizeDp: Number, tint: Int = TEXT): ImageView = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    enum class PillStyle { PRIMARY, SECONDARY, DANGER }

    /**
     * Pill button background: a regular one is a small lifted card that turns resting when pressed (as if pushed in); the primary button is solid accent color.
     * Also used for the pill in the top-right of the home screen (over the wallpaper).
     */
    fun pillBackground(context: Context, style: PillStyle = PillStyle.SECONDARY): Drawable {
        val r = 24
        return StateListDrawable().apply {
            when (style) {
                PillStyle.PRIMARY -> {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(context, ACCENT, r, TEXT, 2))
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(context, ACCENT, r).apply { alpha = 190 })
                    addState(intArrayOf(), bento(context, r, BentoDrawable.Style.LIFTED, fill = ACCENT))
                }
                PillStyle.DANGER -> {
                    val tint = DANGER and 0x00FFFFFF or (if (dark) 0x33000000 else 0x1F000000)
                    addState(intArrayOf(android.R.attr.state_focused), rounded(context, tint, r, ACCENT, 2))
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(context, tint, r).apply { alpha = 170 })
                    addState(intArrayOf(), rounded(context, tint, r))
                }
                PillStyle.SECONDARY -> {
                    addState(intArrayOf(android.R.attr.state_focused),
                        rounded(context, cardColors(dark, BentoDrawable.Style.RESTING, null).bottom, r, ACCENT, 2))
                    addState(intArrayOf(android.R.attr.state_pressed), bento(context, r, BentoDrawable.Style.RESTING))
                    addState(intArrayOf(), bento(context, r, BentoDrawable.Style.LIFTED))
                }
            }
        }
    }

    fun pillText(style: PillStyle) = when (style) {
        PillStyle.PRIMARY -> ON_ACCENT
        PillStyle.SECONDARY -> TEXT
        PillStyle.DANGER -> DANGER
    }

    /** Pill button. */
    fun pill(context: Context, label: String, style: PillStyle = PillStyle.SECONDARY, onClick: () -> Unit): TextView =
        text(context, label, 16f, pillText(style), Font.MEDIUM).apply {
            gravity = Gravity.CENTER
            minWidth = dp(context, 88)
            setPadding(dp(context, 22), 0, dp(context, 22), 0)
            background = pillBackground(context, style)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48))
            // The button's shadow is drawn outside its bounds: once placed in a row / card, disable clipping two levels up, or the shadow gets cut into a box.
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    var p = v.parent
                    repeat(2) { (p as? ViewGroup)?.let { g -> g.clipChildren = false; g.clipToPadding = false }; p = p?.parent }
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }

    /** Lets shadows draw outside the view's bounds: every container level holding the card must disable clipping. */
    fun allowShadows(vararg views: ViewGroup) = views.forEach { it.clipChildren = false; it.clipToPadding = false }

    /**
     * Full-page background for settings, the app list, etc.: the current wallpaper + a heavy scrim (off-white in light, black in dark), consistent with the home screen without competing with content.
     * Returns the container for the content.
     */
    fun pageRoot(activity: Activity): FrameLayout {
        val root = FrameLayout(activity).apply { setBackgroundColor(PAGE) }
        root.addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(activity).apply {
            // The wallpaper shows through (cards are frosted glass), but titles sit directly on the background, so still dim it.
            setBackgroundColor(if (dark) 0xBF0F0F11.toInt() else 0xC7ECEBE8.toInt())
        }, FrameLayout.LayoutParams(-1, -1))
        refreshPageWallpaper(activity, root)
        return root
    }

    /** Refreshes the full-page background after a wallpaper change (the background image is the first child View of [pageRoot]). */
    fun refreshPageWallpaper(activity: Activity, root: FrameLayout) {
        val image = root.getChildAt(0) as? ImageView ?: return
        val config = Config(activity).also { it.reload() }
        val wallpaper = Wallpapers.current(activity, config.wallpaper, intervalMinutes = config.wallpaperInterval,
            daylight = if (config.wallpaperSun) Sun.isDaylight(activity) else null)
        val bmp = wallpaper?.let { Wallpapers.decode(activity, it, activity.resources.displayMetrics.widthPixels / 2) }
        image.setImageBitmap(bmp)
        image.post {
            Glass.update(image, bmp, wallpaper?.key + "/half")
            Glass.invalidateTree(root)
        }
    }
}
