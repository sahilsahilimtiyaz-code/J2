package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R

/** Channels and persistent notifications for listening and multi-step screen control. */
object NotificationHelper {

    const val CHANNEL_VOICE = "jarvis_voice"
    const val CHANNEL_ALERTS = "jarvis_alerts"
    const val CHANNEL_SCREEN_CONTROL = "jarvis_screen_control"
    const val LISTENING_NOTIFICATION_ID = 4101
    const val SCREEN_CONTROL_NOTIFICATION_ID = 4102
    const val HEALTH_REMINDER_NOTIFICATION_ID = 4103

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    CHANNEL_VOICE,
                    NotificationManagerCompat.IMPORTANCE_LOW,
                )
                    .setName(context.getString(R.string.voice_channel_name))
                    .setDescription(context.getString(R.string.voice_channel_description))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(
                    CHANNEL_ALERTS,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                )
                    .setName(context.getString(R.string.alert_channel_name))
                    .setDescription(context.getString(R.string.alert_channel_description))
                    .build(),
                NotificationChannelCompat.Builder(
                    CHANNEL_SCREEN_CONTROL,
                    NotificationManagerCompat.IMPORTANCE_HIGH,
                )
                    .setName(context.getString(R.string.screen_control_channel_name))
                    .setDescription(context.getString(R.string.screen_control_channel_description))
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    fun listeningNotification(
        context: Context,
        serviceActive: Boolean,
        detail: String = "",
    ): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, JarvisListeningService::class.java)
                .setAction(JarvisListeningService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = when {
            detail.isNotBlank() -> detail
            serviceActive -> context.getString(R.string.listening_text)
            else -> context.getString(R.string.listening_text_wake_free)
        }

        return NotificationCompat.Builder(context, CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(context.getString(R.string.listening_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.action_stop_listening), stop)
            .build()
    }

    fun showScreenControlNotification(
        context: Context,
        step: Int,
        maxSteps: Int,
        detail: String,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val openApp = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(context, ScreenControlStopReceiver::class.java).apply {
            action = ScreenControlStopReceiver.ACTION_STOP_SCREEN_CONTROL
        }
        val stopPending = PendingIntent.getBroadcast(
            context,
            11,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = "Step $step/$maxSteps · $detail"
        val notification = NotificationCompat.Builder(context, CHANNEL_SCREEN_CONTROL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(context.getString(R.string.screen_control_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.action_stop_screen_control), stopPending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(SCREEN_CONTROL_NOTIFICATION_ID, notification)
        }
    }

    fun cancelScreenControlNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(SCREEN_CONTROL_NOTIFICATION_ID)
        }
    }

    fun showHealthReminderNotification(
        context: Context,
        categoryLabel: String,
        message: String,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val openApp = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val breakIntent = Intent(context, HealthBreakActionReceiver::class.java).apply {
            action = ScreenUsageHealthService.ACTION_RESET_BREAK
        }
        val breakPending = PendingIntent.getBroadcast(
            context,
            21,
            breakIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snoozeIntent = Intent(context, HealthBreakActionReceiver::class.java).apply {
            action = ScreenUsageHealthService.ACTION_SNOOZE_REMINDER
        }
        val snoozePending = PendingIntent.getBroadcast(
            context,
            22,
            snoozeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis Wellbeing · $categoryLabel")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp)
            .addAction(0, "Took a break", breakPending)
            .addAction(0, "Snooze 15m", snoozePending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(HEALTH_REMINDER_NOTIFICATION_ID, notification)
        }
    }

    fun cancelHealthReminderNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(HEALTH_REMINDER_NOTIFICATION_ID)
        }
    }

    fun alert(context: Context, title: String, text: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val openApp = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify((System.currentTimeMillis() % 100000).toInt(), notification)
        }
    }
}

/** Android 13+ needs the runtime notification permission to show the service card. */
fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
