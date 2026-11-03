package com.jarvis.assistant.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPersonaBriefingEngineTest {

    @Test
    fun testMorningBriefingSynthesizesCleanSpeechWithoutRawLogs() {
        val step1 = StepResult(
            stepIndex = 1,
            toolName = "get_device_status",
            success = true,
            output = "Battery 76%, 25°C. Network: Wi-Fi. Storage 67% used, 34.3 GB free. Memory 69% used. Ringer: Sound on. Nothing is playing.",
        )
        val step2 = StepResult(
            stepIndex = 2,
            toolName = "read_notifications",
            success = true,
            output = "2 active notification(s):\n1. [Instagram at 14:38] dxh7_loves_furry_famboys: replied to your comment on trevorlarcom's post: And wht about it?\n2. [Google at 14:38] 18° in Bandipore: Mostly cloudy · See full forecast",
        )
        val step3 = StepResult(
            stepIndex = 3,
            toolName = "search_youtube",
            success = true,
            output = "Now playing \"Morning Coffee ☕️ [lofi hip hop]\" on YouTube and playback is active.",
        )

        val briefing = SkillPersonaBriefingEngine.generateSpokenBriefing(
            skillName = "Morning Routine",
            stepResults = listOf(step1, step2, step3),
            ownerTitle = "sir",
            success = true,
            currentHour = 8,
        )

        // Must be in true J.A.R.V.I.S. persona
        assertTrue(briefing.startsWith("Good morning, sir."))
        assertTrue(briefing.contains("76%"))
        assertTrue(briefing.contains("Wi-Fi"))
        assertTrue(briefing.contains("Instagram"))
        assertTrue(briefing.contains("Bandipore"))
        assertTrue(briefing.contains("Morning Coffee"))

        // Must NEVER contain developer debug logs or internal prompts
        assertFalse(briefing.contains("Skill 'Morning Routine'"))
        assertFalse(briefing.contains("completed all"))
        assertFalse(briefing.contains("Step 1 (get_device_status)"))
        assertFalse(briefing.contains("Inform the owner now"))
        assertFalse(briefing.contains("25°C"))
        assertFalse(briefing.contains("Storage 67%"))
    }

    @Test
    fun testDiagnosticsBriefingIsConciseAndDignified() {
        val step1 = StepResult(
            stepIndex = 1,
            toolName = "get_device_status",
            success = true,
            output = "Battery 82%, 26°C. Network: Wi-Fi. Storage 55% used.",
        )
        val step2 = StepResult(
            stepIndex = 2,
            toolName = "run_diagnostics",
            success = true,
            output = "RAM load: 45%. System health: Optimal. Uptime: 48h.",
        )

        val briefing = SkillPersonaBriefingEngine.generateSpokenBriefing(
            skillName = "Device Diagnostics",
            stepResults = listOf(step1, step2),
            ownerTitle = "sir",
            success = true,
            currentHour = 14,
        )

        assertTrue(briefing.contains("diagnostics have passed, sir"))
        assertTrue(briefing.contains("Battery is at 82%"))
        assertFalse(briefing.contains("completed all 2 steps"))
    }

    @Test
    fun testFailedStepIsReportedPolitely() {
        val step1 = StepResult(
            stepIndex = 1,
            toolName = "get_device_status",
            success = false,
            output = "Permission denied",
        )

        val briefing = SkillPersonaBriefingEngine.generateSpokenBriefing(
            skillName = "Morning Routine",
            stepResults = listOf(step1),
            ownerTitle = "sir",
            success = false,
            currentHour = 9,
        )

        assertTrue(briefing.contains("encountered an issue"))
        assertFalse(briefing.contains("completed all"))
    }
}
