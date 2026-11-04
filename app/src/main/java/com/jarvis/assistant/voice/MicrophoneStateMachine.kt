package com.jarvis.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic lifecycle states for all microphone access in Jarvis.
 */
enum class MicrophoneState {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    LISTENING,
    PROCESSING,
    STOPPING,
    ERROR,
}

/**
 * Live diagnostic telemetry for the Microphone Status Monitor.
 * Never contains audio buffers, transcripts, or sensitive user content.
 */
data class MicrophoneDiagnostics(
    val state: MicrophoneState = MicrophoneState.IDLE,
    val sessionId: String = "none",
    val listeningStartTimeMs: Long = 0L,
    val listeningDurationMs: Long = 0L,
    val activeRecognitionSessionCount: Int = 0,
    val audioRecordState: String = "RELEASED",
    val foregroundServiceState: String = "STOPPED",
    val lastStartReason: String = "None",
    val lastStopReason: String = "App initialized (Idle)",
    val lastError: String = "None",
    val isMicrophoneResourceHeld: Boolean = false,
    val continuousWakeWordEnabled: Boolean = false,
    val transitionHistory: List<MicrophoneState> = listOf(MicrophoneState.IDLE),
) {
    val currentState: MicrophoneState
        get() = state

    /**
     * Computes live duration in milliseconds when a session is actively starting/listening,
     * or returns the finalized duration of the last session.
     */
    fun liveDurationMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (listeningStartTimeMs <= 0L) return listeningDurationMs
        return if (state == MicrophoneState.STARTING ||
            state == MicrophoneState.LISTENING ||
            isMicrophoneResourceHeld
        ) {
            (nowMs - listeningStartTimeMs).coerceAtLeast(0L)
        } else {
            listeningDurationMs
        }
    }
}

/**
 * Thread-safe state machine governing microphone acquisition, recognition session counting,
 * AudioRecord tracking, and deterministic release across the entire Jarvis runtime.
 */
class MicrophoneStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var sessionCounter = 0L
    private var activeSessionId: String? = null
    private var audioRecordRecording: Boolean = false

    /**
     * While true the microphone is held in a single continuous "hot mic" session.
     * Recognizer teardown between utterances must never report IDLE, because that
     * is what made the microphone indicator and the UI flicker on/off/on/off.
     */
    private var hotHold: Boolean = false

    private val _diagnostics = MutableStateFlow(MicrophoneDiagnostics())
    val diagnostics: StateFlow<MicrophoneDiagnostics> = _diagnostics.asStateFlow()

    val current: MicrophoneDiagnostics
        get() = _diagnostics.value

    val currentState: MicrophoneState
        get() = _diagnostics.value.state

    /**
     * Resets state to clean [MicrophoneState.IDLE] on startup or full restart.
     */
    fun resetForRestart(reason: String = "Jarvis initialized (Idle)") {
        synchronized(lock) {
            activeSessionId = null
            audioRecordRecording = false
            _diagnostics.value = MicrophoneDiagnostics(
                state = MicrophoneState.IDLE,
                sessionId = "none",
                listeningStartTimeMs = 0L,
                listeningDurationMs = 0L,
                activeRecognitionSessionCount = 0,
                audioRecordState = "RELEASED",
                foregroundServiceState = _diagnostics.value.foregroundServiceState,
                lastStartReason = "None",
                lastStopReason = reason,
                lastError = "None",
                isMicrophoneResourceHeld = false,
                continuousWakeWordEnabled = _diagnostics.value.continuousWakeWordEnabled,
                transitionHistory = listOf(MicrophoneState.IDLE),
            )
        }
    }

    /**
     * Transitions to [MicrophoneState.REQUESTING_PERMISSION] before runtime permission dialog.
     */
    fun onRequestingPermission(reason: String) {
        synchronized(lock) {
            val snap = _diagnostics.value
            _diagnostics.value = snap.copy(
                state = MicrophoneState.REQUESTING_PERMISSION,
                lastStartReason = reason,
                isMicrophoneResourceHeld = snap.activeRecognitionSessionCount > 0 || audioRecordRecording,
                transitionHistory = appendTransition(snap.transitionHistory, MicrophoneState.REQUESTING_PERMISSION),
            )
        }
    }

    /**
     * Handles the outcome of a microphone permission check or prompt.
     */
    fun onPermissionDenied(errorMessage: String = "Microphone permission denied") {
        synchronized(lock) {
            val snap = _diagnostics.value
            val withError = appendTransition(snap.transitionHistory, MicrophoneState.ERROR)
            val withIdle = appendTransition(withError, MicrophoneState.IDLE)
            activeSessionId = null
            _diagnostics.value = snap.copy(
                state = MicrophoneState.IDLE,
                activeRecognitionSessionCount = 0,
                audioRecordState = "RELEASED",
                lastStopReason = "Permission denied",
                lastError = errorMessage,
                isMicrophoneResourceHeld = false,
                transitionHistory = withIdle,
            )
        }
    }

    /**
     * Attempts to begin a new microphone session.
     *
     * Returns a new unique session ID if transition to [MicrophoneState.STARTING] succeeded,
     * or `null` if a session is already starting, listening, or stopping (preventing overlapping
     * duplicate listeners from rapid taps or concurrent triggers).
     */
    fun tryBeginSession(reason: String): String? {
        synchronized(lock) {
            val snap = _diagnostics.value
            if (snap.state == MicrophoneState.STARTING ||
                snap.state == MicrophoneState.LISTENING ||
                snap.state == MicrophoneState.STOPPING ||
                snap.activeRecognitionSessionCount > 0
            ) {
                return null
            }
            sessionCounter += 1L
            val newId = String.format(java.util.Locale.US, "mic-%04d", sessionCounter)
            activeSessionId = newId
            val now = clock()
            _diagnostics.value = snap.copy(
                state = MicrophoneState.STARTING,
                sessionId = newId,
                listeningStartTimeMs = now,
                listeningDurationMs = 0L,
                lastStartReason = reason,
                isMicrophoneResourceHeld = audioRecordRecording,
                transitionHistory = appendTransition(snap.transitionHistory, MicrophoneState.STARTING),
            )
            return newId
        }
    }

    /**
     * Marks that the underlying SpeechRecognizer or AudioRecord hardware has started capturing.
     */
    fun onRecognitionHardwareOpened(sessionId: String? = null) {
        synchronized(lock) {
            val snap = _diagnostics.value
            val nextCount = snap.activeRecognitionSessionCount + 1
            val resolvedId = sessionId ?: activeSessionId ?: snap.sessionId
            val now = clock()
            val startMs = if (snap.listeningStartTimeMs > 0L) snap.listeningStartTimeMs else now
            _diagnostics.value = snap.copy(
                state = MicrophoneState.LISTENING,
                sessionId = resolvedId,
                listeningStartTimeMs = startMs,
                activeRecognitionSessionCount = nextCount,
                isMicrophoneResourceHeld = true,
                transitionHistory = appendTransition(snap.transitionHistory, MicrophoneState.LISTENING),
            )
        }
    }

    /**
     * Marks that the recognizer callback `onReadyForSpeech` has fired and the microphone is actively listening.
     */
    fun onListeningReady() {
        synchronized(lock) {
            val snap = _diagnostics.value
            val now = clock()
            val startMs = if (snap.listeningStartTimeMs > 0L) snap.listeningStartTimeMs else now
            _diagnostics.value = snap.copy(
                state = MicrophoneState.LISTENING,
                listeningStartTimeMs = startMs,
                isMicrophoneResourceHeld = true,
                transitionHistory = appendTransition(snap.transitionHistory, MicrophoneState.LISTENING),
            )
        }
    }

    /**
     * Marks that the user finished speaking an utterance and Jarvis is transitioning to
     * [MicrophoneState.PROCESSING]. Callers must immediately release the recognizer hardware.
     */
    fun onUtteranceCapturedBeginProcessing(
        sessionId: String? = null,
        stopReason: String = "Utterance completed (processing command)",
    ) {
        synchronized(lock) {
            val snap = _diagnostics.value
            if (sessionId != null && activeSessionId != null && sessionId != activeSessionId) {
                return
            }
            val duration = computeElapsedLocked(snap)
            _diagnostics.value = snap.copy(
                state = MicrophoneState.PROCESSING,
                listeningDurationMs = duration,
                lastStopReason = stopReason,
                transitionHistory = appendTransition(snap.transitionHistory, MicrophoneState.PROCESSING),
            )
        }
    }

    fun onProcessingUtterance(
        stopReason: String = "Utterance completed (processing command)",
    ) {
        onUtteranceCapturedBeginProcessing(stopReason = stopReason)
    }

    /**
     * Finalizes [MicrophoneState.PROCESSING] to [MicrophoneState.IDLE] once the microphone
     * hardware has been released and the command has been dispatched to the AI brain.
     */
    fun completeProcessingToIdle(stopReason: String? = null) {
        synchronized(lock) {
            val snap = _diagnostics.value
            if (snap.activeRecognitionSessionCount == 0 && snap.state != MicrophoneState.IDLE) {
                if (hotHold) {
                    // The AudioRecord anchor still owns the microphone: stay live.
                    val history = appendTransition(snap.transitionHistory, MicrophoneState.LISTENING)
                    _diagnostics.value = snap.copy(
                        state = MicrophoneState.LISTENING,
                        lastStopReason = stopReason ?: snap.lastStopReason,
                        isMicrophoneResourceHeld = audioRecordRecording || snap.isMicrophoneResourceHeld,
                        transitionHistory = history,
                    )
                } else {
                    activeSessionId = null
                    _diagnostics.value = snap.copy(
                        state = MicrophoneState.IDLE,
                        lastStopReason = stopReason ?: snap.lastStopReason,
                        isMicrophoneResourceHeld = audioRecordRecording,
                        transitionHistory = appendTransition(snap.transitionHistory, MicrophoneState.IDLE),
                    )
                }
            }
        }
    }

    /**
     * Marks the microphone as deliberately held across turns (continuous voice link).
     * When disabled, the normal deterministic release path resumes.
     */
    fun setHotHold(enabled: Boolean, reason: String = "Continuous live microphone") {
        synchronized(lock) {
            hotHold = enabled
            val snap = _diagnostics.value
            if (enabled) {
                val history = if (snap.state != MicrophoneState.LISTENING) {
                    appendTransition(snap.transitionHistory, MicrophoneState.LISTENING)
                } else {
                    snap.transitionHistory
                }
                _diagnostics.value = snap.copy(
                    state = MicrophoneState.LISTENING,
                    lastStartReason = reason,
                    isMicrophoneResourceHeld = true,
                    transitionHistory = history,
                )
            }
        }
    }

    val isHotHeld: Boolean
        get() = synchronized(lock) { hotHold }

    /**
     * Initiates deterministic teardown of an active microphone session
     * (manual cancel, navigate away, screen lock, incoming phone call, or stop button).
     */
    fun beginStopping(reason: String): Boolean {
        synchronized(lock) {
            val snap = _diagnostics.value
            val duration = computeElapsedLocked(snap)
            if (snap.state == MicrophoneState.IDLE && !snap.isMicrophoneResourceHeld) {
                _diagnostics.value = snap.copy(lastStopReason = reason)
                return false
            }
            val withStopping = appendTransition(snap.transitionHistory, MicrophoneState.STOPPING)
            val held = snap.activeRecognitionSessionCount > 0 || audioRecordRecording
            if (!held) {
                activeSessionId = null
                _diagnostics.value = snap.copy(
                    state = MicrophoneState.IDLE,
                    listeningDurationMs = duration,
                    lastStopReason = reason,
                    isMicrophoneResourceHeld = false,
                    transitionHistory = appendTransition(withStopping, MicrophoneState.IDLE),
                )
            } else {
                _diagnostics.value = snap.copy(
                    state = MicrophoneState.STOPPING,
                    listeningDurationMs = duration,
                    lastStopReason = reason,
                    transitionHistory = withStopping,
                )
            }
            return true
        }
    }

    /**
     * Records a speech recognition timeout or error and transitions through
     * [MicrophoneState.ERROR] -> [MicrophoneState.STOPPING] -> [MicrophoneState.IDLE]
     * once hardware is released.
     */
    fun onRecognitionError(
        errorMessage: String,
        stopReason: String = "Recognition error: $errorMessage",
    ) {
        synchronized(lock) {
            val snap = _diagnostics.value
            val duration = computeElapsedLocked(snap)
            val withError = appendTransition(snap.transitionHistory, MicrophoneState.ERROR)
            val held = snap.activeRecognitionSessionCount > 0 || audioRecordRecording
            if (hotHold) {
                _diagnostics.value = snap.copy(
                    state = MicrophoneState.LISTENING,
                    listeningDurationMs = duration,
                    lastError = errorMessage,
                    lastStopReason = stopReason,
                    isMicrophoneResourceHeld = true,
                    transitionHistory = appendTransition(appendTransition(withError, MicrophoneState.LISTENING), MicrophoneState.LISTENING),
                )
            } else if (!held) {
                val withStopping = appendTransition(withError, MicrophoneState.STOPPING)
                val withIdle = appendTransition(withStopping, MicrophoneState.IDLE)
                activeSessionId = null
                _diagnostics.value = snap.copy(
                    state = MicrophoneState.IDLE,
                    listeningDurationMs = duration,
                    lastError = errorMessage,
                    lastStopReason = stopReason,
                    isMicrophoneResourceHeld = false,
                    transitionHistory = withIdle,
                )
            } else {
                _diagnostics.value = snap.copy(
                    state = MicrophoneState.ERROR,
                    listeningDurationMs = duration,
                    lastError = errorMessage,
                    lastStopReason = stopReason,
                    transitionHistory = withError,
                )
            }
        }
    }

    /**
     * Must be called whenever a SpeechRecognizer instance is cancelled and destroyed.
     * Decrements [MicrophoneDiagnostics.activeRecognitionSessionCount] and transitions
     * to [MicrophoneState.IDLE] when no hardware resources remain held.
     */
    fun onRecognitionHardwareReleased(
        sessionId: String? = null,
        stopReason: String? = null,
    ) {
        synchronized(lock) {
            val snap = _diagnostics.value
            val nextCount = (snap.activeRecognitionSessionCount - 1).coerceAtLeast(0)
            val duration = computeElapsedLocked(snap)
            val held = nextCount > 0 || audioRecordRecording || hotHold
            var history = snap.transitionHistory
            val nextState = if (hotHold) {
                history = appendTransition(history, MicrophoneState.LISTENING)
                MicrophoneState.LISTENING
            } else if (!held) {
                if (snap.state == MicrophoneState.ERROR ||
                    snap.state == MicrophoneState.LISTENING ||
                    snap.state == MicrophoneState.STARTING
                ) {
                    history = appendTransition(history, MicrophoneState.STOPPING)
                }
                if (snap.state != MicrophoneState.IDLE) {
                    history = appendTransition(history, MicrophoneState.IDLE)
                }
                activeSessionId = null
                MicrophoneState.IDLE
            } else {
                snap.state
            }
            _diagnostics.value = snap.copy(
                state = nextState,
                activeRecognitionSessionCount = nextCount,
                listeningDurationMs = duration,
                lastStopReason = stopReason ?: snap.lastStopReason,
                isMicrophoneResourceHeld = held,
                transitionHistory = history,
            )
        }
    }

    /**
     * Updates AudioRecord hardware state (`"RELEASED"`, `"INITIALIZED"`, `"RECORDING"`).
     */
    fun onAudioRecordStateChanged(stateLabel: String, isRecording: Boolean) {
        synchronized(lock) {
            audioRecordRecording = isRecording
            val snap = _diagnostics.value
            val held = snap.activeRecognitionSessionCount > 0 || isRecording
            val duration = if (!held) computeElapsedLocked(snap) else snap.listeningDurationMs
            var history = snap.transitionHistory
            val nextState = when {
                isRecording && snap.state == MicrophoneState.STARTING -> {
                    history = appendTransition(history, MicrophoneState.LISTENING)
                    MicrophoneState.LISTENING
                }
                !held && (snap.state == MicrophoneState.STOPPING ||
                    snap.state == MicrophoneState.PROCESSING ||
                    snap.state == MicrophoneState.LISTENING ||
                    snap.state == MicrophoneState.ERROR) -> {
                    if (snap.state != MicrophoneState.STOPPING && snap.state != MicrophoneState.PROCESSING) {
                        history = appendTransition(history, MicrophoneState.STOPPING)
                    }
                    history = appendTransition(history, MicrophoneState.IDLE)
                    activeSessionId = null
                    MicrophoneState.IDLE
                }
                else -> snap.state
            }
            _diagnostics.value = snap.copy(
                state = nextState,
                audioRecordState = stateLabel,
                listeningDurationMs = duration,
                isMicrophoneResourceHeld = held,
                transitionHistory = history,
            )
        }
    }

    /**
     * Tracks whether the foreground microphone service (`JarvisListeningService`) is running.
     */
    fun onForegroundServiceStateChanged(running: Boolean) {
        synchronized(lock) {
            val snap = _diagnostics.value
            _diagnostics.value = snap.copy(
                foregroundServiceState = if (running) "RUNNING" else "STOPPED",
            )
        }
    }

    /**
     * Tracks whether continuous wake-word mode is explicitly enabled by the user.
     */
    fun onContinuousModeChanged(enabled: Boolean) {
        synchronized(lock) {
            val snap = _diagnostics.value
            _diagnostics.value = snap.copy(
                continuousWakeWordEnabled = enabled,
            )
        }
    }

    fun onError(
        errorMessage: String,
        stopReason: String = "Recognition error: $errorMessage",
    ) {
        onRecognitionError(errorMessage = errorMessage, stopReason = stopReason)
    }

    /**
     * Handles an incoming phone call or audio focus loss by immediately transitioning
     * through [MicrophoneState.STOPPING] -> [MicrophoneState.IDLE] and releasing all handles.
     */
    fun onAudioInterruptionOrCall(
        reason: String = "Incoming phone call / audio interruption",
    ) {
        forceReleaseAll(reason)
    }

    /**
     * Finalizes any stopping or ending session cleanly to [MicrophoneState.IDLE].
     */
    fun completeStop(
        reason: String = "Listening session ended",
    ) {
        forceReleaseAll(reason)
    }

    /**
     * Emergency / deterministic force-release of all tracked microphone state.
     */
    fun forceReleaseAll(reason: String) {
        synchronized(lock) {
            hotHold = false
            val snap = _diagnostics.value
            val duration = computeElapsedLocked(snap)
            activeSessionId = null
            audioRecordRecording = false
            var history = snap.transitionHistory
            if (snap.state != MicrophoneState.IDLE && snap.state != MicrophoneState.STOPPING) {
                history = appendTransition(history, MicrophoneState.STOPPING)
            }
            if (history.lastOrNull() != MicrophoneState.IDLE) {
                history = appendTransition(history, MicrophoneState.IDLE)
            }
            _diagnostics.value = snap.copy(
                state = MicrophoneState.IDLE,
                activeRecognitionSessionCount = 0,
                audioRecordState = "RELEASED",
                listeningDurationMs = duration,
                lastStopReason = reason,
                isMicrophoneResourceHeld = false,
                transitionHistory = history,
            )
        }
    }

    private fun computeElapsedLocked(snap: MicrophoneDiagnostics): Long {
        if (snap.listeningStartTimeMs <= 0L) return snap.listeningDurationMs
        if (snap.state == MicrophoneState.IDLE && snap.listeningDurationMs > 0L) {
            return snap.listeningDurationMs
        }
        return (clock() - snap.listeningStartTimeMs).coerceAtLeast(0L)
    }

    private fun appendTransition(
        current: List<MicrophoneState>,
        next: MicrophoneState,
    ): List<MicrophoneState> {
        if (current.lastOrNull() == next) return current
        val updated = current + next
        return if (updated.size > MAX_HISTORY) updated.takeLast(MAX_HISTORY) else updated
    }

    private companion object {
        const val MAX_HISTORY = 32
    }
}
