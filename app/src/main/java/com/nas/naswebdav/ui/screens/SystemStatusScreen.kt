package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.joinAll
import com.nas.naswebdav.LocalDeviceManagementVM
import com.nas.naswebdav.LocalSystemMonitorVM
import com.nas.naswebdav.ui.components.NasMetricCard
import com.nas.naswebdav.ui.components.NasSectionHeader
import com.nas.naswebdav.ui.screens.ServiceStatusRow

/**
 * System status tab — storage, services, SMART info.
 *
 * Pattern:
 * ┌──────────────────────────────────────────┐
 * │       HỆ THỐNG                          │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ 💾 LƯU TRỮ                          │ │
 * │ │ 1.2 TB / 2 TB                       │ │
 * │ └──────────────────────────────────────┘ │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ ••••••••• DỊCH VỤ                    │ │
 * │ │ Docker container: 5 running          │ │
 * │ └──────────────────────────────────────┘ │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ ••••••••• SMART                      │ │
 * │ │ WD Blue 1TB     ● Healthy  98%      │ │
 * │ └──────────────────────────────────────┘ │
 * └──────────────────────────────────────────┘
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemStatusScreen(
    onBack: () -> Unit = {},
) {
    val systemVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
    // CC-005 P1: pull-refresh nhẹ — chỉ refresh data của màn này (omv/smart/
    // docker/storage), không fetch all như MainMenu. Dùng PullToRefreshContainer
    // (cùng pattern MainMenuScreen) vì bản material3 này chưa có PullToRefreshBox.
    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        androidx.compose.runtime.LaunchedEffect(pullState.isRefreshing) {
            try {
                listOfNotNull(
                    deviceVM.fetchOmvOverview(minIntervalMs = 0L),
                    deviceVM.fetchSmartData(minIntervalMs = 0L),
                    deviceVM.loadDockerContainers(),
                    deviceVM.fetchStorageUsage(minIntervalMs = 0L),
                    systemVM.triggerStatusUpdate(),
                ).joinAll()
            } finally {
                pullState.endRefresh()
            }
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = AppSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        // Header
        NasSectionHeader(
            title = "HỆ THỐNG",
            titleColor = PanelTitleCyan,
        )

        // Storage info from OMV overview
        val omvOverview = deviceVM.omvOverview
        val disks = omvOverview.disks
        if (disks.isNotEmpty()) {
            NasSectionHeader(
                title = "LƯU TRỮ",
                titleColor = PanelTitleCyan,
            )
            disks.forEach { disk ->
                NasMetricCard(
                    icon = AppIcons.Storage,
                    label = disk.model.ifBlank { disk.name },
                    value = disk.size.ifBlank { "--" },
                    valueColor = AccentOrange,
                )
            }
        }

        // Docker containers
        val containers = deviceVM.dockerContainers
        if (containers.isNotEmpty()) {
            NasSectionHeader(
                title = "DOCKER",
                count = containers.size,
                titleColor = AccentCyan,
            )
            containers.forEach { container ->
                ServiceStatusRow(
                    icon = AppIcons.Build,
                    name = container.name,
                    isOnline = container.status.contains("Up", true),
                )
            }
        }

        // SMART info
        val smart = deviceVM.smartInfo
        val hasAnySection = disks.isNotEmpty() || containers.isNotEmpty() || (smart.status.isNotBlank() && smart.status != "Đang tải...")
        if (!hasAnySection) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.LG),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = AppShapes.Card,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.MD)) {
                    Text(
                        text = if (smart.status == "Đang tải...") "Đang tải thông tin hệ thống NAS..." else "Chưa có dữ liệu trạng thái hệ thống. Kéo xuống để làm mới.",
                        style = AppTypography.BodyLarge,
                        color = TextSecondary,
                    )
                }
            }
        }
        if (smart.status.isNotBlank() && smart.status != "Đang tải...") {
            NasSectionHeader(
                title = "SMART",
                titleColor = AccentGreen,
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.LG),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = AppShapes.Card,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.MD)) {
                    Text(
                        text = "Trạng thái: ${smart.status}",
                        style = AppTypography.BodyLarge,
                        color = TextPrimary,
                    )
                    if (smart.temperature.isNotBlank()) {
                        Text(
                            text = "Nhiệt độ: ${smart.temperature}",
                            style = AppTypography.BodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
    } // Column
        // Indicator keo-xuong (cung pattern MainMenuScreen).
        androidx.compose.material3.pulltorefresh.PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    } // Box
}
