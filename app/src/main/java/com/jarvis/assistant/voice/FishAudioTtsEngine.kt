package com.jarvis.assistant.voice

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Fish Audio text to speech over `POST /v1/tts`.
 *
 * IMPORTANT: Because `.flowOn(Dispatchers.IO)` already runs the producer on IO,
 * we must never wrap `emit(...)` inside `withContext(Dispatchers.IO)` or a broad
 * `try/catch` that retries after a collector exception—doing so violates Kotlin
 * Coroutines Flow context preservation and exception transparency.
 */
class FishAudioTtsEngine(private val client: OkHttpClient) : TtsEngine {

    override var lastError: String? = null

    @Volatile
    private var cachedWorkingModel: String? = null

    @Volatile
    private var warmedUp: Boolean = false

    /**
     * Pre-warms the TCP + TLS 1.3 connection pool to `api.fish.audio` in the
     * background so the very first sentence synthesis has zero handshake delay.
     */
    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        runCatching {
            val req = Request.Builder()
                .url("https://api.fish.audio/")
                .head()
                .build()
            client.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    runCatching { response.close() }
                }
            })
        }
    }

    override fun synthesize(text: String, config: TtsConfig): Flow<AudioChunk> = flow {
        lastError = null
        if (config.apiKey.isBlank()) {
            lastError = "Fish Audio needs an API key. Add one in Settings, then Voice."
            return@flow
        }
        val effectiveVoiceId = config.voiceId.trim()
        if (effectiveVoiceId.isBlank()) {
            lastError = "Fish Audio needs a voice reference id. Copy it from your Fish Audio voice."
            return@flow
        }
        val clean = text.trim()
        if (clean.isEmpty()) return@flow

        val payload = JSONObject().apply {
            put("text", clean)
            put("reference_id", effectiveVoiceId)
            put("format", "pcm")
            put("sample_rate", PCM_SAMPLE_RATE)
            put("latency", "balanced")
            put("normalize", true)
            // chunk_length=100 flushes the first vocoder packet ~2x faster than 200
            put("chunk_length", 100)
            put("prosody", JSONObject().put("speed", config.speed.coerceIn(0.5f, 2.0f).toDouble()))
            config.language.trim().takeIf { it.isNotBlank() && it.length <= 35 }?.let {
                val baseLang = it.substringBefore('-').substringBefore('_').lowercase()
                if (baseLang.length in 2..3) {
                    put("language", baseLang)
                }
            }
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA)

        val candidateModels = buildModelCandidates(config.model)
        var attempts = 0
        var modelIndex = 0

        while (true) {
            attempts++
            lastError = null
            val currentModel = candidateModels[modelIndex.coerceAtMost(candidateModels.lastIndex)]
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer ${config.apiKey.trim()}")
                .header("Content-Type", "application/json")
                .header("Accept", "audio/pcm")
                .header("model", currentModel)
                .post(body)
                .build()

            val call = client.newCall(request)
            var retryable = false
            var sawAudio = false

            val response = try {
                call.execute()
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (e: Exception) {
                call.cancel()
                lastError = "Fish Audio could not be reached: ${e.message ?: e::class.simpleName}"
                retryable = true
                null
            }

            if (response != null) {
                try {
                    if (!response.isSuccessful) {
                        val code = response.code
                        val detail = readErrorBody(response)
                        // If the model is rejected with 400/402/404 and we have a fallback candidate, try it immediately
                        if ((code == 400 || code == 402 || code == 404) && modelIndex + 1 < candidateModels.size) {
                            modelIndex++
                            retryable = true
                        } else {
                            retryable = code == 429 || code >= 500
                        }
                        lastError = friendlyError(code, detail)
                    } else {
                        cachedWorkingModel = currentModel
                        val stream = response.body.byteStream()
                        val buffer = ByteArray(STREAM_CHUNK_BYTES)
                        var pendingHalfByte: Int = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            // Read a small 1KB slice for the very first chunk so playback starts in milliseconds
                            val maxRead = if (!sawAudio) FIRST_CHUNK_BYTES else STREAM_CHUNK_BYTES
                            val read = try {
                                stream.read(buffer, 0, maxRead)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (!sawAudio) {
                                    lastError = "Fish Audio stream interrupted: ${e.message ?: e::class.simpleName}"
                                    retryable = true
                                }
                                break
                            }
                            if (read <= 0) break

                            // Ensure 16-bit PCM alignment (even number of bytes per chunk)
                            val combined = if (pendingHalfByte >= 0) {
                                ByteArray(read + 1).also { arr ->
                                    arr[0] = pendingHalfByte.toByte()
                                    System.arraycopy(buffer, 0, arr, 1, read)
                                }
                            } else {
                                buffer.copyOf(read)
                            }

                            val evenLength = combined.size and 1.inv()
                            pendingHalfByte = if (combined.size > evenLength) {
                                combined[combined.lastIndex].toInt() and 0xFF
                            } else {
                                -1
                            }

                            if (evenLength > 0) {
                                sawAudio = true
                                // Emit directly in the flow builder context — never inside withContext!
                                emit(AudioChunk(combined.copyOf(evenLength), PCM_SAMPLE_RATE))
                            }
                        }
                        if (!sawAudio && lastError == null) {
                            lastError = "Fish Audio returned no audio. Check the voice id and try again."
                        }
                    }
                } finally {
                    runCatching { response.close() }
                }
            }

            if (sawAudio || lastError == null || !retryable || attempts >= MAX_ATTEMPTS) return@flow
            // Fast retry when switching model headers (50ms), normal backoff on network/5xx
            val delayMs = if (modelIndex > 0 && attempts == 1) 40L else RETRY_DELAY_MS * attempts
            delay(delayMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun buildModelCandidates(configured: String): List<String> {
        val trimmed = configured.trim()
        val remembered = cachedWorkingModel
        return buildList {
            if (trimmed.isNotBlank()) add(trimmed)
            if (!remembered.isNullOrBlank()) add(remembered)
            add(DEFAULT_MODEL)
            add("s1")
            add("speech-1.6")
        }.distinct()
    }

    private fun readErrorBody(response: okhttp3.Response): String = runCatching {
        response.body.string().take(600).replace(Regex("\\s+"), " ").trim()
    }.getOrDefault("")

    private fun friendlyError(code: Int, detail: String): String {
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (code) {
            400 -> "Fish Audio rejected the request. Check the voice reference id$suffix."
            401 -> "Fish Audio rejected the API key. Copy it again from fish.audio/app/api-keys."
            402 -> "Fish Audio has no credit for this model. Choose s2.1-pro-free in Voice settings."
            403 -> "This Fish Audio key is not allowed to use that voice$suffix."
            404 -> "Fish Audio could not find that voice reference id."
            413 -> "That sentence was too long for Fish Audio."
            429 -> "Fish Audio is busy (rate limit). I will use the fallback voice."
            in 500..599 -> "Fish Audio is having server trouble (HTTP $code)."
            else -> "Fish Audio returned HTTP $code$suffix."
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.fish.audio/v1/tts"
        const val DEFAULT_MODEL = "s2.1-pro-free"
        const val PCM_SAMPLE_RATE = 24_000
        const val FIRST_CHUNK_BYTES = 1024

        /**
         * Network read slice while streaming. 16384 B = 4096 samples = 256 ms at
         * 16 kHz stereo — big enough that each AudioTrack write corresponds to a
         * comfortable slice of the deep jitter buffer, so the voice never runs
         * dry between network packets and never "splits" mid-word.
         */
        const val STREAM_CHUNK_BYTES = 16_384
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 250L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
