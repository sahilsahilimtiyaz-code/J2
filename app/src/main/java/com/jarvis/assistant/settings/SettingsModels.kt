package com.jarvis.assistant.settings

import com.jarvis.assistant.brain.ProviderKind
import kotlinx.serialization.Serializable

@Serializable
enum class VoiceProvider(val label: String) {
    SYSTEM("Android system voice"),
    FISH_AUDIO("Fish Audio"),
    ELEVENLABS("ElevenLabs"),
}

@Serializable
enum class SystemVoiceGender(val label: String, val shortLabel: String) {
    MALE("Male (J.A.R.V.I.S. British Butler)", "JARVIS (Male)"),
    FEMALE("Female (F.R.I.D.A.Y. Neural)", "FRIDAY (Female)"),
}

@Serializable
enum class HudColorTheme(
    val label: String,
    val shortLabel: String,
    val primaryHex: Long,
    val brightHex: Long,
    val dimHex: Long,
    val voidHex: Long,
    val panelHex: Long,
) {
    IM3_SEAFOAM(
        label = "Iron Man 3 Seafoam (Original Video)",
        shortLabel = "IM3 Video",
        primaryHex = 0xFF42C8C4L,
        brightHex = 0xFF6EF8F0L,
        dimHex = 0xFF125A5CL,
        voidHex = 0xFF010A0CL,
        panelHex = 0xFF05181CL,
    ),
    BLUE(
        label = "Arc Reactor Blue",
        shortLabel = "Blue",
        primaryHex = 0xFF0088FFL,
        brightHex = 0xFF4DC4FFL,
        dimHex = 0xFF0A3D7AL,
        voidHex = 0xFF01060FL,
        panelHex = 0xFF051329L,
    ),
    SKY_BLUE(
        label = "Sky Blue",
        shortLabel = "Sky Blue",
        primaryHex = 0xFF38BDF8L,
        brightHex = 0xFF7DD3FCL,
        dimHex = 0xFF0C4A6EL,
        voidHex = 0xFF020810L,
        panelHex = 0xFF061928L,
    ),
    WHITE(
        label = "Pure White",
        shortLabel = "White",
        primaryHex = 0xFFD8E6F2L,
        brightHex = 0xFFFFFFFFL,
        dimHex = 0xFF475569L,
        voidHex = 0xFF05070AL,
        panelHex = 0xFF11161FL,
    ),
    RED(
        label = "Crimson Red",
        shortLabel = "Red",
        primaryHex = 0xFFEF4444L,
        brightHex = 0xFFFF7A7AL,
        dimHex = 0xFF7F1D1DL,
        voidHex = 0xFF0B0203L,
        panelHex = 0xFF210709L,
    ),
    PURPLE(
        label = "Quantum Purple",
        shortLabel = "Purple",
        primaryHex = 0xFFA855F7L,
        brightHex = 0xFFD8B4FEL,
        dimHex = 0xFF581C87L,
        voidHex = 0xFF07020DL,
        panelHex = 0xFF170726L,
    ),
    VIOLET(
        label = "Neural Violet",
        shortLabel = "Violet",
        primaryHex = 0xFF7C3AEDL,
        brightHex = 0xFFC4B5FDL,
        dimHex = 0xFF4C1D95L,
        voidHex = 0xFF05020DL,
        panelHex = 0xFF130829L,
    ),
    EMERALD(
        label = "Emerald Green",
        shortLabel = "Emerald",
        primaryHex = 0xFF10B981L,
        brightHex = 0xFF6EE7B7L,
        dimHex = 0xFF065F46L,
        voidHex = 0xFF010A06L,
        panelHex = 0xFF051F15L,
    ),
    GOLD(
        label = "Stark Gold",
        shortLabel = "Gold",
        primaryHex = 0xFFF59E0BL,
        brightHex = 0xFFFDE68AL,
        dimHex = 0xFF78350FL,
        voidHex = 0xFF0A0601L,
        panelHex = 0xFF211404L,
    ),
}

@Serializable
data class OwnerProfile(
    val name: String = "",
    val title: String = "sir",
    val occupation: String = "",
    val preferences: String = "",
)

