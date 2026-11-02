package com.jarvis.assistant.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.brain.LocalDownloadState
import com.jarvis.assistant.brain.LocalModelCatalog
import com.jarvis.assistant.brain.LocalModelDownloadStatus
import com.jarvis.assistant.brain.LocalModelSpec
import com.jarvis.assistant.brain.ProviderKind
import java.util.Locale
import com.jarvis.assistant.settings.OwnerProfile
import com.jarvis.assistant.settings.ProviderPreset
import com.jarvis.assistant.settings.ProviderSettings
import com.jarvis.assistant.settings.VoiceProvider
import com.jarvis.assistant.settings.defaultPresetFor
import com.jarvis.assistant.settings.presetsFor
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.GlowDivider
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.components.HudIconButton
import com.jarvis.assistant.ui.components.HudPill
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.JarvisBottomBar
import com.jarvis.assistant.ui.components.ReactorGlyph
import com.jarvis.assistant.ui.theme.JarvisPalette

private enum class SettingsSection {
    AI_MODEL,
    VOICE,
    APPEARANCE,
    CONNECTIONS,
    SYSTEM,
    SECURITY,
    ABOUT,
}

/**
 * Screen 7 — Settings matching the reference image:
 * - Header: Reactor logo, `J . A . R . V . I . S .`, `Settings` subtitle in cyan, gear icon button
 * - 7 glassmorphic hub rows with circular glowing cyan icon badges:
 *   1. `AI Model` (active model subtitle + live Fetch Free Models & Free Models Only toggle)
 *   2. `Voice Settings` (`JARVIS (Male)` / provider subtitle)
 *   3. `Appearance` (`Themes & UI` / Persona & Owner Profile)
 *   4. `Connections` (`API Keys & Services`)
 *   5. `System` (`Performance & Storage` / Background Listening & Action Log)
 *   6. `Security` (`Privacy & Permissions` / Screen Control Blocklist & Permission Wizard)
 *   7. `About` (`v3.1.0`)
 * - Bottom navigation bar (`JarvisBottomBar` with `Settings` selected).
 */
