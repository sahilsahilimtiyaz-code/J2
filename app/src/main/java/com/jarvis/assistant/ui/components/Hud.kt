package com.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Frosted navy card with a cyan hairline, the base surface of every screen. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    accent: Color = JarvisPalette.Cyan,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        JarvisPalette.Panel.copy(alpha = 0.94f),
                        JarvisPalette.Deep.copy(alpha = 0.88f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.40f), accent.copy(alpha = 0.05f)),
                ),
                shape = shape,
            )
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun HudSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = JarvisPalette.TextDim,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = 0.85f),
        letterSpacing = 2.4.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = true,
    size: Dp = 7.dp,
) {
    val transition = rememberInfiniteTransition(label = "status dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot alpha",
    )
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = if (pulsing) alpha else 0.9f), CircleShape),
    )
}

@Composable
fun HudPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = JarvisPalette.Cyan,
    filled: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = if (filled) 0.18f else 0.06f))
            .border(1.dp, color.copy(alpha = 0.38f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            letterSpacing = 0.8.sp,
            maxLines = 1,
        )
    }
}

/** Small circular glass button used for the gear and the quick panel handle. */
@Composable
fun HudIconButton(
    glyph: HudGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    tint: Color = JarvisPalette.CyanBright,
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(JarvisPalette.Panel.copy(alpha = 0.85f))
            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HudIcon(
            glyph = glyph,
            color = tint,
            modifier = Modifier
                .size(size * 0.52f)
                .semantics { this.contentDescription = contentDescription },
            weight = 1.6.dp,
        )
    }
}

/** Miniature Iron Man 3 J.A.R.V.I.S. Orb glyph used in headers across all screens. */
@Composable
fun ReactorGlyph(
    modifier: Modifier = Modifier,
    color: Color = JarvisPalette.CyanBright,
) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.35f),
                    JarvisPalette.Panel.copy(alpha = 0.90f),
                    JarvisPalette.Void,
                ),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )
        // Outer double-walled stippled rim
        drawCircle(
            color = color.copy(alpha = 0.85f),
            radius = r * 0.92f,
            center = c,
            style = Stroke(width = 1.4.dp.toPx()),
        )
        drawCircle(
            color = color.copy(alpha = 0.50f),
            radius = r * 0.76f,
            center = c,
            style = Stroke(width = 0.9.dp.toPx()),
        )
        // 12-arch festoon ring
        val archPath = Path()
        for (i in 0..72) {
            val theta = (i.toFloat() / 72f) * 2f * PI.toFloat()
            val ar = r * (0.42f + 0.08f * kotlin.math.abs(sin(6f * theta)))
            val x = c.x + ar * cos(theta)
            val y = c.y + ar * sin(theta)
            if (i == 0) archPath.moveTo(x, y) else archPath.lineTo(x, y)
        }
        drawPath(archPath, color = color.copy(alpha = 0.65f), style = Stroke(width = 0.8.dp.toPx()))
        // Central geodesic sphere limb + struts
        val sr = r * 0.31f
        drawCircle(
            color = JarvisPalette.Ice.copy(alpha = 0.92f),
            radius = sr,
            center = c,
            style = Stroke(width = 1.1.dp.toPx()),
        )
        for (k in 0 until 3) {
            val ang = k * (PI.toFloat() / 3f)
            drawLine(
                color = color.copy(alpha = 0.55f),
                start = Offset(c.x - sr * cos(ang), c.y - sr * sin(ang)),
                end = Offset(c.x + sr * cos(ang), c.y + sr * sin(ang)),
                strokeWidth = 0.7.dp.toPx(),
            )
        }
    }
}

/** Header used on every screen: reactor mark, name, status, trailing actions. */
@Composable
fun JarvisTopBar(
    title: String = "J.A.R.V.I.S.",
    status: String? = "ONLINE",
    statusColor: Color = JarvisPalette.Success,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            HudIconButton(
                glyph = HudGlyph.ARROW_BACK,
                contentDescription = "Back",
                onClick = onBack,
                size = 34.dp,
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(JarvisPalette.Panel.copy(alpha = 0.8f))
                    .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ReactorGlyph(modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                color = JarvisPalette.Ice,
                maxLines = 1,
            )
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = statusColor, pulsing = true)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor.copy(alpha = 0.9f),
                        letterSpacing = 1.6.sp,
                    )
                }
            }
        }

        trailing()
    }
}

/** Square action tile with a glowing icon well, as in the reference home screen. */
@Composable
fun ActionTile(
    glyph: HudGlyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = JarvisPalette.Cyan,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        JarvisPalette.Panel.copy(alpha = 0.95f),
                        JarvisPalette.Deep.copy(alpha = 0.9f),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.22f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.06f)),
                    ),
                )
                .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            HudIcon(
                glyph = glyph,
                color = accent,
                modifier = Modifier.size(18.dp),
                weight = 1.8.dp,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.Ice.copy(alpha = 0.9f),
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Label + value readout used in the status strip and the quick panel. */
@Composable
fun StatReadout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = JarvisPalette.CyanBright,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.TextDim,
            letterSpacing = 1.2.sp,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            fontFamily = com.jarvis.assistant.ui.theme.HudNumerals.fontFamily,
        )
    }
}

/** Thin energy bar with a soft glow, used for battery, memory and storage. */
@Composable
fun EnergyBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = JarvisPalette.Cyan,
    height: Dp = 4.dp,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "energy bar",
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(JarvisPalette.Line.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.55f), color),
                    ),
                ),
        )
    }
}

/** A tappable row of the tools and log lists. */
@Composable
fun HudRow(
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisPalette.Panel.copy(alpha = 0.72f))
            .border(1.dp, JarvisPalette.Line.copy(alpha = 0.55f), shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            leading()
            Spacer(Modifier.width(12.dp))
            content()
        },
    )
}

/** Colour helper for a status string coming from the tools registry. */
@Composable
fun statusColor(ready: Boolean): Color {
    val target = if (ready) JarvisPalette.Success else JarvisPalette.Amber
    val animated by animateColorAsState(targetValue = target, animationSpec = tween(400), label = "status")
    return animated
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall,
        color = JarvisPalette.Ice,
        fontWeight = FontWeight.Light,
        letterSpacing = 0.5.sp,
    )
}

/** Convenience: horizontal padding used by every screen body. */
val ScreenPadding: PaddingValues = PaddingValues(horizontal = 16.dp)

@Composable
fun GlowDivider(modifier: Modifier = Modifier, color: Color = JarvisPalette.Cyan) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.35f), Color.Transparent),
                ),
            ),
    )
}
