package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.settings.SystemVoiceGender
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-grade Android system text-to-speech engine tuned for a realistic British
 * J.A.R.V.I.S. baritone (`SystemVoiceGender.MALE`) or F.R.I.D.A.Y. neural voice
 * (`SystemVoiceGender.FEMALE`).
 *
 * - Prefers `com.google.android.tts` (Google Speech Services) so Samsung and other OEM
 *   devices use Google's neural British English voices (`en-gb-x-rjs`, `en-gb-x-gbd`)
 *   instead of robotic default voices.
 * - Automatically falls back to the platform default TTS engine if Google Speech Services
 *   is not installed.
 */
class SystemTtsEngine(context: Context) {
    var lastError: String? = null
        private set

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val lock = Any()

    private var pending: CompletableDeferred<Boolean>? = null

    private companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        const val MAX_UTTERANCE_MS = 90_000L
        const val MAX_SYNTHESIS_MS = 60_000L
        const val INIT_TIMEOUT_MS = 8_000L

        /** Priority list of British & Neural Male voice IDs that sound closest to Paul Bettany's J.A.R.V.I.S. */
        val PREFERRED_MALE_VOICE_TOKENS = listOf(
            "en-gb-x-rjs-local",
            "en-gb-x-rjs-network",
            "en-gb-x-rjs",
            "en-gb-x-gbd-local",
            "en-gb-x-gbd-network",
            "en-gb-x-gbd",
            "en-gb-x-fis-local",
            "en-gb-x-fis-network",
            "en-gb-x-fis",
            "en-gb-x-omp-local",
            "en-us-x-iom-local",
            "en-us-x-iom-network",
            "en-us-x-tpd-local",
            "en-au-x-aud-local",
            "en-in-x-end-local",
        )

