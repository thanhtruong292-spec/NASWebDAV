package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.components.NasSectionHeader
import com.nas.naswebdav.ui.components.StatusLevel

/**
 * Single alert/insight row — icon + message + optional action.
 */
@Composable
fun AlertRow(
    icon: ImageVector,
    message: String,
    level: StatusLevel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        modifier.clickable { onClick() }
    } else {
        modifier
    }

    val bgColor = when (level) {
        StatusLevel.Error -> AccentRed.copy(alpha = 0.08f)
        StatusLevel.Warning -> AccentOrange.copy(alpha = 0.08f)
        StatusLevel.Info -> AccentCyan.copy(alpha = 0.08f)
        StatusLevel.Success -> AccentGreen.copy(alpha = 0.08f)
    }
    val iconTint = when (level) {
        StatusLevel.Error -> AccentRed
        StatusLevel.Warning -> AccentOrange
        StatusLevel.Info -> AccentCyan
        StatusLevel.Success -> AccentGreen
    }

    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .clip(AppShapes.Card)
            .background(bgColor)
            .padding(horizontal = AppSpacing.MD, vertical = AppSpacing.SM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = iconTint,
        )
        Text(
            text = message,
            style = AppTypography.BodySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Dashboard alerts/insights section — shows warnings, errors, and smart insights.
 *
 * Pattern:
 * ┌──────────────────────────────────────┐
 * │ ••••••• THÔNG BÁO                   │
 * │ ⚠ RAM sử dụng trên 80%              │
 * │ 💡 Nên dọn cache định kỳ            │
 * │ ❌ Docker container đã dừng         │
 * └──────────────────────────────────────┘
 */
@Composable
fun DashboardAlerts(
    alerts: List<Triple<ImageVector, String, StatusLevel>>,
    onAlertClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (alerts.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS),
    ) {
        NasSectionHeader(
            title = "THÔNG BÁO",
            count = alerts.size,
            titleColor = AccentOrange,
        )

        alerts.forEachIndexed { index, (icon, message, level) ->
            AlertRow(
                icon = icon,
                message = message,
                level = level,
                onClick = { onAlertClick(index) },
            )
        }
    }
}
