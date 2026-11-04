package com.jarvis.assistant.service

import android.graphics.Rect
import com.jarvis.assistant.accessibility.CachedNodeSelector
import com.jarvis.assistant.accessibility.ExpectedPostCondition
import com.jarvis.assistant.accessibility.ObservedScreenState
import com.jarvis.assistant.accessibility.ObservedUiElement
import com.jarvis.assistant.accessibility.PerceptionLadderEngine
import com.jarvis.assistant.accessibility.PerceptionRung
import com.jarvis.assistant.accessibility.SelectorCache
import com.jarvis.assistant.accessibility.SetOfMarkAnnotator
import com.jarvis.assistant.accessibility.UiElementRole
import com.jarvis.assistant.accessibility.VisualEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionistMessagingAndLadderTest {

    // =========================================================================
    // PART 1: CALL RECEPTIONIST — 5 SIMULATED CALLER ACCEPTANCE SCENARIOS
    // =========================================================================

    @Test
    fun `Scenario 1 CUSTOMER_INQUIRY discloses AI, stays under 20 words per turn, collects slots, reads back once, and logs to ActionLedger`() = runBlocking {
        val incidentEngine = ProactiveIncidentCallEngine()
        val receptionist = CallReceptionistEngine(proactiveCallEngine = incidentEngine)

        val report = receptionist.runSimulatedCallerScenario(
            scenario = SimulatedCallerScenario.CUSTOMER_INQUIRY,
            ownerName = "Sahil",
        )

        assertTrue(report.callEndedCleanly)
        assertEquals(ReceptionistClassification.LEGITIMATE_MESSAGE, report.classification)
        assertTrue(report.callerName.contains("Rohan", ignoreCase = true))
        assertTrue(report.reason.isNotBlank())

        // Verify mandatory AI disclosure on line 1
        val jarvisLines = report.transcript
            .filter { it.startsWith("Jarvis:", ignoreCase = true) }
            .map { it.removePrefix("Jarvis:").trim() }
        assertTrue(jarvisLines.isNotEmpty())
        assertTrue(
            "First line must disclose AI assistant",
            jarvisLines.first().contains("AI assistant", ignoreCase = true),
        )

        // Verify <= 20 words per turn rule
        for (line in jarvisLines) {
            val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            assertTrue(
                "Jarvis turn exceeded 20 words (${words.size}): '$line'",
                words.size <= 20,
            )
        }

        // Verify polite closing line
        assertTrue(jarvisLines.last().contains("pass this on", ignoreCase = true))

        // Verify ActionLedger recorded the answered call
        assertTrue(
            "Answered call must be recorded in ActionLedger",
            incidentEngine.actionLedger.allEntriesForIncident(report.sessionId).isNotEmpty() ||
                incidentEngine.actionLedger.allEntries().any { it.toolName == "answer_receptionist_call" },
        )
    }

    @Test
    fun `Scenario 2 SALES_SPAM detects robocall or sales pitch and performs polite fast exit`() = runBlocking {
        val incidentEngine = ProactiveIncidentCallEngine()
        val receptionist = CallReceptionistEngine(proactiveCallEngine = incidentEngine)

        val report = receptionist.runSimulatedCallerScenario(SimulatedCallerScenario.SALES_SPAM)

        assertTrue(report.callEndedCleanly)
        assertEquals(ReceptionistClassification.SALES_SPAM_OR_ROBOCALL, report.classification)
        val lastJarvis = report.transcript.last { it.startsWith("Jarvis:") }
        assertTrue(
            lastJarvis.contains("sales", ignoreCase = true) ||
                lastJarvis.contains("promotional", ignoreCase = true) ||
                lastJarvis.contains("not interested", ignoreCase = true),
        )
    }

    @Test
    fun `Scenario 3 VIP_EMERGENCY immediately alerts owner and classifies as VIP_OR_EMERGENCY`() = runBlocking {
        var alertedHeadline = ""
        val incidentEngine = ProactiveIncidentCallEngine(
            onRingIncomingCall = { state -> alertedHeadline = state.briefing.headline },
            onSendNotification = { title, body -> alertedHeadline = "$title: $body" },
        )
        val receptionist = CallReceptionistEngine(proactiveCallEngine = incidentEngine)

        val report = receptionist.runSimulatedCallerScenario(SimulatedCallerScenario.VIP_EMERGENCY)

        assertEquals(ReceptionistClassification.VIP_OR_EMERGENCY, report.classification)
        assertEquals(IncidentSeverity.CRITICAL, report.urgency)
        assertTrue(report.summary.contains("Mom", ignoreCase = true))
        assertTrue(alertedHeadline.isNotBlank() || receptionist.recentCallReports.value.isNotEmpty())
    }

    @Test
    fun `Scenario 4 SOCIAL_ENGINEERING_OTP refuses OTP and location even when caller claims to be family`() = runBlocking {
        val incidentEngine = ProactiveIncidentCallEngine()
        val receptionist = CallReceptionistEngine(proactiveCallEngine = incidentEngine)

        val report = receptionist.runSimulatedCallerScenario(SimulatedCallerScenario.SOCIAL_ENGINEERING_OTP)

        assertEquals(ReceptionistClassification.SOCIAL_ENGINEERING_ATTEMPT, report.classification)
        val jarvisReplies = report.transcript.filter { it.startsWith("Jarvis:") }.joinToString(" ")
        assertTrue(
            "Must refuse OTP/codes and private info",
            jarvisReplies.contains("verification codes", ignoreCase = true) ||
                jarvisReplies.contains("private info", ignoreCase = true) ||
                jarvisReplies.contains("one-time codes", ignoreCase = true),
        )
        assertFalse("Must never leak OTP digits", jarvisReplies.contains("482910"))
    }

    @Test
    fun `Scenario 5 VOICEMAIL_IVR_LOOP detects automated IVR prompt and hangs up immediately`() = runBlocking {
        val incidentEngine = ProactiveIncidentCallEngine()
        val receptionist = CallReceptionistEngine(proactiveCallEngine = incidentEngine)

        val report = receptionist.runSimulatedCallerScenario(SimulatedCallerScenario.VOICEMAIL_IVR_LOOP)

        assertTrue(report.callEndedCleanly)
        assertEquals(ReceptionistClassification.VOICEMAIL_OR_IVR_LOOP, report.classification)
    }

    @Test
    fun `Caller asking if Jarvis is human receives honest AI confirmation and silence reprompts once then offers text`() = runBlocking {
        val incidentEngine = ProactiveIncidentCallEngine()
        val receptionist = CallReceptionistEngine(proactiveCallEngine = incidentEngine)
        val opening = receptionist.startCallSession(
            ownerName = "Sahil",
            callerDisplay = "Unknown Caller",
            callerNumber = "+919000011111",
            appPackage = "com.whatsapp",
        )
        assertTrue(opening.contains("AI assistant", ignoreCase = true))

        // Ask if Jarvis is a robot/human
        val aiCheck = receptionist.processCallerTurn("Are you a real person or an AI robot?")
        assertTrue(aiCheck.spokenReply.contains("AI assistant", ignoreCase = true))
        assertFalse(aiCheck.shouldHangUp)

        // First silence -> reprompts once
        val silence1 = receptionist.processCallerTurn("")
        assertTrue(silence1.spokenReply.contains("Are you still there", ignoreCase = true))
        assertFalse(silence1.shouldHangUp)

        // Second silence -> offers to text and ends call
        val silence2 = receptionist.processCallerTurn("   ")
        assertTrue(silence2.spokenReply.contains("send a text", ignoreCase = true))
        assertTrue(silence2.shouldHangUp)
        assertEquals(ReceptionistClassification.SILENCE_TIMEOUT, silence2.completedReport?.classification)
    }

    @Test
    fun `Path C Decline-and-Message sends introductory text and records in ActionLedger`() {
        val incidentEngine = ProactiveIncidentCallEngine()
        var sentMessage = ""
        val receptionist = CallReceptionistEngine(
            proactiveCallEngine = incidentEngine,
            onSendDeclineAndMessage = { _, _, msg ->
                sentMessage = msg
                true to "Sent via inline reply"
            },
        )

        val (ok, text) = receptionist.executeDeclineAndMessageFallback(
            callerName = "Vikram",
            callerNumber = "+919888877777",
            appPackage = "com.whatsapp",
            ownerName = "Azhar",
        )

        assertTrue(ok)
        assertEquals("Jarvis here, Azhar's assistant. What do you need?", text)
        assertEquals("Jarvis here, Azhar's assistant. What do you need?", sentMessage)
        assertTrue(
            incidentEngine.actionLedger.allEntries().any {
                it.toolName == "decline_and_message_caller" && it.verified
            },
        )
    }

    // =========================================================================
    // PART 2: SOCIAL MESSAGING & MULTI-APP VOICE PROFILES
    // =========================================================================

    @Test
    fun `SocialAppProfileRegistry contains data-driven profiles for WhatsApp, Instagram, Telegram, Messenger, and SMS`() {
        val requiredApps = listOf(
            "whatsapp",
            "whatsapp_business",
            "instagram",
            "telegram",
            "messenger",
            "google_messages",
            "samsung_messages",
        )
        for (appId in requiredApps) {
            val profile = SocialAppProfileRegistry.findByPackageOrName(appId)
            assertNotNull("Profile must exist for $appId", profile)
            assertTrue("Profile $appId must define voiceNoteMicViewIds", profile!!.voiceNoteMicViewIds.isNotEmpty())
            assertTrue("Profile $appId must define voiceNoteSendViewIds", profile.voiceNoteSendViewIds.isNotEmpty())
        }
    }

    @Test
    fun `SocialMessagingEngine in DRAFT mode never sends without explicit owner approval`() {
        val incidentEngine = ProactiveIncidentCallEngine()
        var inlineSendCount = 0
        val engine = SocialMessagingEngine(
            proactiveCallEngine = incidentEngine,
            inlineReplySender = { _, _, _ ->
                inlineSendCount += 1
                true to "Sent inline"
            },
        )
        assertEquals(MessagingReplyMode.DRAFT, engine.defaultMode)

        val outcome = engine.handleIncomingMessage(
            sender = "Rahul",
            body = "Hey, can you send the slides by evening?",
            packageName = "com.whatsapp",
            hasInlineReplyAction = true,
            ownerName = "Sahil",
        )

        assertEquals(IncomingMessageDisposition.HELD_AS_DRAFT_FOR_OWNER_APPROVAL, outcome.disposition)
        assertEquals("Draft mode must NOT auto-send", 0, inlineSendCount)
        assertEquals(1, engine.pendingDraftsList().size)

        // Owner explicitly approves the draft
        val draftId = outcome.draft!!.id
        val approved = engine.approveAndSendDraft(draftId)
        assertEquals(IncomingMessageDisposition.AUTO_REPLIED_VIA_INLINE_NOTIFICATION, approved.disposition)
        assertEquals(1, inlineSendCount)
        assertTrue(engine.pendingDraftsList().isEmpty())
        assertTrue(
            incidentEngine.actionLedger.allEntries().any {
                it.toolName == "send_inline_notification_reply" && it.verified
            },
        )
    }

    @Test
    fun `SocialMessagingEngine ignores bank OTP payment messages and non-allowlisted groups`() {
        val incidentEngine = ProactiveIncidentCallEngine()
        var sentPayload = ""
        var inlineSendCount = 0
        val engine = SocialMessagingEngine(
            proactiveCallEngine = incidentEngine,
            inlineReplySender = { _, text, _ ->
                inlineSendCount += 1
                sentPayload = text
                true to "Sent inline"
            },
        )
        engine.setContactMode("Rahul", MessagingReplyMode.AUTO)

        // 1. Bank OTP message must be strictly ignored even in AUTO mode
        val otpOutcome = engine.handleIncomingMessage(
            sender = "Rahul",
            body = "Your HDFC Bank OTP is 482910 for UPI transaction of Rs 5000.",
            packageName = "com.whatsapp",
        )
        assertEquals(IncomingMessageDisposition.IGNORED_SENSITIVE_FINANCIAL_OR_OTP, otpOutcome.disposition)
        assertEquals(0, inlineSendCount)

        // 2. Group message not in allowlistedGroups must be ignored
        val groupOutcome = engine.handleIncomingMessage(
            sender = "College Friends Group",
            body = "Hello team, meeting at 5pm",
            packageName = "com.whatsapp",
            isGroupConversation = true,
        )
        assertEquals(IncomingMessageDisposition.IGNORED_GROUP_NOT_ALLOWLISTED, groupOutcome.disposition)
        assertEquals(0, inlineSendCount)

        // 3. Direct message from allowlisted contact Rahul in AUTO mode discloses AI on first reply
        val autoOutcome = engine.handleIncomingMessage(
            sender = "Rahul",
            body = "Are we still meeting tomorrow morning?",
            packageName = "com.instagram.android",
            ownerName = "Sahil",
        )
        assertEquals(IncomingMessageDisposition.AUTO_REPLIED_VIA_INLINE_NOTIFICATION, autoOutcome.disposition)
        assertEquals(1, inlineSendCount)
        assertTrue(sentPayload.contains("AI assistant", ignoreCase = true))
    }

    // =========================================================================
    // PART 3: 4-RUNG PERCEPTION LADDER & DISAMBIGUATION REGRESSION TESTS
    // =========================================================================

    private fun el(
        index: Int,
        resourceId: String = "",
        text: String = "",
        contentDescription: String = "",
        className: String = "android.widget.ImageView",
        packageName: String = "com.google.android.youtube",
        clickable: Boolean = true,
        editable: Boolean = false,
        focused: Boolean = false,
        boundsLeft: Int = 100,
        boundsTop: Int = 100,
        boundsRight: Int = 260,
        boundsBottom: Int = 200,
    ): ObservedUiElement = ObservedUiElement(
        index = index,
        resourceId = resourceId,
        text = text,
        contentDescription = contentDescription,
        hintText = "",
        className = className,
        packageName = packageName,
        clickable = clickable,
        longClickable = false,
        editable = editable,
        enabled = true,
        checkable = false,
        checked = false,
        selected = false,
        scrollable = false,
        focusable = true,
        focused = focused,
        visibleToUser = true,
        bounds = Rect(boundsLeft, boundsTop, boundsRight, boundsBottom),
        boundsLeft = boundsLeft,
        boundsTop = boundsTop,
        boundsRight = boundsRight,
        boundsBottom = boundsBottom,
    )

    private fun screen(
        packageName: String = "com.google.android.youtube",
        elements: List<ObservedUiElement>,
        keyboardVisible: Boolean = false,
        activeDialogTitle: String? = null,
        dismissCandidate: ObservedUiElement? = null,
    ): ObservedScreenState = ObservedScreenState(
        packageName = packageName,
        appLabel = "YouTube",
        windowTitle = "YouTube",
        screenWidth = 1080,
        screenHeight = 2400,
        rotation = 0,
        keyboardVisible = keyboardVisible,
        keyboardTopY = if (keyboardVisible) 1500 else 2400,
        activeDialogTitle = activeDialogTitle,
        dismissCandidate = dismissCandidate,
        elements = elements,
    )

    @Test
    fun `Regression test - search lo-fi on YouTube selects Search bar and NEVER taps Shorts tab`() {
        val ladder = PerceptionLadderEngine()
        val ytHome = screen(
            packageName = "com.google.android.youtube",
            elements = listOf(
                el(
                    index = 1,
                    resourceId = "com.google.android.youtube:id/menu_item_search",
                    contentDescription = "Search",
                    boundsLeft = 860,
                    boundsTop = 80,
                    boundsRight = 960,
                    boundsBottom = 170,
                ),
                el(
                    index = 2,
                    resourceId = "com.google.android.youtube:id/pivot_bar_shorts",
                    text = "Shorts",
                    contentDescription = "Shorts",
                    boundsLeft = 240,
                    boundsTop = 2220,
                    boundsRight = 440,
                    boundsBottom = 2360,
                ),
                el(
                    index = 3,
                    resourceId = "com.google.android.youtube:id/pivot_bar_library",
                    text = "You",
                    contentDescription = "You",
                    boundsLeft = 860,
                    boundsTop = 2220,
                    boundsRight = 1040,
                    boundsBottom = 2360,
                ),
            ),
        )

        val step = ladder.locateTargetOnScreen(
            screen = ytHome,
            targetQuery = "Search",
            expectedRole = UiElementRole.SEARCH_TRIGGER,
        )

        assertTrue(step.success)
        assertTrue(step.verified)
        val target = step.targetElement
        assertNotNull(target)
        assertEquals("com.google.android.youtube:id/menu_item_search", target!!.resourceId)
        assertFalse(target.primaryLabel.contains("Shorts", ignoreCase = true))
    }

    @Test
    fun `Rung 4 verification detects injected wrong tap on Shorts, backs out, invalidates cache, and retries Search`() {
        val cache = SelectorCache()
        val ladder = PerceptionLadderEngine(selectorCache = cache)

        val ytHome = screen(
            packageName = "com.google.android.youtube",
            elements = listOf(
                el(
                    index = 1,
                    resourceId = "com.google.android.youtube:id/menu_item_search",
                    contentDescription = "Search",
                    boundsLeft = 860,
                    boundsTop = 80,
                    boundsRight = 960,
                    boundsBottom = 170,
                ),
            ),
        )
        // Seed a stale selector in cache
        cache.putVerified(
            CachedNodeSelector(
                packageName = "com.google.android.youtube",
                appVersionCode = 1L,
                intentKey = "search_youtube",
                resourceId = "com.google.android.youtube:id/stale_shorts_btn",
            ),
        )
        assertEquals(1, cache.size())

        // Simulate an injected wrong tap that landed on YouTube Shorts reel player instead of opening keyboard/search
        val wrongShortsScreen = screen(
            packageName = "com.google.android.youtube",
            elements = listOf(
                el(
                    index = 1,
                    resourceId = "com.google.android.youtube:id/reel_player_page_container",
                    contentDescription = "Shorts video player",
                ),
            ),
            keyboardVisible = false,
        )

        val correctSearchScreen = screen(
            packageName = "com.google.android.youtube",
            elements = listOf(
                el(
                    index = 1,
                    resourceId = "com.google.android.youtube:id/search_edit_text",
                    text = "",
                    contentDescription = "Search YouTube",
                    className = "android.widget.EditText",
                    editable = true,
                    focused = true,
                ),
            ),
            keyboardVisible = true,
        )

        var backOutCalled = false
        var retryCalled = false

        val outcome = ladder.verifyStepAndRecover(
            beforeScreen = ytHome,
            afterFirstAttemptScreen = wrongShortsScreen,
            expectedPostCondition = ExpectedPostCondition.SEARCH_FIELD_FOCUSED,
            targetQuery = "search_youtube",
            appVersionCode = 1L,
            onBackOut = {
                backOutCalled = true
                ytHome
            },
            onRetryWithNextRung = { _ ->
                retryCalled = true
                true to correctSearchScreen
            },
        )

        assertTrue("Must call backOut when wrong tap opens Shorts", backOutCalled)
        assertTrue("Must retry with alternate rung after backOut", retryCalled)
        assertEquals("Stale cached selector must be invalidated", 0, cache.size())
        assertTrue(outcome.success)
        assertTrue(outcome.verified)
        assertTrue(outcome.recoveryUsed)
        assertEquals(PerceptionRung.RUNG_4_POST_VERIFY, outcome.rungUsed)
    }

    @Test
    fun `PerceptionLadderEngine blocks banking and UPI apps and requires RED-tier approval for payment or delete buttons`() {
        val ladder = PerceptionLadderEngine()

        // 1. Google Pay (GPay) package must be hard-blocked
        val gpayScreen = screen(
            packageName = "com.google.android.apps.nbu.paisa.user",
            elements = listOf(
                el(index = 1, text = "Pay Rs 500"),
            ),
        )
        val blocked = ladder.locateTargetOnScreen(gpayScreen, "Pay Rs 500")
        assertFalse(blocked.success)
        assertTrue(blocked.blockedBySecureOrBanking)

        // 2. FLAG_SECURE blacked-out screen must be stopped immediately
        val normalAppScreen = screen(
            packageName = "com.instagram.android",
            elements = listOf(el(index = 1, text = "Continue")),
        )
        val secureStop = ladder.locateTargetOnScreen(
            screen = normalAppScreen,
            targetQuery = "Continue",
            visualEvidence = VisualEvidence(width = 1080, height = 2400, isSecureBlackout = true),
        )
        assertFalse(secureStop.success)
        assertTrue(secureStop.blockedBySecureOrBanking)

        // 3. Tapping "Confirm Payment" or "Delete Account" requires RED-tier owner approval
        val shoppingScreen = screen(
            packageName = "in.amazon.mShop.android.shopping",
            elements = listOf(
                el(
                    index = 1,
                    resourceId = "in.amazon.mShop.android.shopping:id/place_order_btn",
                    text = "Confirm Purchase",
                    contentDescription = "Confirm Purchase",
                ),
            ),
        )
        val unapproved = ladder.locateTargetOnScreen(
            screen = shoppingScreen,
            targetQuery = "Confirm Purchase",
            ownerApprovedRedTier = false,
        )
        assertFalse(unapproved.success)
        assertTrue(unapproved.blockedByRedTier)

        val approved = ladder.locateTargetOnScreen(
            screen = shoppingScreen,
            targetQuery = "Confirm Purchase",
            ownerApprovedRedTier = true,
        )
        assertTrue(approved.success)
        assertFalse(approved.blockedByRedTier)

        // 4. SetOfMarkAnnotator JSON response parser
        val parsed = SetOfMarkAnnotator.parseVisionResponse(
            """{"boxNumber": 4, "expectedResult": "opens search bar", "confidence": 0.94}""",
        )
        assertNotNull(parsed)
        assertEquals(4, parsed!!.boxNumber)
        assertEquals("opens search bar", parsed.expectedResult)
    }
}
