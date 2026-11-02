package com.x0finch.q5home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address

/**
 * Helps enable adb (when the head unit can't first be connected from a computer). Stock Android 12 has no entry point that jumps straight to the "Wireless debugging" page
 * (in AOSP Settings it's just an item in Developer options), so try in order:
 *  1. A direct action provided by some ROMs;
 *  2. Developer options, with the "Wireless debugging" item scrolled into place and highlighted (:settings:fragment_args_key = toggle_adb_wireless);
 *  3. Developer options not unlocked: open "About" and prompt to tap the build number repeatedly.
 */
object DebugAccess {

    const val DIRECT_ACTION = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
    private const val EXTRA_HIGHLIGHT = ":settings:fragment_args_key"
    private const val KEY_WIRELESS = "toggle_adb_wireless"

    /** Wireless debugging only exists since Android 11 (API 30). */
    fun hasWirelessDebugging(sdk: Int = Build.VERSION.SDK_INT) = sdk >= 30

    /**
     * Compute the real Android version from the API level. Some head-unit firmware changes the version string to "12" while it's actually an older system
     * (e.g. this WY / WWC2 head unit says 12, but the API is 27 = Android 8.1).
     */
    fun realAndroidVersion(sdk: Int = Build.VERSION.SDK_INT): String = when (sdk) {
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"
        33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
        else -> "API $sdk"
    }

    /** Open the most suitable page and return a hint to show the user. */
    fun openWirelessDebugging(context: Context, sdk: Int = Build.VERSION.SDK_INT): String {
        val pm = context.packageManager
        if (!hasWirelessDebugging(sdk)) {
            val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            if (dev.resolveActivity(pm) != null && start(context, dev)) {
                return "这台车机实际是安卓 ${realAndroidVersion(sdk)}，没有无线调试；已打开开发者选项"
            }
            start(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            return "这台车机实际是安卓 ${realAndroidVersion(sdk)}，没有无线调试；开发者选项没打开时连点 7 次「版本号」"
        }
        val direct = Intent(DIRECT_ACTION)
        if (direct.resolveActivity(pm) != null && start(context, direct)) {
            return "已打开无线调试页面"
        }
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(EXTRA_HIGHLIGHT, KEY_WIRELESS)
        if (dev.resolveActivity(pm) != null && start(context, dev)) {
            return "已打开开发者选项：找高亮的「无线调试」，打开后点进去看 IP 和端口"
        }
        if (start(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))) {
            return "开发者选项还没打开：在这页连续点 7 次「版本号」，再回来点一次"
        }
        return "车机没有提供这些设置页面"
    }

    fun usbDebuggingOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** Wireless debugging toggle on Android 11+ (Settings.Global.ADB_WIFI_ENABLED is a hidden constant). */
    fun wirelessDebuggingOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    /** IPv4 address of the head unit's current network (needed for adb connect). */
    fun ipAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
