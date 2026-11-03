package com.jarvis.assistant.accessibility

import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive JVM Unit Test Suite for the OpenClaw-Inspired Mobile UI
 * Intelligence Upgrade (Phases 2–8).
 *
 * Tests all 14 mandatory reliability and safety contracts:
 * 1. Stale snapshots (TARGET_STALE)
 * 2. Package changes (TARGET_STALE)
 * 3. Missing elements (TARGET_NOT_FOUND — zero arbitrary coordinate fallback)
 * 4. Disabled elements (FAILED)
 * 5. UI transitions (VERIFIED_SUCCESS + ScreenSnapshotDiff)
 * 6. Keyboard appearance & occlusion
 * 7. Dialogs & higher-layer window occlusion
 * 8. Scroll containers
 * 9. Secure fields & password protection (SECURE_CONTENT + VisionPrivacyGate)
 * 10. Failed gestures (FAILED)
 * 11. User interruption / human takeover
 * 12. Action timeouts / bounded Wait
 * 13. Duplicate consequential actions & fail-closed approval gate
 * 14. False success prevention (ACCEPTED_BUT_UNVERIFIED when dispatch=true but UI unchanged)
 */
class OpenClawMobileUiEngineTest {

    @Before
    fun setUp() {
        HumanTakeoverManager.setAutomationRunning(true)
    }

    @After
    fun tearDown() {
        HumanTakeoverManager.setAutomationRunning(false)
    }

    private fun element(
        index: Int,
        text: String = "",
        contentDesc: String = "",
        resourceId: String = "",
        clickable: Boolean = true,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        password: Boolean = false,
        left: Int = 100,
        top: Int = 200,
        right: Int = 400,
        bottom: Int = 320,
        windowId: Int = 1,
        windowLayer: Int = 1,
        parentRef: String? = null,
    ): ObservedUiElement = ObservedUiElement(
        index = index,
        resourceId = resourceId,
        text = text,
        contentDescription = contentDesc,
        hintText = "",
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        packageName = "com.google.android.youtube",
        clickable = clickable,
        longClickable = clickable,
        editable = editable,
        enabled = enabled,
        checkable = false,
        checked = false,
        selected = false,
        scrollable = scrollable,
        focusable = true,
        focused = false,
        visibleToUser = true,
        bounds = Rect(left, top, right, bottom),
        boundsLeft = left,
        boundsTop = top,
        boundsRight = right,
        boundsBottom = bottom,
        elementRef = "ref_e$index",
        parentRef = parentRef,
        windowId = windowId,
        windowLayer = windowLayer,
        password = password,
    )

    private fun snapshot(
        snapshotId: String = "snap_1",
        packageName: String = "com.google.android.youtube",
        windowTitle: String = "YouTube",
        elements: List<ObservedUiElement> = emptyList(),
        keyboardVisible: Boolean = false,
        keyboardTopY: Int = 2340,
        activeDialogTitle: String? = null,
        dismissCandidate: ObservedUiElement? = null,
        windows: List<ObservedWindowInfo> = emptyList(),
        isSecureOrProtected: Boolean = false,
    ): ObservedScreenState = ObservedScreenState(
        packageName = packageName,
        appLabel = "YouTube",
        windowTitle = windowTitle,
        screenWidth = 1080,
        screenHeight = 2340,
        rotation = 0,
        keyboardVisible = keyboardVisible,
        keyboardTopY = keyboardTopY,
        activeDialogTitle = activeDialogTitle,
        dismissCandidate = dismissCandidate,
        elements = elements,
        snapshotId = snapshotId,
        windows = windows,
        isSecureOrProtected = isSecureOrProtected,
    )

