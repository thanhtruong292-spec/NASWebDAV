@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.R
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
import com.nas.naswebdav.ui.dialogs.*
import com.nas.naswebdav.ui.components.NasBottomSheetHandle

import android.content.Context
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import coil.compose.AsyncImage
import com.nas.naswebdav.NasFile

// ============ MainMenu sections: status cards, dialogs & bottom sheets
//              (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

data class QuickActionDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val gradientColors: List<androidx.compose.ui.graphics.Color>
)

// Gradient colors are intentional brand palette constants for quick-action tile icons.
val AVAILABLE_QUICK_ACTIONS = listOf(
    QuickActionDef("sync", "Tự Đồng Bộ", "Cấu hình sao lưu", Icons.Default.CloudSync, listOf(AccentCyan, AccentCyan)),
    QuickActionDef("stream", "Ghi Livestream", "Ghi TikTok, Facebook", Icons.Default.Videocam, listOf(AccentRed, AccentRed)),
    QuickActionDef("trash", "Thùng Rác", "Khôi phục dữ liệu", Icons.Default.Delete, listOf(AccentRed, AccentRed)),
    QuickActionDef("organizer", "Phân Loại Tệp", "AI Smart Organizer", Icons.Default.AutoAwesomeMotion, listOf(AccentBlue, AccentBlue)),
    QuickActionDef("guest", "Mạng Khách", "Cấp thẻ Wi-Fi QR", Icons.Default.Wifi, listOf(AccentPurple, AccentPurple)),
    QuickActionDef("log", "Nhật ký Lõi", "Tiến trình giám sát", Icons.Default.Assignment, listOf(AccentCyan, AccentCyan)),
    QuickActionDef("nasbackup", "Sao Lưu Cấu Hình", "Backup NAS + OneDrive", Icons.Default.SettingsBackupRestore, listOf(AccentGreen, AccentGreen)),
    QuickActionDef("smb", "Ổ đĩa LAN (SMB)", "Map Network Drive", Icons.Default.Dns, listOf(AccentOrange, AccentOrange)),
    QuickActionDef("duplicate", "Quét Trùng Lặp", "Phát hiện tệp trùng", Icons.Default.ContentCopy, listOf(AccentBlue, AccentBlue)),
    QuickActionDef("screen_record", "Quay Màn Hình", "Lưu thẳng vào NAS", Icons.Default.ScreenShare, listOf(AccentGreen, AccentCyan))
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QuickActionSelectorDialog(
    currentSlots: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = DarkSurface.copy(alpha = 0.6f),
        dragHandle = { NasBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).verticalScroll(rememberScrollState())
        ) {
            Text("TUỲ CHỌN LỐI TẮT TRUY CẬP", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 12.dp))
            AVAILABLE_QUICK_ACTIONS.forEach { action ->
                val isSelected = currentSlots.contains(action.id)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).minimumInteractiveComponentSize().clip(RoundedCornerShape(12.dp)).clickable { if (!isSelected) onSelect(action.id) },
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) AccentCyan.copy(alpha=0.15f) else DarkCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(action.gradientColors)), contentAlignment = Alignment.Center) {
                            Icon(action.icon, stringResource(R.string.cd_tools), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(action.subtitle, color = TextSecondary, fontSize = 11.sp)
                        }
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun SystemLogsSummaryCard(realtimeNow: Long = System.currentTimeMillis()) {
    val viewModel = LocalDeviceManagementVM.current
    // Kept as Unit: one-shot log load when this summary card enters composition.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadSystemLogs()
    }

    if (viewModel.systemLogsList.isEmpty()) return
    
    var isExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { isExpanded = !isExpanded }
            ) {
                Icon(Icons.Default.Assignment, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("NHẬT KÝ HỆ THỐNG", fontWeight = FontWeight.Black, color = PanelTitleCyan, fontSize = PanelTitleSize, letterSpacing = PanelTitleLetterSpacing)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Mở rộng/Thu gọn",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                PanelFreshnessTag(viewModel.lastLogsRefreshAt, realtimeNow, staleAfterMs = 30_000L)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.showLogDialog = true },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Xem tất cả", color = AccentCyan, fontSize = 12.sp)
                }
            }
            
            androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val recentLogs = viewModel.systemLogsList.take(3)
                    recentLogs.forEach { log ->
                        val logColor = when (log.type) {
                            "SUCCESS" -> MaterialTheme.colorScheme.tertiary
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARNING" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        val timeStr = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(log.timestamp)
                        
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(logColor).padding(top = 4.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(log.module, color = logColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(timeStr, color = TextSecondary, fontSize = 10.sp)
                                }
                                Text(
                                    com.nas.naswebdav.ui.dialogs.formatLogMessage(log.message),
                                    color = TextPrimary.copy(alpha=0.85f),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun MainMenuSectionsFormatElapsedTimeUI(millis: Long): String {
    if (millis <= 0) return "0 giây"
    val totalSeconds = millis / 1000
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val parts = mutableListOf<String>()
    if (days > 0) parts.add("$days ngày")
    if (hours > 0) parts.add("$hours giờ")
    if (minutes > 0) parts.add("$minutes phút")
    if (seconds > 0 || parts.isEmpty()) parts.add("$seconds giây")

    return parts.joinToString(", ")
}
