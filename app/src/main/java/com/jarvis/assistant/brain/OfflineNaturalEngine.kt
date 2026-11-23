package com.jarvis.assistant.brain

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin sanitization, math/unit computation, and natural-language response engine
 * for Jarvis's offline/on-device models.
 *
 * Guarantees that offline models NEVER reply with raw code blocks, JSON payloads,
 * LLM control tokens (`<think>`, `<|im_start|>`, `<tool_call>`), Markdown syntax,
 * URLs, or internal agent prompt directives unless the user explicitly asks for code.
 */
object NaturalResponseSanitizer {

    private val CONTROL_TOKEN_REGEX = Regex(
        """<\|[a-zA-Z0-9_:\-]+\|>|\[\|[a-zA-Z0-9_:\-]+\|\]|</?s>|<bos>|<eos>|\[/?INST\]|<<\/?SYS>>|<end_of_turn>|<start_of_turn>(?:model|user|system)?|<\|eot_id\|>|<\|end_of_text\|>|<\|start_header_id\|>.*?<\|end_header_id\|>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val JINJA_TEMPLATE_REGEX = Regex(
        """\{%.*?%\}|\{\{.*?\}\}""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val THINK_BLOCK_REGEX = Regex(
        """<(?:think|thought|reasoning|analysis|reflection|scratchpad)>.*?</(?:think|thought|reasoning|analysis|reflection|scratchpad)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val UNCLOSED_THINK_REGEX = Regex(
        """<(?:think|thought|reasoning|analysis|reflection|scratchpad)>.*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TOOL_CALL_BLOCK_REGEX = Regex(
        """<(?:tool_call|function_call|tool_response|observation)>.*?</(?:tool_call|function_call|tool_response|observation)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val UNCLOSED_TOOL_CALL_REGEX = Regex(
        """<(?:tool_call|function_call|tool_response|observation)>.*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PYTHON_TAG_REGEX = Regex(
        """<\|python_tag\|>.*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val INTERNAL_DIRECTIVE_REGEX = Regex(
        """\[(?:Action complete on phone|Action finished|BACKGROUND MONITORING OBSERVATION|System note|Tool result|BLOCKED_INJECTION_ATTEMPT)[^\]]*]""",
        RegexOption.IGNORE_CASE,
    )
    private val PROMPT_ECHO_LINE_REGEX = Regex(
        """(?im)^\s*(?:recent conversation context\s*:|current question\s*:|/(?:no_think|think))\s*$""",
    )
    private val CODE_FENCE_REGEX = Regex("""```([a-zA-Z0-9_+\-#]*)\s*\n?(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    private val UNCLOSED_CODE_FENCE_REGEX = Regex("""```([a-zA-Z0-9_+\-#]*)\s*\n?(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_TOOL_JSON_REGEX = Regex("""(?s)\{\s*"(?:name|tool|function|tool_name|call)"\s*:\s*"[^"]+".*?\}""")
    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
    private val RAW_URL_REGEX = Regex("""https?://\S+""")
    private val MARKDOWN_HEADING_REGEX = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
    private val TELEMETRY_BANNER_REGEX = Regex("""(?m)^\s*={3,}.*?={3,}\s*$""")

    private val CODE_LANGUAGES = setOf(
        "python", "py", "kotlin", "java", "js", "javascript", "ts", "typescript",
        "c", "cpp", "c++", "csharp", "cs", "go", "rust", "bash", "sh", "zsh",
        "sql", "xml", "html", "css", "json", "yaml", "yml", "php", "ruby",
    )

    fun userExplicitlyAskedForCode(userText: String): Boolean {
        val lower = userText.lowercase(Locale.ROOT)
        return Regex(
            """\b(write code|give me code|show me code|code snippet|sample code|example code|implement in|program in|script in|kotlin code|python code|java code|javascript code|html code|css code|sql query|regex for|function in|class in|debug this code|write a function|write a script|write a program)\b""",
        ).containsMatchIn(lower)
    }

    /**
     * Cleans raw output streamed from a local GGUF/loopback LLM so no control tokens,
     * chain-of-thought tags, tool-call JSON, template errors, syntax errors, or accidental
     * code/Markdown syntax leaks.
     * Returns empty string if the model emitted raw code/syntax-error/template garbage
     * when conversational prose was expected.
     */
    fun sanitizeModelOutput(raw: String, allowCode: Boolean = false): String {
        if (raw.isBlank()) return ""
        var text = raw
        text = THINK_BLOCK_REGEX.replace(text, " ")
        text = UNCLOSED_THINK_REGEX.replace(text, " ")
        text = TOOL_CALL_BLOCK_REGEX.replace(text, " ")
        text = UNCLOSED_TOOL_CALL_REGEX.replace(text, " ")
        text = PYTHON_TAG_REGEX.replace(text, " ")
        text = CONTROL_TOKEN_REGEX.replace(text, "")
        text = JINJA_TEMPLATE_REGEX.replace(text, "")
        text = INTERNAL_DIRECTIVE_REGEX.replace(text, "")
        text = PROMPT_ECHO_LINE_REGEX.replace(text, "")
        text = text.replace(Regex("(?im)^\\s*(?:assistant|jarvis|j\\.a\\.r\\.v\\.i\\.s\\.|ai|model|user|system)\\s*:\\s*"), "")

        if (!allowCode) {
            if (looksLikeSyntaxOrCodeError(text)) {
                return ""
            }
            text = CODE_FENCE_REGEX.replace(text) { m ->
                val lang = m.groupValues[1].lowercase(Locale.ROOT)
                val inner = m.groupValues[2].trim()
                when {
                    looksLikeJsonPayload(inner) -> extractProseFromJsonText(inner)
                    lang in CODE_LANGUAGES || looksMostlyLikeRawCode(inner) || looksLikeSyntaxOrCodeError(inner) -> ""
                    else -> inner
                }
            }
            text = UNCLOSED_CODE_FENCE_REGEX.replace(text) { m ->
                val lang = m.groupValues[1].lowercase(Locale.ROOT)
                val inner = m.groupValues[2].trim()
                when {
                    looksLikeJsonPayload(inner) -> extractProseFromJsonText(inner)
                    lang in CODE_LANGUAGES || looksMostlyLikeRawCode(inner) || looksLikeSyntaxOrCodeError(inner) || lang.isEmpty() -> ""
                    else -> inner
                }
            }
            text = INLINE_TOOL_JSON_REGEX.replace(text, "")
            if (looksLikeJsonPayload(text.trim())) {
                val extracted = extractProseFromJsonText(text.trim())
                text = if (looksLikeToolCallJson(text.trim()) && extracted.isBlank()) "" else extracted
            }
            text = stripMarkdownSyntax(text)
            val candidate = text.trim()
            if (looksLikeSyntaxOrCodeError(candidate) ||
                looksMostlyLikeRawCode(candidate) ||
                looksLikeDegenerateRepetition(candidate)
            ) {
                return ""
            }
        }

        return text
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /**
     * Detects when a small quantized model produced a programming/parser/template error
     * instead of a natural conversational answer.
     */
    fun looksLikeSyntaxOrCodeError(text: String): Boolean {
        if (text.isBlank()) return false
        val errorRegex = Regex(
            """(?i)\b(?:SyntaxError|IndentationError|NameError|TypeError|ValueError|AttributeError|KeyError|IndexError|ReferenceError|ParseError|CompileError|RuntimeError)\b|traceback\s*\(most recent call last\)|(?:invalid|unexpected)\s+syntax|syntax\s+error|unexpected\s+token|unexpected\s+end\s+of|unterminated\s+string|parse\s+error|jinja\w*\s+error|template\s+error|decode\s+failed""",
        )
        return errorRegex.containsMatchIn(text)
    }

    /**
     * Detects degenerate token-repetition loops common in tiny unpenalized quantized models.
     */
    fun looksLikeDegenerateRepetition(text: String): Boolean {
        if (text.length < 30) return false
        val sameWordRepeat = Regex("""(?i)\b(\w{2,})(?:\s+\1\b){5,}""")
        if (sameWordRepeat.containsMatchIn(text)) return true
        val phraseRepeat = Regex("""(?i)(\b\w+(?:\s+\w+){1,4}\b)(?:[,\s.!?;:]+\1){3,}""")
        return phraseRepeat.containsMatchIn(text)
    }

    /**
     * Detects weak non-answers from tiny offline models so [LocalLlmProvider] can replace
     * them with a verified offline answer whenever one exists.
     */
    fun isUnhelpfulRefusal(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return true
        return Regex(
            """^(?:i\s+(?:don't|do\s+not)\s+know\b|i'?m\s+sorry,?\s+i\s+(?:don't|do\s+not)\s+(?:know|have)|as\s+an\s+ai(?:\s+language)?\s+model\b|i\s+(?:cannot|can't|am\s+unable\s+to)\s+(?:answer|provide|help\s+with)\s+that\b|i\s+do\s+not\s+have\s+(?:access\s+to|information\s+about|that\s+information))""",
        ).containsMatchIn(lower)
    }

    private fun looksLikeToolCallJson(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return (lower.contains("\"name\"") || lower.contains("\"function\"") || lower.contains("\"tool\"")) &&
            (lower.contains("\"arguments\"") || lower.contains("\"parameters\"") || lower.contains("\"args\""))
    }

    fun looksMostlyLikeRawCode(text: String): Boolean {
        if (text.isBlank()) return false
        if (looksLikeSyntaxOrCodeError(text)) return true
        if (looksLikeToolCallJson(text)) return true

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false

        val strongSingleLineCodeRegex = Regex(
            """^(?:def\s+\w+\s*\(|class\s+\w+\s*[:({]|fun\s+\w+\s*\(|from\s+[\w.]+\s+import\b|import\s+[\w.]+|print\s*\(|println\s*\(|console\.log\s*\(|(?:val|var|const|let)\s+\w+\s*=|#include\b|#!/|\<\?php|\<script\b|SELECT\s+.+\s+FROM\b)""",
        )
        if (lines.size == 1) {
            val single = lines[0]
            if (strongSingleLineCodeRegex.containsMatchIn(single)) return true
            if (single.endsWith(";") && single.contains("=")) return true
        }

        val codeLineRegex = Regex(
            """^(?:def |class |fun |val |var |import |from |return |if\s*\(|for\s*\(|while\s*\(|public |private |const |let |function |#include|print\(|println\(|console\.log|//|/\*|\{|}|\[|]|<\?|\w+\s*=\s*[\[{'"\d])""",
        )
        val codeLineCount = lines.count { line ->
            codeLineRegex.containsMatchIn(line) || line.endsWith(";") || line.endsWith("{")
        }
        if (lines.size >= 2 && codeLineCount.toFloat() / lines.size >= 0.45f) {
            return true
        }
        val symbolCount = text.count { it in "{}[];<>`\\" }
        return text.length >= 20 && (symbolCount.toFloat() / text.length) > 0.18f
    }

    /**
     * Converts raw tool JSON / Markdown / diagnostic reports into clean, natural spoken J.A.R.V.I.S. prose.
     */
    fun sanitizeToolOutcome(
        rawToolContent: String,
        toolName: String? = null,
        ownerTitle: String = "sir",
    ): String {
        val trimmed = rawToolContent.trim()
        if (trimmed.isBlank()) return ""

        val unwrapped = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            extractJsonField(trimmed, "result")
                ?.ifBlank { extractJsonField(trimmed, "error") }
                ?.ifBlank { extractProseFromJsonText(trimmed) }
                ?: trimmed
        } else {
            trimmed
        }

        var text = INTERNAL_DIRECTIVE_REGEX.replace(unwrapped, "")
            .replace("Inform the owner now.", "", ignoreCase = true)
            .replace(
                "Summarize these results so the owner hears the answer; no browser is open and none should be opened.",
                "",
                ignoreCase = true,
            )
            .replace(
                "Use `fetch_web_page` on any specific source URL above if deeper inspection is needed.",
                "",
                ignoreCase = true,
            )
            .trim()

        // Strip telemetry banners and raw URLs
        text = TELEMETRY_BANNER_REGEX.replace(text, "")
        text = MARKDOWN_LINK_REGEX.replace(text, "$1")
        text = stripMarkdownSyntax(text)

        // Format search / research / fact-check / diagnostics cleanly into natural prose
        val lower = text.lowercase(Locale.ROOT)
        val isSearchLike = toolName in setOf("web_search", "deep_research", "fact_check", "fetch_web_page") ||
            lower.startsWith("live web search results for") ||
            lower.startsWith("deep research synthesis:") ||
            lower.startsWith("fact-check report for:")

        if (isSearchLike) {
            val proseLines = text.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank() &&
                        !line.startsWith("http://", ignoreCase = true) &&
                        !line.startsWith("https://", ignoreCase = true) &&
                        !line.startsWith("source:", ignoreCase = true) &&
                        !line.startsWith("sources:", ignoreCase = true) &&
                        !line.startsWith("summarize these results", ignoreCase = true) &&
                        !line.startsWith("use fetch_web_page", ignoreCase = true) &&
                        !line.startsWith("verdict guidance", ignoreCase = true)
                }
                .map { line ->
                    RAW_URL_REGEX.replace(line, "")
                        .replace(Regex("""^\d+\.\s*"""), "")
                        .replace(Regex("""^[-•*]\s*"""), "")
                        .replace(Regex("""\s*\(\s*\)"""), "")
                        .trim()
                }
                .filter { it.length >= 12 }
                .distinct()
                .take(4)

            if (proseLines.isNotEmpty()) {
                return proseLines.joinToString(" ").replace(Regex("""\s{2,}"""), " ").trim()
            }
        }

        // General cleanup for diagnostics, storage, scheduled tasks, etc.
        val cleanLines = text.lines()
            .map { RAW_URL_REGEX.replace(it, "").trim() }
            .filter { it.isNotBlank() && !it.startsWith("source:", ignoreCase = true) }
            .map { it.replace(Regex("""^[-•*]\s*"""), "") }

        val joined = cleanLines.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
        return if (joined.isBlank()) {
            "That task is complete, $ownerTitle."
        } else {
            joined
        }
    }

    fun isSearchErrorOrEmpty(rawToolContent: String): Boolean {
        val lower = rawToolContent.lowercase(Locale.ROOT)
        return lower.contains("\"ok\":false") ||
            lower.contains("unable to reach") ||
            lower.contains("no internet") ||
            lower.contains("network error") ||
            lower.contains("failed to connect") ||
            lower.contains("unknownhostexception") ||
            lower.contains("no results found") ||
            lower.contains("timed out") ||
            lower.contains("could not fetch")
    }

    private fun stripMarkdownSyntax(input: String): String {
        var out = MARKDOWN_HEADING_REGEX.replace(input, "")
        out = MARKDOWN_LINK_REGEX.replace(out, "$1")
        out = out.replace(Regex("""\*\*([^*\n]+)\*\*"""), "$1")
        out = out.replace(Regex("""__([^_\n]+)__"""), "$1")
        out = out.replace(Regex("""\*([^*\n]+)\*"""), "$1")
        out = out.replace(Regex("""`([^`\n]+)`"""), "$1")
        out = out.replace("```", "")
        return out
    }

    private fun looksLikeJsonPayload(text: String): Boolean {
        val t = text.trim()
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            return t.contains("\":") || t.contains("\" :")
        }
        return false
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = Regex(""""${Regex.escape(field)}"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val match = pattern.find(json) ?: return null
        return unescapeJsonString(match.groupValues[1]).trim()
    }

    private fun extractProseFromJsonText(json: String): String {
        val preferredFields = listOf("answer", "reply", "response", "message", "summary", "result", "content", "text", "error")
        for (field in preferredFields) {
            val found = extractJsonField(json, field)
            if (!found.isNullOrBlank()) return found
        }
        // Fallback: turn string key/value pairs into readable sentences
        val pairs = Regex(""""([a-zA-Z0-9_\- ]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(json)
            .mapNotNull { m ->
                val key = m.groupValues[1].replace('_', ' ').trim()
                val value = unescapeJsonString(m.groupValues[2]).trim()
                if (value.isBlank() || key.equals("ok", ignoreCase = true) || key.equals("status", ignoreCase = true)) {
                    null
                } else {
                    "${key.replaceFirstChar { it.uppercase(Locale.ROOT) }}: $value"
                }
            }
            .toList()
        return if (pairs.isNotEmpty()) pairs.joinToString(". ") else ""
    }

    private fun unescapeJsonString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/**
 * Full arithmetic expression evaluator (recursive descent with operator precedence,
 * parentheses, exponents, percentages, square roots) and everyday unit converter.
 */
object OfflineMathAndUnitEngine {

    fun tryEvaluate(userText: String, ownerTitle: String = "sir"): String? {
        val clean = userText.trim()
        if (clean.isBlank()) return null

        // 1. Unit conversions ("convert 100 km to miles", "30 c to f", "5 kg in lbs")
        tryUnitConversion(clean, ownerTitle)?.let { return it }

        // 2. Percentage queries ("25% of 400", "what is 15 percent of 80")
        val pctMatch = Regex(
            """(?i)(-?\d+(?:\.\d+)?)\s*(?:%|percent)\s+of\s+(-?\d+(?:\.\d+)?)""",
        ).find(clean)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toDoubleOrNull()
            val base = pctMatch.groupValues[2].toDoubleOrNull()
            if (pct != null && base != null) {
                val res = (pct / 100.0) * base
                return "${formatNumber(pct)} percent of ${formatNumber(base)} is ${formatNumber(res)}, $ownerTitle."
            }
        }

        // 3. Square root ("square root of 144", "sqrt(81)")
        val sqrtMatch = Regex("""(?i)(?:square\s+root\s+of|sqrt\s*\(?)\s*(-?\d+(?:\.\d+)?)\s*\)?""").find(clean)
        if (sqrtMatch != null) {
            val num = sqrtMatch.groupValues[1].toDoubleOrNull()
            if (num != null) {
                return if (num < 0) {
                    "The square root of a negative number is imaginary, $ownerTitle — specifically ${formatNumber(sqrt(-num))}i."
                } else {
                    "The square root of ${formatNumber(num)} is ${formatNumber(sqrt(num))}, $ownerTitle."
                }
            }
        }

        // 4. General arithmetic expressions ("(12 + 8) * 3", "what is 45 times 6 minus 10", "2^10")
        val expr = extractArithmeticExpression(clean) ?: return null
        val value = evaluateExpression(expr) ?: return null
        if (value.isNaN() || value.isInfinite()) {
            return "That expression involves division by zero or overflows standard bounds, $ownerTitle."
        }
        return "That comes out to ${formatNumber(value)}, $ownerTitle."
    }

    private fun tryUnitConversion(text: String, ownerTitle: String): String? {
        val m = Regex(
            """(?i)(?:convert\s+)?(-?\d+(?:\.\d+)?)\s*°?\s*([a-zA-Z/]+(?:\s+[a-zA-Z]+)?)\s+(?:to|in|into)\s+°?\s*([a-zA-Z/]+(?:\s+[a-zA-Z]+)?)""",
        ).find(text) ?: return null

        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val fromRaw = m.groupValues[2].trim().lowercase(Locale.ROOT)
        val toRaw = m.groupValues[3].trim().lowercase(Locale.ROOT)

        val fromUnit = normalizeUnit(fromRaw) ?: return null
        val toUnit = normalizeUnit(toRaw) ?: return null

        // Temperature
        if (fromUnit in setOf("c", "f", "k") && toUnit in setOf("c", "f", "k")) {
            val celsius = when (fromUnit) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                "k" -> value - 273.15
                else -> return null
            }
            val result = when (toUnit) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                "k" -> celsius + 273.15
                else -> return null
            }
            return "${formatNumber(value)} ${unitLabel(fromUnit)} is ${formatNumber(result)} ${unitLabel(toUnit)}, $ownerTitle."
        }

        // Linear factor conversions by dimension
        val lengthToMeters = mapOf(
            "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344,
        )
        val weightToGrams = mapOf(
            "mg" to 0.001, "g" to 1.0, "kg" to 1000.0,
            "oz" to 28.349523125, "lb" to 453.59237,
        )
        val volumeToMl = mapOf(
            "ml" to 1.0, "l" to 1000.0, "cup" to 236.5882365, "gal" to 3785.411784,
        )
        val speedToMps = mapOf(
            "m/s" to 1.0, "km/h" to (1000.0 / 3600.0), "mph" to (1609.344 / 3600.0),
        )
        val dataToBytes = mapOf(
            "b" to 1.0, "kb" to 1024.0, "mb" to 1024.0 * 1024.0,
            "gb" to 1024.0 * 1024.0 * 1024.0, "tb" to 1024.0 * 1024.0 * 1024.0 * 1024.0,
        )
        val timeToSeconds = mapOf(
            "sec" to 1.0, "min" to 60.0, "hr" to 3600.0, "day" to 86400.0, "week" to 604800.0,
        )

        val tables = listOf(lengthToMeters, weightToGrams, volumeToMl, speedToMps, dataToBytes, timeToSeconds)
        for (table in tables) {
            val fFactor = table[fromUnit]
            val tFactor = table[toUnit]
            if (fFactor != null && tFactor != null) {
                val result = (value * fFactor) / tFactor
                return "${formatNumber(value)} ${unitLabel(fromUnit)} equals ${formatNumber(result)} ${unitLabel(toUnit)}, $ownerTitle."
            }
        }
        return null
    }

    private fun normalizeUnit(raw: String): String? = when (raw.trimEnd('?', '.', ',', '!')) {
        "c", "celsius", "centigrade", "deg c", "degrees c", "degrees celsius" -> "c"
        "f", "fahrenheit", "deg f", "degrees f", "degrees fahrenheit" -> "f"
        "k", "kelvin" -> "k"
        "mm", "millimeter", "millimeters", "millimetre", "millimetres" -> "mm"
        "cm", "centimeter", "centimeters", "centimetre", "centimetres" -> "cm"
        "m", "meter", "meters", "metre", "metres" -> "m"
        "km", "kilometer", "kilometers", "kilometre", "kilometres" -> "km"
        "in", "inch", "inches" -> "in"
        "ft", "foot", "feet" -> "ft"
        "yd", "yard", "yards" -> "yd"
        "mi", "mile", "miles" -> "mi"
        "mg", "milligram", "milligrams" -> "mg"
        "g", "gram", "grams" -> "g"
        "kg", "kilogram", "kilograms", "kilo", "kilos" -> "kg"
        "oz", "ounce", "ounces" -> "oz"
        "lb", "lbs", "pound", "pounds" -> "lb"
        "ml", "milliliter", "milliliters", "millilitre", "millilitres" -> "ml"
        "l", "liter", "liters", "litre", "litres" -> "l"
        "cup", "cups" -> "cup"
        "gal", "gallon", "gallons" -> "gal"
        "km/h", "kmh", "kph" -> "km/h"
        "mph" -> "mph"
        "m/s", "mps" -> "m/s"
        "b", "byte", "bytes" -> "b"
        "kb", "kilobyte", "kilobytes" -> "kb"
        "mb", "megabyte", "megabytes" -> "mb"
        "gb", "gigabyte", "gigabytes" -> "gb"
        "tb", "terabyte", "terabytes" -> "tb"
        "s", "sec", "secs", "second", "seconds" -> "sec"
        "min", "mins", "minute", "minutes" -> "min"
        "h", "hr", "hrs", "hour", "hours" -> "hr"
        "day", "days" -> "day"
        "week", "weeks" -> "week"
        else -> null
    }

    private fun unitLabel(code: String): String = when (code) {
        "c" -> "degrees Celsius"
        "f" -> "degrees Fahrenheit"
        "k" -> "Kelvin"
        "mm" -> "millimeters"
        "cm" -> "centimeters"
        "m" -> "meters"
        "km" -> "kilometers"
        "in" -> "inches"
        "ft" -> "feet"
        "yd" -> "yards"
        "mi" -> "miles"
        "mg" -> "milligrams"
        "g" -> "grams"
        "kg" -> "kilograms"
        "oz" -> "ounces"
        "lb" -> "pounds"
        "ml" -> "milliliters"
        "l" -> "liters"
        "cup" -> "cups"
        "gal" -> "gallons"
        "km/h" -> "kilometers per hour"
        "mph" -> "miles per hour"
        "m/s" -> "meters per second"
        "b" -> "bytes"
        "kb" -> "kilobytes"
        "mb" -> "megabytes"
        "gb" -> "gigabytes"
        "tb" -> "terabytes"
        "sec" -> "seconds"
        "min" -> "minutes"
        "hr" -> "hours"
        "day" -> "days"
        "week" -> "weeks"
        else -> code
    }

    private fun extractArithmeticExpression(raw: String): String? {
        var s = raw.lowercase(Locale.ROOT)
            .replace(Regex("""^(?:jarvis\s*,?\s*)?(?:please\s+)?(?:what\s+is|what's|calculate|compute|solve|evaluate)\s+"""), "")
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace("to the power of", "^")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "*")
            .replace("×", "*")
            .replace("÷", "/")
            .replace(Regex("""(?<=\d)\s*x\s*(?=\d)"""), "*")
            .replace("squared", "^2")
            .replace("cubed", "^3")
            .trim()
            .trimEnd('?', '.', '!')

        // Must contain at least one digit and at least one operator to avoid triggering on normal sentences with numbers
        val hasOperator = s.any { it in charArrayOf('+', '-', '*', '/', '^') }
        val hasDigit = s.any { it.isDigit() }
        if (!hasOperator || !hasDigit) return null

        // Strip trailing conversational words like "equals"
        s = s.replace(Regex("""\s*equals?\s*$"""), "").trim()

        // Ensure the remaining string is strictly a valid math expression
        if (!Regex("""^[0-9.\s+\-*/^()]+$""").matches(s)) return null
        return s
    }

    internal fun evaluateExpression(expr: String): Double? {
        val tokens = expr.replace(" ", "")
        if (tokens.isEmpty()) return null
        val parser = object {
            var pos = 0

            fun parseExpression(): Double {
                var x = parseTerm()
                while (pos < tokens.length) {
                    x = when (tokens[pos]) {
                        '+' -> {
                            pos++
                            x + parseTerm()
                        }
                        '-' -> {
                            pos++
                            x - parseTerm()
                        }
                        else -> break
                    }
                }
                return x
            }

            fun parseTerm(): Double {
                var x = parsePower()
                while (pos < tokens.length) {
                    x = when (tokens[pos]) {
                        '*' -> {
                            pos++
                            x * parsePower()
                        }
                        '/' -> {
                            pos++
                            x / parsePower()
                        }
                        else -> break
                    }
                }
                return x
            }

            fun parsePower(): Double {
                val base = parseFactor()
                return if (pos < tokens.length && tokens[pos] == '^') {
                    pos++
                    base.pow(parsePower())
                } else {
                    base
                }
            }

            fun parseFactor(): Double {
                if (pos < tokens.length && tokens[pos] == '+') {
                    pos++
                    return parseFactor()
                }
                if (pos < tokens.length && tokens[pos] == '-') {
                    pos++
                    return -parseFactor()
                }
                if (pos < tokens.length && tokens[pos] == '(') {
                    pos++
                    val inner = parseExpression()
                    if (pos >= tokens.length || tokens[pos] != ')') {
                        throw IllegalArgumentException("Unmatched parenthesis")
                    }
                    pos++
                    return inner
                }
                val start = pos
                while (pos < tokens.length && (tokens[pos].isDigit() || tokens[pos] == '.')) {
                    pos++
                }
                if (start == pos) throw IllegalArgumentException("Expected number at $pos")
                return tokens.substring(start, pos).toDouble()
            }
        }

        return runCatching {
            val result = parser.parseExpression()
            if (parser.pos != tokens.length) null else result
        }.getOrNull()
    }

    fun formatNumber(value: Double): String {
        val rounded = value.roundToInt().toDouble()
        return if (abs(value - rounded) < 1e-9 && abs(value) < 1e12) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }
}

/**
 * Natural conversational & general-knowledge offline engine in J.A.R.V.I.S.'s voice.
 *
 * Ensures offline models answer everyday questions, science, history, geography,
 * computing concepts, study coaching, planning, and casual conversation naturally
 * in plain English prose — never raw code, JSON, or robotic boilerplate.
 */
object OfflineNaturalEngine {

