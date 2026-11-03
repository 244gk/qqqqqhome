package com.x0finch.q5home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A favorite-app tile on the home screen: icon key (see [Icons]; null means use the app's own icon) + target app (see [Targets]). */
data class DockItem(val icon: String?, val target: String) {
    fun toJson(): JSONObject = JSONObject().put("icon", icon ?: JSONObject.NULL).put("target", target)

    companion object {
        /** Accepts either `"target"` or `{"icon": "files", "target": "target"}`. */
        fun parse(value: Any): DockItem = when (value) {
            is JSONObject -> DockItem(
                value.optString("icon").ifBlank { null }?.takeIf { it != "null" },
                value.getString("target"),
            )
            else -> DockItem(null, value.toString())
        }
    }
}

/**
 * Launcher configuration. Stored in SharedPreferences; changes made in the car by long-pressing tiles are written here directly.
 *
 * You can also push a config.json via adb for bulk settings (e.g. after discovering the OEM screen-switch component):
 *   adb push config.json /sdcard/Android/data/com.x0finch.q5home/files/config.json
 * When the file changes, it is merged in the next time you return to the home screen. The active config is written to current.json in the same directory for inspection via adb pull.
 * See launcher/config.example.json for fields and [Targets] for target syntax.
 */
class Config(private val context: Context) {

    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    var carplay: String? = null; private set
    var oem: String? = null; private set
    var dock: List<DockItem> = emptyList(); private set
    var autoCarplay = false; private set
    var wallpaper = Wallpapers.DAILY; private set
    /** Auto-rotate frequency (minutes). */
    var wallpaperInterval = Wallpapers.DEFAULT_INTERVAL; private set
    /** When auto-rotating, split day / night wallpapers by sunrise and sunset. */
    var wallpaperSun = false; private set
    /** Show GPS speed, heading and altitude below the home screen clock. */
    var homeGps = true; private set
    /** Appearance: auto (follows sunrise/sunset) / light / dark. */
    var appearance = APPEARANCE_AUTO; private set
    /** Auto-download new wallpapers when on Wi-Fi. */
    var onlineWallpapers = true; private set
    /** If set, fetch random images via the Unsplash API; otherwise use the curated list in the repo. Can only be set via config.json. */
    var unsplashKey: String? = null; private set
    var wallpaperQuery = OnlineWallpapers.DEFAULT_QUERY; private set
    var wallpaperFeed = OnlineWallpapers.DEFAULT_FEED; private set
    var title = DEFAULT_TITLE; private set

    /** Loads the config. Returns the config.json error message, or null if there is none. */
    fun reload(): String? {
        val error = importFile()
        // When not set manually (or left blank), re-detect every time so a later-installed ZLINK / DiPlay is found too.
        carplay = prefs.getString(KEY_CARPLAY, null)?.ifBlank { null } ?: Targets.findCarPlay(context)
        // Once: the old auto-detection could store an app whose name merely contains 原车 / oem; the OEM tile now opens the stock
        // home "主页" (the owner's choice). Later manual choices are kept.
        if (!prefs.getBoolean(KEY_OEM_HOME_FIX, false)) {
            val home = Targets.stockHome(context)
            prefs.edit().apply { if (home != null) putString(KEY_OEM, home) }.putBoolean(KEY_OEM_HOME_FIX, true).apply()
        }
        oem = prefs.getString(KEY_OEM, null)?.ifBlank { null } ?: Targets.findOem(context)
        dock = prefs.getString(KEY_DOCK, null)?.let(::parseDock) ?: defaultDock()
        autoCarplay = prefs.getBoolean(KEY_AUTO_CARPLAY, false)
        wallpaper = prefs.getString(KEY_WALLPAPER, Wallpapers.DAILY)!!
        wallpaperInterval = prefs.getInt(KEY_INTERVAL, Wallpapers.DEFAULT_INTERVAL).coerceIn(5, 1440)
        wallpaperSun = prefs.getBoolean(KEY_SUN, false)
        homeGps = prefs.getBoolean(KEY_HOME_GPS, true)
        appearance = prefs.getString(KEY_APPEARANCE, null)?.takeIf { it in APPEARANCES } ?: APPEARANCE_AUTO
        onlineWallpapers = prefs.getBoolean(KEY_ONLINE, true)
        unsplashKey = prefs.getString(KEY_UNSPLASH_KEY, null)?.ifBlank { null }
        wallpaperQuery = prefs.getString(KEY_QUERY, null)?.ifBlank { null } ?: OnlineWallpapers.DEFAULT_QUERY
        wallpaperFeed = prefs.getString(KEY_FEED, null)?.ifBlank { null } ?: OnlineWallpapers.DEFAULT_FEED
        title = prefs.getString(KEY_TITLE, DEFAULT_TITLE)!!
        writeCurrent()
        return error
    }

    /** Default favorite apps: Files, Settings. */
    private fun defaultDock(): List<DockItem> = listOfNotNull(
        Targets.findFiles(context)?.let { DockItem("files", it) },
        DockItem("settings", Targets.SYSTEM_SETTINGS),
    )

    fun setCarplay(spec: String) = edit { putString(KEY_CARPLAY, spec) }
    fun setOem(spec: String) = edit { putString(KEY_OEM, spec) }
    fun setAutoCarplay(on: Boolean) = edit { putBoolean(KEY_AUTO_CARPLAY, on) }
    fun setWallpaper(key: String) = edit { putString(KEY_WALLPAPER, key) }
    fun setWallpaperInterval(minutes: Int) = edit { putInt(KEY_INTERVAL, minutes) }
    fun setWallpaperSun(on: Boolean) = edit { putBoolean(KEY_SUN, on) }
    fun setHomeGps(on: Boolean) = edit { putBoolean(KEY_HOME_GPS, on) }
    fun setAppearance(value: String) = edit { putString(KEY_APPEARANCE, value) }
    fun setOnlineWallpapers(on: Boolean) = edit { putBoolean(KEY_ONLINE, on) }

    fun setDock(items: List<DockItem>) =
        edit { putString(KEY_DOCK, JSONArray(items.map { it.toJson() }).toString()) }
    fun addToDock(target: String) {
        if (dock.none { it.target == target }) {
            setDock(dock + DockItem(Icons.guess(Targets.packageOf(target), Targets.label(context, target)), target))
        }
    }
    /** Changes the app but keeps the icon: this is the "only change the app id later" idea. */
    fun replaceTarget(index: Int, target: String) =
        setDock(dock.toMutableList().also { it[index] = it[index].copy(target = target) })
    fun setIcon(index: Int, icon: String?) =
        setDock(dock.toMutableList().also { it[index] = it[index].copy(icon = icon) })
    fun removeFromDock(index: Int) = setDock(dock.toMutableList().also { it.removeAt(index) })
    fun moveInDock(index: Int, delta: Int) =
        setDock(dock.toMutableList().also { it.add(index + delta, it.removeAt(index)) })

    /** Reset to defaults: clear manual settings made in the car and fall back to config.json (if any) and auto-detection. */
    fun reset() {
        // Clear the import record too: if config.json is still there it gets re-imported (so the Unsplash key etc. isn't lost).
        prefs.edit().clear().apply()
        reload()
    }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        reload()
    }

    private fun parseDock(json: String): List<DockItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { DockItem.parse(arr.get(it)) }
    }

    private fun importFile(): String? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val file = File(dir, FILE)
        if (!file.isFile || file.lastModified() == prefs.getLong(KEY_IMPORTED, 0)) return null
        return try {
            val json = JSONObject(file.readText())
            val e = prefs.edit()
            if (json.has("carplay")) e.putString(KEY_CARPLAY, json.getString("carplay"))
            if (json.has("oem")) e.putString(KEY_OEM, json.getString("oem"))
            if (json.has("title")) e.putString(KEY_TITLE, json.getString("title"))
            if (json.has("wallpaper")) e.putString(KEY_WALLPAPER, json.getString("wallpaper"))
            if (json.has("wallpaperInterval")) e.putInt(KEY_INTERVAL, json.getInt("wallpaperInterval"))
            if (json.has("wallpaperSun")) e.putBoolean(KEY_SUN, json.getBoolean("wallpaperSun"))
            if (json.has("homeGps")) e.putBoolean(KEY_HOME_GPS, json.getBoolean("homeGps"))
            if (json.has("appearance")) e.putString(KEY_APPEARANCE, json.getString("appearance"))
            if (json.has("onlineWallpapers")) e.putBoolean(KEY_ONLINE, json.getBoolean("onlineWallpapers"))
            if (json.has("unsplashKey")) e.putString(KEY_UNSPLASH_KEY, json.getString("unsplashKey"))
            if (json.has("wallpaperQuery")) e.putString(KEY_QUERY, json.getString("wallpaperQuery"))
            if (json.has("wallpaperFeed")) e.putString(KEY_FEED, json.getString("wallpaperFeed"))
            if (json.has("autoCarplay")) e.putBoolean(KEY_AUTO_CARPLAY, json.getBoolean("autoCarplay"))
            if (json.has("dock")) {
                // Parse everything first; if the format is wrong, import nothing.
                val items = parseDock(json.getJSONArray("dock").toString())
                e.putString(KEY_DOCK, JSONArray(items.map { it.toJson() }).toString())
            }
            e.putLong(KEY_IMPORTED, file.lastModified()).apply()
            null
        } catch (ex: Exception) {
            // Remember the timestamp so the same error isn't reported every time we return to the home screen.
            prefs.edit().putLong(KEY_IMPORTED, file.lastModified()).apply()
            ex.message ?: ex.toString()
        }
    }

    private fun writeCurrent() {
        val dir = context.getExternalFilesDir(null) ?: return
        val json = JSONObject()
            .put("carplay", carplay ?: JSONObject.NULL)
            .put("oem", oem ?: JSONObject.NULL)
            .put("dock", JSONArray(dock.map { it.toJson() }))
            .put("autoCarplay", autoCarplay)
            .put("wallpaper", wallpaper)
            .put("wallpaperInterval", wallpaperInterval)
            .put("wallpaperSun", wallpaperSun)
            .put("homeGps", homeGps)
            .put("appearance", appearance)
            .put("onlineWallpapers", onlineWallpapers)
            // Don't write out the key itself, only whether it's configured.
            .put("unsplashKey", if (unsplashKey != null) "(已配置)" else JSONObject.NULL)
            .put("wallpaperQuery", wallpaperQuery)
            .put("wallpaperFeed", wallpaperFeed)
            .put("title", title)
        runCatching { File(dir, CURRENT_FILE).writeText(json.toString(2)) }
    }

    companion object {
        const val FILE = "config.json"
        const val CURRENT_FILE = "current.json"
        private const val DEFAULT_TITLE = "Audi Q5"
        private const val KEY_CARPLAY = "carplay"
        private const val KEY_OEM = "oem"
        private const val KEY_OEM_HOME_FIX = "oem_home_fix_v1"
        private const val KEY_DOCK = "dock_v2"
        private const val KEY_AUTO_CARPLAY = "auto_carplay"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_INTERVAL = "wallpaper_interval_min"
        private const val KEY_SUN = "wallpaper_sun"
        private const val KEY_HOME_GPS = "home_gps"
        private const val KEY_APPEARANCE = "appearance"
        const val APPEARANCE_AUTO = "auto"
        const val APPEARANCE_LIGHT = "light"
        const val APPEARANCE_DARK = "dark"
        val APPEARANCES = listOf(APPEARANCE_AUTO, APPEARANCE_LIGHT, APPEARANCE_DARK)
        private const val KEY_TITLE = "title"
        private const val KEY_ONLINE = "online_wallpapers"
        private const val KEY_UNSPLASH_KEY = "unsplash_key"
        private const val KEY_QUERY = "wallpaper_query"
        private const val KEY_FEED = "wallpaper_feed"
        private const val KEY_IMPORTED = "imported_mtime"
    }
}
