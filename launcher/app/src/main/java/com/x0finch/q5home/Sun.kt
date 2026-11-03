package com.x0finch.q5home

import android.content.Context
import android.location.Location
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sunrise/sunset (offline, computed from location and date, accurate to a minute or two): used for switching day / night wallpapers by sunrise and sunset.
 * Location is the most recent GPS fix, stored only to 1 decimal place (about 10 km); with no fix yet, daytime is 7:00–19:00.
 * Formula: Wikipedia's Sunrise equation (simplified NOAA version).
 */
object Sun {

    private const val PREFS = "sun"

    /** Records a coarse location (1 decimal place). */
    fun remember(context: Context, location: Location) {
        val lat = (location.latitude * 10).roundToInt() / 10f
        val lng = (location.longitude * 10).roundToInt() / 10f
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getFloat("lat", 999f) != lat || prefs.getFloat("lng", 999f) != lng) {
            prefs.edit().putFloat("lat", lat).putFloat("lng", lng).apply()
        }
    }

    fun location(context: Context): Pair<Double, Double>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getFloat("lat", 999f)
        val lng = prefs.getFloat("lng", 999f)
        return if (lat == 999f || lng == 999f) null else lat.toDouble() to lng.toDouble()
    }

    /**
     * Sunrise and sunset times (millis) for the day containing [dayMillis] (in [zone]). Returns null for polar day / polar night.
     */
    fun times(lat: Double, lng: Double, dayMillis: Long, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Long>? {
        // Local noon of the date, converted to a Julian day.
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = dayMillis
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val jd = cal.timeInMillis / 86_400_000.0 + 2440587.5
        val n = floor(jd - 2451545.0 + 0.0008 + 0.5)
        val jStar = n - lng / 360.0
        val m = Math.toRadians((357.5291 + 0.98560028 * jStar).mod(360.0))
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180 + 102.9372).mod(360.0))
        val transit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val phi = Math.toRadians(lat)
        val cosW = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (cosW < -1 || cosW > 1) return null
        val w = Math.toDegrees(acos(cosW)) / 360.0
        fun toMillis(j: Double) = ((j - 2440587.5) * 86_400_000.0).toLong()
        return toMillis(transit - w) to toMillis(transit + w)
    }

    /** Whether it's daytime now: by sunrise/sunset if a location is known, otherwise 7:00–19:00. */
    fun isDaylight(context: Context, now: Long = TrueTime.now()): Boolean {
        val loc = location(context)
        val t = loc?.let { times(it.first, it.second, now) }
        if (t != null) return now in t.first until t.second
        if (loc != null) {
            // Polar day / night: check the sun's noon elevation — high-latitude summer counts as day.
            val month = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MONTH)
            return (loc.first > 0) == (month in Calendar.APRIL..Calendar.SEPTEMBER)
        }
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return hour in 7 until 19
    }
}
