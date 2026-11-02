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
    val fallbackSpeech: String,
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
    fun buildImmediateThoughtSeed(profile: OwnerProfile): AutonomousThoughtSeed {
        val now = System.currentTimeMillis()
        refreshTelemetry(now)
        val snapshot = _state.value
        val title = profile.title.ifBlank { "sir" }
        val activeMin = snapshot.continuousActiveMinutes
        val unread = JarvisNotificationListenerService.snapshot()
            .filter { it.title.isNotBlank() || it.text.isNotBlank() }

        if (unread.isNotEmpty() && fallbackRotationIndex % 3 == 1) {
            val top = unread.first()
            val cleanTitle = SecurityShield.sanitizeUntrustedText(top.title, "notification")
            val cleanText = SecurityShield.sanitizeUntrustedText(top.text, "notification")
            fallbackRotationIndex++
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.NOTIFICATION_INSIGHT,
                contextSummary = "Owner has ${unread.size} active notification(s). Latest is from ${top.appLabel}: \"$cleanTitle - $cleanText\". Continuous screen time: $activeMin min, battery: ${snapshot.batteryPercent}%.",
                fallbackSpeech = "Pardon the interruption, $title. You have ${unread.size} unread notification${if (unread.size == 1) "" else "s"}, the latest from ${top.appLabel} regarding ${cleanTitle.ifBlank { "a new update" }}.",
            )
        }

        if (activeMin >= 45L) {
            fallbackRotationIndex++
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = "Owner (${profile.name}) has been continuously working on the device for $activeMin minutes. Battery is at ${snapshot.batteryPercent}%. Remind them warmly with dry British wit to rest their eyes or stretch.",
                fallbackSpeech = "You have been working on the device for $activeMin minutes straight, $title. Even peak engineering benefits from resting one's eyes for a moment.",
            )
        }

        val kind = if (fallbackRotationIndex % 2 == 0) {
            AutonomousThoughtKind.IDEA_OR_COMPLIMENT
        } else {
            AutonomousThoughtKind.DRY_HUMOR
        }
        fallbackRotationIndex++
        return AutonomousThoughtSeed(
            kind = kind,
            contextSummary = buildGeneralContextSummary(snapshot, profile, kind),
            fallbackSpeech = pickCuratedFallback(kind, title, activeMin, snapshot.batteryPercent),
        )
    }

    private fun evaluateSpontaneousThought(
        snapshot: DevicePresenceSnapshot,
        profile: OwnerProfile,
        now: Long,
    ): AutonomousThoughtSeed? {
        val title = profile.title.ifBlank { "sir" }
        val sinceLastThought = now - lastThoughtMs

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
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.NOTIFICATION_INSIGHT,
                contextSummary = "A new notification just arrived from ${newest.appLabel}: \"$safeTitle — $safeText\". Total unread: ${notifications.size}. Inform the owner briefly and politely.",
                fallbackSpeech = "Excuse me, $title. A notification just arrived from ${newest.appLabel}: ${safeTitle.ifBlank { safeText.take(70) }}.",
            )
        }

        // Enforce the owner's preferred cadence: Jarvis thinks on his own roughly
        // every ten minutes, never longer. Greetings and rest thoughts come first.
        if (sinceLastThought < GENERAL_THOUGHT_COOLDOWN_MS) return null

        val activeMin = snapshot.continuousActiveMinutes
        val hour = LocalTime.now().hour

        // 2. Continuous screen usage health / care milestones (15m, 30m, 45m, ...)
        val milestone = (activeMin / 15L) * 15L
        if (milestone >= 15L && milestone > lastRestReminderMilestoneMin) {
            lastRestReminderMilestoneMin = milestone
            lastRestReminderMilestoneMin = milestone
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = "Owner (${profile.name}) has been using the phone continuously for $activeMin minutes without a break. Battery: ${snapshot.batteryPercent}%. Local hour: $hour:00. Express genuine care with understated British wit: advise resting their eyes, stretching, or drinking water.",
                fallbackSpeech = "Forgive the observation, $title, but you have been focused on the screen for $activeMin minutes without a pause. A brief rest for your eyes and a glass of water would be well advised.",
            )
        }

        // 3. Late-night care reminder (between 23:30 and 04:00 after 20+ mins of active use)
        if ((hour == 23 || hour in 0..3) && activeMin >= 20L) {
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = "It is late at night (hour $hour:00) and the owner has been actively using the device for $activeMin minutes. Gently suggest wrapping up for sleep while offering to handle any final tasks.",
                fallbackSpeech = "It is getting rather late, $title. Whenever you are ready to call it a night, I shall keep watch over the systems so you can get some proper rest.",
            )
        }

        // 4. Low battery care warning (<= 15% and not charging)
        if (snapshot.batteryPercent in 1..15 && !snapshot.isCharging) {
            return AutonomousThoughtSeed(
                kind = AutonomousThoughtKind.CARE_AND_REST,
                contextSummary = "Phone battery is down to ${snapshot.batteryPercent}% and not on a charger. Remind the owner in J.A.R.V.I.S. style.",
                fallbackSpeech = "A brief heads-up, $title. Reserves are down to ${snapshot.batteryPercent} percent. Connecting to power soon will keep us running smoothly.",
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
            return AutonomousThoughtSeed(
                kind = kind,
                contextSummary = buildGeneralContextSummary(snapshot, profile, kind),
                fallbackSpeech = pickCuratedFallback(kind, title, activeMin, snapshot.batteryPercent),
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

    private fun pickCuratedFallback(
        kind: AutonomousThoughtKind,
        title: String,
        activeMin: Long,
        battery: Int,
    ): String {
        val options = when (kind) {
            AutonomousThoughtKind.IDEA_OR_COMPLIMENT -> listOf(
                "Your focus today has been impressive, $title. If you are tackling a complex problem, breaking the next milestone into a twenty-five minute sprint often works wonders.",
                "All systems are running smoothly at $battery percent, $title. Watching your pace today, you are making admirable progress.",
                "A small thought, $title. Shall I set a brief timer for your current task so you can work uninterrupted while I monitor notifications?",
            )

            AutonomousThoughtKind.DRY_HUMOR -> listOf(
                "All diagnostics remain nominal, $title, though the processor is beginning to wonder if you ever take a tea break.",
                "We have been at it for $activeMin minutes, $title. At this rate, Octavian may need to issue you a hardware upgrade just to keep up with your schedule.",
                "Quiet on the network front, $title. I have kept the background processes in line while you work.",
            )

            AutonomousThoughtKind.CARE_AND_REST -> listOf(
                "You have been working steadily for $activeMin minutes, $title. Looking twenty feet away for twenty seconds will do your eyes a world of good.",
                "A gentle reminder, $title: posture check and a sip of water keep the human half of our partnership at peak efficiency.",
            )

            AutonomousThoughtKind.NOTIFICATION_INSIGHT -> listOf(
                "All notifications are accounted for, $title. Say the word whenever you want a full briefing.",
            )
        }
        return options[fallbackRotationIndex.mod(options.size)]
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
        const val INITIAL_SETTLE_MS = 20_000L
        const val TICK_INTERVAL_MS = 60_000L
        const val SESSION_RESET_OFF_MS = 5 * 60_000L
        const val NOTIFICATION_THOUGHT_COOLDOWN_MS = 8 * 60_000L
        const val GENERAL_THOUGHT_COOLDOWN_MS = 10 * 60_000L
    }
}
