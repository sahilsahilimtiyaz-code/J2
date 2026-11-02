package com.jarvis.assistant.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * Universal interface for application-specific automation adapters.
 */
interface SmartAppAdapter {
    val packageName: String
    val displayName: String
    val supportedWorkflows: List<String>
    val knownRestrictions: List<String>

    fun createLaunchIntent(context: Context, query: String = ""): Intent?
    fun resolveScreenState(screen: ObservedScreenState): String
    fun getKnownSelectors(role: String): List<String>
}

/**
 * Registry of all modular application adapters for J.A.R.V.I.S.
 */
object SmartAppAdapterRegistry {

    private val adapters: Map<String, SmartAppAdapter> = listOf(
        WhatsAppAdapter,
        YouTubeAdapter,
        InstagramAdapter,
        ChromeAdapter,
        GmailAdapter,
        GoogleMapsAdapter,
        SpotifyAdapter,
        SettingsAdapter,
        PhoneAdapter,
        ContactsAdapter,
        MessagesAdapter,
    ).associateBy { it.packageName }

    fun getAdapter(packageName: String): SmartAppAdapter? = adapters[packageName]

    fun findAdapterBySpokenName(name: String): SmartAppAdapter? {
        val lower = name.lowercase(Locale.ROOT).trim()
        return adapters.values.firstOrNull {
            it.displayName.lowercase(Locale.ROOT).contains(lower) ||
                lower.contains(it.displayName.lowercase(Locale.ROOT))
        }
    }

    val allAdapters: List<SmartAppAdapter>
        get() = adapters.values.toList()
}

// 1. WhatsApp -----------------------------------------------------------------

object WhatsAppAdapter : SmartAppAdapter {
    override val packageName = "com.whatsapp"
    override val displayName = "WhatsApp"
    override val supportedWorkflows = listOf(
        "send_text_message",
        "send_voice_note",
        "open_chat_by_contact_name",
        "open_chat_by_phone_number",
        "answer_incoming_call",
    )
    override val knownRestrictions = listOf(
        "Voice notes require holding push-to-talk mic continuously until playback completes",
        "End-to-end encrypted calls require notification/call-assistant bridge",
        "Send button transforms into Microphone button when text field is empty",
    )

    override fun createLaunchIntent(context: Context, query: String): Intent? {
        return if (query.isNotBlank() && query.all { it.isDigit() || it == '+' }) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${query.replace("+", "")}")).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }
    }

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.resourceId.contains("entry") || it.hintText.contains("message", ignoreCase = true) } ->
            "CHAT_CONVERSATION"
        screen.elements.any { it.resourceId.contains("search_src_text") || it.resourceId.contains("my_search_bar") } ->
            "SEARCH_ACTIVE"
        screen.elements.any { it.text.equals("Chats", ignoreCase = true) || it.contentDescription.equals("Chats", ignoreCase = true) } ->
            "CHATS_TAB"
        else -> "UNKNOWN_WHATSAPP_SURFACE"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("menuitem_search", "my_search_bar", "search_bar_inner_layout", "search_holder")
        "input" -> listOf("entry", "message_entry")
        "send" -> listOf("send", "voice_note_draft_send", "voice_recorder_send_btn")
        "mic" -> listOf("voice_note_btn", "send_container")
        else -> emptyList()
    }
}

// 2. YouTube ------------------------------------------------------------------

object YouTubeAdapter : SmartAppAdapter {
    override val packageName = "com.google.android.youtube"
    override val displayName = "YouTube"
    override val supportedWorkflows = listOf(
        "search_and_play_video",
        "pause_or_resume_playback",
        "skip_ad_if_available",
        "browse_feed",
    )
    override val knownRestrictions = listOf(
        "Custom video surface ignores standard ACTION_CLICK; physical coordinate taps are required",
        "Skip Ad buttons may have dynamic countdown delay before becoming clickable",
    )

