package com.nas.naswebdav.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.NasFile
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.encodeWebDavSegment
import com.nas.naswebdav.ThumbnailAuditData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Stack
import java.util.concurrent.ConcurrentHashMap

/**
 * FileBrowserViewModel — Phase 7a.1: Primary owner of file-browser state.
 *
 * Quản lý: Navigation (currentUrl, urlStack), file list (fileList, pagedFilesFlow),
 *          Loading state (isLoading, loadGeneration), pending deletes,
 *          batch operations, image viewer counter, text preview.
 *
 * Phase 7a.1: State moved from WebDavViewModel facade. Facade retains read-only
 * mirrors for backwards compatibility until Phase 7 UI migration flips all readers.
 */
class FileBrowserViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ NAVIGATION STATE (Phase 7a.1 — moved from facade) ═══

    var currentUrl by androidx.compose.runtime.mutableStateOf("")
    internal val urlStack: Stack<String> = Stack()
    var isSpecialMode by androidx.compose.runtime.mutableStateOf(false)
    var specialTitle by androidx.compose.runtime.mutableStateOf("")
        internal set

    // ═══ FILE LIST STATE ═══

    var fileList by androidx.compose.runtime.mutableStateOf<List<NasFile>>(emptyList())
        internal set

    /** Set tracks file paths pending deletion — prevents files from reappearing after refresh. */
    internal val pendingDeletes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Guards against stale coroutines overwriting newer UI state when loadCurrentUrl() is invoked again. */
    internal var loadGeneration = 0

    private val _pagedFilesFlow = MutableStateFlow<Flow<PagingData<NasFile>>>(emptyFlow())
    val pagedFilesFlow = _pagedFilesFlow.asStateFlow()

    private val _thumbnailAudit = MutableStateFlow<ThumbnailAuditData?>(null)
    val thumbnailAudit = _thumbnailAudit.asStateFlow()
    internal fun updateThumbnailAudit(value: ThumbnailAuditData?) { _thumbnailAudit.value = value }
    internal fun updatePagedFilesFlow(value: Flow<PagingData<NasFile>>) { _pagedFilesFlow.value = value }
    internal fun incrementLoadGeneration(): Int { loadGeneration++; return loadGeneration }

    // ═══ LOADING / ERROR STATE ═══

    var isLoading by androidx.compose.runtime.mutableStateOf(false)
        internal set

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

    /** Group 1 — Navigation. Logic chính (urlStack, fileList update) giữ ở facade WebDavViewModel.
     *  Method này chỉ trigger callback — facade wire trong Phase 7 sẽ delegate sang đây.
     */
    fun openFolder(file: NasFile) {
        // Actual navigation: managed by facade (openFolder mutates urlStack + currentUrl + loadCurrentUrl)
        // Phase 5b keeps this no-op; facade handles all UI state mutations.
    }

    fun openSpecificUrl(url: String, title: String) {
        // No-op: facade handles url mutation + loadCurrentUrl
    }

    fun refresh() {
        // No-op: facade's refresh() dispatches to loadCurrentUrl/showLatestPhotos/etc
    }

    fun resetToDefaultMode() {
        // No-op: facade owns isSpecialMode + specialTitle state
    }

    fun navigateToUrl(url: String) {
        // No-op: facade handles navigation
    }

    fun goBack(): Boolean = false

    fun searchGlobal(keyword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = WebDavManager.listFiles(WebDavManager.currentBaseUrl)
                    .filter { it.name.contains(keyword, ignoreCase = true) }
                // Search results fed back to facade via SharedStateHolder
                withContext(Dispatchers.Main) {
                    com.nas.naswebdav.shared.SharedStateHolder.updateErrorMessage("Tìm thấy ${results.size} kết quả")
                }
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "searchGlobal: ${e.message}")
            }
        }
    }

    fun showLatestPhotos() {
        // No-op: facade's getLatestPhotos() updates fileList with cached photos
    }

    fun showRecentVideos() {
        // No-op: facade's getRecentVideos() updates fileList
    }

    fun deleteFile(context: Context, file: NasFile) {
        // Phase 5b Group 2: actual WebDAV MOVE to trash on NAS
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trashUrl = file.path.toTrashUrl()
                if (trashUrl != null) {
                    WebDavManager.renameFile(file.path, trashUrl.replace("/", ""))
                } else {
                    WebDavManager.deleteFile(file.path)
                }
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "deleteFile: ${e.message}")
                // Facade (WebDavViewModel.deleteFile) handles rollback + offline queue
            }
        }
    }

    private fun String.toTrashUrl(): String? {
        val normalizedBase = WebDavManager.currentBaseUrl.trimEnd('/')
        val relativePath = removePrefix(normalizedBase).removePrefix("/").trimStart('/')
        val driveName = relativePath.substringBefore('/')
        return if (driveName.isBlank()) null
        else {
            val fileName = substringAfterLast('/')
            val safeName = fileName.replace('/', '_').take(200)
            "$normalizedBase/${encodeWebDavSegment(driveName)}/.trash/${encodeWebDavSegment(safeName)}"
        }
    }

    fun deleteMultipleFiles(context: Context, filesToDelete: List<NasFile>) {
        viewModelScope.launch(Dispatchers.IO) {
            filesToDelete.forEach { file ->
                try {
                    val trashUrl = file.path.toTrashUrl()
                    if (trashUrl != null) {
                        WebDavManager.renameFile(file.path, trashUrl.replace("/", ""))
                    } else {
                        WebDavManager.deleteFile(file.path)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun batchCopyFiles(context: Context, filesToCopy: List<NasFile>, destUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            filesToCopy.forEach { file ->
                try {
                    val encodedName = encodeWebDavSegment(file.name)
                    val sep = if (destUrl.endsWith("/")) "" else "/"
                    val targetUrl = destUrl + sep + encodedName
                    WebDavManager.copyFile(file.path, targetUrl)
                } catch (_: Exception) { }
            }
            isBatchProcessing = false
            batchProcessProgress = 1f
        }
    }

    fun batchMoveFiles(context: Context, filesToMove: List<NasFile>, destUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            filesToMove.forEach { file ->
                try {
                    val encodedName = encodeWebDavSegment(file.name)
                    val sep = if (destUrl.endsWith("/")) "" else "/"
                    val targetUrl = destUrl + sep + encodedName
                    WebDavManager.renameFile(file.path, targetUrl)
                } catch (_: Exception) { }
            }
            isBatchProcessing = false
            batchProcessProgress = 1f
        }
    }

    fun renameFile(context: Context, file: NasFile, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newUrl = file.path.substringBeforeLast('/') + "/" + encodeWebDavSegment(newName)
                WebDavManager.renameFile(file.path, newUrl)
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "renameFile: ${e.message}")
                // Facade handles rollback + offline queue
            }
        }
    }

    fun createFolder(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetUrl = (context.applicationContext as NasApplication).let {
                    val currentUrl = WebDavManager.currentBaseUrl
                    val sep = if (currentUrl.endsWith("/")) "" else "/"
                    val encodedName = encodeWebDavSegment(folderName)
                    currentUrl + sep + encodedName + "/"
                }
                WebDavManager.createFolder(targetUrl)
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "createFolder: ${e.message}")
            }
        }
    }

    fun restoreFile(context: Context, file: NasFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val originalPath = file.path.replace("/.trash/", "/")
                WebDavManager.renameFile(file.path, originalPath + file.name)
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "restoreFile: ${e.message}")
            }
        }
    }

    fun restoreMultipleFiles(context: Context, filesToRestore: List<NasFile>) {
        viewModelScope.launch(Dispatchers.IO) {
            filesToRestore.forEach { file ->
                try {
                    val originalPath = file.path.replace("/.trash/", "/")
                    WebDavManager.renameFile(file.path, originalPath + file.name)
                } catch (_: Exception) { }
            }
        }
    }

    fun fetchTextPreview(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(path)
                    .header("Authorization", WebDavManager.currentAuthHeader())
                    .get()
                    .build()
                val response = NasApplication.instance.fastApiClient.newCall(request).execute()
                val text = response.body?.string() ?: ""
                response.close()
                withContext(Dispatchers.Main) { textPreviewContent = text }
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "fetchTextPreview: ${e.message}")
            }
        }
    }
}