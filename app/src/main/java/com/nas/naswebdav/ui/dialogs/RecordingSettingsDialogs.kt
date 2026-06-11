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

// ============ Livestream recording + settings dialogs (biometric/bandwidth/sleep)
//              (tách cơ học từ Dialogs.kt — không đổi logic) ============

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

