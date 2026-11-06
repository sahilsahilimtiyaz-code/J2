package com.jarvis.assistant.accessibility

import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Execution modality determining how the action trigger was dispatched.
 */
enum class TriggerExecutionMode {
    /** Executed natively via [AccessibilityNodeInfo.performAction]. */
    DIRECT_ACCESSIBILITY_ACTION,

    /** Delegated upward to a clickable or scrollable ancestor node. */
    ANCESTOR_ACCESSIBILITY_ACTION,

    /** Injected via physical touchscreen gesture ([JarvisAccessibilityService.dispatchGesture]). */
    PHYSICAL_GESTURE_DISPATCH,

    /** Hybrid multi-step trigger (e.g. click-to-focus followed by text injection). */
    HYBRID_COMPOSED,

    /** Action was blocked by safety rules or secure surface protections. */
    REJECTED_SAFETY,

    /** Dispatch failed across all execution paths. */
    FAILED,
}

/**
 * Represents the outcome of executing a programmatic action trigger.
 */
data class TriggerResult(
    val success: Boolean,
    val triggerName: String,
    val executionMode: TriggerExecutionMode,
    val message: String,
    val targetBounds: Rect? = null,
    val touchCoordinates: PointF? = null,
    val executionDurationMs: Long = 0L,
    val actionId: Int? = null,
)

/**
 * Optional arguments and overrides for programmatic trigger execution.
 */
data class TriggerOptions(
    val textArgument: String? = null,
    val durationMs: Long? = null,
    val swipeDistance: SwipeDistance = SwipeDistance.MEDIUM,
    val forcePhysicalGesture: Boolean = false,
    val targetDestinationBounds: Rect? = null,
    val customCoordinates: PointF? = null,
    val zoomIn: Boolean = true,
    val whyRationale: String = "",
)

/**
 * Distance factor for directional swipe and scroll gestures.
 */
enum class SwipeDistance(val factor: Float, val durationMs: Long) {
    SHORT(0.20f, 220L),
    MEDIUM(0.42f, 280L),
    LONG(0.65f, 350L),
}

/**
 * Direction enum for directional swipes and scrolls.
 */
enum class GestureDirection(val label: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun fromString(value: String): GestureDirection = when (value.lowercase(Locale.ROOT).trim()) {
            "up", "top", "scroll_up", "swipe_up", "backward" -> UP
            "left", "scroll_left", "swipe_left" -> LEFT
            "right", "scroll_right", "swipe_right" -> RIGHT
            else -> DOWN
        }
    }
}

/**
 * Strongly typed programmatic gesture trigger definitions.
 */
