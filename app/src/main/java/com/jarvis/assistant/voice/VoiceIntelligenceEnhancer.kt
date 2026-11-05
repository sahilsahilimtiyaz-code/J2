package com.jarvis.assistant.voice

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Phase 4 Upgrade:
 * 1. [PhoneticAccentNormalizer]: Corrects common Android SpeechRecognizer (STT) misrecognitions
 *    for Indian/British/global accents, Hinglish kinship terms ("Shan Didi", "Bhaiya", "Ammi"),
 *    and app names ("WhatsApp", "YouTube", "Instagram", "Jarvis") without altering user meaning.
 * 2. [VoicePhraseAudioCache]: Disk-backed LRU PCM audio cache for frequent J.A.R.V.I.S. phrases
 *    and confirmations so repeated lines play with 0ms cloud synthesis latency.
 */
object PhoneticAccentNormalizer {

    private val PHRASE_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        // Wake-word & assistant name phonetic variants
        Regex("(?i)\\b(jaanvis|jarves|jarviz|javis|charvis|garvis)\\b") to "Jarvis",
        // App name phonetic mishearings
        Regex("(?i)\\b(watsapp|what's up message|whats up message|what sup|watch app)\\b") to "WhatsApp",
        Regex("(?i)\\b(on what's up|on whats up|in what's up|in whats up)\\b") to "on WhatsApp",
        Regex("(?i)\\b(you tube|u tube|utube)\\b") to "YouTube",
        Regex("(?i)\\b(insta gram|in stagram)\\b") to "Instagram",
        // Self-recipient normalization
        Regex("(?i)\\b(to my self|for my self)\\b") to "to myself",
        // Common Indian/Hinglish kinship & contact phonetic variants
        Regex("(?i)\\b(shaan didi|shan dee dee|shaan dee dee|shandidi)\\b") to "Shan Didi",
        Regex("(?i)\\b(dee dee|didee)\\b") to "Didi",
        Regex("(?i)\\b(bhaiyya|bhaya)\\b") to "Bhaiya",
        // Voice message / note normalization
        Regex("(?i)\\b(voice mail message|voicemail note)\\b") to "voice message",
    )

    /**
     * Normalizes an STT transcript before intent classification and routing.
     */
    fun normalizeTranscript(raw: String): String {
        var result = raw.trim()
        if (result.isBlank()) return result
        for ((pattern, replacement) in PHRASE_REPLACEMENTS) {
            result = pattern.replace(result, replacement)
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Disk-backed PCM cache for synthesized J.A.R.V.I.S. speech chunks.
 * Caches short (< 180 chars) frequent responses keyed by (provider, voiceId, speed, text).
 */
class VoicePhraseAudioCache(
    cacheRootDir: File,
    private val maxEntries: Int = 80,
) {
    private val dir = File(cacheRootDir, "jarvis_voice_phrase_cache").apply { mkdirs() }

    data class CachedPhrasePcm(
        val pcmBytes: ByteArray,
        val sampleRate: Int,
    )

    fun isCacheableSentence(sentence: String): Boolean {
        val clean = sentence.trim()
        return clean.length in 4..180 && !clean.contains(Regex("\\d{4,}"))
    }

    fun get(config: TtsConfig, sentence: String): CachedPhrasePcm? {
        if (!isCacheableSentence(sentence)) return null
        val key = cacheKey(config, sentence)
        val pcmFile = File(dir, "$key.pcm")
        val metaFile = File(dir, "$key.meta")
        if (!pcmFile.exists() || !metaFile.exists()) return null
        return runCatching {
            val sampleRate = metaFile.readText().trim().toIntOrNull() ?: return null
            val bytes = pcmFile.readBytes()
            if (bytes.isEmpty()) return null
            pcmFile.setLastModified(System.currentTimeMillis())
            CachedPhrasePcm(bytes, sampleRate)
        }.getOrNull()
    }

    fun put(config: TtsConfig, sentence: String, pcmBytes: ByteArray, sampleRate: Int) {
        if (!isCacheableSentence(sentence) || pcmBytes.isEmpty() || sampleRate <= 0) return
        runCatching {
            val key = cacheKey(config, sentence)
            val pcmFile = File(dir, "$key.pcm")
            val metaFile = File(dir, "$key.meta")
            pcmFile.writeBytes(pcmBytes)
            metaFile.writeText(sampleRate.toString())
            evictIfNeeded()
        }
    }

    private fun evictIfNeeded() {
        val pcmFiles = dir.listFiles { f -> f.name.endsWith(".pcm") } ?: return
        if (pcmFiles.size <= maxEntries) return
        val sorted = pcmFiles.sortedBy { it.lastModified() }
        val toDelete = sorted.take(pcmFiles.size - maxEntries)
        for (file in toDelete) {
            val base = file.name.removeSuffix(".pcm")
            file.delete()
            File(dir, "$base.meta").delete()
        }
    }

    private fun cacheKey(config: TtsConfig, sentence: String): String {
        val normalized = sentence.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val rawKey = "${config.provider.name}|${config.voiceId}|${config.model}|${"%.2f".format(Locale.US, config.speed)}|$normalized"
        val digest = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