@Serializable
data class ProviderSettings(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val encryptedApiKey: String = "",
    val enabled: Boolean = true,
) {
    val requiresApiKey: Boolean
        get() = kind != ProviderKind.LOCAL_ON_DEVICE &&
            !baseUrl.contains("127.0.0.1") &&
            !baseUrl.contains("localhost", ignoreCase = true) &&
            !baseUrl.contains("pollinations.ai", ignoreCase = true)

    val hasSavedKey: Boolean get() = encryptedApiKey.isNotBlank()
    val isReadyToUse: Boolean get() = enabled && (hasSavedKey || !requiresApiKey)
}

@Serializable
data class PersonaSettings(
    val tone: String = "dry British wit, calm and understated",
    val customPrompt: String? = null,
    val lastGreetingId: String? = null,
    /** Epoch millis of the last spoken greeting, so Jarvis does not repeat himself. */
    val lastGreetingAt: Long = 0L,
    /** Allows Jarvis to monitor device usage and rarely share spontaneous care, wit, or notification insights. */
    val autonomousThoughtsEnabled: Boolean = true,
    /** Dynamic Orb & Bottom Bar HUD color theme (defaults to 100% Iron Man 3 Seafoam). */
    val hudColorTheme: HudColorTheme = HudColorTheme.IM3_SEAFOAM,
)

@Serializable
data class VoiceSettings(
    val provider: VoiceProvider = VoiceProvider.SYSTEM,
    val systemVoiceGender: SystemVoiceGender = SystemVoiceGender.MALE,
    val encryptedApiKey: String = "",
    val fishEncryptedApiKey: String = "",
    val elevenLabsEncryptedApiKey: String = "",
    val voiceId: String = "",
    val fishVoiceId: String = "",
    val elevenLabsVoiceId: String = "",
    /** Cloud model name. Fish Audio defaults to the free S2.1 model. */
    val model: String = "",
    val speed: Float = 1.0f,
    val fallbackToSystem: Boolean = true,
    val language: String = "en-US",
    /** Keep the microphone live continuously across turns like a live phone call (on by default). */
    val continuousListening: Boolean = true,
    /** Keep the microphone service running in the background so the live mic never drops (on by default). */
    val alwaysListening: Boolean = true,
    /** Require the wake word before treating speech as a command in background mode. */
    val requireWakeWord: Boolean = true,
    val wakeWord: String = "jarvis",
) {
    val activeEncryptedKey: String
        get() = when (provider) {
            VoiceProvider.FISH_AUDIO -> fishEncryptedApiKey.ifBlank { encryptedApiKey }
            VoiceProvider.ELEVENLABS -> elevenLabsEncryptedApiKey.ifBlank { encryptedApiKey }
            VoiceProvider.SYSTEM -> encryptedApiKey
        }

    val activeVoiceId: String
        get() = when (provider) {
            VoiceProvider.FISH_AUDIO -> voiceId.ifBlank { fishVoiceId }
            VoiceProvider.ELEVENLABS -> voiceId.ifBlank { elevenLabsVoiceId }
            VoiceProvider.SYSTEM -> voiceId
        }

    val hasSavedKey: Boolean
        get() = activeEncryptedKey.isNotBlank() || encryptedApiKey.isNotBlank()

    fun hasSavedKeyFor(target: VoiceProvider): Boolean = when (target) {
        VoiceProvider.FISH_AUDIO -> fishEncryptedApiKey.isNotBlank() ||
            (provider == VoiceProvider.FISH_AUDIO && encryptedApiKey.isNotBlank())
        VoiceProvider.ELEVENLABS -> elevenLabsEncryptedApiKey.isNotBlank() ||
            (provider == VoiceProvider.ELEVENLABS && encryptedApiKey.isNotBlank())
        VoiceProvider.SYSTEM -> true
    }
}

