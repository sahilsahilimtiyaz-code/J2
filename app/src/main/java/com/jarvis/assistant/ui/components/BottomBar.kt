package com.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.JarvisTab

private val Im3HudCyan = Color(0xFF3BF5D6)
private val Im3HudMid = Color(0xFF19A895)
private val Im3HudDark = Color(0xFF041D1E)
private val Im3HudVoid = Color(0xFF010B0C)

/**
 * Chamfered Iron Man 3 J.A.R.V.I.S. Second Screen Experience Bottom HUD Bar
 * matching `frame_16.png` – `frame_28.png` of the trailer:
 * - Chamfered dark-teal sci-fi wireframe border with outer and inner 45° bevels
 * - Central circular medallion with glowing capsule microphone & dual soundwaves `((🎙))`
 * - 4 circular HUD medallions separated by faint vertical divider ticks (Home/Clock, Tools/Nodes,
 *   Memory/Radar, Settings/Mark 42 Helmet).
 */
@Composable
fun JarvisBottomBar(
    selected: JarvisTab,
    onSelect: (JarvisTab) -> Unit,
    listening: Boolean,
    busy: Boolean,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Chamfered sci-fi HUD bar background + top wireframe border + vertical dividers
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val outerBevel = 12.dp.toPx()
            val innerBevel = 10.dp.toPx()
            val wingInnerX = 44.dp.toPx()
            val topY = 4.dp.toPx()
            val dipY = 13.dp.toPx()

            val fillPath = Path().apply {
                moveTo(0f, dipY)
                lineTo(outerBevel, topY)
                lineTo(cx - wingInnerX, topY)
                lineTo(cx - wingInnerX + innerBevel, dipY)
                lineTo(cx + wingInnerX - innerBevel, dipY)
                lineTo(cx + wingInnerX, topY)
                lineTo(w - outerBevel, topY)
                lineTo(w, dipY)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Im3HudDark.copy(alpha = 0.94f),
                        Im3HudVoid.copy(alpha = 0.98f),
                    ),
                ),
            )

            // Chamfered top cyan wireframe border (frame_16.png – frame_28.png)
            val borderPath = Path().apply {
                moveTo(0f, dipY)
                lineTo(outerBevel, topY)
                lineTo(cx - wingInnerX, topY)
                lineTo(cx - wingInnerX + innerBevel, dipY)
                moveTo(cx + wingInnerX - innerBevel, dipY)
                lineTo(cx + wingInnerX, topY)
                lineTo(w - outerBevel, topY)
                lineTo(w, dipY)
            }
            drawPath(
                path = borderPath,
                color = Im3HudCyan.copy(alpha = 0.65f),
                style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Subtle vertical divider hairlines between the 4 HUD slots
            val leftDividerX = (cx - wingInnerX) * 0.52f
            val rightDividerX = w - leftDividerX
            for (divX in floatArrayOf(leftDividerX, cx - wingInnerX - 2.dp.toPx(), cx + wingInnerX + 2.dp.toPx(), rightDividerX)) {
                drawLine(
                    color = Im3HudMid.copy(alpha = 0.25f),
                    start = Offset(divX, topY + 12.dp.toPx()),
                    end = Offset(divX, h - 10.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Im3HudNavSlot(
                slotIndex = 0,
                label = "Home",
                selected = selected == JarvisTab.HOME,
                onClick = { onSelect(JarvisTab.HOME) },
                modifier = Modifier.weight(1f),
            )
            Im3HudNavSlot(
                slotIndex = 1,
                label = "Tools",
                selected = selected == JarvisTab.TOOLS,
                onClick = { onSelect(JarvisTab.TOOLS) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(78.dp))
            Im3HudNavSlot(
                slotIndex = 2,
                label = "Memory",
                selected = selected == JarvisTab.MEMORY,
                onClick = { onSelect(JarvisTab.MEMORY) },
                modifier = Modifier.weight(1f),
            )
            Im3HudNavSlot(
                slotIndex = 3,
                label = "Settings",
                selected = selected == JarvisTab.SETTINGS,
                onClick = { onSelect(JarvisTab.SETTINGS) },
                modifier = Modifier.weight(1f),
            )
        }

        // Central Circular Medallion with Capsule Microphone & Dual Soundwaves ((🎙))
        // Exactly matching the center bottom medallion in frame_16.png – frame_28.png!
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(68.dp),
            contentAlignment = Alignment.Center,
        ) {
            PulseRings(
                active = listening,
                modifier = Modifier.size(76.dp),
                color = if (listening) Im3HudCyan else Color.Transparent,
            )
            val pulseScale by animateFloatAsState(
                targetValue = if (listening || busy) 1.05f else 1f,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                label = "im3_mic_scale",
            )
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                if (listening) Im3HudMid.copy(alpha = 0.42f) else Im3HudDark.copy(alpha = 0.96f),
                                Im3HudVoid,
                            ),
                        ),
                    )
                    .border(
                        width = 1.6.dp,
                        color = if (listening || busy) Im3HudCyan else Im3HudCyan.copy(alpha = 0.68f),
                        shape = CircleShape,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onMic,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(40.dp)) {
                    val c = Offset(size.width * 0.5f, size.height * 0.5f)
                    val s = size.minDimension
                    val tint = if (listening) Color(0xFFD6FFF9) else Im3HudCyan.copy(alpha = 0.85f)
                    val strokeW = 1.6.dp.toPx()

                    // Inner ring inside medallion
                    drawCircle(
                        color = Im3HudMid.copy(alpha = 0.28f),
                        radius = s * 0.46f,
                        center = c,
                        style = Stroke(width = 1.dp.toPx()),
                    )

                    // Capsule microphone pill
                    val capW = s * 0.17f
                    val capH = s * 0.34f
                    val capTop = c.y - capH * 0.65f
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(c.x - capW * 0.5f, capTop),
                        size = Size(capW, capH),
                        cornerRadius = CornerRadius(capW * 0.5f, capW * 0.5f),
                        style = Stroke(width = strokeW),
                    )

                    // U-cradle + stem + base
                    val cradleR = capW * 0.85f
                    val cradleY = capTop + capH * 0.62f
                    drawArc(
                        color = tint,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(c.x - cradleR, cradleY - cradleR),
                        size = Size(cradleR * 2f, cradleR * 2f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round),
                    )
                    val stemTop = cradleY + cradleR
                    val stemBottom = stemTop + s * 0.11f
                    drawLine(
                        color = tint,
                        start = Offset(c.x, stemTop),
                        end = Offset(c.x, stemBottom),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = tint,
                        start = Offset(c.x - cradleR * 0.68f, stemBottom),
                        end = Offset(c.x + cradleR * 0.68f, stemBottom),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )

                    // Dual soundwave parentheses (( )) on left and right
                    for (ringIdx in 1..2) {
                        val arcR = cradleR * (1.38f + ringIdx * 0.50f)
                        val arcAlpha = if (listening) 0.95f - ringIdx * 0.18f else 0.72f - ringIdx * 0.18f
                        val topLeft = Offset(c.x - arcR, cradleY - capH * 0.15f - arcR)
                        val arcSize = Size(arcR * 2f, arcR * 2f)
                        drawArc(
                            color = tint.copy(alpha = arcAlpha),
                            startAngle = -38f,
                            sweepAngle = 76f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeW * 0.85f, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = tint.copy(alpha = arcAlpha),
                            startAngle = 142f,
                            sweepAngle = 76f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeW * 0.85f, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Im3HudNavSlot(
    slotIndex: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) Im3HudCyan else Im3HudMid.copy(alpha = 0.75f),
        animationSpec = tween(durationMillis = 220),
        label = "im3_nav_tint",
    )
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Canvas(modifier = Modifier.size(34.dp)) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            val r = size.minDimension * 0.44f
            val strokeW = 1.4.dp.toPx()

            when (slotIndex) {
                0 -> {
                    // Slot 0: Circular Clock Medallion (matching far-left icon in frame_22.png)
                    drawCircle(
                        color = tint.copy(alpha = 0.75f),
                        radius = r,
                        center = c,
                        style = Stroke(width = strokeW),
                    )
                    drawArc(
                        color = tint,
                        startAngle = -75f,
                        sweepAngle = 115f,
                        useCenter = false,
                        topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f),
                        size = Size(r * 1.56f, r * 1.56f),
                        style = Stroke(width = strokeW * 0.85f, cap = StrokeCap.Round),
                    )
                    drawLine(
                        color = tint,
                        start = c,
                        end = Offset(c.x - r * 0.22f, c.y - r * 0.52f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = tint,
                        start = c,
                        end = Offset(c.x + r * 0.52f, c.y + r * 0.18f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                }
                1 -> {
                    // Slot 1: 3-Node Share / Network Graph (< icon matching second icon in frame_22.png)
                    val leftN = Offset(c.x - r * 0.42f, c.y + r * 0.05f)
                    val topN = Offset(c.x + r * 0.34f, c.y - r * 0.48f)
                    val botN = Offset(c.x + r * 0.34f, c.y + r * 0.54f)
                    val nr = r * 0.22f
                    drawLine(color = tint, start = leftN, end = topN, strokeWidth = strokeW)
                    drawLine(color = tint, start = leftN, end = botN, strokeWidth = strokeW)
                    for (pt in listOf(leftN, topN, botN)) {
                        drawCircle(color = Im3HudDark, radius = nr, center = pt)
                        drawCircle(color = tint, radius = nr, center = pt, style = Stroke(width = strokeW))
                    }
                }
                2 -> {
                    // Slot 2: Radar / Optical Disc Sweep Medallion (matching fourth icon in frame_22.png)
                    drawCircle(
                        color = tint.copy(alpha = 0.78f),
                        radius = r,
                        center = c,
                        style = Stroke(width = strokeW),
                    )
                    drawCircle(
                        color = tint,
                        radius = r * 0.26f,
                        center = c,
                        style = Stroke(width = strokeW),
                    )
                    drawLine(
                        color = tint.copy(alpha = 0.65f),
                        start = Offset(c.x - r * 0.65f, c.y - r * 0.65f),
                        end = Offset(c.x - r * 0.20f, c.y - r * 0.20f),
                        strokeWidth = strokeW,
                    )
                    drawLine(
                        color = tint.copy(alpha = 0.65f),
                        start = Offset(c.x + r * 0.20f, c.y + r * 0.20f),
                        end = Offset(c.x + r * 0.65f, c.y + r * 0.65f),
                        strokeWidth = strokeW,
                    )
                }
                else -> {
                    // Slot 3: Iron Man Helmet Faceplate Medallion (matching far-right icon in frame_22.png)
                    val hs = r * 0.88f
                    for (side in intArrayOf(-1, 1)) {
                        val outline = Path().apply {
                            moveTo(c.x, c.y - hs * 0.95f)
                            lineTo(c.x + hs * 0.65f * side, c.y - hs * 0.75f)
                            lineTo(c.x + hs * 0.80f * side, c.y - hs * 0.05f)
                            lineTo(c.x + hs * 0.60f * side, c.y + hs * 0.70f)
                            lineTo(c.x + hs * 0.28f * side, c.y + hs * 0.95f)
                            lineTo(c.x, c.y + hs * 0.95f)
                        }
                        drawPath(outline, color = tint, style = Stroke(width = strokeW, join = StrokeJoin.Round))
                        // Glowing eye slit
                        drawLine(
                            color = if (selected) Color.White else tint,
                            start = Offset(c.x + hs * 0.18f * side, c.y - hs * 0.08f),
                            end = Offset(c.x + hs * 0.58f * side, c.y - hs * 0.12f),
                            strokeWidth = strokeW * 1.35f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
