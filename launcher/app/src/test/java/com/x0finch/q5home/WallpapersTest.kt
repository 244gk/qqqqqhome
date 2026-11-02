package com.x0finch.q5home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class WallpapersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun day(n: Int) = Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, n) }

    @Test
    fun dailyRotates() {
        assertNotEquals(
            Wallpapers.current(context, Wallpapers.DAILY, day(10))!!.key,
            Wallpapers.current(context, Wallpapers.DAILY, day(11))!!.key,
        )
    }

    private fun at(day: Int, hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_YEAR, day); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
    }

    @Test
    fun hourlyRotatesEveryHour() {
        fun key(c: Calendar) = Wallpapers.current(context, Wallpapers.DAILY, c, intervalMinutes = 60)!!.key
        assertEquals(key(at(10, 8, 0)), key(at(10, 8, 59)))
        assertNotEquals(key(at(10, 8, 59)), key(at(10, 9, 0)))
        // 30 minutes: switches at the half hour.
        assertNotEquals(Wallpapers.slot(at(10, 8, 29), 30), Wallpapers.slot(at(10, 8, 30), 30))
        // Daily: same as older versions, keyed by day of year, unchanged all day.
        assertEquals(10, Wallpapers.slot(at(10, 0, 0), 1440))
        assertEquals(10, Wallpapers.slot(at(10, 23, 59), 1440))
        assertEquals("自动轮换 · 每小时", Wallpapers.label(context, Wallpapers.DAILY, 60))
    }

    @Test
    fun fixedAndNone() {
        assertEquals("city", Wallpapers.current(context, "city")!!.key)
        assertNull(Wallpapers.current(context, Wallpapers.NONE))
        assertEquals("纯黑", Wallpapers.label(context, Wallpapers.NONE))
        // Once a selected online wallpaper has been pruned, fall back to daily rotation instead of blank.
        assertNotNull(Wallpapers.current(context, "u:gone"))
    }

    @Test
    fun builtinsDecode() {
        for (w in Wallpapers.BUILTIN) assertNotNull(w.key, Wallpapers.decode(context, w, 1280))
    }
}
