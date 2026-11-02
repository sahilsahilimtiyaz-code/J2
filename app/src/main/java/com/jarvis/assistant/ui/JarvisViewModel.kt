package com.jarvis.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.brain.DiscoveredModel
import com.jarvis.assistant.brain.ModelCatalogueService
import com.jarvis.assistant.brain.ProviderKind
import com.jarvis.assistant.core.JarvisCore
import com.jarvis.assistant.memory.MemoryEntity
import com.jarvis.assistant.permissions.PermissionStatus
import com.jarvis.assistant.settings.OwnerProfile
import com.jarvis.assistant.settings.ProviderSettings
import com.jarvis.assistant.settings.SafetySettings
import com.jarvis.assistant.settings.VoiceProvider
import com.jarvis.assistant.settings.VoiceSettings
import com.jarvis.assistant.tools.TorchState
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A thin adapter over [JarvisCore].
 *
 * All conversation, voice and memory state lives in the app-scoped runtime so
 * the foreground listening service keeps working when the screen is gone. This
 * class only translates that state into Compose-shaped models and forwards the
 * owner's taps.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val core = JarvisCore.get(application)
    private val appContext = application.applicationContext
    private val modelCatalogue = ModelCatalogueService(core.httpClient)

    private val _ui = MutableStateFlow(JarvisUiState())
    val ui: StateFlow<JarvisUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            core.settings.collect { settings ->
                _ui.update { state ->
                    state.copy(
                        settings = settings,
                        loading = false,
                        onboardingComplete = settings.profile.name.isNotBlank(),
                        tools = core.refreshToolCatalogue(),
                        permissions = PermissionStatus.read(appContext),
                    )
                }
            }
        }

        viewModelScope.launch {
            core.state.collect { runtime ->
                _ui.update { state ->
                    state.copy(
                        messages = runtime.messages.map { message ->
                            ChatMessageUi(
                                id = message.id,
                                author = if (message.author == com.jarvis.assistant.core.ChatAuthor.USER) {
                                    ChatAuthor.USER
                                } else {
                                    ChatAuthor.JARVIS
                                },
                                text = message.text,
                                providerName = message.providerName,
                                streaming = message.streaming,
                                isError = message.isError,
                                tools = message.tools,
                                createdAt = message.createdAt,
                            )
                        },
                        generating = runtime.generating,
                        listening = runtime.listening,
                        speaking = runtime.speaking,
                        partialTranscript = runtime.partialTranscript,
                        activeProvider = runtime.activeProvider,
                        toolActivity = runtime.toolActivity,
                        error = runtime.error,
                        notice = runtime.notice,
                        voiceStatus = runtime.voiceStatus,
                        pendingConfirmation = runtime.pendingConfirmation?.let {
                            PendingConfirmation(it.id, it.title, it.detail)
                        },
                        pendingPermission = runtime.pendingPermission?.let {
                            PendingPermission(it.permission, it.rationale)
                        },
                        serviceActive = runtime.serviceActive,
                        memoryCount = runtime.memoryCount,
                        screenAutomationActive = runtime.screenAutomationActive,
                        screenAutomationStep = runtime.screenAutomationStep,
                        screenAutomationMaxSteps = runtime.screenAutomationMaxSteps,
                        screenAutomationStatus = runtime.screenAutomationStatus,
                        audioLevel = runtime.audioLevel,
                        micLocked = runtime.micLocked,
                    )
                }
            }
        }

        viewModelScope.launch {
            core.actionLogStore.entries.collect { entries ->
                _ui.update { it.copy(actions = entries) }
            }
        }

        viewModelScope.launch {
            core.memoryRepository.memories.collect { memories ->
                _ui.update { it.copy(memories = memories) }
            }
        }

        viewModelScope.launch {
            core.memoryRepository.facts.collect { facts ->
                _ui.update { it.copy(facts = facts) }
            }
        }

        viewModelScope.launch {
            val prompt = withContext(Dispatchers.IO) { core.defaultPersonaPrompt() }
            _ui.update {
                it.copy(
                    defaultPersonaPrompt = prompt,
                    securityAudit = com.jarvis.assistant.security.SecurityShield.audit(appContext),
                )
            }
        }

        viewModelScope.launch {
            core.devicePresenceMonitor.state.collect { presence ->
                _ui.update { it.copy(devicePresence = presence) }
            }
        }

        viewModelScope.launch {
            com.jarvis.assistant.service.ScreenUsageHealthService.snapshot.collect { healthSnap ->
                _ui.update { it.copy(screenHealth = healthSnap) }
            }
        }

        viewModelScope.launch {
            com.jarvis.assistant.security.SecurityShield.threats.collect {
                _ui.update {
                    it.copy(securityAudit = com.jarvis.assistant.security.SecurityShield.audit(appContext))
                }
            }
        }

        viewModelScope.launch {
            core.skillRepository.observeSkills().collect { skills ->
                _ui.update { it.copy(skills = skills) }
            }
        }

        viewModelScope.launch {
            core.proposalRepository.proposals.collect { proposals ->
                _ui.update { it.copy(proposals = proposals) }
            }
        }

        viewModelScope.launch {
            core.conversationMode.collect { mode ->
                _ui.update { it.copy(conversationMode = mode) }
            }
        }
    }

    // Screen and chat ---------------------------------------------------------

    fun setUiVisible(visible: Boolean) = core.setUiVisible(visible)

    fun onInputChange(value: String) {
        _ui.update { it.copy(input = value) }
    }

    fun send(textOverride: String? = null) {
        val snapshot = _ui.value
        val text = (textOverride ?: snapshot.input).trim()
        if (text.isEmpty() || snapshot.generating) return
        _ui.update { it.copy(input = "", voiceOverlayOpen = false) }
        core.send(text)
    }

    fun stop() = core.stop()

    fun stopScreenAutomation() = core.stopScreenAutomation()

    fun retryLast() = core.retryLast()

    fun toggleListening() {
        core.toggleListening()
    }

    fun startListening() {
        core.startListening()
    }

    fun stopListening() = core.stopListening()

    fun openVoiceOverlay() {
        _ui.update { it.copy(voiceOverlayOpen = true) }
        if (!_ui.value.listening) {
            core.startListening()
        }
    }

    fun closeVoiceOverlay() {
        _ui.update { it.copy(voiceOverlayOpen = false) }
    }

    fun speakText(text: String) = core.speak(text)

    fun testVoice() = core.testVoice()

    fun clearVoiceStatus() = core.clearVoiceStatus()

    fun triggerAutonomousThoughtNow() = core.triggerAutonomousThoughtNow()

    fun setAutonomousThoughtsEnabled(enabled: Boolean) = core.setAutonomousThoughtsEnabled(enabled)

    fun saveHealthSettings(health: com.jarvis.assistant.settings.HealthSettings) {
        core.saveHealthSettings(health)
    }

    fun resetHealthBreakTimer() {
        com.jarvis.assistant.service.ScreenUsageHealthService.markBreakTaken(appContext)
        core.setNotice("Screen usage session timer reset. Enjoy your refreshed focus.")
    }

    fun triggerHealthSuggestionNow() {
        com.jarvis.assistant.service.ScreenUsageHealthService.triggerImmediateHealthSuggestion(appContext)
    }

    fun toggleBackgroundListening() {
        if (_ui.value.serviceActive) {
            core.stopBackgroundListening()
        } else {
            core.startBackgroundListening()
        }
        refreshPermissions()
    }

    fun toggleConversationMode() {
        core.toggleConversationMode()
    }

    fun toggleFloatingHud() {
        val next = !_ui.value.floatingHudActive
        _ui.update { it.copy(floatingHudActive = next) }
        if (next) {
            com.jarvis.assistant.service.JarvisFloatingHudService.start(appContext)
        } else {
            com.jarvis.assistant.service.JarvisFloatingHudService.stop(appContext)
        }
    }

    suspend fun createMasterBackup(): String =
        core.backupRestoreRepository.createMasterBackup()

    suspend fun restoreMasterBackup(backupJson: String): Result<String> =
        core.backupRestoreRepository.restoreMasterBackup(backupJson)

    fun refreshPermissions() {
        _ui.update {
            it.copy(
                permissions = PermissionStatus.read(appContext),
                securityAudit = com.jarvis.assistant.security.SecurityShield.audit(appContext),
            )
        }
    }

    // Navigation --------------------------------------------------------------

    fun selectTab(tab: JarvisTab) {
        _ui.update { state ->
            state.copy(
                tab = tab,
                voiceOverlayOpen = false,
                notice = null,
                tools = core.refreshToolCatalogue(),
                permissions = PermissionStatus.read(appContext),
            )
        }
    }

    fun openQuickPanel() {
        _ui.update {
            it.copy(
                quickPanelOpen = true,
                torchOn = TorchState.isOn,
                tools = core.refreshToolCatalogue(),
            )
        }
    }

    fun closeQuickPanel() {
        _ui.update { it.copy(quickPanelOpen = false) }
    }

    fun openSettings() {
        _ui.update { it.copy(tab = JarvisTab.SETTINGS, notice = null) }
    }

    fun closeSettings() {
        _ui.update { it.copy(tab = JarvisTab.HOME, notice = null) }
    }

    fun openPermissions() {
        _ui.update {
            it.copy(
                tab = JarvisTab.PERMISSIONS,
                permissions = PermissionStatus.read(appContext),
            )
        }
    }

    fun closePermissions() {
        _ui.update {
            it.copy(tab = JarvisTab.SETTINGS, permissions = PermissionStatus.read(appContext))
        }
    }

    fun openActionLog() {
        _ui.update { it.copy(tab = JarvisTab.LOG) }
    }

    fun dismissError() = core.dismissError()

    fun dismissNotice() = core.dismissNotice()

    // Quick panel & tool actions ----------------------------------------------

    fun toggleTorch() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                core.toolRegistry.execute("control_flashlight", "{}")
            }
            _ui.update { it.copy(torchOn = TorchState.isOn, tools = core.refreshToolCatalogue()) }
            if (!result.success) core.setError(result.content)
        }
    }

    fun toggleMedia() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                core.toolRegistry.execute("control_media", "{\"action\":\"play_pause\"}")
            }
            _ui.update { it.copy(tools = core.refreshToolCatalogue()) }
            if (!result.success) core.setError(result.content)
        }
    }

    fun runQuickCommand(prompt: String) {
        _ui.update { it.copy(quickPanelOpen = false, voiceOverlayOpen = false, tab = JarvisTab.HOME) }
        send(prompt)
    }

    fun approveConfirmation() = core.approveConfirmation()

    fun rejectConfirmation() = core.rejectConfirmation()

    fun onPermissionResult(granted: Boolean) {
        core.onPermissionResult(granted)
        refreshPermissions()
    }

    fun clearActionLog() {
        viewModelScope.launch { core.actionLogStore.clear() }
    }

    // Live Model Discovery ----------------------------------------------------

    fun setFreeModelsOnly(freeOnly: Boolean) {
        _ui.update { it.copy(freeModelsOnly = freeOnly) }
    }

    fun fetchModelsForProvider(
        provider: ProviderSettings,
        draftBaseUrl: String = provider.baseUrl,
        draftKey: String = "",
    ) {
        if (_ui.value.fetchingModels) return
        _ui.update { it.copy(fetchingModels = true) }
        viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) {
                    if (draftKey.isNotBlank()) {
                        draftKey.trim()
                    } else if (provider.id.isNotBlank()) {
                        core.settingsRepository.decryptApiKey(provider)
                    } else {
                        ""
                    }
                }
                val models = modelCatalogue.fetchModels(
                    kind = provider.kind,
                    baseUrl = draftBaseUrl.trim().ifBlank { provider.baseUrl },
                    apiKey = key,
                )
                _ui.update {
                    it.copy(
                        discoveredModels = models,
                        fetchingModels = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        discoveredModels = modelCatalogue.curatedModelsFor(provider.kind, draftBaseUrl),
                        fetchingModels = false,
                    )
                }
            }
        }
    }

    fun fetchAllAvailableModels() {
        if (_ui.value.fetchingModels) return
        _ui.update { it.copy(fetchingModels = true) }
        viewModelScope.launch {
            try {
                val providers = _ui.value.settings.providers
                val gathered = mutableListOf<DiscoveredModel>()
                for (provider in providers) {
                    val key = withContext(Dispatchers.IO) {
                        core.settingsRepository.decryptApiKey(provider)
                    }
                    val fetched = modelCatalogue.fetchModels(
                        kind = provider.kind,
                        baseUrl = provider.baseUrl,
                        apiKey = key,
                    )
                    gathered.addAll(fetched)
                }
                gathered.addAll(modelCatalogue.allGlobalFreeModelsCatalog())
                val distinct = gathered.distinctBy { "${it.providerLabel}:${it.id}" }
                _ui.update {
                    it.copy(
                        discoveredModels = distinct,
                        fetchingModels = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update {
                    it.copy(
                        discoveredModels = modelCatalogue.allGlobalFreeModelsCatalog(),
                        fetchingModels = false,
                    )
                }
            }
        }
    }

    fun selectQuickModel(providerIdOrLabel: String, modelId: String) {
        viewModelScope.launch {
            runCatching {
                core.settingsRepository.update { current ->
                    val existing = current.providers.firstOrNull { p ->
                        p.id.equals(providerIdOrLabel, ignoreCase = true) ||
                            p.name.equals(providerIdOrLabel, ignoreCase = true) ||
                            modelCatalogue.resolveProviderLabel(p.baseUrl.lowercase())
                                .equals(providerIdOrLabel, ignoreCase = true)
                    }
                    if (existing != null) {
                        val updatedProviders = current.providers.map { p ->
                            if (p.id == existing.id) p.copy(model = modelId, enabled = true) else p
                        }
                        current.copy(
                            providers = updatedProviders,
                            defaultProviderId = existing.id,
                        )
                    } else {
                        val preset = com.jarvis.assistant.settings.findPresetForProvider(providerIdOrLabel)
                        if (preset != null) {
                            val newProvider = ProviderSettings(
                                id = preset.id,
                                name = preset.name,
                                kind = preset.kind,
                                baseUrl = preset.baseUrl,
                                model = modelId,
                                enabled = true,
                            )
                            current.copy(
                                providers = current.providers + newProvider,
                                defaultProviderId = newProvider.id,
                            )
                        } else {
                            val fallbackId = current.defaultProviderId.ifBlank {
                                current.providers.firstOrNull()?.id.orEmpty()
                            }
                            val updatedProviders = current.providers.map { p ->
                                if (p.id == fallbackId) p.copy(model = modelId, enabled = true) else p
                            }
                            current.copy(
                                providers = updatedProviders,
                                defaultProviderId = fallbackId,
                            )
                        }
                    }
                }
                core.setNotice("Active model set to $modelId ($providerIdOrLabel).")
            }.onFailure {
                core.setError(it.message?.take(300) ?: "Could not switch model.")
            }
        }
    }

    fun addAllGlobalFreeProviders() {
        viewModelScope.launch {
            runCatching {
                var addedCount = 0
                core.settingsRepository.update { current ->
                    val existingIds = current.providers.map { it.id.lowercase() }.toMutableSet()
                    val existingUrls = current.providers.map { it.baseUrl.lowercase() }.toMutableSet()
                    val extra = mutableListOf<ProviderSettings>()
                    for (preset in com.jarvis.assistant.settings.providerPresets) {
                        if (!preset.isCompletelyFree) continue
                        if (preset.id.lowercase() in existingIds || preset.baseUrl.lowercase() in existingUrls) {
                            continue
                        }
                        existingIds += preset.id.lowercase()
                        existingUrls += preset.baseUrl.lowercase()
                        extra += ProviderSettings(
                            id = preset.id,
                            name = preset.name,
                            kind = preset.kind,
                            baseUrl = preset.baseUrl,
                            model = preset.model,
                            enabled = true,
                        )
                    }
                    addedCount = extra.size
                    current.copy(providers = current.providers + extra)
                }
                core.setNotice(
                    if (addedCount > 0) {
                        "Added $addedCount global free AI providers (Z.ai, NVIDIA NIM, OpenCode Zen, Agnes AI & more)."
                    } else {
                        "All global free AI providers are already in your Connections list."
                    },
                )
            }.onFailure {
                core.setError(it.message?.take(300) ?: "Could not add global free providers.")
            }
        }
    }

    // Profile, persona, providers --------------------------------------------

    fun saveProfile(profile: OwnerProfile) {
        core.saveProfile(profile)
    }

    fun savePersona(tone: String, customPrompt: String?) {
        core.savePersona(tone, customPrompt)
    }

    fun resetPersonaPrompt(tone: String) {
        core.savePersona(tone = tone, customPrompt = null)
    }

    fun saveSafety(
        screenControlEnabled: Boolean,
        blocklistText: String,
        callAssistantEnabled: Boolean = false,
        callAssistantMaxTurns: Int = 6,
    ) {
        val keywords = blocklistText
            .split(',', '\n')
            .map { it.trim().lowercase(Locale.UK) }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { com.jarvis.assistant.settings.DEFAULT_BLOCKLIST }
        val turns = callAssistantMaxTurns.coerceIn(2, 12)
        viewModelScope.launch {
            runCatching {
                core.settingsRepository.update { current ->
                    current.copy(
                        safety = current.safety.copy(
                            screenControlEnabled = screenControlEnabled,
                            blocklistKeywords = keywords,
                            callAssistantEnabled = callAssistantEnabled,
                            callAssistantMaxTurns = turns,
                        ),
                    )
                }
                core.setNotice("Safety settings saved.")
            }.onFailure {
                core.setError(it.message?.take(400) ?: "Could not save the safety settings.")
            }
        }
    }

    fun saveWhatsAppVoiceNotes(
        enabled: Boolean,
        autoReplyEnabled: Boolean,
        allowlistText: String,
        maxChars: Int,
        audioFallback: Boolean,
        cooldownMinutes: Int,
        dailyLimit: Int,
    ) {
        val contacts = allowlistText
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.UK) }
        viewModelScope.launch {
            runCatching {
                core.settingsRepository.update { current ->
                    current.copy(
                        safety = current.safety.copy(
                            voiceNoteEnabled = enabled,
                            voiceNoteAutoReplyEnabled = autoReplyEnabled && contacts.isNotEmpty(),
                            voiceNoteAllowedContacts = contacts,
                            voiceNoteMaxChars = maxChars.coerceIn(80, 700),
                            voiceNoteAudioFallbackEnabled = audioFallback,
                            voiceNoteCooldownMinutes = cooldownMinutes.coerceIn(1, 240),
                            voiceNoteDailyLimit = dailyLimit.coerceIn(1, 60),
                        ),
                    )
                }
                core.setNotice("WhatsApp voice note settings saved.")
            }.onFailure {
                core.setError(it.message?.take(400) ?: "Could not save the voice note settings.")
            }
        }
    }

    fun saveProvider(provider: ProviderSettings, newApiKey: String = "") {
        if (provider.id.isBlank()) {
            core.setError("This provider has not been saved yet.")
            return
        }
        validateProvider(provider)?.let {
            core.setError(it)
            return
        }
        viewModelScope.launch {
            try {
                val encrypted = withContext(Dispatchers.IO) {
                    if (newApiKey.isNotBlank()) {
                        core.settingsRepository.encryptApiKey(newApiKey.trim())
                    } else {
                        provider.encryptedApiKey
                    }
                }
                core.settingsRepository.update { current ->
                    val updated = provider.copy(encryptedApiKey = encrypted)
                    val exists = current.providers.any { it.id == provider.id }
                    current.copy(
                        providers = if (exists) {
                            current.providers.map { if (it.id == provider.id) updated else it }
                        } else {
                            current.providers + updated
                        },
                        defaultProviderId = current.defaultProviderId.ifBlank { updated.id },
                    )
                }
                core.setNotice("Provider saved.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.setError(e.message?.take(400) ?: "Could not save the provider.")
            }
        }
    }

    fun addProvider(
        kind: ProviderKind,
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String,
    ) {
        val provisional = ProviderSettings(
            id = "pending",
            name = name.trim(),
            kind = kind,
            baseUrl = baseUrl.trim(),
            model = model.trim(),
        )
        validateProvider(provisional)?.let {
            core.setError(it)
            return
        }
        viewModelScope.launch {
            try {
                val encrypted = withContext(Dispatchers.IO) {
                    if (apiKey.isNotBlank()) {
                        core.settingsRepository.encryptApiKey(apiKey.trim())
                    } else {
                        ""
                    }
                }
                val id = "${kind.name.lowercase(Locale.US)}_${UUID.randomUUID().toString().take(8)}"
                core.settingsRepository.update { current ->
                    val newProvider = provisional.copy(id = id, encryptedApiKey = encrypted)
                    current.copy(
                        providers = current.providers + newProvider,
                        defaultProviderId = current.defaultProviderId.ifBlank { id },
                    )
                }
                core.setNotice("Provider added.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.setError(e.message?.take(400) ?: "Could not add the provider.")
            }
        }
    }

    fun removeProvider(providerId: String) {
        viewModelScope.launch {
            try {
                core.settingsRepository.update { current ->
                    val remaining = current.providers.filterNot { it.id == providerId }
                    current.copy(
                        providers = remaining,
                        defaultProviderId = if (current.defaultProviderId == providerId) {
                            remaining.firstOrNull()?.id.orEmpty()
                        } else {
                            current.defaultProviderId
                        },
                    )
                }
                _ui.update {
                    it.copy(
                        providerTests = it.providerTests - providerId,
                        notice = "Provider removed.",
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.setError(e.message?.take(400) ?: "Could not remove the provider.")
            }
        }
    }

    fun setDefaultProvider(providerId: String) {
        viewModelScope.launch {
            runCatching {
                core.settingsRepository.update { it.copy(defaultProviderId = providerId) }
                core.setNotice("Default provider updated.")
            }.onFailure {
                core.setError(it.message?.take(400) ?: "Could not change the default provider.")
            }
        }
    }

    fun moveProvider(providerId: String, delta: Int) {
        viewModelScope.launch {
            runCatching {
                core.settingsRepository.update { current ->
                    val providers = current.providers.toMutableList()
                    val index = providers.indexOfFirst { it.id == providerId }
                    if (index < 0) return@update current
                    val target = index + delta
                    if (target !in providers.indices) return@update current
                    val item = providers.removeAt(index)
                    providers.add(target, item)
                    current.copy(providers = providers)
                }
            }.onFailure {
                core.setError(it.message?.take(400) ?: "Could not reorder the providers.")
            }
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                core.settingsRepository.update { current ->
                    val providers = current.providers.map {
                        if (it.id == providerId) it.copy(enabled = enabled) else it
                    }
                    val defaultId = if (!enabled && current.defaultProviderId == providerId) {
                        providers.firstOrNull { it.enabled }?.id ?: current.defaultProviderId
                    } else {
                        current.defaultProviderId
                    }
                    current.copy(providers = providers, defaultProviderId = defaultId)
                }
            }.onFailure {
                core.setError(it.message?.take(400) ?: "Could not update the provider.")
            }
        }
    }

    fun testProvider(provider: ProviderSettings, draftKey: String = "") {
        if (provider.id.isBlank()) {
            setProviderTest(provider.id, ProviderTestState.Failure("Save the provider first."))
            return
        }
        if (_ui.value.providerTests[provider.id] is ProviderTestState.Testing) return
        validateProvider(provider)?.let {
            setProviderTest(provider.id, ProviderTestState.Failure(it))
            return
        }
        setProviderTest(provider.id, ProviderTestState.Testing)
        viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) {
                    if (draftKey.isNotBlank()) {
                        draftKey.trim()
                    } else {
                        core.settingsRepository.decryptApiKey(provider)
                    }
                }
                if (key.isBlank() && provider.requiresApiKey) {
                    setProviderTest(
                        provider.id,
                        ProviderTestState.Failure("Enter an API key first, or save one with the provider."),
                    )
                    return@launch
                }
                val result = core.providerTester.test(core.providerConfig(provider, key))
                setProviderTest(
                    provider.id,
                    if (result.success) {
                        ProviderTestState.Success(result.message)
                    } else {
                        ProviderTestState.Failure(result.message)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setProviderTest(
                    provider.id,
                    ProviderTestState.Failure(e.message?.take(400) ?: "The connection test failed."),
                )
            }
        }
    }

    // Voice -------------------------------------------------------------------

    fun saveVoiceSettings(
        provider: VoiceProvider,
        voiceId: String,
        speed: Float,
        fallbackToSystem: Boolean,
        language: String,
        apiKey: String,
        continuousListening: Boolean = _ui.value.settings.voice.continuousListening,
        model: String = _ui.value.settings.voice.model,
        alwaysListening: Boolean = _ui.value.settings.voice.alwaysListening,
        requireWakeWord: Boolean = _ui.value.settings.voice.requireWakeWord,
        wakeWord: String = _ui.value.settings.voice.wakeWord,
    ) {
        viewModelScope.launch {
            try {
                val existing = _ui.value.settings.voice
                val cleanVoiceId = voiceId.trim()
                core.settingsRepository.saveVoice(
                    existing.copy(
                        provider = provider,
                        model = model.trim(),
                        voiceId = cleanVoiceId,
                        fishVoiceId = if (provider == VoiceProvider.FISH_AUDIO && cleanVoiceId.isNotBlank()) {
                            cleanVoiceId
                        } else {
                            existing.fishVoiceId
                        },
                        elevenLabsVoiceId = if (provider == VoiceProvider.ELEVENLABS && cleanVoiceId.isNotBlank()) {
                            cleanVoiceId
                        } else {
                            existing.elevenLabsVoiceId
                        },
                        speed = speed,
                        fallbackToSystem = fallbackToSystem,
                        language = language.trim().ifBlank { "en-US" },
                        continuousListening = continuousListening,
                        alwaysListening = alwaysListening,
                        requireWakeWord = requireWakeWord,
                        wakeWord = wakeWord.trim().ifBlank { "jarvis" },
                    ),
                    apiKey.trim(),
                )
                if (alwaysListening && !_ui.value.serviceActive) {
                    core.startBackgroundListening()
                } else if (!alwaysListening && _ui.value.serviceActive) {
                    core.stopBackgroundListening()
                }
                core.setNotice("Voice settings saved.")
                refreshPermissions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.setError(e.message?.take(400) ?: "Could not save voice settings.")
            }
        }
    }

    fun testVoiceWithDraft(
        provider: VoiceProvider,
        voiceId: String,
        speed: Float,
        fallbackToSystem: Boolean,
        language: String,
        apiKey: String,
        continuousListening: Boolean,
    ) {
        viewModelScope.launch {
            try {
                val existing = _ui.value.settings.voice
                val cleanVoiceId = voiceId.trim()
                core.settingsRepository.saveVoice(
                    existing.copy(
                        provider = provider,
                        voiceId = cleanVoiceId,
                        fishVoiceId = if (provider == VoiceProvider.FISH_AUDIO && cleanVoiceId.isNotBlank()) {
                            cleanVoiceId
                        } else {
                            existing.fishVoiceId
                        },
                        elevenLabsVoiceId = if (provider == VoiceProvider.ELEVENLABS && cleanVoiceId.isNotBlank()) {
                            cleanVoiceId
                        } else {
                            existing.elevenLabsVoiceId
                        },
                        speed = speed,
                        fallbackToSystem = fallbackToSystem,
                        language = language.trim().ifBlank { "en-US" },
                        continuousListening = continuousListening,
                    ),
                    apiKey.trim(),
                )
                core.testVoice()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.setError(e.message?.take(400) ?: "Could not test voice.")
            }
        }
    }

    // Memory ------------------------------------------------------------------

    fun addMemory(text: String, type: String, importance: Int) {
        core.addMemory(text, type, importance)
    }

    fun updateMemory(memory: MemoryEntity) = core.updateMemory(memory)

    fun deleteMemory(id: Long) = core.deleteMemory(id)

    fun forgetEverything() = core.forgetEverything()

    fun extractMemoriesNow() {
        viewModelScope.launch { core.extractMemoriesFromLatestConversation() }
    }

    fun exportMemories(uri: Uri) = core.exportMemories(uri)

    fun importMemories(uri: Uri) = core.importMemories(uri)

    // Skills & Proposals (Phase 7) -------------------------------------------

    fun runSkill(skill: com.jarvis.assistant.memory.SkillDefinition) {
        viewModelScope.launch {
            send(skill.triggers.firstOrNull() ?: skill.name)
        }
    }

    fun deleteSkill(id: Long) {
        viewModelScope.launch {
            core.skillRepository.deleteSkill(id)
        }
    }

    fun approveProposal(proposalId: String) {
        viewModelScope.launch {
            val approved = core.proposalRepository.approveProposal(proposalId)
            if (approved) {
                _ui.update { it.copy(notice = "Proposal approved and applied.") }
            }
        }
    }

    fun dismissProposal(proposalId: String) {
        viewModelScope.launch {
            core.proposalRepository.dismissProposal(proposalId)
        }
    }

    fun runSelfReviewNow() {
        com.jarvis.assistant.service.SelfReviewWorker.runNow(getApplication())
        _ui.update { it.copy(notice = "Nightly self-review initiated. Analyzing recent action logs for lessons...") }
    }

    fun onAssistTriggered() {
        core.setUiVisible(true)
        if (!_ui.value.listening) {
            core.toggleListening()
        }
    }

    // Helpers -----------------------------------------------------------------

    private fun validateProvider(provider: ProviderSettings): String? {
        if (provider.name.isBlank()) return "Give the provider a name."
        if (!core.isValidProviderUrl(provider.baseUrl)) {
            return "Base URL must be a full http:// or https:// address."
        }
        if (provider.model.isBlank()) return "Enter a model name."
        return null
    }

    private fun setProviderTest(providerId: String, state: ProviderTestState) {
        if (providerId.isBlank()) return
        _ui.update {
            it.copy(providerTests = it.providerTests + (providerId to state))
        }
    }

    override fun onCleared() {
        core.detachScreen()
        super.onCleared()
    }
}
