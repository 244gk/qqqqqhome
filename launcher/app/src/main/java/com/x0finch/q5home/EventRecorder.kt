package com.x0finch.q5home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Background recon recording: while q5home runs (it is the home app, so nearly always), it only LISTENS and writes what it
 * hears to [EventLog]. It never sends a broadcast, never writes a setting or provider, never changes the head unit.
 *
 * What is recorded:
 *  1. Broadcasts: built-in system ones plus every action-like name found in the vendor apps' code, with all their extras;
 *  2. Change notifications from the vendor apps' content providers (e.g. CanbusProvider): the changed address;
 *  3. System / Global / Secure settings: key, old value → new value;
 *  4. System properties named in the vendor apps' code (persist.*, sys.*, ...), checked every [PROP_POLL_MS];
 *  5. q5home's own process start (gaps in the log = the process was not running) and home screen shown / left;
 *  6. with usage access granted by the owner ("使用情况访问权限"): which app and page came to the front.
 *
 * Limits: since Android 8 only broadcasts sent to everyone reach a registered receiver; broadcasts sent to one app
 * (setPackage / setComponent) are not seen. Repeats of the same event within a second (provider changes: 10 seconds) are counted,
 * not logged one by one. Sticky broadcasts replayed on registration are old state and skipped. Events already known not to matter
 * ([ReconKnown.ignoreEvents]) are not recorded at all, and the file is written in batches ([EventLog]).
 */
object EventRecorder {
    const val PROP_POLL_MS = 2_000L
    private const val REPEAT_WINDOW_MS = 1_000L
    /** Provider change notifications can stream (car_info: 5–20 a second): folded over a longer window. */
    private const val DATA_WINDOW_MS = 10_000L
    private const val MAX_PER_SECOND = 40
    private const val MAX_ACTIONS = 1500
    private const val MAX_PROPS = 200

