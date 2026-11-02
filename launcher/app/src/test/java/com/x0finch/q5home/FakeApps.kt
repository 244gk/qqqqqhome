package com.x0finch.q5home

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.GradientDrawable
import org.robolectric.Shadows.shadowOf

/** Installs a few fake apps into Robolectric's PackageManager to simulate the head unit. */
object FakeApps {
    const val ZLINK = "com.zjinnova.zlink/com.zjinnova.zlink.MainActivity"
    const val OEM = "com.wy.oem/com.wy.oem.MainActivity"
    const val FILES = "com.android.documentsui/com.android.documentsui.LauncherActivity"
    const val NAVI = "com.autonavi.amapauto/com.autonavi.amapauto.MainActivity"

    fun install(context: Context, vararg specs: Pair<String, String>) {
        val spm = shadowOf(context.packageManager)
        for ((spec, label) in specs) {
            val pkg = spec.substringBefore('/')
            val cls = spec.substringAfter('/')
            val ai = ActivityInfo().apply {
                packageName = pkg; name = cls; exported = true
                nonLocalizedLabel = label
                applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
            }
            spm.addOrUpdateActivity(ai)
            spm.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                ResolveInfo().apply { activityInfo = ai; nonLocalizedLabel = label },
            )
            spm.setApplicationIcon(pkg, GradientDrawable().apply { setColor(0xFF3366FF.toInt()); setSize(96, 96) })
        }
    }

    fun installCar(context: Context) {
        install(
            context,
            ZLINK to "ZLINK",
            OEM to "原车",
            FILES to "Files",
            NAVI to "高德地图车机版",
        )
        // System settings: responds to android.settings.SETTINGS.
        val settings = ActivityInfo().apply {
            packageName = "com.android.settings"; name = "com.android.settings.Settings"; exported = true
            nonLocalizedLabel = "Settings"
            applicationInfo = ApplicationInfo().apply { packageName = "com.android.settings" }
        }
        val spm = shadowOf(context.packageManager)
        spm.addOrUpdateActivity(settings)
        spm.addResolveInfoForIntent(
            Intent(android.provider.Settings.ACTION_SETTINGS),
            ResolveInfo().apply { activityInfo = settings; nonLocalizedLabel = "Settings" },
        )
    }
}
