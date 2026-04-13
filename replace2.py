import sys

kt_file = r'd:\\Android\\NASWebDAV\\app\\src\\main\\java\\com\\nas\\naswebdav\\ui\\screens\\MainMenuScreen.kt'

with open(kt_file, 'r', encoding='utf-8') as f:
    lines = f.readlines()

system_cards_replacement = """@Composable
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
                        3 -> LivestreamTabContent(activeStreams)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
    lines[start_idx:end_idx+1] = [system_cards_replacement + "\n"]
    with open(kt_file, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    print(f"SUCCESS: Replaced from {start_idx} to {end_idx}")
else:
    print(f"FAILED TO FIND EXACT BLOCK bounds: start={start_idx}, end={end_idx}")
