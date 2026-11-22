package com.jarvis.assistant.brain

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real on-device GGUF inference engine backed by native ARM64 `llama.cpp`
 * (`dev.ffmpegkit-maintained:llama-android`).
 *
 * Key engineering safeguards for mobile hardware (Samsung Galaxy A54):
 * - Caches a single active [LlamaModel] handle so subsequent turns answer quickly
 *   without reloading hundreds of megabytes of weights from flash storage.
 * - Uses adaptive CPU thread counts (2–4 threads) to prevent thermal throttling
 *   and battery drain.
 * - Releases native memory cleanly on model switch or Android `onTrimMemory`.
 * - Fails gracefully if native libraries are unavailable (e.g. JVM unit tests or
 *   non-ARM64 emulators), allowing [LocalLlmProvider] to fall back cleanly.
 */
object OnDeviceGgufEngine {

    private val mutex = Mutex()

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var loadedModel: LlamaModel? = null

    @Volatile
    private var loadedThreads: Int = 0

    @Volatile
    private var nativeUnavailable: Boolean = false

    fun isModelLoaded(): Boolean = loadedModel?.isLoaded == true

    fun loadedPath(): String? = loadedModelPath

    /**
     * Generates a natural conversational reply directly from the downloaded `.gguf`
     * file on-device. Returns `null` if native inference is unavailable or fails.
     */
    suspend fun generateReply(
        modelFile: File,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 256,
        threads: Int = 4,
        contextSize: Int = 2048,
    ): String? {
        if (nativeUnavailable || !modelFile.exists() || !modelFile.isFile) {
            return null
        }

        val safeThreads = threads.coerceIn(1, 4)
        val safeContext = contextSize.coerceIn(1024, 4096)

        return mutex.withLock {
            if (nativeUnavailable) return@withLock null
            try {
                val model = ensureModelLoadedLocked(
                    modelFile = modelFile,
                    threads = safeThreads,
                    contextSize = safeContext,
                ) ?: return@withLock null

                val result = Llama.complete(
                    model = model,
                    prompt = userPrompt,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens.coerceIn(64, 512),
                )
                result.text.trim().takeIf { it.isNotBlank() }
            } catch (e: UnsatisfiedLinkError) {
                nativeUnavailable = true
                null
            } catch (e: NoClassDefFoundError) {
                nativeUnavailable = true
                null
            } catch (e: Throwable) {
                // If inference failed because the handle became stale or low-RAM, release it cleanly
                releaseModelLocked()
                null
            }
        }
    }

    /**
     * Builds a compact, natural-language-only system prompt optimized for small
     * 350M–1.7B quantized GGUF models so they answer questions accurately in
     * clear prose instead of emitting raw code or hallucinated JSON tool calls.
     */
    fun buildCompactLocalSystemPrompt(
        ownerName: String,
        ownerTitle: String,
        activeModeName: String,
        memoryHighlights: List<String>,
        userAskedForCode: Boolean,
    ): String = buildString {
        append("You are J.A.R.V.I.S., the calm, polite, intelligent personal AI assistant created by Octavian. ")
        if (ownerName.isNotBlank()) {
            append("You are assisting $ownerName (address them as $ownerTitle). ")
        } else {
            append("Address the user as $ownerTitle. ")
        }
        if (activeModeName.isNotBlank() && !activeModeName.equals("Standard", ignoreCase = true)) {
            append("Active mode: $activeModeName. ")
        }
        if (userAskedForCode) {
            append("Answer accurately and concisely, providing clean code only where requested. ")
        } else {
            append(
                "Answer every question directly, accurately, and naturally in clear conversational English prose (2 to 5 concise sentences). " +
                    "NEVER output JSON, XML, tool_call tags, Python, code blocks, or programming syntax. " +
                    "Do not show internal thinking tags. ",
            )
        }
        if (memoryHighlights.isNotEmpty()) {
            append("Known context about the user: ")
            append(memoryHighlights.take(4).joinToString("; "))
            append(".")
        }
    }

    /** Releases any loaded GGUF weights from native RAM (called on memory pressure or model switch). */
    suspend fun release() {
        mutex.withLock {
            releaseModelLocked()
        }
    }

    /** Best-effort non-blocking release for synchronous Android lifecycle callbacks like `onTrimMemory`. */
    fun releaseBestEffort() {
        val current = loadedModel ?: return
        loadedModel = null
        loadedModelPath = null
        loadedThreads = 0
        runCatching { Llama.releaseModel(current) }
    }

    private suspend fun ensureModelLoadedLocked(
        modelFile: File,
        threads: Int,
        contextSize: Int,
    ): LlamaModel? {
        val path = modelFile.absolutePath
        val existing = loadedModel
        if (existing != null && existing.isLoaded && loadedModelPath == path && loadedThreads == threads) {
            return existing
        }

        releaseModelLocked()

        val config = LlamaConfig(
            contextSize = contextSize,
            threads = threads,
            gpuLayers = 0,
            temperature = 0.65f,
            topP = 0.90f,
            topK = 40,
            seed = -1,
        )
        val loaded = Llama.loadModel(modelPath = path, config = config)
        loadedModel = loaded
        loadedModelPath = path
        loadedThreads = threads
        return loaded
    }

    private fun releaseModelLocked() {
        val current = loadedModel
        loadedModel = null
        loadedModelPath = null
        loadedThreads = 0
        if (current != null) {
            runCatching { Llama.releaseModel(current) }
        }
    }
}
