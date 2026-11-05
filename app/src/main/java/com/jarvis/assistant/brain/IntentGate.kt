package com.jarvis.assistant.brain

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Semantic classification of an incoming user utterance.
 */
enum class UserIntentKind {
    /** User is asking whether JARVIS supports or can perform a capability ("Are you able to...", "Can you..."). */
    CAPABILITY_QUESTION,

    /** User is asking how something works or for general information ("How would you send a voice message?"). */
    INFORMATIONAL_QUESTION,

    /** User is posing a hypothetical scenario ("What happens if someone calls me on WhatsApp?"). */
    HYPOTHETICAL_SCENARIO,

    /** User is cancelling, stopping, or correcting an unintended action ("Stop. I didn't ask you to send anything."). */
    CANCELLATION_OR_CORRECTION,

    /** User intends an action, but a required parameter (recipient or message content) is missing. */
    CLARIFICATION_NEEDED,

    /** User is giving an explicit, validated execution command ("Send a voice message to myself on WhatsApp saying good morning."). */
    EXECUTABLE_COMMAND,

    /** General conversation, greeting, or statement that requires no external device automation. */
    CONVERSATIONAL,
}

/**
 * Detailed result of semantic intent analysis.
 */
data class IntentAnalysis(
    val kind: UserIntentKind,
    val normalizedText: String,
    val reason: String,
    val clarificationPrompt: String? = null,
    val capabilityExplanation: String? = null,
) {
    val isExecutableCommand: Boolean
        get() = kind == UserIntentKind.EXECUTABLE_COMMAND

    val isNonExecutionInquiry: Boolean
        get() = kind == UserIntentKind.CAPABILITY_QUESTION ||
            kind == UserIntentKind.INFORMATIONAL_QUESTION ||
            kind == UserIntentKind.HYPOTHETICAL_SCENARIO

    val isCancellationOrCorrection: Boolean
        get() = kind == UserIntentKind.CANCELLATION_OR_CORRECTION
}

/**
 * Outcome of the Deterministic Execution Safety Gate before any tool call is allowed to run.
 */
sealed interface SafetyGateDecision {
    data object Allowed : SafetyGateDecision

    data class BlockedNonCommand(
        val intentKind: UserIntentKind,
        val explanation: String,
    ) : SafetyGateDecision

    data class NeedsClarification(
        val prompt: String,
    ) : SafetyGateDecision
}

/**
 * Deterministic Semantic Intent Classifier & Execution Safety Gate (Parts 2, 3, 4, 8).
 *
 * Ensures that:
 * 1. Capability inquiries ("Are you able to send voice messages and pick up the call?"),
 *    informational questions ("How would you send a voice message?"), and hypotheticals
 *    NEVER trigger tool calls, mission planning, WhatsApp opening, or audio recording.
 * 2. Corrections and cancellations ("Stop. I didn't ask you to send anything.") immediately
 *    halt execution and never re-trigger tools from words mentioned inside the correction.
 * 3. Consequential actions (WhatsApp messages, voice notes, calls, SMS, emails, screen automation)
 *    verify explicit instruction, explicit recipient, and explicit content before planning or execution.
 */
object IntentGate {

    private val CONSEQUENTIAL_TOOLS = setOf(
        "send_whatsapp_message",
        "send_whatsapp_voice_note",
        "send_social_voice_note",
        "answer_whatsapp_call",
        "end_whatsapp_call",
        "call_contact",
        "send_sms",
        "compose_email",
        "automate_screen",
        "tap_screen",
        "type_on_screen",
        "scroll_screen",
    )

    private val SELF_RECIPIENT_TOKENS = setOf(
        "myself",
        "me",
        "my own number",
        "my number",
        "my whatsapp",
        "my own whatsapp",
        "my chat",
        "my own chat",
        "self",
        "note to self",
    )

    private val INVALID_EXTRACTED_RECIPIENTS = setOf(
        "",
        "last",
        "latest",
        "recent",
        "reply",
        "auto",
        "unknown",
        "none",
        "null",
        "someone",
        "anyone",
        "anybody",
        "people",
        "contacts",
        "a contact",
        "the call",
        "call",
        "voice message",
        "voice messages",
        "whatsapp",
        "messages",
    )

