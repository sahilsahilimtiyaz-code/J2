package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.jarvis.assistant.settings.VoiceProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One fully rendered voice note: a local WAV plus everything needed to wait for it. */
data class RenderedVoiceNote(
    val file: File,
    val sampleRate: Int,
    val durationMs: Long,
    val source: String,
)

/**
 * Minimal RIFF/WAVE writer for mono 16-bit PCM.
 *
 * WhatsApp records through its own microphone, so Jarvis cannot hand it a buffer
 * directly. The honest path is to render the line to a file, play it through the
 * loudspeaker at full media volume, and let WhatsApp's recorder capture it. WAV
 * is used because every Android text-to-speech engine can write it and MediaPlayer
 * can play it without a codec dependency.
 */
object PcmWav {
    const val HEADER_BYTES = 44

    fun write(file: File, pcm: ByteArray, sampleRate: Int): Boolean = runCatching {
        require(sampleRate in 8_000..48_000)
        require(pcm.size >= 2)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            val dataSize = pcm.size and 1.inv()
            val byteRate = sampleRate * 2
            val header = ByteArray(HEADER_BYTES)
            fun putAscii(offset: Int, value: String) {
                value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
            }

            fun putIntLE(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
                header[offset + 2] = ((value shr 16) and 0xFF).toByte()
                header[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }

            fun putShortLE(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }

            putAscii(0, "RIFF")
            putIntLE(4, 36 + dataSize)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putIntLE(16, 16)
            putShortLE(20, 1) // PCM
            putShortLE(22, 1) // mono
            putIntLE(24, sampleRate)
            putIntLE(28, byteRate)
            putShortLE(32, 2) // block align
            putShortLE(34, 16) // bits per sample
            putAscii(36, "data")
            putIntLE(40, dataSize)
            out.write(header)
            out.write(pcm, 0, dataSize)
            out.flush()
        }
        file.length() > HEADER_BYTES
    }.getOrDefault(false)

    fun durationMs(pcmBytes: Int, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return ((pcmBytes.coerceAtLeast(0) / 2L) * 1_000L / sampleRate).coerceAtLeast(0L)
    }
}

/**
 * Turns a line of Jarvis's speech into a local WAV.
 *
 * Cloud voices stream raw PCM, so their chunks are collected and wrapped. If a
 * cloud voice fails (no key, quota, network), the Android system voice is used
 * as the fallback when the owner allows it; the failure is surfaced, never hidden.
 */
