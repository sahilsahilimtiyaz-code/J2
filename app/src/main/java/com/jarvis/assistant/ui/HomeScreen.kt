package com.jarvis.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.brain.LocalDownloadState
import com.jarvis.assistant.brain.LocalModelCatalog
import com.jarvis.assistant.brain.LocalModelDownloadStatus
import com.jarvis.assistant.brain.ProviderKind
import com.jarvis.assistant.tools.DeviceStatus
import com.jarvis.assistant.tools.readDeviceStatus
import com.jarvis.assistant.ui.components.ActionTile
import com.jarvis.assistant.ui.components.ArcReactor
import com.jarvis.assistant.ui.components.EnergyBar
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HolographicGlobe
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.components.HudIconButton
import com.jarvis.assistant.ui.components.HudPill
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.JarvisBottomBar
import com.jarvis.assistant.ui.components.JarvisTopBar
import com.jarvis.assistant.ui.components.ParticleWaveCanvas
import com.jarvis.assistant.ui.components.PulseRings
import com.jarvis.assistant.ui.components.ReactorGlyph
import com.jarvis.assistant.ui.components.StatusDot
import com.jarvis.assistant.ui.components.VoiceWave
import com.jarvis.assistant.ui.theme.HudNumerals
import com.jarvis.assistant.ui.theme.JarvisPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Covers:
 * - Screen 2: Home Screen (AI MODEL pill bar, SYSTEM/CPU/GPU + MEMORY/STORAGE HUD readouts,
 *   central spinning ArcReactor with 3D holographic side panels, greeting card, and 4 quick tiles:
 *   Web Search, Generate, Tools, Memory).
 * - Screen 3: Voice Assistant Overlay/Mode ("Listening...", top waveform, 3D rotating HolographicGlobe,
 *   "How can I help you, sir?", Web Search / YouTube / Summarize / Analyze chips, and glowing mic button).
 * - Screen 4: AI Response / Chat View (User bubble, glassmorphic J.A.R.V.I.S. response card,
 *   Sources/Tool chips, embedded ParticleWaveCanvas, Regenerate / Copy / + More buttons, and
 *   Ask anything... composer bar).
 * - Persistent in-app Screen Automation Stop Banner when Jarvis is controlling the screen.
 */
