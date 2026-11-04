package com.jarvis.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.theme.JarvisPalette
import com.jarvis.assistant.ui.theme.JarvisTheme

@Composable
fun JarvisApp(vm: JarvisViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()

    // The runtime needs to know whether a screen can show the confirmation sheet
    // or a permission prompt. With no screen, sensitive tools decline out loud.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    vm.setUiVisible(true)
                    vm.refreshPermissions()
                }

                Lifecycle.Event.ON_STOP -> vm.setUiVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setUiVisible(false)
        }
    }

    JarvisTheme {
        var bootFinished by rememberSaveable { mutableStateOf(false) }
        var autoPromptedMic by rememberSaveable { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            vm.onPermissionResult(granted)
            vm.refreshPermissions()
        }

        LaunchedEffect(state.pendingPermission?.permission) {
            state.pendingPermission?.let { pending ->
                permissionLauncher.launch(pending.permission)
            }
        }

        LaunchedEffect(bootFinished, state.onboardingComplete, state.permissions.microphone) {
            if (bootFinished && state.onboardingComplete && !state.permissions.microphone && !autoPromptedMic) {
                autoPromptedMic = true
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }

        // Safety rule: the confirmation is spoken as well as shown.
        LaunchedEffect(state.pendingConfirmation?.id) {
            state.pendingConfirmation?.let { pending ->
                vm.speakText("${pending.title} Confirm?")
            }
        }

        BackHandler(enabled = state.quickPanelOpen) { vm.closeQuickPanel() }
        BackHandler(enabled = !state.quickPanelOpen && state.voiceOverlayOpen) {
            vm.closeVoiceOverlay()
        }
        BackHandler(
            enabled = !state.quickPanelOpen &&
                !state.voiceOverlayOpen &&
                state.onboardingComplete &&
                state.tab != JarvisTab.HOME,
        ) {
            when (state.tab) {
                JarvisTab.PERMISSIONS -> vm.closePermissions()
                JarvisTab.DIAGNOSTICS -> vm.closeDiagnostics()
                else -> vm.closeSettings()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(JarvisPalette.Void),
        ) {
            when {
                state.loading || !bootFinished -> BootScreen(
                    ready = !state.loading,
                    onFinished = { bootFinished = true },
                )

                !state.poweredOn -> PoweredOffScreen(onPowerOn = vm::powerOn)

                !state.settings.auth.isAuthenticated -> AuthScreen(
                    defaultName = state.settings.profile.name,
                    onGoogleSignIn = vm::signInWithGoogle,
                    onGuestSignIn = vm::continueAsGuest,
                )

                !state.onboardingComplete -> OnboardingScreen(
                    state = state,
                    onSave = vm::saveProfile,
                    onDismissError = vm::dismissError,
                )

                else -> {
                    AnimatedContent(
                        targetState = state.tab,
                        transitionSpec = {
                            fadeIn(tween(260)) togetherWith fadeOut(tween(160))
                        },
                        label = "tab",
                    ) { tab ->
                        when (tab) {
                            JarvisTab.HOME -> HomeScreen(
                                state = state,
                                onInputChange = vm::onInputChange,
                                onSend = { textOverride -> vm.send(textOverride) },
                                onStop = vm::stop,
                                onStopScreenAutomation = vm::stopScreenAutomation,
                                onToggleMic = vm::toggleListening,
                                onOpenVoiceOverlay = vm::openVoiceOverlay,
                                onCloseVoiceOverlay = vm::closeVoiceOverlay,
                                onSelectTab = vm::selectTab,
                                onOpenQuickPanel = vm::openQuickPanel,
                                onRetry = vm::retryLast,
                                onDismissError = vm::dismissError,
                                onDismissNotice = vm::dismissNotice,
                                onFetchModels = vm::fetchAllAvailableModels,
                                onSetFreeModelsOnly = vm::setFreeModelsOnly,
                                onSelectQuickModel = vm::selectQuickModel,
                                onDownloadLocalModel = vm::downloadLocalModel,
                                onPauseLocalModel = vm::pauseLocalModelDownload,
                                onDeleteLocalModel = vm::deleteLocalModel,
                                onSelectLocalModel = vm::selectActiveLocalModel,
                                onCancelPlan = vm::cancelActivePlan,
                                onDismissPlan = vm::dismissActivePlan,
                                onRetryFailedPlanSteps = vm::retryFailedPlanSteps,
                                onActOnSharedContent = vm::actOnSharedContent,
                                onClearSharedContent = vm::clearSharedContent,
                            )

                            JarvisTab.MEMORY -> MemoryScreen(
                                state = state,
                                onSelectTab = vm::selectTab,
                                onToggleMic = vm::toggleListening,
                                onAdd = { text, type, importance, tags, tempHours ->
                                    vm.addMemory(text, type, importance, tags, tempHours)
                                },
                                onUpdate = vm::updateMemory,
                                onDelete = vm::deleteMemory,
                                onForgetAll = vm::forgetEverything,
                                onExport = vm::exportMemories,
                                onImport = vm::importMemories,
                                onExtract = vm::extractMemoriesNow,
                                onSearchConversations = vm::searchConversations,
                            )

                            JarvisTab.TOOLS -> ToolsScreen(
                                state = state,
                                onSelectTab = vm::selectTab,
                                onToggleMic = vm::toggleListening,
                                onRunToolPrompt = vm::runQuickCommand,
                                onLaunchTaskPlan = vm::executeTaskPlan,
                            )

                            JarvisTab.LOG -> ActionLogScreen(
                                state = state,
                                onSelectTab = vm::selectTab,
                                onToggleMic = vm::toggleListening,
                                onClear = vm::clearActionLog,
                                onRetryEntry = vm::retryActionLogEntry,
                                onUndoEntry = vm::undoActionLogEntry,
                            )

                            JarvisTab.SKILLS -> SkillsScreen(
                                state = state,
                                onSelectTab = vm::selectTab,
                                onToggleMic = vm::toggleListening,
                                onRunSkill = vm::runSkill,
                                onDeleteSkill = vm::deleteSkill,
                            )

                            JarvisTab.PROPOSALS -> ProposalsScreen(
                                state = state,
                                onSelectTab = vm::selectTab,
                                onToggleMic = vm::toggleListening,
                                onApproveProposal = vm::approveProposal,
                                onDismissProposal = vm::dismissProposal,
                                onRunSelfReviewNow = vm::runSelfReviewNow,
                            )

                            JarvisTab.SETTINGS -> SettingsScreen(
                                state = state,
                                onBack = vm::closeSettings,
                                viewModel = vm,
                            )

                            JarvisTab.PERMISSIONS -> PermissionsScreen(
                                state = state,
                                onBack = vm::closePermissions,
                                onRefresh = vm::refreshPermissions,
                                onToggleBackgroundListening = vm::toggleBackgroundListening,
                                onSaveActionConfirmations = vm::saveActionConfirmationSettings,
                            )

                            JarvisTab.DIAGNOSTICS -> {
                                val core = vm.coreInstance()
                                val killSwitch by core.proactiveCallEngine.killSwitchActive.collectAsStateWithLifecycle()
                                FeasibilityDiagnosticsScreen(
                                    probeEngine = core.feasibilityProbeEngine,
                                    receptionistEngine = core.callReceptionistEngine,
                                    messagingEngine = core.socialMessagingEngine,
                                    killSwitchActive = killSwitch,
                                    onToggleKillSwitch = core::setProactiveKillSwitch,
                                    onBack = vm::closeDiagnostics,
                                )
                            }
                        }
                    }

                    QuickPanelEmptyDrag(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(20.dp),
                        enabled = state.poweredOn && !state.quickPanelOpen,
                        onOpen = vm::openQuickPanel,
                    )

                    QuickPanelOverlay(
                        visible = state.poweredOn && state.quickPanelOpen,
                        state = state,
                        onClose = vm::closeQuickPanel,
                        onSelectTab = { tab ->
                            vm.selectTab(tab)
                            vm.closeQuickPanel()
                        },
                        onToggleTorch = vm::toggleTorch,
                        onToggleMedia = vm::toggleMedia,
                        onToggleConversationMode = vm::toggleConversationMode,
                        onToggleFloatingHud = vm::toggleFloatingHud,
                        onRunQuickCommand = vm::runQuickCommand,
                    )

                    ConfirmationOverlay(
                        pending = if (state.poweredOn) state.pendingConfirmation else null,
                        onApprove = vm::approveConfirmation,
                        onAlwaysAllow = vm::approveAndAlwaysAllowConfirmation,
                        onReject = vm::rejectConfirmation,
                    )
                }
            }
        }
    }
}

/**
 * A slim, always-present grab strip on the right edge so the quick panel can be
 * pulled in with a swipe, the way the reference does it.
 */
@Composable
private fun QuickPanelEmptyDrag(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (total < -50f) onOpen() },
                onDragCancel = { total = 0f },
                onHorizontalDrag = { _, delta -> total += delta },
            )
        },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (enabled) {
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .width(3.dp)
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(JarvisPalette.Cyan.copy(alpha = 0.30f)),
            )
        }
    }
}

