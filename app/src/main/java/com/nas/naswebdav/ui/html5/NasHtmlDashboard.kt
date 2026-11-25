package com.nas.naswebdav.ui.html5

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.nas.naswebdav.*
import com.nas.naswebdav.shared.SharedStateHolder
import com.nas.naswebdav.ui.screens.dashboardDiskMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTML5 WebView Dashboard Bridge (Dark Slate + Glassmorphism + Cyan/Green/Yellow/Red).
 * Dung chung cho 4 tab: dashboard, apps, system, more.
 * Giu nguyen Native cho FileBrowser, MediaViewer, Login va AutoBackup Worker.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NasHtmlDashboardScreen(
    viewName: String,
    onAction: (String, String?) -> Unit,
    onAccountAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sysMonitorVM = LocalSystemMonitorVM.current
    val deviceVM = LocalDeviceManagementVM.current
    val smartToolsVM = LocalSmartToolsVM.current
    val autoBackupVM = LocalAutoBackupVM.current
    val fileBrowserVM = LocalFileBrowserVM.current
    val liveVM = LocalLivestreamVM.current
    val authVM = LocalAuthSessionVM.current
    val connStatus by SharedStateHolder.connectionStatus.collectAsState()
    val thumbAudit by smartToolsVM.thumbnailAudit.collectAsState()
    val session = WebDavManager.currentAuthState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var htmlModalOpen by remember { mutableStateOf(false) }
    var filePageRequest by remember { mutableStateOf(Html5FilePageRequest()) }
    BackHandler(enabled = htmlModalOpen && pageLoaded && webViewRef != null) {
        webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.closeTopModal();", null)
    }
    val uiScope = rememberCoroutineScope()

    val torrentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            uploadTorrentFileToNas(uiScope, context, uri)
            Toast.makeText(context, "Đang gửi file .torrent lên NAS...", Toast.LENGTH_SHORT).show()
        }
    }

    data class UploadSelection(val id: String, val uri: android.net.Uri, val folder: String, val auth: WebDavManager.AuthState)
    val failedSelections = remember { mutableMapOf<String, UploadSelection>() }
    var uploadBusy by remember { mutableStateOf(false) }

    fun publishUpload(id: String, status: String, progress: Int = 0, name: String? = null) {
        val payload = JSONObject().put("id", id).put("status", status).put("progress", progress)
        if (name != null) payload.put("name", name)
        webViewRef?.evaluateJavascript("window.NasUpload032 && window.NasUpload032.update($payload);", null)
    }

    fun uploadSelections(selections: List<UploadSelection>) {
        if (uploadBusy || selections.isEmpty()) return
        uploadBusy = true
        uiScope.launch {
            var changed = false
            try {
                selections.forEach { publishUpload(it.id, "waiting") }
                for (selection in selections) {
                    val (id, uri, folder, auth) = selection
                    failedSelections.remove(id)
                    val view = webViewRef
                    val outcome = withContext(Dispatchers.IO) {
                        if (WebDavManager.currentAuthState() != auth) return@withContext "unavailable"
                        WebDavManager.threadLocalAuth.set(auth)
                        var tempFile: java.io.File? = null
                        try {
                            val rawName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            } ?: throw java.io.IOException("Missing filename")
                            view?.post { publishUpload(id, "uploading", name = rawName) }
                            val safeName = rawName.replace('/', '_').replace('\\', '_').replace("..", "_").take(180)
                            val encoded = java.net.URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")
                            val destUrl = "$folder/$encoded"
                            if (WebDavManager.fileExists(destUrl)) return@withContext "skipped"
                            val temp = java.io.File.createTempFile("webdav-upload-", ".part", context.cacheDir)
                            tempFile = temp
                            val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open source")
                            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            var lastPercent = 0
                            temp.inputStream().use { source ->
                                WebDavManager.uploadStreamWithProgress(destUrl, source, temp.length(), mime, { written, total ->
                                    val percent = if (total > 0) (written.toDouble() / total * 100).toInt().coerceIn(0, 99) else 0
                                    if (percent > lastPercent) {
                                        lastPercent = percent
                                        view?.post { publishUpload(id, "uploading", percent) }
                                    }
                                }, createOnly = true)
                            }
                            FileMutationSync.notifyOrEnqueue(context.applicationContext, auth.baseUrl, auth.authHeader, "upload", destUrl, destUrl)
                            "done"
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: java.nio.file.FileAlreadyExistsException) {
                            "skipped"
                        } catch (_: Exception) {
                            "failed"
                        } finally {
                            tempFile?.delete()
                            WebDavManager.threadLocalAuth.remove()
                        }
                    }
                    if (outcome == "failed") failedSelections[id] = selection
                    if (outcome == "done") changed = true
                    publishUpload(id, outcome, if (outcome == "done") 100 else 0)
                }
            } finally {
                uploadBusy = false
                webViewRef?.evaluateJavascript("window.NasUpload032 && window.NasUpload032.finish();", null)
                if (changed) fileBrowserVM.refresh()
            }
        }
    }

    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val auth = WebDavManager.currentAuthState()
        val targetFolder = fileBrowserVM.currentUrl.ifBlank { auth.baseUrl }.trimEnd('/')
        if (uris.isEmpty()) {
            webViewRef?.evaluateJavascript("window.NasUpload032 && window.NasUpload032.finish();", null)
        } else {
            uploadSelections(uris.map { UploadSelection(java.util.UUID.randomUUID().toString(), it, targetFolder, auth) })
        }
    }

    // Initial data fetch on screen mount
    LaunchedEffect(viewName) {
        try { authVM.ensureSmartNetwork(context) } catch (_: Exception) {}
        if (viewName == "files") return@LaunchedEffect
        withContext(Dispatchers.IO) { sysMonitorVM.fetchStatusNow() }
        sysMonitorVM.fetchStorageSummary()
        sysMonitorVM.fetchMetricsHistory(sysMonitorVM.metricsHours)
        deviceVM.fetchSmartData()
        deviceVM.loadSystemLogs()
        smartToolsVM.fetchThumbnailAudit()
        if (viewName == "dashboard" || viewName == "system") {
            sysMonitorVM.fetchSystemProcesses(context)
        }
    }

    // Periodic refresh while HTML5 UI is active
    LaunchedEffect(viewName, pageLoaded) {
        if (!pageLoaded) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.setView('$viewName');", null)
        if (viewName == "files") return@LaunchedEffect
        smartToolsVM.fetchThumbStatus()
        liveVM.refreshLivestreamState(context)
        deviceVM.fetchUsbImportStatus(compact = true, minIntervalMs = 5_000L)
        var tick = 0
        while (isActive) {
            if (tick % 2 == 0) {
                withContext(Dispatchers.IO) { sysMonitorVM.fetchStatusNow() }
            }
            if (smartToolsVM.thumbRunning || smartToolsVM.thumbPaused || tick % 5 == 0) {
                smartToolsVM.fetchThumbStatus()
            }
            if ((liveVM.activeLivestreams.isNotEmpty() && tick % 2 == 0) || tick % 5 == 0) {
                liveVM.fetchLivestreamStatusOnly(context)
            }
            if (tick % 3 == 0 && (smartToolsVM.nasCleanupActive || smartToolsVM.isScanningDuplicates || smartToolsVM.isWorkerRunning)) {
                smartToolsVM.fetchDuplicateSchedule()
            }
            val usbStatus = deviceVM.usbImportState.status
            if (usbStatus == "copying" || usbStatus == "cancelling" || tick % 5 == 0) {
                deviceVM.fetchUsbImportStatus(compact = true, minIntervalMs = 2_000L)
            }
            if (tick % 10 == 0) {
                deviceVM.loadSystemLogs()
            }
            if (tick % 2 == 0) {
                autoBackupVM.fetchNasBackupSessions()
            }
            if (tick % 15 == 0) {
                smartToolsVM.fetchThumbnailAudit()
                sysMonitorVM.fetchMetricsHistory(sysMonitorVM.metricsHours)
            }
            val payload = buildHtml5StateJson(
                context = context,
                authVM = authVM,
                sysMonitorVM = sysMonitorVM,
                deviceVM = deviceVM,
                autoBackupVM = autoBackupVM,
                liveVM = liveVM,
                smartToolsVM = smartToolsVM,
                thumbAudit = thumbAudit,
                connStatus = connStatus.message
            )
            webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.updateState($payload);", null)
            tick++
            delay(2000L)
        }
    }

    // Immediate push when key ViewModel snapshots change
    LaunchedEffect(
        pageLoaded,
        sysMonitorVM.systemStatus,
        sysMonitorVM.storageSummary,
        sysMonitorVM.metricsHistory.size,
        sysMonitorVM.lastHistoryRefreshAt,
        sysMonitorVM.metricsHours,
        sysMonitorVM.isLoadingMetrics,
        deviceVM.systemLogs.size,
        deviceVM.dockerContainers,
        deviceVM.dockerLoadError,
        deviceVM.isFetchingDocker,
        deviceVM.usbImportState,
        deviceVM.usbImportMessage,
        deviceVM.isUsbImportLoading,
        smartToolsVM.dupServerStatus,
        deviceVM.smartInfo,
        session,
        sysMonitorVM.systemProcesses,
        thumbAudit,
        connStatus,
        liveVM.activeLivestreams.size,
        liveVM.livestreamMessage,
        liveVM.isStartingLivestream,
        liveVM.tiktokLiveWatchUsers,
        autoBackupVM.isAutoBackupRunning,
        autoBackupVM.autoBackupOutcome,
        deviceVM.isTestingSpeed,
        deviceVM.speedTestResult,
        autoBackupVM.autoBackupProcessedCount,
        autoBackupVM.autoBackupCurrentFile,
        autoBackupVM.autoBackupProgress,
        autoBackupVM.autoBackupIsPaused,
        autoBackupVM.autoBackupImageProcessedCount,
        autoBackupVM.autoBackupImageSpeedBps,
        autoBackupVM.autoBackupVideoProcessedCount,
        autoBackupVM.autoBackupVideoSpeedBps,
        autoBackupVM.autoBackupVideoFileProgress,
        autoBackupVM.autoBackupVideoBytesWritten,
        autoBackupVM.autoBackupMode,
        autoBackupVM.phoneStorageFreeBytes,
        autoBackupVM.storageFreedSessionBytes,
        autoBackupVM.storageFreedTotalBytes,
        autoBackupVM.nasQueuePosition,
        autoBackupVM.nasBackupSessions,
        smartToolsVM.duplicateFilesList.size,
        smartToolsVM.isScanningDuplicates,
        smartToolsVM.isDeletingDuplicates,
        smartToolsVM.dupDeleteProcessed,
        smartToolsVM.dupDeleteDone,
        smartToolsVM.dupDeleteCurrentFile,
        smartToolsVM.dupSchedEnabled,
        smartToolsVM.dupSchedHour,
        smartToolsVM.dupSchedIntervalDays,
        smartToolsVM.dupSchedLastRunAt,
        smartToolsVM.organizerScanning,
        smartToolsVM.organizerExecuting,
        smartToolsVM.organizerScanResult,
        smartToolsVM.organizerResult,
        smartToolsVM.organizerError
    ) {
        if (!pageLoaded) return@LaunchedEffect
        val payload = buildHtml5StateJson(
            context = context,
            authVM = authVM,
            sysMonitorVM = sysMonitorVM,
            deviceVM = deviceVM,
            autoBackupVM = autoBackupVM,
            liveVM = liveVM,
            smartToolsVM = smartToolsVM,
            thumbAudit = thumbAudit,
            connStatus = connStatus.message
        )
        webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.updateState($payload);", null)
    }

    if (pageLoaded && viewName == "files") {
        val files = fileBrowserVM.fileList
        val folder = fileBrowserVM.currentUrl
        val title = fileBrowserVM.specialTitle.takeIf { fileBrowserVM.isSpecialMode }.orEmpty()
        val loading = fileBrowserVM.isLoading
        val sort = fileBrowserVM.fileSort.collectAsState().value
        val request = filePageRequest
        val page = request.page.takeIf { request.folder == folder && request.baseUrl == session.baseUrl &&
            request.user == session.user } ?: 0
        LaunchedEffect(webViewRef, session, files, folder, title, loading, sort, request) {
            val payload = withContext(Dispatchers.Default) {
                buildHtml5FilesJson(files, sort, folder, title, loading, session, request, page)
            }
            if (session == WebDavManager.currentAuthState() && folder == fileBrowserVM.currentUrl &&
                files === fileBrowserVM.fileList && request == filePageRequest) {
                webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.updateFiles($payload);", null)
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            if (com.nas.naswebdav.BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.parseColor("#0B1120"))
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    allowFileAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onModalVisibilityChanged(open: Boolean) {
                        post { htmlModalOpen = open }
                    }

                    @JavascriptInterface
                    fun action(name: String) {
                        post {
                            when (name) {
                                "appSettings", "logout" -> onAccountAction(name)
                                else -> onAction(name, null)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun search(query: String) {
                        post { onAction("search", query) }
                    }

                    @JavascriptInterface
                    fun refreshStorage() {
                        post { sysMonitorVM.fetchStorageSummary(force = true) }
                    }

                    @JavascriptInterface
                    fun setFanMode(mode: String) {
                        post {
                            deviceVM.setFanMode(mode) {
                                uiScope.launch(Dispatchers.IO) { sysMonitorVM.fetchStatusNow() }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun setFanCustom(onTemp: Double, offTemp: Double) {
                        post {
                            deviceVM.setFanMode("custom", onTemp.toFloat(), offTemp.toFloat()) {
                                uiScope.launch(Dispatchers.IO) { sysMonitorVM.fetchStatusNow() }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun setMetricsRange(hours: Int) {
                        post {
                            sysMonitorVM.selectMetricsHours(hours)
                        }
                    }

                    @JavascriptInterface
                    fun thumbControl(act: String) {
                        post {
                            when (act) {
                                "pause" -> if (!smartToolsVM.thumbPaused) smartToolsVM.toggleThumbPause()
                                "resume" -> if (smartToolsVM.thumbPaused) smartToolsVM.toggleThumbPause()
                                else -> smartToolsVM.fetchThumbnailAudit()
                            }
                        }
                    }

                    @JavascriptInterface
                    fun openFile(path: String) {
                        post {
                            val item = fileBrowserVM.fileList.firstOrNull { it.path == path } ?: return@post
                            if (item.isDirectory) {
                                fileBrowserVM.openFolder(item)
                            } else {
                                val lower = item.name.lowercase()
                                when {
                                    lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                                    lower.endsWith(".mov") || lower.endsWith(".ts") || lower.endsWith(".webm") ||
                                    lower.endsWith(".m4v") || lower.endsWith(".flv") -> onAction("openVideo", item.path)
                                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                                    lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                                    lower.endsWith(".heic") || lower.endsWith(".heif") -> onAction("openImage", item.path)
                                    else -> onAction("openImage", item.path)
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun fileBack() {
                        post {
                            if (!fileBrowserVM.goBack()) {
                                onAction("backToMenu", null)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun openFileAncestor(index: Int, expectedFolder: String) {
                        post {
                            if (fileBrowserVM.isSpecialMode || expectedFolder != fileBrowserVM.currentUrl) return@post
                            val crumbs = html5FileBreadcrumbs(WebDavManager.currentAuthState().baseUrl, expectedFolder)
                            if (index !in 0 until crumbs.lastIndex) return@post
                            fileBrowserVM.urlStack.clear()
                            fileBrowserVM.urlStack.addAll(crumbs.take(index).map { it.second })
                            fileBrowserVM.navigateToUrl(crumbs[index].second)
                        }
                    }

                    @JavascriptInterface
                    fun filePage(page: Int, query: String, filter: String, requestId: Int) {
                        post {
                            val currentSession = WebDavManager.currentAuthState()
                            filePageRequest = Html5FilePageRequest(
                                page.coerceAtLeast(0), query.take(512), filter, requestId,
                                fileBrowserVM.currentUrl, currentSession.baseUrl, currentSession.user
                            )
                        }
                    }

                    @JavascriptInterface
                    fun fileRefresh() {
                        post { fileBrowserVM.refresh() }
                    }

                    @JavascriptInterface
                    fun fileSort(mode: String) {
                        post { fileBrowserVM.setFileSort(mode) }
                    }

                    @JavascriptInterface
                    fun fileDelete(path: String) {
                        post {
                            val item = fileBrowserVM.fileList.firstOrNull { it.path == path } ?: return@post
                            fileBrowserVM.deleteFile(context, item)
                        }
                    }

                    @JavascriptInterface
                    fun fileRename(jsonStr: String) {
                        post {
                            val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return@post
                            val path = obj.optString("path", "")
                            val newName = obj.optString("name", "").trim()
                            if (path.isBlank() || newName.isBlank()) return@post
                            val item = fileBrowserVM.fileList.firstOrNull { it.path == path } ?: return@post
                            fileBrowserVM.renameFile(context, item, newName)
                        }
                    }

                    @JavascriptInterface
                    fun fetchProcesses() {
                        post { sysMonitorVM.fetchSystemProcesses(context) }
                    }

                    @JavascriptInterface
                    fun killProcess(pid: Int) {
                        post { sysMonitorVM.killSystemProcess(context, pid) }
                    }

                    @JavascriptInterface
                    fun pickUploadFiles() {
                        post { if (!uploadBusy) uploadPickerLauncher.launch(arrayOf("*/*")) }
                    }

                    @JavascriptInterface
                    fun retryUploadFile(id: String) {
                        post { failedSelections[id]?.let { uploadSelections(listOf(it)) } }
                    }

                    @JavascriptInterface
                    fun fetchTimeline(offset: Int) {
                        uiScope.launch(Dispatchers.IO) {
                            try {
                                val base = WebDavManager.currentBaseUrl.ifBlank { SecurePrefsHelper.getUrl(context) }
                                val apiBase = base.toApiBaseUrl()
                                val req = okhttp3.Request.Builder()
                                    .url("$apiBase/api/photos/timeline?limit=120&offset=$offset")
                                    .let(WebDavManager::tagCurrentAuth)
                                    .build()
                                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                                    val body = resp.body?.string() ?: "{}"
                                    val rawJson = JSONObject(body)
                                    val rawItems = rawJson.optJSONArray("items") ?: JSONArray()
                                    val outItems = JSONArray()
                                    val rootBase = WebDavManager.currentBaseUrl.trimEnd('/')
                                    for (i in 0 until rawItems.length()) {
                                        val it = rawItems.getJSONObject(i)
                                        val p = it.optString("path", "")
                                        val rel = if (p.startsWith("/")) "/webdav$p" else "/webdav/$p"
                                        val cleanRel = rel.replace("//", "/")
                                        val fullUrl = rootBase + "/" + p.trimStart('/')
                                        outItems.put(JSONObject().apply {
                                            put("name", p.substringAfterLast('/'))
                                            put("path", fullUrl)
                                            put("relPath", cleanRel)
                                            put("mtime", it.optLong("mtime", 0L))
                                            put("size", it.optLong("size", 0L))
                                        })
                                    }
                                    val result = JSONObject().apply {
                                        put("total", rawJson.optInt("total", outItems.length()))
                                        put("offset", offset)
                                        put("items", outItems)
                                    }.toString()
                                    withContext(Dispatchers.Main) {
                                        webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.onTimelineLoaded($result);", null)
                                    }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main) {
                                    webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.onTimelineLoaded({total:0,offset:0,items:[]});", null)
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun fileDeleteBatch(pathsJson: String) {
                        post {
                            val arr = runCatching { JSONArray(pathsJson) }.getOrNull() ?: return@post
                            val pathSet = mutableSetOf<String>()
                            for (i in 0 until arr.length()) pathSet.add(arr.optString(i))
                            val items = fileBrowserVM.fileList.filter { it.path in pathSet }
                            if (items.isNotEmpty()) fileBrowserVM.deleteMultipleFiles(context, items)
                        }
                    }

                    @JavascriptInterface
                    fun fileDownloadBatch(pathsJson: String) {
                        val arr = runCatching { JSONArray(pathsJson) }.getOrNull() ?: return
                        val paths = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                        if (paths.isEmpty()) return
                        uiScope.launch(Dispatchers.IO) {
                            val dlDir = java.io.File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "NASWebDAV"
                            ).apply { mkdirs() }
                            val canonicalDlDir = dlDir.canonicalFile
                            var okCount = 0
                            val activeBase = WebDavManager.currentBaseUrl.trimEnd('/')
                            paths.forEach { rawItem ->
                                try {
                                    val targetUrl = if (rawItem.startsWith("http://", ignoreCase = true) || rawItem.startsWith("https://", ignoreCase = true)) {
                                        rawItem
                                    } else {
                                        "$activeBase/${rawItem.trimStart('/')}"
                                    }
                                    val rawName = targetUrl.substringAfterLast('/').substringBefore('?').ifBlank { "nas_${System.currentTimeMillis()}" }
                                    val decodedName = runCatching { java.net.URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
                                    val safeName = java.io.File(decodedName).name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "nas_${System.currentTimeMillis()}"
                                    val targetFile = java.io.File(dlDir, safeName).canonicalFile
                                    if (targetFile.parentFile?.canonicalFile?.equals(canonicalDlDir) != true) return@forEach
                                    val req = okhttp3.Request.Builder().url(targetUrl).let(WebDavManager::tagCurrentAuth).build()
                                    NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            resp.body?.byteStream()?.use { inp ->
                                                targetFile.outputStream().use { out -> inp.copyTo(out) }
                                                okCount++
                                            }
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {}
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Đã tải $okCount/${paths.size} tệp vào Downloads/NASWebDAV", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    @JavascriptInterface
                    fun createShareLink(relPath: String) {
                        uiScope.launch(Dispatchers.IO) {
                            try {
                                val base = WebDavManager.currentBaseUrl.ifBlank { SecurePrefsHelper.getUrl(context) }
                                val apiBase = base.toApiBaseUrl()
                                val body = JSONObject().put("path", relPath).put("hours", 24).toString()
                                val req = okhttp3.Request.Builder()
                                    .url("$apiBase/api/share/create")
                                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                                    .let(WebDavManager::tagCurrentAuth)
                                    .build()
                                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                                    val txt = resp.body?.string() ?: "{}"
                                    val j = JSONObject(txt)
                                    val relUrl = j.optString("url", "")
                                    if (!resp.isSuccessful || relUrl.isBlank()) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, j.optString("error", "Không thể tạo link chia sẻ"), Toast.LENGTH_SHORT).show()
                                        }
                                        return@use
                                    }
                                    val fullLink = "$apiBase$relUrl"
                                    withContext(Dispatchers.Main) {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("NAS Share Link", fullLink))
                                        Toast.makeText(context, "Đã sao chép link chia sẻ (hiệu lực 24h)", Toast.LENGTH_SHORT).show()
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, fullLink)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ liên kết tệp NAS"))
                                    }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Lỗi tạo link: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun saveAlertRules(jsonStr: String) {
                        post {
                            val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return@post
                            val repo = com.nas.naswebdav.utils.PreferencesRepository.get(context)
                            val cur = repo.getAlertRules()
                            repo.setAlertRules(
                                cur.copy(
                                    enabled = obj.optBoolean("enabled", cur.enabled),
                                    hddTempThreshold = obj.optInt("hddTemp", cur.hddTempThreshold).coerceIn(35, 75),
                                    cpuThreshold = obj.optInt("cpuTemp", cur.cpuThreshold).coerceIn(50, 99),
                                    diskThreshold = obj.optInt("diskPct", cur.diskThreshold).coerceIn(60, 99),
                                    smartAlert = obj.optBoolean("smart", cur.smartAlert)
                                )
                            )
                            scheduleNasAlertWorker(context)
                        }
                    }

                    @JavascriptInterface
                    fun setBiometric(enabled: Boolean) {
                        post {
                            com.nas.naswebdav.utils.PreferencesRepository.get(context).setBiometricEnabled(enabled)
                        }
                    }

                    @JavascriptInterface
                    fun saveAutoBackup(enabled: Boolean, deleteAfter: Boolean, backupMode: String = "both") {
                        post {
                            val prefs = com.nas.naswebdav.utils.PreferencesRepository.get(context)
                            prefs.setBackupMode(backupMode)
                            prefs.setDeleteAfterBackup(deleteAfter)
                            com.nas.naswebdav.AutoBackupState.deleteAfter.value = deleteAfter
                            autoBackupVM.setBackupModeImmediate(backupMode)
                            autoBackupVM.refreshPhoneStorage()
                            if (enabled) {
                                if (!com.nas.naswebdav.utils.MediaPermissionHelper.canRunPeriodicBackup(context)) {
                                    Toast.makeText(context, "Chưa cấp quyền toàn bộ Ảnh/Video trong Cài đặt Android!", Toast.LENGTH_LONG).show()
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.fromParts("package", context.packageName, null)
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    return@post
                                }
                                prefs.setAutoBackupFlag(true)
                                val constraints = androidx.work.Constraints.Builder()
                                    .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                                    .setRequiresCharging(true)
                                    .build()
                                val req = androidx.work.PeriodicWorkRequestBuilder<AutoBackupWorker>(12, java.util.concurrent.TimeUnit.HOURS)
                                    .setConstraints(constraints)
                                    .setInputData(
                                        androidx.work.workDataOf(
                                            "delete_after_backup" to deleteAfter,
                                            "backup_mode" to prefs.getBackupMode()
                                        )
                                    )
                                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30L, java.util.concurrent.TimeUnit.SECONDS)
                                    .addTag("com.nas.naswebdav.AutoBackupWorker")
                                    .build()
                                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                                    "AutoBackupWork",
                                    androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
                                    req
                                )
                                Toast.makeText(context, "Đã lưu cấu hình Auto-Backup!", Toast.LENGTH_SHORT).show()
                            } else {
                                prefs.setAutoBackupFlag(false)
                                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("AutoBackupWork")
                                Toast.makeText(context, "Đã tắt lịch Auto-Backup", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    @JavascriptInterface
                    fun setBackupMode(mode: String) {
                        post {
                            val prefs = com.nas.naswebdav.utils.PreferencesRepository.get(context)
                            prefs.setBackupMode(mode)
                            autoBackupVM.setBackupModeImmediate(prefs.getBackupMode())
                        }
                    }

                    @JavascriptInterface
                    fun setDeleteAfterBackup(deleteAfter: Boolean) {
                        post {
                            com.nas.naswebdav.utils.PreferencesRepository.get(context).setDeleteAfterBackup(deleteAfter)
                            com.nas.naswebdav.AutoBackupState.deleteAfter.value = deleteAfter
                            if (deleteAfter && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                                Toast.makeText(context, "Hãy bật 'Truy cập tất cả tệp' để ứng dụng xóa được ảnh/video sau khi sao lưu!", Toast.LENGTH_LONG).show()
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun requestAllFilesAccess() {
                        post {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun refreshBackupInfo() {
                        post {
                            autoBackupVM.refreshPhoneStorage()
                            autoBackupVM.fetchNasBackupSessions()
                        }
                    }

                    @JavascriptInterface
                    fun triggerAutoBackup() {
                        post {
                            autoBackupVM.triggerManualBackup(context) { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    @JavascriptInterface
                    fun cancelAutoBackup() {
                        post {
                            autoBackupVM.cancelAutoBackup(context)
                            Toast.makeText(context, "Đã gửi lệnh hủy đồng bộ", Toast.LENGTH_SHORT).show()
                        }
                    }

                    @JavascriptInterface
                    fun fetchSmb() {
                        post { deviceVM.fetchSmbStatus() }
                    }

                    @JavascriptInterface
                    fun toggleSmb(enabled: Boolean) {
                        post { deviceVM.toggleSmb(context, enabled) }
                    }

                    @JavascriptInterface
                    fun fetchDocker() {
                        post { deviceVM.loadDockerContainers() }
                    }

                    @JavascriptInterface
                    fun controlDocker(act: String, name: String) {
                        post { deviceVM.controlDockerContainer(act, name) }
                    }

                    @JavascriptInterface
                    fun fetchLogs() {
                        post { deviceVM.loadSystemLogs(minIntervalMs = 0L) }
                    }

                    @JavascriptInterface
                    fun clearLogs() {
                        post { deviceVM.clearSystemLogs() }
                    }

                    @JavascriptInterface
                    fun refreshLivestream() {
                        post {
                            liveVM.refreshLivestreamState(context)
                            liveVM.fetchTikTokLiveWatch(context)
                        }
                    }

                    @JavascriptInterface
                    fun clearLivestreamMessage() {
                        post { liveVM.clearLivestreamMessage() }
                    }

                    @JavascriptInterface
                    fun startLivestream(rawUrl: String, quality: String) {
                        post {
                            val clean = rawUrl.trim()
                            if (clean.isBlank()) return@post
                            val lower = clean.lowercase()
                            val isVod = clean.contains("/video/") || clean.contains("watch?v=") ||
                                (clean.contains("youtu.be") && !lower.contains("/live")) ||
                                lower.contains("/reel") || lower.contains("/shorts/")
                            if (isVod) {
                                liveVM.requestSocialDownload(clean, AppConfig.LIVESTREAM_FOLDER)
                                Toast.makeText(context, "Đã chuyển video sang tải xuống vào Livestream/", Toast.LENGTH_SHORT).show()
                            } else {
                                liveVM.startLivestreamRecord(clean, quality.ifBlank { "best" })
                            }
                        }
                    }

                    @JavascriptInterface
                    fun stopLivestream(jobId: String) {
                        post { liveVM.stopLivestreamRecord(jobId) }
                    }

                    @JavascriptInterface
                    fun enqueueLivestream(url: String, quality: String, watchUser: String) {
                        post { liveVM.enqueueLivestreamQueue(url.trim(), quality.ifBlank { "best" }, watchUser) }
                    }

                    @JavascriptInterface
                    fun cancelLivestreamQueue(queueId: String) {
                        post { liveVM.cancelLivestreamQueue(queueId) }
                    }

                    @JavascriptInterface
                    fun addTikTokWatch(username: String) {
                        val clean = username.trim().removePrefix("@")
                        if (clean.isNotBlank()) post { liveVM.addTikTokLiveWatchUser(clean) }
                    }

                    @JavascriptInterface
                    fun removeTikTokWatch(username: String) {
                        post { liveVM.removeTikTokLiveWatchUser(username) }
                    }

                    @JavascriptInterface
                    fun saveTikTokWatchSchedule(enabled: Boolean, start: String, end: String) {
                        post { liveVM.updateTikTokLiveWatchSettings(enabled, start, end) }
                    }

                    @JavascriptInterface
                    fun getClipboardText(): String {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        return cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    }

                    @JavascriptInterface
                    fun createGuestPass(minutes: Int) {
                        post { authVM.createGuestPass(minutes) }
                    }

                    @JavascriptInterface
                    fun revokeGuestPass() {
                        post {
                            authVM.revokeGuestPass(
                                onSuccess = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
                                onWarning = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                            )
                        }
                    }

                    @JavascriptInterface
                    fun downloadSocialVideo(url: String, folder: String) {
                        val clean = url.trim()
                        if (clean.isNotBlank()) {
                            post { liveVM.requestSocialDownload(clean, folder.ifBlank { "Downloads" }) }
                        }
                    }

                    @JavascriptInterface
                    fun runSpeedTest() {
                        post { deviceVM.runSpeedTest() }
                    }

                    @JavascriptInterface
                    fun fetchLanWhitelist() {
                        post { deviceVM.loadLanWhitelist() }
                    }

                    @JavascriptInterface
                    fun addLanWhitelist(entry: String) {
                        val clean = entry.trim()
                        if (clean.isNotBlank()) post { deviceVM.addLanWhitelistEntry(clean) }
                    }

                    @JavascriptInterface
                    fun removeLanWhitelist(entry: String, isSubnet: Boolean) {
                        post { deviceVM.removeLanWhitelistEntry(entry, isSubnet) }
                    }

                    @JavascriptInterface
                    fun setBandwidthLimit(bps: Long) {
                        post {
                            com.nas.naswebdav.utils.PreferencesRepository.get(context).setUploadSpeedLimit(bps)
                            Toast.makeText(context, if (bps <= 0L) "Đã bỏ giới hạn băng thông" else "Đã đặt giới hạn: ${bps / (1024 * 1024)} MB/s", Toast.LENGTH_SHORT).show()
                        }
                    }

                    @JavascriptInterface
                    fun fetchNasBackups() {
                        post { sysMonitorVM.fetchNasConfigBackups() }
                    }

                    @JavascriptInterface
                    fun createNasBackup() {
                        post { sysMonitorVM.createNasConfigBackup() }
                    }

                    @JavascriptInterface
                    fun restoreNasBackup(filename: String) {
                        post { sysMonitorVM.restoreNasConfigBackup(filename) }
                    }

                    @JavascriptInterface
                    fun deleteNasBackup(filename: String) {
                        post { sysMonitorVM.deleteNasConfigBackup(filename) }
                    }

                    @JavascriptInterface
                    fun fetchFailedUploads() {
                        post { deviceVM.loadFailedUploads(context) }
                    }

                    @JavascriptInterface
                    fun retryFailedUpload(id: Int) {
                        post { deviceVM.retryFailedUpload(context, id) }
                    }

                    @JavascriptInterface
                    fun retryAllFailedUploads() {
                        post { deviceVM.retryAllFailedUploads(context) }
                    }

                    @JavascriptInterface
                    fun fetchUsbImport() {
                        post { deviceVM.fetchUsbImportStatus() }
                    }

                    @JavascriptInterface
                    fun fetchSleepSchedule() {
                        post { autoBackupVM.fetchSleepSchedule() }
                    }

                    @JavascriptInterface
                    fun saveSleepSchedule(enabled: Boolean, mode: String, startHour: Int, endHour: Int, idleOnly: Boolean) {
                        post {
                            autoBackupVM.saveSleepSchedule(
                                SleepSchedule(
                                    enabled = enabled,
                                    mode = mode.ifBlank { "spindown" },
                                    startHour = startHour.coerceIn(0, 23),
                                    endHour = endHour.coerceIn(0, 23),
                                    idleOnly = idleOnly
                                )
                            )
                        }
                    }

                    @JavascriptInterface
                    fun sendDownloadLink(url: String) {
                        val clean = url.trim()
                        if (clean.isNotBlank()) {
                            post {
                                sendDownloadLinkToQbittorrent(uiScope, clean)
                                Toast.makeText(context, "Đã gửi link tải xuống tới qBittorrent", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    @JavascriptInterface
                    fun pickTorrentFile() {
                        post { torrentPickerLauncher.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }
                    }

                    @JavascriptInterface
                    fun startDuplicateScan(forceRestart: Boolean, lightningMode: Boolean) {
                        post {
                            smartToolsVM.startBackgroundDuplicateScan(context, forceRestart = forceRestart, lightningMode = lightningMode)
                        }
                    }

                    @JavascriptInterface
                    fun refreshDuplicateSchedule() {
                        post { smartToolsVM.fetchDuplicateSchedule(forceReload = true) }
                    }

                    @JavascriptInterface
                    fun saveDuplicateSchedule(enabled: Boolean, hour: Int, intervalDays: Int, autoClean: Boolean = false) {
                        post { smartToolsVM.saveDuplicateSchedule(enabled, hour, intervalDays, autoClean, context) }
                    }

                    @JavascriptInterface
                    fun autoCleanAllDuplicates(filter: String) {
                        post { smartToolsVM.autoCleanAllDuplicates(filter) }
                    }

                    @JavascriptInterface
                    fun deleteDuplicateFiles(pathsJson: String) {
                        post {
                            runCatching {
                                val arr = JSONArray(pathsJson)
                                val targets = mutableSetOf<String>()
                                for (i in 0 until arr.length()) {
                                    val p = arr.optString(i)
                                    if (p.isNotBlank()) targets.add(p)
                                }
                                val toDelete = smartToolsVM.duplicateFilesList.filter { it.path in targets }
                                if (toDelete.isNotEmpty()) {
                                    smartToolsVM.deleteSelectedDuplicates(toDelete)
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun cancelDuplicateDeletion() {
                        post { smartToolsVM.cancelDuplicateDeletion() }
                    }

                    @JavascriptInterface
                    fun openDuplicateMedia(path: String) {
                        post {
                            runCatching {
                                val auth = WebDavManager.currentAuthState()
                                val lower = path.lowercase()
                                val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                                        lower.endsWith(".mov") || lower.endsWith(".ts") || lower.endsWith(".webm") ||
                                        lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".3gp")
                                val url = if (path.startsWith("http://") || path.startsWith("https://")) path else {
                                    val cleanPath = path.trimStart('/')
                                    val base = WebDavManager.currentBaseUrl.trimEnd('/')
                                    "$base/$cleanPath"
                                }
                                if (isVideo) {
                                    com.nas.naswebdav.ui.screens.openExternalVideoPlayer(
                                        context = context,
                                        url = url,
                                        user = auth.user,
                                        pass = auth.pass,
                                        onError = {
                                            onAction("openVideo", url)
                                        }
                                    )
                                } else {
                                    onAction("openImage", url)
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun smartOrganizeScan(filter: String, path: String) {
                        post {
                            val f = when (filter.lowercase()) {
                                "image" -> OrganizerFilter.IMAGE
                                "video" -> OrganizerFilter.VIDEO
                                else -> OrganizerFilter.ALL
                            }
                            smartToolsVM.smartOrganizeScan(f, path.trim())
                        }
                    }

                    @JavascriptInterface
                    fun smartOrganizeExecute(filter: String, path: String) {
                        post {
                            val f = when (filter.lowercase()) {
                                "image" -> OrganizerFilter.IMAGE
                                "video" -> OrganizerFilter.VIDEO
                                else -> OrganizerFilter.ALL
                            }
                            smartToolsVM.smartOrganizeExecute(f, path.trim())
                        }
                    }

                    @JavascriptInterface
                    fun toggleThumbPause() {
                        post { smartToolsVM.toggleThumbPause() }
                    }

                    @JavascriptInterface
                    fun refreshThumbStatus() {
                        post { smartToolsVM.fetchThumbStatus() }
                    }

                    @JavascriptInterface
                    fun togglePauseDuplicateScan() {
                        post { smartToolsVM.togglePauseDuplicateScan() }
                    }

                    @JavascriptInterface
                    fun cancelDuplicateScan() {
                        post { smartToolsVM.cancelDuplicateScan(context) }
                    }

                    @JavascriptInterface
                    fun startUsbImport() {
                        post { deviceVM.startUsbImportNow() }
                    }

                    @JavascriptInterface
                    fun cancelUsbImport() {
                        post { deviceVM.cancelUsbImport() }
                    }

                    @JavascriptInterface
                    fun toggleAutoBackupPause() {
                        post {
                            com.nas.naswebdav.AutoBackupState.isPaused.value =
                                !com.nas.naswebdav.AutoBackupState.isPaused.value
                        }
                    }
                }, "NasBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return request?.let(DashboardMediaLoader::intercept)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded = true
                    }
                }
                loadUrl("file:///android_asset/html5/index.html")
                webViewRef = this
            }
        }
    )
}

private fun html5FileBreadcrumbs(baseUrl: String, folderUrl: String): List<Pair<String, String>> = runCatching {
    val base = java.net.URI(baseUrl)
    val folder = java.net.URI(folderUrl.ifBlank { baseUrl })
    if (!base.isAbsolute || base.host == null || !base.scheme.equals(folder.scheme, true) ||
        !base.rawAuthority.equals(folder.rawAuthority, true) || folder.rawQuery != null || folder.rawFragment != null
    ) return@runCatching emptyList()
    val root = base.rawPath.trimEnd('/') + "/"
    val path = folder.rawPath.trimEnd('/') + "/"
    if (!path.startsWith(root)) return@runCatching emptyList()
    val rootUrl = baseUrl.trimEnd('/') + "/"
    var currentUrl = rootUrl
    val result = mutableListOf("WebDAV" to rootUrl)
    for (segment in path.removePrefix(root).split('/').filter { it.isNotEmpty() }) {
        val name = java.net.URI("/$segment").path.substring(1)
        if (name == "." || name == ".." || '/' in name || '\\' in name) return@runCatching emptyList()
        currentUrl += "$segment/"
        result.add(name to currentUrl)
    }
    result
}.getOrDefault(emptyList())

private data class Html5FilePageRequest(
    val page: Int = 0,
    val query: String = "",
    val filter: String = "all",
    val id: Int = 0,
    val folder: String = "",
    val baseUrl: String = "",
    val user: String = ""
)

private fun buildHtml5FilesJson(
    files: List<NasFile>,
    sort: String,
    folder: String,
    specialTitle: String,
    loading: Boolean,
    session: WebDavManager.AuthState,
    request: Html5FilePageRequest,
    requestedPage: Int
): String {
    val directoriesFirst = compareByDescending<NasFile> { it.isDirectory }
    val order = when (sort) {
        "date" -> directoriesFirst.thenByDescending { it.lastModified }
        "size" -> directoriesFirst.thenByDescending { it.contentLength }
        else -> directoriesFirst.thenBy { it.name.lowercase() }
    }
    val matches = files.filter { file ->
        file.name.contains(request.query, ignoreCase = true) && when (request.filter) {
            "dir" -> file.isDirectory
            "image" -> !file.isDirectory &&
                (com.nas.naswebdav.utils.MediaUtils.isImage(file.name) || file.name.endsWith(".heif", ignoreCase = true))
            "video" -> !file.isDirectory && com.nas.naswebdav.utils.MediaUtils.isVideo(file.name)
            else -> true
        }
    }
    val pageSize = com.nas.naswebdav.browser.DIRECTORY_PREVIEW_SIZE
    val page = requestedPage.coerceIn(0, (matches.size - 1).coerceAtLeast(0) / pageSize)
    val items = JSONArray()
    for (file in matches.sortedWith(order).drop(page * pageSize).take(pageSize)) {
        val relPath = runCatching {
            val path = java.net.URI(file.path).path ?: file.path
            val index = path.indexOf("/webdav/")
            if (index >= 0) path.substring(index) else path
        }.getOrDefault(file.path)
        items.put(JSONObject().apply {
            put("name", file.name)
            put("path", file.path)
            put("relPath", relPath)
            put("isDir", file.isDirectory)
            put("size", file.contentLength.coerceAtLeast(0L))
            put("mtime", file.lastModified / 1000.0)
        })
    }
    val title = specialTitle.ifBlank {
        runCatching { java.net.URI(folder).path ?: "/webdav/" }.getOrDefault("/webdav/")
    }
    val crumbs = if (specialTitle.isBlank()) html5FileBreadcrumbs(session.baseUrl, folder) else emptyList()
    return JSONObject().put("baseUrl", session.baseUrl).put("user", session.user)
        .put("breadcrumbs", JSONArray(crumbs.map { it.first })).put("folder", folder)
        .put("title", title).put("loading", loading).put("items", items)
        .put("totalMatches", matches.size).put("offset", page * pageSize)
        .put("page", page).put("requestId", request.id).toString()
}

private fun buildHtml5StateJson(
    context: android.content.Context,
    authVM: com.nas.naswebdav.auth.AuthSessionViewModel,
    sysMonitorVM: com.nas.naswebdav.monitor.SystemMonitorViewModel,
    deviceVM: com.nas.naswebdav.device.DeviceManagementViewModel,
    autoBackupVM: com.nas.naswebdav.backup.AutoBackupViewModel,
    liveVM: com.nas.naswebdav.livestream.LivestreamViewModel,
    smartToolsVM: com.nas.naswebdav.smarttools.SmartToolsViewModel,
    thumbAudit: ThumbnailAuditData,
    connStatus: String
): String {
    val root = JSONObject()
    val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
    root.put("apiBase", apiBase)
    val connOk = connStatus.contains("Đã kết nối", ignoreCase = true) ||
        connStatus.contains("LAN", ignoreCase = true) ||
        connStatus.contains("Tailscale", ignoreCase = true)
    root.put("connOk", connOk)
    root.put("connLabel", when {
        connStatus.contains("LAN", ignoreCase = true) -> "LAN"
        connStatus.contains("Tailscale", ignoreCase = true) -> "Tailscale"
        else -> connStatus.ifBlank { "NAS" }
    })
    root.put("user", WebDavManager.currentUser)
    root.put("baseUrl", WebDavManager.currentBaseUrl)
    root.put("versionName", runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0"))

    // Storage Summary
    val s = sysMonitorVM.storageSummary
    val stJson = JSONObject().apply {
        put("totalFiles", s.totalFiles)
        put("totalDirs", s.totalDirs)
        put("totalBytes", s.totalBytes)
        put("partial", s.partial)
        put("updatedAtMs", s.updatedAt)
        put("byType", JSONObject().apply {
            put("image", JSONObject().put("files", s.imageFiles).put("bytes", s.imageBytes))
            put("video", JSONObject().put("files", s.videoFiles).put("bytes", s.videoBytes))
            put("doc", JSONObject().put("files", s.docFiles).put("bytes", s.docBytes))
            put("audio", JSONObject().put("files", s.audioFiles).put("bytes", s.audioBytes))
            put("archive", JSONObject().put("files", s.archiveFiles).put("bytes", s.archiveBytes))
            put("other", JSONObject().put("files", s.otherFiles).put("bytes", s.otherBytes))
        })
        val arr = JSONArray()
        s.largest.take(5).forEach { f ->
            arr.put(JSONObject().put("path", f.path).put("bytes", f.bytes))
        }
        put("largest", arr)
    }
    root.put("storage", stJson)

    // System Status
    val sys = sysMonitorVM.systemStatus
    val diskMetric = sys.dashboardDiskMetric()
    val cpuPct = Regex("(\\d+(?:\\.\\d+)?)").find(sys.cpu)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val cpuTemp = Regex("(\\d+(?:\\.\\d+)?)").find(sys.cpuTemp)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val ramPct = sys.ramPercent.toDoubleOrNull() ?: 0.0
    val sysJson = JSONObject().apply {
        put("cpuPercent", cpuPct)
        put("cpuTemp", cpuTemp)
        put("ram", sys.ram)
        put("ramPercent", ramPct)
        put("diskCap", diskMetric?.capacity ?: sys.diskCapacity.ifBlank { "--" })
        put("diskPercent", (diskMetric?.percent ?: 0f).toDouble())
        put("smartStatus", deviceVM.smartInfo.status.ifBlank { "UNKNOWN" })
        put("hddTemp", sys.temp.takeUnless { it.startsWith("--") } ?: deviceVM.smartInfo.temperature)
        put("smartRaw", deviceVM.smartInfo.rawLog)
        put("fanStatus", sys.fanStatus)
        put("fanMode", sys.fanMode)
        put("fanOnTemp", sys.fanOnTemp.toDouble())
        put("fanOffTemp", sys.fanOffTemp.toDouble())
        put("omvInfo", "OpenMediaVault • ${sys.uptime}")
    }
    root.put("system", sysJson)

    // Thumbnail status
    root.put("thumb", JSONObject().apply {
        put("scanProgress", thumbAudit.scanProgress)
        put("scanTotal", thumbAudit.scanTotal)
        put("running", thumbAudit.running)
        put("paused", thumbAudit.paused)
        put("actualStatus", thumbAudit.actualStatus)
    })

    // Auto Backup summary
    root.put("backupSub", if (autoBackupVM.autoBackupCurrentFile.isNotBlank()) {
        "Đang sao lưu: ${autoBackupVM.autoBackupCurrentFile}"
    } else "Chạy nền qua WorkManager Native")

    // Metrics history
    val mArr = JSONArray()
    sysMonitorVM.metricsHistory.forEach { m ->
        mArr.put(JSONObject().apply {
            put("ts", m.timestamp)
            put("cpuPct", m.cpuPercent.toDouble())
            put("ramPct", m.ramPercent.toDouble())
            put("cpuTemp", m.cpuTemp.toDouble())
            put("hddTemp", m.hddTemp.toDouble())
            put("netRx", m.netRxKbps.toDouble())
            put("netTx", m.netTxKbps.toDouble())
        })
    }
    root.put("metrics", mArr)
    root.put("metricsHours", sysMonitorVM.metricsHours)
    root.put("metricsLoading", sysMonitorVM.isLoadingMetrics)
    root.put("metricsError", sysMonitorVM.metricsError ?: "")

    // Alert rules snapshot + live evaluation
    val alertRules = com.nas.naswebdav.utils.PreferencesRepository.get(context).getAlertRules()
    root.put("alerts", JSONObject().apply {
        put("enabled", alertRules.enabled)
        put("hddTemp", alertRules.hddTempThreshold)
        put("cpuTemp", alertRules.cpuThreshold)
        put("diskPct", alertRules.diskThreshold)
        put("smart", alertRules.smartAlert)
    })
    NasAlertEvaluator.evaluateAndNotify(
        context = context,
        cpuPct = cpuPct,
        hddTempC = sys.temp.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0,
        diskPct = (diskMetric?.percent ?: 0f).toDouble(),
        smartStatus = deviceVM.smartInfo.status
    )

    val pArr = JSONArray()
    sysMonitorVM.systemProcesses.forEach { p ->
        pArr.put(JSONObject().apply {
            put("pid", p.pid)
            put("name", p.name)
            put("user", p.user)
            put("cpu", p.cpu.toDouble())
            put("mem", p.mem.toDouble())
            put("isSystem", p.isSystem)
        })
    }
    root.put("processes", pArr)

    val prefs = com.nas.naswebdav.utils.PreferencesRepository.get(context)
    val hasAllFilesAccess = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
    root.put("toolbox", JSONObject().apply {
        put("biometric", prefs.isBiometricEnabled())
        put("autoBackup", prefs.isAutoBackupFlag())
        put("deleteAfter", prefs.isDeleteAfterBackup())
        put("hasAllFilesAccess", hasAllFilesAccess)
        put("backupMode", prefs.getBackupMode())
        put("deviceId", autoBackupVM.phoneDeviceId.ifEmpty { prefs.getDeviceId(context) })
        put("deviceName", autoBackupVM.phoneDeviceName)
        put("phoneFreeBytes", autoBackupVM.phoneStorageFreeBytes)
        put("phoneTotalBytes", autoBackupVM.phoneStorageTotalBytes)
        put("freedSessionBytes", autoBackupVM.storageFreedSessionBytes)
        put("freedTotalBytes", autoBackupVM.storageFreedTotalBytes)
        put("autoBackupRunning", autoBackupVM.isAutoBackupRunning)
        put("autoBackupFile", autoBackupVM.autoBackupCurrentFile)
        put("smbEnabled", deviceVM.isSmbEnabled)
        put("smbLoading", deviceVM.isLoadingSmb)
    })

    val dArr = JSONArray()
    deviceVM.dockerContainers.forEach { d ->
        dArr.put(JSONObject().apply {
            put("id", d.id)
            put("name", d.name)
            put("status", d.status)
        })
    }
    root.put("docker", dArr)
    root.put("dockerLoading", deviceVM.isFetchingDocker)
    root.put("dockerLoadError", deviceVM.dockerLoadError)

    val lArr = JSONArray()
    val timeFmt = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault())
    deviceVM.systemLogs.take(60).forEach { l ->
        lArr.put(JSONObject().apply {
            put("type", l.type)
            put("module", l.module)
            put("message", l.message)
            put("time", timeFmt.format(java.util.Date(l.timestamp)))
        })
    }
    root.put("logs", lArr)

    val activeJobs = JSONArray()
    liveVM.activeLivestreams.forEach { job ->
        activeJobs.put(JSONObject().apply {
            put("jobId", job.jobId)
            put("platform", job.platform)
            put("watchUsername", job.watchUsername)
            put("duration", job.durationSeconds)
            put("startedTs", job.startedTs)
            put("speed", job.speed)
            put("fileSize", job.fileSize)
            put("outputFile", job.outputFile)
        })
    }
    val queueArr = JSONArray()
    liveVM.livestreamQueue.forEach { q ->
        queueArr.put(JSONObject().apply {
            put("queueId", q.queueId)
            put("position", q.position)
            put("url", q.url)
        })
    }
    val watchArr = JSONArray()
    liveVM.tiktokLiveWatchUsers.forEach { u ->
        watchArr.put(JSONObject().apply {
            put("username", u.username)
            put("status", u.status)
            put("lastCheck", u.lastCheck)
            put("lastLive", u.lastLive)
            put("lastError", u.lastError)
        })
    }
    root.put("livestream", JSONObject().apply {
        put("starting", liveVM.isStartingLivestream)
        put("message", liveVM.livestreamMessage)
        put("isError", liveVM.isLivestreamError)
        put("errorCode", liveVM.livestreamErrorCode)
        put("activeCount", liveVM.serverLivestreamActive)
        put("cap", liveVM.serverLivestreamCap)
        put("jobs", activeJobs)
        put("queue", queueArr)
        put("watchUsers", watchArr)
        put("daemonRunning", liveVM.tiktokWatchDaemonRunning)
        put("daemonSummary", liveVM.tiktokWatchDaemonSummary)
        put("excludeEnabled", liveVM.tiktokExcludeEnabled)
        put("excludeStart", liveVM.tiktokExcludeStart)
        put("excludeEnd", liveVM.tiktokExcludeEnd)
        put("cookiesStatus", liveVM.tiktokCookiesStatus)
    })

    // Sub-UI state snapshots (Guest, Social, Perf, Whitelist, Bandwidth, NasBackup, FailedUploads, USB)
    val guest = authVM.activeGuestPass
    val guestTimeFmt = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault())
    root.put("guest", JSONObject().apply {
        put("loading", authVM.isGuestPassLoading)
        put("error", authVM.guestPassError ?: "")
        if (guest != null) {
            put("username", guest.username)
            put("password", guest.password)
            val expMs = if (guest.expiresAt < 10_000_000_000L) guest.expiresAt * 1000L else guest.expiresAt
            put("expiresAt", guestTimeFmt.format(java.util.Date(expMs)))
        }
    })
    val socialHist = JSONArray()
    liveVM.socialDownloadHistory.take(15).forEach { sItem ->
        socialHist.put(JSONObject().apply {
            put("title", sItem.url)
            put("status", if (sItem.isSuccess) "Thành công" else "Thất bại")
            put("folder", sItem.platform)
        })
    }
    root.put("social", JSONObject().apply {
        put("extracting", liveVM.isSocialExtracting)
        put("status", liveVM.socialExtractStatus)
        put("history", socialHist)
    })
    root.put("perf", JSONObject().apply {
        put("testing", deviceVM.isTestingSpeed)
        put("dl", deviceVM.speedTestResult.readSpeed)
        put("ul", deviceVM.speedTestResult.writeSpeed)
        put("latencyMs", sysMonitorVM.apiLatencyMs)
        put("bandwidthLimit", prefs.getUploadSpeedLimit())
    })
    val wlIps = JSONArray(deviceVM.lanWhitelistIps)
    val wlSubs = JSONArray(deviceVM.lanWhitelistSubnets)
    root.put("whitelist", JSONObject().apply {
        put("ips", wlIps)
        put("subnets", wlSubs)
        put("loading", deviceVM.lanWhitelistLoading)
        put("status", deviceVM.lanWhitelistStatus)
        put("error", deviceVM.lanWhitelistError)
    })
    val nbArr = JSONArray()
    sysMonitorVM.nasConfigBackups.forEach { b ->
        nbArr.put(JSONObject().apply {
            put("filename", b.filename)
            put("size", b.sizeHuman)
            put("date", b.createdAt)
        })
    }
    root.put("nasBackup", JSONObject().apply {
        put("items", nbArr)
        put("creating", sysMonitorVM.isCreatingNasConfigBackup)
        put("restoring", sysMonitorVM.isRestoringNasConfigBackup)
        put("message", sysMonitorVM.nasConfigBackupMessage)
    })
    val fuArr = JSONArray()
    deviceVM.failedUploads.forEach { f ->
        fuArr.put(JSONObject().apply {
            put("id", f.id)
            put("name", f.sourcePath.substringAfterLast('/'))
            put("error", f.status)
        })
    }
    root.put("failedUploads", fuArr)
    val usbSt = deviceVM.usbImportState
    val usbRunning = usbSt.status.equals("copying", ignoreCase = true) || usbSt.status.equals("cancelling", ignoreCase = true)
    root.put("usb", JSONObject().apply {
        put("loading", deviceVM.isUsbImportLoading)
        put("message", deviceVM.usbImportMessage.ifBlank { usbSt.message })
        put("running", usbRunning)
        put("phase", usbSt.status)
        put("device", usbSt.detectedDevicesInfo.ifBlank { usbSt.activeDevice })
        put("currentFile", usbSt.currentFile)
        put("filesDone", usbSt.filesDone)
        put("filesTotal", usbSt.filesTotal)
        put("bytesProcessed", usbSt.bytesProcessed)
        put("bytesTotal", usbSt.bytesTotal)
        put("speedBps", usbSt.copySpeedBps)
        put("etaSeconds", usbSt.etaSeconds)
    })
    val sl = autoBackupVM.sleepSchedule
    root.put("sleep", JSONObject().apply {
        put("enabled", sl.enabled)
        put("mode", sl.mode)
        put("startHour", sl.startHour)
        put("endHour", sl.endHour)
        put("idleOnly", sl.idleOnly)
        put("hddState", sl.currentHddState)
        put("inWindow", sl.inWindowNow)
        put("message", autoBackupVM.sleepScheduleMessage)
    })
    val dupStage = DuplicateProgressState.stage.value
    val dupPercent = DuplicateProgressState.percent.value
    val dupPaused = DuplicateProgressState.isPaused.value
    val dupRunning = dupStage != "Khởi động..." && dupStage != "Hoàn tất" && (dupPercent < 1f && dupPercent > 0f || dupStage.contains("Đang phân tích"))
    val dupActive = dupRunning || dupPaused || dupStage == "Đang tổng hợp kết quả..." || smartToolsVM.duplicateFilesList.isNotEmpty()
    val thumbActive = (smartToolsVM.thumbRunning || smartToolsVM.thumbPaused) && smartToolsVM.thumbGenerated < smartToolsVM.thumbTotal && smartToolsVM.thumbTotal > 0
    val imgExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    val vidExts = setOf("mp4", "mkv", "avi", "mov", "mpg", "mpeg", "wmv", "flv", "ts", "m4v", "webm", "3gp")
    val dupGroupsArr = JSONArray()
    val allDupGroups = smartToolsVM.duplicateFilesList
        .filter { f -> f.partialHash?.length == 64 }
        .groupBy { f -> f.partialHash!! }
        .values
        .filter { it.size > 1 }
        .sortedByDescending { (it.firstOrNull()?.contentLength ?: 0L) * (it.size - 1) }

    var imgGroupsCount = 0
    var imgFilesCount = 0
    var imgWastedBytes = 0L

    var vidGroupsCount = 0
    var vidFilesCount = 0
    var vidWastedBytes = 0L

    var otherGroupsCount = 0
    var otherFilesCount = 0
    var otherWastedBytes = 0L

    allDupGroups.forEach { grp ->
        val ext = grp.firstOrNull()?.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val wasted = (grp.firstOrNull()?.contentLength ?: 0L) * (grp.size - 1).coerceAtLeast(0)
        when {
            ext in imgExts -> {
                imgGroupsCount++
                imgFilesCount += grp.size
                imgWastedBytes += wasted
            }
            ext in vidExts -> {
                vidGroupsCount++
                vidFilesCount += grp.size
                vidWastedBytes += wasted
            }
            else -> {
                otherGroupsCount++
                otherFilesCount += grp.size
                otherWastedBytes += wasted
            }
        }
    }

    val totalDupWastedBytes = imgWastedBytes + vidWastedBytes + otherWastedBytes
    val redundantFiles = allDupGroups.sumOf { (it.size - 1).coerceAtLeast(0) }
    val originalFiles = allDupGroups.size
    val redundantImgFiles = allDupGroups.filter { grp ->
        grp.firstOrNull()?.name?.substringAfterLast('.', "")?.lowercase().orEmpty() in imgExts
    }.sumOf { (it.size - 1).coerceAtLeast(0) }
    val redundantVidFiles = allDupGroups.filter { grp ->
        grp.firstOrNull()?.name?.substringAfterLast('.', "")?.lowercase().orEmpty() in vidExts
    }.sumOf { (it.size - 1).coerceAtLeast(0) }
    allDupGroups
        .forEach { grp ->
            val ext = grp.first().name.substringAfterLast('.', "").lowercase()
            val grpType = when {
                ext in imgExts -> "image"
                ext in vidExts -> "video"
                else -> "other"
            }
            val filesArr = JSONArray()
            grp.forEach { f ->
                val fExt = f.name.substringAfterLast('.', "").lowercase()
                val rel = f.path.toNasRelativePath().ifBlank {
                    f.path.substringAfter("/webdav/", "").let { raw ->
                        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    }
                }
                filesArr.put(JSONObject().apply {
                    put("name", f.name)
                    put("path", f.path)
                    put("relPath", rel)
                    put("size", f.contentLength)
                    put("folder", rel.substringBeforeLast('/', "/"))
                    put("isImage", fExt in imgExts)
                    put("isVideo", fExt in vidExts)
                    put("type", when { fExt in imgExts -> "image"; fExt in vidExts -> "video"; else -> "other" })
                })
            }
            dupGroupsArr.put(JSONObject().apply {
                put("size", grp.first().contentLength)
                put("count", grp.size)
                put("wastedBytes", (grp.first().contentLength) * (grp.size - 1))
                put("type", grpType)
                put("files", filesArr)
            })
        }
    root.put("duplicate", JSONObject().apply {
        put("scanning", smartToolsVM.isScanningDuplicates || smartToolsVM.isWorkerRunning || dupRunning)
        put("paused", dupPaused)
        put("stage", dupStage)
        put("stageDesc", DuplicateProgressState.stageDescription.value)
        put("percent", (dupPercent * 100f).toDouble())
        put("scanned", DuplicateProgressState.scannedCount.value)
        put("found", DuplicateProgressState.foundCount.value)
        put("elapsedMs", DuplicateProgressState.elapsedTime.value)
        put("etaMs", DuplicateProgressState.estimatedTimeRemaining.value)
        put("currentFolder", DuplicateProgressState.currentFolder.value.ifBlank { smartToolsVM.scanDuplicatesCurrentFolderUrl })
        put("currentFile", DuplicateProgressState.itemName.value.ifBlank { smartToolsVM.scanDuplicatesCurrentItemName })
        put("count", smartToolsVM.duplicateFilesList.size)
        put("totalGroups", allDupGroups.size)
        put("redundantFiles", redundantFiles)
        put("originalFiles", originalFiles)
        put("redundantImgFiles", redundantImgFiles)
        put("redundantVidFiles", redundantVidFiles)
        put("nasCleanupActive", smartToolsVM.nasCleanupActive)
        put("serverStatus", smartToolsVM.dupServerStatus)
        put("sessionDeletedCount", smartToolsVM.dupSessionDeletedCount)
        put("sessionFreedBytes", smartToolsVM.dupSessionFreedBytes)
        put("wastedBytes", totalDupWastedBytes)
        put("imageGroups", imgGroupsCount)
        put("imageFiles", imgFilesCount)
        put("imageWasted", imgWastedBytes)
        put("videoGroups", vidGroupsCount)
        put("videoFiles", vidFilesCount)
        put("videoWasted", vidWastedBytes)
        put("otherGroups", otherGroupsCount)
        put("otherFiles", otherFilesCount)
        put("otherWasted", otherWastedBytes)
        put("deleting", smartToolsVM.isDeletingDuplicates)
        put("delTotal", smartToolsVM.dupDeleteTotal)
        put("delDone", smartToolsVM.dupDeleteDone)
        put("delProcessed", smartToolsVM.dupDeleteProcessed)
        put("delRemaining", (smartToolsVM.dupDeleteTotal - smartToolsVM.dupDeleteProcessed).coerceAtLeast(0))
        put("delSkipped", smartToolsVM.dupDeleteSkipped)
        put("delBytesFreed", smartToolsVM.dupDeleteBytesFreed)
        put("delCurrentFile", smartToolsVM.dupDeleteCurrentFile)
        put("groups", dupGroupsArr)
        put("schedule", JSONObject().apply {
            put("enabled", smartToolsVM.dupSchedEnabled)
            put("hour", smartToolsVM.dupSchedHour)
            put("intervalDays", smartToolsVM.dupSchedIntervalDays)
            put("autoClean", smartToolsVM.dupSchedAutoClean)
            put("lastRunAt", smartToolsVM.dupSchedLastRunAt)
            put("lastGroups", smartToolsVM.dupSchedLastGroups)
        })
    })
    val orgGroupsArr = JSONArray()
    (smartToolsVM.organizerScanResult ?: emptyList()).forEach { g ->
        orgGroupsArr.put(JSONObject().apply {
            put("label", g.label)
            put("count", g.count)
            put("size", g.size)
            put("samples", JSONArray(g.sampleFiles.take(5)))
        })
    }
    root.put("organizer", JSONObject().apply {
        put("scanning", smartToolsVM.organizerScanning)
        put("executing", smartToolsVM.organizerExecuting)
        put("totalFiles", smartToolsVM.organizerTotalFiles)
        put("result", smartToolsVM.organizerResult ?: "")
        put("error", smartToolsVM.organizerError ?: "")
        put("groups", orgGroupsArr)
    })
    root.put("bgTasks", JSONObject().apply {
        put("thumb", JSONObject().apply {
            put("active", thumbActive)
            put("running", smartToolsVM.thumbRunning)
            put("paused", smartToolsVM.thumbPaused)
            put("generated", smartToolsVM.thumbGenerated)
            put("total", smartToolsVM.thumbTotal)
            put("lastFile", smartToolsVM.thumbLastFile.substringAfterLast('/'))
        })
        put("dup", JSONObject().apply {
            put("active", dupActive)
            put("running", dupRunning)
            put("paused", dupPaused)
            put("stage", dupStage)
            put("stageDesc", DuplicateProgressState.stageDescription.value)
            put("percent", dupPercent.toDouble())
            put("scanned", DuplicateProgressState.scannedCount.value)
            put("found", DuplicateProgressState.foundCount.value)
            put("stageNum", DuplicateProgressState.stageNumber.value)
            put("totalStages", DuplicateProgressState.totalStages.value)
            put("elapsedMs", DuplicateProgressState.elapsedTime.value)
            put("etaMs", DuplicateProgressState.estimatedTimeRemaining.value)
            put("resultCount", smartToolsVM.duplicateFilesList.size)
        })
        put("backup", JSONObject().apply {
            put("active", autoBackupVM.isAutoBackupRunning)
            put("outcome", autoBackupVM.autoBackupOutcome)
            put("currentFile", autoBackupVM.autoBackupCurrentFile)
            put("sourcePath", autoBackupVM.autoBackupSourcePath)
            put("destPath", autoBackupVM.autoBackupDestPath)
            put("progress", autoBackupVM.autoBackupProgress.toDouble())
            put("processed", autoBackupVM.autoBackupProcessedCount)
            put("total", autoBackupVM.autoBackupTotalCount)
            put("speedBps", autoBackupVM.autoBackupUploadSpeedBps)
            put("bytesWritten", autoBackupVM.autoBackupFileBytesWritten)
            put("bytesTotal", autoBackupVM.autoBackupFileBytesTotal)
            put("bytesRemaining", autoBackupVM.autoBackupFileBytesRemaining)
            put("etaSeconds", autoBackupVM.autoBackupEtaSeconds)
            put("isPaused", autoBackupVM.autoBackupIsPaused)
            put("backupMode", autoBackupVM.autoBackupMode)
            put("deviceId", autoBackupVM.phoneDeviceId)
            put("deviceName", autoBackupVM.phoneDeviceName)
            put("phoneFreeBytes", autoBackupVM.phoneStorageFreeBytes)
            put("phoneTotalBytes", autoBackupVM.phoneStorageTotalBytes)
            put("freedSessionBytes", autoBackupVM.storageFreedSessionBytes)
            put("freedTotalBytes", autoBackupVM.storageFreedTotalBytes)
            put("queuePosition", autoBackupVM.nasQueuePosition)
            put("maxConcurrent", autoBackupVM.nasMaxConcurrentDevices)
            put("sessions", JSONArray().apply {
                autoBackupVM.nasBackupSessions.forEach { s ->
                    put(JSONObject().apply {
                        put("deviceId", s.deviceId)
                        put("deviceName", s.deviceName)
                        put("backupMode", s.backupMode)
                        put("status", s.status)
                        put("processed", s.processed)
                        put("total", s.total)
                        put("speedBps", s.speedBps)
                        put("currentFile", s.currentFile)
                        put("freedBytes", s.freedBytes)
                        put("phoneFreeBytes", s.phoneFreeBytes)
                        put("phoneTotalBytes", s.phoneTotalBytes)
                        put("slotGranted", s.slotGranted)
                        put("queuePosition", s.queuePosition)
                    })
                }
            })
            put("images", JSONObject().apply {
                put("currentFile", autoBackupVM.autoBackupImageCurrentFile)
                put("progress", autoBackupVM.autoBackupImageProgress.toDouble())
                put("processed", autoBackupVM.autoBackupImageProcessedCount)
                put("total", autoBackupVM.autoBackupImageTotalCount)
                put("speedBps", autoBackupVM.autoBackupImageSpeedBps)
                put("bytesUploaded", autoBackupVM.autoBackupImageBytesUploaded)
                put("bytesTotal", autoBackupVM.autoBackupImageBytesTotal)
                put("bytesRemaining", autoBackupVM.autoBackupImageBytesRemaining)
                put("etaSeconds", autoBackupVM.autoBackupImageEtaSeconds)
            })
            put("videos", JSONObject().apply {
                put("currentFile", autoBackupVM.autoBackupVideoCurrentFile)
                put("progress", autoBackupVM.autoBackupVideoProgress.toDouble())
                put("fileProgress", autoBackupVM.autoBackupVideoFileProgress.toDouble())
                put("bytesWritten", autoBackupVM.autoBackupVideoBytesWritten)
                put("bytesTotal", autoBackupVM.autoBackupVideoBytesTotal)
                put("processed", autoBackupVM.autoBackupVideoProcessedCount)
                put("total", autoBackupVM.autoBackupVideoTotalCount)
                put("speedBps", autoBackupVM.autoBackupVideoSpeedBps)
                put("bytesUploaded", autoBackupVM.autoBackupVideoBytesUploaded)
                put("bytesTotalPending", autoBackupVM.autoBackupVideoBytesTotalPending)
                put("bytesRemaining", autoBackupVM.autoBackupVideoBytesRemaining)
                put("etaSeconds", autoBackupVM.autoBackupVideoEtaSeconds)
            })
        })
    })

    return root.toString()
}
