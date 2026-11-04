package com.jarvis.assistant.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Permission Tiers for Proactive Chief-of-Staff Autonomous Mitigation & In-Call Tool Execution.
 * - GREEN: Safe read-only checks, staging drafts, warming up backup resources, pausing non-essential rebills.
 * - AMBER: Reversible operational adjustments; executed automatically during pre-call mitigation only when enabled, always logged.
 * - RED: High-consequence / external / financial actions (sending invoices, shifting live ad budgets, confirming external client demos, placing calls, deleting data). NEVER run without explicit owner approval during the call.
 * Unknown tools ALWAYS default to RED.
 */
@Serializable
enum class PermissionTier {
    GREEN,
    AMBER,
    RED,
}

@Serializable
enum class IncidentSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

@Serializable
enum class TriageDecision {
    IGNORE,
    LOG_FOR_MORNING_BRIEF,
    NOTIFICATION,
    CALL,
}

@Serializable
data class PlannedToolMitigation(
    val toolName: String,
    val description: String,
    val spokenClaim: String,
    val parameters: Map<String, String> = emptyMap(),
    val explicitTierOverride: PermissionTier? = null,
)

@Serializable
data class PendingRedAction(
    val id: String = UUID.randomUUID().toString(),
    val incidentId: String,
    val toolName: String,
    val summary: String,
    val approvalQuestion: String,
    val parameters: Map<String, String> = emptyMap(),
    val requiresParameterName: String? = null,
    val parameterPromptQuestion: String? = null,
)

@Serializable
data class ProactiveIncidentEvent(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val severity: IncidentSeverity,
    val summary: String,
    val facts: Map<String, String> = emptyMap(),
    val moneyAtRiskUsd: Double = 0.0,
    val deadlineMinutes: Int = 120,
    val isReversible: Boolean = true,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val preCallMitigations: List<PlannedToolMitigation> = emptyList(),
    val pendingRedAction: PendingRedAction? = null,
    val oddsOrEta: String = "",
    val oneMoreThing: String? = null,
    val secondaryRedAction: PendingRedAction? = null,
    val forceDrivingContext: Boolean = false,
)

@Serializable
data class ActionLedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val incidentId: String,
    val toolName: String,
    val tier: PermissionTier,
    val summary: String,
    val spokenSummary: String,
    val success: Boolean,
    val verified: Boolean,
    val resultDetail: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
data class IncidentBriefing(
    val incidentId: String,
    val headline: String,
    val impactNumbers: List<String>,
    val actionsDone: List<String>,
    val oddsOrEta: String,
    val decisionNeeded: String,
    val oneMoreThing: String? = null,
    val requiresDrivingCheck: Boolean = false,
    val missingParameterQuestion: String? = null,
)

@Serializable
data class CallContextSignals(
    val isDriving: Boolean = false,
    val headphonesConnected: Boolean = false,
    val isDndActive: Boolean = false,
    val timeOfDayLabel: String = "daytime",
    val activityType: String = "stationary",
) {
    fun toPromptBlock(): String = buildString {
        appendLine("- Driving / In-Transit: $isDriving")
        appendLine("- Headphones Connected: $headphonesConnected")
        appendLine("- Do Not Disturb Active: $isDndActive")
        appendLine("- Time of Day: $timeOfDayLabel")
        appendLine("- Owner Activity: $activityType")
    }.trim()
}

@Serializable
data class TriageConfig(
    val moneyThresholdForCallUsd: Double = 250.0,
    val deadlineMinutesForCall: Int = 60,
    val minMinutesBetweenCalls: Int = 15,
    val maxCallsPerHour: Int = 3,
    val quietHoursStart: String = "22:30",
    val quietHoursEnd: String = "07:30",
    val notifyBeforeMediumCall: Boolean = true,
    val allowCriticalBreakthroughQuietHours: Boolean = true,
)

@Serializable
enum class CallLifecycleStatus {
    IDLE,
    RINGING,
    IN_CALL,
    UNANSWERED_FALLBACK,
    RETRY_SCHEDULED,
    COMPLETED,
    KILLED,
}

@Serializable
data class ActiveIncidentCallState(
    val sessionId: String = UUID.randomUUID().toString(),
    val incident: ProactiveIncidentEvent,
    val briefing: IncidentBriefing,
    val contextSignals: CallContextSignals,
    val status: CallLifecycleStatus = CallLifecycleStatus.RINGING,
    val turnCount: Int = 0,
    val drivingCheckCompleted: Boolean = false,
    val headlineDelivered: Boolean = false,
    val collectedParameterValue: String? = null,
    val primaryRedApproved: Boolean = false,
    val primaryRedExecuted: Boolean = false,
    val oneMoreThingOffered: Boolean = false,
    val secondaryRedExecuted: Boolean = false,
    val transcript: List<String> = emptyList(),
)

/**
 * Pluggable Watcher interface. Any new incident source (Stripe, Meta Ads, GitHub, Server Monitor,
 * NotificationListener, Calendar, Device Battery/Network) implements [IncidentWatcher].
 */
interface IncidentWatcher {
    val watcherId: String
    val displayName: String
    suspend fun pollEvents(): List<ProactiveIncidentEvent>
}

/**
 * Watcher 1: Urgent Notification Incident Watcher (parses critical business/payment/ad-account notifications).
 */
class NotificationIncidentWatcher(
    private val notificationProvider: () -> List<Pair<String, String>> = { emptyList() },
) : IncidentWatcher {
    override val watcherId: String = "notification_incident_watcher"
    override val displayName: String = "Urgent Business & Security Notifications"
    private val seenFingerprints = ConcurrentHashMap.newKeySet<String>()

    override suspend fun pollEvents(): List<ProactiveIncidentEvent> {
        val raw = runCatching { notificationProvider() }.getOrDefault(emptyList())
        val events = mutableListOf<ProactiveIncidentEvent>()
        for ((title, body) in raw) {
            val combined = "$title $body".trim()
            if (combined.isBlank()) continue
            val fingerprint = combined.lowercase().take(120)
            if (!seenFingerprints.add(fingerprint)) continue

            val lower = combined.lowercase()
            val isUrgentFinancial = lower.contains("restricted") ||
                lower.contains("fraud freeze") ||
                lower.contains("declined") ||
                lower.contains("invoice") ||
                lower.contains("approved final design")
            if (isUrgentFinancial) {
                events += ProactiveIncidentEvent(
                    source = watcherId,
                    severity = if (lower.contains("freeze") || lower.contains("restricted")) {
                        IncidentSeverity.HIGH
                    } else {
                        IncidentSeverity.MEDIUM
                    },
                    summary = combined.take(140),
                    facts = mapOf("notification_title" to title, "notification_body" to body),
                    moneyAtRiskUsd = if (lower.contains("freeze") || lower.contains("restricted")) 400.0 else 250.0,
                    deadlineMinutes = 30,
                    isReversible = true,
                )
            }
        }
        return events
    }
}

