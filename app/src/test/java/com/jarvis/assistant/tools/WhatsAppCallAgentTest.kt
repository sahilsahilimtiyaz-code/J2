package com.jarvis.assistant.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppCallAgentTest {

    @Test
    fun `isIncomingCallWindow recognises WhatsApp voip classes`() {
        assertTrue(
            WhatsAppCallAutomation.isIncomingCallWindow(
                "com.whatsapp",
                "com.whatsapp.voipcalling.IncomingCallActivity",
            ),
        )
        assertFalse(
            WhatsAppCallAutomation.isIncomingCallWindow(
                "com.whatsapp",
                "com.whatsapp.HomeActivity",
            ),
        )
        assertFalse(
            WhatsAppCallAutomation.isIncomingCallWindow(
                "com.other",
                "com.other.voipcalling.IncomingCallActivity",
            ),
        )
    }

    @Test
    fun `isFarewell detects wrap-up phrases`() {
        assertTrue(WhatsAppCallAutomation.isFarewell("Okay thank you, bye"))
        assertTrue(WhatsAppCallAutomation.isFarewell("Tell him I called, that's all"))
        assertTrue(WhatsAppCallAutomation.isFarewell("I will call him later"))
        assertFalse(WhatsAppCallAutomation.isFarewell("I need to ask about dinner tonight"))
    }

    @Test
    fun `sanitizeCallerText caps the length`() {
        val longInput = "a".repeat(1_000)
        val out = WhatsAppCallAutomation.sanitizeCallerText(longInput)
        assertTrue(out.length <= 600)
    }

    @Test
    fun `receptionist prompt mentions unavailability and identity`() {
        val prompt = WhatsAppCallAutomation.receptionistSystemPrompt(
            ownerAddress = "sir",
            contextNote = "Owner left a note.",
        )
        assertTrue(prompt.contains("not available"))
        assertTrue(prompt.contains("sir"))
        assertTrue(prompt.contains("Jarvis"))
        assertTrue(prompt.contains("untrusted", ignoreCase = true))
    }

    @Test
    fun `buildCallerTurnMessage includes caller words and history`() {
        val msg = WhatsAppCallAutomation.buildCallerTurnMessage(
            history = listOf("Hello there"),
            latestUtterance = "I wanted to ask about Friday",
        )
        assertTrue(msg.contains("I wanted to ask about Friday"))
        assertTrue(msg.contains("Hello there"))
    }
}