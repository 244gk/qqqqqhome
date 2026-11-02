package com.x0finch.q5home

import android.app.Application
import android.location.Location
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class SignalCheckTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun namesAndTexts() {
        assertEquals("没插 SIM 卡", SignalCheck.simStateName(TelephonyManager.SIM_STATE_ABSENT))
        assertEquals("4G（LTE）", SignalCheck.networkTypeName(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G", SignalCheck.networkTypeName(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("没有移动网络", SignalCheck.networkTypeName(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        assertEquals("3/4 格 · 好 · -95 dBm", SignalCheck.signalText(3, -95))
        assertEquals("未知", SignalCheck.signalText(null, null))
        assertEquals("0/4 格 · 没信号", SignalCheck.signalText(0, Int.MAX_VALUE)) // invalid dBm is not shown
    }

    @Test
    fun satellitesAndFix() {
        assertTrue(SignalCheck.satellitesText(emptyList()).startsWith("还没收到卫星"))
        val sats = listOf(SignalCheck.Satellite(35f, true), SignalCheck.Satellite(22f, true), SignalCheck.Satellite(12f, false))
        assertEquals("看到 3 颗 · 用于定位 2 颗 · 最强 35 dB-Hz（好）", SignalCheck.satellitesText(sats))

        assertTrue(SignalCheck.fixText(null, null).startsWith("还没定位"))
        val loc = Location("gps").apply { accuracy = 8f; time = 1_000_000L }
        assertEquals("已定位 · 精度约 ±8 米 · 首次定位用时 42 秒", SignalCheck.fixText(loc, 42_000, nowMs = 1_002_000L))
        assertTrue(SignalCheck.fixText(loc, null, nowMs = 1_060_000L).endsWith("最近一次是 60 秒前"))
    }

    /** Report text still works without permissions: no crash and no coordinates. */
    @Test
    fun linesWithoutPermissions() {
        val s = SignalCheck(app)
        val all = s.gpsLines() + s.cellLines("能上网 ✓")
        assertTrue(all.any { it.first == "定位" })
        assertTrue(all.none { it.second.contains("°") })
        s.start {}
        s.stop()
    }
}
