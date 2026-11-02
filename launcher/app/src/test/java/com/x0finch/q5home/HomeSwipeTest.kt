package com.x0finch.q5home

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RC

/** Swiping horizontally on the non-clickable home cards (clock, GPS) scrolls the app tiles at the bottom along with it. */
@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class HomeSwipeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun swipeOnBlankAreaScrollsTiles() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        // Add extra favorite apps so the tiles overflow the screen width.
        Config(context).apply { repeat(9) { i -> setDock(dock + DockItem("nav", "${FakeApps.NAVI}$i")) } }
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = home.findViewById<View>(R.id.root)
        val scroll = home.findViewById<HorizontalScrollView>(R.id.tileScroll)
        assertTrue("tiles should overflow the screen", scroll.getChildAt(0).width > scroll.width)

        // Drag from the GPS card to the clock card (both non-clickable), 600px to the left.
        val y = root.height * 0.35f
        val t = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, dt: Long) {
            val e = MotionEvent.obtain(t, t + dt, action, x, y, 0)
            root.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 630f, 0)
        for (i in 1..12) send(MotionEvent.ACTION_MOVE, 630f - i * 50f, i * 16L)
        send(MotionEvent.ACTION_UP, 30f, 13 * 16L)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("tile list should have scrolled, scrollX=${scroll.scrollX}", scroll.scrollX > 300)
    }

    /** Without location permission, a tap (no drag) on the GPS card opens the "GPS 与 4G" settings page. */
    @Test
    fun tapGpsCardWithoutPermissionOpensSignalSettings() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = home.findViewById<View>(R.id.root)
        val card = home.findViewById<View>(R.id.gpsCard)
        val pos = IntArray(2)
        card.getLocationInWindow(pos)
        val x = pos[0] + card.width / 2f
        val y = pos[1] + card.height / 2f
        val t = SystemClock.uptimeMillis()
        for ((action, dt) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 60L)) {
            val e = MotionEvent.obtain(t, t + dt, action, x, y, 0)
            root.dispatchTouchEvent(e)
            e.recycle()
        }
        val next = shadowOf(home).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, next?.component?.className)
        assertEquals(SettingsActivity.SECTION_SIGNAL, next?.getStringExtra(SettingsActivity.EXTRA_SECTION))
    }
}
