package com.jarvis.assistant.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
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

        // Listen action (opens MainActivity with start listening)
        val listenIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_START_LISTENING, true)
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
    }
}
