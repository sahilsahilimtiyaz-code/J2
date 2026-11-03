package com.jarvis.assistant.voice

import com.jarvis.assistant.core.CoreState
import com.jarvis.assistant.settings.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying the 7-state [MicrophoneStateMachine] and all 12 scenarios
 * required by the Jarvis Microphone Bug Diagnostic & Fix specification.
 */
class MicrophoneStateMachineTest {

    // Scenario 1 & 2: Start listening, speak a command, finish speaking, and verify microphone is released
    @Test
    fun scenario01_and_02_startListening_speakCommand_and_verifyMicrophoneReleased() {
        var now = 1_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)

        // 1. Request permission & start session
        sm.onRequestingPermission("User tapped microphone")
        assertEquals(MicrophoneState.REQUESTING_PERMISSION, sm.currentState)

        val sessionId = sm.tryBeginSession("User tapped microphone")
        assertNotNull(sessionId)
        assertEquals(MicrophoneState.STARTING, sm.currentState)

        // Hardware SpeechRecognizer opens and becomes ready
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()
        assertEquals(MicrophoneState.LISTENING, sm.currentState)
        assertTrue(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(1, sm.diagnostics.value.activeRecognitionSessionCount)

        // 2. User finishes speaking after 1,850 ms
        now += 1_850L
        sm.onProcessingUtterance("Speech captured — processing command")
        assertEquals(MicrophoneState.PROCESSING, sm.currentState)
        assertEquals(1_850L, sm.diagnostics.value.listeningDurationMs)

        // Hardware recognizer is destroyed immediately upon onResults
        sm.onRecognitionHardwareReleased("Speech utterance finished")
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)

