package com.jarvis.assistant.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapPrecisionEngineTest {

    private val displayW = 1080
    private val displayH = 2340

    @Test
    fun target_point_is_the_element_centre_inside_the_display() {
        val point = TapPrecisionEngine.targetPoint(
            ElementBox(100, 400, 500, 500),
            displayW,
            displayH,
        )
        assertEquals(300, point.x)
        assertEquals(450, point.y)
    }

    @Test
    fun target_point_is_clamped_away_from_status_and_navigation_bars() {
        val underStatusBar = TapPrecisionEngine.targetPoint(
            ElementBox(0, 0, displayW, 40),
            displayW,
            displayH,
            topInset = 96,
        )
        assertTrue(underStatusBar.y >= 106)

        val underNavBar = TapPrecisionEngine.targetPoint(
            ElementBox(0, displayH - 30, displayW, displayH),
            displayW,
            displayH,
            bottomInset = 84,
        )
        assertTrue(underNavBar.y <= displayH - 94)
    }

    @Test
    fun correction_ladder_starts_at_the_target_and_stays_inside_the_element() {
        val box = ElementBox(200, 800, 880, 940)
        val target = TapPrecisionEngine.targetPoint(box, displayW, displayH)
        val ladder = TapPrecisionEngine.correctionLadder(target, box, displayW, displayH)

        assertEquals(target, ladder.first())
        assertTrue("expected several retry points", ladder.size >= 4)
        ladder.forEach { point ->
            assertTrue("x ${point.x} escaped the element", point.x in box.left..box.right)
            assertTrue("y ${point.y} escaped the element", point.y in box.top..box.bottom)
        }
        assertTrue(ladder.distinct().size == ladder.size)
    }

    @Test
    fun raw_coordinate_ladder_nudges_around_the_point_and_stays_on_screen() {
        val target = TapPoint(540, 1200)
        val ladder = TapPrecisionEngine.correctionLadder(target, null, displayW, displayH)

        assertEquals(target, ladder.first())
        assertTrue(ladder.any { it.y < target.y })
        assertTrue(ladder.any { it.y > target.y })
        assertTrue(ladder.any { it.x < target.x })
        assertTrue(ladder.any { it.x > target.x })

        val edge = TapPrecisionEngine.correctionLadder(TapPoint(4, 2), null, displayW, displayH)
        edge.forEach { point ->
            assertTrue(point.x >= 10)
            assertTrue(point.y >= 10)
        }
    }

    @Test
    fun learned_bias_is_averaged_per_app_and_applied_to_later_taps() {
        TapPrecisionEngine.reset()
        assertTrue(TapPrecisionEngine.learnedBias("com.google.android.youtube").isZero)

        TapPrecisionEngine.recordVerifiedCorrection(
            "com.google.android.youtube",
            TapPoint(500, 900),
            TapPoint(500, 940),
        )
        TapPrecisionEngine.recordVerifiedCorrection(
            "com.google.android.youtube",
            TapPoint(500, 900),
            TapPoint(510, 960),
        )

        val bias = TapPrecisionEngine.learnedBias("com.google.android.youtube", displayW, displayH)
        assertEquals(5, bias.dx)
        // 60px was requested but each sample is capped at MAX_LEARNED_BIAS_PX (44),
        // so the stored average is (40 + 44) / 2 = 42.
        assertEquals(42, bias.dy)
        assertFalse(bias.isZero)

        val applied = TapPrecisionEngine.applyBias(TapPoint(500, 900), bias, displayW, displayH)
        assertTrue(applied.y > 900)
        assertEquals(505, applied.x)
    }

    @Test
    fun learned_bias_is_clamped_so_one_bad_tap_cannot_wreck_later_taps() {
        TapPrecisionEngine.reset()
        TapPrecisionEngine.recordVerifiedCorrection("com.example.app", TapPoint(10, 10), TapPoint(1000, 2000))
        val bias = TapPrecisionEngine.learnedBias("com.example.app", displayW, displayH)
        assertTrue(bias.dx <= TapPrecisionEngine.MAX_LEARNED_BIAS_PX)
        assertTrue(bias.dy <= TapPrecisionEngine.MAX_LEARNED_BIAS_PX)
    }

    @Test
    fun bias_never_pushes_a_touch_off_the_display() {
        val applied = TapPrecisionEngine.applyBias(
            TapPoint(12, 15),
            TapBias(-500, -500),
            displayW,
            displayH,
        )
        assertTrue(applied.x >= 10)
        assertTrue(applied.y >= 10)

        val bottomRight = TapPrecisionEngine.applyBias(
            TapPoint(displayW - 12, displayH - 15),
            TapBias(500, 500),
            displayW,
            displayH,
        )
        assertTrue(bottomRight.x <= displayW - 10)
        assertTrue(bottomRight.y <= displayH - 10)
    }
}
