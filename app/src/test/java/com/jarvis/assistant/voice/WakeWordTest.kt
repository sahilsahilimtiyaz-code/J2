package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test
    fun `leading wake word is removed from the command`() {
        val result = WakeWord.parse("Jarvis, open YouTube and search lo-fi")
        assertTrue(result.addressed)
        assertFalse(result.summonsOnly)
        assertEquals("open YouTube and search lo-fi", result.command)
    }

    @Test
    fun `filler before the wake word is ignored`() {
        val result = WakeWord.parse("hey jarvis what time is it")
        assertTrue(result.addressed)
        assertEquals("what time is it", result.command)
    }

    @Test
    fun `wake word alone is a summons`() {
        val result = WakeWord.parse("Jarvis")
        assertTrue(result.addressed)
        assertTrue(result.summonsOnly)
        assertEquals("", result.command)
    }

    @Test
    fun `room chatter is ignored when the wake word is required`() {
        val result = WakeWord.parse("the carpet is dirty again")
        assertFalse(result.addressed)
    }

    @Test
    fun `wake word must be a whole word`() {
        val result = WakeWord.parse("jarvison is a place in the north")
        assertFalse(result.addressed)
    }

    @Test
    fun `wake word can be turned off`() {
        val result = WakeWord.parse(
            text = "open the camera",
            wakeWord = "jarvis",
            requireWakeWord = false,
        )
        assertTrue(result.addressed)
        assertEquals("open the camera", result.command)
    }

    @Test
    fun `custom wake word works`() {
        val result = WakeWord.parse("Computer, lights off", wakeWord = "computer")
        assertTrue(result.addressed)
        assertEquals("lights off", result.command)
    }

    @Test
    fun `multi word wake word works`() {
        val result = WakeWord.parse("okay jarvis set a timer for ten minutes")
        assertTrue(result.addressed)
        assertEquals("set a timer for ten minutes", result.command)
    }

    @Test
    fun `blank input is not addressed`() {
        val result = WakeWord.parse("   ")
        assertFalse(result.addressed)
    }
}
