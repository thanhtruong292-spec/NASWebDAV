@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.R
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
import com.nas.naswebdav.ui.dialogs.*
import com.nas.naswebdav.utils.FormatUtils
import com.nas.naswebdav.ui.components.NasBottomSheetHandle
import com.nas.naswebdav.ui.components.NasHorizontalDivider
import com.nas.naswebdav.ui.components.NasLoadingSpinner
import com.nas.naswebdav.ui.components.NasModalBottomSheet

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
import androidx.compose.ui.graphics.toArgb
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
                    colors = listOf(TextPrimary, TextSecondary, DarkCardHover),  // metallic orb gradient
                    center = Offset(cx - R * 0.08f, cy - R * 0.08f),
                    radius = R * 0.35f
                ),
                radius = R * 0.22f,
                center = Offset(cx, cy)
            )
            // Hub Core
            drawCircle(
                color = DarkElevated,
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
    // FIX CPU #1: prefs qua PreferencesRepository (không I/O mỗi recomposition).
    val prefsRepo = remember(mContext) {
        com.nas.naswebdav.utils.PreferencesRepository.get(mContext)
    }
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
    var macAddress by rememberSaveable { mutableStateOf(prefsRepo.getMacAddress()) }

    // STATE CHO DIALOG THÔNG BÁO
    var commonDialogMessage by rememberSaveable { mutableStateOf("") }
    var commonDialogType by rememberSaveable { mutableStateOf(DialogType.SUCCESS) }

    // STATE CHO DANH MỤC TRUY CẬP NHANH ĐỘNG
    var slot2Id by rememberSaveable { mutableStateOf(prefsRepo.getQuickSlot("qa_slot2", "sync")) }
    var slot3Id by rememberSaveable { mutableStateOf(prefsRepo.getQuickSlot("qa_slot3", "stream")) }
    var slot4Id by rememberSaveable { mutableStateOf(prefsRepo.getQuickSlot("qa_slot4", "trash")) }
    // Kept as Unit: one-shot SharedPreferences migration (runs once on first composition after
    // this code is introduced; guarded by screen_record_quick_added flag).
    LaunchedEffect(Unit) {
        if (!prefsRepo.isScreenRecordQuickAdded()) {
            slot4Id = "screen_record"
            prefsRepo.setQuickSlot("qa_slot4", "screen_record")
            prefsRepo.setScreenRecordQuickAdded(true)
        }
    }
    var editingSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var showCommonDialog by rememberSaveable { mutableStateOf(false) }

    // UX3: che do Tinh gon (chi file/backup/media) vs Chuyen gia (day du
    // widget sysadmin). Luu prefs de giu lua chon.
    var simpleMode by rememberSaveable { mutableStateOf(prefsRepo.isDashboardSimpleMode()) }

    // STATE CHO XÁC NHẬN NGUỒN VÀ TOOLBOX
    var showPowerMenu by rememberSaveable { mutableStateOf(false) }
    var showRebootConfirm by rememberSaveable { mutableStateOf(false) }
    var showShutdownConfirm by rememberSaveable { mutableStateOf(false) }
    var showToolboxDialog by rememberSaveable { mutableStateOf(false) }

    // STATE CHO AUTO-BACKUP
    var showAutoBackupDialog by rememberSaveable { mutableStateOf(false) }
    var isAutoBackupEnabled by rememberSaveable { mutableStateOf(prefsRepo.isAutoBackupFlag()) }
    var deleteAfterBackup by rememberSaveable { mutableStateOf(prefsRepo.isDeleteAfterBackup()) }

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
    // Load bandwidth limit (1 lần khi mở app)
    // Kept as Unit: one-shot read on screen load.
    LaunchedEffect(Unit) {
        com.nas.naswebdav.AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC = prefsRepo.getUploadSpeedLimit()
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
            icon = { Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
            title = { Text("Quét tệp trùng lặp", fontWeight = FontWeight.Bold, color = TextPrimary) },
            containerColor = DarkCardHover,
            textContentColor = TextPrimary,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Hệ thống sẽ quét toàn bộ NAS và phát hiện tệp có nội dung giống nhau.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, lineHeight = 18.sp)

                    // Option 1: Lightning Mode
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { dupScanLightningMode = !dupScanLightningMode },
                        color = if (dupScanLightningMode) AccentOrange.copy(alpha = 0.1f) else DarkCardHover,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dupScanLightningMode,
                                onCheckedChange = { dupScanLightningMode = it },
                                colors = CheckboxDefaults.colors(checkedColor = AccentOrange)
                            )
                            Column(Modifier.padding(start = 6.dp)) {
                                Text("⚡ Chế độ nhanh (Khuyến nghị)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                    color = if (dupScanLightningMode) AccentOrange else TextPrimary)
                                Text("Bỏ qua hash nội dung, dùng ETag. Nhanh hơn 100×, phù hợp 500k+ tệp.", style = MaterialTheme.typography.bodySmall, color = TextSecondary, lineHeight = 14.sp)
                            }
                        }
                    }

                    // Option 2: Force Restart
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { dupScanForceRestart = !dupScanForceRestart },
                        color = if (dupScanForceRestart) MaterialTheme.colorScheme.error.copy(alpha = 0.1f) else DarkCardHover,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dupScanForceRestart,
                                onCheckedChange = { dupScanForceRestart = it },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error)
                            )
                            Column(Modifier.padding(start = 6.dp)) {
                                Text("Quét lại từ đầu", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                    color = if (dupScanForceRestart) MaterialTheme.colorScheme.error else TextPrimary)
                                Text("Bỏ qua lịch sử lưu tạm, thực hiện quét hoàn toàn mới.", style = MaterialTheme.typography.bodySmall, color = TextSecondary, lineHeight = 14.sp)
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
                        Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mở lại kết quả quét gần nhất", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
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
                    prefsRepo.setMacAddress(wolMac)
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
                if (isAutoBackupEnabled) {
                    // FIX: Kiểm tra quyền TRƯỚC khi ghi prefs & enqueue worker.
                    // Partial access (Android 14+) gây mass "has no access" errors
                    // vì MediaStore trả về tất cả ảnh nhưng openInputStream() bị từ chối.
                    val hasFullAccess = com.nas.naswebdav.utils.MediaPermissionHelper.canRunPeriodicBackup(mContext)
                    if (!hasFullAccess) {
                        // Không đủ quyền → chỉ hiện lỗi, KHÔNG ghi prefs, KHÔNG enqueue worker
                        commonDialogType = DialogType.ERROR
                        commonDialogMessage = if (com.nas.naswebdav.utils.MediaPermissionHelper.hasNoMediaAccess(mContext)) {
                            "Chưa cấp quyền truy cập Media. Vui lòng cấp quyền READ_MEDIA_IMAGES/VIDEO trong Cài đặt trước khi bật Auto-Backup."
                        } else {
                            "Đang ở chế độ quyền ảnh một phần (partial access). Auto-Backup cần quyền truy cập TOÀN BỘ ảnh/video. Vui lòng chọn \"Cho phép tất cả\" trong Cài đặt quyền."
                        }
                        showCommonDialog = true
                    } else {
                        // Đủ quyền → ghi prefs + enqueue worker
                        prefsRepo.setAutoBackupFlag(true)
                        prefsRepo.setDeleteAfterBackup(deleteAfterBackup)
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
                    }
                } else {
                    // Tắt auto-backup → ghi prefs + hủy worker
                    prefsRepo.setAutoBackupFlag(false)
                    prefsRepo.setDeleteAfterBackup(deleteAfterBackup)
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
    if (deviceVM.showFailedUploadsDialog) {
        FailedUploadsDialog(
            failedUploads = deviceVM.failedUploads,
            isLoading = deviceVM.isLoadingFailedUploads,
            onRetry = { deviceVM.retryFailedUpload(mContext, it.id) },
            onRetryAll = { deviceVM.retryAllFailedUploads(mContext) },
            onDelete = { deviceVM.deleteFailedUpload(mContext, it) },
            onDismiss = { deviceVM.showFailedUploadsDialog = false }
        )
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
            prefsRepo = prefsRepo,
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
            "failed_uploads" -> { deviceVM.loadFailedUploads(mContext); deviceVM.showFailedUploadsDialog = true }
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

        // ── NEW: DashboardHeader component ──
        DashboardHeader(
            systemStatus = sysMonitorVM.systemStatus.status,
            networkLabel = if (deviceVM.isOnLan) "LAN" else "Tailscale",
            isOnLan = deviceVM.isOnLan,
            uptime = sysMonitorVM.systemStatus.uptime,
            apiLatencyMs = sysMonitorVM.apiLatencyMs,
            apiFailureCount = sysMonitorVM.apiFailureCount,
            onPowerMenuClick = { showPowerMenu = true },
        )

            Spacer(Modifier.height(8.dp))

        // UX3: toggle Tinh gon / Chuyen gia — Tinh gon an 5 panel sysadmin
        // (SystemOverview, OMV, MonitoringChart, Insights, Torrent), giu
        // QuickAccess + SystemStatus + Logs cho user pho thong.
        androidx.compose.material3.FilterChip(
            selected = simpleMode,
            onClick = {
                simpleMode = !simpleMode
                prefsRepo.setDashboardSimpleMode(simpleMode)
            },
            label = { Text(if (simpleMode) "Chế độ: Tinh gọn" else "Chế độ: Chuyên gia") },
            leadingIcon = {
                Icon(
                    if (simpleMode) Icons.Default.VisibilityOff else Icons.Default.Dashboard,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier.align(Alignment.End)
        )
            Spacer(Modifier.height(8.dp))

        if (!simpleMode) {
        MainMenuDashboardSystemOverviewCard(
            realtimeNow = realtimeNow,
            onShowProcessList = { type ->
                processSortType = type
                showProcessDialog = true
            },
            onOpenNewDiskProfile = { showNewDiskProfileSheet = true },
            onOpenSmartDetails = { showSmartDialog = true },
        )
        }

        if (!simpleMode) {
        MainMenuDashboardOmvServicesHardwarePanel()
        }

        // --- CHÈN BIỂU ĐỒ GIÁM SÁT VÀ BÁO CÁO Ở ĐÂY ---
        if (!simpleMode) {
        Spacer(Modifier.height(8.dp))
        com.nas.naswebdav.ui.screens.MonitoringChartCard()
        Spacer(Modifier.height(8.dp))
        MainMenuDashboardNasInsightsSummaryCard(
            onOpen = {
                sysMonitorVM.fetchNasInsights(minIntervalMs = 0L)
                showNasInsightsDialog = true
            }
        )
        }

        // ── NEW: DashboardAlerts component ──
        run {
            val alerts = mutableListOf<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, com.nas.naswebdav.ui.components.StatusLevel>>()
            // API failures
            if (sysMonitorVM.apiFailureCount > 0) {
                alerts.add(Triple(Icons.Default.Warning, "${sysMonitorVM.apiFailureCount} lỗi API", com.nas.naswebdav.ui.components.StatusLevel.Error))
            }
            // NAS insights
            val insights = sysMonitorVM.nasInsights
            val insightsText = insights.toString()
            if (insightsText.isNotBlank() && insightsText != "NasInsights()") {
                alerts.add(Triple(AppIcons.Info, "NAS Insights có dữ liệu mới", com.nas.naswebdav.ui.components.StatusLevel.Info))
            }
            if (alerts.isNotEmpty()) {
                DashboardAlerts(alerts = alerts)
            }
        }
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

        if (!simpleMode) {
        MainMenuDashboardTorrentActivityCard(
            onOpenFolder = onOpenFolder,
            onGlobalSearch = onGlobalSearch
        )
        }




        MainMenuDashboardQuickAccessSection(
            slot2Id = slot2Id,
            slot3Id = slot3Id,
            slot4Id = slot4Id,
            editingSlot = editingSlot,
            prefsRepo = prefsRepo,
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
            prefsRepo = prefsRepo,
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
                            color = MaterialTheme.colorScheme.onSurface,
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
                        .background(if (deviceVM.isOnLan) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (deviceVM.isOnLan) Icons.Default.NetworkWifi else Icons.Default.Language,
                        contentDescription = null,
                        tint = if (deviceVM.isOnLan) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (deviceVM.isOnLan) "LAN" else "Tailscale",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = if (deviceVM.isOnLan) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                }
                val ut = sysMonitorVM.systemStatus.uptime
                if (ut.isNotBlank() && ut != "--") {
                    val cleanUt = ut.replace(Regex(",\\s*\\d+\\s*giây"), "")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Schedule, null, tint = AccentCyan, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(cleanUt, style = MaterialTheme.typography.bodySmall, color = AccentCyan, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "v${com.nas.naswebdav.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentPurple,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.minimumInteractiveComponentSize().clip(CircleShape).background(DarkCard).clickable { onPowerMenuChange(true) },
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
                    gradientColors = listOf(AccentBlue, AccentPurple),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onShowProcessList("cpu")
                    }
                )
                MainMenuDashboardGaugeCard(
                    title = "RAM", value = sysMonitorVM.systemStatus.ram, subValue = "${sysMonitorVM.systemStatus.ramPercent}%",
                    icon = Icons.Default.DeveloperBoard,
                    gradientColors = listOf(AccentGreen, AccentGreen),
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
                        gradientColors = listOf(MaterialTheme.colorScheme.error, AccentOrange),
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
                    isSmartOk -> listOf(MaterialTheme.colorScheme.tertiary, AccentGreen)
                    isSmartEmmc -> listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary) // Nhận diện eMMC màu Xanh Dương
                    isSmartFailed -> listOf(MaterialTheme.colorScheme.error, AccentRed) // FAILED hiển thị màu Đỏ
                    else -> listOf(TextTertiary, TextTertiary) // Màu xám cho UNKNOWN, ĐANG TẢI, LỖI...
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


            // Fan control expandable panel
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(10.dp)
            ) {
                val fanStatusStr = sysMonitorVM.systemStatus.fanStatus
                val fanPercentFromApi = sysMonitorVM.systemStatus.fanPercent
                val statusPercent = Regex("""Đang chạy\s+(\d+)%""").find(fanStatusStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val rpmFromApi = sysMonitorVM.systemStatus.fanRpm
                    ?: Regex("""(\d+)\s*rpm""", RegexOption.IGNORE_CASE).find(fanStatusStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val percentFromRpm = rpmFromApi?.let { rpm -> ((rpm * 100f) / 4300f).toInt() }
                val rawPercent = fanPercentFromApi ?: statusPercent ?: percentFromRpm ?: 0
                val displayPercent = if (sysMonitorVM.systemStatus.fanMode == "off") 0 else rawPercent.coerceIn(0, 100)
                val isFanDisplayRunning = displayPercent > 0
                val displayStatusStr = if (isFanDisplayRunning) {
                    val rpm = rpmFromApi ?: (4300f * displayPercent / 100f).toInt()
                    if (fanStatusStr.contains("Đang chạy")) fanStatusStr else "Đang chạy $displayPercent% - Tốc độ: $rpm rpm"
                } else "Dừng"
                var fanExpanded by rememberSaveable { mutableStateOf(false) }
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
                    "on" -> MaterialTheme.colorScheme.tertiary
                    "off" -> MaterialTheme.colorScheme.error
                    else -> AccentCyan
                }
                Column(Modifier.padding(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current
                        ) { fanExpanded = !fanExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FanSpeedIcon(
                                percent = displayPercent,
                                color = if (isFanDisplayRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Quạt tản nhiệt", style = MaterialTheme.typography.bodySmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(displayStatusStr, fontSize = 9.sp, color = if (isFanDisplayRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(badgeColor)
                                    .alpha(if (isFanControlLocked) 0.5f else 1f)
                                    .clickable(enabled = !isFanControlLocked) {
                                        deviceVM.setFanMode(nextMode, onSuccess = { sysMonitorVM.triggerStatusUpdate() })
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(currentLabel, style = MaterialTheme.typography.labelMedium, color = DarkSurface, fontWeight = FontWeight.Bold)
                            }
                            Icon(
                                if (fanExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Mở cài đặt quạt",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = fanExpanded && currentMode == "custom") {
                        Column(Modifier.fillMaxWidth()) {
                            HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 6.dp))
                            Text(
                                "Hệ thống sẽ chạy ngầm để bật quạt khi tới 'Nhiệt độ bật', và tắt quạt khi hạ xuống 'Nhiệt độ tắt'.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(6.dp))
                            var onTemp by rememberSaveable { mutableStateOf(sysMonitorVM.systemStatus.fanOnTemp.toInt().toString()) }
                            var offTemp by rememberSaveable { mutableStateOf(sysMonitorVM.systemStatus.fanOffTemp.toInt().toString()) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Bật (°C)", color = TextSecondary, fontSize = 11.sp)
                                    Spacer(Modifier.height(2.dp))
                                    com.nas.naswebdav.ui.components.CompactTextField(
                                        value = onTemp,
                                        onValueChange = { onTemp = it.filter(Char::isDigit) },
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Tắt (°C)", color = TextSecondary, fontSize = 11.sp)
                                    Spacer(Modifier.height(2.dp))
                                    com.nas.naswebdav.ui.components.CompactTextField(
                                        value = offTemp,
                                        onValueChange = { offTemp = it.filter(Char::isDigit) },
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { fanExpanded = false },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    enabled = !deviceVM.isFanModeUpdating,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("Hủy", color = TextSecondary, fontSize = 12.sp) }
                                Button(
                                    onClick = {
                                        deviceVM.setFanMode(
                                            "custom",
                                            onTemp.toFloatOrNull() ?: 45f,
                                            offTemp.toFloatOrNull() ?: 40f,
                                            onSuccess = { sysMonitorVM.triggerStatusUpdate() }
                                        )
                                        fanExpanded = false
                                    },
                                    enabled = !deviceVM.isFanModeUpdating,
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (deviceVM.isFanModeUpdating) "LƯU..." else "Lưu & Áp dụng",
                                        color = DarkSurface,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
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
                            indication = androidx.compose.foundation.LocalIndication.current
                        ) { ExclusivePanelState.toggle("omv") },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dashboard, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OMV", fontSize = PanelTitleSize, fontWeight = FontWeight.Black, color = PanelTitleCyan, letterSpacing = PanelTitleLetterSpacing)
                            if (deviceVM.omvOverview.omvVersion.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(deviceVM.omvOverview.omvVersion, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            }
                            val ping = sysMonitorVM.networkPingMs
                            if (ping != null) {
                                Spacer(Modifier.width(8.dp))
                                val pingColor = if (ping < 50) MaterialTheme.colorScheme.tertiary else if (ping < 150) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error
                                Text("${ping}ms", style = MaterialTheme.typography.labelMedium, color = pingColor, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Tải xuống / Tải lên inline ngay header
                            Text("↓ ${sysMonitorVM.systemStatus.netRx}", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("↑ ${sysMonitorVM.systemStatus.netTx}", fontSize = 9.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
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
                                        val svcColor = if (svcActive) MaterialTheme.colorScheme.tertiary else TextSecondary.copy(alpha = 0.45f)
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
                                                indication = androidx.compose.foundation.LocalIndication.current
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
                                            Text("${net.name} • ${net.speed}Mbps", style = MaterialTheme.typography.labelMedium, color = TextSecondary, letterSpacing = 0.5.sp)
                                            Text("${net.address} | Cổng mạng: ${net.gateway}", style = MaterialTheme.typography.labelMedium, color = AccentBlue)
                                            Text("MAC: ${net.mac}", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.6f))
                                        }
                                    }
                                    if (hdd != null) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(hdd.model, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1)
                                            Text("Số sê-ri: ${hdd.serial}", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.6f))
                                            val sizeGb = (hdd.size.toLongOrNull() ?: 0L) / (1024L * 1024 * 1024)
                                            val sizeTb = if (sizeGb >= 1024) "%.1f TB".format(sizeGb / 1024f) else "$sizeGb GB"
                                            Text(sizeTb, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
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
                            Text("Đang tải xuống (${downloadingTorrents.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
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
                                            Text(torrent.speed, style = MaterialTheme.typography.bodySmall, color = AccentCyan, modifier = Modifier.padding(start = 8.dp))
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
                            androidx.compose.material3.HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Đã hoàn thành (${completedTorrents.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
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
                                    Icon(Icons.Default.Folder, null, tint = AccentOrange, modifier = Modifier.size(16.dp))
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

// Palette hằng số cho menu tile — stable reference, tránh tạo List mỗi recomposition.
private val MenuTilePaletteOrange = kotlinx.collections.immutable.persistentListOf(AccentOrange, AccentOrange)

@Composable
private fun MainMenuDashboardQuickAccessSection(
    slot2Id: String,
    slot3Id: String,
    slot4Id: String,
    editingSlot: Int?,
    prefsRepo: com.nas.naswebdav.utils.PreferencesRepository,
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
            MainMenuDashboardBigMenuTile("Quản lý Tệp", "Duyệt & quản lý tệp", Icons.Default.Folder, MenuTilePaletteOrange, Modifier.weight(1f), onClick = onOpenFiles)
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
            // remember theo theme colors — tránh tạo List mới mỗi recomposition.
            val primaryColor = MaterialTheme.colorScheme.primary
            val errorColor = MaterialTheme.colorScheme.error
            val photoPalette = remember(primaryColor) {
                kotlinx.collections.immutable.persistentListOf(AccentPurple, primaryColor)
            }
            val videoPalette = remember(errorColor) {
                kotlinx.collections.immutable.persistentListOf(AccentPink, errorColor)
            }
            MainMenuDashboardBigMenuTile("Ảnh gần đây", "Mở ảnh mới nhất", Icons.Default.PhotoLibrary, photoPalette, Modifier.weight(1f), onClick = onOpenLatestPhotos)
            MainMenuDashboardBigMenuTile("Video gần đây", "Mở video mới nhất", Icons.Default.VideoLibrary, videoPalette, Modifier.weight(1f), onClick = onOpenRecentVideos)
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
                    prefsRepo.setQuickSlot("qa_slot2", nextSlot2)
                    prefsRepo.setQuickSlot("qa_slot3", nextSlot3)
                    prefsRepo.setQuickSlot("qa_slot4", nextSlot4)
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
                Text("Công cụ & Cài đặt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        }
}


// ============ COMPONENT: Inline stat row (emoji + label + value) ============
@Composable
fun MainMenuDashboardInlineStatRow(emoji: String, label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(5.dp))
        Column {
            Text(label, fontSize = 8.sp, color = TextSecondary, letterSpacing = 0.8.sp)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
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
                    Text(displayMode, color = modeColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                Text("${task.label}: ${task.file.ifBlank { "đang thực thi" }}", color = AccentCyan, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MainMenuDashboardInsightMiniStat(title: String, value: String, sub: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(DarkCard, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(title, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
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
            2 -> MaterialTheme.colorScheme.error // Đỏ
            1 -> MaterialTheme.colorScheme.error // Vàng
            else -> MaterialTheme.colorScheme.tertiary // Xanh
        }
    }

    Card(
        modifier = if (onClick != null) modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = androidx.compose.foundation.LocalIndication.current,
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
                                        isDisk && tempVal >= 55f -> MaterialTheme.colorScheme.error
                                        isDisk && tempVal >= 45f -> MaterialTheme.colorScheme.error
                                        !isDisk && tempVal >= 80f -> MaterialTheme.colorScheme.error
                                        !isDisk && tempVal >= 60f -> MaterialTheme.colorScheme.error // CPU 60+ is Yellow
                                        else -> MaterialTheme.colorScheme.tertiary
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
            Text("$used / $total", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ============ COMPONENT: Thẻ menu lớn (gradient) ============
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainMenuDashboardBigMenuTile(title: String, subtitle: String, icon: ImageVector, gradientColors: kotlinx.collections.immutable.ImmutableList<Color>, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { if (onLongClick != null) onLongClick() else onClick() }
                )
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = gradientColors.first())
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(gradientColors.first())
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
                indication = androidx.compose.foundation.LocalIndication.current
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
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                when { t >= 75f -> AccentRed; t >= 60f -> AccentOrange; t > 0f -> AccentGreen; else -> TextTertiary }
            }
            "CPU", "Ổ đĩa" -> {
                val p = extractNumber(value)
                when { p >= 90f -> AccentRed; p >= 75f -> AccentOrange; p > 0f -> AccentGreen; else -> TextTertiary }
            }
            "RAM" -> {
                val p = if (rawPercent.isNotBlank()) extractNumber(rawPercent) else extractNumber(value)
                when { p >= 90f -> AccentRed; p >= 75f -> AccentOrange; p > 0f -> AccentGreen; else -> TextTertiary }
            }
            else -> TextTertiary
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return TextTertiary }
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
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BIỂU ĐỒ NHIỆT ĐỘ",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                // Legend
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AccentOrange))
                        Spacer(Modifier.width(4.dp))
                        Text("CPU", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AccentBlue))
                        Spacer(Modifier.width(4.dp))
                        Text("HDD", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface)
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
                
                // Đường lưới đứt nét ngang (Grid Lines) — fixed chart palette (intentional, not theme-driven)
                val gridPaint = androidx.compose.ui.graphics.Paint().apply {
                    color = DarkCardHover
                    strokeWidth = 1f
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                }
                for (i in 0..4) {
                    val y = height - (i * (height / 4))
                    drawLine(
                        color = DarkCardHover.copy(alpha = 0.5f),
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
                    color = AccentOrange,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                drawPath(
                    path = hddPath,
                    color = AccentBlue,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                
                // Vẽ điểm gút cuối cùng (cục tròn phát sáng nhẹ)
                val lastPoint = history.last()
                val lastX = (history.size - 1) * pointWidth
                
                val lastCpuY = height - ((lastPoint.first.coerceIn(minTemp, maxTemp) - minTemp) / range * height)
                drawCircle(color = AccentOrange, radius = 6f, center = androidx.compose.ui.geometry.Offset(lastX, lastCpuY))
                drawCircle(color = TextPrimary, radius = 3f, center = androidx.compose.ui.geometry.Offset(lastX, lastCpuY))  // chart endpoint: fixed color

                val lastHddY = height - ((lastPoint.second.coerceIn(minTemp, maxTemp) - minTemp) / range * height)
                drawCircle(color = AccentBlue, radius = 6f, center = androidx.compose.ui.geometry.Offset(lastX, lastHddY))
                drawCircle(color = TextPrimary, radius = 3f, center = androidx.compose.ui.geometry.Offset(lastX, lastHddY))  // chart endpoint: fixed color
                
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
                paint.color = AccentBlue500.toArgb()
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
    NasModalBottomSheet(
        onDismissRequest = onDismiss,
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
                        Box(Modifier.size(36.dp).clip(CircleShape).background(action.gradientColors.first()), contentAlignment = Alignment.Center) {
                            Icon(action.icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(action.subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
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
    val context = LocalContext.current
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
                    indication = androidx.compose.foundation.LocalIndication.current
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
                Spacer(Modifier.width(4.dp))
                val hasUploadErrors = deviceVM.failedUploads.isNotEmpty()
                TextButton(
                    onClick = { deviceVM.loadFailedUploads(context); deviceVM.showFailedUploadsDialog = true },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Icon(
                        if (hasUploadErrors) Icons.Default.CloudOff else Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = if (hasUploadErrors) MaterialTheme.colorScheme.error else AccentGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        if (hasUploadErrors) "Upload lỗi" else "Upload OK",
                        color = if (hasUploadErrors) MaterialTheme.colorScheme.error else AccentGreen,
                        fontSize = 12.sp
                    )
                }
            }
            
            androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val recentGroups = com.nas.naswebdav.ui.dialogs.groupConsecutiveLogs(deviceVM.systemLogsList).take(3)
                    recentGroups.forEach { group ->
                        val logColor = when (group.lastType) {
                            "SUCCESS" -> MaterialTheme.colorScheme.tertiary
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARNING" -> AccentOrange
                            else -> MaterialTheme.colorScheme.primary
                        }
                        val timeStr = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(group.lastTimestamp)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(logColor).padding(top = 4.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(group.module, color = logColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        if (group.count > 1) {
                                            Spacer(Modifier.width(4.dp))
                                            Text("(x${group.count} thông báo)", color = logColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(timeStr, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                                }
                                Text(
                                    com.nas.naswebdav.ui.dialogs.formatLogMessage(group.logs.last().message),
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

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    text = "Tiến Trình (Theo ${if (sortBy == "cpu") "CPU" else "RAM"})",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (systemMonitorVM.isLoadingProcesses) {
                    NasLoadingSpinner(size = 24.dp, color = AccentCyan, strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            
            // Header
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("TIẾN TRÌNH", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
                Text(if (sortBy == "cpu") "CPU" else "RAM", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
NasHorizontalDivider(thickness = 1.dp)

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
                        isSysEntry -> MaterialTheme.colorScheme.primary
                        proc.status == "running" -> MaterialTheme.colorScheme.tertiary
                        proc.status == "sleeping" -> TextTertiary
                        proc.status == "disk-sleep" -> MaterialTheme.colorScheme.error
                        proc.status in listOf("zombie", "dead") -> MaterialTheme.colorScheme.error
                        else -> TextTertiary
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
                            .background(if (isSysEntry) DarkCard else DarkCardHover, RoundedCornerShape(6.dp))
                            .clickable(enabled = !isSysEntry, onClick = { showKillConfirm = true })
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // S badge
                        Box(
                            Modifier.size(20.dp).background(statusColor.copy(alpha=0.2f), androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(statusChar, color = statusColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(proc.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (isSysEntry) "Hệ điều hành OS" else "${proc.user} (${proc.pid})", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                        }
                        val displayValue = if (sortBy == "cpu") "${proc.cpu}%" else "${proc.mem}%"
                        Text(displayValue, color = AccentCyan, fontSize = 12.sp, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        if (!isSysEntry) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Default.Close,
                                contentDescription = "Kill",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
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

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                val statusColor = when {
                    smartInfo.status.uppercase().contains("PASSED") -> MaterialTheme.colorScheme.tertiary
                    smartInfo.status.uppercase().contains("FAILED") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.error
                }
                Text(
                    smartInfo.status.uppercase(),
                    color = statusColor,
                    style = MaterialTheme.typography.titleMedium,
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
                            rawValue.uppercase().contains("GOOD") || rawValue.uppercase().contains("PASSED") -> MaterialTheme.colorScheme.tertiary
                            rawValue.uppercase().contains("BAD") || rawValue.uppercase().contains("FAILED") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.error
                        }
                        rawLabel == "Nhiet do" -> {
                            val temp = rawValue.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                            when {
                                temp >= 55 -> MaterialTheme.colorScheme.error  // Nóng - Đỏ
                                temp >= 45 -> MaterialTheme.colorScheme.error  // Ấm - Vàng
                                else -> MaterialTheme.colorScheme.tertiary         // Mát - Xanh
                            }
                        }
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(140.dp))
                        Text(rawValue, color = valueColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
NasHorizontalDivider(thickness = 1.dp)
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
                        val rowColor = if (isCritical) MaterialTheme.colorScheme.error.copy(alpha = 0.15f) else DarkCardHover
                        val textColor = if (isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(rowColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(id, color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
                            Text(attr.replace("_", " "), color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(value, color = AccentCyan, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            Text(raw, color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    // Fetch thực trạng từ server mỗi khi mở dialog, để Switch luôn đúng
    androidx.compose.runtime.LaunchedEffect(Unit) {
        deviceVM.fetchSmbStatus()
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Map Network Drive cho PC",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                if (deviceVM.isLoadingSmb) {
                    NasLoadingSpinner(size = 24.dp, color = AccentCyan, strokeWidth = 2.dp)
                } else {
                    androidx.compose.material3.Switch(
                        checked = deviceVM.isSmbEnabled,
                        onCheckedChange = { isChecked ->
                            deviceVM.toggleSmb(context, isChecked)
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                            checkedTrackColor = AccentCyan,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (deviceVM.isSmbEnabled) {
                Text(
                    "Truy cập qua máy tính (LAN):",
                    color = AccentCyan,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = DarkCardHover)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Dành cho Windows:", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("\\\\192.168.100.254\\NAS_Data", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { 
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString("\\\\192.168.100.254\\NAS_Data"))
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = AccentCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Dành cho MacOS:", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("smb://192.168.100.254/NAS_Data", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = DarkCardHover)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = "User", tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tài khoản:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                            Text("daica", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = "Password", tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Mật khẩu:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                            Text("(Mật khẩu của App NAS)", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } else {
                Text(
                    "Bật tính năng này để sử dụng NAS như một ổ cứng mạng nội bộ trên máy tính. Tốc độ copy sẽ đạt mức tối đa của mạng LAN mà không qua server trung gian.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
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
        NasModalBottomSheet(
            // Onclick scrim KHÔNG đóng sheet — user phải bấm nút "Thu nhỏ" / "Huỷ" explicit.
            onDismissRequest = { smartToolsVM.isScanningDuplicates = false },
            sheetState = scanSheetState,
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Icon(Icons.Default.FindReplace, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Phát hiện tệp trùng lặp", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Column(Modifier.fillMaxWidth()) {
                    // ═══ GIAI ĐOẠN HIỆN TẠI ═══
                    val stage = smartToolsVM.scanDuplicatesStage
                    val stageColor = when {
                        stage.contains("Thu thập") || stage.contains("nhận") || stage.contains("WebDAV") -> MaterialTheme.colorScheme.primary // Xanh dương
                        stage.contains("Phân tích") -> AccentOrange // Cam
                        stage.contains("Hash") || stage.contains("Xác minh") -> AccentPurple // Tím
                        stage.contains("Hoàn tất") -> AccentGreen // Xanh lá
                        else -> TextTertiary // Xám
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
                            Text(stage, color = stageColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ═══ THƯ MỤC ĐANG QUÉT ═══
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = FileTypeColors.Folder, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Thư mục:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        text = smartToolsVM.scanDuplicatesCurrentFolderUrl.ifEmpty { "..." },
                        color = FileTypeColors.Folder, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // ═══ FILE ĐANG XỬ LÝ ═══
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Đang xử lý:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        text = smartToolsVM.scanDuplicatesCurrentItemName.ifEmpty { "..." },
                        color = AccentOrange, fontSize = 12.sp, fontWeight = FontWeight.Medium,
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
                            Text("Tổng thể", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(60.dp))
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { progressValue.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = AccentGreen,
                                trackColor = AccentGreen.copy(alpha = 0.15f)
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
                            Text("Giai đoạn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(60.dp))
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { stageProgressValue.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = AccentGreen,
                                trackColor = AccentGreen.copy(alpha = 0.15f)
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
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = stageColor
                        )
                        Text(
                            "${(stageProgressValue * 100).toInt()}% giai đoạn",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (smartToolsVM.scanDuplicatesStageDescription.isNotEmpty()) {
                        Text(
                            smartToolsVM.scanDuplicatesStageDescription,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
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

                        Text("Thời gian chạy: ${FormatUtils.formatElapsedTime(elapsed)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(if (etr >= 0) "Ước tính còn: ${FormatUtils.formatElapsedTime(etr)}" else "Đang tính toán...", style = MaterialTheme.typography.bodySmall, color = AccentBlue, fontWeight = FontWeight.Bold)
                    }

                    // ═══ THỐNG KÊ RÕ RÀNG ═══
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${smartToolsVM.scanDuplicatesTotalScanned}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Tổng tệp", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${smartToolsVM.scanDuplicatesFound}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Text("Trùng lặp", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed)
                        ) { Text("Huỷ", color = AccentRed, fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = { smartToolsVM.togglePauseDuplicateScan() },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue)
                        ) { Text(if (isPaused) "Tiếp tục" else "Tạm dừng", color = AccentBlue, fontWeight = FontWeight.SemiBold) }
                        Button(
                            onClick = { smartToolsVM.isScanningDuplicates = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("Thu nhỏ", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                    }
                } else {
                    Button(
                        onClick = { smartToolsVM.isScanningDuplicates = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) { Text("Đóng", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }



// Hộp thoại Hiển thị danh sách File Trùng Lặp
    if (smartToolsVM.isShowingDuplicates) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        NasModalBottomSheet(
            onDismissRequest = { smartToolsVM.isShowingDuplicates = false },
            sheetState = sheetState,
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
                        Text("Tệp trùng lặp", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
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
                            Text("Hoàn tất", color = AccentGreen, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        if (smartToolsVM.selectedDuplicates.isNotEmpty()) {
                            TextButton(onClick = { smartToolsVM.deleteSelectedDuplicates(smartToolsVM.duplicateFilesList) }) {
                                Text("Xóa (${smartToolsVM.selectedDuplicates.size}) mục", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (smartToolsVM.duplicateFilesList.isEmpty()) {
                    Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = AccentGreen)
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
                                    label = { Text(opt.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
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
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = AccentBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("Chọn thông minh", fontWeight = FontWeight.Bold, color = AccentBlue)
                        }

                        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                            items(items = filteredGroups, key = { it.first().partialHash ?: "${it.first().contentLength}_${it.first().name}" }) { group ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
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
                                                val auth = WebDavManager.currentAuthState().authHeader

                                                Box(
                                                    modifier = Modifier
                                                        .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) MaterialTheme.colorScheme.error.copy(alpha = 0.2f) else DarkSurface)
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
                                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                                    }

                                                    // 2. Lớp phủ đỏ mờ nếu đang được tick chọn xóa
                                                    if (isSelected) {
                                                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error.copy(alpha = 0.4f)))
                                                    }

                                                    // 3. Checkbox nằm góc trên phải
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = {
                                                            if (it) smartToolsVM.selectedDuplicates.add(dupFile)
                                                            else smartToolsVM.selectedDuplicates.remove(dupFile)
                                                        },
                                                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error, uncheckedColor = MaterialTheme.colorScheme.onSurface)
                                                    )

                                                    // 4. Tên file + thư mục cha đè ở dưới cùng (Để phân biệt các file)
                                                    Column(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .fillMaxWidth()
                                                            .background(DarkSurface.copy(alpha = 0.75f))
                                                            .padding(horizontal = 4.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(
                                                            text = dupFile.name,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                            lineHeight = 10.sp
                                                        )
                                                        val decodedPath = java.net.URLDecoder.decode(dupFile.path, "UTF-8")
                                                        val parentFolder = decodedPath.substringAfter("/webdav/").substringBeforeLast("/")
                                                        Text(
                                                            text = "📁 $parentFolder",
                                                            fontSize = 7.sp,
                                                            color = MaterialTheme.colorScheme.outline,
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
