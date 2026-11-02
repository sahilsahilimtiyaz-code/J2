package com.jarvis.assistant.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One durable thing Jarvis knows about the owner.
 *
 * The primary key is stored as `rowid` because the FTS4 table mirrors this
 * table as external content; Room's FTS integration requires that column name.
 */
@Entity(
    tableName = "memories",
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val id: Long = 0L,
    val text: String,
    val fingerprint: String,
    val type: String = MemoryType.FACT,
    val importance: Int = 3,
    val source: String = "chat",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
    val useCount: Int = 0,
)

/**
 * Full-text index over [MemoryEntity.text]. Room keeps it in sync with the
 * content table through generated triggers, so inserts and deletes never need a
 * second write.
 */
@Fts4(contentEntity = MemoryEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "memories_fts")
data class MemoryFts(
    val text: String,
)

/** Small, always-included facts: the owner's name, title, occupation, preferences. */
@Entity(tableName = "profile_facts")
data class ProfileFactEntity(
    @PrimaryKey val key: String,
    val value: String,
    val source: String = "profile",
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A conversation groups the messages of one continuous session. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val summary: String = "",
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"])],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    /** "user" or "jarvis". */
    val author: String,
    val text: String,
    val providerName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Compact summary of a closed conversation, kept out of the system prompt. */
@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Declarative, replayable multi-step routines. Used by Phase 7. */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val triggers: String,
    val stepsJson: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Local action log: what Jarvis did, when, and with which tool. */
@Entity(tableName = "action_log")
data class ActionLogEntity(
    @PrimaryKey val id: String,
    val toolName: String,
    val label: String,
    val detail: String,
    val success: Boolean,
    val timestamp: Long,
)

object MemoryType {
    const val FACT = "fact"
    const val PREFERENCE = "preference"
    const val PERSON = "person"
    const val PROJECT = "project"
    const val ROUTINE = "routine"
    const val LESSON = "lesson"
}
