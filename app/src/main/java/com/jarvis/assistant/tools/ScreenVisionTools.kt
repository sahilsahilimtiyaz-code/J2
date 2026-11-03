package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.brain.ToolDefinition
import com.jarvis.assistant.security.SecurityShield
import kotlinx.serialization.json.JsonObject

/**
 * Context Screen Vision & Structural Scene Inspection Tool.
 *
 * Inspects what is currently rendered on the user's phone screen: active application,
 * window titles, paragraph contents, interactive buttons, input fields, and status cues.
 *
 * Provides a structured visual perception context to the LLM agent while enforcing
 * strict SecurityShield redaction of passwords, credentials, and payment cards.
 */
class AnalyzeScreenVisionTool : Tool {

    override val definition = ToolDefinition(
        name = "analyze_screen",
        description = "Analyzes what is currently displayed on the device screen in real time. Returns the active application, window title, visible text, buttons, and editable inputs so you can understand what the user is looking at or assist them with an on-screen task.",
        parameters = objectSchema(
            properties = mapOf(
                "detail_level" to stringProperty(
                    description = "Optional detail level: 'summary' (brief high-level overview) or 'detailed' (full list of visible text and interactive elements). Defaults to 'detailed'.",
                ),
            ),
            required = emptyList(),
        ),
    )

    override val label: String = "Screen Vision Analysis"
    override val category: ToolCategory = ToolCategory.DEVICE

    override fun availability(context: Context): ToolAvailability =
        if (JarvisAccessibilityService.isEnabled(context)) {
            ToolAvailability("Accessibility Vision On", true)
        } else {
            ToolAvailability("Needs Accessibility", false)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        screenUnavailable(context)?.let { return it }

        val detailLevel = arguments.argString("detail_level")?.lowercase() ?: "detailed"
        val screen = context.screen ?: return ToolResult(false, "Screen access is not initialized.")

        val screenData = screen.readScreen()
        if (!screenData.success) {
            return ToolResult(false, "Unable to inspect the screen: ${screenData.message}")
        }

        // Sanitize untrusted content through SecurityShield
        val sanitized = SecurityShield.sanitizeUntrustedText(
            raw = screenData.message,
            source = "screen_vision",
        )

        val sb = StringBuilder()
        sb.append("=== J.A.R.V.I.S. OPTICAL SCREEN ANALYSIS ===\n")
        sb.append(sanitized)
        sb.append("\n============================================\n")

        if (detailLevel == "summary") {
            val lines = sanitized.lines().filter { it.isNotBlank() }
            val appLine = lines.firstOrNull { it.startsWith("App:") } ?: "Active Screen"
            val textCount = lines.count { it.startsWith("Text:") }
            val buttonCount = lines.count { it.startsWith("Button:") || it.startsWith("Clickable:") }
            return ToolResult(
                true,
                "$appLine with $textCount visible text elements and $buttonCount interactive controls.",
            )
        }

        return ToolResult(true, sb.toString())
    }
}

/**
 * True computer vision for the agent: captures an actual screenshot of the
 * current screen (API 30+ via the accessibility service), attaches the JPEG to
 * the next model turn so a multimodal model can look at the real pixels, and
 * merges a sanitized accessibility-tree summary for maximum context.
 *
 * Call this BEFORE tapping anything unfamiliar, then act with
 * [TapCoordinatesTool] using percent coordinates (unit=percent).
 */
class SeeScreenTool : Tool {

    override val definition = ToolDefinition(
        name = "see_screen",
        description = "Capture a real screenshot of the phone screen right now. You receive the raw image plus node labels so you can see exactly what is displayed and decide precise tap locations. Use before tapping unfamiliar apps or screens.",
        parameters = objectSchema(
            properties = mapOf(
                "note" to stringProperty(
                    description = "Optional one-line reason, for example 'find WhatsApp search bar'.",
                ),
            ),
            required = emptyList(),
        ),
    )

    override val label: String = "Look at the screen"
    override val category: ToolCategory = ToolCategory.DEVICE

    override fun availability(context: Context): ToolAvailability =
        if (JarvisAccessibilityService.isEnabled(context)) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                ToolAvailability("Live camera-vision on", true)
            } else {
                ToolAvailability("Needs Android 11+", false)
            }
        } else {
            ToolAvailability("Needs Accessibility", false)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        screenUnavailable(context)?.let { return it }
        val screen = context.screen ?: return ToolResult(false, "Screen access is not initialized.")

        // Check VisionPrivacyGate against the live accessibility snapshot before capturing pixels
        val liveService = JarvisAccessibilityService.serviceInstance()
        if (liveService != null) {
            val observed = com.jarvis.assistant.accessibility.JarvisScreenObserver.observe(
                service = liveService,
                appLabelResolver = { pkg -> JarvisAccessibilityService.controller()?.appLabel(pkg) ?: pkg },
                forceRefresh = false,
            )
            val privacyDecision = com.jarvis.assistant.accessibility.VisionPrivacyGate.evaluateExternalVisionRequest(
                userConsentedToVision = true,
                modelSupportsVision = true,
                screen = observed,
            )
            if (!privacyDecision.allowed) {
                return ToolResult(false, privacyDecision.reason)
            }
        }

        val capture = screen.captureVision()
            ?: if (android.os.Build.VERSION.SDK_INT >= 30) {
                return ToolResult(
                    false,
                    "I could not capture the screen. Re-enable Jarvis screen control in Accessibility " +
                        "settings (App info > three-dot menu > Allow restricted settings), then try again.",
                )
            } else {
                return ToolResult(false, "Screen vision needs Android 11 or newer.")
            }

        // Merge the sanitized accessibility tree so the model gets BOTH pixels and labels.
        val tree = runCatching {
            SecurityShield.sanitizeUntrustedText(
                raw = screen.readScreen().message,
                source = "screen_vision",
            )
        }.getOrDefault("")

        val (width, height) = capture.widthPx to capture.heightPx
        val text = buildString {
            appendLine("Live screen inspection captured ($width x $height px).")
            appendLine("Foreground package: ${capture.packageName.ifBlank { "unknown" }}")
            if (tree.isNotBlank()) {
                appendLine("Screen elements & coordinates (accessibility tree):")
                appendLine(tree.take(2_500))
            }
            appendLine(
                "To act on what is on screen: call tap_screen with an element label or #index, " +
                    "call type_on_screen to type into a search bar or input field, or call " +
                    "tap_screen_coordinates with unit=percent (0-100) or unit=pixels using the (x, y) coordinates above.",
            )
        }
        return ToolResult(true, text, image = capture.image)
    }
}

