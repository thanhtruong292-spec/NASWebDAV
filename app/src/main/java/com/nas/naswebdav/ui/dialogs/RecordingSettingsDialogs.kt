@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.components.NasModalBottomSheet
import com.nas.naswebdav.ui.components.NasBottomSheetHandle
import com.nas.naswebdav.ui.screens.*
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
import androidx.core.content.edit

// ============ Livestream recording + settings dialogs (biometric/bandwidth/sleep)
//              (tách cơ học từ Dialogs.kt — không đổi logic) ============


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
fun BiometricSettingsDialogCompat(
    sharedPrefs: android.content.SharedPreferences,
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val autoBackupVM = LocalAutoBackupVM.current
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "error: ${e.message}" }
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Lock, null, tint = AccentPurple, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Khóa Sinh trắc học", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            // Availability badge
            val (bioColor, bioText) = when (bioStatus) {
                "available" -> AccentGreen to "Sinh trắc học sẵn sàng (vân tay/khuôn mặt đã đăng ký)"
                "no_hardware" -> AccentRed to "Thiết bị không hỗ trợ sinh trắc"
                "hw_unavailable" -> AccentOrange to "Phần cứng sinh trắc tạm thời không khả dụng"
                "none_enrolled" -> AccentOrange to "Chưa đăng ký vân tay/khuôn mặt nào. Vào Cài đặt → Sinh trắc để thêm."
                else -> TextTertiary to "Trạng thái: $bioStatus"
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
            HorizontalDivider(color = DarkCard)
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
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentPurple, checkedTrackColor = AccentPurple.copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bật khoá sinh trắc", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Khoá khi app vào nền theo thời gian dưới"
                        else "Tắt — app không bao giờ tự khoá",
                        color = TextTertiary, fontSize = 11.sp
                    )
                }
            }

            // Delay picker
            Spacer(Modifier.height(5.dp))
            Text("THỜI GIAN CHỜ KHOÁ (sau khi app vào nền)", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                                if (isSel) AccentPurple.copy(alpha = 0.15f) else DarkSurface,
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
                            colors = RadioButtonDefaults.colors(selectedColor = AccentPurple)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            label,
                            color = when {
                                !enabled -> TextTertiary.copy(alpha = 0.5f)
                                isSel -> AccentPurple
                                else -> TextPrimary
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
                        sharedPrefs.edit {
                            putBoolean("biometric_enabled", enabled)
                            putInt("biometric_lock_delay_sec", delaySec)
                        }
                        deviceVM.logUserAction("Security", "cập nhật khóa sinh trắc (${if (enabled) "bật" else "tắt"}, trễ ${delaySec}s).")
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("LƯU", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                OutlinedButton(
                    onClick = {
                        // Save first, then trigger lock
                        sharedPrefs.edit {
                            putBoolean("biometric_enabled", true)
                            putInt("biometric_lock_delay_sec", delaySec)
                        }
                        deviceVM.logUserAction("Security", "Kích hoạt khoá sinh trắc học cục bộ.")
                        autoBackupVM.lockNowRequested = true
                        onDismiss()
                    },
                    enabled = bioStatus == "available",
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
                ) { Text("KHOÁ NGAY", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Lưu ý: \"Khoá NGAY\" trong delay = không có buffer khi switch app/đọc thông báo. Đề xuất 5-30 giây.",
                color = TextTertiary.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
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
    sharedPrefs: android.content.SharedPreferences,
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
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

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Icon(Icons.Default.Speed, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Giới hạn tốc độ upload", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(
                "Áp dụng cho tất cả upload qua WebDAV (auto-backup ảnh, share file, batch ops). " +
                    "Dùng để tránh app chiếm hết băng thông Wi-Fi/LAN.",
                color = TextTertiary, fontSize = 11.sp, lineHeight = 14.sp
            )

            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                presets.forEach { (value, label) ->
                    val isSelected = selected == value
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .height(44.dp)
                            .background(
                                if (isSelected) AccentBlue.copy(alpha = 0.15f) else DarkSurface,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) AccentBlue.copy(alpha = 0.6f) else Color.Transparent,
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
                            colors = RadioButtonDefaults.colors(selectedColor = AccentBlue)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            label,
                            color = if (isSelected) AccentBlue else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    sharedPrefs.edit { putLong("upload_speed_limit_bps", selected) }
                    com.nas.naswebdav.AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC = selected
                    val selectedLabel = presets.firstOrNull { it.first == selected }?.second ?: "${selected / 1024 / 1024} MB/s"
                    deviceVM.logUserAction("Bandwidth", "Thiết lập giới hạn băng thông tải lên: $selectedLabel.")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(10.dp),
            ) { Text("ÁP DỤNG", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Spacer(Modifier.height(4.dp))
            Text(
                "Lưu ý: giới hạn này CHỈ ảnh hưởng upload từ điện thoại lên NAS, không ảnh hưởng tốc độ NAS ↔ Internet.",
                color = TextTertiary.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
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
    onDismiss: () -> Unit
) {
    val autoBackupVM = LocalAutoBackupVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Kept as Unit: one-shot schedule fetch when dialog opens.
    LaunchedEffect(Unit) { autoBackupVM.fetchSleepSchedule() }

    val sched = autoBackupVM.sleepSchedule
    var localEnabled by remember(sched.enabled) { mutableStateOf(sched.enabled) }
    var localMode by remember(sched.mode) { mutableStateOf(sched.mode) }
    var localStartHour by remember(sched.startHour) { mutableStateOf(sched.startHour) }
    var localEndHour by remember(sched.endHour) { mutableStateOf(sched.endHour) }
    var localIdleOnly by remember(sched.idleOnly) { mutableStateOf(sched.idleOnly) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            // Bỏ fillMaxHeight(0.6f): trước đây ép cao 60% màn hình -> thừa khoảng trống
            // đáy khi nội dung ngắn. Co theo nội dung; verticalScroll vẫn cuộn khi dài.
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Bedtime, null, tint = AccentPurple, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Lịch ngủ NAS", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { autoBackupVM.fetchSleepSchedule() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Refresh, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                "Tự động parking head + ngừng quay HDD ngoài giờ dùng → giảm hao mòn (đặc biệt với ổ đã già). " +
                    "NAS vẫn online (ping/SSH OK), chỉ HDD spindown. Khi có request đụng disk → tự wake.",
                color = TextTertiary, fontSize = 11.sp, lineHeight = 14.sp
            )

            // Current HDD state badge
            Spacer(Modifier.height(6.dp))
            val stateColor = when {
                sched.currentHddState.contains("active", true) -> AccentGreen
                sched.currentHddState.contains("standby", true) || sched.currentHddState.contains("sleeping", true) -> AccentPurple
                else -> TextTertiary
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
                        color = TextTertiary, fontSize = 11.sp
                    )
                }
            }

            // Enable toggle
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = DarkCard)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { localEnabled = !localEnabled }) {
                Switch(
                    checked = localEnabled,
                    onCheckedChange = { localEnabled = it },
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentPurple, checkedTrackColor = AccentPurple.copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bật lịch ngủ", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (localEnabled) "Sẽ spindown theo lịch dưới" else "Chưa kích hoạt", color = TextTertiary, fontSize = 11.sp)
                }
            }

            // Time range
            Spacer(Modifier.height(8.dp))
            Text("KHUNG GIỜ NGỦ (24h)", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Từ", color = TextTertiary, fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = localStartHour.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) localStartHour = it } },
                    accentColor = AccentPurple,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.width(60.dp)
                )
                Text("h", color = TextTertiary, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("→", color = TextTertiary, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("Đến", color = TextTertiary, fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = localEndHour.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) localEndHour = it } },
                    accentColor = AccentPurple,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.width(60.dp)
                )
                Text("h", color = TextTertiary, fontSize = 13.sp)
            }
            Text(
                if (localStartHour < localEndHour) "Trong ngày (${localStartHour}h-${localEndHour}h)"
                else "Qua đêm (${localStartHour}h-${localEndHour}h sáng hôm sau)",
                color = TextTertiary.copy(alpha = 0.7f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            // Mode picker
            Spacer(Modifier.height(8.dp))
            Text("CHẾ ĐỘ NGỦ", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                            selectedContainerColor = AccentPurple.copy(alpha = 0.2f),
                            selectedLabelColor = AccentPurple,
                            containerColor = Color.Transparent,
                            labelColor = TextTertiary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = TextTertiary.copy(alpha = 0.3f),
                            selectedBorderColor = AccentPurple.copy(alpha = 0.6f),
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
                color = TextTertiary.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            // Idle only toggle
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { localIdleOnly = !localIdleOnly }) {
                Switch(
                    checked = localIdleOnly,
                    onCheckedChange = { localIdleOnly = it },
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentPurple, checkedTrackColor = AccentPurple.copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Chỉ ngủ khi NAS rảnh", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("CPU<30% + không có recording + không backup chạy", color = TextTertiary, fontSize = 11.sp)
                }
            }

            // Save + test buttons
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        autoBackupVM.saveSleepSchedule(
                            SleepSchedule(
                                enabled = localEnabled,
                                mode = localMode,
                                startHour = localStartHour,
                                endHour = localEndHour,
                                idleOnly = localIdleOnly,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("LƯU", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                OutlinedButton(
                    onClick = {
                        autoBackupVM.spindownHddNow()
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
                ) { Text("SPINDOWN NGAY", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }

            // Status message
            if (autoBackupVM.sleepScheduleMessage.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val msgColor = if (autoBackupVM.sleepScheduleMessage.startsWith("Lỗi")) AccentRed else AccentGreen
                Text(autoBackupVM.sleepScheduleMessage, color = msgColor, fontSize = 11.sp)
            }
            if (sched.lastActionState.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Lần ngủ cuối: ${sched.lastActionState}",
                    color = TextTertiary.copy(alpha = 0.7f), fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

