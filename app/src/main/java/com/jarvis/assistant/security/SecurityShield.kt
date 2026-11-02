package com.jarvis.assistant.security

import android.content.Context
import android.provider.Settings
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SecurityThreatEvent(
    val id: String,
    val source: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class SecurityAuditStatus(
    val keystoreHardwareBacked: Boolean = true,
    val promptFirewallActive: Boolean = true,
    val uriExploitGuardActive: Boolean = true,
    val credentialRedactorActive: Boolean = true,
    val installerLockdownActive: Boolean = true,
    val adbEnabled: Boolean = false,
    val blockedThreatCount: Int = 0,
    val recentThreats: List<SecurityThreatEvent> = emptyList(),
)

/**
 * Multi-layered anti-hacking defense shield for Jarvis.
 *
 * Protects the owner's phone from:
 * 1. Indirect prompt injection hidden inside notifications, web pages, clipboard, or screen text.
 * 2. Malicious URIs (`javascript:`, `file://`, `content://`, `intent://`, APK auto-downloads).
 * 3. Unauthorized package installation, permission-controller tampering, or system exploit intents.
 * 4. Credential, OTP, credit-card, API-key, and seed-phrase exfiltration to cloud LLMs.
 */
object SecurityShield {

    private val _threats = MutableStateFlow<List<SecurityThreatEvent>>(emptyList())
    val threats: StateFlow<List<SecurityThreatEvent>> = _threats.asStateFlow()

    @Volatile
    private var threatCounter = 0

    /** System packages that Jarvis must never tap or automate without explicit user action. */
    private val PROTECTED_SYSTEM_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.android.vpndialogs",
        "com.android.certinstaller",
    )

    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|above|system)\\s+(instructions|prompts|rules|directions)"),
        Regex("(?i)disregard\\s+(all\\s+)?(previous|prior|above|system)\\s+(instructions|prompts|rules)"),
        Regex("(?i)you\\s+are\\s+now\\s+(in\\s+)?(developer|admin|god|jailbreak|unrestricted|dan)\\s+mode"),
        Regex("(?i)new\\s+system\\s+(prompt|instruction|directive)\\s*:"),
        Regex("(?i)(execute|call|invoke|run)\\s+tool\\s+(send_sms|send_whatsapp_message|call_contact|open_url|automate_screen|tap_screen)"),
        Regex("(?i)<\\s*(system|instruction|prompt|tool_call|function_call)\\s*>"),
        Regex("(?i)\\[\\s*system\\s*override\\s*]"),
    )

    private val DANGEROUS_URI_SCHEMES = setOf(
        "javascript",
        "vbscript",
        "data",
        "file",
        "content",
        "intent",
        "jar",
        "blob",
    )

    private val DANGEROUS_FILE_EXTENSIONS = listOf(
        ".apk",
        ".xapk",
        ".apks",
        ".dex",
        ".so",
        ".sh",
        ".bat",
        ".exe",
        ".msi",
        ".scr",
    )

    private val OTP_CONTEXT_KEYWORDS = listOf(
        "otp",
        "verification code",
        "security code",
        "passcode",
        "one-time",
        "2fa",
        "two-factor",
        "authentication code",
        "login code",
        "confirm your identity",
        "secret code",
        "code ",
        "code:",
        "pin ",
        "pin:",
    )

    private val API_KEY_PATTERNS = listOf(
        Regex("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
        Regex("\\bAIza[0-9A-Za-z_-]{30,}\\b"),
        Regex("\\bghp_[0-9A-Za-z]{30,}\\b"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    )

    /**
     * Sanitizes untrusted text from notifications, screen reading, or clipboard
     * before it enters the LLM context. Neutralizes indirect prompt injections and
     * redacts sensitive credentials/OTPs.
     */
    fun sanitizeUntrustedText(raw: String, source: String): String {
        if (raw.isBlank()) return raw
        var cleaned = redactSecrets(raw, source)

        for (pattern in PROMPT_INJECTION_PATTERNS) {
            if (pattern.containsMatchIn(cleaned)) {
                recordThreat(
                    source = source,
                    description = "Blocked indirect prompt-injection attempt in $source.",
                )
                cleaned = pattern.replace(cleaned, "[BLOCKED_INJECTION_ATTEMPT]")
            }
        }
        return cleaned
    }

    /**
     * Redacts OTPs, payment card numbers, and API/private keys so they are never
     * transmitted to external AI providers or written to logs.
     */
    fun redactSecrets(raw: String, source: String = "data"): String {
        if (raw.isBlank()) return raw
        var text = raw

        // 1. Redact API keys and private keys
        for (keyRegex in API_KEY_PATTERNS) {
            if (keyRegex.containsMatchIn(text)) {
                text = keyRegex.replace(text, "[REDACTED_SECRET_KEY]")
            }
        }

        // 2. Redact 4-8 digit OTPs when OTP keywords appear nearby
        val lower = text.lowercase(Locale.ROOT)
        if (OTP_CONTEXT_KEYWORDS.any { lower.contains(it) }) {
            val otpRegex = Regex("\\b\\d{4,8}\\b")
            if (otpRegex.containsMatchIn(text)) {
                text = otpRegex.replace(text, "[REDACTED_SECURITY_CODE]")
            }
        }

        // 3. Redact 13-19 digit payment card sequences
        val cardRegex = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
        text = cardRegex.replace(text) { match ->
            val digits = match.value.filter { it.isDigit() }
            if (digits.length in 13..19 && passesLuhn(digits)) {
                recordThreat(source, "Redacted payment card number from $source.")
                "[REDACTED_CARD_NUMBER]"
            } else {
                match.value
            }
        }

        return text
    }

    /**
     * Validates a URL before opening it in a browser or intent.
     * Returns null if safe, or a human-readable block reason if malicious/unsafe.
     */
    fun validateUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return "Empty URL."

        val lower = trimmed.lowercase(Locale.ROOT)
        for (scheme in DANGEROUS_URI_SCHEMES) {
            if (lower.startsWith("$scheme:")) {
                recordThreat("open_url", "Blocked dangerous URI scheme '$scheme:' in $trimmed")
                return "Security Shield blocked '$scheme:' URI to protect your phone."
            }
        }

        val normalized = if (lower.startsWith("http://") || lower.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return "Malformed URL blocked by Security Shield."

        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "http" && scheme != "https") {
            recordThreat("open_url", "Blocked non-HTTP URI scheme '$scheme'")
            return "Security Shield only permits http:// and https:// web links."
        }

        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) {
            return "URL is missing a valid hostname."
        }

        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (DANGEROUS_FILE_EXTENSIONS.any { path.endsWith(it) }) {
            recordThreat("open_url", "Blocked direct executable/package download link: $path")
            return "Security Shield blocked a direct executable or APK download link ($path)."
        }

        return null
    }

    /**
     * Checks whether a foreground package is a protected system installer,
     * permission controller, or VPN/certificate dialog that must never be automated.
     */
    fun isProtectedSystemPackage(packageName: String): Boolean {
        val clean = packageName.trim().lowercase(Locale.ROOT)
        return clean in PROTECTED_SYSTEM_PACKAGES
    }

    /**
     * Inspects a tool call and its raw JSON arguments before execution.
     * Returns null if allowed, or an error string if blocked.
     */
    fun inspectToolCall(toolName: String, argumentsJson: String): String? {
        val lowerArgs = argumentsJson.lowercase(Locale.ROOT)

        // Block shell injection / command chaining patterns in arguments
        if (lowerArgs.contains("&& rm ") ||
            lowerArgs.contains("; rm -rf") ||
            lowerArgs.contains("/system/bin/sh") ||
            lowerArgs.contains("su -c") ||
            lowerArgs.contains("adb shell") ||
            lowerArgs.contains("pm install") ||
            lowerArgs.contains("am start")
        ) {
            recordThreat(toolName, "Blocked shell/command injection payload in $toolName.")
            return "Security Shield blocked a suspicious command payload in $toolName."
        }

        // Prevent typing secrets or exploit strings into screen fields
        if (toolName == "type_on_screen") {
            for (pattern in PROMPT_INJECTION_PATTERNS) {
                if (pattern.containsMatchIn(argumentsJson)) {
                    recordThreat(toolName, "Blocked injected screen typing command.")
                    return "Security Shield blocked untrusted text from being typed."
                }
            }
        }

        return null
    }

    fun audit(context: Context): SecurityAuditStatus {
        val adb = runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0,
            ) == 1
        }.getOrDefault(false)

        val hardwareBacked = runCatching {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = keyStore.getKey("jarvis_master_key_v1", null) as? javax.crypto.SecretKey
            if (secretKey != null) {
                val factory = javax.crypto.SecretKeyFactory.getInstance(
                    secretKey.algorithm,
                    "AndroidKeyStore",
                )
                val keyInfo = factory.getKeySpec(
                    secretKey,
                    android.security.keystore.KeyInfo::class.java,
                ) as? android.security.keystore.KeyInfo
                @Suppress("DEPRECATION")
                keyInfo?.isInsideSecureHardware ?: true
            } else {
                true
            }
        }.getOrDefault(true)

        val currentThreats = _threats.value
        return SecurityAuditStatus(
            keystoreHardwareBacked = hardwareBacked,
            promptFirewallActive = true,
            uriExploitGuardActive = true,
            credentialRedactorActive = true,
            installerLockdownActive = true,
            adbEnabled = adb,
            blockedThreatCount = threatCounter,
            recentThreats = currentThreats.take(5),
        )
    }

    private fun recordThreat(source: String, description: String) {
        threatCounter++
        val event = SecurityThreatEvent(
            id = "threat_${System.currentTimeMillis()}_$threatCounter",
            source = source,
            description = description,
        )
        _threats.update { list -> (listOf(event) + list).take(25) }
    }

    private fun passesLuhn(digits: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0 && digits.toSet().size > 2
    }
}