@Serializable
data class HealthSettings(
    /** Master switch for the background screen usage and voice health reminder service. */
    val enabled: Boolean = true,
    /** Speak health suggestions aloud when screen usage exceeds the recommended session limit. */
    val voiceSuggestionsEnabled: Boolean = true,
    /** Recommended continuous screen usage limit in minutes before a friendly break reminder. */
    val sessionLimitMinutes: Int = 15,
    /** Screen-off minutes required to count as a completed rest break. */
    val breakResetMinutes: Int = 5,
    /** Quiet hours start (24-hour clock, default 23 = 11 PM) when voice reminders stay silent. */
    val quietHoursStart: Int = 23,
    /** Quiet hours end (24-hour clock, default 7 = 7 AM). */
    val quietHoursEnd: Int = 7,
)

@Serializable
data class SafetySettings(
    /** Master switch for screen control through the accessibility service. */
    val screenControlEnabled: Boolean = true,
    /**
     * Package-name and app-label keywords that are never automated. The default
     * list covers banking, payments, password managers and authenticators.
     */
    val blocklistKeywords: List<String> = DEFAULT_BLOCKLIST,
    /**
     * WhatsApp call receptionist: when enabled, Jarvis answers incoming
     * WhatsApp voice calls, tells the caller sir is unavailable and engages
     * them about what they say, with his own voice.
     */
    val callAssistantEnabled: Boolean = true,
    /** How many caller exchanges the call assistant may hold (clamped 2..12). */
    val callAssistantMaxTurns: Int = 6,
    /**
     * WhatsApp voice messages: Jarvis speaks the line and records it through
     * WhatsApp's own microphone, producing a real PTT voice note. The manual
     * tool still passes the Confirmation Gate.
     */
    val voiceNoteEnabled: Boolean = true,
    /** Offer the audio-file share handoff when WhatsApp's recorder cannot be driven. */
    val voiceNoteAudioFallbackEnabled: Boolean = true,
    /** Longest spoken note; protects credit spend and WhatsApp's recording window. */
    val voiceNoteMaxChars: Int = 420,
    /** Opt-in: answer incoming WhatsApp messages with a voice note. */
    val voiceNoteAutoReplyEnabled: Boolean = false,
    /** Contacts (names or numbers) allowed to receive automatic voice replies. */
    val voiceNoteAllowedContacts: List<String> = emptyList(),
    /** Minutes between automatic replies to the same contact. */
    val voiceNoteCooldownMinutes: Int = 10,
    /** Hard daily cap on automatic replies, per device. */
    val voiceNoteDailyLimit: Int = 12,
    /**
     * Master toggle: when true (default), Jarvis executes owner-requested phone
     * actions immediately without blocking on a confirmation popup.
     */
    val alwaysAllowActions: Boolean = true,
    /** When alwaysAllowActions is false, whether to show a confirmation popup before placing calls. */
    val confirmCalls: Boolean = false,
    /** When alwaysAllowActions is false, whether to show a confirmation popup before sending SMS/WhatsApp messages. */
    val confirmMessages: Boolean = false,
    /** When alwaysAllowActions is false, whether to show a confirmation popup before sensitive screen taps. */
    val confirmScreenControl: Boolean = false,
    /** When alwaysAllowActions is false, whether to show a confirmation popup before deleting memories/items. */
    val confirmDeletions: Boolean = false,
)

@Serializable
enum class AuthMode {
    NONE,
    GOOGLE,
    GUEST,
}

@Serializable
data class AuthSession(
    val mode: AuthMode = AuthMode.NONE,
    val email: String = "",
    val displayName: String = "",
    val signedInAt: Long = 0L,
) {
    val isAuthenticated: Boolean
        get() = mode != AuthMode.NONE
}

@Serializable
data class LocalAiSettings(
    val activeModelId: String = "lfm2_5_350m",
    val autoFallbackWhenOffline: Boolean = true,
)

