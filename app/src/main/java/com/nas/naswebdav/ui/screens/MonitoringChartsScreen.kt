package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nas.naswebdav.DailyReportData
import com.nas.naswebdav.LocalSystemMonitorVM
import com.nas.naswebdav.MetricsSnapshot


private val _ChartDarkCard    = Color(0xFF0F0F0F)
private val _ChartDarkSurface = Color.Black
private val _ChartAccentBlue  = Color(0xFF2196F3)
private val _ChartAccentCyan  = Color(0xFF00D2FF)
private val _ChartAccentGreen = Color(0xFF00E676)
private val _ChartAccentOrange= Color(0xFFFF9100)
private val _ChartAccentRed   = Color(0xFFFF1744)
private val _ChartAccentPurple= Color(0xFFBB86FC)
private val _ChartAccentPink  = Color(0xFFFF6EC7)
private val _ChartTextPrimary = Color(0xFFE8E8E8)
private val _ChartTextSecond  = Color(0xFF8892B0)
private val _ChartPanelTitle  = Color(0xFF4DD0E1)
private val _ChartReportTitle = Color(0xFFB388FF)
private val _ChartPanelTitleSize = 11.sp
private val _ChartPanelTitleLetterSpacing = 1.5.sp

// Ke thua nguong tu GaugeCard (MainMenuScreen.kt) de bieu do duong dong bo voi GaugeCard tron.
// Mau: Do 0xFFEF5350 / Vang 0xFFFFC400 (vang am thuan, dam hon Material 400 mat) / Xanh 0xFF66BB6A
private val _StatusRed    = Color(0xFFEF5350)
private val _StatusYellow = Color(0xFFFFC400)
private val _StatusGreen  = Color(0xFF66BB6A)

private fun percentStatusColor(latest: Float): Color = when {
    latest >= 90f -> _StatusRed
    latest >= 70f -> _StatusYellow
    else -> _StatusGreen
}

private fun cpuTempStatusColor(v: Float): Color = when {
    v >= 80f -> _StatusRed
    v >= 60f -> _StatusYellow
    else -> Color(0xFFFF5252)
}

private fun hddTempStatusColor(v: Float): Color = when {
    v >= 55f -> _StatusRed
    v >= 45f -> _StatusYellow
    else -> Color(0xFF00E5FF)
}

private fun cpuPercentStatusColor(v: Float): Color = when {
    v >= 90f -> _StatusRed
    v >= 70f -> _StatusYellow
    else -> Color(0xFFFF9100)
}

private fun ramPercentStatusColor(v: Float): Color = when {
    v >= 90f -> _StatusRed
    v >= 70f -> _StatusYellow
    else -> Color(0xFF00B0FF)
}

// Linear blend ARGB cho 2 mau de doan noi 2 diem nhiet do/ phan tram khac mau
// (vd vang -> do) doi mau muot thay vi gay duong khuc cua nhin gat mat.
private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val s = t.coerceIn(0f, 1f)
    return Color(
        red   = a.red   + (b.red   - a.red)   * s,
        green = a.green + (b.green - a.green) * s,
        blue  = a.blue  + (b.blue  - a.blue)  * s,
        alpha = a.alpha + (b.alpha - a.alpha) * s
    )
}

