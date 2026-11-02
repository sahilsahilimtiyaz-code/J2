package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The arc-reactor palette. Deep navy glass, cyan energy, one warm highlight for
 * anything the owner should look at first.
 */
object JarvisPalette {
    val Void = Color(0xFF01050A)
    val Deep = Color(0xFF03101B)
    val Navy = Color(0xFF061B2B)
    val Panel = Color(0xFF082133)
    val PanelLight = Color(0xFF0C2A40)
    val Line = Color(0xFF133A55)
    val Cyan = Color(0xFF2E9BFF)
    val CyanBright = Color(0xFF8BD8FF)
    val CyanDim = Color(0xFF1A5E94)
    val Ice = Color(0xFFE6F6FF)
    val TextDim = Color(0xFF8CAFC5)
    val Amber = Color(0xFFFFA45C)
    val Danger = Color(0xFFFF7A6B)
    val Success = Color(0xFF4FE3A6)
}

private val JarvisDarkScheme = darkColorScheme(
    primary = JarvisPalette.Cyan,
    onPrimary = Color(0xFF00232F),
    primaryContainer = Color(0xFF0B3D50),
    onPrimaryContainer = JarvisPalette.CyanBright,
    secondary = JarvisPalette.CyanBright,
    onSecondary = Color(0xFF00232F),
    secondaryContainer = Color(0xFF0F2F3F),
    onSecondaryContainer = JarvisPalette.Ice,
    tertiary = JarvisPalette.Amber,
    onTertiary = Color(0xFF2F1A00),
    background = JarvisPalette.Void,
    onBackground = JarvisPalette.Ice,
    surface = JarvisPalette.Navy,
    onSurface = JarvisPalette.Ice,
    surfaceVariant = JarvisPalette.Panel,
    onSurfaceVariant = JarvisPalette.TextDim,
    outline = JarvisPalette.Line,
    outlineVariant = Color(0xFF0E2B3B),
    error = JarvisPalette.Danger,
    onError = Color(0xFF340300),
    errorContainer = Color(0xFF43120D),
    onErrorContainer = Color(0xFFFFDAD4),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisDarkScheme,
        typography = JarvisTypography,
        content = content,
    )
}
