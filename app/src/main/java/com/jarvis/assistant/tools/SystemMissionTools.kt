package com.jarvis.assistant.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.brain.ToolDefinition
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Lists installed launchable apps on the phone so Jarvis can discover what apps
 * the owner has installed before opening or automating them.
 */
class ListInstalledAppsTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "list_installed_apps",
        description = "List installed launchable apps on the phone, optionally filtered by a " +
            "search keyword (for example \"social\", \"music\", \"google\", or empty for all).",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "filter" to stringProperty("Optional keyword to filter app names or package names."),
            ),
        ),
    )

    override val label = "List installed apps"
    override val category = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val filter = arguments.argString("filter")?.trim().orEmpty()
            val pm = context.appContext.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { info ->
                    val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrNull().orEmpty()
                    val pkg = info.activityInfo?.packageName.orEmpty()
                    if (label.isBlank() || pkg.isBlank()) null else "$label ($pkg)"
                }
                .distinct()
                .sorted()

            val matched = if (filter.isBlank()) {
                apps
            } else {
                apps.filter { it.contains(filter, ignoreCase = true) }
            }

            if (matched.isEmpty()) {
                ToolResult(false, "No installed apps matched \"$filter\".")
            } else {
                val preview = matched.take(60).joinToString(", ")
                ToolResult(
                    true,
                    "Found ${matched.size} installed apps: $preview",
                )
            }
        }
}

/**
 * Takes a system screenshot using the Accessibility Service's native Android 11+
 * `GLOBAL_ACTION_TAKE_SCREENSHOT`.
 */
class TakeScreenshotTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "take_screenshot",
        description = "Take a screenshot of the current phone screen and save it to the gallery.",
        parameters = objectSchema(required = emptyList(), properties = emptyMap()),
    )

    override val label = "Take screenshot"
    override val category = ToolCategory.DEVICE

    override fun availability(context: Context): ToolAvailability =
        if (JarvisAccessibilityService.isEnabled(context)) {
            ToolAvailability("Accessibility on", true)
        } else {
            ToolAvailability("Needs accessibility", false)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        screenUnavailable(context)?.let { return it }
        val res = context.screen!!.press("screenshot")
        return if (res.success) {
            ToolResult(true, "Screenshot captured and saved to the phone gallery.")
        } else {
            ToolResult(false, res.message)
        }
    }
}

/**
 * Opens the phone's Camera app in still-photo or video recording mode, on the
 * lens the owner asked for. Lens extras are applied so "open camera" followed by
 * "take a picture from the back camera" lands on the rear lens, not whichever
 * lens the camera app happened to remember.
 */
class OpenCameraTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "open_camera",
        description = "Open the phone camera in photo or video mode, on the requested lens.",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "mode" to stringProperty(
                    "Camera mode: photo or video. Defaults to photo.",
                    enum = listOf("photo", "video"),
                ),
                "facing" to stringProperty(
                    "Which camera: back or front. Defaults to back.",
                    enum = listOf("back", "front"),
                ),
            ),
        ),
    )

    override val label = "Open camera"
    override val category = ToolCategory.MEDIA

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val mode = arguments.argString("mode")?.lowercase() ?: "photo"
        val facing = arguments.argString("facing")?.lowercase() ?: "back"
        val action = if (mode == "video") {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA
        } else {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AppAutomation.applyLensSelection(intent, facing)
        return runCatching {
            context.appContext.startActivity(intent)
            ToolResult(true, "Opened the camera on the $facing lens in $mode mode.")
        }.getOrElse {
            // Some phones lack the still/media camera action; try the camera app by name.
            OpenAppTool().execute(
                kotlinx.serialization.json.buildJsonObject {
                    put("app", kotlinx.serialization.json.JsonPrimitive("Camera"))
                },
                context,
            )
        }
    }
}

/**
 * Takes an actual photo: opens a single-shot capture session on the requested
 * lens, presses the shutter through screen automation, and verifies that the
 * photo really landed in the gallery.
 */
class TakePhotoTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "take_photo",
        description = "Take a photo with the phone camera on the requested lens (back camera by " +
            "default). Jarvis opens the camera, presses the shutter and confirms the photo was saved.",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "facing" to stringProperty(
                    "Which camera: back or front. Defaults to back.",
                    enum = listOf("back", "front"),
                ),
                "subject" to stringProperty(
                    "Optional one-line description of what the photo shows, used only for the log.",
                ),
            ),
        ),
    )

    override val label = "Take a photo"
    override val category = ToolCategory.MEDIA

    override fun availability(context: Context): ToolAvailability =
        if (JarvisAccessibilityService.isEnabled(context)) {
            ToolAvailability("Camera automation on", true)
        } else {
            ToolAvailability("Opens camera app", true)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val facing = arguments.argString("facing")?.lowercase()?.takeIf {
            it == "back" || it == "front"
        } ?: "back"
        val subject = arguments.argString("subject")
        val cameraPermission = context.permissions.ensure(
            android.Manifest.permission.CAMERA,
            "Jarvis needs the Camera permission to take a picture.",
        )
        if (!cameraPermission) {
            // No permission: at least open the camera app on the right lens.
            return OpenCameraTool().execute(
                kotlinx.serialization.json.buildJsonObject {
                    put("facing", kotlinx.serialization.json.JsonPrimitive(facing))
                    put("mode", kotlinx.serialization.json.JsonPrimitive("photo"))
                },
                context,
            )
        }
        val fileName = "Jarvis_${System.currentTimeMillis()}"
        val result = withContext(Dispatchers.IO) {
            AppAutomation.capturePhoto(context, facing, fileName)
        }
        val suffix = subject?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return ToolResult(result.success, result.message + suffix)
    }
}

