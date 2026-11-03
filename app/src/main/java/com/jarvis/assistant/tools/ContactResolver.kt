package com.jarvis.assistant.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of resolving a spoken contact name or number.
 */
sealed interface ContactMatchResult {
    data class Found(
        val contactName: String,
        val rawNumber: String,
        val normalizedNumber: String, // Clean digits for wa.me / tel (e.g. "919876543210")
        val e164Number: String,       // Standard E164 with + prefix (e.g. "+919876543210")
        val hasWhatsApp: Boolean = false,
        val matchConfidence: Double = 1.0,
    ) : ContactMatchResult

    data class Ambiguous(
        val query: String,
        val candidates: List<Found>,
    ) : ContactMatchResult

    data class NotFound(
        val query: String,
        val reason: String,
    ) : ContactMatchResult

    data class PermissionDenied(
        val permission: String = Manifest.permission.READ_CONTACTS,
        val reason: String,
    ) : ContactMatchResult
}

/**
 * Tier 1 Contact Resolver:
 * - Spoken name -> verified phone number
 * - Case-insensitive, token + fuzzy match (STT phonetic variants, "bhai"-style suffixes)
 * - Prefers contacts with an existing WhatsApp account
 * - Normalizes with Google libphonenumber using device region (default India +91 / "IN")
 * - 0 matches: explicit notice
 * - 2+ matches: asks user which one to choose; never guesses
 */
object ContactResolver {

    private const val TAG = "JARVIS_ACT"
    private const val DEFAULT_REGION = "IN"

    // Common informal and speech suffixes to strip when matching spoken names
    private val NAME_SUFFIXES = listOf(
        "bhai", "bhaiya", "ji", "bro", "sir", "sahab", "saab",
        "dada", "anna", "yaar", "uncle", "aunty", "boss", "dude",
    )

