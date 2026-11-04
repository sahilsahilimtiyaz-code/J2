package com.jarvis.assistant.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArrayList

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val isClearable: Boolean,
    val category: String = "",
    val actionTitles: List<String> = emptyList(),
    val hasRemoteInput: Boolean = false,
)

/**
 * Phase 0 Probe P2 Telemetry entry recording what NotificationListenerService sees
 * for real incoming calls and messages across WhatsApp, Instagram, Telegram, Messenger, and SMS.
 */
data class NotificationProbeTelemetry(
    val key: String,
    val packageName: String,
    val category: String,
    val title: String,
    val textRedacted: String,
    val actionTitles: List<String>,
    val hasRemoteInput: Boolean,
    val remoteInputResultKeys: List<String>,
    val isOngoing: Boolean,
    val postedAt: Long,
)

/** A WhatsApp message notification, reduced to what a spoken reply needs. */
data class WhatsAppIncomingMessage(
    val sender: String,
    val body: String,
    val key: String,
    val postedAt: Long,
    val packageName: String,
    val hasInlineReply: Boolean = false,
    val isGroupMessage: Boolean = false,
)

/**
 * Tier 2 phone control: reads, summarises and dismisses status-bar notifications,
 * executes Rung 1 inline RemoteInput replies, and triggers incoming-call Answer/Decline actions.
 *
 * Safety rules enforced here:
 * - Jarvis's own ongoing service notifications are ignored.
 * - Notifications containing one-time passcodes (OTP), verification codes, or
 *   password-manager secrets are redacted so they are never sent to an LLM or
 *   saved in logs.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshSnapshot()
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        cache.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        recordProbeTelemetry(sbn)
        refreshSnapshot()
        notifyIncomingWhatsApp(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSnapshot()
    }

    private fun recordProbeTelemetry(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val bodyText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val content = bigText.ifBlank { bodyText }
        val actions = notification.actions?.toList().orEmpty()
        val actionTitles = actions.mapNotNull { it.title?.toString()?.trim()?.takeIf { t -> t.isNotBlank() } }
        val remoteKeys = actions.flatMap { act ->
            act.remoteInputs?.mapNotNull { ri -> ri.resultKey?.takeIf { it.isNotBlank() } }.orEmpty()
        }
        val entry = NotificationProbeTelemetry(
            key = sbn.key,
            packageName = sbn.packageName,
            category = notification.category.orEmpty().ifBlank { "uncategorized" },
            title = title.take(80),
            textRedacted = redactSensitiveCodes(title, content).take(160),
            actionTitles = actionTitles,
            hasRemoteInput = remoteKeys.isNotEmpty(),
            remoteInputResultKeys = remoteKeys,
            isOngoing = sbn.isOngoing,
            postedAt = sbn.postTime,
        )
        probeTelemetryHistory.add(0, entry)
        while (probeTelemetryHistory.size > 40) {
            probeTelemetryHistory.removeAt(probeTelemetryHistory.lastIndex)
        }
        incomingProbeListener?.invoke(entry)
    }

    private fun refreshSnapshot() {
        val raw = runCatching { activeNotifications }.getOrNull() ?: return
        val pm = packageManager
        val list = raw.mapNotNull { sbn ->
            if (sbn.packageName == packageName) return@mapNotNull null
            val notification = sbn.notification ?: return@mapNotNull null
            if ((notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 && !sbn.isClearable) {
                return@mapNotNull null
            }
            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            val bodyText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val content = bigText.ifBlank { bodyText }
            if (title.isBlank() && content.isBlank()) return@mapNotNull null

            val appName = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName)

            val actions = notification.actions?.toList().orEmpty()
            val actionTitles = actions.mapNotNull { it.title?.toString()?.trim()?.takeIf { t -> t.isNotBlank() } }
            val hasRemoteInput = actions.any { !it.remoteInputs.isNullOrEmpty() }

            val safeTitle = com.jarvis.assistant.security.SecurityShield.sanitizeUntrustedText(
                raw = title,
                source = "notification:${sbn.packageName}",
            )
            val safeContent = com.jarvis.assistant.security.SecurityShield.sanitizeUntrustedText(
                raw = redactSensitiveCodes(title, content),
                source = "notification:${sbn.packageName}",
            )
            CapturedNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = appName,
                title = safeTitle.take(140),
                text = safeContent.take(360),
                postedAt = sbn.postTime,
                isClearable = sbn.isClearable,
                category = notification.category.orEmpty(),
                actionTitles = actionTitles,
                hasRemoteInput = hasRemoteInput,
            )
        }.sortedByDescending { it.postedAt }
            .take(MAX_NOTIFICATIONS)

        cache.clear()
        cache.addAll(list)
    }

    /**
     * Hands a WhatsApp or social messaging text notification to the runtime once. Calls, ongoing
     * notifications, media placeholders and repeated updates are filtered here
     * so the auto-reply policy only ever sees a real incoming line.
     */
    private fun notifyIncomingWhatsApp(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        val isSupportedSocial = pkg.startsWith(WHATSAPP_PACKAGE_PREFIX) ||
            pkg == "com.instagram.android" ||
            pkg.startsWith("org.telegram.messenger") ||
            pkg == "com.facebook.orca" ||
            pkg == "com.google.android.apps.messaging" ||
            pkg == "com.samsung.android.messaging"
        if (!isSupportedSocial) return
        if (sbn.isOngoing) return
        val notification = sbn.notification ?: return
        if (notification.category == Notification.CATEGORY_CALL ||
            notification.category == Notification.CATEGORY_MISSED_CALL
        ) {
            return
        }
        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val longText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()?.trim().orEmpty()
        val shortText = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()
        val body = longText.ifBlank { shortText }
        if (sender.isBlank() || body.isBlank()) return
        val lower = body.lowercase()
        if (PLACEHOLDER_BODIES.any { lower == it || lower.startsWith("$it ") }) return

        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null ||
            sender.contains(" • ") ||
            Regex("^.+\\(\\d+ messages?\\).*$").matches(sender)
        val hasInlineReply = notification.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true

        val dedupeKey = "${sbn.key}|${body.hashCode()}"
        val now = System.currentTimeMillis()
        synchronized(recentWhatsApp) {
            val stale = recentWhatsApp.filterValues { now - it > WHATSAPP_DEDUPE_MS }.keys
            stale.forEach { recentWhatsApp.remove(it) }
            if (recentWhatsApp.containsKey(dedupeKey)) return
            recentWhatsApp[dedupeKey] = now
            while (recentWhatsApp.size > 30) {
                recentWhatsApp.remove(recentWhatsApp.keys.first())
            }
        }
        val event = WhatsAppIncomingMessage(
            sender = sender.take(80),
            body = body.take(700),
            key = sbn.key,
            postedAt = sbn.postTime,
            packageName = sbn.packageName,
            hasInlineReply = hasInlineReply,
            isGroupMessage = isGroup,
        )
        recordWhatsAppMessage(event)
        if (pkg.startsWith(WHATSAPP_PACKAGE_PREFIX)) {
            val listener = incomingWhatsAppListener ?: return
            runCatching { listener(event) }
        }
    }

    private fun redactSensitiveCodes(title: String, body: String): String {
        val combined = "$title $body".lowercase()
        val looksLikeOtp = OTP_KEYWORDS.any { combined.contains(it) } &&
            Regex("\\b\\d{4,8}\\b").containsMatchIn(body)
        return if (looksLikeOtp) {
            "[One-time security code hidden for safety]"
        } else {
            body
        }
    }

    companion object {
        private const val MAX_NOTIFICATIONS = 40
        private const val WHATSAPP_PACKAGE_PREFIX = "com.whatsapp"
        private const val WHATSAPP_DEDUPE_MS = 30_000L
        private val recentWhatsApp = LinkedHashMap<String, Long>()
        private val whatsappHistory = CopyOnWriteArrayList<WhatsAppIncomingMessage>()
        private val probeTelemetryHistory = CopyOnWriteArrayList<NotificationProbeTelemetry>()

        /** Set once by JarvisCore; receives real incoming WhatsApp text messages. */
        @Volatile
        var incomingWhatsAppListener: ((WhatsAppIncomingMessage) -> Unit)? = null

        @Volatile
        var incomingProbeListener: ((NotificationProbeTelemetry) -> Unit)? = null

        fun probeTelemetryLog(): List<NotificationProbeTelemetry> {
            instance?.let { svc ->
                runCatching { svc.activeNotifications }.getOrNull()?.forEach { sbn ->
                    if (probeTelemetryHistory.none { it.key == sbn.key && it.postedAt == sbn.postTime }) {
                        svc.recordProbeTelemetry(sbn)
                    }
                }
            }
            return probeTelemetryHistory.toList()
        }

        /**
         * Rung 1 Inline Reply via Notification RemoteInput.
         * Prefers replying directly through Android's notification action without opening or tapping UI.
         */
        fun sendInlineReply(
            keyOrSenderQuery: String,
            replyText: String,
            packageFilter: String? = null,
        ): Pair<Boolean, String> {
            val svc = instance ?: return false to "NotificationListenerService is not connected."
            val cleanReply = replyText.trim()
            if (cleanReply.isBlank()) return false to "Reply text cannot be empty."
            val active = runCatching { svc.activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            val q = keyOrSenderQuery.trim().lowercase()

            val matchingSbn = active.firstOrNull { sbn ->
                val pkgOk = packageFilter.isNullOrBlank() || sbn.packageName.contains(packageFilter, ignoreCase = true)
                if (!pkgOk) return@firstOrNull false
                if (sbn.key.equals(keyOrSenderQuery, ignoreCase = true)) return@firstOrNull true
                val title = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase().orEmpty()
                q.isBlank() || title.contains(q) || q.contains(title.takeIf { it.isNotBlank() } ?: "__none__")
            } ?: return false to "No active notification matching '$keyOrSenderQuery' found."

            val actions = matchingSbn.notification?.actions?.toList().orEmpty()
            val replyAction = actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }
                ?: return false to "Notification from ${matchingSbn.packageName} has no RemoteInput reply action."

            return runCatching {
                val intent = Intent()
                val bundle = Bundle()
                for (remoteInput in replyAction.remoteInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, cleanReply)
                }
                RemoteInput.addResultsToIntent(replyAction.remoteInputs, intent, bundle)
                replyAction.actionIntent.send(svc.applicationContext, 0, intent)
                true to "Sent inline RemoteInput reply via ${matchingSbn.packageName} action '${replyAction.title}'."
            }.getOrElse { e ->
                false to "RemoteInput PendingIntent failed: ${e.message}"
            }
        }

        /**
         * Rung 1 Notification Action Trigger (e.g. Answer / Decline / Hang up on an incoming call notification).
         */
        fun triggerNotificationAction(
            packageFilter: String,
            actionKeywords: List<String>,
        ): Pair<Boolean, String> {
            val svc = instance ?: return false to "NotificationListenerService is not connected."
            val active = runCatching { svc.activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            val lowerKeywords = actionKeywords.map { it.lowercase() }

            for (sbn in active) {
                if (packageFilter.isNotBlank() && !sbn.packageName.contains(packageFilter, ignoreCase = true)) continue
                val actions = sbn.notification?.actions?.toList().orEmpty()
                for (action in actions) {
                    val title = action.title?.toString()?.trim().orEmpty()
                    val lowerTitle = title.lowercase()
                    if (lowerKeywords.any { kw -> lowerTitle == kw || lowerTitle.contains(kw) }) {
                        val pending = action.actionIntent ?: continue
                        return runCatching {
                            pending.send()
                            true to "Fired notification action '$title' on ${sbn.packageName}."
                        }.getOrElse { e ->
                            false to "Failed firing action '$title' on ${sbn.packageName}: ${e.message}"
                        }
                    }
                }
            }
            return false to "No active notification action matching ${actionKeywords.joinToString("/")} for '$packageFilter'."
        }

        internal fun recordWhatsAppMessage(event: WhatsAppIncomingMessage) {
            whatsappHistory.removeAll {
                it.sender.equals(event.sender, ignoreCase = true) && it.body == event.body
            }
            whatsappHistory.add(0, event)
            while (whatsappHistory.size > 20) {
                whatsappHistory.removeAt(whatsappHistory.lastIndex)
            }
        }

        /**
         * Returns the most recent incoming WhatsApp message, optionally filtered by
         * [senderQuery]. Falls back to scanning active status-bar notifications if
         * no WhatsApp event was captured since service start.
         */
        fun latestWhatsAppMessage(senderQuery: String? = null): WhatsAppIncomingMessage? {
            val query = senderQuery?.trim().orEmpty()
            val normalizedQuery = query.lowercase()
            val isGenericLast = normalizedQuery.isBlank() ||
                normalizedQuery in setOf("last", "latest", "recent", "reply", "them", "him", "her", "sender")
            val fromHistory = if (isGenericLast) {
                whatsappHistory.firstOrNull()
            } else {
                whatsappHistory.firstOrNull {
                    it.sender.lowercase().contains(normalizedQuery) ||
                        normalizedQuery.contains(it.sender.lowercase())
                }
            }
            if (fromHistory != null) return fromHistory

            // Fallback: inspect current active notifications snapshot for WhatsApp
            val fromActive = snapshot().firstOrNull { notif ->
                notif.packageName.startsWith(WHATSAPP_PACKAGE_PREFIX) &&
                    notif.title.isNotBlank() &&
                    notif.text.isNotBlank() &&
                    (isGenericLast || notif.title.lowercase().contains(normalizedQuery))
            }
            return fromActive?.let {
                WhatsAppIncomingMessage(
                    sender = it.title,
                    body = it.text,
                    key = it.key,
                    postedAt = it.postedAt,
                    packageName = it.packageName,
                    hasInlineReply = it.hasRemoteInput,
                )
            }
        }

        fun recentWhatsAppMessages(): List<WhatsAppIncomingMessage> = whatsappHistory.toList()

        private val PLACEHOLDER_BODIES = listOf(
            "voice message",
            "voice note",
            "photo",
            "video",
            "sticker",
            "gif",
            "document",
            "contact card",
            "location",
            "missed voice call",
            "missed video call",
            "missed call",
            "you have a new message",
            "new message",
        )
        private val OTP_KEYWORDS = listOf(
            "otp",
            "verification code",
            "security code",
            "passcode",
            "one-time",
            "2fa",
            "authentication code",
        )

        @Volatile
        private var instance: JarvisNotificationListenerService? = null

        private val cache = CopyOnWriteArrayList<CapturedNotification>()

        fun isEnabled(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)

        fun isConnected(): Boolean = instance != null

        fun snapshot(): List<CapturedNotification> {
            instance?.refreshSnapshot()
            return cache.toList()
        }

        fun unreadCount(): Int = cache.size

        fun dismissMatching(query: String): Int {
            val service = instance ?: return 0
            service.refreshSnapshot()
            val trimmed = query.trim().lowercase()
            val targets = if (trimmed.isEmpty() || trimmed == "all") {
                cache.filter { it.isClearable }
            } else {
                cache.filter { item ->
                    item.isClearable && (
                        item.appLabel.lowercase().contains(trimmed) ||
                            item.packageName.lowercase().contains(trimmed) ||
                            item.title.lowercase().contains(trimmed) ||
                            item.text.lowercase().contains(trimmed)
                        )
                }
            }
            targets.forEach { item ->
                runCatching { service.cancelNotification(item.key) }
            }
            service.refreshSnapshot()
            return targets.size
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
