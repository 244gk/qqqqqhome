package com.x0finch.q5home

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config as RC
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RC(sdk = [31])
class OnlineWallpapersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: ServerSocket
    @Volatile private var imageRequests = 0
    private var base = ""

    /** A real small image, so decoding is guaranteed to work. */
    private val image: ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(64, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** Minimal HTTP server: /feed.json returns a list of 5 photos, /img/... returns images. */
    private fun respond(path: String): Pair<Int, ByteArray> = when {
        path.startsWith("/feed.json") -> {
            val photos = (1..5).joinToString(",") { """{"id":"p$it","author":"Author $it","url":"$base/img/p$it"}""" }
            200 to """{"photos":[$photos]}""".toByteArray()
        }
        // Image URLs must carry the screen-size crop parameters.
        path.startsWith("/img/") && path.contains("w=1280") && path.contains("fm=webp") -> {
            imageRequests++
            200 to image
        }
        else -> 404 to ByteArray(0)
    }

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) { /* skip request headers */ }
                    val (code, body) = respond(path)
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        }
        File(context.getExternalFilesDir(null), Config.FILE).writeText("""{"wallpaperFeed": "$base/feed.json"}""")
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun downloadsNewestTwoPerRunAndSkipsExisting() {
        val config = Config(context).also { it.reload() }
        val store = OnlineWallpapers(context)
        assertEquals(2, store.update(config, 1280))
        assertEquals(listOf("p4", "p5"), store.list().map { it.id }.sorted())
        assertEquals(2, store.update(config, 1280))
        assertEquals(4, store.list().size)
        assertEquals(4, imageRequests)
        // Newly downloaded ones appear in the wallpaper list and can be decoded.
        val online = Wallpapers.available(context).filter { it.key.startsWith(OnlineWallpapers.KEY_PREFIX) }
        assertEquals(4, online.size)
        assertTrue(online.all { Wallpapers.decode(context, it, 1280) != null })
        assertTrue(online.first().name.startsWith("在线 · Author"))
    }

    @Test
    fun pruneKeepsNewestWithinLimits() {
        val config = Config(context).also { it.reload() }
        val store = OnlineWallpapers(context)
        store.update(config, 1280)
        store.update(config, 1280)
        store.prune(maxCount = 3)
        assertEquals(3, store.list().size)
        store.prune(maxBytes = image.size.toLong())
        assertEquals(1, store.list().size)
        // Only the index and the kept image remain on disk.
        assertEquals(2, store.dir.listFiles()!!.size)
        store.clear()
        assertEquals(0, store.list().size)
    }

    @Test
    fun offlineIsHarmless() {
        server.close()
        val config = Config(context).also { it.reload() }
        val result = runCatching { OnlineWallpapers(context).update(config, 1280) }
        // When the network is down, throw so JobScheduler retries, and leave no partial files behind.
        assertTrue(result.isFailure)
        assertEquals(0, OnlineWallpapers(context).list().size)
    }

    @Test
    fun imageUrlAppendsResizeParams() {
        assertEquals(
            "https://x/photo-1?w=1920&h=720&fit=crop&crop=entropy&fm=webp&q=60",
            OnlineWallpapers.imageUrl("https://x/photo-1", 1920, 720),
        )
        assertTrue(OnlineWallpapers.imageUrl("https://x/raw?ixid=1", 1280, 480).contains("ixid=1&w=1280"))
    }
}
