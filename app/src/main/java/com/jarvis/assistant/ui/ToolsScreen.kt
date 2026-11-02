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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.components.HudIconButton
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.JarvisBottomBar
import com.jarvis.assistant.ui.components.ReactorGlyph
import com.jarvis.assistant.ui.components.StatusDot
import com.jarvis.assistant.ui.theme.JarvisPalette

private data class ShowcaseToolItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val badgeText: String,
    val badgeColor: Color,
    val glyph: HudGlyph,
    val promptToRun: String,
    val accent: Color = JarvisPalette.Cyan,
)

/**
 * Screen 5 — Tools Section matching the reference image:
 * - Header: Reactor logo, `J . A . R . V . I . S .`, `Tools` subtitle in cyan, trailing filter button
 * - Category tabs: `All`, `Productivity`, `Media`, `Analysis`
 * - Search bar: `Search tools...`
 * - Glassmorphic tool cards with glowing icon box, title, subtitle, `● Online` / `● Local` / `● Connected`
 *   pill badge, and right chevron, plus all registered phone & screen tools below.
 */
@Composable
fun ToolsScreen(
    state: JarvisUiState,
    onSelectTab: (JarvisTab) -> Unit,
    onToggleMic: () -> Unit,
    onRunToolPrompt: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf("All", "Productivity", "Media", "Analysis")

    val featuredTools = remember(state.permissions) {
        listOf(
            ShowcaseToolItem(
                id = "web_search_card",
                title = "Web Search",
                subtitle = "Real-time information",
                category = "Analysis",
                badgeText = "Online",
                badgeColor = JarvisPalette.Success,
                glyph = HudGlyph.GLOBE,
                promptToRun = "Search the web for latest technology news",
                accent = JarvisPalette.CyanBright,
            ),
            ShowcaseToolItem(
                id = "image_generation_card",
                title = "Image Generation",
                subtitle = "Create stunning images",
                category = "Media",
                badgeText = "Online",
                badgeColor = JarvisPalette.Success,
                glyph = HudGlyph.IMAGE,
                promptToRun = "Describe a futuristic holographic J.A.R.V.I.S. workshop in detail",
                accent = Color(0xFFB58BFF),
            ),
            ShowcaseToolItem(
                id = "video_analysis_card",
                title = "Video Analysis",
                subtitle = "Analyze any video",
                category = "Media",
                badgeText = "Online",
                badgeColor = JarvisPalette.Success,
                glyph = HudGlyph.VIDEO,
                promptToRun = "Open YouTube and search lo-fi beats",
                accent = Color(0xFFFF8071),
            ),
            ShowcaseToolItem(
                id = "file_manager_card",
                title = "File Manager",
                subtitle = "Access device files",
                category = "Productivity",
                badgeText = "Local",
                badgeColor = JarvisPalette.Amber,
                glyph = HudGlyph.FOLDER,
                promptToRun = "Check device storage and available memory",
                accent = Color(0xFF6FA8FF),
            ),
            ShowcaseToolItem(
                id = "camera_vision_card",
                title = "Camera Vision",
                subtitle = "Analyze with camera",
                category = "Analysis",
                badgeText = "Local",
                badgeColor = JarvisPalette.Amber,
                glyph = HudGlyph.CAMERA,
                promptToRun = "Read what is currently on my screen",
                accent = JarvisPalette.Amber,
            ),
            ShowcaseToolItem(
                id = "terminal_card",
                title = "Terminal",
                subtitle = "Execute commands",
                category = "Productivity",
                badgeText = "Connected",
                badgeColor = JarvisPalette.CyanBright,
                glyph = HudGlyph.TERMINAL,
                promptToRun = "Set a timer for 10 minutes",
                accent = Color(0xFFB58BFF),
            ),
        )
    }

    val featuredTitles = remember(featuredTools) {
        featuredTools.map { it.title.lowercase() }.toSet()
    }
    val mappedRegistryTools = remember(state.tools, featuredTools) {
        state.tools.filterNot { tool -> featuredTitles.contains(tool.label.lowercase()) }.map { tool ->
            val mappedCategory = when (tool.category) {
                "Media" -> "Media"
                "Info", "Memory" -> "Analysis"
                else -> "Productivity"
            }
            val badgeText = when {
                tool.status.equals("Ready", ignoreCase = true) -> "Online"
                tool.category == "Screen" || tool.category == "System" -> "Local"
                else -> tool.status
            }
            val badgeColor = when {
                !tool.statusReady -> JarvisPalette.Amber
                badgeText == "Local" -> JarvisPalette.Amber
                badgeText == "Connected" -> JarvisPalette.CyanBright
                else -> JarvisPalette.Success
            }
            ShowcaseToolItem(
                id = "reg_${tool.name}",
                title = tool.label,
                subtitle = tool.description,
                category = mappedCategory,
                badgeText = badgeText,
                badgeColor = badgeColor,
                glyph = glyphForTool(tool.name, tool.category),
                promptToRun = promptForTool(tool.name, tool.label),
            )
        }
    }

    val allItems = remember(featuredTools, mappedRegistryTools) {
        featuredTools + mappedRegistryTools
    }

    val filtered = remember(allItems, selectedCategory, query) {
        allItems.filter { item ->
            (selectedCategory == "All" || item.category == selectedCategory) &&
                (query.isBlank() ||
                    item.title.contains(query, ignoreCase = true) ||
                    item.subtitle.contains(query, ignoreCase = true))
        }
    }

    JarvisBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // Top bar matching Screen 5
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(JarvisPalette.Panel.copy(alpha = 0.85f))
                        .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape),
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
                    glyph = HudGlyph.SLIDERS,
                    contentDescription = "Action Log",
                    onClick = { onSelectTab(JarvisTab.LOG) },
                    size = 36.dp,
                )
            }

            Text(
                text = "Tools",
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "categories") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                label = cat,
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                            )
                        }
                    }
                }

                item(key = "search") {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search tools...",
                    )
                }

                items(filtered, key = { it.id }) { item ->
                    ShowcaseToolRow(
                        item = item,
                        onClick = { onRunToolPrompt(item.promptToRun) },
                    )
                }

                item(key = "tail") { Spacer(Modifier.height(10.dp)) }
            }

            JarvisBottomBar(
                selected = JarvisTab.TOOLS,
                onSelect = onSelectTab,
                listening = state.listening,
                busy = state.generating || state.speaking || state.screenAutomationActive,
                onMic = onToggleMic,
            )
        }
    }
}

