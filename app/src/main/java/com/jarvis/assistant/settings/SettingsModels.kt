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
        get() = !baseUrl.contains("127.0.0.1") &&
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
)

@Serializable
data class VoiceSettings(
    val provider: VoiceProvider = VoiceProvider.SYSTEM,
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
    /** Resume listening automatically after Jarvis finishes a reply. */
    val continuousListening: Boolean = true,
    /** Keep the microphone service running in the background once the app is opened. */
    val alwaysListening: Boolean = true,
    /** Require the wake word before treating speech as a command in background mode. */
    val requireWakeWord: Boolean = false,
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
    val callAssistantEnabled: Boolean = false,
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
)

@Serializable
data class AppSettings(
    val profile: OwnerProfile = OwnerProfile(),
    val providers: List<ProviderSettings> = defaultProviderSettings(),
    val defaultProviderId: String = "openrouter",
    val persona: PersonaSettings = PersonaSettings(),
    val voice: VoiceSettings = VoiceSettings(),
    val safety: SafetySettings = SafetySettings(),
    val health: HealthSettings = HealthSettings(),
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

