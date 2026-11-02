package com.jarvis.assistant.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * High-accuracy cloud STT engine utilizing Whisper models (such as Groq's
 * ultra-low latency whisper-large-v3-turbo or OpenAI's whisper-1).
 *
 * Records 16 kHz 16-bit mono PCM via [AudioRecord], calculates real-time RMS
 * audio levels, detects silence endpoints, packages the audio as a compliant WAV
 * file, and calls the OpenAI-compatible transcription endpoint.
 */
class WhisperSttEngine(
    private val client: OkHttpClient,
    private val getApiKey: () -> String?,
    private val endpointUrl: String = "https://api.groq.com/openai/v1/audio/transcriptions",
    private val model: String = "whisper-large-v3-turbo",
) : SttEngine {

    override fun listen(locale: Locale): Flow<SttEvent> = callbackFlow {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            trySend(SttEvent.Error("Whisper STT requires an active API key."))
            close()
            return@callbackFlow
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize.coerceAtLeast(sampleRate * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
            )
        } catch (e: Exception) {
            trySend(SttEvent.Error("Could not initialize microphone: ${e.message}"))
            close()
            return@callbackFlow
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            trySend(SttEvent.Error("Microphone hardware failed to initialize."))
            close()
            return@callbackFlow
        }

        val pcmStream = ByteArrayOutputStream()
        val readBuffer = ByteArray(2048)
        var silenceStartTime = 0L
        var speechDetected = false
        var totalRecordedBytes = 0
        val maxRecordingBytes = sampleRate * 2 * 30 // 30 seconds max

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            trySend(SttEvent.Error("Could not start audio capture: ${e.message}"))
            close()
            return@callbackFlow
        }

        trySend(SttEvent.Partial("Listening with Whisper..."))

        val captureJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            while (isActive && totalRecordedBytes < maxRecordingBytes) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    pcmStream.write(readBuffer, 0, read)
                    totalRecordedBytes += read

                    // Calculate RMS
                    var sum = 0.0
                    val shortCount = read / 2
                    for (i in 0 until shortCount) {
                        val sample = (readBuffer[i * 2 + 1].toInt() shl 8) or (readBuffer[i * 2].toInt() and 0xFF)
                        sum += sample * sample
                    }
                    val rms = kotlin.math.sqrt(sum / shortCount.coerceAtLeast(1))
                    val normalizedRms = (rms / 32767.0).toFloat().coerceIn(0f, 1f)
                    trySend(SttEvent.Rms(normalizedRms))

                    // Voice Activity Detection heuristic
                    if (normalizedRms > 0.06f) {
                        speechDetected = true
                        silenceStartTime = 0L
                    } else if (speechDetected) {
                        val now = System.currentTimeMillis()
                        if (silenceStartTime == 0L) {
                            silenceStartTime = now
                        } else if (now - silenceStartTime > 1800L) {
                            // 1.8 seconds of silence after speech detected -> end of utterance
                            break
                        }
                    }
                }
            }

            trySend(SttEvent.End)
            trySend(SttEvent.Partial("Transcribing audio..."))

            try {
                record.stop()
            } catch (_: Exception) {}
            record.release()

            val rawPcm = pcmStream.toByteArray()
            if (rawPcm.size < sampleRate * 2 / 2) { // Less than 0.5 sec
                trySend(SttEvent.Error("No speech detected."))
                close()
                return@launch
            }

            val wavBytes = pcmToWav(rawPcm, sampleRate = sampleRate, channels = 1)

            val transcriptionResult = transcribeAudio(
                wavBytes = wavBytes,
                apiKey = apiKey,
                language = locale.language.ifBlank { "en" },
            )

            if (transcriptionResult.isNotBlank()) {
                trySend(SttEvent.Final(transcriptionResult))
            } else {
                trySend(SttEvent.Error("I did not catch that."))
            }
            close()
        }

        awaitClose {
            captureJob.cancel()
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun transcribeAudio(
        wavBytes: ByteArray,
        apiKey: String,
        language: String,
    ): String = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "speech.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use ""
                val bodyText = response.body?.string().orEmpty()
                val parsed = Json.parseToJsonElement(bodyText)
                parsed.jsonObject["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            }
        }.getOrDefault("")
    }

    companion object {
        fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * channels * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // Subchunk1Size for PCM
            header.putShort(1) // AudioFormat: 1 = PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * 2).toShort()) // BlockAlign
            header.putShort(16) // BitsPerSample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcmData.size)

            val wav = ByteArray(44 + pcmData.size)
            System.arraycopy(header.array(), 0, wav, 0, 44)
            System.arraycopy(pcmData, 0, wav, 44, pcmData.size)
            return wav
        }
    }
}