/**
 * Watcher 2: Calendar & Client Deadline Watcher.
 */
class CalendarDeadlineWatcher(
    private val upcomingDeadlinesProvider: () -> List<String> = { emptyList() },
) : IncidentWatcher {
    override val watcherId: String = "calendar_deadline_watcher"
    override val displayName: String = "Client Demos & Executive Deadlines"
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override suspend fun pollEvents(): List<ProactiveIncidentEvent> {
        val items = runCatching { upcomingDeadlinesProvider() }.getOrDefault(emptyList())
        return items.mapNotNull { item ->
            if (!reported.add(item)) return@mapNotNull null
            ProactiveIncidentEvent(
                source = watcherId,
                severity = IncidentSeverity.MEDIUM,
                summary = item,
                facts = mapOf("calendar_item" to item),
                moneyAtRiskUsd = 0.0,
                deadlineMinutes = 45,
                isReversible = true,
            )
        }
    }
}

/**
 * Watcher 3: Scheduled Device & Operations Health Watcher.
 */
class DeviceOperationsHealthWatcher(
    private val batteryLevelProvider: () -> Int = { 100 },
) : IncidentWatcher {
    override val watcherId: String = "device_operations_watcher"
    override val displayName: String = "Device & Connectivity Readiness"
    private var lowBatteryAlerted = false

    override suspend fun pollEvents(): List<ProactiveIncidentEvent> {
        val pct = runCatching { batteryLevelProvider() }.getOrDefault(100)
        if (pct in 1..12 && !lowBatteryAlerted) {
            lowBatteryAlerted = true
            return listOf(
                ProactiveIncidentEvent(
                    source = watcherId,
                    severity = IncidentSeverity.LOW,
                    summary = "Phone battery is down to $pct percent.",
                    facts = mapOf("battery_percent" to "$pct percent"),
                    moneyAtRiskUsd = 0.0,
                    deadlineMinutes = 30,
                    isReversible = true,
                ),
            )
        }
        if (pct > 25) lowBatteryAlerted = false
        return emptyList()
    }
}

/**
 * Built-in Simulation Scenarios (A, B, C) for `simulate <scenario>` debug command & UI buttons.
 */
object SimulationScenarioFactory {

    fun createScenario(code: String): ProactiveIncidentEvent {
        val normalized = code.trim().uppercase()
        return when {
            normalized == "A" || normalized.contains("ROOF") || normalized.contains("INVOICE") -> scenarioA()
            normalized == "B" || normalized.contains("META") || normalized.contains("AD") -> scenarioB()
            normalized == "C" || normalized.contains("DRIV") || normalized.contains("BANK") || normalized.contains("FREEZE") -> scenarioC()
            else -> scenarioA()
        }
    }

    /**
     * Scenario A: Roofing client approved final design; production deploy staged & invoice drafted;
     * Jarvis asks what price to put on the invoice ("Four grand"), then asks approval to send & push live.
     */
    fun scenarioA(): ProactiveIncidentEvent {
        val incidentId = "sim-scenario-a"
        return ProactiveIncidentEvent(
            id = incidentId,
            source = "simulation_a",
            severity = IncidentSeverity.HIGH,
            summary = "The roofing client just approved the final design.",
            facts = mapOf(
                "client" to "roofing client",
                "event" to "approved the final design",
                "deploy_status" to "staged production deploy",
                "invoice_status" to "drafted invoice awaiting price",
            ),
            moneyAtRiskUsd = 4000.0,
            deadlineMinutes = 30,
            isReversible = false,
            preCallMitigations = listOf(
                PlannedToolMitigation(
                    toolName = "stage_production_deploy",
                    description = "Stage the production website deployment for the roofing client",
                    spokenClaim = "I've staged the production deploy",
                    parameters = mapOf("client" to "roofing_client"),
                    explicitTierOverride = PermissionTier.GREEN,
                ),
                PlannedToolMitigation(
                    toolName = "draft_client_invoice",
                    description = "Create a draft invoice for the roofing client",
                    spokenClaim = "drafted the invoice",
                    parameters = mapOf("client" to "roofing_client"),
                    explicitTierOverride = PermissionTier.GREEN,
                ),
            ),
            pendingRedAction = PendingRedAction(
                incidentId = incidentId,
                toolName = "send_invoice_and_deploy_site",
                summary = "Send the invoice and push the roofing client site live",
                approvalQuestion = "Do I have approval to send the invoice and push the site live?",
                parameters = mapOf("client" to "roofing_client"),
                requiresParameterName = "invoice_price",
                parameterPromptQuestion = "What price should I put on it?",
            ),
            oddsOrEta = "Ready to go live within two minutes of approval.",
        )
    }

    /**
     * Scenario B: Meta restricted main ad account 17 minutes ago; spend was about $400/day;
     * automated policy flag on yesterday's creative; Jarvis already filed the appeal and started warming up
     * the backup account; odds 50-50, takes a day or two; asks approval to shift 50% of daily budget to backup.
     */
    fun scenarioB(): ProactiveIncidentEvent {
        val incidentId = "sim-scenario-b"
        return ProactiveIncidentEvent(
            id = incidentId,
            source = "simulation_b",
            severity = IncidentSeverity.CRITICAL,
            summary = "Meta just restricted our main ad account seventeen minutes ago. Spend was about four hundred a day.",
            facts = mapOf(
                "platform" to "Meta",
                "account" to "main ad account",
                "time_ago" to "seventeen minutes ago",
                "daily_spend" to "about four hundred a day",
                " daily_spend_numeric" to "400",
                "minutes_ago_numeric" to "17",
                "budget_shift_percent" to "fifty percent",
                "cause" to "Automated policy flag on yesterday's creative",
                "odds" to "fifty-fifty",
                "eta" to "a day or two",
            ),
            moneyAtRiskUsd = 400.0,
            deadlineMinutes = 15,
            isReversible = true,
            preCallMitigations = listOf(
                PlannedToolMitigation(
                    toolName = "file_ad_account_appeal",
                    description = "File automated policy flag appeal on Meta main ad account",
                    spokenClaim = "I've already filed the appeal",
                    parameters = mapOf("account" to "meta_main", "reason" to "false_positive_creative_flag"),
                    explicitTierOverride = PermissionTier.GREEN,
                ),
                PlannedToolMitigation(
                    toolName = "warm_up_backup_ad_account",
                    description = "Start warming up backup Meta ad account",
                    spokenClaim = "started warming up the backup account",
                    parameters = mapOf("account" to "meta_backup"),
                    explicitTierOverride = PermissionTier.GREEN,
                ),
            ),
            pendingRedAction = PendingRedAction(
                incidentId = incidentId,
                toolName = "shift_ad_budget_to_backup",
                summary = "Shift fifty percent of daily budget to the backup account",
                approvalQuestion = "Want me to shift fifty percent of daily budget to the backup account now?",
                parameters = mapOf("percentage" to "50", "target" to "meta_backup"),
            ),
            oddsOrEta = "Honestly, fifty-fifty. Usually takes a day or two.",
        )
    }

