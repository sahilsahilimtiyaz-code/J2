package com.jarvis.assistant.persona

import com.jarvis.assistant.settings.OwnerProfile
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingEngineTest {

    private val engine = GreetingEngine()
    private val profile = OwnerProfile(name = "Tony", title = "sir")

    @Test
    fun `never repeats the immediately previous greeting variant`() {
        var lastId: String? = null
        val morning = ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, ZoneId.of("UTC"))

        repeat(100) { index ->
            val greeting = engine.next(profile, lastId, morning, Random(index))
            assertNotEquals(lastId, greeting.id)
            lastId = greeting.id
        }
    }

    @Test
    fun `every greeting is at most two sentences`() {
        val sentenceEnd = Regex("[.!?](?:\\s|$)")
        for (hour in 0..23) {
            val now = ZonedDateTime.of(2026, 10, 30, hour, 0, 0, 0, ZoneId.of("UTC"))
            repeat(20) { index ->
                val greeting = engine.next(profile, null, now, Random(hour * 100 + index))
                val sentences = sentenceEnd.findAll(greeting.text).count()
                assertTrue(
                    "Greeting has $sentences sentences: ${greeting.text}",
                    sentences <= 2,
                )
                assertTrue(greeting.text.contains("sir"))
            }
        }
    }

    @Test
    fun `blank title falls back to sir`() {
        val greeting = engine.next(OwnerProfile(name = "Tony", title = "  "), null)
        assertTrue(greeting.text.contains("sir"))
        assertTrue(!greeting.text.contains("  "))
    }

    @Test
    fun `greeting is time aware`() {
        val morning = engine.next(
            profile,
            null,
            ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, ZoneId.of("UTC")),
        )
        val evening = engine.next(
            profile,
            null,
            ZonedDateTime.of(2026, 10, 30, 20, 0, 0, 0, ZoneId.of("UTC")),
        )
        assertTrue(morning.text.contains("morning", ignoreCase = true))
        assertTrue(evening.text.contains("evening", ignoreCase = true))
    }
}
