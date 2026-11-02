package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.memory.ActionLogDao
import com.jarvis.assistant.memory.ActionLogEntity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ActionLogEntry(
    val id: String,
    val toolName: String,
    val label: String,
    val detail: String,
    val success: Boolean,
    val timestamp: Long,
)

/**
 * The local action log required by the safety rules: what Jarvis did, when and
 * with which tool. It lives in the Room database now, so it survives restarts
 * alongside memory, and any log written by the earlier JSON-file version is
 * imported once on first launch.
 */
class ActionLogStore(
    context: Context,
    private val dao: ActionLogDao,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private val _entries = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val entries: StateFlow<List<ActionLogEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            runCatching { migrateLegacyFile() }
            dao.observe(MAX_ENTRIES).collect { rows ->
                _entries.value = rows.map { it.toEntry() }
            }
        }
    }

    suspend fun load() {
        // The flow in init already keeps this current; this also warms the cache
        // for callers that read immediately after startup.
        val rows = withContext(Dispatchers.IO) { dao.recent(MAX_ENTRIES) }
        if (_entries.value.isEmpty()) _entries.value = rows.map { it.toEntry() }
    }

    suspend fun record(entry: ActionLogEntry) {
        dao.insert(entry.toEntity())
        _entries.value = (listOf(entry) + _entries.value)
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
            .take(MAX_ENTRIES)
    }

    suspend fun clear() {
        dao.clear()
        _entries.value = emptyList()
    }

    private suspend fun migrateLegacyFile() = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, "action_log.json")
        if (!file.exists()) return@withContext
        if (dao.count() == 0) {
            val legacy = runCatching {
                json.decodeFromString(ListSerializer(ActionLogEntry.serializer()), file.readText())
            }.getOrDefault(emptyList())
            legacy.forEach { entry -> runCatching { dao.insert(entry.toEntity()) } }
        }
        runCatching { file.renameTo(File(appContext.filesDir, "action_log.json.migrated")) }
    }

    private fun ActionLogEntry.toEntity(): ActionLogEntity = ActionLogEntity(
        id = id,
        toolName = toolName,
        label = label,
        detail = detail,
        success = success,
        timestamp = timestamp,
    )

    private fun ActionLogEntity.toEntry(): ActionLogEntry = ActionLogEntry(
        id = id,
        toolName = toolName,
        label = label,
        detail = detail,
        success = success,
        timestamp = timestamp,
    )

    private companion object {
        const val MAX_ENTRIES = 200
        val json = Json { ignoreUnknownKeys = true }
    }
}
