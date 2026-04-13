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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nas.naswebdav.DailyReportData
import com.nas.naswebdav.MetricsSnapshot
import com.nas.naswebdav.WebDavViewModel


private val _ChartDarkCard    = Color(0xFF0A0A0A)
private val _ChartDarkSurface = Color.Black
private val _ChartAccentBlue  = Color(0xFF0A0A0A)
private val _ChartAccentCyan  = Color(0xFF00D2FF)
private val _ChartAccentGreen = Color(0xFF00E676)
private val _ChartAccentOrange= Color(0xFFFF9100)
private val _ChartAccentRed   = Color(0xFFFF1744)
private val _ChartAccentPurple= Color(0xFFBB86FC)
private val _ChartAccentPink  = Color(0xFFFF6EC7)
private val _ChartTextPrimary = Color(0xFFE8E8E8)
private val _ChartTextSecond  = Color(0xFF8892B0)

@Composable
fun MonitoringChartCard(viewModel: WebDavViewModel) {
    val tabLabels  = listOf("🌡️ Nhiệt độ", "📊 Tài nguyên", "📶 Mạng")
    val hourLabels = listOf("1h", "6h", "24h")
    val hourValues = listOf(1, 6, 24)
    var showReport by remember { mutableStateOf(false) }
    val report = viewModel.dailyReport

    LaunchedEffect(viewModel.metricsHours) {
        viewModel.fetchMetricsHistory(viewModel.metricsHours)
    }
    LaunchedEffect(Unit) {
        viewModel.fetchDailyReport()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = _ChartDarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            var chartExpanded by remember { mutableStateOf(false) }

            // Header — nhấn để mở/đóng (không ripple)
            Row(
                Modifier.fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { chartExpanded = !chartExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Insights, null, tint = _ChartAccentCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("GIÁM SÁT", fontSize = 9.sp, color = _ChartTextSecond, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    hourLabels.forEachIndexed { i, label ->
                        val selected = hourValues[i] == viewModel.metricsHours
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) _ChartAccentCyan.copy(alpha = 0.2f) else Color.Transparent,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { viewModel.fetchMetricsHistory(hourValues[i]) }
                        ) {
                            Text(label, fontSize = 10.sp,
                                color = if (selected) _ChartAccentCyan else _ChartTextSecond,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
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
                    val sel = i == viewModel.metricsChartTab
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (sel) _ChartAccentBlue else _ChartDarkSurface,
                        modifier = Modifier.weight(1f).clickable { viewModel.metricsChartTab = i }
                    ) {
                        Text(label, fontSize = 10.sp,
                            color = if (sel) _ChartAccentCyan else _ChartTextSecond,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 5.dp).fillMaxWidth())
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Vùng biểu đồ — hiển thị theo trạng thái
            val history = viewModel.metricsHistory
            val error   = viewModel.metricsError
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
                                modifier = Modifier.clickable { viewModel.fetchMetricsHistory(viewModel.metricsHours) }
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
                history.isEmpty() && viewModel.isLoadingMetrics -> {
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
                                modifier = Modifier.clickable { viewModel.fetchMetricsHistory(viewModel.metricsHours) }
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
                else -> NasMetricsLineChart(history = history, tabIndex = viewModel.metricsChartTab)
            }


            Spacer(Modifier.height(10.dp))

            // Nút xem báo cáo hàng ngày
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = _ChartDarkSurface,
                modifier = Modifier.fillMaxWidth().clickable {
                    showReport = !showReport
                    if (showReport && report == null) viewModel.fetchDailyReport()
                }
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Assessment, null, tint = _ChartAccentPurple, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Báo cáo hôm qua", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = _ChartTextPrimary)
                            if (report != null) {
                                val icon = when {
                                    report.healthScore >= 80 -> "🟢"
                                    report.healthScore >= 60 -> "🟡"
                                    else -> "🔴"
                                }
                                Text("$icon Sức khoẻ: ${report.healthScore}%  |  ${report.date}",
                                    fontSize = 10.sp, color = _ChartTextSecond)
                            } else {
                                Text(if (viewModel.isDailyReportLoading) "Đang tải..." else "Nhấn để xem báo cáo ngày hôm qua",
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
        val icon: androidx.compose.ui.graphics.vector.ImageVector
    )
    val series: List<Series> = when (tabIndex) {
        0 -> listOf(
            Series(history.map { it.cpuTemp  }, Color(0xFFFF9100), "CPU",    "°C",   Icons.Default.Memory),
            Series(history.map { it.hddTemp  }, Color(0xFFFF1744),    "HDD",    "°C",   Icons.Default.Storage)
        )
        1 -> listOf(
            Series(history.map { it.cpuPercent }, Color(0xFF00D2FF),   "CPU",  "%",    Icons.Default.Speed),
            Series(history.map { it.ramPercent }, Color(0xFFBB86FC), "RAM",  "%",    Icons.Default.DeveloperBoard)
        )
        else -> listOf(
            Series(history.map { (it.netRxKbps / 1024f).coerceAtLeast(0f) }, Color(0xFF00E676), "Tải về",  " MB/s", Icons.Default.ArrowDownward),
            Series(history.map { (it.netTxKbps / 1024f).coerceAtLeast(0f) }, Color(0xFFFF6EC7),  "Tải lên", " MB/s", Icons.Default.ArrowUpward)
        )
    }

    val allVals = series.flatMap { it.values }
    val maxVal  = (allVals.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val minVal  = (allVals.minOrNull() ?: 0f).coerceAtMost(maxVal * 0.9f)
    val range   = (maxVal - minVal).coerceAtLeast(1f)

    var touchedIndex by remember { mutableIntStateOf(-1) }
    val pad = 10f
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val textPx = 10f * density 

    Column {
        // Chú thích màu — Legend
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(s.icon, null, tint = s.color, modifier = Modifier.size(12.dp))
                        Text("${s.label} (${s.unit.trim()})", fontSize = 10.sp, color = s.color, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        
        // Canvas biểu đồ + touch detection
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(history.size, tabIndex) {
                        awaitPointerEventScope {
                            while (true) {
                                // Sử dụng pass Initial để chặn thao tác vuốt màn hình của cha (Scrollable Column)
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val pos = event.changes.firstOrNull()?.position
                                if (pos != null && history.size >= 2) {
                                    val leftPadLocal = 42f * density // 42dp nhường chỗ cho nhãn Y
                                    val usableW = size.width - leftPadLocal - pad
                                    val step = usableW / (history.size - 1).toFloat()
                                    val idx = ((pos.x - leftPadLocal) / step).toInt().coerceIn(0, history.size - 1)
                                    
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
                val leftPad = 42f * density

                // Nhãn trục Y (max, mid, min) hiển thị đậm và dứt khoát
                val yPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(220, 200, 200, 200)
                    textSize = textPx
                    textAlign = android.graphics.Paint.Align.LEFT
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                drawContext.canvas.nativeCanvas.apply {
                    val fmt = if (tabIndex == 2) "%.1f" else "%.0f"
                    drawText(fmt.format(maxVal), 0f, pad + textPx, yPaint)
                    drawText(fmt.format((maxVal + minVal) / 2f), 0f, h / 2f + textPx/3f, yPaint)
                    drawText(fmt.format(minVal), 0f, h - pad, yPaint)
                }

                // Grid ngang
                for (i in 0..3) {
                    val y = pad + (i / 3f) * (h - pad * 2)
                    drawLine(Color(0x33FFFFFF), Offset(leftPad, y), Offset(w, y), strokeWidth = 1f)
                }

                // Vẽ các series
                series.forEach { s ->
                    val pts = s.values
                    if (pts.size < 2) return@forEach
                    val step = (w - leftPad - pad) / (pts.size - 1).toFloat()
                    fun xOf(i: Int) = leftPad + i * step
                    fun yOf(v: Float) = h - pad - ((v - minVal) / range) * (h - pad * 2)

                    // Fill mờ bên dưới
                    val fillPath = androidx.compose.ui.graphics.Path()
                    fillPath.moveTo(xOf(0), h - pad)
                    pts.forEachIndexed { i, v -> fillPath.lineTo(xOf(i), yOf(v)) }
                    fillPath.lineTo(xOf(pts.lastIndex), h - pad)
                    fillPath.close()
                    drawPath(fillPath, s.color.copy(alpha = 0.15f))

                    // Đường liên kết
                    for (i in 0 until pts.size - 1) {
                        drawLine(s.color, Offset(xOf(i), yOf(pts[i])), Offset(xOf(i + 1), yOf(pts[i + 1])), strokeWidth = 1f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }

                    // Điểm mốc cuối cùng nếu ko chạm
                    if(touchedIndex == -1) {
                        drawCircle(s.color, 4f * density, Offset(xOf(pts.lastIndex), yOf(pts.last())))
                        drawCircle(Color.White, 2f * density, Offset(xOf(pts.lastIndex), yOf(pts.last())))
                    }
                }

                // Chế độ tương tác vuốt (Scrubbing)
                if (touchedIndex in history.indices && series.isNotEmpty()) {
                    val pts0 = series[0].values
                    if (touchedIndex < pts0.size) {
                        val step = (w - leftPad - pad) / (pts0.size - 1).toFloat()
                        val cx = leftPad + touchedIndex * step

                        // Đường gióng dọc màu trắng nổi bật
                        drawLine(Color.White, Offset(cx, pad/2), Offset(cx, h), strokeWidth = 1f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 10f)))

                        // Timestamp cho dòng kẻ dọc
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
                            android.graphics.RectF(boxCx - tsW/2 - 15f, h - textPx - 15f, boxCx + tsW/2 + 15f, h),
                            8f, 8f, tsBgPaint
                        )
                        drawContext.canvas.nativeCanvas.drawText(timeStr, boxCx, h - 8f, tsPaint)

                        val labelPaint = android.graphics.Paint().apply {
                            textSize = textPx * 1.15f
                            isAntiAlias = true
                            isFakeBoldText = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        }

                        // Vẽ điểm nổi cho từng loại và hiển thị bong bóng
                        series.forEach { s ->
                            if (touchedIndex >= s.values.size) return@forEach
                            val v = s.values[touchedIndex]
                            val cy = h - pad - ((v - minVal) / range) * (h - pad * 2)

                            // Halo sáng hơn tại điểm chạm
                            drawCircle(s.color.copy(alpha = 0.5f), 10f * density, Offset(cx, cy))
                            drawCircle(s.color, 6f * density, Offset(cx, cy))
                            drawCircle(Color.White, 3f * density, Offset(cx, cy))

                            val fmt = if (tabIndex == 2) "%.2f" else "%.1f"
                            val label = "${fmt.format(v)}${s.unit}"
                            val txtW = labelPaint.measureText(label)
                            
                            val isLeft = cx + txtW + 30f > w
                            val labelX = if (isLeft) cx - txtW/2 - 15f else cx + txtW/2 + 15f
                            val labelY = cy - 5f

                            // Vẽ nền trong suốt đen nhám của popup
                            val bgC = s.color
                            val bgColor = android.graphics.Color.argb(220, (bgC.red*255).toInt()/5, (bgC.green*255).toInt()/5, (bgC.blue*255).toInt()/5)
                            
                            drawContext.canvas.nativeCanvas.drawRoundRect(
                                android.graphics.RectF(labelX - txtW/2 - 12f, labelY - textPx - 8f, labelX + txtW/2 + 12f, labelY + 8f),
                                12f, 12f, android.graphics.Paint().apply { color = bgColor }
                            )

                            // Đặt màu text đồng với màu series
                            labelPaint.color = android.graphics.Color.argb(255, (s.color.red*255).toInt(), (s.color.green*255).toInt(), (s.color.blue*255).toInt())
                            drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, labelPaint)
                        }
                    }
                }
            }
        }

        // Hàng giá trị dưới cùng hiển thị Động theo ngón tay (đồng bộ)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            val idx = if (touchedIndex in history.indices) touchedIndex else history.size - 1
            series.forEach { s ->
                if (idx in s.values.indices) {
                    val v = s.values[idx]
                    val fmt = if (tabIndex == 2) "%.2f" else "%.1f"
                    Text("${s.label}: ${fmt.format(v)}${s.unit}  ", fontSize = 11.sp, color = s.color, fontWeight = FontWeight.Bold)
                }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sức khoẻ tổng thể", fontSize = 13.sp, color = _ChartTextPrimary, fontWeight = FontWeight.Bold)
            Text("${report.healthScore}%", fontSize = 20.sp, color = scoreColor, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { report.healthScore / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = scoreColor, trackColor = _ChartDarkSurface
        )
        Spacer(Modifier.height(4.dp))

        @Composable
        fun StatPair(label1: String, val1: String, label2: String, val2: String, c1: Color = _ChartTextPrimary, c2: Color = _ChartTextPrimary) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(label1, fontSize = 9.sp, color = _ChartTextSecond)
                    Text(val1, fontSize = 13.sp, color = c1, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f)) {
                    Text(label2, fontSize = 9.sp, color = _ChartTextSecond)
                    Text(val2, fontSize = 13.sp, color = c2, fontWeight = FontWeight.Medium)
                }
            }
        }

        StatPair("CPU TB", "%.1f%%".format(report.cpuAvg), "CPU đỉnh", "%.1f%%".format(report.cpuPeak),
            c2 = if (report.cpuPeak > 90) _ChartAccentRed else _ChartTextPrimary)
        StatPair("RAM TB", "%.1f%%".format(report.ramAvg), "RAM đỉnh", "%.1f%%".format(report.ramPeak),
            c2 = if (report.ramPeak > 90) _ChartAccentRed else _ChartTextPrimary)
        StatPair("CPU °C TB", "%.1f".format(report.cpuTempAvg), "CPU °C đỉnh", "%.1f".format(report.cpuTempPeak),
            c2 = if (report.cpuTempPeak > 75) _ChartAccentOrange else _ChartAccentGreen)
        StatPair("HDD °C TB", "%.1f".format(report.hddTempAvg), "HDD °C đỉnh", "%.1f".format(report.hddTempPeak),
            c2 = if (report.hddTempPeak > 50) _ChartAccentOrange else _ChartAccentGreen)
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
        StatPair("Tải về", fmtSize(report.downloadMb), "Tải lên", fmtSize(report.uploadMb))
        StatPair("Lỗi hệ thống", "${report.errorCount}", "Cảnh báo", "${report.warningCount}",
            c1 = if (report.errorCount > 0) _ChartAccentRed else _ChartAccentGreen,
            c2 = if (report.warningCount > 0) _ChartAccentOrange else _ChartAccentGreen)
        Text("Tổng mẫu: ${report.samples} điểm trong ngày ${report.date}",
            fontSize = 9.sp, color = _ChartTextSecond)
    }
}