    private data class KnowledgeEntry(
        val keywords: List<String>,
        val answer: String,
        val studyQuestion: String? = null,
    )

    private val knowledgeBase: List<KnowledgeEntry> = listOf(
        // Physics & Astronomy
        KnowledgeEntry(
            keywords = listOf("gravity", "gravitational"),
            answer = "Gravity is the fundamental force of attraction between objects that have mass or energy. Einstein's general relativity describes it as the curvature of spacetime caused by mass, which is why Earth stays in orbit around the Sun and objects fall toward the ground.",
            studyQuestion = "To lock that in: how does Einstein's view of gravity differ from Newton's force-at-a-distance view?",
        ),
        KnowledgeEntry(
            keywords = listOf("relativity", "e=mc2", "e = mc2", "spacetime"),
            answer = "Einstein's theory of relativity has two parts. Special relativity shows that the speed of light is constant for all observers, so time and space stretch or contract at high speeds, and mass and energy are equivalent through E equals m c squared. General relativity extends this to show that mass curves spacetime, creating gravity.",
            studyQuestion = "Quick check: what happens to time for a traveler moving close to the speed of light?",
        ),
        KnowledgeEntry(
            keywords = listOf("black hole", "event horizon"),
            answer = "A black hole is a region of spacetime where gravity is so intense that nothing — not even light — can escape once it crosses the boundary called the event horizon. Most stellar black holes form when massive stars run out of fuel and collapse under their own weight.",
            studyQuestion = "What is the name of the point of no return around a black hole?",
        ),
        KnowledgeEntry(
            keywords = listOf("solar system", "planets", "mercury", "jupiter", "saturn", "mars"),
            answer = "Our solar system consists of the Sun and eight planets orbiting it: Mercury, Venus, Earth, and Mars as the rocky inner worlds, followed by the gas and ice giants Jupiter, Saturn, Uranus, and Neptune, along with moons, asteroids, and comets.",
            studyQuestion = "Which four planets make up the rocky inner solar system?",
        ),
        KnowledgeEntry(
            keywords = listOf("quantum", "superposition", "entanglement"),
            answer = "Quantum mechanics describes how matter and energy behave at atomic and subatomic scales. Unlike everyday objects, quantum particles exhibit both wave and particle properties, exist in probability distributions until measured, and can become linked through entanglement.",
        ),
        KnowledgeEntry(
            keywords = listOf("electricity", "voltage", "current", "ohm"),
            answer = "Electricity is the flow of electric charge, usually electrons moving through a conductor. Voltage is the electrical pressure pushing the charge, current is the rate of flow measured in amperes, and resistance opposes that flow — linked by Ohm's law: voltage equals current times resistance.",
            studyQuestion = "If you double the resistance while keeping voltage the same, what happens to the current?",
        ),
        KnowledgeEntry(
            keywords = listOf("speed of light", "light year", "light-year"),
            answer = "Light travels through a vacuum at roughly 299,792 kilometers per second, or about 186,282 miles per second. A light-year is a measure of distance — about 9.46 trillion kilometers — representing how far light travels in one year.",
        ),
        KnowledgeEntry(
            keywords = listOf("atom", "proton", "neutron", "electron"),
            answer = "An atom is the basic building block of matter. It has a dense central nucleus containing positively charged protons and neutral neutrons, surrounded by a cloud of negatively charged electrons. The number of protons defines which chemical element it is.",
        ),
        KnowledgeEntry(
            keywords = listOf("newton", "laws of motion", "inertia"),
            answer = "Newton's three laws of motion explain classical mechanics: first, an object stays at rest or in steady motion unless acted upon by a force; second, force equals mass times acceleration; and third, every action has an equal and opposite reaction.",
        ),

        // Biology & Earth Science
        KnowledgeEntry(
            keywords = listOf("photosynthesis", "chlorophyll"),
            answer = "Photosynthesis is the process plants, algae, and some bacteria use to turn sunlight, water, and carbon dioxide into glucose for energy, releasing oxygen as a byproduct. It takes place inside chloroplasts using the green pigment chlorophyll.",
            studyQuestion = "What are the three inputs a plant needs for photosynthesis?",
        ),
        KnowledgeEntry(
            keywords = listOf("dna", "rna", "genetics", "chromosome", "gene"),
            answer = "DNA, or deoxyribonucleic acid, is the double-helix molecule that carries genetic instructions for building and running living organisms. It encodes information using four chemical bases — adenine, thymine, cytosine, and guanine — organized into genes.",
            studyQuestion = "Which four bases make up the alphabet of DNA?",
        ),
        KnowledgeEntry(
            keywords = listOf("evolution", "natural selection", "darwin"),
            answer = "Evolution is the gradual change in inherited traits of populations across generations. Charles Darwin explained its primary engine as natural selection: individuals with traits better suited to their environment are more likely to survive and pass those traits on.",
        ),
        KnowledgeEntry(
            keywords = listOf("mitochondria", "cell", "mitosis"),
            answer = "Cells are the smallest structural units of life. Inside complex cells, specialized structures called organelles divide the labor — the nucleus stores DNA, ribosomes assemble proteins, and mitochondria generate usable chemical energy in the form of ATP.",
        ),
        KnowledgeEntry(
            keywords = listOf("water cycle", "evaporation", "condensation", "precipitation"),
            answer = "The water cycle moves water continuously between Earth's surface and atmosphere: sunlight drives evaporation from oceans and lakes, rising vapor cools into clouds through condensation, falls back as precipitation like rain or snow, and collects again in rivers and oceans.",
        ),
        KnowledgeEntry(
            keywords = listOf("climate change", "greenhouse effect", "global warming"),
            answer = "The greenhouse effect occurs when gases like carbon dioxide and methane trap heat in Earth's atmosphere. While a natural greenhouse effect keeps Earth habitable, burning fossil fuels increases these gases and gradually raises global average temperatures.",
        ),
        KnowledgeEntry(
            keywords = listOf("why is the sky blue", "sky blue", "rayleigh"),
            answer = "The sky looks blue because Earth's atmosphere scatters shorter blue wavelengths of sunlight much more strongly than longer red wavelengths — a phenomenon known as Rayleigh scattering. At sunset, sunlight travels through more atmosphere, leaving warm reds and oranges behind.",
        ),
        KnowledgeEntry(
            keywords = listOf("human brain", "neuron", "nervous system"),
            answer = "The human brain contains roughly 86 billion neurons connected by trillions of synapses. They communicate through tiny electrical impulses and chemical neurotransmitters, coordinating everything from breathing and movement to memory and reasoning.",
        ),

        // Computing, Tech & AI (Explained in plain natural prose — never code dumps!)
        KnowledgeEntry(
            keywords = listOf("artificial intelligence", "machine learning", "neural network", "deep learning", "llm", "large language model"),
            answer = "Artificial intelligence refers to computer systems designed to perform tasks associated with human reasoning, such as understanding language or recognizing patterns. Modern machine learning trains neural networks on large datasets so they learn statistical patterns rather than following hand-written rules.",
            studyQuestion = "How does machine learning differ from traditional rule-based programming?",
        ),
        KnowledgeEntry(
            keywords = listOf("cpu", "processor", "gpu", "ram", "memory vs storage"),
            answer = "In a computer or smartphone, the CPU is the general-purpose processor that coordinates instructions, the GPU specializes in parallel math for graphics and AI workloads, RAM is fast temporary memory for active apps, and storage holds your files permanently even when power is off.",
        ),
        KnowledgeEntry(
            keywords = listOf("internet", "http", "https", "dns", "ip address", "wi-fi", "wifi"),
            answer = "The internet is a global network of connected computers communicating through standard protocols. DNS translates human-friendly domain names into numeric IP addresses, and HTTPS encrypts data in transit between your device and the server so it stays private.",
        ),
        KnowledgeEntry(
            keywords = listOf("encryption", "cryptography", "aes", "cybersecurity"),
            answer = "Encryption scrambles readable information into ciphertext using mathematical keys so only authorized parties can decode it. Modern apps rely on algorithms such as AES-GCM for stored data and TLS for network traffic to protect privacy.",
        ),
        KnowledgeEntry(
            keywords = listOf("api", "rest api", "webhook"),
            answer = "An API, or Application Programming Interface, is a structured contract that lets two pieces of software talk to each other safely. For example, a weather app sends a request to a weather service API and receives the current forecast back without needing to know how the server works internally.",
        ),
        KnowledgeEntry(
            keywords = listOf("database", "sql", "sqlite", "nosql"),
            answer = "A database organizes information so applications can store, search, and update it reliably. Relational databases like SQLite and PostgreSQL store data in structured linked tables, while document databases store flexible nested records.",
        ),
        KnowledgeEntry(
            keywords = listOf("algorithm", "data structure", "big o"),
            answer = "An algorithm is a step-by-step procedure for solving a problem, and a data structure is the way information is organized in memory so that procedure runs efficiently. Choosing the right pairing can turn a sluggish operation into an instantaneous one.",
        ),
        KnowledgeEntry(
            keywords = listOf("android", "kotlin", "operating system", "linux"),
            answer = "An operating system manages a device's hardware, apps, memory, and security. Android is built atop a Linux kernel, and modern native Android apps are typically written in Kotlin with Jetpack Compose for fast, responsive interfaces.",
        ),
        KnowledgeEntry(
            keywords = listOf("git", "github", "version control"),
            answer = "Git is a version control system that tracks snapshots of a project's history so developers can branch, experiment safely, review changes, and roll back mistakes without losing work.",
        ),
        KnowledgeEntry(
            keywords = listOf("blockchain", "bitcoin", "cryptocurrency"),
            answer = "A blockchain is a distributed digital ledger where transactions are grouped into blocks and linked cryptographically across many computers, making past entries extremely difficult to alter without consensus from the network.",
        ),
        KnowledgeEntry(
            keywords = listOf("cloud computing", "server", "docker"),
            answer = "Cloud computing means renting computing power, storage, and databases over the internet on demand rather than maintaining physical servers yourself. It lets services scale smoothly as traffic rises or falls.",
        ),

        // Geography & World Facts
        KnowledgeEntry(
            keywords = listOf("continents", "how many continents"),
            answer = "Earth has seven continents: Asia, Africa, North America, South America, Antarctica, Europe, and Australia (often grouped as Oceania).",
        ),
        KnowledgeEntry(
            keywords = listOf("oceans", "largest ocean", "pacific"),
            answer = "Earth's five major oceans are the Pacific, Atlantic, Indian, Southern, and Arctic. The Pacific Ocean is the largest and deepest, covering roughly a third of the planet's surface.",
        ),
        KnowledgeEntry(
            keywords = listOf("mount everest", "tallest mountain", "highest mountain"),
            answer = "Mount Everest, in the Himalayas on the border of Nepal and Tibet, is Earth's highest mountain above sea level at approximately 8,849 meters, or 29,032 feet.",
        ),
        KnowledgeEntry(
            keywords = listOf("longest river", "nile", "amazon river"),
            answer = "The Nile in northeastern Africa and the Amazon in South America are Earth's two longest rivers, each stretching roughly 6,400 to 6,650 kilometers. The Amazon carries by far the largest volume of water.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of france", "paris"),
            answer = "The capital of France is Paris, situated along the Seine River in northern France.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of japan", "tokyo"),
            answer = "The capital of Japan is Tokyo, one of the largest metropolitan areas in the world.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of india", "new delhi"),
            answer = "The capital of India is New Delhi.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of usa", "capital of united states", "capital of the us", "capital of america"),
            answer = "The capital of the United States is Washington, D.C.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of uk", "capital of united kingdom", "capital of england", "capital of britain"),
            answer = "The capital of the United Kingdom is London.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of germany", "berlin"),
            answer = "The capital of Germany is Berlin.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of italy", "rome"),
            answer = "The capital of Italy is Rome.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of australia", "canberra"),
            answer = "The capital of Australia is Canberra — often mistaken for Sydney or Melbourne.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of canada", "ottawa"),
            answer = "The capital of Canada is Ottawa.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of china", "beijing"),
            answer = "The capital of China is Beijing.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of russia", "moscow"),
            answer = "The capital of Russia is Moscow.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of brazil", "brasilia"),
            answer = "The capital of Brazil is Brasília.",
        ),

        // History & Notable Figures
        KnowledgeEntry(
            keywords = listOf("albert einstein", "einstein"),
            answer = "Albert Einstein was a German-born theoretical physicist who developed the theories of special and general relativity and explained the photoelectric effect, earning the 1921 Nobel Prize in Physics.",
        ),
        KnowledgeEntry(
            keywords = listOf("nikola tesla", "tesla"),
            answer = "Nikola Tesla was a Serbian-American inventor and electrical engineer best known for pioneering alternating current electricity systems, induction motors, and early wireless transmission experiments.",
        ),
        KnowledgeEntry(
            keywords = listOf("alan turing", "turing"),
            answer = "Alan Turing was a British mathematician and pioneer of computer science whose work formalizing computation and breaking the Enigma cipher during World War II laid the foundations of modern computing and artificial intelligence.",
        ),
        KnowledgeEntry(
            keywords = listOf("marie curie", "curie"),
            answer = "Marie Curie was a Polish-French physicist and chemist who pioneered research on radioactivity, discovered polonium and radium, and became the first person to win Nobel Prizes in two different sciences — Physics and Chemistry.",
        ),
        KnowledgeEntry(
            keywords = listOf("leonardo da vinci", "da vinci"),
            answer = "Leonardo da Vinci was an Italian Renaissance polymath renowned equally for masterpieces such as the Mona Lisa and The Last Supper and for his notebooks filled with engineering, anatomy, and flight designs.",
        ),
        KnowledgeEntry(
            keywords = listOf("moon landing", "apollo 11", "neil armstrong"),
            answer = "NASA's Apollo 11 mission landed the first humans on the Moon on July 20, 1969, with Neil Armstrong and Buzz Aldrin walking on the lunar surface while Michael Collins orbited above.",
        ),
        KnowledgeEntry(
            keywords = listOf("world war 2", "world war ii", "ww2", "wwii"),
            answer = "World War II lasted from 1939 to 1945, pitting the Allied powers against the Axis powers across Europe, the Pacific, Africa, and Asia. It ended in 1945 and reshaped global politics, leading to the creation of the United Nations.",
        ),

        // Study, Productivity & Health
        KnowledgeEntry(
            keywords = listOf("pomodoro", "how to focus", "study better", "study tips", "active recall", "spaced repetition", "feynman"),
            answer = "Three evidence-backed study techniques stand out: active recall, where you test yourself from memory instead of passively rereading; spaced repetition, where you review material over expanding intervals; and the Feynman technique, where you explain a concept in plain language to expose any gaps. Pairing those with focused 25-minute Pomodoro sprints keeps fatigue low.",
        ),
        KnowledgeEntry(
            keywords = listOf("sleep", "insomnia", "sleep better", "how much sleep"),
            answer = "Most adults perform best on seven to nine hours of consistent sleep. Keeping a steady wake time, dimming bright screens an hour before bed, avoiding caffeine late in the afternoon, and keeping your room cool and dark all noticeably improve sleep quality.",
        ),
        KnowledgeEntry(
            keywords = listOf("eye strain", "20-20-20", "screen fatigue"),
            answer = "To reduce eye strain during long screen sessions, follow the 20-20-20 rule: every 20 minutes, look at something roughly 20 feet away for 20 seconds, blink deliberately, and match your screen brightness to the room around you.",
        ),
        KnowledgeEntry(
            keywords = listOf("hydration", "how much water", "drink water"),
            answer = "General hydration needs vary with climate and activity, typically around two to three liters of fluids daily from water and food. Clear or pale-yellow urine is usually a reliable everyday indicator that you are well hydrated.",
        ),
        KnowledgeEntry(
            keywords = listOf("workout", "exercise", "fitness", "pushup", "cardio"),
            answer = "A well-balanced fitness routine combines aerobic activity for heart health, resistance training two to three times a week to build strength, and brief mobility work to protect joints. Consistency and gradual progression beat occasional all-out sessions.",
        ),
        KnowledgeEntry(
            keywords = listOf("budget", "compound interest", "saving money", "invest"),
            answer = "A practical personal-finance foundation starts with tracking expenses, keeping an emergency fund covering three to six months of essentials, avoiding high-interest debt, and letting compound growth work over the long term through consistent saving.",
        ),
        KnowledgeEntry(
            keywords = listOf("battery health", "save battery", "extend battery", "battery life"),
            answer = "Lithium-ion phone batteries last longest when kept away from excessive heat and extreme charge levels. Lowering screen brightness, curbing background wakeups, avoiding heavy gaming while fast-charging, and keeping daily charge roughly between 20 and 85 percent preserves long-term health.",
        ),

        // Expanded Physics, Space, Chemistry & Everyday Science
        KnowledgeEntry(
            keywords = listOf("sun", "star", "stars", "nuclear fusion"),
            answer = "The Sun is a medium-sized yellow dwarf star made mostly of hydrogen and helium. Deep in its core, nuclear fusion combines hydrogen nuclei into helium, releasing the light and heat that sustain life on Earth.",
        ),
        KnowledgeEntry(
            keywords = listOf("moon", "lunar", "tides", "eclipse"),
            answer = "The Moon is Earth's only natural satellite, orbiting roughly 384,400 kilometers away. Its gravitational pull drives ocean tides, and solar or lunar eclipses occur when the Sun, Earth, and Moon align along the same line.",
        ),
        KnowledgeEntry(
            keywords = listOf("universe", "big bang", "galaxy", "milky way"),
            answer = "The Milky Way is our home barred-spiral galaxy, containing over a hundred billion stars. According to cosmology, the observable universe has been expanding and cooling since the Big Bang roughly 13.8 billion years ago.",
        ),
        KnowledgeEntry(
            keywords = listOf("thermodynamics", "entropy", "heat transfer", "conduction", "convection"),
            answer = "Thermodynamics studies heat, energy, and work. Energy cannot be created or destroyed, heat naturally flows from warmer objects to cooler ones via conduction, convection, or radiation, and entropy measures how dispersed that energy becomes.",
        ),
        KnowledgeEntry(
            keywords = listOf("magnet", "magnetism", "electromagnet", "magnetic field"),
            answer = "Magnetism arises from moving electric charges and aligned electron spins inside materials like iron. Electric currents create magnetic fields and changing magnetic fields induce electric currents — the principle behind motors and power generators.",
        ),
        KnowledgeEntry(
            keywords = listOf("sound wave", "speed of sound", "echo", "frequency", "decibel"),
            answer = "Sound travels as mechanical vibrations through air, water, or solids. In dry air at room temperature it moves at about 343 meters per second — far slower than light, which is why you see lightning before hearing thunder.",
        ),
        KnowledgeEntry(
            keywords = listOf("water", "h2o", "boiling point", "freezing point", "ice floats"),
            answer = "Water, or H2O, consists of two hydrogen atoms bonded to one oxygen atom. At sea-level pressure it freezes at 0 degrees Celsius and boils at 100 degrees Celsius, and ice floats because frozen water expands into an open crystal lattice.",
        ),
        KnowledgeEntry(
            keywords = listOf("oxygen", "breathing", "respiration", "lungs", "atmosphere"),
            answer = "Earth's atmosphere is roughly 78 percent nitrogen and 21 percent oxygen. Your lungs draw in oxygen and transfer it to red blood cells, which deliver it throughout the body to release energy from food while exhaling carbon dioxide.",
        ),
        KnowledgeEntry(
            keywords = listOf("periodic table", "chemistry", "molecule", "chemical bond"),
            answer = "The periodic table organizes the 118 known chemical elements by atomic number and recurring chemical properties. Atoms share or transfer electrons through covalent or ionic bonds to form molecules like water and salt.",
        ),
        KnowledgeEntry(
            keywords = listOf("acid", "base", "ph scale", "alkaline"),
            answer = "The pH scale runs from 0 to 14 to measure how acidic or basic a water solution is. Pure water sits near neutral at 7, acids like lemon juice sit below 7, and bases like baking soda sit above 7.",
        ),
        KnowledgeEntry(
            keywords = listOf("seasons", "summer", "winter", "equinox", "solstice", "earth tilt"),
            answer = "Earth experiences seasons not because its distance from the Sun changes much, but because its axis is tilted about 23.5 degrees. Whichever hemisphere tilts toward the Sun receives more direct sunlight and longer days.",
        ),
        KnowledgeEntry(
            keywords = listOf("earthquake", "volcano", "plate tectonics", "tectonic"),
            answer = "Earth's outer crust is broken into massive tectonic plates slowly floating over the mantle. Earthquakes happen when stress along plate boundaries releases suddenly, while magma pushing to the surface creates volcanoes.",
        ),
        KnowledgeEntry(
            keywords = listOf("thunder", "lightning", "rain", "weather", "rainbow"),
            answer = "Rain forms when cooled water vapor condenses around tiny atmospheric dust particles until droplets grow too heavy to stay aloft. Lightning heats the surrounding air so rapidly that the resulting shockwave reaches us as thunder.",
        ),
        KnowledgeEntry(
            keywords = listOf("heart", "blood", "circulation", "blood pressure"),
            answer = "The human heart beats roughly 100,000 times a day, pumping oxygen-poor blood to the lungs and oxygen-rich blood through arteries to every organ, then returning it through veins in a continuous loop.",
        ),
        KnowledgeEntry(
            keywords = listOf("immune system", "vaccine", "virus", "bacteria", "antibiotic"),
            answer = "The immune system defends the body using white blood cells and targeted antibodies. Vaccines train that defense safely in advance, whereas antibiotics kill bacteria and do not work against viral infections.",
        ),
        KnowledgeEntry(
            keywords = listOf("nutrition", "protein", "carbohydrates", "vitamins", "metabolism"),
            answer = "A balanced diet supplies carbohydrates and healthy fats for energy, protein for repairing tissue and muscle, and vitamins and minerals for cellular reactions, alongside adequate water and fiber.",
        ),

        // Expanded Math Concepts & Computing Topics (Plain Spoken English)
        KnowledgeEntry(
            keywords = listOf("pythagoras", "pythagorean", "right triangle"),
            answer = "The Pythagorean theorem states that in any right triangle, the square of the longest side (the hypotenuse) equals the sum of the squares of the other two sides: a squared plus b squared equals c squared.",
        ),
        KnowledgeEntry(
            keywords = listOf("what is pi", "number pi", "circumference"),
            answer = "Pi is the mathematical constant representing the ratio of a circle's circumference to its diameter, approximately 3.14159. It is irrational, meaning its decimal digits continue indefinitely without repeating.",
        ),
        KnowledgeEntry(
            keywords = listOf("prime number", "prime numbers"),
            answer = "A prime number is a whole number greater than 1 whose only positive divisors are 1 and itself, starting with 2, 3, 5, 7, 11, and 13. Primes are essential in modern cryptography and secure communications.",
        ),
        KnowledgeEntry(
            keywords = listOf("binary", "bits and bytes", "binary code"),
            answer = "Computers represent all data in binary using two states — 0 and 1, called bits — because electronic circuits easily distinguish off and on voltages. Eight bits form a byte, capable of representing 256 distinct values.",
        ),
        KnowledgeEntry(
            keywords = listOf("bluetooth", "nfc", "5g", "gps", "satellite navigation"),
            answer = "Short-range wireless standards like Bluetooth connect nearby accessories such as earbuds, NFC handles tap-to-pay within centimeters, Wi-Fi delivers high-speed local internet, and GPS calculates your location using timing signals from orbiting satellites.",
        ),
        KnowledgeEntry(
            keywords = listOf("password", "two factor", "2fa", "phishing", "malware"),
            answer = "Strong digital security relies on unique long passphrases, a dedicated password manager, and two-factor authentication, while watching carefully for phishing messages that try to rush you into handing over codes or logins.",
        ),
        KnowledgeEntry(
            keywords = listOf("programming", "coding", "what is code", "compiler", "software"),
            answer = "Software programming means writing clear instructions that tell a computer how to process information and respond to users. Compilers or interpreters translate human-readable source code into binary machine instructions the processor executes.",
        ),

        // Expanded Geography & History
        KnowledgeEntry(
            keywords = listOf("capital of spain", "madrid"),
            answer = "The capital of Spain is Madrid.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of south korea", "seoul"),
            answer = "The capital of South Korea is Seoul.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of egypt", "cairo"),
            answer = "The capital of Egypt is Cairo, near the Nile Delta and ancient Giza.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of turkey", "ankara", "istanbul"),
            answer = "The capital of Turkey is Ankara, though Istanbul is its largest and best-known city.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of uae", "abu dhabi", "dubai"),
            answer = "The capital of the United Arab Emirates is Abu Dhabi, while Dubai is its largest commercial hub.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of pakistan", "islamabad"),
            answer = "The capital of Pakistan is Islamabad.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of bangladesh", "dhaka"),
            answer = "The capital of Bangladesh is Dhaka.",
        ),
        KnowledgeEntry(
            keywords = listOf("capital of mexico", "mexico city"),
            answer = "The capital of Mexico is Mexico City.",
        ),
        KnowledgeEntry(
            keywords = listOf("sahara", "largest desert", "desert"),
            answer = "The Sahara in North Africa is the largest hot desert on Earth, while Antarctica is technically the largest cold desert overall due to its minimal precipitation.",
        ),
        KnowledgeEntry(
            keywords = listOf("shakespeare", "romeo and juliet", "hamlet"),
            answer = "William Shakespeare was an English playwright and poet of the late 16th and early 17th centuries whose works, including Hamlet, Macbeth, and Romeo and Juliet, deeply shaped English literature and theater.",
        ),
        KnowledgeEntry(
            keywords = listOf("ada lovelace", "first programmer"),
            answer = "Ada Lovelace was a 19th-century English mathematician who wrote the first published algorithm intended for Charles Babbage's Analytical Engine and foresaw that computers could work with symbols beyond pure numbers.",
        ),
        KnowledgeEntry(
            keywords = listOf("galileo", "telescope", "heliocentric"),
            answer = "Galileo Galilei was an Italian astronomer and physicist who improved the telescope, discovered Jupiter's four largest moons, and championed observational science and the Sun-centered solar system model.",
        ),
        KnowledgeEntry(
            keywords = listOf("renaissance", "industrial revolution", "printing press"),
            answer = "Gutenberg's 15th-century movable-type printing press accelerated the European Renaissance's revival of art and science, which later paved the way for the Industrial Revolution's shift to mechanized manufacturing and steam power.",
        ),
    )

    private val dryJokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I told a chemistry joke once, sir. Unfortunately, there was no reaction.",
        "A SQL query walks into a cafe, approaches two tables, and asks politely: may I join you?",
        "Why did the computer catch a cold? Someone left its Windows open.",
        "There are only two hard problems in computer science: cache invalidation, naming things, and off-by-one errors.",
        "I tried to catch some fog earlier. I mist.",
    )

    private val groundedMotivations = listOf(
        "Momentum rarely arrives before action, sir. Pick the single smallest step in front of you, finish it cleanly, and let progress carry the rest.",
        "Quiet consistency outperforms bursts of inspiration every single time, sir. Five focused minutes right now is already a win.",
        "Complexity yields quickly once you break it into one concrete decision at a time, sir. Tell me the first piece you want to tackle.",
    )

    fun extractActiveMode(systemPrompt: String): String {
        val m = Regex("""ACTIVE OPERATIONAL MODE:\s*([A-Z]+)""").find(systemPrompt)
        return m?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: "DEFAULT"
    }

    /**
     * Produces a natural, conversational J.A.R.V.I.S. reply completely offline.
     */
    fun generateNaturalReply(
        userText: String,
        ownerTitle: String,
        ownerName: String,
        modelDisplayName: String,
        modelParameterBadge: String,
        modelSizeLabel: String,
        modelBestUse: String,
        systemPrompt: String,
        priorDialogue: List<LlmMessage>,
        memoryLookup: (String) -> String?,
        allMemoriesSummary: () -> String?,
    ): String {
        val verified = tryAnswerVerified(
            userText = userText,
            ownerTitle = ownerTitle,
            ownerName = ownerName,
            modelDisplayName = modelDisplayName,
            modelParameterBadge = modelParameterBadge,
            modelSizeLabel = modelSizeLabel,
            modelBestUse = modelBestUse,
            systemPrompt = systemPrompt,
            priorDialogue = priorDialogue,
            memoryLookup = memoryLookup,
            allMemoriesSummary = allMemoriesSummary,
        )
        if (verified != null) return verified

        val clean = userText.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val norm = lower.replace(Regex("""[^\p{L}\p{N}\s'/%+\-*^().]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val mode = extractActiveMode(systemPrompt)
        val lastAssistantReply = priorDialogue.lastOrNull { it.role == LlmRole.ASSISTANT && it.content.isNotBlank() }?.content?.trim()
        val previousUserQuestion = priorDialogue.lastOrNull { it.role == LlmRole.USER && it.content.isNotBlank() }?.content?.trim()

        // Simple conversational acknowledgements
        if (norm in setOf("yes", "yeah", "yep", "sure", "ok", "okay", "alright", "got it", "understood", "sounds good", "fine", "cool", "nice", "great")) {
            return if (!lastAssistantReply.isNullOrBlank() && lastAssistantReply.contains("?")) {
                "Very good, $ownerTitle. Tell me what specific step you would like next and I will take care of it."
            } else {
                "Very well, $ownerTitle. Standing by whenever you are ready."
            }
        }
        if (norm in setOf("no", "nah", "nope", "not really", "nothing")) {
            return "Understood, $ownerTitle. I will leave it there — let me know if you need anything else."
        }
        if (lower.contains("fun fact") || lower.contains("interesting fact") || lower.contains("tell me a fact") || lower.contains("surprise me")) {
            val facts = listOf(
                "A day on Venus is longer than its year, $ownerTitle: Venus takes about 243 Earth days to rotate once, yet only 225 Earth days to orbit the Sun.",
                "Octopuses have three hearts and blue copper-based blood, $ownerTitle — two hearts pump blood through the gills while the third circulates it to the body.",
                "Light takes roughly 8 minutes and 20 seconds to travel from the Sun to Earth, $ownerTitle, so we always see the Sun as it was minutes ago.",
                "Honey never spoils under proper sealed storage, $ownerTitle; archaeologists have uncovered edible pots of honey thousands of years old.",
            )
            val idx = abs((clean.hashCode() + LocalTime.now().minute)) % facts.size
            return facts[idx]
        }

        // Follow-up conversational continuity
        if (!previousUserQuestion.isNullOrBlank() &&
            (lower.startsWith("and ") || lower.startsWith("what about ") || lower.startsWith("tell me more") ||
                lower.contains("about that") || lower.contains("about it") || lower.startsWith("why?") || norm == "why" ||
                norm == "explain more" || norm == "go on")
        ) {
            return "Staying with \"$previousUserQuestion\", $ownerTitle: let me know which part you want to focus on, or ask me to search online when connected for live details."
        }

        // Mode-aware helpful offline fallback (natural prose — never syntax errors, never code, never robotic boilerplate)
        val topic = extractCleanTopic(clean)
        return when (mode) {
            "STUDY" ->
                "That is an excellent subject to examine, $ownerTitle. For $topic, try breaking it into three pieces: what it is at its core, how its parts interact, and one everyday example. If you paste your notes here, I can quiz you or condense them right away offline."
            "TASKS" ->
                "I can organize $topic into clear steps right away, $ownerTitle. Give me the first deadline or milestone, and I will set up a reminder or scheduled task for you."
            "RESEARCH" ->
                "Live web sources are unavailable while offline, $ownerTitle, so I will not invent specifics about $topic. Ask me to research $topic once connected and I will pull verified articles for you."
            else ->
                if (clean.endsWith("?") || lower.startsWith("what ") || lower.startsWith("who ") || lower.startsWith("why ") ||
                    lower.startsWith("how ") || lower.startsWith("when ") || lower.startsWith("where ") || lower.startsWith("explain ")
                ) {
                    "Offline on $modelDisplayName, I do not have a verified reference on $topic right here, $ownerTitle, and I prefer being candid over guessing. If you have an internet connection, say \"research $topic\" or enable a cloud model in Settings for full live answers."
                } else {
                    "Noted, $ownerTitle. While offline on $modelDisplayName, I can answer science, math, history, and everyday questions, manage reminders and notes, or run phone actions whenever you give the word."
                }
        }
    }

    /**
     * Deterministic checks (conversation recall, creator/Jarvis identity, greetings, math/units,
     * calendar date/time, and saved owner memories) that tiny quantized models cannot know or
     * reliably compute on their own.
     */
    fun tryAnswerDeterministic(
        userText: String,
        ownerTitle: String,
        ownerName: String,
        modelDisplayName: String,
        modelParameterBadge: String,
        modelSizeLabel: String,
        modelBestUse: String,
        priorDialogue: List<LlmMessage>,
        memoryLookup: (String) -> String?,
        allMemoriesSummary: () -> String?,
    ): String? {
        val clean = userText.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val norm = lower.replace(Regex("""[^\p{L}\p{N}\s'/%+\-*^().]"""), " ").replace(Regex("""\s+"""), " ").trim()

        val lastAssistantReply = priorDialogue.lastOrNull { it.role == LlmRole.ASSISTANT && it.content.isNotBlank() }?.content?.trim()
        val previousUserQuestion = priorDialogue.lastOrNull { it.role == LlmRole.USER && it.content.isNotBlank() }?.content?.trim()

        // 1. Short-term conversation recall
        if (lower.contains("what did you just say") ||
            lower.contains("what did you say") ||
            lower.contains("repeat that") ||
            lower.contains("say that again") ||
            norm == "pardon" ||
            norm == "come again" ||
            lower.contains("what was your last")
        ) {
            return if (!lastAssistantReply.isNullOrBlank()) {
                "I just said: \"$lastAssistantReply\", $ownerTitle."
            } else {
                "We have only just begun this session, $ownerTitle, so I had not spoken prior to this."
            }
        }
        if (lower.contains("what did i just say") ||
            lower.contains("what did i say") ||
            lower.contains("what did i ask") ||
            lower.contains("what was i asking") ||
            lower.contains("what were we talking about") ||
            lower.contains("do you remember what we")
        ) {
            return if (!previousUserQuestion.isNullOrBlank()) {
                val replyNote = if (!lastAssistantReply.isNullOrBlank()) " And I replied: \"$lastAssistantReply\"" else ""
                "You just asked: \"$previousUserQuestion\", $ownerTitle.$replyNote"
            } else {
                "You have not asked a prior question in this session yet, $ownerTitle."
            }
        }

        // 2. Creator & Identity
        if (lower.contains("who created you") ||
            lower.contains("who made you") ||
            lower.contains("who built you") ||
            lower.contains("who is your creator") ||
            lower.contains("who is your developer") ||
            lower.contains("who developed you") ||
            lower.contains("who programmed you") ||
            lower.contains("who is octavian")
        ) {
            return "I was architected and built by Octavian, my creator and chief architect, $ownerTitle. You can also support his work on YouTube at Octavian Codes from Settings."
        }
        if (lower.contains("who are you") || lower.contains("what is your name") || lower.contains("introduce yourself")) {
            return "I am Jarvis, your personal AI assistant, $ownerTitle. I am currently running directly on your device via $modelDisplayName ($modelParameterBadge), ready to answer questions, manage your schedule, and control your phone."
        }
        if (lower.contains("who am i") || lower.contains("what is my name")) {
            return if (ownerName.isNotBlank()) {
                "You are $ownerName, $ownerTitle."
            } else {
                "You are my owner, $ownerTitle. If you tell me your name, I will remember it for future sessions."
            }
        }
        if (lower.contains("which model") || lower.contains("what model") || lower.contains("local ai")) {
            return "I am currently operating on-device with $modelDisplayName ($modelSizeLabel, $modelBestUse), $ownerTitle. Your phone tools, memories, and natural conversation engine are all active."
        }
        if (lower.contains("what can you do") || norm == "help" || lower.contains("your capabilities")) {
            return "At your service, $ownerTitle. I can answer questions naturally, handle math and unit conversions, run live web research and fact-checks when online, switch between Study, Research, Tasks, and Agentic modes, schedule recurring tasks, recall your personal memories, and control your phone — from opening apps and YouTube to alarms, timers, flashlight, calls, messages, and diagnostics."
        }

        // 3. Greetings, social warmth, emotional check-ins, humor & motivation
        if (norm in setOf("hi", "hello", "hey", "hey jarvis", "hi jarvis", "hello jarvis", "jarvis", "yo", "sup", "hola", "namaste")) {
            val hour = runCatching { LocalTime.now().hour }.getOrDefault(12)
            val salutation = when (hour) {
                in 5..11 -> "Good morning"
                in 12..16 -> "Good afternoon"
                else -> "Good evening"
            }
            return "$salutation, $ownerTitle. All systems are online and at your disposal. What can I help you with?"
        }
        if (lower.startsWith("good morning")) {
            return "Good morning, $ownerTitle. Everything is online and ready for the day. Where shall we begin?"
        }
        if (lower.startsWith("good afternoon")) {
            return "Good afternoon, $ownerTitle. I hope the day is treating you well. How may I assist?"
        }
        if (lower.startsWith("good evening")) {
            return "Good evening, $ownerTitle. Systems are running quietly and smoothly. What do you need?"
        }
        if (lower.startsWith("good night") || lower.startsWith("goodnight")) {
            return "Good night, $ownerTitle. Rest well — I will keep things quiet until you need me."
        }
        if (lower.contains("how are you") ||
            lower.contains("how's it going") ||
            lower.contains("how is it going") ||
            lower.contains("are you okay") ||
            lower.contains("are you ok") ||
            lower.contains("are you there") ||
            lower.contains("you there") ||
            lower.contains("what's up") ||
            lower.contains("whats up")
        ) {
            return "All systems are nominal and running smoothly, $ownerTitle. How are you getting on today?"
        }
        if (Regex("""^(?:thank you|thanks|thx|tysm|appreciate it|much appreciated|cheers|good job|well done|nice work|awesome|great job)\b""").containsMatchIn(norm)) {
            return "Always a pleasure, $ownerTitle. Just let me know if there is anything else you need."
        }
        if (Regex("""^(?:bye|goodbye|see you|see ya|catch you later|that's all|thats all|signing off)\b""").containsMatchIn(norm)) {
            return "Very well, $ownerTitle. I will be right here whenever you call."
        }
        if (lower.contains("i'm sorry") || lower.contains("im sorry") || norm == "sorry" || lower.contains("my bad")) {
            return "No apology necessary, $ownerTitle. Let us pick right back up whenever you are ready."
        }
        if (lower.contains("i'm tired") || lower.contains("i am tired") || lower.contains("exhausted") || lower.contains("sleepy")) {
            return "It sounds like you have earned a proper breather, $ownerTitle. Step away from the screen for a few minutes, drink some water, and if it is late, let tomorrow carry the rest."
        }
        if (lower.contains("i'm stressed") || lower.contains("i am stressed") || lower.contains("anxious") || lower.contains("overwhelmed")) {
            return "Take a slow breath, $ownerTitle. When everything feels urgent at once, picking just one small task and ignoring the rest for ten minutes usually restores clarity. Tell me what is weighing on you and we can break it down together."
        }
        if (lower.contains("i'm bored") || lower.contains("i am bored")) {
            return "We can remedy that easily, $ownerTitle. We could dive into a curiosity topic in Study mode, queue up music on YouTube, or tackle a quick project you have been putting off."
        }
        if (lower.contains("joke") || lower.contains("make me laugh") || lower.contains("something funny")) {
            val idx = abs((clean.hashCode() + LocalTime.now().minute)) % dryJokes.size
            return dryJokes[idx]
        }
        if (lower.contains("motivate") || lower.contains("inspire") || lower.contains("motivation") || lower.contains("cheer me up")) {
            val idx = abs((clean.hashCode() + LocalTime.now().minute)) % groundedMotivations.size
            return groundedMotivations[idx]
        }
        if (lower.contains("do you like me") || lower.contains("love you")) {
            return "You have my undivided loyalty and professional esteem, $ownerTitle — which is about as affectionate as an engineering-grade AI gets."
        }

        // 3b. Quick counting / spelling / letter counting
        Regex("""count (?:to|up to) (\d{1,3})""").find(lower)?.let { match ->
            val limit = match.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 10
            return (1..limit).joinToString(", ") + "."
        }
        Regex("""(?:how many letters (?:are )?in|length of(?: the word)?)\s+["']?([a-zA-Z]+)["']?""").find(lower)?.let { match ->
            val word = match.groupValues[1]
            return "The word \"$word\" has ${word.length} letters, $ownerTitle."
        }

        // 4. Math & Unit Conversions
        OfflineMathAndUnitEngine.tryEvaluate(clean, ownerTitle)?.let { return it }

        // 5. Date, Day & Time questions
        tryAnswerDateOrTime(lower, ownerTitle)?.let { return it }

        // 6. Direct personal memory recall
        memoryLookup(lower)?.let { matchedMemory ->
            return "From what you have told me, $ownerTitle: $matchedMemory."
        }
        if (lower.contains("memor") || lower.contains("my notes") || lower.contains("know about me")) {
            allMemoriesSummary()?.let { summary ->
                return "Here is what I currently remember about you, $ownerTitle:\n$summary"
            }
        }

        return null
    }

    /**
     * Returns a verified deterministic/knowledge-base answer if the user's message matches
     * conversation recall, identity, greetings, math/units, calendar date/time, saved memories,
     * counting/spelling, or our curated knowledge base — or `null` otherwise.
     */
    fun tryAnswerVerified(
        userText: String,
        ownerTitle: String,
        ownerName: String,
        modelDisplayName: String,
        modelParameterBadge: String,
        modelSizeLabel: String,
        modelBestUse: String,
        systemPrompt: String,
        priorDialogue: List<LlmMessage>,
        memoryLookup: (String) -> String?,
        allMemoriesSummary: () -> String?,
    ): String? {
        tryAnswerDeterministic(
            userText = userText,
            ownerTitle = ownerTitle,
            ownerName = ownerName,
            modelDisplayName = modelDisplayName,
            modelParameterBadge = modelParameterBadge,
            modelSizeLabel = modelSizeLabel,
            modelBestUse = modelBestUse,
            priorDialogue = priorDialogue,
            memoryLookup = memoryLookup,
            allMemoriesSummary = allMemoriesSummary,
        )?.let { return it }

        val clean = userText.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val norm = lower.replace(Regex("""[^\p{L}\p{N}\s'/%+\-*^().]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val mode = extractActiveMode(systemPrompt)

        // 7. Comprehensive Offline Knowledge Base (Natural prose + mode-aware framing)
        matchKnowledgeEntry(norm)?.let { entry ->
            return formatKnowledgeForMode(entry, mode, ownerTitle)
        }

        // 8. Writing, drafting & brainstorming
        if (lower.startsWith("write ") || lower.startsWith("draft ") || lower.startsWith("compose ") ||
            lower.contains("write a ") || lower.contains("draft a ") || lower.contains("write an ") || lower.contains("draft an ")
        ) {
            return buildNaturalDraft(clean, lower, ownerTitle, ownerName)
        }

        return null
    }

    private fun tryAnswerDateOrTime(lower: String, ownerTitle: String): String? {
        val today = runCatching { LocalDate.now() }.getOrNull() ?: return null
        val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
        val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

        return when {
            lower.contains("what day is tomorrow") || lower.contains("tomorrow's date") || lower.contains("date tomorrow") -> {
                val tomorrow = today.plusDays(1)
                "Tomorrow is ${tomorrow.format(dateFmt)}, $ownerTitle."
            }
            lower.contains("what day was yesterday") || lower.contains("yesterday's date") -> {
                val yesterday = today.minusDays(1)
                "Yesterday was ${yesterday.format(dateFmt)}, $ownerTitle."
            }
            (lower.contains("what") || lower.contains("tell")) && (lower.contains("today's date") || lower.contains("todays date") || lower.contains("date today") || lower.contains("what day is it") || lower.contains("what date is it")) -> {
                "Today is ${today.format(dateFmt)}, $ownerTitle."
            }
            lower.contains("what year is it") || lower.contains("current year") -> {
                "It is ${today.year}, $ownerTitle."
            }
            lower.contains("what month is it") || lower.contains("current month") -> {
                val month = today.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
                "We are in $month, $ownerTitle."
            }
            lower.contains("time") && (lower.contains("what is the time") || lower.contains("what's the time") || lower.contains("current time") || lower.contains("tell me the time")) -> {
                val now = LocalTime.now().format(timeFmt)
                "It is currently $now on ${today.format(dateFmt)}, $ownerTitle."
            }
            else -> null
        }
    }

    private fun matchKnowledgeEntry(norm: String): KnowledgeEntry? {
        // Prefer longer / more specific phrase matches first
        var bestEntry: KnowledgeEntry? = null
        var bestScore = 0
        for (entry in knowledgeBase) {
            for (kw in entry.keywords) {
                val matched = if (kw.contains(' ')) {
                    norm.contains(kw)
                } else {
                    Regex("""\b${Regex.escape(kw)}\b""").containsMatchIn(norm)
                }
                if (matched && kw.length > bestScore) {
                    bestScore = kw.length
                    bestEntry = entry
                }
            }
        }
        return bestEntry
    }

    private fun formatKnowledgeForMode(entry: KnowledgeEntry, mode: String, ownerTitle: String): String {
        return when (mode) {
            "STUDY" -> buildString {
                append(entry.answer)
                if (!entry.studyQuestion.isNullOrBlank()) {
                    append("\n\n")
                    append(entry.studyQuestion)
                }
            }
            "TASKS" -> "${entry.answer} Let me know if you want to save this as a note or turn it into a scheduled task, $ownerTitle."
            else -> entry.answer
        }
    }

    private fun buildNaturalDraft(
        clean: String,
        lower: String,
        ownerTitle: String,
        ownerName: String,
    ): String {
        val signOff = if (ownerName.isNotBlank()) ownerName else "Best regards"
        val topic = clean
            .replace(Regex("""(?i)^(jarvis\s*,?\s*)?(please\s+)?(write|draft|compose)\s+(me\s+)?(a\s+|an\s+)?"""), "")
            .trim()
            .ifBlank { "your request" }

        return when {
            lower.contains("poem") ->
                "Here is a short verse on $topic, $ownerTitle:\n\n" +
                    "Quiet sparks along the glass,\n" +
                    "Steady signals as hours pass,\n" +
                    "Built with care and sharpened sight,\n" +
                    "Turning distant plans to light."

            lower.contains("apology") || lower.contains("apologize") ->
                "Certainly, $ownerTitle. Here is a courteous message:\n\n" +
                    "Hello — I want to sincerely apologize for the delay and any inconvenience caused regarding $topic. I appreciate your patience and will make sure we get this resolved promptly. Warm regards, $signOff."

            lower.contains("leave") || lower.contains("sick") || lower.contains("day off") ->
                "Certainly, $ownerTitle. Here is a clean leave message:\n\n" +
                    "Hello — I will need to take time off today and will be away from my desk. I will ensure anything urgent is covered and follow up as soon as I return. Thank you for understanding, $signOff."

            lower.contains("birthday") ->
                "Here is a warm birthday note, $ownerTitle:\n\n" +
                    "Wishing you a wonderful birthday filled with good health, great company, and an exciting year ahead. Hope today treats you brilliantly."

            else ->
                "Certainly, $ownerTitle. Here is a concise draft for $topic:\n\n" +
                    "Hello — I am reaching out regarding $topic. Please let me know a convenient time to go over the details and next steps. Best regards, $signOff."
        }
    }

    private fun extractCleanTopic(clean: String): String {
        val stripped = clean
            .replace(Regex("""(?i)^(jarvis\s*,?\s*)?(please\s+)?(can you\s+|could you\s+)?(tell me about|tell me|explain|what is|what are|who is|who was|how does|how do|why does|why do|define|describe)\s+"""), "")
            .trim()
            .trimEnd('?', '.', '!')
        return if (stripped.length in 2..70) "\"$stripped\"" else "that topic"
    }
}
