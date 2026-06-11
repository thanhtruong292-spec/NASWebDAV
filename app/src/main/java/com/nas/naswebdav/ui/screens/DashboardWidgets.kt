@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ============ Dashboard reusable widgets (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

// ============ COMPONENT: Inline stat row (emoji + label + value) ============
@Composable
fun InlineStatRow(emoji: String, label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 13.sp)
        Spacer(Modifier.width(5.dp))
        Column {
            Text(label, fontSize = 8.sp, color = TextSecondary, letterSpacing = 0.8.sp)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
        }
    }
}

// ============ COMPONENT: Thẻ đo lớn (CPU / RAM) với gradient ============
private fun insightRate(bytesPerSec: Long): String {
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

@Composable
fun NasInsightsSummaryCard(
    viewModel: WebDavViewModel,
    onOpen: () -> Unit
) {
    val insight = viewModel.nasInsights
    val modeColor = when (insight.workloadMode) {
        "protect" -> AccentRed
        "balanced" -> AccentOrange
        else -> AccentGreen
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoGraph, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("PHÂN TÍCH HỆ THỐNG", color = PanelTitleCyan, fontSize = PanelTitleSize, fontWeight = FontWeight.Black, letterSpacing = PanelTitleLetterSpacing)
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(modeColor.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    val displayMode = when(insight.workloadMode.lowercase()) {
                        "normal" -> "BÌNH THƯỜNG"
                        "balanced" -> "CÂN BẰNG TẢI"
                        "protect" -> "BẢO VỆ HỆ THỐNG"
                        else -> insight.workloadMode.uppercase()
                    }
                    Text(displayMode, color = modeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightMiniStat("HDD", "${insight.hddScore}/100", "${insight.hddTempC}°C", AccentGreen, Modifier.weight(1f))
                InsightMiniStat("eMMC", "${insight.emmcRootPercent}%", "log ${insight.emmcLogPercent}%", if (insight.emmcWarnings.isEmpty()) AccentCyan else AccentOrange, Modifier.weight(1f))
                InsightMiniStat("Ghi HDD", insightRate(insight.diskWriteBps), "đọc ${insightRate(insight.diskReadBps)}", AccentPurple, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val summary = insight.maintenanceActions.firstOrNull()?.detail
                ?: insight.workloadRecommendation.ifBlank { "Đang chờ dữ liệu phân tích NAS." }
            Text(summary, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (insight.flowTasks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val task = insight.flowTasks.first()
                Text("${task.label}: ${task.file.ifBlank { "đang thực thi" }}", color = AccentCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InsightMiniStat(title: String, value: String, sub: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF171922), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(title, color = TextSecondary, fontSize = 10.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GaugeCard(
    title: String,
    value: String,
    subValue: String? = null,
    icon: ImageVector,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    overridePercent: Float? = null,
    label: String? = null,
    onClick: (() -> Unit)? = null
) {
    val numericValue = overridePercent ?: (Regex("[^0-9.]").replace(value, "").toFloatOrNull() ?: 0f)
    val progress = (numericValue / 100f).coerceIn(0f, 1f)
    
    // Status từ phần trăm (CPU%/RAM%/Disk%)
    val pctRank = when {
        progress >= 0.90f -> 2
        progress >= 0.70f -> 1
        else -> 0
    }
    // Status từ nhiệt độ (nếu subValue có °C) — dùng cùng ngưỡng như line chart và text subValue:
    //   CPU temp: >=80 đỏ, >=60 vàng. HDD/SMART temp: >=55 đỏ, >=45 vàng.
    val tempRank: Int = if (!subValue.isNullOrBlank() && (subValue.contains("°C") || subValue.contains("°C") || subValue.contains("°"))) {
        val tempVal = Regex("[^0-9.]").replace(subValue, "").toFloatOrNull() ?: 0f
        val isDisk = title == "S.M.A.R.T" || title == "HDD"
        when {
            isDisk && tempVal >= 55f -> 2
            isDisk && tempVal >= 45f -> 1
            !isDisk && tempVal >= 80f -> 2
            !isDisk && tempVal >= 60f -> 1
            else -> 0
        }
    } else -1
    val accentColor = if (title == "S.M.A.R.T") {
        gradientColors.first()
    } else {
        // Lay trang thai NANG HON giua phan tram va nhiet do de vong tron dong bo voi line chart.
        when (maxOf(pctRank, tempRank)) {
            2 -> Color(0xFFEF5350) // Đỏ
            1 -> Color(0xFFFFA726) // Vàng
            else -> Color(0xFF66BB6A) // Xanh
        }
    }

    Card(
        modifier = if (onClick != null) modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ) else modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(64.dp),
                        color = accentColor,
                        trackColor = TextSecondary.copy(alpha = 0.15f),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy((-3).dp)
                    ) {
                        Icon(icon, null, tint = accentColor, modifier = Modifier.size(16.dp).offset(y = 2.dp))
                        Text(
                            text = title,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor.copy(alpha = 0.85f),
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.offset(y = 1.dp)
                        )
                        if (!subValue.isNullOrBlank() && subValue != "--\u00b0C" && subValue != "--°C") {
                            Text(
                                text = subValue,
                                fontSize = 8.sp,
                                color = if (subValue.contains("°C") || subValue.contains("\u00b0C") || subValue.contains("°")) {
                                    val tempVal = Regex("[^0-9.]").replace(subValue, "").toFloatOrNull() ?: 0f
                                    val isDisk = title == "S.M.A.R.T" || title == "HDD"
                                    when {
                                        isDisk && tempVal >= 55f -> Color(0xFFEF5350)
                                        isDisk && tempVal >= 45f -> Color(0xFFFFA726)
                                        !isDisk && tempVal >= 80f -> Color(0xFFEF5350)
                                        !isDisk && tempVal >= 60f -> Color(0xFFFFA726) // CPU 60+ is Yellow
                                        else -> Color(0xFF66BB6A)
                                    }
                                } else accentColor.copy(alpha = 0.9f),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.offset(y = (-1).dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    val textSize = if (value.length > 7) 8.5.sp else 11.sp
                    Text(value, fontSize = textSize, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ============ COMPONENT: Thẻ thống kê nhỏ? ============
@Composable
fun MiniStatCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, fontSize = 9.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

// ============ COMPONENT: Thanh phân vùng ổ đĩa ============
@Composable
fun DiskPartitionBar(mount: String, percent: Float, total: String, used: String) {
    val barColor = when {
        percent >= 90f -> AccentRed
        percent >= 75f -> AccentOrange
        else -> AccentGreen
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(mount, fontSize = 12.sp, color = TextPrimary)
            Text("$used / $total", fontSize = 11.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = barColor,
            trackColor = TextSecondary.copy(alpha = 0.15f)
        )
    }
}

// ============ COMPONENT: Quick Action Chip ============
@Composable
fun QuickActionChip(label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ============ COMPONENT: Thẻ menu lớn (gradient) ============
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BigMenuTile(title: String, subtitle: String, icon: ImageVector, gradientColors: List<Color>, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { if (onLongClick != null) onLongClick() else onClick() }
                )
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradientColors))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Icon(icon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Column {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(subtitle, fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// ============ COMPONENT: Settings Menu Card ============
@Composable
fun SettingsMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 10.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (checked != null) {
                Switch(
                    checked = checked,
                    onCheckedChange = { onClick() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = color,
                        checkedTrackColor = color.copy(alpha = 0.4f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = TextSecondary.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.graphicsLayer { scaleX = 0.7f; scaleY = 0.7f }
                )
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ============ HÀM TIỆN ÍCH (Giữ lại tương thích) ============
fun getStatusColor(title: String, value: String, rawPercent: String = ""): Color {
    try {
        val extractNumber = { str: String -> Regex("[^0-9.]").replace(str, "").toFloatOrNull() ?: 0f }
        return when (title) {
            "Nhiệt độ" -> {
                val t = extractNumber(value)
                when { t >= 75f -> AccentRed; t >= 60f -> AccentOrange; t > 0f -> AccentGreen; else -> Color.Gray }
            }
            "CPU", "Ổ đĩa" -> {
                val p = extractNumber(value)
                when { p >= 90f -> AccentRed; p >= 75f -> AccentOrange; p > 0f -> AccentGreen; else -> Color.Gray }
            }
            "RAM" -> {
                val p = if (rawPercent.isNotBlank()) extractNumber(rawPercent) else extractNumber(value)
                when { p >= 90f -> AccentRed; p >= 75f -> AccentOrange; p > 0f -> AccentGreen; else -> Color.Gray }
            }
            else -> Color.Gray
        }
    } catch (e: Exception) { return Color.Gray }
}

// Giữ lại MenuCard tương thích cho các file khác nếu cần
@Composable
fun MenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    SettingsMenuCard(title = title, subtitle = subtitle, icon = icon, color = color, checked = checked, onClick = onClick)
}

@Composable
fun SystemStatusItem(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    MiniStatCard(title = title, value = value, icon = icon, color = color, modifier = modifier)
}



@Composable
fun TemperatureChartCard(history: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BIỂU ĐỒ NHIỆT ĐỘ",
                    fontSize = 9.sp,
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                // Legend
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFFF9800)))
                        Spacer(Modifier.width(4.dp))
                        Text("CPU", fontSize = 9.sp, color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF03A9F4)))
                        Spacer(Modifier.width(4.dp))
                        Text("HDD", fontSize = 9.sp, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            
            // Vẽ Biểu đồ bằng Native Canvas (Chiếm 0MB RAM)
            Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                val width = size.width
                val height = size.height
                
                // Mức giới hạn đo nhiệt độ từ 30°C đến 100°C
                val minTemp = 30f
                val maxTemp = 100f
                val range = maxTemp - minTemp
                
                // Đường lưới đứt nét ngang (Grid Lines)
                val gridPaint = androidx.compose.ui.graphics.Paint().apply {
                    color = Color.DarkGray
                    strokeWidth = 1f
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                }
                for (i in 0..4) {
                    val y = height - (i * (height / 4))
                    drawLine(
                        color = Color.DarkGray.copy(alpha = 0.5f),
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(width, y),
                        strokeWidth = 1f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }

                if (history.size < 2) return@Canvas
                
                val pointWidth = width / (40f - 1) // 40 points max
                
                // Vẽ Data
                val cpuPath = androidx.compose.ui.graphics.Path()
                val hddPath = androidx.compose.ui.graphics.Path()
                
                history.forEachIndexed { index, (cpu, hdd) ->
                    val x = index * pointWidth
                    // Calculate Y and clamp it
                    val clampedCpu = cpu.coerceIn(minTemp, maxTemp)
                    val cpuY = height - ((clampedCpu - minTemp) / range * height)
                    
                    val clampedHdd = hdd.coerceIn(minTemp, maxTemp)
                    val hddY = height - ((clampedHdd - minTemp) / range * height)
                    
                    if (index == 0) {
                        cpuPath.moveTo(x, cpuY)
                        hddPath.moveTo(x, hddY)
                    } else {
                        val prevX = (index - 1) * pointWidth
                        val prevCpu = history[index - 1].first.coerceIn(minTemp, maxTemp)
                        val prevCpuY = height - ((prevCpu - minTemp) / range * height)
                        val prevHdd = history[index - 1].second.coerceIn(minTemp, maxTemp)
                        val prevHddY = height - ((prevHdd - minTemp) / range * height)
                        
                        // Bezier Curve tạo đường cong mượt
                        cpuPath.cubicTo(
                            prevX + pointWidth / 2, prevCpuY,
                            x - pointWidth / 2, cpuY,
                            x, cpuY
                        )
                        hddPath.cubicTo(
                            prevX + pointWidth / 2, prevHddY,
                            x - pointWidth / 2, hddY,
                            x, hddY
                        )
                    }
                }
                
                // Vẽ nét đôi
                drawPath(
                    path = cpuPath,
                    color = Color(0xFFFF9800),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                drawPath(
                    path = hddPath,
                    color = Color(0xFF03A9F4),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                
                // Vẽ điểm gút cuối cùng (cục tròn phát sáng nhẹ)
                val lastPoint = history.last()
                val lastX = (history.size - 1) * pointWidth
                
                val lastCpuY = height - ((lastPoint.first.coerceIn(minTemp, maxTemp) - minTemp) / range * height)
                drawCircle(color = Color(0xFFFF9800), radius = 6f, center = androidx.compose.ui.geometry.Offset(lastX, lastCpuY))
                drawCircle(color = Color.White, radius = 3f, center = androidx.compose.ui.geometry.Offset(lastX, lastCpuY))
                
                val lastHddY = height - ((lastPoint.second.coerceIn(minTemp, maxTemp) - minTemp) / range * height)
                drawCircle(color = Color(0xFF03A9F4), radius = 6f, center = androidx.compose.ui.geometry.Offset(lastX, lastHddY))
                drawCircle(color = Color.White, radius = 3f, center = androidx.compose.ui.geometry.Offset(lastX, lastHddY))
                
                // Vẽ chữ hiển thị thông số tại thời điểm đo
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "${String.format("%.1f", lastPoint.first)}°C", 
                    lastX - 15f, 
                    lastCpuY - 15f, 
                    paint
                )
                paint.color = android.graphics.Color.parseColor("#03A9F4")
                drawContext.canvas.nativeCanvas.drawText(
                    "${String.format("%.1f", lastPoint.second)}°C", 
                    lastX - 15f, 
                    lastHddY + 30f, 
                    paint
                )
            }
        }
    }
}

