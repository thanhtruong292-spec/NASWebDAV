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

private const val VIEWED_FILES_LIMIT = 5000

private fun markBrowserFilesViewed(
    prefs: android.content.SharedPreferences,
    paths: Collection<String>
) {
    val cleanPaths = paths.filter { it.isNotBlank() }.distinct()
    if (cleanPaths.isEmpty()) return
    synchronized(prefs) {
        val viewed = prefs.getStringSet("viewed_files", emptySet())?.toMutableSet() ?: mutableSetOf()
        val order = mutableListOf<String>()
        val orderRaw = prefs.getString("viewed_files_order", "[]") ?: "[]"
        try {
            val arr = JSONArray(orderRaw)
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "")
                if (p.isNotBlank() && p in viewed) order.add(p)
            }
        } catch (_: Exception) {}

        val cleanSet = cleanPaths.toSet()
        order.removeAll(cleanSet)
        order.addAll(cleanPaths)
        viewed.addAll(cleanPaths)

        while (order.size > VIEWED_FILES_LIMIT) {
            viewed.remove(order.removeAt(0))
        }
        if (viewed.size > VIEWED_FILES_LIMIT) {
            val keep = order.toSet()
            viewed.removeAll(viewed.filter { it !in keep }.take(viewed.size - VIEWED_FILES_LIMIT).toSet())
        }

        prefs.edit()
            .putStringSet("viewed_files", viewed)
            .putString("viewed_files_order", JSONArray(order).toString())
            .apply()
    }
}

