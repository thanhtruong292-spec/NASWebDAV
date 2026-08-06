package com.nas.naswebdav.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// ════════════════════════════════════════════════════════════════════════════
// NAS DESIGN TOKENS — AMOLED-OPTIMIZED dark palette (true black for OLED)
// ════════════════════════════════════════════════════════════════════════════

// Surface hierarchy: pure black base, cards use 5–10% white tint for
// visual separation without lighting the OLED pixel unnecessarily.
internal val DarkSurface = Color(0xFF000000)    // true black — OLED off
internal val DarkCard = Color(0xFF0A0A0A)       // ~4% gray — minimal light bleed
internal val DarkCardHover = Color(0xFF141414)   // ~8% gray — hover state
internal val DarkElevated = Color(0xFF1A1A1A)    // ~10% gray — sheets/dialogs
internal val DarkInput = Color(0xFF0F0F0F)       // input field background

// Semantic accents — bright vivid colors chosen for OLED contrast.
internal val AccentCyan = Color(0xFF60A5FA)     // primary action / links
internal val AccentGreen = Color(0xFF4ADE80)    // success / online
internal val AccentOrange = Color(0xFFFB923C)   // warning / caution
internal val AccentRed = Color(0xFFF87171)      // error / destructive
internal val AccentPurple = Color(0xFFA78BFA)   // secondary feature
internal val AccentPink = Color(0xFFF472B6)      // media accent
internal val AccentBlue = Color(0xFF3B82F6)     // informational accent

internal val StatusOnline = AccentGreen
internal val StatusWarning = AccentOrange
internal val StatusOffline = AccentRed
internal val StatusInfo = AccentCyan

// Text hierarchy — pure white primary, desaturated secondaries for OLED.
internal val TextPrimary = Color(0xFFF8FAFC)
internal val TextSecondary = Color(0xFFCBD5E1)
internal val TextTertiary = Color(0xFF6B7280)

// Panel titles and progress tracks.
internal val PanelTitleCyan = Color(0xFF93C5FD)
internal val PanelTitleGreen = Color(0xFF86EFAC)
internal val PanelTitlePurple = Color(0xFFC4B5FD)
internal val PanelTitleSize = 11.sp
internal val PanelTitleLetterSpacing = 1.5.sp
internal val TrackGray = Color(0xFF1F2937)
internal val TrackGreen = Color(0xFF14532D)
internal val TrackCyan = Color(0xFF1E3A8A)
