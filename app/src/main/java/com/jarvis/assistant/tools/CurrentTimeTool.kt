package com.jarvis.assistant.tools

import com.jarvis.assistant.brain.ToolDefinition
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CurrentTimeTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "get_current_time",
        description = "Get the current local date, time and time zone on the owner's phone.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("additionalProperties", false)
        },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val now = ZonedDateTime.now()
        return ToolResult(
            success = true,
            content = now.format(FORMAT),
        )
    }

    private companion object {
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm z", Locale.UK)
    }
}
