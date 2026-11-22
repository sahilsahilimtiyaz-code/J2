package com.jarvis.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationManagerTest {

    @Test
    fun testPowerProfileEnums() {
        assertEquals("High Performance", PowerProfile.PERFORMANCE.label)
        assertEquals("⚡", PowerProfile.PERFORMANCE.icon)

        assertEquals("Adaptive Balanced", PowerProfile.BALANCED.label)
        assertEquals("⚖️", PowerProfile.BALANCED.icon)

        assertEquals("Ultra Power Saver", PowerProfile.POWER_SAVER.label)
        assertEquals("🔋", PowerProfile.POWER_SAVER.icon)
    }

    @Test
    fun testBatteryDiagnosticsDefaults() {
        val diag = BatteryDiagnostics()
        assertEquals(100, diag.levelPercent)
        assertFalse(diag.isCharging)
        assertEquals("Battery", diag.chargePlug)
        assertEquals("Good", diag.health)
        assertEquals(ThermalState.NOMINAL, diag.thermalState)
        assertEquals("Nominal", diag.thermalStatusLabel)
        assertEquals(PowerProfile.BALANCED, diag.profile)
        assertEquals(PowerProfileOverride.AUTO, diag.profileOverride)
    }

    @Test
    fun testResolvePowerProfileLogic() {
        // High Performance: charging OR >= 60%
        assertEquals(
            PowerProfile.PERFORMANCE,
            BatteryOptimizationManager.resolvePowerProfile(levelPercent = 15, isCharging = true, isPowerSaveMode = false),
        )
        assertEquals(
            PowerProfile.PERFORMANCE,
            BatteryOptimizationManager.resolvePowerProfile(levelPercent = 85, isCharging = false, isPowerSaveMode = false),
        )

        // Balanced: 20% to 59% on battery
        assertEquals(
            PowerProfile.BALANCED,
            BatteryOptimizationManager.resolvePowerProfile(levelPercent = 45, isCharging = false, isPowerSaveMode = false),
        )

        // Power Saver: < 20% or OS power-save mode
        assertEquals(
            PowerProfile.POWER_SAVER,
            BatteryOptimizationManager.resolvePowerProfile(levelPercent = 12, isCharging = false, isPowerSaveMode = false),
        )
        assertEquals(
            PowerProfile.POWER_SAVER,
            BatteryOptimizationManager.resolvePowerProfile(levelPercent = 50, isCharging = false, isPowerSaveMode = true),
        )

        // Thermal throttling always overrides even PERFORMANCE manual override
        assertEquals(
            PowerProfile.POWER_SAVER,
            BatteryOptimizationManager.resolvePowerProfile(
                levelPercent = 95,
                isCharging = true,
                isPowerSaveMode = false,
                isThermallyThrottled = true,
                override = PowerProfileOverride.PERFORMANCE,
            ),
        )

        // Manual override works when thermally nominal
        assertEquals(
            PowerProfile.POWER_SAVER,
            BatteryOptimizationManager.resolvePowerProfile(
                levelPercent = 90,
                isCharging = true,
                isPowerSaveMode = false,
                isThermallyThrottled = false,
                override = PowerProfileOverride.POWER_SAVER,
            ),
        )
    }

    @Test
    fun testSilenceGateAdaptiveFramePacingAndLocalThreads() {
        // Silence gate: allows audio FX when audio > 0.015f or in PERFORMANCE, gates during idle silence otherwise
        assertTrue(BatteryOptimizationManager.shouldComputeAudioFx(0.20f, PowerProfile.POWER_SAVER))
        assertTrue(BatteryOptimizationManager.shouldComputeAudioFx(0.0f, PowerProfile.PERFORMANCE))
        assertFalse(BatteryOptimizationManager.shouldComputeAudioFx(0.0f, PowerProfile.BALANCED))
        assertFalse(BatteryOptimizationManager.shouldComputeAudioFx(0.005f, PowerProfile.POWER_SAVER))

        // Active interaction always gets 16ms (full 60fps)
        assertEquals(16L, BatteryOptimizationManager.targetOrbFrameIntervalMs(PowerProfile.POWER_SAVER, isActive = true))
        assertEquals(16L, BatteryOptimizationManager.targetOrbFrameIntervalMs(PowerProfile.PERFORMANCE, isActive = false))
        assertEquals(33L, BatteryOptimizationManager.targetOrbFrameIntervalMs(PowerProfile.BALANCED, isActive = false))
        assertEquals(50L, BatteryOptimizationManager.targetOrbFrameIntervalMs(PowerProfile.POWER_SAVER, isActive = false))

        // Background video backdrop gating
        assertTrue(BatteryOptimizationManager.shouldRunBackgroundVideo(PowerProfile.PERFORMANCE, isThermallyThrottled = false))
        assertTrue(BatteryOptimizationManager.shouldRunBackgroundVideo(PowerProfile.BALANCED, isThermallyThrottled = false))
        assertFalse(BatteryOptimizationManager.shouldRunBackgroundVideo(PowerProfile.POWER_SAVER, isThermallyThrottled = false))
        assertFalse(BatteryOptimizationManager.shouldRunBackgroundVideo(PowerProfile.PERFORMANCE, isThermallyThrottled = true))

        // Adaptive telemetry polling intervals
        assertEquals(12_000L, BatteryOptimizationManager.recommendedTelemetryPollMs(PowerProfile.PERFORMANCE))
        assertEquals(20_000L, BatteryOptimizationManager.recommendedTelemetryPollMs(PowerProfile.BALANCED))
        assertEquals(35_000L, BatteryOptimizationManager.recommendedTelemetryPollMs(PowerProfile.POWER_SAVER))

        // Adaptive on-device GGUF thread counts
        assertEquals(4, BatteryOptimizationManager.recommendedInferenceThreads(PowerProfile.PERFORMANCE, availableCores = 8))
        assertEquals(3, BatteryOptimizationManager.recommendedInferenceThreads(PowerProfile.BALANCED, availableCores = 8))
        assertEquals(2, BatteryOptimizationManager.recommendedInferenceThreads(PowerProfile.POWER_SAVER, availableCores = 8))
        assertEquals(1, BatteryOptimizationManager.recommendedInferenceThreads(PowerProfile.PERFORMANCE, availableCores = 1))
    }
}
