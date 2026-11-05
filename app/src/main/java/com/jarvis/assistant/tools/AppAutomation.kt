package com.jarvis.assistant.tools

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenController
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deep, verified on-screen automation for apps the owner names by voice.
 *
 * Every step follows observe -> decide -> act -> verify: the accessibility tree
 * is re-read before acting, the most specific node is tapped (normally by a
 * physical gesture at its on-screen position, which works even when apps ignore
 * node clicks), and the result is verified before moving on.
 *
 * Used by the WhatsApp messenger and the camera photo capture tools.
 */
internal object AppAutomation {

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    private const val JARVIS_PACKAGE = "com.jarvis.assistant"
    private const val MAX_NODES = 2_500

    // ------------------------------------------------------------- primitives

    /** A located, tappable spot in real screen coordinates. */
    private data class Spot(val centerX: Float, val centerY: Float)

    internal fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        return "$text $desc".trim()
    }

    internal fun normalized(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    internal fun boundsOf(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        val ok = runCatching { node.getBoundsInScreen(rect); true }.getOrDefault(false)
        if (!ok || rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) return null
        return rect
    }

    /** Finds the first node whose screen bounds satisfy [predicate]. */
    internal suspend fun findNode(
        predicate: (AccessibilityNodeInfo, Rect) -> Boolean,
        settleMs: Long = 0L,
    ): AccessibilityNodeInfo? {
        if (settleMs > 0) delay(settleMs)
        return withContext(Dispatchers.Main) {
            val root = JarvisAccessibilityService.serviceInstance()?.rootInActiveWindow
                ?: return@withContext null
            var hit: AccessibilityNodeInfo? = null
            var walked = 0
            fun recurse(node: AccessibilityNodeInfo): Boolean {
                walked++
                if (walked > MAX_NODES) return false
                val bounds = boundsOf(node)
                if (bounds != null && predicate(node, bounds)) {
                    hit = node
                    return false
                }
                var keepGoing = true
                runCatching {
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        keepGoing = recurse(child)
                        if (!keepGoing) break
                    }
                }
                return keepGoing && hit == null
            }
            runCatching { recurse(root) }
            hit
        }
    }

    /** The on-screen rectangle whose node satisfies [predicate]. */
    internal suspend fun findSpot(
        predicate: (AccessibilityNodeInfo, Rect) -> Boolean,
        settleMs: Long = 0L,
    ): Rect? = findNode(predicate, settleMs)?.let { boundsOf(it) }

    /** Finds a node by resource-id token (e.g. "send" for com.whatsapp:id/send). */
    internal suspend fun findByViewId(tokenId: String, settleMs: Long = 0L): AccessibilityNodeInfo? =
        findNode(
            { node, _ ->
                node.viewIdResourceName.orEmpty().substringAfterLast('/') == tokenId
            },
            settleMs,
        )

    /** One verified tap attempt at [spot]; resolves to whether a gesture left the service. */
    internal suspend fun tapSpot(spot: Rect): Boolean {
        val service = JarvisAccessibilityService.serviceInstance() ?: return false
        return service.dispatchTapGesture(spot.centerX().toFloat(), spot.centerY().toFloat())
    }

    /** Fallback: performs ACTION_CLICK on the clickable node or its nearest clickable ancestor at [spot]. */
    internal suspend fun tapNodeAction(spot: Rect): Boolean {
        return withContext(Dispatchers.Main) {
            val root = JarvisAccessibilityService.serviceInstance()?.rootInActiveWindow
                ?: return@withContext false
            var bestNode: AccessibilityNodeInfo? = null
            var walked = 0
            fun walk(node: AccessibilityNodeInfo) {
                if (walked++ > MAX_NODES) return
                val bounds = boundsOf(node)
                if (bounds != null &&
                    bounds.contains(spot.centerX(), spot.centerY())
                ) {
                    if (node.isClickable || bestNode == null) {
                        bestNode = node
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it) }
                }
            }
            runCatching { walk(root) }
            var current = bestNode
            var depth = 0
            while (current != null && depth < 6) {
                if (current.isClickable) {
                    val clicked = runCatching {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                    if (clicked) return@withContext true
                }
                current = current.parent
                depth++
            }
            false
        }
    }

    /** Taps [spot] via physical gesture first, falling back to accessibility node/ancestor click. */
    internal suspend fun tapSpotOrAncestor(spot: Rect): Boolean =
        tapSpot(spot) || tapNodeAction(spot)

    /** Type into a specific editable node located by its screen bounds, or any active editable field on screen. */
    internal suspend fun typeIntoEditable(spot: Rect, text: String): Boolean {
        val node = findNode(predicate = { candidate, bounds ->
            (candidate.isEditable || candidate.isFocusable) &&
                ( (abs(bounds.centerX() - spot.centerX()) < 40 && abs(bounds.centerY() - spot.centerY()) < 40) ||
                    bounds.contains(spot.centerX(), spot.centerY()) )
        }) ?: findNode(predicate = { candidate, _ -> candidate.isEditable })
            ?: return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val worked = withContext(Dispatchers.Main) {
            var result = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
            if (!result) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                delay(150L)
                result = runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }.getOrDefault(false)
            }
            result
        }
        return worked
    }

    internal suspend fun foregroundPackage(): String =
        withContext(Dispatchers.Main) {
            JarvisAccessibilityService.serviceInstance()?.rootInActiveWindow
                ?.packageName?.toString().orEmpty()
        }

    internal suspend fun whatsappInForeground(): Boolean {
        val pkg = foregroundPackage()
        return pkg.startsWith(WHATSAPP_PACKAGE) || pkg.startsWith(WHATSAPP_BUSINESS_PACKAGE)
    }

    // ---------------------------------------------------------------- WhatsApp

    private val WHATSAPP_ROW_TITLE_IDS = setOf(
        "conversations_row_contact_name",
        "contactpicker_row_name",
        "contact_name",
        "row_title",
        "name",
    )

    private val WHATSAPP_SEARCH_INPUT_IDS = listOf(
        "search_src_text",
        "search_input",
        "search_text",
        "search_chat",
        "search_edit_frame",
    )

    private val WHATSAPP_NEW_CHAT_IDS = listOf(
        "fab",
        "menuitem_new_conversation",
        "new_chat",
        "fab_new_chat",
    )

    private fun subtreeLabels(node: AccessibilityNodeInfo, maxDepth: Int = 3): List<String> {
        val out = ArrayList<String>(6)
        fun collect(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > maxDepth) return
            val own = labelOf(current)
            if (own.isNotBlank()) out += own
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { collect(it, depth + 1) }
            }
        }
        runCatching { collect(node, 0) }
        return out
    }

    /** The send arrow in any WhatsApp chat. */
    private suspend fun findSendSpot(): Rect? =
        findByViewId("send")?.let { boundsOf(it) } ?: findSpot(
            { node, bounds ->
                val label = normalized(labelOf(node))
                (label == "send" || label == "sendmessage") &&
                    !node.isEditable && bounds.height() > 50 && node.isEnabled
            },
        )

    /** The message input on a WhatsApp chat conversation screen (excluding top search bars). */
    internal suspend fun findChatInputSpot(): Rect? {
        val (_, screenHeight) = JarvisAccessibilityService.serviceInstance()
            ?.getScreenDimensions() ?: (1_080 to 2_340)
        return findByViewId("entry")?.let { boundsOf(it) }
            ?: findByViewId("input_replace")?.let { boundsOf(it) }
            ?: findSpot(
                { node, bounds ->
                    val shortId = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
                    node.isEditable &&
                        bounds.width() > 180 &&
                        bounds.centerY() > screenHeight * 0.35f &&
                        !shortId.contains("search")
                },
            )
    }

    /** Finds the editable search field once WhatsApp's search bar or New Chat search is active. */
    internal suspend fun findWhatsAppSearchInputSpot(): Rect? {
        for (id in WHATSAPP_SEARCH_INPUT_IDS) {
            findByViewId(id)?.let { boundsOf(it) }?.let { return it }
        }
        return findSpot(
            { node, bounds ->
                node.isEditable && bounds.width() > 160
            },
        )
    }

    /**
     * Locates WhatsApp's search control on the Chats screen across both classic action-bar
     * magnifying glass icons and modern full-width "Ask Meta AI or Search" pill bars.
     */
    internal suspend fun findWhatsAppSearchSpot(): Rect? {
        for (id in WhatsAppVoiceNotes.SEARCH_VIEW_IDS) {
            findByViewId(id)?.let { boundsOf(it) }?.let { return it }
        }
        return withContext(Dispatchers.Main) {
            val root = JarvisAccessibilityService.serviceInstance()?.rootInActiveWindow
                ?: return@withContext null
            var best: Rect? = null
            var walked = 0
            fun walk(node: AccessibilityNodeInfo) {
                if (walked++ > MAX_NODES || best != null) return
                val bounds = boundsOf(node)
                if (bounds != null &&
                    bounds.centerY() in 50..640 &&
                    bounds.width() >= 44 &&
                    bounds.height() in 32..320 &&
                    !node.isEditable
                ) {
                    val shortId = node.viewIdResourceName.orEmpty().substringAfterLast('/')
                    val combinedLabel = subtreeLabels(node, maxDepth = 2).joinToString(" ")
                    if (WhatsAppVoiceNotes.isSearchBarNode(combinedLabel, shortId)) {
                        best = bounds
                        return
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it) }
                }
            }
            runCatching { walk(root) }
            best
        }
    }

    /** Fallback FAB ("New chat" / "Select contact") on WhatsApp's home screen. */
    internal suspend fun findWhatsAppNewChatSpot(): Rect? {
        for (id in WHATSAPP_NEW_CHAT_IDS) {
            findByViewId(id)?.let { boundsOf(it) }?.let { return it }
        }
        return findSpot(
            { node, bounds ->
                val norm = normalized(labelOf(node))
                node.isClickable &&
                    bounds.centerY() > 800 &&
                    (norm.contains("newchat") || norm.contains("newconversation") || norm.contains("sendmessage"))
            },
        )
    }

    /**
     * Finds a WhatsApp chat or contact row matching [contactName] on either the
     * main recent Chats list or the search results list.
     *
     * Handles WhatsApp's RecyclerView layout where the contact name is rendered on a
     * non-clickable child TextView (`conversations_row_contact_name`) inside a clickable
     * parent row container.
     */
    internal suspend fun findWhatsAppContactRowSpot(contactName: String): Rect? =
        withContext(Dispatchers.Main) {
            val service = JarvisAccessibilityService.serviceInstance()
                ?: return@withContext null
            val root = service.rootInActiveWindow ?: return@withContext null
            val (_, screenHeight) = service.getScreenDimensions()
            val minY = 120
            val maxY = (screenHeight - 150).coerceAtLeast(900)
            val normTarget = normalized(contactName)
            val targetEmojis = ContactResolver.extractEmojisAndSymbols(contactName)
            val isSelf = com.jarvis.assistant.brain.IntentGate.isSelfRecipient(contactName)
            if (normTarget.isBlank() && targetEmojis.isBlank() && !isSelf) return@withContext null

            var bestSpot: Rect? = null
            var bestScore = 0
            var bestY = Int.MAX_VALUE
            var walked = 0

            fun resolveRowTapBounds(node: AccessibilityNodeInfo, ownBounds: Rect): Rect {
                var current: AccessibilityNodeInfo? = node
                var depth = 0
                while (current != null && depth < 5) {
                    val b = boundsOf(current)
                    if (current.isClickable && b != null && b.width() > 260 && b.height() in 50..480) {
                        return b
                    }
                    current = current.parent
                    depth++
                }
                return ownBounds
            }

            fun consider(score: Int, tapBounds: Rect) {
                val y = tapBounds.centerY()
                if (score > bestScore || (score == bestScore && y < bestY)) {
                    bestScore = score
                    bestSpot = tapBounds
                    bestY = y
                }
            }

            fun walk(node: AccessibilityNodeInfo) {
                if (walked++ > MAX_NODES) return
                val bounds = boundsOf(node)
                val shortId = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
                val isSearchOrInput = node.isEditable ||
                    shortId.contains("search") ||
                    shortId == "entry" ||
                    shortId == "input_replace"

                if (bounds != null && !isSearchOrInput && bounds.centerY() in minY..maxY) {
                    val ownLabel = labelOf(node).trim()
                    val normOwn = normalized(ownLabel)
                    if (ownLabel.isNotBlank() && WhatsAppVoiceNotes.matchesContactLabel(ownLabel, contactName)) {
                        val isTitleId = shortId in WHATSAPP_ROW_TITLE_IDS
                        val score = when {
                            isTitleId && normOwn == normTarget -> 100
                            isTitleId -> 92
                            normOwn == normTarget -> 85
                            normOwn.startsWith(normTarget) && ownLabel.length <= contactName.length + 28 -> 78
                            ownLabel.length <= contactName.length + 40 -> 68
                            else -> 52
                        }
                        consider(score, resolveRowTapBounds(node, bounds))
                    } else if (node.isClickable && bounds.width() > 300 && bounds.height() in 55..460) {
                        val combined = subtreeLabels(node, maxDepth = 3)
                        if (combined.any { WhatsAppVoiceNotes.matchesContactLabel(it, contactName) }) {
                            val exactChild = combined.any { normalized(it) == normTarget }
                            consider(if (exactChild) 74 else 45, bounds)
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it) }
                }
            }

            runCatching { walk(root) }
            bestSpot
        }

    /** Checks whether the currently open WhatsApp conversation header matches [recipient]. */
    internal suspend fun whatsAppHeaderMatches(recipient: String, isNumber: Boolean): Boolean {
        val headerLabels = whatsAppTopHeaderLabels()
        if (headerLabels.isEmpty()) return false
        if (isNumber) {
            val wanted = WhatsAppVoiceNotes.digitsOf(recipient)
            return headerLabels.any { label ->
                val digits = WhatsAppVoiceNotes.digitsOf(label)
                digits.length >= 7 &&
                    (digits.endsWith(wanted.takeLast(7)) || wanted.endsWith(digits.takeLast(7)))
            }
        }
        return headerLabels.any { label ->
            WhatsAppVoiceNotes.matchesContactLabel(label, recipient)
        }
    }

    internal suspend fun whatsAppTopHeaderLabels(): List<String> = withContext(Dispatchers.Main) {
        val service = JarvisAccessibilityService.serviceInstance()
            ?: return@withContext emptyList()
        val root = service.rootInActiveWindow ?: return@withContext emptyList()
        val labels = LinkedHashSet<String>()
        var walked = 0
        fun walk(node: AccessibilityNodeInfo) {
            if (walked++ > MAX_NODES || labels.size > 90) return
            val bounds = boundsOf(node)
            val shortId = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
            val isHeaderId = shortId == "conversation_contact_name" ||
                shortId == "toolbar_title" ||
                shortId == "conversation_contact"
            if ((bounds != null && bounds.centerY() in 20..580) || isHeaderId) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) labels += text
                if (desc.isNotBlank()) labels += desc
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { walk(it) }
            }
        }
        runCatching { walk(root) }
        labels.toList()
    }

    /**
     * Opens a WhatsApp conversation by contact name using a 4-stage resilient strategy:
     *  1. Keep current conversation if already open to [contactName].
     *  2. Scan the visible recent Chats list directly (and scroll once if needed) — zero search required when contact is in recent chats.
     *  3. Tap WhatsApp's search bar (modern "Ask Meta AI or Search" pill or classic icon), type [contactName], and tap the matching row.
     *  4. Fallback to WhatsApp's "New chat" FAB to search and open the contact.
     */
    internal suspend fun openWhatsAppChatByName(
        controller: ScreenController,
        contactName: String,
    ): ScreenResult {
        val service = JarvisAccessibilityService.serviceInstance()
            ?: return ScreenResult(false, "Screen control is disconnected, so I cannot reach WhatsApp.", verified = false)
        val packageName = listOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE).firstOrNull { pkg ->
            runCatching { service.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        } ?: return ScreenResult(false, "WhatsApp is not installed, sir.", verified = false)

        // Stage 1: Already inside the matching conversation
        if (whatsappInForeground() &&
            findChatInputSpot() != null &&
            whatsAppHeaderMatches(contactName, isNumber = false)
        ) {
            return ScreenResult(true, "Opened WhatsApp chat with $contactName.", verified = true)
        }

        // Stage 2: Bring WhatsApp to foreground
        if (!whatsappInForeground()) {
            delay(350L)
            val launch = runCatching {
                service.packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.getOrNull()
            if (launch == null || runCatching { service.startActivity(launch) }.isFailure) {
                return ScreenResult(false, "WhatsApp would not open, sir.", verified = false)
            }
            val inApp = withTimeoutOrNull(8_000L) {
                while (!whatsappInForeground()) delay(250L)
                true
            } ?: false
            if (!inApp) {
                return ScreenResult(false, "WhatsApp did not come to the foreground.", verified = false)
            }
            delay(1_800L)
        }

        // If WhatsApp opened inside an unrelated conversation or an old search overlay, back out to the Chats list.
        if (findChatInputSpot() != null || findWhatsAppSearchInputSpot() != null) {
            if (!whatsAppHeaderMatches(contactName, isNumber = false) || findChatInputSpot() == null) {
                runCatching { controller.press("back") }
                delay(900L)
                if (findChatInputSpot() != null && !whatsAppHeaderMatches(contactName, isNumber = false)) {
                    runCatching { controller.press("back") }
                    delay(800L)
                }
            } else {
                return ScreenResult(true, "Opened WhatsApp chat with $contactName.", verified = true)
            }
        }

        // Ensure Chats tab is selected if user was on Updates / Communities / Calls tab
        findWhatsAppContactRowSpot(contactName) ?: run {
            findSpot(
                { node, bounds ->
                    val norm = normalized(labelOf(node))
                    bounds.centerY() > 1_600 && (norm == "chats" || norm.startsWith("chats"))
                },
            )?.let { chatsTab ->
                tapSpotOrAncestor(chatsTab)
                delay(700L)
            }
        }

        suspend fun tryOpenVisibleRow(): Boolean {
            if (!whatsappInForeground()) return false
            val row = findWhatsAppContactRowSpot(contactName) ?: return false
            if (!tapSpotOrAncestor(row)) return false
            val composerReady = withTimeoutOrNull(5_500L) {
                while (findChatInputSpot() == null) delay(250L)
                true
            } ?: false
            return composerReady
        }

        // Stage 3: Direct Recent Chats List Scan (fast path when contact is already visible at top of recent chats)
        if (tryOpenVisibleRow()) {
            return ScreenResult(true, "Opened WhatsApp chat with $contactName.", verified = true)
        }

        val cleanSearchQuery = when {
            com.jarvis.assistant.brain.IntentGate.isSelfRecipient(contactName) -> "You"
            else -> com.jarvis.assistant.brain.IntentGate.expandSpokenEmojis(contactName)
                .replace(
                    Regex("""(?i)\b(?:red\s+|pink\s+|blue\s+)?(?:heart|smile|smiling|laughing|fire|star|rose|flower|sparkles)?\s*emojis?\b"""),
                    " ",
                )
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifBlank { contactName.trim() }
        }

        val searchQueriesToTry = listOf(
            cleanSearchQuery,
            cleanSearchQuery.split(Regex("""\s+""")).distinct().joinToString(" "),
            cleanSearchQuery.split(Regex("""\s+""")).firstOrNull().orEmpty(),
        ).filter { it.isNotBlank() }.distinct()

        // Stage 4: Modern Search Bar ("Ask Meta AI or Search" pill or magnifying glass icon) BEFORE scrolling
        val searchSpot = findWhatsAppSearchSpot()
        if (whatsappInForeground() && searchSpot != null && tapSpotOrAncestor(searchSpot)) {
            delay(1_100L)
            val searchField = findWhatsAppSearchInputSpot() ?: searchSpot
            var found = false
            for (query in searchQueriesToTry) {
                if (typeIntoEditable(searchField, query)) {
                    delay(1_900L)
                    if (tryOpenVisibleRow()) {
                        found = true
                        break
                    }
                }
            }
            if (found) {
                return ScreenResult(true, "Opened WhatsApp chat with $contactName.", verified = true)
            }
            // Back out of search if it didn't open the chat so we can check scrolled rows or New Chat
            runCatching { controller.press("back") }
            delay(700L)
        }

        // Stage 4b: Only scroll down once if WhatsApp is confirmed in foreground and search bar was unavailable
        if (whatsappInForeground()) {
            runCatching { controller.scroll(true) }
            delay(950L)
            if (tryOpenVisibleRow()) {
                return ScreenResult(true, "Opened WhatsApp chat with $contactName.", verified = true)
            }
        }

        // Stage 5: "New chat" FAB fallback
        val newChatSpot = findWhatsAppNewChatSpot()
        if (newChatSpot != null && tapSpotOrAncestor(newChatSpot)) {
            delay(1_200L)
            if (tryOpenVisibleRow()) {
                return ScreenResult(true, "Opened WhatsApp chat with $contactName.", verified = true)
            }
            val innerSearch = findWhatsAppSearchSpot() ?: findWhatsAppSearchInputSpot()
            if (innerSearch != null) {
                tapSpotOrAncestor(innerSearch)
                delay(900L)
                val field = findWhatsAppSearchInputSpot() ?: innerSearch
                var found = false
                for (query in searchQueriesToTry) {
                    if (typeIntoEditable(field, query)) {
                        delay(1_800L)
                        if (tryOpenVisibleRow()) {
                            found = true
                            break
                        }
                    }
                }
                if (found) {
                    return ScreenResult(true, "Opened WhatsApp chat with $contactName.", verified = true)
                }
            }
        }

        return ScreenResult(
            false,
            "I opened WhatsApp, but could not find a chat or contact matching \"$contactName\".",
            verified = false,
        )
    }

    /**
     * Verifies a message left the input: the field is empty again, or the exact
     * message text now occurs more than once on screen (typed copy + bubble).
     */
    private suspend fun verifyMessageSent(
        controller: ScreenController,
        message: String,
    ): Boolean {
        val needle = normalized(message)
        val input = findChatInputSpot()
        val inputText = input?.let { jarvisNodeText(it) }.orEmpty()
        if (input != null && inputText.isNotBlank()) {
            val after = controller.snapshot()
            return after.text.split('\n').count { normalized(it).contains(needle) } >= 2
        }
        return true
    }

    private suspend fun jarvisNodeText(spot: Rect): String = withContext(Dispatchers.Main) {
        val node = findNode(predicate = { _, bounds ->
            abs(bounds.centerX() - spot.centerX()) < 2 &&
                abs(bounds.centerY() - spot.centerY()) < 2
        })
        node?.text?.toString().orEmpty()
    }

    /**
     * Full WhatsApp send when a phone number is known: opens the chat with the
     * message pre-filled through the official wa.me link, presses Send, verifies.
     */
    suspend fun sendToNumber(
        controller: ScreenController,
        phoneDigits: String,
        displayName: String,
        message: String,
    ): ScreenResult {
        val encoded = Uri.encode(message)
        val openIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$phoneDigits?text=$encoded"),
        ).apply {
            setPackage(WHATSAPP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            JarvisAccessibilityService.serviceInstance()!!.startActivity(openIntent)
        }.isSuccess
        if (!launched) {
            return ScreenResult(
                false,
                "WhatsApp did not open — it may not be installed, sir.",
                verified = false,
            )
        }
        val inApp = withTimeoutOrNull(8_000L) {
            while (!whatsappInForeground()) delay(300L)
            true
        } ?: false
        if (!inApp) {
            return ScreenResult(
                false,
                "WhatsApp opened too slowly, so I stopped before sending anything.",
                verified = false,
            )
        }
        delay(1_800L)

        var typed = false
        repeat(4) {
            val sendSpot = findSendSpot()
            if (sendSpot != null) {
                val tapped = tapSpotOrAncestor(sendSpot)
                if (tapped) {
                    delay(1_600L)
                    if (verifyMessageSent(controller, message)) {
                        return ScreenResult(
                            true,
                            "Message sent to $displayName on WhatsApp.",
                            verified = true,
                        )
                    }
                    return ScreenResult(
                        true,
                        "I pressed Send for $displayName, but the input did not clear, so I cannot swear the message went out.",
                        verified = false,
                    )
                }
            }
            if (!typed) {
                findChatInputSpot()?.let { inputSpot ->
                    runCatching {
                        tapSpotOrAncestor(inputSpot)
                        typeIntoEditable(inputSpot, message)
                    }
                    typed = true
                    delay(1_000L)
                }
            }
            delay(800L)
        }
        return ScreenResult(
            false,
            "WhatsApp is open with the message ready for $displayName, but I could not press Send. Tap the green arrow to finish, sir.",
            verified = false,
        )
    }

    /**
     * WhatsApp send when only a NAME is known: opens the contact's chat (from recent
     * chats, search bar, or New chat list), types the message, and sends it.
     */
    suspend fun sendToContactByName(
        controller: ScreenController,
        contactName: String,
        message: String,
    ): ScreenResult {
        val opened = openWhatsAppChatByName(controller, contactName)
        if (!opened.success) {
            return opened
        }
        delay(900L)

        // Type + send inside the opened chat.
        var typed = false
        repeat(4) {
            if (typed) {
                val sendSpot = findSendSpot()
                if (sendSpot != null && tapSpotOrAncestor(sendSpot)) {
                    delay(1_600L)
                    if (verifyMessageSent(controller, message)) {
                        return ScreenResult(
                            true,
                            "Message sent to $contactName on WhatsApp.",
                            verified = true,
                        )
                    }
                }
            }
            findChatInputSpot()?.let { inputSpot ->
                runCatching {
                    tapSpotOrAncestor(inputSpot)
                    if (typeIntoEditable(inputSpot, message)) {
                        typed = true
                    }
                }
            }
            delay(1_000L)
        }
        return ScreenResult(
            false,
            "WhatsApp is in the chat with $contactName and the message is typed, but I could not press Send. Tap the green arrow to finish, sir.",
            verified = false,
        )
    }

    // ------------------------------------------------------------------ camera

    /**
     * Writes the lens-selection extras. These keys jointly cover AOSP Camera,
     * CameraX-based OEM apps and Samsung One UI, so "back camera" genuinely
     * opens the rear lens instead of whichever lens was last used.
     */
    fun applyLensSelection(intent: Intent, facing: String) {
        val front = facing.equals("front", ignoreCase = true) ||
            facing.equals("selfie", ignoreCase = true)
        intent.putExtra("android.intent.extras.CAMERA_FACING", if (front) 1 else 0)
        intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", front)
        intent.putExtra("camera_id", if (front) 1 else 0)
        intent.putExtra("android.intent.extra.camera.CAMERA_FACING", if (front) 1 else 0)
    }

    /** The shutter control on mainstream camera apps. */
    private suspend fun findShutterSpot(): Rect? =
        findByViewId("shutter_button")?.let { boundsOf(it) }
            ?: findSpot(predicate = { node, bounds ->
                val label = normalized(labelOf(node))
                val named = label.contains("takephoto") ||
                    label.contains("takepicture") ||
                    label.contains("photograph") ||
                    label.contains("capture") ||
                    label.contains("shutter")
                named && bounds.height() > 80 && node.isEnabled
            })

    /**
     * Real photo capture: launches a single-shot ACTION_IMAGE_CAPTURE session on
     * the requested lens, presses the shutter itself, then verifies the photo file.
     */
    suspend fun capturePhoto(
        context: ToolContext,
        facing: String,
        fileName: String,
    ): ScreenResult {
        val controller = JarvisAccessibilityService.controller()
            ?: return ScreenResult(false, "Screen control is not connected, sir.", verified = false)
        val resolver = context.appContext.contentResolver

        val pendingValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val pendingUri: Uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            pendingValues,
        ) ?: return ScreenResult(
            false,
            "The gallery refused a slot for the photo, sir.",
            verified = false,
        )

        fun discardPending() = runCatching { resolver.delete(pendingUri, null, null) }

        val targetPackage = runCatching {
            context.appContext.packageManager.resolveActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MediaStore.EXTRA_OUTPUT, pendingUri)
            applyLensSelection(this, facing)
            if (targetPackage != null && targetPackage != JARVIS_PACKAGE) {
                setPackage(targetPackage)
            }
        }
        val launched = runCatching { context.appContext.startActivity(cameraIntent) }.isSuccess
        if (!launched) {
            discardPending()
            return ScreenResult(false, "I could not open the camera app, sir.", verified = false)
        }

        val visible = withTimeoutOrNull(9_000L) {
            while (true) {
                val pkg = foregroundPackage()
                if (pkg.isNotBlank() && pkg != JARVIS_PACKAGE) {
                    if (targetPackage == null || pkg == targetPackage) return@withTimeoutOrNull true
                }
                delay(350L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        if (!visible) {
            discardPending()
            return ScreenResult(
                false,
                "The camera never took the foreground, sir. Nothing was captured.",
                verified = false,
            )
        }
        delay(2_500L) // let the camera app settle its lens and UI

        var shutterPressed = false
        repeat(3) {
            if (shutterPressed) return@repeat
            findShutterSpot()?.let { spot ->
                shutterPressed = tapSpot(spot) || tapNodeAction(spot)
            }
            delay(1_400L)
        }
        if (!shutterPressed) {
            discardPending()
            return ScreenResult(
                false,
                "The camera is open on the $facing lens, but I could not press the shutter. Press it and Jarvis will confirm the photo, sir.",
                verified = false,
            )
        }
        delay(2_900L)

        // Confirm the taken photo if the app shows a preview screen.
        findSpot(
            { node, _ ->
                normalized(labelOf(node)) in setOf("ok", "done", "yes", "keep", "save")
            },
        )?.let { runCatching { tapSpot(it) } }
        delay(900L)

        val size = pollFileSize(resolver, pendingUri, 9_000L)
        publishPending(resolver, pendingUri)
        return if (size > 0) {
            ScreenResult(
                true,
                "Picture captured from the $facing camera and saved to the gallery (Pictures/Jarvis/$fileName).",
                verified = true,
            )
        } else {
            discardPending()
            ScreenResult(
                true,
                "I pressed the shutter on the $facing camera, but the photo file never reached the gallery. Check the camera's own film roll, sir.",
                verified = false,
            )
        }
    }

    private suspend fun pollFileSize(
        resolver: android.content.ContentResolver,
        uri: Uri,
        timeoutMs: Long,
    ): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val size = fileSize(resolver, uri)
            if (size > 0) return size
            delay(1_000L)
        }
        return fileSize(resolver, uri)
    }

    private fun fileSize(resolver: android.content.ContentResolver, uri: Uri): Long =
        runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                cursor.moveToFirst()
                if (column >= 0) cursor.getLong(column) else 0L
            } ?: 0L
        }.getOrDefault(0L)

    private fun publishPending(resolver: android.content.ContentResolver, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }
    }
}
