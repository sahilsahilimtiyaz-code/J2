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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private data class Particle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val alpha: Float,
)

private data class HexCell(
    val col: Int,
    val row: Int,
    val alphaWeight: Float,
    val filled: Boolean,
)

// Upper-right and lower-left hexagonal honeycomb clusters matching 00:02–00:14 of the Iron Man 3 trailer
private val UPPER_RIGHT_HEX_CLUSTER: List<HexCell> = listOf(
    HexCell(0, 0, 0.55f, false), HexCell(1, 0, 0.72f, true), HexCell(2, 0, 0.45f, false),
    HexCell(0, 1, 0.65f, false), HexCell(1, 1, 0.85f, false), HexCell(2, 1, 0.60f, true), HexCell(3, 1, 0.35f, false),
    HexCell(1, 2, 0.50f, false), HexCell(2, 2, 0.75f, false), HexCell(3, 2, 0.48f, false),
    HexCell(2, 3, 0.40f, false), HexCell(3, 3, 0.30f, false),
)

private val LOWER_LEFT_HEX_CLUSTER: List<HexCell> = listOf(
    HexCell(0, 0, 0.38f, false), HexCell(1, 0, 0.52f, false),
    HexCell(0, 1, 0.68f, true), HexCell(1, 1, 0.78f, false), HexCell(2, 1, 0.44f, false),
    HexCell(0, 2, 0.58f, false), HexCell(1, 2, 0.64f, false), HexCell(2, 2, 0.50f, true),
    HexCell(0, 3, 0.42f, false), HexCell(1, 3, 0.35f, false),
)

/**
 * Authentic Iron Man 3 J.A.R.V.I.S. Second Screen Experience Backdrop:
 * - Deep emerald-teal to obsidian radial/vertical atmosphere (`#010809` -> `#042322` -> `#010506`)
 * - Translucent hexagonal honeycomb grid clusters in the upper-right and lower-left quadrants (`00:02`–`00:14`)
 * - Subtle seafoam containment rings, drifting holographic micro-dust, and scanline sweep.
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
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    val seafoamBright = Color(0xFF3BF5D6)
    val seafoamMid = Color(0xFF17BD9E)

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF010A0B),
                        Color(0xFF031C1B),
                        Color(0xFF010607),
                    ),
                ),
            )

            // Central emerald-cyan atmospheric halo behind the J.A.R.V.I.S. orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        seafoamMid.copy(alpha = 0.22f * breathe),
                        seafoamMid.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.34f),
                    radius = size.width * 0.92f,
                ),
                radius = size.width * 0.92f,
                center = Offset(size.width * 0.5f, size.height * 0.34f),
            )

            // Upper-Right Hexagonal Honeycomb Grid Cluster (00:02–00:14)
            drawHexCluster(
                origin = Offset(size.width * 0.68f, size.height * 0.11f),
                hexRadius = 20.dp.toPx(),
                cells = UPPER_RIGHT_HEX_CLUSTER,
                color = seafoamBright,
                breathe = breathe,
            )

            // Lower-Left Hexagonal Honeycomb Grid Cluster (00:02–00:14)
            drawHexCluster(
                origin = Offset(size.width * 0.06f, size.height * 0.56f),
                hexRadius = 20.dp.toPx(),
                cells = LOWER_LEFT_HEX_CLUSTER,
                color = seafoamBright,
                breathe = breathe,
            )

            // Subtle instrument grid hairlines
            val stepX = 56.dp.toPx()
            val stepY = 56.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(
                    color = seafoamMid.copy(alpha = 0.025f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += stepX
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(
                    color = seafoamMid.copy(alpha = 0.022f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += stepY
            }

            // Drifting seafoam micro-dust
            particles.forEach { particle ->
                val travel = (particle.y - drift * particle.speed * 12f + 1f) % 1f
                val position = Offset(particle.x * size.width, travel * size.height)
                drawCircle(
                    color = seafoamBright.copy(alpha = particle.alpha * 0.85f),
                    radius = particle.radius,
                    center = position,
                )
            }

            // Single scanline sweep
            val sweepY = sweep * size.height
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        seafoamBright.copy(alpha = 0.045f),
                        Color.Transparent,
                    ),
                    startY = sweepY - 90f,
                    endY = sweepY + 90f,
                ),
                topLeft = Offset(0f, sweepY - 90f),
                size = Size(size.width, 180f),
            )
        }

        content()
    }
}

private fun DrawScope.drawHexCluster(
    origin: Offset,
    hexRadius: Float,
    cells: List<HexCell>,
    color: Color,
    breathe: Float,
) {
    val dx = hexRadius * 1.5f
    val dy = hexRadius * sqrt(3f)
    val strokeW = 1.0.dp.toPx()
    val path = Path()

    for (cell in cells) {
        val cx = origin.x + cell.col * dx
        val cy = origin.y + cell.row * dy + (cell.col % 2) * (dy * 0.5f)
        path.reset()
        for (v in 0 until 6) {
            val angle = (v * 60f) * (PI.toFloat() / 180f)
            val vx = cx + (hexRadius * 0.92f) * cos(angle)
            val vy = cy + (hexRadius * 0.92f) * sin(angle)
            if (v == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
        }
        path.close()
        val alpha = (cell.alphaWeight * 0.16f * breathe).coerceIn(0.03f, 0.22f)
        if (cell.filled) {
            drawPath(
                path = path,
                color = color.copy(alpha = alpha * 0.45f),
            )
        }
        drawPath(
            path = path,
            color = color.copy(alpha = alpha),
            style = Stroke(width = strokeW),
        )
    }
}
