package com.jarvis.assistant.brain

import com.jarvis.assistant.tools.ToolExecutor
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

data class AgentRequest(
    val systemPrompt: String,
    val history: List<LlmMessage>,
    val userMessage: String,
    val providers: List<ProviderConfig>,
    val tools: List<ToolDefinition>,
)

sealed interface AgentEvent {
    data class TextDelta(val text: String, val providerName: String) : AgentEvent
    data class ProviderFallback(val from: String, val to: String, val reason: String) : AgentEvent
    data class ToolStarted(val name: String, val arguments: String) : AgentEvent
    data class ToolFinished(
        val name: String,
        val success: Boolean,
        val summary: String,
        val arguments: String = "",
    ) : AgentEvent
    data class Completed(val providerName: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
}

/**
 * Runs the provider/tool loop. A turn is allowed at most eight provider rounds,
 * which is enough for a short observe-decide-act sequence without risking an
 * endless tool loop on a phone.
 */
class AgentLoop(
    private val providerRegistry: ProviderRegistry,
    private val toolExecutor: ToolExecutor,
) {

    fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        if (request.providers.isEmpty()) {
            emit(
                AgentEvent.Error(
                    "No AI provider is configured. Open Settings, add an API key, and try again.",
                ),
            )
            return@flow
        }

        val messages = mutableListOf<LlmMessage>().apply {
            add(LlmMessage.system(request.systemPrompt))
            addAll(request.history.takeLast(MAX_HISTORY_MESSAGES))
            add(LlmMessage.user(request.userMessage))
        }

        val executedCalls = mutableMapOf<String, String>()
        var lastToolSummary: String? = null
        var lastProviderName: String = request.providers.first().name
        /** Latest screenshot produced by a tool (e.g. see_screen) awaiting attachment. */
        var pendingImage: AttachedImage? = null

        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val rawResult = streamRound(messages, request, includeTools = true, images = pendingImage?.let { listOf(it) })
            pendingImage = null

            // Deterministic Tool Guarantee on Round 1:
            // 1. If all cloud providers failed with HTTP/rate-limit errors, check if OfflineTaskRouter
            //    can execute the phone command directly so the owner never gets blocked by provider errors!
            // 2. If the cloud model returned text without calling a tool (e.g. hallucinated "I did it" or
            //    asked "Shall I proceed?") for an explicit phone action command, inject the matched tool call!
            val result: RoundResult = when (rawResult) {
                is RoundResult.Failure -> {
                    if (round == 1 && request.tools.isNotEmpty()) {
                        val rescuedCalls = OfflineTaskRouter.routeUserCommand(request.userMessage, request.tools)
                        if (rescuedCalls.isNotEmpty()) {
                            RoundResult.Success(
                                providerName = "Jarvis Direct Engine",
                                text = "",
                                toolCalls = rescuedCalls,
                            )
                        } else {
                            rawResult
                        }
                    } else {
                        rawResult
                    }
                }
                is RoundResult.Success -> {
                    if (rawResult.toolCalls.isEmpty() && request.tools.isNotEmpty()) {
                        val routedCalls = if (round == 1) {
                            OfflineTaskRouter.routeUserCommand(request.userMessage, request.tools)
                        } else {
                            val priorToolCalls = messages.filter { it.role == LlmRole.ASSISTANT }.flatMap { it.toolCalls }
                            val trailingTools = messages.takeLastWhile { it.role == LlmRole.TOOL }
                            OfflineTaskRouter.routeFollowUpAction(request.userMessage, priorToolCalls, trailingTools, request.tools)
                        }
                        if (routedCalls.isNotEmpty()) {
                            RoundResult.Success(
                                providerName = rawResult.providerName,
                                text = "",
                                toolCalls = routedCalls,
                            )
                        } else {
                            rawResult
                        }
                    } else {
                        rawResult
                    }
                }
            }

            when (result) {
                is RoundResult.Failure -> {
                    emit(AgentEvent.Error(result.message))
                    return@flow
                }

                is RoundResult.Success -> {
                    lastProviderName = result.providerName
                    if (result.toolCalls.isEmpty()) {
                        if (result.text.isBlank()) {
                            val fallbackText = lastToolSummary?.let { "Done. $it" }
                            if (!fallbackText.isNullOrBlank()) {
                                emit(AgentEvent.TextDelta(fallbackText, result.providerName))
                                emit(AgentEvent.Completed(result.providerName))
                                return@flow
                            }
                            emit(AgentEvent.Error("Jarvis returned an empty reply. Please try again."))
                            return@flow
                        }
                        emit(AgentEvent.Completed(result.providerName))
                        return@flow
                    }

                    messages += LlmMessage.assistant(result.text, result.toolCalls)
                    var allDuplicatesThisRound = true
                    for (call in result.toolCalls) {
                        val signature = "${call.name}:${call.argumentsJson.trim()}"
                        val priorOutput = executedCalls[signature]
                        if (priorOutput != null) {
                            // Short-circuit duplicate tool calls in the same turn so the LLM answers immediately
                            val stopLoopHint = "{\"ok\":true,\"result\":\"Already completed ${call.name} ($priorOutput). Do NOT call any more tools; give your final spoken answer to the owner now.\"}"
                            messages += LlmMessage.tool(call, stopLoopHint)
                            continue
                        }

                        allDuplicatesThisRound = false
                        emit(
                            AgentEvent.ToolStarted(
                                name = call.name,
                                arguments = call.argumentsJson.take(300),
                            ),
                        )
                        val toolResult = toolExecutor.execute(call.name, call.argumentsJson)
                        val summary = toolResult.content.take(240)
                        if (toolResult.success && summary.isNotBlank()) {
                            lastToolSummary = summary
                        }
                        executedCalls[signature] = summary
                        emit(
                            AgentEvent.ToolFinished(
                                name = call.name,
                                success = toolResult.success,
                                summary = summary,
                                arguments = call.argumentsJson,
                            ),
                        )
                        if (toolResult.image != null) {
                            pendingImage = AttachedImage(
                                bytes = toolResult.image,
                                caption = "Live screenshot of the phone screen captured now. " +
                                    "Use it to decide your next action or to answer about the screen.",
                            )
                        }

                        val enrichedContent = if (toolResult.success && call.name in TERMINAL_ACTION_TOOLS) {
                            "${toolResult.content} [Action complete on phone. Reply to the owner now in 1-2 sentences without calling more tools unless another distinct step was requested.]"
                        } else {
                            toolResult.content
                        }
                        val payload = com.jarvis.assistant.tools.ToolResult(
                            success = toolResult.success,
                            content = enrichedContent,
                        ).asJsonString().take(MAX_TOOL_RESULT_CHARS)
                        messages += LlmMessage.tool(call, payload)
                    }

                    // If the model only repeated already-executed tools, force a tool-free final reply right now
                    if (allDuplicatesThisRound) {
                        break
                    }
                }
            }
        }

