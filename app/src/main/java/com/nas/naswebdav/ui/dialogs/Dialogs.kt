@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.screens.WebDavCachedThumbnail

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
// DIALOG XÁC NHẬN NGỦ NAS
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
                Icon(Icons.Default.PowerSettingsNew, null, tint = Color(0xFF26A69A), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ngủ NAS", fontWeight = FontWeight.Bold)
            }
        },
        text = { Text("Chuyển NAS sang chế độ ngủ thay vì tắt nguồn hoàn toàn. Đèn LAN cần còn sáng để Wake-on-LAN đánh thức lại NAS.", fontSize = 14.sp) },
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
                    Text("Ngủ NAS", color = Color.White, fontWeight = FontWeight.Bold)
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDialog(
    downloadLink: String,
    onLinkChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onPickTorrentFile: () -> Unit = {}
) {
    var tabIndex by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.CloudDownload, null, tint = Color(0xFF26A69A), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tải BitTorrent", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0 to ("🔗" to "Link / Magnet"),
                    1 to ("📁" to "File .torrent"),
                ).forEach { (idx, pair) ->
                    val (emoji, label) = pair
                    val selected = tabIndex == idx
                    FilterChip(
                        selected = selected,
                        onClick = { tabIndex = idx },
                        label = { Text("$emoji $label", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).height(34.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00897B).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF00897B),
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when (tabIndex) {
                0 -> {
                    Text("Dán Magnet Link hoặc HTTP URL của file .torrent. NAS sẽ tự tải qua qBittorrent.", fontSize = 12.sp, color = Color(0xFF8892B0))
                    Spacer(Modifier.height(6.dp))
                    com.nas.naswebdav.ui.components.CompactTextField(
                        value = downloadLink,
                        onValueChange = onLinkChange,
                        placeholder = "magnet:?xt=... hoặc https://...torrent",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onConfirm,
                        enabled = downloadLink.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("THÊM VÀO HÀNG ĐỢI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                1 -> {
                    Text("Chọn 1 file .torrent từ điện thoại để upload lên NAS. qBittorrent sẽ bắt đầu tải ngay.", fontSize = 12.sp, color = Color(0xFF8892B0))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onPickTorrentFile() },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00897B).copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00897B))
                    ) {
                        Icon(Icons.Default.UploadFile, null, tint = Color(0xFF00897B), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("CHỌN FILE .TORRENT", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Sau khi chọn, file sẽ tự upload và đóng form.",
                        color = Color(0xFF8892B0).copy(alpha = 0.7f), fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00897B).copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00897B))
            ) { Text("HỦY", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            Spacer(Modifier.height(4.dp))
        }
    }
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
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = macAddress,
                    onValueChange = onMacChange,
                    placeholder = "VD: AA:BB:CC:DD:EE:FF",
                    modifier = Modifier.fillMaxWidth()
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
// EXCLUSIVE PANEL STATE cho 3 section trong LivestreamRecordDialog:
// "watchlist" (THEO DÕI TIKTOK LIVE) | "exclude" (Thoi gian loai tru) | "active" (Dang ghi hinh)
// Chỉ 1 section mở cùng lúc -> tối ưu diện tích màn hình.
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
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Sync, null, tint = Color(0xFF43A047), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sao lưu tự động", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
            }

            Text("Tự động sao lưu ảnh lên NAS mỗi khi cắm sạc và có kết nối Wi-Fi.", fontSize = 12.sp, color = Color.LightGray)
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onAutoBackupEnabledChange(!isAutoBackupEnabled) }.padding(vertical = 2.dp)) {
                Switch(
                    checked = isAutoBackupEnabled,
                    onCheckedChange = onAutoBackupEnabledChange,
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00897B), checkedTrackColor = Color(0xFF80CBC4), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.DarkGray)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isAutoBackupEnabled) "Đã bật" else "Đã tắt", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (isAutoBackupEnabled) Color(0xFF00897B) else Color.Gray)
            }

            Spacer(Modifier.height(4.dp))

            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFF00897B).copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, Color(0xFF00897B).copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF4DB6AC), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Các tệp sẽ được lưu và giữ nguyên cấu trúc thư mục của máy vào trong thư mục /AutoBackup/ trên NAS.",
                        fontSize = 11.sp, color = Color(0xFFB2DFDB), lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color.DarkGray)
            Spacer(Modifier.height(6.dp))

            Text("Chế độ sao lưu:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDeleteAfterBackupChange(false) }.padding(vertical = 2.dp)) {
                RadioButton(
                    selected = !deleteAfterBackup,
                    onClick = { onDeleteAfterBackupChange(false) },
                    modifier = Modifier.scale(0.9f),
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF43A047), unselectedColor = Color.Gray)
                )
                Column {
                    Text("Chỉ Sao lưu (Copy)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (!deleteAfterBackup) Color(0xFF43A047) else Color.LightGray)
                    Text("Giữ lại ảnh gốc trên điện thoại.", fontSize = 11.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDeleteAfterBackupChange(true) }.padding(vertical = 2.dp)) {
                RadioButton(
                    selected = deleteAfterBackup,
                    onClick = { onDeleteAfterBackupChange(true) },
                    modifier = Modifier.scale(0.9f),
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFE53935), unselectedColor = Color.Gray)
                )
                Column {
                    Text("Sao lưu & Giải phóng (Move)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (deleteAfterBackup) Color(0xFFE53935) else Color.LightGray)
                    Text("Tự động xóa ảnh trên điện thoại sau khi lên NAS.", fontSize = 11.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Buttons Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onTriggerManualSync,
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ĐỒNG BỘ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = { onSaveAndSchedule(); onDismiss() },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("LƯU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Assignment, null, tint = Color(0xFF00ACC1), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nhật ký hệ thống", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                if (viewModel.systemLogsList.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearSystemLogs() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (viewModel.systemLogsList.isEmpty()) {
                Text("Chưa có dữ liệu nhật ký nào.", modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray, fontSize = 13.sp)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Icon(logIcon, null, tint = logColor, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(log.module, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = logColor)
                                        Text(timeStr, fontSize = 10.sp, color = Color.Gray)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(formatLogMessage(log.message), fontSize = 12.sp, color = Color.White.copy(alpha=0.85f))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại", tint = MaterialTheme.colorScheme.primary)
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
    if (message.isBlank()) return

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
    val currentOnAuthenticated by rememberUpdatedState(onAuthenticated)
    val currentOnFallbackToLogin by rememberUpdatedState(onFallbackToLogin)
    var authError by remember { mutableStateOf("") }
    var failCount by remember { mutableStateOf(0) }
    var authInFlight by remember { mutableStateOf(false) }
    var activePrompt by remember { mutableStateOf<androidx.biometric.BiometricPrompt?>(null) }
    val clearActivePrompt = {
        authInFlight = false
        activePrompt = null
    }
    val authenticate = authenticate@{
        if (authInFlight) return@authenticate
        authInFlight = true
        try {
            val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Khóa bảo mật NAS").setSubtitle("Vui lòng xác thực vân tay/khuôn mặt để truy cập dữ liệu")
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
            val biometricPrompt = androidx.biometric.BiometricPrompt(activity, executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { super.onAuthenticationSucceeded(result); clearActivePrompt(); failCount = 0; currentOnAuthenticated() }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        clearActivePrompt()
                        if (errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED || errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) currentOnFallbackToLogin()
                        else { failCount++; authError = "Lỗi: $errString (Sai $failCount/3 lần)"; if (failCount >= 3) currentOnFallbackToLogin() }
                    }
                    override fun onAuthenticationFailed() { super.onAuthenticationFailed(); failCount++; authError = "Vân tay không khớp! (Sai $failCount/3 lần)"; if (failCount >= 3) { clearActivePrompt(); currentOnFallbackToLogin() } }
                })
            activePrompt = biometricPrompt
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            clearActivePrompt()
            authError = "Lỗi: ${e.message ?: "Không mở được quét vân tay"}"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activePrompt?.cancelAuthentication()
            activePrompt = null
            authInFlight = false
        }
    }
    var hasStartedAuth by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!hasStartedAuth) { hasStartedAuth = true; authenticate() } }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).pointerInput(Unit) { detectTapGestures { authenticate() } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = authenticate, modifier = Modifier.size(140.dp)) {
                Icon(Icons.Default.Fingerprint, contentDescription = "Quét vân tay để mở khóa", modifier = Modifier.size(120.dp), tint = Color(0xFF00897B))
            }
            if (authError.isNotEmpty()) { Spacer(Modifier.height(16.dp)); Text(authError, color = Color.Red, fontSize = 14.sp) }
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
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).heightIn(max = 600.dp)
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
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it },
                    placeholder = "192.168.1.0/24",
                    accentColor = Color(0xFF66BB6A),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newEntry.isNotBlank()) {
                            viewModel.addLanWhitelistEntry(newEntry.trim())
                            newEntry = ""
                        }
                    },
                    modifier = Modifier.size(40.dp).background(Color(0xFF66BB6A).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) { Icon(Icons.Default.Add, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(18.dp)) }
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
                            key("subnet-$subnet") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { viewModel.removeLanWhitelistEntry(subnet, true) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(Color(0xFF15151D), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Hub, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(subnet, color = Color(0xFFE8E8E8), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                    if (ipList.isNotEmpty()) {
                        if (subnetList.isNotEmpty()) Spacer(Modifier.height(12.dp))
                        Text("IP", fontSize = 11.sp, color = Color(0xFF8892B0), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        ipList.forEach { ip ->
                            key("ip-$ip") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { viewModel.removeLanWhitelistEntry(ip, false) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(Color(0xFF15151D), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Computer, null, tint = Color(0xFF00D2FF), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(ip, color = Color(0xFFE8E8E8), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
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
                    Text("Bạn có chắc chắn muốn NAS quét và di chuyển toàn bộ video không phải MP4 (như mpg, flv, mkv, avi...) vào thư mục 'Other Video' không? Thao tác này giúp danh sách video gọn hơn và được xử lý trực tiếp trên NAS.")
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
            com.nas.naswebdav.ui.components.CompactTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = "Nhập tên thư mục",
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