/**
 * Sends a WhatsApp message to a phone number or contact after explicit owner
 * confirmation (honoring the non-negotiable safety rule).
 *
 * Unlike the old version that merely opened the app, this one resolves the
 * contact, opens the exact chat, types the message and presses Send through
 * verified screen automation so "send hi to my friend" really delivers "hi".
 */
class SendWhatsAppTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "send_whatsapp_message",
        description = "Send a WhatsApp message to a contact name or phone number. Jarvis finds " +
            "the contact, opens the chat, types the message and presses Send itself " +
            "(after the owner confirms).",
        parameters = objectSchema(
            required = listOf("recipient", "message"),
            properties = mapOf(
                "recipient" to stringProperty("Contact name, or phone number with country code."),
                "message" to stringProperty("The message text to send on WhatsApp."),
            ),
        ),
    )

    override val label = "Send WhatsApp message"
    override val category = ToolCategory.CONTACTS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val recipient = arguments.argString("recipient")?.trim().orEmpty()
        val message = arguments.argString("message")?.trim().orEmpty().take(1_000)
        if (recipient.isBlank() || message.isBlank()) {
            return ToolResult(false, "Provide both the recipient and the WhatsApp message text.")
        }

        val approved = context.confirmation.confirm(
            title = "Send WhatsApp to $recipient?",
            detail = "“$message”",
        )
        if (!approved) {
            return ToolResult(false, "The owner declined sending the WhatsApp message.")
        }

        // Resolve the contact first so the flow never opens a dead end.
        val digitsOnly = recipient.filter { it.isDigit() }.takeIf { it.length >= 7 }
        val resolvedName: String
        val resolvedNumber: String?
        if (digitsOnly != null) {
            resolvedName = recipient
            resolvedNumber = digitsOnly
        } else {
            val match = resolveTarget(context, recipient)
            if (match == null) {
                resolvedName = recipient
                resolvedNumber = null
            } else {
                resolvedName = match.name
                resolvedNumber = match.number
            }
        }

        val result = withContext(Dispatchers.IO) {
            val controller = JarvisAccessibilityService.controller()
            if (controller != null) {
                if (resolvedNumber != null) {
                    AppAutomation.sendToNumber(
                        controller = controller,
                        phoneDigits = resolvedNumber,
                        displayName = resolvedName,
                        message = message,
                    )
                } else {
                    AppAutomation.sendToContactByName(
                        controller = controller,
                        contactName = resolvedName,
                        message = message,
                    )
                }
            } else {
                ScreenResult(
                    false,
                    "Screen control is off, so I can only prepare the message, not send it.",
                    verified = false,
                )
            }
        }
        return ToolResult(result.success, result.message)
    }
}

/**
 * Sends a genuine WhatsApp voice message: Jarvis renders the line in his own
 * configured voice, drives WhatsApp's recorder and sends the note.
 */
class SendWhatsAppVoiceNoteTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "send_whatsapp_voice_note",
        description = "Send a real WhatsApp voice message to a contact name or phone number. " +
            "Jarvis speaks the supplied text in his own voice, records it through WhatsApp's " +
            "own microphone, sends it, and verifies the chat. The owner confirms first.",
        parameters = objectSchema(
            required = listOf("recipient", "message"),
            properties = mapOf(
                "recipient" to stringProperty("Contact name, or phone number with country code."),
                "message" to stringProperty(
                    "What Jarvis should say in the voice note. One or two short sentences.",
                ),
            ),
        ),
    )

    override val label = "Send WhatsApp voice note"
    override val category = ToolCategory.CONTACTS

    override fun availability(context: Context): ToolAvailability {
        val connected = JarvisAccessibilityService.isConnected()
        return ToolAvailability(
            label = if (connected) "Ready" else "Needs screen control",
            ready = connected,
        )
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val recipient = arguments.argString("recipient")?.trim().orEmpty()
        val message = arguments.argString("message")?.trim().orEmpty().take(700)
        if (recipient.isBlank() || message.isBlank()) {
            return ToolResult(false, "Give me both the recipient and what to say in the voice note.")
        }
        val core = runCatching {
            com.jarvis.assistant.core.JarvisCore.get(context.appContext)
        }.getOrNull() ?: return ToolResult(false, "Jarvis is not initialised yet.")
        if (!core.settings.value.safety.voiceNoteEnabled) {
            return ToolResult(
                false,
                "WhatsApp voice messages are switched off. Enable them in Settings, Security, " +
                    "WhatsApp voice messages.",
            )
        }
        val approved = context.confirmation.confirm(
            title = "Send a WhatsApp voice message to $recipient?",
            detail = "\u201c${message.take(300)}\u201d\n\nJarvis will speak this aloud in his " +
                "configured voice and record it through WhatsApp. Keep the phone unlocked, " +
                "on the loudspeaker and off any headset.",
        )
        if (!approved) {
            return ToolResult(false, "The owner declined the WhatsApp voice message.")
        }
        val result = core.sendWhatsAppVoiceNoteNow(recipient, message)
        return ToolResult(result.success, result.message)
    }
}

