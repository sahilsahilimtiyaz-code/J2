package com.jarvis.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.theme.JarvisPalette
import com.jarvis.assistant.update.AvailableUpdate

/**
 * The "a newer build is on the Octa-Jarvis site" sheet.
 *
 * Shown once per published version: downloading opens the site, Later hides it
 * until the next launch, and Skip silences that exact version for good.
 */
@Composable
fun UpdateAvailableDialog(
    update: AvailableUpdate?,
    currentVersionName: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkipVersion: () -> Unit,
) {
    AnimatedVisibility(
        visible = update != null,
        enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 6 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 6 },
    ) {
        val shown = update ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f)),
            contentAlignment = Alignment.Center,
        ) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                accent = JarvisPalette.CyanBright,
                corner = 24.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HudIcon(
                            glyph = HudGlyph.BOLT,
                            color = JarvisPalette.CyanBright,
                            modifier = Modifier.size(16.dp),
                            weight = 1.8.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (shown.isMandatory) "REQUIRED UPDATE" else "APP UPDATE AVAILABLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.CyanBright,
                            letterSpacing = 1.8.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(JarvisPalette.Cyan.copy(alpha = 0.16f))
                            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "v${shown.versionName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.Ice,
                            fontSize = 10.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "A new build is ready, sir.",
                    style = MaterialTheme.typography.titleMedium,
                    color = JarvisPalette.Ice,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (currentVersionName.isNotBlank()) {
                        "Installed: v$currentVersionName → available: v${shown.versionName}"
                    } else {
                        "Available: v${shown.versionName}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim,
                )

                if (shown.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = shown.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JarvisPalette.TextDim,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Open Octa-Jarvis, download the APK and install it over this copy. " +
                        "Your keys, memories and settings are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim.copy(alpha = 0.8f),
                )

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UpdateDialogButton(
                        label = "Later",
                        filled = false,
                        onClick = onLater,
                        modifier = Modifier.weight(1f),
                    )
                    UpdateDialogButton(
                        label = "Download",
                        filled = true,
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (!shown.isMandatory) {
                    Spacer(Modifier.height(6.dp))
                    UpdateDialogButton(
                        label = "Skip this version",
                        filled = false,
                        onClick = onSkipVersion,
                        modifier = Modifier.fillMaxWidth(),
                        subdued = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateDialogButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subdued: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    filled -> JarvisPalette.Cyan.copy(alpha = 0.92f)
                    subdued -> JarvisPalette.Panel.copy(alpha = 0.4f)
                    else -> JarvisPalette.Panel.copy(alpha = 0.9f)
                },
            )
            .border(
                width = 1.dp,
                color = when {
                    filled -> JarvisPalette.CyanBright.copy(alpha = 0.6f)
                    subdued -> JarvisPalette.Line.copy(alpha = 0.6f)
                    else -> JarvisPalette.Line
                },
                shape = shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) JarvisPalette.Void else JarvisPalette.Ice.copy(alpha = if (subdued) 0.7f else 1f),
        )
    }
}
