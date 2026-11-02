package com.jarvis.assistant.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.core.JarvisCore
import com.jarvis.assistant.settings.HealthSettings
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HealthSuggestionCategory(val label: String) {
    EYE_CARE_20_20_20("20-20-20 Eye Care"),
    HYDRATION_BREAK("Hydration & Refresh"),
    POSTURE_AND_SHOULDERS("Posture & Neck Relief"),
    STRETCH_AND_CIRCULATION("Stretch & Circulation"),
    EXTENDED_WORKLOAD_REST("Extended Session Rest"),
}

data class ScreenUsageHealthSnapshot(
    val serviceRunning: Boolean = false,
    val isScreenInteractive: Boolean = true,
    val continuousSessionMinutes: Long = 0L,
    val dailyScreenMinutes: Long = 0L,
    val recommendedLimitMinutes: Int = 45,
    val breaksTakenToday: Int = 0,
    val lastReminderAtMs: Long = 0L,
    val lastSuggestionText: String? = null,
    val lastSuggestionCategory: String? = null,
)

data class HealthReminderDecision(
    val shouldRemind: Boolean,
    val shouldSpeakAloud: Boolean,
    val milestoneMinutes: Long,
    val category: HealthSuggestionCategory,
    val message: String,
    val reason: String,
)

/**
 * Pure, deterministic policy evaluator for screen usage limits and non-intrusive
 * voice health reminders.
 */
object ScreenUsageHealthPolicy {

