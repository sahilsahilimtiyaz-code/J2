package com.jarvis.assistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.brain.OnDeviceGgufEngine
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Operating power profile that balances performance and energy conservation
 * without reducing visual fidelity, animations, or assistant capabilities.
 */
enum class PowerProfile(
    val label: String,
    val icon: String,
    val description: String,
) {
    PERFORMANCE(
        label = "High Performance",
        icon = "⚡",
        description = "Full 60fps rendering, 4-thread local inference, instant responsiveness.",
    ),
    BALANCED(
        label = "Adaptive Balanced",
        icon = "⚖️",
        description = "Lifecycle render gating, batched orb canvas, adaptive telemetry intervals.",
    ),
    POWER_SAVER(
        label = "Ultra Power Saver",
        icon = "🔋",
        description = "Smooth 20–30fps idle pacing, thermal-safe local threads, idle background deferral.",
    );
}

/** User-selectable power profile override in Settings -> Battery & App Optimization. */
enum class PowerProfileOverride(val label: String) {
    AUTO("Auto"),
    PERFORMANCE("Max"),
    BALANCED("Balanced"),
    POWER_SAVER("Saver"),
}

/** Real Android thermal severity bucket mapped from PowerManager.currentThermalStatus + battery sensor. */
enum class ThermalState(val label: String) {
    NOMINAL("Cool"),
    LIGHT("Warm"),
    MODERATE("Elevated"),
    SEVERE("Hot"),
}

data class BatteryDiagnostics(
    val levelPercent: Int = 100,
    val isCharging: Boolean = false,
    val chargePlug: String = "Battery",
    val temperatureCelsius: Float = 28.0f,
    val voltageMv: Int = 4100,
    val health: String = "Good",
    val thermalState: ThermalState = ThermalState.NOMINAL,
    val thermalStatusLabel: String = "Nominal",
    val isThermallyThrottled: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    val isDeviceIdleMode: Boolean = false,
    val isIgnoringOptimizations: Boolean = false,
    val profile: PowerProfile = PowerProfile.BALANCED,
    val profileOverride: PowerProfileOverride = PowerProfileOverride.AUTO,
    val targetIdleFps: Int = 30,
    val lastCacheTrimSummary: String = "",
)

/**
 * Pure, unit-testable battery and thermal optimization policy.
 * Never removes any UI/UX features — only paces idle work and protects thermals.
 */
object BatteryOptimizationPolicy {

    fun resolveEffectiveProfile(
        override: PowerProfileOverride,
        levelPercent: Int,
        isCharging: Boolean,
        isPowerSaveMode: Boolean,
        thermalState: ThermalState,
    ): PowerProfile {
        if (thermalState == ThermalState.SEVERE) return PowerProfile.POWER_SAVER
        val base = when (override) {
            PowerProfileOverride.PERFORMANCE -> PowerProfile.PERFORMANCE
            PowerProfileOverride.BALANCED -> PowerProfile.BALANCED
            PowerProfileOverride.POWER_SAVER -> PowerProfile.POWER_SAVER
            PowerProfileOverride.AUTO -> when {
                isPowerSaveMode || (!isCharging && levelPercent <= 20) -> PowerProfile.POWER_SAVER
                isCharging || levelPercent >= 60 -> PowerProfile.PERFORMANCE
                else -> PowerProfile.BALANCED
            }
        }
        return if (thermalState == ThermalState.MODERATE && base == PowerProfile.PERFORMANCE) {
            PowerProfile.BALANCED
        } else {
            base
        }
    }

    /**
     * Adaptive Arc Reactor frame interval:
     * - 16ms (~60fps) whenever active (listening, thinking, speaking, touched, or audio > 0.015f)
     *   unless the device is severely hot.
     * - Smoothly paces idle frames (16ms PERFORMANCE / 33ms BALANCED / 50ms POWER_SAVER)
     *   so zero visual layers are removed while idle GPU/CPU draw drops sharply.
     */
    fun targetOrbFrameIntervalMs(
        profile: PowerProfile,
        isActive: Boolean,
        audioAmplitude: Float,
        thermalState: ThermalState,
    ): Long {
        if (thermalState == ThermalState.SEVERE) return if (isActive) 33L else 50L
        if (isActive || audioAmplitude > 0.015f) return 16L
        return when (profile) {
            PowerProfile.PERFORMANCE -> 16L
            PowerProfile.BALANCED -> 33L
            PowerProfile.POWER_SAVER -> 50L
        }
    }

    fun targetIdleFps(profile: PowerProfile, thermalState: ThermalState): Int {
        val ms = targetOrbFrameIntervalMs(profile, isActive = false, audioAmplitude = 0f, thermalState = thermalState)
        return (1000L / ms.coerceAtLeast(16L)).toInt().coerceIn(20, 60)
    }

