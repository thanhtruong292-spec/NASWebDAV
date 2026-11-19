package com.nas.naswebdav.ui.html5

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
    val fileSort by fileBrowserVM.fileSort.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val targetFolder = fileBrowserVM.currentUrl.ifBlank { WebDavManager.currentBaseUrl }.trimEnd('/')
        uiScope.launch(Dispatchers.IO) {
            var okCount = 0
            WebDavManager.threadLocalAuth.set(
                WebDavManager.AuthState(
                    WebDavManager.currentBaseUrl,
                    SecurePrefsHelper.getUser(context),
                    SecurePrefsHelper.getPass(context)
                )
            )
            try {
                uris.forEachIndexed { idx, uri ->
                    var tempFile: java.io.File? = null
                    try {
                        val rawName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (c.moveToFirst() && col >= 0) c.getString(col) else null
                        } ?: "upload_${System.currentTimeMillis()}_$idx"
                        val safeName = rawName.replace('/', '_').replace('\\', '_').replace("..", "_").take(180)
                        val encoded = java.net.URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")
                        val destUrl = "$targetFolder/$encoded"
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val temp = java.io.File(context.cacheDir, safeName)
                            tempFile = temp
                            temp.outputStream().use { input.copyTo(it) }
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            WebDavManager.uploadFile(destUrl, temp, mime)
                            okCount++
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    } finally {
                        tempFile?.delete()
                    }
                }
            } finally {
                WebDavManager.threadLocalAuth.remove()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Đã tải lên $okCount/${uris.size} tệp", Toast.LENGTH_SHORT).show()
                fileBrowserVM.refresh()
            }
        }
    }

    // Initial data fetch on screen mount
    LaunchedEffect(viewName) {
        try { authVM.ensureSmartNetwork(context) } catch (_: Exception) {}
        withContext(Dispatchers.IO) { sysMonitorVM.fetchStatusNow() }
        sysMonitorVM.fetchStorageSummary()
        sysMonitorVM.fetchMetricsHistory(sysMonitorVM.metricsHours)
        deviceVM.fetchSmartData()
        smartToolsVM.fetchThumbnailAudit()
        if (viewName == "dashboard" || viewName == "system") {
            sysMonitorVM.fetchSystemProcesses(context)
        }
    }

    // Periodic refresh while HTML5 UI is active
    LaunchedEffect(viewName, pageLoaded) {
        if (!pageLoaded) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.setView('$viewName');", null)
        var tick = 0
        while (isActive) {
            if (tick % 2 == 0) {
                withContext(Dispatchers.IO) { sysMonitorVM.fetchStatusNow() }
            }
            if (tick % 15 == 0) {
                smartToolsVM.fetchThumbnailAudit()
            }
            val payload = buildHtml5StateJson(
                context = context,
                sysMonitorVM = sysMonitorVM,
                deviceVM = deviceVM,
                autoBackupVM = autoBackupVM,
                fileBrowserVM = fileBrowserVM,
                liveVM = liveVM,
                fileSort = fileSort,
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
        deviceVM.smartInfo,
        fileBrowserVM.fileList,
        fileBrowserVM.currentUrl,
        fileBrowserVM.isLoading,
        fileSort,
        sysMonitorVM.systemProcesses,
        thumbAudit,
        connStatus,
        liveVM.activeLivestreams.size,
        liveVM.livestreamMessage,
        liveVM.isStartingLivestream,
        liveVM.tiktokLiveWatchUsers
    ) {
        if (!pageLoaded) return@LaunchedEffect
        val payload = buildHtml5StateJson(
            context = context,
            sysMonitorVM = sysMonitorVM,
            deviceVM = deviceVM,
            autoBackupVM = autoBackupVM,
            fileBrowserVM = fileBrowserVM,
            liveVM = liveVM,
            fileSort = fileSort,
            thumbAudit = thumbAudit,
            connStatus = connStatus.message
        )
        webViewRef?.evaluateJavascript("window.NasUI && window.NasUI.updateState($payload);", null)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.parseColor("#0B1120"))
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun action(name: String) {
                        post { onAction(name, null) }
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
                    fun fileRefresh() {
                        post { fileBrowserVM.refresh() }
                    }

                    @JavascriptInterface
                    fun fileSort(mode: String) {
                        post {
                            fileBrowserVM.setFileSort(mode)
                            fileBrowserVM.refresh()
                        }
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
                        post { uploadPickerLauncher.launch(arrayOf("*/*")) }
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
                            var okCount = 0
                            paths.forEach { url ->
                                try {
                                    val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "nas_${System.currentTimeMillis()}" }
                                    val req = okhttp3.Request.Builder().url(url).let(WebDavManager::tagCurrentAuth).build()
                                    NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            resp.body?.byteStream()?.use { inp ->
                                                java.io.File(dlDir, name).outputStream().use { out -> inp.copyTo(out) }
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
                                    .post(okhttp3.RequestBody.create(null, body))
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
                    fun saveAutoBackup(enabled: Boolean, deleteAfter: Boolean) {
                        post {
                            val prefs = com.nas.naswebdav.utils.PreferencesRepository.get(context)
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
                                prefs.setDeleteAfterBackup(deleteAfter)
                                val constraints = androidx.work.Constraints.Builder()
                                    .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                                    .setRequiresCharging(true)
                                    .build()
                                val req = androidx.work.PeriodicWorkRequestBuilder<AutoBackupWorker>(12, java.util.concurrent.TimeUnit.HOURS)
                                    .setConstraints(constraints)
                                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30L, java.util.concurrent.TimeUnit.SECONDS)
                                    .addTag("com.nas.naswebdav.AutoBackupWorker")
                                    .build()
                                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                                    "AutoBackupWork",
                                    androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
                                    req
                                )
                                Toast.makeText(context, "Đã lưu lịch Auto-Backup!", Toast.LENGTH_SHORT).show()
                            } else {
                                prefs.setAutoBackupFlag(false)
                                prefs.setDeleteAfterBackup(deleteAfter)
                                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("AutoBackupWork")
                                Toast.makeText(context, "Đã tắt Auto-Backup", Toast.LENGTH_SHORT).show()
                            }
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
                }, "NasBridge")
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpAuthRequest(
                        view: WebView?,
                        handler: HttpAuthHandler?,
                        host: String?,
                        realm: String?
                    ) {
                        val auth = WebDavManager.currentAuthState()
                        handler?.proceed(auth.user, auth.pass)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded = true
                        view?.evaluateJavascript("window.NasUI && window.NasUI.setView('$viewName');", null)
                    }
                }
                loadUrl("file:///android_asset/html5/index.html")
                webViewRef = this
            }
        },
        update = { wv ->
            if (pageLoaded) {
                wv.evaluateJavascript("window.NasUI && window.NasUI.setView('$viewName');", null)
            }
        }
    )
}

