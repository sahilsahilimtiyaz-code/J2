package com.jarvis.assistant.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Starts and stops the microphone foreground service from a visible app or a tile. */
object ListeningServiceController {

    fun start(context: Context): Boolean {
        val intent = Intent(context, JarvisListeningService::class.java)
            .setAction(JarvisListeningService.ACTION_START)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        }.getOrElse {
            android.util.Log.w("JarvisService", "Could not start listening service", it)
            false
        }
    }

    fun stop(context: Context) {
        runCatching {
            context.stopService(Intent(context, JarvisListeningService::class.java))
        }
    }

    fun component(context: Context): ComponentName =
        ComponentName(context, JarvisListeningService::class.java)
}
