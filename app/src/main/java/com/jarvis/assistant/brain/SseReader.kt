package com.jarvis.assistant.brain

import java.io.BufferedReader

internal data class SseEvent(
    val event: String?,
    val data: String,
)

/**
 * Small RFC 4180-style server-sent-events reader. It tolerates CRLF, comments,
 * multiple data lines and streams that omit the final blank line.
 */
internal class SseReader(private val reader: BufferedReader) {

    fun next(): SseEvent? {
        var event: String? = null
        val data = StringBuilder()

        while (true) {
            val rawLine = reader.readLine() ?: break
            val line = rawLine.removeSuffix("\r")

            if (line.isEmpty()) {
                if (data.isNotEmpty() || event != null) {
                    return SseEvent(event, data.toString())
                }
                continue
            }
            if (line.startsWith(":")) continue

            val separator = line.indexOf(':')
            val field = if (separator >= 0) line.substring(0, separator) else line
            val value = if (separator >= 0) {
                line.substring(separator + 1).removePrefix(" ")
            } else {
                ""
            }

            when (field) {
                "event" -> event = value
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
            }
        }

        if (data.isNotEmpty() || event != null) {
            return SseEvent(event, data.toString())
        }
        return null
    }
}
