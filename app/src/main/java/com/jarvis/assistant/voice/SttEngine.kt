package com.jarvis.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

sealed interface SttEvent {
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
    data class Rms(val level: Float) : SttEvent
    data class Error(val message: String, val code: Int? = null) : SttEvent
    data object End : SttEvent
}

interface SttEngine {
    fun listen(locale: Locale): Flow<SttEvent>
}

/**
 * Keeps a single continuous, call-like [AudioRecord] open in-process while Jarvis's
 * microphone is live so Android's `OP_RECORD_AUDIO` privacy indicator stays
 * steadily ON (like a live phone call) and NEVER cycles OFF and ON.
 *
 * Architecture:
 * - Holds one persistent 16kHz 16-bit mono [AudioRecord] continuously without ever
 *   stopping during silence or between turns.
 * - Maintains a 750ms circular PCM pre-roll buffer (12,000 samples / 24,000 bytes)
 *   and runs adaptive Voice Activity Detection (VAD).
 * - During silence, [SpeechRecognizer] is NOT started at all — eliminating the 4-second
 *   `ERROR_SPEECH_TIMEOUT` rollover loop and `ERROR_AUDIO` HAL conflicts.
 * - When the user speaks, streams the 750ms pre-roll + live PCM frames directly into
 *   [SpeechRecognizer] via Android 13+ [RecognizerIntent.EXTRA_AUDIO_SOURCE] pipe so
 *   [SpeechRecognizer] never opens or closes hardware `OP_RECORD_AUDIO`.
 */
