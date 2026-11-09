package com.jarvis.assistant.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.brain.HttpClientFactory
import com.jarvis.assistant.service.NotificationHelper
import java.util.concurrent.TimeUnit

/**
 * Checks the Octa-Jarvis site in the background and posts a notification when a
 * newer build has been published, so the owner hears about releases even if they
 * have not opened the app that day.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val checker = AppUpdateChecker(applicationContext, HttpClientFactory.create())
        val update = runCatching { checker.check() }.getOrNull() ?: return Result.success()
        NotificationHelper.showUpdateNotification(
            context = applicationContext,
            versionName = update.versionName,
            releaseNotes = update.releaseNotes,
            downloadUrl = checker.downloadUrlFor(update),
        )
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "octa_jarvis_update_check"

        /** Schedules (or keeps) the periodic check. Safe to call on every launch. */
        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}
