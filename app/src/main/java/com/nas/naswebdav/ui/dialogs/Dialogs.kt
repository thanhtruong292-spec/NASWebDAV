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
                        Text("Nhanh gấp 100 lần. Bỏ qua phân tích nội dung, chỉ dùng ETag gốc (dung lượng, tên, ngày sửa). Có thể quét rất nhanh tới 500.000 tệp.", fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
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
                        Text("🤖 Tự động dọn dẹp (hàng tuần)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                        Text("Chạy nền 7 ngày/lần khi điện thoại đang sạc pin và có Wi-Fi. Tự động chuyển tệp trùng vào thùng rác (.trash), giữ lại tệp có đường dẫn ngắn nhất.", fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
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
                // BỔ SUNG: Nhóm theo Hash/Fingerprint để đảm bảo hiển thị đúng file trùng, 
                // dùng contentLength làm fallback dự phòng.
                val groupedDuplicates = remember(viewModel.duplicateFilesList) {
                    viewModel.duplicateFilesList.groupBy { it.partialHash ?: it.contentLength }.values.filter { it.size >= 2 }.toList()
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
                                            val authSnapshot = viewModel.webDavManager.currentAuthState()
                                            val auth = okhttp3.Credentials.basic(authSnapshot.user, authSnapshot.pass)

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
                                                        WebDavCachedThumbnail(url = dupFile.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize(), viewModel = viewModel)
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
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    // Khôi phục trạng thái nếu Worker đang chạy ngầm
    LaunchedEffect(Unit) { viewModel.syncLivestreamStateWithServer(context) }
    // Reset tat ca panel ve trang thai dong khi user mo dialog — moi lan vao se thay
    // giao dien gon, user chu dong bam header de xem section can xem.
    androidx.compose.runtime.DisposableEffect(Unit) {
        LivestreamPanelState.current.value = null
        onDispose { }
    }
    var liveUrl by remember { mutableStateOf("") }
    var isResolvingTikTokLink by remember { mutableStateOf(false) }
    var newTikTokWatchUser by remember { mutableStateOf("") }
    var livePanelMode by remember { mutableStateOf("record") }
    var selectedQuality by remember { mutableStateOf("best") }
    val activeLivestreams = viewModel.activeLivestreams
    val message = viewModel.livestreamMessage
    val tiktokWatchUsers = viewModel.tiktokLiveWatchUsers

    LaunchedEffect(Unit) { viewModel.fetchTikTokLiveWatch(context) }

    // AUTO-PASTE: Đọc clipboard khi dialog mở, tự dán nếu chứa link livestream
    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text ?: ""
        if (clipText.isNotBlank() && listOf("tiktok", "facebook", "fb.watch", "youtube", "youtu.be", "shopee").any { clipText.contains(it, true) }) {
            liveUrl = clipText.trim()
            livePanelMode = "record"
            viewModel.clearLivestreamMessage()
        }
    }

    val detectedPlatform = remember(liveUrl) {
        when {
            liveUrl.contains("tiktok", true) -> "tiktok"
            liveUrl.contains("facebook", true) || liveUrl.contains("fb.watch", true) -> "facebook"
            liveUrl.contains("youtube", true) || liveUrl.contains("youtu.be", true) -> "youtube"
            liveUrl.contains("shopee", true) -> "shopee"
            else -> ""
        }
    }

    // Trích xuất tên/username từ URL để hiện thay vì chỉ "TikTok Live"
    val detectedTitle = remember(liveUrl) {
        when {
            liveUrl.contains("tiktok", true) -> {
                val username = Regex("@([\\w.]+)").find(liveUrl)?.groupValues?.get(1)
                if (username != null) "Live của @$username" 
                else if (isResolvingTikTokLink || liveUrl.contains("vt.tiktok.com") || liveUrl.contains("vm.tiktok.com") || Regex("""tiktok\.com/t/[\w-]+""").containsMatchIn(liveUrl)) "TikTok Live (Đang lấy tên...)"
                else "TikTok Live"
            }
            liveUrl.contains("facebook", true) || liveUrl.contains("fb.watch", true) -> {
                val fbUser = Regex("facebook\\.com/([^/\\?]+)").find(liveUrl)?.groupValues?.get(1)
                if (!fbUser.isNullOrEmpty() && fbUser != "watch") "Live của $fbUser" else "Facebook Live"
            }
            liveUrl.contains("youtube", true) || liveUrl.contains("youtu.be", true) -> {
                val channel = Regex("@([\\w.-]+)").find(liveUrl)?.groupValues?.get(1)
                if (channel != null) "Live của @$channel" else "YouTube Live"
            }
            liveUrl.contains("shopee", true) -> "Shopee Live"
            else -> ""
        }
    }
    
    // Auto-resolve TikTok short links to get the actual username.
    // Bao gom: vt.tiktok.com/<id>, vm.tiktok.com/<id>, va dinh dang share moi tiktok.com/t/<id>
    LaunchedEffect(liveUrl) {
        val originalUrl = liveUrl.trim()
        val isShortLink = originalUrl.contains("vt.tiktok.com") ||
                          originalUrl.contains("vm.tiktok.com") ||
                          Regex("""tiktok\.com/t/[\w-]+""").containsMatchIn(originalUrl)
        if (isShortLink && !originalUrl.contains("@")) {
            isResolvingTikTokLink = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    var current = originalUrl
                    // Theo redirect toi 5 hop de tranh loop, vi tiktok.com/t/ co the redirect 2-3 lan
                    for (hop in 0 until 5) {
                        val conn = java.net.URL(current).openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        conn.setRequestProperty("Referer", "https://www.tiktok.com/")
                        conn.responseCode
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location.isNullOrBlank()) break
                        current = if (location.startsWith("http")) location else java.net.URL(java.net.URL(current), location).toString()
                        if (current.contains("@")) break
                    }
                    if (current.contains("@") && current != originalUrl) {
                        val newUrl = current.substringBefore("?")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (liveUrl == originalUrl) {
                                liveUrl = newUrl
                                viewModel.clearLivestreamMessage()
                            }
                        }
                    }
                } catch(e: Exception) {}
                finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        isResolvingTikTokLink = false
                    }
                }
            }
        } else {
            isResolvingTikTokLink = false
        }
    }

    val activePlatform = detectedPlatform.ifEmpty { "livestream" }
    val platformIcon = when (activePlatform) { "tiktok" -> "🎵"; "facebook" -> "📘"; "youtube" -> "▶️"; "shopee" -> "🛒"; else -> "📹" }
    val platformName = when (activePlatform) { "tiktok" -> "TikTok"; "facebook" -> "Facebook"; "youtube" -> "YouTube"; "shopee" -> "Shopee"; else -> "Livestream" }
    val accentColor = when (activePlatform) { "tiktok" -> Color(0xFFEE1D52); "facebook" -> Color(0xFF1877F2); "youtube" -> Color(0xFFFF0000); else -> Color(0xFFFF6B35) }

    // ScrollState chia se cho toan dialog — khi user mo 1 panel thi tu dong scroll
    // de panel content lo ra ngoai cua so visible (khong bi an duoi day man hinh).
    val dialogScrollState = rememberScrollState()
    val tiktokSnackbarHostState = remember { SnackbarHostState() }
    // SheetState voi skipPartiallyExpanded = true — sheet luon o full height, khong
    // bao gio dung lai o half. Khi user mo panel thi sheet con auto expand() de
    // dam bao co du khong gian hien thi content.
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val expandedPanel = LivestreamPanelState.current.value
    androidx.compose.runtime.LaunchedEffect(expandedPanel) {
        if (expandedPanel != null) {
            // 1) Day sheet len max height (truong hop user mo dialog xong it phat
            //    moi bam panel, sheet co the dang o trang thai chua full)
            try { sheetState.expand() } catch (_: Exception) {}
            // 2) Cho animation expand cua panel ~200ms
            kotlinx.coroutines.delay(220)
            // 3) Scroll dialog content xuong day -> content panel vua mo lo ra het
            dialogScrollState.animateScrollTo(dialogScrollState.maxValue)
        }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F0F0F),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { CompactBottomSheetHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                // Cho phep content cao den 1000dp -> du cho ca khi expand "Dang ghi hinh"
                // voi nhieu job. Vuot qua se duoc scroll boi verticalScroll.
                .heightIn(max = 1000.dp)
                .verticalScroll(dialogScrollState)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text(platformIcon, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
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

            // Tab buttons — dung [PillTab] de dam bao consistency voi cac tab khac trong app
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.nas.naswebdav.ui.components.PillTab(
                    selected = livePanelMode == "watch",
                    label = "Theo dõi người dùng",
                    emoji = "👤",
                    accentColor = Color(0xFFEE1D52),
                    onClick = { livePanelMode = "watch" },
                    modifier = Modifier.weight(1f),
                )
                com.nas.naswebdav.ui.components.PillTab(
                    selected = livePanelMode == "record",
                    label = "Ghi link live",
                    emoji = "🔗",
                    accentColor = accentColor,
                    onClick = { livePanelMode = "record" },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))

            if (livePanelMode == "watch") {
                TikTokLiveWatchSection(
                    viewModel = viewModel,
                    context = context,
                    users = tiktokWatchUsers,
                    newUsername = newTikTokWatchUser,
                    onUsernameChange = { newTikTokWatchUser = it.removePrefix("@") },
                    snackbarHostState = tiktokSnackbarHostState
                )
            } else {

            // --- PHẦN 1: FORM TẠO JOB MỚI ---
            com.nas.naswebdav.ui.components.CompactTextField(
                value = liveUrl,
                onValueChange = {
                    liveUrl = it
                    viewModel.clearLivestreamMessage()
                },
                placeholder = "Dán link livestream — https://www.tiktok.com/@user/live",
                accentColor = accentColor,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (liveUrl.isNotEmpty()) {
                        IconButton(onClick = { liveUrl = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Xóa", tint = Color(0xFF8892B0), modifier = Modifier.size(18.dp))
                        }
                    } else if (detectedPlatform.isNotEmpty()) {
                        Text(platformIcon, fontSize = 18.sp)
                    }
                }
            )

            Spacer(Modifier.height(6.dp))

            if (detectedPlatform.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(platformIcon, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Đã nhận diện: $detectedTitle", color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
            }
            
            Text("CHẤT LƯỢNG", color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
            
            if (viewModel.isStartingLivestream) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = accentColor, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(viewModel.livestreamMessage.ifEmpty { "Đang kết nối luồng Live..." }, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (message.isNotEmpty()) {
                // FIX: Auto-clear lỗi sau 5 giây để hiện lại nút "BẮT ĐẦU GHI"
                LaunchedEffect(message) {
                    kotlinx.coroutines.delay(5000L)
                    viewModel.clearLivestreamMessage()
                }
                Spacer(Modifier.height(6.dp))
                val msgColor = if (message.startsWith("Lỗi")) Color.Red else Color(0xFF8892B0)
                Text(
                    message, color = msgColor, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.clearLivestreamMessage() },
                    textAlign = TextAlign.Center
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (liveUrl.isNotBlank() && !viewModel.isStartingLivestream) {
                            val isVOD = liveUrl.contains("/video/") || liveUrl.contains("/watch") || liveUrl.contains("youtu.be") || liveUrl.contains("/t/") || liveUrl.contains("/v/") || liveUrl.contains("/reel")
                            // Bóc tách username TikTok tu URL (sau khi resolver da chay xong)
                            // de tu dong them vao danh sach theo doi — lan sau watchdog tu phat hien live.
                            val tiktokUsername: String? = if (liveUrl.contains("tiktok", true)) {
                                Regex("tiktok\\.com/@([\\w.]+)").find(liveUrl)?.groupValues?.get(1)
                            } else null
                            if (isVOD) {
                                // Tự động phát hiện Video On Demand (VOD) thay vì Livestream
                                // Chuyển hướng sang yt-dlp nhưng lưu vào Livestream/ để user dễ tìm
                                viewModel.requestSocialDownload(liveUrl.trim(), "Livestream/")
                                onDismiss()
                            } else {
                                viewModel.startLivestreamRecord(context, liveUrl.trim(), selectedQuality)
                            }
                        }
                    },
                    enabled = liveUrl.isNotBlank() && !isResolvingTikTokLink,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.AddCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isResolvingTikTokLink) "ĐANG LẤY USER..." else "BẮT ĐẦU GHI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            }

            Spacer(Modifier.height(6.dp))

            // --- PHẦN 2: DANH SÁCH CÁC JOB ĐANG GHI ---
            // Ẩn mặc định, bấm header để mở (toggle "active" panel). Khi mở sẽ tự
            // động đóng các panel khác (watchlist + exclude) thông qua LivestreamPanelState.
            val activeExpanded = LivestreamPanelState.current.value == "active"
            if (activeLivestreams.isNotEmpty()) {
                HorizontalDivider(color = Color.DarkGray)
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { LivestreamPanelState.toggle("active") }
                ) {
                    val pulse = rememberInfiniteTransition(label = "rec_pulse")
                    val alpha by pulse.animateFloat(initialValue = 1f, targetValue = 0.4f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "rec_alpha")
                    Box(Modifier.size(8.dp).background(Color.Red.copy(alpha = alpha), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ĐANG GHI HÌNH (${activeLivestreams.size})",
                        color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (activeExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (activeExpanded) "Ẩn" else "Mở",
                        tint = Color(0xFF9AA3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            androidx.compose.animation.AnimatedVisibility(visible = activeLivestreams.isNotEmpty() && activeExpanded) {
                // CHANGED: LazyColumn -> Column de tranh loi "Vertically scrolling parent
                // doesn't have a maximum height" khi nam trong outer verticalScroll Column.
                // List active luong it (max ~16) nen Column khong gay perf issue.
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    activeLivestreams.forEach { job ->
                        val jobPlatform = job.platform.ifEmpty { "livestream" }
                        val jobPlatformName = when (jobPlatform) { "tiktok" -> "TikTok"; "facebook" -> "Facebook"; "youtube" -> "YouTube"; "shopee" -> "Shopee"; else -> "Livestream" }
                        val jobAccentColor = when (jobPlatform) { "tiktok" -> Color(0xFFEE1D52); "facebook" -> Color(0xFF1877F2); "youtube" -> Color(0xFFFF0000); else -> Color(0xFFFF6B35) }

                        Column(
                            Modifier.fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(jobAccentColor.copy(alpha = 0.12f), Color.Transparent)), RoundedCornerShape(10.dp))
                                .border(1.dp, jobAccentColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(8.dp)
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
                            Spacer(Modifier.height(6.dp))
                            // FIX: chia thanh 3 cot dong nhat — Thoi gian chay | Toc do | Dung luong
                            // 3 cot dung Row weight 1f de cach deu, label cung font 10sp xam, value cung
                            // font 16sp bold trang. Toc do giua, dung luong phai (align end).
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                // Cot 1: Thoi gian chay
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Thời gian chạy", color = Color(0xFF8892B0), fontSize = 10.sp)
                                    var localSeconds by remember(job.jobId) { mutableStateOf(job.durationSeconds) }
                                    LaunchedEffect(job.jobId, job.startedTs, job.durationSeconds) {
                                        localSeconds = if (job.startedTs > 0L) {
                                            ((System.currentTimeMillis() / 1000L) - job.startedTs).coerceAtLeast(0L)
                                        } else {
                                            localSeconds.coerceAtLeast(job.durationSeconds)
                                        }
                                    }
                                    LaunchedEffect(job.jobId) {
                                        while (true) {
                                            delay(1000)
                                            localSeconds = if (job.startedTs > 0L) {
                                                ((System.currentTimeMillis() / 1000L) - job.startedTs).coerceAtLeast(0L)
                                            } else {
                                                localSeconds + 1
                                            }
                                        }
                                    }
                                    val displayDur = "${localSeconds / 3600}h${String.format("%02d", (localSeconds % 3600) / 60)}m${String.format("%02d", localSeconds % 60)}s"
                                    Text(displayDur, color = Color(0xFFE8E8E8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                // Cot 2: Toc do (giua, ngang voi 2 cot kia)
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Tốc độ", color = Color(0xFF8892B0), fontSize = 10.sp)
                                    Text(job.speed.ifEmpty { "—" }, color = Color(0xFFE8E8E8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                // Cot 3: Dung luong (align phai)
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text("Dung lượng", color = Color(0xFF8892B0), fontSize = 10.sp)
                                    Text(job.fileSize.ifEmpty { "0 B" }, color = Color(0xFFE8E8E8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (job.outputFile.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(job.outputFile, color = Color(0xFF8892B0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { viewModel.stopLivestreamRecord(context, job.jobId) },
                                modifier = Modifier.fillMaxWidth().height(38.dp),
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
            }
            if (activeLivestreams.isEmpty()) {
                // Khi không có luồng nào đang ghi -> thêm spacer cho UI không bị sát đáy.
                Spacer(Modifier.height(12.dp))
            }
        }
        SnackbarHost(
            hostState = tiktokSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Color(0xFF1A1A24),
                contentColor = Color(0xFFE8E8E8),
                actionColor = Color(0xFF4DD0E1),
                shape = RoundedCornerShape(8.dp)
            )
        }
        }
    }
}

// ====================================================================
// DIALOG CAU HINH KHOA SINH TRAC HOC
// Truoc day chi co toggle on/off + delay hardcode 60s -> user thay nhu
// khong tac dung. Dialog moi:
//   - Check biometric availability (BiometricManager.canAuthenticate)
//   - Toggle enable + delay picker (0s instant / 5s / 30s / 1m / 5m)
//   - Nut "Khoa ngay" de test khong can doi
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiometricSettingsDialog(
    viewModel: WebDavViewModel,
    sharedPrefs: android.content.SharedPreferences,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(sharedPrefs.getBoolean("biometric_enabled", false)) }
    var delaySec by remember { mutableStateOf(sharedPrefs.getInt("biometric_lock_delay_sec", 10)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Check biometric availability
    val bioStatus = remember {
        try {
            val bm = androidx.biometric.BiometricManager.from(context)
            val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            when (bm.canAuthenticate(auth)) {
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "available"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hw_unavailable"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
                else -> "unknown"
            }
        } catch (e: Exception) { "error: ${e.message}" }
    }

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
                Icon(Icons.Default.Lock, null, tint = Color(0xFF9C27B0), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Khóa Sinh trắc học", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            // Availability badge
            val (bioColor, bioText) = when (bioStatus) {
                "available" -> Color(0xFF66BB6A) to "Sinh trắc học sẵn sàng (vân tay/khuôn mặt đã đăng ký)"
                "no_hardware" -> Color(0xFFEF5350) to "Thiết bị không hỗ trợ sinh trắc"
                "hw_unavailable" -> Color(0xFFFFA726) to "Phần cứng sinh trắc tạm thời không khả dụng"
                "none_enrolled" -> Color(0xFFFFA726) to "Chưa đăng ký vân tay/khuôn mặt nào. Vào Cài đặt → Sinh trắc để thêm."
                else -> Color(0xFF8892B0) to "Trạng thái: $bioStatus"
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(bioColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .border(1.dp, bioColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (bioStatus) {
                        "available" -> Icons.Default.CheckCircle
                        else -> Icons.Default.Warning
                    },
                    null, tint = bioColor, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(bioText, color = bioColor, fontSize = 12.sp, lineHeight = 15.sp)
            }

            // Enable toggle
            Spacer(Modifier.height(5.dp))
            HorizontalDivider(color = Color(0xFF2A2A3E))
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(
                    enabled = bioStatus == "available",
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    enabled = !enabled
                }
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = bioStatus == "available",
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF9C27B0), checkedTrackColor = Color(0xFF9C27B0).copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bật khoá sinh trắc", color = Color(0xFFE8E8E8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Khoá khi app vào nền theo thời gian dưới"
                        else "Tắt — app không bao giờ tự khoá",
                        color = Color(0xFF8892B0), fontSize = 11.sp
                    )
                }
            }

            // Delay picker
            Spacer(Modifier.height(5.dp))
            Text("THỜI GIAN CHỜ KHOÁ (sau khi app vào nền)", color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            val delayOptions = listOf(
                0 to "Khoá NGAY",
                5 to "5 giây",
                30 to "30 giây",
                60 to "1 phút",
                300 to "5 phút",
                600 to "10 phút",
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                delayOptions.forEach { (sec, label) ->
                    val isSel = delaySec == sec
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .height(44.dp)
                            .background(
                                if (isSel) Color(0xFF9C27B0).copy(alpha = 0.15f) else Color(0xFF15151D),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { delaySec = sec }
                            .padding(horizontal = 8.dp, vertical = 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = { delaySec = sec },
                            enabled = enabled,
                            modifier = Modifier.scale(0.7f),
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF9C27B0))
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            label,
                            color = when {
                                !enabled -> Color(0xFF8892B0).copy(alpha = 0.5f)
                                isSel -> Color(0xFF9C27B0)
                                else -> Color(0xFFE8E8E8)
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        sharedPrefs.edit()
                            .putBoolean("biometric_enabled", enabled)
                            .putInt("biometric_lock_delay_sec", delaySec)
                            .apply()
                        viewModel.logUserAction("Security", "cập nhật khóa sinh trắc (${if (enabled) "bật" else "tắt"}, trễ ${delaySec}s).")
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("LƯU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                OutlinedButton(
                    onClick = {
                        // Save first, then trigger lock
                        sharedPrefs.edit()
                            .putBoolean("biometric_enabled", true)
                            .putInt("biometric_lock_delay_sec", delaySec)
                            .apply()
                        viewModel.logUserAction("Security", "Kích hoạt khoá sinh trắc học cục bộ.")
                        viewModel.lockNowRequested = true
                        onDismiss()
                    },
                    enabled = bioStatus == "available",
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF9C27B0).copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9C27B0))
                ) { Text("KHOÁ NGAY", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Lưu ý: \"Khoá NGAY\" trong delay = không có buffer khi switch app/đọc thông báo. Đề xuất 5-30 giây.",
                color = Color(0xFF8892B0).copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}


// ====================================================================
// DIALOG GIOI HAN TOC DO UPLOAD — Throttle WebDAV upload
// Backend (AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC) da co. Day la UI
// chinh + persist sang SharedPreferences. App start tu doc lai gia tri.
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandwidthThrottleDialog(
    viewModel: WebDavViewModel,
    sharedPrefs: android.content.SharedPreferences,
    onDismiss: () -> Unit
) {
    val presets = listOf(
        0L to "Không giới hạn",
        1L * 1024 * 1024 to "1 MB/s",
        5L * 1024 * 1024 to "5 MB/s",
        10L * 1024 * 1024 to "10 MB/s",
        20L * 1024 * 1024 to "20 MB/s",
        50L * 1024 * 1024 to "50 MB/s",
    )
    var selected by remember { mutableStateOf(sharedPrefs.getLong("upload_speed_limit_bps", 0L)) }
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Icon(Icons.Default.Speed, null, tint = Color(0xFF42A5F5), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Giới hạn tốc độ upload", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(
                "Áp dụng cho tất cả upload qua WebDAV (auto-backup ảnh, share file, batch ops). " +
                    "Dùng để tránh app chiếm hết băng thông Wi-Fi/LAN.",
                color = Color(0xFF8892B0), fontSize = 11.sp, lineHeight = 14.sp
            )

            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                presets.forEach { (value, label) ->
                    val isSelected = selected == value
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .height(44.dp)
                            .background(
                                if (isSelected) Color(0xFF42A5F5).copy(alpha = 0.15f) else Color(0xFF15151D),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF42A5F5).copy(alpha = 0.6f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { selected = value }
                            .padding(horizontal = 8.dp, vertical = 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selected = value },
                            modifier = Modifier.scale(0.7f),
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF42A5F5))
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            label,
                            color = if (isSelected) Color(0xFF42A5F5) else Color(0xFFE8E8E8),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    sharedPrefs.edit().putLong("upload_speed_limit_bps", selected).apply()
                    com.nas.naswebdav.AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC = selected
                    val selectedLabel = presets.firstOrNull { it.first == selected }?.second ?: "${selected / 1024 / 1024} MB/s"
                    viewModel.logUserAction("Bandwidth", "Thiết lập giới hạn băng thông tải lên: $selectedLabel.")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF42A5F5)),
                shape = RoundedCornerShape(10.dp),
            ) { Text("ÁP DỤNG", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Spacer(Modifier.height(4.dp))
            Text(
                "Lưu ý: giới hạn này CHỈ ảnh hưởng upload từ điện thoại lên NAS, không ảnh hưởng tốc độ NAS ↔ Internet.",
                color = Color(0xFF8892B0).copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}


// ====================================================================
// DIALOG LICH NGU NAS — HDD spindown / full suspend theo gio
// Bao ve o cung khoi mon: ngoai gio dung, parking head + ngung quay.
// Tich hop voi Disk Health Monitor de keo dai tuoi tho o cu.
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScheduleDialog(
    viewModel: WebDavViewModel,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { viewModel.fetchSleepSchedule() }

    val sched = viewModel.sleepSchedule
    var localEnabled by remember(sched.enabled) { mutableStateOf(sched.enabled) }
    var localMode by remember(sched.mode) { mutableStateOf(sched.mode) }
    var localStartHour by remember(sched.startHour) { mutableStateOf(sched.startHour) }
    var localEndHour by remember(sched.endHour) { mutableStateOf(sched.endHour) }
    var localIdleOnly by remember(sched.idleOnly) { mutableStateOf(sched.idleOnly) }
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
                .fillMaxHeight(0.6f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Bedtime, null, tint = Color(0xFF7E57C2), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Lịch ngủ NAS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.fetchSleepSchedule() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, null, tint = Color(0xFF8892B0), modifier = Modifier.size(16.dp))
                }
            }
            Text(
                "Tự động parking head + ngừng quay HDD ngoài giờ dùng → giảm hao mòn (đặc biệt với ổ đã già). " +
                    "NAS vẫn online (ping/SSH OK), chỉ HDD spindown. Khi có request đụng disk → tự wake.",
                color = Color(0xFF8892B0), fontSize = 11.sp, lineHeight = 14.sp
            )

            // Current HDD state badge
            Spacer(Modifier.height(6.dp))
            val stateColor = when {
                sched.currentHddState.contains("active", true) -> Color(0xFF66BB6A)
                sched.currentHddState.contains("standby", true) || sched.currentHddState.contains("sleeping", true) -> Color(0xFF7E57C2)
                else -> Color(0xFF8892B0)
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(stateColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .border(1.dp, stateColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Storage, null, tint = stateColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("HDD: ${sched.currentHddState}", color = stateColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (sched.inWindowNow) "Đang trong khung giờ ngủ" else "Ngoài khung giờ ngủ",
                        color = Color(0xFF8892B0), fontSize = 11.sp
                    )
                }
            }

            // Enable toggle
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF2A2A3E))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { localEnabled = !localEnabled }) {
                Switch(
                    checked = localEnabled,
                    onCheckedChange = { localEnabled = it },
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF7E57C2), checkedTrackColor = Color(0xFF7E57C2).copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bật lịch ngủ", color = Color(0xFFE8E8E8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (localEnabled) "Sẽ spindown theo lịch dưới" else "Chưa kích hoạt", color = Color(0xFF8892B0), fontSize = 11.sp)
                }
            }

            // Time range
            Spacer(Modifier.height(8.dp))
            Text("KHUNG GIỜ NGỦ (24h)", color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Từ", color = Color(0xFF8892B0), fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = localStartHour.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) localStartHour = it } },
                    accentColor = Color(0xFF7E57C2),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.width(60.dp)
                )
                Text("h", color = Color(0xFF8892B0), fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("→", color = Color(0xFF8892B0), fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("Đến", color = Color(0xFF8892B0), fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = localEndHour.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) localEndHour = it } },
                    accentColor = Color(0xFF7E57C2),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.width(60.dp)
                )
                Text("h", color = Color(0xFF8892B0), fontSize = 13.sp)
            }
            Text(
                if (localStartHour < localEndHour) "Trong ngày (${localStartHour}h-${localEndHour}h)"
                else "Qua đêm (${localStartHour}h-${localEndHour}h sáng hôm sau)",
                color = Color(0xFF8892B0).copy(alpha = 0.7f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            // Mode picker
            Spacer(Modifier.height(8.dp))
            Text("CHẾ ĐỘ NGỦ", color = Color(0xFF8892B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    "spindown" to "HDD Spindown",
                    "suspend" to "Full Suspend",
                ).forEach { (value, label) ->
                    val selected = localMode == value
                    FilterChip(
                        selected = selected,
                        onClick = { localMode = value },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF7E57C2).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF7E57C2),
                            containerColor = Color.Transparent,
                            labelColor = Color(0xFF8892B0)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color(0xFF8892B0).copy(alpha = 0.3f),
                            selectedBorderColor = Color(0xFF7E57C2).copy(alpha = 0.6f),
                            enabled = true, selected = selected
                        ),
                        modifier = Modifier.weight(1f).height(32.dp)
                    )
                }
            }
            Text(
                when (localMode) {
                    "spindown" -> "HDD ngừng quay, NAS vẫn online (mạng, SSH, ping OK). Wake tự động khi có request."
                    else -> "NAS suspend hoàn toàn — cần WoL để đánh thức. KHÔNG khuyến nghị khi đang theo dõi TikTok live."
                },
                color = Color(0xFF8892B0).copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            // Idle only toggle
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { localIdleOnly = !localIdleOnly }) {
                Switch(
                    checked = localIdleOnly,
                    onCheckedChange = { localIdleOnly = it },
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF7E57C2), checkedTrackColor = Color(0xFF7E57C2).copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Chỉ ngủ khi NAS rảnh", color = Color(0xFFE8E8E8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("CPU<30% + không có recording + không backup chạy", color = Color(0xFF8892B0), fontSize = 11.sp)
                }
            }

            // Save + test buttons
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        viewModel.saveSleepSchedule(
                            WebDavViewModel.SleepSchedule(
                                enabled = localEnabled,
                                mode = localMode,
                                startHour = localStartHour,
                                endHour = localEndHour,
                                idleOnly = localIdleOnly,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2)),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("LƯU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                OutlinedButton(
                    onClick = {
                        viewModel.spindownHddNow { ok, msg ->
                            android.widget.Toast.makeText(context, if (ok) "Spindown OK" else "Lỗi: $msg", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7E57C2).copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7E57C2))
                ) { Text("SPINDOWN NGAY", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }

            // Status message
            if (viewModel.sleepScheduleMessage.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val msgColor = if (viewModel.sleepScheduleMessage.startsWith("Lỗi")) Color(0xFFEF5350) else Color(0xFF66BB6A)
                Text(viewModel.sleepScheduleMessage, color = msgColor, fontSize = 11.sp)
            }
            if (sched.lastActionState.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Lần ngủ cuối: ${sched.lastActionState}",
                    color = Color(0xFF8892B0).copy(alpha = 0.7f), fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

