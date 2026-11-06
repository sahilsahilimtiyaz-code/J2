package com.jarvis.assistant.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jarvis.assistant.service.JarvisAlarmReceiver
import com.jarvis.assistant.service.JarvisIncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        fun fromString(value: String): TaskPriority = when (value.lowercase(Locale.ROOT).trim()) {
            "high", "urgent", "p1", "critical" -> HIGH
            "low", "minor", "p3" -> LOW
            else -> MEDIUM
        }
    }
}

@Serializable
enum class TaskRecurrence {
    NONE,
    DAILY,
    WEEKLY;

    companion object {
        fun fromString(value: String): TaskRecurrence = when (value.lowercase(Locale.ROOT).trim()) {
            "daily", "day", "everyday", "each day" -> DAILY
            "weekly", "week", "every week" -> WEEKLY
            else -> NONE
        }
    }
}

@Serializable
data class ScheduledTask(
    val id: String,
    val title: String,
    val description: String = "",
    val mode: String = JarvisOperatingMode.DEFAULT.id,
    val triggerTimeEpochMs: Long,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val recurrence: TaskRecurrence = TaskRecurrence.NONE,
    val isCompleted: Boolean = false,
    val actionCommand: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val formattedTime: String
        get() = SimpleDateFormat("EEE, d MMM 'at' h:mm a", Locale.UK).format(Date(triggerTimeEpochMs))

    val modeEnum: JarvisOperatingMode
        get() = JarvisOperatingMode.fromId(mode)
}

/**
 * Thread-safe, persistent task scheduler and manager for Jarvis's Study, Research, Tasks, and Agentic modes.
 */
class ScheduledTaskManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val file = File(context.filesDir, "scheduled_tasks.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    init {
        scope.launch {
            loadTasks()
        }
    }

    private suspend fun loadTasks() = mutex.withLock {
        if (!file.exists()) {
            _tasks.value = emptyList()
            return@withLock
        }
        val loaded = runCatching {
            json.decodeFromString<List<ScheduledTask>>(file.readText())
        }.getOrDefault(emptyList())
        _tasks.value = loaded.sortedBy { it.triggerTimeEpochMs }
    }

    private suspend fun persistTasks(list: List<ScheduledTask>) {
        _tasks.value = list.sortedBy { it.triggerTimeEpochMs }
        runCatching {
            val encoded = json.encodeToString(_tasks.value)
            file.writeText(encoded)
        }.onFailure { Log.e(TAG, "Failed to persist scheduled tasks: ${it.message}", it) }
    }

    suspend fun scheduleTask(
        title: String,
        description: String = "",
        mode: JarvisOperatingMode = JarvisOperatingMode.TASKS,
        triggerTimeEpochMs: Long,
        priority: TaskPriority = TaskPriority.MEDIUM,
        recurrence: TaskRecurrence = TaskRecurrence.NONE,
        actionCommand: String = "",
    ): ScheduledTask = mutex.withLock {
        val task = ScheduledTask(
            id = UUID.randomUUID().toString().take(8),
            title = title.trim(),
            description = description.trim(),
            mode = mode.id,
            triggerTimeEpochMs = triggerTimeEpochMs,
            priority = priority,
            recurrence = recurrence,
            isCompleted = false,
            actionCommand = actionCommand.trim(),
            createdAtEpochMs = System.currentTimeMillis(),
        )

        val updated = _tasks.value.toMutableList()
        updated.add(task)
        persistTasks(updated)

        armAlarm(task)
        Log.i(TAG, "Scheduled task \"${task.title}\" in ${task.mode} mode for ${task.formattedTime}")
        task
    }

    suspend fun completeTask(taskIdOrTitle: String): Boolean = mutex.withLock {
        val query = taskIdOrTitle.trim().lowercase(Locale.ROOT)
        val index = _tasks.value.indexOfFirst {
            it.id.equals(query, ignoreCase = true) || it.title.lowercase(Locale.ROOT).contains(query)
        }
        if (index < 0) return@withLock false

        val existing = _tasks.value[index]
        val updated = _tasks.value.toMutableList()

        if (existing.recurrence != TaskRecurrence.NONE) {
            val nextTime = when (existing.recurrence) {
                TaskRecurrence.DAILY -> existing.triggerTimeEpochMs + 24 * 60 * 60 * 1000L
                TaskRecurrence.WEEKLY -> existing.triggerTimeEpochMs + 7 * 24 * 60 * 60 * 1000L
                TaskRecurrence.NONE -> existing.triggerTimeEpochMs
            }
            val recurringTask = existing.copy(
                triggerTimeEpochMs = nextTime,
                isCompleted = false,
            )
            updated[index] = recurringTask
            armAlarm(recurringTask)
        } else {
            updated[index] = existing.copy(isCompleted = true)
            cancelAlarm(existing)
        }

        persistTasks(updated)
        true
    }

    suspend fun deleteTask(taskIdOrTitle: String): Boolean = mutex.withLock {
        val query = taskIdOrTitle.trim().lowercase(Locale.ROOT)
        val task = _tasks.value.firstOrNull {
            it.id.equals(query, ignoreCase = true) || it.title.lowercase(Locale.ROOT).contains(query)
        } ?: return@withLock false

        val updated = _tasks.value.filterNot { it.id == task.id }
        persistTasks(updated)
        cancelAlarm(task)
        true
    }

    fun listTasks(
        modeFilter: JarvisOperatingMode? = null,
        includeCompleted: Boolean = false,
    ): List<ScheduledTask> {
        val current = _tasks.value
        return current.filter { task ->
            (modeFilter == null || task.modeEnum == modeFilter) &&
                (includeCompleted || !task.isCompleted)
        }
    }

    private fun armAlarm(task: ScheduledTask) {
        if (task.triggerTimeEpochMs <= System.currentTimeMillis()) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, JarvisAlarmReceiver::class.java).apply {
            putExtra(JarvisIncomingCallActivity.EXTRA_TITLE, "${task.modeEnum.icon} ${task.title}")
            val msg = buildString {
                append("Scheduled ${task.modeEnum.label} Task: ${task.title}")
                if (task.description.isNotBlank()) append(". ${task.description}")
            }
            putExtra(JarvisIncomingCallActivity.EXTRA_MESSAGE, msg)
            putExtra("EXTRA_TASK_ID", task.id)
            putExtra("EXTRA_MODE", task.mode)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.triggerTimeEpochMs,
                    pendingIntent,
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    task.triggerTimeEpochMs,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, task.triggerTimeEpochMs, pendingIntent)
        }
    }

    private fun cancelAlarm(task: ScheduledTask) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, JarvisAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskManager"

        @Volatile
        private var instance: ScheduledTaskManager? = null

        fun get(context: Context): ScheduledTaskManager =
            instance ?: synchronized(this) {
                instance ?: ScheduledTaskManager(context.applicationContext).also { instance = it }
            }
    }
}
