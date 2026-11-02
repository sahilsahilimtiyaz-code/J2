package com.jarvis.assistant.tools

import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenController
import com.jarvis.assistant.brain.ToolDefinition
import com.jarvis.assistant.brain.arrayOrNull
import com.jarvis.assistant.brain.parseJsonObject
import com.jarvis.assistant.brain.stringOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val YOUTUBE_PACKAGE = "com.google.android.youtube"

class YouTubeSearchTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "search_youtube",
        description = "Play a song or video on YouTube. Resolves the best matching result, opens " +
            "the video player directly, verifies that playback started, and taps Play when the " +
            "player opens paused. Use this for requests such as \"play Starboy\" or \"play lo-fi beats\".",
        parameters = objectSchema(
            required = listOf("query"),
            properties = mapOf(
                "query" to stringProperty("The song or video title/query to play on YouTube."),
            ),
        ),
    )

    override val label = "YouTube play & search"
    override val category = ToolCategory.MEDIA

    override fun availability(context: Context): ToolAvailability =
        if (isPackageInstalled(context, YOUTUBE_PACKAGE)) {
            ToolAvailability("YouTube app", true)
        } else {
            ToolAvailability("Browser", true)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("query")?.trim()
            ?: return ToolResult(false, "Tell me what song or video to play on YouTube.")
        if (query.isBlank()) {
            return ToolResult(false, "Tell me what song or video to play on YouTube.")
        }

        val appContext = context.appContext
        val youtubeInstalled = isPackageInstalled(appContext, YOUTUBE_PACKAGE)
        val controller = JarvisAccessibilityService.controller()

        // 1. Resolve the top result so we can deep-link straight into the video
        //    player instead of stopping on a search results page.
        val resolvedVideo = withContext(Dispatchers.IO) { resolveTopYouTubeVideo(query) }
        if (resolvedVideo != null) {
            val watchUrl = "https://www.youtube.com/watch?v=${resolvedVideo.videoId}"
            if (youtubeInstalled && launchDirectVideo(appContext, resolvedVideo.videoId)) {
                val verified = awaitPlayback(appContext, controller)
                val displayTitle = resolvedVideo.title.ifBlank { query }
                return ToolResult(
                    true,
                    if (verified) {
                        "Now playing \"$displayTitle\" on YouTube and playback is active. Inform the owner now."
                    } else {
                        "Opened \"$displayTitle\" on YouTube, but I could not confirm that audio started. If it is paused, tell me to tap play."
                    },
                )
            }
            if (!youtubeInstalled) {
                val displayTitle = resolvedVideo.title.ifBlank { query }
                return openUrl(
                    appContext,
                    watchUrl,
                    "Opened \"$displayTitle\" on YouTube in the browser. Inform the owner now.",
                )
            }
        }

        // 2. Fallback: open the YouTube app's search results and use accessibility
        //    to tap the first card, retrying while the results animate in.
        if (youtubeInstalled && openYouTubeSearch(appContext, query)) {
            if (controller != null) {
                for (attempt in 0 until 3) {
                    delay(if (attempt == 0) 1_700L else 1_150L)
                    val result = controller.tapFirstYouTubeVideoVerified(query)
                    if (result.verified) {
                        return ToolResult(
                            true,
                            "Played the top result for \"$query\" on YouTube (${result.verificationSignal}). Inform the owner now.",
                        )
                    }
                    if (attempt == 2 && result.success) {
                        return ToolResult(
                            true,
                            "Opened YouTube, searched for \"$query\" and tapped the top result, but I could not confirm playback. If nothing is playing, tell me to tap the video again.",
                        )
                    }
                }
            }
            return ToolResult(
                true,
                "Opened YouTube and searched for \"$query\". Playback did not start automatically. Tell the owner the results are open and offer to tap the first video.",
            )
        }

        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        return openUrl(appContext, url, "Opened YouTube for \"$query\" in the browser.")
    }

    private data class ResolvedYouTubeVideo(
        val videoId: String,
        val title: String,
    )

    private fun launchDirectVideo(context: Context, videoId: String): Boolean {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).setPackage(YOUTUBE_PACKAGE),
            Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)).setPackage(YOUTUBE_PACKAGE),
            Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    private fun openYouTubeSearch(context: Context, query: String): Boolean {
        val resultsUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse(resultsUrl)).setPackage(YOUTUBE_PACKAGE),
            Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE_PACKAGE)
                .putExtra(SearchManager.QUERY, query),
            Intent(Intent.ACTION_VIEW, Uri.parse(resultsUrl)),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /**
     * Watches the player after a direct launch, taps the play control once if the
     * video opened paused, and reports whether audio actually became active.
     */
    @Suppress("DEPRECATION")
    private suspend fun awaitPlayback(
        context: Context,
        controller: ScreenController?,
    ): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val wasPlaying = audio?.isMusicActive == true
        var tappedPlay = false
        repeat(7) { index ->
            delay(if (index == 0) 1_150L else 520L)
            if (!wasPlaying && audio?.isMusicActive == true) return true
            if (controller != null) {
                val state = withContext(Dispatchers.Main) { controller.captureFingerprint() }
                if (state.isAudioPlaying || state.hasPauseControl) return true
                if (!tappedPlay && state.hasPlayControl && !state.hasPauseControl) {
                    tappedPlay = true
                    controller.tapVerified("play video")
                }
            }
        }
        // A media-key play is a safe last resort and resumes a paused player even
        // when its overlay controls are hidden.
        runCatching {
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
        delay(800L)
        if (!wasPlaying && audio?.isMusicActive == true) return true
        val screen = controller ?: return false
        return withContext(Dispatchers.Main) {
            val state = screen.captureFingerprint()
            state.isAudioPlaying || state.hasPauseControl
        }
    }

    /**
     * Tries the InnerTube mobile/web search API first, then a YouTube search
     * page. Both parsers accept the first real videoRenderer.
     */
    private fun resolveTopYouTubeVideo(query: String): ResolvedYouTubeVideo? {
        innertubeSearch(query)?.let { return it }
        return scrapeSearch(query)
    }

    private fun innertubeSearch(query: String): ResolvedYouTubeVideo? = runCatching {
        val conn = (URL("https://www.youtube.com/youtubei/v1/search?key=$YOUTUBE_WEB_KEY").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            val payload = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20241101.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                }
                put("query", query)
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { reader -> readLimited(reader) }
            parseVideo(body, query)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun scrapeSearch(query: String): ResolvedYouTubeVideo? = runCatching {
        val searchUrl = "https://m.youtube.com/results?search_query=" + Uri.encode(query) + "&hl=en"
        val conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 5_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val html = conn.inputStream.bufferedReader().use { reader -> readLimited(reader) }
            parseVideo(html, query)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun readLimited(reader: java.io.Reader): String {
        val buffer = CharArray(32_768)
        val builder = StringBuilder()
        var total = 0
        while (total < 900_000) {
            val read = reader.read(buffer)
            if (read <= 0) break
            builder.append(buffer, 0, read)
            total += read
        }
        return builder.toString()
    }

    private fun parseVideo(body: String, fallbackTitle: String): ResolvedYouTubeVideo? {
        val videoId = Regex("\"videoRenderer\"\\s*:\\s*\\{\\s*\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"")
            .find(body)?.groupValues?.get(1)
            ?: Regex("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"").find(body)?.groupValues?.get(1)
            ?: return null
        val title = Regex("\"title\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"([^\"]+)\"")
            .find(body)?.groupValues?.get(1)
            ?: Regex("\"title\"\\s*:\\s*\\{\\s*\"simpleText\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1)
        return ResolvedYouTubeVideo(
            videoId = videoId,
            title = decodeJsonText(title.orEmpty()).ifBlank { fallbackTitle },
        )
    }

    private fun decodeJsonText(raw: String): String = raw
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\u003c", "<")
        .replace("\\u003e", ">")
        .trim()

    private companion object {
        // Public web client key used by youtube.com itself. It is not a secret and
        // is only used for a read-only search to find a video id; if YouTube ever
        // rotates it, the HTML fallback above takes over.
        const val YOUTUBE_WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    }
}

/**
 * Real in-app web search: fetches live results over DuckDuckGo, parses the top
 * five hits (title, snippet, link) and hands them to Jarvis to answer directly,
 * with no browser needed. Opening the browser is only a last-resort fallback
 * when the network is unavailable, and the owner hates that default, so it only
 * triggers on an explicit ask.
 */
class WebSearchTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "web_search",
        description = "Search the web IN THIS APP for a query (for example \"weather in Mumbai\" or " +
            "\"latest Pixel news\") and give me the results as text so I can answer directly. " +
            "Do not open a browser or Google for this.",
        parameters = objectSchema(
            required = listOf("query"),
            properties = mapOf(
                "query" to stringProperty("The search query."),
            ),
        ),
    )

    override val label = "Web search"
    override val category = ToolCategory.MEDIA

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("query")?.trim()
            ?: return ToolResult(false, "Tell me what to search the web for.")
        if (query.isBlank()) {
            return ToolResult(false, "Tell me what to search the web for.")
        }

        val results = withContext(Dispatchers.IO) { searchDuckDuckGo(query) }
        if (results.isNotEmpty()) {
            val listed = results
                .take(5)
                .mapIndexed { index, hit ->
                    val safeTitle = com.jarvis.assistant.security.SecurityShield.sanitizeUntrustedText(
                        hit.title,
                        "web_search",
                    )
                    val safeSnippet = com.jarvis.assistant.security.SecurityShield.sanitizeUntrustedText(
                        hit.snippet.ifBlank { "(no snippet available)" },
                        "web_search",
                    )
                    "${index + 1}. $safeTitle — $safeSnippet\n   ${hit.url}"
                }
                .joinToString("\n")
            val text = buildString {
                appendLine("Live web search results for \"$query\":")
                appendLine(listed)
                appendLine(
                    "Summarize these results so the owner hears the answer; " +
                        "no browser is open and none should be opened.",
                )
            }
            return ToolResult(true, text.take(4_000))
        }

        // Network path failed (offline, blocked, or both mirrors down).
        return ToolResult(
            false,
            "I could not fetch live results for \"$query\" right now — the network may be down " +
                "or the search service refused the request. Ask me again shortly, or say " +
                "\"open Google\" if you want to search in the browser yourself.",
        )
    }
}

/** One parsed DuckDuckGo result. */
private data class WebHit(
    val title: String,
    val snippet: String,
    val url: String,
)

/** Fetch-based DuckDuckGo search with two mirror strategies. */
private fun searchDuckDuckGo(query: String): List<WebHit> {
    val encoded = java.net.URLEncoder.encode(query.take(200), Charsets.UTF_8.name())
    fetchHtml(
        "https://html.duckduckgo.com/html/?q=$encoded",
        userAgent = USER_AGENT_MOBILE,
        timeoutMs = 6_500,
    )?.let { html -> parseDuckHtmlResults(html).takeIf { it.isNotEmpty() }?.let { return it } }

    fetchHtml(
        "https://lite.duckduckgo.com/lite/?q=$encoded",
        userAgent = USER_AGENT_MOBILE,
        timeoutMs = 6_500,
    )?.let { html -> parseDuckLiteResults(html).takeIf { it.isNotEmpty() }?.let { return it } }

    // Instant-answer API as a final web-data source.
    runCatching {
        val json = fetchHtml(
            "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1",
            userAgent = USER_AGENT_APP,
            timeoutMs = 5_000,
        ) ?: return@runCatching
        val root = com.jarvis.assistant.brain.parseJsonObject(json) ?: return@runCatching
        val hits = mutableListOf<WebHit>()
        root.stringOrNull("AbstractText")?.takeIf { it.isNotBlank() }?.let { abstract ->
            hits += WebHit(
                title = root.stringOrNull("Heading")?.takeIf { it.isNotBlank() } ?: query,
                snippet = abstract,
                url = root.stringOrNull("AbstractURL")?.takeIf { it.isNotBlank() }
                    ?: "https://duckduckgo.com/?q=${encoded}",
            )
        }
        root.arrayOrNull("RelatedTopics")?.filterIsInstance<JsonObject>()?.take(4)?.forEach { topic ->
            val text = topic.stringOrNull("Text")?.takeIf { it.isNotBlank() } ?: return@forEach
            val url = topic.stringOrNull("FirstURL")?.takeIf { it.isNotBlank() } ?: ""
            hits += WebHit(
                title = text.substringBefore(" - ").take(90),
                snippet = text.take(320),
                url = url,
            )
        }
        if (hits.isNotEmpty()) {
            return hits
        }
    }

    return emptyList()
}

private const val USER_AGENT_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124" +
        ".0.6367.82 Mobile Safari/537.36"
private const val USER_AGENT_APP = "Jarvis/0.3 (Android private assistant)"

private fun fetchHtml(url: String, userAgent: String, timeoutMs: Int): String? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        setRequestProperty("Accept", "text/html,application/xhtml+xml")
        instanceFollowRedirects = true
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) return@runCatching null
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

/** Extracts entity-decoded text and strips tags for snippet fields. */
private fun htmlText(raw: String): String = raw
    .replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")
    .replace("\\s+".toRegex(), " ")
    .trim()

/** html.duckduckgo.com results. */
private fun parseDuckHtmlResults(html: String): List<WebHit> = html
    .split(Regex("""<a[^>]+class="result__a""""))
    .drop(1)
    .mapNotNull { chunk ->
        val titleBody = chunk.substringBefore("</a>")
        val href = Regex("""href="([^"]+)"""").find(titleBody)?.groupValues?.get(1)
            ?: return@mapNotNull null
        val title = htmlText(titleBody.substringAfter(">"))
        val snippetBody = chunk.substringAfter("result__snippet", "").let {
            Regex("</a>(.*?)</div>").find(it)?.groupValues?.get(1)
                ?: chunk.substringAfter("</a>", "")
        }
        val snippet = htmlText(snippetBody).take(320)
        if (title.isBlank()) return@mapNotNull null
        WebHit(title.take(140), snippet, cleanDuckUrl(href))
    }

/** lite.duckduckgo.com results. */
private fun parseDuckLiteResults(html: String): List<WebHit> {
    val hits = mutableListOf<WebHit>()
    Regex("""<a[^>]+class="result-link"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""")
        .findAll(html)
        .forEach { match ->
            val title = htmlText(match.groupValues[2])
            if (title.isBlank()) return@forEach
            hits += WebHit(title.take(140), "", cleanDuckUrl(match.groupValues[1]))
        }
    // Attach following snippets where lite provides them.
    val snippets = Regex("""<td[^>]*class="result-snippet"[^>]*>(.*?)</td>""")
        .findAll(html)
        .map { htmlText(it.groupValues[1]).take(320) }
        .toList()
    return hits.mapIndexed { index, hit ->
        (snippets.getOrNull(index)?.let { hit.copy(snippet = it) } ?: hit)
    }
}

/** DuckDuckGo wraps outbound links in /l/?uddg=... — unwrap them. */
private fun cleanDuckUrl(href: String): String {
    val decoded = if (href.startsWith("//duckduckgo.com/l/") || href.contains("/l/?uddg=")) {
        java.net.URLDecoder.decode(
            Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1).orEmpty(),
            Charsets.UTF_8.name(),
        )
    } else {
        href
    }
    return when {
        decoded.startsWith("//") -> "https:$decoded"
        decoded.startsWith("http://") || decoded.startsWith("https://") -> decoded
        else -> "https://duckduckgo.com$decoded"
    }
}

@Suppress("DEPRECATION")
class MediaControlTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "control_media",
        description = "Play, pause, or skip the media that is currently playing on the phone.",
        parameters = objectSchema(
            required = listOf("action"),
            properties = mapOf(
                "action" to stringProperty(
                    "What to do with the current media.",
                    enum = listOf("play_pause", "play", "pause", "next", "previous", "stop"),
                ),
            ),
        ),
    )

    override val label = "Media control"
    override val category = ToolCategory.MEDIA

    override fun availability(context: Context): ToolAvailability {
        val audio = context.getSystemService(AudioManager::class.java)
        return if (audio?.isMusicActive == true) {
            ToolAvailability("Playing", true)
        } else {
            ToolAvailability("Ready", true)
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val action = arguments.argString("action")?.lowercase() ?: "play_pause"
        val keyCode = when (action) {
            "play", "pause", "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult(false, "I do not know the media action \"$action\".")
        }

        val audio = context.appContext.getSystemService(AudioManager::class.java)
            ?: return ToolResult(false, "Audio is unavailable on this device.")

        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

        val label = when (action) {
            "next", "skip" -> "Skipped to the next track."
            "previous", "back" -> "Went back a track."
            "stop" -> "Stopped playback."
            else -> "Toggled playback."
        }
        return ToolResult(true, label)
    }
}

class VolumeTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "set_volume",
        description = "Set the phone volume, either to an exact percentage or one step up/down.",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "level" to intProperty("Target volume as a percentage from 0 to 100."),
                "direction" to stringProperty(
                    "Use \"up\" or \"down\" instead of a level to step the volume.",
                    enum = listOf("up", "down"),
                ),
                "stream" to stringProperty(
                    "Which volume to change. Defaults to media.",
                    enum = listOf("media", "ring", "alarm", "notification"),
                ),
            ),
        ),
    )

    override val label = "Volume"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val audio = context.appContext.getSystemService(AudioManager::class.java)
            ?: return ToolResult(false, "Audio is unavailable on this device.")

        val stream = when (arguments.argString("stream")?.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(stream)
        val current = audio.getStreamVolume(stream)
        val target = when {
            arguments.argInt("level") != null ->
                (max * arguments.argInt("level")!!.coerceIn(0, 100) + 50) / 100
            arguments.argString("direction")?.lowercase() == "down" -> current - 1
            arguments.argString("direction")?.lowercase() == "up" -> current + 1
            else -> return ToolResult(false, "Tell me the volume you want, or say louder or quieter.")
        }.coerceIn(0, max)

        runCatching { audio.setStreamVolume(stream, target, 0) }
            .getOrElse { error ->
                return ToolResult(false, "Android would not change the volume: ${error.message}.")
            }

        val percent = if (max > 0) (target * 100) / max else 0
        return ToolResult(true, "Volume set to $percent%.")
    }
}

class RingerTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "set_ringer_mode",
        description = "Switch the phone between sound, vibrate and silent.",
        parameters = objectSchema(
            required = listOf("mode"),
            properties = mapOf(
                "mode" to stringProperty(
                    "The ringer mode to switch to.",
                    enum = listOf("sound", "vibrate", "silent"),
                ),
            ),
        ),
    )

    override val label = "Ringer mode"
    override val category = ToolCategory.DEVICE

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val mode = arguments.argString("mode")?.lowercase()
            ?: return ToolResult(false, "Tell me whether to use sound, vibrate or silent.")

        val appContext = context.appContext
        val audio = appContext.getSystemService(AudioManager::class.java)
            ?: return ToolResult(false, "Audio is unavailable on this device.")
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)

        val target = when (mode) {
            "sound", "normal", "ring" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
            else -> return ToolResult(false, "I do not know the ringer mode \"$mode\".")
        }

        if (target == AudioManager.RINGER_MODE_SILENT &&
            notificationManager?.isNotificationPolicyAccessGranted != true
        ) {
            return ToolResult(
                false,
                "Android only lets me go fully silent with Do Not Disturb access. Open " +
                    "Settings, Notifications, Do Not Disturb access and allow Jarvis, " +
                    "or ask me to switch to vibrate instead.",
            )
        }

        runCatching { audio.ringerMode = target }
            .getOrElse { error ->
                return ToolResult(false, "Android refused the ringer change: ${error.message}.")
            }

        val label = when (target) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "sound"
        }
        return ToolResult(true, "Ringer set to $label.")
    }
}

