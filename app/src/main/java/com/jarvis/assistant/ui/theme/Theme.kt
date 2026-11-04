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
    val Void = Color(0xFF02040A)
    val Deep = Color(0xFF050B16)
    val Navy = Color(0xFF071426)
    val Panel = Color(0xFF0A1B2E)
    val PanelLight = Color(0xFF102A44)
    val Line = Color(0xFF173A5A)
    val Cyan = Color(0xFF35A7FF)
    val CyanBright = Color(0xFF9FE4FF)
    val CyanDim = Color(0xFF1B6793)
    val Ice = Color(0xFFEAF7FF)
    val TextDim = Color(0xFF93B7CC)
    val Amber = Color(0xFFFFB070)
    val Danger = Color(0xFFFF7A6B)
    val Success = Color(0xFF54E8B0)

    /** Futuristic neural accents used by the orb and the premium surfaces. */
    val Violet = Color(0xFF8B7CFF)
    val Plasma = Color(0xFF4D6BFF)
    val Magenta = Color(0xFFFF6FD8)
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
