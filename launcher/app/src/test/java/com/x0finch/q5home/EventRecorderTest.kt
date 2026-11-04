package com.x0finch.q5home

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC

/** Background recon recording: listen-only event log, its size cap, and the native-library reader. */
@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class EventRecorderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun logKeepsNewestAndFiltersByTime() {
        EventLog.clear(app)
        val t0 = 1_762_000_000_000L
        EventLog.append(app, "广播", "old", t0)
        EventLog.append(app, "广播", "new\nline", t0 + 60_000)
        val all = EventLog.lines(app)
        assertEquals(2, all.size)
        assertTrue(all[1].endsWith("广播  new line")) // one event = one line
        assertEquals(listOf(all[1]), EventLog.lines(app, sinceMs = t0 + 30_000))
        assertEquals(t0 + 60_000, EventLog.timeOf(all[1]))
        // Written in batches: nothing on disk until a flush (lines() flushes first).
        EventLog.clear(app)
        EventLog.append(app, "广播", "batched", t0)
        assertTrue(!EventLog.file(app).exists() || EventLog.file(app).length() == 0L)
        EventLog.append(app, "启动", "important", t0, now = true)
        assertTrue(EventLog.file(app).readText().contains("batched"))

        // Past the cap the oldest part is dropped, starting at a whole line.
        val big = "x".repeat(500)
        repeat((EventLog.MAX_BYTES / 520 + 10).toInt()) { EventLog.append(app, "广播", "$it $big", t0 + 120_000) }
        EventLog.flush()
        val f = EventLog.file(app)
        assertTrue(f.length() <= EventLog.MAX_BYTES)
        val lines = EventLog.lines(app)
        assertFalse(lines.any { it.endsWith("batched") })
        assertTrue(lines.all { it.startsWith("20") })
        EventLog.clear(app)
        assertTrue(EventLog.lines(app).isEmpty())
    }

    @Test
    fun clockRolledBack1024WeeksIsCorrected() {
        val real = 1_793_700_000_000L // 2026-11-03
        val rolled = real - HomeGps.ROLLOVER_MS // 2007-03-20, what the head unit showed
        assertEquals(real, TrueTime.correct(rolled))
        assertEquals(real, TrueTime.correct(real))
        assertEquals(0L, TrueTime.correct(0L))
        // A line stamped with the rolled-back clock reads as the real time.
        EventLog.clear(app)
        EventLog.append(app, "广播", "rolled", rolled)
        val line = EventLog.lines(app).single()
        assertEquals(real, EventLog.timeOf(line))
        // The report shows the corrected date, not 2007.
        assertTrue(EventLog.corrected(line).startsWith("2026-"))
        assertTrue(EventLog.corrected(line).endsWith("广播  rolled"))
        EventLog.clear(app)
    }

    @Test
    fun realTimeSurvivesClockJumpsWithinABoot() {
        val real = 1_793_700_000_000L // 2026-11-03
        TrueTime.reset()
        assertEquals(real, TrueTime.now(real, 1_000))               // plausible clock: anchored
        assertEquals(real + 60_000, TrueTime.now(5_000_000L, 61_000)) // clock fell back to 1970: anchor + elapsed
        assertEquals(real + 120_000, TrueTime.now(real - HomeGps.ROLLOVER_MS + 120_000, 121_000)) // GPS rollover
        assertEquals(real + 180_500, TrueTime.now(real + 180_500, 181_000)) // agrees again: the clock is followed
        // Someone sets the clock to another plausible date: followed, unless the real time came from a server.
        val day = 86_400_000L
        assertEquals(real + day, TrueTime.now(real + day, 241_000))
        // No anchor (just booted, offline): one rollover is added back, but a 1970 clock is not turned into a made-up date.
        TrueTime.reset()
        assertEquals(real, TrueTime.now(real - HomeGps.ROLLOVER_MS, 0))
        TrueTime.reset()
        assertEquals(5_000_000L, TrueTime.now(5_000_000L, 0))
        // Old 1970-stamped log lines are not "corrected" to 2028.
        assertEquals(5_000_000L, TrueTime.correct(5_000_000L))
        TrueTime.reset()
    }

    @Test
    fun eventsAfterTheCopiedLineSurviveAClockJump() {
        EventLog.clear(app)
        val t = 1_793_700_000_000L
        EventLog.append(app, "广播", "before", t)
        val marker = EventLog.lines(app).last()
        // The clock jumps back after the report was copied; time filtering would drop these, the line marker keeps them.
        EventLog.append(app, "广播", "after1", t - 3_600_000L)
        EventLog.append(app, "广播", "after2", t - 3_500_000L)
        val after = EventLog.lines(app, sinceMs = t, afterLine = marker)
        assertEquals(2, after.size)
        assertTrue(after[0].endsWith("after1"))
        // A marker no longer in the log (trimmed / cleared) falls back to the time filter.
        assertEquals(1, EventLog.lines(app, sinceMs = t, afterLine = "gone").size)
        EventLog.clear(app)
    }

    @Test
    fun recordsBroadcastsWithExtrasAndFoldsRepeats() {
        EventLog.clear(app)
        EventRecorder.start(app, force = true)
        waitFor { EventLog.lines(app).any { it.contains("开始记录") } }
        val intent = Intent("com.wwc2.APK_TO_MAIN").putExtra("cmd", 7).putExtra("data", intArrayOf(1, 2))
        repeat(3) { app.sendBroadcast(intent) }
        waitFor { EventLog.lines(app).any { it.contains("com.wwc2.APK_TO_MAIN") } }
        Thread.sleep(300)
        val hits = EventLog.lines(app).filter { it.contains("com.wwc2.APK_TO_MAIN") }
        assertEquals("repeats within a second are folded", 1, hits.size)
        assertTrue(hits[0], hits[0].contains("广播  com.wwc2.APK_TO_MAIN · cmd=7, data=[1, 2]"))
        assertTrue(EventLog.lines(app).first().contains("q5home 进程启动"))
    }

    @Test
    fun knownNoiseIsNeverRecorded() {
        assertTrue(EventRecorder.isIgnored("android.intent.action.SIG_STR · LteRsrp=-99"))
        assertTrue(EventRecorder.isIgnored("content://com.wwc2.canmcusdk.CanbusProvider/car_info"))
        assertTrue(EventRecorder.isIgnored("android.intent.action.TIME_TICK"))
        assertFalse(EventRecorder.isIgnored("com.wwc2.APK_TO_MAIN · cmd=7"))
        assertFalse(EventRecorder.isIgnored("content://com.wwc2.canmcusdk.CanbusProvider/original_screen"))
    }

    @Test
    fun actionNames() {
        assertTrue(EventRecorder.isAction("com.wwc2.main.ACTION_SWITCH"))
        assertTrue(EventRecorder.isAction("comm.power.ACC_ON"))
        assertTrue(EventRecorder.isAction("com.wwc2.canbus.action.update"))
        assertFalse(EventRecorder.isAction("com.wwc2.main.MainActivity"))
        assertFalse(EventRecorder.isAction("android.permission.INTERNET"))
    }

    @Test
    fun readsNativeLibraryExportsAndDevicePaths() {
        // test/resources/libavin-test.so.bin, built from libavin-test.c.txt with gcc (".bin": the build drops .so files from test resources).
        val so = javaClass.classLoader!!.getResourceAsStream("libavin-test.so.bin")!!.readBytes()
        val r = ElfScan.scan(so)
        assertNotNull(r)
        assertEquals(listOf("Java_com_wwc2_main_avin_AvinSignal_switchSource", "avin_open", "use_hidden"), r!!.exports)
        assertTrue(r.strings.toString(), r.strings.containsAll(listOf("/dev/avin_switch", "/dev/ttyMT1")))
        assertFalse(r.strings.contains("hello world"))
        assertEquals(null, ElfScan.scan(ByteArray(100)))
        assertTrue(runCatching { ElfScan.scan(so.copyOf(200)) }.isSuccess)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        assertTrue("timed out", condition())
    }
}
