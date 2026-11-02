package com.jarvis.assistant.voice

import java.util.Locale

/**
 * Decides whether a recognised utterance was addressed to Jarvis.
 *
 * Recognition returns ordinary text, so the wake word is matched on word
 * boundaries rather than substrings: "Jarvis, open YouTube" is a command,
 * "jarvis" alone is a summons, and a conversation happening in the room is
 * ignored unless the owner has turned the wake word off.
 */
object WakeWord {

    data class Result(
        /** The utterance was addressed to Jarvis. */
        val addressed: Boolean,
        /** What to do, with the wake word and filler removed. Empty after a summons. */
        val command: String,
        /** Only the wake word was said, so Jarvis should answer without acting. */
        val summonsOnly: Boolean,
    )

    private val LEADING_FILLER = listOf("hey", "hi", "ok", "okay", "yo", "oi", "hello")

    fun parse(
        text: String,
        wakeWord: String = "jarvis",
        requireWakeWord: Boolean = true,
    ): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result(false, "", false)

        val lower = trimmed.lowercase(Locale.UK)
        val words = wakeWord.trim().lowercase(Locale.UK)
            .ifBlank { "jarvis" }
            .split(' ')
            .filter { it.isNotBlank() }

        val index = indexOfPhrase(lower, words)
        if (index >= 0) {
            val afterIndex = index + words.sumOf { it.length } + (words.size - 1)
            val command = trimmed
                .substring(afterIndex.coerceAtMost(trimmed.length))
                .trimStart(' ', ',', '.', ':', ';', '-', '!', '?')
                .trim()
            return Result(
                addressed = true,
                command = command,
                summonsOnly = command.none { it.isLetterOrDigit() },
            )
        }

        // Also accept the wake word at the very start after filler, e.g.
        // "okay jarvis ..." is already covered above because the phrase match is
        // not anchored; nothing else is needed here.
        if (!requireWakeWord) {
            val cleaned = stripLeadingFiller(trimmed)
            return Result(
                addressed = cleaned.isNotBlank(),
                command = cleaned,
                summonsOnly = false,
            )
        }

        return Result(false, "", false)
    }

    private fun stripLeadingFiller(text: String): String {
        var result = text.trim()
        var changed = true
        while (changed) {
            changed = false
            val lower = result.lowercase(Locale.UK)
            for (filler in LEADING_FILLER) {
                if (lower == filler) return ""
                if (lower.startsWith("$filler ")) {
                    result = result.substring(filler.length).trimStart(' ', ',', '.', '-')
                    changed = true
                    break
                }
            }
        }
        return result.trim()
    }

    /** Index of the first whole-word occurrence of the phrase, or -1. */
    private fun indexOfPhrase(haystack: String, phrase: List<String>): Int {
        if (phrase.isEmpty()) return -1
        var from = 0
        while (from <= haystack.length) {
            val found = haystack.indexOf(phrase.first(), from)
            if (found < 0) return -1
            val beforeOk = found == 0 || !haystack[found - 1].isLetterOrDigit()
            var cursor = found + phrase.first().length
            var matched = beforeOk
            for (word in phrase.drop(1)) {
                if (!matched) break
                if (cursor >= haystack.length || !haystack[cursor].isWhitespace()) {
                    matched = false
                    break
                }
                while (cursor < haystack.length && haystack[cursor].isWhitespace()) cursor++
                if (!haystack.startsWith(word, cursor)) {
                    matched = false
                    break
                }
                cursor += word.length
            }
            val afterOk = cursor >= haystack.length || !haystack[cursor].isLetterOrDigit()
            if (matched && afterOk) return found
            from = found + 1
        }
        return -1
    }
}
