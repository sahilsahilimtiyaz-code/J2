package com.jarvis.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.service.CallReceptionistEngine
import com.jarvis.assistant.service.FeasibilityProbeEngine
import com.jarvis.assistant.service.MessagingReplyMode
import com.jarvis.assistant.service.ProbeId
import com.jarvis.assistant.service.ProbeResultEntry
import com.jarvis.assistant.service.ProbeVerdict
import com.jarvis.assistant.service.ReceptionistCallMode
import com.jarvis.assistant.service.ReceptionistCallReport
import com.jarvis.assistant.service.SimulatedCallerScenario
import com.jarvis.assistant.service.SocialAppProfileRegistry
import com.jarvis.assistant.service.SocialMessagingEngine
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlinx.coroutines.launch

/**
 * PHASE 0 Hidden Diagnostics & Feasibility Probe Wizard + Call Receptionist Simulator.
 *
 * Guides the owner step-by-step through P1–P6 on their real phone, asks what they observed,
 * saves PASS/FAIL + telemetry evidence to a shareable Markdown/JSON file, displays the
 * Path A / B / C recommendation matrix, and lets them test the 5 simulated caller scenarios.
 */
@Composable
fun FeasibilityDiagnosticsScreen(
    probeEngine: FeasibilityProbeEngine,
    receptionistEngine: CallReceptionistEngine,
    messagingEngine: SocialMessagingEngine,
    killSwitchActive: Boolean,
    onToggleKillSwitch: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val probeState by probeEngine.state.collectAsState()
    val callReports by receptionistEngine.recentCallReports.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var callMode by remember { mutableStateOf(receptionistEngine.mode) }
    var msgMode by remember { mutableStateOf(messagingEngine.defaultMode) }
    var announceRecording by remember { mutableStateOf(receptionistEngine.recordingEnabledWithAnnouncement) }
    var lastSimReport by remember { mutableStateOf<ReceptionistCallReport?>(null) }

    val recommendedPath = probeState.recommendedPath()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisPalette.Void)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PHASE 0 · FEASIBILITY PROBES & RECEPTIONIST LAB",
                            color = JarvisPalette.Cyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = probeState.statusMessage,
                            color = JarvisPalette.TextDim,
                            fontSize = 12.sp,
                        )
                    }
                    OutlinedButton(onClick = onBack) {
                        Text("Back", color = JarvisPalette.Cyan)
                    }
                }
            }

            // Global Kill Switch & Feature Toggles
            item {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Global Kill Switch (Halts Calls, Messages & Mitigations)",
                                    color = if (killSwitchActive) JarvisPalette.Danger else JarvisPalette.Ice,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = if (killSwitchActive) "ACTIVE — All autonomous answering and messaging stopped." else "Standby — Toggle ON to instantly halt all autonomous actions.",
                                    color = JarvisPalette.TextDim,
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = killSwitchActive,
                                onCheckedChange = onToggleKillSwitch,
                            )
                        }

                        HorizontalDivider(color = JarvisPalette.Cyan.copy(alpha = 0.2f))

                        Text(
                            text = "Call Receptionist Mode",
                            color = JarvisPalette.Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ReceptionistCallMode.entries.forEach { m ->
                                FilterChip(
                                    selected = callMode == m,
                                    onClick = {
                                        callMode = m
                                        receptionistEngine.mode = m
                                    },
                                    label = { Text(m.label, fontSize = 11.sp) },
                                )
                            }
                        }

                        Text(
                            text = "Messaging Reply Mode (WhatsApp, Instagram, Telegram, Messenger, SMS)",
                            color = JarvisPalette.Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MessagingReplyMode.entries.forEach { m ->
                                FilterChip(
                                    selected = msgMode == m,
                                    onClick = {
                                        msgMode = m
                                        messagingEngine.defaultMode = m
                                    },
                                    label = { Text(m.label, fontSize = 11.sp) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Call Recording Consent Mode",
                                    color = JarvisPalette.Ice,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = if (announceRecording) "Recording ON — Announces 'This call is recorded' on line 1." else "Transcripts Only (Default) — Audio recording OFF.",
                                    color = JarvisPalette.TextDim,
                                    fontSize = 11.sp,
                                )
                            }
                            Switch(
                                checked = announceRecording,
                                onCheckedChange = {
                                    announceRecording = it
                                    receptionistEngine.recordingEnabledWithAnnouncement = it
                                },
                            )
                        }
                    }
                }
            }

            // Path Recommendation & Export Card
            item {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "RECOMMENDED ARCHITECTURE: ${recommendedPath.code} — ${recommendedPath.title}",
                            color = JarvisPalette.Cyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = recommendedPath.summary,
                            color = JarvisPalette.Ice,
                            fontSize = 12.sp,
                        )
                        if (!probeState.isPathBUnlocked()) {
                            Text(
                                text = "Note: On-device consumer call audio (Path B) remains locked until both P4 (Audio In) and P5 (Audio Out) are measured and marked PASS on this device.",
                                color = JarvisPalette.Amber,
                                fontSize = 11.sp,
                            )
                        } else {
                            Text(
                                text = "Experimental Warning: Path B is unlocked by your P4/P5 probe results. Automating the consumer WhatsApp app can violate Meta's terms and risk number restrictions.",
                                color = JarvisPalette.Amber,
                                fontSize = 11.sp,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(probeEngine.createShareReportIntent())
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisPalette.Cyan),
                            ) {
                                Text("Share Report File", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val md = probeEngine.buildMarkdownReport()
                                    clipboard.setText(AnnotatedString(md))
                                    probeEngine.saveReportFiles()
                                },
                            ) {
                                Text("Copy Report MD", color = JarvisPalette.Cyan, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // P1 - P6 Probe Cards
            items(ProbeId.entries) { probeId ->
                val entry = probeState.entries[probeId] ?: ProbeResultEntry(probeId)
                ProbeStepCard(
                    probeId = probeId,
                    entry = entry,
                    isRunning = probeState.currentlyRunning == probeId,
                    onRunProbe = { subAction ->
                        scope.launch {
                            when (probeId) {
                                ProbeId.P1_DEVICE -> probeEngine.runP1DeviceProbe()
                                ProbeId.P2_NOTIFICATIONS -> {
                                    when (subAction) {
                                        "answer" -> probeEngine.runP2NotificationsProbe(testActionKeyword = "answer")
                                        "decline" -> probeEngine.runP2NotificationsProbe(testActionKeyword = "decline")
                                        "reply" -> probeEngine.runP2NotificationsProbe(testInlineReplyText = "Jarvis P2 RemoteInput probe OK.")
                                        else -> probeEngine.runP2NotificationsProbe()
                                    }
                                }
                                ProbeId.P3_SCREENS -> probeEngine.runP3ScreenAndScreenshotProbe(subAction.ifBlank { "current_screen" })
                                ProbeId.P4_AUDIO_IN -> probeEngine.runP4AudioInProbe(subAction.ifBlank { "Active Call" })
                                ProbeId.P5_AUDIO_OUT -> probeEngine.runP5AudioOutProbe()
                                ProbeId.P6_CONTROL -> probeEngine.runP6CallControlProbe(subAction.ifBlank { "check_all" })
                            }
                        }
                    },
                    onRecordObservation = { verdict, answer, notes ->
                        probeEngine.recordUserObservation(probeId, verdict, answer, notes)
                    },
                )
            }

            // Simulated Caller Test Harness (5 Acceptance Scenarios)
            item {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "SIMULATED CALLER ACCEPTANCE TEST HARNESS (BUILD STEP 4)",
                            color = JarvisPalette.Cyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "Test the Receptionist conversation engine against all 5 required caller scenarios before any live call:",
                            color = JarvisPalette.TextDim,
                            fontSize = 12.sp,
                        )
                        SimulatedCallerScenario.entries.forEach { scenario ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        lastSimReport = receptionistEngine.runSimulatedCallerScenario(scenario)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(scenario.title, color = JarvisPalette.Ice, fontSize = 12.sp)
                            }
                        }

                        lastSimReport?.let { rep ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Classification: ${rep.classification} | Urgency: ${rep.urgency}",
                                    color = JarvisPalette.Cyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = "Summary: ${rep.summary}",
                                    color = JarvisPalette.Ice,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = "Recommended Action: ${rep.recommendedAction}",
                                    color = JarvisPalette.Amber,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = rep.transcript.joinToString("\n"),
                                    color = JarvisPalette.TextDim,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            // Data-Driven Supported Social & Messaging App Profiles + Privacy Data Flow
            item {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "REGISTERED SOCIAL & VOICE-NOTE APP PROFILES",
                            color = JarvisPalette.Cyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        SocialAppProfileRegistry.allProfiles().forEach { p ->
                            Text(
                                text = "• ${p.displayName} (${p.packageName}) — Calls=${p.supportsVoiceCalls}, VoiceNotes=${p.supportsVoiceNotes}",
                                color = JarvisPalette.Ice,
                                fontSize = 12.sp,
                            )
                        }
                        HorizontalDivider(color = JarvisPalette.Cyan.copy(alpha = 0.2f))
                        Text(
                            text = "PRIVACY & DATA LEAVING DEVICE DISCLOSURE (PART 4)",
                            color = JarvisPalette.Cyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "1. Speech-to-Text: Uses on-device Android SpeechRecognizer by default (stays on phone unless a cloud Whisper endpoint is configured).\n" +
                                "2. LLM Reasoning: Redacted message/call text and downscaled Set-of-Mark screenshots (only when Rung 3 Vision is invoked) are sent to your selected LLM provider (or stay 100% on-device when Offline Local GGUF AI is active).\n" +
                                "3. Text-to-Speech: Spoken reply text is sent to Fish Audio / ElevenLabs (or stays on-device with Android System TTS).\n" +
                                "4. Redaction Guarantee: OTP codes, banking/UPI screens, and password fields are stripped on-device and never written to logs or sent to any external API.",
                            color = JarvisPalette.TextDim,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeStepCard(
    probeId: ProbeId,
    entry: ProbeResultEntry,
    isRunning: Boolean,
    onRunProbe: (String) -> Unit,
    onRecordObservation: (ProbeVerdict, String, String) -> Unit,
) {
    var notes by remember(entry.userNotes) { mutableStateOf(entry.userNotes) }
    val badgeColor = when (entry.verdict) {
        ProbeVerdict.PASS -> JarvisPalette.Success
        ProbeVerdict.PARTIAL -> JarvisPalette.Amber
        ProbeVerdict.FAIL -> JarvisPalette.Danger
        ProbeVerdict.MEASURED_AWAITING_USER_CONFIRMATION -> JarvisPalette.Cyan
        ProbeVerdict.NOT_RUN -> JarvisPalette.TextDim
    }

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = probeId.title,
                    color = JarvisPalette.Ice,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entry.verdict.name,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }

            Text(
                text = probeId.stepInstruction,
                color = JarvisPalette.TextDim,
                fontSize = 12.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = { onRunProbe("") },
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisPalette.Cyan),
                ) {
                    Text(
                        text = if (isRunning) "Running..." else "Run ${probeId.code} Probe",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }

                if (probeId == ProbeId.P2_NOTIFICATIONS) {
                    OutlinedButton(onClick = { onRunProbe("answer") }) {
                        Text("Fire Answer", fontSize = 11.sp, color = JarvisPalette.Cyan)
                    }
                    OutlinedButton(onClick = { onRunProbe("reply") }) {
                        Text("Fire Reply", fontSize = 11.sp, color = JarvisPalette.Cyan)
                    }
                }
                if (probeId == ProbeId.P6_CONTROL) {
                    OutlinedButton(onClick = { onRunProbe("answer_whatsapp") }) {
                        Text("WA Answer", fontSize = 11.sp, color = JarvisPalette.Cyan)
                    }
                    OutlinedButton(onClick = { onRunProbe("end_whatsapp") }) {
                        Text("WA End", fontSize = 11.sp, color = JarvisPalette.Cyan)
                    }
                }
            }

            // Automated telemetry box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .border(1.dp, JarvisPalette.Cyan.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    text = entry.automatedEvidence,
                    color = JarvisPalette.Ice,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }

            // Owner observation question & PASS / PARTIAL / FAIL buttons
            Text(
                text = "Your Observation: ${probeId.observationQuestion}",
                color = JarvisPalette.Cyan,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Optional observation notes (e.g. speakerphone on, echo heard)", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onRecordObservation(ProbeVerdict.PASS, "Yes (Verified on device)", notes) },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisPalette.Success),
                ) {
                    Text("PASS (Yes)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { onRecordObservation(ProbeVerdict.PARTIAL, "Partial / Conditional", notes) },
                ) {
                    Text("PARTIAL", color = JarvisPalette.Amber, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { onRecordObservation(ProbeVerdict.FAIL, "No (Failed on device)", notes) },
                ) {
                    Text("FAIL (No)", color = JarvisPalette.Danger, fontSize = 11.sp)
                }
            }
        }
    }
}
