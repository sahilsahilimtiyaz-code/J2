package com.jarvis.assistant.tools

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.jarvis.assistant.brain.ToolDefinition
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * Runs a comprehensive Iron Man J.A.R.V.I.S. hardware and system diagnostic scan:
 * battery telemetry, memory, storage, network connectivity, and operating parameters.
 */
class DeviceDiagnosticsTool : Tool {
    override val definition = ToolDefinition(
        name = "run_diagnostics",
        description = "Runs a full J.A.R.V.I.S. device hardware and system diagnostic scan: battery telemetry, memory usage, storage breakdown, WiFi/cellular signals, and OS health.",
        parameters = objectSchema(
            properties = emptyMap(),
            required = emptyList(),
        ),
    )

    override val label: String = "System Diagnostics"
    override val category: ToolCategory = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val ctx = context.appContext
        val sb = StringBuilder()
        sb.append("=== J.A.R.V.I.S. CORE TELEMETRY ===\n")

        // 1. Device Info & Uptime
        val uptimeHours = (SystemClock.elapsedRealtime() / (1000 * 60 * 60)).toInt()
        val uptimeMinutes = ((SystemClock.elapsedRealtime() / (1000 * 60)) % 60).toInt()
        sb.append("• Unit: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
        sb.append("• Uptime: ${uptimeHours}h ${uptimeMinutes}m | Security Patch: ${Build.VERSION.SECURITY_PATCH}\n")

        // 2. Battery Telemetry
        val ifilter = IntentFilter(IntentIntentFilterBattery())
        val bStatus = ctx.registerReceiver(null, ifilter)
        if (bStatus != null) {
            val level = bStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = bStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = bStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val powerSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC Fast Charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Qi Pad"
                else -> if (isCharging) "Charging" else "Discharging"
            }
            val temp = bStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0f
            val voltage = bStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) / 1000.0f
            val health = when (bStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Optimal (Good)"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEATING"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                else -> "Normal"
            }
            sb.append("• Arc Power (Battery): $pct% ($powerSource) | Temp: ${temp}°C | Voltage: ${voltage}V | Health: $health\n")
        }

        // 3. Memory (RAM)
        val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
            val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            val usedPct = (((totalGb - availGb) / totalGb) * 100).toInt()
            sb.append("• Neural Memory (RAM): ${String.format(Locale.US, "%.1f", totalGb - availGb)} GB used of ${String.format(Locale.US, "%.1f", totalGb)} GB ($usedPct% load | Low RAM: ${memInfo.lowMemory})\n")
        }

        // 4. Internal Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availBlocks = stat.availableBlocksLong
        val totalStorageGb = (totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
        val freeStorageGb = (availBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
        val usedStorageGb = totalStorageGb - freeStorageGb
        val storagePct = ((usedStorageGb / totalStorageGb) * 100).toInt()
        sb.append("• Core Storage: ${String.format(Locale.US, "%.1f", usedStorageGb)} GB used of ${String.format(Locale.US, "%.1f", totalStorageGb)} GB ($storagePct% full | ${String.format(Locale.US, "%.1f", freeStorageGb)} GB free)\n")

        // 5. Network & Comms
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        if (caps != null) {
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val downSpeed = caps.linkDownstreamBandwidthKbps / 1000
            val upSpeed = caps.linkUpstreamBandwidthKbps / 1000

            var ipAddr = "Unavailable"
            try {
                for (en in NetworkInterface.getNetworkInterfaces()) {
                    for (addr in en.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            ipAddr = addr.hostAddress ?: ""
                            break
                        }
                    }
                }
            } catch (_: Exception) {}

            val netType = if (isWifi) "WiFi Link" else if (isCell) "Cellular Data" else "Active Connection"
            sb.append("• Comms ($netType): Speed ~${downSpeed}Mbps Down / ~${upSpeed}Mbps Up | Local IP: $ipAddr\n")
        } else {
            sb.append("• Comms: Offline / No active network interface detected.\n")
        }

        sb.append("=== ALL SYSTEMS OPERATIONAL ===")
        return ToolResult(true, sb.toString())
    }

    private fun IntentIntentFilterBattery(): String = Intent.ACTION_BATTERY_CHANGED
}

/**
 * Deep breakdown of device internal and external SD storage.
 */
