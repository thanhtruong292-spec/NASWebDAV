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

// ============ Main menu panels (tach co hoc tu MainMenuScreen.kt - khong doi logic) ============


// ============ COMPONENT: Hộp công cụ Toolbox mở rộng ============
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainMenuToolboxDialog(
    prefsRepo: com.nas.naswebdav.utils.PreferencesRepository,
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
    var isBiometricEnabled by rememberSaveable { mutableStateOf(prefsRepo.isBiometricEnabled()) }
    var isAutoBackupEnabled by rememberSaveable { mutableStateOf(prefsRepo.isAutoBackupFlag()) }
    var deleteAfterBackup by rememberSaveable { mutableStateOf(prefsRepo.isDeleteAfterBackup()) }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); onOpenTrash() }
                )
                MainMenuSettingsMenuCard(
                    title = "Khóa Sinh trắc học",
                    subtitle = if (isBiometricEnabled) {
                        val sec = prefsRepo.getBiometricLockDelaySec()
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
                    prefsRepo = prefsRepo,
                    onDismiss = {
                        showBiometricSettings = false
                        // Re-read để cập nhật subtitle card
                        isBiometricEnabled = prefsRepo.isBiometricEnabled()
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
                    color = MaterialTheme.colorScheme.tertiary,
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    checked = deviceVM.usbImportState.settings.enabled,
                    onClick = { onDismiss(); showUsbImportDialog() }
                )
                MainMenuSettingsMenuCard(
                    title = "Sức khoẻ ổ cứng",
                    subtitle = "SMART + dmesg + điểm",
                    icon = Icons.Default.HealthAndSafety,
                    color = MaterialTheme.colorScheme.error,
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
                    color = AccentPurple,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showSleepScheduleDialog() }
                )
                MainMenuSettingsMenuCard(
                    title = "Giới hạn upload",
                    subtitle = "Tránh nghẽn mạng",
                    icon = Icons.Default.Speed,
                    color = MaterialTheme.colorScheme.primary,
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = { deviceVM.toggleDockerPower(if (deviceVM.isDockerRunning) "stop" else "start") }
                )
                MainMenuSettingsMenuCard(
                    title = "Tải BitTorrent",
                    subtitle = "Magnet, URL, tệp .torrent",
                    icon = Icons.Default.CloudDownload,
                    color = MaterialTheme.colorScheme.primary,
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
                    color = MaterialTheme.colorScheme.primary,
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
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showLanWhitelistDialog() }
                )
                MainMenuSettingsMenuCard(
                    title = "Ghi Livestream",
                    subtitle = if (livestreamVM.activeLivestreams.isNotEmpty()) "Đang ghi ${livestreamVM.activeLivestreams.size} kênh" else "TikTok / Facebook / YouTube",
                    icon = Icons.Default.Videocam,
                    color = AccentRed,
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
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); deviceVM.cleanTrashOnDemand(context, maxAgeDays = 30) }
                )
                MainMenuSettingsMenuCard(
                    title = "Ổ đĩa LAN (SMB)",
                    subtitle = if (deviceVM.isSmbEnabled) "Đang bật — NAS_Data" else "Tắt — bấm để cấu hình",
                    icon = Icons.Default.Dns,
                    color = AccentOrange,
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
                    color = MaterialTheme.colorScheme.secondary,
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
    val prefsRepo2 = remember(mContext) {
        com.nas.naswebdav.utils.PreferencesRepository.get(mContext)
    }
    val autoBackupEnabled = prefsRepo2.isAutoBackupFlag()
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
                                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Tạo ảnh thu nhỏ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(
                                        when {
                                            smartToolsVM.thumbTotal > 0 && smartToolsVM.thumbGenerated >= smartToolsVM.thumbTotal -> "✅ Hoàn tất"
                                            smartToolsVM.thumbPaused -> "⏸ Tạm dừng"
                                            smartToolsVM.thumbRunning -> "▶️ Đang tạo thumbnail"
                                            else -> "💤 Tạm nghỉ"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when {
                                            smartToolsVM.thumbTotal > 0 && smartToolsVM.thumbGenerated >= smartToolsVM.thumbTotal -> MaterialTheme.colorScheme.tertiary
                                            smartToolsVM.thumbPaused -> MaterialTheme.colorScheme.error
                                            smartToolsVM.thumbRunning -> MaterialTheme.colorScheme.tertiary
                                            else -> TextSecondary
                                        }
                                    )
                                }
                                if ((smartToolsVM.thumbRunning || smartToolsVM.thumbPaused) && !(smartToolsVM.thumbTotal > 0 && smartToolsVM.thumbGenerated >= smartToolsVM.thumbTotal)) {
                                    IconButton(onClick = { smartToolsVM.toggleThumbPause() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                        Icon(
                                            if (smartToolsVM.thumbPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            null,
                                            tint = if (smartToolsVM.thumbPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { smartToolsVM.fetchThumbStatus() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                    Icon(Icons.Default.Refresh, "Làm mới", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                            // Chi tiết thumbnail
                            if (smartToolsVM.thumbLastFile.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tệp: " + smartToolsVM.thumbLastFile.substringAfterLast("/"),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 50.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (thumbPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.secondary, trackColor = DarkCard
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                // Số thumbnail đã tạo / tổng
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("✓ Đã tạo:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    Text("${smartToolsVM.thumbGenerated}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                    Text("/ ${smartToolsVM.thumbTotal}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    val thumbMissing = smartToolsVM.thumbTotal - smartToolsVM.thumbGenerated
                                    if (thumbMissing > 0) {
                                        Text("• Còn ${thumbMissing} thiếu", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Text("%.1f%%".format(thumbPercent), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            }
                        }

                        if (showThumbTask && thumbIsActive && (dupIsActive || autoBackupIsActive || usbImportIsActive || activeStreams.isNotEmpty())) {
                            NasHorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
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
                                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Quét trùng lặp", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    val dupStatusLabel = when {
                                        smartToolsVM.duplicateFilesList.isNotEmpty() -> "✅ Đã tìm thấy ${smartToolsVM.duplicateFilesList.size} nhóm trùng"
                                        dupIsPaused -> "⏸ Đã tạm dừng"
                                        !dupIsRunning -> "Chuẩn bị..."
                                        else -> "🟢 Đang quét — Bước $dupStageNum/${dupTotalStages}"
                                    }
                                    val dupStatusColor = when {
                                        smartToolsVM.duplicateFilesList.isNotEmpty() -> AccentBlue // Xanh dương
                                        dupIsPaused -> MaterialTheme.colorScheme.error // Cam
                                        else -> MaterialTheme.colorScheme.tertiary // Xanh lá
                                    }
                                    Text(dupStatusLabel, style = MaterialTheme.typography.bodySmall, color = dupStatusColor)
                                }
                                if (dupIsRunning || dupIsPaused) {
                                    IconButton(onClick = { smartToolsVM.togglePauseDuplicateScan() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                        Icon(if (dupIsPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (dupIsPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { smartToolsVM.cancelDuplicateScan(mContext) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                        Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            // Giai đoạn hiện tại
                            if (dupStage.isNotBlank() && dupStage != "Khởi động...") {
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        dupStage,
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // Mô tả giai đoạn chi tiết
                            if (dupStageDesc.isNotBlank()) {
                                Text(
                                    dupStageDesc,
                                    style = MaterialTheme.typography.labelMedium, color = TextSecondary.copy(alpha = 0.85f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    lineHeight = 13.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { dupPercent.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary, trackColor = DarkCard
                            )
                            Spacer(Modifier.height(6.dp))
                            // Hàng thống kê: số tệp + trùng + thời gian
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$dupScanned", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Text("Tổng tệp", fontSize = 9.sp, color = TextSecondary)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$dupFound", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                        Text("Trùng lặp", fontSize = 9.sp, color = TextSecondary)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(fmtMs(dupElapsed), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                        Text("Thời gian", fontSize = 9.sp, color = TextSecondary)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("%.1f%%".format(dupPercent * 100), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    if (dupEta >= 0) {
                                        Text("Ước tính: ${fmtMs(dupEta)}", fontSize = 9.sp, color = AccentBlue)
                                    }
                                }
                            }
                        }
                          }

                        if (dupIsActive && (autoBackupIsActive || usbImportIsActive || activeStreams.isNotEmpty())) {
                            NasHorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
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
                                    Modifier.size(38.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Đồng Bộ NAS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Spacer(Modifier.height(4.dp))
                                    
                                    val speed = if (autoBackupVM.autoBackupUploadSpeedBps > 0L) {
                                        "${com.nas.naswebdav.utils.FormatUtils.formatBytes(autoBackupVM.autoBackupUploadSpeedBps)}/s"
                                    } else if (autoBackupVM.autoBackupElapsedTime > 1000L) {
                                        "Đang đối chiếu..."
                                    } else "Đang chuẩn bị..."

                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Tệp: ${autoBackupVM.autoBackupCurrentFile}", style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(speed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(start = 4.dp))
                                    }

                                    if (autoBackupVM.autoBackupSourcePath.isNotEmpty()) {
                                        val src = autoBackupVM.autoBackupSourcePath.substringAfterLast("0/").trim('/')
                                        Text("Từ: /$src", style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (autoBackupVM.autoBackupDestPath.isNotEmpty()) {
                                        val dst = autoBackupVM.autoBackupDestPath.substringAfter("/webdav/").trim('/')
                                        Text("Lưu: /$dst", style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }

                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { autoBackupVM.autoBackupProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.tertiary, trackColor = DarkCard
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Tổng tiến trình: ${autoBackupVM.autoBackupProcessedCount} / ${autoBackupVM.autoBackupTotalCount} tệp", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = TextSecondary)
                                        val totalPercent = if(autoBackupVM.autoBackupTotalCount > 0) (autoBackupVM.autoBackupProcessedCount * 100f / autoBackupVM.autoBackupTotalCount) else 0f
                                        Text("%.1f%%".format(totalPercent), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                        }

                        if (autoBackupIsActive && (usbImportIsActive || activeStreams.isNotEmpty())) {
                            NasHorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
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
                                    Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Usb, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("USB Import", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(
                                        if (usbImport.status == "cancelling") "Đang hủy copy USB" else "Đang copy từ ${usbImport.detectedDevicesInfo.ifBlank { usbImport.activeDevice.ifBlank { "ổ USB" } }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (usbImport.currentFile.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Tệp: ${usbImport.currentFile}", style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (usbImport.currentSource.isNotBlank()) {
                                        Text("Từ: ${usbImport.currentSource}", style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (usbImport.currentDest.isNotBlank()) {
                                        Text("Lưu: ${usbImport.currentDest}", style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { usbImportProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = DarkCard
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            "${usbImport.filesDone}/${usbImport.filesTotal} tệp • ${com.nas.naswebdav.utils.FormatUtils.formatBytes(usbImport.bytesProcessed)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = TextSecondary
                                        )
                                        Text(
                                            "${com.nas.naswebdav.utils.FormatUtils.formatBytes(usbImport.copySpeedBps)}/s • ETA ${usbImportEtaLabel(usbImport.etaSeconds)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                IconButton(onClick = { deviceVM.cancelUsbImport() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                    Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        if (usbImportIsActive && activeStreams.isNotEmpty()) {
                            NasHorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
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
                                Box(Modifier.size(30.dp).background(AccentOrange.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Videocam, null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Ghi hình livestream", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text("🔴 Đang ghi hình (${activeStreams.size} kênh)", style = MaterialTheme.typography.labelMedium, color = AccentOrange)
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
                                            Text(primaryLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = TextPrimary)
                                            Text(displayDur, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AccentOrange)
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
                                            Text(secondaryLabel, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(job.fileSize.ifEmpty { "0 B" }, style = MaterialTheme.typography.labelMedium, color = TextPrimary, modifier = Modifier.padding(start = 8.dp))
                                        }
                                        if (job.speed.isNotEmpty()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text("Tốc độ mạng: ${job.speed}", style = MaterialTheme.typography.labelMedium, color = AccentGreen)
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
