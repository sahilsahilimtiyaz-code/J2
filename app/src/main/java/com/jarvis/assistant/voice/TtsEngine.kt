package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.assistant.settings.SystemVoiceGender
import com.jarvis.assistant.settings.VoiceProvider
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class TtsConfig(
    val provider: VoiceProvider,
    val apiKey: String,
    val voiceId: String,
    val model: String = "",
    val speed: Float = 1.0f,
    val fallbackToSystem: Boolean = true,
    val language: String = "en",
    val systemVoiceGender: SystemVoiceGender = SystemVoiceGender.MALE,
)

/** One piece of raw 16-bit mono PCM, 24 kHz unless the provider says otherwise. */
data class AudioChunk(
    val bytes: ByteArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is AudioChunk && sampleRate == other.sampleRate && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + sampleRate
}

/**
 * A cloud voice.
 *
 * [synthesize] emits raw PCM as it arrives so playback can start on the first
 * chunk of the first sentence. Implementations own only the network request;
 * playback belongs to [AudioPlayer]. On failure the flow completes without
 * chunks and [lastError] explains why, in words fit to be shown and spoken.
 */
interface TtsEngine {
    var lastError: String?

    fun synthesize(text: String, config: TtsConfig): Flow<AudioChunk>
}

/** Fade length in mono 16-bit frames. 20 ms is enough to stop a click. */
private const val FADE_FRAMES = 480

/**
 * Ramps the last [FADE_FRAMES] samples of a little-endian 16-bit buffer down to
 * silence. Fading per sample (not per byte) is what keeps the tail from turning
 * into noise, and it is applied only to the final chunk of an utterance so
 * speech inside a sentence is never dipped.
 */
fun fadeOutPcm(pcm: ByteArray) {
    val frames = pcm.size / 2
    if (frames <= 0) return
    val fade = minOf(FADE_FRAMES, frames)
    val startFrame = frames - fade
    for (i in 0 until fade) {
        val factor = 1f - (i.toFloat() / fade)
        val index = (startFrame + i) * 2
        val raw = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
        val scaled = (raw.toShort().toInt() * factor).roundToInt().coerceIn(-32768, 32767)
        pcm[index] = (scaled and 0xFF).toByte()
        pcm[index + 1] = ((scaled shr 8) and 0xFF).toByte()
    }
}

/** Computes a normalized 0f..1f RMS amplitude from 16-bit little-endian mono PCM. */
private fun computePcmLevel(pcm: ByteArray, offset: Int, length: Int): Float {
    val samples = length / 2
    if (samples <= 0) return 0f
    var sumSquares = 0.0
    var count = 0
    val step = maxOf(1, samples / 64) * 2
    var i = offset
    val limit = (offset + length - 1).coerceAtMost(pcm.size - 1)
    while (i < limit) {
        val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
        sumSquares += sample * sample
        count++
        i += step
    }
    if (count == 0) return 0f
    val rms = sqrt(sumSquares / count) / 9000.0
    return rms.toFloat().coerceIn(0f, 1f)
}

/**
 * Owns a single [AudioTrack] for one spoken reply, so sentence N+1 continues in
 * the same buffer with no click or gap after sentence N.
 *
 * Every entry point is safe from any state: a released, stopped or uninitialised
 * session is ignored rather than thrown, because Stop and barge-in race the
 * audio thread by design.
 *
 * Crackle/"split voice" fix: streamed PCM used to start playing the moment the
 * first 1 KB chunk arrived, on a buffer barely half a second deep. Any network
 * hiccup later in the sentence drained that buffer and the track underran,
 * which audibly chops the voice mid-word. The player now accumulates a short
 * pre-roll (0.25 s), allocates a deep ~2.5 s jitter buffer and uses the
 * standard (non-low-latency) performance path, so playback rides out stalling
 * chunks without a single gap.
 */
class AudioPlayer(private val context: Context) {