// --- BROWSER SCREEN ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: WebDavViewModel,
    onVideo: (String) -> Unit,
    onImage: (String) -> Unit,
    onLogout: () -> Unit,
    onBackToMenu: () -> Unit // Thêm tham số này
) {
    // Trạng thái thanh tìm kiếm
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val historyManager = remember { SearchHistoryManager(context) }
    val searchHistory by remember(isSearching, searchQuery) { 
        mutableStateOf(if (isSearching && searchQuery.isEmpty()) historyManager.getHistory() else emptyList())
    }

    // TÍNH NĂNG 7.L: Trạng thái của chế độ Multi-Selection
    var selectionMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { androidx.compose.runtime.mutableStateListOf<NasFile>() }
    // Tick increment khi can refresh red-dot "newFile" indicator tu SharedPrefs.
    // VD: nhan "Chon tat ca" -> mark all viewed -> increment tick -> moi
    // FileItemGridCell remember key bi invalidated -> doc lai prefs.
    var viewedRefreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(viewModel.currentUrl) { viewedRefreshTick++ }

    // SORT — luu trong SharedPreferences de nho cua user qua cac lan vao app.
    // Values: "name_asc" | "name_desc" | "date_desc" | "date_asc" | "size_desc" | "size_asc"
    val sortPrefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
    var sortMode by remember { mutableStateOf(sortPrefs.getString("file_sort", "name_asc") ?: "name_asc") }
    var showSortMenu by remember { mutableStateOf(false) }

    // TÍNH NĂNG 7.M: Trạng thái Text Preview
    var showTextPreviewDialog by remember { mutableStateOf(false) }
    var textPreviewName by remember { mutableStateOf("") }

    // TÍNH NĂNG: Trạng thái Folder Picker cho Copy/Move
    var showFolderPickerDialog by remember { mutableStateOf(false) }
    var pendingBatchOperation by remember { mutableStateOf("") } // "COPY" hoặc "MOVE"


    LaunchedEffect(Unit) {
        viewModel.autoCleanEnabled = context.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE).getBoolean("auto_clean_enabled", false)
    }

    // Xóa chế độ chọn khi đổi thư mục
    LaunchedEffect(viewModel.currentUrl) {
        if (selectionMode) {
            selectedFiles.clear()
            selectionMode = false
        }
    }

    // SỬA LỖI 13.14: Bắt vòng đời Dispose để rút cạn danh sách đang Chọn, giải tỏa Memory Leak
    DisposableEffect(Unit) {
        onDispose {
            selectedFiles.clear()
            selectionMode = false
        }
    }

    // XỬ LÝ NÚT BACK THÔNG MINH: Đồng bộ giữa Folder và Sub-menu
    BackHandler {
        if (selectionMode) {
            selectionMode = false
            selectedFiles.clear()
        } else if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else {
            // Trong mọi chế độ (Bình thường hay isSpecialMode), thử lùi cấu trúc cây thư mục trước
            // Nếu urlStack cạn (nghĩa là đã về gốc của chế độ đó), thì mới thoát ra Menu Chính
            if (!viewModel.goBack()) {
                onBackToMenu()
            }
        }
    }

    // TÍNH NĂNG 7.M: Màn hình đọc lướt File Văn Bản Code nhanh chóng
    if (showTextPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showTextPreviewDialog = false; viewModel.textPreviewContent = null },
            title = { Text(textPreviewName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (viewModel.isLoading && viewModel.textPreviewContent == null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(10.dp))
                    }
                } else if (!viewModel.textPreviewContent.isNullOrBlank()) {
                    SelectionContainer { // Cấp quyền bôi đen copy đoạn Text mồi này
                        Text(
                            text = viewModel.textPreviewContent!!,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                } else {
                    Text("Không thể tải nội dung tệp hoặc tệp rỗng.", color = Color.Red)
                }
            },
            confirmButton = {
                TextButton(onClick = { showTextPreviewDialog = false; viewModel.textPreviewContent = null }) {
                    Text("Đóng")
                }
            }
        )
    }

    val animatedProgress by animateFloatAsState(targetValue = viewModel.imageLoadProgress, animationSpec = tween(500), label = "Progress")
    val coroutineScope = rememberCoroutineScope()

    // Trạng thái hiển thị hộp thoại tạo thư mục
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showOrganizeDialog by remember { mutableStateOf(false) }
    var isOrganizing by remember { mutableStateOf(false) }
    var organizeResult by remember { mutableStateOf<String?>(null) }


    // PHASE 9: Trạng thái Hộp thoại Cấu hình Quét Dọn Thông Minh
    var showDuplicateConfigDialog by remember { mutableStateOf(false) }
    var isLightningMode by remember { mutableStateOf(true) } // Mặc định bật chế độ quét nhanh
    var isForceRestartDuplicate by remember { mutableStateOf(false) }

    // ============ DIALOG: Chọn thư mục đích cho Copy/Move ============
    if (showFolderPickerDialog) {
        com.nas.naswebdav.ui.dialogs.FolderPickerDialog(
            viewModel = viewModel,
            startingUrl = viewModel.webDavManager.currentBaseUrl,
            onDismiss = {
                showFolderPickerDialog = false
                pendingBatchOperation = ""
            },
            onFolderSelected = { destUrl ->
                showFolderPickerDialog = false
                val filesToProcess = selectedFiles.toList()
                when (pendingBatchOperation) {
                    "COPY" -> viewModel.batchCopyFiles(context, filesToProcess, destUrl)
                    "MOVE" -> viewModel.batchMoveFiles(context, filesToProcess, destUrl)
                }
                pendingBatchOperation = ""
                selectedFiles.clear()
                selectionMode = false
            }
        )
    }

    var showMultiDeleteDialog by remember { mutableStateOf(false) }
    if (showMultiDeleteDialog) {
        val isTrash = viewModel.isSpecialMode && viewModel.specialTitle == "Thùng rác"
        com.nas.naswebdav.ui.dialogs.MultiDeleteDialog(
            selectedCount = selectedFiles.size,
            isTrash = isTrash,
            onConfirm = {
                showMultiDeleteDialog = false
                viewModel.deleteMultipleFiles(context, selectedFiles.toList())
                selectionMode = false
                selectedFiles.clear()
            },
            onDismiss = { showMultiDeleteDialog = false }
        )
    }

    // ============ DIALOG: Hộp thoại trùng lặp (ĐÃ XÓA THEO YÊU CẦU: CHỈ GIỮ AUTO BACKUP) ============

    // ============ DIALOG: Cấu hình Quét dọn Rác NHANH (PHASE 9) ============
    if (showDuplicateConfigDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateConfigDialog = false },
            icon = { Icon(Icons.Default.Bolt, null, tint = Color(0xFFFFC107), modifier = Modifier.size(36.dp)) },
            title = { Text("Cấu hình quét trùng lặp", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Thiết lập hệ thống kiểm tra hàng nghìn tệp trên Server NAS.", fontSize = 13.sp, color = Color.Gray)
                    
                    // Option 1: Lightning Mode
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { isLightningMode = !isLightningMode }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isLightningMode,
                            onCheckedChange = { isLightningMode = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFC107))
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text("⚡ Chế độ nhanh (Khuyến nghị)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isLightningMode) Color(0xFFFFC107) else Color.White)
                            Text("Nhanh gấp 100 lần. Bỏ qua phân tích nội dung, chỉ dùng ETag gốc (dung lượng, tên, ngày sửa). Có thể quét rất nhanh tới 500.000 tệp.", fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
                        }
                    }

                    // Option 2: Force Restart
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { isForceRestartDuplicate = !isForceRestartDuplicate }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isForceRestartDuplicate, onCheckedChange = { isForceRestartDuplicate = it })
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text("Quét lại từ đầu", fontSize = 14.sp, color = Color.White)
                            Text("Thực hiện quét lại toàn bộ ổ cứng NAS, bỏ qua lịch sử lưu tạm.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(Modifier.height(8.dp))

                    // Option 3: Tự động chạy ngầm (Auto Clean)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("🤖 Tự động dọn dẹp (hàng tuần)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                            Text("Chạy nền 7 ngày/lần khi điện thoại đang sạc pin và có Wi-Fi. Tự động chuyển tệp trùng vào thùng rác (.trash), giữ lại tệp có đường dẫn ngắn nhất.", fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
                        }
                        Switch(
                            checked = viewModel.autoCleanEnabled,
                            onCheckedChange = { viewModel.toggleAutoClean(context, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4FC3F7), checkedTrackColor = Color(0xFF4FC3F7).copy(alpha = 0.5f))
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDuplicateConfigDialog = false
                        viewModel.startBackgroundDuplicateScan(context, forceRestart = isForceRestartDuplicate, lightningMode = isLightningMode)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("🚀 Bắt đầu quét")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateConfigDialog = false }) { Text("Hủy", color = Color.Gray) }
            }
        )
    }

