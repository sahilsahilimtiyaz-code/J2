package com.jarvis.assistant.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.VisualAccessibilityReconciler
import com.jarvis.assistant.tools.ScreenVisionCapture
import com.jarvis.assistant.tools.WhatsAppCallAutomation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ProbeId(
    val code: String,
    val title: String,
    val stepInstruction: String,
    val observationQuestion: String,
) {
    P1_DEVICE(
        code = "P1",
        title = "P1: Device, OS & Advanced Protection",
        stepInstruction = "Step 1: Tap 'Run P1 Probe' to inspect Android SDK, OEM, Advanced Protection (Android 16/17), Accessibility Service, Notification Listener, and Restricted Settings state.",
        observationQuestion = "Are both Accessibility Service and Notification Listener shown as CONNECTED?",
    ),
    P2_NOTIFICATIONS(
        code = "P2",
        title = "P2: WhatsApp & Social Call/Message Notifications",
        stepInstruction = "Step 2: From a second phone, send a WhatsApp message and place a WhatsApp call to this phone. While ringing or unread, tap 'Run P2 Probe' to log package, category, action titles, RemoteInput keys, and test firing Answer/Decline/Reply.",
        observationQuestion = "Did NotificationListenerService capture the WhatsApp call/message actions and did firing the action work?",
    ),
    P3_SCREENS(
        code = "P3",
        title = "P3: Accessibility Node Tree & Screenshot Probe",
        stepInstruction = "Step 3: Open WhatsApp (incoming-call, in-call, chat, or contact-search screen), then tap 'Run P3 Probe' (or trigger via voice/timer) to dump the accessibility node tree and test takeScreenshot() for blank or FLAG_SECURE blackout.",
        observationQuestion = "Did the node tree dump contain the expected WhatsApp controls and was the screenshot valid (not blank/black)?",
    ),
    P4_AUDIO_IN(
        code = "P4",
        title = "P4: In-Call Audio Capture (Accessibility VOICE_RECOGNITION)",
        stepInstruction = "Step 4: Place a real WhatsApp call (and then a cellular call) from another phone. Answer it, toggle speakerphone ON (and test OFF), have the caller speak continuously, and tap 'Run P4 Probe' to test VOICE_RECOGNITION, MIC, VOICE_COMMUNICATION, and UNPROCESSED with AudioRecordingCallback.isClientSilenced.",
        observationQuestion = "Was isClientSilenced=false and did the RMS level rise clearly when the remote caller spoke?",
    ),
    P5_AUDIO_OUT(
        code = "P5",
        title = "P5: In-Call Audio Output (Tone & TTS vs Echo Cancellation)",
        stepInstruction = "Step 5: While in an active WhatsApp call (and cellular call) with speakerphone ON, tap 'Run P5 Probe'. Jarvis will play a 660Hz test tone and speak a test line across USAGE_MEDIA, USAGE_VOICE_COMMUNICATION, and USAGE_ASSISTANCE_ACCESSIBILITY.",
        observationQuestion = "Did the caller on the other phone clearly hear Jarvis's test tone and spoken sentence?",
    ),
    P6_CONTROL(
        code = "P6",
        title = "P6: Call Answer, End & Speakerphone Control",
        stepInstruction = "Step 6: While a test call is ringing or active, tap 'Run P6 Probe' to test answering/ending via Notification Action, Accessibility Click, TelecomManager (cellular), and toggling Speakerphone.",
        observationQuestion = "Was Jarvis able to answer, turn on speakerphone, and end the call reliably?",
    ),
}

enum class ProbeVerdict {
    NOT_RUN,
    MEASURED_AWAITING_USER_CONFIRMATION,
    PASS,
    PARTIAL,
    FAIL,
}

enum class RecommendedCallPath(val code: String, val title: String, val summary: String) {
    PATH_A(
        code = "PATH A",
        title = "WhatsApp Business Platform Cloud API Calling",
        summary = "Webhook receives call on a dedicated business number; WebRTC/SIP media bridged directly to STT -> LLM -> TTS. Clean digital audio, zero screen tapping. Scoped strictly to receptionist duties per Meta policy.",
    ),
    PATH_B(
        code = "PATH B",
        title = "On-Device Consumer App Call Receptionist (Experimental)",
        summary = "Answers via notification action or accessibility click, turns on speakerphone, captures caller audio via AccessibilityService (VOICE_RECOGNITION), and speaks half-duplex over loudspeaker. Warning: automating consumer WhatsApp can violate Meta ToS.",
    ),
    PATH_C(
        code = "PATH C",
        title = "Call Screening, Decline-and-Message & Cloud Voice Agent Forwarding",
        summary = "Safe no-hack path when Android silences in-call mic capture (P4) or echo-cancels speaker output (P5), and for all cellular calls. Declines or screens with an instant message ('Jarvis here, [Name]'s assistant. What do you need?') or forwards unanswered calls to an India-compatible SIP/telephony cloud agent.",
    ),
}

data class AudioSourceSampleResult(
    val sourceName: String,
    val sourceId: Int,
    val initialized: Boolean,
    val isClientSilenced: Boolean,
    val peakAmplitude: Int,
    val meanRms: Double,
    val maxRms: Double,
    val durationMs: Long,
    val note: String,
)

