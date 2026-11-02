package com.jarvis.assistant.persona

import com.jarvis.assistant.settings.OwnerProfile
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

data class Greeting(
    val id: String,
    val text: String,
)

/**
 * Local, offline greeting generator. It never blocks on a provider and never
 * repeats the immediately previous greeting variant.
 */
class GreetingEngine {

    fun next(
        profile: OwnerProfile,
        lastGreetingId: String?,
        now: ZonedDateTime = ZonedDateTime.now(),
        random: Random = Random.Default,
    ): Greeting {
        val title = profile.title.trim().ifBlank { "sir" }
        val time = now.format(TIME_FORMAT)
        val templates = templatesFor(now.hour)
        val fresh = templates.filterNot { it.id == lastGreetingId }
        val pool = fresh.ifEmpty { templates }
        val template = pool[random.nextInt(pool.size)]
        return Greeting(template.id, template.format(title, time))
    }

    private fun templatesFor(hour: Int): List<GreetingTemplate> = when (hour) {
        in 5..11 -> MORNING
        in 12..16 -> AFTERNOON
        in 17..21 -> EVENING
        else -> NIGHT
    }

    private data class GreetingTemplate(
        val id: String,
        val format: (title: String, time: String) -> String,
    )

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

        val MORNING = listOf(
            GreetingTemplate("morning-1") { title, time ->
                "Good morning, $title. It is $time, and the day is yours."
            },
            GreetingTemplate("morning-2") { title, time ->
                "Good morning, $title. All systems are awake; the time is $time."
            },
            GreetingTemplate("morning-3") { title, time ->
                "Morning, $title. The time is $time; what shall we begin with?"
            },
            GreetingTemplate("morning-4") { title, time ->
                "Good morning, $title. It is $time; I am ready when you are."
            },
        )

        val AFTERNOON = listOf(
            GreetingTemplate("afternoon-1") { title, time ->
                "Good afternoon, $title. It is $time; the afternoon is at your disposal."
            },
            GreetingTemplate("afternoon-2") { title, time ->
                "Good afternoon, $title. Systems are online; it is $time."
            },
            GreetingTemplate("afternoon-3") { title, time ->
                "Afternoon, $title. It is $time; shall we continue?"
            },
            GreetingTemplate("afternoon-4") { title, time ->
                "Good afternoon, $title. It is $time; what would you like to do?"
            },
        )

        val EVENING = listOf(
            GreetingTemplate("evening-1") { title, time ->
                "Good evening, $title. It is $time; I have kept things quiet."
            },
            GreetingTemplate("evening-2") { title, time ->
                "Good evening, $title. All systems are online; it is $time."
            },
            GreetingTemplate("evening-3") { title, time ->
                "Evening, $title. It is $time; what would you like to do?"
            },
            GreetingTemplate("evening-4") { title, time ->
                "Good evening, $title. It is $time; shall I bring you up to speed?"
            },
        )

        val NIGHT = listOf(
            GreetingTemplate("night-1") { title, time ->
                "Good evening, $title. It is $time; I have kept the lights low and the systems quiet."
            },
            GreetingTemplate("night-2") { title, time ->
                "You are up late, $title. It is $time; what do you need?"
            },
            GreetingTemplate("night-3") { title, time ->
                "Good evening, $title. It is $time; shall I keep things brief?"
            },
            GreetingTemplate("night-4") { title, time ->
                "The hour is late, $title. It is $time; I am still here."
            },
        )
    }
}
