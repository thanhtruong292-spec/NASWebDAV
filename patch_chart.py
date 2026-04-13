import sys

kt_file = r'd:\\Android\\NASWebDAV\\app\\src\\main\\java\\com\\nas\\naswebdav\\ui\\screens\\MonitoringChartsScreen.kt'

new_chart_code = """@Composable
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
                        drawLine(s.color, Offset(xOf(i), yOf(pts[i])), Offset(xOf(i + 1), yOf(pts[i + 1])), strokeWidth = 3f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
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
                        drawLine(Color.White, Offset(cx, pad/2), Offset(cx, h), strokeWidth = 1.5f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 10f)))

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
"""

with open(kt_file, 'r', encoding='utf-8') as f:
    lines = f.readlines()

start = -1
end = -1
brace_cnt = 0
in_func = False

for i, lineStr in enumerate(lines):
    if '@Composable' in lineStr and i+1 < len(lines) and 'fun NasMetricsLineChart(' in lines[i+1]:
        start = i
        in_func = True
        brace_cnt = 0
    elif 'fun NasMetricsLineChart(' in lineStr and not in_func:
        # Neu mat chu @Composable (co the no o line khac)
        if start == -1:
            start = i
        in_func = True
        brace_cnt = 0

    if in_func:
        brace_cnt += lineStr.count('{') - lineStr.count('}')
        if brace_cnt == 0 and '{' in lineStr and '}' in lineStr:
            pass # cung dong
        if brace_cnt == 0 and '}' in lineStr:
            end = i
            break

if start != -1 and end != -1:
    lines[start:end+1] = [new_chart_code + '\\n']
    with open(kt_file, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    print(f"SUCCESS: Replaced NasMetricsLineChart ({start} to {end})")
else:
    print(f"FAILED TO FIND EXACT BLOCK bounds. Start: {start}, End: {end}")
