package com.jarvis.assistant.accessibility

import android.content.Context
import android.graphics.Rect
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** A physical touch location on the display. */
data class TapPoint(val x: Int, val y: Int) {
    fun offset(dx: Int, dy: Int): TapPoint = TapPoint(x + dx, y + dy)
    fun describe(): String = "($x, $y)"
}

/** An element's screen rectangle in pixels (kept free of Android types so it is testable). */
data class ElementBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    companion object {
        fun from(rect: Rect): ElementBox = ElementBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}

/** Learned per-app pixel correction applied before a touch is dispatched. */
data class TapBias(val dx: Int = 0, val dy: Int = 0) {
    val isZero: Boolean get() = dx == 0 && dy == 0
    fun reversed(): TapBias = TapBias(-dx, -dy)
}

/**
 * Precision layer for touch actions.
 *
 * Two boring things break screen control: the coordinate lands a few pixels off the
 * real touch target (thin rows, floating action buttons, video thumbnails), or the
 * app consistently reports bounds that are shifted from what it actually hit-tests.
 * This engine fixes both:
 *
 * 1. [targetPoint] computes a centre inset away from the element edges and clamped
 *    inside the status/navigation-bar safe area.
 * 2. [correctionLadder] produces an ordered set of nearby points to retry when the
 *    first touch produced no visible change (centre, upper/lower thirds, left/right
 *    thirds, then small nudges). Retrying beats reporting failure.
 * 3. [recordVerifiedCorrection] remembers the delta between the requested point and
 *    the point that actually verified, per app, and applies it to later taps so the
 *    same miss is not repeated.
 */
object TapPrecisionEngine {

    /** A single app never learns more than this many pixels of correction. */
    const val MAX_LEARNED_BIAS_PX = 44

    /** Fraction of an element's width/height kept away from its edges. */
    private const val EDGE_INSET_FRACTION = 0.22f

    /** Keeps touches off the very top and bottom of the display. */
    private const val DISPLAY_EDGE_MARGIN_PX = 10

    private const val MAX_BIAS_SAMPLES = 8
    private const val PREFS_NAME = "jarvis_tap_precision"
    private const val PREFS_KEY = "learned_bias"

    private val samples = ConcurrentHashMap<String, MutableList<TapBias>>()
    @Volatile private var attachedContext: Context? = null
    @Volatile private var loadedFromDisk = false