object TorchState {
    @Volatile
    var isOn: Boolean = false
}

class FlashlightTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "control_flashlight",
        description = "Turn the phone torch (flashlight) on or off. With no argument it toggles.",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "on" to booleanProperty("true to turn the torch on, false to turn it off."),
            ),
        ),
    )

    override val label = "Flashlight"
    override val category = ToolCategory.DEVICE

    override fun availability(context: Context): ToolAvailability =
        if (flashCameraId(context) != null) {
            ToolAvailability(if (TorchState.isOn) "On" else "Ready", true)
        } else {
            ToolAvailability("No flash", false)
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val appContext = context.appContext
        val manager = appContext.getSystemService(CameraManager::class.java)
            ?: return ToolResult(false, "This phone does not expose a torch.")
        val cameraId = flashCameraId(appContext)
            ?: return ToolResult(false, "This phone has no flashlight.")

        val target = arguments.argBoolean("on") ?: !TorchState.isOn

        fun apply(): Boolean = runCatching {
            manager.setTorchMode(cameraId, target)
            true
        }.getOrDefault(false)

        if (!apply()) {
            val granted = context.permissions.ensure(
                android.Manifest.permission.CAMERA,
                "The torch needs the Camera permission on some phones.",
            )
            if (!granted || !apply()) {
                return ToolResult(
                    false,
                    "The torch is unavailable. Close other camera apps and allow the " +
                        "Camera permission in Settings, Permissions.",
                )
            }
        }

        TorchState.isOn = target
        return ToolResult(true, if (target) "Torch on." else "Torch off.")
    }
}

internal fun flashCameraId(context: Context): String? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    val ids = runCatching { manager.cameraIdList }.getOrElse { return null }
    for (id in ids) {
        val hasFlash = runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        }.getOrNull()
        if (hasFlash == true) return id
    }
    return null
}

internal fun isPackageInstalled(context: Context, packageName: String): Boolean =
    runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

internal fun openUrl(context: Context, url: String, successMessage: String): ToolResult {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }
        .fold(
            onSuccess = { ToolResult(true, successMessage) },
            onFailure = { error ->
                ToolResult(false, "No app could open that link: ${error.message}.")
            },
        )
}
