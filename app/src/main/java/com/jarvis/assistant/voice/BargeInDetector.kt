package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Watches the microphone while Jarvis is speaking and fires [onSpeech] when the
 * owner starts talking, so the reply can be cut off mid-sentence.
 *
 * The detector is energy-based on purpose: Android offers no reliable
 * "is someone else talking" signal. To avoid Jarvis triggering on his own
 * voice the echo canceller is enabled, the listener is briefly disarmed while
 * playback ramps up, and the threshold is calibrated against the measured
 * room noise.
 */
class BargeInDetector(private val context: Context) {
    private var job: Job? = null
    var lastError: String? = null
        private set

    /** How long the detector stays deaf after it starts, in milliseconds. */
    var armDelayMs: Long = 700L

    /** Minimum loudness (RMS) that counts as speech, before calibration. */
    var baseThreshold: Double = 2200.0

    val isRunning: Boolean get() = job?.isActive == true

    fun start(onSpeech: () -> Unit): Job {
        stop()
        lastError = null

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "Microphone permission is needed to listen for interruptions."
            return Job().also { it.complete() }
        }

        val newJob = CoroutineScope(Dispatchers.IO).launch {
            val sampleRate = 16000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuffer <= 0) {
                lastError = "This device cannot run the microphone."
                return@launch
            }

            val recorder = try {
                AudioRecord(
                    // VOICE_COMMUNICATION enables echo cancellation on most devices,
                    // which is what keeps Jarvis from hearing himself.
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channel,
                    encoding,
                    minBuffer * 2,
                )
            } catch (e: Exception) {
                lastError = "Failed to initialise the microphone: ${e.message}"
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                lastError = "Microphone could not be initialised."
                return@launch
            }

            val effects = mutableListOf<Any>()
            if (AcousticEchoCanceler.isAvailable()) {
                runCatching {
                    AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                        enabled = true
                        effects += this
                    }
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                runCatching {
                    NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                        enabled = true
                        effects += this
                    }
                }
            }

            val buffer = ShortArray(minBuffer)
            try {
                recorder.startRecording()
                calibrate(recorder, buffer)
                run(recorder, buffer, onSpeech)
            } catch (e: Exception) {
                lastError = "Interruption listening failed: ${e.message}"
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                effects.forEach { effect ->
                    runCatching {
                        when (effect) {
                            is AcousticEchoCanceler -> effect.release()
                            is NoiseSuppressor -> effect.release()
                        }
                    }
                }
            }
        }
        job = newJob
        return newJob
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Measures the room so the speech threshold adapts to quiet and loud places. */
    private fun calibrate(recorder: AudioRecord, buffer: ShortArray) {
        val deadline = System.currentTimeMillis() + 400L
        var peak = 0.0
        var samples = 0
        while (System.currentTimeMillis() < deadline) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) break
            peak += rms(buffer, read)
            samples++
        }
        if (samples > 0) {
            val floor = peak / samples
            baseThreshold = maxOf(baseThreshold, floor * 2.2)
        }
    }

    private suspend fun run(
        recorder: AudioRecord,
        buffer: ShortArray,
        onSpeech: () -> Unit,
    ) {
        val armedAt = System.currentTimeMillis() + armDelayMs
        val threshold = baseThreshold
        var consecutive = 0
        var releaseAfter = 0

        while (coroutineContext.isActive) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            // Speaker has to stay loud for three frames (~95 ms) so a knock or a
            // keyboard click does not count as an interruption.
            if (rms(buffer, read) > threshold) {
                consecutive++
            } else {
                consecutive = 0
            }
            if (consecutive < 3) continue

            if (System.currentTimeMillis() < armedAt) {
                continue
            }
            onSpeech()
            releaseAfter++
            // One trigger is enough; a second is only reported after a pause so
            // the caller is not spammed while speech continues.
            if (releaseAfter >= 1) break
        }
    }

    private fun rms(buffer: ShortArray, read: Int): Double {
        var sum = 0.0
        for (i in 0 until read) {
            val value = buffer[i].toDouble()
            sum += value * value
        }
        return sqrt(sum / read)
    }
}
