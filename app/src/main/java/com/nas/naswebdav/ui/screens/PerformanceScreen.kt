@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============ PerformanceScreen (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(onBack: () -> Unit) {
    val mContext = LocalContext.current
    val metrics by PerformanceMonitor.metricsFlow.collectAsState()
    LaunchedEffect(Unit) { PerformanceMonitor.startMonitoring(mContext) }
    Scaffold(topBar = { TopAppBar(title = { Text("Màn Giám Sát Kỹ Thuật (DevOps Monitor)", style = MaterialTheme.typography.headlineSmall) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Trở lại") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(DarkSurface).padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PerfMetricCard("Động Cơ JVM (App RAM)", Icons.Default.Memory, "${metrics.usedJvmMemoryMb} MB / ${metrics.maxJvmMemoryMb} MB", if (metrics.maxJvmMemoryMb > 0) metrics.usedJvmMemoryMb.toFloat() / metrics.maxJvmMemoryMb else 0f, if (metrics.usedJvmMemoryMb > metrics.maxJvmMemoryMb * 0.8) AccentRed else AccentGreen)
            PerfMetricCard("Bộ Nhớ Hệ Thống (Màng RAM)", Icons.Default.Adb, "Trống: ${metrics.freeRamMb} MB (Tổng: ${metrics.totalRamMb} MB)", metrics.ramUsagePercent / 100f, if (metrics.ramUsagePercent > 85) AccentRed else AccentBlue)
            PerfMetricCard("Trái Tim Chip Bán Dẫn (CPU Thread)", Icons.Default.Speed, "Hoạt động: ${metrics.cpuUsagePercent}% (Dao động ảo)", metrics.cpuUsagePercent / 100f, if (metrics.cpuUsagePercent > 70) AccentOrange else AccentCyan)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PerfNetworkBadge(Modifier.weight(1f), "Tải Xuống", "${metrics.rxSpeedKbps} KB/s", Icons.Default.ArrowDownward, AccentGreen)
                PerfNetworkBadge(Modifier.weight(1f), "Đẩy Lên", "${metrics.txSpeedKbps} KB/s", Icons.Default.ArrowUpward, AccentOrange)
            }
            PerfMetricCard("Kho Gạch Ngói Hình Ảnh (Coil Disk Cache)", Icons.Default.Storage, "${metrics.diskCacheSizeMb} MB đang ngốn rác", (metrics.diskCacheSizeMb / 800f).coerceIn(0f, 1f), AccentPurple)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { coil.Coil.imageLoader(mContext).memoryCache?.clear() }, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentPink)) { Icon(Icons.Default.DeleteForever, contentDescription = "Clear cache icon"); Spacer(Modifier.width(8.dp)); Text("BĂM NÚT BỘ ĐỆM RAM (Tránh Đơ Máy)", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun PerfMetricCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, progress: Float, progressColor: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(title, color = TextTertiary, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp)); Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = progressColor, trackColor = DarkCardHover)
        }
    }
}

@Composable
fun PerfNetworkBadge(modifier: Modifier, title: String, speed: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(4.dp)); Text(title, color = TextTertiary, fontSize = 12.sp); Text(speed, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