    /**
     * Resolves a spoken query to a single verified contact or ambiguous list.
     */
    suspend fun resolve(
        context: Context,
        query: String,
        preferWhatsApp: Boolean = false,
    ): ContactMatchResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext ContactMatchResult.NotFound(query, "No contact name or phone number was provided.")
        }

        // Direct number check: if the query consists mostly of digits (+, -, spaces)
        val digitsOnly = cleanQuery.filter { it.isDigit() || it == '+' }
        val digitCount = digitsOnly.count { it.isDigit() }
        val letterCount = cleanQuery.count { it.isLetter() }

        if (digitCount >= 7 && letterCount == 0) {
            val normalized = normalizePhoneNumber(context, digitsOnly)
            Log.d(TAG, "ContactResolver: Direct phone number '$cleanQuery' -> $normalized")
            return@withContext ContactMatchResult.Found(
                contactName = cleanQuery,
                rawNumber = cleanQuery,
                normalizedNumber = normalized.first,
                e164Number = normalized.second,
                hasWhatsApp = true,
                matchConfidence = 1.0,
            )
        }

        // Check runtime permission
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ContactResolver: READ_CONTACTS permission is not granted.")
            return@withContext ContactMatchResult.PermissionDenied(
                reason = "Jarvis needs contact access to resolve \"$cleanQuery\". Please grant Contacts permission in Settings.",
            )
        }

        val allContacts = loadDeviceContacts(context)
        if (allContacts.isEmpty()) {
            Log.w(TAG, "ContactResolver: 0 contacts loaded from device.")
            return@withContext ContactMatchResult.NotFound(
                query = cleanQuery,
                reason = "No contacts were found in your phone's address book.",
            )
        }

        val cleanedQueryTokens = cleanAndTokenizeName(cleanQuery)
        val queryBase = cleanedQueryTokens.joinToString(" ")

        Log.d(TAG, "ContactResolver: Resolving '$cleanQuery' (base: '$queryBase') across ${allContacts.size} contacts...")

        // Score each contact candidate
        val scored = allContacts.map { entry ->
            val score = computeContactMatchScore(
                queryRaw = cleanQuery,
                queryBase = queryBase,
                queryTokens = cleanedQueryTokens,
                contactName = entry.contactName,
                hasWhatsApp = entry.hasWhatsApp,
                preferWhatsApp = preferWhatsApp,
            )
            entry to score
        }.filter { it.second > 0.40 } // minimum acceptable threshold

        if (scored.isEmpty()) {
            Log.i(TAG, "ContactResolver: 0 matches found for '$cleanQuery'")
            return@withContext ContactMatchResult.NotFound(
                query = cleanQuery,
                reason = "I could not find a contact called \"$cleanQuery\" in your contacts list.",
            )
        }

        // Sort by score descending
        val sorted = scored.sortedByDescending { it.second }
        val topScore = sorted.first().second

        // Identify candidates within close score margin of top score (e.g. within 0.10)
        // or having identical first names with different last names
        val topMatches = sorted.filter { (it.second >= topScore - 0.08) && (it.second >= 0.65) }

        if (topMatches.size > 1) {
            // Check if they are actually the exact same contact entry or different phone numbers
            val distinctNumbers = topMatches.map { it.first.normalizedNumber }.distinct()
            if (distinctNumbers.size > 1) {
                Log.i(TAG, "ContactResolver: Ambiguous matches for '$cleanQuery': ${topMatches.map { "${it.first.contactName} (${it.first.e164Number})" }}")
                return@withContext ContactMatchResult.Ambiguous(
                    query = cleanQuery,
                    candidates = topMatches.map { it.first },
                )
            }
        }

        val best = sorted.first().first
        Log.i(TAG, "ContactResolver: Match found for '$cleanQuery' -> ${best.contactName} (${best.e164Number}, WhatsApp: ${best.hasWhatsApp}, score: $topScore)")
        return@withContext best
    }

    /**
     * Cleans spoken name, removes suffixes like "bhai", "ji", and tokenizes.
     */
    internal fun cleanAndTokenizeName(raw: String): List<String> {
        val lower = raw.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9\s]"""), " ")
        val tokens = lower.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val filtered = tokens.filterNot { token ->
            NAME_SUFFIXES.contains(token)
        }
        return if (filtered.isNotEmpty()) filtered else tokens
    }

    /**
     * Computes similarity score [0.0..1.0] between query and contact name.
     */
    internal fun computeContactMatchScore(
        queryRaw: String,
        queryBase: String,
        queryTokens: List<String>,
        contactName: String,
        hasWhatsApp: Boolean,
        preferWhatsApp: Boolean,
    ): Double {
        val contactTokens = cleanAndTokenizeName(contactName)
        val contactBase = contactTokens.joinToString(" ")

        if (queryBase.isBlank() || contactBase.isBlank()) return 0.0

        var baseScore = 0.0

        // 1. Exact string match (ignoring case & punctuation)
        if (queryBase == contactBase) {
            baseScore = 1.0
        } else if (contactBase.startsWith("$queryBase ") || contactBase.endsWith(" $queryBase")) {
            baseScore = 0.92
        } else {
            // 2. Token match: check how many query tokens match contact tokens
            var matchedTokens = 0
            for (qToken in queryTokens) {
                val tokenMatch = contactTokens.any { cToken ->
                    cToken == qToken ||
                    (cToken.startsWith(qToken) && qToken.length >= 3) ||
                    fuzzyStringSimilarity(qToken, cToken) >= 0.82
                }
                if (tokenMatch) matchedTokens++
            }

            val tokenRatio = matchedTokens.toDouble() / queryTokens.size.toDouble()

            // 3. Overall fuzzy similarity between whole bases
            val overallSim = fuzzyStringSimilarity(queryBase, contactBase)

            baseScore = max(tokenRatio * 0.85, overallSim)
        }

        // WhatsApp preference bonus (up to +0.08)
        if (preferWhatsApp && hasWhatsApp) {
            baseScore = min(1.0, baseScore + 0.08)
        }

        return baseScore
    }

    /**
     * Levenshtein distance based string similarity ratio [0.0..1.0].
     */
    internal fun fuzzyStringSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0

        val maxLen = max(len1, len2)
        val dist = levenshteinDistance(s1, s2)
        return (maxLen - dist).toDouble() / maxLen.toDouble()
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(lhs.length + 1) { it }
        var newCost = IntArray(lhs.length + 1) { 0 }

        for (i in 1..rhs.length) {
            newCost[0] = i
            for (j in 1..lhs.length) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhs.length]
    }

    /**
     * Loads contacts from ContactsContract, querying phones and WhatsApp mime types.
     */
    private fun loadDeviceContacts(context: Context): List<ContactMatchResult.Found> {
        val phoneUtil = PhoneNumberUtil.getInstance()
        val defaultRegion = getDeviceCountryIso(context)

        // Step 1: Query WhatsApp contact raw IDs to know which contacts have WhatsApp
        val whatsAppContactIds = mutableSetOf<Long>()
        runCatching {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.profile"),
                null,
            )
            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                while (it.moveToNext()) {
                    if (idIdx >= 0) {
                        whatsAppContactIds.add(it.getLong(idIdx))
                    }
                }
            }
        }

        // Step 2: Query all phone numbers
        val result = mutableListOf<ContactMatchResult.Found>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        )

        runCatching {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val normIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)

                while (c.moveToNext()) {
                    val contactId = if (idIdx >= 0) c.getLong(idIdx) else -1L
                    val name = if (nameIdx >= 0) c.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNum = if (numIdx >= 0) c.getString(numIdx)?.trim().orEmpty() else ""
                    val sysNorm = if (normIdx >= 0) c.getString(normIdx)?.trim().orEmpty() else ""

                    if (name.isBlank() || rawNum.isBlank()) continue

                    val (digits, e164) = normalizeWithLibPhoneNumber(phoneUtil, rawNum, sysNorm, defaultRegion)

                    val hasWa = whatsAppContactIds.contains(contactId)

                    result.add(
                        ContactMatchResult.Found(
                            contactName = name,
                            rawNumber = rawNum,
                            normalizedNumber = digits,
                            e164Number = e164,
                            hasWhatsApp = hasWa,
                        ),
                    )
                }
            }
        }

        return result
    }

    /**
     * Normalizes a raw phone string using Google libphonenumber with device country ISO default.
     * Returns Pair(cleanDigitsNoPlus, e164WithPlus).
     */
    fun normalizePhoneNumber(context: Context, rawNumber: String): Pair<String, String> {
        val phoneUtil = PhoneNumberUtil.getInstance()
        val defaultRegion = getDeviceCountryIso(context)
        return normalizeWithLibPhoneNumber(phoneUtil, rawNumber, "", defaultRegion)
    }

    private fun normalizeWithLibPhoneNumber(
        phoneUtil: PhoneNumberUtil,
        rawNumber: String,
        sysNorm: String,
        defaultRegion: String,
    ): Pair<String, String> {
        val cleanDigits = rawNumber.filter { it.isDigit() || it == '+' }
        try {
            val parsed = phoneUtil.parse(cleanDigits, defaultRegion)
            if (phoneUtil.isValidNumber(parsed)) {
                val e164 = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                val digitsOnly = e164.removePrefix("+")
                return digitsOnly to e164
            }
        } catch (_: Exception) {
            // fallback below
        }

        if (sysNorm.isNotBlank()) {
            val digitsOnly = sysNorm.removePrefix("+").filter { it.isDigit() }
            val e164 = if (sysNorm.startsWith("+")) sysNorm else "+$sysNorm"
            return digitsOnly to e164
        }

        // Fallback heuristic: if 10 digits and default region is IN (+91)
        val digits = cleanDigits.filter { it.isDigit() }
        if (digits.length == 10 && (defaultRegion == "IN" || defaultRegion.isBlank())) {
            return "91$digits" to "+91$digits"
        }
        val e164 = if (cleanDigits.startsWith("+")) cleanDigits else "+$cleanDigits"
        return digits to e164
    }

    private fun getDeviceCountryIso(context: Context): String {
        return runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simCountry = tm?.simCountryIso?.uppercase(Locale.ROOT)
            val netCountry = tm?.networkCountryIso?.uppercase(Locale.ROOT)
            when {
                !simCountry.isNullOrBlank() -> simCountry
                !netCountry.isNullOrBlank() -> netCountry
                else -> Locale.getDefault().country.takeIf { it.isNotBlank() } ?: DEFAULT_REGION
            }
        }.getOrDefault(DEFAULT_REGION)
    }
}
