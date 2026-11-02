package com.jarvis.assistant.accessibility

import android.content.Context
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Preconditions required before an autonomous step can execute.
 */
@Serializable
sealed class StepPrecondition {
    @Serializable
    data class RequireForegroundPackage(val packageName: String) : StepPrecondition()
    @Serializable
    data class RequireElementVisible(val targetLabel: String) : StepPrecondition()
    @Serializable
    data class RequireKeyboardVisible(val expected: Boolean) : StepPrecondition()
    @Serializable
    data object None : StepPrecondition()
}

/**
 * Expected postconditions to verify whether a step succeeded.
 */
@Serializable
sealed class StepPostcondition {
    @Serializable
    data class ExpectForegroundPackage(val packageName: String) : StepPostcondition()
    @Serializable
    data class ExpectUiDelta(val targetLabel: String = "") : StepPostcondition()
    @Serializable
    data class ExpectTextEntered(val expectedText: String) : StepPostcondition()
    @Serializable
    data class ExpectMediaPlaying(val timeoutMs: Long = 3000L) : StepPostcondition()
    @Serializable
    data class ExpectDialogDismissed(val dialogTitle: String) : StepPostcondition()
    @Serializable
    data object None : StepPostcondition()
}

@Serializable
enum class StepExecutionStatus {
    PENDING,
    IN_PROGRESS,
    VERIFIED_COMPLETED,
    FAILED,
    SKIPPED,
    INTERRUPTED_BY_USER,
}

/**
 * A concrete, verifiable step in an autonomous phone operation.
 */
@Serializable
data class AutonomousStep(
    val stepIndex: Int,
    val title: String,
    val actionType: HumanInteractionType,
    val targetQuery: String,
    val payloadText: String? = null,
    val precondition: StepPrecondition = StepPrecondition.None,
    val postcondition: StepPostcondition = StepPostcondition.None,
    val timeoutMs: Long = 4000L,
    val maxRetries: Int = 2,
    val isConsequential: Boolean = false,
    var status: StepExecutionStatus = StepExecutionStatus.PENDING,
    var failureReason: String? = null,
)

/**
 * Complete multi-step plan for autonomous phone control.
 */
@Serializable
data class AutonomousExecutionPlan(
    val planId: String,
    val objective: String,
    val targetApp: String,
    val createdAt: Long = System.currentTimeMillis(),
    val steps: List<AutonomousStep>,
    var activeStepIndex: Int = 0,
    var isCompleted: Boolean = false,
    var isPaused: Boolean = false,
    var isCancelled: Boolean = false,
    var lastVerifiedPackage: String = "",
) {
    val completedStepsCount: Int
        get() = steps.count { it.status == StepExecutionStatus.VERIFIED_COMPLETED }

    val currentStep: AutonomousStep?
        get() = steps.getOrNull(activeStepIndex)
}

/**
 * Validated screen coordinates tagged with a screen version to eliminate
 * stale-coordinate taps across screen transitions.
 */
data class ValidatedCoordinate(
    val x: Float,
    val y: Float,
    val screenVersion: Long,
    val elementLabel: String,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    fun isStale(currentScreenVersion: Long, maxAgeMs: Long = 2500L): Boolean {
        if (currentScreenVersion != screenVersion) return true
        if (System.currentTimeMillis() - capturedAt > maxAgeMs) return true
        return false
    }
}

/**
 * Dynamic Screen Reasoner that tracks screen state evolution, monitors version
 * increments, and enforces stale-coordinate rejection.
 */
class DynamicScreenReasoner {
    private val versionCounter = AtomicLong(1L)
    @Volatile private var lastKnownFingerprint: UiStateFingerprint? = null

    val currentScreenVersion: Long
        get() = versionCounter.get()

    /**
     * Updates screen state and increments version if the screen changed.
     */
    fun onNewScreenObserved(newFingerprint: UiStateFingerprint): Boolean {
        val prev = lastKnownFingerprint
        lastKnownFingerprint = newFingerprint
        if (prev == null || prev.treeSignatureHash != newFingerprint.treeSignatureHash ||
            prev.packageName != newFingerprint.packageName ||
            prev.nodeCount != newFingerprint.nodeCount
        ) {
            versionCounter.incrementAndGet()
            return true
        }
        return false
    }

