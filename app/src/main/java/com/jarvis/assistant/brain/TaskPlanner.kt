package com.jarvis.assistant.brain

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class PlanStepStatus {
    PENDING,
    RUNNING,
    AWAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    SKIPPED,
}

@Serializable
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
data class PlanStep(
    val id: String,
    val index: Int,
    val title: String,
    val toolName: String,
    val argumentsJson: String = "{}",
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val output: String = "",
    val verified: Boolean = false,
    val attempts: Int = 0,
)

@Serializable
data class TaskPlan(
    val id: String,
    val objective: String,
    val createdAt: Long = System.currentTimeMillis(),
    val steps: List<PlanStep>,
    val isRunning: Boolean = false,
    val isCancelled: Boolean = false,
    val summary: String = "",
) {
    val completedCount: Int
        get() = steps.count { it.status == PlanStepStatus.COMPLETED }

    val failedCount: Int
        get() = steps.count { it.status == PlanStepStatus.FAILED }

    val progressFraction: Float
        get() = if (steps.isEmpty()) 0f else completedCount.toFloat() / steps.size.toFloat()
}

/**
 * Represents content shared into J.A.R.V.I.S. from the Android Share Sheet
 * (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) or contextual text selection (`ACTION_PROCESS_TEXT`).
 */
data class SharedContentPayload(
    val id: String = "share_${System.currentTimeMillis()}",
    val mimeType: String,
    val title: String,
    val textOrUrl: String = "",
    val uriString: String = "",
    val extractedPreview: String = "",
    val imageBytes: ByteArray? = null,
)

/**
 * Context-aware tool selector that trims the tool schema list sent on each LLM turn
 * to the core everyday tools plus domain-relevant tools for the user's request,
 * preventing 55-tool schema bloat while keeping every tool executable in `ToolRegistry`.
 */
object ToolContextBudgeter {

    private val ALWAYS_INCLUDED_TOOLS = setOf(
        "open_app",
        "web_search",
        "search_youtube",
        "get_device_status",
        "get_current_time",
        "remember_about_owner",
        "search_memory",
        "schedule_reminder",
        "set_timer",
        "set_alarm",
        "read_notifications",
        "read_calendar_events",
        "create_calendar_event",
        "run_skill",
        "save_skill",
        "automate_screen",
        "see_screen",
        "tap_screen",
        "type_on_screen",
        "run_diagnostics",
    )

