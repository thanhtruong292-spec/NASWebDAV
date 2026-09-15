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
                Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dl_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0 to ("🔗" to stringResource(R.string.dl_link_tab)),
                    1 to ("📁" to stringResource(R.string.dl_torrent_tab)),
                ).forEach { (idx, pair) ->
                    val (emoji, label) = pair
                    val selected = tabIndex == idx
                    FilterChip(
                        selected = selected,
                        onClick = { tabIndex = idx },
                        label = { Text("$emoji $label", fontSize = 12.sp) },
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
                        Icon(Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dl_pick_torrent_button), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        stringResource(R.string.dl_pick_torrent_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp
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
                Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.minimumInteractiveComponentSize())
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
                                fontSize = 10.sp,
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

// ====================================================================
// EXCLUSIVE PANEL STATE cho 3 section trong DialogsLivestreamRecordDialog:
// "watchlist" (THEO DÕI TIKTOK LIVE) | "exclude" (Thoi gian loai tru) | "active" (Dang ghi hinh)
// Chỉ 1 section mở cùng lúc -> tối ưu diện tích màn hình.
// ====================================================================
object DialogsLivestreamPanelState {
    val current: androidx.compose.runtime.MutableState<String?> =
        androidx.compose.runtime.mutableStateOf(null)

    fun toggle(panelId: String) {
        current.value = if (current.value == panelId) null else panelId
    }
}

private fun DialogsNormalizeTikTokWatchMessage(message: String): String {
    if (message.isBlank()) return ""
    return message
        .replace("Đã ghi phiên live này; không tạo file thứ hai cho tới khi user offline.", "Đã ghi phiên live này; không tạo tệp thứ hai cho tới khi người dùng ngoại tuyến.")
        .replace("Chưa xác nhận offline:", "Chưa xác nhận ngoại tuyến:")
        .replace("đã offline", "đã ngoại tuyến")
        .replace("mở khoá phiên live tiếp theo", "mở khoá phiên live tiếp theo")
        .replace("khong", "không")
        .replace("Khong", "Không")
        .replace("chua", "chưa")
        .replace("Chua", "Chưa")
        .replace("dang", "đang")
        .replace("Dang", "Đang")
        .replace("loi", "lỗi")
        .replace("Loi", "Lỗi")
        .replace("phien", "phiên")
        .replace("tao", "tạo")
        .replace("thu hai", "thứ hai")
        .replace("toi khi", "tới khi")
        .replace("offline", "ngoại tuyến")
}

// ====================================================================
// DIALOG CẤU HÌNH AUTO-BACKUP
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogsTikTokLiveWatchSection(
    livestreamVM: com.nas.naswebdav.livestream.LivestreamViewModel,
    context: Context,
    users: List<com.nas.naswebdav.TikTokLiveWatchUser>,
    newUsername: String,
    onUsernameChange: (String) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    var expandedUserName by remember { mutableStateOf<String?>(null) }
    var pendingDeleteUser by remember { mutableStateOf<com.nas.naswebdav.TikTokLiveWatchUser?>(null) }
    val snackbarScope = rememberCoroutineScope()
    // Các panel theo dõi TikTok / thời gian loại trừ / đang ghi hình — chi 1 panel mo
    // cung luc thong qua DialogsLivestreamPanelState. Mac dinh tat ca dong (current.value == null).
    val listExpanded = DialogsLivestreamPanelState.current.value == "watchlist"
    val excludeExpanded = DialogsLivestreamPanelState.current.value == "exclude"

    pendingDeleteUser?.let { target ->
        DialogsAppStatusDialog(
            type = DialogType.CONFIRM,
            message = "Bạn có chắc chắn muốn xoá @${target.username} khỏi danh sách theo dõi TikTok Live không?",
            onConfirm = {
                val deletedUsername = target.username
                pendingDeleteUser = null
                if (expandedUserName == deletedUsername) expandedUserName = null
                livestreamVM.removeTikTokLiveWatchUser(context, deletedUsername)
                snackbarScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Đã xoá @$deletedUsername khỏi danh sách theo dõi.",
                        actionLabel = "Hoàn tác",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        livestreamVM.addTikTokLiveWatchUser(context, deletedUsername)
                    }
                }
            },
            onDismiss = { pendingDeleteUser = null }
        )
    }

    HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
    Spacer(Modifier.height(4.dp))
    // Header clickable -> toggle list user. Hien icon expand/collapse + count.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (users.isNotEmpty()) Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { DialogsLivestreamPanelState.toggle("watchlist") } else Modifier
            )
    ) {
        Text("♪", fontSize = 20.sp, color = AccentRed)
        Spacer(Modifier.width(8.dp))
        Text("THEO DÕI TIKTOK LIVE", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text("${users.size} người dùng", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (users.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(
                if (listExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (listExpanded) "Ẩn danh sách" else "Mở danh sách",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        com.nas.naswebdav.ui.components.CompactTextField(
            value = newUsername,
            onValueChange = onUsernameChange,
            placeholder = "Nhập tài khoản TikTok",
            leadingIcon = { Text("@", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            trailingIcon = {
                if (newUsername.isNotBlank()) {
                    IconButton(
                        onClick = { onUsernameChange("") },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Xoá nội dung nhập",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            accentColor = AccentRed,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = {
                val cleanUsername = newUsername.trim().removePrefix("@")
                if (users.any { it.username.equals(cleanUsername, ignoreCase = true) }) {
                    onUsernameChange("")
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Người dùng @$cleanUsername đã tồn tại trong danh sách theo dõi.",
                            duration = SnackbarDuration.Short
                        )
                    }
                } else {
                    livestreamVM.addTikTokLiveWatchUser(context, cleanUsername)
                    onUsernameChange("")
                }
            },
            enabled = newUsername.isNotBlank() && !livestreamVM.isLoadingTikTokWatch,
            modifier = Modifier.height(40.dp).widthIn(min = 80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkCardHover),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Thêm", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    val watchError = livestreamVM.tiktokLiveWatchError ?: ""
    if (watchError.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(watchError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
    val daemonColor = if (livestreamVM.tiktokWatchDaemonRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        val checkLine = buildString {
            append(if (livestreamVM.tiktokWatchDaemonRunning) "Watcher NAS đang chạy" else "Watcher NAS chưa phản hồi")
            if (livestreamVM.tiktokWatchDaemonLastTick.isNotEmpty()) append(" • Lần kiểm tra cuối: ${livestreamVM.tiktokWatchDaemonLastTick}")
        }
        Text(
            checkLine,
            color = daemonColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (livestreamVM.tiktokWatchDaemonSummary.isNotEmpty()) {
            val summaryText = livestreamVM.tiktokWatchDaemonSummary
            Text(
                buildAnnotatedString {
                    append(summaryText)
                    Regex("""\d+\s+user|\d+\s+đang ghi|\d+\s+vừa(?:\s+mới)?\s+bắt đầu""").findAll(summaryText).forEach { match ->
                        addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            start = match.range.first,
                            end = match.range.last + 1
                        )
                    }
                },
                color = daemonColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    // Banner trang thai cookies — chi hien khi co van de de tranh nhieu UI.
    val cookiesStatus = livestreamVM.tiktokCookiesStatus
    if (cookiesStatus == "missing" || cookiesStatus == "expired" || cookiesStatus == "revoked") {
        Spacer(Modifier.height(4.dp))
        val (bannerBg, bannerFg, label) = when (cookiesStatus) {
            "missing" -> Triple(AccentOrange.copy(alpha = 0.2f), MaterialTheme.colorScheme.error, "Chưa có cookies.txt")
            "expired" -> Triple(AccentRed.copy(alpha = 0.2f), MaterialTheme.colorScheme.error, "Cookies TikTok hết hạn")
            else -> Triple(AccentRed.copy(alpha = 0.2f), MaterialTheme.colorScheme.error, "Cookies TikTok bị thu hồi")
        }
        Row(
            Modifier.fillMaxWidth().background(bannerBg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = bannerFg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = bannerFg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val detail = livestreamVM.tiktokCookiesMessage
                if (detail.isNotEmpty()) {
                    Text(detail, color = bannerFg.copy(alpha = 0.85f), fontSize = 11.sp)
                }
                Text(
                    "Hãy đăng nhập TikTok trên trình duyệt, export cookies.txt mới rồi đặt vào WebDAV root (cookies.txt) để watcher hoạt động trở lại.",
                    color = bannerFg.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
            }
        }
    }
    // List user theo doi — chi hien khi listExpanded == true. Mac dinh an de tiet kiem
    // khong gian man hinh khi co nhieu user; user bam header de mo.
    androidx.compose.animation.AnimatedVisibility(visible = users.isNotEmpty() && listExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            users.forEach { user ->
                key(user.username) {
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                pendingDeleteUser = user
                                false
                            } else false
                        },
                        positionalThreshold = { totalDistance -> totalDistance * 0.35f }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            val bgColor by animateColorAsState(
                                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.55f) else Color.Transparent,
                                label = "swipeBg"
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(bgColor, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Xoá",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(DarkCardHover, RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    expandedUserName = if (expandedUserName == user.username) null else user.username
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            val isUserExpanded = expandedUserName == user.username
                            val statusLabel = when (user.status) {
                                "recording" -> "Đang live - đã tự ghi"
                                "recorded" -> "Đã ghi phiên này"
                                "excluded" -> "Đang trong giờ loại trừ"
                                "error" -> "Lỗi kiểm tra"
                                else -> "Đang theo dõi"
                            }
                            val displayLastError = DialogsNormalizeTikTokWatchMessage(user.lastError)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "@${user.username}",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                DialogsTikTokWatchStatusChip(
                                    status = user.status,
                                    label = statusLabel,
                                    modifier = Modifier.widthIn(min = 116.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    if (isUserExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isUserExpanded) "Thu gọn người dùng" else "Mở chi tiết người dùng",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            val checkLiveLine = buildString {
                                if (user.lastCheck.isNotEmpty()) append("Kiểm tra: ${user.lastCheck}")
                                if (user.lastLive.isNotEmpty()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append("Live cuối: ${user.lastLive}")
                                }
                            }
                            if (checkLiveLine.isNotEmpty()) {
                                Text(checkLiveLine, color = MaterialTheme.colorScheme.outline, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            androidx.compose.animation.AnimatedVisibility(visible = isUserExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(DarkCardHover, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("THÔNG TIN THEO DÕI", color = AccentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text(statusLabel, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    if (user.jobId.isNotEmpty()) {
                                        Text("Tác vụ ghi hình: ${user.jobId}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                    if (user.lastCheck.isNotEmpty() || user.lastLive.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (user.lastCheck.isNotEmpty()) {
                                                Text(
                                                    "Kiểm tra lần cuối: ${user.lastCheck}",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            } else {
                                                Spacer(Modifier.weight(1f))
                                            }
                                            if (user.lastLive.isNotEmpty()) {
                                                Text(
                                                    "Phát hiện live cuối: ${user.lastLive}",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = TextAlign.End,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                    if (displayLastError.isNotEmpty()) {
                                        Text("CHI TIẾT LỖI", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        androidx.compose.foundation.layout.Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 220.dp)
                                                .verticalScroll(rememberScrollState())
                                                .background(DarkCardHover, RoundedCornerShape(8.dp))
                                                .padding(8.dp)
                                        ) {
                                            androidx.compose.foundation.text.selection.SelectionContainer {
                                                Text(
                                                    displayLastError,
                                                    color = AccentOrange,
                                                    fontSize = 11.sp,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
    }
    Spacer(Modifier.height(4.dp))
    Text("Thêm tài khoản TikTok để tự động dò và ghi khi live", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
    Spacer(Modifier.height(4.dp))
    // Header "Thoi gian loai tru" — clickable, hien icon expand/collapse.
    // Switch tat/bat de o header de user co the bat/tat khong can mo panel.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { DialogsLivestreamPanelState.toggle("exclude") }
    ) {
        Text("☾", fontSize = 18.sp, color = AccentOrange)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Thời gian loại trừ", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Không kiểm tra livestream trong khoảng giờ này", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(
            checked = livestreamVM.tiktokExcludeEnabled,
            onCheckedChange = { livestreamVM.updateTikTokLiveWatchSettings(context, it) }
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (excludeExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (excludeExpanded) "Ẩn" else "Mở",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
    androidx.compose.animation.AnimatedVisibility(visible = excludeExpanded) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Từ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = livestreamVM.tiktokExcludeStart,
                    onValueChange = { if (it.length <= 5) livestreamVM.updateTikTokLiveWatchSettings(context, livestreamVM.tiktokExcludeEnabled, it, livestreamVM.tiktokExcludeEnd) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    accentColor = AccentRed,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(80.dp)
                )
                Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Text("Đến", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = livestreamVM.tiktokExcludeEnd,
                    onValueChange = { if (it.length <= 5) livestreamVM.updateTikTokLiveWatchSettings(context, livestreamVM.tiktokExcludeEnabled, livestreamVM.tiktokExcludeStart, it) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    accentColor = AccentRed,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(80.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DialogsTikTokWatchStatusChip(
    status: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val normalizedStatus = status.lowercase()
    val (containerColor, borderColor, textColor) = when (normalizedStatus) {
        "recording" -> Triple(
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.tertiary
        )
        "recorded" -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.primary
        )
        "excluded" -> Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
            AccentOrange
        )
        "error" -> Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
            AccentRed
        )
        else -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            TextSecondary
        )
    }

    Box(
        modifier = modifier
            .widthIn(max = 176.dp)
            .clip(RoundedCornerShape(50.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(50.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
                Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
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
                    Icon(Icons.Default.Info, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Các tệp sẽ được lưu và giữ nguyên cấu trúc thư mục của máy vào trong thư mục /AutoBackup/ trên NAS.",
                        fontSize = 11.sp, color = AccentGreen, lineHeight = 14.sp
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
                    Text("Giữ lại ảnh gốc trên điện thoại.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
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
                    Text("Tự động xóa ảnh trên điện thoại sau khi lên NAS.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
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
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
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
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
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
                                fontSize = 10.sp,
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
                            Icon(Icons.Default.AllInclusive, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Thời gian lưu trữ: Vĩnh viễn", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Dữ liệu sao lưu được bảo quản vĩnh viễn trên ổ cứng NAS", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
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
                Icon(Icons.Default.Assignment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
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
                                                            fontSize = 9.sp,
                                                            color = groupColor,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            Text(timeRange, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(lastMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.85f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    if (group.count > 1) {
                                        Icon(
                                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
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
                                            Icon(subIcon, null, tint = subColor, modifier = Modifier.size(12.dp).padding(top = 3.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Column {
                                                Text(subTime, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                                Text(formatLogMessage(log.message), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.75f))
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
                                    Text(if (isRunning) "Đang chạy" else "Đã dừng", fontSize = 11.sp, color = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
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





// ════════════════════════════════════════════════════════════════════════════
// AppStatusDialog + DialogType enum (từ ui.components.AppStatusDialog)
// ════════════════════════════════════════════════════════════════════════════

@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_THING")
@Composable
fun DialogsAppStatusDialog(type: DialogType, message: String, onConfirm: (() -> Unit)? = null, onDismiss: () -> Unit) {
    AppStatusDialog(type, message, onConfirm, onDismiss)
}

// ════════════════════════════════════════════════════════════════════════════
// SharedComponents — BiometricLockScreen + NotificationDialog
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun DialogsBiometricLockScreen(activity: androidx.fragment.app.FragmentActivity, onAuthenticated: () -> Unit, onFallbackToLogin: () -> Unit) {
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
    Box(modifier = Modifier.fillMaxSize().background(DarkSurface).pointerInput(Unit) { detectTapGestures { authenticate() } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = authenticate, modifier = Modifier.size(140.dp)) {
                Icon(Icons.Default.Fingerprint, contentDescription = "Quét vân tay để mở khóa", modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (authError.isNotEmpty()) { Spacer(Modifier.height(16.dp)); Text(authError, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        }
    }
}

@Composable
fun DialogsNotificationDialog(title: String, message: String, icon: ImageVector, iconColor: Color, onDismiss: () -> Unit) {
    LaunchedEffect(key1 = title, key2 = message) { kotlinx.coroutines.delay(3000); onDismiss() }
    val type = when {
        title.contains("Lỗi", true) || title.contains("Thất bại", true) -> DialogType.ERROR
        title.contains("Cảnh báo", true) -> DialogType.WARNING
        else -> DialogType.SUCCESS
    }
    DialogsAppStatusDialog(
        type = type,
        message = message,
        onDismiss = onDismiss
    )
}

// ════════════════════════════════════════════════════════════════════════════
// IpApprovalDialog — Cảnh báo bảo mật IP lạ
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun DialogsIpApprovalDialog(
    onDismiss: () -> Unit
) {
    // pendingIpAddress/approvalMessage/pendingCountryCode → DeviceMgmtVM
    val deviceVM = LocalDeviceManagementVM.current
    val ip = deviceVM.pendingIpAddress; val message = deviceVM.approvalMessage; val countryCode = deviceVM.pendingCountryCode
    val infiniteTransition = rememberInfiniteTransition(label = "shield_pulse")
    val pulseScale by infiniteTransition.animateFloat(1f, 1.15f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(0.7f, 1f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "alpha")
    val isLocal = ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
    val riskColor = if (isLocal) AccentOrange else MaterialTheme.colorScheme.error
    val riskLabel = if (isLocal) "Mạng nội bộ" else "IP ngoài ($countryCode)"
    AlertDialog(onDismissRequest = {}, containerColor = DarkCardHover, shape = RoundedCornerShape(24.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(64.dp).scale(pulseScale).clip(CircleShape).background(Brush.radialGradient(listOf(riskColor.copy(alpha = pulseAlpha * 0.3f), riskColor.copy(alpha = 0.05f)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, null, tint = riskColor.copy(alpha = pulseAlpha), modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("⚠️ CẢNH BÁO BẢO MẬT", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = riskColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Phát hiện thiết bị lạ kết nối", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(colors = CardDefaults.cardColors(containerColor = DarkCard), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ĐỊA CHỈ IP", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(ip.ifEmpty { "Không xác định" }, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Surface(color = riskColor.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) { Text(riskLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = riskColor) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (message.isNotBlank()) { Text(message, fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 18.sp); Spacer(Modifier.height(12.dp)) }
                Card(colors = CardDefaults.cardColors(containerColor = DarkElevated.copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Cho phép: Thêm vào whitelist, cho truy cập NAS", fontSize = 11.sp, color = TextSecondary) }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Chặn: Ban IP vĩnh viễn bằng iptables", fontSize = 11.sp, color = TextSecondary) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { deviceVM.approveDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary, AccentGreen)), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cho phép", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            Button(onClick = { deviceVM.denyDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.error, AccentRed)), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Chặn IP", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
// LanWhitelistDialog — Quản lý danh sách IP/Subnet được truy cập nội bộ
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogsLanWhitelistDialog(
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val ipList = deviceVM.lanWhitelistIps
    val subnetList = deviceVM.lanWhitelistSubnets
    val isLoading = deviceVM.lanWhitelistLoading
    val errorMessage = deviceVM.lanWhitelistError
    val statusMessage = deviceVM.lanWhitelistStatus

    var newEntry by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { deviceVM.loadLanWhitelist() }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).heightIn(max = 600.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.minimumInteractiveComponentSize())
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("LAN Whitelist", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("IP truy cập không cần Tailscale", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it },
                    placeholder = "192.168.1.0/24",
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newEntry.isNotBlank()) {
                            deviceVM.addLanWhitelistEntry(newEntry.trim())
                            newEntry = ""
                        }
                    },
                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp)) }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(statusMessage, fontSize = 12.sp, color = if (statusMessage.startsWith("✅")) MaterialTheme.colorScheme.tertiary else if (statusMessage.startsWith("❌")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    NasLoadingSpinner(size = 24.dp, color = MaterialTheme.colorScheme.tertiary, strokeWidth = 3.dp)
                }
            } else if (errorMessage.isNotBlank()) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            } else if (subnetList.isEmpty() && ipList.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                    Text("Chưa có IP/subnet nào. Thêm để cho phép truy cập LAN.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            } else {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (subnetList.isNotEmpty()) {
                        Text("SUBNET", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        subnetList.forEach { subnet ->
                            key("subnet-$subnet") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { deviceVM.removeLanWhitelistEntry(subnet, true) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(DarkCardHover, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(subnet, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                    if (ipList.isNotEmpty()) {
                        if (subnetList.isNotEmpty()) Spacer(Modifier.height(12.dp))
                        Text("IP", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        ipList.forEach { ip ->
                            key("ip-$ip") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { deviceVM.removeLanWhitelistEntry(ip, false) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(DarkCardHover, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(ip, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
                colors = ButtonDefaults.buttonColors(containerColor = DarkElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ĐÓNG", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// (Đã xoá SmartSyncDialog theo yêu cầu)
@Composable
fun DialogsOrganizeLegacyDialog(onDismiss: () -> Unit) {
    val smartToolsVM = LocalSmartToolsVM.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!smartToolsVM.organizingLegacyRunning) onDismiss() },
        title = { Text("Phân loại video cũ") },
        text = {
            Column {
                if (smartToolsVM.organizingLegacyRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
                    Text("Đang ra lệnh cho NAS dọn dẹp nội bộ...")
                } else if (smartToolsVM.organizingLegacyResult != null) {
                    Text(smartToolsVM.organizingLegacyResult!!)
                } else {
                    Text("Bạn có chắc chắn muốn NAS quét và di chuyển toàn bộ video không phải MP4 (như mpg, flv, mkv, avi...) vào thư mục 'Other Video' không? Thao tác này giúp danh sách video gọn hơn và được xử lý trực tiếp trên NAS.")
                }
            }
        },
        confirmButton = {
            if (!smartToolsVM.organizingLegacyRunning && smartToolsVM.organizingLegacyResult == null) {
                TextButton(onClick = { smartToolsVM.organizeLegacyVideos() }) { Text("Chạy NAS") }
            } else if (smartToolsVM.organizingLegacyResult != null) {
                TextButton(onClick = { smartToolsVM.resetOrganizingLegacy(); onDismiss() }) { Text("Đóng") }
            }
        },
        dismissButton = {
            if (!smartToolsVM.organizingLegacyRunning && smartToolsVM.organizingLegacyResult == null) {
                TextButton(onClick = onDismiss) { Text("Hủy") }
            }
        }
    )
}

@Composable
fun DialogsDuplicateConfigDialog(
    context: android.content.Context,
    onStartScan: (Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val smartToolsVM = LocalSmartToolsVM.current
    var isLightningMode by remember { mutableStateOf(true) }
    var isForceRestartDuplicate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Bolt, null, tint = AccentOrange, modifier = Modifier.size(36.dp)) },
        title = { Text("Cấu hình quét trùng lặp", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Thiết lập hệ thống kiểm tra hàng nghìn tệp trên Server NAS.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                
                // Option 1: Lightning Mode
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isLightningMode = !isLightningMode }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isLightningMode,
                        onCheckedChange = { isLightningMode = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentOrange)
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("⚡ Chế độ nhanh (Khuyến nghị)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isLightningMode) AccentOrange else MaterialTheme.colorScheme.onSurface)
                        Text("Nhanh gấp 100 lần. Bỏ qua phân tích nội dung, chỉ dùng ETag gốc (dung lượng, tên, ngày sửa). Có thể quét rất nhanh tới 500.000 tệp.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 14.sp)
                    }
                }

                // Option 2: Force Restart
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isForceRestartDuplicate = !isForceRestartDuplicate }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isForceRestartDuplicate, onCheckedChange = { isForceRestartDuplicate = it })
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("Quét lại từ đầu", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("Thực hiện quét lại toàn bộ ổ cứng NAS, bỏ qua lịch sử lưu tạm.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))

                // Option 3: Tự động chạy ngầm (Auto Clean)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("🤖 Tự động dọn dẹp (hàng tuần)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                        Text("Chạy nền 7 ngày/lần khi điện thoại đang sạc pin và có Wi-Fi. Tự động chuyển tệp trùng vào thùng rác (.trash), giữ lại tệp có đường dẫn ngắn nhất.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 14.sp)
                    }
                    Switch(
                        checked = smartToolsVM.autoCleanEnabled,
                        onCheckedChange = { smartToolsVM.toggleAutoClean(context, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentBlue, checkedTrackColor = AccentBlue.copy(alpha = 0.5f))
                    )
                }
            }
        },
        confirmButton = {
            com.nas.naswebdav.ui.components.NasGradientButton(
                onClick = {
                    onStartScan(isForceRestartDuplicate, isLightningMode)
                    onDismiss()
                },
                text = "Bắt đầu quét",
                height = 48.dp,
                shape = RoundedCornerShape(16.dp),
                icon = { Text("🚀", fontSize = 16.sp) }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy", color = MaterialTheme.colorScheme.outline) }
        }
    )
}
@Composable
fun DialogsDuplicateFilesDialog(onDismiss: () -> Unit) {
    val smartToolsVM = LocalSmartToolsVM.current
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Column {
                Text("Tệp trùng lặp", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                // BỔ SUNG: Hiển thị tổng số file rác phát hiện được nếu danh sách không trống
                if (smartToolsVM.duplicateFilesList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Phát hiện ${smartToolsVM.duplicateFilesList.size} tệp trùng lặp",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            if (smartToolsVM.duplicateFilesList.isEmpty()) {
                Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = AccentGreen)
            } else {
                // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                // SỬA LỖI: Nhóm theo Hash/Fingerprint thay vì chỉ theo Size để đảm bảo tuyệt đối file có nội dung giống nhau mới nằm chung nhóm
                // FIX-SYNC-G1: chỉ group theo partialHash THẬT (xem DuplicateDialogs) —
                // bỏ fallback size và pseudo-hash LGH_ legacy.
                val groupedDuplicates = remember(smartToolsVM.duplicateFilesList) {
                    smartToolsVM.duplicateFilesList
                        .filter { !it.partialHash.isNullOrEmpty() && !it.partialHash.startsWith("LGH_") }
                        .groupBy { it.partialHash }.values.filter { it.size >= 2 }.toList()
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
                            smartToolsVM.selectedDuplicates.clear()
                            groupedDuplicates.forEach { group ->
                                // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                smartToolsVM.selectedDuplicates.addAll(filesToDelete)
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = AccentBlue)
                        Spacer(Modifier.width(4.dp))
                        Text("Chọn thông minh", fontWeight = FontWeight.Bold, color = AccentBlue)
                    }

                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                        items(items = filteredGroups, key = { it.first().contentLength }) { group ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
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
                                            val isSelected = smartToolsVM.selectedDuplicates.contains(dupFile)
                                            val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                            val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(dupFile.name)
                                            val auth = WebDavManager.currentAuthState().authHeader

                                            Box(
                                                modifier = Modifier
                                                    .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) AccentRed.copy(alpha = 0.2f) else DarkSurface)
                                                    .clickable {
                                                        if (isSelected) smartToolsVM.selectedDuplicates.remove(dupFile)
                                                        else smartToolsVM.selectedDuplicates.add(dupFile)
                                                    }
                                            ) {
                                                // 1. Lớp Ảnh Nền (TỐI ƯU HÓA DB CACHE MỚI CHO TẤT CẢ MEDIA)
                                                if (isImage || isVideo) {
                                                    Box(modifier = Modifier.fillMaxSize()) {
                                                        WebDavCachedThumbnail(url = dupFile.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize())
                                                    }
                                                } else {
                                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                                }

                                                // 2. Lớp phủ đỏ mờ nếu đang được tick chọn xóa
                                                if (isSelected) {
                                                    Box(modifier = Modifier.fillMaxSize().background(AccentRed.copy(alpha = 0.4f)))
                                                }

                                                // 3. Checkbox nằm góc trên phải
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = {
                                                        if (it) smartToolsVM.selectedDuplicates.add(dupFile)
                                                        else smartToolsVM.selectedDuplicates.remove(dupFile)
                                                    },
                                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error, uncheckedColor = MaterialTheme.colorScheme.onSurface)
                                                )

                                                // 4. Tên file + thư mục cha đè ở dưới cùng (Để phân biệt các file)
                                                Column(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .fillMaxWidth()
                                                        .background(DarkSurface.copy(alpha = 0.75f))
                                                        .padding(horizontal = 4.dp, vertical = 3.dp)
                                                ) {
                                                    Text(
                                                        text = dupFile.name,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        lineHeight = 10.sp
                                                    )
                                                    val parentFolder = dupFile.path.substringBeforeLast("/").substringAfterLast("/")
                                                    Text(
                                                        text = "📁 $parentFolder",
                                                        fontSize = 7.sp,
                                                        color = MaterialTheme.colorScheme.outline,
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
                if (smartToolsVM.selectedDuplicates.isNotEmpty()) {
                    TextButton(onClick = { smartToolsVM.deleteSelectedDuplicates(smartToolsVM.selectedDuplicates.toList()) }) {
                        Text("Xóa (${smartToolsVM.selectedDuplicates.size}) mục", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = {
                    onDismiss()
                    smartToolsVM.selectedDuplicates.clear() // Xóa danh sách tick chọn tạm thời khi đóng hộp thoại
                }) { Text("Đóng") }
            }
        }
    )
}



// ====================================================================
// DIALOG TẠO THƯ MỤC MỚI
// ====================================================================
@Composable
fun DialogsCreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    NasAlertDialog(
        onDismissRequest = onDismiss,
        title = "Thư mục mới",
        content = {
            com.nas.naswebdav.ui.components.CompactTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = "Nhập tên thư mục",
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmText = "Tạo",
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { onConfirm(folderName) },
    )
}

// ====================================================================
// DIALOG XÓA NHIỀU TỆP CÙNG LÚC
// ====================================================================
@Composable
fun DialogsMultiDeleteDialog(
    selectedCount: Int,
    isTrash: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    DialogsAppStatusDialog(
        type = DialogType.WARNING,
        message = if (isTrash) "Bạn có chắc chắn muốn xóa vĩnh viễn $selectedCount tệp này không? Hành động này không thể hoàn tác." else "Bạn có chắc chắn muốn đưa $selectedCount tệp này vào Thùng rác?",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

// ════════════════════════════════════════════════════════════════════════════
// DialogsLivestreamRecordDialog — Ghi hinh Livestream TikTok / Facebook / YouTube
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogsLivestreamRecordDialog(
    onDismiss: () -> Unit
) {
    val livestreamVM = LocalLivestreamVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    // Khôi phục trạng thái nếu Worker đang chạy ngầm
    LaunchedEffect(Unit) { livestreamVM.syncLivestreamStateWithServer() }
    // Reset tat ca panel ve trang thai dong khi user mo dialog — moi lan vao se thay
    // giao dien gon, user chu dong bam header de xem section can xem.
    androidx.compose.runtime.DisposableEffect(Unit) {
        DialogsLivestreamPanelState.current.value = null
        onDispose { }
    }
    var liveUrl by remember { mutableStateOf("") }
    var isResolvingTikTokLink by remember { mutableStateOf(false) }
    var newTikTokWatchUser by remember { mutableStateOf("") }
    var livePanelMode by remember { mutableStateOf("record") }
    var selectedQuality by remember { mutableStateOf("best") }
    val activeLivestreams = livestreamVM.activeLivestreams
    val message = livestreamVM.livestreamMessage
    val tiktokWatchUsers = livestreamVM.tiktokLiveWatchUsers

    LaunchedEffect(Unit) { livestreamVM.fetchTikTokLiveWatch(context) }

    // AUTO-PASTE: Đọc clipboard khi dialog mở, tự dán nếu chứa link livestream
    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text ?: ""
        if (clipText.isNotBlank() && listOf("tiktok", "facebook", "fb.watch", "youtube", "youtu.be", "shopee").any { clipText.contains(it, true) }) {
            liveUrl = clipText.trim()
            livePanelMode = "record"
            livestreamVM.clearLivestreamMessage()
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
                                livestreamVM.clearLivestreamMessage()
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
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
    val accentColor = when (activePlatform) { "tiktok" -> AccentRed; "facebook" -> AccentBlue; "youtube" -> AccentRed; else -> AccentOrange }

    // ScrollState chia se cho toan dialog — khi user mo 1 panel thi tu dong scroll
    // de panel content lo ra ngoai cua so visible (khong bi an duoi day man hinh).
    val dialogScrollState = rememberScrollState()
    val tiktokSnackbarHostState = remember { SnackbarHostState() }
    // SheetState voi skipPartiallyExpanded = true — sheet luon o full height, khong
    // bao gio dung lai o half. Khi user mo panel thi sheet con auto expand() de
    // dam bao co du khong gian hien thi content.
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val expandedPanel = DialogsLivestreamPanelState.current.value
    androidx.compose.runtime.LaunchedEffect(expandedPanel) {
        if (expandedPanel != null) {
            // 1) Day sheet len max height (truong hop user mo dialog xong it phat
            //    moi bam panel, sheet co the dang o trang thai chua full)
            try { sheetState.expand() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            // 2) Cho animation expand cua panel ~200ms
            kotlinx.coroutines.delay(220)
            // 3) Scroll dialog content xuong day -> content panel vua mo lo ra het
            dialogScrollState.animateScrollTo(dialogScrollState.maxValue)
        }
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                    Text("Ghi hình Livestream", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Ghi trực tiếp vào NAS HDD", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                if (activeLivestreams.isNotEmpty()) {
                    IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_close), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            // Tab buttons — dung [PillTab] de dam bao consistency voi cac tab khac trong app
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.nas.naswebdav.ui.components.PillTab(
                    selected = livePanelMode == "watch",
                    label = "Theo dõi người dùng",
                    emoji = "👤",
                    accentColor = AccentRed,
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
                DialogsTikTokLiveWatchSection(
                    livestreamVM = livestreamVM,
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
                    livestreamVM.clearLivestreamMessage()
                },
                placeholder = "Dán link livestream — https://www.tiktok.com/@user/live",
                accentColor = accentColor,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (liveUrl.isNotEmpty()) {
                        IconButton(onClick = { liveUrl = "" }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.Clear, contentDescription = "Xóa", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
            
            Text("CHẤT LƯỢNG", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            selectedBorderColor = accentColor.copy(alpha = 0.5f),
                            enabled = true,
                            selected = selected
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
            
            if (livestreamVM.isStartingLivestream) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    NasLoadingSpinner(size = 24.dp, color = accentColor, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(livestreamVM.livestreamMessage.ifEmpty { "Đang kết nối luồng Live..." }, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (message.isNotEmpty()) {
                // FIX: Auto-clear lỗi sau 5 giây để hiện lại nút "BẮT ĐẦU GHI"
                LaunchedEffect(message) {
                    kotlinx.coroutines.delay(5000L)
                    livestreamVM.clearLivestreamMessage()
                }
                Spacer(Modifier.height(6.dp))
                val msgColor = if (message.startsWith("Lỗi")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    message, color = msgColor, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { livestreamVM.clearLivestreamMessage() },
                    textAlign = TextAlign.Center
                )
            } else {
                Spacer(Modifier.height(8.dp))
                com.nas.naswebdav.ui.components.NasGradientButton(
                    onClick = {
                        if (liveUrl.isNotBlank() && !livestreamVM.isStartingLivestream) {
                            val isVOD = liveUrl.contains("/video/") || liveUrl.contains("/watch") || liveUrl.contains("youtu.be") || liveUrl.contains("/t/") || liveUrl.contains("/v/") || liveUrl.contains("/reel")
                            if (isVOD) {
                                livestreamVM.requestSocialDownload(liveUrl.trim(), "Livestream/")
                                onDismiss()
                            } else {
                                livestreamVM.startLivestreamRecord(liveUrl.trim(), selectedQuality)
                            }
                        }
                    },
                    enabled = liveUrl.isNotBlank() && !isResolvingTikTokLink,
                    text = if (isResolvingTikTokLink) "ĐANG LẤY USER..." else "BẮT ĐẦU GHI",
                    height = 42.dp,
                    shape = RoundedCornerShape(10.dp),
                    icon = { Icon(Icons.Default.AddCircle, null, tint = TextPrimary, modifier = Modifier.size(18.dp)) }
                )
            }
            }

            Spacer(Modifier.height(6.dp))

            // --- PHẦN 2: DANH SÁCH CÁC JOB ĐANG GHI ---
            // Ẩn mặc định, bấm header để mở (toggle "active" panel). Khi mở sẽ tự
            // động đóng các panel khác (watchlist + exclude) thông qua DialogsLivestreamPanelState.
            val activeExpanded = DialogsLivestreamPanelState.current.value == "active"
            if (activeLivestreams.isNotEmpty()) {
                HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { DialogsLivestreamPanelState.toggle("active") }
                ) {
                    val pulse = rememberInfiniteTransition(label = "rec_pulse")
                    val alpha by pulse.animateFloat(initialValue = 1f, targetValue = 0.4f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "rec_alpha")
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error.copy(alpha = alpha), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ĐANG GHI HÌNH (${activeLivestreams.size})",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (activeExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (activeExpanded) "Ẩn" else "Mở",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        val jobAccentColor = when (jobPlatform) { "tiktok" -> AccentRed; "facebook" -> AccentBlue; "youtube" -> AccentRed; else -> AccentOrange }

                        Column(
                            Modifier.fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(jobAccentColor.copy(alpha = 0.12f), Color.Transparent)), RoundedCornerShape(10.dp))
                                .border(1.dp, jobAccentColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val pulse = rememberInfiniteTransition(label = "pulse")
                                val alpha by pulse.animateFloat(initialValue = 1f, targetValue = 0.3f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseAlpha")
                                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error.copy(alpha = alpha), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("GHI HÌNH", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                                    Text("Thời gian chạy", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    var localSeconds by remember(job.jobId) { mutableStateOf(job.durationSeconds) }
                                    LaunchedEffect(job.jobId, job.durationSeconds) {
                                        localSeconds = job.durationSeconds
                                    }
                                    LaunchedEffect(job.jobId) {
                                        while (isActive) {
                                            delay(1000)
                                            localSeconds++
                                        }
                                    }
                                    val displayDur = "${localSeconds / 3600}h${String.format(java.util.Locale.US, "%02d", (localSeconds % 3600) / 60)}m${String.format(java.util.Locale.US, "%02d", localSeconds % 60)}s"
                                    Text(displayDur, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                // Cot 2: Toc do (giua, ngang voi 2 cot kia)
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Tốc độ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    Text(job.speed.ifEmpty { "—" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                // Cot 3: Dung luong (align phai)
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text("Dung lượng", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    Text(job.fileSize.ifEmpty { "0 B" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (job.outputFile.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(job.outputFile, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { livestreamVM.stopLivestreamRecord(job.jobId) },
                                modifier = Modifier.fillMaxWidth().height(38.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("DỪNG GHI", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
        com.nas.naswebdav.ui.components.NasSnackbarHost(
            hostState = tiktokSnackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
fun DialogsBiometricSettingsDialog(
    prefsRepo: com.nas.naswebdav.utils.PreferencesRepository,
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val autoBackupVM = LocalAutoBackupVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(prefsRepo.isBiometricEnabled()) }
    var delaySec by remember { mutableStateOf(prefsRepo.getBiometricLockDelaySec()) }
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
                Text("Khóa Sinh trắc học", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            // Availability badge
            val (bioColor, bioText) = when (bioStatus) {
                "available" -> MaterialTheme.colorScheme.tertiary to "Sinh trắc học sẵn sàng (vân tay/khuôn mặt đã đăng ký)"
                "no_hardware" -> MaterialTheme.colorScheme.error to "Thiết bị không hỗ trợ sinh trắc"
                "hw_unavailable" -> MaterialTheme.colorScheme.error to "Phần cứng sinh trắc tạm thời không khả dụng"
                "none_enrolled" -> MaterialTheme.colorScheme.error to "Chưa đăng ký vân tay/khuôn mặt nào. Vào Cài đặt → Sinh trắc để thêm."
                else -> MaterialTheme.colorScheme.onSurfaceVariant to "Trạng thái: $bioStatus"
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
            HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
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
                    Text("Bật khoá sinh trắc", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Khoá khi app vào nền theo thời gian dưới"
                        else "Tắt — app không bao giờ tự khoá",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                    )
                }
            }

            // Delay picker
            Spacer(Modifier.height(5.dp))
            Text("THỜI GIAN CHỜ KHOÁ (sau khi app vào nền)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                                if (isSel) AccentPurple.copy(alpha = 0.15f) else DarkCardHover,
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
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                isSel -> AccentPurple
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NasGradientButton(
                    onClick = {
                        prefsRepo.setBiometricEnabled(enabled)
                        prefsRepo.setBiometricLockDelaySec(delaySec)
                        deviceVM.logUserAction("Security","cập nhật khóa sinh trắc (${if (enabled) "bật" else "tắt"}, trễ ${delaySec}s).")
                        onDismiss()
                    },
                    text = "LƯU",
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    shape = RoundedCornerShape(10.dp),
                    gradientColors = listOf(AccentPurple, AccentPurple.copy(alpha = 0.8f), AccentCyan),
                )
                OutlinedButton(
                    onClick = {
                        // Save first, then trigger lock
                        prefsRepo.setBiometricEnabled(true)
                        prefsRepo.setBiometricLockDelaySec(delaySec)
                        deviceVM.logUserAction("Security","Kích hoạt khoá sinh trắc học cục bộ.")
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
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
fun DialogsBandwidthThrottleDialog(
    prefsRepo: com.nas.naswebdav.utils.PreferencesRepository,
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
    var selected by remember { mutableStateOf(prefsRepo.getUploadSpeedLimit()) }
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
                Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Giới hạn tốc độ upload", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(
                "Áp dụng cho tất cả upload qua WebDAV (auto-backup ảnh, share file, batch ops). " +
                    "Dùng để tránh app chiếm hết băng thông Wi-Fi/LAN.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp
            )

            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                presets.forEach { (value, label) ->
                    val isSelected = selected == value
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .height(44.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else DarkCardHover,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.Transparent,
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
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            label,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            NasGradientButton(
                onClick = {
                    prefsRepo.setUploadSpeedLimit(selected)
                    com.nas.naswebdav.AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC = selected
                    val selectedLabel = presets.firstOrNull { it.first == selected }?.second ?: "${selected / 1024 / 1024} MB/s"
                    deviceVM.logUserAction("Bandwidth", "Thiết lập giới hạn băng thông tải lên: $selectedLabel.")
                    onDismiss()
                },
                text = "ÁP DỤNG",
                height = 40.dp,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Lưu ý: giới hạn này CHỈ ảnh hưởng upload từ điện thoại lên NAS, không ảnh hưởng tốc độ NAS ↔ Internet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}



// ====================================================================
// DIALOG SUC KHOE O CUNG — Hien score, attributes, warnings tu SMART
// + dmesg + io stats. Goi /api/disk/health.
// ====================================================================



// ====================================================================
// USB IMPORT - quản lý daemon copy ổ USB gắn ngoài vào NAS
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsUsbImportDialog(
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val state = deviceVM.usbImportState
    val settings = state.settings
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var autoMount by remember(settings) { mutableStateOf(settings.autoMount) }
    var mountReadonly by remember(settings) { mutableStateOf(settings.mountReadonly) }
    var resumeEnabled by remember(settings) { mutableStateOf(settings.resumeEnabled) }
    var verifyChecksum by remember(settings) { mutableStateOf(settings.verifyChecksum) }
    var copyMode by remember(settings) { mutableStateOf(settings.copyMode) }
    var destFolder by remember(settings) { mutableStateOf(settings.destFolder) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { deviceVM.fetchUsbImportStatus() }
    val isPollingStatus = state.status == "copying" || state.status == "cancelling"
    LaunchedEffect(isPollingStatus) {
        while (isPollingStatus) {
            delay(2500)
            deviceVM.fetchUsbImportStatus(compact = true, minIntervalMs = 2_000L)
        }
    }

    val fileCountProgress = if (state.filesTotal > 0) {
        (state.filesDone + state.filesSkipped + state.filesFailed).toFloat() / state.filesTotal.toFloat()
    } else 0f
    val progress = if (state.bytesTotal > 0L) {
        state.bytesProcessed.toFloat() / state.bytesTotal.toFloat()
    } else fileCountProgress
    val currentFileProgress = if (state.currentFileBytesTotal > 0L) {
        state.currentFileBytesDone.toFloat() / state.currentFileBytesTotal.toFloat()
    } else 0f
    fun usbEtaLabel(seconds: Long): String {
        if (seconds <= 0L) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
    val isRunning = state.status == "copying" || state.status == "cancelling"
    val statusColor = when (state.status) {
        "copying" -> MaterialTheme.colorScheme.primary
        "done" -> MaterialTheme.colorScheme.tertiary
        "error" -> MaterialTheme.colorScheme.error
        "disabled" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 820.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Usb, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("USB Import", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { deviceVM.fetchUsbImportStatus() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Refresh, "Làm mới", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                "NAS tự phát hiện ổ cứng/USB gắn qua cổng USB 3.0 và copy dữ liệu vào thư mục USB Import.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth().background(DarkCardHover, RoundedCornerShape(8.dp)).padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(state.status.uppercase(), color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (deviceVM.isUsbImportLoading) {
                        NasLoadingSpinner(size = 24.dp, color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(state.message.ifBlank { "Đang chờ trạng thái từ NAS" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                if (state.detectedDevicesInfo.isNotBlank()) {
                    Text("Đã phát hiện: ${state.detectedDevicesInfo}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                if (state.activeDevice.isNotBlank() || state.destDir.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (state.activeDevice.isNotBlank()) {
                            Text(
                                state.activeDevice,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.32f)
                            )
                        }
                        if (state.activeDevice.isNotBlank() && state.destDir.isNotBlank()) {
                            Icon(
                                Icons.Default.ArrowForward,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp).padding(horizontal = 2.dp)
                            )
                        }
                        if (state.destDir.isNotBlank()) {
                            Text(
                                state.destDir,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.68f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (state.currentFile.isNotBlank()) {
                    Text("File đang copy", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.currentFile, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.currentSource.isNotBlank()) {
                        Text("Từ: ${state.currentSource}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { currentFileProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = DarkCard
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.currentFileBytesDone)} / ${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.currentFileBytesTotal)}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 10.sp
                        )
                        Text(
                            "${(currentFileProgress * 100f).toInt()}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text("Tổng tiến trình", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                    color = statusColor,
                    trackColor = DarkCard
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${state.filesDone}/${state.filesTotal} file", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.bytesTotal > 0L)
                            "${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.bytesProcessed)} / ${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.bytesTotal)}"
                        else com.nas.naswebdav.utils.FormatUtils.formatBytes(state.bytesDone),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bỏ qua ${state.filesSkipped} • Lỗi ${state.filesFailed}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    Text(
                        "${com.nas.naswebdav.utils.FormatUtils.formatBytes(state.copySpeedBps)}/s • ETA ${usbEtaLabel(state.etaSeconds)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                if (state.lastError.isNotBlank()) {
                    Text(state.lastError, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.status == "needs_action" && state.needsAction && state.pendingConflictsCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            "Có ${state.pendingConflictsCount} file trùng tên cần xử lý",
                            color = AccentOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        state.pendingConflicts.take(3).forEach { item ->
                            val oldSize = com.nas.naswebdav.utils.FormatUtils.formatBytes(item.destSize)
                            val newSize = com.nas.naswebdav.utils.FormatUtils.formatBytes(item.sourceSize)
                            Text(
                                "${item.destName.ifBlank { item.rel }} • cũ $oldSize / mới $newSize",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { deviceVM.resolveUsbImportConflicts("skip") },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Bỏ qua", fontSize = 11.sp) }
                            Button(
                                onClick = { deviceVM.resolveUsbImportConflicts("rename") },
                                modifier = Modifier.weight(1f).height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Đổi tên", fontSize = 11.sp) }
                            Button(
                                onClick = { deviceVM.resolveUsbImportConflicts("overwrite") },
                                modifier = Modifier.weight(1f).height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Ghi đè", fontSize = 11.sp) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            val settingsArrowRotation by animateFloatAsState(
                targetValue = if (settingsExpanded) 180f else 0f,
                animationSpec = tween(durationMillis = 220),
                label = "usbImportSettingsArrow"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCardHover, RoundedCornerShape(8.dp))
                    .border(1.dp, DarkElevated, RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsExpanded = !settingsExpanded }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cài đặt", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp).rotate(settingsArrowRotation)
                    )
                }
                AnimatedVisibility(
                    visible = settingsExpanded,
                    enter = expandVertically(animationSpec = tween(240)) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(140))
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tự động phát hiện", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tự mount ổ USB", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(checked = autoMount, onCheckedChange = { autoMount = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Mount read-only", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(checked = mountReadonly, onCheckedChange = { mountReadonly = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Resume sau restart", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Copy tiếp vào cùng thư mục nếu NAS/API bị restart.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            Switch(checked = resumeEnabled, onCheckedChange = { resumeEnabled = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Checksum SHA-256", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Chậm hơn nhưng ghi manifest để kiểm chứng file.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            Switch(checked = verifyChecksum, onCheckedChange = { verifyChecksum = it })
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = destFolder,
                            onValueChange = { if (it.length <= 48) destFolder = it },
                            label = { Text("Thư mục đích") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = DarkCardHover,
                                focusedLabelColor = MaterialTheme.colorScheme.primary,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = copyMode == "new_only",
                                onClick = { copyMode = "new_only" },
                                label = { Text("Chỉ file mới", fontSize = 12.sp) },
                                leadingIcon = if (copyMode == "new_only") {{ Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }} else null
                            )
                            FilterChip(
                                selected = copyMode == "overwrite",
                                onClick = { copyMode = "overwrite" },
                                label = { Text("Ghi đè", fontSize = 12.sp) },
                                leadingIcon = if (copyMode == "overwrite") {{ Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }} else null
                            )
                        }
                    }
                }
            }

            if (deviceVM.usbImportMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(deviceVM.usbImportMessage, color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        deviceVM.saveUsbImportSettings(
                            UsbImportSettings(
                                enabled = enabled,
                                destFolder = destFolder,
                                copyMode = copyMode,
                                autoMount = autoMount,
                                mountReadonly = mountReadonly,
                                pollSeconds = settings.pollSeconds,
                                resumeEnabled = resumeEnabled,
                                verifyChecksum = verifyChecksum,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lưu", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (isRunning) {
                            enabled = false
                            deviceVM.saveUsbImportSettings(
                                UsbImportSettings(
                                    enabled = false,
                                    destFolder = destFolder,
                                    copyMode = copyMode,
                                    autoMount = autoMount,
                                    mountReadonly = mountReadonly,
                                    pollSeconds = settings.pollSeconds,
                                    resumeEnabled = resumeEnabled,
                                    verifyChecksum = verifyChecksum,
                                )
                            )
                            deviceVM.cancelUsbImport()
                        } else {
                            deviceVM.startUsbImportNow()
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(if (isRunning) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRunning) "Tắt USB Import" else "Copy ngay", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ====================================================================
// DIALOG THUOC TINH FILE — Tuong tu cua so Properties cua Windows
// Hien khi user long-press 1 file/folder trong BrowserScreen va chon "Thuoc tinh"
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsFilePropertiesDialog(
    file: com.nas.naswebdav.NasFile,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Tinh toan cac field hien thi
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val mime = remember(file.name, file.isDirectory) {
        if (file.isDirectory) "Thư mục" else {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: file.contentType ?: "application/octet-stream"
        }
    }
    val sizeFormatted = remember(file.contentLength) {
        if (file.isDirectory) "—" else com.nas.naswebdav.utils.FormatUtils.formatBytes(file.contentLength)
    }
    val sizeRaw = if (file.isDirectory) "" else " (${"%,d".format(file.contentLength)} bytes)"
    val modifiedStr = remember(file.lastModified) {
        if (file.lastModified <= 0L) "—" else {
            try {
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(file.lastModified))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "—" }
        }
    }

    // Hash MD5/aHash tu DB fingerprint (neu da scan duplicate truoc do).
    // Dung LaunchedEffect tra cuu o background, KHONG block UI.
    var fingerprintHash by remember(file.path) { mutableStateOf<String?>(null) }
    var fingerprintLoading by remember(file.path) { mutableStateOf(true) }
    LaunchedEffect(file.path) {
        if (file.isDirectory) {
            fingerprintLoading = false
            return@LaunchedEffect
        }
        try {
            val db = com.nas.naswebdav.NasApplication.instance.database
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // FingerprintDao chi co findByExactHash(hash) — query full table de tim filePath khong toi uu.
                // Thay vao do dung FileDao.partialHash / fullHash (CachedFile co san).
                db.fileDao().getFileByPath(file.path)
            }
            fingerprintHash = result?.fullHash ?: result?.partialHash ?: result?.imageFingerprint
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            fingerprintHash = null
        } finally {
            fingerprintLoading = false
        }
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = if (file.isDirectory) AccentOrange else AccentGreen,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thuộc tính",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))

            // Cac dong field — label trai, value phai, value selectable de copy
            DialogsPropertyRow("Tên", file.name, selectable = true)
            DialogsPropertyRow("Đường dẫn", file.path, selectable = true, monospace = true)
            DialogsPropertyRow("Loại", mime)
            if (!file.isDirectory) {
                DialogsPropertyRow("Phần mở rộng", if (ext.isEmpty()) "—" else ".$ext")
                DialogsPropertyRow("Kích thước", "$sizeFormatted$sizeRaw")
            }
            DialogsPropertyRow("Sửa lần cuối", modifiedStr)
            val hashDisplay = when {
                file.isDirectory -> "—"
                fingerprintLoading -> "Đang tra cứu..."
                fingerprintHash.isNullOrEmpty() -> "— (chưa quét fingerprint)"
                else -> fingerprintHash!!
            }
            DialogsPropertyRow("Hash", hashDisplay, selectable = true, monospace = true)

            Spacer(Modifier.height(8.dp))

            // Nut dong
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Button(
                onClick = onDismiss,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary, AccentGreen)
                        ),
                        shape = RoundedCornerShape(23.dp)
                    )
            ) {
                Text("Đóng", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun DialogsInsightRate(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return if (idx == 0) "${value.toInt()} ${units[idx]}" else "%.1f %s".format(java.util.Locale.US, value, units[idx])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsNasInsightsDialog(
    onDismiss: () -> Unit,
    onTaskClick: (String) -> Unit = {}
) {
    val sysMonitorVM = LocalSystemMonitorVM.current
    LaunchedEffect(Unit) { sysMonitorVM.fetchNasInsights(minIntervalMs = 5_000L) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val insight = sysMonitorVM.nasInsights
        Column(
            modifier = Modifier.fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoGraph, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tổng quan hệ thống NAS", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { sysMonitorVM.fetchNasInsights(minIntervalMs = 0L) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }

            DialogsInsightSection("Sức khoẻ Toshiba HDD", Icons.Default.HealthAndSafety, MaterialTheme.colorScheme.tertiary) {
                DialogsInsightRow("Điểm hiện tại", "${insight.hddScore}/100")
                val liveHddTemp = if (sysMonitorVM.systemStatus.temp.isNotBlank() && sysMonitorVM.systemStatus.temp != "--°C") sysMonitorVM.systemStatus.temp else "${insight.hddTempC}°C"
                DialogsInsightRow("Nhiệt độ", liveHddTemp)
                DialogsInsightRow("Thấp nhất 7 ngày", "${insight.hddMinScore}/100")
                DialogsInsightRow("Biến động", if (insight.hddScoreDelta >= 0) "+${insight.hddScoreDelta}" else "${insight.hddScoreDelta}")
                if (insight.hddStatusText.isNotBlank()) {
                    Text(insight.hddStatusText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            DialogsInsightSection("Bộ điều phối tải nền", Icons.Default.Tune, MaterialTheme.colorScheme.error) {
                val displayMode = when(insight.workloadMode.lowercase()) {
                    "normal" -> "BÌNH THƯỜNG"
                    "balanced" -> "CÂN BẰNG TẢI"
                    "protect" -> "BẢO VỆ HỆ THỐNG"
                    else -> insight.workloadMode.uppercase()
                }
                DialogsInsightRow("Chế độ", displayMode)
                DialogsInsightRow("Áp lực tải", "${insight.workloadPressure}")
                Text(insight.workloadRecommendation.ifBlank { "Chưa có khuyến nghị." }, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                if (insight.workloadReasons.isNotEmpty()) {
                    Text(insight.workloadReasons.joinToString(" • "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }

            DialogsInsightSection("Bảo vệ eMMC", Icons.Default.Memory, MaterialTheme.colorScheme.primary) {
                DialogsInsightRow("Root eMMC", "${insight.emmcRootPercent}%")
                DialogsInsightRow("Log/zram", "${insight.emmcLogPercent}%")
                val recs = insight.emmcRecommendations.ifEmpty { listOf("eMMC đang an toàn.") }
                recs.take(3).forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            }

            DialogsInsightSection("Luồng dữ liệu thực tế", Icons.Default.SyncAlt, AccentPurple) {
                DialogsInsightRow("Ghi HDD", DialogsInsightRate(insight.diskWriteBps))
                DialogsInsightRow("Đọc HDD", DialogsInsightRate(insight.diskReadBps))
                DialogsInsightRow("LAN nhận", DialogsInsightRate(insight.netRxBps))
                DialogsInsightRow("LAN gửi", DialogsInsightRate(insight.netTxBps))
                if (insight.flowTasks.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    insight.flowTasks.take(4).forEach { task ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(DarkCardHover, RoundedCornerShape(7.dp))
                                .clickable { onTaskClick(task.label) }
                                .padding(8.dp)
                        ) {
                            Text("${task.label} • ${task.progress}%", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(task.file.ifBlank { "Đang xử lý" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(task.dest, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            DialogsInsightSection("Khuyến nghị bảo trì", Icons.Default.EventAvailable, MaterialTheme.colorScheme.tertiary) {
                insight.maintenanceActions.ifEmpty {
                    listOf(InsightAction("low", "Ổn định", "Chưa có tác vụ bảo trì bắt buộc."))
                }.forEach { action ->
                    val color = when (action.priority) {
                        "high" -> MaterialTheme.colorScheme.error
                        "medium" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(8.dp).padding(top = 5.dp).background(color, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(action.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }

            Text(
                "Dữ liệu lấy từ /api/system/insights: SMART trend, tải nền, USB import, eMMC guard, luồng I/O và lịch bảo trì.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DialogsInsightSection(title: String, icon: ImageVector, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(7.dp))
        content()
    }
}

@Composable
private fun DialogsInsightRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DialogsPropertyRow(
    label: String,
    value: String,
    selectable: Boolean = false,
    monospace: Boolean = false
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(1.dp))
        val style = if (monospace) {
            androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (selectable) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(value, style = style, softWrap = true)
            }
        } else {
            Text(value, style = style, softWrap = true)
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsNasConfigBackupDialog(
    onDismiss: () -> Unit
) {
    val sysMonitorVM = LocalSystemMonitorVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDeleteFilename by remember { mutableStateOf<String?>(null) }
    var pendingRestoreFilename by remember { mutableStateOf<String?>(null) }
    var isPreparingShare by remember { mutableStateOf(false) }

    // Confirm dialogs
    if (pendingDeleteFilename != null) {
        val target = pendingDeleteFilename!!
        DialogsAppStatusDialog(
            type = DialogType.CONFIRM,
            message = "Sẽ xoá vĩnh viễn:\n$target",
            onConfirm = {
                sysMonitorVM.deleteNasConfigBackup(target)
                pendingDeleteFilename = null
            },
            onDismiss = { pendingDeleteFilename = null }
        )
    }
    if (pendingRestoreFilename != null) {
        val target = pendingRestoreFilename!!
        DialogsAppStatusDialog(
            type = DialogType.CONFIRM,
            message = "Sẽ ghi đè các file cấu hình hiện tại của NAS bằng nội dung trong:\n\n$target\n\nCác file gốc được giữ lại với đuôi .pre-restore. Sau khi xong, service nas_api/nginx sẽ tự restart.\n\nTiếp tục?",
            onConfirm = {
                sysMonitorVM.restoreNasConfigBackup(target)
                pendingRestoreFilename = null
            },
            onDismiss = { pendingRestoreFilename = null }
        )
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                .heightIn(max = 720.dp).verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.SettingsBackupRestore, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sao lưu cấu hình NAS", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(
                "Backup toàn bộ cấu hình NAS (nas_api server, watcher TikTok, fan, cookies, nginx, OMV WebDAV, ...) " +
                    "thành 1 file .tar.gz lưu trên eMMC. Có thể tải về điện thoại hoặc đẩy lên OneDrive để dự phòng.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp
            )
            Spacer(Modifier.height(6.dp))

            // Create button
            Button(
                onClick = { sysMonitorVM.createNasConfigBackup() },
                enabled = !sysMonitorVM.isCreatingNasConfigBackup,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (sysMonitorVM.isCreatingNasConfigBackup) "ĐANG TẠO..." else "TẠO BACKUP MỚI",
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
            }

            // Status message
            if (sysMonitorVM.nasConfigBackupMessage.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val msgColor = when {
                    sysMonitorVM.nasConfigBackupMessage.startsWith("Lỗi") -> MaterialTheme.colorScheme.error
                    sysMonitorVM.nasConfigBackupMessage.startsWith("Đã") -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(sysMonitorVM.nasConfigBackupMessage, color = msgColor, fontSize = 12.sp)
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
            Spacer(Modifier.height(6.dp))

            // List header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "BACKUP HIỆN CÓ (${sysMonitorVM.nasConfigBackups.size})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { sysMonitorVM.fetchNasConfigBackups() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.Refresh, "Làm mới", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(4.dp))

            if (sysMonitorVM.nasConfigBackups.isEmpty()) {
                Text(
                    "Chưa có bản backup nào. Tạo bản đầu tiên bằng nút phía trên.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sysMonitorVM.nasConfigBackups.forEach { backup ->
                        key(backup.filename) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(DarkCardHover, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(backup.filename, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${backup.createdAt}  •  ${backup.sizeHuman}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // Share to OneDrive (qua Android share intent)
                                    TextButton(
                                        onClick = {
                                            if (isPreparingShare) return@TextButton
                                            isPreparingShare = true
                                            scope.launch {
                                                val f = sysMonitorVM.downloadNasConfigBackup(context, backup.filename)
                                                isPreparingShare = false
                                                if (f != null) {
                                                    try {
                                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                                            context,
                                                            context.applicationContext.packageName + ".fileprovider",
                                                            f
                                                        )
                                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                            type = "application/gzip"
                                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                            putExtra(android.content.Intent.EXTRA_SUBJECT, backup.filename)
                                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        val chooser = android.content.Intent.createChooser(send, "Chia sẻ tới OneDrive / Drive / Email ...")
                                                        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(chooser)
                                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                                        android.widget.Toast.makeText(context, "Lỗi share: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                                    }
                                                } else {
                                                    android.widget.Toast.makeText(context, "Không tải được file backup", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("OneDrive", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    TextButton(
                                        onClick = { pendingRestoreFilename = backup.filename },
                                        enabled = !sysMonitorVM.isRestoringNasConfigBackup,
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Khôi phục", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    TextButton(
                                        onClick = { pendingDeleteFilename = backup.filename },
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Xoá", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isPreparingShare) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NasLoadingSpinner(size = 24.dp, color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Đang tải file từ NAS để share...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            if (sysMonitorVM.isRestoringNasConfigBackup) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NasLoadingSpinner(size = 24.dp, color = MaterialTheme.colorScheme.error, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Đang khôi phục + restart services...", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
