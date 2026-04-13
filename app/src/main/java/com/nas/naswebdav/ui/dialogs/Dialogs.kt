package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.screens.WebDavCachedThumbnail

import android.content.Context
import kotlinx.coroutines.delay
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog


/**
 * Tất cả Dialog composable dùng trong MainMenuScreen.
 * Tách riêng để giảm complexity và tăng readable.
 */

// ====================================================================
// DIALOG XÁC NHẬN REBOOT
// ====================================================================
@Composable
fun RebootConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RestartAlt, null, tint = Color(0xFFFB8C00), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Khởi động lại NAS", fontWeight = FontWeight.Bold)
            }
        },
        text = { Text("Bạn có chắc chắn muốn khởi động lại NAS Chainedbox? Mọi tiến trình đang chạy sẽ bị dừng lại.", fontSize = 14.sp) },
        confirmButton = {
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Button(
                onClick = onConfirm,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                modifier = Modifier.background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Khởi động lại", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy", color = Color(0xFF00897B)) } },
        shape = RoundedCornerShape(16.dp)
    )
}

// ====================================================================
// DIALOG XÁC NHẬN SHUTDOWN
// ====================================================================
@Composable
fun ShutdownConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PowerSettingsNew, null, tint = Color(0xFFE53935), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tắt nguồn NAS", fontWeight = FontWeight.Bold)
            }
        },
        text = { Text("Bạn có chắc chắn muốn tắt nguồn máy chủ không? Bạn phải dùng Wake-on-LAN để bật lại máy từ xa.", fontSize = 14.sp) },
        confirmButton = {
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Button(
                onClick = onConfirm,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                modifier = Modifier.background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Tắt nguồn", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy", color = Color(0xFF00897B)) } },
        shape = RoundedCornerShape(16.dp)
    )
}

