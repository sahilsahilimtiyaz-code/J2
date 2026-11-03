package com.jarvis.assistant.accessibility

import com.jarvis.assistant.security.SecurityShield
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Distinct verification outcome states modeled after the OpenClaw mobile UI
 * automation contract.
 *
 * CRITICAL CONTRACT: A raw gesture or node dispatch callback returning `true`
 * must NEVER automatically become [VERIFIED_SUCCESS]. Without an observed
 * post-settle screen delta or satisfied postcondition, it remains
 * [ACCEPTED_BUT_UNVERIFIED].
 */
enum class UiActionOutcomeState(val isVerified: Boolean) {
    /** Immediate dispatch occurred; awaiting settle and re-observation. */
    DISPATCHED(false),

    /** OS accepted the gesture/action, but re-observation detected no UI or state change. */
    ACCEPTED_BUT_UNVERIFIED(false),

    /** Re-observation and postcondition check confirmed the intended UI transition happened. */
    VERIFIED_SUCCESS(true),

    /** Action dispatch was rejected, target was disabled/occluded, or postcondition failed. */
    FAILED(false),

    /** Observation service disconnected or state could not be determined. */
    UNKNOWN(false),

    /** Referenced snapshotId or elementRef is stale because the screen or package changed. */
    TARGET_STALE(false),

    /** Requested elementRef or semantic target was not found on the current screen. */
    TARGET_NOT_FOUND(false),

    /** Blocked by FLAG_SECURE, password field protection, or protected system package policy. */
    SECURE_CONTENT(false),
}

/**
 * High-consequence categories governed by the fail-closed safety policy.
 */
enum class ConsequentialActionCategory(val label: String) {
    SEND_MESSAGE("Sending message"),
    PUBLISH_OR_SHARE("Publishing or sharing content"),
    PAYMENT_OR_PURCHASE("Making a payment or purchase"),
    DELETE_DATA("Deleting data"),
    UNINSTALL_APP("Uninstalling application"),
    CHANGE_ACCOUNT_SECURITY("Changing account or security settings"),
    GRANT_PERMISSION("Granting system permission"),
}

/**
 * Typed One-Action Execution Contract (Phase 3).
 *
 * Every UI interaction is expressed as a single, self-contained, verifiable
 * action tied to the [snapshotId] it was planned against.
 */
sealed class MobileUiActionRequest {
    abstract val snapshotId: String?
    abstract val expectedPackage: String?
    abstract val postcondition: StepPostcondition

    data class ActivateElement(
        override val snapshotId: String? = null,
        val elementRef: String? = null,
        val semanticQuery: String? = null,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta(),
    ) : MobileUiActionRequest()

    data class EnterText(
        override val snapshotId: String? = null,
        val elementRef: String? = null,
        val semanticQuery: String? = null,
        val text: String,
        val clearFirst: Boolean = false,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectTextEntered(text),
    ) : MobileUiActionRequest()

    data class Scroll(
        override val snapshotId: String? = null,
        val containerRef: String? = null,
        val direction: String = "down",
        val distance: String = "medium",
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("scroll"),
    ) : MobileUiActionRequest()

    data class Tap(
        override val snapshotId: String? = null,
        val elementRef: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta(),
    ) : MobileUiActionRequest()

    data class Swipe(
        override val snapshotId: String? = null,
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 280L,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("swipe"),
    ) : MobileUiActionRequest()

    data class LongPress(
        override val snapshotId: String? = null,
        val elementRef: String? = null,
        val semanticQuery: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val durationMs: Long = 650L,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("long_press"),
    ) : MobileUiActionRequest()

    data class DoubleTap(
        override val snapshotId: String? = null,
        val elementRef: String? = null,
        val semanticQuery: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("double_tap"),
    ) : MobileUiActionRequest()

    data class Drag(
        override val snapshotId: String? = null,
        val fromElementRef: String? = null,
        val toElementRef: String? = null,
        val startX: Int? = null,
        val startY: Int? = null,
        val endX: Int? = null,
        val endY: Int? = null,
        val durationMs: Long = 480L,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("drag"),
    ) : MobileUiActionRequest()

    data class PinchAndZoom(
        override val snapshotId: String? = null,
        val targetRef: String? = null,
        val centerX: Int? = null,
        val centerY: Int? = null,
        val zoomIn: Boolean = true,
        val spanPx: Float = 360f,
        val durationMs: Long = 380L,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("pinch_zoom"),
    ) : MobileUiActionRequest()

    data class Back(
        override val snapshotId: String? = null,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("back"),
    ) : MobileUiActionRequest()

    data class Home(
        override val snapshotId: String? = null,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("home"),
    ) : MobileUiActionRequest()

    data class Recents(
        override val snapshotId: String? = null,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.ExpectUiDelta("recents"),
    ) : MobileUiActionRequest()

    data class Wait(
        val durationMs: Long = 500L,
        override val snapshotId: String? = null,
        override val expectedPackage: String? = null,
        override val postcondition: StepPostcondition = StepPostcondition.None,
    ) : MobileUiActionRequest()
}

/**
 * Structural difference between the pre-action and post-action screen snapshots.
 */
