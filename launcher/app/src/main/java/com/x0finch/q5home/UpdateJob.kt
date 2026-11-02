package com.x0finch.q5home

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

/** Checks GitHub Releases once a day (a single tiny request, any network is fine). The home screen shows a notice when a new version exists; it never installs automatically. */
class UpdateJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val updates = Updates(this)
            val ok = !updates.autoCheck || runCatching { updates.check() }.isSuccess
            jobFinished(params, !ok)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 2001

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
                .setPersisted(true)
                .build())
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