@SuppressLint("MissingPermission")
class LiveCallAudioAnchor(
    private val stateMachine: MicrophoneStateMachine? = null,
) {
    private val lock = Any()
    private val sinkLock = Any()

    @Volatile
    private var recorder: AudioRecord? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var drainThread: Thread? = null

    @Volatile
    var isLive: Boolean = false
        private set

    @Volatile
    var onRmsLevel: ((Float) -> Unit)? = null

    @Volatile
    var onVoiceActivityDetected: (() -> Unit)? = null

    @Volatile
    var onVoiceSilenceReached: (() -> Unit)? = null

    // 1.2-second circular pre-roll buffer at 16,000 Hz (19,200 shorts = 38,400 bytes)
    private val preRollCapacityShorts = 19_200
    private val preRollRing = ShortArray(preRollCapacityShorts)
    private var preRollWritePos = 0
    private var preRollFilled = 0

    private var activePipeSink: OutputStream? = null

    @Volatile
    var isSpeechActive: Boolean = false
        private set

    @Volatile
    var suppressedForTts: Boolean = false

    fun ensureLive(): Boolean {
        synchronized(lock) {
            if (isLive &&
                recorder?.state == AudioRecord.STATE_INITIALIZED &&
                recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                return true
            }
            stopInternalLocked(notifyStateMachine = false)

            val sampleRate = SAMPLE_RATE_HZ
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuffer <= 0) return false
            val bufferSize = (minBuffer * 3).coerceAtLeast(sampleRate * 2)

            val created = createBestAudioRecord(sampleRate, channel, encoding, bufferSize)
                ?: return false

            val started = runCatching {
                created.startRecording()
                created.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

            if (!started) {
                runCatching { created.release() }
                return false
            }

            attachAudioEffects(created.audioSessionId)
            recorder = created
            isLive = true
            isSpeechActive = false
            preRollWritePos = 0
            preRollFilled = 0
            stateMachine?.onAudioRecordStateChanged("RECORDING (LIVE CALL)", true)

            val thread = Thread({
                runCaptureLoop()
            }, "JarvisLiveCallMicAnchor").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
            drainThread = thread
            return true
        }
    }

    private fun createBestAudioRecord(
        sampleRate: Int,
        channel: Int,
        encoding: Int,
        bufferSize: Int,
    ): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        for (source in sources) {
            val candidate = runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channel)
                    .build()
                val builder = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setPrivacySensitive(false)
                }
                builder.build()
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    private fun attachAudioEffects(sessionId: Int) {
        if (sessionId <= 0) return
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        }
    }

    private fun runCaptureLoop() {
        val frameShorts = ShortArray(FRAME_SHORTS) // 320 shorts = 20ms @ 16kHz
        val pcmBytes = ByteArray(FRAME_SHORTS * 2)
        var noiseFloorRms = 14.0
        var consecutiveVoiceFrames = 0
        var lastVoiceFrameAtMs = 0L
        var speechTriggeredAtMs = 0L

        while (isLive && !Thread.currentThread().isInterrupted) {
            val activeRec = recorder ?: break
            val read = runCatching { activeRec.read(frameShorts, 0, frameShorts.size) }.getOrDefault(-1)
            if (read <= 0) {
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            var sumSq = 0.0
            var peak = 0
            for (i in 0 until read) {
                val s = frameShorts[i].toInt()
                val abs = kotlin.math.abs(s)
                if (abs > peak) peak = abs
                sumSq += (s * s).toDouble()
            }
            val rms = kotlin.math.sqrt(sumSq / read)
            // Perceptual logarithmic audio scaling: normal speech (~35-350 RMS) gives 0.25 - 0.85 audioLevel
            val normalizedLevel = if (rms > 10.0) {
                (kotlin.math.ln((rms / 11.0).coerceAtLeast(1.0)) / kotlin.math.ln(42.0))
                    .toFloat().coerceIn(0.05f, 1f)
            } else {
                0.04f
            }
            if (!suppressedForTts) {
                onRmsLevel?.invoke(normalizedLevel)
            }

            // Dynamic ambient noise floor tracking (stays agile in quiet & moderate environments)
            if (!isSpeechActive && rms < noiseFloorRms * 1.6) {
                noiseFloorRms = (noiseFloorRms * 0.94 + rms * 0.06).coerceIn(10.0, 180.0)
            }

            // High-sensitivity speech threshold: triggers immediately on soft or normal voice
            val voiceThreshold = (noiseFloorRms * 1.15 + 5.0).coerceIn(14.0, 120.0)
            val isVoiceFrame = !suppressedForTts && (rms >= voiceThreshold || peak >= 50)
            val now = System.currentTimeMillis()

            // Convert shorts to little-endian 16-bit PCM bytes for any attached pipe sink
            var sinkToWrite: OutputStream?
            synchronized(sinkLock) {
                if (!suppressedForTts) {
                    appendPreRollLocked(frameShorts, read)
                }
                sinkToWrite = activePipeSink
            }

            if (sinkToWrite != null) {
                var bIdx = 0
                for (i in 0 until read) {
                    val v = frameShorts[i].toInt()
                    pcmBytes[bIdx++] = (v and 0xFF).toByte()
                    pcmBytes[bIdx++] = ((v shr 8) and 0xFF).toByte()
                }
                val ok = runCatching {
                    sinkToWrite.write(pcmBytes, 0, read * 2)
                    true
                }.getOrDefault(false)
                if (!ok) {
                    detachPipeSink()
                }
            }

            if (isVoiceFrame) {
                consecutiveVoiceFrames++
                lastVoiceFrameAtMs = now
                if (!isSpeechActive && consecutiveVoiceFrames >= 1) {
                    isSpeechActive = true
                    speechTriggeredAtMs = now
                    onVoiceActivityDetected?.invoke()
                }
            } else {
                consecutiveVoiceFrames = 0
                if (isSpeechActive) {
                    val silenceDurationMs = now - lastVoiceFrameAtMs
                    val totalSpeechMs = now - speechTriggeredAtMs
                    if (suppressedForTts || silenceDurationMs >= TRAILING_SILENCE_MS || totalSpeechMs >= MAX_UTTERANCE_MS) {
                        isSpeechActive = false
                        onVoiceSilenceReached?.invoke()
                    }
                }
            }
        }
    }

    private fun appendPreRollLocked(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            preRollRing[preRollWritePos] = samples[i]
            preRollWritePos = (preRollWritePos + 1) % preRollCapacityShorts
            if (preRollFilled < preRollCapacityShorts) {
                preRollFilled++
            }
        }
    }

    /**
     * Attaches an [OutputStream] (such as the write end of a `ParcelFileDescriptor.createPipe()`)
     * and immediately flushes the 1.2s circular pre-roll buffer into it so the beginning of
     * the user's spoken phrase is never clipped.
     */
    fun attachPipeSinkWithPreRoll(sink: OutputStream) {
        val preRollBytes: ByteArray
        synchronized(sinkLock) {
            runCatching { activePipeSink?.close() }
            activePipeSink = sink
            val count = preRollFilled
            preRollBytes = ByteArray(count * 2)
            val startIdx = if (count == preRollCapacityShorts) preRollWritePos else 0
            var bIdx = 0
            for (i in 0 until count) {
                val sample = preRollRing[(startIdx + i) % preRollCapacityShorts].toInt()
                preRollBytes[bIdx++] = (sample and 0xFF).toByte()
                preRollBytes[bIdx++] = ((sample shr 8) and 0xFF).toByte()
            }
        }
        if (preRollBytes.isNotEmpty()) {
            runCatching {
                sink.write(preRollBytes)
                sink.flush()
            }
        }
    }

    fun detachPipeSink() {
        var old: OutputStream?
        synchronized(sinkLock) {
            old = activePipeSink
            activePipeSink = null
        }
        runCatching { old?.flush() }
        runCatching { old?.close() }
    }

    fun resumeHoldBeforeRecognizerStop() {
        ensureLive()
    }

    fun stopLive(reason: String = "Microphone stopped") {
        synchronized(lock) {
            stopInternalLocked(notifyStateMachine = true, reason = reason)
        }
    }

    private fun stopInternalLocked(notifyStateMachine: Boolean, reason: String? = null) {
        isLive = false
        isSpeechActive = false
        suppressedForTts = false
        detachPipeSink()
        val thread = drainThread
        drainThread = null
        runCatching { thread?.interrupt() }
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        val rec = recorder
        recorder = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (notifyStateMachine) {
            stateMachine?.onAudioRecordStateChanged("RELEASED", false)
            if (reason != null && stateMachine?.currentState == MicrophoneState.IDLE) {
                // state machine already updated
            }
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        private const val FRAME_SHORTS = 320 // 20ms frame at 16kHz
        private const val TRAILING_SILENCE_MS = 1_100L
        private const val MAX_UTTERANCE_MS = 20_000L
    }
}

