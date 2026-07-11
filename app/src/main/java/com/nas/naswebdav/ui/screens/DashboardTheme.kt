package com.nas.naswebdav.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// ============ BẢNG MÀU CHUYÊN NGHIỆP (dùng chung trong package ui.screens) ============
// Tách ra file riêng + đổi sang internal để các màn được tách cơ học khác cùng dùng,
// tránh nhân bản hằng số màu. Giá trị không đổi so với bản gốc trong MainMenuScreen.kt.

internal val DarkSurface = Color.Black
internal val DarkCard = Color(0xFF0F0F0F)
internal val AccentBlue = Color(0xFF1976D2)
internal val AccentCyan = Color(0xFF00D2FF)
internal val AccentGreen = Color(0xFF00E676)
internal val AccentOrange = Color(0xFFFF9100)
internal val AccentRed = Color(0xFFFF1744)
internal val AccentPurple = Color(0xFFBB86FC)
internal val AccentPink = Color(0xFFFF6EC7)
internal val TextPrimary = Color(0xFFE8E8E8)
internal val TextSecondary = Color(0xFF8892B0)
internal val PanelTitleCyan = Color(0xFF4DD0E1)
internal val PanelTitleGreen = Color(0xFF66BB6A)
internal val PanelTitlePurple = Color(0xFFB388FF)
internal val PanelTitleSize = 11.sp
internal val PanelTitleLetterSpacing = 1.5.sp