    /** Binds persistence once per process; safe to call repeatedly. */
    fun attach(context: Context?) {
        if (context == null) return
        attachedContext = context.applicationContext
        if (loadedFromDisk) return
        loadedFromDisk = true
        runCatching {
            val raw = attachedContext
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREFS_KEY, null)
                .orEmpty()
            raw.split(';').forEach { entry ->
                val parts = entry.split(':')
                if (parts.size != 2) return@forEach
                val deltas = parts[1].split(',')
                if (deltas.size != 2) return@forEach
                val dx = deltas[0].toIntOrNull() ?: return@forEach
                val dy = deltas[1].toIntOrNull() ?: return@forEach
                samples.getOrPut(parts[0]) { mutableListOf() }.add(TapBias(dx, dy))
            }
        }
    }

    fun reset() {
        samples.clear()
        persist()
    }

    /**
     * Centre of [box] for a touch, inset off the edges and clamped inside the usable
     * display area so status bars and gesture zones cannot swallow the tap.
     */
    fun targetPoint(
        box: ElementBox,
        displayWidth: Int,
        displayHeight: Int,
        topInset: Int = 0,
        bottomInset: Int = 0,
    ): TapPoint {
        val x = box.centerX
        val y = box.centerY
        return TapPoint(
            x = clampX(x, displayWidth),
            y = clampY(y, displayHeight, topInset, bottomInset),
        )
    }

    /**
     * Ordered retry points for a touch that has not produced a visible change yet.
     *
     * With [box] the ladder stays inside the element, so a tap can never drift onto a
     * neighbouring control. Without a box (raw vision coordinates) it uses small
     * proportional nudges around the supplied point.
     */
    fun correctionLadder(
        target: TapPoint,
        box: ElementBox?,
        displayWidth: Int,
        displayHeight: Int,
        topInset: Int = 0,
        bottomInset: Int = 0,
    ): List<TapPoint> {
        val points = LinkedHashSet<TapPoint>()
        points += target

        if (box != null && box.width > 6 && box.height > 6) {
            val w = box.width
            val h = box.height
            val cx = box.centerX
            val cy = box.centerY
            points += TapPoint(cx, box.top + (h * 0.32f).roundToInt())
            points += TapPoint(cx, box.top + (h * 0.68f).roundToInt())
            points += TapPoint(box.left + (w * 0.30f).roundToInt(), cy)
            points += TapPoint(box.left + (w * 0.70f).roundToInt(), cy)
            // Small in-element nudges for chips and thin rows whose real hit area
            // sits a few pixels above or below the reported bounds.
            if (h >= 40) {
                points += TapPoint(cx, cy - (h * 0.12f).roundToInt())
                points += TapPoint(cx, cy + (h * 0.12f).roundToInt())
            }
            return points.map { clampToDisplay(it, displayWidth, displayHeight, topInset, bottomInset) }.distinct()
        }

        // Raw pixel / percent coordinates: nudge by a fraction of the display.
        val dx = (displayWidth * 0.035f).roundToInt().coerceAtLeast(8)
        val dy = (displayHeight * 0.02f).roundToInt().coerceAtLeast(8)
        points += target.offset(0, -dy)
        points += target.offset(0, dy)
        points += target.offset(-dx, 0)
        points += target.offset(dx, 0)
        points += target.offset(0, -(dy * 2))
        points += target.offset(0, dy * 2)
        return points.map { clampToDisplay(it, displayWidth, displayHeight, topInset, bottomInset) }.distinct()
    }

    /** Applies the app's learned correction to a point, clamped to the display. */
    fun applyBias(
        point: TapPoint,
        bias: TapBias,
        displayWidth: Int,
        displayHeight: Int,
        topInset: Int = 0,
        bottomInset: Int = 0,
    ): TapPoint {
        val adjusted = TapPoint(
            point.x + bias.dx.coerceIn(-MAX_LEARNED_BIAS_PX, MAX_LEARNED_BIAS_PX),
            point.y + bias.dy.coerceIn(-MAX_LEARNED_BIAS_PX, MAX_LEARNED_BIAS_PX),
        )
        return clampToDisplay(adjusted, displayWidth, displayHeight, topInset, bottomInset)
    }

    /**
     * A retry point verified a hit that the original point missed. Store the delta
     * (clamped, averaged over recent samples) as this app's learned bias.
     */
    fun recordVerifiedCorrection(packageName: String, requested: TapPoint, landed: TapPoint) {
        if (packageName.isBlank()) return
        val dx = (landed.x - requested.x).coerceIn(-MAX_LEARNED_BIAS_PX, MAX_LEARNED_BIAS_PX)
        val dy = (landed.y - requested.y).coerceIn(-MAX_LEARNED_BIAS_PX, MAX_LEARNED_BIAS_PX)
        if (dx == 0 && dy == 0) return
        val list = samples.getOrPut(packageName.lowercase(Locale.ROOT)) { mutableListOf() }
        list.add(TapBias(dx, dy))
        while (list.size > MAX_BIAS_SAMPLES) list.removeAt(0)
        persist()
    }

    /** The average correction learned for [packageName]. */
    fun learnedBias(
        packageName: String,
        displayWidth: Int = 0,
        displayHeight: Int = 0,
    ): TapBias {
        val list = samples[packageName.lowercase(Locale.ROOT)] ?: return TapBias()
        if (list.isEmpty()) return TapBias()
        val dx = list.sumOf { it.dx } / list.size
        val dy = list.sumOf { it.dy } / list.size
        val maxX = if (displayWidth > 0) (displayWidth * 0.06f).roundToInt() else MAX_LEARNED_BIAS_PX
        val maxY = if (displayHeight > 0) (displayHeight * 0.04f).roundToInt() else MAX_LEARNED_BIAS_PX
        return TapBias(dx.coerceIn(-maxX, maxX), dy.coerceIn(-maxY, maxY))
    }

    fun describe(bias: TapBias): String =
        if (bias.isZero) "no learned offset" else "learned offset ${bias.dx},${bias.dy}px"

    // ------------------------------------------------------------------ //

    private fun clampX(x: Int, displayWidth: Int): Int {
        if (displayWidth <= 0) return x
        return x.coerceIn(DISPLAY_EDGE_MARGIN_PX, (displayWidth - DISPLAY_EDGE_MARGIN_PX).coerceAtLeast(DISPLAY_EDGE_MARGIN_PX))
    }

    private fun clampY(y: Int, displayHeight: Int, topInset: Int, bottomInset: Int): Int {
        if (displayHeight <= 0) return y
        val lower = (topInset + DISPLAY_EDGE_MARGIN_PX).coerceAtLeast(DISPLAY_EDGE_MARGIN_PX)
        val upper = (displayHeight - bottomInset - DISPLAY_EDGE_MARGIN_PX).coerceAtLeast(lower)
        return y.coerceIn(lower, upper)
    }

    private fun clampToDisplay(
        point: TapPoint,
        displayWidth: Int,
        displayHeight: Int,
        topInset: Int,
        bottomInset: Int,
    ): TapPoint = TapPoint(
        x = clampX(point.x, displayWidth),
        y = clampY(point.y, displayHeight, topInset, bottomInset),
    )

    private fun persist() {
        val context = attachedContext ?: return
        runCatching {
            val encoded = samples.entries
                .filter { it.value.isNotEmpty() }
                .joinToString(";") { (pkg, list) ->
                    val bias = TapBias(list.sumOf { it.dx } / list.size, list.sumOf { it.dy } / list.size)
                    "$pkg:${bias.dx},${bias.dy}"
                }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_KEY, encoded)
                .apply()
        }
    }
}
