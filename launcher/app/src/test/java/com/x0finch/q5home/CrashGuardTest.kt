package com.x0finch.q5home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RC

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class CrashGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun threeCrashesInWindowEnterSafeMode() {
        val t = 1_000_000_000L
        CrashGuard.record(context, RuntimeException("1"), t)
        CrashGuard.record(context, RuntimeException("2"), t + 60_000)
        assertFalse(CrashGuard.shouldEnterSafeMode(context, t + 61_000))
        CrashGuard.record(context, RuntimeException("3"), t + 120_000)
        assertTrue(CrashGuard.shouldEnterSafeMode(context, t + 121_000))
        assertNotNull(CrashGuard.lastError(context))
        // Once the time window has passed, old crashes no longer count
        assertFalse(CrashGuard.shouldEnterSafeMode(context, t + CrashGuard.WINDOW_MS + 60_001))
    }

    @Test
    fun resetClears() {
        val now = System.currentTimeMillis()
        repeat(3) { CrashGuard.record(context, RuntimeException("x"), now) }
        assertTrue(CrashGuard.shouldEnterSafeMode(context, now))
        CrashGuard.reset(context)
        assertEquals(0, CrashGuard.recent(context, now).size)
    }

    @Test
    fun homeRedirectsToSafeModeAfterCrashes() {
        val now = System.currentTimeMillis()
        repeat(3) { CrashGuard.record(context, RuntimeException("boom"), now) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val next = shadowOf(controller.get()).nextStartedActivity
        assertEquals(SafeModeActivity::class.java.name, next.component!!.className)
        assertTrue(controller.get().isFinishing)
        controller.destroy() // must not crash in onDestroy even if initialization never finished
    }
}