data class ProbeResultEntry(
    val probeId: ProbeId,
    val verdict: ProbeVerdict = ProbeVerdict.NOT_RUN,
    val automatedEvidence: String = "Not measured on device yet.",
    val userObservationAnswer: String = "",
    val userNotes: String = "",
    val structuredMetrics: Map<String, String> = emptyMap(),
    val updatedAtMs: Long = 0L,
)

data class Phase0ProbeReportState(
    val entries: Map<ProbeId, ProbeResultEntry> = ProbeId.entries.associateWith { ProbeResultEntry(it) },
    val currentlyRunning: ProbeId? = null,
    val statusMessage: String = "Ready. Run each probe on your phone and record what you observe.",
    val lastSavedReportPath: String? = null,
    val advancedProtectionDetected: Boolean = false,
) {
    fun recommendedPath(): RecommendedCallPath {
        val p4 = entries[ProbeId.P4_AUDIO_IN]?.verdict
        val p5 = entries[ProbeId.P5_AUDIO_OUT]?.verdict
        val p6 = entries[ProbeId.P6_CONTROL]?.verdict
        return if (p4 == ProbeVerdict.PASS && p5 == ProbeVerdict.PASS && (p6 == ProbeVerdict.PASS || p6 == ProbeVerdict.PARTIAL)) {
            RecommendedCallPath.PATH_B
        } else {
            RecommendedCallPath.PATH_C
        }
    }

    fun isPathBUnlocked(): Boolean {
        val p4 = entries[ProbeId.P4_AUDIO_IN]?.verdict
        val p5 = entries[ProbeId.P5_AUDIO_OUT]?.verdict
        return p4 == ProbeVerdict.PASS && p5 == ProbeVerdict.PASS
    }
}

/**
 * PHASE 0: On-Device Feasibility Probe Engine (P1–P6).
 *
 * Measures actual Android OS, OEM (Samsung One UI), Accessibility, NotificationListener,
 * in-call AudioRecord (VOICE_RECOGNITION + AudioRecordingCallback.isClientSilenced),
 * in-call AudioTrack/TTS output vs acoustic echo cancellation, and call control APIs.
 *
 * Never fakes or assumes a PASS: every probe records real hardware telemetry and
 * requires the owner's observation confirmation before marking PASS/FAIL.
 */