    fun tagCoordinate(x: Float, y: Float, label: String): ValidatedCoordinate =
        ValidatedCoordinate(x, y, versionCounter.get(), label)
}

/**
 * Human Takeover System ensuring the user always retains ultimate control.
 *
 * If the user touches the physical screen while automation is active,
 * gestures are cancelled immediately, active plans are paused, and control
 * is returned to the user without interference.
 */
object HumanTakeoverManager {
    private val isTakeoverActive = AtomicBoolean(false)
    private val isAutomationRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    var onTakeoverTriggered: (() -> Unit)? = null

    fun setAutomationRunning(running: Boolean) {
        isAutomationRunning.set(running)
        if (running) {
            isTakeoverActive.set(false)
            isPaused.set(false)
            isCancelled.set(false)
        }
    }

    fun onUserManualTouch() {
        if (isAutomationRunning.get()) {
            isTakeoverActive.set(true)
            isPaused.set(true)
            isAutomationRunning.set(false)
            onTakeoverTriggered?.invoke()
        }
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
        isTakeoverActive.set(false)
        isAutomationRunning.set(true)
    }

    fun stop() {
        isCancelled.set(true)
        isAutomationRunning.set(false)
        isPaused.set(false)
    }

    val shouldHalt: Boolean
        get() = isTakeoverActive.get() || isPaused.get() || isCancelled.get()

    val canResume: Boolean
        get() = isPaused.get() && !isCancelled.get()
}

/**
 * Intelligent Task Planner for Autonomous Phone Operations.
 *
 * Synthesizes detailed, verifiable multi-step execution plans for complex
 * operations across YouTube, WhatsApp, Settings, Chrome, and other apps.
 */
object AutonomousTaskPlanner {

