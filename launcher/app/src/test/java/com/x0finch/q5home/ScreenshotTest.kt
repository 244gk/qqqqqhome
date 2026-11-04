package com.x0finch.q5home

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config as RC
import java.io.File

/** Renders the home screen at several common head-unit resolutions and writes PNGs (CI uploads them as artifacts). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RC(sdk = [31])
class ScreenshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun shoot(activity: Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val v = activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        v.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(qualifiers: String, name: String, wallpaper: String = "stars") {
        RuntimeEnvironment.setQualifiers(qualifiers)
        FakeApps.installCar(context)
        // The same default layout as in the car (favorite apps: Files and Settings only); also used for the README previews.
        Config(context).setWallpaper(wallpaper)
        shoot(Robolectric.buildActivity(MainActivity::class.java).setup().get(), name)
    }

    @Test fun home1280x480() = home("w1280dp-h480dp-land-mdpi", "home-1280x480")
    @Test fun home1920x720() = home("w1280dp-h480dp-land-hdpi", "home-1920x720", "city")
    @Test fun home1024x600() = home("w1024dp-h600dp-land-mdpi", "home-1024x600", "road")
    @Test fun homeBlack() = home("w1280dp-h480dp-land-mdpi", "home-black", Wallpapers.NONE)

    /** Fake CanbusProvider: the car_info getter returns what the head unit reported (57 fuel, trunk open). */
    class CarInfoProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? =
            if (method == "car_info") android.os.Bundle().apply {
                putInt("RESIDUAL_OIL", 57); putInt("ENGINE_SPEED", 2300); putInt("AVERAGE_SPEED", 62); putInt("DRIVER_SEAT_BELT", 1)
                putBoolean("TRUNK_DOOR", true)
            } else null
        override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    /** On the WWC2 platform: the OEM tile switches directly, the driving card shows the car's speed and rpm (no GPS fix), the car card fuel and warnings. */
    @Test
    fun homeOnWwc2() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply { packageName = Targets.CANMCU_SDK })
        Robolectric.setupContentProvider(CarInfoProvider::class.java, "com.wwc2.canmcusdk.CanbusProvider")
        Config(context).setWallpaper("stars")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(40) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            if (findText(activity.window.decorView, "油量 57 L") != null) return@repeat
            Thread.sleep(50)
        }
        org.junit.Assert.assertNotNull(findText(activity.window.decorView, "油量 57 L"))
        org.junit.Assert.assertNotNull(findText(activity.window.decorView, "车上报的车速  ·  转速 2300"))
        org.junit.Assert.assertNotNull(findText(activity.window.decorView, "后备箱开着"))
        org.junit.Assert.assertNotNull(findText(activity.window.decorView, "切到原车画面，点屏幕返回"))
        shoot(activity, "home-wwc2")
    }

    /** A favorite app using its own icon: the icon is the same size and position as the white unified glyphs. */
    @Test
    fun homeCustomAppIcon() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        Config(context).apply { setDock(listOf(DockItem("files", FakeApps.FILES), DockItem(null, FakeApps.NAVI))) }
        shoot(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "home-custom-app")
    }

    @Test
    fun library() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        shoot(Robolectric.buildActivity(LibraryActivity::class.java).setup().get(), "library")
    }

    /** Finds the View showing the given text in the view tree (takes the last match, consistent with the CI tap logic). */
    private fun findText(root: View, text: String): View? {
        var hit: View? = null
        fun walk(v: View) {
            if (v is TextView && v.text?.toString() == text) hit = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return hit
    }

    /** Finds the clickable parent View (tile, row) containing the text. */
    private fun clickableParent(v: View): View {
        var cur: View = v
        while (!cur.isClickable && cur.parent is View) cur = cur.parent as View
        return cur
    }

    private fun homeActivity(): MainActivity {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        Config(context).setWallpaper("stars")
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @Test
    fun tileMenuAndIconPicker() {
        val home = homeActivity()
        shadowOf(Looper.getMainLooper()).idle()
        clickableParent(findText(home.window.decorView, "系统设置")!!).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        shoot(home, "ui-tile-menu")
        clickableParent(findText(home.window.decorView, "更换图标")!!).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shoot(home, "ui-icon-picker")
    }

    private fun settings(section: String, name: String, qualifiers: String = "w1280dp-h480dp-land-mdpi") {
        RuntimeEnvironment.setQualifiers(qualifiers)
        FakeApps.installCar(context)
        Config(context).setWallpaper("city")
        val intent = Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, section)
        shoot(Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get(), name)
    }

    @Test fun settingsWallpaper() = settings(SettingsActivity.SECTION_WALLPAPER, "ui-settings-wallpaper")
    @Test fun settingsWallpaper1024() =
        settings(SettingsActivity.SECTION_WALLPAPER, "ui-settings-wallpaper-1024x600", "w1024dp-h600dp-land-mdpi")
    @Test fun settingsWallpaper1920() =
        settings(SettingsActivity.SECTION_WALLPAPER, "ui-settings-wallpaper-1920x720", "w1280dp-h480dp-land-hdpi")
    @Test fun settingsCarPlay() = settings(SettingsActivity.SECTION_CARPLAY, "ui-settings-carplay")
    @Test fun settingsAdvanced() = settings(SettingsActivity.SECTION_ADVANCED, "ui-settings-advanced")
    @Test fun settingsSignal() = settings(SettingsActivity.SECTION_SIGNAL, "ui-settings-signal")

    @Test
    fun confirmCard() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        val intent = Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_ADVANCED)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        clickableParent(findText(activity.window.decorView, "恢复默认布局")!!).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shoot(activity, "ui-confirm")
    }

    /** Pretends a new version has already been found (no network). */
    private fun fakeNewRelease() {
        val json = Updates.Release(9999, "1.0.9999", "- 修好了长按菜单遮挡磁贴\n- 新增：检查 GitHub 更新\n- 方向键焦点从 CarPlay 开始",
            "http://x/q5home.apk", 1_010_000, null, "2026-10-30T08:00:00Z").toJson().toString()
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("latest", json).putLong("checked_at", System.currentTimeMillis()).commit()
    }

    @Test
    fun settingsUpdate() {
        fakeNewRelease()
        settings(SettingsActivity.SECTION_UPDATE, "ui-settings-update")
    }

    @Test
    fun homeWithUpdateBadge() {
        fakeNewRelease()
        home("w1280dp-h480dp-land-mdpi", "home-update-badge")
    }

    /** Head-unit recon: waits for the background recon to finish. */
    @Test
    fun settingsRecon() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        val intent = Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_RECON)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        shoot(activity, "ui-settings-recon")
        // Also save a tall image of the whole page (inside the ScrollView) to see the QR code below.
        val scroll = generateSequence(listOf<View>(activity.window.decorView)) { level ->
            level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } else emptyList() }.ifEmpty { null }
        }.flatten().first { it is android.widget.ScrollView } as ViewGroup
        val content = scroll.getChildAt(0)
        val bmp = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        content.draw(Canvas(bmp))
        File(outDir, "ui-settings-recon-full.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun safeMode() {
        RuntimeEnvironment.setQualifiers("w1280dp-h480dp-land-mdpi")
        FakeApps.installCar(context)
        val now = System.currentTimeMillis()
        repeat(3) { CrashGuard.record(context, IllegalStateException("crash_test：故意崩溃，用来测试安全模式"), now) }
        shoot(Robolectric.buildActivity(SafeModeActivity::class.java).setup().get(), "safe-mode")
    }
}