    private class FakeUiDispatchBackend(
        var beforeState: ObservedScreenState?,
        var afterState: ObservedScreenState? = beforeState,
        var dispatchResult: Boolean = true,
        var visualBlackout: Boolean = false,
        var onActionHook: (() -> Unit)? = null,
    ) : UiDispatchBackend {
        private var observeCalls = 0
        var rawCoordinateTapCalled = false

        override suspend fun observeScreen(forceRefresh: Boolean): ObservedScreenState? {
            observeCalls++
            return if (observeCalls == 1) beforeState else afterState
        }

        override suspend fun performClick(element: ObservedUiElement): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun performSetText(element: ObservedUiElement, text: String, clearFirst: Boolean): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun performScroll(container: ObservedUiElement?, direction: String, distance: String): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean {
            rawCoordinateTapCalled = true
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun dispatchLongPress(x: Float, y: Float, durationMs: Long): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun dispatchDoubleTap(x: Float, y: Float): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun dispatchDrag(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun dispatchPinchZoom(centerX: Float, centerY: Float, zoomIn: Boolean, spanPx: Float, durationMs: Long): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override suspend fun performGlobalNavigation(action: String): Boolean {
            onActionHook?.invoke()
            return dispatchResult
        }

        override fun isVisualSecureBlackout(): Boolean = visualBlackout
    }

    // 1. Stale snapshots ------------------------------------------------------

    @Test
    fun testStaleSnapshotIdIsRejectedWithTargetStale() = runBlocking {
        val currentScreen = snapshot(
            snapshotId = "snap_live_2",
            elements = listOf(element(1, text = "Search")),
        )
        val backend = FakeUiDispatchBackend(beforeState = currentScreen)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_old_1", // Stale snapshot ID!
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.TARGET_STALE, result.outcome)
        assertFalse(result.isVerifiedSuccess)
        assertTrue(result.message.contains("Stale snapshot reference"))
    }

    // 2. Package changes ------------------------------------------------------

    @Test
    fun testPackageMismatchIsRejectedWithTargetStale() = runBlocking {
        val currentScreen = snapshot(
            snapshotId = "snap_1",
            packageName = "com.whatsapp", // Switched to WhatsApp unexpectedly
            elements = listOf(element(1, text = "Search")),
        )
        val backend = FakeUiDispatchBackend(beforeState = currentScreen)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
                expectedPackage = "com.google.android.youtube",
            ),
        )