    fun planFromObjective(objective: String): AutonomousExecutionPlan {
        val lower = objective.lowercase(Locale.ROOT).trim()

        return when {
            // Workflow: YouTube search and play
            lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("watch")) -> {
                val query = extractQuery(objective, listOf("for ", "search ", "about ", "on "))
                    .ifBlank { "Android tutorials" }
                planYouTubeSearchAndPlay(query, objective)
            }

            // Workflow: WhatsApp message send
            lower.contains("whatsapp") && (lower.contains("send") || lower.contains("message") || lower.contains("tell")) -> {
                val recipient = extractRecipient(objective)
                val msg = extractMessage(objective)
                planWhatsAppSendMessage(recipient, msg, objective)
            }

            // Workflow: Settings navigation (e.g. dark mode, sound, display)
            lower.contains("settings") -> {
                val targetSetting = extractSettingTarget(objective)
                planSettingsNavigation(targetSetting, objective)
            }

            // General multi-step fallback
            else -> {
                planGenericAppOperation(objective)
            }
        }
    }

    private fun planYouTubeSearchAndPlay(query: String, rawObjective: String): AutonomousExecutionPlan {
        val steps = listOf(
            AutonomousStep(
                stepIndex = 1,
                title = "Launch YouTube application",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "com.google.android.youtube",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectForegroundPackage("com.google.android.youtube"),
            ),
            AutonomousStep(
                stepIndex = 2,
                title = "Verify YouTube is in foreground",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "",
                precondition = StepPrecondition.RequireForegroundPackage("com.google.android.youtube"),
                postcondition = StepPostcondition.ExpectForegroundPackage("com.google.android.youtube"),
            ),
            AutonomousStep(
                stepIndex = 3,
                title = "Locate search button or search bar",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "Search YouTube",
                precondition = StepPrecondition.RequireForegroundPackage("com.google.android.youtube"),
                postcondition = StepPostcondition.ExpectUiDelta("Search bar opened"),
            ),
            AutonomousStep(
                stepIndex = 4,
                title = "Activate search input field",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "Search YouTube",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Input active"),
            ),
            AutonomousStep(
                stepIndex = 5,
                title = "Enter search query '$query'",
                actionType = HumanInteractionType.ENTER_TEXT,
                targetQuery = "Search YouTube",
                payloadText = query,
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectTextEntered(query),
            ),
            AutonomousStep(
                stepIndex = 6,
                title = "Submit search query",
                actionType = HumanInteractionType.KEYBOARD_ACTION,
                targetQuery = "Search",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Search submitted"),
            ),
            AutonomousStep(
                stepIndex = 7,
                title = "Wait for search results to load",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Results rendered"),
            ),
            AutonomousStep(
                stepIndex = 8,
                title = "Identify the first video result card",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "first video",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Video tapped"),
            ),
            AutonomousStep(
                stepIndex = 9,
                title = "Activate first video",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "first video",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectMediaPlaying(4000L),
            ),
            AutonomousStep(
                stepIndex = 10,
                title = "Verify video playback started",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectMediaPlaying(3000L),
            ),
        )

        return AutonomousExecutionPlan(
            planId = "plan_yt_${System.currentTimeMillis()}",
            objective = rawObjective,
            targetApp = "com.google.android.youtube",
            steps = steps,
        )
    }

    private fun planWhatsAppSendMessage(recipient: String, message: String, rawObjective: String): AutonomousExecutionPlan {
        val steps = listOf(
            AutonomousStep(
                stepIndex = 1,
                title = "Launch WhatsApp to conversation with $recipient",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "com.whatsapp",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectForegroundPackage("com.whatsapp"),
            ),
            AutonomousStep(
                stepIndex = 2,
                title = "Locate or search for contact $recipient",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = recipient,
                precondition = StepPrecondition.RequireForegroundPackage("com.whatsapp"),
                postcondition = StepPostcondition.ExpectUiDelta("Chat opened"),
            ),
            AutonomousStep(
                stepIndex = 3,
                title = "Activate message composer field",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "Type a message",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Composer focused"),
            ),
            AutonomousStep(
                stepIndex = 4,
                title = "Type message text",
                actionType = HumanInteractionType.ENTER_TEXT,
                targetQuery = "Type a message",
                payloadText = message,
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectTextEntered(message),
            ),
            AutonomousStep(
                stepIndex = 5,
                title = "Confirm and send message to $recipient",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "Send",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Message dispatched"),
                isConsequential = true, // Strict anti-duplication protection
            ),
        )

        return AutonomousExecutionPlan(
            planId = "plan_wa_${System.currentTimeMillis()}",
            objective = rawObjective,
            targetApp = "com.whatsapp",
            steps = steps,
        )
    }

    private fun planSettingsNavigation(settingTarget: String, rawObjective: String): AutonomousExecutionPlan {
        val steps = listOf(
            AutonomousStep(
                stepIndex = 1,
                title = "Open Android Settings",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = "com.android.settings",
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectForegroundPackage("com.android.settings"),
            ),
            AutonomousStep(
                stepIndex = 2,
                title = "Locate $settingTarget in Settings",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = settingTarget,
                precondition = StepPrecondition.RequireForegroundPackage("com.android.settings"),
                postcondition = StepPostcondition.ExpectUiDelta("$settingTarget opened"),
            ),
            AutonomousStep(
                stepIndex = 3,
                title = "Select or toggle $settingTarget",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = settingTarget,
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Setting toggled"),
            ),
        )

        return AutonomousExecutionPlan(
            planId = "plan_settings_${System.currentTimeMillis()}",
            objective = rawObjective,
            targetApp = "com.android.settings",
            steps = steps,
        )
    }

    private fun planGenericAppOperation(rawObjective: String): AutonomousExecutionPlan {
        val app = JarvisAppRegistry.findBySpokenName(rawObjective)?.packageNames?.firstOrNull() ?: ""
        val steps = listOf(
            AutonomousStep(
                stepIndex = 1,
                title = "Open target application",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = app,
                precondition = StepPrecondition.None,
                postcondition = if (app.isNotBlank()) StepPostcondition.ExpectForegroundPackage(app) else StepPostcondition.None,
            ),
            AutonomousStep(
                stepIndex = 2,
                title = "Locate target action or item",
                actionType = HumanInteractionType.SINGLE_TAP,
                targetQuery = rawObjective,
                precondition = StepPrecondition.None,
                postcondition = StepPostcondition.ExpectUiDelta("Action performed"),
            ),
        )

        return AutonomousExecutionPlan(
            planId = "plan_gen_${System.currentTimeMillis()}",
            objective = rawObjective,
            targetApp = app,
            steps = steps,
        )
    }

    private fun extractQuery(text: String, markers: List<String>): String {
        for (m in markers) {
            val idx = text.indexOf(m, ignoreCase = true)
            if (idx != -1) {
                val candidate = text.substring(idx + m.length).trim()
                val stopIdx = candidate.indexOf(" and ", ignoreCase = true)
                return if (stopIdx != -1) candidate.substring(0, stopIdx).trim() else candidate
            }
        }
        return ""
    }

    private fun extractRecipient(text: String): String {
        val markers = listOf("to ", "message ", "tell ")
        for (m in markers) {
            val idx = text.indexOf(m, ignoreCase = true)
            if (idx != -1) {
                val remainder = text.substring(idx + m.length).trim()
                val words = remainder.split(' ')
                return words.firstOrNull().orEmpty().replace(Regex("[^a-zA-Z0-9+]"), "")
            }
        }
        return "contact"
    }

    private fun extractMessage(text: String): String {
        val markers = listOf("that ", "saying ", "message ", ": ")
        for (m in markers) {
            val idx = text.indexOf(m, ignoreCase = true)
            if (idx != -1) {
                return text.substring(idx + m.length).trim()
            }
        }
        return "Hello"
    }

    private fun extractSettingTarget(text: String): String = when {
        text.contains("dark", ignoreCase = true) -> "Dark mode"
        text.contains("display", ignoreCase = true) -> "Display"
        text.contains("sound", ignoreCase = true) || text.contains("volume", ignoreCase = true) -> "Sound"
        text.contains("wifi", ignoreCase = true) || text.contains("wi-fi", ignoreCase = true) -> "Wi-Fi"
        text.contains("bluetooth", ignoreCase = true) -> "Bluetooth"
        text.contains("battery", ignoreCase = true) -> "Battery"
        else -> "Display"
    }
}