/**
 * Uninterrupted live-call Android [SpeechRecognizer] engine backed by [LiveCallAudioAnchor].
 *
 * How this eliminates the status-bar microphone ON/OFF cycling completely:
 * 1. [LiveCallAudioAnchor] keeps a single in-process [AudioRecord] open continuously
 *    for the entire time Jarvis's mic is live. It NEVER calls `recorder.stop()` during
 *    silence or between turns.
 * 2. During silence, [SpeechRecognizer] is NOT running at all — only [LiveCallAudioAnchor]'s
 *    in-process VAD monitors the audio stream and updates the UI voice orb RMS level.
 * 3. The instant the user begins speaking, [LiveCallAudioAnchor] triggers VAD and
 *    streams the 1.2s pre-roll + live PCM audio into [SpeechRecognizer] through
 *    [RecognizerIntent.EXTRA_AUDIO_SOURCE] (`ParcelFileDescriptor.createPipe()`).
 *    Because [SpeechRecognizer] reads from the pipe instead of opening the hardware
 *    microphone, there is zero hardware mic contention and zero `OP_RECORD_AUDIO`
 *    start/stop cycling in Android's status bar.
 */
@SuppressLint("MissingPermission")
class AndroidSttEngine(
    private val context: Context,
    private val stateMachine: MicrophoneStateMachine? = null,
) : SttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    val liveAnchor = LiveCallAudioAnchor(stateMachine)

    @Volatile
    private var preferOnDevice: Boolean = false

    @Volatile
    private var omitExplicitLanguage: Boolean = false

    @Volatile
    private var pipeModeSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @Volatile
    private var consecutivePipeAudioErrors: Int = 0

    @Volatile
    private var activeRecognizerRef: SpeechRecognizer? = null

    @Volatile
    private var activeHardwareCounted: Boolean = false

    /**
     * Ensures the continuous live-call microphone anchor is holding `OP_RECORD_AUDIO`
     * so the status-bar microphone indicator stays steadily ON like a live call.
     */
    fun ensureLiveCallAnchor(): Boolean = liveAnchor.ensureLive()

    /**
     * Mutes/unmutes VAD triggering while Jarvis is speaking so TTS audio is not
     * transcribed as user speech, while keeping the hardware [AudioRecord] 100% open.
     */
    fun setMutedForTts(muted: Boolean) {
        liveAnchor.suppressedForTts = muted
        if (muted) {
            liveAnchor.detachPipeSink()
        }
    }

    /**
     * Stops active speech recognition before LLM generation / TTS playback while
     * keeping the [LiveCallAudioAnchor] open so the microphone stays live
     * like a phone call across conversation turns.
     */
    fun pauseRecognitionKeepLiveAnchor(reason: String = "Paused recognizer for Jarvis reply") {
        liveAnchor.suppressedForTts = true
        liveAnchor.detachPipeSink()
        liveAnchor.ensureLive()
        val cleanup = Runnable {
            val rec = activeRecognizerRef
            if (rec != null) {
                runCatching { rec.stopListening() }
                runCatching { rec.cancel() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    /**
     * Immediately stops, cancels, and destroys any active [SpeechRecognizer] AND
     * optionally releases the [LiveCallAudioAnchor] so the microphone is 100% closed.
     */
    fun forceDestroyActive(reason: String = "Force stop", releaseLiveAnchor: Boolean = true) {
        liveAnchor.detachPipeSink()
        if (releaseLiveAnchor) {
            liveAnchor.stopLive(reason)
        } else {
            liveAnchor.ensureLive()
        }
        val cleanup = Runnable {
            val rec = activeRecognizerRef
            activeRecognizerRef = null
            if (rec != null) {
                runCatching { rec.stopListening() }
                runCatching { rec.cancel() }
                runCatching { rec.destroy() }
            }
            if (releaseLiveAnchor && activeHardwareCounted) {
                activeHardwareCounted = false
                stateMachine?.onRecognitionHardwareReleased(stopReason = reason)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    override fun listen(locale: Locale): Flow<SttEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            stateMachine?.onRecognitionError(
                errorMessage = "Speech recognition is not available on this phone.",
                stopReason = "Recognizer unavailable",
            )
            trySend(SttEvent.Error("Speech recognition is not available on this phone."))
            close()
            return@callbackFlow
        }

        // 1. Ensure the single continuous AudioRecord is running and holding OP_RECORD_AUDIO.
        liveAnchor.suppressedForTts = false
        val anchorOk = liveAnchor.ensureLive()
        stateMachine?.onListeningReady()

        var recognizer: SpeechRecognizer? = activeRecognizerRef
        var activeReadPipe: ParcelFileDescriptor? = null
        var closed = false
        var hardwareOpened = activeHardwareCounted
        var recognitionInFlight = false
        var usingOnDevice = false
        var lastPartialText = ""
        var fallbackStage = 0
        var startRecognitionForSpeech: () -> Unit = {}
        lateinit var makeListener: () -> RecognitionListener

        fun emit(event: SttEvent) {
            if (!closed) trySend(event)
        }

        fun closeActivePipe() {
            liveAnchor.detachPipeSink()
            val rp = activeReadPipe
            activeReadPipe = null
            runCatching { rp?.close() }
        }

        liveAnchor.onRmsLevel = { level ->
            if (!closed && !liveAnchor.suppressedForTts) {
                emit(SttEvent.Rms(level))
            }
        }

        fun canUseOnDevice(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                    .getOrDefault(false)

        fun languageTag(requested: Locale): String {
            val lang = requested.language.lowercase(Locale.ROOT)
            val country = requested.country.uppercase(Locale.ROOT)
            if (lang.isBlank() || lang == "und") {
                return Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
            }
            if (country.isNotBlank()) return "$lang-$country"
            val system = Locale.getDefault()
            if (system.language.equals(lang, ignoreCase = true) && system.country.isNotBlank()) {
                return "${system.language}-${system.country}"
            }
            return when (lang) {
                "en" -> "en-US"
                "hi" -> "hi-IN"
                "ur" -> "ur-PK"
                "ar" -> "ar-SA"
                "es" -> "es-ES"
                "fr" -> "fr-FR"
                "de" -> "de-DE"
                else -> system.toLanguageTag().ifBlank { "en-US" }
            }
        }

        fun buildIntent(audioPipeReadFd: ParcelFileDescriptor?): Intent {
            val includeLanguage = !omitExplicitLanguage
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                if (includeLanguage) {
                    val tag = languageTag(locale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 12_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1_200L,
                )
                if (audioPipeReadFd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioPipeReadFd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                        LiveCallAudioAnchor.SAMPLE_RATE_HZ,
                    )
                }
                if (usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
        }

        fun pickBestCandidate(bundle: Bundle?): String {
            val matches = bundle
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (matches.isEmpty()) return ""
            val scores = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            if (scores != null && scores.size == matches.size) {
                var bestIdx = 0
                var bestScore = -1f
                for (i in matches.indices) {
                    val score = scores[i]
                    val lengthBonus = (matches[i].length.coerceAtMost(80) / 80f) * 0.08f
                    if (score + lengthBonus > bestScore) {
                        bestScore = score + lengthBonus
                        bestIdx = i
                    }
                }
                return matches[bestIdx]
            }
            return matches.maxByOrNull { it.length } ?: matches.first()
        }

        fun markHardwareOpened() {
            if (!hardwareOpened) {
                hardwareOpened = true
                activeHardwareCounted = true
                stateMachine?.onRecognitionHardwareOpened()
            }
        }

        fun destroyRecognizer(releaseReason: String? = null, releaseHardwareCount: Boolean = false) {
            recognitionInFlight = false
            closeActivePipe()
            val rec = recognizer
            recognizer = null
            if (activeRecognizerRef === rec) {
                activeRecognizerRef = null
            }
            if (rec != null) {
                runCatching { rec.stopListening() }
                runCatching { rec.cancel() }
                runCatching { rec.destroy() }
            }
            if (releaseHardwareCount && !liveAnchor.isLive && hardwareOpened) {
                hardwareOpened = false
                activeHardwareCounted = false
                stateMachine?.onRecognitionHardwareReleased(stopReason = releaseReason)
            }
        }

        fun createRecognizer(): SpeechRecognizer? {
            destroyRecognizer(releaseHardwareCount = false)
            val wantOnDevice = preferOnDevice && canUseOnDevice()
            var createdOnDevice = false
            val created = runCatching {
                if (wantOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    createdOnDevice = true
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            }.recoverCatching {
                createdOnDevice = false
                SpeechRecognizer.createSpeechRecognizer(context)
            }.getOrNull()
                ?: if (wantOnDevice) {
                    createdOnDevice = false
                    runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
                } else {
                    null
                }

            if (created == null) return null
            usingOnDevice = createdOnDevice
            recognizer = created
            activeRecognizerRef = created
            created.setRecognitionListener(makeListener())
            return created
        }

        startRecognitionForSpeech = recognition@{
            if (closed || recognitionInFlight || liveAnchor.suppressedForTts) return@recognition
            liveAnchor.ensureLive()
            val rec = recognizer ?: createRecognizer()
            if (rec == null) {
                stateMachine?.onRecognitionError(
                    errorMessage = "Speech recognition could not be started.",
                    stopReason = "Recognizer creation failed",
                )
                emit(SttEvent.Error("Speech recognition could not be started."))
                close()
                return@recognition
            }

            closeActivePipe()
            var readEnd: ParcelFileDescriptor? = null
            if (pipeModeSupported && liveAnchor.isLive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
                if (pipe != null && pipe.size == 2) {
                    readEnd = pipe[0]
                    activeReadPipe = readEnd
                    val writeStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                    liveAnchor.attachPipeSinkWithPreRoll(writeStream)
                }
            }

            lastPartialText = ""
            recognitionInFlight = true
            val started = runCatching { rec.startListening(buildIntent(readEnd)) }.isSuccess
            if (started) {
                markHardwareOpened()
            } else {
                recognitionInFlight = false
                closeActivePipe()
            }
        }

        // Hook up VAD callbacks on the continuous LiveCallAudioAnchor:
        // SpeechRecognizer only starts when the user actually speaks!
        liveAnchor.onVoiceActivityDetected = {
            if (!closed && !recognitionInFlight && !liveAnchor.suppressedForTts) {
                mainHandler.post {
                    if (!closed && !recognitionInFlight && !liveAnchor.suppressedForTts) {
                        startRecognitionForSpeech()
                    }
                }
            }
        }

        liveAnchor.onVoiceSilenceReached = {
            if (!closed) {
                // Close the pipe so SpeechRecognizer sees EOF and immediately finalizes the transcript
                liveAnchor.detachPipeSink()
                mainHandler.post {
                    if (!closed && recognitionInFlight) {
                        runCatching { recognizer?.stopListening() }
                    }
                }
            }
        }

        makeListener = {
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    markHardwareOpened()
                    stateMachine?.onListeningReady()
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    if (!liveAnchor.isLive && !liveAnchor.suppressedForTts) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.04f, 1f)
                        emit(SttEvent.Rms(normalized))
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    closeActivePipe()
                    emit(SttEvent.End)
                }

                override fun onError(error: Int) {
                    if (closed) return
                    recognitionInFlight = false
                    closeActivePipe()
                    liveAnchor.ensureLive()

                    // 1. Rescue words already spoken before a pause or silence cutoff.
                    val rescued = lastPartialText.trim()
                    if (rescued.length >= 2) {
                        lastPartialText = ""
                        consecutivePipeAudioErrors = 0
                        emit(SttEvent.Final(rescued))
                        close()
                        return
                    }

                    // 2. If offline language pack is missing, fall back to standard recognizer.
                    val languageProblem =
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                            (usingOnDevice &&
                                (error == SpeechRecognizer.ERROR_CLIENT ||
                                    error == SpeechRecognizer.ERROR_SERVER))
                    if (languageProblem && fallbackStage < 2) {
                        fallbackStage++
                        if (usingOnDevice) {
                            preferOnDevice = false
                        } else {
                            omitExplicitLanguage = true
                        }
                        createRecognizer()
                        if (liveAnchor.isSpeechActive && !liveAnchor.suppressedForTts) {
                            mainHandler.postDelayed({ startRecognitionForSpeech() }, 35L)
                        }
                        return
                    }

                    // Only disable pipe mode if ERROR_AUDIO happens 3 consecutive times during active speech
                    if (error == SpeechRecognizer.ERROR_AUDIO && pipeModeSupported) {
                        consecutivePipeAudioErrors++
                        if (consecutivePipeAudioErrors >= 3) {
                            pipeModeSupported = false
                            createRecognizer()
                        }
                        if (liveAnchor.isSpeechActive && !liveAnchor.suppressedForTts) {
                            mainHandler.postDelayed({ startRecognitionForSpeech() }, 60L)
                        }
                        return
                    }

                    // 3. Recoverable silence / no-match / client / busy errors:
                    // Do NOT spin in an endless 4-second startListening loop during silence!
                    // LiveCallAudioAnchor stays 100% open and will trigger startRecognitionForSpeech()
                    // the moment the user speaks again.
                    if (isRecoverableSttError(error) ||
                        error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                    ) {
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_SERVER
                        ) {
                            createRecognizer()
                        }
                        if ((!liveAnchor.isLive || !pipeModeSupported || liveAnchor.isSpeechActive) &&
                            !liveAnchor.suppressedForTts
                        ) {
                            mainHandler.postDelayed({
                                if (!closed && !recognitionInFlight && !liveAnchor.suppressedForTts) {
                                    startRecognitionForSpeech()
                                }
                            }, 180L)
                        }
                        return
                    }

                    // 4. Unrecoverable error (e.g., permission revoked)
                    val msg = sttErrorMessage(error)
                    val stopReason = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                        else -> "Recognition error ($error): $msg"
                    }
                    stateMachine?.onRecognitionError(errorMessage = msg, stopReason = stopReason)
                    destroyRecognizer(stopReason, releaseHardwareCount = true)
                    emit(SttEvent.Error(msg, error))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    if (closed) return
                    recognitionInFlight = false
                    consecutivePipeAudioErrors = 0
                    closeActivePipe()
                    liveAnchor.ensureLive()
                    val text = pickBestCandidate(results).ifBlank { lastPartialText.trim() }
                    lastPartialText = ""
                    if (text.isNotBlank()) {
                        emit(SttEvent.Final(text))
                        close()
                    } else if ((!liveAnchor.isLive || !pipeModeSupported || liveAnchor.isSpeechActive) &&
                        !liveAnchor.suppressedForTts
                    ) {
                        mainHandler.postDelayed({
                            if (!closed && !recognitionInFlight && !liveAnchor.suppressedForTts) {
                                startRecognitionForSpeech()
                            }
                        }, 120L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (closed) return
                    consecutivePipeAudioErrors = 0
                    val text = pickBestCandidate(partialResults)
                    if (text.isNotBlank()) {
                        lastPartialText = text
                        emit(SttEvent.Partial(text))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
        }

        if (recognizer == null) {
            createRecognizer()
        } else {
            recognizer?.setRecognitionListener(makeListener())
        }
        markHardwareOpened()

        // If LiveCallAudioAnchor is active and pipeMode is supported (Android 13+),
        // LiveCallAudioAnchor holds the hardware mic open continuously and buffers 1.2s of pre-roll.
        // We only start SpeechRecognizer when voice is active (or immediately if anchor/pipe is unavailable).
        if (!anchorOk || !pipeModeSupported || liveAnchor.isSpeechActive) {
            startRecognitionForSpeech()
        }

        awaitClose {
            closed = true
            liveAnchor.onRmsLevel = null
            liveAnchor.onVoiceActivityDetected = null
            liveAnchor.onVoiceSilenceReached = null
            closeActivePipe()
            mainHandler.removeCallbacksAndMessages(null)
            // Keep the SpeechRecognizer instance alive across turns while LiveCallAudioAnchor is active
            // so Android never tears down and recreates the recognizer between utterances.
            if (!liveAnchor.isLive) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    destroyRecognizer(releaseHardwareCount = true)
                } else {
                    mainHandler.post { destroyRecognizer(releaseHardwareCount = true) }
                }
            } else {
                val stopRec = Runnable {
                    if (recognitionInFlight) {
                        recognitionInFlight = false
                        runCatching { recognizer?.cancel() }
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    stopRec.run()
                } else {
                    mainHandler.post(stopRec)
                }
            }
        }
    }.flowOn(Dispatchers.Main)
}

/** True when the error means the next listen attempt is worth trying. */
fun isRecoverableSttError(code: Int?): Boolean = when (code) {
    SpeechRecognizer.ERROR_AUDIO,
    SpeechRecognizer.ERROR_CLIENT,
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    -> true

    else -> false
}

internal fun sttErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "I could not hear you clearly."
    SpeechRecognizer.ERROR_CLIENT -> "Speech recognition stopped unexpectedly."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
    SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection."
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out."
    SpeechRecognizer.ERROR_NO_MATCH -> "I did not catch that."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy."
    SpeechRecognizer.ERROR_SERVER -> "Speech recognition failed on the server."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear anything."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
        "Speech language pack not found. Check Google Voice Typing in Android Settings."

    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "That language is not downloaded for offline speech recognition."

    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition is rate limiting. One moment."
    else -> "Speech recognition failed."
}

