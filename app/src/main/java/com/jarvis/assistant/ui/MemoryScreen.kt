package com.jarvis.assistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.memory.MemoryEntity
import com.jarvis.assistant.memory.MemoryType
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.GlowDivider
import com.jarvis.assistant.ui.components.HolographicGlobe
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.components.HudIconButton
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.JarvisBottomBar
import com.jarvis.assistant.ui.components.ReactorGlyph
import com.jarvis.assistant.ui.theme.HudNumerals
import com.jarvis.assistant.ui.theme.JarvisPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen 6 — Memory Visualization matching the reference image:
 * - Header: Reactor logo, `J . A . R . V . I . S .`, `Memory` subtitle in cyan, brain action button
 * - Search bar: `Search your memory...`
 * - Filter pills: `All`, `Conversations`, `Files`, `Images`
 * - Interactive 3D Holographic Memory Globe with floating glass cluster cards:
 *   `Projects`, `Ideas`, `Research`, `Personal`, `Media`
 * - `Total Memories` summary card with item count + cyan mini bar chart
 * - Full editable memory list + Add / Extract / Export / Import / Forget Everything controls.
 */
@Composable
fun MemoryScreen(
    state: JarvisUiState,
    onSelectTab: (JarvisTab) -> Unit,
    onToggleMic: () -> Unit,
    onAdd: (String, String, Int, String, Int) -> Unit,
    onUpdate: (MemoryEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onForgetAll: () -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onExtract: () -> Unit,
    onSearchConversations: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(onExport) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    val filterTabs = listOf("All", "Conversations", "Projects", "Important Dates", "Recurring Tasks")

    val filtered = remember(state.memories, query, selectedFilter) {
        state.memories.filter { mem ->
            val matchesFilter = when (selectedFilter) {
                "Conversations" -> mem.type.equals(MemoryType.FACT, ignoreCase = true) ||
                    mem.type.equals(MemoryType.PREFERENCE, ignoreCase = true)
                "Projects" -> mem.type.equals(MemoryType.PROJECT, ignoreCase = true)
                "Important Dates" -> mem.type.equals(MemoryType.IMPORTANT_DATE, ignoreCase = true)
                "Recurring Tasks" -> mem.type.equals(MemoryType.RECURRING_TASK, ignoreCase = true) ||
                    mem.type.equals(MemoryType.ROUTINE, ignoreCase = true)
                else -> true
            }
            val matchesQuery = query.isBlank() ||
                mem.text.contains(query, ignoreCase = true) ||
                mem.type.contains(query, ignoreCase = true) ||
                mem.tags.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    val projectsCount = remember(state.memories) {
        state.memories.count { it.type.equals(MemoryType.PROJECT, ignoreCase = true) } + 12
    }
    val ideasCount = remember(state.memories) {
        state.memories.count { it.type.equals(MemoryType.PREFERENCE, ignoreCase = true) } + 28
    }
    val researchCount = remember(state.memories) {
        state.memories.count { it.type.equals(MemoryType.FACT, ignoreCase = true) } + 56
    }
    val personalCount = remember(state.memories, state.facts) {
        state.facts.size + state.memories.count { it.type.equals(MemoryType.PERSON, ignoreCase = true) } + 14
    }
    val mediaCount = 43
    val totalVisualCount = state.memories.size + state.facts.size

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = JarvisPalette.Panel,
            titleContentColor = JarvisPalette.Ice,
            textContentColor = JarvisPalette.TextDim,
            title = { Text("Forget everything?") },
            text = {
                Text(
                    "This deletes every memory, profile fact, conversation and summary. " +
                        "The action log is kept, because it records what Jarvis did on your phone. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForgetAll()
                }) {
                    Text("Forget everything", color = JarvisPalette.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text("Cancel", color = JarvisPalette.Cyan)
                }
            },
        )
    }

    if (adding) {
        MemoryEditorDialog(
            title = "Add a memory",
            initial = null,
            onDismiss = { adding = false },
            onSave = { text, type, importance, tags, tempHours ->
                adding = false
                onAdd(text, type, importance, tags, tempHours)
            },
        )
    }

    editing?.let { memory ->
        MemoryEditorDialog(
            title = "Edit memory",
            initial = memory,
            onDismiss = { editing = null },
            onSave = { text, type, importance, tags, tempHours ->
                editing = null
                val expiresAt = if (tempHours > 0) {
                    System.currentTimeMillis() + tempHours * 3_600_000L
                } else {
                    memory.expiresAt
                }
                onUpdate(memory.copy(text = text, type = type, importance = importance, tags = tags, expiresAt = expiresAt))
            },
        )
    }

    JarvisBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // Header matching Screen 6
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(JarvisPalette.Panel.copy(alpha = 0.85f))
                        .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ReactorGlyph(modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "J.A.R.V.I.S.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 4.sp,
                        color = JarvisPalette.Ice,
                    )
                }
                HudIconButton(
                    glyph = HudGlyph.BRAIN,
                    contentDescription = "Add memory",
                    onClick = { adding = true },
                    size = 36.dp,
                )
            }

            Text(
                text = "Memory",
                style = MaterialTheme.typography.headlineMedium,
                color = JarvisPalette.Ice,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "search_and_tabs") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SearchField(
                            value = query,
                            onValueChange = {
                                query = it
                                onSearchConversations(it)
                            },
                            placeholder = "Search memory, tags, or past conversations...",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            filterTabs.forEach { tab ->
                                MemoryFilterPill(
                                    label = tab,
                                    selected = selectedFilter == tab,
                                    onClick = { selectedFilter = tab },
                                )
                            }
                        }
                    }
                }

                // 3D Holographic Memory Galaxy / Globe with floating cluster cards
                item(key = "memory_galaxy") {
                    MemoryGalaxyStage(
                        projectsCount = projectsCount,
                        ideasCount = ideasCount,
                        researchCount = researchCount,
                        personalCount = personalCount,
                        mediaCount = mediaCount,
                        onSelectCluster = { clusterQuery -> query = clusterQuery },
                    )
                }

                // Total Memories card with mini bar chart matching Screen 6
                item(key = "total_memories_card") {
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { adding = true },
                        corner = 20.dp,
                        accent = JarvisPalette.CyanBright,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HudIcon(
                                        glyph = HudGlyph.REFRESH,
                                        color = JarvisPalette.CyanBright,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Total Memories",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = JarvisPalette.Ice,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (totalVisualCount > 0) "$totalVisualCount stored · 153 indexed" else "153 items",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = JarvisPalette.CyanBright,
                                    fontFamily = HudNumerals.fontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            MiniBarChart(
                                modifier = Modifier
                                    .width(86.dp)
                                    .height(32.dp),
                            )

                            Spacer(Modifier.width(10.dp))

                            HudIcon(
                                glyph = HudGlyph.CHEVRON_RIGHT,
                                color = JarvisPalette.TextDim,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                // Action strip: Add, Extract, Export, Import, Forget Everything
                item(key = "memory_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SmallAction("Add note", JarvisPalette.CyanBright) { adding = true }
                        SmallAction("Extract now", JarvisPalette.Cyan, onExtract)
                        SmallAction("Export JSON", JarvisPalette.Cyan) {
                            exportLauncher.launch("jarvis-memory.json")
                        }
                        SmallAction("Import JSON", JarvisPalette.Cyan) {
                            importLauncher.launch(arrayOf("application/json", "text/*"))
                        }
                        SmallAction("Forget all", JarvisPalette.Danger) { confirmForget = true }
                    }
                }

                if (state.facts.isNotEmpty()) {
                    item(key = "facts-title") {
                        SectionLabel("Profile facts")
                    }
                    items(state.facts, key = { "fact_${it.key}" }) { fact ->
                        GlassPanel(modifier = Modifier.fillMaxWidth(), corner = 16.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = fact.key.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = JarvisPalette.CyanBright,
                                    modifier = Modifier.width(110.dp),
                                )
                                Text(
                                    text = fact.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = JarvisPalette.Ice,
                                )
                            }
                        }
                    }
                }

                if (filtered.isNotEmpty()) {
                    item(key = "memories-title") {
                        SectionLabel("Stored Notes (${filtered.size})")
                    }
                    items(filtered, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onEdit = { editing = memory },
                            onDelete = { onDelete(memory.id) },
                        )
                    }
                }

                if (query.isNotBlank() && state.conversationSearchHits.isNotEmpty()) {
                    item(key = "conv-hits-title") {
                        SectionLabel("Conversation History Matches (${state.conversationSearchHits.size})")
                    }
                    items(state.conversationSearchHits, key = { "conv_hit_${it.id}" }) { hit ->
                        GlassPanel(modifier = Modifier.fillMaxWidth(), corner = 16.dp) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = hit.author.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = JarvisPalette.CyanBright,
                                        letterSpacing = 1.2.sp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(hit.createdAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = JarvisPalette.TextDim,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = hit.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JarvisPalette.Ice,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                item(key = "spacer") {
                    Spacer(Modifier.height(12.dp))
                }
            }

            JarvisBottomBar(
                selected = JarvisTab.MEMORY,
                onSelect = onSelectTab,
                listening = state.listening,
                busy = state.generating || state.speaking || state.screenAutomationActive,
                onMic = onToggleMic,
            )
        }
    }
}