@Composable
fun SettingsScreen(
    state: JarvisUiState,
    onBack: () -> Unit,
    viewModel: JarvisViewModel,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var expandedSection by remember { mutableStateOf<SettingsSection?>(null) }

    fun toggleSection(section: SettingsSection) {
        expandedSection = if (expandedSection == section) null else section
    }

    val voiceSubtitle = remember(state.settings.voice.provider) {
        when (state.settings.voice.provider) {
            VoiceProvider.FISH_AUDIO -> "JARVIS (Male · Fish Audio)"
            VoiceProvider.ELEVENLABS -> "JARVIS (Male · ElevenLabs)"
            VoiceProvider.SYSTEM -> "JARVIS (Male)"
        }
    }

    JarvisBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            // Top bar matching Screen 7
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
                        .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.4f), CircleShape)
                        .clickable(onClick = onBack),
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
                    glyph = HudGlyph.GEAR,
                    contentDescription = "Add provider",
                    onClick = { showAddDialog = true },
                    size = 36.dp,
                )
            }

            Text(
                text = "Settings",
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
                if (state.error != null) {
                    item(key = "error_banner") {
                        SettingsBanner(
                            message = state.error,
                            isError = true,
                            onDismiss = viewModel::dismissError,
                        )
                    }
                }
                if (state.notice != null) {
                    item(key = "notice_banner") {
                        SettingsBanner(
                            message = state.notice,
                            isError = false,
                            onDismiss = viewModel::dismissNotice,
                        )
                    }
                }

                // 0. Account Session (G Login / Guest)
                item(key = "account_session_card") {
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        corner = 20.dp,
                        accent = JarvisPalette.CyanBright,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReactorGlyph(modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val auth = state.settings.auth
                                Text(
                                    text = when (auth.mode) {
                                        com.jarvis.assistant.settings.AuthMode.GOOGLE ->
                                            "Google Account · ${auth.displayName.ifBlank { "Connected" }}"
                                        com.jarvis.assistant.settings.AuthMode.GUEST ->
                                            "Guest Access Mode"
                                        com.jarvis.assistant.settings.AuthMode.NONE ->
                                            "Not Signed In"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = JarvisPalette.Ice,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = if (auth.mode == com.jarvis.assistant.settings.AuthMode.GOOGLE && auth.email.isNotBlank()) {
                                        auth.email
                                    } else {
                                        "Local device profile · Switch to Google Login anytime"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JarvisPalette.TextDim,
                                )
                            }
                            TextButton(onClick = viewModel::signOut) {
                                Text(
                                    text = if (state.settings.auth.mode == com.jarvis.assistant.settings.AuthMode.GOOGLE) {
                                        "Switch / Sign Out"
                                    } else {
                                        "G Login >"
                                    },
                                    color = JarvisPalette.CyanBright,
                                )
                            }
                        }
                    }
                }

                // 1. AI Model
                item(key = "hub_ai_model") {
                    SettingsHubRow(
                        glyph = HudGlyph.CHIP,
                        title = "AI Model",
                        subtitle = state.defaultProviderModel,
                        expanded = expandedSection == SettingsSection.AI_MODEL,
                        onClick = {
                            toggleSection(SettingsSection.AI_MODEL)
                            if (state.discoveredModels.isEmpty()) {
                                viewModel.fetchAllAvailableModels()
                            }
                        },
                    )
                }
                item(key = "detail_ai_model") {
                    AnimatedVisibility(visible = expandedSection == SettingsSection.AI_MODEL) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OfflineLocalModelsCard(state = state, viewModel = viewModel)
                            AiModelDiscoveryCard(state = state, viewModel = viewModel)
                        }
                    }
                }

                // 2. Voice Settings
                item(key = "hub_voice") {
                    SettingsHubRow(
                        glyph = HudGlyph.MIC,
                        title = "Voice Settings",
                        subtitle = voiceSubtitle,
                        expanded = expandedSection == SettingsSection.VOICE,
                        onClick = { toggleSection(SettingsSection.VOICE) },
                    )
                }
                item(key = "detail_voice") {
                    AnimatedVisibility(visible = expandedSection == SettingsSection.VOICE) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            VoiceSettingsCard(state = state, viewModel = viewModel)
                            BackgroundVoiceCard(state = state, viewModel = viewModel)
                            ScreenUsageHealthCard(state = state, viewModel = viewModel)
                        }
                    }
                }

                // 3. Appearance & Persona
                item(key = "hub_appearance") {
                    SettingsHubRow(
                        glyph = HudGlyph.WRENCH,
                        title = "Appearance",
                        subtitle = "Themes & UI · Persona",
                        expanded = expandedSection == SettingsSection.APPEARANCE,
                        onClick = { toggleSection(SettingsSection.APPEARANCE) },
                    )
                }
                item(key = "detail_appearance") {
                    AnimatedVisibility(visible = expandedSection == SettingsSection.APPEARANCE) {
                        PersonaEditor(state = state, viewModel = viewModel)
                    }
                }

                // 4. Connections (API Keys & Services)
                item(key = "hub_connections") {
                    SettingsHubRow(
                        glyph = HudGlyph.BOLT,
                        title = "Connections",
                        subtitle = "API Keys & Services",
                        expanded = expandedSection == SettingsSection.CONNECTIONS,
                        onClick = { toggleSection(SettingsSection.CONNECTIONS) },
                    )
                }
                if (expandedSection == SettingsSection.CONNECTIONS) {
                    item(key = "connections_help") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Jarvis tries your default provider first, then falls back in order. " +
                                    "All global 100%-free-by-API-key providers (Z.ai, NVIDIA NIM, OpenCode Zen, Agnes AI, OpenRouter, Gemini, Groq, Cerebras, SambaNova, Mistral, GitHub Models, SiliconFlow, Cohere, Hugging Face, Chutes & Pollinations) are supported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JarvisPalette.TextDim,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.addAllGlobalFreeProviders() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text("Load All Free Providers")
                                }
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text("+ Add Provider")
                                }
                            }
                        }
                    }
                    itemsIndexed(
                        items = state.settings.providers,
                        key = { _, provider -> provider.id },
                    ) { index, provider ->
                        ProviderCard(
                            provider = provider,
                            index = index,
                            lastIndex = state.settings.providers.lastIndex,
                            isDefault = provider.id == state.settings.defaultProviderId,
                            state = state,
                            viewModel = viewModel,
                        )
                    }
                    item(key = "add_provider_btn") {
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Add Provider (Z.ai, NVIDIA NIM, OpenCode Zen, Agnes AI, Gemini, OpenRouter, Groq & more)")
                        }
                    }
                }

                // 5. System (Performance & Storage)
                item(key = "hub_system") {
                    SettingsHubRow(
                        glyph = HudGlyph.DISC,
                        title = "System",
                        subtitle = "Performance & Storage",
                        expanded = expandedSection == SettingsSection.SYSTEM,
                        onClick = { toggleSection(SettingsSection.SYSTEM) },
                    )
                }
                item(key = "detail_system") {
                    AnimatedVisibility(visible = expandedSection == SettingsSection.SYSTEM) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ScreenUsageHealthCard(state = state, viewModel = viewModel)
                            BackgroundVoiceCard(state = state, viewModel = viewModel)
                            ActionLogCard(state = state, onOpenLog = viewModel::openActionLog)
                        }
                    }
                }

                // 6. Security (Privacy & Permissions)
                item(key = "hub_security") {
                    SettingsHubRow(
                        glyph = HudGlyph.LOCK,
                        title = "Security",
                        subtitle = "Privacy & Permissions",
                        expanded = expandedSection == SettingsSection.SECURITY,
                        onClick = { toggleSection(SettingsSection.SECURITY) },
                    )
                }
                item(key = "detail_security") {
                    AnimatedVisibility(visible = expandedSection == SettingsSection.SECURITY) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PermissionsCard(state = state, onOpenWizard = viewModel::openPermissions)
                            SafetyCard(state = state, viewModel = viewModel)
                            WhatsAppVoiceNoteCard(state = state, viewModel = viewModel)
                        }
                    }
                }

                // 7. About
                item(key = "hub_about") {
                    SettingsHubRow(
                        glyph = HudGlyph.HOME,
                        title = "About",
                        subtitle = "v3.1.0",
                        expanded = expandedSection == SettingsSection.ABOUT,
                        onClick = { toggleSection(SettingsSection.ABOUT) },
                    )
                }
                item(key = "detail_about") {
                    AnimatedVisibility(visible = expandedSection == SettingsSection.ABOUT) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AboutCard()
                            FeedbackCard()
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(8.dp))
                }
            }

            JarvisBottomBar(
                selected = JarvisTab.SETTINGS,
                onSelect = viewModel::selectTab,
                listening = state.listening,
                busy = state.generating || state.speaking || state.screenAutomationActive,
                onMic = viewModel::toggleListening,
            )
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { kind, name, baseUrl, model, apiKey ->
                viewModel.addProvider(kind, name, baseUrl, model, apiKey)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun SettingsHubRow(
    glyph: HudGlyph,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        corner = 20.dp,
        accent = if (expanded) JarvisPalette.CyanBright else JarvisPalette.Cyan,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Circular glowing icon badge matching Screen 7
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                JarvisPalette.Cyan.copy(alpha = 0.28f),
                                JarvisPalette.Panel.copy(alpha = 0.90f),
                            ),
                        ),
                    )
                    .border(1.dp, JarvisPalette.CyanBright.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HudIcon(
                    glyph = glyph,
                    color = JarvisPalette.CyanBright,
                    modifier = Modifier.size(21.dp),
                    weight = 1.7.dp,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = JarvisPalette.Ice,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HudIcon(
                glyph = HudGlyph.CHEVRON_RIGHT,
                color = if (expanded) JarvisPalette.CyanBright else JarvisPalette.TextDim,
                modifier = Modifier.size(15.dp),
                weight = 1.9.dp,
            )
        }
    }
}

