@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
import com.nas.naswebdav.ui.dialogs.*
import com.nas.naswebdav.LocalSmartToolsVM
import com.nas.naswebdav.LocalAutoBackupVM
import com.nas.naswebdav.LocalLivestreamVM
import com.nas.naswebdav.LocalDeviceManagementVM
import com.nas.naswebdav.WebDavManager

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

// ============ SystemStatusCards (tách từ MainMenuSections.kt — không đổi logic) ============

@Composable
fun DashboardSystemStatusCards(
    mContext: android.content.Context,
    onOpenAutoBackup: () -> Unit = {},
    onOpenLivestream: () -> Unit = {},
    onOpenUsbImport: () -> Unit = {},
    onOpenDuplicateScan: () -> Unit = {}
) {
    val smartToolsVM = LocalSmartToolsVM.current
    val autoBackupVM = LocalAutoBackupVM.current
    val deviceMgmtVM = LocalDeviceManagementVM.current
    val livestreamVM = LocalLivestreamVM.current

    // facade delegation → respective Domain VMs (AutoBackupVM, SmartToolsVM,
    // LivestreamVM). Direct LocalXxxVM.current migration deferred to Group 3.
    // 1. Thumbnail Status
    LaunchedEffect(Unit) {
        smartToolsVM.fetchThumbStatus()
        livestreamVM.syncLivestreamStateWithServer()
        deviceMgmtVM.fetchUsbImportStatus(compact = true, minIntervalMs = 5_000L)
        var consecutiveFails = 0
        while (isActive) {
            val active = smartToolsVM.thumbRunning || smartToolsVM.thumbPaused
            smartToolsVM.fetchThumbStatus()
            val stillActive = smartToolsVM.thumbRunning || smartToolsVM.thumbPaused
            if (stillActive) { consecutiveFails = 0; kotlinx.coroutines.delay(2_000L) }
            else if (active && !stillActive) { consecutiveFails = 0; kotlinx.coroutines.delay(10_000L) }
            else { consecutiveFails++; kotlinx.coroutines.delay((10_000L + consecutiveFails.coerceAtMost(10) * 5_000L).coerceAtMost(120_000L)) }
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
    val sharedPrefs = androidx.compose.runtime.remember(mContext) {
        mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
    }
    val autoBackupEnabled = sharedPrefs.getBoolean("auto_backup", false)
    val autoBackupIsActive = autoBackupVM.isAutoBackupRunning

    // 4. Livestream — poll định kỳ để phát hiện job do Watcher daemon tự bắt
    val activeStreams = livestreamVM.activeLivestreams
    LaunchedEffect(Unit) {
        // Lần đầu: đồng bộ đầy đủ (bao gồm WorkManager restore)
        livestreamVM.syncLivestreamStateWithServer()
        while (isActive) {
            kotlinx.coroutines.delay(30_000L) // poll nhẹ mỗi 30 giây, không flicker
            livestreamVM.fetchLivestreamStatusOnly(mContext)
            livestreamVM.fetchTikTokLiveWatch(mContext)
        }
    }
    val usbImport = deviceMgmtVM.usbImportState
    val usbImportIsActive = usbImport.status == "copying" || usbImport.status == "cancelling"
    LaunchedEffect(Unit) {
        deviceMgmtVM.fetchUsbImportStatus(compact = true, minIntervalMs = 5_000L)
    }
    LaunchedEffect(usbImport.status) {
        while (usbImport.status == "copying" || usbImport.status == "cancelling") {
            kotlinx.coroutines.delay(2_500L)
            deviceMgmtVM.fetchUsbImportStatus(compact = true, minIntervalMs = 2_000L)
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
                                    // Nút Dừng hẳn thumbnail
                                    IconButton(onClick = { smartToolsVM.stopThumbGeneration() }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Stop, contentDescription = "Dừng thumbnail", tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
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
                            Column(
                                modifier = Modifier.fillMaxWidth().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onOpenAutoBackup() }
                            ) {
                                // Header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(30.dp).background(
                                            (if (autoBackupVM.autoBackupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A)).copy(alpha = 0.15f),
                                            CircleShape
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Sync, null,
                                            tint = if (autoBackupVM.autoBackupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("Đồng Bộ NAS", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    if (autoBackupVM.autoBackupIsPaused) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("⏸ Tạm dừng", fontSize = 10.sp, color = Color(0xFFFFA726),
                                            modifier = Modifier.background(Color(0xFFFFA726).copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    // Nút Tạm dừng / Tiếp tục
                                    IconButton(
                                        onClick = { autoBackupVM.toggleAutoBackupPause() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            if (autoBackupVM.autoBackupIsPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = if (autoBackupVM.autoBackupIsPaused) "Tiếp tục" else "Tạm dừng",
                                            tint = if (autoBackupVM.autoBackupIsPaused) Color(0xFF66BB6A) else Color(0xFFFFA726),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    // Nút Huỷ bỏ
                                    IconButton(
                                        onClick = { autoBackupVM.cancelAutoBackup(mContext) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = "Huỷ đồng bộ",
                                            tint = Color(0xFFEF5350),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(4.dp))

                                val fileProgress = when {
                                    autoBackupVM.autoBackupIsPaused -> "Đã tạm dừng"
                                    autoBackupVM.autoBackupFileBytesTotal > 0L -> {
                                        val written = com.nas.naswebdav.utils.FormatUtils.formatBytes(autoBackupVM.autoBackupFileBytesWritten)
                                        val total = com.nas.naswebdav.utils.FormatUtils.formatBytes(autoBackupVM.autoBackupFileBytesTotal)
                                        val speedStr = if (autoBackupVM.autoBackupUploadSpeedBps > 0L)
                                            " • ${com.nas.naswebdav.utils.FormatUtils.formatBytes(autoBackupVM.autoBackupUploadSpeedBps)}/s"
                                        else ""
                                        "$written / $total$speedStr"
                                    }
                                    autoBackupVM.autoBackupElapsedTime > 1000L -> "Đang đối chiếu..."
                                    else -> "Đang chuẩn bị..."
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Tệp: ${autoBackupVM.autoBackupCurrentFile}", fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(fileProgress, fontSize = 11.sp,
                                        color = if (autoBackupVM.autoBackupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A),
                                        modifier = Modifier.padding(start = 4.dp))
                                }

                                if (autoBackupVM.autoBackupSourcePath.isNotEmpty()) {
                                    val srcDir = autoBackupVM.autoBackupSourcePath.substringBeforeLast("/")
                                    val src = srcDir.substringAfterLast("0/").trim('/')
                                    Text("Từ: /$src", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { autoBackupVM.autoBackupProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = if (autoBackupVM.autoBackupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A),
                                    trackColor = Color(0xFF161616)
                                )
                                Spacer(Modifier.height(4.dp))

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Tổng tiến trình: ${autoBackupVM.autoBackupProcessedCount} / ${autoBackupVM.autoBackupTotalCount} tệp", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                                    val totalPercent = if(autoBackupVM.autoBackupTotalCount > 0) (autoBackupVM.autoBackupProcessedCount * 100f / autoBackupVM.autoBackupTotalCount) else 0f
                                    Text("%.1f%%".format(totalPercent), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        color = if (autoBackupVM.autoBackupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A))
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
                                IconButton(onClick = { deviceMgmtVM.cancelUsbImport() }, modifier = Modifier.size(32.dp)) {
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
                                            .padding(start = 8.dp, top = 6.dp)
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
                                            Text(primaryLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                            Text(displayDur, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF7043))
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
                                            
                                            val sizeAndSpeed = buildString {
                                                if (job.fileSize.isNotEmpty()) append(job.fileSize) else append("0 B")
                                                if (job.speed.isNotEmpty()) append(" • ${job.speed}")
                                            }
                                            Text(sizeAndSpeed, fontSize = 10.sp, color = AccentGreen, modifier = Modifier.padding(start = 8.dp))
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