        // Transition from PROCESSING -> IDLE before AI response generation
        sm.completeProcessingToIdle("Microphone released — generating AI response")
        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
    }

    // Scenario 3: Start listening and cancel manually
    @Test
    fun scenario03_startListening_and_cancelManually() {
        var now = 2_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        sm.tryBeginSession("User tapped microphone")
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()
        assertTrue(sm.diagnostics.value.isMicrophoneResourceHeld)

        now += 600L
        sm.beginStopping("User tapped microphone to stop")
        assertEquals(MicrophoneState.STOPPING, sm.currentState)

        sm.forceReleaseAll("User tapped microphone to stop")
        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("RELEASED", sm.diagnostics.value.audioRecordState)
        assertEquals(600L, sm.diagnostics.value.listeningDurationMs)
    }

    // Scenario 4: Start listening and navigate away
    @Test
    fun scenario04_startListening_and_navigateAway() {
        var now = 3_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        sm.tryBeginSession("User tapped microphone")
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()
        assertEquals(MicrophoneState.LISTENING, sm.currentState)

        now += 450L
        sm.beginStopping("Navigated away to SETTINGS")
        sm.forceReleaseAll("Navigated away to SETTINGS")

        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("Navigated away to SETTINGS", sm.diagnostics.value.lastStopReason)
    }

    // Scenario 5: Start listening and lock the screen
    @Test
    fun scenario05_startListening_and_lockScreen() {
        var now = 4_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        sm.tryBeginSession("User tapped microphone")
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()
        assertTrue(sm.diagnostics.value.isMicrophoneResourceHeld)

        now += 900L
        sm.beginStopping("Screen locked (ACTION_SCREEN_OFF)")
        sm.forceReleaseAll("Screen locked (ACTION_SCREEN_OFF)")

        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("Screen locked (ACTION_SCREEN_OFF)", sm.diagnostics.value.lastStopReason)
    }

    // Scenario 6: Trigger a recognition timeout
    @Test
    fun scenario06_triggerRecognitionTimeout_releasesMicrophoneCleanly() {
        var now = 5_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        sm.tryBeginSession("User tapped microphone")
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()

        now += 5_000L
        sm.onRecognitionHardwareReleased("Recognizer error 6")
        sm.onError(
            errorMessage = "No speech was heard.",
            stopReason = "Recognition error (code 6)",
        )

        // onError transitions through ERROR -> STOPPING -> IDLE when hardware is released
        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("No speech was heard.", sm.diagnostics.value.lastError)
        assertEquals("Recognition error (code 6)", sm.diagnostics.value.lastStopReason)
    }

    // Scenario 7: Trigger a recognition error
    @Test
    fun scenario07_triggerRecognitionError_releasesMicrophoneCleanly() {
        var now = 6_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        sm.tryBeginSession("User tapped microphone")
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()

        now += 300L
        sm.onRecognitionHardwareReleased("Recognizer error 2")
        sm.onError(
            errorMessage = "Network error while recognising speech.",
            stopReason = "Recognition error (code 2)",
        )

        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("Network error while recognising speech.", sm.diagnostics.value.lastError)
    }

    // Scenario 8: Receive an incoming phone call during listening
    @Test
    fun scenario08_receiveIncomingPhoneCallDuringListening_releasesImmediately() {
        var now = 7_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        sm.tryBeginSession("User tapped microphone")
        sm.onRecognitionHardwareOpened()
        sm.onAudioRecordStateChanged("RECORDING", isRecording = true)
        sm.onListeningReady()
        assertTrue(sm.diagnostics.value.isMicrophoneResourceHeld)

        now += 1_200L
        sm.onAudioInterruptionOrCall("Incoming/active phone call (RINGING)")

        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("RELEASED", sm.diagnostics.value.audioRecordState)
        assertEquals("Incoming/active phone call (RINGING)", sm.diagnostics.value.lastStopReason)
    }

    // Scenario 9: Rapidly tap the microphone button several times
    @Test
    fun scenario09_rapidlyTapMicrophoneButton_preventsDuplicateListeners() {
        val sm = MicrophoneStateMachine()

        val firstSession = sm.tryBeginSession("Tap 1")
        assertNotNull(firstSession)
        sm.onRecognitionHardwareOpened()
        assertEquals(1, sm.diagnostics.value.activeRecognitionSessionCount)

        // Rapid taps 2, 3, 4 while STARTING or LISTENING must be rejected
        assertNull(sm.tryBeginSession("Tap 2"))
        sm.onListeningReady()
        assertNull(sm.tryBeginSession("Tap 3"))
        assertNull(sm.tryBeginSession("Tap 4"))

        assertEquals(firstSession, sm.diagnostics.value.sessionId)
        assertEquals(1, sm.diagnostics.value.activeRecognitionSessionCount)

        sm.forceReleaseAll("Done")
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
    }

    // Scenario 10: Start and stop listening repeatedly
    @Test
    fun scenario10_startAndStopListeningRepeatedly_neverLeaksHandles() {
        var now = 8_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        for (cycle in 1..10) {
            val session = sm.tryBeginSession("Cycle $cycle start")
            assertNotNull("Cycle $cycle should start cleanly", session)
            sm.onRecognitionHardwareOpened()
            sm.onListeningReady()
            assertTrue(sm.diagnostics.value.isMicrophoneResourceHeld)
            assertEquals(1, sm.diagnostics.value.activeRecognitionSessionCount)

            now += 250L
            sm.beginStopping("Cycle $cycle stop")
            sm.onRecognitionHardwareReleased("Cycle $cycle stop")
            assertEquals(MicrophoneState.IDLE, sm.currentState)
            assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
            assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        }
    }

    // Scenario 11: Generate an AI response and verify microphone access does not continue
    @Test
    fun scenario11_generateAiResponse_verifiesMicrophoneReleasedDuringGenerationAndTts() {
        var now = 9_000_000L
        val sm = MicrophoneStateMachine(clock = { now })

        sm.tryBeginSession("Voice command")
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()

        now += 1_500L
        // Utterance completes: SpeechRecognizer destroyed and state moves to PROCESSING -> IDLE
        sm.onProcessingUtterance("Speech utterance captured")
        sm.onRecognitionHardwareReleased("SpeechRecognizer destroyed before LLM call")
        sm.completeProcessingToIdle("Released microphone before AI response generation")

        // During LLM streaming & TTS playback, microphone state is strictly IDLE and unheld
        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("RELEASED", sm.diagnostics.value.audioRecordState)
    }

    // Scenario 12: Restart Jarvis and inspect microphone state and live-call defaults
    @Test
    fun scenario12_restartJarvis_inspectDefaultMicrophoneState() {
        val sm = MicrophoneStateMachine()
        val defaultVoiceSettings = VoiceSettings()
        val defaultCoreState = CoreState()

        // Verify clean cold-boot state machine and live-call defaults
        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertFalse(sm.diagnostics.value.isMicrophoneResourceHeld)
        assertEquals(0, sm.diagnostics.value.activeRecognitionSessionCount)
        assertEquals("STOPPED", sm.diagnostics.value.foregroundServiceState)
        assertEquals("RELEASED", sm.diagnostics.value.audioRecordState)

        assertTrue("continuousListening must default to true for live call mode", defaultVoiceSettings.continuousListening)
        assertTrue("alwaysListening must default to true for live call mode", defaultVoiceSettings.alwaysListening)
        assertTrue("requireWakeWord must default to true", defaultVoiceSettings.requireWakeWord)
        assertFalse("CoreState.listening must default to false before UI attach", defaultCoreState.listening)
        assertFalse("CoreState.micLocked must default to false before UI attach", defaultCoreState.micLocked)

        // Verify that when LiveCallAudioAnchor is active, OP_RECORD_AUDIO stays continuously held
        // across utterance processing and immediate next-turn re-arming without dropping to false
        sm.onAudioRecordStateChanged("RECORDING (LIVE CALL)", isRecording = true)
        val firstSession = sm.tryBeginSession("Live call turn 1")
        assertNotNull(firstSession)
        sm.onRecognitionHardwareOpened()
        sm.onListeningReady()
        assertTrue(sm.diagnostics.value.isMicrophoneResourceHeld)

        // Utterance finishes -> recognizer stops for LLM turn while LiveCallAudioAnchor stays recording
        sm.onProcessingUtterance("Turn 1 captured")
        sm.onRecognitionHardwareReleased("Paused recognizer for reply")
        sm.completeProcessingToIdle("Turn 1 processing")
        assertEquals(MicrophoneState.IDLE, sm.currentState)
        assertTrue("LiveCallAudioAnchor keeps microphone held during reply so OS indicator never blinks off", sm.diagnostics.value.isMicrophoneResourceHeld)

        // Next turn starts immediately while LiveCallAudioAnchor is still holding the mic
        val secondSession = sm.tryBeginSession("Live call turn 2")
        assertNotNull("Next recognition pass must start while LiveCallAudioAnchor is active", secondSession)
    }
}
