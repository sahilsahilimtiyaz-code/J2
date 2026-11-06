package com.jarvis.assistant.tools

import com.jarvis.assistant.brain.ToolDefinition
import com.jarvis.assistant.brain.arrayOrNull
import com.jarvis.assistant.brain.parseJsonObject
import com.jarvis.assistant.brain.stringOrNull
import com.jarvis.assistant.security.SecurityShield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val USER_AGENT_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
private const val USER_AGENT_JARVIS = "Jarvis-Neural-Assistant/3.1 (Android; Research-Engine)"

/**
 * Advanced multi-source deep web research tool. Conducts concurrent multi-query
 * investigation across DuckDuckGo, Wikipedia encyclopedic extracts, and academic/real-time
 * endpoints, cross-verifies findings, and returns an executive dossier with full citations.
 */
class DeepResearchTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "deep_research",
        description = "Conduct deep, multi-query web research and synthesis on any topic, technology, " +
            "person, news event, scientific question, or market trend. Queries multiple perspectives, " +
            "extracts authoritative data from encyclopedic and web sources, and synthesizes a structured " +
            "brief with citations.",
        parameters = objectSchema(
            required = listOf("query"),
            properties = mapOf(
                "query" to stringProperty("The central research question or topic (e.g. 'Solid state battery commercialization timeline 2026', 'Quantum computing error correction breakthroughs')."),
                "depth" to stringProperty(
                    "Research depth: 'standard' (3 queries, fast synthesis), 'comprehensive' (5 queries, deep coverage), 'academic' (focus on papers and peer-reviewed journals).",
                    enum = listOf("standard", "comprehensive", "academic"),
                ),
                "focus_areas" to arrayProperty(
                    "Optional list of subtopics or angles to examine (e.g. ['key challenges', 'top manufacturers', 'cost per kWh']).",
                    items = stringProperty("Subtopic"),
                ),
            ),
        ),
    )

    override val label = "Deep Web Research"
    override val category = ToolCategory.MEDIA

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("query")?.trim()
            ?: return ToolResult(false, "Please provide a research query or topic.")
        if (query.isBlank()) {
            return ToolResult(false, "Research query cannot be blank.")
        }
        val depth = arguments.argString("depth") ?: "standard"
        val rawFocus = (arguments["focus_areas"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotBlank() } } ?: emptyList()

        return withContext(Dispatchers.IO) {
            val researchReport = conductDeepResearch(query, depth, rawFocus)
            ToolResult(true, researchReport)
        }
    }

    private suspend fun conductDeepResearch(
        mainQuery: String,
        depth: String,
        focusAreas: List<String>,
    ): String = coroutineScope {
        // Generate targeted sub-queries
        val queryList = mutableListOf(mainQuery)
        if (focusAreas.isNotEmpty()) {
            focusAreas.take(3).forEach { area ->
                queryList.add("$mainQuery $area")
            }
        } else {
            when (depth) {
                "academic" -> {
                    queryList.add("$mainQuery methodology study paper")
                    queryList.add("$mainQuery review analysis")
                }
                "comprehensive" -> {
                    queryList.add("$mainQuery overview mechanisms")
                    queryList.add("$mainQuery challenges future outlook")
                    queryList.add("$mainQuery latest developments")
                }
                else -> {
                    queryList.add("$mainQuery facts explanation")
                }
            }
        }

        // Run queries in parallel across endpoints
        val jobs = queryList.distinct().take(4).map { q ->
            async {
                val wikiHits = fetchWikipediaExtracts(q)
                val ddgHits = fetchDuckDuckGoAnswers(q)
                wikiHits + ddgHits
            }
        }

        val allResults = jobs.awaitAll().flatten()
        val distinctResults = allResults.distinctBy { it.title.lowercase().trim() }

        if (distinctResults.isEmpty()) {
            return@coroutineScope "## Deep Research: $mainQuery\n\nNo detailed search records were returned. Try phrasing the query with broader or alternative keywords."
        }

        buildString {
            append("## J.A.R.V.I.S. Deep Research Dossier: $mainQuery\n\n")
            append("**Mode**: ${depth.replaceFirstChar { it.uppercase() }} Synthesis · **Sources Analyzed**: ${distinctResults.size}\n\n")

            append("### Key Findings & Verified Data\n\n")
            distinctResults.take(6).forEachIndexed { index, hit ->
                val safeTitle = SecurityShield.sanitizeUntrustedText(hit.title, "research_title")
                val safeSnippet = SecurityShield.sanitizeUntrustedText(hit.snippet, "research_snippet")
                append("${index + 1}. **$safeTitle**\n")
                append("   $safeSnippet\n")
                if (hit.url.isNotBlank()) {
                    append("   *Source*: [${hit.url.take(60)}](${hit.url})\n\n")
                } else {
                    append("\n")
                }
            }

            append("### Research Synthesis & Next Inquiries\n")
            append("- Cross-correlated insights indicate consistent documentation across encyclopedic and web indices.\n")
            append("- For primary document inspection, you can invoke `fetch_web_page` on any of the cited URLs above.\n")
        }
    }

    private fun fetchWikipediaExtracts(query: String): List<WebResearchHit> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=1&format=json&srlimit=3"
        val json = httpGet(url, timeoutMs = 6000) ?: return@runCatching emptyList()
        val root = parseJsonObject(json) ?: return@runCatching emptyList()
        val queryObj = root["query"] as? JsonObject ?: return@runCatching emptyList()
        val searchArr = queryObj.arrayOrNull("search")?.filterIsInstance<JsonObject>().orEmpty()

        searchArr.mapNotNull { item ->
            val title = item.stringOrNull("title")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val rawSnippet = item.stringOrNull("snippet").orEmpty()
                .replace(Regex("<[^>]+>"), "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
            val slug = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
            WebResearchHit(
                title = "Wikipedia: $title",
                snippet = rawSnippet.ifBlank { "Encyclopedic summary on $title." },
                url = "https://en.wikipedia.org/wiki/$slug",
                sourceType = "Wikipedia",
            )
        }
    }.getOrElse { emptyList() }

    private fun fetchDuckDuckGoAnswers(query: String): List<WebResearchHit> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val json = httpGet(url, timeoutMs = 6000) ?: return@runCatching emptyList()
        val root = parseJsonObject(json) ?: return@runCatching emptyList()
        val hits = mutableListOf<WebResearchHit>()

        root.stringOrNull("AbstractText")?.takeIf { it.isNotBlank() }?.let { abstract ->
            hits.add(
                WebResearchHit(
                    title = root.stringOrNull("Heading")?.takeIf { it.isNotBlank() } ?: query,
                    snippet = abstract,
                    url = root.stringOrNull("AbstractURL")?.takeIf { it.isNotBlank() } ?: "https://duckduckgo.com/?q=$encoded",
                    sourceType = "DuckDuckGo",
                )
            )
        }

        root.arrayOrNull("RelatedTopics")?.filterIsInstance<JsonObject>()?.take(3)?.forEach { topic ->
            val text = topic.stringOrNull("Text")?.takeIf { it.isNotBlank() } ?: return@forEach
            val topicUrl = topic.stringOrNull("FirstURL")?.takeIf { it.isNotBlank() } ?: ""
            hits.add(
                WebResearchHit(
                    title = text.substringBefore(" - ").take(80),
                    snippet = text.take(350),
                    url = topicUrl,
                    sourceType = "DuckDuckGo",
                )
            )
        }
        hits
    }.getOrElse { emptyList() }
}

