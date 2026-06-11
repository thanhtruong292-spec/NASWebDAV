@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
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


internal fun realtimeFreshnessLabel(lastRefreshAt: Long, now: Long): String {
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
internal fun PanelFreshnessTag(
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
internal fun DashboardCompactBottomSheetHandle() {
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
    onOpenSocialExtractor: () -> Unit = {},
    onStartScreenRecord: () -> Unit = {}
) {
    val mContext = LocalContext.current
    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
    var realtimeNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
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
    LaunchedEffect(Unit) {
        if (!sharedPrefs.getBoolean("screen_record_quick_added", false)) {
            slot4Id = "screen_record"
            sharedPrefs.edit()
                .putString("qa_slot4", "screen_record")
                .putBoolean("screen_record_quick_added", true)
                .apply()
        }
    }
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
        viewModel.fetchStorageUsage(minIntervalMs = 0L)
        viewModel.fetchSmartData(minIntervalMs = 0L)
        viewModel.fetchOmvOverview(minIntervalMs = 0L)
        viewModel.fetchNasInsights(minIntervalMs = 0L)
        viewModel.syncLivestreamStateWithServer(mContext)
        viewModel.startDashboardMonitoring(resetStatusPoll = false)
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
                    
                    // Nút Lịch sử quét
                    TextButton(
                        onClick = {
                            showDuplicateScanDialog = false
                            viewModel.loadDuplicateResultsFromCache(mContext)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.History, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mở lại kết quả quét gần nhất", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
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
                        .addTag("com.nas.naswebdav.AutoBackupWorker")
                        .build()
                    androidx.work.WorkManager.getInstance(mContext).enqueueUniquePeriodicWork(
                        "AutoBackupWork",
                        androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
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
            "log" -> { viewModel.loadSystemLogs(minIntervalMs = 0L); viewModel.showLogDialog = true }
            "nasbackup" -> { viewModel.fetchNasConfigBackups(); showNasBackupDialog = true }
            "smb" -> { viewModel.fetchSmbStatus(); showSmbDialog = true }
            "duplicate" -> showDuplicateScanDialog = true
            "screen_record" -> onStartScreenRecord()
        }
    }

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.checkSmartNetwork(mContext)
            viewModel.fetchStorageUsage(minIntervalMs = 0L)
            viewModel.fetchNasInsights(minIntervalMs = 0L)
            viewModel.fetchOmvOverview(minIntervalMs = 0L)
            viewModel.fetchSmartData(minIntervalMs = 0L)
            viewModel.startDashboardMonitoring(resetStatusPoll = true) // KHÔI PHỤC KẾT NỐI VÀ RESET DELAY NGAY LẬP TỨC
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

        MainDashboardHeader(
            viewModel = viewModel,
            realtimeNow = realtimeNow,
            showPowerMenu = showPowerMenu,
            onPowerMenuChange = { showPowerMenu = it },
            onLogout = onLogout,
            onReboot = { showRebootConfirm = true },
            onShutdown = { showShutdownConfirm = true }
        )

            Spacer(Modifier.height(8.dp))

        DashboardSystemOverviewCard(
            viewModel = viewModel,
            realtimeNow = realtimeNow,
            onShowProcessList = { sortType ->
                processSortType = sortType
                showProcessDialog = true
            },
            onOpenNewDiskProfile = { showNewDiskProfileSheet = true },
            onOpenSmartDetails = { showSmartDialog = true }
        )

        OmvServicesHardwarePanel(viewModel = viewModel)

        // --- CHÈN BIỂU ĐỒ GIÁM SÁT VÀ BÁO CÁO Ở ĐÂY ---
        Spacer(Modifier.height(8.dp))
        com.nas.naswebdav.ui.screens.MonitoringChartCard(viewModel)
        Spacer(Modifier.height(8.dp))
        NasInsightsSummaryCard(
            viewModel = viewModel,
            onOpen = {
                viewModel.fetchNasInsights(minIntervalMs = 0L)
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
            onOpenDuplicateScan = {
                when {
                    viewModel.isWorkerRunning || viewModel.isScanningDuplicates -> viewModel.isScanningDuplicates = true
                    viewModel.duplicateFilesList.isNotEmpty() -> viewModel.isShowingDuplicates = true
                    else -> showDuplicateScanDialog = true
                }
            }
        )
        SystemLogsSummaryCard(viewModel)

        TorrentActivityCard(
            viewModel = viewModel,
            onOpenFolder = onOpenFolder,
            onGlobalSearch = onGlobalSearch
        )



        QuickAccessSection(
            slot2Id = slot2Id,
            slot3Id = slot3Id,
            slot4Id = slot4Id,
            editingSlot = editingSlot,
            sharedPrefs = sharedPrefs,
            onEditingSlotChange = { editingSlot = it },
            onSlot2Change = { slot2Id = it },
            onSlot3Change = { slot3Id = it },
            onSlot4Change = { slot4Id = it },
            onQuickAction = handleQuickAction,
            onOpenFiles = onOpenFiles,
            onOpenLatestPhotos = onOpenLatestPhotos,
            onOpenRecentVideos = onOpenRecentVideos,
            onOpenToolbox = { showToolboxDialog = true }
        )
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

