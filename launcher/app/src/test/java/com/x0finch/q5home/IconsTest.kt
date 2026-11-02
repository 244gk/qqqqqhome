package com.x0finch.q5home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class IconsTest {
    @Test
    fun guessesCommonApps() {
        assertEquals("carplay", Icons.guess("com.zjinnova.zlink", "ZLINK"))
        assertEquals("oem", Icons.guess("com.wy.oem", "原车"))
        assertEquals("files", Icons.guess("com.android.documentsui", "Files"))
        assertEquals("settings", Icons.guess(null, "设置"))
        assertEquals("nav", Icons.guess("com.autonavi.amapauto", "高德地图车机版"))
        assertNull(Icons.guess("com.example.unknown", "Something"))
    }

    @Test
    fun everyIconRenders() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (icon in Icons.ALL) {
            val d = Icons.badge(context, icon)
            d.setBounds(0, 0, 96, 96)
            assertNotNull(icon.key, d)
        }
    }
}
