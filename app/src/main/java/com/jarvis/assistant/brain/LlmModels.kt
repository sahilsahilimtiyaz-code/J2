package com.jarvis.assistant.brain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ProviderKind(val label: String, val defaultBaseUrl: String) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://openrouter.ai/api/v1"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1"),
    LOCAL_ON_DEVICE("Offline Local AI", "http://127.0.0.1:8080/v1"),
}

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String = "{}",
)

data class LlmMessage(
    val role: LlmRole,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
) {
    companion object {
        fun system(text: String) = LlmMessage(LlmRole.SYSTEM, text)
        fun user(text: String) = LlmMessage(LlmRole.USER, text)
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) =
            LlmMessage(LlmRole.ASSISTANT, text, toolCalls)

        fun tool(call: ToolCall, result: String) =
            LlmMessage(
                role = LlmRole.TOOL,
                content = result,
                toolCallId = call.id,
                toolName = call.name,
            )
    }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

data class ChatRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    /**
     * Raw JPEG screenshots (for example from `see_screen`) attached to this
     * request. Providers deliver them as a final user turn so screen-vision
     * capable models can look at the real pixels before deciding what to tap.
     */
    val images: List<AttachedImage>? = null,
)

/** One attached JPEG image plus a one-line caption describing why it is attached. */
data class AttachedImage(
    val bytes: ByteArray,
    val caption: String = "",
) {
    override fun equals(other: Any?): Boolean =
        other is AttachedImage && bytes.contentEquals(other.bytes) && caption == other.caption

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + caption.hashCode()
}

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ToolCallReady(val call: ToolCall) : LlmEvent
    data object Done : LlmEvent
    data class Error(val message: String) : LlmEvent
}

interface LlmProvider {
    fun stream(request: ChatRequest, config: ProviderConfig): Flow<LlmEvent>
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