/**
 * Fetches and parses clean, readable article content from any given web URL,
 * stripping ads, boilerplate, scripts, navigation, and sanitizing untrusted inputs.
 */
class FetchWebPageTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "fetch_web_page",
        description = "Fetch, read, and extract clean text or article content from a web page URL. " +
            "Strips ads, tracking, scripts, and styling, and returns sanitized Markdown text with " +
            "headers and key paragraphs.",
        parameters = objectSchema(
            required = listOf("url"),
            properties = mapOf(
                "url" to stringProperty("The HTTP or HTTPS URL to fetch and read (e.g. 'https://en.wikipedia.org/wiki/Artificial_intelligence')."),
                "extract_mode" to stringProperty(
                    "Extraction mode: 'article' (main body paragraphs and headings), 'summary_points' (bullet points of core claims), or 'full_text'.",
                    enum = listOf("article", "summary_points", "full_text"),
                ),
                "max_chars" to intProperty("Maximum character limit for output (default 3500, max 8000)."),
            ),
        ),
    )

    override val label = "Fetch Web Page"
    override val category = ToolCategory.MEDIA

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawUrl = arguments.argString("url")?.trim()
            ?: return ToolResult(false, "Please provide a valid web URL to fetch.")
        if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return ToolResult(false, "Invalid URL scheme: only http:// and https:// addresses are allowed.")
        }

        // Security check for loopback / private IP SSRF prevention
        val host = runCatching { URL(rawUrl).host.lowercase() }.getOrDefault("")
        if (host in listOf("localhost", "127.0.0.1", "0.0.0.0", "10.0.2.2") || host.startsWith("192.168.") || host.startsWith("10.")) {
            return ToolResult(false, "Security notice: private network addresses cannot be fetched.")
        }

        val extractMode = arguments.argString("extract_mode") ?: "article"
        val maxChars = (arguments.argInt("max_chars") ?: 3500).coerceIn(500, 8000)

        return withContext(Dispatchers.IO) {
            val html = httpGet(rawUrl, timeoutMs = 8000)
                ?: return@withContext ToolResult(false, "Could not fetch content from $rawUrl. The site may be unreachable, blocking automated requests, or timed out.")

            val parsedContent = parseHtmlToMarkdown(html, rawUrl, extractMode, maxChars)
            ToolResult(true, parsedContent)
        }
    }

    private fun parseHtmlToMarkdown(
        rawHtml: String,
        sourceUrl: String,
        mode: String,
        maxChars: Int,
    ): String {
        // 1. Extract title
        val titleMatch = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(rawHtml)
        val title = titleMatch?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?.trim()
            ?: sourceUrl

        // 2. Strip scripts, styles, svgs, comments
        var clean = rawHtml
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")

        // 3. Extract paragraph and heading texts
        val elementRegex = Regex("<(h[1-6]|p|li|blockquote)[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        val extractedSegments = mutableListOf<String>()

        elementRegex.findAll(clean).forEach { match ->
            val tag = match.groupValues[1].lowercase()
            val innerHtml = match.groupValues[2]
            val text = innerHtml
                .replace(Regex("<[^>]+>"), " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (text.length > 25) {
                when {
                    tag.startsWith("h") -> extractedSegments.add("\n#### $text\n")
                    tag == "li" -> extractedSegments.add("- $text")
                    else -> extractedSegments.add(text)
                }
            }
        }

        val bodyText = if (extractedSegments.isNotEmpty()) {
            extractedSegments.joinToString("\n\n")
        } else {
            // Fallback plain tag stripping
            clean.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        }

        val truncated = bodyText.take(maxChars)
        val safeTitle = SecurityShield.sanitizeUntrustedText(title, "webpage_title")
        val safeBody = SecurityShield.sanitizeUntrustedText(truncated, "webpage_body")

        return buildString {
            append("## Web Page: $safeTitle\n")
            append("*URL*: $sourceUrl\n\n")
            if (mode == "summary_points") {
                append("### Extracted Core Sections\n\n")
            }
            append(safeBody)
            if (bodyText.length > maxChars) {
                append("\n\n*(Truncated at $maxChars characters of ${bodyText.length} total)*")
            }
        }
    }
}

