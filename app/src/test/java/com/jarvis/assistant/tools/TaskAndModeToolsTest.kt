package com.jarvis.assistant.tools

import com.jarvis.assistant.core.JarvisOperatingMode
import com.jarvis.assistant.core.TaskPriority
import com.jarvis.assistant.core.TaskRecurrence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAndModeToolsTest {

    @Test
    fun testJarvisOperatingModeEnumValues() {
        assertEquals("default", JarvisOperatingMode.DEFAULT.id)
        assertEquals("study", JarvisOperatingMode.STUDY.id)
        assertEquals("research", JarvisOperatingMode.RESEARCH.id)
        assertEquals("tasks", JarvisOperatingMode.TASKS.id)
        assertEquals("agentic", JarvisOperatingMode.AGENTIC.id)

        assertEquals(JarvisOperatingMode.STUDY, JarvisOperatingMode.fromId("study"))
        assertEquals(JarvisOperatingMode.RESEARCH, JarvisOperatingMode.fromId("RESEARCH"))
        assertEquals(JarvisOperatingMode.TASKS, JarvisOperatingMode.fromId("tasks"))
        assertEquals(JarvisOperatingMode.AGENTIC, JarvisOperatingMode.fromId("agentic"))
        assertEquals(JarvisOperatingMode.DEFAULT, JarvisOperatingMode.fromId("unknown"))
    }

    @Test
    fun testTaskPriorityAndRecurrenceParsing() {
        assertEquals(TaskPriority.HIGH, TaskPriority.fromString("urgent"))
        assertEquals(TaskPriority.HIGH, TaskPriority.fromString("high"))
        assertEquals(TaskPriority.LOW, TaskPriority.fromString("minor"))
        assertEquals(TaskPriority.MEDIUM, TaskPriority.fromString("normal"))

        assertEquals(TaskRecurrence.DAILY, TaskRecurrence.fromString("daily"))
        assertEquals(TaskRecurrence.WEEKLY, TaskRecurrence.fromString("weekly"))
        assertEquals(TaskRecurrence.NONE, TaskRecurrence.fromString("none"))
    }

    private fun JsonObject.hasProperty(name: String): Boolean =
        (this["properties"] as? JsonObject)?.containsKey(name) == true

    @Test
    fun testToolDefinitionsExistAndHaveCorrectNames() {
        val modeTool = SetOperatingModeTool()
        assertEquals("set_operating_mode", modeTool.definition.name)
        assertTrue(modeTool.definition.parameters.hasProperty("mode"))

        val scheduleTool = ScheduleTaskTool()
        assertEquals("schedule_task", scheduleTool.definition.name)
        assertTrue(scheduleTool.definition.parameters.hasProperty("title"))

        val listTool = ListScheduledTasksTool()
        assertEquals("list_scheduled_tasks", listTool.definition.name)

        val completeTool = CompleteScheduledTaskTool()
        assertEquals("complete_scheduled_task", completeTool.definition.name)
        assertTrue(completeTool.definition.parameters.hasProperty("task_id_or_title"))
    }

    @Test
    fun testAdvancedWebSearchToolsDefinitions() {
        val deepResearch = DeepResearchTool()
        assertEquals("deep_research", deepResearch.definition.name)
        assertTrue(deepResearch.definition.parameters.hasProperty("query"))
        assertTrue(deepResearch.definition.parameters.hasProperty("depth"))

        val fetchPage = FetchWebPageTool()
        assertEquals("fetch_web_page", fetchPage.definition.name)
        assertTrue(fetchPage.definition.parameters.hasProperty("url"))

        val factCheck = FactCheckSearchTool()
        assertEquals("fact_check", factCheck.definition.name)
        assertTrue(factCheck.definition.parameters.hasProperty("claim"))
    }

    @Test
    fun testDefaultToolsIncludesNewTools() {
        val tools = defaultTools()
        val toolNames = tools.map { it.definition.name }.toSet()

        assertTrue(toolNames.contains("set_operating_mode"))
        assertTrue(toolNames.contains("schedule_task"))
        assertTrue(toolNames.contains("list_scheduled_tasks"))
        assertTrue(toolNames.contains("complete_scheduled_task"))
        assertTrue(toolNames.contains("deep_research"))
        assertTrue(toolNames.contains("fetch_web_page"))
        assertTrue(toolNames.contains("fact_check"))
    }
}
