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

// ============ MainMenu bottom sheets: process list, smart detail, SMB, duplicate scan ============

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ProcessListBottomSheet(
    sortBy: String,
    onDismiss: () -> Unit
) {
    // Phase 7c.3: ProcessList state (systemProcesses, isLoadingProcesses) → SystemMonitorVM (Phase 7a).
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
                if (sortBy == "cpu") it.cpu > 0f else it.mem > 0f
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
                    val statusColor = when (proc.status) {
                        "running" -> Color(0xFF66BB6A)
                        "sleeping" -> Color(0xFF9E9E9E)
                        "disk-sleep" -> Color(0xFFFFA726)
                        "zombie", "dead" -> Color(0xFFEF5350)
                        "idle" -> Color(0xFF29B6F6)
                        else -> Color(0xFF9E9E9E)
                    }
                    val statusChar = when (proc.status) {
                        "running" -> "R"
                        "sleeping" -> "S"
                        "disk-sleep" -> "D"
                        "zombie" -> "Z"
                        "idle" -> "I"
                        else -> "?"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
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
                            Text("${proc.user} (${proc.pid})", color = TextSecondary, fontSize = 10.sp)
                        }
                        val displayValue = if (sortBy == "cpu") "${proc.cpu}%" else "${proc.mem}%"
                        Text(displayValue, color = AccentCyan, fontSize = 12.sp, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SmartDetailBottomSheet(
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
fun SmbBottomSheet(
    onDismiss: () -> Unit
) {
    // Phase 7c.3: SMB state (isSmbEnabled, isLoadingSmb) → DeviceMgmtVM (Phase 7a).
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
fun DuplicateScanGlobalUI(
    viewModel: com.nas.naswebdav.WebDavViewModel,
    context: android.content.Context
) {
    // Phase 7c.3: Duplicate scan state lives in WebDavViewModel (not yet in SmartToolsVM).
    // Only SmartToolsVM-backed calls use LocalSmartToolsVM.current below.
    // 2. Hộp thoại Quét Rác — TÁI THIẾT KẾ HIỂN THỊ CHÍNH XÁC
    if (viewModel.isScanningDuplicates) {
        val scanSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
            // Onclick scrim KHÔNG đóng sheet — user phải bấm nút "Thu nhỏ" / "Huỷ" explicit.
            // Cách làm: onDismissRequest -> mặc định ban đầu đóng sheet -> ta set
            // isScanningDuplicates = false nếu user thu nhỏ thủ công.
            // Với behavior "không đóng khi click ngoài", dismissRequest của sheet phải
            // skip-action: chỉ log + thu nhỏ (= behavior của nút Thu nhỏ).
            onDismissRequest = { viewModel.isScanningDuplicates = false },
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
                    val stage = viewModel.scanDuplicatesStage
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
                        text = viewModel.scanDuplicatesCurrentFolderUrl.ifEmpty { "..." },
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
                        text = viewModel.scanDuplicatesCurrentItemName.ifEmpty { "..." },
                        color = Color(0xFFEF6C00), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // ═══ PROGRESS BAR CHÍNH XÁC (2 THANH) ═══
                    val progressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = viewModel.scanDuplicatesPercent,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "totalProgress"
                    )
                    val stageProgressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = viewModel.scanDuplicatesCurrentStagePercent,
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
                            "Bước ${viewModel.scanDuplicatesStageNumber}/${viewModel.scanDuplicatesTotalStages}",
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
                    if (viewModel.scanDuplicatesStageDescription.isNotEmpty()) {
                        Text(
                            viewModel.scanDuplicatesStageDescription,
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
                        val elapsed = viewModel.scanDuplicatesElapsedTime
                        val etr = viewModel.scanDuplicatesEstimatedTimeRemaining

                        Text("Thời gian chạy: ${FormatUtils.formatElapsedTime(elapsed)}", fontSize = 11.sp, color = Color.Gray)
                        Text(if (etr >= 0) "Ước tính còn: ${FormatUtils.formatElapsedTime(etr)}" else "Đang tính toán...", fontSize = 11.sp, color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)
                    }

                    // ═══ THỐNG KÊ RÕ RÀNG ═══
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${viewModel.scanDuplicatesTotalScanned}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                            Text("Tổng tệp", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${viewModel.scanDuplicatesFound}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                            Text("Trùng lặp", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
                // ── ACTION ROW: Tạm dừng / Huỷ / Thu nhỏ ──
                Spacer(Modifier.height(12.dp))
                if (viewModel.isWorkerRunning && !viewModel.scanDuplicatesStage.contains("Hoàn tất", ignoreCase = true)) {
                    val isPaused by com.nas.naswebdav.DuplicateProgressState.isPaused.collectAsState()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.cancelDuplicateScan(context) },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373))
                        ) { Text("Huỷ", color = Color(0xFFE57373), fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = { viewModel.togglePauseDuplicateScan() },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6))
                        ) { Text(if (isPaused) "Tiếp tục" else "Tạm dừng", color = Color(0xFF64B5F6), fontWeight = FontWeight.SemiBold) }
                        Button(
                            onClick = { viewModel.isScanningDuplicates = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) { Text("Thu nhỏ", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                } else {
                    Button(
                        onClick = { viewModel.isScanningDuplicates = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("Đóng", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }



// Hộp thoại Hiển thị danh sách File Trùng Lặp
    if (viewModel.isShowingDuplicates) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { viewModel.isShowingDuplicates = false },
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
                        if (viewModel.duplicateFilesList.isNotEmpty()) {
                            Text(
                                text = "Phát hiện ${viewModel.duplicateFilesList.size} tệp trùng lặp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (viewModel.duplicateFilesList.isEmpty()) {
                        TextButton(onClick = { viewModel.isShowingDuplicates = false; viewModel.selectedDuplicates.clear() }) { 
                            Text("Hoàn tất", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold) 
                        }
                    } else {
                        if (viewModel.selectedDuplicates.isNotEmpty()) {
                            TextButton(onClick = { viewModel.deleteSelectedDuplicates() }) {
                                Text("Xóa (${viewModel.selectedDuplicates.size}) mục", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (viewModel.duplicateFilesList.isEmpty()) {
                    Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = Color.Green)
                } else {
                    // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                    // SỬA LỖI: Nhóm theo Hash/Fingerprint thay vì chỉ theo Size để đảm bảo tuyệt đối file có nội dung giống nhau mới nằm chung nhóm
                    val groupedDuplicates = remember(viewModel.duplicateFilesList) {
                        viewModel.duplicateFilesList.groupBy { it.partialHash ?: "${it.contentLength}_${it.name}" }.values.filter { it.size >= 2 }.toList()
                    }

                    // ═══ BỘ LỌC NHANH ═══
                    var selectedFilter by remember { mutableStateOf("all") } // all, image, video, doc
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
                                viewModel.selectedDuplicates.clear()
                                groupedDuplicates.forEach { group ->
                                    // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                    // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                    val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                    viewModel.selectedDuplicates.addAll(filesToDelete)
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
                                                val isSelected = viewModel.selectedDuplicates.contains(dupFile)
                                                val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                                val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(dupFile.name)
                                                val authSnapshot = viewModel.webDavManager.currentAuthState()
                                                val auth = okhttp3.Credentials.basic(authSnapshot.user, authSnapshot.pass)

                                                Box(
                                                    modifier = Modifier
                                                        .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) Color.Red.copy(alpha = 0.2f) else Color.Black)
                                                        .clickable {
                                                            if (isSelected) viewModel.selectedDuplicates.remove(dupFile)
                                                            else viewModel.selectedDuplicates.add(dupFile)
                                                        }
                                                ) {
                                                    // 1. Lớp Ảnh Nền (TỐI ƯU HÓA DB CACHE MỚI CHO TẤT CẢ MEDIA)
                                                    if (isImage || isVideo) {
                                                        Box(modifier = Modifier.fillMaxSize()) {
                                                            WebDavCachedThumbnail(url = dupFile.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize(), viewModel = viewModel)
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
                                                            if (it) viewModel.selectedDuplicates.add(dupFile)
                                                            else viewModel.selectedDuplicates.remove(dupFile)
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
