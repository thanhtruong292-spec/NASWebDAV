package com.nas.naswebdav.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.*

/**
 * Bottom navigation bar for main app sections.
 *
 * 5 tabs matching professional NAS apps:
 *   Trang chủ | Ứng dụng | File | Hệ thống | Thêm
 *
 * Pattern:
 * ┌──────────────────────────────────────────┐
 * │ [🏠]    [📱]    [📁]    [⚙️]    [⋯]    │
 * │ Trang   Ứng     File    Hệ      Thêm    │
 * │ chủ     dụng            thống             │
 * └──────────────────────────────────────────┘
 *
 * Native Material navigation owns tab semantics and touch targets.
 * The outer Scaffold owns system-bar insets.
 */
enum class NasTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Dashboard("dashboard", "Trang chủ", AppIcons.Dashboard, AppIcons.Dashboard),
    Apps("apps", "Ứng dụng", AppIcons.Build, AppIcons.Build),
    Files("files", "Tệp", AppIcons.Folder, AppIcons.FolderOpen),
    System("system", "Hệ thống", AppIcons.Settings, AppIcons.Settings),
    More("more", "Thêm", AppIcons.More, AppIcons.More),
}

/**
 * Fixed bottom navigation bar. Shows only on main tabs (not on full-screen
 * destinations like Login, Video, Image, GuestPass, etc.).
 *
 * @param currentRoute The current NavHost route (used to highlight active tab)
 * @param onTabSelected Callback when a tab is tapped
 */
@Composable
fun NasNavigationBar(
    currentRoute: String?,
    onTabSelected: (NasTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = DarkCard,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        NasTab.entries.forEach { tab ->
            val isActive = currentRoute == tab.route
            NavigationBarItem(
                selected = isActive,
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onTabSelected(tab)
                },
                icon = {
                    Icon(if (isActive) tab.selectedIcon else tab.icon, null, modifier = Modifier.size(24.dp))
                },
                label = { Text(tab.label, style = AppTypography.BodySmall, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    indicatorColor = DarkElevated,
                    unselectedIconColor = TextTertiary,
                    unselectedTextColor = TextTertiary,
                ),
            )
        }
    }
}

/**
 * Routes where the bottom nav bar should be visible.
 */
val BOTTOM_NAV_ROUTES = setOf(
    NasTab.Dashboard.route,
    NasTab.Apps.route,
    NasTab.Files.route,
    NasTab.System.route,
    NasTab.More.route,
)
