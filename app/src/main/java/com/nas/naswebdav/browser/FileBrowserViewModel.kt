package com.nas.naswebdav.browser

import com.nas.naswebdav.R
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
import com.nas.naswebdav.buildWebDavRestoreTargetUrl
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val repository: WebDavRepository,
    private val appContext: Context = NasApplication.instance.applicationContext
) : ViewModel() {

    // UI prefs reactive — survive rotation (VM scoped), thay đọc prefs trong composable.
    private val prefsRepo = com.nas.naswebdav.utils.PreferencesRepository.get(appContext)
    val fileSort: StateFlow<String> = prefsRepo.fileSort
    val viewModeName: StateFlow<String> = prefsRepo.viewMode
    fun setFileSort(mode: String) = prefsRepo.setFileSort(mode)
    fun setViewModeName(mode: String) = prefsRepo.setViewMode(mode)
    fun markFilesViewed(paths: Collection<String>) = prefsRepo.markViewed(paths)

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
                try {
                    WebDavManager.createFolder(targetUrl)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("FileBrowser", "createFolder trash: ${e.message}")
                }
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

    /**
     * Windows-Explorer-style search: finds files matching [keyword] in any
     * subdirectory under the current browser root (not just the root itself).
     *
     * Strategy:
     * 1. Instant Room-cache results (files already browsed)
     * 2. BFS PROPFIND from root: list root → enqueue all subfolders →
     *    list each subfolder → match files → enqueue deeper folders
     *    Limits: depth ≤ 8, folders ≤ 1000, results ≤ 500 (prevents OOM)
     */
    fun searchGlobal(keyword: String) {
        val normalizedQuery = keyword.trim()
        if (normalizedQuery.isBlank()) {
            clearSearch()
            return
        }
        searchJob?.cancel()
        val searchRootUrl = currentUrl.ifBlank { WebDavManager.currentBaseUrl }
        if (searchRootUrl.isBlank()) {
            clearSearch()
            return
        }
        _isSearchActive.value = true
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(80) // Debounce cực ngắn giúp phản hồi ngay lập tức
            if (!isActive) return@launch

            val results = ConcurrentHashMap<String, NasFile>()
            val normalizedSearchRoot = if (searchRootUrl.endsWith("/")) searchRootUrl else "$searchRootUrl/"
            val relativeRoot = if (searchRootUrl.startsWith(WebDavManager.currentBaseUrl)) {
                searchRootUrl.removePrefix(WebDavManager.currentBaseUrl).trim('/')
            } else ""

            // Pass 0: Gọi Server API /api/search quét siêu tốc trực tiếp trong phạm vi thư mục hiện tại
            try {
                val apiBaseUrl = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (apiBaseUrl.isNotBlank()) {
                    val encodedQ = java.net.URLEncoder.encode(normalizedQuery, "UTF-8")
                    val encodedRoot = java.net.URLEncoder.encode(relativeRoot, "UTF-8")
                    val searchApiUrl = "$apiBaseUrl/api/search?q=$encodedQ&root=$encodedRoot"
                    val request = okhttp3.Request.Builder()
                        .url(searchApiUrl)
                        .header("Authorization", WebDavManager.currentAuthHeader())
                        .get()
                        .build()
                    NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val jsonStr = response.body?.string()
                            if (!jsonStr.isNullOrEmpty()) {
                                val jsonObj = org.json.JSONObject(jsonStr)
                                if (jsonObj.optBoolean("success", false)) {
                                    val arr = jsonObj.optJSONArray("results")
                                    if (arr != null && arr.length() > 0) {
                                        val apiFiles = mutableListOf<NasFile>()
                                        for (i in 0 until arr.length()) {
                                            val obj = arr.getJSONObject(i)
                                            val file = NasFile(
                                                name = obj.getString("name"),
                                                path = obj.getString("path"),
                                                isDirectory = obj.getBoolean("isDirectory"),
                                                contentType = obj.optString("contentType", ""),
                                                contentLength = obj.optLong("contentLength", 0L),
                                                lastModified = obj.optLong("lastModified", 0L)
                                            )
                                            results[file.path] = file
                                            apiFiles.add(file)
                                        }
                                        repository.saveDiscoveredFiles(apiFiles, searchRootUrl)
                                        val apiList = results.values.toList()
                                        withContext(Dispatchers.Main) {
                                            _searchResults.value = apiList
                                            _isSearchActive.value = false
                                        }
                                        return@launch // Đã có kết quả chính xác 100% trong thư mục hiện tại!
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // Pass 1: Room Cache — query SQL giới hạn trong root (LIMIT 200),
            // thay full-table scan 25k rows vào RAM.
            try {
                repository.searchCacheUnder(normalizedSearchRoot, normalizedQuery).forEach { file ->
                    results[file.path] = file
                }
            } catch (_: Exception) {}
            if (results.isNotEmpty()) {
                val cachedList = results.values.toList()
                withContext(Dispatchers.Main) { _searchResults.value = cachedList }
            }

            // Pass 2: Quét đa luồng nhẹ nhàng (3 workers + delay 35ms) để không gây treo hay hao phí tài nguyên NAS
            val queue = ConcurrentLinkedQueue<Pair<String, Int>>()
            val visited = ConcurrentHashMap.newKeySet<String>()
            val root = com.nas.naswebdav.toValidUrl(if (searchRootUrl.endsWith("/")) searchRootUrl else "$searchRootUrl/")
            queue.add(root to 0)

            val activeWorkers = java.util.concurrent.atomic.AtomicInteger(0)
            val foldersVisited = java.util.concurrent.atomic.AtomicInteger(0)
            val maxDepth = 6
            val maxFolders = 400
            val maxResults = 300
            val workerCount = 3 // 3 luồng vừa phải tránh gây áp lực CPU NAS

            val updateMutex = Mutex()

            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.IO) {
                        while (isActive && foldersVisited.get() < maxFolders && results.size < maxResults) {
                            val item = queue.poll()
                            if (item == null) {
                                if (activeWorkers.get() == 0 && queue.isEmpty()) break
                                delay(15)
                                continue
                            }

                            val (folderUrl, depth) = item
                            val normalizedFolder = folderUrl.trimEnd('/').lowercase()

                            // Bỏ qua tuyệt đối các thư mục rác / hệ thống / cache để không phí tài nguyên
                            if (normalizedFolder.contains("/.trash") ||
                                normalizedFolder.contains("/@eadir") ||
                                normalizedFolder.contains("/#recycle") ||
                                normalizedFolder.contains("/.thumbnails") ||
                                normalizedFolder.contains("/.git") ||
                                normalizedFolder.contains("/.cache") ||
                                normalizedFolder.contains("/\$recycle.bin") ||
                                normalizedFolder.contains("/system volume information") ||
                                normalizedFolder.contains("/android/data") ||
                                normalizedFolder.contains("/android/obb")
                            ) continue

                            if (!visited.add(normalizedFolder) || depth > maxDepth) continue

                            activeWorkers.incrementAndGet()
                            foldersVisited.incrementAndGet()

                            try {
                                delay(35) // Tạm dừng 35ms giữa mỗi folder để duy trì CPU NAS < 5%
                                val children = WebDavManager.listFiles(folderUrl)
                                repository.saveDiscoveredFiles(children, folderUrl) // Lưu bản đồ SQLite ngầm
                                var hasNewMatch = false
                                for (file in children) {
                                    if (matchesQuery(file.name, normalizedQuery) && results.size < maxResults) {
                                        results[file.path] = file
                                        hasNewMatch = true
                                    }
                                    if (file.isDirectory && depth < maxDepth && !file.name.startsWith(".")) {
                                        val dirPath = com.nas.naswebdav.toValidUrl(if (file.path.endsWith("/")) file.path else "${file.path}/")
                                        queue.add(dirPath to depth + 1)
                                    }
                                }

                                if (hasNewMatch && isActive) {
                                    val currentMatches = results.values.toList()
                                    updateMutex.withLock {
                                        withContext(Dispatchers.Main) {
                                            _searchResults.value = currentMatches
                                        }
                                    }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            } finally {
                                activeWorkers.decrementAndGet()
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                _searchResults.value = results.values.toList()
                _isSearchActive.value = false
            }
        }
    }

    fun searchFiles(query: String) {
        searchGlobal(query)
    }

    fun matchesQuery(name: String, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val n = name.lowercase()

        // 1. Chuỗi con trực tiếp (có phân biệt/không phân biệt chữ hoa thường)
        if (n.contains(q)) return true

        // 2. Chuỗi con tiếng Việt không dấu
        val normName = removeDiacritics(n)
        val normQuery = removeDiacritics(q)
        if (normName.contains(normQuery)) return true

        // 3. Khớp đa từ (Tất cả từ trong câu truy vấn phải xuất hiện trong tên)
        val tokens = normQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size > 1 && tokens.all { normName.contains(it) }) return true

        // 4. Khớp chữ cái đầu từng từ (Acronym/Word-boundary match)
        val words = normName.split(Regex("[\\s._\\-]+")).filter { it.isNotBlank() }
        if (words.size >= q.length) {
            val acronym = words.mapNotNull { it.firstOrNull() }.joinToString("")
            if (acronym.contains(q)) return true
        }

        return false
    }

    fun calculateRelevanceScore(name: String, query: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val n = name.lowercase()
        val normName = removeDiacritics(n)
        val normQuery = removeDiacritics(q)

        var score = 0
        if (n == q || normName == normQuery) score += 1000
        else if (n.startsWith(q) || normName.startsWith(normQuery)) score += 500
        else if (n.contains(q) || normName.contains(normQuery)) score += 200
        else {
            val tokens = normQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size > 1 && tokens.all { normName.contains(it) }) score += 100
            else score += 50
        }
        return score
    }

    private fun removeDiacritics(str: String): String {
        val nfdNormalized = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
        val diacriticalRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
        return diacriticalRegex.replace(nfdNormalized, "")
            .replace('đ', 'd').replace('Đ', 'd')
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
        // Fallback to WebDavManager.currentBaseUrl so search works immediately after
        // login before user navigates anywhere.
        val searchRootUrl = currentUrl.ifBlank { WebDavManager.currentBaseUrl }
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
                    errorMessage = appContext.getString(R.string.browser_folder_access_error, failCount)
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
            try { repository.getRemoteFilesAndCache(WebDavManager.currentBaseUrl) } catch(e: Exception) { withContext(Dispatchers.Main) { errorMessage = appContext.getString(R.string.browser_error_prefix, e.message.orEmpty()) } }
            val photos = repository.getLatestPhotos()
            withContext(Dispatchers.Main) { fileList = photos; isLoading = false }
        }
    }

    fun showRecentVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Video gần đây" }
            try { repository.getRemoteFilesAndCache(WebDavManager.currentBaseUrl) } catch(e: Exception) { withContext(Dispatchers.Main) { errorMessage = appContext.getString(R.string.browser_error_prefix, e.message.orEmpty()) } }
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
                    try {
                        repository.getRemoteFilesAndCache(currentUrl)
                        // P2-7: refresh nen xong PHAl cap nhat fileList — ban cu chi
                        // ghi Room, UI giu danh sach cu (file them/xoa tu may khac
                        // khong hien). Generation guard + ton trong pendingDeletes.
                        val fresh = repository.getCachedFiles(currentUrl)
                        val activeDeletes = pendingDeletes.toSet()
                        withContext(Dispatchers.Main) {
                            if (gen == loadGeneration) {
                                fileList = fresh.map {
                                    NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
                                }
                                    .filter { it.path !in activeDeletes }
                                    .filter { !it.name.startsWith(".") || isSpecialMode }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("FileBrowser", "background refresh: ${e.message}")
                    }
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
                            errorMessage = appContext.getString(R.string.browser_error_prefix, e.message.orEmpty())
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
                    // P1-3: MOVE trash that bai -> GIU FILE + bao loi, KHONG
                    // fallback DELETE vinh vien. File khong con trong trash nen
                    // khong co TrashMeta; log ro de truy vet.
                    android.util.Log.w("FileBrowser", "WebDAV MOVE to .trash failed, giu file: ${e.message}")
                    com.nas.naswebdav.utils.SystemLogger.log("WARNING", "FileBrowser",
                        "MOVE trash that bai (${file.path}) — giu file, khong xoa vinh vien: ${e.message}")
                    lastError = e
                }
            }

            // P1-3: KHONG con fallback DELETE vinh vien khi MOVE trash that bai.
            // Chi DELETE truc tiep khi KHONG tinh duoc trashUrl (drive goc,
            // khong co vi tri trash hop le) — day la truong hop cau truc,
            // khong phai loi runtime, va da bao loi ro cho user.
            if (!deletedSuccessfully && trashUrl == null) {
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
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.browser_delete_success, file.name),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val errText = lastError?.message ?: context.getString(R.string.browser_webdav_system_error)
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.browser_delete_failure, file.name, errText),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    fileList = fileList + file
                }
            }
        }
    }

    /**
     * R4: xoa VINH VIEN file DANG O TRONG TRASH (da duoc user xac nhan o UI).
     * Tach khoi deleteFile() — ham do dung cho file thuong (MOVE vao trash).
     * Goi deleteFile() cho file trong trash se dung lai trash URL -> MOVE ve
     * chinh no -> that bai (hoi quy P1-3). Ham nay DELETE truc tiep + xoa meta.
     */
    fun deletePermanently(context: Context, file: NasFile) {
        viewModelScope.launch(Dispatchers.IO) {
            pendingDeletes.add(file.path)
            withContext(Dispatchers.Main) {
                fileList = fileList.filter { it.path != file.path }
            }
            try {
                WebDavManager.deleteFile(file.path, file.isDirectory)
                try {
                    NasApplication.instance.database.trashMetaDao().deleteByTrashPath(file.path)
                } catch (dbEx: kotlinx.coroutines.CancellationException) { throw dbEx } catch (dbEx: Exception) {
                    android.util.Log.w("FileBrowser", "DB sync failed after permanent delete", dbEx)
                }
                repository.removeDuplicateFromDb(file.path)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.browser_delete_success, file.name),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingDeletes.remove(file.path)
                com.nas.naswebdav.utils.SystemLogger.log("ERROR", "FileBrowser",
                    "Xóa vĩnh viễn thất bại (${file.path}): ${e.message}")
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.browser_delete_failure, file.name, e.message.orEmpty())
                    fileList = fileList + file
                }
            }
        }
    }

    // R5: ten trash DUY NHAT theo nguon. A/photo.jpg va B/photo.jpg khac
    // parent -> ten trash khac nhau (them hash parent), khong dung do 412.
    // TrashMeta luu originalPath day du de restore + hien thi ten goc.
    private fun String.toTrashUrl(): String? {
        val normalizedBase = WebDavManager.currentBaseUrl.trimEnd('/')
        val relativePath = removePrefix(normalizedBase).removePrefix("/").trimStart('/')
        val driveName = relativePath.substringBefore('/')
        return if (driveName.isBlank()) null
        else {
            val fileName = substringAfterLast('/')
            val parentPath = substringBeforeLast('/', "")
            val parentHash = parentPath.hashCode().toUInt().toString(36)
            val dot = fileName.lastIndexOf('.')
            val uniqueName = if (dot > 0) {
                fileName.substring(0, dot) + "__" + parentHash + fileName.substring(dot)
            } else {
                fileName + "__" + parentHash
            }
            val safeName = uniqueName.replace('/', '_').take(200)
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
            errorMessage = context.getString(R.string.browser_batch_prepare_error, e.message.orEmpty())
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "renameFile: ${e.message}")
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.browser_rename_failure, e.message.orEmpty())
                }
            }
        }
    }

    fun createFolder(context: Context, folderName: String) {
        // P2-5: chup snapshot thu muc DANG DUYET (currentUrl) truoc khi launch —
        // ban cu dung WebDavManager.currentBaseUrl (root) nen tao nham o root
        // khi dang o thu muc con. Refresh sau thanh cong de hien thu muc moi.
        val destDir = currentUrl.ifBlank { WebDavManager.currentBaseUrl }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sep = if (destDir.endsWith("/")) "" else "/"
                val encodedName = encodeWebDavSegment(folderName)
                val targetUrl = destDir + sep + encodedName + "/"
                WebDavManager.createFolder(targetUrl)
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("FileBrowser", "createFolder: ${e.message}")
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.browser_create_folder_failure, e.message.orEmpty())
                }
            }
        }
    }

    fun restoreFile(context: Context, file: NasFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val trashMetaDao = NasApplication.instance.database.trashMetaDao()
            val meta = runCatching { trashMetaDao.findByTrashPath(file.path) }.getOrNull()
            // P2-6: thieu metadata -> dung ham restore chung (tach drive + ten,
            // khong phai removePrefix). Neu dich trung nguon (URL tuyet doi,
            // khong xac dinh duoc goc) -> BAO LOI ro, khong MOVE ve chinh no.
            val targetUrl = meta?.originalPath
                ?: buildWebDavRestoreTargetUrl(
                    WebDavManager.currentBaseUrl, file.path, file.name, file.isDirectory)
            if (targetUrl == file.path) {
                com.nas.naswebdav.utils.SystemLogger.log("ERROR", "FileBrowser",
                    "Khôi phục thất bại (${file.path}): thiếu metadata gốc, không xác định được đích.")
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.browser_restore_failure, "thiếu thông tin gốc")
                }
                return@launch
            }
            try {
                WebDavManager.renameFile(file.path, targetUrl)
                trashMetaDao.deleteByTrashPath(file.path)
                refresh()
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.browser_restore_failure, e.message.orEmpty())
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