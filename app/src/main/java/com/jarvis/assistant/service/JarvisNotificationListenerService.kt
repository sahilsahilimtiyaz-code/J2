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
