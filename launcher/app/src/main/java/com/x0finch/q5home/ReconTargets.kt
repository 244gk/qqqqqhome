package com.x0finch.q5home

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the "重点报告" (focused report) analyzes: which apps, which code to look for, which providers to trial-read.
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
) {
    class CodeGroup(val name: String, val pattern: Regex)
    class Site(val name: String, val call: Regex)

    companion object {
        const val URL = "https://raw.githubusercontent.com/244gk/qqqqqhome/HEAD/config/recon-targets.json"
        private const val MAX_PACKAGES = 12
        private const val MAX_GROUPS = 8
        private const val MAX_PROVIDERS = 20

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
              "strings": "original|car_info|speed|rpm|engine|fuel|mileage|temperature|PictureInPicture|float|small_?window|mini_?window|freeform|split|overlay|auto_?(enter|open|start)",
              "providers": [
                "content://com.wwc2.canmcusdk.CanbusProvider/original_screen",
                "content://com.wwc2.canmcusdk.CanbusProvider/car_info",
                "content://com.wwc2.main.provider.logic/logic"
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
              "nativeLibs": "avin|video|signal|canbus|mcu|serial|uart|source|switch",
              "settings": "original|origin|avin|canbus|can_|mcu|car|screen_mode|source|reverse|backcar|zlink|carplay",
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
            ReconTargets(
                packages, code, regex(o.optString("strings")), providers,
                o.optInt("maxMethods", 15).coerceIn(1, 40), o.optInt("maxEvents", 60).coerceIn(5, 200), source,
                sites, regex(o.optString("constants")), regex(o.optString("nativeLibs")), regex(o.optString("settings")),
            )
        }.getOrNull()

        /** Downloads the latest version from the repo; falls back to the built-in one. Call on a background thread. */
        fun load(url: String = URL): ReconTargets = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "q5home")
            }
            try {
                if (conn.responseCode !in 200..299) null
                else parse(conn.inputStream.bufferedReader().use { it.readText() }, "仓库 config/recon-targets.json")
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: DEFAULT
    }
}