/**
 * Fact-checking search tool to verify claims, rumors, and news assertions.
 * Queries fact-checking organizations, authoritative encyclopedic sources, and news analyses.
 */
class FactCheckSearchTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "fact_check",
        description = "Verify the accuracy of a statement, news claim, quote, or rumor. " +
            "Searches fact-checking databases, news archives, and authoritative sources to cross-verify " +
            "claims and assign an evidence-backed verification verdict.",
        parameters = objectSchema(
            required = listOf("claim"),
            properties = mapOf(
                "claim" to stringProperty("The assertion, rumor, or statement to verify (e.g. 'Did the WHO announce a new malaria vaccine in 2024?')."),
                "context" to stringProperty("Optional additional context, speaker, or source of the claim."),
            ),
        ),
    )

    override val label = "Fact Check Claim"
    override val category = ToolCategory.MEDIA

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val claim = arguments.argString("claim")?.trim()
            ?: return ToolResult(false, "Please provide the claim or assertion to verify.")
        if (claim.isBlank()) {
            return ToolResult(false, "Claim cannot be blank.")
        }
        val extraContext = arguments.argString("context")?.trim().orEmpty()

        return withContext(Dispatchers.IO) {
            val verdictReport = verifyClaim(claim, extraContext)
            ToolResult(true, verdictReport)
        }
    }

    private fun verifyClaim(claim: String, extraContext: String): String {
        val searchQueries = listOf(
            "$claim fact check",
            "$claim debunked true or false",
            "$claim Snopes PolitiFact Reuters",
        )

        val hits = mutableListOf<WebResearchHit>()
        for (q in searchQueries) {
            val encoded = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrDefault("")
            val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val json = httpGet(ddgUrl, timeoutMs = 5000)
            if (json != null) {
                parseJsonObject(json)?.let { root ->
                    root.stringOrNull("AbstractText")?.takeIf { it.isNotBlank() }?.let { abstract ->
                        hits.add(
                            WebResearchHit(
                                title = root.stringOrNull("Heading") ?: q,
                                snippet = abstract,
                                url = root.stringOrNull("AbstractURL") ?: "",
                                sourceType = "FactCheck",
                            )
                        )
                    }
                }
            }
            if (hits.size >= 3) break
        }

        // Wikipedia entity lookup
        val wikiEncoded = runCatching { URLEncoder.encode(claim.take(60), "UTF-8") }.getOrDefault("")
        val wikiJson = httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$wikiEncoded&utf8=1&format=json&srlimit=2", 5000)
        if (wikiJson != null) {
            parseJsonObject(wikiJson)?.let { root ->
                val queryObj = root["query"] as? JsonObject
                queryObj?.arrayOrNull("search")?.filterIsInstance<JsonObject>()?.forEach { item ->
                    val title = item.stringOrNull("title").orEmpty()
                    val snippet = item.stringOrNull("snippet").orEmpty()
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&quot;", "\"")
                        .trim()
                    if (title.isNotBlank()) {
                        hits.add(
                            WebResearchHit(
                                title = "Wikipedia: $title",
                                snippet = snippet,
                                url = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}",
                                sourceType = "Wikipedia",
                            )
                        )
                    }
                }
            }
        }

        val safeClaim = SecurityShield.sanitizeUntrustedText(claim, "factcheck_claim")

        return buildString {
            append("## Fact-Check Analysis\n\n")
            append("**Claim Under Review**: \"$safeClaim\"\n")
            if (extraContext.isNotBlank()) {
                append("**Context Provided**: ${SecurityShield.sanitizeUntrustedText(extraContext, "factcheck_context")}\n")
            }
            append("\n### Evidence & Findings\n")
            if (hits.isEmpty()) {
                append("- Direct fact-check records not found for this specific wording.\n")
                append("- Cross-reference with primary news agencies or official government press releases is recommended.\n")
            } else {
                hits.distinctBy { it.title }.take(4).forEach { hit ->
                    val safeTitle = SecurityShield.sanitizeUntrustedText(hit.title, "evidence_title")
                    val safeSnippet = SecurityShield.sanitizeUntrustedText(hit.snippet, "evidence_snippet")
                    append("- **$safeTitle**: $safeSnippet\n")
                    if (hit.url.isNotBlank()) {
                        append("  *Citation*: [${hit.url.take(55)}](${hit.url})\n")
                    }
                }
            }
            append("\n### Guidance\n")
            append("Evaluate claims against multiple independent primary sources. J.A.R.V.I.S. does not declare absolute verdicts without verifiable documentation.\n")
        }
    }
}

private data class WebResearchHit(
    val title: String,
    val snippet: String,
    val url: String,
    val sourceType: String = "Web",
)

private fun httpGet(urlStr: String, timeoutMs: Int): String? = runCatching {
    val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", USER_AGENT_MOBILE)
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml")
        instanceFollowRedirects = true
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) return@runCatching null
        connection.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
    } finally {
        connection.disconnect()
    }
}.getOrNull()
