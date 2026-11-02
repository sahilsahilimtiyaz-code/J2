package com.jarvis.assistant.memory

import com.jarvis.assistant.settings.OwnerProfile
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything Jarvis remembers, backed by Room.
 *
 * Retrieval uses the FTS4 mirror of the memory table with sanitised keywords and
 * falls back to a LIKE scan when the MATCH expression is rejected. Profile
 * facts are always included in the prompt; free-form memories are included only
 * when they match the current request.
 */
class MemoryRepository(private val dao: MemoryDao) {

    val memories: Flow<List<MemoryEntity>> = dao.observeMemories()
    val facts: Flow<List<ProfileFactEntity>> = dao.observeFacts()
    val skills: Flow<List<SkillEntity>> = dao.observeSkills()

    // Reads -------------------------------------------------------------------

    suspend fun count(): Int = dao.memoryCount()

    suspend fun allMemories(): List<MemoryEntity> = dao.allMemories()

    suspend fun search(query: String, limit: Int = DEFAULT_RETRIEVAL): List<MemoryEntity> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext dao.allMemories().take(limit)

            val match = ftsExpression(query)
            if (match != null) {
                val hits = runCatching { dao.searchMemories(match, limit) }.getOrDefault(emptyList())
                if (hits.isNotEmpty()) {
                    dao.markUsed(hits.map { it.id }, System.currentTimeMillis())
                    return@withContext hits
                }
            }
            dao.searchMemoriesLike(query.trim(), limit)
        }

    // Writes ------------------------------------------------------------------

    /**
     * Stores a memory, merging into an existing identical one instead of
     * duplicating it. Returns true when a new row was created.
     */
    suspend fun remember(
        text: String,
        type: String = MemoryType.FACT,
        importance: Int = 3,
        source: String = "chat",
    ): Boolean = withContext(Dispatchers.IO) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 3) return@withContext false
        val fingerprint = fingerprint(clean)
        if (fingerprint.isBlank()) return@withContext false

        val existing = dao.memoryByFingerprint(fingerprint)
        if (existing != null) {
            dao.updateMemory(
                existing.copy(
                    text = clean,
                    importance = maxOf(existing.importance, importance.coerceIn(1, 5)),
                    lastUsedAt = System.currentTimeMillis(),
                ),
            )
            return@withContext false
        }

        dao.insertMemory(
            MemoryEntity(
                text = clean,
                fingerprint = fingerprint,
                type = type.takeIf { it.isNotBlank() } ?: MemoryType.FACT,
                importance = importance.coerceIn(1, 5),
                source = source,
            ),
        )
        true
    }

    suspend fun updateMemory(memory: MemoryEntity) = withContext(Dispatchers.IO) {
        dao.updateMemory(
            memory.copy(
                text = memory.text.trim(),
                fingerprint = fingerprint(memory.text),
                importance = memory.importance.coerceIn(1, 5),
            ),
        )
    }

    suspend fun deleteMemory(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteMemory(id)
    }

    suspend fun upsertFact(key: String, value: String, source: String = "profile") =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext
            dao.upsertFact(
                ProfileFactEntity(
                    key = key.trim().lowercase(Locale.UK),
                    value = value.trim(),
                    source = source,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

    /** Keeps the facts table in step with the onboarding profile and immutable creator identity. */
    suspend fun syncProfile(profile: OwnerProfile) {
        ensureCreatorIdentity()
        upsertFact("name", profile.name, "onboarding")
        upsertFact("title", profile.title, "onboarding")
        if (profile.occupation.isNotBlank()) upsertFact("occupation", profile.occupation, "onboarding")
        if (profile.preferences.isNotBlank()) {
            upsertFact("preferences", profile.preferences, "onboarding")
        }
    }

    /** Ensures Jarvis permanently remembers his developer and architect is Octavian. */
    suspend fun ensureCreatorIdentity() = withContext(Dispatchers.IO) {
        upsertFact("developer", "Octavian (Creator and Chief Architect of J.A.R.V.I.S.)", "core_immutable")
        remember(
            text = "My developer, creator, and chief architect is Octavian, no matter who uses this phone.",
            type = MemoryType.FACT,
            importance = 5,
            source = "core_immutable",
        )
    }

    // Prompt context ----------------------------------------------------------

    /**
     * The compact "what I know about my owner" block: always-on profile facts
     * plus the memories that match this request.
     */
    suspend fun promptContext(query: String): String = withContext(Dispatchers.IO) {
        val factLines = dao.facts()
            .filter { it.value.isNotBlank() }
            .joinToString(separator = "\n") { "- ${it.key}: ${it.value}" }
        val notes = search(query, DEFAULT_RETRIEVAL)
            .joinToString(separator = "\n") { "- ${it.text}" }
        buildString {
            append("Immutable core memory:\n")
            append("- developer: Octavian (Creator and Chief Architect of J.A.R.V.I.S.)\n")
            if (factLines.isNotBlank()) {
                append("Known profile facts:\n")
                append(factLines)
                append('\n')
            }
            if (notes.isNotBlank()) {
                append("Relevant memories:\n")
                append(notes)
            }
        }.trim()
    }

    // Conversations -----------------------------------------------------------

    suspend fun conversationForTurn(): Long = withContext(Dispatchers.IO) {
        val latest = dao.latestConversation()
        val now = System.currentTimeMillis()
        if (latest != null && now - latest.updatedAt < CONVERSATION_GAP_MS) {
            latest.id
        } else {
            dao.insertConversation(ConversationEntity(startedAt = now, updatedAt = now))
        }
    }

    suspend fun addMessage(
        conversationId: Long,
        author: String,
        text: String,
        providerName: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val id = dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                author = author,
                text = text,
                providerName = providerName,
            ),
        )
        val conversation = dao.conversation(conversationId)
        val title = when {
            !conversation?.title.isNullOrBlank() -> conversation!!.title
            author == "user" -> text.take(60)
            else -> conversation?.title.orEmpty()
        }
        dao.touchConversation(conversationId, System.currentTimeMillis(), title)
        id
    }

    /** Newest messages first, as stored. */
    suspend fun recentTranscript(limit: Int = 40): List<MessageEntity> = withContext(Dispatchers.IO) {
        dao.recentMessages(limit)
    }

    suspend fun messagesAfter(conversationId: Long, afterId: Long): List<MessageEntity> =
        withContext(Dispatchers.IO) { dao.messagesAfter(conversationId, afterId) }

    suspend fun latestConversationId(): Long? = withContext(Dispatchers.IO) {
        dao.latestConversation()?.id
    }

    suspend fun recentSummaries(limit: Int = 3): List<SummaryEntity> =
        withContext(Dispatchers.IO) { dao.recentSummaries(limit) }

    suspend fun addSummary(conversationId: Long, text: String) = withContext(Dispatchers.IO) {
        if (text.isNotBlank()) {
            dao.insertSummary(SummaryEntity(conversationId = conversationId, text = text.trim()))
        }
    }

    // Forget and transfer -----------------------------------------------------

    /**
     * Removes everything personal: facts, memories, conversations, summaries and
     * skills. The action log is deliberately kept, because the safety rules
     * require a record of what Jarvis did; the UI says so before it runs.
     */
    suspend fun forgetEverything() = withContext(Dispatchers.IO) {
        dao.clearMemories()
        dao.clearFacts()
        dao.clearMessages()
        dao.clearConversations()
        dao.clearSummaries()
        dao.clearSkills()
        ensureCreatorIdentity()
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val export = MemoryExport(
            exportedAt = System.currentTimeMillis(),
            facts = dao.facts().map { FactDto(it.key, it.value, it.source) },
            memories = dao.allMemories().map {
                MemoryDto(it.text, it.type, it.importance, it.source, it.createdAt)
            },
            skills = dao.skills().map {
                SkillDto(it.name, it.triggers, it.stepsJson, it.enabled)
            },
        )
        json.encodeToString(export)
    }

    /** Imports an export, merging by fingerprint. Returns how many memories were added. */
    suspend fun importJson(raw: String): Int = withContext(Dispatchers.IO) {
        val export = json.decodeFromString<MemoryExport>(raw)
        var added = 0
        export.facts.forEach { fact ->
            if (fact.key.isNotBlank() && fact.value.isNotBlank() && fact.source != "onboarding") {
                upsertFact(fact.key, fact.value, fact.source)
            }
        }
        export.memories.forEach { memory ->
            if (remember(memory.text, memory.type, memory.importance, memory.source)) added++
        }
        export.skills.forEach { skill ->
            runCatching {
                dao.insertSkill(
                    SkillEntity(
                        name = skill.name,
                        triggers = skill.triggers,
                        stepsJson = skill.stepsJson,
                        enabled = skill.enabled,
                    ),
                )
            }
        }
        added
    }

    private fun ftsExpression(query: String): String? {
        val tokens = query.lowercase(Locale.UK)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(8)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { "\"$it\"*" }
    }

    private fun fingerprint(text: String): String =
        text.lowercase(Locale.UK)
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    @Serializable
    data class MemoryExport(
        val version: Int = 1,
        val exportedAt: Long = 0L,
        val facts: List<FactDto> = emptyList(),
        val memories: List<MemoryDto> = emptyList(),
        val skills: List<SkillDto> = emptyList(),
    )

    @Serializable
    data class FactDto(val key: String, val value: String, val source: String = "import")

    @Serializable
    data class MemoryDto(
        val text: String,
        val type: String = MemoryType.FACT,
        val importance: Int = 3,
        val source: String = "import",
        val createdAt: Long = 0L,
    )

    @Serializable
    data class SkillDto(
        val name: String,
        val triggers: String,
        val stepsJson: String,
        val enabled: Boolean = true,
    )

    private companion object {
        const val DEFAULT_RETRIEVAL = 8
        const val CONVERSATION_GAP_MS = 6 * 60 * 60 * 1000L
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
        }
    }
}
