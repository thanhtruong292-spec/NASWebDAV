@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
import com.nas.naswebdav.ui.dialogs.*
import com.nas.naswebdav.utils.FormatUtils

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

// ============ REALTIME FRESHNESS ============
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
    // FIX CPU #2: PanelFreshnessTag đọc System.currentTimeMillis() nội bộ với
    // ticker riêng (2s). Trước đây MainMenuScreen truyền realtimeNow từ root → mỗi
    // giây root + 4 child composable đều recompose (cascade). Giờ chỉ bản thân
    // PanelFreshnessTag (3 component nhỏ) recompose.
    var localNow by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    // Kept as Unit: this ticker is screen-scoped (lives for the duration of the
    // composable). Re-running would just reset localNow and waste a tick. The
    // outer `isActive` guards the coroutine against leaking past the composable.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (isActive) {
            kotlinx.coroutines.delay(2_000L)
            localNow = System.currentTimeMillis()
        }
    }
    val effectiveNow = if (now > 0L) now else localNow
    val ageMs = (effectiveNow - lastRefreshAt).coerceAtLeast(0L)
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
            realtimeFreshnessLabel(lastRefreshAt, effectiveNow),
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DashboardCompactBottomSheetHandle() {
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
    val safePercent = percent.coerceIn(1, 100)
    // 100% -> 450ms (Silky smooth 2.2 RPS without 5-blade stroboscopic aliasing), 50% -> 900ms, 25% -> 1350ms
    val durationMs = if (isRunning) (450 + ((100 - safePercent) * 9.0f).toInt()) else 9999
    
    val angle = if (isRunning) {
        key(durationMs) {
            val infiniteTransition = rememberInfiniteTransition(label = "fan")
            val animAngle by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ), label = "fan_angle"
            )
            animAngle
        }
    } else 0f
    val currentAngle = angle

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
        current.value = if (current.value == panelId) "closed" else panelId
    }
}

