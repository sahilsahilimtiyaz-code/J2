package com.jarvis.assistant.brain

import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.tools.ToolResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

class AgentLoopTest {

    @Test
    fun `executes a tool round and returns the final answer`() = runBlocking {
        val bodies = CopyOnWriteArrayList<String>()
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += readBody(exchange)
            if (requestCount.getAndIncrement() == 0) {
                sendSse(
                    exchange,
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_current_time","arguments":"{\"timezone\":\"UTC\"}"}}]}}]}""",
                )
            } else {
                sendSse(
                    exchange,
                    """data: {"choices":[{"delta":{"content":"It is ten o'clock, sir."}}]}""",
                )
            }
        }
        server.start()

        try {
            val executor = FakeToolExecutor()
            val loop = AgentLoop(
                providerRegistry = ProviderRegistry(OkHttpClient()),
                toolExecutor = executor,
            )
            val events = loop.run(
                AgentRequest(
                    systemPrompt = "You are Jarvis.",
                    history = emptyList(),
                    userMessage = "What time is it?",
                    providers = listOf(
                        ProviderConfig(
                            id = "test",
                            name = "Test provider",
                            kind = ProviderKind.OPENAI_COMPATIBLE,
                            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                            model = "test-model",
                            apiKey = "test-key",
                        ),
                    ),
                    tools = listOf(timeTool()),
                ),
            ).toList()

            assertEquals(
                "It is ten o'clock, sir.",
                events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text },
            )
            assertEquals(1, events.count { it is AgentEvent.ToolStarted })
            assertEquals(1, events.count { it is AgentEvent.ToolFinished })
            assertTrue(events.any { it is AgentEvent.Completed })
            assertFalse(events.any { it is AgentEvent.Error })

            assertEquals(1, executor.calls.size)
            assertEquals("get_current_time", executor.calls[0].first)
            assertEquals("""{"timezone":"UTC"}""", executor.calls[0].second)

            assertEquals(2, bodies.size)
            val secondPayload = Json.parseToJsonElement(bodies[1]).jsonObject
            val messages = secondPayload["messages"]!!.jsonArray
            assertTrue(
                messages.any {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
                },
            )
            assertTrue(bodies[1].contains("2026-10-30T10:00:00Z"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `short-circuits duplicate tool calls and synthesizes final reply instead of failing after tool rounds`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = readBody(exchange)
            val count = requestCount.getAndIncrement()
            if (count < 2) {
                // Model stubbornly requests the exact same search_youtube tool twice in a row
                sendSse(
                    exchange,
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_$count","type":"function","function":{"name":"search_youtube","arguments":"{\"query\":\"Numb Linkin Park\"}"}}]}}]}""",
                )
            } else {
                // On the 3rd request, verify short-circuit notice was fed back and return spoken confirmation
                assertTrue(body.contains("Already completed"))
                sendSse(
                    exchange,
                    """data: {"choices":[{"delta":{"content":"Playing Numb by Linkin Park on YouTube now, sir."}}]}""",
                )
            }
        }
        server.start()

        try {
            val executor = FakeToolExecutor()
            val loop = AgentLoop(
                providerRegistry = ProviderRegistry(OkHttpClient()),
                toolExecutor = executor,
            )
            val events = loop.run(
                AgentRequest(
                    systemPrompt = "You are Jarvis.",
                    history = emptyList(),
                    userMessage = "Open YouTube and play Numb by Linkin Park",
                    providers = listOf(
                        ProviderConfig(
                            id = "test",
                            name = "Test provider",
                            kind = ProviderKind.OPENAI_COMPATIBLE,
                            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                            model = "test-model",
                            apiKey = "test-key",
                        ),
                    ),
                    tools = listOf(timeTool()),
                ),
            ).toList()

            val reply = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("Playing Numb by Linkin Park on YouTube now, sir.", reply)
            // Underlying tool should only have been executed ONCE because the 2nd identical call was short-circuited
            assertEquals(1, executor.calls.size)
            assertFalse(events.any { it is AgentEvent.Error })
        } finally {
            server.stop(0)
        }
    }

    private inner class FakeToolExecutor : ToolExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override fun definitions(): List<ToolDefinition> = listOf(timeTool())

        override suspend fun execute(name: String, argumentsJson: String): ToolResult {
            calls += name to argumentsJson
            return ToolResult(true, "2026-10-30T10:00:00Z")
        }
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }

    private fun sendSse(exchange: HttpExchange, data: String) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output ->
            output.write((data + "\n\ndata: [DONE]\n\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }

    private fun timeTool(): ToolDefinition = ToolDefinition(
        name = "get_current_time",
        description = "Returns the current local time.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        },
    )
}