data class ScreenSnapshotDiff(
    val beforeSnapshotId: String,
    val afterSnapshotId: String,
    val packageChanged: Boolean,
    val windowTitleChanged: Boolean,
    val keyboardVisibilityChanged: Boolean,
    val dialogChanged: Boolean,
    val addedElementRefs: List<String>,
    val removedElementRefs: List<String>,
    val textModifiedRefs: List<String>,
    val checkedModifiedRefs: List<String>,
    val boundsShiftedCount: Int,
    val visualPixelDiffRatio: Float = 0f,
) {
    val hasMeaningfulChange: Boolean
        get() = packageChanged ||
            windowTitleChanged ||
            keyboardVisibilityChanged ||
            dialogChanged ||
            addedElementRefs.isNotEmpty() ||
            removedElementRefs.isNotEmpty() ||
            textModifiedRefs.isNotEmpty() ||
            checkedModifiedRefs.isNotEmpty() ||
            boundsShiftedCount > 0 ||
            visualPixelDiffRatio >= 0.03f

    fun summary(): String = buildString {
        val parts = mutableListOf<String>()
        if (packageChanged) parts += "package_changed"
        if (windowTitleChanged) parts += "window_changed"
        if (keyboardVisibilityChanged) parts += "keyboard_changed"
        if (dialogChanged) parts += "dialog_changed"
        if (addedElementRefs.isNotEmpty()) parts += "added=${addedElementRefs.size}"
        if (removedElementRefs.isNotEmpty()) parts += "removed=${removedElementRefs.size}"
        if (textModifiedRefs.isNotEmpty()) parts += "text_changed=${textModifiedRefs.size}"
        if (checkedModifiedRefs.isNotEmpty()) parts += "checked_changed=${checkedModifiedRefs.size}"
        if (boundsShiftedCount > 0) parts += "bounds_shifted=$boundsShiftedCount"
        if (visualPixelDiffRatio > 0f) parts += "visual_diff=${(visualPixelDiffRatio * 100).toInt()}%"
        append(if (parts.isEmpty()) "no_structural_change" else parts.joinToString(", "))
    }
}

object ScreenSnapshotComparator {

    fun compare(
        before: ObservedScreenState,
        after: ObservedScreenState,
        visualDiffRatio: Float = 0f,
    ): ScreenSnapshotDiff {
        val pkgChanged = before.packageName != after.packageName
        val winChanged = before.windowTitle != after.windowTitle
        val kbChanged = before.keyboardVisible != after.keyboardVisible
        val dlgChanged = before.activeDialogTitle != after.activeDialogTitle

        val beforeBySignature = before.elements.associateBy { elementKey(it) }
        val afterBySignature = after.elements.associateBy { elementKey(it) }

        val added = afterBySignature.keys.minus(beforeBySignature.keys)
            .mapNotNull { afterBySignature[it]?.elementRef }
        val removed = beforeBySignature.keys.minus(afterBySignature.keys)
            .mapNotNull { beforeBySignature[it]?.elementRef }

        val textModified = mutableListOf<String>()
        val checkedModified = mutableListOf<String>()
        var boundsShifted = 0

        for ((key, beforeEl) in beforeBySignature) {
            val afterEl = afterBySignature[key] ?: continue
            if (beforeEl.text != afterEl.text || beforeEl.contentDescription != afterEl.contentDescription) {
                textModified += afterEl.elementRef
            }
            if (beforeEl.checked != afterEl.checked || beforeEl.selected != afterEl.selected) {
                checkedModified += afterEl.elementRef
            }
            if (kotlin.math.abs(beforeEl.centerX - afterEl.centerX) > 16 ||
                kotlin.math.abs(beforeEl.centerY - afterEl.centerY) > 16
            ) {
                boundsShifted++
            }
        }

        // Also detect pure text changes on focused/editable fields by index/role
        val beforeEditables = before.elements.filter { it.editable }
        val afterEditables = after.elements.filter { it.editable }
        if (beforeEditables.map { it.text } != afterEditables.map { it.text }) {
            afterEditables.firstOrNull()?.elementRef?.let { ref ->
                if (ref !in textModified) textModified += ref
            }
        }

        return ScreenSnapshotDiff(
            beforeSnapshotId = before.snapshotId,
            afterSnapshotId = after.snapshotId,
            packageChanged = pkgChanged,
            windowTitleChanged = winChanged,
            keyboardVisibilityChanged = kbChanged,
            dialogChanged = dlgChanged,
            addedElementRefs = added,
            removedElementRefs = removed,
            textModifiedRefs = textModified,
            checkedModifiedRefs = checkedModified,
            boundsShiftedCount = boundsShifted,
            visualPixelDiffRatio = visualDiffRatio,
        )
    }

    private fun elementKey(el: ObservedUiElement): String =
        if (el.resourceId.isNotBlank()) {
            "${el.packageName}:${el.resourceId}:${el.index}"
        } else {
            "${el.packageName}:${el.className}:${el.primaryLabel.take(40)}:${el.index}"
        }
}

/**
 * Complete result of executing one [MobileUiActionRequest] with automatic
 * re-observation and postcondition verification.
 */