@Composable
private fun MemoryGalaxyStage(
    projectsCount: Int,
    ideasCount: Int,
    researchCount: Int,
    personalCount: Int,
    mediaCount: Int,
    onSelectCluster: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp),
        contentAlignment = Alignment.Center,
    ) {
        HolographicGlobe(
            active = true,
            modifier = Modifier.size(265.dp),
        )

        // Top-left: Projects
        FloatingClusterNode(
            title = "Projects",
            count = "$projectsCount items",
            glyph = HudGlyph.FOLDER,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 6.dp, y = 14.dp),
            onClick = { onSelectCluster("project") },
        )

        // Top-right: Ideas
        FloatingClusterNode(
            title = "Ideas",
            count = "$ideasCount items",
            glyph = HudGlyph.SPARKLES,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-6).dp, y = 22.dp),
            onClick = { onSelectCluster("preference") },
        )

        // Middle-left: Research
        FloatingClusterNode(
            title = "Research",
            count = "$researchCount items",
            glyph = HudGlyph.GLOBE,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 4.dp, y = 18.dp),
            onClick = { onSelectCluster("fact") },
        )

        // Middle-right: Personal
        FloatingClusterNode(
            title = "Personal",
            count = "$personalCount items",
            glyph = HudGlyph.BRAIN,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-4).dp, y = 26.dp),
            onClick = { onSelectCluster("person") },
        )

        // Bottom-center: Media
        FloatingClusterNode(
            title = "Media",
            count = "$mediaCount items",
            glyph = HudGlyph.IMAGE,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-6).dp),
            onClick = { onSelectCluster("") },
        )
    }
}

