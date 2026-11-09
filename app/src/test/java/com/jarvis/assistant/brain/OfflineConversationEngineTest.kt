package com.jarvis.assistant.brain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineConversationEngineTest {

    private fun at(hour: Int): Date =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-11-09 %02d:15".format(hour))!!

    private fun context(
        hour: Int = 9,
        modelReady: Boolean = false,
        memory: List<String> = emptyList(),
        network: Boolean = true,
        lastReply: String? = null,
        previousQuestion: String? = null,
        ownerName: String = "",
    ) = OfflineReplyContext(
        ownerTitle = "sir",
        ownerName = ownerName,
        modelName = "Qwen2.5 0.5B Instruct",
        modelSizeLabel = "400 MB",
        modelReady = modelReady,
        memoryLines = memory,
        lastAssistantReply = lastReply,
        previousUserQuestion = previousQuestion,
        networkAvailable = network,
        now = at(hour),
    )

    @Test
    fun greeting_is_time_aware_and_addresses_the_owner() {
        val morning = OfflineConversationEngine.reply("good morning jarvis", context(hour = 8))
        assertTrue(morning, morning.startsWith("Good morning, sir"))

        val evening = OfflineConversationEngine.reply("hello", context(hour = 21))
        assertTrue(evening, evening.startsWith("Good evening, sir"))

        val night = OfflineConversationEngine.reply("hey jarvis", context(hour = 2))
        assertTrue(night, night.contains("rather late") || night.contains("calm") || night.contains("Still awake"))
    }

    @Test
    fun greeting_mentions_missing_profile_once_memory_is_empty() {
        val reply = OfflineConversationEngine.reply("hello", context(hour = 10, ownerName = ""))
        assertTrue(reply, reply.contains("memory") || reply.startsWith("Good morning"))
    }

    @Test
    fun unknown_input_gets_a_useful_reply_not_a_fixed_slogan() {
        val first = OfflineConversationEngine.reply("the kettle is broken again", context(lastReply = null))
        val second = OfflineConversationEngine.reply("the kettle is broken again", context(lastReply = "Good evening, sir."))
        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertNotEquals(first, second)
        assertTrue(first.contains("sir"))
    }

    @Test
    fun identity_and_status_are_honest_about_offline_mode() {
        val identity = OfflineConversationEngine.reply("who are you?", context())
        assertTrue(identity, identity.contains("Jarvis"))

        val status = OfflineConversationEngine.reply("which model are you using?", context(modelReady = false))
        assertTrue(status, status.contains("built-in offline conversation engine"))

        val downloaded = OfflineConversationEngine.reply("which model are you using?", context(modelReady = true))
        assertTrue(downloaded, downloaded.contains("Qwen2.5 0.5B Instruct"))
    }

    @Test
    fun remembers_and_recalls_saved_facts() {
        val reply = OfflineConversationEngine.reply(
            "where is my passport",
            context(memory = listOf("passport number is in the blue folder")),
        )
        assertTrue(reply, reply.contains("blue folder"))

        val listing = OfflineConversationEngine.reply(
            "what do you remember about me",
            context(memory = listOf("likes filter coffee", "works night shifts")),
        )
        assertTrue(listing, listing.contains("filter coffee") && listing.contains("night shifts"))
    }

    @Test
    fun general_questions_are_declined_honestly_offline_and_offered_a_search() {
        val offline = OfflineConversationEngine.reply("what is quantum computing", context(network = false))
        assertTrue(offline, offline.contains("cannot verify"))

        val online = OfflineConversationEngine.reply("what is quantum computing", context(network = true))
        assertTrue(online, online.contains("web search"))
    }

    @Test
    fun repair_and_follow_up_use_conversation_context() {
        val repeat = OfflineConversationEngine.reply(
            "what did you just say",
            context(lastReply = "It is 09:15, sir."),
        )
        assertTrue(repeat, repeat.contains("It is 09:15, sir."))

        val previous = OfflineConversationEngine.reply(
            "what did i ask",
            context(previousQuestion = "open Spotify"),
        )
        assertTrue(previous, previous.contains("open Spotify"))

        val followUp = OfflineConversationEngine.reply(
            "tell me more",
            context(previousQuestion = "what is a neural network"),
        )
        assertTrue(followUp, followUp.contains("neural network"))
    }

    @Test
    fun tells_time_date_and_arithmetic() {
        assertTrue(OfflineConversationEngine.reply("what time is it?", context()).contains("09:15"))
        assertTrue(OfflineConversationEngine.reply("what is the date today?", context()).contains("2026"))
        assertTrue(OfflineConversationEngine.reply("what is 12 times 4", context()).contains("48"))
    }
}
