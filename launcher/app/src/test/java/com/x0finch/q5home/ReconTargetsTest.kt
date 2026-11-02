package com.x0finch.q5home

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RC
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class ReconTargetsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultMatchesRepoFile() {
        // The built-in copy must match config/recon-targets.json in the repo (change one, change the other).
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "config/recon-targets.json") }.first { it.exists() }
        val fromRepo = ReconTargets.parse(repo.readText(), "repo")!!
        val builtin = ReconTargets.DEFAULT
        assertEquals(fromRepo.packages, builtin.packages)
        assertEquals(fromRepo.code.map { it.name to it.pattern.pattern }, builtin.code.map { it.name to it.pattern.pattern })
        assertEquals(fromRepo.strings?.pattern, builtin.strings?.pattern)
        assertEquals(fromRepo.providers, builtin.providers)
    }

    @Test
    fun parseIsDefensive() {
        val t = ReconTargets.parse("""{"packages":["com.ok","bad pkg!"],
            "code":[{"name":"坏的","pattern":"(["},{"name":"好的","pattern":"a|b"}],
            "providers":["content://com.wwc2.x/y","content://evil/z"],"maxMethods":999}""", "t")!!
        assertEquals(listOf("com.ok"), t.packages)
        assertEquals(listOf("好的"), t.code.map { it.name })
        assertEquals(listOf("content://com.wwc2.x/y"), t.providers)
        assertEquals(40, t.maxMethods)
        assertNull(t.strings)
        assertNull(ReconTargets.parse("not json", "t"))
    }

    @Test
    fun loadsFromRepoOrFallsBack() {
        TestHttpServer { 200 to """{"packages":["com.remote"],"code":[]}""".toByteArray() }.use { s ->
            val t = ReconTargets.load(s.base + "/t.json")
            assertEquals(listOf("com.remote"), t.packages)
            assertTrue(t.source.startsWith("仓库"))
        }
        TestHttpServer { 404 to ByteArray(0) }.use { s ->
            assertEquals("内置", ReconTargets.load(s.base + "/t.json").source)
        }
    }

    @Test
    fun focusedReportShowsOnlyTargets() {
        Robolectric.setupContentProvider(ReconTest.FakeCanbusProvider::class.java, "com.wwc2.canmcusdk.CanbusProvider")
        val apk = File.createTempFile("main", ".apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(javaClass.classLoader!!.getResourceAsStream("oem-switch.dex")!!.readBytes())
            zip.closeEntry()
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.wwc2.main"; versionName = "8.4.154"
            applicationInfo = ApplicationInfo().apply { packageName = "com.wwc2.main"; sourceDir = apk.path }
        })
        val t = ReconTargets.parse("""{"packages":["com.wwc2.main","com.not.installed"],
            "code":[{"name":"原车画面切换","pattern":"original_screen"}],
            "strings":"original",
            "providers":["content://com.wwc2.canmcusdk.CanbusProvider/original_screen"]}""", "测试")!!
        val md = Recon.focused(app, t).markdown
        assertTrue(md.contains("**重点报告**"))
        assertTrue(md.contains("| `com.wwc2.main` | 8.4.154"))
        assertTrue(md.contains("| `com.not.installed` | 未安装 |"))
        assertTrue(md.contains("## 原车画面切换（只读反汇编）"))
        assertTrue(md, md.contains("- `com.wwc2.fake.OemSwitch.enterOriginalMedia`"))
        assertTrue(md.contains("`content://com.wwc2.canmcusdk.CanbusProvider/original_screen`"))
        assertTrue(md.contains("name=original_screen, value=1"))
        assertTrue(md.contains("## 相关字符串"))
        // No more package list, system properties and the like.
        assertTrue(!md.contains("## 厂商应用") && !md.contains("ro.build.type"))
    }
}
