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
    Scaffold(topBar = { TopAppBar(title = { Text("Màn Giám Sát Kỹ Thuật (DevOps Monitor)", fontSize = 18.sp, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Trở lại") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard("Động Cơ JVM (App RAM)", Icons.Default.Memory, "${metrics.usedJvmMemoryMb} MB / ${metrics.maxJvmMemoryMb} MB", if (metrics.maxJvmMemoryMb > 0) metrics.usedJvmMemoryMb.toFloat() / metrics.maxJvmMemoryMb else 0f, if (metrics.usedJvmMemoryMb > metrics.maxJvmMemoryMb * 0.8) Color.Red else Color.Green)
            MetricCard("Bộ Nhớ Hệ Thống (Màng RAM)", Icons.Default.Adb, "Trống: ${metrics.freeRamMb} MB (Tổng: ${metrics.totalRamMb} MB)", metrics.ramUsagePercent / 100f, if (metrics.ramUsagePercent > 85) Color.Red else Color(0xFF03A9F4))
            MetricCard("Trái Tim Chip Bán Dẫn (CPU Thread)", Icons.Default.Speed, "Hoạt động: ${metrics.cpuUsagePercent}% (Dao động ảo)", metrics.cpuUsagePercent / 100f, if (metrics.cpuUsagePercent > 70) Color(0xFFFF9800) else Color.Cyan)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkBadge(Modifier.weight(1f), "Tải Xuống", "${metrics.rxSpeedKbps} KB/s", Icons.Default.ArrowDownward, Color.Green)
                NetworkBadge(Modifier.weight(1f), "Đẩy Lên", "${metrics.txSpeedKbps} KB/s", Icons.Default.ArrowUpward, Color(0xFFFF5722))
            }
            MetricCard("Kho Gạch Ngói Hình Ảnh (Coil Disk Cache)", Icons.Default.Storage, "${metrics.diskCacheSizeMb} MB đang ngốn rác", (metrics.diskCacheSizeMb / 800f).coerceIn(0f, 1f), Color(0xFF9C27B0))
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { coil.Coil.imageLoader(mContext).memoryCache?.clear(); System.gc() }, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))) { Icon(Icons.Default.DeleteForever, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("BĂM NÚT BỘ ĐỆM RAM (Tránh Đơ Máy)", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun MetricCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, progress: Float, progressColor: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(title, color = Color.Gray, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp)); Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = progressColor, trackColor = Color(0xFF424242))
        }
    }
}

@Composable
fun NetworkBadge(modifier: Modifier, title: String, speed: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(4.dp)); Text(title, color = Color.Gray, fontSize = 12.sp); Text(speed, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