        assertEquals(UiActionOutcomeState.TARGET_STALE, result.outcome)
        assertTrue(result.message.contains("Package mismatch"))
    }

    // 3. Missing elements (Never silently replace with arbitrary coordinate) ---

    @Test
    fun testMissingSemanticElementReturnsTargetNotFoundWithoutArbitraryCoordinateTap() = runBlocking {
        val currentScreen = snapshot(
            snapshotId = "snap_1",
            elements = listOf(element(1, text = "Home"), element(2, text = "Subscriptions")),
        )
        val backend = FakeUiDispatchBackend(beforeState = currentScreen)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                semanticQuery = "NonExistentButton",
            ),
        )

        assertEquals(UiActionOutcomeState.TARGET_NOT_FOUND, result.outcome)
        assertFalse(backend.rawCoordinateTapCalled)
        assertFalse(result.isVerifiedSuccess)
    }

    // 4. Disabled elements ----------------------------------------------------

    @Test
    fun testDisabledElementReturnsFailed() = runBlocking {
        val disabledBtn = element(1, text = "Continue", enabled = false)
        val currentScreen = snapshot(snapshotId = "snap_1", elements = listOf(disabledBtn))
        val backend = FakeUiDispatchBackend(beforeState = currentScreen)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.FAILED, result.outcome)
        assertTrue(result.message.contains("disabled"))
    }

    // 5. UI transitions -------------------------------------------------------

    @Test
    fun testVerifiedUiTransitionReturnsVerifiedSuccessAndDiff() = runBlocking {
        val before = snapshot(
            snapshotId = "snap_1",
            windowTitle = "Home",
            elements = listOf(element(1, text = "Search YouTube", resourceId = "id/search")),
        )
        val after = snapshot(
            snapshotId = "snap_2",
            windowTitle = "Search",
            keyboardVisible = true,
            keyboardTopY = 1400,
            elements = listOf(element(1, text = "", editable = true, resourceId = "id/search_edit_text")),
        )
        val backend = FakeUiDispatchBackend(beforeState = before, afterState = after, dispatchResult = true)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.VERIFIED_SUCCESS, result.outcome)
        assertTrue(result.isVerifiedSuccess)
        assertNotNull(result.diff)
        assertTrue(result.diff!!.windowTitleChanged)
        assertTrue(result.diff!!.keyboardVisibilityChanged)
        assertEquals("snap_2", result.afterSnapshotId)
    }

    // 6. Keyboard appearance & occlusion --------------------------------------

    @Test
    fun testKeyboardOccludedElementIsRejectedBeforeBlindTap() = runBlocking {
        val occludedBtn = element(
            index = 1,
            text = "Save Settings",
            top = 1850,
            bottom = 2000, // Center Y = 1925, below keyboardTopY (1400)
        )
        val screenWithKeyboard = snapshot(
            snapshotId = "snap_1",
            elements = listOf(occludedBtn),
            keyboardVisible = true,
            keyboardTopY = 1400,
        )
        val backend = FakeUiDispatchBackend(beforeState = screenWithKeyboard)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.FAILED, result.outcome)
        assertTrue(result.message.contains("covered by the visible keyboard"))
    }

    // 7. Dialogs & higher-layer window occlusion ------------------------------

    @Test
    fun testBlockingDialogAndHigherWindowLayerPreventBackgroundTap() = runBlocking {
        val bgElement = element(1, text = "Play Video", top = 500, bottom = 700, windowId = 1, windowLayer = 1)
        val dismissBtn = element(2, text = "Dismiss", top = 1100, bottom = 1200, windowId = 2, windowLayer = 3)
        val overlayWindow = ObservedWindowInfo(
            windowId = 2,
            windowType = "SYSTEM",
            title = "System Alert",
            packageName = "android",
            boundsLeft = 50,
            boundsTop = 400,
            boundsRight = 1030,
            boundsBottom = 1300,
            isActive = true,
            isFocused = true,
            layer = 3,
        )
        val screenWithOverlay = snapshot(
            snapshotId = "snap_1",
            elements = listOf(bgElement, dismissBtn),
            windows = listOf(overlayWindow),
        )
        val backend = FakeUiDispatchBackend(beforeState = screenWithOverlay)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.FAILED, result.outcome)
        assertTrue(result.message.contains("occluded by a higher-layer"))
    }

    // 8. Scroll containers ----------------------------------------------------

    @Test
    fun testScrollContainerShiftsElementsAndVerifiesSuccess() = runBlocking {
        val before = snapshot(
            snapshotId = "snap_1",
            elements = listOf(
                element(1, text = "Feed List", scrollable = true, top = 100, bottom = 2000),
                element(2, text = "Video Item 1", top = 800, bottom = 1000, resourceId = "id/video_1"),
            ),
        )
        val after = snapshot(
            snapshotId = "snap_2",
            elements = listOf(
                element(1, text = "Feed List", scrollable = true, top = 100, bottom = 2000),
                element(2, text = "Video Item 1", top = 300, bottom = 500, resourceId = "id/video_1"), // Shifted up by 500px
            ),
        )
        val backend = FakeUiDispatchBackend(beforeState = before, afterState = after, dispatchResult = true)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.Scroll(
                snapshotId = "snap_1",
                containerRef = "ref_e1",
                direction = "down",
            ),
        )

        assertEquals(UiActionOutcomeState.VERIFIED_SUCCESS, result.outcome)
        assertTrue(result.diff!!.boundsShiftedCount > 0)
    }

    // 9. Secure fields & password protection ----------------------------------

    @Test
    fun testPasswordFieldAndSecureScreenAreBlockedWithSecureContent() = runBlocking {
        val passwordInput = element(1, text = "", editable = true, password = true, resourceId = "id/password")
        val loginScreen = snapshot(snapshotId = "snap_1", elements = listOf(passwordInput))
        val backend = FakeUiDispatchBackend(beforeState = loginScreen)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.EnterText(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
                text = "hunter2",
            ),
        )

        assertEquals(UiActionOutcomeState.SECURE_CONTENT, result.outcome)
        assertEquals("[REDACTED_PASSWORD_FIELD]", passwordInput.primaryLabel)

        // Also verify VisionPrivacyGate blocks external screenshot transmission when password field is visible
        val visionDecision = VisionPrivacyGate.evaluateExternalVisionRequest(
            userConsentedToVision = true,
            modelSupportsVision = true,
            screen = loginScreen,
        )
        assertFalse(visionDecision.allowed)
        assertTrue(visionDecision.reason.contains("password"))
    }

    // 10. Failed gestures -----------------------------------------------------

    @Test
    fun testRejectedGestureDispatchReturnsFailedState() = runBlocking {
        val before = snapshot(
            snapshotId = "snap_1",
            elements = listOf(element(1, text = "Refresh")),
        )
        val backend = FakeUiDispatchBackend(beforeState = before, dispatchResult = false)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.FAILED, result.outcome)
        assertFalse(result.isVerifiedSuccess)
    }

    // 11. User interruption / takeover ----------------------------------------

    @Test
    fun testUserTouchTakeoverImmediatelyHaltsAction() = runBlocking {
        val before = snapshot(
            snapshotId = "snap_1",
            elements = listOf(element(1, text = "Next Track")),
        )
        val backend = FakeUiDispatchBackend(
            beforeState = before,
            onActionHook = {
                // Human touches screen mid-gesture
                HumanTakeoverManager.onUserManualTouch()
            },
        )
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.FAILED, result.outcome)
        assertTrue(result.message.contains("interrupted by owner touch"))
    }

    // 12. Action timeouts / Wait contract -------------------------------------

    @Test
    fun testBoundedWaitReobservesScreenAfterDuration() = runBlocking {
        val before = snapshot(snapshotId = "snap_1", elements = emptyList())
        val after = snapshot(snapshotId = "snap_2", elements = listOf(element(1, text = "Loaded Content")))
        val backend = FakeUiDispatchBackend(beforeState = before, afterState = after)
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.Wait(
                durationMs = 60L,
                snapshotId = "snap_1",
            ),
        )

        assertEquals(UiActionOutcomeState.VERIFIED_SUCCESS, result.outcome)
        assertEquals("snap_2", result.afterSnapshotId)
        assertTrue(result.diff!!.addedElementRefs.contains("ref_e1"))
    }

    // 13. Duplicate consequential actions & fail-closed approval --------------

    @Test
    fun testFailClosedPolicyRequiresApprovalAndBlocksDuplicateConsequentialAction() = runBlocking {
        val sendBtn = element(1, text = "Send", resourceId = "com.whatsapp:id/send")
        val before = snapshot(
            snapshotId = "snap_1",
            packageName = "com.whatsapp",
            elements = listOf(sendBtn),
        )
        val after = snapshot(
            snapshotId = "snap_2",
            packageName = "com.whatsapp",
            elements = listOf(sendBtn, element(2, text = "Message sent")),
        )

        // First test without approval -> fail-closed blocks it
        val unapprovedExecutor = MobileUiActionExecutor(
            backend = FakeUiDispatchBackend(beforeState = before, afterState = after),
            settleDelayMs = 0L,
            approvalGate = { _, _ -> false },
        )
        val deniedResult = unapprovedExecutor.execute(
            MobileUiActionRequest.ActivateElement(snapshotId = "snap_1", elementRef = "ref_e1"),
        )
        assertEquals(UiActionOutcomeState.FAILED, deniedResult.outcome)
        assertEquals(ConsequentialActionCategory.SEND_MESSAGE, deniedResult.consequentialCategory)

        // Now test with approval -> first succeeds, duplicate second attempt is blocked!
        val approvedBackend = FakeUiDispatchBackend(beforeState = before, afterState = after)
        val approvedExecutor = MobileUiActionExecutor(
            backend = approvedBackend,
            settleDelayMs = 0L,
            approvalGate = { _, _ -> true },
        )
        val firstResult = approvedExecutor.execute(
            MobileUiActionRequest.ActivateElement(snapshotId = "snap_1", elementRef = "ref_e1"),
        )
        assertEquals(UiActionOutcomeState.VERIFIED_SUCCESS, firstResult.outcome)

        // Try duplicate send on snap_2 -> blocked by anti-duplication guard
        val duplicateCheck = approvedExecutor.execute(
            MobileUiActionRequest.ActivateElement(snapshotId = "snap_2", elementRef = "ref_e1"),
        )
        assertEquals(UiActionOutcomeState.FAILED, duplicateCheck.outcome)
        assertTrue(duplicateCheck.message.contains("Blocked duplicate consequential action"))
    }

    // 14. False success prevention --------------------------------------------

    @Test
    fun testGestureCallbackTrueWithZeroScreenChangeReturnsAcceptedButUnverifiedNeverSuccess() = runBlocking {
        val staticScreen = snapshot(
            snapshotId = "snap_1",
            elements = listOf(element(1, text = "Play Video")),
        )
        val identicalAfterScreen = snapshot(
            snapshotId = "snap_2",
            elements = listOf(element(1, text = "Play Video")), // Zero change!
        )
        val backend = FakeUiDispatchBackend(
            beforeState = staticScreen,
            afterState = identicalAfterScreen,
            dispatchResult = true, // OS GestureResultCallback.onCompleted returned true
        )
        val executor = MobileUiActionExecutor(backend = backend, settleDelayMs = 0L)

        val result = executor.execute(
            MobileUiActionRequest.ActivateElement(
                snapshotId = "snap_1",
                elementRef = "ref_e1",
            ),
        )

        assertEquals(UiActionOutcomeState.ACCEPTED_BUT_UNVERIFIED, result.outcome)
        assertFalse(result.isVerifiedSuccess)
        assertFalse(result.diff!!.hasMeaningfulChange)
    }
}
