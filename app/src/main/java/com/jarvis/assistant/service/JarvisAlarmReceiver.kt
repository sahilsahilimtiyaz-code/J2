package com.jarvis.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R

class JarvisAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(JarvisIncomingCallActivity.EXTRA_TITLE)
            ?: "Priority Audio Link"
        val message = intent.getStringExtra(JarvisIncomingCallActivity.EXTRA_MESSAGE)
            ?: "Good morning, sir. Jarvis standing by."

        val callIntent = Intent(context, JarvisIncomingCallActivity::class.java).apply {
            putExtra(JarvisIncomingCallActivity.EXTRA_TITLE, title)
            putExtra(JarvisIncomingCallActivity.EXTRA_MESSAGE, message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Priority Calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming Jarvis audio calls and morning briefings"
                enableVibration(true)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_jarvis, "Answer", pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Try direct launch
        runCatching { context.startActivity(callIntent) }
    }

    companion object {
        const val CHANNEL_ID = "jarvis_priority_calls"
        const val NOTIFICATION_ID = 9002
    }
}
