package com.nas.naswebdav.ui.html5

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
    val authVM = LocalAuthSessionVM.current
    val connStatus by SharedStateHolder.connectionStatus.collectAsState()
    val thumbAudit by smartToolsVM.thumbnailAudit.collectAsState()
    val fileSort by fileBrowserVM.fileSort.collectAsState()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    // Initial data fetch on screen mount
    LaunchedEffect(viewName) {
        try { authVM.ensureSmartNetwork(context) } catch (_: Exception) {}
        withContext(Dispatchers.IO) { sysMonitorVM.fetchStatusNow() }
        sysMonitorVM.fetchStorageSummary()
        sysMonitorVM.fetchMetricsHistory(sysMonitorVM.metricsHours)
        deviceVM.fetchSmartData()
        smartToolsVM.fetchThumbnailAudit()
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
        connStatus
    ) {
        if (!pageLoaded) return@LaunchedEffect
        val payload = buildHtml5StateJson(
            context = context,
            sysMonitorVM = sysMonitorVM,
            deviceVM = deviceVM,
            autoBackupVM = autoBackupVM,
            fileBrowserVM = fileBrowserVM,
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
                        post { deviceVM.setFanMode(mode) }
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

    return root.toString()
}
