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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue

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
private val PanelTitleCyan = Color(0xFF4DD0E1)
private val PanelTitleGreen = Color(0xFF66BB6A)
private val PanelTitlePurple = Color(0xFFB388FF)
private val PanelTitleSize = 11.sp
private val PanelTitleLetterSpacing = 1.5.sp

private fun realtimeFreshnessLabel(lastRefreshAt: Long, now: Long): String {
    if (lastRefreshAt <= 0L) return "Đang chờ dữ liệu"
    val ageSec = ((now - lastRefreshAt).coerceAtLeast(0L) / 1000L).toInt()
    return when {
        ageSec < 5 -> "Vừa cập nhật"
        ageSec < 60 -> "Cập nhật ${ageSec} giây trước"
        ageSec < 3600 -> "Cập nhật ${ageSec / 60} phút trước"
        else -> "Dữ liệu trễ ${ageSec / 3600} giờ"
    }
}

@Composable
private fun PanelFreshnessTag(
    lastRefreshAt: Long,
    now: Long,
    staleAfterMs: Long = 30_000L,
) {
    val ageMs = (now - lastRefreshAt).coerceAtLeast(0L)
    val isWaiting = lastRefreshAt <= 0L
    val isStale = isWaiting || ageMs > staleAfterMs
    val color = if (isStale) AccentOrange else AccentGreen
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            realtimeFreshnessLabel(lastRefreshAt, now),
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DashboardCompactBottomSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(5.dp)
                .background(Color(0xFF6D6A75), RoundedCornerShape(50))
        )
    }
}

// ============ FAN SPEED ANIMATED ICON ============
@Composable
fun FanSpeedIcon(percent: Int, color: Color, modifier: Modifier = Modifier) {
    val isRunning = percent > 0
    val durationMs = if (isRunning) maxOf(300, (30000 / maxOf(percent, 1))) else 9999
    
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

// ============================================================================
// EXCLUSIVE PANEL STATE — chỉ cho phép 1 panel inline mở cùng lúc trong
// MainMenuScreen (OMV, Tasks, Chart). Mở panel này -> tự cụp panel kia.
// Singleton object để các panel rải rác qua nhiều Composable vẫn chia chung
// 1 state không cần pass qua 2 hộp param.
// Reset về null khi user logout (xử lý trong MainMenuScreen.onLogout nếu cần).
// ============================================================================
object ExclusivePanelState {
    val current: androidx.compose.runtime.MutableState<String?> =
        androidx.compose.runtime.mutableStateOf(null)

    fun toggle(panelId: String) {
        current.value = if (current.value == panelId) null else panelId
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
    var realtimeNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            realtimeNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // STATE CHO POPUP TẢI TỪ XA
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadLink by remember { mutableStateOf("") }
    
    // STATE CHO DANH SÁCH TIẾN TRÌNH
    var showProcessDialog by remember { mutableStateOf(false) }
    var processSortType by remember { mutableStateOf("cpu") }

    // STATE CHO QUÉT TRÙNG LẶP (từ màn hình chính)
    var showDuplicateScanDialog by remember { mutableStateOf(false) }
    var dupScanLightningMode by remember { mutableStateOf(true) }
    var dupScanForceRestart by remember { mutableStateOf(false) }
    var showSmartDialog by remember { mutableStateOf(false) }

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
    var showSmbDialog by remember { mutableStateOf(false) }

    // STATE CHO NAS CONFIG BACKUP/RESTORE
    var showNasBackupDialog by remember { mutableStateOf(false) }
    // STATE CHO DISK HEALTH MONITOR
    var showDiskHealthDialog by remember { mutableStateOf(false) }
    var showNewDiskProfileSheet by remember { mutableStateOf(false) }
    // STATE CHO SLEEP SCHEDULE
    var showSleepScheduleDialog by remember { mutableStateOf(false) }
    // STATE CHO BANDWIDTH THROTTLE
    var showBandwidthDialog by remember { mutableStateOf(false) }
    // STATE CHO USB IMPORT
    var showUsbImportDialog by remember { mutableStateOf(false) }
    var showNasInsightsDialog by remember { mutableStateOf(false) }
    // Load bandwidth limit từ SharedPreferences (1 lần khi mở app)
    LaunchedEffect(Unit) {
        val savedLimit = sharedPrefs.getLong("upload_speed_limit_bps", 0L)
        com.nas.naswebdav.AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC = savedLimit
    }
    // ── SMART SWITCH: Tự động kiểm tra và chuyển mạng khi vào màn hình ──────
    LaunchedEffect(Unit) {
        viewModel.checkSmartNetwork(mContext)
        viewModel.fetchSmartData()
        viewModel.fetchOmvOverview()
        viewModel.fetchNasInsights()
        viewModel.syncLivestreamStateWithServer(mContext)
        viewModel.launchDashboardRealtimeScheduler()
    }

    // FIX D10: Collect tất cả AutoBackupState values cùng lúc ở top-level Composable.
    // Trước đây: 4 lần collectAsState() được gọi BÊN TRONG `if (showBackupResult)` block →
    // chỉ subscribe khi dialog mở, nhưng tạo ra race condition và subscription không ổn định.
    // Bây giờ: collect ở top-level, luôn sẵn có khi cần, không có allocation thêm.
    val showBackupResult by com.nas.naswebdav.AutoBackupState.showResultDialog.collectAsState()
    val backupResultTotal by com.nas.naswebdav.AutoBackupState.resultTotal.collectAsState()
    val backupResultSuccess by com.nas.naswebdav.AutoBackupState.resultSuccess.collectAsState()
    val backupResultSkipped by com.nas.naswebdav.AutoBackupState.resultSkipped.collectAsState()
    val backupResultFailed by com.nas.naswebdav.AutoBackupState.resultFailed.collectAsState()

    if (showBackupResult) {
        AlertDialog(
            onDismissRequest = { com.nas.naswebdav.AutoBackupState.showResultDialog.value = false },
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CloudDone, null, tint = AccentGreen); Spacer(Modifier.width(8.dp)); Text("Báo Cáo Đồng Bộ") } },
            text = {
                Column {
                    Text("Tổng danh sách tệp được quét: $backupResultTotal", fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Thành công: $backupResultSuccess", color = AccentGreen, fontWeight = FontWeight.Bold)
                    Text("Bỏ qua (đã đồng bộ trước đó): $backupResultSkipped", color = TextSecondary)
                    Text("Thất bại: $backupResultFailed", color = if (backupResultFailed > 0) AccentRed else TextSecondary)
                }
            },
            confirmButton = { TextButton(onClick = { com.nas.naswebdav.AutoBackupState.showResultDialog.value = false }) { Text("Đóng", color = AccentCyan) } },
            containerColor = DarkCard,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    // --- DIALOGS (từ ui/dialogs/Dialogs.kt) ---
    if (showRebootConfirm) {
        RebootConfirmDialog(
            onConfirm = {
                viewModel.sendCommandToNas("power/reboot") { ok, message ->
                    commonDialogType = if (ok) DialogType.WARNING else DialogType.ERROR
                    commonDialogMessage = message
                    showCommonDialog = true
                }
                showRebootConfirm = false
            },
            onDismiss = { showRebootConfirm = false }
        )
    }
    if (showShutdownConfirm) {
        ShutdownConfirmDialog(
            onConfirm = {
                viewModel.sendCommandToNas("power/suspend") { ok, message ->
                    commonDialogType = if (ok) DialogType.WARNING else DialogType.ERROR
                    commonDialogMessage = message
                    showCommonDialog = true
                }
                showShutdownConfirm = false
            },
            onDismiss = { showShutdownConfirm = false }
        )
    }
    // Launcher de pick file .torrent tu storage
    val torrentFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            viewModel.uploadTorrentFile(mContext, uri)
            showDownloadDialog = false
            downloadLink = ""
        }
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
            onDismiss = { showDownloadDialog = false },
            onPickTorrentFile = {
                // Mo file picker — chap nhan .torrent va octet-stream (mot so file
                // manager khong khai bao MIME chuan cho .torrent).
                torrentFilePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
            }
        )
    }
    
    if (showProcessDialog) {
        ProcessListBottomSheet(
            viewModel = viewModel,
            sortBy = processSortType,
            onDismiss = { showProcessDialog = false }
        )
    }

    // DIALOG CẤU HÌNH QUÉT TRÙNG LẶP — từ màn hình chính
    if (showDuplicateScanDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateScanDialog = false },
            icon = { Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF29B6F6), modifier = Modifier.size(36.dp)) },
            title = { Text("Quét tệp trùng lặp", fontWeight = FontWeight.Bold, color = TextPrimary) },
            containerColor = Color(0xFF1A1A2E),
            textContentColor = TextPrimary,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Hệ thống sẽ quét toàn bộ NAS và phát hiện tệp có nội dung giống nhau.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)

                    // Option 1: Lightning Mode
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { dupScanLightningMode = !dupScanLightningMode },
                        color = if (dupScanLightningMode) Color(0xFFFFC107).copy(alpha = 0.1f) else Color(0xFF222233),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dupScanLightningMode,
                                onCheckedChange = { dupScanLightningMode = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFC107))
                            )
                            Column(Modifier.padding(start = 6.dp)) {
                                Text("⚡ Chế độ nhanh (Khuyến nghị)", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = if (dupScanLightningMode) Color(0xFFFFC107) else TextPrimary)
                                Text("Bỏ qua hash nội dung, dùng ETag. Nhanh hơn 100×, phù hợp 500k+ tệp.", fontSize = 11.sp, color = TextSecondary, lineHeight = 14.sp)
                            }
                        }
                    }

                    // Option 2: Force Restart
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { dupScanForceRestart = !dupScanForceRestart },
                        color = if (dupScanForceRestart) Color(0xFFEF5350).copy(alpha = 0.1f) else Color(0xFF222233),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dupScanForceRestart,
                                onCheckedChange = { dupScanForceRestart = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFEF5350))
                            )
                            Column(Modifier.padding(start = 6.dp)) {
                                Text("Quét lại từ đầu", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = if (dupScanForceRestart) Color(0xFFEF5350) else TextPrimary)
                                Text("Bỏ qua lịch sử lưu tạm, thực hiện quét hoàn toàn mới.", fontSize = 11.sp, color = TextSecondary, lineHeight = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDuplicateScanDialog = false
                        viewModel.startBackgroundDuplicateScan(mContext, forceRestart = dupScanForceRestart, lightningMode = dupScanLightningMode)
                        viewModel.isScanningDuplicates = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6))
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bắt đầu quét", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateScanDialog = false }) {
                    Text("Hủy", color = TextSecondary)
                }
            }
        )
    }
    if (showSmartDialog) {
        SmartDetailBottomSheet(
            smartInfo = viewModel.smartInfo,
            onDismiss = { showSmartDialog = false }
        )
    }
    if (showWolDialog) {
        WolDialog(
            macAddress = macAddress,
            onMacChange = { macAddress = it },
            onConfirm = {
                val wolMac = macAddress.trim()
                if (wolMac.isNotBlank()) {
                    sharedPrefs.edit().putString("mac_address", wolMac).apply()
                    showWolDialog = false
                    viewModel.sendWakeOnLan(wolMac) { result ->
                        commonDialogType = if (result.success) DialogType.SUCCESS else DialogType.ERROR
                        commonDialogMessage = result.message
                        showCommonDialog = true
                    }
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
    if (showSmbDialog) {
        SmbBottomSheet(
            viewModel = viewModel,
            onDismiss = { showSmbDialog = false }
        )
    }
    if (showLivestreamDialog) {
        LivestreamRecordDialog(
            viewModel = viewModel,
            onDismiss = { showLivestreamDialog = false }
        )
    }
    if (showNasBackupDialog) {
        com.nas.naswebdav.ui.dialogs.NasConfigBackupDialog(
            viewModel = viewModel,
            onDismiss = { showNasBackupDialog = false }
        )
    }
    if (showDiskHealthDialog) {
        com.nas.naswebdav.ui.dialogs.DiskHealthDialog(
            viewModel = viewModel,
            onDismiss = { showDiskHealthDialog = false }
        )
    }
    if (showNewDiskProfileSheet) {
        DiskProfileBottomSheet(
            viewModel = viewModel,
            onDismiss = { showNewDiskProfileSheet = false }
        )
    }
    if (showSleepScheduleDialog) {
        com.nas.naswebdav.ui.dialogs.SleepScheduleDialog(
            viewModel = viewModel,
            onDismiss = { showSleepScheduleDialog = false }
        )
    }
    if (showUsbImportDialog) {
        com.nas.naswebdav.ui.dialogs.UsbImportDialog(
            viewModel = viewModel,
            onDismiss = { showUsbImportDialog = false }
        )
    }
    if (showNasInsightsDialog) {
        com.nas.naswebdav.ui.dialogs.NasInsightsDialog(
            viewModel = viewModel,
            onDismiss = { showNasInsightsDialog = false },
            onTaskClick = { taskLabel ->
                showNasInsightsDialog = false
                val lbl = taskLabel.lowercase()
                if (lbl.contains("livestream") || lbl.contains("stream")) {
                    showLivestreamDialog = true
                } else if (lbl.contains("usb")) {
                    viewModel.fetchUsbImportStatus()
                    showUsbImportDialog = true
                } else {
                    ExclusivePanelState.current.value = "tasks"
                }
            }
        )
    }
    if (showBandwidthDialog) {
        com.nas.naswebdav.ui.dialogs.BandwidthThrottleDialog(
            viewModel = viewModel,
            sharedPrefs = sharedPrefs,
            onDismiss = { showBandwidthDialog = false }
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
            "nasbackup" -> { viewModel.fetchNasConfigBackups(); showNasBackupDialog = true }
            "smb" -> { viewModel.fetchSmbStatus(); showSmbDialog = true }
            "duplicate" -> showDuplicateScanDialog = true
        }
    }

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.checkSmartNetwork(mContext)
            viewModel.fetchSmartData()
            viewModel.listenToLocalNasApi() // KHÔI PHỤC KẾT NỐI VÀ RESET DELAY NGAY LẬP TỨC
            viewModel.launchDashboardRealtimeScheduler()
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
                .padding(horizontal = 10.dp)
        ) {
        Spacer(Modifier.height(24.dp))

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
                Text("NAS Dashboard", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chainedbox L1 Pro", fontSize = 12.sp, color = TextSecondary)
                    Text("  \u2022  ", fontSize = 12.sp, color = TextSecondary)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isOnlineStatus) AccentGreen else AccentRed)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (isOnlineStatus) "Online" else "Offline",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    val isRealtimeStale = viewModel.lastStatusRefreshAt <= 0L || realtimeNow - viewModel.lastStatusRefreshAt > 10_000L
                    val realtimeColor = if (isRealtimeStale) AccentOrange else AccentGreen
                    Icon(Icons.Default.Sync, null, tint = realtimeColor, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        realtimeFreshnessLabel(viewModel.lastStatusRefreshAt, realtimeNow),
                        fontSize = 10.sp,
                        color = realtimeColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    viewModel.apiLatencyMs?.let { latency ->
                        Spacer(Modifier.width(8.dp))
                        Text("API ${latency}ms", fontSize = 10.sp, color = TextSecondary)
                    }
                    if (viewModel.apiFailureCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("${viewModel.apiFailureCount} lỗi", fontSize = 10.sp, color = AccentRed, fontWeight = FontWeight.Bold)
                    }
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
                            text = { Text("Ngủ NAS", color = AccentCyan) },
                            leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null, tint = AccentCyan) },
                            onClick = { showPowerMenu = false; showShutdownConfirm = true }
                        )
                    }
                }
            }
        }
            
            Spacer(Modifier.height(8.dp))

            // Đã THẾ HỆ THỐNG: CPU + RAM + Stats Đã
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HỆ THỐNG", fontSize = PanelTitleSize, color = PanelTitleCyan, fontWeight = FontWeight.Black,
                        letterSpacing = PanelTitleLetterSpacing)
                    Spacer(Modifier.weight(1f))
                    PanelFreshnessTag(viewModel.lastMetricsRefreshAt, realtimeNow, staleAfterMs = 15_000L)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeCard(
                        title = "CPU", value = viewModel.systemStatus.cpu,
                        subValue = viewModel.systemStatus.cpuTemp,
                        icon = Icons.Default.Memory,
                        gradientColors = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            processSortType = "cpu"
                            showProcessDialog = true
                        }
                    )
                    GaugeCard(
                        title = "RAM", value = viewModel.systemStatus.ram, subValue = "${viewModel.systemStatus.ramPercent}%",
                        icon = Icons.Default.DeveloperBoard,
                        gradientColors = listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
                        modifier = Modifier.weight(1f),
                        overridePercent = viewModel.systemStatus.ramPercent.replace("%", "").trim().toFloatOrNull(),
                        onClick = {
                            processSortType = "mem"
                            showProcessDialog = true
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
                            gradientColors = listOf(Color(0xFFFFA726), Color(0xFFF57C00)),
                            modifier = Modifier.weight(1f),
                            overridePercent = hddDisk.percent,
                            onClick = { showNewDiskProfileSheet = true }
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
                        overridePercent = smartPercent,
                        onClick = { showSmartDialog = true }
                    )
                }
            }
        }

        // ═══ OMV SERVICES & HARDWARE (Expandable Panel) ═══
        if (viewModel.omvOverview.services.isNotEmpty() || viewModel.omvOverview.disks.isNotEmpty()) {
            // Mo doc quyen: panel mo dong bo voi ExclusivePanelState — khi mo
            // panel khac (Tasks, Chart) thi panel nay tu cup.
            val omvExpanded = ExclusivePanelState.current.value == "omv"
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
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
                            Icon(Icons.Default.Dashboard, null, tint = Color(0xFF42A5F5), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OMV", fontSize = PanelTitleSize, fontWeight = FontWeight.Black, color = PanelTitleCyan, letterSpacing = PanelTitleLetterSpacing)
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
                            Spacer(Modifier.height(6.dp))

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
                                            Text(if (svc.running) "Bật" else "Tắt", fontSize = 9.sp, color = svcColor.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }

                            // Network + Hardware info
                            val net = viewModel.omvOverview.network.firstOrNull()
                            val hdd = selectNasTargetDisk(viewModel.omvOverview.disks)
                            if (net != null || hdd != null) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    if (net != null) {
                                        Column {
                                            Text("${net.name} • ${net.speed}Mbps", fontSize = 10.sp, color = TextSecondary, letterSpacing = 0.5.sp)
                                            Text("${net.address} | Cổng mạng: ${net.gateway}", fontSize = 10.sp, color = Color(0xFF81D4FA))
                                            Text("MAC: ${net.mac}", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.6f))
                                        }
                                    }
                                    if (hdd != null) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(hdd.model, fontSize = 10.sp, color = TextSecondary, maxLines = 1)
                                            Text("Số sê-ri: ${hdd.serial}", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.6f))
                                            val sizeGb = (hdd.size.toLongOrNull() ?: 0L) / (1024L * 1024 * 1024)
                                            val sizeTb = if (sizeGb >= 1024) "%.1f TB".format(sizeGb / 1024f) else "$sizeGb GB"
                                            Text(sizeTb, fontSize = 10.sp, color = Color(0xFFFFA726), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            
                            // Fan Control
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth().background(Color(0xFF191919), RoundedCornerShape(6.dp)).padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val fanStatusStr = viewModel.systemStatus.fanStatus
                                    val isFanRunning = fanStatusStr != "Dừng" && fanStatusStr != "--"
                                    val percentStr = fanStatusStr.replace(Regex("[^0-9]"), "")
                                    val realPercent = if (percentStr.isNotEmpty()) percentStr.toInt() else if (isFanRunning) 100 else 0
                                    
                                    var displayPercent = realPercent
                                    var displayStatusStr = fanStatusStr
                                    
                                    if (viewModel.systemStatus.fanMode == "custom" && isFanRunning) {
                                        val cpuVal = viewModel.systemStatus.cpuTemp.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                                        val onT = viewModel.systemStatus.fanOnTemp
                                        val offT = viewModel.systemStatus.fanOffTemp
                                        if (cpuVal >= onT) {
                                            displayPercent = 100
                                        } else if (cpuVal <= offT) {
                                            displayPercent = 20
                                        } else if (onT > offT) {
                                            displayPercent = 20 + ((cpuVal - offT) / (onT - offT) * 80).toInt()
                                        }
                                        displayStatusStr = "Đang thực thi $displayPercent%"
                                    }
                                    
                                    FanSpeedIcon(percent = displayPercent, color = if (isFanRunning) Color(0xFF00E676) else TextSecondary, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Quạt tản nhiệt", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                        Text(displayStatusStr, fontSize = 9.sp, color = if (isFanRunning) Color(0xFF00E676) else TextSecondary)
                                    }
                                }
                                // Mute / Auto / Max Toggle
                                var showFanSettings by remember { mutableStateOf(false) }
                                Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                                    val modes = listOf("custom" to "Tùy chỉnh", "on" to "Bật", "off" to "Tắt")
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
                                                .background(if (active) if (m == "off") Color(0xFFEF5350) else Color(0xFF00E676) else Color.Transparent)
                                                .alpha(if (isFanControlLocked && !active) 0.5f else 1f)
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
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
                                            val isFanControlLocked = viewModel.isFanModeUpdating
                                            Button(
                                                enabled = !isFanControlLocked,
                                                onClick = { 
                                                    viewModel.setFanMode("custom", onTemp.toFloatOrNull() ?: 65f, offTemp.toFloatOrNull() ?: 55f)
                                                    showFanSettings = false 
                                                }
                                            ) { Text("Lưu & Áp dụng") }
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
        Spacer(Modifier.height(8.dp))
        NasInsightsSummaryCard(
            viewModel = viewModel,
            onOpen = {
                viewModel.fetchNasInsights()
                showNasInsightsDialog = true
            }
        )
        Spacer(Modifier.height(4.dp))
        
        SystemStatusCards(
            viewModel = viewModel,
            mContext = mContext,
            onOpenAutoBackup = { showAutoBackupDialog = true },
            onOpenLivestream = { showLivestreamDialog = true },
            onOpenUsbImport = {
                viewModel.fetchUsbImportStatus()
                showUsbImportDialog = true
            },
            onOpenDuplicateScan = { if (viewModel.duplicateFilesList.isNotEmpty()) viewModel.isShowingDuplicates = true else showDuplicateScanDialog = true }
        )

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
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
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
                            androidx.compose.runtime.key(torrent.hash) {
                            com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                onDelete = { viewModel.controlTorrent("delete", torrent.hash) },
                                shape = RoundedCornerShape(6.dp),
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
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFFCA28), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(torrent.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
            Spacer(Modifier.height(8.dp))
        }



        // Đã DANH MỤC TRUY CẬP NHANH Đã 
        Text("TRUY CẬP NHANH", fontSize = PanelTitleSize, fontWeight = FontWeight.Black, color = PanelTitlePurple, letterSpacing = PanelTitleLetterSpacing, modifier = Modifier.padding(bottom = 6.dp))

        // Đã CHỨC NĂNG CHÍNH (Lưới 2x2) Đã 
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigMenuTile("Quản lý Tệp", "Duyệt & quản lý tệp", Icons.Default.Folder, listOf(Color(0xFFFFCA28), Color(0xFFFF8F00)), Modifier.weight(1f), onClick = onOpenFiles)
            val s2 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot2Id } ?: AVAILABLE_QUICK_ACTIONS[0]
            BigMenuTile(s2.title, s2.subtitle, s2.icon, s2.gradientColors, Modifier.weight(1f), onClick = { handleQuickAction(s2.id) }, onLongClick = { editingSlot = 2 })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val s3 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot3Id } ?: AVAILABLE_QUICK_ACTIONS[1]
            BigMenuTile(s3.title, s3.subtitle, s3.icon, s3.gradientColors, Modifier.weight(1f), onClick = { handleQuickAction(s3.id) }, onLongClick = { editingSlot = 3 })
            val s4 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot4Id } ?: AVAILABLE_QUICK_ACTIONS[2]
            BigMenuTile(s4.title, s4.subtitle, s4.icon, s4.gradientColors, Modifier.weight(1f), onClick = { handleQuickAction(s4.id) }, onLongClick = { editingSlot = 4 })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigMenuTile("Ảnh gần đây", "Mở ảnh mới nhất", Icons.Default.PhotoLibrary, listOf(Color(0xFF7C4DFF), Color(0xFF00D2FF)), Modifier.weight(1f), onClick = onOpenLatestPhotos)
            BigMenuTile("Video gần đây", "Mở video mới nhất", Icons.Default.VideoLibrary, listOf(Color(0xFFFF6EC7), Color(0xFFFF9100)), Modifier.weight(1f), onClick = onOpenRecentVideos)
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

        Spacer(Modifier.height(10.dp))
        
        // Nút mở Toolbox mở rộng
        Button(
            onClick = { showToolboxDialog = true },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkCard),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BuildCircle, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Công cụ & Cài đặt", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        }
        Spacer(Modifier.height(6.dp))

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

        Spacer(Modifier.height(16.dp))
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
            showLivestreamDialog = { showLivestreamDialog = true },
            showNasBackupDialog = { viewModel.fetchNasConfigBackups(); showNasBackupDialog = true },
            showDiskHealthDialog = { showDiskHealthDialog = true },
            showSleepScheduleDialog = { showSleepScheduleDialog = true },
            showBandwidthDialog = { showBandwidthDialog = true },
            showUsbImportDialog = { viewModel.fetchUsbImportStatus(); showUsbImportDialog = true },
            showDownloadDialog = { showDownloadDialog = true },
            showSmbDialog = { viewModel.fetchSmbStatus(); showSmbDialog = true },
            showDuplicateScanDialog = { showDuplicateScanDialog = true }
        )
    }
    DuplicateScanGlobalUI(viewModel, mContext)
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
private fun insightRate(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return if (idx == 0) "${value.toInt()} ${units[idx]}" else "%.1f %s".format(java.util.Locale.US, value, units[idx])
}

