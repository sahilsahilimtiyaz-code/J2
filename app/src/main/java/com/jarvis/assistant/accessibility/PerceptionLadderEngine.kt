package com.jarvis.assistant.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

/**
 * The 4 Rungs of Jarvis's Perception Ladder (Part 3).
 */
enum class PerceptionRung(val rungNumber: Int, val label: String) {
    RUNG_1_INTENT_OR_NOTIFICATION(1, "Notification Action / RemoteInput / Deep Link"),
    RUNG_2_ACCESSIBILITY_TREE(2, "Accessibility Node Tree (ID / Text / Role / Bounds)"),
    RUNG_3_VISION_SET_OF_MARK(3, "Screenshot + Numbered Box Vision Model"),
    RUNG_4_POST_VERIFY(4, "Post-Action Verification & Recovery"),
}

enum class ExpectedPostCondition {
    KEYBOARD_VISIBLE,
    SEARCH_FIELD_FOCUSED,
    CALL_SCREEN_SHOWN,
    CHAT_THREAD_OPENED,
    RECORDING_BAR_VISIBLE,
    PACKAGE_CHANGED,
    SCREEN_CHANGED,
}

data class CachedNodeSelector(
    val packageName: String,
    val appVersionCode: Long,
    val intentKey: String,
    val resourceId: String = "",
    val contentDescription: String = "",
    val text: String = "",
    val role: UiElementRole = UiElementRole.ANY,
    val normalizedCenterX: Float = 0.5f,
    val normalizedCenterY: Float = 0.5f,
    val successCount: Int = 1,
    val lastVerifiedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Caches successful selectors per (packageName + appVersionCode + intentKey) and
 * replays them before falling back to vision models. Invalidates immediately on verification failure.
 */
class SelectorCache {
    private val store = ConcurrentHashMap<String, CachedNodeSelector>()

    private fun key(packageName: String, versionCode: Long, intentKey: String): String =
        "${packageName.lowercase(Locale.ROOT)}:$versionCode:${intentKey.lowercase(Locale.ROOT).trim()}"

    fun putVerified(selector: CachedNodeSelector) {
        val k = key(selector.packageName, selector.appVersionCode, selector.intentKey)
        val existing = store[k]
        store[k] = if (existing != null) {
            selector.copy(
                successCount = existing.successCount + 1,
                lastVerifiedAtMs = System.currentTimeMillis(),
            )
        } else {
            selector
        }
    }

    fun get(packageName: String, versionCode: Long, intentKey: String): CachedNodeSelector? =
        store[key(packageName, versionCode, intentKey)]

    fun invalidate(packageName: String, versionCode: Long, intentKey: String) {
        store.remove(key(packageName, versionCode, intentKey))
    }

    fun size(): Int = store.size

    fun clear() {
        store.clear()
    }
}

/**
 * Set-of-Mark (SoM) Screenshot Annotator for Rung 3:
 * Downscales the screenshot and draws numbered high-contrast bounding boxes (#1, #2, #3...)
 * on clickable/editable nodes, paired with a structured node list for the vision model.
 */
object SetOfMarkAnnotator {

    data class AnnotatedVisionFrame(
        val annotatedBitmap: Bitmap,
        val boxMap: Map<Int, ObservedUiElement>,
        val promptNodeList: String,
        val scaleFactor: Float,
    )

    data class VisionBoxDecision(
        val boxNumber: Int,
        val expectedResult: String,
        val confidence: Float = 0.85f,
        val reasoning: String = "",
    )

    fun annotate(
        rawScreenshot: Bitmap,
        screen: ObservedScreenState,
        maxDimensionPx: Int = 720,
    ): AnnotatedVisionFrame {
        val srcW = rawScreenshot.width.coerceAtLeast(1)
        val srcH = rawScreenshot.height.coerceAtLeast(1)
        val scale = min(1f, maxDimensionPx.toFloat() / max(srcW, srcH).toFloat())
        val dstW = (srcW * scale).toInt().coerceAtLeast(1)
        val dstH = (srcH * scale).toInt().coerceAtLeast(1)

        val scaledBitmap = Bitmap.createScaledBitmap(rawScreenshot, dstW, dstH, true)
            .copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(scaledBitmap)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(0, 229, 255) // Arc-reactor cyan
        }
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 8, 20, 38)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            isFakeBoldText = true
        }

        val actionable = screen.elements.filter { el ->
            el.enabled && (el.clickable || el.editable || el.checkable || el.scrollable) &&
                (el.right - el.left) >= 20 && (el.bottom - el.top) >= 20
        }.take(45)

        val boxMap = LinkedHashMap<Int, ObservedUiElement>()
        val promptLines = mutableListOf<String>()

        actionable.forEachIndexed { idx, el ->
            val boxNum = idx + 1
            boxMap[boxNum] = el

            val left = el.boundsLeft * scale
            val top = el.boundsTop * scale
            val right = el.boundsRight * scale
            val bottom = el.boundsBottom * scale

            canvas.drawRect(left, top, right, bottom, boxPaint)
            val badgeText = "#$boxNum"
            val textWidth = textPaint.measureText(badgeText)
            val badgeRect = RectF(
                left.coerceAtLeast(0f),
                (top - 22f).coerceAtLeast(0f),
                (left + textWidth + 12f).coerceAtMost(dstW.toFloat()),
                (top + 2f).coerceAtMost(dstH.toFloat()),
            )
            canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBgPaint)
            canvas.drawText(badgeText, badgeRect.left + 6f, badgeRect.bottom - 5f, textPaint)

            val shortId = el.resourceId.substringAfterLast('/')
            val label = el.primaryLabel.take(60)
            promptLines += "#$boxNum [role=${el.inferRole().name}, id=$shortId, bounds=(${el.boundsLeft},${el.boundsTop},${el.boundsRight},${el.boundsBottom})] \"$label\""
        }

        return AnnotatedVisionFrame(
            annotatedBitmap = scaledBitmap,
            boxMap = boxMap,
            promptNodeList = promptLines.joinToString("\n"),
            scaleFactor = scale,
        )
    }

    fun parseVisionResponse(rawJsonOrText: String): VisionBoxDecision? {
        val trimmed = rawJsonOrText.trim()
        if (trimmed.isBlank()) return null

        val boxMatch = Regex(""""(?:boxNumber|box)"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""#(\d{1,2})\b""").find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (boxMatch == null || boxMatch <= 0) return null

        val expectedMatch = Regex(""""(?:expectedResult|expected)"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: "screen_changed"

        val confMatch = Regex(""""confidence"\s*:\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: 0.85f

        val reasonMatch = Regex(""""reasoning"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)
            .orEmpty()

        return VisionBoxDecision(
            boxNumber = boxMatch,
            expectedResult = expectedMatch,
            confidence = confMatch,
            reasoning = reasonMatch,
        )
    }
}

/**
 * Result of a single step on the Perception Ladder.
 */
data class PerceptionStepOutcome(
    val success: Boolean,
    val verified: Boolean,
    val rungUsed: PerceptionRung,
    val targetElement: ObservedUiElement? = null,
    val recoveryUsed: Boolean = false,
    val blockedByRedTier: Boolean = false,
    val blockedBySecureOrBanking: Boolean = false,
    val summary: String,
)

/**
 * PART 3: Shared 4-Rung Perception Ladder Engine.
 *
 * Enforces:
 * - Max 12 steps per task
 * - Banking / UPI / Password Manager blocklist
 * - RED-tier block on pay / send money / delete / confirm purchase without explicit approval
 * - Blank / FLAG_SECURE screen stop
 * - Selector cache replay before Vision
 * - Post-step verification with back-out + alternate-rung retry on mismatch
 */
class PerceptionLadderEngine(
    val selectorCache: SelectorCache = SelectorCache(),
) {
    @Volatile
    var globalKillSwitchActive: Boolean = false

    companion object {
        const val MAX_STEPS_PER_TASK = 12

        private val BLOCKED_BANKING_UPI_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay (GPay)
            "com.phonepe.app", // PhonePe
            "net.one97.paytm", // Paytm
            "in.org.npci.upiapp", // BHIM UPI
            "in.amazon.mShop.android.shopping.pay",
            "com.whatsapp.payments",
            "com.sbi.lotusintouch", // YONO SBI
            "com.snapwork.hdfc", // HDFC Bank
            "com.csam.icici.bank.imobile", // iMobile ICICI
            "com.axis.mobile", // Axis Mobile
            "com.kotak.bank.mobile", // Kotak
            "com.paypal.android.p2pmobile",
            "com.x8bit.bitwarden",
            "com.agilebits.onepassword",
            "com.dashlane",
            "com.lastpass.lpandroid",
        )

        private val RED_TIER_PAYMENT_OR_DELETE_REGEX = Regex(
            pattern = "\\b(pay now|send money|transfer money|confirm payment|proceed to pay|authorize payment|place order|buy now|confirm purchase|delete account|delete chat|delete permanently|erase all|uninstall)\\b",
            option = RegexOption.IGNORE_CASE,
        )

        private val COMMON_DIALOG_DISMISS_LABELS = listOf(
            "not now",
            "no thanks",
            "dismiss",
            "skip",
            "later",
            "got it",
            "close",
        )

        fun isBlockedBankingOrUpiApp(packageName: String, customBlocklist: Set<String> = emptySet()): Boolean {
            val lower = packageName.lowercase(Locale.ROOT).trim()
            if (lower.isBlank()) return false
            if (lower in BLOCKED_BANKING_UPI_PACKAGES || lower in customBlocklist.map { it.lowercase(Locale.ROOT) }) {
                return true
            }
            return lower.contains(".upi") ||
                lower.contains("netbanking") ||
                lower.contains("passwordmanager") ||
                lower.contains("authenticator")
        }

        fun requiresRedTierApproval(targetTextOrQuery: String, matchedElement: ObservedUiElement? = null): Boolean {
            if (RED_TIER_PAYMENT_OR_DELETE_REGEX.containsMatchIn(targetTextOrQuery)) return true
            if (matchedElement != null) {
                val combined = "${matchedElement.text} ${matchedElement.contentDescription} ${matchedElement.resourceId}"
                if (RED_TIER_PAYMENT_OR_DELETE_REGEX.containsMatchIn(combined)) return true
            }
            return false
        }
    }

    /**
     * Resolves a target element using:
     * 1. Cached selector (if previously verified for this package + version)
     * 2. Rung 2 Accessibility Node Tree (with strict anti-Shorts and sibling/spatial disambiguation)
     * 3. Rung 3 Set-of-Mark Vision fallback (when tree matching is ambiguous or empty)
     */
    fun locateTargetOnScreen(
        screen: ObservedScreenState,
        targetQuery: String,
        intentKey: String = targetQuery,
        appVersionCode: Long = 1L,
        expectedRole: UiElementRole = UiElementRole.ANY,
        ownerApprovedRedTier: Boolean = false,
        visualEvidence: VisualEvidence? = null,
        visionSelectorCallback: ((SetOfMarkAnnotator.AnnotatedVisionFrame) -> SetOfMarkAnnotator.VisionBoxDecision?)? = null,
    ): PerceptionStepOutcome {
        if (globalKillSwitchActive) {
            return PerceptionStepOutcome(
                success = false,
                verified = false,
                rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                summary = "Stopped: Global Kill Switch is active.",
            )
        }

        // 1. Check banking / UPI / password manager blocklist
        if (isBlockedBankingOrUpiApp(screen.packageName)) {
            return PerceptionStepOutcome(
                success = false,
                verified = false,
                rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                blockedBySecureOrBanking = true,
                summary = "Blocked: '${screen.packageName}' is a protected banking/UPI/password app. Jarvis never automates financial or credential apps.",
            )
        }

        // 2. Check secure / blank screen
        if (visualEvidence?.isSecureBlackout == true || screen.elements.isEmpty()) {
            return PerceptionStepOutcome(
                success = false,
                verified = false,
                rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                blockedBySecureOrBanking = true,
                summary = "Stopped: The current window is blank or protected by Android FLAG_SECURE.",
            )
        }

        // 3. Check if a common popup dialog is occluding the screen and can be handled by rule
        if (screen.activeDialogTitle != null && screen.dismissCandidate != null) {
            val dialogDismiss = screen.elements.firstOrNull { el ->
                el.clickable && COMMON_DIALOG_DISMISS_LABELS.any { d -> el.primaryLabel.equals(d, ignoreCase = true) }
            } ?: screen.dismissCandidate
            if (dialogDismiss != null && !targetQuery.contains(screen.activeDialogTitle, ignoreCase = true)) {
                return PerceptionStepOutcome(
                    success = true,
                    verified = true,
                    rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                    targetElement = dialogDismiss,
                    summary = "Handled blocking dialog '${screen.activeDialogTitle}' via rule ('${dialogDismiss.primaryLabel}').",
                )
            }
        }

        // 4. Try cached verified selector first
        val cached = selectorCache.get(screen.packageName, appVersionCode, intentKey)
        if (cached != null) {
            val fromCache = screen.elements.firstOrNull { el ->
                el.enabled && (
                    (cached.resourceId.isNotBlank() && el.resourceId.equals(cached.resourceId, ignoreCase = true)) ||
                        (cached.contentDescription.isNotBlank() && el.contentDescription.equals(cached.contentDescription, ignoreCase = true)) ||
                        (cached.text.isNotBlank() && el.text.equals(cached.text, ignoreCase = true))
                    )
            }
            if (fromCache != null) {
                if (!ownerApprovedRedTier && requiresRedTierApproval(targetQuery, fromCache)) {
                    return PerceptionStepOutcome(
                        success = false,
                        verified = false,
                        rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                        targetElement = fromCache,
                        blockedByRedTier = true,
                        summary = "RED-tier approval required before tapping '${fromCache.primaryLabel}'.",
                    )
                }
                return PerceptionStepOutcome(
                    success = true,
                    verified = true,
                    rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                    targetElement = fromCache,
                    summary = "Matched '${fromCache.primaryLabel}' via verified selector cache.",
                )
            }
        }

        // 5. Rung 2: Accessibility Node Tree with disambiguation
        val matchResult = JarvisUiElementMatcher.resolve(
            screen = screen,
            query = ElementMatchQuery(
                rawTarget = targetQuery,
                expectedRole = expectedRole,
                preferEditable = expectedRole == UiElementRole.TEXT_FIELD,
                packageContext = screen.packageName,
            ),
        )

        val bestCandidate = matchResult.best
        if (bestCandidate != null && !matchResult.ambiguityDetected) {
            val elem = bestCandidate.element
            if (!ownerApprovedRedTier && requiresRedTierApproval(targetQuery, elem)) {
                return PerceptionStepOutcome(
                    success = false,
                    verified = false,
                    rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                    targetElement = elem,
                    blockedByRedTier = true,
                    summary = "RED-tier approval required before tapping '${elem.primaryLabel}'.",
                )
            }
            return PerceptionStepOutcome(
                success = true,
                verified = true,
                rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
                targetElement = elem,
                summary = "Matched '${elem.primaryLabel}' via Rung 2 Accessibility Tree (${bestCandidate.matchedBy.joinToString("+")}).",
            )
        }

        // 6. Rung 3: Vision Set-of-Mark Fallback when Rung 2 is ambiguous or finds no match
        if (visionSelectorCallback != null) {
            val dummyBitmap = Bitmap.createBitmap(
                screen.screenWidth.coerceAtLeast(100),
                screen.screenHeight.coerceAtLeast(100),
                Bitmap.Config.ARGB_8888,
            )
            val annotated = SetOfMarkAnnotator.annotate(dummyBitmap, screen)
            val decision = visionSelectorCallback(annotated)
            val chosen = decision?.let { annotated.boxMap[it.boxNumber] }
            if (chosen != null) {
                if (!ownerApprovedRedTier && requiresRedTierApproval(targetQuery, chosen)) {
                    return PerceptionStepOutcome(
                        success = false,
                        verified = false,
                        rungUsed = PerceptionRung.RUNG_3_VISION_SET_OF_MARK,
                        targetElement = chosen,
                        blockedByRedTier = true,
                        summary = "RED-tier approval required before tapping box #${decision.boxNumber} ('${chosen.primaryLabel}').",
                    )
                }
                return PerceptionStepOutcome(
                    success = true,
                    verified = true,
                    rungUsed = PerceptionRung.RUNG_3_VISION_SET_OF_MARK,
                    targetElement = chosen,
                    summary = "Matched box #${decision.boxNumber} ('${chosen.primaryLabel}') via Rung 3 Set-of-Mark Vision.",
                )
            }
        }

        val reason = if (matchResult.ambiguityDetected) {
            "Multiple ambiguous nodes matched '$targetQuery' (${matchResult.runnersUp.take(2).joinToString { it.element.primaryLabel }}); refused to guess."
        } else {
            "Could not locate '$targetQuery' on screen '${screen.packageName}'."
        }
        return PerceptionStepOutcome(
            success = false,
            verified = false,
            rungUsed = PerceptionRung.RUNG_2_ACCESSIBILITY_TREE,
            summary = reason,
        )
    }

    /**
     * Rung 4: Verifies the expected post-condition after an action and recovers from an injected
     * wrong tap by backing out (`onBackOut`), invalidating the selector cache, and retrying once
     * with an alternate rung (`onRetryWithNextRung`).
     */
    fun verifyStepAndRecover(
        beforeScreen: ObservedScreenState,
        afterFirstAttemptScreen: ObservedScreenState,
        expectedPostCondition: ExpectedPostCondition,
        targetQuery: String,
        appVersionCode: Long = 1L,
        expectedTextOrPackage: String = "",
        onBackOut: () -> ObservedScreenState,
        onRetryWithNextRung: (ObservedScreenState) -> Pair<Boolean, ObservedScreenState>,
    ): PerceptionStepOutcome {
        if (checkPostCondition(beforeScreen, afterFirstAttemptScreen, expectedPostCondition, expectedTextOrPackage)) {
            return PerceptionStepOutcome(
                success = true,
                verified = true,
                rungUsed = PerceptionRung.RUNG_4_POST_VERIFY,
                summary = "Verified post-condition $expectedPostCondition on first attempt.",
            )
        }

        // First attempt landed on the wrong screen (e.g. injected wrong tap on Shorts instead of Search):
        // 1. Invalidate cached selector
        selectorCache.invalidate(beforeScreen.packageName, appVersionCode, targetQuery)

        // 2. Back out to previous screen
        val restoredScreen = onBackOut()

        // 3. Retry once with alternate rung
        val (retryTapped, afterRetryScreen) = onRetryWithNextRung(restoredScreen)
        if (retryTapped && checkPostCondition(restoredScreen, afterRetryScreen, expectedPostCondition, expectedTextOrPackage)) {
            return PerceptionStepOutcome(
                success = true,
                verified = true,
                rungUsed = PerceptionRung.RUNG_4_POST_VERIFY,
                recoveryUsed = true,
                summary = "Recovered from wrong tap: backed out, retried with alternate rung, and verified $expectedPostCondition.",
            )
        }

        return PerceptionStepOutcome(
            success = false,
            verified = false,
            rungUsed = PerceptionRung.RUNG_4_POST_VERIFY,
            recoveryUsed = true,
            summary = "Verification of $expectedPostCondition failed after back-out and retry; stopped safely.",
        )
    }

    fun checkPostCondition(
        before: ObservedScreenState,
        after: ObservedScreenState,
        condition: ExpectedPostCondition,
        expectedHint: String = "",
    ): Boolean {
        // Guard: if we accidentally opened YouTube Shorts when we wanted Search or Profile, fail immediately!
        if (!expectedHint.contains("shorts", ignoreCase = true)) {
            val landedOnShortsPlayer = after.elements.any {
                it.resourceId.contains("reel_player", ignoreCase = true) ||
                    it.resourceId.contains("shorts_player", ignoreCase = true)
            }
            if (landedOnShortsPlayer) return false
        }

        val visibleLabels = after.elements.map { it.primaryLabel } + listOf(after.windowTitle)

        return when (condition) {
            ExpectedPostCondition.KEYBOARD_VISIBLE ->
                after.keyboardVisible || after.elements.any { it.editable && it.focused }
            ExpectedPostCondition.SEARCH_FIELD_FOCUSED ->
                after.elements.any {
                    it.editable && (it.focused || it.inferRole() == UiElementRole.TEXT_FIELD || it.inferRole() == UiElementRole.SEARCH_TRIGGER)
                }
            ExpectedPostCondition.CALL_SCREEN_SHOWN ->
                visibleLabels.any {
                    it.contains("mute", ignoreCase = true) ||
                        it.contains("speaker", ignoreCase = true) ||
                        Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(it)
                }
            ExpectedPostCondition.CHAT_THREAD_OPENED ->
                after.elements.any { it.editable || it.resourceId.contains("entry") || it.resourceId.contains("voice_note") } &&
                    (expectedHint.isBlank() || visibleLabels.any { it.contains(expectedHint, ignoreCase = true) })
            ExpectedPostCondition.RECORDING_BAR_VISIBLE ->
                visibleLabels.any {
                    it.contains("slide to cancel", ignoreCase = true) ||
                        it.contains("recording", ignoreCase = true) ||
                        Regex("\\b0:\\d{2}\\b").containsMatchIn(it)
                }
            ExpectedPostCondition.PACKAGE_CHANGED ->
                if (expectedHint.isNotBlank()) {
                    after.packageName.equals(expectedHint, ignoreCase = true)
                } else {
                    after.packageName != before.packageName
                }
            ExpectedPostCondition.SCREEN_CHANGED ->
                after.structuralSignature() != before.structuralSignature()
        }
    }
}
