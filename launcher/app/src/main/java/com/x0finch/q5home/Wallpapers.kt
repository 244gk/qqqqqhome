package com.x0finch.q5home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Calendar

/** Wallpapers: built-in ones (Unsplash, sources in the repo's CREDITS.md) + ones auto-downloaded on Wi-Fi ([OnlineWallpapers]). */
object Wallpapers {

    class Wallpaper(
        val key: String,
        val name: String,
        val res: Int = 0,
        val file: File? = null,
        /** Photographer of an online wallpaper, shown in the bottom-right corner. */
        val author: String? = null,
        /** Night scene (used at night in sunrise/sunset mode); null = decide by image brightness. */
        val night: Boolean? = null,
    )

    /** Auto-rotate (the key keeps the old name "daily"; see [INTERVALS] for rotation frequencies). */
    const val DAILY = "daily"
    const val NONE = "none"

    /** Available rotation frequencies (minutes) and their names; defaults to daily. */
    val INTERVALS = listOf(30 to "30 分钟", 60 to "每小时", 180 to "3 小时", 1440 to "每天")
    const val DEFAULT_INTERVAL = 1440

    fun intervalName(minutes: Int) = INTERVALS.firstOrNull { it.first == minutes }?.second ?: "每 $minutes 分钟"

    val BUILTIN = listOf(
        Wallpaper("stars", "星空雪山", R.drawable.wall_stars, night = true),
        Wallpaper("city", "城市夜景", R.drawable.wall_city, night = true),
        Wallpaper("road", "峡谷公路", R.drawable.wall_road, night = false),
        Wallpaper("clouds", "云海", R.drawable.wall_clouds, night = false),
        Wallpaper("lake", "湖畔黄昏", R.drawable.wall_lake, night = true),
        Wallpaper("forest", "雾林", R.drawable.wall_forest, night = false),
    )

    /** All selectable wallpapers: downloaded online ones (newest first) + built-in ones. */
    fun available(context: Context): List<Wallpaper> {
        val store = OnlineWallpapers(context)
        val online = store.list().mapIndexed { i, p ->
            Wallpaper(p.key, "在线 · ${p.author.ifBlank { "#${i + 1}" }}", file = store.fileOf(p), author = p.author)
        }
        return online + BUILTIN
    }

    fun label(context: Context, setting: String, intervalMinutes: Int = DEFAULT_INTERVAL): String = when (setting) {
        DAILY -> "自动轮换 · ${intervalName(intervalMinutes)}"
        NONE -> "纯黑"
        else -> available(context).firstOrNull { it.key == setting }?.name ?: "自动轮换 · ${intervalName(intervalMinutes)}"
    }

    /**
     * The wallpaper for the current setting; null for plain black. Auto-rotate picks from all wallpapers by time slot:
     * daily = day of the year (same as older versions); shorter frequencies = minutes elapsed this year ÷ frequency.
     */
    fun current(context: Context, setting: String, calendar: Calendar = TrueTime.calendar(),
                intervalMinutes: Int = DEFAULT_INTERVAL, daylight: Boolean? = null): Wallpaper? {
        if (setting == NONE) return null
        val all = available(context)
        all.firstOrNull { it.key == setting }?.let { return it }
        // Auto-rotate, or the chosen online wallpaper has been cleaned up. Sunrise/sunset mode: rotate only day images by day, only night scenes at night.
        val pool = daylight?.let { day -> all.filter { isNight(context, it) != day } }?.ifEmpty { null } ?: all
        return pool[slot(calendar, intervalMinutes) % pool.size]
    }

    /** Night or day: hard-coded for built-in ones; for online ones, judged once from the thumbnail's average brightness and remembered. */
    fun isNight(context: Context, w: Wallpaper): Boolean {
        w.night?.let { return it }
        val prefs = context.getSharedPreferences("wallpaper_night", Context.MODE_PRIVATE)
        if (prefs.contains(w.key)) return prefs.getBoolean(w.key, false)
        val night = brightness(context, w)?.let { it < NIGHT_BRIGHTNESS } ?: false
        prefs.edit().putBoolean(w.key, night).apply()
        return night
    }

    /** Average brightness 0–1 (computed on a copy scaled down to a few dozen pixels wide, so it's fast). */
    fun brightness(context: Context, w: Wallpaper): Float? {
        val bmp = decode(context, w, 48) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        if (px.isEmpty()) return null
        val sum = px.sumOf { c ->
            (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
        }
        return (sum / px.size).toFloat()
    }

    /** Average brightness below this counts as a night scene. */
    const val NIGHT_BRIGHTNESS = 0.3f

    fun slot(calendar: Calendar, intervalMinutes: Int): Int {
        val day = calendar.get(Calendar.DAY_OF_YEAR)
        if (intervalMinutes >= 1440) return day
        val minutes = (day - 1) * 1440 + calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return minutes / intervalMinutes.coerceAtLeast(1)
    }

    /** Decodes scaled down to the screen width, 16-bit color; a 1920×720 image takes about 2.7 MB of memory. */
    fun decode(context: Context, wallpaper: Wallpaper, targetWidth: Int): Bitmap? {
        val res = context.resources
        fun decodeWith(opts: BitmapFactory.Options): Bitmap? = when {
            wallpaper.file != null -> BitmapFactory.decodeFile(wallpaper.file.path, opts)
            else -> BitmapFactory.decodeResource(res, wallpaper.res, opts)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeWith(bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (targetWidth > 0 && bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return decodeWith(BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}
