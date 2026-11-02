package com.jarvis.assistant.service

import android.app.Notification
import android.content.Context
import android.content.Intent
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
)

/** A WhatsApp message notification, reduced to what a spoken reply needs. */
data class WhatsAppIncomingMessage(
    val sender: String,
    val body: String,
    val key: String,
    val postedAt: Long,
    val packageName: String,
)

/**
 * Tier 2 phone control: reads, summarises and dismisses status-bar notifications.
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
        refreshSnapshot()
        notifyIncomingWhatsApp(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSnapshot()
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
            )
        }.sortedByDescending { it.postedAt }
            .take(MAX_NOTIFICATIONS)

        cache.clear()
        cache.addAll(list)
    }

    /**
     * Hands a WhatsApp text notification to the runtime once. Calls, ongoing
     * notifications, media placeholders and repeated updates are filtered here
     * so the auto-reply policy only ever sees a real incoming line.
     */
    private fun notifyIncomingWhatsApp(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!sbn.packageName.startsWith(WHATSAPP_PACKAGE_PREFIX)) return
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
        val listener = incomingWhatsAppListener ?: return
        val event = WhatsAppIncomingMessage(
            sender = sender.take(80),
            body = body.take(700),
            key = sbn.key,
            postedAt = sbn.postTime,
            packageName = sbn.packageName,
        )
        runCatching { listener(event) }
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

        /** Set once by JarvisCore; receives real incoming WhatsApp text messages. */
        @Volatile
        var incomingWhatsAppListener: ((WhatsAppIncomingMessage) -> Unit)? = null

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
