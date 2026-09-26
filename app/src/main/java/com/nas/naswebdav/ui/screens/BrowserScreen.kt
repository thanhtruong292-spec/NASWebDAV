@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.browser.FileBrowserViewModel
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.nas.naswebdav.ui.components.NasEmptyState
import com.nas.naswebdav.ui.layout.adaptiveGridColumns
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.nas.naswebdav.R
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.core.content.edit
import androidx.core.net.toUri

private const val VIEWED_FILES_LIMIT = 5000

/** Giữ tương thích caller cũ — delegate sang PreferencesRepository (reactive). */
internal fun markBrowserFilesViewed(
    prefs: android.content.SharedPreferences,
    paths: Collection<String>
) {
    com.nas.naswebdav.utils.PreferencesRepository
        .get(com.nas.naswebdav.NasApplication.instance.applicationContext)
        .markViewed(paths)
}

internal fun markBrowserFilesViewed(
    repo: com.nas.naswebdav.utils.PreferencesRepository,
    paths: Collection<String>
) = repo.markViewed(paths)

// --- VIEW MODE ENUM ---
// TÍNH NĂNG MỚI: Chế độ hiển thị file (giống Windows Explorer)
// ICON: Lưới icon lớn (mặc định cũ)
// LIST: Danh sách compact 48dp
// DETAIL: Danh sách có cột (icon, tên, dung lượng, ngày sửa)
private enum class BrowserViewMode { ICON, LIST, DETAIL }

// --- BROWSER SCREEN ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    onVideo: (String) -> Unit,
    onImage: (String) -> Unit,
    onLogout: () -> Unit,
    onBackToMenu: () -> Unit // Thêm tham số này
) {
    // ═══ PHASE 7c.3 — Group 3: FileBrowserVM hook at root composable ═══
    // BrowserScreen owns file-list state. Reads via facade delegation → FileBrowserVM SSoT.
    val fileBrowserVM = LocalFileBrowserVM.current
    val smartToolsVM = LocalSmartToolsVM.current
    val autoBackupVM = LocalAutoBackupVM.current
    val authVM = LocalAuthSessionVM.current
    val sysMonitorVM = LocalSystemMonitorVM.current
    val globalUiVM  = LocalGlobalUiVM.current
    // Trạng thái thanh tìm kiếm
    var isSearching by remember { mutableStateOf(false) }
    var wasInSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val historyManager = remember { SearchHistoryManager(context) }
    val searchHistory by remember(isSearching, searchQuery) { 
        mutableStateOf(if (isSearching && searchQuery.isEmpty()) historyManager.getHistory() else emptyList())
    }

    // Bộ lọc định dạng file thông minh: ALL, IMAGE, VIDEO, DOC, ARCHIVE
    var selectedCategory by remember { mutableStateOf("ALL") }

    // TÍNH NĂNG 7.L: Trạng thái của chế độ Multi-Selection
    var selectionMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { androidx.compose.runtime.mutableStateListOf<NasFile>() }
    // Tick increment khi can refresh red-dot "newFile" indicator tu SharedPrefs.
    // VD: nhan "Chon tat ca" -> mark all viewed -> increment tick -> moi
    // FileItemGridCell remember key bi invalidated -> doc lai prefs.
    var viewedRefreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(fileBrowserVM.currentUrl) { 
        viewedRefreshTick++
        selectedCategory = "ALL"
    }

    // SORT — reactive từ FileBrowserViewModel (survive rotation).
    // Values: "name_asc" | "name_desc" | "date_desc" | "date_asc" | "size_desc" | "size_asc"
    val sortMode by fileBrowserVM.fileSort.collectAsStateWithLifecycle()
    var showSortMenu by rememberSaveable { mutableStateOf(false) }

    // View mode (ICON / LIST / DETAIL) — persisted, survive rotation
    val viewModeName by fileBrowserVM.viewModeName.collectAsStateWithLifecycle()
    var viewMode = runCatching { BrowserViewMode.valueOf(viewModeName) }
        .getOrDefault(BrowserViewMode.ICON)

    // TÍNH NĂNG 7.M: Trạng thái Text Preview (saveable — survive rotation)
    var showTextPreviewDialog by rememberSaveable { mutableStateOf(false) }
    var textPreviewName by rememberSaveable { mutableStateOf("") }

    // TÍNH NĂNG: Trạng thái Folder Picker cho Copy/Move (saveable — survive rotation)
    var showFolderPickerDialog by rememberSaveable { mutableStateOf(false) }
    var pendingBatchOperation by rememberSaveable { mutableStateOf("") } // "COPY" hoặc "MOVE"


    LaunchedEffect(Unit) {
        smartToolsVM.autoCleanEnabled =
            com.nas.naswebdav.utils.PreferencesRepository.get(context).autoCleanEnabled.value
    }

    // Xóa chế độ chọn khi đổi thư mục
    LaunchedEffect(fileBrowserVM.currentUrl) {
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
        } else if (selectedCategory != "ALL") {
            selectedCategory = "ALL"
        } else if (isSearching || wasInSearchMode) {
            // A search Back press only closes search; it must never navigate
            // out of BrowserScreen to the main menu.
            isSearching = false
            wasInSearchMode = false
            searchQuery = ""
            fileBrowserVM.clearSearch()
        } else {
            // Trong mọi chế độ (Bình thường hay isSpecialMode), thử lùi cấu trúc cây thư mục trước
            // Nếu urlStack cạn (nghĩa là đã về gốc của chế độ đó), thì mới thoát ra Menu Chính
            if (!fileBrowserVM.goBack()) {
                onBackToMenu()
            }
        }
    }

    // TÍNH NĂNG 7.M: Màn hình đọc lướt File Văn Bản Code nhanh chóng
    if (showTextPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showTextPreviewDialog = false; fileBrowserVM.textPreviewContent = null },
            containerColor = DarkCard,
            shape = RoundedCornerShape(16.dp),
            title = { Text(textPreviewName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary) },
            text = {
                if (fileBrowserVM.isLoading && fileBrowserVM.textPreviewContent == null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(10.dp), color = AccentCyan)
                    }
                } else if (!fileBrowserVM.textPreviewContent.isNullOrBlank()) {
                    SelectionContainer {
                        Text(
                            text = fileBrowserVM.textPreviewContent!!,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                } else {
                    Text(stringResource(R.string.error_cannot_load_file), color = AccentRed)
                }
            },
            confirmButton = {
                Button(onClick = { showTextPreviewDialog = false; fileBrowserVM.textPreviewContent = null }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = DarkSurface), shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.action_close), fontWeight = FontWeight.SemiBold)
                }
            },
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    val animatedProgress by animateFloatAsState(targetValue = if (fileBrowserVM.totalImagesInFolder > 0) fileBrowserVM.loadedImagesCount.toFloat() / fileBrowserVM.totalImagesInFolder else 0f, animationSpec = tween(500), label = "Progress")
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
            startingUrl = WebDavManager.currentBaseUrl,
            onDismiss = {
                showFolderPickerDialog = false
                pendingBatchOperation = ""
            },
            onFolderSelected = { destUrl ->
                showFolderPickerDialog = false
                val filesToProcess = selectedFiles.toList()
                when (pendingBatchOperation) {
                    "COPY" -> fileBrowserVM.batchCopyFiles(context, filesToProcess, destUrl)
                    "MOVE" -> fileBrowserVM.batchMoveFiles(context, filesToProcess, destUrl)
                }
                pendingBatchOperation = ""
                selectedFiles.clear()
                selectionMode = false
            }
        )
    }

    var showMultiDeleteDialog by remember { mutableStateOf(false) }
    if (showMultiDeleteDialog) {
        val isTrash = fileBrowserVM.isSpecialMode && fileBrowserVM.specialTitle == "Thùng rác"
        com.nas.naswebdav.ui.dialogs.MultiDeleteDialog(
            selectedCount = selectedFiles.size,
            isTrash = isTrash,
            onConfirm = {
                showMultiDeleteDialog = false
                fileBrowserVM.deleteMultipleFiles(context, selectedFiles.toList())
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
            icon = { Icon(Icons.Default.Bolt, null, tint = AccentOrange, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.dialog_duplicate_config), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dialog_duplicate_config_desc), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    
                    // Option 1: Lightning Mode
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { isLightningMode = !isLightningMode }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isLightningMode,
                            onCheckedChange = { isLightningMode = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentOrange)
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(stringResource(R.string.dialog_duplicate_fast_mode), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isLightningMode) AccentOrange else MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.dialog_duplicate_fast_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 14.sp)
                        }
                    }

                    // Option 2: Force Restart
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { isForceRestartDuplicate = !isForceRestartDuplicate }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isForceRestartDuplicate, onCheckedChange = { isForceRestartDuplicate = it })
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(stringResource(R.string.dialog_duplicate_rescan), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.dialog_duplicate_rescan_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))

                    // Option 3: Tự động chạy ngầm (Auto Clean)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.dialog_duplicate_auto_clean), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                            Text(stringResource(R.string.dialog_duplicate_auto_clean_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 14.sp)
                        }
                        Switch(
                            checked = smartToolsVM.autoCleanEnabled,
                            onCheckedChange = { smartToolsVM.toggleAutoClean(context, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentBlue, checkedTrackColor = AccentBlue.copy(alpha = 0.5f))
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDuplicateConfigDialog = false
                        smartToolsVM.startBackgroundDuplicateScan(context, forceRestart = isForceRestartDuplicate, lightningMode = isLightningMode)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text(stringResource(R.string.dialog_start_scan))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateConfigDialog = false }) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.outline) }
            }
        )
    }