/**
 * Reads or writes the system clipboard so Jarvis can copy summaries, code, links,
 * or read what the owner just copied.
 */
class ClipboardTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "clipboard_control",
        description = "Copy text to the phone clipboard, or read the current text from the clipboard.",
        parameters = objectSchema(
            required = listOf("action"),
            properties = mapOf(
                "action" to stringProperty(
                    "copy or read.",
                    enum = listOf("copy", "read"),
                ),
                "text" to stringProperty("The text to copy when action is copy."),
            ),
        ),
    )

    override val label = "Clipboard read/copy"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        withContext(Dispatchers.Main) {
            val action = arguments.argString("action")?.lowercase() ?: "copy"
            val clipboard = context.appContext.getSystemService(ClipboardManager::class.java)
                ?: return@withContext ToolResult(false, "Clipboard service is not available.")

            if (action == "read") {
                val text = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context.appContext)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) {
                    ToolResult(true, "The clipboard is currently empty or not accessible in the background.")
                } else {
                    val safeText = com.jarvis.assistant.security.SecurityShield.sanitizeUntrustedText(
                        raw = text.take(1500),
                        source = "clipboard",
                    )
                    ToolResult(true, "Clipboard text: $safeText")
                }
            } else {
                val text = arguments.argString("text").orEmpty()
                if (text.isBlank()) {
                    return@withContext ToolResult(false, "Provide the text to copy to the clipboard.")
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
                ToolResult(true, "Copied ${text.length} characters to the clipboard.")
            }
        }
}

/**
 * Opens any website URL or deep link directly in the browser or handling app.
 */
class OpenUrlTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "open_url",
        description = "Open a specific website URL or deep link on the phone.",
        parameters = objectSchema(
            required = listOf("url"),
            properties = mapOf(
                "url" to stringProperty("Full URL to open (for example https://github.com)."),
            ),
        ),
    )

    override val label = "Open URL"
    override val category = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val raw = arguments.argString("url")?.trim().orEmpty()
        if (raw.isBlank()) return ToolResult(false, "Provide a URL to open.")
        com.jarvis.assistant.security.SecurityShield.validateUrl(raw)?.let { blockedReason ->
            return ToolResult(false, blockedReason)
        }
        val normalized = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else {
            "https://$raw"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.appContext.startActivity(intent)
            ToolResult(true, "Opened $normalized.")
        }.getOrElse { error ->
            ToolResult(false, "Could not open $normalized: ${error.message}")
        }
    }
}


/**
 * Picks up a ringing WhatsApp voice call and runs the receptionist conversation:
 * Jarvis explains sir is unavailable, listens to what the caller says and replies
 * in his own voice. Requires the WhatsApp call assistant enabled in Settings,
 * Safety and screen control on.
 */
class AnswerWhatsAppCallTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "answer_whatsapp_call",
        description = "Answer the incoming WhatsApp voice call and speak with the caller as " +
            "sir's assistant, explaining that sir is not available and taking a message.",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "note" to stringProperty("Optional one-line context to help Jarvis with this call."),
            ),
        ),
    )

    override val label = "Answer WhatsApp call"
    override val category = ToolCategory.CONTACTS

    override fun availability(context: Context): ToolAvailability =
        ToolAvailability(
            label = if (JarvisAccessibilityService.isConnected()) "Ready" else "Needs screen control",
            ready = JarvisAccessibilityService.isConnected(),
        )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val note = arguments.argString("note")?.trim()?.take(300)?.takeIf { it.isNotBlank() }
        val core = runCatching {
            com.jarvis.assistant.core.JarvisCore.get(context.appContext)
        }.getOrNull() ?: return ToolResult(false, "Jarvis is not initialised yet.")
        val result = core.answerWhatsAppCallNow(note)
        return ToolResult(result.success, result.message)
    }
}

/**
 * Hangs up the active WhatsApp voice call if Jarvis is on one.
 */
class EndWhatsAppCallTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "end_whatsapp_call",
        description = "Hang up the active WhatsApp voice call that Jarvis is handling.",
        parameters = objectSchema(
            required = emptyList(),
            properties = emptyMap(),
        ),
    )

    override val label = "End WhatsApp call"
    override val category = ToolCategory.CONTACTS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val core = runCatching {
            com.jarvis.assistant.core.JarvisCore.get(context.appContext)
        }.getOrNull() ?: return ToolResult(false, "Jarvis is not initialised yet.")
        val result = core.endWhatsAppCallNow()
        return ToolResult(result.success, result.message)
    }
}
