package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.settings.HealthSettings

/**
 * Background screen usage & non-intrusive voice health reminder controls.
 */
@Composable
fun ScreenUsageHealthCard(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    val health = state.settings.health
    val telemetry = state.screenHealth
    var enabled by remember(health.enabled) { mutableStateOf(health.enabled) }
    var voiceEnabled by remember(health.voiceSuggestionsEnabled) { mutableStateOf(health.voiceSuggestionsEnabled) }
    var sessionLimit by remember(health.sessionLimitMinutes) { mutableStateOf(health.sessionLimitMinutes) }

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
                text = "Screen Usage & Voice Health Service",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Continuous session: ${telemetry.continuousSessionMinutes}m / ${sessionLimit}m limit · " +
                    "Today: ${telemetry.dailyScreenMinutes}m · Breaks taken today: ${telemetry.breaksTakenToday}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            telemetry.lastSuggestionText?.takeIf { it.isNotBlank() }?.let { lastText ->
                Text(
                    text = "Latest health reminder (${telemetry.lastSuggestionCategory ?: "Wellbeing"}): \"$lastText\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        viewModel.saveHealthSettings(
                            health.copy(
                                enabled = it,
                                voiceSuggestionsEnabled = voiceEnabled,
                                sessionLimitMinutes = sessionLimit,
                            ),
                        )
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Track screen usage in background", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Monitors continuous active screen time and resets automatically after a ${health.breakResetMinutes}-minute screen-off break.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = voiceEnabled,
                    onCheckedChange = {
                        voiceEnabled = it
                        viewModel.saveHealthSettings(
                            health.copy(
                                enabled = enabled,
                                voiceSuggestionsEnabled = it,
                                sessionLimitMinutes = sessionLimit,
                            ),
                        )
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Non-intrusive voice health suggestions", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Speaks friendly break reminders (20-20-20 eye care, hydration, posture) when usage exceeds the limit. Never interrupts phone calls or quiet hours (${health.quietHoursStart}:00–0${health.quietHoursEnd}:00).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Recommended continuous session limit:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 45, 60, 90).forEach { mins ->
                    FilterChip(
                        selected = sessionLimit == mins,
                        onClick = {
                            sessionLimit = mins
                            viewModel.saveHealthSettings(
                                HealthSettings(
                                    enabled = enabled,
                                    voiceSuggestionsEnabled = voiceEnabled,
                                    sessionLimitMinutes = mins,
                                    breakResetMinutes = health.breakResetMinutes,
                                    quietHoursStart = health.quietHoursStart,
                                    quietHoursEnd = health.quietHoursEnd,
                                ),
                            )
                        },
                        label = { Text("${mins}m") },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::resetHealthBreakTimer) {
                    Text("Took a break (Reset timer)")
                }
                TextButton(onClick = viewModel::triggerHealthSuggestionNow) {
                    Text("Test health reminder")
                }
            }
        }
    }
}

/**
 * The parts of the voice that only matter once the cloud voice works: the model
 * header, the wake word, and the switches for hands-free and always-on listening.
 */
