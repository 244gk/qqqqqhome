package com.x0finch.q5home

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The recon event log: one line per event in q5home's own private file. Lines are collected in memory and written in batches
 * (every [FLUSH_MS], or at [FLUSH_LINES] lines, or right away for important lines such as a process start or the screen going off),
 * so the file is not written on every event. Capped at [MAX_BYTES]; past that the oldest part is dropped (keeps the newest [KEEP_BYTES]).
 * Read-only towards the head unit: this only ever writes q5home's own file.
 *
 * Line format: `2026-11-02 09:35:12.345  类型  内容`.
 */
object EventLog {
    const val MAX_BYTES = 2L * 1024 * 1024
    const val KEEP_BYTES = 1L * 1024 * 1024
    const val FLUSH_MS = 30_000L
    const val FLUSH_LINES = 100
    private const val MAX_LINE = 600
    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val pending = StringBuilder()
    private var pendingLines = 0
    private var pendingFile: File? = null
    private var lastFlush = 0L

    fun file(context: Context) = File(File(context.filesDir, "recon"), "events.log")

    /** Adds a line; it reaches the file with the next batch, or immediately with [now] = true. */
    @Synchronized
    fun append(context: Context, kind: String, text: String, nowMs: Long = TrueTime.now(), now: Boolean = false) {
        val f = file(context)
        if (pendingFile != null && pendingFile != f) flushLocked()
        pendingFile = f
        if (lastFlush == 0L) lastFlush = System.currentTimeMillis()
        pending.append(format.get()!!.format(Date(nowMs))).append("  ").append(kind).append("  ")
            .append(text.replace('\n', ' ').take(MAX_LINE)).append('\n')
        pendingLines++
        if (now || pendingLines >= FLUSH_LINES || System.currentTimeMillis() - lastFlush >= FLUSH_MS) flushLocked()
    }

    /** Writes what is collected so far (called on a timer by the recorder, and before reading). */
    @Synchronized
    fun flush() = flushLocked()

    private fun flushLocked() {
        lastFlush = System.currentTimeMillis()
        val f = pendingFile ?: return
        if (pendingLines == 0) return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(pending.toString(), Charsets.UTF_8)
            if (f.length() > MAX_BYTES) trim(f)
        }
        pending.setLength(0)
        pendingLines = 0
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

    /**
     * All lines, in the order they were written; with [afterLine], only the lines after its last occurrence (the last line when the
     * owner copied a report: robust to the clock jumping), else with [sinceMs], only lines at or after that time.
     */
    @Synchronized
    fun lines(context: Context, sinceMs: Long? = null, afterLine: String? = null): List<String> {
        flushLocked()
        val all = runCatching { file(context).readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        if (afterLine != null) {
            val i = all.lastIndexOf(afterLine)
            if (i >= 0) return all.subList(i + 1, all.size)
        }
        if (sinceMs == null) return all
        return all.filter { (timeOf(it) ?: 0L) >= sinceMs }
    }

    /** When a line was written; lines stamped while the clock was 1024 weeks behind are corrected ([TrueTime]). */
    fun timeOf(line: String): Long? = runCatching { format.get()!!.parse(line.take(23))?.time }.getOrNull()?.let(TrueTime::correct)

    @Synchronized
    fun clear(context: Context) {
        pending.setLength(0)
        pendingLines = 0
        runCatching { file(context).delete() }
    }
}