@Composable
fun HomeScreen(
    state: JarvisUiState,
    onInputChange: (String) -> Unit,
    onSend: (String?) -> Unit,
    onStop: () -> Unit,
    onStopScreenAutomation: () -> Unit,
    onToggleMic: () -> Unit,
    onOpenVoiceOverlay: () -> Unit = {},
    onCloseVoiceOverlay: () -> Unit,
    onSelectTab: (JarvisTab) -> Unit,
    onOpenQuickPanel: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
    onFetchModels: () -> Unit,
    onSetFreeModelsOnly: (Boolean) -> Unit,
    onSelectQuickModel: (String, String) -> Unit,
    onDownloadLocalModel: (String) -> Unit = {},
    onPauseLocalModel: (String) -> Unit = {},
    onDeleteLocalModel: (String) -> Unit = {},
    onSelectLocalModel: (String) -> Unit = {},
    onCancelPlan: () -> Unit = {},
    onDismissPlan: () -> Unit = {},
    onRetryFailedPlanSteps: () -> Unit = {},
    onActOnSharedContent: (String) -> Unit = {},
    onClearSharedContent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var deviceStatus by remember { mutableStateOf<DeviceStatus?>(null) }
    var showModelModal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            deviceStatus = withContext(Dispatchers.IO) {
                runCatching { readDeviceStatus(context) }.getOrNull()
            }
            delay(5000)
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            runCatching {
                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }
        }
    }

    // Streaming must not re-animate the whole transcript on every token. Only
    // follow the reply while the owner is already at the bottom, so messages
    // never appear to slide away and vanish while Jarvis is talking.
    LaunchedEffect(state.messages.lastOrNull()?.text?.length) {
        if (!state.generating) return@LaunchedEffect
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (total > 0 && lastVisible >= total - 3) {
            runCatching { listState.scrollToItem(total - 1) }
        }
    }

    val voiceModeActive = state.voiceOverlayOpen

    if (showModelModal) {
        ModelPickerModal(
            state = state,
            onDismiss = { showModelModal = false },
            onFetchModels = onFetchModels,
            onSetFreeModelsOnly = onSetFreeModelsOnly,
            onSelectModel = { providerId, modelId ->
                onSelectQuickModel(providerId, modelId)
                showModelModal = false
            },
            onDownloadLocalModel = onDownloadLocalModel,
            onPauseLocalModel = onPauseLocalModel,
            onDeleteLocalModel = onDeleteLocalModel,
            onSelectLocalModel = { modelId ->
                onSelectLocalModel(modelId)
                showModelModal = false
            },
            onOpenProviderSettings = {
                showModelModal = false
                onSelectTab(JarvisTab.SETTINGS)
            },
        )
    }

    JarvisBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            JarvisTopBar(
                status = when {
                    state.screenAutomationActive -> "SCREEN CONTROL"
                    voiceModeActive -> null
                    state.toolActivity != null -> "MISSION ACTIVE"
                    state.generating -> "PROCESSING"
                    state.speaking -> "SPEAKING"
                    state.listening -> "LISTENING LIVE"
                    else -> "ONLINE"
                },
                statusColor = when {
                    state.screenAutomationActive || state.toolActivity != null -> JarvisPalette.Amber
                    state.listening -> JarvisPalette.CyanBright
                    state.generating -> JarvisPalette.Amber
                    state.speaking -> JarvisPalette.Cyan
                    else -> JarvisPalette.Success
                },
                trailing = {
                    HudIconButton(
                        glyph = HudGlyph.GEAR,
                        contentDescription = "Settings",
                        onClick = { onSelectTab(JarvisTab.SETTINGS) },
                        size = 36.dp,
                    )
                },
            )

            // Visible Stop Control Banner whenever multi-step screen automation is running
            AnimatedVisibility(
                visible = state.screenAutomationActive,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 },
            ) {
                ScreenAutomationStopBanner(
                    step = state.screenAutomationStep,
                    maxSteps = state.screenAutomationMaxSteps,
                    statusText = state.screenAutomationStatus ?: "Jarvis is controlling the screen",
                    onStop = onStopScreenAutomation,
                )
            }

            if (voiceModeActive) {
                // Screen 3 — Voice Assistant Mode
                VoiceAssistantView(
                    state = state,
                    onToggleMic = onToggleMic,
                    onCloseVoiceOverlay = onCloseVoiceOverlay,
                    onQuickPrompt = { prompt -> onSend(prompt) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                // Screen 2 (Home HUD) + Screen 4 (AI Response / Chat)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "home_hud") {
                        HomeHudHero(
                            state = state,
                            deviceStatus = deviceStatus,
                            onOpenModelPicker = {
                                onFetchModels()
                                showModelModal = true
                            },
                            onOpenSettings = { onSelectTab(JarvisTab.SETTINGS) },
                            onOpenVoiceOverlay = onOpenVoiceOverlay,
                            onToggleMic = onToggleMic,
                            onSelectTab = onSelectTab,
                            onQuickPrompt = { prompt -> onSend(prompt) },
                        )
                    }

                    state.sharedPayload?.let { payload ->
                        item(key = "shared_payload_banner") {
                            SharedContentBanner(
                                payload = payload,
                                onAction = onActOnSharedContent,
                                onDismiss = onClearSharedContent,
                            )
                        }
                    }

                    state.activePlan?.let { plan ->
                        item(key = "active_task_plan") {
                            TaskExecutionTimelineCard(
                                plan = plan,
                                onCancel = onCancelPlan,
                                onRetryFailed = onRetryFailedPlanSteps,
                                onDismiss = onDismissPlan,
                            )
                        }
                    }

                    items(state.messages, key = { it.id }) { message ->
                        MessageCard(
                            message = message,
                            isLastAssistant = message.id == state.messages.lastOrNull()?.id,
                            onRetry = onRetry,
                            onQuickPrompt = { prompt -> onSend(prompt) },
                        )
                    }

                    item(key = "tail") { Spacer(Modifier.height(4.dp)) }
                }

                AnimatedVisibility(
                    visible = state.error != null || state.notice != null,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 },
                    exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 2 },
                ) {
                    BannerStrip(
                        error = state.error,
                        notice = state.notice,
                        onDismissError = onDismissError,
                        onDismissNotice = onDismissNotice,
                    )
                }

                AnimatedVisibility(
                    visible = state.conversationMode,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(150)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisPalette.CyanBright.copy(alpha = 0.12f))
                            .border(1.dp, JarvisPalette.CyanBright.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(color = JarvisPalette.CyanBright, pulsing = true, size = 6.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "DUPLEX CONVERSATION ACTIVE · CONTINUOUS VOICE LINK",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.CyanBright,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                ComposerBar(
                    state = state,
                    onInputChange = onInputChange,
                    onSend = { onSend(null) },
                    onStop = onStop,
                    onToggleMic = onToggleMic,
                )
            }

            if (!voiceModeActive) {
                JarvisBottomBar(
                    selected = JarvisTab.HOME,
                    onSelect = onSelectTab,
                    listening = state.listening,
                    busy = state.generating || state.speaking || state.screenAutomationActive,
                    onMic = onToggleMic,
                )
            }
        }
    }
}

