package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.components.NasSectionHeader
import com.nas.naswebdav.ui.components.NasStatusBadge
import com.nas.naswebdav.ui.components.StatusLevel

/**
 * Service status row — single service with icon + name + status badge.
 */
@Composable
fun ServiceStatusRow(
    icon: ImageVector,
    name: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        modifier.clickable { onClick() }
    } else {
        modifier
    }

    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.LG, vertical = AppSpacing.SM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.MD),
    ) {
        // Service icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(DarkCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isOnline) AccentGreen else TextTertiary,
            )
        }

        // Service name
        Text(
            text = name,
            style = AppTypography.BodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )

        // Status badge
        NasStatusBadge(
            level = if (isOnline) StatusLevel.Success else StatusLevel.Error,
            text = if (isOnline) "Online" else "Offline",
        )
    }
}

/**
 * Dashboard services section — list of key NAS services with status.
 *
 * Pattern:
 * ┌──────────────────────────────────────┐
 * │ •••••• DỊCH VỤ         [X online]   │
 * │ [■] SMB/CIFS              ● Online   │
 * │ [■] WebDAV                ● Online   │
 * │ [■] Docker                ● Online   │
 * │ [■] OpenMediaVault        ● Online   │
 * │ [■] Samba                 ● Offline  │
 * └──────────────────────────────────────┘
 */
@Composable
fun DashboardServices(
    services: List<Pair<String, Boolean>>,
    onServiceClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val onlineCount = services.count { it.second }

    Column(modifier = modifier) {
        NasSectionHeader(
            title = "DỊCH VỤ",
            count = onlineCount,
            titleColor = PanelTitleCyan,
        )

        services.forEach { (name, isOnline) ->
            val icon = when {
                name.contains("SMB", true) || name.contains("Samba", true) -> Icons.Default.Folder
                name.contains("WebDAV", true) -> Icons.Default.Cloud
                name.contains("Docker", true) -> Icons.Default.Build
                name.contains("OpenMediaVault", true) || name.contains("OMV", true) -> Icons.Default.Storage
                name.contains("SSH", true) -> Icons.Default.Terminal
                name.contains("FTP", true) -> Icons.Default.CloudUpload
                else -> Icons.Default.Settings
            }
            ServiceStatusRow(
                icon = icon,
                name = name,
                isOnline = isOnline,
                onClick = { onServiceClick(name) },
            )
        }
    }
}