class VoiceNoteStudio(
    private val cacheDir: File,
    private val fish: TtsEngine,
    private val eleven: TtsEngine,
    private val systemTts: SystemTtsEngine,
) {
    @Volatile
    var lastError: String? = null
        private set

    suspend fun render(text: String, config: TtsConfig): RenderedVoiceNote? =
        withContext(Dispatchers.IO) {
            lastError = null
            val clean = text.trim()
            if (clean.isBlank()) {
                lastError = "There was nothing to say."
                return@withContext null
            }
            val dir = File(cacheDir, "voice_notes").apply { mkdirs() }
            prune(dir)
            val file = File(dir, "note_${System.currentTimeMillis()}.wav")
            val engine = when (config.provider) {
                VoiceProvider.FISH_AUDIO -> fish
                VoiceProvider.ELEVENLABS -> eleven
                VoiceProvider.SYSTEM -> null
            }

            if (engine != null) {
                val pcm = ByteArrayOutputStream()
                var sampleRate = CLOUD_SAMPLE_RATE
                var failure: String? = null
                try {
                    withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                        engine.synthesize(clean, config).collect { chunk ->
                            if (pcm.size() < MAX_PCM_BYTES) {
                                pcm.write(chunk.bytes)
                                if (chunk.sampleRate > 0) sampleRate = chunk.sampleRate
                            }
                        }
                    }
                    failure = engine.lastError
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e::class.simpleName
                }
                val audio = pcm.toByteArray()
                if (audio.isNotEmpty() && PcmWav.write(file, audio, sampleRate)) {
                    return@withContext RenderedVoiceNote(
                        file = file,
                        sampleRate = sampleRate,
                        durationMs = PcmWav.durationMs(audio.size, sampleRate),
                        source = config.provider.label,
                    )
                }
                runCatching { file.delete() }
                lastError = failure ?: "The ${config.provider.label} voice returned no audio."
                if (!config.fallbackToSystem) {
                    return@withContext null
                }
            }

            // SYSTEM provider, or the explicit fallback after a cloud failure.
            val spoken = systemTts.synthesizeToFile(clean, file, config.language)
            if (spoken && file.length() > PcmWav.HEADER_BYTES) {
                val pcmBytes = (file.length() - PcmWav.HEADER_BYTES).toInt().coerceAtLeast(0)
                val sampleRate = readWavSampleRate(file) ?: SYSTEM_SAMPLE_RATE
                return@withContext RenderedVoiceNote(
                    file = file,
                    sampleRate = sampleRate,
                    durationMs = PcmWav.durationMs(pcmBytes, sampleRate),
                    source = "Android system voice",
                )
            }
            lastError = lastError
                ?: systemTts.lastError
                ?: "I could not render the voice note."
            runCatching { file.delete() }
            null
        }

    private fun readWavSampleRate(file: File): Int? = runCatching {
        val header = ByteArray(PcmWav.HEADER_BYTES)
        val read = file.inputStream().use { it.read(header) }
        if (read != header.size) return@runCatching null

        fun intLE(offset: Int): Int =
            (header[offset].toInt() and 0xFF) or
                ((header[offset + 1].toInt() and 0xFF) shl 8) or
                ((header[offset + 2].toInt() and 0xFF) shl 16) or
                ((header[offset + 3].toInt() and 0xFF) shl 24)

        intLE(24).takeIf { it in 8_000..48_000 }
    }.getOrNull()

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - PRUNE_AGE_MS
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private companion object {
        const val RENDER_TIMEOUT_MS = 45_000L
        const val MAX_PCM_BYTES = 24_000 * 2 * 75
        const val CLOUD_SAMPLE_RATE = 24_000
        const val SYSTEM_SAMPLE_RATE = 24_000
        const val PRUNE_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * Plays a rendered voice note through the loudspeaker for WhatsApp's microphone.
 *
 * Audio focus is requested as a transient media stream. Any headset or Bluetooth
 * output makes the acoustic loop impossible, so callers ask [externalAudioRoute]
 * first and refuse honestly rather than sending a silent voice note.
 */
class VoiceNotePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var completion: CompletableDeferred<Boolean>? = null

    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    var lastError: String? = null
        private set

    /** The name of an attached output that would bypass the loudspeaker, or null. */
    fun externalAudioRoute(): String? {
        val devices = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull().orEmpty()
        val external = devices.firstOrNull { it.type in EXTERNAL_OUTPUT_TYPES } ?: return null
        return when (external.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call device"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
            else -> "external audio device"
        }
    }

    /** Plays [file] and suspends until it finishes. Returns false on any failure. */
    suspend fun play(file: File, expectedDurationMs: Long): Boolean = withContext(Dispatchers.IO) {
        stop()
        lastError = null
        if (!file.exists() || file.length() <= PcmWav.HEADER_BYTES) {
            lastError = "The rendered voice note is missing."
            return@withContext false
        }
        val media = MediaPlayer()
        val done = CompletableDeferred<Boolean>()
        completion = done
        val started = runCatching {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            media.setDataSource(file.absolutePath)
            media.prepare()
            media.setOnCompletionListener { done.complete(true) }
            media.setOnErrorListener { _, what, extra ->
                lastError = "The voice note could not be played (MediaPlayer $what/$extra)."
                done.complete(false)
                true
            }
            player = media
            requestAudioFocus()
            media.start()
            true
        }.getOrElse { error ->
            lastError = "The voice note could not be played: ${error.message ?: error::class.simpleName}"
            false
        }
        if (!started) {
            runCatching { media.release() }
            if (player === media) player = null
            if (completion === done) completion = null
            abandonAudioFocus()
            return@withContext false
        }

        val duration = media.duration.takeIf { it > 0 } ?: expectedDurationMs.toInt()
        val finished = withTimeoutOrNull(duration.toLong() + PLAYBACK_GRACE_MS) { done.await() } ?: false
        if (!finished && lastError == null) {
            lastError = "The voice note playback did not finish in time."
        }
        runCatching { if (media.isPlaying) media.stop() }
        runCatching { media.release() }
        if (player === media) player = null
        if (completion === done) completion = null
        abandonAudioFocus()
        finished
    }

    fun stop() {
        val pending = completion
        completion = null
        runCatching {
            val media = player
            if (media != null) {
                if (media.isPlaying) media.stop()
                media.release()
            }
        }
        player = null
        if (pending != null && pending.isActive) pending.complete(false)
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean = runCatching {
        val manager = audioManager ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }.getOrDefault(false)

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    private companion object {
        const val PLAYBACK_GRACE_MS = 12_000L
        val EXTERNAL_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
