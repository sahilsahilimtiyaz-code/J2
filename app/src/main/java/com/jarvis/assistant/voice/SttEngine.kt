package com.jarvis.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
 * Keeps a continuous, call-like [AudioRecord] open in-process while Jarvis's
 * microphone is live so Android's `OP_RECORD_AUDIO` privacy indicator stays
 * steadily ON (like a live phone call) instead of cycling OFF and ON every few
 * seconds when [SpeechRecognizer] rolls over between silence windows or turns.
 *
 * Uses `setPrivacySensitive(false)` on Android 11+ (API 30+) so Android's
 * concurrent capture policy allows [SpeechRecognizer] (`VOICE_RECOGNITION`)
 * to capture simultaneously without contention. If a device's audio HAL ever
 * reports `ERROR_AUDIO` during concurrent capture, [yieldWhileRecognizerActive]
 * automatically switches to relay-baton handover within SystemUI's indicator
 * retention window.
 */
@SuppressLint("MissingPermission")
class LiveCallAudioAnchor(
    private val stateMachine: MicrophoneStateMachine? = null,
) {
    private val lock = Any()

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var drainThread: Thread? = null

    @Volatile
    var isLive: Boolean = false
        private set

    @Volatile
    var pausedForRecognizerYield: Boolean = false
        private set

    @Volatile
    var yieldWhileRecognizerActive: Boolean = false

    @Volatile
    var onRmsLevel: ((Float) -> Unit)? = null

    fun ensureLive(): Boolean {
        synchronized(lock) {
            if (isLive && recorder?.state == AudioRecord.STATE_INITIALIZED) {
                if (pausedForRecognizerYield) {
                    pausedForRecognizerYield = false
                    runCatching { recorder?.startRecording() }
                    stateMachine?.onAudioRecordStateChanged("RECORDING (LIVE CALL)", true)
                }
                return true
            }
            stopInternalLocked(notifyStateMachine = false)

            val sampleRate = 16000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuffer <= 0) return false
            val bufferSize = (minBuffer * 2).coerceAtLeast(sampleRate)

            val created = runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channel)
                    .build()
                val builder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setPrivacySensitive(false)
                }
                builder.build()
            }.getOrNull() ?: return false

            if (created.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { created.release() }
                return false
            }

            val started = runCatching {
                created.startRecording()
                created.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

            if (!started) {
                runCatching { created.release() }
                return false
            }

            recorder = created
            isLive = true
            pausedForRecognizerYield = false
            stateMachine?.onAudioRecordStateChanged("RECORDING (LIVE CALL)", true)

            val thread = Thread({
                val buf = ShortArray(1024)
                while (isLive && !Thread.currentThread().isInterrupted) {
                    val activeRec = recorder ?: break
                    if (pausedForRecognizerYield) {
                        try {
                            Thread.sleep(20L)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    val read = runCatching { activeRec.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (read > 0) {
                        val callback = onRmsLevel
                        if (callback != null) {
                            var sum = 0.0
                            for (i in 0 until read) {
                                val s = buf[i].toDouble()
                                sum += s * s
                            }
                            val rms = kotlin.math.sqrt(sum / read)
                            val normalized = (rms / 6500.0).toFloat().coerceIn(0.04f, 1f)
                            callback(normalized)
                        }
                    } else {
                        try {
                            Thread.sleep(20L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }, "JarvisLiveCallMicAnchor").apply {
                isDaemon = true
                start()
            }
            drainThread = thread
            return true
        }
    }

    /**
     * Used only on devices whose HAL cannot share the mic simultaneously: pauses the
     * anchor's active read after [SpeechRecognizer] has acquired `OP_RECORD_AUDIO`, and
     * immediately resumes it via [resumeHoldBeforeRecognizerStop] before the recognizer stops.
     */
    fun yieldIfRequiredAfterRecognizerReady() {
        if (!yieldWhileRecognizerActive) return
        synchronized(lock) {
            if (!isLive || pausedForRecognizerYield) return
            pausedForRecognizerYield = true
            runCatching { recorder?.stop() }
        }
    }

    fun resumeHoldBeforeRecognizerStop() {
        synchronized(lock) {
            if (!isLive) {
                ensureLive()
                return
            }
            if (pausedForRecognizerYield) {
                pausedForRecognizerYield = false
                runCatching { recorder?.startRecording() }
                stateMachine?.onAudioRecordStateChanged("RECORDING (LIVE CALL)", true)
            }
        }
    }

    fun stopLive(reason: String = "Microphone stopped") {
        synchronized(lock) {
            stopInternalLocked(notifyStateMachine = true, reason = reason)
        }
    }

    private fun stopInternalLocked(notifyStateMachine: Boolean, reason: String? = null) {
        isLive = false
        pausedForRecognizerYield = false
        val thread = drainThread
        drainThread = null
        runCatching { thread?.interrupt() }
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
}

/**
 * Continuous, call-like Android [SpeechRecognizer] engine backed by [LiveCallAudioAnchor].
 *
 * Why this exists:
 * Android's [SpeechRecognizer] times out after ~4-5 seconds of silence with
 * `ERROR_SPEECH_TIMEOUT` or `ERROR_NO_MATCH` and closes its internal audio input.
 * Without [LiveCallAudioAnchor] and in-place re-arming, Android's status-bar green
 * microphone indicator turns OFF for 2-3 seconds and then turns back ON in an endless
 * ON/OFF cycle.
 *
 * With [LiveCallAudioAnchor] + 20ms in-place re-arming:
 * 1. `OP_RECORD_AUDIO` stays continuously active like a live phone call while listening.
 * 2. Silence windows re-arm seamlessly on the main thread without closing the Flow or
 *    dropping the UI `listening` state.
 * 3. When Jarvis thinks/speaks in live mode, [pauseRecognitionKeepLiveAnchor] stops
 *    transcription (so Jarvis never hears himself) while keeping the live call mic
 *    anchor open so the OS mic indicator never blinks off and on.
 */
@SuppressLint("MissingPermission")
class AndroidSttEngine(
    private val context: Context,
    private val stateMachine: MicrophoneStateMachine? = null,
) : SttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    val liveAnchor = LiveCallAudioAnchor(stateMachine)

    @Volatile
    private var preferOnDevice: Boolean = true

    @Volatile
    private var omitExplicitLanguage: Boolean = false

    @Volatile
    private var activeRecognizerRef: SpeechRecognizer? = null

    @Volatile
    private var activeHardwareCounted: Boolean = false

    /**
     * Ensures the continuous live-call microphone anchor is holding `OP_RECORD_AUDIO`
     * so the status-bar microphone indicator never flickers off between turns.
     */
    fun ensureLiveCallAnchor(): Boolean = liveAnchor.ensureLive()

    /**
     * Stops active speech recognition before LLM generation / TTS playback while
     * optionally keeping the [LiveCallAudioAnchor] open so the microphone stays live
     * like a phone call across conversation turns.
     */
    fun pauseRecognitionKeepLiveAnchor(reason: String = "Paused recognizer for Jarvis reply") {
        liveAnchor.resumeHoldBeforeRecognizerStop()
        val cleanup = Runnable {
            val rec = activeRecognizerRef
            activeRecognizerRef = null
            if (rec != null) {
                runCatching { rec.stopListening() }
                runCatching { rec.cancel() }
                runCatching { rec.destroy() }
            }
            if (activeHardwareCounted) {
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

    /**
     * Immediately stops, cancels, and destroys any active [SpeechRecognizer] AND
     * releases the [LiveCallAudioAnchor] so the microphone is 100% closed.
     */
    fun forceDestroyActive(reason: String = "Force stop", releaseLiveAnchor: Boolean = true) {
        if (releaseLiveAnchor) {
            liveAnchor.stopLive(reason)
        } else {
            liveAnchor.resumeHoldBeforeRecognizerStop()
        }
        val cleanup = Runnable {
            val rec = activeRecognizerRef
            activeRecognizerRef = null
            if (rec != null) {
                runCatching { rec.stopListening() }
                runCatching { rec.cancel() }
                runCatching { rec.destroy() }
            }
            if (activeHardwareCounted) {
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

        // Start the continuous live-call audio anchor immediately so OP_RECORD_AUDIO
        // stays continuously active without ever cycling off and on between passes.
        liveAnchor.ensureLive()

        var recognizer: SpeechRecognizer? = null
        var closed = false
        var hardwareOpened = false
        var recognizerReady = false
        var usingOnDevice = false
        var lastPartialText = ""
        var fallbackStage = 0
        var consecutiveAudioErrors = 0
        var startRecognitionPass: () -> Unit = {}
        lateinit var makeListener: () -> RecognitionListener

        fun emit(event: SttEvent) {
            if (!closed) trySend(event)
        }

        liveAnchor.onRmsLevel = { level ->
            if (!closed && !recognizerReady) {
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

        fun buildIntent(): Intent {
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
                // Keep each recognition pass open longer so the recognizer rolls over
                // less frequently while still finalizing promptly after spoken phrases.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 12_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1_400L,
                )
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

        fun destroyRecognizer(releaseReason: String? = null, releaseHardwareCount: Boolean = true) {
            recognizerReady = false
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
            if (releaseHardwareCount && hardwareOpened) {
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

        val rearmRunnable = Runnable {
            if (!closed) {
                startRecognitionPass()
            }
        }

        fun scheduleContinuousRearm(delayMs: Long = 20L, recreateRecognizer: Boolean = false) {
            if (closed) return
            recognizerReady = false
            // Ensure the in-process AudioRecord anchor is actively holding OP_RECORD_AUDIO
            // before the recognizer resets so Android never sees 0 active mic sessions.
            liveAnchor.resumeHoldBeforeRecognizerStop()
            mainHandler.removeCallbacks(rearmRunnable)
            if (recreateRecognizer) {
                createRecognizer()
            }
            mainHandler.postDelayed(rearmRunnable, delayMs)
        }

        startRecognitionPass = recognition@{
            if (closed) return@recognition
            liveAnchor.resumeHoldBeforeRecognizerStop()
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
            lastPartialText = ""
            recognizerReady = false
            val started = runCatching { rec.startListening(buildIntent()) }.isSuccess
            if (started) {
                markHardwareOpened()
                if (liveAnchor.yieldWhileRecognizerActive) {
                    mainHandler.postDelayed({
                        if (!closed && recognizerReady) {
                            liveAnchor.yieldIfRequiredAfterRecognizerReady()
                        }
                    }, 120L)
                }
            } else {
                scheduleContinuousRearm(delayMs = 80L, recreateRecognizer = true)
            }
        }

        makeListener = {
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    recognizerReady = true
                    consecutiveAudioErrors = 0
                    markHardwareOpened()
                    stateMachine?.onListeningReady()
                    liveAnchor.yieldIfRequiredAfterRecognizerReady()
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.04f, 1f)
                    emit(SttEvent.Rms(normalized))
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    // Re-assert the in-process AudioRecord anchor BEFORE SpeechRecognizer
                    // finishes closing its internal audio input so OP_RECORD_AUDIO never drops.
                    liveAnchor.resumeHoldBeforeRecognizerStop()
                    recognizerReady = false
                    emit(SttEvent.End)
                }

                override fun onError(error: Int) {
                    if (closed) return
                    liveAnchor.resumeHoldBeforeRecognizerStop()
                    recognizerReady = false

                    // 1. Rescue words already spoken before a pause or silence cutoff.
                    val rescued = lastPartialText.trim()
                    if (rescued.length >= 2) {
                        lastPartialText = ""
                        stateMachine?.onUtteranceCapturedBeginProcessing(
                            stopReason = "Utterance rescued after silence",
                        )
                        destroyRecognizer("Utterance rescued", releaseHardwareCount = true)
                        emit(SttEvent.Final(rescued))
                        close()
                        return
                    }

                    // 2. Automatic fallback from on-device recognizer to standard recognizer
                    // if the offline language pack is missing on this phone.
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
                        scheduleContinuousRearm(delayMs = 20L, recreateRecognizer = true)
                        return
                    }

                    // 3. If the device's audio HAL reported ERROR_AUDIO while both
                    // LiveCallAudioAnchor and SpeechRecognizer were open, switch the anchor
                    // to relay-handover mode and immediately re-arm.
                    if (error == SpeechRecognizer.ERROR_AUDIO) {
                        consecutiveAudioErrors++
                        if (!liveAnchor.yieldWhileRecognizerActive) {
                            liveAnchor.yieldWhileRecognizerActive = true
                        }
                        if (consecutiveAudioErrors <= 6) {
                            scheduleContinuousRearm(delayMs = 40L, recreateRecognizer = true)
                            return
                        }
                    }

                    // 4. Keep the microphone continuously LIVE across silence windows and
                    // transient recognizer rollovers (just like staying live on a call).
                    if (isRecoverableSttError(error) ||
                        error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                    ) {
                        val needsRecreate = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_AUDIO
                        val rearmDelay = when (error) {
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_NO_MATCH,
                            -> 15L
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 90L
                            else -> 35L
                        }
                        scheduleContinuousRearm(
                            delayMs = rearmDelay,
                            recreateRecognizer = needsRecreate,
                        )
                        return
                    }

                    // 5. Unrecoverable error (e.g., permission revoked)
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
                    liveAnchor.resumeHoldBeforeRecognizerStop()
                    recognizerReady = false
                    val text = pickBestCandidate(results).ifBlank { lastPartialText.trim() }
                    lastPartialText = ""
                    if (text.isNotBlank()) {
                        stateMachine?.onUtteranceCapturedBeginProcessing(
                            stopReason = "Utterance completed",
                        )
                        destroyRecognizer("Utterance completed", releaseHardwareCount = true)
                        emit(SttEvent.Final(text))
                        close()
                    } else {
                        // Empty result during silence: keep the mic live and immediately re-arm!
                        scheduleContinuousRearm(delayMs = 15L, recreateRecognizer = false)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (closed) return
                    val text = pickBestCandidate(partialResults)
                    if (text.isNotBlank()) {
                        lastPartialText = text
                        emit(SttEvent.Partial(text))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
        }

        createRecognizer()
        startRecognitionPass()

        awaitClose {
            closed = true
            liveAnchor.onRmsLevel = null
            mainHandler.removeCallbacksAndMessages(null)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                destroyRecognizer(releaseHardwareCount = true)
            } else {
                mainHandler.post { destroyRecognizer(releaseHardwareCount = true) }
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
