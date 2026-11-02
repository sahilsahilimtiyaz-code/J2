package com.jarvis.assistant.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.argString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.argInt(key: String): Int? =
    runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

internal fun JsonObject.argBoolean(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

internal fun JsonObject.argDouble(key: String): Double? =
    argString(key)?.toDoubleOrNull()

/** Case and punctuation insensitive form used for fuzzy app and contact matching. */
internal fun normalizeName(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

/**
 * Scores how well [candidate] answers [query]. 0 means no useful match; higher is
 * better. Deliberately simple so the owner can predict what "open youtube" picks.
 */
internal fun matchScore(query: String, candidate: String): Int {
    val q = normalizeName(query)
    val c = normalizeName(candidate)
    if (q.isEmpty() || c.isEmpty()) return 0
    return when {
        c == q -> 100
        c.startsWith(q) -> 85
        q.startsWith(c) -> 70
        c.contains(q) -> 60
        q.contains(c) -> 45
        else -> {
            // Fall back to token overlap so "open the camera app" still finds "Camera".
            val tokens = query.lowercase().split(' ', '-', '_').filter { it.length > 2 }
            if (tokens.any { c.contains(normalizeName(it)) }) 30 else 0
        }
    }
}
