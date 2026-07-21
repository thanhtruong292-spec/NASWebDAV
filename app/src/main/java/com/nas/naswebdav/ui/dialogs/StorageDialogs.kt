@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.screens.WebDavCachedThumbnail

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ============ Storage / disk health / NAS backup / USB import / insights dialogs
//              (tách cơ học từ Dialogs.kt — không đổi logic) ============

// ====================================================================
// DIALOG SUC KHOE O CUNG — Hien score, attributes, warnings tu SMART
// + dmesg + io stats. Goi /api/disk/health.
// ====================================================================


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbImportDialog(
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val state = deviceVM.usbImportState
    val settings = state.settings
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var autoMount by remember(settings) { mutableStateOf(settings.autoMount) }
    var mountReadonly by remember(settings) { mutableStateOf(settings.mountReadonly) }
    var resumeEnabled by remember(settings) { mutableStateOf(settings.resumeEnabled) }
    var verifyChecksum by remember(settings) { mutableStateOf(settings.verifyChecksum) }
    var copyMode by remember(settings) { mutableStateOf(settings.copyMode) }
    var destFolder by remember(settings) { mutableStateOf(settings.destFolder) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { deviceVM.fetchUsbImportStatus() }
    val isPollingStatus = state.status == "copying" || state.status == "cancelling"
    LaunchedEffect(isPollingStatus) {
        while (isPollingStatus) {
            delay(2500)
            deviceVM.fetchUsbImportStatus(compact = true, minIntervalMs = 2_000L)
        }
    }

    val fileCountProgress = if (state.filesTotal > 0) {
        (state.filesDone + state.filesSkipped + state.filesFailed).toFloat() / state.filesTotal.toFloat()
    } else 0f
    val progress = if (state.bytesTotal > 0L) {
        state.bytesProcessed.toFloat() / state.bytesTotal.toFloat()
    } else fileCountProgress
    val currentFileProgress = if (state.currentFileBytesTotal > 0L) {
        state.currentFileBytesDone.toFloat() / state.currentFileBytesTotal.toFloat()
    } else 0f
    fun usbEtaLabel(seconds: Long): String {
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
    val isRunning = state.status == "copying" || state.status == "cancelling"
    val statusColor = when (state.status) {
        "copying" -> Color(0xFF42A5F5)
        "done" -> Color(0xFF66BB6A)
        "error" -> Color(0xFFEF5350)
        "disabled" -> Color(0xFF8892B0)
        else -> Color(0xFFFFA726)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 820.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Usb, null, tint = Color(0xFF26A69A), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("USB Import", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { deviceVM.fetchUsbImportStatus() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, "Làm mới", tint = Color(0xFF8892B0), modifier = Modifier.size(18.dp))
                }
            }
            Text(
                "NAS tự phát hiện ổ cứng/USB gắn qua cổng USB 3.0 và copy dữ liệu vào thư mục USB Import.",
                color = Color(0xFF8892B0),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF15151D), RoundedCornerShape(8.dp)).padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(state.status.uppercase(), color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (deviceVM.isUsbImportLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF42A5F5), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(state.message.ifBlank { "Đang chờ trạng thái từ NAS" }, color = Color.White, fontSize = 13.sp)
                if (state.detectedDevicesInfo.isNotBlank()) {
                    Text("Đã phát hiện: ${state.detectedDevicesInfo}", color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                if (state.activeDevice.isNotBlank() || state.destDir.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (state.activeDevice.isNotBlank()) {
                            Text(
                                state.activeDevice,
                                color = Color(0xFF8892B0),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.32f)
                            )
                        }
                        if (state.activeDevice.isNotBlank() && state.destDir.isNotBlank()) {
                            Icon(
                                Icons.Default.ArrowForward,
                                null,
                                tint = Color(0xFF42A5F5),
                                modifier = Modifier.size(14.dp).padding(horizontal = 2.dp)
                            )
                        }
                        if (state.destDir.isNotBlank()) {
                            Text(
                                state.destDir,
                                color = Color(0xFF8892B0),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.68f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (state.currentFile.isNotBlank()) {
                    Text("File đang copy", color = Color(0xFF8892B0), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.currentFile, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.currentSource.isNotBlank()) {
                        Text("Từ: ${state.currentSource}", color = Color(0xFF8892B0), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { currentFileProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)),
                        color = Color(0xFF26A69A),
                        trackColor = Color(0xFF2A2A3E)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.currentFileBytesDone)} / ${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.currentFileBytesTotal)}",
                            color = Color(0xFFE8E8E8),
                            fontSize = 10.sp
                        )
                        Text(
                            "${(currentFileProgress * 100f).toInt()}%",
                            color = Color(0xFF8892B0),
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text("Tổng tiến trình", color = Color(0xFF8892B0), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                    color = statusColor,
                    trackColor = Color(0xFF2A2A3E)
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${state.filesDone}/${state.filesTotal} file", color = Color(0xFFE8E8E8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.bytesTotal > 0L)
                            "${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.bytesProcessed)} / ${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.bytesTotal)}"
                        else com.nas.naswebdav.utils.FormatUtils.formatBytes(state.bytesDone),
                        color = Color(0xFF8892B0),
                        fontSize = 11.sp
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bỏ qua ${state.filesSkipped} • Lỗi ${state.filesFailed}", color = Color(0xFF8892B0), fontSize = 10.sp)
                    Text(
                        "${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.copySpeedBps)}/s • ETA ${usbEtaLabel(state.etaSeconds)}",
                        color = Color(0xFF8892B0),
                        fontSize = 10.sp
                    )
                }
                if (state.lastError.isNotBlank()) {
                    Text(state.lastError, color = Color(0xFFEF5350), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.status == "needs_action" && state.needsAction && state.pendingConflictsCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF211A12), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            "Có ${state.pendingConflictsCount} file trùng tên cần xử lý",
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        state.pendingConflicts.take(3).forEach { item ->
                            val oldSize = com.nas.naswebdav.utils.FormatUtils.formatBytes(item.destSize)
                            val newSize = com.nas.naswebdav.utils.FormatUtils.formatBytes(item.sourceSize)
                            Text(
                                "${item.destName.ifBlank { item.rel }} • cũ $oldSize / mới $newSize",
                                color = Color(0xFFE8E8E8),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { deviceVM.resolveUsbImportConflicts("skip") },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Bỏ qua", fontSize = 11.sp) }
                            Button(
                                onClick = { deviceVM.resolveUsbImportConflicts("rename") },
                                modifier = Modifier.weight(1f).height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF42A5F5)),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Đổi tên", fontSize = 11.sp) }
                            Button(
                                onClick = { deviceVM.resolveUsbImportConflicts("overwrite") },
                                modifier = Modifier.weight(1f).height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Ghi đè", fontSize = 11.sp) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            val settingsArrowRotation by animateFloatAsState(
                targetValue = if (settingsExpanded) 180f else 0f,
                animationSpec = tween(durationMillis = 220),
                label = "usbImportSettingsArrow"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15151D), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF242436), RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsExpanded = !settingsExpanded }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, null, tint = Color(0xFF26C6DA), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cài đặt", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        null,
                        tint = Color(0xFF8892B0),
                        modifier = Modifier.size(22.dp).rotate(settingsArrowRotation)
                    )
                }
                AnimatedVisibility(
                    visible = settingsExpanded,
                    enter = expandVertically(animationSpec = tween(240)) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(140))
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tự động phát hiện", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tự mount ổ USB", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(checked = autoMount, onCheckedChange = { autoMount = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Mount read-only", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(checked = mountReadonly, onCheckedChange = { mountReadonly = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Resume sau restart", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Copy tiếp vào cùng thư mục nếu NAS/API bị restart.", color = Color(0xFF8892B0), fontSize = 10.sp)
                            }
                            Switch(checked = resumeEnabled, onCheckedChange = { resumeEnabled = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Checksum SHA-256", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Chậm hơn nhưng ghi manifest để kiểm chứng file.", color = Color(0xFF8892B0), fontSize = 10.sp)
                            }
                            Switch(checked = verifyChecksum, onCheckedChange = { verifyChecksum = it })
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = destFolder,
                            onValueChange = { if (it.length <= 48) destFolder = it },
                            label = { Text("Thư mục đích") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF26A69A),
                                unfocusedBorderColor = Color(0xFF333344),
                                focusedLabelColor = Color(0xFF26A69A),
                                unfocusedLabelColor = Color(0xFF8892B0)
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = copyMode == "new_only",
                                onClick = { copyMode = "new_only" },
                                label = { Text("Chỉ file mới", fontSize = 12.sp) },
                                leadingIcon = if (copyMode == "new_only") {{ Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }} else null
                            )
                            FilterChip(
                                selected = copyMode == "overwrite",
                                onClick = { copyMode = "overwrite" },
                                label = { Text("Ghi đè", fontSize = 12.sp) },
                                leadingIcon = if (copyMode == "overwrite") {{ Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }} else null
                            )
                        }
                    }
                }
            }

            if (deviceVM.usbImportMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(deviceVM.usbImportMessage, color = Color(0xFF66BB6A), fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        deviceVM.saveUsbImportSettings(
                            UsbImportSettings(
                                enabled = enabled,
                                destFolder = destFolder,
                                copyMode = copyMode,
                                autoMount = autoMount,
                                mountReadonly = mountReadonly,
                                pollSeconds = settings.pollSeconds,
                                resumeEnabled = resumeEnabled,
                                verifyChecksum = verifyChecksum,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26A69A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lưu", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (isRunning) {
                            enabled = false
                            deviceVM.saveUsbImportSettings(
                                UsbImportSettings(
                                    enabled = false,
                                    destFolder = destFolder,
                                    copyMode = copyMode,
                                    autoMount = autoMount,
                                    mountReadonly = mountReadonly,
                                    pollSeconds = settings.pollSeconds,
                                    resumeEnabled = resumeEnabled,
                                    verifyChecksum = verifyChecksum,
                                )
                            )
                            deviceVM.cancelUsbImport()
                        } else {
                            deviceVM.startUsbImportNow()
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFEF5350) else Color(0xFF42A5F5)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(if (isRunning) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRunning) "Tắt USB Import" else "Copy ngay", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasConfigBackupDialog(
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sysMonitorVM = LocalSystemMonitorVM.current
    var pendingDeleteFilename by remember { mutableStateOf<String?>(null) }
    var pendingRestoreFilename by remember { mutableStateOf<String?>(null) }
    var isPreparingShare by remember { mutableStateOf(false) }

    // Confirm dialogs
    if (pendingDeleteFilename != null) {
        val target = pendingDeleteFilename!!
        AppStatusDialog(
            type = DialogType.WARNING,
            message = "Sẽ xoá vĩnh viễn:\n$target",
            onConfirm = {
                sysMonitorVM.deleteNasConfigBackup(target)
                pendingDeleteFilename = null
            },
            onDismiss = { pendingDeleteFilename = null }
        )
    }
    if (pendingRestoreFilename != null) {
        val target = pendingRestoreFilename!!
        AppStatusDialog(
            type = DialogType.WARNING,
            message = "Sẽ ghi đè các file cấu hình hiện tại của NAS bằng nội dung trong:\n\n$target\n\nCác file gốc được giữ lại với đuôi .pre-restore. Sau khi xong, service nas_api/nginx sẽ tự restart.\n\nTiếp tục?",
            onConfirm = {
                sysMonitorVM.restoreNasConfigBackup(target)
                pendingRestoreFilename = null
            },
            onDismiss = { pendingRestoreFilename = null }
        )
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                .heightIn(max = 720.dp).verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.SettingsBackupRestore, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sao lưu cấu hình NAS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(
                "Backup toàn bộ cấu hình NAS (nas_api server, watcher TikTok, fan, cookies, nginx, OMV WebDAV, ...) " +
                    "thành 1 file .tar.gz lưu trên eMMC. Có thể tải về điện thoại hoặc đẩy lên OneDrive để dự phòng.",
                color = Color(0xFF8892B0), fontSize = 11.sp, lineHeight = 14.sp
            )
            Spacer(Modifier.height(6.dp))

            // Create button
            Button(
                onClick = { sysMonitorVM.createNasConfigBackup() },
                enabled = !sysMonitorVM.isCreatingNasConfigBackup,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (sysMonitorVM.isCreatingNasConfigBackup) "ĐANG TẠO..." else "TẠO BACKUP MỚI",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
            }

            // Status message
            if (sysMonitorVM.nasConfigBackupMessage.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val msgColor = when {
                    sysMonitorVM.nasConfigBackupMessage.startsWith("Lỗi") -> Color(0xFFEF5350)
                    sysMonitorVM.nasConfigBackupMessage.startsWith("Đã") -> Color(0xFF66BB6A)
                    else -> Color(0xFF8892B0)
                }
                Text(sysMonitorVM.nasConfigBackupMessage, color = msgColor, fontSize = 12.sp)
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF2A2A3E))
            Spacer(Modifier.height(6.dp))

            // List header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "BACKUP HIỆN CÓ (${sysMonitorVM.nasConfigBackups.size})",
                    color = Color(0xFF8892B0), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { sysMonitorVM.fetchNasConfigBackups() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, "Làm mới", tint = Color(0xFF8892B0), modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(4.dp))

            if (sysMonitorVM.nasConfigBackups.isEmpty()) {
                Text(
                    "Chưa có bản backup nào. Tạo bản đầu tiên bằng nút phía trên.",
                    color = Color(0xFF8892B0).copy(alpha = 0.7f), fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sysMonitorVM.nasConfigBackups.forEach { backup ->
                        key(backup.filename) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Color(0xFF15151D), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(backup.filename, color = Color(0xFFE8E8E8), fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${backup.createdAt}  •  ${backup.sizeHuman}",
                                    color = Color(0xFF8892B0), fontSize = 10.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // Share to OneDrive (qua Android share intent)
                                    TextButton(
                                        onClick = {
                                            if (isPreparingShare) return@TextButton
                                            isPreparingShare = true
                                            scope.launch {
                                                val f = sysMonitorVM.downloadNasConfigBackup(context, backup.filename)
                                                isPreparingShare = false
                                                if (f != null) {
                                                    try {
                                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                                            context,
                                                            context.applicationContext.packageName + ".fileprovider",
                                                            f
                                                        )
                                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                            type = "application/gzip"
                                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                            putExtra(android.content.Intent.EXTRA_SUBJECT, backup.filename)
                                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        val chooser = android.content.Intent.createChooser(send, "Chia sẻ tới OneDrive / Drive / Email ...")
                                                        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(chooser)
                                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                                        android.widget.Toast.makeText(context, "Lỗi share: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                                    }
                                                } else {
                                                    android.widget.Toast.makeText(context, "Không tải được file backup", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.CloudUpload, null, tint = Color(0xFF42A5F5), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("OneDrive", color = Color(0xFF42A5F5), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    TextButton(
                                        onClick = { pendingRestoreFilename = backup.filename },
                                        enabled = !sysMonitorVM.isRestoringNasConfigBackup,
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, null, tint = Color(0xFFFFA726), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Khôi phục", color = Color(0xFFFFA726), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    TextButton(
                                        onClick = { pendingDeleteFilename = backup.filename },
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Xoá", color = Color(0xFFEF5350), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isPreparingShare) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF42A5F5), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Đang tải file từ NAS để share...", color = Color(0xFF8892B0), fontSize = 11.sp)
                }
            }
            if (sysMonitorVM.isRestoringNasConfigBackup) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFFFFA726), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Đang khôi phục + restart services...", color = Color(0xFFFFA726), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
