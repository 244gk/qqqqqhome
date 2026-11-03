package com.x0finch.q5home

import java.util.Calendar
import java.util.Date

/**
 * The real time, even when the head unit's clock is 1024 weeks behind. With "自动确定日期和时间" on, this head unit sets its clock
 * from the GPS chip, which misses the GPS week rollover: the clock then reads exactly 1024 weeks (19.6 years) earlier
 * (2026-11-03 became 2007-03-20, same weekday and time of day). q5home cannot set the system clock, so everything it shows or
 * records (home date, event log, reports, sunrise / sunset) goes through [now] / [correct].
 */
object TrueTime {
    /** No real date is earlier than this (q5home did not exist yet): an earlier clock reading has rolled over. */
    const val EARLIEST = 1_735_689_600_000L // 2025-01-01T00:00:00Z

    /** [time] with whole 1024-week rollovers added back when it is before [EARLIEST]. */
    fun correct(time: Long): Long {
        var t = time
        var n = 0
        while (t in 1 until EARLIEST && n++ < 4) t += HomeGps.ROLLOVER_MS
        return t
    }

    fun now(): Long = correct(System.currentTimeMillis())

    /** The system clock reads 1024 weeks behind (shown as a hint on the home screen). */
    fun systemClockRolledBack(): Boolean = System.currentTimeMillis() < EARLIEST

    fun date(): Date = Date(now())

    fun calendar(): Calendar = Calendar.getInstance().apply { timeInMillis = now() }
}
