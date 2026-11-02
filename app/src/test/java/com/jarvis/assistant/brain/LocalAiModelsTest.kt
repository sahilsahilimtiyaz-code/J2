package com.jarvis.assistant.brain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiModelsTest {

    private val sampleTools = listOf(
        ToolDefinition("send_whatsapp_voice_note", "Send WhatsApp voice note", buildJsonObject {}),
        ToolDefinition("send_whatsapp_message", "Send WhatsApp text", buildJsonObject {}),
        ToolDefinition("search_youtube", "Search YouTube", buildJsonObject {}),
        ToolDefinition("open_app", "Open installed app", buildJsonObject {}),
        ToolDefinition("control_flashlight", "Toggle flashlight", buildJsonObject {}),
        ToolDefinition("set_timer", "Set countdown timer", buildJsonObject {}),
        ToolDefinition("set_alarm", "Set alarm", buildJsonObject {}),
        ToolDefinition("set_volume", "Adjust volume", buildJsonObject {}),
        ToolDefinition("run_diagnostics", "Run diagnostics", buildJsonObject {}),
        ToolDefinition("remember_about_owner", "Save owner fact", buildJsonObject {}),
        ToolDefinition("search_memory", "Search saved memory and notes", buildJsonObject {}),
        ToolDefinition("web_search", "Search the web", buildJsonObject {}),
    )

    @Test
    fun catalog_contains_all_six_requested_lightweight_models() {
        val models = LocalModelCatalog.models
        assertEquals(6, models.size)

        assertEquals("LFM2.5 350M", models[0].displayName)
        assertEquals("250–350 MB", models[0].approxDownloadSizeLabel)
        assertEquals("Extremely lightweight chat", models[0].bestUse)

        assertEquals("Qwen2.5 0.5B Instruct", models[1].displayName)
        assertEquals("400 MB", models[1].approxDownloadSizeLabel)
        assertEquals("Basic assistant", models[1].bestUse)

        assertEquals("Gemma 3 1B", models[2].displayName)
        assertEquals("700 MB", models[2].approxDownloadSizeLabel)
        assertEquals("General conversation", models[2].bestUse)

        assertEquals("LFM2.5 1.2B Instruct", models[3].displayName)
        assertEquals("731 MB", models[3].approxDownloadSizeLabel)
        assertEquals("General-purpose local AI", models[3].bestUse)

        assertEquals("SmolLM2 1.7B", models[4].displayName)
        assertEquals("990 MB", models[4].approxDownloadSizeLabel)
        assertEquals("Lightweight chat and writing", models[4].bestUse)

        assertEquals("Qwen3 1.7B", models[5].displayName)
        assertEquals("1.28 GB", models[5].approxDownloadSizeLabel)
        assertEquals("Stronger", models[5].bestUse)

        models.forEach { spec ->
            assertTrue(spec.downloadUrl.startsWith("https://huggingface.co/"))
            assertTrue(spec.downloadUrl.contains(".gguf"))
            assertTrue(spec.fileName.endsWith(".gguf"))
        }
    }

    @Test
    fun gguf_inspector_validates_authentic_gguf_binary_header_and_rejects_corrupt_files() {
        val tempGguf = File.createTempFile("test_model", ".gguf")
        try {
            val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("GGUF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(3) // GGUF v3
            buffer.putLong(290L) // tensor count
            buffer.putLong(24L) // kv count
            tempGguf.writeBytes(buffer.array())

            val info = GgufModelInspector.inspect(tempGguf)
            assertTrue(info.valid)
            assertTrue(info.validMagic)
            assertEquals(3, info.version)
            assertEquals(290L, info.tensorCount)
            assertEquals(24L, info.kvCount)

            val corruptFile = File.createTempFile("corrupt", ".gguf")
            corruptFile.writeBytes("HTML error page".toByteArray())
            val corruptInfo = GgufModelInspector.inspect(corruptFile)
            assertEquals(false, corruptInfo.valid)
            corruptFile.delete()
        } finally {
            tempGguf.delete()
        }
    }

    @Test
    fun offline_task_router_routes_whatsapp_youtube_apps_and_hardware_commands() {
        val voiceCall = OfflineTaskRouter.routeUserCommand(
            "Hi Jarvis send a voice message on WhatsApp to Azhar as introducing yourself",
            sampleTools,
        ).firstOrNull()
        assertNotNull(voiceCall)
        assertEquals("send_whatsapp_voice_note", voiceCall!!.name)
        assertTrue(voiceCall.argumentsJson.contains("Azhar"))

        val ytCall = OfflineTaskRouter.routeUserCommand(
            "open YouTube and search lo-fi hip hop",
            sampleTools,
        ).firstOrNull()
        assertNotNull(ytCall)
        assertEquals("search_youtube", ytCall!!.name)
        assertTrue(ytCall.argumentsJson.contains("lo-fi hip hop"))

        val torchCall = OfflineTaskRouter.routeUserCommand(
            "turn on the flashlight",
            sampleTools,
        ).firstOrNull()
        assertNotNull(torchCall)
        assertEquals("control_flashlight", torchCall!!.name)
        assertTrue(torchCall.argumentsJson.contains("on"))

        val timerCall = OfflineTaskRouter.routeUserCommand(
            "set a timer for 10 minutes",
            sampleTools,
        ).firstOrNull()
        assertNotNull(timerCall)
        assertEquals("set_timer", timerCall!!.name)
        assertTrue(timerCall.argumentsJson.contains("600"))

        val openAppCall = OfflineTaskRouter.routeUserCommand(
            "open Spotify",
            sampleTools,
        ).firstOrNull()
        assertNotNull(openAppCall)
        assertEquals("open_app", openAppCall!!.name)
        assertTrue(openAppCall.argumentsJson.contains("Spotify"))

        val webResearchCall = OfflineTaskRouter.routeUserCommand(
            "do a web research on quantum computing breakthroughs",
            sampleTools,
        ).firstOrNull()
        assertNotNull(webResearchCall)
        assertEquals("web_search", webResearchCall!!.name)
        assertTrue(webResearchCall.argumentsJson.contains("quantum computing breakthroughs"))

        val noteSaveCall = OfflineTaskRouter.routeUserCommand(
            "save a note that my passport number is in the blue folder",
            sampleTools,
        ).firstOrNull()
        assertNotNull(noteSaveCall)
        assertEquals("remember_about_owner", noteSaveCall!!.name)
        assertTrue(noteSaveCall.argumentsJson.contains("\"text\""))
        assertTrue(noteSaveCall.argumentsJson.contains("passport number"))

        val memoryRecallCall = OfflineTaskRouter.routeUserCommand(
            "what do you remember about my stored notes?",
            sampleTools,
        ).firstOrNull()
        assertNotNull(memoryRecallCall)
        assertEquals("search_memory", memoryRecallCall!!.name)
    }

    @Test
    fun local_llm_provider_executes_tool_calls_and_synthesizes_offline_reply() = runBlocking {
        val provider = LocalLlmProvider(OkHttpClient(), downloadManager = null)
        val config = ProviderConfig(
            id = "local_ai",
            name = "Offline Local AI",
            kind = ProviderKind.LOCAL_ON_DEVICE,
            baseUrl = "http://127.0.0.1:8080/v1",
            model = "lfm2_5_350m",
            apiKey = "",
        )

        // Round 1: User asks to turn on the flashlight -> emits ToolCallReady
        val round1Events = provider.stream(
            request = ChatRequest(
                messages = listOf(
                    LlmMessage.system("You are Jarvis."),
                    LlmMessage.user("turn on the flashlight"),
                ),
                tools = sampleTools,
            ),
            config = config,
        ).toList()

        val toolEvent = round1Events.filterIsInstance<LlmEvent.ToolCallReady>().firstOrNull()
        assertNotNull(toolEvent)
        assertEquals("control_flashlight", toolEvent!!.call.name)

        // Round 2: Tool result returned -> emits spoken J.A.R.V.I.S. confirmation
        val round2Events = provider.stream(
            request = ChatRequest(
                messages = listOf(
                    LlmMessage.system("You are Jarvis."),
                    LlmMessage.user("turn on the flashlight"),
                    LlmMessage.assistant("", listOf(toolEvent.call)),
                    LlmMessage.tool(toolEvent.call, "Flashlight turned on."),
                ),
                tools = sampleTools,
            ),
            config = config,
        ).toList()

        val text = round2Events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("Flashlight turned on"))
        assertTrue(round2Events.any { it is LlmEvent.Done })
    }
}
