package com.jarvis.assistant.brain

import com.jarvis.assistant.tools.WhatsAppVoiceNotes
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIntelligenceAndSafetyGateTest {

    private val testTools = listOf(
        ToolDefinition(
            name = "send_whatsapp_voice_note",
            description = "Send a WhatsApp voice note",
            parameters = buildJsonObject {},
        ),
        ToolDefinition(
            name = "send_whatsapp_message",
            description = "Send a WhatsApp text message",
            parameters = buildJsonObject {},
        ),
        ToolDefinition(
            name = "answer_whatsapp_call",
            description = "Answer a WhatsApp call",
            parameters = buildJsonObject {},
        ),
    )

    @Test
    fun `capability questions never trigger tool calls or mission plans`() {
        val queries = listOf(
            "Are you able to send WhatsApp voice messages?",
            "Can you pick up calls and stay on the call?",
            "Are you able to send voice messages and pick up the call and stay on call?",
            "Do you support sending WhatsApp audio notes?",
            "Is it possible for you to answer my WhatsApp calls?",
        )

        for (query in queries) {
            val analysis = IntentGate.classify(query, ownerTitle = "sir")
            assertEquals(
                "Expected CAPABILITY_QUESTION for: $query",
                UserIntentKind.CAPABILITY_QUESTION,
                analysis.kind,
            )
            assertFalse("Capability question must not be executable: $query", analysis.isExecutableCommand)
            assertTrue("Capability question must be marked non-execution inquiry: $query", analysis.isNonExecutionInquiry)
            assertNotNull("Capability question must provide direct explanation: $query", analysis.capabilityExplanation)

            val routedTools = OfflineTaskRouter.routeUserCommand(query, testTools)
            assertTrue(
                "OfflineTaskRouter must return ZERO tools for capability question '$query', got: $routedTools",
                routedTools.isEmpty(),
            )

            assertFalse(
                "TaskPlanner must never create a mission plan for capability question '$query'",
                TaskPlanner.shouldCreatePlan(query),
            )

            val gate = IntentGate.validateToolCall(
                userMessage = query,
                toolName = "send_whatsapp_voice_note",
                argumentsJson = """{"recipient":"Shan Didi","message":"hello"}""",
            )
            assertTrue(
                "Safety gate must block tool call on capability question '$query'",
                gate is SafetyGateDecision.BlockedNonCommand,
            )
        }
    }

    @Test
    fun `informational and hypothetical questions never trigger execution`() {
        val queries = listOf(
            "How would you send a voice message?" to UserIntentKind.INFORMATIONAL_QUESTION,
            "How can you answer a WhatsApp call?" to UserIntentKind.INFORMATIONAL_QUESTION,
            "What happens if someone calls me on WhatsApp?" to UserIntentKind.HYPOTHETICAL_SCENARIO,
            "Suppose I asked you to send a voice note, how does it work?" to UserIntentKind.HYPOTHETICAL_SCENARIO,
        )

        for ((query, expectedKind) in queries) {
            val analysis = IntentGate.classify(query, ownerTitle = "sir")
            assertEquals("Unexpected kind for '$query'", expectedKind, analysis.kind)
            assertFalse(analysis.isExecutableCommand)
            assertTrue(OfflineTaskRouter.routeUserCommand(query, testTools).isEmpty())
            assertFalse(TaskPlanner.shouldCreatePlan(query))
        }
    }

    @Test
    fun `corrections and cancellations are classified and block tool execution`() {
        val cancellations = listOf(
            "Stop. I didn't ask you to send anything.",
            "Cancel that immediately",
            "No, don't send anything",
            "Wait stop recording",
            "Abort mission",
        )

        for (utterance in cancellations) {
            val analysis = IntentGate.classify(utterance, ownerTitle = "sir")
            assertEquals(
                "Expected CANCELLATION_OR_CORRECTION for '$utterance'",
                UserIntentKind.CANCELLATION_OR_CORRECTION,
                analysis.kind,
            )
            assertFalse(analysis.isExecutableCommand)
            assertTrue(OfflineTaskRouter.routeUserCommand(utterance, testTools).isEmpty())
            assertFalse(TaskPlanner.shouldCreatePlan(utterance))
        }
    }

    @Test
    fun `incomplete WhatsApp commands request clarification instead of guessing recipient or message`() {
        val missingBoth = IntentGate.classify("Send a WhatsApp voice message.", ownerTitle = "sir")
        assertEquals(UserIntentKind.CLARIFICATION_NEEDED, missingBoth.kind)
        assertFalse(missingBoth.isExecutableCommand)
        assertTrue(OfflineTaskRouter.routeUserCommand("Send a WhatsApp voice message.", testTools).isEmpty())

        val missingMessage = IntentGate.classify("Send a WhatsApp voice message to Rahul.", ownerTitle = "sir")
        assertEquals(UserIntentKind.CLARIFICATION_NEEDED, missingMessage.kind)
        assertFalse(missingMessage.isExecutableCommand)
        assertTrue(OfflineTaskRouter.routeUserCommand("Send a WhatsApp voice message to Rahul.", testTools).isEmpty())
    }

    @Test
    fun `explicit WhatsApp voice message to myself resolves recipient and passes safety gate`() {
        val command = "Send a voice message to myself on WhatsApp saying good morning."
        val analysis = IntentGate.classify(command, ownerTitle = "sir")
        assertEquals(UserIntentKind.EXECUTABLE_COMMAND, analysis.kind)
        assertTrue(analysis.isExecutableCommand)
        assertFalse(analysis.isNonExecutionInquiry)

        val calls = OfflineTaskRouter.routeUserCommand(command, testTools)
        assertEquals(1, calls.size)
        assertEquals("send_whatsapp_voice_note", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("\"recipient\":\"myself\""))
        assertTrue(calls[0].argumentsJson.contains("good morning"))

        val gate = IntentGate.validateToolCall(
            userMessage = command,
            toolName = calls[0].name,
            argumentsJson = calls[0].argumentsJson,
        )
        assertEquals(SafetyGateDecision.Allowed, gate)

        assertTrue(IntentGate.isSelfRecipient("myself"))
        assertTrue(WhatsAppVoiceNotes.matchesContactLabel("Message yourself", "myself"))
        assertTrue(WhatsAppVoiceNotes.matchesContactLabel("Swapnil (You)", "myself"))
    }

    @Test
    fun `safety gate blocks hallucinated unrelated contact when user specified myself`() {
        val command = "Send a voice message to myself on WhatsApp saying good morning."
        val gate = IntentGate.validateToolCall(
            userMessage = command,
            toolName = "send_whatsapp_voice_note",
            argumentsJson = """{"recipient":"Shan Didi","message":"good morning"}""",
        )
        assertTrue(
            "Safety gate must reject hallucinated contact 'Shan Didi' when user asked for 'myself'",
            gate is SafetyGateDecision.NeedsClarification,
        )
    }

    @Test
    fun `emoji contact names and spoken emojis work for WhatsApp messages`() {
        val commandWithEmoji = "Send a WhatsApp message to Shan Didi ❤️ saying hello ❤️"
        val calls = OfflineTaskRouter.routeUserCommand(commandWithEmoji, testTools)
        assertEquals(1, calls.size)
        assertEquals("send_whatsapp_message", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("Shan Didi ❤️"))
        assertTrue(calls[0].argumentsJson.contains("hello ❤️"))

        val commandWithSpokenEmoji = "Send a WhatsApp message to Shan Didi saying good morning heart emoji"
        val spokenCalls = OfflineTaskRouter.routeUserCommand(commandWithSpokenEmoji, testTools)
        assertEquals(1, spokenCalls.size)
        assertTrue(spokenCalls[0].argumentsJson.contains("good morning ❤️"))

        assertTrue(WhatsAppVoiceNotes.matchesContactLabel("Shan Didi ❤️", "Shan Didi"))
        assertTrue(WhatsAppVoiceNotes.matchesContactLabel("Shan Didi", "Shan Didi ❤️"))
        assertTrue(WhatsAppVoiceNotes.matchesContactLabel("❤️", "❤️"))
    }
}
