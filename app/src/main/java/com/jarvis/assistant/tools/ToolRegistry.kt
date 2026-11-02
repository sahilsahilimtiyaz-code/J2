package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.brain.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolCategory(val label: String) {
    APPS("Apps"),
    MEDIA("Media"),
    TIME("Time"),
    CONTACTS("Calls & SMS"),
    DEVICE("Device"),
    INFO("Info"),
}

data class ToolAvailability(
    val label: String,
    val ready: Boolean,
)

/**
 * Asks the owner to approve an action that touches the outside world (a call,
 * a message). Suspends until the owner answers, so a tool never acts on its own.
 */
interface ConfirmationGate {
    suspend fun confirm(title: String, detail: String): Boolean
}

/** Requests an Android runtime permission and suspends until the owner answers. */
interface PermissionGate {
    fun isGranted(permission: String): Boolean
    suspend fun ensure(permission: String, rationale: String): Boolean
}

data class ToolContext(
    val appContext: Context,
    val confirmation: ConfirmationGate,
    val permissions: PermissionGate,
    val memory: MemoryBridge? = null,
    val screen: ScreenAccess? = null,
    val skills: SkillBridge? = null,
)

data class ToolResult(
    val success: Boolean,
    val content: String,
    /** Optional JPEG screenshot attached so the model can literally see the screen. */
    val image: ByteArray? = null,
) {
    fun asJsonString(): String = buildJsonObject {
        put("ok", success)
        put("result", content)
    }.toString()
}

interface Tool {
    val definition: ToolDefinition

    /** Short human name used in the Tools screen and the action log. */
    val label: String get() = definition.name
    val category: ToolCategory get() = ToolCategory.DEVICE

