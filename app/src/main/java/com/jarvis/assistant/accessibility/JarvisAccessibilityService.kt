package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Lightweight, privacy-safe telemetry of accessibility events used strictly to
 * verify whether an automated click, scroll, or text entry actually took effect
 * on screen before advancing to the next automation step.
 */
data class AccessibilityEventTelemetry(
    val sequence: Long = 0L,
    val lastClickSeq: Long = 0L,
    val lastWindowChangeSeq: Long = 0L,
    val lastContentChangeSeq: Long = 0L,
    val lastScrollSeq: Long = 0L,
    val lastPackageName: String = "",
)

/**
 * The Tier 3 control surface: reads the visible screen and performs verified
 * taps, text entry, scrolling and system navigation on the owner's behalf.
 *
 * Android only allows this after the owner enables the service in accessibility
 * settings, including the "Allow restricted settings" step for a sideloaded app.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val sequenceCounter = AtomicLong(0L)

    @Volatile
    private var lastClickSeq: Long = 0L

    @Volatile
    private var lastWindowChangeSeq: Long = 0L

    @Volatile
    private var lastContentChangeSeq: Long = 0L

    @Volatile
    private var lastScrollSeq: Long = 0L

    @Volatile
    private var lastEventPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val seq = sequenceCounter.incrementAndGet()
        event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let {
            lastEventPackage = it
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                lastClickSeq = seq
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastWindowChangeSeq = seq
                windowStateListener?.invoke(
                    event.packageName?.toString().orEmpty(),
                    event.className?.toString().orEmpty(),
                )
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                lastContentChangeSeq = seq
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollSeq = seq
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun eventTelemetry(): AccessibilityEventTelemetry = AccessibilityEventTelemetry(
        sequence = sequenceCounter.get(),
        lastClickSeq = lastClickSeq,
        lastWindowChangeSeq = lastWindowChangeSeq,
        lastContentChangeSeq = lastContentChangeSeq,
        lastScrollSeq = lastScrollSeq,
        lastPackageName = lastEventPackage,
    )

    /**
     * Dispatches a physical coordinate tap gesture at ([x], [y]) when a custom UI
     * surface (such as a YouTube video card or player overlay) ignores standard
     * `ACTION_CLICK` node actions.
     */
    suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long = 65L): Boolean {
        if (x <= 0f || y <= 0f) return false
        return suspendCancellableCoroutine { cont ->
            val completed = runCatching {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!completed && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    /**
     * Dispatches a continuous press-and-hold touch gesture at ([x], [y]) for [durationMs].
     * Uses a 1px micro-path (`moveTo(x, y); lineTo(x + 1f, y)`) so Android's GestureManifold
     * emits `ACTION_DOWN` followed by steady `ACTION_MOVE` touch events for the entire duration
     * and only emits `ACTION_UP` once [durationMs] has elapsed. Essential for holding WhatsApp's
     * push-to-talk microphone button until the full voice recording finishes.
     */
    suspend fun dispatchHoldGesture(
        x: Float,
        y: Float,
        durationMs: Long,
    ): Boolean {
        if (x <= 0f || y <= 0f) return false
        val safeDuration = durationMs.coerceIn(500L, 59_000L)
        return suspendCancellableCoroutine { cont ->
            val completed = runCatching {
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x + 1f, y)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!completed && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    /**
     * Dispatches a physical touch swipe gesture across screen coordinates from ([startX], [startY])
     * to ([endX], [endY]). Injected directly into the window manager, this works reliably across
     * all applications (Compose LazyColumn, YouTube feeds, Flutter, WebViews, games, settings)
     * even when accessibility nodes reject standard ACTION_SCROLL actions.
     */
    suspend fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 280L,
    ): Boolean {
        if (startX <= 0f || startY <= 0f || endX <= 0f || endY <= 0f) return false
        return suspendCancellableCoroutine { cont ->
            val completed = runCatching {
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!completed && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    /**
     * Dispatches a human-like double tap at ([x], [y]) with two crisp strokes
     * separated by ~85ms.
     */
    suspend fun dispatchDoubleTapGesture(x: Float, y: Float): Boolean {
        if (x <= 0f || y <= 0f) return false
        val first = dispatchTapGesture(x, y, durationMs = 50L)
        if (!first) return false
        delay(85L)
        return dispatchTapGesture(x, y, durationMs = 50L)
    }

    /**
     * Dispatches a long press at ([x], [y]) holding contact for [durationMs] (default 650ms).
     */
    suspend fun dispatchLongPressGesture(x: Float, y: Float, durationMs: Long = 650L): Boolean {
        return dispatchHoldGesture(x, y, durationMs.coerceIn(400L, 2500L))
    }

    /**
     * Dispatches a continuous drag and drop gesture from ([startX], [startY]) to ([endX], [endY])
     * holding at the origin to pick up before moving to the destination.
     */
    suspend fun dispatchDragGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 480L,
    ): Boolean {
        if (startX <= 0f || startY <= 0f || endX <= 0f || endY <= 0f) return false
        return suspendCancellableCoroutine { cont ->
            val completed = runCatching {
                val holdPath = Path().apply {
                    moveTo(startX, startY)
                    lineTo(startX + 1f, startY)
                }
                val stroke1 = GestureDescription.StrokeDescription(holdPath, 0L, 350L, true)
                val movePath = Path().apply {
                    moveTo(startX + 1f, startY)
                    lineTo(endX, endY)
                }
                val stroke2 = stroke1.continueStroke(movePath, 0L, durationMs.coerceAtLeast(200L), false)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke1)
                    .addStroke(stroke2)
                    .build()
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!completed && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    /**
     * Dispatches a supported multi-touch pinch or zoom gesture using two simultaneous
     * StrokeDescription strokes centered around ([centerX], [centerY]).
     */
    suspend fun dispatchPinchZoomGesture(
        centerX: Float,
        centerY: Float,
        zoomIn: Boolean,
        spanPx: Float = 360f,
        durationMs: Long = 380L,
    ): Boolean {
        if (centerX <= 0f || centerY <= 0f) return false
        return suspendCancellableCoroutine { cont ->
            val completed = runCatching {
                val halfSpan = (spanPx / 2f).coerceAtLeast(40f)
                val path1 = Path()
                val path2 = Path()
                if (zoomIn) {
                    // Zoom in (spread fingers outwards)
                    path1.moveTo(centerX - 40f, centerY - 40f)
                    path1.lineTo(centerX - halfSpan, centerY - halfSpan)
                    path2.moveTo(centerX + 40f, centerY + 40f)
                    path2.lineTo(centerX + halfSpan, centerY + halfSpan)
                } else {
                    // Pinch out (pinch fingers inwards)
                    path1.moveTo(centerX - halfSpan, centerY - halfSpan)
                    path1.lineTo(centerX - 40f, centerY - 40f)
                    path2.moveTo(centerX + halfSpan, centerY + halfSpan)
                    path2.lineTo(centerX + 40f, centerY + 40f)
                }
                val stroke1 = GestureDescription.StrokeDescription(path1, 0L, durationMs)
                val stroke2 = GestureDescription.StrokeDescription(path2, 0L, durationMs)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke1)
                    .addStroke(stroke2)
                    .build()
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!completed && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    /**
     * Returns true if an input method / soft keyboard window is currently active and visible.
     */
    fun isKeyboardVisible(): Boolean = runCatching {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /**
     * Executes standard IME action on the focused node (Enter / Search / Send).
     */
    fun performImeAction(): Boolean = runCatching {
        val root = rootInActiveWindow ?: return@runCatching false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            false
        }
    }.getOrDefault(false)

    /**
     * Returns the physical screen dimensions (width to height) of the active device display,
     * defaulting to the Samsung Galaxy A54 profile (1080 x 2340) if metrics are unreadable.
     */
    fun getScreenDimensions(): Pair<Int, Int> {
        // currentWindowMetrics is the full display including the status and
        // navigation bars, which is exactly the coordinate space used by
        // AccessibilityNodeInfo bounds and dispatched gestures. displayMetrics
        // can exclude system bars and made edge taps land in the wrong place.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching {
                getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = resources.displayMetrics
        val width = dm?.widthPixels?.coerceAtLeast(1080) ?: 1080
        val height = dm?.heightPixels?.coerceAtLeast(1920) ?: 2340
        return Pair(width, height)
    }

    /**
     * Captures the real pixels currently on the screen (API 30+) so Jarvis can
     * literally SEE the display instead of guessing from the accessibility tree.
     *
     * The raw frame is rendered out of the hardware buffer into an in-memory
     * bitmap, downscaled to at most [maxWidth] px wide. Returns null when the
     * platform refuses (test surface, timing out, or a secure screen).
     */
    suspend fun captureScreenshotBitmap(maxWidth: Int = 1_000): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val raw = suspendCancellableCoroutine { cont ->
            val registered = runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    directExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            if (cont.isActive) {
                                cont.resume(screenshot)
                            } else {
                                screenshot.hardwareBuffer?.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.isSuccess
            if (!registered && cont.isActive) cont.resume(null)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val hardware = raw?.hardwareBuffer ?: return@runCatching null
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(
                        hardware,
                        raw.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB),
                    ) ?: return@runCatching null
                    val raster = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                        ?: return@runCatching null
                    val scaled = if (raster.width > maxWidth) {
                        val height = (raster.height.toFloat() / raster.width * maxWidth)
                            .toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(raster, maxWidth, height, true) ?: raster
                    } else {
                        raster
                    }
                    if (scaled !== raster) raster.recycle()
                    scaled
                } finally {
                    hardware.close()
                }
            }.getOrNull()
        }
    }

    /** Captures the screen and JPEG-encodes it for the vision model together with true dims. */
    suspend fun captureScreenshotBytes(maxWidth: Int = 1_000): Pair<ByteArray, Pair<Int, Int>>? =
        withContext(Dispatchers.IO) {
            val bitmap = runCatching { captureScreenshotBitmap(maxWidth) }.getOrNull()
                ?: return@withContext null
            val encoded = runCatching {
                ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 72, stream)
                    stream.toByteArray()
                }
            }.getOrNull()
            val dimensions = getScreenDimensions()
            runCatching { bitmap.recycle() }
            encoded?.takeIf { it.isNotEmpty() }?.let { it to dimensions }
        }

    /** 1x1 executor that runs screenshot callbacks inline on the calling thread. */
    private val directExecutor: Executor = Executor { it.run() }

    companion object {
        @Volatile
        private var instance: JarvisAccessibilityService? = null

        /**
         * Single observer hook for window-state changes (package to top-level
         * class name). JarvisCore sets it to feed the WhatsApp call assistant.
         * Invoked on the accessibility event thread, so implementations must be
         * cheap and hand real work to a coroutine.
         */
        @Volatile
        var windowStateListener: ((pkg: String, className: String) -> Unit)? = null

        fun serviceInstance(): JarvisAccessibilityService? = instance

        fun controller(): ScreenController? = instance?.let { ScreenController(it) }

        fun isConnected(): Boolean = instance != null

        /** True when this service is enabled in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, JarvisAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
