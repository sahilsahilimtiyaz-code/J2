package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.brain.ToolDefinition
import com.jarvis.assistant.memory.MemoryType
import kotlinx.serialization.json.JsonObject

class RememberTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "remember_about_owner",
        description = "Save one durable or temporary fact, preference, project, important date, or recurring task " +
            "about the owner so it is remembered across sessions. " +
            "Never store passwords, card numbers, one-time codes or API keys.",
        parameters = objectSchema(
            required = listOf("text"),
            properties = mapOf(
                "text" to stringProperty(
                    "One short sentence or note to remember, e.g. \"The owner's sister is named Anya.\"",
                ),
                "type" to stringProperty(
                    "Kind of memory.",
                    enum = listOf(
                        MemoryType.FACT,
                        MemoryType.NOTE,
                        MemoryType.PREFERENCE,
                        MemoryType.PERSON,
                        MemoryType.PROJECT,
                        MemoryType.ROUTINE,
                        MemoryType.IMPORTANT_DATE,
                        MemoryType.RECURRING_TASK,
                    ),
                ),
                "importance" to intProperty("1 low to 5 high. Use 4 or 5 for identity, notes, dates, and close family."),
                "tags" to stringProperty("Optional comma-separated tags (e.g. \"note,work,trip,family\")."),
                "temporary_hours" to intProperty(
                    "Optional hours until this temporary memory automatically expires (0 or omitted = permanent).",
                ),
            ),
        ),
    )

    override val label = "Remember a fact"
    override val category = ToolCategory.INFO

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val memory = context.memory
            ?: return ToolResult(false, "Memory is not available in this build.")
        val text = arguments.argString("text")
            ?: arguments.argString("fact")
            ?: arguments.argString("note")
            ?: arguments.argString("content")
            ?: return ToolResult(false, "Tell me what to remember.")
        if (text.length < 3) return ToolResult(false, "That is too short to remember.")
        if (looksLikeSecret(text)) {
            return ToolResult(false, "I will not store passwords, codes or keys.")
        }
        val type = arguments.argString("type") ?: MemoryType.FACT
        val importance = (arguments.argInt("importance") ?: 4).coerceIn(1, 5)
        val tags = arguments.argString("tags").orEmpty()
        val tempHours = arguments.argInt("temporary_hours") ?: 0
        val expiresAt = if (tempHours > 0) {
            System.currentTimeMillis() + tempHours.coerceIn(1, 24 * 365) * 3_600_000L
        } else {
            0L
        }
        return ToolResult(true, memory.rememberAdvanced(text, type, importance, tags, expiresAt))
    }

    private fun looksLikeSecret(text: String): Boolean {
        val lower = text.lowercase()
        if (SECRET_WORDS.any { lower.contains(it) }) return true
        return Regex("\\d{12,}").containsMatchIn(text)
    }

    private companion object {
        val SECRET_WORDS = listOf(
            "password",
            "passcode",
            "card number",
            "cvv",
            "otp",
            "one-time code",
            "api key",
            "secret",
        )
    }
}

class SearchMemoryTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "search_memory",
        description = "Search what Jarvis remembers about the owner, stored notes, and optionally past conversation history. " +
            "Use it when the answer may depend on a stored detail, note, project, date, or earlier chat.",
        parameters = objectSchema(
            required = listOf("query"),
            properties = mapOf(
                "query" to stringProperty("Keywords to search for, e.g. \"sister birthday\", \"notes\", or \"trip\"."),
                "limit" to intProperty("Maximum results, default 8."),
                "include_history" to booleanProperty("Also search past conversation messages (default true)."),
            ),
        ),
    )

    override val label = "Search memory"
    override val category = ToolCategory.INFO

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val memory = context.memory
            ?: return ToolResult(false, "Memory is not available in this build.")
        val query = arguments.argString("query").orEmpty().trim()
        val limit = (arguments.argInt("limit") ?: 8).coerceIn(1, 15)
        val includeHistory = arguments.argBoolean("include_history") ?: true
        val primaryResults = memory.search(query, limit)
        // If specific keyword search yielded nothing, return all stored memories/notes so the owner
        // never gets an empty answer when they have saved memories or notes.
        val results = if (primaryResults.isNotEmpty()) {
            primaryResults
        } else {
            memory.search("", limit)
        }
        val historyHits = if (includeHistory && query.isNotBlank()) memory.searchHistory(query, limit) else emptyList()
        if (results.isEmpty() && historyHits.isEmpty()) {
            return ToolResult(true, "I have no stored memories or notes yet.")
        }
        val combined = buildString {
            if (results.isNotEmpty()) {
                append("Stored memories and notes:\n")
                append(results.joinToString("\n") { "- $it" })
            }
            if (historyHits.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Conversation history matches:\n")
                append(historyHits.joinToString("\n") { "- $it" })
            }
        }
        return ToolResult(true, combined)
    }
}

class ForgetMemoryTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "forget_memory",
        description = "Delete stored memories that match a query. This is irreversible, so the " +
            "app asks the owner to confirm before anything is removed.",
        parameters = objectSchema(
            required = listOf("query"),
            properties = mapOf(
                "query" to stringProperty("What the owner wants forgotten."),
            ),
        ),
    )

    override val label = "Forget a memory"
    override val category = ToolCategory.INFO

    override fun availability(context: Context): ToolAvailability =
        ToolAvailability("Asks first", true)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val memory = context.memory
            ?: return ToolResult(false, "Memory is not available in this build.")
        val query = arguments.argString("query")
            ?: return ToolResult(false, "What should I forget?")
        val approved = context.confirmation.confirm(
            title = "Forget memories about \"$query\"?",
            detail = "This permanently deletes the matching entries. It cannot be undone.",
        )
        if (!approved) return ToolResult(false, "The owner declined, so nothing was deleted.")
        return ToolResult(true, memory.forget(query))
    }
}
