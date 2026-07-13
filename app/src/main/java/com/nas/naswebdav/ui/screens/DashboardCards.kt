@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*

import android.content.Context
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import coil.compose.AsyncImage
import com.nas.naswebdav.NasFile

// ============ Dashboard cards (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

@Composable
internal fun MainDashboardHeader(
    viewModel: WebDavViewModel,
    realtimeNow: Long,
    showPowerMenu: Boolean,
    onPowerMenuChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onReboot: () -> Unit,
    onShutdown: () -> Unit,
) {
    // ═══ PHASE 7c.2: SystemMonitor state (systemStatus, networkPingMs, lastXxxRefreshAt,
    // apiLatencyMs, isOnLan) reads via facade delegation → SystemMonitorVM is SSoT.
    // DeviceManagement state (smartInfo, isFanModeUpdating, omvOverview) → DeviceMgmtVM.
    // Both VMs reference the SAME instances via CompositionLocalProvider in MainActivity,
    // so facade mirror reads and direct LocalXxxVM reads observe identical Compose State.
    val systemVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
        // ═══ HEADER ═══
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Đèn tín hiệu trạng thái (Pulse animation)
            val currentStatus = viewModel.systemStatus.status
            val isOnlineStatus = currentStatus.contains("Online", true) || currentStatus.contains("Đã kết nối", true)
            val statusColor = when {
                currentStatus.contains("Online", true) || currentStatus.contains("Đã kết nối", true) -> AccentGreen
                currentStatus.contains("Chờ", true) -> AccentOrange
                else -> AccentRed
            }
            Column {
                Text("NAS Dashboard", style = AppTypography.HeadlineLarge.copy(color = TextPrimary))
                // Nhãn phiên bản auto theo build — nhìn là biết bản nào, tránh nhầm
                Text("v${com.nas.naswebdav.BuildConfig.VERSION_NAME}", style = AppTypography.LabelMedium.copy(color = TextSecondary.copy(alpha = 0.7f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chainedbox L1 Pro", style = AppTypography.BodyLarge.copy(color = TextSecondary))
                    Text("  \u2022  ", style = AppTypography.BodyLarge.copy(color = TextSecondary))
                    Box(
                        modifier = Modifier
                            .clip(AppShapes.Badge)
                            .background(if (isOnlineStatus) AccentGreen else AccentRed)
                            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XXS)
                    ) {
                        Text(
                            if (isOnlineStatus) "Online" else "Offline",
                            style = AppTypography.BodyMedium.copy(color = Color.White, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.SM))
                    val isRealtimeStale = viewModel.lastStatusRefreshAt <= 0L || realtimeNow - viewModel.lastStatusRefreshAt > 10_000L
                    val realtimeColor = if (isRealtimeStale) AccentOrange else AccentGreen
                    Icon(Icons.Default.Sync, null, tint = realtimeColor, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(AppSpacing.XS))
                    Text(
                        realtimeFreshnessLabel(viewModel.lastStatusRefreshAt, realtimeNow),
                        style = AppTypography.BodySmall.copy(color = realtimeColor, fontWeight = FontWeight.SemiBold)
                    )
                    viewModel.apiLatencyMs?.let { latency ->
                        Spacer(Modifier.width(AppSpacing.SM))
                        Text("API ${latency}ms", style = AppTypography.BodySmall.copy(color = TextSecondary))
                    }
                    if (viewModel.apiFailureCount > 0) {
                        Spacer(Modifier.width(AppSpacing.SM))
                        Text("${viewModel.apiFailureCount} lỗi", style = AppTypography.BodySmall.copy(color = AccentRed, fontWeight = FontWeight.Bold))
                    }
                }
                
                // ── SMART SWITCH BADGE ──
                Spacer(Modifier.height(AppSpacing.XS))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .clip(AppShapes.Badge)
                            .background(if (viewModel.isOnLan) AccentGreen.copy(alpha = 0.15f) else AccentCyan.copy(alpha = 0.15f))
                            .padding(horizontal = AppSpacing.SM - AppSpacing.XS, vertical = AppSpacing.XXS),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (viewModel.isOnLan) Icons.Default.NetworkWifi else Icons.Default.Language,
                            contentDescription = null,
                            tint = if (viewModel.isOnLan) AccentGreen else AccentCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(AppSpacing.XS))
                        Text(
                            if (viewModel.isOnLan) "LAN" else "Tailscale",
                            style = AppTypography.BodySmall.copy(fontWeight = FontWeight.Bold, color = if (viewModel.isOnLan) AccentGreen else AccentCyan)
                        )
                    }
                    val ut = viewModel.systemStatus.uptime
                    if (ut.isNotBlank() && ut != "--") {
                        val cleanUt = ut.replace(Regex(",\\s*\\d+\\s*giây"), "")
                        Spacer(Modifier.width(AppSpacing.SM))
                        Icon(Icons.Default.Schedule, null, tint = AccentCyan, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(AppSpacing.XXS + AppSpacing.XS))
                        Text(cleanUt, style = AppTypography.BodyMedium.copy(color = AccentCyan, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(DarkCard).clickable { onPowerMenuChange(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Nguồn", tint = AccentRed, modifier = Modifier.size(16.dp))
                    
                    DropdownMenu(
                        expanded = showPowerMenu,
                        onDismissRequest = { onPowerMenuChange(false) },
                        modifier = Modifier.background(DarkCard)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Đăng xuất", color = AccentOrange) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = AccentOrange) },
                            onClick = { onPowerMenuChange(false); onLogout() }
                        )
                        DropdownMenuItem(
                            text = { Text("Khởi động lại NAS", color = AccentGreen) },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null, tint = AccentGreen) },
                            onClick = { onPowerMenuChange(false); onReboot() }
                        )
                        DropdownMenuItem(
                            text = { Text("Ngủ NAS", color = AccentCyan) },
                            leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null, tint = AccentCyan) },
                            onClick = { onPowerMenuChange(false); onShutdown() }
                        )
                    }
                }
            }
        }
}

