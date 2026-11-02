package com.x0finch.q5home

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class SunAndGpsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun at(zone: String, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private fun assertNear(expected: Long, actual: Long) =
        assertTrue("off by ${(actual - expected) / 60000} minutes", abs(actual - expected) <= 4 * 60_000)

    @Test
    fun sunTimesMatchPublishedValues() {
        // Toronto 2026-06-21: sunrise about 5:36, sunset about 21:03 (DST).
        val tz = TimeZone.getTimeZone("America/Toronto")
        val (rise, set) = Sun.times(43.65, -79.38, at("America/Toronto", 2026, 6, 21, 12, 0), tz)!!
        assertNear(at("America/Toronto", 2026, 6, 21, 5, 36), rise)
        assertNear(at("America/Toronto", 2026, 6, 21, 21, 3), set)
        // Beijing 2026-12-21: sunrise about 7:33, sunset about 16:53.
        val bj = TimeZone.getTimeZone("Asia/Shanghai")
        val (r2, s2) = Sun.times(39.9, 116.4, at("Asia/Shanghai", 2026, 12, 21, 12, 0), bj)!!
        assertNear(at("Asia/Shanghai", 2026, 12, 21, 7, 33), r2)
        assertNear(at("Asia/Shanghai", 2026, 12, 21, 16, 53), s2)
        // Svalbard in June, polar day: no sunrise or sunset.
        assertNull(Sun.times(78.2, 15.6, at("UTC", 2026, 6, 21, 12, 0), TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun daylightUsesRememberedCoarseLocation() {
        // No location: use 7:00–19:00.
        val noon = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis
        val night = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 3) }.timeInMillis
        assertTrue(Sun.isDaylight(app, noon))
        assertFalse(Sun.isDaylight(app, night))
        // Location is stored with only 1 decimal place.
        Sun.remember(app, Location("gps").apply { latitude = 43.6532; longitude = -79.3832 })
        assertEquals(43.7f, Sun.location(app)!!.first.toFloat(), 0.001f)
        assertEquals(-79.4f, Sun.location(app)!!.second.toFloat(), 0.001f)
    }

    @Test
    fun homeGpsText() {
        val now = 1_000_000L
        fun loc(block: Location.() -> Unit) = Location("gps").apply { time = now; block() }
        assertNull(HomeGps.text(null, now))
        assertNull("stale fixes are not shown", HomeGps.text(loc { speed = 10f; time = now - 20_000 }, now))
        // 72 km/h heading northeast, altitude 120 m.
        assertEquals("72 km/h  ·  东北 45°  ·  海拔 120 m",
            HomeGps.text(loc { speed = 20f; bearing = 45f; altitude = 120.4 }, now))
        // Stationary: no heading shown.
        assertEquals("0 km/h  ·  海拔 76 m", HomeGps.text(loc { speed = 0f; bearing = 200f; altitude = 76.0 }, now))
        assertEquals("北", HomeGps.compass(359f))
        assertEquals("南", HomeGps.compass(180f))
        assertEquals("西北", HomeGps.compass(-45f))
    }

    @Test
    fun dayNightWallpaperPools() {
        repeat(30) { i ->
            val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, i + 1) }
            val day = Wallpapers.current(app, Wallpapers.DAILY, c, daylight = true)!!
            val night = Wallpapers.current(app, Wallpapers.DAILY, c, daylight = false)!!
            assertFalse(day.key, Wallpapers.isNight(app, day))
            assertTrue(night.key, Wallpapers.isNight(app, night))
        }
        // Sunrise/sunset mode off: rotate through all wallpapers as before.
        val keys = (1..12).map { Wallpapers.current(app, Wallpapers.DAILY, Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, it) })!!.key }
        assertTrue(keys.toSet().size >= 6)
    }
}