/** The explicit yes/no sheet for calls, messages and anything else irreversible. */
@Composable
private fun ConfirmationOverlay(
    pending: PendingConfirmation?,
    onApprove: () -> Unit,
    onAlwaysAllow: () -> Unit = onApprove,
    onReject: () -> Unit,
) {
    var lastShown by remember { mutableStateOf<PendingConfirmation?>(null) }
    LaunchedEffect(pending) {
        if (pending != null) lastShown = pending
    }

    AnimatedVisibility(
        visible = pending != null,
        enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 6 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 6 },
    ) {
        val shown = pending ?: lastShown
        if (shown != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) {
                GlassPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp),
                    accent = JarvisPalette.Amber,
                    corner = 24.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HudIcon(
                            glyph = HudGlyph.BOLT,
                            color = JarvisPalette.Amber,
                            modifier = Modifier.size(16.dp),
                            weight = 1.8.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "CONFIRMATION REQUIRED",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.Amber,
                            letterSpacing = 2.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = shown.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = JarvisPalette.Ice,
                    )
                    if (shown.detail.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = shown.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = JarvisPalette.TextDim,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConfirmationButton(
                            label = "No",
                            filled = false,
                            onClick = onReject,
                            modifier = Modifier.weight(1f),
                        )
                        ConfirmationButton(
                            label = "Yes, do it",
                            filled = true,
                            onClick = onApprove,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ConfirmationButton(
                        label = "Always Allow (Don't Ask Again)",
                        filled = false,
                        onClick = onAlwaysAllow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (filled) {
                    JarvisPalette.Cyan.copy(alpha = 0.92f)
                } else {
                    JarvisPalette.Panel.copy(alpha = 0.9f)
                },
            )
            .border(
                width = 1.dp,
                color = if (filled) {
                    JarvisPalette.CyanBright.copy(alpha = 0.6f)
                } else {
                    JarvisPalette.Line
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
            color = if (filled) JarvisPalette.Void else JarvisPalette.Ice,
        )
    }
}