sealed class ProgrammaticGestureTrigger(
    val identifier: String,
    val label: String,
    val primaryAccessibilityActionId: Int?,
) {
    /** Single tap / click on target. */
    object Tap : ProgrammaticGestureTrigger("tap", "Single Tap", AccessibilityNodeInfo.ACTION_CLICK)

    /** Touch-and-hold / Long click with configurable hold duration. */
    data class TouchAndHold(val holdDurationMs: Long = 650L) :
        ProgrammaticGestureTrigger("touch_and_hold", "Touch & Hold", AccessibilityNodeInfo.ACTION_LONG_CLICK)

    /** Double-tap with two crisp strokes spaced by ~85ms. */
    object DoubleTap : ProgrammaticGestureTrigger("double_tap", "Double Tap", null)

    /** Directional swipe across element bounds or viewport. */
    data class Swipe(val direction: GestureDirection, val distance: SwipeDistance = SwipeDistance.MEDIUM) :
        ProgrammaticGestureTrigger("swipe_${direction.label}", "Swipe ${direction.label.replaceFirstChar { it.uppercase() }}", null)

    /** Directional scroll with node action and physical gesture fallback. */
    data class Scroll(val direction: GestureDirection, val distance: SwipeDistance = SwipeDistance.MEDIUM) :
        ProgrammaticGestureTrigger(
            "scroll_${direction.label}",
            "Scroll ${direction.label.replaceFirstChar { it.uppercase() }}",
            if (direction == GestureDirection.UP) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        )

    /** Direct text injection via ACTION_SET_TEXT with focus and paste fallback. */
    data class TypeText(val text: String) :
        ProgrammaticGestureTrigger("type_text", "Type Text", AccessibilityNodeInfo.ACTION_SET_TEXT)

    /** Clears text field content. */
    object ClearText : ProgrammaticGestureTrigger("clear_text", "Clear Text", AccessibilityNodeInfo.ACTION_SET_TEXT)

    /** Submits IME action (Enter / Search / Done) on the active keyboard. */
    object ImeSubmit : ProgrammaticGestureTrigger("ime_submit", "IME Submit", null)

    /** Dismisses dialog, bottom sheet, or popup. */
    object Dismiss : ProgrammaticGestureTrigger("dismiss", "Dismiss Dialog", AccessibilityNodeInfo.ACTION_DISMISS)

    /** Expands a collapsible container or dropdown. */
    object Expand : ProgrammaticGestureTrigger("expand", "Expand", AccessibilityNodeInfo.ACTION_EXPAND)

    /** Collapses an expanded container. */
    object Collapse : ProgrammaticGestureTrigger("collapse", "Collapse", AccessibilityNodeInfo.ACTION_COLLAPSE)

    /** Selects or checks the UI control. */
    object Select : ProgrammaticGestureTrigger("select", "Select", AccessibilityNodeInfo.ACTION_SELECT)

    /** Unselects or unchecks the UI control. */
    object ClearSelection : ProgrammaticGestureTrigger("clear_selection", "Clear Selection", AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)

    /** Continuous drag from source to destination. */
    data class DragAndDrop(val destinationBounds: Rect) :
        ProgrammaticGestureTrigger("drag_and_drop", "Drag & Drop", null)

    /** Multi-touch pinch or zoom. */
    data class PinchZoom(val zoomIn: Boolean = true) :
        ProgrammaticGestureTrigger("pinch_zoom", if (zoomIn) "Zoom In" else "Pinch Out", null)

    /** Generic action fallback for custom AccessibilityActions. */
    data class CustomAction(val actionId: Int, val customLabel: String) :
        ProgrammaticGestureTrigger("custom_$actionId", customLabel, actionId)
}

/**
 * Comprehensive utility class that maps [AccessibilityNodeInfo] actions to programmatic triggers,
 * enabling the application to perform gestures like touch, swipe, and scroll on behalf of the user.
 *
 * Implements a robust multi-tier execution strategy:
 * 1. Direct Accessibility Action ([AccessibilityNodeInfo.performAction])
 * 2. Ancestor / Container Delegation (for non-clickable text labels inside clickable cards/buttons)
 * 3. Physical Touch Gesture Dispatch ([JarvisAccessibilityService.dispatchGesture]) at calculated bounding coordinates
 * 4. Human-like spatial trajectory calculations for directional swipes and scrolls
 */
object AccessibilityActionTriggerMapper {

    private const val TAG = "JARVIS_TRIGGER_MAPPER"

    // =========================================================================
    // 1. MAPPING: Accessibility Action ID -> ProgrammaticGestureTrigger
    // =========================================================================

    /**
     * Maps an [AccessibilityNodeInfo] integer action ID to its corresponding [ProgrammaticGestureTrigger].
     */
    fun mapActionIdToTrigger(actionId: Int, options: TriggerOptions = TriggerOptions()): ProgrammaticGestureTrigger =
        when (actionId) {
            AccessibilityNodeInfo.ACTION_CLICK -> ProgrammaticGestureTrigger.Tap
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> ProgrammaticGestureTrigger.TouchAndHold(
                options.durationMs ?: 650L,
            )
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> ProgrammaticGestureTrigger.Scroll(
                GestureDirection.DOWN,
                options.swipeDistance,
            )
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> ProgrammaticGestureTrigger.Scroll(
                GestureDirection.UP,
                options.swipeDistance,
            )
            AccessibilityNodeInfo.ACTION_SET_TEXT -> ProgrammaticGestureTrigger.TypeText(
                options.textArgument.orEmpty(),
            )
            AccessibilityNodeInfo.ACTION_DISMISS -> ProgrammaticGestureTrigger.Dismiss
            AccessibilityNodeInfo.ACTION_EXPAND -> ProgrammaticGestureTrigger.Expand
            AccessibilityNodeInfo.ACTION_COLLAPSE -> ProgrammaticGestureTrigger.Collapse
            AccessibilityNodeInfo.ACTION_SELECT -> ProgrammaticGestureTrigger.Select
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> ProgrammaticGestureTrigger.ClearSelection
            AccessibilityNodeInfo.ACTION_PASTE -> ProgrammaticGestureTrigger.TypeText(
                options.textArgument.orEmpty(),
            )
            else -> ProgrammaticGestureTrigger.CustomAction(actionId, getActionLabel(actionId))
        }

