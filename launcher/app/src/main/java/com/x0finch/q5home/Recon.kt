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
     * The one recon report: only what is NEW compared with [known] (earlier reports) —
     *  1. changes: system build, vendor apps added / removed / updated (their components and code strings, minus known strings);
     *  2. GPS fix line;
     *  3. the background event log since [eventsSinceMs] (known noise from [ReconKnown.ignoreEvents] only counted);
     *  4. read-only static analysis of [ReconTargets.packages]: matching methods (minus known ones), broadcast / page-start
     *     call sites, constant tables, native libraries, property and settings values, provider reads (known query results skipped).
     */
    fun report(context: Context, targets: ReconTargets, known: ReconKnown, eventsSinceMs: Long?, nowMs: Long = System.currentTimeMillis()): Report {
        val pm = context.packageManager
        val md = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(nowMs))
        fun info(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val self = info(context.packageName)?.versionName ?: "?"

        md.appendLine("<!-- q5home-recon v3 -->")
        md.appendLine("由 q5home $self 在车上生成（$now），只读，没有改动车机。")
        md.appendLine(if (known.source != null) "只列新内容：之前报告里已经有的（${known.source}）都省略了。"
            else "**已知列表没下载到**（车机没联网？），下面是全部内容，没有省略已知的。")
        md.appendLine()

        // ---- 1. Changes ----
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

        // ---- 2. GPS ----
        md.appendLine("## GPS")
        SignalCheck(context).gpsLines().forEach { (k, v) -> md.appendLine("- $k：$v") }
        md.appendLine()

        // ---- 3. Event log ----
        val all = EventLog.lines(context, eventsSinceMs)
        val noise = known.ignoreEvents.associateWith { 0 }.toMutableMap()
        val events = all.filter { line ->
            val g = known.ignoreEvents.firstOrNull { it.pattern.containsMatchIn(line) }
            if (g != null) noise[g] = noise.getValue(g) + 1
            g == null
        }
        val since = eventsSinceMs?.let { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(it)) }
        md.appendLine("## 后台事件记录（${if (since != null) "上次复制报告 $since 之后" else "全部"}，${events.size} 条）")
        md.appendLine("q5home 在后台只听不发。时间是车机时间，精确到毫秒；「启动」= q5home 进程重新启动，之前的空白是没在记录。" +
            "「前台」= 切到前台的应用和页面（要在系统里给 q5home 开「使用情况访问权限」才有）。")
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

        // ---- 4. Static analysis ----
        val apks = targets.packages.mapNotNull { pkg -> info(pkg)?.applicationInfo?.sourceDir?.let { pkg to File(it) } }
        for (group in targets.code) {
            md.appendLine("## ${group.name}（只读反汇编）")
            md.appendLine("用到匹配 `${group.pattern.pattern}` 的字符串、调用或字段（或自己就叫这个名字）的方法：" +
                "按顺序列出字符串（str）、整数（int）、调用（call）、读写字段（get / put）。静态初始化（只是填表）不列，见常量表。")
            md.appendLine()
            var any = false
            var skipped = 0
            for ((pkg, apk) in apks) {
                val match = { s: String -> group.pattern.containsMatchIn(s) }
                val methods = DexScan.scanApk(apk, match, targets.maxMethods + known.methods.size.coerceAtMost(20), targets.maxEvents, refs = match)
                val fresh = methods.filter { it.name !in known.methods }.take(targets.maxMethods)
                skipped += methods.size - methods.count { it.name !in known.methods }
                if (fresh.isEmpty()) continue
                any = true
                md.appendLine("### `$pkg`（${fresh.size} 个方法）")
                fresh.forEach { m ->
                    md.appendLine("- `${m.name}`")
                    md.appendLine("  " + m.events.joinToString(" → ") { "`$it`" })
                }
                md.appendLine()
            }
            if (!any) md.appendLine("（没有新的）\n") else if (skipped > 0) md.appendLine("（另有 $skipped 个已知方法省略）\n")
        }

        for (site in targets.sites) {
            md.appendLine("## ${site.name}（只读反汇编）")
            md.appendLine("调用了匹配 `${site.call.pattern}` 的方法的地方，以及这个方法里用到的字符串（广播动作、参数名、包名 / 类名）。")
            md.appendLine()
            var any = false
            for ((pkg, apk) in apks) {
                val found = DexScan.sitesApk(apk, { site.call.containsMatchIn(it) }, SITE_LIMIT)
                if (found.isEmpty()) continue
                any = true
                md.appendLine("### `$pkg`（${found.size} 处${if (found.size >= SITE_LIMIT) "，只列前 $SITE_LIMIT 处" else ""}）")
                found.forEach { f ->
                    md.appendLine("- `${f.name}` → ${f.calls.joinToString { "`${it.substringAfterLast('.')}`" }}：" +
                        f.strings.ifEmpty { listOf("（没有字符串）") }.joinToString(" ") { "`$it`" })
                }
                md.appendLine()
            }
            if (!any) md.appendLine("（这些应用里没找到）\n")
        }

        targets.constants?.let { pattern ->
            md.appendLine("## 常量表（类名匹配 `${pattern.pattern}`）")
            md.appendLine("每个类的静态字段和值；没有值的是在代码里赋值的（比如枚举），只列名字。整数同时给出十六进制。")
            md.appendLine()
            for ((pkg, apk) in apks) {
                val tables = DexScan.constantsApk(apk, { name ->
                    LIBRARY_CLASS.none { name.startsWith(it) } && pattern.containsMatchIn(name)
                })
                if (tables.isEmpty()) continue
                md.appendLine("### `$pkg`（${tables.size} 个类）")
                tables.forEach { t ->
                    md.appendLine("- `${t.className}`：" + t.fields.joinToString(" · ") { (k, v) -> if (v == null) "`$k`" else "`$k=$v`" })
                }
                md.appendLine()
            }
        }

        targets.nativeLibs?.let { pattern ->
            md.appendLine("## 原生库（只读文件内容，不加载、不运行）")
            md.appendLine("文件名匹配 `${pattern.pattern}` 的 .so：导出的函数（Java_ 开头的是给 Java 调的入口）和像设备节点、串口、视频源的字符串。")
            md.appendLine()
            val libs = nativeLibraries(context, targets.packages, pattern)
            if (libs.isEmpty()) md.appendLine("（没找到）\n")
            for ((where, bytes) in libs) {
                val r = ElfScan.scan(bytes) ?: continue
                md.appendLine("### `$where`（${bytes.size / 1024} KB）")
                md.appendLine("- 导出函数 ${r.exports.size} 个：" + r.exports.joinToString(" ") { "`$it`" }.ifEmpty { "（无）" })
                md.appendLine("- 字符串：" + r.strings.joinToString(" ") { "`$it`" }.ifEmpty { "（无）" })
                md.appendLine()
            }
        }

        // Property names mentioned in the vendor code, with their current values (the recorder watches these for changes).
        md.appendLine("## 代码里提到的系统属性（当前值）")
        val propNames = apks.flatMap { (_, apk) ->
            ApkStrings.fromApk(apk, limit = 300, filter = { PROP_NAME.matches(it) && !it.endsWith('.') })
        }.distinct().sorted().take(PROP_LIMIT)
        if (propNames.isEmpty()) md.appendLine("（没找到）")
        else md.appendLine(propNames.joinToString(" · ") { "`$it`=${prop(it).ifEmpty { "（空）" }.take(60)}" })
        md.appendLine()

        targets.settings?.let { pattern ->
            md.appendLine("## 系统设置当前值（键名匹配 `${pattern.pattern}`）")
            for ((table, uri) in listOf("system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI,
                    "secure" to Settings.Secure.CONTENT_URI)) {
                val rows = runCatching {
                    context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                        val out = mutableListOf<String>()
                        while (c.moveToNext()) {
                            val k = c.getString(0) ?: continue
                            if (pattern.containsMatchIn(k)) out += "`$k=${(c.getString(1) ?: "null").take(60)}`"
                        }
                        out
                    }
                }.getOrNull()
                md.appendLine("- $table：" + when {
                    rows == null -> "读不了"
                    rows.isEmpty() -> "（没有匹配的）"
                    else -> rows.sorted().joinToString(" ")
                })
            }
            md.appendLine()
        }

        if (targets.providers.isNotEmpty()) {
            md.appendLine("## 数据接口试读（只读 query + getType）")
            md.appendLine("getType 也是只读的；厂商的 CanbusProvider.getType 会按地址里的参数返回数据。query 结果已知的不再列。")
            targets.providers.forEach {
                val q = if (it in known.providers) "" else "query：${queryProvider(context, it)}；"
                md.appendLine("- `$it`：${q}getType：${providerType(context, it)}")
            }
            md.appendLine()
        }

        return Report(md.toString(), "车机摸底 ${Build.MODEL} $now")
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

    private const val SITE_LIMIT = 30
    private const val EVENT_LIMIT = 1500
    private const val PROP_LIMIT = 150
    private val PROP_NAME = Regex("^(persist|sys|vendor|debug|ro|init|dev|media|hw)\\.[A-Za-z0-9_.\\-]{2,80}$")
    private val LIBRARY_CLASS = listOf("android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "com.google.", "okhttp3.", "org.")
    private const val MAX_LIB_BYTES = 16L shl 20

    /**
     * Native libraries of the given apps whose file name matches [pattern]: inside the APK (lib/…/x.so), in the app's extracted
     * library folder, and in /system/lib and /vendor/lib. Only reads the files; nothing is loaded. At most 12 libraries.
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
        for (dir in listOf("/system/lib", "/vendor/lib", "/system/lib64", "/vendor/lib64")) {
            runCatching {
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
