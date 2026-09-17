package com.nas.naswebdav.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * - Active: Cyan accent color + filled icon
 * - Inactive: TextTertiary, outline icon
 * - Height: 56dp (Material3 standard)
 * - Background: True black #000000 (AMOLED)
 * - No elevation, top border: 1dp DarkDivider
 */
enum class NasTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Dashboard("dashboard", "Trang chủ", AppIcons.Dashboard, AppIcons.Dashboard),
    Apps("apps", "Ứng dụng", AppIcons.Build, AppIcons.Build),
    Files("files", "File", AppIcons.Folder, AppIcons.FolderOpen),
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DarkSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)
                .clip(RoundedCornerShape(AppSpacing.LG)),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NasTab.entries.forEach { tab ->
                val isActive = currentRoute == tab.route
                BottomNavItem(
                    tab = tab,
                    isActive = isActive,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    tab: NasTab,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (isActive) PanelTitleCyan else TextTertiary

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.SM))
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = if (isActive) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            modifier = Modifier.size(22.dp),
            tint = contentColor,
        )
        Text(
            text = tab.label,
            style = AppTypography.LabelSmall,
            color = contentColor,
            maxLines = 1,
        )
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
