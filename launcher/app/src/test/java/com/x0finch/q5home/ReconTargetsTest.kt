package com.x0finch.q5home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC
import java.io.File

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
    fun knownListParsesAndRepoFileIsValid() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "config/recon-known.json") }.first { it.exists() }
        val k = ReconKnown.parse(repo.readText(), "repo")!!
        assertTrue(k.versions.size >= 30 && k.strings.isNotEmpty() && k.methods.isNotEmpty())
        assertTrue(k.ignoreEvents.any { it.pattern.containsMatchIn("android.intent.action.SIG_STR · x") })
        // The recorder's built-in list matches the repo file (change one, change the other).
        assertEquals(k.ignoreEvents.map { it.name to it.pattern.pattern }, ReconKnown.DEFAULT_IGNORE.map { it.name to it.pattern.pattern })
        assertNull(ReconKnown.parse("nope", "t"))
        TestHttpServer { 404 to ByteArray(0) }.use { s -> assertNull(ReconKnown.load(s.base + "/k.json").source) }
        // Offline: the copy kept from the last download is used.
        val cache = File.createTempFile("known", ".json")
        TestHttpServer { 200 to """{"system":"cached"}""".toByteArray() }.use { s -> ReconKnown.load(s.base + "/k.json", cache) }
        TestHttpServer { 500 to ByteArray(0) }.use { s ->
            val k2 = ReconKnown.load(s.base + "/k.json", cache)
            assertEquals("cached", k2.system)
            assertTrue(k2.source!!.contains("这次没下载到"))
        }
        TestHttpServer { 200 to """{"system":"x","versions":{"a.b":"1"}}""".toByteArray() }.use { s ->
            val loaded = ReconKnown.load(s.base + "/k.json")
            assertEquals("x", loaded.system)
            assertEquals(mapOf("a.b" to "1"), loaded.versions)
        }
    }
}
