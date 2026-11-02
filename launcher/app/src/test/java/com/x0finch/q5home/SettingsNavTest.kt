package com.x0finch.q5home

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

/** Settings: switching sections cross-fades and ends fully visible; a swipe from the left edge returns to the launcher. */
@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class SettingsNavTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun switchingSectionEndsFullyVisible() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        settings.switchSection(SettingsActivity.SECTION_ADVANCED)
        // Run the fade-out, the rebuild and the fade-in.
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        val root = settings.window.decorView
        assertTrue("advanced section should be shown", texts(root).contains("恢复默认布局"))
        val content = findContentWith(root as ViewGroup, "恢复默认布局")!!
        assertEquals(1f, content.alpha, 0.001f)
        assertEquals(0f, content.translationY, 0.001f)
    }

    @Test
    fun swipeFromLeftEdgeFinishes() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        val t = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, dt: Long) {
            val e = android.view.MotionEvent.obtain(t, t + dt, action, x, 240f, 0)
            settings.dispatchTouchEvent(e)
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, 4f, 0)
        for (i in 1..8) send(android.view.MotionEvent.ACTION_MOVE, 4f + i * 30f, i * 16L)
        assertTrue("left-edge swipe should close settings", settings.isFinishing)
    }

    /** The vertical content column (the ScrollView's child) that contains [text]. */
    private fun findContentWith(v: ViewGroup, text: String): View? {
        if (v is android.widget.ScrollView && texts(v).contains(text)) return v.getChildAt(0)
        for (i in 0 until v.childCount) (v.getChildAt(i) as? ViewGroup)?.let { g -> findContentWith(g, text)?.let { return it } }
        return null
    }
}
