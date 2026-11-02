package com.jarvis.assistant.accessibility

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualIntelligenceAndAutonomousPlanTest {

    private fun sampleElement(
        index: Int,
        text: String = "",
        contentDescription: String = "",
        boundsLeft: Int = 100,
        boundsTop: Int = 200,
        boundsRight: Int = 300,
        boundsBottom: Int = 320,
    ): ObservedUiElement = ObservedUiElement(
        index = index,
        resourceId = "test_view",
        text = text,
        contentDescription = contentDescription,
        hintText = "",
        className = "android.widget.Button",
        packageName = "com.google.android.youtube",
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
        bounds = Rect(boundsLeft, boundsTop, boundsRight, boundsBottom),
        boundsLeft = boundsLeft,
        boundsTop = boundsTop,
        boundsRight = boundsRight,
        boundsBottom = boundsBottom,
    )

    private fun sampleScreen(
        packageName: String = "com.google.android.youtube",
        elements: List<ObservedUiElement> = emptyList(),
        dialogTitle: String? = null,
        dismissCandidate: ObservedUiElement? = null,
        keyboardVisible: Boolean = false,
        keyboardTopY: Int = 2340,
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

    // 1. Hybrid Visual + Accessibility Reconciliation -------------------------

    @Test
    fun testSecureBlackoutBlocksAutomationWithExplanation() {
        val visual = VisualEvidence(
            width = 1080,
            height = 2340,
            isSecureBlackout = true, // FLAG_SECURE blacked out
        )
        val screen = sampleScreen()
        val query = ElementMatchQuery(rawTarget = "Enter PIN")

        val result = VisualAccessibilityReconciler.reconcile(
            query = query,
            matchedElement = null,
            visual = visual,
            screen = screen,
        )

        assertEquals(ReconciliationStatus.SECURE_SCREEN_BLOCKED, result.status)
        assertFalse(result.safeToInteract)
        assertTrue(result.explanation.contains("FLAG_SECURE"))
    }

    @Test
    fun testModalDialogDetectsOcclusionAndSuggestsDismissal() {
        val dismissBtn = sampleElement(index = 99, text = "Cancel")
        val targetBtn = sampleElement(index = 1, text = "Play Next")
        val screen = sampleScreen(
            elements = listOf(dismissBtn, targetBtn),
            dialogTitle = "Terms of Service",
            dismissCandidate = dismissBtn,
        )
        val visual = VisualEvidence(width = 1080, height = 2340, hasModalScrim = true)
        val query = ElementMatchQuery(rawTarget = "Play Next")

        val result = VisualAccessibilityReconciler.reconcile(
            query = query,
            matchedElement = targetBtn,
            visual = visual,
            screen = screen,
        )

        assertEquals(ReconciliationStatus.MODAL_OCCLUDED, result.status)
        assertFalse(result.safeToInteract)
        assertEquals("DISMISS_DIALOG", result.suggestedAction)
    }

    @Test
    fun testKeyboardOcclusionDetectedByReconciler() {
        val buttonUnderKeyboard = sampleElement(index = 1, text = "Submit", boundsTop = 1800, boundsBottom = 1950)
        val screen = sampleScreen(
            elements = listOf(buttonUnderKeyboard),
            keyboardVisible = true,
            keyboardTopY = 1400, // Keyboard sits above the button
        )
        val visual = VisualEvidence(width = 1080, height = 2340)
        val query = ElementMatchQuery(rawTarget = "Submit")

        val result = VisualAccessibilityReconciler.reconcile(
            query = query,
            matchedElement = buttonUnderKeyboard,
            visual = visual,
            screen = screen,
        )

        assertEquals(ReconciliationStatus.MODAL_OCCLUDED, result.status)
        assertFalse(result.safeToInteract)
        assertEquals("HIDE_KEYBOARD", result.suggestedAction)
    }

    @Test
    fun testConsistentEvidenceAllowsSafeInteraction() {
        val target = sampleElement(index = 1, text = "Search YouTube", boundsTop = 200, boundsBottom = 300)
        val screen = sampleScreen(elements = listOf(target))
        val visual = VisualEvidence(width = 1080, height = 2340)
        val query = ElementMatchQuery(rawTarget = "Search YouTube")

        val result = VisualAccessibilityReconciler.reconcile(
            query = query,
            matchedElement = target,
            visual = visual,
            screen = screen,
        )

        assertEquals(ReconciliationStatus.CONSISTENT, result.status)
        assertTrue(result.safeToInteract)
        assertEquals(target, result.resolvedElement)
    }

    @Test
    fun testVisualOcrSynthesizesElementWhenAccessibilityMissing() {
        val ocrRegion = VisualTextRegion(
            text = "Trending Now",
            bounds = Rect(120, 450, 420, 520),
        )
        val visual = VisualEvidence(
            width = 1080,
            height = 2340,
            extractedRegions = listOf(ocrRegion),
        )
        val screen = sampleScreen(elements = emptyList()) // Accessibility tree empty
        val query = ElementMatchQuery(rawTarget = "Trending")

        val result = VisualAccessibilityReconciler.reconcile(
            query = query,
            matchedElement = null,
            visual = visual,
            screen = screen,
        )

        assertEquals(ReconciliationStatus.CONSISTENT, result.status)
        assertTrue(result.safeToInteract)
        assertNotNull(result.resolvedElement)
        assertEquals("visual_ocr", result.resolvedElement!!.resourceId)
        assertEquals("Trending Now", result.resolvedElement!!.text)
    }

    // 2. Dynamic Screen Reasoning & Stale Coordinate Rejection -----------------

    @Test
    fun testDynamicScreenReasonerDetectsStateChangeAndIncrementsVersion() {
        val reasoner = DynamicScreenReasoner()
        val initialVersion = reasoner.currentScreenVersion

        val fp1 = UiStateFingerprint(
            packageName = "com.google.android.youtube",
            nodeCount = 15,
            treeSignatureHash = 111,
            checkedStatesHash = 0,
        )
        reasoner.onNewScreenObserved(fp1)

        val fp2 = UiStateFingerprint(
            packageName = "com.google.android.youtube",
            nodeCount = 28, // List loaded
            treeSignatureHash = 999,
            checkedStatesHash = 0,
        )
        val changed = reasoner.onNewScreenObserved(fp2)

        assertTrue(changed)
        assertTrue(reasoner.currentScreenVersion > initialVersion)
    }

    @Test
    fun testStaleCoordinateRejectedAfterScreenTransition() {
        val reasoner = DynamicScreenReasoner()
        val coord = reasoner.tagCoordinate(540f, 960f, "Video Card")

        assertFalse(coord.isStale(reasoner.currentScreenVersion))

        // Screen changes: new version
        val fpNew = UiStateFingerprint(
            packageName = "com.google.android.youtube",
            nodeCount = 50,
            treeSignatureHash = 54321,
            checkedStatesHash = 0,
        )
        reasoner.onNewScreenObserved(fpNew)

        // Coordinate tagged with old version is now rejected as STALE
        assertTrue(coord.isStale(reasoner.currentScreenVersion))
    }

    // 3. Intelligent Multi-Step Planning --------------------------------------

    @Test
    fun testAutonomousTaskPlannerBuildsTenStepYouTubePlan() {
        val plan = AutonomousTaskPlanner.planFromObjective("Open YouTube, search for Android app development tutorials and play the first video")

        assertEquals("com.google.android.youtube", plan.targetApp)
        assertEquals(10, plan.steps.size)

        assertEquals("Launch YouTube application", plan.steps[0].title)
        assertEquals("Enter search query 'Android app development tutorials'", plan.steps[4].title)
        assertEquals("Android app development tutorials", plan.steps[4].payloadText)
        assertEquals("Identify the first video result card", plan.steps[7].title)
        assertEquals("Verify video playback started", plan.steps[9].title)
    }

    @Test
    fun testAutonomousTaskPlannerBuildsWhatsAppPlanWithConsequentialFlag() {
        val plan = AutonomousTaskPlanner.planFromObjective("Open WhatsApp, send message to Azhar saying I am arriving in ten minutes")

        assertEquals("com.whatsapp", plan.targetApp)
        assertTrue(plan.steps.size >= 4)

        val sendStep = plan.steps.last()
        assertTrue(sendStep.isConsequential)
        assertEquals("Send", sendStep.targetQuery)
    }

    // 4. Human Takeover & Cancellation ----------------------------------------

    @Test
    fun testHumanTakeoverHaltsActiveAutomation() {
        HumanTakeoverManager.setAutomationRunning(true)
        assertFalse(HumanTakeoverManager.shouldHalt)

        // User manually touches screen
        HumanTakeoverManager.onUserManualTouch()

        assertTrue(HumanTakeoverManager.shouldHalt)
        assertTrue(HumanTakeoverManager.canResume)
    }

    // 5. Task State Persistence & Resumption ----------------------------------

    @Test
    fun testTaskResumptionEngineSkipsCompletedStepsAndProtectsConsequentialSteps() {
        val plan = AutonomousTaskPlanner.planFromObjective("Open WhatsApp, send message to Azhar saying hello")
        plan.steps[0].status = StepExecutionStatus.VERIFIED_COMPLETED
        plan.steps[1].status = StepExecutionStatus.VERIFIED_COMPLETED
        plan.activeStepIndex = 2

        val currentScreen = sampleScreen(packageName = "com.whatsapp")
        val reconciled = TaskResumptionEngine.reconcileForResumption(plan, currentScreen)

        assertEquals(2, reconciled.activeStepIndex)
        assertFalse(reconciled.isPaused)
    }

    // 6. Smart App Adapters ---------------------------------------------------

    @Test
    fun testSmartAppAdaptersSupportElevenCoreApplications() {
        val adapters = SmartAppAdapterRegistry.allAdapters
        assertEquals(11, adapters.size)

        val packages = adapters.map { it.packageName }.toSet()
        assertTrue(packages.contains("com.whatsapp"))
        assertTrue(packages.contains("com.google.android.youtube"))
        assertTrue(packages.contains("com.instagram.android"))
        assertTrue(packages.contains("com.android.chrome"))
        assertTrue(packages.contains("com.google.android.gm"))
        assertTrue(packages.contains("com.google.android.apps.maps"))
        assertTrue(packages.contains("com.spotify.music"))
        assertTrue(packages.contains("com.android.settings"))
        assertTrue(packages.contains("com.samsung.android.dialer"))
        assertTrue(packages.contains("com.samsung.android.app.contacts"))
        assertTrue(packages.contains("com.samsung.android.messaging"))

        val wa = SmartAppAdapterRegistry.getAdapter("com.whatsapp")
        assertNotNull(wa)
        assertTrue(wa!!.supportedWorkflows.contains("send_voice_note"))

        val yt = SmartAppAdapterRegistry.getAdapter("com.google.android.youtube")
        assertNotNull(yt)
        assertTrue(yt!!.supportedWorkflows.contains("search_and_play_video"))
    }
}
