package com.jarvis.assistant.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.jarvisDataStore by preferencesDataStore(name = "jarvis_settings")

class SettingsRepository(
    context: Context,
    private val cipher: SecretCipher = SecretCipher(),
) {
    private val appContext = context.applicationContext
    val vault = EncryptedKeystoreVault(appContext, cipher)

    val settings: Flow<AppSettings> = appContext.jarvisDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> decodeWithBackup(preferences) }

    /** The current document, for work that cannot collect a flow. */
    suspend fun current(): AppSettings = settings.first()

    /**
     * Atomically updates the whole settings document and synchronously mirrors
     * all encrypted TTS and LLM provider credentials into [EncryptedKeystoreVault]
     * so they are never lost across app restarts or process kills.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var updated = AppSettings()
        appContext.jarvisDataStore.edit { preferences ->
            val current = decodeWithBackup(preferences)
            val candidate = transform(current).normalized()
            val mergedVoice = mergeVoicePreservingSecrets(current.voice, candidate.voice)
            val mergedProviders = mergeProvidersPreservingSecrets(current.providers, candidate.providers)
            updated = candidate.copy(
                voice = mergedVoice,
                providers = mergedProviders,
            )
            preferences[STATE_KEY] = json.encodeToString(updated)
            syncVoiceBackups(preferences, mergedVoice)
            syncVault(mergedVoice, mergedProviders)
        }
        return updated
    }

    suspend fun saveProfile(profile: OwnerProfile): AppSettings =
        update { current -> current.copy(profile = profile.sanitized()) }

    suspend fun saveHealth(health: HealthSettings): AppSettings =
        update { current ->
            current.copy(
                health = health.copy(
                    sessionLimitMinutes = health.sessionLimitMinutes.coerceIn(10, 240),
                    breakResetMinutes = health.breakResetMinutes.coerceIn(1, 30),
                    quietHoursStart = health.quietHoursStart.coerceIn(0, 23),
                    quietHoursEnd = health.quietHoursEnd.coerceIn(0, 23),
                ),
            )
        }

    suspend fun saveAuthSession(auth: AuthSession): AppSettings =
        update { current ->
            val nextProfile = if (auth.mode == AuthMode.GOOGLE &&
                current.profile.name.isBlank() &&
                auth.displayName.isNotBlank()
            ) {
                current.profile.copy(name = auth.displayName.trim()).sanitized()
            } else {
                current.profile
            }
            current.copy(
                auth = auth,
                profile = nextProfile,
            )
        }

    suspend fun signOut(): AppSettings =
        update { current ->
            current.copy(auth = AuthSession(mode = AuthMode.NONE))
        }

    suspend fun savePersona(persona: PersonaSettings): AppSettings =
        update { current ->
            current.copy(
                persona = persona.copy(
                    tone = persona.tone.trim().ifBlank { "dry British wit, calm and understated" },
                    customPrompt = persona.customPrompt?.trim()?.takeIf { it.isNotBlank() },
                    lastGreetingId = current.persona.lastGreetingId,
                    // Preserve the greeting clock, or editing the persona would
                    // let Jarvis repeat himself on the next wake.
                    lastGreetingAt = current.persona.lastGreetingAt,
                ),
            )
        }

    suspend fun saveVoice(voice: VoiceSettings, newApiKey: String = ""): AppSettings =
        update { current ->
            val prev = current.voice
            val freshlyEncrypted = if (newApiKey.isNotBlank()) {
                encryptApiKey(newApiKey.trim())
            } else {
                ""
            }

            // Immediately commit newly entered TTS key to the synchronous Keystore Vault
            if (freshlyEncrypted.isNotBlank()) {
                vault.putEncrypted(EncryptedKeystoreVault.SLOT_TTS_ACTIVE, freshlyEncrypted)
                when (voice.provider) {
                    VoiceProvider.FISH_AUDIO ->
                        vault.putEncrypted(EncryptedKeystoreVault.SLOT_TTS_FISH, freshlyEncrypted)
                    VoiceProvider.ELEVENLABS ->
                        vault.putEncrypted(EncryptedKeystoreVault.SLOT_TTS_ELEVEN, freshlyEncrypted)
                    VoiceProvider.SYSTEM -> Unit
                }
            }

            val resolvedFishKey = when {
                voice.provider == VoiceProvider.FISH_AUDIO && freshlyEncrypted.isNotBlank() -> freshlyEncrypted
                voice.fishEncryptedApiKey.isNotBlank() -> voice.fishEncryptedApiKey
                prev.fishEncryptedApiKey.isNotBlank() -> prev.fishEncryptedApiKey
                vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_FISH).isNotBlank() ->
                    vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_FISH)
                prev.provider == VoiceProvider.FISH_AUDIO && prev.encryptedApiKey.isNotBlank() -> prev.encryptedApiKey
                else -> ""
            }

            val resolvedElevenKey = when {
                voice.provider == VoiceProvider.ELEVENLABS && freshlyEncrypted.isNotBlank() -> freshlyEncrypted
                voice.elevenLabsEncryptedApiKey.isNotBlank() -> voice.elevenLabsEncryptedApiKey
                prev.elevenLabsEncryptedApiKey.isNotBlank() -> prev.elevenLabsEncryptedApiKey
                vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ELEVEN).isNotBlank() ->
                    vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ELEVEN)
                prev.provider == VoiceProvider.ELEVENLABS && prev.encryptedApiKey.isNotBlank() -> prev.encryptedApiKey
                else -> ""
            }

            val resolvedActiveKey = when {
                freshlyEncrypted.isNotBlank() -> freshlyEncrypted
                voice.provider == VoiceProvider.FISH_AUDIO && resolvedFishKey.isNotBlank() -> resolvedFishKey
                voice.provider == VoiceProvider.ELEVENLABS && resolvedElevenKey.isNotBlank() -> resolvedElevenKey
                voice.encryptedApiKey.isNotBlank() -> voice.encryptedApiKey
                prev.encryptedApiKey.isNotBlank() -> prev.encryptedApiKey
                vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ACTIVE).isNotBlank() ->
                    vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ACTIVE)
                else -> resolvedFishKey.ifBlank { resolvedElevenKey }
            }

            val cleanVoiceId = voice.voiceId.trim()
            val resolvedFishVoiceId = when {
                voice.provider == VoiceProvider.FISH_AUDIO && cleanVoiceId.isNotBlank() -> cleanVoiceId
                voice.fishVoiceId.isNotBlank() -> voice.fishVoiceId.trim()
                prev.fishVoiceId.isNotBlank() -> prev.fishVoiceId.trim()
                vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_FISH).isNotBlank() ->
                    vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_FISH)
                prev.provider == VoiceProvider.FISH_AUDIO && prev.voiceId.isNotBlank() -> prev.voiceId.trim()
                else -> ""
            }
            val resolvedElevenVoiceId = when {
                voice.provider == VoiceProvider.ELEVENLABS && cleanVoiceId.isNotBlank() -> cleanVoiceId
                voice.elevenLabsVoiceId.isNotBlank() -> voice.elevenLabsVoiceId.trim()
                prev.elevenLabsVoiceId.isNotBlank() -> prev.elevenLabsVoiceId.trim()
                vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_ELEVEN).isNotBlank() ->
                    vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_ELEVEN)
                prev.provider == VoiceProvider.ELEVENLABS && prev.voiceId.isNotBlank() -> prev.voiceId.trim()
                else -> ""
            }
            val resolvedVoiceId = when {
                cleanVoiceId.isNotBlank() -> cleanVoiceId
                voice.provider == VoiceProvider.FISH_AUDIO -> resolvedFishVoiceId.ifBlank { prev.voiceId }
                voice.provider == VoiceProvider.ELEVENLABS -> resolvedElevenVoiceId.ifBlank { prev.voiceId }
                else -> prev.voiceId
            }

            current.copy(
                voice = voice.copy(
                    encryptedApiKey = resolvedActiveKey,
                    fishEncryptedApiKey = resolvedFishKey,
                    elevenLabsEncryptedApiKey = resolvedElevenKey,
                    voiceId = resolvedVoiceId,
                    fishVoiceId = resolvedFishVoiceId,
                    elevenLabsVoiceId = resolvedElevenVoiceId,
                    speed = voice.speed.coerceIn(0.5f, 2.0f),
                    language = voice.language.trim().ifBlank { "en-US" },
                ),
            )
        }

    fun encryptApiKey(plainText: String): String = cipher.encrypt(plainText)

    /**
     * Returns the saved key for [provider], falling back to the synchronous
     * [EncryptedKeystoreVault] if needed, or an empty string when none is saved.
     *
     * @throws SecretCipherException when a saved key cannot be decrypted.
     */
    fun decryptApiKey(provider: ProviderSettings): String {
        val candidate = provider.encryptedApiKey.ifBlank {
            vault.getEncrypted(EncryptedKeystoreVault.slotForProvider(provider.id))
        }
        if (candidate.isBlank()) return ""
        return cipher.decrypt(candidate)
    }

    fun decryptVoiceApiKey(voice: VoiceSettings): String {
        val vaultForProvider = when (voice.provider) {
            VoiceProvider.FISH_AUDIO -> vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_FISH)
            VoiceProvider.ELEVENLABS -> vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ELEVEN)
            VoiceProvider.SYSTEM -> ""
        }
        val candidate = voice.activeEncryptedKey
            .ifBlank { vaultForProvider }
            .ifBlank { voice.encryptedApiKey }
            .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ACTIVE) }
            .ifBlank { voice.fishEncryptedApiKey.ifBlank { voice.elevenLabsEncryptedApiKey } }
        if (candidate.isBlank()) return ""
        return cipher.decrypt(candidate)
    }

    private fun decodeWithBackup(
        preferences: androidx.datastore.preferences.core.Preferences,
    ): AppSettings {
        val base = decode(preferences[STATE_KEY])
        val v = base.voice
        val backupGeneric = preferences[VOICE_KEY_BACKUP].orEmpty()
            .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ACTIVE) }
        val backupFish = preferences[FISH_KEY_BACKUP].orEmpty()
            .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_FISH) }
        val backupEleven = preferences[ELEVEN_KEY_BACKUP].orEmpty()
            .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ELEVEN) }
        val backupFishVoiceId = preferences[FISH_VOICE_ID_BACKUP].orEmpty()
            .ifBlank { vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_FISH) }
        val backupElevenVoiceId = preferences[ELEVEN_VOICE_ID_BACKUP].orEmpty()
            .ifBlank { vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_ELEVEN) }

        val fishKey = v.fishEncryptedApiKey.ifBlank { backupFish }.ifBlank {
            if (v.provider == VoiceProvider.FISH_AUDIO) v.encryptedApiKey.ifBlank { backupGeneric } else ""
        }
        val elevenKey = v.elevenLabsEncryptedApiKey.ifBlank { backupEleven }.ifBlank {
            if (v.provider == VoiceProvider.ELEVENLABS) v.encryptedApiKey.ifBlank { backupGeneric } else ""
        }
        val genericKey = v.encryptedApiKey.ifBlank { backupGeneric }.ifBlank {
            when (v.provider) {
                VoiceProvider.FISH_AUDIO -> fishKey
                VoiceProvider.ELEVENLABS -> elevenKey
                VoiceProvider.SYSTEM -> fishKey.ifBlank { elevenKey }
            }
        }
        val fishVid = v.fishVoiceId.ifBlank { backupFishVoiceId }.ifBlank {
            if (v.provider == VoiceProvider.FISH_AUDIO) v.voiceId else ""
        }
        val elevenVid = v.elevenLabsVoiceId.ifBlank { backupElevenVoiceId }.ifBlank {
            if (v.provider == VoiceProvider.ELEVENLABS) v.voiceId else ""
        }
        val activeVid = v.voiceId.ifBlank {
            when (v.provider) {
                VoiceProvider.FISH_AUDIO -> fishVid
                VoiceProvider.ELEVENLABS -> elevenVid
                VoiceProvider.SYSTEM -> fishVid.ifBlank { elevenVid }
            }
        }

        val baseProviders = if (vault.getMetadata(SLOT_GLOBAL_FREE_PROVIDERS_SEEDED) != "v2") {
            vault.putMetadata(SLOT_GLOBAL_FREE_PROVIDERS_SEEDED, "v2")
            val existingIds = base.providers.map { it.id.lowercase() }.toSet()
            val existingHosts = base.providers.map { it.baseUrl.lowercase() }.toSet()
            val additions = defaultProviderSettings().filter { def ->
                def.id.lowercase() !in existingIds && def.baseUrl.lowercase() !in existingHosts
            }
            base.providers + additions
        } else {
            base.providers
        }

        val restoredProviders = baseProviders.map { p ->
            if (p.encryptedApiKey.isNotBlank()) {
                p
            } else {
                val vaultKey = vault.getEncrypted(EncryptedKeystoreVault.slotForProvider(p.id))
                if (vaultKey.isNotBlank()) p.copy(encryptedApiKey = vaultKey) else p
            }
        }

        val restoredVoice = v.copy(
            encryptedApiKey = genericKey,
            fishEncryptedApiKey = fishKey,
            elevenLabsEncryptedApiKey = elevenKey,
            voiceId = activeVid,
            fishVoiceId = fishVid,
            elevenLabsVoiceId = elevenVid,
        )

        // Ensure the synchronous Keystore Vault stays populated with any keys loaded from DataStore
        syncVault(restoredVoice, restoredProviders)

        return base.copy(
            providers = restoredProviders,
            voice = restoredVoice,
        )
    }

    private fun mergeVoicePreservingSecrets(old: VoiceSettings, next: VoiceSettings): VoiceSettings {
        val fishKey = next.fishEncryptedApiKey
            .ifBlank { old.fishEncryptedApiKey }
            .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_FISH) }
        val elevenKey = next.elevenLabsEncryptedApiKey
            .ifBlank { old.elevenLabsEncryptedApiKey }
            .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ELEVEN) }
        val genericKey = next.encryptedApiKey
            .ifBlank { old.encryptedApiKey }
            .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.SLOT_TTS_ACTIVE) }
            .ifBlank {
                when (next.provider) {
                    VoiceProvider.FISH_AUDIO -> fishKey
                    VoiceProvider.ELEVENLABS -> elevenKey
                    VoiceProvider.SYSTEM -> fishKey.ifBlank { elevenKey }
                }
            }
        val fishVid = next.fishVoiceId
            .ifBlank { old.fishVoiceId }
            .ifBlank { vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_FISH) }
        val elevenVid = next.elevenLabsVoiceId
            .ifBlank { old.elevenLabsVoiceId }
            .ifBlank { vault.getMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_ELEVEN) }
        val activeVid = next.voiceId.ifBlank { old.voiceId }
        return next.copy(
            encryptedApiKey = genericKey,
            fishEncryptedApiKey = fishKey,
            elevenLabsEncryptedApiKey = elevenKey,
            voiceId = activeVid,
            fishVoiceId = fishVid,
            elevenLabsVoiceId = elevenVid,
        )
    }

    private fun mergeProvidersPreservingSecrets(
        old: List<ProviderSettings>,
        next: List<ProviderSettings>,
    ): List<ProviderSettings> {
        val oldById = old.associateBy { it.id }
        return next.map { candidate ->
            val resolvedKey = candidate.encryptedApiKey
                .ifBlank { oldById[candidate.id]?.encryptedApiKey.orEmpty() }
                .ifBlank { vault.getEncrypted(EncryptedKeystoreVault.slotForProvider(candidate.id)) }
            candidate.copy(encryptedApiKey = resolvedKey)
        }
    }

    private fun syncVoiceBackups(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        voice: VoiceSettings,
    ) {
        if (voice.encryptedApiKey.isNotBlank()) {
            preferences[VOICE_KEY_BACKUP] = voice.encryptedApiKey
        }
        if (voice.fishEncryptedApiKey.isNotBlank()) {
            preferences[FISH_KEY_BACKUP] = voice.fishEncryptedApiKey
        }
        if (voice.elevenLabsEncryptedApiKey.isNotBlank()) {
            preferences[ELEVEN_KEY_BACKUP] = voice.elevenLabsEncryptedApiKey
        }
        if (voice.fishVoiceId.isNotBlank()) {
            preferences[FISH_VOICE_ID_BACKUP] = voice.fishVoiceId
        }
        if (voice.elevenLabsVoiceId.isNotBlank()) {
            preferences[ELEVEN_VOICE_ID_BACKUP] = voice.elevenLabsVoiceId
        }
    }

    private fun syncVault(voice: VoiceSettings, providers: List<ProviderSettings>) {
        if (voice.encryptedApiKey.isNotBlank()) {
            vault.putEncrypted(EncryptedKeystoreVault.SLOT_TTS_ACTIVE, voice.encryptedApiKey)
        }
        if (voice.fishEncryptedApiKey.isNotBlank()) {
            vault.putEncrypted(EncryptedKeystoreVault.SLOT_TTS_FISH, voice.fishEncryptedApiKey)
        }
        if (voice.elevenLabsEncryptedApiKey.isNotBlank()) {
            vault.putEncrypted(EncryptedKeystoreVault.SLOT_TTS_ELEVEN, voice.elevenLabsEncryptedApiKey)
        }
        if (voice.fishVoiceId.isNotBlank()) {
            vault.putMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_FISH, voice.fishVoiceId)
        }
        if (voice.elevenLabsVoiceId.isNotBlank()) {
            vault.putMetadata(EncryptedKeystoreVault.SLOT_VOICE_ID_ELEVEN, voice.elevenLabsVoiceId)
        }
        providers.forEach { provider ->
            if (provider.encryptedApiKey.isNotBlank()) {
                vault.putEncrypted(
                    EncryptedKeystoreVault.slotForProvider(provider.id),
                    provider.encryptedApiKey,
                )
            }
        }
    }

    private fun decode(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }
            .getOrElse { AppSettings() }
    }

    private fun AppSettings.normalized(): AppSettings {
        val seen = mutableSetOf<String>()
        val cleaned = providers.mapNotNull { provider ->
            val id = provider.id.trim()
            if (id.isEmpty() || !seen.add(id)) {
                null
            } else {
                provider.copy(
                    id = id,
                    name = provider.name.trim().ifBlank { provider.kind.label },
                    baseUrl = provider.baseUrl.trim().trimEnd('/'),
                    model = provider.model.trim(),
                )
            }
        }
        val validDefault = defaultProviderId.takeIf { id -> cleaned.any { it.id == id } }
            ?: cleaned.firstOrNull()?.id
            ?: ""
        return copy(providers = cleaned, defaultProviderId = validDefault)
    }

    private fun OwnerProfile.sanitized(): OwnerProfile = copy(
        name = name.trim(),
        title = title.trim().ifBlank { "sir" },
        occupation = occupation.trim(),
        preferences = preferences.trim(),
    )

    private companion object {
        const val SLOT_GLOBAL_FREE_PROVIDERS_SEEDED = "seeded_global_free_providers"
        val STATE_KEY = stringPreferencesKey("settings_v1")
        val VOICE_KEY_BACKUP = stringPreferencesKey("voice_api_key_backup_v1")
        val FISH_KEY_BACKUP = stringPreferencesKey("voice_fish_api_key_backup_v1")
        val ELEVEN_KEY_BACKUP = stringPreferencesKey("voice_eleven_api_key_backup_v1")
        val FISH_VOICE_ID_BACKUP = stringPreferencesKey("voice_fish_voice_id_backup_v1")
        val ELEVEN_VOICE_ID_BACKUP = stringPreferencesKey("voice_eleven_voice_id_backup_v1")
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
