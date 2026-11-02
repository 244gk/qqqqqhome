package com.x0finch.q5home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RC
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@RC(sdk = [31])
class UpdatesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun releaseJson(base: String, tag: String, digest: String? = "sha256:$sha", withApk: Boolean = true): String {
        val asset = if (withApk) """{"name":"q5home-$tag.apk","size":${apk.size},
            "browser_download_url":"$base/download/q5home.apk"${digest?.let { ""","digest":"$it"""" } ?: ""}}""" else
            """{"name":"notes.txt","size":1,"browser_download_url":"$base/x"}"""
        return """{"tag_name":"$tag","body":"- 修好了菜单遮挡\n- 新增检查更新","published_at":"2026-10-30T08:00:00Z",
            "assets":[$asset]}"""
    }

    @Test
    fun parsesTagAndAsset() {
        val r = Updates.parse(releaseJson("http://x", "v1.0.57"))!!
        assertEquals(570L, r.versionCode)
        assertEquals(571L, Updates.parse(releaseJson("http://x", "v1.0.57.1"))!!.versionCode)
        // A rollback release sorts after the normal release with the same commit count and before the next normal release
        assertTrue(Updates.versionCodeOf("v1.0.57.1")!! < Updates.versionCodeOf("v1.0.58")!!)
        assertEquals("1.0.57", r.versionName)
        assertEquals(sha, r.sha256)
        assertTrue(r.notes.contains("检查更新"))
        assertNull(Updates.parse(releaseJson("http://x", "v1.0.58", withApk = false)))
    }

    @Test
    fun checkDetectsNewerVersionAndDownloadVerifiesSha() {
        TestHttpServer { path ->
            when {
                path.startsWith("/latest") -> 200 to "".toByteArray()
                path.startsWith("/download/") -> 200 to apk
                else -> 404 to ByteArray(0)
            }
        }.use { server ->
            // First replace the /latest content with a real release JSON
            TestHttpServer { path ->
                if (path.startsWith("/latest")) 200 to releaseJson(server.base, "v1.0.9999").toByteArray()
                else 404 to ByteArray(0)
            }.use { api ->
                val updates = Updates(context, "${api.base}/latest")
                val latest = updates.check()!!
                assertTrue(updates.hasUpdate)
                assertEquals(99990L, updates.latest!!.versionCode)
                assertNull(updates.lastError)

                var lastProgress = -1
                val file = updates.download(latest) { lastProgress = it }
                assertEquals(100, lastProgress)
                assertEquals(apk.size.toLong(), file.length())
                // This file is not a real APK: the pre-install check must reject it.
                assertNotNull(updates.verify(file))
            }
        }
    }

    @Test
    fun shaMismatchIsRejected() {
        TestHttpServer { path -> if (path.startsWith("/download/")) 200 to apk else 404 to ByteArray(0) }.use { server ->
            val release = Updates.parse(releaseJson(server.base, "v1.0.9999", digest = "sha256:" + "0".repeat(64)))!!
            val result = runCatching { Updates(context).download(release) }
            assertTrue(result.exceptionOrNull() is IOException)
            assertFalse(java.io.File(context.cacheDir, "updates/q5home-1.0.9999.apk").exists())
        }
    }

    @Test
    fun noReleaseYetAndErrorsAreRecorded() {
        TestHttpServer { 404 to ByteArray(0) }.use { api ->
            val updates = Updates(context, "${api.base}/latest")
            assertNull(updates.check())
            assertFalse(updates.hasUpdate)
            assertTrue(updates.lastCheckedAt > 0)
        }
        TestHttpServer { 500 to ByteArray(0) }.use { api ->
            val updates = Updates(context, "${api.base}/latest")
            assertTrue(runCatching { updates.check() }.isFailure)
            assertNotNull(updates.lastError)
        }
    }
}
