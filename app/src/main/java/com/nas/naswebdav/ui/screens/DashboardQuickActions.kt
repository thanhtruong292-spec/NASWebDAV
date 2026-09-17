package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.components.NasSectionHeader

/**
 * Single quick action button — icon + label in a rounded card.
 *
 * Pattern:
 * ┌─────────────┐
 * │     [■]     │
 * │   Tệp tin   │
 * └─────────────┘
 */
@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: androidx.compose.ui.graphics.Color = AccentCyan,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.MD))
            .background(DarkCard)
            .clickable { onClick() }
            .padding(AppSpacing.SM),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(28.dp),
            tint = iconTint,
        )
        Text(
            text = label,
            style = AppTypography.LabelSmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * Dashboard quick actions — grid of common actions.
 *
 * Pattern:
 * ┌──────────────────────────────────────┐
 * │ ••••••• HÀNH ĐỘNG NHANH             │
 * │ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐│
 * │ │📁    │ │📸    │ │🎬    │ │🗑️    ││
 * │ │Tệp   │ │Ảnh   │ │Video │ │Thùng ││
 * │ │tin   │ │mới   │ │gần   │ │rác   ││
 * │ └──────┘ └──────┘ └──────┘ └──────┘│
 * │ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐│
 * │ │🔍    │ │📊    │ │🔗    │ │⚙️    ││
 * │ │Tìm   │ │Đối   │ │Guest │ │Máy  ││
 * │ │kiếm  │ │tác   │ │Pass  │ │chủ  ││
 * │ └──────┘ └──────┘ └──────┘ └──────┘│
 * └──────────────────────────────────────┘
 */
@Composable
fun DashboardQuickActions(
    actions: List<Triple<ImageVector, String, () -> Unit>>,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    Column(modifier = modifier) {
        NasSectionHeader(
            title = "HÀNH ĐỘNG NHANH",
            titleColor = PanelTitleCyan,
        )

        val rows = actions.chunked(columns)
        rows.forEach { rowActions ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.LG),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM),
            ) {
                rowActions.forEach { (icon, label, onClick) ->
                    QuickActionButton(
                        icon = icon,
                        label = label,
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fill empty slots
                repeat(columns - rowActions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (rowActions != rows.last()) {
                Spacer(modifier = Modifier.height(AppSpacing.SM))
            }
        }
    }
}
