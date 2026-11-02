package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.tools.ActionLogEntry
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudPill
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.JarvisBottomBar
import com.jarvis.assistant.ui.components.JarvisTopBar
import com.jarvis.assistant.ui.components.ReactorGlyph
import com.jarvis.assistant.ui.components.ScreenTitle
import com.jarvis.assistant.ui.components.StatusDot
import com.jarvis.assistant.ui.theme.JarvisPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ActionLogScreen(
    state: JarvisUiState,
    onSelectTab: (JarvisTab) -> Unit,
    onToggleMic: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = JarvisPalette.Panel,
            titleContentColor = JarvisPalette.Ice,
            textContentColor = JarvisPalette.TextDim,
            title = { Text("Clear the action log?") },
            text = {
                Text("This only deletes the history of what Jarvis did on this phone. It cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) {
                    Text("Clear", color = JarvisPalette.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel", color = JarvisPalette.Cyan)
                }
            },
        )
    }

    JarvisBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            JarvisTopBar(
                title = "ACTION LOG",
                status = "${state.actions.size} RECORDED",
                statusColor = JarvisPalette.Success,
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "intro") {
                    Column {
                        ScreenTitle("What Jarvis did")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Every tool run is recorded here, on this phone only. " +
                                "Nothing is uploaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JarvisPalette.TextDim,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (state.actions.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { confirmClear = true }) {
                                    Text(
                                        text = "Clear log",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = JarvisPalette.Danger,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.actions.isEmpty()) {
                    item(key = "empty") {
                        GlassPanel(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReactorGlyph(modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Nothing recorded yet",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = JarvisPalette.Ice,
                                    )
                                    Text(
                                        text = "Ask Jarvis to open an app, set a timer or send a message.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JarvisPalette.TextDim,
                                    )
                                }
                            }
                        }
                    }
                }

                items(state.actions, key = { it.id }) { entry ->
                    LogRow(entry)
                }

                item(key = "tail") { Spacer(Modifier.height(12.dp)) }
            }

            JarvisBottomBar(
                selected = JarvisTab.LOG,
                onSelect = onSelectTab,
                listening = state.listening,
                busy = state.generating || state.speaking,
                onMic = onToggleMic,
            )
        }
    }
}

@Composable
private fun LogRow(entry: ActionLogEntry) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        corner = 16.dp,
        accent = if (entry.success) JarvisPalette.Success else JarvisPalette.Danger,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = if (entry.success) JarvisPalette.Success else JarvisPalette.Danger,
                pulsing = false,
                size = 7.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = JarvisPalette.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisPalette.TextDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatClock(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextDim,
                    letterSpacing = 0.6.sp,
                )
                Spacer(Modifier.height(4.dp))
                HudPill(
                    text = if (entry.success) "done" else "failed",
                    color = if (entry.success) JarvisPalette.Success else JarvisPalette.Danger,
                    filled = !entry.success,
                )
            }
        }
    }
}

private fun formatClock(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd MMM HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
