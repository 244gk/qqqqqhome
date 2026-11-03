package com.x0finch.q5home

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Prepares the whole recon report in the background, so scanning the QR code shows it at once (nothing is computed then):
 *  - shortly after q5home starts (the head unit starting) or the ignition comes on, it downloads the two config files
 *    (offline: the copies kept from last time), runs the slow code analysis ([Recon.staticSections], redone only when a
 *    config, a vendor app or q5home changes) and reads the rest ([Recon.snapshotSections]); then refreshes every [REFRESH_MS];
 *  - [serve] only reads that file and adds the event log (a local file), so the page appears immediately.
 * Read-only like the rest of recon: it only writes q5home's own files.
 */
object ReconReport {
    /** Delay after start-up when a prepared report already exists (the head unit is busy just after it starts). */
    const val START_DELAY_MS = 120_000L
    /** Delay when nothing is prepared yet (first start of a new version): start almost right away. */
    const val FIRST_DELAY_MS = 15_000L
    const val REFRESH_MS = 15 * 60_000L

    enum class State { IDLE, WORKING, READY, FAILED }

    /** Held while reading the large vendor APKs (here and in the recorder's discovery), so two such reads never run at once. */
    val HEAVY = Any()

    @Volatile var state = State.IDLE; private set
    /** What the background work is doing right now (shown on the recon page and the web page while not ready). */
    @Volatile var progress = ""; private set

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "q5-report").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    private fun dir(context: Context) = File(context.filesDir, "recon")
    private fun staticFile(context: Context) = File(dir(context), "report-static.md")
    private fun keyFile(context: Context) = File(dir(context), "report-static.key")
    private fun readyFile(context: Context) = File(dir(context), "report-ready.md")
    private fun propsFile(context: Context) = File(dir(context), "props.txt")

    /** When the whole report was last prepared (0 = never). */
    fun readyAt(context: Context): Long = readyFile(context).takeIf { it.exists() }?.lastModified() ?: 0L

    /**
     * Prepares the report after [delayMs] (default: soon if nothing is prepared yet, otherwise after start-up has settled).
     * A pending run is kept rather than pushed back.
     */
    @Synchronized
    fun schedule(context: Context, delayMs: Long? = null) {
        if (pending?.isDone == false) return
        val app = context.applicationContext
        val delay = delayMs ?: if (readyAt(app) > 0) START_DELAY_MS else FIRST_DELAY_MS
        pending = executor.schedule({ prepare(app); scheduleRefresh(app) }, delay, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun scheduleRefresh(context: Context) {
        pending = executor.schedule({ prepare(context); scheduleRefresh(context) }, REFRESH_MS, TimeUnit.MILLISECONDS)
    }

    /** Downloads the configs (or uses the cached ones), redoes the code analysis if needed and writes the whole report. */
    fun prepare(context: Context) {
        state = State.WORKING
        progress = "下载分析配置"
        runCatching {
            val targets = ReconTargets.load(cache = ReconTargets.cacheFile(context))
            val known = ReconKnown.load(cache = ReconKnown.cacheFile(context))
            EventRecorder.reloadKnown(context)
            val key = key(context, targets)
            val static = staticFile(context)
            if (!static.exists() || runCatching { keyFile(context).readText() }.getOrNull() != key) {
                progress = "等后台记录收集完广播名"
                val text = synchronized(HEAVY) { Recon.staticSections(context, targets, known) { progress = it } }
                dir(context).mkdirs()
                static.writeText(text)
                keyFile(context).writeText(key)
                propsFile(context).delete() // property names are re-read from the (possibly updated) vendor apps
            }
            progress = "读取车辆数据、属性和设置"
            val snapshot = Recon.snapshotSections(context, targets, known)
            val tmp = File(dir(context), "report-ready.tmp")
            tmp.writeText(snapshot + static.readText())
            tmp.renameTo(readyFile(context))
        }.onSuccess { state = State.READY; progress = "" }
            .onFailure { state = State.FAILED; progress = "准备报告出错：${it.javaClass.simpleName} ${it.message?.take(80) ?: ""}" }
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

    /** Whether a prepared report is there to show. */
    fun isReady(context: Context) = readyFile(context).exists()

    /**
     * The report as shown on the QR page: the prepared file + the event log. Never computes anything slow; if nothing is
     * prepared yet it says what the background work is doing (the page then refreshes itself).
     */
    fun serve(context: Context, eventsSinceMs: Long?, afterLine: String? = null): String {
        val known = ReconKnown.cached(context)
        val self = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val ready = readyFile(context).takeIf { it.exists() }
        val md = StringBuilder()
        md.appendLine("<!-- q5home-recon v5 -->")
        md.appendLine("q5home $self，只读，没有改动车机。" + if (ready != null)
            "报告在 ${time.format(Date(TrueTime.correct(ready.lastModified())))} 后台准备好，事件记录到 ${time.format(TrueTime.date())}。" else "")
        if (TrueTime.systemClockRolledBack()) md.appendLine("**车机系统时钟早了 1024 周**（自动时间用了 GPS 报的日期），报告里的时间已按真实日期纠正。")
        md.appendLine(if (known.source != null) "只列新内容：之前报告里已经有的（${known.source}）都省略了。"
            else "**已知列表没下载到，车机上也没有存过**，下面是全部内容，没有省略已知的。")
        md.appendLine()
        md.append(Recon.eventsSection(context, known, eventsSinceMs, afterLine))
        if (ready != null) md.append(runCatching { ready.readText() }.getOrDefault("（读不了准备好的报告）\n"))
        else md.appendLine("## 其余部分还在后台准备\n${progress.ifEmpty { "车机启动后马上开始" }}（本页会自动刷新）\n")
        return md.toString()
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
