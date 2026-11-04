package com.jarvis.assistant.settings

import android.content.Context
import com.jarvis.assistant.memory.MemoryEntity
import com.jarvis.assistant.memory.MemoryRepository
import com.jarvis.assistant.memory.SkillDefinition
import com.jarvis.assistant.memory.SkillRepository
import com.jarvis.assistant.memory.SkillStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MasterJarvisBackup(
    val formatVersion: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "2.0.0",
    val profile: BackupProfile,
    val voice: BackupVoice,
    val skills: List<BackupSkill>,
    val memories: List<BackupMemory>,
    val facts: Map<String, String>,
    val conversations: List<BackupConversation> = emptyList(),
    val messages: List<BackupMessage> = emptyList(),
)

@Serializable
data class BackupConversation(
    val id: Long,
    val startedAt: Long,
    val updatedAt: Long,
    val title: String = "",
)

@Serializable
data class BackupMessage(
    val id: Long,
    val conversationId: Long,
    val author: String,
    val text: String,
    val providerName: String? = null,
    val createdAt: Long = 0L,
)

@Serializable
data class BackupProfile(
    val name: String,
    val title: String,
    val occupation: String,
    val tone: String,
    val developer: String = "Octavian",
)

@Serializable
data class BackupVoice(
    val provider: String,
    val speed: Float,
    val fallbackToSystem: Boolean,
    val soundFxEnabled: Boolean = true,
    val language: String = "en-US",
)

@Serializable
data class BackupSkill(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val steps: List<SkillStep>,
    val enabled: Boolean = true,
)

@Serializable
data class BackupMemory(
    val text: String,
    val type: String,
    val importance: Int,
    val createdAt: Long,
)

class BackupRestoreRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository,
    private val skillRepository: SkillRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun createMasterBackup(): String = withContext(Dispatchers.IO) {
        val currentSettings = settingsRepository.settings.first()
        val allSkills = skillRepository.getSkills()
        val rawMemories = memoryRepository.exportJson()
        val decodedMemories = json.decodeFromString<MemoryRepository.MemoryExport>(rawMemories)

        val factsMap = decodedMemories.facts.associate { it.key to it.value }

        val backup = MasterJarvisBackup(
            profile = BackupProfile(
                name = currentSettings.profile.name,
                title = currentSettings.profile.title,
                occupation = currentSettings.profile.occupation,
                tone = currentSettings.persona.tone,
                developer = "Octavian",
            ),
            voice = BackupVoice(
                provider = currentSettings.voice.provider.name,
                speed = currentSettings.voice.speed,
                fallbackToSystem = currentSettings.voice.fallbackToSystem,
                soundFxEnabled = true,
                language = currentSettings.voice.language,
            ),
            skills = allSkills.map { s ->
                BackupSkill(
                    name = s.name,
                    description = s.description,
                    triggers = s.triggers,
                    steps = s.steps,
                    enabled = s.enabled,
                )
            },
            memories = decodedMemories.memories.map { m ->
                BackupMemory(
                    text = m.text,
                    type = m.type,
                    importance = m.importance,
                    createdAt = m.createdAt,
                )
            },
            facts = factsMap,
            conversations = decodedMemories.conversations.map { c ->
                BackupConversation(c.id, c.startedAt, c.updatedAt, c.title)
            },
            messages = decodedMemories.messages.map { m ->
                BackupMessage(m.id, m.conversationId, m.author, m.text, m.providerName, m.createdAt)
            },
        )

        json.encodeToString(backup)
    }

    suspend fun restoreMasterBackup(backupJson: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = json.decodeFromString<MasterJarvisBackup>(backupJson)

            // 1. Restore Profile
            settingsRepository.saveProfile(
                OwnerProfile(
                    name = backup.profile.name,
                    title = backup.profile.title,
                    occupation = backup.profile.occupation,
                    preferences = "",
                ),
            )

            // 2. Restore Skills
            var skillsRestored = 0
            backup.skills.forEach { sk ->
                skillRepository.saveSkill(
                    name = sk.name,
                    description = sk.description,
                    triggers = sk.triggers,
                    steps = sk.steps,
                )
                skillsRestored++
            }

            // 3. Restore Memories & Facts
            var memoriesRestored = 0
            backup.facts.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    memoryRepository.upsertFact(key, value, "backup_restore")
                }
            }
            // Always ensure creator identity
            memoryRepository.ensureCreatorIdentity()

            backup.memories.forEach { m ->
                val added = memoryRepository.remember(
                    text = m.text,
                    type = m.type,
                    importance = m.importance,
                    source = "backup_restore",
                )
                if (added) memoriesRestored++
            }

            // 4. Restore Chat Conversations & Messages
            var messagesRestored = 0
            if (backup.conversations.isNotEmpty() || backup.messages.isNotEmpty()) {
                val exportFormat = MemoryRepository.MemoryExport(
                    version = 3,
                    exportedAt = System.currentTimeMillis(),
                    conversations = backup.conversations.map {
                        MemoryRepository.ConversationDto(it.id, it.startedAt, it.updatedAt, it.title)
                    },
                    messages = backup.messages.map {
                        MemoryRepository.MessageDto(it.id, it.conversationId, it.author, it.text, it.providerName, it.createdAt)
                    },
                )
                memoryRepository.importJson(json.encodeToString(exportFormat))
                messagesRestored = backup.messages.size
            }

            "Backup successfully restored: $memoriesRestored memories, $messagesRestored chats, and $skillsRestored skills loaded."
        }
    }

    // Google Login Real Chat Persistence & Sync Vault --------------------------

    private fun googleChatSyncDir(): java.io.File {
        val dir = java.io.File(context.filesDir, "google_chat_sync")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun googleChatVaultDir(): java.io.File {
        val dir = java.io.File(context.noBackupFilesDir, "google_chat_vault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeEmail(email: String): String =
        email.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifBlank { "anonymous" }

    suspend fun saveGoogleAccountChatBackup(email: String): Boolean = withContext(Dispatchers.IO) {
        if (email.isBlank()) return@withContext false
        runCatching {
            val fileName = "chats_${sanitizeEmail(email)}.json"
            val primaryFile = java.io.File(googleChatSyncDir(), fileName)
            val vaultFile = java.io.File(googleChatVaultDir(), fileName)

            val currentExportJson = memoryRepository.exportJson()
            val currentExport = runCatching {
                json.decodeFromString<MemoryRepository.MemoryExport>(currentExportJson)
            }.getOrNull()

            // Merge with any previously saved vault for this Google account so earlier chats are never overwritten
            val existingRaw = when {
                primaryFile.exists() && primaryFile.length() > 0L -> runCatching { primaryFile.readText() }.getOrNull()
                vaultFile.exists() && vaultFile.length() > 0L -> runCatching { vaultFile.readText() }.getOrNull()
                else -> null
            }
            val existingExport = existingRaw?.let {
                runCatching { json.decodeFromString<MemoryRepository.MemoryExport>(it) }.getOrNull()
            }

            val finalJson = if (currentExport != null && existingExport != null) {
                val mergedConvs = (existingExport.conversations + currentExport.conversations)
                    .distinctBy { "${it.startedAt}:${it.title}" }
                val mergedMessages = (existingExport.messages + currentExport.messages)
                    .filter { it.text.isNotBlank() }
                    .distinctBy { "${it.author}:${it.text.trim()}:${it.createdAt / 1000}" }
                    .sortedBy { it.createdAt }
                val mergedMemories = (existingExport.memories + currentExport.memories)
                    .distinctBy { it.text.trim().lowercase() }
                val mergedFacts = (existingExport.facts + currentExport.facts)
                    .associateBy { it.key }
                    .values
                    .toList()
                val mergedSkills = (existingExport.skills + currentExport.skills)
                    .distinctBy { it.name.trim().lowercase() }
                json.encodeToString(
                    MemoryRepository.MemoryExport(
                        version = currentExport.version,
                        exportedAt = System.currentTimeMillis(),
                        facts = mergedFacts,
                        memories = mergedMemories,
                        skills = mergedSkills,
                        conversations = mergedConvs,
                        messages = mergedMessages,
                    ),
                )
            } else {
                currentExportJson
            }

            primaryFile.writeText(finalJson)
            runCatching { vaultFile.writeText(finalJson) }
            true
        }.getOrDefault(false)
    }

    suspend fun restoreGoogleAccountChatBackup(email: String): Int = withContext(Dispatchers.IO) {
        if (email.isBlank()) return@withContext 0
        runCatching {
            val fileName = "chats_${sanitizeEmail(email)}.json"
            val primaryFile = java.io.File(googleChatSyncDir(), fileName)
            val vaultFile = java.io.File(googleChatVaultDir(), fileName)
            val sourceFile = when {
                primaryFile.exists() && primaryFile.length() > 0L -> primaryFile
                vaultFile.exists() && vaultFile.length() > 0L -> vaultFile
                else -> return@withContext 0
            }
            val raw = sourceFile.readText()
            memoryRepository.importJson(raw)
            // Keep both locations synced after restore
            saveGoogleAccountChatBackup(email)
            json.decodeFromString<MemoryRepository.MemoryExport>(raw).messages.size
        }.getOrDefault(0)
    }
}
