package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.components.NasSectionHeader

/**
 * Menu item row — icon + label.
 */
@Composable
private fun MenuItemRow(
    icon: ImageVector,
    label: String,
    iconTint: androidx.compose.ui.graphics.Color = AccentCyan,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.SM))
            .clickable { onClick() }
            .padding(horizontal = AppSpacing.LG, vertical = AppSpacing.MD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.MD),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = iconTint,
        )
        Text(
            text = label,
            style = AppTypography.BodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * More section — settings, performance, about, logout.
 *
 * Pattern:
 * ┌──────────────────────────────────────────┐
 * │       THÊM                              │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ 📊 Hiệu suất                        │ │
 * │ │ ⚙️  Cài đặt                         │ │
 * │ │ ℹ️  Giới thiệu                      │ │
 * │ │ 🚪 Đăng xuất                        │ │
 * │ └──────────────────────────────────────┘ │
 * └──────────────────────────────────────────┘
 */
@Composable
fun MoreSectionScreen(
    onOpenPerformance: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = AppSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        NasSectionHeader(
            title = "THÊM",
            titleColor = PanelTitleCyan,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.LG),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = AppShapes.Card,
        ) {
            Column {
                MenuItemRow(
                    icon = AppIcons.Speed,
                    label = "Hiệu suất",
                    onClick = onOpenPerformance,
                )
                HorizontalDivider(color = DarkDivider, modifier = Modifier.padding(horizontal = AppSpacing.LG))
                MenuItemRow(
                    icon = AppIcons.Settings,
                    label = "Cài đặt",
                    onClick = { /* TODO: settings screen */ },
                )
                HorizontalDivider(color = DarkDivider, modifier = Modifier.padding(horizontal = AppSpacing.LG))
                MenuItemRow(
                    icon = AppIcons.Info,
                    label = "Giới thiệu",
                    onClick = { /* TODO: about dialog */ },
                )
                HorizontalDivider(color = DarkDivider, modifier = Modifier.padding(horizontal = AppSpacing.LG))
                MenuItemRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    label = "Đăng xuất",
                    iconTint = AccentRed,
                    onClick = onLogout,
                )
            }
        }
    }
}
