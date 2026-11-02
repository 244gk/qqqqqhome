package com.x0finch.q5home

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RC
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class ReconTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Installs a fake vendor app with exported activities / broadcast receivers. */
    private fun installPackage(pkg: String, label: String, activities: List<String>, receivers: List<String> = emptyList(),
                               enabled: Boolean = true, apk: java.io.File? = null) {
        val appInfo = ApplicationInfo().apply {
            packageName = pkg; nonLocalizedLabel = label; flags = ApplicationInfo.FLAG_SYSTEM; this.enabled = enabled
            sourceDir = apk?.path
        }
        fun comp(name: String) = ActivityInfo().apply {
            packageName = pkg; this.name = name; exported = true; applicationInfo = appInfo
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "1.0"; applicationInfo = appInfo
            this.activities = activities.map(::comp).toTypedArray()
            this.receivers = receivers.map(::comp).toTypedArray()
        })
    }

    @Test
    fun findsCandidatesAndWritesReport() {
        FakeApps.installCar(app)
        installPackage("com.zjinnova.zlink", "ZLINK", listOf("com.zjinnova.zlink.MainActivity"))
        installPackage("com.wy.oem", "原车", listOf("com.wy.oem.MainActivity"), listOf("com.wy.oem.SwitchReceiver"))
        installPackage("com.wy.factory", "工厂设置", listOf("com.wy.factory.Main", "com.wy.factory.UsbModeActivity"))
        val mainApk = ApkStringsTest.fakeApk(java.io.File.createTempFile("main", ".apk"),
            listOf("com.wwc2.main.ACTION_ENTER_OEM", "boring"))
        installPackage("com.wwc2.main", "main", listOf("com.wwc2.main.MainActivity"), apk = mainApk)
        installPackage("com.wy.junk", "广告", listOf("com.wy.junk.AdActivity"), enabled = false)
        installPackage("com.android.settings", "设置", listOf(
            "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity", "com.android.settings.Settings\$WifiSettingsActivity"))

        val report = Recon.collect(app, ports = emptyList())
        val kinds = report.candidates.associate { it.spec to it.kind }
        assertEquals("CarPlay", kinds[FakeApps.ZLINK])
        assertEquals("原车", kinds[FakeApps.OEM])
        assertEquals("调试", kinds["com.wy.factory/com.wy.factory.UsbModeActivity"])
        assertEquals("调试", kinds["com.android.settings/com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"])
        assertTrue("WiFi settings is not a debug page", kinds.keys.none { it.contains("WifiSettings") })

        val md = report.markdown
        assertTrue(md.contains("### 原车 `com.wy.oem`"))
        assertTrue(md.contains("- 广播：`com.wy.oem.SwitchReceiver`"))
        assertTrue(md.contains("广告 `com.wy.junk`") && md.contains("已停用"))
        assertTrue(md.contains("## 调试 / root 线索"))
        // APK strings of key vendor apps (com.wwc2.main is on the dig list).
        assertTrue(md.contains("### `com.wwc2.main`"))
        assertTrue(md.contains("  - `com.wwc2.main.ACTION_ENTER_OEM`"))
        assertTrue(md.contains("- 页面：`com.wwc2.main.MainActivity`"))
        assertTrue(!md.contains("boring"))
        assertTrue(report.facts.any { it.first == "adb" })
    }

    /** Fake WWC2 CanbusProvider: original_screen returns one row. */
    class FakeCanbusProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) =
            if (uri.lastPathSegment == "original_screen")
                android.database.MatrixCursor(arrayOf("name", "value")).apply { addRow(arrayOf<Any>("original_screen", 1)) }
            else null
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun readsVendorContentProviders() {
        org.robolectric.Robolectric.setupContentProvider(FakeCanbusProvider::class.java, "com.wwc2.canmcusdk.CanbusProvider")
        val apk = ApkStringsTest.fakeApk(java.io.File.createTempFile("launcher", ".apk"),
            listOf("content://com.wwc2.canmcusdk.CanbusProvider/original_screen"))
        installPackage("com.wwc2.canmcusdk", "CanMcuSDK", listOf("com.wwc2.canmcusdk.MainActivity"), apk = apk)
        val md = Recon.collect(app, ports = emptyList()).markdown
        assertTrue(md.contains("## 厂商数据接口试读"))
        assertTrue(md, md.contains("`content://com.wwc2.canmcusdk.CanbusProvider/original_screen`：1 行，列 name, value"))
        assertTrue(md.contains("name=original_screen, value=1"))
        // OEM-related code: build an APK from a real dex (OemSwitch.enterOriginalMedia); the report must list its call sequence.
        val dexApk = java.io.File.createTempFile("oem-switch", ".apk")
        java.util.zip.ZipOutputStream(dexApk.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
            zip.write(javaClass.classLoader!!.getResourceAsStream("oem-switch.dex")!!.readBytes())
            zip.closeEntry()
        }
        installPackage("com.wwc2.main", "main", listOf("com.wwc2.main.MainActivity"), apk = dexApk)
        val md2 = Recon.collect(app, ports = emptyList()).markdown
        assertTrue(md2.contains("## 原车相关代码（只读反汇编）"))
        assertTrue(md2, md2.contains("- `com.wwc2.fake.OemSwitch.enterOriginalMedia`"))
        assertTrue(md2.contains("`str \"original_screen\"` → `call Map.put`"))
    }

    @Test
    fun localShareServesReportsReadOnly() {
        var full: String? = null
        val share = LocalShare(report = { "# 报告 <b>" }, full = { full }, port = 0)
        try {
            val base = "http://127.0.0.1:${share.port}"
            fun get(path: String): Pair<Int, String> {
                val c = URL(base + path).openConnection() as HttpURLConnection
                val code = c.responseCode
                return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
            }
            assertEquals(404, get("/").first)
            assertEquals(404, get("/wrong/").first)
            val (code, page) = get("/${share.secret}/")
            assertEquals(200, code)
            assertTrue("report must be escaped", page.contains("# 报告 &lt;b&gt;"))
            assertTrue("no token / upload form any more", !page.contains("<form") && !page.contains("令牌"))
            assertEquals("# 报告 <b>", get("/${share.secret}/report.md").second)
            // The full report only appears once it has been generated.
            assertEquals(404, get("/${share.secret}/full.md").first)
            full = "# 完整"
            assertEquals("# 完整", get("/${share.secret}/full.md").second)
            assertTrue(get("/${share.secret}/").second.contains("完整报告"))
            // Nothing accepts uploads.
            val c = URL("$base/${share.secret}/token").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write("token=x".toByteArray()) }
            assertEquals(404, c.responseCode)
        } finally {
            share.close()
        }
    }
}