    /**
     * Maps an [AccessibilityAction] object to its corresponding [ProgrammaticGestureTrigger].
     */
    fun mapAccessibilityActionToTrigger(
        action: AccessibilityAction,
        options: TriggerOptions = TriggerOptions(),
    ): ProgrammaticGestureTrigger = when (action.id) {
        AccessibilityAction.ACTION_SCROLL_DOWN.id -> ProgrammaticGestureTrigger.Scroll(
            GestureDirection.DOWN,
            options.swipeDistance,
        )
        AccessibilityAction.ACTION_SCROLL_UP.id -> ProgrammaticGestureTrigger.Scroll(
            GestureDirection.UP,
            options.swipeDistance,
        )
        AccessibilityAction.ACTION_SCROLL_LEFT.id -> ProgrammaticGestureTrigger.Scroll(
            GestureDirection.LEFT,
            options.swipeDistance,
        )
        AccessibilityAction.ACTION_SCROLL_RIGHT.id -> ProgrammaticGestureTrigger.Scroll(
            GestureDirection.RIGHT,
            options.swipeDistance,
        )
        AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> ProgrammaticGestureTrigger.Scroll(
            GestureDirection.DOWN,
            SwipeDistance.SHORT,
        )
        else -> mapActionIdToTrigger(action.id, options)
    }

    // =========================================================================
    // 2. MAPPING: Semantic Action Strings -> ProgrammaticGestureTrigger
    // =========================================================================

    /**
     * Maps semantic string commands (e.g. "tap", "swipe_left", "touch_and_hold", "scroll_down")
     * into a strongly typed [ProgrammaticGestureTrigger].
     */
    fun mapSemanticActionToTrigger(
        actionName: String,
        options: TriggerOptions = TriggerOptions(),
    ): ProgrammaticGestureTrigger {
        val normalized = actionName.lowercase(Locale.ROOT).trim()
        return when {
            normalized in setOf("tap", "click", "single_tap", "press") ->
                ProgrammaticGestureTrigger.Tap

            normalized in setOf("touch", "touch_and_hold", "long_press", "hold", "long_click", "press_and_hold") ->
                ProgrammaticGestureTrigger.TouchAndHold(options.durationMs ?: 650L)

            normalized in setOf("double_tap", "double_click") ->
                ProgrammaticGestureTrigger.DoubleTap

            normalized.startsWith("swipe_") -> {
                val dir = GestureDirection.fromString(normalized.removePrefix("swipe_"))
                ProgrammaticGestureTrigger.Swipe(dir, options.swipeDistance)
            }
            normalized in setOf("swipe", "swap", "flick", "slide") -> {
                val dir = GestureDirection.fromString(options.textArgument ?: "left")
                ProgrammaticGestureTrigger.Swipe(dir, options.swipeDistance)
            }

            normalized.startsWith("scroll_") -> {
                val dir = GestureDirection.fromString(normalized.removePrefix("scroll_"))
                ProgrammaticGestureTrigger.Scroll(dir, options.swipeDistance)
            }
            normalized in setOf("scroll", "scroll_forward") ->
                ProgrammaticGestureTrigger.Scroll(GestureDirection.DOWN, options.swipeDistance)
            normalized in setOf("scroll_backward") ->
                ProgrammaticGestureTrigger.Scroll(GestureDirection.UP, options.swipeDistance)

            normalized in setOf("type", "set_text", "enter_text", "write") ->
                ProgrammaticGestureTrigger.TypeText(options.textArgument.orEmpty())

            normalized in setOf("clear", "clear_text") ->
                ProgrammaticGestureTrigger.ClearText

            normalized in setOf("ime_enter", "ime_submit", "enter", "submit", "search_keyboard") ->
                ProgrammaticGestureTrigger.ImeSubmit

            normalized in setOf("dismiss", "close_dialog", "dismiss_dialog") ->
                ProgrammaticGestureTrigger.Dismiss

            normalized == "expand" -> ProgrammaticGestureTrigger.Expand
            normalized == "collapse" -> ProgrammaticGestureTrigger.Collapse
            normalized in setOf("select", "check") -> ProgrammaticGestureTrigger.Select
            normalized in setOf("unselect", "uncheck", "clear_selection") -> ProgrammaticGestureTrigger.ClearSelection

            normalized in setOf("drag", "drag_and_drop") -> {
                val dest = options.targetDestinationBounds ?: Rect(540, 1200, 540, 1200)
                ProgrammaticGestureTrigger.DragAndDrop(dest)
            }

            normalized in setOf("pinch_zoom", "zoom") ->
                ProgrammaticGestureTrigger.PinchZoom(options.zoomIn)

            else -> ProgrammaticGestureTrigger.Tap
        }
    }

