package com.jarvis.assistant.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.jarvis.assistant.core.JarvisCore

/**
 * Keeps Jarvis listening with the screen off.
 *
 * Android 14 requires a microphone service to declare its type and to be started
 * from a visible app, a notification action or a Quick Settings tile. This
 * service is therefore only ever started by [ListeningServiceController], and it
 * announces itself with an ongoing notification that includes Stop.
 */
class JarvisListeningService : Service() {

    private lateinit var core: JarvisCore
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        core = JarvisCore.get(this)
        if (!core.settings.value.masterEnabled) {
            stopSelf()
            return
        }
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.LISTENING_NOTIFICATION_ID,
                NotificationHelper.listeningNotification(this, serviceActive = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        }.getOrElse { error ->
            // Android 14 refuses a microphone service started without its
            // permission or from the background. Fail visibly instead of
            // crashing the process.
            android.util.Log.e("JarvisService", "Foreground start was refused", error)
            false
        }
        if (!promoted) {
            stopSelf()
            return
        }
        isRunning = true
        acquireWakeLock()
        core.onListeningServiceStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // This is the notification's explicit Stop action. Route it
                // through the same latch as the in-app mic button so neither the
                // UI nor the foreground service can silently keep the mic open.
                core.stopListening()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NotificationHelper.LISTENING_NOTIFICATION_ID,
                        NotificationHelper.listeningNotification(this, serviceActive = true),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                }
                core.onListeningServiceStarted()
            }
        }
        // START_NOT_STICKY on purpose: Android will not let the process start a
        // microphone foreground service from the background, so a restart would
        // fail silently and look like Jarvis had died.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        core.onListeningServiceStopped()
        isRunning = false
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::listening")
        runCatching {
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.jarvis.assistant.action.START_LISTENING"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP_LISTENING"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