@Composable
private fun ScreenAutomationStopBanner(
    step: Int,
    maxSteps: Int,
    statusText: String,
    onStop: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        accent = JarvisPalette.Danger,
        corner = 18.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = JarvisPalette.Danger, pulsing = true, size = 8.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "JARVIS IS CONTROLLING THE SCREEN · STEP $step/$maxSteps",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.Amber,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(JarvisPalette.Danger)
                    .clickable(onClick = onStop)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudIcon(
                        glyph = HudGlyph.STOP,
                        color = Color.White,
                        modifier = Modifier.size(14.dp),
                        weight = 2.dp,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "STOP",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedContentBanner(
    payload: com.jarvis.assistant.brain.SharedContentPayload,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = JarvisPalette.CyanBright,
        corner = 18.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = JarvisPalette.CyanBright, pulsing = true, size = 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "SHARED WITH J.A.R.V.I.S. · ${payload.mimeType.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.CyanBright,
                    letterSpacing = 1.3.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextDim,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            if (payload.title.isNotBlank()) {
                Text(
                    text = payload.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = JarvisPalette.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val bodyPreview = payload.textOrUrl.ifBlank { payload.extractedPreview }
            if (bodyPreview.isNotBlank()) {
                Text(
                    text = bodyPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceSuggestionChip("Summarize") {
                    onAction("Summarize this shared content concisely and highlight the key points.")
                }
                VoiceSuggestionChip("Action Items") {
                    onAction("Extract actionable tasks, dates, or reminders from this shared content.")
                }
                VoiceSuggestionChip("Save to Memory") {
                    onAction("Save the key facts from this shared content into my long-term memory.")
                }
                VoiceSuggestionChip("Fact Check") {
                    onAction("Search the web to verify and expand on this shared content.")
                }
            }
        }
    }
}

@Composable
private fun TaskExecutionTimelineCard(
    plan: com.jarvis.assistant.brain.TaskPlan,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasRunning = plan.steps.any { it.status == com.jarvis.assistant.brain.PlanStepStatus.RUNNING }
    val hasFailed = plan.steps.any { it.status == com.jarvis.assistant.brain.PlanStepStatus.FAILED }
    val completedCount = plan.steps.count { it.status == com.jarvis.assistant.brain.PlanStepStatus.COMPLETED }
    val progress = if (plan.steps.isNotEmpty()) {
        completedCount.toFloat() / plan.steps.size.toFloat()
    } else {
        0f
    }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = when {
            hasFailed -> JarvisPalette.Amber
            hasRunning -> JarvisPalette.CyanBright
            else -> JarvisPalette.Success
        },
        corner = 20.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    color = when {
                        hasFailed -> JarvisPalette.Amber
                        hasRunning -> JarvisPalette.CyanBright
                        else -> JarvisPalette.Success
                    },
                    pulsing = hasRunning,
                    size = 8.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "MISSION PLANNER · $completedCount/${plan.steps.size} COMPLETE",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.CyanBright,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (hasRunning) {
                    Text(
                        text = "CANCEL",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.Danger,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onCancel),
                    )
                } else {
                    Text(
                        text = "DISMISS",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                }
            }

            Text(
                text = plan.objective,
                style = MaterialTheme.typography.titleSmall,
                color = JarvisPalette.Ice,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            EnergyBar(
                progress = progress.coerceIn(0.05f, 1f),
                modifier = Modifier.fillMaxWidth(),
                color = if (hasFailed) JarvisPalette.Amber else JarvisPalette.CyanBright,
                height = 5.dp,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                plan.steps.forEachIndexed { idx, step ->
                    val statusColor = when (step.status) {
                        com.jarvis.assistant.brain.PlanStepStatus.COMPLETED -> JarvisPalette.Success
                        com.jarvis.assistant.brain.PlanStepStatus.RUNNING -> JarvisPalette.CyanBright
                        com.jarvis.assistant.brain.PlanStepStatus.AWAITING_CONFIRMATION -> JarvisPalette.Amber
                        com.jarvis.assistant.brain.PlanStepStatus.FAILED -> JarvisPalette.Danger
                        com.jarvis.assistant.brain.PlanStepStatus.SKIPPED -> JarvisPalette.TextDim
                        com.jarvis.assistant.brain.PlanStepStatus.PENDING -> JarvisPalette.TextDim
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(JarvisPalette.Deep.copy(alpha = 0.65f))
                            .border(1.dp, statusColor.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(
                            color = statusColor,
                            pulsing = step.status == com.jarvis.assistant.brain.PlanStepStatus.RUNNING,
                            size = 6.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${idx + 1}. ${step.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = JarvisPalette.Ice,
                                fontWeight = FontWeight.Medium,
                            )
                            if (step.output.isNotBlank()) {
                                Text(
                                    text = step.output,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JarvisPalette.TextDim,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        HudPill(
                            text = if (step.verified && step.status == com.jarvis.assistant.brain.PlanStepStatus.COMPLETED) {
                                "verified"
                            } else {
                                step.status.name.lowercase()
                            },
                            color = statusColor,
                            filled = step.status == com.jarvis.assistant.brain.PlanStepStatus.FAILED,
                        )
                    }
                }
            }

            if (plan.summary.isNotBlank()) {
                Text(
                    text = plan.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.CyanBright,
                )
            }

            if (hasFailed && !hasRunning) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    VoiceSuggestionChip("Retry Failed Steps", onClick = onRetryFailed)
                }
            }
        }
    }
}

/**
 * Screen 2 — Home Screen Hero matching the reference image:
 * - AI MODEL selector bar (`AI MODEL` / model name + wave icon + gear button)
 * - Left telemetry column (`SYSTEM STABLE`, `CPU 12%`, `GPU 8%`) and Right telemetry column (`MEMORY 47%`, `STORAGE 87%`)
 * - Central spinning Iron Man Neural Arc Reactor with 3D angled holographic display panels flanking left & right
 * - Greeting card (`"Good morning, sir."` / `"All systems are online and at your service."` + live speech transcript)
 * - 4 Quick Action tiles (`Web Search`, `Generate`, `Tools`, `Memory`)
 */
@Composable
private fun HomeHudHero(
    state: JarvisUiState,
    deviceStatus: DeviceStatus?,
    onOpenModelPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoiceOverlay: () -> Unit,
    onToggleMic: () -> Unit,
    onSelectTab: (JarvisTab) -> Unit,
    onQuickPrompt: (String) -> Unit,
) {
    val memoryPct = deviceStatus?.memoryUsedPercent ?: 47
    val storagePct = deviceStatus?.storageUsedPercent ?: 87
    val cpuPct = remember(state.generating, state.listening, state.speaking) {
        when {
            state.generating -> 38
            state.speaking -> 26
            state.listening -> 18
            else -> 12
        }
    }
    val gpuPct = remember(state.generating, state.speaking) {
        when {
            state.generating -> 28
            state.speaking -> 19
            else -> 8
        }
    }

    val greetingHeadline = remember(state.settings.profile.title) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val period = when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        val title = state.settings.profile.title.ifBlank { "sir" }
        "$period, $title."
    }

    val latestGreetingSubtitle = state.messages
        .firstOrNull { it.author == ChatAuthor.JARVIS }
        ?.text
        ?.takeIf { state.messages.size <= 1 }
        ?: "All systems are online and at your service."

    val liveSubtitle = when {
        state.toolActivity != null -> state.toolActivity
        state.partialTranscript.isNotBlank() -> "“${state.partialTranscript}”"
        else -> latestGreetingSubtitle
    }

    val toolActive = state.toolActivity != null || state.screenAutomationActive

    Column(modifier = Modifier.fillMaxWidth()) {
        // AI MODEL selector pill bar + trailing gear
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                JarvisPalette.Panel.copy(alpha = 0.94f),
                                JarvisPalette.Deep.copy(alpha = 0.90f),
                            ),
                        ),
                    )
                    .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.32f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenModelPicker)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudIcon(
                    glyph = HudGlyph.ROCKET,
                    color = JarvisPalette.CyanBright,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI MODEL",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim,
                        fontSize = 9.sp,
                        letterSpacing = 1.6.sp,
                    )
                    Text(
                        text = state.defaultProviderModel,
                        style = MaterialTheme.typography.titleSmall,
                        color = JarvisPalette.Ice,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HudIcon(
                    glyph = HudGlyph.WAVE,
                    color = JarvisPalette.CyanBright,
                    modifier = Modifier.size(18.dp),
                )
            }

            HudIconButton(
                glyph = HudGlyph.GEAR,
                contentDescription = "Model settings",
                onClick = onOpenSettings,
                size = 42.dp,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Central Reactor + Left/Right Telemetry + 3D Perspective Side Panels
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(286.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 3D perspective holographic side screens behind the reactor
            HolographicSidePanels(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
            )

            // Left HUD telemetry column: SYSTEM STABLE, CPU, GPU
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MiniTelemetryBlock(
                    title = "SYSTEM",
                    value = when {
                        toolActive -> "EXEC"
                        state.speaking -> "VOCAL"
                        state.generating -> "NEURAL"
                        state.listening -> "ACTIVE"
                        else -> "STABLE"
                    },
                    valueColor = if (toolActive) JarvisPalette.Amber else JarvisPalette.CyanBright,
                )
                MiniTelemetryBlock(
                    title = "CPU",
                    value = "$cpuPct%",
                    valueColor = JarvisPalette.Ice,
                )
                MiniTelemetryBlock(
                    title = "GPU",
                    value = "$gpuPct%",
                    valueColor = JarvisPalette.Ice,
                )
            }

            // Right HUD telemetry column: MEMORY, STORAGE
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(96.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RightBarTelemetry(
                    label = "MEMORY",
                    percent = memoryPct,
                )
                RightBarTelemetry(
                    label = "STORAGE",
                    percent = storagePct,
                )
            }

            // Central 3D Brahma Morphing Wireframe Orb (touch physics + tap to open Voice Overlay)
            Box(
                modifier = Modifier.size(268.dp),
                contentAlignment = Alignment.Center,
            ) {
                ArcReactor(
                    state = state.reactorState,
                    modifier = Modifier.fillMaxSize(),
                    audioLevel = state.audioLevel,
                    toolActive = toolActive,
                    onTap = onOpenVoiceOverlay,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Greeting glass card ("Good morning, sir." / live partial transcript or status)
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            corner = 20.dp,
            accent = if (toolActive) JarvisPalette.Amber else JarvisPalette.CyanBright,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = greetingHeadline,
                    style = MaterialTheme.typography.titleMedium,
                    color = JarvisPalette.Ice,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = liveSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.partialTranscript.isNotBlank() || toolActive) {
                        JarvisPalette.CyanBright
                    } else {
                        JarvisPalette.TextDim
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 4 Quick Action Tiles: Web Search, Generate, Tools, Memory
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(
                glyph = HudGlyph.GLOBE,
                label = "Web Search",
                onClick = { onQuickPrompt("Search the web for today's top technology news") },
                modifier = Modifier.weight(1f),
                accent = JarvisPalette.CyanBright,
            )
            ActionTile(
                glyph = HudGlyph.SPARKLES,
                label = "Generate",
                onClick = { onQuickPrompt("Explain quantum computing clearly in a few sentences") },
                modifier = Modifier.weight(1f),
                accent = JarvisPalette.Cyan,
            )
            ActionTile(
                glyph = HudGlyph.WRENCH,
                label = "Tools",
                onClick = { onSelectTab(JarvisTab.TOOLS) },
                modifier = Modifier.weight(1f),
                accent = JarvisPalette.CyanBright,
            )
            ActionTile(
                glyph = HudGlyph.BRAIN,
                label = "Memory",
                onClick = { onSelectTab(JarvisTab.MEMORY) },
                modifier = Modifier.weight(1f),
                accent = JarvisPalette.Cyan,
            )
        }
    }
}

