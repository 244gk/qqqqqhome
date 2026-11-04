package com.x0finch.q5home

import android.content.Context
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Date

/**
 * The real time, even when the head unit's clock is wrong. Seen on this head unit: with "自动确定日期和时间" on it took the GPS
 * chip's date, which misses the GPS week rollover (2026-11-03 read as 2007-03-20, exactly 1024 weeks earlier), and later the clock
 * fell back to 1970. q5home cannot set the system clock, so everything it shows or records goes through [now]:
 *  1. an anchor (real time + boot clock) is kept while the clock is plausible, and refreshed from a plain-HTTP `Date` header
 *     ([syncFromNetwork], works even when HTTPS fails because of the wrong date); within the same boot the real time is the anchor
 *     plus the elapsed boot clock, so a clock jump changes nothing;
 *  2. without an anchor (just booted with a wrong clock, offline), a clock before [EARLIEST] gets one 1024-week rollover added
 *     back when that lands on a plausible date; otherwise (e.g. 1970) the clock is used as it is.
 */
object TrueTime {
    /** No real date is earlier than this (q5home did not exist yet). */
    const val EARLIEST = 1_735_689_600_000L // 2025-01-01T00:00:00Z
    /** A clock reading this far from the anchor's prediction counts as a jump. */
    const val JUMP_MS = 2 * 60_000L
    private const val DAY_MS = 86_400_000L
    private const val PERSIST_EVERY_MS = 10 * 60_000L
    const val SYNC_URL = "http://connectivitycheck.gstatic.com/generate_204"

    private var app: Context? = null
    private var boot = -1
    // Anchor: the real time [anchorTrue] at boot-clock [anchorElapsed]; [fromNet] = learned from a server, not from the clock.
    @Volatile private var anchorTrue = 0L
    @Volatile private var anchorElapsed = 0L
    @Volatile private var fromNet = false
    private var persistedAt = 0L

    /** Restores this boot's anchor (q5home's process restarts often, e.g. on every update). */
    @Synchronized
    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        boot = runCatching { android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }
            .getOrDefault(-1)
        val p = c.getSharedPreferences("true_time", Context.MODE_PRIVATE)
        val el = SystemClock.elapsedRealtime()
        if (boot >= 0 && p.getInt("boot", -2) == boot && p.getLong("elapsed", Long.MAX_VALUE) <= el) {
            anchorTrue = p.getLong("true", 0L)
            anchorElapsed = p.getLong("elapsed", 0L)
            fromNet = p.getBoolean("net", false)
        }
    }

    private fun anchored() = anchorTrue > 0

    @Synchronized
    private fun setAnchor(trueMs: Long, elapsed: Long, net: Boolean) {
        anchorTrue = trueMs
        anchorElapsed = elapsed
        fromNet = net
        persist(elapsed, force = true)
    }

    private fun persist(elapsed: Long, force: Boolean) {
        if (!force && elapsed - persistedAt < PERSIST_EVERY_MS) return
        persistedAt = elapsed
        app?.getSharedPreferences("true_time", Context.MODE_PRIVATE)?.edit()
            ?.putInt("boot", boot)?.putLong("true", anchorTrue)?.putLong("elapsed", anchorElapsed)?.putBoolean("net", fromNet)?.apply()
    }

    /** The real time now. */
    fun now(): Long = now(System.currentTimeMillis(), SystemClock.elapsedRealtime())

    internal fun now(wall: Long, elapsed: Long): Long {
        val plausible = wall >= EARLIEST
        if (anchored()) {
            val predicted = anchorTrue + (elapsed - anchorElapsed)
            if (!plausible) return predicted
            if (Math.abs(wall - predicted) <= JUMP_MS) {
                // The clock agrees: follow it (a clock anchor is moved along so it stays fresh).
                if (!fromNet) { anchorTrue = wall; anchorElapsed = elapsed; persist(elapsed, force = false) }
                return wall
            }
            // A jump to another plausible date: a server-learned anchor wins; otherwise someone set the clock, follow it.
            if (fromNet) return predicted
            setAnchor(wall, elapsed, net = false)
            return wall
        }
        if (plausible) { setAnchor(wall, elapsed, net = false); return wall }
        return rolledForward(wall) ?: wall
    }

    /**
     * [wall] plus one 1024-week rollover when that lands on a plausible date (not past [limit]), else null. Only one: the GPS chip
     * is off by exactly one rollover, while a clock reset to 1970 plus several rollovers would land on a made-up date.
     */
    private fun rolledForward(wall: Long, limit: Long? = null): Long? {
        val t = wall + HomeGps.ROLLOVER_MS
        return t.takeIf { wall > 0 && it >= EARLIEST && (limit == null || it <= limit) }
    }

    /** Forgets the anchor (tests). */
    @Synchronized
    internal fun reset() { anchorTrue = 0L; anchorElapsed = 0L; fromNet = false; persistedAt = 0L }

    /**
     * A time stamp written with the raw clock (old event-log lines, file times): kept when plausible, otherwise corrected by one
     * 1024-week rollover if that lands between [EARLIEST] and now; a 1970-style reading stays as it is (it can't be recovered).
     */
    fun correct(time: Long): Long =
        if (time >= EARLIEST) time else rolledForward(time, limit = now() + DAY_MS) ?: time

    /** A raw clock time stamp taken just now (e.g. a system event's time) converted to the real time. */
    fun fromWall(wallStamp: Long): Long = now() - (System.currentTimeMillis() - wallStamp)

    /** The system clock is off by more than [JUMP_MS] from the real time (shown in the report). */
    fun systemClockWrong(): Boolean = Math.abs(System.currentTimeMillis() - now()) > JUMP_MS

    fun date(): Date = Date(now())

    fun calendar(): Calendar = Calendar.getInstance().apply { timeInMillis = now() }

    /**
     * Learns the real time from a server's `Date` header over plain HTTP (an empty "is there internet" page; nothing is sent).
     * HTTPS can't be used: with a wrong clock its certificates look invalid. Call on a background thread; false when offline.
     */
    fun syncFromNetwork(url: String = SYNC_URL): Boolean = runCatching {
        val start = SystemClock.elapsedRealtime()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "q5home")
        }
        try {
            conn.responseCode
            val server = conn.date
            val end = SystemClock.elapsedRealtime()
            if (server < EARLIEST) return@runCatching false
            // The header has whole seconds: take the middle of the second and of the round trip.
            setAnchor(server + 500, (start + end) / 2, net = true)
            true
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
