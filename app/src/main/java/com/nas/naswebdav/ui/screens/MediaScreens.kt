@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import coil.compose.AsyncImage
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


// ════════════════════════════════════════════════════════════════════════════
// ImageViewerScreen.kt
// ════════════════════════════════════════════════════════════════════════════

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

private val MvDarkSurface = Color.Black
private val MvDarkCard    = Color.Black
private val MvAccentCyan  = Color(0xFF00D2FF)
private val MvTextPrimary = Color(0xFFE8E8E8)
private val MvTextSecondary = Color(0xFF8892B0)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun ImageViewerScreen(initialUrl: String, viewModel: WebDavViewModel, user: String, pass: String, onBack: () -> Unit) {
    val imageFiles = remember(viewModel.fileList) {
        viewModel.fileList.filter { com.nas.naswebdav.utils.MediaUtils.isImage(it.name) }
    }

    val initialPage = remember(imageFiles, initialUrl) {
        val index = imageFiles.indexOfFirst { it.path == initialUrl }
        if (index >= 0) index else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { imageFiles.size }
    )

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ============ TAB STATE ============
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Tất Cả", "Khám Phá")

    // ============ TRÌNH CHIẾU (SLIDESHOW) ============
    var isSlideshowActive by remember { mutableStateOf(false) }
    var slideshowIntervalMs by remember { mutableLongStateOf(3000L) }
    var showControls by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Timer tự động chuyển ảnh
    LaunchedEffect(isSlideshowActive, pagerState.currentPage) {
        if (isSlideshowActive && imageFiles.isNotEmpty()) {
            delay(slideshowIntervalMs)
            val nextPage = (pagerState.currentPage + 1) % imageFiles.size
            pagerState.scrollToPage(nextPage)
        }
    }
    LaunchedEffect(isSlideshowActive, showControls) {
        if (isSlideshowActive && showControls) { delay(3000); showControls = false }
    }

    // Fetch AI tags khi vào Tab Khám Phá lần đầu
    var aiTabVisited by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && !aiTabVisited) {
            aiTabVisited = true
            viewModel.fetchAiTags()
        }
    }

    // Giải phóng RAM khi thoát
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

    // ============ XÁC NHẬN XÓA ẢNH ============
    if (showDeleteDialog && imageFiles.isNotEmpty()) {
        val currentFile = imageFiles[pagerState.currentPage]
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, null, tint = Color(0xFFEF5350)) },
            title = { Text("Xóa ảnh?", fontWeight = FontWeight.Bold) },
            text = { Text("Bạn có chắc muốn xóa\n\"${currentFile.name}\"?\n\nẢnh sẽ được chuyển vào Thùng rác.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        val fileToDelete = NasFile(currentFile.name, currentFile.path, false, "", 0, 0)
                        viewModel.deleteFile(context, fileToDelete)
                        if (imageFiles.size <= 1) onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                ) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Hủy") } }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MvDarkSurface)
    ) {
        // ============ HEADER + TABS ============
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MvDarkCard)
        ) {
            // Top bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (isSlideshowActive) { isSlideshowActive = false; showControls = true }
                    else onBack()
                }) {
                    Icon(
                        if (isSlideshowActive) Icons.Default.Stop else Icons.Default.ArrowBack,
                        "Back", tint = Color.White
                    )
                }
                Text(
                    text = if (selectedTab == 0 && imageFiles.isNotEmpty())
                        "${pagerState.currentPage + 1}/${imageFiles.size} — ${imageFiles[pagerState.currentPage].name}"
                    else "AI Gallery",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selectedTab == 0) {
                    IconButton(onClick = { isSlideshowActive = !isSlideshowActive; showControls = true }) {
                        Icon(
                            if (isSlideshowActive) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            null, tint = if (isSlideshowActive) Color(0xFF00E676) else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350), modifier = Modifier.size(24.dp))
                    }
                } else {
                    // Tab Khám Phá: nút Refresh + Trigger AI
                    IconButton(onClick = { viewModel.fetchAiTags() }) {
                        Icon(Icons.Default.Refresh, null, tint = MvAccentCyan, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { viewModel.triggerAiScan(context) }) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFAB47BC), modifier = Modifier.size(22.dp))
                    }
                }
            }

            // Tab Row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MvDarkCard,
                contentColor = MvAccentCyan,
                indicator = { tabPositions ->
                    Box(
                        Modifier
                            .tabIndicatorOffset(tabPositions[selectedTab])
                            .height(2.dp)
                            .padding(horizontal = 16.dp)
                            .background(MvAccentCyan, RoundedCornerShape(1.dp))
                    )
                }
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (idx == 1) {
                                    Icon(Icons.Default.AutoAwesome, null,
                                        tint = if (selectedTab == 1) MvAccentCyan else MvTextSecondary,
                                        modifier = Modifier.size(14.dp))
                                }
                                Text(label, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == idx) MvAccentCyan else MvTextSecondary,
                                    fontSize = 13.sp)
                            }
                        }
                    )
                }
            }
        }

        // ============ NỘI DUNG THEO TAB ============
        when (selectedTab) {
            0 -> PhotoViewerContent(
                imageFiles = imageFiles,
                pagerState = pagerState,
                isSlideshowActive = isSlideshowActive,
                showControls = showControls,
                slideshowIntervalMs = slideshowIntervalMs,
                onSlideshowIntervalChange = { slideshowIntervalMs = it },
                user = user, pass = pass
            )
            1 -> AiGalleryContent(
                viewModel = viewModel,
                user = user, pass = pass,
                baseUrl = viewModel.webDavManager.currentBaseUrl
            )
        }
    }
}