// Trạng thái hiển thị menu 3 chấm trên TopAppBar
    var showMoreMenu by remember { mutableStateOf(false) }

    // PHASE 6.C: Tự động mở màn hình dọn rác nếu được gọi từ Notification
    LaunchedEffect(viewModel.shouldAutoOpenDuplicates) {
        if (viewModel.shouldAutoOpenDuplicates) {
            viewModel.shouldAutoOpenDuplicates = false
            viewModel.loadDuplicateResultsFromCache(context)
        }
    }

    // Đã xóa UploadLauncher và SyncFolderLauncher theo yêu cầu (Chỉ giữ Auto Backup)
    if (showCreateFolderDialog) {
        com.nas.naswebdav.ui.dialogs.CreateFolderDialog(
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    // TÍNH NĂNG 5.I: Truyền context vào để nhét vô SQLite Queue nếu mất mạng
                    viewModel.createFolder(context, name)
                }
                showCreateFolderDialog = false
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    if (showOrganizeDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!isOrganizing) showOrganizeDialog = false },
            title = { Text("Phân loại video cũ") },
            text = {
                Column {
                    if (isOrganizing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Color(0xFF00897B), trackColor = Color.Transparent)
                        Text("Đang ra lệnh cho NAS dọn dẹp nội bộ...")
                    } else if (organizeResult != null) {
                        Text(organizeResult!!)
                    } else {
                        Text("Bạn có chắc chắn muốn NAS quét và di chuyển toàn bộ video không phải MP4 (như mpg, flv, mkv, avi...) vào thư mục 'Other Video' không? Thao tác này giúp danh sách video gọn hơn và được xử lý trực tiếp trên NAS.")
                    }
                }
            },
            confirmButton = {
                if (!isOrganizing && organizeResult == null) {
                    TextButton(
                        onClick = {
                            isOrganizing = true
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val authSnapshot = viewModel.webDavManager.currentAuthState()
                                    val urlStr = authSnapshot.baseUrl
                                    
                                    val host = java.net.URL(urlStr).host ?: "127.0.0.1"
                                    val apiUrl = "${urlStr.toApiBaseUrl()}/api/tools/organize_legacy_videos"
                                    
                                    val request = okhttp3.Request.Builder()
                                        .url(apiUrl)
                                        .post(ByteArray(0).toRequestBody(null, 0, 0))
                                        .header("Authorization", authSnapshot.authHeader)
                                        .build()
                                        
                                    // Tăng timeout lên 5 phút vì thao tác quét và chép file toàn bộ NAS có thể lâu hơn 30s
                                    val client = NasApplication.instance.sharedHttpClient.newBuilder()
                                        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                                        .build()
                                    client.newCall(request).execute().use { response ->
                                        val body = response.body?.string()
                                        
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            isOrganizing = false
                                            if (response.isSuccessful && body != null) {
                                                try {
                                                    val json = org.json.JSONObject(body)
                                                    val count = json.optInt("moved_count", 0)
                                                    organizeResult = "Hoàn tất! Đã gom $count video."
                                                    viewModel.refresh()
                                                } catch (e: Exception) {
                                                    organizeResult = "Lỗi phản hồi: ${e.message}"
                                                }
                                            } else {
                                                organizeResult = "Lỗi NAS: ${response.code}"
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        isOrganizing = false
                                        organizeResult = "Lỗi kết nối: ${e.message}"
                                    }
                                }
                            }
                        }
                    ) { Text("Chạy NAS") }
                } else if (organizeResult != null) {
                    TextButton(onClick = { showOrganizeDialog = false; organizeResult = null }) { Text("Đóng") }
                }
            },
            dismissButton = {
                if (!isOrganizing && organizeResult == null) {
                    TextButton(onClick = { showOrganizeDialog = false }) { Text("Hủy") }
                }
            }
        )
    }
    // Logic lọc danh sách file theo từ khóa tìm kiếm (bỏ qua viết hoa/viết thường)
    // FIX BUG #4: derivedStateOf thay remember — tránh recomposition bất ổn khi fileList thay đổi
    // NOTE: Khai báo ở đây (trước Scaffold) để TopAppBar có thể truy cập displayedFiles
    val displayedFiles by remember {
        derivedStateOf {
            val filtered = if (searchQuery.isBlank()) {
                viewModel.fileList
            } else {
                viewModel.fileList.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
            // SORT: thu muc luon o tren, sau do ap dung sort theo che do user chon
            val folders = filtered.filter { it.isDirectory }
            val files = filtered.filter { !it.isDirectory }
            val sortFn: (NasFile) -> Comparable<*> = when (sortMode) {
                "name_desc", "name_asc" -> { f -> f.name.lowercase() }
                "date_desc", "date_asc" -> { f -> f.lastModified }
                "size_desc", "size_asc" -> { f -> f.contentLength }
                else -> { f -> f.name.lowercase() }
            }
            val descending = sortMode.endsWith("_desc")
            @Suppress("UNCHECKED_CAST")
            val cmp = compareBy<NasFile> { sortFn(it) as Comparable<Any> }
            val orderedFolders = if (descending) folders.sortedWith(cmp.reversed()) else folders.sortedWith(cmp)
            val orderedFiles = if (descending) files.sortedWith(cmp.reversed()) else files.sortedWith(cmp)
            orderedFolders + orderedFiles
        }
    }

    // TÍNH NĂNG MỚI: THANH TIẾN TRÌNH NỔI (FLOATING TRANSFER BAR) DÀNH CHO AUTO-BACKUP
    Scaffold(
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(
                visible = viewModel.isAutoBackupRunning,
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f).padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { viewModel.autoBackupProgress },
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                                strokeWidth = 4.dp
                            )
                            Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Tiến trình Tự động Sao lưu",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "${(viewModel.autoBackupProgress * 100).toInt()}%",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                text = "Nguồn: ${viewModel.autoBackupSourcePath.substringBeforeLast("/", "").takeLast(15)}/${viewModel.autoBackupCurrentFile}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Đích: ${viewModel.autoBackupDestPath}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                            )
                        }
                        IconButton(onClick = { viewModel.isAutoBackupRunning = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Ẩn", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }


        },
        topBar = {
            if (isSearching) {

            TopAppBar(
                title = {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Tìm kiếm tệp...", color = Color.Gray) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            historyManager.saveQuery(searchQuery)
                            focusManager.clearFocus()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Đóng")
                    }
                },
                actions = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Xóa")
                        }
                    }
                }
            )
        } else if (selectionMode) {
            // TÍNH NĂNG 7.L: Giao diện Gộp nhóm (Multi-Selection Mode Bar)
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val allSelected = displayedFiles.isNotEmpty() && selectedFiles.size == displayedFiles.size
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    selectedFiles.clear()
                                    selectedFiles.addAll(displayedFiles)
                                    // FIX: "Chon tat ca" cung mark moi file la da xem
                                    // de bo cham do (newFile indicator) — user da chu y
                                    // den toan bo danh sach thi khong can chi dau.
                                    try {
                                        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                                        val paths = displayedFiles.filter { !it.isDirectory }.map { it.path }
                                        coroutineScope.launch(Dispatchers.IO) {
                                            markBrowserFilesViewed(prefs, paths)
                                        }
                                        // Bump tick de moi FileItemGridCell remember key bi
                                        // invalidated -> doc lai prefs -> red dot bien mat.
                                        viewedRefreshTick++
                                    } catch (_: Exception) {}
                                } else {
                                    selectedFiles.clear()
                                    selectionMode = false
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF42A5F5),
                                uncheckedColor = Color.White,
                                checkmarkColor = Color.White
                            )
                        )
                        Text("${selectedFiles.size} đã chọn", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { selectionMode = false; selectedFiles.clear() }) {
                        Icon(Icons.Default.Close, contentDescription = "Đóng")
                    }
                },
                actions = {
                    val isTrash = viewModel.isSpecialMode && viewModel.specialTitle == "Thùng rác"
                    
                    if (isTrash) {
                        // NÚT KHÔI PHỤC HÀNG LOẠT (CHỈ TRONG THÙNG RÁC)
                        IconButton(
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    viewModel.restoreMultipleFiles(context, selectedFiles.toList())
                                    selectionMode = false
                                    selectedFiles.clear()
                                }
                            },
                            enabled = selectedFiles.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = "Khôi phục",
                                tint = if (selectedFiles.isNotEmpty()) Color(0xFF66BB6A) else Color.Gray
                            )
                        }
                    } else {
                        // NÚT SAO CHÉP
                        IconButton(
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    pendingBatchOperation = "COPY"
                                    showFolderPickerDialog = true
                                }
                            },
                            enabled = selectedFiles.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Sao chép",
                                tint = if (selectedFiles.isNotEmpty()) Color(0xFF66BB6A) else Color.Gray
                            )
                        }
                        // NÚT DI CHUYỂN
                        IconButton(
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    pendingBatchOperation = "MOVE"
                                    showFolderPickerDialog = true
                                }
                            },
                            enabled = selectedFiles.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.DriveFileMove,
                                contentDescription = "Di chuyển",
                                tint = if (selectedFiles.isNotEmpty()) Color(0xFFFF8F00) else Color.Gray
                            )
                        }
                    }
                    
                    // NÚT XÓA HÀNG LOẠT (DÙNG CHUNG)
                    IconButton(
                        onClick = {
                            showMultiDeleteDialog = true
                        },
                        enabled = selectedFiles.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = if (isTrash) "Xóa vĩnh viễn" else "Xóa",
                            tint = if (selectedFiles.isNotEmpty()) Color(0xFFEF5350) else Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        } else {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.goBack()) onBackToMenu()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                title = {
                    val displayTitle = if (viewModel.isSpecialMode) viewModel.specialTitle
                    else {
                        val decodedUrl = try { java.net.URLDecoder.decode(viewModel.currentUrl, "UTF-8") } catch (_: Exception) { viewModel.currentUrl }
                        val baseUrl = viewModel.webDavManager.currentBaseUrl
                        val relativePath = if (decodedUrl.startsWith(baseUrl)) decodedUrl.removePrefix(baseUrl) else ""
                        val segments = relativePath.trim('/').split("/").filter { it.isNotEmpty() }
                        if (segments.isEmpty()) "Thư mục gốc" else segments.last()
                    }
                    Column {
                        Text(displayTitle, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    // Thiết kế Chip trạng thái kết nối
                    val rawStatus = viewModel.connectionStatus
                        val displayStatus = if (rawStatus.contains("Cache", ignoreCase = true)) "Cache" else rawStatus

                        val chipIcon = when {
                            displayStatus.contains("Chờ", ignoreCase = true) -> Icons.Default.Help
                            displayStatus.contains("Lỗi", ignoreCase = true) || displayStatus.contains("Mất", ignoreCase = true) -> Icons.Default.Cancel
                            else -> Icons.Default.CheckCircle
                        }

                        val chipColor = when {
                            displayStatus.contains("Chờ", ignoreCase = true) -> Color(0xFF1E88E5) // Xanh dương
                            displayStatus.contains("Lỗi", ignoreCase = true) || displayStatus.contains("Mất", ignoreCase = true) -> Color(0xFFE53935) // Đỏ
                            displayStatus.contains("Cache", ignoreCase = true) -> Color(0xFFF57C00) // Cam
                            else -> Color(0xFF43A047) // Xanh lá
                        }

                        Surface(
                            color = chipColor.copy(alpha = 0.15f), // Nền mờ bao quanh tinh tế hơn
                            shape = RoundedCornerShape(percent = 50),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = chipIcon,
                                    contentDescription = null,
                                    tint = chipColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = displayStatus + if (viewModel.networkPingMs != null && viewModel.networkPingMs!! >= 0) " - ${viewModel.networkPingMs}ms" else "",
                                    color = chipColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
            }, actions = {
                // Giữ lại 2 nút quan trọng nhất hiển thị trực tiếp
                IconButton(onClick = { isSearching = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Tìm kiếm")
                }
                // Đã ẩn nút Tải lên lẻ

                // Gom các nút còn lại vào Menu 3 chấm để giải phóng không gian màn hình
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Tùy chọn khác")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        // Đã xóa nút Đồng bộ thư mục
                        DropdownMenuItem(
                            text = { Text("Tìm tệp trùng lặp") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { showMoreMenu = false; showDuplicateConfigDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Tạo thư mục") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { showMoreMenu = false; showCreateFolderDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Gom video cũ") },
                            leadingIcon = { Icon(Icons.Default.SnippetFolder, null) },
                            onClick = { showMoreMenu = false; showOrganizeDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Làm mới") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { showMoreMenu = false; viewModel.refresh() }
                        )
                        DropdownMenuItem(
                            text = { Text("Ảnh ngẫu nhiên") },
                            leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                            onClick = {
                                showMoreMenu = false
                                val images = viewModel.fileList.filter { it.name.lowercase().run { endsWith(".jpg") || endsWith(".png") } }
                                images.randomOrNull()?.let { onImage(it.path) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Đăng xuất", color = Color.Red) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Color.Red) },
                            onClick = { showMoreMenu = false; onLogout() }
                        )
                    }
                }
            })
        }
    }) { padding ->
        // displayedFiles đã được khai báo trước Scaffold ở trên

        // Lược bỏ Paging 3 siêu hạng để dùng LazyGrid tĩnh (Native siêu mượt)

        Column(Modifier.fillMaxSize().padding(padding)) {
            // ═══ THANH ĐƯỜNG DẪN (BREADCRUMB) ═══
            if (!viewModel.isSpecialMode) {
                val baseUrl = viewModel.webDavManager.currentBaseUrl
                val relativePath = if (viewModel.currentUrl.startsWith(baseUrl))
                    viewModel.currentUrl.removePrefix(baseUrl) else ""
                val decodedPath = try { java.net.URLDecoder.decode(relativePath, "UTF-8") } catch (_: Exception) { relativePath }
                val segments = decodedPath.trim('/').split("/").filter { it.isNotEmpty() }
                
                val scrollState = androidx.compose.foundation.rememberScrollState()
                LaunchedEffect(segments) { scrollState.animateScrollTo(scrollState.maxValue) }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Trái: Đường dẫn dạng cuộn ngang
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(scrollState),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("NAS", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { viewModel.resetToRoot() })
                        
                        segments.forEachIndexed { index, segment ->
                            Text(" / ", fontSize = 12.sp, color = Color.Gray)
                            val isLast = index == segments.lastIndex
                            Text(
                                text = segment,
                                fontSize = 12.sp,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = if (!isLast) Modifier.clickable {
                                    val targetPath = segments.take(index + 1).joinToString("/") + "/"
                                    val targetUrl = baseUrl + targetPath
                                    viewModel.urlStack.clear()
                                    for (i in 0 until index + 1) {
                                        if (i == 0) viewModel.urlStack.push(baseUrl)
                                        else viewModel.urlStack.push(baseUrl + segments.take(i).joinToString("/") + "/")
                                    }
                                    viewModel.navigateToUrl(targetUrl)
                                } else Modifier
                            )
                        }
                    }
                    
                    // Phải: Thống kê + Nút Chọn
                    val folders = viewModel.fileList.count { it.isDirectory }
                    val files = viewModel.fileList.count { !it.isDirectory }
                    val statsText = buildList {
                        if (folders > 0) add("$folders thư mục")
                        if (files > 0) add("$files tệp")
                    }.joinToString(", ").ifEmpty { "Trống" }

                    Text(
                        text = statsText,
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )

                    // Nút Sắp xếp file — hiện bên cạnh nút "Chọn file" để user dễ tìm
                    if (!selectionMode && viewModel.fileList.isNotEmpty()) {
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sort,
                                    contentDescription = "Sắp xếp",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                data class SortOpt(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                                val opts = listOf(
                                    SortOpt("name_asc",  "Tên: A → Z",          Icons.Default.SortByAlpha),
                                    SortOpt("name_desc", "Tên: Z → A",          Icons.Default.SortByAlpha),
                                    SortOpt("date_desc", "Mới nhất trước",      Icons.Default.Schedule),
                                    SortOpt("date_asc",  "Cũ nhất trước",       Icons.Default.Schedule),
                                    SortOpt("size_desc", "Kích thước: Lớn → Nhỏ", Icons.Default.DataUsage),
                                    SortOpt("size_asc",  "Kích thước: Nhỏ → Lớn", Icons.Default.DataUsage),
                                )
                                opts.forEach { opt ->
                                    val isActive = sortMode == opt.key
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                opt.label,
                                                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                opt.icon, null,
                                                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        trailingIcon = if (isActive) {
                                            { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                        } else null,
                                        onClick = {
                                            sortMode = opt.key
                                            sortPrefs.edit().putString("file_sort", opt.key).apply()
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Nút kích hoạt chế độ chọn nhiều file
                    if (!selectionMode && viewModel.fileList.isNotEmpty()) {
                        IconButton(
                            onClick = { selectionMode = true },
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = "Chọn file",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            
            // Thanh tiến trình tải ảnh (chỉ hiện khi đang tải hàng loạt)
            if (animatedProgress > 0f && animatedProgress < 1f) LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF00897B), trackColor = Color.Transparent)

            // TÍNH NĂNG MỚI: Trạng thái Kéo để làm mới (Pull-to-Refresh) CHUẨN ĐỒNG BỘ
            val pullToRefreshState = rememberPullToRefreshState()
            val view = androidx.compose.ui.platform.LocalView.current

            // 1. Kích hoạt tải dữ liệu khi bị vuốt (Refresh Trigger)
            if (pullToRefreshState.isRefreshing) {
                LaunchedEffect(true) {
                    // TÍNH NĂNG 3.F: Thiết lập Haptic Feedback phản hồi vật lý
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    viewModel.refresh()
                }
            }

            // 2. Chờ tải xong mới thu hồi Pull-to-Refresh UI bằng luồng Flow an toàn (Không khoá UI)
            LaunchedEffect(pullToRefreshState.isRefreshing) {
                if (pullToRefreshState.isRefreshing) {
                    androidx.compose.runtime.snapshotFlow { viewModel.isLoading }
                        .collect { loading ->
                            if (!loading) {
                                pullToRefreshState.endRefresh()
                            }
                        }
                }
            }

            // TÍNH NĂNG 3.E: Lịch sử Tìm kiếm nằm ngay dưới Thanh Tìm Kiếm
            if (isSearching && searchQuery.isEmpty() && searchHistory.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    item { Text("Tìm kiếm gần đây", color = Color.Gray, modifier = Modifier.padding(10.dp)) }
                    items(items = searchHistory, key = { it.query }) { history ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { searchQuery = history.query; historyManager.saveQuery(history.query); focusManager.clearFocus() }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray, modifier = Modifier.padding(end = 16.dp))
                            Text(history.query, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            } else {

            // SỬA LỖI: Sử dụng trực tiếp nestedScroll() sau khi đã import
            Box(Modifier.fillMaxSize().nestedScroll(pullToRefreshState.nestedScrollConnection)) {
                
                // Grouping logic for Trash
                val isTrashMode = viewModel.isSpecialMode && viewModel.specialTitle == "Thùng rác"
                val groupedFiles = remember(displayedFiles, isTrashMode) {
                    if (isTrashMode) {
                        val now = System.currentTimeMillis()
                        displayedFiles.groupBy { file ->
                            // lastModified trong Thùng rác chính là thời điểm file bị move name vào nó
                            val diffDays = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(now - (file.lastModified ?: now))
                            when {
                                diffDays == 0L -> "Hôm nay"
                                diffDays == 1L -> "Hôm qua"
                                diffDays in 2..7 -> "Tuần này"
                                diffDays in 8..30 -> "Tháng này"
                                else -> "Cũ hơn"
                            }
                        }
                    } else {
                        mapOf("" to displayedFiles)
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    groupedFiles.forEach { (header, filesInGroup) ->
                        if (header.isNotEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = header,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                                )
                            }
                        }
                        
                        items(
                            items = filesInGroup, 
                            key = { it.path },
                            contentType = { if (it.isDirectory) "folder" else "file" }
                        ) { file ->
                            FileItemGridCell(
                                file = file,
                                viewModel = viewModel,
                                selectionMode = selectionMode,
                                viewedRefreshTick = viewedRefreshTick,
                                isSelected = selectedFiles.contains(file),
                                onLongClick = {
                                    if (!selectionMode) {
                                        // Nhấn giữ lần đầu → kích hoạt chế độ chọn và chọn file này
                                        selectionMode = true
                                        selectedFiles.add(file)
                                    } else {
                                        // Đang trong chế độ chọn → toggle file này
                                        if (selectedFiles.contains(file)) {
                                            selectedFiles.remove(file)
                                            if (selectedFiles.isEmpty()) selectionMode = false
                                        } else {
                                            selectedFiles.add(file)
                                        }
                                    }
                                },
                                onClick = {
                                    if (selectionMode) {
                                        if (selectedFiles.contains(file)) {
                                            selectedFiles.remove(file)
                                            if (selectedFiles.isEmpty()) selectionMode = false
                                        } else {
                                            selectedFiles.add(file)
                                        }
                                    } else {
                                        if (file.isDirectory) viewModel.openFolder(file)
                                        else if (com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)) onVideo(file.path)
                                        else if (file.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }) onImage(file.path)
                                        // TÍNH NĂNG 7.M: Ném file Text lên màn hình nổi
                                        else if (file.name.lowercase().run { endsWith(".txt") || endsWith(".md") || endsWith(".py") || endsWith(".log") || endsWith(".json") || endsWith(".xml") || endsWith(".kt") || endsWith(".java") }) {
                                            viewModel.fetchTextPreview(file.path)
                                            textPreviewName = file.name
                                            showTextPreviewDialog = true
                                        }
                                    }
                                }, 
                                onVideo = onVideo
                            )
                        }
                    }
                }

                // LOADING HIỆN ĐẠI NHẤT: LinearProgressIndicator đặt ngay dưới TopAppBar thay vì vòng tròn trắng cồng kềnh
                if (viewModel.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                        color = Color(0xFF00897B), // Đồng bộ màu Xanh Ngọc
                        trackColor = Color.Transparent
                    )
                }
                // Hiển thị lỗi kết nối rõ ràng ở giữa màn hình
                val currentError = viewModel.errorMessage
                if (!currentError.isNullOrEmpty() && !viewModel.isLoading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Không thể kết nối NAS", fontWeight = FontWeight.Bold, color = Color(0xFFEF5350))
                        Spacer(Modifier.height(4.dp))
                        Text(currentError, color = Color.Gray, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("Thử lại") }
                    }
                } else if (!viewModel.isLoading && displayedFiles.isEmpty() && currentError.isNullOrEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Thư mục trống", color = Color.Gray)
                    }
                }
            }
            } // THÊM DẤU NÀY ĐỂ ĐÓNG KHỐI else CỦA TÍNH NĂNG 3.E TÌM KIẾM
        }

        // LẮNG NGHE VÀ HIỂN THỊ DIALOG TỪ VIEWMODEL TRÊN BROWSER SCREEN
        if (viewModel.showCommonDialog) {
            AppStatusDialog(
                type = viewModel.commonDialogType,
                message = viewModel.commonDialogMessage,
                onDismiss = { viewModel.showCommonDialog = false }
            )
        }
    }
}