@Composable
fun BackgroundVoiceCard(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    val voice = state.settings.voice
    var model by remember(voice.model) { mutableStateOf(voice.model) }
    var wakeWord by remember(voice.wakeWord) { mutableStateOf(voice.wakeWord) }
    var requireWake by remember(voice.requireWakeWord) { mutableStateOf(voice.requireWakeWord) }
    var alwaysOn by remember(voice.alwaysListening) { mutableStateOf(voice.alwaysListening) }

    LaunchedEffect(state.serviceActive, voice.alwaysListening) {
        alwaysOn = state.serviceActive || voice.alwaysListening
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
            Text(
                text = "Listening and model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Fish Audio model header") },
                placeholder = { Text("s2.1-pro-free") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Text(
                text = "Use s2.1-pro-free on a zero balance. Jarvis also falls back automatically " +
                    "across s1 and speech-1.6 if a model header is rejected.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = wakeWord,
                onValueChange = { wakeWord = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Wake word") },
                placeholder = { Text("jarvis") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = requireWake, onCheckedChange = { requireWake = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Only answer when called by name", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "With this off, every sentence heard is treated as a request.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = alwaysOn, onCheckedChange = { alwaysOn = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Listen in the background", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = if (state.serviceActive) {
                            "Running now. Say “$wakeWord” with the screen off."
                        } else {
                            "Starts a microphone service with an ongoing notification and Stop."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.settings.persona.autonomousThoughtsEnabled,
                    onCheckedChange = viewModel::setAutonomousThoughtsEnabled,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Autonomous thoughts & wellbeing care", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "Monitors screen-on time (${state.devicePresence.continuousActiveMinutes}m continuous, " +
                            "${state.devicePresence.totalActiveMinutesToday}m today), battery (${state.devicePresence.batteryPercent}%) " +
                            "and notifications (${state.devicePresence.unreadNotifications}). Speaks rare care reminders, dry wit, ideas and compliments.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.voiceStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        viewModel.saveVoiceSettings(
                            provider = voice.provider,
                            voiceId = voice.voiceId,
                            speed = voice.speed,
                            fallbackToSystem = voice.fallbackToSystem,
                            language = voice.language,
                            apiKey = "",
                            continuousListening = voice.continuousListening,
                            model = model,
                            alwaysListening = alwaysOn,
                            requireWakeWord = requireWake,
                            wakeWord = wakeWord,
                        )
                    },
                ) {
                    Text("Save listening settings")
                }
                TextButton(onClick = viewModel::testVoice) {
                    Text("Test voice")
                }
                TextButton(onClick = viewModel::triggerAutonomousThoughtNow) {
                    Text("Share thought now")
                }
            }
        }
    }
}

@Composable
fun PermissionsCard(
    state: JarvisUiState,
    onOpenWizard: () -> Unit,
) {
    val permissions = state.permissions
    val missing = buildList {
        if (!permissions.microphone) add("Microphone")
        if (!permissions.notifications) add("Notifications")
        if (!permissions.notificationListener) add("Notification reader")
        if (!permissions.accessibility) add("Accessibility")
        if (!permissions.batteryUnrestricted) add("Battery")
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (missing.isEmpty()) "All access granted" else "${missing.size} to grant",
                style = MaterialTheme.typography.titleSmall,
                color = if (missing.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            Text(
                text = if (missing.isEmpty()) {
                    "Microphone, notifications, notification reader, accessibility and battery are all set."
                } else {
                    "Still needed: ${missing.joinToString(", ")}. The wizard explains each one in " +
                        "plain words, including the restricted-settings step for a sideloaded app."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenWizard) {
                Text("Open permission wizard")
            }
        }
    }
}

@Composable
fun SafetyCard(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    val safety = state.settings.safety
    val audit = state.securityAudit
    var enabled by remember(safety.screenControlEnabled) {
        mutableStateOf(safety.screenControlEnabled)
    }
    var blocklist by remember(safety.blocklistKeywords) {
        mutableStateOf(safety.blocklistKeywords.joinToString(", "))
    }
    var callEnabled by remember(safety.callAssistantEnabled) {
        mutableStateOf(safety.callAssistantEnabled)
    }
    var callTurns by remember(safety.callAssistantMaxTurns) {
        mutableStateOf(safety.callAssistantMaxTurns.toString())
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
            Text(
                text = "Hacking-Proof Security Shield · ACTIVE",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "• Hardware AES-256-GCM Keystore Vault: Active\n" +
                    "• Indirect Prompt-Injection Firewall (Screen/Notifications/Clipboard): Active\n" +
                    "• Malicious URI & Auto-APK Exploit Blocker: Active\n" +
                    "• System Package Installer & Permission Controller Lockdown: Active\n" +
                    "• OTP, Payment Card & Secret Key Redactor: Active\n" +
                    "• Threats neutralized this session: ${audit.blockedThreatCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (audit.recentThreats.isNotEmpty()) {
                val latest = audit.recentThreats.first()
                Text(
                    text = "Latest intercepted threat (${latest.source}): ${latest.description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Allow screen control", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "When off, Jarvis refuses every accessibility action.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = callEnabled, onCheckedChange = { callEnabled = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Answer WhatsApp calls for sir", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "When a WhatsApp call rings, Jarvis picks up, says sir is unavailable, " +
                            "and talks with the caller in his own voice. Requires screen control. " +
                            "Android cannot inject audio into another app's call, so Jarvis " +
                            "speaks through the loudspeaker and WhatsApp's mic carries it to the " +
                            "caller — keep the call volume up and the phone off the ear.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = callTurns,
                onValueChange = { callTurns = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Max caller exchanges") },
                supportingText = {
                    Text("2 to 12. Longer calls listen longer but cost more AI usage.")
                },
                shape = RoundedCornerShape(14.dp),
            )

            OutlinedTextField(
                value = blocklist,
                onValueChange = { blocklist = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Blocked app keywords") },
                supportingText = {
                    Text("Comma separated. Matched against the app's package name and label.")
                },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
            )

            TextButton(
                onClick = {
                    val turns = callTurns.toIntOrNull()?.coerceIn(2, 12) ?: 6
                    viewModel.saveSafety(enabled, blocklist, callEnabled, turns)
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text("Save safety settings")
            }
        }
    }
}

@Composable
fun WhatsAppVoiceNoteCard(
    state: JarvisUiState,
    viewModel: JarvisViewModel,
) {
    val safety = state.settings.safety
    var enabled by remember(safety.voiceNoteEnabled) { mutableStateOf(safety.voiceNoteEnabled) }
    var autoReply by remember(safety.voiceNoteAutoReplyEnabled) {
        mutableStateOf(safety.voiceNoteAutoReplyEnabled)
    }
    var allowlist by remember(safety.voiceNoteAllowedContacts) {
        mutableStateOf(safety.voiceNoteAllowedContacts.joinToString(", "))
    }
    var maxChars by remember(safety.voiceNoteMaxChars) {
        mutableStateOf(safety.voiceNoteMaxChars.toString())
    }
    var fallback by remember(safety.voiceNoteAudioFallbackEnabled) {
        mutableStateOf(safety.voiceNoteAudioFallbackEnabled)
    }
    var cooldown by remember(safety.voiceNoteCooldownMinutes) {
        mutableStateOf(safety.voiceNoteCooldownMinutes.toString())
    }
    var dailyLimit by remember(safety.voiceNoteDailyLimit) {
        mutableStateOf(safety.voiceNoteDailyLimit.toString())
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
            Text(
                text = "WhatsApp voice messages",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Jarvis speaks the line in your configured voice, records it through " +
                    "WhatsApp's own microphone and sends a genuine voice note. Android cannot " +
                    "inject audio into another app, so this is an acoustic recording: keep the " +
                    "phone unlocked, off any headset, and media volume up. Every manual send " +
                    "still asks for your confirmation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Allow WhatsApp voice notes", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Adds the send_whatsapp_voice_note tool. You can say: \u201cJarvis, send " +
                            "a voice message to Rahul saying I will call him tonight.\u201d",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoReply, onCheckedChange = { autoReply = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Auto-reply to allowed contacts", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "When a listed contact messages you and the phone is unlocked, Jarvis " +
                            "drafts a short reply and sends it as a voice note. Links, OTPs, " +
                            "payment requests and group chats are refused.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = allowlist,
                onValueChange = { allowlist = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Allowed contacts (comma separated)") },
                supportingText = {
                    Text("Names or phone numbers, exactly as WhatsApp shows them. Empty disables auto-reply.")
                },
                minLines = 2,
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
            )

            OutlinedTextField(
                value = maxChars,
                onValueChange = { maxChars = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Maximum note characters") },
                supportingText = { Text("80 to 700. Shorter notes sound better and cost less.") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            OutlinedTextField(
                value = cooldown,
                onValueChange = { cooldown = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Minutes between auto-replies to the same contact") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            OutlinedTextField(
                value = dailyLimit,
                onValueChange = { dailyLimit = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Daily auto-reply limit") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fallback, onCheckedChange = { fallback = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Offer an audio-file fallback", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "If WhatsApp's recorder cannot be driven, open its share screen with " +
                            "the rendered audio and say plainly that it is not yet sent.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TextButton(
                onClick = {
                    viewModel.saveWhatsAppVoiceNotes(
                        enabled = enabled,
                        autoReplyEnabled = autoReply,
                        allowlistText = allowlist,
                        maxChars = maxChars.toIntOrNull() ?: 420,
                        audioFallback = fallback,
                        cooldownMinutes = cooldown.toIntOrNull() ?: 10,
                        dailyLimit = dailyLimit.toIntOrNull() ?: 12,
                    )
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text("Save voice note settings")
            }
        }
    }
}

@Composable
fun ActionLogCard(
    state: JarvisUiState,
    onOpenLog: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Action log", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${state.actions.size} recorded, stored on this phone only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenLog) {
                Text("Open")
            }
        }
    }
}
