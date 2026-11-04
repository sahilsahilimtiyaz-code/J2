package com.jarvis.assistant.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveIncidentCallEngineTest {

    @Test
    fun `Scenario A roofing client deployment and invoice approval matches reference dialogue`() = runBlocking {
        val savedMemories = mutableListOf<String>()
        val spokenLines = mutableListOf<String>()
        val engine = ProactiveIncidentCallEngine(
            onSpeakLine = { spokenLines += it },
            onSaveDecisionMemory = { savedMemories += it },
        )

        val state = engine.triggerSimulation("A")
        assertNotNull(state)
        assertEquals(2, engine.actionLedger.verifiedActionsForIncident(state!!.incident.id).size)

        // Turn 1: Jarvis opens with "Sir."
        val turn1 = engine.onCallAnswered()
        assertEquals("Sir.", turn1)

        // Turn 2: Owner asks "Yeah, Jarvis, what's up?"
        val turn2 = engine.processOwnerCallTurn("Yeah, Jarvis, what's up?")
        assertEquals(
            "The roofing client just approved the final design. I've staged the production deploy and drafted the invoice.",
            turn2,
        )
        assertTrue(turn2.split(Regex("\\s+")).size <= 35)

        // Turn 3: Owner asks "Nice. How much is the invoice for?"
        val turn3 = engine.processOwnerCallTurn("Nice. How much is the invoice for?")
        assertEquals("What price should I put on it?", turn3)

        // Turn 4: Owner says "Four grand."
        val turn4 = engine.processOwnerCallTurn("Four grand.")
        assertEquals(
            "Got it, four grand. Do I have approval to send the invoice and push the site live?",
            turn4,
        )
        assertTrue(savedMemories.any { it.contains("Four grand", ignoreCase = true) })

        // Turn 5: Owner approves "Yeah, just send it."
        val turn5 = engine.processOwnerCallTurn("Yeah, just send it.")
        assertEquals("Done.", turn5)
        assertEquals(CallLifecycleStatus.COMPLETED, engine.activeCallState.value?.status)
        assertTrue(
            engine.actionLedger.verifiedActionsForIncident(state.incident.id)
                .any { it.toolName == "send_invoice_and_deploy_site" && it.tier == PermissionTier.RED },
        )
    }

    @Test
    fun `Scenario B Meta ad account restricted mitigation and budget shift matches reference dialogue`() = runBlocking {
        val engine = ProactiveIncidentCallEngine()
        val state = engine.triggerSimulation("B")
        assertNotNull(state)

        // Pre-call GREEN mitigations (appeal + warm up backup) ran automatically
        val preActions = engine.actionLedger.verifiedActionsForIncident(state!!.incident.id)
        assertEquals(2, preActions.size)
        assertFalse(preActions.any { it.tier == PermissionTier.RED })

        assertEquals("Sir.", engine.onCallAnswered())

        val turn2 = engine.processOwnerCallTurn("Talk to me.")
        assertEquals(
            "Meta just restricted our main ad account seventeen minutes ago. Spend was about four hundred a day.",
            turn2,
        )

        // Owner swears ("Oh, shit. Why?") — Jarvis ignores swearing and gives root cause + verified ledger actions
        val turn3 = engine.processOwnerCallTurn("Oh, shit. Why?")
        assertEquals(
            "Automated policy flag on yesterday's creative. I've already filed the appeal and started warming up the backup account.",
            turn3,
        )

        val turn4 = engine.processOwnerCallTurn("Will the appeal work?")
        assertEquals(
            "Honestly, fifty-fifty. Usually takes a day or two. Want me to shift fifty percent of daily budget to the backup account now?",
            turn4,
        )

        val turn5 = engine.processOwnerCallTurn("Do it.")
        assertEquals("Done. Thank you.", turn5)
        assertEquals(CallLifecycleStatus.COMPLETED, engine.activeCallState.value?.status)
    }

    @Test
    fun `Scenario C driving check revenue update bank freeze and single one more thing matches reference dialogue`() = runBlocking {
        val engine = ProactiveIncidentCallEngine()
        val state = engine.triggerSimulation("C")
        assertNotNull(state)

        // Driving context active -> opens with "Sir. Are you driving?"
        val opening = engine.onCallAnswered()
        assertEquals("Sir. Are you driving?", opening)

        val turn2 = engine.processOwnerCallTurn("Yeah, heading into a meeting.")
        assertEquals(
            "Then I'll keep it short. Six new clients closed today for nineteen thousand total, and the bank triggered a fraud freeze on the operating card.",
            turn2,
        )

        val turn3 = engine.processOwnerCallTurn("Wait, why did they freeze it?")
        assertEquals(
            "Unusual spike in software charges after the new onboarding batch. I've paused non-essential rebills so nothing bounces.",
            turn3,
        )

        val turn4 = engine.processOwnerCallTurn("Okay, text me the verification link so I can clear it when I park.")
        assertEquals(
            "Sent. One more thing — the med spa lead just booked a demo for Thursday at two. Want me to confirm it?",
            turn4,
        )

        val turn5 = engine.processOwnerCallTurn("Yes, lock it in.")
        assertEquals("Done. Good luck in the meeting.", turn5)
        assertEquals(CallLifecycleStatus.COMPLETED, engine.activeCallState.value?.status)
    }

    @Test
    fun `HonestyGuardrail blocks phantom actions not present in ActionLedger`() {
        val emptyLedger = ActionLedger()
        val event = SimulationScenarioFactory.scenarioB()

        val dishonest = "Automated policy flag. I've already filed the appeal and started warming up the backup account."
        val enforced = HonestyGuardrail.enforce(
            rawReply = dishonest,
            incident = event,
            actionLedger = emptyLedger,
        )
        assertTrue(enforced.contains("I haven't executed that action yet, sir."))
        assertFalse(enforced.contains("I've already filed the appeal"))
    }

    @Test
    fun `HonestyGuardrail blocks invented numbers and enforces TTS formatting and 35 word limit`() {
        val ledger = ActionLedger()
        val event = SimulationScenarioFactory.scenarioB()

        val inventedNumberReply = "Certainly! Spend was about nine hundred a day!"
        val enforced = HonestyGuardrail.enforce(
            rawReply = inventedNumberReply,
            incident = event,
            actionLedger = ledger,
        )
        assertFalse(enforced.contains("Certainly"))
        assertFalse(enforced.contains("!"))
        assertEquals("I don't have that figure yet.", enforced)

        // Verify 35-word cap when owner has not asked for detail
        val longLine = (1..50).joinToString(" ") { "word" } + "."
        val capped = HonestyGuardrail.enforce(
            rawReply = longLine,
            incident = event,
            actionLedger = ledger,
            ownerAskedForDetail = false,
        )
        assertTrue(capped.split(Regex("\\s+")).size <= 35)
    }

    @Test
    fun `PermissionTier defaults unknown tools to RED and Global Kill Switch stops all calls`() = runBlocking {
        val notifications = mutableListOf<Pair<String, String>>()
        val engine = ProactiveIncidentCallEngine(
            onSendNotification = { title, body -> notifications += title to body },
        )

        assertEquals(PermissionTier.RED, engine.getPermissionTier("unknown_custom_financial_tool"))
        assertEquals(PermissionTier.GREEN, engine.getPermissionTier("stage_production_deploy"))

        // Unanswered call timeout sends 1-line notification and schedules retry for HIGH/CRITICAL
        val state = engine.handleIncidentEvent(
            event = SimulationScenarioFactory.scenarioB(),
            isSimulation = true,
            ringTimeoutMs = 0L,
        )
        assertNotNull(state)
        val missedLine = engine.handleUnansweredCallTimeout(state!!.sessionId, scheduleRetryDelayMs = 0L)
        assertNotNull(missedLine)
        assertTrue(notifications.any { it.first == "Missed Call from JARVIS" })
        assertEquals(CallLifecycleStatus.RETRY_SCHEDULED, engine.activeCallState.value?.status)

        // Global Kill Switch immediately halts everything
        engine.setKillSwitch(true)
        assertTrue(engine.killSwitchActive.value)
        assertEquals(CallLifecycleStatus.KILLED, engine.activeCallState.value?.status)
        val blocked = engine.triggerSimulation("A")
        assertNull(blocked)
    }
}
