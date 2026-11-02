package com.x0finch.q5home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * GPS and 4G check (read-only, never changes system settings):
 *  - GPS: how many satellites are visible, how many are used in the fix, signal strength, whether there is a fix, time to first fix;
 *  - 4G: SIM card, operator, network type, signal bars / dBm, whether mobile data is on, which network currently carries traffic;
 *  - 4G connectivity test: temporarily fetches a test URL once over the mobile network only (tiny traffic), unaffected by Wi-Fi / phone hotspot.
 * Location coordinates are only shown as accuracy in the car and never written into the recon report (the report goes to GitHub).
 */
class SignalCheck(private val context: Context) {

    class Satellite(val cn0: Float, val usedInFix: Boolean)

    private val lm = context.getSystemService(LocationManager::class.java)
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var satellites: List<Satellite> = emptyList(); private set
    @Volatile var location: Location? = null; private set
    @Volatile var firstFixMs: Int? = null; private set
    @Volatile var signalLevel: Int? = null; private set
    var listening = false; private set
    private var startedAt = 0L
    private var gnssCallback: GnssStatus.Callback? = null
    private var locationListener: LocationListener? = null
    private var phoneListener: PhoneStateListener? = null

    fun hasLocationPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasPhonePermission() =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    fun hasGps() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    fun gpsEnabled() = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)
    fun hasTelephony() = tm != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** Starts listening for satellites, location and signal strength; [onChange] is called on the main thread. Without location permission only signal strength is monitored. */
    @SuppressLint("MissingPermission")
    fun start(onChange: () -> Unit) {
        if (listening) return
        listening = true
        startedAt = SystemClock.elapsedRealtime()
        val main = Handler(Looper.getMainLooper())
        if (hasTelephony()) {
            // Signal strength needs no permission. PhoneStateListener is deprecated since Android 12, but this head unit runs 8.1, so it still works.
            @Suppress("DEPRECATION")
            phoneListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(s: SignalStrength) {
                    signalLevel = s.level
                    onChange()
                }
            }.also { runCatching { tm!!.listen(it, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) } }
        }
        if (!hasLocationPermission() || lm == null) return
        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                satellites = (0 until status.satelliteCount).map { Satellite(status.getCn0DbHz(it), status.usedInFix(it)) }
                onChange()
            }
            override fun onFirstFix(ttffMillis: Int) {
                firstFixMs = ttffMillis
                onChange()
            }
        }
        // On Android 8.1 all four LocationListener methods are abstract and must all be implemented, or the system callback crashes.
        locationListener = object : LocationListener {
            override fun onLocationChanged(l: Location) { location = l; onChange() }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) { onChange() }
            override fun onProviderDisabled(provider: String) { onChange() }
        }
        runCatching {
            lm.registerGnssStatusCallback(gnssCallback!!, main)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener!!, Looper.getMainLooper())
            location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        gnssCallback?.let { runCatching { lm?.unregisterGnssStatusCallback(it) } }
        locationListener?.let { runCatching { lm?.removeUpdates(it) } }
        @Suppress("DEPRECATION")
        phoneListener?.let { runCatching { tm?.listen(it, PhoneStateListener.LISTEN_NONE) } }
        gnssCallback = null; locationListener = null; phoneListener = null
    }

    // ---- Text lines for the UI / report ----

    fun gpsLines(): List<Pair<String, String>> {
        val now = SystemClock.elapsedRealtime()
        val state = when {
            !hasGps() -> "系统报告没有 GPS 硬件"
            !gpsEnabled() -> "定位开关是关的（安卓设置 → 位置信息 → 打开）"
            !hasLocationPermission() -> "还没授权位置信息"
            !listening -> "GPS 已打开，还没开始检查"
            // Shown in 10-second steps so the text doesn't change every second (the UI keeps redrawing).
            else -> "正在搜星 · 已 ${(now - startedAt) / 10_000 * 10} 秒"
        }
        return listOf("GPS" to state, "卫星" to satellitesText(satellites),
            "定位" to fixText(location ?: lastKnown(), firstFixMs))
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? = if (!hasLocationPermission()) null
        else runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()

    @SuppressLint("MissingPermission")
    fun cellLines(probe: String? = null): List<Pair<String, String>> {
        if (!hasTelephony()) {
            return listOf("4G 模块" to "系统报告没有电话 / 移动网络功能", "现在上网走" to activeTransport())
        }
        val t = tm!!
        val sim = simStateName(t.simState)
        val op = t.networkOperatorName.orEmpty().ifBlank { t.simOperatorName.orEmpty() }
        val type = runCatching {
            @Suppress("DEPRECATION")
            if (hasPhonePermission()) t.dataNetworkType else t.networkType
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val cell = if (hasLocationPermission()) runCatching { t.allCellInfo }.getOrNull()?.firstOrNull { it.isRegistered } else null
        val (cellLevel, dbm) = cellStrength(cell)
        val level = cellLevel ?: signalLevel
        val data = if (hasPhonePermission()) runCatching { if (t.isDataEnabled) "开" else "关" }.getOrDefault("未知") else "未知（没授权电话状态）"
        return listOfNotNull(
            "SIM 卡" to (sim + if (op.isNotBlank()) " · $op" else ""),
            "网络" to networkTypeName(type) + if (t.isNetworkRoaming) " · 漫游" else "",
            "信号" to signalText(level, dbm),
            "移动数据" to data,
            "现在上网走" to activeTransport(),
            probe?.let { "4G 上网测试" to it },
        )
    }

    private fun cellStrength(cell: CellInfo?): Pair<Int?, Int?> = when (cell) {
        is CellInfoLte -> cell.cellSignalStrength.level to cell.cellSignalStrength.dbm
        is CellInfoWcdma -> cell.cellSignalStrength.level to cell.cellSignalStrength.dbm
        is CellInfoGsm -> cell.cellSignalStrength.level to cell.cellSignalStrength.dbm
        is CellInfoCdma -> cell.cellSignalStrength.level to cell.cellSignalStrength.dbm
        else -> null to null
    }

    private fun activeTransport(): String {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return "没有网络"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi（比如手机热点）"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络（4G）"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            else -> "其它"
        }
    }

    /**
     * 4G connectivity test: temporarily requests a mobile network and uses it only to fetch [url] once. [done] is called exactly once (background thread).
     * Even with Wi-Fi, Android brings up the mobile network for this request and releases it afterwards.
     */
    fun probeCellular(url: String = PROBE_URL, timeoutMs: Int = 20_000, done: (String) -> Unit) {
        val cm = cm ?: return done("系统没有网络服务")
        val finished = AtomicBoolean(false)
        fun finish(cb: ConnectivityManager.NetworkCallback, msg: String) {
            if (finished.compareAndSet(false, true)) {
                runCatching { cm.unregisterNetworkCallback(cb) }
                done(msg)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val cb = this
                thread {
                    val start = SystemClock.elapsedRealtime()
                    val msg = try {
                        val conn = network.openConnection(URL(url)) as HttpURLConnection
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.instanceFollowRedirects = false
                        val code = conn.responseCode
                        conn.disconnect()
                        val ms = SystemClock.elapsedRealtime() - start
                        if (code in 200..399) "能上网 ✓（HTTP $code，用时 $ms 毫秒）" else "连上了移动网络，但访问失败（HTTP $code）"
                    } catch (e: Exception) {
                        "连上了移动网络，但上不了网：${e.javaClass.simpleName}"
                    }
                    finish(cb, msg)
                }
            }
            override fun onUnavailable() {
                finish(this, "连不上移动网络：没插 SIM、没开移动数据、欠费或没有信号")
            }
        }
        try {
            cm.requestNetwork(request, callback, timeoutMs)
        } catch (e: Exception) {
            finish(callback, "没法请求移动网络：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val PROBE_URL = "https://www.gstatic.com/generate_204"

        fun simStateName(state: Int): String = when (state) {
            TelephonyManager.SIM_STATE_ABSENT -> "没插 SIM 卡"
            TelephonyManager.SIM_STATE_READY -> "已就绪"
            TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "被锁（需要 PIN / PUK）"
            TelephonyManager.SIM_STATE_NOT_READY -> "还没准备好"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "已被永久停用"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "读卡出错（卡没插好？）"
            else -> "未知"
        }

        @Suppress("DEPRECATION")
        fun networkTypeName(type: Int): String = when (type) {
            TelephonyManager.NETWORK_TYPE_LTE -> "4G（LTE）"
            20 -> "5G（NR）" // NETWORK_TYPE_NR, constant only exists from API 29
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            else -> "没有移动网络"
        }

        fun signalText(level: Int?, dbm: Int?): String {
            if (level == null && dbm == null) return "未知"
            val bars = level?.coerceIn(0, 4)
            val word = when (bars) { null -> ""; 0 -> "没信号"; 1 -> "很弱"; 2 -> "一般"; 3 -> "好"; else -> "很好" }
            return listOfNotNull(
                // Don't use symbols like ▮▯: the head unit's font lacks them and shows boxes.
                bars?.let { "$it/4 格 · $word" },
                dbm?.takeIf { it in -150..-20 }?.let { "$it dBm" },
            ).joinToString(" · ")
        }

        fun satellitesText(sats: List<Satellite>): String {
            if (sats.isEmpty()) return "还没收到卫星（天线没接好、在地库 / 室内，或刚开始搜）"
            val used = sats.count { it.usedInFix }
            val best = sats.maxOf { it.cn0 }
            val quality = when { best >= 30 -> "好"; best >= 20 -> "一般"; else -> "弱" }
            return String.format(Locale.US, "看到 %d 颗 · 用于定位 %d 颗 · 最强 %.0f dB-Hz（%s）", sats.size, used, best, quality)
        }

        fun fixText(location: Location?, firstFixMs: Int?, nowMs: Long = System.currentTimeMillis()): String {
            if (location == null) return "还没定位（在室外冷启动一般要 30 秒到几分钟）"
            val ageS = ((nowMs - location.time) / 1000).coerceAtLeast(0)
            return listOfNotNull(
                "已定位",
                if (location.hasAccuracy()) "精度约 ±${location.accuracy.toInt()} 米" else null,
                firstFixMs?.let { "首次定位用时 ${it / 1000} 秒" },
                if (ageS > 5) "最近一次是 $ageS 秒前" else null,
            ).joinToString(" · ")
        }
    }
}
