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
 * One continuous Android speech-recognition session.
 *
 * The previous implementation alternated between [SpeechRecognizer] and a
 * short-lived AudioRecord "mic keeper". Every silence window therefore closed
 * one capture client and opened another, which made the OS microphone indicator
 * blink on/off even though the app still said it was listening. The keeper also
 * released the microphone while TTS was starting.
 *
 * This engine deliberately uses **one recognizer instance and one long-lived
 * flow**. A Final result no longer closes the flow: the recognizer is re-armed
 * immediately and the collector in JarvisCore keeps consuming finals. Listening
 * is owned and latched by JarvisCore, not by the recognizer's per-utterance
 * lifecycle, so generation and TTS never tear the microphone down. The only way
 * the capture stops is an explicit user stop or the foreground service ending.
 *
 * Android still ends individual recognition sessions after long silence. The
 * re-arm is immediate and uses the same instance; there is no keeper hand-off,
 * no 160 ms teardown delay, and no deliberate off period.
 */
@SuppressLint("MissingPermission")
class AndroidSttEngine(private val context: Context) : SttEngine {

    @Volatile
    private var preferOnDevice: Boolean = true

    @Volatile
    private var omitExplicitLanguage: Boolean = false

    override fun listen(locale: Locale): Flow<SttEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SttEvent.Error("Speech recognition is not available on this phone."))
            close()
            return@callbackFlow
        }

        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var closed = false
        var sessionActive = false
        var usingOnDevice = false
        var lastPartialText = ""
        var consecutiveFailures = 0
        var fallbackStage = 0
        var restartScheduled = false
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
                // Ask the recognizer to stay open through normal pauses. These are
                // hints, so the engine still re-arms immediately if the platform
                // ends a session early.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600_000L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    600_000L,
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

        fun destroyRecognizer() {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            sessionActive = false
        }

        fun rearm(delayMs: Long = 40L) {
            if (closed || restartScheduled) return
            restartScheduled = true
            mainHandler.postDelayed({
                restartScheduled = false
                if (!closed) startRecognitionPass()
            }, delayMs)
        }

        fun createRecognizer(): SpeechRecognizer? {
            destroyRecognizer()
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
            created.setRecognitionListener(makeListener())
            return created
        }

        startRecognitionPass = recognition@{
            if (closed || sessionActive) return@recognition
            val rec = recognizer ?: createRecognizer()
            if (rec == null) {
                emit(SttEvent.Error("Speech recognition could not be started."))
                close()
                return@recognition
            }
            lastPartialText = ""
            val started = runCatching { rec.startListening(buildIntent()) }.isSuccess
            if (started) {
                sessionActive = true
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 4) {
                    emit(SttEvent.Error("Speech recognition could not be started."))
                    close()
                } else {
                    rearm(250L * consecutiveFailures)
                }
            }
        }

        makeListener = {
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    sessionActive = true
                    consecutiveFailures = 0
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
                    sessionActive = false
                    emit(SttEvent.Rms(0f))

                    // Rescue words already transcribed before a pause or hiccup.
                    val rescued = lastPartialText.trim()
                    if (rescued.length >= 2) {
                        lastPartialText = ""
                        emit(SttEvent.Final(rescued))
                        rearm(40L)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        emit(SttEvent.Error("Microphone permission required.", error))
                        close()
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NO_MATCH
                    ) {
                        // Re-arm the same recognizer immediately. No keeper, no
                        // teardown, no deliberate microphone-off window.
                        rearm(40L)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        consecutiveFailures++
                        rearm((180L * consecutiveFailures).coerceAtMost(1_500L))
                        return
                    }

                    val languageProblem =
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                            (usingOnDevice &&
                                (error == SpeechRecognizer.ERROR_CLIENT ||
                                    error == SpeechRecognizer.ERROR_SERVER))
                    if (languageProblem && fallbackStage < 2) {
                        fallbackStage++
                        when {
                            usingOnDevice -> preferOnDevice = false
                            !omitExplicitLanguage -> omitExplicitLanguage = true
                            else -> {
                                rearm(250L)
                                return
                            }
                        }
                        destroyRecognizer()
                        createRecognizer()
                        rearm(180L)
                        return
                    }

                    if ((error == SpeechRecognizer.ERROR_NETWORK ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) &&
                        !usingOnDevice && canUseOnDevice() && fallbackStage == 0
                    ) {
                        fallbackStage = 1
                        preferOnDevice = true
                        destroyRecognizer()
                        createRecognizer()
                        rearm(180L)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                    ) {
                        consecutiveFailures++
                        if (consecutiveFailures >= 4) {
                            emit(
                                SttEvent.Error(
                                    "Speech recognition is temporarily unavailable. I will keep the microphone ready and retry.",
                                    error,
                                ),
                            )
                            consecutiveFailures = 0
                        }
                        rearm(1_000L)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_AUDIO ||
                        error == SpeechRecognizer.ERROR_CLIENT
                    ) {
                        consecutiveFailures++
                        if (consecutiveFailures <= 3) {
                            destroyRecognizer()
                            createRecognizer()
                            rearm((250L * consecutiveFailures).coerceAtMost(1_000L))
                        } else {
                            emit(SttEvent.Error(sttErrorMessage(error), error))
                            close()
                        }
                        return
                    }

                    consecutiveFailures++
                    if (consecutiveFailures <= 3) {
                        destroyRecognizer()
                        createRecognizer()
                        rearm(400L)
                    } else {
                        emit(SttEvent.Error(sttErrorMessage(error), error))
                        close()
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (closed) return
                    sessionActive = false
                    emit(SttEvent.Rms(0f))
                    val text = pickBestCandidate(results).ifBlank { lastPartialText.trim() }
                    lastPartialText = ""
                    if (text.isNotBlank()) {
                        emit(SttEvent.Final(text))
                    }
                    // Critical: the flow stays subscribed and the recognizer
                    // immediately re-arms. Listening is not ended by an utterance.
                    rearm(40L)
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
            destroyRecognizer()
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

private fun sttErrorMessage(code: Int): String = when (code) {
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
