package com.nas.naswebdav.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.NasFile
import com.nas.naswebdav.WebDavRepository
import kotlinx.coroutines.launch
import java.util.Stack

/**
 * FileBrowserViewModel — Phase 5 của VM Split (HIGHEST RISK).
 *
 * Quản lý: File list, folder navigation (urlStack), pending deletes, batch operations,
 *          Image viewer counter, Text preview.
 *
 * Đây là domain lớn nhất (~30% codebase) và phức tạp nhất vì 27 UI files depend.
 *
 * Phase 5 skeleton: state declarations + placeholder methods.
 * KHÔNG MOVE currentUrl — giữ nguyên ở WebDavViewModel cho đến Phase 7 (migration).
 * Function bodies sẽ được move từ facade trong Phase 5b.
 *
 * Design: WebDavViewModel giữ `currentUrl`, `urlStack`, `fileList` làm primary state.
 * FileBrowserVM skeleton chỉ khai báo state types — khi migrate sẽ sync 2 chiều.
 */
class FileBrowserViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ NOTE: currentUrl, urlStack, fileList — GIỮ ở WebDavViewModel ═══
    // Lý do: 27 UI files đọc trực tiếp `viewModel.currentUrl`, `viewModel.fileList`
    // Nếu move sang FileBrowserVM → phải đổi 27 files + 500+ references.
    // Phase 7 sẽ migrate từ từ.
    //
    // Khi Phase 7 migrate, FileBrowserVM sẽ có:
    //   var currentUrl by mutableStateOf("")
    //   val urlStack = Stack<String>()
    //   var fileList by mutableStateOf<List<NasFile>>(emptyList())
    //   var isLoading by mutableStateOf(false)

    // ═══ BATCH OPERATION STATE ═══

    var isBatchProcessing by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var batchProcessType by androidx.compose.runtime.mutableStateOf("")
        internal set
    var batchProcessProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        internal set
    var batchProcessCurrentFile by androidx.compose.runtime.mutableStateOf("")
        internal set

    // ═══ IMAGE VIEWER COUNTER ═══

    var totalImagesInFolder by androidx.compose.runtime.mutableIntStateOf(0)
        internal set
    var loadedImagesCount by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    // ═══ TEXT PREVIEW ═══

    var textPreviewContent by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set
    var textPreviewName by androidx.compose.runtime.mutableStateOf("")
        internal set

    // ═══ PLACEHOLDER METHODS — implement Phase 5b ═══

    fun openFolder(file: NasFile) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun openSpecificUrl(url: String, title: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun refresh() { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun resetToDefaultMode() { /* TODO Phase 5b */ }
    fun navigateToUrl(url: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun goBack(): Boolean = false
    fun searchGlobal(keyword: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun showLatestPhotos() { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun showRecentVideos() { viewModelScope.launch { /* TODO Phase 5b */ } }

    fun deleteFile(context: Context, file: NasFile) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun deleteMultipleFiles(context: Context, filesToDelete: List<NasFile>) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun batchCopyFiles(context: Context, filesToCopy: List<NasFile>, destUrl: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun batchMoveFiles(context: Context, filesToMove: List<NasFile>, destUrl: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun renameFile(context: Context, file: NasFile, newName: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun createFolder(context: Context, folderName: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun restoreFile(context: Context, file: NasFile) { viewModelScope.launch { /* TODO Phase 5b */ } }
    fun restoreMultipleFiles(context: Context, filesToRestore: List<NasFile>) { viewModelScope.launch { /* TODO Phase 5b */ } }

    fun fetchTextPreview(path: String) { viewModelScope.launch { /* TODO Phase 5b */ } }
}