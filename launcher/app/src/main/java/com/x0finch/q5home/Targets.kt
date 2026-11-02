package com.x0finch.q5home

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

/**
 * The target a tile points to, described as a single string (easy to put in config.json):
 *  - `package`                    launch the app's default entry point
 *  - `package/Class` or `package/.Class`  launch a specific Activity (the OEM screen switch may not be a normal entry point)
 *  - `action:ACTION`              launch an Activity by action
 *  - `broadcast:ACTION[@package]` send a broadcast (some firmware switches to the OEM screen via broadcast)
 */
object Targets {

    /** Known CarPlay app package names, in priority order. */
    private val CARPLAY_PACKAGES = listOf(
        "com.zjinnova.zlink",
        "com.zjinnova.zlink5",
        "com.carconnect5.connect",
        "com.shihab.diplay",
    )
    /** System settings: uses the action form so it opens on any head unit. */
    const val SYSTEM_SETTINGS = "action:android.settings.SETTINGS"

    private val FILES_PACKAGES = listOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.mediatek.filemanager",
        "com.android.fileexplorer",
    )
    private val FILES_KEYWORDS = listOf("文件", "file")
    private val CARPLAY_KEYWORDS = listOf("zlink", "carplay", "diplay", "autokit", "carlink")
    private val OEM_KEYWORDS = listOf("原车", "原厂", "oem", "original")

    fun intentFor(context: Context, spec: String?): Intent? {
        if (spec.isNullOrBlank()) return null
        if (spec.startsWith("action:")) return Intent(spec.removePrefix("action:"))
        if (spec.startsWith("broadcast:")) return null
        val slash = spec.indexOf('/')
        if (slash > 0) {
            val pkg = spec.substring(0, slash)
            var cls = spec.substring(slash + 1)
            if (cls.startsWith(".")) cls = pkg + cls
            return Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, cls))
        }
        return context.packageManager.getLaunchIntentForPackage(spec)
    }

    fun packageOf(spec: String?): String? {
        if (spec.isNullOrBlank()) return null
        if (spec.startsWith("action:")) return null
        if (spec.startsWith("broadcast:")) return spec.substringAfter('@', "").ifEmpty { null }
        return spec.substringBefore('/')
    }

    fun isAvailable(context: Context, spec: String?): Boolean {
        if (spec.isNullOrBlank()) return false
        if (spec.startsWith("broadcast:")) return true
        val intent = intentFor(context, spec) ?: return false
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun launch(context: Context, spec: String?): Boolean {
        if (spec.isNullOrBlank()) return false
        return try {
            if (spec.startsWith("broadcast:")) {
                val body = spec.removePrefix("broadcast:")
                val intent = Intent(body.substringBefore('@'))
                body.substringAfter('@', "").takeIf { it.isNotEmpty() }?.let { intent.setPackage(it) }
                context.sendBroadcast(intent)
                return true
            }
            val intent = intentFor(context, spec) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun icon(context: Context, spec: String?): Drawable? {
        val pm = context.packageManager
        val intent = intentFor(context, spec)
        return try {
            when {
                intent?.component != null -> pm.getActivityIcon(intent.component!!)
                packageOf(spec) != null -> pm.getApplicationIcon(packageOf(spec)!!)
                // action: form (e.g. system settings): use the icon of the app that handles it.
                intent != null -> pm.resolveActivity(intent, 0)?.loadIcon(pm)
                else -> null
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun label(context: Context, spec: String?): String? {
        val pm = context.packageManager
        val intent = intentFor(context, spec)
        return try {
            val info = intent?.let { pm.resolveActivity(it, 0) }
            when {
                info != null -> info.loadLabel(pm).toString()
                packageOf(spec) != null ->
                    pm.getApplicationLabel(pm.getApplicationInfo(packageOf(spec)!!, 0)).toString()
                else -> null
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** All launchable apps on the head unit (excluding this launcher), sorted by name. */
    fun launchableApps(context: Context): List<ResolveInfo> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    fun specOf(info: ResolveInfo): String =
        "${info.activityInfo.packageName}/${info.activityInfo.name}"

    /** Auto-detects the CarPlay app: first by known package names, then by label keywords. */
    fun findCarPlay(context: Context): String? {
        CARPLAY_PACKAGES.firstOrNull { isAvailable(context, it) }?.let { return it }
        return findByKeywords(context, CARPLAY_KEYWORDS)
    }

    /**
     * The stock launcher "主页" (Home) on the WWC2 platform (WY firmware): it opens the "安卓盒子" (Android box) desktop, which has a "原车媒体" (OEM media) entry
     * (verified by the owner: you go in via "主页", not main). Until a direct route to the OEM screen is found, the OEM tile opens this.
     */
    const val WWC2_HOME = "com.wwc2.launcher"

    /** Auto-detects the OEM app: first by label keywords, then falls back to the WWC2 stock home. If not found, the user picks one by long-press or via config.json. */
    fun findOem(context: Context): String? =
        findByKeywords(context, OEM_KEYWORDS)
            ?: launchableApps(context).firstOrNull { it.activityInfo.packageName == WWC2_HOME }?.let { specOf(it) }

    /** Auto-detects the file manager. */
    fun findFiles(context: Context): String? {
        launchableApps(context).firstOrNull { it.activityInfo.packageName in FILES_PACKAGES }
            ?.let { return specOf(it) }
        return findByKeywords(context, FILES_KEYWORDS)
    }

    private fun findByKeywords(context: Context, keywords: List<String>): String? {
        val pm = context.packageManager
        return launchableApps(context).firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            keywords.any { label.contains(it) || pkg.contains(it) }
        }?.let { specOf(it) }
    }
}