@Composable
private fun OfflineLocalModelsCard(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    val freeGb = remember(state.freeStorageBytes) {
        if (state.freeStorageBytes > 0L) {
            String.format(Locale.US, "%.1f GB free", state.freeStorageBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
        } else {
            "On-Device Storage"
        }
    }
    val activeLocalId = state.settings.localAi.activeModelId
    val isLocalProviderDefault = state.settings.defaultProvider?.kind == ProviderKind.LOCAL_ON_DEVICE

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Offline Local AI Models (No API Key Needed)",
                        style = MaterialTheme.typography.titleSmall,
                        color = JarvisPalette.CyanBright,
                    )
                    Text(
                        text = "Download lightweight GGUF models to your phone so J.A.R.V.I.S. chats and executes all phone tasks 100% offline without any API keys.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                    )
                }
                Spacer(Modifier.width(8.dp))
                HudPill(
                    text = freeGb,
                    color = JarvisPalette.CyanBright,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.settings.localAi.autoFallbackWhenOffline,
                    onCheckedChange = viewModel::setLocalAutoFallback,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-use Offline AI when no internet or API key",
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisPalette.Ice,
                    )
                    Text(
                        text = "Automatically switches to your downloaded local model if offline or no cloud API key is set.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                    )
                }
            }

            LocalModelCatalog.models.forEach { spec ->
                val status = state.localModels[spec.id]
                    ?: LocalModelDownloadStatus(
                        modelId = spec.id,
                        state = LocalDownloadState.NOT_DOWNLOADED,
                        downloadedBytes = 0L,
                        totalBytes = spec.approxBytes,
                    )
                val isSelected = isLocalProviderDefault && activeLocalId == spec.id
                OfflineLocalModelRow(
                    spec = spec,
                    status = status,
                    isSelected = isSelected,
                    onDownload = { viewModel.downloadLocalModel(spec.id) },
                    onPause = { viewModel.pauseLocalModelDownload(spec.id) },
                    onDelete = { viewModel.deleteLocalModel(spec.id) },
                    onSelect = { viewModel.selectActiveLocalModel(spec.id) },
                )
            }
        }
    }
}

