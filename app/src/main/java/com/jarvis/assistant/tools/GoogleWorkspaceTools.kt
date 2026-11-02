package com.jarvis.assistant.tools

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.jarvis.assistant.brain.ToolDefinition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class ReadCalendarTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "read_calendar_events",
        description = "Reads upcoming events and appointments from the owner's Google Calendar.",
        parameters = objectSchema(
            properties = mapOf(
                "days_ahead" to intProperty("How many days ahead to search (default 1 for today and tomorrow)."),
            ),
        ),
    )

    override val label = "Read calendar"
    override val category = ToolCategory.INFO

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val daysAhead = arguments.argInt("days_ahead") ?: 1
        return withContext(Dispatchers.IO) {
            val granted = context.permissions.ensure(
                Manifest.permission.READ_CALENDAR,
                "Jarvis needs access to your calendar to read your schedule.",
            )

            if (!granted) {
                // Fallback: open calendar app directly
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.appContext.startActivity(intent) }
                return@withContext ToolResult(
                    true,
                    "Calendar permission not granted; opened Calendar app instead.",
                )
            }

            val startMillis = System.currentTimeMillis()
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, daysAhead.coerceIn(1, 7))
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
            }
            val endMillis = cal.timeInMillis

            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
            )

            val events = mutableListOf<String>()
            val timeFormat = SimpleDateFormat("EEE h:mm a", Locale.getDefault())

            runCatching {
                context.appContext.contentResolver.query(
                    builder.build(),
                    projection,
                    null,
                    null,
                    CalendarContract.Instances.BEGIN + " ASC",
                )?.use { cursor ->
                    val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

                    while (cursor.moveToNext() && events.size < 10) {
                        val title = if (titleIdx >= 0) cursor.getString(titleIdx) else "Event"
                        val begin = if (beginIdx >= 0) cursor.getLong(beginIdx) else 0L
                        val loc = if (locIdx >= 0) cursor.getString(locIdx) else null
                        val locStr = if (!loc.isNullOrBlank()) " at $loc" else ""
                        val dateStr = if (begin > 0) timeFormat.format(Date(begin)) else "upcoming"
                        events.add("- $title ($dateStr$locStr)")
                    }
                }
            }

            if (events.isEmpty()) {
                ToolResult(true, "Your calendar is completely clear for the next $daysAhead day(s), sir.")
            } else {
                ToolResult(true, "Upcoming calendar events:\n" + events.joinToString("\n"))
            }
        }
    }
}

class CreateCalendarEventTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "schedule_calendar_event",
        description = "Schedules a new event or appointment on the owner's Google Calendar starting N minutes from now.",
        parameters = objectSchema(
            required = listOf("title"),
            properties = mapOf(
                "title" to stringProperty("The event title or description."),
                "minutes_from_now" to intProperty("Minutes from now when event starts (default 60)."),
                "duration_minutes" to intProperty("Duration in minutes (default 30)."),
                "location" to stringProperty("Optional location or meeting link."),
            ),
        ),
    )

    override val label = "Schedule calendar event"
    override val category = ToolCategory.TIME

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val title = arguments.argString("title")
            ?: return ToolResult(false, "Specify the event title.")
        val minutesFromNow = arguments.argInt("minutes_from_now") ?: 60
        val duration = arguments.argInt("duration_minutes") ?: 30
        val location = arguments.argString("location").orEmpty()

        return withContext(Dispatchers.IO) {
            val startCal = Calendar.getInstance().apply {
                add(Calendar.MINUTE, minutesFromNow)
            }
            val endCal = Calendar.getInstance().apply {
                timeInMillis = startCal.timeInMillis
                add(Calendar.MINUTE, duration)
            }

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startCal.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endCal.timeInMillis)
                if (location.isNotBlank()) {
                    putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            runCatching { context.appContext.startActivity(intent) }
                .fold(
                    onSuccess = {
                        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(startCal.time)
                        ToolResult(true, "Prepared calendar event \"$title\" for $timeStr.")
                    },
                    onFailure = { error ->
                        ToolResult(false, "Could not open calendar to add event: ${error.message}")
                    },
                )
        }
    }
}

class ComposeEmailTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "compose_email",
        description = "Drafts or sends an email via Gmail with recipient, subject, and body.",
        parameters = objectSchema(
            required = listOf("recipient", "subject", "body"),
            properties = mapOf(
                "recipient" to stringProperty("Email address of the recipient."),
                "subject" to stringProperty("Subject line of the email."),
                "body" to stringProperty("The content of the email."),
            ),
        ),
    )

    override val label = "Compose email"
    override val category = ToolCategory.CONTACTS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val recipient = arguments.argString("recipient")
            ?: return ToolResult(false, "Specify the recipient email address.")
        val subject = arguments.argString("subject") ?: "Message from Jarvis"
        val body = arguments.argString("body") ?: ""

        val approved = context.confirmation.confirm(
            title = "Send email to $recipient?",
            detail = "Subject: $subject\n\n$body",
        )
        if (!approved) {
            return ToolResult(false, "Email dispatch cancelled by owner.")
        }

        return withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$recipient")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            runCatching { context.appContext.startActivity(intent) }
                .fold(
                    onSuccess = { ToolResult(true, "Email drafted to $recipient. Confirming dispatch in Gmail.") },
                    onFailure = { error -> ToolResult(false, "Could not open email app: ${error.message}") },
                )
        }
    }
}
