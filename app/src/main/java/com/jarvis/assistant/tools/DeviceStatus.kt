package com.jarvis.assistant.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.jarvis.assistant.brain.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A real reading of this phone, used by the status tool and the HUD panels. */
data class DeviceStatus(
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTemperatureC: Float?,
    val network: String,
    val networkConnected: Boolean,
    val storageUsedPercent: Int,
    val storageFreeGb: Double,
    val memoryUsedPercent: Int,
    val musicActive: Boolean,
    val ringerMode: String,
)

fun readDeviceStatus(context: Context): DeviceStatus {
    val batteryIntent = context.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        Context.RECEIVER_NOT_EXPORTED,
    )
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val temperature = batteryIntent
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeIf { it != Int.MIN_VALUE }
        ?.div(10f)

    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val capabilities = connectivityManager
        ?.getNetworkCapabilities(connectivityManager.activeNetwork)
    val network = when {
        capabilities == null -> "Offline"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Connected"
    }

    val storage = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val usedPercent = if (total > 0) (((total - free) * 100) / total).toInt() else 0
        usedPercent to (free / 1_073_741_824.0)
    }.getOrDefault(0 to 0.0)

    val memory = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val total = info.totalMem
        if (total > 0) (((total - info.availMem) * 100) / total).toInt() else 0
    }.getOrDefault(0)

    val audio = context.getSystemService(AudioManager::class.java)
    val ringer = when (audio?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else -> "Sound on"
    }

    return DeviceStatus(
        batteryPercent = percent,
        charging = charging,
        batteryTemperatureC = temperature,
        network = network,
        networkConnected = capabilities != null,
        storageUsedPercent = storage.first,
        storageFreeGb = storage.second,
        memoryUsedPercent = memory,
        musicActive = audio?.isMusicActive == true,
        ringerMode = ringer,
    )
}

class DeviceStatusTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "get_device_status",
        description = "Read the phone's battery level, charging state, network, storage, " +
            "memory use, ringer mode and whether media is playing.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("additionalProperties", false)
        },
    )

    override val label = "Device status"
    override val category = ToolCategory.INFO

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val status = readDeviceStatus(context.appContext)
        val summary = buildString {
            append("Battery ${status.batteryPercent}%")
            if (status.charging) append(" (charging)")
            status.batteryTemperatureC?.let { append(", ${it.toInt()}°C") }
            append(". Network: ${status.network}.")
            append(" Storage ${status.storageUsedPercent}% used, ${"%.1f".format(status.storageFreeGb)} GB free.")
            append(" Memory ${status.memoryUsedPercent}% used.")
            append(" Ringer: ${status.ringerMode}.")
            append(if (status.musicActive) " Media is playing." else " Nothing is playing.")
        }
        return ToolResult(true, summary)
    }
}
