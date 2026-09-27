@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import android.webkit.*
import android.widget.Toast
import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*
import com.nas.naswebdav.utils.FormatUtils
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.session.MediaSession
import android.app.PictureInPictureParams
import android.util.Rational
import android.content.Intent
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri


// ════════════════════════════════════════════════════════════════════════════
// ImageViewerScreen.kt — REDESIGNED Professional Photo Viewer (Phase 6)
// ════════════════════════════════════════════════════════════════════════════

/**
 * Chuyển NasFile sang content URI Android (dùng cho share + delete qua SAF nếu cần).
 * Hiện tại chỉ dùng cho Intent.ACTION_SEND.
 */
private fun shareText(context: android.content.Context, text: String, mime: String = "text/plain") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
}

private fun shareImageUrl(
    context: android.content.Context,
    url: String,
    fileName: String
) {
    try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, fileName)
            // Một số app nhận EXTRA_STREAM — cố gắng lấy qua Uri.parse
            putExtra(Intent.EXTRA_STREAM, url.toUri())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Chia sẻ ảnh")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        Toast.makeText(context, "Không thể chia sẻ: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun ImageViewerScreen(
    initialUrl: String,
    user: String,
    pass: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val fileBrowserVM = LocalFileBrowserVM.current

    // ============ IMAGE LIST ============
    val imageFiles = remember(fileBrowserVM.fileList) {
        fileBrowserVM.fileList.filter { com.nas.naswebdav.utils.MediaUtils.isImage(it.name) }
    }

    val initialPage = remember(imageFiles, initialUrl) {
        val index = imageFiles.indexOfFirst { it.path == initialUrl }
        if (index >= 0) index else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (imageFiles.size - 1).coerceAtLeast(0)),
        pageCount = { imageFiles.size }
    )

    // ============ IMMERSIVE / AUTO-HIDE CONTROLS ============
    var showControls by remember { mutableStateOf(true) }
    var isSlideshowActive by remember { mutableStateOf(false) }

    // Tự ẩn thanh header/footer sau 2s nếu không có tương tác
    LaunchedEffect(showControls, isSlideshowActive, pagerState.currentPage) {
        if (showControls && imageFiles.isNotEmpty()) {
            delay(2000)
            showControls = false
        }
    }

    // ============ DELETE DIALOG ============
    var showDeleteDialog by remember { mutableStateOf(false) }

    // ============ SLIDESHOW TIMER ============
    LaunchedEffect(isSlideshowActive, pagerState.currentPage) {
        if (isSlideshowActive && imageFiles.isNotEmpty()) {
            delay(3000)
            val next = (pagerState.currentPage + 1) % imageFiles.size
            pagerState.scrollToPage(next)
        }
    }

    // ============ CLEANUP ============
    DisposableEffect(Unit) {
        onDispose {
            val imageLoader = coil.Coil.imageLoader(context)
            imageLoader.memoryCache?.clear()
            val cachePrefs = com.nas.naswebdav.utils.PreferencesRepository.get(context)
            val lastClearTime = cachePrefs.getLastCacheClear()
            val now = System.currentTimeMillis()
            if (now - lastClearTime > 7 * 24 * 60 * 60 * 1000L) {
                imageLoader.diskCache?.clear()
                cachePrefs.setLastCacheClear(now)
            }
        }
    }

    // ============ DELETE UX ============
    val coroutineScope = rememberCoroutineScope()

    // Sau khi xóa thành công:
    //  - Nếu còn ảnh: tự động advance (next hoặc previous nếu đang ở cuối)
    //  - Nếu hết: gọi onBack()
    val handleConfirmDelete: (NasFile) -> Unit = { fileToDelete ->
        val wasLast = imageFiles.size <= 1
        val wasAtEnd = pagerState.currentPage >= imageFiles.size - 1
        fileBrowserVM.deleteFile(context, fileToDelete)
        showDeleteDialog = false
        Toast.makeText(context, "Đã chuyển vào Thùng rác", Toast.LENGTH_SHORT).show()
        if (wasLast) {
            onBack()
        } else if (wasAtEnd) {
            // Ảnh tiếp theo sẽ tự động là previous image (vì list đã giảm 1)
            coroutineScope.launch {
                val newCurrentPage = (pagerState.currentPage - 1).coerceAtLeast(0)
                try {
                    pagerState.scrollToPage(newCurrentPage)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    // ============ DELETE DIALOG ============
    if (showDeleteDialog && imageFiles.isNotEmpty()) {
        val currentFile = imageFiles[pagerState.currentPage.coerceIn(0, imageFiles.lastIndex)]
        AppStatusDialog(
            type = DialogType.CONFIRM,
            message = "Bạn có chắc muốn xóa\n\"${currentFile.name}\"?\n\nẢnh sẽ được chuyển vào Thùng rác.",
            onConfirm = { handleConfirmDelete(currentFile) },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DarkSurface) // pure black immersive background
    ) {
        if (imageFiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.ImageNotSupported, null, tint = TextTertiary, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Không có ảnh nào để hiển thị", color = TextTertiary)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Quay lại") }
            }
            return@Box
        }

        // ============ PAGER + ZOOM ============
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { imageFiles[it].path },
            userScrollEnabled = true
        ) { page ->
            val file = imageFiles[page]
            ZoomableImage(
                path = file.path,
                auth = WebDavManager.AuthState(user = user, pass = pass).authHeader,
                fileName = file.name,
                onTap = { showControls = !showControls },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ============ TOP TOOLBAR (auto-hide) ============
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(DarkSurface.copy(alpha = 0.7f), Color.Transparent)
                        )
                    )
            ) {
                Spacer(Modifier.statusBarsPadding())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (isSlideshowActive) {
                            isSlideshowActive = false
                            showControls = true
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            if (isSlideshowActive) Icons.Default.Stop else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = TextPrimary
                        )
                    }

                    // File counter
                    val total = imageFiles.size
                    val current = (pagerState.currentPage + 1).coerceAtMost(total)
                    Text(
                        text = "$current / $total",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )

                    // Slideshow toggle
                    IconButton(onClick = {
                        isSlideshowActive = !isSlideshowActive
                        showControls = true
                    }) {
                        Icon(
                            if (isSlideshowActive) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = "Trình chiếu",
                            tint = if (isSlideshowActive) AccentGreen else TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Share
                    IconButton(onClick = {
                        val currentFile = imageFiles[pagerState.currentPage]
                        shareImageUrl(context, currentFile.path, currentFile.name)
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Chia sẻ",
                            tint = TextPrimary
                        )
                    }

                    // Delete
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Xóa",
                            tint = AccentRed,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }

        // ============ BOTTOM PANEL (INFO BAR + THUMBNAIL STRIP) ============
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // 1. File Info Bar (auto-hide with showControls)
            AnimatedVisibility(
                visible = showControls && imageFiles.isNotEmpty(),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                val currentFile = imageFiles[pagerState.currentPage.coerceIn(0, imageFiles.lastIndex)]
                val fileSize = com.nas.naswebdav.utils.FormatUtils.formatBytes(currentFile.contentLength)
                val total = imageFiles.size
                val current = (pagerState.currentPage + 1).coerceAtMost(total)

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, DarkSurface.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = currentFile.name,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = fileSize,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        Box(
                            Modifier
                                .height(12.dp)
                                .width(1.dp)
                                .background(TextSecondary.copy(alpha = 0.5f))
                        )
                        Text(
                            text = "$current / $total",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // 2. Thumbnail Strip (auto-hide with showControls)
            AnimatedVisibility(
                visible = showControls && imageFiles.isNotEmpty(),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                ThumbnailStrip(
                    imageFiles = imageFiles,
                    currentPage = pagerState.currentPage,
                    user = user,
                    pass = pass,
                    onThumbClick = { idx ->
                        coroutineScope.launch {
                            pagerState.scrollToPage(idx)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// ZoomableImage — composable con cho pager page. Pinch-to-zoom + double-tap.
// ════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(
    path: String,
    auth: String,
    fileName: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Reset zoom khi đổi ảnh
    LaunchedEffect(path) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    val maxScale = 5f
    val minScale = 1f

    fun clampOffsets() {
        val maxPan = (scale - 1f) * 1000f
        offsetX = offsetX.coerceIn(-maxPan, maxPan)
        offsetY = offsetY.coerceIn(-maxPan, maxPan)
    }

    Box(
        modifier = modifier
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1f) {
                            // Zoom out về 1f
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            // Zoom in 2.5x, căn giữa tap point
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val targetOffset = (center - tapOffset) * (scale - 1f)
                            val maxPan = (scale - 1f) * 1000f
                            offsetX = targetOffset.x.coerceIn(-maxPan, maxPan)
                            offsetY = targetOffset.y.coerceIn(-maxPan, maxPan)
                        }
                    }
                )
            }
            .pointerInput(path) {
                // Multi-touch zoom — single-finger pan is consumed when zoomed (to prevent
                // accidental page-swipe via HorizontalPager). Single-finger passes through only
                // when scale == 1f (not zoomed), so the user can swipe to the next image.
                awaitEachGesture {
                    var wasMultiTouch = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val anyPressed = ev.changes.any { it.pressed }
                        if (!anyPressed) break
                        if (ev.changes.size >= 2) {
                            wasMultiTouch = true
                            val c = ev.changes
                            val oldDist = kotlin.math.hypot(
                                c[0].previousPosition.x - c[1].previousPosition.x,
                                c[0].previousPosition.y - c[1].previousPosition.y
                            ).coerceAtLeast(1f)
                            val newDist = kotlin.math.hypot(
                                c[0].position.x - c[1].position.x,
                                c[0].position.y - c[1].position.y
                            ).coerceAtMost(1f)
                            val zoom = newDist / oldDist
                            val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                            scale = newScale
                            if (newScale > 1f) {
                                val cx = (c[0].position.x + c[1].position.x) / 2f
                                val cy = (c[0].position.y + c[1].position.y) / 2f
                                val oldCx = (c[0].previousPosition.x + c[1].previousPosition.x) / 2f
                                val oldCy = (c[0].previousPosition.y + c[1].previousPosition.y) / 2f
                                offsetX += cx - oldCx
                                offsetY += cy - oldCy
                                clampOffsets()
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            c.forEach { if (it.pressed) it.consume() }
                        } else if (ev.changes.size == 1 && wasMultiTouch && scale > 1f) {
                            // After a pinch, pan with the remaining single finger while zoomed.
                            // Consume so the event doesn't bubble to HorizontalPager.
                            ev.changes.forEach { it.consume() }
                        }
                        // Single-finger with no prior multi-touch and scale == 1f:
                        // do NOT consume → HorizontalPager handles swipe naturally.
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(path)
                .addHeader("Authorization", auth)
                .size(1920, 1080)
                .allowHardware(true) // performance — ảnh lớn render mượt
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .crossfade(true)
                .build(),
            contentDescription = fileName,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
            contentScale = ContentScale.Fit
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// ThumbnailStrip — danh sách thumbnail ngang: click → chuyển ảnh, auto-scroll khi swipe
// ════════════════════════════════════════════════════════════════════════════
@Composable
private fun ThumbnailStrip(
    imageFiles: List<NasFile>,
    currentPage: Int,
    user: String,
    pass: String,
    onThumbClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val auth = WebDavManager.AuthState(user = user, pass = pass).authHeader
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Auto-scroll khi currentPage đổi (do swipe pager)
    LaunchedEffect(currentPage, imageFiles.size) {
        if (imageFiles.isEmpty()) return@LaunchedEffect
        try {
            // Đợi 1 frame để tránh race khi navigate ngay lúc mount
            kotlinx.coroutines.delay(50)
            val visible = listState.layoutInfo.visibleItemsInfo
            val visibleAt = visible.firstOrNull()?.index ?: -1
            val visibleEnd = visible.lastOrNull()?.index ?: -1
            // Chỉ animateScrollToItem nếu thumbnail hiện tại đang ngoài tầm nhìn
            if (currentPage !in visibleAt..visibleEnd) {
                listState.animateScrollToItem(
                    index = currentPage,
                    scrollOffset = -40
                )
            } else {
                listState.scrollToItem(currentPage, scrollOffset = -40)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, DarkSurface.copy(alpha = 0.55f))
                )
            )
            .padding(vertical = 6.dp)
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            items(
                count = imageFiles.size,
                key = { idx -> imageFiles[idx].path }
            ) { idx ->
                val file = imageFiles[idx]
                val isCurrent = idx == currentPage
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(AppShapes.Badge)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) AccentCyan else TextSecondary.copy(alpha = 0.3f),
                            shape = AppShapes.Badge
                        )
                        .clickable { onThumbClick(idx) }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(file.path)
                            .addHeader("Authorization", auth)
                            .size(160, 160)
                            .allowHardware(true)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .crossfade(true)
                            .build(),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}



// ════════════════════════════════════════════════════════════════════════════
// VideoPlayerScreen.kt
// ════════════════════════════════════════════════════════════════════════════

// Định nghĩa hằng số Action cho PiP Broadcast
private const val PIP_ACTION_REWIND = "com.nas.naswebdav.PIP_REWIND"
private const val PIP_ACTION_PLAY_PAUSE = "com.nas.naswebdav.PIP_PLAY_PAUSE"
private const val PIP_ACTION_FAST_FORWARD = "com.nas.naswebdav.PIP_FAST_FORWARD"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, user: String, pass: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val globalUiVM = LocalGlobalUiVM.current
    val fileBrowserVM = LocalFileBrowserVM.current
    val resolvedAuth = remember(user, pass) {
        if (user.isNotBlank() && pass.isNotBlank()) {
            user to pass
        } else {
            val authData = SecurePrefsHelper.readEncrypted(context.applicationContext)
            if (authData is SecurePrefsHelper.AuthData.Valid) {
                val savedUser = String(authData.user)
                val savedPass = String(authData.pass)
                authData.clear()
                savedUser to savedPass
            } else {
                user to pass
            }
        }
    }
    val resolvedUser = resolvedAuth.first
    val resolvedPass = resolvedAuth.second

    // Trạng thái theo dõi chế độ Popup (PiP)
    var isInPiP by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    // Gắn theo dõi trạng thái hiển thị của thanh ExoPlayer Controller
    var isControllerVisible by remember { mutableStateOf(true) }
    
    // Trạng thái riêng cho các nút overlay Compose (Mute, PiP, Repeat...)
    // Luôn hiện khi chạm màn hình, tự ẩn sau 3 giây
    var showOverlayButtons by remember { mutableStateOf(true) }
    
    // Trạng thái mới: Tắt tiếng (Mute) và Lặp lại (Repeat)
    var isMuted by remember { mutableStateOf(false) }
    var isRepeat by remember { mutableStateOf(false) }
    var playerIsPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }

    // Cập nhật trạng thái hiển thị overlay lập tức theo ExoPlayer, bỏ delay
    LaunchedEffect(isControllerVisible) {
        showOverlayButtons = isControllerVisible
    }

    // NAS CHI LUU TRU — moi dinh dang do dien thoai giai ma truc tiep tu file
    // goc (direct-play). Khong con duong transcode len NAS (ffmpeg dot CPU).
    // May khong decode duoc (codec la) -> dialog loi da co san huong dan mo
    // bang VLC/MX Player qua proxy cuc bo.
    val effectiveUrl = remember(url) {
        url.toFastMediaUrl() // MP4, MKV, MOV, TS, WEBM, AVI, WMV, FLV... — endpoint Range/ETag toi uu LAN
    }

    // Khong bao gio transcode tren NAS nua.
    val isTranscoding = false

    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPiP = info.isInPictureInPictureMode
        }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            if (activity is VideoPlayerActivity) {
                activity.isPlayingVideo = false
            }
            activity?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    // ═══ KHỞI TẠO EXOPLAYER TỐI ƯU CỰC ĐẠI CHO NAS STREAMING ═══
    val exoPlayer = remember {
        val app = NasApplication.instance

        // 1. LOAD CONTROL — Chống OOM (Tràn RAM) khi Stream Video dung lượng khủng
        // Gốc (250s) gây crash Out Of Memory lập tức với video 4K/bluray. Giữ mức tối đa 50s.
        // TÍNH TOÁN RAM ĐỘNG CHO EXO PLAYER
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        // Dành 15% RAM trống hiện tại làm bộ đệm video. Tối thiểu 50MB, tối đa 500MB để tránh OOM.
        val dynamicBufferBytes = (memoryInfo.availMem * 0.15).toLong()
            .coerceIn(50L * 1024 * 1024, 500L * 1024 * 1024).toInt()
            
        android.util.Log.i("VideoPlayer", "Dynamic Buffer allocated: ${dynamicBufferBytes / 1024 / 1024} MB")

        val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(true, androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                10_000,   // Min buffer: nạp 10s là đủ để duy trì mượt
                120_000,  // Max buffer: nạp trước tối đa 2 phút (đủ xem xuyên qua mạng chập chờn)
                250,      // Ngưỡng mới play cực thấp (0.25s) để play ngay lập tức
                500       // Ngưỡng re-buffer cực thấp
            )
            .setTargetBufferBytes(dynamicBufferBytes) // Dùng cấp phát RAM động
            .setBackBuffer(30_000, true)  // Giữ 30s lui để tua lại mượt hơn
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // 2. RENDERERS
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // 3. DATA SOURCE — BỎ HOÀN TOÀN DISK CACHE (Zero Disk IO)
        // Khi dùng mạng LAN ổ NAS quay siêu nhanh, nếu ép điện thoại chép Video vào bộ nhớ Flash 
        // lúc tua (Seeking) sẽ bị thắt cổ chai vòng quay (Flash memory Write Speed quá thấp). 
        // -> Đọc thẳng luồng stream từ NAS đổ vào RAM hiển thị luôn!
        // AUTH FIX: use UTF-8 Base64 auth header instead of ISO-8859-1 Credentials.basic().
        // The client interceptor supplies current auth only when no explicit header is present.
        val authHeader = WebDavManager.AuthState(user = resolvedUser, pass = resolvedPass).authHeader
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(app.videoStreamingClient)
            .setDefaultRequestProperties(mapOf("Authorization" to authHeader))

        // 4. EXTRACTORS - Tối ưu mạnh mẽ để quét được độ dài (00:00 bug fix)
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            // Tắt ConstantBitrateSeeking vì nó chặn tua MP4 bị lỗi header
            .setConstantBitrateSeekingEnabled(false)
            // Quét sâu tới 5MB cuối file thay vì 1MB để chắc chắn moi được Metadata thời lượng (chữa lỗi 00:00)
            .setTsExtractorTimestampSearchBytes(5_000_000)

        // 5. MEDIA SOURCE FACTORY
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // 6. MIME TYPE cho format gốc (nếu không dùng transcode)
        val mimeType = if (isTranscoding) {
            androidx.media3.common.MimeTypes.APPLICATION_M3U8  // Transcode only for legacy formats
        } else {
            null  // Let ExoPlayer auto-detect for all modern formats (mp4, mkv, mov, webm, ts)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(effectiveUrl)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        // 7. BUILD EXOPLAYER BẢN GỐC
        val basePlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(app.bandwidthMeter)
            .build()
            .apply {
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val invalidResponseCode = generateSequence(error.cause) { it.cause }
                            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()
                            ?.responseCode
                        android.util.Log.e("VideoPlayer", "Lỗi: ${error.errorCodeName} - ${error.message}, HTTP=$invalidResponseCode")
                        // Bắt lỗi khi phần cứng điện thoại (Hardware Decoder) KHÔNG HỖ TRỢ định dạng 
                        // Ví dụ: Video 4K HDR HEVC trên máy tính bảng cũ, hoặc Âm thanh Dolby AC3 trong file MKV.
                        // Tại đây, ta báo cho giao diện bật Dialog hỏi chuyển sang VLC
                        (activity as? MainActivity)?.runOnUiThread {
                            globalUiVM.showCommonDialog = true
                            globalUiVM.commonDialogType = DialogType.ERROR
                            globalUiVM.commonDialogMessage = if (invalidResponseCode == 416) {
                                "Tệp MP4 này bị hỏng hoặc chưa được hoàn tất metadata (HTTP 416, ${error.errorCodeName}).\n\nNAS sẽ tự ẩn các bản ghi livestream thiếu moov atom sau khi dọn nền. Vui lòng chọn một bản ghi khác hoặc ghi lại livestream."
                            } else if (invalidResponseCode != null) {
                                "Không thể tải luồng video từ NAS (HTTP $invalidResponseCode, ${error.errorCodeName}).\n\nVui lòng thử lại sau vài giây hoặc nhấn nút [Mở bằng ứng dụng ngoài] (biểu tượng mũi tên) để xem bằng VLC/MX Player qua proxy cục bộ."
                            } else {
                                "Thiết bị của bạn không hỗ trợ giải mã định dạng phim này (Lỗi: ${error.errorCodeName}).\n\nVui lòng nhấn nút [Mở bằng ứng dụng ngoài] (biểu tượng mũi tên) để xem bằng VLC hoặc MX Player."
                            }
                        }
                    }
                    
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        super.onVideoSizeChanged(videoSize)
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val act = activity as? VideoPlayerActivity
                            if (act != null) {
                                // 1. Truyền tỉ lệ thực tế về VideoPlayerActivity để nó dùng khi gọi từ phím Home (onUserLeaveHint)
                                act.videoAspectRatio = android.util.Rational(videoSize.width, videoSize.height)
                                
                                // 2. Lập tức cập nhật System PiP Params trên Android 12+ (Auto-PiP behavior)
                                updatePipActions(act, this@apply)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        super.onIsPlayingChanged(isPlaying)
                        playerIsPlaying = isPlaying
                        // Khóa sáng màn hình khi ĐANG PHÁT, tự động cho ngủ màn hình khi PAUSE
                        if (isPlaying) {
                            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        
                        // Bắt buộc: Tính năng PiP khi vuốt Home chỉ kích hoạt nếu video ĐANG PHÁT.
                        // Tránh lỗi khi người dùng ấn "Mở bằng VLC", ứng dụng này bị văng ra sau (onUserLeaveHint) 
                        // và vô tình bật PiP đè lên cả VLC.
                        (activity as? VideoPlayerActivity)?.isPlayingVideo = isPlaying
                    }
                })
                setMediaItem(mediaItem)
                
                // Đồng bộ Volume và Repeat Mode khởi tạo
                volume = if (isMuted) 0f else 1f
                repeatMode = if (isRepeat) androidx.media3.common.Player.REPEAT_MODE_ALL else androidx.media3.common.Player.REPEAT_MODE_OFF
                
                prepare()
                playWhenReady = true
            }

        // 8. BỌC EXOPLAYER TRONG FORWARDING PLAYER ĐỂ TÙY DỤNG GIA TỐC TUA THEO ĐỘ DÀI TRUYỆN/VIDEO
        object : androidx.media3.common.ForwardingPlayer(basePlayer) {
            private fun getDynamicSeekIncrement(): Long {
                val dur = duration
                return when {
                    dur == androidx.media3.common.C.TIME_UNSET -> 15000L
                    dur > 3600_000L -> 60000L // Dài hơn 1 tiếng -> tua 60s
                    dur > 1800_000L -> 30000L // Dài hơn 30 phút -> tua 30s
                    dur < 300_000L -> 5000L   // Ngắn hơn 5 phút -> tua 5s
                    else -> 15000L            // Mặc định 15s
                }
            }

            override fun getSeekForwardIncrement(): Long = getDynamicSeekIncrement()
            override fun getSeekBackIncrement(): Long = getDynamicSeekIncrement()

            override fun seekForward() {
                seekTo((currentPosition + getDynamicSeekIncrement()).coerceAtMost(duration.coerceAtLeast(0L)))
            }

            override fun seekBack() {
                seekTo((currentPosition - getDynamicSeekIncrement()).coerceAtLeast(0L))
            }
        }
    }

    // Effect để lắng nghe thay đổi trạng thái và áp dụng ngay lập tức
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }
    LaunchedEffect(isRepeat) {
        exoPlayer.repeatMode = if (isRepeat) androidx.media3.common.Player.REPEAT_MODE_ALL else androidx.media3.common.Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            playbackPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            playbackDurationMs = exoPlayer.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            delay(500)
        }
    }

    // MediaSession đơn giản — chỉ cần cho Android biết đang phát media
    val mediaSession = remember {
        MediaSession.Builder(context, exoPlayer).build()
    }

    // Lưu giữ PlayerView reference để cập nhật trạng thái PiP
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }

    // ============ BROADCAST RECEIVER CHO NÚT PIP ============
    // Đăng ký BroadcastReceiver để lắng nghe lệnh từ nút PiP của HĐH
    DisposableEffect(exoPlayer) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    PIP_ACTION_REWIND -> {
                        exoPlayer.seekBack()
                    }
                    PIP_ACTION_PLAY_PAUSE -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        // Cập nhật lại icon Play/Pause trên PiP
                        activity?.let { updatePipActions(it, exoPlayer) }
                    }
                    PIP_ACTION_FAST_FORWARD -> {
                        exoPlayer.seekForward()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(PIP_ACTION_REWIND)
            addAction(PIP_ACTION_PLAY_PAUSE)
            addAction(PIP_ACTION_FAST_FORWARD)
        }
        // QUAN TRỌNG: Phải dùng RECEIVER_EXPORTED để HĐH Android gửi được Broadcast vào App
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.d("VideoPlayer", "Receiver đã được gỡ hoặc không tồn tại: ${e.message}")
            }
            exoPlayer.release()
            mediaSession.release()
        }
    }

    // TỐI ƯU HÓA 12.F: Sát thủ rò rỉ âm thanh (Ghost Audio)
    // QUAN TRỌNG: Lấy LifecycleOwner bằng thư viện chuẩn của Compose UI
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val act = context as? android.app.Activity
                val pip = act?.isInPictureInPictureMode ?: false
                if (!pip) {
                    exoPlayer.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(Modifier.fillMaxSize().background(DarkSurface)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true // NGĂN TẮT MÀN HÌNH KHI PHÁT VIDEO
                    // Ép video scale lấp đầy khung hình (Xóa viền đen 2 bên)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    post {
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_center_controls)?.apply {
                            visibility = android.view.View.GONE
                            background = null
                        }
                    }
                    
                    playerViewRef.value = this
                }
            },
            update = { view ->
                view.useController = false
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay buttons nằm trên PlayerView nhưng KHÔNG chặn touch (chỉ đọc)
        Box(Modifier.fillMaxSize()) {

        // Chỉ hiển thị các nút điều khiển khi KHÔNG ở chế độ Popup
        androidx.compose.animation.AnimatedVisibility(
            visible = !isInPiP && showOverlayButtons,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Tiêu đề Video & Nút Back
                val fileName = url.substringAfterLast("/").let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { it }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(DarkSurface.copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                        .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = fileName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, DarkSurface.copy(alpha = 0.62f))
                        )
                    )
                    .padding(start = 10.dp, end = 10.dp, bottom = 12.dp, top = 18.dp)
            ) {
                Slider(
                    value = if (playbackDurationMs > 0L) {
                        playbackPositionMs.coerceIn(0L, playbackDurationMs).toFloat()
                    } else {
                        0f
                    },
                    onValueChange = { value ->
                        if (playbackDurationMs > 0L) {
                            exoPlayer.seekTo(value.toLong().coerceIn(0L, playbackDurationMs))
                        }
                    },
                    valueRange = 0f..playbackDurationMs.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = TextPrimary,
                        activeTrackColor = AccentOrange,
                        inactiveTrackColor = TextPrimary.copy(alpha = 0.42f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { exoPlayer.seekTo(0L) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.SkipPrevious, "Về đầu", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { exoPlayer.seekBack() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.Replay30, "Tua lùi", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                if (playerIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Phát / tạm dừng",
                                tint = TextPrimary,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                        IconButton(onClick = { exoPlayer.seekForward() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.Forward30, "Tua tới", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                val duration = exoPlayer.duration
                                if (duration > 0L && duration != androidx.media3.common.C.TIME_UNSET) {
                                    exoPlayer.seekTo(duration)
                                }
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.SkipNext, "Tới cuối", tint = TextPrimary.copy(alpha = 0.65f), modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = "${FormatUtils.formatPlayerTime(playbackPositionMs)} / ${FormatUtils.formatPlayerTime(playbackDurationMs)}",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { isMuted = !isMuted },
                            modifier = Modifier.minimumInteractiveComponentSize().semantics {
                                stateDescription = if (isMuted) "Tắt tiếng: Bật" else "Tắt tiếng: Tắt"
                                role = Role.Button
                            }
                        ) {
                            Icon(
                                if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                if (isMuted) "Đang tắt tiếng" else "Đang bật tiếng",
                                tint = if (isMuted) AccentRed else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { isRepeat = !isRepeat },
                            modifier = Modifier.minimumInteractiveComponentSize().semantics {
                                stateDescription = if (isRepeat) "Lặp lại: Bật" else "Lặp lại: Tắt"
                                role = Role.Button
                            }
                        ) {
                            Icon(
                                Icons.Default.Repeat,
                                if (isRepeat) "Đang lặp lại" else "Không lặp lại",
                                tint = if (isRepeat) AccentGreen else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { activity?.let { act -> enterPipMode(act, exoPlayer) } }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.PictureInPictureAlt, "Popup", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                exoPlayer.pause()
                                openExternalVideoPlayer(
                                    context = context,
                                    // Pass original WebDAV URL so external-player helper can rewrite /webdav/ → /media/.
                                    url = url,
                                    user = resolvedUser,
                                    pass = resolvedPass,
                                    onError = { android.util.Log.e("VideoPlayer", "Không mở được trình phát ngoài") }
                                )
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.OpenInNew, "Mở bằng ứng dụng ngoài", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Default.Delete, "Xóa video", tint = AccentRed, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Dialog xác nhận xóa video
            if (showDeleteDialog) {
                val fileName = url.substringAfterLast("/").let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { it }
                }
                AppStatusDialog(
                    type = DialogType.WARNING,
                    message = "Bạn có chắc muốn xóa\n\"$fileName\"?\n\nVideo sẽ được chuyển vào Thùng rác.",
                    onConfirm = {
                        showDeleteDialog = false
                        exoPlayer.pause()
                        val fileToDelete = NasFile(fileName, url, false, "video/*", 0, 0)
                        fileBrowserVM.deleteFile(context, fileToDelete)
                        onBack()
                    },
                    onDismiss = { showDeleteDialog = false }
                )
            }
            } // Close Box (AnimatedVisibility content)
        } // Close AnimatedVisibility
        } // Close touch-intercept Box
    } // Close outer Box
} // Close VideoPlayerScreen
// ============ HÀM TẠO NÚT PIP ============

/** Tạo danh sách 3 nút: Tua lùi 15s | Play/Pause | Tua tới 15s */
private fun buildPipActions(
    context: android.content.Context,
    isPlaying: Boolean
): List<android.app.RemoteAction> {
    // Nút 1: Tua lùi 15s
    val rewindAction = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_rew),
        "Tua lùi", "Tua lùi 15 giây",
        android.app.PendingIntent.getBroadcast(
            context, 1,
            android.content.Intent(PIP_ACTION_REWIND).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    // Nút 2: Play/Pause (Đổi icon phù hợp trạng thái hiện tại)
    val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
    val playPauseLabel = if (isPlaying) "Tạm dừng" else "Phát"
    val playPauseAction = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(context, playPauseIcon),
        playPauseLabel, playPauseLabel,
        android.app.PendingIntent.getBroadcast(
            context, 2,
            android.content.Intent(PIP_ACTION_PLAY_PAUSE).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    // Nút 3: Tua tới 15s
    val forwardAction = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_ff),
        "Tua tới", "Tua tới 15 giây",
        android.app.PendingIntent.getBroadcast(
            context, 3,
            android.content.Intent(PIP_ACTION_FAST_FORWARD).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    return listOf(rewindAction, playPauseAction, forwardAction)
}
/** Clamp tỉ lệ PiP để không bị crash IllegalArgumentException */
private fun getSafePipRatio(width: Int, height: Int): Rational {
    if (width <= 0 || height <= 0) return Rational(16, 9)
    val ratio = width.toFloat() / height.toFloat()
    return when {
        ratio > 2.38f -> Rational(238, 100) // Tối đa 2.39:1
        ratio < 0.42f -> Rational(100, 238) // Tối thiểu 1:2.39 (0.4184)
        else -> Rational(width, height)
    }
}

/** Vào chế độ PiP với 3 nút điều khiển và tỉ lệ khung hình tự động */
private fun enterPipMode(activity: ComponentActivity, exoPlayer: androidx.media3.common.Player) {
    try {
        val videoSize = exoPlayer.videoSize
        // Tự động phát hiện tỉ lệ khung hình thực tế của video (thay vì hardcode 16:9)
        val aspectRatio = getSafePipRatio(videoSize.width, videoSize.height)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(buildPipActions(activity, exoPlayer.isPlaying))
            .build()
        activity.enterPictureInPictureMode(params)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        android.util.Log.e("VideoPlayer", "Thiết bị không hỗ trợ PiP: ${e.message}")
    }
}

/** Cập nhật các nút PiP (Ví dụ: đổi icon Play → Pause sau khi bấm) */
private fun updatePipActions(activity: ComponentActivity, exoPlayer: androidx.media3.common.Player) {
    try {
        val videoSize = exoPlayer.videoSize
        val aspectRatio = getSafePipRatio(videoSize.width, videoSize.height)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(buildPipActions(activity, exoPlayer.isPlaying))
            .build()
        activity.setPictureInPictureParams(params)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        // Ignored: PiP unsupported or disabled globally
    }
}



