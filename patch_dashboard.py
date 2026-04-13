import sys
import re

kt_file = r'd:\Android\NASWebDAV\app\src\main\java\com\nas\naswebdav\ui\screens\MainMenuScreen.kt'
chart_file = r'd:\Android\NASWebDAV\app\src\main\java\com\nas\naswebdav\ui\screens\MonitoringChartsScreen.kt'

# 1. Stroke width
with open(chart_file, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('strokeWidth = 1.5f * density', 'strokeWidth = 1f * density')
with open(chart_file, 'w', encoding='utf-8') as f:
    f.write(text)

# 2. Top Padding & System Block
with open(kt_file, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('Spacer(Modifier.height(8.dp))\n\n        // ═══ HEADER ═══', 'Spacer(Modifier.windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars))\n        Spacer(Modifier.height(16.dp))\n\n        // ═══ HEADER ═══')

system_block_replacement = """// Đã THẾ HỆ THỐNG: CPU + RAM + Stats Đã 
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("HỆ THỐNG", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                
                // Hàng 1: CPU và RAM
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GaugeCard(
                        title = "CPU", value = viewModel.systemStatus.cpu,
                        subValue = viewModel.systemStatus.cpuTemp,
                        icon = Icons.Default.DeveloperBoard,
                        gradientColors = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
                        modifier = Modifier.weight(1f)
                    )
                    GaugeCard(
                        title = "RAM", value = viewModel.systemStatus.ram, subValue = null,
                        icon = Icons.Default.Memory,
                        gradientColors = listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
                        modifier = Modifier.weight(1f),
                        overridePercent = viewModel.systemStatus.ramPercent.toFloatOrNull()
                    )
                }
                Spacer(Modifier.height(12.dp))
                
                // Hàng 2: Bộ nhớ trong và HDD
                val rootDisk = viewModel.systemStatus.diskParts.find { it.mount == "/" }
                val hddDisk = viewModel.systemStatus.diskParts.find { it.mount != "/" }
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (rootDisk != null) {
                        GaugeCard(
                            title = "BỘ NHỚ (/)", value = "${rootDisk.used} / ${rootDisk.total}",
                            subValue = "${rootDisk.percent}%",
                            icon = Icons.Default.Storage,
                            gradientColors = listOf(Color(0xFF42A5F5), Color(0xFF1976D2)),
                            modifier = Modifier.weight(1f),
                            overridePercent = rootDisk.percent.toFloatOrNull()
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (hddDisk != null) {
                        GaugeCard(
                            title = "Ổ CỨNG (HDD)", value = "${hddDisk.used} / ${hddDisk.total}",
                            subValue = "${hddDisk.percent}%",
                            icon = Icons.Default.Hdd,
                            gradientColors = listOf(Color(0xFFFFA726), Color(0xFFF57C00)),
                            modifier = Modifier.weight(1f),
                            overridePercent = hddDisk.percent.toFloatOrNull()
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InlineStatRow("🌡️?", "NHIỆT HDD", viewModel.systemStatus.temp, Color(0xFFFF6B6B))
                    androidx.compose.material3.VerticalDivider(modifier = Modifier.height(8.dp), color = TextSecondary.copy(alpha = 0.12f))
                    InlineStatRow("↓", "TẢI XUỐNG", viewModel.systemStatus.netRx, Color(0xFF42A5F5))
                    androidx.compose.material3.VerticalDivider(modifier = Modifier.height(8.dp), color = TextSecondary.copy(alpha = 0.12f))
                    InlineStatRow("↑", "TẢI LÊN", viewModel.systemStatus.netTx, Color(0xFFAB47BC))
                }
            }
        }
        
        // Disk Partitions đã được gộp vào Thẻ hệ thống ở trên."""

text = re.sub(r'// Đã THẾ HỆ THỐNG: CPU \+ RAM \+ Stats Đã.*?// Disk Partitions đã được gộp vào Thẻ hệ thống ở trên\.', system_block_replacement, text, flags=re.DOTALL)

with open(kt_file, 'w', encoding='utf-8') as f:
    f.write(text)
print("SUCCESS apply all fixes")
