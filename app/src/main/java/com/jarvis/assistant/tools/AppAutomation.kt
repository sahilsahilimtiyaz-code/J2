package com.jarvis.assistant.tools

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenController
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deep, verified on-screen automation for apps the owner names by voice.
 *
 * Every step follows observe -> decide -> act -> verify: the accessibility tree
 * is re-read before acting, the most specific node is tapped (normally by a
 * physical gesture at its on-screen position, which works even when apps ignore
 * node clicks), and the result is verified before moving on.
 *
 * Used by the WhatsApp messenger and the camera photo capture tools.
 */
internal object AppAutomation {

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    private const val JARVIS_PACKAGE = "com.jarvis.assistant"
    private const val MAX_NODES = 2_500

    // ------------------------------------------------------------- primitives

    /** A located, tappable spot in real screen coordinates. */
    private data class Spot(val centerX: Float, val centerY: Float)

    internal fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        return "$text $desc".trim()
    }

    internal fun normalized(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    internal fun boundsOf(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        val ok = runCatching { node.getBoundsInScreen(rect); true }.getOrDefault(false)
        if (!ok || rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) return null
        return rect
    }

    /** Finds the first node whose screen bounds satisfy [predicate]. */
    internal suspend fun findNode(
        predicate: (AccessibilityNodeInfo, Rect) -> Boolean,
        settleMs: Long = 0L,
    ): AccessibilityNodeInfo? {
        if (settleMs > 0) delay(settleMs)
        return withContext(Dispatchers.Main) {
            val root = JarvisAccessibilityService.serviceInstance()?.rootInActiveWindow
                ?: return@withContext null
            var hit: AccessibilityNodeInfo? = null
            var walked = 0
            fun recurse(node: AccessibilityNodeInfo): Boolean {
                walked++
                if (walked > MAX_NODES) return false
                val bounds = boundsOf(node)
                if (bounds != null && predicate(node, bounds)) {
                    hit = node
                    return false
                }
                var keepGoing = true
                runCatching {
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        keepGoing = recurse(child)
                        if (!keepGoing) break
                    }
                }
                return keepGoing && hit == null
            }
            runCatching { recurse(root) }
            hit
        }
    }

    /** The on-screen rectangle whose node satisfies [predicate]. */
    internal suspend fun findSpot(
        predicate: (AccessibilityNodeInfo, Rect) -> Boolean,
        settleMs: Long = 0L,
    ): Rect? = findNode(predicate, settleMs)?.let { boundsOf(it) }

    /** Finds a node by resource-id token (e.g. "send" for com.whatsapp:id/send). */
    internal suspend fun findByViewId(tokenId: String, settleMs: Long = 0L): AccessibilityNodeInfo? =
        findNode(
            { node, _ ->
                node.viewIdResourceName.orEmpty().substringAfterLast('/') == tokenId
            },
            settleMs,
        )

    /** One verified tap attempt at [spot]; resolves to whether a gesture left the service. */
    internal suspend fun tapSpot(spot: Rect): Boolean {
        val service = JarvisAccessibilityService.serviceInstance() ?: return false
        return service.dispatchTapGesture(spot.centerX().toFloat(), spot.centerY().toFloat())
    }

    /** Fallback: performs ACTION_CLICK on the clickable node sitting exactly at [spot]. */
    internal suspend fun tapNodeAction(spot: Rect): Boolean {
        val node = findNode(predicate = { candidate, bounds ->
            candidate.isClickable &&
                abs(bounds.centerX() - spot.centerX()) < 2 &&
                abs(bounds.centerY() - spot.centerY()) < 2
        }) ?: return false
        return withContext(Dispatchers.Main) {
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
        }
    }

    /** Type into a specific editable node located by its screen bounds, with a focus fallback. */
    internal suspend fun typeIntoEditable(spot: Rect, text: String): Boolean {
        val node = findNode(predicate = { _, bounds ->
            abs(bounds.centerX() - spot.centerX()) < 2 &&
                abs(bounds.centerY() - spot.centerY()) < 2
        }) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val worked = withContext(Dispatchers.Main) {
            var result = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
            if (!result) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                delay(120L)
                result = runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }.getOrDefault(false)
            }
            result
        }
        return worked
    }

    internal suspend fun foregroundPackage(): String =
        withContext(Dispatchers.Main) {
            JarvisAccessibilityService.serviceInstance()?.rootInActiveWindow
                ?.packageName?.toString().orEmpty()
        }

    private suspend fun whatsappInForeground(): Boolean {
        val pkg = foregroundPackage()
        return pkg.startsWith(WHATSAPP_PACKAGE) || pkg.startsWith(WHATSAPP_BUSINESS_PACKAGE)
    }

    // ---------------------------------------------------------------- WhatsApp

    /** The send arrow in any WhatsApp chat. */
    private suspend fun findSendSpot(): Rect? =
        findByViewId("send")?.let { boundsOf(it) } ?: findSpot(
            { node, bounds ->
                val label = normalized(labelOf(node))
                (label == "send" || label == "send message") &&
                    !node.isEditable && bounds.height() > 60 && node.isEnabled
            },
        )

    /** The message input on a WhatsApp chat screen. */
    internal suspend fun findChatInputSpot(): Rect? =
        findByViewId("entry")?.let { boundsOf(it) }
            ?: findByViewId("input_replace")?.let { boundsOf(it) }
            ?: findSpot(
                { node, bounds ->
                    node.isEditable && bounds.width() > 200
                },
            )

    /**
     * Verifies a message left the input: the field is empty again, or the exact
     * message text now occurs more than once on screen (typed copy + bubble).
     */
    private suspend fun verifyMessageSent(
        controller: ScreenController,
        message: String,
    ): Boolean {
        val needle = normalized(message)
        val input = findChatInputSpot()
        val inputText = input?.let { jarvisNodeText(it) }.orEmpty()
        if (input != null && inputText.isNotBlank()) {
            val after = controller.snapshot()
            return after.text.split('\n').count { normalized(it).contains(needle) } >= 2
        }
        return true
    }

    private suspend fun jarvisNodeText(spot: Rect): String = withContext(Dispatchers.Main) {
        val node = findNode(predicate = { _, bounds ->
            abs(bounds.centerX() - spot.centerX()) < 2 &&
                abs(bounds.centerY() - spot.centerY()) < 2
        })
        node?.text?.toString().orEmpty()
    }

    /**
     * Full WhatsApp send when a phone number is known: opens the chat with the
     * message pre-filled through the official wa.me link, presses Send, verifies.
     */
    suspend fun sendToNumber(
        controller: ScreenController,
        phoneDigits: String,
        displayName: String,
        message: String,
    ): ScreenResult {
        val encoded = Uri.encode(message)
        val openIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$phoneDigits?text=$encoded"),
        ).apply {
            setPackage(WHATSAPP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            JarvisAccessibilityService.serviceInstance()!!.startActivity(openIntent)
        }.isSuccess
        if (!launched) {
            return ScreenResult(
                false,
                "WhatsApp did not open — it may not be installed, sir.",
                verified = false,
            )
        }
        val inApp = withTimeoutOrNull(8_000L) {
            while (!whatsappInForeground()) delay(300L)
            true
        } ?: false
        if (!inApp) {
            return ScreenResult(
                false,
                "WhatsApp opened too slowly, so I stopped before sending anything.",
                verified = false,
            )
        }
        delay(1_800L)

        var typed = false
        repeat(4) {
            val sendSpot = findSendSpot()
            if (sendSpot != null) {
                val tapped = tapSpot(sendSpot) || tapNodeAction(sendSpot)
                if (tapped) {
                    delay(1_600L)
                    if (verifyMessageSent(controller, message)) {
                        return ScreenResult(
                            true,
                            "Message sent to $displayName on WhatsApp.",
                            verified = true,
                        )
                    }
                    return ScreenResult(
                        true,
                        "I pressed Send for $displayName, but the input did not clear, so I cannot swear the message went out.",
                        verified = false,
                    )
                }
            }
            if (!typed) {
                findChatInputSpot()?.let { inputSpot ->
                    runCatching {
                        tapSpot(inputSpot)
                        typeIntoEditable(inputSpot, message)
                    }
                    typed = true
                    delay(1_000L)
                }
            }
            delay(800L)
        }
        return ScreenResult(
            false,
            "WhatsApp is open with the message ready for $displayName, but I could not press Send. Tap the green arrow to finish, sir.",
            verified = false,
        )
    }

    /**
     * WhatsApp send when only a NAME is known: opens WhatsApp, searches contacts,
     * opens the best match, types the message, and sends it.
     */
    suspend fun sendToContactByName(
        controller: ScreenController,
        contactName: String,
        message: String,
    ): ScreenResult {
        val launch = runCatching {
            JarvisAccessibilityService.serviceInstance()!!
                .packageManager
                .getLaunchIntentForPackage(WHATSAPP_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()
        val started = launch != null && runCatching {
            JarvisAccessibilityService.serviceInstance()!!.startActivity(launch)
        }.isSuccess
        if (!started) {
            return ScreenResult(
                false,
                "WhatsApp did not open — it may not be installed, sir.",
                verified = false,
            )
        }
        val inApp = withTimeoutOrNull(8_000L) {
            while (!whatsappInForeground()) delay(300L)
            true
        } ?: false
        if (!inApp) {
            return ScreenResult(
                false,
                "WhatsApp opened too slowly, so I stopped before doing anything.",
                verified = false,
            )
        }
        delay(2_100L)

        // Open WhatsApp's search overlay.
        val searchSpot = findByViewId("menuitem_search")?.let { boundsOf(it) }
            ?: findSpot(predicate = { node, _ -> normalized(labelOf(node)) == "search" })
        if (searchSpot == null || !(tapSpot(searchSpot) || tapNodeAction(searchSpot))) {
            return ScreenResult(
                false,
                "WhatsApp is open, but I could not reach its contact search, so I left the message undone, sir.",
                verified = false,
            )
        }
        delay(1_500L)

        // Type the name into the search field.
        val searchSpot2 = findChatInputSpot()
        if (searchSpot2 == null || !typeIntoEditable(searchSpot2, contactName)) {
            return ScreenResult(
                false,
                "I could not type \"$contactName\" into WhatsApp's search, so nothing was sent.",
                verified = false,
            )
        }
        delay(2_200L)

        // Open the chat row that matches the name (fallback: the first chat row).
        val want = normalized(contactName)
        val rowSpot = findSpot(
            { node, bounds ->
                bounds.centerY() > 320 &&
                    bounds.width() > 320 &&
                    bounds.height() in 60..460 &&
                    normalized(labelOf(node)).contains(want)
            },
        ) ?: findSpot(
            { node, bounds ->
                bounds.centerY() > 480 &&
                    node.isClickable &&
                    bounds.width() > 320 &&
                    bounds.height() in 60..460 &&
                    labelOf(node).length >= 2 &&
                    normalized(labelOf(node)) != "search"
            },
        ) ?: run {
            return ScreenResult(
                false,
                "I searched WhatsApp for \"$contactName\" but no chat matched. Nobody was messaged.",
                verified = false,
            )
        }
        if (!(tapSpot(rowSpot) || tapNodeAction(rowSpot))) {
            return ScreenResult(
                false,
                "I found \"$contactName\" in WhatsApp but the chat refused to open.",
                verified = false,
            )
        }
        delay(2_000L)

        // Type + send inside the opened chat.
        repeat(3) {
            val sendSpot = findSendSpot()
            if (sendSpot != null && (tapSpot(sendSpot) || tapNodeAction(sendSpot))) {
                delay(1_600L)
                if (verifyMessageSent(controller, message)) {
                    return ScreenResult(
                        true,
                        "Message sent to $contactName on WhatsApp.",
                        verified = true,
                    )
                }
            }
            findChatInputSpot()?.let { inputSpot ->
                runCatching {
                    tapSpot(inputSpot)
                    typeIntoEditable(inputSpot, message)
                }
            }
            delay(1_100L)
        }
        return ScreenResult(
            false,
            "WhatsApp is in the chat with $contactName and the message is typed, but I could not press Send. Tap the green arrow to finish, sir.",
            verified = false,
        )
    }

    // ------------------------------------------------------------------ camera

    /**
     * Writes the lens-selection extras. These keys jointly cover AOSP Camera,
     * CameraX-based OEM apps and Samsung One UI, so "back camera" genuinely
     * opens the rear lens instead of whichever lens was last used.
     */
    fun applyLensSelection(intent: Intent, facing: String) {
        val front = facing.equals("front", ignoreCase = true) ||
            facing.equals("selfie", ignoreCase = true)
        intent.putExtra("android.intent.extras.CAMERA_FACING", if (front) 1 else 0)
        intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", front)
        intent.putExtra("camera_id", if (front) 1 else 0)
        intent.putExtra("android.intent.extra.camera.CAMERA_FACING", if (front) 1 else 0)
    }

    /** The shutter control on mainstream camera apps. */
    private suspend fun findShutterSpot(): Rect? =
        findByViewId("shutter_button")?.let { boundsOf(it) }
            ?: findSpot(predicate = { node, bounds ->
                val label = normalized(labelOf(node))
                val named = label.contains("takephoto") ||
                    label.contains("takepicture") ||
                    label.contains("photograph") ||
                    label.contains("capture") ||
                    label.contains("shutter")
                named && bounds.height() > 80 && node.isEnabled
            })

    /**
     * Real photo capture: launches a single-shot ACTION_IMAGE_CAPTURE session on
     * the requested lens, presses the shutter itself, then verifies the photo file.
     */
    suspend fun capturePhoto(
        context: ToolContext,
        facing: String,
        fileName: String,
    ): ScreenResult {
        val controller = JarvisAccessibilityService.controller()
            ?: return ScreenResult(false, "Screen control is not connected, sir.", verified = false)
        val resolver = context.appContext.contentResolver

        val pendingValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val pendingUri: Uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            pendingValues,
        ) ?: return ScreenResult(
            false,
            "The gallery refused a slot for the photo, sir.",
            verified = false,
        )

        fun discardPending() = runCatching { resolver.delete(pendingUri, null, null) }

        val targetPackage = runCatching {
            context.appContext.packageManager.resolveActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MediaStore.EXTRA_OUTPUT, pendingUri)
            applyLensSelection(this, facing)
            if (targetPackage != null && targetPackage != JARVIS_PACKAGE) {
                setPackage(targetPackage)
            }
        }
        val launched = runCatching { context.appContext.startActivity(cameraIntent) }.isSuccess
        if (!launched) {
            discardPending()
            return ScreenResult(false, "I could not open the camera app, sir.", verified = false)
        }

        val visible = withTimeoutOrNull(9_000L) {
            while (true) {
                val pkg = foregroundPackage()
                if (pkg.isNotBlank() && pkg != JARVIS_PACKAGE) {
                    if (targetPackage == null || pkg == targetPackage) return@withTimeoutOrNull true
                }
                delay(350L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        if (!visible) {
            discardPending()
            return ScreenResult(
                false,
                "The camera never took the foreground, sir. Nothing was captured.",
                verified = false,
            )
        }
        delay(2_500L) // let the camera app settle its lens and UI

        var shutterPressed = false
        repeat(3) {
            if (shutterPressed) return@repeat
            findShutterSpot()?.let { spot ->
                shutterPressed = tapSpot(spot) || tapNodeAction(spot)
            }
            delay(1_400L)
        }
        if (!shutterPressed) {
            discardPending()
            return ScreenResult(
                false,
                "The camera is open on the $facing lens, but I could not press the shutter. Press it and Jarvis will confirm the photo, sir.",
                verified = false,
            )
        }
        delay(2_900L)

        // Confirm the taken photo if the app shows a preview screen.
        findSpot(
            { node, _ ->
                normalized(labelOf(node)) in setOf("ok", "done", "yes", "keep", "save")
            },
        )?.let { runCatching { tapSpot(it) } }
        delay(900L)

        val size = pollFileSize(resolver, pendingUri, 9_000L)
        publishPending(resolver, pendingUri)
        return if (size > 0) {
            ScreenResult(
                true,
                "Picture captured from the $facing camera and saved to the gallery (Pictures/Jarvis/$fileName).",
                verified = true,
            )
        } else {
            discardPending()
            ScreenResult(
                true,
                "I pressed the shutter on the $facing camera, but the photo file never reached the gallery. Check the camera's own film roll, sir.",
                verified = false,
            )
        }
    }

    private suspend fun pollFileSize(
        resolver: android.content.ContentResolver,
        uri: Uri,
        timeoutMs: Long,
    ): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val size = fileSize(resolver, uri)
            if (size > 0) return size
            delay(1_000L)
        }
        return fileSize(resolver, uri)
    }

    private fun fileSize(resolver: android.content.ContentResolver, uri: Uri): Long =
        runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                cursor.moveToFirst()
                if (column >= 0) cursor.getLong(column) else 0L
            } ?: 0L
        }.getOrDefault(0L)

    private fun publishPending(resolver: android.content.ContentResolver, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }
    }
}