@Composable
fun NasInsightsSummaryCard(
    viewModel: WebDavViewModel,
    onOpen: () -> Unit
) {
    val insight = viewModel.nasInsights
    val modeColor = when (insight.workloadMode) {
        "protect" -> AccentRed
        "balanced" -> AccentOrange
        else -> AccentGreen
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoGraph, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("PHÂN TÍCH HỆ THỐNG", color = PanelTitleCyan, fontSize = PanelTitleSize, fontWeight = FontWeight.Black, letterSpacing = PanelTitleLetterSpacing)
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(modeColor.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    val displayMode = when(insight.workloadMode.lowercase()) {
                        "normal" -> "BÌNH THƯỜNG"
                        "balanced" -> "CÂN BẰNG TẢI"
                        "protect" -> "BẢO VỆ HỆ THỐNG"
                        else -> insight.workloadMode.uppercase()
                    }
                    Text(displayMode, color = modeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightMiniStat("HDD", "${insight.hddScore}/100", "${insight.hddTempC}°C", AccentGreen, Modifier.weight(1f))
                InsightMiniStat("eMMC", "${insight.emmcRootPercent}%", "log ${insight.emmcLogPercent}%", if (insight.emmcWarnings.isEmpty()) AccentCyan else AccentOrange, Modifier.weight(1f))
                InsightMiniStat("Ghi HDD", insightRate(insight.diskWriteBps), "đọc ${insightRate(insight.diskReadBps)}", AccentPurple, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val summary = insight.maintenanceActions.firstOrNull()?.detail
                ?: insight.workloadRecommendation.ifBlank { "Đang chờ dữ liệu phân tích NAS." }
            Text(summary, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (insight.flowTasks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val task = insight.flowTasks.first()
                Text("${task.label}: ${task.file.ifBlank { "đang thực thi" }}", color = AccentCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InsightMiniStat(title: String, value: String, sub: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF171922), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(title, color = TextSecondary, fontSize = 10.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GaugeCard(
    title: String,
    value: String,
    subValue: String? = null,
    icon: ImageVector,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    overridePercent: Float? = null,
    label: String? = null,
    onClick: (() -> Unit)? = null
) {
    val numericValue = overridePercent ?: (Regex("[^0-9.]").replace(value, "").toFloatOrNull() ?: 0f)
    val progress = (numericValue / 100f).coerceIn(0f, 1f)
    
    // Status từ phần trăm (CPU%/RAM%/Disk%)
    val pctRank = when {
        progress >= 0.90f -> 2
        progress >= 0.70f -> 1
        else -> 0
    }
    // Status từ nhiệt độ (nếu subValue có °C) — dùng cùng ngưỡng như line chart và text subValue:
    //   CPU temp: >=80 đỏ, >=60 vàng. HDD/SMART temp: >=55 đỏ, >=45 vàng.
    val tempRank: Int = if (!subValue.isNullOrBlank() && (subValue.contains("°C") || subValue.contains("°C") || subValue.contains("°"))) {
        val tempVal = Regex("[^0-9.]").replace(subValue, "").toFloatOrNull() ?: 0f
        val isDisk = title == "S.M.A.R.T" || title == "HDD"
        when {
            isDisk && tempVal >= 55f -> 2
            isDisk && tempVal >= 45f -> 1
            !isDisk && tempVal >= 80f -> 2
            !isDisk && tempVal >= 60f -> 1
            else -> 0
        }
    } else -1
    val accentColor = if (title == "S.M.A.R.T") {
        gradientColors.first()
    } else {
        // Lay trang thai NANG HON giua phan tram va nhiet do de vong tron dong bo voi line chart.
        when (maxOf(pctRank, tempRank)) {
            2 -> Color(0xFFEF5350) // Đỏ
            1 -> Color(0xFFFFA726) // Vàng
            else -> Color(0xFF66BB6A) // Xanh
        }
    }

    Card(
        modifier = if (onClick != null) modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ) else modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 8.dp),
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
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
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
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradientColors))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Icon(icon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
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
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 10.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    modifier = Modifier.graphicsLayer { scaleX = 0.7f; scaleY = 0.7f }
                )
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
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
    showLivestreamDialog: () -> Unit,
    showNasBackupDialog: () -> Unit = {},
    showDiskHealthDialog: () -> Unit = {},
    showSleepScheduleDialog: () -> Unit = {},
    showBandwidthDialog: () -> Unit = {},
    showUsbImportDialog: () -> Unit = {},
    showDownloadDialog: () -> Unit = {},
    showSmbDialog: () -> Unit = {},
    showDuplicateScanDialog: () -> Unit = {}
) {
    var isBiometricEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("biometric_enabled", false)) }
    var isAutoBackupEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_backup", false)) }
    var deleteAfterBackup by remember { mutableStateOf(sharedPrefs.getBoolean("delete_after_backup", false)) }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { DashboardCompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            Text("CÔNG CỤ HỆ THỐNG", fontSize = PanelTitleSize, fontWeight = FontWeight.Black, color = PanelTitleCyan, letterSpacing = PanelTitleLetterSpacing, modifier = Modifier.padding(bottom = 8.dp))

            var showBiometricSettings by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "Thùng Rác",
                    subtitle = "Khôi phục tệp bị xoá",
                    icon = Icons.Default.Delete,
                    color = Color(0xFFEF5350),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); onOpenTrash() }
                )
                SettingsMenuCard(
                    title = "Khóa Sinh trắc học",
                    subtitle = if (isBiometricEnabled) {
                        val sec = sharedPrefs.getInt("biometric_lock_delay_sec", 10)
                        val delayLabel = when {
                            sec == 0 -> "khoá ngay"
                            sec < 60 -> "sau ${sec}s"
                            else -> "sau ${sec / 60}m"
                        }
                        "Đã bật — $delayLabel khi vào nền"
                    } else "Vân tay / FaceID — chưa bật",
                    icon = Icons.Default.Lock,
                    color = AccentPurple,
                    modifier = Modifier.weight(1f),
                    checked = isBiometricEnabled,
                    onClick = { showBiometricSettings = true }
                )
            }
            Spacer(Modifier.height(8.dp))
            if (showBiometricSettings) {
                com.nas.naswebdav.ui.dialogs.BiometricSettingsDialog(
                    viewModel = viewModel,
                    sharedPrefs = sharedPrefs,
                    onDismiss = {
                        showBiometricSettings = false
                        // Re-read from SharedPrefs to update card subtitle
                        isBiometricEnabled = sharedPrefs.getBoolean("biometric_enabled", false)
                    }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "Auto-Backup",
                    subtitle = if (deleteAfterBackup) "Copy & xoá gốc" else "Chỉ copy",
                    icon = Icons.Default.Sync,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f),
                    checked = isAutoBackupEnabled,
                    onClick = { onDismiss(); showAutoBackupDialog() }
                )
                SettingsMenuCard(
                    title = "Sao lưu cấu hình NAS",
                    subtitle = "Config + watcher + cookies",
                    icon = Icons.Default.SettingsBackupRestore,
                    color = Color(0xFF66BB6A),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showNasBackupDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "USB Import",
                    subtitle = "Tự copy ổ USB 3.0",
                    icon = Icons.Default.Usb,
                    color = Color(0xFF26A69A),
                    modifier = Modifier.weight(1f),
                    checked = viewModel.usbImportState.settings.enabled,
                    onClick = { onDismiss(); showUsbImportDialog() }
                )
                SettingsMenuCard(
                    title = "Sức khoẻ ổ cứng",
                    subtitle = "SMART + dmesg + điểm",
                    icon = Icons.Default.HealthAndSafety,
                    color = Color(0xFFFFA726),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showDiskHealthDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "Lịch ngủ NAS",
                    subtitle = "HDD spindown ngoài giờ",
                    icon = Icons.Default.Bedtime,
                    color = Color(0xFF7E57C2),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showSleepScheduleDialog() }
                )
                SettingsMenuCard(
                    title = "Giới hạn upload",
                    subtitle = "Tránh nghẽn mạng",
                    icon = Icons.Default.Speed,
                    color = Color(0xFF42A5F5),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showBandwidthDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.checkDockerStatus() }
                SettingsMenuCard(
                    title = "Docker / qBittorrent",
                    subtitle = if (viewModel.isTogglingDocker) "Đang xử lý..." else if (viewModel.isDockerRunning) "Đang thực thi" else "Đã ngắt",
                    icon = Icons.Default.ViewInAr,
                    color = Color(0xFF1E88E5),
                    modifier = Modifier.weight(1f),
                    checked = viewModel.isDockerRunning,
                    onClick = { viewModel.toggleDockerPower(!viewModel.isDockerRunning) }
                )
                SettingsMenuCard(
                    title = "Tải BitTorrent",
                    subtitle = "Magnet, URL, tệp .torrent",
                    icon = Icons.Default.CloudDownload,
                    color = Color(0xFF26A69A),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showDownloadDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "Nhật ký hệ thống",
                    subtitle = "Lịch sử tiến trình",
                    icon = Icons.Default.Assignment,
                    color = AccentCyan,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.loadSystemLogs()
                        onDismiss()
                        viewModel.showLogDialog = true
                    }
                )
                SettingsMenuCard(
                    title = "Quét trùng lặp",
                    subtitle = "Dọn dẹp không gian",
                    icon = Icons.Default.ContentCopy,
                    color = Color(0xFF29B6F6),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showDuplicateScanDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "LAN Whitelist",
                    subtitle = "IP LAN truy cập thẳng",
                    icon = Icons.Default.Wifi,
                    color = Color(0xFF66BB6A),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showLanWhitelistDialog() }
                )
                SettingsMenuCard(
                    title = "Ghi Livestream",
                    subtitle = if (viewModel.activeLivestreams.isNotEmpty()) "Đang ghi ${viewModel.activeLivestreams.size} kênh" else "TikTok / Facebook / YouTube",
                    icon = Icons.Default.Videocam,
                    color = Color(0xFFEE1D52),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showLivestreamDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "Dọn thùng rác",
                    subtitle = "Xoá rác cũ hơn 30 ngày",
                    icon = Icons.Default.DeleteSweep,
                    color = Color(0xFFEF5350),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); viewModel.cleanTrashOnDemand(context, maxAgeDays = 30) }
                )
                SettingsMenuCard(
                    title = "Ổ đĩa LAN (SMB)",
                    subtitle = if (viewModel.isSmbEnabled) "Đang bật — NAS_Data" else "Tắt — bấm để cấu hình",
                    icon = Icons.Default.Dns,
                    color = Color(0xFFFF9800),
                    modifier = Modifier.weight(1f),
                    checked = viewModel.isSmbEnabled,
                    onClick = { onDismiss(); showSmbDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.fetchThumbnailAudit() }
                val thumbAudit by viewModel.thumbnailAudit.collectAsState()
                SettingsMenuCard(
                    title = "Kiểm tra Thumbnail",
                    subtitle = if (thumbAudit != null) {
                        if (thumbAudit!!.running) "Đang quét..." else "Thiếu ${thumbAudit!!.missing} / Tổng ${thumbAudit!!.total}"
                    } else "Thống kê & Quét",
                    icon = Icons.Default.PhotoLibrary,
                    color = Color(0xFFAB47BC),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (thumbAudit == null || (!thumbAudit!!.running && thumbAudit!!.missing > 0)) {
                            viewModel.triggerThumbnailScan()
                            android.widget.Toast.makeText(context, "Đã gửi lệnh quét Thumbnail vào hệ thống ngầm!", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.fetchThumbnailAudit()
                        }
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


private fun parseProfileSizeBytes(raw: String): Long {
    val value = raw.replace(",", ".").replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: return 0L
    val upper = raw.uppercase(java.util.Locale.US)
    val multiplier = when {
        "TIB" in upper -> 1024.0 * 1024.0 * 1024.0 * 1024.0
        "TB" in upper -> 1000.0 * 1000.0 * 1000.0 * 1000.0
        "GIB" in upper -> 1024.0 * 1024.0 * 1024.0
        "GB" in upper -> 1000.0 * 1000.0 * 1000.0
        "MIB" in upper -> 1024.0 * 1024.0
        "MB" in upper -> 1000.0 * 1000.0
        else -> 1.0
    }
    return (value * multiplier).toLong().coerceAtLeast(0L)
}

private fun profileSizeLabel(bytes: Long): String {
    if (bytes <= 0L) return "Chưa rõ"
    val tib = bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0)
    return if (tib >= 1.0) "%.2f TiB".format(java.util.Locale.US, tib)
    else com.nas.naswebdav.utils.FormatUtils.formatBytes(bytes)
}

private fun profilePercent(raw: String): Float =
    raw.replace("%", "").trim().toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f

private fun profileTempValue(raw: String): Float? =
    raw.replace(Regex("[^0-9.]"), "").toFloatOrNull()?.takeIf { it > 0f }

private fun profileParseLoggedSizeBytes(message: String): Long {
    val match = Regex("""\(([\d.,]+)\s*(B|KB|MB|GB|TB)\)""", RegexOption.IGNORE_CASE).find(message) ?: return 0L
    val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return 0L
    val unit = match.groupValues[2].uppercase(java.util.Locale.US)
    val multiplier = when (unit) {
        "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
        "GB" -> 1024.0 * 1024.0 * 1024.0
        "MB" -> 1024.0 * 1024.0
        "KB" -> 1024.0
        else -> 1.0
    }
    return (value * multiplier).toLong().coerceAtLeast(0L)
}

private fun profileIsToday(timestamp: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    val todayYear = cal.get(java.util.Calendar.YEAR)
    val todayDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = timestamp
    return cal.get(java.util.Calendar.YEAR) == todayYear &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == todayDay
}

private fun isNasTargetDisk(disk: OmvDiskInfo): Boolean {
    val blob = "${disk.name} ${disk.device} ${disk.model} ${disk.serial}".uppercase(java.util.Locale.US)
    return disk.isTargetHdd ||
        disk.serial.equals("X6N7KALWFVLC", ignoreCase = true) ||
        disk.device == "/dev/sda" ||
        disk.name == "sda" ||
        "TOSHIBA" in blob ||
        "MG04" in blob ||
        "N300" in blob
}

private fun selectNasTargetDisk(disks: List<OmvDiskInfo>): OmvDiskInfo? =
    disks.firstOrNull { isNasTargetDisk(it) } ?:
        disks.firstOrNull { !it.isRoot && !it.isUsbImport && it.name != "sdb" && it.device != "/dev/sdb" }

private fun profileDiskKey(disk: OmvDiskInfo?, fallback: String): String {
    val serial = disk?.serial?.trim().orEmpty()
    val model = disk?.model?.trim().orEmpty()
    return when {
        serial.isNotBlank() -> "disk_${serial.replace(Regex("[^A-Za-z0-9_.-]+"), "_")}"
        model.isNotBlank() -> "disk_${model.replace(Regex("[^A-Za-z0-9_.-]+"), "_")}"
        else -> "disk_${fallback.replace(Regex("[^A-Za-z0-9_.-]+"), "_")}"
    }
}

private fun profileDiskEnduranceTbPerYear(model: String): Int? {
    val upper = model.uppercase(java.util.Locale.US)
    return when {
        "MG04" in upper -> 550
        Regex("""\bMG\d{2}""").containsMatchIn(upper) -> 550
        "N300" in upper -> 180
        "MN04" in upper || "MN05" in upper -> 180
        "MN08" in upper || "MN09" in upper || "MN10" in upper -> 300
        "IRONWOLF" in upper -> 180
        "RED" in upper || "WD" in upper -> 180
        else -> null
    }
}

private fun profileStatusColor(status: String): Color = when {
    status.contains("nguy", ignoreCase = true) ||
        status.contains("không nên", ignoreCase = true) ||
        status.contains("lỗi", ignoreCase = true) -> AccentRed
    status.contains("cảnh", ignoreCase = true) ||
        status.contains("theo dõi", ignoreCase = true) ||
        status.contains("cao", ignoreCase = true) -> AccentOrange
    status.contains("chưa", ignoreCase = true) -> TextSecondary
    else -> AccentGreen
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DiskProfileBottomSheet(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nas_hardware_profile", Context.MODE_PRIVATE) }
    var showTrackingConfirm by remember { mutableStateOf(false) }
    var showResetTrackingConfirm by remember { mutableStateOf(false) }
    var writePanelExpanded by remember { mutableStateOf(false) }
    var operationMode by remember { mutableStateOf(prefs.getString("operation_mode", "balanced") ?: "balanced") }
    val hddDisk = viewModel.systemStatus.diskParts
        .filter { it.mount != "/" && !it.mount.startsWith("/mnt/usb-import") }
        .sortedByDescending { it.mount.startsWith("/srv/dev-disk-by-label-data") }
        .maxByOrNull { parseProfileSizeBytes(it.total) }
    val omvDisk = selectNasTargetDisk(viewModel.omvOverview.disks)
    val activeDiskKey = profileDiskKey(omvDisk, hddDisk?.mount ?: "unknown")
    var installedAt by remember(activeDiskKey) {
        val serialValue = prefs.getLong("${activeDiskKey}_installed_at", 0L)
        val legacyValue = prefs.getLong("toshiba_n300_installed_at", 0L)
        mutableStateOf(if (serialValue > 0L) serialValue else legacyValue)
    }
    val isTrackingNewDisk = installedAt > 0L
    val usedPercent = hddDisk?.percent ?: profilePercent(viewModel.systemStatus.disk)
    val remainingPercent = (100f - usedPercent).coerceIn(0f, 100f)
    val fsBytes = viewModel.omvOverview.filesystems
        .filter { it.mountpoint != "/" && !it.mountpoint.startsWith("/mnt/usb-import") }
        .sortedByDescending { it.mountpoint.startsWith("/srv/dev-disk-by-label-data") }
        .maxOfOrNull { it.sizeBytes }
        ?: 0L
    val totalBytes = listOf(
        fsBytes,
        parseProfileSizeBytes(hddDisk?.total ?: ""),
        parseProfileSizeBytes(omvDisk?.size ?: "")
    ).maxOrNull() ?: 0L
    val totalTiB = if (totalBytes > 0L) totalBytes / (1024.0 * 1024.0 * 1024.0 * 1024.0) else 0.0
    val estimatedFreeTiB = (totalTiB * remainingPercent / 100.0).toFloat()
    val diskModel = omvDisk?.model?.takeIf { it.isNotBlank() } ?: "Ổ dữ liệu NAS"
    val diskSerial = omvDisk?.serial?.takeIf { it.isNotBlank() } ?: "Chưa đọc được serial"
    val enduranceTbPerYear = profileDiskEnduranceTbPerYear(diskModel)
    val dailyBudgetGb = enduranceTbPerYear?.let { ((it * 1024f) / 365f).toInt() } ?: 0
    val remainingDays = if (isTrackingNewDisk && dailyBudgetGb > 0) ((estimatedFreeTiB * 1024f) / dailyBudgetGb).toInt().coerceAtLeast(0) else null
    val activeRecordings = if (isTrackingNewDisk) viewModel.activeLivestreams.size else 0
    val completedLivestreamLogsToday = viewModel.systemLogsList.filter { log ->
        log.module.equals("Livestream", ignoreCase = true) &&
            log.message.contains("đã ghi xong", ignoreCase = true) &&
            profileIsToday(log.timestamp)
    }
    val completedLivestreamSessionsToday = if (isTrackingNewDisk) completedLivestreamLogsToday.count { log ->
        !log.message.contains("(0 B)", ignoreCase = true)
    } else 0
    val completedLivestreamBytesToday = if (isTrackingNewDisk) completedLivestreamLogsToday.sumOf { log ->
        profileParseLoggedSizeBytes(log.message)
    } else 0L
    val livestreamSessionsToday = activeRecordings + completedLivestreamSessionsToday
    val smartTemp = viewModel.smartInfo.temperature
        .replace("Â°C", "°C")
        .replace("--", "Chưa có dữ liệu")
    val smartStatus = viewModel.smartInfo.status
    val diskHealth = viewModel.diskHealthCurrent
    val healthScore = diskHealth?.score
    val trialStatus = when {
        isTrackingNewDisk && healthScore != null && healthScore < 60 -> "Cần kiểm tra"
        !isTrackingNewDisk -> "Chưa phân tích"
        smartStatus.contains("PASSED", ignoreCase = true) || smartStatus.equals("OK", ignoreCase = true) -> "Ổn định"
        smartStatus.contains("Đang tải", ignoreCase = true) -> "Đang cập nhật"
        smartStatus.contains("Không", ignoreCase = true) || smartStatus.contains("Lỗi", ignoreCase = true) -> "Cần kiểm tra"
        else -> "Sẵn sàng theo dõi"
    }
    val installedDate = if (installedAt > 0L) {
        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("vi", "VN")).format(java.util.Date(installedAt))
    } else {
        "Chưa đặt"
    }
    val trialDays = if (installedAt > 0L) {
        ((System.currentTimeMillis() - installedAt) / 86_400_000L).coerceIn(0L, 999L).toInt()
    } else 0
    val trialLabel = when {
        installedAt == 0L -> "Chưa bắt đầu"
        trialDays <= 7 -> "Ngày $trialDays/7"
        else -> "Đã hoàn tất"
    }
    val tempValue = if (isTrackingNewDisk) profileTempValue(smartTemp) ?: diskHealth?.tempC?.toFloat() else null
    val tempStatus = when {
        !isTrackingNewDisk -> "0°C"
        tempValue == null -> "Chưa có dữ liệu"
        tempValue >= 50f -> "Nóng"
        tempValue >= 45f -> "Cần theo dõi"
        else -> "Ổn định"
    }
    val downloadTasks = if (isTrackingNewDisk) viewModel.systemStatus.torrents.count { torrent ->
        val state = torrent.state
        state.contains("DL", ignoreCase = false) || state == "downloading" || state == "stalledDL" || state == "forcedDL" || state == "metaDL"
    } else 0
    val heavyWriteTasks = activeRecordings + downloadTasks + if (isTrackingNewDisk && viewModel.isAutoBackupRunning) 1 else 0
    val estimatedActiveWriteGb = activeRecordings * 8 + downloadTasks * 20 + if (isTrackingNewDisk && viewModel.isAutoBackupRunning) 30 else 0
    val completedLivestreamWriteGb = (completedLivestreamBytesToday / (1024.0 * 1024.0 * 1024.0)).toInt()
    val estimatedActualWriteGb = estimatedActiveWriteGb + completedLivestreamWriteGb
    val actualForecastDays = if (isTrackingNewDisk && estimatedActualWriteGb > 0) ((estimatedFreeTiB * 1024f) / estimatedActualWriteGb).toInt().coerceAtLeast(0) else remainingDays
    val monthlyBudgetTb = enduranceTbPerYear?.let { it / 12 } ?: 0
    val backupTasks = if (isTrackingNewDisk && viewModel.isAutoBackupRunning) 1 else 0
    val writeRiskLabel = when {
        !isTrackingNewDisk -> "Chưa phân tích"
        heavyWriteTasks >= 4 -> "Khối lượng ghi cao"
        heavyWriteTasks >= 2 -> "Khối lượng ghi trung bình"
        heavyWriteTasks == 1 -> "Khối lượng ghi thấp"
        else -> "Trạng thái rảnh (Không ghi)"
    }
    val cpuLoad = profilePercent(viewModel.systemStatus.cpu)
    val ramLoad = profilePercent(viewModel.systemStatus.ramPercent)
    val storageUsage = viewModel.storageFolderUsage
    val trashUsage = storageUsage.firstOrNull { it.path == ".trash" }
    val trashWarning = if ((trashUsage?.sizeBytes ?: 0L) > 50L * 1024L * 1024L * 1024L) "Nên dọn thùng rác" else "Thùng rác ổn"
    val fillWarning = when {
        !isTrackingNewDisk -> "Chưa phân tích"
        actualForecastDays != null && actualForecastDays in 1..7 -> "Cảnh báo 7 ngày"
        actualForecastDays != null && actualForecastDays in 8..14 -> "Cảnh báo 14 ngày"
        actualForecastDays != null && actualForecastDays in 15..30 -> "Cảnh báo 30 ngày"
        else -> "Dung lượng ổn"
    }
    val livestreamReady = when {
        !isTrackingNewDisk -> "Chưa phân tích"
        healthScore != null && healthScore < 60 -> "SMART cảnh báo"
        remainingPercent < 5f -> "Không nên ghi"
        tempValue != null && tempValue >= 50f -> "Nhiệt độ cao"
        heavyWriteTasks >= 4 -> "Tải hệ thống cao"
        cpuLoad >= 85f || ramLoad >= 90f -> "Hệ thống tải cao"
        else -> "Sẵn sàng ghi"
    }
    val operationAdvice = when (livestreamReady) {
        "Sẵn sàng ghi" -> "NAS đủ điều kiện ghi livestream theo dữ liệu hiện tại."
        "Chưa phân tích" -> "Hãy đặt mốc theo dõi cho ổ dữ liệu hiện tại: $diskModel."
        "Không nên ghi" -> "Dung lượng trống thấp, nên dọn dữ liệu trước khi ghi thêm."
        "Nhiệt độ cao" -> "Nên bật quạt hoặc giảm tác vụ ghi cho đến khi ổ mát hơn."
        "Tải hệ thống cao" -> "Khuyến nghị hạn chế khởi tạo luồng ghi hình mới khi có nhiều tiến trình phân bổ dữ liệu."
        "SMART cảnh báo" -> "SMART/disk health đang cảnh báo, nên kiểm tra ổ trước khi ghi thêm."
        else -> "Nên chờ CPU/RAM ổn định trước khi bắt đầu ghi livestream."
    }
    val nasHealthState = when {
        !isTrackingNewDisk -> "Chưa phân tích ổ"
        livestreamReady == "Sẵn sàng ghi" && ramLoad < 80f && cpuLoad < 75f -> "Ổn định"
        livestreamReady == "Không nên ghi" || livestreamReady == "SMART cảnh báo" || ramLoad >= 90f || cpuLoad >= 90f -> "Không nên ghi thêm"
        else -> "Cần theo dõi"
    }
    val quietWindowAdvice = if (heavyWriteTasks > 0) "Khuyến nghị tạm ngưng quét dữ liệu/ảnh thu nhỏ." else "Hệ thống sẵn sàng cho các tác vụ bảo trì định kỳ."
    val ramGuardAdvice = when {
        ramLoad >= 90f -> "Mức sử dụng RAM ở ngưỡng nguy hiểm, yêu cầu tinh giản tác vụ nền."
        ramLoad >= 80f -> "RAM cao, theo dõi trước khi mở thêm tác vụ."
        else -> "RAM phù hợp cho vận hành hiện tại."
    }
    val operationModeLabel = when (operationMode) {
        "stream" -> "Ưu tiên ghi livestream"
        "eco" -> "Tiết kiệm tài nguyên"
        else -> "Cân bằng"
    }
    val realWriteDataLabel = when {
        !isTrackingNewDisk -> "Chưa phân tích"
        estimatedActualWriteGb > 0 -> "~$estimatedActualWriteGb GB/ngày"
        else -> "Đang nhàn rỗi"
    }
    val actualForecastLabel = when {
        !isTrackingNewDisk -> "Chưa phân tích"
        actualForecastDays != null -> "~$actualForecastDays ngày"
        estimatedActualWriteGb == 0 -> "Không có tải ghi"
        else -> "Chưa rõ"
    }
    val forecastSubtitle = when {
        !isTrackingNewDisk -> "Chưa bắt đầu theo dõi"
        estimatedActualWriteGb > 0 -> "Theo tải ghi hiện tại"
        dailyBudgetGb > 0 -> "Theo ngân sách workload"
        else -> "Thiếu thông số workload"
    }
    val smartRiskText = when {
        healthScore == null -> smartStatus
        healthScore >= 80 -> "Tốt ${healthScore}/100"
        healthScore >= 60 -> "Cảnh báo ${healthScore}/100"
        else -> "Nguy hiểm ${healthScore}/100"
    }
    fun profileChecklistStatus(targetDay: Int): String = when {
        !isTrackingNewDisk -> "Chưa bắt đầu"
        trialDays < targetDay -> "Còn ${targetDay - trialDays} ngày"
        trialDays == targetDay -> "Đến hạn"
        else -> "Quá hạn ${trialDays - targetDay} ngày"
    }

    LaunchedEffect(Unit) {
        viewModel.fetchSmartData()
        viewModel.fetchDiskHealth()
        viewModel.fetchOmvOverview()
        viewModel.fetchStorageUsage()
        viewModel.loadSystemLogs()
    }

    if (showTrackingConfirm) {
        AlertDialog(
            onDismissRequest = { showTrackingConfirm = false },
            containerColor = Color(0xFF15161D),
            title = { Text("Xác nhận theo dõi ổ mới", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Chỉ đặt mốc theo dõi sau khi đã xác nhận ổ dữ liệu hiện tại là ổ cần theo dõi. Mốc này gắn với model/serial ổ để tính checklist 24 giờ, 7 ngày và 30 ngày.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    prefs.edit()
                        .putLong("${activeDiskKey}_installed_at", now)
                        .putString("${activeDiskKey}_model", diskModel)
                        .putString("${activeDiskKey}_serial", diskSerial)
                        .apply()
                    viewModel.logUserAction("DiskProfile", "Thiết lập điểm kiểm soát ổ đĩa: $diskModel ($diskSerial).")
                    installedAt = now
                    showTrackingConfirm = false
                }) { Text("Bắt đầu theo dõi", color = AccentGreen, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showTrackingConfirm = false }) { Text("Hủy", color = TextSecondary) }
            }
        )
    }
    if (showResetTrackingConfirm) {
        AlertDialog(
            onDismissRequest = { showResetTrackingConfirm = false },
            containerColor = Color(0xFF15161D),
            title = { Text("Đặt lại mốc theo dõi", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Thao tác này đưa hồ sơ ổ mới về trạng thái chưa theo dõi và các số liệu sẽ trở lại 0 cho đến khi đặt mốc mới.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit()
                        .remove("${activeDiskKey}_installed_at")
                        .remove("${activeDiskKey}_model")
                        .remove("${activeDiskKey}_serial")
                        .apply()
                    viewModel.logUserAction("DiskProfile", "Tái thiết lập điểm kiểm soát ổ đĩa: $diskModel ($diskSerial).", "WARNING")
                    installedAt = 0L
                    showResetTrackingConfirm = false
                }) { Text("Đặt lại", color = AccentOrange, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showResetTrackingConfirm = false }) { Text("Hủy", color = TextSecondary) }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101216),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { DashboardCompactBottomSheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Hồ sơ ổ cứng mới", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("$diskModel • $diskSerial", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                    Text(trialStatus, color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!isTrackingNewDisk) showTrackingConfirm = true
                        }
                    ) {
                        Text(if (isTrackingNewDisk) "Đang theo dõi" else "Đặt theo dõi hôm nay", color = AccentCyan, fontSize = 10.sp)
                    }
                    if (isTrackingNewDisk) {
                        Box(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showResetTrackingConfirm = true }
                        ) {
                            Text("Đặt lại mốc", color = AccentOrange, fontSize = 10.sp)
                        }
                    }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HardwareMetricCell(
                    title = "Dung lượng",
                    value = if (isTrackingNewDisk) profileSizeLabel(totalBytes) else "Chưa phân tích",
                    subtitle = if (isTrackingNewDisk) "Trống ~%.2f TiB • dùng %.0f%%".format(java.util.Locale.US, estimatedFreeTiB, usedPercent) else "Nhấn đặt mốc để bắt đầu",
                    icon = Icons.Default.Inventory2,
                    color = AccentCyan,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Ngân sách ghi",
                    value = if (isTrackingNewDisk && enduranceTbPerYear != null) "$enduranceTbPerYear TB/năm" else "Chưa rõ",
                    subtitle = if (isTrackingNewDisk && dailyBudgetGb > 0) "~$dailyBudgetGb GB/ngày" else "Không có thông số workload",
                    icon = Icons.Default.EditNote,
                    color = AccentOrange,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Livestream",
                    value = "$livestreamSessionsToday phiên",
                    subtitle = if (isTrackingNewDisk) "Đang ghi hình: $activeRecordings luồng • Đã hoàn tất: $completedLivestreamSessionsToday phiên" else "Chưa phân tích trên phân vùng mới",
                    icon = Icons.Default.Videocam,
                    color = AccentPink,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HardwareMetricCell(
                    title = "Ngày lắp ổ",
                    value = installedDate,
                    subtitle = "Tiến trình thử nghiệm: $trialLabel",
                    icon = Icons.Default.HealthAndSafety,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Nhiệt độ ổ",
                    value = tempStatus,
                    subtitle = if (isTrackingNewDisk) "Hiện tại: $smartTemp" else "Chưa phân tích",
                    icon = Icons.Default.EventAvailable,
                    color = AccentPurple,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Ghi dữ liệu cường độ cao",
                    value = "$heavyWriteTasks tiến trình",
                    subtitle = "Ghi hình: $activeRecordings luồng • Tải xuống: $downloadTasks phiên",
                    icon = Icons.Default.VerifiedUser,
                    color = Color(0xFF66BB6A),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HardwareMetricCell(
                    title = "Ghi hôm nay",
                    value = if (completedLivestreamBytesToday > 0L) com.nas.naswebdav.utils.FormatUtils.formatBytes(completedLivestreamBytesToday) else "~$estimatedActualWriteGb GB",
                    subtitle = if (completedLivestreamBytesToday > 0L) "Livestream đã hoàn tất hôm nay" else "Ước tính từ tác vụ",
                    icon = Icons.Default.Today,
                    color = AccentCyan,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Ngân sách tháng",
                    value = "$monthlyBudgetTb TB",
                    subtitle = enduranceTbPerYear?.let { "Theo $it TB/năm" } ?: "Chưa có thông số",
                    icon = Icons.Default.CalendarMonth,
                    color = AccentOrange,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Dự báo thực tế",
                    value = actualForecastLabel,
                    subtitle = forecastSubtitle,
                    icon = Icons.Default.QueryStats,
                    color = AccentPurple,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HardwareMetricCell(
                    title = "Cảnh báo đầy ổ",
                    value = fillWarning,
                    subtitle = if (isTrackingNewDisk) "Dự báo: $actualForecastLabel" else "Chưa bắt đầu theo dõi",
                    icon = Icons.Default.WarningAmber,
                    color = AccentOrange,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Sẵn sàng ghi",
                    value = livestreamReady,
                    subtitle = "CPU ${cpuLoad.toInt()}% • RAM ${ramLoad.toInt()}%",
                    icon = Icons.Default.PlayCircle,
                    color = if (livestreamReady == "Sẵn sàng ghi") AccentGreen else AccentOrange,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Dữ liệu ghi thật",
                    value = realWriteDataLabel,
                    subtitle = smartRiskText,
                    icon = Icons.Default.History,
                    color = profileStatusColor(smartRiskText),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171922), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TipsAndUpdates, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Khuyến nghị vận hành hôm nay", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(operationAdvice, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171922), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, null, tint = AccentPurple, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Chế độ vận hành", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(operationModeLabel, color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OperationModeChip("stream", "Ghi live", operationMode, prefs) {
                        operationMode = it
                        viewModel.logUserAction("DiskProfile", "Thay đổi hồ sơ hoạt động ổ cứng thành: $it.")
                    }
                    OperationModeChip("balanced", "Cân bằng", operationMode, prefs) {
                        operationMode = it
                        viewModel.logUserAction("DiskProfile", "Thay đổi hồ sơ hoạt động ổ cứng thành: $it.")
                    }
                    OperationModeChip("eco", "Tiết kiệm", operationMode, prefs) {
                        operationMode = it
                        viewModel.logUserAction("DiskProfile", "Thay đổi hồ sơ hoạt động ổ cứng thành: $it.")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HardwareMetricCell(
                    title = "Sức khỏe NAS",
                    value = nasHealthState,
                    subtitle = "CPU ${cpuLoad.toInt()}% • RAM ${ramLoad.toInt()}%",
                    icon = Icons.Default.MonitorHeart,
                    color = profileStatusColor(nasHealthState),
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Bảo vệ RAM thấp",
                    value = if (ramLoad >= 80f) "Đang theo dõi" else "Ổn định",
                    subtitle = ramGuardAdvice,
                    icon = Icons.Default.Memory,
                    color = if (ramLoad >= 80f) AccentOrange else AccentGreen,
                    modifier = Modifier.weight(1f)
                )
                HardwareMetricCell(
                    title = "Lịch yên tĩnh",
                    value = if (heavyWriteTasks > 0) "Nên bật" else "Chưa cần",
                    subtitle = quietWindowAdvice,
                    icon = Icons.Default.Bedtime,
                    color = AccentPurple,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171922), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Theo dõi thư mục lớn", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(if (viewModel.isFetchingStorageUsage) "Đang tải" else trashWarning, color = TextSecondary, fontSize = 10.sp)
                }
                Spacer(Modifier.height(6.dp))
                if (storageUsage.isEmpty()) {
                    Text("Chưa có dữ liệu thư mục. App sẽ tự tải khi NAS API sẵn sàng.", color = TextSecondary, fontSize = 11.sp)
                } else {
                    storageUsage.take(4).forEach { item ->
                        WriteTaskRow(item.name, "${item.size} • ${item.files} tệp", if (item.path == ".trash") AccentOrange else AccentCyan)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171922), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Summarize, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nhật ký vận hành hôm nay", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                WriteTaskRow("Livestream hôm nay", "$livestreamSessionsToday phiên", AccentPink)
                WriteTaskRow("Tải ghi hiện tại", writeRiskLabel, AccentOrange)
                WriteTaskRow("Trạng thái ghi", livestreamReady, if (livestreamReady == "Sẵn sàng ghi") AccentGreen else AccentOrange)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NewDiskChecklistItem("Sau 24 giờ", profileChecklistStatus(1), Icons.Default.Schedule, AccentCyan, Modifier.weight(1f))
                NewDiskChecklistItem("Sau 7 ngày", profileChecklistStatus(7), Icons.Default.FactCheck, AccentGreen, Modifier.weight(1f))
                NewDiskChecklistItem("Sau 30 ngày", profileChecklistStatus(30), Icons.Default.EventRepeat, AccentOrange, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171922), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { writePanelExpanded = !writePanelExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EditNote, null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("Tiến trình phân bổ dữ liệu", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(writeRiskLabel, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Icon(if (writePanelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
                androidx.compose.animation.AnimatedVisibility(visible = writePanelExpanded) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        WriteTaskRow("Livestream hôm nay", "$livestreamSessionsToday phiên", AccentPink)
                        WriteTaskRow("Đã hoàn tất hôm nay", "$completedLivestreamSessionsToday phiên • ${com.nas.naswebdav.utils.FormatUtils.formatBytes(completedLivestreamBytesToday)}", AccentGreen)
                        WriteTaskRow("Torrent đang tải", "$downloadTasks tác vụ", AccentCyan)
                        WriteTaskRow("Sao lưu nền", "$backupTasks tác vụ", AccentGreen)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Các chỉ số mới chỉ dùng dữ liệu hiện có để dự báo, không thay đổi tác vụ ghi, WebDAV, đăng nhập hoặc lịch nền.",
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
private fun OperationModeChip(
    mode: String,
    label: String,
    selectedMode: String,
    prefs: android.content.SharedPreferences,
    onSelect: (String) -> Unit
) {
    val selected = mode == selectedMode
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AccentCyan.copy(alpha = 0.18f) else Color(0xFF101216))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                prefs.edit().putString("operation_mode", mode).apply()
                onSelect(mode)
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) AccentCyan else TextSecondary, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun NewDiskChecklistItem(
    title: String,
    status: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF171922), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(status, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Nhắc kiểm tra S.M.A.R.T", color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WriteTaskRow(title: String, value: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HardwareMetricCell(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF171922), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SystemStatusCards(
    viewModel: WebDavViewModel,
    mContext: android.content.Context,
    onOpenAutoBackup: () -> Unit = {},
    onOpenLivestream: () -> Unit = {},
    onOpenUsbImport: () -> Unit = {},
    onOpenDuplicateScan: () -> Unit = {}
) {
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
    
    // 4. Livestream — poll định kỳ để phát hiện job do Watcher daemon tự bắt
    val activeStreams = viewModel.activeLivestreams
    LaunchedEffect(Unit) {
        // Lần đầu: đồng bộ đầy đủ (bao gồm WorkManager restore)
        viewModel.syncLivestreamStateWithServer(mContext)
        while (true) {
            kotlinx.coroutines.delay(30_000L) // poll nhẹ mỗi 30 giây, không flicker
            viewModel.fetchLivestreamStatusOnly(mContext)
            viewModel.fetchTikTokLiveWatch(mContext)
        }
    }
    val usbImport = viewModel.usbImportState
    val usbImportIsActive = usbImport.status == "copying" || usbImport.status == "cancelling"
    LaunchedEffect(Unit) {
        viewModel.fetchUsbImportStatus()
    }
    LaunchedEffect(usbImport.status) {
        while (usbImport.status == "copying" || usbImport.status == "cancelling") {
            kotlinx.coroutines.delay(1000)
            viewModel.fetchUsbImportStatus()
        }
    }
    val usbImportProgress = if (usbImport.bytesTotal > 0L) {
        usbImport.bytesProcessed.toFloat() / usbImport.bytesTotal.toFloat()
    } else if (usbImport.filesTotal > 0) {
        (usbImport.filesDone + usbImport.filesSkipped + usbImport.filesFailed).toFloat() / usbImport.filesTotal.toFloat()
    } else 0f
    fun usbImportEtaLabel(seconds: Long): String {
        if (seconds <= 0L) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    // Thumbnail generator is an internal maintenance job. Keep it out of the
    // user-facing background task panel so livestream/sync progress stays clean.
    val showThumbTask = true
    val hasAnyTasks = dupIsActive || autoBackupIsActive || usbImportIsActive || activeStreams.isNotEmpty() || (showThumbTask && thumbIsActive)

    if (!hasAnyTasks) return

    Column(Modifier.fillMaxWidth()) {
        // Mo doc quyen: panel mo dong bo voi ExclusivePanelState
        val tasksExpanded = ExclusivePanelState.current.value == "tasks"
        val activeCount = listOf(dupIsActive, autoBackupIsActive, usbImportIsActive, activeStreams.isNotEmpty()).count { it }
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(Modifier.padding(8.dp)) {
                // Header — nhấn để mở/đóng
                Row(
                    Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { ExclusivePanelState.toggle("tasks") },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("TÁC VỤ NỀN", fontSize = PanelTitleSize, color = PanelTitleCyan, fontWeight = FontWeight.Black, letterSpacing = PanelTitleLetterSpacing)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("$activeCount đang thực thi", fontSize = 9.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
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
                        if (showThumbTask && thumbIsActive) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(38.dp).background(Color(0xFFAB47BC).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PhotoLibrary, null, tint = Color(0xFFAB47BC), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Tạo ảnh thu nhỏ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(
                                        when {
                                            viewModel.thumbTotal > 0 && viewModel.thumbGenerated >= viewModel.thumbTotal -> "✅ Hoàn tất"
                                            viewModel.thumbPaused -> "⏸ Tạm dừng"
                                            viewModel.thumbRunning -> "▶️ Đang tạo thumbnail"
                                            else -> "💤 Tạm nghỉ"
                                        },
                                        fontSize = 11.sp,
                                        color = when {
                                            viewModel.thumbTotal > 0 && viewModel.thumbGenerated >= viewModel.thumbTotal -> Color(0xFF66BB6A)
                                            viewModel.thumbPaused -> Color(0xFFFFA726)
                                            viewModel.thumbRunning -> Color(0xFF66BB6A)
                                            else -> TextSecondary
                                        }
                                    )
                                }
                                if ((viewModel.thumbRunning || viewModel.thumbPaused) && !(viewModel.thumbTotal > 0 && viewModel.thumbGenerated >= viewModel.thumbTotal)) {
                                    IconButton(onClick = { viewModel.toggleThumbPause() }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            if (viewModel.thumbPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            null,
                                            tint = if (viewModel.thumbPaused) Color(0xFF66BB6A) else Color(0xFFFFA726),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.fetchThumbStatus() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Refresh, "Làm mới", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                            // Chi tiết thumbnail
                            if (viewModel.thumbLastFile.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tệp: " + viewModel.thumbLastFile.substringAfterLast("/"),
                                    fontSize = 10.sp, color = Color(0xFFAB47BC).copy(alpha = 0.85f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 50.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (thumbPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFFAB47BC), trackColor = Color(0xFF161616)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                // Số thumbnail đã tạo / tổng
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("✓ Đã tạo:", fontSize = 10.sp, color = TextSecondary)
                                    Text("${viewModel.thumbGenerated}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF66BB6A))
                                    Text("/ ${viewModel.thumbTotal}", fontSize = 10.sp, color = TextSecondary)
                                    val thumbMissing = viewModel.thumbTotal - viewModel.thumbGenerated
                                    if (thumbMissing > 0) {
                                        Text("• Còn ${thumbMissing} thiếu", fontSize = 10.sp, color = Color(0xFFFFA726))
                                    }
                                }
                                Text("%.1f%%".format(thumbPercent), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAB47BC))
                            }
                        }

                        if (showThumbTask && thumbIsActive && (dupIsActive || autoBackupIsActive || usbImportIsActive || activeStreams.isNotEmpty())) {
                            HorizontalDivider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 6.dp))
                        }

                        // --- DUPLICATE QUÉT ---
                        if (dupIsActive) {
                          Column(modifier = Modifier.fillMaxWidth().clickable { onOpenDuplicateScan() }.padding(vertical = 4.dp)) {
                            // Helper format time
                            fun fmtMs(ms: Long): String {
                                if (ms < 0) return "--:--"
                                val s = ms / 1000
                                val m = s / 60; val sec = s % 60
                                return "%02d:%02d".format(m, sec)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(Color(0xFF29B6F6).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF29B6F6), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Quét trùng lặp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    val dupStatusLabel = if (dupIsPaused) "⏸ Đã tạm dừng" else if (!dupIsRunning) "Chuẩn bị..." else "🟢 Đang quét — Bước $dupStageNum/${dupTotalStages}"
                                    Text(dupStatusLabel, fontSize = 11.sp, color = if (dupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A))
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
                            // Giai đoạn hiện tại
                            if (dupStage.isNotBlank() && dupStage != "Khởi động...") {
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    color = Color(0xFF29B6F6).copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        dupStage,
                                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                        color = Color(0xFF29B6F6),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // Mô tả giai đoạn chi tiết
                            if (dupStageDesc.isNotBlank()) {
                                Text(
                                    dupStageDesc,
                                    fontSize = 10.sp, color = TextSecondary.copy(alpha = 0.85f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    lineHeight = 13.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { dupPercent.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF29B6F6), trackColor = Color(0xFF161616)
                            )
                            Spacer(Modifier.height(6.dp))
                            // Hàng thống kê: số tệp + trùng + thời gian
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$dupScanned", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF29B6F6))
                                        Text("Tổng tệp", fontSize = 9.sp, color = TextSecondary)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$dupFound", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF5350))
                                        Text("Trùng lặp", fontSize = 9.sp, color = TextSecondary)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(fmtMs(dupElapsed), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF66BB6A))
                                        Text("Thời gian", fontSize = 9.sp, color = TextSecondary)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("%.1f%%".format(dupPercent * 100), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF29B6F6))
                                    if (dupEta >= 0) {
                                        Text("Ước tính: ${fmtMs(dupEta)}", fontSize = 9.sp, color = Color(0xFF4FC3F7))
                                    }
                                }
                            }
                        }
                          }

                        if (dupIsActive && (autoBackupIsActive || usbImportIsActive || activeStreams.isNotEmpty())) {
                            HorizontalDivider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 6.dp))
                        }

                        // --- AUTO BACKUP ---
                        if (autoBackupIsActive) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onOpenAutoBackup() },
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    Modifier.size(38.dp).background(Color(0xFF66BB6A).copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Sync, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Đồng Bộ NAS", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Spacer(Modifier.height(4.dp))
                                    
                                    val speed = if (viewModel.autoBackupElapsedTime > 1000L) {
                                        "%.1f file/s".format(viewModel.autoBackupProcessedCount * 1000f / viewModel.autoBackupElapsedTime)
                                    } else "Đang chuẩn bị..."
                                    
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Tệp: ${viewModel.autoBackupCurrentFile}", fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(speed, fontSize = 11.sp, color = Color(0xFF66BB6A), modifier = Modifier.padding(start = 4.dp))
                                    }
                                    
                                    if (viewModel.autoBackupSourcePath.isNotEmpty()) {
                                        val src = viewModel.autoBackupSourcePath.substringAfterLast("0/").trim('/')
                                        Text("Từ: /$src", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (viewModel.autoBackupDestPath.isNotEmpty()) {
                                        val dst = viewModel.autoBackupDestPath.substringAfter("/webdav/").trim('/')
                                        Text("Lưu: /$dst", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }

                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { viewModel.autoBackupProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = Color(0xFF66BB6A), trackColor = Color(0xFF161616)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Tổng tiến trình: ${viewModel.autoBackupProcessedCount} / ${viewModel.autoBackupTotalCount} tệp", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                                        val totalPercent = if(viewModel.autoBackupTotalCount > 0) (viewModel.autoBackupProcessedCount * 100f / viewModel.autoBackupTotalCount) else 0f
                                        Text("%.1f%%".format(totalPercent), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF66BB6A))
                                    }
                                }
                            }
                        }

                        if (autoBackupIsActive && (usbImportIsActive || activeStreams.isNotEmpty())) {
                            HorizontalDivider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 6.dp))
                        }

                        // --- USB IMPORT ---
                        if (usbImportIsActive) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onOpenUsbImport() },
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    Modifier.size(38.dp).background(Color(0xFF26A69A).copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Usb, null, tint = Color(0xFF26A69A), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("USB Import", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(
                                        if (usbImport.status == "cancelling") "Đang hủy copy USB" else "Đang copy từ ${usbImport.detectedDevicesInfo.ifBlank { usbImport.activeDevice.ifBlank { "ổ USB" } }}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF26A69A)
                                    )
                                    if (usbImport.currentFile.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Tệp: ${usbImport.currentFile}", fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (usbImport.currentSource.isNotBlank()) {
                                        Text("Từ: ${usbImport.currentSource}", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (usbImport.currentDest.isNotBlank()) {
                                        Text("Lưu: ${usbImport.currentDest}", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { usbImportProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = Color(0xFF26A69A),
                                        trackColor = Color(0xFF161616)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            "${usbImport.filesDone}/${usbImport.filesTotal} tệp • ${com.nas.naswebdav.utils.FormatUtils.formatBytes(usbImport.bytesProcessed)}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextSecondary
                                        )
                                        Text(
                                            "${com.nas.naswebdav.utils.FormatUtils.formatBytes(usbImport.copySpeedBps)}/s • ETA ${usbImportEtaLabel(usbImport.etaSeconds)}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF26A69A)
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.cancelUsbImport() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Stop, null, tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        if (usbImportIsActive && activeStreams.isNotEmpty()) {
                            HorizontalDivider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 6.dp))
                        }

                        // --- LIVESTREAM ---
                        if (activeStreams.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onOpenLivestream() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(30.dp).background(Color(0xFFFF7043).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Videocam, null, tint = Color(0xFFFF7043), modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Ghi hình livestream", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text("🔴 Đang ghi hình (${activeStreams.size} kênh)", fontSize = 10.sp, color = Color(0xFFFF7043))
                                }
                            }
                            
                            Spacer(Modifier.height(4.dp))
                            activeStreams.forEach { job ->
                                androidx.compose.runtime.key(job.jobId) {
                                    val jobPlatformName = when (job.platform) { "tiktok" -> "TikTok"; "facebook" -> "Facebook"; "youtube" -> "YouTube"; "shopee" -> "Shopee"; else -> "Livestream" }
                                    
                                    var localSeconds by remember(job.jobId) { androidx.compose.runtime.mutableStateOf(job.durationSeconds) }
                                    LaunchedEffect(job.jobId, job.startedTs, job.durationSeconds) {
                                        while (true) {
                                            localSeconds = if (job.startedTs > 0L) {
                                                ((System.currentTimeMillis() / 1000L) - job.startedTs).coerceAtLeast(0L)
                                            } else {
                                                localSeconds.coerceAtLeast(job.durationSeconds)
                                            }
                                            kotlinx.coroutines.delay(1000)
                                            if (job.startedTs <= 0L) localSeconds++
                                        }
                                    }
                                    val displayDur = "${localSeconds / 3600}h${String.format("%02d", (localSeconds % 3600) / 60)}m${String.format("%02d", localSeconds % 60)}s"

                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 38.dp, top = 4.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { onOpenLivestream() }
                                    ) {
                                        // Hiện "@user" nếu có watchUsername (từ /api/livestream/status hoặc local extract),
                                        // fallback về "${platform} • ${jobId.takeLast(6)}" cho các job không gắn với user (vd FB/YT).
                                        val primaryLabel = if (job.watchUsername.isNotBlank())
                                            "$jobPlatformName • @${job.watchUsername}"
                                        else
                                            "$jobPlatformName • ${job.jobId.takeLast(6)}"
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(primaryLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                            Text(displayDur, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF7043))
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            val secondaryLabel = when {
                                                job.outputFile.isNotEmpty() -> {
                                                    val fileName = job.outputFile.substringAfterLast('/')
                                                    if (job.outputFile.startsWith("Livestream/")) job.outputFile else "Livestream/$fileName"
                                                }
                                                else -> "Livestream/đang tạo file..."
                                            }
                                            Text(secondaryLabel, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(job.fileSize.ifEmpty { "0 B" }, fontSize = 10.sp, color = TextPrimary, modifier = Modifier.padding(start = 8.dp))
                                        }
                                        if (job.speed.isNotEmpty()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text("Tốc độ mạng: ${job.speed}", fontSize = 10.sp, color = AccentGreen)
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

    // State cho 2 nút khẩn cấp (WoL + Restart) hiện trên login screen — dùng khi
    // NAS bị lỗi không đăng nhập được.
    val sharedPrefs = remember { context.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE) }
    var macAddress by remember { mutableStateOf(sharedPrefs.getString("mac_address", "") ?: "") }
    var showWolDialog by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var emergencyMsg by remember { mutableStateOf("") }
    var emergencyIsError by remember { mutableStateOf(false) }

    // Trạng thái ping real-time cho các IP: URL → RTT (ms), -1 = unreachable
    var ipPingStatus by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var isCheckingPings by remember { mutableStateOf(false) }

    // Khởi động vòng lặp ping thực tế khi LoginScreen hiển thị
    LaunchedEffect(Unit) {
        while (true) {
            isCheckingPings = true
            try {
                val fullUrls = (historyIps + ipInput).distinct().filter { it.isNotBlank() }.map { ipToFullUrl(it) }
                if (fullUrls.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) {
                    val results = viewModel.pingUrlsForDisplay(fullUrls, user, pass)
                    ipPingStatus = results
                }
            } catch (_: Exception) {}
            isCheckingPings = false
            kotlinx.coroutines.delay(2000)
        }
    }

    LaunchedEffect(ipPingStatus) {
        val bestUrl = ipPingStatus
            .filterValues { it > 0L }
            .minByOrNull { it.value }
            ?.key
        if (!bestUrl.isNullOrBlank()) {
            ipInput = fullUrlToIp(bestUrl)
        }
    }

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
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    // Hiển thị chỉ báo ping: ● xanh = OK, ● đỏ = fail, ● xám = checking
                                    val fullUrl = ipToFullUrl(ipOption)
                                    val rtt = ipPingStatus[fullUrl] ?: -2L
                                    val indicatorColor = when {
                                        rtt > 0 -> Color(0xFF00E676)      // Xanh: kết nối được
                                        rtt == -1L -> Color(0xFFE53935)   // Đỏ: không kết nối được
                                        else -> if (isCheckingPings) Color(0xFF8892B0) else Color(0xFF8892B0)  // Xám: checking hoặc chưa check
                                    }
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(indicatorColor)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(ipOption, modifier = Modifier.weight(1f))
                                    // Hiển thị ping time (nếu có)
                                    if (rtt > 0) {
                                        Text("${rtt}ms", fontSize = 11.sp, color = Color(0xFF8892B0))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                }
                            },
                            onClick = { ipInput = ipOption; expanded = false },
                            trailingIcon = {
                                IconButton(onClick = {
                                    historyIps = historyIps.filter { it != ipOption }
                                    com.nas.naswebdav.SecurePrefsHelper.saveCredentialsAsync(context, historyIps.map { ipToFullUrl(it) }, user, pass)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Xóa", modifier = Modifier.size(20.dp))
                                }
                            }
                        )
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
            if (viewModel.isLoading) {
                viewModel.cancelLogin()
            } else {
                val fullUrl = ipToFullUrl(ipInput); val currentIp = fullUrlToIp(fullUrl)
                val reachableUrls = ipPingStatus.filterValues { it > 0L }.entries.sortedBy { it.value }.map { it.key }
                val allUrls = (historyIps + currentIp).distinct().filter { it.isNotEmpty() }.map { ipToFullUrl(it) }
                val fullUrlList = (listOf(fullUrl) + reachableUrls + allUrls).distinct()
                historyIps = fullUrlList.map { fullUrlToIp(it) }.distinct().filter { it.isNotEmpty() }
                viewModel.connect(fullUrlList.map { it.trim() }, user.trim(), pass.trim(), onSuccess = {
                    viewModel.scheduleIdleDuplicateScan(context); viewModel.scheduleIdleSpeedTest(context); viewModel.scheduleFingerprintWorker(context); onLoginSuccess()
                }, onError = { errorMsg -> viewModel.commonDialogType = DialogType.ERROR; viewModel.commonDialogMessage = errorMsg; viewModel.showCommonDialog = true })
            }
        }, enabled = viewModel.isLoading || ipInput.isNotEmpty(), interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
            modifier = Modifier.fillMaxWidth().height(50.dp).background(brush = Brush.linearGradient(listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))), shape = RoundedCornerShape(24.dp))
        ) {
            if (viewModel.isLoading) { Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Dừng đăng nhập", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            else Text("Kết nối NAS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        // ── BIOMETRIC QUICK-LOGIN: chi hien khi biometric_enabled + co credentials da luu ──
        val biometricEnabled = sharedPrefs.getBoolean("biometric_enabled", false)
        val hasSavedCreds = remember {
            SecurePrefsHelper.getUser(context).isNotEmpty() &&
                SecurePrefsHelper.getPass(context).isNotEmpty() &&
                SecurePrefsHelper.getUrlList(context).isNotEmpty()
        }
        val biometricAvailable = remember {
            try {
                val bm = androidx.biometric.BiometricManager.from(context)
                val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                bm.canAuthenticate(auth) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
            } catch (_: Exception) { false }
        }
        if (biometricEnabled && hasSavedCreds && biometricAvailable) {
            Spacer(Modifier.height(12.dp))
            val activity = context as? androidx.fragment.app.FragmentActivity
            var autoTriggered by remember { mutableStateOf(false) }
            val triggerBiometric: () -> Unit = {
                if (activity != null) {
                    val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
                    val prompt = androidx.biometric.BiometricPrompt(activity, executor,
                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                val urlList = SecurePrefsHelper.getUrlList(context)
                                val u = SecurePrefsHelper.getUser(context)
                                val p = SecurePrefsHelper.getPass(context)
                                viewModel.connect(urlList, u, p, onSuccess = {
                                    viewModel.scheduleIdleDuplicateScan(context)
                                    viewModel.scheduleIdleSpeedTest(context)
                                    viewModel.scheduleFingerprintWorker(context)
                                    onLoginSuccess()
                                }, onError = { msg ->
                                    viewModel.commonDialogType = DialogType.ERROR
                                    viewModel.commonDialogMessage = msg
                                    viewModel.showCommonDialog = true
                                })
                            }
                        })
                    val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Đăng nhập NAS")
                        .setSubtitle("Dùng vân tay/khuôn mặt để đăng nhập nhanh")
                        .setAllowedAuthenticators(
                            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        ).build()
                    prompt.authenticate(info)
                }
            }
            // Auto-trigger 1 lan khi screen vua compose (chi khi user chua login va da co credentials)
            LaunchedEffect(Unit) {
                if (!autoTriggered && !viewModel.isLoading) {
                    autoTriggered = true
                    kotlinx.coroutines.delay(300)  // cho UI settle
                    triggerBiometric()
                }
            }
            OutlinedButton(
                onClick = triggerBiometric,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF9C27B0)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9C27B0))
            ) {
                Icon(Icons.Default.Fingerprint, null, tint = Color(0xFF9C27B0), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Đăng nhập bằng vân tay", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── KHU VUC NUT KHAN CAP: Bat nguon (WoL) + Khoi dong lai NAS ────────────
        // Cho phep dieu khien NAS khi khong dang nhap duoc (vd NAS treo, mat ket noi).
        Spacer(Modifier.height(20.dp))
        Text(
            "Điều khiển từ xa (không cần đăng nhập)",
            fontSize = 11.sp,
            color = Color(0xFF8892B0),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // NUT 1: WoL — bat nguon NAS qua magic packet, chi can MAC address
            OutlinedButton(
                onClick = {
                    macAddress = sharedPrefs.getString("mac_address", macAddress) ?: macAddress
                    showWolDialog = true
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26A69A))
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, tint = Color(0xFF26A69A), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bật nguồn", color = Color(0xFF26A69A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            // NUT 2: Restart NAS — POST /api/power/reboot truc tiep voi IP + auth tu form
            OutlinedButton(
                onClick = { showRebootConfirm = true },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFB8C00))
            ) {
                Icon(Icons.Default.RestartAlt, null, tint = Color(0xFFFB8C00), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Khởi động lại", color = Color(0xFFFB8C00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (emergencyMsg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                emergencyMsg,
                fontSize = 12.sp,
                color = if (emergencyIsError) Color(0xFFE53935) else Color(0xFF00E676),
                fontWeight = FontWeight.Medium
            )
        }
    }

    // ── DIALOGS cho khu vuc khan cap ────────────────────────────────────────
    if (showWolDialog) {
        com.nas.naswebdav.ui.dialogs.WolDialog(
            macAddress = macAddress,
            onMacChange = { macAddress = it },
            onConfirm = {
                val wolMac = macAddress.trim()
                if (wolMac.isNotBlank()) {
                    sharedPrefs.edit().putString("mac_address", wolMac).apply()
                    showWolDialog = false
                    emergencyIsError = false
                    emergencyMsg = "Đang gửi Wake-on-LAN..."
                    viewModel.sendWakeOnLan(wolMac, ipInput) { result ->
                        emergencyIsError = !result.success
                        emergencyMsg = result.message
                    }
                }
            },
            onDismiss = { showWolDialog = false }
        )
    }
    if (showRebootConfirm) {
        com.nas.naswebdav.ui.dialogs.RebootConfirmDialog(
            onConfirm = {
                showRebootConfirm = false
                viewModel.sendPowerCommandFromLogin(
                    ipInput = ipInput,
                    user = user.trim(),
                    pass = pass.trim(),
                    endpoint = "power/reboot",
                    onResult = { ok, msg ->
                        emergencyIsError = !ok
                        emergencyMsg = msg
                    }
                )
            },
            onDismiss = { showRebootConfirm = false }
        )
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
                        listOf(30 to "30 phút", 60 to "1 giờ", 180 to "3 giờ", 1440 to "1 ngày").forEach { (min, label) ->
                            FilterChip(selected = durationMinutes == min, onClick = { durationMinutes = min }, label = { Text(label, fontSize = 11.sp) }, modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GpAccentCyan.copy(alpha = 0.2f), selectedLabelColor = GpAccentCyan, containerColor = GpDarkSurface, labelColor = GpTextSecondary))
                        }
                    }
                    Spacer(Modifier.height(8.dp)); Text("Thời hạn đã chọn: $durationMinutes phút (${durationMinutes / 60} giờ ${durationMinutes % 60} phút)", fontSize = 12.sp, color = GpAccentCyan)
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
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Timer, null, tint = if (expiresMin < 10) GpAccentOrange else GpTextSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(if (expiresMin > 0) "Hết hạn sau $expiresMin phút" else "⚠️ Sắp hết hạn / Đã hết hạn", fontSize = 12.sp, color = if (expiresMin < 10) GpAccentOrange else GpTextSecondary) }
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { viewModel.revokeGuestPass() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !viewModel.isGuestPassLoading, colors = ButtonDefaults.buttonColors(containerColor = GpAccentRed.copy(alpha = 0.8f))) { Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Thu hồi ngay", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            viewModel.guestPassError?.let { err -> Spacer(Modifier.height(10.dp)); Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpAccentRed.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Error, null, tint = GpAccentRed, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(err, color = GpAccentRed, fontSize = 12.sp) } } }
            Spacer(Modifier.height(14.dp))
            if (pass == null) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (!viewModel.isGuestPassLoading) Brush.horizontalGradient(listOf(GpAccentPurple, Color(0xFF6200EA))) else Brush.horizontalGradient(listOf(GpTextSecondary.copy(alpha=0.2f), GpTextSecondary.copy(alpha=0.2f)))).clickable(
                    enabled = !viewModel.isGuestPassLoading,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { viewModel.createGuestPass(durationMinutes) }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
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
    QuickActionDef("stream", "Ghi Livestream", "Ghi TikTok, Facebook", Icons.Default.Videocam, listOf(Color(0xFFFF5252), Color(0xFFC62828))),
    QuickActionDef("trash", "Thùng Rác", "Khôi phục dữ liệu", Icons.Default.Delete, listOf(Color(0xFFEF5350), Color(0xFFD32F2F))),
    QuickActionDef("organizer", "Phân Loại Tệp", "AI Smart Organizer", Icons.Default.AutoAwesomeMotion, listOf(Color(0xFF42A5F5), Color(0xFF1565C0))),
    QuickActionDef("guest", "Mạng Khách", "Cấp thẻ Wi-Fi QR", Icons.Default.Wifi, listOf(Color(0xFFAB47BC), Color(0xFF7B1FA2))),
    QuickActionDef("log", "Nhật ký Lõi", "Tiến trình giám sát", Icons.Default.Assignment, listOf(Color(0xFF26C6DA), Color(0xFF0097A7))),
    QuickActionDef("nasbackup", "Sao Lưu Cấu Hình", "Backup NAS + OneDrive", Icons.Default.SettingsBackupRestore, listOf(Color(0xFF66BB6A), Color(0xFF388E3C))),
    QuickActionDef("smb", "Ổ đĩa LAN (SMB)", "Map Network Drive", Icons.Default.Dns, listOf(Color(0xFFFF9800), Color(0xFFF57C00))),
    QuickActionDef("duplicate", "Quét Trùng Lặp", "Phát hiện tệp trùng", Icons.Default.ContentCopy, listOf(Color(0xFF29B6F6), Color(0xFF0277BD)))
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
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { DashboardCompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).verticalScroll(rememberScrollState())
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
fun SystemLogsSummaryCard(viewModel: WebDavViewModel, realtimeNow: Long = System.currentTimeMillis()) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadSystemLogs()
    }
    
    if (viewModel.systemLogsList.isEmpty()) return
    
    Spacer(Modifier.height(8.dp))
    
    var isExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { isExpanded = !isExpanded }
            ) {
                Icon(Icons.Default.Assignment, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("NHẬT KÝ HỆ THỐNG", fontWeight = FontWeight.Black, color = PanelTitleCyan, fontSize = PanelTitleSize, letterSpacing = PanelTitleLetterSpacing)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Mở rộng/Thu gọn",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                PanelFreshnessTag(viewModel.lastLogsRefreshAt, realtimeNow, staleAfterMs = 30_000L)
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
    if (hours > 0) parts.add("$hours giờ")
    if (minutes > 0) parts.add("$minutes phút")
    if (seconds > 0 || parts.isEmpty()) parts.add("$seconds giây")

    return parts.joinToString(", ")
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ProcessListBottomSheet(
    viewModel: WebDavViewModel,
    sortBy: String,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    androidx.compose.runtime.LaunchedEffect(sortBy) {
        while (true) {
            viewModel.fetchSystemProcesses(sortBy)
            kotlinx.coroutines.delay(3000) // Tự động làm mới mỗi 3 giây
        }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        dragHandle = { DashboardCompactBottomSheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    text = "Tiến Trình (Theo ${if (sortBy == "cpu") "CPU" else "RAM"})",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (viewModel.isLoadingProcesses) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentCyan
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            
            // Header
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("TIẾN TRÌNH", fontSize = 10.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                Text(if (sortBy == "cpu") "CPU" else "RAM", fontSize = 10.sp, color = TextSecondary, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            androidx.compose.material3.Divider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)

            val displayProcesses = viewModel.systemProcesses.filter {
                if (sortBy == "cpu") it.cpu > 0f else it.mem > 0f
            }

            if (displayProcesses.isEmpty() && !viewModel.isLoadingProcesses) {
                Text(
                    "Không có dữ liệu tiến trình.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.85f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(displayProcesses.size) { index ->
                    val proc = displayProcesses[index]
                    val statusColor = when (proc.status) {
                        "running" -> Color(0xFF66BB6A)
                        "sleeping" -> Color(0xFF9E9E9E)
                        "disk-sleep" -> Color(0xFFFFA726)
                        "zombie", "dead" -> Color(0xFFEF5350)
                        "idle" -> Color(0xFF29B6F6)
                        else -> Color(0xFF9E9E9E)
                    }
                    val statusChar = when (proc.status) {
                        "running" -> "R"
                        "sleeping" -> "S"
                        "disk-sleep" -> "D"
                        "zombie" -> "Z"
                        "idle" -> "I"
                        else -> "?"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // S badge
                        Box(
                            Modifier.size(20.dp).background(statusColor.copy(alpha=0.2f), androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(statusChar, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(proc.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${proc.user} (${proc.pid})", color = TextSecondary, fontSize = 10.sp)
                        }
                        val displayValue = if (sortBy == "cpu") "${proc.cpu}%" else "${proc.mem}%"
                        Text(displayValue, color = AccentCyan, fontSize = 12.sp, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SmartDetailBottomSheet(
    smartInfo: SmartInfo,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    // Parse rawLog thành SMART attributes
    val lines = smartInfo.rawLog.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val headerLines = lines.takeWhile { !it.startsWith("ID") && !it.startsWith("===") }
    val attrLines = lines.dropWhile { !it.startsWith("ID") }.drop(1) // Bỏ header row

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        dragHandle = { DashboardCompactBottomSheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            // Title
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    "Thông tin S.M.A.R.T",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                val statusColor = when {
                    smartInfo.status.uppercase().contains("PASSED") -> Color(0xFF66BB6A)
                    smartInfo.status.uppercase().contains("FAILED") -> Color(0xFFEF5350)
                    else -> Color(0xFFFFA726)
                }
                Text(
                    smartInfo.status.uppercase(),
                    color = statusColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))

            // Device info header
            val labelMap = mapOf(
                "Thiet bi" to "Thiết bị",
                "Trang thai OMV" to "Trạng thái OMV",
                "Nhiet do" to "Nhiệt độ"
            )
            for (line in headerLines) {
                if (line.startsWith("===")) continue
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    val rawLabel = parts[0].trim()
                    val label = labelMap[rawLabel] ?: rawLabel
                    val rawValue = parts[1].trim()
                    
                    // Màu sắc theo trạng thái
                    val valueColor = when {
                        rawLabel == "Trang thai OMV" -> when {
                            rawValue.uppercase().contains("GOOD") || rawValue.uppercase().contains("PASSED") -> Color(0xFF66BB6A)
                            rawValue.uppercase().contains("BAD") || rawValue.uppercase().contains("FAILED") -> Color(0xFFEF5350)
                            else -> Color(0xFFFFA726)
                        }
                        rawLabel == "Nhiet do" -> {
                            val temp = rawValue.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                            when {
                                temp >= 55 -> Color(0xFFEF5350)  // Nóng - Đỏ
                                temp >= 45 -> Color(0xFFFFA726)  // Ấm - Vàng
                                else -> Color(0xFF66BB6A)         // Mát - Xanh
                            }
                        }
                        else -> Color.White
                    }
                    
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(140.dp))
                        Text(rawValue, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.Divider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)
            Spacer(Modifier.height(4.dp))

            // Table header
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("ID", fontSize = 9.sp, color = TextSecondary, modifier = Modifier.width(28.dp))
                Text("Thuộc tính", fontSize = 9.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                Text("Giá trị", fontSize = 9.sp, color = TextSecondary, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Raw", fontSize = 9.sp, color = TextSecondary, modifier = Modifier.width(80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.85f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(attrLines.size) { index ->
                    val line = attrLines[index]
                    val tokens = line.split(Regex("\\s+"))
                    if (tokens.size >= 10 && tokens[0].toIntOrNull() != null) {
                        val id = tokens[0]
                        val attr = tokens[1]
                        val value = tokens[3]
                        val raw = tokens.drop(9).joinToString(" ")
                        
                        // Highlight attributes that may indicate problems
                        val rawNumber = raw.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                        val isCritical = id in listOf("5", "187", "197", "198", "10") && rawNumber > 0L
                        val rowColor = if (isCritical) Color(0xFFEF5350).copy(alpha = 0.15f) else Color(0xFF1E1E1E)
                        val textColor = if (isCritical) Color(0xFFEF5350) else Color.White

                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(rowColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(id, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(28.dp))
                            Text(attr.replace("_", " "), color = textColor, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(value, color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            Text(raw, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SmbBottomSheet(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkCard,
        dragHandle = { DashboardCompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Ổ Đĩa Mạng (SMB)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Map Network Drive cho PC",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                if (viewModel.isLoadingSmb) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = AccentCyan,
                        strokeWidth = 2.dp
                    )
                } else {
                    androidx.compose.material3.Switch(
                        checked = viewModel.isSmbEnabled,
                        onCheckedChange = { isChecked ->
                            viewModel.toggleSmbShare(isChecked) { success, msg ->
                                // Optional toast
                            }
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentCyan,
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color.Gray
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (viewModel.isSmbEnabled) {
                Text(
                    "Truy cập qua máy tính (LAN):",
                    color = AccentCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Dành cho Windows:", color = TextSecondary, fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("\\\\192.168.100.254\\NAS_Data", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { 
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString("\\\\192.168.100.254\\NAS_Data"))
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = AccentCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Dành cho MacOS:", color = TextSecondary, fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("smb://192.168.100.254/NAS_Data", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { 
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString("smb://192.168.100.254/NAS_Data"))
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = AccentCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = "User", tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tài khoản:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                            Text("daica", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = "Password", tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Mật khẩu:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                            Text("(Mật khẩu của App NAS)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } else {
                Text(
                    "Bật tính năng này để sử dụng NAS như một ổ cứng mạng nội bộ trên máy tính. Tốc độ copy sẽ đạt mức tối đa của mạng LAN mà không qua server trung gian.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DuplicateScanGlobalUI(viewModel: com.nas.naswebdav.WebDavViewModel, context: android.content.Context) {
    // 2. Hộp thoại Quét Rác — TÁI THIẾT KẾ HIỂN THỊ CHÍNH XÁC
    if (viewModel.isScanningDuplicates) {
        val scanSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
            // Onclick scrim KHÔNG đóng sheet — user phải bấm nút "Thu nhỏ" / "Huỷ" explicit.
            // Cách làm: onDismissRequest -> mặc định ban đầu đóng sheet -> ta set
            // isScanningDuplicates = false nếu user thu nhỏ thủ công.
            // Với behavior "không đóng khi click ngoài", dismissRequest của sheet phải
            // skip-action: chỉ log + thu nhỏ (= behavior của nút Thu nhỏ).
            onDismissRequest = { viewModel.isScanningDuplicates = false },
            sheetState = scanSheetState,
            containerColor = Color(0xFF0F0F0F),
            scrimColor = Color.Black.copy(alpha = 0.6f),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(5.dp)
                            .background(Color(0xFF6D6A75), RoundedCornerShape(50))
                    )
                }
            }
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Icon(Icons.Default.FindReplace, contentDescription = null, tint = Color(0xFF1E88E5), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Phát hiện tệp trùng lặp", color = Color(0xFFE8E8E8), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Column(Modifier.fillMaxWidth()) {
                    // ═══ GIAI ĐOẠN HIỆN TẠI ═══
                    val stage = viewModel.scanDuplicatesStage
                    val stageColor = when {
                        stage.contains("Thu thập") || stage.contains("nhận") || stage.contains("WebDAV") -> Color(0xFF1E88E5) // Xanh dương
                        stage.contains("Phân tích") -> Color(0xFFF57C00) // Cam
                        stage.contains("Hash") || stage.contains("Xác minh") -> Color(0xFF7B1FA2) // Tím
                        stage.contains("Hoàn tất") -> Color(0xFF2E7D32) // Xanh lá
                        else -> Color(0xFF616161) // Xám
                    }
                    Surface(
                        color = stageColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when {
                                    stage.contains("Hoàn tất") -> Icons.Default.CheckCircle
                                    stage.contains("Hash") || stage.contains("Xác minh") -> Icons.Default.Fingerprint
                                    else -> Icons.Default.Radar
                                },
                                contentDescription = null, tint = stageColor, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stage, color = stageColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ═══ THƯ MỤC ĐANG QUÉT ═══
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF5C6BC0), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Thư mục:", fontSize = 11.sp, color = Color.Gray)
                    }
                    Text(
                        text = viewModel.scanDuplicatesCurrentFolderUrl.ifEmpty { "..." },
                        color = Color(0xFF5C6BC0), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // ═══ FILE ĐANG XỬ LÝ ═══
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color(0xFFEF6C00), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Đang xử lý:", fontSize = 11.sp, color = Color.Gray)
                    }
                    Text(
                        text = viewModel.scanDuplicatesCurrentItemName.ifEmpty { "..." },
                        color = Color(0xFFEF6C00), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // ═══ PROGRESS BAR CHÍNH XÁC (2 THANH) ═══
                    val progressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = viewModel.scanDuplicatesPercent,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "totalProgress"
                    )
                    val stageProgressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = viewModel.scanDuplicatesCurrentStagePercent,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "stageProgress"
                    )
                    
                    Column(Modifier.fillMaxWidth()) {
                        // Thanh 1: TỔNG QUÁT (Bao trùm toàn bộ tiến trình lớn)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tổng thể", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(60.dp))
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { progressValue.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = Color(0xFF4CAF50),
                                trackColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${(progressValue * 100).toInt()}%",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = stageColor,
                                modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Thanh 2: HIỆN TẠI (Theo từng giai đoạn)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Giai đoạn", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(60.dp))
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { stageProgressValue.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = Color(0xFF81C784),
                                trackColor = Color(0xFF81C784).copy(alpha = 0.15f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${(stageProgressValue * 100).toInt()}%",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = stageColor.copy(alpha = 0.8f),
                                modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ═══ STAGE LABEL + MÔ TẢ ═══
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Bước ${viewModel.scanDuplicatesStageNumber}/${viewModel.scanDuplicatesTotalStages}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = stageColor
                        )
                        Text(
                            "${(stageProgressValue * 100).toInt()}% giai đoạn",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                    if (viewModel.scanDuplicatesStageDescription.isNotEmpty()) {
                        Text(
                            viewModel.scanDuplicatesStageDescription,
                            fontSize = 10.sp,
                            color = Color.Gray.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // ═══ THỜI GIAN DỰ KIẾN ═══
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val elapsed = viewModel.scanDuplicatesElapsedTime
                        val etr = viewModel.scanDuplicatesEstimatedTimeRemaining
                        
                        fun formatTime(ms: Long): String {
                            if (ms < 0) return "--:--"
                            val totalSec = ms / 1000
                            val m = totalSec / 60
                            val s = totalSec % 60
                            return String.format(java.util.Locale.US, "%02d:%02d", m, s)
                        }

                        Text("Thời gian chạy: ${formatTime(elapsed)}", fontSize = 11.sp, color = Color.Gray)
                        Text(if (etr >= 0) "Ước tính còn: ${formatTime(etr)}" else "Đang tính toán...", fontSize = 11.sp, color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)
                    }

                    // ═══ THỐNG KÊ RÕ RÀNG ═══
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${viewModel.scanDuplicatesTotalScanned}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                            Text("Tổng tệp", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${viewModel.scanDuplicatesFound}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                            Text("Trùng lặp", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
                // ── ACTION ROW: Tạm dừng / Huỷ / Thu nhỏ ──
                Spacer(Modifier.height(12.dp))
                if (viewModel.isWorkerRunning && !viewModel.scanDuplicatesStage.contains("Hoàn tất", ignoreCase = true)) {
                    val isPaused by com.nas.naswebdav.DuplicateProgressState.isPaused.collectAsState()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.cancelDuplicateScan(context) },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373))
                        ) { Text("Huỷ", color = Color(0xFFE57373), fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = { viewModel.togglePauseDuplicateScan() },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6))
                        ) { Text(if (isPaused) "Tiếp tục" else "Tạm dừng", color = Color(0xFF64B5F6), fontWeight = FontWeight.SemiBold) }
                        Button(
                            onClick = { viewModel.isScanningDuplicates = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) { Text("Thu nhỏ", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                } else {
                    Button(
                        onClick = { viewModel.isScanningDuplicates = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("Đóng", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }



// Hộp thoại Hiển thị danh sách File Trùng Lặp
    if (viewModel.isShowingDuplicates) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { viewModel.isShowingDuplicates = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            dragHandle = { DashboardCompactBottomSheetHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Tệp trùng lặp", style = MaterialTheme.typography.titleMedium, color = Color.Red)
                        if (viewModel.duplicateFilesList.isNotEmpty()) {
                            Text(
                                text = "Phát hiện ${viewModel.duplicateFilesList.size} tệp trùng lặp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (viewModel.duplicateFilesList.isEmpty()) {
                        TextButton(onClick = { viewModel.isShowingDuplicates = false; viewModel.selectedDuplicates.clear() }) { 
                            Text("Hoàn tất", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold) 
                        }
                    } else {
                        if (viewModel.selectedDuplicates.isNotEmpty()) {
                            TextButton(onClick = { viewModel.deleteSelectedDuplicates() }) {
                                Text("Xóa (${viewModel.selectedDuplicates.size}) mục", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (viewModel.duplicateFilesList.isEmpty()) {
                    Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = Color.Green)
                } else {
                    // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                    // SỬA LỖI: Nhóm theo Hash/Fingerprint thay vì chỉ theo Size để đảm bảo tuyệt đối file có nội dung giống nhau mới nằm chung nhóm
                    val groupedDuplicates = remember(viewModel.duplicateFilesList) {
                        viewModel.duplicateFilesList.groupBy { it.contentLength }.values.filter { it.size >= 2 }.toList()
                    }

                    // ═══ BỘ LỌC NHANH ═══
                    var selectedFilter by remember { mutableStateOf("all") } // all, image, video, doc
                    val filteredGroups = remember(groupedDuplicates, selectedFilter) {
                        when (selectedFilter) {
                            "image" -> groupedDuplicates.filter { group ->
                                group.any { it.name.lowercase().run { endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp") || endsWith(".heic") || endsWith(".gif") || endsWith(".bmp") } }
                            }
                            "video" -> groupedDuplicates.filter { group ->
                                group.any { com.nas.naswebdav.utils.MediaUtils.isVideo(it.name) }
                            }
                            "doc" -> groupedDuplicates.filter { group ->
                                group.any { f -> val n = f.name.lowercase(); !n.run { endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp") || endsWith(".heic") || endsWith(".gif") || endsWith(".bmp") } && !com.nas.naswebdav.utils.MediaUtils.isVideo(f.name) }
                            }
                            else -> groupedDuplicates
                        }
                    }

                    Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                        // ═══ FILTER CHIP ROW ═══
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            data class FilterOption(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                            val filters = listOf(
                                FilterOption("all", "Tất cả (${groupedDuplicates.size})", Icons.Default.SelectAll),
                                FilterOption("image", "🖼 Ảnh", Icons.Default.Image),
                                FilterOption("video", "🎬 Video", Icons.Default.PlayCircle),
                                FilterOption("doc", "📄 Khác", Icons.Default.InsertDriveFile)
                            )
                            filters.forEach { opt ->
                                androidx.compose.material3.FilterChip(
                                    selected = selectedFilter == opt.key,
                                    onClick = { selectedFilter = opt.key },
                                    label = { Text(opt.label, fontSize = 10.sp, maxLines = 1) },
                                    leadingIcon = if (selectedFilter == opt.key) {{ Icon(Icons.Default.Done, null, Modifier.size(14.dp)) }} else null,
                                    modifier = Modifier.height(30.dp)
                                )
                            }
                        }

                        // Nút Tự động chọn thông minh (Giữ lại 1 bản, tick chọn xóa các bản copy)
                        TextButton(
                            onClick = {
                                viewModel.selectedDuplicates.clear()
                                groupedDuplicates.forEach { group ->
                                    // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                    // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                    val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                    viewModel.selectedDuplicates.addAll(filesToDelete)
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF2196F3))
                            Spacer(Modifier.width(4.dp))
                            Text("Chọn thông minh", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                        }

                        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                            items(items = filteredGroups, key = { it.first().contentLength }) { group ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Nhóm ${group.size} tệp trùng lặp (${group.first().contentLength / 1024} KB)",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        // Hiển thị danh sách file trong nhóm bằng Cuộn Ngang (LazyRow)
                                        androidx.compose.foundation.lazy.LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(items = group, key = { it.path }) { dupFile ->
                                                val isSelected = viewModel.selectedDuplicates.contains(dupFile)
                                                val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                                val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(dupFile.name)
                                                val auth = remember { okhttp3.Credentials.basic(viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) }

                                                Box(
                                                    modifier = Modifier
                                                        .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) Color.Red.copy(alpha = 0.2f) else Color.Black)
                                                        .clickable {
                                                            if (isSelected) viewModel.selectedDuplicates.remove(dupFile)
                                                            else viewModel.selectedDuplicates.add(dupFile)
                                                        }
                                                ) {
                                                    // 1. Lớp Ảnh Nền (TỐI ƯU HÓA DB CACHE MỚI CHO TẤT CẢ MEDIA)
                                                    if (isImage || isVideo) {
                                                        Box(modifier = Modifier.fillMaxSize()) {
                                                            WebDavCachedThumbnail(url = dupFile.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize())
                                                        }
                                                    } else {
                                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                                    }

                                                    // 2. Lớp phủ đỏ mờ nếu đang được tick chọn xóa
                                                    if (isSelected) {
                                                        Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.4f)))
                                                    }

                                                    // 3. Checkbox nằm góc trên phải
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = {
                                                            if (it) viewModel.selectedDuplicates.add(dupFile)
                                                            else viewModel.selectedDuplicates.remove(dupFile)
                                                        },
                                                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                        colors = CheckboxDefaults.colors(checkedColor = Color.Red, uncheckedColor = Color.White)
                                                    )

                                                    // 4. Tên file + thư mục cha đè ở dưới cùng (Để phân biệt các file)
                                                    Column(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .fillMaxWidth()
                                                            .background(Color.Black.copy(alpha = 0.75f))
                                                            .padding(horizontal = 4.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(
                                                            text = dupFile.name,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                            lineHeight = 10.sp
                                                        )
                                                        val parentFolder = dupFile.path.substringBeforeLast("/").substringAfterLast('/')
                                                        Text(
                                                            text = "📁 $parentFolder",
                                                            fontSize = 7.sp,
                                                            color = Color.Gray,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
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
        }
    }


}
}
