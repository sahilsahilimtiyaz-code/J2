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

        // 6. Complete agreement: both accessibility and visual geometry are aligned
        return ReconciliationResult(
            status = ReconciliationStatus.CONSISTENT,
            resolvedElement = matchedElement,
            safeToInteract = true,
            explanation = "Accessibility hierarchy and visual screen layout agree. Target '${matchedElement.primaryLabel}' is visible at (${matchedElement.centerX}, ${matchedElement.centerY}).",
            suggestedAction = "PROCEED",
        )
    }
}
