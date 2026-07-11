@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ════════════════════════════════════════════════════════════════════════════
// DESIGN SYSTEM — NAS WebDAV Professional UI
// ════════════════════════════════════════════════════════════════════════════

// ── Surface Hierarchy ──────────────────────────────────────────────────────
internal val DarkSurface    = Color.Black          // Screen background
internal val DarkCard       = Color(0xFF121212)    // Card surfaces
internal val DarkCardHover  = Color(0xFF1E1E1E)    // Pressed/hovered state
internal val DarkElevated   = Color(0xFF1A1A2E)    // Dialogs, modals, sheets
internal val DarkInput      = Color(0xFF252525)    // Input fields, text fields

// ── Accent Colors ──────────────────────────────────────────────────────────
internal val AccentCyan     = Color(0xFF00D2FF)    // Primary accent, links
internal val AccentGreen    = Color(0xFF00E676)    // Success, online, active
internal val AccentOrange   = Color(0xFFFF9100)    // Warning, stale, caution
internal val AccentRed      = Color(0xFFFF1744)    // Error, danger, offline
internal val AccentPurple   = Color(0xFFBB86FC)    // Special features
internal val AccentPink     = Color(0xFFFF6EC7)    // Decorative accents
internal val AccentBlue     = Color(0xFF1976D2)    // Info, neutral accent

// ── Semantic Color Aliases ─────────────────────────────────────────────────
internal val StatusOnline   = AccentGreen
internal val StatusWarning  = AccentOrange
internal val StatusOffline  = AccentRed
internal val StatusInfo     = AccentCyan

// ── Text Hierarchy ─────────────────────────────────────────────────────────
internal val TextPrimary    = Color(0xFFE8E8E8)    // Headings, primary text
internal val TextSecondary  = Color(0xFF8892B0)    // Body, labels
internal val TextTertiary   = Color(0xFF5C6370)    // Captions, hints, disabled

// ── Panel Titles ───────────────────────────────────────────────────────────
internal val PanelTitleCyan    = Color(0xFF4DD0E1)
internal val PanelTitleGreen   = Color(0xFF66BB6A)
internal val PanelTitlePurple  = Color(0xFFB388FF)
internal val PanelTitleSize    = 11.sp
internal val PanelTitleLetterSpacing = 1.5.sp

// ── Progress Track Colors ──────────────────────────────────────────────────
internal val TrackGray       = Color(0xFF424242)    // Progress bar track
internal val TrackGreen      = Color(0xFF1B5E20)    // Green progress track
internal val TrackCyan       = Color(0xFF004D40)    // Cyan progress track

// ════════════════════════════════════════════════════════════════════════════
// TYPOGRAPHY SCALE
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
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        lineHeight = 18.sp
    )
    val BodyMedium = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
        lineHeight = 16.sp
    )
    val BodySmall = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
        lineHeight = 14.sp
    )
    val LabelLarge = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        lineHeight = 14.sp
    )
    val LabelMedium = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        lineHeight = 12.sp
    )
    val LabelSmall = TextStyle(
        fontSize = 8.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        lineHeight = 11.sp
    )
}

// ════════════════════════════════════════════════════════════════════════════
// SPACING SCALE
// ════════════════════════════════════════════════════════════════════════════

internal object AppSpacing {
    val XXS = 2.dp
    val XS  = 4.dp
    val SM  = 8.dp
    val MD  = 12.dp
    val LG  = 16.dp
    val XL  = 24.dp
    val XXL = 32.dp
}

// ════════════════════════════════════════════════════════════════════════════
// SHAPE TOKENS
// ════════════════════════════════════════════════════════════════════════════

internal object AppShapes {
    val Card     = RoundedCornerShape(12.dp)
    val Badge    = RoundedCornerShape(6.dp)
    val Button   = RoundedCornerShape(8.dp)
    val Dialog   = RoundedCornerShape(16.dp)
    val Pill     = RoundedCornerShape(50.dp)
    val Input    = RoundedCornerShape(8.dp)
}

// ════════════════════════════════════════════════════════════════════════════
// MATERIAL 3 COLOR SCHEME
// ════════════════════════════════════════════════════════════════════════════

private val NasDarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = DarkSurface,
    primaryContainer = AccentCyan.copy(alpha = 0.15f),
    secondary = AccentPurple,
    onSecondary = DarkSurface,
    secondaryContainer = AccentPurple.copy(alpha = 0.15f),
    tertiary = AccentGreen,
    onTertiary = DarkSurface,
    tertiaryContainer = AccentGreen.copy(alpha = 0.15f),
    background = DarkSurface,
    onBackground = TextPrimary,
    surface = DarkCard,
    onSurface = TextPrimary,
    surfaceVariant = DarkCardHover,
    onSurfaceVariant = TextSecondary,
    error = AccentRed,
    onError = Color.White,
    errorContainer = AccentRed.copy(alpha = 0.15f),
    outline = TextTertiary,
    outlineVariant = Color(0xFF333333)
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
// THEME COMPOSABLE
// ════════════════════════════════════════════════════════════════════════════

@Composable
internal fun NasTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NasDarkColorScheme,
        typography = NasTypography,
        shapes = NasShapes,
        content = content
    )
}
