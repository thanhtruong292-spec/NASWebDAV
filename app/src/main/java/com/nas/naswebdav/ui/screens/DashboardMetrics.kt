package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nas.naswebdav.ui.components.NasMetricCard
import com.nas.naswebdav.ui.components.NasMetricCardClickable

/**
 * Dashboard metrics row — 4 compact metric cards in a 2×2 grid.
 *
 * Pattern:
 * ┌─────────────────┬─────────────────┐
 * │ 💻 CPU          │ 🧠 RAM          │
 * │ 23%             │ 1.8 GB          │
 * │ 45°C            │ 73%             │
 * ├─────────────────┼─────────────────┤
 * │ 🌐 Network      │ 💾 Storage      │
 * │ 125 / 45 Mbps   │ 1.2 TB / 2 TB   │
 * │ ↓ LAN           │ 60%             │
 * └─────────────────┴─────────────────┘
 */
@Composable
fun DashboardMetrics(
    cpu: String,
    cpuTemp: String,
    ram: String,
    ramPercent: String,
    networkDown: String,
    networkUp: String,
    networkLabel: String,
    storageUsed: String,
    storageTotal: String,
    storagePercent: String,
    onCpuClick: () -> Unit,
    onRamClick: () -> Unit,
    onStorageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = AppSpacing.LG),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        // Row 1: CPU + RAM
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
            NasMetricCardClickable(
                icon = Icons.Default.Memory,
                label = "CPU",
                value = cpu,
                subtitle = cpuTemp,
                onClick = onCpuClick,
                valueColor = AccentPurple,
                modifier = Modifier.weight(1f),
            )
            NasMetricCardClickable(
                icon = Icons.Default.Speed,
                label = "RAM",
                value = ram,
                subtitle = ramPercent,
                onClick = onRamClick,
                valueColor = AccentGreen,
                modifier = Modifier.weight(1f),
            )
        }

        // Row 2: Network + Storage
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
            NasMetricCard(
                icon = Icons.Default.NetworkWifi,
                label = "Network",
                value = "$networkDown / $networkUp",
                subtitle = networkLabel,
                valueColor = AccentCyan,
                modifier = Modifier.weight(1f),
            )
            NasMetricCardClickable(
                icon = Icons.Default.Storage,
                label = "Storage",
                value = "$storageUsed / $storageTotal",
                subtitle = storagePercent,
                onClick = onStorageClick,
                valueColor = AccentOrange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
