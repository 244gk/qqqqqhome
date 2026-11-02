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
        assertTrue(EventLog.markdown(app, null, "事件记录").contains("事件记录（2 条）"))

        // Past the cap the oldest part is dropped, starting at a whole line.
        val big = "x".repeat(500)
        repeat((EventLog.MAX_BYTES / 520 + 10).toInt()) { EventLog.append(app, "广播", "$it $big", t0 + 120_000) }
        val f = EventLog.file(app)
        assertTrue(f.length() <= EventLog.MAX_BYTES)
        val lines = EventLog.lines(app)
        assertFalse(lines.any { it.endsWith("old") })
        assertTrue(lines.all { it.startsWith("20") })
        EventLog.clear(app)
        assertTrue(EventLog.lines(app).isEmpty())
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
