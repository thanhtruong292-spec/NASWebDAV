@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*

import android.content.Context
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

// ============ ToolboxDialog (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolboxDialog(
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
    val deviceVM = com.nas.naswebdav.LocalDeviceManagementVM.current
    val smartToolsVM = com.nas.naswebdav.LocalSmartToolsVM.current
    val livestreamVM = com.nas.naswebdav.LocalLivestreamVM.current
    // ═══ PHASE 7c.2: Toolbox state (isSmbEnabled, isLoadingSmb, isFanModeUpdating,
    // dockerContainers) reads via facade delegation → DeviceManagementVM is SSoT. ═══
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
                .padding(horizontal = AppSpacing.SM, vertical = 6.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            Text(
                text = "CÔNG CỤ HỆ THỐNG",
                style = AppTypography.LabelLarge.copy(
                    color = PanelTitleCyan,
                    fontWeight = FontWeight.Black,
                    letterSpacing = PanelTitleLetterSpacing
                ),
                modifier = Modifier.padding(bottom = AppSpacing.SM)
            )

            // Đồng bộ trạng thái THỰC TẾ của các switch khi mở Toolbox (giống Docker bên dưới)
            // để switch phản ánh đúng hiện trạng server thay vì giá trị mặc định/cũ.
            // Trước đây chỉ Docker fetch khi mở -> SMB/USB Import hiển thị sai cho tới khi
            // người dùng mở riêng dialog tương ứng.
            // Kept as Unit: one-shot status refresh when Toolbox opens.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                deviceVM.fetchSmbStatus()
                deviceVM.fetchUsbImportStatus()
            }

            var showBiometricSettings by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                MainMenuSettingsMenuCard(
                    title = "Thùng Rác",
                    subtitle = "Khôi phục tệp bị xoá",
                    icon = Icons.Default.Delete,
                    color = AccentRed,
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
            Spacer(Modifier.height(AppSpacing.SM))
            if (showBiometricSettings) {
                com.nas.naswebdav.ui.dialogs.DialogsBiometricSettingsDialog(
                    sharedPrefs = sharedPrefs,
                    onDismiss = {
                        showBiometricSettings = false
                        // Re-read from SharedPrefs to update card subtitle
                        isBiometricEnabled = sharedPrefs.getBoolean("biometric_enabled", false)
                    }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                MainMenuSettingsMenuCard(
                    title = "Auto-Backup",
                    subtitle = if (deleteAfterBackup) "Copy & xoá gốc" else "Chỉ copy",
                    icon = Icons.Default.Sync,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f),
                    checked = isAutoBackupEnabled,
                    onClick = { onDismiss(); showAutoBackupDialog() }
                )
            }
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                MainMenuSettingsMenuCard(
                    title = "USB Import",
                    subtitle = "Tự copy từ USB 3.0",
                    icon = Icons.Default.Usb,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f),
                    checked = deviceVM.usbImportState.settings.enabled,
                    onClick = { onDismiss(); showUsbImportDialog() }
                )
            }
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                MainMenuSettingsMenuCard(
                    title = "Sao lưu cấu hình NAS",
                    subtitle = "Config + watcher + cookies",
                    icon = Icons.Default.SettingsBackupRestore,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showNasBackupDialog() }
                )
                MainMenuSettingsMenuCard(
                    title = "Sức khoẻ ổ cứng",
                    subtitle = "SMART + dmesg + điểm",
                    icon = Icons.Default.HealthAndSafety,
                    color = AccentOrange,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showDiskHealthDialog() }
                )
            }
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
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
                    color = AccentCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showBandwidthDialog() }
                )
            }
            Spacer(Modifier.height(AppSpacing.SM))
            var showTelegram by remember { mutableStateOf(false) }
            var showRules by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                MainMenuSettingsMenuCard(
                    title = "Thông báo Telegram",
                    subtitle = "Cảnh báo ghi live / ổ cứng",
                    icon = Icons.Default.Notifications,
                    color = AccentCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { showTelegram = true }
                )
                MainMenuSettingsMenuCard(
                    title = "Quy tắc cảnh báo",
                    subtitle = "Ngưỡng ổ/nhiệt/RAM + hành động",
                    icon = Icons.Default.Tune,
                    color = AccentOrange,
                    modifier = Modifier.weight(1f),
                    onClick = { showRules = true }
                )
            }
            if (showTelegram) {
                com.nas.naswebdav.ui.dialogs.TelegramSettingsDialog(onDismiss = { showTelegram = false })
            }
            if (showRules) {
                com.nas.naswebdav.ui.dialogs.RulesSettingsDialog(onDismiss = { showRules = false })
            }
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                // Kept as Unit: one-shot Docker status refresh when this card is composed.
                androidx.compose.runtime.LaunchedEffect(Unit) { deviceVM.checkDockerStatus() }
                MainMenuSettingsMenuCard(
                    title = "Docker / qBittorrent",
                    subtitle = if (deviceVM.isTogglingDocker) "Đang xử lý..." else if (deviceVM.isDockerRunning) "Đang thực thi" else "Đã ngắt",
                    icon = Icons.Default.ViewInAr,
                    color = AccentBlue,
                    modifier = Modifier.weight(1f),
                    checked = deviceVM.isDockerRunning,
                    onClick = { deviceVM.toggleDockerPower(if (deviceVM.isDockerRunning) "stop" else "start") }
                )
                MainMenuSettingsMenuCard(
                    title = "Tải BitTorrent",
                    subtitle = "Magnet, URL, tệp .torrent",
                    icon = Icons.Default.CloudDownload,
                    color = AccentGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showDownloadDialog() }
                )
            }
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
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
                    color = AccentCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { onDismiss(); showDuplicateScanDialog() }
                )
            }
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                MainMenuSettingsMenuCard(
                    title = "LAN Whitelist",
                    subtitle = "IP LAN truy cập thẳng",
                    icon = Icons.Default.Wifi,
                    color = AccentGreen,
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
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                MainMenuSettingsMenuCard(
                    title = "Dọn thùng rác",
                    subtitle = "Xoá rác cũ hơn 30 ngày",
                    icon = Icons.Default.DeleteSweep,
                    color = AccentRed,
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
            Spacer(Modifier.height(AppSpacing.SM))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                // Kept as Unit: one-shot audit fetch when this card is composed.
                androidx.compose.runtime.LaunchedEffect(Unit) { smartToolsVM.fetchThumbnailAudit() }
                val thumbAudit by smartToolsVM.thumbnailAudit.collectAsState()
                val ta = thumbAudit
                MainMenuSettingsMenuCard(
                    title = "Kiểm tra Thumbnail",
                    // #2: ghi rõ đây là số liệu của LẦN QUÉT GẦN NHẤT (quét theo cửa sổ
                    // ~15k entry/lần), không phải audit toàn bộ thư viện -> tránh hiểu nhầm
                    // "Thiếu 0" = cả NAS đã đủ thumbnail.
                    subtitle = when {
                        ta == null -> "Thống kê & Quét"
                        ta.running -> "Đang quét nền... ${ta.thumbnailed}/${ta.total}"
                        else -> "Quét gần nhất: thiếu ${ta.missing}/${ta.total}"
                    },
                    icon = Icons.Default.PhotoLibrary,
                    color = AccentPurple,
                    modifier = Modifier.weight(1f),
                    // #1: luôn có phản hồi + luôn cho phép kích hoạt quét tiếp (kể cả khi
                    // cửa sổ gần nhất không thiếu, vì có thể còn file ngoài cửa sổ/cursor).
                    onClick = {
                        when {
                            ta != null && ta.running -> {
                                smartToolsVM.fetchThumbnailAudit()
                                android.widget.Toast.makeText(context, "Đang quét nền — đã làm mới trạng thái.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            ta != null && ta.missing > 0 -> {
                                smartToolsVM.triggerThumbnailScan()
                                android.widget.Toast.makeText(context, "Đã gửi lệnh quét ${ta.missing} ảnh còn thiếu vào nền.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                smartToolsVM.triggerThumbnailScan()
                                android.widget.Toast.makeText(context, "Đã kích hoạt quét tiếp phần còn lại của thư viện.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(AppSpacing.LG))
        }
    }
}