// ============ TAB 0: XEM ẢNH GỐC (tách ra khỏi Composable chính) ============
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewerContent(
    imageFiles: List<NasFile>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    isSlideshowActive: Boolean,
    showControls: Boolean,
    slideshowIntervalMs: Long,
    onSlideshowIntervalChange: (Long) -> Unit,
    user: String, pass: String
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondBoundsPageCount = if (isSlideshowActive) 1 else 0,
            key = { imageFiles[it].path },
            userScrollEnabled = !isSlideshowActive,
            pageSpacing = 32.dp
        ) { page ->
            val file = imageFiles[page]
            var scale by remember { mutableFloatStateOf(1f) }
            var panOffset by remember { mutableStateOf(Offset.Zero) }

            LaunchedEffect(pagerState.currentPage) { scale = 1f; panOffset = Offset.Zero }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isSlideshowActive) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                if (!isSlideshowActive) {
                                    if (scale > 1f) { scale = 1f; panOffset = Offset.Zero }
                                    else {
                                        scale = 2.5f
                                        val center = Offset(size.width / 2f, size.height / 2f)
                                        val targetOffset = (center - tapOffset) * (scale - 1f)
                                        val extraW = (scale - 1) * 1000f; val extraH = (scale - 1) * 1000f
                                        panOffset = Offset(
                                            targetOffset.x.coerceIn(-extraW, extraW),
                                            targetOffset.y.coerceIn(-extraH, extraH)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(isSlideshowActive) {
                        if (!isSlideshowActive) {
                            awaitEachGesture {
                                awaitFirstDown()
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2 || scale > 1f) {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        scale = (scale * zoomChange).coerceIn(1f, 5f)
                                        if (scale > 1f) {
                                            val extraW = (scale - 1) * 1000f; val extraH = (scale - 1) * 1000f
                                            panOffset = Offset(
                                                (panOffset.x + panChange.x).coerceIn(-extraW, extraW),
                                                (panOffset.y + panChange.y).coerceIn(-extraH, extraH)
                                            )
                                        } else panOffset = Offset.Zero
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(file.path)
                        .addHeader("Authorization", okhttp3.Credentials.basic(user, pass))
                        .size(1920, 1080)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                        .crossfade(true)
                        .build(),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panOffset.x, translationY = panOffset.y),
                    contentScale = ContentScale.Fit
                )
            }
        }

        if (isSlideshowActive && imageFiles.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                LinearProgressIndicator(
                    progress = { (pagerState.currentPage + 1).toFloat() / imageFiles.size },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                    color = Color(0xFF00E676), trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
        AnimatedVisibility(
            visible = isSlideshowActive && showControls,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier.padding(bottom = 40.dp).clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(2000L to "2s", 3000L to "3s", 5000L to "5s", 10000L to "10s").forEach { (ms, label) ->
                    val isSelected = slideshowIntervalMs == ms
                    Surface(onClick = { onSlideshowIntervalChange(ms) },
                        color = if (isSelected) Color(0xFF00E676) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp)) {
                        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (isSelected) Color.Black else Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

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
        modifier = Modifier.fillMaxSize().background(MvDarkSurface),
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
                    Text("🤖 AI Gallery", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MvTextPrimary)
                    if (viewModel.aiLastScan.isNotEmpty()) {
                        Text("Lần quét cuối: ${viewModel.aiLastScan}", fontSize = 11.sp, color = MvTextSecondary)
                    }
                }
                if (viewModel.aiTotal > 0) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(MvAccentCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("${viewModel.aiTotal} ảnh", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MvAccentCyan)
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
                            CircularProgressIndicator(color = MvAccentCyan, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Đang tải dữ liệu AI...", color = MvTextSecondary, fontSize = 13.sp)
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
            val authSnapshot = viewModel.webDavManager.currentAuthState()
            AiCategoryCard(
                name = catName,
                count = urls.size,
                previewUrl = if (urls.isNotEmpty()) buildFullUrl(urls[0], viewModel.webDavManager.currentBaseUrl) else null,
                user = authSnapshot.user,
                pass = authSnapshot.pass,
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
        colors = CardDefaults.cardColors(containerColor = MvDarkCard),
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
                            Brush.horizontalGradient(listOf(Color.Transparent, MvDarkCard.copy(alpha = 0.3f))),
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
                    Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MvTextPrimary)
                }
                Spacer(Modifier.height(4.dp))
                Text("$count ảnh", fontSize = 12.sp, color = MvTextSecondary)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MvTextSecondary, modifier = Modifier.padding(end = 16.dp))
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
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MvTextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (aiRunning)
                "NAS đang quét và phân loại ảnh theo thư mục. Hãy quay lại sau vài phút."
            else
                "Chưa quét ảnh lần nào.\nNhấn nút ✨ để bắt đầu phân loại ảnh.",
            fontSize = 13.sp, color = MvTextSecondary, textAlign = TextAlign.Center
        )
        if (aiRunning) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(color = MvAccentCyan, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        }
        if (status.isNotEmpty() && status != "ok") {
            Spacer(Modifier.height(12.dp))
            Text(status, fontSize = 11.sp, color = MvTextSecondary.copy(alpha = 0.7f), textAlign = TextAlign.Center)
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
    Column(Modifier.fillMaxSize().background(MvDarkSurface)) {
        // Header
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .background(MvDarkCard)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
            val icon = categoryIcons[categoryName] ?: "🖼️"
            Text("$icon $categoryName", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MvTextPrimary,
                modifier = Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(MvAccentCyan.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("${relativePaths.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MvAccentCyan)
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