    override fun createLaunchIntent(context: Context, query: String): Intent? {
        return if (query.isNotBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://search?query=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }
    }

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.contentDescription.contains("Pause video", ignoreCase = true) || it.contentDescription.contains("Play video", ignoreCase = true) } ->
            "VIDEO_PLAYER_ACTIVE"
        screen.elements.any { it.resourceId.contains("search_edit_text") } ->
            "SEARCH_ACTIVE"
        screen.elements.any { it.resourceId.contains("results") || it.resourceId.contains("video_title") } ->
            "SEARCH_RESULTS"
        else -> "YOUTUBE_HOME_FEED"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("menu_item_search", "search_edit_text")
        "play" -> listOf("play_video", "player_control_play_pause")
        "skip_ad" -> listOf("skip_ad_button", "modern_skip_ad_button")
        else -> emptyList()
    }
}

// 3. Instagram ----------------------------------------------------------------

object InstagramAdapter : SmartAppAdapter {
    override val packageName = "com.instagram.android"
    override val displayName = "Instagram"
    override val supportedWorkflows = listOf("open_profile", "search_user", "open_direct_messages", "browse_reels")
    override val knownRestrictions = listOf("Heavy custom SurfaceViews in Reels and Stories")

    override fun createLaunchIntent(context: Context, query: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.resourceId.contains("row_thread_composer") } -> "DM_CONVERSATION"
        screen.elements.any { it.resourceId.contains("action_bar_search_edit_text") } -> "SEARCH_ACTIVE"
        else -> "MAIN_FEED"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("action_bar_search_button", "action_bar_search_edit_text")
        "send" -> listOf("row_thread_composer_button_send")
        else -> emptyList()
    }
}

// 4. Google Chrome ------------------------------------------------------------

object ChromeAdapter : SmartAppAdapter {
    override val packageName = "com.android.chrome"
    override val displayName = "Chrome"
    override val supportedWorkflows = listOf("open_url", "search_web", "browse_page", "scroll_article")
    override val knownRestrictions = listOf("Web contents render inside Blink engine; accessibility tree reflects DOM")

    override fun createLaunchIntent(context: Context, query: String): Intent? {
        val uri = if (query.startsWith("http://") || query.startsWith("https://")) {
            Uri.parse(query)
        } else if (query.isNotBlank()) {
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        } else {
            Uri.parse("https://www.google.com")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.resourceId.contains("url_bar") && it.focused } -> "URL_ENTRY_ACTIVE"
        screen.elements.any { it.resourceId.contains("url_bar") } -> "WEB_PAGE_LOADED"
        else -> "CHROME_SURFACE"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("search_box", "url_bar")
        "input" -> listOf("url_bar")
        else -> emptyList()
    }
}

// 5. Gmail --------------------------------------------------------------------

object GmailAdapter : SmartAppAdapter {
    override val packageName = "com.google.android.gm"
    override val displayName = "Gmail"
    override val supportedWorkflows = listOf("search_email", "read_inbox", "compose_draft")
    override val knownRestrictions = listOf("Sending emails is a consequential action requiring user confirmation")

    override fun createLaunchIntent(context: Context, query: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.resourceId.contains("compose_button") } -> "INBOX"
        screen.elements.any { it.resourceId.contains("to") || it.resourceId.contains("body") } -> "COMPOSE_SCREEN"
        else -> "GMAIL_SURFACE"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "compose" -> listOf("compose_button")
        "send" -> listOf("send")
        "search" -> listOf("open_search_view_edit_text")
        else -> emptyList()
    }
}

// 6. Google Maps --------------------------------------------------------------

object GoogleMapsAdapter : SmartAppAdapter {
    override val packageName = "com.google.android.apps.maps"
    override val displayName = "Google Maps"
    override val supportedWorkflows = listOf("search_location", "start_navigation", "explore_nearby")
    override val knownRestrictions = listOf("Map canvas is a Vector tile OpenGL view; markers may lack individual node bounds")

