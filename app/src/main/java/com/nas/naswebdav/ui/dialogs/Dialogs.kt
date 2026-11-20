@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.R
import com.nas.naswebdav.ui.components.NasModalBottomSheet
import com.nas.naswebdav.ui.components.NasBottomSheetHandle
import com.nas.naswebdav.ui.components.NasGradientButton
import com.nas.naswebdav.ui.components.NasAlertDialog
import com.nas.naswebdav.ui.components.NasLoadingSpinner
import com.nas.naswebdav.ui.screens.WebDavCachedThumbnail
import com.nas.naswebdav.ui.screens.AccentOrange
import com.nas.naswebdav.ui.screens.AccentRed
import com.nas.naswebdav.ui.screens.AccentGreen
import com.nas.naswebdav.ui.screens.AccentBlue
import com.nas.naswebdav.ui.screens.AccentCyan
import com.nas.naswebdav.ui.screens.AccentPurple
import com.nas.naswebdav.ui.screens.AccentPink
import com.nas.naswebdav.ui.screens.DarkCard
import com.nas.naswebdav.ui.screens.DarkCardHover
import com.nas.naswebdav.ui.screens.DarkSurface
import com.nas.naswebdav.ui.screens.DarkElevated
import com.nas.naswebdav.ui.screens.TextPrimary
import com.nas.naswebdav.ui.screens.TextSecondary
import com.nas.naswebdav.ui.screens.TextTertiary

