package com.jarvis.assistant.brain

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Gemini generateContent adapter with streaming and function calling.
 * The key is sent in the x-goog-api-key header rather than the URL.
 */
class GeminiProvider(private val client: OkHttpClient) : LlmProvider {

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<LlmEvent> = flow {
        val systemText = request.messages
            .filter { it.role == LlmRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .trim()

        val payload = buildJsonObject {
            if (systemText.isNotEmpty()) {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", systemText) })
                            },
                        )
                    },
                )
            }
            put("contents", geminiContents(request.messages, request.images))
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "functionDeclarations",
                                    buildJsonArray {
                                        request.tools.forEach { tool ->
                                            add(
                                                buildJsonObject {
                                                    put("name", tool.name)
                                                    put("description", tool.description)
                                                    put("parameters", geminiSchema(tool.parameters))
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    },
                )
            }
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", request.temperature)
                    put("maxOutputTokens", request.maxTokens)
                },
            )
        }

        val httpRequest = Request.Builder()
            .url(endpoint(config))
            .header("x-goog-api-key", config.apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(httpRequest)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        var toolCounter = 0

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(LlmEvent.Error(providerErrorMessage(response.code, response.body.string())))
                    return@flow
                }

                val responseBody = response.body

                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    val parsed = runCatching {
                        JsonSupport.parseToJsonElement(responseBody.string())
                    }.getOrNull()
                    val roots = when (parsed) {
                        is JsonArray -> parsed.objects()
                        is JsonObject -> listOf(parsed)
                        else -> emptyList()
                    }
                    if (roots.isEmpty()) {
                        emit(LlmEvent.Error("Gemini returned a response that was not valid JSON."))
                        return@flow
                    }
                    for (root in roots) {
                        val ok = parseGeminiObject(root) { call ->
                            emit(
                                LlmEvent.ToolCallReady(
                                    call.copy(id = "gemini_${toolCounter++}"),
                                ),
                            )
                        }
                        if (!ok) return@flow
                    }
                    emit(LlmEvent.Done)
                    return@flow
                }

                val reader = SseReader(responseBody.charStream().buffered())
                loop@ while (true) {
                    val event = reader.next() ?: break@loop
                    val data = event.data.trim()
                    if (data.isEmpty()) continue
                    val root = parseJsonObject(data) ?: continue
                    val ok = parseGeminiObject(root) { call ->
                        emit(
                            LlmEvent.ToolCallReady(
                                call.copy(id = call.id.ifBlank { "gemini_${toolCounter++}" }),
                            ),
                        )
                    }
                    if (!ok) return@flow
                }
                emit(LlmEvent.Done)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            emit(LlmEvent.Error(networkErrorMessage(e)))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emit(LlmEvent.Error(e.message?.take(400) ?: "The Gemini request failed."))
        } finally {
            cancelHandle.dispose()
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<LlmEvent>.parseGeminiObject(
        root: JsonObject,
        emitTool: suspend (ToolCall) -> Unit,
    ): Boolean {
        if (root["error"] != null) {
            emit(LlmEvent.Error(extractProviderError(root.toString()) ?: "Gemini returned an error."))
            return false
        }

        val blockReason = root.objOrNull("promptFeedback")?.stringOrNull("blockReason")
        if (!blockReason.isNullOrBlank()) {
            emit(LlmEvent.Error("Gemini blocked this request: ${blockReason.lowercase().replace('_', ' ')}."))
            return false
        }

        val candidates = root.arrayOrNull("candidates")?.objects().orEmpty()
        for (candidate in candidates) {
            val parts = candidate.objOrNull("content")?.arrayOrNull("parts")?.objects().orEmpty()
            for (part in parts) {
                // Gemini 2.5+ can return thought summaries as parts. They are model
                // reasoning, not answer text, so keep them out of the conversation.
                if (part.booleanOrNull("thought") == true) continue

                part.stringOrNull("text")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(LlmEvent.TextDelta(it)) }

                val functionCall = part.objOrNull("functionCall")
                if (functionCall != null) {
                    val name = functionCall.stringOrNull("name")?.takeIf { it.isNotBlank() }
                    if (name != null) {
                        val arguments = functionCall["args"]?.let { args ->
                            when (args) {
                                is JsonObject -> args.toString()
                                is JsonPrimitive -> args.contentOrNull ?: "{}"
                                else -> "{}"
                            }
                        } ?: "{}"
                        emitTool(
                            ToolCall(
                                id = "",
                                name = name,
                                argumentsJson = arguments,
                            ),
                        )
                    }
                }
            }

            val finishReason = candidate.stringOrNull("finishReason")
            if (!finishReason.isNullOrBlank() && finishReason in BLOCKED_FINISH_REASONS) {
                val detail = candidate.stringOrNull("finishMessage")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { " $it" }
                    .orEmpty()
                emit(
                    LlmEvent.Error(
                        "Gemini stopped the reply (${finishReason.lowercase().replace('_', ' ')}).$detail",
                    ),
                )
                return false
            }
        }
        return true
    }

    private fun geminiContents(
        messages: List<LlmMessage>,
        images: List<AttachedImage>?,
    ): JsonArray {
        data class Turn(val role: String, val parts: MutableList<JsonObject>)

        val turns = mutableListOf<Turn>()
        fun addPart(role: String, part: JsonObject) {
            val last = turns.lastOrNull()
            if (last != null && last.role == role) {
                last.parts += part
            } else {
                turns += Turn(role, mutableListOf(part))
            }
        }

        messages.forEach { message ->
            when (message.role) {
                LlmRole.SYSTEM -> Unit
                LlmRole.USER -> {
                    if (message.content.isNotBlank()) {
                        addPart(
                            "user",
                            buildJsonObject { put("text", message.content) },
                        )
                    }
                }
                LlmRole.ASSISTANT -> {
                    if (message.content.isNotBlank()) {
                        addPart(
                            "model",
                            buildJsonObject { put("text", message.content) },
                        )
                    }
                    message.toolCalls.forEach { call ->
                        addPart(
                            "model",
                            buildJsonObject {
                                put(
                                    "functionCall",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("args", parseJsonObjectOrEmpty(call.argumentsJson))
                                    },
                                )
                            },
                        )
                    }
                }
                LlmRole.TOOL -> {
                    val name = message.toolName?.takeIf { it.isNotBlank() }
                    if (name != null) {
                        addPart(
                            "user",
                            buildJsonObject {
                                put(
                                    "functionResponse",
                                    buildJsonObject {
                                        put("name", name)
                                        put("response", parseToolResult(message.content))
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

        images?.takeIf { it.isNotEmpty() }?.forEach { image ->
            if (image.caption.isNotBlank()) {
                addPart("user", buildJsonObject { put("text", image.caption) })
            }
            addPart(
                "user",
                buildJsonObject {
                    put(
                        "inline_data",
                        buildJsonObject {
                            put("mime_type", "image/jpeg")
                            put(
                                "data",
                                java.util.Base64.getEncoder().encodeToString(image.bytes),
                            )
                        },
                    )
                },
            )
        }

        return buildJsonArray {
            for (turn in turns) {
                add(
                    buildJsonObject {
                        put("role", turn.role)
                        put(
                            "parts",
                            buildJsonArray {
                                turn.parts.forEach { part -> add(part) }
                            },
                        )
                    },
                )
            }
        }
    }

    /**
     * Gemini's Schema object is a narrower, upper-case dialect of JSON Schema.
     * Convert the shared tool schema so an OpenAI-oriented declaration does not
     * get rejected for fields such as additionalProperties or lower-case types.
     */
    private fun geminiSchema(schema: JsonObject): JsonObject = buildJsonObject {
        schema.forEach { (key, value) ->
            when (key) {
                "type" -> (value as? JsonPrimitive)?.contentOrNull?.let {
                    put("type", it.uppercase(Locale.US))
                }
                "format" -> (value as? JsonPrimitive)?.contentOrNull?.let {
                    put("format", it.uppercase(Locale.US))
                }
                "properties" -> (value as? JsonObject)?.let { properties ->
                    put(
                        "properties",
                        buildJsonObject {
                            properties.forEach { (name, property) ->
                                if (property is JsonObject) {
                                    put(name, geminiSchema(property))
                                }
                            }
                        },
                    )
                }
                "items" -> (value as? JsonObject)?.let {
                    put("items", geminiSchema(it))
                }
                "required", "description", "enum", "nullable", "minimum", "maximum",
                "minItems", "maxItems", "minProperties", "maxProperties",
                "minLength", "maxLength", "pattern" -> put(key, value)
                // Anything else (for example additionalProperties or $schema) is
                // not part of Gemini's Schema message and is deliberately dropped.
            }
        }
    }

    private fun endpoint(config: ProviderConfig): String {
        var base = config.baseUrl.trim().trimEnd('/')
        if (base.endsWith("/models")) base = base.removeSuffix("/models")
        val rawModel = config.model.trim().removePrefix("models/")
        val model = URLEncoder.encode(rawModel, StandardCharsets.UTF_8)
        return "$base/models/$model:streamGenerateContent?alt=sse"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT = "Jarvis/0.2 (Android)"

        val BLOCKED_FINISH_REASONS = setOf(
            "SAFETY",
            "RECITATION",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
            "SPII",
            "MALFORMED_FUNCTION_CALL",
        )
    }
}
