package com.jarvis.assistant.brain

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineNaturalEngineTest {

    private val extendedTools = listOf(
        ToolDefinition("set_operating_mode", "Switch mode", buildJsonObject {}),
        ToolDefinition("schedule_task", "Schedule a task", buildJsonObject {}),
        ToolDefinition("list_scheduled_tasks", "List scheduled tasks", buildJsonObject {}),
        ToolDefinition("complete_scheduled_task", "Complete task", buildJsonObject {}),
        ToolDefinition("deep_research", "Deep research", buildJsonObject {}),
        ToolDefinition("fact_check", "Fact check", buildJsonObject {}),
        ToolDefinition("fetch_web_page", "Fetch page", buildJsonObject {}),
        ToolDefinition("web_search", "Search web", buildJsonObject {}),
        ToolDefinition("swipe_screen", "Swipe screen", buildJsonObject {}),
    )

    private fun assertNaturalProseOnly(reply: String) {
        assertTrue("Reply should not be blank", reply.isNotBlank())
        assertFalse("Reply leaked JSON envelope: $reply", reply.contains("{\"ok\""))
        assertFalse("Reply leaked <think> tags: $reply", reply.contains("<think>"))
        assertFalse("Reply leaked control token: $reply", reply.contains("<|im_start|>"))
        assertFalse("Reply leaked control token: $reply", reply.contains("<|im_end|>"))
        assertFalse("Reply leaked <tool_call>: $reply", reply.contains("<tool_call>"))
        assertFalse("Reply leaked Markdown heading: $reply", reply.contains("## "))
        assertFalse("Reply leaked code fence: $reply", reply.contains("```"))
        assertFalse("Reply leaked internal agent directive: $reply", reply.contains("[Action complete on phone"))
    }

    @Test
    fun intent_gate_does_not_hijack_general_knowledge_or_conversation_questions() {
        val generalQuestions = listOf(
            "How does gravity work?",
            "How is electricity generated?",
            "How does a timer work?",
            "How is a battery charged?",
            "Are you okay?",
            "Do you know Albert Einstein?",
            "How do you cook rice?",
        )
        for (q in generalQuestions) {
            val analysis = IntentGate.classify(q)
            assertFalse(
                "General question '$q' must NOT be misclassified as a capability/informational gate (${analysis.kind})",
                analysis.isNonExecutionInquiry,
            )
        }

        // Meanwhile genuine Jarvis phone-automation capability questions STILL trigger the gate
        val capabilityQuestions = listOf(
            "Can you send WhatsApp voice messages?",
            "How do you answer a WhatsApp call?",
            "What can you do?",
            "Are you able to control my phone screen?",
        )
        for (q in capabilityQuestions) {
            val analysis = IntentGate.classify(q)
            assertTrue(
                "Capability question '$q' should still be recognized as non-execution inquiry",
                analysis.isNonExecutionInquiry,
            )
        }
    }

    @Test
    fun sanitizer_strips_think_tags_control_tokens_tool_calls_markdown_and_json_envelopes() {
        val rawModelLeak = """
            <think>Internal reasoning about gravity</think>
            <|im_start|>assistant
            <tool_call>{"name":"web_search","arguments":{"query":"gravity"}}</tool_call>
            ## Gravity Explained
            **Gravity** is the force of attraction between objects with mass. See [Wikipedia](https://en.wikipedia.org/wiki/Gravity).
            <|im_end|>
        """.trimIndent()

        val cleaned = NaturalResponseSanitizer.sanitizeModelOutput(rawModelLeak, allowCode = false)
        assertNaturalProseOnly(cleaned)
        assertTrue(cleaned.contains("Gravity is the force of attraction"))
        assertFalse(cleaned.contains("Internal reasoning"))
        assertFalse(cleaned.contains("https://"))

        val rawToolPayload = """
            {"ok":true,"result":"## Deep Research Synthesis: Quantum Computing\n\n### Findings\n- **Qubits**: Quantum computers use superposition and entanglement.\n  *Source*: [https://example.com](https://example.com)\n[Action complete on phone. Reply to the owner now in 1-2 sentences without calling more tools unless another distinct step was requested.]"}
        """.trimIndent()
        val spokenTool = NaturalResponseSanitizer.sanitizeToolOutcome(rawToolPayload, "deep_research", "sir")
        assertNaturalProseOnly(spokenTool)
        assertTrue(spokenTool.contains("superposition"))
        assertFalse(spokenTool.contains("https://"))
    }

    @Test
    fun offline_math_and_unit_engine_computes_expressions_and_conversions_naturally() {
        val expr = OfflineMathAndUnitEngine.tryEvaluate("what is (12 + 8) * 3?", "sir")
        assertNotNull(expr)
        assertTrue("Expected 60 in '$expr'", expr!!.contains("60"))
        assertNaturalProseOnly(expr)

        val pct = OfflineMathAndUnitEngine.tryEvaluate("what is 25% of 400", "sir")
        assertNotNull(pct)
        assertTrue("Expected 100 in '$pct'", pct!!.contains("100"))

        val root = OfflineMathAndUnitEngine.tryEvaluate("square root of 144", "sir")
        assertNotNull(root)
        assertTrue("Expected 12 in '$root'", root!!.contains("12"))

        val temp = OfflineMathAndUnitEngine.tryEvaluate("convert 30 c to f", "sir")
        assertNotNull(temp)
        assertTrue("Expected 86 in '$temp'", temp!!.contains("86"))

        val dist = OfflineMathAndUnitEngine.tryEvaluate("100 km to miles", "sir")
        assertNotNull(dist)
        assertTrue("Expected 62.14 in '$dist'", dist!!.contains("62.14"))
    }

    @Test
    fun offline_natural_engine_answers_diverse_questions_naturally_without_codes() {
        val provider = LocalLlmProvider(client = OkHttpClient(), downloadManager = null)
        val config = ProviderConfig(
            id = "local",
            name = "Offline",
            kind = ProviderKind.LOCAL_ON_DEVICE,
            baseUrl = "local://on-device",
            apiKey = "",
            model = "qwen3-1.7b",
        )

        fun ask(prompt: String, system: String = "Preferred form of address: sir.\nOwner name: Alex."): String =
            runBlocking {
                val events = provider.stream(
                    ChatRequest(
                        messages = listOf(
                            LlmMessage.system(system),
                            LlmMessage.user(prompt),
                        ),
                        tools = emptyList(),
                    ),
                    config,
                ).toList()
                events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }.trim()
            }

        val greeting = ask("Hello Jarvis")
        assertNaturalProseOnly(greeting)
        assertTrue(greeting.contains("sir", ignoreCase = true))

        val wellbeing = ask("How are you?")
        assertNaturalProseOnly(wellbeing)
        assertTrue(wellbeing.contains("nominal", ignoreCase = true) || wellbeing.contains("smoothly", ignoreCase = true))

        val creator = ask("Who created you?")
        assertNaturalProseOnly(creator)
        assertTrue(creator.contains("Octavian"))

        val gravity = ask("How does gravity work?")
        assertNaturalProseOnly(gravity)
        assertTrue("Expected real gravity explanation, got: $gravity", gravity.contains("spacetime", ignoreCase = true) || gravity.contains("mass", ignoreCase = true))

        val apiConcept = ask("What is an API?")
        assertNaturalProseOnly(apiConcept)
        assertTrue("Expected plain-English API explanation, got: $apiConcept", apiConcept.contains("Application Programming Interface"))

        val studyReply = ask(
            "Explain photosynthesis",
            system = "Preferred form of address: sir.\nACTIVE OPERATIONAL MODE: STUDY",
        )
        assertNaturalProseOnly(studyReply)
        assertTrue(studyReply.contains("sunlight", ignoreCase = true))
        assertTrue("Study mode should include an active recall prompt", studyReply.contains("?"))

        val obscureTopic = ask("Explain the Treaty of Westphalia in 1648")
        assertNaturalProseOnly(obscureTopic)
        assertTrue(obscureTopic.contains("sir", ignoreCase = true))
    }

    @Test
    fun offline_provider_falls_back_gracefully_when_web_search_fails_offline() {
        val provider = LocalLlmProvider(client = OkHttpClient(), downloadManager = null)
        val config = ProviderConfig(
            id = "local",
            name = "Offline",
            kind = ProviderKind.LOCAL_ON_DEVICE,
            baseUrl = "local://on-device",
            apiKey = "",
            model = "qwen3-1.7b",
        )
        val searchCall = ToolCall(id = "c1", name = "web_search", argumentsJson = """{"query":"gravity"}""")
        val events = runBlocking {
            provider.stream(
                ChatRequest(
                    messages = listOf(
                        LlmMessage.system("Preferred form of address: sir."),
                        LlmMessage.user("How does gravity work?"),
                        LlmMessage.assistant("", listOf(searchCall)),
                        LlmMessage.tool(searchCall, """{"ok":false,"error":"Unable to reach network: UnknownHostException"}"""),
                    ),
                    tools = extendedTools,
                ),
                config,
            ).toList()
        }
        val reply = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }.trim()
        assertNaturalProseOnly(reply)
        assertTrue("Should fall back to offline knowledge on network error, got: $reply", reply.contains("mass", ignoreCase = true))
        assertFalse("Should NOT expose raw UnknownHostException to user", reply.contains("UnknownHostException"))
    }

    @Test
    fun offline_task_router_routes_modes_tasks_advanced_search_and_protects_personal_queries() {
        val modeCall = OfflineTaskRouter.routeUserCommand("Switch to study mode", extendedTools).firstOrNull()
        assertNotNull(modeCall)
        assertEquals("set_operating_mode", modeCall!!.name)
        assertTrue(modeCall.argumentsJson.contains("\"study\""))

        val scheduleCall = OfflineTaskRouter.routeUserCommand("Schedule a daily task in 30 minutes to review notes", extendedTools).firstOrNull()
        assertNotNull(scheduleCall)
        assertEquals("schedule_task", scheduleCall!!.name)
        assertTrue(scheduleCall.argumentsJson.contains("\"daily\""))

        val deepCall = OfflineTaskRouter.routeUserCommand("Deep research solid state batteries", extendedTools).firstOrNull()
        assertNotNull(deepCall)
        assertEquals("deep_research", deepCall!!.name)
        assertTrue(deepCall.argumentsJson.contains("\"query\""))

        val factCall = OfflineTaskRouter.routeUserCommand("Fact check bananas are berries", extendedTools).firstOrNull()
        assertNotNull(factCall)
        assertEquals("fact_check", factCall!!.name)

        val swipeCall = OfflineTaskRouter.routeUserCommand("swipe up", extendedTools).firstOrNull()
        assertNotNull(swipeCall)
        assertEquals("swipe_screen", swipeCall!!.name)

        // Must NOT route personal/identity or math queries to web_search!
        assertTrue(OfflineTaskRouter.routeUserCommand("What is your name?", extendedTools).isEmpty())
        assertTrue(OfflineTaskRouter.routeUserCommand("What is 25% of 400?", extendedTools).isEmpty())
        assertTrue(OfflineTaskRouter.routeUserCommand("Who created you?", extendedTools).isEmpty())
    }

    @Test
    fun sanitizer_rejects_syntax_errors_single_line_code_jinja_templates_and_repetition_loops() {
        val syntaxSamples = listOf(
            "SyntaxError: invalid syntax",
            "  File \"<stdin>\", line 1\nSyntaxError: unexpected EOF while parsing",
            "bash: syntax error near unexpected token `newline'",
            "Traceback (most recent call last):\n  File \"main.py\", line 2, in <module>\nNameError: name 'x' is not defined",
            "print('hello world')",
            "def greet(name): return f'hi {name}'",
            "```python\ndef unclosed():\n    return 42",
            "<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"test\"}}",
            "hello hello hello hello hello hello hello world",
        )
        for (sample in syntaxSamples) {
            val cleaned = NaturalResponseSanitizer.sanitizeModelOutput(sample, allowCode = false)
            assertEquals("Expected broken/code output '$sample' to be rejected", "", cleaned)
        }

        val jinjaAndGemmaLeak = "{{ bos_token }}{% if system %}{% endif %}<start_of_turn>model\nLight travels at 299,792 km/s.<end_of_turn>"
        val cleanedJinja = NaturalResponseSanitizer.sanitizeModelOutput(jinjaAndGemmaLeak, allowCode = false)
        assertEquals("Light travels at 299,792 km/s.", cleanedJinja)

        val promptEcho = "Recent conversation context:\nUser: hi\nCurrent question:\n/no_think\nAll systems are nominal."
        val cleanedEcho = NaturalResponseSanitizer.sanitizeModelOutput(promptEcho, allowCode = false)
        assertTrue(cleanedEcho.contains("All systems are nominal."))
        assertFalse(cleanedEcho.contains("/no_think"))
        assertFalse(cleanedEcho.contains("Current question:"))

        assertTrue(NaturalResponseSanitizer.isUnhelpfulRefusal("I don't know."))
        assertTrue(NaturalResponseSanitizer.isUnhelpfulRefusal("I'm sorry, I don't know about that."))
        assertTrue(NaturalResponseSanitizer.isUnhelpfulRefusal("As an AI language model, I cannot answer that."))
        assertFalse(NaturalResponseSanitizer.isUnhelpfulRefusal("Light travels at roughly 300,000 kilometers per second."))
    }

    @Test
    fun expanded_offline_knowledge_and_helpful_utilities_answer_naturally() {
        val provider = LocalLlmProvider(client = OkHttpClient(), downloadManager = null)
        val config = ProviderConfig(
            id = "local",
            name = "Offline",
            kind = ProviderKind.LOCAL_ON_DEVICE,
            baseUrl = "local://on-device",
            apiKey = "",
            model = "qwen3-1.7b",
        )

        fun ask(prompt: String): String = runBlocking {
            val events = provider.stream(
                ChatRequest(
                    messages = listOf(
                        LlmMessage.system("Preferred form of address: sir.\nOwner name: Alex."),
                        LlmMessage.user(prompt),
                    ),
                    tools = emptyList(),
                ),
                config,
            ).toList()
            events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }.trim()
        }

        val sun = ask("How does the sun produce energy?")
        assertNaturalProseOnly(sun)
        assertTrue("Expected nuclear fusion explanation, got: $sun", sun.contains("fusion", ignoreCase = true))

        val seasons = ask("Why do we have seasons?")
        assertNaturalProseOnly(seasons)
        assertTrue("Expected axial tilt explanation, got: $seasons", seasons.contains("23.5"))

        val ph = ask("What is the pH scale?")
        assertNaturalProseOnly(ph)
        assertTrue("Expected pH explanation, got: $ph", ph.contains("0 to 14"))

        val pyth = ask("What is the Pythagorean theorem?")
        assertNaturalProseOnly(pyth)
        assertTrue("Expected hypotenuse explanation, got: $pyth", pyth.contains("hypotenuse", ignoreCase = true))

        val letters = ask("How many letters in Jarvis?")
        assertNaturalProseOnly(letters)
        assertTrue("Expected 6 letters, got: $letters", letters.contains("6 letters"))

        val count = ask("Count to 5")
        assertNaturalProseOnly(count)
        assertTrue("Expected 1, 2, 3, 4, 5, got: $count", count.contains("1, 2, 3, 4, 5"))

        val fact = ask("Tell me a fun fact")
        assertNaturalProseOnly(fact)
    }
}
