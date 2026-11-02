package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlin.random.Random

private data class Particle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val alpha: Float,
)

/**
 * The screen behind everything: deep-space gradient, two reactor glows, a faint
 * instrument grid, slow drifting particles and a single scanline sweep. Drawn on
 * one Canvas so it costs one draw pass per frame.
 */
@Composable
fun JarvisBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val particles = remember {
        val random = Random(7)
        List(46) {
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.6f + random.nextFloat() * 1.6f,
                speed = 0.010f + random.nextFloat() * 0.030f,
                alpha = 0.10f + random.nextFloat() * 0.35f,
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "backdrop")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift",
    )
    val sweep by transition.animateFloat(
        initialValue = -0.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF03101D),
                        JarvisPalette.Void,
                        Color(0xFF01060D),
                    ),
                ),
            )

            // Reactor glow behind the orb area.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        JarvisPalette.Cyan.copy(alpha = 0.20f * breathe),
                        JarvisPalette.Cyan.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.30f),
                    radius = size.width * 0.95f,
                ),
                radius = size.width * 0.95f,
                center = Offset(size.width * 0.5f, size.height * 0.30f),
            )
            // Faint reactor containment rings echoing the reference board.
            val halo = Offset(size.width * 0.5f, size.height * 0.32f)
            listOf(0.34f, 0.45f, 0.56f).forEach { scale ->
                drawCircle(
                    color = JarvisPalette.CyanBright.copy(alpha = 0.045f * breathe),
                    radius = size.width * scale,
                    center = halo,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // Copper rim light on the left edge, as in the reference board.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        JarvisPalette.Amber.copy(alpha = 0.11f),
                        Color.Transparent,
                    ),
                    center = Offset(0f, size.height * 0.55f),
                    radius = size.width * 0.6f,
                ),
                radius = size.width * 0.6f,
                center = Offset(0f, size.height * 0.55f),
            )

            // Cool corner glow.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1E4E8C).copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.05f, size.height * 0.95f),
                    radius = size.width * 0.8f,
                ),
                radius = size.width * 0.8f,
                center = Offset(size.width * 0.05f, size.height * 0.95f),
            )

            // Instrument grid.
            val stepX = 54.dp.toPx()
            val stepY = 54.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(
                    color = JarvisPalette.Cyan.copy(alpha = 0.035f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += stepX
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(
                    color = JarvisPalette.Cyan.copy(alpha = 0.030f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += stepY
            }

            // Drifting dust.
            particles.forEach { particle ->
                val travel = (particle.y - drift * particle.speed * 12f + 1f) % 1f
                val position = Offset(particle.x * size.width, travel * size.height)
                drawCircle(
                    color = JarvisPalette.CyanBright.copy(alpha = particle.alpha),
                    radius = particle.radius,
                    center = position,
                )
            }

            // Single scanline sweep.
            val sweepY = sweep * size.height
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        JarvisPalette.Cyan.copy(alpha = 0.055f),
                        Color.Transparent,
                    ),
                    startY = sweepY - 90f,
                    endY = sweepY + 90f,
                ),
                topLeft = Offset(0f, sweepY - 90f),
                size = androidx.compose.ui.geometry.Size(size.width, 180f),
            )
        }

        content()
    }
}
