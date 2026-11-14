package com.brick.history.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Runs the trash rules once a day, so old chats move on time even when Brick and History aren't used. */
class CleanupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Store.init(applicationContext)
                Store.cleanup()
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val ID = 4101

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(context, CleanupJob::class.java))
                .setPeriodic(24 * 3600_000L)
                .setPersisted(true)
                .setRequiresBatteryNotLow(true)
                .build()
            runCatching { js.schedule(job) }
        }
    }
}
