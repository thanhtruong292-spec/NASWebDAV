import sys

kt_file = r'd:\\Android\\NASWebDAV\\app\\src\\main\\java\\com\\nas\\naswebdav\\ui\\screens\\MainMenuScreen.kt'

new_code = """fun SystemStatusCards(viewModel: WebDavViewModel, mContext: android.content.Context) {
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
    val autoBackupIsActive = viewModel.isAutoBackupRunning

    // --- 4. LIVESTREAM STATES ---
    val activeStreams = viewModel.activeLivestreams

    val activeTabs = mutableListOf<Pair<Int, String>>()
    if (autoBackupIsActive) activeTabs.add(0 to "Đồng bộ")
    if (thumbIsActive) activeTabs.add(1 to "Thumbnail")
    if (dupIsActive) activeTabs.add(2 to "Trùng lặp")
    if (activeStreams.isNotEmpty()) activeTabs.add(3 to "Livestream")

    // Nếu không có tác vụ nào đang chạy, ẨN TOÀN BỘ KHỐI TÁC VỤ NỀN
    if (activeTabs.isEmpty()) return

    // Theo dõi tab đang được chọn dựa trên ID nguyên thủy (0, 1, 2, 3) để tái khớp nếu số lượng thay đổi
    var selectedId by remember { mutableIntStateOf(-1) }
    if (activeTabs.none { it.first == selectedId }) {
        selectedId = activeTabs.first().first
    }
    val currentIndex = activeTabs.indexOfFirst { it.first == selectedId }.coerceAtLeast(0)

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(4.dp))
        Text("TÁC VỤ NỀN", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 16.dp))
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column {
                if (activeTabs.size > 1) {
                    androidx.compose.material3.ScrollableTabRow(
                        selectedTabIndex = currentIndex,
                        containerColor = Color.Transparent,
                        contentColor = AccentCyan,
                        edgePadding = 8.dp,
                        indicator = { tabPositions ->
                            androidx.compose.material3.TabRowDefaults.Indicator(
                                Modifier.tabIndicatorOffset(tabPositions[currentIndex]),
                                color = AccentCyan
                            )
                        },
                        divider = {}
                    ) {
                        activeTabs.forEachIndexed { index, tabPair ->
                            androidx.compose.material3.Tab(
                                selected = currentIndex == index,
                                onClick = { selectedId = tabPair.first },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tabPair.second, fontSize = 11.sp, fontWeight = if (currentIndex == index) FontWeight.Bold else FontWeight.Medium)
                                        Spacer(Modifier.width(4.dp))
                                        Box(Modifier.size(6.dp).background(Color(0xFFEF5350), CircleShape))
                                    }
                                }
                            )
                        }
                    }
                } else if (activeTabs.size == 1) {
                    // Nếu chỉ có 1 tác vụ chạy, vẽ 1 tab tĩnh đẹp mắt
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(Color(0xFFEF5350), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(activeTabs[0].second, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color(0xFF2C2C2C))
                }

                Box(Modifier.padding(8.dp)) {
                    when (selectedId) {
                        0 -> AutoBackupTabContent(viewModel, mContext, autoBackupEnabled, lastSyncLog, lastBackupLog)
                        1 -> ThumbnailTabContent(viewModel, thumbPercent)
                        2 -> DuplicateTabContent(viewModel, mContext, dupStage, dupPercent, dupScanned, dupFound, dupStageDesc, dupStageNum, dupTotalStages, dupElapsed, dupEta, dupIsPaused, dupIsRunning)
                        3 -> LivestreamTabContent(viewModel)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}"""

with open(kt_file, 'r', encoding='utf-8') as f:
    lines = f.readlines()

start = -1
end = -1
brace_cnt = 0
in_func = False

for i, lineStr in enumerate(lines):
    if 'fun SystemStatusCards(' in lineStr:
        start = i
        in_func = True
        brace_cnt = 0

    if in_func:
        brace_cnt += lineStr.count('{') - lineStr.count('}')
        if brace_cnt == 0 and '{' in lineStr and '}' in lineStr:
            pass
        elif brace_cnt == 0 and '}' in lineStr:
            end = i
            break

if start != -1 and end != -1:
    lines[start:end+1] = [new_code + '\\n']
    with open(kt_file, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    print(f"SUCCESS: Replaced SystemStatusCards ({start} to {end})")
else:
    print("FAILED TO FIND")