@Composable
private fun MiniTelemetryBlock(
    title: String,
    value: String,
    valueColor: Color,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = JarvisPalette.CyanBright, pulsing = false, size = 5.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = JarvisPalette.TextDim,
                fontSize = 9.sp,
                letterSpacing = 1.4.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontFamily = HudNumerals.fontFamily,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun RightBarTelemetry(
    label: String,
    percent: Int,
) {
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.TextDim,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(3.dp))
        EnergyBar(
            progress = (percent / 100f).coerceIn(0.08f, 1f),
            modifier = Modifier.fillMaxWidth(0.85f),
            color = JarvisPalette.CyanBright,
            height = 4.dp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = JarvisPalette.Ice,
            fontFamily = HudNumerals.fontFamily,
        )
    }
}

/**
 * Draws the angled 3D perspective holographic side screens flanking the lower left and right
 * of the Arc Reactor on Screen 2 (Home Screen).
 */
@Composable
private fun HolographicSidePanels(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "holographic_panels")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "panel_sweep",
    )
    val glow by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "panel_glow",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun drawPerspectiveCard(
            x1: Float,
            y1Top: Float,
            y1Bottom: Float,
            x2: Float,
            y2Top: Float,
            y2Bottom: Float,
        ) {
            val path = Path().apply {
                moveTo(x1, y1Top)
                lineTo(x2, y2Top)
                lineTo(x2, y2Bottom)
                lineTo(x1, y1Bottom)
                close()
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        JarvisPalette.Cyan.copy(alpha = 0.17f * glow),
                        JarvisPalette.Panel.copy(alpha = 0.58f),
                    ),
                ),
            )
            drawPath(
                path = path,
                color = JarvisPalette.CyanBright.copy(alpha = 0.40f * glow),
                style = Stroke(width = 1.1.dp.toPx()),
            )

            // Internal HUD chart lines.
            val midY1 = (y1Top + y1Bottom) * 0.52f
            val midY2 = (y2Top + y2Bottom) * 0.48f
            drawLine(
                color = JarvisPalette.CyanBright.copy(alpha = 0.45f),
                start = Offset(x1 + (x2 - x1) * 0.15f, midY1),
                end = Offset(x1 + (x2 - x1) * 0.85f, midY2),
                strokeWidth = 1.2.dp.toPx(),
            )
            drawLine(
                color = JarvisPalette.Amber.copy(alpha = 0.28f * glow),
                start = Offset(x1 + (x2 - x1) * 0.22f, midY1 + 10.dp.toPx()),
                end = Offset(x1 + (x2 - x1) * 0.72f, midY2 + 10.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )

            // Travelling holographic scanline across the angled glass.
            val lane = ((sweep + (x1 / w) * 0.31f) % 1f)
            val scanY = y1Top + (y1Bottom - y1Top) * lane
            val scanYFar = y2Top + (y2Bottom - y2Top) * lane
            drawLine(
                color = JarvisPalette.CyanBright.copy(alpha = 0.36f * glow),
                start = Offset(x1 + (x2 - x1) * 0.12f, scanY),
                end = Offset(x1 + (x2 - x1) * 0.88f, scanYFar),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = JarvisPalette.CyanBright.copy(alpha = 0.10f * glow),
                start = Offset(x1 + (x2 - x1) * 0.12f, scanY - 4.dp.toPx()),
                end = Offset(x1 + (x2 - x1) * 0.88f, scanYFar - 4.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
            )
        }

        // Left outer and inner perspective screens
        drawPerspectiveCard(
            x1 = w * 0.01f,
            y1Top = h * 0.10f,
            y1Bottom = h * 0.78f,
            x2 = w * 0.20f,
            y2Top = h * 0.22f,
            y2Bottom = h * 0.88f,
        )
        drawPerspectiveCard(
            x1 = w * 0.14f,
            y1Top = h * 0.30f,
            y1Bottom = h * 0.92f,
            x2 = w * 0.31f,
            y2Top = h * 0.40f,
            y2Bottom = h * 0.98f,
        )

        // Right inner and outer perspective screens
        drawPerspectiveCard(
            x1 = w * 0.69f,
            y1Top = h * 0.40f,
            y1Bottom = h * 0.98f,
            x2 = w * 0.86f,
            y2Top = h * 0.30f,
            y2Bottom = h * 0.92f,
        )
        drawPerspectiveCard(
            x1 = w * 0.80f,
            y1Top = h * 0.22f,
            y1Bottom = h * 0.88f,
            x2 = w * 0.99f,
            y2Top = h * 0.10f,
            y2Bottom = h * 0.78f,
        )
    }
}

