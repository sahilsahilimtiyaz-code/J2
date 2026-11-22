package com.jarvis.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationPolicyTest {

    @Test
    fun auto_profile_adapts_to_battery_charge_powersave_and_thermals() {
        assertEquals(
            PowerProfile.PERFORMANCE,
            BatteryOptimizationPolicy.resolveEffectiveProfile(
                override = PowerProfileOverride.AUTO,
                levelPercent = 85,
                isCharging = false,
                isPowerSaveMode = false,
                thermalState = ThermalState.NOMINAL,
            ),
        )
        assertEquals(
            PowerProfile.PERFORMANCE,
            BatteryOptimizationPolicy.resolveEffectiveProfile(
                override = PowerProfileOverride.AUTO,
                levelPercent = 30,
                isCharging = true,
                isPowerSaveMode = false,
                thermalState = ThermalState.NOMINAL,
            ),
        )
        assertEquals(
            PowerProfile.BALANCED,
            BatteryOptimizationPolicy.resolveEffectiveProfile(
                override = PowerProfileOverride.AUTO,
                levelPercent = 45,
                isCharging = false,
                isPowerSaveMode = false,
                thermalState = ThermalState.NOMINAL,
            ),
        )
        assertEquals(
            PowerProfile.POWER_SAVER,
            BatteryOptimizationPolicy.resolveEffectiveProfile(
                override = PowerProfileOverride.AUTO,
                levelPercent = 18,
                isCharging = false,
                isPowerSaveMode = false,
                thermalState = ThermalState.NOMINAL,
            ),
        )
        assertEquals(
            PowerProfile.POWER_SAVER,
            BatteryOptimizationPolicy.resolveEffectiveProfile(
                override = PowerProfileOverride.AUTO,
                levelPercent = 80,
                isCharging = false,
                isPowerSaveMode = true,
                thermalState = ThermalState.NOMINAL,
            ),
        )
    }

    @Test
    fun severe_thermal_always_enforces_power_saver_even_with_performance_override() {
        assertEquals(
            PowerProfile.POWER_SAVER,
            BatteryOptimizationPolicy.resolveEffectiveProfile(
                override = PowerProfileOverride.PERFORMANCE,
                levelPercent = 95,
                isCharging = true,
                isPowerSaveMode = false,
                thermalState = ThermalState.SEVERE,
            ),
        )
        assertEquals(
            PowerProfile.BALANCED,
            BatteryOptimizationPolicy.resolveEffectiveProfile(
                override = PowerProfileOverride.PERFORMANCE,
                levelPercent = 95,
                isCharging = true,
                isPowerSaveMode = false,
                thermalState = ThermalState.MODERATE,
            ),
        )
    }

    @Test
    fun orb_frame_pacing_stays_60fps_when_active_and_conserves_energy_when_idle() {
        // Active voice/interaction stays 60fps (16ms) in every non-severe thermal state
        assertEquals(
            16L,
            BatteryOptimizationPolicy.targetOrbFrameIntervalMs(
                profile = PowerProfile.POWER_SAVER,
                isActive = true,
                audioAmplitude = 0f,
                thermalState = ThermalState.NOMINAL,
            ),
        )
        assertEquals(
            16L,
            BatteryOptimizationPolicy.targetOrbFrameIntervalMs(
                profile = PowerProfile.BALANCED,
                isActive = false,
                audioAmplitude = 0.2f,
                thermalState = ThermalState.NOMINAL,
            ),
        )
        // Idle adapts cleanly
        assertEquals(
            33L,
            BatteryOptimizationPolicy.targetOrbFrameIntervalMs(
                profile = PowerProfile.BALANCED,
                isActive = false,
                audioAmplitude = 0f,
                thermalState = ThermalState.NOMINAL,
            ),
        )
        assertEquals(
            50L,
            BatteryOptimizationPolicy.targetOrbFrameIntervalMs(
                profile = PowerProfile.POWER_SAVER,
                isActive = false,
                audioAmplitude = 0f,
                thermalState = ThermalState.NOMINAL,
            ),
        )
    }

    @Test
    fun silence_gate_and_video_backdrop_policies_work_as_designed() {
        assertTrue(BatteryOptimizationPolicy.shouldComputeAudioFx(PowerProfile.POWER_SAVER, 0.05f, ThermalState.NOMINAL))
        assertFalse(BatteryOptimizationPolicy.shouldComputeAudioFx(PowerProfile.BALANCED, 0.0f, ThermalState.NOMINAL))
        assertTrue(BatteryOptimizationPolicy.shouldComputeAudioFx(PowerProfile.PERFORMANCE, 0.0f, ThermalState.NOMINAL))
        assertFalse(BatteryOptimizationPolicy.shouldComputeAudioFx(PowerProfile.PERFORMANCE, 0.0f, ThermalState.MODERATE))

        assertTrue(BatteryOptimizationPolicy.shouldRunBackgroundVideo(PowerProfile.BALANCED, ThermalState.NOMINAL))
        assertFalse(BatteryOptimizationPolicy.shouldRunBackgroundVideo(PowerProfile.POWER_SAVER, ThermalState.NOMINAL))
        assertFalse(BatteryOptimizationPolicy.shouldRunBackgroundVideo(PowerProfile.PERFORMANCE, ThermalState.SEVERE))
    }
}