@Composable
private fun ShowcaseToolRow(
    item: ShowcaseToolItem,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        corner = 18.dp,
        accent = JarvisPalette.Cyan,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        Brush.radialGradient(
                            listOf(
                                item.accent.copy(alpha = 0.25f),
                                item.accent.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                    .border(
                        1.dp,
                        item.accent.copy(alpha = 0.35f),
                        RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                HudIcon(
                    glyph = item.glyph,
                    color = item.accent,
                    modifier = Modifier.size(21.dp),
                    weight = 1.7.dp,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = JarvisPalette.Ice,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Status Pill (e.g. ● Online, ● Local, ● Connected)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(item.badgeColor.copy(alpha = 0.14f))
                    .border(1.dp, item.badgeColor.copy(alpha = 0.42f), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(
                    color = item.badgeColor,
                    pulsing = false,
                    size = 6.dp,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = item.badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = item.badgeColor,
                    fontSize = 10.sp,
                )
            }

            Spacer(Modifier.width(8.dp))

            HudIcon(
                glyph = HudGlyph.CHEVRON_RIGHT,
                color = JarvisPalette.TextDim,
                modifier = Modifier.size(14.dp),
                weight = 1.9.dp,
            )
        }
    }
}

@Composable
private fun FilterChip(
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
                    Brush.horizontalGradient(
                        listOf(
                            JarvisPalette.Cyan.copy(alpha = 0.35f),
                            JarvisPalette.CyanBright.copy(alpha = 0.22f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            JarvisPalette.Panel.copy(alpha = 0.78f),
                            JarvisPalette.Deep.copy(alpha = 0.78f),
                        ),
                    )
                },
            )
            .border(
                1.dp,
                if (selected) JarvisPalette.CyanBright else JarvisPalette.Line.copy(alpha = 0.65f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            .background(JarvisPalette.Panel.copy(alpha = 0.85f))
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
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = JarvisPalette.Ice),
            cursorBrush = SolidColor(JarvisPalette.Cyan),
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

private fun glyphForTool(name: String, category: String): HudGlyph = when {
    name.contains("web", ignoreCase = true) || name.contains("url", ignoreCase = true) -> HudGlyph.GLOBE
    name.contains("youtube", ignoreCase = true) -> HudGlyph.VIDEO
    name.contains("notification", ignoreCase = true) -> HudGlyph.BELL
    name.contains("screen", ignoreCase = true) || name.contains("automate", ignoreCase = true) || name.contains("camera", ignoreCase = true) -> HudGlyph.CAMERA
    name.contains("flash", ignoreCase = true) -> HudGlyph.TORCH
    category == "Media" -> HudGlyph.NOTE
    category == "Calls & SMS" -> HudGlyph.SEND
    category == "Info" -> HudGlyph.CHIP
    else -> HudGlyph.TERMINAL
}

private fun promptForTool(name: String, label: String): String = when (name) {
    "current_time" -> "What is the current time and date?"
    "device_status" -> "Report my phone battery, memory, storage, and connectivity status"
    "open_app" -> "List my installed apps and open YouTube"
    "list_installed_apps" -> "List my installed apps on this phone"
    "youtube_search" -> "Open YouTube and search lo-fi study beats"
    "web_search" -> "Search the web for today's top technology headlines"
    "open_url" -> "Open https://news.ycombinator.com in my browser"
    "navigate_to" -> "Navigate to the nearest coffee shop"
    "create_calendar_event" -> "Create a calendar event for Project Review tomorrow at 10 AM"
    "share_text" -> "Open the system share sheet with a quick status note"
    "open_settings" -> "Open my phone Wi-Fi settings screen"
    "set_alarm" -> "Set an alarm for 7:30 AM tomorrow"
    "set_timer" -> "Set a timer for 5 minutes"
    "flashlight" -> "Toggle the phone flashlight"
    "take_screenshot" -> "Take a screenshot of my screen right now"
    "open_camera" -> "Open the camera in photo mode"
    "clipboard_control" -> "Read what is currently copied on my clipboard"
    "media_control" -> "Play or pause my current media playback"
    "set_volume" -> "Set my media volume to 60 percent"
    "set_ringer_mode" -> "Set my phone ringer mode to vibrate"
    "find_contact" -> "Search my contacts"
    "place_call" -> "Find a contact and ask before placing a call"
    "send_sms" -> "Draft an SMS message and ask for my confirmation"
    "send_whatsapp_message" -> "Prepare a WhatsApp message and ask for my confirmation"
    "remember_fact" -> "Remember that I prefer concise engineering briefings"
    "recall_memories" -> "Recall everything you remember about my preferences"
    "read_notifications" -> "Read and summarize my recent notifications"
    "dismiss_notifications" -> "Dismiss my cleared notifications"
    "automate_screen_task" -> "Open Android Settings and check my battery status on screen"
    "read_screen" -> "Read what is currently visible on my screen"
    "screen_click" -> "Inspect the screen and tap the search bar"
    "screen_type" -> "Type a search query into the focused input field"
    "screen_scroll" -> "Scroll down on the current screen"
    "screen_global_action" -> "Perform a global screen navigation action"
    else -> "Run the $label tool and report the result"
}

