package com.jarvis.assistant.accessibility

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jarvis.assistant.security.SecurityShield
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Explicit task lifecycle state machine for Jarvis's human-like touch and
 * screen automation engine.
 */
enum class AutomationStage(val label: String) {
    IDLE("Idle"),
    LISTENING("Listening"),
    UNDERSTANDING_INTENT("Understanding intent"),
    PLANNING_STEPS("Planning steps"),
    CHECKING_PERMISSIONS("Checking permissions"),
    LAUNCHING_APP("Launching application"),
    IDENTIFYING_SCREEN("Identifying current screen"),
    FINDING_TARGET("Locating target UI element"),
    PERFORMING_TOUCH("Performing touch interaction"),
    WAITING_FOR_RESPONSE("Waiting for app response"),
    VERIFYING_RESULT("Verifying action outcome"),
    RECOVERING_FROM_ERROR("Recovering from UI obstacle"),
    ASKING_CONFIRMATION("Awaiting owner confirmation"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

/**
 * Supported human-like touchscreen and navigation interaction primitives.
 */
enum class HumanInteractionType(val id: String, val label: String) {
    SINGLE_TAP("single_tap", "Single tap"),
    DOUBLE_TAP("double_tap", "Double tap"),
    LONG_PRESS("long_press", "Long press"),
    SHORT_PRESS("short_press", "Short press"),
    SWIPE_UP("swipe_up", "Swipe up"),
    SWIPE_DOWN("swipe_down", "Swipe down"),
    SWIPE_LEFT("swipe_left", "Swipe left"),
    SWIPE_RIGHT("swipe_right", "Swipe right"),
    VERTICAL_SCROLL("vertical_scroll", "Vertical scroll"),
    HORIZONTAL_SCROLL("horizontal_scroll", "Horizontal scroll"),
    DRAG_AND_DROP("drag_and_drop", "Drag and drop"),
    PRESS_AND_HOLD("press_and_hold", "Press and hold"),
    SEQUENTIAL_GESTURE("sequential_gesture", "Sequential gestures"),
    PINCH_ZOOM("pinch_zoom", "Multi-touch pinch/zoom"),
    NAVIGATE_BACK("navigate_back", "Back navigation"),
    KEYBOARD_ACTION("keyboard_action", "Keyboard IME action"),
    SELECT_TEXT_FIELD("select_text_field", "Text-field selection"),
    ENTER_TEXT("enter_text", "Text entry"),
    SELECT_UI_ELEMENT("select_ui_element", "Select UI element (checkbox/switch/radio/tab)"),
    DISMISS_DIALOG("dismiss_dialog", "Dismiss dialog"),
}

/**
 * UI element semantic roles used for role-aware matching.
 */
enum class UiElementRole {
    ANY,
    BUTTON,
    TEXT_FIELD,
    TOGGLE,
    TAB,
    VIDEO_CARD,
    LIST_ITEM,
    DIALOG_DISMISS,
    SEARCH_TRIGGER,
}

/**
 * Metadata for an active window observed via [AccessibilityWindowInfo].
 */
data class ObservedWindowInfo(
    val windowId: Int,
    val windowType: String,
    val title: String,
    val packageName: String,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val layer: Int,
)

/**
 * Structured representation of a single node on the live Android screen.
 * Pure value object — never retains an [AccessibilityNodeInfo] reference.
 */
data class ObservedUiElement(
    val index: Int,
    val resourceId: String,
    val text: String,
    val contentDescription: String,
    val hintText: String,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val scrollable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val visibleToUser: Boolean,
    val bounds: Rect,
    val parentLabel: String = "",
    val childLabels: List<String> = emptyList(),
    val depth: Int = 0,
    val isInInputMethodWindow: Boolean = false,
    val boundsLeft: Int = bounds.left,
    val boundsTop: Int = bounds.top,
    val boundsRight: Int = bounds.right,
    val boundsBottom: Int = bounds.bottom,
    val elementRef: String = "ref_e$index",
    val parentRef: String? = null,
    val childRefs: List<String> = emptyList(),
    val windowId: Int = -1,
    val windowLayer: Int = 0,
    val password: Boolean = false,
    val supportedActions: Set<String> = buildDefaultSupportedActions(
        clickable = clickable,
        longClickable = longClickable,
        editable = editable,
        scrollable = scrollable,
        focusable = focusable,
    ),
) {
    val left: Int
        get() = if (boundsLeft != 0 || boundsRight != 0) boundsLeft else bounds.left

    val top: Int
        get() = if (boundsTop != 0 || boundsBottom != 0) boundsTop else bounds.top

    val right: Int
        get() = if (boundsRight != 0) boundsRight else bounds.right

    val bottom: Int
        get() = if (boundsBottom != 0) boundsBottom else bounds.bottom

    val primaryLabel: String
        get() = when {
            password -> "[REDACTED_PASSWORD_FIELD]"
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            hintText.isNotBlank() -> hintText
            childLabels.isNotEmpty() -> childLabels.joinToString(" ").take(90)
            parentLabel.isNotBlank() -> parentLabel
            resourceId.isNotBlank() -> resourceId.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }

    val shortResourceId: String
        get() = resourceId.substringAfterLast('/')

    val centerX: Int
        get() = (left + right) / 2

    val centerY: Int
        get() = (top + bottom) / 2

    val areaPx: Int
        get() = max(0, right - left) * max(0, bottom - top)

    fun supportsAction(action: String): Boolean =
        supportedActions.any { it.equals(action, ignoreCase = true) }

    fun hasValidTouchBounds(screenWidth: Int, screenHeight: Int): Boolean {
        val w = right - left
        val h = bottom - top
        if (w < 12 || h < 12) return false
        if (right <= 0 || bottom <= 0) return false
        if (screenWidth > 0 && left >= screenWidth) return false
        if (screenHeight > 0 && top >= screenHeight) return false
        return true
    }

    fun inferRole(): UiElementRole {
        val cls = className.lowercase(Locale.ROOT)
        val id = shortResourceId.lowercase(Locale.ROOT)
        val label = "$text $contentDescription $hintText".lowercase(Locale.ROOT)
        val h = bottom - top
        return when {
            editable || password || cls.contains("edittext") || cls.contains("autocompletetextview") ->
                UiElementRole.TEXT_FIELD
            checkable || cls.contains("switch") || cls.contains("checkbox") ||
                cls.contains("radiobutton") || cls.contains("togglebutton") ||
                cls.contains("compoundbutton") ->
                UiElementRole.TOGGLE
            cls.contains("tab") || id.contains("tab") || selected && clickable && h < 240 ->
                UiElementRole.TAB
            id.contains("search") || label == "search" || label.startsWith("search ") ->
                UiElementRole.SEARCH_TRIGGER
            cls.contains("button") || cls.contains("imagebutton") || cls.contains("floatingactionbutton") ->
                UiElementRole.BUTTON
            else -> UiElementRole.ANY
        }
    }

    companion object {
        fun buildDefaultSupportedActions(
            clickable: Boolean,
            longClickable: Boolean,
            editable: Boolean,
            scrollable: Boolean,
            focusable: Boolean,
        ): Set<String> = buildSet {
            if (clickable) add("ACTION_CLICK")
            if (longClickable) add("ACTION_LONG_CLICK")
            if (editable) {
                add("ACTION_SET_TEXT")
                add("ACTION_IME_ENTER")
            }
            if (scrollable) {
                add("ACTION_SCROLL_FORWARD")
                add("ACTION_SCROLL_BACKWARD")
            }
            if (focusable) add("ACTION_FOCUS")
        }
    }
}

/**
 * Complete structured observation of the active Android display with a unique
 * [snapshotId] and stable element references.
 */
data class ObservedScreenState(
    val packageName: String,
    val appLabel: String,
    val windowTitle: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val rotation: Int,
    val keyboardVisible: Boolean,
    val keyboardTopY: Int,
    val activeDialogTitle: String?,
    val dismissCandidate: ObservedUiElement?,
    val elements: List<ObservedUiElement>,
    val timestampMs: Long = System.currentTimeMillis(),
    val snapshotId: String = "snap_${timestampMs}_${packageName.hashCode().toUInt()}_${elements.size}",
    val screenVersion: Long = 1L,
    val windows: List<ObservedWindowInfo> = emptyList(),
    val isSecureOrProtected: Boolean = SecurityShield.isProtectedSystemPackage(packageName),
) {
    fun summaryHeader(): String = buildString {
        append("Snapshot: ").append(snapshotId)
        append(" | App: ").append(appLabel.ifBlank { packageName })
        append(" (").append(packageName).append(")")
        if (windowTitle.isNotBlank()) append(" | Window: ").append(windowTitle)
        append(" | Screen: ").append(screenWidth).append("x").append(screenHeight)
        append(" (rot=").append(rotation).append(")")
        if (keyboardVisible) append(" | Keyboard: VISIBLE (topY=").append(keyboardTopY).append(")")
        if (!activeDialogTitle.isNullOrBlank()) append(" | Dialog: ").append(activeDialogTitle)
        if (isSecureOrProtected) append(" | PROTECTED_SURFACE")
    }

    /**
     * Resolves an element by its exact [ObservedUiElement.elementRef] (e.g. "ref_e3")
     * or numeric index ("#3" or "3").
     */
    fun findByRef(refOrIndex: String): ObservedUiElement? {
        val clean = refOrIndex.trim()
        if (clean.isBlank()) return null
        elements.firstOrNull { it.elementRef.equals(clean, ignoreCase = true) }?.let { return it }
        val idx = clean.removePrefix("#").removePrefix("ref_e").toIntOrNull()
        return if (idx != null) elements.firstOrNull { it.index == idx } else null
    }

    fun hasPasswordField(): Boolean = elements.any { it.password }

    fun structuralSignature(): Int {
        var h = packageName.hashCode()
        h = 31 * h + windowTitle.hashCode()
        h = 31 * h + (if (keyboardVisible) 1 else 0)
        h = 31 * h + (activeDialogTitle?.hashCode() ?: 0)
        for (el in elements.take(120)) {
            h = 31 * h + el.elementRef.hashCode()
            h = 31 * h + el.primaryLabel.hashCode()
            h = 31 * h + (if (el.checked) 1 else 0)
            h = 31 * h + (if (el.enabled) 2 else 0)
        }
        return h
    }

    /**
     * Produces a compact, token-efficient OpenClaw-style snapshot string for LLM consumption.
     */
    fun formatSnapshotForAgent(maxElements: Int = 60): String = buildString {
        appendLine(summaryHeader())
        if (windows.isNotEmpty()) {
            appendLine(
                "Windows (${windows.size}): " + windows.joinToString(", ") {
                    "${it.windowType}[id=${it.windowId},layer=${it.layer},active=${it.isActive}]"
                },
            )
        }
        appendLine("Elements (${elements.size} total, showing up to $maxElements):")
        for (el in elements.take(maxElements)) {
            val role = el.inferRole().name.lowercase(Locale.ROOT)
            val stateFlags = buildList {
                if (el.clickable) add("clickable")
                if (el.editable) add("editable")
                if (el.scrollable) add("scrollable")
                if (el.checkable) add(if (el.checked) "checked" else "unchecked")
                if (el.focused) add("focused")
                if (!el.enabled) add("DISABLED")
                if (el.password) add("PASSWORD")
            }.joinToString(",")
            val parentTag = el.parentRef?.let { " parent=$it" }.orEmpty()
            appendLine(
                "- [${el.elementRef}] role=$role label=\"${el.primaryLabel.take(80)}\" " +
                    "id=${el.shortResourceId.ifBlank { "none" }} " +
                    "bounds=[${el.left},${el.top},${el.right},${el.bottom}] " +
                    "state=($stateFlags)$parentTag",
            )
        }
    }
}

/**
 * Query specification for locating a UI element on screen.
 */
data class ElementMatchQuery(
    val rawTarget: String,
    val expectedRole: UiElementRole = UiElementRole.ANY,
    val preferEditable: Boolean = false,
    val preferCheckable: Boolean = false,
    val preferScrollable: Boolean = false,
    val packageContext: String = "",
    val knownResourceIds: List<String> = emptyList(),
)

/**
 * Scored candidate returned by [JarvisUiElementMatcher].
 */
data class ElementMatchCandidate(
    val element: ObservedUiElement,
    val score: Int,
    val confidence: Float,
    val matchedBy: List<String>,
) {
    val evidence: String
        get() = "'${element.primaryLabel.take(50)}' (#${element.index}, id=${element.shortResourceId.ifBlank { "none" }}, " +
            "bounds=[${element.bounds.left},${element.bounds.top}-${element.bounds.right},${element.bounds.bottom}], " +
            "score=$score, confidence=${(confidence * 100).roundToInt()}%, signals=${matchedBy.joinToString("+")})"
}

data class ElementMatchResolution(
    val best: ElementMatchCandidate?,
    val runnersUp: List<ElementMatchCandidate>,
    val ambiguityDetected: Boolean,
    val ambiguityMessage: String? = null,
)

/**
 * 10-Tier UI Element Identification and Disambiguation Engine.
 *
 * Ranks every visible node using:
 * 1. Exact resource ID
 * 2. Exact content description
 * 3. Exact visible text
 * 4. Element type / class role
 * 5. Parent-child and sibling relationships
 * 6. Package & app-registry context
 * 7. Enabled and clickable status
 * 8. On-screen bounds & keyboard occlusion check
 * 9. Ordinal ("first", "second", "#2") and spatial ("top right", "bottom") hints
 * 10. Visual coordinate fallback when tree nodes lack labels
 */
object JarvisUiElementMatcher {

    private val ORDINAL_LIST = listOf(
        "first" to 1,
        "1st" to 1,
        "second" to 2,
        "2nd" to 2,
        "third" to 3,
        "3rd" to 3,
        "fourth" to 4,
        "4th" to 4,
        "fifth" to 5,
        "5th" to 5,
        "sixth" to 6,
        "6th" to 6,
        "seventh" to 7,
        "7th" to 7,
        "eighth" to 8,
        "8th" to 8,
        "ninth" to 9,
        "9th" to 9,
        "tenth" to 10,
        "10th" to 10,
        "number 1" to 1,
        "number 2" to 2,
        "number 3" to 3,
        "number 4" to 4,
        "number 5" to 5,
        "number one" to 1,
        "number two" to 2,
        "number three" to 3,
    )

    private val HIGH_CONSEQUENCE_KEYWORDS = setOf(
        "send", "submit", "pay", "purchase", "buy", "delete", "remove",
        "uninstall", "transfer", "confirm", "call", "dial", "post", "publish",
    )

    fun extractOrdinalIndex(rawTarget: String): Int? {
        val trimmed = rawTarget.trim().lowercase(Locale.ROOT)
        val hashMatch = Regex("""^#?(\d{1,3})$""").find(trimmed)
        if (hashMatch != null) return hashMatch.groupValues[1].toIntOrNull()

        val numberedPhrase = Regex("""\b(?:#|item\s+|result\s+|option\s+|video\s+)(\d{1,2})\b""").find(trimmed)
        if (numberedPhrase != null) return numberedPhrase.groupValues[1].toIntOrNull()

        for ((word, idx) in ORDINAL_LIST) {
            if (Regex("""\b$word\b""").containsMatchIn(trimmed)) {
                return idx
            }
        }
        if (Regex("""\blast\b""").containsMatchIn(trimmed)) {
            return -1 // Special marker for last matching item
        }
        return null
    }

    fun stripOrdinalAndSpatialWords(rawTarget: String): String {
        return rawTarget.lowercase(Locale.ROOT)
            .replace(
                Regex("""\b(the|first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th|last|top|bottom|left|right|upper|lower|middle|center|button|icon|tab|item|option|one|result)\b"""),
                " ",
            )
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun resolve(
        screen: ObservedScreenState,
        query: ElementMatchQuery,
    ): ElementMatchResolution {
        val raw = query.rawTarget.trim()
        if (raw.isEmpty() && !query.preferEditable && !query.preferCheckable && !query.preferScrollable) {
            return ElementMatchResolution(null, emptyList(), false)
        }

        // Direct elementRef ("ref_e3") or #index ("#3", "3") lookup when user or planner specifies it
        val refCandidate = if (raw.startsWith("ref_", ignoreCase = true) || Regex("""^#?\d{1,3}$""").matches(raw)) {
            screen.findByRef(raw)
        } else {
            null
        }
        if (refCandidate != null) {
            val candidate = ElementMatchCandidate(
                element = refCandidate,
                score = if (refCandidate.enabled) 160 else 45,
                confidence = if (refCandidate.enabled) 0.98f else 0.35f,
                matchedBy = buildList {
                    add("exact_index:#${refCandidate.index}")
                    add("exact_ref:${refCandidate.elementRef}")
                    if (!refCandidate.enabled) add("disabled_penalty")
                },
            )
            return ElementMatchResolution(candidate, emptyList(), false)
        }

        val needle = raw.lowercase(Locale.ROOT)
        val cleanNeedle = stripOrdinalAndSpatialWords(raw).ifBlank { needle }
        val needleTokens = cleanNeedle.split(' ', '_', '-', '/').filter { it.length >= 2 }
        val ordinalHint = extractOrdinalIndex(raw)
        val wantTop = needle.contains("top") || needle.contains("upper")
        val wantBottom = needle.contains("bottom") || needle.contains("lower")
        val wantLeft = needle.contains("left")
        val wantRight = needle.contains("right")

        val isProfileQuery = cleanNeedle in setOf(
            "profile", "my profile", "your profile", "you", "you tab",
            "account", "my account", "your channel", "my channel",
        ) || needle.contains("profile") || needle == "you"
        val isNavTabQuery = isProfileQuery || cleanNeedle in setOf(
            "home", "subscriptions", "library", "explore", "search tab", "reels", "shorts",
        )
        val wantsShortsOrReels = needle.contains("short") || needle.contains("reel")

        val expandedSynonyms: List<String> = when {
            isProfileQuery -> listOf("profile", "your profile", "my profile", "account", "your channel", "you", "library")
            cleanNeedle == "search" || cleanNeedle == "search bar" || cleanNeedle == "search box" ->
                listOf("search", "search youtube", "search settings", "search…", "find")
            cleanNeedle == "send" || cleanNeedle == "send button" ->
                listOf("send", "send message", "send voice message")
            cleanNeedle == "microphone" || cleanNeedle == "mic" || cleanNeedle == "voice note" || cleanNeedle == "voice message" ->
                listOf("voice message", "record voice", "microphone", "mic", "voice note", "voice_note_btn")
            else -> listOf(cleanNeedle)
        }

        val scored = mutableListOf<ElementMatchCandidate>()

        for (el in screen.elements) {
            if (!el.visibleToUser) continue
            if (!el.hasValidTouchBounds(screen.screenWidth, screen.screenHeight)) continue

            var score = 0
            val signals = mutableListOf<String>()

            val resIdLower = el.resourceId.lowercase(Locale.ROOT)
            val shortIdLower = el.shortResourceId.lowercase(Locale.ROOT)
            val textLower = el.text.lowercase(Locale.ROOT).trim()
            val descLower = el.contentDescription.lowercase(Locale.ROOT).trim()
            val hintLower = el.hintText.lowercase(Locale.ROOT).trim()
            val parentLower = el.parentLabel.lowercase(Locale.ROOT).trim()
            val childrenLower = el.childLabels.joinToString(" ").lowercase(Locale.ROOT).trim()
            val combinedLabel = "$textLower $descLower $shortIdLower"

            val elementWidth = el.right - el.left
            val isBottomNavBarRegion = screen.screenHeight > 0 &&
                el.top >= (screen.screenHeight * 0.80f).toInt() &&
                elementWidth <= (screen.screenWidth * 0.36f).toInt().coerceAtLeast(380)
            val isTopRightAvatarRegion = screen.screenHeight > 0 && screen.screenWidth > 0 &&
                el.bottom <= (screen.screenHeight * 0.18f).toInt() &&
                el.centerX >= (screen.screenWidth * 0.70f).toInt()
            val isRightmostBottomTab = isBottomNavBarRegion &&
                screen.screenWidth > 0 &&
                el.centerX >= (screen.screenWidth * 0.72f).toInt()

            // Anti-Shorts / Anti-Reels Guard: never tap Shorts when user asked for Profile or anything else!
            if (!wantsShortsOrReels && (
                    Regex("""\bshorts?\b""").containsMatchIn(combinedLabel) ||
                        shortIdLower.contains("reel_pivot") ||
                        shortIdLower.contains("shorts")
                    )
            ) {
                score -= 220
                signals += "anti_shorts_guard"
            }

            // 1. Exact resource ID (+120)
            if (needle.isNotBlank() && (resIdLower == needle || shortIdLower == needle || shortIdLower == cleanNeedle)) {
                score += 120
                signals += "exact_resource_id"
            } else if (query.knownResourceIds.any { it.equals(el.resourceId, ignoreCase = true) || it.equals(el.shortResourceId, ignoreCase = true) }) {
                score += 115
                signals += "registry_resource_id"
            } else if (cleanNeedle.length >= 3 && shortIdLower.contains(cleanNeedle.replace(" ", "_"))) {
                score += 75
                signals += "partial_resource_id"
            } else if (isProfileQuery && (
                    shortIdLower.contains("avatar") ||
                        shortIdLower.contains("account") ||
                        shortIdLower.contains("profile") ||
                        shortIdLower == "tab_you" ||
                        shortIdLower.contains("you_tab")
                    )
            ) {
                score += 95
                signals += "profile_resource_id"
            }

            // Helper: checks if a candidate label is a genuine "You" tab label rather than "Shorts for you" / "Recommended for you"
            fun isGenuineYouTabLabel(label: String): Boolean {
                if (label.isBlank()) return false
                if (label == "you" || label == "you tab" || label.startsWith("you,") || label.startsWith("you (")) return true
                return false
            }

            // 2. Content description matching (+110 exact, +95 genuine profile synonym)
            if (descLower.isNotBlank()) {
                when {
                    descLower == needle || descLower == cleanNeedle -> {
                        score += 110
                        signals += "exact_content_desc"
                    }
                    isProfileQuery && isGenuineYouTabLabel(descLower) -> {
                        score += 108
                        signals += "genuine_you_tab_desc"
                    }
                    isProfileQuery && expandedSynonyms.filter { it != "you" }.any { syn ->
                        descLower == syn || Regex("""\b${Regex.escape(syn)}\b""").containsMatchIn(descLower)
                    } -> {
                        score += 96
                        signals += "profile_synonym_desc"
                    }
                    cleanNeedle != "you" && Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(descLower) -> {
                        score += 82
                        signals += "word_content_desc"
                    }
                    cleanNeedle != "you" && cleanNeedle.length >= 3 && descLower.contains(cleanNeedle) -> {
                        score += 56
                        signals += "substring_content_desc"
                    }
                }
            }

            // 3. Visible text matching (+105 exact, +92 genuine profile synonym)
            if (textLower.isNotBlank()) {
                when {
                    textLower == needle || textLower == cleanNeedle -> {
                        score += 105
                        signals += "exact_text"
                    }
                    isProfileQuery && isGenuineYouTabLabel(textLower) -> {
                        score += 104
                        signals += "genuine_you_tab_text"
                    }
                    isProfileQuery && expandedSynonyms.filter { it != "you" }.any { syn ->
                        textLower == syn || Regex("""\b${Regex.escape(syn)}\b""").containsMatchIn(textLower)
                    } -> {
                        score += 92
                        signals += "profile_synonym_text"
                    }
                    cleanNeedle != "you" && Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(textLower) -> {
                        score += 78
                        signals += "word_text"
                    }
                    cleanNeedle != "you" && cleanNeedle.length >= 3 && textLower.contains(cleanNeedle) -> {
                        score += 52
                        signals += "substring_text"
                    }
                }
            }

            // Hint text match for text fields
            if (hintLower.isNotBlank() && cleanNeedle.isNotBlank() &&
                (hintLower == cleanNeedle || hintLower.contains(cleanNeedle))
            ) {
                score += 65
                signals += "hint_text"
            }

            // Token overlap when multi-word target is given (excluding generic "you" false-positives)
            if (score <= 0 && needleTokens.size >= 2 && !isProfileQuery) {
                val combined = "$textLower $descLower $hintLower $shortIdLower $childrenLower $parentLower"
                val matchedTokens = needleTokens.count { token ->
                    if (token == "you") false else combined.contains(token)
                }
                if (matchedTokens >= 2 || matchedTokens == needleTokens.size) {
                    score += 35 + (matchedTokens * 14)
                    signals += "token_overlap($matchedTokens/${needleTokens.size})"
                }
            }

            // 4. Correct element type or class (+28)
            val inferredRole = el.inferRole()
            if (query.expectedRole != UiElementRole.ANY && inferredRole == query.expectedRole) {
                score += 28
                signals += "role:${inferredRole.name.lowercase(Locale.ROOT)}"
            }
            if (isNavTabQuery && (inferredRole == UiElementRole.TAB || isBottomNavBarRegion)) {
                score += 48
                signals += "nav_tab_region"
            }
            if (isProfileQuery && (isRightmostBottomTab || isTopRightAvatarRegion)) {
                score += 55
                signals += "profile_spatial_anchor"
            }
            if (query.preferEditable && el.editable) {
                score += 45
                signals += "editable"
            }
            if (query.preferCheckable && (el.checkable || inferredRole == UiElementRole.TOGGLE)) {
                score += 45
                signals += "checkable"
            }

            // 5. Parent-child relationships (+24, avoiding full-width container false matches)
            val isFullWidthContainer = screen.screenWidth > 0 && elementWidth > (screen.screenWidth * 0.58f).toInt()
            if (cleanNeedle.isNotBlank() && !isFullWidthContainer) {
                val childMatch = if (isProfileQuery) {
                    el.childLabels.any { isGenuineYouTabLabel(it.lowercase(Locale.ROOT).trim()) || it.contains("profile", ignoreCase = true) || it.contains("account", ignoreCase = true) }
                } else {
                    childrenLower == cleanNeedle || (cleanNeedle != "you" && Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(childrenLower))
                }
                if (childMatch) {
                    score += 68
                    signals += "child_label_match"
                } else if (!isProfileQuery && (parentLower == cleanNeedle || (cleanNeedle != "you" && Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(parentLower)))) {
                    score += if (el.checkable || el.clickable || el.editable) 64 else 24
                    signals += "parent_context_match"
                }
            }

            // Penalize full-width container bars (e.g. pivot_bar spanning 0..1080) when targeting a specific tab/button
            if (isFullWidthContainer && (isNavTabQuery || query.expectedRole == UiElementRole.TAB || query.expectedRole == UiElementRole.BUTTON)) {
                score -= 140
                signals += "full_width_container_penalty"
            }

            // If we still have no semantic match and no role-only query, skip
            if (score <= 0 && ordinalHint == null) continue

            // 6. Package and current-screen context (+15)
            if (query.packageContext.isNotBlank() && el.packageName.equals(query.packageContext, ignoreCase = true)) {
                score += 15
                signals += "package_match"
            }

            // 7. Enabled and clickable status (+22, heavy penalty if disabled)
            if (!el.enabled) {
                score -= 90
                signals += "disabled_penalty"
            } else {
                if (el.clickable || el.editable || el.checkable || el.longClickable) {
                    score += 22
                    signals += "actionable"
                }
            }

            // 8. Current element bounds & keyboard occlusion (+18 / -45)
            val occludedByKeyboard = screen.keyboardVisible &&
                !el.isInInputMethodWindow &&
                screen.keyboardTopY in 1..el.centerY
            if (occludedByKeyboard) {
                score -= 45
                signals += "keyboard_occluded"
            } else if (el.areaPx >= 900) {
                score += 18
                signals += "healthy_bounds"
            }

            // 9. Spatial context ("top", "bottom", "left", "right")
            if (screen.screenHeight > 0 && screen.screenWidth > 0) {
                if (wantTop && el.centerY < screen.screenHeight * 0.32) {
                    score += 25
                    signals += "spatial_top"
                }
                if (wantBottom && el.centerY > screen.screenHeight * 0.68) {
                    score += 25
                    signals += "spatial_bottom"
                }
                if (wantLeft && el.centerX < screen.screenWidth * 0.38) {
                    score += 20
                    signals += "spatial_left"
                }
                if (wantRight && el.centerX > screen.screenWidth * 0.62) {
                    score += 20
                    signals += "spatial_right"
                }
            }

            val confidence = (score.toFloat() / 165f).coerceIn(0.05f, 0.99f)
            if (score > 0 || ordinalHint != null) {
                scored += ElementMatchCandidate(
                    element = el,
                    score = score,
                    confidence = confidence,
                    matchedBy = signals,
                )
            }
        }

        // Apply ordinal selection ("first", "second", "third", "last") if requested
        if (ordinalHint != null) {
            val pool = scored
                .filter { it.score >= 20 || cleanNeedle.isBlank() }
                .ifEmpty {
                    screen.elements
                        .filter { it.visibleToUser && it.enabled && (it.clickable || it.editable || it.checkable) }
                        .map {
                            ElementMatchCandidate(
                                element = it,
                                score = 40,
                                confidence = 0.65f,
                                matchedBy = listOf("actionable_pool"),
                            )
                        }
                }
                // Sort top-to-bottom, left-to-right for natural human reading order
                .sortedWith(compareBy<ElementMatchCandidate>({ it.element.top / 48 }, { it.element.left }))

            val chosen = when {
                pool.isEmpty() -> null
                ordinalHint == -1 -> pool.last()
                ordinalHint in 1..pool.size -> pool[ordinalHint - 1]
                else -> null
            }
            if (chosen != null) {
                val boosted = chosen.copy(
                    score = chosen.score + 55,
                    confidence = max(chosen.confidence, 0.86f),
                    matchedBy = chosen.matchedBy + "ordinal_position:$ordinalHint",
                )
                return ElementMatchResolution(
                    best = boosted,
                    runnersUp = pool.filter { it.element.index != boosted.element.index }.take(3),
                    ambiguityDetected = false,
                )
            }
        }

        val sorted = scored.sortedWith(
            compareByDescending<ElementMatchCandidate> { it.score }
                .thenByDescending { it.element.clickable }
                .thenBy { it.element.index },
        )

        val best = sorted.firstOrNull() ?: return ElementMatchResolution(null, emptyList(), false)
        val second = sorted.drop(1).firstOrNull {
            // Distinct screen location (not just parent/child wrapper of the same button)
            hypot(
                (it.element.centerX - best.element.centerX).toDouble(),
                (it.element.centerY - best.element.centerY).toDouble(),
            ) > 72.0
        }

        val isHighConsequence = HIGH_CONSEQUENCE_KEYWORDS.any { needle.contains(it) }
        val ambiguous = second != null &&
            isHighConsequence &&
            abs(best.score - second.score) <= 8 &&
            best.element.primaryLabel.lowercase(Locale.ROOT) != second.element.primaryLabel.lowercase(Locale.ROOT)

        val ambiguityMsg = if (ambiguous) {
            "Multiple similar high-consequence elements matched '$raw': " +
                "1) '${best.element.primaryLabel}' (#${best.element.index}) and " +
                "2) '${second!!.element.primaryLabel}' (#${second.element.index}). " +
                "Please specify which one to tap."
        } else {
            null
        }

        return ElementMatchResolution(
            best = best,
            runnersUp = sorted.drop(1).take(4),
            ambiguityDetected = ambiguous,
            ambiguityMessage = ambiguityMsg,
        )
    }
}

/**
 * 4-Level Verification Engine for Jarvis's Touch & Automation subsystem.
 *
 * Level 1: Interaction Verification (performAction / GestureResultCallback returned true)
 * Level 2: UI Verification (UiStateFingerprint changed: window, content, scroll, checked, or text)
 * Level 3: Application Verification (foreground package, target toggle state, or expected screen element)
 * Level 4: External / System API Verification (Bluetooth, Wi-Fi, Volume, Brightness, DND, Audio playback)
 */
enum class VerificationLevel(val level: Int, val label: String) {
    LEVEL_1_INTERACTION(1, "Level 1: Touch/Accessibility Dispatch"),
    LEVEL_2_UI_DELTA(2, "Level 2: UI Hierarchy & State Delta"),
    LEVEL_3_APP_STATE(3, "Level 3: Application Post-Condition"),
    LEVEL_4_SYSTEM_API(4, "Level 4: Android System API State"),
}

enum class VerificationStatus(val label: String) {
    VERIFIED_SUCCESS("VERIFIED_SUCCESS"),
    UNVERIFIED_UNKNOWN("UNVERIFIED_UNKNOWN"),
    FAILED("FAILED"),
}

data class ActionVerificationReport(
    val status: VerificationStatus,
    val highestLevelPassed: VerificationLevel?,
    val level1InteractionPassed: Boolean,
    val level2UiDeltaPassed: Boolean,
    val level3AppStatePassed: Boolean,
    val level4SystemApiPassed: Boolean? = null,
    val evidenceSummary: String,
) {
    val isVerifiedSuccess: Boolean
        get() = status == VerificationStatus.VERIFIED_SUCCESS
}

object JarvisActionVerifier {

    fun verifyTouchOrAction(
        interactionType: HumanInteractionType,
        dispatched: Boolean,
        before: UiStateFingerprint,
        after: UiStateFingerprint,
        expectedPackage: String? = null,
        expectedTextInEditable: String? = null,
        expectedToggleState: Boolean? = null,
        actualToggleStateAfter: Boolean? = null,
        dialogWasPresentBefore: Boolean = false,
        dialogPresentAfter: Boolean = false,
        systemApiCheck: (() -> Boolean)? = null,
    ): ActionVerificationReport {
        if (!dispatched) {
            return ActionVerificationReport(
                status = VerificationStatus.FAILED,
                highestLevelPassed = null,
                level1InteractionPassed = false,
                level2UiDeltaPassed = false,
                level3AppStatePassed = false,
                level4SystemApiPassed = null,
                evidenceSummary = "Level 1 failed: Android OS rejected or cancelled the touch/accessibility dispatch.",
            )
        }

        // Level 2: UI Delta check
        val uiOutcome = when (interactionType) {
            HumanInteractionType.VERTICAL_SCROLL,
            HumanInteractionType.HORIZONTAL_SCROLL,
            HumanInteractionType.SWIPE_UP,
            HumanInteractionType.SWIPE_DOWN,
            HumanInteractionType.SWIPE_LEFT,
            HumanInteractionType.SWIPE_RIGHT,
            -> StepVerificationEvaluator.evaluateScroll(before, after)
            HumanInteractionType.ENTER_TEXT -> StepVerificationEvaluator.evaluateTextEntry(before, after, expectedTextInEditable.orEmpty())
            else -> StepVerificationEvaluator.evaluateClick(before, after, expectedTextInEditable.orEmpty())
        }
        val uiChanged = uiOutcome.verified

        // Level 3: Application Post-Condition check
        val pkgTransitioned = !expectedPackage.isNullOrBlank() &&
            !before.packageName.equals(expectedPackage, ignoreCase = true) &&
            after.packageName.equals(expectedPackage, ignoreCase = true)
        val textMatched = !expectedTextInEditable.isNullOrBlank() &&
            after.editableTexts.any { it.lowercase(Locale.ROOT).contains(expectedTextInEditable.trim().lowercase(Locale.ROOT)) }
        val toggleMatched = expectedToggleState != null && actualToggleStateAfter == expectedToggleState
        val dialogDismissed = dialogWasPresentBefore && !dialogPresentAfter

        val level3Passed = pkgTransitioned || textMatched || toggleMatched || dialogDismissed

        // Level 4: External / System API check
        val level4Passed = systemApiCheck?.let { runCatching { it() }.getOrNull() }

        val highestLevel = when {
            level4Passed == true -> VerificationLevel.LEVEL_4_SYSTEM_API
            level3Passed -> VerificationLevel.LEVEL_3_APP_STATE
            uiChanged -> VerificationLevel.LEVEL_2_UI_DELTA
            else -> VerificationLevel.LEVEL_1_INTERACTION
        }

        val verifiedSuccess = (level4Passed == true) || level3Passed || uiChanged
        val status = when {
            level4Passed == false && !uiChanged -> VerificationStatus.FAILED
            verifiedSuccess -> VerificationStatus.VERIFIED_SUCCESS
            else -> VerificationStatus.UNVERIFIED_UNKNOWN
        }

        val evidence = buildString {
            append("[${status.label}] ")
            append("L1(Dispatch)=true, ")
            append("L2(UI Delta)=$uiChanged (${uiOutcome.signal}), ")
            append("L3(App State)=$level3Passed")
            if (level4Passed != null) append(", L4(System API)=$level4Passed")
            if (before.packageName != after.packageName) {
                append(" | pkg: ${before.packageName} -> ${after.packageName}")
            }
            if (before.checkedStatesHash != after.checkedStatesHash) {
                append(" | toggle_state_changed=true")
            }
            if (after.telemetry.lastScrollSeq > before.telemetry.lastScrollSeq) {
                append(" | scroll_events=${after.telemetry.lastScrollSeq - before.telemetry.lastScrollSeq}")
            }
        }

        return ActionVerificationReport(
            status = status,
            highestLevelPassed = highestLevel,
            level1InteractionPassed = true,
            level2UiDeltaPassed = uiChanged,
            level3AppStatePassed = level3Passed,
            level4SystemApiPassed = level4Passed,
            evidenceSummary = evidence,
        )
    }

    /**
     * Level 4 System API verifiers for hardware and OS state.
     */
    fun isBluetoothEnabled(context: Context): Boolean? = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.isEnabled
    }.getOrNull()

    fun isWifiEnabled(context: Context): Boolean? = runCatching {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        manager?.isWifiEnabled
    }.getOrNull()

    fun isMediaPlaying(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audio?.isMusicActive == true
    }.getOrDefault(false)

    fun currentMusicVolume(context: Context): Int = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    }.getOrDefault(-1)

    fun currentScreenBrightness(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(-1)
}

/**
 * Intelligent Error Recovery Manager.
 *
 * Diagnoses why an interaction failed or was blocked, suggests the safest
 * recovery step, and enforces the Anti-Duplication Guard so consequential
 * actions (send message, pay, delete, place call) are never blindly repeated.
 */
enum class RecoveryActionType {
    HIDE_KEYBOARD,
    DISMISS_BLOCKING_DIALOG,
    SCROLL_DOWN_TO_REVEAL,
    SCROLL_UP_TO_REVEAL,
    RELAUNCH_TARGET_APP,
    WAIT_FOR_SLOW_LOAD,
    FALLBACK_TO_COORDINATE_TAP,
    ABORT_CONSEQUENTIAL_DUPLICATE,
    ABORT_UNRECOVERABLE,
}

data class RecoveryDecision(
    val action: RecoveryActionType,
    val reason: String,
    val safeToRetryTarget: Boolean,
)

object JarvisRecoveryManager {

    private val CONSEQUENTIAL_VERBS = setOf(
        "send", "submit", "pay", "purchase", "order", "transfer", "delete",
        "remove", "erase", "uninstall", "call", "dial", "post", "publish",
    )

    fun isConsequentialTarget(targetLabel: String, toolOrActionName: String = ""): Boolean {
        val combined = "$targetLabel $toolOrActionName".lowercase(Locale.ROOT)
        return CONSEQUENTIAL_VERBS.any { verb ->
            Regex("""\b$verb\b""").containsMatchIn(combined)
        }
    }

    fun diagnoseAndPlanRecovery(
        targetLabel: String,
        attemptNumber: Int,
        maxAttempts: Int,
        screenBefore: ObservedScreenState,
        screenAfter: ObservedScreenState,
        lastReport: ActionVerificationReport?,
        expectedPackage: String? = null,
    ): RecoveryDecision {
        // 1. Prevent duplicate consequential actions!
        if (isConsequentialTarget(targetLabel) && lastReport != null) {
            if (lastReport.level1InteractionPassed && lastReport.level2UiDeltaPassed) {
                return RecoveryDecision(
                    action = RecoveryActionType.ABORT_CONSEQUENTIAL_DUPLICATE,
                    reason = "Consequential action '$targetLabel' already dispatched and changed the screen; blocking duplicate retry for safety.",
                    safeToRetryTarget = false,
                )
            }
        }

        if (attemptNumber >= maxAttempts) {
            return RecoveryDecision(
                action = RecoveryActionType.ABORT_UNRECOVERABLE,
                reason = "Reached maximum recovery attempts ($maxAttempts) for '$targetLabel'.",
                safeToRetryTarget = false,
            )
        }

        // 2. Check if target app lost foreground
        if (!expectedPackage.isNullOrBlank() &&
            screenAfter.packageName.isNotBlank() &&
            !screenAfter.packageName.equals(expectedPackage, ignoreCase = true)
        ) {
            return RecoveryDecision(
                action = RecoveryActionType.RELAUNCH_TARGET_APP,
                reason = "Expected package '$expectedPackage' is not in foreground (current: '${screenAfter.packageName}').",
                safeToRetryTarget = true,
            )
        }

        // 3. Check if an unexpected dialog or popup is blocking the screen
        if (!screenAfter.activeDialogTitle.isNullOrBlank() &&
            !targetLabel.contains(screenAfter.activeDialogTitle, ignoreCase = true) &&
            screenAfter.dismissCandidate != null
        ) {
            return RecoveryDecision(
                action = RecoveryActionType.DISMISS_BLOCKING_DIALOG,
                reason = "Dialog '${screenAfter.activeDialogTitle}' is blocking the screen.",
                safeToRetryTarget = true,
            )
        }

        // 4. Check if soft keyboard is covering the lower screen while searching for a button/element
        if (screenAfter.keyboardVisible) {
            val match = JarvisUiElementMatcher.resolve(
                screenAfter,
                ElementMatchQuery(rawTarget = targetLabel),
            ).best
            if (match == null || match.matchedBy.contains("keyboard_occluded")) {
                return RecoveryDecision(
                    action = RecoveryActionType.HIDE_KEYBOARD,
                    reason = "Soft keyboard is visible and may be covering '$targetLabel'; hiding keyboard first.",
                    safeToRetryTarget = true,
                )
            }
        }

        // 5. If element is not visible on current screen, scroll to reveal it
        val matchOnAfter = JarvisUiElementMatcher.resolve(
            screenAfter,
            ElementMatchQuery(rawTarget = targetLabel),
        ).best
        if (matchOnAfter == null) {
            val scrollType = if (attemptNumber % 3 == 2) {
                RecoveryActionType.SCROLL_UP_TO_REVEAL
            } else {
                RecoveryActionType.SCROLL_DOWN_TO_REVEAL
            }
            return RecoveryDecision(
                action = scrollType,
                reason = "Target '$targetLabel' is not in the visible hierarchy; scrolling to bring it into view.",
                safeToRetryTarget = true,
            )
        }

        // 6. Element is visible on screen, fallback to direct hardware coordinate gesture tap
        return RecoveryDecision(
            action = RecoveryActionType.FALLBACK_TO_COORDINATE_TAP,
            reason = "Target '$targetLabel' is visible at [${matchOnAfter.element.centerX}, ${matchOnAfter.element.centerY}]; retrying with direct touch gesture.",
            safeToRetryTarget = !isConsequentialTarget(targetLabel) || !(lastReport?.level1InteractionPassed ?: false),
        )
    }
}

/**
 * Application Integration Profile & Registry (`JarvisAppRegistry`).
 */
data class AppIntegrationProfile(
    val canonicalName: String,
    val aliases: List<String>,
    val packageNames: List<String>,
    val searchButtonIds: List<String> = emptyList(),
    val searchInputIds: List<String> = emptyList(),
    val sendButtonIds: List<String> = emptyList(),
    val profileTabLabels: List<String> = emptyList(),
    val supportsDeepLinkSearch: Boolean = false,
)

object JarvisAppRegistry {

    val KNOWN_PROFILES: List<AppIntegrationProfile> = listOf(
        AppIntegrationProfile(
            canonicalName = "WhatsApp",
            aliases = listOf("whatsapp", "whats app", "wa", "whatsapp business"),
            packageNames = listOf("com.whatsapp", "com.whatsapp.w4b"),
            searchButtonIds = listOf("com.whatsapp:id/menuitem_search", "com.whatsapp:id/search_holder"),
            searchInputIds = listOf("com.whatsapp:id/search_input", "com.whatsapp:id/search_src_text"),
            sendButtonIds = listOf("com.whatsapp:id/send", "com.whatsapp:id/voice_note_send_btn"),
            profileTabLabels = listOf("Settings", "Profile", "You"),
            supportsDeepLinkSearch = false,
        ),
        AppIntegrationProfile(
            canonicalName = "YouTube",
            aliases = listOf("youtube", "yt", "you tube"),
            packageNames = listOf("com.google.android.youtube"),
            searchButtonIds = listOf("com.google.android.youtube:id/menu_item_1", "com.google.android.youtube:id/menu_search"),
            searchInputIds = listOf("com.google.android.youtube:id/search_edit_text"),
            profileTabLabels = listOf("You", "Library", "Account"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Instagram",
            aliases = listOf("instagram", "insta", "ig"),
            packageNames = listOf("com.instagram.android"),
            searchButtonIds = listOf("com.instagram.android:id/search_tab", "com.instagram.android:id/action_bar_search_edit_text"),
            searchInputIds = listOf("com.instagram.android:id/action_bar_search_edit_text"),
            sendButtonIds = listOf("com.instagram.android:id/row_thread_composer_button_send"),
            profileTabLabels = listOf("Profile", "Your profile"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Chrome",
            aliases = listOf("chrome", "google chrome", "browser"),
            packageNames = listOf("com.android.chrome"),
            searchButtonIds = listOf("com.android.chrome:id/search_box_text", "com.android.chrome:id/url_bar"),
            searchInputIds = listOf("com.android.chrome:id/url_bar"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Google Maps",
            aliases = listOf("maps", "google maps", "navigation"),
            packageNames = listOf("com.google.android.apps.maps"),
            searchButtonIds = listOf("com.google.android.apps.maps:id/search_omnibox_text_box"),
            searchInputIds = listOf("com.google.android.apps.maps:id/search_omnibox_edit_text"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Spotify",
            aliases = listOf("spotify", "music"),
            packageNames = listOf("com.spotify.music"),
            searchButtonIds = listOf("com.spotify.music:id/search_tab", "com.spotify.music:id/find_search_field"),
            searchInputIds = listOf("com.spotify.music:id/query"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Gmail",
            aliases = listOf("gmail", "email", "google mail"),
            packageNames = listOf("com.google.android.gm"),
            searchButtonIds = listOf("com.google.android.gm:id/open_search_bar_text_view"),
            searchInputIds = listOf("com.google.android.gm:id/open_search_view_edit_text"),
            sendButtonIds = listOf("com.google.android.gm:id/send"),
            supportsDeepLinkSearch = false,
        ),
        AppIntegrationProfile(
            canonicalName = "Settings",
            aliases = listOf("settings", "system settings", "phone settings", "device settings"),
            packageNames = listOf("com.android.settings"),
            searchButtonIds = listOf("com.android.settings:id/search_action_bar", "com.android.settings:id/search_bar"),
            searchInputIds = listOf("android:id/search_src_text", "com.google.android.settings.intelligence:id/open_search_view_edit_text"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Phone",
            aliases = listOf("phone", "dialer", "calls"),
            packageNames = listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"),
            searchButtonIds = listOf("com.google.android.dialer:id/open_search_bar", "com.samsung.android.dialer:id/menu_search"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Contacts",
            aliases = listOf("contacts", "address book", "people"),
            packageNames = listOf("com.samsung.android.app.contacts", "com.google.android.contacts", "com.android.contacts"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Messages",
            aliases = listOf("messages", "sms", "text messages", "google messages"),
            packageNames = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            sendButtonIds = listOf("com.google.android.apps.messaging:id/send_message_button_icon"),
            supportsDeepLinkSearch = true,
        ),
        AppIntegrationProfile(
            canonicalName = "Camera",
            aliases = listOf("camera", "photo camera"),
            packageNames = listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera2"),
            supportsDeepLinkSearch = true,
        ),
    )

    fun findByPackage(packageName: String): AppIntegrationProfile? {
        val clean = packageName.trim().lowercase(Locale.ROOT)
        return KNOWN_PROFILES.firstOrNull { profile ->
            profile.packageNames.any { it.lowercase(Locale.ROOT) == clean }
        }
    }

    fun findBySpokenName(name: String): AppIntegrationProfile? {
        val clean = name.trim().lowercase(Locale.ROOT)
        return KNOWN_PROFILES.firstOrNull { profile ->
            profile.canonicalName.lowercase(Locale.ROOT) == clean ||
                profile.aliases.any { it == clean || clean.contains(it) }
        }
    }
}

/**
 * Short-term Contextual Reference Memory (`InteractionContextMemory`).
 */
data class InteractionSnapshot(
    val packageName: String = "",
    val previousPackageName: String = "",
    val appLabel: String = "",
    val lastTargetLabel: String = "",
    val lastActionType: HumanInteractionType = HumanInteractionType.SINGLE_TAP,
    val lastBounds: Rect? = null,
    val lastScrollDirection: String = "down",
    val lastTypedText: String = "",
    val lastRecipient: String = "",
    val lastSearchQuery: String = "",
    val lastAssistantReply: String = "",
    val pendingChainSteps: List<String> = emptyList(),
    val recentVisibleLabels: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

class InteractionContextMemory {
    @Volatile
    private var snapshot = InteractionSnapshot()

    fun current(): InteractionSnapshot = snapshot

    fun recordScreenObservation(screen: ObservedScreenState) {
        val prev = snapshot
        val nextPreviousPkg = if (screen.packageName.isNotBlank() &&
            prev.packageName.isNotBlank() &&
            screen.packageName != prev.packageName &&
            !screen.packageName.startsWith("com.jarvis.assistant")
        ) {
            prev.packageName
        } else {
            prev.previousPackageName
        }
        val labels = screen.elements
            .filter { it.clickable && it.primaryLabel.isNotBlank() }
            .map { it.primaryLabel }
            .distinct()
            .take(15)
        snapshot = prev.copy(
            packageName = screen.packageName.ifBlank { prev.packageName },
            previousPackageName = nextPreviousPkg,
            appLabel = screen.appLabel.ifBlank { prev.appLabel },
            recentVisibleLabels = if (labels.isNotEmpty()) labels else prev.recentVisibleLabels,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun recordAction(
        actionType: HumanInteractionType,
        targetLabel: String = "",
        bounds: Rect? = null,
        scrollDirection: String = "",
        typedText: String = "",
        recipient: String = "",
        searchQuery: String = "",
    ) {
        val prev = snapshot
        snapshot = prev.copy(
            lastActionType = actionType,
            lastTargetLabel = targetLabel.ifBlank { prev.lastTargetLabel },
            lastBounds = bounds ?: prev.lastBounds,
            lastScrollDirection = scrollDirection.ifBlank { prev.lastScrollDirection },
            lastTypedText = typedText.ifBlank { prev.lastTypedText },
            lastRecipient = recipient.ifBlank { prev.lastRecipient },
            lastSearchQuery = searchQuery.ifBlank { prev.lastSearchQuery },
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun recordConversationTurn(
        assistantReply: String,
        remainingSteps: List<String> = snapshot.pendingChainSteps,
    ) {
        val prev = snapshot
        snapshot = prev.copy(
            lastAssistantReply = assistantReply.ifBlank { prev.lastAssistantReply },
            pendingChainSteps = remainingSteps,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun popNextPendingStep(): String? {
        val prev = snapshot
        val next = prev.pendingChainSteps.firstOrNull() ?: return null
        snapshot = prev.copy(
            pendingChainSteps = prev.pendingChainSteps.drop(1),
            updatedAtMs = System.currentTimeMillis(),
        )
        return next
    }

    fun formatForPrompt(): String {
        val s = snapshot
        if (s.updatedAtMs == 0L) return ""
        return buildString {
            if (s.appLabel.isNotBlank() || s.packageName.isNotBlank()) {
                appendLine("- Active foreground app: ${s.appLabel.ifBlank { s.packageName }} (${s.packageName})")
            }
            if (s.lastTargetLabel.isNotBlank()) {
                appendLine("- Last UI target interacted with: '${s.lastTargetLabel}' via ${s.lastActionType.label}")
            }
            if (s.lastSearchQuery.isNotBlank()) {
                appendLine("- Last search query entered: '${s.lastSearchQuery}'")
            }
            if (s.lastTypedText.isNotBlank()) {
                appendLine("- Last text/message content: '${s.lastTypedText}'")
            }
            if (s.lastRecipient.isNotBlank()) {
                appendLine("- Last recipient/contact: '${s.lastRecipient}'")
            }
            if (s.lastAssistantReply.isNotBlank()) {
                appendLine("- Your previous spoken/text reply: \"${s.lastAssistantReply.take(180)}\"")
            }
            if (s.pendingChainSteps.isNotEmpty()) {
                appendLine("- Queued next steps in current workflow: ${s.pendingChainSteps.joinToString(" -> ")}")
            }
        }.trim()
    }

    /**
     * Resolves anaphoric or contextual target references such as "that", "it",
     * "the second one", "the first result", or "same thing again".
     */
    fun resolveTargetReference(rawTarget: String): String {
        val clean = rawTarget.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (lower in setOf("that", "it", "that one", "this", "this one", "same", "again", "open that", "tap that", "click that", "now open that")) {
            return snapshot.lastTargetLabel.ifBlank {
                snapshot.recentVisibleLabels.firstOrNull() ?: clean
            }
        }
        val ordinal = JarvisUiElementMatcher.extractOrdinalIndex(lower)
        val stripped = JarvisUiElementMatcher.stripOrdinalAndSpatialWords(lower)
        if (ordinal != null && stripped.isBlank()) {
            val list = snapshot.recentVisibleLabels
            if (ordinal == -1 && list.isNotEmpty()) return list.last()
            if (ordinal in 1..list.size) return "#$ordinal"
            return "#${max(1, ordinal)}"
        }
        return clean
    }
}

/**
 * Live Screen Observer that traverses `AccessibilityService` windows and nodes
 * to construct a rich [ObservedScreenState].
 */
object JarvisScreenObserver {

    const val MAX_OBSERVED_NODES = 450
    const val MAX_DEPTH = 18
    const val MAX_TEXT_LENGTH = 140
    const val OBSERVE_TIME_BUDGET_MS = 260L
    private const val SNAPSHOT_CACHE_TTL_MS = 90L

    private val snapshotSequence = java.util.concurrent.atomic.AtomicLong(1L)

    @Volatile
    private var lastCachedSnapshot: ObservedScreenState? = null

    private val DISMISS_LABELS = setOf(
        "dismiss", "close", "cancel", "not now", "no thanks", "skip",
        "later", "got it", "ok", "okay", "deny", "don't allow",
    )

    fun invalidateCache() {
        lastCachedSnapshot = null
    }

    fun observe(
        service: JarvisAccessibilityService,
        appLabelResolver: (String) -> String,
        forceRefresh: Boolean = true,
    ): ObservedScreenState {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = lastCachedSnapshot
            if (cached != null && (now - cached.timestampMs) <= SNAPSHOT_CACHE_TTL_MS) {
                return cached
            }
        }

        val (screenW, screenH) = service.getScreenDimensions()
        val rotation = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.display?.rotation ?: 0
            } else {
                0
            }
        }.getOrDefault(0)

        var keyboardVisible = false
        var keyboardTopY = screenH
        var windowTitle = ""
        var foregroundPackage = ""
        var activeWindowId = -1
        var activeWindowLayer = 0
        val observedWindows = mutableListOf<ObservedWindowInfo>()

        runCatching {
            val windows = service.windows
            for (window in windows) {
                val rect = Rect()
                runCatching { window.getBoundsInScreen(rect) }
                val wType = when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
                    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
                    else -> "OTHER_${window.type}"
                }
                val wTitle = window.title?.toString()?.trim().orEmpty()
                val wPkg = runCatching { window.root?.packageName?.toString().orEmpty() }.getOrDefault("")
                observedWindows += ObservedWindowInfo(
                    windowId = window.id,
                    windowType = wType,
                    title = wTitle.take(80),
                    packageName = wPkg,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom,
                    isActive = window.isActive,
                    isFocused = window.isFocused,
                    layer = window.layer,
                )

                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    keyboardVisible = true
                    if (rect.top in 1 until keyboardTopY) {
                        keyboardTopY = rect.top
                    }
                } else if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                    if (wTitle.isNotBlank()) windowTitle = wTitle
                    if (wPkg.isNotBlank()) foregroundPackage = wPkg
                    activeWindowId = window.id
                    activeWindowLayer = window.layer
                }
            }
        }

        val root = service.rootInActiveWindow
        if (foregroundPackage.isBlank()) {
            foregroundPackage = root?.packageName?.toString().orEmpty()
        }
        val appLabel = appLabelResolver(foregroundPackage)
        val isProtectedPkg = SecurityShield.isProtectedSystemPackage(foregroundPackage)

        val rawElements = mutableListOf<ObservedUiElement>()
        val parentToChildren = mutableMapOf<String, MutableList<String>>()
        var nextIndex = 1
        var activeDialogTitle: String? = null
        val startNs = System.nanoTime()

        fun extractSupportedActions(node: AccessibilityNodeInfo): Set<String> {
            val actions = mutableSetOf<String>()
            runCatching {
                for (action in node.actionList) {
                    when (action.id) {
                        AccessibilityNodeInfo.ACTION_CLICK -> actions += "ACTION_CLICK"
                        AccessibilityNodeInfo.ACTION_LONG_CLICK -> actions += "ACTION_LONG_CLICK"
                        AccessibilityNodeInfo.ACTION_SET_TEXT -> actions += "ACTION_SET_TEXT"
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> actions += "ACTION_SCROLL_FORWARD"
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> actions += "ACTION_SCROLL_BACKWARD"
                        AccessibilityNodeInfo.ACTION_FOCUS -> actions += "ACTION_FOCUS"
                        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> actions += "ACTION_CLEAR_FOCUS"
                        AccessibilityNodeInfo.ACTION_SELECT -> actions += "ACTION_SELECT"
                        AccessibilityNodeInfo.ACTION_COPY -> actions += "ACTION_COPY"
                        AccessibilityNodeInfo.ACTION_PASTE -> actions += "ACTION_PASTE"
                        else -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                            ) {
                                actions += "ACTION_IME_ENTER"
                            }
                        }
                    }
                }
            }
            if (actions.isEmpty()) {
                return ObservedUiElement.buildDefaultSupportedActions(
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    focusable = node.isFocusable,
                )
            }
            return actions
        }

        fun collectChildTextLabels(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
            if (depth > 3) return emptyList()
            val out = mutableListOf<String>()
            val count = node.childCount.coerceAtMost(24)
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                if (child.isPassword) continue
                val t = child.text?.toString()?.trim().orEmpty()
                val d = child.contentDescription?.toString()?.trim().orEmpty()
                if (t.isNotBlank()) out += SecurityShield.sanitizeUntrustedText(t, "screen_child").take(60)
                if (d.isNotBlank() && d != t) out += SecurityShield.sanitizeUntrustedText(d, "screen_child_desc").take(60)
                if (out.size < 4) {
                    out += collectChildTextLabels(child, depth + 1)
                }
            }
            return out.distinct().take(4)
        }

        fun walk(
            node: AccessibilityNodeInfo?,
            parentLabel: String,
            parentRef: String?,
            depth: Int,
        ) {
            if (node == null || depth > MAX_DEPTH || rawElements.size >= MAX_OBSERVED_NODES) return
            if ((System.nanoTime() - startNs) / 1_000_000L > OBSERVE_TIME_BUDGET_MS) return

            val cls = node.className?.toString().orEmpty()
            val isPassword = node.isPassword
            val rawText = if (isPassword) "" else node.text?.toString()?.trim().orEmpty()
            val rawDesc = if (isPassword) "" else node.contentDescription?.toString()?.trim().orEmpty()
            val rawHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val resId = node.viewIdResourceName?.trim().orEmpty()
            val pkg = node.packageName?.toString().orEmpty().ifBlank { foregroundPackage }

            val text = if (rawText.isNotBlank()) {
                SecurityShield.sanitizeUntrustedText(rawText, "screen:$pkg").take(MAX_TEXT_LENGTH)
            } else {
                ""
            }
            val desc = if (rawDesc.isNotBlank()) {
                SecurityShield.sanitizeUntrustedText(rawDesc, "screen_desc:$pkg").take(MAX_TEXT_LENGTH)
            } else {
                ""
            }
            val hint = if (rawHint.isNotBlank()) {
                SecurityShield.sanitizeUntrustedText(rawHint, "screen_hint:$pkg").take(100)
            } else {
                ""
            }

            if (activeDialogTitle == null &&
                (cls.contains("AlertDialog", ignoreCase = true) ||
                    cls.contains("Dialog", ignoreCase = true) ||
                    resId.endsWith("alertTitle", ignoreCase = true))
            ) {
                val candidateTitle = text.ifBlank { desc }
                if (candidateTitle.isNotBlank()) activeDialogTitle = candidateTitle
            }

            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }

            val childLabels = if (!isPassword && text.isBlank() && desc.isBlank() && (node.isClickable || node.isCheckable)) {
                collectChildTextLabels(node)
            } else {
                emptyList()
            }

            val inheritedParentLabel = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                childLabels.isNotEmpty() -> childLabels.first()
                else -> parentLabel
            }

            val isMeaningful = node.isVisibleToUser && (
                isPassword ||
                    text.isNotBlank() ||
                    desc.isNotBlank() ||
                    hint.isNotBlank() ||
                    resId.isNotBlank() ||
                    node.isClickable ||
                    node.isEditable ||
                    node.isCheckable ||
                    node.isScrollable ||
                    childLabels.isNotEmpty()
                )

            var currentElementRef = parentRef
            if (isMeaningful) {
                val idx = nextIndex++
                val ref = "ref_e$idx"
                currentElementRef = ref
                if (parentRef != null) {
                    parentToChildren.getOrPut(parentRef) { mutableListOf() }.add(ref)
                }
                val nodeWindowId = runCatching { node.windowId }.getOrDefault(activeWindowId)
                rawElements += ObservedUiElement(
                    index = idx,
                    resourceId = resId,
                    text = text,
                    contentDescription = desc,
                    hintText = hint,
                    className = cls,
                    packageName = pkg,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    selected = node.isSelected,
                    scrollable = node.isScrollable,
                    focusable = node.isFocusable,
                    focused = node.isFocused,
                    visibleToUser = node.isVisibleToUser,
                    bounds = rect,
                    parentLabel = parentLabel,
                    childLabels = childLabels,
                    depth = depth,
                    isInInputMethodWindow = false,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom,
                    elementRef = ref,
                    parentRef = parentRef,
                    windowId = nodeWindowId,
                    windowLayer = activeWindowLayer,
                    password = isPassword,
                    supportedActions = extractSupportedActions(node),
                )
            }

            val childCount = node.childCount.coerceAtMost(64)
            for (i in 0 until childCount) {
                if (rawElements.size >= MAX_OBSERVED_NODES) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                walk(child, inheritedParentLabel, currentElementRef, depth + 1)
            }
        }

        if (root != null && !isProtectedPkg) {
            walk(root, parentLabel = "", parentRef = null, depth = 0)
        }

        val elements = rawElements.map { el ->
            val children = parentToChildren[el.elementRef]
            if (!children.isNullOrEmpty()) el.copy(childRefs = children.toList()) else el
        }

        val dismissCandidate = elements.firstOrNull { el ->
            el.clickable && el.enabled && (
                el.primaryLabel.lowercase(Locale.ROOT) in DISMISS_LABELS ||
                    el.shortResourceId.lowercase(Locale.ROOT).let { id ->
                        id == "button2" || id.contains("close") || id.contains("dismiss") || id.contains("cancel")
                    }
                )
        }

        val seq = snapshotSequence.getAndIncrement()
        val snapId = "snap_${seq}_${now}_${foregroundPackage.substringAfterLast('.').ifBlank { "root" }}"

        val state = ObservedScreenState(
            packageName = foregroundPackage,
            appLabel = appLabel,
            windowTitle = windowTitle,
            screenWidth = screenW,
            screenHeight = screenH,
            rotation = rotation,
            keyboardVisible = keyboardVisible,
            keyboardTopY = keyboardTopY,
            activeDialogTitle = activeDialogTitle,
            dismissCandidate = dismissCandidate,
            elements = elements,
            timestampMs = now,
            snapshotId = snapId,
            screenVersion = seq,
            windows = observedWindows,
            isSecureOrProtected = isProtectedPkg,
        )
        lastCachedSnapshot = state
        return state
    }
}

// ============================================================================
// 15-STATE EXECUTION STATE MACHINE & STRUCTURED AUTOMATION DATA MODELS
// ============================================================================

/**
 * Exact 15-state execution state machine required for deterministic human-like
 * Android touch automation.
 */
enum class AutomationExecutionState {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    PLANNING,
    PERMISSION_CHECK,
    OBSERVING_SCREEN,
    LOCATING_TARGET,
    EXECUTING_ACTION,
    WAITING_FOR_UI,
    VERIFYING_ACTION,
    RECOVERING,
    WAITING_FOR_USER,
    TASK_COMPLETED,
    TASK_FAILED,
    CANCELLED,
}

/**
 * Risk classification for every planned or executed automation step.
 */
enum class AutomationRiskLevel {
    LOW_RISK,
    MEDIUM_RISK,
    HIGH_RISK,
}

data class AutomationTarget(
    val label: String,
    val resourceId: String = "",
    val contentDescription: String = "",
    val role: UiElementRole = UiElementRole.ANY,
    val expectedPackage: String = "",
    val bounds: Rect? = null,
    val ordinalIndex: Int? = null,
)

typealias AccessibilityElement = ObservedUiElement
typealias ScreenObservation = ObservedScreenState

data class VisualTarget(
    val label: String,
    val confidence: Float,
    val boundingBox: Rect,
    val sourceSnapshotId: String,
) {
    val centerX: Int get() = (boundingBox.left + boundingBox.right) / 2
    val centerY: Int get() = (boundingBox.top + boundingBox.bottom) / 2
}

data class ActionPrecondition(
    val expectedPackage: String? = null,
    val requiredElementLabel: String? = null,
    val requireEditableFocused: Boolean = false,
    val requireNoBlockingDialog: Boolean = true,
)

data class ActionPostcondition(
    val expectedPackage: String? = null,
    val expectedTextPresent: String? = null,
    val expectedElementPresent: String? = null,
    val expectedElementGone: String? = null,
    val expectedToggleChecked: Boolean? = null,
    val requireUiChange: Boolean = true,
)

data class ActionExecutionResult(
    val stepId: String,
    val interactionType: HumanInteractionType,
    val dispatched: Boolean,
    val usedAccessibilityNodeAction: Boolean,
    val coordinatesUsed: Pair<Int, Int>? = null,
    val durationMs: Long = 0L,
    val errorDetail: String? = null,
)

data class VerificationResult(
    val verified: Boolean,
    val status: VerificationStatus,
    val levelPassed: VerificationLevel?,
    val preconditionMet: Boolean,
    val postconditionMet: Boolean,
    val evidence: String,
)

data class RecoveryAttempt(
    val attemptNumber: Int,
    val actionType: RecoveryActionType,
    val reason: String,
    val succeeded: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class AutomationStep(
    val stepId: String,
    val index: Int,
    val description: String,
    val interactionType: HumanInteractionType,
    val target: AutomationTarget,
    val textInput: String = "",
    val riskLevel: AutomationRiskLevel = AutomationRiskLevel.LOW_RISK,
    val precondition: ActionPrecondition = ActionPrecondition(),
    val postcondition: ActionPostcondition = ActionPostcondition(),
    val timeoutMs: Long = 8_000L,
)

data class AutomationTask(
    val taskId: String,
    val rawCommand: String,
    val normalizedGoal: String,
    val targetAppName: String = "",
    val targetPackage: String = "",
    val steps: List<AutomationStep>,
    val riskLevel: AutomationRiskLevel = steps.maxOfOrNull { it.riskLevel } ?: AutomationRiskLevel.LOW_RISK,
    val requiresUserConfirmation: Boolean = riskLevel == AutomationRiskLevel.HIGH_RISK,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class StateTransitionRecord(
    val from: AutomationExecutionState,
    val to: AutomationExecutionState,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class AutomationSession(
    val sessionId: String,
    val task: AutomationTask,
    val state: AutomationExecutionState,
    val currentStepIndex: Int = 0,
    val transitions: List<StateTransitionRecord> = emptyList(),
    val executionResults: List<ActionExecutionResult> = emptyList(),
    val verifications: List<VerificationResult> = emptyList(),
    val recoveries: List<RecoveryAttempt> = emptyList(),
    val startedAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null,
)

/**
 * Thread-safe 15-state execution state machine with transition logging,
 * cancellation handling, and step timeout tracking.
 */
class AutomationStateMachine {
    private val transitions = java.util.concurrent.CopyOnWriteArrayList<StateTransitionRecord>()

    @Volatile
    var currentState: AutomationExecutionState = AutomationExecutionState.IDLE
        private set

    @Volatile
    var isCancelled: Boolean = false
        private set

    fun transitionTo(next: AutomationExecutionState, reason: String = ""): StateTransitionRecord {
        val previous = currentState
        val record = StateTransitionRecord(
            from = previous,
            to = next,
            reason = reason,
        )
        currentState = next
        if (next == AutomationExecutionState.CANCELLED) {
            isCancelled = true
        } else if (next == AutomationExecutionState.LISTENING || next == AutomationExecutionState.UNDERSTANDING) {
            isCancelled = false
        }
        transitions.add(record)
        return record
    }

    fun cancel(reason: String = "User requested stop"): StateTransitionRecord {
        isCancelled = true
        return transitionTo(AutomationExecutionState.CANCELLED, reason)
    }

    fun reset() {
        isCancelled = false
        currentState = AutomationExecutionState.IDLE
        transitions.clear()
    }

    fun history(): List<StateTransitionRecord> = transitions.toList()
}

/**
 * NaturalCommandDecomposer:
 * Understands natural-language single-step, compound multi-step, and contextual follow-up
 * phone automation commands, classifying risk levels and generating structured [AutomationTask]s.
 */
object NaturalCommandDecomposer {

    private val STOP_PHRASES = setOf(
        "stop", "cancel", "abort", "halt", "cancel what you're doing",
        "stop controlling the screen", "nevermind", "never mind",
    )

    fun isStopCommand(command: String): Boolean {
        val clean = command.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        return clean in STOP_PHRASES || clean.startsWith("cancel what you") || clean == "jarvis stop"
    }

    fun classifyRisk(stepDescription: String, interactionType: HumanInteractionType, targetLabel: String): AutomationRiskLevel {
        val combined = "$stepDescription $targetLabel".lowercase(Locale.ROOT)
        return when {
            Regex("""\b(send|pay|purchase|buy|transfer|delete|remove|uninstall|call|dial|post|publish|confirm)\b""").containsMatchIn(combined) ->
                AutomationRiskLevel.HIGH_RISK
            interactionType == HumanInteractionType.ENTER_TEXT ||
                Regex("""\b(bluetooth|wifi|wi-fi|dark mode|setting|toggle|switch|share|attach)\b""").containsMatchIn(combined) ->
                AutomationRiskLevel.MEDIUM_RISK
            else -> AutomationRiskLevel.LOW_RISK
        }
    }

    /**
     * Decomposes a natural-language command (including compound commands joined by
     * "and then", "then", "and", ",", or contextual follow-ups) into a structured [AutomationTask].
     */
    fun decompose(
        rawCommand: String,
        contextMemory: InteractionContextMemory = InteractionContextMemory(),
    ): AutomationTask {
        val trimmed = rawCommand.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val snap = contextMemory.current()

        // 1. Contextual follow-ups ("Now open that", "Go back", "Do the next step", "Send the same thing to Rahul", "Search for something similar")
        if (lower in setOf("go back", "back", "navigate back", "return")) {
            val step = AutomationStep(
                stepId = "step_1",
                index = 1,
                description = "Navigate back to previous screen",
                interactionType = HumanInteractionType.NAVIGATE_BACK,
                target = AutomationTarget(label = "Back", expectedPackage = snap.packageName),
                riskLevel = AutomationRiskLevel.LOW_RISK,
            )
            return AutomationTask(
                taskId = "task_${System.currentTimeMillis()}",
                rawCommand = trimmed,
                normalizedGoal = "Navigate back",
                targetPackage = snap.packageName,
                steps = listOf(step),
            )
        }

        if (lower in setOf("now open that", "open that", "tap that", "click that", "select that")) {
            val resolvedTarget = contextMemory.resolveTargetReference(lower)
            val step = AutomationStep(
                stepId = "step_1",
                index = 1,
                description = "Tap contextual target '$resolvedTarget'",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(label = resolvedTarget, expectedPackage = snap.packageName),
                riskLevel = AutomationRiskLevel.LOW_RISK,
            )
            return AutomationTask(
                taskId = "task_${System.currentTimeMillis()}",
                rawCommand = trimmed,
                normalizedGoal = "Open contextual target '$resolvedTarget'",
                targetPackage = snap.packageName,
                steps = listOf(step),
            )
        }

        if (lower.startsWith("send the same thing to ") || lower.startsWith("send that to ")) {
            val recipient = trimmed.replace(Regex("^(?i)(send the same thing to|send that to)\\s+"), "").trim()
            val messageToRepeat = snap.lastTypedText.ifBlank { snap.lastSearchQuery.ifBlank { "Hello!" } }
            return buildWhatsAppTextTask(trimmed, recipient, messageToRepeat)
        }

        if (lower.contains("search for something similar") || lower == "search similar") {
            val baseQuery = snap.lastSearchQuery.ifBlank { snap.lastTargetLabel.ifBlank { "lofi music" } }
            val similarQuery = "$baseQuery mix"
            return buildAppSearchTask(
                rawCommand = trimmed,
                appName = snap.appLabel.ifBlank { "YouTube" },
                packageName = snap.packageName.ifBlank { "com.google.android.youtube" },
                query = similarQuery,
                playFirstResult = false,
            )
        }

        if (lower in setOf("do the next step", "next step", "continue")) {
            val nextStepCmd = contextMemory.popNextPendingStep()
            if (!nextStepCmd.isNullOrBlank()) {
                return decompose(nextStepCmd, contextMemory)
            }
        }

        // 2. Check for multi-app compound chains ("Open YouTube, search for X, then open WhatsApp and send Rahul Y")
        val thenParts = trimmed.split(Regex("""\b(?:and\s+then|then)\b""", RegexOption.IGNORE_CASE))
            .map { it.trim().trim(',', ' ') }
            .filter { it.isNotBlank() }
        if (thenParts.size >= 2 && thenParts.drop(1).any { it.lowercase(Locale.ROOT).startsWith("open ") || it.lowercase(Locale.ROOT).startsWith("send ") || it.lowercase(Locale.ROOT).startsWith("play ") }) {
            val subTasks = thenParts.map { decomposeSingleGoal(it, snap) }
            var idx = 1
            val mergedSteps = subTasks.flatMap { sub ->
                sub.steps.map { step ->
                    val nextIdx = idx++
                    step.copy(stepId = "step_$nextIdx", index = nextIdx)
                }
            }
            return AutomationTask(
                taskId = "task_${System.currentTimeMillis()}",
                rawCommand = trimmed,
                normalizedGoal = subTasks.joinToString(" -> ") { it.normalizedGoal },
                targetAppName = subTasks.first().targetAppName,
                targetPackage = subTasks.first().targetPackage,
                steps = mergedSteps,
            )
        }

        return decomposeSingleGoal(trimmed, snap)
    }

    private fun decomposeSingleGoal(command: String, snap: InteractionSnapshot): AutomationTask {
        val lower = command.lowercase(Locale.ROOT)

        // YouTube Profile / Library / Subscriptions / History
        if ((lower.contains("youtube") || snap.packageName == "com.google.android.youtube") &&
            (lower.contains("profile") || lower.contains("my channel") || lower.contains("your channel") || lower.endsWith(" you tab"))
        ) {
            val steps = listOf(
                AutomationStep(
                    stepId = "step_1",
                    index = 1,
                    description = "Launch YouTube and verify foreground package",
                    interactionType = HumanInteractionType.SINGLE_TAP,
                    target = AutomationTarget(
                        label = "YouTube",
                        expectedPackage = "com.google.android.youtube",
                    ),
                    postcondition = ActionPostcondition(expectedPackage = "com.google.android.youtube"),
                ),
                AutomationStep(
                    stepId = "step_2",
                    index = 2,
                    description = "Tap bottom-right 'You' / Profile navigation tab in YouTube (avoiding Shorts)",
                    interactionType = HumanInteractionType.SELECT_UI_ELEMENT,
                    target = AutomationTarget(
                        label = "You",
                        contentDescription = "You",
                        role = UiElementRole.TAB,
                        expectedPackage = "com.google.android.youtube",
                    ),
                    precondition = ActionPrecondition(expectedPackage = "com.google.android.youtube"),
                    postcondition = ActionPostcondition(
                        expectedPackage = "com.google.android.youtube",
                        expectedElementPresent = "History",
                    ),
                ),
            )
            return AutomationTask(
                taskId = "task_${System.currentTimeMillis()}",
                rawCommand = command,
                normalizedGoal = "Open YouTube Profile ('You' tab)",
                targetAppName = "YouTube",
                targetPackage = "com.google.android.youtube",
                steps = steps,
            )
        }

        // WhatsApp voice message / motivational voice note
        if (lower.contains("whatsapp") && (lower.contains("voice message") || lower.contains("voice note") || lower.contains("audio message"))) {
            val recipientMatch = Regex("""(?:to|find)\s+([a-zA-Z0-9_ ]+?)(?:\s+and\b|\s+saying\b|\s+with\b|\s+a\s+motivational|$)""", RegexOption.IGNORE_CASE)
                .find(command)
            val recipient = recipientMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: "my friend"
            return buildWhatsAppVoiceNoteTask(command, recipient)
        }

        // WhatsApp text message ("Open WhatsApp, find Rahul, and send him this message...")
        if (lower.contains("whatsapp") && (lower.contains("send") || lower.contains("message"))) {
            val findMatch = Regex("""find\s+([a-zA-Z0-9_ ]+?)(?:,|\s+and\b)""", RegexOption.IGNORE_CASE).find(command)
            val toMatch = Regex("""send\s+([a-zA-Z0-9_ ]+?)\s+(?:a\s+message|this\s+message|that)""", RegexOption.IGNORE_CASE).find(command)
            val recipient = (findMatch?.groupValues?.get(1) ?: toMatch?.groupValues?.get(1) ?: "contact").trim()
            val msgAfterColon = command.substringAfter(":", "").trim()
            val msg = msgAfterColon.ifBlank { snap.lastTypedText.ifBlank { "Hello!" } }
            return buildWhatsAppTextTask(command, recipient, msg)
        }

        // YouTube search & optional play first result
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("find"))) {
            val playFirst = lower.contains("play the first") || lower.contains("play first") ||
                (lower.contains("play ") && !lower.contains("search"))
            val query = extractSearchQuery(command, "youtube")
            return buildAppSearchTask(
                rawCommand = command,
                appName = "YouTube",
                packageName = "com.google.android.youtube",
                query = query,
                playFirstResult = playFirst,
            )
        }

        // Instagram search account & open profile
        if (lower.contains("instagram") && (lower.contains("search") || lower.contains("profile") || lower.contains("find"))) {
            val query = extractSearchQuery(command, "instagram")
            val steps = mutableListOf(
                AutomationStep(
                    stepId = "step_1",
                    index = 1,
                    description = "Launch Instagram",
                    interactionType = HumanInteractionType.SINGLE_TAP,
                    target = AutomationTarget(label = "Instagram", expectedPackage = "com.instagram.android"),
                    postcondition = ActionPostcondition(expectedPackage = "com.instagram.android"),
                ),
                AutomationStep(
                    stepId = "step_2",
                    index = 2,
                    description = "Tap Search tab in Instagram",
                    interactionType = HumanInteractionType.SELECT_UI_ELEMENT,
                    target = AutomationTarget(
                        label = "Search and explore",
                        resourceId = "com.instagram.android:id/search_tab",
                        role = UiElementRole.TAB,
                        expectedPackage = "com.instagram.android",
                    ),
                ),
                AutomationStep(
                    stepId = "step_3",
                    index = 3,
                    description = "Enter '$query' in Instagram search bar",
                    interactionType = HumanInteractionType.ENTER_TEXT,
                    target = AutomationTarget(
                        label = "Search",
                        resourceId = "com.instagram.android:id/action_bar_search_edit_text",
                        role = UiElementRole.TEXT_FIELD,
                        expectedPackage = "com.instagram.android",
                    ),
                    textInput = query,
                    riskLevel = AutomationRiskLevel.MEDIUM_RISK,
                ),
                AutomationStep(
                    stepId = "step_4",
                    index = 4,
                    description = "Tap matching account profile '$query'",
                    interactionType = HumanInteractionType.SINGLE_TAP,
                    target = AutomationTarget(
                        label = query,
                        role = UiElementRole.LIST_ITEM,
                        expectedPackage = "com.instagram.android",
                    ),
                ),
            )
            return AutomationTask(
                taskId = "task_${System.currentTimeMillis()}",
                rawCommand = command,
                normalizedGoal = "Open Instagram, search '$query', and open profile",
                targetAppName = "Instagram",
                targetPackage = "com.instagram.android",
                steps = steps,
            )
        }

        // Settings toggle (Bluetooth, Wi-Fi, Dark mode)
        if (lower.contains("settings") || lower.contains("bluetooth") || lower.contains("dark mode") || lower.contains("wi-fi") || lower.contains("wifi")) {
            val settingName = when {
                lower.contains("bluetooth") -> "Bluetooth"
                lower.contains("dark mode") || lower.contains("dark theme") -> "Dark mode"
                lower.contains("wi-fi") || lower.contains("wifi") -> "Wi-Fi"
                else -> "Settings"
            }
            val wantEnable = !lower.contains("turn off") && !lower.contains("disable")
            val steps = listOf(
                AutomationStep(
                    stepId = "step_1",
                    index = 1,
                    description = "Open Settings screen for $settingName",
                    interactionType = HumanInteractionType.SINGLE_TAP,
                    target = AutomationTarget(label = settingName, expectedPackage = "com.android.settings"),
                    postcondition = ActionPostcondition(expectedPackage = "com.android.settings"),
                ),
                AutomationStep(
                    stepId = "step_2",
                    index = 2,
                    description = "${if (wantEnable) "Enable" else "Disable"} $settingName toggle",
                    interactionType = HumanInteractionType.SELECT_UI_ELEMENT,
                    target = AutomationTarget(
                        label = settingName,
                        role = UiElementRole.TOGGLE,
                        expectedPackage = "com.android.settings",
                    ),
                    riskLevel = AutomationRiskLevel.MEDIUM_RISK,
                    postcondition = ActionPostcondition(expectedToggleChecked = wantEnable),
                ),
            )
            return AutomationTask(
                taskId = "task_${System.currentTimeMillis()}",
                rawCommand = command,
                normalizedGoal = "${if (wantEnable) "Turn on" else "Turn off"} $settingName in Settings",
                targetAppName = "Settings",
                targetPackage = "com.android.settings",
                steps = steps,
            )
        }

        // Chrome search
        if (lower.contains("chrome") && lower.contains("search")) {
            val query = extractSearchQuery(command, "chrome")
            return buildAppSearchTask(
                rawCommand = command,
                appName = "Chrome",
                packageName = "com.android.chrome",
                query = query,
                playFirstResult = false,
            )
        }

        // Generic tap / open command
        val resolvedTarget = if (lower.startsWith("open ")) {
            command.substring(5).trim()
        } else if (lower.startsWith("tap ") || lower.startsWith("click ")) {
            command.replace(Regex("^(?i)(tap|click)\\s+(on\\s+)?"), "").trim()
        } else {
            command
        }
        val appProfile = JarvisAppRegistry.findBySpokenName(resolvedTarget)
        val step = AutomationStep(
            stepId = "step_1",
            index = 1,
            description = "Interact with '$resolvedTarget'",
            interactionType = HumanInteractionType.SINGLE_TAP,
            target = AutomationTarget(
                label = resolvedTarget,
                expectedPackage = appProfile?.packageNames?.firstOrNull().orEmpty(),
            ),
            riskLevel = classifyRisk(command, HumanInteractionType.SINGLE_TAP, resolvedTarget),
        )
        return AutomationTask(
            taskId = "task_${System.currentTimeMillis()}",
            rawCommand = command,
            normalizedGoal = command,
            targetAppName = appProfile?.canonicalName.orEmpty(),
            targetPackage = appProfile?.packageNames?.firstOrNull().orEmpty(),
            steps = listOf(step),
        )
    }

    private fun extractSearchQuery(command: String, appKeyword: String): String {
        val afterSearch = Regex("""search(?:\s+for)?\s+(.+?)(?:\s+and\s+(?:play|open|show|tap)|$)""", RegexOption.IGNORE_CASE)
            .find(command)?.groupValues?.get(1)?.trim()
        if (!afterSearch.isNullOrBlank()) {
            return afterSearch.replace(Regex("""(?i)\s+on\s+$appKeyword$"""), "").trim('"', '\'', '.', ' ')
        }
        val afterPlay = Regex("""play\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(command)?.groupValues?.get(1)?.trim()
        return (afterPlay ?: command).trim('"', '\'', '.', ' ')
    }

    private fun buildAppSearchTask(
        rawCommand: String,
        appName: String,
        packageName: String,
        query: String,
        playFirstResult: Boolean,
    ): AutomationTask {
        val profile = JarvisAppRegistry.findByPackage(packageName)
        val steps = mutableListOf(
            AutomationStep(
                stepId = "step_1",
                index = 1,
                description = "Launch $appName",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(label = appName, expectedPackage = packageName),
                postcondition = ActionPostcondition(expectedPackage = packageName),
            ),
            AutomationStep(
                stepId = "step_2",
                index = 2,
                description = "Tap Search button in $appName",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(
                    label = "Search",
                    resourceId = profile?.searchButtonIds?.firstOrNull().orEmpty(),
                    role = UiElementRole.SEARCH_TRIGGER,
                    expectedPackage = packageName,
                ),
                precondition = ActionPrecondition(expectedPackage = packageName),
            ),
            AutomationStep(
                stepId = "step_3",
                index = 3,
                description = "Type '$query' into search box and submit",
                interactionType = HumanInteractionType.ENTER_TEXT,
                target = AutomationTarget(
                    label = "Search",
                    resourceId = profile?.searchInputIds?.firstOrNull().orEmpty(),
                    role = UiElementRole.TEXT_FIELD,
                    expectedPackage = packageName,
                ),
                textInput = query,
                riskLevel = AutomationRiskLevel.MEDIUM_RISK,
                postcondition = ActionPostcondition(expectedPackage = packageName, expectedTextPresent = query),
            ),
        )
        if (playFirstResult) {
            steps += AutomationStep(
                stepId = "step_4",
                index = 4,
                description = "Tap the first video result for '$query'",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(
                    label = "first result",
                    role = UiElementRole.VIDEO_CARD,
                    expectedPackage = packageName,
                    ordinalIndex = 1,
                ),
                postcondition = ActionPostcondition(expectedPackage = packageName, requireUiChange = true),
            )
        }
        return AutomationTask(
            taskId = "task_${System.currentTimeMillis()}",
            rawCommand = rawCommand,
            normalizedGoal = if (playFirstResult) "Search $appName for '$query' and play first result" else "Search $appName for '$query'",
            targetAppName = appName,
            targetPackage = packageName,
            steps = steps,
        )
    }

    private fun buildWhatsAppTextTask(
        rawCommand: String,
        recipient: String,
        message: String,
    ): AutomationTask {
        val steps = listOf(
            AutomationStep(
                stepId = "step_1",
                index = 1,
                description = "Launch WhatsApp",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(label = "WhatsApp", expectedPackage = "com.whatsapp"),
                postcondition = ActionPostcondition(expectedPackage = "com.whatsapp"),
            ),
            AutomationStep(
                stepId = "step_2",
                index = 2,
                description = "Search for contact '$recipient'",
                interactionType = HumanInteractionType.ENTER_TEXT,
                target = AutomationTarget(
                    label = "Search",
                    resourceId = "com.whatsapp:id/menuitem_search",
                    role = UiElementRole.SEARCH_TRIGGER,
                    expectedPackage = "com.whatsapp",
                ),
                textInput = recipient,
                riskLevel = AutomationRiskLevel.MEDIUM_RISK,
            ),
            AutomationStep(
                stepId = "step_3",
                index = 3,
                description = "Open conversation with '$recipient'",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(label = recipient, role = UiElementRole.LIST_ITEM, expectedPackage = "com.whatsapp"),
                postcondition = ActionPostcondition(expectedElementPresent = recipient),
            ),
            AutomationStep(
                stepId = "step_4",
                index = 4,
                description = "Enter message '$message' in WhatsApp chat field",
                interactionType = HumanInteractionType.ENTER_TEXT,
                target = AutomationTarget(
                    label = "Message",
                    resourceId = "com.whatsapp:id/entry",
                    role = UiElementRole.TEXT_FIELD,
                    expectedPackage = "com.whatsapp",
                ),
                textInput = message,
                riskLevel = AutomationRiskLevel.MEDIUM_RISK,
                postcondition = ActionPostcondition(expectedTextPresent = message),
            ),
            AutomationStep(
                stepId = "step_5",
                index = 5,
                description = "Tap WhatsApp Send button (requires user confirmation)",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(
                    label = "Send",
                    resourceId = "com.whatsapp:id/send",
                    contentDescription = "Send",
                    role = UiElementRole.BUTTON,
                    expectedPackage = "com.whatsapp",
                ),
                riskLevel = AutomationRiskLevel.HIGH_RISK,
                postcondition = ActionPostcondition(requireUiChange = true),
            ),
        )
        return AutomationTask(
            taskId = "task_${System.currentTimeMillis()}",
            rawCommand = rawCommand,
            normalizedGoal = "Send WhatsApp message to $recipient",
            targetAppName = "WhatsApp",
            targetPackage = "com.whatsapp",
            steps = steps,
            riskLevel = AutomationRiskLevel.HIGH_RISK,
            requiresUserConfirmation = true,
        )
    }

    private fun buildWhatsAppVoiceNoteTask(
        rawCommand: String,
        recipient: String,
    ): AutomationTask {
        val steps = listOf(
            AutomationStep(
                stepId = "step_1",
                index = 1,
                description = "Launch WhatsApp",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(label = "WhatsApp", expectedPackage = "com.whatsapp"),
                postcondition = ActionPostcondition(expectedPackage = "com.whatsapp"),
            ),
            AutomationStep(
                stepId = "step_2",
                index = 2,
                description = "Open conversation with '$recipient'",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(label = recipient, role = UiElementRole.LIST_ITEM, expectedPackage = "com.whatsapp"),
                postcondition = ActionPostcondition(expectedElementPresent = recipient),
            ),
            AutomationStep(
                stepId = "step_3",
                index = 3,
                description = "Press and hold WhatsApp voice note microphone control",
                interactionType = HumanInteractionType.PRESS_AND_HOLD,
                target = AutomationTarget(
                    label = "Voice message",
                    resourceId = "com.whatsapp:id/voice_note_btn",
                    contentDescription = "Voice message",
                    role = UiElementRole.BUTTON,
                    expectedPackage = "com.whatsapp",
                ),
                riskLevel = AutomationRiskLevel.MEDIUM_RISK,
            ),
            AutomationStep(
                stepId = "step_4",
                index = 4,
                description = "Verify voice note recording / send after owner confirmation",
                interactionType = HumanInteractionType.SINGLE_TAP,
                target = AutomationTarget(
                    label = "Send",
                    resourceId = "com.whatsapp:id/voice_note_send_btn",
                    contentDescription = "Send",
                    role = UiElementRole.BUTTON,
                    expectedPackage = "com.whatsapp",
                ),
                riskLevel = AutomationRiskLevel.HIGH_RISK,
                postcondition = ActionPostcondition(requireUiChange = true),
            ),
        )
        return AutomationTask(
            taskId = "task_${System.currentTimeMillis()}",
            rawCommand = rawCommand,
            normalizedGoal = "Send WhatsApp motivational voice message to $recipient",
            targetAppName = "WhatsApp",
            targetPackage = "com.whatsapp",
            steps = steps,
            riskLevel = AutomationRiskLevel.HIGH_RISK,
            requiresUserConfirmation = true,
        )
    }
}

/**
 * WhatsAppVoiceWorkflowEngine:
 * Explicitly supports both required WhatsApp voice-message modes:
 * - [Mode.MODE_A_GENUINE_WHATSAPP_MIC]: Opens the conversation, locates the WhatsApp microphone
 *   control (`com.whatsapp:id/voice_note_btn` / `"Voice message"`), prepares/locks recording,
 *   and verifies the recording preview or sent bubble.
 * - [Mode.MODE_B_AI_GENERATED_AUDIO_ATTACHMENT]: Generates a motivational thought, displays/speaks
 *   it to the user, synthesizes an audio file via TTS, and shares it into WhatsApp as an audio
 *   attachment — explicitly disclosing that it is an AI-generated audio attachment rather than
 *   claiming an ordinary attachment is a native PTT voice note.
 */
object WhatsAppVoiceWorkflowEngine {

    enum class Mode {
        MODE_A_GENUINE_WHATSAPP_MIC,
        MODE_B_AI_GENERATED_AUDIO_ATTACHMENT,
    }

    data class VoiceWorkflowPlan(
        val mode: Mode,
        val recipient: String,
        val motivationalScript: String,
        val requiresSendConfirmation: Boolean = true,
        val disclosureNote: String,
        val task: AutomationTask,
    )

    fun generateMotivationalScript(recipient: String, customTopic: String = ""): String {
        val cleanName = recipient.takeIf { it.isNotBlank() && !it.equals("my friend", ignoreCase = true) }
        val greeting = if (cleanName != null) "Hey $cleanName," else "Hey,"
        return if (customTopic.isNotBlank()) {
            "$greeting just wanted to send a quick note on $customTopic — stay focused, trust your preparation, and keep pushing forward. You've got this."
        } else {
            "$greeting just a quick reminder today: every disciplined step you take compounds. Stay focused, trust your work, and keep moving forward. You've got this."
        }
    }

    fun buildPlan(
        rawCommand: String,
        recipient: String,
        preferredMode: Mode = Mode.MODE_A_GENUINE_WHATSAPP_MIC,
        customTopic: String = "",
    ): VoiceWorkflowPlan {
        val script = generateMotivationalScript(recipient, customTopic)
        val task = NaturalCommandDecomposer.decompose(
            "Open WhatsApp and send $recipient a motivational voice message",
        )
        val disclosure = when (preferredMode) {
            Mode.MODE_A_GENUINE_WHATSAPP_MIC ->
                "Mode A (Native WhatsApp Microphone Voice Note): Jarvis locates WhatsApp's 'Voice message' mic control, locks hands-free recording, speaks the motivational script, and verifies the outgoing voice note."
            Mode.MODE_B_AI_GENERATED_AUDIO_ATTACHMENT ->
                "Mode B (AI-Generated Audio Attachment): Jarvis synthesizes the motivational script to an audio file and shares it to $recipient via WhatsApp's share sheet as an audio file attachment (not a native PTT mic recording)."
        }
        return VoiceWorkflowPlan(
            mode = preferredMode,
            recipient = recipient,
            motivationalScript = script,
            requiresSendConfirmation = true,
            disclosureNote = disclosure,
            task = task,
        )
    }
}

