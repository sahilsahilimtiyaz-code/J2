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

        // Reschedule tomorrow's daily briefing if this was a recurring morning briefing alarm
        val dailyHour = intent.getIntExtra(EXTRA_DAILY_HOUR, -1)
        val dailyMinute = intent.getIntExtra(EXTRA_DAILY_MINUTE, -1)
        if (dailyHour in 0..23 && dailyMinute in 0..59) {
            runCatching {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                if (alarmManager != null) {
                    val nextCal = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, dailyHour)
                        set(java.util.Calendar.MINUTE, dailyMinute)
                        set(java.util.Calendar.SECOND, 0)
                    }
                    val nextIntent = Intent(context, JarvisAlarmReceiver::class.java).apply {
                        putExtra(JarvisIncomingCallActivity.EXTRA_TITLE, title)
                        putExtra(JarvisIncomingCallActivity.EXTRA_MESSAGE, message)
                        putExtra(EXTRA_DAILY_HOUR, dailyHour)
                        putExtra(EXTRA_DAILY_MINUTE, dailyMinute)
                    }
                    val nextPending = PendingIntent.getBroadcast(
                        context,
                        9001,
                        nextIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    com.jarvis.assistant.tools.scheduleAlarmSafely(
                        alarmManager,
                        nextCal.timeInMillis,
                        nextPending,
                    )
                }
            }
        }

        // Try direct launch
        runCatching { context.startActivity(callIntent) }
    }

    companion object {
        const val CHANNEL_ID = "jarvis_priority_calls"
        const val NOTIFICATION_ID = 9002
        const val EXTRA_DAILY_HOUR = "extra_daily_hour"
        const val EXTRA_DAILY_MINUTE = "extra_daily_minute"
    }
}
