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
        .replace("Nguoi dung", "Người dùng")
        .replace("nguoi dung", "người dùng")
        .replace("dat che do quat", "đặt chế độ quạt")
        .replace("Dat che do quat", "Đặt chế độ quạt")
        .replace("che do quat", "chế độ quạt")
        .replace("thanh cong", "thành công")
        .replace("that bai", "thất bại")
        .replace("Khong dat duoc", "Không đặt được")
        .replace("khong dat duoc", "không đặt được")
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerDialog(
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
        // KHÔNG dùng verticalScroll vì bên trong có LazyColumn (tránh nested scroll cùng chiều).
        Column(
            modifier = Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Icon(Icons.Default.ViewInAr, null, tint = Color(0xFF1E88E5), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Quản lý Docker", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                if (viewModel.isFetchingDocker) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF1E88E5), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.fetchDockerContainers() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Refresh, "Làm mới", tint = Color.Gray)
                    }
                }
            }
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
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Đóng", color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}



