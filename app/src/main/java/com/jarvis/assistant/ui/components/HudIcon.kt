package com.jarvis.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The icon set. Drawing these in a 24-unit canvas keeps one consistent thin
 * stroke across the whole app and avoids pulling in the Material icon packs,
 * which this project does not depend on.
 */
enum class HudGlyph {
    HOME,
    SLIDERS,
    LOG,
    GEAR,
    SEARCH,
    MIC,
    SEND,
    STOP,
    CLOSE,
    TRASH,
    COPY,
    REFRESH,
    CHEVRON_RIGHT,
    CHECK,
    BOLT,
    WIFI,
    CHIP,
    BATTERY,
    TEMPERATURE,
    DISC,
    NOTE,
    TORCH,
    PLAY,
    PAUSE,
    ARROW_BACK,
    PLUS,
    GLOBE,
    BRAIN,
    WRENCH,
    ROCKET,
    SPARKLES,
    CAMERA,
    IMAGE,
    VIDEO,
    FOLDER,
    TERMINAL,
    SHIELD,
    LOCK,
    BELL,
    WAVE,
}

@Composable
fun HudIcon(
    glyph: HudGlyph,
    color: Color,
    modifier: Modifier = Modifier.size(22.dp),
    weight: Dp = 1.7.dp,
) {
    Canvas(modifier = modifier) {
        val unit = size.minDimension / 24f
        scale(scale = unit, pivot = Offset.Zero) {
            drawGlyph(glyph, color, weight.value.coerceAtLeast(1f))
        }
    }
}

