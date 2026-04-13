package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
import com.nas.naswebdav.ui.dialogs.*

import android.content.Context

import kotlinx.coroutines.launch

import androidx.compose.runtime.collectAsState

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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

// ============ BẢNG MÀU CHUYÊN NGHIỆP ============
private val DarkSurface = Color.Black
private val DarkCard = Color(0xFF0F0F0F)
private val AccentBlue = Color(0xFF1976D2)
private val AccentCyan = Color(0xFF00D2FF)
private val AccentGreen = Color(0xFF00E676)
private val AccentOrange = Color(0xFFFF9100)
private val AccentRed = Color(0xFFFF1744)
private val AccentPurple = Color(0xFFBB86FC)
private val AccentPink = Color(0xFFFF6EC7)
private val TextPrimary = Color(0xFFE8E8E8)
private val TextSecondary = Color(0xFF8892B0)

// ============ FAN SPEED ANIMATED ICON ============
@Composable
fun FanSpeedIcon(percent: Int, color: Color, modifier: Modifier = Modifier) {
    val level = when {
        percent <= 0  -> 0
        percent <= 25 -> 1
        percent <= 65 -> 2
        else          -> 3
    }
    val isRunning = level > 0
    val durationMs = when (level) { 1 -> 1500; 2 -> 600; 3 -> 250; else -> 9999 }
    
    val infiniteTransition = rememberInfiniteTransition(label = "fan")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "fan_angle"
    )
    val currentAngle = if (isRunning) angle else 0f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val R = minOf(cx, cy)
            
            // Outer casing ring
            drawCircle(
                color = color.copy(alpha = 0.15f),
                radius = R,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = R * 0.08f)
            )
            
            // Glowing sweep background indicator
            if (isRunning) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(Color.Transparent, color.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(cx, cy)
                    ),
                    radius = R * 0.9f
                )
            }

            withTransform({ rotate(currentAngle, Offset(cx, cy)) }) {
                // Draw 5 modern blades
                val bladeCount = 5
                for (i in 0 until bladeCount) {
                    withTransform({ rotate((360f / bladeCount) * i, Offset(cx, cy)) }) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(cx, cy)
                            // Right side curve
                            quadraticBezierTo(cx + R * 0.6f, cy - R * 0.2f, cx + R * 0.2f, cy - R * 0.85f)
                            // Top flat curve
                            quadraticBezierTo(cx, cy - R * 0.95f, cx - R * 0.2f, cy - R * 0.85f)
                            // Left side inward curve
                            quadraticBezierTo(cx - R * 0.3f, cy - R * 0.3f, cx, cy)
                            close()
                        }
                        
                        drawPath(
                            path = path,
                            brush = Brush.radialGradient(
                                colors = listOf(color, color.copy(alpha = 0.4f)),
                                center = Offset(cx, cy - R * 0.5f),
                                radius = R * 0.8f
                            )
                        )
                    }
                }
            }
            
            // Center Hub - Metallic Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color(0xFFB0BEC5), Color(0xFF455A64)),
                    center = Offset(cx - R * 0.08f, cy - R * 0.08f),
                    radius = R * 0.35f
                ),
                radius = R * 0.22f,
                center = Offset(cx, cy)
            )
            // Hub Core
            drawCircle(
                color = Color(0xFF263238),
                radius = R * 0.08f,
                center = Offset(cx, cy)
            )
        }
    }
}

