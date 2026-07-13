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

// ============ File properties + NAS insights dialogs (tách từ StorageDialogs.kt) ============

// ====================================================================
// DIALOG THUOC TINH FILE — Tuong tu cua so Properties cua Windows
// Hien khi user long-press 1 file/folder trong BrowserScreen va chon "Thuoc tinh"
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePropertiesDialog(
    file: com.nas.naswebdav.NasFile,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Tinh toan cac field hien thi
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val mime = remember(file.name, file.isDirectory) {
        if (file.isDirectory) "Thư mục" else {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: file.contentType ?: "application/octet-stream"
        }
    }
    val sizeFormatted = remember(file.contentLength) {
        if (file.isDirectory) "—" else com.nas.naswebdav.utils.FormatUtils.formatBytes(file.contentLength)
    }
    val sizeRaw = if (file.isDirectory) "" else " (${"%,d".format(file.contentLength)} bytes)"
    val modifiedStr = remember(file.lastModified) {
        if (file.lastModified <= 0L) "—" else {
            try {
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(file.lastModified))
            } catch (_: Exception) { "—" }
        }
    }

    // Hash MD5/aHash tu DB fingerprint (neu da scan duplicate truoc do).
    // Dung LaunchedEffect tra cuu o background, KHONG block UI.
    var fingerprintHash by remember(file.path) { mutableStateOf<String?>(null) }
    var fingerprintLoading by remember(file.path) { mutableStateOf(true) }
    LaunchedEffect(file.path) {
        if (file.isDirectory) {
            fingerprintLoading = false
            return@LaunchedEffect
        }
        try {
            val db = com.nas.naswebdav.NasApplication.instance.database
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // FingerprintDao chi co findByExactHash(hash) — query full table de tim filePath khong toi uu.
                // Thay vao do dung FileDao.partialHash / fullHash (CachedFile co san).
                db.fileDao().getFileByPath(file.path)
            }
            fingerprintHash = result?.fullHash ?: result?.partialHash ?: result?.imageFingerprint
        } catch (_: Exception) {
            fingerprintHash = null
        } finally {
            fingerprintLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101012),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = if (file.isDirectory) Color(0xFFFFB74D) else Color(0xFF80CBC4),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thuộc tính",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color(0xFFE8E8E8)
                )
            }
            Spacer(Modifier.height(6.dp))

            // Cac dong field — label trai, value phai, value selectable de copy
            PropertyRow("Tên", file.name, selectable = true)
            PropertyRow("Đường dẫn", file.path, selectable = true, monospace = true)
            PropertyRow("Loại", mime)
            if (!file.isDirectory) {
                PropertyRow("Phần mở rộng", if (ext.isEmpty()) "—" else ".$ext")
                PropertyRow("Kích thước", "$sizeFormatted$sizeRaw")
            }
            PropertyRow("Sửa lần cuối", modifiedStr)
            val hashDisplay = when {
                file.isDirectory -> "—"
                fingerprintLoading -> "Đang tra cứu..."
                fingerprintHash.isNullOrEmpty() -> "— (chưa quét fingerprint)"
                else -> fingerprintHash!!
            }
            PropertyRow("Hash", hashDisplay, selectable = true, monospace = true)

            Spacer(Modifier.height(8.dp))

            // Nut dong
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Button(
                onClick = onDismiss,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))
                        ),
                        shape = RoundedCornerShape(23.dp)
                    )
            ) {
                Text("Đóng", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

internal fun dialogInsightRate(bytesPerSec: Long): String {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasInsightsDialog(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit,
    onTaskClick: (String) -> Unit = {}
) {
    // nasInsights, isFetchingNasInsights owned by SystemMonitorVM (Phase 7a).
    LaunchedEffect(Unit) { viewModel.fetchNasInsights(minIntervalMs = 5_000L) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        val insight = viewModel.nasInsights
        Column(
            // Bỏ fillMaxHeight(0.9f): co theo nội dung, tránh thừa khoảng trống đáy khi
            // nội dung ngắn; verticalScroll vẫn cuộn khi dài (sheet tự giới hạn ~màn hình).
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoGraph, null, tint = Color(0xFF00D2FF), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tổng quan hệ thống NAS", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.fetchNasInsights(minIntervalMs = 0L) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, null, tint = Color(0xFF8892B0), modifier = Modifier.size(18.dp))
                }
            }

            InsightSection("Sức khoẻ Toshiba HDD", Icons.Default.HealthAndSafety, Color(0xFF66BB6A)) {
                InsightRow("Điểm hiện tại", "${insight.hddScore}/100")
                InsightRow("Nhiệt độ", "${insight.hddTempC}°C")
                InsightRow("Thấp nhất 7 ngày", "${insight.hddMinScore}/100")
                InsightRow("Biến động", if (insight.hddScoreDelta >= 0) "+${insight.hddScoreDelta}" else "${insight.hddScoreDelta}")
                if (insight.hddStatusText.isNotBlank()) {
                    Text(insight.hddStatusText, color = Color(0xFF8892B0), fontSize = 12.sp)
                }
            }

            InsightSection("Bộ điều phối tải nền", Icons.Default.Tune, Color(0xFFFFA726)) {
                val displayMode = when(insight.workloadMode.lowercase()) {
                    "normal" -> "BÌNH THƯỜNG"
                    "balanced" -> "CÂN BẰNG TẢI"
                    "protect" -> "BẢO VỆ HỆ THỐNG"
                    else -> insight.workloadMode.uppercase()
                }
                InsightRow("Chế độ", displayMode)
                InsightRow("Áp lực tải", "${insight.workloadPressure}")
                Text(insight.workloadRecommendation.ifBlank { "Chưa có khuyến nghị." }, color = Color(0xFFE8E8E8), fontSize = 12.sp)
                if (insight.workloadReasons.isNotEmpty()) {
                    Text(insight.workloadReasons.joinToString(" • "), color = Color(0xFF8892B0), fontSize = 11.sp)
                }
            }

            InsightSection("Bảo vệ eMMC", Icons.Default.Memory, Color(0xFF42A5F5)) {
                InsightRow("Root eMMC", "${insight.emmcRootPercent}%")
                InsightRow("Log/zram", "${insight.emmcLogPercent}%")
                val recs = insight.emmcRecommendations.ifEmpty { listOf("eMMC đang an toàn.") }
                recs.take(3).forEach { Text(it, color = Color(0xFF8892B0), fontSize = 12.sp) }
            }

            InsightSection("Luồng dữ liệu thực tế", Icons.Default.SyncAlt, Color(0xFFB388FF)) {
                InsightRow("Ghi HDD", dialogInsightRate(insight.diskWriteBps))
                InsightRow("Đọc HDD", dialogInsightRate(insight.diskReadBps))
                InsightRow("LAN nhận", dialogInsightRate(insight.netRxBps))
                InsightRow("LAN gửi", dialogInsightRate(insight.netTxBps))
                if (insight.flowTasks.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    insight.flowTasks.take(4).forEach { task ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF15151D), RoundedCornerShape(7.dp))
                                .clickable { onTaskClick(task.label) }
                                .padding(8.dp)
                        ) {
                            Text("${task.label} • ${task.progress}%", color = Color(0xFF00D2FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(task.file.ifBlank { "Đang xử lý" }, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(task.dest, color = Color(0xFF8892B0), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            InsightSection("Khuyến nghị bảo trì", Icons.Default.EventAvailable, Color(0xFF00E676)) {
                insight.maintenanceActions.ifEmpty {
                    listOf(WebDavViewModel.InsightAction("low", "Ổn định", "Chưa có tác vụ bảo trì bắt buộc."))
                }.forEach { action ->
                    val color = when (action.priority) {
                        "high" -> Color(0xFFEF5350)
                        "medium" -> Color(0xFFFFA726)
                        else -> Color(0xFF66BB6A)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(8.dp).padding(top = 5.dp).background(color, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(action.detail, color = Color(0xFF8892B0), fontSize = 11.sp)
                        }
                    }
                }
            }

            Text(
                "Dữ liệu lấy từ /api/system/insights: SMART trend, tải nền, USB import, eMMC guard, luồng I/O và lịch bảo trì.",
                color = Color(0xFF8892B0).copy(alpha = 0.75f),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
internal fun InsightSection(title: String, icon: ImageVector, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF171922), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(7.dp))
        content()
    }
}

@Composable
internal fun InsightRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF8892B0), fontSize = 12.sp)
        Text(value, color = Color(0xFFE8E8E8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun PropertyRow(
    label: String,
    value: String,
    selectable: Boolean = false,
    monospace: Boolean = false
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = Color(0xFF8892B0),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(1.dp))
        val style = if (monospace) {
            androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 13.sp,
                color = Color(0xFFE8E8E8)
            )
        } else {
            androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = Color(0xFFE8E8E8),
                fontWeight = FontWeight.SemiBold
            )
        }
        if (selectable) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(value, style = style, softWrap = true)
            }
        } else {
            Text(value, style = style, softWrap = true)
        }
    }
}