    /** System broadcasts that help line things up (power, screen, audio, volume / knob). */
    private val BUILTIN_ACTIONS = listOf(
        Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN, Intent.ACTION_USER_PRESENT,
        Intent.ACTION_CLOSE_SYSTEM_DIALOGS, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
        Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_HEADSET_PLUG, Intent.ACTION_MEDIA_BUTTON,
        "android.media.AUDIO_BECOMING_NOISY", "android.media.VOLUME_CHANGED_ACTION", "android.media.STREAM_MUTE_CHANGED_ACTION",
        "android.intent.action.ACTION_SHUTDOWN", "android.intent.action.REBOOT",
        "com.wwc2.APK_TO_MAIN", "com.wwc2.MAIN_TO_APK", "com.wwc2.MODULE_TO_MODULE",
    )
    /** Broadcasts after which the file is written at once (the head unit may lose power right after). */
    private val FLUSH_NOW = setOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN, "android.intent.action.ACTION_SHUTDOWN",
        "android.intent.action.REBOOT")

    /**
     * Events already known not to matter ([ReconKnown.ignoreEvents]: phone signal, the minute tick, battery, the car_info stream, …):
     * never registered and never written. The list downloaded with the latest report replaces the built-in one.
     */
    @Volatile private var ignore: List<Regex> = ReconKnown.DEFAULT_IGNORE.map { it.pattern }

    fun isIgnored(text: String) = ignore.any { it.containsMatchIn(text) }

    /** Picks up the known list downloaded with a report (call after [ReconKnown.load] saved a new copy). */
    fun reloadKnown(context: Context) {
        ignore = ReconKnown.cached(context).ignoreEvents.map { it.pattern }
    }
    private val BUILTIN_PROPS = listOf("sys.usb.state", "sys.usb.config", "sys.boot_completed", "service.adb.tcp.port")
    private val ACTION_LIKE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){2,}$")
    /** Calls whose surrounding strings are broadcast actions: building an IntentFilter / Intent, or sending a broadcast. */
    private val INTENT_CALLS = Regex("IntentFilter\\.(addAction|<init>)$|Intent\\.(<init>|setAction)$|\\.send(Ordered|Sticky)?Broadcast(AsUser)?$")
    private val PROP_LIKE = Regex("^(persist|sys|vendor|debug|init|dev|media|hw|ctl)\\.[A-Za-z0-9_.\\-]{2,80}$")

    @Volatile var running = false; private set
    private var handler: Handler? = null
    private val last = HashMap<String, Pair<Long, Int>>()
    private var secondStart = 0L
    private var inSecond = 0
    private var droppedInSecond = 0
    private val props = LinkedHashMap<String, String>()
    private val settings = HashMap<String, String?>()

    /** Called from the Application; does nothing under unit tests unless [force]. */
    fun start(context: Context, force: Boolean = false) {
        if (running || (!force && Build.FINGERPRINT == "robolectric")) return
        running = true
        val app = context.applicationContext
        val thread = HandlerThread("q5-recorder", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val h = Handler(thread.looper)
        handler = h
        h.post { runCatching { begin(app, h) }.onFailure { EventLog.append(app, "出错", "开始记录失败：$it") } }
    }

    private fun begin(app: Context, h: Handler) {
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        reloadKnown(app)
        EventLog.append(app, "启动", "q5home 进程启动（版本 $version，pid ${Process.myPid()}）", now = true)
        // The head unit (or the ignition) just started: prepare the recon report in the background, ready for the QR code.
        ReconReport.schedule(app)
        register(app, h, BUILTIN_ACTIONS)
        watchSettings(app, h)
        val providers = watchProviders(app, h)
        h.postDelayed(object : Runnable {
            override fun run() {
                runCatching { pollProps(app) }
                runCatching { pollForeground(app) }
                if (++ticks % (EventLog.FLUSH_MS / PROP_POLL_MS).toInt() == 0) EventLog.flush()
                h.postDelayed(this, PROP_POLL_MS)
            }
        }, PROP_POLL_MS)
        // Reading the vendor APKs takes tens of seconds the first time: done on its own thread so the recorder thread keeps
        // writing events with their real times meanwhile; the result is cached per app version.
        Thread({
            val found = runCatching { synchronized(ReconReport.HEAVY) { discover(app) } }.getOrDefault(emptyList<String>() to emptyList())
            h.post {
                val actions = found.first.filter { it !in BUILTIN_ACTIONS && !isIgnored(it) }
                register(app, h, actions)
                synchronized(props) { (BUILTIN_PROPS + found.second).distinct().forEach { props[it] = Recon.prop(it) } }
                EventLog.append(app, "启动", "开始记录：广播 ${BUILTIN_ACTIONS.size + actions.size} 种 · 数据接口 $providers 个 · " +
                    "系统设置 3 张表 · 系统属性 ${props.size} 个 · 前台应用 ${if (usageAllowed(app)) "已开启" else "没开启（要「使用情况访问权限」）"}" +
                    " · 已知不需要的不记：${ignore.size} 类", now = true)
            }
        }, "q5-recorder-discover").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Is "使用情况访问权限" (usage access) granted to q5home? Granted by the owner in system settings; q5home never changes it. */
    fun usageAllowed(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private var foregroundSince = 0L
    private var ticks = 0

    /** Which app / page came to the front, from the system's usage events (only with usage access). */
    private fun pollForeground(app: Context) {
        if (!usageAllowed(app)) return
        val usm = app.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        if (foregroundSince == 0L) foregroundSince = now - PROP_POLL_MS
        val events = usm.queryEvents(foregroundSince, now) ?: return
        val e = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp < foregroundSince) continue
            @Suppress("DEPRECATION")
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val text = "${e.packageName}/${e.className ?: "?"}"
                EventLog.append(app, "前台", text, e.timeStamp)
            }
        }
        foregroundSince = now
    }

    /** q5home's own home screen shown / left (to line up with what the owner did). */
    fun note(context: Context, kind: String, text: String) {
        if (running) handler?.post { log(context.applicationContext, kind, text, key = "$kind $text") }
    }

    private fun register(app: Context, h: Handler, actions: List<String>) {
        if (actions.isEmpty()) return
        val filter = IntentFilter().apply { actions.filterNot { isIgnored(it) }.forEach { addAction(it) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // The system replays the last "sticky" broadcast on registration: that is old state, not an event.
                if (isInitialStickyBroadcast) return
                val text = describe(intent)
                log(app, "广播", text, key = text)
                if (intent.action in FLUSH_NOW) EventLog.flush()
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, null, h, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter, null, h)
        }.onFailure { EventLog.append(app, "出错", "注册广播失败：$it") }
    }

    fun describe(intent: Intent): String {
        val parts = mutableListOf(intent.action ?: "(无动作)")
        intent.data?.let { parts += "data=$it" }
        intent.categories?.takeIf { it.isNotEmpty() }?.let { parts += "category=${it.joinToString()}" }
        intent.extras?.let { parts += extras(it) }
        return parts.joinToString(" · ")
    }

    fun extras(b: Bundle): String = runCatching {
        b.keySet().sorted().joinToString(", ") { k ->
            @Suppress("DEPRECATION")
            val v = b.get(k)
            "$k=" + when (v) {
                null -> "null"
                is IntArray -> v.contentToString()
                is LongArray -> v.contentToString()
                is ByteArray -> v.joinToString(" ", "[", "]") { String.format("%02X", it) }
                is BooleanArray -> v.contentToString()
                is FloatArray -> v.contentToString()
                is Array<*> -> v.contentToString()
                is Bundle -> "{${extras(v)}}"
                else -> v.toString()
            }.take(120)
        }
    }.getOrElse { "（参数读不了：${it.javaClass.simpleName}）" }

    private fun watchProviders(app: Context, h: Handler): Int {
        val pm = app.packageManager
        val authorities = linkedSetOf<String>()
        val packages = runCatching { pm.getInstalledPackages(0).map { it.packageName } }.getOrDefault(emptyList())
            .filter { it.startsWith("com.wwc2.") || it in ReconTargets.DEFAULT.packages }
        for (pkg in packages) {
            val info = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS)
            }.getOrNull() ?: continue
            info.providers.orEmpty().forEach { p -> p.authority?.split(';')?.forEach { authorities += it } }
        }
        ReconTargets.DEFAULT.providers.forEach { Uri.parse(it).authority?.let { a -> authorities += a } }
        for (a in authorities) {
            runCatching {
                app.contentResolver.registerContentObserver(Uri.parse("content://$a"), true, object : ContentObserver(h) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        val text = (uri ?: Uri.parse("content://$a")).toString()
                        log(app, "数据变化", text, key = text)
                        // Ignition on / off: (re)prepare the report a little later.
                        if (text.endsWith("/acc_status")) ReconReport.schedule(app)
                    }
                })
            }
        }
        return authorities.size
    }

    private val TABLES = listOf(
        "system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI,
    )

    private fun watchSettings(app: Context, h: Handler) {
        for ((table, uri) in TABLES) {
            readTable(app, uri).forEach { (k, v) -> settings["$table/$k"] = v }
            runCatching {
                app.contentResolver.registerContentObserver(uri, true, object : ContentObserver(h) {
                    override fun onChange(selfChange: Boolean, changed: Uri?) {
                        val key = changed?.lastPathSegment?.takeIf { changed.pathSegments.size >= 2 }
                        if (key != null) settingChanged(app, table, key, readOne(app, table, key))
                        else readTable(app, uri).forEach { (k, v) -> settingChanged(app, table, k, v) }
                    }
                })
            }
        }
    }

    private fun settingChanged(app: Context, table: String, key: String, value: String?) {
        val full = "$table/$key"
        val old = settings[full]
        if (settings.containsKey(full) && old == value) return
        settings[full] = value
        log(app, "设置", "$full：${old ?: "（无）"} → ${value ?: "（无）"}", key = full)
    }

    private fun readOne(app: Context, table: String, key: String): String? = runCatching {
        val cr = app.contentResolver
        when (table) {
            "system" -> Settings.System.getString(cr, key)
            "global" -> Settings.Global.getString(cr, key)
            else -> Settings.Secure.getString(cr, key)
        }
    }.getOrNull()

    private fun readTable(app: Context, uri: Uri): Map<String, String?> = runCatching {
        val out = HashMap<String, String?>()
        app.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out[it] = c.getString(1) }
        }
        out as Map<String, String?>
    }.getOrDefault(emptyMap())

    private fun pollProps(app: Context) {
        val changes = mutableListOf<Triple<String, String, String>>()
        synchronized(props) {
            for ((k, old) in props) {
                val now = Recon.prop(k)
                if (now != old) { changes += Triple(k, old, now); props[k] = now }
            }
        }
        changes.forEach { (k, old, now) -> log(app, "属性", "$k：${old.ifEmpty { "（空）" }} → ${now.ifEmpty { "（空）" }}", key = k) }
    }

    /**
     * Action-like and property-like names in the code of the vendor apps (the report's target packages plus every com.wwc2.* app),
     * cached in q5home's files and only re-read when one of those apps changes version.
     */
    private fun discover(app: Context): Pair<List<String>, List<String>> {
        val pm = app.packageManager
        val infos = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            .filter { it.packageName.startsWith("com.wwc2.") || it.packageName in ReconTargets.DEFAULT.packages }
            .sortedBy { it.packageName }
        @Suppress("DEPRECATION")
        val key = "v2|" + infos.joinToString(",") { "${it.packageName}:${it.versionCode}:${it.lastUpdateTime}" }
        val cache = File(File(app.filesDir, "recon"), "discovered.json")
        runCatching {
            val o = JSONObject(cache.readText())
            if (o.getString("key") == key) {
                fun list(name: String) = o.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) } }
                return list("actions") to list("props")
            }
        }
        val actions = sortedSetOf<String>()
        val propNames = sortedSetOf<String>()
        for (p in infos) {
            val apk = p.applicationInfo?.sourceDir?.let { File(it) } ?: continue
            ApkStrings.fromApk(apk, limit = 5000, filter = { s ->
                s.length <= 100 && (isAction(s) || PROP_LIKE.matches(s))
            }).forEach { s -> if (PROP_LIKE.matches(s) && !s.endsWith('.')) propNames += s else actions += s }
            // Any dotted name the vendor code puts into an IntentFilter / Intent or broadcasts, whatever its case
            // (e.g. "wwc2.com.wangyong.car", which switches to the OEM screen, is all lower case).
            DexScan.sitesApk(apk, { INTENT_CALLS.containsMatchIn(it) }, max = 3000, maxStrings = 40).forEach { site ->
                site.strings.filterTo(actions) { ACTION_LIKE.matches(it) && it.length <= 100 && !it.startsWith("android.permission.") }
            }
        }
        val result = actions.take(MAX_ACTIONS) to propNames.take(MAX_PROPS)
        runCatching {
            cache.parentFile?.mkdirs()
            cache.writeText(JSONObject().put("key", key).put("actions", JSONArray(result.first))
                .put("props", JSONArray(result.second)).toString())
        }
        return result
    }

    /** Looks like a broadcast action: dotted, and the last part is in capitals (ACTION_X) or the name mentions action / broadcast. */
    fun isAction(s: String): Boolean {
        if (!ACTION_LIKE.matches(s) || s.startsWith("android.permission.") || s.startsWith("java.")) return false
        val last = s.substringAfterLast('.')
        return (last.length >= 3 && last == last.uppercase() && last.any { it.isLetter() }) ||
            s.contains("action", ignoreCase = true) || s.contains("broadcast", ignoreCase = true)
    }

    /** Writes one line, folding repeats of the same [key] within a second and capping the rate. Runs on the recorder thread. */
    private fun log(app: Context, kind: String, text: String, key: String) {
        if (isIgnored(text)) return
        val now = System.currentTimeMillis()
        val prev = last[key]
        val window = if (kind == "数据变化") DATA_WINDOW_MS else REPEAT_WINDOW_MS
        if (prev != null && now - prev.first < window) { last[key] = prev.first to prev.second + 1; return }
        if (now - secondStart >= 1000) {
            if (droppedInSecond > 0) EventLog.append(app, "提示", "上一秒事件太多，跳过了 $droppedInSecond 条")
            secondStart = now; inSecond = 0; droppedInSecond = 0
        }
        if (++inSecond > MAX_PER_SECOND) { droppedInSecond++; return }
        val repeats = prev?.second ?: 0
        last[key] = now to 0
        if (last.size > 5000) last.clear()
        val note = if (repeats > 0) "（之前 ${window / 1000} 秒内还重复了 $repeats 次）" else ""
        EventLog.append(app, kind, text + note, now)
    }
}