class StorageAnalysisTool : Tool {
    override val definition = ToolDefinition(
        name = "analyze_storage",
        description = "Provides a detailed storage breakdown for internal and external SD card storage, showing free space, total space, and recommendations.",
        parameters = objectSchema(
            properties = emptyMap(),
            required = emptyList(),
        ),
    )

    override val label: String = "Storage Analysis"
    override val category: ToolCategory = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val sb = StringBuilder()
        sb.append("J.A.R.V.I.S. Storage Breakdown:\n")

        // Internal Storage
        val internalStat = StatFs(Environment.getDataDirectory().path)
        val internalTotalGb = (internalStat.blockCountLong * internalStat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
        val internalFreeGb = (internalStat.availableBlocksLong * internalStat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
        val internalUsedGb = internalTotalGb - internalFreeGb
        val internalPct = ((internalUsedGb / internalTotalGb) * 100).toInt()

        sb.append("• Internal Storage: ${String.format(Locale.US, "%.1f", internalUsedGb)} GB / ${String.format(Locale.US, "%.1f", internalTotalGb)} GB ($internalPct% full, ${String.format(Locale.US, "%.1f", internalFreeGb)} GB free)\n")

        // External Storage / SD card if present
        val extDir = context.appContext.getExternalFilesDirs(null)
        if (extDir.size > 1 && extDir[1] != null) {
            val sdStat = StatFs(extDir[1].path)
            val sdTotalGb = (sdStat.blockCountLong * sdStat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
            val sdFreeGb = (sdStat.availableBlocksLong * sdStat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
            val sdUsedGb = sdTotalGb - sdFreeGb
            val sdPct = if (sdTotalGb > 0) ((sdUsedGb / sdTotalGb) * 100).toInt() else 0
            sb.append("• MicroSD Card: ${String.format(Locale.US, "%.1f", sdUsedGb)} GB / ${String.format(Locale.US, "%.1f", sdTotalGb)} GB ($sdPct% full, ${String.format(Locale.US, "%.1f", sdFreeGb)} GB free)\n")
        } else {
            sb.append("• External SD Card: Not mounted or not installed.\n")
        }

        if (internalFreeGb < 5.0) {
            sb.append("Warning: Core storage is running low (less than 5 GB remaining). I recommend cleaning application cache or media.")
        } else {
            sb.append("Status: Storage capacity is healthy with ample headroom.")
        }

        return ToolResult(true, sb.toString())
    }
}

/**
 * Inspects or adjusts Do Not Disturb / Zen mode.
 */
class DoNotDisturbTool : Tool {
    override val definition = ToolDefinition(
        name = "set_do_not_disturb",
        description = "Inspects or configures the device's Do Not Disturb (DND) / Zen mode setting.",
        parameters = objectSchema(
            properties = mapOf(
                "mode" to stringProperty(
                    description = "DND mode: 'priority' (priority only), 'alarms' (alarms only), 'silence' (total silence), 'off' (normal sound), or 'status' (read current setting).",
                ),
            ),
            required = listOf("mode"),
        ),
    )

    override val label: String = "Do Not Disturb"
    override val category: ToolCategory = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val mode = arguments.argString("mode")?.lowercase() ?: "status"
        val nm = context.appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolResult(false, "Notification manager is unavailable.")

        val currentFilter = nm.currentInterruptionFilter
        val currentDesc = when (currentFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "Off (Normal sound)"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority Only"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms Only"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "Total Silence"
            else -> "Unknown"
        }

        if (mode == "status") {
            return ToolResult(true, "Do Not Disturb is currently: $currentDesc.")
        }

        if (!nm.isNotificationPolicyAccessGranted) {
            // Launch DND access settings if not granted
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.appContext.startActivity(intent)
            return ToolResult(
                false,
                "Do Not Disturb access has not been granted to Jarvis yet. I have opened the Do Not Disturb permission settings on your screen, sir. Please toggle Jarvis on.",
            )
        }

        val targetFilter = when (mode) {
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "silence", "total_silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "off", "normal", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            else -> return ToolResult(false, "Invalid DND mode: $mode. Use 'priority', 'alarms', 'silence', 'off', or 'status'.")
        }

        nm.setInterruptionFilter(targetFilter)
        return ToolResult(true, "Do Not Disturb has been set to: $mode.")
    }
}

/**
 * Inspects or sets screen brightness level or auto-brightness mode.
 */
class ScreenBrightnessTool : Tool {
    override val definition = ToolDefinition(
        name = "set_screen_brightness",
        description = "Inspects or adjusts the display brightness (level 0 to 255, percentage 0 to 100, or auto mode).",
        parameters = objectSchema(
            properties = mapOf(
                "level" to intProperty(description = "Brightness percentage from 0 to 100, or -1 for query only."),
                "auto" to booleanProperty(description = "Set to true to enable auto-brightness, false for manual."),
            ),
            required = emptyList(),
        ),
    )

    override val label: String = "Screen Brightness"
    override val category: ToolCategory = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val cr = context.appContext.contentResolver
        val currentLevel = try {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 128 }
        val currentMode = try {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE)
        } catch (_: Exception) { Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }
        val isAuto = currentMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val currentPct = (currentLevel * 100 / 255)

        val targetLevel = arguments.argInt("level")
        val targetAuto = arguments.argBoolean("auto")

        if (targetLevel == null && targetAuto == null) {
            return ToolResult(
                true,
                "Current screen brightness: $currentPct% (${if (isAuto) "Adaptive/Auto" else "Manual"}).",
            )
        }

        if (!Settings.System.canWrite(context.appContext)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.appContext.startActivity(intent)
            return ToolResult(
                false,
                "Modifying system brightness requires 'Modify system settings' permission. I have opened the settings screen for you, sir.",
            )
        }

        if (targetAuto != null) {
            val modeVal = if (targetAuto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, modeVal)
        }

        if (targetLevel != null && targetLevel >= 0) {
            val raw = (targetLevel.coerceIn(0, 100) * 255 / 100)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, raw)
            return ToolResult(true, "Screen brightness adjusted to $targetLevel%.")
        }