/**
 * Screen 3 — Voice Assistant Mode matching the reference image:
 * - "Listening..." subtitle in cyan
 * - Top horizontal voice waveform spectrum
 * - Central 3D rotating particle/wireframe HolographicGlobe with orbital rings
 * - Spoken prompt / transcript ("How can I help you, sir?")
 * - 4 quick suggestion pills: Web Search, YouTube, Summarize, Analyze
 * - Large circular glowing Microphone button with concentric pulse rings
 */
@Composable
private fun VoiceAssistantView(
    state: JarvisUiState,
    onToggleMic: () -> Unit,
    onCloseVoiceOverlay: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = state.settings.profile.title.ifBlank { "sir" }
    val toolActive = state.toolActivity != null || state.screenAutomationActive
    val promptLine = when {
        state.toolActivity != null -> state.toolActivity
        state.partialTranscript.isNotBlank() -> "“${state.partialTranscript}”"
        state.listening -> "“How can I help you, $title?”"
        state.speaking -> "Speaking..."
        else -> "“Tap the microphone or say a command, $title.”"
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0.84f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "voice_entrance",
    )

    Column(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .graphicsLayer {
                scaleX = entrance
                scaleY = entrance
                alpha = entrance.coerceIn(0f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    toolActive -> "Executing Mission..."
                    state.speaking -> "Speaking..."
                    state.generating -> "Synthesizing..."
                    state.listening -> "Listening..."
                    else -> "Voice Assistant"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (toolActive) JarvisPalette.Amber else JarvisPalette.CyanBright,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(10.dp))
            VoiceWave(
                active = state.listening || state.speaking || state.generating,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .height(44.dp),
                color = if (toolActive) JarvisPalette.Amber else JarvisPalette.CyanBright,
                bars = 41,
                audioLevel = state.audioLevel,
            )
        }

        HolographicGlobe(
            active = state.listening || state.speaking || state.generating || toolActive,
            modifier = Modifier.size(272.dp),
            audioLevel = state.audioLevel,
            toolActive = toolActive,
            onTap = onToggleMic,
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = promptLine,
                style = MaterialTheme.typography.titleMedium,
                color = JarvisPalette.Ice,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                VoiceSuggestionChip("Web Search") {
                    onQuickPrompt("Search the web for latest AI breakthroughs")
                }
                VoiceSuggestionChip("YouTube") {
                    onQuickPrompt("Open YouTube and search lo-fi hip hop")
                }
                VoiceSuggestionChip("Summarize") {
                    onQuickPrompt("Read and summarize my recent notifications")
                }
                VoiceSuggestionChip("Analyze") {
                    onQuickPrompt("Read my phone status and battery health")
                }
            }

            Spacer(Modifier.height(18.dp))

            // Large glowing circular microphone orb (toggles mic or returns to Home HUD)
            Box(
                modifier = Modifier.size(148.dp),
                contentAlignment = Alignment.Center,
            ) {
                PulseRings(
                    active = state.listening || state.speaking,
                    modifier = Modifier.fillMaxSize(),
                    color = if (state.listening) JarvisPalette.CyanBright else JarvisPalette.Amber,
                    rings = 3,
                )
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    JarvisPalette.CyanBright.copy(alpha = 0.42f),
                                    JarvisPalette.Panel.copy(alpha = 0.95f),
                                    JarvisPalette.Deep,
                                ),
                            ),
                        )
                        .border(2.dp, JarvisPalette.CyanBright, CircleShape)
                        .clickable(onClick = onToggleMic),
                    contentAlignment = Alignment.Center,
                ) {
                    HudIcon(
                        glyph = HudGlyph.MIC,
                        color = if (state.listening) Color.White else JarvisPalette.Amber,
                        modifier = Modifier.size(38.dp),
                        weight = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceSuggestionChip(
    label: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(JarvisPalette.Panel.copy(alpha = 0.85f))
            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.38f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = JarvisPalette.Ice.copy(alpha = 0.92f),
        )
    }
}