    private val DOMAIN_KEYWORDS: Map<String, Set<String>> = mapOf(
        "call_contact" to setOf("call", "phone", "dial", "ring"),
        "send_sms" to setOf("sms", "text", "message"),
        "send_whatsapp_message" to setOf("whatsapp", "message", "chat", "text"),
        "send_whatsapp_voice_note" to setOf("whatsapp", "voice", "note", "audio"),
        "answer_whatsapp_call" to setOf("whatsapp", "answer", "call", "pickup"),
        "end_whatsapp_call" to setOf("whatsapp", "hang", "end", "call"),
        "compose_email" to setOf("email", "gmail", "mail", "draft", "send"),
        "schedule_calendar_event" to setOf("calendar", "schedule", "meeting", "appointment", "event", "trip"),
        "schedule_morning_briefing" to setOf("morning", "briefing", "wake", "daily"),
        "control_flashlight" to setOf("flashlight", "torch", "light", "lamp"),
        "set_volume" to setOf("volume", "louder", "quieter", "mute", "audio", "sound"),
        "set_ringer_mode" to setOf("ringer", "vibrate", "silent", "sound", "mute"),
        "set_do_not_disturb" to setOf("dnd", "disturb", "focus", "silence", "quiet", "priority"),
        "set_screen_brightness" to setOf("brightness", "dim", "bright", "display", "screen"),
        "control_media" to setOf("music", "play", "pause", "next", "skip", "previous", "stop", "track", "song"),
        "navigate_to" to setOf("navigate", "map", "directions", "route", "drive", "where", "trip"),
        "share_text" to setOf("share", "send", "export"),
        "open_settings_screen" to setOf("settings", "wifi", "bluetooth", "display", "battery", "accessibility"),
        "open_url" to setOf("http", "www", "url", "website", "link", "browser", "open"),
        "list_installed_apps" to setOf("apps", "installed", "packages", "list"),
        "inspect_app" to setOf("inspect", "app", "version", "permission", "package"),
        "analyze_storage" to setOf("storage", "disk", "space", "free", "memory", "gb"),
        "clipboard" to setOf("clipboard", "copy", "paste"),
        "take_screenshot" to setOf("screenshot", "capture", "screen"),
        "take_photo" to setOf("photo", "picture", "camera", "selfie", "snap"),
        "open_camera" to setOf("camera", "video", "record", "photo"),
        "dismiss_notifications" to setOf("dismiss", "clear", "notifications", "alerts"),
        "forget_memory" to setOf("forget", "delete", "erase", "remove", "memory"),
        "save_skill" to setOf("skill", "routine", "macro", "save", "automate"),
        "list_skills" to setOf("skills", "routines", "macros", "list"),
        "delete_skill" to setOf("delete", "remove", "skill", "routine"),
        "analyze_screen" to setOf("screen", "vision", "ocr", "read", "analyze", "look"),
        "read_screen" to setOf("screen", "read", "what", "showing", "page"),
        "capture_screen_snapshot" to setOf("snapshot", "screen", "elements", "ui", "tree"),
        "execute_mobile_ui_action" to setOf("action", "tap", "type", "scroll", "swipe", "screen"),
        "tap_video_item" to setOf("video", "youtube", "play", "first", "second", "watch"),
        "tap_screen_coordinates" to setOf("tap", "click", "coordinate", "x", "y", "screen"),
        "type_on_screen" to setOf("type", "write", "enter", "input", "search", "screen", "youtube"),
        "scroll_screen" to setOf("scroll", "down", "up", "swipe", "screen"),
        "press_system_key" to setOf("back", "home", "recents", "notifications", "navigate"),
    )

    fun selectToolsForTurn(
        userMessage: String,
        allTools: List<ToolDefinition>,
        maxTools: Int = 28,
    ): List<ToolDefinition> {
        if (allTools.size <= maxTools) return allTools
        val lower = userMessage.lowercase(Locale.ROOT)
        val tokens = lower.split(Regex("[^a-z0-9_]+")).filter { it.length >= 2 }.toSet()

        val scored = allTools.map { def ->
            val isCore = def.name in ALWAYS_INCLUDED_TOOLS
            val domainWords = DOMAIN_KEYWORDS[def.name].orEmpty()
            val keywordHits = domainWords.count { kw -> kw in tokens || lower.contains(kw) }
            val nameMatch = if (lower.contains(def.name.replace('_', ' ')) || lower.contains(def.name)) 5 else 0
            val descHits = tokens.count { tok -> tok.length >= 4 && def.description.lowercase(Locale.ROOT).contains(tok) }
            val score = (if (isCore) 100 else 0) + (keywordHits * 15) + nameMatch + descHits
            def to score
        }

        return scored
            .sortedByDescending { it.second }
            .take(maxTools.coerceAtLeast(1))
            .map { it.first }
    }
}

/**
 * Multi-step AI Task Planner & Execution Engine for J.A.R.V.I.S.
 *
 * Breaks complex natural-language objectives into verifiable, risk-classified
 * steps with retry, confirmation, cancellation, and execution summary generation.
 */
object TaskPlanner {

    private val json = Json { ignoreUnknownKeys = true }

    private val HIGH_RISK_TOOLS = setOf(
        "call_contact",
        "send_sms",
        "send_whatsapp",
        "send_whatsapp_voice_note",
        "compose_email",
        "forget_memory",
        "dismiss_notifications",
        "delete_skill",
    )

    private val MEDIUM_RISK_TOOLS = setOf(
        "automate_screen",
        "tap_on_screen",
        "tap_screen_coordinates",
        "type_on_screen",
        "set_do_not_disturb",
        "set_ringer_mode",
        "set_volume",
        "set_screen_brightness",
        "control_flashlight",
        "create_calendar_event",
        "schedule_calendar_event",
        "schedule_reminder",
        "schedule_morning_briefing",
    )