    /** Silence gate: skip ambient audio FX math during silence unless running cool in PERFORMANCE. */
    fun shouldComputeAudioFx(
        profile: PowerProfile,
        audioAmplitude: Float,
        thermalState: ThermalState,
    ): Boolean {
        if (audioAmplitude > 0.015f) return true
        if (thermalState == ThermalState.MODERATE || thermalState == ThermalState.SEVERE) return false
        return profile == PowerProfile.PERFORMANCE
    }

    /** Hardware MP4 backdrop stays active in PERFORMANCE/BALANCED; falls back to pure 3D Canvas in POWER_SAVER/SEVERE. */
    fun shouldRunBackgroundVideo(
        profile: PowerProfile,
        thermalState: ThermalState,
    ): Boolean {
        if (thermalState == ThermalState.SEVERE) return false
        return profile != PowerProfile.POWER_SAVER
    }

    /** Adaptive foreground device telemetry polling cadence. */
    fun deviceStatusPollIntervalMs(
        profile: PowerProfile,
        thermalState: ThermalState,
    ): Long {
        if (thermalState == ThermalState.SEVERE) return 35_000L
        return when (profile) {
            PowerProfile.PERFORMANCE -> 12_000L
            PowerProfile.BALANCED -> 20_000L
            PowerProfile.POWER_SAVER -> 35_000L
        }
    }

    /** Adaptive CPU thread ceiling for on-device GGUF llama.cpp inference. */
    fun recommendedInferenceThreads(
        profile: PowerProfile,
        availableCores: Int = 8,
    ): Int {
        val safeCores = availableCores.coerceAtLeast(1)
        return when (profile) {
            PowerProfile.PERFORMANCE -> minOf(4, safeCores)
            PowerProfile.BALANCED -> minOf(3, safeCores)
            PowerProfile.POWER_SAVER -> minOf(2, safeCores)
        }
    }
}

/**
 * Centralized Battery, Thermal, and App Optimization Engine for J.A.R.V.I.S.
 *
 * Implements:
 * 1. Hardware-level broadcast monitoring for battery capacity, plug type, temperature, and Android thermal status.
 * 2. Samsung Galaxy A54 Battery Unrestricted guide and direct intent launcher.
 * 3. Adaptive Power Profiling (with persisted user override): dynamically scales idle frame pacing,
 *    telemetry poll frequency, background video, and local GGUF inference thread count without removing UI/UX features.
 * 4. One-tap app cache + idle OkHttp socket pool + idle GGUF weight optimization.
 */
