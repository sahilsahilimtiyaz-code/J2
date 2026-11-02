package com.jarvis.assistant.brain

import com.jarvis.assistant.memory.SkillRepository
import com.jarvis.assistant.memory.SkillStep
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1IntelligenceTest {

    private fun def(name: String, description: String) = ToolDefinition(
        name = name,
        description = description,
        parameters = JsonObject(emptyMap()),
    )

    private val sampleTools = listOf(
        def("get_current_time", "Get current time and date"),
        def("get_device_status", "Get battery, memory, storage, and network status"),
        def("run_diagnostics", "Run full system diagnostics"),
        def("read_notifications", "Read recent notifications"),
        def("search_youtube", "Search YouTube videos"),
        def("web_search", "Search the web for real-time news and weather"),
        def("read_calendar_events", "Read upcoming calendar events"),
        def("schedule_reminder", "Schedule a future reminder"),
        def("remember_about_owner", "Save a fact to memory"),
        def("search_memory", "Recall facts from memory"),
        def("run_skill", "Run a saved skill macro"),
    )

    @Test
    fun `shouldCreatePlan detects multi-step objectives and ignores simple questions`() {
        assertTrue(TaskPlanner.shouldCreatePlan("Jarvis, prepare everything for my trip tomorrow"))
        assertTrue(TaskPlanner.shouldCreatePlan("Plan my morning routine and check battery"))
        assertFalse(TaskPlanner.shouldCreatePlan("What time is it?"))
    }

    @Test
    fun `buildPlanForObjective creates trip preparation steps with real tools`() {
        val availableNames = sampleTools.map { it.name }.toSet()
        val plan = TaskPlanner.buildPlanForObjective(
            objective = "Prepare everything for my trip tomorrow",
            availableTools = availableNames,
        )
        assertTrue(plan.steps.size >= 4)
        assertTrue(plan.steps.any { it.toolName == "read_calendar_events" })
        assertTrue(plan.steps.any { it.toolName == "web_search" })
        assertTrue(plan.steps.any { it.toolName == "remember_about_owner" })
    }

    @Test
    fun `parsePlanJson parses valid JSON array and filters unknown tools`() {
        val availableNames = sampleTools.map { it.name }.toSet()
        val rawJson = """
            [
              {"title": "Check time", "tool": "get_current_time", "arguments": {}},
              {"title": "Unknown step", "tool": "non_existent_tool", "arguments": {}}
            ]
        """.trimIndent()

        val parsed = TaskPlanner.parsePlanJson("Test objective", rawJson, availableNames)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.steps.size)
        assertEquals("get_current_time", parsed.steps[0].toolName)
    }

    @Test
    fun `ToolContextBudgeter enforces maxTools budget and keeps core and matched tools`() {
        val manyTools = sampleTools + (1..30).map { idx ->
            def("extra_tool_$idx", "Extra utility number $idx")
        }
        val budgeted = ToolContextBudgeter.selectToolsForTurn(
            userMessage = "Search YouTube for lo-fi music and check my notifications",
            allTools = manyTools,
            maxTools = 16,
        )
        assertTrue(budgeted.size <= 16)
        val names = budgeted.map { it.name }.toSet()
        assertTrue("get_current_time" in names)
        assertTrue("search_youtube" in names)
        assertTrue("read_notifications" in names)
    }

    @Test
    fun `SkillRepository validateSteps catches missing tools and accepts valid tools`() {
        val availableNames = sampleTools.map { it.name }.toSet()
        val validSteps = listOf(
            SkillStep("get_device_status", emptyMap()),
            SkillStep("get_current_time", emptyMap()),
            SkillStep("run_diagnostics", emptyMap()),
        )
        org.junit.Assert.assertNull(SkillRepository.validateSteps(validSteps, availableNames))

        val invalidSteps = listOf(
            SkillStep("read_battery_status", emptyMap()),
            SkillStep("read_time", emptyMap()),
        )
        val err = SkillRepository.validateSteps(invalidSteps, availableNames)
        assertNotNull(err)
        assertTrue(err!!.contains("read_battery_status"))
        assertTrue(err.contains("read_time"))
    }

    @Test
    fun `AuthSession tracks Google and Guest authentication modes and derives display name`() {
        val defaultAuth = com.jarvis.assistant.settings.AuthSession()
        assertFalse(defaultAuth.isAuthenticated)

        val googleAuth = com.jarvis.assistant.settings.AuthSession(
            mode = com.jarvis.assistant.settings.AuthMode.GOOGLE,
            email = "tony.stark@gmail.com",
            displayName = com.jarvis.assistant.ui.deriveDisplayNameFromEmail("tony.stark@gmail.com"),
            signedInAt = 1000L,
        )
        assertTrue(googleAuth.isAuthenticated)
        assertEquals("Tony Stark", googleAuth.displayName)

        val guestAuth = com.jarvis.assistant.settings.AuthSession(
            mode = com.jarvis.assistant.settings.AuthMode.GUEST,
            displayName = "Guest",
        )
        assertTrue(guestAuth.isAuthenticated)
    }

    @Test
    fun `JarvisNotificationListenerService resolves latest WhatsApp sender for voice note replies`() {
        com.jarvis.assistant.service.JarvisNotificationListenerService.recordWhatsAppMessage(
            com.jarvis.assistant.service.WhatsAppIncomingMessage(
                sender = "Pepper Potts",
                body = "Are you coming to the Stark Expo tonight?",
                key = "wa_1",
                postedAt = 100L,
                packageName = "com.whatsapp",
            ),
        )
        val latest = com.jarvis.assistant.service.JarvisNotificationListenerService.latestWhatsAppMessage("last")
        assertNotNull(latest)
        assertEquals("Pepper Potts", latest!!.sender)
        assertEquals("Are you coming to the Stark Expo tonight?", latest.body)
    }
}
