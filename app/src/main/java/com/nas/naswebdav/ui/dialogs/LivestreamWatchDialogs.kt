@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.livestream.LivestreamViewModel
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

// ============ Livestream watch / TikTok live section (tách cơ học từ Dialogs.kt — không đổi logic) ============

object LivestreamPanelState {
    val current: androidx.compose.runtime.MutableState<String?> =
        androidx.compose.runtime.mutableStateOf(null)

    fun toggle(panelId: String) {
        current.value = if (current.value == panelId) null else panelId
    }
}

internal fun normalizeTikTokWatchMessage(message: String): String {
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
internal fun TikTokLiveWatchSection(
    liveVM: LivestreamViewModel,
    context: Context,
    users: List<TikTokLiveWatchUser>,
    newUsername: String,
    onUsernameChange: (String) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    // TikTok state (isLoadingTikTokWatch, tiktokLiveWatchError, tiktokWatchDaemonRunning,
    // tiktokLiveWatchUsers) reads via facade delegation → LivestreamVM is source of truth.
    var expandedUserName by remember { mutableStateOf<String?>(null) }
    var pendingDeleteUser by remember { mutableStateOf<TikTokLiveWatchUser?>(null) }
    val snackbarScope = rememberCoroutineScope()
    // Các panel theo dõi TikTok / thời gian loại trừ / đang ghi hình — chi 1 panel mo
    // cung luc thong qua LivestreamPanelState. Mac dinh tat ca dong (current.value == null).
    val listExpanded = LivestreamPanelState.current.value == "watchlist"
    val excludeExpanded = LivestreamPanelState.current.value == "exclude"

    pendingDeleteUser?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteUser = null },
            containerColor = DarkCard,
            title = {
                Text("Xác nhận xoá người dùng", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Bạn có chắc chắn muốn xoá @${target.username} khỏi danh sách theo dõi TikTok Live không?",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val deletedUsername = target.username
                    pendingDeleteUser = null
                    if (expandedUserName == deletedUsername) expandedUserName = null
                    liveVM.removeTikTokLiveWatchUser(context, deletedUsername)
                    snackbarScope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Đã xoá @$deletedUsername khỏi danh sách theo dõi.",
                            actionLabel = "Hoàn tác",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            liveVM.addTikTokLiveWatchUser(context, deletedUsername)
                        }
                    }
                }) {
                    Text("Xoá", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteUser = null }) {
                    Text("Huỷ", color = TextTertiary, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    HorizontalDivider(color = TextTertiary.copy(alpha = 0.25f))
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
                ) { LivestreamPanelState.toggle("watchlist") } else Modifier
            )
    ) {
        Text("♪", fontSize = 20.sp, color = AccentRed)
        Spacer(Modifier.width(8.dp))
        Text("THEO DÕI TIKTOK LIVE", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text("${users.size} người dùng", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (users.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(
                if (listExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (listExpanded) "Ẩn danh sách" else "Mở danh sách",
                tint = TextSecondary,
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
            leadingIcon = { Text("@", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            trailingIcon = {
                if (newUsername.isNotBlank()) {
                    IconButton(
                        onClick = { onUsernameChange("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Xoá nội dung nhập",
                            tint = TextTertiary,
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
                    liveVM.addTikTokLiveWatchUser(context, cleanUsername)
                    onUsernameChange("")
                }
            },
            enabled = newUsername.isNotBlank() && !liveVM.isLoadingTikTokWatch,
            modifier = Modifier.height(40.dp).widthIn(min = 80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkCardHover),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            Icon(Icons.Default.Add, null, tint = TextPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Thêm", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    val watchError = liveVM.tiktokLiveWatchError ?: ""
    if (watchError.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(watchError, color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
    val daemonColor = if (liveVM.tiktokWatchDaemonRunning) AccentGreen else AccentOrange
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        val checkLine = buildString {
            append(if (liveVM.tiktokWatchDaemonRunning) "Watcher NAS đang chạy" else "Watcher NAS chưa phản hồi")
            if (liveVM.tiktokWatchDaemonLastTick.isNotEmpty()) append(" • Lần kiểm tra cuối: ${liveVM.tiktokWatchDaemonLastTick}")
        }
        Text(
            checkLine,
            color = daemonColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (liveVM.tiktokWatchDaemonSummary.isNotEmpty()) {
            val summaryText = liveVM.tiktokWatchDaemonSummary
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
    val cookiesStatus = liveVM.tiktokCookiesStatus
    if (cookiesStatus == "missing" || cookiesStatus == "expired" || cookiesStatus == "revoked") {
        Spacer(Modifier.height(4.dp))
        val (bannerBg, bannerFg, label) = when (cookiesStatus) {
            "missing" -> Triple(AccentOrange.copy(alpha = 0.2f), AccentOrange, "Chưa có cookies.txt")
            "expired" -> Triple(AccentRed.copy(alpha = 0.2f), AccentRed, "Cookies TikTok hết hạn")
            else -> Triple(AccentRed.copy(alpha = 0.2f), AccentRed, "Cookies TikTok bị thu hồi")
        }
        Row(
            Modifier.fillMaxWidth().background(bannerBg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = bannerFg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = bannerFg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val detail = liveVM.tiktokCookiesMessage
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
                                    AccentRed.copy(alpha = 0.55f) else Color.Transparent,
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
                                    tint = TextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(DarkSurface, RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    expandedUserName = if (expandedUserName == user.username) null else user.username
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            val isUserExpanded = expandedUserName == user.username
                            val effectiveStatus = user.status
                            val statusLabel = when (effectiveStatus) {
                                "recording" -> "Đang live - đã tự ghi"
                                "recorded" -> "Đã ghi phiên này"
                                "excluded" -> "Đang trong giờ loại trừ"
                                "error" -> "Lỗi kiểm tra"
                                else -> "Đang theo dõi"
                            }
                            val displayLastError = normalizeTikTokWatchMessage(user.lastError)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "@${user.username}",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                TikTokWatchStatusChip(
                                    status = effectiveStatus,
                                    label = statusLabel,
                                    modifier = Modifier.widthIn(min = 116.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    if (isUserExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isUserExpanded) "Thu gọn người dùng" else "Mở chi tiết người dùng",
                                    tint = TextSecondary,
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
                                Text(checkLiveLine, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            androidx.compose.animation.AnimatedVisibility(visible = isUserExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .background(DarkElevated, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("THÔNG TIN THEO DÕI", color = AccentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text(statusLabel, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    if (user.jobId.isNotEmpty()) {
                                        Text("Tác vụ ghi hình: ${user.jobId}", color = TextTertiary, fontSize = 11.sp)
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
                                                    color = TextTertiary,
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
                                                    color = TextTertiary,
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
                                        Text("CHI TIẾT LỖI", color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        androidx.compose.foundation.layout.Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 220.dp)
                                                .verticalScroll(rememberScrollState())
                                                .background(DarkSurface, RoundedCornerShape(8.dp))
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
    Text("Thêm tài khoản TikTok để tự động dò và ghi khi live", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = TextTertiary.copy(alpha = 0.25f))
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
            ) { LivestreamPanelState.toggle("exclude") }
    ) {
        Text("☾", fontSize = 18.sp, color = AccentOrange)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Thời gian loại trừ", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Không kiểm tra livestream trong khoảng giờ này", color = TextTertiary, fontSize = 11.sp)
        }
        Switch(
            checked = liveVM.tiktokExcludeEnabled,
            onCheckedChange = { liveVM.updateTikTokLiveWatchSettings(context, it) }
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (excludeExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (excludeExpanded) "Ẩn" else "Mở",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
    androidx.compose.animation.AnimatedVisibility(visible = excludeExpanded) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Từ", color = TextTertiary, fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = liveVM.tiktokExcludeStart,
                    onValueChange = { if (it.length <= 5) liveVM.updateTikTokLiveWatchSettings(context, liveVM.tiktokExcludeEnabled, it, liveVM.tiktokExcludeEnd) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    accentColor = AccentRed,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(80.dp)
                )
                Text("→", color = TextTertiary, fontSize = 16.sp)
                Text("Đến", color = TextTertiary, fontSize = 13.sp)
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = liveVM.tiktokExcludeEnd,
                    onValueChange = { if (it.length <= 5) liveVM.updateTikTokLiveWatchSettings(context, liveVM.tiktokExcludeEnabled, liveVM.tiktokExcludeStart, it) },
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
internal fun TikTokWatchStatusChip(
    status: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val normalizedStatus = status.lowercase()
    val (containerColor, borderColor, textColor) = when (normalizedStatus) {
        "recording" -> Triple(
            AccentGreen.copy(alpha = 0.18f),
            AccentGreen.copy(alpha = 0.45f),
            AccentGreen
        )
        "recorded" -> Triple(
            AccentCyan.copy(alpha = 0.18f),
            AccentCyan.copy(alpha = 0.45f),
            AccentCyan
        )
        "excluded" -> Triple(
            AccentOrange.copy(alpha = 0.18f),
            AccentOrange.copy(alpha = 0.45f),
            AccentOrange
        )
        "error" -> Triple(
            AccentRed.copy(alpha = 0.16f),
            AccentRed.copy(alpha = 0.45f),
            AccentRed
        )
        else -> Triple(
            TextTertiary.copy(alpha = 0.18f),
            TextTertiary.copy(alpha = 0.35f),
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