private fun buildHtml5StateJson(
    context: android.content.Context,
    sysMonitorVM: com.nas.naswebdav.monitor.SystemMonitorViewModel,
    deviceVM: com.nas.naswebdav.device.DeviceManagementViewModel,
    autoBackupVM: com.nas.naswebdav.backup.AutoBackupViewModel,
    fileBrowserVM: com.nas.naswebdav.browser.FileBrowserViewModel,
    liveVM: com.nas.naswebdav.livestream.LivestreamViewModel,
    fileSort: String,
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

    // File Browser snapshot
    val sortedFiles = when (fileSort) {
        "date" -> fileBrowserVM.fileList.sortedWith(compareByDescending<NasFile> { it.isDirectory }.thenByDescending { it.lastModified })
        "size" -> fileBrowserVM.fileList.sortedWith(compareByDescending<NasFile> { it.isDirectory }.thenByDescending { it.contentLength })
        else -> fileBrowserVM.fileList.sortedWith(compareByDescending<NasFile> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
    val fArr = JSONArray()
    sortedFiles.take(300).forEach { f ->
        val relPath = runCatching {
            val uPath = java.net.URI(f.path).path ?: f.path
            val idx = uPath.indexOf("/webdav/")
            if (idx >= 0) uPath.substring(idx) else uPath
        }.getOrDefault(f.path)
        fArr.put(JSONObject().apply {
            put("name", f.name)
            put("path", f.path)
            put("relPath", relPath)
            put("isDir", f.isDirectory)
            put("size", f.contentLength)
        })
    }
    val curTitle = if (fileBrowserVM.isSpecialMode && fileBrowserVM.specialTitle.isNotBlank()) {
        fileBrowserVM.specialTitle
    } else {
        runCatching { java.net.URI(fileBrowserVM.currentUrl).path ?: "/webdav/" }.getOrDefault("/webdav/")
    }
    root.put("files", JSONObject().apply {
        put("title", curTitle)
        put("loading", fileBrowserVM.isLoading)
        put("items", fArr)
    })

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
    root.put("toolbox", JSONObject().apply {
        put("biometric", prefs.isBiometricEnabled())
        put("autoBackup", prefs.isAutoBackupFlag())
        put("deleteAfter", prefs.isDeleteAfterBackup())
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
            put("duration", job.durationSeconds)
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

    return root.toString()
}
