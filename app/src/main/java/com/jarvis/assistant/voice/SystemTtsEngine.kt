package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback voice built on the Android system text-to-speech engine.
 *
 * Every utterance has its own completion slot, and [stop] completes the pending
 * one, so a barge-in or a Stop can never leave the speech loop waiting forever
 * for a callback Android will not send.
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
        const val MAX_UTTERANCE_MS = 90_000L
        const val INIT_TIMEOUT_MS = 8_000L
    }

    init {
        createEngine()
    }

    private fun createEngine() {
        ready = CompletableDeferred()
        engine = TextToSpeech(appContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
    }

    /** Speaks [text] and suspends until it finishes, fails or is stopped. */
    suspend fun speak(text: String, language: String = "en", flush: Boolean = true): Boolean {
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
                    // Called for flush and for stop(); without this the awaiting
                    // coroutine used to hang forever after a barge-in.
                    finish(utteranceId, false)
                }
            })

            val locale = runCatching { Locale.forLanguageTag(language) }
                .getOrDefault(Locale.getDefault())
            runCatching {
                if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.language = locale
                }
            }

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
            // Some engines never deliver onDone for very long text, and a reply
            // must never hang the whole speech pipeline on that.
            runCatching { tts.stop() }
            lastError = "The system voice did not finish in time."
            return false
        }
        return answered
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
