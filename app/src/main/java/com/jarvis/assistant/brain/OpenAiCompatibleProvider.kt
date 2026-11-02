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
 * OpenAI Chat Completions adapter. The configurable base URL covers OpenRouter,
 * OpenAI, Groq, Together, a local Ollama server and any compatible endpoint.
 */
class OpenAiCompatibleProvider(private val client: OkHttpClient) : LlmProvider {

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<LlmEvent> = flow {
        val normalizedBaseUrl = normalizeBaseUrl(config.baseUrl)
        val normalizedModel = normalizeModelId(normalizedBaseUrl, config.model)

        fun buildHttpRequest(includeTools: Boolean): Request {
            val payload = buildJsonObject {
                put("model", normalizedModel)
                put("stream", true)
                put("temperature", request.temperature)
                put("max_tokens", request.maxTokens)
                put("messages", openAiMessages(request.messages, request.images))
                if (includeTools && request.tools.isNotEmpty()) {
                    put("tools", openAiTools(request.tools))
                    put("tool_choice", "auto")
                }
            }

            return Request.Builder()
                .url(chatEndpoint(normalizedBaseUrl))
                .apply {
                    if (config.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${config.apiKey.trim()}")
                    }
                    if (normalizedBaseUrl.contains("openrouter.ai", ignoreCase = true)) {
                        header("X-Title", "Jarvis")
                        header("HTTP-Referer", "https://jarvis.local")
                    }
                }
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("User-Agent", USER_AGENT)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
        }

        var activeCall = client.newCall(buildHttpRequest(includeTools = true))
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { activeCall.cancel() }

        try {
            var response = activeCall.execute()

            // If a free/reasoning model rejects the request because it does not support tools,
            // automatically retry once without tools so chat still works seamlessly.
            if (!response.isSuccessful && request.tools.isNotEmpty()) {
                val firstCode = response.code
                val firstErrorBody = response.use { it.body.string() }
                if (shouldRetryWithoutTools(firstCode, firstErrorBody)) {
                    activeCall = client.newCall(buildHttpRequest(includeTools = false))
                    response = activeCall.execute()
                } else {
                    emit(LlmEvent.Error(providerErrorMessage(firstCode, firstErrorBody)))
                    return@flow
                }
            }

            response.use { res ->
                if (!res.isSuccessful) {
                    emit(LlmEvent.Error(providerErrorMessage(res.code, res.body.string())))
                    return@flow
                }

                val responseBody = res.body

                val contentType = res.header("Content-Type").orEmpty()
                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    parseFullResponse(responseBody.string())
                    return@flow
                }

                val reader = SseReader(responseBody.charStream().buffered())
                val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
                var emittedContent = false
                val reasoningFallback = StringBuilder()

                loop@ while (true) {
                    val event = reader.next() ?: break@loop
                    val data = event.data.trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break@loop

                    val root = parseJsonObject(data) ?: continue
                    if (root["error"] != null) {
                        emit(LlmEvent.Error(extractProviderError(data) ?: "The provider returned an error."))
                        return@flow
                    }

                    val choices = root.arrayOrNull("choices")?.objects().orEmpty()
                    for (choice in choices) {
                        val delta = choice.objOrNull("delta")
                        if (delta != null) {
                            delta.stringOrNull("content")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    emittedContent = true
                                    emit(LlmEvent.TextDelta(it))
                                }

                            delta.stringOrNull("reasoning_content")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { reasoningFallback.append(it) }

                            delta.arrayOrNull("tool_calls")
                                ?.objects()
                                .orEmpty()
                                .forEach { part ->
                                    accumulateToolCall(part, toolCalls)
                                }
                        }

                        // Some compatible servers send a complete message instead
                        // of a delta, especially on the final chunk.
                        choice.objOrNull("message")?.let { message ->
                            message.stringOrNull("content")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    emittedContent = true
                                    emit(LlmEvent.TextDelta(it))
                                }
                            message.stringOrNull("reasoning_content")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { reasoningFallback.append(it) }
                            message.arrayOrNull("tool_calls")
                                ?.objects()
                                .orEmpty()
                                .forEach { part ->
                                    accumulateCompleteToolCall(part, toolCalls)
                                }
                        }
                    }
                }

                if (!emittedContent && toolCalls.isEmpty() && reasoningFallback.isNotEmpty()) {
                    emit(LlmEvent.TextDelta(reasoningFallback.toString().trim()))
                }

