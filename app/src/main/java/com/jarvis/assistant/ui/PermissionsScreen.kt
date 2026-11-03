package com.jarvis.assistant.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.assistant.permissions.PermissionStatus
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.JarvisTopBar
import com.jarvis.assistant.ui.components.ScreenTitle
import com.jarvis.assistant.ui.theme.JarvisPalette

/**
 * The permission wizard. Every row explains in plain words what the access is
 * for, what Android will show, and what to do when a sideloaded build is blocked
 * by restricted settings.
 */
@Composable
fun PermissionsScreen(
    state: JarvisUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleBackgroundListening: () -> Unit,
    onSaveActionConfirmations: (
        alwaysAllowActions: Boolean,
        confirmCalls: Boolean,
        confirmMessages: Boolean,
        confirmScreenControl: Boolean,
        confirmDeletions: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val permissions = state.permissions
    val safety = state.settings.safety

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onRefresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onRefresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    JarvisBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            JarvisTopBar(
                title = "PERMISSIONS",
                status = if (permissions.microphone) "MIC READY" else "ACTION NEEDED",
                statusColor = if (permissions.microphone) JarvisPalette.Success else JarvisPalette.Amber,
                onBack = onBack,
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column {
                        ScreenTitle("What Jarvis may use")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Nothing here is granted automatically. Jarvis asks only when a " +
                                "request needs it, and you can revoke any of it in Android settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JarvisPalette.TextDim,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                item {
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        accent = if (safety.alwaysAllowActions) JarvisPalette.Success else JarvisPalette.Cyan,
                        corner = 18.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Action Confirmations & Always Allow",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JarvisPalette.Ice,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (safety.alwaysAllowActions) {
                                        "ALWAYS ALLOW ACTIVE: Jarvis executes your commands immediately without stopping to ask for confirmation popups."
                                    } else {
                                        "Custom confirmation mode: choose which actions require a confirmation popup below."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JarvisPalette.TextDim,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = safety.alwaysAllowActions,
                                onCheckedChange = { enabled ->
                                    onSaveActionConfirmations(
                                        enabled,
                                        if (enabled) false else safety.confirmCalls,
                                        if (enabled) false else safety.confirmMessages,
                                        if (enabled) false else safety.confirmScreenControl,
                                        if (enabled) false else safety.confirmDeletions,
                                    )
                                },
                            )
                        }

                        if (!safety.alwaysAllowActions) {
                            Spacer(Modifier.height(10.dp))
                            ConfirmationCategoryToggleRow(
                                label = "Phone Calls",
                                subtitle = if (!safety.confirmCalls) "Always Allow (No Popup)" else "Ask Confirmation Popup",
                                alwaysAllow = !safety.confirmCalls,
                                onToggleAlwaysAllow = { allow ->
                                    onSaveActionConfirmations(
                                        false,
                                        !allow,
                                        safety.confirmMessages,
                                        safety.confirmScreenControl,
                                        safety.confirmDeletions,
                                    )
                                },
                            )
                            ConfirmationCategoryToggleRow(
                                label = "SMS & WhatsApp Messages",
                                subtitle = if (!safety.confirmMessages) "Always Allow (No Popup)" else "Ask Confirmation Popup",
                                alwaysAllow = !safety.confirmMessages,
                                onToggleAlwaysAllow = { allow ->
                                    onSaveActionConfirmations(
                                        false,
                                        safety.confirmCalls,
                                        !allow,
                                        safety.confirmScreenControl,
                                        safety.confirmDeletions,
                                    )
                                },
                            )
                            ConfirmationCategoryToggleRow(
                                label = "Screen Control & Taps",
                                subtitle = if (!safety.confirmScreenControl) "Always Allow (No Popup)" else "Ask Confirmation Popup",
                                alwaysAllow = !safety.confirmScreenControl,
                                onToggleAlwaysAllow = { allow ->
                                    onSaveActionConfirmations(
                                        false,
                                        safety.confirmCalls,
                                        safety.confirmMessages,
                                        !allow,
                                        safety.confirmDeletions,
                                    )
                                },
                            )
                            ConfirmationCategoryToggleRow(
                                label = "Memory & Data Deletions",
                                subtitle = if (!safety.confirmDeletions) "Always Allow (No Popup)" else "Ask Confirmation Popup",
                                alwaysAllow = !safety.confirmDeletions,
                                onToggleAlwaysAllow = { allow ->
                                    onSaveActionConfirmations(
                                        false,
                                        safety.confirmCalls,
                                        safety.confirmMessages,
                                        safety.confirmScreenControl,
                                        !allow,
                                    )
                                },
                            )
                        }
                    }
                }

                item {
                    PermissionRow(
                        title = "Microphone",
                        granted = permissions.microphone,
                        explanation = "So Jarvis can hear “Jarvis” and your requests. Audio is only " +
                            "captured while the listening service runs; nothing is recorded to disk.",
                        actionLabel = if (permissions.microphone) "Granted" else "Allow",
                        actionEnabled = !permissions.microphone,
                        onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                }

                item {
                    PermissionRow(
                        title = "Notifications",
                        granted = permissions.notifications,
                        explanation = "Needed for the ongoing “Jarvis is listening” and “Jarvis is controlling " +
                            "the screen” cards and their Stop actions.",
                        actionLabel = if (permissions.notifications) "Granted" else "Allow",
                        actionEnabled = !permissions.notifications,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                PermissionStatus.openNotificationSettings(context)
                            }
                        },
                    )
                }

                item {
                    PermissionRow(
                        title = "Default digital assistant",
                        granted = permissions.defaultAssistant,
                        explanation = "Set Jarvis as your Android Default Assistant so you can summon Jarvis by holding the Side Key (Power button) or swiping the screen corner.",
                        actionLabel = if (permissions.defaultAssistant) "Active" else "Set as default",
                        actionEnabled = true,
                        onClick = { PermissionStatus.openDefaultAssistantSettings(context) },
                    )
                }

                item {
                    PermissionRow(
                        title = "Notification access (Tier 2)",
                        granted = permissions.notificationListener,
                        explanation = "Lets Jarvis read your status-bar notifications, summarise them " +
                            "when you ask, and dismiss them. One-time verification codes are redacted.",
                        actionLabel = if (permissions.notificationListener) {
                            if (permissions.notificationListenerConnected) "Enabled" else "Reconnect"
                        } else {
                            "Open settings"
                        },
                        actionEnabled = true,
                        onClick = { PermissionStatus.openNotificationListenerSettings(context) },
                    )
                }

                item {
                    GlassPanel(modifier = Modifier.fillMaxWidth(), corner = 18.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Background listening",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = JarvisPalette.Ice,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Keeps the microphone open with the screen off. Android 14 " +
                                        "only lets this start while Jarvis is on screen, from the " +
                                        "notification or from the Quick Settings tile. A partial wake " +
                                        "lock is held while it runs, which costs battery.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JarvisPalette.TextDim,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = when {
                                        permissions.serviceRunning -> "Listening service is running."
                                        permissions.microphone -> "Ready to start."
                                        else -> "Grant the microphone first."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (permissions.serviceRunning) {
                                        JarvisPalette.Success
                                    } else {
                                        JarvisPalette.TextDim
                                    },
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(
                                checked = permissions.serviceRunning,
                                onCheckedChange = { onToggleBackgroundListening() },
                                enabled = permissions.microphone,
                            )
                        }
                    }
                }

                item {
                    PermissionRow(
                        title = "Accessibility: screen control (Tier 3)",
                        granted = permissions.accessibility,
                        explanation = "Lets Jarvis read the screen, tap what you ask for, type, scroll, " +
                            "and run multi-step screen automation (up to 15 steps) with a Stop button. " +
                            "Banking, payment and password apps are blocked, risky taps are confirmed " +
                            "first, and every action is logged.",
                        actionLabel = if (permissions.accessibility) {
                            if (permissions.accessibilityConnected) "Enabled" else "Reconnect"
                        } else {
                            "Open settings"
                        },
                        actionEnabled = true,
                        onClick = { PermissionStatus.openAccessibilitySettings(context) },
                    )
                }

                item {
                    GlassPanel(modifier = Modifier.fillMaxWidth(), corner = 18.dp) {
                        Text(
                            text = "If the accessibility or notification switch is greyed out",
                            style = MaterialTheme.typography.titleSmall,
                            color = JarvisPalette.Amber,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Open Android Settings, Apps, Jarvis. Tap the three-dot menu at the " +
                                "top right and choose “Allow restricted settings”. Then return to " +
                                "Accessibility (or Notification access), Downloaded apps, Jarvis and turn it on. " +
                                "Samsung sometimes labels the menu “More options”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisPalette.TextDim,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "If Android Advanced Protection Mode is on, it blocks accessibility " +
                                "and sideloading for apps like this one. Turn it off for Jarvis or leave " +
                                "screen control disabled.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { PermissionStatus.openAppDetails(context) }) {
                            Text("Open Jarvis app info", color = JarvisPalette.Cyan)
                        }
                    }
                }

                item {
                    PermissionRow(
                        title = "Battery: unrestricted",
                        granted = permissions.batteryUnrestricted,
                        explanation = "Samsung puts sideloaded apps to sleep, which kills background " +
                            "listening. Set Battery usage to Unrestricted and remove Jarvis from " +
                            "Sleeping apps.",
                        actionLabel = if (permissions.batteryUnrestricted) "Done" else "Open",
                        actionEnabled = !permissions.batteryUnrestricted,
                        onClick = { PermissionStatus.requestBatteryUnrestricted(context) },
                    )
                }

                item {
                    GlassPanel(modifier = Modifier.fillMaxWidth(), corner = 18.dp) {
                        Text(
                            text = "Not requested yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = JarvisPalette.Ice,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Contacts, phone and SMS are asked for the first time you make a " +
                                "call or send a message, never at startup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisPalette.TextDim,
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    explanation: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actionEnabled, onClick = onClick),
        corner = 18.dp,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(9.dp)
                    .background(
                        if (granted) JarvisPalette.Success else JarvisPalette.Amber,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = JarvisPalette.Ice,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisPalette.TextDim,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        (if (granted) JarvisPalette.Success else JarvisPalette.Cyan)
                            .copy(alpha = 0.14f),
                    )
                    .border(
                        1.dp,
                        (if (granted) JarvisPalette.Success else JarvisPalette.Cyan)
                            .copy(alpha = 0.5f),
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) JarvisPalette.Success else JarvisPalette.Cyan,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun ConfirmationCategoryToggleRow(
    label: String,
    subtitle: String,
    alwaysAllow: Boolean,
    onToggleAlwaysAllow: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = JarvisPalette.Ice,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (alwaysAllow) JarvisPalette.Success else JarvisPalette.Amber,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = alwaysAllow,
            onCheckedChange = onToggleAlwaysAllow,
        )
    }
}

/** Helper for the settings row that opens the wizard. */
internal fun Context.hasMicrophone(): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
