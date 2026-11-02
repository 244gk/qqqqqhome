package com.x0finch.q5home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * The GPS line below the home screen clock: speed, heading, altitude. Listens only while the home screen is visible and stops on leave.
 * Does nothing without location permission (granted on the "GPS 与 4G" page) or when disabled in settings.
 * Also records a coarse location (1 decimal place, about 10 km) for the sunrise/sunset wallpapers.
 */
class HomeGps(private val context: Context, private val onChange: (Location?) -> Unit) {

    private val lm = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    var last: Location? = null; private set

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (listener != null || lm == null || !hasPermission()) return
        // On Android 8.1 all four LocationListener methods are abstract and must all be implemented.
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                last = location
                Sun.remember(context, location)
                onChange(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { onChange(null) }
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
            listener = l
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last = it; Sun.remember(context, it) }
        }
    }

    fun stop() {
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
    }

    companion object {
        /** Hide if there's been no update for this long (tunnels, underground garages). */
        const val STALE_MS = 15_000L
        private val DIRECTIONS = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

        fun compass(bearing: Float): String = DIRECTIONS[(((bearing % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]

        /** Home screen GPS card: speed (large) + heading · altitude. */
        class Reading(val speedKmh: Int?, val detail: String)

        /** GPS counts weeks in 10 bits: chips that miss the rollover report times exactly 1024 weeks too early. */
        const val ROLLOVER_MS = 1024L * 7 * 24 * 3600 * 1000

        /** [time] (ms since 1970) with whole 1024-week rollovers added back when it is that far behind [nowMs]. */
        fun correctedTime(time: Long, nowMs: Long): Long {
            var t = time
            var n = 0
            while (time > 0 && nowMs - t > ROLLOVER_MS - 24 * 3600 * 1000L && n++ < 4) t += ROLLOVER_MS
            return t
        }

        /**
         * How old a fix is. Uses the boot clock ([Location.getElapsedRealtimeNanos]) when the fix has it: it doesn't depend on
         * the date the GPS chip reports (on this head unit that date is 1024 weeks behind). Otherwise the corrected fix time.
         */
        fun ageMs(l: Location, nowMs: Long = System.currentTimeMillis(),
                  nowElapsedNs: Long = SystemClock.elapsedRealtimeNanos()): Long =
            if (l.elapsedRealtimeNanos > 0 && nowElapsedNs >= l.elapsedRealtimeNanos) (nowElapsedNs - l.elapsedRealtimeNanos) / 1_000_000
            else nowMs - correctedTime(l.time, nowMs)

        /** Returns null without a fresh fix. Heading is shown only while moving (GPS heading is unreliable when stationary). */
        fun reading(location: Location?, nowMs: Long = System.currentTimeMillis(),
                    nowElapsedNs: Long = SystemClock.elapsedRealtimeNanos()): Reading? {
            val l = location ?: return null
            if (ageMs(l, nowMs, nowElapsedNs) > STALE_MS) return null
            val detail = listOfNotNull(
                if (l.hasBearing() && l.hasSpeed() && l.speed >= 1.5f) "${compass(l.bearing)} ${l.bearing.roundToInt() % 360}°" else null,
                if (l.hasAltitude()) "海拔 ${l.altitude.roundToInt()} m" else null,
            ).joinToString("  ·  ")
            return Reading(if (l.hasSpeed()) (l.speed * 3.6f).roundToInt() else null, detail)
        }

        /** Single-line text version (speed · heading · altitude); returns null without a fresh fix. */
        fun text(location: Location?, nowMs: Long = System.currentTimeMillis(),
                 nowElapsedNs: Long = SystemClock.elapsedRealtimeNanos()): String? {
            val r = reading(location, nowMs, nowElapsedNs) ?: return null
            val parts = listOfNotNull(r.speedKmh?.let { "$it km/h" }, r.detail.ifEmpty { null })
            return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
        }
    }
}
