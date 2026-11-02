package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.brain.ToolDefinition
import com.jarvis.assistant.memory.MemoryType
import kotlinx.serialization.json.JsonObject

class RememberTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "remember_about_owner",
        description = "Save one durable fact or preference about the owner so it is remembered " +
            "after the app restarts. Use this whenever the owner says to remember something, or " +
            "states a lasting detail such as their name, family, work, routine or taste. " +
            "Never store passwords, card numbers, one-time codes or API keys.",
        parameters = objectSchema(
            required = listOf("text"),
            properties = mapOf(
                "text" to stringProperty(
                    "One short sentence in the third person, e.g. \"The owner's sister is named Anya.\"",
                ),
                "type" to stringProperty(
                    "Kind of memory.",
                    enum = listOf(
                        MemoryType.FACT,
                        MemoryType.PREFERENCE,
                        MemoryType.PERSON,
                        MemoryType.PROJECT,
                        MemoryType.ROUTINE,
                    ),
                ),
                "importance" to intProperty("1 low to 5 high. Use 4 or 5 for identity and close family."),
            ),
        ),
    )

    override val label = "Remember a fact"
    override val category = ToolCategory.INFO

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val memory = context.memory
            ?: return ToolResult(false, "Memory is not available in this build.")
        val text = arguments.argString("text")
            ?: return ToolResult(false, "Tell me what to remember.")
        if (text.length < 3) return ToolResult(false, "That is too short to remember.")
        if (looksLikeSecret(text)) {
            return ToolResult(false, "I will not store passwords, codes or keys.")
        }
        val type = arguments.argString("type") ?: MemoryType.FACT
        val importance = (arguments.argInt("importance") ?: 3).coerceIn(1, 5)
        return ToolResult(true, memory.remember(text, type, importance))
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
        description = "Search what Jarvis remembers about the owner. Use it when the answer may " +
            "depend on a stored detail that is not already in the conversation.",
        parameters = objectSchema(
            required = listOf("query"),
            properties = mapOf(
                "query" to stringProperty("Keywords to search for, e.g. \"sister birthday\"."),
                "limit" to intProperty("Maximum results, default 5."),
            ),
        ),
    )

    override val label = "Search memory"
    override val category = ToolCategory.INFO

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val memory = context.memory
            ?: return ToolResult(false, "Memory is not available in this build.")
        val query = arguments.argString("query")
            ?: return ToolResult(false, "What should I look for?")
        val limit = (arguments.argInt("limit") ?: 5).coerceIn(1, 10)
        val results = memory.search(query, limit)
        return if (results.isEmpty()) {
            ToolResult(true, "I have nothing stored that matches \"$query\".")
        } else {
            ToolResult(true, results.joinToString("\n") { "- $it" })
        }
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
