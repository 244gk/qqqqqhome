package com.x0finch.q5home

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONArray

/**
 * Crash guard: records every crash (version code + time); if the same version crashes [LIMIT] times within [WINDOW_MS], enter safe mode,
 * so the launcher doesn't crash on startup, crash repeatedly on HOME, and leave nothing usable.
 *
 * Only the current version counts: installing a fixed or rolled-back version restores normal behavior automatically; old crashes outside the time window no longer count either.
 */
object CrashGuard {
    const val LIMIT = 3
    const val WINDOW_MS = 10 * 60_000L
    private const val PREFS = "crash_guard"
    private const val KEY_CRASHES = "crashes"
    private const val KEY_LAST = "last_error"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The process is about to be killed, so write synchronously (commit, not apply).
            runCatching { record(app, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, error: Throwable, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = recent(context, now).toMutableList()
        list += now
        val version = versionCode(context)
        val arr = JSONArray(list.map { "$version:$it" })
        val trace = error.stackTraceToString().lineSequence().take(12).joinToString("\n")
        prefs.edit().putString(KEY_CRASHES, arr.toString()).putString(KEY_LAST, trace).commit()
    }

    /** Crash times of the current version within the time window. */
    fun recent(context: Context, now: Long = System.currentTimeMillis()): List<Long> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CRASHES, "[]")
        val version = versionCode(context)
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val (v, t) = arr.getString(i).split(':').let { it[0].toLong() to it[1].toLong() }
            t.takeIf { v == version && now - t <= WINDOW_MS }
        }
    }

    fun shouldEnterSafeMode(context: Context, now: Long = System.currentTimeMillis()) = recent(context, now).size >= LIMIT

    fun lastError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    /** Cleared when the user picks "再试一次" (Try again) in safe mode, or after a new version runs stably. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun versionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }
}

class Q5App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        // Background recon recording (listen-only; see EventRecorder).
        EventRecorder.start(this)
    }
}