// Trạng thái hiển thị menu 3 chấm trên TopAppBar
    var showMoreMenu by remember { mutableStateOf(false) }

    // PHASE 6.C: Tự động mở màn hình dọn rác nếu được gọi từ Notification
    LaunchedEffect(smartToolsVM.shouldAutoOpenDuplicates) {
        if (smartToolsVM.shouldAutoOpenDuplicates) {
            smartToolsVM.resetShouldAutoOpenDuplicates()
            smartToolsVM.loadDuplicateResultsFromCache(context)
        }
    }

    // Đã xóa UploadLauncher và SyncFolderLauncher theo yêu cầu (Chỉ giữ Auto Backup)
    if (showCreateFolderDialog) {
        com.nas.naswebdav.ui.dialogs.CreateFolderDialog(
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    // TÍNH NĂNG 5.I: Truyền context vào để nhét vô SQLite Queue nếu mất mạng
                    fileBrowserVM.createFolder(context, name)
                }
                showCreateFolderDialog = false
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    if (showOrganizeDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!isOrganizing) showOrganizeDialog = false },
            title = { Text(stringResource(R.string.dialog_organize_title)) },
            text = {
                Column {
                    if (isOrganizing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
                        Text(stringResource(R.string.dialog_organize_loading))
                    } else if (organizeResult != null) {
                        Text(organizeResult!!)
                    } else {
                        Text(stringResource(R.string.dialog_organize_confirm))
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
                                    val user = WebDavManager.currentUser
                                    val pass = WebDavManager.currentPass
                                    val urlStr = WebDavManager.currentBaseUrl
                                    
                                    val host = java.net.URL(urlStr).host ?: "127.0.0.1"
                                    val apiUrl = "${urlStr.toApiBaseUrl()}/api/tools/organize_legacy_videos"
                                    
                                    val request = okhttp3.Request.Builder()
                                        .url(apiUrl)
                                        .post(ByteArray(0).toRequestBody(null, 0, 0))
                                        .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
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
                                                    fileBrowserVM.refresh()
                                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                                    organizeResult = "Lỗi phản hồi: ${e.message}"
                                                }
                                            } else {
                                                organizeResult = "Lỗi NAS: ${response.code}"
                                            }
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        isOrganizing = false
                                        organizeResult = "Lỗi kết nối: ${e.message}"
                                    }
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.action_run_nas)) }
                } else if (organizeResult != null) {
                    TextButton(onClick = { showOrganizeDialog = false; organizeResult = null }) { Text(stringResource(R.string.action_close)) }
                }
            },
            dismissButton = {
                if (!isOrganizing && organizeResult == null) {
                    TextButton(onClick = { showOrganizeDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        )
    }
    val serverSearchResults by fileBrowserVM.searchResults.collectAsState()
    val isServerSearchActive by fileBrowserVM.isSearchActive.collectAsState()
    // Track when we enter search mode so first Back press only closes search,
    // not navigate away from BrowserScreen.
    LaunchedEffect(isSearching) {
        if (isSearching) wasInSearchMode = true
    }
    // Chi luu lich su khi search xong VA co ket qua — query 0 ket qua hoac
    // dang go do khong lam rac lich su. Bam vao file giu nguyen (luu ngay).
    LaunchedEffect(isServerSearchActive, serverSearchResults.size) {
        if (isSearching && searchQuery.isNotBlank() && !isServerSearchActive && serverSearchResults.isNotEmpty()) {
            historyManager.saveQuery(searchQuery)
        }
    }

    // Windows-Explorer-style search display:
    // - blank query → current folder listing
    // - typing → show global search results as they arrive
    // - never hide local matches while waiting for remote BFS
    val displayedFiles by remember {
        derivedStateOf {
            val filtered = if (searchQuery.isBlank()) {
                fileBrowserVM.fileList
            } else if (serverSearchResults.isNotEmpty()) {
                serverSearchResults
            } else {
                // Fallback: filter current folder while global search is still loading
                // or returned nothing yet.
                fileBrowserVM.fileList.filter { fileBrowserVM.matchesQuery(it.name, searchQuery) }
            }
            val categoryFiltered = when (selectedCategory) {
                "IMAGE" -> filtered.filter { com.nas.naswebdav.utils.MediaUtils.isImage(it.name) }
                "VIDEO" -> filtered.filter { com.nas.naswebdav.utils.MediaUtils.isVideo(it.name) }
                "DOC" -> filtered.filter {
                    val n = it.name.lowercase()
                    n.endsWith(".pdf") || n.endsWith(".doc") || n.endsWith(".docx") ||
                    n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".ppt") ||
                    n.endsWith(".pptx") || n.endsWith(".txt") || n.endsWith(".md")
                }
                "ARCHIVE" -> filtered.filter {
                    val n = it.name.lowercase()
                    n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") ||
                    n.endsWith(".tar") || n.endsWith(".gz")
                }
                else -> filtered
            }
            if (searchQuery.isNotBlank()) {
                // TÌM KIẾM: Sắp xếp theo điểm gần khớp nhất (Relevance Score) đưa kết quả sát nhất lên đầu
                val folders = categoryFiltered.filter { it.isDirectory }.sortedByDescending { fileBrowserVM.calculateRelevanceScore(it.name, searchQuery) }
                val files = categoryFiltered.filter { !it.isDirectory }.sortedByDescending { fileBrowserVM.calculateRelevanceScore(it.name, searchQuery) }
                folders + files
            } else {
                // SORT: thu muc luon o tren, sau do ap dung sort theo che do user chon
                val folders = categoryFiltered.filter { it.isDirectory }
                val files = categoryFiltered.filter { !it.isDirectory }
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
    }

    // TÍNH NĂNG MỚI: THANH TIẾN TRÌNH NỔI (FLOATING TRANSFER BAR) DÀNH CHO AUTO-BACKUP
    Scaffold(
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(
                visible = autoBackupVM.isAutoBackupRunning,
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f).padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { autoBackupVM.autoBackupProgress },
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
                                    text = "${(autoBackupVM.autoBackupProgress * 100).toInt()}%",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                text = "Nguồn: ${autoBackupVM.autoBackupSourcePath.substringBeforeLast("/", "").takeLast(15)}/${autoBackupVM.autoBackupCurrentFile}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Đích: ${autoBackupVM.autoBackupDestPath}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                            )
                        }
                        IconButton(onClick = { autoBackupVM.isAutoBackupRunning = false }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_hide), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        },
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = selectionMode,
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
            ) {
                Surface(
                    color = DarkCard,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isTrash = fileBrowserVM.isSpecialMode && fileBrowserVM.specialTitle == "Thùng rác"
                        val hasSelection = selectedFiles.isNotEmpty()

                        if (isTrash) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = hasSelection) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        fileBrowserVM.restoreMultipleFiles(context, selectedFiles.toList())
                                        selectionMode = false
                                        selectedFiles.clear()
                                    }
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Restore,
                                    contentDescription = stringResource(R.string.cd_restore),
                                    tint = if (hasSelection) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    stringResource(R.string.cd_restore),
                                    fontSize = 11.sp,
                                    color = if (hasSelection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = hasSelection) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        pendingBatchOperation = "COPY"
                                        showFolderPickerDialog = true
                                    }
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy),
                                    tint = if (hasSelection) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    stringResource(R.string.cd_copy),
                                    fontSize = 11.sp,
                                    color = if (hasSelection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = hasSelection) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        pendingBatchOperation = "MOVE"
                                        showFolderPickerDialog = true
                                    }
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.DriveFileMove,
                                    contentDescription = stringResource(R.string.cd_move),
                                    tint = if (hasSelection) AccentOrange else MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    stringResource(R.string.cd_move),
                                    fontSize = 11.sp,
                                    color = if (hasSelection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = hasSelection) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showMultiDeleteDialog = true
                                }
                                .padding(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = if (isTrash) "Xóa vĩnh viễn" else "Xóa",
                                tint = if (hasSelection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                            )
                            Text(
                                if (isTrash) "Xóa vĩnh viễn" else "Xóa",
                                fontSize = 11.sp,
                                color = if (hasSelection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
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
                        onValueChange = { value ->
                            searchQuery = value
                            if (value.isNotBlank()) {
                                fileBrowserVM.searchFiles(value)
                            } else {
                                fileBrowserVM.clearSearch()
                            }
                        },
                        placeholder = { Text(stringResource(R.string.label_search_placeholder), color = MaterialTheme.colorScheme.outline) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            // Khong luu o day — LaunchedEffect luu khi search
                            // xong VA co ket qua.
                            focusManager.clearFocus()
                            if (searchQuery.isNotBlank()) {
                                fileBrowserVM.searchFiles(searchQuery)
                            }
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
                    IconButton(onClick = { isSearching = false; searchQuery = ""; fileBrowserVM.clearSearch() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_close))
                    }
                },
                actions = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear))
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
                                        val paths = displayedFiles.filter { !it.isDirectory }.map { it.path }
                                        coroutineScope.launch(Dispatchers.IO) {
                                            fileBrowserVM.markFilesViewed(paths)
                                        }
                                        // Bump tick de moi FileItemGridCell remember key bi
                                        // invalidated -> doc lai prefs -> red dot bien mat.
                                        viewedRefreshTick++
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                                } else {
                                    selectedFiles.clear()
                                    selectionMode = false
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurface,
                                checkmarkColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(stringResource(R.string.label_selected_count_with_count, selectedFiles.size), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { selectionMode = false; selectedFiles.clear() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                    }
                },
                actions = {
                    val isTrash = fileBrowserVM.isSpecialMode && fileBrowserVM.specialTitle == "Thùng rác"
                    
                    if (isTrash) {
                        // NÚT KHÔI PHỤC HÀNG LOẠT (CHỈ TRONG THÙNG RÁC)
                        IconButton(
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    fileBrowserVM.restoreMultipleFiles(context, selectedFiles.toList())
                                    selectionMode = false
                                    selectedFiles.clear()
                                }
                            },
                            enabled = selectedFiles.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = stringResource(R.string.cd_restore),
                                tint = if (selectedFiles.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
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
                                contentDescription = stringResource(R.string.cd_copy),
                                tint = if (selectedFiles.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
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
                                contentDescription = stringResource(R.string.cd_move),
                                tint = if (selectedFiles.isNotEmpty()) AccentOrange else MaterialTheme.colorScheme.outline
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
                            tint = if (selectedFiles.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        } else {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (!fileBrowserVM.goBack()) onBackToMenu()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                title = {
                    val displayTitle = if (fileBrowserVM.isSpecialMode) fileBrowserVM.specialTitle
                    else {
                        val decodedUrl = try { java.net.URLDecoder.decode(fileBrowserVM.currentUrl, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { fileBrowserVM.currentUrl }
                        val baseUrl = WebDavManager.currentBaseUrl
                        val relativePath = if (decodedUrl.startsWith(baseUrl)) decodedUrl.removePrefix(baseUrl) else ""
                        val segments = relativePath.trim('/').split("/").filter { it.isNotEmpty() }
                        if (segments.isEmpty()) "Thư mục gốc" else segments.last()
                    }
                    Column {
                        Text(displayTitle, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    // Thiết kế Chip trạng thái kết nối
                    val rawStatus = authVM.connectionStatus
                        val displayStatus = if (rawStatus.contains("Cache", ignoreCase = true)) "Cache" else rawStatus

                        val chipIcon = when {
                            displayStatus.contains("Chờ", ignoreCase = true) -> Icons.Default.Help
                            displayStatus.contains("Lỗi", ignoreCase = true) || displayStatus.contains("Mất", ignoreCase = true) -> Icons.Default.Cancel
                            else -> Icons.Default.CheckCircle
                        }

                        val chipColor = when {
                            displayStatus.contains("Chờ", ignoreCase = true) -> MaterialTheme.colorScheme.primary // Xanh dương
                            displayStatus.contains("Lỗi", ignoreCase = true) || displayStatus.contains("Mất", ignoreCase = true) -> MaterialTheme.colorScheme.error // Đỏ
                            displayStatus.contains("Cache", ignoreCase = true) -> AccentOrange // Cam
                            else -> MaterialTheme.colorScheme.tertiary // Xanh lá
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
                                    text = displayStatus + if (sysMonitorVM.networkPingMs != null && sysMonitorVM.networkPingMs!! >= 0) " - ${sysMonitorVM.networkPingMs}ms" else "",
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
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                }
                // Đã ẩn nút Tải lên lẻ

                // Gom các nút còn lại vào Menu 3 chấm để giải phóng không gian màn hình
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        // Đã xóa nút Đồng bộ thư mục
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_find_duplicates)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, stringResource(R.string.menu_find_duplicates)) },
                            onClick = { showMoreMenu = false; showDuplicateConfigDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_create_folder)) },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.action_create_folder)) },
                            onClick = { showMoreMenu = false; showCreateFolderDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_organize_videos)) },
                            leadingIcon = { Icon(Icons.Default.SnippetFolder, stringResource(R.string.menu_organize_videos)) },
                            onClick = { showMoreMenu = false; showOrganizeDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_refresh)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) },
                            onClick = { showMoreMenu = false; fileBrowserVM.refresh() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_random_photo)) },
                            leadingIcon = { Icon(Icons.Default.Shuffle, stringResource(R.string.menu_random_photo)) },
                            onClick = {
                                showMoreMenu = false
                                val images = fileBrowserVM.fileList.filter { it.name.lowercase().run { endsWith(".jpg") || endsWith(".png") } }
                                images.randomOrNull()?.let { onImage(it.path) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_login), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.menu_login), tint = MaterialTheme.colorScheme.error) },
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
            if (!fileBrowserVM.isSpecialMode) {
                val baseUrl = WebDavManager.currentBaseUrl
                val relativePath = if (fileBrowserVM.currentUrl.startsWith(baseUrl))
                    fileBrowserVM.currentUrl.removePrefix(baseUrl) else ""
                val decodedPath = try { java.net.URLDecoder.decode(relativePath, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { relativePath }
                val segments = decodedPath.trim('/').split("/").filter { it.isNotEmpty() }
                
                val scrollState = androidx.compose.foundation.rememberScrollState()
                LaunchedEffect(segments) { scrollState.animateScrollTo(scrollState.maxValue) }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkCardHover.copy(alpha = 0.5f))
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
                            modifier = Modifier.clickable { fileBrowserVM.navigateToUrl("/") })
                        
                        segments.forEachIndexed { index, segment ->
                            Text(" / ", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
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
                                    fileBrowserVM.urlStack.clear()
                                    for (i in 0 until index + 1) {
                                        if (i == 0) fileBrowserVM.urlStack.push(baseUrl)
                                        else fileBrowserVM.urlStack.push(baseUrl + segments.take(i).joinToString("/") + "/")
                                    }
                                    fileBrowserVM.navigateToUrl(targetUrl)
                                } else Modifier
                            )
                        }
                    }
                    
                    // Phải: Thống kê động theo danh sách đang hiển thị (cả tìm kiếm và duyệt thư mục)
                    val targetList = displayedFiles
                    val folders = targetList.count { it.isDirectory }
                    val files = targetList.count { !it.isDirectory }
                    val statsText = buildList {
                        if (folders > 0) add("$folders thư mục")
                        if (files > 0) add("$files tệp")
                    }.joinToString(", ").ifEmpty { "Trống" }

                    Text(
                        text = statsText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )

                    // Nút Gộp Chuyển đổi chế độ hiển thị file (ICON -> LIST -> DETAIL -> ICON)
                    if (!selectionMode && fileBrowserVM.fileList.isNotEmpty()) {
                        val (currentIcon, nextMode, modeDesc) = when (viewMode) {
                            BrowserViewMode.ICON -> Triple(Icons.Default.GridView, BrowserViewMode.LIST, "Chế độ Icon (nhấn để đổi sang Danh sách)")
                            BrowserViewMode.LIST -> Triple(Icons.Default.ViewList, BrowserViewMode.DETAIL, "Chế độ Danh sách (nhấn để đổi sang Chi tiết)")
                            BrowserViewMode.DETAIL -> Triple(Icons.Default.TableRows, BrowserViewMode.ICON, "Chế độ Chi tiết (nhấn để đổi sang Icon)")
                        }
                        IconButton(
                            onClick = {
                                fileBrowserVM.setViewModeName(nextMode.name)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = currentIcon,
                                contentDescription = modeDesc,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Nút Sắp xếp file — hiện bên cạnh nút "Chọn file" để user dễ tìm
                    if (!selectionMode && fileBrowserVM.fileList.isNotEmpty()) {
                        Box {
                            IconButton(
                                onClick = { 
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showSortMenu = true 
                                },
                                modifier = Modifier
                                    .padding(start = 2.dp)
                                    .size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sort,
                                    contentDescription = stringResource(R.string.cd_sort),
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
                                                opt.icon, opt.label,
                                                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        trailingIcon = if (isActive) {
                                            { Icon(Icons.Default.Check, opt.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                        } else null,
                                        onClick = {
                                            fileBrowserVM.setFileSort(opt.key)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Nút kích hoạt chế độ chọn nhiều file
                    if (!selectionMode && fileBrowserVM.fileList.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectionMode = true
                            },
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = stringResource(R.string.cd_select_file),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            
            // Thanh tiến trình tải ảnh (chỉ hiện khi đang tải hàng loạt)
            if (animatedProgress > 0f && animatedProgress < 1f) LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)

            // Thanh chip lọc nhanh định dạng (Tất cả, Ảnh, Video, Tài liệu, Nén)
            if (!selectionMode && (fileBrowserVM.fileList.isNotEmpty() || selectedCategory != "ALL")) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "ALL" to "Tất cả",
                        "IMAGE" to "Ảnh",
                        "VIDEO" to "Video",
                        "DOC" to "Tài liệu",
                        "ARCHIVE" to "File nén"
                    ).forEach { (catKey, catLabel) ->
                        val isSelected = selectedCategory == catKey
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategory = if (isSelected && catKey != "ALL") "ALL" else catKey
                            },
                            label = { Text(catLabel, fontSize = 11.sp) },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
            }

            // TÍNH NĂNG MỚI: Trạng thái Kéo để làm mới (Pull-to-Refresh) CHUẨN ĐỒNG BỘ
            val pullToRefreshState = rememberPullToRefreshState()
            val view = androidx.compose.ui.platform.LocalView.current

            // 1. Kích hoạt tải dữ liệu khi bị vuốt (Refresh Trigger)
            if (pullToRefreshState.isRefreshing) {
                LaunchedEffect(pullToRefreshState.isRefreshing) {
                    // TÍNH NĂNG 3.F: Thiết lập Haptic Feedback phản hồi vật lý
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    fileBrowserVM.refresh()
                }
            }

            // 2. Chờ tải xong mới thu hồi Pull-to-Refresh UI bằng luồng Flow an toàn (Không khoá UI)
            LaunchedEffect(pullToRefreshState.isRefreshing) {
                if (pullToRefreshState.isRefreshing) {
                    androidx.compose.runtime.snapshotFlow { fileBrowserVM.isLoading }
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
                    item { Text(stringResource(R.string.label_recent_searches), color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(10.dp)) }
                    items(items = searchHistory, key = { it.query }) { history ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { searchQuery = history.query; fileBrowserVM.searchFiles(history.query); focusManager.clearFocus() }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 16.dp))
                            Text(history.query, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            } else {

            // SỬA LỖI: Sử dụng trực tiếp nestedScroll() sau khi đã import
            Box(Modifier.fillMaxSize().nestedScroll(pullToRefreshState.nestedScrollConnection)) {
                if (isSearching && searchQuery.isNotEmpty() && isServerSearchActive) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = DarkCard
                    )
                }
                
                // Grouping logic for Trash
                val isTrashMode = fileBrowserVM.isSpecialMode && fileBrowserVM.specialTitle == "Thùng rác"
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

                // Shared file click handler for all view modes
                val onFileClick: (NasFile) -> Unit = { file ->
                    if (selectionMode) {
                        if (selectedFiles.contains(file)) {
                            selectedFiles.remove(file)
                            if (selectedFiles.isEmpty()) selectionMode = false
                        } else {
                            selectedFiles.add(file)
                        }
                    } else {
                        if (isSearching && searchQuery.isNotBlank()) {
                            historyManager.saveQuery(searchQuery)
                        }
                        if (file.isDirectory) {
                            if (isSearching) {
                                isSearching = false
                                searchQuery = ""
                                fileBrowserVM.clearSearch()
                            }
                            fileBrowserVM.openFolder(file)
                        }
                        else if (com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)) onVideo(file.path)
                        else if (file.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }) onImage(file.path)
                        else if (file.name.lowercase().run { endsWith(".txt") || endsWith(".md") || endsWith(".py") || endsWith(".log") || endsWith(".json") || endsWith(".xml") || endsWith(".kt") || endsWith(".java") }) {
                            fileBrowserVM.fetchTextPreview(file.path)
                            textPreviewName = file.name
                            showTextPreviewDialog = true
                        }
                    }
                }
                val onFileLongClick: (NasFile) -> Unit = { file ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!selectionMode) {
                        selectionMode = true
                        selectedFiles.add(file)
                    } else {
                        if (selectedFiles.contains(file)) {
                            selectedFiles.remove(file)
                            if (selectedFiles.isEmpty()) selectionMode = false
                        } else {
                            selectedFiles.add(file)
                        }
                    }
                }

                // TÍNH NĂNG MỚI: Chuyển đổi layout theo chế độ hiển thị
                when (viewMode) {
                    BrowserViewMode.ICON -> {
                        // ICON MODE: Lưới icon thích ứng (3 cột điện thoại, 4 cột tablet, 5 cột máy tính)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(adaptiveGridColumns()),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(AppSpacing.XS),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
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
                                    BrowserScreenFileItemGridCell(
                                        file = file,
                                        fileBrowserVM = fileBrowserVM,
                                        selectionMode = selectionMode,
                                        viewedRefreshTick = viewedRefreshTick,
                                        isSelected = selectedFiles.contains(file),
                                        onLongClick = { onFileLongClick(file) },
                                        onClick = { onFileClick(file) },
                                        onVideo = onVideo,
                                        onDelete = {
                                            // BUG FIX: Sync the selection state with what
                                            // viewModel just did in its optimistic update.
                                            selectedFiles.remove(file)
                                            if (selectedFiles.isEmpty()) {
                                                selectionMode = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    BrowserViewMode.LIST -> {
                        // LIST MODE: Danh sách compact — thumbnail 32x32, tên + dung lượng
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            groupedFiles.forEach { (header, filesInGroup) ->
                                if (header.isNotEmpty()) {
                                    item(key = "header_$header") {
                                        Text(
                                            text = header,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                                items(
                                    items = filesInGroup,
                                    key = { it.path }
                                ) { file ->
                                    val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)
                                    val isImageFile = com.nas.naswebdav.utils.MediaUtils.isImage(file.name)
                                    val isMedia = isVideo || isImageFile
                                    val displaySize = com.nas.naswebdav.utils.FormatUtils.formatBytes(file.contentLength)
                                    val auth = WebDavManager.currentAuthState().authHeader

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .combinedClickable(
                                                onClick = { onFileClick(file) },
                                                onLongClick = { onFileLongClick(file) }
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Selection checkbox
                                        if (selectionMode) {
                                            Icon(
                                                imageVector = if (selectedFiles.contains(file)) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (selectedFiles.contains(file)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }

                                        // Small thumbnail / folder icon
                                        if (isMedia) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(DarkCard),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                var listThumbState by remember { mutableStateOf<ThumbState?>(null) }
                                                WebDavCachedThumbnail(
                                                    url = file.path,
                                                    auth = auth,
                                                    isVideo = isVideo,
                                                    modifier = Modifier.fillMaxSize(),
                                                    onStateChange = { listThumbState = it }
                                                )
                                                if (isVideo && listThumbState == ThumbState.SUCCESS) {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        } else if (file.isDirectory) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = AccentOrange,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        } else {
                                            val ext = file.name.substringAfterLast('.', "").uppercase()
                                            val extColor = when {
                                                isImageFile -> MaterialTheme.colorScheme.primary
                                                isVideo -> AccentOrange
                                                ext in listOf("ZIP", "RAR", "7Z", "TAR", "GZ") -> MaterialTheme.colorScheme.error
                                                ext in listOf("TXT", "MD", "LOG", "JSON", "XML", "PY", "KT") -> MaterialTheme.colorScheme.tertiary
                                                ext in listOf("PDF", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX") -> MaterialTheme.colorScheme.secondary
                                                ext in listOf("MP3", "WAV", "FLAC", "M4A") -> MaterialTheme.colorScheme.primary
                                                else -> TextTertiary
                                            }
                                            Icon(
                                                imageVector = Icons.Default.InsertDriveFile,
                                                contentDescription = null,
                                                tint = extColor,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }

                                        Spacer(Modifier.width(10.dp))

                                        // File name
                                        Text(
                                            text = file.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )

                                        // Size
                                        Text(
                                            text = displaySize,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    BrowserViewMode.DETAIL -> {
                        // DETAIL MODE: Danh sách có cột — icon, tên (trọng số), dung lượng, ngày sửa
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Column headers
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkCard)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.width(32.dp + 10.dp)) // Icon column
                                Text(
                                    "Tên",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Dung lượng",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                    modifier = Modifier.width(72.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Sửa đổi",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                    modifier = Modifier.width(90.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                groupedFiles.forEach { (header, filesInGroup) ->
                                    if (header.isNotEmpty()) {
                                        item(key = "header_$header") {
                                            Text(
                                                text = header,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                                            )
                                        }
                                    }
                                    items(
                                        items = filesInGroup,
                                        key = { it.path }
                                    ) { file ->
                                        val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)
                                        val isImageFile = com.nas.naswebdav.utils.MediaUtils.isImage(file.name)
                                        val displaySize = com.nas.naswebdav.utils.FormatUtils.formatBytes(file.contentLength)
                                        val displayDate = if (file.lastModified > 0L) {
                                            val fmt = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault())
                                            fmt.format(java.util.Date(file.lastModified))
                                        } else ""
                                        val auth = WebDavManager.currentAuthState().authHeader

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                                .combinedClickable(
                                                    onClick = { onFileClick(file) },
                                                    onLongClick = { onFileLongClick(file) }
                                                )
                                                .padding(horizontal = 12.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Selection checkbox
                                            if (selectionMode) {
                                                Icon(
                                                    imageVector = if (selectedFiles.contains(file)) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    tint = if (selectedFiles.contains(file)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }

                                            // Small icon
                                            if (file.isDirectory) {
                                                Icon(
                                                    imageVector = Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = AccentOrange,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            } else if (isVideo || isImageFile) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(DarkCard),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    var detailThumbState by remember { mutableStateOf<ThumbState?>(null) }
                                                    WebDavCachedThumbnail(
                                                        url = file.path,
                                                        auth = auth,
                                                        isVideo = isVideo,
                                                        modifier = Modifier.fillMaxSize(),
                                                        onStateChange = { detailThumbState = it }
                                                    )
                                                    if (isVideo && detailThumbState == ThumbState.SUCCESS) {
                                                        Icon(
                                                            Icons.Default.PlayArrow,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }

                                            Spacer(Modifier.width(10.dp))

                                            // File name
                                            Text(
                                                text = file.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )

                                            // Size
                                            Text(
                                                text = displaySize,
                                                maxLines = 1,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.width(72.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                                            )

                                            Spacer(Modifier.width(8.dp))

                                            // Date modified
                                            Text(
                                                text = displayDate,
                                                maxLines = 1,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.width(90.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // LOADING HIỆN ĐẠI NHẤT: LinearProgressIndicator đặt ngay dưới TopAppBar thay vì vòng tròn trắng cồng kềnh
                if (fileBrowserVM.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary, // Đồng bộ màu Xanh Ngọc
                        trackColor = Color.Transparent
                    )
                }
                // Hiển thị lỗi kết nối rõ ràng ở giữa màn hình
                val currentError = fileBrowserVM.errorMessage
                if (!currentError.isNullOrEmpty() && !fileBrowserVM.isLoading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.error_cannot_connect_nas), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text(currentError, color = MaterialTheme.colorScheme.outline, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { fileBrowserVM.refresh() }) { Text(stringResource(R.string.action_retry)) }
                    }
                } else if (!fileBrowserVM.isLoading && displayedFiles.isEmpty() && currentError.isNullOrEmpty()) {
                    androidx.compose.animation.Crossfade(
                        // 3 trang thai: dang tim (spinner) / xong-khong-ket-qua /
                        // loc category trong / thu muc trong. Ban cu hien "Khong
                        // tim thay" ngay ca khi BFS van dang chay -> nhap nhay.
                        targetState = if (isSearching && searchQuery.isNotEmpty() && isServerSearchActive) "loading"
                            else if (isSearching && searchQuery.isNotEmpty()) "noresult"
                            else if (selectedCategory != "ALL") "nocategory" else "empty",
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
                        label = "BrowserEmptyState"
                    ) { state ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state == "loading") {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("Đang tìm \"$searchQuery\"...", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                                }
                            } else {
                            val showSearchEmpty = state == "noresult"
                            NasEmptyState(
                                icon = if (showSearchEmpty) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                                title = when (state) {
                                    "noresult" -> "Không tìm thấy kết quả"
                                    "nocategory" -> "Không có tệp phù hợp"
                                    else -> stringResource(R.string.label_folder_empty)
                                },
                                description = when (state) {
                                    "noresult" -> "Không có tệp nào phù hợp với \"$searchQuery\""
                                    "nocategory" -> "Không tìm thấy tệp thuộc danh mục đã chọn trong thư mục này"
                                    else -> "Thư mục hiện tại chưa có dữ liệu nào"
                                },
                                actionText = when (state) {
                                    "noresult" -> "Xóa tìm kiếm"
                                    "nocategory" -> "Hiện tất cả tệp"
                                    else -> "Làm mới"
                                },
                                onAction = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    when (state) {
                                        "noresult" -> {
                                            searchQuery = ""
                                            fileBrowserVM.clearSearch()
                                        }
                                        "nocategory" -> {
                                            selectedCategory = "ALL"
                                        }
                                        else -> {
                                            fileBrowserVM.refresh()
                                        }
                                    }
                                }
                            )
                            }
                        }
                    }
                }
            }
            } // THÊM DẤU NÀY ĐỂ ĐÓNG KHỐI else CỦA TÍNH NĂNG 3.E TÌM KIẾM
        }

        // LẮNG NGHE VÀ HIỂN THỊ DIALOG TỪ VIEWMODEL TRÊN BROWSER SCREEN
        if (globalUiVM.showCommonDialog) {
            AppStatusDialog(
                type = globalUiVM.commonDialogType,
                message = globalUiVM.commonDialogMessage,
                onDismiss = { globalUiVM.dismiss() }
            )
        }
    }
}

// --- FILE ITEM GRID CELL (kept for backwards compat) ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreenFileItemGridCell(
    file: NasFile,
    fileBrowserVM: FileBrowserViewModel,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    viewedRefreshTick: Int = 0,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
    onVideo: (String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)
    val smartToolsVM = com.nas.naswebdav.LocalSmartToolsVM.current
    // Gọi thẳng từ Utils để ăn trọn mọi định dạng ảnh (HEIC, PNG, GIF, BMP...)
    val isImage = com.nas.naswebdav.utils.MediaUtils.isImage(file.name)
    val isMedia = isVideo || isImage
    val auth = WebDavManager.currentAuthState().authHeader

    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf(file.name) }

    // Viewed set reactive từ PreferencesRepository — mỗi cell collect 1 lần,
    // không đọc prefs mỗi recomposition.
    val prefsRepo = remember(context) {
        com.nas.naswebdav.utils.PreferencesRepository.get(context)
    }
    val viewedSet by prefsRepo.viewedFiles.collectAsStateWithLifecycle()
    val itemScope = rememberCoroutineScope()
    // Key on viewedRefreshTick de re-init khi parent goi "Chon tat ca" mark all viewed.
    var isNewFile by remember(file.path, viewedRefreshTick, viewedSet) {
        mutableStateOf(!file.isDirectory && file.path !in viewedSet)
    }

    val isTrash = fileBrowserVM.isSpecialMode && fileBrowserVM.specialTitle == "Thùng rác"

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
                // R4: trong trash -> xoa vinh vien (da xac nhan); ngoai trash ->
                // dua vao trash (khoi phuc duoc). Khong dung chung deleteFile().
                if (isTrash) fileBrowserVM.deletePermanently(context, file)
                else fileBrowserVM.deleteFile(context, file)
                onDelete?.invoke()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showRenameDialog) {
        val renameFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = DarkCard,
            shape = RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.action_rename), fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(renameFocusRequester),
                    focusRequester = renameFocusRequester
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRenameDialog = false
                    if (newFileName.isNotBlank() && newFileName != file.name) fileBrowserVM.renameFile(context, file, newFileName)
                }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = DarkSurface), shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRenameDialog = false }, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary), shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    val cellHaptic = LocalHapticFeedback.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {
                    // Mark file da xem -> red dot bien mat. Folder khong tracking.
                    if (!selectionMode && !file.isDirectory && isNewFile) {
                        isNewFile = false
                        itemScope.launch(Dispatchers.IO) {
                            markBrowserFilesViewed(prefsRepo, listOf(file.path))
                        }
                    }
                    onClick()
                },
                onLongClick = {
                    cellHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_download)) }, onClick = {
                showMenu = false
                val request = android.app.DownloadManager.Request(file.path.toUri())
                    .setTitle(file.name)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, file.name)
                    .addRequestHeader("Authorization", auth)
                (context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(request)

                commonDialogType = DialogType.SUCCESS
                commonDialogMessage = "Đã bắt đầu tải về: ${file.name}"
                showCommonDialog = true
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_copy_link)) }, onClick = {
                showMenu = false
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NAS Link", file.path))

                commonDialogType = DialogType.SUCCESS
                commonDialogMessage = "Đã sao chép liên kết tệp!"
                showCommonDialog = true
            })
            // Chỉ hiện nút Khôi phục nếu đang đứng trong Thùng rác
            if (fileBrowserVM.isSpecialMode && fileBrowserVM.specialTitle == "Thùng rác") {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_restore)) },
                    onClick = {
                        showMenu = false
                        fileBrowserVM.restoreFile(context, file)
                    },)
            }

            // TÍNH NĂNG MỚI: Giải nén tại NAS
            if (file.name.lowercase().endsWith(".zip")) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_extract_nas), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) },
                    onClick = {
                        showMenu = false
                        smartToolsVM.unzipFile(file.path)
                    }
                )
            }

            // Mở video bằng ứng dụng ngoài
            if (isVideo) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open_external), color = AccentOrange, fontWeight = FontWeight.Bold) },
                    onClick = {
                        showMenu = false
                        openExternalVideoPlayer(
                            context = context,
                            url = file.path,
                            user = WebDavManager.currentUser,
                            pass = WebDavManager.currentPass,
                            onError = {
                                commonDialogType = DialogType.ERROR
                                commonDialogMessage = "Không tìm thấy trình phát video ngoài nào!"
                                showCommonDialog = true
                            }
                        )
                    }
                )
            }

            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { showMenu = false; newFileName = file.name; showRenameDialog = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_properties)) }, onClick = { showMenu = false; showPropertiesDialog = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_file), color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteDialog = true })
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
                        isMedia -> DarkCardHover
                        else -> TextTertiary
                    }
                )
        ) {
            if (isMedia) {
                // FIX 2026-07-13: thumbState sync — play icon chỉ hiện khi thumb load thành công
                var thumbState by remember { mutableStateOf<ThumbState?>(value = null) }
                WebDavCachedThumbnail(
                    url = file.path, auth = auth, isVideo = isVideo,
                    modifier = Modifier.fillMaxSize(),
                    onStateChange = { thumbState = it }
                )

                // Badge video play icon — CHỈ hiện khi thumb load thành công
                if (isVideo && thumbState == ThumbState.SUCCESS) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp).align(Alignment.Center)
                    )
                }
            } else if (file.isDirectory) {
                // THƯ MỤC: Icon folder lớn, canh giữa
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(65.dp)
                )
            } else {
                // FILE THƯỜNG: Icon cơ bản, canh giữa
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(40.dp).align(Alignment.Center)
                )
            }

            // GẮN BADGE THÔNG TIN (Cho mọi tệp không phải thư mục)
            if (!file.isDirectory) {
                val ext = file.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() } ?: "FILE"
                
                // MÀU SẮC BADGE THEO LOẠI FILE
                val extColor = when {
                    isImage -> MaterialTheme.colorScheme.primary // Xanh dương
                    isVideo -> AccentOrange // Cam
                    ext in listOf("ZIP", "RAR", "7Z", "TAR", "GZ") -> MaterialTheme.colorScheme.error // Đỏ
                    ext in listOf("TXT", "MD", "LOG", "JSON", "XML", "PY", "KT") -> MaterialTheme.colorScheme.tertiary // Xanh lá
                    ext in listOf("PDF", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX") -> MaterialTheme.colorScheme.secondary // Tím
                    ext in listOf("MP3", "WAV", "FLAC", "M4A") -> MaterialTheme.colorScheme.primary // Xanh Cyan
                    else -> TextTertiary // Xám
                }

                // Extension góc dưới phải
                Text(
                    text = ext,
                    color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(DarkSurface.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }

            if (!selectionMode && !file.isDirectory) {
                if (isTrash && file.lastModified > 0L) {
                    val daysInTrash = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - file.lastModified)
                    val daysLeft = (30 - daysInTrash).coerceAtLeast(0)
                    val badgeColor = when {
                        daysLeft <= 3 -> MaterialTheme.colorScheme.error
                        daysLeft <= 7 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .background(badgeColor.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.label_days_left, daysLeft), color = MaterialTheme.colorScheme.onSurface, fontSize = 7.sp, fontWeight = FontWeight.Bold, lineHeight = 8.sp)
                    }
                } else if (isNewFile) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
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
                            .background(AccentBlue.copy(alpha = 0.33f))
                    )
                }
                // Checkbox góc trái trên — luôn hiển thị khi selectionMode
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp)
                        .background(TextPrimary.copy(alpha = 0.85f), shape = androidx.compose.foundation.shape.CircleShape)
                        .clip(androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Đã chọn",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.RadioButtonUnchecked,
                            contentDescription = "Chưa chọn",
                            tint = TextSecondary,
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
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 20; maxRequestsPerHost = 4 })
        .build()
}

// LỚP PHỤ TRỢ: Bộ nhớ Lịch sử Tìm Kiếm (TÍNH NĂNG 3.E)
@androidx.compose.runtime.Immutable
data class SearchHistory(val query: String, val timestamp: Long)

class SearchHistoryManager(context: android.content.Context) {
    private val repo = com.nas.naswebdav.utils.PreferencesRepository.get(context)

    fun saveQuery(query: String) = repo.saveSearchQuery(query)

    fun getHistory(): List<SearchHistory> {
        return try {
            repo.getSearchHistory().map { (q, ts) -> SearchHistory(q, ts) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.e("SearchHistory", "Không đọc được lịch sử tìm kiếm: ${e.message}")
            emptyList()
        }
    }
}
