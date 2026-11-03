package com.jarvis.assistant.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

/**
 * TIER 1 Intent & Deep Link Registry.
 *
 * Provides direct deep-link and standard intent builders for core phone tasks,
 * avoiding blind coordinate guessing or fragile UI navigation whenever an intent exists.
 */
object AppRegistry {

    private const val TAG = "JARVIS_ACT"

    const val PACKAGE_WHATSAPP = "com.whatsapp"
    const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val PACKAGE_YOUTUBE = "com.google.android.youtube"
    const val PACKAGE_GOOGLE_MAPS = "com.google.android.apps.maps"
    const val PACKAGE_CHROME = "com.android.chrome"
    const val PACKAGE_SPOTIFY = "com.spotify.music"

    /**
     * Checks if a package is installed on the device.
     */
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * Creates a direct WhatsApp chat deep link intent:
     * https://wa.me/<digits>?text=<urlencoded>
     * Targets package com.whatsapp (fallback com.whatsapp.w4b).
     */
    fun createWhatsAppIntent(
        context: Context,
        digitsOnly: String,
        messageText: String,
    ): Intent {
        val cleanDigits = digitsOnly.filter { it.isDigit() }
        val encodedText = Uri.encode(messageText)
        val deepLinkUri = Uri.parse("https://wa.me/$cleanDigits?text=$encodedText")

        Log.d(TAG, "AppRegistry: Creating WhatsApp deep link intent for $cleanDigits")

        val targetPkg = when {
            isPackageInstalled(context, PACKAGE_WHATSAPP) -> PACKAGE_WHATSAPP
            isPackageInstalled(context, PACKAGE_WHATSAPP_BUSINESS) -> PACKAGE_WHATSAPP_BUSINESS
            else -> null
        }

        return Intent(Intent.ACTION_VIEW, deepLinkUri).apply {
            if (targetPkg != null) {
                setPackage(targetPkg)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates a YouTube search intent:
     * Intent.ACTION_SEARCH with package com.google.android.youtube and extra SearchManager.QUERY / "query".
     * Falls back to https://www.youtube.com/results?search_query=<q> if the native app is not installed.
     */
    fun createYouTubeSearchIntent(context: Context, query: String): Intent {
        val cleanQuery = query.trim()
        val hasYouTubeApp = isPackageInstalled(context, PACKAGE_YOUTUBE)

        Log.d(TAG, "AppRegistry: Creating YouTube search intent for '$cleanQuery' (native: $hasYouTubeApp)")

        if (hasYouTubeApp) {
            return Intent(Intent.ACTION_SEARCH).apply {
                setPackage(PACKAGE_YOUTUBE)
                putExtra(SearchManager.QUERY, cleanQuery)
                putExtra("query", cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // Web fallback
        val resultsUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery)
        return Intent(Intent.ACTION_VIEW, Uri.parse(resultsUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates a Maps navigation / search intent.
     */
    fun createMapsIntent(context: Context, destination: String): Intent {
        val cleanDest = destination.trim()
        val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(cleanDest))
        val hasMaps = isPackageInstalled(context, PACKAGE_GOOGLE_MAPS)

        val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            if (hasMaps) setPackage(PACKAGE_GOOGLE_MAPS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            return intent
        }

        // Web fallback
        val webMaps = "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(cleanDest)
        return Intent(Intent.ACTION_VIEW, Uri.parse(webMaps)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates a dialer intent for placing calls (requires owner confirmation first).
     */
    fun createDialerIntent(phoneNumber: String, directCall: Boolean = false): Intent {
        val clean = phoneNumber.filter { it.isDigit() || it == '+' }
        val action = if (directCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return Intent(action, Uri.parse("tel:" + Uri.encode(clean))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an SMS compose intent.
     */
    fun createSmsIntent(phoneNumber: String, message: String): Intent {
        val clean = phoneNumber.filter { it.isDigit() || it == '+' }
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(clean))).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an alarm intent using AlarmClock.
     */
    fun createAlarmIntent(hour: Int, minute: Int, message: String = "Jarvis Alarm"): Intent {
        return Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates a timer intent using AlarmClock.
     */
    fun createTimerIntent(seconds: Int, message: String = "Jarvis Timer"): Intent {
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates a settings screen intent.
     * Returns Pair(Intent, HumanReadableLabel).
     */
    fun createSettingsScreenIntent(screen: String): Pair<Intent, String> {
        val normalized = screen.lowercase().replace(" ", "_").trim()
        val (action, label) = when (normalized) {
            "wifi", "wi_fi", "internet", "network" ->
                Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
            "bluetooth", "bt" ->
                Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "sound", "volume", "audio" ->
                Settings.ACTION_SOUND_SETTINGS to "Sound"
            "display", "screen", "dark_mode", "brightness" ->
                Settings.ACTION_DISPLAY_SETTINGS to "Display"
            "battery", "battery_saver", "power" ->
                Settings.ACTION_BATTERY_SAVER_SETTINGS to "Battery"
            "apps", "applications", "installed_apps" ->
                Settings.ACTION_APPLICATION_SETTINGS to "Apps"
            "accessibility", "access" ->
                Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility"
            "location", "gps" ->
                Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location"
            "do_not_disturb", "dnd" ->
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS to "Do Not Disturb"
            "notification_access", "notifications" ->
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to "Notification Access"
            "developer", "developer_options" ->
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "Developer Options"
            "date_and_time", "date", "time" ->
                Settings.ACTION_DATE_SETTINGS to "Date & Time"
            else ->
                Settings.ACTION_SETTINGS to "Settings"
        }

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent to label
    }

    /**
     * Creates a music search intent via MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.
     */
    fun createMusicSearchIntent(query: String): Intent {
        return Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates a general browser web search intent.
     */
    fun createWebSearchIntent(query: String): Intent {
        return Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