data class MobileUiActionResult(
    val outcome: UiActionOutcomeState,
    val message: String,
    val beforeSnapshotId: String,
    val afterSnapshotId: String,
    val targetElementRef: String? = null,
    val targetLabel: String? = null,
    val diff: ScreenSnapshotDiff? = null,
    val afterSnapshot: ObservedScreenState? = null,
    val consequentialCategory: ConsequentialActionCategory? = null,
) {
    val isVerifiedSuccess: Boolean
        get() = outcome == UiActionOutcomeState.VERIFIED_SUCCESS
}

/**
 * Fail-Closed Safety Policy (Phase 6).
 *
 * Classifies consequential UI actions, enforces explicit human confirmation,
 * blocks protected system packages (permission controllers, package installers),
 * protects password fields, and prevents duplicate consequential actions.
 */
object FailClosedActionPolicy {

    private val MESSAGE_KEYWORDS = listOf("send", "send message", "send voice note", "reply")
    private val PUBLISH_KEYWORDS = listOf("post", "publish", "share", "tweet", "upload", "broadcast")
    private val PAYMENT_KEYWORDS = listOf("pay", "purchase", "buy now", "checkout", "place order", "transfer", "send money", "confirm payment")
    private val DELETE_KEYWORDS = listOf("delete", "remove", "erase", "trash", "clear all", "wipe", "forget")
    private val UNINSTALL_KEYWORDS = listOf("uninstall", "remove app")
    private val SECURITY_KEYWORDS = listOf("password", "passkey", "2fa", "two-factor", "security code", "deactivate", "delete account", "factory reset")
    private val PERMISSION_KEYWORDS = listOf("allow", "while using the app", "always allow", "grant permission", "allow restricted settings")

    /**
     * Classifies whether a target element or action falls into a high-consequence category
     * requiring explicit owner confirmation before touch dispatch.
     */
    fun classifyConsequentialAction(
        targetElement: ObservedUiElement?,
        rawTargetQuery: String = "",
        packageName: String = "",
    ): ConsequentialActionCategory? {
        val combined = buildString {
            append(rawTargetQuery).append(' ')
            if (targetElement != null) {
                append(targetElement.text).append(' ')
                append(targetElement.contentDescription).append(' ')
                append(targetElement.shortResourceId)
            }
        }.lowercase(Locale.ROOT).trim()

        if (combined.isBlank()) return null

        val pkgLower = packageName.lowercase(Locale.ROOT)
        if (pkgLower.contains("permissioncontroller") || matchesWord(combined, PERMISSION_KEYWORDS)) {
            return ConsequentialActionCategory.GRANT_PERMISSION
        }
        if (pkgLower.contains("packageinstaller") || matchesWord(combined, UNINSTALL_KEYWORDS)) {
            return ConsequentialActionCategory.UNINSTALL_APP
        }
        if (matchesWord(combined, PAYMENT_KEYWORDS)) {
            return ConsequentialActionCategory.PAYMENT_OR_PURCHASE
        }
        if (matchesWord(combined, DELETE_KEYWORDS)) {
            return ConsequentialActionCategory.DELETE_DATA
        }
        if (matchesWord(combined, SECURITY_KEYWORDS)) {
            return ConsequentialActionCategory.CHANGE_ACCOUNT_SECURITY
        }
        if (matchesWord(combined, PUBLISH_KEYWORDS)) {
            return ConsequentialActionCategory.PUBLISH_OR_SHARE
        }
        if (matchesWord(combined, MESSAGE_KEYWORDS)) {
            return ConsequentialActionCategory.SEND_MESSAGE
        }
        return null
    }

    private fun matchesWord(haystack: String, phrases: List<String>): Boolean =
        phrases.any { phrase ->
            Regex("""\b${Regex.escape(phrase)}\b""").containsMatchIn(haystack)
        }
}

/**
 * Pluggable dispatch backend so [MobileUiActionExecutor] can drive the real
 * [JarvisAccessibilityService] on the Galaxy A54 as well as deterministic
 * test harnesses in JVM unit tests.
 */
interface UiDispatchBackend {
    suspend fun observeScreen(forceRefresh: Boolean = true): ObservedScreenState?
    suspend fun performClick(element: ObservedUiElement): Boolean
    suspend fun performSetText(element: ObservedUiElement, text: String, clearFirst: Boolean): Boolean
    suspend fun performScroll(container: ObservedUiElement?, direction: String, distance: String): Boolean
    suspend fun dispatchTap(x: Float, y: Float, durationMs: Long = 65L): Boolean
    suspend fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 650L): Boolean
    suspend fun dispatchDoubleTap(x: Float, y: Float): Boolean
    suspend fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 280L): Boolean
    suspend fun dispatchDrag(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 480L): Boolean
    suspend fun dispatchPinchZoom(centerX: Float, centerY: Float, zoomIn: Boolean, spanPx: Float, durationMs: Long = 380L): Boolean
    suspend fun performGlobalNavigation(action: String): Boolean
    fun isVisualSecureBlackout(): Boolean = false
}

/**
 * Live Android implementation of [UiDispatchBackend] backed by [JarvisAccessibilityService]
 * and [ScreenController].
 */
