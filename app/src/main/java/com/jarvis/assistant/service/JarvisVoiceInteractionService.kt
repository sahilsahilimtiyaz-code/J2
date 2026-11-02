package com.jarvis.assistant.service

import android.service.voice.VoiceInteractionService

/**
 * System-level VoiceInteractionService.
 *
 * When selected as the default Digital Assistant app in Android Settings
 * (Settings -> Apps -> Default apps -> Digital assistant app), this service
 * gives Jarvis system priority, enables launching from the Side Key (power button)
 * long-press or corner-swipe assist gesture, and keeps the assistant alive.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