private fun DrawScope.drawGlyph(glyph: HudGlyph, color: Color, weight: Float) {
    val stroke = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, Offset(x1, y1), Offset(x2, y2), weight, StrokeCap.Round)
    }

    fun poly(vararg points: Pair<Float, Float>, close: Boolean = false) {
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.first, point.second)
            else path.lineTo(point.first, point.second)
        }
        if (close) path.close()
        drawPath(path, color, style = stroke)
    }

    fun circle(cx: Float, cy: Float, radius: Float) {
        drawCircle(color, radius, Offset(cx, cy), style = stroke)
    }

    fun solid(cx: Float, cy: Float, radius: Float) {
        drawCircle(color, radius, Offset(cx, cy))
    }

    fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float) {
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2, radius * 2),
            style = stroke,
        )
    }

    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) {
        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(w, h),
            cornerRadius = CornerRadius(radius),
            style = stroke,
        )
    }

    when (glyph) {
        HudGlyph.HOME -> {
            poly(3.2f to 10.6f, 12f to 3.6f, 20.8f to 10.6f)
            poly(5.9f to 9.6f, 5.9f to 20.2f, 18.1f to 20.2f, 18.1f to 9.6f)
            poly(9.8f to 20.2f, 9.8f to 14.2f, 14.2f to 14.2f, 14.2f to 20.2f)
        }

        HudGlyph.SLIDERS -> {
            line(3f, 6.5f, 21f, 6.5f)
            line(3f, 12f, 21f, 12f)
            line(3f, 17.5f, 21f, 17.5f)
            circle(9f, 6.5f, 2.1f)
            circle(15.5f, 12f, 2.1f)
            circle(7.5f, 17.5f, 2.1f)
        }

        HudGlyph.LOG -> {
            solid(5f, 6.5f, 1.3f)
            solid(5f, 12f, 1.3f)
            solid(5f, 17.5f, 1.3f)
            line(9f, 6.5f, 20.5f, 6.5f)
            line(9f, 12f, 20.5f, 12f)
            line(9f, 17.5f, 20.5f, 17.5f)
        }

        HudGlyph.GEAR -> {
            circle(12f, 12f, 3.3f)
            repeat(8) { index ->
                val angle = (index * PI / 4).toFloat()
                line(
                    12f + cos(angle) * 5.3f,
                    12f + sin(angle) * 5.3f,
                    12f + cos(angle) * 7.6f,
                    12f + sin(angle) * 7.6f,
                )
            }
        }

        HudGlyph.SEARCH -> {
            circle(10.5f, 10.5f, 6.2f)
            line(15.2f, 15.2f, 20.5f, 20.5f)
        }

        HudGlyph.MIC -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(9.1f, 2.8f),
                size = Size(5.8f, 11.2f),
                cornerRadius = CornerRadius(2.9f),
                style = stroke,
            )
            arc(12f, 9.4f, 6.2f, 0f, 180f)
            line(12f, 15.6f, 12f, 19.6f)
            line(8.8f, 21f, 15.2f, 21f)
        }

        HudGlyph.SEND -> {
            poly(3f to 20.5f, 21f to 12f, 3f to 3.5f, 6.2f to 12f, close = true)
            line(6.2f, 12f, 12.5f, 12f)
        }

        HudGlyph.STOP -> box(7.2f, 7.2f, 9.6f, 9.6f, 2.4f)

        HudGlyph.CLOSE -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }

        HudGlyph.TRASH -> {
            line(4f, 7f, 20f, 7f)
            poly(9.4f to 7f, 9.4f to 4.6f, 14.6f to 4.6f, 14.6f to 7f)
            poly(6.2f to 7f, 7.3f to 20.4f, 16.7f to 20.4f, 17.8f to 7f)
            line(10.2f, 10.6f, 10.2f, 17f)
            line(13.8f, 10.6f, 13.8f, 17f)
        }

        HudGlyph.COPY -> {
            box(3.6f, 3.6f, 11f, 11f, 2.2f)
            box(9.4f, 9.4f, 11f, 11f, 2.2f)
        }

        HudGlyph.REFRESH -> {
            arc(12f, 12f, 7.6f, 35f, 280f)
            poly(18.4f to 2.6f, 18.4f to 7.6f, 13.4f to 7.6f)
        }

        HudGlyph.CHEVRON_RIGHT -> poly(9f to 4.8f, 16.2f to 12f, 9f to 19.2f)

        HudGlyph.CHECK -> poly(4.6f to 12.6f, 9.4f to 17.4f, 19.4f to 6.4f)

        HudGlyph.BOLT -> poly(
            13.2f to 2.4f,
            5.4f to 13.6f,
            11.2f to 13.6f,
            10.2f to 21.6f,
            18.6f to 10.4f,
            12.4f to 10.4f,
            close = true,
        )

        HudGlyph.WIFI -> {
            arc(12f, 18.2f, 5.4f, 214f, 112f)
            arc(12f, 18.2f, 10f, 214f, 112f)
            arc(12f, 18.2f, 14.6f, 214f, 112f)
            solid(12f, 18.4f, 1.5f)
        }

        HudGlyph.CHIP -> {
            box(5.6f, 5.6f, 12.8f, 12.8f, 2.2f)
            box(9.4f, 9.4f, 5.2f, 5.2f, 1.2f)
            repeat(3) { index ->
                val offset = 8f + index * 4f
                line(offset, 2.6f, offset, 5.6f)
                line(offset, 18.4f, offset, 21.4f)
                line(2.6f, offset, 5.6f, offset)
                line(18.4f, offset, 21.4f, offset)
            }
        }

        HudGlyph.BATTERY -> {
            box(2.4f, 7.4f, 16.4f, 9.2f, 2.4f)
            drawRoundRect(
                color = color,
                topLeft = Offset(20.2f, 10.2f),
                size = Size(2f, 3.6f),
                cornerRadius = CornerRadius(1f),
            )
            line(5.2f, 12f, 12.4f, 12f)
        }

        HudGlyph.TEMPERATURE -> {
            line(12f, 4.4f, 12f, 14.6f)
            solid(12f, 17.4f, 3.1f)
            circle(12f, 5.6f, 2.1f)
        }

        HudGlyph.DISC -> {
            circle(12f, 12f, 7.4f)
            circle(12f, 12f, 2.4f)
        }

        HudGlyph.NOTE -> {
            solid(6.8f, 17.6f, 2.4f)
            solid(17.2f, 15.4f, 2.4f)
            line(9.2f, 17.6f, 9.2f, 6.4f)
            line(19.6f, 15.4f, 19.6f, 4.2f)
            line(9.2f, 6.4f, 19.6f, 4.2f)
        }

        HudGlyph.TORCH -> {
            circle(12f, 9.6f, 5.1f)
            line(9.4f, 15.8f, 14.6f, 15.8f)
            line(10.2f, 18.4f, 13.8f, 18.4f)
            line(12f, 1.6f, 12f, 3.2f)
            line(4.4f, 9.6f, 2.8f, 9.6f)
            line(19.6f, 9.6f, 21.2f, 9.6f)
        }

        HudGlyph.PLAY -> {
            val path = Path().apply {
                moveTo(8.4f, 4.6f)
                lineTo(19.4f, 12f)
                lineTo(8.4f, 19.4f)
                close()
            }
            drawPath(path, color)
        }

        HudGlyph.PAUSE -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(7.4f, 4.8f),
                size = Size(3.4f, 14.4f),
                cornerRadius = CornerRadius(1.4f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(13.2f, 4.8f),
                size = Size(3.4f, 14.4f),
                cornerRadius = CornerRadius(1.4f),
            )
        }

        HudGlyph.ARROW_BACK -> {
            line(20f, 12f, 4.6f, 12f)
            poly(11f to 4.6f, 3.6f to 12f)
            poly(11f to 19.4f, 3.6f to 12f)
        }

        HudGlyph.PLUS -> {
            line(12f, 4.6f, 12f, 19.4f)
            line(4.6f, 12f, 19.4f, 12f)
        }

        HudGlyph.GLOBE -> {
            circle(12f, 12f, 8.6f)
            line(3.4f, 12f, 20.6f, 12f)
            line(12f, 3.4f, 12f, 20.6f)
            drawOval(
                color = color,
                topLeft = Offset(7.6f, 3.4f),
                size = Size(8.8f, 17.2f),
                style = stroke,
            )
        }

        HudGlyph.BRAIN -> {
            arc(9.4f, 9f, 4.2f, 120f, 220f)
            arc(14.6f, 9f, 4.2f, 200f, 220f)
            arc(8.8f, 15.2f, 4.0f, 30f, 210f)
            arc(15.2f, 15.2f, 4.0f, 300f, 210f)
            line(12f, 4.8f, 12f, 19.2f)
        }

        HudGlyph.WRENCH -> {
            arc(15.5f, 8.5f, 4.2f, -35f, 250f)
            line(12.2f, 11.8f, 5.2f, 18.8f)
            circle(5.2f, 18.8f, 1.6f)
        }

        HudGlyph.ROCKET -> {
            poly(12f to 3f, 16.5f to 9.5f, 15.5f to 16f, 8.5f to 16f, 7.5f to 9.5f, close = true)
            poly(7.5f to 13f, 4.5f to 16.5f, 8.5f to 16f)
            poly(16.5f to 13f, 19.5f to 16.5f, 15.5f to 16f)
            circle(12f, 10.2f, 1.8f)
            line(10.5f, 18.5f, 12f, 21f)
            line(13.5f, 18.5f, 12f, 21f)
        }

        HudGlyph.SPARKLES -> {
            box(4f, 4f, 16f, 16f, 3.2f)
            line(9.5f, 14.5f, 16.5f, 7.5f)
            poly(13f to 7.5f, 16.5f to 7.5f, 16.5f to 11f)
        }

        HudGlyph.CAMERA -> {
            box(3.4f, 6.8f, 17.2f, 12.6f, 2.8f)
            poly(8.4f to 6.8f, 10f to 4.4f, 14f to 4.4f, 15.6f to 6.8f)
            circle(12f, 13.1f, 3.4f)
        }

        HudGlyph.IMAGE -> {
            box(3.6f, 4.4f, 16.8f, 15.2f, 2.6f)
            circle(8.8f, 9.2f, 1.8f)
            poly(4.2f to 17.6f, 10.2f to 12.2f, 14.2f to 15.6f, 17.2f to 13f, 20f to 16.4f)
        }

        HudGlyph.VIDEO -> {
            box(3.4f, 5.4f, 13.2f, 13.2f, 2.6f)
            poly(16.6f to 9.6f, 21f to 7f, 21f to 17f, 16.6f to 14.4f)
            poly(8.4f to 9.4f, 13.2f to 12f, 8.4f to 14.6f, close = true)
        }

        HudGlyph.FOLDER -> {
            poly(
                3.4f to 6.2f,
                9.6f to 6.2f,
                11.6f to 8.4f,
                20.6f to 8.4f,
                20.6f to 18.8f,
                3.4f to 18.8f,
                close = true,
            )
        }

        HudGlyph.TERMINAL -> {
            box(3.4f, 4.6f, 17.2f, 14.8f, 2.6f)
            poly(7.4f to 9.4f, 10.6f to 12f, 7.4f to 14.6f)
            line(12.2f, 14.8f, 16.6f, 14.8f)
        }

        HudGlyph.SHIELD -> {
            poly(
                12f to 3.2f,
                19.4f to 6.2f,
                19.4f to 12.2f,
                12f to 20.8f,
                4.6f to 12.2f,
                4.6f to 6.2f,
                close = true,
            )
        }

        HudGlyph.LOCK -> {
            box(5.4f, 10.6f, 13.2f, 9.6f, 2.4f)
            arc(12f, 8.2f, 3.8f, 180f, 180f)
            line(8.2f, 8.2f, 8.2f, 10.6f)
            line(15.8f, 8.2f, 15.8f, 10.6f)
            solid(12f, 15.2f, 1.4f)
        }

        HudGlyph.BELL -> {
            arc(12f, 10f, 5.6f, 180f, 180f)
            line(6.4f, 10f, 5.2f, 16.4f)
            line(17.6f, 10f, 18.8f, 16.4f)
            line(4.4f, 16.4f, 19.6f, 16.4f)
            arc(12f, 16.8f, 2.2f, 0f, 180f)
        }

        HudGlyph.WAVE -> {
            line(4f, 12f, 4f, 12f)
            line(7.2f, 8.5f, 7.2f, 15.5f)
            line(10.4f, 5f, 10.4f, 19f)
            line(13.6f, 7.5f, 13.6f, 16.5f)
            line(16.8f, 9.5f, 16.8f, 14.5f)
            line(20f, 11f, 20f, 13f)
        }
    }
}