    // =========================================================================
    // 3. EXECUTION: Programmatic Trigger Dispatch Pipeline
    // =========================================================================

    /**
     * Executes the given [trigger] on behalf of the user using the optimal execution path
     * (Direct API, Ancestor Delegation, or Physical Touch Gesture).
     *
     * @param node The target [AccessibilityNodeInfo] if available.
     * @param trigger The programmatic trigger to execute.
     * @param service The live [JarvisAccessibilityService] instance for physical gesture injection.
     * @param options Execution overrides (hold durations, swipe distances, text payloads).
     * @return [TriggerResult] detailing success, coordinates, mode, and diagnostics.
     */
    suspend fun executeTrigger(
        node: AccessibilityNodeInfo?,
        trigger: ProgrammaticGestureTrigger,
        service: JarvisAccessibilityService? = JarvisAccessibilityService.serviceInstance(),
        options: TriggerOptions = TriggerOptions(),
    ): TriggerResult {
        val startTime = System.currentTimeMillis()
        val bounds = Rect().also { node?.getBoundsInScreen(it) }
        val (screenWidth, screenHeight) = service?.getScreenDimensions() ?: (1080 to 2340)

        // Safety verification: Reject protected password fields from physical touch text recording
        if (node != null && node.isPassword && trigger is ProgrammaticGestureTrigger.TypeText) {
            return TriggerResult(
                success = false,
                triggerName = trigger.identifier,
                executionMode = TriggerExecutionMode.REJECTED_SAFETY,
                message = "Refused to inject text into protected password field.",
                targetBounds = bounds,
            )
        }

        // Fast path: User requested explicit physical touch gesture override
        if (options.forcePhysicalGesture && service != null) {
            val gestureResult = executePhysicalGesture(trigger, bounds, service, screenWidth, screenHeight, options)
            return gestureResult.copy(executionDurationMs = System.currentTimeMillis() - startTime)
        }

        // 1. Direct Accessibility Action Execution
        if (node != null && !options.forcePhysicalGesture) {
            val directResult = tryDirectAccessibilityAction(node, trigger, options)
            if (directResult != null && directResult.success) {
                return directResult.copy(
                    targetBounds = bounds,
                    touchCoordinates = PointF(bounds.exactCenterX(), bounds.exactCenterY()),
                    executionDurationMs = System.currentTimeMillis() - startTime,
                )
            }
        }

        // 2. Ancestor / Container Delegation (e.g. clicked a TextView inside a clickable LinearLayout)
        if (node != null && (trigger is ProgrammaticGestureTrigger.Tap || trigger is ProgrammaticGestureTrigger.TouchAndHold)) {
            val ancestorResult = tryAncestorDelegation(node, trigger)
            if (ancestorResult != null && ancestorResult.success) {
                return ancestorResult.copy(
                    targetBounds = bounds,
                    touchCoordinates = PointF(bounds.exactCenterX(), bounds.exactCenterY()),
                    executionDurationMs = System.currentTimeMillis() - startTime,
                )
            }
        }

        // 3. Physical Touch Gesture Dispatch Fallback
        if (service != null) {
            Log.d(TAG, "Accessibility action unconsumed for ${trigger.identifier}. Falling back to physical touch gesture.")
            val gestureResult = executePhysicalGesture(trigger, bounds, service, screenWidth, screenHeight, options)
            return gestureResult.copy(executionDurationMs = System.currentTimeMillis() - startTime)
        }

        return TriggerResult(
            success = false,
            triggerName = trigger.identifier,
            executionMode = TriggerExecutionMode.FAILED,
            message = "Neither direct accessibility action nor gesture injection service was available.",
            targetBounds = bounds,
            executionDurationMs = System.currentTimeMillis() - startTime,
        )
    }

    // =========================================================================
    // 4. DIRECT ACCESSIBILITY ACTION HANDLERS
    // =========================================================================