class LiveAndroidUiDispatchBackend(
    private val serviceProvider: () -> JarvisAccessibilityService? = { JarvisAccessibilityService.serviceInstance() },
) : UiDispatchBackend {

    override suspend fun observeScreen(forceRefresh: Boolean): ObservedScreenState? {
        val service = serviceProvider() ?: return null
        val controller = JarvisAccessibilityService.controller()
        return JarvisScreenObserver.observe(
            service = service,
            appLabelResolver = { pkg -> controller?.appLabel(pkg) ?: pkg },
            forceRefresh = forceRefresh,
        )
    }

    override suspend fun performClick(element: ObservedUiElement): Boolean {
        val service = serviceProvider() ?: return false
        val controller = ScreenController(service)
        val label = element.primaryLabel.takeIf { it.isNotBlank() && !element.password }
            ?: "#${element.index}"
        val res = controller.tap(label)
        if (res.success) return true
        return service.dispatchTapGesture(element.centerX.toFloat(), element.centerY.toFloat())
    }

    override suspend fun performSetText(element: ObservedUiElement, text: String, clearFirst: Boolean): Boolean {
        val service = serviceProvider() ?: return false
        val controller = ScreenController(service)
        if (!element.focused) {
            service.dispatchTapGesture(element.centerX.toFloat(), element.centerY.toFloat())
            delay(120L)
        }
        return controller.type(text).success
    }

    override suspend fun performScroll(container: ObservedUiElement?, direction: String, distance: String): Boolean {
        val service = serviceProvider() ?: return false
        val controller = ScreenController(service)
        return controller.scrollDirectionVerified(direction, distance).success
    }

    override suspend fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean =
        serviceProvider()?.dispatchTapGesture(x, y, durationMs) ?: false

    override suspend fun dispatchLongPress(x: Float, y: Float, durationMs: Long): Boolean =
        serviceProvider()?.dispatchLongPressGesture(x, y, durationMs) ?: false

    override suspend fun dispatchDoubleTap(x: Float, y: Float): Boolean =
        serviceProvider()?.dispatchDoubleTapGesture(x, y) ?: false

    override suspend fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean = serviceProvider()?.dispatchSwipeGesture(startX, startY, endX, endY, durationMs) ?: false

    override suspend fun dispatchDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean = serviceProvider()?.dispatchDragGesture(startX, startY, endX, endY, durationMs) ?: false

    override suspend fun dispatchPinchZoom(
        centerX: Float,
        centerY: Float,
        zoomIn: Boolean,
        spanPx: Float,
        durationMs: Long,
    ): Boolean = serviceProvider()?.dispatchPinchZoomGesture(centerX, centerY, zoomIn, spanPx, durationMs) ?: false

    override suspend fun performGlobalNavigation(action: String): Boolean {
        val service = serviceProvider() ?: return false
        return ScreenController(service).press(action).success
    }
}

/**
 * OpenClaw-inspired One-Action Mobile UI Executor with Automatic Re-Observation
 * and Honest 8-State Verification (Phases 3, 4, 6).
 */
