package com.jarvis.assistant.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Ordered selector rule:
 * Priority: viewId > exact contentDescription > exact text > role / class
 */
data class SelectorRule(
    val viewIds: List<String> = emptyList(),
    val exactContentDescriptions: List<String> = emptyList(),
    val exactTexts: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val description: String = "",
) {
    /**
     * Checks if this node matches the selector, returning a score > 0 if matched.
     * Higher score = higher priority match (viewId=100, exact desc=80, exact text=60, role=40).
     */
    fun matchScore(node: ActionableNodeSnapshot): Int {
        val shortId = node.viewId.substringAfterLast('/')
        if (viewIds.any { it.equals(node.viewId, ignoreCase = true) || it.equals(shortId, ignoreCase = true) }) {
            return 100
        }
        val desc = node.contentDescription.trim()
        if (desc.isNotBlank() && exactContentDescriptions.any { it.equals(desc, ignoreCase = true) }) {
            return 80
        }
        val text = node.text.trim()
        if (text.isNotBlank() && exactTexts.any { it.equals(text, ignoreCase = true) }) {
            return 60
        }
        val className = node.className.substringAfterLast('.')
        if (roles.any { it.equals(node.className, ignoreCase = true) || it.equals(className, ignoreCase = true) }) {
            return 40
        }
        return 0
    }
}

/**
 * Avoid rule: nodes matching this rule MUST NOT be tapped or selected
 * unless the user explicitly requested them (e.g. YouTube bottom nav Shorts tab).
 */
data class AvoidRule(
    val viewIds: List<String> = emptyList(),
    val textOrDescContains: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val reason: String = "",
) {
    fun shouldAvoid(node: ActionableNodeSnapshot, userGoalLower: String = ""): Boolean {
        // If the user explicitly requested this word (e.g. "open shorts"), do not avoid
        for (avoidKeyword in textOrDescContains) {
            if (userGoalLower.contains(avoidKeyword.lowercase(Locale.ROOT))) {
                return false
            }
        }

        val shortId = node.viewId.substringAfterLast('/')
        if (viewIds.any { it.equals(node.viewId, ignoreCase = true) || it.equals(shortId, ignoreCase = true) }) {
            return true
        }

        val combined = "${node.text} ${node.contentDescription}".lowercase(Locale.ROOT)
        if (textOrDescContains.any { combined.contains(it.lowercase(Locale.ROOT)) }) {
            return true
        }

        if (roles.any { it.equals(node.className, ignoreCase = true) }) {
            return true
        }

        return false
    }
}

data class AppSkillDefinition(
    val packageName: String,
    val searchTriggerSelectors: List<SelectorRule> = emptyList(),
    val searchInputSelectors: List<SelectorRule> = emptyList(),
    val sendButtonSelectors: List<SelectorRule> = emptyList(),
    val avoidRules: List<AvoidRule> = emptyList(),
)

/**
 * Data-driven skill repository for top apps.
 */
object AppSkillsRegistry {

    private val YOUTUBE_SKILL = AppSkillDefinition(
        packageName = "com.google.android.youtube",
        searchTriggerSelectors = listOf(
            SelectorRule(
                viewIds = listOf(
                    "com.google.android.youtube:id/menu_item_1",
                    "com.google.android.youtube:id/menu_search",
                    "com.google.android.youtube:id/search_button",
                    "com.google.android.youtube:id/search_box",
                    "menu_search",
                    "menu_item_1",
                ),
                exactContentDescriptions = listOf("Search", "Search YouTube"),
                description = "YouTube Top Search Bar / Magnifying Glass",
            ),
        ),
        searchInputSelectors = listOf(
            SelectorRule(
                viewIds = listOf(
                    "com.google.android.youtube:id/search_edit_text",
                    "android:id/search_src_text",
                    "search_edit_text",
                ),
                roles = listOf("android.widget.EditText"),
                description = "YouTube Search Query Input Field",
            ),
        ),
        avoidRules = listOf(
            AvoidRule(
                viewIds = listOf(
                    "com.google.android.youtube:id/shorts_container",
                    "com.google.android.youtube:id/reel_pivot_button",
                ),
                textOrDescContains = listOf("shorts", "create"),
                reason = "Avoid accidental taps on Shorts or Create tab unless explicitly requested",
            ),
        ),
    )

    private val WHATSAPP_SKILL = AppSkillDefinition(
        packageName = "com.whatsapp",
        sendButtonSelectors = listOf(
            SelectorRule(
                viewIds = listOf("com.whatsapp:id/send", "send"),
                exactContentDescriptions = listOf("Send", "send"),
                description = "WhatsApp Chat Send Button",
            ),
        ),
        searchInputSelectors = listOf(
            SelectorRule(
                viewIds = listOf("com.whatsapp:id/entry", "com.whatsapp:id/input_replace", "entry"),
                roles = listOf("android.widget.EditText"),
                description = "WhatsApp Message Text Entry Field",
            ),
        ),
        avoidRules = listOf(
            AvoidRule(
                viewIds = listOf("com.whatsapp:id/voice_record", "com.whatsapp:id/camera_btn"),
                textOrDescContains = listOf("voice message", "record", "camera"),
                reason = "Do not click camera or voice record when trying to send text",
            ),
        ),
    )

    private val WHATSAPP_BUSINESS_SKILL = AppSkillDefinition(
        packageName = "com.whatsapp.w4b",
        sendButtonSelectors = listOf(
            SelectorRule(
                viewIds = listOf("com.whatsapp.w4b:id/send", "send"),
                exactContentDescriptions = listOf("Send", "send"),
                description = "WhatsApp Business Chat Send Button",
            ),
        ),
        searchInputSelectors = listOf(
            SelectorRule(
                viewIds = listOf("com.whatsapp.w4b:id/entry", "com.whatsapp.w4b:id/input_replace", "entry"),
                roles = listOf("android.widget.EditText"),
                description = "WhatsApp Business Message Text Entry Field",
            ),
        ),
        avoidRules = listOf(
            AvoidRule(
                viewIds = listOf("com.whatsapp.w4b:id/voice_record", "com.whatsapp.w4b:id/camera_btn"),
                textOrDescContains = listOf("voice message", "record", "camera"),
                reason = "Do not click camera or voice record when trying to send text",
            ),
        ),
    )

    private val skills = mapOf(
        "com.google.android.youtube" to YOUTUBE_SKILL,
        "com.whatsapp" to WHATSAPP_SKILL,
        "com.whatsapp.w4b" to WHATSAPP_BUSINESS_SKILL,
    )

    fun getSkill(packageName: String): AppSkillDefinition? = skills[packageName]
}
