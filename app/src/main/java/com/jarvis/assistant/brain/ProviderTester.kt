package com.jarvis.assistant.brain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

data class ProviderTestResult(
    val success: Boolean,
    val message: String,
)

class ProviderTester(private val registry: ProviderRegistry) {

    suspend fun test(config: ProviderConfig): ProviderTestResult {
        var sawText = false
        return try {
            withTimeout(30_000L) {
                registry.stream(
                    ChatRequest(
                        messages = listOf(
                            LlmMessage.user("Reply with exactly: OK"),
                        ),
                        tools = emptyList(),
                        temperature = 0.0,
                        maxTokens = 32,
                    ),
                    config,
                ).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            if (event.text.isNotBlank()) sawText = true
                        }

                        is LlmEvent.ToolCallReady -> Unit
                        is LlmEvent.Error -> throw ProviderException(event.message)
                        LlmEvent.Done -> Unit
                    }
                }
            }
            if (sawText) {
                ProviderTestResult(true, "Connected to ${config.name} using ${config.model}.")
            } else {
                ProviderTestResult(
                    true,
                    "Connected to ${config.name}, but the model returned no text. Check the model name.",
                )
            }
        } catch (e: TimeoutCancellationException) {
            ProviderTestResult(false, "The test timed out after 30 seconds.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderTestResult(false, e.message?.take(400) ?: "The connection test failed.")
        }
    }
}
