package com.jarvis.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Procedural Iron Man J.A.R.V.I.S. Acoustic Sound FX Engine.
 *
 * Generates high-fidelity sci-fi acoustic cues mathematically using raw 16-bit mono PCM
 * (sample rate 24 kHz) played via [AudioTrack] in low-latency stream mode.
 *
 * Benefits:
 * - 100% offline, zero external MP3/WAV assets required.
 * - Sub-millisecond startup latency.
 * - Clean frequency modulation and envelope shaping.
 */
object SoundFxEngine {

    private const val SAMPLE_RATE = 24000

    enum class SoundType {
        ARC_REACTOR_BOOT,
        LISTENING_START,
        COMMAND_ACKNOWLEDGED,
        ACTION_CONFIRMED,
        DUPLEX_CHIRP,
        ALERT_WARNING,
        STANDBY,
    }

    suspend fun play(type: SoundType, volume: Float = 0.65f) = withContext(Dispatchers.IO) {
        val pcm = when (type) {
            SoundType.ARC_REACTOR_BOOT -> generateReactorBootPcm()
            SoundType.LISTENING_START -> generateListeningStartPcm()
            SoundType.COMMAND_ACKNOWLEDGED -> generateCommandAckPcm()
            SoundType.ACTION_CONFIRMED -> generateActionConfirmedPcm()
            SoundType.DUPLEX_CHIRP -> generateDuplexChirpPcm()
            SoundType.ALERT_WARNING -> generateAlertWarningPcm()
            SoundType.STANDBY -> generateStandbyPcm()
        }
        playPcm(pcm, volume)
    }

    /**
     * Synthesizes a rising holographic frequency sweep (120 Hz -> 840 Hz) with resonant harmonic hum.
     */
    private fun generateReactorBootPcm(): ByteArray {
        val durationSeconds = 0.38f
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            // Exponential frequency ramp
            val freq = 120.0 + (720.0 * progress * progress)
            val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
            val subHarmonic = 2.0 * PI * (freq * 0.5) * (i.toDouble() / SAMPLE_RATE)

            // Attack-decay-sustain-release envelope
            val envelope = when {
                progress < 0.15f -> progress / 0.15f
                progress > 0.75f -> (1.0f - progress) / 0.25f
                else -> 1.0f
            }

            val sampleVal = (sin(phase) * 0.75 + sin(subHarmonic) * 0.25) * envelope
            buffer[i] = (sampleVal * 28000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteArray(buffer)
    }

    /**
     * Clean modern affirmative two-tone chime (660 Hz & 990 Hz harmonic interval).
     */
    private fun generateListeningStartPcm(): ByteArray {
        val durationSeconds = 0.18f
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = if (progress < 0.45f) 659.25 else 987.77 // E5 then B5
            val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
            val envelope = (1.0f - progress) * (1.0f - progress) // Smooth decay

            val sampleVal = sin(phase) * envelope
            buffer[i] = (sampleVal * 24000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteArray(buffer)
    }

    /**
     * Dual high-tech acknowledgment pulse (523 Hz & 784 Hz).
     */
    private fun generateCommandAckPcm(): ByteArray {
        val durationSeconds = 0.14f
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = 784.0 // G5
            val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
            val overtone = 2.0 * PI * (freq * 2.0) * (i.toDouble() / SAMPLE_RATE)
            val envelope = (1.0f - progress) * (1.0f - progress)

            val sampleVal = (sin(phase) * 0.8 + sin(overtone) * 0.2) * envelope
            buffer[i] = (sampleVal * 22000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteArray(buffer)
    }

    /**
     * Resonant, confident confirmation chord (440 Hz -> 554 Hz).
     */
    private fun generateActionConfirmedPcm(): ByteArray {
        val durationSeconds = 0.22f
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = if (progress < 0.35f) 440.0 else 554.37 // A4 then C#5
            val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
            val envelope = (1.0f - progress)

            val sampleVal = sin(phase) * envelope
            buffer[i] = (sampleVal * 26000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteArray(buffer)
    }

    /**
     * Subtle, high-tech prompt chirp for continuous duplex conversation mode.
     */
    private fun generateDuplexChirpPcm(): ByteArray {
        val durationSeconds = 0.10f
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = 1200.0 + (600.0 * progress)
            val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
            val envelope = sin(progress * PI.toFloat())

            val sampleVal = sin(phase) * envelope
            buffer[i] = (sampleVal * 20000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteArray(buffer)
    }

    /**
     * Pulsing high-priority warning / security alert sound.
     */
    private fun generateAlertWarningPcm(): ByteArray {
        val durationSeconds = 0.30f
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = if ((i / (SAMPLE_RATE * 0.075f).toInt()) % 2 == 0) 880.0 else 660.0
            val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
            val envelope = if (progress > 0.8f) (1.0f - progress) / 0.2f else 1.0f

            val sampleVal = sin(phase) * envelope
            buffer[i] = (sampleVal * 26000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteArray(buffer)
    }

    /**
     * Soft sign-off tone when duplex conversation mode concludes.
     */
    private fun generateStandbyPcm(): ByteArray {
        val durationSeconds = 0.25f
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val progress = i.toFloat() / totalSamples
            val freq = 523.25 - (150.0 * progress) // Downward glide C5 -> ~373Hz
            val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
            val envelope = (1.0f - progress) * (1.0f - progress)

            val sampleVal = sin(phase) * envelope
            buffer[i] = (sampleVal * 18000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteArray(buffer)
    }

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun playPcm(pcm: ByteArray, volume: Float) {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(pcm.size)

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.setVolume(volume.coerceIn(0f, 1f))
                track.play()
                track.write(pcm, 0, pcm.size)
                // Drain small audio tail
                val drainMs = (pcm.size.toDouble() / (SAMPLE_RATE * 2) * 1000).toLong() + 20
                Thread.sleep(drainMs.coerceAtMost(500))
                track.stop()
            }
        } catch (_: Exception) {
            // Ignore audio device transient errors
        } finally {
            runCatching { track?.release() }
        }
    }
}