@Serializable
data class AppSettings(
    /**
     * Global power switch. When false Jarvis releases the microphone, stops every
     * foreground service, silences all speech and stays completely off until the
     * owner turns him back on.
     */
    val masterEnabled: Boolean = true,
    val profile: OwnerProfile = OwnerProfile(),
    val providers: List<ProviderSettings> = defaultProviderSettings(),
    val defaultProviderId: String = "openrouter",
    val persona: PersonaSettings = PersonaSettings(),
    val voice: VoiceSettings = VoiceSettings(),
    val safety: SafetySettings = SafetySettings(),
    val health: HealthSettings = HealthSettings(),
    val auth: AuthSession = AuthSession(),
    val localAi: LocalAiSettings = LocalAiSettings(),
    val schemaMigrationVersion: Int = 1,
) {
    val defaultProvider: ProviderSettings?
        get() = providers.firstOrNull { it.id == defaultProviderId }
            ?: providers.firstOrNull()

    val configuredProviders: List<ProviderSettings>
        get() = providers.filter { it.isReadyToUse }
}

/** Keywords are matched case-insensitively against the package name and label. */
val DEFAULT_BLOCKLIST: List<String> = listOf(
    "bank",
    "banking",
    "neobank",
    "pay",
    "payment",
    "wallet",
    "upi",
    "venmo",
    "paypal",
    "cash app",
    "google pay",
    "samsung pay",
    "password",
    "1password",
    "bitwarden",
    "lastpass",
    "keepass",
    "keeper",
    "authenticator",
    "authy",
    "otp",
)

fun defaultProviderSettings(): List<ProviderSettings> = listOf(
    ProviderSettings(
        id = "local_ai",
        name = "Offline Local AI",
        kind = ProviderKind.LOCAL_ON_DEVICE,
        baseUrl = ProviderKind.LOCAL_ON_DEVICE.defaultBaseUrl,
        model = "lfm2_5_350m",
    ),
    ProviderSettings(
        id = "openrouter",
        name = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = ProviderKind.OPENAI_COMPATIBLE.defaultBaseUrl,
        model = "meta-llama/llama-3.3-70b-instruct:free",
    ),
    ProviderSettings(
        id = "gemini",
        name = "Google Gemini",
        kind = ProviderKind.GEMINI,
        baseUrl = ProviderKind.GEMINI.defaultBaseUrl,
        model = "gemini-2.5-flash",
    ),
    ProviderSettings(
        id = "zai",
        name = "Z.ai",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.z.ai/api/paas/v4",
        model = "glm-4.5-flash",
    ),
    ProviderSettings(
        id = "nvidia_nim",
        name = "NVIDIA NIM",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://integrate.api.nvidia.com/v1",
        model = "meta/llama-3.3-70b-instruct",
    ),
    ProviderSettings(
        id = "opencode_zen",
        name = "OpenCode Zen",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://opencode.ai/zen/v1",
        model = "big-pickle",
    ),
    ProviderSettings(
        id = "agnes_ai",
        name = "Agnes AI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://apihub.agnes-ai.com/v1",
        model = "agnes-3.0-flash",
    ),
    ProviderSettings(
        id = "groq",
        name = "Groq",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
    ),
    ProviderSettings(
        id = "cerebras",
        name = "Cerebras",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.cerebras.ai/v1",
        model = "llama-3.3-70b",
    ),
    ProviderSettings(
        id = "sambanova",
        name = "SambaNova",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.sambanova.ai/v1",
        model = "Meta-Llama-3.3-70B-Instruct",
    ),
    ProviderSettings(
        id = "mistral",
        name = "Mistral AI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.mistral.ai/v1",
        model = "mistral-small-latest",
    ),
    ProviderSettings(
        id = "github_models",
        name = "GitHub Models",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://models.inference.ai.azure.com",
        model = "gpt-4o-mini",
    ),
    ProviderSettings(
        id = "siliconflow",
        name = "SiliconFlow",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.siliconflow.com/v1",
        model = "Qwen/Qwen2.5-7B-Instruct",
    ),
    ProviderSettings(
        id = "cohere",
        name = "Cohere",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.cohere.com/compatibility/v1",
        model = "command-a-03-2025",
    ),
    ProviderSettings(
        id = "anthropic",
        name = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = ProviderKind.ANTHROPIC.defaultBaseUrl,
        model = "claude-haiku-4-5",
    ),
)

