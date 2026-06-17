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
    val safePercent = percent.coerceIn(0, 100)
    val isRunning = safePercent > 0
    val speedRatio = safePercent / 100f
    val durationMs = if (isRunning) {
        (1700f - 1300f * kotlin.math.sqrt(speedRatio)).toInt().coerceIn(400, 1700)
    } else {
        1700
    }
    val blurAlpha = (speedRatio * 0.28f).coerceIn(0f, 0.28f)
    val sweepAlpha = (speedRatio * 0.55f).coerceIn(0.12f, 0.55f)

    key(durationMs) {
        val infiniteTransition = rememberInfiniteTransition(label = "fan_$durationMs")
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
                        colors = listOf(Color.Transparent, color.copy(alpha = sweepAlpha), Color.Transparent),
                        center = Offset(cx, cy)
                    ),
                    radius = R * 0.9f
                )
                drawArc(
                    color = color.copy(alpha = (0.18f + speedRatio * 0.45f).coerceAtMost(0.63f)),
                    startAngle = -90f,
                    sweepAngle = 360f * speedRatio,
                    useCenter = false,
                    topLeft = Offset(cx - R * 0.93f, cy - R * 0.93f),
                    size = Size(R * 1.86f, R * 1.86f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = R * 0.10f,
                        cap = StrokeCap.Round
                    )
                )
                if (safePercent >= 45) {
                    drawCircle(
                        color = color.copy(alpha = blurAlpha),
                        radius = R * (0.58f + speedRatio * 0.18f),
                        center = Offset(cx, cy)
                    )
                }
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
            if (isRunning && safePercent >= 70) {
                listOf(-12f, 12f).forEach { ghostOffset ->
                    withTransform({ rotate(currentAngle + ghostOffset, Offset(cx, cy)) }) {
                        val bladeCount = 5
                        for (i in 0 until bladeCount) {
                            withTransform({ rotate((360f / bladeCount) * i, Offset(cx, cy)) }) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(cx, cy)
                                    quadraticBezierTo(cx + R * 0.58f, cy - R * 0.18f, cx + R * 0.2f, cy - R * 0.82f)
                                    quadraticBezierTo(cx, cy - R * 0.9f, cx - R * 0.18f, cy - R * 0.82f)
                                    quadraticBezierTo(cx - R * 0.28f, cy - R * 0.3f, cx, cy)
                                    close()
                                }
                                drawPath(path = path, color = color.copy(alpha = 0.13f * speedRatio))
                            }
                        }
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
        val dupSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDuplicateScanDialog = false },
            sheetState = dupSheetState,
            containerColor = Color(0xFF0F0F0F),
            scrimColor = Color.Black.copy(alpha = 0.6f),
            dragHandle = { DashboardCompactBottomSheetHandle() }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF29B6F6), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Quét tệp trùng lặp", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 17.sp)
                }
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.History, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mở lại kết quả quét gần nhất", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(2.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showDuplicateScanDialog = false }) {
                        Text("Hủy", color = TextSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
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
                }
                Spacer(Modifier.height(8.dp))
            }
        }
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