class FeasibilityProbeEngine(
    private val context: Context,
    private val onSpeakTestSentence: (String) -> Unit = {},
) {
    private val reportDir = File(context.filesDir, "probe_reports").apply { mkdirs() }
    private val stateFile = File(reportDir, "jarvis_phase0_probe_report.json")
    private val markdownFile = File(reportDir, "jarvis_phase0_probe_report.md")

    private val _state = MutableStateFlow(loadPersistedState())
    val state: StateFlow<Phase0ProbeReportState> = _state.asStateFlow()

    /**
     * Checks Android 16/17 AdvancedProtectionManager via reflection and Settings.Secure
     * so it works safely across API 31–37.
     */
    fun detectAdvancedProtection(): Pair<Boolean, String> {
        return runCatching {
            val serviceObj = context.getSystemService("advanced_protection")
            if (serviceObj != null) {
                val method = serviceObj.javaClass.methods.firstOrNull {
                    it.name == "isAdvancedProtectionEnabled" && it.parameterCount == 0
                }
                val enabled = method?.invoke(serviceObj) as? Boolean ?: false
                return@runCatching enabled to "AdvancedProtectionManager API present (enabled=$enabled)"
            }
            val secureVal = runCatching {
                android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    "advanced_protection_mode",
                    0,
                )
            }.getOrDefault(0)
            (secureVal == 1) to "Settings.Secure(advanced_protection_mode=$secureVal)"
        }.getOrElse { e ->
            false to "AdvancedProtectionManager not active on this SDK (${e.javaClass.simpleName})"
        }
    }

    /**
     * P1: Device, Android version/SDK, OEM, Advanced Protection, Accessibility, NotificationListener,
     * and Restricted Settings status.
     */
    suspend fun runP1DeviceProbe(): ProbeResultEntry = withContext(Dispatchers.IO) {
        setRunning(ProbeId.P1_DEVICE, "Measuring P1: Device, OS & Advanced Protection state...")
        val sdkInt = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val (advProtEnabled, advProtDetail) = detectAdvancedProtection()
        val a11yEnabled = JarvisAccessibilityService.isEnabled(context)
        val a11yConnected = JarvisAccessibilityService.isConnected()
        val notifEnabled = JarvisNotificationListenerService.isEnabled(context)
        val notifConnected = JarvisNotificationListenerService.isConnected()
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val answerCallsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        val phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        val restrictedSettingsLikelyBlocking = sdkInt >= 33 && !a11yEnabled
        val evidence = buildString {
            appendLine("Device: $manufacturer $model (${Build.DEVICE})")
            appendLine("Android: $release (SDK $sdkInt)")
            appendLine("Advanced Protection: $advProtEnabled ($advProtDetail)")
            appendLine("Accessibility Service: enabled=$a11yEnabled, connected=$a11yConnected")
            appendLine("Notification Listener: enabled=$notifEnabled, connected=$notifConnected")
            appendLine("Restricted Settings Hint: ${if (restrictedSettingsLikelyBlocking) "Accessibility is off on Android 13+; open App Info -> 3-dot menu -> Allow restricted settings" else "Clear / Already allowed"}")
            appendLine("Permissions: RECORD_AUDIO=$micGranted, ANSWER_PHONE_CALLS=$answerCallsGranted, READ_PHONE_STATE=$phoneStateGranted")
        }.trim()

        val autoVerdict = when {
            advProtEnabled && !a11yConnected -> ProbeVerdict.FAIL
            a11yConnected && notifConnected && micGranted -> ProbeVerdict.PASS
            a11yConnected || notifConnected -> ProbeVerdict.PARTIAL
            else -> ProbeVerdict.MEASURED_AWAITING_USER_CONFIRMATION
        }

        val entry = ProbeResultEntry(
            probeId = ProbeId.P1_DEVICE,
            verdict = autoVerdict,
            automatedEvidence = evidence,
            structuredMetrics = mapOf(
                "sdk" to sdkInt.toString(),
                "release" to release,
                "oem" to "$manufacturer $model",
                "advanced_protection" to advProtEnabled.toString(),
                "accessibility_connected" to a11yConnected.toString(),
                "notification_listener_connected" to notifConnected.toString(),
                "restricted_settings_blocking" to restrictedSettingsLikelyBlocking.toString(),
            ),
            updatedAtMs = System.currentTimeMillis(),
        )
        updateEntry(entry, advProtEnabled, "P1 Device Probe completed.")
        entry
    }

    /**
     * P2: Inspects what NotificationListenerService sees for WhatsApp/Social calls and messages:
     * package, category, action titles, RemoteInput presence, and optionally fires a test action.
     */
    suspend fun runP2NotificationsProbe(
        testActionKeyword: String? = null,
        testInlineReplyText: String? = null,
    ): ProbeResultEntry = withContext(Dispatchers.IO) {
        setRunning(ProbeId.P2_NOTIFICATIONS, "Inspecting NotificationListenerService telemetry for WhatsApp/Social calls & messages...")
        val connected = JarvisNotificationListenerService.isConnected()
        val telemetry = JarvisNotificationListenerService.probeTelemetryLog()
        val socialTelemetry = telemetry.filter {
            it.packageName.contains("whatsapp", ignoreCase = true) ||
                it.packageName.contains("instagram", ignoreCase = true) ||
                it.packageName.contains("telegram", ignoreCase = true) ||
                it.packageName.contains("orca", ignoreCase = true) ||
                it.packageName.contains("messaging", ignoreCase = true) ||
                it.packageName.contains("dialer", ignoreCase = true)
        }

        var actionTestOutput = "No action trigger requested during this probe run."
        if (!testActionKeyword.isNullOrBlank()) {
            val (ok, msg) = JarvisNotificationListenerService.triggerNotificationAction(
                packageFilter = "whatsapp",
                actionKeywords = listOf(testActionKeyword),
            )
            actionTestOutput = "Action Trigger ('$testActionKeyword'): success=$ok -> $msg"
        } else if (!testInlineReplyText.isNullOrBlank()) {
            val (ok, msg) = JarvisNotificationListenerService.sendInlineReply(
                keyOrSenderQuery = "",
                replyText = testInlineReplyText,
                packageFilter = "whatsapp",
            )
            actionTestOutput = "Inline RemoteInput Reply: success=$ok -> $msg"
        }

        val evidence = buildString {
            appendLine("NotificationListener Connected: $connected")
            appendLine("Total Captured Notifications: ${telemetry.size} (Social/Call relevant: ${socialTelemetry.size})")
            if (socialTelemetry.isEmpty()) {
                appendLine("WARNING: No WhatsApp/Social call or message notification captured yet. Send a test WhatsApp message or call from another phone and re-run P2.")
            } else {
                socialTelemetry.take(8).forEachIndexed { idx, item ->
                    appendLine(
                        "#${idx + 1} pkg=${item.packageName} | cat=${item.category} | ongoing=${item.isOngoing} | " +
                            "actions=[${item.actionTitles.joinToString(", ")}] | " +
                            "hasRemoteInput=${item.hasRemoteInput} (keys=${item.remoteInputResultKeys.joinToString(",")}) | " +
                            "title='${item.title}'",
                    )
                }
            }
            appendLine(actionTestOutput)
        }.trim()

        val hasCallNotif = socialTelemetry.any { it.category == "call" || it.actionTitles.any { a -> a.contains("answer", true) || a.contains("decline", true) } }
        val hasMsgNotif = socialTelemetry.any { it.hasRemoteInput }

        val entry = ProbeResultEntry(
            probeId = ProbeId.P2_NOTIFICATIONS,
            verdict = ProbeVerdict.MEASURED_AWAITING_USER_CONFIRMATION,
            automatedEvidence = evidence,
            structuredMetrics = mapOf(
                "listener_connected" to connected.toString(),
                "social_notifications_seen" to socialTelemetry.size.toString(),
                "call_notification_with_actions_seen" to hasCallNotif.toString(),
                "message_notification_with_remote_input_seen" to hasMsgNotif.toString(),
            ),
            updatedAtMs = System.currentTimeMillis(),
        )
        updateEntry(entry, statusMsg = "P2 Notification Probe captured (${socialTelemetry.size} relevant notifications). Confirm observation below.")
        entry
    }

    /**
     * P3: Dumps the accessibility node tree for the current foreground screen (WhatsApp incoming-call,
     * in-call, chat, contact-search, etc.) and tests AccessibilityService.takeScreenshot().
     */
    suspend fun runP3ScreenAndScreenshotProbe(screenContextTag: String = "current_foreground"): ProbeResultEntry = withContext(Dispatchers.IO) {
        setRunning(ProbeId.P3_SCREENS, "Capturing accessibility node tree and testing takeScreenshot()...")
        val controller = JarvisAccessibilityService.controller()
        if (controller == null) {
            val entry = ProbeResultEntry(
                probeId = ProbeId.P3_SCREENS,
                verdict = ProbeVerdict.FAIL,
                automatedEvidence = "AccessibilityService is not connected. Enable Jarvis in Android Accessibility settings first.",
                updatedAtMs = System.currentTimeMillis(),
            )
            updateEntry(entry, statusMsg = "P3 failed: AccessibilityService disconnected.")
            return@withContext entry
        }

        val fgPkg = withContext(Dispatchers.Main) { controller.foregroundPackage() }
        val nodes = withContext(Dispatchers.Main) { controller.snapshotActionableNodes() }
        val formattedTree = nodes.joinToString("\n") { it.toCompactString() }.take(3500)

        val bitmap = JarvisAccessibilityService.serviceInstance()?.captureScreenshotBitmap(720)
        val visualEvidence = VisualAccessibilityReconciler.analyzeBitmap(bitmap)
        val screenshotStatus = when {
            bitmap == null -> "BLANK_OR_NULL (takeScreenshot returned null)"
            visualEvidence.isSecureBlackout -> "BLOCKED_FLAG_SECURE (100% black frame, ${visualEvidence.width}x${visualEvidence.height})"
            else -> "VALID_BITMAP (${visualEvidence.width}x${visualEvidence.height}, avgLuminance=${String.format(Locale.US, "%.2f", visualEvidence.averageLuminance)})"
        }

        val prevEvidence = _state.value.entries[ProbeId.P3_SCREENS]?.automatedEvidence.orEmpty()
        val thisCapture = buildString {
            appendLine("=== P3 Capture [$screenContextTag] @ ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ===")
            appendLine("Foreground Package: $fgPkg")
            appendLine("Actionable Nodes Count: ${nodes.size}")
            appendLine("takeScreenshot() Result: $screenshotStatus")
            appendLine("Node Tree Sample:")
            appendLine(formattedTree)
        }.trim()

        val combinedEvidence = if (prevEvidence.startsWith("=== P3 Capture")) {
            "$thisCapture\n\n$prevEvidence".take(9000)
        } else {
            thisCapture
        }

        val entry = ProbeResultEntry(
            probeId = ProbeId.P3_SCREENS,
            verdict = ProbeVerdict.MEASURED_AWAITING_USER_CONFIRMATION,
            automatedEvidence = combinedEvidence,
            structuredMetrics = mapOf(
                "foreground_package" to fgPkg,
                "actionable_nodes" to nodes.size.toString(),
                "screenshot_status" to screenshotStatus,
                "is_secure_blackout" to visualEvidence.isSecureBlackout.toString(),
            ),
            updatedAtMs = System.currentTimeMillis(),
        )
        updateEntry(entry, statusMsg = "P3 Screen & Screenshot Probe captured for $fgPkg (${nodes.size} nodes, $screenshotStatus).")
        entry
    }

    /**
     * P4: Tests AudioRecord capture from inside the AccessibilityService context using:
     * - AudioSource.VOICE_RECOGNITION (the Android CDD accessibility exception source)
     * - AudioSource.MIC
     * - AudioSource.VOICE_COMMUNICATION
     * - AudioSource.UNPROCESSED
     * Registers an AudioManager.AudioRecordingCallback to log isClientSilenced, peak, and RMS levels.
     */
    suspend fun runP4AudioInProbe(callContextLabel: String = "Active Call"): ProbeResultEntry = withContext(Dispatchers.IO) {
        setRunning(ProbeId.P4_AUDIO_IN, "Measuring P4 Audio In across VOICE_RECOGNITION, MIC, VOICE_COMMUNICATION, UNPROCESSED...")
        val a11yContext: Context = JarvisAccessibilityService.serviceInstance() ?: context
        val usingA11yServiceContext = JarvisAccessibilityService.serviceInstance() != null
        val audioManager = a11yContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioMode = audioManager.mode
        val speakerOn = @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn

        val sourcesToTest = listOf(
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
        )

        val results = mutableListOf<AudioSourceSampleResult>()
        for ((name, sourceId) in sourcesToTest) {
            setRunning(ProbeId.P4_AUDIO_IN, "Sampling P4 AudioSource.$name (1.5s)...")
            results += sampleAudioSource(a11yContext, audioManager, name, sourceId, durationMs = 1_400L)
        }

        val voiceRecResult = results.firstOrNull { it.sourceName == "VOICE_RECOGNITION" }
        val evidence = buildString {
            appendLine("Call Context: $callContextLabel")
            appendLine("Executed Inside AccessibilityService Context: $usingA11yServiceContext")
            appendLine("AudioManager.mode: $audioMode (MODE_IN_CALL=${AudioManager.MODE_IN_CALL}, MODE_IN_COMMUNICATION=${AudioManager.MODE_IN_COMMUNICATION})")
            appendLine("Speakerphone Active: $speakerOn")
            results.forEach { r ->
                appendLine(
                    "- ${r.sourceName}: init=${r.initialized} | isClientSilenced=${r.isClientSilenced} | " +
                        "peak=${r.peakAmplitude} | meanRMS=${String.format(Locale.US, "%.1f", r.meanRms)} | " +
                        "maxRMS=${String.format(Locale.US, "%.1f", r.maxRms)} (${r.note})",
                )
            }
        }.trim()

        val entry = ProbeResultEntry(
            probeId = ProbeId.P4_AUDIO_IN,
            verdict = ProbeVerdict.MEASURED_AWAITING_USER_CONFIRMATION,
            automatedEvidence = evidence,
            structuredMetrics = mapOf(
                "inside_accessibility_service" to usingA11yServiceContext.toString(),
                "audio_mode" to audioMode.toString(),
                "speakerphone_on" to speakerOn.toString(),
                "voice_recognition_silenced" to (voiceRecResult?.isClientSilenced ?: true).toString(),
                "voice_recognition_max_rms" to String.format(Locale.US, "%.1f", voiceRecResult?.maxRms ?: 0.0),
                "voice_recognition_peak" to (voiceRecResult?.peakAmplitude ?: 0).toString(),
            ),
            updatedAtMs = System.currentTimeMillis(),
        )
        updateEntry(entry, statusMsg = "P4 Audio In Probe finished. Confirm below whether the caller's voice was captured.")
        entry
    }

    private fun sampleAudioSource(
        captureContext: Context,
        audioManager: AudioManager,
        sourceName: String,
        sourceId: Int,
        durationMs: Long,
    ): AudioSourceSampleResult {
        if (ContextCompat.checkSelfPermission(captureContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return AudioSourceSampleResult(sourceName, sourceId, false, true, 0, 0.0, 0.0, 0L, "RECORD_AUDIO not granted")
        }
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(3200)

        var silencedFlag = false
        var callbackObserved = false
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                configs?.forEach { cfg ->
                    if (cfg.clientAudioSource == sourceId) {
                        callbackObserved = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cfg.isClientSilenced) {
                            silencedFlag = true
                        }
                    }
                }
            }
        }

        audioManager.registerAudioRecordingCallback(callback, null)
        var recorder: AudioRecord? = null
        return try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val builder = AudioRecord.Builder()
                    .setAudioSource(sourceId)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { builder.setContext(captureContext) }
                }
                builder.build()
            } else {
                AudioRecord(sourceId, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return AudioSourceSampleResult(sourceName, sourceId, false, true, 0, 0.0, 0.0, 0L, "STATE_UNINITIALIZED")
            }

            recorder.startRecording()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeCfg = audioManager.activeRecordingConfigurations.firstOrNull {
                    it.clientAudioSource == sourceId
                }
                if (activeCfg?.isClientSilenced == true) {
                    silencedFlag = true
                }
            }

            val buf = ShortArray(1024)
            val start = System.currentTimeMillis()
            var peak = 0
            var rmsSum = 0.0
            var maxRms = 0.0
            var frames = 0

            while (System.currentTimeMillis() - start < durationMs) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = kotlin.math.abs(buf[i].toInt())
                        if (s > peak) peak = s
                        sumSq += s.toDouble() * s.toDouble()
                    }
                    val rms = sqrt(sumSq / read)
                    rmsSum += rms
                    if (rms > maxRms) maxRms = rms
                    frames++
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeCfg = audioManager.activeRecordingConfigurations.firstOrNull {
                    it.clientAudioSource == sourceId
                }
                if (activeCfg?.isClientSilenced == true) {
                    silencedFlag = true
                }
            }

            val meanRms = if (frames > 0) rmsSum / frames else 0.0
            val effectivelyDead = peak == 0 && meanRms == 0.0
            val note = when {
                silencedFlag -> "OS silenced stream (isClientSilenced=true)"
                effectivelyDead -> "Zero-amplitude stream (muted by call audio hal)"
                maxRms < 35.0 -> "Very quiet / ambient noise floor only"
                else -> "Active audio signal captured (callbackSeen=$callbackObserved)"
            }
            AudioSourceSampleResult(
                sourceName = sourceName,
                sourceId = sourceId,
                initialized = true,
                isClientSilenced = silencedFlag || effectivelyDead,
                peakAmplitude = peak,
                meanRms = meanRms,
                maxRms = maxRms,
                durationMs = System.currentTimeMillis() - start,
                note = note,
            )
        } catch (t: Throwable) {
            AudioSourceSampleResult(sourceName, sourceId, false, true, 0, 0.0, 0.0, 0L, "Exception: ${t.message}")
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        }
    }

    /**
     * P5: Plays a 660Hz test tone and a spoken TTS sentence across different AudioAttributes usages
     * (USAGE_MEDIA, USAGE_VOICE_COMMUNICATION, USAGE_ASSISTANCE_ACCESSIBILITY) and logs active audio routes.
     */
    suspend fun runP5AudioOutProbe(): ProbeResultEntry = withContext(Dispatchers.IO) {
        setRunning(ProbeId.P5_AUDIO_OUT, "Running P5 Audio Out Probe (test tones + TTS across usages)...")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usages = listOf(
            "USAGE_MEDIA" to AudioAttributes.USAGE_MEDIA,
            "USAGE_VOICE_COMMUNICATION" to AudioAttributes.USAGE_VOICE_COMMUNICATION,
            "USAGE_ASSISTANCE_ACCESSIBILITY" to AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        )

        val logs = mutableListOf<String>()
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { dev ->
            "${deviceTypeLabel(dev.type)}(${dev.productName})"
        }
        logs += "Active Output Routes: ${outputDevices.joinToString(", ")}"
        logs += "AudioManager.mode=${audioManager.mode}, speakerphone=${@Suppress("DEPRECATION") audioManager.isSpeakerphoneOn}"

        for ((label, usage) in usages) {
            setRunning(ProbeId.P5_AUDIO_OUT, "Playing 660Hz test tone via $label...")
            val result = playTestTone(audioManager, label, usage, durationMs = 650)
            logs += result
            delay(250L)
        }

        // Also trigger Jarvis's configured TTS engine sentence
        runCatching {
            onSpeakTestSentence("Testing Jarvis call audio uplink. Please confirm if the caller hears this sentence.")
            logs += "Triggered TTS sentence: 'Testing Jarvis call audio uplink. Please confirm if the caller hears this sentence.'"
        }.onFailure {
            logs += "TTS trigger error: ${it.message}"
        }

        val entry = ProbeResultEntry(
            probeId = ProbeId.P5_AUDIO_OUT,
            verdict = ProbeVerdict.MEASURED_AWAITING_USER_CONFIRMATION,
            automatedEvidence = logs.joinToString("\n"),
            structuredMetrics = mapOf(
                "output_routes" to outputDevices.joinToString(";"),
                "audio_mode" to audioManager.mode.toString(),
                "usages_tested" to usages.joinToString(",") { it.first },
            ),
            updatedAtMs = System.currentTimeMillis(),
        )
        updateEntry(entry, statusMsg = "P5 Audio Out Probe played tones & TTS. Record below whether the caller heard it.")
        entry
    }

    private fun playTestTone(
        audioManager: AudioManager,
        usageLabel: String,
        usage: Int,
        durationMs: Int,
    ): String {
        val sampleRate = 16_000
        val numSamples = (sampleRate * durationMs) / 1000
        val pcm = ShortArray(numSamples)
        val freq = 660.0
        for (i in 0 until numSamples) {
            val env = if (i < 400) i / 400.0 else if (i > numSamples - 400) (numSamples - i) / 400.0 else 1.0
            pcm[i] = (sin(2.0 * PI * i * freq / sampleRate) * 18_000 * env).toInt().toShort()
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        val focusRes = audioManager.requestAudioFocus(focusReq)

        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 60L)
            val routedDevice = track.routedDevice?.let { "${deviceTypeLabel(it.type)}(${it.productName})" } ?: "default"
            track.stop()
            track.release()
            "- $usageLabel: played ${durationMs}ms @ 660Hz | focusGranted=${focusRes == AudioManager.AUDIOFOCUS_REQUEST_GRANTED} | routedTo=$routedDevice"
        } catch (t: Throwable) {
            "- $usageLabel: failed (${t.message})"
        } finally {
            runCatching { audioManager.abandonAudioFocusRequest(focusReq) }
        }
    }

    /**
     * P6: Tests answering/ending a WhatsApp call (notification action + accessibility click),
     * toggling speakerphone, and checking cellular TelecomManager answer/end readiness.
     */
    suspend fun runP6CallControlProbe(
        actionToTest: String = "check_all",
    ): ProbeResultEntry = withContext(Dispatchers.IO) {
        setRunning(ProbeId.P6_CONTROL, "Running P6 Call Control Probe ($actionToTest)...")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val controller = JarvisAccessibilityService.controller()
        val logs = mutableListOf<String>()

        // 1. Speakerphone toggle test
        val prevSpeaker = @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        val afterSpeaker = @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn
        val speakerBuiltIn = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        var commDeviceResult = "N/A"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && speakerBuiltIn != null) {
            val setOk = runCatching { audioManager.setCommunicationDevice(speakerBuiltIn) }.getOrDefault(false)
            commDeviceResult = "setCommunicationDevice(BUILTIN_SPEAKER)=$setOk"
        }
        logs += "Speakerphone Control: was=$prevSpeaker -> setSpeakerphoneOn(true)=$afterSpeaker | $commDeviceResult"

        // 2. WhatsApp Notification Action vs Accessibility Control
        when (actionToTest) {
            "answer_whatsapp" -> {
                val (notifOk, notifMsg) = JarvisNotificationListenerService.triggerNotificationAction(
                    packageFilter = "whatsapp",
                    actionKeywords = listOf("answer", "accept"),
                )
                logs += "WhatsApp Notification Answer: $notifOk ($notifMsg)"
                if (!notifOk && controller != null) {
                    val a11yRes = WhatsAppCallAutomation.answerIncomingCall(controller)
                    logs += "WhatsApp Accessibility Answer Fallback: ${a11yRes.success} (${a11yRes.message})"
                }
            }
            "end_whatsapp" -> {
                val (notifOk, notifMsg) = JarvisNotificationListenerService.triggerNotificationAction(
                    packageFilter = "whatsapp",
                    actionKeywords = listOf("decline", "end", "hang up", "reject"),
                )
                logs += "WhatsApp Notification End/Decline: $notifOk ($notifMsg)"
                if (!notifOk && controller != null) {
                    val a11yRes = WhatsAppCallAutomation.endActiveCall(controller)
                    logs += "WhatsApp Accessibility End Fallback: ${a11yRes.success} (${a11yRes.message})"
                }
            }
            "answer_cellular" -> {
                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                if (hasPerm && telecomManager != null) {
                    val ok = runCatching {
                        @Suppress("DEPRECATION")
                        telecomManager.acceptRingingCall()
                        true
                    }.getOrElse { false }
                    logs += "Cellular TelecomManager.acceptRingingCall(): invoked=$ok"
                } else {
                    logs += "Cellular TelecomManager.acceptRingingCall(): blocked (ANSWER_PHONE_CALLS granted=$hasPerm)"
                }
            }
            "end_cellular" -> {
                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                if (hasPerm && telecomManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val ok = runCatching {
                        @Suppress("DEPRECATION")
                        telecomManager.endCall()
                    }.getOrElse { false }
                    logs += "Cellular TelecomManager.endCall(): result=$ok"
                } else {
                    logs += "Cellular TelecomManager.endCall(): blocked (ANSWER_PHONE_CALLS granted=$hasPerm)"
                }
            }
            else -> {
                val hasAnswerPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                val waActiveControls = if (controller != null) WhatsAppCallAutomation.activeCallControlsReady(controller) else false
                logs += "WhatsApp In-Call Controls Currently Visible on Screen: $waActiveControls"
                logs += "Cellular TelecomManager Ready (ANSWER_PHONE_CALLS): $hasAnswerPerm"
            }
        }

        val entry = ProbeResultEntry(
            probeId = ProbeId.P6_CONTROL,
            verdict = ProbeVerdict.MEASURED_AWAITING_USER_CONFIRMATION,
            automatedEvidence = logs.joinToString("\n"),
            structuredMetrics = mapOf(
                "speakerphone_toggled" to afterSpeaker.toString(),
                "comm_device_result" to commDeviceResult,
                "action_tested" to actionToTest,
            ),
            updatedAtMs = System.currentTimeMillis(),
        )
        updateEntry(entry, statusMsg = "P6 Call Control Probe completed ($actionToTest). Record your observation below.")
        entry
    }

    /**
     * Records the owner's observation ("Did the caller hear it?", "Did answer work?") and sets
     * the final PASS / PARTIAL / FAIL verdict for the probe, saving the report immediately.
     */
    fun recordUserObservation(
        probeId: ProbeId,
        verdict: ProbeVerdict,
        observationAnswer: String,
        notes: String = "",
    ) {
        val current = _state.value.entries[probeId] ?: ProbeResultEntry(probeId)
        val updated = current.copy(
            verdict = verdict,
            userObservationAnswer = observationAnswer,
            userNotes = notes,
            updatedAtMs = System.currentTimeMillis(),
        )
        updateEntry(updated, statusMsg = "Saved ${probeId.code} verdict: $verdict")
    }

    /**
     * Generates the complete Markdown report with the P1–P6 summary table, raw evidence,
     * and the Path A / Path B / Path C Recommendation Table.
     */
    fun buildMarkdownReport(): String {
        val current = _state.value
        val rec = current.recommendedPath()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        return buildString {
            appendLine("# J.A.R.V.I.S. — Phase 0 On-Device Feasibility Probe Report")
            appendLine("Generated: ${sdf.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("## 1. Probe Summary Table")
            appendLine("| Probe | Area | Verdict | User Observation | Key Telemetry |")
            appendLine("|---|---|---|---|---|")
            for (id in ProbeId.entries) {
                val entry = current.entries[id] ?: ProbeResultEntry(id)
                val metricsSummary = entry.structuredMetrics.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    .ifBlank { "Pending on-device run" }
                val obs = entry.userObservationAnswer.ifBlank { "—" }
                appendLine("| **${id.code}** | ${id.title.substringAfter(": ")} | **${entry.verdict}** | $obs | $metricsSummary |")
            }
            appendLine()
            appendLine("## 2. Path Evaluation & Recommendation")
            appendLine("| Path | Architecture | Status based on Probes | Requirements & Risks |")
            appendLine("|---|---|---|---|")
            appendLine("| **PATH A** | WhatsApp Business Cloud API Calling (WebRTC/SIP bridge) | **AVAILABLE (Cloud)** | Requires dedicated WhatsApp Business Cloud API number + webhook server. Clean digital audio, zero UI tapping. Must scope strictly to receptionist duties per Meta policy. |")
            appendLine("| **PATH B** | On-Device Consumer App Call Audio (Accessibility Mic + Speaker) | **${if (current.isPathBUnlocked()) "UNLOCKED (Experimental)" else "LOCKED (Requires P4 & P5 PASS)"}** | Requires P4 (VOICE_RECOGNITION not silenced) and P5 (speaker TTS audible to caller without echo-cancellation suppression). Experimental; automating consumer WhatsApp can risk number restriction. |")
            appendLine("| **PATH C** | Call Screening, Decline-and-Message & Cloud Voice Agent Forwarding | **ALWAYS READY (Default Safe Path)** | Works on 100% of Android devices and cellular calls without audio hacks. Declines/screens with instant message or forwards unanswered cellular calls to an India SIP/telephony agent (Exotel / Twilio India / Plivo). |")
            appendLine()
            appendLine("### Active Recommendation: **${rec.code} — ${rec.title}**")
            appendLine(rec.summary)
            appendLine()
            appendLine("## 3. Detailed Probe Evidence (P1–P6)")
            for (id in ProbeId.entries) {
                val entry = current.entries[id] ?: ProbeResultEntry(id)
                appendLine("### ${id.title}")
                appendLine("- **Verdict**: `${entry.verdict}`")
                appendLine("- **Owner Observation**: ${entry.userObservationAnswer.ifBlank { "Not recorded yet" }}")
                if (entry.userNotes.isNotBlank()) {
                    appendLine("- **Owner Notes**: ${entry.userNotes}")
                }
                appendLine("```text")
                appendLine(entry.automatedEvidence)
                appendLine("```")
                appendLine()
            }
        }
    }

    /**
     * Persists both JSON and Markdown reports to `filesDir/probe_reports/` and returns the Markdown file.
     */
    fun saveReportFiles(): File {
        val current = _state.value
        val root = JSONObject()
        root.put("generatedAtMs", System.currentTimeMillis())
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        root.put("sdkInt", Build.VERSION.SDK_INT)
        root.put("recommendedPath", current.recommendedPath().name)
        val arr = JSONArray()
        for (id in ProbeId.entries) {
            val e = current.entries[id] ?: ProbeResultEntry(id)
            val obj = JSONObject()
            obj.put("probeId", id.name)
            obj.put("verdict", e.verdict.name)
            obj.put("automatedEvidence", e.automatedEvidence)
            obj.put("userObservationAnswer", e.userObservationAnswer)
            obj.put("userNotes", e.userNotes)
            obj.put("updatedAtMs", e.updatedAtMs)
            val metricsObj = JSONObject()
            e.structuredMetrics.forEach { (k, v) -> metricsObj.put(k, v) }
            obj.put("structuredMetrics", metricsObj)
            arr.put(obj)
        }
        root.put("probes", arr)
        runCatching { stateFile.writeText(root.toString(2)) }
        runCatching { markdownFile.writeText(buildMarkdownReport()) }
        _state.update { it.copy(lastSavedReportPath = markdownFile.absolutePath) }
        return markdownFile
    }

    /**
     * Creates an Android Share Sheet intent (`ACTION_SEND`) so the owner can share
     * the Phase 0 Probe Report file or text directly from their phone.
     */
    fun createShareReportIntent(): Intent {
        val mdFile = saveReportFiles()
        val reportText = buildMarkdownReport()
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.voicefiles",
                mdFile,
            )
        }.getOrNull()

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "J.A.R.V.I.S. Phase 0 Feasibility Probe Report")
            putExtra(Intent.EXTRA_TEXT, reportText)
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return Intent.createChooser(sendIntent, "Share Phase 0 Probe Report").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun setRunning(probeId: ProbeId, message: String) {
        _state.update { it.copy(currentlyRunning = probeId, statusMessage = message) }
    }

    private fun updateEntry(
        entry: ProbeResultEntry,
        advProt: Boolean? = null,
        statusMsg: String,
    ) {
        _state.update { current ->
            val nextMap = current.entries.toMutableMap()
            nextMap[entry.probeId] = entry
            current.copy(
                entries = nextMap,
                currentlyRunning = null,
                statusMessage = statusMsg,
                advancedProtectionDetected = advProt ?: current.advancedProtectionDetected,
            )
        }
        saveReportFiles()
    }

    private fun loadPersistedState(): Phase0ProbeReportState {
        if (!stateFile.exists()) return Phase0ProbeReportState()
        return runCatching {
            val root = JSONObject(stateFile.readText())
            val arr = root.optJSONArray("probes") ?: return@runCatching Phase0ProbeReportState()
            val map = ProbeId.entries.associateWith { ProbeResultEntry(it) }.toMutableMap()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = runCatching { ProbeId.valueOf(obj.getString("probeId")) }.getOrNull() ?: continue
                val verdict = runCatching { ProbeVerdict.valueOf(obj.getString("verdict")) }.getOrDefault(ProbeVerdict.NOT_RUN)
                val metricsObj = obj.optJSONObject("structuredMetrics")
                val metrics = mutableMapOf<String, String>()
                metricsObj?.keys()?.forEach { k -> metrics[k] = metricsObj.optString(k) }
                map[id] = ProbeResultEntry(
                    probeId = id,
                    verdict = verdict,
                    automatedEvidence = obj.optString("automatedEvidence", "Not measured on device yet."),
                    userObservationAnswer = obj.optString("userObservationAnswer", ""),
                    userNotes = obj.optString("userNotes", ""),
                    structuredMetrics = metrics,
                    updatedAtMs = obj.optLong("updatedAtMs", 0L),
                )
            }
            Phase0ProbeReportState(
                entries = map,
                lastSavedReportPath = markdownFile.takeIf { it.exists() }?.absolutePath,
            )
        }.getOrDefault(Phase0ProbeReportState())
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE_$type"
    }
}
