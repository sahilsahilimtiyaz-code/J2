package com.jarvis.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.R

private val Default = Typography()

/**
 * Rajdhani is the condensed, wide-tracked technical face used by the reference
 * board for the J.A.R.V.I.S. logotype, screen headings, HUD labels and numbers.
 * Body copy stays on the platform sans so long answers remain easy to read.
 */
private val Hud = FontFamily(
    Font(R.font.rajdhani_light, FontWeight.Light),
    Font(R.font.rajdhani_regular, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
)

val JarvisTypography = Typography(
    displayLarge = Default.displayLarge.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Light,
        letterSpacing = 1.sp,
    ),
    displayMedium = Default.displayMedium.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Light,
        letterSpacing = 1.sp,
    ),
    headlineLarge = Default.headlineLarge.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Light,
        letterSpacing = 0.8.sp,
    ),
    headlineMedium = Default.headlineMedium.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Light,
        letterSpacing = 0.6.sp,
    ),
    headlineSmall = Default.headlineSmall.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Light,
        letterSpacing = 0.5.sp,
    ),
    titleLarge = Default.titleLarge.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Normal,
        letterSpacing = 2.sp,
    ),
    titleMedium = Default.titleMedium.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    ),
    titleSmall = Default.titleSmall.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    ),
    labelLarge = Default.labelLarge.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = Default.labelMedium.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
    ),
    labelSmall = Default.labelSmall.copy(
        fontFamily = Hud,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
    ),
    bodyLarge = Default.bodyLarge.copy(
        lineHeight = 23.sp,
        fontFamily = FontFamily.Default,
    ),
)

/** Mono-ish style reserved for numbers and status readouts. */
val HudNumerals: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
)
