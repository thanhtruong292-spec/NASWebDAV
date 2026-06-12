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

// ============ ToolboxDialog (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

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

            // Đồng bộ trạng thái THỰC TẾ của các switch khi mở Toolbox (giống Docker bên dưới)
            // để switch phản ánh đúng hiện trạng server thay vì giá trị mặc định/cũ.
            // Trước đây chỉ Docker fetch khi mở -> SMB/USB Import hiển thị sai cho tới khi
            // người dùng mở riêng dialog tương ứng.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                viewModel.fetchSmbStatus()
                viewModel.fetchUsbImportStatus()
            }

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
            var showTelegram by remember { mutableStateOf(false) }
            var showRules by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenuCard(
                    title = "Thông báo Telegram",
                    subtitle = "Cảnh báo ghi live / ổ cứng",
                    icon = Icons.Default.Notifications,
                    color = Color(0xFF29B6F6),
                    modifier = Modifier.weight(1f),
                    onClick = { showTelegram = true }
                )
                SettingsMenuCard(
                    title = "Quy tắc cảnh báo",
                    subtitle = "Ngưỡng ổ/nhiệt/RAM + hành động",
                    icon = Icons.Default.Tune,
                    color = Color(0xFFFFB300),
                    modifier = Modifier.weight(1f),
                    onClick = { showRules = true }
                )
            }
            if (showTelegram) {
                com.nas.naswebdav.ui.dialogs.TelegramSettingsDialog(viewModel = viewModel, onDismiss = { showTelegram = false })
            }
            if (showRules) {
                com.nas.naswebdav.ui.dialogs.RulesSettingsDialog(viewModel = viewModel, onDismiss = { showRules = false })
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



