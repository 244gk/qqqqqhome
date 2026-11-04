package com.x0finch.q5home

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Vehicle data for the home screen's car card, read the way the stock launcher and "液晶仪表" do: the read-only getter
 * ContentResolver.call(CanbusProvider/car_info, "car_info"), which returns the latest CAN values as a Bundle and writes nothing.
 * The provider notifies car_info changes several times a second; the card is refreshed at most once a second, and only while
 * the home screen listens ([start] / [stop]).
 */
class CarData(private val context: Context, private val onChange: (Status) -> Unit) {

    /** What the cards show. Null fields are not known (not reported by the CAN box, or out of range). */
    class Status(
        val fuel: Int?,
        val rpm: Int?,
        /** Open doors / lids, in Chinese ("左前门" …); empty = all closed. */
        val open: List<String>,
        /** Vehicle speed from the CAN bus (km/h), the same value the instrument cluster shows. */
        val speed: Int? = null,
        /** 1 = driver's belt fastened (0 while parked, 1 while driving in the owner's reports). */
        val belt: Int? = null,
        /** 1 = handbrake on. */
        val handbrake: Int? = null,
        /** A/C: automatic mode, fan level (0 = off). */
        val airAuto: Boolean? = null,
        val fanLevel: Int? = null,
        /** When this was read (elapsed ms), to tell fresh data from old. */
        val at: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        val moving get() = (speed ?: 0) >= MOVING_KMH
    }

    enum class Tone { NORMAL, WARN, DANGER }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "q5-car").apply { isDaemon = true } }
    private var observer: ContentObserver? = null
    private var lastRead = 0L
    private var queued = false

    fun start() {
        if (observer != null || !Targets.hasOemSwitch(context)) return
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = request()
        }
        runCatching { context.contentResolver.registerContentObserver(URI, false, o) }.onSuccess { observer = o }
        request()
    }

    fun stop() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        main.removeCallbacksAndMessages(null)
    }

    /** Reads now, or a little later if the last read was less than a second ago. */
    private fun request() {
        if (queued) return
        val wait = (lastRead + MIN_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0)
        queued = true
        main.postDelayed({
            queued = false
            lastRead = System.currentTimeMillis()
            worker.execute {
                val status = read(context) ?: return@execute
                main.post { if (observer != null) onChange(status) }
            }
        }, wait)
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.wwc2.canmcusdk.CanbusProvider/car_info")
        private const val MIN_INTERVAL_MS = 1000L

        val AIR_URI: Uri = Uri.parse("content://com.wwc2.canmcusdk.CanbusProvider/air_info")
        const val MOVING_KMH = 5
        /** Below this many litres the card turns amber (the Q5's tank holds about 75 L). */
        const val LOW_FUEL_L = 10

        fun read(context: Context): Status? = runCatching {
            val car = context.contentResolver.call(URI, "car_info", null, null) ?: return@runCatching null
            val air = runCatching { context.contentResolver.call(AIR_URI, "air_info", null, null) }.getOrNull()
            parse(car, air)
        }.getOrNull()

        /** Turns the car_info (and air_info) Bundles into what the cards show (65535 / negative = not reported). */
        @Suppress("DEPRECATION")
        fun parse(b: Bundle, air: Bundle? = null): Status {
            fun int(bundle: Bundle?, key: String) = (bundle?.get(key) as? Number)?.toInt()?.takeIf { it in 0..65534 }
            fun on(key: String) = b.get(key) == true
            val open = listOf(
                "LEFT_FRONT_DOOR" to "左前门", "RIGHT_FRONT_DOOR" to "右前门", "LEFT_BACK_DOOR" to "左后门",
                "RIGHT_BACK_DOOR" to "右后门", "TRUNK_DOOR" to "后备箱", "CAR_HOOD" to "引擎盖",
            ).filter { on(it.first) }.map { it.second }
            return Status(
                fuel = int(b, "RESIDUAL_OIL"),
                rpm = int(b, "ENGINE_SPEED")?.takeIf { it > 0 },
                open = open,
                // Named AVERAGE_SPEED in the vendor's table, but McuManager.dyncInfoMessage stores the live vehicle speed there.
                speed = int(b, "AVERAGE_SPEED")?.takeIf { it <= 400 },
                belt = int(b, "DRIVER_SEAT_BELT"),
                handbrake = int(b, "HANDBRAKE_STATE"),
                airAuto = air?.get("MODE_AUTO") as? Boolean,
                fanLevel = int(air, "LEFT_WIND_LEVEL"),
            )
        }

        /** Car card: title, second line, and how urgent the second line is. */
        fun texts(s: Status?): Triple<String, String, Tone> {
            if (s == null) return Triple("车况", "读取中", Tone.NORMAL)
            val title = s.fuel?.let { "油量 $it L" } ?: "车况"
            return when {
                s.open.isNotEmpty() -> Triple(title, s.open.joinToString("、") + "开着", Tone.DANGER)
                s.moving && s.handbrake == 1 -> Triple(title, "手刹没放", Tone.DANGER)
                s.moving && s.belt == 0 -> Triple(title, "安全带没系", Tone.DANGER)
                s.fuel != null && s.fuel <= LOW_FUEL_L -> Triple(title, "油不多了", Tone.WARN)
                else -> Triple(title, listOfNotNull("车门都关好", air(s)).joinToString(" · "), Tone.NORMAL)
            }
        }

        // air_info has no compressor (A/C on/off) field: MODE_AUTO stays true with only the fan running, so only the fan is shown.
        private fun air(s: Status): String? = s.fanLevel?.takeIf { it > 0 }?.let { "风量 $it" }
    }
}
