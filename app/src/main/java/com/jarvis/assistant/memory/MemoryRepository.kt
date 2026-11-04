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
            val now = System.currentTimeMillis()
            runCatching { dao.pruneExpiredMemories(now) }
            val activeMemories = dao.allMemories()
                .filter { it.expiresAt <= 0L || it.expiresAt > now }

            if (query.isBlank()) {
                return@withContext activeMemories.take(limit)
            }

            val keywords = meaningfulTokens(query)
            // If the user asked a general recall question like "What do you remember about me?"
            // or "What are my stored notes?", all tokens are stop-words — return the stored memories directly!
            if (keywords.isEmpty()) {
                return@withContext activeMemories.take(limit)
            }

            val combined = LinkedHashMap<Long, MemoryEntity>()

            val match = ftsExpressionFromTokens(keywords)
            if (match != null) {
                val ftsHits = runCatching { dao.searchMemories(match, limit) }.getOrDefault(emptyList())
                    .filter { it.expiresAt <= 0L || it.expiresAt > now }
                for (hit in ftsHits) {
                    combined[hit.id] = hit
                }
            }

            // Also score all active memories by keyword matches across text, tags, and type
            // so partial words, tags ("note", "work"), and multi-word queries always match.
            if (combined.size < limit) {
                val scored = activeMemories
                    .mapNotNull { mem ->
                        if (combined.containsKey(mem.id)) return@mapNotNull null
                        val hay = "${mem.text} ${mem.tags} ${mem.type}".lowercase(Locale.UK)
                        val hits = keywords.count { kw -> hay.contains(kw) }
                        if (hits > 0) mem to (hits * 10 + mem.importance) else null
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }
                for (mem in scored) {
                    if (combined.size >= limit) break
                    combined[mem.id] = mem
                }
            }

            // Final fallback to literal substring LIKE scan
            if (combined.isEmpty()) {
                val likeHits = dao.searchMemoriesLike(query.trim(), limit)
                    .filter { it.expiresAt <= 0L || it.expiresAt > now }
                for (hit in likeHits) {
                    combined[hit.id] = hit
                }
            }

            val results = combined.values.take(limit)
            if (results.isNotEmpty()) {
                runCatching { dao.markUsed(results.map { it.id }, now) }
            }
            results
        }

    suspend fun searchConversationMessages(query: String, limit: Int = 8): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext dao.recentMessages(limit)
            dao.searchMessages(clean, limit)
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
        tags: String = "",
        expiresAt: Long = 0L,
    ): Boolean = withContext(Dispatchers.IO) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 3) return@withContext false
        val fingerprint = fingerprint(clean)
        if (fingerprint.isBlank()) return@withContext false
        val cleanTags = tags.split(",")
            .map { it.trim().lowercase(Locale.UK) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")

        val existing = dao.memoryByFingerprint(fingerprint)
        if (existing != null) {
            val mergedTags = (existing.tags.split(",") + cleanTags.split(","))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(",")
            dao.updateMemory(
                existing.copy(
                    text = clean,
                    type = type.takeIf { it.isNotBlank() } ?: existing.type,
                    importance = maxOf(existing.importance, importance.coerceIn(1, 5)),
                    lastUsedAt = System.currentTimeMillis(),
                    tags = mergedTags,
                    expiresAt = if (expiresAt > 0L) expiresAt else existing.expiresAt,
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
                tags = cleanTags,
                expiresAt = expiresAt.coerceAtLeast(0L),
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
                tags = memory.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(","),
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
     * The compact "what I know about my owner" block: always-on profile facts,
     * query-matched memories, AND recent/important stored memories and notes so
     * Jarvis never forgets saved notes or personal facts even on broad questions.
     */
    suspend fun promptContext(query: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        runCatching { dao.pruneExpiredMemories(now) }
        val factLines = dao.facts()
            .filter { it.value.isNotBlank() }
            .joinToString(separator = "\n") { "- ${it.key}: ${it.value}" }

        val matched = search(query, DEFAULT_RETRIEVAL)
        val matchedIds = matched.map { it.id }.toSet()
        val allActive = dao.allMemories()
            .filter { it.expiresAt <= 0L || it.expiresAt > now }
        val additionalStored = allActive
            .filterNot { it.id in matchedIds }
            .take(MAX_ALWAYS_INCLUDED_MEMORIES)

        fun formatEntry(it: MemoryEntity): String {
            val tagSuffix = if (it.tags.isNotBlank()) " [${it.tags}]" else ""
            return "- (${it.type}) ${it.text}$tagSuffix"
        }

        val matchedLines = matched.joinToString(separator = "\n", transform = ::formatEntry)
        val additionalLines = additionalStored.joinToString(separator = "\n", transform = ::formatEntry)

        buildString {
            append("Immutable core memory:\n")
            append("- developer: Octavian (Creator and Chief Architect of J.A.R.V.I.S.)\n")
            if (factLines.isNotBlank()) {
                append("Known profile facts:\n")
                append(factLines)
                append('\n')
            }
            if (matchedLines.isNotBlank()) {
                append("Relevant memories:\n")
                append(matchedLines)
                append('\n')
            }
            if (additionalLines.isNotBlank()) {
                append("Stored memories and notes:\n")
                append(additionalLines)
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
        val allConvs = dao.allConversations()
        val allMsgs = dao.allMessages()
        val export = MemoryExport(
            exportedAt = System.currentTimeMillis(),
            facts = dao.facts().map { FactDto(it.key, it.value, it.source) },
            memories = dao.allMemories().map {
                MemoryDto(
                    text = it.text,
                    type = it.type,
                    importance = it.importance,
                    source = it.source,
                    createdAt = it.createdAt,
                    tags = it.tags,
                    expiresAt = it.expiresAt,
                )
            },
            skills = dao.skills().map {
                SkillDto(it.name, it.description, it.triggers, it.stepsJson, it.enabled)
            },
            conversations = allConvs.map { c ->
                ConversationDto(
                    id = c.id,
                    startedAt = c.startedAt,
                    updatedAt = c.updatedAt,
                    title = c.title,
                )
            },
            messages = allMsgs.map { m ->
                MessageDto(
                    id = m.id,
                    conversationId = m.conversationId,
                    author = m.author,
                    text = m.text,
                    providerName = m.providerName,
                    createdAt = m.createdAt,
                )
            },
        )
        json.encodeToString(export)
    }

    /** Imports an export, merging by fingerprint. Returns how many memories were added. */
    suspend fun importJson(raw: String): Int = withContext(Dispatchers.IO) {
        val export = json.decodeFromString<MemoryExport>(raw)
        var added = 0
        export.facts.forEach { fact ->
            if (fact.key.isNotBlank() && fact.value.isNotBlank()) {
                upsertFact(fact.key, fact.value, fact.source)
            }
        }
        export.memories.forEach { memory ->
            if (
                remember(
                    text = memory.text,
                    type = memory.type,
                    importance = memory.importance,
                    source = memory.source,
                    tags = memory.tags,
                    expiresAt = memory.expiresAt,
                )
            ) {
                added++
            }
        }
        export.skills.forEach { skill ->
            runCatching {
                dao.insertSkill(
                    SkillEntity(
                        name = skill.name,
                        description = skill.description,
                        triggers = skill.triggers,
                        stepsJson = skill.stepsJson,
                        enabled = skill.enabled,
                    ),
                )
            }
        }
        // Restore conversations and messages if present
        if (export.conversations.isNotEmpty() || export.messages.isNotEmpty()) {
            val convIdMap = mutableMapOf<Long, Long>()
            export.conversations.forEach { c ->
                val existing = dao.conversationByStart(c.startedAt, c.title)
                val newId = existing?.id ?: dao.insertConversation(
                    ConversationEntity(
                        startedAt = c.startedAt,
                        updatedAt = c.updatedAt,
                        title = c.title,
                    ),
                )
                convIdMap[c.id] = newId
            }
            val fallbackConvId by lazy {
                convIdMap.values.firstOrNull() ?: dao.insertConversation(
                    ConversationEntity(
                        startedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        title = "Restored Google Chat History",
                    ),
                )
            }
            export.messages.sortedBy { it.createdAt }.forEach { m ->
                val targetConvId = convIdMap[m.conversationId] ?: fallbackConvId
                val duplicate = dao.messageExists(targetConvId, m.author, m.text, m.createdAt) > 0
                if (!duplicate && m.text.isNotBlank()) {
                    dao.insertMessage(
                        MessageEntity(
                            conversationId = targetConvId,
                            author = m.author,
                            text = m.text,
                            providerName = m.providerName,
                            createdAt = m.createdAt,
                        ),
                    )
                }
            }
        }
        added
    }

    private fun meaningfulTokens(query: String): List<String> =
        query.lowercase(Locale.UK)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in QUERY_STOP_WORDS }
            .distinct()
            .take(8)

    private fun ftsExpressionFromTokens(tokens: List<String>): String? {
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
        val version: Int = 3,
        val exportedAt: Long = 0L,
        val facts: List<FactDto> = emptyList(),
        val memories: List<MemoryDto> = emptyList(),
        val skills: List<SkillDto> = emptyList(),
        val conversations: List<ConversationDto> = emptyList(),
        val messages: List<MessageDto> = emptyList(),
    )

    @Serializable
    data class ConversationDto(
        val id: Long,
        val startedAt: Long,
        val updatedAt: Long,
        val title: String = "",
    )

    @Serializable
    data class MessageDto(
        val id: Long,
        val conversationId: Long,
        val author: String,
        val text: String,
        val providerName: String? = null,
        val createdAt: Long = 0L,
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
        val tags: String = "",
        val expiresAt: Long = 0L,
    )

    @Serializable
    data class SkillDto(
        val name: String,
        val description: String = "",
        val triggers: String,
        val stepsJson: String,
        val enabled: Boolean = true,
    )

    private companion object {
        const val DEFAULT_RETRIEVAL = 10
        const val MAX_ALWAYS_INCLUDED_MEMORIES = 15
        const val CONVERSATION_GAP_MS = 6 * 60 * 60 * 1000L

        val QUERY_STOP_WORDS = setOf(
            "what", "who", "where", "when", "why", "how", "which",
            "is", "are", "was", "were", "am", "be", "been",
            "do", "does", "did", "can", "could", "would", "should", "will",
            "have", "has", "had", "you", "your", "yours",
            "my", "me", "mine", "i", "we", "our", "us",
            "the", "a", "an", "in", "on", "at", "to", "for", "of", "with", "from", "by",
            "about", "remember", "remembered", "know", "known", "tell", "show", "read", "list",
            "stored", "saved", "save", "store", "keep", "kept",
            "memory", "memories", "note", "notes", "fact", "facts",
            "anything", "everything", "something", "all", "any",
            "jarvis", "please", "sir", "hey", "hi", "hello",
        )

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
        }
    }
}
