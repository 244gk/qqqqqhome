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

    /** Fake WWC2 CanbusProvider: original_screen returns one row. */
    class FakeCanbusProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) =
            if (uri.lastPathSegment == "original_screen")
                android.database.MatrixCursor(arrayOf("name", "value")).apply { addRow(arrayOf<Any>("original_screen", 1)) }
            else null
        override fun getType(uri: android.net.Uri): String? = null
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? =
            if (method == "car_info") android.os.Bundle().apply { putInt("speed", 42); putIntArray("doors", intArrayOf(0, 1)) } else null
        override fun insert(uri: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private fun dexApk(resource: String): java.io.File {
        val apk = java.io.File.createTempFile("dex", ".apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
            zip.write(javaClass.classLoader!!.getResourceAsStream(resource)!!.readBytes())
            zip.closeEntry()
        }
        return apk
    }

    /** The one report: only what is not already known, the event log since the last copy, and the read-only code analysis. */
    @Test
    fun reportShowsOnlyNewThings() {
        org.robolectric.Robolectric.setupContentProvider(FakeCanbusProvider::class.java, "com.wwc2.canmcusdk.CanbusProvider")
        installPackage("com.wwc2.main", "main", listOf("com.wwc2.main.MainActivity"), apk = dexApk("oem-switch.dex"))
        installPackage("com.wwc2.canmcusdk", "CanMcuSDK", listOf("com.wwc2.canmcusdk.MainActivity"), apk = dexApk("recon-extra.dex"))
        installPackage("com.wy.same", "没变", listOf("com.wy.same.Main"))
        EventLog.clear(app)
        val t0 = 1_762_000_000_000L
        EventLog.append(app, "广播", "com.wwc2.OLD", t0)
        EventLog.append(app, "广播", "android.intent.action.SIG_STR · x=1", t0 + 20_000)
        EventLog.append(app, "广播", "com.wwc2.LATE", t0 + 40_000)
        EventLog.append(app, "广播", "com.wwc2.APK_TO_MAIN · cmd=7", t0 + 30_000)
        val targets = ReconTargets.parse("""{"packages":["com.wwc2.main","com.wwc2.canmcusdk"],
            "code":[{"name":"原车画面切换","pattern":"original_screen|switchOriginalMenu"}],
            "strings":"original|ACTION_SWITCH",
            "providers":["content://com.wwc2.canmcusdk.CanbusProvider/original_screen","content://com.wwc2.canmcusdk.CanbusProvider/car_info"],
            "calls":["content://com.wwc2.canmcusdk.CanbusProvider/car_info#car_info","content://com.wwc2.canmcusdk.CanbusProvider/x#write_all"],
            "vendorClasses":"^com\\.wwc2\\.","settings":"zzz_none",
            "sites":[{"name":"发广播的地方","call":"\\.sendBroadcast$"}],
            "constants":"AvinInterface"}""", "测试")!!
        val known = ReconKnown.parse("""{"system":"${android.os.Build.DISPLAY}",
            "versions":{"com.wwc2.main":"1.0","com.wy.same":"1.0","com.wwc2.canmcusdk":"0.9","com.wy.gone":"2.0"},
            "methods":["com.wwc2.fake.OemSwitch.enterOriginalMedia"],
            "providers":["content://com.wwc2.canmcusdk.CanbusProvider/car_info"],
            "strings":["enter_original_media"],
            "ignoreEvents":[{"name":"手机信号","pattern":"SIG_STR"}]}""", "测试")!!
        val md = Recon.report(app, targets, known, eventsSinceMs = t0 + 10_000).markdown

        // Changes only: the updated and the removed app; unchanged apps and the system are not repeated.
        assertTrue(md, md.contains("- 应用更新：`com.wwc2.canmcusdk` 0.9 → 1.0"))
        assertTrue(md.contains("- 应用没了（卸载或停用后不可见）：`com.wy.gone`"))
        assertTrue(md.contains("### `com.wwc2.canmcusdk` 1.0"))
        assertTrue(!md.contains("com.wy.same") && !md.contains("系统版本："))
        // Events since the last copy; known noise only counted.
        assertTrue(md.contains("com.wwc2.APK_TO_MAIN · cmd=7"))
        assertTrue(!md.contains("com.wwc2.OLD") && !md.contains("SIG_STR"))
        assertTrue(md.contains("已知、省略：手机信号 1 条"))
        // Known method left out, new ones listed (found by the called method's name).
        assertTrue(!md.contains("- `com.wwc2.fake.OemSwitch.enterOriginalMedia`"))
        assertTrue(md.contains("- `com.wwc2.fake.Tap.onOriginalClick`"))
        assertTrue(md.contains("（另有 1 个已知方法省略）"))
        assertTrue(md.contains("- `com.wwc2.canmcusdk` `com.wwc2.fake.Tap.onOriginalClick` → `sendBroadcast`：`com.wwc2.main.ACTION_SWITCH_SOURCE` `source`"))
        assertTrue(md.contains("`SWITCH_ORIGINAL=33 (0x21)`"))
        // Known strings and known provider query results are not repeated; getType is always tried.
        assertTrue(!md.contains("`enter_original_media`"))
        assertTrue(md, md.contains("`content://com.wwc2.canmcusdk.CanbusProvider/original_screen`：query：1 行，列 name, value"))
        assertTrue(md.contains("`content://com.wwc2.canmcusdk.CanbusProvider/car_info`：query：返回空"))
        // Vehicle data through the read-only "xxx_info" call; other method names are refused.
        assertTrue(md, md.contains("- `car_info`：2 项：doors=[0, 1], speed=42"))
        assertEquals("不允许的方法", Recon.callProvider(app, "content://com.wwc2.canmcusdk.CanbusProvider/car_info", "set_info2"))
        // A line seen before (same text) is left out; events come out in time order.
        val sorted = md.substringAfter("## 后台事件记录").substringBefore("```\n\n")
        assertTrue(sorted.indexOf("com.wwc2.APK_TO_MAIN") < sorted.indexOf("com.wwc2.LATE"))
        // Nothing of the old full report.
        assertTrue(!md.contains("## 厂商应用") && !md.contains("ro.build.type") && !md.contains("## AOSP"))

        // Without the known list everything counts as new, and the report says so.
        val all = Recon.report(app, targets, ReconKnown.NONE, null).markdown
        assertTrue(all.contains("已知列表没下载到"))
        assertTrue(all.contains("- `com.wwc2.fake.OemSwitch.enterOriginalMedia`") && all.contains("com.wwc2.OLD"))
        EventLog.clear(app)
    }

    /** Before the background preparation has run, the report still shows the cheap parts and says the code analysis is coming. */
    @Test
    fun reportBeforeBackgroundPreparation() {
        val md = ReconReport.build(app, null).markdown
        assertTrue(md.contains("## 和上次相比的变化"))
        assertTrue(md.contains("还在后台准备"))
    }

    @Test
    fun localShareServesReportsReadOnly() {
        var copied = 0
        var md: String? = null
        val share = LocalShare(report = { md }, onCopied = { copied++ }, port = 0)
        try {
            val base = "http://127.0.0.1:${share.port}"
            fun get(path: String): Pair<Int, String> {
                val c = URL(base + path).openConnection() as HttpURLConnection
                val code = c.responseCode
                return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
            }
            assertEquals(404, get("/").first)
            assertEquals(404, get("/wrong/").first)
            // While generating: refreshes itself, report.md not ready yet.
            assertTrue(get("/${share.secret}/").second.contains("http-equiv=\"refresh\""))
            assertEquals(503, get("/${share.secret}/report.md").first)
            md = "# 报告 <b>"
            val (code, page) = get("/${share.secret}/")
            assertEquals(200, code)
            assertTrue("report must be escaped", page.contains("# 报告 &lt;b&gt;"))
            assertTrue("one report only", !page.contains("完整报告") && !page.contains("重点报告"))
            assertTrue("no token / upload form any more", !page.contains("<form") && !page.contains("令牌"))
            assertEquals("# 报告 <b>", get("/${share.secret}/report.md").second)
            // Copying tells the app, so the next report's event log continues from here.
            assertEquals(0, copied)
            assertEquals(200, get("/${share.secret}/copied").first)
            assertEquals(1, copied)
            assertEquals(404, get("/${share.secret}/full.md").first)
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