    /**
     * Converts spoken emoji phrases (e.g. "heart emoji", "laughing emoji", "thumbs up emoji")
     * into their actual Unicode emoji characters while preserving existing Unicode emojis.
     */
    fun expandSpokenEmojis(text: String): String {
        if (text.isBlank()) return text
        var result = text
        val replacements = listOf(
            Regex("""(?i)\b(red\s+)?heart\s+emoji\b""") to "❤️",
            Regex("""(?i)\blove\s+emoji\b""") to "❤️",
            Regex("""(?i)\bpink\s+heart\s+emoji\b""") to "🩷",
            Regex("""(?i)\bblue\s+heart\s+emoji\b""") to "💙",
            Regex("""(?i)\bgreen\s+heart\s+emoji\b""") to "💚",
            Regex("""(?i)\byellow\s+heart\s+emoji\b""") to "💛",
            Regex("""(?i)\bpurple\s+heart\s+emoji\b""") to "💜",
            Regex("""(?i)\bblack\s+heart\s+emoji\b""") to "🖤",
            Regex("""(?i)\bwhite\s+heart\s+emoji\b""") to "🤍",
            Regex("""(?i)\bbroken\s+heart\s+emoji\b""") to "💔",
            Regex("""(?i)\b(laughing|laugh|joy|tears\s+of\s+joy)\s+emoji\b""") to "😂",
            Regex("""(?i)\b(rofl|rolling\s+on\s+the\s+floor\s+laughing)\s+emoji\b""") to "🤣",
            Regex("""(?i)\b(smile|smiling|happy)\s+emoji\b""") to "😊",
            Regex("""(?i)\b(grin|grinning)\s+emoji\b""") to "😁",
            Regex("""(?i)\b(wink|winking)\s+emoji\b""") to "😉",
            Regex("""(?i)\b(heart\s+eyes|smiling\s+with\s+heart\s+eyes)\s+emoji\b""") to "😍",
            Regex("""(?i)\b(kiss|blowing\s+a\s+kiss)\s+emoji\b""") to "😘",
            Regex("""(?i)\b(thumbs\s+up|like)\s+emoji\b""") to "👍",
            Regex("""(?i)\b(thumbs\s+down|dislike)\s+emoji\b""") to "👎",
            Regex("""(?i)\b(folded\s+hands|namaste|pray|praying|thank\s+you)\s+emoji\b""") to "🙏",
            Regex("""(?i)\b(fire|lit)\s+emoji\b""") to "🔥",
            Regex("""(?i)\b(sparkles|sparkle)\s+emoji\b""") to "✨",
            Regex("""(?i)\b(star)\s+emoji\b""") to "⭐",
            Regex("""(?i)\b(hundred|100)\s+emoji\b""") to "💯",
            Regex("""(?i)\b(clap|clapping)\s+emoji\b""") to "👏",
            Regex("""(?i)\b(party|celebration|tada)\s+emoji\b""") to "🎉",
            Regex("""(?i)\b(cry|crying|sad)\s+emoji\b""") to "😢",
            Regex("""(?i)\b(sob|sobbing|loudly\s+crying)\s+emoji\b""") to "😭",
            Regex("""(?i)\b(angry|mad)\s+emoji\b""") to "😠",
            Regex("""(?i)\b(thinking)\s+emoji\b""") to "🤔",
            Regex("""(?i)\b(cool|sunglasses)\s+emoji\b""") to "😎",
            Regex("""(?i)\b(ok|okay|ok\s+hand)\s+emoji\b""") to "👌",
            Regex("""(?i)\b(wave|waving|hi|hello)\s+emoji\b""") to "👋",
            Regex("""(?i)\b(rose|flower)\s+emoji\b""") to "🌹",
            Regex("""(?i)\b(check|check\s+mark)\s+emoji\b""") to "✅",
        )
        for ((pattern, emoji) in replacements) {
            result = result.replace(pattern, emoji)
        }
        return result
    }

