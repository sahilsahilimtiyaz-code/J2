package com.jarvis.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.components.ReactorGlyph
import com.jarvis.assistant.ui.theme.JarvisPalette

/**
 * Full-screen state after the owner flips the master power switch off.
 * Nothing runs in the background while this is showing.
 */
@Composable
fun PoweredOffScreen(onPowerOn: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "offline_breath")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offline_pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF02060C),
                        JarvisPalette.Void,
                        Color(0xFF010409),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        JarvisPalette.Cyan.copy(alpha = 0.10f * pulse),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height * 0.38f),
                    radius = size.minDimension * 0.55f,
                ),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width / 2f, size.height * 0.38f),
            )
            drawCircle(
                color = JarvisPalette.Cyan.copy(alpha = 0.16f * pulse),
                radius = size.minDimension * 0.18f,
                center = Offset(size.width / 2f, size.height * 0.38f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                JarvisPalette.Panel.copy(alpha = 0.95f),
                                Color(0xFF030B13),
                            ),
                        ),
                    )
                    .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.35f * pulse), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ReactorGlyph(
                    modifier = Modifier.size(54.dp),
                    color = JarvisPalette.CyanDim.copy(alpha = 0.55f + 0.25f * pulse),
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                text = "J.A.R.V.I.S.",
                style = MaterialTheme.typography.titleLarge,
                color = JarvisPalette.Ice,
                letterSpacing = 7.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "OFFLINE",
                style = MaterialTheme.typography.labelSmall,
                color = JarvisPalette.CyanBright.copy(alpha = 0.75f),
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = "All systems are powered down. The microphone, services, speech and background automation are completely stopped.",
                style = MaterialTheme.typography.bodyMedium,
                color = JarvisPalette.TextDim,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(30.dp))
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPowerOn),
                accent = JarvisPalette.CyanBright,
                corner = 20.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 18.dp,
                    vertical = 16.dp,
                ),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        HudIcon(
                            glyph = HudGlyph.BOLT,
                            color = JarvisPalette.CyanBright,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "POWER ON J.A.R.V.I.S.",
                            style = MaterialTheme.typography.labelLarge,
                            color = JarvisPalette.Ice,
                            letterSpacing = 2.sp,
                        )
                    }
                }
            }
        }
    }
}