// ====================================================================
// DIALOG TẢI XUỐNG TỪ XA
// ====================================================================
@Composable
fun DownloadDialog(
    downloadLink: String,
    onLinkChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tải xuống qua qBittorrent", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Nhập Magnet Link hoặc HTTP URL để NAS tự động tải ngầm qua qBittorrent.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = downloadLink,
                    onValueChange = onLinkChange,
                    placeholder = { Text("https://... hoặc magnet:?...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Thêm vào hàng đợi") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

// ====================================================================
// DIALOG WAKE-ON-LAN
// ====================================================================
@Composable
fun WolDialog(
    macAddress: String,
    onMacChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wake-on-LAN", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Nhập địa chỉ MAC của cổng mạng NAS (VD: 00:1A:2B:3C:4D:5E). Ứng dụng sẽ lưu lại cho các lần sau và bắn tín hiệu đánh thức qua mạng LAN.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = macAddress,
                    onValueChange = onMacChange,
                    placeholder = { Text("VD: AA:BB:CC:DD:EE:FF") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Đánh thức NAS") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

// ====================================================================
// DIALOG S.M.A.R.T VÀ TEST TỐC ĐỘ Ổ CỨNG
// ====================================================================
@Composable
fun SmartDiskDialog(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HealthAndSafety, null, tint = Color(0xFF43A047), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chẩn đoán Ổ cứng", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Thông tin S.M.A.R.T
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Text("Trạng thái S.M.A.R.T", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Trạng thái:", fontSize = 13.sp)
                            Text(viewModel.smartInfo.status, color = if (viewModel.smartInfo.status == "PASSED") Color(0xFF43A047) else Color.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Nhiệt độ ổ cứng:", fontSize = 13.sp)
                            Text(viewModel.smartInfo.temperature, color = Color(0xFFFB8C00), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                // Test tốc độ Read/Write
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                        Text("Kiểm tra tốc độ đọc/ghi", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Ghi (Write):", fontSize = 13.sp)
                            Text(viewModel.speedTestResult.writeSpeed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF8E24AA))
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Đọc (Read):", fontSize = 13.sp)
                            Text(viewModel.speedTestResult.readSpeed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1E88E5))
                        }

                        if (viewModel.lastAutoSpeedTime.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = viewModel.lastAutoSpeedTime,
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.runSpeedTest() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = if (viewModel.isTestingSpeed) listOf(Color.Gray, Color.LightGray) else listOf(Color(0xFF00897B), Color(0xFF26A69A))
                                    ),
                                    shape = RoundedCornerShape(24.dp)
                                ),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            interactionSource = interactionSource,
                            enabled = !viewModel.isTestingSpeed
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (viewModel.isTestingSpeed) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (viewModel.isTestingSpeed) "Đang kiểm tra..." else "Bắt đầu kiểm tra", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng", color = Color(0xFF00897B))
            }
        }
    )
}

// ====================================================================
// DIALOG CẤU HÌNH AUTO-BACKUP
// ====================================================================
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AutoBackupDialog(
    context: Context,
    isAutoBackupEnabled: Boolean,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    deleteAfterBackup: Boolean,
    onDeleteAfterBackupChange: (Boolean) -> Unit,
    onSaveAndSchedule: () -> Unit,
    onTriggerManualSync: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161616),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Icon(Icons.Default.Sync, null, tint = Color(0xFF43A047), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sao lưu tự động", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 20.sp)
            }

            Text("Tự động sao lưu ảnh lên NAS mỗi khi cắm sạc và có kết nối Wi-Fi.", fontSize = 13.sp, color = Color.LightGray)
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onAutoBackupEnabledChange(!isAutoBackupEnabled) }.padding(vertical = 4.dp)) {
                Switch(
                    checked = isAutoBackupEnabled,
                    onCheckedChange = onAutoBackupEnabledChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00897B), checkedTrackColor = Color(0xFF80CBC4), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.DarkGray)
                )
                Spacer(Modifier.width(12.dp))
                Text(if (isAutoBackupEnabled) "Đã bật" else "Đã tắt", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isAutoBackupEnabled) Color(0xFF00897B) else Color.Gray)
            }
            
            Spacer(Modifier.height(8.dp))
            
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFF00897B).copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, Color(0xFF00897B).copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF4DB6AC), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Các tệp sẽ được lưu và giữ nguyên cấu trúc thư mục của máy vào trong thư mục /AutoBackup/ trên NAS.",
                        fontSize = 11.sp, color = Color(0xFFB2DFDB), lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.DarkGray)
            Spacer(Modifier.height(16.dp))

            Text("Chế độ sao lưu:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDeleteAfterBackupChange(false) }.padding(vertical = 4.dp)) {
                RadioButton(
                    selected = !deleteAfterBackup,
                    onClick = { onDeleteAfterBackupChange(false) },
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF43A047), unselectedColor = Color.Gray)
                )
                Column {
                    Text("Chỉ Sao lưu (Copy)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (!deleteAfterBackup) Color(0xFF43A047) else Color.LightGray)
                    Text("Giữ lại ảnh gốc trên điện thoại.", fontSize = 12.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDeleteAfterBackupChange(true) }.padding(vertical = 4.dp)) {
                RadioButton(
                    selected = deleteAfterBackup,
                    onClick = { onDeleteAfterBackupChange(true) },
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFE53935), unselectedColor = Color.Gray)
                )
                Column {
                    Text("Sao lưu & Giải phóng (Move)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (deleteAfterBackup) Color(0xFFE53935) else Color.LightGray)
                    Text("Tự động xóa ảnh trên điện thoại sau khi lên NAS.", fontSize = 12.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(24.dp))
            
            // Buttons Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTriggerManualSync,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ĐỒNG BỘ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                
                Button(
                    onClick = { onSaveAndSchedule(); onDismiss() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("LƯU", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

fun formatLogMessage(raw: String): String {
    if (raw.trim().startsWith("{")) {
        try {
            val j = org.json.JSONObject(raw)
            when (j.optString("event")) {
                "WEBDAV_SUCCESS" -> return "${j.optString("device")} (IP: ${j.optString("ip")} - MAC: ${j.optString("mac")}) đã kết nối NAS."
                "SSH_FAIL" -> return "Cảnh báo: IP ${j.optString("ip")} đang phản hồi sai mật khẩu SSH khi cố đăng nhập user: ${j.optString("user")}!"
                "SSH_SUCCESS" -> return "Đã đăng nhập SSH thành công từ IP ${j.optString("ip")} (Tài khoản: ${j.optString("user")}, Phương thức: ${j.optString("method")})."
                "CPU_TEMP_WARN" -> return "Nhiệt độ CPU hiện tại đang vượt ngưỡng an toàn! Vui lòng kiểm tra tản nhiệt."
                "SMART_WARN" -> return "Phát hiện lỗi phần cứng trên phân vùng ${j.optString("device")}: ${j.optString("error")}. Đề xuất sao lưu dữ liệu ngay lập tức!"
                else -> return raw
            }
        } catch (e: Exception) {
            return raw
        }
    }
    return raw
}

// ====================================================================
// DIALOG NHẬT KÝ HỆ THỐNG
// ====================================================================
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SystemLogDialog(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(max = 600.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Icon(Icons.Default.Assignment, null, tint = Color(0xFF00ACC1), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nhật ký hệ thống", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                if (viewModel.systemLogsList.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearSystemLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color(0xFFE53935))
                    }
                }
            }
            
            if (viewModel.systemLogsList.isEmpty()) {
                Text("Chưa có dữ liệu nhật ký nào.", modifier = Modifier.padding(vertical = 16.dp), color = Color.Gray)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = viewModel.systemLogsList, key = { it.id }) { log ->
                        val logColor = when (log.type) {
                            "SUCCESS" -> Color(0xFF43A047)
                            "ERROR" -> Color(0xFFE53935)
                            "WARNING" -> Color(0xFFFB8C00)
                            else -> Color(0xFF1E88E5)
                        }
                        val logIcon = when (log.type) {
                            "SUCCESS" -> Icons.Default.CheckCircle
                            "ERROR" -> Icons.Default.Error
                            "WARNING" -> Icons.Default.Warning
                            else -> Icons.Default.Info
                        }
                        val timeStr = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(log.timestamp)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Icon(logIcon, null, tint = logColor, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(log.module, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = logColor)
                                        Text(timeStr, fontSize = 10.sp, color = Color.Gray)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(formatLogMessage(log.message), fontSize = 12.sp, color = Color.White.copy(alpha=0.85f))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ====================================================================
// DIALOG QUẢN LÝ DOCKER
// ====================================================================
@Composable
fun DockerDialog(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewInAr, null, tint = Color(0xFF1E88E5), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Quản lý Docker", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (viewModel.isFetchingDocker) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF1E88E5), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.fetchDockerContainers() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Refresh, "Làm mới", tint = Color.Gray)
                    }
                }
            }
        },
        text = {
            if (viewModel.dockerContainers.isEmpty() && !viewModel.isFetchingDocker) {
                Text("Không tìm thấy Container nào đang tồn tại.", modifier = Modifier.padding(16.dp), color = Color.Gray)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = viewModel.dockerContainers, key = { it.id }) { container ->
                        val isRunning = container.status.lowercase() == "running"
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isRunning) Color(0xFF43A047) else Color(0xFFE53935))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(container.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(if (isRunning) "Đang chạy" else "Đã dừng", fontSize = 11.sp, color = if (isRunning) Color(0xFF43A047) else Color.Gray)
                                }

                                if (isRunning) {
                                    IconButton(onClick = { viewModel.controlDockerContainer("restart", container.name) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.RestartAlt, "Khởi động lại", tint = Color(0xFFFB8C00), modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { viewModel.controlDockerContainer("stop", container.name) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Stop, "Dừng", tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                                    }
                                } else {
                                    IconButton(onClick = { viewModel.controlDockerContainer("start", container.name) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.PlayArrow, "Bật", tint = Color(0xFF43A047), modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng", color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    viewModel: WebDavViewModel,
    startingUrl: String,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    var currentUrl by remember { mutableStateOf(startingUrl) }
    var folderList by remember { mutableStateOf<List<NasFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(currentUrl) {
        isLoading = true
        try {
            val items = viewModel.webDavManager.listFiles(currentUrl)
            folderList = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            folderList = emptyList()
        }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Chọn thư mục đích", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val decoded = try { java.net.URLDecoder.decode(currentUrl, "UTF-8") } catch (_: Exception) { currentUrl }
                val relativePath = decoded.removePrefix(viewModel.webDavManager.currentBaseUrl)
                Text(
                    text = if (relativePath.isEmpty()) "/ (Thư mục gốc)" else relativePath,
                    fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (currentUrl.trimEnd('/') != viewModel.webDavManager.currentBaseUrl.trimEnd('/')) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val parentUrl = currentUrl.trimEnd('/').substringBeforeLast('/') + "/"
                                            currentUrl = if (parentUrl.length < viewModel.webDavManager.currentBaseUrl.length)
                                                viewModel.webDavManager.currentBaseUrl else parentUrl
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(16.dp))
                                    Text(".. (Quay lại)", fontWeight = FontWeight.Medium)
                                }
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                            }
                        }

                        if (folderList.isEmpty()) {
                            item {
                                Text(
                                    text = "(Thư mục trống)",
                                    color = Color.Gray,
                                    modifier = Modifier.padding(16.dp).fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                                )
                            }
                        } else {
                            lazyItems(folderList) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentUrl = folder.path }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = "Thư mục", tint = Color(0xFFFFCA28))
                                    Spacer(Modifier.width(16.dp))
                                    Text(folder.name, fontWeight = FontWeight.Medium)
                                }
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onFolderSelected(currentUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
            ) {
                Text("Chép/Di chuyển vào đây", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy", color = Color.Gray)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// ════════════════════════════════════════════════════════════════════════════
// AppStatusDialog + DialogType enum (từ ui.components.AppStatusDialog)
// ════════════════════════════════════════════════════════════════════════════

enum class DialogType { SUCCESS, ERROR, WARNING, CONFIRM }

@Composable
fun AppStatusDialog(type: DialogType, message: String, onConfirm: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val (icon, color, title) = when (type) {
        DialogType.SUCCESS -> Triple(Icons.Default.Check, Color(0xFF4CAF50), "Thành công")
        DialogType.ERROR -> Triple(Icons.Default.Close, Color(0xFFE53935), "Thất bại")
        DialogType.WARNING -> Triple(Icons.Default.Warning, Color(0xFFFFA726), "Cảnh báo")
        DialogType.CONFIRM -> Triple(Icons.Default.HelpOutline, Color(0xFF2196F3), "Xác nhận")
    }
    val isDark = isSystemInDarkTheme()
    val dialogBg = if (isDark) Color(0xFF263238) else Color.White
    val textColor = if (isDark) Color.White else Color(0xFF546E7A)
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.fillMaxWidth().background(dialogBg, shape = RoundedCornerShape(24.dp)).border(1.dp, Color(0xFFEEEEEE).copy(alpha = 0.3f), RoundedCornerShape(24.dp)).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(72.dp).background(color.copy(0.1f), CircleShape).border(2.dp, color.copy(0.2f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(36.dp))
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = message, fontSize = 16.sp, color = textColor, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(28.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) { Text("Đóng", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    if (onConfirm != null) { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) { Text("Xác nhận", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) } }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SharedComponents — BiometricLockScreen + NotificationDialog
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun BiometricLockScreen(activity: androidx.fragment.app.FragmentActivity, onAuthenticated: () -> Unit, onFallbackToLogin: () -> Unit) {
    val executor = remember { androidx.core.content.ContextCompat.getMainExecutor(activity) }
    var authError by remember { mutableStateOf("") }
    var failCount by remember { mutableStateOf(0) }
    val authenticate = {
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Khóa bảo mật NAS").setSubtitle("Vui lòng xác thực vân tay/khuôn mặt để truy cập dữ liệu")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
        val biometricPrompt = androidx.biometric.BiometricPrompt(activity, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { super.onAuthenticationSucceeded(result); failCount = 0; onAuthenticated() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED || errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) onFallbackToLogin()
                    else { failCount++; authError = "Lỗi: $errString (Sai $failCount/3 lần)"; if (failCount >= 3) onFallbackToLogin() }
                }
                override fun onAuthenticationFailed() { super.onAuthenticationFailed(); failCount++; authError = "Vân tay không khớp! (Sai $failCount/3 lần)"; if (failCount >= 3) onFallbackToLogin() }
            })
        biometricPrompt.authenticate(promptInfo)
    }
    var hasStartedAuth by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!hasStartedAuth) { hasStartedAuth = true; authenticate() } }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, contentDescription = "Lock", modifier = Modifier.size(64.dp), tint = Color(0xFF00897B))
            Spacer(Modifier.height(16.dp))
            Text("Ứng dụng đang khóa", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (authError.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(authError, color = Color.Red, fontSize = 14.sp) }
            Spacer(Modifier.height(24.dp))
            Button(onClick = authenticate, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))) { Text("Chạm để mở khóa", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun NotificationDialog(title: String, message: String, icon: ImageVector, iconColor: Color, onDismiss: () -> Unit) {
    LaunchedEffect(key1 = title, key2 = message) { kotlinx.coroutines.delay(3000); onDismiss() }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Đã hiểu", fontWeight = FontWeight.Bold) } },
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(title, fontWeight = FontWeight.Bold) } },
        text = { Text(message, fontSize = 14.sp) }, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.primary, textContentColor = MaterialTheme.colorScheme.onSurface)
}

// ════════════════════════════════════════════════════════════════════════════
// IpApprovalDialog — Cảnh báo bảo mật IP lạ
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun IpApprovalDialog(viewModel: WebDavViewModel, onDismiss: () -> Unit) {
    val ip = viewModel.pendingIpAddress; val message = viewModel.approvalMessage; val countryCode = viewModel.pendingCountryCode
    val infiniteTransition = rememberInfiniteTransition(label = "shield_pulse")
    val pulseScale by infiniteTransition.animateFloat(1f, 1.15f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(0.7f, 1f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "alpha")
    val isLocal = ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
    val riskColor = if (isLocal) Color(0xFFFB8C00) else Color(0xFFE53935)
    val riskLabel = if (isLocal) "Mạng nội bộ" else "IP ngoài ($countryCode)"
    AlertDialog(onDismissRequest = {}, containerColor = Color(0xFF1A1A2E), shape = RoundedCornerShape(24.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(64.dp).scale(pulseScale).clip(CircleShape).background(Brush.radialGradient(listOf(riskColor.copy(alpha = pulseAlpha * 0.3f), riskColor.copy(alpha = 0.05f)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, null, tint = riskColor.copy(alpha = pulseAlpha), modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("⚠️ CẢNH BÁO BẢO MẬT", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = riskColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Phát hiện thiết bị lạ kết nối", fontSize = 13.sp, color = Color(0xFF8892B0), textAlign = TextAlign.Center)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ĐỊA CHỈ IP", fontSize = 10.sp, color = Color(0xFF8892B0), fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(ip.ifEmpty { "Không xác định" }, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFE8E8E8), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Surface(color = riskColor.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) { Text(riskLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = riskColor) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (message.isNotBlank()) { Text(message, fontSize = 13.sp, color = Color(0xFFB0BEC5), textAlign = TextAlign.Center, lineHeight = 18.sp); Spacer(Modifier.height(12.dp)) }
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F3460).copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Cho phép: Thêm vào whitelist, cho truy cập NAS", fontSize = 11.sp, color = Color(0xFFB0BEC5)) }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = Color(0xFFE53935), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Chặn: Ban IP vĩnh viễn bằng iptables", fontSize = 11.sp, color = Color(0xFFB0BEC5)) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.approveDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cho phép", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            Button(onClick = { viewModel.denyDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFFC62828))), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Chặn IP", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
// LanWhitelistDialog — Quản lý danh sách IP/Subnet được truy cập nội bộ
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LanWhitelistDialog(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    val ipList = viewModel.lanWhitelistIps
    val subnetList = viewModel.lanWhitelistSubnets
    val isLoading = viewModel.lanWhitelistLoading
    val errorMessage = viewModel.lanWhitelistError
    val statusMessage = viewModel.lanWhitelistStatus

    var newEntry by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.loadLanWhitelist() }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(max = 600.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Icon(Icons.Default.Wifi, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("LAN Whitelist", color = Color(0xFFE8E8E8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("IP truy cập không cần Tailscale", color = Color(0xFF8892B0), fontSize = 11.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("192.168.1.0/24", color = Color(0xFF8892B0), fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color(0xFFE8E8E8)),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF66BB6A),
                        unfocusedBorderColor = Color(0xFF8892B0).copy(alpha = 0.3f),
                        cursorColor = Color(0xFF66BB6A),
                        focusedTextColor = Color(0xFFE8E8E8),
                        unfocusedTextColor = Color(0xFFE8E8E8)
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newEntry.isNotBlank()) {
                            viewModel.addLanWhitelistEntry(newEntry.trim())
                            newEntry = ""
                        }
                    },
                    modifier = Modifier.size(48.dp).background(Color(0xFF66BB6A).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) { Icon(Icons.Default.Add, null, tint = Color(0xFF66BB6A)) }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(statusMessage, fontSize = 12.sp, color = if (statusMessage.startsWith("✅")) Color(0xFF66BB6A) else if (statusMessage.startsWith("❌")) Color(0xFFFF1744) else Color(0xFF8892B0))
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF66BB6A), modifier = Modifier.size(28.dp))
                }
            } else if (errorMessage.isNotBlank()) {
                Text(errorMessage, color = Color(0xFFFF1744), fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            } else if (subnetList.isEmpty() && ipList.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                    Text("Chưa có IP/subnet nào. Thêm để cho phép truy cập LAN.", color = Color(0xFF8892B0), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            } else {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (subnetList.isNotEmpty()) {
                        Text("SUBNET", fontSize = 11.sp, color = Color(0xFF8892B0), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        subnetList.forEach { subnet ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Hub, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(subnet, color = Color(0xFFE8E8E8), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { viewModel.removeLanWhitelistEntry(subnet, true) },
                                    modifier = Modifier.size(28.dp)
                                ) { Icon(Icons.Default.Close, null, tint = Color(0xFFFF1744).copy(alpha = 0.7f), modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                    if (ipList.isNotEmpty()) {
                        if (subnetList.isNotEmpty()) Spacer(Modifier.height(12.dp))
                        Text("IP", fontSize = 11.sp, color = Color(0xFF8892B0), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        ipList.forEach { ip ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Computer, null, tint = Color(0xFF00D2FF), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(ip, color = Color(0xFFE8E8E8), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { viewModel.removeLanWhitelistEntry(ip, false) },
                                    modifier = Modifier.size(28.dp)
                                ) { Icon(Icons.Default.Close, null, tint = Color(0xFFFF1744).copy(alpha = 0.7f), modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ĐÓNG", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// (Đã xoá SmartSyncDialog theo yêu cầu)
@Composable
fun OrganizeLegacyDialog(viewModel: WebDavViewModel, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!viewModel.organizingLegacyRunning) onDismiss() },
        title = { Text("Phân loại video cũ") },
        text = {
            Column {
                if (viewModel.organizingLegacyRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Color(0xFF00897B), trackColor = Color.Transparent)
                    Text("Đang ra lệnh cho NAS dọn dẹp nội bộ...")
                } else if (viewModel.organizingLegacyResult != null) {
                    Text(viewModel.organizingLegacyResult!!)
                } else {
                    Text("Bạn có chắc chắn muốn NAS quét và di chuyển toàn bộ video KHÔNG PHẢI MP4 (như mpg, flv, mkv, avi...) vào thư mục 'Other Video' không? Tránh việc hiển thị lẫn lộn. Thao tác phân loại này diễn ra nhanh chóng trên thiết bị NAS.")
                }
            }
        },
        confirmButton = {
            if (!viewModel.organizingLegacyRunning && viewModel.organizingLegacyResult == null) {
                TextButton(onClick = { viewModel.organizeLegacyVideos() }) { Text("Chạy NAS") }
            } else if (viewModel.organizingLegacyResult != null) {
                TextButton(onClick = { viewModel.resetOrganizingLegacy(); onDismiss() }) { Text("Đóng") }
            }
        },
        dismissButton = {
            if (!viewModel.organizingLegacyRunning && viewModel.organizingLegacyResult == null) {
                TextButton(onClick = onDismiss) { Text("Hủy") }
            }
        }
    )
}

@Composable
fun DuplicateConfigDialog(
    viewModel: WebDavViewModel,
    context: android.content.Context,
    onStartScan: (Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var isLightningMode by remember { mutableStateOf(true) }
    var isForceRestartDuplicate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Bolt, null, tint = Color(0xFFFFC107), modifier = Modifier.size(36.dp)) },
        title = { Text("Cấu hình quét trùng lặp", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Thiết lập hệ thống kiểm tra hàng nghìn tệp trên Server NAS.", fontSize = 13.sp, color = Color.Gray)
                
                // Option 1: Lightning Mode
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isLightningMode = !isLightningMode }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isLightningMode,
                        onCheckedChange = { isLightningMode = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFC107))
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("⚡ Chế độ nhanh (Khuyến nghị)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isLightningMode) Color(0xFFFFC107) else Color.White)
                        Text("Nhanh gấp 100 lần. Bỏ qua phân tích mạng nội dung, chỉ dùng ETag gốc (Dung lượng, Tên, Ngày sửa). Quét nháy mắt 500,000 files.", fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
                    }
                }

                // Option 2: Force Restart
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isForceRestartDuplicate = !isForceRestartDuplicate }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isForceRestartDuplicate, onCheckedChange = { isForceRestartDuplicate = it })
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("Quét lại từ đầu", fontSize = 14.sp, color = Color.White)
                        Text("Thực hiện quét lại toàn bộ ổ cứng NAS, bỏ qua lịch sử lưu tạm.", fontSize = 11.sp, color = Color.Gray)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(Modifier.height(8.dp))

                // Option 3: Tự động chạy ngầm (Auto Clean)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("🤖 Tự động dọn dẽp (hàng tuần)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                        Text("Chạy ngầm 7 ngày/lần khi điện thoại đang sạc Pin & có Wifi. Tự động chuyển file trùng (giữ lại file có đường dẫn ngắn nhất) vào thùng rác (.trash).", fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
                    }
                    Switch(
                        checked = viewModel.autoCleanEnabled,
                        onCheckedChange = { viewModel.toggleAutoClean(context, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4FC3F7), checkedTrackColor = Color(0xFF4FC3F7).copy(alpha = 0.5f))
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStartScan(isForceRestartDuplicate, isLightningMode)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("🚀 Bắt đầu quét")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy", color = Color.Gray) }
        }
    )
}
@Composable
fun DuplicateFilesDialog(viewModel: WebDavViewModel, onDismiss: () -> Unit) {
    var selectedFilter by remember { mutableStateOf("all") }
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Column {
                Text("Tệp trùng lặp", style = MaterialTheme.typography.titleMedium, color = Color.Red)
                // BỔ SUNG: Hiển thị tổng số file rác phát hiện được nếu danh sách không trống
                if (viewModel.duplicateFilesList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Phát hiện ${viewModel.duplicateFilesList.size} tệp trùng lặp",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            if (viewModel.duplicateFilesList.isEmpty()) {
                Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = Color.Green)
            } else {
                // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                // SỬA LỖI: Nhóm theo Hash/Fingerprint thay vì chỉ theo Size để đảm bảo tuyệt đối file có nội dung giống nhau mới nằm chung nhóm
                val groupedDuplicates = remember(viewModel.duplicateFilesList) {
                    viewModel.duplicateFilesList.groupBy { it.contentLength }.values.filter { it.size >= 2 }.toList()
                }

                // ═══ BỘ LỌC NHANH ═══
                var selectedFilter by remember { mutableStateOf("all") } // all, image, video, doc
                val filteredGroups = remember(groupedDuplicates, selectedFilter) {
                    when (selectedFilter) {
                        "image" -> groupedDuplicates.filter { group ->
                            group.any { it.name.lowercase().run { endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp") || endsWith(".heic") || endsWith(".gif") || endsWith(".bmp") } }
                        }
                        "video" -> groupedDuplicates.filter { group ->
                            group.any { com.nas.naswebdav.utils.MediaUtils.isVideo(it.name) }
                        }
                        "doc" -> groupedDuplicates.filter { group ->
                            group.any { f -> val n = f.name.lowercase(); !n.run { endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp") || endsWith(".heic") || endsWith(".gif") || endsWith(".bmp") } && !com.nas.naswebdav.utils.MediaUtils.isVideo(f.name) }
                        }
                        else -> groupedDuplicates
                    }
                }

                Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                    // ═══ FILTER CHIP ROW ═══
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        data class FilterOption(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                        val filters = listOf(
                            FilterOption("all", "Tất cả (${groupedDuplicates.size})", Icons.Default.SelectAll),
                            FilterOption("image", "🖼 Ảnh", Icons.Default.Image),
                            FilterOption("video", "🎬 Video", Icons.Default.PlayCircle),
                            FilterOption("doc", "📄 Khác", Icons.Default.InsertDriveFile)
                        )
                        filters.forEach { opt ->
                            androidx.compose.material3.FilterChip(
                                selected = selectedFilter == opt.key,
                                onClick = { selectedFilter = opt.key },
                                label = { Text(opt.label, fontSize = 10.sp, maxLines = 1) },
                                leadingIcon = if (selectedFilter == opt.key) {{ Icon(Icons.Default.Done, null, Modifier.size(14.dp)) }} else null,
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }

                    // Nút Tự động chọn thông minh (Giữ lại 1 bản, tick chọn xóa các bản copy)
                    TextButton(
                        onClick = {
                            viewModel.selectedDuplicates.clear()
                            groupedDuplicates.forEach { group ->
                                // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                viewModel.selectedDuplicates.addAll(filesToDelete)
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF2196F3))
                        Spacer(Modifier.width(4.dp))
                        Text("Chọn thông minh", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                    }

                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                        items(items = filteredGroups, key = { it.first().contentLength }) { group ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Nhóm ${group.size} tệp trùng lặp (${group.first().contentLength / 1024} KB)",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )

                                    // Hiển thị danh sách file trong nhóm bằng Cuộn Ngang (LazyRow)
                                    androidx.compose.foundation.lazy.LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(items = group, key = { it.path }) { dupFile ->
                                            val isSelected = viewModel.selectedDuplicates.contains(dupFile)
                                            val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                            val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(dupFile.name)
                                            val auth = remember { okhttp3.Credentials.basic(viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) }

                                            Box(
                                                modifier = Modifier
                                                    .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) Color.Red.copy(alpha = 0.2f) else Color.Black)
                                                    .clickable {
                                                        if (isSelected) viewModel.selectedDuplicates.remove(dupFile)
                                                        else viewModel.selectedDuplicates.add(dupFile)
                                                    }
                                            ) {
                                                // 1. Lớp Ảnh Nền (TỐI ƯU HÓA DB CACHE MỚI CHO TẤT CẢ MEDIA)
                                                if (isImage || isVideo) {
                                                    Box(modifier = Modifier.fillMaxSize()) {
                                                        WebDavCachedThumbnail(url = dupFile.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize())
                                                    }
                                                } else {
                                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                                }

                                                // 2. Lớp phủ đỏ mờ nếu đang được tick chọn xóa
                                                if (isSelected) {
                                                    Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.4f)))
                                                }

                                                // 3. Checkbox nằm góc trên phải
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = {
                                                        if (it) viewModel.selectedDuplicates.add(dupFile)
                                                        else viewModel.selectedDuplicates.remove(dupFile)
                                                    },
                                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                    colors = CheckboxDefaults.colors(checkedColor = Color.Red, uncheckedColor = Color.White)
                                                )

                                                // 4. Tên file + thư mục cha đè ở dưới cùng (Để phân biệt các file)
                                                Column(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .fillMaxWidth()
                                                        .background(Color.Black.copy(alpha = 0.75f))
                                                        .padding(horizontal = 4.dp, vertical = 3.dp)
                                                ) {
                                                    Text(
                                                        text = dupFile.name,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        lineHeight = 10.sp
                                                    )
                                                    val parentFolder = dupFile.path.substringBeforeLast("/").substringAfterLast("/")
                                                    Text(
                                                        text = "📁 $parentFolder",
                                                        fontSize = 7.sp,
                                                        color = Color.Gray,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // Hiển thị nút Xóa hàng loạt màu đỏ nổi bật nếu có file đang được tick
                if (viewModel.selectedDuplicates.isNotEmpty()) {
                    TextButton(onClick = { viewModel.deleteSelectedDuplicates() }) {
                        Text("Xóa (${viewModel.selectedDuplicates.size}) mục", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = {
                    onDismiss()
                    viewModel.selectedDuplicates.clear() // Xóa danh sách tick chọn tạm thời khi đóng hộp thoại
                }) { Text("Đóng") }
            }
        }
    )
}



// ====================================================================
// DIALOG TẠO THƯ MỤC MỚI
// ====================================================================
@Composable
fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thư mục mới") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Nhập tên thư mục") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(folderName) }) { Text("Tạo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

// ====================================================================
// DIALOG XÓA NHIỀU TỆP CÙNG LÚC
// ====================================================================
@Composable
fun MultiDeleteDialog(
    selectedCount: Int,
    isTrash: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppStatusDialog(
        type = DialogType.WARNING,
        message = if (isTrash) "Bạn có chắc chắn muốn xóa vĩnh viễn $selectedCount tệp này không? Hành động này không thể hoàn tác." else "Bạn có chắc chắn muốn đưa $selectedCount tệp này vào Thùng rác?",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

// ════════════════════════════════════════════════════════════════════════════
// LivestreamRecordDialog — Ghi hinh Livestream TikTok / Facebook / YouTube
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LivestreamRecordDialog(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Khôi phục trạng thái nếu Worker đang chạy ngầm
    LaunchedEffect(Unit) { viewModel.restoreLivestreamStateIfRunning(context) }
    var liveUrl by remember { mutableStateOf("") }
    var selectedQuality by remember { mutableStateOf("best") }
    val activeLivestreams = viewModel.activeLivestreams
    val message = viewModel.livestreamMessage

    val detectedPlatform = remember(liveUrl) {
        when {
            liveUrl.contains("tiktok", true) -> "tiktok"
            liveUrl.contains("facebook", true) || liveUrl.contains("fb.watch", true) -> "facebook"
            liveUrl.contains("youtube", true) || liveUrl.contains("youtu.be", true) -> "youtube"
            liveUrl.contains("shopee", true) -> "shopee"
            else -> ""
        }
    }
    val activePlatform = detectedPlatform.ifEmpty { "livestream" }
    val platformIcon = when (activePlatform) { "tiktok" -> "🎵"; "facebook" -> "📘"; "youtube" -> "▶️"; "shopee" -> "🛒"; else -> "📹" }
    val platformName = when (activePlatform) { "tiktok" -> "TikTok"; "facebook" -> "Facebook"; "youtube" -> "YouTube"; "shopee" -> "Shopee"; else -> "Livestream" }
    val accentColor = when (activePlatform) { "tiktok" -> Color(0xFFEE1D52); "facebook" -> Color(0xFF1877F2); "youtube" -> Color(0xFFFF0000); else -> Color(0xFFFF6B35) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (activeLivestreams.isEmpty()) onDismiss() },
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(max = 600.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(platformIcon, fontSize = 24.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ghi hình Livestream", color = Color(0xFFE8E8E8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Ghi trực tiếp vào NAS HDD", color = Color(0xFF8892B0), fontSize = 12.sp)
                }
                if (activeLivestreams.isNotEmpty()) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Thu nhỏ", tint = Color.Gray)
                    }
                }
            }

            // --- PHẦN 1: FORM TẠO JOB MỚI ---
            OutlinedTextField(
                value = liveUrl,
                onValueChange = { liveUrl = it },
                label = { Text("Dán link livestream", color = Color(0xFF8892B0)) },
                placeholder = { Text("https://www.tiktok.com/@user/live", color = Color(0xFF8892B0).copy(alpha = 0.5f), fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = Color(0xFF8892B0).copy(alpha = 0.3f),
                    cursorColor = accentColor,
                    focusedTextColor = Color(0xFFE8E8E8),
                    unfocusedTextColor = Color(0xFFE8E8E8)
                ),
                trailingIcon = { if (detectedPlatform.isNotEmpty()) { Text(platformIcon, fontSize = 18.sp) } }
            )
            
            Spacer(Modifier.height(12.dp))
            
            if (detectedPlatform.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(platformIcon, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Đã nhận diện: $platformName Live", color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
            }
            
            Text("CHẤT LƯỢNG", color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("best" to "Tốt nhất", "720p" to "720p", "audio" to "Chỉ âm thanh").forEach { (value, label) ->
                    val selected = selectedQuality == value
                    FilterChip(
                        selected = selected,
                        onClick = { selectedQuality = value },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.2f),
                            selectedLabelColor = accentColor,
                            containerColor = Color.Transparent,
                            labelColor = Color(0xFF8892B0)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color(0xFF8892B0).copy(alpha = 0.2f),
                            selectedBorderColor = accentColor.copy(alpha = 0.5f),
                            enabled = true,
                            selected = selected
                        ),
                        modifier = Modifier.height(36.dp)
                    )
                }
            }
            
            if (viewModel.isStartingLivestream) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = accentColor, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(viewModel.livestreamMessage.ifEmpty { "Đang kết nối luồng Live..." }, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (message.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val msgColor = if (message.startsWith("Lỗi")) Color.Red else Color(0xFF8892B0)
                Text(message, color = msgColor, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { if (liveUrl.isNotBlank() && !viewModel.isStartingLivestream) { viewModel.startLivestreamRecord(context, liveUrl.trim(), selectedQuality) } },
                    enabled = liveUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("BẮT ĐẦU GHI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- PHẦN 2: DANH SÁCH CÁC JOB ĐANG GHI ---
            if (activeLivestreams.isNotEmpty()) {
                HorizontalDivider(color = Color.DarkGray)
                Spacer(Modifier.height(12.dp))
                Text("ĐANG GHI HÌNH (${activeLivestreams.size})", color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(12.dp))
                
                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(activeLivestreams.size) { index ->
                        val job = activeLivestreams[index]
                        val jobPlatform = job.platform.ifEmpty { "livestream" }
                        val jobPlatformName = when (jobPlatform) { "tiktok" -> "TikTok"; "facebook" -> "Facebook"; "youtube" -> "YouTube"; "shopee" -> "Shopee"; else -> "Livestream" }
                        val jobAccentColor = when (jobPlatform) { "tiktok" -> Color(0xFFEE1D52); "facebook" -> Color(0xFF1877F2); "youtube" -> Color(0xFFFF0000); else -> Color(0xFFFF6B35) }

                        Column(
                            Modifier.fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(jobAccentColor.copy(alpha = 0.12f), Color.Transparent)), RoundedCornerShape(14.dp))
                                .border(1.dp, jobAccentColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val pulse = rememberInfiniteTransition(label = "pulse")
                                val alpha by pulse.animateFloat(initialValue = 1f, targetValue = 0.3f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseAlpha")
                                Box(Modifier.size(10.dp).background(Color.Red.copy(alpha = alpha), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("GHI HÌNH", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Spacer(Modifier.weight(1f))
                                Text(jobPlatformName, color = jobAccentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("Thời gian chạy", color = Color(0xFF8892B0), fontSize = 10.sp)
                                    var localSeconds by remember(job.jobId) { mutableStateOf(job.durationSeconds) }
                                    LaunchedEffect(job.jobId, job.durationSeconds) {
                                        localSeconds = job.durationSeconds
                                        while(true) { delay(1000); localSeconds++ }
                                    }
                                    val displayDur = "${localSeconds / 3600}h${String.format("%02d", (localSeconds % 3600) / 60)}m${String.format("%02d", localSeconds % 60)}s"
                                    Text(displayDur, color = Color(0xFFE8E8E8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Dung lượng (tạm tính)", color = Color(0xFF8892B0), fontSize = 10.sp)
                                    Text(job.fileSize.ifEmpty { "0 B" }, color = Color(0xFFE8E8E8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (job.speed.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Tốc độ: ${job.speed}", color = Color(0xFF8892B0), fontSize = 12.sp) }
                            if (job.outputFile.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(job.outputFile, color = Color(0xFF8892B0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.stopLivestreamRecord(context, job.jobId) },
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Stop, null, tint = Color.Red, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("DỪNG GHI", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
