package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.security.SecurityShield
import com.jarvis.assistant.settings.OwnerProfile
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AutonomousThoughtKind(val label: String) {
    CARE_AND_REST("Wellbeing & Care"),
    NOTIFICATION_INSIGHT("Notification Briefing"),
    DRY_HUMOR("Dry British Wit"),
    IDEA_OR_COMPLIMENT("Idea & Compliment"),
}

data class DevicePresenceSnapshot(
    val isScreenOn: Boolean = true,
    val continuousActiveMinutes: Long = 0L,
    val totalActiveMinutesToday: Long = 0L,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val unreadNotifications: Int = 0,
    val lastThoughtAt: Long = 0L,
    val lastThoughtKind: String? = null,
)

data class AutonomousThoughtSeed(
    val kind: AutonomousThoughtKind,
    val contextSummary: String,
    val prompt: String = "",
    val fallbackSpeech: String = "",
)

/**
 * Monitors the owner's live device state (screen interactive state, continuous
 * working session duration, battery health, late-night hours, and incoming
 * status-bar notifications) and triggers rare, high-signal autonomous thoughts
 * when appropriate.
 *
 * Key design principles:
 * - Never spams: enforces strict minimum cooldowns between spontaneous thoughts
 *   and only speaks when the screen is on and the owner is not already in the
 *   middle of a voice turn.
 * - Tracks continuous active usage: if the screen turns off for less than 5
 *   minutes, the active work session continues; if off for 5+ minutes, the
 *   continuous session timer resets cleanly.
 */
class DevicePresenceMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onTriggerAutonomousThought: suspend (AutonomousThoughtSeed) -> Unit,
) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)

    private val _state = MutableStateFlow(DevicePresenceSnapshot())
    val state: StateFlow<DevicePresenceSnapshot> = _state.asStateFlow()

    @Volatile
    private var sessionStartedAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var screenOffSinceMs: Long = 0L

    @Volatile
    private var accumulatedTodayMs: Long = 0L

    @Volatile
    private var lastTickMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastThoughtMs: Long = 0L

    @Volatile
    private var lastRestReminderMilestoneMin: Long = 0L

    @Volatile
    private var lastNotifiedNotificationKey: String? = null

    @Volatile
    private var fallbackRotationIndex: Int = 0

    private var monitorJob: Job? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    val offDuration = if (screenOffSinceMs > 0L) now - screenOffSinceMs else 0L
                    if (offDuration >= SESSION_RESET_OFF_MS) {
                        // Owner took a real break; reset the continuous session counter
                        sessionStartedAtMs = now
                        lastRestReminderMilestoneMin = 0L
                    }
                    screenOffSinceMs = 0L
                    lastTickMs = now
                    refreshTelemetry(now)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    screenOffSinceMs = now
                    refreshTelemetry(now)
                }
            }
        }
    }

    /** Stops all monitoring and releases the screen-state receiver. */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }

    fun start(profileProvider: () -> OwnerProfile, canSpeakNow: () -> Boolean) {
        registerReceiverIfNeeded()
        refreshTelemetry(System.currentTimeMillis())
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.Default) {
            delay(INITIAL_SETTLE_MS)
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                refreshTelemetry(now)

                val snapshot = _state.value
                if (!snapshot.isScreenOn || !canSpeakNow()) continue

                val profile = profileProvider()
                if (profile.name.isBlank()) continue

                val candidate = evaluateSpontaneousThought(snapshot, profile, now)
                if (candidate != null) {
                    lastThoughtMs = now
                    _state.update {
                        it.copy(
                            lastThoughtAt = now,
                            lastThoughtKind = candidate.kind.label,
                        )
                    }
                    runCatching { onTriggerAutonomousThought(candidate) }
                }
            }
        }
    }

    /**
     * Generates an immediate autonomous thought seed on demand (e.g. when the
     * owner taps "Autonomous Thought" in Quick Panel or Settings).
     */
    private fun resolveActiveApp(): String? {
        val pkg = com.jarvis.assistant.accessibility.JarvisAccessibilityService.currentForegroundPackage() ?: return null
        return runCatching {
            val pm = appContext.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    /**
     * Builds the full internal cognition prompt for J.A.R.V.I.S.'s neural brain,
     * grounding the thought strictly in real-time phone telemetry so that autonomous
     * observations always originate from the AI model rather than static app code.
     */
    fun buildAutonomousPrompt(
        snapshot: DevicePresenceSnapshot,
        profile: OwnerProfile,
        kind: AutonomousThoughtKind,
        currentApp: String?,
        detailContext: String = "",
    ): String = buildString {
        appendLine("[BACKGROUND MONITORING OBSERVATION]")
        appendLine("You are J.A.R.V.I.S., observing your owner's live device state and continuous activity in the background.")
        appendLine("Live telemetry:")
        appendLine("- Owner: ${profile.name} (Preferred title: ${profile.title.ifBlank { "sir" }}${if (profile.occupation.isNotBlank()) ", occupation: ${profile.occupation}" else ""}${if (profile.preferences.isNotBlank()) ", interests: ${profile.preferences}" else ""})")
        appendLine("- Screen session: ${snapshot.continuousActiveMinutes} continuous minutes (Total today: ${snapshot.totalActiveMinutesToday} minutes)")
        if (!currentApp.isNullOrBlank()) {
            appendLine("- Current foreground application: $currentApp")
        }
        appendLine("- Battery: ${snapshot.batteryPercent}% (${if (snapshot.isCharging) "charging" else "on battery"})")
        appendLine("- Local time: ${LocalTime.now().toString().take(5)} (Hour ${LocalTime.now().hour}:00)")
        if (snapshot.unreadNotifications > 0) {
            appendLine("- Unread notifications count: ${snapshot.unreadNotifications}")
        }
        if (detailContext.isNotBlank()) {
            appendLine("- Background event context: $detailContext")
        }
        appendLine()
        appendLine("Instructions:")
        appendLine("Formulate your own spontaneous, genuine J.A.R.V.I.S. thought or verbal status remark addressed to your owner.")
        appendLine("- Must originate purely from your neural intelligence, never canned templates.")
        appendLine("- Tone: Calm, impeccably polite, dry British wit, understated confidence, loyal and observant.")
        appendLine("- Length: 1 to 3 concise spoken sentences. Never robotic, no emojis, no exclamation marks.")
        when (kind) {
            AutonomousThoughtKind.IDEA_OR_COMPLIMENT ->
                appendLine("- Thought focus: An insightful observation about their focus, a habit tip, or a thoughtful perspective.")
            AutonomousThoughtKind.DRY_HUMOR ->
                appendLine("- Thought focus: A clever, understated J.A.R.V.I.S.-style dry witty remark on their workflow or device state.")
            AutonomousThoughtKind.CARE_AND_REST ->
                appendLine("- Thought focus: Genuine wellbeing care — encouraging resting eyes, hydrating, or calling it a night if late.")
            AutonomousThoughtKind.NOTIFICATION_INSIGHT ->
                appendLine("- Thought focus: A high-signal briefing on what just arrived without cluttering them.")
        }
    }

    /**
     * Generates an immediate autonomous thought seed on demand (e.g. when the
     * owner taps "Autonomous Thought" in Quick Panel or Settings).
     */
    fun buildImmediateThoughtSeed(profile: OwnerProfile): AutonomousThoughtSeed {
        val now = System.currentTimeMillis()
        refreshTelemetry(now)
        val snapshot = _state.value
        val title = profile.title.ifBlank { "sir" }
        val activeMin = snapshot.continuousActiveMinutes
        val currentApp = resolveActiveApp()
        val unread = JarvisNotificationListenerService.snapshot()
            .filter { it.title.isNotBlank() || it.text.isNotBlank() }

        if (unread.isNotEmpty() && fallbackRotationIndex % 3 == 1) {
            val top = unread.first()
            val cleanTitle = SecurityShield.sanitizeUntrustedText(top.title, "notification")
            val cleanText = SecurityShield.sanitizeUntrustedText(top.text, "notification")
            fallbackRotationIndex++
            val detail = "Owner has ${unread.size} active notification(s). Latest is from ${top.appLabel}: \"$cleanTitle — $cleanText\"."
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.NOTIFICATION_INSIGHT,
                contextSummary = detail,
                prompt = buildAutonomousPrompt(snapshot, profile, AutonomousThoughtKind.NOTIFICATION_INSIGHT, currentApp, detail),
                fallbackSpeech = generateDynamicFallback(AutonomousThoughtKind.NOTIFICATION_INSIGHT, title, activeMin, snapshot.batteryPercent, currentApp),
            )
        }

        if (activeMin >= 45L) {
            fallbackRotationIndex++
            val detail = "Continuous screen time reached $activeMin minutes without a break."
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = "Owner has been continuously working on the device for $activeMin minutes. Battery is at ${snapshot.batteryPercent}%.",
                prompt = buildAutonomousPrompt(snapshot, profile, AutonomousThoughtKind.CARE_AND_REST, currentApp, detail),
                fallbackSpeech = generateDynamicFallback(AutonomousThoughtKind.CARE_AND_REST, title, activeMin, snapshot.batteryPercent, currentApp),
            )
        }

        val kind = if (fallbackRotationIndex % 2 == 0) {
            AutonomousThoughtKind.IDEA_OR_COMPLIMENT
        } else {
            AutonomousThoughtKind.DRY_HUMOR
        }
        fallbackRotationIndex++
        val summary = buildGeneralContextSummary(snapshot, profile, kind)
        return AutonomousThoughtSeed(
            kind = kind,
            contextSummary = summary,
            prompt = buildAutonomousPrompt(snapshot, profile, kind, currentApp, summary),
            fallbackSpeech = generateDynamicFallback(kind, title, activeMin, snapshot.batteryPercent, currentApp),
        )
    }

    private fun evaluateSpontaneousThought(
        snapshot: DevicePresenceSnapshot,
        profile: OwnerProfile,
        now: Long,
    ): AutonomousThoughtSeed? {
        val title = profile.title.ifBlank { "sir" }
        val sinceLastThought = now - lastThoughtMs
        val currentApp = resolveActiveApp()

        // 1. Check for important newly arrived notifications (8-minute gap)
        val notifications = JarvisNotificationListenerService.snapshot()
        val newest = notifications.firstOrNull()
        if (newest != null &&
            newest.key != lastNotifiedNotificationKey &&
            now - newest.postedAt < 3 * 60_000L &&
            sinceLastThought >= NOTIFICATION_THOUGHT_COOLDOWN_MS
        ) {
            lastNotifiedNotificationKey = newest.key
            val safeTitle = SecurityShield.sanitizeUntrustedText(newest.title, "notification")
            val safeText = SecurityShield.sanitizeUntrustedText(newest.text, "notification")
            val detail = "A new notification just arrived from ${newest.appLabel}: \"$safeTitle — $safeText\". Total unread: ${notifications.size}."
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.NOTIFICATION_INSIGHT,
                contextSummary = detail,
                prompt = buildAutonomousPrompt(snapshot, profile, AutonomousThoughtKind.NOTIFICATION_INSIGHT, currentApp, detail),
                fallbackSpeech = generateDynamicFallback(AutonomousThoughtKind.NOTIFICATION_INSIGHT, title, snapshot.continuousActiveMinutes, snapshot.batteryPercent, currentApp),
            )
        }

        // Enforce cadence: Jarvis thinks spontaneously roughly every 10 minutes
        if (sinceLastThought < GENERAL_THOUGHT_COOLDOWN_MS) return null

        val activeMin = snapshot.continuousActiveMinutes
        val hour = LocalTime.now().hour

        // 2. Continuous screen usage health / care milestones (15m, 30m, 45m, ...)
        val milestone = (activeMin / 15L) * 15L
        if (milestone >= 15L && milestone > lastRestReminderMilestoneMin) {
            lastRestReminderMilestoneMin = milestone
            val detail = "Continuous screen session has reached $activeMin minutes without a break. Local time is $hour:00."
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = detail,
                prompt = buildAutonomousPrompt(snapshot, profile, AutonomousThoughtKind.CARE_AND_REST, currentApp, detail),
                fallbackSpeech = generateDynamicFallback(AutonomousThoughtKind.CARE_AND_REST, title, activeMin, snapshot.batteryPercent, currentApp),
            )
        }

        // 3. Late-night care reminder (between 23:00 and 04:00 after 20+ mins of active use)
        if ((hour == 23 || hour in 0..3) && activeMin >= 20L) {
            val detail = "Late night working at hour $hour:00 after $activeMin minutes of active screen time."
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = detail,
                prompt = buildAutonomousPrompt(snapshot, profile, AutonomousThoughtKind.CARE_AND_REST, currentApp, detail),
                fallbackSpeech = generateDynamicFallback(AutonomousThoughtKind.CARE_AND_REST, title, activeMin, snapshot.batteryPercent, currentApp),
            )
        }

        // 4. Low battery care warning (<= 15% and not charging)
        if (snapshot.batteryPercent in 1..15 && !snapshot.isCharging) {
            val detail = "Battery is down to ${snapshot.batteryPercent}% and not on a charger."
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = detail,
                prompt = buildAutonomousPrompt(snapshot, profile, AutonomousThoughtKind.CARE_AND_REST, currentApp, detail),
                fallbackSpeech = generateDynamicFallback(AutonomousThoughtKind.CARE_AND_REST, title, activeMin, snapshot.batteryPercent, currentApp),
            )
        }

        // 5. Spontaneous compliment, creative idea, or dry joke after ~8 minutes of work
        if (activeMin >= 8L) {
            val kind = if (fallbackRotationIndex % 2 == 0) {
                AutonomousThoughtKind.IDEA_OR_COMPLIMENT
            } else {
                AutonomousThoughtKind.DRY_HUMOR
            }
            fallbackRotationIndex++
            val detail = buildGeneralContextSummary(snapshot, profile, kind)
            return AutonomousThoughtSeed(
                kind = kind,
                contextSummary = detail,
                prompt = buildAutonomousPrompt(snapshot, profile, kind, currentApp, detail),
                fallbackSpeech = generateDynamicFallback(kind, title, activeMin, snapshot.batteryPercent, currentApp),
            )
        }

        return null
    }

    private fun buildGeneralContextSummary(
        snapshot: DevicePresenceSnapshot,
        profile: OwnerProfile,
        kind: AutonomousThoughtKind,
    ): String = buildString {
        append("Owner: ${profile.name} (title: ${profile.title.ifBlank { "sir" }}" )
        if (profile.occupation.isNotBlank()) append(", occupation: ${profile.occupation}")
        if (profile.preferences.isNotBlank()) append(", interests: ${profile.preferences}")
        append("). ")
        append("Device state: Screen is ON, continuous active session = ${snapshot.continuousActiveMinutes} minutes, ")
        append("today's active screen time = ${snapshot.totalActiveMinutesToday} minutes, ")
        append("battery = ${snapshot.batteryPercent}% (${if (snapshot.isCharging) "charging" else "on battery"}), ")
        append("unread notifications = ${snapshot.unreadNotifications}. ")
        when (kind) {
            AutonomousThoughtKind.IDEA_OR_COMPLIMENT ->
                append("Share one brief, genuine compliment on the owner's focus or one practical, intelligent idea/habit suggestion tailored to them.")
            AutonomousThoughtKind.DRY_HUMOR ->
                append("Share one brief, clever J.A.R.V.I.S.-style dry witty observation or subtle joke while checking in on the owner.")
            AutonomousThoughtKind.CARE_AND_REST ->
                append("Encourage the owner to take a short healthy rest break, blink, or hydrate.")
            AutonomousThoughtKind.NOTIFICATION_INSIGHT ->
                append("Summarize the owner's pending notifications concisely.")
        }
    }

    private fun generateDynamicFallback(
        kind: AutonomousThoughtKind,
        title: String,
        activeMin: Long,
        battery: Int,
        app: String?,
    ): String {
        val appMention = if (!app.isNullOrBlank()) " on $app" else ""
        return when (kind) {
            AutonomousThoughtKind.CARE_AND_REST ->
                "You have been working steadily for $activeMin minutes$appMention, $title. Taking twenty seconds to rest your eyes and hydrate will keep you operating at peak efficiency."
            AutonomousThoughtKind.DRY_HUMOR ->
                "All diagnostics remain nominal at $battery percent, $title. I am keeping watch over the background processes while you work$appMention."
            AutonomousThoughtKind.IDEA_OR_COMPLIMENT ->
                "Your continuous focus$appMention has been admirable today, $title. All background systems are clear."
            AutonomousThoughtKind.NOTIFICATION_INSIGHT ->
                "I am monitoring your incoming alerts, $title. Let me know whenever you would like a briefing."
        }
    }

    private fun refreshTelemetry(now: Long) {
        val interactive = runCatching { powerManager?.isInteractive ?: true }.getOrDefault(true)
        val elapsed = (now - lastTickMs).coerceAtLeast(0L)
        lastTickMs = now

        if (interactive) {
            if (screenOffSinceMs > 0L) {
                val offDuration = now - screenOffSinceMs
                if (offDuration >= SESSION_RESET_OFF_MS) {
                    sessionStartedAtMs = now
                }
                screenOffSinceMs = 0L
            }
            accumulatedTodayMs += elapsed.coerceAtMost(TICK_INTERVAL_MS * 2)
        } else if (screenOffSinceMs == 0L) {
            screenOffSinceMs = now
        }

        val continuousMs = if (interactive) {
            (now - sessionStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }

        val batteryLevel = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        }.getOrDefault(100).coerceIn(0, 100)

        val charging = runCatching {
            batteryManager?.isCharging ?: false
        }.getOrDefault(false)

        val unreadCount = JarvisNotificationListenerService.unreadCount()

        _state.update {
            it.copy(
                isScreenOn = interactive,
                continuousActiveMinutes = continuousMs / 60_000L,
                totalActiveMinutesToday = accumulatedTodayMs / 60_000L,
                batteryPercent = batteryLevel,
                isCharging = charging,
                unreadNotifications = unreadCount,
            )
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                appContext,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    private companion object {
        const val INITIAL_SETTLE_MS = 5_000L
        const val TICK_INTERVAL_MS = 15_000L
        const val SESSION_RESET_OFF_MS = 5 * 60_000L
        const val NOTIFICATION_THOUGHT_COOLDOWN_MS = 1_000L
        const val GENERAL_THOUGHT_COOLDOWN_MS = 10 * 60_000L
    }
}
