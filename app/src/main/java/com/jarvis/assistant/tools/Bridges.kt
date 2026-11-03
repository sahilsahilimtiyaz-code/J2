package com.jarvis.assistant.tools

/**
 * What the memory tools need. Implemented by the app runtime so tools never
 * touch Room directly.
 */
interface MemoryBridge {
    /** Stores a memory and describes the outcome for the tool result. */
    suspend fun remember(text: String, type: String, importance: Int): String

    /** Stores a memory with optional comma-separated tags and expiration timestamp. */
    suspend fun rememberAdvanced(
        text: String,
        type: String,
        importance: Int,
        tags: String = "",
        expiresAt: Long = 0L,
    ): String = remember(text, type, importance)

    /** Returns memorable lines matching [query]. */
    suspend fun search(query: String, limit: Int): List<String>

    /** Searches past conversation transcripts for [query]. */
    suspend fun searchHistory(query: String, limit: Int = 5): List<String> = emptyList()

    /** Deletes matching memories and reports how many were removed. */
    suspend fun forget(query: String): String
}

/** Result of one screen action, with a message fit to be spoken and post-step verification status. */
data class ScreenResult(
    val success: Boolean,
    val message: String,
    val verified: Boolean = success,
    val verificationSignal: String = "",
    val attempts: Int = 1,
)

/**
 * The Tier 3 control surface exposed to tools. Implemented by the app runtime,
 * which owns the accessibility service, the safety blocklist and the action log.
 */
/** One real screenshot of the screen plus the display size it was taken on. */
data class ScreenVisionCapture(
    /** JPEG-encoded screenshot (already downscaled by the capture pipeline). */
    val image: ByteArray,
    /** True device width in pixels the screenshot was scaled from. */
    val widthPx: Int,
    /** True device height in pixels. */
    val heightPx: Int,
    /** Foreground package at capture time. */
    val packageName: String,
    /** Accessibility labels visible on that screen, if any, merged for context. */
    val labels: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        other is ScreenVisionCapture &&
            other.image.contentEquals(image) &&
            other.widthPx == widthPx &&
            other.heightPx == heightPx &&
            other.packageName == packageName

    override fun hashCode(): Int = 31 * image.contentHashCode() + widthPx * 7919 + heightPx
}

interface ScreenAccess {
    /** True when the accessibility service is connected and screen control is on. */
    fun ready(): Boolean

    /** The foreground app's label when it is on the blocklist, otherwise null. */
    fun blockedApp(): String?

    suspend fun readScreen(): ScreenResult

    suspend fun tap(text: String): ScreenResult

    suspend fun type(text: String): ScreenResult

    suspend fun scroll(forward: Boolean): ScreenResult

    suspend fun scrollDirection(direction: String): ScreenResult =
        scroll(direction.lowercase() != "up")

    suspend fun tapCoordinates(x: Int, y: Int): ScreenResult =
        tap("$x,$y")

    suspend fun tapVideo(index: Int = 1, query: String = ""): ScreenResult =
        tap("video")

    suspend fun doubleTap(text: String): ScreenResult = tap(text)
    suspend fun longPress(text: String, durationMs: Long = 650L): ScreenResult = tap(text)
    suspend fun swipe(direction: String, distance: String = "medium"): ScreenResult = scrollDirection(direction)
    suspend fun drag(fromTarget: String, toTarget: String): ScreenResult = tap(fromTarget)
    suspend fun pinchZoom(zoomIn: Boolean, scaleFactor: Float = 1.5f): ScreenResult = ScreenResult(false, "Pinch zoom not supported on this view.", verified = false)
    suspend fun selectElement(target: String, desiredState: Boolean? = null): ScreenResult = tap(target)
    suspend fun dismissDialog(): ScreenResult = press("back")
    suspend fun keyboardAction(action: String = "enter"): ScreenResult = press("enter")

    suspend fun press(key: String): ScreenResult

    /**
     * Runs an observe -> decide -> act -> verify multi-step screen automation loop
     * up to [maxSteps] (capped at 15) with a persistent Stop notification and in-app Stop control.
     */
    suspend fun automateScreen(goal: String, maxSteps: Int = 12): ScreenResult

    /** Immediately halts any running screen automation loop. */
    fun stopAutomation()

    /**
     * Captures the real pixels on the screen (JPEG) with the true display size and
     * a short accessibility-tree summary, so a multimodal model can look at what
     * is actually rendered before tapping. Null when vision is unavailable.
     */
    suspend fun captureVision(): ScreenVisionCapture? = null

    /** Real display dimensions in pixels (width to height), or null when unknown. */
    fun screenDimensions(): Pair<Int, Int>? = null

    /** Dumps a formatted string of visible actionable nodes with #refs and logs to logcat under JARVIS_ACT. */
    suspend fun dumpScreen(): String = ""

    /** Executes an action on a node identified by temporary ref (#1, #2, etc.). */
    suspend fun executeRefAction(tempRef: String, actionType: String, textArgument: String? = null, userGoal: String = ""): ScreenResult =
        ScreenResult(false, "Ref execution not supported on this surface.", verified = false)
}

/**
 * What declarative macro skills need. Implemented by the app runtime to save,
 * replay, list, and delete skills without touching Room directly.
 */
interface SkillBridge {
    suspend fun saveSkill(name: String, description: String, triggers: List<String>, stepsJson: String): String
    suspend fun runSkill(nameOrTrigger: String): String
    suspend fun listSkills(): List<String>
    suspend fun deleteSkill(name: String): String
}