    fun riskForTool(toolName: String): RiskLevel = when (toolName) {
        in HIGH_RISK_TOOLS -> RiskLevel.HIGH
        in MEDIUM_RISK_TOOLS -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    /**
     * Detects whether [userMessage] represents a complex multi-step objective
     * that benefits from a structured execution timeline.
     */
    fun shouldCreatePlan(userMessage: String): Boolean {
        val analysis = IntentGate.classify(userMessage)
        if (!analysis.isExecutableCommand) return false
        val lower = userMessage.trim().lowercase(Locale.ROOT)
        if (lower.length < 18) return false
        val triggerPhrases = listOf(
            "prepare everything for",
            "prepare for my trip",
            "plan my ",
            "plan and execute",
            "execute a plan",
            "morning briefing",
            "full system check",
            "step by step",
            "run diagnostics and",
            "check my calendar and",
            "check battery and",
        )
        if (triggerPhrases.any { lower.contains(it) }) return true

        // Multi-action conjunctions ("first ... then ...", or 2+ distinct action verbs)
        val hasSequentialMarkers = (lower.contains("first ") && lower.contains(" then ")) ||
            (lower.contains(" and then ")) ||
            (lower.contains(", then "))
        return hasSequentialMarkers
    }

    /**
     * Builds a deterministic, verifiable [TaskPlan] for multi-step objectives
     * without requiring an extra round-trip when standard phone tools apply.
     */
    fun buildPlanForObjective(
        objective: String,
        availableTools: Set<String>,
    ): TaskPlan {
        if (!IntentGate.classify(objective).isExecutableCommand) {
            return TaskPlan(
                id = "plan_${System.currentTimeMillis()}",
                objective = objective.trim(),
                steps = emptyList(),
                isRunning = false,
            )
        }
        val lower = objective.trim().lowercase(Locale.ROOT)
        val steps = mutableListOf<PlanStep>()

        fun addStep(title: String, toolName: String, args: JsonObject = JsonObject(emptyMap())) {
            if (toolName !in availableTools) return
            val idx = steps.size + 1
            steps += PlanStep(
                id = "step_${System.currentTimeMillis()}_$idx",
                index = idx,
                title = title,
                toolName = toolName,
                argumentsJson = args.toString(),
                riskLevel = riskForTool(toolName),
            )
        }

        when {
            lower.contains("trip") || lower.contains("travel") -> {
                addStep(
                    title = "Check upcoming calendar schedule for trip details",
                    toolName = "read_calendar_events",
                    args = buildJsonObject { put("days_ahead", 2) },
                )
                addStep(
                    title = "Search stored memories for travel preferences & packing notes",
                    toolName = "search_memory",
                    args = buildJsonObject { put("query", "trip travel flight hotel packing") },
                )
                val destQuery = extractAfterKeyword(objective, listOf("to ", "in ", "for "))
                    .ifBlank { "destination weather tomorrow" }
                addStep(
                    title = "Check weather forecast and travel advisories",
                    toolName = "web_search",
                    args = buildJsonObject { put("query", "weather forecast $destQuery") },
                )
                addStep(
                    title = "Check phone battery and connectivity readiness",
                    toolName = "get_device_status",
                )
                addStep(
                    title = "Save trip preparation checklist to memory",
                    toolName = "remember_about_owner",
                    args = buildJsonObject {
                        put("text", "Owner is preparing for an upcoming trip ($objective). Checklist: documents, charger, power bank, weather-appropriate attire, and itinerary.")
                        put("type", "project")
                        put("importance", 4)
                        put("tags", "trip,checklist")
                    },
                )
            }

            lower.contains("morning") && (lower.contains("briefing") || lower.contains("routine") || lower.contains("prepare")) -> {
                addStep(
                    title = "Verify current local time and date",
                    toolName = "get_current_time",
                )
                addStep(
                    title = "Check device battery health and network status",
                    toolName = "get_device_status",
                )
                addStep(
                    title = "Read today's calendar appointments",
                    toolName = "read_calendar_events",
                    args = buildJsonObject { put("days_ahead", 1) },
                )
                addStep(
                    title = "Read and summarize recent notifications",
                    toolName = "read_notifications",
                )
            }

            lower.contains("diagnostic") || lower.contains("health check") || lower.contains("system check") -> {
                addStep(
                    title = "Read battery, charging, and network status",
                    toolName = "get_device_status",
                )
                addStep(
                    title = "Run full hardware, RAM, and thermal diagnostics",
                    toolName = "run_diagnostics",
                )
                addStep(
                    title = "Analyze internal and external storage headroom",
                    toolName = "analyze_storage",
                )
            }

            else -> {
                // Parse multi-clause intents from the user's objective
                if (lower.contains("time") || lower.contains("date")) {
                    addStep("Check current time and date", "get_current_time")
                }
                if (lower.contains("battery") || lower.contains("status") || lower.contains("power")) {
                    addStep("Check device battery and network status", "get_device_status")
                }
                if (lower.contains("diagnostic") || lower.contains("ram") || lower.contains("cpu")) {
                    addStep("Run system hardware diagnostics", "run_diagnostics")
                }
                if (lower.contains("storage") || lower.contains("space")) {
                    addStep("Analyze storage usage", "analyze_storage")
                }
                if (lower.contains("calendar") || lower.contains("schedule") || lower.contains("meeting")) {
                    addStep(
                        "Read upcoming calendar events",
                        "read_calendar_events",
                        buildJsonObject { put("days_ahead", 2) },
                    )
                }
                if (lower.contains("notification") || lower.contains("alert") || lower.contains("unread")) {
                    addStep("Read recent notifications", "read_notifications")
                }
                if (lower.contains("weather") || lower.contains("news") || lower.contains("search")) {
                    addStep(
                        "Search live web information",
                        "web_search",
                        buildJsonObject { put("query", objective.take(120)) },
                    )
                }
                if (lower.contains("flashlight") || lower.contains("torch")) {
                    val turnOn = !lower.contains("off")
                    addStep(
                        if (turnOn) "Turn on flashlight" else "Turn off flashlight",
                        "control_flashlight",
                        buildJsonObject { put("on", turnOn) },
                    )
                }
                if (steps.isEmpty()) {
                    addStep("Check device status", "get_device_status")
                    addStep("Search relevant owner memories", "search_memory", buildJsonObject {
                        put("query", objective.take(80))
                    })
                }
            }
        }

        return TaskPlan(
            id = "plan_${System.currentTimeMillis()}",
            objective = objective.trim(),
            steps = steps,
            isRunning = false,
        )
    }

    /**
     * Parses a JSON array of steps (if generated by an LLM planner prompt) into a [TaskPlan].
     */
    fun parsePlanJson(
        objective: String,
        rawJson: String,
        availableTools: Set<String>,
    ): TaskPlan? = runCatching {
        val trimmed = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = json.parseToJsonElement(trimmed) as? JsonArray ?: return null
        val steps = array.mapIndexedNotNull { idx, elem ->
            val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
            val toolName = obj["tool"]?.jsonPrimitive?.content
                ?: obj["toolName"]?.jsonPrimitive?.content
                ?: return@mapIndexedNotNull null
            if (toolName !in availableTools) return@mapIndexedNotNull null
            val title = obj["title"]?.jsonPrimitive?.content
                ?: obj["description"]?.jsonPrimitive?.content
                ?: "Execute $toolName"
            val argsObj = runCatching { obj["arguments"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            PlanStep(
                id = "step_${System.currentTimeMillis()}_${idx + 1}",
                index = idx + 1,
                title = title,
                toolName = toolName,
                argumentsJson = argsObj.toString(),
                riskLevel = riskForTool(toolName),
            )
        }
        if (steps.isEmpty()) null else TaskPlan(
            id = "plan_${System.currentTimeMillis()}",
            objective = objective,
            steps = steps,
        )
    }.getOrNull()

    private fun extractAfterKeyword(text: String, keywords: List<String>): String {
        val lower = text.lowercase(Locale.ROOT)
        for (kw in keywords) {
            val idx = lower.indexOf(kw)
            if (idx >= 0) {
                val sub = text.substring(idx + kw.length).trim()
                if (sub.isNotBlank()) return sub.take(60)
            }
        }
        return ""
    }
}
