package com.x0finch.q5home

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Prepares the recon report in the background, so scanning the QR code shows it right away:
 *  - a couple of minutes after q5home starts (the head unit starting) or the ignition comes on, it downloads the two config files
 *    (offline: the copies kept from last time), and runs the slow part of the report (reading and disassembling the vendor apps,
 *    [Recon.staticSections]) on a low-priority thread;
 *  - the result is kept in q5home's own files and only redone when the configs, the vendor apps or q5home change;
 *  - showing the report ([build]) then only adds the cheap parts (changes, vehicle data, event log, properties, settings).
 * Read-only like the rest of recon: it only writes q5home's own files.
 */
object ReconReport {
    const val START_DELAY_MS = 120_000L

    enum class State { IDLE, WORKING, READY, FAILED }

    /** Held while reading the large vendor APKs (here and in the recorder's discovery), so two such reads never run at once. */
    val HEAVY = Any()

    @Volatile var state = State.IDLE; private set
    /** When the code analysis was last prepared (0 = never). */
    @Volatile var readyAt = 0L; private set

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "q5-report").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    private fun dir(context: Context) = File(context.filesDir, "recon")
    private fun staticFile(context: Context) = File(dir(context), "report-static.md")
    private fun keyFile(context: Context) = File(dir(context), "report-static.key")
    private fun propsFile(context: Context) = File(dir(context), "props.txt")

    /** Prepares the report after [delayMs] (a pending run is kept rather than pushed back). */
    @Synchronized
    fun schedule(context: Context, delayMs: Long = START_DELAY_MS) {
        if (pending?.isDone == false) return
        val app = context.applicationContext
        pending = executor.schedule({ prepare(app) }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Runs [prepare] now (queued behind a run already going) and waits for it. */
    fun prepareNow(context: Context) {
        val app = context.applicationContext
        runCatching { executor.submit { prepare(app) }.get(10, TimeUnit.MINUTES) }
    }

    /** Downloads the configs (or uses the cached ones) and redoes the code analysis if anything it depends on changed. */
    fun prepare(context: Context) {
        state = State.WORKING
        runCatching {
            val targets = ReconTargets.load(cache = ReconTargets.cacheFile(context))
            val known = ReconKnown.load(cache = ReconKnown.cacheFile(context))
            EventRecorder.reloadKnown(context)
            val key = key(context, targets)
            val file = staticFile(context)
            if (file.exists() && runCatching { keyFile(context).readText() }.getOrNull() == key) {
                readyAt = file.lastModified()
            } else {
                val text = synchronized(HEAVY) { Recon.staticSections(context, targets, known) }
                dir(context).mkdirs()
                file.writeText(text)
                keyFile(context).writeText(key)
                propsFile(context).delete() // property names are re-read from the (possibly updated) vendor apps
                readyAt = file.lastModified()
            }
        }.onSuccess { state = State.READY }.onFailure { state = State.FAILED }
    }

    /** What the analysis depends on: both config files, the vendor apps' versions and q5home's own version. */
    private fun key(context: Context, targets: ReconTargets): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val versions = (targets.packages + context.packageName).joinToString(",") { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0).let { "$pkg:${it.versionCode}:${it.lastUpdateTime}" } }.getOrDefault("$pkg:-")
        }
        val configs = listOf(ReconTargets.cacheFile(context), ReconKnown.cacheFile(context))
            .joinToString("|") { runCatching { it.readText() }.getOrDefault("") }
        val digest = MessageDigest.getInstance("SHA-1").digest("$versions|$configs".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }

    /** The prepared code analysis, or null if it has not been prepared yet. */
    fun staticPart(context: Context): String? =
        staticFile(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** The whole report: the cheap parts read now + the prepared code analysis. Uses only cached configs (no network). */
    fun build(context: Context, eventsSinceMs: Long?): Recon.Report {
        val targets = ReconTargets.cached(context)
        val known = ReconKnown.cached(context)
        val static = staticPart(context) ?: "## 厂商代码分析\n（还在后台准备，稍后再扫一次码）\n\n"
        return Recon.report(context, targets, known, eventsSinceMs, staticPart = static)
    }

    /** Property names mentioned in the vendor code (reading the APK strings is slow, so cached until the analysis is redone). */
    fun propNames(context: Context, apks: List<Pair<String, File>>): List<String> {
        val file = propsFile(context)
        runCatching { if (file.exists()) return file.readLines().filter { it.isNotBlank() } }
        val names = apks.flatMap { (_, apk) ->
            ApkStrings.fromApk(apk, limit = 300, filter = { PROP_NAME.matches(it) && !it.endsWith('.') })
        }.distinct().sorted().take(PROP_LIMIT)
        runCatching { dir(context).mkdirs(); file.writeText(names.joinToString("\n")) }
        return names
    }

    private const val PROP_LIMIT = 150
    private val PROP_NAME = Regex("^(persist|sys|vendor|debug|ro|init|dev|media|hw)\\.[A-Za-z0-9_.\\-]{2,80}$")
}
