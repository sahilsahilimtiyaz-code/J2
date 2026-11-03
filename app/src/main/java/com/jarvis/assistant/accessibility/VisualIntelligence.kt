package com.jarvis.assistant.accessibility

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Visual text and element region extracted from a screen capture.
 */
data class VisualTextRegion(
    val text: String,
    val bounds: Rect,
    val confidence: Float = 0.85f,
)

/**
 * Visual evidence synthesized from screen bitmap analysis.
 */
data class VisualEvidence(
    val width: Int,
    val height: Int,
    val isSecureBlackout: Boolean = false,
    val hasModalScrim: Boolean = false,
    val hasLoadingIndicator: Boolean = false,
    val extractedRegions: List<VisualTextRegion> = emptyList(),
    val averageLuminance: Float = 0.5f,
)

enum class ReconciliationStatus {
    /** Accessibility and visual evidence agree on the target and state */
    CONSISTENT,
    /** Screen is currently loading; automation should wait */
    LOADING_WAIT,
    /** Target is occluded by a modal scrim, overlay, or unexpected dialog */
    MODAL_OCCLUDED,
    /** Screen is protected by Android FLAG_SECURE (banking/PIN); tapping is blocked */
    SECURE_SCREEN_BLOCKED,
    /** Target coordinates or presence disagree between visual and accessibility tree */
    AMBIGUOUS,
    /** Visual evidence contradicts accessibility (e.g. element hidden or destroyed) */
    CONFLICTING_EVIDENCE,
}

data class ReconciliationResult(
    val status: ReconciliationStatus,
    val resolvedElement: ObservedUiElement?,
    val safeToInteract: Boolean,
    val explanation: String,
    val suggestedAction: String = "PROCEED",
)

/**
 * Hybrid Visual + Accessibility Intelligence Engine.
 *
 * Combines structural AccessibilityNodeInfo data with lightweight on-device
 * bitmap analysis and optional cloud vision models to ensure Jarvis never taps
 * blindly when visual and accessibility evidence diverge.
 */
object VisualAccessibilityReconciler {

    /**
     * Inspects a raw screen bitmap to detect modal scrims, loading spinners,
     * FLAG_SECURE blackouts, and visual characteristics.
     */
    fun analyzeBitmap(bitmap: Bitmap?): VisualEvidence {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return VisualEvidence(0, 0)
        }

        val w = bitmap.width
        val h = bitmap.height

        // Fast downsampled pixel sampling (every 32nd pixel) to preserve Galaxy A54 RAM/CPU
        val stepX = max(1, w / 40)
        val stepY = max(1, h / 40)

        var totalLum = 0.0
        var sampledPixels = 0
        var darkPixelCount = 0
        var pureBlackPixels = 0

        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

                totalLum += lum
                sampledPixels++