@Composable
internal fun DashboardSystemOverviewCard(
    viewModel: WebDavViewModel,
    realtimeNow: Long,
    onShowProcessList: (String) -> Unit,
    onOpenNewDiskProfile: () -> Unit,
    onOpenSmartDetails: () -> Unit,
) {
            // Đã THẾ HỆ THỐNG: CPU + RAM + Stats Đã
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = AppShapes.Card
        ) {
            Column(Modifier.padding(horizontal = AppSpacing.SM, vertical = AppSpacing.SM - AppSpacing.XS)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HỆ THỐNG", fontSize = PanelTitleSize, color = PanelTitleCyan, fontWeight = FontWeight.Black,
                        letterSpacing = PanelTitleLetterSpacing)
                    Spacer(Modifier.weight(1f))
                    PanelFreshnessTag(viewModel.lastMetricsRefreshAt, realtimeNow, staleAfterMs = 15_000L)
                }
                Spacer(Modifier.height(AppSpacing.SM - AppSpacing.XS))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeCard(
                        title = "CPU", value = viewModel.systemStatus.cpu,
                        subValue = viewModel.systemStatus.cpuTemp,
                        icon = Icons.Default.Memory,
                        gradientColors = listOf(AccentPurple, AccentPurple.copy(alpha = 0.8f)),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onShowProcessList("cpu")
                        }
                    )
                    GaugeCard(
                        title = "RAM", value = viewModel.systemStatus.ram, subValue = "${viewModel.systemStatus.ramPercent}%",
                        icon = Icons.Default.DeveloperBoard,
                        gradientColors = listOf(AccentGreen, AccentGreen.copy(alpha = 0.8f)),
                        modifier = Modifier.weight(1f),
                        overridePercent = viewModel.systemStatus.ramPercent.replace("%", "").trim().toFloatOrNull(),
                        onClick = {
                            onShowProcessList("mem")
                        }
                    )
                    
                    val hddDisk = viewModel.systemStatus.diskParts.firstOrNull { it.mount.startsWith("/srv/dev-disk-by-label-data") }
                        ?: viewModel.systemStatus.diskParts.find { it.mount != "/" && !it.mount.startsWith("/mnt/usb-import") }
                    if (hddDisk != null) {
                        val fmtTotal = hddDisk.total.let {
                            val n = it.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                            if (it.contains("GB", true) && n >= 1000f) "%.1f TB".format(java.util.Locale.US, n / 1024f) else it
                        }
                        GaugeCard(
                            title = "HDD", value = "${hddDisk.used} / $fmtTotal",
                            subValue = "${hddDisk.percent}%",
                            icon = Icons.Default.Storage,
                            gradientColors = listOf(AccentOrange, AccentOrange.copy(alpha = 0.8f)),
                            modifier = Modifier.weight(1f),
                            overridePercent = hddDisk.percent,
                            onClick = onOpenNewDiskProfile
                        )
                    } else Spacer(Modifier.weight(1f))
                    
                    val smartStatusText = viewModel.smartInfo.status.uppercase().trim()
                    
                    val isSmartOk = smartStatusText.contains("PASSED") || smartStatusText == "OK"
                    val isSmartFailed = smartStatusText.contains("FAILED")
                    val isSmartEmmc = smartStatusText.contains("EMMC")
                    
                    val smartColors = when {
                        isSmartOk -> listOf(AccentGreen, AccentGreen.copy(alpha = 0.8f))
                        isSmartEmmc -> listOf(AccentBlue, AccentBlue.copy(alpha = 0.8f)) // Nhận diện eMMC màu Xanh Dương
                        isSmartFailed -> listOf(AccentRed, AccentRed.copy(alpha = 0.8f)) // FAILED hiển thị màu Đỏ
                        else -> listOf(TextTertiary, TextTertiary.copy(alpha = 0.8f)) // Màu xám cho UNKNOWN, ĐANG TẢI, LỖI...
                    }
                    val smartPercent = when {
                        isSmartOk -> 100f
                        isSmartEmmc -> 100f
                        isSmartFailed -> 0f
                        else -> 50f
                    }
                    GaugeCard(
                        title = "S.M.A.R.T",
                        value = smartStatusText,
                        // Nhiệt độ HDD THỰC TẾ (live, refresh mỗi poll) thay vì nhiệt lúc quét SMART (kẹt cố định).
                        subValue = viewModel.systemStatus.temp.replace("°C", "°").replace("--", ""),
                        icon = Icons.Default.HealthAndSafety,
                        gradientColors = smartColors,
                        modifier = Modifier.weight(1f),
                        overridePercent = smartPercent,
                        onClick = onOpenSmartDetails
                    )
                }
                            // Fan Control
                            Spacer(Modifier.height(AppSpacing.SM - AppSpacing.XS))
                            Row(Modifier.fillMaxWidth().background(DarkCard, AppShapes.Badge).padding(AppSpacing.SM - AppSpacing.XS), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val fanStatusStr = viewModel.systemStatus.fanStatus
                                    val statusPercent = Regex("""(\d+)\s*%""").find(fanStatusStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    val rpmFromApi = viewModel.systemStatus.fanRpm ?: Regex("""(\d+)\s*rpm""", RegexOption.IGNORE_CASE).find(fanStatusStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    val percentFromRpm = rpmFromApi?.let { rpm -> ((rpm * 100f) / 4300f).toInt() }
                                    val realPercent = statusPercent ?: percentFromRpm ?: 0

                                    // Hiển thị ĐÚNG trạng thái thực tế do server báo (get_fan_info đã đọc
                                    // PWM duty + enable + cổng nguồn 5V GPIO). KHÔNG tự suy đoán theo nhiệt
                                    // độ: trước đây ở chế độ custom app tính lại percent từ HDD temp nên lệch
                                    // với quạt thật (vd HDD temp "--" -> đoán "Dừng" dù quạt đang chạy).
                                    val displayPercent = realPercent.coerceIn(0, 100)
                                    val displayStatusStr = if (realPercent > 0) fanStatusStr else "Dừng"
                                    val isFanDisplayRunning = displayPercent > 0
                                    FanSpeedIcon(percent = displayPercent, color = if (isFanDisplayRunning) AccentGreen else TextSecondary, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(AppSpacing.SM))
                                    Column {
                                        Text("Quạt tản nhiệt", style = AppTypography.BodyMedium.copy(color = TextPrimary, fontWeight = FontWeight.Bold))
                                        Text(displayStatusStr, style = AppTypography.LabelMedium.copy(color = if (isFanDisplayRunning) AccentGreen else TextSecondary))
                                    }
                                }
                                // Mute / Auto / Max Toggle
                                var showFanSettings by remember { mutableStateOf(false) }
                                Row(Modifier.clip(AppShapes.Badge).background(Color.Black)) {
                                    val modes = listOf("custom" to "Tự động", "on" to "Bật", "off" to "Tắt")
                                    val currentMode = viewModel.systemStatus.fanMode
                                    val isFanControlLocked = viewModel.isFanModeUpdating
                                    modes.forEach { (m, label) ->
                                        val active = m == currentMode
                                        Box(
                                            Modifier.clickable(
                                                enabled = !isFanControlLocked,
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                if (m == "custom") showFanSettings = true else {
                                                    viewModel.setFanMode(m)
                                                }
                                            }
                                                .background(if (active) if (m == "off") AccentRed else AccentGreen else Color.Transparent)
                                                .alpha(if (isFanControlLocked && !active) 0.5f else 1f)
                                                .padding(horizontal = AppSpacing.SM - AppSpacing.XS, vertical = AppSpacing.XS)
                                        ) {
                                            Text(label, style = AppTypography.LabelMedium.copy(color = if (active) Color.Black else TextSecondary, fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                                
                                if (showFanSettings) {
                                    var onTemp by remember { mutableStateOf(viewModel.systemStatus.fanOnTemp.toInt().toString()) }
                                    var offTemp by remember { mutableStateOf(viewModel.systemStatus.fanOffTemp.toInt().toString()) }
                                    androidx.compose.material3.AlertDialog(
                                        onDismissRequest = { showFanSettings = false },
                                        title = { Text("Độ trễ nhiệt (Hysteresis)", style = AppTypography.TitleLarge.copy(color = TextPrimary)) },
                                        text = { 
                                            Column {
                                                Text("Hệ thống sẽ chạy ngầm để bật quạt khi tới 'Nhiệt độ bật', và tắt quạt khi hạ xuống 'Nhiệt độ tắt'.", style = AppTypography.BodyLarge.copy(color = TextSecondary))
                                                Spacer(Modifier.height(AppSpacing.MD))
                                                OutlinedTextField(value = onTemp, onValueChange = { onTemp = it }, label = { Text("Nhiệt độ Bật (°C)") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                                Spacer(Modifier.height(AppSpacing.SM))
                                                OutlinedTextField(value = offTemp, onValueChange = { offTemp = it }, label = { Text("Nhiệt độ Tắt (°C)") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                            }
                                        },
                                        confirmButton = {
                                            val isFanControlLocked = viewModel.isFanModeUpdating
                                            Button(
                                                enabled = !isFanControlLocked,
                                                onClick = { 
                                                    viewModel.setFanMode("custom", onTemp.toFloatOrNull() ?: 45f, offTemp.toFloatOrNull() ?: 40f)
                                                    showFanSettings = false 
                                                }
                                            ) { Text("Lưu & Áp dụng") }
                                        },
                                        dismissButton = {
                                            androidx.compose.material3.TextButton(onClick = { showFanSettings = false }) { Text("Hủy", color = TextSecondary) }
                                        },
                                        containerColor = DarkCardHover,
                                        textContentColor = Color.White
                                    )
                                }
                            }
            }
        }
}

@Composable
internal fun OmvServicesHardwarePanel(viewModel: WebDavViewModel) {
        // ═══ OMV SERVICES & HARDWARE (Expandable Panel) ═══
        var pendingServiceName by remember { mutableStateOf("") }
        var pendingServiceTitle by remember { mutableStateOf("") }
        var pendingServiceEnable by remember { mutableStateOf(false) }
        if (pendingServiceName.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { pendingServiceName = "" },
                containerColor = DarkElevated,
                title = { Text("Xác nhận dịch vụ", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "${if (pendingServiceEnable) "Bật" else "Tắt"} dịch vụ $pendingServiceTitle?",
                        color = TextSecondary,
                        style = AppTypography.BodyLarge.copy(color = TextSecondary)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.toggleOmvService(pendingServiceName, pendingServiceEnable)
                        pendingServiceName = ""
                    }) { Text(if (pendingServiceEnable) "Bật" else "Tắt", color = if (pendingServiceEnable) AccentGreen else AccentRed, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingServiceName = "" }) { Text("Hủy", color = TextSecondary) }
                }
            )
        }
        if (viewModel.omvOverview.services.isNotEmpty() || viewModel.omvOverview.disks.isNotEmpty()) {
            // Mo doc quyen: panel mo dong bo voi ExclusivePanelState — khi mo
            // panel khac (Tasks, Chart) thi panel nay tu cup.
            val omvExpanded = ExclusivePanelState.current.value == "omv"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = AppShapes.Card
            ) {
                Column(Modifier.padding(AppSpacing.SM)) {
                    // Header — nhấn để mở/đóng
                    Row(
                        Modifier.fillMaxWidth().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { ExclusivePanelState.toggle("omv") },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dashboard, null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(AppSpacing.SM - AppSpacing.XS))
                            Text("OMV", fontSize = PanelTitleSize, fontWeight = FontWeight.Black, color = PanelTitleCyan, letterSpacing = PanelTitleLetterSpacing)
                            if (viewModel.omvOverview.omvVersion.isNotBlank()) {
                                Spacer(Modifier.width(AppSpacing.SM - AppSpacing.XS))
                                Text(viewModel.omvOverview.omvVersion, style = AppTypography.BodySmall.copy(color = TextSecondary))
                            }
                            val ping = viewModel.networkPingMs
                            if (ping != null) {
                                Spacer(Modifier.width(AppSpacing.SM))
                                val pingColor = when {
                                    ping < 0L -> AccentRed
                                    ping < 50L -> AccentGreen
                                    ping < 150L -> AccentOrange
                                    else -> AccentRed
                                }
                                val pingText = if (ping < 0L) "Mất kết nối" else "${ping}ms"
                                Text(pingText, style = AppTypography.BodySmall.copy(color = pingColor, fontWeight = FontWeight.Bold))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Tải xuống / Tải lên inline ngay header
                            Text("↓ ${viewModel.systemStatus.netRx}", style = AppTypography.LabelMedium.copy(color = AccentBlue, fontWeight = FontWeight.Bold))
                            Spacer(Modifier.width(AppSpacing.SM))
                            Text("↑ ${viewModel.systemStatus.netTx}", style = AppTypography.LabelMedium.copy(color = AccentPurple, fontWeight = FontWeight.Bold))
                            Spacer(Modifier.width(AppSpacing.XS))
                            Icon(
                                if (omvExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Nội dung mở rộng
                    androidx.compose.animation.AnimatedVisibility(visible = omvExpanded) {
                        Column {
                            Spacer(Modifier.height(AppSpacing.SM - AppSpacing.XS))

                            // Services Row
                            if (viewModel.omvOverview.services.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    viewModel.omvOverview.services.forEach { svc ->
                                        val svcActive = svc.effectiveEnabled
                                        val svcColor = if (svcActive) AccentGreen else TextSecondary.copy(alpha = 0.45f)
                                        val svcIcon = when (svc.name) {
                                            "ssh" -> Icons.Default.Terminal
                                            "ftp" -> Icons.Default.CloudUpload
                                            "samba" -> Icons.Default.FolderShared
                                            "nfs" -> Icons.Default.Storage
                                            else -> Icons.Default.SettingsEthernet
                                        }
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.weight(1f).clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                pendingServiceName = svc.name
                                                pendingServiceTitle = svc.title
                                                pendingServiceEnable = !svcActive
                                            }
                                        ) {
                                            Icon(svcIcon, null, tint = svcColor, modifier = Modifier.size(18.dp))
                                            Text(svc.title, style = AppTypography.LabelMedium.copy(color = svcColor, fontWeight = FontWeight.Bold), maxLines = 1)
                                            Text(if (svcActive) "Bật" else "Tắt", style = AppTypography.LabelMedium.copy(color = svcColor.copy(alpha = 0.7f)))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(AppSpacing.SM - AppSpacing.XS))
                            }

                            // Network + Hardware info
                            val net = viewModel.omvOverview.network.firstOrNull()
                            val hdd = selectNasTargetDiskFromDiskProfileScreen(viewModel.omvOverview.disks)
                            if (net != null || hdd != null) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    if (net != null) {
                                        Column {
                                            Text("${net.name} • ${net.speed}Mbps", style = AppTypography.BodySmall.copy(color = TextSecondary, letterSpacing = 0.5.sp))
                                            Text("${net.address} | Cổng mạng: ${net.gateway}", style = AppTypography.BodySmall.copy(color = AccentBlue.copy(alpha = 0.7f)))
                                            Text("MAC: ${net.mac}", style = AppTypography.LabelMedium.copy(color = TextSecondary.copy(alpha = 0.6f)))
                                        }
                                    }
                                    if (hdd != null) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(hdd.model, style = AppTypography.BodySmall.copy(color = TextSecondary), maxLines = 1)
                                            Text("Số sê-ri: ${hdd.serial}", style = AppTypography.LabelMedium.copy(color = TextSecondary.copy(alpha = 0.6f)))
                                            val sizeGb = (hdd.size.toLongOrNull() ?: 0L) / (1024L * 1024 * 1024)
                                            val sizeTb = if (sizeGb >= 1024) "%.1f TB".format(sizeGb / 1024f) else "$sizeGb GB"
                                            Text(sizeTb, style = AppTypography.BodySmall.copy(color = AccentOrange, fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                            }
                            
                        }
                    }
                }
            }
        }
}

@Composable
internal fun TorrentActivityCard(
    viewModel: WebDavViewModel,
    onOpenFolder: (webdavPath: String) -> Unit,
    onGlobalSearch: (String) -> Unit,
) {
        // Đã TORRENT ĐANG TẢI & HOÀN THÀNH Đã
        val downloadingTorrents = viewModel.systemStatus.torrents.filter { t ->
            val s = t.state
            // Active or paused download - NOT yet completed
            s.contains("DL", ignoreCase = false) || s == "downloading" || s == "stalledDL" || s == "forcedDL" || s == "metaDL" || s.isEmpty()
        }
        val completedTorrents = viewModel.systemStatus.torrents.filter { t ->
            val s = t.state
            // stoppedUP, uploading, pausedUP, forcedUP = seeding after completion
            s.contains("UP", ignoreCase = false) || t.progress >= 1f
        }
        if (downloadingTorrents.isNotEmpty() || completedTorrents.isNotEmpty()) {
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = AppShapes.Card
            ) {
                Column(Modifier.padding(AppSpacing.SM)) {
                    if (downloadingTorrents.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDownload, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(AppSpacing.SM - AppSpacing.XS))
                            Text("Đang tải xuống (${downloadingTorrents.size})", style = AppTypography.TitleMedium.copy(color = TextPrimary))
                        }
                        Spacer(Modifier.height(AppSpacing.XS))
                        downloadingTorrents.take(5).forEach { torrent ->
                            var showTorrentMenu by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .pointerInput(torrent.hash) { detectTapGestures(onLongPress = { showTorrentMenu = true }) }
                                        .padding(vertical = AppSpacing.XS),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(torrent.name, style = AppTypography.BodyLarge.copy(color = TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(torrent.speed, style = AppTypography.BodyMedium.copy(color = AccentCyan), modifier = Modifier.padding(start = AppSpacing.SM))
                                        }
                                        Spacer(Modifier.height(AppSpacing.XS))
                                        LinearProgressIndicator(
                                            progress = { torrent.progress },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(AppShapes.Badge),
                                            color = AccentGreen, trackColor = TextSecondary.copy(alpha = 0.2f)
                                        )
                                    }
                                    Spacer(Modifier.width(AppSpacing.SM))
                                    val isPaused = if (torrent.state.isNotEmpty()) {
                                        torrent.state == "pausedDL" || torrent.state == "stoppedDL"
                                    } else {
                                        torrent.speed == "0 B/s"
                                    }
                                    Box(
                                        modifier = Modifier.size(26.dp).clip(CircleShape).background(DarkSurface).clickable {
                                            if (isPaused) viewModel.controlTorrent("resume", torrent.hash) else viewModel.controlTorrent("pause", torrent.hash)
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (isPaused) AccentGreen else AccentOrange, modifier = Modifier.size(12.dp))
                                    }
                                }
                                DropdownMenu(expanded = showTorrentMenu, onDismissRequest = { showTorrentMenu = false }) {
                                    DropdownMenuItem(text = { Text("Tạm dừng") }, leadingIcon = { Icon(Icons.Default.Pause, null, tint = AccentOrange) }, onClick = { showTorrentMenu = false; viewModel.controlTorrent("pause", torrent.hash) })
                                    DropdownMenuItem(text = { Text("Tiếp tục") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = AccentGreen) }, onClick = { showTorrentMenu = false; viewModel.controlTorrent("resume", torrent.hash) })
                                    DropdownMenuItem(text = { Text("Xóa", color = AccentRed) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = AccentRed) }, onClick = { showTorrentMenu = false; viewModel.controlTorrent("delete", torrent.hash) })
                                }
                            }
                        }
                    }
                    
                    if (completedTorrents.isNotEmpty()) {
                        if (downloadingTorrents.isNotEmpty()) {
                            Spacer(Modifier.height(AppSpacing.SM))
                            androidx.compose.material3.HorizontalDivider(color = TextSecondary.copy(alpha = 0.1f), thickness = 0.7.dp)
                            Spacer(Modifier.height(AppSpacing.SM))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(AppSpacing.SM - AppSpacing.XS))
                            Text("Đã hoàn thành (${completedTorrents.size})", style = AppTypography.TitleMedium.copy(color = TextPrimary))
                        }
                        Spacer(Modifier.height(AppSpacing.XS))
                        completedTorrents.take(5).forEach { torrent ->
                            var showCompletedMenu by remember { mutableStateOf(false) }
                            androidx.compose.runtime.key(torrent.hash) {
                            com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                onDelete = { viewModel.controlTorrent("delete", torrent.hash) },
                                shape = AppShapes.Badge,
                                backgroundPaddingHorizontal = 8.dp,
                                iconSize = 18.dp
                            ) {
                            Box(Modifier.fillMaxWidth().background(DarkCard)) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .pointerInput(torrent.hash) { detectTapGestures(
                                            onTap = {
                                                if (torrent.savePath.isNotEmpty()) {
                                                    // /downloads/* on NAS is symlinked as Downloads/ in WebDAV root
                                                    val base = viewModel.webDavManager.currentBaseUrl
                                                    val linuxPath = torrent.savePath.trimEnd('/')
                                                    // Replace /downloads prefix with WebDAV symlink folder name "Downloads"
                                                    val webdavRel = if (linuxPath.startsWith("/downloads", ignoreCase = true)) {
                                                        "Downloads" + linuxPath.substring("/downloads".length)
                                                    } else {
                                                        // Generic: strip leading slash and hope it matches WebDAV path
                                                        linuxPath.trimStart('/')
                                                    }
                                                    onOpenFolder("$base$webdavRel/")
                                                } else {
                                                    onGlobalSearch(torrent.name)
                                                }
                                            },
                                            onLongPress = { showCompletedMenu = true }
                                        )}
                                        .padding(vertical = AppSpacing.SM),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(AppSpacing.SM))
                                    Text(torrent.name, style = AppTypography.BodyLarge.copy(color = TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                                DropdownMenu(expanded = showCompletedMenu, onDismissRequest = { showCompletedMenu = false }) {
                                    DropdownMenuItem(text = { Text("Xóa khỏi danh sách", color = AccentRed) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = AccentRed) }, onClick = { showCompletedMenu = false; viewModel.controlTorrent("delete", torrent.hash) })
                                }
                            }
                            } // SwipeDeleteRow content
                            } // key
                        }
                    }
                }
            }
        }
}

