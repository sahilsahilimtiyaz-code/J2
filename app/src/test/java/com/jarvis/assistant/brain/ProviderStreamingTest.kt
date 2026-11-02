package com.jarvis.assistant.brain

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exercise the real OkHttp request/response path against a local
 * HTTP server. They are deliberately provider-shaped rather than mocking the
 * parser, so SSE framing, headers, payloads and tool-call assembly are tested.
 */
class ProviderStreamingTest {

    @Test
    fun `openai compatible streams text chunks`() = runBlocking {
        val captured = AtomicReference<String>()
        withServer({ exchange ->
            captured.set(readBody(exchange))
            sendSse(
                exchange,
                listOf(
                    """data: {"choices":[{"delta":{"content":"Good morning"}}]}""",
                    """data: {"choices":[{"delta":{"content":", sir."}}]}""",
                    "data: [DONE]",
                ),
            )
        }) { baseUrl ->
            val provider = OpenAiCompatibleProvider(OkHttpClient())
            val events = provider.stream(
                ChatRequest(
                    messages = listOf(
                        LlmMessage.system("You are Jarvis."),
                        LlmMessage.user("Hello"),
                    ),
                ),
                config(baseUrl, ProviderKind.OPENAI_COMPATIBLE, "test-model"),
            ).toList()

            assertEquals(
                "Good morning, sir.",
                events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text },
            )
            assertTrue(events.any { it is LlmEvent.Done })
            assertFalse(events.any { it is LlmEvent.Error })

            val payload = Json.parseToJsonElement(captured.get()).jsonObject
            assertEquals("test-model", payload["model"]!!.jsonPrimitive.content)
            assertEquals("true", payload["stream"]!!.jsonPrimitive.content)
            assertEquals(
                "You are Jarvis.",
                payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `openai compatible assembles fragmented tool call`() = runBlocking {
        withServer({ exchange ->
            sendSse(
                exchange,
                listOf(
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_current_time","arguments":""}}]}}]}""",
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"timezone\":"}}]}}]}""",
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"UTC\"}"}}]}}]}""",
                    "data: [DONE]",
                ),
            )
        }) { baseUrl ->
            val provider = OpenAiCompatibleProvider(OkHttpClient())
            val events = provider.stream(
                ChatRequest(
                    messages = listOf(LlmMessage.user("What time is it?")),
                    tools = listOf(timeTool()),
                ),
                config(baseUrl, ProviderKind.OPENAI_COMPATIBLE, "test-model"),
            ).toList()

            val call = events.filterIsInstance<LlmEvent.ToolCallReady>().single().call
            assertEquals("call_1", call.id)
            assertEquals("get_current_time", call.name)
            assertEquals("""{"timezone":"UTC"}""", call.argumentsJson)
            assertFalse(events.any { it is LlmEvent.Error })
            assertTrue(events.any { it is LlmEvent.Done })
        }
    }

    @Test
    fun `gemini streams text and converts tool schema`() = runBlocking {
        val captured = AtomicReference<String>()
        withServer({ exchange ->
            captured.set(readBody(exchange))
            sendSse(
                exchange,
                listOf(
                    """data: {"candidates":[{"content":{"parts":[{"text":"Good evening, sir."}]},"finishReason":"STOP"}]}""",
                    """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_current_time","args":{}}}]},"finishReason":"STOP"}]}""",
                ),
            )
        }) { baseUrl ->
            val provider = GeminiProvider(OkHttpClient())
            val events = provider.stream(
                ChatRequest(
                    messages = listOf(LlmMessage.user("What time is it?")),
                    tools = listOf(timeTool()),
                ),
                config(baseUrl, ProviderKind.GEMINI, "gemini-test"),
            ).toList()

            assertEquals(
                "Good evening, sir.",
                events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text },
            )
            val call = events.filterIsInstance<LlmEvent.ToolCallReady>().single().call
            assertEquals("get_current_time", call.name)
            assertEquals("{}", call.argumentsJson)

            val payload = Json.parseToJsonElement(captured.get()).jsonObject
            val declaration = payload["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
            val parameters = declaration["parameters"]!!.jsonObject
            assertEquals("OBJECT", parameters["type"]!!.jsonPrimitive.content)
            assertFalse(parameters["additionalProperties"] != null)
        }
    }

    @Test
    fun `anthropic streams text and tool use`() = runBlocking {
        withServer({ exchange ->
            sendSse(
                exchange,
                listOf(
                    """event: message_start
data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
                    """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"At your service"}}""",
                    """event: content_block_stop
data: {"type":"content_block_stop","index":0}""",
                    """event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_current_time","input":{}}}""",
                    """event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
                    """event: content_block_stop
data: {"type":"content_block_stop","index":1}""",
                    """event: message_stop
data: {"type":"message_stop"}""",
                ),
            )
        }) { baseUrl ->
            val provider = AnthropicProvider(OkHttpClient())
            val events = provider.stream(
                ChatRequest(
                    messages = listOf(LlmMessage.user("Hello")),
                    tools = listOf(timeTool()),
                ),
                config(baseUrl, ProviderKind.ANTHROPIC, "claude-test"),
            ).toList()

            assertEquals(
                "At your service",
                events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text },
            )
            val call = events.filterIsInstance<LlmEvent.ToolCallReady>().single().call
            assertEquals("toolu_1", call.id)
            assertEquals("get_current_time", call.name)
            assertEquals("{}", call.argumentsJson)
            assertTrue(events.any { it is LlmEvent.Done })
        }
    }

    @Test
    fun `provider error body is surfaced without crashing`() = runBlocking {
        withServer({ exchange ->
            sendJson(
                exchange,
                status = 401,
                body = """{"error":{"message":"Invalid API key"}}""",
            )
        }) { baseUrl ->
            val provider = OpenAiCompatibleProvider(OkHttpClient())
            val events = provider.stream(
                ChatRequest(messages = listOf(LlmMessage.user("Hello"))),
                config(baseUrl, ProviderKind.OPENAI_COMPATIBLE, "test-model"),
            ).toList()

            val error = events.filterIsInstance<LlmEvent.Error>().single()
            assertTrue(error.message.contains("Invalid API key"))
            assertTrue(error.message.contains("API key was rejected"))
        }
    }

    private fun config(baseUrl: String, kind: ProviderKind, model: String): ProviderConfig =
        ProviderConfig(
            id = "test",
            name = "Test provider",
            kind = kind,
            baseUrl = baseUrl,
            model = model,
            apiKey = "test-key",
        )

    private fun timeTool(): ToolDefinition = ToolDefinition(
        name = "get_current_time",
        description = "Returns the current local time.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("additionalProperties", false)
        },
    )

    private suspend fun withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }

    private fun sendSse(exchange: HttpExchange, chunks: List<String>) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output ->
            chunks.forEach { chunk ->
                output.write((chunk + "\n\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        }
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