        /** Priority list of British & Neural Female voice IDs (F.R.I.D.A.Y. style). */
        val PREFERRED_FEMALE_VOICE_TOKENS = listOf(
            "en-gb-x-gba-local",
            "en-gb-x-gba-network",
            "en-gb-x-gba",
            "en-gb-x-gbc-local",
            "en-gb-x-gbc-network",
            "en-gb-x-gbc",
            "en-gb-x-gbg-local",
            "en-us-x-sfg-local",
            "en-us-x-tpf-local",
            "en-in-x-ena-local",
        )
    }

    init {
        createEngine(preferGoogleEngine = true)
    }

    private fun createEngine(preferGoogleEngine: Boolean) {
        ready = CompletableDeferred()
        engine = if (preferGoogleEngine) {
            TextToSpeech(
                appContext,
                { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ready.complete(true)
                    } else {
                        // Fall back to default system engine if Google TTS package is unavailable
                        runCatching { engine?.shutdown() }
                        engine = TextToSpeech(appContext) { fallbackStatus ->
                            ready.complete(fallbackStatus == TextToSpeech.SUCCESS)
                        }
                    }
                },
                GOOGLE_TTS_PACKAGE,
            )
        } else {
            TextToSpeech(appContext) { status ->
                ready.complete(status == TextToSpeech.SUCCESS)
            }
        }
    }

    /**
     * Configures the [TextToSpeech] instance with the best matching neural voice,
     * pitch, and speech rate for [gender] (`MALE` = J.A.R.V.I.S. British baritone,
     * `FEMALE` = F.R.I.D.A.Y.).
     */
    private fun applyJarvisVoiceProfile(
        tts: TextToSpeech,
        language: String,
        gender: SystemVoiceGender,
        speed: Float,
    ) {
        // For Male J.A.R.V.I.S., prefer British English (en-GB) when the user's language is English
        val requestedLocale = runCatching { Locale.forLanguageTag(language) }
            .getOrDefault(Locale.UK)
        val targetLocale = if (requestedLocale.language.equals("en", ignoreCase = true) || language.isBlank()) {
            Locale.UK
        } else {
            requestedLocale
        }

        runCatching {
            if (tts.isLanguageAvailable(targetLocale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = targetLocale
            } else if (tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = Locale.US
            }
        }

        // Scan installed voices for the highest-quality neural voice matching the requested gender
        runCatching {
            val allVoices = tts.voices?.toList().orEmpty()
            if (allVoices.isNotEmpty()) {
                val chosenVoice = pickBestVoice(allVoices, targetLocale, gender)
                if (chosenVoice != null) {
                    tts.voice = chosenVoice
                }
            }
        }

        // Apply J.A.R.V.I.S. baritone resonance vs F.R.I.D.A.Y. female timbre
        val targetPitch = when (gender) {
            SystemVoiceGender.MALE -> 0.90f // Deep, calm, composed Paul Bettany J.A.R.V.I.S. resonance
            SystemVoiceGender.FEMALE -> 1.05f // Crisp, natural female assistant resonance
        }
        val baseRateMultiplier = when (gender) {
            SystemVoiceGender.MALE -> 1.04f // Articulate British butler cadence
            SystemVoiceGender.FEMALE -> 1.02f
        }
        runCatching { tts.setPitch(targetPitch) }
        runCatching { tts.setSpeechRate((speed * baseRateMultiplier).coerceIn(0.6f, 2.0f)) }
    }

    private fun pickBestVoice(
        voices: List<Voice>,
        targetLocale: Locale,
        gender: SystemVoiceGender,
    ): Voice? {
        val langPrefix = targetLocale.language.lowercase(Locale.ROOT).ifBlank { "en" }
        val sameLangVoices = voices.filter { v ->
            v.locale?.language?.lowercase(Locale.ROOT) == langPrefix
        }.ifEmpty { voices }

        val preferredTokens = when (gender) {
            SystemVoiceGender.MALE -> PREFERRED_MALE_VOICE_TOKENS
            SystemVoiceGender.FEMALE -> PREFERRED_FEMALE_VOICE_TOKENS
        }

        // 1. Exact/substring match against our curated neural J.A.R.V.I.S. / F.R.I.D.A.Y. voice IDs
        for (token in preferredTokens) {
            val match = sameLangVoices.firstOrNull { v ->
                v.name.lowercase(Locale.ROOT).contains(token)
            }
            if (match != null) return match
        }

        // 2. Score remaining voices by gender markers, British locale preference, and quality
        return sameLangVoices.maxByOrNull { v ->
            val nameLower = v.name.lowercase(Locale.ROOT)
            val featuresLower = runCatching {
                v.features?.joinToString(" ")?.lowercase(Locale.ROOT).orEmpty()
            }.getOrDefault("")
            val combined = "$nameLower $featuresLower"

            val isExplicitMale = combined.contains("#male") ||
                combined.contains("_male") ||
                combined.contains("-male") ||
                nameLower.contains("-rjs") ||
                nameLower.contains("-gbd") ||
                nameLower.contains("-fis") ||
                nameLower.contains("-iom") ||
                nameLower.contains("-tpd") ||
                nameLower.contains("-end")

            val isExplicitFemale = combined.contains("#female") ||
                combined.contains("_female") ||
                combined.contains("-female") ||
                nameLower.contains("-gba") ||
                nameLower.contains("-gbc") ||
                nameLower.contains("-sfg") ||
                nameLower.contains("-tpf") ||
                nameLower.contains("-ena")

            var score = 0
            when (gender) {
                SystemVoiceGender.MALE -> {
                    if (isExplicitMale) score += 500
                    if (isExplicitFemale) score -= 500
                }
                SystemVoiceGender.FEMALE -> {
                    if (isExplicitFemale) score += 500
                    if (isExplicitMale) score -= 500
                }
            }
            if (v.locale?.country.equals("GB", ignoreCase = true)) {
                score += 180
            } else if (v.locale?.country.equals(targetLocale.country, ignoreCase = true)) {
                score += 120
            }
            if (!v.isNetworkConnectionRequired) {
                score += 60 // Local neural voices have 0ms network jitter
            }
            score += (v.quality / 10)
            score
        }
    }

    /** Speaks [text] and suspends until it finishes, fails or is stopped. */
    suspend fun speak(
        text: String,
        language: String = "en-GB",
        flush: Boolean = true,
        gender: SystemVoiceGender = SystemVoiceGender.MALE,
        speed: Float = 1.0f,
    ): Boolean {
        lastError = null
        if (text.isBlank()) return true
        if (!awaitReady()) {
            lastError = "The Android system voice is not available on this phone."
            return false
        }
        val tts = engine ?: return false
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Boolean>()
        synchronized(lock) { pending = completion }

        val started = withContext(Dispatchers.Main) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    finish(utteranceId, true)
                }

                @Deprecated("Superseded by the 21+ overload", ReplaceWith("onError(id, code)"))
                override fun onError(utteranceId: String?) {
                    finish(utteranceId, false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    lastError = "The system voice failed (code $errorCode)."
                    finish(utteranceId, false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finish(utteranceId, false)
                }
            })

            applyJarvisVoiceProfile(tts, language, gender, speed)

            val result = runCatching {
                tts.speak(
                    text,
                    if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    utteranceId,
                )
            }.getOrDefault(TextToSpeech.ERROR)

            if (result == TextToSpeech.ERROR) {
                lastError = "The system voice refused to speak."
                finish(utteranceId, false)
                false
            } else {
                true
            }
        }

        if (!started) {
            synchronized(lock) {
                if (pending === completion) pending = null
            }
            return false
        }

        val answered = try {
            kotlinx.coroutines.withTimeoutOrNull(MAX_UTTERANCE_MS) { completion.await() }
        } catch (e: CancellationException) {
            runCatching { tts.stop() }
            finish(utteranceId, false)
            throw e
        }
        synchronized(lock) {
            if (pending === completion) pending = null
        }
        if (answered == null) {
            runCatching { tts.stop() }
            lastError = "The system voice did not finish in time."
            return false
        }
        return answered
    }

    /**
     * Renders [text] into [file] as a WAV using the platform engine, for the
     * WhatsApp voice-note path. Returns true only when the engine reported
     * completion and the file contains more than a bare WAV header.
     */
    suspend fun synthesizeToFile(
        text: String,
        file: File,
        language: String = "en-GB",
        gender: SystemVoiceGender = SystemVoiceGender.MALE,
        speed: Float = 1.0f,
    ): Boolean {
        lastError = null
        if (text.isBlank()) return false
        if (!awaitReady()) {
            lastError = "The Android system voice is not available on this phone."
            return false
        }
        val tts = engine ?: return false
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Boolean>()
        synchronized(lock) { pending = completion }

        val started = withContext(Dispatchers.Main) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    finish(utteranceId, true)
                }

                @Deprecated("Superseded by the 21+ overload", ReplaceWith("onError(id, code)"))
                override fun onError(utteranceId: String?) {
                    finish(utteranceId, false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    lastError = "The system voice could not render the note (code $errorCode)."
                    finish(utteranceId, false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finish(utteranceId, false)
                }
            })

            applyJarvisVoiceProfile(tts, language, gender, speed)
            runCatching { file.parentFile?.mkdirs() }
            val result = runCatching {
                tts.synthesizeToFile(text, null, file, utteranceId)
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) {
                lastError = "The system voice refused to render the note."
                finish(utteranceId, false)
                false
            } else {
                true
            }
        }

        if (!started) {
            synchronized(lock) {
                if (pending === completion) pending = null
            }
            return false
        }

        val done = try {
            kotlinx.coroutines.withTimeoutOrNull(MAX_SYNTHESIS_MS) { completion.await() } ?: false
        } catch (e: CancellationException) {
            runCatching { tts.stop() }
            finish(utteranceId, false)
            throw e
        }
        synchronized(lock) {
            if (pending === completion) pending = null
        }
        if (!done && lastError == null) {
            lastError = "The system voice did not finish rendering in time."
        }
        return done && file.exists() && file.length() > 44L
    }

    private fun finish(utteranceId: String?, success: Boolean) {
        val completion = synchronized(lock) { pending }
        if (completion != null && completion.isActive) completion.complete(success)
    }

    /** Cuts off the current utterance. The engine stays usable afterwards. */
    fun stop() {
        runCatching { engine?.stop() }
        val completion = synchronized(lock) { pending }
        if (completion != null && completion.isActive) completion.complete(false)
    }

    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
    }

    private suspend fun awaitReady(): Boolean {
        val tts = engine ?: return false
        val ok = kotlinx.coroutines.withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!ok && engine === tts) {
            lastError = "The Android system voice is not ready."
        }
        return ok && engine != null
    }
}
