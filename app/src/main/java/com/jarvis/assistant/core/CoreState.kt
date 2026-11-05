package com.jarvis.assistant.core

enum class ChatAuthor { USER, JARVIS }

/**
 * The runtime's own view of a conversation. It is deliberately free of Compose
 * types so the foreground service, the workers and the UI can share it.
 */
data class CoreMessage(
    val id: String,
    val author: ChatAuthor,
    val text: String,
    val providerName: String? = null,
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val tools: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class PendingConfirmation(
    val id: String,
    val title: String,
    val detail: String,
    val recipientLabel: String = "",
    val actionCategory: String = "",
    val editableDraftText: String = "",
    val isVoiceNote: Boolean = false,
)

data class LiveCallSessionState(
    val active: Boolean = false,
    val callerName: String = "",
    val appLabel: String = "WhatsApp",
    val turnIndex: Int = 0,
    val maxTurns: Int = 6,
    val lastCallerUtterance: String = "",
    val lastJarvisReply: String = "",
    val statusText: String = "",
)

data class VisionTargetReticleState(
    val active: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val targetLabel: String = "",
    val verified: Boolean = false,
    val timestampMs: Long = 0L,
)

data class PendingPermission(
    val permission: String,
    val rationale: String,
)

/** Everything the UI and the notification reflect. */
data class CoreState(
    /** False after the owner uses the master power switch in Settings. */
    val poweredOn: Boolean = true,
    val messages: List<CoreMessage> = emptyList(),
    val generating: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    /** Live recogniser text, shown under the reactor while the owner talks. */
    val partialTranscript: String = "",
    val activeProvider: String? = null,
    val toolActivity: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** Outcome of the last voice action, shown in Voice settings. */
    val voiceStatus: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingPermission: PendingPermission? = null,
    /** True while the foreground listening service owns the microphone. */
    val serviceActive: Boolean = false,
    /** True while the multi-step screen automation loop is actively controlling the screen. */
    val screenAutomationActive: Boolean = false,
    val screenAutomationStep: Int = 0,
    val screenAutomationMaxSteps: Int = 15,
    val screenAutomationStatus: String? = null,
    val memoryCount: Int = 0,
    val historyLoaded: Boolean = false,
    /** Live 0f..1f audio amplitude from TTS output or microphone input driving the Neural Orb. */
    val audioLevel: Float = 0f,
    /**
     * Caption of what Jarvis is saying right now, streamed sentence by
     * sentence as he speaks. Shown live under the orb and on the incoming-call
     * screen so the owner can read along with the voice.
     */
    val spokenCaption: String? = null,
    /** True when the microphone is actively locked for a listening session. */
    val micLocked: Boolean = false,
    /** Live microphone state machine and diagnostic telemetry. */
    val micDiagnostics: com.jarvis.assistant.voice.MicrophoneDiagnostics =
        com.jarvis.assistant.voice.MicrophoneDiagnostics(),
    /** Active or most recently executed multi-step task plan shown in the timeline. */
    val activePlan: com.jarvis.assistant.brain.TaskPlan? = null,
    /** Content shared into Jarvis via Android Share Sheet or text selection. */
    val sharedPayload: com.jarvis.assistant.brain.SharedContentPayload? = null,
    /** Phase 1: Live two-way call companion state when Jarvis is on a WhatsApp/voice call. */
    val liveCallSession: LiveCallSessionState = LiveCallSessionState(),
    /** Phase 3: Vision Eye live target reticle coordinates and node label during screen control. */
    val visionReticle: VisionTargetReticleState = VisionTargetReticleState(),
)

