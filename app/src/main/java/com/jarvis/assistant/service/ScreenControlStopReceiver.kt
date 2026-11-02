package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.core.JarvisCore

/**
 * Receives the Stop button tap from the persistent "Jarvis is controlling the screen"
 * notification and terminates screen automation immediately (<100 ms).
 */
class ScreenControlStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP_SCREEN_CONTROL) {
            JarvisCore.get(context).stopScreenAutomation()
            NotificationHelper.cancelScreenControlNotification(context)
        }
    }

    companion object {
        const val ACTION_STOP_SCREEN_CONTROL = "com.jarvis.assistant.action.STOP_SCREEN_CONTROL"
    }
}
