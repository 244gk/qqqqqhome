package com.x0finch.q5home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Head-unit recon (read-only, never changes any head-unit state): checks on the car what tools/recon.sh checks, without adb.
 *  - System info and debug / root clues (build type, su files, adb ports);
 *  - Candidate components for the launcher, CarPlay and the OEM screen;
 *  - Vendor apps (non-AOSP / Google): launcher entries, exported activities and broadcast receivers, used to find how to
 *    switch to the OEM screen and hidden factory / USB pages;
 *  - All other apps are listed by package name only.
 */
object Recon {

    /** A component that can be opened directly from the launcher (candidate OEM / CarPlay / debug page). */
    data class Candidate(val kind: String, val label: String, val spec: String)

    data class Report(
        val markdown: String,
        /** Key facts shown on the settings page: (title, value). */
        val facts: List<Pair<String, String>>,
        val candidates: List<Candidate>,
        val title: String,
    )

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su", "/vendor/bin/su",
        "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk",
    )
    private val PROPS = listOf(
        "ro.build.type", "ro.debuggable", "ro.secure", "ro.adb.secure", "ro.boot.selinux",
        "service.adb.tcp.port", "persist.adb.tcp.port", "persist.sys.usb.config", "sys.usb.config", "sys.usb.state",
        "ro.board.platform", "ro.hardware", "ro.product.board", "ro.soc.model", "ro.product.cpu.abi",
        "ro.build.display.id", "ro.build.version.incremental", "ro.build.date",
    )
    private val AOSP_PREFIXES = listOf("android", "com.android.", "com.google.", "androidx.")
    /** Components whose names look like debug / factory / USB settings; they may be hidden switches. */
    private val DEBUG_WORDS = Regex("adb|usb|debug|develop|factory|engineer|wireless|otg|工厂|调试", RegexOption.IGNORE_CASE)
    private val OEM_WORDS = Regex("原车|原厂|oem|origin|mmi|canbus|can_|carinfo|avm|camera|reverse|aux", RegexOption.IGNORE_CASE)
    private val CARPLAY_WORDS = Regex("zlink|carplay|diplay|autokit|carlink|androidauto", RegexOption.IGNORE_CASE)
    private const val PER_PACKAGE_LIMIT = 40
    /** Vendor apps whose APK strings are read (in addition to all launcher apps). */
    private val DIG_PACKAGES = Regex("\\.main$|mainui|black|carmanager|canbus|canmcu|\\.oem|avin")
    private const val DIG_LIMIT = 600
    /** Max number of related strings listed per app in the focused report. */
    private const val STRING_LIMIT = 150
    /** Strings to look for when disassembling (related to the OEM screen). */
    private val ORIGINAL_CODE = Regex(
        "original_screen|enter_original|switch_original|original_unlock|source_avin|media_box", RegexOption.IGNORE_CASE)
    /** Max number of content:// URIs to try reading, and max rows per URI. */
    private const val PROVIDER_LIMIT = 12
    private const val PROVIDER_ROWS = 6

    /**
     * Full report. With [deep] = false, skips reading APKs / disassembling / trial provider reads (when the settings
     * page opens it only needs the key facts and candidate entries, which takes a few seconds).
     */
    fun collect(context: Context, ports: List<Int> = listOf(5555), deep: Boolean = true): Report {
        val pm = context.packageManager
        val props = PROPS.associateWith { prop(it) }
        val md = StringBuilder()
        val candidates = mutableListOf<Candidate>()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

        md.appendLine("<!-- q5home-recon v1 -->")
        md.appendLine("由 q5home $version 在车上生成（$now），只读，没有改动车机。")
        md.appendLine()

        // ---- System ----
        md.appendLine("## 系统")
        md.appendLine("| 项 | 值 |")
        md.appendLine("|---|---|")
        listOf(
            "厂商 / 型号" to "${Build.MANUFACTURER} / ${Build.MODEL}",
            "品牌 / 设备 / 产品" to "${Build.BRAND} / ${Build.DEVICE} / ${Build.PRODUCT}",
            "硬件 / 主板" to "${Build.HARDWARE} / ${Build.BOARD}",
            "安卓" to "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}${realVersionNote()}），安全补丁 ${Build.VERSION.SECURITY_PATCH}",
            "构建" to "${Build.DISPLAY} · ${Build.TYPE} · ${Build.TAGS}",
            "Fingerprint" to Build.FINGERPRINT,
        ).forEach { (k, v) -> md.appendLine("| $k | ${cell(v)} |") }
        props.forEach { (k, v) -> md.appendLine("| `$k` | ${cell(v.ifEmpty { "—" })} |") }
        md.appendLine()

        // ---- Debug / root clues ----
        val su = SU_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        val adbPorts = (ports + listOfNotNull(props["service.adb.tcp.port"]?.toIntOrNull(), props["persist.adb.tcp.port"]?.toIntOrNull()))
            .filter { it in 1..65535 }.distinct()
        val portState = adbPorts.associateWith { probe(it) }
        val usbDebug = DebugAccess.usbDebuggingOn(context)
        val wifiDebug = DebugAccess.wirelessDebuggingOn(context)
        val devOptions = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        md.appendLine("## 调试 / root 线索")
        md.appendLine("- 开发者选项：${onOff(devOptions)}；USB 调试：${onOff(usbDebug)}；无线调试：${onOff(wifiDebug)}")
        md.appendLine("- 构建类型 `${Build.TYPE}`，ro.debuggable=`${props["ro.debuggable"].orEmpty()}`，ro.secure=`${props["ro.secure"].orEmpty()}`")
        md.appendLine("- su 文件：${if (su.isEmpty()) "没找到" else su.joinToString { "`$it`" }}")
        md.appendLine("- 本机 adb 端口：" + portState.entries.joinToString { (p, open) -> "$p ${if (open) "在监听" else "没监听"}" })
        md.appendLine("- 本机 IP：${DebugAccess.ipAddress(context) ?: "未联网"}")
        md.appendLine()

        // ---- Launchers ----
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        md.appendLine("## 桌面（HOME）")
        md.appendLine("当前默认：${HomeRole.currentHomeLabel(context) ?: "没有固定"}")
        homes.forEach { md.appendLine("- ${cell(it.loadLabel(pm).toString())} `${it.activityInfo.packageName}/${it.activityInfo.name}`") }
        md.appendLine()

        // ---- Apps ----
        val packages = installedPackages(pm)
        val (vendor, aosp) = packages.partition { p -> AOSP_PREFIXES.none { p.packageName == it || p.packageName.startsWith(it) } }
        val launchable = Targets.launchableApps(context).groupBy { it.activityInfo.packageName }

        md.appendLine("## 厂商应用（${vendor.size} 个）")
        md.appendLine("格式：名称 `包名` 版本 · 系统/用户 · 启用/停用。入口 = 桌面图标；Activity / 广播 = 导出的组件（可从外部打开）。")
        md.appendLine()
        for (p in vendor.sortedBy { it.packageName }) {
            val app = p.applicationInfo
            val label = app?.loadLabel(pm)?.toString() ?: p.packageName
            val system = app != null && app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val enabled = app?.enabled ?: true
            md.appendLine("### ${cell(label)} `${p.packageName}`")
            md.appendLine("${p.versionName ?: "?"} · ${if (system) "系统" else "用户"} · ${if (enabled) "启用" else "已停用"}")
            launchable[p.packageName]?.forEach { md.appendLine("- 入口：`${it.activityInfo.name}`") }
            val activities = p.activities.orEmpty().filter { it.exported }
            activities.take(PER_PACKAGE_LIMIT).forEach { md.appendLine("- Activity：`${it.name}`") }
            if (activities.size > PER_PACKAGE_LIMIT) md.appendLine("- …还有 ${activities.size - PER_PACKAGE_LIMIT} 个 Activity")
            val receivers = p.receivers.orEmpty().filter { it.exported }
            receivers.take(PER_PACKAGE_LIMIT).forEach { md.appendLine("- 广播：`${it.name}`") }
            if (receivers.size > PER_PACKAGE_LIMIT) md.appendLine("- …还有 ${receivers.size - PER_PACKAGE_LIMIT} 个广播接收器")
            md.appendLine()

            val text = "$label ${p.packageName}"
            if (enabled) {
                launchable[p.packageName]?.forEach { info ->
                    val spec = Targets.specOf(info)
                    when {
                        CARPLAY_WORDS.containsMatchIn(text) -> candidates += Candidate("CarPlay", label, spec)
                        OEM_WORDS.containsMatchIn(text) -> candidates += Candidate("原车", label, spec)
                    }
                }
                activities.filter { DEBUG_WORDS.containsMatchIn(it.name) }.take(10).forEach {
                    candidates += Candidate("调试", label, "${p.packageName}/${it.name}")
                }
            }
        }

        // GPS and 4G snapshot (no coordinates). Live status is on the "GPS 与 4G" page.
        val sig = SignalCheck(context)
        md.appendLine("## GPS 与 4G（摸底时的快照）")
        (sig.gpsLines() + sig.cellLines()).forEach { (k, v) -> md.appendLine("- $k：$v") }
        md.appendLine()

        if (deep) {
            // String constants in the APKs of key vendor apps (OEM launcher, main / mainui / black / carmanager / canbus):
            // switching to the OEM screen is most likely a broadcast or an MCU command, whose name sits in a code constant.
            // Only reads the APK files, never runs anything.
            val homePackages = homes.map { it.activityInfo.packageName }.toSet()
            val contentUris = linkedSetOf<String>()
            val dig = vendor.filter { p ->
                p.packageName != context.packageName && p.applicationInfo?.sourceDir != null &&
                    (p.packageName in homePackages || DIG_PACKAGES.containsMatchIn(p.packageName))
            }.sortedBy { it.packageName }
            md.appendLine("## 关键厂商应用的内部组件和字符串（找原车切换方式，${dig.size} 个应用）")
            md.appendLine("全部页面 / 服务 / 广播接收器（含不对外的，标 🔒），以及 APK 代码里像广播动作、跳转参数、" +
                "或含 原车 / oem / avin / canbus 等字样的常量。")
            md.appendLine()
            for (p in dig) {
                val apk = File(p.applicationInfo!!.sourceDir)
                md.appendLine("### `${p.packageName}`（${apk.length() / 1024} KB）")
                val full = runCatching {
                    pm.getPackageInfo(p.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS)
                }.getOrNull()
                fun list(kind: String, items: List<android.content.pm.ComponentInfo>) {
                    items.take(PER_PACKAGE_LIMIT * 2).forEach {
                        md.appendLine("- $kind：`${it.name}`${if (it.exported) "" else " 🔒"}")
                    }
                    if (items.size > PER_PACKAGE_LIMIT * 2) md.appendLine("- …还有 ${items.size - PER_PACKAGE_LIMIT * 2} 个$kind")
                }
                list("页面", full?.activities.orEmpty().toList())
                list("服务", full?.services.orEmpty().toList())
                list("广播", full?.receivers.orEmpty().toList())
                full?.providers.orEmpty().forEach {
                    md.appendLine("- 数据接口：`content://${it.authority}`（`${it.name}`${if (it.exported) "" else " 🔒"}）")
                    contentUris += "content://${it.authority}/"
                }
                val strings = ApkStrings.fromApk(apk, limit = DIG_LIMIT, filter = ApkStrings::interestingDeep)
                strings.filterTo(contentUris) { it.startsWith("content://") }
                md.appendLine("- 字符串 ${strings.size} 条：")
                if (strings.isEmpty()) md.appendLine("  （没读到，或没有匹配的字符串）")
                else md.appendLine(strings.joinToString("\n") { "  - `$it`" })
                md.appendLine()
            }

            // OEM-related code: what the methods using these strings do, in order (read-only APK disassembly, nothing is run).
            md.appendLine("## 原车相关代码（只读反汇编）")
            md.appendLine("用到 original_screen / enter_original / switch_original / original_unlock / source_avin / media_box 的方法，" +
                "按顺序列出里面的字符串（str）、整数（int）、调用（call）、读写字段（get / put）。")
            md.appendLine()
            for (p in dig) {
                val methods = DexScan.scanApk(File(p.applicationInfo!!.sourceDir), { ORIGINAL_CODE.containsMatchIn(it) })
                if (methods.isEmpty()) continue
                md.appendLine("### `${p.packageName}`（${methods.size} 个方法）")
                methods.forEach { m ->
                    md.appendLine("- `${m.name}`")
                    md.appendLine("  " + m.events.joinToString(" → ") { "`$it`" })
                }
                md.appendLine()
            }

            // Trial-read the content:// URIs found (query only, no writes), e.g. CanbusProvider/original_screen used by the OEM launcher.
            val readable = contentUris.filter { it.startsWith("content://com.wwc2") }
                .sortedBy { !it.contains("original") }.take(PROVIDER_LIMIT)
            md.appendLine("## 厂商数据接口试读（只读 query，${readable.size} 个）")
            readable.forEach { md.appendLine("- `$it`：${queryProvider(context, it)}") }
            md.appendLine()
        }

        // System settings pages whose names relate to USB / adb / developer options (WY may just have hidden the entries).
        val settings = packages.firstOrNull { it.packageName == "com.android.settings" }
        val settingsHits = settings?.activities.orEmpty().filter { it.exported && DEBUG_WORDS.containsMatchIn(it.name) }
        md.appendLine("## 系统设置里和调试有关的页面（${settingsHits.size} 个）")
        settingsHits.forEach {
            md.appendLine("- `${it.name}`")
            candidates += Candidate("调试", "设置 · ${it.name.substringAfterLast('.').substringAfterLast('$')}",
                "com.android.settings/${it.name}")
        }
        md.appendLine()

        md.appendLine("## AOSP / Google 应用（${aosp.size} 个，只列包名，* = 已停用）")
        md.appendLine(aosp.sortedBy { it.packageName }.joinToString(" ") { p ->
            "`${p.packageName}`" + if (p.applicationInfo?.enabled == false) "*" else ""
        })

        val facts = listOf(
            "车机" to "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.HARDWARE} · ${props["ro.board.platform"].orEmpty().ifEmpty { "?" }}",
            "系统" to "Android ${Build.VERSION.RELEASE}${realVersionNote()} · ${Build.TYPE} · ${Build.TAGS} · debuggable=${props["ro.debuggable"].orEmpty().ifEmpty { "?" }}",
            "root 线索（su 文件）" to su.ifEmpty { listOf("没找到") }.joinToString(),
            "adb" to "USB 调试 ${onOff(usbDebug)} · 无线调试 ${onOff(wifiDebug)} · " +
                portState.entries.joinToString { "端口 ${it.key} ${if (it.value) "在监听" else "没监听"}" },
            "应用" to "厂商 ${vendor.size} 个 · AOSP / Google ${aosp.size} 个",
        )

        return Report(md.toString(), facts, candidates.distinctBy { it.spec },
            "车机摸底 ${Build.MANUFACTURER} ${Build.MODEL} $now")
    }

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
     * Focused report: only what [targets] specifies — versions, GPS, call order of related code in the given apps,
     * related strings, and read-only results of the given providers. Omits static content like the package list and system properties.
     */
    fun focused(context: Context, targets: ReconTargets): Report {
        val pm = context.packageManager
        val md = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        fun info(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val self = info(context.packageName)?.versionName ?: "?"

        md.appendLine("<!-- q5home-recon focused v1 -->")
        md.appendLine("由 q5home $self 在车上生成（$now），**重点报告**，只读，没有改动车机。分析目标：${targets.source}。")
        md.appendLine()

        md.appendLine("## 版本")
        md.appendLine("| 应用 | 版本 |")
        md.appendLine("|---|---|")
        md.appendLine("| 系统 | ${cell(Build.DISPLAY)}（API ${Build.VERSION.SDK_INT}） |")
        targets.packages.forEach { pkg ->
            val p = info(pkg)
            md.appendLine("| `$pkg` | ${p?.let { "${it.versionName}（${it.longVersionCodeCompat()}）" } ?: "未安装"} |")
        }
        md.appendLine()

        md.appendLine("## GPS（快照，不含坐标）")
        SignalCheck(context).gpsLines().forEach { (k, v) -> md.appendLine("- $k：$v") }
        md.appendLine()

        val apks = targets.packages.mapNotNull { pkg ->
            info(pkg)?.applicationInfo?.sourceDir?.let { pkg to File(it) }
        }
        for (group in targets.code) {
            md.appendLine("## ${group.name}（只读反汇编）")
            md.appendLine("匹配 `${group.pattern.pattern}` 的方法：按顺序列出字符串（str）、整数（int）、调用（call）、读写字段（get / put）。")
            md.appendLine()
            var any = false
            for ((pkg, apk) in apks) {
                val methods = DexScan.scanApk(apk, { group.pattern.containsMatchIn(it) }, targets.maxMethods, targets.maxEvents)
                if (methods.isEmpty()) continue
                any = true
                md.appendLine("### `$pkg`（${methods.size} 个方法）")
                methods.forEach { m ->
                    md.appendLine("- `${m.name}`")
                    md.appendLine("  " + m.events.joinToString(" → ") { "`$it`" })
                }
                md.appendLine()
            }
            if (!any) md.appendLine("（这些应用里没找到）\n")
        }

        targets.strings?.let { pattern ->
            md.appendLine("## 相关字符串（`${pattern.pattern}`）")
            for ((pkg, apk) in apks) {
                val strings = ApkStrings.fromApk(apk, limit = STRING_LIMIT, filter = ApkStrings.matching(pattern))
                if (strings.isEmpty()) continue
                md.appendLine("- `$pkg`：" + strings.joinToString(" ") { "`$it`" })
            }
            md.appendLine()
        }

        if (targets.providers.isNotEmpty()) {
            md.appendLine("## 数据接口试读（只读 query）")
            targets.providers.forEach { md.appendLine("- `$it`：${queryProvider(context, it)}") }
            md.appendLine()
        }

        return Report(md.toString(), emptyList(), emptyList(), "车机摸底（重点）${Build.MODEL} $now")
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    /** Read-only query of a content:// URI; returns the column names and first few rows (each cell cut to 60 chars). Waits at most 3 seconds. */
    fun queryProvider(context: Context, uri: String): String {
        val task = java.util.concurrent.FutureTask {
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

    /** Reads a system property (hidden API; returns an empty string if unreadable). */
    @SuppressLint("PrivateApi")
    fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    /** Whether a local port is listening (only tries to connect, then disconnects immediately). */
    fun probe(port: Int, timeoutMs: Int = 300): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
    }.isSuccess

    /** Notes the real version when the firmware fakes its version number (e.g. says 12 but is actually API 27). */
    private fun realVersionNote(): String {
        val real = DebugAccess.realAndroidVersion()
        return if (Build.VERSION.RELEASE.substringBefore('.') == real.substringBefore('.')) "" else "，实际是安卓 $real"
    }

    private fun onOff(on: Boolean) = if (on) "开" else "关"

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
}
