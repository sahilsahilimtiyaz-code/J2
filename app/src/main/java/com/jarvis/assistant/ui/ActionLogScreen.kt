package com.jarvis.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.tools.ActionLogEntry
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
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
    onRetryEntry: (ActionLogEntry) -> Unit = {},
    onUndoEntry: (ActionLogEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("All") }

    val filters = listOf("All", "Succeeded", "Failed", "Undoable")

    val filteredActions = remember(state.actions, searchQuery, statusFilter) {
        state.actions.filter { entry ->
            val matchesStatus = when (statusFilter) {
                "Succeeded" -> entry.success
                "Failed" -> !entry.success
                "Undoable" -> entry.isUndoable
                else -> true
            }
            val matchesQuery = searchQuery.isBlank() ||
                entry.label.contains(searchQuery, ignoreCase = true) ||
                entry.toolName.contains(searchQuery, ignoreCase = true) ||
                entry.detail.contains(searchQuery, ignoreCase = true)
            matchesStatus && matchesQuery
        }
    }

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
                                "Retry failed commands or undo reversible hardware settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JarvisPalette.TextDim,
                        )
                        Spacer(Modifier.height(10.dp))

                        // Search bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(JarvisPalette.Panel.copy(alpha = 0.85f))
                                .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.25f), RoundedCornerShape(50))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HudIcon(
                                glyph = HudGlyph.SEARCH,
                                color = JarvisPalette.TextDim,
                                modifier = Modifier.size(16.dp),
                                weight = 1.8.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = JarvisPalette.Ice),
                                cursorBrush = SolidColor(JarvisPalette.Cyan),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search tool runs...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = JarvisPalette.TextDim.copy(alpha = 0.75f),
                                        )
                                    }
                                    inner()
                                },
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            filters.forEach { label ->
                                val selected = statusFilter == label
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (selected) JarvisPalette.Cyan.copy(alpha = 0.26f)
                                            else JarvisPalette.Panel.copy(alpha = 0.75f),
                                        )
                                        .border(
                                            1.dp,
                                            if (selected) JarvisPalette.CyanBright else JarvisPalette.Line.copy(alpha = 0.65f),
                                            RoundedCornerShape(50),
                                        )
                                        .clickable { statusFilter = label }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) JarvisPalette.Ice else JarvisPalette.TextDim,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                            if (state.actions.isNotEmpty()) {
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

                if (filteredActions.isEmpty()) {
                    item(key = "empty") {
                        GlassPanel(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReactorGlyph(modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "No matching actions",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = JarvisPalette.Ice,
                                    )
                                    Text(
                                        text = "Ask Jarvis to open an app, set a timer or change a setting.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JarvisPalette.TextDim,
                                    )
                                }
                            }
                        }
                    }
                }

                items(filteredActions, key = { it.id }) { entry ->
                    LogRow(
                        entry = entry,
                        onRetry = { onRetryEntry(entry) },
                        onUndo = { onUndoEntry(entry) },
                    )
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
private fun LogRow(
    entry: ActionLogEntry,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        corner = 16.dp,
        accent = if (entry.success) JarvisPalette.Success else JarvisPalette.Danger,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            if (entry.isRetryable || entry.isUndoable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (entry.isUndoable) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(JarvisPalette.Amber.copy(alpha = 0.16f))
                                .border(1.dp, JarvisPalette.Amber.copy(alpha = 0.55f), RoundedCornerShape(50))
                                .clickable(onClick = onUndo)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Undo Setting",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.Amber,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (entry.isRetryable) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(JarvisPalette.CyanBright.copy(alpha = 0.16f))
                                .border(1.dp, JarvisPalette.CyanBright.copy(alpha = 0.55f), RoundedCornerShape(50))
                                .clickable(onClick = onRetry)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.CyanBright,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
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

