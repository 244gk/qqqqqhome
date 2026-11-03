package com.x0finch.q5home

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the recon report analyzes: which apps, which code to look for, which providers to trial-read.
 * On submit the latest config/recon-targets.json is downloaded from the repo; if that fails, the identical built-in copy is used.
 * So changing the analysis focus only needs a repo file change, no head-unit update.
 * It can only decide "what to read": nothing here writes to or switches anything on the head unit.
 */
data class ReconTargets(
    val packages: List<String>,
    val code: List<CodeGroup>,
    /** Additionally list string constants in each app matching this regex; null means none are listed. */
    val strings: Regex?,
    /** content:// URIs to query read-only (only ones starting with content://com.wwc2 are accepted). */
    val providers: List<String>,
    val maxMethods: Int,
    val maxEvents: Int,
    /** Where it came from: repo / built-in. */
    val source: String,
    /** List every method that calls a method whose "Class.name" matches (broadcast sends, page / service starts). */
    val sites: List<Site> = emptyList(),
    /** Constant tables: static fields of classes whose full name matches. */
    val constants: Regex? = null,
    /** Native libraries (.so file names) whose exported functions and device paths are listed. */
    val nativeLibs: Regex? = null,
    /** System / Global / Secure settings whose key matches: current values listed. */
    val settings: Regex? = null,
    /** Only methods / classes whose full name matches are analyzed: the vendor's own code, not bundled libraries. */
    val vendorClasses: Regex? = null,
    /** (content:// URI, method) read with ContentResolver.call; only getter methods named "xxx_info" are accepted. */
    val calls: List<Pair<String, String>> = emptyList(),
) {
    class CodeGroup(val name: String, val pattern: Regex)
    class Site(val name: String, val call: Regex)

    companion object {
        const val URL = "https://raw.githubusercontent.com/244gk/qqqqqhome/HEAD/config/recon-targets.json"
        private const val MAX_PACKAGES = 12
        private const val MAX_GROUPS = 8
        private const val MAX_PROVIDERS = 20
        /** ContentResolver.call methods allowed: only "xxx_info" getters (CanbusProvider.call returns data for these and writes nothing). */
        val CALL_METHOD = Regex("^[a-z]{2,20}_info$")

        fun cacheFile(context: android.content.Context) = java.io.File(java.io.File(context.filesDir, "recon"), "targets.json")

        /** The copy kept from the last download, or the built-in one (no network). */
        fun cached(context: android.content.Context): ReconTargets =
            runCatching { parse(cacheFile(context).readText(), "车机上次下载的 config/recon-targets.json") }.getOrNull() ?: DEFAULT

        /** Kept identical to config/recon-targets.json in the repo (used when the download fails). */
        val DEFAULT_JSON = """
            {
              "packages": [
                "com.wwc2.launcher",
                "com.wwc2.main",
                "com.wwc2.mainui",
                "com.wwc2.canmcusdk",
                "com.wwc2.canbusapk",
                "com.zjinnova.zlink"
              ],
              "code": [
                {
                  "name": "原车切换广播（主页「原车媒体」→ canmcusdk → MCU）",
                  "pattern": "wwc2\\.com\\.wangyong\\.car|wy\\.switch\\.system|AudiMedia|ACTION_GOTO_CAR|SWITCH_ORIGNIAL|SWITCH_ARM|sendBroadCast\\w*Media|McuManager\\${"$"}1\\.onReceive|McuManager\\${"$"}3\\.handleMessage"
                },
                {
                  "name": "原车画面切换",
                  "pattern": "original_screen|enter_original|switch_original|original_unlock|source_avin|media_box|switchOriginalMenu|setOriginal|AvinLogic\\.(switch|set|enter|change|open|start)"
                },
                {
                  "name": "仪表 / 车辆数据（车速、转速、车门等）",
                  "pattern": "car_info|getCanMcuCarInfo|CarInfoLogic|canbusDataFromMCU|notifyCarInfo|uri_carinfo"
                },
                {
                  "name": "ZLINK 小窗 / 画中画 / 悬浮窗",
                  "pattern": "PictureInPicture|picture_in_picture|pip_|float_?window|floatview|small_?window|mini_?window|freeform|split_?screen|TYPE_APPLICATION_OVERLAY|SYSTEM_ALERT_WINDOW|auto_?(enter|open|start)"
                }
              ],
              "providers": [
                "content://com.wwc2.canmcusdk.CanbusProvider/original_screen",
                "content://com.wwc2.canmcusdk.CanbusProvider/car_info",
                "content://com.wwc2.main.provider.logic/logic"
              ],
              "calls": [
                "content://com.wwc2.canmcusdk.CanbusProvider/car_info#car_info",
                "content://com.wwc2.canmcusdk.CanbusProvider/air_info#air_info",
                "content://com.wwc2.canmcusdk.CanbusProvider/radar_info#radar_info"
              ],
              "sites": [
                {
                  "name": "发广播的地方",
                  "call": "\\.(sendBroadcast|sendBroadcastAsUser|sendOrderedBroadcast|sendStickyBroadcast)${"$"}"
                },
                {
                  "name": "打开页面 / 服务的地方",
                  "call": "\\.(startActivity|startActivityAsUser|startService|startForegroundService|bindService|setComponent|setClassName)${"$"}|ComponentName\\.<init>${"$"}"
                }
              ],
              "constants": "AvinInterface|VideoSource|CanBusDefine|APK_TO_MAIN|MAIN_TO_APK|MODULE_TO_MODULE|Define${"$"}|Defines${"$"}|Constant|Const${"$"}|Command|Cmd${"$"}|\\${"$"}Stub${"$"}",
              "nativeLibs": "avin|video|signal|canbus|mcu|serial|uart|switch",
              "settings": "original|origin|avin|canbus|can_|mcu|car|screen_mode|source|reverse|backcar|zlink|carplay",
              "vendorClasses": "^(com\\.wwc2|wwc2\\.|com\\.baony)",
              "maxMethods": 25,
              "maxEvents": 60
            }
        """.trimIndent()

        val DEFAULT: ReconTargets get() = parse(DEFAULT_JSON, "内置")!!

        /** Parses; returns null on bad format. Entries with an invalid regex are skipped. Counts and lengths are capped so the report can't blow up. */
        fun parse(json: String, source: String): ReconTargets? = runCatching {
            val o = JSONObject(json)
            fun regex(s: String?) = s?.takeIf { it.isNotBlank() && it.length <= 500 }
                ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
            val packages = o.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                .filter { Regex("^[a-zA-Z0-9_.]{3,100}$").matches(it) }.distinct().take(MAX_PACKAGES)
            val code = o.optJSONArray("code")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val g = a.getJSONObject(i)
                    regex(g.optString("pattern"))?.let { CodeGroup(g.optString("name").ifBlank { "代码" }.take(40), it) }
                }
            }.orEmpty().take(MAX_GROUPS)
            val providers = o.optJSONArray("providers")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                .filter { it.startsWith("content://com.wwc2") && it.length <= 200 }.distinct().take(MAX_PROVIDERS)
            val sites = o.optJSONArray("sites")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val g = a.getJSONObject(i)
                    regex(g.optString("call"))?.let { Site(g.optString("name").ifBlank { "调用" }.take(40), it) }
                }
            }.orEmpty().take(MAX_GROUPS)
            val calls = o.optJSONArray("calls")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                .mapNotNull { c ->
                    val uri = c.substringBefore('#'); val method = c.substringAfter('#', "")
                    (uri to method).takeIf { uri.startsWith("content://com.wwc2") && uri.length <= 200 && CALL_METHOD.matches(method) }
                }.distinct().take(MAX_PROVIDERS)
            ReconTargets(
                packages, code, regex(o.optString("strings")), providers,
                o.optInt("maxMethods", 15).coerceIn(1, 40), o.optInt("maxEvents", 60).coerceIn(5, 200), source,
                sites, regex(o.optString("constants")), regex(o.optString("nativeLibs")), regex(o.optString("settings")),
                o.optString("vendorClasses").takeIf { it.isNotBlank() && it.length <= 500 }?.let { runCatching { Regex(it) }.getOrNull() },
                calls,
            )
        }.getOrNull()

        /**
         * Downloads the latest version from the repo and keeps a copy in [cache]; offline (the head unit's Wi-Fi drops often) the
         * copy from last time is used, then the built-in one. Call on a background thread.
         */
        fun load(url: String = URL, cache: java.io.File? = null): ReconTargets = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "q5home")
            }
            try {
                if (conn.responseCode !in 200..299) null
                else {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(text, "仓库 config/recon-targets.json")?.also {
                        cache?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(text) } }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
            ?: cache?.let { f -> runCatching { parse(f.readText(), "车机上次下载的 config/recon-targets.json") }.getOrNull() }
            ?: DEFAULT
    }
}