    fun isQuietHour(currentHour: Int, quietStart: Int, quietEnd: Int): Boolean {
        val hour = currentHour.mod(24)
        val start = quietStart.mod(24)
        val end = quietEnd.mod(24)
        if (start == end) return false
        return if (start < end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    fun evaluate(
        settings: HealthSettings,
        ownerTitle: String,
        continuousMinutes: Long,
        dailyMinutes: Long,
        lastRemindedMilestoneMinutes: Long,
        lastRemindedAtMs: Long,
        snoozedUntilMs: Long,
        nowMs: Long,
        currentHour: Int,
        isScreenInteractive: Boolean,
        isInCallOrCommunication: Boolean,
        isAssistantBusy: Boolean,
        rotationIndex: Int = 0,
    ): HealthReminderDecision {
        val cleanTitle = ownerTitle.trim().ifBlank { "sir" }
        val limit = settings.sessionLimitMinutes.coerceIn(15, 240).toLong()

        if (!settings.enabled) {
            return noReminder("health_tracking_disabled")
        }
        if (!isScreenInteractive) {
            return noReminder("screen_off")
        }
        if (isInCallOrCommunication) {
            return noReminder("in_active_call")
        }
        if (isAssistantBusy) {
            return noReminder("assistant_busy")
        }
        if (nowMs < snoozedUntilMs) {
            return noReminder("snoozed")
        }
        if (continuousMinutes < limit) {
            return noReminder("below_session_limit")
        }

        val milestone = (continuousMinutes / limit) * limit
        if (milestone <= lastRemindedMilestoneMinutes) {
            return noReminder("milestone_already_reminded")
        }
        if (lastRemindedAtMs > 0L && nowMs - lastRemindedAtMs < MIN_REMINDER_COOLDOWN_MS) {
            return noReminder("cooldown_active")
        }

        val category = pickCategory(continuousMinutes, limit, rotationIndex)
        val message = buildFriendlyHealthSuggestion(
            category = category,
            title = cleanTitle,
            continuousMinutes = continuousMinutes,
            dailyMinutes = dailyMinutes,
        )
        val quiet = isQuietHour(currentHour, settings.quietHoursStart, settings.quietHoursEnd)
        val speakAloud = settings.voiceSuggestionsEnabled && !quiet

        return HealthReminderDecision(
            shouldRemind = true,
            shouldSpeakAloud = speakAloud,
            milestoneMinutes = milestone,
            category = category,
            message = message,
            reason = if (quiet) "quiet_hours_notification_only" else "voice_and_notification",
        )
    }

    fun buildFriendlyHealthSuggestion(
        category: HealthSuggestionCategory,
        title: String,
        continuousMinutes: Long,
        dailyMinutes: Long,
    ): String {
        val safeTitle = title.trim().ifBlank { "sir" }
        return when (category) {
            HealthSuggestionCategory.EYE_CARE_20_20_20 ->
                "Pardon the brief interruption, $safeTitle. You have been on the screen for $continuousMinutes minutes straight. Looking twenty feet away for twenty seconds will give your eyes a well-earned rest."

            HealthSuggestionCategory.HYDRATION_BREAK ->
                "A friendly health check, $safeTitle. We have been working continuously for $continuousMinutes minutes—stepping away for a glass of water and a short breather will keep your focus sharp."

            HealthSuggestionCategory.POSTURE_AND_SHOULDERS ->
                "Forgive the observation, $safeTitle, but $continuousMinutes minutes of continuous screen time is a good cue to relax your shoulders, check your posture, and rest your eyes for a moment."

            HealthSuggestionCategory.STRETCH_AND_CIRCULATION ->
                "You have been focused on the device for $continuousMinutes minutes, $safeTitle. A two-minute standing stretch will do wonders for circulation before we tackle the next task."

            HealthSuggestionCategory.EXTENDED_WORKLOAD_REST ->
                "We have reached $continuousMinutes minutes of uninterrupted screen use ($dailyMinutes minutes today), $safeTitle. I strongly recommend a five-minute screen-free break while I keep watch over your notifications."
        }
    }

    private fun pickCategory(
        continuousMinutes: Long,
        limit: Long,
        rotationIndex: Int,
    ): HealthSuggestionCategory {
        if (continuousMinutes >= limit * 2) {
            return if (rotationIndex % 2 == 0) {
                HealthSuggestionCategory.EXTENDED_WORKLOAD_REST
            } else {
                HealthSuggestionCategory.STRETCH_AND_CIRCULATION
            }
        }
        val pool = listOf(
            HealthSuggestionCategory.EYE_CARE_20_20_20,
            HealthSuggestionCategory.HYDRATION_BREAK,
            HealthSuggestionCategory.POSTURE_AND_SHOULDERS,
            HealthSuggestionCategory.STRETCH_AND_CIRCULATION,
        )
        return pool[rotationIndex.mod(pool.size)]
    }

    private fun noReminder(reason: String) = HealthReminderDecision(
        shouldRemind = false,
        shouldSpeakAloud = false,
        milestoneMinutes = 0L,
        category = HealthSuggestionCategory.EYE_CARE_20_20_20,
        message = "",
        reason = reason,
    )

    const val MIN_REMINDER_COOLDOWN_MS = 10 * 60_000L
}

/**
 * Background service that continuously tracks screen interactive usage and
 * delivers periodic, non-intrusive voice health suggestions or friendly break
 * reminders when screen time exceeds recommended limits.
 */
class ScreenUsageHealthService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    onScreenInteractiveChanged(this@ScreenUsageHealthService, interactive = true, now = now)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    onScreenInteractiveChanged(this@ScreenUsageHealthService, interactive = false, now = now)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        restorePersistedState(this)
        registerScreenReceiver()
        startTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_BREAK -> {
                markBreakTaken(this)
            }

            ACTION_SNOOZE_REMINDER -> {
                snoozeReminders(this, minutes = 15)
            }

            ACTION_STOP_HEALTH_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startTicker()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        unregisterScreenReceiver()
        tickerJob?.cancel()
        serviceScope.cancel()
        _snapshot.update { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            while (isActive) {
                tickAndEvaluate(this@ScreenUsageHealthService)
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
    }

    companion object {
        const val ACTION_START_HEALTH_SERVICE = "com.jarvis.assistant.action.START_HEALTH_SERVICE"
        const val ACTION_STOP_HEALTH_SERVICE = "com.jarvis.assistant.action.STOP_HEALTH_SERVICE"
        const val ACTION_RESET_BREAK = "com.jarvis.assistant.action.RESET_HEALTH_BREAK"
        const val ACTION_SNOOZE_REMINDER = "com.jarvis.assistant.action.SNOOZE_HEALTH_REMINDER"

        private const val PREFS_NAME = "jarvis_screen_health_v1"
        private const val KEY_DAY_EPOCH = "day_epoch"
        private const val KEY_DAILY_ACCUMULATED_MS = "daily_accumulated_ms"
        private const val KEY_SESSION_STARTED_MS = "session_started_ms"
        private const val KEY_SCREEN_OFF_SINCE_MS = "screen_off_since_ms"
        private const val KEY_BREAKS_TODAY = "breaks_today"
        private const val KEY_LAST_REMINDER_MS = "last_reminder_ms"
        private const val KEY_LAST_MILESTONE_MIN = "last_milestone_min"
        private const val KEY_SNOOZED_UNTIL_MS = "snoozed_until_ms"
        private const val KEY_LAST_SUGGESTION_TEXT = "last_suggestion_text"
        private const val KEY_LAST_SUGGESTION_CATEGORY = "last_suggestion_category"
        private const val KEY_ROTATION_INDEX = "rotation_index"

        private const val TICK_INTERVAL_MS = 30_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var initializedFromDisk = false

        @Volatile
        private var sessionStartedAtMs: Long = System.currentTimeMillis()

        @Volatile
        private var screenOffSinceMs: Long = 0L

        @Volatile
        private var dailyAccumulatedMs: Long = 0L

        @Volatile
        private var trackedDayEpoch: Long = LocalDate.now().toEpochDay()

        @Volatile
        private var lastTickMs: Long = System.currentTimeMillis()

        @Volatile
        private var breaksTakenToday: Int = 0

        @Volatile
        private var lastReminderAtMs: Long = 0L

        @Volatile
        private var lastMilestoneMinutes: Long = 0L

        @Volatile
        private var snoozedUntilMs: Long = 0L

        @Volatile
        private var rotationIndex: Int = 0

        private val _snapshot = MutableStateFlow(ScreenUsageHealthSnapshot())
        val snapshot: StateFlow<ScreenUsageHealthSnapshot> = _snapshot.asStateFlow()

        fun ensureStarted(context: Context) {
            val appContext = context.applicationContext
            restorePersistedState(appContext)
            runCatching {
                appContext.startService(
                    Intent(appContext, ScreenUsageHealthService::class.java)
                        .setAction(ACTION_START_HEALTH_SERVICE),
                )
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, ScreenUsageHealthService::class.java))
            }
        }

        @Synchronized
        fun markBreakTaken(context: Context) {
            val appContext = context.applicationContext
            restorePersistedState(appContext)
            val now = System.currentTimeMillis()
            sessionStartedAtMs = now
            screenOffSinceMs = 0L
            lastMilestoneMinutes = 0L
            snoozedUntilMs = 0L
            breaksTakenToday += 1
            persistState(appContext)
            refreshSnapshot(appContext, now)
            NotificationHelper.cancelHealthReminderNotification(appContext)
        }

        @Synchronized
        fun snoozeReminders(context: Context, minutes: Int = 15) {
            val appContext = context.applicationContext
            restorePersistedState(appContext)
            val now = System.currentTimeMillis()
            snoozedUntilMs = now + minutes.coerceIn(5, 120) * 60_000L
            persistState(appContext)
            refreshSnapshot(appContext, now)
            NotificationHelper.cancelHealthReminderNotification(appContext)
        }

        /**
         * Triggers an immediate, friendly voice health suggestion on demand
         * (for testing or when the owner taps "Test health reminder" in Settings).
         */
        fun triggerImmediateHealthSuggestion(context: Context): HealthReminderDecision {
            val appContext = context.applicationContext
            restorePersistedState(appContext)
            val now = System.currentTimeMillis()
            refreshSnapshot(appContext, now)
            val core = JarvisCore.get(appContext)
            val settings = core.settings.value
            val snap = _snapshot.value
            val categories = HealthSuggestionCategory.entries
            val category = categories[rotationIndex.mod(categories.size)]
            rotationIndex++

            val effectiveMinutes = snap.continuousSessionMinutes.coerceAtLeast(1L)
            val message = ScreenUsageHealthPolicy.buildFriendlyHealthSuggestion(
                category = category,
                title = settings.profile.title.ifBlank { "sir" },
                continuousMinutes = effectiveMinutes,
                dailyMinutes = snap.dailyScreenMinutes.coerceAtLeast(effectiveMinutes),
            )
            val decision = HealthReminderDecision(
                shouldRemind = true,
                shouldSpeakAloud = true,
                milestoneMinutes = effectiveMinutes,
                category = category,
                message = message,
                reason = "manual_trigger",
            )
            recordDeliveredReminder(appContext, decision, now)
            NotificationHelper.showHealthReminderNotification(
                context = appContext,
                categoryLabel = decision.category.label,
                message = decision.message,
            )
            core.deliverHealthSuggestion(decision)
            return decision
        }

        @Synchronized
        internal fun onScreenInteractiveChanged(context: Context, interactive: Boolean, now: Long) {
            val appContext = context.applicationContext
            restorePersistedState(appContext)
            val core = runCatching { JarvisCore.get(appContext) }.getOrNull()
            val breakResetMs = (core?.settings?.value?.health?.breakResetMinutes ?: 5)
                .coerceIn(1, 30) * 60_000L

            if (interactive) {
                if (screenOffSinceMs > 0L) {
                    val offDuration = now - screenOffSinceMs
                    if (offDuration >= breakResetMs) {
                        sessionStartedAtMs = now
                        lastMilestoneMinutes = 0L
                        breaksTakenToday += 1
                    }
                    screenOffSinceMs = 0L
                }
                lastTickMs = now
            } else if (screenOffSinceMs == 0L) {
                screenOffSinceMs = now
            }
            persistState(appContext)
            refreshSnapshot(appContext, now)
        }

        @Synchronized
        internal fun tickAndEvaluate(context: Context) {
            val appContext = context.applicationContext
            restorePersistedState(appContext)
            val now = System.currentTimeMillis()
            refreshSnapshot(appContext, now)

            val core = runCatching { JarvisCore.get(appContext) }.getOrNull() ?: return
            val appSettings = core.settings.value
            if (appSettings.profile.name.isBlank()) return

            val audio = appContext.getSystemService(AudioManager::class.java)
            val inCall = runCatching {
                val mode = audio?.mode ?: AudioManager.MODE_NORMAL
                mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_RINGTONE
            }.getOrDefault(false)

            val runtimeState = core.state.value
            val busy = runtimeState.generating || runtimeState.speaking || runtimeState.screenAutomationActive
            val snap = _snapshot.value

            val decision = ScreenUsageHealthPolicy.evaluate(
                settings = appSettings.health,
                ownerTitle = appSettings.profile.title,
                continuousMinutes = snap.continuousSessionMinutes,
                dailyMinutes = snap.dailyScreenMinutes,
                lastRemindedMilestoneMinutes = lastMilestoneMinutes,
                lastRemindedAtMs = lastReminderAtMs,
                snoozedUntilMs = snoozedUntilMs,
                nowMs = now,
                currentHour = LocalTime.now().hour,
                isScreenInteractive = snap.isScreenInteractive,
                isInCallOrCommunication = inCall,
                isAssistantBusy = busy,
                rotationIndex = rotationIndex,
            )

            if (decision.shouldRemind) {
                rotationIndex++
                recordDeliveredReminder(appContext, decision, now)
                NotificationHelper.showHealthReminderNotification(
                    context = appContext,
                    categoryLabel = decision.category.label,
                    message = decision.message,
                )
                if (decision.shouldSpeakAloud) {
                    core.deliverHealthSuggestion(decision)
                }
            }
        }

        @Synchronized
        private fun refreshSnapshot(context: Context, now: Long) {
            val todayEpoch = LocalDate.now().toEpochDay()
            if (todayEpoch != trackedDayEpoch) {
                trackedDayEpoch = todayEpoch
                dailyAccumulatedMs = 0L
                breaksTakenToday = 0
                lastMilestoneMinutes = 0L
            }

            val power = context.getSystemService(PowerManager::class.java)
            val interactive = runCatching { power?.isInteractive ?: true }.getOrDefault(true)
            val core = runCatching { JarvisCore.get(context) }.getOrNull()
            val healthSettings = core?.settings?.value?.health ?: HealthSettings()
            val breakResetMs = healthSettings.breakResetMinutes.coerceIn(1, 30) * 60_000L

            val elapsed = (now - lastTickMs).coerceAtLeast(0L)
            lastTickMs = now

            if (interactive) {
                if (screenOffSinceMs > 0L) {
                    if (now - screenOffSinceMs >= breakResetMs) {
                        sessionStartedAtMs = now
                        lastMilestoneMinutes = 0L
                        breaksTakenToday += 1
                    }
                    screenOffSinceMs = 0L
                }
                dailyAccumulatedMs += elapsed.coerceAtMost(TICK_INTERVAL_MS * 3)
            } else if (screenOffSinceMs == 0L) {
                screenOffSinceMs = now
            }

            val continuousMs = if (interactive) {
                (now - sessionStartedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }

            persistState(context)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _snapshot.update {
                it.copy(
                    serviceRunning = isRunning,
                    isScreenInteractive = interactive,
                    continuousSessionMinutes = continuousMs / 60_000L,
                    dailyScreenMinutes = (dailyAccumulatedMs / 60_000L).coerceAtLeast(continuousMs / 60_000L),
                    recommendedLimitMinutes = healthSettings.sessionLimitMinutes,
                    breaksTakenToday = breaksTakenToday,
                    lastReminderAtMs = lastReminderAtMs,
                    lastSuggestionText = prefs.getString(KEY_LAST_SUGGESTION_TEXT, null),
                    lastSuggestionCategory = prefs.getString(KEY_LAST_SUGGESTION_CATEGORY, null),
                )
            }
        }

        @Synchronized
        private fun recordDeliveredReminder(
            context: Context,
            decision: HealthReminderDecision,
            now: Long,
        ) {
            lastReminderAtMs = now
            if (decision.milestoneMinutes > lastMilestoneMinutes) {
                lastMilestoneMinutes = decision.milestoneMinutes
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_LAST_REMINDER_MS, lastReminderAtMs)
                .putLong(KEY_LAST_MILESTONE_MIN, lastMilestoneMinutes)
                .putString(KEY_LAST_SUGGESTION_TEXT, decision.message)
                .putString(KEY_LAST_SUGGESTION_CATEGORY, decision.category.label)
                .putInt(KEY_ROTATION_INDEX, rotationIndex)
                .apply()
            _snapshot.update {
                it.copy(
                    lastReminderAtMs = now,
                    lastSuggestionText = decision.message,
                    lastSuggestionCategory = decision.category.label,
                )
            }
        }

        @Synchronized
        private fun restorePersistedState(context: Context) {
            if (initializedFromDisk) return
            initializedFromDisk = true
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedDay = prefs.getLong(KEY_DAY_EPOCH, LocalDate.now().toEpochDay())
            val today = LocalDate.now().toEpochDay()
            trackedDayEpoch = today
            if (savedDay == today) {
                dailyAccumulatedMs = prefs.getLong(KEY_DAILY_ACCUMULATED_MS, 0L)
                breaksTakenToday = prefs.getInt(KEY_BREAKS_TODAY, 0)
            } else {
                dailyAccumulatedMs = 0L
                breaksTakenToday = 0
            }
            sessionStartedAtMs = prefs.getLong(KEY_SESSION_STARTED_MS, now).coerceAtMost(now)
            screenOffSinceMs = prefs.getLong(KEY_SCREEN_OFF_SINCE_MS, 0L)
            lastReminderAtMs = prefs.getLong(KEY_LAST_REMINDER_MS, 0L)
            lastMilestoneMinutes = prefs.getLong(KEY_LAST_MILESTONE_MIN, 0L)
            snoozedUntilMs = prefs.getLong(KEY_SNOOZED_UNTIL_MS, 0L)
            rotationIndex = prefs.getInt(KEY_ROTATION_INDEX, 0)
            lastTickMs = now
        }

        @Synchronized
        private fun persistState(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_DAY_EPOCH, trackedDayEpoch)
                .putLong(KEY_DAILY_ACCUMULATED_MS, dailyAccumulatedMs)
                .putLong(KEY_SESSION_STARTED_MS, sessionStartedAtMs)
                .putLong(KEY_SCREEN_OFF_SINCE_MS, screenOffSinceMs)
                .putInt(KEY_BREAKS_TODAY, breaksTakenToday)
                .putLong(KEY_LAST_REMINDER_MS, lastReminderAtMs)
                .putLong(KEY_LAST_MILESTONE_MIN, lastMilestoneMinutes)
                .putLong(KEY_SNOOZED_UNTIL_MS, snoozedUntilMs)
                .putInt(KEY_ROTATION_INDEX, rotationIndex)
                .apply()
        }
    }
}

/**
 * BroadcastReceiver for the "Took a Break" and "Snooze 15m" notification actions
 * on health reminder notifications.
 */
class HealthBreakActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        when (intent?.action) {
            ScreenUsageHealthService.ACTION_RESET_BREAK -> {
                ScreenUsageHealthService.markBreakTaken(ctx)
            }

            ScreenUsageHealthService.ACTION_SNOOZE_REMINDER -> {
                ScreenUsageHealthService.snoozeReminders(ctx, minutes = 15)
            }
        }
    }
}
