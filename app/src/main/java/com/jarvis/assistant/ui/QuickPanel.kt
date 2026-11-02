package com.jarvis.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.tools.DeviceStatus
import com.jarvis.assistant.tools.readDeviceStatus
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.components.ReactorGlyph
import com.jarvis.assistant.ui.components.VoiceWave
import com.jarvis.assistant.ui.theme.HudNumerals
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Screen 8 — Quick Panel matching the reference image:
 * - Slide-over right glassmorphic drawer leaving the glowing Arc Reactor peeking on the left
 * - Card 1: `QUICK ACCESS` with close `×` button and 5 action rows:
 *   `Camera Vision`, `Screenshot`, `YouTube`, `Summarize`, `Translate`
 * - Card 2: `SYSTEM` with close `×` button and 4 live readouts:
 *   `Network Connected`, `Battery 92%`, `Temperature 36°C`, `Performance Optimal`
 * - Card 3: `NOW PLAYING` with live cyan `VoiceWave` spectrum and `JARVIS Voice Active` status.
 */
@Composable
fun QuickPanelOverlay(
    visible: Boolean,
    state: JarvisUiState,
    onClose: () -> Unit,
    onSelectTab: (JarvisTab) -> Unit,
    onToggleTorch: () -> Unit,
    onToggleMedia: () -> Unit,
    onToggleConversationMode: () -> Unit = {},
    onToggleFloatingHud: () -> Unit = {},
    onRunQuickCommand: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<DeviceStatus?>(null) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            status = withContext(Dispatchers.IO) {
                runCatching { readDeviceStatus(context) }.getOrNull()
            }
            delay(4000)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(onClick = onClose),
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(320)) { it } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(250)) { it } + fadeOut(tween(180)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(306.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                JarvisPalette.Navy.copy(alpha = 0.96f),
                                JarvisPalette.Void.copy(alpha = 0.98f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                JarvisPalette.CyanBright.copy(alpha = 0.50f),
                                JarvisPalette.Cyan.copy(alpha = 0.18f),
                            ),
                        ),
                        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Card 1: QUICK ACCESS
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    corner = 20.dp,
                    accent = JarvisPalette.CyanBright,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HudIcon(
                            glyph = HudGlyph.WAVE,
                            color = JarvisPalette.CyanBright,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "QUICK ACCESS",
                            style = MaterialTheme.typography.labelMedium,
                            color = JarvisPalette.Ice,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        HudIcon(
                            glyph = HudGlyph.CLOSE,
                            color = JarvisPalette.TextDim,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = onClose),
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAccessRow(
                            glyph = HudGlyph.CAMERA,
                            label = "Camera Vision",
                            accent = JarvisPalette.CyanBright,
                            onClick = { onRunQuickCommand("Read and describe what is currently on my screen") },
                        )
                        QuickAccessRow(
                            glyph = HudGlyph.IMAGE,
                            label = "Screenshot",
                            accent = JarvisPalette.Cyan,
                            onClick = { onRunQuickCommand("Take a screenshot of the current screen") },
                        )
                        QuickAccessRow(
                            glyph = HudGlyph.PLAY,
                            label = "YouTube",
                            accent = JarvisPalette.Danger,
                            onClick = { onRunQuickCommand("Open YouTube") },
                        )
                        QuickAccessRow(
                            glyph = HudGlyph.LOG,
                            label = "Summarize",
                            accent = JarvisPalette.CyanBright,
                            onClick = { onRunQuickCommand("Read and summarize my recent notifications") },
                        )
                        QuickAccessRow(
                            glyph = HudGlyph.GLOBE,
                            label = "Translate",
                            accent = JarvisPalette.Cyan,
                            onClick = { onRunQuickCommand("Translate any foreign text visible on my screen into English") },
                        )
                        QuickAccessRow(
                            glyph = HudGlyph.SPARKLES,
                            label = "Macro Skills (${state.skills.size})",
                            accent = JarvisPalette.CyanBright,
                            onClick = { onSelectTab(JarvisTab.SKILLS) },
                        )
                        QuickAccessRow(
                            glyph = HudGlyph.BRAIN,
                            label = "AI Proposals",
                            accent = JarvisPalette.Amber,
                            onClick = { onSelectTab(JarvisTab.PROPOSALS) },
                        )
                        QuickAccessRow(
                            glyph = HudGlyph.SLIDERS,
                            label = "Action Log",
                            accent = JarvisPalette.Cyan,
                            onClick = { onSelectTab(JarvisTab.LOG) },
                        )
                    }
                }

                // Card 2: SYSTEM
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    corner = 20.dp,
                    accent = JarvisPalette.Cyan,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HudIcon(
                            glyph = HudGlyph.SLIDERS,
                            color = JarvisPalette.CyanBright,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "SYSTEM",
                            style = MaterialTheme.typography.labelMedium,
                            color = JarvisPalette.Ice,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        HudIcon(
                            glyph = HudGlyph.CLOSE,
                            color = JarvisPalette.TextDim,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = onClose),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Network Connected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HudIcon(
                            glyph = HudGlyph.WIFI,
                            color = JarvisPalette.CyanBright,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Network",
                                style = MaterialTheme.typography.bodySmall,
                                color = JarvisPalette.Ice,
                            )
                            Text(
                                text = if (status?.networkConnected != false) "Connected" else "Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status?.networkConnected != false) {
                                    JarvisPalette.Success
                                } else {
                                    JarvisPalette.Amber
                                },
                            )
                        }
                        Text(
                            text = status?.network ?: "Wi-Fi",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Battery
                    SystemMetricRow(
                        glyph = HudGlyph.BATTERY,
                        label = "Battery",
                        value = status?.let { "${it.batteryPercent}%" } ?: "92%",
                        valueColor = JarvisPalette.Ice,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Temperature
                    SystemMetricRow(
                        glyph = HudGlyph.TEMPERATURE,
                        label = "Temperature",
                        value = status?.batteryTemperatureC?.let { "${it.toInt()}°C" } ?: "36°C",
                        valueColor = JarvisPalette.Ice,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Performance
                    SystemMetricRow(
                        glyph = HudGlyph.CHIP,
                        label = "Performance",
                        value = "Optimal",
                        valueColor = JarvisPalette.CyanBright,
                    )

                }

                // Card 3: NOW PLAYING / JARVIS Voice Active
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    corner = 20.dp,
                    accent = JarvisPalette.CyanBright,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HudIcon(
                            glyph = HudGlyph.PLAY,
                            color = JarvisPalette.CyanBright,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "NOW PLAYING",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.Ice,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    VoiceWave(
                        active = state.speaking || state.listening || status?.musicActive == true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        color = JarvisPalette.CyanBright,
                        bars = 27,
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(JarvisPalette.Panel)
                                .border(1.dp, JarvisPalette.CyanBright.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            ReactorGlyph(modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "JARVIS Voice",
                                style = MaterialTheme.typography.titleSmall,
                                color = JarvisPalette.Ice,
                            )
                            Text(
                                text = if (state.speaking) "Speaking" else if (state.listening) "Listening" else "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.CyanBright,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAccessRow(
    glyph: HudGlyph,
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisPalette.Deep.copy(alpha = 0.78f))
            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.22f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            HudIcon(
                glyph = glyph,
                color = accent,
                modifier = Modifier.size(15.dp),
                weight = 1.7.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = JarvisPalette.Ice,
            modifier = Modifier.weight(1f),
        )
        HudIcon(
            glyph = HudGlyph.CHEVRON_RIGHT,
            color = JarvisPalette.TextDim,
            modifier = Modifier.size(13.dp),
            weight = 1.9.dp,
        )
    }
}

@Composable
private fun SystemMetricRow(
    glyph: HudGlyph,
    label: String,
    value: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudIcon(
            glyph = glyph,
            color = JarvisPalette.CyanBright,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = JarvisPalette.Ice,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontFamily = HudNumerals.fontFamily,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MiniTogglePill(
    glyph: HudGlyph,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val tint = if (active) JarvisPalette.CyanBright else JarvisPalette.TextDim
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (active) JarvisPalette.Cyan.copy(alpha = 0.18f)
                else JarvisPalette.Deep.copy(alpha = 0.75f),
            )
            .border(
                1.dp,
                if (active) JarvisPalette.CyanBright else JarvisPalette.Line.copy(alpha = 0.55f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudIcon(glyph = glyph, color = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
