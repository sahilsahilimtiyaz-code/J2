package com.jarvis.assistant.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE rowid = :id")
    suspend fun memory(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun memoryByFingerprint(fingerprint: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE rowid = :id")
    suspend fun deleteMemory(id: Long)

    @Query("SELECT * FROM memories ORDER BY importance DESC, createdAt DESC")
    fun observeMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY importance DESC, createdAt DESC")
    suspend fun allMemories(): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun memoryCount(): Int

    /**
     * Full-text retrieval. The caller passes a MATCH expression built only from
     * sanitised keywords, never raw owner text.
     */
    @Query(
        "SELECT * FROM memories WHERE rowid IN " +
            "(SELECT rowid FROM memories_fts WHERE memories_fts MATCH :match) " +
            "ORDER BY importance DESC, lastUsedAt DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun searchMemories(match: String, limit: Int): List<MemoryEntity>

    @Query(
        "SELECT * FROM memories WHERE text LIKE '%' || :query || '%' " +
            "ORDER BY importance DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun searchMemoriesLike(query: String, limit: Int): List<MemoryEntity>

    @Query("UPDATE memories SET lastUsedAt = :now, useCount = useCount + 1 WHERE rowid IN (:ids)")
    suspend fun markUsed(ids: List<Long>, now: Long)

    @Query("DELETE FROM memories")
    suspend fun clearMemories()

    // Profile facts -----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFact(fact: ProfileFactEntity)

    @Query("SELECT * FROM profile_facts ORDER BY key ASC")
    fun observeFacts(): Flow<List<ProfileFactEntity>>

    @Query("SELECT * FROM profile_facts ORDER BY key ASC")
    suspend fun facts(): List<ProfileFactEntity>

    @Query("DELETE FROM profile_facts")
    suspend fun clearFacts()

    // Conversations -----------------------------------------------------------

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestConversation(): ConversationEntity?

    @Query("UPDATE conversations SET updatedAt = :now, title = :title WHERE id = :id")
    suspend fun touchConversation(id: Long, now: Long, title: String)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: Long): ConversationEntity?

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun messages(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :limit")
    fun observeRecentMessages(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :limit")
    suspend fun recentMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND id > :afterId ORDER BY id ASC")
    suspend fun messagesAfter(conversationId: Long, afterId: Long): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()

    // Summaries ---------------------------------------------------------------

    @Insert
    suspend fun insertSummary(summary: SummaryEntity)

    @Query("SELECT * FROM summaries ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentSummaries(limit: Int): List<SummaryEntity>

    @Query("DELETE FROM summaries")
    suspend fun clearSummaries()

    // Skills ------------------------------------------------------------------

    @Insert
    suspend fun insertSkill(skill: SkillEntity): Long

    @Query("SELECT * FROM skills ORDER BY createdAt DESC")
    fun observeSkills(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY createdAt DESC")
    suspend fun skills(): List<SkillEntity>

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteSkill(id: Long)

    @Query("DELETE FROM skills")
    suspend fun clearSkills()
}
