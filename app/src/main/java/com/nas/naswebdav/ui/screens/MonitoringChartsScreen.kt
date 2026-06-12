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
import com.nas.naswebdav.MetricsSnapshot
import com.nas.naswebdav.WebDavViewModel


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

private fun cpuTempStatusColor(latest: Float): Color = when {
    latest >= 80f -> _StatusRed
    latest >= 60f -> _StatusYellow
    else -> _StatusGreen
}

private fun hddTempStatusColor(latest: Float): Color = when {
    latest >= 55f -> _StatusRed
    latest >= 45f -> _StatusYellow
    else -> _StatusGreen
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
                    val ageSec = if (viewModel.lastMetricsRefreshAt > 0L) ((System.currentTimeMillis() - viewModel.lastMetricsRefreshAt).coerceAtLeast(0L) / 1000L).toInt() else -1
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
                        val selected = hourValues[i] == viewModel.metricsHours
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) _ChartAccentCyan.copy(alpha = 0.16f) else Color.Transparent,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // Force open chart panel (overrides other panels)
                                com.nas.naswebdav.ui.screens.ExclusivePanelState.current.value = "chart"
                                viewModel.fetchMetricsHistory(hourValues[i])
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
                    val sel = i == viewModel.metricsChartTab
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (sel) Color(0xFF1B5E20).copy(alpha = 0.5f) else _ChartDarkSurface,
                        modifier = Modifier.weight(1f).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { viewModel.metricsChartTab = i }
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
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        // Neu khac null: moi diem/doan duoc to mau theo gia tri cua chinh no.
        // Neu null: dung mau co dinh (vd tab Mang khong co nguong nhiet do).
        val colorOf: ((Float) -> Color)? = null,
        // Kieu net rieng cho tung duong (null = lien net) de phan biet CPU/HDD/RAM.
        val dash: FloatArray? = null
    )
    // Lay gia tri moi nhat (cuoi danh sach) de quyet dinh mau theo trang thai —
    // dong bo voi GaugeCard tron tren MainMenuScreen.
    val cpuTempVals = history.map { it.cpuTemp }
    val hddTempVals = history.map { it.hddTemp }
    val cpuPctVals  = history.map { it.cpuPercent }
    val ramPctVals  = history.map { it.ramPercent }

    // Moi duong 1 mau co dinh + 1 kieu net rieng -> de phan biet CPU/HDD/RAM.
    val series: List<Series> = when (tabIndex) {
        0 -> listOf(
            Series(cpuTempVals, Color(0xFF00D2FF), "CPU", "°C", Icons.Default.Memory),
            Series(hddTempVals, Color(0xFFFF9100), "HDD", "°C", Icons.Default.Storage, dash = floatArrayOf(14f, 8f))
        )
        1 -> listOf(
            Series(cpuPctVals, Color(0xFF2196F3), "CPU", "%", Icons.Default.Speed),
            Series(ramPctVals, Color(0xFFBB86FC), "RAM", "%", Icons.Default.DeveloperBoard, dash = floatArrayOf(14f, 8f))
        )
        else -> listOf(
            Series(history.map { (it.netRxKbps / 1024f).coerceAtLeast(0f) }, Color(0xFF00E676), "Tải về",  " MB/s", Icons.Default.ArrowDownward),
            Series(history.map { (it.netTxKbps / 1024f).coerceAtLeast(0f) }, Color(0xFFFF6EC7),  "Tải lên", " MB/s", Icons.Default.ArrowUpward, dash = floatArrayOf(14f, 8f))
        )
    }

    val allVals = series.flatMap { it.values }
    // Truc Y co dinh theo tab: Nhiet do 20-80, Tai nguyen 0-100; Mang tu dong co gian.
    val (minVal, maxVal) = when (tabIndex) {
        0    -> 20f to 80f
        1    -> 0f to 100f
        else -> {
            val mx = (allVals.maxOrNull() ?: 1f).coerceAtLeast(1f)
            val mn = (allVals.minOrNull() ?: 0f).coerceAtMost(mx * 0.9f)
            mn to mx
        }
    }
    val range   = (maxVal - minVal).coerceAtLeast(1f)

    var touchedIndex by remember { mutableIntStateOf(-1) }
    val pad = 10f
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val textPx = 10f * density 

    Column {
        // Chú thích màu — Legend
        // Màu theo giá trị ĐANG HIỂN THỊ: nếu user đang chạm thì màu của điểm đó,
        // nếu không thì màu của giá trị cuối (đồng bộ với GaugeCard tròn).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                series.forEach { s ->
                    val displayIdx = if (touchedIndex in s.values.indices) touchedIndex else s.values.lastIndex
                    val legendColor = if (displayIdx >= 0) (s.colorOf?.invoke(s.values[displayIdx]) ?: s.color) else s.color
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(s.icon, null, tint = legendColor, modifier = Modifier.size(12.dp))
                        Text("${s.label} (${s.unit.trim()})", fontSize = 10.sp, color = legendColor, fontWeight = FontWeight.Medium)
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
                    fun yOf(v: Float) = h - pad - ((v.coerceIn(minVal, maxVal) - minVal) / range) * (h - pad * 2)

                    if (s.colorOf != null) {
                        // ----- Tab Nhiet do / Tai nguyen: to mau theo tung diem -----
                        // Voi moi doan [i, i+1] ve 1 hinh thang fill rieng + line
                        // mau trung binh cua 2 dau doan -> nhin nhu gradient muot.
                        val colorFn = s.colorOf
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
                            // Fill hinh thang duoi doan nay
                            val segPath = androidx.compose.ui.graphics.Path().apply {
                                moveTo(x1, h - pad)
                                lineTo(x1, y1)
                                lineTo(x2, y2)
                                lineTo(x2, h - pad)
                                close()
                            }
                            drawPath(segPath, cMid.copy(alpha = 0.15f))
                            // Duong line cua doan
                            drawLine(cMid, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    } else {
                        // ----- Tab Mang: giu 1 mau co dinh nhu cu -----
                        val fillPath = androidx.compose.ui.graphics.Path()
                        fillPath.moveTo(xOf(0), h - pad)
                        pts.forEachIndexed { i, v -> fillPath.lineTo(xOf(i), yOf(v)) }
                        fillPath.lineTo(xOf(pts.lastIndex), h - pad)
                        fillPath.close()
                        drawPath(fillPath, s.color.copy(alpha = 0.15f))

                        // Ve duong lien tuc 1 Path de net dut (dash) chay muot, day net cho de nhin.
                        val linePath = androidx.compose.ui.graphics.Path()
                        linePath.moveTo(xOf(0), yOf(pts[0]))
                        for (i in 1 until pts.size) linePath.lineTo(xOf(i), yOf(pts[i]))
                        drawPath(
                            linePath,
                            color = s.color,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.8f * density,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                pathEffect = s.dash?.let { androidx.compose.ui.graphics.PathEffect.dashPathEffect(it) }
                            )
                        )
                    }

                    // Điểm mốc cuối cùng nếu ko chạm — dung mau cua chinh diem cuoi
                    if(touchedIndex == -1) {
                        val endColor = s.colorOf?.invoke(pts.last()) ?: s.color
                        drawCircle(endColor, 4f * density, Offset(xOf(pts.lastIndex), yOf(pts.last())))
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
                            // Mau cua chinh diem dang cham — to chinh xac theo gia tri tai do
                            val pointColor = s.colorOf?.invoke(v) ?: s.color

                            // Halo sáng hơn tại điểm chạm
                            drawCircle(pointColor.copy(alpha = 0.5f), 10f * density, Offset(cx, cy))
                            drawCircle(pointColor, 6f * density, Offset(cx, cy))
                            drawCircle(Color.White, 3f * density, Offset(cx, cy))

                            val fmt = if (tabIndex == 2) "%.2f" else "%.1f"
                            val label = "${fmt.format(v)}${s.unit}"
                            val txtW = labelPaint.measureText(label)

                            val isLeft = cx + txtW + 30f > w
                            val labelX = if (isLeft) cx - txtW/2 - 15f else cx + txtW/2 + 15f
                            val labelY = cy - 5f

                            // Vẽ nền trong suốt đen nhám của popup (lay theo mau diem chu khong theo series)
                            val bgC = pointColor
                            val bgColor = android.graphics.Color.argb(220, (bgC.red*255).toInt()/5, (bgC.green*255).toInt()/5, (bgC.blue*255).toInt()/5)

                            drawContext.canvas.nativeCanvas.drawRoundRect(
                                android.graphics.RectF(labelX - txtW/2 - 12f, labelY - textPx - 8f, labelX + txtW/2 + 12f, labelY + 8f),
                                12f, 12f, android.graphics.Paint().apply { color = bgColor }
                            )

                            // Đặt màu text đồng với màu diem
                            labelPaint.color = android.graphics.Color.argb(255, (pointColor.red*255).toInt(), (pointColor.green*255).toInt(), (pointColor.blue*255).toInt())
                            drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, labelPaint)
                        }
                    }
                }
            }
        }

        // Hàng giá trị dưới cùng hiển thị Động theo ngón tay (đồng bộ)
        // Mau chu theo gia tri hien thi (touched neu co, else gia tri cuoi) — match legend + popup.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            val idx = if (touchedIndex in history.indices) touchedIndex else history.size - 1
            series.forEach { s ->
                if (idx in s.values.indices) {
                    val v = s.values[idx]
                    val fmt = if (tabIndex == 2) "%.2f" else "%.1f"
                    val rowColor = s.colorOf?.invoke(v) ?: s.color
                    Text("${s.label}: ${fmt.format(v)}${s.unit}  ", fontSize = 11.sp, color = rowColor, fontWeight = FontWeight.Bold)
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
