package com.jarvis.assistant.service

import android.content.Intent
import android.speech.RecognitionService

/**
 * Android RecognitionService stub for Jarvis voice interaction service manifest
 * requirements. Real speech recognition is handled through [AndroidSttEngine]
 * and [WhisperSttEngine].
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Recognition requests through this service are passed through
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