    override fun createLaunchIntent(context: Context, query: String): Intent? {
        val uri = if (query.isNotBlank()) Uri.parse("geo:0,0?q=${Uri.encode(query)}") else Uri.parse("geo:0,0")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.contentDescription.contains("Start", ignoreCase = true) } -> "DIRECTIONS_PREVIEW"
        screen.elements.any { it.resourceId.contains("search_omnibox_text_box") } -> "MAP_SEARCH"
        else -> "MAP_VIEW"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("search_omnibox_text_box", "search_omnibox_edit_text")
        "start" -> listOf("start_button", "directions_button")
        else -> emptyList()
    }
}

// 7. Spotify ------------------------------------------------------------------

object SpotifyAdapter : SmartAppAdapter {
    override val packageName = "com.spotify.music"
    override val displayName = "Spotify"
    override val supportedWorkflows = listOf("search_song", "play_playlist", "pause_resume", "skip_track")
    override val knownRestrictions = listOf("Dynamic bottom player bar transitions across screens")

    override fun createLaunchIntent(context: Context, query: String): Intent? {
        return if (query.isNotBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }
    }

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.resourceId.contains("play_pause_button") } -> "PLAYER_ACTIVE"
        screen.elements.any { it.resourceId.contains("find_search_field") } -> "SEARCH_ACTIVE"
        else -> "HOME_TAB"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("find_search_field", "query")
        "play" -> listOf("play_pause_button", "track_row")
        else -> emptyList()
    }
}

// 8. Settings -----------------------------------------------------------------

object SettingsAdapter : SmartAppAdapter {
    override val packageName = "com.android.settings"
    override val displayName = "Settings"
    override val supportedWorkflows = listOf("navigate_settings", "toggle_setting", "search_setting")
    override val knownRestrictions = listOf("OEM variations between Samsung One UI and Vanilla AOSP")

    override fun createLaunchIntent(context: Context, query: String): Intent? =
        Intent(android.provider.Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    override fun resolveScreenState(screen: ObservedScreenState): String = when {
        screen.elements.any { it.resourceId.contains("search_src_text") } -> "SEARCH_ACTIVE"
        else -> "SETTINGS_LIST"
    }

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("search_action_bar", "search_src_text")
        "toggle" -> listOf("switch_widget", "checkbox")
        else -> emptyList()
    }
}

// 9. Phone (Dialer) -----------------------------------------------------------

object PhoneAdapter : SmartAppAdapter {
    override val packageName = "com.samsung.android.dialer"
    override val displayName = "Phone"
    override val supportedWorkflows = listOf("dial_number", "view_recent_calls")
    override val knownRestrictions = listOf("Calling is a consequential action requiring explicit spoken confirmation")

    override fun createLaunchIntent(context: Context, query: String): Intent? =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(query)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    override fun resolveScreenState(screen: ObservedScreenState): String = "DIALER"

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "call" -> listOf("call_button", "dial_button")
        else -> emptyList()
    }
}

// 10. Contacts ----------------------------------------------------------------

object ContactsAdapter : SmartAppAdapter {
    override val packageName = "com.samsung.android.app.contacts"
    override val displayName = "Contacts"
    override val supportedWorkflows = listOf("search_contact", "view_contact_details")
    override val knownRestrictions = listOf("Contacts permissions required for direct lookup")

    override fun createLaunchIntent(context: Context, query: String): Intent? =
        Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    override fun resolveScreenState(screen: ObservedScreenState): String = "CONTACTS_LIST"

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "search" -> listOf("search_view", "search_edit_text")
        else -> emptyList()
    }
}

// 11. Messages ----------------------------------------------------------------

object MessagesAdapter : SmartAppAdapter {
    override val packageName = "com.samsung.android.messaging"
    override val displayName = "Messages"
    override val supportedWorkflows = listOf("compose_sms", "read_messages")
    override val knownRestrictions = listOf("SMS dispatch is a consequential action requiring explicit confirmation")

    override fun createLaunchIntent(context: Context, query: String): Intent? =
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    override fun resolveScreenState(screen: ObservedScreenState): String = "MESSAGES_LIST"

    override fun getKnownSelectors(role: String): List<String> = when (role.lowercase(Locale.ROOT)) {
        "compose" -> listOf("compose_button", "fab")
        "send" -> listOf("send_button")
        else -> emptyList()
    }
}
