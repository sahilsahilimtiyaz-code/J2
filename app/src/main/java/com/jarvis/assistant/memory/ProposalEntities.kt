package com.jarvis.assistant.memory

import kotlinx.serialization.Serializable

enum class ProposalType {
    PERSONA_PROMPT,
    NEW_SKILL,
    SETTING_OPTIMIZATION,
}

@Serializable
data class Proposal(
    val id: String,
    val type: ProposalType,
    val title: String,
    val rationale: String,
    val beforeText: String,
    val afterText: String,
    val timestamp: Long = System.currentTimeMillis(),
)