    /**
     * Checks whether [recipient] refers to the owner's own WhatsApp chat ("myself", "me", "self").
     */
    fun isSelfRecipient(recipient: String): Boolean {
        val norm = recipient.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return norm in SELF_RECIPIENT_TOKENS
    }

    /**
     * Full-sentence semantic intent classification.
     */
    fun classify(rawText: String, ownerTitle: String = "sir"): IntentAnalysis {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return IntentAnalysis(
                kind = UserIntentKind.CONVERSATIONAL,
                normalizedText = "",
                reason = "Empty utterance.",
            )
        }

        // Strip leading wake-word address ("Jarvis,", "Hey Jarvis,", "Please") for structural analysis
        val withoutWake = trimmed
            .replace(Regex("""(?i)^(?:hey\s+|ok\s+|okay\s+|hi\s+|hello\s+)?jarvis[\s,!.:;-]*"""), "")
            .replace(Regex("""(?i)^please[\s,!.:;-]*"""), "")
            .trim()

        val norm = withoutWake.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s'?]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val normNoPunct = norm.replace("?", "").trim()

        // 1. Check for CANCELLATION or CORRECTION first (Highest safety priority)
        if (isCancellationOrCorrectionUtterance(normNoPunct)) {
            return IntentAnalysis(
                kind = UserIntentKind.CANCELLATION_OR_CORRECTION,
                normalizedText = normNoPunct,
                reason = "User issued a stop, cancellation, or correction statement.",
                capabilityExplanation = "Understood, $ownerTitle. I have stopped all active operations and cleared the queue.",
            )
        }

        // 2. Check for HYPOTHETICAL SCENARIOS ("What happens if...", "If someone called...", "Suppose...")
        if (isHypotheticalUtterance(normNoPunct)) {
            return IntentAnalysis(
                kind = UserIntentKind.HYPOTHETICAL_SCENARIO,
                normalizedText = normNoPunct,
                reason = "User posed a hypothetical scenario, not an execution instruction.",
                capabilityExplanation = buildCapabilityResponse(normNoPunct, ownerTitle),
            )
        }

        // 3. Check for INFORMATIONAL / HOW-TO QUESTIONS ("How would you send a voice message?", "How can you...")
        if (isInformationalQuestion(normNoPunct)) {
            return IntentAnalysis(
                kind = UserIntentKind.INFORMATIONAL_QUESTION,
                normalizedText = normNoPunct,
                reason = "User asked a how-to or informational question about JARVIS processes.",
                capabilityExplanation = buildCapabilityResponse(normNoPunct, ownerTitle),
            )
        }

        // 4. Check for CAPABILITY QUESTIONS ("Are you able to...", "Can you...", "Do you support...", "Is it possible...")
        if (isCapabilityQuestion(normNoPunct, trimmed.endsWith("?"))) {
            return IntentAnalysis(
                kind = UserIntentKind.CAPABILITY_QUESTION,
                normalizedText = normNoPunct,
                reason = "User asked a capability question. Mentioning a capability does not authorize execution.",
                capabilityExplanation = buildCapabilityResponse(normNoPunct, ownerTitle),
            )
        }

        // 5. Check for incomplete messaging / voice-note commands that need clarification
        val clarification = checkIncompleteCommandClarification(withoutWake, normNoPunct, ownerTitle)
        if (clarification != null) {
            return IntentAnalysis(
                kind = UserIntentKind.CLARIFICATION_NEEDED,
                normalizedText = normNoPunct,
                reason = "Command is missing an explicit recipient or message payload.",
                clarificationPrompt = clarification,
            )
        }

