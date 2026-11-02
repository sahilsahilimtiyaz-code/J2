package com.jarvis.assistant.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.assistant.brain.ToolDefinition
import com.jarvis.assistant.service.JarvisAlarmReceiver
import com.jarvis.assistant.service.JarvisIncomingCallActivity
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class ScheduleReminderTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "schedule_reminder",
        description = "Schedules an exact spoken reminder that rings the phone like an incoming call from Jarvis. " +
            "Provide the reminder message and either delay in minutes or target hour and minute.",
        parameters = objectSchema(
            required = listOf("message"),
            properties = mapOf(
                "message" to stringProperty("What Jarvis should remind the owner about."),
                "minutes_from_now" to intProperty("Delay in minutes from now (e.g. 15)."),
                "hour" to intProperty("Target hour of day (0-23)."),
                "minute" to intProperty("Target minute (0-59)."),
            ),
        ),
    )

    override val label = "Schedule reminder"
    override val category = ToolCategory.TIME

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val message = arguments.argString("message")
            ?: return ToolResult(false, "Specify the reminder message.")

        val minutes = arguments.argInt("minutes_from_now")
        val hour = arguments.argInt("hour")
        val minute = arguments.argInt("minute") ?: 0

        return withContext(Dispatchers.IO) {
            val alarmManager = context.appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return@withContext ToolResult(false, "Alarm manager service is unavailable.")

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
                triggerCal.add(Calendar.MINUTE, 10)
            }

            val intent = Intent(context.appContext, JarvisAlarmReceiver::class.java).apply {
                putExtra(JarvisIncomingCallActivity.EXTRA_TITLE, "Jarvis Reminder")
                putExtra(JarvisIncomingCallActivity.EXTRA_MESSAGE, message)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context.appContext,
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val exactAllowed = scheduleAlarmSafely(alarmManager, triggerCal.timeInMillis, pendingIntent)

            val timeStr = String.format("%02d:%02d", triggerCal.get(Calendar.HOUR_OF_DAY), triggerCal.get(Calendar.MINUTE))
            saveWidgetNextReminder(context.appContext, "$timeStr · ${message.take(36)}")
            val suffix = if (exactAllowed) "" else " (using window alarm; allow Alarms & Reminders in Settings for exact second precision)"
            ToolResult(true, "Reminder scheduled for $timeStr: \"$message\"$suffix.")
        }
    }
}

class ScheduleMorningBriefingTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "schedule_morning_briefing",
        description = "Schedules a daily morning audio briefing call from Jarvis at a specific hour and minute.",
        parameters = objectSchema(
            required = listOf("hour", "minute"),
            properties = mapOf(
                "hour" to intProperty("Hour to call in 24h format (0-23, e.g. 7 for 7 AM)."),
                "minute" to intProperty("Minute to call (0-59, e.g. 30)."),
            ),
        ),
    )

    override val label = "Morning briefing call"
    override val category = ToolCategory.TIME

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val hour = (arguments.argInt("hour") ?: 7).coerceIn(0, 23)
        val minute = (arguments.argInt("minute") ?: 30).coerceIn(0, 59)

        return withContext(Dispatchers.IO) {
            val alarmManager = context.appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return@withContext ToolResult(false, "Alarm manager service is unavailable.")

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(context.appContext, JarvisAlarmReceiver::class.java).apply {
                putExtra(JarvisIncomingCallActivity.EXTRA_TITLE, "Morning Briefing")
                putExtra(
                    JarvisIncomingCallActivity.EXTRA_MESSAGE,
                    "Good morning, sir. All systems are online. Shall I brief you on today's schedule and notifications?",
                )
                putExtra(JarvisAlarmReceiver.EXTRA_DAILY_HOUR, hour)
                putExtra(JarvisAlarmReceiver.EXTRA_DAILY_MINUTE, minute)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context.appContext,
                9001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val exactAllowed = scheduleAlarmSafely(alarmManager, cal.timeInMillis, pendingIntent)

            val timeStr = String.format("%02d:%02d", hour, minute)
            saveWidgetNextReminder(context.appContext, "$timeStr Daily · Morning Briefing")
            val suffix = if (exactAllowed) "" else " (standard window alarm)"
            ToolResult(true, "Morning briefing call configured for $timeStr daily$suffix.")
        }
    }
}

internal fun scheduleAlarmSafely(
    alarmManager: AlarmManager,
    triggerAtMillis: Long,
    pendingIntent: PendingIntent,
): Boolean {
    val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    } else {
        true
    }
    return try {
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
            true
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
            false
        }
    } catch (_: SecurityException) {
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
        false
    }
}

internal fun saveWidgetNextReminder(context: Context, summary: String) {
    runCatching {
        context.getSharedPreferences("jarvis_widget_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("next_reminder", summary)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
        com.jarvis.assistant.service.JarvisWidgetProvider.updateAll(context)
    }
}
