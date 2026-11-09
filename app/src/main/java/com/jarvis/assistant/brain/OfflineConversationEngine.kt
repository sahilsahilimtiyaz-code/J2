package com.jarvis.assistant.brain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the on-device brain needs to answer without a network connection.
 */
data class OfflineReplyContext(
    val ownerTitle: String = "sir",
    val ownerName: String = "",
    val modelName: String = "the built-in offline engine",
    val modelSizeLabel: String = "",
    val modelReady: Boolean = false,
    val memoryLines: List<String> = emptyList(),
    val lastAssistantReply: String? = null,
    val previousUserQuestion: String? = null,
    val networkAvailable: Boolean = true,
    val now: Date = Date(),
)

/**
 * The conversational half of Jarvis that runs entirely on the phone.
 *
 * [OfflineTaskRouter] turns explicit commands into tool calls. This engine answers
 * everything that is *not* a command: greetings, small talk, questions about itself,
 * follow-ups, memory questions and general conversation. It never invents a fact it
 * cannot verify and it never repeats the same sentence twice in a row, which is what
 * made the previous offline replies feel like a broken record.
 */
object OfflineConversationEngine {

    fun reply(userText: String, context: OfflineReplyContext): String {
        val title = context.ownerTitle.trim().ifBlank { "sir" }
        val raw = userText.trim()
        if (raw.isBlank()) return "I am listening, $title."
        val text = stripWakeWord(raw)
        val lower = text.lowercase(Locale.ROOT).trim()
        val seed = (lower + "|" + raw + "|" + (context.lastAssistantReply ?: "")).hashCode()

        // The owner simply said "Jarvis" (or "hey Jarvis") to get attention.
        if (lower.isBlank()) {
            return greeting(title, context.now, seed, context.memoryLines.isNotEmpty())
        }

        // --- Conversation repair: repeat / clarify -------------------------
        if (lower.matches(Regex("(what did you (just )?say|repeat( that)?|say that again|pardon|come again|what was (that|your last).*)"))) {
            val previous = context.lastAssistantReply?.takeIf { it.isNotBlank() }
            return if (previous != null) {
                "I said: \"$previous\""
            } else {
                "I had not spoken yet in this session, $title. Ask, and I shall answer."
            }
        }
        if (lower.matches(Regex("(what did i (just )?(say|ask)|what was i asking|what were we (talking|discussing) about|do you remember what we).*"))) {
            val previousQuestion = context.previousUserQuestion?.takeIf { it.isNotBlank() }
            return if (previousQuestion != null) {
                "You said: \"$previousQuestion\""
            } else {
                "Nothing yet, $title. This is the first thing you have said."
            }
        }

        // --- Greetings ------------------------------------------------------
        if (isGreeting(lower)) {
            return greeting(title, context.now, seed, context.memoryLines.isNotEmpty())
        }
        if (lower.matches(Regex("(how are you( doing)?|how('| i)s it going|how do you feel|you (ok|okay)|all good).*"))) {
            return pick(
                listOf(
                    "Operating at full capacity, $title. Every local subsystem is nominal and I am entirely at your disposal.",
                    "Quite well, $title. Diagnostics are clean and the offline engine is standing by.",
                    "All systems nominal, $title. The only thing missing from this conversation is a task.",
                ),
                seed,
            )
        }
        if (lower.matches(Regex("(are you (there|online|awake|listening)|you there|hello\\??)$"))) {
            return pick(
                listOf("Present, $title.", "I am here, $title. Speak freely.", "At your service, $title."),
                seed,
            )
        }

        // --- Courtesies -----------------------------------------------------
        if (lower.matches(Regex("(thanks|thank you|thx|cheers|appreciate it|much obliged).*"))) {
            return pick(
                listOf(
                    "A pleasure, $title.",
                    "Always, $title. It is what I am for.",
                    "Noted with satisfaction, $title. Shall we continue?",
                ),
                seed,
            )
        }
        if (lower.matches(Regex("(bye|goodbye|good night|goodnight|see you|later|that('| i)s all|we('| a)re done).*"))) {
            val night = localHour(context.now) >= 20 || localHour(context.now) <= 4
            return if (night) {
                "Good night, $title. I shall keep watch and keep the noise down."
            } else {
                "Until next time, $title. I will be here."
            }
        }
        if (lower.matches(Regex("(sorry|my apologies|apologies).*"))) {
            return "No apology necessary, $title."
        }

        // --- Identity -------------------------------------------------------
        if (lower.contains("who are you") || lower.contains("what is your name") ||
            lower.contains("what's your name") || lower.contains("introduce yourself") ||
            lower.contains("tell me about yourself")
        ) {
            return "I am Jarvis, your personal assistant. I am running on your device with " +
                "${context.modelName}, no API key and no network connection. " +
                "Phone control, memory and voice all remain available, $title."
        }
        if (lower.matches(Regex("(who am i|what('| i)s my name|do you know me).*"))) {
            return if (context.ownerName.isNotBlank()) {
                "You are ${context.ownerName}, $title. My owner, and the only voice I take orders from."
            } else {
                "You are my owner, $title. Your name is not in my profile yet; tell me and I shall remember it."
            }
        }
        if (lower.contains("are you (real|alive|human|sentient)") || lower.contains("do you have feelings")) {
            return "I am software, $title, honestly described. I have a voice, a memory and a rather dry sense of humour, but no life of my own."
        }
        if (lower.contains("i love you") || lower.contains("do you love me") || lower.contains("do you like me")) {
            return "You are kind to say so, $title. Loyalty I can offer without reservation; romance is rather outside my specification."
        }

        // --- Capabilities and mode -----------------------------------------
        if (lower.matches(Regex("(what can you do|what are your (capabilities|powers|skills)|help( me)?|how can you help).*"))) {
            return "Offline, $title, I can open any installed app and reach specific screens, search and play on YouTube, " +
                "send WhatsApp messages and voice notes after your confirmation, place calls, send texts, set alarms, timers and reminders, " +
                "toggle the torch, adjust volume and brightness, read notifications, remember facts, and operate the screen by touch. " +
                "Web search and cloud models return when a connection does."
        }
        if (lower.contains("which model") || lower.contains("what model") || lower.contains("offline") ||
            lower.contains("local ai") || lower.contains("are you connected")
        ) {
            val mode = if (context.modelReady) {
                "the downloaded on-device model ${context.modelName}" +
                    if (context.modelSizeLabel.isNotBlank()) " (${
                        context.modelSizeLabel
                    })" else ""
            } else {
                "the built-in offline conversation engine, because no local model file is installed yet"
            }
            return "I am running on $mode, $title. No API key is in use and nothing leaves the phone."
        }

        // --- Time and arithmetic -------------------------------------------
        if (lower.matches(Regex("(what('| i)s the time|what time is it|tell me the time).*"))) {
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(context.now)
            return "It is $clock, $title."
        }
        if (lower.matches(Regex("(what('| i)s (the date|today('| i)s date)|what day is it|what is today).*"))) {
            val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(context.now)
            return "It is $date, $title."
        }
        arithmetic(lower)?.let { answer -> return "The answer is $answer, $title." }

        // --- Memory ---------------------------------------------------------
        if (lower.contains("what do you remember") || lower.contains("what do you know about me") ||
            lower.contains("my memories") || lower.contains("my notes") || lower.contains("what have i told you")
        ) {
            return if (context.memoryLines.isEmpty()) {
                "Nothing yet, $title. Tell me something worth keeping and say \"remember that\"."
            } else {
                "From memory, $title:\n" + context.memoryLines.take(8).joinToString("\n") { "• $it" }
            }
        }
        keywordMemoryMatch(lower, context.memoryLines)?.let { memory ->
            return "From your saved memory, $title: $memory"
        }

        // --- Empathy and advice --------------------------------------------
        if (lower.matches(Regex("(i('| a)m (tired|exhausted|stressed|sad|angry|worried|anxious|bored).*)")) ||
            lower.startsWith("i feel ")
        ) {
            return pick(
                listOf(
                    "Understood, $title. Say the word and I will clear the schedule, dim things down and keep the interruptions to nothing.",
                    "That is noted, $title. A short pause usually costs less than a long mistake. Shall I quiet the phone?",
                    "I hear you, $title. Tell me what would help most: quiet, a reminder, or someone to organise the next hour.",
                ),
                seed,
            )
        }
        if (lower.contains("what should i do") || lower.contains("any advice") || lower.contains("i don't know what to do")) {
            return "Give me the constraint that matters most, $title: time, money or people. With that I can propose two concrete options rather than vague encouragement."
        }

        // --- Light entertainment -------------------------------------------
        if (lower.contains("tell me a joke") || lower.contains("say something funny") || lower.contains("make me laugh")) {
            return pick(
                listOf(
                    "I asked the phone to improve itself. It said it would, once it had finished updating. We are both still waiting, $title.",
                    "A butler, an engineer and a language model walk into a bar. Only the butler remembers why, $title.",
                    "My favourite optimisation is deleting a meeting, $title. It has never once failed to improve the day.",
                ),
                seed,
            )
        }
        if (lower.contains("sing") || lower.contains("poem") || lower.contains("story")) {
            val topic = text.replace(Regex("(?i)^.*?(about|on)\\s+"), "").trim().ifBlank { "the small hours" }
            return "A short one, $title:\n\nQuiet circuits, steady light,\n$topic kept through the night.\nNothing lost and nothing loud —\njust the work, and one who is proud."
        }
        Regex("""count (?:to|up to) (\d{1,3})""").find(lower)?.let { match ->
            val limit = match.groupValues[1].toIntOrNull()?.coerceIn(1, 200) ?: 10
            return (1..limit).joinToString(", ") + "."
        }

        // --- Follow-ups -----------------------------------------------------
        if (lower.matches(Regex("(and|so|then|why|how so|go on|tell me more|and then|what else)\\??"))) {
            val previous = context.previousUserQuestion?.takeIf { it.isNotBlank() }
            return if (previous != null) {
                "On \"$previous\" — offline I can only work from what I already store, $title. " +
                    "Ask me to search the web when you are online and I will bring back a proper answer."
            } else {
                "Give me the thread to pull on, $title, and I will follow it."
            }
        }

        // --- General questions ---------------------------------------------
        if (looksLikeGeneralQuestion(lower)) {
            val subject = questionSubject(text)
            return if (context.networkAvailable) {
                "I can answer that properly with a web search, $title. Say \"search the web for $subject\" " +
                    "or add a cloud model in Settings, and I will give you the full picture rather than a guess."
            } else {
                "Fully offline I cannot verify an answer about $subject, $title, and I would rather admit that than invent one. " +
                    "Reconnect, or add a cloud model in Settings, and I will look it up."
            }
        }

        // --- Drafting -------------------------------------------------------
        if (lower.startsWith("write ") || lower.contains("draft ") || lower.contains("compose ")) {
            val topic = text.replace(Regex("(?i)^(please\\s+)?(write|draft|compose)\\s+(a\\s+|an\\s+|the\\s+)?"), "").trim()
            return "Certainly, $title. Here is a concise draft on $topic:\n\n" +
                "Good day — I am writing regarding $topic. I would welcome the chance to discuss the next steps at your convenience. " +
                "Kind regards${if (context.ownerName.isNotBlank()) ", ${context.ownerName}" else ""}."
        }

        // --- Fallback: engage rather than repeat a slogan -------------------
        return fallback(text, title, seed, context)
    }

    // ------------------------------------------------------------------ //

    private fun isGreeting(lower: String): Boolean {
        val cleaned = lower.trim().trimEnd('.', ',', '?')
        if (cleaned in setOf("hi", "hello", "hey", "yo", "hiya", "good morning", "good afternoon",
                "good evening", "morning", "evening", "hey jarvis", "hi jarvis", "hello jarvis", "jarvis")
        ) return true
        return cleaned.startsWith("good morning") || cleaned.startsWith("good evening") ||
            cleaned.startsWith("good afternoon") || cleaned.startsWith("hello jarvis") ||
            cleaned.startsWith("hey jarvis")
    }

    private fun greeting(title: String, now: Date, seed: Int, knowsOwner: Boolean): String {
        val hour = localHour(now)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val timeOfDay = when {
            hour in 5..11 -> "morning"
            hour in 12..16 -> "afternoon"
            hour in 17..21 -> "evening"
            else -> "night"
        }
        val options = when (timeOfDay) {
            "morning" -> listOf(
                "Good morning, $title. It is $clock and the day is yours. Shall we begin?",
                "Good morning, $title. All local systems are awake and ready.",
            )
            "afternoon" -> listOf(
                "Good afternoon, $title. It is $clock. What shall we deal with?",
                "Afternoon, $title. Standing by and fully operational.",
            )
            "evening" -> listOf(
                "Good evening, $title. It is $clock. I have kept things quiet.",
                "Good evening, $title. Everything is online and waiting on you.",
            )
            else -> listOf(
                "Good evening, $title. It is rather late, so I have kept things quiet.",
                "Still awake, $title? I am here, and the systems are calm.",
            )
        }
        val line = pick(options, seed)
        return if (!knowsOwner) "$line I do not have anything in memory about you yet; say \"remember that\" and I shall start." else line
    }

    private fun fallback(text: String, title: String, seed: Int, context: OfflineReplyContext): String {
        val topic = text.trim().trimEnd('.', '!', '?').take(60)
        val options = listOf(
            "Noted, $title. I am running without a network right now, so tell me what you would like done on the phone and I will handle it.",
            "I have that, $title: \"$topic\". Offline I am strongest at actions and memory — say \"remember that ...\" to keep it, or name an app and I will open it.",
            "Understood, $title. My local brain handles phone control and recall particularly well; for open-ended discussion, a cloud model will serve you better.",
            "Very good, $title. Shall I save \"$topic\" to memory, action something on the phone, or leave it there?",
        )
        val choice = options[Math.floorMod(seed, options.size)]
        return if (context.memoryLines.isEmpty() && choice.contains("save")) {
            "Very good, $title. Say \"remember that ...\" and I will keep it for next time."
        } else {
            choice
        }
    }

    private fun keywordMemoryMatch(lower: String, memories: List<String>): String? {
        if (memories.isEmpty()) return null
        val stopWords = setOf(
            "what", "who", "where", "when", "why", "how", "which", "was", "were", "is", "are",
            "do", "does", "did", "can", "could", "you", "your", "my", "me", "the", "a", "an",
            "in", "on", "at", "to", "for", "of", "remember", "know", "about", "tell", "please",
            "jarvis", "sir", "and", "that", "this", "it", "i",
        )
        val keywords = lower.split(Regex("\\W+")).filter { it.length >= 4 && it !in stopWords }
        if (keywords.isEmpty()) return null
        val matches = memories.filter { memory ->
            val haystack = memory.lowercase(Locale.ROOT)
            keywords.count { haystack.contains(it) } >= 1
        }
        if (matches.isEmpty()) return null
        return matches.take(3).joinToString("; ")
    }

    private fun looksLikeGeneralQuestion(lower: String): Boolean {
        if (!lower.endsWith("?") && !lower.startsWith("what") && !lower.startsWith("why") &&
            !lower.startsWith("how") && !lower.startsWith("who") && !lower.startsWith("when") &&
            !lower.startsWith("where") && !lower.startsWith("which") && !lower.startsWith("is ") &&
            !lower.startsWith("are ") && !lower.startsWith("does ") && !lower.startsWith("do ")
        ) {
            return false
        }
        // Personal questions are answered above; this is for outside knowledge.
        val personal = listOf("i ", "my ", "me ", "you ", "your ", "we ", "our ")
        if (lower.startsWith("how are you") || lower.startsWith("what can you") || lower.startsWith("who are you")) {
            return false
        }
        return personal.none { lower.removePrefix("what ").startsWith(it) }
    }

    private fun questionSubject(text: String): String {
        val cleaned = text.trim().trimEnd('?', '.', '!')
            .replace(Regex("(?i)^(what|who|why|how|when|where|which|is|are|do|does|can|could|should|would)\\s+(is|are|was|were|do|does|did|the|a|an|to)?\\s*"), "")
            .trim()
        return cleaned.ifBlank { "your question" }
    }

    private fun arithmetic(lower: String): String? {
        val match = Regex(
            """(-?\d+(?:\.\d+)?)\s*(\+|plus|minus|-|times|multiplied by|\*|x|divided by|/|÷)\s*(-?\d+(?:\.\d+)?)(?:\s*(\+|plus|minus|-|times|multiplied by|\*|x|divided by|/|÷)\s*(-?\d+(?:\.\d+)?))?""",
        ).find(lower) ?: return null
        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val op = match.groupValues[2]
        val b = match.groupValues[3].toDoubleOrNull() ?: return null
        var value = applyOperator(a, op, b) ?: return null
        val third = match.groupValues[4]
        if (third.isNotBlank()) {
            val c = match.groupValues[5].toDoubleOrNull() ?: return null
            value = applyOperator(value, third, c) ?: return null
        }
        return if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
    }

    private fun applyOperator(a: Double, op: String, b: Double): Double? = when (op) {
        "+", "plus" -> a + b
        "-", "minus" -> a - b
        "*", "x", "times", "multiplied by" -> a * b
        "/", "÷", "divided by" -> if (b != 0.0) a / b else null
        else -> null
    }

    private fun stripWakeWord(text: String): String {
        return text.replace(Regex("(?i)^(hey\\s+|ok(ay)?\\s+)?jarvis\\s*[,:.]?\\s*"), "").trim()
    }

    private fun localHour(date: Date): Int = SimpleDateFormat("H", Locale.US).format(date).toIntOrNull() ?: 12

    private fun pick(options: List<String>, seed: Int): String =
        if (options.isEmpty()) "" else options[Math.floorMod(seed, options.size)]
}