        return IntentAnalysis(
            kind = UserIntentKind.EXECUTABLE_COMMAND,
            normalizedText = normNoPunct,
            reason = "Validated as an explicit user instruction or conversational turn.",
        )
    }

    /**
     * Validates a specific tool call against the user's original utterance (Part 3 & Part 4).
     */
    fun validateToolCall(
        userMessage: String,
        toolName: String,
        argumentsJson: String,
        ownerTitle: String = "sir",
    ): SafetyGateDecision {
        val analysis = classify(userMessage, ownerTitle)

        // Rule 1: Never allow ANY tool execution on a cancellation or correction turn
        if (analysis.isCancellationOrCorrection) {
            return SafetyGateDecision.BlockedNonCommand(
                intentKind = analysis.kind,
                explanation = analysis.capabilityExplanation
                    ?: "Understood, $ownerTitle. I have cancelled the operation.",
            )
        }

        // Rule 2: Never allow consequential or automation tools on capability, informational, or hypothetical questions
        if (analysis.isNonExecutionInquiry && (toolName in CONSEQUENTIAL_TOOLS || toolName.startsWith("open_"))) {
            return SafetyGateDecision.BlockedNonCommand(
                intentKind = analysis.kind,
                explanation = analysis.capabilityExplanation
                    ?: buildCapabilityResponse(analysis.normalizedText, ownerTitle),
            )
        }

        // Rule 3: If the command was flagged as needing clarification, require clarification before running messaging tools
        if (analysis.kind == UserIntentKind.CLARIFICATION_NEEDED &&
            toolName in setOf("send_whatsapp_message", "send_whatsapp_voice_note", "send_social_voice_note", "send_sms")
        ) {
            return SafetyGateDecision.NeedsClarification(
                prompt = analysis.clarificationPrompt
                    ?: "Please specify both the recipient and the message you would like me to send, $ownerTitle.",
            )
        }

        // Rule 4: Deep parameter validation for WhatsApp / Social / SMS messaging tools
        if (toolName in setOf("send_whatsapp_voice_note", "send_whatsapp_message", "send_social_voice_note", "send_sms")) {
            val args = runCatching {
                Json.parseToJsonElement(argumentsJson) as? JsonObject
            }.getOrNull()

            val recipient = args?.get("recipient")?.jsonPrimitive?.content?.trim().orEmpty()
            val message = args?.get("message")?.jsonPrimitive?.content?.trim().orEmpty()
            val lowerUser = userMessage.lowercase(Locale.ROOT)
            val explicitlyAskedToReply = Regex("""\b(reply|respond)\b""").containsMatchIn(lowerUser)
            val explicitlyAskedForSelf = SELF_RECIPIENT_TOKENS.any { token ->
                Regex("""\bto\s+${Regex.escape(token)}\b""").containsMatchIn(lowerUser) ||
                    Regex("""\bmessage\s+${Regex.escape(token)}\b""").containsMatchIn(lowerUser) ||
                    Regex("""\bnote\s+to\s+self\b""").containsMatchIn(lowerUser)
            }

            // Validate recipient
            val normRecipient = recipient.lowercase(Locale.ROOT).trim()
            if (normRecipient.isBlank()) {
                return SafetyGateDecision.NeedsClarification(
                    "Who would you like me to send that message to, $ownerTitle?",
                )
            }
            if (normRecipient in setOf("last", "latest", "recent", "reply", "sender") && !explicitlyAskedToReply) {
                return SafetyGateDecision.NeedsClarification(
                    "Who would you like me to send that message to, $ownerTitle? Please tell me the contact's name or number.",
                )
            }
            if (isSelfRecipient(normRecipient) && !explicitlyAskedForSelf) {
                return SafetyGateDecision.NeedsClarification(
                    "Who should receive this message, $ownerTitle? Please specify the contact name.",
                )
            }
            if (normRecipient in INVALID_EXTRACTED_RECIPIENTS && !explicitlyAskedToReply) {
                return SafetyGateDecision.NeedsClarification(
                    "Please tell me the exact contact name or phone number for this message, $ownerTitle.",
                )
            }
            // Reject hallucinated recipients that do not appear anywhere in the user's command
            // (unless replying to the last message when explicitly asked)
            if (!explicitlyAskedToReply && !isSelfRecipient(normRecipient)) {
                val recipientTokens = normRecipient
                    .split(Regex("""[^\p{L}\p{N}]+"""))
                    .filter { it.length >= 2 }
                val userNorm = lowerUser.replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                val anyTokenInUserMessage = recipientTokens.isEmpty() || recipientTokens.any { token ->
                    userNorm.contains(token)
                }
                if (!anyTokenInUserMessage) {
                    return SafetyGateDecision.NeedsClarification(
                        "I did not hear a contact name in your request, $ownerTitle. Who would you like me to message?",
                    )
                }
            }

            // Validate message content
            val normMessage = message.lowercase(Locale.ROOT).trim()
            if (normMessage.isBlank() || (normMessage in setOf("auto", "reply", "draft") && !explicitlyAskedToReply)) {
                val targetLabel = if (isSelfRecipient(recipient)) "yourself" else recipient
                return SafetyGateDecision.NeedsClarification(
                    "What would you like me to say in the message to $targetLabel, $ownerTitle?",
                )
            }
        }

        // Rule 5: Validate answer_whatsapp_call
        if (toolName == "answer_whatsapp_call") {
            if (analysis.isNonExecutionInquiry) {
                return SafetyGateDecision.BlockedNonCommand(
                    intentKind = analysis.kind,
                    explanation = buildCapabilityResponse(analysis.normalizedText, ownerTitle),
                )
            }
        }

        return SafetyGateDecision.Allowed
    }

    private fun isCancellationOrCorrectionUtterance(norm: String): Boolean {
        val exactStops = setOf(
            "stop",
            "stop it",
            "stop now",
            "stop please",
            "please stop",
            "cancel",
            "cancel that",
            "cancel mission",
            "abort",
            "halt",
            "never mind",
            "nevermind",
            "enough",
            "quiet",
            "be quiet",
            "silence",
            "shut up",
            "wait",
            "hold on",
            "no",
            "nope",
            "do not",
            "dont",
            "don't",
        )
        if (norm in exactStops) return true

        val stopPrefixes = listOf(
            "stop ",
            "cancel ",
            "abort ",
            "halt ",
            "never mind ",
            "nevermind ",
            "wait stop",
            "no stop",
            "no cancel",
            "no do not",
            "no don't",
            "no dont",
            "do not send",
            "don't send",
            "dont send",
            "do not record",
            "don't record",
            "dont record",
            "do not call",
            "don't call",
            "dont call",
            "do not open",
            "don't open",
            "dont open",
            "do not do",
            "don't do",
            "dont do",
        )
        if (stopPrefixes.any { norm.startsWith(it) }) return true

        val correctionPhrases = listOf(
            "i didn't ask",
            "i did not ask",
            "i didn t ask",
            "i didn't tell you",
            "i did not tell you",
            "i didn t tell you",
            "i never asked",
            "i never told you",
            "didn't ask you to",
            "did not ask you to",
            "didn t ask you to",
            "that's not what i asked",
            "that is not what i asked",
            "not what i asked",
            "why are you sending",
            "why did you open",
            "why are you recording",
            "stop sending",
            "stop recording",
        )
        return correctionPhrases.any { norm.contains(it) }
    }

    private fun isHypotheticalUtterance(norm: String): Boolean {
        val prefixes = listOf(
            "what happens if ",
            "what would happen if ",
            "what would you do if ",
            "what will you do if ",
            "if someone calls ",
            "if someone called ",
            "if i ask you to ",
            "if i asked you to ",
            "suppose ",
            "assuming ",
            "in case someone ",
        )
        return prefixes.any { norm.startsWith(it) }
    }

    private fun isInformationalQuestion(norm: String): Boolean {
        val prefixes = listOf(
            "how would you ",
            "how do you ",
            "how can you ",
            "how does ",
            "how is ",
            "explain how you ",
            "tell me how you ",
            "what is the process ",
            "what are your capabilities",
            "what can you do",
            "what features do you have",
        )
        return prefixes.any { norm.startsWith(it) }
    }

    private fun isCapabilityQuestion(norm: String, hasQuestionMark: Boolean): Boolean {
        // Explicit capability inquiry prefixes — always capability questions
        val explicitCapabilityPrefixes = listOf(
            "are you able to ",
            "are you able ",
            "are you capable of ",
            "are you capable ",
            "do you have the ability to ",
            "do you support ",
            "is it possible to ",
            "is it possible for you to ",
            "do you know how to ",
            "can you tell me if you can ",
            "tell me if you can ",
            "have you got the ability to ",
        )
        if (explicitCapabilityPrefixes.any { norm.startsWith(it) || norm.contains(" $it") }) {
            return true
        }

        // Modal auxiliary prefixes ("can you ...", "could you ...", "do you ...", "will you ...")
        val modalPrefixes = listOf(
            "can you ",
            "could you ",
            "do you ",
            "are you ",
            "will you be able to ",
            "would you be able to ",
        )
        val matchedPrefix = modalPrefixes.firstOrNull { norm.startsWith(it) }
        if (matchedPrefix != null) {
            val remainder = norm.removePrefix(matchedPrefix).trim()

            // General plural or capability-level objects ("send voice messages", "pick up calls", "stay on call")
            val capabilityPatterns = listOf(
                Regex("""^(?:also\s+)?send\s+(?:whatsapp\s+)?(?:voice\s+)?messages\b"""),
                Regex("""^(?:also\s+)?send\s+(?:a\s+)?(?:whatsapp\s+)?(?:voice\s+)?(?:message|note|notes|messages)\s*$"""),
                Regex("""^(?:also\s+)?send\s+(?:a\s+)?(?:whatsapp\s+)?(?:voice\s+)?(?:message|note|notes|messages)\s+on\s+whatsapp\s*$"""),
                Regex("""^(?:also\s+)?(?:pick\s+up|answer|take|attend|handle|receive)\s+(?:a\s+|the\s+)?(?:whatsapp\s+)?(?:call|calls)\b"""),
                Regex(""".*\band\s+(?:pick\s+up|answer|stay\s+on)\s+(?:a\s+|the\s+)?call\b.*"""),
                Regex(""".*\bstay\s+on\s+(?:a\s+|the\s+)?call\b.*"""),
                Regex("""^(?:also\s+)?(?:make|place)\s+(?:phone\s+|whatsapp\s+)?calls\b"""),
                Regex("""^(?:also\s+)?(?:control|automate|read|see)\s+(?:my\s+|the\s+)?(?:phone|screen|apps)\b"""),
            )
            if (capabilityPatterns.any { it.containsMatchIn(remainder) }) {
                return true
            }

            // If it asks about sending a message/voice note with "can you" but provides NO recipient or NO message content,
            // it is a capability inquiry ("Can you send a WhatsApp voice message?"), not an instruction!
            val mentionsMessaging = remainder.contains("voice message") ||
                remainder.contains("voice note") ||
                remainder.contains("whatsapp message")
            if (mentionsMessaging) {
                val hasExplicitPayload = Regex("""\b(?:saying|that|with\s+message)\s+\S+""").containsMatchIn(remainder)
                val hasExplicitTarget = Regex("""\bto\s+[a-z0-9]+""").containsMatchIn(remainder)
                if (!hasExplicitPayload || !hasExplicitTarget) {
                    return true
                }
            }

            // "Do you ..." / "Are you ..." are always questions, never imperative commands
            if (matchedPrefix == "do you " || matchedPrefix == "are you ") {
                return true
            }
        }

        // If the sentence ends with '?' and mentions capabilities without an explicit recipient+message
        if (hasQuestionMark) {
            val asksAboutVoiceOrCall = norm.contains("voice message") ||
                norm.contains("voice note") ||
                norm.contains("pick up") ||
                norm.contains("stay on call") ||
                norm.contains("stay on the call") ||
                norm.contains("answer call") ||
                norm.contains("answer whatsapp")
            val hasSayingClause = Regex("""\b(?:saying|that)\s+\S+""").containsMatchIn(norm)
            if (asksAboutVoiceOrCall && !hasSayingClause) {
                return true
            }
        }

        return false
    }

    private fun checkIncompleteCommandClarification(
        withoutWake: String,
        norm: String,
        ownerTitle: String,
    ): String? {
        val isVoiceNoteCommand = Regex(
            """^(?:send|record|leave)\s+(?:a\s+)?(?:whatsapp\s+)?(?:voice\s+message|voice\s+note|audio\s+message)\b""",
        ).containsMatchIn(norm)
        if (isVoiceNoteCommand) {
            val hasReplyIntent = Regex("""\b(reply|respond)\b""").containsMatchIn(norm)
            if (hasReplyIntent) return null

            val toMatch = Regex(
                """(?i)\bto\s+([^\n:]+?)(?:\s+on\s+whatsapp)?(?:\s+(?:saying|as|that|telling|with\s+message|:)\s+(.+))?$""",
            ).find(withoutWake)
            val target = toMatch?.groupValues?.getOrNull(1)
                ?.replace(Regex("""(?i)\s+on\s+whatsapp.*$"""), "")
                ?.trim()
                .orEmpty()
            val message = toMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()

            if (target.isBlank()) {
                return "Who would you like me to send the WhatsApp voice message to, $ownerTitle, and what should I say?"
            }
            if (message.isBlank()) {
                val targetLabel = if (isSelfRecipient(target)) "yourself" else target
                return "What would you like me to say in the WhatsApp voice message to $targetLabel, $ownerTitle?"
            }
        }

        val isTextMessageCommand = Regex(
            """^(?:send\s+(?:a\s+)?(?:whatsapp\s+)?message|whatsapp)\b""",
        ).containsMatchIn(norm)
        if (isTextMessageCommand && !norm.contains("voice")) {
            val hasTo = Regex("""\bto\s+\S+""").containsMatchIn(norm)
            val hasMessage = Regex("""\b(?:saying|that)\s+\S+""").containsMatchIn(norm) || withoutWake.contains(":")
            if (!hasTo) {
                return "Who would you like me to send the WhatsApp message to, $ownerTitle?"
            }
            if (!hasMessage) {
                return "What message would you like me to send, $ownerTitle?"
            }
        }

        return null
    }

    /**
     * Generates an accurate, butler-grade explanation of JARVIS's real capabilities and limitations
     * without triggering any automation tools.
     */
    fun buildCapabilityResponse(norm: String, ownerTitle: String = "sir"): String {
        val asksVoiceNote = norm.contains("voice message") ||
            norm.contains("voice note") ||
            norm.contains("audio message")
        val asksCall = norm.contains("call") ||
            norm.contains("pick up") ||
            norm.contains("stay on")

        return when {
            asksVoiceNote && asksCall ->
                "Yes, $ownerTitle. I can record and send WhatsApp voice messages in my own voice once you name the contact and what to say, and I can answer incoming WhatsApp calls on speakerphone and converse with the caller as your receptionist. I will only perform those actions when you explicitly command me to do so."

            asksVoiceNote && norm.startsWith("how") ->
                "When you give an explicit command with a contact name and message, $ownerTitle, I synthesize the spoken audio in my voice, ask for your confirmation, open that specific contact's WhatsApp chat, hold the voice-note microphone while playing the audio, and verify that the voice note bubble was sent."

            asksVoiceNote ->
                "Yes, $ownerTitle. I support sending WhatsApp voice messages in my own voice. Whenever you would like me to send one, simply tell me the recipient and what to say—for example, 'Send a voice message to myself on WhatsApp saying good morning'—and I will ask for your confirmation before sending."

            asksCall ->
                "Yes, $ownerTitle. When an incoming WhatsApp call is ringing, I can click the Answer button, switch the call to speakerphone, and speak with the caller on your behalf to take a message. Because Android restricts direct cellular call-stream injection on non-system apps, speakerphone acoustics are used while staying on the call."

            else ->
                "Yes, $ownerTitle, that capability is supported and ready. Let me know if you would like me to execute a specific command."
        }
    }
}