// --- MAIN MENU / DASHBOARD ---
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainMenuScreen(
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
    val menuScope = rememberCoroutineScope()
    // ═══ PHASE 7c.3 — Group 3: ALL Domain VMs hooked here ═══
    // MainMenuScreen is the root of the entire dashboard — it needs every VM.
    // Reads via facade delegation → SSoT preserved. Direct hooks are available
    // for Group 3 migration pass (7c.3). PHASE 7d.6: All viewModel calls migrated.
    // blast-radius control during this additive migration phase.
    val sysMonitorVM = LocalSystemMonitorVM.current
    val deviceVM     = LocalDeviceManagementVM.current
    val fileBrowserVM = LocalFileBrowserVM.current
    val autoBackupVM = LocalAutoBackupVM.current
    val smartToolsVM = LocalSmartToolsVM.current
    val livestreamVM = LocalLivestreamVM.current
    val authVM       = LocalAuthSessionVM.current
    val globalUiVM   = LocalGlobalUiVM.current
    // FIX CPU #1: wrap getSharedPreferences trong remember() để tránh file I/O mỗi recomposition.
    // Trước đây gọi trực tiếp → disk I/O mỗi khung hình (120Hz = 120 lần/giây).
    val sharedPrefs = remember(mContext) { mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE) }
    var realtimeNow by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    // Kept as Unit: this 1-second ticker is screen-scoped and intentionally
    // runs for the lifetime of the composable. The `isActive` guard prevents
    // leaking past recomposition.
    LaunchedEffect(Unit) {
        while (isActive) {
            realtimeNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // STATE CHO POPUP TẢI TỪ XA
    var showDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var downloadLink by rememberSaveable { mutableStateOf("") }
    
    // STATE CHO DANH SÁCH TIẾN TRÌNH
    var showProcessDialog by rememberSaveable { mutableStateOf(false) }
    var processSortType by rememberSaveable { mutableStateOf("cpu") }

    // STATE CHO QUÉT TRÙNG LẶP (từ màn hình chính)
    var showDuplicateScanDialog by rememberSaveable { mutableStateOf(false) }
    var dupScanLightningMode by rememberSaveable { mutableStateOf(true) }
    var dupScanForceRestart by rememberSaveable { mutableStateOf(false) }
    var showSmartDialog by rememberSaveable { mutableStateOf(false) }

    // STATE CHO WAKE-ON-LAN
    var showWolDialog by rememberSaveable { mutableStateOf(false) }
    var macAddress by rememberSaveable { mutableStateOf(sharedPrefs.getString("mac_address", "") ?: "") }

    // STATE CHO DIALOG THÔNG BÁO
    var commonDialogMessage by rememberSaveable { mutableStateOf("") }
    var commonDialogType by rememberSaveable { mutableStateOf(DialogType.SUCCESS) }

    // STATE CHO DANH MỤC TRUY CẬP NHANH ĐỘNG
    var slot2Id by rememberSaveable { mutableStateOf(sharedPrefs.getString("qa_slot2", "sync") ?: "sync") }
    var slot3Id by rememberSaveable { mutableStateOf(sharedPrefs.getString("qa_slot3", "stream") ?: "stream") }
    var slot4Id by rememberSaveable { mutableStateOf(sharedPrefs.getString("qa_slot4", "trash") ?: "trash") }
    // Kept as Unit: one-shot SharedPreferences migration (runs once on first composition after
    // this code is introduced; guarded by screen_record_quick_added flag).
    LaunchedEffect(Unit) {
        if (!sharedPrefs.getBoolean("screen_record_quick_added", false)) {
            slot4Id = "screen_record"
            sharedPrefs.edit {
                putString("qa_slot4", "screen_record")
                putBoolean("screen_record_quick_added", true)
            }
        }
    }
    var editingSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var showCommonDialog by rememberSaveable { mutableStateOf(false) }

    // STATE CHO XÁC NHẬN NGUỒN VÀ TOOLBOX
    var showPowerMenu by rememberSaveable { mutableStateOf(false) }
    var showRebootConfirm by rememberSaveable { mutableStateOf(false) }
    var showShutdownConfirm by rememberSaveable { mutableStateOf(false) }
    var showToolboxDialog by rememberSaveable { mutableStateOf(false) }

    // STATE CHO AUTO-BACKUP
    var showAutoBackupDialog by rememberSaveable { mutableStateOf(false) }
    var isAutoBackupEnabled by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("auto_backup", false)) }
    var deleteAfterBackup by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("delete_after_backup", false)) }

    // STATE CHO LAN WHITELIST
    var showLanWhitelistDialog by rememberSaveable { mutableStateOf(false) }

    // STATE CHO LIVESTREAM RECORD
    var showLivestreamDialog by rememberSaveable { mutableStateOf(false) }
    var showSmbDialog by rememberSaveable { mutableStateOf(false) }

    // STATE CHO NAS CONFIG BACKUP/RESTORE
    var showNasBackupDialog by rememberSaveable { mutableStateOf(false) }

    // STATE CHO DISK HEALTH MONITOR
    var showNewDiskProfileSheet by rememberSaveable { mutableStateOf(false) }
    // STATE CHO SLEEP SCHEDULE
    var showSleepScheduleDialog by rememberSaveable { mutableStateOf(false) }
    // STATE CHO BANDWIDTH THROTTLE
    var showBandwidthDialog by rememberSaveable { mutableStateOf(false) }
    // STATE CHO USB IMPORT
    var showUsbImportDialog by rememberSaveable { mutableStateOf(false) }
    var showNasInsightsDialog by rememberSaveable { mutableStateOf(false) }
    // Load bandwidth limit từ SharedPreferences (1 lần khi mở app)
    // Kept as Unit: one-shot SharedPreferences read on screen load.
    LaunchedEffect(Unit) {
        val savedLimit = sharedPrefs.getLong("upload_speed_limit_bps", 0L)
        com.nas.naswebdav.AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC = savedLimit
    }
    // ── SMART SWITCH: Tự động kiểm tra và chuyển mạng khi vào màn hình ──────
    // Kept as Unit: fires once on screen open to refresh the dashboard state.
    LaunchedEffect(Unit) {
        authVM.checkSmartNetwork(mContext)
        deviceVM.fetchStorageUsage(minIntervalMs = 0L)
        deviceVM.fetchSmartData(minIntervalMs = 0L)
        deviceVM.fetchOmvOverview(minIntervalMs = 0L)
        sysMonitorVM.fetchNasInsights(minIntervalMs = 0L)
        livestreamVM.syncLivestreamStateWithServer()
        sysMonitorVM.startDashboardMonitoring(resetStatusPoll = false)
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
                sendPowerCommandToNas(menuScope, "power/reboot") { ok, message ->
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
                sendPowerCommandToNas(menuScope, "power/suspend") { ok, message ->
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
            uploadTorrentFileToNas(menuScope, mContext, uri)
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
                    sendDownloadLinkToQbittorrent(menuScope, downloadLink)
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
        MainMenuBottomSheetProcessListBottomSheet(
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
                            smartToolsVM.loadDuplicateResultsFromCache(mContext)
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
                        smartToolsVM.startBackgroundDuplicateScan(mContext, forceRestart = dupScanForceRestart, lightningMode = dupScanLightningMode)
                        smartToolsVM.isScanningDuplicates = true
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
        MainMenuBottomSheetSmartDetailBottomSheet(
            smartInfo = deviceVM.smartInfo,
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
                    sharedPrefs.edit { putString("mac_address", wolMac) }
                    showWolDialog = false
                    sendWakeOnLanFromMenu(menuScope, wolMac) { result ->
                        commonDialogType = if (result.success) DialogType.SUCCESS else DialogType.ERROR
                        commonDialogMessage = result.message
                        showCommonDialog = true
                    }
                }
            },
            onDismiss = { showWolDialog = false }
        )
    }
    if (deviceVM.showSmartDialog) {
        SmartDiskDialog(onDismiss = { deviceVM.showSmartDialog = false })
    }
    if (showAutoBackupDialog) {
        AutoBackupDialog(
            context = mContext,
            isAutoBackupEnabled = isAutoBackupEnabled,
            isAutoBackupRunning = autoBackupVM.isAutoBackupRunning,
            onAutoBackupEnabledChange = { isAutoBackupEnabled = it },
            deleteAfterBackup = deleteAfterBackup,
            onDeleteAfterBackupChange = { deleteAfterBackup = it },
            onSaveAndSchedule = {
                sharedPrefs.edit {
                    putBoolean("auto_backup", isAutoBackupEnabled)
                    putBoolean("delete_after_backup", deleteAfterBackup)
                }
                if (isAutoBackupEnabled) {
                    val constraints = androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                    val backupWorkRequest = androidx.work.PeriodicWorkRequestBuilder<AutoBackupWorker>(12, java.util.concurrent.TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                            androidx.work.BackoffPolicy.EXPONENTIAL,
                            30L,
                            java.util.concurrent.TimeUnit.SECONDS
                        )
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
                autoBackupVM.triggerManualBackup(mContext) { msg ->
                    commonDialogMessage = msg
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                    showCommonDialog = true
                }
                showAutoBackupDialog = false
            },
            onCancelSync = {
                autoBackupVM.cancelAutoBackup(mContext)
                showAutoBackupDialog = false
            },
            onDismiss = { showAutoBackupDialog = false }
        )
    }
    if (deviceVM.showLogDialog) {
        SystemLogDialog(onDismiss = { deviceVM.showLogDialog = false })
    }
    if (deviceVM.showDockerDialog) {
        DockerDialog(onDismiss = { deviceVM.showDockerDialog = false })
    }
    if (showLanWhitelistDialog) {
        LanWhitelistDialog(
            onDismiss = { showLanWhitelistDialog = false }
        )
    }
    if (showSmbDialog) {
        MainMenuBottomSheetSmbBottomSheet(
            onDismiss = { showSmbDialog = false }
        )
    }
    if (showLivestreamDialog) {
        LivestreamRecordDialog(
            onDismiss = { showLivestreamDialog = false }
        )
    }
    if (showNasBackupDialog) {
        DialogsNasConfigBackupDialog(
            onDismiss = { showNasBackupDialog = false }
        )
    }
    if (showNewDiskProfileSheet) {
        DiskProfileBottomSheet(
            onDismiss = { showNewDiskProfileSheet = false }
        )
    }
    if (showSleepScheduleDialog) {
        SleepScheduleDialog(
            onDismiss = { showSleepScheduleDialog = false }
        )
    }
    if (showUsbImportDialog) {
        DialogsUsbImportDialog(
            onDismiss = { showUsbImportDialog = false }
        )
    }
    if (showNasInsightsDialog) {
        DialogsNasInsightsDialog(
            onDismiss = { showNasInsightsDialog = false },
            onTaskClick = { taskLabel ->
                showNasInsightsDialog = false
                val lbl = taskLabel.lowercase()
                if (lbl.contains("livestream") || lbl.contains("stream")) {
                    showLivestreamDialog = true
                } else if (lbl.contains("usb")) {
                    deviceVM.fetchUsbImportStatus()
                    showUsbImportDialog = true
                } else {
                    ExclusivePanelState.current.value = "tasks"
                }
            }
        )
    }
    if (showBandwidthDialog) {
        com.nas.naswebdav.ui.dialogs.BandwidthThrottleDialog(
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
            "log" -> { deviceVM.loadSystemLogs(minIntervalMs = 0L); deviceVM.showLogDialog = true }
            "smb" -> { deviceVM.fetchSmbStatus(); showSmbDialog = true }
            "duplicate" -> showDuplicateScanDialog = true
            "screen_record" -> onStartScreenRecord()
        }
    }

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(pullRefreshState.isRefreshing) {
            authVM.checkSmartNetwork(mContext)
            deviceVM.fetchStorageUsage(minIntervalMs = 0L)
            sysMonitorVM.fetchNasInsights(minIntervalMs = 0L)
            deviceVM.fetchOmvOverview(minIntervalMs = 0L)
            deviceVM.fetchSmartData(minIntervalMs = 0L)
            sysMonitorVM.startDashboardMonitoring(resetStatusPoll = true) // KHÔI PHỤC KẾT NỐI VÀ RESET DELAY NGAY LẬP TỨC
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

        MainMenuDashboardHeader(
            realtimeNow = realtimeNow,
            showPowerMenu = showPowerMenu,
            onPowerMenuChange = { showPowerMenu = it },
            onLogout = onLogout,
            onReboot = { showRebootConfirm = true },
            onShutdown = { showShutdownConfirm = true }
        )

            Spacer(Modifier.height(8.dp))

        MainMenuDashboardSystemOverviewCard(
            realtimeNow = realtimeNow,
            onShowProcessList = { sortType ->
                processSortType = sortType
                showProcessDialog = true
            },
            onOpenNewDiskProfile = { showNewDiskProfileSheet = true },
            onOpenSmartDetails = { showSmartDialog = true }
        )

        MainMenuDashboardOmvServicesHardwarePanel()

        // --- CHÈN BIỂU ĐỒ GIÁM SÁT VÀ BÁO CÁO Ở ĐÂY ---
        Spacer(Modifier.height(8.dp))
        com.nas.naswebdav.ui.screens.MonitoringChartCard()
        Spacer(Modifier.height(8.dp))
        MainMenuDashboardNasInsightsSummaryCard(
            onOpen = {
                sysMonitorVM.fetchNasInsights(minIntervalMs = 0L)
                showNasInsightsDialog = true
            }
        )
        Spacer(Modifier.height(4.dp))

        MainMenuSystemStatusCards(
            mContext = mContext,
            onOpenAutoBackup = { showAutoBackupDialog = true },
            onOpenLivestream = { showLivestreamDialog = true },
            onOpenUsbImport = {
                deviceVM.fetchUsbImportStatus()
                showUsbImportDialog = true
            },
            onOpenDuplicateScan = {
                when {
                    smartToolsVM.isWorkerRunning || smartToolsVM.isScanningDuplicates -> smartToolsVM.isScanningDuplicates = true
                    smartToolsVM.duplicateFilesList.isNotEmpty() -> smartToolsVM.isShowingDuplicates = true
                    else -> showDuplicateScanDialog = true
                }
            }
        )
        MainMenuSectionSystemLogsSummaryCard()

        MainMenuDashboardTorrentActivityCard(
            onOpenFolder = onOpenFolder,
            onGlobalSearch = onGlobalSearch
        )



        MainMenuDashboardQuickAccessSection(
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
        Spacer(Modifier.height(16.dp))

        // THÔNG BÁO DIALOG
        if (showCommonDialog) {
            AppStatusDialog(
                type = commonDialogType,
                message = commonDialogMessage,
                onDismiss = { showCommonDialog = false }
            )
        }
        // DIALOG THÔNG BÁO TỪ GLOBAL UI VM (Phase 7d.3)
        if (globalUiVM.showCommonDialog) {
            AppStatusDialog(
                type = globalUiVM.commonDialogType,
                message = globalUiVM.commonDialogMessage,
                onDismiss = { globalUiVM.dismiss() }
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
        MainMenuToolboxDialog(
            sharedPrefs = sharedPrefs,
            context = mContext,
            onDismiss = { showToolboxDialog = false },
            onOpenLatestPhotos = onOpenLatestPhotos,
            onOpenRecentVideos = onOpenRecentVideos,
            onOpenTrash = onOpenTrash,
            showAutoBackupDialog = { showAutoBackupDialog = true },
            showLanWhitelistDialog = { showLanWhitelistDialog = true },
            showLivestreamDialog = { showLivestreamDialog = true },
            showNasBackupDialog = { sysMonitorVM.fetchNasConfigBackups(); showNasBackupDialog = true },
            showDiskHealthDialog = { showNewDiskProfileSheet = true },
            showSleepScheduleDialog = { showSleepScheduleDialog = true },
            showBandwidthDialog = { showBandwidthDialog = true },
            showUsbImportDialog = { deviceVM.fetchUsbImportStatus(); showUsbImportDialog = true },
            showDownloadDialog = { showDownloadDialog = true },
            showSmbDialog = { deviceVM.fetchSmbStatus(); showSmbDialog = true },
            showDuplicateScanDialog = { showDuplicateScanDialog = true }
        )
    }
    MainMenuBottomSheetDuplicateScanGlobalUI(mContext)
}


@Composable
private fun MainMenuDashboardHeader(
    realtimeNow: Long,
    showPowerMenu: Boolean,
    onPowerMenuChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onReboot: () -> Unit,
    onShutdown: () -> Unit,
) {
    val sysMonitorVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
    // ═══ HEADER ═══
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Đèn tín hiệu trạng thái (Pulse animation)
        val currentStatus = sysMonitorVM.systemStatus.status
        val isOnlineStatus = currentStatus.contains("Online", true) || currentStatus.contains("Đã kết nối", true)
        val statusColor = when {
            currentStatus.contains("Online", true) || currentStatus.contains("Đã kết nối", true) -> AccentGreen
            currentStatus.contains("Chờ", true) -> AccentOrange
            else -> AccentRed
        }
        Column {
            Text(
                "NAS Dashboard",
                style = AppTypography.HeadlineLarge.copy(color = TextPrimary)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Chainedbox L1 Pro",
                    style = AppTypography.BodyMedium.copy(color = TextSecondary)
                )
                Text("  \u2022  ", style = AppTypography.BodyMedium.copy(color = TextSecondary))
                Box(
                    modifier = Modifier
                        .clip(AppShapes.Badge)
                        .background(if (isOnlineStatus) AccentGreen else AccentRed)
                        .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XXS)
                ) {
                    Text(
                        if (isOnlineStatus) "Online" else "Offline",
                        style = AppTypography.LabelMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(AppSpacing.SM))
                val isRealtimeStale = sysMonitorVM.lastStatusRefreshAt <= 0L || realtimeNow - sysMonitorVM.lastStatusRefreshAt > 10_000L
                val realtimeColor = if (isRealtimeStale) AccentOrange else AccentGreen
                Icon(Icons.Default.Sync, null, tint = realtimeColor, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(AppSpacing.XS))
                Text(
                    realtimeFreshnessLabel(sysMonitorVM.lastStatusRefreshAt, realtimeNow),
                    style = AppTypography.LabelMedium.copy(
                        color = realtimeColor,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                sysMonitorVM.apiLatencyMs?.let { latency ->
                    Spacer(Modifier.width(AppSpacing.SM))
                    Text("API ${latency}ms", style = AppTypography.LabelMedium.copy(color = TextSecondary))
                }
                if (sysMonitorVM.apiFailureCount > 0) {
                    Spacer(Modifier.width(AppSpacing.SM))
                    Text("${sysMonitorVM.apiFailureCount} lỗi", style = AppTypography.LabelMedium.copy(color = AccentRed, fontWeight = FontWeight.Bold))
                }
            }
            
            // ── SMART SWITCH BADGE ──
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (deviceVM.isOnLan) Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFF29B6F6).copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (deviceVM.isOnLan) Icons.Default.NetworkWifi else Icons.Default.Language,
                        contentDescription = null,
                        tint = if (deviceVM.isOnLan) Color(0xFF00E676) else Color(0xFF29B6F6),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (deviceVM.isOnLan) "LAN" else "Tailscale",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (deviceVM.isOnLan) Color(0xFF00E676) else Color(0xFF29B6F6)
                    )
                }
                val ut = sysMonitorVM.systemStatus.uptime
                if (ut.isNotBlank() && ut != "--") {
                    val cleanUt = ut.replace(Regex(",\\s*\\d+\\s*giây"), "")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Schedule, null, tint = AccentCyan, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(cleanUt, fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFAB47BC).copy(alpha = 0.25f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "v${com.nas.naswebdav.BuildConfig.VERSION_NAME}",
                        fontSize = 10.sp,
                        color = Color(0xFFE040FB),
                        fontWeight = FontWeight.Bold
                    )
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
private fun MainMenuDashboardSystemOverviewCard(
    realtimeNow: Long,
    onShowProcessList: (String) -> Unit,
    onOpenNewDiskProfile: () -> Unit,
    onOpenSmartDetails: () -> Unit,
) {
    val sysMonitorVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
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
                PanelFreshnessTag(sysMonitorVM.lastMetricsRefreshAt, realtimeNow, staleAfterMs = 15_000L)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainMenuDashboardGaugeCard(
                    title = "CPU", value = sysMonitorVM.systemStatus.cpu,
                    subValue = sysMonitorVM.systemStatus.cpuTemp,
                    icon = Icons.Default.Memory,
                    gradientColors = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onShowProcessList("cpu")
                    }
                )
                MainMenuDashboardGaugeCard(
                    title = "RAM", value = sysMonitorVM.systemStatus.ram, subValue = "${sysMonitorVM.systemStatus.ramPercent}%",
                    icon = Icons.Default.DeveloperBoard,
                    gradientColors = listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
                    modifier = Modifier.weight(1f),
                    overridePercent = sysMonitorVM.systemStatus.ramPercent.replace("%", "").trim().toFloatOrNull(),
                    onClick = {
                        onShowProcessList("mem")
                    }
                )
                
                val hddDisk = sysMonitorVM.systemStatus.diskParts.firstOrNull { it.mount.startsWith("/srv/dev-disk-by-label-data") }
                    ?: sysMonitorVM.systemStatus.diskParts.find { it.mount != "/" && !it.mount.startsWith("/mnt/usb-import") }
                if (hddDisk != null) {
                    val fmtTotal = hddDisk.total.let {
                        val n = it.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                        if (it.contains("GB", true) && n >= 1000f) "%.1f TB".format(java.util.Locale.US, n / 1024f) else it
                    }
                    MainMenuDashboardGaugeCard(
                        title = "HDD", value = "${hddDisk.used} / $fmtTotal",
                        subValue = "${hddDisk.percent}%",
                        icon = Icons.Default.Storage,
                        gradientColors = listOf(Color(0xFFFFA726), Color(0xFFF57C00)),
                        modifier = Modifier.weight(1f),
                        overridePercent = hddDisk.percent,
                        onClick = onOpenNewDiskProfile
                    )
                } else Spacer(Modifier.weight(1f))
                
                val smartStatusText = deviceVM.smartInfo.status.uppercase().trim()
                
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
                MainMenuDashboardGaugeCard(
                    title = "S.M.A.R.T",
                    value = smartStatusText,
                    subValue = sysMonitorVM.systemStatus.temp.ifEmpty { deviceVM.smartInfo.temperature }.replace("°C", "°").replace("--", ""),
                    icon = Icons.Default.HealthAndSafety,
                    gradientColors = smartColors,
                    modifier = Modifier.weight(1f),
                    overridePercent = smartPercent,
                    onClick = onOpenSmartDetails
                )
            }
                        // Fan Control
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().background(Color(0xFF191919), RoundedCornerShape(6.dp)).padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val fanStatusStr = sysMonitorVM.systemStatus.fanStatus
                                val fanPercentFromApi = sysMonitorVM.systemStatus.fanPercent
                                val statusPercent = Regex("""Đang chạy\s+(\d+)%""").find(fanStatusStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val rpmFromApi = sysMonitorVM.systemStatus.fanRpm ?: Regex("""(\d+)\s*rpm""", RegexOption.IGNORE_CASE).find(fanStatusStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val percentFromRpm = rpmFromApi?.let { rpm -> ((rpm * 100f) / 4300f).toInt() }
                                val rawPercent = fanPercentFromApi ?: statusPercent ?: percentFromRpm ?: 0

                                val displayPercent = if (sysMonitorVM.systemStatus.fanMode == "off") 0 else rawPercent.coerceIn(0, 100)
                                val isFanDisplayRunning = displayPercent > 0
                                val displayStatusStr = if (isFanDisplayRunning) {
                                    val rpm = rpmFromApi ?: (4300f * displayPercent / 100f).toInt()
                                    if (fanStatusStr.contains("Đang chạy")) fanStatusStr else "Đang chạy $displayPercent% - Tốc độ: $rpm rpm"
                                } else {
                                    "Dừng"
                                }
                                FanSpeedIcon(percent = displayPercent, color = if (isFanDisplayRunning) Color(0xFF00E676) else Color(0xFFEF5350), modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Quạt tản nhiệt", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                    Text(displayStatusStr, fontSize = 9.sp, color = if (isFanDisplayRunning) Color(0xFF00E676) else Color(0xFFEF5350))
                                }
                            }
                             // Single Combined Fan Mode Toggle Button (Cycle: Tùy chỉnh -> Bật -> Tắt)
                             var showFanSettings by rememberSaveable { mutableStateOf(false) }
                             val rawMode = sysMonitorVM.systemStatus.fanMode.lowercase()
                             val currentMode = if (rawMode == "auto") "custom" else rawMode
                             val isFanControlLocked = deviceVM.isFanModeUpdating

                             val nextMode = when (currentMode) {
                                 "custom" -> "on"
                                 "on" -> "off"
                                 "off" -> "custom"
                                 else -> "custom"
                             }
                             val currentLabel = when (currentMode) {
                                 "on" -> "Bật 100%"
                                 "off" -> "Tắt"
                                 else -> "Tùy chỉnh"
                             }
                             val badgeColor = when (currentMode) {
                                 "on" -> Color(0xFF00E676)
                                 "off" -> Color(0xFFEF5350)
                                 else -> Color(0xFF00E5FF)
                             }

                             Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                 Box(
                                     modifier = Modifier
                                         .clip(RoundedCornerShape(6.dp))
                                         .background(badgeColor)
                                         .alpha(if (isFanControlLocked) 0.5f else 1f)
                                         .clickable(enabled = !isFanControlLocked) {
                                             deviceVM.setFanMode(nextMode, onSuccess = { sysMonitorVM.triggerStatusUpdate() })
                                         }
                                         .padding(horizontal = 10.dp, vertical = 6.dp)
                                 ) {
                                     Text(currentLabel, fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                 }
                                 if (currentMode == "custom") {
                                     IconButton(
                                         onClick = { showFanSettings = true },
                                         modifier = Modifier.size(24.dp)
                                     ) {
                                         Icon(Icons.Default.Settings, contentDescription = "Cài đặt nhiệt độ", tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                     }
                                 }
                             }
                            
                            if (showFanSettings) {
                                var onTemp by rememberSaveable { mutableStateOf(sysMonitorVM.systemStatus.fanOnTemp.toInt().toString()) }
                                var offTemp by rememberSaveable { mutableStateOf(sysMonitorVM.systemStatus.fanOffTemp.toInt().toString()) }
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
                                        val isFanControlLocked = deviceVM.isFanModeUpdating
                                        Button(
                                            enabled = !isFanControlLocked,
                                            onClick = { 
                                                deviceVM.setFanMode("custom", onTemp.toFloatOrNull() ?: 45f, offTemp.toFloatOrNull() ?: 40f, onSuccess = { sysMonitorVM.triggerStatusUpdate() })
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

@Composable
private fun MainMenuDashboardOmvServicesHardwarePanel() {
    val sysMonitorVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
        // ═══ OMV SERVICES & HARDWARE (Expandable Panel) ═══
        var pendingServiceName by rememberSaveable { mutableStateOf("") }
        var pendingServiceTitle by rememberSaveable { mutableStateOf("") }
        var pendingServiceEnable by rememberSaveable { mutableStateOf(false) }
        if (pendingServiceName.isNotBlank()) {
            AppStatusDialog(
                type = DialogType.CONFIRM,
                message = "${if (pendingServiceEnable) "Bật" else "Tắt"} dịch vụ $pendingServiceTitle?",
                onConfirm = {
                    deviceVM.toggleOmvService(pendingServiceName, pendingServiceEnable)
                    pendingServiceName = ""
                },
                onDismiss = { pendingServiceName = "" }
            )
        }
        if (deviceVM.omvOverview.services.isNotEmpty() || deviceVM.omvOverview.disks.isNotEmpty()) {
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
                            if (deviceVM.omvOverview.omvVersion.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(deviceVM.omvOverview.omvVersion, fontSize = 10.sp, color = TextSecondary)
                            }
                            val ping = sysMonitorVM.networkPingMs
                            if (ping != null) {
                                Spacer(Modifier.width(8.dp))
                                val pingColor = if (ping < 50) Color(0xFF00E676) else if (ping < 150) Color(0xFFFFA726) else Color(0xFFEF5350)
                                Text("${ping}ms", fontSize = 10.sp, color = pingColor, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Tải xuống / Tải lên inline ngay header
                            Text("↓ ${sysMonitorVM.systemStatus.netRx}", fontSize = 9.sp, color = Color(0xFF42A5F5), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("↑ ${sysMonitorVM.systemStatus.netTx}", fontSize = 9.sp, color = Color(0xFFAB47BC), fontWeight = FontWeight.Bold)
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
                            if (deviceVM.omvOverview.services.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    deviceVM.omvOverview.services.forEach { svc ->
                                        val svcActive = svc.effectiveEnabled
                                        val svcColor = if (svcActive) Color(0xFF00E676) else TextSecondary.copy(alpha = 0.45f)
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
                                            Text(svc.title, fontSize = 9.sp, color = svcColor, maxLines = 1, fontWeight = FontWeight.Bold)
                                            Text(if (svcActive) "Bật" else "Tắt", fontSize = 9.sp, color = svcColor.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }

                            // Network + Hardware info
                            val net = deviceVM.omvOverview.network.firstOrNull()
                            val hdd = selectNasTargetDisk(deviceVM.omvOverview.disks)
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
                            
                        }
                    }
                }
            }
        }
}

@Composable
private fun MainMenuDashboardTorrentActivityCard(
    onOpenFolder: (webdavPath: String) -> Unit,
    onGlobalSearch: (String) -> Unit,
) {
    val sysMonitorVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
        // Đã TORRENT ĐANG TẢI & HOÀN THÀNH Đã
        val downloadingTorrents = sysMonitorVM.systemStatus.torrents.filter { t ->
            val s = t.state
            // Active or paused download - NOT yet completed
            s.contains("DL", ignoreCase = false) || s == "downloading" || s == "stalledDL" || s == "forcedDL" || s == "metaDL" || s.isEmpty()
        }
        val completedTorrents = sysMonitorVM.systemStatus.torrents.filter { t ->
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
                            var showTorrentMenu by rememberSaveable { mutableStateOf(false) }
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
                                            if (isPaused) deviceVM.controlTorrent("resume", torrent.hash) else deviceVM.controlTorrent("pause", torrent.hash)
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (isPaused) AccentGreen else AccentOrange, modifier = Modifier.size(12.dp))
                                    }
                                }
                                DropdownMenu(expanded = showTorrentMenu, onDismissRequest = { showTorrentMenu = false }) {
                                    DropdownMenuItem(text = { Text("Tạm dừng") }, leadingIcon = { Icon(Icons.Default.Pause, null, tint = AccentOrange) }, onClick = { showTorrentMenu = false; deviceVM.controlTorrent("pause", torrent.hash) })
                                    DropdownMenuItem(text = { Text("Tiếp tục") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = AccentGreen) }, onClick = { showTorrentMenu = false; deviceVM.controlTorrent("resume", torrent.hash) })
                                    DropdownMenuItem(text = { Text("Xóa", color = AccentRed) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = AccentRed) }, onClick = { showTorrentMenu = false; deviceVM.controlTorrent("delete", torrent.hash) })
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
                            var showCompletedMenu by rememberSaveable { mutableStateOf(false) }
                            androidx.compose.runtime.key(torrent.hash) {
                            com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                onDelete = { deviceVM.controlTorrent("delete", torrent.hash) },
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
                                                    val base = WebDavManager.currentBaseUrl
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
                                    DropdownMenuItem(text = { Text("Xóa khỏi danh sách", color = AccentRed) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = AccentRed) }, onClick = { showCompletedMenu = false; deviceVM.controlTorrent("delete", torrent.hash) })
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
}

@Composable
private fun MainMenuDashboardQuickAccessSection(
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
        Text("TRUY CẬP NHANH", fontSize = PanelTitleSize, fontWeight = FontWeight.Black, color = PanelTitlePurple, letterSpacing = PanelTitleLetterSpacing, modifier = Modifier.padding(bottom = 6.dp))

        // Đã CHỨC NĂNG CHÍNH (Lưới 2x2) Đã 
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MainMenuDashboardBigMenuTile("Quản lý Tệp", "Duyệt & quản lý tệp", Icons.Default.Folder, listOf(Color(0xFFFFCA28), Color(0xFFFF8F00)), Modifier.weight(1f), onClick = onOpenFiles)
            val s2 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot2Id } ?: AVAILABLE_QUICK_ACTIONS[0]
            MainMenuDashboardBigMenuTile(s2.title, s2.subtitle, s2.icon, s2.gradientColors, Modifier.weight(1f), onClick = { onQuickAction(s2.id) }, onLongClick = { onEditingSlotChange(2) })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val s3 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot3Id } ?: AVAILABLE_QUICK_ACTIONS[1]
            MainMenuDashboardBigMenuTile(s3.title, s3.subtitle, s3.icon, s3.gradientColors, Modifier.weight(1f), onClick = { onQuickAction(s3.id) }, onLongClick = { onEditingSlotChange(3) })
            val s4 = AVAILABLE_QUICK_ACTIONS.find { it.id == slot4Id } ?: AVAILABLE_QUICK_ACTIONS[2]
            MainMenuDashboardBigMenuTile(s4.title, s4.subtitle, s4.icon, s4.gradientColors, Modifier.weight(1f), onClick = { onQuickAction(s4.id) }, onLongClick = { onEditingSlotChange(4) })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MainMenuDashboardBigMenuTile("Ảnh gần đây", "Mở ảnh mới nhất", Icons.Default.PhotoLibrary, listOf(Color(0xFF7C4DFF), Color(0xFF00D2FF)), Modifier.weight(1f), onClick = onOpenLatestPhotos)
            MainMenuDashboardBigMenuTile("Video gần đây", "Mở video mới nhất", Icons.Default.VideoLibrary, listOf(Color(0xFFFF6EC7), Color(0xFFFF9100)), Modifier.weight(1f), onClick = onOpenRecentVideos)
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
                    sharedPrefs.edit {
                        putString("qa_slot2", nextSlot2)
                        putString("qa_slot3", nextSlot3)
                        putString("qa_slot4", nextSlot4)
                    }
                    onSlot2Change(nextSlot2)
                    onSlot3Change(nextSlot3)
                    onSlot4Change(nextSlot4)
                    onEditingSlotChange(null)
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        
        // Nút mở Toolbox mở rộng
        Button(
            onClick = { onOpenToolbox() },
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
}


// ============ COMPONENT: Inline stat row (emoji + label + value) ============
@Composable
fun MainMenuDashboardInlineStatRow(emoji: String, label: String, value: String, valueColor: Color) {
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
fun MainMenuDashboardNasInsightsSummaryCard(
    onOpen: () -> Unit
) {
    val sysMonitorVM = LocalSystemMonitorVM.current
    val insight = sysMonitorVM.nasInsights
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
                val liveHddTemp = if (sysMonitorVM.systemStatus.temp.isNotBlank() && sysMonitorVM.systemStatus.temp != "--°C") sysMonitorVM.systemStatus.temp else "${insight.hddTempC}°C"
                MainMenuDashboardInsightMiniStat("HDD", "${insight.hddScore}/100", liveHddTemp, AccentGreen, Modifier.weight(1f))
                MainMenuDashboardInsightMiniStat("eMMC", "${insight.emmcRootPercent}%", "log ${insight.emmcLogPercent}%", if (insight.emmcWarnings.isEmpty()) AccentCyan else AccentOrange, Modifier.weight(1f))
                MainMenuDashboardInsightMiniStat("Ghi HDD", insightRate(insight.diskWriteBps), "đọc ${insightRate(insight.diskReadBps)}", AccentPurple, Modifier.weight(1f))
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
private fun MainMenuDashboardInsightMiniStat(title: String, value: String, sub: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF171922), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(title, color = TextSecondary, fontSize = 10.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MainMenuDashboardGaugeCard(
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
fun MainMenuDashboardMiniStatCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
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
fun MainMenuDashboardDiskPartitionBar(mount: String, percent: Float, total: String, used: String) {
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
fun MainMenuDashboardQuickActionChip(label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
fun MainMenuDashboardBigMenuTile(title: String, subtitle: String, icon: ImageVector, gradientColors: List<Color>, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
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
fun MainMenuSettingsMenuCard(
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
fun MainMenuScreenGetStatusColor(title: String, value: String, rawPercent: String = ""): Color {
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
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return Color.Gray }
}

// Giữ lại MenuCard tương thích cho các file khác nếu cần
@Composable
fun MainMenuDashboardMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    MainMenuSettingsMenuCard(title = title, subtitle = subtitle, icon = icon, color = color, checked = checked, onClick = onClick)
}

@Composable
fun MainMenuDashboardSystemStatusItem(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    MainMenuDashboardMiniStatCard(title = title, value = value, icon = icon, color = color, modifier = modifier)
}



@Composable
fun MainMenuDashboardTemperatureChartCard(history: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
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
                    "${String.format(java.util.Locale.US, "%.1f", lastPoint.first)}°C",
                    lastX - 15f, 
                    lastCpuY - 15f, 
                    paint
                )
                paint.color = "#03A9F4".toColorInt()
                drawContext.canvas.nativeCanvas.drawText(
                    "${String.format(java.util.Locale.US, "%.1f", lastPoint.second)}°C",
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
fun MainMenuToolboxDialog(
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
    val deviceVM = LocalDeviceManagementVM.current
    val smartToolsVM = LocalSmartToolsVM.current
    val livestreamVM = LocalLivestreamVM.current
    var isBiometricEnabled by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("biometric_enabled", false)) }
    var isAutoBackupEnabled by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("auto_backup", false)) }
    var deleteAfterBackup by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("delete_after_backup", false)) }
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

            var showBiometricSettings by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainMenuSettingsMenuCard(
                    title = "Thùng Rác",
                    subtitle = "Khôi phục tệp bị xoá",
                    icon = Icons.Default.Delete,
                    color = Color(0xFFEF5350),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); onOpenTrash() }
                )
                MainMenuSettingsMenuCard(
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
                com.nas.naswebdav.ui.dialogs.BiometricSettingsDialogCompat(
                    sharedPrefs = sharedPrefs,
                    onDismiss = {
                        showBiometricSettings = false
                        // Re-read from SharedPrefs to update card subtitle
                        isBiometricEnabled = sharedPrefs.getBoolean("biometric_enabled", false)
                    }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainMenuSettingsMenuCard(
                    title = "Auto-Backup",
                    subtitle = if (deleteAfterBackup) "Copy & xoá gốc" else "Chỉ copy",
                    icon = Icons.Default.Sync,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f),
                    checked = isAutoBackupEnabled,
                    onClick = { onDismiss(); showAutoBackupDialog() }
                )
                MainMenuSettingsMenuCard(
                    title = "USB Import",
                    subtitle = "Auto copy từ USB gắn ngoài",
                    icon = Icons.Default.Usb,
                    color = Color(0xFF66BB6A),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showUsbImportDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainMenuSettingsMenuCard(
                    title = "USB Import",
                    subtitle = "Tự copy ổ USB 3.0",
                    icon = Icons.Default.Usb,
                    color = Color(0xFF26A69A),
                    modifier = Modifier.weight(1f),
                    checked = deviceVM.usbImportState.settings.enabled,
                    onClick = { onDismiss(); showUsbImportDialog() }
                )
                MainMenuSettingsMenuCard(
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
                MainMenuSettingsMenuCard(
                    title = "Lịch ngủ NAS",
                    subtitle = "HDD spindown ngoài giờ",
                    icon = Icons.Default.Bedtime,
                    color = Color(0xFF7E57C2),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showSleepScheduleDialog() }
                )
                MainMenuSettingsMenuCard(
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
                // Kept as Unit: one-shot status fetch when Toolbox section is composed.
                androidx.compose.runtime.LaunchedEffect(Unit) { deviceVM.loadDockerContainers() }
                MainMenuSettingsMenuCard(
                    title = "Docker / qBittorrent",
                    subtitle = if (deviceVM.isTogglingDocker) "Đang xử lý..." else if (deviceVM.isDockerRunning) "Đang thực thi" else "Đã ngắt",
                    icon = Icons.Default.ViewInAr,
                    color = Color(0xFF1E88E5),
                    modifier = Modifier.weight(1f),
                    onClick = { deviceVM.toggleDockerPower(if (deviceVM.isDockerRunning) "stop" else "start") }
                )
                MainMenuSettingsMenuCard(
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
                MainMenuSettingsMenuCard(
                    title = "Nhật ký hệ thống",
                    subtitle = "Lịch sử tiến trình",
                    icon = Icons.Default.Assignment,
                    color = AccentCyan,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        deviceVM.loadSystemLogs()
                        onDismiss()
                        deviceVM.showLogDialog = true
                    }
                )
                MainMenuSettingsMenuCard(
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
                MainMenuSettingsMenuCard(
                    title = "LAN Whitelist",
                    subtitle = "IP LAN truy cập thẳng",
                    icon = Icons.Default.Wifi,
                    color = Color(0xFF66BB6A),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showLanWhitelistDialog() }
                )
                MainMenuSettingsMenuCard(
                    title = "Ghi Livestream",
                    subtitle = if (livestreamVM.activeLivestreams.isNotEmpty()) "Đang ghi ${livestreamVM.activeLivestreams.size} kênh" else "TikTok / Facebook / YouTube",
                    icon = Icons.Default.Videocam,
                    color = Color(0xFFEE1D52),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showLivestreamDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainMenuSettingsMenuCard(
                    title = "Dọn thùng rác",
                    subtitle = "Xoá rác cũ hơn 30 ngày",
                    icon = Icons.Default.DeleteSweep,
                    color = Color(0xFFEF5350),
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); deviceVM.cleanTrashOnDemand(context, maxAgeDays = 30) }
                )
                MainMenuSettingsMenuCard(
                    title = "Ổ đĩa LAN (SMB)",
                    subtitle = if (deviceVM.isSmbEnabled) "Đang bật — NAS_Data" else "Tắt — bấm để cấu hình",
                    icon = Icons.Default.Dns,
                    color = Color(0xFFFF9800),
                    modifier = Modifier.weight(1f),
                    checked = deviceVM.isSmbEnabled,
                    onClick = { onDismiss(); showSmbDialog() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Kept as Unit: one-shot audit fetch when Toolbox section is composed.
                androidx.compose.runtime.LaunchedEffect(Unit) { smartToolsVM.fetchThumbnailAudit() }
                val thumbAudit by smartToolsVM.thumbnailAudit.collectAsState()
                MainMenuSettingsMenuCard(
                    title = "Kiểm tra Thumbnail",
                    subtitle = thumbAudit?.let { audit ->
                        if (audit.running) "Đang quét..." else "Thiếu ${audit.missing} / Tổng ${audit.total}"
                    } ?: "Thống kê & Quét",
                    icon = Icons.Default.PhotoLibrary,
                    color = Color(0xFFAB47BC),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val canStart = thumbAudit?.let { !it.running && it.missing > 0 } ?: true
                        if (canStart) {
                            smartToolsVM.triggerThumbnailScan()
                            android.widget.Toast.makeText(context, "Đã gửi lệnh quét Thumbnail vào hệ thống ngầm!", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            smartToolsVM.fetchThumbnailAudit()
                        }
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


@Composable
fun MainMenuSystemStatusCards(
    mContext: android.content.Context,
    onOpenAutoBackup: () -> Unit = {},
    onOpenLivestream: () -> Unit = {},
    onOpenUsbImport: () -> Unit = {},
    onOpenDuplicateScan: () -> Unit = {}
) {
    val autoBackupVM = LocalAutoBackupVM.current
    val smartToolsVM = LocalSmartToolsVM.current
    val livestreamVM = LocalLivestreamVM.current
    val deviceVM = LocalDeviceManagementVM.current
    // 1. Thumbnail Status
    // Kept as Unit: screen-scoped polling loop for thumbnail / USB / livestream status.
    LaunchedEffect(Unit) {
        smartToolsVM.fetchThumbStatus()
        livestreamVM.syncLivestreamStateWithServer()
        deviceVM.fetchUsbImportStatus(compact = true, minIntervalMs = 5_000L)
        while (isActive) {
            val interval = if (smartToolsVM.thumbRunning || smartToolsVM.thumbPaused) 2_000L else 10_000L
            kotlinx.coroutines.delay(interval)
            smartToolsVM.fetchThumbStatus()
        }
    }
    val thumbPercent = if (smartToolsVM.thumbTotal > 0) smartToolsVM.thumbGenerated * 100f / smartToolsVM.thumbTotal else 0f
    val thumbIsActive = (smartToolsVM.thumbRunning || smartToolsVM.thumbPaused) && smartToolsVM.thumbGenerated < smartToolsVM.thumbTotal && smartToolsVM.thumbTotal > 0

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
    val dupIsActive = dupIsRunning || dupIsPaused || dupStage == "Đang tổng hợp kết quả..." || smartToolsVM.duplicateFilesList.isNotEmpty()

    // 3. Auto Backup
    val sharedPrefs2 = remember(mContext) { mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE) }
    val autoBackupEnabled = sharedPrefs2.getBoolean("auto_backup", false)
    val autoBackupIsActive = autoBackupVM.isAutoBackupRunning
    
    // 4. Livestream — poll định kỳ để phát hiện job do Watcher daemon tự bắt
    val activeStreams = livestreamVM.activeLivestreams
    // Kept as Unit: screen-scoped polling loop for livestream status.
    LaunchedEffect(Unit) {
        livestreamVM.syncLivestreamStateWithServer()
        while (isActive) {
            livestreamVM.fetchLivestreamStatusOnly(mContext)
            livestreamVM.fetchTikTokLiveWatch(mContext)
            val pollDelay = if (livestreamVM.activeLivestreams.isNotEmpty()) 3_000L else 10_000L
            kotlinx.coroutines.delay(pollDelay)
        }
    }
    val usbImport = deviceVM.usbImportState
    val usbImportIsActive = usbImport.status == "copying" || usbImport.status == "cancelling"
    // Kept as Unit: one-shot initial status fetch; active polling is keyed by usbImport.status below.
    LaunchedEffect(Unit) {
        deviceVM.fetchUsbImportStatus(compact = true, minIntervalMs = 5_000L)
    }
    LaunchedEffect(usbImport.status) {
        while (usbImport.status == "copying" || usbImport.status == "cancelling") {
            kotlinx.coroutines.delay(2_500L)
            deviceVM.fetchUsbImportStatus(compact = true, minIntervalMs = 2_000L)
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
        val tasksExpanded = ExclusivePanelState.current.value == "tasks" || ExclusivePanelState.current.value == null
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
                                            smartToolsVM.thumbTotal > 0 && smartToolsVM.thumbGenerated >= smartToolsVM.thumbTotal -> "✅ Hoàn tất"
                                            smartToolsVM.thumbPaused -> "⏸ Tạm dừng"
                                            smartToolsVM.thumbRunning -> "▶️ Đang tạo thumbnail"
                                            else -> "💤 Tạm nghỉ"
                                        },
                                        fontSize = 11.sp,
                                        color = when {
                                            smartToolsVM.thumbTotal > 0 && smartToolsVM.thumbGenerated >= smartToolsVM.thumbTotal -> Color(0xFF66BB6A)
                                            smartToolsVM.thumbPaused -> Color(0xFFFFA726)
                                            smartToolsVM.thumbRunning -> Color(0xFF66BB6A)
                                            else -> TextSecondary
                                        }
                                    )
                                }
                                if ((smartToolsVM.thumbRunning || smartToolsVM.thumbPaused) && !(smartToolsVM.thumbTotal > 0 && smartToolsVM.thumbGenerated >= smartToolsVM.thumbTotal)) {
                                    IconButton(onClick = { smartToolsVM.toggleThumbPause() }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            if (smartToolsVM.thumbPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            null,
                                            tint = if (smartToolsVM.thumbPaused) Color(0xFF66BB6A) else Color(0xFFFFA726),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { smartToolsVM.fetchThumbStatus() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Refresh, "Làm mới", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                            // Chi tiết thumbnail
                            if (smartToolsVM.thumbLastFile.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tệp: " + smartToolsVM.thumbLastFile.substringAfterLast("/"),
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
                                    Text("${smartToolsVM.thumbGenerated}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF66BB6A))
                                    Text("/ ${smartToolsVM.thumbTotal}", fontSize = 10.sp, color = TextSecondary)
                                    val thumbMissing = smartToolsVM.thumbTotal - smartToolsVM.thumbGenerated
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
                                    val dupStatusLabel = when {
                                        smartToolsVM.duplicateFilesList.isNotEmpty() -> "✅ Đã tìm thấy ${smartToolsVM.duplicateFilesList.size} nhóm trùng"
                                        dupIsPaused -> "⏸ Đã tạm dừng"
                                        !dupIsRunning -> "Chuẩn bị..."
                                        else -> "🟢 Đang quét — Bước $dupStageNum/${dupTotalStages}"
                                    }
                                    val dupStatusColor = when {
                                        smartToolsVM.duplicateFilesList.isNotEmpty() -> Color(0xFF64B5F6) // Xanh dương
                                        dupIsPaused -> Color(0xFFFFA726) // Cam
                                        else -> Color(0xFF66BB6A) // Xanh lá
                                    }
                                    Text(dupStatusLabel, fontSize = 11.sp, color = dupStatusColor)
                                }
                                if (dupIsRunning || dupIsPaused) {
                                    IconButton(onClick = { smartToolsVM.togglePauseDuplicateScan() }, modifier = Modifier.size(32.dp)) {
                                        Icon(if (dupIsPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (dupIsPaused) Color(0xFF66BB6A) else Color(0xFFFFA726), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { smartToolsVM.cancelDuplicateScan(mContext) }, modifier = Modifier.size(32.dp)) {
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
                                    
                                    val speed = if (autoBackupVM.autoBackupUploadSpeedBps > 0L) {
                                        "${com.nas.naswebdav.utils.FormatUtils.formatBytes(autoBackupVM.autoBackupUploadSpeedBps)}/s"
                                    } else if (autoBackupVM.autoBackupElapsedTime > 1000L) {
                                        "Đang đối chiếu..."
                                    } else "Đang chuẩn bị..."

                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Tệp: ${autoBackupVM.autoBackupCurrentFile}", fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(speed, fontSize = 11.sp, color = Color(0xFF66BB6A), modifier = Modifier.padding(start = 4.dp))
                                    }

                                    if (autoBackupVM.autoBackupSourcePath.isNotEmpty()) {
                                        val src = autoBackupVM.autoBackupSourcePath.substringAfterLast("0/").trim('/')
                                        Text("Từ: /$src", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (autoBackupVM.autoBackupDestPath.isNotEmpty()) {
                                        val dst = autoBackupVM.autoBackupDestPath.substringAfter("/webdav/").trim('/')
                                        Text("Lưu: /$dst", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }

                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { autoBackupVM.autoBackupProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = Color(0xFF66BB6A), trackColor = Color(0xFF161616)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Tổng tiến trình: ${autoBackupVM.autoBackupProcessedCount} / ${autoBackupVM.autoBackupTotalCount} tệp", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                                        val totalPercent = if(autoBackupVM.autoBackupTotalCount > 0) (autoBackupVM.autoBackupProcessedCount * 100f / autoBackupVM.autoBackupTotalCount) else 0f
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
                                IconButton(onClick = { deviceVM.cancelUsbImport() }, modifier = Modifier.size(32.dp)) {
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
                                    LaunchedEffect(job.jobId, job.durationSeconds) {
                                        localSeconds = job.durationSeconds
                                    }
                                    LaunchedEffect(job.jobId) {
                                        while (isActive) {
                                            kotlinx.coroutines.delay(1000)
                                            localSeconds++
                                        }
                                    }
                                    val displayDur = "${localSeconds / 3600}h${String.format(java.util.Locale.US, "%02d", (localSeconds % 3600) / 60)}m${String.format(java.util.Locale.US, "%02d", localSeconds % 60)}s"

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

private fun fullUrlToIp(url: String): String = try { java.net.URL(url).host } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { url }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuSectionQuickActionSelectorDialog(
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
fun MainMenuSectionSystemLogsSummaryCard(realtimeNow: Long = System.currentTimeMillis()) {
    val deviceVM = LocalDeviceManagementVM.current
    // Kept as Unit: one-shot log load when this summary card enters composition.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        deviceVM.loadSystemLogs()
    }

    if (deviceVM.systemLogsList.isEmpty()) return
    
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
                PanelFreshnessTag(deviceVM.lastLogsRefreshAt, realtimeNow, staleAfterMs = 30_000L)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { deviceVM.showLogDialog = true },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Xem tất cả", color = AccentCyan, fontSize = 12.sp)
                }
            }
            
            androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val recentLogs = deviceVM.systemLogsList.take(3)
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

fun MainMenuScreenFormatElapsedTimeUI(millis: Long): String {
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
fun MainMenuBottomSheetProcessListBottomSheet(
    sortBy: String,
    onDismiss: () -> Unit
) {
    val systemMonitorVM = LocalSystemMonitorVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.runtime.LaunchedEffect(sortBy) {
        while (isActive) {
            systemMonitorVM.fetchSystemProcesses(context)
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
                if (systemMonitorVM.isLoadingProcesses) {
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
            androidx.compose.material3.HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)

            val displayProcesses = systemMonitorVM.systemProcesses.filter {
                (if (sortBy == "cpu") it.cpu >= 0f else it.mem >= 0f)
            }

            if (displayProcesses.isEmpty() && !systemMonitorVM.isLoadingProcesses) {
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
                items(displayProcesses.size, key = { displayProcesses[it].pid }) { index ->
                    val proc = displayProcesses[index]
                    val isSysEntry = proc.isSystem || proc.pid <= 0
                    val statusColor = when {
                        isSysEntry -> Color(0xFF29B6F6)
                        proc.status == "running" -> Color(0xFF66BB6A)
                        proc.status == "sleeping" -> Color(0xFF9E9E9E)
                        proc.status == "disk-sleep" -> Color(0xFFFFA726)
                        proc.status in listOf("zombie", "dead") -> Color(0xFFEF5350)
                        else -> Color(0xFF9E9E9E)
                    }
                    val statusChar = when {
                        isSysEntry -> "OS"
                        proc.status == "running" -> "R"
                        proc.status == "sleeping" -> "S"
                        proc.status == "disk-sleep" -> "D"
                        proc.status == "zombie" -> "Z"
                        else -> "I"
                    }

                    var showKillConfirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    if (showKillConfirm && !isSysEntry) {
                        AppStatusDialog(
                            type = DialogType.CONFIRM,
                            message = "Bạn có chắc muốn tắt tiến trình ${proc.name} (PID: ${proc.pid}) không?",
                            onConfirm = {
                                showKillConfirm = false
                                systemMonitorVM.killSystemProcess(context, proc.pid)
                            },
                            onDismiss = { showKillConfirm = false }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSysEntry) Color(0xFF15232D) else Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                            .clickable(enabled = !isSysEntry, onClick = { showKillConfirm = true })
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
                            Text(if (isSysEntry) "Hệ điều hành OS" else "${proc.user} (${proc.pid})", color = TextSecondary, fontSize = 10.sp)
                        }
                        val displayValue = if (sortBy == "cpu") "${proc.cpu}%" else "${proc.mem}%"
                        Text(displayValue, color = AccentCyan, fontSize = 12.sp, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        if (!isSysEntry) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Default.Close,
                                contentDescription = "Kill",
                                tint = Color(0xFFEF5350).copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}


@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun MainMenuBottomSheetSmartDetailBottomSheet(
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
            androidx.compose.material3.HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)
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
fun MainMenuBottomSheetSmbBottomSheet(
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
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

                if (deviceVM.isLoadingSmb) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = AccentCyan,
                        strokeWidth = 2.dp
                    )
                } else {
                    androidx.compose.material3.Switch(
                        checked = deviceVM.isSmbEnabled,
                        onCheckedChange = { isChecked ->
                            deviceVM.toggleSmb(context, isChecked)
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

            if (deviceVM.isSmbEnabled) {
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
fun MainMenuBottomSheetDuplicateScanGlobalUI(context: android.content.Context) {
    val smartToolsVM = LocalSmartToolsVM.current
    // 2. Hộp thoại Quét Rác — TÁI THIẾT KẾ HIỂN THỊ CHÍNH XÁC
    if (smartToolsVM.isScanningDuplicates) {
        val scanSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
            // Onclick scrim KHÔNG đóng sheet — user phải bấm nút "Thu nhỏ" / "Huỷ" explicit.
            // Cách làm: onDismissRequest -> mặc định ban đầu đóng sheet -> ta set
            // isScanningDuplicates = false nếu user thu nhỏ thủ công.
            // Với behavior "không đóng khi click ngoài", dismissRequest của sheet phải
            // skip-action: chỉ log + thu nhỏ (= behavior của nút Thu nhỏ).
            onDismissRequest = { smartToolsVM.isScanningDuplicates = false },
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
                    val stage = smartToolsVM.scanDuplicatesStage
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
                        text = smartToolsVM.scanDuplicatesCurrentFolderUrl.ifEmpty { "..." },
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
                        text = smartToolsVM.scanDuplicatesCurrentItemName.ifEmpty { "..." },
                        color = Color(0xFFEF6C00), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // ═══ PROGRESS BAR CHÍNH XÁC (2 THANH) ═══
                    val progressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = smartToolsVM.scanDuplicatesPercent,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "totalProgress"
                    )
                    val stageProgressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = smartToolsVM.scanDuplicatesCurrentStagePercent,
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
                            "Bước ${smartToolsVM.scanDuplicatesStageNumber}/${smartToolsVM.scanDuplicatesTotalStages}",
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
                    if (smartToolsVM.scanDuplicatesStageDescription.isNotEmpty()) {
                        Text(
                            smartToolsVM.scanDuplicatesStageDescription,
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
                        val elapsed = smartToolsVM.scanDuplicatesElapsedTime
                        val etr = smartToolsVM.scanDuplicatesEstimatedTimeRemaining

                        Text("Thời gian chạy: ${FormatUtils.formatElapsedTime(elapsed)}", fontSize = 11.sp, color = Color.Gray)
                        Text(if (etr >= 0) "Ước tính còn: ${FormatUtils.formatElapsedTime(etr)}" else "Đang tính toán...", fontSize = 11.sp, color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)
                    }

                    // ═══ THỐNG KÊ RÕ RÀNG ═══
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${smartToolsVM.scanDuplicatesTotalScanned}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                            Text("Tổng tệp", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${smartToolsVM.scanDuplicatesFound}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                            Text("Trùng lặp", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
                // ── ACTION ROW: Tạm dừng / Huỷ / Thu nhỏ ──
                Spacer(Modifier.height(12.dp))
                if (smartToolsVM.isWorkerRunning && !smartToolsVM.scanDuplicatesStage.contains("Hoàn tất", ignoreCase = true)) {
                    val isPaused by com.nas.naswebdav.DuplicateProgressState.isPaused.collectAsState()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { smartToolsVM.cancelDuplicateScan(context) },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373))
                        ) { Text("Huỷ", color = Color(0xFFE57373), fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = { smartToolsVM.togglePauseDuplicateScan() },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6))
                        ) { Text(if (isPaused) "Tiếp tục" else "Tạm dừng", color = Color(0xFF64B5F6), fontWeight = FontWeight.SemiBold) }
                        Button(
                            onClick = { smartToolsVM.isScanningDuplicates = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) { Text("Thu nhỏ", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                } else {
                    Button(
                        onClick = { smartToolsVM.isScanningDuplicates = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("Đóng", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }



// Hộp thoại Hiển thị danh sách File Trùng Lặp
    if (smartToolsVM.isShowingDuplicates) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { smartToolsVM.isShowingDuplicates = false },
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
                        if (smartToolsVM.duplicateFilesList.isNotEmpty()) {
                            Text(
                                text = "Phát hiện ${smartToolsVM.duplicateFilesList.size} tệp trùng lặp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (smartToolsVM.duplicateFilesList.isEmpty()) {
                        TextButton(onClick = { smartToolsVM.isShowingDuplicates = false; smartToolsVM.selectedDuplicates.clear() }) { 
                            Text("Hoàn tất", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold) 
                        }
                    } else {
                        if (smartToolsVM.selectedDuplicates.isNotEmpty()) {
                            TextButton(onClick = { smartToolsVM.deleteSelectedDuplicates(smartToolsVM.duplicateFilesList) }) {
                                Text("Xóa (${smartToolsVM.selectedDuplicates.size}) mục", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (smartToolsVM.duplicateFilesList.isEmpty()) {
                    Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = Color.Green)
                } else {
                    // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                    // SỬA LỖI: Nhóm theo Hash/Fingerprint thay vì chỉ theo Size để đảm bảo tuyệt đối file có nội dung giống nhau mới nằm chung nhóm
                    val groupedDuplicates = remember(smartToolsVM.duplicateFilesList) {
                        smartToolsVM.duplicateFilesList.groupBy { it.partialHash ?: "${it.contentLength}_${it.name}" }.values.filter { it.size >= 2 }.toList()
                    }

                    // ═══ BỘ LỌC NHANH ═══
                    var selectedFilter by rememberSaveable { mutableStateOf("all") } // all, image, video, doc
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

                    Column(Modifier.fillMaxWidth().weight(1f)) {
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
                                smartToolsVM.selectedDuplicates.clear()
                                groupedDuplicates.forEach { group ->
                                    // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                    // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                    val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                    smartToolsVM.selectedDuplicates.addAll(filesToDelete)
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF2196F3))
                            Spacer(Modifier.width(4.dp))
                            Text("Chọn thông minh", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                        }

                        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                            items(items = filteredGroups, key = { it.first().partialHash ?: "${it.first().contentLength}_${it.first().name}" }) { group ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Nhóm ${group.size} tệp trùng lặp (${android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, group.first().contentLength)})",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        // Hiển thị danh sách file trong nhóm bằng Cuộn Ngang (LazyRow)
                                        androidx.compose.foundation.lazy.LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(items = group, key = { it.path }) { dupFile ->
                                                val isSelected = smartToolsVM.selectedDuplicates.contains(dupFile)
                                                val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                                val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(dupFile.name)
                                                val auth = okhttp3.Credentials.basic(WebDavManager.currentUser, WebDavManager.currentPass)

                                                Box(
                                                    modifier = Modifier
                                                        .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) Color.Red.copy(alpha = 0.2f) else Color.Black)
                                                        .clickable {
                                                            if (isSelected) smartToolsVM.selectedDuplicates.remove(dupFile)
                                                            else smartToolsVM.selectedDuplicates.add(dupFile)
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
                                                            if (it) smartToolsVM.selectedDuplicates.add(dupFile)
                                                            else smartToolsVM.selectedDuplicates.remove(dupFile)
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
                                                        val decodedPath = java.net.URLDecoder.decode(dupFile.path, "UTF-8")
                                                        val parentFolder = decodedPath.substringAfter("/webdav/").substringBeforeLast("/")
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
