package com.x0finch.q5home

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.util.concurrent.TimeUnit

/** Updates online wallpapers when on Wi-Fi (an unmetered network), at most once a day. */
class WallpaperJob : JobService() {

    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        thread = Thread {
            val ok = runCatching {
                val config = Config(this).also { it.reload() }
                if (config.onlineWallpapers) {
                    OnlineWallpapers(this).update(config, resources.displayMetrics.widthPixels)
                }
            }.isSuccess
            // On failure (e.g. network dropped), let the system retry with backoff.
            jobFinished(params, !ok)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        thread?.interrupt()
        return true
    }

    companion object {
        private const val JOB_DAILY = 1001
        private const val JOB_NOW = 1002

        /** Daily periodic job; not recreated if it already exists. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_DAILY) != null) return
            js.schedule(builder(context, JOB_DAILY)
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
                .setPersisted(true)
                .build())
        }

        /** Updates once as soon as Wi-Fi is next available (the "立即更新" (Update now) setting). */
        fun runSoon(context: Context) {
            context.getSystemService(JobScheduler::class.java)
                .schedule(builder(context, JOB_NOW).build())
        }

        fun cancel(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            js.cancel(JOB_DAILY)
            js.cancel(JOB_NOW)
        }

        private fun builder(context: Context, id: Int): JobInfo.Builder {
            val b = JobInfo.Builder(id, ComponentName(context, WallpaperJob::class.java))
                .setRequiresStorageNotLow(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Wi-Fi / unmetered only.
                b.setRequiredNetwork(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .build())
            } else {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            }
            return b
        }
    }
}
