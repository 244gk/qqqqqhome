package com.x0finch.q5home

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RC
import java.time.Duration

/** Auto-launch CarPlay on boot: only once per boot; a launcher restart (e.g. after a crash) does not open it again. */
@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class AutoCarPlayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launchHomeAndGetStarted(): String? {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        return shadowOf(activity).nextStartedActivity?.component?.packageName
    }

    @Test
    fun oncePerBoot() {
        FakeApps.installCar(context)
        Config(context).setAutoCarplay(true)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)

        assertEquals("com.zjinnova.zlink", launchHomeAndGetStarted())
        // Launcher restart within the same boot: not opened again
        assertNull(launchHomeAndGetStarted())
        // Next boot: opened again
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertEquals("com.zjinnova.zlink", launchHomeAndGetStarted())
    }
}