/**
 * Dialogs.kt — Phase 7c.3 file-level provenance.
 *
 * All 30+ dialog composables in this file read state via `viewModel.xxx` which
 * delegates to the appropriate Domain VM (Phase 7a):
 *   - AutoBackupDialog / UsbImportDialog / SleepScheduleDialog → AutoBackupVM
 *   - DuplicateConfigDialog / FilesDialog / OrganizeLegacyDialog / ConfigDialog → SmartToolsVM
 *   - DiskHealthDialog / NasInsightsDialog / FilePropertiesDialog → SystemMonitorVM
 *   - LanWhitelistDialog / DockerDialog / SmartDiskDialog / BandwidthDialog → DeviceMgmtVM
 *   - LivestreamRecordDialog → LivestreamVM
 *   - SystemLogDialog → DeviceMgmtVM (systemLogsList)
 *
 * Direct migration to LocalXxxVM.current will happen in the Group 3 cleanup pass.
 * For now, additive annotation only — minimal blast radius for the largest dialog file.
 */

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import com.nas.naswebdav.utils.CrashLogExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit


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
    AppStatusDialog(
        type = DialogType.WARNING,
        message = stringResource(R.string.reboot_confirm_message),
        onConfirm = { onDismiss(); onConfirm() },
        onDismiss = onDismiss
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
    AppStatusDialog(
        type = DialogType.WARNING,
        message = stringResource(R.string.shutdown_confirm_message),
        onConfirm = { onDismiss(); onConfirm() },
        onDismiss = onDismiss
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
                Icon(Icons.Default.CloudDownload, "Tải xuống", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dl_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0 to (Icons.Default.Link to stringResource(R.string.dl_link_tab)),
                    1 to (Icons.Default.UploadFile to stringResource(R.string.dl_torrent_tab)),
                ).forEach { (idx, pair) ->
                    val (tabIcon, label) = pair
                    val selected = tabIndex == idx
                    FilterChip(
                        selected = selected,
                        onClick = { tabIndex = idx },
                        leadingIcon = { Icon(tabIcon, "Tab $tabIndex", modifier = Modifier.size(16.dp)) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).height(34.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when (tabIndex) {
                0 -> {
                    Text(stringResource(R.string.dl_link_description), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    com.nas.naswebdav.ui.components.CompactTextField(
                        value = downloadLink,
                        onValueChange = onLinkChange,
                        placeholder = stringResource(R.string.dl_link_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    com.nas.naswebdav.ui.components.NasGradientButton(
                        onClick = onConfirm,
                        text = stringResource(R.string.dl_add_queue),
                        enabled = downloadLink.isNotBlank(),
                        height = 40.dp,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                1 -> {
                    Text(stringResource(R.string.dl_pick_torrent_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onPickTorrentFile() },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.UploadFile, "Chọn tệp torrent", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dl_pick_torrent_button), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        stringResource(R.string.dl_pick_torrent_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { Text(stringResource(R.string.dl_cancel), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
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
    NasAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.wol_title),
        content = {
            Text(stringResource(R.string.wol_description), fontSize = 13.sp, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            com.nas.naswebdav.ui.components.CompactTextField(
                value = macAddress,
                onValueChange = onMacChange,
                placeholder = stringResource(R.string.wol_mac_placeholder),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmText = stringResource(R.string.wol_confirm),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
    )
}

// ====================================================================
// DIALOG S.M.A.R.T VÀ TEST TỐC ĐỘ Ổ CỨNG
// ====================================================================
@Composable
fun SmartDiskDialog(
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HealthAndSafety, "S.M.A.R.T", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.minimumInteractiveComponentSize())
                Spacer(Modifier.width(8.dp))
                Text("Chẩn đoán Ổ cứng", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Thông tin S.M.A.R.T
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCardHover.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Text("Trạng thái S.M.A.R.T", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Trạng thái:", fontSize = 13.sp)
                            Text(deviceVM.smartInfo.status, color = if (deviceVM.smartInfo.status == "PASSED") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Nhiệt độ ổ cứng:", fontSize = 13.sp)
                            Text(deviceVM.smartInfo.temperature, color = AccentOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                // Test tốc độ Read/Write
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCardHover.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                        Text("Kiểm tra tốc độ đọc/ghi", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Ghi (Write):", fontSize = 13.sp)
                            Text(deviceVM.speedTestResult.writeSpeed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Đọc (Read):", fontSize = 13.sp)
                            Text(deviceVM.speedTestResult.readSpeed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }

                        if (deviceVM.lastAutoSpeedTime > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(deviceVM.lastAutoSpeedTime)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        com.nas.naswebdav.ui.components.NasGradientButton(
                            onClick = { deviceVM.runSpeedTest() },
                            text = if (deviceVM.isTestingSpeed) "Đang kiểm tra..." else "Bắt đầu kiểm tra",
                            modifier = Modifier,
                            enabled = !deviceVM.isTestingSpeed,
                            gradientColors = if (deviceVM.isTestingSpeed) listOf(TextTertiary, TextSecondary) else listOf(AccentCyan, AccentCyan, AccentGreen),
                            interactionSource = interactionSource,
                            customContent = {
                                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (deviceVM.isTestingSpeed) {
                                            NasLoadingSpinner(size = 24.dp, color = TextPrimary, strokeWidth =  2.dp)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(if (deviceVM.isTestingSpeed) "Đang kiểm tra..." else "Bắt đầu kiểm tra", color = TextPrimary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBackupDialog(
    context: Context,
    isAutoBackupEnabled: Boolean,
    isAutoBackupRunning: Boolean,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    deleteAfterBackup: Boolean,
    onDeleteAfterBackupChange: (Boolean) -> Unit,
    onSaveAndSchedule: () -> Unit,
    onTriggerManualSync: () -> Unit,
    onCancelSync: () -> Unit,
    onDismiss: () -> Unit
) {
    val autoBackupVM = LocalAutoBackupVM.current

    // --- Schedule state (local copies synced with VM) ---
    var frequencyExpanded by remember { mutableStateOf(false) }
    val frequencyOptions = listOf("daily" to "Hàng ngày", "weekly" to "Hàng tuần", "monthly" to "Hàng tháng")

    // Fetch schedule from server on first open
    LaunchedEffect(Unit) {
        autoBackupVM.fetchBackupSchedule()
    }

    // Observe VM state directly
    val schedule = autoBackupVM.backupSchedule
    val scheduleMessage = autoBackupVM.backupScheduleMessage

    LaunchedEffect(scheduleMessage) {
        if (scheduleMessage.isNotBlank()) {
            Toast.makeText(context, scheduleMessage, Toast.LENGTH_SHORT).show()
        }
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Sync, "Sao lưu tự động", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sao lưu tự động", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
            }

            Text("Tự động sao lưu ảnh lên NAS mỗi khi cắm sạc và có kết nối Wi-Fi.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onAutoBackupEnabledChange(!isAutoBackupEnabled) }.padding(vertical = 2.dp)) {
                Switch(
                    checked = isAutoBackupEnabled,
                    onCheckedChange = onAutoBackupEnabledChange,
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = DarkCard
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isAutoBackupEnabled) "Đã bật" else "Đã tắt", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (isAutoBackupEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(4.dp))

            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = "Thông tin", tint = AccentGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Các tệp sẽ được lưu và giữ nguyên cấu trúc thư mục của máy vào trong thư mục /AutoBackup/ trên NAS.",
                        fontSize = 12.sp, color = AccentGreen, lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
            Spacer(Modifier.height(6.dp))

            Text("Chế độ sao lưu:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDeleteAfterBackupChange(false) }.padding(vertical = 2.dp)) {
                RadioButton(
                    selected = !deleteAfterBackup,
                    onClick = { onDeleteAfterBackupChange(false) },
                    modifier = Modifier.scale(0.9f),
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.tertiary, unselectedColor = MaterialTheme.colorScheme.outline)
                )
                Column {
                    Text("Chỉ Sao lưu (Copy)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (!deleteAfterBackup) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Giữ lại ảnh gốc trên điện thoại.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDeleteAfterBackupChange(true) }.padding(vertical = 2.dp)) {
                RadioButton(
                    selected = deleteAfterBackup,
                    onClick = { onDeleteAfterBackupChange(true) },
                    modifier = Modifier.scale(0.9f),
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error, unselectedColor = MaterialTheme.colorScheme.outline)
                )
                Column {
                    Text("Sao lưu & Giải phóng (Move)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (deleteAfterBackup) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Tự động xóa ảnh/video trên điện thoại sau khi đã xác minh có trên NAS.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            if (deleteAfterBackup && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                val permCtx = androidx.compose.ui.platform.LocalContext.current
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${permCtx.packageName}")
                            )
                            permCtx.startActivity(intent)
                        }.onFailure {
                            runCatching {
                                permCtx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Cấp quyền Quản lý tất cả tệp để tự xóa nguồn", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Buttons Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isAutoBackupRunning) {
                    Button(
                        onClick = onCancelSync,
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Stop, "Dừng đồng bộ", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(4.dp))
                        Text("DỪNG", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onTriggerManualSync,
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkCardHover),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ĐỒNG BỘ", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                NasGradientButton(
                    onClick = { onSaveAndSchedule(); onDismiss() },
                    text = "LƯU",
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // ─── Schedule Section ───
            androidx.compose.animation.AnimatedVisibility(visible = isAutoBackupEnabled) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, "Lịch trình sao lưu", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(id = R.string.backup_schedule_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (autoBackupVM.backupScheduleMessage.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                autoBackupVM.backupScheduleMessage,
                                fontSize = 12.sp,
                                color = if (autoBackupVM.backupScheduleMessage.startsWith("Lỗi"))
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // ── Frequency dropdown ──
                    Text(stringResource(id = R.string.backup_schedule_frequency), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    ExposedDropdownMenuBox(
                        expanded = frequencyExpanded,
                        onExpandedChange = { frequencyExpanded = !frequencyExpanded }
                    ) {
                        val selectedLabel = frequencyOptions.find { it.first == schedule.frequency }?.second ?: "Hàng tuần"
                        OutlinedTextField(
                            value = selectedLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frequencyExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = DarkCardHover,
                                unfocusedContainerColor = DarkCardHover
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = frequencyExpanded,
                            onDismissRequest = { frequencyExpanded = false }
                        ) {
                            frequencyOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = if (value == schedule.frequency) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (value == schedule.frequency) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        frequencyExpanded = false
                                        autoBackupVM.saveBackupSchedule(schedule.copy(frequency = value))
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // ── Hour slider ──
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(id = R.string.backup_schedule_hour), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(id = R.string.backup_schedule_hour_format, schedule.hour),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Drag handle with local state, persist on release
                    var hourDragValue by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = hourDragValue ?: schedule.hour.toFloat(),
                        onValueChange = { hourDragValue = it },
                        onValueChangeFinished = {
                            val newHour = (hourDragValue ?: schedule.hour.toFloat()).toInt().coerceIn(0, 23)
                            hourDragValue = null
                            if (newHour != schedule.hour) {
                                autoBackupVM.saveBackupSchedule(schedule.copy(hour = newHour))
                            }
                        },
                        valueRange = 0f..23f,
                        steps = 22,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    // +/- buttons for precise hour control
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val newHour = (schedule.hour - 1).coerceIn(0, 23)
                                autoBackupVM.saveBackupSchedule(schedule.copy(hour = newHour))
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .background(DarkCardHover, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.Remove, "Giảm giờ", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(id = R.string.backup_schedule_hour_format, schedule.hour),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.widthIn(min = 52.dp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.width(12.dp))
                        IconButton(
                            onClick = {
                                val newHour = (schedule.hour + 1).coerceIn(0, 23)
                                autoBackupVM.saveBackupSchedule(schedule.copy(hour = newHour))
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .background(DarkCardHover, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.Add, "Tăng giờ", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // ── Retention: Vĩnh viễn (Lưu an toàn trên NAS) ──
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCardHover.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AllInclusive, contentDescription = "Tất cả", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Thời gian lưu trữ: Vĩnh viễn", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Dữ liệu sao lưu được bảo quản vĩnh viễn trên ổ cứng NAS", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

data class LogGroup(
    val module: String,
    val count: Int,
    val lastType: String,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val logs: List<SystemLog>
)

fun groupConsecutiveLogs(logs: List<SystemLog>): List<LogGroup> {
    if (logs.isEmpty()) return emptyList()
    val groups = mutableListOf<LogGroup>()
    var currentLogs = mutableListOf(logs.first())
    for (i in 1 until logs.size) {
        val log = logs[i]
        if (log.module == currentLogs.last().module) {
            currentLogs.add(log)
        } else {
            groups.add(
                LogGroup(
                    module = currentLogs.first().module,
                    count = currentLogs.size,
                    lastType = currentLogs.last().type,
                    firstTimestamp = currentLogs.first().timestamp,
                    lastTimestamp = currentLogs.last().timestamp,
                    logs = currentLogs.toList()
                )
            )
            currentLogs = mutableListOf(log)
        }
    }
    groups.add(
        LogGroup(
            module = currentLogs.first().module,
            count = currentLogs.size,
            lastType = currentLogs.last().type,
            firstTimestamp = currentLogs.first().timestamp,
            lastTimestamp = currentLogs.last().timestamp,
            logs = currentLogs.toList()
        )
    )
    return groups
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Assignment, "Nhật ký hệ thống", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nhật ký hệ thống", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        shareScope.launch {
                            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                CrashLogExporter.exportToFile(context, com.nas.naswebdav.NasApplication.instance.database)
                            }
                            if (path == null) {
                                Toast.makeText(context, "Không thể xuất log lỗi", Toast.LENGTH_SHORT).show()
                            } else {
                                val file = java.io.File(path)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    context.packageName + ".fileprovider",
                                    file
                                )
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "NAS WebDAV crash log")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = Intent.createChooser(sendIntent, "Chia sẻ log lỗi").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooser)
                            }
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Chia sẻ log lỗi", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                if (deviceVM.systemLogsList.isNotEmpty()) {
                    IconButton(onClick = { deviceVM.clearSystemLogs() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            CrashReportingSection()

            if (deviceVM.systemLogsList.isEmpty()) {
                Text("Chưa có dữ liệu nhật ký nào.", modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
            } else {
                val logGroups = groupConsecutiveLogs(deviceVM.systemLogsList)
                val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = logGroups, key = { "${it.module}_${it.firstTimestamp}" }) { group ->
                        val groupColor = when (group.lastType) {
                            "SUCCESS" -> MaterialTheme.colorScheme.tertiary
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARNING" -> AccentOrange
                            else -> MaterialTheme.colorScheme.primary
                        }
                        val groupIcon = when (group.lastType) {
                            "SUCCESS" -> Icons.Default.CheckCircle
                            "ERROR" -> Icons.Default.Error
                            "WARNING" -> Icons.Default.Warning
                            else -> Icons.Default.Info
                        }
                        val isExpanded = expandedGroups["${group.module}_${group.firstTimestamp}"] == true
                        val timeRange = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(group.firstTimestamp) +
                            if (group.count > 1) " – " + com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(group.lastTimestamp) else ""
                        val lastMsg = formatLogMessage(group.logs.last().message)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { expandedGroups["${group.module}_${group.firstTimestamp}"] = !isExpanded }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(groupIcon, null, tint = groupColor, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(group.module, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = groupColor)
                                                if (group.count > 1) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Surface(
                                                        color = groupColor.copy(alpha = 0.15f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Text(
                                                            "(x${group.count} thông báo)",
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                            fontSize = 12.sp,
                                                            color = groupColor,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            Text(timeRange, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(lastMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.85f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    if (group.count > 1) {
                                        Icon(
                                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = "Mở rộng chi tiết",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                                        )
                                    }
                                }
                                if (isExpanded && group.count > 1) {
                                    HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                                    group.logs.reversed().forEach { log ->
                                        val subColor = when (log.type) {
                                            "SUCCESS" -> MaterialTheme.colorScheme.tertiary
                                            "ERROR" -> MaterialTheme.colorScheme.error
                                            "WARNING" -> AccentOrange
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                        val subIcon = when (log.type) {
                                            "SUCCESS" -> Icons.Default.CheckCircle
                                            "ERROR" -> Icons.Default.Error
                                            "WARNING" -> Icons.Default.Warning
                                            else -> Icons.Default.Info
                                        }
                                        val subTime = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(log.timestamp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(subIcon, "Loại nhật ký", tint = subColor, modifier = Modifier.size(12.dp).padding(top = 3.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Column {
                                                Text(subTime, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                                Text(formatLogMessage(log.message), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.75f))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
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
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewInAr, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.minimumInteractiveComponentSize())
                Spacer(Modifier.width(8.dp))
                Text("Quản lý Docker", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (deviceVM.isFetchingDocker) {
                    NasLoadingSpinner(size = 24.dp, color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { deviceVM.loadDockerContainers() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.Refresh, "Làm mới", tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        text = {
            if (deviceVM.dockerContainers.isEmpty() && !deviceVM.isFetchingDocker) {
                Text("Không tìm thấy Container nào đang tồn tại.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = deviceVM.dockerContainers, key = { it.id }) { container ->
                        val isRunning = container.status.lowercase() == "running"
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkCardHover.copy(alpha = 0.5f)),
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
                                        .background(if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(container.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(if (isRunning) "Đang chạy" else "Đã dừng", fontSize = 12.sp, color = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
                                }

                                if (isRunning) {
                                    IconButton(onClick = { deviceVM.controlDockerContainer("restart", container.name) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                        Icon(Icons.Default.RestartAlt, "Khởi động lại", tint = AccentOrange, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { deviceVM.controlDockerContainer("stop", container.name) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                        Icon(Icons.Default.Stop, "Dừng", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                } else {
                                    IconButton(onClick = { deviceVM.controlDockerContainer("start", container.name) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                        Icon(Icons.Default.PlayArrow, "Bật", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.minimumInteractiveComponentSize())
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
                Text("Đóng", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