    fun availability(context: Context): ToolAvailability = ToolAvailability("Ready", true)

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/** One row of the Tools screen, computed from the real tool and the real device. */
data class ToolSummary(
    val name: String,
    val label: String,
    val description: String,
    val category: String,
    val status: String,
    val statusReady: Boolean,
)

interface ToolExecutor {
    fun definitions(): List<ToolDefinition>
    suspend fun execute(name: String, argumentsJson: String): ToolResult
}

class ToolRegistry(
    context: Context,
    private val confirmation: ConfirmationGate = NeverConfirm,
    private val permissions: PermissionGate = NoPermissions,
    private val actionLog: ActionLogStore? = null,
    memory: MemoryBridge? = null,
    screen: ScreenAccess? = null,
    skills: SkillBridge? = null,
    private val tools: List<Tool> = defaultTools(),
) : ToolExecutor {

    val toolContext = ToolContext(
        appContext = context.applicationContext,
        confirmation = confirmation,
        permissions = permissions,
        memory = memory,
        screen = screen,
        skills = skills,
    )

    override fun definitions(): List<ToolDefinition> = tools.map { it.definition }

    /** Everything the Tools screen shows, with availability read from this device. */
    fun catalogue(): List<ToolSummary> = tools.map { tool ->
        val availability = runCatching { tool.availability(toolContext.appContext) }
            .getOrElse { ToolAvailability("Unavailable", false) }
        ToolSummary(
            name = tool.definition.name,
            label = tool.label,
            description = tool.definition.description,
            category = tool.category.label,
            status = availability.label,
            statusReady = availability.ready,
        )
    }

    override suspend fun execute(name: String, argumentsJson: String): ToolResult {
        val tool = tools.firstOrNull { it.definition.name == name }
            ?: return ToolResult(false, "Unknown tool: $name")
        com.jarvis.assistant.security.SecurityShield.inspectToolCall(name, argumentsJson)?.let { blocked ->
            actionLog?.record(
                ActionLogEntry(
                    id = "blocked_${System.currentTimeMillis()}_${name.hashCode().toUInt()}",
                    toolName = name,
                    label = "Security Shield",
                    detail = blocked.take(220),
                    success = false,
                    timestamp = System.currentTimeMillis(),
                ),
            )
            return ToolResult(false, blocked)
        }
        val arguments = parseArguments(argumentsJson)
            ?: return ToolResult(false, "The tool arguments were not valid JSON.")

        val result = try {
            // Package queries, contact lookups and the camera all touch the
            // system, so no tool ever runs on the main thread.
            withContext(Dispatchers.IO) { tool.execute(arguments, toolContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolResult(false, "Android blocked that action: ${e.message ?: "missing permission"}.")
        } catch (e: Exception) {
            ToolResult(false, "Tool failed: ${e.message?.take(300) ?: "unknown error"}")
        }

        actionLog?.record(
            ActionLogEntry(
                id = "action_${System.currentTimeMillis()}_${name.hashCode().toUInt()}",
                toolName = name,
                label = tool.label,
                detail = result.content.take(220),
                success = result.success,
                timestamp = System.currentTimeMillis(),
            ),
        )

        return result
    }

    private fun parseArguments(raw: String): JsonObject? {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return com.jarvis.assistant.brain.parseJsonObject(raw)
    }

    private object NeverConfirm : ConfirmationGate {
        override suspend fun confirm(title: String, detail: String): Boolean = false
    }

    private object NoPermissions : PermissionGate {
        override fun isGranted(permission: String): Boolean = false
        override suspend fun ensure(permission: String, rationale: String): Boolean = false
    }
}

fun defaultTools(): List<Tool> = listOf(
    // Apps
    OpenAppTool(),
    ListInstalledAppsTool(),
    OpenUrlTool(),
    // Media
    YouTubeSearchTool(),
    WebSearchTool(),
    OpenCameraTool(),
    MediaControlTool(),
    VolumeTool(),
    RingerTool(),
    FlashlightTool(),
    // Time
    SetTimerTool(),
    SetAlarmTool(),
    // Calls and messages
    CallContactTool(),
    SendSmsTool(),
    SendWhatsAppTool(),
    SendWhatsAppVoiceNoteTool(),
    AnswerWhatsAppCallTool(),
    EndWhatsAppCallTool(),
    // Intents
    NavigateTool(),
    CalendarEventTool(),
    ShareTextTool(),
    OpenSettingsScreenTool(),
    // Device and info
    DeviceStatusTool(),
    CurrentTimeTool(),
    ClipboardTool(),
    TakeScreenshotTool(),
    // Tier 2: Notifications
    ReadNotificationsTool(),
    DismissNotificationsTool(),
    // Memory (Phase 4)
    RememberTool(),
    SearchMemoryTool(),
    ForgetMemoryTool(),
    // Declarative Skills (Phase 7)
    SaveSkillTool(),
    RunSkillTool(),
    ListSkillsTool(),
    DeleteSkillTool(),
    // Reminders & Exact Briefings (Phase 7)
    ScheduleReminderTool(),
    ScheduleMorningBriefingTool(),
    // Google Workspace (Phase 9)
    ReadCalendarTool(),
    CreateCalendarEventTool(),
    ComposeEmailTool(),
    // Device Diagnostics & Hardware Telemetry (Part 2)
    DeviceDiagnosticsTool(),
    StorageAnalysisTool(),
    DoNotDisturbTool(),
    ScreenBrightnessTool(),
    AppInspectorTool(),
    // Optical Screen Vision (Part 2)
    AnalyzeScreenVisionTool(),
    // True vision: capture the real pixels so the model can SEE the screen.
    SeeScreenTool(),
    // Camera photo capture (Tier 1)
    TakePhotoTool(),
    // Screen control (Tier 3, requires the accessibility service)
    AutomateScreenTool(),
    ReadScreenTool(),
    TapScreenTool(),
    TapVideoItemTool(),
    TapCoordinatesTool(),
    TypeOnScreenTool(),
    ScrollScreenTool(),
    SystemNavigationTool(),
)
