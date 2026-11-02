package com.jarvis.assistant.memory

import android.content.Context
import com.jarvis.assistant.settings.AppSettings
import com.jarvis.assistant.settings.SettingsRepository
import com.jarvis.assistant.brain.LlmEvent
import com.jarvis.assistant.brain.LlmMessage
import com.jarvis.assistant.brain.ChatRequest
import com.jarvis.assistant.brain.ProviderConfig
import com.jarvis.assistant.brain.ProviderException
import com.jarvis.assistant.brain.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The background memory loop: reads a conversation, asks the configured model for
 * durable facts, and writes them into the memory table.
 *
 * It is deliberately conservative: only facts that will still be true later, at
 * most six per run, and never anything that looks like a secret.
 */
class MemoryExtractor(
    private val context: Context,
    private val registry: ProviderRegistry,
    private val memory: MemoryRepository,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun extractFromLatestConversation(): Int = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        val providers = resolveProviders(settings)
        if (providers.isEmpty()) return@withContext 0

        val conversationId = memory.latestConversationId() ?: return@withContext 0
        val messages = memory.messagesAfter(conversationId, 0L)
        if (messages.size < 2) return@withContext 0

        val transcript = messages.joinToString(separator = "\n") { message ->
            val who = if (message.author == "user") "Owner" else "Jarvis"
            "$who: ${message.text.take(1200)}"
        }
        if (transcript.isBlank()) return@withContext 0

        val known = memory.allMemories().take(40).joinToString("\n") { "- ${it.text}" }
        val prompt = buildString {
            append("Extract durable personal facts from the conversation below. ")
            append("Keep only information that will still be true later: names, relationships, ")
            append("preferences, routines, projects, health or work context the owner stated. ")
            append("Do not store secrets, passwords, card numbers, one-time codes or API keys. ")
            append("Do not store transient details such as the current time or a one-off question. ")
            append("Never invent anything. Skip anything already known.\n\n")
            if (known.isNotBlank()) {
                append("Already known:\n")
                append(known)
                append("\n\n")
            }
            append("Conversation:\n")
            append(transcript.take(12000))
            append("\n\n")
            append("Reply with ONLY a JSON array, at most six items, each ")
            append("{\"text\":\"...\",\"type\":\"fact|preference|person|project|routine\",")
            append("\"importance\":1-5}. Reply [] when there is nothing durable.")
        }

        val answer = streamCompletion(
            providers = providers,
            system = "You are a precise memory extractor. You answer with JSON only.",
            user = prompt,
        ) ?: return@withContext 0

        val items = parseItems(answer)
        var saved = 0
        items.take(6).forEach { item ->
            if (item.text.length < 3 || item.text.length > 400) return@forEach
            if (looksLikeSecret(item.text)) return@forEach
            if (memory.remember(item.text, item.type, item.importance, source = "extraction")) {
                saved++
            }
        }
        if (messages.isNotEmpty()) {
            val summary = summarise(items, messages)
            if (summary.isNotBlank()) memory.addSummary(conversationId, summary)
        }
        saved
    }

    private suspend fun streamCompletion(
        providers: List<ProviderConfig>,
        system: String,
        user: String,
    ): String? {
        var lastError: String? = null
        providers.forEach { provider ->
            val text = StringBuilder()
            try {
                registry.stream(
                    ChatRequest(
                        messages = listOf(LlmMessage.system(system), LlmMessage.user(user)),
                        tools = emptyList(),
                        temperature = 0.2,
                        maxTokens = 700,
                    ),
                    provider,
                ).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> text.append(event.text)
                        is LlmEvent.Error -> throw ProviderException(event.message)
                        LlmEvent.Done, is LlmEvent.ToolCallReady -> Unit
                    }
                }
                if (text.isNotBlank()) return text.toString()
                lastError = "${provider.name} returned an empty reply."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${provider.name}: ${e.message?.take(200)}"
            }
        }
        if (lastError != null) {
            // Extraction is best-effort: a failed run must never crash a turn or a
            // worker, and the next run retries.
            android.util.Log.w("JarvisMemory", "Extraction skipped: $lastError")
        }
        return null
    }

    private fun parseItems(raw: String): List<ExtractedItem> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val slice = raw.substring(start, end + 1)
        return runCatching { json.decodeFromString<List<ExtractedItem>>(slice) }
            .getOrDefault(emptyList())
            .map { item ->
                item.copy(
                    text = item.text.trim().replace(Regex("\\s+"), " "),
                    type = item.type.lowercase().takeIf { it in VALID_TYPES } ?: MemoryType.FACT,
                    importance = item.importance.coerceIn(1, 5),
                )
            }
            .filter { it.text.isNotBlank() }
    }

    private fun summarise(items: List<ExtractedItem>, messages: List<MessageEntity>): String {
        if (items.isEmpty()) return ""
        val opening = messages.firstOrNull { it.author == "user" }?.text?.take(90).orEmpty()
        return buildString {
            append("Conversation")
            if (opening.isNotBlank()) append(" about \"$opening\"")
            append(": ")
            append(items.joinToString("; ") { it.text })
        }
    }

    private fun looksLikeSecret(text: String): Boolean {
        val lower = text.lowercase()
        if (SECRET_WORDS.any { lower.contains(it) }) return true
        // 12+ digit runs, or anything with an obvious key pattern.
        return Regex("\\d{12,}").containsMatchIn(text) ||
            Regex("\\b(sk|pk|ghp|AIza)[-_A-Za-z0-9]{12,}").containsMatchIn(text)
    }

    private suspend fun resolveProviders(settings: AppSettings): List<ProviderConfig> {
        val ordered = buildList {
            settings.defaultProvider?.let { add(it) }
            addAll(settings.providers.filter { it.id != settings.defaultProvider?.id })
        }
        return ordered.mapNotNull { provider ->
            if (!provider.enabled || !provider.hasSavedKey) return@mapNotNull null
            val key = runCatching { settingsRepository.decryptApiKey(provider) }.getOrNull()
            if (key.isNullOrBlank()) return@mapNotNull null
            ProviderConfig(
                id = provider.id,
                name = provider.name,
                kind = provider.kind,
                baseUrl = provider.baseUrl.trim().trimEnd('/'),
                model = provider.model.trim(),
                apiKey = key,
            )
        }
    }

    @Serializable
    data class ExtractedItem(
        val text: String = "",
        val type: String = MemoryType.FACT,
        val importance: Int = 3,
    )

    private companion object {
        val VALID_TYPES = setOf(
            MemoryType.FACT,
            MemoryType.PREFERENCE,
            MemoryType.PERSON,
            MemoryType.PROJECT,
            MemoryType.ROUTINE,
            MemoryType.LESSON,
        )
        val SECRET_WORDS = listOf(
            "password",
            "passcode",
            "pin code",
            "card number",
            "cvv",
            "one-time code",
            "otp",
            "api key",
            "secret key",
            "seed phrase",
        )
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