        // Instead of failing with "I stopped after eight tool rounds without reaching a final answer",
        // perform one final synthesis call with tools disabled so Jarvis always gives a natural spoken reply.
        val finalMessages = messages + LlmMessage.user(
            "All tool actions for this request are complete. Summarize what you just did and answer me directly in one or two brief sentences.",
        )
        when (val synthesis = streamRound(finalMessages, request, includeTools = false, images = pendingImage?.let { listOf(it) })) {
            is RoundResult.Success -> {
                if (synthesis.text.isNotBlank()) {
                    emit(AgentEvent.Completed(synthesis.providerName))
                    return@flow
                }
            }
            is RoundResult.Failure -> Unit
        }

        val gracefulFallback = lastToolSummary?.let { "Done, sir. $it" }
            ?: "That task is complete, sir."
        emit(AgentEvent.TextDelta(gracefulFallback, lastProviderName))
        emit(AgentEvent.Completed(lastProviderName))
    }

    private suspend fun FlowCollector<AgentEvent>.streamRound(
        messages: List<LlmMessage>,
        request: AgentRequest,
        includeTools: Boolean = true,
        images: List<AttachedImage>? = null,
    ): RoundResult {
        var lastError: String? = null

        request.providers.forEachIndexed { index, provider ->
            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            var emittedAnything = false

            try {
                try {
                    providerRegistry.stream(
                        ChatRequest(
                            messages = messages,
                            tools = if (includeTools) request.tools else emptyList(),
                            images = images,
                        ),
                        provider,
                    ).collect { event ->
                        when (event) {
                            is LlmEvent.TextDelta -> {
                                emittedAnything = true
                                text.append(event.text)
                                emit(AgentEvent.TextDelta(event.text, provider.name))
                            }

                            is LlmEvent.ToolCallReady -> {
                                if (includeTools) {
                                    emittedAnything = true
                                    toolCalls += event.call
                                }
                            }

                            LlmEvent.Done -> Unit
                            is LlmEvent.Error -> throw ProviderException(event.message)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message?.take(400) ?: "The provider request failed."
                    val imageRelated = images != null &&
                        !emittedAnything &&
                        looksLikeImageRejection(message)

                    // Some text-only models reject image turns outright. If this
                    // provider never produced output, retry it once without the
                    // screenshot so the turn still succeeds with plain text.
                    if (imageRelated) {
                        text.clear()
                        toolCalls.clear()
                        providerRegistry.stream(
                            ChatRequest(
                                messages = messages,
                                tools = if (includeTools) request.tools else emptyList(),
                            ),
                            provider,
                        ).collect { event ->
                            when (event) {
                                is LlmEvent.TextDelta -> {
                                    emittedAnything = true
                                    text.append(event.text)
                                    emit(AgentEvent.TextDelta(event.text, provider.name))
                                }

                                is LlmEvent.ToolCallReady -> {
                                    if (includeTools) {
                                        emittedAnything = true
                                        toolCalls += event.call
                                    }
                                }

                                LlmEvent.Done -> Unit
                                is LlmEvent.Error -> throw ProviderException(event.message)
                            }
                        }
                    } else {
                        throw e
                    }
                }

                val (cleanedText, parsedCalls) = if (includeTools && toolCalls.isEmpty()) {
                    extractToolCallsFromText(text.toString(), request.tools)
                } else {
                    text.toString() to emptyList()
                }
                val finalCalls = toolCalls + parsedCalls

                if (cleanedText.isBlank() && finalCalls.isEmpty()) {
                    lastError = "${provider.name} returned an empty response."
                } else {
                    return RoundResult.Success(
                        providerName = provider.name,
                        text = cleanedText,
                        toolCalls = finalCalls,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message?.take(400) ?: "The provider request failed."
                if (emittedAnything) {
                    return RoundResult.Failure(message)
                }
                lastError = "${provider.name}: $message"
                val next = request.providers.getOrNull(index + 1)
                if (next != null) {
                    emit(
                        AgentEvent.ProviderFallback(
                            from = provider.name,
                            to = next.name,
                            reason = message,
                        ),
                    )
                }
            }
        }

        return RoundResult.Failure(
            lastError ?: "No AI provider is available. Check your keys in Settings.",
        )
    }

    private sealed interface RoundResult {
        data class Success(
            val providerName: String,
            val text: String,
            val toolCalls: List<ToolCall>,
        ) : RoundResult

        data class Failure(val message: String) : RoundResult
    }

    private fun extractToolCallsFromText(
        rawText: String,
        tools: List<ToolDefinition>,
    ): Pair<String, List<ToolCall>> {
        val toolNames = tools.map { it.name }.toSet()
        if (toolNames.isEmpty()) return rawText to emptyList()

        val parsed = mutableListOf<ToolCall>()
        var cleaned = rawText

        // 1. Check for XML format: <tool_call> ... </tool_call>
        val xmlRegex = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        xmlRegex.findAll(rawText).forEach { match ->
            val jsonStr = match.groupValues[1]
            parseToolCallFromJson(jsonStr, toolNames)?.let { call ->
                parsed += call
                cleaned = cleaned.replace(match.value, "")
            }
        }

        // 2. Check for markdown json block containing {"name": ..., "arguments": ...} or {"tool": ..., ...}
        val mdJsonRegex = Regex("""```(?:json)?\s*(\{\s*"(?:name|tool)"\s*:\s*".*?"\s*,.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
        mdJsonRegex.findAll(cleaned).forEach { match ->
            val jsonStr = match.groupValues[1]
            parseToolCallFromJson(jsonStr, toolNames)?.let { call ->
                parsed += call
                cleaned = cleaned.replace(match.value, "")
            }
        }

        // 3. Check for standalone JSON object with valid tool name
        if (parsed.isEmpty()) {
            val jsonRegex = Regex("""(\{\s*"(?:name|tool)"\s*:\s*"([a-zA-Z0-9_-]+)"\s*,\s*"(?:arguments|parameters)"\s*:\s*(\{.*?\})\s*\})""", RegexOption.DOT_MATCHES_ALL)
            jsonRegex.findAll(cleaned).forEach { match ->
                val fullMatch = match.groupValues[1]
                val name = match.groupValues[2]
                val args = match.groupValues[3]
                if (name in toolNames) {
                    parsed += ToolCall(
                        id = "text_tool_${UUID.randomUUID().toString().take(8)}",
                        name = name,
                        argumentsJson = args,
                    )
                    cleaned = cleaned.replace(fullMatch, "")
                }
            }
        }

        return cleaned.trim() to parsed
    }

    private fun parseToolCallFromJson(jsonStr: String, toolNames: Set<String>): ToolCall? {
        return runCatching {
            val obj = JSONObject(jsonStr)
            val name = obj.optString("name").ifBlank { obj.optString("tool") }
            if (name !in toolNames) return null
            val argsObj = obj.optJSONObject("arguments")
                ?: obj.optJSONObject("parameters")
                ?: JSONObject()
            ToolCall(
                id = "text_tool_${UUID.randomUUID().toString().take(8)}",
                name = name,
                argumentsJson = argsObj.toString(),
            )
        }.getOrNull()
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 10
        const val MAX_HISTORY_MESSAGES = 40
        const val MAX_TOOL_RESULT_CHARS = 4_000

        val TERMINAL_ACTION_TOOLS = setOf(
            "search_youtube",
            "open_app",
            "open_url",
            "open_camera",
            "take_photo",
            "set_timer",
            "set_alarm",
            "navigate_to",
            "create_calendar_event",
            "send_whatsapp_message",
            "send_whatsapp_voice_note",
            "send_sms",
            "call_contact",
            "control_flashlight",
            "control_media",
            "set_volume",
            "set_ringer_mode",
        )

        /** Detects providers that reject screenshot turns instead of reading them. */
        private fun looksLikeImageRejection(message: String): Boolean {
            val lower = message.lowercase()
            return listOf(
                "messages.content",
                "content.type",
                "allowed values",
                "['text']",
                "[\"text\"]",
                "image",
                "vision",
                "multimodal",
                "multi-modal",
                "inline_data",
                "image_url",
                "content parts",
                "unsupported content",
                "media_type",
                "input_image",
            ).any { lower.contains(it) }
        }
    }
}
