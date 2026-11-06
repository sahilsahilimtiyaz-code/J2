package com.jarvis.assistant.ui

import com.jarvis.assistant.brain.DiscoveredModel
import com.jarvis.assistant.memory.MemoryEntity
import com.jarvis.assistant.memory.ProfileFactEntity
import com.jarvis.assistant.permissions.PermissionSummary
import com.jarvis.assistant.security.SecurityAuditStatus
import com.jarvis.assistant.service.DevicePresenceSnapshot
import com.jarvis.assistant.service.ScreenUsageHealthSnapshot
import com.jarvis.assistant.settings.AppSettings
import com.jarvis.assistant.tools.ActionLogEntry
import com.jarvis.assistant.tools.ToolSummary

enum class ChatAuthor { USER, JARVIS }

enum class JarvisTab { HOME, MEMORY, TOOLS, LOG, SKILLS, PROPOSALS, SETTINGS, PERMISSIONS, DIAGNOSTICS }

data class ChatMessageUi(
    val id: String,
    val author: ChatAuthor,
    val text: String,
    val providerName: String? = null,
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val tools: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

sealed interface ProviderTestState {
    data object Testing : ProviderTestState
    data class Success(val message: String) : ProviderTestState
    data class Failure(val message: String) : ProviderTestState
}

/** An action waiting for the owner's explicit yes. */
data class PendingConfirmation(
    val id: String,
    val title: String,
    val detail: String,
    val recipientLabel: String = "",
    val actionCategory: String = "",
    val editableDraftText: String = "",
    val isVoiceNote: Boolean = false,
)

/** A runtime permission Jarvis needs before it can finish a tool call. */
data class PendingPermission(
    val permission: String,
    val rationale: String,
)

data class JarvisUiState(
    val loading: Boolean = true,
    /** False after the owner uses the master power switch. */
    val poweredOn: Boolean = true,
    val onboardingComplete: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val partialTranscript: String = "",
    val activeProvider: String? = null,
    val toolActivity: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val voiceStatus: String? = null,
    val tab: JarvisTab = JarvisTab.HOME,
    val onboardingBusy: Boolean = false,
    val providerTests: Map<String, ProviderTestState> = emptyMap(),
    val defaultPersonaPrompt: String = "",
    val tools: List<ToolSummary> = emptyList(),
    val actions: List<ActionLogEntry> = emptyList(),
    val memories: List<MemoryEntity> = emptyList(),
    val facts: List<ProfileFactEntity> = emptyList(),
    val memoryCount: Int = 0,
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingPermission: PendingPermission? = null,
    val quickPanelOpen: Boolean = false,
    val voiceOverlayOpen: Boolean = false,
    val torchOn: Boolean = false,
    val serviceActive: Boolean = false,
    val permissions: PermissionSummary = PermissionSummary(),
    val screenAutomationActive: Boolean = false,
    val screenAutomationStep: Int = 0,
    val screenAutomationMaxSteps: Int = 15,
    val screenAutomationStatus: String? = null,
    val discoveredModels: List<DiscoveredModel> = emptyList(),
    val fetchingModels: Boolean = false,
    val freeModelsOnly: Boolean = true,
    val audioLevel: Float = 0f,
    val micLocked: Boolean = false,
    val micDiagnostics: com.jarvis.assistant.voice.MicrophoneDiagnostics =
        com.jarvis.assistant.voice.MicrophoneDiagnostics(),
    val devicePresence: DevicePresenceSnapshot = DevicePresenceSnapshot(),
    val screenHealth: ScreenUsageHealthSnapshot = ScreenUsageHealthSnapshot(),
    val securityAudit: SecurityAuditStatus = SecurityAuditStatus(),
    val skills: List<com.jarvis.assistant.memory.SkillDefinition> = emptyList(),
    val proposals: List<com.jarvis.assistant.memory.Proposal> = emptyList(),
    val conversationMode: Boolean = false,
    val floatingHudActive: Boolean = false,
    val activePlan: com.jarvis.assistant.brain.TaskPlan? = null,
    val sharedPayload: com.jarvis.assistant.brain.SharedContentPayload? = null,
    val conversationSearchHits: List<com.jarvis.assistant.memory.MessageEntity> = emptyList(),
    val localModels: Map<String, com.jarvis.assistant.brain.LocalModelDownloadStatus> = emptyMap(),
    val freeStorageBytes: Long = 0L,
    val liveCallSession: com.jarvis.assistant.core.LiveCallSessionState =
        com.jarvis.assistant.core.LiveCallSessionState(),
    val visionReticle: com.jarvis.assistant.core.VisionTargetReticleState =
        com.jarvis.assistant.core.VisionTargetReticleState(),
    val activeMode: com.jarvis.assistant.core.JarvisOperatingMode =
        com.jarvis.assistant.core.JarvisOperatingMode.DEFAULT,
) {
    val showSettings: Boolean
        get() = tab == JarvisTab.SETTINGS

    val hasConfiguredProvider: Boolean
        get() = settings.providers.any { it.enabled && it.hasSavedKey } ||
            localModels.values.any { it.state == com.jarvis.assistant.brain.LocalDownloadState.READY }

    val defaultProviderName: String
        get() = settings.defaultProvider?.name ?: "No provider"

    val defaultProviderModel: String
        get() {
            val defaultProvider = settings.defaultProvider
            if (defaultProvider?.kind == com.jarvis.assistant.brain.ProviderKind.LOCAL_ON_DEVICE) {
                val spec = com.jarvis.assistant.brain.LocalModelCatalog.findByIdOrName(
                    defaultProvider.model.ifBlank { settings.localAi.activeModelId },
                )
                if (spec != null) {
                    val isReady = localModels[spec.id]?.state == com.jarvis.assistant.brain.LocalDownloadState.READY
                    return if (isReady) "${spec.displayName} (Offline)" else "${spec.displayName} (Download Needed)"
                }
            }
            return defaultProvider?.model ?: "Claude 3.5 Sonnet"
        }

    val reactorState: com.jarvis.assistant.ui.components.ReactorState
        get() = when {
            loading -> com.jarvis.assistant.ui.components.ReactorState.BOOT
            generating || screenAutomationActive -> com.jarvis.assistant.ui.components.ReactorState.THINKING
            listening -> com.jarvis.assistant.ui.components.ReactorState.LISTENING
            speaking -> com.jarvis.assistant.ui.components.ReactorState.SPEAKING
            else -> com.jarvis.assistant.ui.components.ReactorState.IDLE
        }
}