@Composable
fun MonitoringChartCard() {
    val sysMonitorVM = LocalSystemMonitorVM.current
    val tabLabels  = listOf("🌡️ Nhiệt độ", "📊 Tài nguyên", "📶 Mạng")
    val hourLabels = listOf("1h", "6h", "24h")
    val hourValues = listOf(1, 6, 24)
    var showReport by remember { mutableStateOf(false) }
    val report = sysMonitorVM.dailyReport

    LaunchedEffect(sysMonitorVM.metricsHours) {
        sysMonitorVM.fetchMetricsHistory(sysMonitorVM.metricsHours)
    }
    LaunchedEffect(Unit) {
        sysMonitorVM.fetchDailyReport()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = _ChartDarkCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            // Mo doc quyen: chart mo dong bo voi ExclusivePanelState — khi mo
            // panel khac (OMV, Tasks) thi chart tu cup.
            val chartExpanded = com.nas.naswebdav.ui.screens.ExclusivePanelState.current.value == "chart"

            // Header — nhấn để mở/đóng (không ripple)
            Row(
                Modifier.fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { com.nas.naswebdav.ui.screens.ExclusivePanelState.toggle("chart") },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Insights, null, tint = _ChartAccentCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("GIÁM SÁT", fontSize = _ChartPanelTitleSize, color = _ChartPanelTitle, fontWeight = FontWeight.Black, letterSpacing = _ChartPanelTitleLetterSpacing)
                    val ageSec = if (sysMonitorVM.lastMetricsRefreshAt > 0L) ((System.currentTimeMillis() - sysMonitorVM.lastMetricsRefreshAt).coerceAtLeast(0L) / 1000L).toInt() else -1
                    val refreshLabel = when {
                        ageSec < 0 -> "Đang chờ dữ liệu"
                        ageSec < 60 -> "Mới ${ageSec}s"
                        else -> "Mới ${ageSec / 60}p"
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(refreshLabel, fontSize = 9.sp, color = if (ageSec in 0..89) _ChartTextSecond else _ChartAccentOrange)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    hourLabels.forEachIndexed { i, label ->
                        val selected = hourValues[i] == sysMonitorVM.metricsHours
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) _ChartAccentCyan.copy(alpha = 0.16f) else Color.Transparent,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // Force open chart panel (overrides other panels)
                                com.nas.naswebdav.ui.screens.ExclusivePanelState.current.value = "chart"
                                sysMonitorVM.fetchMetricsHistory(hourValues[i])
                            }
                        ) {
                            Text(label, fontSize = 10.sp,
                                color = if (selected) _ChartAccentCyan else _ChartTextSecond,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (chartExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = _ChartTextSecond,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Nội dung mở rộng
            androidx.compose.animation.AnimatedVisibility(visible = chartExpanded) {
                Column {
                    Spacer(Modifier.height(8.dp))

            // Tab chọn loại biểu đồ
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabLabels.forEachIndexed { i, label ->
                    val sel = i == sysMonitorVM.metricsChartTab
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (sel) Color(0xFF1B5E20).copy(alpha = 0.5f) else _ChartDarkSurface,
                        modifier = Modifier.weight(1f).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { sysMonitorVM.metricsChartTab = i }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 5.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (sel) {
                                Box(Modifier.size(6.dp).background(Color(0xFFFF1744), androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(label, fontSize = 10.sp,
                                color = if (sel) _ChartAccentGreen else _ChartTextSecond,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Vùng biểu đồ — hiển thị theo trạng thái
            val history = sysMonitorVM.metricsHistory
            val error   = sysMonitorVM.metricsError
            when {
                // Có lỗi: hiện thông báo + nút Refresh
                error != null -> {
                    Box(Modifier.fillMaxWidth().height(100.dp)
                        .background(_ChartDarkSurface, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ErrorOutline, null, tint = _ChartAccentRed, modifier = Modifier.size(22.dp))
                            Text(error, fontSize = 10.sp, color = _ChartTextSecond, textAlign = TextAlign.Center)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = _ChartAccentCyan.copy(alpha = 0.15f),
                                modifier = Modifier.clickable { sysMonitorVM.fetchMetricsHistory(sysMonitorVM.metricsHours) }
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Refresh, null, tint = _ChartAccentCyan, modifier = Modifier.size(14.dp))
                                    Text("Thử lại", fontSize = 11.sp, color = _ChartAccentCyan, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                // Chưa có dữ liệu + đang load: spinner
                history.isEmpty() && sysMonitorVM.isLoadingMetrics -> {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = _ChartAccentCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(6.dp))
                            Text("Đang tải dữ liệu...", fontSize = 11.sp, color = _ChartTextSecond)
                        }
                    }
                }
                // Chưa có dữ liệu + không load: chờ kết nối
                history.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().height(100.dp)
                        .background(_ChartDarkSurface, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Chưa có dữ liệu giám sát", fontSize = 11.sp, color = _ChartTextSecond)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = _ChartAccentCyan.copy(alpha = 0.15f),
                                modifier = Modifier.clickable { sysMonitorVM.fetchMetricsHistory(sysMonitorVM.metricsHours) }
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Refresh, null, tint = _ChartAccentCyan, modifier = Modifier.size(14.dp))
                                    Text("Làm mới", fontSize = 11.sp, color = _ChartAccentCyan, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                // Có dữ liệu: vẽ biểu đồ
                else -> NasMetricsLineChart(history = history, tabIndex = sysMonitorVM.metricsChartTab)
            }


            Spacer(Modifier.height(10.dp))

            // Nút xem báo cáo hàng ngày
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = _ChartDarkSurface,
                modifier = Modifier.fillMaxWidth().clickable {
                    showReport = !showReport
                    if (showReport && report == null) sysMonitorVM.fetchDailyReport()
                }
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Assessment, null, tint = _ChartAccentPurple, modifier = Modifier.size(18.dp))
                        Column {
                            Text("BÁO CÁO HÔM QUA", fontSize = _ChartPanelTitleSize, fontWeight = FontWeight.Black, color = _ChartReportTitle, letterSpacing = _ChartPanelTitleLetterSpacing)
                            if (report != null) {
                                val icon = when {
                                    report.healthScore >= 80 -> "🟢"
                                    report.healthScore >= 60 -> "🟡"
                                    else -> "🔴"
                                }
                                Text("$icon Sức khoẻ: ${report.healthScore}%  |  ${report.date}",
                                    fontSize = 10.sp, color = _ChartTextSecond)
                            } else {
                                Text(if (sysMonitorVM.isDailyReportLoading) "Đang tải..." else "Nhấn để xem báo cáo ngày hôm qua",
                                    fontSize = 10.sp, color = _ChartTextSecond)
                            }
                        }
                    }
                    Icon(if (showReport) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = _ChartTextSecond, modifier = Modifier.size(18.dp))
                }
            }

            // Panel báo cáo mở rộng
            androidx.compose.animation.AnimatedVisibility(visible = showReport && report != null) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    report?.let { NasDailyReportPanel(it) }
                }
            }

                } // end Column inside AnimatedVisibility
            } // end AnimatedVisibility
        }
    }
}

@Composable
fun NasMetricsLineChart(history: List<MetricsSnapshot>, tabIndex: Int) {
    data class Series(
        val values: List<Float>,
        val color: Color,
        val label: String,
        val unit: String,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val isDashed: Boolean = false,
        val colorOf: ((Float) -> Color)? = null
    )

    val cpuTempVals = history.map { it.cpuTemp }
    val hddTempVals = history.map { it.hddTemp }
    val cpuPctVals  = history.map { it.cpuPercent }
    val ramPctVals  = history.map { it.ramPercent }

    // Đánh giá màu sắc theo TRẠNG THÁI (Bình thường -> Vàng Cảnh báo -> Đỏ Nguy hiểm) & kiểu nét kẻ riêng biệt:
    // - CPU: Nét liền (Solid Line), màu Đỏ Coral (#FF5252) / Cam (#FF9100) khi bình thường
    // - HDD / RAM: Nét đứt (Dashed Line), màu Cyan (#00E5FF) / Xanh Dương (#00B0FF) khi bình thường
    val series: List<Series> = when (tabIndex) {
        0 -> listOf(
            Series(cpuTempVals, Color(0xFFFF5252), "CPU", "°C", Icons.Default.Memory, isDashed = false, colorOf = ::cpuTempStatusColor),
            Series(hddTempVals, Color(0xFF00E5FF), "HDD", "°C", Icons.Default.Storage, isDashed = true, colorOf = ::hddTempStatusColor)
        )
        1 -> listOf(
            Series(cpuPctVals, Color(0xFFFF9100), "CPU", "%", Icons.Default.Speed, isDashed = false, colorOf = ::cpuPercentStatusColor),
            Series(ramPctVals, Color(0xFF00B0FF), "RAM", "%", Icons.Default.DeveloperBoard, isDashed = true, colorOf = ::ramPercentStatusColor)
        )
        else -> listOf(
            Series(history.map { (it.netRxKbps / 1024f).coerceAtLeast(0f) }, Color(0xFF00E676), "Tải về", " MB/s", Icons.Default.ArrowDownward, isDashed = false),
            Series(history.map { (it.netTxKbps / 1024f).coerceAtLeast(0f) }, Color(0xFFFF4081), "Tải lên", " MB/s", Icons.Default.ArrowUpward, isDashed = true)
        )
    }

    val allVals = series.flatMap { it.values }
    
    val minVal: Float = when (tabIndex) {
        0 -> 20f
        1 -> 0f
        else -> 0f
    }
    val maxVal: Float = when (tabIndex) {
        0 -> 80f
        1 -> 100f
        else -> (allVals.maxOrNull() ?: 1f).coerceAtLeast(0.2f)
    }
    val range = (maxVal - minVal).coerceAtLeast(0.1f)

    var touchedIndex by remember { mutableIntStateOf(-1) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val textPx = 10f * density
    val topPad = 10f * density
    val bottomPad = 22f * density
    val leftPad = 45f * density
    val rightPad = 12f * density

    Column {
        // Chú thích màu & kiểu nét kẻ — Legend gộp chung thông số số liệu
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val displayIdx = if (touchedIndex in history.indices) touchedIndex else history.size - 1
                series.forEach { s ->
                    val currentVal = if (displayIdx in s.values.indices) s.values[displayIdx] else 0f
                    val fmt = if (tabIndex == 2) "%.2f" else "%.1f"
                    val valStr = "${fmt.format(currentVal)}${s.unit}"
                    val styleLabel = if (s.isDashed) "- - - " else "━━ "
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(styleLabel, fontSize = 11.sp, color = s.color, fontWeight = FontWeight.Black)
                        Icon(s.icon, null, tint = s.color, modifier = Modifier.size(13.dp))
                        Text("${s.label}: $valStr", fontSize = 11.sp, color = s.color, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        
        // Canvas biểu đồ + touch detection
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .pointerInput(history.size, tabIndex) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val pos = event.changes.firstOrNull()?.position
                                if (pos != null && history.size >= 2) {
                                    val usableW = size.width - leftPad - rightPad
                                    val step = usableW / (history.size - 1).toFloat()
                                    val idx = ((pos.x - leftPad) / step).toInt().coerceIn(0, history.size - 1)
                                    
                                    when (event.type) {
                                        androidx.compose.ui.input.pointer.PointerEventType.Press,
                                        androidx.compose.ui.input.pointer.PointerEventType.Move -> {
                                            touchedIndex = idx
                                            event.changes.forEach { it.consume() }
                                        }
                                        androidx.compose.ui.input.pointer.PointerEventType.Release -> {
                                            touchedIndex = -1
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val usableW = w - leftPad - rightPad
                val usableH = h - topPad - bottomPad

                // Nhãn trục Y
                val yPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 160, 175, 200)
                    textSize = textPx
                    textAlign = android.graphics.Paint.Align.LEFT
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                // Paint nhãn thời gian cho trục X tại mốc dọc
                val xTimePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(180, 136, 146, 176) // #8892B0 70% alpha
                    textSize = textPx * 0.9f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                // Grid 4 đường kẻ ngang siêu mảnh (0.6dp)
                val gridCount = 4
                for (i in 0 until gridCount) {
                    val fraction = i / (gridCount - 1).toFloat()
                    val y = topPad + fraction * usableH
                    val gridVal = maxVal - fraction * (maxVal - minVal)

                    drawLine(
                        color = Color(0x22FFFFFF),
                        start = Offset(leftPad, y),
                        end = Offset(w, y),
                        strokeWidth = 0.6f * density
                    )

                    val fmt = when (tabIndex) {
                        0 -> "%.0f°C".format(gridVal)
                        1 -> "%.0f%%".format(gridVal)
                        else -> if (maxVal < 1f) "%.2f".format(gridVal) else "%.1f".format(gridVal)
                    }
                    drawContext.canvas.nativeCanvas.drawText(fmt, 0f, y + textPx / 3f, yPaint)
                }

                // Grid các đường kẻ dọc mờ (0.5dp) + Nhãn thời gian tự động tại mốc
                val sampleSize = history.size
                if (sampleSize >= 2) {
                    val vGridCount = 5.coerceAtMost(sampleSize)
                    val vStep = (sampleSize - 1) / (vGridCount - 1).toFloat()
                    
                    for (gi in 0 until vGridCount) {
                        val pIdx = (gi * vStep).toInt().coerceIn(0, sampleSize - 1)
                        val vx = leftPad + (pIdx.toFloat() / (sampleSize - 1)) * usableW
                        
                        drawLine(
                            color = Color(0x1CFFFFFF),
                            start = Offset(vx, topPad),
                            end = Offset(vx, topPad + usableH),
                            strokeWidth = 0.5f * density,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * density, 4f * density))
                        )

                        // Nhãn thời gian động (HH:mm) dưới chân đường kẻ dọc
                        val rawTs = history[pIdx].timestamp
                        val timeLabel = when {
                            rawTs.length >= 19 -> rawTs.substring(11, 16) // Lấy HH:mm từ "YYYY-MM-DD HH:mm:ss"
                            rawTs.length >= 5 -> rawTs.substring(0, 5)
                            else -> rawTs
                        }
                        if (timeLabel.isNotBlank()) {
                            drawContext.canvas.nativeCanvas.drawText(timeLabel, vx, h - 3f * density, xTimePaint)
                        }
                    }
                }

                // Vẽ các series đường kẻ mảnh bằng 1 Path liên tục với kiểu Solid/Dashed phân biệt
                series.forEach { s ->
                    val pts = s.values
                    if (pts.size < 2) return@forEach
                    fun xOf(i: Int) = leftPad + (i.toFloat() / (pts.size - 1)) * usableW
                    fun yOf(v: Float) = (topPad + usableH) - ((v.coerceIn(minVal, maxVal) - minVal) / range) * usableH

                    // Fill vùng mờ nhẹ (0.04f alpha)
                    val fillPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(xOf(0), topPad + usableH)
                        pts.forEachIndexed { i, v -> lineTo(xOf(i), yOf(v)) }
                        lineTo(xOf(pts.lastIndex), topPad + usableH)
                        close()
                    }
                    drawPath(fillPath, s.color.copy(alpha = 0.04f))

                    val colorFn: (Float) -> Color = { v -> s.colorOf?.invoke(v) ?: s.color }

                    // Vẽ từng đoạn [i, i+1] chuyển màu theo trạng thái nhiệt độ/tài nguyên (Xanh -> Vàng -> Đỏ)
                    var accumulatedLength = 0f
                    for (i in 0 until pts.size - 1) {
                        val v1 = pts[i]
                        val v2 = pts[i + 1]
                        val c1 = colorFn(v1)
                        val c2 = colorFn(v2)
                        val cMid = lerpColor(c1, c2, 0.5f)
                        val x1 = xOf(i)
                        val x2 = xOf(i + 1)
                        val y1 = yOf(v1)
                        val y2 = yOf(v2)

                        val len = kotlin.math.hypot(x2 - x1, y2 - y1)
                        val dashEffect = if (s.isDashed) {
                            androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(5f * density, 2.5f * density),
                                phase = accumulatedLength
                            )
                        } else null
                        accumulatedLength += len

                        val segPath = androidx.compose.ui.graphics.Path().apply {
                            moveTo(x1, y1)
                            lineTo(x2, y2)
                        }

                        drawPath(
                            path = segPath,
                            color = cMid,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 0.5f * density,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                pathEffect = dashEffect
                            )
                        )
                    }

                    // Điểm mốc cuối cùng nếu ko chạm
                    if (touchedIndex == -1) {
                        val lastY = yOf(pts.last())
                        val lastColor = colorFn(pts.last())
                        drawCircle(lastColor, 3.5f * density, Offset(xOf(pts.lastIndex), lastY))
                        drawCircle(Color.White, 1.5f * density, Offset(xOf(pts.lastIndex), lastY))
                    }
                }

                // Chế độ tương tác vuốt (Scrubbing)
                if (touchedIndex in history.indices && series.isNotEmpty()) {
                    val pts0 = series[0].values
                    if (touchedIndex < pts0.size) {
                        val cx = leftPad + (touchedIndex.toFloat() / (pts0.size - 1)) * usableW

                        drawLine(Color.White, Offset(cx, topPad), Offset(cx, topPad + usableH), strokeWidth = 1f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))

                        val tsText = history[touchedIndex].timestamp
                        val timeStr = if (tsText.length >= 19) tsText.substring(11, 19) else tsText
                        val tsPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = textPx * 0.9f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                            isFakeBoldText = true
                        }
                        val tsBgPaint = android.graphics.Paint().apply { color = android.graphics.Color.argb(230, 20, 20, 30) }
                        val tsW = tsPaint.measureText(timeStr)
                        var boxCx = cx
                        if (boxCx - tsW/2 - 10f < leftPad) boxCx = leftPad + tsW/2 + 10f
                        if (boxCx + tsW/2 + 10f > w) boxCx = w - tsW/2 - 10f
                        drawContext.canvas.nativeCanvas.drawRoundRect(
                            android.graphics.RectF(boxCx - tsW/2 - 15f, (topPad + usableH) - textPx - 10f, boxCx + tsW/2 + 15f, topPad + usableH + 4f),
                            8f, 8f, tsBgPaint
                        )
                        drawContext.canvas.nativeCanvas.drawText(timeStr, boxCx, topPad + usableH - 2f, tsPaint)

                        val labelPaint = android.graphics.Paint().apply {
                            textSize = textPx * 1.15f
                            isAntiAlias = true
                            isFakeBoldText = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        }

                        series.forEach { s ->
                            if (touchedIndex >= s.values.size) return@forEach
                            val v = s.values[touchedIndex]
                            val cy = (topPad + usableH) - ((v.coerceIn(minVal, maxVal) - minVal) / range) * usableH

                            drawCircle(s.color.copy(alpha = 0.4f), 8f * density, Offset(cx, cy))
                            drawCircle(s.color, 5f * density, Offset(cx, cy))
                            drawCircle(Color.White, 2.5f * density, Offset(cx, cy))

                            val fmt = if (tabIndex == 2) "%.2f" else "%.1f"
                            val label = "${fmt.format(v)}${s.unit}"
                            val txtW = labelPaint.measureText(label)

                            val isLeft = cx + txtW + 30f > w
                            val labelX = if (isLeft) cx - txtW/2 - 15f else cx + txtW/2 + 15f
                            val labelY = cy - 5f

                            val bgC = s.color
                            val bgColor = android.graphics.Color.argb(220, (bgC.red*255).toInt()/5, (bgC.green*255).toInt()/5, (bgC.blue*255).toInt()/5)

                            drawContext.canvas.nativeCanvas.drawRoundRect(
                                android.graphics.RectF(labelX - txtW/2 - 12f, labelY - textPx - 8f, labelX + txtW/2 + 12f, labelY + 8f),
                                12f, 12f, android.graphics.Paint().apply { color = bgColor }
                            )

                            labelPaint.color = android.graphics.Color.argb(255, (s.color.red*255).toInt(), (s.color.green*255).toInt(), (s.color.blue*255).toInt())
                            drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, labelPaint)
                        }
                    }
                }
            }
        }

        // Hàng dưới cùng: Nhãn thời gian cập nhật
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val idx = if (touchedIndex in history.indices) touchedIndex else history.size - 1
            val tsText = if (idx in history.indices) history[idx].timestamp else ""
            val timeLabel = if (tsText.length >= 19) tsText.substring(11, 19) else if (tsText.isNotBlank()) tsText else "Vừa cập nhật"
            val prefixText = if (touchedIndex != -1) "Mốc vuốt: " else "Cập nhật lúc: "

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Schedule, null, tint = Color(0xFF8892B0), modifier = Modifier.size(12.dp))
                Text("$prefixText$timeLabel", fontSize = 11.sp, color = Color(0xFF8892B0), fontWeight = FontWeight.Medium)
            }
        }
    }
}
@Composable
fun NasDailyReportPanel(report: DailyReportData) {
    val scoreColor = when {
        report.healthScore >= 80 -> _ChartAccentGreen
        report.healthScore >= 60 -> _ChartAccentOrange
        else -> _ChartAccentRed
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sức khoẻ tổng thể", fontSize = 13.sp, color = _ChartTextPrimary, fontWeight = FontWeight.Bold)
            Text("${report.healthScore}%", fontSize = 20.sp, color = scoreColor, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { report.healthScore / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = scoreColor, trackColor = _ChartDarkSurface
        )
        Spacer(Modifier.height(2.dp))

        @Composable
        fun StatCell(label: String, value: String, color: Color = _ChartTextPrimary, modifier: Modifier = Modifier) {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontSize = 8.sp, color = _ChartTextSecond, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        val fmtSize = { mb: Float ->
            val bytes = mb * 1024 * 1024
            when {
                bytes >= 1024L * 1024 * 1024 * 1024 -> "%.2f TB".format(bytes / (1024.0 * 1024 * 1024 * 1024))
                bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
                bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
                else -> "%.0f B".format(bytes.toDouble())
            }
        }
        val stats = listOf(
            Triple("CPU trung bình", "%.1f%%".format(report.cpuAvg), _ChartTextPrimary),
            Triple("CPU cao nhất", "%.1f%%".format(report.cpuPeak), if (report.cpuPeak > 90) _ChartAccentRed else _ChartTextPrimary),
            Triple("RAM trung bình", "%.1f%%".format(report.ramAvg), _ChartTextPrimary),
            Triple("RAM cao nhất", "%.1f%%".format(report.ramPeak), if (report.ramPeak > 90) _ChartAccentRed else _ChartTextPrimary),
            Triple("CPU °C trung bình", "%.1f".format(report.cpuTempAvg), _ChartTextPrimary),
            Triple("CPU °C cao nhất", "%.1f".format(report.cpuTempPeak), if (report.cpuTempPeak > 75) _ChartAccentOrange else _ChartAccentGreen),
            Triple("HDD °C trung bình", "%.1f".format(report.hddTempAvg), _ChartTextPrimary),
            Triple("HDD °C cao nhất", "%.1f".format(report.hddTempPeak), if (report.hddTempPeak > 50) _ChartAccentOrange else _ChartAccentGreen),
            Triple("Tải về", fmtSize(report.downloadMb), _ChartTextPrimary),
            Triple("Tải lên", fmtSize(report.uploadMb), _ChartTextPrimary),
            Triple("Lỗi hệ thống", "${report.errorCount}", if (report.errorCount > 0) _ChartAccentRed else _ChartAccentGreen),
            Triple("Cảnh báo", "${report.warningCount}", if (report.warningCount > 0) _ChartAccentOrange else _ChartAccentGreen)
        )
        stats.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (label, value, color) ->
                    StatCell(label, value, color, Modifier.weight(1f))
                }
                repeat(3 - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Text("Tổng mẫu: ${report.samples} điểm trong ngày ${report.date}",
            fontSize = 9.sp, color = _ChartTextSecond)
    }
}
