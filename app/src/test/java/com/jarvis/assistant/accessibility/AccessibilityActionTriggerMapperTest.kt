package com.jarvis.assistant.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityActionTriggerMapperTest {

    @Test
    fun testMapActionIdToTrigger() {
        // Test standard Accessibility action ID mapping
        val clickTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_CLICK)
        assertTrue(clickTrigger is ProgrammaticGestureTrigger.Tap)
        assertEquals("tap", clickTrigger.identifier)

        val longClickTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(
            AccessibilityNodeInfo.ACTION_LONG_CLICK,
            TriggerOptions(durationMs = 800L),
        )
        assertTrue(longClickTrigger is ProgrammaticGestureTrigger.TouchAndHold)
        assertEquals(800L, (longClickTrigger as ProgrammaticGestureTrigger.TouchAndHold).holdDurationMs)

        val scrollForwardTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        assertTrue(scrollForwardTrigger is ProgrammaticGestureTrigger.Scroll)
        assertEquals(GestureDirection.DOWN, (scrollForwardTrigger as ProgrammaticGestureTrigger.Scroll).direction)

        val scrollBackwardTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        assertTrue(scrollBackwardTrigger is ProgrammaticGestureTrigger.Scroll)
        assertEquals(GestureDirection.UP, (scrollBackwardTrigger as ProgrammaticGestureTrigger.Scroll).direction)

        val setTextTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            TriggerOptions(textArgument = "Lofi Hip Hop"),
        )
        assertTrue(setTextTrigger is ProgrammaticGestureTrigger.TypeText)
        assertEquals("Lofi Hip Hop", (setTextTrigger as ProgrammaticGestureTrigger.TypeText).text)

        val dismissTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_DISMISS)
        assertTrue(dismissTrigger is ProgrammaticGestureTrigger.Dismiss)

        val expandTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_EXPAND)
        assertTrue(expandTrigger is ProgrammaticGestureTrigger.Expand)

        val collapseTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_COLLAPSE)
        assertTrue(collapseTrigger is ProgrammaticGestureTrigger.Collapse)

        val selectTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_SELECT)
        assertTrue(selectTrigger is ProgrammaticGestureTrigger.Select)

        val clearSelectionTrigger = AccessibilityActionTriggerMapper.mapActionIdToTrigger(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)
        assertTrue(clearSelectionTrigger is ProgrammaticGestureTrigger.ClearSelection)
    }

    @Test
    fun testMapSemanticActionToTrigger() {
        // Tap variations
        assertTrue(AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("tap") is ProgrammaticGestureTrigger.Tap)
        assertTrue(AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("click") is ProgrammaticGestureTrigger.Tap)
        assertTrue(AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("press") is ProgrammaticGestureTrigger.Tap)

        // Touch & Hold / Long press variations
        val touchTrigger = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("touch_and_hold", TriggerOptions(durationMs = 1000L))
        assertTrue(touchTrigger is ProgrammaticGestureTrigger.TouchAndHold)
        assertEquals(1000L, (touchTrigger as ProgrammaticGestureTrigger.TouchAndHold).holdDurationMs)

        val longPressTrigger = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("long_press")
        assertTrue(longPressTrigger is ProgrammaticGestureTrigger.TouchAndHold)

        // Double tap
        assertTrue(AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("double_tap") is ProgrammaticGestureTrigger.DoubleTap)

        // Directional Swipes
        val swipeLeft = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("swipe_left")
        assertTrue(swipeLeft is ProgrammaticGestureTrigger.Swipe)
        assertEquals(GestureDirection.LEFT, (swipeLeft as ProgrammaticGestureTrigger.Swipe).direction)

        val swipeRight = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("swipe_right")
        assertTrue(swipeRight is ProgrammaticGestureTrigger.Swipe)
        assertEquals(GestureDirection.RIGHT, (swipeRight as ProgrammaticGestureTrigger.Swipe).direction)

        val swipeUp = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("swipe_up")
        assertTrue(swipeUp is ProgrammaticGestureTrigger.Swipe)
        assertEquals(GestureDirection.UP, (swipeUp as ProgrammaticGestureTrigger.Swipe).direction)

        val swipeDown = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("swipe_down")
        assertTrue(swipeDown is ProgrammaticGestureTrigger.Swipe)
        assertEquals(GestureDirection.DOWN, (swipeDown as ProgrammaticGestureTrigger.Swipe).direction)

        // Directional Scrolls
        val scrollDown = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("scroll_down")
        assertTrue(scrollDown is ProgrammaticGestureTrigger.Scroll)
        assertEquals(GestureDirection.DOWN, (scrollDown as ProgrammaticGestureTrigger.Scroll).direction)

        val scrollUp = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("scroll_up")
        assertTrue(scrollUp is ProgrammaticGestureTrigger.Scroll)
        assertEquals(GestureDirection.UP, (scrollUp as ProgrammaticGestureTrigger.Scroll).direction)

        // Text input & IME
        val typeTrigger = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("type", TriggerOptions(textArgument = "Jarvis test"))
        assertTrue(typeTrigger is ProgrammaticGestureTrigger.TypeText)
        assertEquals("Jarvis test", (typeTrigger as ProgrammaticGestureTrigger.TypeText).text)

        val imeTrigger = AccessibilityActionTriggerMapper.mapSemanticActionToTrigger("ime_enter")
        assertTrue(imeTrigger is ProgrammaticGestureTrigger.ImeSubmit)
    }

    @Test
    fun testCalculateDirectionalVectors() {
        val bounds = Rect().apply {
            left = 200
            top = 400
            right = 800
            bottom = 1000
        }
        val screenW = 1080
        val screenH = 2340

        // Test Swipe / Scroll UP: finger moves upward from higher Y to lower Y
        val vecUp = AccessibilityActionTriggerMapper.calculateDirectionalVector(
            bounds = bounds,
            direction = GestureDirection.UP,
            factor = 0.40f,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        val (startXUp, startYUp, endXUp, endYUp) = vecUp
        assertEquals(500f, startXUp, 1.0f)
        assertEquals(500f, endXUp, 1.0f)
        assertTrue("startY should be greater than endY for upward motion", startYUp > endYUp)

        // Test Swipe / Scroll DOWN: finger moves downward from lower Y to higher Y
        val vecDown = AccessibilityActionTriggerMapper.calculateDirectionalVector(
            bounds = bounds,
            direction = GestureDirection.DOWN,
            factor = 0.40f,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        val (startXDown, startYDown, endXDown, endYDown) = vecDown
        assertEquals(500f, startXDown, 1.0f)
        assertEquals(500f, endXDown, 1.0f)
        assertTrue("startY should be less than endY for downward motion", startYDown < endYDown)

        // Test Swipe LEFT: finger moves leftward from higher X to lower X
        val vecLeft = AccessibilityActionTriggerMapper.calculateDirectionalVector(
            bounds = bounds,
            direction = GestureDirection.LEFT,
            factor = 0.40f,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        val (startXLeft, startYLeft, endXLeft, endYLeft) = vecLeft
        assertEquals(700f, startYLeft, 1.0f)
        assertEquals(700f, endYLeft, 1.0f)
        assertTrue("startX should be greater than endX for leftward swipe", startXLeft > endXLeft)

        // Test Swipe RIGHT: finger moves rightward from lower X to higher X
        val vecRight = AccessibilityActionTriggerMapper.calculateDirectionalVector(
            bounds = bounds,
            direction = GestureDirection.RIGHT,
            factor = 0.40f,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        val (startXRight, startYRight, endXRight, endYRight) = vecRight
        assertEquals(700f, startYRight, 1.0f)
        assertEquals(700f, endYRight, 1.0f)
        assertTrue("startX should be less than endX for rightward swipe", startXRight < endXRight)

        // Screen boundary safety: Ensure coordinates stay within display margins
        assertTrue("Coordinates must stay within screen bounds", startXLeft in 0f..screenW.toFloat())
        assertTrue("Coordinates must stay within screen bounds", endXLeft in 0f..screenW.toFloat())
        assertTrue("Coordinates must stay within screen bounds", startYUp in 0f..screenH.toFloat())
        assertTrue("Coordinates must stay within screen bounds", endYUp in 0f..screenH.toFloat())
    }

    @Test
    fun testGetActionLabel() {
        assertEquals("ACTION_CLICK", AccessibilityActionTriggerMapper.getActionLabel(AccessibilityNodeInfo.ACTION_CLICK))
        assertEquals("ACTION_LONG_CLICK", AccessibilityActionTriggerMapper.getActionLabel(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        assertEquals("ACTION_SCROLL_FORWARD", AccessibilityActionTriggerMapper.getActionLabel(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertEquals("ACTION_SCROLL_BACKWARD", AccessibilityActionTriggerMapper.getActionLabel(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        assertEquals("ACTION_SET_TEXT", AccessibilityActionTriggerMapper.getActionLabel(AccessibilityNodeInfo.ACTION_SET_TEXT))
        assertEquals("ACTION_DISMISS", AccessibilityActionTriggerMapper.getActionLabel(AccessibilityNodeInfo.ACTION_DISMISS))
    }
}
