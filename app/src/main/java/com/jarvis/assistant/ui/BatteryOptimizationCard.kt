package com.jarvis.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.PowerProfile
import com.jarvis.assistant.core.PowerProfileOverride
import com.jarvis.assistant.core.ThermalState
import com.jarvis.assistant.ui.theme.JarvisPalette

@Composable
fun BatteryOptimizationCard(
    state: JarvisUiState,
    onRequestBatteryUnrestricted: () -> Unit,
    onSelectPowerProfile: (PowerProfileOverride) -> Unit = {},
    onOptimizeAppCache: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val diag = state.batteryDiagnostics
    val profile = diag.profile

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "🔋",
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "Battery & App Optimization",
                        style = MaterialTheme.typography.titleSmall,
                        color = JarvisPalette.CyanBright,
                    )
                }

                Surface(
                    color = when (profile) {
                        PowerProfile.PERFORMANCE -> Color(0xFF00E676).copy(alpha = 0.16f)
                        PowerProfile.BALANCED -> JarvisPalette.Cyan.copy(alpha = 0.16f)
                        PowerProfile.POWER_SAVER -> Color(0xFFFF9100).copy(alpha = 0.16f)
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = when (profile) {
                            PowerProfile.PERFORMANCE -> Color(0xFF00E676)
                            PowerProfile.BALANCED -> JarvisPalette.CyanBright
                            PowerProfile.POWER_SAVER -> Color(0xFFFF9100)
                        },
                    ),
                ) {
                    Text(
                        text = "${profile.icon} ${profile.label}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (profile) {
                            PowerProfile.PERFORMANCE -> Color(0xFF00E676)
                            PowerProfile.BALANCED -> JarvisPalette.CyanBright
                            PowerProfile.POWER_SAVER -> Color(0xFFFF9100)
                        },
                    )
                }
            }

            // Telemetry grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Battery Gauge Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(JarvisPalette.Panel.copy(alpha = 0.75f))
                        .padding(10.dp),
                ) {
                    Column {
                        Text(
                            text = "BATTERY LEVEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                            fontSize = 9.sp,
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${diag.levelPercent}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = JarvisPalette.Ice,
                            )
                            Text(
                                text = if (diag.isCharging) "(${diag.chargePlug})" else "(On Battery)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (diag.isCharging) Color(0xFF00E676) else JarvisPalette.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }

                // Temp & Thermal Status Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(JarvisPalette.Panel.copy(alpha = 0.75f))
                        .padding(10.dp),
                ) {
                    Column {
                        Text(
                            text = "THERMAL & PACING",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                            fontSize = 9.sp,
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "%.1f°C".format(diag.temperatureCelsius),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = when (diag.thermalState) {
                                    ThermalState.SEVERE -> Color(0xFFFF5252)
                                    ThermalState.MODERATE -> Color(0xFFFF9100)
                                    else -> JarvisPalette.Ice
                                },
                            )
                            Text(
                                text = "• ${diag.thermalState.label} (${diag.targetIdleFps}fps)",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }

            // Interactive Power Profile Selector (Auto / Performance / Balanced / Power Saver)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "POWER PROFILE MODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextDim,
                    letterSpacing = 1.2.sp,
                    fontSize = 9.5.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PowerProfileOverride.entries.forEach { option ->
                        val selected = diag.profileOverride == option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectPowerProfile(option) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                JarvisPalette.Cyan.copy(alpha = 0.22f)
                            } else {
                                JarvisPalette.Panel.copy(alpha = 0.65f)
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (selected) JarvisPalette.CyanBright else JarvisPalette.Cyan.copy(alpha = 0.22f),
                            ),
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) JarvisPalette.CyanBright else JarvisPalette.TextDim,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            // Samsung A54 Sleeping Apps & Whitelist Status
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (diag.isIgnoringOptimizations) {
                    Color(0xFF00E676).copy(alpha = 0.08f)
                } else {
                    Color(0xFFFF9100).copy(alpha = 0.12f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (diag.isIgnoringOptimizations) {
                        Color(0xFF00E676).copy(alpha = 0.35f)
                    } else {
                        Color(0xFFFF9100).copy(alpha = 0.5f)
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (diag.isIgnoringOptimizations) {
                                "✓ SAMSUNG A54: UNRESTRICTED"
                            } else {
                                "⚠️ SAMSUNG A54: RESTRICTED"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (diag.isIgnoringOptimizations) Color(0xFF00E676) else Color(0xFFFF9100),
                            letterSpacing = 1.sp,
                        )

                        if (!diag.isIgnoringOptimizations) {
                            TextButton(
                                onClick = onRequestBatteryUnrestricted,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Allow Unrestricted",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = JarvisPalette.CyanBright,
                                )
                            }
                        }
                    }
                    Text(
                        text = if (diag.isIgnoringOptimizations) {
                            "Jarvis is exempt from Samsung background app sleeping and will remain responsive in deep sleep."
                        } else {
                            "Samsung Galaxy A54 will put Jarvis into Sleeping Apps unless set to Unrestricted under App Info → Battery."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp,
                    )
                }
            }

            // One-tap App Cache & Socket Optimization
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = JarvisPalette.Panel.copy(alpha = 0.70f),
                border = BorderStroke(1.dp, JarvisPalette.Cyan.copy(alpha = 0.25f)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "App Cache & Socket Pool",
                            style = MaterialTheme.typography.labelMedium,
                            color = JarvisPalette.Ice,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = onOptimizeAppCache,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "Optimize Now",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = JarvisPalette.CyanBright,
                            )
                        }
                    }
                    Text(
                        text = diag.lastCacheTrimSummary.ifBlank {
                            "Clears temporary cache files (preserving offline GGUF models & memories) and flushes idle HTTP sockets."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }

            // Active Battery & Performance Optimizations (all genuinely wired in code)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "ACTIVE EFFICIENCY ARCHITECTURE",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextDim,
                    letterSpacing = 1.2.sp,
                    fontSize = 9.5.sp,
                )
                Text(
                    text = "• Lifecycle & Adaptive Orb Pacing: 60fps when active, ${diag.targetIdleFps}fps idle, 0% GPU/video draw when backgrounded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    text = "• Batched Zero-GC Orb Canvas: 4,224 stipple dots render via 6 batched native draw calls with pre-allocated trig & point buffers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    text = "• Thermal-Aware Local GGUF Threading: On-device AI scales 2–4 ARM64 threads by thermal/power state and releases RAM on memory trim",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    text = "• Pooled HTTP/2 & Idle WorkManager Jobs: Shared OkHttp connection pool + battery-not-low/idle constraints for background workers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
