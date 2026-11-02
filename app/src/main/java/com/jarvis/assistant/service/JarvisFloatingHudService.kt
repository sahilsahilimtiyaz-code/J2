package com.jarvis.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.core.JarvisCore
import kotlin.math.abs

/**
 * Floating Mini Arc Reactor Overlay Service ("Float Assist HUD").
 *
 * Allows J.A.R.V.I.S. to sit on top of any active screen (YouTube, Chrome, Games)
 * as a subtle glowing Arc Reactor orb.
 *
 * Gestures:
 * - Tap: Instant voice summon / start listening
 * - Double-Tap: Instant emergency Stop
 * - Drag: Move anywhere on the screen
 * - Long-Press: Launch full Jarvis app
 */
class JarvisFloatingHudService : Service() {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var isListeningState = false

    companion object {
        const val CHANNEL_ID = "jarvis_floating_hud"
        const val NOTIFICATION_ID = 4040
        const val ACTION_START = "com.jarvis.assistant.action.START_FLOATING_HUD"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP_FLOATING_HUD"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                return
            }
            val intent = Intent(context, JarvisFloatingHudService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisFloatingHudService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
        setupFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sizePx = (58 * resources.displayMetrics.density).toInt()
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - sizePx - 20)
            y = (resources.displayMetrics.heightPixels / 3)
        }

        val container = FrameLayout(this)
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(220, 10, 18, 30))
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(255, 68, 214, 255))
        }
        container.background = bgDrawable

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.rgb(68, 214, 255))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        container.addView(icon)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastTapTime = 0L

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.rawX - initialTouchX)
                    val diffY = abs(event.rawY - initialTouchY)
                    if (diffX < 15 && diffY < 15) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 350) {
                            // Double tap: emergency stop
                            JarvisCore.get(applicationContext).stopAll()
                            bgDrawable.setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(255, 255, 128, 113))
                        } else {
                            // Single tap: toggle voice listening
                            val core = JarvisCore.get(applicationContext)
                            core.startManualListening()
                            bgDrawable.setStroke((3 * resources.displayMetrics.density).toInt(), Color.argb(255, 88, 231, 174))
                        }
                        lastTapTime = now
                    } else {
                        // Drag released: smooth snap to nearest screen border (left or right)
                        val screenWidth = resources.displayMetrics.widthPixels
                        val snapX = if (params.x + sizePx / 2 < screenWidth / 2) 20 else (screenWidth - sizePx - 20)
                        params.x = snapX
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }
                else -> false
            }
        }

        container.setOnLongClickListener {
            val appIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(appIntent)
            true
        }

        floatView = container
        try {
            windowManager?.addView(container, params)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIFICATION_ID)
        if (floatView != null) {
            try {
                windowManager?.removeView(floatView)
            } catch (_: Exception) {}
            floatView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Floating HUD",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the floating Arc Reactor assistant bubble active on screen."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, JarvisFloatingHudService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("J.A.R.V.I.S. Floating HUD Active")
            .setContentText("Tap floating Arc Reactor orb to speak anytime.")
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close HUD", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