@Composable
internal fun QuickAccessSection(
    slot2Id: String,
    slot3Id: String,
    slot4Id: String,
    editingSlot: Int?,
    sharedPrefs: android.content.SharedPreferences,
    onEditingSlotChange: (Int?) -> Unit,
    onSlot2Change: (String) -> Unit,
    onSlot3Change: (String) -> Unit,
    onSlot4Change: (String) -> Unit,
    onQuickAction: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenLatestPhotos: () -> Unit,
    onOpenRecentVideos: () -> Unit,
    onOpenToolbox: () -> Unit,
) {
        // Đã DANH MỤC TRUY CẬP NHANH Đã 
        Text("TRUY CẬP NHANH", fontSize = PanelTitleSize, fontWeight = FontWeight.Black, color = PanelTitlePurple, letterSpacing = PanelTitleLetterSpacing, modifier = Modifier.padding(bottom = AppSpacing.SM - AppSpacing.XS))

        // Đã CHỨC NĂNG CHÍNH (Lưới 2x2) Đã 
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigMenuTile("Quản lý Tệp", "Duyệt & quản lý tệp", Icons.Default.Folder, listOf(AccentOrange, AccentOrange.copy(alpha = 0.8f)), Modifier.weight(1f), onClick = onOpenFiles)
            val s2 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot2Id } ?: AVAILABLE_QUICK_ACTIONS[0]
            BigMenuTile(s2.title, s2.subtitle, s2.icon, s2.gradientColors, Modifier.weight(1f), onClick = { onQuickAction(s2.id) }, onLongClick = { onEditingSlotChange(2) })
        }
        Spacer(Modifier.height(AppSpacing.SM))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val s3 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot3Id } ?: AVAILABLE_QUICK_ACTIONS[1]
            BigMenuTile(s3.title, s3.subtitle, s3.icon, s3.gradientColors, Modifier.weight(1f), onClick = { onQuickAction(s3.id) }, onLongClick = { onEditingSlotChange(3) })
            val s4 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot4Id } ?: AVAILABLE_QUICK_ACTIONS[2]
            BigMenuTile(s4.title, s4.subtitle, s4.icon, s4.gradientColors, Modifier.weight(1f), onClick = { onQuickAction(s4.id) }, onLongClick = { onEditingSlotChange(4) })
        }
        Spacer(Modifier.height(AppSpacing.SM))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigMenuTile("Ảnh gần đây", "Mở ảnh mới nhất", Icons.Default.PhotoLibrary, listOf(AccentPurple, AccentCyan), Modifier.weight(1f), onClick = onOpenLatestPhotos)
            BigMenuTile("Video gần đây", "Mở video mới nhất", Icons.Default.VideoLibrary, listOf(AccentPink, AccentOrange), Modifier.weight(1f), onClick = onOpenRecentVideos)
        }

        if (editingSlot != null) {
            MainMenuSectionQuickActionSelectorDialog(
                currentSlots = setOf(slot2Id, slot3Id, slot4Id),
                onDismiss = { onEditingSlotChange(null) },
                onSelect = { newId ->
                    var nextSlot2 = slot2Id
                    var nextSlot3 = slot3Id
                    var nextSlot4 = slot4Id
                    when (editingSlot) {
                        2 -> {
                            if (nextSlot3 == newId) nextSlot3 = nextSlot2
                            if (nextSlot4 == newId) nextSlot4 = nextSlot2
                            nextSlot2 = newId
                        }
                        3 -> {
                            if (nextSlot2 == newId) nextSlot2 = nextSlot3
                            if (nextSlot4 == newId) nextSlot4 = nextSlot3
                            nextSlot3 = newId
                        }
                        4 -> {
                            if (nextSlot2 == newId) nextSlot2 = nextSlot4
                            if (nextSlot3 == newId) nextSlot3 = nextSlot4
                            nextSlot4 = newId
                        }
                    }
                    sharedPrefs.edit()
                        .putString("qa_slot2", nextSlot2)
                        .putString("qa_slot3", nextSlot3)
                        .putString("qa_slot4", nextSlot4)
                        .apply()
                    onSlot2Change(nextSlot2)
                    onSlot3Change(nextSlot3)
                    onSlot4Change(nextSlot4)
                    onEditingSlotChange(null)
                }
            )
        }

        Spacer(Modifier.height(AppSpacing.SM + AppSpacing.XS))
        
        // Nút mở Toolbox mở rộng
        Button(
            onClick = { onOpenToolbox() },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkCard),
            shape = AppShapes.Card
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BuildCircle, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(AppSpacing.SM))
                Text("Công cụ & Cài đặt", style = AppTypography.TitleMedium.copy(color = TextPrimary))
            }
        }
}
