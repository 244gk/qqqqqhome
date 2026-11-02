package com.x0finch.q5home

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Temporary web page on the head unit: opened on a phone by scanning a QR code; it shows the recon report to copy or download
 * (the owner just sends the copied text). Read-only: nothing is uploaded anywhere and no token is ever involved. Runs only while the "车机摸底" (recon) settings page is open and closes itself after [TIMEOUT_MS];
 * the path contains a random string so other devices on the same network can't guess it.
 *
 * [report] returns the focused report Markdown (null while it is still being generated); [full] the full report, if one was
 * generated on the recon page.
 */
class LocalShare(
    private val report: () -> String?,
    private val full: () -> String? = { null },
    port: Int = DEFAULT_PORT,
) {
    private val server: ServerSocket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }
        .getOrElse { ServerSocket(0) } // if the port is taken, pick any free one
    val port: Int get() = server.localPort
    val secret: String = randomSecret()
    private val startedAt = System.currentTimeMillis()

    @Volatile var closed = false; private set

    init {
        thread(isDaemon = true, name = "q5-share") {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) { socket.close(); close(); break }
                thread(isDaemon = true) { socket.use { handle(it) } }
            }
        }
    }

    fun url(ip: String) = "http://$ip:$port/$secret/"

    fun close() {
        closed = true
        runCatching { server.close() }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val (method, path) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var length = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = ByteArray(length.coerceIn(0, MAX_BODY))
        var read = 0
        while (read < body.size) { val n = input.read(body, read, body.size - read); if (n < 0) break; read += n }

        val base = "/$secret/"
        val (code, type, content) = when {
            method == "GET" && path == base -> Triple(200, HTML, page())
            method == "GET" && path == "${base}report.md" ->
                report()?.let { Triple(200, "text/markdown; charset=utf-8", it) } ?: Triple(503, TEXT, "报告还在生成，稍后刷新")
            method == "GET" && path == "${base}full.md" ->
                full()?.let { Triple(200, "text/markdown; charset=utf-8", it) } ?: Triple(404, TEXT, "没有生成完整报告")
            else -> Triple(404, TEXT, "没有这个页面")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    /**
     * The page: the focused report (copy all or download — sending the copied text is enough), then the full report if one was
     * generated. While the focused report is still being generated the page refreshes itself every 5 s.
     */
    private fun page(): String {
        val md = report()
        val fullMd = full()
        val refresh = if (md == null) "<meta http-equiv=\"refresh\" content=\"5\">" else ""
        fun section(id: String, file: String, text: String) = """
              <button onclick="var t=document.getElementById('$id');t.select();document.execCommand('copy');this.textContent='已复制'">复制全部</button>
              <a class="btn sec" href="$file" download="$file">下载 $file</a>
              <p><textarea id="$id" readonly>${esc(text)}</textarea></p>"""
        return """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            $refresh
            <title>q5home 车机摸底</title>
            <style>
              body{margin:0;padding:16px;background:#0f0f11;color:#eee;font:16px/1.5 -apple-system,system-ui,sans-serif}
              h1{font-weight:300;font-size:26px;margin:8px 0 4px} h2{font-size:17px;margin:24px 0 8px}
              .card{background:#1c1c1f;border:1px solid #333;border-radius:16px;padding:16px;margin:12px 0}
              button,.btn{display:inline-block;margin-top:12px;margin-right:8px;padding:12px 20px;border:0;border-radius:24px;background:#6e7dff;color:#fff;font-size:16px;font-weight:600;text-decoration:none}
              .sec{background:#333;color:#fff} small{color:#999}
              textarea{width:100%;box-sizing:border-box;height:45vh;background:#000;color:#ccc;border:1px solid #333;border-radius:12px;font:12px/1.4 ui-monospace,monospace}
            </style></head><body>
            <h1>q5home 车机摸底</h1><small>车机上的临时网页，离开摸底页面或 15 分钟后关闭。只读，不会改动车机，也不会上传到任何地方。</small>
            <h2>重点报告</h2>
            <div class="card">
              ${if (md == null) "正在生成重点报告（几十秒），本页会自动刷新…" else section("r", "report.md", md) +
                "<p><small>点「复制全部」，直接发出去就行。</small></p>"}
            </div>
            ${if (fullMd == null) "" else "<h2>完整报告</h2><div class=\"card\">" + section("f", "full.md", fullMd) + "</div>"}
            </body></html>
        """.trimIndent()
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val TIMEOUT_MS = 15 * 60_000L
        private const val MAX_BODY = 16 * 1024
        private const val HTML = "text/html; charset=utf-8"
        private const val TEXT = "text/plain; charset=utf-8"

        private fun randomSecret(): String {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
                if (buf.size() > 8192) return null
                buf.write(b)
            }
        }
    }
}
