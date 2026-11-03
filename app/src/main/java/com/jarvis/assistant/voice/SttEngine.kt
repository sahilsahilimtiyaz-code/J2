package com.jarvis.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
 * Deterministic single-utterance Android [SpeechRecognizer] engine.
 *
 * Lifecycle rules:
 * 1. Opens a single [SpeechRecognizer] session on the main thread when [listen] is collected.
 * 2. Reports hardware acquisition and release to [MicrophoneStateMachine].
 * 3. Immediately calls `stopListening()`, `cancel()`, and `destroy()` as soon as a final
 *    utterance ([SttEvent.Final]), timeout, or error occurs, then closes the flow.
 * 4. Never re-arms itself in an infinite background loop.
 */
@SuppressLint("MissingPermission")
class AndroidSttEngine(
    private val context: Context,
    private val stateMachine: MicrophoneStateMachine? = null,
) : SttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var preferOnDevice: Boolean = true

    @Volatile
    private var omitExplicitLanguage: Boolean = false

    @Volatile
    private var activeRecognizerRef: SpeechRecognizer? = null

    @Volatile
    private var activeHardwareCounted: Boolean = false

    /**
     * Immediately stops, cancels, and destroys any active [SpeechRecognizer] on the main thread.
     */
    fun forceDestroyActive(reason: String = "Force stop") {
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

        var recognizer: SpeechRecognizer? = null
        var closed = false
        var hardwareOpened = false
        var usingOnDevice = false
        var lastPartialText = ""
        var fallbackStage = 0
        var startRecognitionPass: () -> Unit = {}
        lateinit var makeListener: () -> RecognitionListener

        fun emit(event: SttEvent) {
            if (!closed) trySend(event)
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
                // Standard single-utterance silence endpointing so the microphone
                // closes promptly after the owner finishes speaking.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1_200L,
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

        fun destroyRecognizer(releaseReason: String? = null) {
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
            if (hardwareOpened) {
                hardwareOpened = false
                activeHardwareCounted = false
                stateMachine?.onRecognitionHardwareReleased(stopReason = releaseReason)
            }
        }

        fun createRecognizer(): SpeechRecognizer? {
            val existing = recognizer
            recognizer = null
            if (existing != null) {
                runCatching { existing.stopListening() }
                runCatching { existing.cancel() }
                runCatching { existing.destroy() }
            }
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

        startRecognitionPass = recognition@{
            if (closed) return@recognition
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
            val started = runCatching { rec.startListening(buildIntent()) }.isSuccess
            if (started) {
                markHardwareOpened()
            } else {
                stateMachine?.onRecognitionError(
                    errorMessage = "Speech recognition could not be started.",
                    stopReason = "startListening threw exception",
                )
                destroyRecognizer("Failed to start listening")
                emit(SttEvent.Error("Speech recognition could not be started."))
                close()
            }
        }

        makeListener = {
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    markHardwareOpened()
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.04f, 1f)
                    emit(SttEvent.Rms(normalized))
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    emit(SttEvent.Rms(0f))
                    emit(SttEvent.End)
                }

                override fun onError(error: Int) {
                    if (closed) return
                    emit(SttEvent.Rms(0f))

                    // Rescue words already transcribed before a pause or cutoff.
                    val rescued = lastPartialText.trim()
                    if (rescued.length >= 2) {
                        lastPartialText = ""
                        stateMachine?.onUtteranceCapturedBeginProcessing(
                            stopReason = "Utterance rescued after silence",
                        )
                        destroyRecognizer("Utterance rescued")
                        emit(SttEvent.Final(rescued))
                        close()
                        return
                    }

                    // One-time automatic fallback from on-device recognizer to standard
                    // recognizer if the offline language pack is missing on this phone.
                    val languageProblem =
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                            (usingOnDevice &&
                                (error == SpeechRecognizer.ERROR_CLIENT ||
                                    error == SpeechRecognizer.ERROR_SERVER))
                    if (languageProblem && fallbackStage < 1) {
                        fallbackStage++
                        if (usingOnDevice) {
                            preferOnDevice = false
                        } else {
                            omitExplicitLanguage = true
                        }
                        val nextRec = createRecognizer()
                        if (nextRec != null) {
                            startRecognitionPass()
                            return
                        }
                    }

                    val msg = sttErrorMessage(error)
                    val stopReason = when (error) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Recognition timeout (no speech)"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                        else -> "Recognition error ($error): $msg"
                    }
                    stateMachine?.onRecognitionError(errorMessage = msg, stopReason = stopReason)
                    destroyRecognizer(stopReason)
                    emit(SttEvent.Error(msg, error))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    if (closed) return
                    emit(SttEvent.Rms(0f))
                    val text = pickBestCandidate(results).ifBlank { lastPartialText.trim() }
                    lastPartialText = ""
                    if (text.isNotBlank()) {
                        stateMachine?.onUtteranceCapturedBeginProcessing(
                            stopReason = "Utterance completed",
                        )
                        destroyRecognizer("Utterance completed")
                        emit(SttEvent.Final(text))
                    } else {
                        val msg = sttErrorMessage(SpeechRecognizer.ERROR_NO_MATCH)
                        stateMachine?.onRecognitionError(
                            errorMessage = msg,
                            stopReason = "Empty recognition result",
                        )
                        destroyRecognizer("Empty recognition result")
                        emit(SttEvent.Error(msg, SpeechRecognizer.ERROR_NO_MATCH))
                    }
                    close()
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
            mainHandler.removeCallbacksAndMessages(null)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                destroyRecognizer()
            } else {
                mainHandler.post { destroyRecognizer() }
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
