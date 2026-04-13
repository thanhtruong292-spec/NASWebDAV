import sys

kt_file = r'd:\\Android\\NASWebDAV\\app\\src\\main\\java\\com\\nas\\naswebdav\\ui\\screens\\MainMenuScreen.kt'

with open(kt_file, 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_system_cards = """@Composable
fun SystemStatusCards(viewModel: WebDavViewModel, mContext: android.content.Context) {
    var bgTabIndex by remember { mutableIntStateOf(0) }
    val bgTabs = listOf("Đồng bộ", "Thumbnail", "Trùng lặp", "Livestream")

    // --- 1. THUMBNAIL STATES ---
    LaunchedEffect(Unit) {
        viewModel.fetchThumbStatus()
        while (true) {
            val interval = if (viewModel.thumbRunning || viewModel.thumbPaused) 2_000L else 10_000L
            kotlinx.coroutines.delay(interval)
            viewModel.fetchThumbStatus()
        }
    }
    val thumbPercent = if (viewModel.thumbTotal > 0) viewModel.thumbGenerated * 100f / viewModel.thumbTotal else 0f
    val thumbIsActive = viewModel.thumbRunning || viewModel.thumbPaused || (thumbPercent > 0f && thumbPercent < 100f)

    // --- 2. DUPLICATE SCAN STATES ---
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
    val dupIsRunning = dupStage != "Khởi động..." && dupStage != "Hoàn tất" && dupPercent < 1f && dupPercent > 0f
    val dupIsActive = dupIsRunning || dupIsPaused || dupStage == "Đang tổng hợp kết quả..."

    // --- 3. AUTO BACKUP STATES ---
    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
    val autoBackupEnabled = sharedPrefs.getBoolean("auto_backup", false)
    var lastSyncLog by remember { mutableStateOf("") }
    var lastBackupLog by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val db = NasApplication.instance.database
                    val syncLogs = db.logDao().getLogsByModule("SmartSync", 1)
                    if (syncLogs.isNotEmpty()) lastSyncLog = syncLogs[0].message
                    val backupLogs = db.logDao().getLogsByModule("AutoBackup", 1)
                    if (backupLogs.isNotEmpty()) lastBackupLog = backupLogs[0].message
                } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(10_000L)
        }
    }

    // --- 4. LIVESTREAM STATES ---
    val activeStreams = viewModel.activeLivestreams

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column {
                androidx.compose.material3.ScrollableTabRow(
                    selectedTabIndex = bgTabIndex,
                    containerColor = Color.Transparent,
                    contentColor = AccentCyan,
                    edgePadding = 8.dp,
                    indicator = { tabPositions ->
                        androidx.compose.material3.TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[bgTabIndex]),
                            color = AccentCyan
                        )
                    },
                    divider = {}
                ) {
                    bgTabs.forEachIndexed { index, title ->
                        val isActive = when (index) {
                            0 -> viewModel.isAutoBackupRunning
                            1 -> thumbIsActive
                            2 -> dupIsActive
                            3 -> activeStreams.isNotEmpty()
                            else -> false
                        }
                        androidx.compose.material3.Tab(
                            selected = bgTabIndex == index,
                            onClick = { bgTabIndex = index },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, fontSize = 11.sp, fontWeight = if (bgTabIndex == index) FontWeight.Bold else FontWeight.Medium)
                                    if (isActive) {
                                        Spacer(Modifier.width(4.dp))
                                        Box(Modifier.size(6.dp).background(Color(0xFFEF5350), CircleShape))
                                    }
                                }
                            }
                        )
                    }
                }

                Box(Modifier.padding(14.dp)) {
                    when (bgTabIndex) {
                        0 -> AutoBackupTabContent(viewModel, mContext, autoBackupEnabled, lastSyncLog, lastBackupLog)
                        1 -> ThumbnailTabContent(viewModel, thumbPercent)
                        2 -> DuplicateTabContent(viewModel, mContext, dupStage, dupPercent, dupScanned, dupFound, dupStageDesc, dupStageNum, dupTotalStages, dupElapsed, dupEta, dupIsPaused, dupIsRunning)
                        3 -> LivestreamTabContent(viewModel)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AutoBackupTabContent(viewModel: WebDavViewModel, mContext: android.content.Context, autoBackupEnabled: Boolean, lastSyncLog: String, lastBackupLog: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Color(0xFF26A69A).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CloudDone, null, tint = Color(0xFF26A69A), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tự động đồng bộ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(if (autoBackupEnabled) "Trạng thái: Bật đồng bộ ngầm định kỳ" else "Trạng thái: Chờ đồng bộ thủ công", fontSize = 10.sp, color = if (autoBackupEnabled) Color(0xFF66BB6A) else TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (viewModel.isAutoBackupRunning) {
                    IconButton(onClick = { viewModel.toggleAutoBackupPause() }) {
                        Icon(if (viewModel.autoBackupIsPaused) Icons.Default.PlayArrow else Icons.Default.Pause, "Tạm dừng", tint = if (viewModel.autoBackupIsPaused) Color(0xFF66BB6A) else Color(0xFFFFA726), modifier = Modifier.size(24.dp))
                    }
                }
                IconButton(onClick = { viewModel.triggerManualBackup(mContext) }) {
                    Icon(Icons.Default.Sync, "Sync Now", tint = Color(0xFF26A69A), modifier = Modifier.size(24.dp))
                }
            }
        }
        if (viewModel.isAutoBackupRunning) {
            Spacer(Modifier.height(12.dp))
            Text(if (viewModel.autoBackupIsPaused) "Tạm dừng sao lưu..." else "Đang sao lưu: ${viewModel.autoBackupCurrentFile}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (viewModel.autoBackupIsPaused) Color(0xFFFFA726) else AccentCyan, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val totalPercent = if(viewModel.autoBackupTotalCount > 0) viewModel.autoBackupProcessedCount.toFloat() / viewModel.autoBackupTotalCount else 0f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tổng tiến trình: ${viewModel.autoBackupProcessedCount}/${viewModel.autoBackupTotalCount} tệp", fontSize = 10.sp, color = TextSecondary)
                Text("${(totalPercent * 100).toInt()}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
            }
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(progress = { totalPercent.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFF64B5F6), trackColor = DarkSurface)
            Spacer(Modifier.height(8.dp))
            Text("Tiến trình tệp cục bộ:", fontSize = 10.sp, color = TextSecondary)
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(progress = { viewModel.autoBackupProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = AccentCyan, trackColor = DarkSurface)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Đã Chạy: ${formatElapsedTimeUI(viewModel.autoBackupElapsedTime)}", fontSize = 10.sp, color = TextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("${(viewModel.autoBackupProgress * 100).toInt()}% tệp", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            }
        } else {
            if (lastBackupLog.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("📦 $lastBackupLog", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            if (lastSyncLog.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("🔄 $lastSyncLog", fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            if (!autoBackupEnabled && lastBackupLog.isBlank() && lastSyncLog.isBlank()) { Spacer(Modifier.height(8.dp)); Text("Nhấn vào đây để thiết lập sao lưu", fontSize = 11.sp, color = TextSecondary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
        }
    }
}

@Composable
private fun ThumbnailTabContent(viewModel: WebDavViewModel, thumbPercent: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Color(0xFFAB47BC).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PhotoLibrary, null, tint = Color(0xFFAB47BC), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Trình Tạo Ảnh Thu Nhỏ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(when { viewModel.thumbPaused -> "Đã Tạm dừng"; viewModel.thumbRunning -> "🟢 Đang chạy"; else -> "💤 Tạm nghỉ" }, fontSize = 11.sp, color = when { viewModel.thumbPaused -> Color(0xFFFFA726); viewModel.thumbRunning -> Color(0xFF66BB6A); else -> TextSecondary })
            }
            if (viewModel.thumbRunning || viewModel.thumbPaused) {
                IconButton(onClick = { viewModel.toggleThumbPause() }, modifier = Modifier.size(32.dp)) {
                    Icon(if (viewModel.thumbPaused) Icons.Default.PlayArrow else Icons.Default.Pause, if (viewModel.thumbPaused) "Tiếp tục" else "Tạm dừng", tint = if (viewModel.thumbPaused) Color(0xFF66BB6A) else Color(0xFFFFA726), modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { viewModel.fetchThumbStatus() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, "Làm mới", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (viewModel.thumbTotal > 0) {
            LinearProgressIndicator(progress = { (thumbPercent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFFAB47BC), trackColor = Color(0xFF2A2A2A))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${viewModel.thumbGenerated} / ${viewModel.thumbTotal}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("%.1f%%".format(thumbPercent), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAB47BC))
            }
        } else if (viewModel.thumbRunning) { Text("Đang quét đối chiếu danh sách ảnh...", fontSize = 12.sp, color = TextSecondary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
        else { Text("Chưa có tiến trình quét ảnh nào đang chạy.", fontSize = 11.sp, color = TextSecondary) }
        
        if (viewModel.thumbErrors > 0) { Text("⚠ ${viewModel.thumbErrors} lỗi", fontSize = 10.sp, color = Color(0xFFFF7043)) }
        if (viewModel.thumbLastFile.isNotBlank()) { Text(viewModel.thumbLastFile, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        if (viewModel.thumbRunning && viewModel.thumbElapsed > 0) { Text(if (viewModel.thumbEta > 0) "Đã ${viewModel.thumbElapsedFmt} / ${viewModel.thumbEtaFmt}" else "Đã ${viewModel.thumbElapsedFmt}", fontSize = 10.sp, color = TextSecondary) }
    }
}

@Composable
private fun DuplicateTabContent(viewModel: WebDavViewModel, mContext: android.content.Context, dupStage: String, dupPercent: Float, dupScanned: Int, dupFound: Int, dupStageDesc: String, dupStageNum: Int, dupTotalStages: Int, dupElapsed: Long, dupEta: Long, dupIsPaused: Boolean, dupIsRunning: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Color(0xFFEF5350).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ContentCopy, null, tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Quét trùng lặp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(if (dupIsRunning) { if (dupIsPaused) "Đã Đang tạm dừng" else "🟢 $dupStage" } else if (dupPercent >= 1f) "✅ Hoàn tất" else "Chưa chạy", fontSize = 11.sp, color = if (dupIsRunning) { if (dupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A) } else if (dupPercent >= 1f) Color(0xFF66BB6A) else TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (dupIsRunning || dupPercent >= 1f) { Text("$dupStageNum/$dupTotalStages", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF5350)) }
        }
        if (dupIsRunning || dupPercent >= 1f) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { dupPercent.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFFEF5350), trackColor = Color(0xFF2A2A2A))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Đã quét: $dupScanned | Trùng: $dupFound", fontSize = 11.sp, color = TextPrimary)
                Text("%.0f%%".format(dupPercent * 100f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF5350))
            }
            if (dupStageDesc.isNotBlank()) { Text(dupStageDesc, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            if (dupIsRunning && dupElapsed > 0L) {
                val elapsedMin = dupElapsed / 60000; val elapsedSec = (dupElapsed / 1000) % 60
                val etaText = if (dupEta > 0L) { val etaMin = dupEta / 60000; val etaSec = (dupEta / 1000) % 60; " | ETA: ${etaMin}p${etaSec}s" } else ""
                Text("Đã ${elapsedMin}p${elapsedSec}s$etaText", fontSize = 10.sp, color = TextSecondary)
            }
            if (dupIsRunning) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.cancelDuplicateScan(mContext) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("Hủy bỏ?", color = Color(0xFFE57373), fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.togglePauseDuplicateScan() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text(if (dupIsPaused) "Tiếp tục" else "Tạm dừng", color = Color(0xFF64B5F6), fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("Hãy khởi động quy trình Quét trùng lặp từ menu", fontSize = 11.sp, color = TextSecondary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        }
    }
}

@Composable
private fun LivestreamTabContent(viewModel: WebDavViewModel) {
    val activeStreams = viewModel.activeLivestreams
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Color(0xFFEE1D52).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Videocam, null, tint = Color(0xFFEE1D52), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ghi Livestream", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(if (activeStreams.isEmpty()) "Không có stream nào đang ghi" else "Đang ghi ${activeStreams.size} stream...", fontSize = 10.sp, color = if (activeStreams.isEmpty()) TextSecondary else Color(0xFFEE1D52), fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(Modifier.height(12.dp))
        if(activeStreams.isEmpty()) {
            Text("Không có luồng video nào đang được tải.", fontSize = 11.sp, color = TextSecondary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        } else {
            activeStreams.forEachIndexed { index, job ->
                val pfName = job.platform.ifEmpty { "Livestream" }.uppercase()
                val color = when(job.platform) { "tiktok" -> Color(0xFFEE1D52); "youtube" -> Color(0xFFFF0000); "facebook" -> Color(0xFF1877F2); "x" -> Color(0xFF1DA1F2); else -> Color.White }
                
                Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).border(1.dp, color.copy(alpha=0.15f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pulse = rememberInfiniteTransition(label = "pulse")
                        val alpha by pulse.animateFloat(initialValue = 1f, targetValue = 0.3f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseAlpha")
                        Box(Modifier.size(8.dp).background(Color.Red.copy(alpha = alpha), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Nền tảng:", color = TextSecondary, fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(pfName, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tên Video:", color = TextSecondary, fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(job.outputFile.ifEmpty { "Đang kết nối luồng Live..." }, color = TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Thời gian tải:", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(job.duration, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (job.speed.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Tốc độ:", color = TextSecondary, fontSize = 11.sp)
                                Spacer(Modifier.width(4.dp))
                                Text(job.speed, color = Color(0xFFFFA726), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (job.fileSize.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dung lượng:", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(job.fileSize, color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
"""

start_idx = -1
end_idx = -1
for i, line in enumerate(lines):
    if line.startswith("@Composable") and "fun SystemStatusCards(" in lines[i+1]:
        start_idx = i
        break

if start_idx != -1:
    brace_count = 0
    in_function = False
    for i in range(start_idx, len(lines)):
        lineStr = lines[i]
        brace_count += lineStr.count('{')
        brace_count -= lineStr.count('}')
        if "{" in lineStr:
            in_function = True
        
        if in_function and brace_count == 0:
            end_idx = i
            break

if start_idx != -1 and end_idx != -1:
    lines[start_idx:end_idx+1] = [new_system_cards + "\n"]
    
    # Check if we need to replace formatElapsedTimeUI in the file too since we restored it
    time_format_new = """
/**
 * Định dạng thời gian đã trôi qua (milliseconds) thành chuỗi dễ đọc.
 * Format: xx tháng, xx ngày, xx giờ, xx phút, xx giây
 * Quy tắc: Nếu tổng thời gian >= 1 phút thì ẩn phần giây để gọn gàng.
 */
fun formatElapsedTimeUI(millis: Long): String {
    if (millis <= 0) return "0 giây"
    val totalSeconds = millis / 1000
    val months  = totalSeconds / 2592000           // ~30 ngày/tháng
    val days    = (totalSeconds % 2592000) / 86400
    val hours   = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val hasMinutes = (totalSeconds >= 60)

    val parts = mutableListOf<String>()
    if (months  > 0) parts.add("${months} tháng")
    if (days    > 0) parts.add("${days} ngày")
    if (hours   > 0) parts.add("${hours} giờ")
    if (minutes > 0) parts.add("${minutes} phút")
    if (!hasMinutes) parts.add("${seconds} giây")   // ẩn giây khi >= 1 phút
    return if (parts.isEmpty()) "0 giây" else parts.joinToString(", ")
}

/**
 * Parse và định dạng lại chuỗi uptime từ server NAS.
 * Server trả về dạng "3 ngày, 6 giờ, 59 phút, 38 giây"
 * Hàm này áp dụng rule ẩn giây nếu uptime >= 1 phút, và thêm tháng nếu cần.
 */
fun formatUptimeNice(raw: String): String {
    if (raw.isBlank() || raw == "--" || raw == "--:--") return raw
    var months = 0L; var days = 0L; var hours = 0L; var minutes = 0L; var seconds = 0L
    Regex("(\\d+)\\\\s*tháng").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let  { months  = it }
    Regex("(\\d+)\\\\s*ngày").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let   { days    = it }
    Regex("(\\d+)\\\\s*gi[oờ]").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { hours   = it }
    Regex("(\\d+)\\\\s*ph[uú]t").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let{ minutes = it }
    Regex("(\\d+)\\\\s*gi[aây]+").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let{ seconds = it }
    val totalSec = months * 2592000 + days * 86400 + hours * 3600 + minutes * 60 + seconds
    val hasMinutes = totalSec >= 60
    val parts = mutableListOf<String>()
    if (months  > 0) parts.add("${months} tháng")
    if (days    > 0) parts.add("${days} ngày")
    if (hours   > 0) parts.add("${hours} giờ")
    if (minutes > 0) parts.add("${minutes} phút")
    if (!hasMinutes) parts.add("${seconds} giây")
    return if (parts.isEmpty()) raw else parts.joinToString(", ")
}
"""
    # Replace formatElapsedTimeUI
    format_start = -1
    format_end = -1
    for i, line in enumerate(lines):
        if "fun formatElapsedTimeUI(" in line:
            format_start = i
            break
    if format_start != -1:
        for i in range(format_start, len(lines)):
            if lines[i].strip() == "}":
                format_end = i
                break
    if format_start != -1 and format_end != -1:
        lines[format_start:format_end+1] = [time_format_new + "\n"]

    with open(kt_file, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    print(f"SUCCESS: Replaced SystemStatusCards and formatElapsedTimeUI")
else:
    print(f"FAILED TO FIND EXACT BLOCK bounds")