    private class Session(val track: AudioTrack, val sampleRate: Int) {
        var framesWritten: Long = 0L

        @Volatile
        var released: Boolean = false
    }

    private class PendingChunk(val bytes: ByteArray, val sampleRate: Int)

    private val lock = Any()
    private var session: Session? = null
    /** Chunks held back until the pre-roll buffer is deep enough to start playing. */
    private val pendingChunks = ArrayDeque<PendingChunk>()
    private var pendingFrames: Long = 0L
    private var pendingFirstMs: Long = 0L

    /** Bumped by [stop] so an in-flight write aborts instead of touching a dead track. */
    private var generation: Long = 0L

    private val _audioLevel = MutableStateFlow(0f)
    /** Real-time 0f..1f voice amplitude driving the Iron Man J.A.R.V.I.S. Neural Orb HUD. */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    /** True while the speaker is actually producing sound. */
    val isSpeaking: Boolean
        get() = synchronized(lock) {
            val current = session ?: return@synchronized false
            !current.released && current.track.playState == AudioTrack.PLAYSTATE_PLAYING
        }

    /**
     * Appends PCM to the current reply, opening a track at [sampleRate] once a
     * 0.25 s pre-roll has piled up. Blocking writes pace the caller to real
     * time, which is the backpressure the synthesis loop needs. Writes never
     * run while holding [lock], so Stop always lands quickly.
     */
    suspend fun write(bytes: ByteArray, sampleRate: Int, fadeOut: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext true
            val gen = synchronized(lock) { generation }
            val data = if (fadeOut) bytes.copyOf().also { fadeOutPcm(it) } else bytes

            // Fast path: a track is already rolling at this rate.
            val rolling = synchronized(lock) {
                if (generation != gen) return@withContext true
                val current = session
                current?.takeIf { !it.released && it.sampleRate == sampleRate }
            }
            if (rolling != null) {
                return@withContext writeFrames(rolling, data, gen)
            }

            // Slow path: park the chunk into the pre-roll buffer, then flush when deep.
            var flushOverdue = false
            var flushRateChanged: Int? = null
            synchronized(lock) {
                if (generation != gen) return@withContext true
                if (pendingChunks.isNotEmpty() &&
                    pendingChunks.last().sampleRate != sampleRate
                ) {
                    // Rare mid-reply sample-rate switch: play the parked stream first.
                    flushRateChanged = pendingChunks.last().sampleRate
                }
                if (pendingChunks.isEmpty()) {
                    pendingFirstMs = System.currentTimeMillis()
                    flushOverdue = false
                }
                pendingChunks.addLast(PendingChunk(data, sampleRate))
                pendingFrames += (data.size / 2).toLong()
                if (!flushOverdue) {
                    flushOverdue =
                        System.currentTimeMillis() - pendingFirstMs > PREBUFFER_MAX_HOLD_MS
                }
            }
            flushRateChanged?.let { flushPending(it, gen) }
            val ready = synchronized(lock) {
                val overdue = System.currentTimeMillis() - pendingFirstMs > PREBUFFER_MAX_HOLD_MS
                pendingChunks.isNotEmpty() &&
                    (pendingFrames >= PREBUFFER_FRAMES || overdue)
            }
            if (ready) flushPending(sampleRate, gen)
            true
        }

    /** Starts the track (if needed) and plays every parked chunk, outside [lock]. */
    private suspend fun flushPending(rate: Int, gen: Long) {
        val parked = synchronized(lock) {
            if (generation != gen) {
                pendingChunks.clear()
                pendingFrames = 0L
                emptyList()
            } else {
                val snapshot = pendingChunks.toList()
                pendingChunks.clear()
                pendingFrames = 0L
                pendingFirstMs = 0L
                snapshot
            }
        }
        if (parked.isEmpty()) return
        val created = createSession(rate) ?: return
        parked.forEach { chunk -> writeFrames(created, chunk.bytes, gen) }
    }