/**
 * What is already known about the head unit (from earlier reports): `config/recon-known.json` in the repo, downloaded when the
 * report is generated. Anything listed here is left out of the report, so each report only shows what is new. Updated after
 * reading each report; no app release needed. Without a download nothing counts as known and the report says so.
 */
class ReconKnown(
    /** Build.DISPLAY last seen. */
    val system: String?,
    /** Vendor app versions last seen; an app with the same version has nothing new in its components or strings. */
    val versions: Map<String, String>,
    /** Disassembled methods already reported (full "Class.method" names). */
    val methods: Set<String>,
    /** content:// URIs whose query result is already known. */
    val providers: Set<String>,
    /** Code strings already reported. */
    val strings: Set<String>,
    /** Event-log lines matching these are known noise: counted, not listed. */
    val ignoreEvents: List<ReconTargets.CodeGroup>,
    /** Where it came from (null = nothing known: the download failed). */
    val source: String?,
    /** Report lines already seen, as [lineHash]: a line that comes out the same again is left out. */
    val lines: Set<String> = emptySet(),
) {
    fun knows(line: String) = lineHash(line) in lines

    companion object {
        const val URL = "https://raw.githubusercontent.com/244gk/qqqqqhome/HEAD/config/recon-known.json"

        /**
         * Events already known not to matter, kept identical to "ignoreEvents" in config/recon-known.json (tests check). The background
         * recorder never writes these to its file; a newer list downloaded with a report replaces this one.
         */
        val DEFAULT_IGNORE = listOf(
            ReconTargets.CodeGroup("手机信号 / 网络 / SIM / Wi-Fi 开关",
                Regex("SIG_STR|SERVICE_STATE|ANY_DATA_STATE|SUBINFO|DEFAULT_DATA_SUBSCRIPTION|SIM_STATE_CHANGED|SUBSCRIPTION_PHONE_STATE|RADIO_CAPABILITY|CLEAR_DNS_CACHE|global/wifi_on|RSSI_CHANGED|SCAN_RESULTS|LOCATED_PLMN_CHANGED|global/wifi_saved_state|global/auto_time")),
            ReconTargets.CodeGroup("每分钟的时钟广播", Regex("TIME_TICK|TIME_SET|TIMEZONE_CHANGED")),
            ReconTargets.CodeGroup("电池状态", Regex("BATTERY_CHANGED")),
            ReconTargets.CodeGroup("USB / HDMI 状态", Regex("USB_STATE|HDMI_PLUGGED")),
            ReconTargets.CodeGroup("车辆数据 car_info 变化（每秒 5–20 次，一直在推）", Regex("CanbusProvider/car_info")),
            ReconTargets.CodeGroup("WiFi / 网络连接过程", Regex("android\\.net\\.wifi\\.|CONNECTIVITY_CHANGE|INET_CONDITION_ACTION|CARRIER_CONFIG_CHANGED|POWER_SAVE_TEMP_WHITELIST")),
            ReconTargets.CodeGroup("GPS 开关 / 定位状态", Regex("GPS_ENABLED_CHANGE|GPS_FIX_CHANGE|HIGH_POWER_REQUEST_CHANGE")),
            ReconTargets.CodeGroup("切换前台时厂商的重复通知", Regex("CURRENT_PACKAGENAME|CurPkgName=|OldPkgName=|CurClsName=|OldClsName=")),
            ReconTargets.CodeGroup("触摸屏幕", Regex("com\\.wwc2\\.touch\\.event|status=true, type=17")),
        )

        val NONE = ReconKnown(null, emptyMap(), emptySet(), emptySet(), emptySet(), DEFAULT_IGNORE, null)

        /** Short hash of one report line (first 16 hex digits of SHA-1 of the trimmed line), as stored in "lines". */
        fun lineHash(line: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(line.trim().toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { String.format("%02x", it) }

        /** The last list downloaded, kept on the head unit: used when offline and by the background recorder. */
        fun cacheFile(context: android.content.Context) = java.io.File(java.io.File(context.filesDir, "recon"), "known.json")

        /** The cached list (or [NONE]); the recorder reads its "ignoreEvents". */
        fun cached(context: android.content.Context): ReconKnown =
            runCatching { parse(cacheFile(context).readText(), "车机上次下载的 config/recon-known.json") }.getOrNull() ?: NONE

        fun parse(json: String, source: String): ReconKnown? = runCatching {
            val o = org.json.JSONObject(json)
            fun list(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val versions = o.optJSONObject("versions")?.let { v -> v.keys().asSequence().associateWith { v.getString(it) } }.orEmpty()
            val ignore = o.optJSONArray("ignoreEvents")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val g = a.getJSONObject(i)
                    runCatching { Regex(g.getString("pattern")) }.getOrNull()?.let { ReconTargets.CodeGroup(g.optString("name").take(60), it) }
                }
            }.orEmpty().ifEmpty { DEFAULT_IGNORE }
            ReconKnown(o.optString("system").ifBlank { null }, versions, list("methods").toSet(), list("providers").toSet(),
                list("strings").toSet(), ignore, source, list("lines").toSet())
        }.getOrNull()

        /**
         * Downloads the latest list from the repo and keeps a copy in [cache]; offline, the copy from last time is used, and only
         * without either nothing counts as known ([NONE]). Call on a background thread.
         */
        fun load(url: String = URL, cache: java.io.File? = null): ReconKnown = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "q5home")
            }
            try {
                if (conn.responseCode !in 200..299) null
                else {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(text, "仓库 config/recon-known.json")?.also {
                        cache?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(text) } }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
            ?: cache?.let { f -> runCatching { parse(f.readText(), "车机上次下载的 config/recon-known.json（这次没下载到）") }.getOrNull() }
            ?: NONE
    }
}
