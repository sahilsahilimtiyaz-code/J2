package com.jarvis.assistant.tools

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import com.jarvis.assistant.brain.ToolDefinition
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject

class NavigateTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "navigate_to",
        description = "Open maps navigation to a place or address, e.g. \"Gateway of India\".",
        parameters = objectSchema(
            required = listOf("destination"),
            properties = mapOf(
                "destination" to stringProperty("The place or address to navigate to."),
            ),
        ),
    )

    override val label = "Navigate"
    override val category = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val destination = arguments.argString("destination")
            ?: return ToolResult(false, "Tell me where to navigate.")

        val geo = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("geo:0,0?q=" + Uri.encode(destination)),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (resolves(context.appContext, geo)) {
            return runCatching { context.appContext.startActivity(geo) }.fold(
                onSuccess = { ToolResult(true, "Opening navigation to $destination.") },
                onFailure = { error ->
                    ToolResult(false, "Maps would not open: ${error.message}.")
                },
            )
        }

        return openUrl(
            context.appContext,
            "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(destination),
            "Opening $destination in the browser map.",
        )
    }
}

class CalendarEventTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "create_calendar_event",
        description = "Open the calendar's new-event screen with the details filled in. " +
            "Uses ISO-8601 local time, for example 2026-11-02T09:30.",
        parameters = objectSchema(
            required = listOf("title", "start"),
            properties = mapOf(
                "title" to stringProperty("Event title."),
                "start" to stringProperty("Start time as 2026-11-02T09:30, or just 09:30 for today."),
                "duration_minutes" to intProperty("Length in minutes. Defaults to 60."),
                "location" to stringProperty("Optional location."),
                "notes" to stringProperty("Optional notes."),
            ),
        ),
    )

    override val label = "Calendar event"
    override val category = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val title = arguments.argString("title")
            ?: return ToolResult(false, "Give the event a title.")
        val startText = arguments.argString("start")
            ?: return ToolResult(false, "Tell me when the event starts.")
        val duration = (arguments.argInt("duration_minutes") ?: 60).coerceIn(5, 24 * 60)

        val start = parseStart(startText)
            ?: return ToolResult(
                false,
                "I could not read the start time \"$startText\". Use 2026-11-02T09:30 or 09:30.",
            )
        val zone = ZoneId.systemDefault()
        val startMillis = start.atZone(zone).toInstant().toEpochMilli()
        val endMillis = start.plusMinutes(duration.toLong()).atZone(zone).toInstant().toEpochMilli()

        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        arguments.argString("location")?.let {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
        }
        arguments.argString("notes")?.let {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, it)
        }

        if (!resolves(context.appContext, intent)) {
            return ToolResult(false, "No calendar app is installed to create the event.")
        }

        val stamp = start.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
        return runCatching { context.appContext.startActivity(intent) }.fold(
            onSuccess = { ToolResult(true, "Opening the calendar with \"$title\" for $stamp.") },
            onFailure = { error ->
                ToolResult(false, "The calendar app would not open: ${error.message}.")
            },
        )
    }

    private fun parseStart(raw: String): LocalDateTime? {
        val text = raw.trim()
        runCatching { return LocalDateTime.parse(text) }
        runCatching { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }
        runCatching {
            val date = LocalDate.parse(text)
            return date.atTime(9, 0)
        }
        runCatching {
            val time = LocalTime.parse(text)
            return LocalDate.now().atTime(time)
        }
        return null
    }
}

class ShareTextTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "share_text",
        description = "Share a piece of text through the Android share sheet, so the owner can " +
            "pick where it goes.",
        parameters = objectSchema(
            required = listOf("text"),
            properties = mapOf(
                "text" to stringProperty("The text to share."),
                "subject" to stringProperty("Optional subject line."),
            ),
        ),
    )

    override val label = "Share text"
    override val category = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val text = arguments.argString("text")
            ?: return ToolResult(false, "Give me the text to share.")

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            arguments.argString("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        val chooser = Intent.createChooser(send, "Share with")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching { context.appContext.startActivity(chooser) }.fold(
            onSuccess = { ToolResult(true, "Opening the share sheet.") },
            onFailure = { error ->
                ToolResult(false, "The share sheet would not open: ${error.message}.")
            },
        )
    }
}

class OpenSettingsScreenTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "open_settings_screen",
        description = "Open a specific Android settings screen, such as Wi-Fi, Bluetooth, " +
            "sound, display, battery or accessibility.",
        parameters = objectSchema(
            required = listOf("screen"),
            properties = mapOf(
                "screen" to stringProperty(
                    "Which settings screen to open.",
                    enum = listOf(
                        "wifi", "bluetooth", "sound", "display", "battery", "apps",
                        "accessibility", "location", "do_not_disturb", "developer", "main",
                    ),
                ),
            ),
        ),
    )

    override val label = "Settings screens"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val screen = arguments.argString("screen")?.lowercase()?.replace(' ', '_')
            ?: return ToolResult(false, "Tell me which settings screen to open.")

        val (action, label) = when (screen) {
            "wifi", "wi_fi" -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS to "Sound"
            "display", "screen", "dark_mode" -> Settings.ACTION_DISPLAY_SETTINGS to "Display"
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "Battery"
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS to "Apps"
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility"
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location"
            "do_not_disturb", "dnd" ->
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS to "Do Not Disturb access"
            "notification_access" ->
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to "Notification access"
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "Developer options"
            else -> Settings.ACTION_SETTINGS to "Settings"
        }

        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.appContext.startActivity(intent) }.fold(
            onSuccess = { ToolResult(true, "Opened $label settings.") },
            onFailure = { error ->
                ToolResult(false, "Android would not open $label settings: ${error.message}.")
            },
        )
    }
}