@Composable
private fun OfflineLocalModelRow(
    spec: LocalModelSpec,
    status: LocalModelDownloadStatus,
    isSelected: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val accent = when {
        isSelected && status.state == LocalDownloadState.READY -> JarvisPalette.Success
        status.state == LocalDownloadState.READY -> JarvisPalette.CyanBright
        status.state == LocalDownloadState.DOWNLOADING -> JarvisPalette.Amber
        status.state == LocalDownloadState.FAILED -> JarvisPalette.Danger
        else -> JarvisPalette.Cyan
    }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        corner = 14.dp,
        accent = accent,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = spec.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = JarvisPalette.Ice,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(6.dp))
                        HudPill(text = spec.parameterCount, color = JarvisPalette.CyanBright)
                        Spacer(Modifier.width(4.dp))
                        HudPill(text = spec.quantization, color = JarvisPalette.Cyan)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${spec.approxDownloadSizeLabel} · Best use: ${spec.bestUse}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                    )
                }

                when (status.state) {
                    LocalDownloadState.READY -> {
                        if (isSelected) {
                            HudPill(text = "ACTIVE OFFLINE", color = JarvisPalette.Success, filled = true)
                        } else {
                            HudPill(text = "READY", color = JarvisPalette.Success)
                        }
                    }
                    LocalDownloadState.DOWNLOADING -> {
                        val pct = (status.progress * 100f).toInt().coerceIn(0, 100)
                        HudPill(text = "$pct%", color = JarvisPalette.Amber, filled = true)
                    }
                    LocalDownloadState.PAUSED -> {
                        HudPill(text = "PAUSED", color = JarvisPalette.Amber)
                    }
                    LocalDownloadState.FAILED -> {
                        HudPill(text = "RETRY", color = JarvisPalette.Danger)
                    }
                    LocalDownloadState.NOT_DOWNLOADED -> {
                        HudPill(text = "OFFLINE", color = JarvisPalette.Cyan)
                    }
                }
            }

            if (status.state == LocalDownloadState.DOWNLOADING || status.state == LocalDownloadState.PAUSED) {
                LinearProgressIndicator(
                    progress = { status.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = JarvisPalette.CyanBright,
                    trackColor = JarvisPalette.Deep,
                )
                val dlMb = status.downloadedBytes.toDouble() / (1024.0 * 1024.0)
                val totMb = status.totalBytes.coerceAtLeast(1L).toDouble() / (1024.0 * 1024.0)
                val speedMb = status.speedBytesPerSec.toDouble() / (1024.0 * 1024.0)
                Text(
                    text = buildString {
                        append(String.format(Locale.US, "%.1f MB / %.1f MB", dlMb, totMb))
                        if (status.state == LocalDownloadState.DOWNLOADING && speedMb > 0.05) {
                            append(String.format(Locale.US, " · %.2f MB/s", speedMb))
                        } else if (status.state == LocalDownloadState.PAUSED) {
                            append(" · Paused (resumable)")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.CyanBright,
                )
            }

            if (status.errorMessage != null) {
                Text(
                    text = status.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.Danger,
                )
            }

            if (status.state == LocalDownloadState.READY && status.ggufInfo != null) {
                Text(
                    text = "Verified GGUF v${status.ggufInfo.version} (${status.ggufInfo.tensorCount} tensors) · Works offline without API keys",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.Success,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (status.state) {
                    LocalDownloadState.NOT_DOWNLOADED, LocalDownloadState.FAILED -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = if (status.state == LocalDownloadState.FAILED) {
                                    "Retry Download (${spec.approxDownloadSizeLabel})"
                                } else {
                                    "Download (${spec.approxDownloadSizeLabel})"
                                },
                            )
                        }
                    }

                    LocalDownloadState.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Pause")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Cancel")
                        }
                    }

                    LocalDownloadState.PAUSED -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Resume Download")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Delete")
                        }
                    }

                    LocalDownloadState.READY -> {
                        Button(
                            onClick = onSelect,
                            enabled = !isSelected,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(if (isSelected) "Active Offline Model" else "Use Offline (No API Key)")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiModelDiscoveryCard(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    var selectedProviderFilter by remember { mutableStateOf("All") }
    val providerFilters = remember(state.discoveredModels) {
        listOf("All") + state.discoveredModels.map { it.providerLabel }.distinct()
    }
    val filteredModels = remember(state.discoveredModels, state.freeModelsOnly, selectedProviderFilter) {
        state.discoveredModels.filter { model ->
            (!state.freeModelsOnly || model.isFree) &&
                (selectedProviderFilter == "All" || model.providerLabel.equals(selectedProviderFilter, ignoreCase = true))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Global Free AI Model Discovery",
                        style = MaterialTheme.typography.titleSmall,
                        color = JarvisPalette.CyanBright,
                    )
                    Text(
                        text = "Discovers free models across Z.ai, NVIDIA NIM, OpenCode Zen, Agnes AI, Gemini, OpenRouter, Groq, Cerebras, SambaNova, Mistral, GitHub Models, SiliconFlow & more.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                    )
                }
                Button(
                    onClick = { viewModel.fetchAllAvailableModels() },
                    enabled = !state.fetchingModels,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (state.fetchingModels) "Fetching..." else "Fetch Models")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.freeModelsOnly,
                    onCheckedChange = viewModel::setFreeModelsOnly,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Show Free Models only (${filteredModels.size} available)",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.Ice,
                )
            }

            if (providerFilters.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(providerFilters, key = { "filter_$it" }) { filterName ->
                        FilterChip(
                            selected = selectedProviderFilter == filterName,
                            onClick = { selectedProviderFilter = filterName },
                            label = { Text(filterName) },
                        )
                    }
                }
            }

            filteredModels.take(24).forEach { model ->
                GlassPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectQuickModel(model.providerLabel, model.id)
                        },
                    corner = 14.dp,
                    accent = if (model.id == state.defaultProviderModel) JarvisPalette.CyanBright else JarvisPalette.Cyan,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = JarvisPalette.Ice,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${model.providerLabel} · ${model.id}",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.TextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (model.isFree) {
                            HudPill(text = "FREE", color = JarvisPalette.Success, filled = true)
                        }
                        if (model.supportsTools) {
                            Spacer(Modifier.width(4.dp))
                            HudPill(text = "TOOLS", color = JarvisPalette.CyanBright)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
            TextButton(onClick = onDismiss) {
                Text(text = "Dismiss", color = content)
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderSettings,
    index: Int,
    lastIndex: Int,
    isDefault: Boolean,
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var model by remember(provider.id, provider.model) { mutableStateOf(provider.model) }
    var apiKey by remember(provider.id) { mutableStateOf("") }
    var revealKey by remember(provider.id) { mutableStateOf(false) }
    var showRemoveDialog by remember(provider.id) { mutableStateOf(false) }
    val presets = remember(provider.kind) { presetsFor(provider.kind) }
    val matchedPreset = remember(name, baseUrl) {
        com.jarvis.assistant.settings.findPresetForProvider(name, baseUrl)
    }
    val draft = provider.copy(
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (matchedPreset?.isCompletelyFree == true) {
                            Spacer(Modifier.width(8.dp))
                            HudPill(
                                text = "100% FREE API",
                                color = JarvisPalette.Success,
                                filled = true,
                            )
                        }
                    }
                    Text(
                        text = buildString {
                            append(provider.kind.label)
                            if (!matchedPreset?.apiKeyPortal.isNullOrBlank()) {
                                append(" · Key: ${matchedPreset?.apiKeyPortal}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!matchedPreset?.note.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = matchedPreset?.note.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.CyanBright.copy(alpha = 0.85f),
                        )
                    }
                    if (isDefault) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = "Default",
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = { viewModel.setProviderEnabled(provider.id, it) },
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Provider name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presets, key = { it.label }) { preset ->
                    AssistChip(
                        onClick = {
                            name = preset.name
                            baseUrl = preset.baseUrl
                            model = preset.model
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            // Live discovered models for this provider (prioritizing models matching this provider)
            if (state.discoveredModels.isNotEmpty()) {
                val matchingModels = remember(state.discoveredModels, state.freeModelsOnly, name, matchedPreset) {
                    val targetLabel = matchedPreset?.name ?: name
                    val providerSpecific = state.discoveredModels.filter {
                        (!state.freeModelsOnly || it.isFree) &&
                            it.providerLabel.equals(targetLabel, ignoreCase = true)
                    }
                    if (providerSpecific.isNotEmpty()) {
                        providerSpecific.take(12)
                    } else {
                        state.discoveredModels.filter { !state.freeModelsOnly || it.isFree }.take(10)
                    }
                }
                if (matchingModels.isNotEmpty()) {
                    Text(
                        text = "Discovered models (tap to apply):",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.CyanBright,
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(matchingModels, key = { "disc_${provider.id}_${it.providerLabel}_${it.id}" }) { disc ->
                            AssistChip(
                                onClick = { model = disc.id },
                                label = {
                                    Text(
                                        text = if (disc.isFree) "${disc.id} (Free)" else disc.id,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (provider.hasSavedKey) "API key (saved)" else "API key")
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (revealKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(
                        onClick = { revealKey = !revealKey },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(
                            text = if (revealKey) "Hide" else "Show",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
            if (provider.hasSavedKey) {
                Text(
                    text = "A key is saved. Leave the field blank to keep it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "Stored encrypted with AES-GCM and the Android Keystore. Never written to logs.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { viewModel.saveProvider(draft, apiKey) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Save")
                }
                TextButton(onClick = { viewModel.testProvider(draft, apiKey) }) {
                    Text("Test")
                }
                TextButton(
                    onClick = { viewModel.fetchModelsForProvider(draft, baseUrl, apiKey) },
                    enabled = !state.fetchingModels,
                ) {
                    Text(if (state.fetchingModels) "Fetching..." else "Fetch Free Models")
                }
                if (!isDefault) {
                    TextButton(onClick = { viewModel.setDefaultProvider(provider.id) }) {
                        Text("Default")
                    }
                }
                TextButton(
                    onClick = { viewModel.moveProvider(provider.id, -1) },
                    enabled = index > 0,
                ) {
                    Text("Up")
                }
                TextButton(
                    onClick = { viewModel.moveProvider(provider.id, 1) },
                    enabled = index < lastIndex,
                ) {
                    Text("Down")
                }
                TextButton(
                    onClick = { showRemoveDialog = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Remove")
                }
            }

            when (val test = state.providerTests[provider.id]) {
                is ProviderTestState.Testing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "Testing connection...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is ProviderTestState.Success -> {
                    Text(
                        text = test.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is ProviderTestState.Failure -> {
                    Text(
                        text = test.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                null -> Unit
            }
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove ${provider.name}?") },
            text = {
                Text("Its saved key and settings will be deleted from this phone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeProvider(provider.id)
                        showRemoveDialog = false
                    },
                ) {
                    Text(
                        text = "Remove",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun PersonaEditor(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    val profile = state.settings.profile
    val persona = state.settings.persona

    var name by remember(profile.name) { mutableStateOf(profile.name) }
    var title by remember(profile.title) { mutableStateOf(profile.title) }
    var occupation by remember(profile.occupation) { mutableStateOf(profile.occupation) }
    var preferences by remember(profile.preferences) { mutableStateOf(profile.preferences) }
    var tone by remember(persona.tone) { mutableStateOf(persona.tone) }
    var prompt by remember(persona.customPrompt) { mutableStateOf(persona.customPrompt.orEmpty()) }
    var showDefaultPrompt by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = "Owner profile",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Preferred title") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = occupation,
                onValueChange = { occupation = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Occupation (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = preferences,
                onValueChange = { preferences = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Preferences (optional)") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = {
                    viewModel.saveProfile(
                        OwnerProfile(
                            name = name,
                            title = title,
                            occupation = occupation,
                            preferences = preferences,
                        ),
                    )
                },
                enabled = !state.onboardingBusy,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Save profile")
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Text(
                text = "Voice and prompt",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = tone,
                onValueChange = { tone = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tone") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Custom persona prompt (optional)") },
                minLines = 8,
                maxLines = 14,
                shape = RoundedCornerShape(14.dp),
            )
            Text(
                text = "Leave blank to use the built-in Jarvis prompt. The owner profile, current context " +
                    "and non-negotiable safety rules are always added and cannot be edited away.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        viewModel.savePersona(
                            tone = tone,
                            customPrompt = prompt.ifBlank { null },
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Save persona")
                }
                TextButton(
                    onClick = {
                        prompt = ""
                        viewModel.resetPersonaPrompt(tone)
                    },
                ) {
                    Text("Use default")
                }
                if (state.defaultPersonaPrompt.isNotBlank()) {
                    TextButton(onClick = { showDefaultPrompt = !showDefaultPrompt }) {
                        Text(if (showDefaultPrompt) "Hide default" else "View default")
                    }
                }
            }

            if (showDefaultPrompt && state.defaultPersonaPrompt.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = state.defaultPersonaPrompt,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (ProviderKind, String, String, String, String) -> Unit,
) {
    var kind by remember { mutableStateOf(ProviderKind.OPENAI_COMPATIBLE) }
    var preset by remember { mutableStateOf(defaultPresetFor(kind)) }
    var name by remember { mutableStateOf(preset.name) }
    var baseUrl by remember { mutableStateOf(preset.baseUrl) }
    var model by remember { mutableStateOf(preset.model) }
    var apiKey by remember { mutableStateOf("") }

    fun applyPreset(next: ProviderPreset) {
        kind = next.kind
        preset = next
        name = next.name
        baseUrl = next.baseUrl
        model = next.model
    }

    val valid = name.isNotBlank() &&
        (baseUrl.trim().startsWith("http://") || baseUrl.trim().startsWith("https://")) &&
        model.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Choose a preset, or enter any OpenAI-compatible endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ProviderKind.entries.toList(), key = { it.name }) { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { applyPreset(defaultPresetFor(option)) },
                            label = { Text(option.label) },
                        )
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presetsFor(kind), key = { it.label }) { option ->
                        AssistChip(
                            onClick = { applyPreset(option) },
                            label = { Text(option.label) },
                        )
                    }
                }

                if (preset.note.isNotBlank()) {
                    Text(
                        text = preset.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Provider name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    text = "You can add the key now or later. It is encrypted with the Android Keystore.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        kind,
                        name.trim(),
                        baseUrl.trim(),
                        model.trim(),
                        apiKey.trim(),
                    )
                },
                enabled = valid,
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun VoiceSettingsCard(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    val voice = state.settings.voice
    var provider by remember(voice.provider) { mutableStateOf(voice.provider) }
    var voiceId by remember(voice.provider, voice.voiceId, voice.fishVoiceId, voice.elevenLabsVoiceId) {
        mutableStateOf(
            when (voice.provider) {
                VoiceProvider.FISH_AUDIO -> voice.fishVoiceId.ifBlank { voice.voiceId }
                VoiceProvider.ELEVENLABS -> voice.elevenLabsVoiceId.ifBlank { voice.voiceId }
                VoiceProvider.SYSTEM -> voice.voiceId
            },
        )
    }
    var apiKey by remember(voice.provider) { mutableStateOf("") }
    var speed by remember(voice.speed) { mutableStateOf(voice.speed) }
    var fallback by remember(voice.fallbackToSystem) { mutableStateOf(voice.fallbackToSystem) }
    var language by remember(voice.language) { mutableStateOf(voice.language) }
    var continuousListening by remember(voice.continuousListening) { mutableStateOf(voice.continuousListening) }
    var revealKey by remember { mutableStateOf(false) }

    val hasKeyForProvider = voice.hasSavedKeyFor(provider)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Voice", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Choose the voice Jarvis uses. Fish Audio and ElevenLabs stream ultra-low-latency 16-bit PCM audio; system voice is the automatic fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VoiceProvider.entries.toList(), key = { it.name }) { option ->
                    FilterChip(
                        selected = provider == option,
                        onClick = {
                            provider = option
                            voiceId = when (option) {
                                VoiceProvider.FISH_AUDIO -> voice.fishVoiceId.ifBlank { voice.voiceId }
                                VoiceProvider.ELEVENLABS -> voice.elevenLabsVoiceId.ifBlank { voice.voiceId }
                                VoiceProvider.SYSTEM -> voice.voiceId
                            }
                        },
                        label = { Text(option.label) },
                    )
                }
            }

            if (provider != VoiceProvider.SYSTEM) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (hasKeyForProvider) {
                                "${provider.label} API key (saved permanently)"
                            } else {
                                "${provider.label} API key"
                            },
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { revealKey = !revealKey }) {
                            Text(if (revealKey) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
                if (hasKeyForProvider) {
                    Text(
                        text = "Your ${provider.label} key is encrypted and backed up in DataStore. Leave blank to keep it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            OutlinedTextField(
                value = voiceId,
                onValueChange = { voiceId = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (provider == VoiceProvider.FISH_AUDIO) {
                            "Fish voice reference id (optional)"
                        } else if (provider == VoiceProvider.ELEVENLABS) {
                            "ElevenLabs voice id"
                        } else {
                            "Voice id"
                        },
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Language tag (BCP-47)") },
                placeholder = { Text("en-US") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            Column {
                Text("Speed: ${"%.2f".format(speed)}", style = MaterialTheme.typography.labelSmall)
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.0f)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fallback, onCheckedChange = { fallback = it })
                Spacer(Modifier.width(8.dp))
                Text("Fall back to system voice", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = continuousListening, onCheckedChange = { continuousListening = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Always-on microphone while app is open", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Keep listening continuously on launch and after Jarvis finishes speaking",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.voiceStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.CyanBright,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.saveVoiceSettings(
                        provider = provider,
                        voiceId = voiceId,
                        speed = speed,
                        fallbackToSystem = fallback,
                        language = language,
                        apiKey = apiKey,
                        continuousListening = continuousListening,
                    )
                    apiKey = ""
                }) { Text("Save voice") }
                TextButton(onClick = {
                    viewModel.testVoiceWithDraft(
                        provider = provider,
                        voiceId = voiceId,
                        speed = speed,
                        fallbackToSystem = fallback,
                        language = language,
                        apiKey = apiKey,
                        continuousListening = continuousListening,
                    )
                    apiKey = ""
                }) { Text("Test voice") }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "J.A.R.V.I.S. v3.1.0 · Engineered by Octavian",
                style = MaterialTheme.typography.titleSmall,
                color = JarvisPalette.CyanBright,
            )
            Text(
                text = "Developer, Creator & Chief Architect: Octavian (immutable core identity).",
                style = MaterialTheme.typography.labelMedium,
                color = JarvisPalette.Ice,
            )
            Text(
                text = "Streaming multi-provider AI brain, live free model discovery, " +
                    "Fish Audio & ElevenLabs neural TTS, direct YouTube video playback, " +
                    "persistent foreground & background microphone, autonomous wellbeing & " +
                    "thought engine, Tier 1–3 phone & screen automation, and Hacking-Proof Security Shield.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Zero secrets in repo, BuildConfig or logs. Protected by AES-256-GCM Android Keystore and prompt-injection firewall.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedbackCard() {
    val context = LocalContext.current
    val emails = listOf(
        "sahilsahilimtiyaz@gmail.com",
        "octavianfx96@gmail.com",
        "lorcyfx@gmail.com",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Feedback",
                style = MaterialTheme.typography.titleSmall,
                color = JarvisPalette.CyanBright,
            )
            Text(
                text = "Send bug reports, ideas or questions to any of these addresses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            emails.forEach { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.Ice,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }
    }
}
