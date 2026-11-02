package com.jarvis.assistant.brain

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal val JsonSupport = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun parseJsonObject(raw: String): JsonObject? =
    runCatching { JsonSupport.parseToJsonElement(raw).jsonObject }.getOrNull()

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.objOrNull(key: String): JsonObject? =
    this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? =
    this[key] as? JsonArray

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.rawOrNull(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.toString()

internal fun parseJsonObjectOrEmpty(raw: String): JsonObject =
    parseJsonObject(raw) ?: JsonObject(emptyMap())

internal fun parseToolResult(raw: String): JsonObject {
    val parsed = parseJsonObject(raw)
    if (parsed != null) return parsed
    return JsonObject(mapOf("result" to JsonPrimitive(raw)))
}

internal fun providerErrorMessage(status: Int, body: String): String {
    val detail = extractProviderError(body)
    val base = when (status) {
        400 -> "The provider rejected the request"
        401, 403 -> "The API key was rejected or does not have access to this model"
        404 -> "The endpoint or model was not found"
        408 -> "The provider timed out"
        413 -> "The request was too large for this model"
        422 -> "The provider could not process the request"
        429 -> "The provider rate limit was reached"
        in 500..599 -> "The provider had a server problem"
        else -> "The provider returned HTTP $status"
    }
    return if (detail.isNullOrBlank()) "$base." else "$base: $detail"
}

internal fun extractProviderError(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    val parsed = parseJsonObject(trimmed)
    if (parsed != null) {
        val error = parsed["error"]
        if (error is JsonObject) {
            error.stringOrNull("message")?.let { return it.take(500) }
            error.stringOrNull("status")?.let { return it.take(500) }
        }
        if (error is JsonPrimitive) {
            error.contentOrNull?.let { return it.take(500) }
        }
        parsed.stringOrNull("message")?.let { return it.take(500) }
        parsed.stringOrNull("detail")?.let { return it.take(500) }
    }
    return trimmed.take(500)
}

internal fun networkErrorMessage(error: IOException): String = when (error) {
    is UnknownHostException ->
        "No network connection, or the provider host could not be found."
    is SocketTimeoutException ->
        "The provider took too long to respond."
    is SSLException ->
        "The secure connection to the provider failed."
    else ->
        "Network error: ${error.message?.take(300) ?: "connection failed"}."
}

internal fun JsonArray?.objects(): List<JsonObject> =
    this?.mapNotNull { it as? JsonObject } ?: emptyList()
