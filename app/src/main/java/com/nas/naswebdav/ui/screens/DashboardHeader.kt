package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.BuildConfig
import com.nas.naswebdav.ui.components.NasStatusBadge
import com.nas.naswebdav.ui.components.NasStatusPill
import com.nas.naswebdav.ui.components.StatusLevel

/**
 * Dashboard header — NAS name + connection status + network badge + uptime + version.
 *
 * Pattern (redesigned):
 * ┌─────────────────────────────────────────────────┐
 * │ NAS Dashboard                     [⏻ power]    │
 * │ Chainedbox L1 Pro  ● Online                     │
 * │ 🌐 LAN  ⏱ 12d 3h  v1.0.48                      │
 * └─────────────────────────────────────────────────┘
 */
@Composable
fun DashboardHeader(
    systemStatus: String,
    networkLabel: String,
    isOnLan: Boolean,
    uptime: String,
    apiLatencyMs: Long?,
    apiFailureCount: Int,
    onPowerMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOnline = systemStatus.contains("Online", true) || systemStatus.contains("Đã kết nối", true)
    val statusLevel = when {
        isOnline -> StatusLevel.Success
        systemStatus.contains("Chờ", true) -> StatusLevel.Warning
        else -> StatusLevel.Error
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.LG, vertical = AppSpacing.MD),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
            // Title row
            Text(
                text = "NAS Dashboard",
                style = AppTypography.HeadlineLarge,
                color = TextPrimary,
            )

            // NAS name + status badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Chainedbox L1 Pro",
                    style = AppTypography.BodyLarge,
                    color = TextSecondary,
                )
                Spacer(Modifier.width(AppSpacing.SM))
                NasStatusBadge(
                    level = statusLevel,
                    text = if (isOnline) "Online" else "Offline",
                )
            }

            // Network + uptime + version row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM),
            ) {
                // Network badge (LAN / Tailscale)
                NasStatusPill(
                    level = if (isOnLan) StatusLevel.Success else StatusLevel.Info,
                    text = networkLabel,
                )

                // Uptime
                val cleanUp = uptime.replace(Regex(",\\s*\\d+\\s*giây"), "")
                if (cleanUp.isNotBlank() && cleanUp != "--") {
                    Icon(
                        imageVector = com.nas.naswebdav.ui.screens.AppIcons.History,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = AccentCyan,
                    )
                    Text(
                        text = cleanUp,
                        style = AppTypography.BodySmall,
                        color = AccentCyan,
                    )
                }

                // Version badge
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = AppTypography.LabelSmall,
                    color = AccentPurple,
                )

                // API latency
                apiLatencyMs?.let { latency ->
                    Text(
                        text = "API ${latency}ms",
                        style = AppTypography.LabelSmall,
                        color = TextTertiary,
                    )
                }

                // API failures
                if (apiFailureCount > 0) {
                    Text(
                        text = "${apiFailureCount} lỗi",
                        style = AppTypography.LabelSmall,
                        color = AccentRed,
                    )
                }
            }
        }

        // Power button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(DarkCard)
                .clickable { onPowerMenuClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Nguồn",
                tint = AccentRed,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
