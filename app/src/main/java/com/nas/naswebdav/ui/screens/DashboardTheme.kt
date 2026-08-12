@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ════════════════════════════════════════════════════════════════════════════
// NAS THEME — dark-mode-only flat design
// ════════════════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════════════════
// TYPOGRAPHY SCALE  (minimum body 12sp for readability)
// ════════════════════════════════════════════════════════════════════════════

internal object AppTypography {
    val HeadlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        lineHeight = 34.sp
    )
    val HeadlineMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
        lineHeight = 28.sp
    )
    val TitleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
        lineHeight = 24.sp
    )
    val TitleMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        lineHeight = 20.sp
    )
    val BodyLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        lineHeight = 20.sp
    )
    val BodyMedium = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        lineHeight = 18.sp
    )
    val BodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
        lineHeight = 16.sp
    )
    val LabelLarge = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        lineHeight = 16.sp
    )
    val LabelMedium = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        lineHeight = 14.sp
    )
    val LabelSmall = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        lineHeight = 12.sp
    )
}

// ════════════════════════════════════════════════════════════════════════════
// SPACING SCALE
// ════════════════════════════════════════════════════════════════════════════

internal object AppSpacing {
    val XXS = 2.dp
    val XS = 4.dp
    val SM = 6.dp
    val MD = 8.dp
    val LG = 12.dp
    val XL = 16.dp
    val XXL = 24.dp
    val XXXL = 32.dp
}

// ════════════════════════════════════════════════════════════════════════════
// SHAPE TOKENS
// ════════════════════════════════════════════════════════════════════════════

internal object AppShapes {
    val Card = RoundedCornerShape(12.dp)
    val Badge = RoundedCornerShape(6.dp)
    val Button = RoundedCornerShape(8.dp)
    val Dialog = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(50.dp)
    val Input = RoundedCornerShape(8.dp)
}

// ════════════════════════════════════════════════════════════════════════════
// MATERIAL 3 DARK COLOR SCHEME
// ════════════════════════════════════════════════════════════════════════════

private val NasDarkColorScheme = darkColorScheme(
    // AMOLED: pure black background = 0xFF000000 = DarkSurface
    background = DarkSurface,
    onBackground = TextPrimary,

    // Cards/surfaces: slightly off-black for visual hierarchy without lighting OLED pixels
    surface = DarkCard,
    onSurface = TextPrimary,
    surfaceVariant = DarkCardHover,
    onSurfaceVariant = TextSecondary,

    // Primary action — bright blue on pure black (WCAG AAA ≥7:1)
    primary = AccentCyan,
    onPrimary = OnDarkPrimary,
    primaryContainer = AccentCyan.copy(alpha = 0.12f),
    onPrimaryContainer = AccentCyan,

    // Secondary — vivid purple accent
    secondary = AccentPurple,
    onSecondary = OnDarkSecondary,
    secondaryContainer = AccentPurple.copy(alpha = 0.12f),
    onSecondaryContainer = AccentPurple,

    // Tertiary — bright green for success/online
    tertiary = AccentGreen,
    onTertiary = OnDarkTertiary,
    tertiaryContainer = AccentGreen.copy(alpha = 0.12f),
    onTertiaryContainer = AccentGreen,

    // Error — bright red on pure black
    error = AccentRed,
    onError = OnDarkError,
    errorContainer = AccentRed.copy(alpha = 0.12f),
    onErrorContainer = AccentRed,

    // Outline — subtle, not slate
    outline = TextTertiary,
    outlineVariant = OutlineVariantDark
)

// ════════════════════════════════════════════════════════════════════════════
// MATERIAL 3 TYPOGRAPHY
// ════════════════════════════════════════════════════════════════════════════

private val NasTypography = androidx.compose.material3.Typography(
    headlineLarge = AppTypography.HeadlineLarge,
    headlineMedium = AppTypography.HeadlineMedium,
    titleLarge = AppTypography.TitleLarge,
    titleMedium = AppTypography.TitleMedium,
    bodyLarge = AppTypography.BodyLarge,
    bodyMedium = AppTypography.BodyMedium,
    bodySmall = AppTypography.BodySmall,
    labelLarge = AppTypography.LabelLarge,
    labelMedium = AppTypography.LabelMedium,
    labelSmall = AppTypography.LabelSmall
)

// ════════════════════════════════════════════════════════════════════════════
// MATERIAL 3 SHAPES
// ════════════════════════════════════════════════════════════════════════════

private val NasShapes = Shapes(
    small = AppShapes.Input,
    medium = AppShapes.Card,
    large = AppShapes.Dialog,
    extraLarge = AppShapes.Dialog
)

// ════════════════════════════════════════════════════════════════════════════
// THEME COMPOSABLE — always dark
// ════════════════════════════════════════════════════════════════════════════

@Composable
internal fun NasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NasDarkColorScheme,
        typography = NasTypography,
        shapes = NasShapes,
        content = content
    )
}
