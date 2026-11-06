package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.brain.ToolDefinition
import com.jarvis.assistant.core.JarvisCore
import com.jarvis.assistant.core.JarvisOperatingMode
import com.jarvis.assistant.core.ScheduledTaskManager
import com.jarvis.assistant.core.TaskPriority
import com.jarvis.assistant.core.TaskRecurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

class SetOperatingModeTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "set_operating_mode",
        description = "Switch Jarvis into a specialized operational mode: " +
            "'study' (concept breakdown, Feynman technique, active recall, distraction blocking), " +
            "'research' (deep multi-query investigation, academic synthesis, cross-verification), " +
            "'tasks' (high-velocity scheduling, priority matrix, deadlines), " +
            "'agentic' (maximum autonomy, tool chaining, self-healing UI actions), or " +
            "'default' (balanced standard assistant).",
        parameters = objectSchema(
            required = listOf("mode"),
            properties = mapOf(
                "mode" to stringProperty(
                    "Target mode to activate: 'study', 'research', 'tasks', 'agentic', or 'default'.",
                    enum = listOf("study", "research", "tasks", "agentic", "default"),
                ),
                "focus_topic" to stringProperty("Optional topic or subject for study/research (e.g. 'Quantum Computing', 'Organic Chemistry')."),
                "duration_minutes" to intProperty("Optional focus duration in minutes (e.g. 25 for Pomodoro)."),
            ),
        ),
    )

    override val label = "Set operating mode"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawMode = arguments.argString("mode") ?: "default"
        val targetMode = JarvisOperatingMode.fromId(rawMode)
        val focusTopic = arguments.argString("focus_topic")?.trim().orEmpty()
        val durationMinutes = arguments.argInt("duration_minutes")

        JarvisCore.get(context.appContext).setOperatingMode(targetMode)

        val detail = buildString {
            append("Activated ${targetMode.icon} ${targetMode.label} Mode (${targetMode.tagline}).")
            if (focusTopic.isNotBlank()) {
                append(" Primary focus: \"$focusTopic\".")
            }
            if (durationMinutes != null && durationMinutes > 0) {
                append(" Focus timer set for $durationMinutes minutes.")
            }
        }
        return ToolResult(true, detail)
    }
}

class ScheduleTaskTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "schedule_task",
        description = "Schedule a task, study session, research goal, or agentic mission for a specific time. " +
            "Automatically arms an exact Android reminder that alerts the owner when due.",
        parameters = objectSchema(
            required = listOf("title"),
            properties = mapOf(
                "title" to stringProperty("Task title or objective."),
                "description" to stringProperty("Detailed description, notes, or execution instructions."),
                "mode" to stringProperty(
                    "Associated operating mode: 'study', 'research', 'tasks', or 'agentic'. Defaults to 'tasks'.",
                    enum = listOf("study", "research", "tasks", "agentic", "default"),
                ),
                "minutes_from_now" to intProperty("Delay in minutes from now (e.g. 30)."),
                "hour" to intProperty("Target hour of day (0-23)."),
                "minute" to intProperty("Target minute (0-59)."),
                "priority" to stringProperty(
                    "Priority level: 'high', 'medium', or 'low'. Defaults to 'medium'.",
                    enum = listOf("high", "medium", "low"),
                ),
                "recurrence" to stringProperty(
                    "Optional recurrence: 'none', 'daily', or 'weekly'. Defaults to 'none'.",
                    enum = listOf("none", "daily", "weekly"),
                ),
                "action_command" to stringProperty("Optional autonomous voice command or tool query to run when triggered."),
            ),
        ),
    )

    override val label = "Schedule task"
    override val category = ToolCategory.TIME

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val title = arguments.argString("title")?.trim()
            ?: return ToolResult(false, "Specify the task title.")
        val description = arguments.argString("description")?.trim().orEmpty()
        val mode = JarvisOperatingMode.fromId(arguments.argString("mode") ?: "tasks")
        val priority = TaskPriority.fromString(arguments.argString("priority") ?: "medium")
        val recurrence = TaskRecurrence.fromString(arguments.argString("recurrence") ?: "none")
        val actionCommand = arguments.argString("action_command")?.trim().orEmpty()

        val minutes = arguments.argInt("minutes_from_now")
        val hour = arguments.argInt("hour")
        val minute = arguments.argInt("minute") ?: 0

        val triggerCal = Calendar.getInstance()
        if (minutes != null && minutes > 0) {
            triggerCal.add(Calendar.MINUTE, minutes)
        } else if (hour != null) {
            triggerCal.set(Calendar.HOUR_OF_DAY, hour)
            triggerCal.set(Calendar.MINUTE, minute)
            triggerCal.set(Calendar.SECOND, 0)
            if (triggerCal.before(Calendar.getInstance())) {
                triggerCal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            triggerCal.add(Calendar.HOUR_OF_DAY, 1)
        }

        val triggerTime = triggerCal.timeInMillis
        val taskManager = ScheduledTaskManager.get(context.appContext)

        val task = withContext(Dispatchers.IO) {
            taskManager.scheduleTask(
                title = title,
                description = description,
                mode = mode,
                triggerTimeEpochMs = triggerTime,
                priority = priority,
                recurrence = recurrence,
                actionCommand = actionCommand,
            )
        }

        val recurrenceNote = if (recurrence != TaskRecurrence.NONE) " (${recurrence.name.lowercase()} recurring)" else ""
        return ToolResult(
            true,
            "Scheduled task \"${task.title}\" for ${task.formattedTime} in ${mode.icon} ${mode.label} mode$recurrenceNote [ID: ${task.id}].",
        )
    }
}

class ListScheduledTasksTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "list_scheduled_tasks",
        description = "List scheduled tasks, study sessions, research goals, or agentic missions.",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "mode" to stringProperty(
                    "Optional mode filter: 'study', 'research', 'tasks', 'agentic', or 'all'.",
                    enum = listOf("study", "research", "tasks", "agentic", "all"),
                ),
                "include_completed" to booleanProperty("Set to true to include completed tasks. Defaults to false."),
            ),
        ),
    )

    override val label = "List scheduled tasks"
    override val category = ToolCategory.TIME

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawMode = arguments.argString("mode")
        val modeFilter = if (rawMode != null && rawMode != "all") JarvisOperatingMode.fromId(rawMode) else null
        val includeCompleted = arguments.argBoolean("include_completed") ?: false

        val taskManager = ScheduledTaskManager.get(context.appContext)
        val list = withContext(Dispatchers.IO) {
            taskManager.listTasks(modeFilter, includeCompleted)
        }

        if (list.isEmpty()) {
            val filterMsg = if (modeFilter != null) " in ${modeFilter.label} mode" else ""
            return ToolResult(true, "No active scheduled tasks found$filterMsg.")
        }

        val formatted = list.mapIndexed { idx, task ->
            val status = if (task.isCompleted) "[✓ Done]" else "[⏳ Pending]"
            val recurring = if (task.recurrence != TaskRecurrence.NONE) " (Repeats ${task.recurrence.name.lowercase()})" else ""
            val desc = if (task.description.isNotBlank()) " — ${task.description}" else ""
            "${idx + 1}. $status ${task.modeEnum.icon} \"${task.title}\"$desc\n   Due: ${task.formattedTime}$recurring · Priority: ${task.priority.name} · ID: ${task.id}"
        }.joinToString("\n\n")

        return ToolResult(true, "Active scheduled tasks (${list.size}):\n\n$formatted")
    }
}

class CompleteScheduledTaskTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "complete_scheduled_task",
        description = "Mark a scheduled task, study goal, or mission as completed.",
        parameters = objectSchema(
            required = listOf("task_id_or_title"),
            properties = mapOf(
                "task_id_or_title" to stringProperty("The ID or title of the task to mark as complete."),
            ),
        ),
    )

    override val label = "Complete scheduled task"
    override val category = ToolCategory.TIME

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = arguments.argString("task_id_or_title")?.trim()
            ?: return ToolResult(false, "Specify the task ID or title to complete.")

        val taskManager = ScheduledTaskManager.get(context.appContext)
        val success = withContext(Dispatchers.IO) {
            taskManager.completeTask(target)
        }

        return if (success) {
            ToolResult(true, "Marked task \"$target\" as completed.")
        } else {
            ToolResult(false, "Could not find an active task matching \"$target\".")
        }
    }
}