    /**
     * Scenario C: Owner is driving into a meeting; 6 new clients closed today for $19,000 total;
     * bank triggered a fraud freeze on the operating card due to unusual spike in software charges
     * after the new onboarding batch; Jarvis paused non-essential rebills so nothing bounces;
     * can text verification link; plus ONE "One more thing": med spa lead booked a demo for Thursday at 2.
     */
    fun scenarioC(): ProactiveIncidentEvent {
        val incidentId = "sim-scenario-c"
        return ProactiveIncidentEvent(
            id = incidentId,
            source = "simulation_c",
            severity = IncidentSeverity.CRITICAL,
            summary = "Six new clients closed today for nineteen thousand total, and the bank triggered a fraud freeze on the operating card.",
            facts = mapOf(
                "new_clients" to "Six new clients",
                "new_clients_numeric" to "6",
                "revenue_closed" to "nineteen thousand",
                "revenue_numeric" to "19000",
                "issue" to "the bank triggered a fraud freeze on the operating card",
                "cause" to "Unusual spike in software charges after the new onboarding batch",
                "one_more_thing_lead" to "the med spa lead just booked a demo for Thursday at two",
            ),
            moneyAtRiskUsd = 19000.0,
            deadlineMinutes = 20,
            isReversible = true,
            forceDrivingContext = true,
            preCallMitigations = listOf(
                PlannedToolMitigation(
                    toolName = "pause_non_essential_rebills",
                    description = "Pause non-essential software rebills on operating card so nothing bounces",
                    spokenClaim = "I've paused non-essential rebills so nothing bounces",
                    parameters = mapOf("card" to "operating_card"),
                    explicitTierOverride = PermissionTier.GREEN,
                ),
            ),
            pendingRedAction = PendingRedAction(
                incidentId = incidentId,
                toolName = "send_bank_verification_link_sms",
                summary = "Text the bank fraud verification link to owner's phone",
                approvalQuestion = "Want me to text you the verification link so you can clear it when you park?",
                parameters = mapOf("channel" to "sms"),
            ),
            oddsOrEta = "Clears immediately once you tap the bank verification link.",
            oneMoreThing = "the med spa lead just booked a demo for Thursday at two",
            secondaryRedAction = PendingRedAction(
                incidentId = incidentId,
                toolName = "confirm_calendar_demo",
                summary = "Confirm the med spa demo for Thursday at two",
                approvalQuestion = "Want me to confirm it?",
                parameters = mapOf("lead" to "med_spa", "slot" to "Thursday 14:00"),
            ),
        )
    }
}

/**
 * ActionLedger: Single source of truth for every tool action Jarvis attempted or completed.
 * Prevents phantom action claims in speech.
 */
class ActionLedger {
    private val entries = CopyOnWriteArrayList<ActionLedgerEntry>()

    fun record(
        incidentId: String,
        toolName: String,
        tier: PermissionTier,
        summary: String,
        spokenSummary: String,
        success: Boolean,
        verified: Boolean,
        resultDetail: String,
    ): ActionLedgerEntry {
        val entry = ActionLedgerEntry(
            incidentId = incidentId,
            toolName = toolName,
            tier = tier,
            summary = summary,
            spokenSummary = spokenSummary,
            success = success,
            verified = verified,
            resultDetail = resultDetail,
        )
        entries.add(entry)
        return entry
    }

    fun verifiedActionsForIncident(incidentId: String): List<ActionLedgerEntry> =
        entries.filter { it.incidentId == incidentId && it.success && it.verified }

    fun allEntriesForIncident(incidentId: String): List<ActionLedgerEntry> =
        entries.filter { it.incidentId == incidentId }

    fun allEntries(): List<ActionLedgerEntry> = entries.toList()

    fun hasVerifiedActionMatching(incidentId: String, claimSentence: String): Boolean {
        val verified = verifiedActionsForIncident(incidentId)
        if (verified.isEmpty()) return false
        val lowerClaim = claimSentence.lowercase()
        val actionKeywords = listOf(
            "staged" to listOf("stage", "deploy"),
            "drafted" to listOf("draft", "invoice"),
            "filed" to listOf("appeal", "file"),
            "appealed" to listOf("appeal", "file"),
            "warming up" to listOf("warm", "backup"),
            "paused" to listOf("pause", "rebill"),
            "sent" to listOf("send", "sms", "invoice", "link"),
            "shifted" to listOf("shift", "budget"),
            "confirmed" to listOf("confirm", "demo", "calendar"),
            "locked in" to listOf("confirm", "demo"),
            "booked" to listOf("book", "demo", "calendar"),
        )
        for ((claimTrigger, requiredTokens) in actionKeywords) {
            if (Regex("\\b${Regex.escape(claimTrigger)}\\b").containsMatchIn(lowerClaim)) {
                val matched = verified.any { entry ->
                    val hay = "${entry.toolName} ${entry.summary} ${entry.spokenSummary}".lowercase()
                    requiredTokens.any { hay.contains(it) }
                }
                if (!matched) return false
            }
        }
        return true
    }

    fun formatForPrompt(incidentId: String): String {
        val list = allEntriesForIncident(incidentId)
        if (list.isEmpty()) return "- No automated actions have been executed for this incident."
        return list.joinToString("\n") { entry ->
            val status = if (entry.success && entry.verified) "VERIFIED_SUCCESS" else "NOT_COMPLETED"
            "- [$status] tool=${entry.toolName} (${entry.tier}): ${entry.spokenSummary} (${entry.resultDetail})"
        }
    }

    fun clear() {
        entries.clear()
    }
}

/**
 * HonestyGuardrail:
 * 1. Verifies that any sentence claiming a completed action ("I've already...", "I'm on...", "I sent...",
 *    "I appealed...", "I staged...", "I drafted...", "I paused...") has a matching verified entry in [ActionLedger].
 *    If not, rewrites the sentence honestly.
 * 2. Verifies spoken numbers against known incident facts, ledger entries, and owner-provided values;
 *    if an unverified number is invented, replaces with "I don't have that figure yet."
 * 3. Enforces TTS call rules: strips markdown/emojis/exclamation marks/filler, caps word count at <=35 words
 *    (unless owner requested detail), and enforces at most ONE "One more thing" per call.
 */
