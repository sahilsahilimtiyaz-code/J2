package com.jarvis.assistant.accessibility

import android.graphics.Rect

/**
 * Snapshot of a visible, actionable accessibility node on screen with a stable temporary reference (#1, #2...).
 */
data class ActionableNodeSnapshot(
    val tempRef: String,
    val packageName: String,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val bounds: Rect,
) {
    fun toCompactString(): String {
        val label = when {
            text.isNotBlank() && contentDescription.isNotBlank() -> "\"$text\" ($contentDescription)"
            text.isNotBlank() -> "\"$text\""
            contentDescription.isNotBlank() -> "($contentDescription)"
            else -> ""
        }
        val type = when {
            isEditable -> "[editable]"
            isClickable -> "[clickable]"
            isScrollable -> "[scrollable]"
            else -> ""
        }
        val id = if (viewId.isNotBlank()) " id=${viewId.substringAfterLast('/')}" else ""
        return "$tempRef $type $label$id bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]".trim()
    }
}
