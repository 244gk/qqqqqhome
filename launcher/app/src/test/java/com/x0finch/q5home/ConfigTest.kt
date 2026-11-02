package com.x0finch.q5home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC
import java.io.File

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class ConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = FakeApps.installCar(context)

    private fun writeConfig(json: String) {
        File(context.getExternalFilesDir(null), Config.FILE).apply {
            writeText(json)
            setLastModified(System.currentTimeMillis() + (Math.random() * 100000).toLong())
        }
    }

    @Test
    fun defaultsAreDetected() {
        val config = Config(context).also { it.reload() }
        assertEquals("com.zjinnova.zlink", Targets.packageOf(config.carplay))
        assertEquals(FakeApps.OEM, config.oem)
        assertEquals(listOf("files", "settings"), config.dock.map { it.icon })
        assertEquals(Wallpapers.DAILY, config.wallpaper)
        assertEquals(Wallpapers.DEFAULT_INTERVAL, config.wallpaperInterval)
    }

    @Test
    fun importsConfigJsonWithBothDockForms() {
        writeConfig(
            """{"oem": "broadcast:com.wy.SWITCH_OEM@com.wy.oem",
                "dock": [{"icon": "nav", "target": "com.autonavi.amapauto"}, "com.android.documentsui"],
                "wallpaper": "city", "wallpaperInterval": 60, "wallpaperSun": true, "homeGps": false, "autoCarplay": true}"""
        )
        val config = Config(context)
        assertNull(config.reload())
        assertEquals("broadcast:com.wy.SWITCH_OEM@com.wy.oem", config.oem)
        assertEquals(DockItem("nav", "com.autonavi.amapauto"), config.dock[0])
        assertEquals(DockItem(null, "com.android.documentsui"), config.dock[1])
        assertEquals("city", config.wallpaper)
        assertEquals(60, config.wallpaperInterval)
        assertEquals(true, config.wallpaperSun)
        assertEquals(false, config.homeGps)
        assertEquals(true, config.autoCarplay)
        // current.json contains the effective config.
        assertNotNull(File(context.getExternalFilesDir(null), Config.CURRENT_FILE).readText())
    }

    @Test
    fun badJsonIsReportedAndIgnored() {
        writeConfig("{ not json")
        val config = Config(context)
        assertNotNull(config.reload())
        assertEquals(FakeApps.OEM, config.oem)
    }

    @Test
    fun blankTargetFallsBackToDetection() {
        writeConfig("""{"carplay": "", "oem": ""}""")
        val config = Config(context).also { it.reload() }
        assertEquals("com.zjinnova.zlink", Targets.packageOf(config.carplay))
        assertEquals(FakeApps.OEM, config.oem)
    }

    @Test
    fun replacingTargetKeepsIcon() {
        val config = Config(context).also { it.reload() }
        config.replaceTarget(0, FakeApps.NAVI)
        assertEquals(DockItem("files", FakeApps.NAVI), config.dock[0])
        config.setIcon(0, "nav")
        config.moveInDock(0, 1)
        assertEquals(DockItem("nav", FakeApps.NAVI), config.dock[1])
        config.reset()
        assertEquals(listOf("files", "settings"), config.dock.map { it.icon })
    }
}
