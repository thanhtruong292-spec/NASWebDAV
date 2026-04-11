package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nas.naswebdav.WebDavViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(initialUrl: String, viewModel: WebDavViewModel, user: String, pass: String, onBack: () -> Unit) {
    val imageFiles = remember(viewModel.fileList) {
        viewModel.fileList.filter { it.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") } }
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
    // Giải phóng toàn bộ bộ nhớ RAM nặng nề của ảnh gốc ngay khi bạn thoát màn hình xem ảnh
    DisposableEffect(Unit) {
        onDispose {
            coil.Coil.imageLoader(context).memoryCache?.clear()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondBoundsPageCount = 1,
            key = { imageFiles[it].path }
        ) { page ->
            val file = imageFiles[page]

            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            // Tự động Reset Zoom khi vuốt sang ảnh mới
            LaunchedEffect(pagerState.currentPage) {
                scale = 1f
                offset = Offset.Zero
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val targetOffset = (center - tapOffset) * (scale - 1f)

                                    val extraW = (scale - 1) * 1000f
                                    val extraH = (scale - 1) * 1000f

                                    offset = Offset(
                                        x = targetOffset.x.coerceIn(-extraW, extraW),
                                        y = targetOffset.y.coerceIn(-extraH, extraH)
                                    )
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            do {
                                val event = awaitPointerEvent()
                                val pointers = event.changes.size

                                if (pointers >= 2 || scale > 1f) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        val extraW = (scale - 1) * 1000f
                                        val extraH = (scale - 1) * 1000f
                                        offset = Offset(
                                            x = (offset.x + panChange.x).coerceIn(-extraW, extraW),
                                            y = (offset.y + panChange.y).coerceIn(-extraH, extraH)
                                        )
                                    } else {
                                        offset = Offset.Zero
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(file.path)
                        .addHeader("Authorization", okhttp3.Credentials.basic(user, pass))
                        .diskCacheKey(file.path)
                        .memoryCacheKey(file.path + "_full")
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .size(coil.size.Size.ORIGINAL)
                        .crossfade(true)
                        .build(),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Header hiển thị Nút Back và Số thứ tự
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(top = 32.dp, bottom = 16.dp, start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (imageFiles.isNotEmpty()) "${pagerState.currentPage + 1} / ${imageFiles.size} - ${imageFiles[pagerState.currentPage].name}" else "",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
