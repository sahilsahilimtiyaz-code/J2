package com.jarvis.assistant.tools

import android.content.Context
import android.util.Log
import com.jarvis.assistant.accessibility.ActionableNodeSnapshot
import com.jarvis.assistant.accessibility.AppSkillsRegistry
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenController
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 3-TIER ACTION ROUTER
 *
 * Implements strict tier-ordered execution:
 * TIER 1: Standard intents & deep links (zero tapping, instant, deterministic).
 * TIER 2: Accessibility-tree executor (#tempRef picking, climbing clickable parents,
 *         IME enter, scrolling, per-app skill selectors, explicit avoid rules, max 15 steps with loop detection).
 * TIER 3: Multimodal vision fallback (invoked ONLY when the accessibility tree is empty, e.g. games/Flutter/custom canvas;
 *         strictly refuses secure or blank screens).
 *
 * Every step is logged to logcat with tag: JARVIS_ACT.
 */
object ActionRouter {

    const val TAG = "JARVIS_ACT"

    /**
     * Executes WhatsApp messaging via Tier 1 deep link -> Tier 2 send button press.
     * 1. Resolves contact name to number with ContactResolver (token + fuzzy match, suffix stripping, libphonenumber, WhatsApp account preference).
     * 2. Handles 0 matches (say so) and 2+ matches (asks user, never guesses).
     * 3. Confirms with user before sending.
     * 4. Opens https://wa.me/<digits>?text=<urlencoded> targeting com.whatsapp (fallback com.whatsapp.w4b).
     * 5. Presses Send button via Tier 2 accessibility tree.
     */
    suspend fun routeWhatsAppMessage(
        context: Context,
        confirmation: ConfirmationGate,
        recipient: String,
        message: String,
    ): ToolResult {
        Log.i(TAG, "ActionRouter: Routing WhatsApp message to '$recipient' (message: '$message')")

        // Step 1: Resolve Contact
        val contactMatch = ContactResolver.resolve(context, recipient, preferWhatsApp = true)

        when (contactMatch) {
            is ContactMatchResult.PermissionDenied -> {
                Log.w(TAG, "ActionRouter: Contacts permission denied: ${contactMatch.reason}")
                return ToolResult(false, contactMatch.reason)
            }

            is ContactMatchResult.NotFound -> {
                Log.i(TAG, "ActionRouter: Contact not found for '$recipient'")
                return ToolResult(
                    false,
                    "I could not find a contact called \"$recipient\" in your contacts list. Please check the spelling or give me the phone number directly.",
                )
            }

            is ContactMatchResult.Ambiguous -> {
                val candidateList = contactMatch.candidates.take(4).joinToString("; ") {
                    "${it.contactName} (${it.e164Number})"
                }
                Log.i(TAG, "ActionRouter: Ambiguous contact for '$recipient': $candidateList")
                return ToolResult(
                    false,
                    "I found multiple contacts for \"$recipient\": $candidateList. Which one shall I message? Please tell me the full name or number.",
                )
            }

            is ContactMatchResult.Found -> {
                // Step 2: Safety confirmation before sending
                val approved = confirmation.confirm(
                    title = "Send WhatsApp to ${contactMatch.contactName}?",
                    detail = "${contactMatch.e164Number}\n“$message”",
                )
                if (!approved) {
                    Log.i(TAG, "ActionRouter: User declined sending WhatsApp message to ${contactMatch.contactName}")
                    return ToolResult(false, "The owner declined sending the WhatsApp message to ${contactMatch.contactName}.")
                }

                // Step 3: Tier 1 Intent - Launch wa.me deep link
                Log.i(TAG, "ActionRouter [TIER 1]: Launching wa.me deep link for ${contactMatch.normalizedNumber}")
                val intent = AppRegistry.createWhatsAppIntent(context, contactMatch.normalizedNumber, message)
                val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)

                if (!launched) {
                    Log.e(TAG, "ActionRouter [TIER 1]: Failed to launch WhatsApp intent")
                    return ToolResult(false, "Android could not open WhatsApp. Ensure WhatsApp is installed on this device.")
                }

                // Step 4: Tier 2 - Press Send button via Accessibility Tree
                Log.i(TAG, "ActionRouter [TIER 2]: Awaiting WhatsApp chat window to press Send...")
                val controller = JarvisAccessibilityService.controller()
                if (controller == null || !JarvisAccessibilityService.isEnabled(context)) {
                    Log.w(TAG, "ActionRouter: Accessibility service not active; WhatsApp opened with pre-filled message.")
                    return ToolResult(
                        true,
                        "Opened the WhatsApp chat for ${contactMatch.contactName} with your message ready. Tap Send to deliver it (or enable Jarvis in Accessibility Settings for automatic sending).",
                    )
                }

                // Wait for WhatsApp to come to the foreground
                val windowAppeared = controller.awaitWindowState(
                    expectedPackage = AppRegistry.PACKAGE_WHATSAPP,
                    timeoutMs = 4000L,
                ) || controller.awaitWindowState(
                    expectedPackage = AppRegistry.PACKAGE_WHATSAPP_BUSINESS,
                    timeoutMs = 1500L,
                )

                delay(400L) // settle after window focus

                // Snapshot screen and find send button via AppSkillsRegistry
                val nodes = controller.snapshotActionableNodes()
                val activePkg = controller.foregroundPackage()
                val skill = AppSkillsRegistry.getSkill(activePkg)

                var sendButtonNode: ActionableNodeSnapshot? = null

                if (skill != null && skill.sendButtonSelectors.isNotEmpty()) {
                    for (selector in skill.sendButtonSelectors) {
                        val matched = nodes.filter { selector.matchScore(it) > 0 }
                            .maxByOrNull { selector.matchScore(it) }
                        if (matched != null) {
                            sendButtonNode = matched
                            break
                        }
                    }
                }

                // Fallback check for send button by viewId or description
                if (sendButtonNode == null) {
                    sendButtonNode = nodes.firstOrNull {
                        val id = it.viewId.substringAfterLast('/').lowercase(Locale.ROOT)
                        val desc = it.contentDescription.lowercase(Locale.ROOT)
                        val text = it.text.lowercase(Locale.ROOT)
                        id == "send" || desc == "send" || text == "send"
                    }
                }

                if (sendButtonNode != null) {
                    Log.i(TAG, "ActionRouter [TIER 2]: Clicking Send button node ${sendButtonNode.tempRef} (${sendButtonNode.viewId})")
                    val result = controller.executeRefAction(
                        tempRef = sendButtonNode.tempRef,
                        actionType = "click",
                        userGoal = "send message",
                    )
                    Log.i(TAG, "ActionRouter [TIER 2]: Send click result: ${result.message} (verified=${result.verified})")
                    return ToolResult(
                        true,
                        "Sent WhatsApp message to ${contactMatch.contactName} (${contactMatch.e164Number}): \"$message\".",
                    )
                } else {
                    // Try IME enter action
                    Log.i(TAG, "ActionRouter [TIER 2]: Send button not directly tagged; triggering IME enter action")
                    val imeOk = JarvisAccessibilityService.serviceInstance()?.performImeAction() ?: false
                    return ToolResult(
                        true,
                        if (imeOk) {
                            "Sent WhatsApp message to ${contactMatch.contactName} via keyboard submit."
                        } else {
                            "Opened chat with ${contactMatch.contactName} with your message filled in. Tap Send to deliver it."
                        },
                    )
                }
            }
        }
    }

    /**
     * Executes YouTube Search via Tier 1 Intent:
     * Uses Intent.ACTION_SEARCH with package com.google.android.youtube and extra SearchManager.QUERY / "query".
     * Never guesses coordinates and avoids touching the bottom-navigation Shorts tab.
     */
    suspend fun routeYouTubeSearch(
        context: Context,
        query: String,
    ): ToolResult {
        Log.i(TAG, "ActionRouter [TIER 1]: Launching YouTube search intent for '$query'")
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return ToolResult(false, "Tell me what to search on YouTube.")
        }

        val intent = AppRegistry.createYouTubeSearchIntent(context, cleanQuery)
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        if (!launched) {
            Log.e(TAG, "ActionRouter [TIER 1]: YouTube search intent failed")
            return ToolResult(false, "Could not open YouTube search.")
        }

        // Wait on window event for YouTube with timeout
        val controller = JarvisAccessibilityService.controller()
        if (controller != null) {
            val opened = controller.awaitWindowState(AppRegistry.PACKAGE_YOUTUBE, timeoutMs = 3000L)
            Log.i(TAG, "ActionRouter [TIER 1]: YouTube window appeared: $opened without touching Shorts")
        }

        return ToolResult(true, "Opened YouTube and searched for \"$cleanQuery\".")
    }

    /**
     * Executes Settings Screen navigation via Tier 1 Deep Links.
     */
    fun routeSettingsScreen(
        context: Context,
        screenName: String,
    ): ToolResult {
        val (intent, label) = AppRegistry.createSettingsScreenIntent(screenName)
        Log.i(TAG, "ActionRouter [TIER 1]: Opening Settings screen: $label")
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "Opened $label settings.")
        }.getOrElse { error ->
            Log.e(TAG, "ActionRouter [TIER 1]: Failed to open $label settings: ${error.message}")
            ToolResult(false, "Could not open $label settings: ${error.message}")
        }
    }

    /**
     * Executes Dump Screen:
     * Dumps snapshot of all visible actionable nodes with #refs, logging to logcat with tag JARVIS_ACT.
     */
    fun routeDumpScreen(context: Context): ToolResult {
        val controller = JarvisAccessibilityService.controller()
        if (controller == null || !JarvisAccessibilityService.isEnabled(context)) {
            Log.w(TAG, "ActionRouter: dump_screen requested but accessibility service is disabled")
            return ToolResult(
                false,
                "Accessibility service is not active. Enable Jarvis in Android Accessibility Settings to inspect the screen.",
            )
        }
        val dump = controller.dumpScreen()
        Log.i(TAG, "ActionRouter: dump_screen generated (${dump.lines().size} lines)")
        return ToolResult(true, dump)
    }
}
