package com.jarvis.assistant.brain

import java.io.IOException
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages adapter with streaming text and tool use.
 */
class AnthropicProvider(private val client: OkHttpClient) : LlmProvider {

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<LlmEvent> = flow {
        val systemText = request.messages
            .filter { it.role == LlmRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .trim()

        val payload = buildJsonObject {
            put("model", config.model)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            put("stream", true)
            if (systemText.isNotEmpty()) put("system", systemText)
            put("messages", anthropicMessages(request.messages, request.images))
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("input_schema", tool.parameters)
                                },
                            )
                        }
                    },
                )
            }
        }

        val httpRequest = Request.Builder()
            .url(endpoint(config.baseUrl))
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(httpRequest)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        var generatedToolId = 0

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(LlmEvent.Error(providerErrorMessage(response.code, response.body.string())))
                    return@flow
                }

                val responseBody = response.body

                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    parseFullResponse(responseBody.string())
                    return@flow
                }

                val reader = SseReader(responseBody.charStream().buffered())
                var toolBuilder: ToolCallBuilder? = null

                loop@ while (true) {
                    val event = reader.next() ?: break@loop
                    val data = event.data.trim()
                    if (data.isEmpty()) continue
                    val root = parseJsonObject(data) ?: continue
                    val type = event.event?.takeIf { it.isNotBlank() }
                        ?: root.stringOrNull("type")
                        ?: continue

                    when (type) {
                        "content_block_start" -> {
                            val block = root.objOrNull("content_block")
                            when (block?.stringOrNull("type")) {
                                "text" -> {
                                    block.stringOrNull("text")
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { emit(LlmEvent.TextDelta(it)) }
                                }
                                "tool_use" -> {
                                    toolBuilder = ToolCallBuilder(
                                        id = block.stringOrNull("id").orEmpty(),
                                        name = block.stringOrNull("name").orEmpty(),
                                    )
                                }
                            }
                        }

                        "content_block_delta" -> {
                            val delta = root.objOrNull("delta")
                            when (delta?.stringOrNull("type")) {
                                "text_delta" -> {
                                    delta.stringOrNull("text")
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { emit(LlmEvent.TextDelta(it)) }
                                }
                                "input_json_delta" -> {
                                    delta.stringOrNull("partial_json")
                                        ?.let { toolBuilder?.arguments?.append(it) }
                                }
                            }
                        }

                        "content_block_stop" -> {
                            toolBuilder?.let { builder ->
                                if (builder.name.isNotBlank()) {
                                    emit(
                                        LlmEvent.ToolCallReady(
                                            ToolCall(
                                                id = builder.id.ifBlank { "tool_${generatedToolId++}" },
                                                name = builder.name,
                                                argumentsJson = builder.arguments.toString().ifBlank { "{}" },
                                            ),
                                        ),
                                    )
                                }
                            }
                            toolBuilder = null
                        }

                        "message_stop" -> break@loop

                        "error" -> {
                            emit(
                                LlmEvent.Error(
                                    extractProviderError(data) ?: "Anthropic returned an error.",
                                ),
                            )
                            return@flow
                        }
                    }
                }

                toolBuilder?.let { builder ->
                    if (builder.name.isNotBlank()) {
                        emit(
                            LlmEvent.ToolCallReady(
                                ToolCall(
                                    id = builder.id.ifBlank { "tool_${generatedToolId++}" },
                                    name = builder.name,
                                    argumentsJson = builder.arguments.toString().ifBlank { "{}" },
                                ),
                            ),
                        )
                    }
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
            emit(LlmEvent.Error(e.message?.take(400) ?: "The Anthropic request failed."))
        } finally {
            cancelHandle.dispose()
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<LlmEvent>.parseFullResponse(body: String) {
        val root = parseJsonObject(body)
        if (root == null) {
            emit(LlmEvent.Error("Anthropic returned a response that was not valid JSON."))
            return
        }
        if (root["error"] != null) {
            emit(LlmEvent.Error(extractProviderError(body) ?: "Anthropic returned an error."))
            return
        }
        root.arrayOrNull("content")
            ?.objects()
            .orEmpty()
            .forEach { block ->
                when (block.stringOrNull("type")) {
                    "text" -> {
                        block.stringOrNull("text")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(LlmEvent.TextDelta(it)) }
                    }
                    "tool_use" -> {
                        val name = block.stringOrNull("name")?.takeIf { it.isNotBlank() }
                        if (name != null) {
                            emit(
                                LlmEvent.ToolCallReady(
                                    ToolCall(
                                        id = block.stringOrNull("id")?.takeIf { it.isNotBlank() }
                                            ?: "tool_${name}",
                                        name = name,
                                        argumentsJson = block["input"]?.toString() ?: "{}",
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        emit(LlmEvent.Done)
    }

    private fun anthropicMessages(
        messages: List<LlmMessage>,
        images: List<AttachedImage>?,
    ): JsonArray {
        data class WireMessage(val role: String, val blocks: MutableList<JsonObject>)

        val wire = mutableListOf<WireMessage>()
        fun addMessage(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            val last = wire.lastOrNull()
            if (last != null && last.role == role) {
                last.blocks += blocks
            } else {
                wire += WireMessage(role, blocks.toMutableList())
            }
        }

        messages.forEach { message ->
            when (message.role) {
                LlmRole.SYSTEM -> Unit
                LlmRole.USER -> {
                    if (message.content.isNotBlank()) {
                        addMessage(
                            "user",
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", message.content)
                                },
                            ),
                        )
                    }
                }

                LlmRole.ASSISTANT -> {
                    val blocks = mutableListOf<JsonObject>()
                    if (message.content.isNotBlank()) {
                        blocks += buildJsonObject {
                            put("type", "text")
                            put("text", message.content)
                        }
                    }
                    message.toolCalls.forEach { call ->
                        blocks += buildJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", parseJsonObjectOrEmpty(call.argumentsJson))
                        }
                    }
                    addMessage("assistant", blocks)
                }

                LlmRole.TOOL -> {
                    val id = message.toolCallId
                    if (!id.isNullOrBlank()) {
                        addMessage(
                            "user",
                            listOf(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", id)
                                    put("content", message.content)
                                },
                            ),
                        )
                    }
                }
            }
        }

        images?.takeIf { it.isNotEmpty() }?.forEach { image ->
            val blocks = mutableListOf<JsonObject>()
            if (image.caption.isNotBlank()) {
                blocks += buildJsonObject {
                    put("type", "text")
                    put("text", image.caption)
                }
            }
            blocks += buildJsonObject {
                put("type", "image")
                put(
                    "source",
                    buildJsonObject {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put(
                            "data",
                            java.util.Base64.getEncoder().encodeToString(image.bytes),
                        )
                    },
                )
            }
            addMessage("user", blocks)
        }

        return buildJsonArray {
            wire.forEach { message ->
                add(
                    buildJsonObject {
                        put("role", message.role)
                        put(
                            "content",
                            buildJsonArray {
                                message.blocks.forEach { block -> add(block) }
                            },
                        )
                    },
                )
            }
        }
    }

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/messages")) base else "$base/messages"
    }

    private class ToolCallBuilder(
        val id: String,
        val name: String,
    ) {
        val arguments = StringBuilder()
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val USER_AGENT = "Jarvis/0.2 (Android)"
    }
}