        return ToolResult(true, "Screen brightness mode updated.")
    }
}

/**
 * Inspects detailed application package metadata: version, install time, target SDK, and status.
 */
class AppInspectorTool : Tool {
    override val definition = ToolDefinition(
        name = "inspect_app",
        description = "Inspects deep package metadata for an installed application: package name, version, first install date, last update date, and permissions.",
        parameters = objectSchema(
            properties = mapOf(
                "appNameOrPackage" to stringProperty(description = "App name (e.g. 'YouTube', 'WhatsApp') or package name (e.g. 'com.google.android.youtube')."),
            ),
            required = listOf("appNameOrPackage"),
        ),
    )

    override val label: String = "App Inspector"
    override val category: ToolCategory = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("appNameOrPackage")?.trim() ?: ""
        if (query.isBlank()) return ToolResult(false, "Please specify an app name or package.")

        val pm = context.appContext.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        // Fuzzy match by label or package name
        val matched = packages.firstOrNull { it.packageName.equals(query, ignoreCase = true) }
            ?: packages.firstOrNull {
                val label = pm.getApplicationLabel(it.applicationInfo ?: return@firstOrNull false).toString()
                label.contains(query, ignoreCase = true)
            }
            ?: return ToolResult(false, "No installed application matching '$query' was found.")

        val appInfo = matched.applicationInfo ?: return ToolResult(false, "Could not read application metadata.")
        val label = pm.getApplicationLabel(appInfo).toString()
        val pkgName = matched.packageName
        val verName = matched.versionName ?: "Unknown"
        val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) matched.longVersionCode else matched.versionCode.toLong()
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isEnabled = appInfo.enabled

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val installTime = sdf.format(Date(matched.firstInstallTime))
        val updateTime = sdf.format(Date(matched.lastUpdateTime))
        val permissionsCount = matched.requestedPermissions?.size ?: 0

        val sb = StringBuilder()
        sb.append("App Inspector: $label ($pkgName)\n")
        sb.append("• Version: $verName (build $verCode) | Target SDK: ${appInfo.targetSdkVersion}\n")
        sb.append("• Installed: $installTime | Updated: $updateTime\n")
        sb.append("• Type: ${if (isSystem) "System App" else "User / Sideloaded App"} | Enabled: $isEnabled\n")
        sb.append("• Permissions requested: $permissionsCount\n")

        return ToolResult(true, sb.toString())
    }
}
