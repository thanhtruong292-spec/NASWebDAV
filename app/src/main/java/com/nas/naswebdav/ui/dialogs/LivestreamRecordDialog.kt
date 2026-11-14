@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.screens.*
import com.nas.naswebdav.ui.components.NasModalBottomSheet
import com.nas.naswebdav.ui.components.minTouchTarget
import com.nas.naswebdav.ui.components.NasGradientButton
import com.nas.naswebdav.ui.components.NasLoadingSpinner
import com.nas.naswebdav.ui.screens.WebDavCachedThumbnail

import android.content.Context
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

// ============ LivestreamRecordDialog (tách từ RecordingSettingsDialogs.kt) ============

// ════════════════════════════════════════════════════════════════════════════
// LivestreamRecordDialog — Ghi hinh Livestream TikTok / Facebook / YouTube
// ════════════════════════════════════════════════════════════════════════════

private val AllowedTikTokHosts = setOf(
    "vt.tiktok.com",
    "vm.tiktok.com",
    "www.tiktok.com",
    "tiktok.com",
    "m.tiktok.com"
)

private val InvisibleUrlCharsRegex = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u200E\\u200F\\u2028\\u2029\\r\\n]")
private val TikTokHandleRegex = Regex("@([\\w.]+)")
private val TikTokShortPathRegex = Regex("""tiktok\.com/t/[\w-]+""")
private val FacebookUserRegex = Regex("facebook\\.com/([^/\\?]+)")
private val YouTubeHandleRegex = Regex("@([\\w.-]+)")
private val TikTokProfileUrlRegex = Regex("tiktok\\.com/@([\\w.]+)")

