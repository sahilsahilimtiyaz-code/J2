package com.jarvis.assistant.skills

import com.jarvis.assistant.memory.Proposal
import com.jarvis.assistant.memory.ProposalType
import com.jarvis.assistant.memory.SkillDefinition
import com.jarvis.assistant.memory.SkillStep
import com.jarvis.assistant.tools.defaultTools
import com.jarvis.assistant.voice.WhisperSttEngine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsAndToolsTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `SkillDefinition serializes and deserializes accurately with steps and triggers`() {
        val skill = SkillDefinition(
            id = 1L,
            name = "Morning Routine",
            description = "Checks battery, notifications, and queues music",
            triggers = listOf("morning routine", "start my day", "wake up jarvis"),
            steps = listOf(
                SkillStep(toolName = "read_battery_status", description = "Check battery power"),
                SkillStep(toolName = "read_notifications", description = "Brief on unread alerts"),
                SkillStep(
                    toolName = "search_youtube",
                    arguments = mapOf("query" to "morning lo-fi beats"),
                    description = "Play morning music",
                ),
            ),
        )

        val serializedSteps = json.encodeToString(ListSerializer(SkillStep.serializer()), skill.steps)
        val deserializedSteps = json.decodeFromString(ListSerializer(SkillStep.serializer()), serializedSteps)

        assertEquals(3, deserializedSteps.size)
        assertEquals("read_battery_status", deserializedSteps[0].toolName)
        assertEquals("search_youtube", deserializedSteps[2].toolName)
        assertEquals("morning lo-fi beats", deserializedSteps[2].arguments["query"])
    }

    @Test
    fun `WhisperSttEngine generates valid RIFF WAVE header for PCM audio`() {
        val dummyPcm = ByteArray(3200) // 100ms at 16kHz 16-bit mono
        val wavBytes = WhisperSttEngine.pcmToWav(dummyPcm, sampleRate = 16000, channels = 1)

        assertEquals(44 + dummyPcm.size, wavBytes.size)

        // Check RIFF magic
        val riffMagic = String(wavBytes.copyOfRange(0, 4), Charsets.US_ASCII)
        assertEquals("RIFF", riffMagic)

        // Check WAVE magic
        val waveMagic = String(wavBytes.copyOfRange(8, 12), Charsets.US_ASCII)
        assertEquals("WAVE", waveMagic)

        // Check fmt chunk
        val fmtMagic = String(wavBytes.copyOfRange(12, 16), Charsets.US_ASCII)
        assertEquals("fmt ", fmtMagic)

        // Check data chunk
        val dataMagic = String(wavBytes.copyOfRange(36, 40), Charsets.US_ASCII)
        assertEquals("data", dataMagic)
    }

    @Test
    fun `ToolRegistry defaultTools includes declarative skills, reminders, and calendar tools`() {
        val tools = defaultTools()
        val toolNames = tools.map { it.definition.name }.toSet()

        // Skills tools
        assertTrue(toolNames.contains("save_skill"))
        assertTrue(toolNames.contains("run_skill"))
        assertTrue(toolNames.contains("list_skills"))
        assertTrue(toolNames.contains("delete_skill"))

        // Reminders & alarms
        assertTrue(toolNames.contains("schedule_reminder"))
        assertTrue(toolNames.contains("schedule_morning_briefing"))

        // Google Workspace tools
        assertTrue(toolNames.contains("read_calendar_events"))
        assertTrue(toolNames.contains("create_calendar_event"))
        assertTrue(toolNames.contains("compose_email"))
    }

    @Test
    fun `Proposal serialization retains before and after text for user approval`() {
        val proposal = Proposal(
            id = "prop_123",
            type = ProposalType.PERSONA_PROMPT,
            title = "Polished Greeting",
            rationale = "Cleaner morning brevity",
            beforeText = "Good morning.",
            afterText = "Good morning, sir. All systems nominal.",
        )

        val encoded = json.encodeToString(Proposal.serializer(), proposal)
        val decoded = json.decodeFromString(Proposal.serializer(), encoded)

        assertEquals(proposal.id, decoded.id)
        assertEquals(ProposalType.PERSONA_PROMPT, decoded.type)
        assertEquals("Good morning.", decoded.beforeText)
        assertEquals("Good morning, sir. All systems nominal.", decoded.afterText)
    }

    @Test
    fun `ToolRegistry defaultTools includes Part 2 diagnostics and screen vision tools`() {
        val tools = defaultTools()
        val toolNames = tools.map { it.definition.name }.toSet()

        assertTrue(toolNames.contains("run_diagnostics"))
        assertTrue(toolNames.contains("analyze_storage"))
        assertTrue(toolNames.contains("set_do_not_disturb"))
        assertTrue(toolNames.contains("set_screen_brightness"))
        assertTrue(toolNames.contains("inspect_app"))
        assertTrue(toolNames.contains("analyze_screen"))
        assertTrue(toolNames.contains("tap_video_item"))
        assertTrue(toolNames.contains("tap_screen_coordinates"))
    }

    @Test
    fun `MasterJarvisBackup serializes and deserializes accurately with full state`() {
        val backup = com.jarvis.assistant.settings.MasterJarvisBackup(
            formatVersion = 2,
            exportedAt = 1730419200000L,
            appVersion = "2.0.0",
            profile = com.jarvis.assistant.settings.BackupProfile(
                name = "Octavian",
                title = "sir",
                occupation = "Software Engineer",
                tone = "WITTY",
                developer = "Octavian",
            ),
            voice = com.jarvis.assistant.settings.BackupVoice(
                provider = "FISH_AUDIO",
                speed = 1.05f,
                fallbackToSystem = true,
                soundFxEnabled = true,
                language = "en-US",
            ),
            skills = listOf(
                com.jarvis.assistant.settings.BackupSkill(
                    name = "Quick Diagnostic",
                    description = "Runs battery check",
                    triggers = listOf("diagnostics", "check systems"),
                    steps = listOf(SkillStep(toolName = "run_diagnostics")),
                    enabled = true,
                ),
            ),
            memories = listOf(
                com.jarvis.assistant.settings.BackupMemory(
                    text = "Owner prefers concise telemetry readouts",
                    type = "fact",
                    importance = 4,
                    createdAt = 1730419200000L,
                ),
            ),
            facts = mapOf("developer" to "Octavian", "title" to "sir"),
        )

        val jsonStr = json.encodeToString(com.jarvis.assistant.settings.MasterJarvisBackup.serializer(), backup)
        val decoded = json.decodeFromString(com.jarvis.assistant.settings.MasterJarvisBackup.serializer(), jsonStr)

        assertEquals("Octavian", decoded.profile.developer)
        assertEquals("FISH_AUDIO", decoded.voice.provider)
        assertEquals(1, decoded.skills.size)
        assertEquals("run_diagnostics", decoded.skills[0].steps[0].toolName)
        assertEquals(1, decoded.memories.size)
        assertEquals("Octavian", decoded.facts["developer"])
    }
}
