package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.assistant.brain.ToolDefinition
import kotlinx.serialization.json.JsonObject

class SetTimerTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "set_timer",
        description = "Start a countdown timer through the phone's clock app, e.g. " +
            "\"ten minutes\" becomes 600 seconds.",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "seconds" to intProperty("Timer length in seconds."),
                "minutes" to intProperty("Timer length in minutes, if seconds is not given."),
                "label" to stringProperty("Optional label for the timer."),
            ),
        ),
    )

    override val label = "Set timer"
    override val category = ToolCategory.TIME

    override fun availability(context: Context): ToolAvailability =
        if (resolves(context, Intent(AlarmClock.ACTION_SET_TIMER))) {
            ToolAvailability("Clock app", true)
        } else {
            ToolAvailability("No clock app", false)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val seconds = when {
            arguments.argInt("seconds") != null -> arguments.argInt("seconds")!!
            arguments.argInt("minutes") != null -> arguments.argInt("minutes")!! * 60
            else -> return ToolResult(false, "Tell me how long the timer should run.")
        }
        if (seconds <= 0) return ToolResult(false, "A timer needs a positive length.")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            arguments.argString("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!resolves(context.appContext, intent)) {
            return ToolResult(false, "I could not find a clock app that accepts timers.")
        }

        return runCatching { context.appContext.startActivity(intent) }.fold(
            onSuccess = {
                ToolResult(true, "Timer set for ${describeDuration(seconds)}.")
            },
            onFailure = { error ->
                ToolResult(false, "The clock app refused the timer: ${error.message}.")
            },
        )
    }
}

class SetAlarmTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "set_alarm",
        description = "Set an alarm on the phone at a 24-hour time.",
        parameters = objectSchema(
            required = listOf("hour", "minute"),
            properties = mapOf(
                "hour" to intProperty("Hour in 24-hour time, 0 to 23."),
                "minute" to intProperty("Minute, 0 to 59."),
                "label" to stringProperty("Optional label for the alarm."),
            ),
        ),
    )

    override val label = "Set alarm"
    override val category = ToolCategory.TIME

    override fun availability(context: Context): ToolAvailability =
        if (resolves(context, Intent(AlarmClock.ACTION_SET_ALARM))) {
            ToolAvailability("Clock app", true)
        } else {
            ToolAvailability("No clock app", false)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val hour = arguments.argInt("hour")
            ?: return ToolResult(false, "Tell me what time the alarm should ring.")
        val minute = arguments.argInt("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult(false, "That time is not valid. Use 0 to 23 for the hour.")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            arguments.argString("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!resolves(context.appContext, intent)) {
            return ToolResult(false, "I could not find a clock app that accepts alarms.")
        }

        val formatted = "%02d:%02d".format(hour, minute)
        return runCatching { context.appContext.startActivity(intent) }.fold(
            onSuccess = { ToolResult(true, "Alarm set for $formatted.") },
            onFailure = { error ->
                ToolResult(false, "The clock app refused the alarm: ${error.message}.")
            },
        )
    }
}

internal fun resolves(context: Context, intent: Intent): Boolean =
    runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() != null

internal fun describeDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    val parts = buildList {
        if (hours > 0) add("$hours hour${if (hours == 1) "" else "s"}")
        if (minutes > 0) add("$minutes minute${if (minutes == 1) "" else "s"}")
        if (remainder > 0) add("$remainder second${if (remainder == 1) "" else "s"}")
    }
    return parts.joinToString(" ")
}