                emitToolCalls(toolCalls)
                emit(LlmEvent.Done)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            emit(LlmEvent.Error(networkErrorMessage(e)))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emit(LlmEvent.Error(e.message?.take(400) ?: "The provider request failed."))
        } finally {
            cancelHandle.dispose()
            activeCall.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<LlmEvent>.parseFullResponse(body: String) {
        val root = parseJsonObject(body)
        if (root == null) {
            emit(LlmEvent.Error("The provider returned a response that was not valid JSON."))
            return
        }
        if (root["error"] != null) {
            emit(LlmEvent.Error(extractProviderError(body) ?: "The provider returned an error."))
            return
        }
        val choice = root.arrayOrNull("choices")?.objects()?.firstOrNull()
        val message = choice?.objOrNull("message")
        if (message == null) {
            emit(LlmEvent.Error("The provider returned no message."))
            return
        }
        val content = message.stringOrNull("content")?.takeIf { it.isNotEmpty() }
            ?: message.stringOrNull("reasoning_content")?.takeIf { it.isNotEmpty() }
        content?.let { emit(LlmEvent.TextDelta(it)) }
        message.arrayOrNull("tool_calls")
            ?.objects()
            .orEmpty()
            .forEach { part ->
                val function = part.objOrNull("function") ?: return@forEach
                val name = function.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return@forEach
                val arguments = function["arguments"]
                    ?.let { arg ->
                        if (arg is JsonPrimitive) arg.contentOrNull else arg.toString()
                    }
                    ?.takeIf { it.isNotBlank() }
                    ?: "{}"
                emit(
                    LlmEvent.ToolCallReady(
                        ToolCall(
                            id = part.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: "call_${name}",
                            name = name,
                            argumentsJson = arguments,
                        ),
                    ),
                )
            }
        emit(LlmEvent.Done)
    }

    private fun accumulateToolCall(
        part: kotlinx.serialization.json.JsonObject,
        accumulator: MutableMap<Int, ToolCallAccumulator>,
    ) {
        val index = part.intOrNull("index") ?: accumulator.size
        val call = accumulator.getOrPut(index) { ToolCallAccumulator(index) }
        part.stringOrNull("id")?.takeIf { it.isNotBlank() }?.let { call.id = it }
        val function = part.objOrNull("function") ?: return
        function.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let { call.name = it }
        function["arguments"]?.let { arg ->
            if (arg is JsonPrimitive) {
                call.arguments.append(arg.contentOrNull.orEmpty())
            } else {
                call.arguments.append(arg.toString())
            }
        }
    }

    private fun accumulateCompleteToolCall(
        part: kotlinx.serialization.json.JsonObject,
        accumulator: MutableMap<Int, ToolCallAccumulator>,
    ) {
        val index = part.intOrNull("index") ?: accumulator.size
        val call = accumulator.getOrPut(index) { ToolCallAccumulator(index) }
        part.stringOrNull("id")?.takeIf { it.isNotBlank() }?.let { call.id = it }
        val function = part.objOrNull("function") ?: return
        function.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let { call.name = it }
        function["arguments"]?.let { arg ->
            val text = if (arg is JsonPrimitive) arg.contentOrNull.orEmpty() else arg.toString()
            call.arguments.append(text)
        }
    }

    private suspend fun FlowCollector<LlmEvent>.emitToolCalls(
        accumulator: Map<Int, ToolCallAccumulator>,
    ) {
        accumulator.values.forEach { call ->
            if (call.name.isNotBlank()) {
                emit(
                    LlmEvent.ToolCallReady(
                        ToolCall(
                            id = call.id.ifBlank { "call_${call.index}" },
                            name = call.name,
                            argumentsJson = call.arguments.toString().ifBlank { "{}" },
                        ),
                    ),
                )
            }
        }
    }

    private fun openAiMessages(
        messages: List<LlmMessage>,
        images: List<AttachedImage>?,
    ) = buildJsonArray {
        messages.forEach { message ->
            add(
                buildJsonObject {
                    when (message.role) {
                        LlmRole.SYSTEM -> {
                            put("role", "system")
                            put("content", message.content)
                        }
                        LlmRole.USER -> {
                            put("role", "user")
                            put("content", message.content)
                        }
                        LlmRole.ASSISTANT -> {
                            put("role", "assistant")
                            put("content", message.content)
                            if (message.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        message.toolCalls.forEach { call ->
                                            add(
                                                buildJsonObject {
                                                    put("id", call.id)
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", call.name)
                                                            put("arguments", call.argumentsJson.ifBlank { "{}" })
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        LlmRole.TOOL -> {
                            put("role", "tool")
                            put("tool_call_id", message.toolCallId.orEmpty())
                            put("content", message.content)
                        }
                    }
                },
            )
        }
        images?.takeIf { it.isNotEmpty() }?.let { attached ->
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put(
                                "text",
                                attached.joinToString("\n") { image ->
                                    "Screen capture attached to this message. ${
                                        image.caption.ifBlank {
                                            "This is the live screenshot of the phone screen. Look at it, then act."
                                        }
                                    }"
                                },
                            )
                        },
                    )
                    attached.forEach { image ->
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    buildJsonObject {
                                        put(
                                            "url",
                                            "data:image/jpeg;base64," +
                                                java.util.Base64.getEncoder()
                                                    .encodeToString(image.bytes),
                                        )
                                    },
                                )
                            },
                        )
                    }
                })
            })
        }
    }

    private fun openAiTools(tools: List<ToolDefinition>) = buildJsonArray {
        tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        },
                    )
                },
            )
        }
    }

    private fun chatEndpoint(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun shouldRetryWithoutTools(statusCode: Int, errorBody: String): Boolean {
        if (statusCode !in listOf(400, 404, 422, 500)) return false
        val lower = errorBody.lowercase()
        return lower.contains("tool") ||
            lower.contains("function") ||
            lower.contains("not support") ||
            lower.contains("unsupported")
    }

    private class ToolCallAccumulator(val index: Int) {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "Jarvis/0.2 (Android)"

        /**
         * Normalizes global provider base URLs so even shorthand entries like
         * `https://api.z.ai`, `https://open.bigmodel.cn`, `https://opencode.ai`,
         * `https://apihub.agnes-ai.com`, or `https://integrate.api.nvidia.com`
         * resolve to their exact OpenAI-compatible gateway root.
         */
        fun normalizeBaseUrl(rawUrl: String): String {
            val clean = rawUrl.trim().trimEnd('/')
            val lower = clean.lowercase()
            return when {
                (lower == "https://api.z.ai" || lower == "https://api.z.ai/v1") ->
                    "https://api.z.ai/api/paas/v4"
                (lower == "https://open.bigmodel.cn" || lower == "https://open.bigmodel.cn/v1") ->
                    "https://open.bigmodel.cn/api/paas/v4"
                (lower == "https://opencode.ai" || lower == "https://opencode.ai/v1" || lower == "https://opencode.ai/zen") ->
                    "https://opencode.ai/zen/v1"
                lower == "https://apihub.agnes-ai.com" ->
                    "https://apihub.agnes-ai.com/v1"
                lower == "https://integrate.api.nvidia.com" ->
                    "https://integrate.api.nvidia.com/v1"
                lower == "https://api.cerebras.ai" ->
                    "https://api.cerebras.ai/v1"
                lower == "https://api.sambanova.ai" ->
                    "https://api.sambanova.ai/v1"
                lower == "https://api.mistral.ai" ->
                    "https://api.mistral.ai/v1"
                lower == "https://api.siliconflow.com" ->
                    "https://api.siliconflow.com/v1"
                lower == "https://api.siliconflow.cn" ->
                    "https://api.siliconflow.cn/v1"
                (lower == "https://api.cohere.com" || lower == "https://api.cohere.com/v1") ->
                    "https://api.cohere.com/compatibility/v1"
                lower == "https://router.huggingface.co" ->
                    "https://router.huggingface.co/v1"
                lower == "https://llm.chutes.ai" ->
                    "https://llm.chutes.ai/v1"
                lower == "https://text.pollinations.ai" ->
                    "https://text.pollinations.ai/openai"
                else -> clean
            }
        }

        /**
         * Normalizes provider-prefixed model slugs (such as `opencode/big-pickle`
         * on OpenCode Zen) to the direct model ID expected by the endpoint.
         */
        fun normalizeModelId(baseUrl: String, model: String): String {
            val cleanModel = model.trim()
            val lowerUrl = baseUrl.lowercase()
            if (lowerUrl.contains("opencode.ai") && cleanModel.startsWith("opencode/", ignoreCase = true)) {
                return cleanModel.substringAfter('/')
            }
            return cleanModel
        }
    }
}