/**
 * Screen 4 — AI Response / Chat Card matching the reference image:
 * - User message bubble aligned right
 * - Glassmorphic J.A.R.V.I.S. response card with glowing header icon
 * - Sources / Tool chips row
 * - Embedded dynamic ParticleWaveCanvas visualization inside assistant cards
 * - Bottom action buttons: `[Regenerate]`, `[Copy]`, `[+ More]`
 */
@Composable
private fun MessageCard(
    message: ChatMessageUi,
    isLastAssistant: Boolean,
    onRetry: () -> Unit,
    onQuickPrompt: (String) -> Unit,
) {
    val context = LocalContext.current
    val isUser = message.author == ChatAuthor.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            val bubble = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomEnd = 7.dp,
                bottomStart = 22.dp,
            )
            Box(
                modifier = Modifier
                    .widthIn(max = 318.dp)
                    .clip(bubble)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                JarvisPalette.Cyan.copy(alpha = 0.92f),
                                JarvisPalette.Plasma.copy(alpha = 0.90f),
                            ),
                        ),
                    )
                    .border(1.dp, JarvisPalette.CyanBright.copy(alpha = 0.45f), bubble)
                    .padding(horizontal = 17.dp, vertical = 12.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
            }
        } else {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(0.98f),
                accent = if (message.isError) JarvisPalette.Danger else JarvisPalette.Cyan,
                corner = 22.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (message.isError) {
                                    JarvisPalette.Danger.copy(alpha = 0.14f)
                                } else {
                                    JarvisPalette.Cyan.copy(alpha = 0.16f)
                                },
                            )
                            .border(
                                1.dp,
                                if (message.isError) {
                                    JarvisPalette.Danger.copy(alpha = 0.55f)
                                } else {
                                    JarvisPalette.CyanBright.copy(alpha = 0.55f)
                                },
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ReactorGlyph(
                            modifier = Modifier.size(17.dp),
                            color = if (message.isError) JarvisPalette.Danger else JarvisPalette.CyanBright,
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "J.A.R.V.I.S.",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message.isError) JarvisPalette.Danger else JarvisPalette.Ice,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (message.providerName != null) {
                        HudPill(text = message.providerName, color = JarvisPalette.CyanDim)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = formatTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisPalette.TextDim.copy(alpha = 0.7f),
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = if (message.streaming) message.text + " \u258D" else message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.isError) {
                        JarvisPalette.Danger.copy(alpha = 0.95f)
                    } else {
                        JarvisPalette.Ice
                    },
                    lineHeight = 22.sp,
                )

                // Sources / Tools row matching Screen 4
                if (!message.isError && (message.tools.isNotEmpty() || message.text.length > 100)) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = JarvisPalette.Success, pulsing = false, size = 6.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (message.tools.isNotEmpty()) "Executed Tools" else "Sources & Context",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (message.tools.isNotEmpty()) {
                            message.tools.forEach { tool ->
                                SourceBadgeCard(
                                    kind = "Tool",
                                    title = tool.replace('_', ' '),
                                )
                            }
                        } else {
                            SourceBadgeCard(kind = "Memory", title = "Owner Profile")
                            SourceBadgeCard(kind = "Engine", title = message.providerName ?: "Neural Core")
                            SourceBadgeCard(kind = "System", title = "Live Context")
                        }
                    }
                }

                // Embedded 3D Particle Wave visualization on rich responses
                if (!message.isError && (isLastAssistant || message.text.length > 80)) {
                    Spacer(Modifier.height(12.dp))
                    ParticleWaveCanvas(
                        active = message.streaming,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.28f), RoundedCornerShape(14.dp)),
                    )
                }

                if (!message.streaming && message.text.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MessageActionPill(
                            glyph = HudGlyph.REFRESH,
                            label = "Regenerate",
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                        )
                        MessageActionPill(
                            glyph = HudGlyph.COPY,
                            label = "Copy",
                            onClick = { copyToClipboard(context, message.text) },
                            modifier = Modifier.weight(1f),
                        )
                        MessageActionPill(
                            glyph = HudGlyph.PLUS,
                            label = "More",
                            onClick = { onQuickPrompt("Elaborate further on your last point") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadgeCard(
    kind: String,
    title: String,
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = when (kind) {
        "Video" -> JarvisPalette.Danger
        "Tool" -> JarvisPalette.Amber
        "Memory" -> JarvisPalette.CyanBright
        "Engine" -> JarvisPalette.Cyan
        else -> JarvisPalette.CyanBright
    }
    val glyph = when (kind) {
        "Video" -> HudGlyph.PLAY
        "Tool" -> HudGlyph.BOLT
        "Memory" -> HudGlyph.BRAIN
        "Engine" -> HudGlyph.CHIP
        else -> HudGlyph.GLOBE
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(JarvisPalette.Deep.copy(alpha = 0.85f))
            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.25f), shape)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.38f),
                            JarvisPalette.Navy.copy(alpha = 0.9f),
                        ),
                    ),
                )
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            HudIcon(
                glyph = glyph,
                color = accent,
                modifier = Modifier.size(14.dp),
                weight = 1.8.dp,
            )
        }
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                text = kind,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontSize = 9.sp,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = JarvisPalette.Ice,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageActionPill(
    glyph: HudGlyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(JarvisPalette.Deep.copy(alpha = 0.82f))
            .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.24f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudIcon(
            glyph = glyph,
            color = JarvisPalette.CyanBright,
            modifier = Modifier.size(13.dp),
            weight = 1.8.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.Ice,
            maxLines = 1,
        )
    }
}

