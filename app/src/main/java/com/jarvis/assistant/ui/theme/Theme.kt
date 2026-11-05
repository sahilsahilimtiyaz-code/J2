package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jarvis.assistant.settings.HudColorTheme

/**
 * Dynamic Iron Man 3 J.A.R.V.I.S. Second Screen Experience HUD Palette.
 *
 * Defaults to 100% video-calibrated Seafoam-Verdigris (`IM3_SEAFOAM`), and
 * dynamically shifts the Orb, Bottom Bar, Top Bar, and HUD panels in real time
 * when the owner selects Blue, Sky Blue, White, Red, Purple, Violet, Emerald, or Gold.
 */
object JarvisPalette {
    var activeTheme by mutableStateOf(HudColorTheme.IM3_SEAFOAM)
        private set

    var Void by mutableStateOf(Color(HudColorTheme.IM3_SEAFOAM.voidHex))
        private set
    var Deep by mutableStateOf(Color(0xFF021114))
        private set
    var Navy by mutableStateOf(Color(0xFF03171B))
        private set
    var Panel by mutableStateOf(Color(HudColorTheme.IM3_SEAFOAM.panelHex))
        private set
    var PanelLight by mutableStateOf(Color(0xFF0A2A30))
        private set
    var Line by mutableStateOf(Color(0xFF144A50))
        private set
    var Cyan by mutableStateOf(Color(HudColorTheme.IM3_SEAFOAM.primaryHex))
        private set
    var CyanBright by mutableStateOf(Color(HudColorTheme.IM3_SEAFOAM.brightHex))
        private set
    var CyanDim by mutableStateOf(Color(HudColorTheme.IM3_SEAFOAM.dimHex))
        private set
    var Ice by mutableStateOf(Color(0xFFE6FCFA))
        private set
    var TextDim by mutableStateOf(Color(0xFF88BFC0))
        private set

    val Amber = Color(0xFFFFB070)
    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF42F5B3)

    /** Secondary neural accents that harmonize with the active theme. */
    var Violet by mutableStateOf(Color(0xFF28A8A8))
        private set
    var Plasma by mutableStateOf(Color(0xFF42C8C4))
        private set
    var Magenta by mutableStateOf(Color(0xFF9EFFFF))
        private set

    fun applyTheme(theme: HudColorTheme) {
        if (activeTheme == theme) return
        activeTheme = theme
        val primary = Color(theme.primaryHex)
        val bright = Color(theme.brightHex)
        val dim = Color(theme.dimHex)
        val voidBg = Color(theme.voidHex)
        val panelBg = Color(theme.panelHex)

        Void = voidBg
        Deep = lerp(voidBg, panelBg, 0.55f)
        Navy = lerp(voidBg, panelBg, 0.80f)
        Panel = panelBg
        PanelLight = lerp(panelBg, primary, 0.18f)
        Line = lerp(panelBg, primary, 0.36f)
        Cyan = primary
        CyanBright = bright
        CyanDim = dim
        Ice = lerp(Color.White, bright, 0.16f)
        TextDim = lerp(Color(0xFF94A3B8), bright, 0.35f)
        Violet = lerp(primary, dim, 0.35f)
        Plasma = primary
        Magenta = lerp(bright, Color.White, 0.45f)
    }
}

@Composable
fun JarvisTheme(
    colorTheme: HudColorTheme = JarvisPalette.activeTheme,
    content: @Composable () -> Unit,
) {
    JarvisPalette.applyTheme(colorTheme)
    SideEffect {
        JarvisPalette.applyTheme(colorTheme)
    }
    val scheme = darkColorScheme(
        primary = JarvisPalette.Cyan,
        onPrimary = JarvisPalette.Void,
        primaryContainer = JarvisPalette.PanelLight,
        onPrimaryContainer = JarvisPalette.CyanBright,
        secondary = JarvisPalette.CyanBright,
        onSecondary = JarvisPalette.Void,
        secondaryContainer = JarvisPalette.Panel,
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
        outlineVariant = JarvisPalette.CyanDim,
        error = JarvisPalette.Danger,
        onError = Color(0xFF340300),
        errorContainer = Color(0xFF43120D),
        onErrorContainer = Color(0xFFFFDAD4),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = JarvisTypography,
        content = content,
    )
}