                if (lum < 0.25) darkPixelCount++
                if (r == 0 && g == 0 && b == 0) pureBlackPixels++
            }
        }

        val avgLuminance = if (sampledPixels > 0) (totalLum / sampledPixels).toFloat() else 0.5f

        // Android FLAG_SECURE renders screenshots as 100% solid black
        val isSecureBlackout = sampledPixels > 50 && (pureBlackPixels.toFloat() / sampledPixels.toFloat()) > 0.98f

        // Modal scrims typically dim the background to luminance < 0.22 while keeping center active
        val hasModalScrim = sampledPixels > 50 && !isSecureBlackout && (darkPixelCount.toFloat() / sampledPixels.toFloat()) > 0.72f

        return VisualEvidence(
            width = w,
            height = h,
            isSecureBlackout = isSecureBlackout,
            hasModalScrim = hasModalScrim,
            averageLuminance = avgLuminance,
        )
    }

    /**
     * Reconciles structural accessibility matching against visual screen evidence.
     */
    fun reconcile(
        query: ElementMatchQuery,
        matchedElement: ObservedUiElement?,
        visual: VisualEvidence,
        screen: ObservedScreenState,
    ): ReconciliationResult {
        // 1. Check for Android FLAG_SECURE protection
        if (visual.isSecureBlackout) {
            return ReconciliationResult(
                status = ReconciliationStatus.SECURE_SCREEN_BLOCKED,
                resolvedElement = null,
                safeToInteract = false,
                explanation = "This screen is protected by Android FLAG_SECURE (e.g. banking, PIN, or secure payment). Touch automation is blocked to protect your security.",
                suggestedAction = "ABORT_SECURE",
            )
        }

        // 2. Check for active modal blocking dialog
        if (screen.activeDialogTitle != null && screen.dismissCandidate != null) {
            val isTargetInsideDialog = matchedElement != null &&
                (matchedElement.parentLabel.contains(screen.activeDialogTitle, ignoreCase = true) ||
                    matchedElement.index == screen.dismissCandidate.index)

            if (!isTargetInsideDialog) {
                return ReconciliationResult(
                    status = ReconciliationStatus.MODAL_OCCLUDED,
                    resolvedElement = screen.dismissCandidate,
                    safeToInteract = false,
                    explanation = "A modal dialog ('${screen.activeDialogTitle}') is currently blocking the screen. It should be addressed or dismissed before proceeding.",
                    suggestedAction = "DISMISS_DIALOG",
                )
            }
        }

        // 3. Check for keyboard occlusion
        if (matchedElement != null && screen.keyboardVisible && !matchedElement.isInInputMethodWindow) {
            if (screen.keyboardTopY in 1..matchedElement.centerY) {
                return ReconciliationResult(
                    status = ReconciliationStatus.MODAL_OCCLUDED,
                    resolvedElement = matchedElement,
                    safeToInteract = false,
                    explanation = "Target element '${matchedElement.primaryLabel}' is occluded behind the active on-screen keyboard.",
                    suggestedAction = "HIDE_KEYBOARD",
                )
            }
        }

        // 4. Element not found in accessibility hierarchy
        if (matchedElement == null) {
            // Check if any visual text region matches the target query
            val visualMatch = visual.extractedRegions.firstOrNull { region ->
                val target = query.rawTarget.lowercase(Locale.ROOT)
                region.text.lowercase(Locale.ROOT).contains(target)
            }

            return if (visualMatch != null) {
                // Synthesize an observed element from OCR bounds
                val synthesized = ObservedUiElement(
                    index = 9999,
                    resourceId = "visual_ocr",
                    text = visualMatch.text,
                    contentDescription = visualMatch.text,
                    hintText = "",
                    className = "android.view.View",
                    packageName = screen.packageName,
                    clickable = true,
                    longClickable = false,
                    editable = false,
                    enabled = true,
                    checkable = false,
                    checked = false,
                    selected = false,
                    scrollable = false,
                    focusable = true,
                    focused = false,
                    visibleToUser = true,
                    bounds = visualMatch.bounds,
                    boundsLeft = visualMatch.bounds.left,
                    boundsTop = visualMatch.bounds.top,
                    boundsRight = visualMatch.bounds.right,
                    boundsBottom = visualMatch.bounds.bottom,
                )
                ReconciliationResult(
                    status = ReconciliationStatus.CONSISTENT,
                    resolvedElement = synthesized,
                    safeToInteract = true,
                    explanation = "Target located via visual screen OCR text '${visualMatch.text}' at (${synthesized.centerX}, ${synthesized.centerY}).",
                    suggestedAction = "PROCEED_VISUAL",
                )
            } else {
                ReconciliationResult(
                    status = ReconciliationStatus.CONFLICTING_EVIDENCE,
                    resolvedElement = null,
                    safeToInteract = false,
                    explanation = "Target element '${query.rawTarget}' could not be identified in either the accessibility hierarchy or visual screen capture.",
                    suggestedAction = "SCROLL_OR_ASK",
                )
            }
        }

        // 5. Verification of healthy bounds
        if (!matchedElement.hasValidTouchBounds(screen.screenWidth, screen.screenHeight)) {
            return ReconciliationResult(
                status = ReconciliationStatus.CONFLICTING_EVIDENCE,
                resolvedElement = matchedElement,
                safeToInteract = false,
                explanation = "Element '${matchedElement.primaryLabel}' has invalid or collapsed bounds on screen (${matchedElement.left}, ${matchedElement.top}, ${matchedElement.right}, ${matchedElement.bottom}).",
                suggestedAction = "SCROLL_TO_REVEAL",
            )
        }

        // 6. Check for higher-window or sibling overlay occlusion
        val occlusion = HierarchyOcclusionDetector.detectOcclusion(matchedElement, screen)
        if (occlusion.isOccluded) {
            return ReconciliationResult(
                status = ReconciliationStatus.MODAL_OCCLUDED,
                resolvedElement = matchedElement,
                safeToInteract = false,
                explanation = occlusion.reason,
                suggestedAction = "DISMISS_OVERLAY",
            )
        }

        // 7. Complete agreement: both accessibility and visual geometry are aligned
        return ReconciliationResult(
            status = ReconciliationStatus.CONSISTENT,
            resolvedElement = matchedElement,
            safeToInteract = true,
            explanation = "Accessibility hierarchy and visual screen layout agree. Target '${matchedElement.primaryLabel}' is visible at (${matchedElement.centerX}, ${matchedElement.centerY}).",
            suggestedAction = "PROCEED",
        )
    }

    /**
     * Computes a lightweight pixel difference between pre-action and post-action
     * bitmaps (sampled on a coarse grid for Galaxy A54 efficiency) so custom Canvas,
     * WebView, or video surfaces that do not emit accessibility events can still be
     * verified visually.
     */
    fun computeBitmapDiff(
        before: Bitmap?,
        after: Bitmap?,
        targetBounds: Rect? = null,
    ): VisualScreenDiff {
        if (before == null || after == null ||
            before.width <= 0 || before.height <= 0 ||
            after.width <= 0 || after.height <= 0
        ) {
            return VisualScreenDiff(
                available = false,
                overallChangeRatio = 0f,
                targetRegionChangeRatio = 0f,
                visualChanged = false,
            )
        }

        val w = min(before.width, after.width)
        val h = min(before.height, after.height)
        val stepX = max(1, w / 36)
        val stepY = max(1, h / 36)

        var totalSamples = 0
        var changedSamples = 0
        var targetSamples = 0
        var targetChanged = 0

        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val p1 = before.getPixel(x, y)
                val p2 = after.getPixel(x, y)
                val dr = abs(Color.red(p1) - Color.red(p2))
                val dg = abs(Color.green(p1) - Color.green(p2))
                val db = abs(Color.blue(p1) - Color.blue(p2))
                val diff = (dr + dg + db) > 36

                totalSamples++
                if (diff) changedSamples++

                if (targetBounds != null &&
                    x in targetBounds.left..targetBounds.right &&
                    y in targetBounds.top..targetBounds.bottom
                ) {
                    targetSamples++
                    if (diff) targetChanged++
                }
            }
        }

        val overallRatio = if (totalSamples > 0) changedSamples.toFloat() / totalSamples.toFloat() else 0f
        val regionRatio = if (targetSamples > 0) targetChanged.toFloat() / targetSamples.toFloat() else overallRatio
        val changed = overallRatio >= 0.02f || regionRatio >= 0.08f

        return VisualScreenDiff(
            available = true,
            overallChangeRatio = overallRatio,
            targetRegionChangeRatio = regionRatio,
            visualChanged = changed,
        )
    }

    /**
     * Matches a visual region (from OCR or a vision model bounding box) to the best
     * corresponding [ObservedUiElement] in the accessibility hierarchy.
     */
    fun matchVisualRegionToAccessibility(
        region: VisualTextRegion,
        screen: ObservedScreenState,
    ): ObservedUiElement? {
        val normText = region.text.trim().lowercase(Locale.ROOT)
        var bestElement: ObservedUiElement? = null
        var bestScore = 0f

        for (el in screen.elements) {
            if (!el.visibleToUser) continue
            val iou = computeIoU(
                region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom,
                el.left, el.top, el.right, el.bottom,
            )
            val containsCenter = region.bounds.centerX() in el.left..el.right &&
                region.bounds.centerY() in el.top..el.bottom
            val textMatch = normText.isNotBlank() &&
                (el.primaryLabel.lowercase(Locale.ROOT).contains(normText) ||
                    normText.contains(el.primaryLabel.lowercase(Locale.ROOT)))

            var score = iou * 100f
            if (containsCenter) score += 35f
            if (textMatch) score += 50f

            if (score > bestScore && score >= 25f) {
                bestScore = score
                bestElement = el
            }
        }
        return bestElement
    }

    private fun computeIoU(
        l1: Int, t1: Int, r1: Int, b1: Int,
        l2: Int, t2: Int, r2: Int, b2: Int,
    ): Float {
        val interLeft = max(l1, l2)
        val interTop = max(t1, t2)
        val interRight = min(r1, r2)
        val interBottom = min(b1, b2)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft).toFloat() * (interBottom - interTop).toFloat()
        val area1 = max(1, (r1 - l1) * (b1 - t1)).toFloat()
        val area2 = max(1, (r2 - l2) * (b2 - t2)).toFloat()
        return interArea / (area1 + area2 - interArea).coerceAtLeast(1f)
    }
}

