package com.jarvis.assistant.service

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android [RecognitionService] implementation required by [JarvisVoiceInteractionService]
 * so Jarvis qualifies as a complete Default Digital Assistant under Android Settings.
 *
 * When a client or system voice-interaction session requests speech recognition through
 * this service, it delegates on the main thread to the device's underlying speech engine
 * and forwards partial/final transcripts and audio levels to [Callback].
 */
class JarvisRecognitionService : RecognitionService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRecognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        mainHandler.post {
            destroyActiveRecognizer()
            if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
                runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
                return@post
            }
            val recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(applicationContext)
            }.getOrNull()
            if (recognizer == null) {
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
                return@post
            }
            activeRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    runCatching { listener.readyForSpeech(params ?: Bundle()) }
                }

                override fun onBeginningOfSpeech() {
                    runCatching { listener.beginningOfSpeech() }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    runCatching { listener.rmsChanged(rmsdB) }
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    if (buffer != null) {
                        runCatching { listener.bufferReceived(buffer) }
                    }
                }

                override fun onEndOfSpeech() {
                    runCatching { listener.endOfSpeech() }
                }

                override fun onError(error: Int) {
                    runCatching { listener.error(error) }
                }

                override fun onResults(results: Bundle?) {
                    runCatching { listener.results(results ?: Bundle()) }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    runCatching { listener.partialResults(partialResults ?: Bundle()) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val requestIntent = (recognizerIntent?.let { Intent(it) } ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).apply {
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (!hasExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)) {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                }
            }
            runCatching { recognizer.startListening(requestIntent) }
                .onFailure { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) } }
        }
    }

    override fun onCancel(listener: Callback?) {
        mainHandler.post {
            runCatching { activeRecognizer?.cancel() }
            destroyActiveRecognizer()
        }
    }

    override fun onStopListening(listener: Callback?) {
        mainHandler.post {
            runCatching { activeRecognizer?.stopListening() }
        }
    }

    override fun onDestroy() {
        mainHandler.post { destroyActiveRecognizer() }
        super.onDestroy()
    }

    private fun destroyActiveRecognizer() {
        runCatching { activeRecognizer?.destroy() }
        activeRecognizer = null
    }
}