class MobileUiActionExecutor(
    private val backend: UiDispatchBackend = LiveAndroidUiDispatchBackend(),
    private val settleDelayMs: Long = 220L,
    private val approvalGate: (suspend (category: ConsequentialActionCategory, targetSummary: String) -> Boolean)? = null,
) {

    /** Tracks executed consequential keys (`snapshotId:elementRef:category`) to prevent duplicate dispatch. */
    private val executedConsequentialActions = mutableSetOf<String>()

    fun resetConsequentialHistory() {
        executedConsequentialActions.clear()
    }

    suspend fun execute(request: MobileUiActionRequest): MobileUiActionResult {
        // 1. Check for immediate user takeover or cancellation
        if (HumanTakeoverManager.shouldHalt) {
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.FAILED,
                message = "Action halted immediately because the owner touched the screen or cancelled automation.",
                beforeSnapshotId = request.snapshotId.orEmpty(),
                afterSnapshotId = request.snapshotId.orEmpty(),
            )
        }

        // 2. Observe current pre-action screen state
        val beforeSnapshot = backend.observeScreen(forceRefresh = true)
            ?: return MobileUiActionResult(
                outcome = UiActionOutcomeState.UNKNOWN,
                message = "Accessibility service could not observe the current screen.",
                beforeSnapshotId = request.snapshotId.orEmpty(),
                afterSnapshotId = "",
            )

        // 3. Enforce secure screen / protected package policy (fail-closed)
        if (beforeSnapshot.isSecureOrProtected || backend.isVisualSecureBlackout()) {
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.SECURE_CONTENT,
                message = "Blocked interaction: screen '${beforeSnapshot.packageName}' is protected by FLAG_SECURE or SecurityShield.",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshot = beforeSnapshot,
            )
        }

        // 4. Validate expectedPackage if specified
        if (!request.expectedPackage.isNullOrBlank() &&
            !beforeSnapshot.packageName.equals(request.expectedPackage, ignoreCase = true)
        ) {
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.TARGET_STALE,
                message = "Package mismatch: expected '${request.expectedPackage}', but active foreground package is '${beforeSnapshot.packageName}'. Re-observe before acting.",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshot = beforeSnapshot,
            )
        }

        // 5. Validate snapshotId freshness when caller specifies a snapshotId
        if (!request.snapshotId.isNullOrBlank() &&
            request.snapshotId != beforeSnapshot.snapshotId
        ) {
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.TARGET_STALE,
                message = "Stale snapshot reference '${request.snapshotId}' (current live snapshot is '${beforeSnapshot.snapshotId}'). Re-observe the screen before acting.",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshot = beforeSnapshot,
            )
        }

        // Handle pure Wait request
        if (request is MobileUiActionRequest.Wait) {
            val clampedWait = request.durationMs.coerceIn(50L, 5_000L)
            delay(clampedWait)
            val afterWait = backend.observeScreen(forceRefresh = true) ?: beforeSnapshot
            val diff = ScreenSnapshotComparator.compare(beforeSnapshot, afterWait)
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.VERIFIED_SUCCESS,
                message = "Waited ${clampedWait}ms and re-observed screen (${diff.summary()}).",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = afterWait.snapshotId,
                diff = diff,
                afterSnapshot = afterWait,
            )
        }

        // 6. Resolve target element (if the action targets an element)
        val resolvedTarget: ObservedUiElement? = when (request) {
            is MobileUiActionRequest.ActivateElement -> resolveTarget(beforeSnapshot, request.elementRef, request.semanticQuery)
            is MobileUiActionRequest.EnterText -> resolveTarget(
                beforeSnapshot,
                request.elementRef,
                request.semanticQuery,
                preferEditable = true,
            )
            is MobileUiActionRequest.Scroll -> request.containerRef?.let { beforeSnapshot.findByRef(it) }
            is MobileUiActionRequest.Tap -> request.elementRef?.let { beforeSnapshot.findByRef(it) }
            is MobileUiActionRequest.LongPress -> resolveTarget(beforeSnapshot, request.elementRef, request.semanticQuery)
            is MobileUiActionRequest.DoubleTap -> resolveTarget(beforeSnapshot, request.elementRef, request.semanticQuery)
            is MobileUiActionRequest.Drag -> request.fromElementRef?.let { beforeSnapshot.findByRef(it) }
            is MobileUiActionRequest.PinchAndZoom -> request.targetRef?.let { beforeSnapshot.findByRef(it) }
            else -> null
        }

        // 7. Never silently replace a missing semantic or elementRef target with an arbitrary coordinate!
        val requiresElement = when (request) {
            is MobileUiActionRequest.ActivateElement -> true
            is MobileUiActionRequest.EnterText -> true
            is MobileUiActionRequest.Tap -> request.elementRef != null || (request.x == null || request.y == null)
            is MobileUiActionRequest.LongPress -> request.elementRef != null || !request.semanticQuery.isNullOrBlank()
            is MobileUiActionRequest.DoubleTap -> request.elementRef != null || !request.semanticQuery.isNullOrBlank()
            is MobileUiActionRequest.Drag -> request.fromElementRef != null || request.toElementRef != null
            else -> false
        }

        if (requiresElement && resolvedTarget == null) {
            val missingDesc = when (request) {
                is MobileUiActionRequest.ActivateElement -> request.elementRef ?: request.semanticQuery.orEmpty()
                is MobileUiActionRequest.EnterText -> request.elementRef ?: request.semanticQuery.orEmpty()
                is MobileUiActionRequest.Tap -> request.elementRef.orEmpty()
                is MobileUiActionRequest.LongPress -> request.elementRef ?: request.semanticQuery.orEmpty()
                is MobileUiActionRequest.DoubleTap -> request.elementRef ?: request.semanticQuery.orEmpty()
                is MobileUiActionRequest.Drag -> "${request.fromElementRef} -> ${request.toElementRef}"
                else -> "target"
            }
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.TARGET_NOT_FOUND,
                message = "Target '$missingDesc' was not found in snapshot '${beforeSnapshot.snapshotId}'. Refusing to guess coordinates.",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshot = beforeSnapshot,
            )
        }

        // Validate Drag destination element if specified by ref
        val dragDestination: ObservedUiElement? = if (request is MobileUiActionRequest.Drag && request.toElementRef != null) {
            val dest = beforeSnapshot.findByRef(request.toElementRef)
            if (dest == null) {
                return MobileUiActionResult(
                    outcome = UiActionOutcomeState.TARGET_NOT_FOUND,
                    message = "Drag destination '${request.toElementRef}' was not found in snapshot '${beforeSnapshot.snapshotId}'.",
                    beforeSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshot = beforeSnapshot,
                )
            }
            dest
        } else {
            null
        }

        // 8. Validate target element state: password protection, enabled status, bounds, and occlusion
        if (resolvedTarget != null) {
            if (resolvedTarget.password) {
                return MobileUiActionResult(
                    outcome = UiActionOutcomeState.SECURE_CONTENT,
                    message = "Target '${resolvedTarget.elementRef}' is a password or PIN field. Jarvis will never read, type, or automate password fields.",
                    beforeSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshotId = beforeSnapshot.snapshotId,
                    targetElementRef = resolvedTarget.elementRef,
                    targetLabel = resolvedTarget.primaryLabel,
                    afterSnapshot = beforeSnapshot,
                )
            }

            if (!resolvedTarget.enabled) {
                return MobileUiActionResult(
                    outcome = UiActionOutcomeState.FAILED,
                    message = "Target '${resolvedTarget.primaryLabel}' (${resolvedTarget.elementRef}) is currently disabled.",
                    beforeSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshotId = beforeSnapshot.snapshotId,
                    targetElementRef = resolvedTarget.elementRef,
                    targetLabel = resolvedTarget.primaryLabel,
                    afterSnapshot = beforeSnapshot,
                )
            }

            if (!resolvedTarget.hasValidTouchBounds(beforeSnapshot.screenWidth, beforeSnapshot.screenHeight)) {
                return MobileUiActionResult(
                    outcome = UiActionOutcomeState.FAILED,
                    message = "Target '${resolvedTarget.primaryLabel}' (${resolvedTarget.elementRef}) has invalid or off-screen bounds.",
                    beforeSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshotId = beforeSnapshot.snapshotId,
                    targetElementRef = resolvedTarget.elementRef,
                    targetLabel = resolvedTarget.primaryLabel,
                    afterSnapshot = beforeSnapshot,
                )
            }

            // Check dialog or higher-window/keyboard occlusion
            val occlusion = HierarchyOcclusionDetector.detectOcclusion(resolvedTarget, beforeSnapshot)
            if (occlusion.isOccluded) {
                return MobileUiActionResult(
                    outcome = UiActionOutcomeState.FAILED,
                    message = occlusion.reason,
                    beforeSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshotId = beforeSnapshot.snapshotId,
                    targetElementRef = resolvedTarget.elementRef,
                    targetLabel = resolvedTarget.primaryLabel,
                    afterSnapshot = beforeSnapshot,
                )
            }

            if (beforeSnapshot.activeDialogTitle != null && beforeSnapshot.dismissCandidate != null) {
                val insideDialog = resolvedTarget.parentLabel.contains(beforeSnapshot.activeDialogTitle, ignoreCase = true) ||
                    resolvedTarget.elementRef == beforeSnapshot.dismissCandidate.elementRef
                if (!insideDialog) {
                    return MobileUiActionResult(
                        outcome = UiActionOutcomeState.FAILED,
                        message = "Target '${resolvedTarget.primaryLabel}' is blocked by active dialog '${beforeSnapshot.activeDialogTitle}'. Dismiss or handle the dialog first.",
                        beforeSnapshotId = beforeSnapshot.snapshotId,
                        afterSnapshotId = beforeSnapshot.snapshotId,
                        targetElementRef = resolvedTarget.elementRef,
                        targetLabel = resolvedTarget.primaryLabel,
                        afterSnapshot = beforeSnapshot,
                    )
                }
            }
        }

        // 9. Fail-Closed Consequential Action Check & Anti-Duplication Guard
        val rawQueryHint = when (request) {
            is MobileUiActionRequest.ActivateElement -> request.semanticQuery.orEmpty()
            is MobileUiActionRequest.LongPress -> request.semanticQuery.orEmpty()
            is MobileUiActionRequest.DoubleTap -> request.semanticQuery.orEmpty()
            else -> ""
        }
        val consequentialCategory = FailClosedActionPolicy.classifyConsequentialAction(
            targetElement = resolvedTarget,
            rawTargetQuery = rawQueryHint,
            packageName = beforeSnapshot.packageName,
        )

        if (consequentialCategory != null) {
            val dedupKey = "${beforeSnapshot.packageName}:${resolvedTarget?.elementRef ?: rawQueryHint}:${consequentialCategory.name}"
            if (dedupKey in executedConsequentialActions) {
                return MobileUiActionResult(
                    outcome = UiActionOutcomeState.FAILED,
                    message = "Blocked duplicate consequential action (${consequentialCategory.label}) on '${resolvedTarget?.primaryLabel ?: rawQueryHint}'.",
                    beforeSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshotId = beforeSnapshot.snapshotId,
                    targetElementRef = resolvedTarget?.elementRef,
                    targetLabel = resolvedTarget?.primaryLabel,
                    afterSnapshot = beforeSnapshot,
                    consequentialCategory = consequentialCategory,
                )
            }

            val gate = approvalGate
            val approved = gate?.invoke(
                consequentialCategory,
                resolvedTarget?.primaryLabel?.ifBlank { rawQueryHint } ?: rawQueryHint,
            ) ?: false

            if (!approved) {
                return MobileUiActionResult(
                    outcome = UiActionOutcomeState.FAILED,
                    message = "Fail-closed policy blocked '${resolvedTarget?.primaryLabel ?: rawQueryHint}': explicit owner approval is required for ${consequentialCategory.label}.",
                    beforeSnapshotId = beforeSnapshot.snapshotId,
                    afterSnapshotId = beforeSnapshot.snapshotId,
                    targetElementRef = resolvedTarget?.elementRef,
                    targetLabel = resolvedTarget?.primaryLabel,
                    afterSnapshot = beforeSnapshot,
                    consequentialCategory = consequentialCategory,
                )
            }
        }

        // 10. Execute the single action
        val dispatched: Boolean = when (request) {
            is MobileUiActionRequest.ActivateElement -> {
                val target = resolvedTarget!!
                if (target.supportsAction("ACTION_CLICK") || target.clickable) {
                    backend.performClick(target)
                } else {
                    backend.dispatchTap(target.centerX.toFloat(), target.centerY.toFloat())
                }
            }

            is MobileUiActionRequest.EnterText -> {
                val target = resolvedTarget!!
                if (SecurityShield.inspectToolCall("type_on_screen", request.text) != null) {
                    return MobileUiActionResult(
                        outcome = UiActionOutcomeState.SECURE_CONTENT,
                        message = "SecurityShield blocked untrusted or injected text payload.",
                        beforeSnapshotId = beforeSnapshot.snapshotId,
                        afterSnapshotId = beforeSnapshot.snapshotId,
                        targetElementRef = target.elementRef,
                        targetLabel = target.primaryLabel,
                        afterSnapshot = beforeSnapshot,
                    )
                }
                backend.performSetText(target, request.text, request.clearFirst)
            }

            is MobileUiActionRequest.Scroll -> {
                backend.performScroll(resolvedTarget, request.direction, request.distance)
            }

            is MobileUiActionRequest.Tap -> {
                if (resolvedTarget != null) {
                    backend.performClick(resolvedTarget)
                } else {
                    val tx = request.x ?: return MobileUiActionResult(
                        outcome = UiActionOutcomeState.TARGET_NOT_FOUND,
                        message = "Tap requires either an elementRef or valid (x, y) coordinates.",
                        beforeSnapshotId = beforeSnapshot.snapshotId,
                        afterSnapshotId = beforeSnapshot.snapshotId,
                    )
                    val ty = request.y ?: return MobileUiActionResult(
                        outcome = UiActionOutcomeState.TARGET_NOT_FOUND,
                        message = "Tap requires either an elementRef or valid (x, y) coordinates.",
                        beforeSnapshotId = beforeSnapshot.snapshotId,
                        afterSnapshotId = beforeSnapshot.snapshotId,
                    )
                    if (tx !in 1 until beforeSnapshot.screenWidth || ty !in 1 until beforeSnapshot.screenHeight) {
                        return MobileUiActionResult(
                            outcome = UiActionOutcomeState.FAILED,
                            message = "Coordinates ($tx, $ty) are outside screen bounds (${beforeSnapshot.screenWidth}x${beforeSnapshot.screenHeight}).",
                            beforeSnapshotId = beforeSnapshot.snapshotId,
                            afterSnapshotId = beforeSnapshot.snapshotId,
                        )
                    }
                    backend.dispatchTap(tx.toFloat(), ty.toFloat())
                }
            }

            is MobileUiActionRequest.Swipe -> {
                backend.dispatchSwipe(
                    startX = request.startX.toFloat(),
                    startY = request.startY.toFloat(),
                    endX = request.endX.toFloat(),
                    endY = request.endY.toFloat(),
                    durationMs = request.durationMs,
                )
            }

            is MobileUiActionRequest.LongPress -> {
                val px = resolvedTarget?.centerX ?: request.x ?: -1
                val py = resolvedTarget?.centerY ?: request.y ?: -1
                if (px <= 0 || py <= 0) {
                    false
                } else {
                    backend.dispatchLongPress(px.toFloat(), py.toFloat(), request.durationMs)
                }
            }

            is MobileUiActionRequest.DoubleTap -> {
                val px = resolvedTarget?.centerX ?: request.x ?: -1
                val py = resolvedTarget?.centerY ?: request.y ?: -1
                if (px <= 0 || py <= 0) {
                    false
                } else {
                    backend.dispatchDoubleTap(px.toFloat(), py.toFloat())
                }
            }

            is MobileUiActionRequest.Drag -> {
                val sx = resolvedTarget?.centerX ?: request.startX ?: -1
                val sy = resolvedTarget?.centerY ?: request.startY ?: -1
                val ex = dragDestination?.centerX ?: request.endX ?: -1
                val ey = dragDestination?.centerY ?: request.endY ?: -1
                if (sx <= 0 || sy <= 0 || ex <= 0 || ey <= 0) {
                    false
                } else {
                    backend.dispatchDrag(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), request.durationMs)
                }
            }

            is MobileUiActionRequest.PinchAndZoom -> {
                val cx = resolvedTarget?.centerX ?: request.centerX ?: (beforeSnapshot.screenWidth / 2)
                val cy = resolvedTarget?.centerY ?: request.centerY ?: (beforeSnapshot.screenHeight / 2)
                backend.dispatchPinchZoom(cx.toFloat(), cy.toFloat(), request.zoomIn, request.spanPx, request.durationMs)
            }

            is MobileUiActionRequest.Back -> backend.performGlobalNavigation("back")
            is MobileUiActionRequest.Home -> backend.performGlobalNavigation("home")
            is MobileUiActionRequest.Recents -> backend.performGlobalNavigation("recents")
            is MobileUiActionRequest.Wait -> true
        }

        if (!dispatched) {
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.FAILED,
                message = "Android rejected or cancelled the UI action dispatch.",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = beforeSnapshot.snapshotId,
                targetElementRef = resolvedTarget?.elementRef,
                targetLabel = resolvedTarget?.primaryLabel,
                afterSnapshot = beforeSnapshot,
                consequentialCategory = consequentialCategory,
            )
        }

        // Check if user interrupted during the gesture itself
        if (HumanTakeoverManager.shouldHalt) {
            return MobileUiActionResult(
                outcome = UiActionOutcomeState.FAILED,
                message = "Action interrupted by owner touch during gesture execution.",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = beforeSnapshot.snapshotId,
                targetElementRef = resolvedTarget?.elementRef,
                targetLabel = resolvedTarget?.primaryLabel,
            )
        }

        // 11. Wait for bounded settling period and re-observe
        if (settleDelayMs > 0L) {
            delay(settleDelayMs)
        }

        val afterSnapshot = backend.observeScreen(forceRefresh = true)
            ?: return MobileUiActionResult(
                outcome = UiActionOutcomeState.UNKNOWN,
                message = "Action was dispatched, but post-action screen observation failed.",
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = "",
                targetElementRef = resolvedTarget?.elementRef,
                targetLabel = resolvedTarget?.primaryLabel,
            )

        val diff = ScreenSnapshotComparator.compare(beforeSnapshot, afterSnapshot)
        val postconditionSatisfied = evaluatePostcondition(
            postcondition = request.postcondition,
            before = beforeSnapshot,
            after = afterSnapshot,
            diff = diff,
        )

        // Record consequential action if it dispatched and produced a change
        if (consequentialCategory != null && (diff.hasMeaningfulChange || postconditionSatisfied)) {
            val dedupKey = "${beforeSnapshot.packageName}:${resolvedTarget?.elementRef ?: rawQueryHint}:${consequentialCategory.name}"
            executedConsequentialActions += dedupKey
        }

        // 12. Determine honest outcome state (NEVER promote an unverified gesture callback to VERIFIED_SUCCESS)
        val outcomeState = when {
            afterSnapshot.isSecureOrProtected || backend.isVisualSecureBlackout() ->
                UiActionOutcomeState.SECURE_CONTENT
            postconditionSatisfied ->
                UiActionOutcomeState.VERIFIED_SUCCESS
            request.postcondition is StepPostcondition.ExpectForegroundPackage && !postconditionSatisfied ->
                UiActionOutcomeState.FAILED
            request.postcondition is StepPostcondition.ExpectTextEntered && !postconditionSatisfied ->
                UiActionOutcomeState.ACCEPTED_BUT_UNVERIFIED
            diff.hasMeaningfulChange ->
                UiActionOutcomeState.VERIFIED_SUCCESS
            else ->
                UiActionOutcomeState.ACCEPTED_BUT_UNVERIFIED
        }

        val targetDesc = resolvedTarget?.let { "'${it.primaryLabel}' (${it.elementRef})" } ?: request::class.simpleName.orEmpty()
        val message = when (outcomeState) {
            UiActionOutcomeState.VERIFIED_SUCCESS ->
                "Verified action on $targetDesc -> ${diff.summary()} (snapshot ${afterSnapshot.snapshotId})."
            UiActionOutcomeState.ACCEPTED_BUT_UNVERIFIED ->
                "Action on $targetDesc was accepted by Android, but the screen did not visibly change (${diff.summary()})."
            UiActionOutcomeState.SECURE_CONTENT ->
                "Post-action screen entered a protected or FLAG_SECURE surface."
            else ->
                "Action on $targetDesc did not satisfy expected postcondition (${diff.summary()})."
        }

        return MobileUiActionResult(
            outcome = outcomeState,
            message = message,
            beforeSnapshotId = beforeSnapshot.snapshotId,
            afterSnapshotId = afterSnapshot.snapshotId,
            targetElementRef = resolvedTarget?.elementRef,
            targetLabel = resolvedTarget?.primaryLabel,
            diff = diff,
            afterSnapshot = afterSnapshot,
            consequentialCategory = consequentialCategory,
        )
    }

    private fun resolveTarget(
        screen: ObservedScreenState,
        elementRef: String?,
        semanticQuery: String?,
        preferEditable: Boolean = false,
    ): ObservedUiElement? {
        if (!elementRef.isNullOrBlank()) {
            return screen.findByRef(elementRef)
        }
        if (!semanticQuery.isNullOrBlank()) {
            val resolution = JarvisUiElementMatcher.resolve(
                screen = screen,
                query = ElementMatchQuery(
                    rawTarget = semanticQuery,
                    preferEditable = preferEditable,
                    packageContext = screen.packageName,
                ),
            )
            if (resolution.ambiguityDetected) return null
            return resolution.best?.element
        }
        if (preferEditable) {
            return screen.elements.firstOrNull { it.editable && it.focused }
                ?: screen.elements.firstOrNull { it.editable }
        }
        return null
    }

    private fun evaluatePostcondition(
        postcondition: StepPostcondition,
        before: ObservedScreenState,
        after: ObservedScreenState,
        diff: ScreenSnapshotDiff,
    ): Boolean = when (postcondition) {
        is StepPostcondition.ExpectForegroundPackage ->
            after.packageName.equals(postcondition.packageName, ignoreCase = true)

        is StepPostcondition.ExpectTextEntered -> {
            val wanted = postcondition.expectedText.trim().lowercase(Locale.ROOT)
            wanted.isNotEmpty() && after.elements.any { el ->
                (el.editable || el.text.isNotBlank()) &&
                    (el.text.lowercase(Locale.ROOT).contains(wanted) ||
                        el.contentDescription.lowercase(Locale.ROOT).contains(wanted))
            }
        }

        is StepPostcondition.ExpectDialogDismissed ->
            before.activeDialogTitle != null && after.activeDialogTitle == null

        is StepPostcondition.ExpectUiDelta ->
            diff.hasMeaningfulChange

        is StepPostcondition.ExpectMediaPlaying ->
            diff.hasMeaningfulChange

        StepPostcondition.None ->
            true
    }
}
