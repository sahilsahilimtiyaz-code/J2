package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.core.JarvisCore
import com.jarvis.assistant.service.NotificationHelper

class JarvisApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        // Build the runtime once, so settings, memory and the voice pipeline are
        // shared by the screen, the workers and the listening service.
        runCatching { JarvisCore.get(this).warmUp() }
            .onFailure { android.util.Log.e("JarvisApplication", "Runtime start failed", it) }
    }
}
