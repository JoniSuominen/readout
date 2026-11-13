package com.jonisuominen.readout.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jonisuominen.readout.container
import com.jonisuominen.readout.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * Best-effort background refresh. Android decides when it runs; this asks for
 * an opportunity about every 30 minutes, it does not set a timer. Sources
 * fetched recently are skipped, and failures stay silent: the widget keeps
 * showing the last values with their real age.
 */
class RefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        if (container.store.dashboard.connectedSources.isEmpty()) return Result.success()
        when (inputData.getString(MODE)) {
            // The widget's refresh tile: fetch everything and report failures.
            MODE_USER -> container.manager.refreshConnectedSources(quiet = false)
            // A shown value expired, such as an event that ended.
            MODE_EXPIRED -> container.manager.refreshExpiredSources(quiet = true)
            else -> container.manager.refreshStaleSources(PASSIVE_MINIMUM_AGE_MILLIS, quiet = true)
        }
        WidgetUpdater.updateNow(applicationContext)
        return Result.success()
    }

    companion object {
        /** Background refreshes skip sources fetched more recently than this. */
        const val PASSIVE_MINIMUM_AGE_MILLIS = 10 * 60 * 1000L
        private const val WORK_NAME = "readout.refresh"
        private const val NOW_WORK_NAME = "readout.refresh.now"
        private const val EXPIRED_WORK_NAME = "readout.refresh.expired"
        private const val MODE = "mode"
        private const val MODE_USER = "user"
        private const val MODE_EXPIRED = "expired"

        /** Runs one refresh as soon as Android allows, for the widget's refresh tile. */
        fun refreshNow(context: Context) = enqueue(context, NOW_WORK_NAME, MODE_USER)

        /**
         * Fetches again for sources whose shown value has expired, so the next
         * event replaces one that ended instead of leaving its tile empty
         * until the next periodic refresh.
         */
        fun refreshExpired(context: Context) = enqueue(context, EXPIRED_WORK_NAME, MODE_EXPIRED)

        private fun enqueue(context: Context, name: String, mode: String) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(workDataOf(MODE to mode))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
