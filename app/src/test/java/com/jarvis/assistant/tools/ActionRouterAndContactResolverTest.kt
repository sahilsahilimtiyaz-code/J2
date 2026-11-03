package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.jarvis.assistant.accessibility.ActionableNodeSnapshot
import com.jarvis.assistant.accessibility.AppSkillsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRouterAndContactResolverTest {

    @Test
    fun testNameTokenizationAndSuffixStripping() {
        val tokens1 = ContactResolver.cleanAndTokenizeName("Azhar bhai")
        assertEquals(listOf("azhar"), tokens1)

        val tokens2 = ContactResolver.cleanAndTokenizeName("Rahul ji")
        assertEquals(listOf("rahul"), tokens2)

        val tokens3 = ContactResolver.cleanAndTokenizeName("Sameer bro")
        assertEquals(listOf("sameer"), tokens3)

        val tokens4 = ContactResolver.cleanAndTokenizeName("Vikram Sir")
        assertEquals(listOf("vikram"), tokens4)

        val tokens5 = ContactResolver.cleanAndTokenizeName("Doctor Sahab")
        assertEquals(listOf("doctor"), tokens5)
    }

    @Test
    fun testFuzzyMatchingAndScoring() {
        // Exact match
        val scoreExact = ContactResolver.computeContactMatchScore(
            queryRaw = "Azhar",
            queryBase = "azhar",
            queryTokens = listOf("azhar"),
            contactName = "Azhar",
            hasWhatsApp = true,
            preferWhatsApp = true,
        )
        assertTrue("Exact match should have near-perfect score", scoreExact >= 0.95)

        // Suffix in contact name: "Azhar Bhai" queried with "Azhar"
        val scoreSuffix = ContactResolver.computeContactMatchScore(
            queryRaw = "Azhar",
            queryBase = "azhar",
            queryTokens = listOf("azhar"),
            contactName = "Azhar Bhai",
            hasWhatsApp = true,
            preferWhatsApp = true,
        )
        assertTrue("Query Azhar against Azhar Bhai should score high", scoreSuffix >= 0.90)

        // Suffix in query: "Azhar bhai" queried against "Azhar"
        val scoreQuerySuffix = ContactResolver.computeContactMatchScore(
            queryRaw = "Azhar bhai",
            queryBase = "azhar",
            queryTokens = listOf("azhar"),
            contactName = "Azhar",
            hasWhatsApp = true,
            preferWhatsApp = true,
        )
        assertTrue("Query Azhar bhai against Azhar should score high", scoreQuerySuffix >= 0.90)

        // WhatsApp bonus
        val scoreWithoutWa = ContactResolver.computeContactMatchScore(
            queryRaw = "Azhar",
            queryBase = "azhar",
            queryTokens = listOf("azhar"),
            contactName = "Azhar Ali",
            hasWhatsApp = false,
            preferWhatsApp = true,
        )
        val scoreWithWa = ContactResolver.computeContactMatchScore(
            queryRaw = "Azhar",
            queryBase = "azhar",
            queryTokens = listOf("azhar"),
            contactName = "Azhar Ali",
            hasWhatsApp = true,
            preferWhatsApp = true,
        )
        assertTrue("Contact with WhatsApp should score higher when preferWhatsApp is true", scoreWithWa > scoreWithoutWa)
    }

    @Test
    fun testLibPhoneNumberNormalization() {
        val phoneUtil = PhoneNumberUtil.getInstance()
        val defaultRegion = "IN"

        // 10-digit Indian number without country code
        val parsed1 = phoneUtil.parse("9876543210", defaultRegion)
        assertTrue(phoneUtil.isValidNumber(parsed1))
        val e164_1 = phoneUtil.format(parsed1, PhoneNumberUtil.PhoneNumberFormat.E164)
        assertEquals("+919876543210", e164_1)
        assertEquals("919876543210", e164_1.removePrefix("+"))

        // Formatted Indian number with +91
        val parsed2 = phoneUtil.parse("+91 98765 43210", defaultRegion)
        assertTrue(phoneUtil.isValidNumber(parsed2))
        val e164_2 = phoneUtil.format(parsed2, PhoneNumberUtil.PhoneNumberFormat.E164)
        assertEquals("+919876543210", e164_2)
    }

    @Test
    fun testYouTubeAvoidRulePreventsShortsTapping() {
        val skill = AppSkillsRegistry.getSkill("com.google.android.youtube")
        assertNotNull("YouTube skill should be registered", skill)

        val shortsNode = ActionableNodeSnapshot(
            tempRef = "#4",
            packageName = "com.google.android.youtube",
            text = "Shorts",
            contentDescription = "Shorts tab",
            viewId = "com.google.android.youtube:id/pivot_bar",
            className = "android.widget.FrameLayout",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            bounds = Rect(200, 2200, 400, 2340),
        )

        // General YouTube search goal (e.g. "search lo-fi on YouTube")
        val shouldAvoid = skill!!.avoidRules.any { it.shouldAvoid(shortsNode, "search lo-fi on youtube") }
        assertTrue("YouTube avoid rules must prevent clicking Shorts tab when searching", shouldAvoid)

        // If user explicitly asks to open shorts
        val shouldAvoidExplicit = skill.avoidRules.any { it.shouldAvoid(shortsNode, "open shorts on youtube") }
        assertFalse("Avoid rule should not trigger if user explicitly asked for shorts", shouldAvoidExplicit)
    }

    @Test
    fun testAppRegistrySettingsIntents() {
        val (wifiIntent, wifiLabel) = AppRegistry.createSettingsScreenIntent("wifi")
        assertEquals("Wi-Fi", wifiLabel)
        assertNotNull(wifiIntent)

        val (btIntent, btLabel) = AppRegistry.createSettingsScreenIntent("bluetooth")
        assertEquals("Bluetooth", btLabel)
        assertNotNull(btIntent)

        val (soundIntent, soundLabel) = AppRegistry.createSettingsScreenIntent("sound")
        assertEquals("Sound", soundLabel)
        assertNotNull(soundIntent)
    }

    @Test
    fun testActionableNodeCompactString() {
        val node = ActionableNodeSnapshot(
            tempRef = "#1",
            packageName = "com.whatsapp",
            text = "Send",
            contentDescription = "Send message",
            viewId = "com.whatsapp:id/send",
            className = "android.widget.ImageButton",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            bounds = Rect(950, 2100, 1050, 2250),
        )

        val compact = node.toCompactString()
        assertTrue(compact.contains("#1"))
        assertTrue(compact.contains("[clickable]"))
        assertTrue(compact.contains("\"Send\" (Send message)"))
        assertTrue(compact.contains("id=send"))
        assertTrue(compact.contains("bounds=["))
    }
}
