package com.x0finch.q5home

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Default launcher (HOME): switchable in the car without adb.
 *  - Set as default: on Android 10+ request the "home" role via RoleManager, and the system shows a confirmation dialog; on older systems open the home app settings.
 *  - Restore OEM: open the system "Home app" settings page and let the user pick the OEM launcher again (the OEM launcher is never disabled).
 */
object HomeRole {

    /** Whether Q5 Home is currently the default launcher. */
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        return currentHomePackage(context) == context.packageName
    }

    /** Name of the current default launcher; null when there is no fixed default (the system asks every time). */
    fun currentHomeLabel(context: Context): String? {
        val pm = context.packageManager
        val info = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        if (info.activityInfo.packageName == "android") return null // The system chooser, meaning there is no fixed default
        return info.loadLabel(pm).toString()
    }

    private fun currentHomePackage(context: Context): String? =
        context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName

    /** Intent used to request becoming the default launcher (pass to startActivityForResult). */
    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return homeSettingsIntent(context)
    }

    /** The system "Home app" settings page; if missing, fall back to default apps settings, then to system settings. */
    fun homeSettingsIntent(context: Context): Intent {
        val pm = context.packageManager
        return listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        ).first { it.resolveActivity(pm) != null || it.action == Settings.ACTION_SETTINGS }
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
}
