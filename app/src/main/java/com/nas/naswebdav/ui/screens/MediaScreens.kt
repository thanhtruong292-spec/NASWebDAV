@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*
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
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(url))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Chia sẻ ảnh")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Không thể chia sẻ: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun ImageViewerScreen(
    initialUrl: String,
    viewModel: WebDavViewModel,
    user: String,
    pass: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // ============ IMAGE LIST ============
    val imageFiles = remember(viewModel.fileList) {
        viewModel.fileList.filter { com.nas.naswebdav.utils.MediaUtils.isImage(it.name) }
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
            val prefs = context.getSharedPreferences("nas_cache", android.content.Context.MODE_PRIVATE)
            val lastClearTime = prefs.getLong("last_cache_clear", 0)
            val now = System.currentTimeMillis()
            if (now - lastClearTime > 7 * 24 * 60 * 60 * 1000L) {
                imageLoader.diskCache?.clear()
                prefs.edit().putLong("last_cache_clear", now).apply()
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
        viewModel.deleteFile(context, fileToDelete)
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
                } catch (_: Exception) {}
            }
        }
    }

    // ============ DELETE DIALOG ============
    if (showDeleteDialog && imageFiles.isNotEmpty()) {
        val currentFile = imageFiles[pagerState.currentPage.coerceIn(0, imageFiles.lastIndex)]
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, null, tint = AccentRed) },
            title = { Text("Xóa ảnh?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Bạn có chắc muốn xóa\n\"${currentFile.name}\"?\n\nẢnh sẽ được chuyển vào Thùng rác.")
            },
            confirmButton = {
                Button(
                    onClick = { handleConfirmDelete(currentFile) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Hủy") }
            }
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black) // pure black immersive background
    ) {
        if (imageFiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.ImageNotSupported, null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Không có ảnh nào để hiển thị", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Quay lại") }
            }
            return@Box
        }

        // ============ PAGER + ZOOM ============
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondBoundsPageCount = 1,
            key = { imageFiles[it].path },
            userScrollEnabled = true
        ) { page ->
            val file = imageFiles[page]
            ZoomableImage(
                path = file.path,
                auth = okhttp3.Credentials.basic(user, pass),
                fileName = file.name,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Tap-anywhere-to-toggle-controls (overlay invisible layer above pager)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls }
                    )
                }
        )

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
                            listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
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
                            tint = Color.White
                        )
                    }

                    // File counter
                    val total = imageFiles.size
                    val current = (pagerState.currentPage + 1).coerceAtMost(total)
                    Text(
                        text = "$current / $total",
                        color = Color.White,
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
                            tint = if (isSlideshowActive) AccentGreen else Color.White,
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
                            tint = Color.White
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

        // ============ BOTTOM INFO BAR (auto-hide) ============
        AnimatedVisibility(
            visible = showControls && imageFiles.isNotEmpty(),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
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
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = currentFile.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = fileSize,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Box(
                        Modifier
                            .height(12.dp)
                            .width(1.dp)
                            .background(TextSecondary.copy(alpha = 0.5f))
                            .align(Alignment.CenterVertically)
                    )
                    Text(
                        text = "$current / $total",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // ============ THUMBNAIL STRIP (always visible at bottom) ============
        if (imageFiles.isNotEmpty()) {
            ThumbnailStrip(
                imageFiles = imageFiles,
                currentPage = pagerState.currentPage,
                user = user,
                pass = pass,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .navigationBarsPadding()
            )
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
                // Pinch zoom + pan
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    scale = newScale
                    if (newScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                        clampOffsets()
                    } else {
                        offsetX = 0f
                        offsetY = 0f
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
// ThumbnailStrip — danh sách thumbnail ngang, hiển thị vị trí hiện tại
// ════════════════════════════════════════════════════════════════════════════
@Composable
private fun ThumbnailStrip(
    imageFiles: List<NasFile>,
    currentPage: Int,
    user: String,
    pass: String,
    modifier: Modifier = Modifier
) {
    val auth = okhttp3.Credentials.basic(user, pass)
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Auto-scroll để thumbnail hiện tại luôn nằm trong khung nhìn
    LaunchedEffect(currentPage) {
        try {
            listState.animateScrollToItem(
                index = currentPage,
                scrollOffset = -40
            )
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
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
                        .size(40.dp)
                        .clip(AppShapes.Badge)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) AccentCyan else TextSecondary.copy(alpha = 0.3f),
                            shape = AppShapes.Badge
                        )
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


// ============ ICON MAP CHO THỂ LOẠI AI ============
private val categoryIcons = mapOf(
    "Khuôn Mặt"         to "👤",
    "Thiên Nhiên / Biển" to "🏖️",
    "Động Vật"           to "🐾",
    "Ẩm Thực"           to "🍽️",
    "Phương Tiện"        to "🚗",
    "Tài Liệu / Văn Phòng" to "📄",
    "Thể Thao"           to "⚽"
)

private val categoryColors = mapOf(
    "Khuôn Mặt"         to listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
    "Thiên Nhiên / Biển" to listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
    "Động Vật"           to listOf(Color(0xFFFC4A1A), Color(0xFFF7B733)),
    "Ẩm Thực"           to listOf(Color(0xFFFF6B6B), Color(0xFFFFE66D)),
    "Phương Tiện"        to listOf(Color(0xFF4776E6), Color(0xFF8E54E9)),
    "Tài Liệu / Văn Phòng" to listOf(Color(0xFF2193B0), Color(0xFF6DD5FA)),
    "Thể Thao"           to listOf(Color(0xFF56AB2F), Color(0xFFA8E063))
)

// ============ TAB 1: AI GALLERY (KHÁM PHÁ) ============
@Composable
private fun AiGalleryContent(
    viewModel: WebDavViewModel,
    user: String, pass: String,
    baseUrl: String
) {
    // State: đang xem chi tiết danh mục nào?
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    if (selectedCategory != null) {
        // Xem ảnh của một danh mục cụ thể
        val relPaths = viewModel.aiCategories[selectedCategory] ?: emptyList()
        AiCategoryPhotoGrid(
            categoryName = selectedCategory!!,
            relativePaths = relPaths,
            baseUrl = baseUrl,
            user = user, pass = pass,
            onBack = { selectedCategory = null }
        )
    } else {
        // Màn hình tổng quan danh mục
        AiCategoryOverview(
            viewModel = viewModel,
            onCategoryClick = { cat -> selectedCategory = cat }
        )
    }
}

@Composable
private fun AiCategoryOverview(
    viewModel: WebDavViewModel,
    onCategoryClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkSurface),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header status
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🤖 AI Gallery", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    if (viewModel.aiLastScan.isNotEmpty()) {
                        Text("Lần quét cuối: ${viewModel.aiLastScan}", fontSize = 11.sp, color = TextSecondary)
                    }
                }
                if (viewModel.aiTotal > 0) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(AccentCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("${viewModel.aiTotal} ảnh", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                    }
                }
            }
        }

        // Trạng thái loading / empty
        item {
            when {
                viewModel.isLoadingAiTags -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Đang tải dữ liệu AI...", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
                viewModel.aiCategories.isEmpty() -> {
                    AiEmptyState(viewModel.aiStatus, viewModel.aiRunning)
                }
                else -> {} // Có data → hiện dưới
            }
        }

        // Danh sách danh mục AI
        val cats = viewModel.aiCategories.entries.toList()
        // FIX: dung key callback de tranh recompose toan bo grid khi 1 cat doi
        items(count = cats.size, key = { idx -> cats[idx].key }) { idx ->
            val (catName, urls) = cats[idx]
            AiCategoryCard(
                name = catName,
                count = urls.size,
                previewUrl = if (urls.isNotEmpty()) buildFullUrl(urls[0], viewModel.webDavManager.currentBaseUrl) else null,
                user = viewModel.webDavManager.currentUser,
                pass = viewModel.webDavManager.currentPass,
                onClick = { onCategoryClick(catName) }
            )
        }
    }
}

