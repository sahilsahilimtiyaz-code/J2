package com.jarvis.assistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The release manifest published by the Octa-Jarvis download site.
 *
 * The site is the single source of truth: the release workflow writes
 * `site/version.json`, GitHub Pages serves it, and every installed copy of the app
 * asks that file whether a newer build exists.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val apkUrl: String = "",
    val downloadPageUrl: String = "",
    val releaseNotes: String = "",
    val releasedAt: String = "",
    val sha256: String = "",
    /** Minimum supported build; anything older is told to update before continuing. */
    val minSupportedVersionCode: Long = 0L,
)

/** A newer build than the one installed. */
data class AvailableUpdate(
    val manifest: UpdateManifest,
    val currentVersionCode: Long,
) {
    val versionName: String get() = manifest.versionName.ifBlank { "a newer build" }
    val releaseNotes: String get() = manifest.releaseNotes.trim()
    val isMandatory: Boolean get() = manifest.minSupportedVersionCode > currentVersionCode
}

/** Pure rules kept separate from Android so they can be unit tested. */
object UpdateManifestRules {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(raw: String): UpdateManifest? {
        val manifest = runCatching { json.decodeFromString<UpdateManifest>(raw) }.getOrNull() ?: return null
        if (manifest.versionCode <= 0L) return null
        if (manifest.versionName.isBlank() && manifest.apkUrl.isBlank() && manifest.downloadPageUrl.isBlank()) return null
        return manifest
    }

    /** True when [manifest] describes a build that should replace [currentVersionCode]. */
    fun isUpdateAvailable(manifest: UpdateManifest, currentVersionCode: Long): Boolean =
        manifest.versionCode > currentVersionCode &&
            (manifest.apkUrl.isNotBlank() || manifest.downloadPageUrl.isNotBlank())

    /** Where a "Download" tap should go, preferring the direct APK. */
    fun resolveDownloadUrl(manifest: UpdateManifest, fallbackPage: String): String = when {
        manifest.apkUrl.isNotBlank() -> manifest.apkUrl
        manifest.downloadPageUrl.isNotBlank() -> manifest.downloadPageUrl
        else -> fallbackPage
    }
}

/**
 * Checks the Octa-Jarvis site for a newer build and remembers which version the
 * owner chose to skip, so the prompt appears once per release rather than constantly.
 */
class AppUpdateChecker(
    context: Context,
    baseClient: OkHttpClient,
) {
    companion object {
        /**
         * The manifest committed by the release workflow. This is the primary feed
         * because it works whether or not the Octa-Jarvis site is hosted on Pages.
         */
        const val PRIMARY_FEED_URL =
            "https://raw.githubusercontent.com/sahilsahilimtiyaz-code/J2/main/site/version.json"

        /** Same manifest served by the Octa-Jarvis site once Pages is enabled. */
        const val BACKUP_FEED_URL = "https://sahilsahilimtiyaz-code.github.io/J2/version.json"

        /** Human-facing download location. */
        const val DOWNLOAD_PAGE_URL = "https://github.com/sahilsahilimtiyaz-code/J2/releases/latest"

        private const val PREFS = "jarvis_updates"
        private const val KEY_SKIPPED = "skipped_version_code"
        private const val KEY_LAST_CHECK = "last_check_epoch_ms"
    }

    private val appContext = context.applicationContext
    private val client = baseClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentVersionCode(): Long = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    fun currentVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun skippedVersionCode(): Long = prefs.getLong(KEY_SKIPPED, 0L)

    fun skipVersion(versionCode: Long) {
        prefs.edit().putLong(KEY_SKIPPED, versionCode).apply()
    }

    fun lastCheckedAt(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    /**
     * Returns the available update, or null when the app is current, the owner
     * skipped this version, or the site could not be reached.
     */
    suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val current = currentVersionCode()
        for (url in listOf(PRIMARY_FEED_URL, BACKUP_FEED_URL)) {
            val manifest = fetchManifest(url) ?: continue
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (!UpdateManifestRules.isUpdateAvailable(manifest, current)) return@withContext null
            if (skippedVersionCode() >= manifest.versionCode) return@withContext null
            return@withContext AvailableUpdate(manifest = manifest, currentVersionCode = current)
        }
        null
    }

    fun downloadUrlFor(update: AvailableUpdate): String =
        UpdateManifestRules.resolveDownloadUrl(update.manifest, DOWNLOAD_PAGE_URL)

    /** Opens the APK download (or the Octa-Jarvis page) in the phone's browser. */
    fun openDownload(context: Context, update: AvailableUpdate): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrlFor(update)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private fun fetchManifest(url: String): UpdateManifest? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "OctaJarvis-Android-Updater/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            UpdateManifestRules.parse(response.body.string())
        }
    }.getOrNull()
}
