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
                                    val user = viewModel.webDavManager.currentUser
                                    val pass = viewModel.webDavManager.currentPass
                                    val urlStr = viewModel.webDavManager.currentBaseUrl
                                    
                                    val host = java.net.URL(urlStr).host ?: "127.0.0.1"
                                    val apiUrl = "${urlStr.toApiBaseUrl()}/api/tools/organize_legacy_videos"
                                    
                                    val request = okhttp3.Request.Builder()
                                        .url(apiUrl)
                                        .post(ByteArray(0).toRequestBody(null, 0, 0))
                                        .header("Authorization", okhttp3.Credentials.basic(user, pass))
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
    // 2. Hộp thoại Quét Rác — TÁI THIẾT KẾ HIỂN THỊ CHÍNH XÁC
    if (viewModel.isScanningDuplicates) {
        val scanSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
            // Onclick scrim KHONG dong sheet — user phai bam nut "Thu nho" / "Huy" explicit.
            // Cach lam: onDismissRequest -> mac dinh ban dau dong sheet -> ta set
            // isScanningDuplicates = false neu user thu nho thu cong.
            // Voi behavior "khong dong khi click ngoai", dismissRequest cua sheet phai
            // skip-action: chi log + thu nho (= behavior cua nut Thu nho).
            onDismissRequest = { viewModel.isScanningDuplicates = false },
            sheetState = scanSheetState,
            containerColor = Color(0xFF0F0F0F),
            scrimColor = Color.Black.copy(alpha = 0.6f)
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Icon(Icons.Default.FindReplace, contentDescription = null, tint = Color(0xFF1E88E5), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Phát hiện tệp trùng lặp", color = Color(0xFFE8E8E8), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Column(Modifier.fillMaxWidth()) {
                    // ═══ GIAI ĐOẠN HIỆN TẠI ═══
                    val stage = viewModel.scanDuplicatesStage
                    val stageColor = when {
                        stage.contains("Thu thập") || stage.contains("nhận") || stage.contains("WebDAV") -> Color(0xFF1E88E5) // Xanh dương
                        stage.contains("Phân tích") -> Color(0xFFF57C00) // Cam
                        stage.contains("Hash") || stage.contains("Xác minh") -> Color(0xFF7B1FA2) // Tím
                        stage.contains("Hoàn tất") -> Color(0xFF2E7D32) // Xanh lá
                        else -> Color(0xFF616161) // Xám
                    }
                    Surface(
                        color = stageColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when {
                                    stage.contains("Hoàn tất") -> Icons.Default.CheckCircle
                                    stage.contains("Hash") || stage.contains("Xác minh") -> Icons.Default.Fingerprint
                                    else -> Icons.Default.Radar
                                },
                                contentDescription = null, tint = stageColor, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stage, color = stageColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ═══ THƯ MỤC ĐANG QUÉT ═══
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF5C6BC0), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Thư mục:", fontSize = 11.sp, color = Color.Gray)
                    }
                    Text(
                        text = viewModel.scanDuplicatesCurrentFolderUrl.ifEmpty { "..." },
                        color = Color(0xFF5C6BC0), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // ═══ FILE ĐANG XỬ LÝ ═══
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color(0xFFEF6C00), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Đang xử lý:", fontSize = 11.sp, color = Color.Gray)
                    }
                    Text(
                        text = viewModel.scanDuplicatesCurrentItemName.ifEmpty { "..." },
                        color = Color(0xFFEF6C00), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // ═══ PROGRESS BAR CHÍNH XÁC (2 THANH) ═══
                    val progressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = viewModel.scanDuplicatesPercent,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "totalProgress"
                    )
                    val stageProgressValue by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = viewModel.scanDuplicatesCurrentStagePercent,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "stageProgress"
                    )
                    
                    Column(Modifier.fillMaxWidth()) {
                        // Thanh 1: TỔNG QUÁT (Bao trùm toàn bộ tiến trình lớn)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tổng thể", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(60.dp))
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { progressValue.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = Color(0xFF4CAF50),
                                trackColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${(progressValue * 100).toInt()}%",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = stageColor,
                                modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Thanh 2: HIỆN TẠI (Theo từng giai đoạn)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Giai đoạn", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(60.dp))
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { stageProgressValue.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = Color(0xFF81C784),
                                trackColor = Color(0xFF81C784).copy(alpha = 0.15f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${(stageProgressValue * 100).toInt()}%",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = stageColor.copy(alpha = 0.8f),
                                modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ═══ STAGE LABEL + MÔ TẢ ═══
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Bước ${viewModel.scanDuplicatesStageNumber}/${viewModel.scanDuplicatesTotalStages}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = stageColor
                        )
                        Text(
                            "${(stageProgressValue * 100).toInt()}% giai đoạn",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                    if (viewModel.scanDuplicatesStageDescription.isNotEmpty()) {
                        Text(
                            viewModel.scanDuplicatesStageDescription,
                            fontSize = 10.sp,
                            color = Color.Gray.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // ═══ THỜI GIAN DỰ KIẾN ═══
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val elapsed = viewModel.scanDuplicatesElapsedTime
                        val etr = viewModel.scanDuplicatesEstimatedTimeRemaining
                        
                        fun formatTime(ms: Long): String {
                            if (ms < 0) return "--:--"
                            val totalSec = ms / 1000
                            val m = totalSec / 60
                            val s = totalSec % 60
                            return String.format(java.util.Locale.US, "%02d:%02d", m, s)
                        }

                        Text("Thời gian chạy: ${formatTime(elapsed)}", fontSize = 11.sp, color = Color.Gray)
                        Text(if (etr >= 0) "ƭc tính còn: ${formatTime(etr)}" else "Đang tính toán...", fontSize = 11.sp, color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)
                    }

                    // ═══ THỐNG KÊ RÕ RÀNG ═══
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${viewModel.scanDuplicatesTotalScanned}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                            Text("Tổng tệp", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${viewModel.scanDuplicatesFound}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                            Text("Trùng lặp", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
                // ── ACTION ROW: Tam dung / Huy / Thu nho ──
                Spacer(Modifier.height(12.dp))
                if (viewModel.isWorkerRunning) {
                    val isPaused by DuplicateProgressState.isPaused.collectAsState()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.cancelDuplicateScan(context) },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373))
                        ) { Text("Huỷ", color = Color(0xFFE57373), fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = { viewModel.togglePauseDuplicateScan() },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6))
                        ) { Text(if (isPaused) "Tiếp tục" else "Tạm dừng", color = Color(0xFF64B5F6), fontWeight = FontWeight.SemiBold) }
                        Button(
                            onClick = { viewModel.isScanningDuplicates = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) { Text("Thu nhỏ", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                } else {
                    Button(
                        onClick = { viewModel.isScanningDuplicates = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("Đóng", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
// Hộp thoại Hiển thị danh sách File Trùng Lặp
    if (viewModel.isShowingDuplicates) {
        AlertDialog(
            // FIX YEU CAU NGUOI DUNG: Dialog ket qua KHONG DONG khi click ngoai hoac
            // an app — chi cho dong khi user da xu ly het toan bo file trung lap
            // (duplicateFilesList.isEmpty()). Tranh user vo tinh dong roi phai quet lai.
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            onDismissRequest = { /* khong dong */ },
            title = {
                Column {
                    Text("Tệp trùng lặp", style = MaterialTheme.typography.titleMedium, color = Color.Red)
                    // BỔ SUNG: Hiển thị tổng số file rác phát hiện được nếu danh sách không trống
                    if (viewModel.duplicateFilesList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Phát hiện ${viewModel.duplicateFilesList.size} tệp trùng lặp",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            text = {
                if (viewModel.duplicateFilesList.isEmpty()) {
                    Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = Color.Green)
                } else {
                    // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                    // SỬA LỖI: Nhóm theo Hash/Fingerprint thay vì chỉ theo Size để đảm bảo tuyệt đối file có nội dung giống nhau mới nằm chung nhóm
                    val groupedDuplicates = remember(viewModel.duplicateFilesList) {
                        viewModel.duplicateFilesList.groupBy { it.contentLength }.values.filter { it.size >= 2 }.toList()
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
                                viewModel.selectedDuplicates.clear()
                                groupedDuplicates.forEach { group ->
                                    // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                    // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                    val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                    viewModel.selectedDuplicates.addAll(filesToDelete)
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF2196F3))
                            Spacer(Modifier.width(4.dp))
                            Text("Chọn thông minh", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                        }

                        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                            items(items = filteredGroups, key = { it.first().contentLength }) { group ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
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
                                                val isSelected = viewModel.selectedDuplicates.contains(dupFile)
                                                val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                                val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(dupFile.name)
                                                val auth = remember { okhttp3.Credentials.basic(viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) }

                                                Box(
                                                    modifier = Modifier
                                                        .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) Color.Red.copy(alpha = 0.2f) else Color.Black)
                                                        .clickable {
                                                            if (isSelected) viewModel.selectedDuplicates.remove(dupFile)
                                                            else viewModel.selectedDuplicates.add(dupFile)
                                                        }
                                                ) {
                                                    // 1. Lớp Ảnh Nền (TỐI ƯU HÓA DB CACHE MỚI CHO TẤT CẢ MEDIA)
                                                    if (isImage || isVideo) {
                                                        Box(modifier = Modifier.fillMaxSize()) {
                                                            WebDavCachedThumbnail(url = dupFile.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize())
                                                        }
                                                    } else {
                                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                                    }

                                                    // 2. Lớp phủ đỏ mờ nếu đang được tick chọn xóa
                                                    if (isSelected) {
                                                        Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.4f)))
                                                    }

                                                    // 3. Checkbox nằm góc trên phải
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = {
                                                            if (it) viewModel.selectedDuplicates.add(dupFile)
                                                            else viewModel.selectedDuplicates.remove(dupFile)
                                                        },
                                                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                        colors = CheckboxDefaults.colors(checkedColor = Color.Red, uncheckedColor = Color.White)
                                                    )

                                                    // 4. Tên file + thư mục cha đè ở dưới cùng (Để phân biệt các file)
                                                    Column(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .fillMaxWidth()
                                                            .background(Color.Black.copy(alpha = 0.75f))
                                                            .padding(horizontal = 4.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(
                                                            text = dupFile.name,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                            lineHeight = 10.sp
                                                        )
                                                        val parentFolder = dupFile.path.substringBeforeLast("/").substringAfterLast("/")
                                                        Text(
                                                            text = "📁 $parentFolder",
                                                            fontSize = 7.sp,
                                                            color = Color.Gray,
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
                    if (viewModel.selectedDuplicates.isNotEmpty()) {
                        TextButton(onClick = { viewModel.deleteSelectedDuplicates() }) {
                            Text("Xóa (${viewModel.selectedDuplicates.size}) mục", color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                    // FIX YEU CAU: chi cho dong khi user da xu ly het file trung lap.
                    // Khi list trong: hien "Hoan tat" mau xanh va dong dialog.
                    if (viewModel.duplicateFilesList.isEmpty()) {
                        TextButton(onClick = {
                            viewModel.isShowingDuplicates = false
                            viewModel.selectedDuplicates.clear()
                        }) { Text("Hoàn tất", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold) }
                    } else {
                        // Hint cho user biet phai xu ly het truoc khi dong duoc
                        Text(
                            "Còn ${viewModel.duplicateFilesList.size} tệp — tick chọn & xóa để đóng",
                            fontSize = 11.sp,
                            color = Color(0xFFFFA726),
                            modifier = Modifier.padding(horizontal = 8.dp).align(Alignment.CenterVertically)
                        )
                    }
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

            // ═══ THẺ DASHBOARD QUÉT NGẦM — HIỂN THỊ KHI WORKER ĐANG CHẠY, DIALOG ĐÃ ĐÓNG ═══
            if (viewModel.isWorkerRunning && !viewModel.isScanningDuplicates) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { viewModel.isScanningDuplicates = true }, // Nhấn → mở lại Dialog
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF311B92).copy(alpha = 0.92f) // Gradient tím đậm
                    ),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        // Header: Icon quét + Giai đoạn hiện tại
                        // Header: Icon quét + Giai đoạn hiện tại (Với % Tổng)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Radar,
                                    contentDescription = null,
                                    tint = Color(0xFFB388FF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Bước ${viewModel.scanDuplicatesStageNumber}/${viewModel.scanDuplicatesTotalStages}: ${viewModel.scanDuplicatesStage}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                "${(viewModel.scanDuplicatesPercent * 100).toInt()}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF4CAF50)
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // Animated progress cho mini card
                        val miniTotalProgress by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = viewModel.scanDuplicatesPercent.coerceIn(0f, 1f),
                            animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                            label = "miniTotal"
                        )
                        val miniStageProgress by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = viewModel.scanDuplicatesCurrentStagePercent.coerceIn(0f, 1f),
                            animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                            label = "miniStage"
                        )

                        // Progress bar mini (Tổng quát)
                        LinearProgressIndicator(
                            progress = { miniTotalProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF4CAF50),
                            trackColor = Color.White.copy(alpha = 0.15f)
                        )
                        
                        Spacer(Modifier.height(2.dp))

                        // Progress bar mini (Giai đoạn hiện tại)
                        LinearProgressIndicator(
                            progress = { miniStageProgress },
                            modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                            color = Color(0xFF81C784), // Màu xanh lá nhạt hơn
                            trackColor = Color.Transparent
                        )

                        Spacer(Modifier.height(6.dp))

                        // Dòng 2: File đang xử lý + thống kê
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                viewModel.scanDuplicatesCurrentItemName.ifEmpty { "..." },
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${viewModel.scanDuplicatesTotalScanned} file",
                                fontSize = 10.sp,
                                color = Color(0xFFB388FF),
                                fontWeight = FontWeight.Medium
                            )
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Đóng")
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
                                        val cur = prefs.getStringSet("viewed_files", emptySet())?.toMutableSet() ?: mutableSetOf()
                                        displayedFiles.filter { !it.isDirectory }.forEach { cur.add(it.path) }
                                        prefs.edit().putStringSet("viewed_files", cur).apply()
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
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

                    // Nút Sắp xếp file — hien ben canh nut "Chon file" de user de tim
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
    val auth = remember { Credentials.basic(viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) }

    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf(file.name) }

    // Tracking file "moi/chua xem" — luu set duong dan da xem vao SharedPreferences.
    // Khi user click vao file de mo lan dau, set them path va red dot bien mat.
    val viewedPrefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
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
                        val current = viewedPrefs.getStringSet("viewed_files", emptySet())?.toMutableSet() ?: mutableSetOf()
                        current.add(file.path)
                        viewedPrefs.edit().putStringSet("viewed_files", current).apply()
                        isNewFile = false
                    }
                    onClick()
                },
                onLongClick = {
                    // Long-press LUON mo menu cho ca file va folder. Selection mode
                    // entry duoc thuc hien qua nut "Chon file" o toolbar.
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
            // Chỉ hiện nút Khôi phục nếu đang đứng trong Thùng rác
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
                        openExternalVideoPlayer(
                            context = context,
                            url = file.path,
                            user = viewModel.webDavManager.currentUser,
                            pass = viewModel.webDavManager.currentPass,
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

        // === KHUNG HIỂN THỊ CHÍNH — SAMSUNG MY FILES STYLE ===
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (file.isDirectory) Modifier.height(72.dp) else Modifier.aspectRatio(1f))
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        file.isDirectory -> Color.Transparent
                        isMedia -> Color(0xFF212121)
                        else -> Color(0xFFF0F0F0)
                    }
                )
        ) {
            if (isMedia) {
                // MEDIA: Thumbnail edge-to-edge, sạch sẽ
                WebDavCachedThumbnail(url = file.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize())

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
                // THƯ MỤC: Icon folder lớn, canh giữa
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(65.dp)
                )
            } else {
                // FILE THƯỜNG: Icon cơ bản, canh giữa
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = Color(0xFF78909C),
                    modifier = Modifier.size(40.dp).align(Alignment.Center)
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

                // Extension góc dưới phải
                Text(
                    text = ext,
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(extColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )

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

        // TÊN THƯ MỤC — chỉ hiện cho thư mục
        if (file.isDirectory) {
            Text(
                text = file.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
// --- THUMBNAIL TỐI ƯU HOÁ CHO TẤT CẢ FILE MEDIA: LƯU VÀO DATABASE VĨNH VIỄN ---
// FIX BUG #10: Giới hạn số thumbnail load đồng thời — tránh OOM + NAS WebDAV 504 CPU Collapse
private val thumbnailSemaphore = kotlinx.coroutines.sync.Semaphore(8)

private val mediaThumbClient by lazy {
    NasApplication.instance.sharedHttpClient.newBuilder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 20; maxRequestsPerHost = 4 })
        .build()
}

@Composable
fun WebDavCachedThumbnail(url: String, auth: String, isVideo: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var localThumbPath by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // Cờ dự phòng: Khi NAS lỗi (hoặc định dạng dị), tự động dùng Coil tải ảnh gốc thu nhỏ
    var useOriginalFallback by remember { mutableStateOf(false) }

    val thumbnailDao = remember { NasApplication.instance.database.thumbnailDao() }
    val fingerprintDao = remember { NasApplication.instance.database.fingerprintDao() }

    LaunchedEffect(url) {
        // TỐI ƯU: Bypass (Bỏ qua) NAS API đối với các định dạng ảnh dễ làm server lỗi (PNG alpha, HEIC Apple)
        val ext = url.substringAfterLast('.', "").substringBefore("?").lowercase()
        val isProblematicForNas = ext in listOf("png", "heic", "heif", "gif", "bmp")

        if (!isVideo && isProblematicForNas) {
            useOriginalFallback = true
            // Xóa cache rác (nếu trước đó NAS đã lỡ lưu cái Icon Play lỗi vào db)
            withContext(Dispatchers.IO) {
                try { thumbnailDao.deleteThumbnail(url) } catch (_: Exception) {}
            }
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                thumbnailSemaphore.withPermit {
                    var attempts = 0
                    val maxAttempts = 1
                    while (attempts < maxAttempts) {
                        attempts++
                        try {
                            // 1. Kiểm tra nhanh DB
                            val cached = thumbnailDao.getThumbnail(url)
                            if (cached != null) {
                                val file = File(cached.localFilePath)
                                if (file.exists() && file.length() > 0) {
                                    localThumbPath = file.absolutePath
                                    return@withPermit
                                } else {
                                    thumbnailDao.deleteThumbnail(url)
                                }
                            }

                            // 2. Kiểm tra File nháp
                            val safeHash = Integer.toHexString(url.hashCode())
                            val thumbDir = context.getDir("persistent_thumbnails", android.content.Context.MODE_PRIVATE)
                            val thumbFile = File(thumbDir, "thumb_$safeHash.jpg")

                            if (thumbFile.exists() && thumbFile.length() > 0) {
                                localThumbPath = thumbFile.absolutePath
                                thumbnailDao.saveThumbnail(com.nas.naswebdav.ThumbnailCache(url, thumbFile.absolutePath))
                                return@withPermit
                            }

                            // 3. Gọi API NAS (/api/thumb)
                            val parsedUrl = java.net.URL(url)
                            val nasHost = parsedUrl.host
                            val webdavPath = parsedUrl.path ?: url.substringAfter(nasHost ?: "", "")
                            val apiThumbUrl = "${url.toApiBaseUrl()}/api/thumb?path=${java.net.URLEncoder.encode(webdavPath, "UTF-8")}"

                            val apiRequest = okhttp3.Request.Builder()
                                .url(apiThumbUrl)
                                .header("Authorization", auth)
                                .build()

                            mediaThumbClient.newCall(apiRequest).execute().use { apiResponse ->
                                val contentLength = apiResponse.header("Content-Length")?.toLongOrNull() ?: 0L

                                if (apiResponse.isSuccessful && apiResponse.body != null) {
                                    // CHẶN BỘ LỌC RÁC: Nếu NAS trả về tệp < 2KB thì 99% đó là Icon Play báo lỗi, ta từ chối!
                                    if (!isVideo && contentLength in 1L..2000L) {
                                        throw Exception("NAS trả về Icon báo lỗi thay vì Thumbnail thật")
                                    }

                                    // FIX: thay !! bang null check phong cao bang isSuccessful=true voi body rong
                                    val stream = apiResponse.body?.byteStream() ?: throw Exception("Phản hồi rỗng từ NAS")
                                    stream.use { input ->
                                        java.io.FileOutputStream(thumbFile).use { out -> input.copyTo(out) }
                                        if (thumbFile.length() > 0) {
                                            localThumbPath = thumbFile.absolutePath
                                            thumbnailDao.saveThumbnail(com.nas.naswebdav.ThumbnailCache(url, thumbFile.absolutePath))
                                            return@withPermit
                                        }
                                    }
                                } else {
                                    throw Exception("NAS API từ chối tạo thumbnail")
                                }
                            }
                        } catch (e: Exception) {
                            if (!isVideo) {
                                useOriginalFallback = true
                            } else {
                                isError = true
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isVideo) useOriginalFallback = true else isError = true
            }
        }
    }

    // ═══ LOGIC RENDER UI ═══
    if (localThumbPath != null) {
        AsyncImage(
            model = coil.request.ImageRequest.Builder(LocalContext.current).data(File(localThumbPath!!)).crossfade(true).build(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else if (useOriginalFallback) {
        // CỨU CHỮA KHI NAS API CHẾT: Ép Coil tải trực tiếp link WebDAV (size 300x300 để giải cứu RAM)
        AsyncImage(
            model = coil.request.ImageRequest.Builder(LocalContext.current)
                .data(url)
                .addHeader("Authorization", auth)
                .size(300, 300)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (isError) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!isVideo) {
                        Icon(Icons.Default.Image, null, tint = Color.LightGray, modifier = Modifier.size(32.dp))
                    }
                    val ext = url.substringAfterLast(".", "").substringBefore("?").uppercase()
                    if (ext.isNotEmpty() && ext.length <= 5) {
                        Text(text = ".$ext", color = Color(0xFF90CAF9), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                CircularProgressIndicator(color = Color(0xFF2196F3), modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}
// --- HÀM HELPER HỖ TRỢ MỞ VIDEO BẰNG EXTERNAL PLAYERS (VLC, MX PLAYER) ---
// KIẾN TRÚC MỚI: Dùng Local HTTP Proxy thay vì nhúng auth vào URL
// → VLC kết nối tới localhost (không cần auth) → Proxy chuyển tiếp tới NAS với header chuẩn
fun openExternalVideoPlayer(
    context: android.content.Context,
    url: String,
    user: String,
    pass: String,
    onError: () -> Unit
) {
    try {
        // 1. Khởi động proxy cục bộ trên localhost — VLC kết nối tới đây
        val proxy = com.nas.naswebdav.LocalVideoProxy(user, pass)
        val localUrl = proxy.start(url)

        // 2. Mở Intent tới VLC/MX Player với URL localhost (không cần xác thực)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(localUrl), "video/*")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = android.content.Intent.createChooser(intent, "Chọn trình phát video (VLC, MX Player...)")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        onError()
    }
}

// LỚP PHỤ TRỢ: Bộ nhớ Lịch sử Tìm Kiếm (TÍNH NĂNG 3.E)
data class SearchHistory(val query: String, val timestamp: Long)

class SearchHistoryManager(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)
    private val maxHistorySize = 15
    
    fun saveQuery(query: String) {
        if (query.isBlank()) return
        val history = getHistory().filter { it.query != query }
        val newHistory = (listOf(SearchHistory(query, System.currentTimeMillis())) + history).take(maxHistorySize)
        val array = org.json.JSONArray()
        newHistory.forEach { 
            val obj = org.json.JSONObject()
            obj.put("query", it.query)
            obj.put("timestamp", it.timestamp)
            array.put(obj)
        }
        prefs.edit().putString("history", array.toString()).apply()
    }
    
    fun getHistory(): List<SearchHistory> {
        val jsonStr = prefs.getString("history", "[]") ?: "[]"
        return try {
            val list = mutableListOf<SearchHistory>()
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(SearchHistory(obj.getString("query"), obj.getLong("timestamp")))
            }
            list
        } catch(e: Exception) {
            // BUG FIX P1#9: Log lỗi thay vì silent fail → mất data
            android.util.Log.e("SearchHistory", "Không đọc được lịch sử tìm kiếm: ${e.message}")
            emptyList()
        }
    }
}
