package com.x0finch.q5home

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Head-unit recon report (read-only, never changes any head-unit state): one report that only shows what is new compared with
 * earlier reports ([ReconKnown]) — changes, the background event log ([EventRecorder]) and a read-only analysis of the vendor
 * apps' code ([ReconTargets]). The owner copies it from the QR-code web page ([LocalShare]).
 */
object Recon {

    data class Report(val markdown: String, val title: String)

    private val AOSP_PREFIXES = listOf("android", "com.android.", "com.google.", "androidx.")
    private const val PER_PACKAGE_LIMIT = 40
    private const val DIG_LIMIT = 600
    /** Max number of new related strings listed per app in the report. */
    private const val STRING_LIMIT = 150
    /** Max rows listed per content:// URI. */
    private const val PROVIDER_ROWS = 6

    private fun installedPackages(pm: PackageManager): List<PackageInfo> {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        // Fetching everything at once can exceed the Binder limit (with many apps); on failure fetch one by one.
        return runCatching { pm.getInstalledPackages(flags) }.getOrElse {
            pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS).map { p ->
                runCatching { pm.getPackageInfo(p.packageName, flags) }.getOrDefault(p)
            }
        }
    }

    /**
     * The one recon report in one go (used by tests; on the head unit [ReconReport] prepares the parts in the background):
     * only what is NEW compared with [known] — event log since [eventsSinceMs], changes, vehicle data, properties, settings,
     * provider reads and the code analysis ([staticPart] if already prepared).
     */
    fun report(context: Context, targets: ReconTargets, known: ReconKnown, eventsSinceMs: Long?,
               nowMs: Long = TrueTime.now(), staticPart: String? = null): Report {
        val md = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(nowMs))
        val self = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        md.appendLine("<!-- q5home-recon v4 -->")
        md.appendLine("由 q5home $self 在车上生成（$now），只读，没有改动车机。")
        md.appendLine(if (known.source != null) "只列新内容：之前报告里已经有的（${known.source}）都省略了。"
            else "**已知列表没下载到，车机上也没有存过**，下面是全部内容，没有省略已知的。")
        md.appendLine()
        md.append(eventsSection(context, known, eventsSinceMs))
        md.append(snapshotSections(context, targets, known))
        md.append(staticPart ?: staticSections(context, targets, known))
        return Report(md.toString(), "车机摸底 ${Build.MODEL} $now")
    }

    /** Writes a section of one-line items, leaving out lines already known; says so when nothing is new. */
    private fun StringBuilder.section(title: String, intro: String?, lines: List<String>, known: ReconKnown, empty: String = "（没有新的）") {
        appendLine("## $title")
        intro?.let { appendLine(it) }
        val fresh = lines.filterNot { known.knows(it) }
        if (fresh.isEmpty()) appendLine(if (lines.isEmpty()) empty else "（${lines.size} 条都是已知的，省略）")
        else {
            fresh.forEach { appendLine(it) }
            if (fresh.size < lines.size) appendLine("（另有 ${lines.size - fresh.size} 条已知的省略）")
        }
        appendLine()
    }

    /** The event log since [eventsSinceMs] (read when the report is shown: it is only a local file). */
    fun eventsSection(context: Context, known: ReconKnown, eventsSinceMs: Long?, afterLine: String? = null): String {
        val md = StringBuilder()
        val all = EventLog.lines(context, eventsSinceMs, afterLine).sortedBy { EventLog.timeOf(it) ?: 0L }
        val noise = known.ignoreEvents.associateWith { 0 }.toMutableMap()
        val events = all.filter { line ->
            val g = known.ignoreEvents.firstOrNull { it.pattern.containsMatchIn(line) }
            if (g != null) noise[g] = noise.getValue(g) + 1
            g == null
        }
        val since = eventsSinceMs?.let { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(it)) }
        md.appendLine("## 后台事件记录（${if (since != null) "上次复制报告 $since 之后" else "全部"}，${events.size} 条）")
        md.appendLine("q5home 在后台只听不发，按时间排序。「启动」= q5home 进程重新启动，之前的空白是没在记录；「前台」= 切到前台的应用和页面。")
        noise.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { n ->
            md.appendLine("已知、省略：" + n.entries.joinToString("；") { "${it.key.name} ${it.value} 条" } + "。")
        }
        if (events.isEmpty()) md.appendLine("（没有新事件）")
        else {
            val shown = events.takeLast(EVENT_LIMIT)
            if (events.size > shown.size) md.appendLine("太多了，只列最后 $EVENT_LIMIT 条。")
            md.appendLine("```")
            shown.forEach { md.appendLine(it) }
            md.appendLine("```")
        }
        md.appendLine()
        return md.toString()
    }

    /** Changes, vehicle data, properties, settings and provider reads: prepared in the background with the code analysis. */
    fun snapshotSections(context: Context, targets: ReconTargets, known: ReconKnown): String {
        val pm = context.packageManager
        val md = StringBuilder()

        // ---- Changes ----
        md.appendLine("## 和上次相比的变化")
        val changes = mutableListOf<String>()
        if (known.system != null && known.system != Build.DISPLAY) changes += "系统版本：${known.system} → ${Build.DISPLAY}"
        val vendor = installedPackages(pm).filter { p ->
            p.packageName != context.packageName && AOSP_PREFIXES.none { p.packageName == it || p.packageName.startsWith(it) }
        }.associateBy { it.packageName }
        val changed = mutableListOf<PackageInfo>()
        if (known.source != null) {
            for ((pkg, p) in vendor.toSortedMap()) {
                val was = known.versions[pkg]
                when {
                    was == null -> { changes += "新应用：`$pkg` ${p.versionName}"; changed += p }
                    was != p.versionName -> { changes += "应用更新：`$pkg` $was → ${p.versionName}"; changed += p }
                }
            }
            known.versions.keys.filter { it !in vendor && it != context.packageName }.sorted()
                .forEach { changes += "应用没了（卸载或停用后不可见）：`$it`" }
        }
        md.appendLine(if (changes.isEmpty()) "没有变化：系统和 ${vendor.size} 个厂商应用的版本都和上次一样。"
            else changes.joinToString("\n") { "- $it" })
        md.appendLine()
        for (p in changed) appInternals(md, pm, p, known.strings)

        // ---- Vehicle data (read-only ContentResolver.call getters, the way the stock launcher reads them) ----
        if (targets.calls.isNotEmpty()) {
            md.appendLine("## 车辆数据（只读 call，和原厂主页、液晶仪表的读法一样）")
            targets.calls.forEach { (uri, method) -> md.appendLine("- `$method`：${callProvider(context, uri, method)}") }
            md.appendLine()
        }

        // ---- Properties named in vendor code, settings, providers: only values not seen before ----
        val apks = targets.packages.mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()?.let { pkg to File(it) }
        }
        val propNames = ReconReport.propNames(context, apks)
        md.section("代码里提到的系统属性（当前值）", null,
            propNames.map { "- `$it`=${prop(it).ifEmpty { "（空）" }.take(60)}" }, known, "（没找到）")

        targets.settings?.let { pattern ->
            val rows = mutableListOf<String>()
            for ((table, uri) in listOf("system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI,
                    "secure" to Settings.Secure.CONTENT_URI)) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val k = c.getString(0) ?: continue
                            if (pattern.containsMatchIn(k)) rows += "- $table/`$k`=${(c.getString(1) ?: "null").take(60)}"
                        }
                    }
                }
            }
            md.section("系统设置当前值（键名匹配 `${pattern.pattern}`）", null, rows.sorted(), known, "（没有匹配的）")
        }

        if (targets.providers.isNotEmpty()) {
            md.section("数据接口试读（只读 query + getType）", null, targets.providers.map {
                "- `$it`：query：${queryProvider(context, it)}；getType：${providerType(context, it)}"
            }, known)
        }
        return md.toString()
    }

    /** The slow part (reads and disassembles the vendor APKs): prepared in the background by [ReconReport]. */
    fun staticSections(context: Context, targets: ReconTargets, known: ReconKnown, progress: (String) -> Unit = {}): String {
        val pm = context.packageManager
        val md = StringBuilder()
        val apks = targets.packages.mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()?.let { pkg to File(it) }
        }
        val vendorCode = { name: String -> targets.vendorClasses?.containsMatchIn(name) ?: true }

        for ((i, group) in targets.code.withIndex()) {
            progress("分析厂商代码：${group.name}（${i + 1}/${targets.code.size}）")
            md.appendLine("## ${group.name}（只读反汇编）")
            md.appendLine("厂商代码里用到匹配 `${group.pattern.pattern}` 的字符串、调用或字段（或自己就叫这个名字）的方法：" +
                "按顺序列出字符串（str）、整数（int）、调用（call）、读写字段（get / put）。")
            md.appendLine()
            var any = false
            var skipped = 0
            for ((pkg, apk) in apks) {
                val match = { s: String -> group.pattern.containsMatchIn(s) }
                val methods = DexScan.scanApk(apk, match, targets.maxMethods + known.methods.size.coerceAtMost(40), targets.maxEvents,
                    refs = match, owner = vendorCode)
                val fresh = methods.filter { it.name !in known.methods }.take(targets.maxMethods)
                skipped += methods.count { it.name in known.methods }
                if (fresh.isEmpty()) continue
                any = true
                md.appendLine("### `$pkg`（${fresh.size} 个方法）")
                fresh.forEach { m ->
                    md.appendLine("- `${m.name}`")
                    md.appendLine("  " + m.events.joinToString(" → ") { "`$it`" })
                }
                md.appendLine()
            }
            if (!any) md.appendLine(if (skipped > 0) "（$skipped 个方法都是已知的，省略）\n" else "（没找到）\n")
            else if (skipped > 0) md.appendLine("（另有 $skipped 个已知方法省略）\n")
        }

        for (site in targets.sites) {
            progress("分析厂商代码：${site.name}")
            val lines = mutableListOf<String>()
            for ((pkg, apk) in apks) {
                DexScan.sitesApk(apk, { site.call.containsMatchIn(it) }, SITE_LIMIT, owner = vendorCode).forEach { f ->
                    lines += "- `$pkg` `${f.name}` → ${f.calls.joinToString { "`${it.substringAfterLast('.')}`" }}：" +
                        f.strings.ifEmpty { listOf("（没有字符串）") }.joinToString(" ") { "`$it`" }
                }
            }
            md.section("${site.name}（只读反汇编）", "厂商代码里调用了匹配 `${site.call.pattern}` 的地方，以及用到的字符串（广播动作、参数名、包名 / 类名）。",
                lines, known, "（没找到）")
        }

        targets.constants?.let { pattern ->
            progress("分析厂商代码：常量表")
            val lines = mutableListOf<String>()
            for ((pkg, apk) in apks) {
                DexScan.constantsApk(apk, { name -> vendorCode(name) && pattern.containsMatchIn(name) }).forEach { t ->
                    lines += "- `$pkg` `${t.className}`：" + t.fields.joinToString(" · ") { (k, v) -> if (v == null) "`$k`" else "`$k=$v`" }
                }
            }
            md.section("常量表（类名匹配 `${pattern.pattern}`）", "静态字段和值；没有值的是在代码里赋值的（比如枚举）。整数同时给出十六进制。",
                lines.distinct(), known, "（没找到）")
        }

        targets.nativeLibs?.let { pattern ->
            val lines = mutableListOf<String>()
            for ((where, bytes) in nativeLibraries(context, targets.packages, pattern)) {
                val r = ElfScan.scan(bytes) ?: continue
                lines += "- `$where`（${bytes.size / 1024} KB）导出函数：" + r.exports.joinToString(" ") { "`$it`" }.ifEmpty { "（无）" } +
                    "；字符串：" + r.strings.joinToString(" ") { "`$it`" }.ifEmpty { "（无）" }
            }
            md.section("厂商原生库（只读文件内容，不加载、不运行）", null, lines, known, "（没找到）")
        }

        targets.strings?.let { pattern ->
            val lines = apks.mapNotNull { (pkg, apk) ->
                ApkStrings.fromApk(apk, limit = STRING_LIMIT * 8, filter = ApkStrings.matching(pattern))
                    .filter { it !in known.strings }.take(STRING_LIMIT).takeIf { it.isNotEmpty() }
                    ?.let { "- `$pkg`：" + it.joinToString(" ") { s -> "`$s`" } }
            }
            md.section("新的相关字符串（`${pattern.pattern}`）", null, lines, known, "（没有新的）")
        }
        return md.toString()
    }

    /** Read-only ContentResolver.call of a "xxx_info" getter; returns every key and value of the Bundle. Waits at most 3 seconds. */
    fun callProvider(context: Context, uri: String, method: String): String {
        if (!ReconTargets.CALL_METHOD.matches(method)) return "不允许的方法"
        return withTimeout {
            val b = context.contentResolver.call(android.net.Uri.parse(uri), method, null, null)
            if (b == null) "返回空" else "${b.size()} 项：" + EventRecorder.extras(b).take(3000)
        }
    }

    /** All components (including non-exported, marked 🔒) and the vendor-looking code strings of one app, minus [knownStrings]. */
    private fun appInternals(md: StringBuilder, pm: PackageManager, p: PackageInfo, knownStrings: Set<String>) {
        val apk = p.applicationInfo?.sourceDir?.let { File(it) }
        md.appendLine("### `${p.packageName}` ${p.versionName}${apk?.let { "（${it.length() / 1024} KB）" } ?: ""}")
        val full = runCatching {
            pm.getPackageInfo(p.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull()
        fun list(kind: String, items: List<android.content.pm.ComponentInfo>) {
            items.take(PER_PACKAGE_LIMIT * 2).forEach { md.appendLine("- $kind：`${it.name}`${if (it.exported) "" else " 🔒"}") }
            if (items.size > PER_PACKAGE_LIMIT * 2) md.appendLine("- …还有 ${items.size - PER_PACKAGE_LIMIT * 2} 个$kind")
        }
        list("页面", full?.activities.orEmpty().toList())
        list("服务", full?.services.orEmpty().toList())
        list("广播", full?.receivers.orEmpty().toList())
        full?.providers.orEmpty().forEach { md.appendLine("- 数据接口：`content://${it.authority}`（`${it.name}`${if (it.exported) "" else " 🔒"}）") }
        if (apk != null) {
            val strings = ApkStrings.fromApk(apk, limit = DIG_LIMIT, filter = ApkStrings::interestingDeep).filter { it !in knownStrings }
            md.appendLine("- 新的代码字符串 ${strings.size} 条" + if (strings.isEmpty()) "" else "：" + strings.joinToString(" ") { "`$it`" })
        }
        md.appendLine()
    }

    private const val SITE_LIMIT = 60
    private const val EVENT_LIMIT = 1500
    private val LIBRARY_CLASS = listOf("android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "com.google.", "okhttp3.", "org.")
    private const val MAX_LIB_BYTES = 16L shl 20

    /**
     * Native libraries of the given apps whose file name matches [pattern]: inside the APK (lib/…/x.so) and in the app's extracted
     * library folder (not the system's own libraries). Only reads the files; nothing is loaded. At most 12 libraries.
     */
    private fun nativeLibraries(context: Context, packages: List<String>, pattern: Regex): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        val seen = mutableSetOf<String>()
        fun add(where: String, name: String, read: () -> ByteArray) {
            if (out.size >= 12 || !name.endsWith(".so") || !pattern.containsMatchIn(name) || !seen.add(name)) return
            runCatching { read() }.getOrNull()?.let { out += where to it }
        }
        for (pkg in packages) {
            val app = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            app.sourceDir?.let { path ->
                runCatching {
                    java.util.zip.ZipFile(path).use { zip ->
                        zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.size in 1..MAX_LIB_BYTES }.forEach { e ->
                            add("$pkg!${e.name}", e.name.substringAfterLast('/')) { zip.getInputStream(e).use { it.readBytes() } }
                        }
                    }
                }
            }
            app.nativeLibraryDir?.let { dir ->
                File(dir).listFiles().orEmpty().filter { it.length() in 1..MAX_LIB_BYTES }.forEach { f -> add(f.path, f.name) { f.readBytes() } }
            }
        }
        return out
    }

    /** Read-only getType of a content:// URI (some providers answer this even when query returns nothing). Waits at most 3 seconds. */
    fun providerType(context: Context, uri: String): String = withTimeout {
        context.contentResolver.getType(android.net.Uri.parse(uri))?.let { "`$it`" } ?: "null"
    }

    private fun withTimeout(block: () -> String): String {
        val task = java.util.concurrent.FutureTask(block)
        Thread(task, "recon-query").apply { isDaemon = true }.start()
        return try {
            task.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            "3 秒没响应"
        } catch (e: Exception) {
            val cause = (e.cause ?: e)
            "读不了：${cause.javaClass.simpleName}${cause.message?.let { "（${it.take(80)}）" } ?: ""}"
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    /** Read-only query of a content:// URI; returns the column names and first few rows (each cell cut to 60 chars). Waits at most 3 seconds. */
    fun queryProvider(context: Context, uri: String): String = withTimeout {
            context.contentResolver.query(android.net.Uri.parse(uri), null, null, null, null)?.use { c ->
                val cols = c.columnNames.toList()
                val rows = mutableListOf<String>()
                while (rows.size < PROVIDER_ROWS && c.moveToNext()) {
                    rows += cols.indices.joinToString(", ") { i ->
                        "${cols[i]}=" + (runCatching { c.getString(i) }.getOrNull() ?: "null").take(60)
                    }
                }
                "${c.count} 行，列 ${cols.joinToString()}" + rows.joinToString("") { "<br>　`$it`" }
            } ?: "返回空（没有这个地址或不让读）"
    }

    /** Reads a system property (hidden API; returns an empty string if unreadable). */
    fun prop(name: String): String = runCatching { propGetter!!.invoke(null, name) as String }.getOrDefault("")

    /** Looked up once: the background recorder reads properties every couple of seconds. */
    private val propGetter: java.lang.reflect.Method? by lazy { lookupPropGetter() }

    @SuppressLint("PrivateApi")
    private fun lookupPropGetter(): java.lang.reflect.Method? =
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
}