    /** Waits (bounded) for the buffered audio to play, then frees the track. */
    suspend fun finish() = withContext(Dispatchers.IO) {
        val gen = synchronized(lock) { generation }
        val rate = synchronized(lock) { pendingChunks.lastOrNull()?.sampleRate }
        if (rate != null) flushPending(rate, gen)
        val target = synchronized(lock) { session }
        if (target == null) {
            _audioLevel.value = 0f
            return@withContext
        }
        try {
            drain(target)
        } finally {
            _audioLevel.value = 0f
            synchronized(lock) { releaseSession(target) }
        }
    }

    /** Silences playback immediately and frees the track. Clears any parked audio. */
    fun stop() {
        _audioLevel.value = 0f
        val target = synchronized(lock) {
            generation++
            pendingChunks.clear()
            pendingFrames = 0L
            pendingFirstMs = 0L
            val current = session
            session = null
            current
        }
        if (target != null) releaseSession(target)
    }

    private fun ensureSession(sampleRate: Int): Session? = createSession(sampleRate)

    private fun createSession(sampleRate: Int): Session? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).takeIf { it > 0 } ?: (sampleRate / 2)
        // A deep jitter buffer (~2.5 s at 24 kHz) absorbs stalled streaming chunks
        // so the voice never chops mid-sentence. Bytes = samples/s * 2 (16-bit).
        val bufferBytes = maxOf(minBuffer * 2, sampleRate * 5)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            lastError = "Could not open audio output: ${e.message}"
            return null
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            lastError = "Audio output could not be initialized."
            return null
        }

        val started = runCatching { track.play() }.isSuccess &&
            track.playState == AudioTrack.PLAYSTATE_PLAYING
        if (!started) {
            lastError = "Audio output could not be started."
            runCatching { track.release() }
            return null
        }

        val newSession = Session(track, sampleRate)
        synchronized(lock) { session = newSession }
        return newSession
    }

    private fun writeFrames(target: Session, data: ByteArray, gen: Long): Boolean {
        var offset = 0
        while (offset < data.size) {
            synchronized(lock) {
                if (generation != gen || target.released) {
                    _audioLevel.value = 0f
                    return false
                }
            }
            val written = try {
                target.track.write(data, offset, data.size - offset)
            } catch (e: IllegalStateException) {
                lastError = "Audio output was closed: ${e.message}"
                _audioLevel.value = 0f
                return false
            }
            when {
                written < 0 -> {
                    lastError = "Audio output error code $written"
                    _audioLevel.value = 0f
                    return false
                }
                written == 0 -> Thread.sleep(3)
                else -> {
                    _audioLevel.value = computePcmLevel(data, offset, written)
                    offset += written
                    synchronized(lock) {
                        if (!target.released) target.framesWritten += written / 2
                    }
                }
            }
        }
        return true
    }

    private fun drain(target: Session) {
        val deadline = System.currentTimeMillis() + MAX_DRAIN_MS
        while (System.currentTimeMillis() < deadline) {
            val written = synchronized(lock) {
                if (target.released) return
                target.framesWritten
            }
            if (written <= 0L) return
            val head = target.track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= written) return
            Thread.sleep(12)
        }
    }

    private fun releaseSession(target: Session) {
        if (target.released) return
        target.released = true
        _audioLevel.value = 0f
        val track = target.track
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING
            ) {
                track.stop()
            }
        }
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) track.release()
        }
    }

    private companion object {
        /** Never hang a reply because a device reports a stalled playback head. */
        const val MAX_DRAIN_MS = 30_000L

        /** Pre-roll before the track ever starts: 0.25 s of 24 kHz 16-bit mono PCM. */
        const val PREBUFFER_FRAMES = 6_000L

        /** Never sit on parked audio longer than this, even if the stream trickled. */
        const val PREBUFFER_MAX_HOLD_MS = 500L
    }
}
