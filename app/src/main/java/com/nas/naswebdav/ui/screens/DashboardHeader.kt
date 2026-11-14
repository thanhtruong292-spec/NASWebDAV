package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
private val UptimeSecondsRegex = Regex(",\\s*\\d+\\s*giây")

@Composable
fun DashboardHeader(
    systemStatus: String,
    connectionStatus: String = "",
    networkLabel: String,
    isOnLan: Boolean,
    networkAvailable: Boolean,
    uptime: String,
    apiLatencyMs: Long?,
    apiFailureCount: Int,
    simpleMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onPowerMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // "Đang kiểm tra môi trường LAN..." is a transient state, not a live
    // connection. Only promote an authenticated/active route to Online.
    val connectionIsOnline = connectionStatus.contains("Đã kết nối", true) ||
        connectionStatus.contains("Đã xác thực", true) ||
        connectionStatus.startsWith("LAN", true) ||
        connectionStatus.startsWith("Tailscale", true) ||
        connectionStatus.startsWith("Chuyển sang LAN", true) ||
        connectionStatus.startsWith("Chuyển sang Tailscale", true)
    val isConnecting = connectionStatus.startsWith("Đang ", true) || networkLabel == "Đang dò mạng"
    val isOnline = networkAvailable && (systemStatus.contains("Online", true) || connectionIsOnline)
    val statusLevel = when {
        isOnline -> StatusLevel.Success
        isConnecting || systemStatus.contains("Chờ", true) -> StatusLevel.Warning
        else -> StatusLevel.Error
    }
    val statusText = when {
        isOnline -> "Online"
        isConnecting -> "Đang kết nối"
        else -> "Offline"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.LG, vertical = AppSpacing.MD),
        shape = RoundedCornerShape(24.dp),
        color = DarkCard.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AccentCyan, AccentPurple))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Dns, contentDescription = "DNS", tint = DarkSurface, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NAS Dashboard", style = AppTypography.TitleLarge, color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    NasStatusBadge(level = statusLevel, text = statusText)
                }
                Text("Chainedbox L1 Pro", style = AppTypography.BodyMedium, color = TextSecondary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NasStatusPill(
                        level = when {
                            isConnecting -> StatusLevel.Warning
                            !networkAvailable -> StatusLevel.Error
                            isOnLan -> StatusLevel.Success
                            else -> StatusLevel.Info
                        },
                        text = networkLabel,
                    )
                    val cleanUp = uptime.replace(UptimeSecondsRegex, "")
                    if (cleanUp.isNotBlank() && cleanUp != "--") {
                        Icon(AppIcons.History, null, Modifier.size(12.dp), tint = AccentCyan)
                        Text(cleanUp, style = AppTypography.LabelSmall, color = AccentCyan, maxLines = 1)
                    }
                    Text("v${BuildConfig.VERSION_NAME}", style = AppTypography.LabelSmall, color = AccentPurple, maxLines = 1, softWrap = false)
                    apiLatencyMs?.let { Text("${it}ms", style = AppTypography.LabelSmall, color = TextTertiary, maxLines = 1) }
                    if (apiFailureCount > 0) {
                        Text("${apiFailureCount} lỗi", style = AppTypography.LabelSmall, color = AccentRed)
                    }
                }
                Surface(
                    modifier = Modifier.padding(top = 4.dp),
                    shape = AppShapes.Pill,
                    color = DarkElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        DashboardModeOption(
                            label = "Tổng quan",
                            selected = simpleMode,
                            onClick = { onModeChange(true) }
                        )
                        DashboardModeOption(
                            label = "Nâng cao",
                            selected = !simpleMode,
                            onClick = { onModeChange(false) }
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ErrorContainer)
                    .clickable { onPowerMenuClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PowerSettingsNew, "Nguồn", tint = AccentRed, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DashboardModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = AppShapes.Pill,
        color = if (selected) InfoContainer else Color.Transparent,
    ) {
        Text(
            text = label,
            style = AppTypography.LabelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (selected) AccentCyan else TextTertiary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