    private fun tryDirectAccessibilityAction(
        node: AccessibilityNodeInfo,
        trigger: ProgrammaticGestureTrigger,
        options: TriggerOptions,
    ): TriggerResult? = when (trigger) {
        is ProgrammaticGestureTrigger.Tap -> {
            if (node.isClickable) {
                val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Clicked node via ACTION_CLICK", actionId = AccessibilityNodeInfo.ACTION_CLICK) else null
            } else null
        }

        is ProgrammaticGestureTrigger.TouchAndHold -> {
            if (node.isLongClickable) {
                val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
                if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Long-clicked node via ACTION_LONG_CLICK", actionId = AccessibilityNodeInfo.ACTION_LONG_CLICK) else null
            } else null
        }

        is ProgrammaticGestureTrigger.Scroll -> {
            if (node.isScrollable) {
                val actionId = if (trigger.direction == GestureDirection.UP) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val ok = runCatching { node.performAction(actionId) }.getOrDefault(false)
                if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Scrolled node ${trigger.direction.label} via ACTION_SCROLL", actionId = actionId) else null
            } else null
        }

        is ProgrammaticGestureTrigger.TypeText -> {
            if (node.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, trigger.text)
                }
                val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
                if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Typed text via ACTION_SET_TEXT", actionId = AccessibilityNodeInfo.ACTION_SET_TEXT) else null
            } else null
        }

        is ProgrammaticGestureTrigger.ClearText -> {
            if (node.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
                if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Cleared text via ACTION_SET_TEXT", actionId = AccessibilityNodeInfo.ACTION_SET_TEXT) else null
            } else null
        }

        is ProgrammaticGestureTrigger.Expand -> {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_EXPAND) }.getOrDefault(false)
            if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Expanded node via ACTION_EXPAND", actionId = AccessibilityNodeInfo.ACTION_EXPAND) else null
        }

        is ProgrammaticGestureTrigger.Collapse -> {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE) }.getOrDefault(false)
            if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Collapsed node via ACTION_COLLAPSE", actionId = AccessibilityNodeInfo.ACTION_COLLAPSE) else null
        }

        is ProgrammaticGestureTrigger.Dismiss -> {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_DISMISS) }.getOrDefault(false)
            if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Dismissed node via ACTION_DISMISS", actionId = AccessibilityNodeInfo.ACTION_DISMISS) else null
        }

        is ProgrammaticGestureTrigger.Select -> {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SELECT) }.getOrDefault(false)
            if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Selected node via ACTION_SELECT", actionId = AccessibilityNodeInfo.ACTION_SELECT) else null
        }

        is ProgrammaticGestureTrigger.ClearSelection -> {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION) }.getOrDefault(false)
            if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Cleared selection via ACTION_CLEAR_SELECTION", actionId = AccessibilityNodeInfo.ACTION_CLEAR_SELECTION) else null
        }

        is ProgrammaticGestureTrigger.CustomAction -> {
            val ok = runCatching { node.performAction(trigger.actionId) }.getOrDefault(false)
            if (ok) TriggerResult(true, trigger.identifier, TriggerExecutionMode.DIRECT_ACCESSIBILITY_ACTION, "Performed custom action ${trigger.customLabel}", actionId = trigger.actionId) else null
        }

        else -> null
    }

    private fun tryAncestorDelegation(
        node: AccessibilityNodeInfo,
        trigger: ProgrammaticGestureTrigger,
    ): TriggerResult? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            when (trigger) {
                is ProgrammaticGestureTrigger.Tap -> {
                    if (current.isClickable) {
                        val ok = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                        if (ok) {
                            return TriggerResult(
                                true,
                                trigger.identifier,
                                TriggerExecutionMode.ANCESTOR_ACCESSIBILITY_ACTION,
                                "Delegated click to ancestor ${current.className} at depth $depth",
                                actionId = AccessibilityNodeInfo.ACTION_CLICK,
                            )
                        }
                    }
                }
                is ProgrammaticGestureTrigger.TouchAndHold -> {
                    if (current.isLongClickable) {
                        val ok = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
                        if (ok) {
                            return TriggerResult(
                                true,
                                trigger.identifier,
                                TriggerExecutionMode.ANCESTOR_ACCESSIBILITY_ACTION,
                                "Delegated long click to ancestor ${current.className} at depth $depth",
                                actionId = AccessibilityNodeInfo.ACTION_LONG_CLICK,
                            )
                        }
                    }
                }
                else -> break
            }
            current = current.parent
            depth++
        }
        return null
    }

    // =========================================================================
    // 5. PHYSICAL TOUCH GESTURE DISPATCHERS
    // =========================================================================

    private suspend fun executePhysicalGesture(
        trigger: ProgrammaticGestureTrigger,
        bounds: Rect,
        service: JarvisAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        options: TriggerOptions,
    ): TriggerResult {
        // Calculate center point or use custom coordinates
        val hasBounds = (bounds.right > bounds.left) && (bounds.bottom > bounds.top)
        val cx = options.customCoordinates?.x ?: if (hasBounds) (bounds.left + bounds.right) / 2f else screenWidth / 2f
        val cy = options.customCoordinates?.y ?: if (hasBounds) (bounds.top + bounds.bottom) / 2f else screenHeight / 2f
        val touchPoint = PointF(cx, cy)

        when (trigger) {
            is ProgrammaticGestureTrigger.Tap, is ProgrammaticGestureTrigger.Select, is ProgrammaticGestureTrigger.Expand, is ProgrammaticGestureTrigger.Collapse -> {
                val ok = service.dispatchTapGesture(cx, cy, durationMs = 60L)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Physical tap dispatched at (${cx.toInt()}, ${cy.toInt()})" else "Physical tap failed",
                    targetBounds = bounds,
                    touchCoordinates = touchPoint,
                )
            }

            is ProgrammaticGestureTrigger.TouchAndHold -> {
                val duration = (options.durationMs ?: trigger.holdDurationMs).coerceIn(400L, 3000L)
                val ok = service.dispatchLongPressGesture(cx, cy, durationMs = duration)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Physical long-press (${duration}ms) dispatched at (${cx.toInt()}, ${cy.toInt()})" else "Physical long-press failed",
                    targetBounds = bounds,
                    touchCoordinates = touchPoint,
                )
            }

            is ProgrammaticGestureTrigger.DoubleTap -> {
                val ok = service.dispatchDoubleTapGesture(cx, cy)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Physical double-tap dispatched at (${cx.toInt()}, ${cy.toInt()})" else "Physical double-tap failed",
                    targetBounds = bounds,
                    touchCoordinates = touchPoint,
                )
            }

            is ProgrammaticGestureTrigger.Swipe -> {
                val (sx, sy, ex, ey) = calculateDirectionalVector(
                    bounds = bounds,
                    direction = trigger.direction,
                    factor = trigger.distance.factor,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                )
                val ok = service.dispatchSwipeGesture(sx, sy, ex, ey, durationMs = trigger.distance.durationMs)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Physical swipe ${trigger.direction.label} dispatched from (${sx.toInt()}, ${sy.toInt()}) to (${ex.toInt()}, ${ey.toInt()})" else "Physical swipe failed",
                    targetBounds = bounds,
                    touchCoordinates = PointF(sx, sy),
                )
            }

            is ProgrammaticGestureTrigger.Scroll -> {
                val (sx, sy, ex, ey) = calculateDirectionalVector(
                    bounds = bounds,
                    direction = trigger.direction,
                    factor = trigger.distance.factor,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                )
                val ok = service.dispatchSwipeGesture(sx, sy, ex, ey, durationMs = trigger.distance.durationMs)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Physical scroll ${trigger.direction.label} dispatched from (${sx.toInt()}, ${sy.toInt()}) to (${ex.toInt()}, ${ey.toInt()})" else "Physical scroll failed",
                    targetBounds = bounds,
                    touchCoordinates = PointF(sx, sy),
                )
            }

            is ProgrammaticGestureTrigger.TypeText -> {
                // Hybrid: Tap element to gain input focus, then trigger IME input
                val tapped = service.dispatchTapGesture(cx, cy, durationMs = 60L)
                delay(180L)
                val typed = JarvisAccessibilityService.controller()?.typeVerified(trigger.text)
                val ok = (typed?.success == true) || tapped
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.HYBRID_COMPOSED,
                    message = if (ok) "Focused field at (${cx.toInt()}, ${cy.toInt()}) and typed text" else "Focus or text entry failed",
                    targetBounds = bounds,
                    touchCoordinates = touchPoint,
                )
            }

            is ProgrammaticGestureTrigger.ImeSubmit -> {
                val ok = service.performImeAction()
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "IME keyboard submit triggered" else "IME submit failed",
                    targetBounds = bounds,
                )
            }

            is ProgrammaticGestureTrigger.Dismiss -> {
                // Swipe down to dismiss sheet or perform back gesture
                val (sx, sy, ex, ey) = calculateDirectionalVector(
                    bounds = bounds,
                    direction = GestureDirection.DOWN,
                    factor = 0.50f,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                )
                val ok = service.dispatchSwipeGesture(sx, sy, ex, ey, durationMs = 260L)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Dismissal downward swipe dispatched" else "Dismiss gesture failed",
                    targetBounds = bounds,
                    touchCoordinates = PointF(sx, sy),
                )
            }

            is ProgrammaticGestureTrigger.DragAndDrop -> {
                val destX = (trigger.destinationBounds.left + trigger.destinationBounds.right) / 2f
                val destY = (trigger.destinationBounds.top + trigger.destinationBounds.bottom) / 2f
                val ok = service.dispatchDragGesture(cx, cy, destX, destY, durationMs = 480L)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Drag & Drop dispatched from (${cx.toInt()}, ${cy.toInt()}) to (${destX.toInt()}, ${destY.toInt()})" else "Drag gesture failed",
                    targetBounds = bounds,
                    touchCoordinates = touchPoint,
                )
            }

            is ProgrammaticGestureTrigger.PinchZoom -> {
                val ok = service.dispatchPinchZoomGesture(
                    centerX = cx,
                    centerY = cy,
                    zoomIn = trigger.zoomIn,
                    spanPx = (minOf(screenWidth, screenHeight) * 0.40f).coerceAtLeast(160f),
                )
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = if (ok) "Pinch zoom (${if (trigger.zoomIn) "in" else "out"}) dispatched at (${cx.toInt()}, ${cy.toInt()})" else "Pinch gesture failed",
                    targetBounds = bounds,
                    touchCoordinates = touchPoint,
                )
            }

            else -> {
                val ok = service.dispatchTapGesture(cx, cy, durationMs = 60L)
                return TriggerResult(
                    success = ok,
                    triggerName = trigger.identifier,
                    executionMode = TriggerExecutionMode.PHYSICAL_GESTURE_DISPATCH,
                    message = "Fallback physical tap dispatched at (${cx.toInt()}, ${cy.toInt()})",
                    targetBounds = bounds,
                    touchCoordinates = touchPoint,
                )
            }
        }
    }

    // =========================================================================
    // 6. GEOMETRIC MOTION VECTOR MATH
    // =========================================================================

    /**
     * Calculates start and end screen coordinates for directional motion vectors,
     * maintaining safe inset margins so gestures do not clip off display edges.
     */
    fun calculateDirectionalVector(
        bounds: Rect,
        direction: GestureDirection,
        factor: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): FloatArray {
        val w = (bounds.right - bounds.left).toFloat()
        val h = (bounds.bottom - bounds.top).toFloat()
        val hasValidBounds = w > 100f && h > 100f
        val cx = if (hasValidBounds) (bounds.left + bounds.right) / 2f else screenWidth / 2f
        val cy = if (hasValidBounds) (bounds.top + bounds.bottom) / 2f else screenHeight / 2f
        val effW = if (hasValidBounds) w else screenWidth.toFloat()
        val effH = if (hasValidBounds) h else screenHeight.toFloat()

        val deltaX = (effW * factor).coerceAtLeast(80f)
        val deltaY = (effH * factor).coerceAtLeast(80f)

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction) {
            GestureDirection.UP -> {
                // Moving finger upward: finger starts lower, moves higher (scrolls content down)
                startX = cx
                startY = (cy + deltaY / 2f).coerceAtMost(screenHeight - 120f)
                endX = cx
                endY = (cy - deltaY / 2f).coerceAtLeast(120f)
            }
            GestureDirection.DOWN -> {
                // Moving finger downward: finger starts higher, moves lower (scrolls content up)
                startX = cx
                startY = (cy - deltaY / 2f).coerceAtLeast(160f)
                endX = cx
                endY = (cy + deltaY / 2f).coerceAtMost(screenHeight - 120f)
            }
            GestureDirection.LEFT -> {
                // Moving finger left: finger starts on the right, moves to the left (next card/tab)
                startX = (cx + deltaX / 2f).coerceAtMost(screenWidth - 60f)
                startY = cy
                endX = (cx - deltaX / 2f).coerceAtLeast(60f)
                endY = cy
            }
            GestureDirection.RIGHT -> {
                // Moving finger right: finger starts on the left, moves to the right (previous card/tab)
                startX = (cx - deltaX / 2f).coerceAtLeast(60f)
                startY = cy
                endX = (cx + deltaX / 2f).coerceAtMost(screenWidth - 60f)
                endY = cy
            }
        }

        return floatArrayOf(startX, startY, endX, endY)
    }

    // =========================================================================
    // 7. NODE INSPECTION & INTROSPECTION HELPERS
    // =========================================================================

    /**
     * Inspects an [AccessibilityNodeInfo] and lists all available programmatic gesture triggers it supports.
     */
    fun getAvailableTriggers(node: AccessibilityNodeInfo): List<ProgrammaticGestureTrigger> {
        val list = mutableListOf<ProgrammaticGestureTrigger>()
        if (node.isClickable) list.add(ProgrammaticGestureTrigger.Tap)
        if (node.isLongClickable) list.add(ProgrammaticGestureTrigger.TouchAndHold())
        if (node.isScrollable) {
            list.add(ProgrammaticGestureTrigger.Scroll(GestureDirection.DOWN))
            list.add(ProgrammaticGestureTrigger.Scroll(GestureDirection.UP))
        }
        if (node.isEditable) {
            list.add(ProgrammaticGestureTrigger.TypeText(""))
            list.add(ProgrammaticGestureTrigger.ClearText)
        }

        for (action in node.actionList) {
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_LONG_CLICK,
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                AccessibilityNodeInfo.ACTION_SET_TEXT, -> Unit // Already added via properties
                AccessibilityNodeInfo.ACTION_DISMISS -> list.add(ProgrammaticGestureTrigger.Dismiss)
                AccessibilityNodeInfo.ACTION_EXPAND -> list.add(ProgrammaticGestureTrigger.Expand)
                AccessibilityNodeInfo.ACTION_COLLAPSE -> list.add(ProgrammaticGestureTrigger.Collapse)
                AccessibilityNodeInfo.ACTION_SELECT -> list.add(ProgrammaticGestureTrigger.Select)
                AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> list.add(ProgrammaticGestureTrigger.ClearSelection)
                else -> {
                    val label = action.label?.toString() ?: getActionLabel(action.id)
                    list.add(ProgrammaticGestureTrigger.CustomAction(action.id, label))
                }
            }
        }

        // Every visible element with bounds supports physical touch gestures
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val hasBounds = (bounds.right > bounds.left) && (bounds.bottom > bounds.top)
        if (hasBounds) {
            if (!list.contains(ProgrammaticGestureTrigger.Tap)) list.add(ProgrammaticGestureTrigger.Tap)
            list.add(ProgrammaticGestureTrigger.DoubleTap)
            list.add(ProgrammaticGestureTrigger.Swipe(GestureDirection.LEFT))
            list.add(ProgrammaticGestureTrigger.Swipe(GestureDirection.RIGHT))
        }
        return list
    }

    /**
     * Human-readable label for standard Android Accessibility action IDs.
     */
    fun getActionLabel(actionId: Int): String = when (actionId) {
        AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
        AccessibilityNodeInfo.ACTION_FOCUS -> "ACTION_FOCUS"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "ACTION_CLEAR_FOCUS"
        AccessibilityNodeInfo.ACTION_SELECT -> "ACTION_SELECT"
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "ACTION_CLEAR_SELECTION"
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "ACTION_ACCESSIBILITY_FOCUS"
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "ACTION_CLEAR_ACCESSIBILITY_FOCUS"
        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> "ACTION_NEXT_AT_MOVEMENT_GRANULARITY"
        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> "ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY"
        AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT -> "ACTION_NEXT_HTML_ELEMENT"
        AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT -> "ACTION_PREVIOUS_HTML_ELEMENT"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
        AccessibilityNodeInfo.ACTION_COPY -> "ACTION_COPY"
        AccessibilityNodeInfo.ACTION_PASTE -> "ACTION_PASTE"
        AccessibilityNodeInfo.ACTION_CUT -> "ACTION_CUT"
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> "ACTION_SET_SELECTION"
        AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
        AccessibilityNodeInfo.ACTION_DISMISS -> "ACTION_DISMISS"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
        else -> "ACTION_0x${Integer.toHexString(actionId)}"
    }
}