@Composable
private fun AiCategoryCard(
    name: String, count: Int,
    previewUrl: String?, user: String, pass: String,
    onClick: () -> Unit
) {
    val icon = categoryIcons[name] ?: "🖼️"
    val colors = categoryColors[name] ?: listOf(Color(0xFF667EEA), Color(0xFF764BA2))

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            // Preview ảnh (nếu có)
            Box(
                Modifier.width(80.dp).fillMaxHeight()
                    .background(Brush.linearGradient(colors), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (previewUrl != null) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                            .data(previewUrl)
                            .addHeader("Authorization", okhttp3.Credentials.basic(user, pass))
                            .size(120, 120)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                // Gradient overlay + icon
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.horizontalGradient(listOf(Color.Transparent, DarkCard.copy(alpha = 0.3f))),
                            RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewUrl == null) {
                        Text(icon, fontSize = 28.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            // Thông tin
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
                Spacer(Modifier.height(4.dp))
                Text("$count ảnh", fontSize = 12.sp, color = TextSecondary)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextSecondary, modifier = Modifier.padding(end = 16.dp))
        }
    }
}

@Composable
private fun AiEmptyState(status: String, aiRunning: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🤖", fontSize = 48.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (aiRunning) "AI đang phân loại ảnh..." else "Chưa có dữ liệu AI",
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (aiRunning)
                "NAS đang quét và phân loại ảnh theo thư mục. Hãy quay lại sau vài phút."
            else
                "Chưa quét ảnh lần nào.\nNhấn nút ✨ để bắt đầu phân loại ảnh.",
            fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center
        )
        if (aiRunning) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(color = AccentCyan, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        }
        if (status.isNotEmpty() && status != "ok") {
            Spacer(Modifier.height(12.dp))
            Text(status, fontSize = 11.sp, color = TextSecondary.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AiCategoryPhotoGrid(
    categoryName: String,
    relativePaths: List<String>,
    baseUrl: String,
    user: String, pass: String,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(DarkSurface)) {
        // Header
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .background(DarkCard)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
            val icon = categoryIcons[categoryName] ?: "🖼️"
            Text("$icon $categoryName", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                modifier = Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(AccentCyan.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("${relativePaths.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // FIX: dung key = relativePath de tranh recompose va reload anh khi list cap nhat
            items(count = relativePaths.size, key = { idx -> relativePaths[idx] }) { idx ->
                val fullUrl = buildFullUrl(relativePaths[idx], baseUrl)
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(fullUrl)
                        .addHeader("Authorization", okhttp3.Credentials.basic(user, pass))
                        .size(300, 300)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

/** Tạo full URL từ đường dẫn tương đối của AI tag + base URL WebDAV */
private fun buildFullUrl(relativePath: String, baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    val rel = relativePath.trimStart('/')
    return "$base/$rel"
}

// ════════════════════════════════════════════════════════════════════════════
// VideoPlayerScreen.kt
// ════════════════════════════════════════════════════════════════════════════

// Định nghĩa hằng số Action cho PiP Broadcast
private const val PIP_ACTION_REWIND = "com.nas.naswebdav.PIP_REWIND"
private const val PIP_ACTION_PLAY_PAUSE = "com.nas.naswebdav.PIP_PLAY_PAUSE"
private const val PIP_ACTION_FAST_FORWARD = "com.nas.naswebdav.PIP_FAST_FORWARD"

private fun formatPlayerTime(positionMs: Long): String {
    val safeMs = positionMs.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, user: String, pass: String, viewModel: WebDavViewModel? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
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

    // ═══ PHÁT HIỆN ĐỊNH DẠNG LEGACY NGAY LÚC MỞ PLAYER ═══
    // Formats ExoPlayer KHÔNG decode được (MPEG-2, WMV, v.v.)
    // → Chuyển thẳng sang URL transcode trên NAS (FFmpeg → MP4)
    val isLegacyFormat = remember(url) {
        val urlLower = url.lowercase()
        urlLower.endsWith(".mpg") || urlLower.endsWith(".mpeg") ||
                urlLower.endsWith(".avi") || urlLower.endsWith(".wmv") ||
                urlLower.endsWith(".flv") || urlLower.endsWith(".asf")
    }

    val effectiveUrl = remember(url, isLegacyFormat) {
        if (isLegacyFormat) {
            // Xây dựng URL transcode: http://host:5050/api/stream/transcode?path=/đường/dẫn/file
            // FIX: Dùng android.net.Uri thay vì java.net.URI để tránh crash URISyntaxException khi có khoảng trắng
            val uri = android.net.Uri.parse(url)
            val relativePath = uri.path?.substringAfter("/webdav") ?: ""
            val encodedPath = java.net.URLEncoder.encode(relativePath, "UTF-8")
            val transcodeUrl = "${url.toApiBaseUrl()}/api/stream/transcode?path=$encodedPath"
            android.util.Log.i("VideoPlayer", "Legacy format → transcode: $transcodeUrl")
            transcodeUrl
        } else {
            url.toFastMediaUrl() // MP4, MKV, MOV, TS, WEBM → endpoint Range/ETag tối ưu LAN
        }
    }

    // Chỉ coi là transcode khi thật sự đi vào endpoint transcode/HLS.
    // /api/media cho MP4 thường cũng đổi URL nhưng vẫn là progressive stream, không phải manifest.
    val isTranscoding = isLegacyFormat

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
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(app.videoStreamingClient)
            .setDefaultRequestProperties(mapOf("Authorization" to okhttp3.Credentials.basic(resolvedUser, resolvedPass)))

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
                            viewModel?.showCommonDialog = true
                            viewModel?.commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            viewModel?.commonDialogMessage = if (invalidResponseCode == 416) {
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
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {
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
                val pip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) act?.isInPictureInPictureMode ?: false else false
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                        .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = fileName,
                        color = Color.White,
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
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
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
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFFC7B2),
                        inactiveTrackColor = Color.White.copy(alpha = 0.42f)
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
                        IconButton(onClick = { exoPlayer.seekTo(0L) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Về đầu", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { exoPlayer.seekBack() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Replay30, "Tua lùi", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                if (playerIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Phát / tạm dừng",
                                tint = Color.White,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                        IconButton(onClick = { exoPlayer.seekForward() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Forward30, "Tua tới", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                val duration = exoPlayer.duration
                                if (duration > 0L && duration != androidx.media3.common.C.TIME_UNSET) {
                                    exoPlayer.seekTo(duration)
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, "Tới cuối", tint = Color.White.copy(alpha = 0.65f), modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = "${formatPlayerTime(playbackPositionMs)} / ${formatPlayerTime(playbackDurationMs)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isMuted = !isMuted }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                "Âm lượng",
                                tint = if (isMuted) Color(0xFFFF8A80) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { isRepeat = !isRepeat }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Repeat,
                                "Lặp lại",
                                tint = if (isRepeat) Color(0xFF00E676) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { activity?.let { act -> enterPipMode(act, exoPlayer) } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.PictureInPictureAlt, "Popup", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                exoPlayer.pause()
                                openExternalVideoPlayer(
                                    context = context,
                                    url = effectiveUrl,
                                    user = resolvedUser,
                                    pass = resolvedPass,
                                    onError = { android.util.Log.e("VideoPlayer", "Không mở được trình phát ngoài") }
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, "Mở bằng ứng dụng ngoài", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        if (viewModel != null) {
                            IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "Xóa video", tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // Dialog xác nhận xóa video
            if (showDeleteDialog && viewModel != null) {
                val fileName = url.substringAfterLast("/").let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                }
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    icon = { Icon(Icons.Default.DeleteForever, null, tint = Color(0xFFEF5350)) },
                    title = { Text("Xóa video?", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                    text = { Text("Bạn có chắc muốn xóa\n\"$fileName\"?\n\nVideo sẽ được chuyển vào Thùng rác.") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteDialog = false
                                exoPlayer.pause()
                                val fileToDelete = NasFile(fileName, url, false, "video/*", 0, 0)
                                viewModel.deleteFile(context, fileToDelete)
                                onBack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                        ) {
                            Text("Xóa")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text("Hủy")
                        }
                    }
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
    } catch (e: Exception) {
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
    } catch (e: Exception) {
        // Ignored: PiP unsupported or disabled globally
    }
}

// ════════════════════════════════════════════════════════════════════════════
// MainMenuSmartOrganizerScreen.kt
// ════════════════════════════════════════════════════════════════════════════


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuSmartOrganizerScreen(
    viewModel: WebDavViewModel,
    onBack: () -> Unit
) {
    // ── STATE ──
    val sourceUrl = viewModel.webDavManager.currentBaseUrl
    var selectedFilter by remember { mutableStateOf(OrganizerFilter.ALL) }
    val isScanning = viewModel.organizerScanning
    val isOrganizing = viewModel.organizerExecuting
    val scanResult = viewModel.organizerScanResult
    val totalFiles = viewModel.organizerTotalFiles
    val organizeResult = viewModel.organizerResult
    val errorMessage = viewModel.organizerError

    // ── Hàm quét ──
    val scanAndPreview: () -> Unit = {
        viewModel.smartOrganizeScan(selectedFilter)
    }

    // ── Hàm thực thi sắp xếp ──
    val startOrganize: () -> Unit = {
        viewModel.smartOrganizeExecute(selectedFilter)
    }

    // ── GIAO DIỆN ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Smart Organizer", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                        Text(
                            "Tự động phân loại tệp theo năm / tháng",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Quay lại", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkSurface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ THẺ THƯ MỤC NGUỒN ═══
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Thư mục nguồn", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))

                    val context = androidx.compose.ui.platform.LocalContext.current
                    // URL WebDAV
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0D1B2A))
                            .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clickable {
                                android.widget.Toast.makeText(context, "Sẽ sớm hỗ trợ chọn thư mục con!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFFFFCA28), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            sourceUrl.ifEmpty { "Chưa kết nối" },
                            fontSize = 14.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ═══ BỘ LỌC ═══
                    Text("Loại file", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data class FilterOpt(val filter: OrganizerFilter, val label: String)
                        val filters = listOf(
                            FilterOpt(OrganizerFilter.ALL, "Tất cả"),
                            FilterOpt(OrganizerFilter.IMAGE, "Chỉ ảnh"),
                            FilterOpt(OrganizerFilter.VIDEO, "Chỉ video")
                        )
                        filters.forEach { opt ->
                            FilterChip(
                                selected = selectedFilter == opt.filter,
                                onClick = { selectedFilter = opt.filter },
                                label = { Text(opt.label, fontSize = 13.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = AccentCyan
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selectedFilter == opt.filter,
                                    borderColor = TextSecondary.copy(alpha = 0.3f),
                                    selectedBorderColor = AccentCyan.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ═══ NÚT QUÉT ═══
                    Button(
                        onClick = { scanAndPreview() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !isScanning && !isOrganizing && sourceUrl.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCyan,
                            disabledContainerColor = AccentCyan.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = DarkSurface.copy(alpha = 0.8f),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Đang quét...", color = DarkSurface.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Search, null, tint = DarkSurface, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Quét & Xem trước", color = DarkSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ═══ PROGRESS BAR QUÉT ═══
            if (isScanning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Phân tích cấu trúc thư mục NAS...", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentCyan,
                            trackColor = DarkSurface
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ═══ HIỂN THỊ LỖI ═══
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = AccentRed, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(errorMessage!!, color = AccentRed, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ═══ HOÀN TẤT ═══
            if (organizeResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(organizeResult!!, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.organizerResult = null
                                viewModel.organizerScanResult = null
                                viewModel.organizerError = null
                            },
                            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sắp xếp thư mục khác", color = AccentCyan)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ═══ ĐANG SẮP XẾP ═══
            if (isOrganizing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Đang di chuyển tệp vào đúng thư mục...", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentCyan,
                            trackColor = DarkSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Quá trình này tùy thuộc vào số lượng và dung lượng tệp",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ═══ KẾT QUẢ QUÉT (Preview nhóm tháng) ═══
            if (scanResult != null && !isOrganizing && organizeResult == null) {
                val groups = scanResult!!

                // Header
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Bản xem trước — $totalFiles tệp",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    "${groups.size} nhóm",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            if (totalFiles > 0) {
                                Icon(Icons.Default.FolderSpecial, null, tint = AccentOrange, modifier = Modifier.size(28.dp))
                            }
                        }

                        if (totalFiles == 0) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "✅ Tất cả tệp đã được sắp xếp đúng thư mục!",
                                fontSize = 14.sp,
                                color = AccentGreen,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Danh sách nhóm
                groups.forEach { group ->
                    OrganizerGroupCard(group)
                    Spacer(Modifier.height(8.dp))
                }

                // Nút "Bắt đầu sắp xếp"
                if (totalFiles > 0) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { startOrganize() },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        enabled = !isScanning && !isOrganizing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            disabledContainerColor = AccentGreen.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.DriveFileMove, null, tint = DarkSurface, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Bắt đầu sắp xếp", color = DarkSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))

                    // Nút hủy
                    TextButton(
                        onClick = { viewModel.organizerScanResult = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Hủy tác vụ", color = TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Card hiển thị nhóm Năm/Tháng ──
@Composable
private fun OrganizerGroupCard(group: OrganizerGroup) {
    val monthNames = mapOf(
        "01" to "Tháng 1", "02" to "Tháng 2", "03" to "Tháng 3", "04" to "Tháng 4",
        "05" to "Tháng 5", "06" to "Tháng 6", "07" to "Tháng 7", "08" to "Tháng 8",
        "09" to "Tháng 9", "10" to "Tháng 10", "11" to "Tháng 11", "12" to "Tháng 12"
    )

    val parts = group.label.split("/")
    val year = parts.getOrElse(0) { "?" }
    val monthNum = parts.getOrElse(1) { "?" }
    val monthLabel = monthNames[monthNum] ?: "Tháng $monthNum"

    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon ngày tháng
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF667EEA), Color(0xFF764BA2)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(monthNum, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(year, fontSize = 8.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "$monthLabel $year",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${group.count} tệp · ${formatSize(group.size)}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    // Sample file names
                    if (!expanded && group.sampleFiles.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            group.sampleFiles.take(3).joinToString(", "),
                            fontSize = 10.sp,
                            color = TextSecondary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Badge count
                Surface(
                    color = AccentCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "${group.count}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 74.dp, end = 14.dp, bottom = 14.dp)) {
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), modifier = Modifier.padding(bottom = 8.dp))
                    group.sampleFiles.forEach { fileName ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.InsertDriveFile, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                fileName,
                                fontSize = 11.sp,
                                color = TextSecondary.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (group.count > group.sampleFiles.size) {
                        Text(
                            "... và ${group.count - group.sampleFiles.size} tệp khác",
                            fontSize = 11.sp,
                            color = AccentCyan,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = com.nas.naswebdav.utils.FormatUtils.formatBytes(bytes)

// ════════════════════════════════════════════════════════════════════════════
// SocialExtractorScreen.kt
// ════════════════════════════════════════════════════════════════════════════

// ── Bảng màu ─────────────────────────────────────────────────────────────────
private val SeDarkBg        = Color.Black
private val SeDarkCard      = Color(0xFF0A0A0A)
private val SeDarkCardAlt   = Color(0xFF0A0A0A)
private val SeAccentGreen   = Color(0xFF00E676)
private val SeAccentOrange  = Color(0xFFFF9100)
private val SeAccentRed     = Color(0xFFFF1744)
private val SeAccentCyan    = Color(0xFF00D2FF)
private val SeAccentPink    = Color(0xFFFF6EC7)
private val SeAccentPurple  = Color(0xFFE040FB)
private val SeTextPrimary   = Color(0xFFE8E8E8)
private val SeTextSecondary = Color(0xFF8892B0)

/**
 * SocialExtractorScreen – Màn hình Stream Piping thực sự.
 *
 * ── Luồng hoạt động:
 *  1. User dán link TikTok/Facebook/YouTube
 *  2. WebView ẩn load trang → JS Injection bóc link MP4 CDN
 *  3. OkHttp mở stream từ link CDN → bơm thẳng qua WebDAV PUT lên NAS
 *  4. Điện thoại chỉ làm "ống nước" (pipe) – KHÔNG lưu 1 byte nào xuống bộ nhớ
 *  5. Progress realtime: bytes đã truyền, tốc độ MB/s, ETA
 *
 * ── Fallback (nếu WebView không bóc được link):
 *  Gửi URL về NAS → NAS tự dùng yt-dlp xử lý
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuSocialExtractorScreen(
    viewModel: WebDavViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // ── State cục bộ ─────────────────────────────────────────────────────────
    var linkInput by remember {
        val clip = clipboardManager.getText()?.text ?: ""
        mutableStateOf(if (MainMenuIsSocialUrl(clip)) clip else "")
    }
    // Chế độ: true = Stream Pipe (điện thoại bơm), false = yt-dlp (NAS tự tải)
    var usePipeMode by remember { mutableStateOf(true) }
    // Kết quả URL video đã bóc được từ WebView
    var extractedVideoUrl by remember { mutableStateOf<String?>(null) }
    // Trạng thái WebView extraction
    var webViewStatus by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    // WebView instance để có thể inject JS sau khi load xong
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Trigger để WebView bắt đầu extract (thay đổi URL này = load trang mới)
    var extractTriggerUrl by remember { mutableStateOf("") }

    // ── Tự động Pipe khi đã bóc được link MP4 ───────────────────────────────
    LaunchedEffect(extractedVideoUrl) {
        val mp4Url = extractedVideoUrl ?: return@LaunchedEffect
        if (mp4Url.isNotEmpty() && usePipeMode) {
            val platform = detectPlatform(linkInput)
            val fileName = "social_${platform}_${System.currentTimeMillis()}.mp4"
            viewModel.startStreamPipe(mp4Url, fileName)
            extractedVideoUrl = null
            webViewStatus = ""
            isExtracting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Social Extractor", fontWeight = FontWeight.Bold, color = SeTextPrimary)
                        Text("Stream Piping – 0MB điện thoại", fontSize = 11.sp, color = SeTextSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancelStreamPipe()
                        onBack()
                    }) { Icon(Icons.Default.ArrowBack, null, tint = SeTextPrimary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SeDarkBg)
            )
        },
        containerColor = SeDarkBg
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Giải thích 2 chế độ ──────────────────────────────────────────
            ModeExplainCard(usePipeMode)
            Spacer(Modifier.height(12.dp))

            // ── Chuyển chế độ ────────────────────────────────────────────────
            ModeSwitchRow(
                isPipeMode = usePipeMode,
                onToggle = {
                    usePipeMode = !usePipeMode
                    extractedVideoUrl = null
                    webViewStatus = ""
                }
            )
            Spacer(Modifier.height(12.dp))

            // ── Input link ───────────────────────────────────────────────────
            LinkInputCard(
                link = linkInput,
                onLinkChange = {
                    linkInput = it
                    extractedVideoUrl = null
                    webViewStatus = ""
                },
                onPasteClipboard = {
                    val clip = clipboardManager.getText()?.text ?: ""
                    if (clip.isNotBlank()) linkInput = clip
                }
            )
            Spacer(Modifier.height(12.dp))

            // ── Thư mục đích ─────────────────────────────────────────────────
            DestFolderRow(viewModel)
            Spacer(Modifier.height(12.dp))

            // ── Trạng thái WebView extraction ────────────────────────────────
            AnimatedVisibility(visible = webViewStatus.isNotEmpty() || isExtracting) {
                ExtractionStatusCard(webViewStatus, isExtracting)
                Spacer(Modifier.height(10.dp))
            }

            // ── Stream Pipe Progress ──────────────────────────────────────────
            AnimatedVisibility(visible = viewModel.streamPipeStatus.isNotEmpty()) {
                StreamPipeProgressCard(viewModel)
                Spacer(Modifier.height(10.dp))
            }

            // ── Trạng thái yt-dlp (fallback mode) ────────────────────────────
            AnimatedVisibility(visible = !usePipeMode && viewModel.socialExtractStatus.isNotEmpty()) {
                SocialStatusCard(viewModel.socialExtractStatus, viewModel.isSocialExtracting)
                Spacer(Modifier.height(10.dp))
            }

            // ── Lịch sử ──────────────────────────────────────────────────────
            if (viewModel.socialDownloadHistory.isNotEmpty()) {
                HistoryCard(viewModel.socialDownloadHistory)
                Spacer(Modifier.height(12.dp))
            }

            // ── Nút hành động chính ───────────────────────────────────────────
            val isWorking = viewModel.isSocialExtracting || viewModel.isStreamPiping || isExtracting
            MainActionButton(
                isPipeMode = usePipeMode,
                isWorking = isWorking,
                isEnabled = linkInput.isNotBlank(),
                onStart = {
                    if (usePipeMode) {
                        // Chế độ truyền trực tiếp: dùng WebView lấy liên kết trước
                        extractedVideoUrl = null
                        webViewStatus = "🔍 Đang tải trang để bóc link video..."
                        isExtracting = true
                        extractTriggerUrl = linkInput.trim()
                    } else {
                        // Chế độ NAS tự tải: gửi URL thẳng về NAS qua yt-dlp
                        viewModel.requestSocialDownload(linkInput.trim())
                        linkInput = ""
                    }
                },
                onCancel = {
                    viewModel.cancelStreamPipe()
                    isExtracting = false
                    webViewStatus = ""
                    extractTriggerUrl = ""
                }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                if (usePipeMode)
                    "Truyền trực tiếp: điện thoại chuyển dữ liệu từ CDN về NAS."
                else
                    "NAS tự tải: NAS dùng yt-dlp và lưu trực tiếp vào ổ cứng.",
                fontSize = 11.sp, color = SeTextSecondary, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // ── WebView ẨN – Chỉ hoạt động khi có trigger URL ────────────────────────
    // View.GONE → không chiếm không gian màn hình nhưng JS vẫn chạy bình thường
    if (extractTriggerUrl.isNotEmpty()) {
        HiddenExtractorWebView(
            targetUrl = extractTriggerUrl,
            onStatusUpdate = { msg -> webViewStatus = msg },
            onVideoUrlFound = { mp4Url ->
                extractedVideoUrl = mp4Url
                extractTriggerUrl = ""  // Dừng WebView sau khi bóc xong
                webViewStatus = "✅ Đã lấy được liên kết video HD. Đang truyền về NAS..."
            },
            onLivestreamFound = { liveUrl, referer, userAgent ->
                viewModel.startLivestreamRecord(context, liveUrl, "best", referer, userAgent)
                extractTriggerUrl = ""
                webViewStatus = "✅ Đã bắt được luồng Livestream (M3U8)! Đang ra lệnh NAS ghi hình..."
                isExtracting = false
            },
            onFailure = { reason ->
                isExtracting = false
                extractTriggerUrl = ""
                webViewStatus = "⚠️ Không lấy được liên kết tự động ($reason). Đang chuyển sang chế độ NAS tự tải..."
                // Tự động fallback sang yt-dlp
                viewModel.requestSocialDownload(linkInput.trim())
            },
            onWebViewReady = { wv -> webViewRef = wv }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// WebView ẨN – Bóc link MP4 bằng JS Injection
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * HiddenExtractorWebView – WebView kích thước 0x0, không hiển thị trên màn hình.
 *
 * Cơ chế:
 *  1. Load trang HTML gốc của TikTok/Facebook (có User-Agent giả Mobile)
 *  2. Inject JS: Quét toàn bộ DOM, video tags, JSON-LD, OG tags → tìm link .mp4
 *  3. Giao tiếp qua JavascriptInterface: onVideoFound(url) / onFailed(reason)
 *  4. Retry tự động: Re-inject JS sau 2s nếu lần đầu chưa thấy (trang chưa load xong)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HiddenExtractorWebView(
    targetUrl: String,
    onStatusUpdate: (String) -> Unit,
    onVideoUrlFound: (String) -> Unit,
    onLivestreamFound: (url: String, referer: String, userAgent: String) -> Unit,
    onFailure: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit
) {
    // Hiển thị dưới dạng 0x0 invisible view
    AndroidView(
        modifier = Modifier.size(0.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // User-Agent thật của Chrome Mobile để vượt bot-detection
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = false  // Tiết kiệm RAM
                    blockNetworkImage = true           // KHÔNG tải ảnh
                }

                // Cho phép cookie (cần cho TikTok/Facebook session)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // JavascriptInterface: Cổng giao tiếp JS → Kotlin
                addJavascriptInterface(
                    object : Any() {
                        @JavascriptInterface
                        fun onVideoFound(url: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (url.isNotBlank() && url.startsWith("http")) {
                                    onVideoUrlFound(url)
                                }
                            }
                        }
                        @JavascriptInterface
                        fun onStatusUpdate(msg: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onStatusUpdate("🔍 $msg")
                            }
                        }
                        @JavascriptInterface
                        fun onFailed(reason: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onFailure(reason)
                            }
                        }
                    },
                    "NasExtractor"
                )

                // ── MỘT webViewClient duy nhất: vừa chặn tài nguyên, vừa inject JS ──
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var timeoutRunnable: Runnable? = null

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val isMain = request?.isForMainFrame == true
                        
                        // --- Bắt luồng livestream (M3U8 / FLV / MPD / TS) ---
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".mpd") || reqUrl.contains(".flv")) {
                            // Bỏ qua các file phân mảnh con, chỉ bắt file master manifest
                            if (!reqUrl.contains("chunk") && !reqUrl.contains("segment")) {
                                val referer = request?.requestHeaders?.get("Referer") ?: targetUrl
                                val ua = request?.requestHeaders?.get("User-Agent") ?: view?.settings?.userAgentString ?: ""
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onLivestreamFound(reqUrl, referer, ua)
                                }
                                // Trả về block để dừng load stream tren dien thoai
                                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                        }

                        if (!isMain && (
                            reqUrl.endsWith(".png") || reqUrl.endsWith(".jpg") ||
                            reqUrl.endsWith(".gif") || reqUrl.endsWith(".webp") ||
                            reqUrl.endsWith(".woff") || reqUrl.endsWith(".woff2") ||
                            reqUrl.contains("analytics") || reqUrl.contains("/ads/") ||
                            reqUrl.contains("tracking") || reqUrl.contains("pixel")
                        )) {
                            return WebResourceResponse("text/plain", "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onStatusUpdate("📄 Trang load xong. Đang tiêm JS bóc link...")

                        // Xoá timeout cũ, đặt lại 10 giây
                        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                        timeoutRunnable = Runnable {
                            onFailure("JS không bóc được link trong 10 giây")
                        }.also { timeoutHandler.postDelayed(it, 10_000) }

                        // Inject lần 1
                        view?.evaluateJavascript(buildExtractorJs(), null)
                        // Retry lần 2 sau 2.5s (trang lazy-load JS)
                        timeoutHandler.postDelayed({
                            view?.evaluateJavascript(buildExtractorJs(), null)
                        }, 2_500)
                    }

                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                            onFailure("Lỗi tải trang: ${error?.description}")
                        }
                    }
                }

                onWebViewReady(this)
                loadUrl(targetUrl)
            }
        },
        update = { wv ->
            // Nếu URL thay đổi thì load lại
            if (wv.url != targetUrl && targetUrl.isNotEmpty()) {
                wv.loadUrl(targetUrl)
            }
        }
    )
}

/**
 * buildExtractorJs() – Script đa chiến lược tìm link video MP4/CDN.
 *
 * Chiến lược (theo thứ tự ưu tiên):
 *  1. <video src="...">, <source src="...">     ← Trực tiếp nhất
 *  2. JSON-LD (script[type=application/ld+json]) ← TikTok dùng cách này
 *  3. OG Tags (meta[property=og:video])          ← Facebook, Instagram
 *  4. window.__INITIAL_STATE__ / window.SIGI_STATE (TikTok SPA)
 *  5. Script JSON rò rỉ (tìm regex playAddr/download_addr trong mọi script)
 */
private fun buildExtractorJs(): String = """
(function() {
    var found = null;
    var host = window.location.host || '';
    
    // --- Chiến lược 1: Video/Source tags trực tiếp (chắc nhất) ---
    var videos = document.querySelectorAll('video[src], source[src]');
    for (var i = 0; i < videos.length; i++) {
        var s = videos[i].src || videos[i].getAttribute('src') || '';
        if (s && (s.includes('.mp4') || s.includes('video') || s.includes('media'))) {
            if (!s.startsWith('blob:')) { found = s; break; }
        }
    }
    
    // --- Chiến lược 2: JSON-LD (TikTok, YouTube) ---
    if (!found) {
        var jsonLds = document.querySelectorAll('script[type="application/ld+json"]');
        for (var i = 0; i < jsonLds.length; i++) {
            try {
                var d = JSON.parse(jsonLds[i].textContent || '{}');
                var contentUrl = d.contentUrl || (d.video && d.video.contentUrl) || '';
                if (contentUrl && contentUrl.includes('http')) { found = contentUrl; break; }
            } catch(e) {}
        }
    }
    
    // --- Chiến lược 3: OG:Video Meta Tag (Facebook, Instagram) ---
    if (!found) {
        var ogVideo = document.querySelector('meta[property="og:video:secure_url"], meta[property="og:video"]');
        if (ogVideo) { found = ogVideo.getAttribute('content') || null; }
    }
    
    // --- Chiến lược 4: TikTok SPA state (SIGI_STATE / __INITIAL_STATE__) ---
    if (!found && (host.includes('tiktok') || host.includes('douyin'))) {
        var keys = ['__INITIAL_STATE__', 'SIGI_STATE', '__UNIVERSAL_DATA__'];
        for (var k = 0; k < keys.length; k++) {
            try {
                var st = window[keys[k]];
                if (!st) continue;
                var str = JSON.stringify(st);
                // Tìm playAddr (link CDN nhanh) hoặc download_addr
                var re = /"playAddr"\s*:\s*"([^"]+)"/;
                var m = re.exec(str);
                if (m) { found = m[1].replace(/\\\\u002F/g, '/'); break; }
                re = /"downloadAddr"\s*:\s*"([^"]+)"/;
                m = re.exec(str);
                if (m) { found = m[1].replace(/\\\\u002F/g, '/'); break; }
            } catch(e) {}
        }
    }
    
    // --- Chiến lược 5: Quet toan bo script tags tim link MP4 ---
    if (!found) {
        var scripts = document.querySelectorAll('script:not([src])');
        for (var i = 0; i < scripts.length; i++) {
            var txt = scripts[i].textContent || '';
            var regs = [
                /"(https?:[^"]*\.mp4[^"]*?)"/,
                /"playUrl"\s*:\s*"([^"]+)"/,
                /"videoUrl"\s*:\s*"([^"]+)"/,
                /playAddr['"]\s*:\s*['"]([^'"]+)['"]/
            ];
            for (var r = 0; r < regs.length; r++) {
                var match = regs[r].exec(txt);
                if (match && match[1].includes('http')) {
                    found = match[1].replace(/\\\//g, '/');
                    break;
                }
            }
            if (found) break;
        }
    }
    
    // --- Gửi kết quả về Kotlin ---
    if (found) {
        NasExtractor.onVideoFound(found);
    } else {
        var counts = 'video=' + document.querySelectorAll('video').length;
        NasExtractor.onStatusUpdate('Chưa tìm thấy (' + counts + ')');
    }
})();
""".trimIndent()

// ═══════════════════════════════════════════════════════════════════════════════
// Sub-Components UI
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ModeExplainCard(isPipeMode: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPipeMode) SeAccentPink.copy(alpha = 0.07f)
                             else SeAccentCyan.copy(alpha = 0.07f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPipeMode) Icons.Default.Bolt else Icons.Default.Cloud,
                    null,
                    tint = if (isPipeMode) SeAccentPink else SeAccentCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isPipeMode) "Chế độ truyền trực tiếp" else "Chế độ NAS tự tải",
                    fontWeight = FontWeight.Bold,
                    color = if (isPipeMode) SeAccentPink else SeAccentCyan,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (isPipeMode)
                    "WebView ẩn lấy liên kết → điện thoại truyền dữ liệu CDN về WebDAV NAS.\nĐiện thoại không lưu tệp cục bộ. Tốc độ phụ thuộc mạng LAN/Tailscale."
                else
                    "Gửi liên kết về NAS → NAS dùng yt-dlp tải nền → lưu vào ổ cứng 4TB.\nĐiện thoại không cần tiếp tục chạy sau khi NAS nhận lệnh.",
                fontSize = 12.sp,
                color = SeTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ModeSwitchRow(isPipeMode: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Truyền trực tiếp
        ModeTab(
            label = "⚡ Truyền trực tiếp",
            desc = "Điện thoại truyền",
            selected = isPipeMode,
            gradientColors = listOf(SeAccentPink, SeAccentPurple),
            modifier = Modifier.weight(1f),
            onClick = { if (!isPipeMode) onToggle() }
        )
        // NAS tự tải
        ModeTab(
            label = "☁️ yt-dlp",
            desc = "NAS tự tải",
            selected = !isPipeMode,
            gradientColors = listOf(SeAccentCyan, Color(0xFF0097A7)),
            modifier = Modifier.weight(1f),
            onClick = { if (isPipeMode) onToggle() }
        )
    }
}

@Composable
private fun ModeTab(
    label: String, desc: String, selected: Boolean,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) Brush.horizontalGradient(gradientColors)
                else Brush.horizontalGradient(listOf(SeDarkCard, SeDarkCard))
            )
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else SeTextSecondary.copy(0.2f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else SeTextSecondary)
            Text(desc, fontSize = 10.sp,
                color = if (selected) Color.White.copy(0.75f) else SeTextSecondary.copy(0.6f))
        }
    }
}

@Composable
private fun LinkInputCard(
    link: String,
    onLinkChange: (String) -> Unit,
    onPasteClipboard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SeDarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Link video", fontSize = 12.sp, color = SeTextSecondary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = link,
                onValueChange = onLinkChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Dán link TikTok / Facebook / YouTube...", color = SeTextSecondary, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Link, null, tint = SeTextSecondary, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (link.isNotEmpty()) {
                        IconButton(onClick = { onLinkChange("") }) {
                            Icon(Icons.Default.Clear, null, tint = SeTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        IconButton(onClick = onPasteClipboard) {
                            Icon(Icons.Default.ContentPaste, null, tint = SeAccentCyan, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                minLines = 2, maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SeAccentCyan,
                    unfocusedBorderColor = SeTextSecondary.copy(alpha = 0.3f),
                    cursorColor = SeAccentCyan,
                    focusedTextColor = SeTextPrimary,
                    unfocusedTextColor = SeTextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )
            if (link.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                val platform = detectPlatform(link)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(platformColor(platform).copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (platform != "Không rõ") "▶ $platform" else "⚠️ URL không nhận dạng được",
                        fontSize = 10.sp,
                        color = if (platform != "Không rõ") SeAccentCyan else SeAccentOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DestFolderRow(viewModel: WebDavViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SeDarkCardAlt.copy(0.5f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = Color(0xFFFFCA28), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Lưu vào NAS:", fontSize = 10.sp, color = SeTextSecondary)
            Text(
                (viewModel.webDavManager.currentBaseUrl) + AppConfig.SOCIAL_DOWNLOAD_FOLDER,
                fontSize = 11.sp, color = SeAccentCyan,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExtractionStatusCard(status: String, isLoading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E3F)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = SeAccentPink, modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.FindInPage, null, tint = SeAccentPink, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(status, color = SeTextPrimary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun StreamPipeProgressCard(viewModel: WebDavViewModel) {
    val status = viewModel.streamPipeStatus
    val isError = status.contains("Lỗi", ignoreCase = true)
    val isDone  = status.contains("Hoàn tất", ignoreCase = true)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> SeAccentRed.copy(0.1f)
                isDone  -> SeAccentGreen.copy(0.1f)
                else    -> SeAccentOrange.copy(0.08f)
            }
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when { isError -> SeAccentRed.copy(0.3f); isDone -> SeAccentGreen.copy(0.3f); else -> SeAccentOrange.copy(0.2f) }
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (viewModel.isStreamPiping) {
                    CircularProgressIndicator(
                        color = SeAccentOrange, modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                        null,
                        tint = if (isError) SeAccentRed else SeAccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(status, color = SeTextPrimary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            }

            // Progress bar
            if (viewModel.isStreamPiping && viewModel.streamPipeProgress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { viewModel.streamPipeProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = SeAccentOrange,
                    trackColor = SeTextSecondary.copy(0.2f)
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${(viewModel.streamPipeProgress * 100).toInt()}% — ${viewModel.streamPipeSpeedStr}",
                        fontSize = 11.sp, color = SeTextSecondary
                    )
                    Text(
                        "ETA: ${viewModel.streamPipeEtaStr}",
                        fontSize = 11.sp, color = SeTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialStatusCard(status: String, isLoading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.contains("Lỗi", ignoreCase = true))
                SeAccentRed.copy(0.1f) else SeAccentCyan.copy(0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) CircularProgressIndicator(color = SeAccentCyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Cloud, null, tint = SeAccentCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(status, color = SeTextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun HistoryCard(history: List<SocialDownloadItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SeDarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Lịch sử gần đây", fontSize = 12.sp, color = SeTextSecondary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            history.takeLast(5).reversed().forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (item.isSuccess) SeAccentGreen else SeAccentRed)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.url.take(48) + if (item.url.length > 48) "…" else "",
                            fontSize = 11.sp, color = SeTextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            Text(item.platform, fontSize = 10.sp, color = platformColor(item.platform))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (item.isSuccess) "✓ OK" else "✗ Thất bại",
                                fontSize = 10.sp,
                                color = if (item.isSuccess) SeAccentGreen else SeAccentRed
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActionButton(
    isPipeMode: Boolean,
    isWorking: Boolean,
    isEnabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val gradient = if (isPipeMode)
        Brush.horizontalGradient(listOf(SeAccentPink, SeAccentPurple))
    else
        Brush.horizontalGradient(listOf(SeAccentCyan, Color(0xFF0097A7)))

    val disabledGradient = Brush.horizontalGradient(
        listOf(SeTextSecondary.copy(0.2f), SeTextSecondary.copy(0.2f))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isEnabled || isWorking) gradient else disabledGradient)
            .clickable(
                enabled = isEnabled || isWorking,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isWorking) onCancel() else onStart()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isWorking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Đang xử lý... (Nhấn để Hủy)", color = Color.White, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPipeMode) Icons.Default.SwapHoriz else Icons.Default.CloudUpload,
                    null, tint = Color.White, modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isPipeMode) "⚡ Bắt đầu truyền" else "☁️ Gửi về NAS",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────
fun MainMenuIsSocialUrl(url: String): Boolean {
    val l = url.lowercase()
    return l.contains("tiktok.com") || l.contains("vm.tiktok") ||
           l.contains("facebook.com/") || l.contains("fb.watch") ||
           l.contains("youtube.com/shorts") || l.contains("youtu.be") ||
           l.contains("instagram.com/reel") || l.contains("instagram.com/p/")
}

private fun detectPlatform(url: String): String {
    val l = url.lowercase()
    return when {
        l.contains("tiktok") || l.contains("vm.tiktok") -> "TikTok"
        l.contains("facebook") || l.contains("fb.watch") -> "Facebook"
        l.contains("youtube") || l.contains("youtu.be") -> "YouTube"
        l.contains("instagram") -> "Instagram"
        l.startsWith("http") -> "Khác"
        else -> "Không rõ"
    }
}

private fun platformColor(platform: String): Color = when (platform) {
    "TikTok"   -> Color(0xFF69C9D0)
    "Facebook" -> Color(0xFF1877F2)
    "YouTube"  -> Color(0xFFFF0000)
    "Instagram"-> Color(0xFFE1306C)
    else       -> Color(0xFF8892B0)
}