@Composable
private fun BannerStrip(
    error: String?,
    notice: String?,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (error != null) {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = JarvisPalette.Danger,
                corner = 16.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelMedium,
                        color = JarvisPalette.Danger,
                        modifier = Modifier.weight(1f),
                    )
                    HudIconButton(
                        glyph = HudGlyph.CLOSE,
                        contentDescription = "Dismiss error",
                        onClick = onDismissError,
                        size = 26.dp,
                        tint = JarvisPalette.Danger,
                    )
                }
            }
        }
        if (notice != null) {
            Spacer(Modifier.height(if (error != null) 8.dp else 0.dp))
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = JarvisPalette.Success,
                corner = 16.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelMedium,
                        color = JarvisPalette.Success,
                        modifier = Modifier.weight(1f),
                    )
                    HudIconButton(
                        glyph = HudGlyph.CLOSE,
                        contentDescription = "Dismiss notice",
                        onClick = onDismissNotice,
                        size = 26.dp,
                        tint = JarvisPalette.Success,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(
    state: JarvisUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleMic: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    val canSend = state.input.isNotBlank() && !state.generating

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            JarvisPalette.Panel.copy(alpha = 0.96f),
                            JarvisPalette.Deep.copy(alpha = 0.96f),
                        ),
                    ),
                )
                .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.28f), shape)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = JarvisPalette.Ice),
                cursorBrush = SolidColor(JarvisPalette.Cyan),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                decorationBox = { inner ->
                    if (state.input.isEmpty()) {
                        Text(
                            text = "Ask anything...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = JarvisPalette.TextDim.copy(alpha = 0.78f),
                        )
                    }
                    inner()
                },
            )

            Spacer(Modifier.width(8.dp))

            HudIcon(
                glyph = HudGlyph.MIC,
                color = if (state.listening) JarvisPalette.CyanBright else JarvisPalette.Cyan,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onToggleMic),
            )
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (state.generating) JarvisPalette.Danger else JarvisPalette.Panel,
                            if (state.generating) JarvisPalette.Danger.copy(alpha = 0.7f) else JarvisPalette.Deep,
                        ),
                    ),
                )
                .border(
                    1.dp,
                    if (state.generating) JarvisPalette.Danger else JarvisPalette.Cyan.copy(alpha = 0.35f),
                    RoundedCornerShape(14.dp),
                )
                .alpha(if (canSend || state.generating) 1f else 0.75f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (state.generating) onStop() else if (canSend) onSend() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            HudIcon(
                glyph = if (state.generating) HudGlyph.STOP else HudGlyph.SEND,
                color = if (state.generating) Color.White else JarvisPalette.CyanBright,
                modifier = Modifier.size(18.dp),
                weight = 1.9.dp,
            )
        }
    }
}

/**
 * Live AI Model Switcher Modal with automatic model fetching and Free Models filter toggle.
 */
