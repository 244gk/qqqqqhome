package com.x0finch.q5home

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** Minimal HTTP server for tests: returns (status code, body) per path and records the requests it receives. */
class TestHttpServer(private val handler: (path: String) -> Pair<Int, ByteArray>) : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val base = "http://127.0.0.1:${server.localPort}"
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val input = s.getInputStream().buffered()
                    val parts = readLine(input)?.split(" ") ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                    val path = parts.getOrNull(1) ?: return@use
                    requests += Request(parts[0], path, headers, String(body, Charsets.UTF_8))
                    val (code, response) = handler(path)
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 $code X\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(response)
                    out.flush()
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    override fun close() = server.close()
}