// --- MAIN MENU / DASHBOARD ---
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainMenuScreen(
    viewModel: WebDavViewModel,
    onOpenFiles: () -> Unit,
    onOpenFolder: (webdavPath: String) -> Unit,
    onGlobalSearch: (String) -> Unit,
    onOpenLatestPhotos: () -> Unit,
    onOpenRecentVideos: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPerformance: () -> Unit,
    onLogout: () -> Unit,
    // ── TÍNH NĂNG MỚI ──────────────────────────────────────────────────────────
    onOpenOrganizer: () -> Unit = {},
    onOpenGuestPass: () -> Unit = {},
    onOpenSocialExtractor: () -> Unit = {}
) {
    val mContext = LocalContext.current
    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)

    // STATE CHO POPUP TẢI TỪ XA
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadLink by remember { mutableStateOf("") }

    // STATE CHO WAKE-ON-LAN
    var showWolDialog by remember { mutableStateOf(false) }
    var macAddress by remember { mutableStateOf(sharedPrefs.getString("mac_address", "") ?: "") }

    // STATE CHO DIALOG THÔNG BÁO
    var commonDialogMessage by remember { mutableStateOf("") }
    var commonDialogType by remember { mutableStateOf(DialogType.SUCCESS) }

    // STATE CHO DANH MỤC TRUY CẬP NHANH ĐỘNG
    var slot2Id by remember { mutableStateOf(sharedPrefs.getString("qa_slot2", "sync") ?: "sync") }
    var slot3Id by remember { mutableStateOf(sharedPrefs.getString("qa_slot3", "stream") ?: "stream") }
    var slot4Id by remember { mutableStateOf(sharedPrefs.getString("qa_slot4", "trash") ?: "trash") }
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var showCommonDialog by remember { mutableStateOf(false) }

    // STATE CHO XÁC NHẬN NGUỒN VÀ TOOLBOX
    var showPowerMenu by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var showShutdownConfirm by remember { mutableStateOf(false) }
    var showToolboxDialog by remember { mutableStateOf(false) }

    // STATE CHO AUTO-BACKUP
    var showAutoBackupDialog by remember { mutableStateOf(false) }
    var isAutoBackupEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_backup", false)) }
    var deleteAfterBackup by remember { mutableStateOf(sharedPrefs.getBoolean("delete_after_backup", false)) }

    // STATE CHO LAN WHITELIST
    var showLanWhitelistDialog by remember { mutableStateOf(false) }

    // STATE CHO LIVESTREAM RECORD
    var showLivestreamDialog by remember { mutableStateOf(false) }
    // ── SMART SWITCH: Tự động kiểm tra và chuyển mạng khi vào màn hình ──────
    LaunchedEffect(Unit) {
        viewModel.checkSmartNetwork(mContext)
        viewModel.fetchSmartData()
        viewModel.fetchOmvOverview()
    }

    // --- DIALOGS (từ ui/dialogs/Dialogs.kt) ---
    if (showRebootConfirm) {
        RebootConfirmDialog(
            onConfirm = {
                viewModel.sendCommandToNas("power/reboot")
                commonDialogType = DialogType.WARNING; commonDialogMessage = "Đã gửi lệnh khởi động lại NAS!"; showCommonDialog = true
                showRebootConfirm = false
            },
            onDismiss = { showRebootConfirm = false }
        )
    }
    if (showShutdownConfirm) {
        ShutdownConfirmDialog(
            onConfirm = {
                viewModel.sendCommandToNas("power/shutdown")
                commonDialogType = DialogType.WARNING; commonDialogMessage = "Đã gửi lệnh tắt nguồn NAS!"; showCommonDialog = true
                showShutdownConfirm = false
            },
            onDismiss = { showShutdownConfirm = false }
        )
    }
    if (showDownloadDialog) {
        DownloadDialog(
            downloadLink = downloadLink,
            onLinkChange = { downloadLink = it },
            onConfirm = {
                if (downloadLink.isNotBlank()) {
                    viewModel.sendDownloadLink(downloadLink)
                    showDownloadDialog = false
                    downloadLink = ""
                }
            },
            onDismiss = { showDownloadDialog = false }
        )
    }
    if (showWolDialog) {
        WolDialog(
            macAddress = macAddress,
            onMacChange = { macAddress = it },
            onConfirm = {
                if (macAddress.isNotBlank()) {
                    sharedPrefs.edit().putString("mac_address", macAddress).apply()
                    viewModel.sendWakeOnLan(macAddress)
                    showWolDialog = false
                    commonDialogType = DialogType.SUCCESS
                    commonDialogMessage = "Đã bắn tín hiệu Wake-on-LAN!"
                    showCommonDialog = true
                }
            },
            onDismiss = { showWolDialog = false }
        )
    }
    if (viewModel.showSmartDialog) {
        SmartDiskDialog(viewModel = viewModel, onDismiss = { viewModel.showSmartDialog = false })
    }
    if (showAutoBackupDialog) {
        AutoBackupDialog(
            context = mContext,
            isAutoBackupEnabled = isAutoBackupEnabled,
            onAutoBackupEnabledChange = { isAutoBackupEnabled = it },
            deleteAfterBackup = deleteAfterBackup,
            onDeleteAfterBackupChange = { deleteAfterBackup = it },
            onSaveAndSchedule = {
                sharedPrefs.edit()
                    .putBoolean("auto_backup", isAutoBackupEnabled)
                    .putBoolean("delete_after_backup", deleteAfterBackup)
                    .apply()
                if (isAutoBackupEnabled) {
                    val constraints = androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                    val backupWorkRequest = androidx.work.PeriodicWorkRequestBuilder<AutoBackupWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .build()
                    androidx.work.WorkManager.getInstance(mContext).enqueueUniquePeriodicWork(
                        "AutoBackupWork",
                        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                        backupWorkRequest
                    )
                    commonDialogType = DialogType.SUCCESS
                    commonDialogMessage = "Đã lưu cấu hình Auto-Backup!"
                    showCommonDialog = true
                } else {
                    androidx.work.WorkManager.getInstance(mContext).cancelUniqueWork("AutoBackupWork")
                }
                showAutoBackupDialog = false
            },
            onTriggerManualSync = {
                viewModel.triggerManualBackup(mContext)
                showAutoBackupDialog = false
            },
            onDismiss = { showAutoBackupDialog = false }
        )
    }
    if (viewModel.showLogDialog) {
        SystemLogDialog(viewModel = viewModel, onDismiss = { viewModel.showLogDialog = false })
    }
    if (viewModel.showDockerDialog) {
        DockerDialog(viewModel = viewModel, onDismiss = { viewModel.showDockerDialog = false })
    }
    if (showLanWhitelistDialog) {
        LanWhitelistDialog(
            viewModel = viewModel,
            onDismiss = { showLanWhitelistDialog = false }
        )
    }
    if (showLivestreamDialog) {
        LivestreamRecordDialog(
            viewModel = viewModel,
            onDismiss = { showLivestreamDialog = false }
        )
    }

    // ============ TRẠNG THÁI SCROLL ============
    val scrollState = rememberScrollState()


    // ============ GIAO DIỆN DASHBOARD CHUYÊN NGHIỆP ============
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    
    val handleQuickAction = { id: String ->
        when (id) {
            "sync" -> showAutoBackupDialog = true
            "stream" -> showLivestreamDialog = true
            "trash" -> onOpenTrash()
            "organizer" -> onOpenOrganizer()
            "guest" -> onOpenGuestPass()
            "log" -> { viewModel.loadSystemLogs(); viewModel.showLogDialog = true }
        }
    }

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.checkSmartNetwork(mContext)
            viewModel.fetchSmartData()
            kotlinx.coroutines.delay(1000)
            pullRefreshState.endRefresh()
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullRefreshState.nestedScrollConnection)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkSurface)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
        Spacer(Modifier.height(48.dp))

        // ═══ HEADER ═══
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Đèn tín hiệu trạng thái (Pulse animation)
            val currentStatus = viewModel.systemStatus.status
            val statusColor = when {
                currentStatus.contains("Online", true) || currentStatus.contains("Đã kết nối", true) -> AccentGreen
                currentStatus.contains("Chờ", true) -> AccentOrange
                else -> AccentRed
            }
            Column {
                Text("NAS Dashboard", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chainedbox L1 Pro", fontSize = 12.sp, color = TextSecondary)
                    Text("  \u2022  ", fontSize = 12.sp, color = TextSecondary)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(4.dp))
                    Text(currentStatus, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                
                // ── SMART SWITCH BADGE ──
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (viewModel.isOnLan) Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFF29B6F6).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (viewModel.isOnLan) Icons.Default.NetworkWifi else Icons.Default.Language,
                            contentDescription = null,
                            tint = if (viewModel.isOnLan) Color(0xFF00E676) else Color(0xFF29B6F6),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (viewModel.isOnLan) "LAN" else "Tailscale",
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = if (viewModel.isOnLan) Color(0xFF00E676) else Color(0xFF29B6F6)
                        )
                    }
                    val ut = viewModel.systemStatus.uptime
                    if (ut.isNotBlank() && ut != "--") {
                        val cleanUt = ut.replace(Regex(",\\s*\\d+\\s*giây"), "")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Schedule, null, tint = AccentCyan, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(cleanUt, fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(DarkCard).clickable { showPowerMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Nguồn", tint = AccentRed, modifier = Modifier.size(16.dp))
                    
                    DropdownMenu(
                        expanded = showPowerMenu,
                        onDismissRequest = { showPowerMenu = false },
                        modifier = Modifier.background(DarkCard)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Đăng xuất", color = AccentOrange) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = AccentOrange) },
                            onClick = { showPowerMenu = false; onLogout() }
                        )
                        DropdownMenuItem(
                            text = { Text("Khởi động lại NAS", color = AccentGreen) },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null, tint = AccentGreen) },
                            onClick = { showPowerMenu = false; showRebootConfirm = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Tắt nguồn NAS", color = AccentRed) },
                            leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null, tint = AccentRed) },
                            onClick = { showPowerMenu = false; showShutdownConfirm = true }
                        )
                    }
                }
            }
        }
            
            Spacer(Modifier.height(12.dp))

            // Đã THẾ HỆ THỐNG: CPU + RAM + Stats Đã
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("HỆ THỐNG", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeCard(
                        title = "CPU", value = viewModel.systemStatus.cpu,
                        subValue = viewModel.systemStatus.cpuTemp,
                        icon = Icons.Default.Memory,
                        gradientColors = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
                        modifier = Modifier.weight(1f)
                    )
                    GaugeCard(
                        title = "RAM", value = viewModel.systemStatus.ram, subValue = "${viewModel.systemStatus.ramPercent}%",
                        icon = Icons.Default.DeveloperBoard,
                        gradientColors = listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
                        modifier = Modifier.weight(1f),
                        overridePercent = viewModel.systemStatus.ramPercent.replace("%", "").trim().toFloatOrNull()
                    )
                    
                    val hddDisk = viewModel.systemStatus.diskParts.find { it.mount != "/" }
                    if (hddDisk != null) {
                        val fmtTotal = hddDisk.total.let {
                            val n = it.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                            if (it.contains("GB", true) && n >= 1000f) "%.1f TB".format(java.util.Locale.US, n / 1024f) else it
                        }
                        GaugeCard(
                            title = "HDD", value = "${hddDisk.used} / $fmtTotal",
                            subValue = "${hddDisk.percent}%",
                            icon = Icons.Default.Storage,
                            gradientColors = listOf(Color(0xFFFFA726), Color(0xFFF57C00)),
                            modifier = Modifier.weight(1f),
                            overridePercent = hddDisk.percent
                        )
                    } else Spacer(Modifier.weight(1f))
                    
                    val smartStatusText = viewModel.smartInfo.status.uppercase().trim()
                    
                    val isSmartOk = smartStatusText.contains("PASSED") || smartStatusText == "OK"
                    val isSmartFailed = smartStatusText.contains("FAILED")
                    val isSmartEmmc = smartStatusText.contains("EMMC")
                    
                    val smartColors = when {
                        isSmartOk -> listOf(Color(0xFF00E676), Color(0xFF1DE9B6))
                        isSmartEmmc -> listOf(Color(0xFF42A5F5), Color(0xFF1E88E5)) // Nhận diện eMMC màu Xanh Dương
                        isSmartFailed -> listOf(Color(0xFFFF1744), Color(0xFFFF5252)) // FAILED hiển thị màu Đỏ
                        else -> listOf(Color(0xFF9E9E9E), Color(0xFFBDBDBD)) // Màu xám cho UNKNOWN, ĐANG TẢI, LỖI...
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
                        subValue = viewModel.smartInfo.temperature.replace("°C", "°").replace("--", ""),
                        icon = Icons.Default.HealthAndSafety,
                        gradientColors = smartColors,
                        modifier = Modifier.weight(1f),
                        overridePercent = smartPercent
                    )
                }
            }
        }

        // ═══ OMV SERVICES & HARDWARE (Expandable Panel) ═══
        if (viewModel.omvOverview.services.isNotEmpty() || viewModel.omvOverview.disks.isNotEmpty()) {
            var omvExpanded by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    // Header — nhấn để mở/đóng
                    Row(
                        Modifier.fillMaxWidth().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { omvExpanded = !omvExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dashboard, null, tint = Color(0xFF42A5F5), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OMV", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFF42A5F5), letterSpacing = 1.5.sp)
                            if (viewModel.omvOverview.omvVersion.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(viewModel.omvOverview.omvVersion, fontSize = 10.sp, color = TextSecondary)
                            }
                            val ping = viewModel.networkPingMs
                            if (ping != null) {
                                Spacer(Modifier.width(8.dp))
                                val pingColor = if (ping < 50) Color(0xFF00E676) else if (ping < 150) Color(0xFFFFA726) else Color(0xFFEF5350)
                                Text("${ping}ms", fontSize = 10.sp, color = pingColor, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Tải xuống / Tải lên inline ngay header
                            Text("↓ ${viewModel.systemStatus.netRx}", fontSize = 9.sp, color = Color(0xFF42A5F5), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("↑ ${viewModel.systemStatus.netTx}", fontSize = 9.sp, color = Color(0xFFAB47BC), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
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
                            Spacer(Modifier.height(8.dp))

                            // Services Row
                            if (viewModel.omvOverview.services.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    viewModel.omvOverview.services.forEach { svc ->
                                        val svcColor = if (svc.running) Color(0xFF00E676) else if (svc.enabled) Color(0xFFFFA726) else TextSecondary.copy(alpha = 0.4f)
                                        val svcIcon = when (svc.name) {
                                            "ssh" -> Icons.Default.Terminal
                                            "ftp" -> Icons.Default.CloudUpload
                                            "samba" -> Icons.Default.FolderShared
                                            "nfs" -> Icons.Default.Storage
                                            else -> Icons.Default.SettingsEthernet
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Icon(svcIcon, null, tint = svcColor, modifier = Modifier.size(18.dp))
                                            Text(svc.title, fontSize = 9.sp, color = svcColor, maxLines = 1, fontWeight = FontWeight.Bold)
                                            Text(if (svc.running) "ON" else "OFF", fontSize = 9.sp, color = svcColor.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            // Network + Hardware info
                            val net = viewModel.omvOverview.network.firstOrNull()
                            val hdd = viewModel.omvOverview.disks.find { !it.isRoot }
                            if (net != null || hdd != null) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    if (net != null) {
                                        Column {
                                            Text("${net.name} • ${net.speed}Mbps", fontSize = 10.sp, color = TextSecondary, letterSpacing = 0.5.sp)
                                            Text("${net.address} | GW: ${net.gateway}", fontSize = 10.sp, color = Color(0xFF81D4FA))
                                            Text("MAC: ${net.mac}", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.6f))
                                        }
                                    }
                                    if (hdd != null) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(hdd.model, fontSize = 10.sp, color = TextSecondary, maxLines = 1)
                                            Text("SN: ${hdd.serial}", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.6f))
                                            val sizeGb = (hdd.size.toLongOrNull() ?: 0L) / (1024L * 1024 * 1024)
                                            val sizeTb = if (sizeGb >= 1024) "%.1f TB".format(sizeGb / 1024f) else "$sizeGb GB"
                                            Text(sizeTb, fontSize = 10.sp, color = Color(0xFFFFA726), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            
                            // Fan Control
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth().background(Color(0xFF191919), RoundedCornerShape(6.dp)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val isFanRunning = viewModel.systemStatus.fanStatus != "Dừng" && viewModel.systemStatus.fanStatus != "--"
                                    FanSpeedIcon(percent = if (isFanRunning) 100 else 0, color = if (isFanRunning) Color(0xFF00E676) else TextSecondary, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Quạt tản nhiệt", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                        Text(viewModel.systemStatus.fanStatus, fontSize = 9.sp, color = if (isFanRunning) Color(0xFF00E676) else TextSecondary)
                                    }
                                }
                                // Mute / Auto / Max Toggle
                                var showFanSettings by remember { mutableStateOf(false) }
                                Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                                    val modes = listOf("auto" to "Tự động", "custom" to "Tùy chỉnh", "on" to "Bật", "off" to "Tắt")
                                    val currentMode = viewModel.systemStatus.fanMode
                                    modes.forEach { (m, label) ->
                                        val active = m == currentMode
                                        Box(
                                            Modifier.clickable {
                                                if (m == "custom") showFanSettings = true else viewModel.setFanMode(m)
                                            }
                                                .background(if (active) if (m == "off") Color(0xFFEF5350) else Color(0xFF00E676) else Color.Transparent)
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(label, fontSize = 9.sp, color = if (active) Color.Black else TextSecondary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                
                                if (showFanSettings) {
                                    var onTemp by remember { mutableStateOf(viewModel.systemStatus.fanOnTemp.toInt().toString()) }
                                    var offTemp by remember { mutableStateOf(viewModel.systemStatus.fanOffTemp.toInt().toString()) }
                                    androidx.compose.material3.AlertDialog(
                                        onDismissRequest = { showFanSettings = false },
                                        title = { Text("Độ trễ nhiệt (Hysteresis)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
                                        text = { 
                                            Column {
                                                Text("Hệ thống sẽ chạy ngầm để bật quạt khi tới 'Nhiệt độ bật', và tắt quạt khi hạ xuống 'Nhiệt độ tắt'.", fontSize = 12.sp, color = TextSecondary)
                                                Spacer(Modifier.height(12.dp))
                                                OutlinedTextField(value = onTemp, onValueChange = { onTemp = it }, label = { Text("Nhiệt độ Bật (°C)") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                                Spacer(Modifier.height(8.dp))
                                                OutlinedTextField(value = offTemp, onValueChange = { offTemp = it }, label = { Text("Nhiệt độ Tắt (°C)") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                            }
                                        },
                                        confirmButton = {
                                            Button(onClick = { 
                                                viewModel.setFanMode("custom", onTemp.toFloatOrNull() ?: 65f, offTemp.toFloatOrNull() ?: 55f)
                                                showFanSettings = false 
                                            }) { Text("Lưu & Áp dụng") }
                                        },
                                        dismissButton = {
                                            androidx.compose.material3.TextButton(onClick = { showFanSettings = false }) { Text("Hủy", color = TextSecondary) }
                                        },
                                        containerColor = Color(0xFF1E1E1E),
                                        textContentColor = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // --- CHÈN BIỂU ĐỒ GIÁM SÁT VÀ BÁO CÁO Ở ĐÂY ---
        Spacer(Modifier.height(8.dp))
        com.nas.naswebdav.ui.screens.MonitoringChartCard(viewModel)
        Spacer(Modifier.height(4.dp))
        
        SystemStatusCards(viewModel, mContext)
        SystemLogsSummaryCard(viewModel)
        // Đã TORRENT ĐANG TẢI & HOÀN THÀNH Đã 
        if (viewModel.systemStatus.torrents.isNotEmpty()) {
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
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (downloadingTorrents.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDownload, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Đang tải xuống (${downloadingTorrents.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Spacer(Modifier.height(4.dp))
                        downloadingTorrents.take(5).forEach { torrent ->
                            var showTorrentMenu by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .pointerInput(torrent.hash) { detectTapGestures(onLongPress = { showTorrentMenu = true }) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(torrent.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(torrent.speed, fontSize = 11.sp, color = AccentCyan, modifier = Modifier.padding(start = 8.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { torrent.progress },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                            color = AccentGreen, trackColor = TextSecondary.copy(alpha = 0.2f)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
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
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.HorizontalDivider(color = TextSecondary.copy(alpha = 0.1f), thickness = 0.7.dp)
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF42A5F5), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Đã hoàn thành (${completedTorrents.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Spacer(Modifier.height(4.dp))
                        completedTorrents.take(5).forEach { torrent ->
                            var showCompletedMenu by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxWidth()) {
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
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFFCA28), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(torrent.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = { viewModel.controlTorrent("delete", torrent.hash) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, null, tint = TextSecondary.copy(alpha=0.6f), modifier = Modifier.size(14.dp))
                                    }
                                }
                                DropdownMenu(expanded = showCompletedMenu, onDismissRequest = { showCompletedMenu = false }) {
                                    DropdownMenuItem(text = { Text("Xóa khỏi danh sách", color = AccentRed) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = AccentRed) }, onClick = { showCompletedMenu = false; viewModel.controlTorrent("delete", torrent.hash) })
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }



        // Đã DANH MỤC TRUY CẬP NHANH Đã 
        Text("Truy cập nhanh", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 6.dp))

        // Đã CHỨC NĂNG CHÍNH (Lưới 2x2) Đã 
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigMenuTile("Quản lý Tệp", "Duyệt & quản lý tệp", Icons.Default.Folder, listOf(Color(0xFFFFCA28), Color(0xFFFF8F00)), Modifier.weight(1f), onClick = onOpenFiles)
            val s2 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot2Id } ?: AVAILABLE_QUICK_ACTIONS[0]
            BigMenuTile(s2.title, s2.subtitle, s2.icon, s2.gradientColors, Modifier.weight(1f), onClick = { handleQuickAction(s2.id) }, onLongClick = { editingSlot = 2 })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val s3 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot3Id } ?: AVAILABLE_QUICK_ACTIONS[1]
            BigMenuTile(s3.title, s3.subtitle, s3.icon, s3.gradientColors, Modifier.weight(1f), onClick = { handleQuickAction(s3.id) }, onLongClick = { editingSlot = 3 })
            val s4 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot4Id } ?: AVAILABLE_QUICK_ACTIONS[2]
            BigMenuTile(s4.title, s4.subtitle, s4.icon, s4.gradientColors, Modifier.weight(1f), onClick = { handleQuickAction(s4.id) }, onLongClick = { editingSlot = 4 })
        }

        if (editingSlot != null) {
            QuickActionSelectorDialog(
                currentSlots = setOf(slot2Id, slot3Id, slot4Id),
                onDismiss = { editingSlot = null },
                onSelect = { newId ->
                    val edit = sharedPrefs.edit()
                    when (editingSlot) {
                        2 -> {
                            if (slot3Id == newId) { slot3Id = slot2Id; edit.putString("qa_slot3", slot3Id) }
                            if (slot4Id == newId) { slot4Id = slot2Id; edit.putString("qa_slot4", slot4Id) }
                            slot2Id = newId; edit.putString("qa_slot2", slot2Id)
                        }
                        3 -> {
                            if (slot2Id == newId) { slot2Id = slot3Id; edit.putString("qa_slot2", slot2Id) }
                            if (slot4Id == newId) { slot4Id = slot3Id; edit.putString("qa_slot4", slot4Id) }
                            slot3Id = newId; edit.putString("qa_slot3", slot3Id)
                        }
                        4 -> {
                            if (slot2Id == newId) { slot2Id = slot4Id; edit.putString("qa_slot2", slot2Id) }
                            if (slot3Id == newId) { slot3Id = slot4Id; edit.putString("qa_slot3", slot3Id) }
                            slot4Id = newId; edit.putString("qa_slot4", slot4Id)
                        }
                    }
                    edit.apply()
                    editingSlot = null
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        
        // Nút mở Toolbox mở rộng
        Button(
            onClick = { showToolboxDialog = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkCard),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BuildCircle, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Công cụ & Cài đặt", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(8.dp))

        // THÔNG BÁO DIALOG
        if (showCommonDialog) {
            AppStatusDialog(
                type = commonDialogType,
                message = commonDialogMessage,
                onDismiss = { showCommonDialog = false }
            )
        }
        // DIALOG THÔNG BÁO TỪ VIEWMODEL
        if (viewModel.showCommonDialog) {
            AppStatusDialog(
                type = viewModel.commonDialogType,
                message = viewModel.commonDialogMessage,
                onDismiss = { viewModel.showCommonDialog = false }
            )
        }

        Spacer(Modifier.height(32.dp))
    }
    
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshContainer(
        state = pullRefreshState,
        modifier = Modifier.align(Alignment.TopCenter),
        containerColor = DarkCard,
        contentColor = AccentCyan
    )
    }

    // Đã HỘP CÔNG CỤ TOOLBOX Đã 
    if (showToolboxDialog) {
        ToolboxDialog(
            viewModel = viewModel,
            sharedPrefs = sharedPrefs,
            context = mContext,
            onDismiss = { showToolboxDialog = false },
            onOpenLatestPhotos = onOpenLatestPhotos,
            onOpenRecentVideos = onOpenRecentVideos,
            onOpenTrash = onOpenTrash,
            showAutoBackupDialog = { showAutoBackupDialog = true },
            showLanWhitelistDialog = { showLanWhitelistDialog = true },
            showLivestreamDialog = { showLivestreamDialog = true }
        )
    }
}


// ============ COMPONENT: Inline stat row (emoji + label + value) ============
@Composable
fun InlineStatRow(emoji: String, label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 13.sp)
        Spacer(Modifier.width(5.dp))
        Column {
            Text(label, fontSize = 8.sp, color = TextSecondary, letterSpacing = 0.8.sp)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
        }
    }
}

// ============ COMPONENT: Thẻ đo lớn (CPU / RAM) với gradient ============
@Composable
fun GaugeCard(
    title: String,
    value: String,
    subValue: String? = null,
    icon: ImageVector,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    overridePercent: Float? = null,
    label: String? = null
) {
    val numericValue = overridePercent ?: (Regex("[^0-9.]").replace(value, "").toFloatOrNull() ?: 0f)
    val progress = (numericValue / 100f).coerceIn(0f, 1f)
    
    val accentColor = if (title == "S.M.A.R.T") {
        gradientColors.first()
    } else {
        when {
            progress >= 0.90f -> Color(0xFFEF5350) // Gần đầy / Quá tải -> Đỏ
            progress >= 0.70f -> Color(0xFFFFA726) // Cảnh báo -> Vàng
            else -> Color(0xFF66BB6A) // Bình thường -> Xanh
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(64.dp),
                        color = accentColor,
                        trackColor = TextSecondary.copy(alpha = 0.15f),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy((-3).dp)
                    ) {
                        Icon(icon, null, tint = accentColor, modifier = Modifier.size(16.dp).offset(y = 2.dp))
                        Text(
                            text = title,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor.copy(alpha = 0.85f),
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.offset(y = 1.dp)
                        )
                        if (!subValue.isNullOrBlank() && subValue != "--\u00b0C" && subValue != "--°C") {
                            Text(
                                text = subValue,
                                fontSize = 8.sp,
                                color = if (subValue.contains("°C") || subValue.contains("\u00b0C") || subValue.contains("°")) {
                                    val tempVal = Regex("[^0-9.]").replace(subValue, "").toFloatOrNull() ?: 0f
                                    val isDisk = title == "S.M.A.R.T" || title == "HDD"
                                    when {
                                        isDisk && tempVal >= 55f -> Color(0xFFEF5350)
                                        isDisk && tempVal >= 45f -> Color(0xFFFFA726)
                                        !isDisk && tempVal >= 80f -> Color(0xFFEF5350)
                                        !isDisk && tempVal >= 60f -> Color(0xFFFFA726) // CPU 60+ is Yellow
                                        else -> Color(0xFF66BB6A)
                                    }
                                } else accentColor.copy(alpha = 0.9f),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.offset(y = (-1).dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    val textSize = if (value.length > 7) 8.5.sp else 11.sp
                    Text(value, fontSize = textSize, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ============ COMPONENT: Thẻ thống kê nhỏ? ============
@Composable
fun MiniStatCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, fontSize = 9.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

// ============ COMPONENT: Thanh phân vùng ổ đĩa ============
@Composable
fun DiskPartitionBar(mount: String, percent: Float, total: String, used: String) {
    val barColor = when {
        percent >= 90f -> AccentRed
        percent >= 75f -> AccentOrange
        else -> AccentGreen
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(mount, fontSize = 12.sp, color = TextPrimary)
            Text("$used / $total", fontSize = 11.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = barColor,
            trackColor = TextSecondary.copy(alpha = 0.15f)
        )
    }
}

// ============ COMPONENT: Quick Action Chip ============
@Composable
fun QuickActionChip(label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ============ COMPONENT: Thẻ menu lớn (gradient) ============
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BigMenuTile(title: String, subtitle: String, icon: ImageVector, gradientColors: List<Color>, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { if (onLongClick != null) onLongClick() else onClick() }
                )
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradientColors))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Icon(icon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(6.dp))
                Column {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(subtitle, fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// ============ COMPONENT: Settings Menu Card ============
@Composable
fun SettingsMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(subtitle, fontSize = 11.sp, color = TextSecondary)
            }
            if (checked != null) {
                Switch(
                    checked = checked,
                    onCheckedChange = { onClick() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = color,
                        checkedTrackColor = color.copy(alpha = 0.4f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = TextSecondary.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f }
                )
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ============ HÀM TIỆN ÍCH (Giữ lại tương thích) ============
fun getStatusColor(title: String, value: String, rawPercent: String = ""): Color {
    try {
        val extractNumber = { str: String -> Regex("[^0-9.]").replace(str, "").toFloatOrNull() ?: 0f }
        return when (title) {
            "Nhiệt độ" -> {
                val t = extractNumber(value)
                when { t >= 75f -> AccentRed; t >= 60f -> AccentOrange; t > 0f -> AccentGreen; else -> Color.Gray }
            }
            "CPU", "Ổ đĩa" -> {
                val p = extractNumber(value)
                when { p >= 90f -> AccentRed; p >= 75f -> AccentOrange; p > 0f -> AccentGreen; else -> Color.Gray }
            }
            "RAM" -> {
                val p = if (rawPercent.isNotBlank()) extractNumber(rawPercent) else extractNumber(value)
                when { p >= 90f -> AccentRed; p >= 75f -> AccentOrange; p > 0f -> AccentGreen; else -> Color.Gray }
            }
            else -> Color.Gray
        }
    } catch (e: Exception) { return Color.Gray }
}

// Giữ lại MenuCard tương thích cho các file khác nếu cần
@Composable
fun MenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    SettingsMenuCard(title = title, subtitle = subtitle, icon = icon, color = color, checked = checked, onClick = onClick)
}

@Composable
fun SystemStatusItem(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    MiniStatCard(title = title, value = value, icon = icon, color = color, modifier = modifier)
}



@Composable
fun TemperatureChartCard(history: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BIỂU ĐỒ NHIỆT ĐỘ",
                    fontSize = 9.sp,
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                // Legend
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFFF9800)))
                        Spacer(Modifier.width(4.dp))
                        Text("CPU", fontSize = 9.sp, color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF03A9F4)))
                        Spacer(Modifier.width(4.dp))
                        Text("HDD", fontSize = 9.sp, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            
            // Vẽ Biểu đồ bằng Native Canvas (Chiếm 0MB RAM)
            Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                val width = size.width
                val height = size.height
                
                // Mức giới hạn đo nhiệt độ từ 30°C đến 100°C
                val minTemp = 30f
                val maxTemp = 100f
                val range = maxTemp - minTemp
                
                // Đường lưới đứt nét ngang (Grid Lines)
                val gridPaint = androidx.compose.ui.graphics.Paint().apply {
                    color = Color.DarkGray
                    strokeWidth = 1f
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                }
                for (i in 0..4) {
                    val y = height - (i * (height / 4))
                    drawLine(
                        color = Color.DarkGray.copy(alpha = 0.5f),
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(width, y),
                        strokeWidth = 1f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }

                if (history.size < 2) return@Canvas
                
                val pointWidth = width / (40f - 1) // 40 points max
                
                // Vẽ Data
                val cpuPath = androidx.compose.ui.graphics.Path()
                val hddPath = androidx.compose.ui.graphics.Path()
                
                history.forEachIndexed { index, (cpu, hdd) ->
                    val x = index * pointWidth
                    // Calculate Y and clamp it
                    val clampedCpu = cpu.coerceIn(minTemp, maxTemp)
                    val cpuY = height - ((clampedCpu - minTemp) / range * height)
                    
                    val clampedHdd = hdd.coerceIn(minTemp, maxTemp)
                    val hddY = height - ((clampedHdd - minTemp) / range * height)
                    
                    if (index == 0) {
                        cpuPath.moveTo(x, cpuY)
                        hddPath.moveTo(x, hddY)
                    } else {
                        val prevX = (index - 1) * pointWidth
                        val prevCpu = history[index - 1].first.coerceIn(minTemp, maxTemp)
                        val prevCpuY = height - ((prevCpu - minTemp) / range * height)
                        val prevHdd = history[index - 1].second.coerceIn(minTemp, maxTemp)
                        val prevHddY = height - ((prevHdd - minTemp) / range * height)
                        
                        // Bezier Curve tạo đường cong mượt
                        cpuPath.cubicTo(
                            prevX + pointWidth / 2, prevCpuY,
                            x - pointWidth / 2, cpuY,
                            x, cpuY
                        )
                        hddPath.cubicTo(
                            prevX + pointWidth / 2, prevHddY,
                            x - pointWidth / 2, hddY,
                            x, hddY
                        )
                    }
                }
                
                // Vẽ nét đôi
                drawPath(
                    path = cpuPath,
                    color = Color(0xFFFF9800),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                drawPath(
                    path = hddPath,
                    color = Color(0xFF03A9F4),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                
                // Vẽ điểm gút cuối cùng (cục tròn phát sáng nhẹ)
                val lastPoint = history.last()
                val lastX = (history.size - 1) * pointWidth
                
                val lastCpuY = height - ((lastPoint.first.coerceIn(minTemp, maxTemp) - minTemp) / range * height)
                drawCircle(color = Color(0xFFFF9800), radius = 6f, center = androidx.compose.ui.geometry.Offset(lastX, lastCpuY))
                drawCircle(color = Color.White, radius = 3f, center = androidx.compose.ui.geometry.Offset(lastX, lastCpuY))
                
                val lastHddY = height - ((lastPoint.second.coerceIn(minTemp, maxTemp) - minTemp) / range * height)
                drawCircle(color = Color(0xFF03A9F4), radius = 6f, center = androidx.compose.ui.geometry.Offset(lastX, lastHddY))
                drawCircle(color = Color.White, radius = 3f, center = androidx.compose.ui.geometry.Offset(lastX, lastHddY))
                
                // Vẽ chữ hiển thị thông số tại thời điểm đo
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "${String.format("%.1f", lastPoint.first)}°C", 
                    lastX - 15f, 
                    lastCpuY - 15f, 
                    paint
                )
                paint.color = android.graphics.Color.parseColor("#03A9F4")
                drawContext.canvas.nativeCanvas.drawText(
                    "${String.format("%.1f", lastPoint.second)}°C", 
                    lastX - 15f, 
                    lastHddY + 30f, 
                    paint
                )
            }
        }
    }
}

// ============ COMPONENT: Hộp công cụ Toolbox mở rộng ============
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolboxDialog(
    viewModel: WebDavViewModel,
    sharedPrefs: android.content.SharedPreferences,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onOpenLatestPhotos: () -> Unit,
    onOpenRecentVideos: () -> Unit,
    onOpenTrash: () -> Unit,
    showAutoBackupDialog: () -> Unit,
    showLanWhitelistDialog: () -> Unit,
    showLivestreamDialog: () -> Unit
) {
    var isBiometricEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("biometric_enabled", false)) }
    var isAutoBackupEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_backup", false)) }
    var deleteAfterBackup by remember { mutableStateOf(sharedPrefs.getBoolean("delete_after_backup", false)) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            Text("🔧 CÔNG CỤ HỆ THỐNG", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 12.dp))
            
            // Nhóm Media
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigMenuTile("Ảnh mới", "Bộ sưu tập", Icons.Default.Collections, listOf(Color(0xFF42A5F5), Color(0xFF1565C0)), Modifier.weight(1f), { onDismiss(); onOpenLatestPhotos() })
                BigMenuTile("Video", "Phim gần đây", Icons.Default.VideoLibrary, listOf(Color(0xFF66BB6A), Color(0xFF2E7D32)), Modifier.weight(1f), { onDismiss(); onOpenRecentVideos() })
            }
            Spacer(Modifier.height(12.dp))

            // Nhóm SettingsCard
            SettingsMenuCard(
                title = "Thùng Rác",
                subtitle = "Khôi phục tệp bị xoá",
                icon = Icons.Default.Delete,
                color = Color(0xFFEF5350),
                onClick = { onDismiss(); onOpenTrash() }
            )
            Spacer(Modifier.height(8.dp))
            SettingsMenuCard(
                title = "Khóa Sinh trắc học",
                subtitle = "Vân tay / FaceID",
                icon = Icons.Default.Lock,
                color = AccentPurple,
                checked = isBiometricEnabled,
                onClick = {
                    val newValue = !isBiometricEnabled
                    sharedPrefs.edit().putBoolean("biometric_enabled", newValue).apply()
                    isBiometricEnabled = newValue
                }
            )
            Spacer(Modifier.height(8.dp))
            SettingsMenuCard(
                title = "Auto-Backup",
                subtitle = if (deleteAfterBackup) "Copy & Xóa gốc" else "Chỉ Copy",
                icon = Icons.Default.Sync,
                color = AccentGreen,
                checked = isAutoBackupEnabled,
                onClick = { onDismiss(); showAutoBackupDialog() }
            )
            Spacer(Modifier.height(8.dp))

            androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.checkDockerStatus() }
            SettingsMenuCard(
                title = "Docker / qBittorrent",
                subtitle = if (viewModel.isTogglingDocker) "Đang xử lý..." else if (viewModel.isDockerRunning) "Đang chạy" else "Đã tắt (tiết kiệm RAM)",
                icon = Icons.Default.ViewInAr,
                color = Color(0xFF1E88E5),
                checked = viewModel.isDockerRunning,
                onClick = {
                    viewModel.toggleDockerPower(!viewModel.isDockerRunning)
                }
            )
            Spacer(Modifier.height(8.dp))
            SettingsMenuCard(
                title = "Nhật ký hệ thống",
                subtitle = "Lịch sử tiến trình",
                icon = Icons.Default.Assignment,
                color = AccentCyan,
                onClick = {
                    viewModel.loadSystemLogs()
                    onDismiss()
                    viewModel.showLogDialog = true
                }
            )
            Spacer(Modifier.height(8.dp))
            SettingsMenuCard(
                title = "LAN Whitelist",
                subtitle = "IP LAN truy cập thẳng",
                icon = Icons.Default.Wifi,
                color = Color(0xFF66BB6A),
                onClick = { onDismiss(); showLanWhitelistDialog() }
            )
            Spacer(Modifier.height(8.dp))
            SettingsMenuCard(
                title = "Ghi Livestream",
                subtitle = if (viewModel.activeLivestreams.isNotEmpty()) "Dang ghi ${viewModel.activeLivestreams.size} stream..." else "TikTok / Facebook / YouTube",
                icon = Icons.Default.Videocam,
                color = Color(0xFFEE1D52),
                onClick = { onDismiss(); showLivestreamDialog() }
            )
            Spacer(Modifier.height(8.dp))
            SettingsMenuCard(
                title = "Dọn Thùng Rác (30 ngày)",
                subtitle = "Xóa rác cũ hơn 30 ngày",
                icon = Icons.Default.DeleteSweep,
                color = Color(0xFFEF5350),
                onClick = { onDismiss(); viewModel.cleanTrashOnDemand(context, maxAgeDays = 30) }
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}


@Composable
fun SystemStatusCards(viewModel: WebDavViewModel, mContext: android.content.Context) {
    // 1. Thumbnail Status
    LaunchedEffect(Unit) {
        viewModel.fetchThumbStatus()
        while (true) {
            val interval = if (viewModel.thumbRunning || viewModel.thumbPaused) 2_000L else 10_000L
            kotlinx.coroutines.delay(interval)
            viewModel.fetchThumbStatus()
        }
    }
    val thumbPercent = if (viewModel.thumbTotal > 0) viewModel.thumbGenerated * 100f / viewModel.thumbTotal else 0f
    val thumbIsActive = viewModel.thumbRunning || viewModel.thumbPaused || (thumbPercent > 0f && thumbPercent < 100f)

    // 2. Duplicate Scan
    val dupStage by DuplicateProgressState.stage.collectAsState()
    val dupPercent by DuplicateProgressState.percent.collectAsState()
    val dupScanned by DuplicateProgressState.scannedCount.collectAsState()
    val dupFound by DuplicateProgressState.foundCount.collectAsState()
    val dupStageDesc by DuplicateProgressState.stageDescription.collectAsState()
    val dupStageNum by DuplicateProgressState.stageNumber.collectAsState()
    val dupTotalStages by DuplicateProgressState.totalStages.collectAsState()
    val dupElapsed by DuplicateProgressState.elapsedTime.collectAsState()
    val dupEta by DuplicateProgressState.estimatedTimeRemaining.collectAsState()
    val dupIsPaused by DuplicateProgressState.isPaused.collectAsState()
    val dupIsRunning = dupStage != "Khởi động..." && dupStage != "Hoàn tất" && (dupPercent < 1f && dupPercent > 0f || dupStage.contains("Đang phân tích"))
    val dupIsActive = dupIsRunning || dupIsPaused || dupStage == "Đang tổng hợp kết quả..."

    // 3. Auto Backup
    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
    val autoBackupEnabled = sharedPrefs.getBoolean("auto_backup", false)
    val autoBackupIsActive = viewModel.isAutoBackupRunning
    
    // 4. Livestream
    val activeStreams = viewModel.activeLivestreams

    val hasAnyTasks = thumbIsActive || dupIsActive || autoBackupIsActive || activeStreams.isNotEmpty()

    if (!hasAnyTasks) return

    Column(Modifier.fillMaxWidth()) {
        var tasksExpanded by remember { mutableStateOf(false) }
        val activeCount = listOf(thumbIsActive, dupIsActive, autoBackupIsActive, activeStreams.isNotEmpty()).count { it }
        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                // Header — nhấn để mở/đóng
                Row(
                    Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { tasksExpanded = !tasksExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("TÁC VỤ NỀN", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("$activeCount đang chạy", fontSize = 9.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (tasksExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Nội dung mở rộng
                androidx.compose.animation.AnimatedVisibility(visible = tasksExpanded) {
                    Column {
                        // --- THUMBNAIL ---
                        if (thumbIsActive) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(Color(0xFFAB47BC).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PhotoLibrary, null, tint = Color(0xFFAB47BC), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Trình Tạo Ảnh Thu Nhỏ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(when { viewModel.thumbPaused -> "Đã Tạm dừng"; viewModel.thumbRunning -> "🟢 Đang chạy"; else -> "💤 Tạm nghỉ" }, fontSize = 11.sp, color = when { viewModel.thumbPaused -> Color(0xFFFFA726); viewModel.thumbRunning -> Color(0xFF66BB6A); else -> TextSecondary })
                                }
                                if (viewModel.thumbRunning || viewModel.thumbPaused) {
                                    IconButton(onClick = { viewModel.toggleThumbPause() }, modifier = Modifier.size(32.dp)) { Icon(if (viewModel.thumbPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (viewModel.thumbPaused) Color(0xFF66BB6A) else Color(0xFFFFA726), modifier = Modifier.size(18.dp)) }
                                }
                                IconButton(onClick = { viewModel.fetchThumbStatus() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Refresh, "Làm mới", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { (thumbPercent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFFAB47BC), trackColor = Color(0xFF161616))
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${viewModel.thumbGenerated} / ${viewModel.thumbTotal}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text("%.1f%%".format(thumbPercent), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAB47BC))
                            }
                        }

                        if (thumbIsActive && (dupIsActive || autoBackupIsActive || activeStreams.isNotEmpty())) {
                            HorizontalDivider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 12.dp))
                        }

                        // --- DUPLICATE QUÉT ---
                        if (dupIsActive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(Color(0xFF29B6F6).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF29B6F6), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Quét Trùng Lặp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(if (dupIsPaused) "⏸ Đã tạm dừng" else (if (!dupIsRunning) "Chuẩn bị..." else "🟢 Đang quét"), fontSize = 11.sp, color = if (dupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A))
                                }
                                if (dupIsRunning || dupIsPaused) {
                                    IconButton(onClick = { viewModel.togglePauseDuplicateScan() }, modifier = Modifier.size(32.dp)) {
                                        Icon(if (dupIsPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (dupIsPaused) Color(0xFF66BB6A) else Color(0xFFFFA726), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { viewModel.cancelDuplicateScan(mContext) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Stop, null, tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { dupPercent.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFF29B6F6), trackColor = Color(0xFF161616))
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$dupStage", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text("%.1f%%".format(dupPercent * 100), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF29B6F6))
                            }
                        }

                        if (dupIsActive && (autoBackupIsActive || activeStreams.isNotEmpty())) {
                            HorizontalDivider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 12.dp))
                        }

                        // --- AUTO BACKUP ---
                        if (autoBackupIsActive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(Color(0xFF66BB6A).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Sync, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Đồng Bộ NAS", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text("🟢 Đang đồng bộ nền...", fontSize = 11.sp, color = Color(0xFF66BB6A))
                                }
                            }
                        }

                        if (autoBackupIsActive && activeStreams.isNotEmpty()) {
                            HorizontalDivider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 12.dp))
                        }

                        // --- LIVESTREAM ---
                        if (activeStreams.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(Color(0xFFFF7043).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Videocam, null, tint = Color(0xFFFF7043), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Livestream Recording", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text("🔴 Đang ghi hình (${activeStreams.size} kênh)", fontSize = 11.sp, color = Color(0xFFFF7043))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
private const val URL_PREFIX = "http://"
private const val URL_SUFFIX = ":8822/webdav/"

private fun ipToFullUrl(ip: String): String {
    val trimmed = ip.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return if (trimmed.contains(":")) "${URL_PREFIX}$trimmed/webdav/" else "${URL_PREFIX}$trimmed${URL_SUFFIX}"
}

private fun fullUrlToIp(url: String): String = try { java.net.URL(url).host } catch (_: Exception) { url }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: WebDavViewModel, onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val rawHistory = remember { SecurePrefsHelper.getUrlList(context) }
    var historyIps by remember { mutableStateOf(rawHistory.map { fullUrlToIp(it) }.distinct().filter { it.isNotEmpty() }) }
    var ipInput by remember { mutableStateOf(historyIps.firstOrNull() ?: "") }
    var user by remember { mutableStateOf(SecurePrefsHelper.getUser(context).ifEmpty { "admin" }) }
    var pass by remember { mutableStateOf(SecurePrefsHelper.getPass(context)) }
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Storage, contentDescription = "NAS", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Kết nối NAS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(value = ipInput, onValueChange = { ipInput = it }, label = { Text("Địa chỉ IP / DDNS của NAS") }, modifier = Modifier.fillMaxWidth().menuAnchor(), singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) })
            if (historyIps.isNotEmpty()) {
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    historyIps.forEach { ipOption ->
                        DropdownMenuItem(text = { Text(ipOption) }, onClick = { ipInput = ipOption; expanded = false },
                            trailingIcon = { IconButton(onClick = { historyIps = historyIps.filter { it != ipOption } }) { Icon(Icons.Default.Close, contentDescription = "Xóa", modifier = Modifier.size(20.dp)) } })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Tên đăng nhập") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Mật khẩu") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(24.dp))
        val interactionSource = remember { MutableInteractionSource() }
        Button(onClick = {
            val fullUrl = ipToFullUrl(ipInput); val currentIp = fullUrlToIp(fullUrl)
            val newHistoryIps = mutableListOf(currentIp); newHistoryIps.addAll(historyIps.filter { it != currentIp && it.isNotEmpty() }); historyIps = newHistoryIps
            val fullUrlList = newHistoryIps.map { ipToFullUrl(it) }
            viewModel.connect(fullUrlList.map { it.trim() }, user.trim(), pass.trim(), onSuccess = {
                viewModel.scheduleIdleDuplicateScan(context); viewModel.scheduleIdleSpeedTest(context); viewModel.scheduleFingerprintWorker(context); onLoginSuccess()
            }, onError = { errorMsg -> viewModel.commonDialogType = DialogType.ERROR; viewModel.commonDialogMessage = errorMsg; viewModel.showCommonDialog = true })
        }, enabled = !viewModel.isLoading && ipInput.isNotEmpty(), interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
            modifier = Modifier.fillMaxWidth().height(50.dp).background(brush = Brush.linearGradient(listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))), shape = RoundedCornerShape(24.dp))
        ) {
            if (viewModel.isLoading) { CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.5.dp); Spacer(Modifier.width(8.dp)); Text("Đang kết nối...", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            else Text("Đăng nhập", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// GuestPassScreen (từ GuestPassScreen.kt)
// ════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════

private val GpDarkSurface   = Color.Black
private val GpDarkCard      = Color(0xFF0F0F0F)
private val GpAccentGreen   = Color(0xFF00E676)
private val GpAccentOrange  = Color(0xFFFF9100)
private val GpAccentRed     = Color(0xFFFF1744)
private val GpAccentCyan    = Color(0xFF00D2FF)
private val GpAccentPurple  = Color(0xFFBB86FC)
private val GpTextPrimary   = Color(0xFFE8E8E8)
private val GpTextSecondary = Color(0xFF8892B0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestPassScreen(viewModel: WebDavViewModel, onBack: () -> Unit) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var durationMinutes by remember { mutableIntStateOf(AppConfig.GUEST_PASS_DEFAULT_MINUTES) }
    var copiedField by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("Local Guest Pass", fontWeight = FontWeight.Bold, color = GpTextPrimary); Text("Cấp vé FTP tạm thời cho khách", fontSize = 11.sp, color = GpTextSecondary) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = GpTextPrimary) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = GpDarkSurface))
    }, containerColor = GpDarkSurface) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpAccentPurple.copy(alpha = 0.08f)), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = GpAccentPurple, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Text("NAS sẽ tự động tạo một tài khoản FTP tạm thời với quyền Chỉ đọc (Read-Only). Khách dùng FTP client (FileZilla, ES File Explorer...) để kết nối vào kho phim. Tài khoản tự xóa sau thời hạn.", fontSize = 12.sp, color = GpTextSecondary, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpDarkCard), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Thời hạn Guest Pass", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GpTextPrimary); Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30 to "30 phút", 60 to "1 giờ?", 180 to "3 giờ?", 1440 to "1 ngày").forEach { (min, label) ->
                            FilterChip(selected = durationMinutes == min, onClick = { durationMinutes = min }, label = { Text(label, fontSize = 11.sp) }, modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GpAccentCyan.copy(alpha = 0.2f), selectedLabelColor = GpAccentCyan, containerColor = GpDarkSurface, labelColor = GpTextSecondary))
                        }
                    }
                    Spacer(Modifier.height(8.dp)); Text("Thời hạn đã chọn: $durationMinutes phút (${durationMinutes / 60} giờ? ${durationMinutes % 60} phút)", fontSize = 12.sp, color = GpAccentCyan)
                }
            }
            Spacer(Modifier.height(12.dp))
            val pass = viewModel.activeGuestPass
            AnimatedVisibility(visible = pass != null) {
                pass?.let { gp ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpDarkCard), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, GpAccentGreen.copy(alpha = 0.4f))) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = GpAccentGreen, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Guest Pass đang hoạt động", fontWeight = FontWeight.Bold, color = GpAccentGreen) }
                            Spacer(Modifier.height(14.dp))
                            GuestInfoRow("Host", gp.host, clipboardManager, copiedField, "host") { copiedField = "host" }; Spacer(Modifier.height(8.dp))
                            GuestInfoRow("Port FTP", gp.ftpPort.toString(), clipboardManager, copiedField, "port") { copiedField = "port" }; Spacer(Modifier.height(8.dp))
                            GuestInfoRow("Username", gp.username, clipboardManager, copiedField, "user") { copiedField = "user" }; Spacer(Modifier.height(8.dp))
                            GuestInfoRow("Password", gp.password, clipboardManager, copiedField, "pass") { copiedField = "pass" }; Spacer(Modifier.height(8.dp))
                            val expiresMs = gp.expiresAt - System.currentTimeMillis(); val expiresMin = (expiresMs / 60000).coerceAtLeast(0)
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Timer, null, tint = if (expiresMin < 10) GpAccentOrange else GpTextSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(if (expiresMin > 0) "Hết hạn sau $expiresMin phút" else "⚠️? Sắp hết hạn / Đã hết hạn", fontSize = 12.sp, color = if (expiresMin < 10) GpAccentOrange else GpTextSecondary) }
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { viewModel.revokeGuestPass() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !viewModel.isGuestPassLoading, colors = ButtonDefaults.buttonColors(containerColor = GpAccentRed.copy(alpha = 0.8f))) { Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Thu hồi ngay", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            viewModel.guestPassError?.let { err -> Spacer(Modifier.height(10.dp)); Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpAccentRed.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Error, null, tint = GpAccentRed, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(err, color = GpAccentRed, fontSize = 12.sp) } } }
            Spacer(Modifier.height(14.dp))
            if (pass == null) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (!viewModel.isGuestPassLoading) Brush.horizontalGradient(listOf(GpAccentPurple, Color(0xFF6200EA))) else Brush.horizontalGradient(listOf(GpTextSecondary.copy(alpha=0.2f), GpTextSecondary.copy(alpha=0.2f)))).clickable(enabled = !viewModel.isGuestPassLoading) { viewModel.createGuestPass(durationMinutes) }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    if (viewModel.isGuestPassLoading) { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = GpTextPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Đang tạo tài khoản...", color = GpTextPrimary, fontWeight = FontWeight.Bold) } }
                    else { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PersonAdd, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text("Cấp Guest Pass ($durationMinutes phút)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GuestInfoRow(label: String, value: String, clipboardManager: androidx.compose.ui.platform.ClipboardManager, copiedField: String, fieldKey: String, onCopied: () -> Unit) {
    val isCopied = copiedField == fieldKey
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F3460).copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 10.sp, color = Color(0xFF8892B0), fontWeight = FontWeight.Bold); Text(value, fontSize = 14.sp, color = Color(0xFFE8E8E8), fontWeight = FontWeight.SemiBold) }
        IconButton(onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(value)); onCopied() }, modifier = Modifier.size(32.dp)) {
            Icon(if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy, null, tint = if (isCopied) Color(0xFF00E676) else Color(0xFF8892B0), modifier = Modifier.size(16.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// PerformanceScreen (từ PerformanceScreen.kt)
// ════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(onBack: () -> Unit) {
    val mContext = LocalContext.current
    val metrics by PerformanceMonitor.metricsFlow.collectAsState()
    LaunchedEffect(Unit) { PerformanceMonitor.startMonitoring(mContext) }
    Scaffold(topBar = { TopAppBar(title = { Text("Màn Giám Sát Kỹ Thuật (DevOps Monitor)", fontSize = 18.sp, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Trở lại") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard("Động Cơ JVM (App RAM)", Icons.Default.Memory, "${metrics.usedJvmMemoryMb} MB / ${metrics.maxJvmMemoryMb} MB", if (metrics.maxJvmMemoryMb > 0) metrics.usedJvmMemoryMb.toFloat() / metrics.maxJvmMemoryMb else 0f, if (metrics.usedJvmMemoryMb > metrics.maxJvmMemoryMb * 0.8) Color.Red else Color.Green)
            MetricCard("Bộ Nhớ Hệ Thống (Màng RAM)", Icons.Default.Adb, "Trống: ${metrics.freeRamMb} MB (Tổng: ${metrics.totalRamMb} MB)", metrics.ramUsagePercent / 100f, if (metrics.ramUsagePercent > 85) Color.Red else Color(0xFF03A9F4))
            MetricCard("Trái Tim Chip Bán Dẫn (CPU Thread)", Icons.Default.Speed, "Hoạt động: ${metrics.cpuUsagePercent}% (Dao động ảo)", metrics.cpuUsagePercent / 100f, if (metrics.cpuUsagePercent > 70) Color(0xFFFF9800) else Color.Cyan)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkBadge(Modifier.weight(1f), "Tải Xuống", "${metrics.rxSpeedKbps} KB/s", Icons.Default.ArrowDownward, Color.Green)
                NetworkBadge(Modifier.weight(1f), "Đẩy Lên", "${metrics.txSpeedKbps} KB/s", Icons.Default.ArrowUpward, Color(0xFFFF5722))
            }
            MetricCard("Kho Gạch Ngói Hình Ảnh (Coil Disk Cache)", Icons.Default.Storage, "${metrics.diskCacheSizeMb} MB đang ngốn rác", (metrics.diskCacheSizeMb / 800f).coerceIn(0f, 1f), Color(0xFF9C27B0))
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { coil.Coil.imageLoader(mContext).memoryCache?.clear(); System.gc() }, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))) { Icon(Icons.Default.DeleteForever, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("BĂM NÚT BỘ ĐỆM RAM (Tránh Đơ Máy)", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun MetricCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, progress: Float, progressColor: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(title, color = Color.Gray, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp)); Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = progressColor, trackColor = Color(0xFF424242))
        }
    }
}

@Composable
fun NetworkBadge(modifier: Modifier, title: String, speed: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(4.dp)); Text(title, color = Color.Gray, fontSize = 12.sp); Text(speed, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

data class QuickActionDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val gradientColors: List<androidx.compose.ui.graphics.Color>
)

val AVAILABLE_QUICK_ACTIONS = listOf(
    QuickActionDef("sync", "Tự Đồng Bộ", "Cấu hình sao lưu", Icons.Default.CloudSync, listOf(Color(0xFF26A69A), Color(0xFF00897B))),
    QuickActionDef("stream", "Ghi Livestream", "Record TikTok, FB", Icons.Default.Videocam, listOf(Color(0xFFFF5252), Color(0xFFC62828))),
    QuickActionDef("trash", "Thùng Rác", "Khôi phục dữ liệu", Icons.Default.Delete, listOf(Color(0xFFEF5350), Color(0xFFD32F2F))),
    QuickActionDef("organizer", "Phân Loại Tệp", "AI Smart Organizer", Icons.Default.AutoAwesomeMotion, listOf(Color(0xFF42A5F5), Color(0xFF1565C0))),
    QuickActionDef("guest", "Mạng Khách", "Cấp thẻ Wi-Fi QR", Icons.Default.Wifi, listOf(Color(0xFFAB47BC), Color(0xFF7B1FA2))),
    QuickActionDef("log", "Nhật ký Lõi", "Tiến trình giám sát", Icons.Default.Assignment, listOf(Color(0xFF26C6DA), Color(0xFF0097A7)))
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QuickActionSelectorDialog(
    currentSlots: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("TUỲ CHỌN LỐI TẮT TRUY CẬP", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 12.dp))
            AVAILABLE_QUICK_ACTIONS.forEach { action ->
                val isSelected = currentSlots.contains(action.id)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { if (!isSelected) onSelect(action.id) },
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) AccentCyan.copy(alpha=0.15f) else DarkCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(action.gradientColors)), contentAlignment = Alignment.Center) {
                            Icon(action.icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(action.subtitle, color = TextSecondary, fontSize = 11.sp)
                        }
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SystemLogsSummaryCard(viewModel: WebDavViewModel) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadSystemLogs()
    }
    
    if (viewModel.systemLogsList.isEmpty()) return
    
    Spacer(Modifier.height(8.dp))
    
    var isExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }) {
                Icon(Icons.Default.Assignment, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nhật ký hệ thống", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Mở rộng/Thu gọn",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.showLogDialog = true },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Xem tất cả", color = AccentCyan, fontSize = 12.sp)
                }
            }
            
            androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val recentLogs = viewModel.systemLogsList.take(3)
                    recentLogs.forEach { log ->
                        val logColor = when (log.type) {
                            "SUCCESS" -> Color(0xFF43A047)
                            "ERROR" -> Color(0xFFEF5350)
                            "WARNING" -> Color(0xFFFFA726)
                            else -> Color(0xFF29B6F6)
                        }
                        val timeStr = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(log.timestamp)
                        
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(logColor).padding(top = 4.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(log.module, color = logColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(timeStr, color = TextSecondary, fontSize = 10.sp)
                                }
                                Text(
                                    com.nas.naswebdav.ui.dialogs.formatLogMessage(log.message),
                                    color = TextPrimary.copy(alpha=0.85f),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatElapsedTimeUI(millis: Long): String {
    if (millis <= 0) return "0 giây"
    val totalSeconds = millis / 1000
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val parts = mutableListOf<String>()
    if (days > 0) parts.add("$days ngày")
    if (hours > 0) parts.add("$hours giờ?")
    if (minutes > 0) parts.add("$minutes phút")
    if (seconds > 0 || parts.isEmpty()) parts.add("$seconds giây")

    return parts.joinToString(", ")
}

