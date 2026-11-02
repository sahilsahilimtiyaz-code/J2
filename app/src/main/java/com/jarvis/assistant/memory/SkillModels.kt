package com.jarvis.assistant.memory

import kotlinx.serialization.Serializable

@Serializable
data class SkillStep(
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val description: String = "",
)

@Serializable
data class SkillDefinition(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val triggers: List<String> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

data class SkillExecutionResult(
    val skillName: String,
    val success: Boolean,
    val stepResults: List<StepResult>,
    val summary: String,
)

data class StepResult(
    val stepIndex: Int,
    val toolName: String,
    val success: Boolean,
    val output: String,
)
