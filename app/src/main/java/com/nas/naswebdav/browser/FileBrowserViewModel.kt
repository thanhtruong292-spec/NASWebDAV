package com.nas.naswebdav.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.NasFile
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.toApiBaseUrl
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.encodeWebDavSegment
import com.nas.naswebdav.ThumbnailAuditData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.util.Stack
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * FileBrowserViewModel — Primary owner of file-browser state.
 *
 * Quản lý: Navigation (currentUrl, urlStack), file list (fileList, pagedFilesFlow),
 *          Loading state (isLoading, loadGeneration), pending deletes,
 *          batch operations, image viewer counter, text preview.
 *
 * Phase 7a.1: State migrated from WebDavViewModel facade. Facade since deleted
 * (Phase 7d.7). UI now reads state directly from this VM.
 */
class FileBrowserViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ NAVIGATION STATE (Phase 7a.1 — moved from facade) ═══

    var currentUrl by androidx.compose.runtime.mutableStateOf("")
    internal val urlStack: Stack<String> = Stack()
    var errorMessage by androidx.compose.runtime.mutableStateOf<String?>(null)
    var isSpecialMode by androidx.compose.runtime.mutableStateOf(false)
    var specialTitle by androidx.compose.runtime.mutableStateOf("")
        internal set

    // ═══ FILE LIST STATE ═══

    var fileList by androidx.compose.runtime.mutableStateOf<List<NasFile>>(emptyList())
        internal set

    /** Set tracks file paths pending deletion — prevents files from reappearing after refresh. */
    internal val pendingDeletes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Guards against stale coroutines overwriting newer UI state when loadCurrentUrl() is invoked again. */
    private var loadGeneration = 0

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

    /** Group 1 — Navigation. */
    fun openFolder(file: NasFile) {
        urlStack.push(currentUrl)
        currentUrl = if (file.path.endsWith("/")) file.path else "${file.path}/"
        fileList = emptyList()
        isLoading = true
        loadCurrentUrl()
    }

    fun openSpecificUrl(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                urlStack.clear()
                isSpecialMode = true
                specialTitle = title
                val targetUrl = if (url.endsWith("/")) url else "$url/"
                currentUrl = targetUrl
                fileList = emptyList()
                isLoading = true
            }
            val targetUrl = if (url.endsWith("/")) url else "$url/"
            if (title == "Thùng rác") {
                try { WebDavManager.createFolder(targetUrl) } catch(e: Exception) {}
            }
            withContext(Dispatchers.Main) { loadCurrentUrl() }
        }
    }

    fun refresh() {
        if (isSpecialMode) {
            when (specialTitle) {
                "Ảnh mới nhất" -> showLatestPhotos()
                "Video gần đây" -> showRecentVideos()
                else -> loadCurrentUrl(forceRefresh = true) // Cho Thùng rác
            }
        } else {
            loadCurrentUrl(forceRefresh = true)
        }
    }

    fun resetToDefaultMode() {
        isSpecialMode = false
        specialTitle = ""
        urlStack.clear()
        currentUrl = WebDavManager.currentBaseUrl
        fileList = emptyList()
        isLoading = true
        loadCurrentUrl()
    }

    fun navigateToUrl(url: String) {
        currentUrl = url
        fileList = emptyList()
        isLoading = true
        loadCurrentUrl()
    }

    fun goBack(): Boolean {
        if (urlStack.isNotEmpty()) {
            currentUrl = urlStack.pop()
            fileList = emptyList()
            isLoading = true
            loadCurrentUrl()
            return true
        }
        return false
    }

    fun searchGlobal(keyword: String) {
        if (keyword.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // Debounce
            if (!isActive) return@launch
            val results = try { repository.searchGlobal(keyword) } catch(e: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                _searchResults.value = results
                _isSearchActive.value = false
            }
        }
    }

    // ═══ RECURSIVE SEARCH STATE ═══

    private val _searchResults = MutableStateFlow<List<NasFile>>(emptyList())
    val searchResults: StateFlow<List<NasFile>> = _searchResults.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Perform recursive search starting from [currentUrl] with max depth 3.
     * First filters current [fileList] for instant feedback, then recursively
     * PROPFINDs subdirectories. Updates [searchResults] incrementally.
     */
    fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank() || query.length < 2) {
            _isSearchActive.value = false
            _searchResults.value = emptyList()
            return
        }

        // FIX Bug 1: capture currentUrl on Main thread before launching IO coroutine.
        // Without this, the IO thread could read a stale or empty value, causing
        // PROPFIND on "/" or wrong path → silent BFS failure.
        val searchRootUrl = currentUrl
        if (searchRootUrl.isBlank()) {
            _isSearchActive.value = false
            _searchResults.value = emptyList()
            return
        }

        _isSearchActive.value = true
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // Debounce 300ms
            if (!isActive) return@launch

            val allResults = mutableListOf<NasFile>()
            val seenPaths = mutableSetOf<String>()
            var failCount = 0

            // Instant feedback: filter current fileList
            val instantResults = fileList.filter {
                it.name.contains(query, ignoreCase = true)
            }
            for (file in instantResults) {
                if (seenPaths.add(file.path)) {
                    allResults.add(file)
                }
            }
            withContext(Dispatchers.Main) { _searchResults.value = allResults.toList() }

            // BFS recursive PROPFIND with max depth 3
            val queue = ArrayDeque<Pair<String, Int>>() // (url, depth)
            queue.add(searchRootUrl to 0)

            while (queue.isNotEmpty() && isActive) {
                val (url, depth) = queue.removeFirst()
                if (depth > 3) continue

                try {
                    val files = WebDavManager.listFiles(url)
                    for (file in files) {
                        if (isActive && seenPaths.add(file.path)) {
                            if (file.name.contains(query, ignoreCase = true)) {
                                allResults.add(file)
                            }
                            if (file.isDirectory) {
                                queue.add(file.path to depth + 1)
                            }
                        }
                    }
                    // Update results incrementally on Main thread
                    withContext(Dispatchers.Main) {
                        _searchResults.value = allResults.toList()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // FIX Bug 4: track failures so we can warn user if search returns empty
                    // due to widespread subfolder errors.
                    failCount++
                    android.util.Log.w("RecursiveSearch", "PROPFIND failed for $url: ${e.message}")
                }
            }

            withContext(Dispatchers.Main) {
                _searchResults.value = allResults
                _isSearchActive.value = false
                if (allResults.isEmpty() && failCount > 0) {
                    errorMessage = "Không truy cập được $failCount thư mục con — NAS có thể từ chối quyền hoặc mạng chậm"
                }
            }
        }
    }

    /** Clear recursive search state and cancel any in-progress search. */
    fun clearSearch() {
        searchJob?.cancel()
        _isSearchActive.value = false
        _searchResults.value = emptyList()
    }

    fun showLatestPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Ảnh mới nhất" }
            try { repository.getRemoteFilesAndCache(WebDavManager.currentBaseUrl) } catch(e: Exception) { withContext(Dispatchers.Main) { errorMessage = "Lỗi: ${e.message}" } }
            val photos = repository.getLatestPhotos()
            withContext(Dispatchers.Main) { fileList = photos; isLoading = false }
        }
    }

    fun showRecentVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Video gần đây" }
            try { repository.getRemoteFilesAndCache(WebDavManager.currentBaseUrl) } catch(e: Exception) { withContext(Dispatchers.Main) { errorMessage = "Lỗi: ${e.message}" } }
            val videos = repository.getRecentVideos()
            withContext(Dispatchers.Main) { fileList = videos; isLoading = false }
        }
    }

    private fun loadCurrentUrl(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val gen = incrementLoadGeneration()
            errorMessage = null

            // Paging Flow from DB
            updatePagedFilesFlow(repository.getFilesStream(currentUrl).cachedIn(viewModelScope))

            // Static list for ImageViewerScreen
            val cached = repository.getCachedFiles(currentUrl)
            val activePendingDeletes = pendingDeletes.toSet()
            fileList = cached.map {
                NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
            }
                .filter { it.path !in activePendingDeletes }
                .filter { !it.name.startsWith(".") || isSpecialMode }
            
            if (gen != loadGeneration) return@launch // stale newer load in progress

            if (!forceRefresh && cached.isNotEmpty()) {
                isLoading = false
                launch(Dispatchers.IO) {
                    try { repository.getRemoteFilesAndCache(currentUrl) } catch (e: Exception) {}
                }
                return@launch
            }

            // Force Refresh or first time (empty cache)
            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    repository.getRemoteFilesAndCache(currentUrl)
                    val newCached = repository.getCachedFiles(currentUrl)
                    val activeDeletes = pendingDeletes.toSet()
                    withContext(Dispatchers.Main) {
                        if (gen == loadGeneration) {
                            fileList = newCached.map {
                                NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
                            }
                                .filter { it.path !in activeDeletes }
                                .filter { !it.name.startsWith(".") || isSpecialMode }
                            isLoading = false
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (gen == loadGeneration) {
                            isLoading = false
                            errorMessage = "Lỗi tải thư mục: ${e.message}"
                        }
                    }
                }
            }
        }
    }

    fun deleteFile(context: Context, file: NasFile) {
        viewModelScope.launch(Dispatchers.IO) {
            // Đánh dấu pendingDeletes và cập nhật UI ngay lập tức
            pendingDeletes.add(file.path)
            withContext(Dispatchers.Main) {
                fileList = fileList.filter { it.path != file.path }
            }
            var deletedSuccessfully = false
            var lastError: Exception? = null

            // 1. Thử chuyển file vào Thùng rác (.trash/) qua WebDAV MOVE
            val trashUrl = file.path.toTrashUrl()
            if (trashUrl != null) {
                try {
                    WebDavManager.renameFile(file.path, trashUrl)
                    deletedSuccessfully = true
                    try {
                        NasApplication.instance.database.trashMetaDao().insert(
                            com.nas.naswebdav.TrashMeta(trashPath = trashUrl, originalPath = file.path)
                        )
                    } catch (dbEx: kotlinx.coroutines.CancellationException) { throw dbEx } catch (dbEx: Exception) {
                        android.util.Log.w("FileBrowser", "DB sync failed after single delete to trash", dbEx)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.w("FileBrowser", "WebDAV MOVE to .trash failed, falling back to DELETE: ${e.message}")
                    lastError = e
                }
            }

            // 2. Nếu MOVE thất bại hoặc không có trashUrl, thực hiện WebDAV DELETE trực tiếp
            if (!deletedSuccessfully) {
                try {
                    WebDavManager.deleteFile(file.path, file.isDirectory)
                    deletedSuccessfully = true
                } catch (e: Exception) {
                    android.util.Log.e("FileBrowser", "WebDAV DELETE failed: ${e.message}", e)
                    lastError = e
                }
            }

            if (deletedSuccessfully) {
                // Xóa khỏi Room Cache DB để khi refresh ứng dụng không bị khôi phục lại từ SQLite
                repository.removeDuplicateFromDb(file.path)
            } else {
                pendingDeletes.remove(file.path)
            }

            withContext(Dispatchers.Main) {
                if (deletedSuccessfully) {
                    android.widget.Toast.makeText(context, "Đã xóa ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val errText = lastError?.message ?: "Lỗi hệ thống WebDAV"
                    android.widget.Toast.makeText(context, "Không thể xóa ${file.name}: $errText", android.widget.Toast.LENGTH_LONG).show()
                    fileList = fileList + file
                }
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
        if (filesToDelete.isEmpty()) return
        enqueueBatchOperation(context, "DELETE", filesToDelete, "")
    }

    fun batchCopyFiles(context: Context, filesToCopy: List<NasFile>, destUrl: String) {
        if (filesToCopy.isEmpty()) return
        enqueueBatchOperation(context, "COPY", filesToCopy, destUrl)
    }

    fun batchMoveFiles(context: Context, filesToMove: List<NasFile>, destUrl: String) {
        if (filesToMove.isEmpty()) return
        enqueueBatchOperation(context, "MOVE", filesToMove, destUrl)
    }

    private fun enqueueBatchOperation(context: Context, operation: String, files: List<NasFile>, destUrl: String) {
        isBatchProcessing = true
        batchProcessType = operation
        batchProcessProgress = 0f

        val payloadFile = try {
            val payloadDir = java.io.File(context.cacheDir, "batch_payloads").apply { mkdirs() }
            val file = java.io.File(payloadDir, "batch_${operation}_${System.currentTimeMillis()}.json")
            val payloadItems = org.json.JSONArray()
            files.forEach { item ->
                payloadItems.put(org.json.JSONObject().apply {
                    put("path", item.path)
                    put("name", item.name)
                })
            }
            file.writeText(
                org.json.JSONObject().put("files", payloadItems).toString(),
                Charsets.UTF_8
            )
            file
        } catch (e: Exception) {
            isBatchProcessing = false
            errorMessage = "Không thể chuẩn bị tác vụ hàng loạt: ${e.message}"
            return
        }

        val inputData = androidx.work.Data.Builder()
            .putString("operation", operation)
            .putString("payloadFile", payloadFile.absolutePath)
            .putString("destUrl", destUrl)
            .putString("baseUrl", WebDavManager.currentBaseUrl)
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.BatchOperationWorker>()
            .setInputData(inputData)
            .addTag("BATCH_OPERATION")
            .build()

        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork("BatchOperation_$operation", androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)

        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { workInfo ->
                    if (workInfo != null) {
                        val completed = workInfo.progress.getInt("completed", 0)
                        val total = workInfo.progress.getInt("total", files.size)
                        batchProcessProgress = if (total > 0) completed.toFloat() / total else 0f

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED ||
                            workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            batchProcessProgress = 1f
                            isBatchProcessing = false
                            val failCount = workInfo.progress.getInt("failCount", 0)
                            val shouldRefresh = when (operation) {
                                "COPY", "DELETE", "RESTORE" -> true
                                "MOVE" -> destUrl.startsWith(currentUrl) || failCount > 0
                                else -> false
                            }
                            if (shouldRefresh) {
                                refresh()
                            }
                            throw kotlinx.coroutines.CancellationException("Batch WorkInfo collector finished")
                        }
                    }
                }
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
            val trashMetaDao = NasApplication.instance.database.trashMetaDao()
            val meta = runCatching { trashMetaDao.findByTrashPath(file.path) }.getOrNull()
            val targetUrl = meta?.originalPath ?: file.path.replace(".trash/", "")
            try {
                WebDavManager.renameFile(file.path, targetUrl)
                trashMetaDao.deleteByTrashPath(file.path)
                refresh()
            } catch (e: Exception) {
                errorMessage = "Khôi phục thất bại: ${e.message}"
            }
        }
    }

    fun restoreMultipleFiles(context: Context, filesToRestore: List<NasFile>) {
        if (filesToRestore.isEmpty()) return
        enqueueBatchOperation(context, "RESTORE", filesToRestore, "")
    }

    fun unzipFile(context: Context, filePath: String) {
        try {
            val uri = java.net.URI(filePath)
            val relativePath = uri.path.substringAfter("/webdav")
            val fileName = filePath.substringAfterLast("/")
            val jsonBody = org.json.JSONObject().apply {
                put("file_path", relativePath)
            }.toString()
            
            android.widget.Toast.makeText(context, "Tac vu giai nen ($fileName) dang chay ngam tren NAS!", android.widget.Toast.LENGTH_LONG).show()

            val inputData = androidx.work.Data.Builder()
                .putString("taskType", "UNZIP")
                .putString("apiUrl", "${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/file/unzip")
                .putString("jsonBody", jsonBody)
                .putString("taskLabel", "Giai nen $fileName")
                .build()

            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.LongRunningApiWorker>()
                .setInputData(inputData)
                .addTag("LONG_RUNNING_API")
                .build()

            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork("Unzip_$fileName", androidx.work.ExistingWorkPolicy.REPLACE, workRequest)
        } catch (e: Exception) {
            errorMessage = "Giải nén thất bại: ${e.message}"
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