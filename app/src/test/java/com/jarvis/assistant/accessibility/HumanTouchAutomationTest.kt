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
}
