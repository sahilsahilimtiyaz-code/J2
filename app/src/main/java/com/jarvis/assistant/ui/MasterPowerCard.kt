package com.jarvis.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.theme.JarvisPalette

/**
 * Shut Off / Master Power switch.
 * Turning Shut Off ON (or Master Power OFF) releases the microphone, stops all
 * foreground services, cancels speech and screen automation, and puts Jarvis into
 * complete offline hibernation.
 */
@Composable
fun MasterPowerCard(
    state: JarvisUiState,
    onPowerOff: () -> Unit,
) {
    val isShutOff = !state.settings.masterEnabled
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = JarvisPalette.Danger,
        corner = 22.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(JarvisPalette.Danger.copy(alpha = 0.14f))
                    .border(1.dp, JarvisPalette.Danger.copy(alpha = 0.48f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HudIcon(
                    glyph = HudGlyph.BOLT,
                    color = JarvisPalette.Danger,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Shut Off J.A.R.V.I.S.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = JarvisPalette.Ice,
                    letterSpacing = 0.3.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Turn on Shut Off to completely power down the app, microphone, voice, and all background services.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = isShutOff,
                onCheckedChange = { shutOffRequested ->
                    if (shutOffRequested) onPowerOff()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisPalette.Ice,
                    checkedTrackColor = JarvisPalette.Danger,
                    uncheckedThumbColor = JarvisPalette.CyanBright,
                    uncheckedTrackColor = JarvisPalette.PanelLight,
                    uncheckedBorderColor = JarvisPalette.Cyan.copy(alpha = 0.55f),
                ),
            )
        }
    }
}