/**
 * Task State Persistence and Resumption Engine.
 *
 * Saves in-flight autonomous plans to local storage so that after an interruption
 * or human takeover, the user can say "Continue where you stopped" or tap Resume,
 * and Jarvis will verify the current screen state before continuing.
 */
object TaskResumptionEngine {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun saveTaskState(context: Context, plan: AutonomousExecutionPlan): Boolean = runCatching {
        val file = java.io.File(context.filesDir, "active_task_state.json")
        file.writeText(json.encodeToString(plan))
        true
    }.getOrDefault(false)

    fun loadTaskState(context: Context): AutonomousExecutionPlan? = runCatching {
        val file = java.io.File(context.filesDir, "active_task_state.json")
        if (!file.exists() || file.length() == 0L) return null
        json.decodeFromString<AutonomousExecutionPlan>(file.readText())
    }.getOrNull()

    fun clearTaskState(context: Context): Boolean = runCatching {
        val file = java.io.File(context.filesDir, "active_task_state.json")
        if (file.exists()) file.delete() else true
    }.getOrDefault(false)

    /**
     * Reconciles current screen state against a saved plan to determine
     * the safe resumption point without repeating completed or consequential steps.
     */
    fun reconcileForResumption(
        plan: AutonomousExecutionPlan,
        currentScreen: ObservedScreenState,
    ): AutonomousExecutionPlan {
        var nextIndex = plan.activeStepIndex

        // If the current app matches target app and current step was an app launcher, advance
        val currentStep = plan.steps.getOrNull(nextIndex)
        if (currentStep != null && currentStep.precondition is StepPrecondition.RequireForegroundPackage &&
            currentScreen.packageName.equals((currentStep.precondition as StepPrecondition.RequireForegroundPackage).packageName, ignoreCase = true)
        ) {
            currentStep.status = StepExecutionStatus.VERIFIED_COMPLETED
            nextIndex++
        }

        // Never repeat a consequential step that was already marked completed
        for (i in 0 until nextIndex) {
            val step = plan.steps[i]
            if (step.isConsequential) {
                step.status = StepExecutionStatus.VERIFIED_COMPLETED
            }
        }

        plan.activeStepIndex = nextIndex
        plan.isPaused = false
        plan.isCancelled = false
        return plan
    }
}
