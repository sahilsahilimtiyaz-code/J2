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

    /** Returns any duplicate tool names in [tools], or empty list when all names are unique. */
    fun duplicateToolNames(): List<String> =
        tools.groupBy { it.definition.name }
            .filter { it.value.size > 1 }
            .keys
            .toList()

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
                    argumentsJson = argumentsJson.take(500),
                ),
            )
            return ToolResult(false, blocked)
        }
        val arguments = parseArguments(argumentsJson)
            ?: return ToolResult(false, "The tool arguments were not valid JSON.")

        val undoJson = runCatching { captureUndoArguments(name, arguments) }.getOrDefault("")

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
                argumentsJson = argumentsJson.take(500),
                undoArgumentsJson = if (result.success) undoJson else "",
            ),
        )

        return result
    }

    private fun captureUndoArguments(name: String, arguments: JsonObject): String {
        val ctx = toolContext.appContext
        return when (name) {
            "set_volume" -> {
                val audio = ctx.getSystemService(android.media.AudioManager::class.java) ?: return ""
                val streamName = arguments.argString("stream")?.lowercase() ?: "media"
                val stream = when (streamName) {
                    "ring", "ringer" -> android.media.AudioManager.STREAM_RING
                    "alarm" -> android.media.AudioManager.STREAM_ALARM
                    "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
                    else -> android.media.AudioManager.STREAM_MUSIC
                }
                val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                val currentPct = (audio.getStreamVolume(stream) * 100) / max
                buildJsonObject {
                    put("level", currentPct)
                    put("stream", streamName)
                }.toString()
            }
            "set_ringer_mode" -> {
                val audio = ctx.getSystemService(android.media.AudioManager::class.java) ?: return ""
                val prevMode = when (audio.ringerMode) {
                    android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
                    android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "sound"
                }
                buildJsonObject { put("mode", prevMode) }.toString()
            }
            "control_flashlight" -> {
                buildJsonObject { put("on", TorchState.isOn) }.toString()
            }
            "set_do_not_disturb" -> {
                val modeArg = arguments.argString("mode")?.lowercase() ?: "status"
                if (modeArg == "status") return ""
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return ""
                val prevMode = when (nm.currentInterruptionFilter) {
                    android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                    android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                    android.app.NotificationManager.INTERRUPTION_FILTER_NONE -> "silence"
                    else -> "off"
                }
                buildJsonObject { put("mode", prevMode) }.toString()
            }
            "set_screen_brightness" -> {
                if (arguments.argInt("level") == null && arguments.argBoolean("auto") == null) return ""
                val cr = ctx.contentResolver
                val currentRaw = runCatching {
                    android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS)
                }.getOrDefault(128)
                val currentMode = runCatching {
                    android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE)
                }.getOrDefault(android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                val isAuto = currentMode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                val currentPct = (currentRaw * 100 / 255).coerceIn(0, 100)
                buildJsonObject {
                    put("level", currentPct)
                    put("auto", isAuto)
                }.toString()
            }
            else -> ""
        }
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
    CaptureScreenSnapshotTool(),
    ExecuteMobileUiActionTool(),
    ReadScreenTool(),
    TapScreenTool(),
    TapVideoItemTool(),
    TapCoordinatesTool(),
    TypeOnScreenTool(),
    ScrollScreenTool(),
    SystemNavigationTool(),
    DumpScreenTool(),
)