class BatteryOptimizationManager private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val prefs = appContext.getSharedPreferences("jarvis_battery_prefs", Context.MODE_PRIVATE)

    @Volatile
    private var profileOverride: PowerProfileOverride = loadSavedOverride()

    private val _diagnostics = MutableStateFlow(
        BatteryDiagnostics(profileOverride = profileOverride),
    )
    val diagnostics: StateFlow<BatteryDiagnostics> = _diagnostics.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateFromBatteryIntent(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
                -> refreshPowerState()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                batteryReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull()
        if (sticky != null) {
            updateFromBatteryIntent(sticky)
        } else {
            refreshPowerState()
        }
    }

    private fun loadSavedOverride(): PowerProfileOverride {
        val raw = runCatching { prefs.getString("power_profile_override", null) }.getOrNull()
        return PowerProfileOverride.entries.firstOrNull { it.name == raw } ?: PowerProfileOverride.AUTO
    }

    fun setProfileOverride(override: PowerProfileOverride) {
        profileOverride = override
        runCatching { prefs.edit().putString("power_profile_override", override.name).apply() }
        refreshPowerState()
    }

    private fun readThermalState(tempCelsius: Float): ThermalState {
        val osThermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                when (powerManager?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                    PowerManager.THERMAL_STATUS_SEVERE,
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN,
                    -> ThermalState.SEVERE
                    else -> ThermalState.NOMINAL
                }
            }.getOrDefault(ThermalState.NOMINAL)
        } else {
            ThermalState.NOMINAL
        }

        val sensorThermal = when {
            tempCelsius >= 43.0f -> ThermalState.SEVERE
            tempCelsius >= 39.5f -> ThermalState.MODERATE
            tempCelsius >= 36.5f -> ThermalState.LIGHT
            else -> ThermalState.NOMINAL
        }
        return if (sensorThermal.ordinal > osThermal.ordinal) sensorThermal else osThermal
    }

    private fun updateFromBatteryIntent(intent: Intent) {
        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (rawLevel >= 0 && scale > 0) (rawLevel * 100) / scale else 100

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (isCharging) "Charging" else "On Battery"
        }

        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280)
        val tempCelsius = rawTemp / 10.0f
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4100)

        val rawHealth = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
        val health = when (rawHealth) {
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Good"
        }

        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        val isDeviceIdle = powerManager?.isDeviceIdleMode ?: false
        val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false
        val thermalState = readThermalState(tempCelsius)
        val throttled = thermalState == ThermalState.MODERATE || thermalState == ThermalState.SEVERE

        val profile = BatteryOptimizationPolicy.resolveEffectiveProfile(
            override = profileOverride,
            levelPercent = pct,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave,
            thermalState = thermalState,
        )

        _diagnostics.update {
            it.copy(
                levelPercent = pct,
                isCharging = isCharging,
                chargePlug = plugType,
                temperatureCelsius = tempCelsius,
                voltageMv = voltage,
                health = health,
                thermalState = thermalState,
                thermalStatusLabel = thermalState.label,
                isThermallyThrottled = throttled,
                isPowerSaveMode = isPowerSave,
                isDeviceIdleMode = isDeviceIdle,
                isIgnoringOptimizations = isIgnoring,
                profile = profile,
                profileOverride = profileOverride,
                targetIdleFps = BatteryOptimizationPolicy.targetIdleFps(profile, thermalState),
            )
        }
    }

    fun refreshPowerState() {
        val current = _diagnostics.value
        val pct = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 } ?: current.levelPercent

        val isCharging = runCatching { batteryManager?.isCharging ?: current.isCharging }
            .getOrDefault(current.isCharging)
        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        val isDeviceIdle = powerManager?.isDeviceIdleMode ?: false
        val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false
        val thermalState = readThermalState(current.temperatureCelsius)
        val throttled = thermalState == ThermalState.MODERATE || thermalState == ThermalState.SEVERE

        val profile = BatteryOptimizationPolicy.resolveEffectiveProfile(
            override = profileOverride,
            levelPercent = pct,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave,
            thermalState = thermalState,
        )

        _diagnostics.update {
            it.copy(
                levelPercent = pct,
                isCharging = isCharging,
                thermalState = thermalState,
                thermalStatusLabel = thermalState.label,
                isThermallyThrottled = throttled,
                isPowerSaveMode = isPowerSave,
                isDeviceIdleMode = isDeviceIdle,
                isIgnoringOptimizations = isIgnoring,
                profile = profile,
                profileOverride = profileOverride,
                targetIdleFps = BatteryOptimizationPolicy.targetIdleFps(profile, thermalState),
            )
        }
    }

    /**
     * Safe one-tap optimization:
     * 1. Clears temporary files inside [Context.getCacheDir] (never touches Room databases, encrypted keys, or downloaded GGUF models).
     * 2. Evicts idle HTTP/TLS sockets in the shared OkHttp ConnectionPool.
     * 3. Releases cached on-device GGUF weights from native RAM until next local prompt.
     */
    fun trimAppCache(): String {
        var bytesFreed = 0L
        var filesDeleted = 0

        fun purgeDir(dir: File?) {
            if (dir == null || !dir.exists()) return
            dir.listFiles()?.forEach { child ->
                // Never delete offline GGUF models or active partial downloads
                if (child.name.endsWith(".gguf", ignoreCase = true) ||
                    child.name.endsWith(".part", ignoreCase = true) ||
                    child.name.equals("local_models", ignoreCase = true)
                ) {
                    return@forEach
                }
                if (child.isDirectory) {
                    purgeDir(child)
                    if (child.listFiles().isNullOrEmpty()) {
                        child.delete()
                    }
                } else {
                    val size = child.length().coerceAtLeast(0L)
                    if (child.delete()) {
                        bytesFreed += size
                        filesDeleted += 1
                    }
                }
            }
        }

        runCatching { purgeDir(appContext.cacheDir) }
        runCatching { purgeDir(appContext.externalCacheDir) }
        runCatching { JarvisCore.get(appContext).httpClient.connectionPool.evictAll() }
        runCatching { OnDeviceGgufEngine.releaseBestEffort() }

        val freedLabel = when {
            bytesFreed >= 1_048_576L -> "%.1f MB".format(bytesFreed / 1_048_576.0)
            bytesFreed >= 1024L -> "${bytesFreed / 1024L} KB"
            else -> "$bytesFreed B"
        }
        val summary = if (filesDeleted > 0) {
            "Cleared $filesDeleted temp cache file(s) ($freedLabel), flushed idle HTTP sockets, and trimmed local RAM."
        } else {
            "Cache already clean. Flushed idle HTTP sockets and trimmed local inference RAM."
        }
        _diagnostics.update { it.copy(lastCacheTrimSummary = summary) }
        return summary
    }

    /**
     * Launch Samsung / Android battery optimization whitelist dialog so Jarvis can
     * run uninterrupted without Samsung putting the app into sleeping apps.
     */
    fun requestBatteryUnrestricted() {
        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { appContext.startActivity(directIntent) }.isSuccess
        if (!launched) {
            val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { appContext.startActivity(appSettingsIntent) }
        }
    }

    fun shouldComputeAudioFx(audioAmplitude: Float): Boolean {
        val diag = _diagnostics.value
        return BatteryOptimizationPolicy.shouldComputeAudioFx(
            profile = diag.profile,
            audioAmplitude = audioAmplitude,
            thermalState = diag.thermalState,
        )
    }

    fun targetOrbFrameIntervalMs(
        isActive: Boolean,
        audioAmplitude: Float = 0f,
    ): Long {
        val diag = _diagnostics.value
        return BatteryOptimizationPolicy.targetOrbFrameIntervalMs(
            profile = diag.profile,
            isActive = isActive,
            audioAmplitude = audioAmplitude,
            thermalState = diag.thermalState,
        )
    }

    fun shouldRunBackgroundVideo(): Boolean {
        val diag = _diagnostics.value
        return BatteryOptimizationPolicy.shouldRunBackgroundVideo(
            profile = diag.profile,
            thermalState = diag.thermalState,
        )
    }

    fun deviceStatusPollIntervalMs(): Long {
        val diag = _diagnostics.value
        return BatteryOptimizationPolicy.deviceStatusPollIntervalMs(
            profile = diag.profile,
            thermalState = diag.thermalState,
        )
    }

    fun recommendedTelemetryPollMs(): Long = deviceStatusPollIntervalMs()

    fun recommendedInferenceThreads(): Int =
        BatteryOptimizationPolicy.recommendedInferenceThreads(
            profile = _diagnostics.value.profile,
            availableCores = Runtime.getRuntime().availableProcessors(),
        )

    companion object {
        @Volatile
        private var instance: BatteryOptimizationManager? = null

        fun get(context: Context): BatteryOptimizationManager =
            instance ?: synchronized(this) {
                instance ?: BatteryOptimizationManager(context.applicationContext).also { instance = it }
            }

        /** Pure power profile resolver (unit-testable). */
        fun resolvePowerProfile(
            levelPercent: Int,
            isCharging: Boolean,
            isPowerSaveMode: Boolean,
            isThermallyThrottled: Boolean = false,
            override: PowerProfileOverride = PowerProfileOverride.AUTO,
        ): PowerProfile = BatteryOptimizationPolicy.resolveEffectiveProfile(
            override = override,
            levelPercent = levelPercent,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode,
            thermalState = if (isThermallyThrottled) ThermalState.SEVERE else ThermalState.NOMINAL,
        )

        fun shouldComputeAudioFx(
            audioAmplitude: Float,
            profile: PowerProfile,
        ): Boolean = BatteryOptimizationPolicy.shouldComputeAudioFx(
            profile = profile,
            audioAmplitude = audioAmplitude,
            thermalState = ThermalState.NOMINAL,
        )

        fun recommendedFrameIntervalNanos(
            profile: PowerProfile,
            isActiveInteraction: Boolean,
        ): Long = BatteryOptimizationPolicy.targetOrbFrameIntervalMs(
            profile = profile,
            isActive = isActiveInteraction,
            audioAmplitude = 0f,
            thermalState = ThermalState.NOMINAL,
        ) * 1_000_000L

        fun targetOrbFrameIntervalMs(
            profile: PowerProfile,
            isActive: Boolean,
        ): Long = BatteryOptimizationPolicy.targetOrbFrameIntervalMs(
            profile = profile,
            isActive = isActive,
            audioAmplitude = 0f,
            thermalState = ThermalState.NOMINAL,
        )

        fun shouldRunBackgroundVideo(
            profile: PowerProfile,
            isThermallyThrottled: Boolean,
        ): Boolean = BatteryOptimizationPolicy.shouldRunBackgroundVideo(
            profile = profile,
            thermalState = if (isThermallyThrottled) ThermalState.SEVERE else ThermalState.NOMINAL,
        )

        fun recommendedTelemetryPollMs(profile: PowerProfile): Long =
            BatteryOptimizationPolicy.deviceStatusPollIntervalMs(profile, ThermalState.NOMINAL)

        fun recommendedInferenceThreads(
            profile: PowerProfile,
            availableCores: Int = 8,
        ): Int = BatteryOptimizationPolicy.recommendedInferenceThreads(profile, availableCores)
    }
}
