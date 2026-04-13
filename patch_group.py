import sys

kt_file = r'd:\\Android\\NASWebDAV\\app\\src\\main\\java\\com\\nas\\naswebdav\\ui\\screens\\MainMenuScreen.kt'
with open(kt_file, 'r', encoding='utf-8') as f:
    text = f.read()

# Locate index of SystemStatusCards
start_idx = text.find("fun SystemStatusCards(viewModel: WebDavViewModel, mContext: android.content.Context) {")
if start_idx == -1:
    print("Failed to find SystemStatusCards!")
    sys.exit(1)

# We want to replace from start_idx to the next function definition but wait...
# Since the original file had `if (autoBackupEnabled)` inline and NO inline `@Composable fun` inside!
# I can safely extract the entire content!
end_idx = text.find("private fun ipToFullUrl(ip: String, port: String): String {")
if end_idx == -1:
    print("Failed to find end boundary!")
    sys.exit(1)

# We replace the entire block with our shiny combined logic!
new_body = """fun SystemStatusCards(viewModel: WebDavViewModel, mContext: android.content.Context) {
    // 1. Thumbnail Status
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
    val dupIsRunning = dupStage != "Khởi động..." && dupStage != "Hoàn tất" && dupPercent < 1f && dupPercent > 0f
    val dupIsActive = dupIsRunning || dupIsPaused || dupStage == "Đang tổng hợp kết quả..."

    // 3. Auto Backup
    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
    val autoBackupEnabled = sharedPrefs.getBoolean("auto_backup", false)
    val autoBackupIsActive = viewModel.isAutoBackupRunning
    
    // 4. Livestream
    val activeStreams = viewModel.activeLivestreams

    val hasAnyTasks = thumbIsActive || dupIsActive || autoBackupIsActive || activeStreams.isNotEmpty()

    if (!hasAnyTasks) return

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        Text("TÁC VỤ NỀN", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 16.dp))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                // --- THÔNG LỆ THUMBNAIL ---
                androidx.compose.animation.AnimatedVisibility(visible = thumbIsActive) {
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
                                IconButton(onClick = { viewModel.toggleThumbPause() }, modifier = Modifier.size(32.dp)) { Icon(if (viewModel.thumbPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (viewModel.thumbPaused) Color(0xFF66BB6A) else Color(0xFFFFA726), modifier = Modifier.size(18.dp)) }
                            }
                            IconButton(onClick = { viewModel.fetchThumbStatus() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Refresh, "Làm mới", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { (thumbPercent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFFAB47BC), trackColor = Color(0xFF161616))
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${viewModel.thumbGenerated} / ${viewModel.thumbTotal}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Text("%.1f%%".format(thumbPercent), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAB47BC))
                        }
                    }
                }

                if (thumbIsActive && (dupIsActive || autoBackupIsActive || activeStreams.isNotEmpty())) {
                    androidx.compose.material3.Divider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 12.dp))
                }

                // --- DUPLICATE QUÉT ---
                androidx.compose.animation.AnimatedVisibility(visible = dupIsActive) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(Color(0xFF29B6F6).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF29B6F6), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Quét Trùng Lặp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(if (dupIsPaused) "⏸ Đã tạm dừng" else (if (!dupIsRunning) "Chuẩn bị..." else "🟢 Đang quét"), fontSize = 11.sp, color = if (dupIsPaused) Color(0xFFFFA726) else Color(0xFF66BB6A))
                            }
                            if (dupIsRunning || dupIsPaused) {
                                IconButton(onClick = { if (dupIsPaused) DuplicateProgressState.resume() else DuplicateProgressState.pause() }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (dupIsPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = if (dupIsPaused) Color(0xFF66BB6A) else Color(0xFFFFA726), modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { DuplicateProgressState.stop(mContext) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Stop, null, tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { dupPercent.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFF29B6F6), trackColor = Color(0xFF161616))
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("$dupStage", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Text("%.1f%%".format(dupPercent * 100), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF29B6F6))
                        }
                    }
                }

                if (dupIsActive && (autoBackupIsActive || activeStreams.isNotEmpty())) {
                    androidx.compose.material3.Divider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 12.dp))
                }

                // --- AUTO BACKUP ---
                androidx.compose.animation.AnimatedVisibility(visible = autoBackupIsActive) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(Color(0xFF66BB6A).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Sync, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Đồng Bộ NAS", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("🟢 Đang đồng bộ nền...", fontSize = 11.sp, color = Color(0xFF66BB6A))
                            }
                        }
                    }
                }

                if (autoBackupIsActive && activeStreams.isNotEmpty()) {
                    androidx.compose.material3.Divider(color = TextSecondary.copy(alpha=0.1f), modifier = Modifier.padding(vertical = 12.dp))
                }

                // --- LIVESTREAM ---
                androidx.compose.animation.AnimatedVisibility(visible = activeStreams.isNotEmpty()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(Color(0xFFFF7043).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Videocam, null, tint = Color(0xFFFF7043), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Livestream Recording", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("🔴 Đang ghi hình (${activeStreams.size} kênh)", fontSize = 11.sp, color = Color(0xFFFF7043))
                            }
                        }
                    }
                }
            }
        }
    }
}
"""

text = text[:start_idx] + new_body + "\\n" + text[end_idx:]

# Also remove the redundant TÁC VỤ NỀN text in the caller (if present)
caller_target1 = """        // --- CHÈN BIỂU ĐỒ GIÁM SÁT VÀ BÁO CÁO Ở ĐÂY ---
        Spacer(Modifier.height(8.dp))
        com.nas.naswebdav.ui.screens.MonitoringChartCard(viewModel)
        Spacer(Modifier.height(8.dp))

        SystemStatusCards(viewModel, mContext) // TÁC VỤ NỀN Text has been moved INSIDE this function"""
caller_rep1 = """        // --- CHÈN BIỂU ĐỒ GIÁM SÁT VÀ BÁO CÁO Ở ĐÂY ---
        Spacer(Modifier.height(8.dp))
        com.nas.naswebdav.ui.screens.MonitoringChartCard(viewModel)
        Spacer(Modifier.height(4.dp))

        SystemStatusCards(viewModel, mContext)"""
text = text.replace(caller_target1, caller_rep1)

with open(kt_file, 'w', encoding='utf-8') as f:
    f.write(text)

print("SUCCESS: Rewrote SystemStatusCards securely!")
