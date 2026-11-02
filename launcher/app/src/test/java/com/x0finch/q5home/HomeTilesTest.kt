package com.x0finch.q5home

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RC

/** Tile titles (unified Chinese name when the icon matches the app, otherwise the app's own name), order and shape. */
@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class HomeTilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var home: MainActivity? = null

    private fun titles(dock: List<DockItem>): List<String> {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        Config(context).setDock(dock)
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        this.home = home
        shadowOf(Looper.getMainLooper()).idle()
        val tiles = home.findViewById<ViewGroup>(R.id.tiles)
        // The line with the largest text size in each tile is the title.
        return (0 until tiles.childCount).map { i ->
            texts(tiles.getChildAt(i)).maxByOrNull { it.textSize }?.text?.toString().orEmpty()
        }
    }

    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun matchingIconUsesUnifiedName() {
        val t = titles(listOf(DockItem("settings", Targets.SYSTEM_SETTINGS), DockItem("nav", FakeApps.NAVI)))
        assertEquals(listOf("系统设置", "导航"), t.subList(1, 3))
    }

    @Test
    fun mismatchedOrAppIconUsesAppName() {
        // An English system label ("Settings") still gets the unified Chinese name; a Chinese label stays as is.
        val t = titles(listOf(DockItem("nav", Targets.SYSTEM_SETTINGS), DockItem(null, FakeApps.NAVI)))
        assertEquals(listOf("系统设置", "高德地图车机版"), t.subList(1, 3))
    }

    @Test
    fun addIsAlwaysLastAndEveryTileIsSquare() {
        val t = titles(listOf(DockItem("files", FakeApps.FILES)))
        assertEquals(listOf("原车", "文件", "全部应用", "添加"), t)
        val tiles = home!!.findViewById<ViewGroup>(R.id.tiles)
        for (i in 0 until tiles.childCount) {
            val tile = tiles.getChildAt(i)
            assertEquals("tile $i should be square", tile.layoutParams.height, tile.layoutParams.width)
        }
    }
}
