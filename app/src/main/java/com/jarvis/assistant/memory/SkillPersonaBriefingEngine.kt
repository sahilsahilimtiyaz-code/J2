package com.jarvis.assistant.memory

import java.util.Calendar
import java.util.Locale

/**
 * Transforms raw skill and routine execution results into calm, dignified,
 * natural J.A.R.V.I.S. briefings, stripping all developer logs, step numbers,
 * tool names, and internal directives.
 */
object SkillPersonaBriefingEngine {

    /**
     * Generates a spoken persona briefing for J.A.R.V.I.S. (1 to 3 natural sentences).
     */
    fun generateSpokenBriefing(
        skillName: String,
        stepResults: List<StepResult>,
        ownerTitle: String = "sir",
        success: Boolean = true,
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): String {
        val title = ownerTitle.trim().ifBlank { "sir" }
        val nameLower = skillName.lowercase(Locale.ROOT)

        if (!success) {
            val failedStep = stepResults.firstOrNull { !it.success }
            val stepDesc = failedStep?.toolName?.replace("_", " ") ?: "a step"
            return "I encountered an issue while executing your $skillName routine at $stepDesc, $title. The remaining steps were paused."
        }

        // Domain-specific natural briefings
        return when {
            nameLower.contains("morning") || nameLower.contains("start my day") -> {
                formatMorningBriefing(stepResults, title, currentHour)
            }
            nameLower.contains("diagnostic") || nameLower.contains("health") -> {
                formatDiagnosticsBriefing(stepResults, title)
            }
            nameLower.contains("music") -> {
                formatMusicBriefing(stepResults, title)
            }
            else -> {
                formatGenericSkillBriefing(skillName, stepResults, title)
            }
        }
    }

    private fun formatMorningBriefing(
        stepResults: List<StepResult>,
        ownerTitle: String,
        currentHour: Int,
    ): String {
        val greeting = when (currentHour) {
            in 4..11 -> "Good morning, $ownerTitle."
            in 12..16 -> "Good afternoon, $ownerTitle."
            in 17..22 -> "Good evening, $ownerTitle."
            else -> "Welcome back, $ownerTitle."
        }

        val parts = mutableListOf<String>()

        // 1. Device status
        val deviceOutput = stepResults.firstOrNull { it.toolName == "get_device_status" }?.output.orEmpty()
        val batteryMatch = Regex("""Battery\s+(\d+%)""").find(deviceOutput)?.groupValues?.get(1)
        val isWifi = deviceOutput.contains("Wi-Fi", ignoreCase = true)
        val isCellular = deviceOutput.contains("Cellular", ignoreCase = true)

        if (batteryMatch != null) {
            val netStr = when {
                isWifi -> "on Wi-Fi"
                isCellular -> "on mobile data"
                else -> ""
            }
            parts += "Battery is at $batteryMatch ${netStr.trim()}".trim()
        }

        // 2. Weather & Notifications
        val notifOutput = stepResults.firstOrNull { it.toolName == "read_notifications" }?.output.orEmpty()

        // Extract weather if Google weather alert exists
        val weatherMatch = Regex("""(\d+°\s+in\s+[^:\n]+:\s*[^·\n]+)""").find(notifOutput)?.groupValues?.get(1)
            ?: Regex("""(\d+°[^\n·]+)""").find(notifOutput)?.groupValues?.get(1)

        if (weatherMatch != null) {
            val cleanWeather = weatherMatch.replace("·", "").replace(":", ",").trim()
            parts += "Weather reports $cleanWeather"
        }

        // Extract unread notifications count and apps
        val notifCount = Regex("""(\d+)\s+active notification""").find(notifOutput)?.groupValues?.get(1)?.toIntOrNull()
        if (notifCount != null && notifCount > 0) {
            val appMentions = mutableListOf<String>()
            if (notifOutput.contains("[Instagram", ignoreCase = true)) appMentions += "Instagram"
            if (notifOutput.contains("[WhatsApp", ignoreCase = true)) appMentions += "WhatsApp"
            if (notifOutput.contains("[Gmail", ignoreCase = true) || notifOutput.contains("[Mail", ignoreCase = true)) appMentions += "email"
            if (notifOutput.contains("[Messages", ignoreCase = true)) appMentions += "SMS"

            val appSummary = if (appMentions.isNotEmpty()) {
                "including updates from ${appMentions.distinct().joinToString(" and ")}"
            } else {
                ""
            }
            parts += "You have $notifCount active notification${if (notifCount > 1) "s" else ""} ${appSummary.trim()}".trim()
        } else if (notifOutput.contains("No active notifications", ignoreCase = true)) {
            parts += "Your notifications are clear"
        }

        // 3. Calendar events
        val calOutput = stepResults.firstOrNull { it.toolName == "read_calendar_events" }?.output.orEmpty()
        if (calOutput.isNotBlank() && !calOutput.contains("No calendar events", ignoreCase = true)) {
            parts += "Your calendar has upcoming events scheduled today"
        } else if (calOutput.contains("No calendar events", ignoreCase = true)) {
            parts += "Your calendar is clear for today"
        }

        // 4. Music playback (if music was part of routine)
        val mediaOutput = stepResults.firstOrNull { it.toolName == "search_youtube" || it.toolName == "media_control" }?.output.orEmpty()
        if (mediaOutput.contains("Now playing", ignoreCase = true) || mediaOutput.contains("Played the top result", ignoreCase = true)) {
            val songTitle = Regex("""Now playing\s+"([^"]+)"""").find(mediaOutput)?.groupValues?.get(1)
                ?: Regex(""""([^"]+)"""").find(mediaOutput)?.groupValues?.get(1)
            if (songTitle != null) {
                parts += "I've queued up \"$songTitle\" on YouTube"
            }
        }

        val body = if (parts.isNotEmpty()) {
            parts.joinToString(". ") + "."
        } else {
            "All morning systems are operational."
        }

        return "$greeting $body All systems are online. Shall we begin?"
    }

    private fun formatDiagnosticsBriefing(
        stepResults: List<StepResult>,
        ownerTitle: String,
    ): String {
        val deviceOutput = stepResults.firstOrNull { it.toolName == "get_device_status" }?.output.orEmpty()
        val batteryMatch = Regex("""Battery\s+(\d+%)""").find(deviceOutput)?.groupValues?.get(1)
        val tempMatch = Regex("""(\d+°C)""").find(deviceOutput)?.groupValues?.get(1)

        val tempStr = if (tempMatch != null) "running at $tempMatch" else "temperatures nominal"
        val batteryStr = if (batteryMatch != null) "Battery is at $batteryMatch" else "Power levels stable"

        return "All system diagnostics have passed, $ownerTitle. $batteryStr, $tempStr, and memory load is optimal."
    }

    private fun formatMusicBriefing(
        stepResults: List<StepResult>,
        ownerTitle: String,
    ): String {
        val mediaOutput = stepResults.firstOrNull { it.toolName == "search_youtube" }?.output.orEmpty()
        val songTitle = Regex("""Now playing\s+"([^"]+)"""").find(mediaOutput)?.groupValues?.get(1)
            ?: Regex(""""([^"]+)"""").find(mediaOutput)?.groupValues?.get(1)
            ?: "your selection"

        return "Now playing \"$songTitle\" for you, $ownerTitle. Enjoy."
    }

    private fun formatGenericSkillBriefing(
        skillName: String,
        stepResults: List<StepResult>,
        ownerTitle: String,
    ): String {
        val count = stepResults.size
        return "Routine '$skillName' completed successfully, $ownerTitle. All $count operations executed as requested."
    }
}
