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

    /** What the card shows. Null fields are not known (not reported by the CAN box, or out of range). */
    class Status(
        val fuel: Int?,
        val rpm: Int?,
        /** Open doors / lids, in Chinese ("左前门" …); empty = all closed. */
        val open: List<String>,
    )

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

        fun read(context: Context): Status? = runCatching {
            context.contentResolver.call(URI, "car_info", null, null)?.let(::parse)
        }.getOrNull()

        /** Turns the car_info Bundle into what the card shows (65535 / negative = not reported). */
        fun parse(b: Bundle): Status {
            fun int(key: String) = (b.get(key) as? Number)?.toInt()?.takeIf { it in 0..65534 }
            fun on(key: String) = b.get(key) == true
            val open = listOf(
                "LEFT_FRONT_DOOR" to "左前门", "RIGHT_FRONT_DOOR" to "右前门", "LEFT_BACK_DOOR" to "左后门",
                "RIGHT_BACK_DOOR" to "右后门", "TRUNK_DOOR" to "后备箱", "CAR_HOOD" to "引擎盖",
            ).filter { on(it.first) }.map { it.second }
            return Status(int("RESIDUAL_OIL"), int("ENGINE_SPEED")?.takeIf { it > 0 }, open)
        }

        /** Card title and second line. */
        fun texts(s: Status?): Pair<String, String> {
            if (s == null) return "车况" to "读取中"
            val title = s.fuel?.let { "油量 $it" } ?: "车况"
            val line = when {
                s.open.isNotEmpty() -> s.open.joinToString("、") + "开着"
                s.rpm != null -> "转速 ${s.rpm}"
                else -> "车门都关好了"
            }
            return title to line
        }
    }
}
