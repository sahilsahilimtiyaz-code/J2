package com.jarvis.assistant.memory

import com.jarvis.assistant.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ProposalRepository(
    private val filesDir: File,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(filesDir, "proposals.json")

    private val _proposals = MutableStateFlow<List<Proposal>>(emptyList())
    val proposals: StateFlow<List<Proposal>> = _proposals.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            seedInitialProposals()
            return@withContext
        }
        val loaded = runCatching {
            json.decodeFromString(ListSerializer(Proposal.serializer()), file.readText())
        }.getOrDefault(emptyList())

        if (loaded.isEmpty()) {
            seedInitialProposals()
        } else {
            _proposals.value = loaded
        }
    }

    private suspend fun seedInitialProposals() {
        val seeded = listOf(
            Proposal(
                id = "prop_morning_greeting",
                type = ProposalType.PERSONA_PROMPT,
                title = "Polished Butler Morning Greeting",
                rationale = "Optimize morning greeting cadence with a succinct status summary and dry British elegance.",
                beforeText = "Good morning, sir. All systems are online.",
                afterText = "Good morning, sir. Systems are nominal, battery is verified, and the schedule is clear. Shall we begin?",
            ),
            Proposal(
                id = "prop_night_wind_down",
                type = ProposalType.NEW_SKILL,
                title = "Automated Evening Wind-Down Routine",
                rationale = "You frequently lower volume and ask for soft music after 10 PM. This skill automates the sequence.",
                beforeText = "Manual command sequence",
                afterText = "Evening Wind-Down: Sets ringer to silent, dims screen brightness, and queues ambient sleep sounds.",
            ),
        )
        _proposals.value = seeded
        saveToDisk(seeded)
    }

    suspend fun addProposal(proposal: Proposal) = withContext(Dispatchers.IO) {
        val updated = listOf(proposal) + _proposals.value.filter { it.id != proposal.id }
        _proposals.value = updated
        saveToDisk(updated)
    }

    suspend fun approveProposal(proposalId: String): Boolean = withContext(Dispatchers.IO) {
        val proposal = _proposals.value.firstOrNull { it.id == proposalId } ?: return@withContext false
        when (proposal.type) {
            ProposalType.PERSONA_PROMPT -> {
                settingsRepository.update { current ->
                    current.copy(
                        persona = current.persona.copy(
                            customPrompt = proposal.afterText,
                        ),
                    )
                }
            }
            ProposalType.NEW_SKILL -> {
                skillRepository.saveSkill(
                    name = proposal.title,
                    description = proposal.rationale,
                    triggers = listOf("evening routine", "wind down", "good night jarvis"),
                    steps = listOf(
                        SkillStep(toolName = "set_ringer_mode", arguments = mapOf("mode" to "silent")),
                        SkillStep(toolName = "search_youtube", arguments = mapOf("query" to "peaceful ambient sleep rain")),
                    ),
                )
            }
            ProposalType.SETTING_OPTIMIZATION -> Unit
        }

        val updated = _proposals.value.filter { it.id != proposalId }
        _proposals.value = updated
        saveToDisk(updated)
        true
    }

    suspend fun dismissProposal(proposalId: String) = withContext(Dispatchers.IO) {
        val updated = _proposals.value.filter { it.id != proposalId }
        _proposals.value = updated
        saveToDisk(updated)
    }

    private fun saveToDisk(list: List<Proposal>) {
        runCatching {
            file.writeText(json.encodeToString(ListSerializer(Proposal.serializer()), list))
        }
    }
}
