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
 * Structured representation of a single node on the live Android screen.
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
            editable || cls.contains("edittext") || cls.contains("autocompletetextview") ->
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
}

/**
 * Complete structured observation of the active Android display.
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
) {
    fun summaryHeader(): String = buildString {
        append("App: ").append(appLabel.ifBlank { packageName })
        append(" (").append(packageName).append(")")
        if (windowTitle.isNotBlank()) append(" | Window: ").append(windowTitle)
        append(" | Screen: ").append(screenWidth).append("x").append(screenHeight)
        if (keyboardVisible) append(" | Keyboard: VISIBLE")
        if (!activeDialogTitle.isNullOrBlank()) append(" | Dialog: ").append(activeDialogTitle)
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

        // Direct #index lookup when user or planner specifies "#3" or "3"
        val pureIndex = Regex("""^#?(\d{1,3})$""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        if (pureIndex != null) {
            val exactIndexNode = screen.elements.firstOrNull { it.index == pureIndex }
            if (exactIndexNode != null) {
                val candidate = ElementMatchCandidate(
                    element = exactIndexNode,
                    score = 160,
                    confidence = 0.98f,
                    matchedBy = listOf("exact_index:#$pureIndex"),
                )
                return ElementMatchResolution(candidate, emptyList(), false)
            }
        }

        val needle = raw.lowercase(Locale.ROOT)
        val cleanNeedle = stripOrdinalAndSpatialWords(raw).ifBlank { needle }
        val needleTokens = cleanNeedle.split(' ', '_', '-', '/').filter { it.length >= 2 }
        val ordinalHint = extractOrdinalIndex(raw)
        val wantTop = needle.contains("top") || needle.contains("upper")
        val wantBottom = needle.contains("bottom") || needle.contains("lower")
        val wantLeft = needle.contains("left")
        val wantRight = needle.contains("right")

        val scored = mutableListOf<ElementMatchCandidate>()

        for (el in screen.elements) {
            if (!el.visibleToUser) continue
            if (!el.hasValidTouchBounds(screen.screenWidth, screen.screenHeight)) continue

            var score = 0
            val signals = mutableListOf<String>()

            val resIdLower = el.resourceId.lowercase(Locale.ROOT)
            val shortIdLower = el.shortResourceId.lowercase(Locale.ROOT)
            val textLower = el.text.lowercase(Locale.ROOT)
            val descLower = el.contentDescription.lowercase(Locale.ROOT)
            val hintLower = el.hintText.lowercase(Locale.ROOT)
            val parentLower = el.parentLabel.lowercase(Locale.ROOT)
            val childrenLower = el.childLabels.joinToString(" ").lowercase(Locale.ROOT)

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
            }

            // 2. Exact content description (+110)
            if (descLower.isNotBlank()) {
                when {
                    descLower == needle || descLower == cleanNeedle -> {
                        score += 110
                        signals += "exact_content_desc"
                    }
                    Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(descLower) -> {
                        score += 82
                        signals += "word_content_desc"
                    }
                    cleanNeedle.length >= 2 && descLower.contains(cleanNeedle) -> {
                        score += 56
                        signals += "substring_content_desc"
                    }
                }
            }

            // 3. Exact visible text (+105)
            if (textLower.isNotBlank()) {
                when {
                    textLower == needle || textLower == cleanNeedle -> {
                        score += 105
                        signals += "exact_text"
                    }
                    Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(textLower) -> {
                        score += 78
                        signals += "word_text"
                    }
                    cleanNeedle.length >= 2 && textLower.contains(cleanNeedle) -> {
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

            // Token overlap when multi-word target is given
            if (score == 0 && needleTokens.size >= 2) {
                val combined = "$textLower $descLower $hintLower $shortIdLower $childrenLower $parentLower"
                val matchedTokens = needleTokens.count { combined.contains(it) }
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
            if (query.preferEditable && el.editable) {
                score += 45
                signals += "editable"
            }
            if (query.preferCheckable && (el.checkable || inferredRole == UiElementRole.TOGGLE)) {
                score += 45
                signals += "checkable"
            }

            // 5. Parent-child relationships (+24)
            if (cleanNeedle.isNotBlank()) {
                if (childrenLower == cleanNeedle || Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(childrenLower)) {
                    score += 68
                    signals += "child_label_match"
                } else if (parentLower == cleanNeedle || Regex("""\b${Regex.escape(cleanNeedle)}\b""").containsMatchIn(parentLower)) {
                    score += if (el.checkable || el.clickable || el.editable) 64 else 24
                    signals += "parent_context_match"
                }
            }

            // If we still have no semantic match and no role-only query, skip
            if (score == 0 && ordinalHint == null) continue

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
        val pkgMatched = !expectedPackage.isNullOrBlank() &&
            after.packageName.equals(expectedPackage, ignoreCase = true)
        val textMatched = !expectedTextInEditable.isNullOrBlank() &&
            after.editableTexts.any { it.lowercase(Locale.ROOT).contains(expectedTextInEditable.trim().lowercase(Locale.ROOT)) }
        val toggleMatched = expectedToggleState != null && actualToggleStateAfter == expectedToggleState
        val dialogDismissed = dialogWasPresentBefore && !dialogPresentAfter

        val level3Passed = pkgMatched || textMatched || toggleMatched || dialogDismissed ||
            (expectedPackage == null && expectedTextInEditable == null && expectedToggleState == null && !dialogWasPresentBefore && uiChanged)

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
 *
 * Maps common applications to their package names, known view IDs, deep-link
 * patterns, and preferred integration hierarchy:
 * 1. Official Android API / System Service
 * 2. Official App Intent / Deep Link
 * 3. Accessible UI Interaction (Resource ID -> Semantic Label)
 * 4. Visual / Coordinate Gesture Fallback
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
 *
 * Tracks the active and previous app, last tapped/focused UI target, last action,
 * last scroll direction, and recent candidate list so follow-up commands like:
 * - "Open that" / "Tap that"
 * - "Click the second one"
 * - "Go back"
 * - "Do the same thing again"
 * - "Scroll a little further"
 * - "Open the app I was using"
 * work deterministically.
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
    ) {
        val prev = snapshot
        snapshot = prev.copy(
            lastActionType = actionType,
            lastTargetLabel = targetLabel.ifBlank { prev.lastTargetLabel },
            lastBounds = bounds ?: prev.lastBounds,
            lastScrollDirection = scrollDirection.ifBlank { prev.lastScrollDirection },
            lastTypedText = typedText.ifBlank { prev.lastTypedText },
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Resolves anaphoric or contextual target references such as "that", "it",
     * "the second one", "the first result", or "same thing again".
     */
    fun resolveTargetReference(rawTarget: String): String {
        val clean = rawTarget.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (lower in setOf("that", "it", "that one", "this", "this one", "same", "again", "open that", "tap that", "click that")) {
            return snapshot.lastTargetLabel.ifBlank { clean }
        }
        val ordinal = JarvisUiElementMatcher.extractOrdinalIndex(lower)
        val stripped = JarvisUiElementMatcher.stripOrdinalAndSpatialWords(lower)
        if (ordinal != null && stripped.isBlank()) {
            // Pure ordinal reference like "the second one" or "first one"
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

    private const val MAX_OBSERVED_NODES = 450
    private const val OBSERVE_TIME_BUDGET_MS = 260L

    private val DISMISS_LABELS = setOf(
        "dismiss", "close", "cancel", "not now", "no thanks", "skip",
        "later", "got it", "ok", "okay", "deny", "don't allow",
    )

    fun observe(
        service: JarvisAccessibilityService,
        appLabelResolver: (String) -> String,
    ): ObservedScreenState {
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

        runCatching {
            val windows = service.windows
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    keyboardVisible = true
                    val rect = Rect()
                    window.getBoundsInScreen(rect)
                    if (rect.top in 1 until keyboardTopY) {
                        keyboardTopY = rect.top
                    }
                } else if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                    val t = window.title?.toString()?.trim().orEmpty()
                    if (t.isNotBlank()) windowTitle = t
                    val rootPkg = window.root?.packageName?.toString().orEmpty()
                    if (rootPkg.isNotBlank()) foregroundPackage = rootPkg
                }
            }
        }

        val root = service.rootInActiveWindow
        if (foregroundPackage.isBlank()) {
            foregroundPackage = root?.packageName?.toString().orEmpty()
        }
        val appLabel = appLabelResolver(foregroundPackage)

        val elements = mutableListOf<ObservedUiElement>()
        var nextIndex = 1
        var activeDialogTitle: String? = null
        val startNs = System.nanoTime()

        fun collectChildTextLabels(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
            if (depth > 3) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
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

        fun walk(node: AccessibilityNodeInfo?, parentLabel: String, depth: Int) {
            if (node == null || elements.size >= MAX_OBSERVED_NODES) return
            if ((System.nanoTime() - startNs) / 1_000_000L > OBSERVE_TIME_BUDGET_MS) return

            val cls = node.className?.toString().orEmpty()
            val rawText = node.text?.toString()?.trim().orEmpty()
            val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
            val rawHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val resId = node.viewIdResourceName?.trim().orEmpty()
            val pkg = node.packageName?.toString().orEmpty().ifBlank { foregroundPackage }

            val text = if (rawText.isNotBlank()) {
                SecurityShield.sanitizeUntrustedText(rawText, "screen:$pkg").take(140)
            } else {
                ""
            }
            val desc = if (rawDesc.isNotBlank()) {
                SecurityShield.sanitizeUntrustedText(rawDesc, "screen_desc:$pkg").take(140)
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

            val childLabels = if (text.isBlank() && desc.isBlank() && (node.isClickable || node.isCheckable)) {
                collectChildTextLabels(node)
            } else {
                emptyList()
            }

            // Also inspect sibling switches/checkboxes inside settings rows
            val inheritedParentLabel = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                childLabels.isNotEmpty() -> childLabels.first()
                else -> parentLabel
            }

            val isMeaningful = node.isVisibleToUser && (
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

            if (isMeaningful) {
                elements += ObservedUiElement(
                    index = nextIndex++,
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
                )
            }

            for (i in 0 until node.childCount) {
                if (elements.size >= MAX_OBSERVED_NODES) break
                walk(node.getChild(i), inheritedParentLabel, depth + 1)
            }
        }

        if (root != null) {
            walk(root, parentLabel = "", depth = 0)
        }

        val dismissCandidate = elements.firstOrNull { el ->
            el.clickable && el.enabled && (
                el.primaryLabel.lowercase(Locale.ROOT) in DISMISS_LABELS ||
                    el.shortResourceId.lowercase(Locale.ROOT).let { id ->
                        id == "button2" || id.contains("close") || id.contains("dismiss") || id.contains("cancel")
                    }
                )
        }

        return ObservedScreenState(
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
        )
    }
}