@Composable
private fun ModelPickerModal(
    state: JarvisUiState,
    onDismiss: () -> Unit,
    onFetchModels: () -> Unit,
    onSetFreeModelsOnly: (Boolean) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onDownloadLocalModel: (String) -> Unit,
    onPauseLocalModel: (String) -> Unit,
    onDeleteLocalModel: (String) -> Unit,
    onSelectLocalModel: (String) -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    var selectedProviderFilter by remember { mutableStateOf("All") }
    val providerFilters = remember(state.discoveredModels) {
        (listOf("All", "Offline Local AI") + state.discoveredModels.map { it.providerLabel }).distinct()
    }
    val filteredModels = remember(state.discoveredModels, state.freeModelsOnly, selectedProviderFilter) {
        state.discoveredModels.filter { model ->
            model.providerLabel != "Offline Local AI" &&
                (!state.freeModelsOnly || model.isFree) &&
                (selectedProviderFilter == "All" || model.providerLabel.equals(selectedProviderFilter, ignoreCase = true))
        }
    }
    val showOfflineModels = selectedProviderFilter == "All" ||
        selectedProviderFilter.equals("Offline Local AI", ignoreCase = true)
    val activeLocalId = state.settings.localAi.activeModelId
    val isLocalProviderDefault = state.settings.defaultProvider?.kind == ProviderKind.LOCAL_ON_DEVICE

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JarvisPalette.Panel,
        titleContentColor = JarvisPalette.Ice,
        textContentColor = JarvisPalette.TextDim,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudIcon(glyph = HudGlyph.ROCKET, color = JarvisPalette.CyanBright, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select AI Model (Offline & Cloud)", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Free & Offline models (${LocalModelCatalog.models.size + filteredModels.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JarvisPalette.Ice,
                        )
                        Text(
                            text = "Download Offline Local AI (no API key) or use Cloud providers",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                        )
                    }
                    Switch(
                        checked = state.freeModelsOnly,
                        onCheckedChange = onSetFreeModelsOnly,
                    )
                }

                if (providerFilters.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        providerFilters.forEach { filterName ->
                            val isSelected = selectedProviderFilter == filterName
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) {
                                            JarvisPalette.Cyan.copy(alpha = 0.25f)
                                        } else {
                                            JarvisPalette.Deep.copy(alpha = 0.7f)
                                        },
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) JarvisPalette.CyanBright else JarvisPalette.Cyan.copy(alpha = 0.25f),
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable { selectedProviderFilter = filterName }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = filterName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) JarvisPalette.CyanBright else JarvisPalette.Ice,
                                )
                            }
                        }
                    }
                }

                if (state.fetchingModels) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = JarvisPalette.CyanBright,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Fetching live models from global providers...",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.CyanBright,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showOfflineModels) {
                        item(key = "offline_header") {
                            Text(
                                text = "OFFLINE LOCAL AI MODELS (NO API KEY NEEDED)",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisPalette.CyanBright,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(LocalModelCatalog.models, key = { "local_${it.id}" }) { spec ->
                            val status = state.localModels[spec.id]
                                ?: LocalModelDownloadStatus(
                                    modelId = spec.id,
                                    state = LocalDownloadState.NOT_DOWNLOADED,
                                    downloadedBytes = 0L,
                                    totalBytes = spec.approxBytes,
                                )
                            val isSelected = isLocalProviderDefault && activeLocalId == spec.id
                            GlassPanel(
                                modifier = Modifier.fillMaxWidth(),
                                corner = 14.dp,
                                accent = when {
                                    isSelected && status.state == LocalDownloadState.READY -> JarvisPalette.Success
                                    status.state == LocalDownloadState.READY -> JarvisPalette.CyanBright
                                    status.state == LocalDownloadState.DOWNLOADING -> JarvisPalette.Amber
                                    else -> JarvisPalette.Cyan
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${spec.displayName} (${spec.approxDownloadSizeLabel})",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = JarvisPalette.Ice,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "Best use: ${spec.bestUse}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = JarvisPalette.TextDim,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        HudPill(
                                            text = when (status.state) {
                                                LocalDownloadState.READY -> if (isSelected) "ACTIVE" else "READY"
                                                LocalDownloadState.DOWNLOADING -> "${(status.progress * 100f).toInt()}%"
                                                LocalDownloadState.PAUSED -> "PAUSED"
                                                LocalDownloadState.FAILED -> "RETRY"
                                                LocalDownloadState.NOT_DOWNLOADED -> "OFFLINE"
                                            },
                                            color = if (status.state == LocalDownloadState.READY) JarvisPalette.Success else JarvisPalette.CyanBright,
                                            filled = status.state == LocalDownloadState.READY && isSelected,
                                        )
                                    }

                                    if (status.state == LocalDownloadState.DOWNLOADING || status.state == LocalDownloadState.PAUSED) {
                                        LinearProgressIndicator(
                                            progress = { status.progress.coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(4.dp)),
                                            color = JarvisPalette.CyanBright,
                                            trackColor = JarvisPalette.Deep,
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        when (status.state) {
                                            LocalDownloadState.NOT_DOWNLOADED, LocalDownloadState.FAILED -> {
                                                Button(
                                                    onClick = { onDownloadLocalModel(spec.id) },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Text("Download (${spec.approxDownloadSizeLabel})")
                                                }
                                            }
                                            LocalDownloadState.DOWNLOADING -> {
                                                OutlinedButton(
                                                    onClick = { onPauseLocalModel(spec.id) },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Text("Pause")
                                                }
                                                OutlinedButton(
                                                    onClick = { onDeleteLocalModel(spec.id) },
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Text("Cancel")
                                                }
                                            }
                                            LocalDownloadState.PAUSED -> {
                                                Button(
                                                    onClick = { onDownloadLocalModel(spec.id) },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Text("Resume")
                                                }
                                                OutlinedButton(
                                                    onClick = { onDeleteLocalModel(spec.id) },
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Text("Delete")
                                                }
                                            }
                                            LocalDownloadState.READY -> {
                                                Button(
                                                    onClick = { onSelectLocalModel(spec.id) },
                                                    enabled = !isSelected,
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Text(if (isSelected) "Active Offline" else "Use Offline")
                                                }
                                                OutlinedButton(
                                                    onClick = { onDeleteLocalModel(spec.id) },
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Text("Delete")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(filteredModels, key = { "${it.providerLabel}_${it.id}" }) { model ->
                        GlassPanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectModel(model.providerLabel, model.id)
                                },
                            corner = 14.dp,
                            accent = if (model.isFree) JarvisPalette.Success else JarvisPalette.Cyan,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.titleSmall,
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
        },
        confirmButton = {
            TextButton(onClick = onOpenProviderSettings) {
                Text("Offline & API Settings", color = JarvisPalette.CyanBright)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onFetchModels) {
                    Text("Refresh", color = JarvisPalette.Cyan)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = JarvisPalette.TextDim)
                }
            }
        },
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
