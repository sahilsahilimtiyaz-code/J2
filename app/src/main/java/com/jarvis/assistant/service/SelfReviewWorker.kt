package com.jarvis.assistant.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.core.JarvisCore
import com.jarvis.assistant.memory.MemoryType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nightly self-review worker as specified in AGENTS.md Section 9:
 * "A nightly WorkManager job looks at failed tool calls and writes short
 * 'lessons' into memory (for example, 'searching YouTube through the intent
 * works better than tapping')."
 */
class SelfReviewWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val core = runCatching { JarvisCore.get(applicationContext) }.getOrNull()
            ?: return@withContext Result.success()

        val actionLog = core.actionLogStore
        actionLog.load()
        val entries = actionLog.entries.value

        // Focus on failed actions and error patterns
        val failedEntries = entries.filter { !it.success }
        if (failedEntries.isEmpty()) {
            return@withContext Result.success()
        }

        val toolFailures = failedEntries.groupBy { it.toolName }
        val memoryRepo = core.memoryRepository

        for ((toolName, failures) in toolFailures) {
            val count = failures.size
            val sampleError = failures.firstOrNull()?.detail?.take(120).orEmpty()
            val lessonText = when (toolName) {
                "search_youtube" -> "Direct HTTPS YouTube video playback is more reliable than generic search intent or coordinate tapping on this phone."
                "tap_screen" -> "Screen element coordinate dispatch requires verified UI state fingerprints before advancing."
                "read_notifications" -> "Notification reading requires active NotificationListenerService binding in Android Settings."
                "send_sms", "call_contact" -> "Telephone and messaging operations require explicit owner confirmation and runtime dialer/SMS intent handling."
                "automate_screen" -> "Multi-step screen automation should verify each step's post-condition and stop immediately if target node does not change."
                else -> "Tool '$toolName' encountered $count issues ($sampleError). Review parameter constraints and fallback execution."
            }

            // Save durable lesson into memory
            runCatching {
                memoryRepo.remember(
                    text = "Lesson: $lessonText",
                    type = MemoryType.LESSON,
                    importance = 4,
                    source = "self_review",
                )
            }
        }

        Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "jarvis-nightly-self-review"
        private const val ONCE_WORK_NAME = "jarvis-self-review-now"

        fun scheduleNightly(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SelfReviewWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SelfReviewWorker>().build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONCE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
}
