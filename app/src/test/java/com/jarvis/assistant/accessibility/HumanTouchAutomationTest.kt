package com.jarvis.assistant.accessibility

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTouchAutomationTest {

    private fun sampleScreen(
        packageName: String = "com.google.android.youtube",
        elements: List<ObservedUiElement>,
        keyboardVisible: Boolean = false,
        keyboardTopY: Int = 2340,
        dialogTitle: String? = null,
        dismissCandidate: ObservedUiElement? = null,
    ): ObservedScreenState = ObservedScreenState(
        packageName = packageName,
        appLabel = "YouTube",
        windowTitle = "YouTube Main",
        screenWidth = 1080,
        screenHeight = 2340,
        rotation = 0,
        keyboardVisible = keyboardVisible,
        keyboardTopY = keyboardTopY,
        activeDialogTitle = dialogTitle,
        dismissCandidate = dismissCandidate,
        elements = elements,
    )

    private fun sampleElement(
        index: Int,
        text: String = "",
        contentDescription: String = "",
        resourceId: String = "",
        className: String = "android.widget.Button",
        clickable: Boolean = true,
        editable: Boolean = false,
        enabled: Boolean = true,
        boundsLeft: Int = 100,
        boundsTop: Int = 200,
        boundsRight: Int = 300,
        boundsBottom: Int = 320,
        parentLabel: String = "",
        childLabels: List<String> = emptyList(),
    ): ObservedUiElement = ObservedUiElement(
        index = index,
        resourceId = resourceId,
        text = text,
        contentDescription = contentDescription,
        hintText = "",
        className = className,
        packageName = "com.google.android.youtube",
        clickable = clickable,
        longClickable = false,
        editable = editable,
        enabled = enabled,
        checkable = false,
        checked = false,
        selected = false,
        scrollable = false,
        focusable = true,
        focused = false,
        visibleToUser = true,
        bounds = Rect(boundsLeft, boundsTop, boundsRight, boundsBottom),
        parentLabel = parentLabel,
        childLabels = childLabels,
        boundsLeft = boundsLeft,
        boundsTop = boundsTop,
        boundsRight = boundsRight,
        boundsBottom = boundsBottom,
    )

    @Test
    fun testExactResourceIdMatchingRanksHighest() {
        val el1 = sampleElement(
            index = 1,
            text = "Search YouTube",
            contentDescription = "Search",
            resourceId = "com.google.android.youtube:id/menu_item_search",
        )
        val el2 = sampleElement(
            index = 2,
            text = "Search settings",
            resourceId = "com.google.android.youtube:id/other_button",
        )
        val screen = sampleScreen(elements = listOf(el1, el2))

        val result = JarvisUiElementMatcher.resolve(
            screen,
            ElementMatchQuery(rawTarget = "com.google.android.youtube:id/menu_item_search"),
        )
        assertNotNull(result.best)
        assertEquals(1, result.best!!.element.index)
        assertTrue(result.best!!.matchedBy.contains("exact_resource_id"))
    }

    @Test
    fun testOrdinalDisambiguationResolvesSecondItem() {
        val item1 = sampleElement(index = 1, text = "Android 16 Quick Start", boundsLeft = 40, boundsTop = 300, boundsRight = 1040, boundsBottom = 500)
        val item2 = sampleElement(index = 2, text = "Android 16 Deep Dive", boundsLeft = 40, boundsTop = 520, boundsRight = 1040, boundsBottom = 720)
        val item3 = sampleElement(index = 3, text = "Android 16 Accessibility", boundsLeft = 40, boundsTop = 740, boundsRight = 1040, boundsBottom = 940)
        val screen = sampleScreen(elements = listOf(item1, item2, item3))

        val result = JarvisUiElementMatcher.resolve(
            screen,
            ElementMatchQuery(rawTarget = "click the second one"),
        )
        assertNotNull(result.best)
        assertEquals(2, result.best!!.element.index)
        assertTrue(result.best!!.matchedBy.any { it.startsWith("ordinal_position") })
    }

    @Test
    fun testKeyboardOcclusionPenalizesOccludedNodes() {
        val lowerButton = sampleElement(
            index = 1,
            text = "Submit Comment",
            boundsLeft = 100,
            boundsTop = 1900,
            boundsRight = 980,
            boundsBottom = 2050,
        )
        val screenWithKeyboard = sampleScreen(
            elements = listOf(lowerButton),
            keyboardVisible = true,
            keyboardTopY = 1400,
        )

        val result = JarvisUiElementMatcher.resolve(
            screenWithKeyboard,
            ElementMatchQuery(rawTarget = "Submit Comment"),
        )
        assertNotNull(result.best)
        assertTrue(result.best!!.matchedBy.contains("keyboard_occluded"))
    }

    @Test
    fun testAntiDuplicationBlocksConsequentialActionRetry() {
        val screen = sampleScreen(elements = emptyList())
        val report = ActionVerificationReport(
            status = VerificationStatus.VERIFIED_SUCCESS,
            highestLevelPassed = VerificationLevel.LEVEL_2_UI_DELTA,
            level1InteractionPassed = true,
            level2UiDeltaPassed = true,
            level3AppStatePassed = true,
            evidenceSummary = "Tap dispatched and screen changed",
        )

        val decision = JarvisRecoveryManager.diagnoseAndPlanRecovery(
            targetLabel = "Send message",
            attemptNumber = 2,
            maxAttempts = 3,
            screenBefore = screen,
            screenAfter = screen,
            lastReport = report,
        )

        assertEquals(RecoveryActionType.ABORT_CONSEQUENTIAL_DUPLICATE, decision.action)
        assertFalse(decision.safeToRetryTarget)
    }

    @Test
    fun testContextMemoryResolvesAnaphoricReferences() {
        val memory = InteractionContextMemory()
        memory.recordAction(
            actionType = HumanInteractionType.SINGLE_TAP,
            targetLabel = "Search YouTube",
        )

        val resolved = memory.resolveTargetReference("open that")
        assertEquals("Search YouTube", resolved)
    }

    @Test
    fun testAppRegistryResolvesKnownAppsAndDeepLinks() {
        val yt = JarvisAppRegistry.findBySpokenName("youtube")
        assertNotNull(yt)
        assertEquals("com.google.android.youtube", yt!!.packageNames.first())
        assertTrue(yt.supportsDeepLinkSearch)

        val wa = JarvisAppRegistry.findByPackage("com.whatsapp")
        assertNotNull(wa)
        assertEquals("WhatsApp", wa!!.canonicalName)
    }

    private fun sampleFingerprint(
        packageName: String = "com.google.android.youtube",
        nodeCount: Int = 20,
        treeSignatureHash: Int = 12345,
        checkedStatesHash: Int = 0,
        editableTexts: List<String> = emptyList(),
        isAudioPlaying: Boolean = false,
        telemetry: AccessibilityEventTelemetry = AccessibilityEventTelemetry(
            sequence = 1,
            lastClickSeq = 0,
            lastWindowChangeSeq = 0,
            lastContentChangeSeq = 0,
            lastScrollSeq = 0,
            lastPackageName = packageName,
        ),
    ): UiStateFingerprint = UiStateFingerprint(
        packageName = packageName,
        nodeCount = nodeCount,
        treeSignatureHash = treeSignatureHash,
        checkedStatesHash = checkedStatesHash,
        editableTexts = editableTexts,
        isAudioPlaying = isAudioPlaying,
        telemetry = telemetry,
    )

    @Test
    fun testSuccessfulGestureWithoutUiOrAppChangeIsUnknownNotSuccess() {
        val fp1 = sampleFingerprint()
        val fp2 = sampleFingerprint() // identical: no UI delta
        val report = JarvisActionVerifier.verifyTouchOrAction(
            interactionType = HumanInteractionType.SINGLE_TAP,
            dispatched = true,
            before = fp1,
            after = fp2,
            expectedPackage = "com.google.android.youtube",
        )
        // Zero fake success: gesture passed L1 dispatch, but L2 and L3 saw zero change
        assertEquals(VerificationStatus.UNVERIFIED_UNKNOWN, report.status)
        assertTrue(report.level1InteractionPassed)
        assertFalse(report.level2UiDeltaPassed)
    }

    @Test
    fun testFailedGestureDispatchProducesFailedStatus() {
        val fp1 = sampleFingerprint()
        val fp2 = sampleFingerprint()
        val report = JarvisActionVerifier.verifyTouchOrAction(
            interactionType = HumanInteractionType.SINGLE_TAP,
            dispatched = false,
            before = fp1,
            after = fp2,
        )
        assertEquals(VerificationStatus.FAILED, report.status)
        assertFalse(report.level1InteractionPassed)
    }

    @Test
    fun testLevel4SystemApiVerificationOverridesUiDelta() {
        val fp1 = sampleFingerprint()
        val fp2 = sampleFingerprint() // no UI change
        val report = JarvisActionVerifier.verifyTouchOrAction(
            interactionType = HumanInteractionType.SINGLE_TAP,
            dispatched = true,
            before = fp1,
            after = fp2,
            systemApiCheck = { true }, // e.g. AudioManager.isMusicActive or BluetoothManager.isEnabled
        )
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, report.status)
        assertEquals(VerificationLevel.LEVEL_4_SYSTEM_API, report.highestLevelPassed)
        assertTrue(report.level4SystemApiPassed == true)
    }

    @Test
    fun testRecoveryDiagnosesKeyboardOcclusion() {
        val screen = sampleScreen(
            elements = emptyList(),
            keyboardVisible = true,
            keyboardTopY = 1200,
        )
        val report = ActionVerificationReport(
            status = VerificationStatus.UNVERIFIED_UNKNOWN,
            highestLevelPassed = VerificationLevel.LEVEL_1_INTERACTION,
            level1InteractionPassed = true,
            level2UiDeltaPassed = false,
            level3AppStatePassed = false,
            evidenceSummary = "No delta",
        )
        val decision = JarvisRecoveryManager.diagnoseAndPlanRecovery(
            targetLabel = "Submit",
            attemptNumber = 1,
            maxAttempts = 3,
            screenBefore = screen,
            screenAfter = screen,
            lastReport = report,
        )
        assertEquals(RecoveryActionType.HIDE_KEYBOARD, decision.action)
    }

    @Test
    fun testRecoveryDiagnosesBlockingDialog() {
        val dismissBtn = sampleElement(index = 99, text = "Not Now", clickable = true)
        val screen = sampleScreen(
            elements = listOf(dismissBtn),
            dialogTitle = "Google Play Services Update",
            dismissCandidate = dismissBtn,
        )
        val report = ActionVerificationReport(
            status = VerificationStatus.UNVERIFIED_UNKNOWN,
            highestLevelPassed = VerificationLevel.LEVEL_1_INTERACTION,
            level1InteractionPassed = true,
            level2UiDeltaPassed = false,
            level3AppStatePassed = false,
            evidenceSummary = "Blocked by modal",
        )
        val decision = JarvisRecoveryManager.diagnoseAndPlanRecovery(
            targetLabel = "Search YouTube",
            attemptNumber = 1,
            maxAttempts = 3,
            screenBefore = screen,
            screenAfter = screen,
            lastReport = report,
        )
        assertEquals(RecoveryActionType.DISMISS_BLOCKING_DIALOG, decision.action)
    }

    @Test
    fun testMissingUiElementReturnsNullMatch() {
        val screen = sampleScreen(elements = emptyList())
        val res = JarvisUiElementMatcher.resolve(
            screen = screen,
            query = ElementMatchQuery(rawTarget = "Transfer Funds"),
        )
        assertEquals(null, res.best)
    }

    @Test
    fun testHighConsequenceAmbiguityTriggersUserPrompt() {
        val btn1 = sampleElement(
            index = 1,
            text = "Delete Chat",
            boundsLeft = 100,
            boundsTop = 200,
            boundsRight = 400,
            boundsBottom = 260,
        )
        val btn2 = sampleElement(
            index = 2,
            text = "Delete Account",
            boundsLeft = 100,
            boundsTop = 400,
            boundsRight = 400,
            boundsBottom = 460,
        )
        val screen = sampleScreen(elements = listOf(btn1, btn2))
        val res = JarvisUiElementMatcher.resolve(
            screen = screen,
            query = ElementMatchQuery(rawTarget = "delete"),
        )
        assertTrue(res.ambiguityDetected)
        assertNotNull(res.ambiguityMessage)
    }
}