object HonestyGuardrail {

    private val phantomActionClaimRegex = Regex(
        pattern = "\\b(i've already|i have already|i already|i've staged|i've drafted|i've filed|i filed|i appealed|i've paused|i paused|i sent|i've sent|i booked|i've booked|i shifted|i've shifted|i confirmed|i've confirmed|i'm on it and already)\\b",
        option = RegexOption.IGNORE_CASE,
    )

    private val fillerPrefixRegex = Regex(
        pattern = "^(certainly[,!.]?\\s*|i'd be happy to[,!.]?\\s*|i would be happy to[,!.]?\\s*|absolutely[,!.]?\\s*|great question[,!.]?\\s*|as an ai[,!.]?\\s*)+",
        option = RegexOption.IGNORE_CASE,
    )

    private val numberWordSet = setOf(
        "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "grand", "million",
    )

    private val allowedConversationalNumbers = setOf(
        "one", "two", "first", "second", "fifty-fifty",
    )

    fun enforce(
        rawReply: String,
        incident: ProactiveIncidentEvent,
        actionLedger: ActionLedger,
        ownerProvidedValues: Set<String> = emptySet(),
        oneMoreThingAlreadyUsed: Boolean = false,
        ownerAskedForDetail: Boolean = false,
    ): String {
        // 1. Strip markdown, bullets, exclamation marks, and filler
        var cleaned = rawReply
            .replace(Regex("[*_`#~>•]"), "")
            .replace("!", ".")
            .replace(Regex("\\.+"), ".")
            .trim()
        cleaned = fillerPrefixRegex.replace(cleaned, "").trim()

        // 2. Split into sentences and inspect each sentence
        val sentences = cleaned.split(Regex("(?<=[.?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        val verifiedSentences = mutableListOf<String>()
        var seenOneMoreThingInThisTurn = false

        val allowedFactCorpus = buildString {
            append(incident.summary).append(' ')
            incident.facts.values.forEach { append(it).append(' ') }
            append(incident.oddsOrEta).append(' ')
            append(incident.oneMoreThing.orEmpty()).append(' ')
            incident.pendingRedAction?.let {
                append(it.summary).append(' ').append(it.approvalQuestion).append(' ')
            }
            incident.secondaryRedAction?.let {
                append(it.summary).append(' ').append(it.approvalQuestion).append(' ')
            }
            actionLedger.verifiedActionsForIncident(incident.id).forEach {
                append(it.summary).append(' ').append(it.spokenSummary).append(' ').append(it.resultDetail).append(' ')
            }
            ownerProvidedValues.forEach { append(it).append(' ') }
        }.lowercase()

        for (sentence in sentences) {
            val lower = sentence.lowercase()

            // Enforce at most ONE "One more thing" per call
            if (lower.contains("one more thing")) {
                if (oneMoreThingAlreadyUsed || seenOneMoreThingInThisTurn) {
                    continue
                }
                seenOneMoreThingInThisTurn = true
            }

            // Check for phantom action claims
            if (phantomActionClaimRegex.containsMatchIn(lower)) {
                val verified = actionLedger.hasVerifiedActionMatching(incident.id, sentence)
                if (!verified) {
                    verifiedSentences += "I haven't executed that action yet, sir."
                    continue
                }
            }

            // Check for unverified numeric claims (digits or spoken financial/metric numbers)
            if (containsUnverifiedNumber(lower, allowedFactCorpus)) {
                verifiedSentences += "I don't have that figure yet."
                continue
            }

            verifiedSentences += sentence
        }

        val joined = verifiedSentences.joinToString(" ").ifBlank { "Standing by, sir." }

        // 3. Enforce max 35 words per turn unless owner explicitly asked for detail
        val maxWords = if (ownerAskedForDetail) 75 else 35
        val words = joined.split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size <= maxWords) {
            joined
        } else {
            val truncated = words.take(maxWords).joinToString(" ").trimEnd(',', ';', ':')
            if (truncated.endsWith(".") || truncated.endsWith("?")) truncated else "$truncated."
        }
    }

    private fun containsUnverifiedNumber(sentenceLower: String, allowedCorpusLower: String): Boolean {
        // Check explicit digit sequences (e.g. "400", "4000", "17", "19000")
        val digitMatches = Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(sentenceLower).map { it.value }.toList()
        for (digit in digitMatches) {
            val normalizedDigit = digit.replace(",", "")
            if (!allowedCorpusLower.contains(normalizedDigit) && !allowedCorpusLower.contains(digit)) {
                return true
            }
        }

        // Check spoken number words
        val tokens = sentenceLower.replace(Regex("[^a-z0-9-]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        for (token in tokens) {
            if (token in allowedConversationalNumbers) continue
            if (token in numberWordSet) {
                if (!allowedCorpusLower.contains(token)) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * IncidentTriageEngine: Scores incoming events based on money at risk, deadline, reversibility,
 * rate limits, quiet hours, and DND.
 */
class IncidentTriageEngine(
    private var config: TriageConfig = TriageConfig(),
) {
    private val recentCallTimestampsMs = CopyOnWriteArrayList<Long>()

    fun updateConfig(newConfig: TriageConfig) {
        config = newConfig
    }

    fun currentConfig(): TriageConfig = config

    fun evaluate(
        event: ProactiveIncidentEvent,
        contextSignals: CallContextSignals,
        nowTime: LocalTime = LocalTime.now(),
        nowEpochMs: Long = System.currentTimeMillis(),
        isSimulation: Boolean = false,
    ): TriageDecision {
        // Simulation events built for interactive call testing always route to CALL
        if (isSimulation) return TriageDecision.CALL

        // Prune call history older than 1 hour
        val oneHourAgo = nowEpochMs - 3_600_000L
        recentCallTimestampsMs.removeIf { it < oneHourAgo }

        var score = 0
        when (event.severity) {
            IncidentSeverity.CRITICAL -> score += 85
            IncidentSeverity.HIGH -> score += 65
            IncidentSeverity.MEDIUM -> score += 40
            IncidentSeverity.LOW -> score += 15
        }
        if (event.moneyAtRiskUsd >= config.moneyThresholdForCallUsd) score += 30
        if (event.deadlineMinutes <= config.deadlineMinutesForCall) score += 20
        if (!event.isReversible) score += 15

        val inQuietHours = isWithinQuietHours(nowTime, config.quietHoursStart, config.quietHoursEnd)
        if ((inQuietHours || contextSignals.isDndActive) &&
            !(config.allowCriticalBreakthroughQuietHours && event.severity == IncidentSeverity.CRITICAL)
        ) {
            return if (score >= 40) TriageDecision.NOTIFICATION else TriageDecision.LOG_FOR_MORNING_BRIEF
        }

        val lastCallMs = recentCallTimestampsMs.maxOrNull() ?: 0L
        val minutesSinceLastCall = (nowEpochMs - lastCallMs) / 60_000L
        val rateLimited = recentCallTimestampsMs.size >= config.maxCallsPerHour ||
            (lastCallMs > 0L && minutesSinceLastCall < config.minMinutesBetweenCalls)

        return when {
            score >= 75 && !rateLimited -> {
                recentCallTimestampsMs.add(nowEpochMs)
                TriageDecision.CALL
            }
            score >= 40 -> TriageDecision.NOTIFICATION
            score >= 15 -> TriageDecision.LOG_FOR_MORNING_BRIEF
            else -> TriageDecision.IGNORE
        }
    }

    private fun isWithinQuietHours(now: LocalTime, startStr: String, endStr: String): Boolean {
        val start = runCatching { LocalTime.parse(startStr) }.getOrDefault(LocalTime.of(22, 30))
        val end = runCatching { LocalTime.parse(endStr) }.getOrDefault(LocalTime.of(7, 30))
        return if (start.isBefore(end)) {
            !now.isBefore(start) && now.isBefore(end)
        } else {
            !now.isBefore(start) || now.isBefore(end)
        }
    }
}

/**
 * BriefingBuilder: Constructs the ground-truth [IncidentBriefing] strictly from the event facts
 * and verified [ActionLedger] entries.
 */
object BriefingBuilder {
    fun build(
        event: ProactiveIncidentEvent,
        actionLedger: ActionLedger,
        contextSignals: CallContextSignals,
    ): IncidentBriefing {
        val verifiedActions = actionLedger.verifiedActionsForIncident(event.id)
            .map { it.spokenSummary }
        val impactNumbers = mutableListOf<String>()
        if (event.moneyAtRiskUsd > 0.0) {
            impactNumbers += "$${event.moneyAtRiskUsd.toLong()}"
        }
        event.facts.forEach { (k, v) ->
            if (k.contains("spend") || k.contains("revenue") || k.contains("clients") || k.contains("time")) {
                impactNumbers += v
            }
        }
        val decisionNeeded = event.pendingRedAction?.approvalQuestion
            ?: "Let me know how you'd like to proceed."
        return IncidentBriefing(
            incidentId = event.id,
            headline = event.summary,
            impactNumbers = impactNumbers.distinct(),
            actionsDone = verifiedActions,
            oddsOrEta = event.oddsOrEta,
            decisionNeeded = decisionNeeded,
            oneMoreThing = event.oneMoreThing,
            requiresDrivingCheck = event.forceDrivingContext || contextSignals.isDriving,
            missingParameterQuestion = event.pendingRedAction?.parameterPromptQuestion,
        )
    }

    fun formatForPrompt(briefing: IncidentBriefing): String = buildString {
        appendLine("- Headline: ${briefing.headline}")
        if (briefing.impactNumbers.isNotEmpty()) {
            appendLine("- Verified Impact Figures: ${briefing.impactNumbers.joinToString(", ")}")
        }
        if (briefing.actionsDone.isNotEmpty()) {
            appendLine("- Actions Already Completed (from ActionLedger): ${briefing.actionsDone.joinToString("; ")}")
        } else {
            appendLine("- Actions Already Completed (from ActionLedger): NONE")
        }
        if (briefing.oddsOrEta.isNotBlank()) {
            appendLine("- Honest Odds / ETA: ${briefing.oddsOrEta}")
        }
        if (!briefing.missingParameterQuestion.isNullOrBlank()) {
            appendLine("- Parameter Needed Before Approval: ${briefing.missingParameterQuestion}")
        }
        appendLine("- Primary Decision Needed From Owner: ${briefing.decisionNeeded}")
        if (!briefing.oneMoreThing.isNullOrBlank()) {
            appendLine("- Optional 'One More Thing' (use at most once after primary decision): ${briefing.oneMoreThing}")
        }
    }.trim()
}

/**
 * ProactiveIncidentCallEngine:
 * Orchestrates Watchers -> Triage -> Pre-Call Mitigation (GREEN/AMBER) -> ActionLedger ->
 * BriefingBuilder -> Incoming Call Delivery -> Realtime Turn Handling -> Holding Line ("One moment.") ->
 * RED Action Approval Execution -> Post-Call Memory Persistence -> Global Kill Switch.
 */
class ProactiveIncidentCallEngine(
    private val toolExecutor: suspend (toolName: String, params: Map<String, String>) -> Pair<Boolean, String> = { tool, params ->
        true to "Executed $tool(${params.entries.joinToString { "${it.key}=${it.value}" }})"
    },
    private val onRingIncomingCall: (ActiveIncidentCallState) -> Unit = {},
    private val onSendNotification: (title: String, body: String) -> Unit = { _, _ -> },
    private val onSpeakLine: (String) -> Unit = {},
    private val onSaveDecisionMemory: suspend (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val actionLedger = ActionLedger()
    val triageEngine = IncidentTriageEngine()

    private val watchers = CopyOnWriteArrayList<IncidentWatcher>()
    private val toolPermissionTiers = ConcurrentHashMap<String, PermissionTier>().apply {
        // Default GREEN tools (read-only checks, staging drafts, safe reversible prep)
        put("stage_production_deploy", PermissionTier.GREEN)
        put("draft_client_invoice", PermissionTier.GREEN)
        put("file_ad_account_appeal", PermissionTier.GREEN)
        put("warm_up_backup_ad_account", PermissionTier.GREEN)
        put("pause_non_essential_rebills", PermissionTier.GREEN)
        put("device_status", PermissionTier.GREEN)
        put("list_notifications", PermissionTier.GREEN)
        // Default AMBER tools
        put("create_calendar_event", PermissionTier.AMBER)
        // Default RED tools (external / financial / irreversible — require explicit approval)
        put("send_invoice_and_deploy_site", PermissionTier.RED)
        put("shift_ad_budget_to_backup", PermissionTier.RED)
        put("send_bank_verification_link_sms", PermissionTier.RED)
        put("confirm_calendar_demo", PermissionTier.RED)
        put("place_phone_call", PermissionTier.RED)
        put("send_sms", PermissionTier.RED)
    }

    private val _killSwitchActive = MutableStateFlow(false)
    val killSwitchActive: StateFlow<Boolean> = _killSwitchActive.asStateFlow()

    private val _activeCallState = MutableStateFlow<ActiveIncidentCallState?>(null)
    val activeCallState: StateFlow<ActiveIncidentCallState?> = _activeCallState.asStateFlow()

    private val _morningBriefLog = MutableStateFlow<List<ProactiveIncidentEvent>>(emptyList())
    val morningBriefLog: StateFlow<List<ProactiveIncidentEvent>> = _morningBriefLog.asStateFlow()

    private var unansweredTimeoutJob: Job? = null
    private var retryCallJob: Job? = null

    fun registerWatcher(watcher: IncidentWatcher) {
        watchers.removeAll { it.watcherId == watcher.watcherId }
        watchers.add(watcher)
    }

    fun registeredWatchers(): List<IncidentWatcher> = watchers.toList()

    /**
     * Unknown tools always default to [PermissionTier.RED] for safety.
     */
    fun getPermissionTier(toolName: String): PermissionTier =
        toolPermissionTiers[toolName] ?: PermissionTier.RED

    fun setPermissionTier(toolName: String, tier: PermissionTier) {
        toolPermissionTiers[toolName] = tier
    }

    fun allPermissionTiers(): Map<String, PermissionTier> = toolPermissionTiers.toMap()

    /**
     * Global Kill Switch: immediately halts all autonomous actions, cancels ringing/active calls,
     * and blocks new watcher actions until disabled.
     */
    fun setKillSwitch(enabled: Boolean) {
        _killSwitchActive.value = enabled
        if (enabled) {
            unansweredTimeoutJob?.cancel()
            retryCallJob?.cancel()
            _activeCallState.update { current ->
                current?.copy(status = CallLifecycleStatus.KILLED)
            }
        }
    }

    /**
     * Trigger a built-in simulation ("simulate A", "simulate B", "simulate C") end-to-end.
     */
    suspend fun triggerSimulation(
        scenarioCode: String,
        contextSignalsOverride: CallContextSignals? = null,
    ): ActiveIncidentCallState? {
        val event = SimulationScenarioFactory.createScenario(scenarioCode)
        val signals = contextSignalsOverride ?: CallContextSignals(
            isDriving = event.forceDrivingContext,
            timeOfDayLabel = "daytime",
            activityType = if (event.forceDrivingContext) "driving" else "office",
        )
        return handleIncidentEvent(
            event = event,
            contextSignals = signals,
            isSimulation = true,
            ringTimeoutMs = 30_000L,
        )
    }

    /**
     * Full pipeline: Triage -> Act First (GREEN/AMBER mitigations) -> ActionLedger ->
     * BriefingBuilder -> Call Delivery / Notification.
     */
    suspend fun handleIncidentEvent(
        event: ProactiveIncidentEvent,
        contextSignals: CallContextSignals = CallContextSignals(isDriving = event.forceDrivingContext),
        isSimulation: Boolean = false,
        ringTimeoutMs: Long = 30_000L,
    ): ActiveIncidentCallState? {
        if (_killSwitchActive.value) return null

        val decision = triageEngine.evaluate(
            event = event,
            contextSignals = contextSignals,
            isSimulation = isSimulation,
        )

        when (decision) {
            TriageDecision.IGNORE -> return null
            TriageDecision.LOG_FOR_MORNING_BRIEF -> {
                _morningBriefLog.update { (it + event).takeLast(50) }
                return null
            }
            TriageDecision.NOTIFICATION -> {
                val decisionLine = event.pendingRedAction?.approvalQuestion ?: "Tap to review."
                onSendNotification("JARVIS — ${event.summary}", decisionLine)
                return null
            }
            TriageDecision.CALL -> {
                if (event.severity == IncidentSeverity.MEDIUM && triageEngine.currentConfig().notifyBeforeMediumCall) {
                    onSendNotification("JARVIS Incoming Briefing", event.summary)
                }
            }
        }

        // Act First: Execute only GREEN and AMBER pre-call mitigations; never execute RED before owner approval.
        for (mitigation in event.preCallMitigations) {
            if (_killSwitchActive.value) return null
            val tier = mitigation.explicitTierOverride ?: getPermissionTier(mitigation.toolName)
            if (tier == PermissionTier.RED) {
                // Never auto-run RED actions before the call
                continue
            }
            val (ok, detail) = runCatching {
                toolExecutor(mitigation.toolName, mitigation.parameters)
            }.getOrElse { false to (it.message ?: "Tool execution error") }

            actionLedger.record(
                incidentId = event.id,
                toolName = mitigation.toolName,
                tier = tier,
                summary = mitigation.description,
                spokenSummary = mitigation.spokenClaim,
                success = ok,
                verified = ok,
                resultDetail = detail,
            )
        }

        val briefing = BriefingBuilder.build(event, actionLedger, contextSignals)
        val callState = ActiveIncidentCallState(
            incident = event,
            briefing = briefing,
            contextSignals = contextSignals,
            status = CallLifecycleStatus.RINGING,
        )
        _activeCallState.value = callState
        onRingIncomingCall(callState)

        // Schedule 30-second unanswered call fallback
        unansweredTimeoutJob?.cancel()
        if (ringTimeoutMs > 0L) {
            unansweredTimeoutJob = scope.launch {
                delay(ringTimeoutMs)
                handleUnansweredCallTimeout(callState.sessionId)
            }
        }

        return callState
    }

    /**
     * Called when the owner answers the incoming JARVIS call.
     * Jarvis always opens with "Sir." (or "Sir. Are you driving?" if driving check is required).
     */
    fun onCallAnswered(): String {
        unansweredTimeoutJob?.cancel()
        val current = _activeCallState.value ?: return "Sir."
        if (_killSwitchActive.value) return "Call halted by kill switch."

        val openingLine = if (current.briefing.requiresDrivingCheck && !current.drivingCheckCompleted) {
            "Sir. Are you driving?"
        } else {
            "Sir."
        }

        _activeCallState.value = current.copy(
            status = CallLifecycleStatus.IN_CALL,
            turnCount = 1,
            transcript = current.transcript + "Jarvis: $openingLine",
        )
        onSpeakLine(openingLine)
        return openingLine
    }

    /**
     * If unanswered in ~30s: send a one-line notification with headline + decision needed,
     * and schedule a single retry after delay for HIGH/CRITICAL severity only.
     */
    fun handleUnansweredCallTimeout(sessionId: String, scheduleRetryDelayMs: Long = 300_000L): String? {
        val current = _activeCallState.value ?: return null
        if (current.sessionId != sessionId || current.status != CallLifecycleStatus.RINGING) return null

        val oneLine = "${current.briefing.headline} — ${current.briefing.decisionNeeded}"
        onSendNotification("Missed Call from JARVIS", oneLine)

        val shouldRetry = current.incident.severity == IncidentSeverity.HIGH ||
            current.incident.severity == IncidentSeverity.CRITICAL

        _activeCallState.value = current.copy(
            status = if (shouldRetry) CallLifecycleStatus.RETRY_SCHEDULED else CallLifecycleStatus.UNANSWERED_FALLBACK,
        )

        if (shouldRetry && scheduleRetryDelayMs > 0L && !_killSwitchActive.value) {
            retryCallJob?.cancel()
            retryCallJob = scope.launch {
                delay(scheduleRetryDelayMs)
                if (!_killSwitchActive.value && _activeCallState.value?.sessionId == sessionId) {
                    val retryState = current.copy(status = CallLifecycleStatus.RINGING)
                    _activeCallState.value = retryState
                    onRingIncomingCall(retryState)
                }
            }
        }
        return oneLine
    }

    /**
     * Executes an in-call tool with the "One moment." holding line if the tool takes >2 seconds.
     */
    suspend fun executeInCallToolWithHoldingLine(
        incidentId: String,
        toolName: String,
        summary: String,
        spokenSummary: String,
        parameters: Map<String, String>,
        tier: PermissionTier,
    ): ActionLedgerEntry {
        var holdingLineSpoken = false
        val holdJob = scope.launch {
            delay(2_000L)
            holdingLineSpoken = true
            onSpeakLine("One moment.")
        }
        val (ok, detail) = try {
            withTimeoutOrNull(15_000L) {
                toolExecutor(toolName, parameters)
            } ?: (false to "Timed out after 15s")
        } catch (t: Throwable) {
            false to (t.message ?: "Execution failed")
        } finally {
            holdJob.cancel()
        }

        return actionLedger.record(
            incidentId = incidentId,
            toolName = toolName,
            tier = tier,
            summary = summary,
            spokenSummary = if (holdingLineSpoken) "One moment. $spokenSummary" else spokenSummary,
            success = ok,
            verified = ok,
            resultDetail = detail,
        )
    }

    /**
     * Processes the owner's spoken or typed turn during an active call, following the exact
     * Chief-of-Staff rules, honesty guardrails, RED approval gate, and post-call memory saving.
     */
    suspend fun processOwnerCallTurn(ownerUtterance: String): String {
        val state = _activeCallState.value ?: return "No active call session, sir."
        if (_killSwitchActive.value) return "Autonomous actions are currently disabled, sir."

        val cleanUtterance = ownerUtterance.trim()
        val lower = cleanUtterance.lowercase()
        val ownerAskedForDetail = lower.contains("detail") || lower.contains("explain in full") || lower.contains("break it down")
        val ownerProvidedValues = mutableSetOf<String>()
        state.collectedParameterValue?.let { ownerProvidedValues.add(it) }

        // 1. Handle driving check response if pending
        if (state.briefing.requiresDrivingCheck && !state.drivingCheckCompleted) {
            val isDrivingConfirmed = lower.contains("yeah") || lower.contains("yes") ||
                lower.contains("driving") || lower.contains("heading") || lower.contains("car")
            val rawReply = buildString {
                if (isDrivingConfirmed) {
                    append("Then I'll keep it short. ")
                }
                append(state.briefing.headline)
            }
            val guarded = HonestyGuardrail.enforce(
                rawReply = rawReply,
                incident = state.incident,
                actionLedger = actionLedger,
                ownerProvidedValues = ownerProvidedValues,
                oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
                ownerAskedForDetail = ownerAskedForDetail,
            )
            _activeCallState.value = state.copy(
                turnCount = state.turnCount + 1,
                drivingCheckCompleted = true,
                headlineDelivered = true,
                transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
            )
            onSpeakLine(guarded)
            return guarded
        }

        // 2. Deliver initial headline + verified actions if not yet delivered
        if (!state.headlineDelivered) {
            val actionsClause = when {
                state.briefing.actionsDone.size >= 2 && state.incident.source != "simulation_b" -> {
                    val first = state.briefing.actionsDone[0]
                    val second = state.briefing.actionsDone[1]
                    "$first and $second."
                }
                state.briefing.actionsDone.size == 1 && state.incident.source == "simulation_a" -> {
                    "${state.briefing.actionsDone[0]}."
                }
                else -> ""
            }
            val rawReply = listOf(state.briefing.headline.trimEnd('.') + ".", actionsClause)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val guarded = HonestyGuardrail.enforce(
                rawReply = rawReply,
                incident = state.incident,
                actionLedger = actionLedger,
                ownerProvidedValues = ownerProvidedValues,
                oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
                ownerAskedForDetail = ownerAskedForDetail,
            )
            _activeCallState.value = state.copy(
                turnCount = state.turnCount + 1,
                headlineDelivered = true,
                transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
            )
            onSpeakLine(guarded)
            return guarded
        }

        // 3. Handle "How much is the invoice for?" / missing parameter prompt (Dialogue A)
        val pendingRed = state.incident.pendingRedAction
        if (pendingRed?.requiresParameterName != null && state.collectedParameterValue == null) {
            val asksHowMuch = lower.contains("how much") || lower.contains("what price") || lower.contains("amount")
            if (asksHowMuch) {
                val question = pendingRed.parameterPromptQuestion ?: "What price should I put on it?"
                val guarded = HonestyGuardrail.enforce(
                    rawReply = question,
                    incident = state.incident,
                    actionLedger = actionLedger,
                    ownerProvidedValues = ownerProvidedValues,
                    oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
                )
                _activeCallState.value = state.copy(
                    turnCount = state.turnCount + 1,
                    transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
                )
                onSpeakLine(guarded)
                return guarded
            }

            // Owner supplies the parameter value (e.g. "Four grand.")
            val suppliedValue = cleanUtterance.trimEnd('.', '!')
            ownerProvidedValues.add(suppliedValue.lowercase())
            onSaveDecisionMemory("Owner set ${pendingRed.requiresParameterName} for ${state.incident.facts["client"] ?: state.incident.source} to $suppliedValue")

            val rawReply = "Got it, ${suppliedValue.lowercase()}. ${pendingRed.approvalQuestion}"
            val guarded = HonestyGuardrail.enforce(
                rawReply = rawReply,
                incident = state.incident,
                actionLedger = actionLedger,
                ownerProvidedValues = ownerProvidedValues,
                oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
            )
            _activeCallState.value = state.copy(
                turnCount = state.turnCount + 1,
                collectedParameterValue = suppliedValue,
                transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
            )
            onSpeakLine(guarded)
            return guarded
        }

        // 4. Handle "Why?" / root cause questions (Dialogues B & C)
        if (lower.contains("why") || lower.contains("what happened") || lower.contains("shit") || lower.contains("wait")) {
            val cause = state.incident.facts["cause"] ?: "Automated security flag on the recent batch"
            val actionsText = if (state.briefing.actionsDone.size >= 2) {
                "${state.briefing.actionsDone[0]} and ${state.briefing.actionsDone[1]}"
            } else {
                state.briefing.actionsDone.firstOrNull().orEmpty()
            }
            val rawReply = if (actionsText.isNotBlank()) {
                "$cause. $actionsText."
            } else {
                "$cause."
            }
            val guarded = HonestyGuardrail.enforce(
                rawReply = rawReply,
                incident = state.incident,
                actionLedger = actionLedger,
                ownerProvidedValues = ownerProvidedValues,
                oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
            )
            _activeCallState.value = state.copy(
                turnCount = state.turnCount + 1,
                transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
            )
            onSpeakLine(guarded)
            return guarded
        }

        // 5. Handle "Will the appeal work?" / odds or ETA questions (Dialogue B)
        if (lower.contains("will") || lower.contains("work") || lower.contains("odds") || lower.contains("how long") || lower.contains("chance")) {
            val odds = state.briefing.oddsOrEta.ifBlank { "Honestly, fifty-fifty. Usually takes a day or two." }
            val decisionAsk = pendingRed?.approvalQuestion.orEmpty()
            val rawReply = "$odds $decisionAsk".trim()
            val guarded = HonestyGuardrail.enforce(
                rawReply = rawReply,
                incident = state.incident,
                actionLedger = actionLedger,
                ownerProvidedValues = ownerProvidedValues,
                oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
            )
            _activeCallState.value = state.copy(
                turnCount = state.turnCount + 1,
                transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
            )
            onSpeakLine(guarded)
            return guarded
        }

        // 6. Handle Owner Approval for Primary RED Action ("Yeah, just send it", "Do it", "text me the verification link...")
        val isApproval = lower.contains("yeah") || lower.contains("yes") || lower.contains("send it") ||
            lower.contains("do it") || lower.contains("text me") || lower.contains("approve") ||
            lower.contains("go ahead") || lower.contains("lock it in") || lower.contains("confirm")

        if (isApproval && !state.primaryRedExecuted && pendingRed != null) {
            val params = pendingRed.parameters.toMutableMap()
            state.collectedParameterValue?.let { params[pendingRed.requiresParameterName ?: "value"] = it }

            val ledgerEntry = executeInCallToolWithHoldingLine(
                incidentId = state.incident.id,
                toolName = pendingRed.toolName,
                summary = pendingRed.summary,
                spokenSummary = "Executed ${pendingRed.summary}",
                parameters = params,
                tier = PermissionTier.RED,
            )
            onSaveDecisionMemory("Owner approved RED action '${pendingRed.toolName}': ${pendingRed.summary}")

            // Check if there is a single "One more thing" item to surface (Dialogue C)
            val oneMoreThingText = state.incident.oneMoreThing
            val secondaryRed = state.incident.secondaryRedAction
            val rawReply = if (!ledgerEntry.success) {
                "That action failed, sir. I'll retry shortly."
            } else if (!oneMoreThingText.isNullOrBlank() && !state.oneMoreThingOffered) {
                val secQuestion = secondaryRed?.approvalQuestion ?: "Want me to confirm it?"
                "Sent. One more thing — $oneMoreThingText. $secQuestion"
            } else if (state.incident.source == "simulation_b") {
                "Done. Thank you."
            } else {
                "Done."
            }

            val guarded = HonestyGuardrail.enforce(
                rawReply = rawReply,
                incident = state.incident,
                actionLedger = actionLedger,
                ownerProvidedValues = ownerProvidedValues,
                oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
            )

            val callFinished = oneMoreThingText.isNullOrBlank() || state.oneMoreThingOffered
            _activeCallState.value = state.copy(
                turnCount = state.turnCount + 1,
                primaryRedApproved = true,
                primaryRedExecuted = ledgerEntry.success,
                oneMoreThingOffered = state.oneMoreThingOffered || !oneMoreThingText.isNullOrBlank(),
                status = if (callFinished) CallLifecycleStatus.COMPLETED else CallLifecycleStatus.IN_CALL,
                transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
            )
            onSpeakLine(guarded)
            return guarded
        }

        // 7. Handle Owner Approval for Secondary "One More Thing" RED Action (Dialogue C: "Yes, lock it in.")
        val secondaryRed = state.incident.secondaryRedAction
        if (isApproval && state.primaryRedExecuted && !state.secondaryRedExecuted && secondaryRed != null) {
            val ledgerEntry = executeInCallToolWithHoldingLine(
                incidentId = state.incident.id,
                toolName = secondaryRed.toolName,
                summary = secondaryRed.summary,
                spokenSummary = "Confirmed ${secondaryRed.summary}",
                parameters = secondaryRed.parameters,
                tier = PermissionTier.RED,
            )
            onSaveDecisionMemory("Owner approved secondary action '${secondaryRed.toolName}': ${secondaryRed.summary}")

            val rawReply = if (ledgerEntry.success) {
                if (state.contextSignals.isDriving || state.incident.forceDrivingContext) {
                    "Done. Good luck in the meeting."
                } else {
                    "Done."
                }
            } else {
                "I couldn't confirm that slot yet, sir."
            }

            val guarded = HonestyGuardrail.enforce(
                rawReply = rawReply,
                incident = state.incident,
                actionLedger = actionLedger,
                ownerProvidedValues = ownerProvidedValues,
                oneMoreThingAlreadyUsed = true,
            )
            _activeCallState.value = state.copy(
                turnCount = state.turnCount + 1,
                secondaryRedExecuted = ledgerEntry.success,
                status = CallLifecycleStatus.COMPLETED,
                transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
            )
            onSpeakLine(guarded)
            return guarded
        }

        // 8. Default concise status / decision prompt
        val fallback = pendingRed?.approvalQuestion ?: "Standing by for your decision, sir."
        val guarded = HonestyGuardrail.enforce(
            rawReply = fallback,
            incident = state.incident,
            actionLedger = actionLedger,
            ownerProvidedValues = ownerProvidedValues,
            oneMoreThingAlreadyUsed = state.oneMoreThingOffered,
            ownerAskedForDetail = ownerAskedForDetail,
        )
        _activeCallState.value = state.copy(
            turnCount = state.turnCount + 1,
            transcript = state.transcript + listOf("Owner: $cleanUtterance", "Jarvis: $guarded"),
        )
        onSpeakLine(guarded)
        return guarded
    }

    fun endActiveCall() {
        unansweredTimeoutJob?.cancel()
        _activeCallState.update { current ->
            current?.copy(status = CallLifecycleStatus.COMPLETED)
        }
    }
}
