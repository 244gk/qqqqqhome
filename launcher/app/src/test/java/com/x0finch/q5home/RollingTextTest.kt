package com.x0finch.q5home

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.x0finch.q5home.ui.RollingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config as RC

/** Rolling digits on the home screen (clock / speed): text and width follow the value; not attached = replaced without animation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RC(sdk = [31])
class RollingTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun widthFollowsTextAndDescriptionIsSet() {
        val v = RollingText(context).apply { setTextSize(40f) }
        v.setText("9", animate = true)
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val narrow = v.measuredWidth
        v.setText("120", animate = true)
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        assertTrue("three digits should be wider than one", v.measuredWidth > narrow)
        assertEquals("120", v.text)
        assertEquals("120", v.contentDescription)
        assertTrue(v.measuredHeight > 0)
    }
}
