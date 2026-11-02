package com.jarvis.assistant.voice

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * ElevenLabs streaming text to speech. The Flash model returns raw PCM at
 * 24 kHz, which is the same format the Fish Audio path uses, so both voices
 * share one playback pipeline.
 */
class ElevenLabsTtsEngine(private val client: OkHttpClient) : TtsEngine {

    override var lastError: String? = null

    override fun synthesize(text: String, config: TtsConfig): Flow<AudioChunk> = flow {
        lastError = null
        if (config.apiKey.isBlank()) {
            lastError = "ElevenLabs needs an API key. Add one in Settings, then Voice."
            return@flow
        }
        if (config.voiceId.isBlank()) {
            lastError = "ElevenLabs needs a voice id. Copy it from your ElevenLabs voice library."
            return@flow
        }
        val clean = text.trim()
        if (clean.isEmpty()) return@flow

        val payload = JSONObject().apply {
            put("text", clean)
            put("model_id", config.model.trim().ifBlank { DEFAULT_MODEL })
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("style", 0.0)
                put("use_speaker_boost", true)
                put("speed", config.speed.coerceIn(0.7f, 1.2f).toDouble())
            })
        }
        val url = "https://api.elevenlabs.io/v1/text-to-speech/${config.voiceId.trim()}/stream" +
            "?output_format=pcm_$PCM_SAMPLE_RATE&optimize_streaming_latency=4"
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", config.apiKey.trim())
            .header("Content-Type", "application/json")
            .header("Accept", "audio/pcm")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(request)
        val response = try {
            call.execute()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: Exception) {
            call.cancel()
            lastError = "ElevenLabs could not be reached: ${e.message ?: e::class.simpleName}"
            return@flow
        }

        try {
            if (!response.isSuccessful) {
                val detail = runCatching {
                    response.body.string().take(600).replace(Regex("\\s+"), " ").trim()
                }.getOrDefault("")
                lastError = friendlyError(response.code, detail)
                return@flow
            }
            val stream = response.body.byteStream()
            val buffer = ByteArray(STREAM_CHUNK_BYTES)
            var pendingHalfByte = -1
            var sawAudio = false
            while (true) {
                coroutineContext.ensureActive()
                val maxRead = if (!sawAudio) FIRST_CHUNK_BYTES else STREAM_CHUNK_BYTES
                val read = try {
                    stream.read(buffer, 0, maxRead)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!sawAudio) {
                        lastError = "ElevenLabs stream interrupted: ${e.message ?: e::class.simpleName}"
                    }
                    break
                }
                if (read <= 0) break

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
                    emit(AudioChunk(combined.copyOf(evenLength), PCM_SAMPLE_RATE))
                }
            }
            if (!sawAudio && lastError == null) {
                lastError = "ElevenLabs returned no audio. Check the voice id and model."
            }
        } finally {
            runCatching { response.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun friendlyError(code: Int, detail: String): String {
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (code) {
            401 -> "ElevenLabs rejected the API key. Check it in the ElevenLabs dashboard."
            403 -> "This ElevenLabs key is not allowed to use that voice$suffix."
            404 -> "ElevenLabs could not find that voice id."
            422 -> "ElevenLabs rejected the voice settings$suffix."
            429 -> "ElevenLabs is rate limiting requests. I will use the fallback voice."
            in 500..599 -> "ElevenLabs is having server trouble (HTTP $code)."
            else -> "ElevenLabs returned HTTP $code$suffix."
        }
    }

    private companion object {
        const val PCM_SAMPLE_RATE = 24_000
        const val FIRST_CHUNK_BYTES = 1024
        const val STREAM_CHUNK_BYTES = 4096
        const val DEFAULT_MODEL = "eleven_flash_v2_5"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
