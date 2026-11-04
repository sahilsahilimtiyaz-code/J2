package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.theme.JarvisPalette

/**
 * Master power switch. Turning it off releases the microphone, stops the
 * foreground services, cancels speech and automation, and shows the offline
 * screen. It is the one switch that truly turns Jarvis off.
 */
@Composable
fun MasterPowerCard(
    state: JarvisUiState,
    onPowerOff: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = JarvisPalette.Danger,
        corner = 20.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HudIcon(
                glyph = HudGlyph.BOLT,
                color = JarvisPalette.Danger,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Master Power",
                    style = MaterialTheme.typography.titleSmall,
                    color = JarvisPalette.Ice,
                )
                Text(
                    text = "Switch off to stop the microphone, every service, speech and background automation completely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.settings.masterEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) onPowerOff()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisPalette.Ice,
                    checkedTrackColor = JarvisPalette.Cyan,
                    uncheckedThumbColor = JarvisPalette.TextDim,
                    uncheckedTrackColor = JarvisPalette.PanelLight,
                ),
            )
        }
    }
}
