package com.x0finch.q5home

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * Uniform home screen icons (filled Material Symbols Rounded). The home screen, settings and the icon grid use [glyph]: a white
 * glyph placed directly on the glass card, no colored square (one icon set, one color). [badge] (rounded gradient background)
 * is only used for the CarPlay card cover when the wallpaper is solid black.
 * A tile only stores "icon key + target app", so switching apps later only changes the target (app id); the icon stays.
 */
object Icons {

    class Icon(val key: String, val name: String, val glyph: Int, val top: Int, val bottom: Int)

    val ALL = listOf(
        Icon("carplay", "CarPlay", R.drawable.glyph_carplay, 0xFF5BE07A.toInt(), 0xFF1E9E55.toInt()),
        Icon("oem", "原车", R.drawable.glyph_oem, 0xFFB8BCC4.toInt(), 0xFF4A4E57.toInt()),
        Icon("files", "文件", R.drawable.glyph_files, 0xFFFFD60A.toInt(), 0xFFFF9500.toInt()),
        Icon("settings", "系统设置", R.drawable.glyph_settings, 0xFFA1A1A8.toInt(), 0xFF4A4A50.toInt()),
        Icon("nav", "导航", R.drawable.glyph_nav, 0xFF40A9FF.toInt(), 0xFF5E5CE6.toInt()),
        Icon("phone", "电话", R.drawable.glyph_phone, 0xFF5BE07A.toInt(), 0xFF248A3D.toInt()),
        Icon("radio", "收音机", R.drawable.glyph_radio, 0xFFFFB340.toInt(), 0xFFFF453A.toInt()),
        Icon("video", "视频", R.drawable.glyph_video, 0xFFFF6482.toInt(), 0xFFBF5AF2.toInt()),
        Icon("music", "音乐", R.drawable.glyph_music, 0xFFFF6482.toInt(), 0xFFFF2D55.toInt()),
        Icon("bluetooth", "蓝牙", R.drawable.glyph_bluetooth, 0xFF40A9FF.toInt(), 0xFF0040DD.toInt()),
        Icon("browser", "浏览器", R.drawable.glyph_browser, 0xFF70D7FF.toInt(), 0xFF0A84FF.toInt()),
        Icon("photos", "相册", R.drawable.glyph_photos, 0xFFFFD60A.toInt(), 0xFFFF375F.toInt()),
        Icon("apps", "应用", R.drawable.glyph_apps, 0xFF7D7AFF.toInt(), 0xFFBF5AF2.toInt()),
    )

    fun byKey(key: String?): Icon? = ALL.firstOrNull { it.key == key }

    /** Guesses an icon from the package name and label; returns null if no match, and the tile then uses the app's own icon. */
    fun guess(pkg: String?, label: String?): String? {
        val p = pkg.orEmpty().lowercase()
        val l = label.orEmpty().lowercase()
        fun has(vararg words: String) = words.any { p.contains(it) || l.contains(it) }
        return when {
            has("zlink", "carplay", "diplay", "autokit", "carlink") -> "carplay"
            has("原车", "原厂") -> "oem"
            has("settings", "设置") -> "settings"
            has("documentsui", "filemanager", "fileexplorer", "文件", "file") -> "files"
            has("navi", "amap", "autonavi", "baidumap", "waze", "maps", "导航", "地图") -> "nav"
            has("dialer", "电话") -> "phone"
            has("radio", "收音机") -> "radio"
            has("video", "movie", "视频") -> "video"
            has("music", "音乐") -> "music"
            has("bluetooth", "蓝牙") -> "bluetooth"
            has("browser", "chrome", "浏览器") -> "browser"
            has("gallery", "photos", "相册", "图库") -> "photos"
            else -> null
        }
    }

    /** The glyph alone, tinted [tint] (white by default). */
    fun glyph(context: Context, icon: Icon, tint: Int = 0xFFFFFFFF.toInt()): Drawable =
        context.getDrawable(icon.glyph)!!.mutate().apply { setTint(tint) }

    /** Rendered icon: rounded gradient background + centered white glyph. */
    fun badge(context: Context, icon: Icon): Drawable {
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(icon.top, icon.bottom))
        bg.shape = GradientDrawable.RECTANGLE
        val glyph = context.getDrawable(icon.glyph)!!.mutate()
        return object : LayerDrawable(arrayOf(bg, glyph)) {
            override fun onBoundsChange(bounds: android.graphics.Rect) {
                bg.cornerRadius = bounds.width() * 0.26f
                val inset = (bounds.width() * 0.22f).toInt()
                setLayerInset(1, inset, inset, inset, inset)
                super.onBoundsChange(bounds)
            }
        }
    }
}