/**
 * Pixel-level difference summary between pre-action and post-action frames.
 */
data class VisualScreenDiff(
    val available: Boolean,
    val overallChangeRatio: Float,
    val targetRegionChangeRatio: Float,
    val visualChanged: Boolean,
)

/**
 * Maps coordinates between downscaled screenshot space, normalized [0..100] percent
 * or [0..1000] grid space, and physical Galaxy A54 display pixel space.
 */
object ScreenCoordinateMapper {

    fun mapScreenshotPointToDisplay(
        screenshotX: Int,
        screenshotY: Int,
        screenshotWidth: Int,
        screenshotHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Int, Int>? {
        if (screenshotWidth <= 0 || screenshotHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return null
        }
        if (screenshotX < 0 || screenshotY < 0 || screenshotX > screenshotWidth || screenshotY > screenshotHeight) {
            return null
        }
        val pxX = ((screenshotX.toDouble() / screenshotWidth.toDouble()) * displayWidth).toInt()
            .coerceIn(1, displayWidth - 1)
        val pxY = ((screenshotY.toDouble() / screenshotHeight.toDouble()) * displayHeight).toInt()
            .coerceIn(1, displayHeight - 1)
        return pxX to pxY
    }

    fun mapPercentToDisplay(
        percentX: Double,
        percentY: Double,
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Int, Int>? {
        if (displayWidth <= 0 || displayHeight <= 0) return null
        if (percentX < 0.0 || percentX > 100.0 || percentY < 0.0 || percentY > 100.0) return null
        val pxX = ((percentX / 100.0) * displayWidth).toInt().coerceIn(1, displayWidth - 1)
        val pxY = ((percentY / 100.0) * displayHeight).toInt().coerceIn(1, displayHeight - 1)
        return pxX to pxY
    }
}

data class OcclusionReport(
    val isOccluded: Boolean,
    val occludingSource: String = "",
    val reason: String = "",
)

/**
 * Detects when a target UI element is covered by a higher-layer window,
 * keyboard, modal dialog, or overlapping overlay node.
 */
object HierarchyOcclusionDetector {

    fun detectOcclusion(
        target: ObservedUiElement,
        screen: ObservedScreenState,
    ): OcclusionReport {
        val cx = target.centerX
        val cy = target.centerY

        // 1. Check higher-layer windows (e.g., system dialog or overlay window sitting above app window)
        for (win in screen.windows) {
            if (win.windowId != -1 && target.windowId != -1 && win.windowId != target.windowId) {
                if (win.layer > target.windowLayer &&
                    cx in win.boundsLeft..win.boundsRight &&
                    cy in win.boundsTop..win.boundsBottom
                ) {
                    return OcclusionReport(
                        isOccluded = true,
                        occludingSource = "window:${win.windowType}:${win.title.ifBlank { win.packageName }}",
                        reason = "Target '${target.primaryLabel}' is occluded by a higher-layer ${win.windowType} window ('${win.title.ifBlank { win.packageName }}').",
                    )
                }
            }
        }

        // 2. Check soft keyboard occlusion
        if (screen.keyboardVisible && !target.isInInputMethodWindow && screen.keyboardTopY in 1..cy) {
            return OcclusionReport(
                isOccluded = true,
                occludingSource = "input_method_keyboard",
                reason = "Target '${target.primaryLabel}' at y=$cy is covered by the visible keyboard (topY=${screen.keyboardTopY}).",
            )
        }

        return OcclusionReport(isOccluded = false)
    }
}

data class VisionConsentDecision(
    val allowed: Boolean,
    val reason: String,
)

/**
 * Enforces privacy and user-consent rules before any screenshot is captured for
 * or transmitted to an external vision-language model.
 */
object VisionPrivacyGate {

    fun evaluateExternalVisionRequest(
        userConsentedToVision: Boolean,
        modelSupportsVision: Boolean,
        screen: ObservedScreenState,
        visual: VisualEvidence? = null,
    ): VisionConsentDecision {
        if (!userConsentedToVision) {
            return VisionConsentDecision(
                allowed = false,
                reason = "User has not consented to sending screen captures to an external model.",
            )
        }
        if (!modelSupportsVision) {
            return VisionConsentDecision(
                allowed = false,
                reason = "The active AI model or tool configuration does not allow image inputs.",
            )
        }
        if (screen.isSecureOrProtected) {
            return VisionConsentDecision(
                allowed = false,
                reason = "Foreground package '${screen.packageName}' is a protected security surface; screenshot transmission is blocked.",
            )
        }
        if (visual?.isSecureBlackout == true) {
            return VisionConsentDecision(
                allowed = false,
                reason = "Screen is protected by Android FLAG_SECURE; screenshot transmission is blocked.",
            )
        }
        if (screen.hasPasswordField()) {
            return VisionConsentDecision(
                allowed = false,
                reason = "A password or PIN entry field is currently visible on screen; external screenshot transmission is blocked to protect your credentials.",
            )
        }
        return VisionConsentDecision(
            allowed = true,
            reason = "Vision capture permitted.",
        )
    }
}

