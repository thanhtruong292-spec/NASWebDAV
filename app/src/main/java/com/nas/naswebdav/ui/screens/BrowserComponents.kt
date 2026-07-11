@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit


import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView

import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.heightIn
import org.json.JSONArray
import org.json.JSONObject

import coil.compose.AsyncImage
import coil.request.ImageRequest

import okhttp3.Credentials
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import android.app.PictureInPictureParams
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.items

// ============ Browser components: grid cell, cached thumbnail, external player (tách từ BrowserScreen.kt) ============

// --- FILE ITEM GRID CELL ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemGridCell(
    file: NasFile,
    viewModel: WebDavViewModel,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    viewedRefreshTick: Int = 0,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
    onVideo: (String) -> Unit
) {
    val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)
    // Gọi thẳng từ Utils để ăn trọn mọi định dạng ảnh (HEIC, PNG, GIF, BMP...)
    val isImage = com.nas.naswebdav.utils.MediaUtils.isImage(file.name)
    val isMedia = isVideo || isImage
    val authSnapshot = viewModel.webDavManager.currentAuthState()
    val auth = Credentials.basic(authSnapshot.user, authSnapshot.pass)

    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var showTransferPickerDialog by remember { mutableStateOf(false) }
    var pendingTransferOperation by remember { mutableStateOf("") }
    var newFileName by remember { mutableStateOf(file.name) }

    // Tracking file "moi/chua xem" — luu set duong dan da xem vao SharedPreferences.
    // Khi user click vao file de mo lan dau, set them path va red dot bien mat.
    val viewedPrefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
    val itemScope = rememberCoroutineScope()
    // Key on viewedRefreshTick de re-init khi parent goi "Chon tat ca" mark all viewed.
    var isNewFile by remember(file.path, viewedRefreshTick) {
        mutableStateOf(!file.isDirectory && file.path !in (viewedPrefs.getStringSet("viewed_files", emptySet()) ?: emptySet()))
    }

    val isTrash = viewModel.isSpecialMode && viewModel.specialTitle == "Thùng rác"

    // STATE CHO DIALOG THÔNG BÁO TẠI ĐÂY (THAY THẾ TOAST)
    var commonDialogMessage by remember { mutableStateOf("") }
    var commonDialogType by remember { mutableStateOf(DialogType.SUCCESS) }
    var showCommonDialog by remember { mutableStateOf(false) }

    if (showCommonDialog) {
        AppStatusDialog(
            type = commonDialogType,
            message = commonDialogMessage,
            onDismiss = { showCommonDialog = false }
        )
    }

    if (showPropertiesDialog) {
        com.nas.naswebdav.ui.dialogs.FilePropertiesDialog(
            file = file,
            onDismiss = { showPropertiesDialog = false }
        )
    }

    if (showDeleteDialog) {
        AppStatusDialog(
            type = DialogType.WARNING,
            message = if (isTrash) "Bạn có chắc chắn muốn xóa vĩnh viễn '${file.name}' không? Hành động này không thể hoàn tác." else "Bạn có chắc chắn muốn đưa '${file.name}' vào Thùng rác?",
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteFile(context, file)
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showTransferPickerDialog) {
        com.nas.naswebdav.ui.dialogs.FolderPickerDialog(
            viewModel = viewModel,
            startingUrl = viewModel.webDavManager.currentBaseUrl,
            onDismiss = {
                showTransferPickerDialog = false
                pendingTransferOperation = ""
            },
            onFolderSelected = { destUrl ->
                showTransferPickerDialog = false
                val filesToProcess = listOf(file)
                when (pendingTransferOperation) {
                    "COPY" -> viewModel.batchCopyFiles(context, filesToProcess, destUrl)
                    "MOVE" -> viewModel.batchMoveFiles(context, filesToProcess, destUrl)
                }
                pendingTransferOperation = ""
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Đổi tên", fontWeight = FontWeight.Bold) },
            text = {
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    if (newFileName.isNotBlank() && newFileName != file.name) viewModel.renameFile(context, file, newFileName)
                }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Hủy") } }
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // Mark file da xem -> red dot bien mat. Folder khong tracking.
                    if (!selectionMode && !file.isDirectory && isNewFile) {
                        isNewFile = false
                        itemScope.launch(Dispatchers.IO) {
                            markBrowserFilesViewed(viewedPrefs, listOf(file.path))
                        }
                    }
                    onClick()
                },
                onLongClick = {
                    // Long-press LUON mo menu cho ca file va folder. Selection mode
                    // entry được thực hiện qua nút "Chọn file" ở toolbar.
                    if (selectionMode) {
                        // Trong selection mode -> long-press toggle select (giu logic cu).
                        onLongClick()
                    } else {
                        showMenu = true
                    }
                }
            )
            .padding(horizontal = 1.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("Tải về máy") }, onClick = {
                showMenu = false
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(file.path))
                    .setTitle(file.name)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, file.name)
                    .addRequestHeader("Authorization", auth)
                (context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(request)

                commonDialogType = DialogType.SUCCESS
                commonDialogMessage = "Đã bắt đầu tải về: ${file.name}"
                showCommonDialog = true
            })
            DropdownMenuItem(text = { Text("Sao chép liên kết") }, onClick = {
                showMenu = false
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NAS Link", file.path))

                commonDialogType = DialogType.SUCCESS
                commonDialogMessage = "Đã sao chép liên kết tệp!"
                showCommonDialog = true
            })
            // Chỉ hiện nút Khôi phục nếu đang ở trong Thùng rác
            DropdownMenuItem(text = { Text("Sao chép…") }, onClick = {
                showMenu = false
                pendingTransferOperation = "COPY"
                showTransferPickerDialog = true
            })
            DropdownMenuItem(text = { Text("Di chuyển…") }, onClick = {
                showMenu = false
                pendingTransferOperation = "MOVE"
                showTransferPickerDialog = true
            })
            if (viewModel.isSpecialMode && viewModel.specialTitle == "Thùng rác") {
                DropdownMenuItem(
                    text = { Text("Khôi phục") },
                    onClick = {
                        showMenu = false
                        viewModel.restoreFile(context, file)
                    },)
            }

            // TÍNH NĂNG MỚI: Giải nén tại NAS
            if (file.name.lowercase().endsWith(".zip")) {
                DropdownMenuItem(
                    text = { Text("Giải nén tại NAS", color = Color(0xFF8E24AA), fontWeight = FontWeight.Bold) },
                    onClick = {
                        showMenu = false
                        viewModel.unzipFile(file.path)
                    }
                )
            }

            // Mở video bằng ứng dụng ngoài
            if (isVideo) {
                DropdownMenuItem(
                    text = { Text("Mở bằng ứng dụng ngoài", color = Color(0xFFE65100), fontWeight = FontWeight.Bold) },
                    onClick = {
                        showMenu = false
                        val authSnapshot = viewModel.webDavManager.currentAuthState()
                        openExternalVideoPlayer(
                            context = context,
                            url = file.path,
                            user = authSnapshot.user,
                            pass = authSnapshot.pass,
                            onError = {
                                commonDialogType = DialogType.ERROR
                                commonDialogMessage = "Không tìm thấy trình phát video ngoài nào!"
                                showCommonDialog = true
                            }
                        )
                    }
                )
            }

            DropdownMenuItem(text = { Text("Đổi tên") }, onClick = { showMenu = false; newFileName = file.name; showRenameDialog = true })
            DropdownMenuItem(text = { Text("Thuộc tính") }, onClick = { showMenu = false; showPropertiesDialog = true })
            DropdownMenuItem(text = { Text("Xóa tệp", color = Color.Red) }, onClick = { showMenu = false; showDeleteDialog = true })
        }

        // === KHUNG HIỂN THỊ CHÍNH — ĐỒNG BỘ DASHBOARD DESIGN ===
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (file.isDirectory) Modifier.height(48.dp) else Modifier.aspectRatio(1f))
                .clip(AppShapes.Card)
                .background(
                    when {
                        file.isDirectory -> DarkCard
                        isMedia -> DarkCard
                        else -> DarkCardHover
                    }
                )
        ) {
            if (isMedia) {
                // MEDIA: Thumbnail edge-to-edge
                WebDavCachedThumbnail(url = file.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize(), viewModel = viewModel)

                // Badge video play icon
                if (isVideo) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp).align(Alignment.Center)
                    )
                }
            } else if (file.isDirectory) {
                // THƯ MỤC: Icon folder lớn, canh giữa — dùng AccentCyan đồng bộ dashboard
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                // FILE THƯỜNG: Icon cơ bản
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(32.dp).align(Alignment.Center)
                )
            }

            // GẮN BADGE THÔNG TIN (Cho mọi tệp không phải thư mục)
            if (!file.isDirectory) {
                val ext = file.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() } ?: "FILE"
                
                // MÀU SẮC BADGE THEO LOẠI FILE
                val extColor = when {
                    isImage -> Color(0xFF1E88E5) // Xanh dương
                    isVideo -> Color(0xFFFF8F00) // Cam
                    ext in listOf("ZIP", "RAR", "7Z", "TAR", "GZ") -> Color(0xFFE53935) // Đỏ
                    ext in listOf("TXT", "MD", "LOG", "JSON", "XML", "PY", "KT") -> Color(0xFF43A047) // Xanh lá
                    ext in listOf("PDF", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX") -> Color(0xFF8E24AA) // Tím
                    ext in listOf("MP3", "WAV", "FLAC", "M4A") -> Color(0xFF00ACC1) // Xanh Cyan
                    else -> Color(0xFF757575) // Xám
                }

                // Dung lượng góc dưới trái
                val displaySize = com.nas.naswebdav.utils.FormatUtils.formatBytes(file.contentLength)
                Text(
                    text = displaySize,
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }

            if (!selectionMode && !file.isDirectory) {
                if (isTrash && file.lastModified > 0L) {
                    val daysInTrash = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - file.lastModified)
                    val daysLeft = (30 - daysInTrash).coerceAtLeast(0)
                    val badgeColor = when {
                        daysLeft <= 3 -> Color(0xFFFF1744)
                        daysLeft <= 7 -> Color(0xFFFFA726)
                        else -> Color(0xFF8892B0)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .background(badgeColor.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${daysLeft} ngày", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, lineHeight = 8.sp)
                    }
                } else if (isNewFile) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(10.dp)
                            .background(Color(0xFFFF1744), CircleShape)
                            .border(1.dp, Color.White, CircleShape)
                    )
                }
            }

            // OVERLAY MULTI-SELECT — chỉ khi đang chọn
            if (selectionMode) {
                // Lớp phủ mờ xanh khi được chọn
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x5542A5F5))
                    )
                }
                // Checkbox góc trái trên — luôn hiển thị khi selectionMode
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp)
                        .background(Color.White.copy(alpha = 0.85f), shape = androidx.compose.foundation.shape.CircleShape)
                        .clip(androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Đã chọn",
                            tint = Color(0xFF42A5F5),
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.RadioButtonUnchecked,
                            contentDescription = "Chưa chọn",
                            tint = Color(0xFF90A4AE),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // EXTENSION BADGE — chỉ cho file (không phải folder), đặt thành đường nhỏ ngay dưới icon
        if (!file.isDirectory) {
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            val ext = file.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() } ?: "FILE"
            val isImageFile = com.nas.naswebdav.utils.MediaUtils.isImage(file.name)
            val isVideoFile = com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)
            val extColor = when {
                isImageFile -> AccentCyan
                isVideoFile -> AccentOrange
                ext in listOf("ZIP", "RAR", "7Z", "TAR", "GZ") -> AccentRed
                ext in listOf("TXT", "MD", "LOG", "JSON", "XML", "PY", "KT") -> AccentGreen
                ext in listOf("PDF", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX") -> AccentPurple
                ext in listOf("MP3", "WAV", "FLAC", "M4A") -> AccentCyan
                else -> TextTertiary
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .padding(horizontal = AppSpacing.SM)
                    .background(extColor.copy(alpha = 0.85f), AppShapes.Badge)
            )
        }

        // TÊN THƯ MỤC / TỆP — dùng design system typography
        Spacer(modifier = Modifier.height(AppSpacing.XXS))
        Text(
            text = file.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = AppTypography.LabelMedium,
            color = TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
// --- THUMBNAIL TỐI ƯU HOÁ CHO TẤT CẢ FILE MEDIA: LƯU VÀO DATABASE VĨNH VIỄN ---
// ════════════════════════════════════════════════════════════════════════════
// THUMBNAIL LOADER — Phone-only, NAS-free architecture
// ════════════════════════════════════════════════════════════════════════════
// Nguyên tắc: NAS chỉ là storage, phone tự xử lý thumbnail
// - Coil manages ALL caching (memory LRU + disk) — không cần Room DB
// - Video: Coil VideoFrameDecoder (MediaCodec, client-side)
// - Ảnh: Coil downsampling trực tiếp từ WebDAV URL
// - Không gọi NAS `/api/thumb` — NAS chỉ serve raw bytes
// - File lỗi: badge đỏ rõ ràng

@Composable
fun WebDavCachedThumbnail(
    url: String,
    auth: String,
    isVideo: Boolean,
    modifier: Modifier,
    viewModel: WebDavViewModel
) {
    val context = LocalContext.current
    var loadState by remember(url, auth) { mutableStateOf<ThumbState>(ThumbState.LOADING) }
    var retryKey by remember(url, auth) { mutableStateOf(0) }

    // Coil memory + disk cache handles everything — no Room DB, no NAS API
    val imageRequest = coil.request.ImageRequest.Builder(context)
        .data(url)
        .addHeader("Authorization", auth)
        .crossfade(true)
        .size(coil.size.Size(300, 300))
        .allowHardware(true)
        .build()

    if (loadState != ThumbState.ERROR) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            onSuccess = { loadState = ThumbState.SUCCESS },
            onError = { loadState = ThumbState.ERROR }
        )
    }

    if (loadState == ThumbState.ERROR) {
        Box(modifier = modifier.background(DarkCard), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.BrokenImage, null, tint = AccentRed, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(2.dp))
                // Red error badge
                Box(
                    modifier = Modifier
                        .background(AccentRed, AppShapes.Badge)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text("!", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private enum class ThumbState { LOADING, SUCCESS, ERROR }

fun openExternalVideoPlayer(
    context: android.content.Context,
    url: String,
    user: String,
    pass: String,
    onError: () -> Unit
) {
    try {
        // Ưu tiên nginx media route (không cần auth)
        val mediaUrl = runCatching {
            val parsed = java.net.URL(url)
            val port = if (parsed.port > 0) ":${parsed.port}" else ""
            val filePart = parsed.file ?: ""
            val fastFilePart = when {
                filePart == "/webdav" -> "/media/"
                filePart.startsWith("/webdav/") -> "/media/" + filePart.removePrefix("/webdav/")
                else -> filePart
            }
            if (fastFilePart.startsWith("/media/")) {
                "${parsed.protocol}://${parsed.host}$port$fastFilePart"
            } else {
                null as String?
            }
        }.getOrNull()

        val (videoUrl, viaProxy) = if (mediaUrl != null) {
            // Nginx media route — không cần auth, VLC/MX mở trực tiếp
            mediaUrl to false
        } else {
            // WebDAV direct — cần proxy localhost để inject Basic Auth
            val proxy = com.nas.naswebdav.LocalVideoProxy(user, pass)
            val proxyUrl = proxy.start(url)
            android.util.Log.d("BrowserScreen", "LocalVideoProxy active: $proxyUrl")
            proxyUrl to true
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(videoUrl), "video/*")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = android.content.Intent.createChooser(intent, "Chọn trình phát video (VLC, MX Player...)")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        android.util.Log.w("BrowserScreen", "Không mở được trình phát video ngoài: ${e.message}", e)
        onError()
    }
}

