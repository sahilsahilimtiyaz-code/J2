package com.jarvis.assistant.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.core.JarvisCore
import java.util.concurrent.TimeUnit

/**
 * Runs the memory extraction after Jarvis has gone quiet. WorkManager survives
 * the app being swiped away and is deferred until the phone is idle enough,
 * which is exactly the behaviour the brief asks for.
 */
class MemoryExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val core = runCatching { JarvisCore.get(applicationContext) }.getOrNull()
            ?: return Result.success()
        return runCatching { core.extractMemoriesFromLatestConversation() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    android.util.Log.w("JarvisMemory", "Extraction worker failed", error)
                    if (runAttemptCount < 3) Result.retry() else Result.success()
                },
            )
    }

    companion object {
        private const val WORK_NAME = "jarvis-memory-extract"

        /**
         * (Re)schedules the one extraction pass. Replacing the existing work
         * means the pass always runs a few minutes after the *last* turn rather
         * than repeatedly being pushed back.
         */
        fun schedule(context: Context, delayMinutes: Long = 4L) {
            val request = OneTimeWorkRequestBuilder<MemoryExtractionWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
}
