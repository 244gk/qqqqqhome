package com.x0finch.q5home

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The recon event log: one line per event, written immediately to q5home's own private file so nothing is lost if the process
 * is killed. Capped at [MAX_BYTES]; when it grows past that the oldest part is dropped (keeps the newest [KEEP_BYTES]).
 * Read-only towards the head unit: this only ever writes q5home's own file.
 *
 * Line format: `2026-11-02 09:35:12.345  类型  内容`.
 */
object EventLog {
    const val MAX_BYTES = 2L * 1024 * 1024
    const val KEEP_BYTES = 1L * 1024 * 1024
    private const val MAX_LINE = 600
    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun file(context: Context) = File(File(context.filesDir, "recon"), "events.log")

    @Synchronized
    fun append(context: Context, kind: String, text: String, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val line = "${format.get()!!.format(Date(nowMs))}  $kind  ${text.replace('\n', ' ').take(MAX_LINE)}\n"
            f.appendText(line, Charsets.UTF_8)
            if (f.length() > MAX_BYTES) trim(f)
        }
    }

    /** Keeps the newest [KEEP_BYTES], starting at a line boundary. */
    private fun trim(f: File) {
        val bytes = RandomAccessFile(f, "r").use { raf ->
            val start = raf.length() - KEEP_BYTES
            raf.seek(start)
            ByteArray((raf.length() - start).toInt()).also { raf.readFully(it) }
        }
        val firstLine = bytes.indexOf('\n'.code.toByte()) + 1
        f.writeBytes(bytes.copyOfRange(firstLine, bytes.size))
    }

    /** All lines, oldest first; with [sinceMs], only lines at or after that time. */
    @Synchronized
    fun lines(context: Context, sinceMs: Long? = null): List<String> {
        val all = runCatching { file(context).readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        if (sinceMs == null) return all
        return all.filter { (timeOf(it) ?: 0L) >= sinceMs }
    }

    fun timeOf(line: String): Long? = runCatching { format.get()!!.parse(line.take(23))?.time }.getOrNull()

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Markdown for the web page: a short header and the lines in a code block. */
    fun markdown(context: Context, sinceMs: Long?, title: String): String {
        val lines = lines(context, sinceMs)
        val sb = StringBuilder()
        sb.appendLine("<!-- q5home-events v1 -->")
        sb.appendLine("## $title（${lines.size} 条）")
        sb.appendLine("q5home 在后台只听、只记，没有发送或改动任何东西。时间是车机时间。")
        sb.appendLine()
        sb.appendLine("```")
        lines.forEach { sb.appendLine(it) }
        sb.appendLine("```")
        return sb.toString()
    }
}
