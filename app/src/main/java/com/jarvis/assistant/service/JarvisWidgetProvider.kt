package com.jarvis.assistant.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.RemoteViews
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R

class JarvisWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_jarvis_reactor)

        // Live battery and assistant status telemetry
        val batteryStatus = runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            if (pct >= 0) {
                if (charging) "ARC $pct%⚡" else "ARC $pct%"
            } else {
                "ARC READY"
            }
        }.getOrDefault("ARC READY")

        val stateLabel = if (JarvisListeningService.isRunning) "LISTENING" else "ONLINE"
        views.setTextViewText(R.id.widget_status, "$stateLabel • $batteryStatus")

        val nextReminder = runCatching {
            context.getSharedPreferences("jarvis_widget_prefs", Context.MODE_PRIVATE)
                .getString("next_reminder", null)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "No pending reminders • Tap MIC to speak"
        views.setTextViewText(R.id.widget_reminder, "Next: $nextReminder")

        // Listen action (opens MainActivity with start listening)
        val listenIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_START_LISTENING, true)
            putExtra("extra_summon_assistant", true)
        }
        val listenPending = PendingIntent.getActivity(
            context,
            widgetId * 10 + 1,
            listenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_btn_listen, listenPending)
        views.setOnClickPendingIntent(R.id.widget_reactor_icon, listenPending)

        // Diagnostics action (opens MainActivity with diagnostics command)
        val diagIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_RUN_DIAGNOSTICS, true)
            putExtra("extra_quick_prompt", "Run full system diagnostics and report telemetry")
        }
        val diagPending = PendingIntent.getActivity(
            context,
            widgetId * 10 + 3,
            diagIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_btn_diag, diagPending)

        // Stop action (broadcasts STOP intent)
        val stopIntent = Intent(context, ScreenControlStopReceiver::class.java).apply {
            action = "com.jarvis.assistant.action.STOP_SCREEN_CONTROL"
        }
        val stopPending = PendingIntent.getBroadcast(
            context,
            widgetId * 10 + 2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_btn_stop, stopPending)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    companion object {
        const val EXTRA_START_LISTENING = "com.jarvis.assistant.extra.START_LISTENING"
        const val EXTRA_RUN_DIAGNOSTICS = "com.jarvis.assistant.extra.RUN_DIAGNOSTICS"

        fun updateAll(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val manager = AppWidgetManager.getInstance(appContext)
                val component = ComponentName(appContext, JarvisWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    val provider = JarvisWidgetProvider()
                    provider.onUpdate(appContext, manager, ids)
                }
            }
        }
    }
}