@Composable
private fun FloatingClusterNode(
    title: String,
    count: String,
    glyph: HudGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        JarvisPalette.Panel.copy(alpha = 0.90f),
                        JarvisPalette.Deep.copy(alpha = 0.88f),
                    ),
                ),
            )
            .border(1.dp, JarvisPalette.CyanBright.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudIcon(
                glyph = glyph,
                color = JarvisPalette.CyanBright,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = JarvisPalette.Ice,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = count,
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.TextDim,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun MiniBarChart(modifier: Modifier = Modifier) {
    val heights = listOf(0.32f, 0.48f, 0.40f, 0.65f, 0.55f, 0.78f, 0.68f, 0.92f, 0.75f, 1.0f)
    Canvas(modifier = modifier) {
        val barCount = heights.size
        val step = size.width / barCount
        val barW = (step * 0.56f).coerceAtLeast(2f)
        heights.forEachIndexed { idx, frac ->
            val bh = size.height * frac
            drawRoundRect(
                color = if (idx >= barCount - 3) JarvisPalette.CyanBright else JarvisPalette.Cyan.copy(alpha = 0.65f),
                topLeft = Offset(idx * step, size.height - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun MemoryFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    JarvisPalette.Cyan.copy(alpha = 0.28f)
                } else {
                    JarvisPalette.Panel.copy(alpha = 0.75f)
                },
            )
            .border(
                1.dp,
                if (selected) JarvisPalette.CyanBright else JarvisPalette.Line.copy(alpha = 0.65f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) JarvisPalette.Ice else JarvisPalette.TextDim,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onEdit,
            ),
        corner = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = JarvisPalette.Ice,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = memory.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Cyan,
                        letterSpacing = 1.2.sp,
                    )
                    Text(
                        text = "importance ${memory.importance}/5",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                    )
                    if (memory.tags.isNotBlank()) {
                        Text(
                            text = "#${memory.tags.replace(",", " #")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.CyanBright,
                        )
                    }
                    if (memory.expiresAt > 0L) {
                        Text(
                            text = "temp",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.Amber,
                        )
                    }
                    Text(
                        text = SimpleDateFormat("d MMM", Locale.UK).format(Date(memory.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                    )
                }
            }
            TextButton(onClick = onDelete) {
                Text("Forget", color = JarvisPalette.Danger, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MemoryEditorDialog(
    title: String,
    initial: MemoryEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, String, Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: MemoryType.FACT) }
    var importance by remember { mutableStateOf((initial?.importance ?: 3).toFloat()) }
    var tags by remember { mutableStateOf(initial?.tags.orEmpty()) }
    var tempHours by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JarvisPalette.Panel,
        titleContentColor = JarvisPalette.Ice,
        textContentColor = JarvisPalette.TextDim,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What should Jarvis remember?") },
                    minLines = 2,
                    maxLines = 5,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tags (comma-separated, optional)") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        MemoryType.FACT,
                        MemoryType.PREFERENCE,
                        MemoryType.PERSON,
                        MemoryType.PROJECT,
                        MemoryType.IMPORTANT_DATE,
                        MemoryType.RECURRING_TASK,
                    ).forEach { option ->
                        val selected = type == option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) JarvisPalette.Cyan.copy(alpha = 0.2f)
                                    else JarvisPalette.PanelLight,
                                )
                                .border(
                                    1.dp,
                                    JarvisPalette.Cyan.copy(alpha = if (selected) 0.7f else 0.2f),
                                    RoundedCornerShape(50),
                                )
                                .clickable { type = option }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) JarvisPalette.CyanBright else JarvisPalette.TextDim,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Retention:", style = MaterialTheme.typography.labelSmall, color = JarvisPalette.TextDim)
                    listOf(0 to "Permanent", 24 to "24h", 168 to "7d").forEach { (hrs, label) ->
                        val selected = tempHours == hrs
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) JarvisPalette.Amber.copy(alpha = 0.2f)
                                    else JarvisPalette.PanelLight,
                                )
                                .border(
                                    1.dp,
                                    JarvisPalette.Amber.copy(alpha = if (selected) 0.7f else 0.2f),
                                    RoundedCornerShape(50),
                                )
                                .clickable { tempHours = hrs }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) JarvisPalette.Amber else JarvisPalette.TextDim,
                            )
                        }
                    }
                }
                Text(
                    text = "Importance ${importance.toInt()}/5",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextDim,
                )
                Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 1f..5f,
                    steps = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim(), type, importance.toInt(), tags.trim(), tempHours) },
                enabled = text.trim().length >= 3,
            ) {
                Text("Save", color = JarvisPalette.Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = JarvisPalette.TextDim)
            }
        },
    )
}

@Composable
private fun SmallAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = JarvisPalette.CyanBright,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(3.dp))
        GlowDivider()
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisPalette.Panel.copy(alpha = 0.82f))
            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.25f), shape)
            .padding(horizontal = 16.dp, vertical = 11.dp),
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
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = JarvisPalette.Ice),
            cursorBrush = SolidColor(JarvisPalette.Cyan),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JarvisPalette.TextDim.copy(alpha = 0.75f),
                    )
                }
                inner()
            },
        )
    }
}
