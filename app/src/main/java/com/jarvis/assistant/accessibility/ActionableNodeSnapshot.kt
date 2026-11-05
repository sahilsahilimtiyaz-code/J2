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
    fun toCompactString(screenWidth: Int = 1080, screenHeight: Int = 2340): String {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val pctX = ((centerX.toDouble() / screenWidth.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
        val pctY = ((centerY.toDouble() / screenHeight.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
        val role = buildString {
            when {
                isEditable -> append("[InputField]")
                className.contains("Button", ignoreCase = true) -> append("[Button]")
                className.contains("Switch", ignoreCase = true) || className.contains("CheckBox", ignoreCase = true) -> append("[Switch]")
                className.contains("Tab", ignoreCase = true) -> append("[Tab]")
                isScrollable -> append("[ScrollableList]")
                isClickable && (bounds.height() > 240 || className.contains("Card", ignoreCase = true)) -> append("[Card]")
                !isClickable -> append("[Text]")
            }
            if (isClickable) append("[clickable]")
            if (isEditable) append("[editable]")
            if (isScrollable) append("[scrollable]")
        }
        val label = when {
            text.isNotBlank() && contentDescription.isNotBlank() -> "\"$text\" ($contentDescription)"
            text.isNotBlank() -> "\"$text\""
            contentDescription.isNotBlank() -> "($contentDescription)"
            else -> ""
        }
        val id = if (viewId.isNotBlank()) " id=${viewId.substringAfterLast('/')}" else ""
        return "$tempRef $role $label$id center=($pctX%, $pctY%) bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]".trim()
    }
}
