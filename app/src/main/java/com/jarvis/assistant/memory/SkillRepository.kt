package com.jarvis.assistant.memory

import com.jarvis.assistant.tools.ToolContext
import com.jarvis.assistant.tools.ToolRegistry
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class SkillRepository(
    private val dao: MemoryDao,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun observeSkills(): Flow<List<SkillDefinition>> {
        return dao.observeSkills().map { entities ->
            entities.map { it.toDefinition() }
        }
    }

    suspend fun getSkills(): List<SkillDefinition> = withContext(Dispatchers.IO) {
        dao.skills().map { it.toDefinition() }
    }

    suspend fun saveSkill(
        name: String,
        description: String,
        triggers: List<String>,
        steps: List<SkillStep>,
    ): SkillDefinition = withContext(Dispatchers.IO) {
        val triggersStr = triggers.joinToString(";") { it.trim().lowercase(Locale.ROOT) }
        val stepsJson = json.encodeToString(ListSerializer(SkillStep.serializer()), steps)
        val cleanDesc = description.trim()
        val entity = SkillEntity(
            name = name.trim(),
            description = cleanDesc,
            triggers = triggersStr,
            stepsJson = stepsJson,
            enabled = true,
        )
        val id = dao.insertSkill(entity)
        SkillDefinition(
            id = id,
            name = name.trim(),
            description = cleanDesc,
            triggers = triggers,
            steps = steps,
            enabled = true,
        )
    }

    fun validateSteps(steps: List<SkillStep>, availableTools: Set<String>): String? =
        Companion.validateSteps(steps, availableTools)

    companion object {
        fun validateSteps(steps: List<SkillStep>, availableTools: Set<String>): String? {
            if (steps.isEmpty()) return "A skill must contain at least one step."
            val unknown = steps.map { it.toolName.trim() }.filter { it.isBlank() || it !in availableTools }
            return if (unknown.isNotEmpty()) {
                "Unknown tool(s) in skill steps: ${unknown.joinToString(", ")}"
            } else {
                null
            }
        }
    }

    suspend fun deleteSkill(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteSkill(id)
    }

    suspend fun findMatchingSkill(input: String): SkillDefinition? = withContext(Dispatchers.IO) {
        val normalized = input.trim().lowercase(Locale.ROOT)
        val all = getSkills().filter { it.enabled }
        all.firstOrNull { skill ->
            skill.triggers.any { trigger ->
                val normTrigger = trigger.trim().lowercase(Locale.ROOT)
                normTrigger.isNotBlank() && (normalized == normTrigger || normalized.contains(normTrigger))
            }
        }
    }

    suspend fun executeSkill(
        skill: SkillDefinition,
        toolRegistry: ToolRegistry,
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        val stepResults = mutableListOf<StepResult>()
        var allSuccess = true

        for ((index, step) in skill.steps.withIndex()) {
            val canonicalTool = when (step.toolName) {
                "read_battery_status" -> "get_device_status"
                "read_time" -> "get_current_time"
                else -> step.toolName
            }
            val argsJson = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(
                    step.arguments.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
                )
            )

            val result = toolRegistry.execute(canonicalTool, argsJson)
            stepResults.add(
                StepResult(
                    stepIndex = index + 1,
                    toolName = canonicalTool,
                    success = result.success,
                    output = result.content,
                ),
            )

            if (!result.success) {
                allSuccess = false
                break
            }
        }

        val details = stepResults.joinToString("\n") {
            "Step ${it.stepIndex} (${it.toolName}): ${it.output}"
        }
        val summary = if (allSuccess) {
            "Skill '${skill.name}' completed all ${skill.steps.size} steps successfully.\n$details"
        } else {
            "Skill '${skill.name}' stopped at step ${stepResults.size} of ${skill.steps.size}.\n$details"
        }

        SkillExecutionResult(
            skillName = skill.name,
            success = allSuccess,
            stepResults = stepResults,
            summary = summary,
        )
    }

    suspend fun seedDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        val existing = dao.skills()
        if (existing.isNotEmpty()) {
            // Repair any legacy seeded skills that referenced deprecated tool names or lacked descriptions
            for (entity in existing) {
                val repairedStepsJson = entity.stepsJson
                    .replace("\"read_battery_status\"", "\"get_device_status\"")
                    .replace("\"read_time\"", "\"get_current_time\"")
                val repairedDesc = when {
                    entity.description.isNotBlank() -> entity.description
                    entity.name.equals("Morning Routine", ignoreCase = true) ->
                        "Checks battery & connectivity, unread notifications, and plays morning lo-fi."
                    entity.name.equals("Device Diagnostics", ignoreCase = true) ->
                        "Comprehensive check of device battery, time, and hardware health."
                    else -> entity.description
                }
                if (repairedStepsJson != entity.stepsJson || repairedDesc != entity.description) {
                    dao.updateSkill(
                        entity.copy(
                            stepsJson = repairedStepsJson,
                            description = repairedDesc,
                        ),
                    )
                }
            }
            return@withContext
        }

        saveSkill(
            name = "Morning Routine",
            description = "Checks battery & connectivity, unread notifications, and plays morning lo-fi.",
            triggers = listOf("morning routine", "start my day", "good morning jarvis routine"),
            steps = listOf(
                SkillStep(toolName = "get_device_status", description = "Check battery and connectivity"),
                SkillStep(toolName = "read_notifications", description = "Brief on unread alerts"),
                SkillStep(
                    toolName = "search_youtube",
                    arguments = mapOf("query" to "morning lo-fi beats"),
                    description = "Play morning music",
                ),
            ),
        )

        saveSkill(
            name = "Device Diagnostics",
            description = "Comprehensive check of device battery, time, and hardware health.",
            triggers = listOf("diagnostics", "check system", "system health check"),
            steps = listOf(
                SkillStep(toolName = "get_device_status", description = "Check battery and network status"),
                SkillStep(toolName = "get_current_time", description = "Verify sync time"),
                SkillStep(toolName = "run_diagnostics", description = "Inspect RAM, CPU and thermal telemetry"),
            ),
        )
    }

    private fun SkillEntity.toDefinition(): SkillDefinition {
        val triggerList = triggers.split(";").map { it.trim() }.filter { it.isNotBlank() }
        val stepList = runCatching {
            json.decodeFromString(ListSerializer(SkillStep.serializer()), stepsJson)
        }.getOrDefault(emptyList())

        return SkillDefinition(
            id = id,
            name = name,
            description = description,
            triggers = triggerList,
            steps = stepList,
            enabled = enabled,
            createdAt = createdAt,
        )
    }
}
