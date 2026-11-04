package com.jarvis.assistant.tools

import com.jarvis.assistant.brain.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class SaveSkillTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "save_skill",
        description = "Saves a multi-step routine or skill so it can be replayed deterministically in the future. " +
            "Provide a name and trigger phrases. Pass steps as a JSON array of {toolName, arguments}. " +
            "If the owner says 'save that as a skill' after a task you just completed, omit steps: " +
            "Jarvis automatically records the successful tool calls from this turn.",
        parameters = objectSchema(
            required = listOf("name", "triggers"),
            properties = mapOf(
                "name" to stringProperty("Unique human name for this routine (e.g. 'Morning Routine')."),
                "description" to stringProperty("Optional summary of what this routine does."),
                "triggers" to stringProperty("Comma-separated trigger phrases (e.g. 'morning routine, start my day')."),
                "steps" to stringProperty("JSON array of steps, each with 'toolName' and optional 'arguments' map."),
            ),
        ),
    )

    override val label = "Save routine"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val bridge = context.skills
            ?: return ToolResult(false, "Skill subsystem is not available.")

        val name = arguments.argString("name")
            ?: return ToolResult(false, "Please specify a name for this skill.")
        val description = arguments.argString("description").orEmpty()
        val triggersRaw = when (val element = arguments["triggers"]) {
            is kotlinx.serialization.json.JsonArray -> element.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.joinToString(",")
            is kotlinx.serialization.json.JsonPrimitive -> element.content
            else -> ""
        }
        val triggers = triggersRaw.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
        val stepsJson = when (val element = arguments["steps"]) {
            null -> ""
            is kotlinx.serialization.json.JsonPrimitive -> element.content
            else -> element.toString()
        }

        return withContext(Dispatchers.IO) {
            val response = bridge.saveSkill(name, description, triggers, stepsJson)
            val ok = !response.startsWith("Invalid", ignoreCase = true) &&
                !response.startsWith("Unknown tool", ignoreCase = true) &&
                !response.startsWith("A skill must", ignoreCase = true) &&
                !response.startsWith("I have no completed task", ignoreCase = true)
            ToolResult(ok, response)
        }
    }
}

class RunSkillTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "run_skill",
        description = "Replays a previously saved routine or skill by name or trigger phrase.",
        parameters = objectSchema(
            required = listOf("skill_name"),
            properties = mapOf(
                "skill_name" to stringProperty("The name or trigger of the skill to execute."),
            ),
        ),
    )

    override val label = "Run routine"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val bridge = context.skills
            ?: return ToolResult(false, "Skill subsystem is not available.")

        val skillName = arguments.argString("skill_name")
            ?: return ToolResult(false, "Specify which skill to run.")

        return withContext(Dispatchers.IO) {
            val response = bridge.runSkill(skillName)
            val ok = !response.startsWith("No skill found", ignoreCase = true) &&
                !response.contains("stopped at step", ignoreCase = true)
            ToolResult(ok, response)
        }
    }
}

class ListSkillsTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "list_skills",
        description = "Lists all automated routines and declarative skills configured on Jarvis.",
        parameters = objectSchema(
            properties = emptyMap(),
        ),
    )

    override val label = "List skills"
    override val category = ToolCategory.INFO

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val bridge = context.skills
            ?: return ToolResult(false, "Skill subsystem is not available.")

        return withContext(Dispatchers.IO) {
            val list = bridge.listSkills()
            if (list.isEmpty()) {
                ToolResult(true, "No automated skills are currently configured.")
            } else {
                ToolResult(true, "Configured skills:\n" + list.joinToString("\n\n"))
            }
        }
    }
}

class DeleteSkillTool : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "delete_skill",
        description = "Deletes a previously saved automated skill by its exact name.",
        parameters = objectSchema(
            required = listOf("name"),
            properties = mapOf(
                "name" to stringProperty("The name of the skill to delete."),
            ),
        ),
    )

    override val label = "Delete skill"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val bridge = context.skills
            ?: return ToolResult(false, "Skill subsystem is not available.")

        val name = arguments.argString("name")
            ?: return ToolResult(false, "Specify which skill to delete.")

        return withContext(Dispatchers.IO) {
            val response = bridge.deleteSkill(name)
            val ok = !response.startsWith("No skill found", ignoreCase = true)
            ToolResult(ok, response)
        }
    }
}
