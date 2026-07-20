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
import androidx.core.content.edit

// ============ Disk Profile bottom sheet (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

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

fun selectNasTargetDisk(disks: List<OmvDiskInfo>): OmvDiskInfo? =
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
internal fun DiskProfileBottomSheet(
    onDismiss: () -> Unit
) {
    // → SystemMonitorVM (diskHealthCurrent/History, isFetchingDiskHealth,
    // nasInsights, storageFolderUsage, omvOverview) is SSoT.
    val sysMonitorVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
    val autoBackupVM = LocalAutoBackupVM.current
    val livestreamVM = LocalLivestreamVM.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nas_hardware_profile", Context.MODE_PRIVATE) }
    var showTrackingConfirm by remember { mutableStateOf(false) }
    var showResetTrackingConfirm by remember { mutableStateOf(false) }
    var writePanelExpanded by remember { mutableStateOf(false) }
    var operationMode by remember { mutableStateOf(prefs.getString("operation_mode", "balanced") ?: "balanced") }
    val hddDisk = sysMonitorVM.systemStatus.diskParts
        .filter { it.mount != "/" && !it.mount.startsWith("/mnt/usb-import") }
        .sortedByDescending { it.mount.startsWith("/srv/dev-disk-by-label-data") }
        .maxByOrNull { parseProfileSizeBytes(it.total) }
    val omvDisk = selectNasTargetDisk(deviceVM.omvOverview.disks)
    val activeDiskKey = profileDiskKey(omvDisk, hddDisk?.mount ?: "unknown")
    var installedAt by remember(activeDiskKey) {
        val serialValue = prefs.getLong("${activeDiskKey}_installed_at", 0L)
        val legacyValue = prefs.getLong("toshiba_n300_installed_at", 0L)
        mutableStateOf(if (serialValue > 0L) serialValue else legacyValue)
    }
    val isTrackingNewDisk = installedAt > 0L
    val usedPercent = hddDisk?.percent ?: profilePercent(sysMonitorVM.systemStatus.disk)
    val remainingPercent = (100f - usedPercent).coerceIn(0f, 100f)
    val fsBytes = deviceVM.omvOverview.filesystems
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
    val activeRecordings = if (isTrackingNewDisk) livestreamVM.activeLivestreams.size else 0
    val completedLivestreamLogsToday = deviceVM.systemLogsList.filter { log ->
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
    val smartTemp = deviceVM.smartInfo.temperature
        .replace("\u00c2\u00b0C", "\u00b0C")
        .replace("--", "Chưa có dữ liệu")
    val smartStatus = deviceVM.smartInfo.status
    val diskHealth = sysMonitorVM.diskHealthCurrent
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
    val downloadTasks = if (isTrackingNewDisk) sysMonitorVM.systemStatus.torrents.count { torrent ->
        val state = torrent.state
        state.contains("DL", ignoreCase = false) || state == "downloading" || state == "stalledDL" || state == "forcedDL" || state == "metaDL"
    } else 0
    val heavyWriteTasks = activeRecordings + downloadTasks + if (isTrackingNewDisk && autoBackupVM.isAutoBackupRunning) 1 else 0
    val estimatedActiveWriteGb = activeRecordings * 8 + downloadTasks * 20 + if (isTrackingNewDisk && autoBackupVM.isAutoBackupRunning) 30 else 0
    val completedLivestreamWriteGb = (completedLivestreamBytesToday / (1024.0 * 1024.0 * 1024.0)).toInt()
    val estimatedActualWriteGb = estimatedActiveWriteGb + completedLivestreamWriteGb
    val actualForecastDays = if (isTrackingNewDisk && estimatedActualWriteGb > 0) ((estimatedFreeTiB * 1024f) / estimatedActualWriteGb).toInt().coerceAtLeast(0) else remainingDays
    val monthlyBudgetTb = enduranceTbPerYear?.let { it / 12 } ?: 0
    val backupTasks = if (isTrackingNewDisk && autoBackupVM.isAutoBackupRunning) 1 else 0
    val writeRiskLabel = when {
        !isTrackingNewDisk -> "Chưa phân tích"
        heavyWriteTasks >= 4 -> "Khối lượng ghi cao"
        heavyWriteTasks >= 2 -> "Khối lượng ghi trung bình"
        heavyWriteTasks == 1 -> "Khối lượng ghi thấp"
        else -> "Trạng thái rảnh (Không ghi)"
    }
    val cpuLoad = profilePercent(sysMonitorVM.systemStatus.cpu)
    val ramLoad = profilePercent(sysMonitorVM.systemStatus.ramPercent)
    val storageUsage = deviceVM.storageFolderUsage
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
        deviceVM.loadSystemLogs()
    }

    if (showTrackingConfirm) {
        AppStatusDialog(
            type = DialogType.CONFIRM,
            message = "Chỉ đặt mốc theo dõi sau khi đã xác nhận ổ dữ liệu hiện tại là ổ cần theo dõi. Mốc này gắn với model/serial ổ để tính checklist 24 giờ, 7 ngày và 30 ngày.",
            onConfirm = {
                val now = System.currentTimeMillis()
                prefs.edit {
                    putLong("${activeDiskKey}_installed_at", now)
                    putString("${activeDiskKey}_model", diskModel)
                    putString("${activeDiskKey}_serial", diskSerial)
                }
                deviceVM.logUserAction("DiskProfile", "Thiết lập điểm kiểm soát ổ đĩa: $diskModel ($diskSerial).")
                installedAt = now
                showTrackingConfirm = false
            },
            onDismiss = { showTrackingConfirm = false }
        )
    }
    if (showResetTrackingConfirm) {
        AppStatusDialog(
            type = DialogType.WARNING,
            message = "Thao tác này đưa hồ sơ ổ mới về trạng thái chưa theo dõi và các số liệu sẽ trở lại 0 cho đến khi đặt mốc mới.",
            onConfirm = {
                prefs.edit {
                    remove("${activeDiskKey}_installed_at")
                    remove("${activeDiskKey}_model")
                    remove("${activeDiskKey}_serial")
                }
                deviceVM.logUserAction("DiskProfile", "Đặt lại mốc theo dõi hồ sơ ổ đĩa.")
                installedAt = 0L
                showResetTrackingConfirm = false
            },
            onDismiss = { showResetTrackingConfirm = false }
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
                        deviceVM.logUserAction("DiskProfile", "Thay đổi hồ sơ hoạt động ổ cứng thành: $it.")
                    }
                    OperationModeChip("balanced", "Cân bằng", operationMode, prefs) {
                        operationMode = it
                        deviceVM.logUserAction("DiskProfile", "Thay đổi hồ sơ hoạt động ổ cứng thành: $it.")
                    }
                    OperationModeChip("eco", "Tiết kiệm", operationMode, prefs) {
                        operationMode = it
                        deviceVM.logUserAction("DiskProfile", "Thay đổi hồ sơ hoạt động ổ cứng thành: $it.")
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
                    Text(if (deviceVM.isFetchingStorageUsage) "Đang tải" else trashWarning, color = TextSecondary, fontSize = 10.sp)
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
                prefs.edit { putString("operation_mode", mode) }
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