private fun isAllowedTikTokRedirectUrl(rawUrl: String): Boolean = runCatching {
    val parsed = java.net.URI(rawUrl.trim())
    val scheme = parsed.scheme?.lowercase(java.util.Locale.US) ?: return false
    val host = parsed.host?.lowercase(java.util.Locale.US) ?: return false
    scheme == "https" && host in AllowedTikTokHosts
}.getOrDefault(false)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LivestreamRecordDialog(
    onDismiss: () -> Unit
) {
    val liveVM = LocalLivestreamVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    // Khôi phục trạng thái nếu Worker đang chạy ngầm
    // Kept as Unit: one-shot state sync when dialog opens.
    // SPEC_LIVESTREAM_LIMITS §4.3: refresh gom (status + cap + queue) thay sync le.
    LaunchedEffect(Unit) { liveVM.refreshLivestreamState(context) }
    // Reset panel khi mo dialog, nhung giu "active" neu dang co job ghi hinh
    androidx.compose.runtime.DisposableEffect(Unit) {
        LivestreamPanelState.current.value = if (liveVM.activeLivestreams.isNotEmpty()) "active" else null
        onDispose { }
    }
    var liveUrl by remember { mutableStateOf("") }
    var isResolvingTikTokLink by remember { mutableStateOf(false) }
    var newTikTokWatchUser by remember { mutableStateOf("") }
    var livePanelMode by remember { mutableStateOf("record") }
    var selectedQuality by remember { mutableStateOf("best") }
    val activeLivestreams = liveVM.activeLivestreams
    val message = liveVM.livestreamMessage
    val tiktokWatchUsers = liveVM.tiktokLiveWatchUsers

    // Kept as Unit: one-shot watch-list fetch when dialog opens.
    LaunchedEffect(Unit) { liveVM.fetchTikTokLiveWatch(context) }

    // URL sanitizer: strip invisible Unicode (U+200B zero-width space, U+FEFF BOM,
    // newlines, carriage returns) that social apps inject when copying links.
    fun sanitizeUrl(raw: String): String {
        return raw.replace(InvisibleUrlCharsRegex, "").trim()
    }

    // AUTO-PASTE: Đọc clipboard khi dialog mở, tự dán nếu chứa link livestream
    // Kept as Unit: one-shot auto-paste from clipboard on dialog open.
    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text ?: ""
        if (clipText.isNotBlank() && listOf("tiktok", "facebook", "fb.watch", "youtube", "youtu.be", "shopee").any { clipText.contains(it, true) }) {
            liveUrl = sanitizeUrl(clipText)
            livePanelMode = "record"
            liveVM.clearLivestreamMessage()
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
                val username = TikTokHandleRegex.find(liveUrl)?.groupValues?.get(1)
                if (username != null) "Live của @$username" 
                else if (isResolvingTikTokLink || liveUrl.contains("vt.tiktok.com") || liveUrl.contains("vm.tiktok.com") || TikTokShortPathRegex.containsMatchIn(liveUrl)) "TikTok Live (Đang lấy tên...)"
                else "TikTok Live"
            }
            liveUrl.contains("facebook", true) || liveUrl.contains("fb.watch", true) -> {
                val fbUser = FacebookUserRegex.find(liveUrl)?.groupValues?.get(1)
                if (!fbUser.isNullOrEmpty() && fbUser != "watch") "Live của $fbUser" else "Facebook Live"
            }
            liveUrl.contains("youtube", true) || liveUrl.contains("youtu.be", true) -> {
                val channel = YouTubeHandleRegex.find(liveUrl)?.groupValues?.get(1)
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
                          TikTokShortPathRegex.containsMatchIn(originalUrl)
        if (isShortLink && !originalUrl.contains("@") && isAllowedTikTokRedirectUrl(originalUrl)) {
            isResolvingTikTokLink = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    var current = originalUrl
                    // Theo redirect toi 5 hop de tranh loop, vi tiktok.com/t/ co the redirect 2-3 lan
                    for (hop in 0 until 5) {
                        if (!isAllowedTikTokRedirectUrl(current)) break
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
                        val nextUrl = if (location.startsWith("http")) location else java.net.URL(java.net.URL(current), location).toString()
                        if (!isAllowedTikTokRedirectUrl(nextUrl)) break
                        current = nextUrl
                        if (current.contains("@")) break
                    }
                    if (current.contains("@") && current != originalUrl) {
                        val newUrl = current.substringBefore("?")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (liveUrl == originalUrl) {
                                liveUrl = newUrl
                                liveVM.clearLivestreamMessage()
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
    val expandedPanel = LivestreamPanelState.current.value
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
                Icon(Icons.Default.Videocam, contentDescription = "Livestream camera", tint = accentColor, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ghi hình Livestream", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Ghi trực tiếp vào NAS HDD", color = TextTertiary, fontSize = 12.sp)
                }
                if (activeLivestreams.isNotEmpty()) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Thu nhỏ", tint = TextTertiary)
                    }
                }
            }

            // Tab buttons — dung [PillTab] de dam bao consistency voi cac tab khac trong app
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.nas.naswebdav.ui.components.PillTab(
                    selected = livePanelMode == "watch",
                    label = "Theo dõi người dùng",
                    emoji = "",
                    accentColor = AccentRed,
                    onClick = { livePanelMode = "watch" },
                    modifier = Modifier.weight(1f),
                )
                com.nas.naswebdav.ui.components.PillTab(
                    selected = livePanelMode == "record",
                    label = "Ghi link live",
                    emoji = "",
                    accentColor = accentColor,
                    onClick = { livePanelMode = "record" },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))

            if (livePanelMode == "watch") {
                TikTokLiveWatchSection(
                    liveVM = liveVM,
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
                    liveUrl = sanitizeUrl(it)
                    liveVM.clearLivestreamMessage()
                },
                placeholder = "Dán link livestream — https://www.tiktok.com/@user/live",
                accentColor = accentColor,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (liveUrl.isNotEmpty()) {
                        IconButton(onClick = { liveUrl = "" }, modifier = Modifier.size(28.dp).minTouchTarget()) {
                            Icon(Icons.Default.Clear, contentDescription = "Xóa", tint = TextTertiary, modifier = Modifier.size(18.dp))
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
                    Icon(Icons.Default.Videocam, contentDescription = "Livestream camera", tint = accentColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Đã nhận diện: $detectedTitle", color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
            }
            
            Text("CHẤT LƯỢNG", color = TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                            labelColor = TextTertiary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = TextTertiary.copy(alpha = 0.2f),
                            selectedBorderColor = accentColor.copy(alpha = 0.5f),
                            enabled = true,
                            selected = selected
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
            
            if (liveVM.isStartingLivestream) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    NasLoadingSpinner(size = 24.dp, color = accentColor, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(liveVM.livestreamMessage.ifEmpty { "Đang kết nối luồng Live..." }, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (message.isNotEmpty()) {
                // SPEC_LIVESTREAM_LIMITS §4.2: loi persistent den khi user dismiss/doi link/thanh cong.
                // Chi auto-clear success sau 10s; loi giu lai + CTA theo code. Thay prefix "Lỗi" gion
                // bang isLivestreamError / livestreamErrorCode tu ViewModel.
                val isErr = liveVM.isLivestreamError
                if (!isErr) {
                    LaunchedEffect(message) {
                        kotlinx.coroutines.delay(10000L)
                        liveVM.clearLivestreamMessage()
                    }
                }
                Spacer(Modifier.height(6.dp))
                val msgColor = if (isErr) AccentRed else TextTertiary
                Text(
                    message, color = msgColor, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { liveVM.clearLivestreamMessage() },
                    textAlign = TextAlign.Center
                )
                // CTA theo code: MAX_STREAMS -> [Cho vao hang doi] [Theo doi user] [Thu 720p/audio]
                if (isErr && liveVM.livestreamErrorCode == "MAX_STREAMS") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "NAS đang bận (${liveVM.serverLivestreamActive}/${liveVM.serverLivestreamCap})" +
                            if (liveVM.livestreamErrorRetryAfterS > 0) " — thử lại sau ${liveVM.livestreamErrorRetryAfterS}s" else "",
                        color = TextTertiary, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val tiktokUserForQueue: String = if (liveUrl.contains("tiktok", true)) {
                            TikTokProfileUrlRegex.find(liveUrl)?.groupValues?.get(1) ?: ""
                        } else ""
                        OutlinedButton(
                            onClick = { liveVM.enqueueLivestreamQueue(liveUrl.trim(), selectedQuality, tiktokUserForQueue) },
                            modifier = Modifier.weight(1f).height(38.dp)
                        ) { Text("Cho vào hàng đợi", fontSize = 12.sp) }
                        if (tiktokUserForQueue.isNotBlank()) {
                            OutlinedButton(
                                onClick = { liveVM.addTikTokLiveWatchUser(tiktokUserForQueue) },
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) { Text("Theo dõi @$tiktokUserForQueue", fontSize = 12.sp) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    selectedQuality = "720p"
                                    liveVM.clearLivestreamMessage()
                                    liveVM.startLivestreamRecord(liveUrl.trim(), "720p")
                                },
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) { Text("Thử 720p nhẹ hơn", fontSize = 12.sp) }
                        }
                    }
                    // Hang doi server-side: hien thi so thu tu + nut huy (client khong giu local).
                    if (liveVM.livestreamQueue.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Hàng đợi server (${liveVM.livestreamQueueCount}/${liveVM.livestreamQueueCap})",
                            color = TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        liveVM.livestreamQueue.take(5).forEachIndexed { idx, e ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Text(
                                    "#${e.position.takeIf { it > 0 } ?: (idx + 1)} ${e.url.take(30)}",
                                    color = TextSecondary, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (e.queueId.isNotBlank()) {
                                    TextButton(
                                        onClick = { liveVM.cancelLivestreamQueue(e.queueId) },
                                        modifier = Modifier.height(28.dp)
                                    ) { Text("Hủy", fontSize = 12.sp, color = AccentRed) }
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                NasGradientButton(
                    onClick = {
                        if (liveUrl.isNotBlank() && !liveVM.isStartingLivestream) {
                            // SPEC_LIVESTREAM_LIMITS §4.3: chi coi VOD khi chac
                            // (/video/, watch?v=, youtu.be non-live, /reel/, /shorts/).
                            // tiktok.com/t/, vt/vm.tiktok.com phai resolve ra @user truoc
                            // roi moi phan loai — tranh mat kha nang record short link.
                            val lower = liveUrl.lowercase()
                            val isTikTokShortUnresolved = (lower.contains("tiktok.com/t/") ||
                                lower.contains("vt.tiktok.com") || lower.contains("vm.tiktok.com")) &&
                                !liveUrl.contains("@")
                            val isVOD = if (isTikTokShortUnresolved || isResolvingTikTokLink) false else (
                                liveUrl.contains("/video/") ||
                                    liveUrl.contains("watch?v=") ||
                                    (liveUrl.contains("youtu.be") && !lower.contains("/live")) ||
                                    lower.contains("/reel") || lower.contains("/shorts/")
                                )
                            // Bóc tách username TikTok tu URL (sau khi resolver da chay xong)
                            // de tu dong them vao danh sach theo doi — lan sau watchdog tu phat hien live.
                            val tiktokUsername: String? = if (liveUrl.contains("tiktok", true)) {
                                TikTokProfileUrlRegex.find(liveUrl)?.groupValues?.get(1)
                            } else null
                            if (isVOD) {
                                // Tự động phát hiện Video On Demand (VOD) thay vì Livestream
                                // Chuyển hướng sang yt-dlp nhưng lưu vào Livestream/ để user dễ tìm
                                liveVM.requestSocialDownload(liveUrl.trim(), AppConfig.LIVESTREAM_FOLDER)
                                onDismiss()
                            } else {
                                liveVM.startLivestreamRecord(liveUrl.trim(), selectedQuality)
                            }
                        }
                    },
                    text = if (isResolvingTikTokLink) "ĐANG LẤY USER..." else "BẮT ĐẦU GHI",
                    enabled = liveUrl.isNotBlank() && !isResolvingTikTokLink,
                    height = 42.dp,
                    shape = RoundedCornerShape(10.dp),
                    icon = { Icon(Icons.Default.AddCircle, null, tint = TextPrimary, modifier = Modifier.size(18.dp)) }
                )
            }
            }

            Spacer(Modifier.height(6.dp))

            // --- PHẦN 2: DANH SÁCH CÁC JOB ĐANG GHI ---
            // Ẩn mặc định, bấm header để mở (toggle "active" panel). Khi mở sẽ tự
            // động đóng các panel khác (watchlist + exclude) thông qua LivestreamPanelState.
            val activeExpanded = LivestreamPanelState.current.value == "active"
            if (activeLivestreams.isNotEmpty()) {
                HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current
                        ) { LivestreamPanelState.toggle("active") }
                ) {
                    val pulse = rememberInfiniteTransition(label = "rec_pulse")
                    val alpha by pulse.animateFloat(initialValue = 1f, targetValue = 0.4f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "rec_alpha")
                    Box(Modifier.size(8.dp).background(AccentRed.copy(alpha = alpha), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ĐANG GHI HÌNH (${activeLivestreams.size})",
                        color = TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (activeExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (activeExpanded) "Ẩn" else "Mở",
                        tint = TextSecondary,
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
                                Box(Modifier.size(10.dp).background(AccentRed.copy(alpha = alpha), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("GHI HÌNH", color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                                    Text("Thời gian chạy", color = TextTertiary, fontSize = 12.sp)
                                    var localSeconds by remember(job.jobId) { mutableStateOf(job.durationSeconds) }
                                    LaunchedEffect(job.jobId, job.durationSeconds) {
                                        localSeconds = job.durationSeconds
                                    }
                                    LaunchedEffect(job.jobId) {
                                        while (true) {
                                            delay(1000)
                                            localSeconds++
                                        }
                                    }
                                    val displayDur = "${localSeconds / 3600}h${String.format(java.util.Locale.US, "%02d", (localSeconds % 3600) / 60)}m${String.format(java.util.Locale.US, "%02d", localSeconds % 60)}s"
                                    Text(displayDur, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                // Cot 2: Toc do (giua, ngang voi 2 cot kia)
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Tốc độ", color = TextTertiary, fontSize = 12.sp)
                                    Text(job.speed.ifEmpty { "—" }, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                // Cot 3: Dung luong (align phai)
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text("Dung lượng", color = TextTertiary, fontSize = 12.sp)
                                    Text(job.fileSize.ifEmpty { "0 B" }, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (job.outputFile.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(job.outputFile, color = TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { liveVM.stopLivestreamRecord(job.jobId) },
                                modifier = Modifier.fillMaxWidth().height(38.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Stop, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("DỪNG GHI", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
