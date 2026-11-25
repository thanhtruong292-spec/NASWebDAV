package com.nas.naswebdav.smarttools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.DuplicateProgressState
import com.nas.naswebdav.OrganizerGroup
import com.nas.naswebdav.ThumbnailAuditData
import com.nas.naswebdav.OrganizerFilter
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.AutoDuplicateScanWorker
import com.nas.naswebdav.DuplicateScanWorker
import com.nas.naswebdav.toApiBaseUrl
import com.nas.naswebdav.toNasRelativePath
import com.nas.naswebdav.buildWebDavTrashTargetUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.core.content.edit

internal fun duplicateScanReadout(snapshot: org.json.JSONObject): String {
    val report = snapshot.optJSONObject("result")
    val scope = when {
        snapshot.opt("path") !is String -> "Phạm vi chưa rõ."
        snapshot.optString("path") in setOf("", "/", "/webdav", "/webdav/") -> "Phạm vi toàn thư mục WebDAV."
        else -> "Phạm vi một thư mục con."
    }
    val count = report?.optLong("total_files_scanned", -1L)?.takeIf { it >= 0 }
        ?: snapshot.optLong("scanned", -1L).takeIf { it >= 0 }
    val errors = report?.opt("read_errors")
    val evidence = when {
        errors !is Number -> "NAS chưa cung cấp số lỗi đọc."
        errors.toLong() > 0L -> "Có ${errors.toLong()} lỗi đọc."
        else -> ""
    }
    return listOf(scope, count?.let { "Đã quét $it tệp." }.orEmpty(), evidence)
        .filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * SmartToolsViewModel — Primary owner of smart-tools state.
 *
 * Quản lý: Duplicate scan, Thumbnail generation, Smart organizer.
 *
 * Constructor: (repository) — WebDavManager là singleton, dùng trực tiếp.
 *
 * Phase 2: initial skeleton. Phase 7d.x: migrated from WebDavViewModel facade.
 * UI now reads state directly from this VM.
 */
class SmartToolsViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ DUPLICATE SCAN STATE ═══

    var isShowingDuplicates by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var shouldAutoOpenDuplicates by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var duplicateFilesList by androidx.compose.runtime.mutableStateOf<List<com.nas.naswebdav.NasFile>>(emptyList())
        private set
    val selectedDuplicates = androidx.compose.runtime.mutableStateListOf<com.nas.naswebdav.NasFile>()
    var isScanningDuplicates by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isWorkerRunning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var scanJob: kotlinx.coroutines.Job? = null
        private set

    var scanDuplicatesCurrentFolderUrl by androidx.compose.runtime.mutableStateOf("")
        private set
    var scanDuplicatesCurrentItemName by androidx.compose.runtime.mutableStateOf("")
        private set
    var scanDuplicatesTotalScanned by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var scanDuplicatesFound by androidx.compose.runtime.mutableIntStateOf(0)
    var scanDuplicatesPercent by androidx.compose.runtime.mutableFloatStateOf(0f)
    var scanDuplicatesCurrentStagePercent by androidx.compose.runtime.mutableFloatStateOf(0f)
    var scanDuplicatesElapsedTime by androidx.compose.runtime.mutableLongStateOf(0L)
    var scanDuplicatesEstimatedTimeRemaining by androidx.compose.runtime.mutableLongStateOf(-1L)
    var scanDuplicatesIsFolder by androidx.compose.runtime.mutableStateOf(false)
    var scanDuplicatesStage by androidx.compose.runtime.mutableStateOf("Khởi động...")
    var scanDuplicatesStageNumber by androidx.compose.runtime.mutableIntStateOf(1)
    var scanDuplicatesTotalStages by androidx.compose.runtime.mutableIntStateOf(4)
    var scanDuplicatesStageDescription by androidx.compose.runtime.mutableStateOf("")
    var scanDuplicatesIsPaused by androidx.compose.runtime.mutableStateOf(false)
    var autoCleanEnabled by androidx.compose.runtime.mutableStateOf(false)
    var dupSchedEnabled by androidx.compose.runtime.mutableStateOf(true)
    var dupSchedHour by androidx.compose.runtime.mutableIntStateOf(3)
    var dupSchedIntervalDays by androidx.compose.runtime.mutableIntStateOf(7)
    var dupSchedAutoClean by androidx.compose.runtime.mutableStateOf(false)
    var dupSchedLastRunAt by androidx.compose.runtime.mutableLongStateOf(0L)
    var dupSchedLastGroups by androidx.compose.runtime.mutableIntStateOf(0)
    private var dupSmoothedEtaMs: Long = -1L

    // ═══ LIVE DUPLICATE DELETION PROGRESS (Rust SHA-256 + F_SETLEASE batch) ═══
    var isDeletingDuplicates by androidx.compose.runtime.mutableStateOf(false)
        private set
    var dupDeleteTotal by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var dupDeleteDone by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var dupDeleteProcessed by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var dupDeleteSkipped by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var dupDeleteBytesFreed by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    private var fullReportDisplayed = false
    private var duplicateStatusJob: kotlinx.coroutines.Job? = null
    private var cleanupScanReadout = ""
    private var cleanupJobId: Long? = null
    val nasCleanupActive get() = cleanupJobId != null
    var dupServerStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    var dupSessionDeletedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var dupSessionFreedBytes by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var dupDeleteCurrentFile by androidx.compose.runtime.mutableStateOf("")
        private set
    @Volatile private var dupDeleteCancelRequested: Boolean = false
    private var dupDeleteJob: kotlinx.coroutines.Job? = null

    // ═══ SIMILAR IMAGES STATE (NAS Rust NEON Hamming Search) ═══

    var similarImagesList by androidx.compose.runtime.mutableStateOf<List<com.nas.naswebdav.NasFile>>(emptyList())
        private set
    var isLoadingSimilarImages by androidx.compose.runtime.mutableStateOf(false)
        private set

    // ═══ THUMBNAIL GENERATION STATE ═══

    var thumbGenerated by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var thumbTotal by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var thumbErrors by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var thumbRunning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var thumbLastFile by androidx.compose.runtime.mutableStateOf("")
        private set
    var thumbElapsed by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var thumbEta by androidx.compose.runtime.mutableLongStateOf(-1L)
        private set
    var thumbElapsedFmt by androidx.compose.runtime.mutableStateOf("")
        private set
    var thumbEtaFmt by androidx.compose.runtime.mutableStateOf("")
        private set
    var thumbPaused by androidx.compose.runtime.mutableStateOf(false)
        private set
    internal val _thumbnailAudit = kotlinx.coroutines.flow.MutableStateFlow(ThumbnailAuditData())
    val thumbnailAudit: kotlinx.coroutines.flow.StateFlow<ThumbnailAuditData>
        get() = _thumbnailAudit

    // ═══ SMART ORGANIZER STATE ═══

    var organizerScanning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var organizerExecuting by androidx.compose.runtime.mutableStateOf(false)
        private set
    var organizerScanResult by androidx.compose.runtime.mutableStateOf<List<OrganizerGroup>?>(null)
        internal set
    var organizerTotalFiles by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var organizerResult by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set
    var organizerError by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set
    var organizingLegacyRunning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var organizingLegacyResult by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    var globalUi: com.nas.naswebdav.GlobalUiViewModel? = null
        private set

    fun attachGlobalUi(ui: com.nas.naswebdav.GlobalUiViewModel) {
        globalUi = ui
    }

    private val _globalUi: com.nas.naswebdav.GlobalUiViewModel
        get() = globalUi ?: fallbackGlobalUi

    private val fallbackGlobalUi by lazy {
        com.nas.naswebdav.GlobalUiViewModel()
    }

    fun fetchDuplicateSchedule(forceReload: Boolean = false) {
        if (duplicateStatusJob?.isActive == true) return
        duplicateStatusJob = viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = WebDavManager.currentBaseUrl
            val apiBase = baseUrl.toApiBaseUrl()
            if (apiBase.isBlank()) return@launch
            val authHeader = WebDavManager.currentAuthHeader()
            runCatching {
                val jobId = cleanupJobId
                if (jobId != null && !forceReload) {
                    val progressReq = okhttp3.Request.Builder()
                        .url("$apiBase/api/duplicates/auto_clean/status")
                        .header("Authorization", authHeader).build()
                    val progress = NasApplication.instance.fastApiClient.newCall(progressReq).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        org.json.JSONObject(response.body?.string().orEmpty())
                    }
                    if (progress.optLong("job_id", -1) == jobId && progress.optString("status") == "running") {
                        withContext(Dispatchers.Main) {
                            dupServerStatus = listOf(cleanupScanReadout, cleanupStatusText(progress))
                                .filter { it.isNotBlank() }.joinToString(" ")
                        }
                        return@launch
                    }
                }
                val req = okhttp3.Request.Builder()
                    .url("$apiBase/api/disk/scan_duplicates/status")
                    .header("Authorization", authHeader)
                    .get()
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val root = org.json.JSONObject(resp.body?.string() ?: return@use)
                    root.optJSONObject("schedule")?.let { j ->
                        withContext(Dispatchers.Main) {
                            dupSchedEnabled = j.optBoolean("enabled", true)
                            dupSchedHour = j.optInt("hour", 3)
                            dupSchedIntervalDays = j.optInt("interval_days", 7)
                            dupSchedAutoClean = j.optBoolean("auto_clean", false) || j.optBoolean("autoClean", false)
                            dupSchedLastRunAt = (j.optDouble("last_run_at", 0.0) * 1000.0).toLong()
                            dupSchedLastGroups = j.optInt("last_groups", 0)
                        }
                    }
                    val st = root.optString("status", "idle")
                    val report = root.optJSONObject("result")
                    val hasInventory = report?.has("inventory") == true
                    val completeReport = report != null && (!hasInventory || runCatching {
                        com.nas.naswebdav.requireCompleteDuplicateInventory(report)
                    }.isSuccess)
                    val notice = when {
                        st != "done" -> ""
                        !completeReport -> "NAS chưa gửi báo cáo catalogue đầy đủ."
                        else -> com.nas.naswebdav.duplicateReportNotice(report)
                    }
                    val cleanup = root.optJSONObject("cleanup")
                    val scanReadout = if (st == "done") duplicateScanReadout(root) else ""
                    val statusText = when {
                        st == "error" -> root.optString("error", "Quét gặp lỗi")
                        st == "cancelled" -> "Đã hủy quét"
                        st == "cancelling" -> "NAS đang dừng phiên quét"

                        cleanup == null -> notice
                        cleanup.optString("status") == "pending" -> "Đang chờ NAS dọn"
                        else -> {
                            listOf(cleanupStatusText(cleanup), notice).filter { it.isNotEmpty() }.joinToString(" ")
                        }
                    }
                    val statusWithScope = listOf(scanReadout, statusText).filter { it.isNotBlank() }.joinToString(" ")
                    val cleanupChanged = statusWithScope != dupServerStatus
                    if (cleanupChanged && notice.isNotEmpty()) android.util.Log.i("DuplicateScan", notice)
                    withContext(Dispatchers.Main) {
                        cleanupJobId = if (cleanup?.optString("status") in setOf("running", "pending")) root.optLong("job_id", -1) else null
                        cleanupScanReadout = scanReadout
                        dupServerStatus = statusWithScope
                    }
                    if (st == "running" || st == "cancelling") {
                        // Tu dong gan lai vao phien quet Rust dang chay tren NAS (ke ca khi vua mo lai app)
                        val rawElapsedMs = (root.optDouble("elapsed_sec", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                        val prevElapsedMs = DuplicateProgressState.elapsedTime.value
                        val elapsedMs = rawElapsedMs.coerceAtLeast(prevElapsedMs)
                        val pctReal = (root.optDouble("percent", 1.0).toFloat() / 100f).coerceIn(0.01f, 0.99f)
                        val rawEtaMs = if (pctReal > 0.03f && elapsedMs >= 3000L) ((elapsedMs / pctReal) - elapsedMs).toLong().coerceAtLeast(0L) else -1L
                        val etaMs = if (rawEtaMs > 0L) {
                            dupSmoothedEtaMs = if (dupSmoothedEtaMs <= 0L) rawEtaMs else ((dupSmoothedEtaMs * 3 + rawEtaMs) / 4)
                            dupSmoothedEtaMs
                        } else {
                            dupSmoothedEtaMs = -1L
                            -1L
                        }
                        val curRel = root.optString("current_file", "")
                        DuplicateProgressState.stage.value = if (st == "cancelling") {
                            "NAS đang dừng phiên quét"
                        } else {
                            root.optString("stage_name", "Rust NAS đang quét...")
                        }
                        DuplicateProgressState.stageNumber.value = root.optInt("stage_num", 1).coerceIn(1, 3)
                        DuplicateProgressState.scannedCount.value = root.optInt("scanned", 0)
                        DuplicateProgressState.foundCount.value = root.optInt("candidates", 0)
                        DuplicateProgressState.percent.value = pctReal
                        DuplicateProgressState.elapsedTime.value = elapsedMs
                        DuplicateProgressState.estimatedTimeRemaining.value = etaMs
                        DuplicateProgressState.currentFolder.value = if (curRel.contains('/')) "/" + curRel.substringBeforeLast('/') else "/"
                        DuplicateProgressState.itemName.value = curRel.substringAfterLast('/')
                        withContext(Dispatchers.Main) { isScanningDuplicates = true }
                    } else {
                        dupSmoothedEtaMs = -1L
                        val wasScanning = isScanningDuplicates
                        withContext(Dispatchers.Main) { isScanningDuplicates = false }
                        // Chỉ nạp lại kết quả khi:
                        // 1. Người dùng yêu cầu làm mới rõ ràng (forceReload)
                        // 2. Hoặc tiến trình quét vừa hoàn tất (wasScanning)
                        // 3. Hoặc danh sách cục bộ đang trống (chưa từng nạp)
                        // Tuyệt đối không ghi đè khi đang xóa tệp (!isDeletingDuplicates)
                        if (!isDeletingDuplicates && (forceReload || jobId != null || wasScanning || cleanupChanged || duplicateFilesList.isEmpty())) {
                            val loaded = if (st == "done" && completeReport) {
                                com.nas.naswebdav.parseRustDuplicateGroups(report, baseUrl)
                            } else {
                                emptyList()
                            }
                            DuplicateProgressState.lastRustResults.value = loaded
                            withContext(Dispatchers.Main) {
                                val isFull = report != null && (report.optInt("total_groups", -1) <= (report.optJSONArray("groups")?.length() ?: 0))
                                fullReportDisplayed = st == "done" && isFull
                                duplicateFilesList = loaded
                            }
                        }
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.w("DuplicateScan", "Không cập nhật được tiến trình NAS", error)
                withContext(Dispatchers.Main) { dupServerStatus = "Chưa cập nhật được tiến trình từ NAS." }
            }
        }
    }

    private fun cleanupStatusText(cleanup: org.json.JSONObject): String {
        val label = when (cleanup.optString("status")) {
            "running" -> "NAS đang dọn"
            "partial" -> "NAS dọn chưa hết"
            "cancelled" -> "NAS đã dừng dọn"
            "done" -> {
                if (cleanup.optInt("total", -1) == 0 && cleanup.optInt("deleted") == 0) {
                    return "Không có tệp trong danh sách dọn của phiên này."
                }
                "NAS đã kết thúc lượt dọn"
            }
            else -> "Dọn gặp lỗi"
        }
        return "$label. Đã xóa ${cleanup.optInt("deleted")}/${cleanup.optInt("total")}, bỏ qua ${cleanup.optInt("skipped")}, giữ ${cleanup.optInt("retained_small")} tệp dưới 1 KiB, chờ đồng bộ ${cleanup.optInt("metadata_pending")}."
    }

    fun saveDuplicateSchedule(enabled: Boolean, hour: Int, intervalDays: Int, autoClean: Boolean = false, context: Context? = null) {
        dupSchedEnabled = enabled
        dupSchedHour = hour.coerceIn(0, 23)
        dupSchedIntervalDays = intervalDays.coerceIn(1, 30)
        dupSchedAutoClean = autoClean
        if (context != null) {
            toggleAutoClean(context, autoClean)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val base = WebDavManager.currentBaseUrl.toApiBaseUrl()
            if (base.isBlank()) return@launch
            runCatching {
                val body = org.json.JSONObject()
                    .put("enabled", dupSchedEnabled)
                    .put("hour", dupSchedHour)
                    .put("interval_days", dupSchedIntervalDays)
                    .put("auto_clean", dupSchedAutoClean)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/disk/scan_duplicates/schedule")
                    .header("Authorization", WebDavManager.currentAuthHeader())
                    .post(body)
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().close()
            }
        }
    }

    // ═══ AUTO CLEAN ═══

    fun toggleAutoClean(context: Context, enabled: Boolean) {
        autoCleanEnabled = enabled
        viewModelScope.launch(Dispatchers.IO) {
            context.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
                .edit { putBoolean("auto_clean_enabled", enabled) }
        }
        val workManager = androidx.work.WorkManager.getInstance(context)
        if (enabled) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                .build()
            val req = androidx.work.PeriodicWorkRequestBuilder<AutoDuplicateScanWorker>(
                30, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    java.util.concurrent.TimeUnit.SECONDS
                )
                .build()
            workManager.enqueueUniquePeriodicWork(
                "AutoCleanDuplicates", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            workManager.cancelUniqueWork("AutoCleanDuplicates")
        }
    }

    // ═══ BACKGROUND DUPLICATE SCAN ═══

    fun startBackgroundDuplicateScan(context: Context, forceRestart: Boolean = false, lightningMode: Boolean = true) {
        _globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS,
            "Đã nhận lệnh! Đang khởi động trình quét rác...")
        isScanningDuplicates = true
        viewModelScope.launch {
            repository.addSystemLog("INFO", "DuplicateScan",
                "Hệ thống: Người dùng đã phân công quét thủ công trùng lặp")
        }
        if (isWorkerRunning) return

        isWorkerRunning = true
        if (forceRestart) {
            dupSessionDeletedCount = 0
            dupSessionFreedBytes = 0L
        }
        scanDuplicatesCurrentFolderUrl = "Đang kết nối..."
        scanDuplicatesCurrentItemName = "Khởi tạo..."
        scanDuplicatesTotalScanned = 0
        scanDuplicatesFound = 0
        scanDuplicatesStage = "Khởi động..."

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500)
            try {
                val workManager = androidx.work.WorkManager.getInstance(context)
                val currentUrl = com.nas.naswebdav.WebDavManager.currentBaseUrl
                val inputData = androidx.work.workDataOf(
                    "currentUrl" to currentUrl,
                    "forceRestart" to forceRestart,
                    "lightningMode" to lightningMode
                )
                val scanWorkRequest = androidx.work.OneTimeWorkRequestBuilder<DuplicateScanWorker>()
                    .setInputData(inputData)
                    .build()
                workManager.enqueueUniqueWork("Unique_Scan_V3",
                    androidx.work.ExistingWorkPolicy.REPLACE, scanWorkRequest)

                workManager.getWorkInfoByIdFlow(scanWorkRequest.id).collect { workInfo ->
                    if (workInfo != null) {
                        when (workInfo.state) {
                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                scanDuplicatesStage = "Hoàn tất"
                                scanDuplicatesCurrentFolderUrl = "Hoàn tất (Rust NAS)!"
                                scanDuplicatesCurrentItemName = "Đã quét xong toàn bộ."
                                scanDuplicatesPercent = 1f
                                scanDuplicatesCurrentStagePercent = 1f
                                scanDuplicatesStageNumber = 3
                                scanDuplicatesStageDescription = workInfo.outputData.getString("stageDescription") ?: "Hoàn tất tác vụ quét Rust."
                                isWorkerRunning = false
                                isScanningDuplicates = false
                                loadDuplicateResultsFromCache(context)
                            }
                            androidx.work.WorkInfo.State.FAILED -> {
                                val err = workInfo.outputData.getString("error") ?: "Rust duplicate_finder gặp lỗi trên NAS!"
                                scanDuplicatesCurrentFolderUrl = err
                                scanDuplicatesStage = "Lỗi quét Rust"
                                scanDuplicatesStageDescription = err
                                isWorkerRunning = false
                                isScanningDuplicates = false
                                _globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR, err)
                            }
                            androidx.work.WorkInfo.State.CANCELLED -> {
                                isWorkerRunning = false
                                isScanningDuplicates = false
                            }
                            else -> { /* RUNNING, ENQUEUED, BLOCKED */ }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                isWorkerRunning = false
                isScanningDuplicates = false
            }
        }
    }

    fun loadDuplicateResultsFromCache(context: Context) {
        viewModelScope.launch {
            try {
                // 100% Rust trên NAS — KHÔNG fallback sang Room DB cục bộ.
                val duplicates = withContext(Dispatchers.IO) {
                    fetchServerDuplicates()
                        ?: error("NAS chưa trả danh sách nhóm cho phiên này.")
                }
                withContext(Dispatchers.Main) {
                    fullReportDisplayed = !duplicates.second.contains("Đã nhận")
                    if (!fullReportDisplayed) dupServerStatus = duplicates.second
                    duplicateFilesList = duplicates.first
                    isShowingDuplicates = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR,
                        "Lỗi nạp kết quả trùng lặp từ Rust NAS: ${e.message}")
                }
            }
        }
    }

    private fun fetchServerDuplicates(): Pair<List<com.nas.naswebdav.NasFile>, String>? {
        val auth = WebDavManager.currentAuthState()
        if (auth.baseUrl.isBlank()) return null
        val apiBase = auth.baseUrl.toApiBaseUrl()
        val request = okhttp3.Request.Builder()
            .url("$apiBase/api/disk/scan_duplicates/status")
            .header("Authorization", auth.authHeader)
            .get()
            .build()
        return try {
            NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val state = org.json.JSONObject(response.body?.string() ?: return null)
                if (state.optString("status") != "done") return null
                val report = state.optJSONObject("result") ?: return null
                if (report.optJSONArray("groups") == null) return null
                if (report.has("inventory")) {
                    com.nas.naswebdav.requireCompleteDuplicateInventory(report)
                }
                com.nas.naswebdav.parseRustDuplicateGroups(report, auth.baseUrl) to com.nas.naswebdav.duplicateReportNotice(report)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tìm ảnh tương đồng bằng Rust ARM64 NEON popcount (VCNT) trên NAS: GET /api/fingerprint/similar.
     */
    fun findSimilarImages(filePath: String, threshold: Int = 5, limit: Int = 50) {
        val baseUrl = WebDavManager.currentBaseUrl
        if (baseUrl.isBlank() || filePath.isBlank()) return
        isLoadingSimilarImages = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = mutableListOf<com.nas.naswebdav.NasFile>()
            try {
                val rel = filePath.toNasRelativePath()
                val encoded = java.net.URLEncoder.encode("/webdav/$rel", "UTF-8")
                val apiUrl = "${baseUrl.toApiBaseUrl()}/api/fingerprint/similar?path=$encoded&threshold=$threshold&limit=$limit"
                val req = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", WebDavManager.currentAuthHeader())
                    .get()
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val matches = org.json.JSONObject(body).optJSONArray("matches")
                        val webdavRoot = baseUrl.trimEnd('/')
                        if (matches != null) {
                            for (i in 0 until matches.length()) {
                                val m = matches.getJSONObject(i)
                                val p = m.optString("path", "").removePrefix("/webdav/").removePrefix("/")
                                if (p.isNotEmpty()) {
                                    found.add(
                                        com.nas.naswebdav.NasFile(
                                            name = p.substringAfterLast('/'),
                                            path = com.nas.naswebdav.toValidUrl("$webdavRoot/$p"),
                                            isDirectory = false,
                                            contentType = "image/*",
                                            contentLength = m.optLong("size", 0L),
                                            lastModified = m.optLong("mtime", 0L),
                                            partialHash = m.optString("ahash", "")
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.nas.naswebdav.utils.SystemLogger.log("WARNING", "SimilarImages", "Tìm ảnh tương đồng lỗi ($filePath): ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    similarImagesList = found
                    isLoadingSimilarImages = false
                }
            }
        }
    }

    // ═══ ZIP / UNZIP (NAS Rust archive_worker) ═══

    fun zipFolder(folderPath: String) {
        val relativePath = folderPath.toNasRelativePath()
        val folderName = folderPath.trimEnd('/').substringAfterLast("/")
        val jsonBody = org.json.JSONObject().apply {
            put("path", "/webdav/$relativePath")
        }.toString()

        _globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS,
            "Tác vụ nén ZIP ($folderName) đang chạy ngầm trên NAS!")

        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "ZIP")
            .putString("apiUrl",
                "${com.nas.naswebdav.WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/zip")
            .putString("jsonBody", jsonBody)
            .putString("taskLabel", "Nén ZIP $folderName")
            .build()
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.LongRunningApiWorker>()
            .setInputData(inputData)
            .addTag("LONG_RUNNING_API")
            .build()
        val context = NasApplication.instance.applicationContext
        androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
    }

    fun unzipFile(filePath: String) {
        val relativePath = filePath.toNasRelativePath()
        val fileName = filePath.substringAfterLast("/")
        val jsonBody = org.json.JSONObject().apply {
            put("file_path", "/webdav/$relativePath")
            put("path", "/webdav/$relativePath")
        }.toString()

        _globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS,
            "Tác vụ giải nén ($fileName) đang chạy ngầm trên NAS!")

        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "UNZIP")
            .putString("apiUrl",
                "${com.nas.naswebdav.WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/file/unzip")
            .putString("jsonBody", jsonBody)
            .putString("taskLabel", "Giải nén $fileName")
            .build()
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.LongRunningApiWorker>()
            .setInputData(inputData)
            .addTag("LONG_RUNNING_API")
            .build()
        val context = NasApplication.instance.applicationContext
        androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
    }
    fun dismissDuplicatesView() { isShowingDuplicates = false }
    fun resetShouldAutoOpenDuplicates() { shouldAutoOpenDuplicates = false }
    fun deleteDuplicateFile(file: com.nas.naswebdav.NasFile) {
        deleteSelectedDuplicates(listOf(file))
    }

    fun cancelDuplicateDeletion() {
        dupDeleteCancelRequested = true
        dupDeleteJob?.cancel()
    }

    fun autoCleanAllDuplicates(filterType: String = "all") {
        if (isDeletingDuplicates) return
        val candidates = duplicateFilesList.filter { !it.isDirectory && (it.partialHash?.length == 64) }
        if (candidates.isEmpty()) {
            android.widget.Toast.makeText(NasApplication.instance, "Không có tệp trùng lặp nào để dọn dẹp.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val imgExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
        val vidExts = setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v")
        val filtered = when (filterType.lowercase()) {
            "image" -> candidates.filter { it.name.substringAfterLast('.', "").lowercase() in imgExts }
            "video" -> candidates.filter { it.name.substringAfterLast('.', "").lowercase() in vidExts }
            else -> candidates
        }
        val victims = mutableListOf<com.nas.naswebdav.NasFile>()
        val grouped = filtered.groupBy { it.partialHash.orEmpty() }
        for ((_, group) in grouped) {
            if (group.size < 2) continue
            // Bản gốc tốt nhất: đường dẫn ngắn nhất, tên tệp chuẩn nhất
            val sorted = group.sortedWith(compareBy<com.nas.naswebdav.NasFile>(
                { it.path.length },
                { it.path }
            ))
            // Giữ lại bản đầu tiên (sorted.first()), tất cả các bản còn lại là victim cần dọn
            victims.addAll(sorted.drop(1))
        }
        if (victims.isEmpty()) {
            android.widget.Toast.makeText(NasApplication.instance, "Tất cả các nhóm đã là bản gốc duy nhất.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        deleteSelectedDuplicates(victims)
    }

    fun deleteSelectedDuplicates(files: List<com.nas.naswebdav.NasFile>) {
        if (files.isEmpty() || isDeletingDuplicates || nasCleanupActive) return
        dupDeleteCancelRequested = false
        dupDeleteJob = viewModelScope.launch(Dispatchers.IO) {
            val accountKey = com.nas.naswebdav.currentAccountKey()
            val selected = files.map { it.path }.toSet()
            // 100% Rust trên NAS: chỉ dùng danh sách đã xác minh SHA-256 từ Rust, không fallback Room DB
            val serverCandidates = duplicateFilesList
                .filter { !it.isDirectory && (it.partialHash?.length == 64) }
                .map { f ->
                    com.nas.naswebdav.CachedFile(
                        path = f.path,
                        name = f.name,
                        isDirectory = false,
                        contentType = f.contentType,
                        parentPath = f.path.substringBeforeLast('/', ""),
                        contentLength = f.contentLength,
                        lastModified = f.lastModified,
                        partialHash = f.partialHash,
                        fullHash = f.partialHash,
                        accountKey = accountKey
                    )
                }
            val mediaExts = setOf(
                "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp",
                "mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v"
            )
            // Ưu tiên xóa tệp ảnh/video có dung lượng thấp trước để giải phóng nhanh & tiến trình mượt
            val plan = com.nas.naswebdav.planVerifiedDuplicateCleanup(serverCandidates, selected)
                .sortedWith(
                    compareBy<Pair<com.nas.naswebdav.CachedFile, com.nas.naswebdav.CachedFile>>(
                        { (v, _) -> if (v.name.substringAfterLast('.', "").lowercase() in mediaExts) 0 else 1 },
                        { (v, _) -> v.contentLength }
                    )
                )
            if (plan.isEmpty()) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        NasApplication.instance,
                        "Không có bản trùng hợp lệ để xóa (đã giữ bản gốc mỗi nhóm).",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                isDeletingDuplicates = true
                dupDeleteTotal = plan.size
                dupDeleteDone = 0
                dupDeleteProcessed = 0
                dupDeleteSkipped = 0
                dupDeleteBytesFreed = 0L
                dupDeleteCurrentFile = plan.first().first.name
            }
            val deleted = mutableSetOf<String>()
            var totalBytesFreed = 0L
            var totalSkipped = 0
            var processedCount = 0
            var metadataPending = 0
            val auth = WebDavManager.currentAuthState()
            val webdavBase = auth.baseUrl
            val apiBase = webdavBase.toApiBaseUrl()
            val authHeader = auth.authHeader
            val batchSupported = apiBase.isNotBlank() && authHeader.isNotBlank()
            val batchClient = NasApplication.instance.fastApiClient.newBuilder()
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()

            try {
                if (batchSupported) {
                    val relToFull = mutableMapOf<String, String>()
                    for (chunk in plan.chunked(20)) {
                        if (dupDeleteCancelRequested) break
                        val curName = chunk.first().first.name
                        withContext(Dispatchers.Main) { dupDeleteCurrentFile = curName }
                        val arr = org.json.JSONArray()
                        for ((victim, keeper) in chunk) {
                            val sha = keeper.fullHash ?: continue
                            val victimPath = com.nas.naswebdav.relativeDuplicatePath(webdavBase, victim.path)
                            val keeperPath = com.nas.naswebdav.relativeDuplicatePath(webdavBase, keeper.path)
                            if (victimPath == null || keeperPath == null) {
                                totalSkipped++
                                continue
                            }
                            // Ánh xạ toàn diện các biến thể định dạng đường dẫn để khớp 100% phản hồi từ server
                            relToFull[victimPath] = victim.path
                            relToFull[victim.path] = victim.path
                            val vClean = victimPath.removePrefix("/webdav/").removePrefix("webdav/").removePrefix("/")
                            relToFull[vClean] = victim.path
                            relToFull["/webdav/$vClean"] = victim.path
                            runCatching { java.net.URLDecoder.decode(victimPath, "UTF-8") }.getOrNull()?.let { decoded ->
                                relToFull[decoded] = victim.path
                                val dClean = decoded.removePrefix("/webdav/").removePrefix("webdav/").removePrefix("/")
                                relToFull[dClean] = victim.path
                                relToFull["/webdav/$dClean"] = victim.path
                            }
                            arr.put(
                                org.json.JSONObject()
                                    .put("victim", victimPath)
                                    .put("keeper", keeperPath)
                                    .put("sha256", sha)
                            )
                        }
                        try {
                            if (arr.length() > 0) {
                                val req = okhttp3.Request.Builder()
                                    .url("$apiBase/api/duplicates/cleanup_batch")
                                    .header("Authorization", authHeader)
                                    .post(org.json.JSONObject().put("items", arr).toString().toRequestBody(jsonMedia))
                                    .build()
                                batchClient.newCall(req).execute().use { resp ->
                                    if (resp.isSuccessful) {
                                        val resObj = org.json.JSONObject(resp.body?.string().orEmpty())
                                        val delArr = resObj.optJSONArray("deleted_paths")
                                        if (delArr != null) {
                                            for (i in 0 until delArr.length()) {
                                                val rel = delArr.optString(i)
                                                relToFull[rel]?.let { deleted.add(it) }
                                            }
                                        }
                                        totalSkipped += resObj.optInt("skipped", 0)
                                        metadataPending += resObj.optInt("metadata_pending", 0)
                                        totalBytesFreed += resObj.optLong("bytes_removed", 0L)
                                    } else {
                                        totalSkipped += arr.length()
                                    }
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("SmartToolsVM", "Batch duplicate cleanup chunk failed", e)
                            totalSkipped += arr.length()
                        }
                        processedCount += chunk.size
                        withContext(Dispatchers.Main) {
                            dupDeleteProcessed = processedCount.coerceAtMost(plan.size)
                            dupDeleteDone = deleted.size
                            dupDeleteSkipped = totalSkipped
                            dupDeleteBytesFreed = totalBytesFreed
                        }
                    }
                }
            } finally {
                val pruned = duplicateFilesList
                    .filter { it.path !in deleted }
                    .groupBy { it.partialHash.orEmpty() }
                    .values
                    .filter { it.size > 1 }
                    .flatten()
                DuplicateProgressState.lastRustResults.value = pruned
                val wasCancelled = dupDeleteCancelRequested
                withContext(Dispatchers.Main) {
                    isDeletingDuplicates = false
                    dupDeleteCurrentFile = ""
                    duplicateFilesList = pruned
                    selectedDuplicates.clear()
                    dupSessionDeletedCount += deleted.size
                    dupSessionFreedBytes += totalBytesFreed
                    val freedText = com.nas.naswebdav.utils.FormatUtils.formatBytes(totalBytesFreed)
                    val msg = if (wasCancelled) {
                        "Đã dừng xóa: đã xóa ${deleted.size}/${plan.size} bản trùng ($freedText)."
                    } else if (deleted.isEmpty()) {
                        "Xóa không thành công (0/${plan.size} tệp; đã bỏ qua $totalSkipped tệp do lỗi kết nối hoặc tệp đã bị thay đổi)."
                    } else if (deleted.size < plan.size) {
                        "Dọn chưa hết. Đã xóa ${deleted.size}/${plan.size} bản trùng ($freedText); bỏ qua $totalSkipped tệp."
                    } else {
                        "Đã xóa ${deleted.size}/${plan.size} bản trùng ($freedText); giữ bản gốc."
                    }
                    val status = if (metadataPending > 0) "$msg $metadataPending tệp đang chờ đồng bộ danh mục." else msg
                    dupServerStatus = status
                    android.widget.Toast.makeText(NasApplication.instance, status, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    fun togglePauseDuplicateScan() {
        scanDuplicatesIsPaused = !scanDuplicatesIsPaused
        DuplicateProgressState.isPaused.value = scanDuplicatesIsPaused
    }

    fun cancelDuplicateScan(context: android.content.Context) {
        DuplicateProgressState.isPaused.value = false
        scanDuplicatesIsPaused = false
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("Unique_Scan_V3")
        isWorkerRunning = false
        dupSmoothedEtaMs = -1L
        scanJob?.cancel()
        isScanningDuplicates = false
        duplicateFilesList = emptyList()
        DuplicateProgressState.lastRustResults.value = emptyList()
        DuplicateProgressState.stage.value = "Khởi động..."
        DuplicateProgressState.percent.value = 0f

    }


    fun resetSmartOrganize() { organizerScanResult = null; organizerError = null; organizerResult = null }

    fun fetchThumbStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder().url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/status").let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newBuilder().readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string() ?: "{}")
                        withContext(Dispatchers.Main) {
                            thumbGenerated = json.optInt("generated", 0)
                            thumbTotal = json.optInt("total_media", 0)
                            thumbErrors = json.optInt("errors", 0)
                            thumbRunning = json.optBoolean("running", false)
                            thumbPaused = json.optBoolean("paused", false)
                            thumbLastFile = json.optString("last_file", "")
                            thumbElapsed = json.optInt("elapsed_seconds", 0).toLong()
                            thumbEta = json.optInt("eta_seconds", -1).toLong()
                            thumbElapsedFmt = json.optString("elapsed_fmt", "00:00")
                            thumbEtaFmt = json.optString("eta_fmt", "--:--")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("SmartToolsVM", "fetchThumbStatus: ${e.message}") }
        }
    }

    fun stopThumbGeneration() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = org.json.JSONObject().put("action", "pause").toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/control")
                    .post(json).build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            withContext(Dispatchers.Main) { thumbRunning = false }
        }
    }

    fun toggleThumbPause() {
        val action = if (thumbPaused) "resume" else "pause"
        thumbPaused = action == "pause"
        if (thumbPaused) thumbRunning = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = org.json.JSONObject().put("action", action).toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/control").post(body).build()
                NasApplication.instance.fastApiClient.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) withContext(Dispatchers.Main) { thumbPaused = action != "pause" }
                }
                kotlinx.coroutines.delay(1500)
                fetchThumbStatus()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { withContext(Dispatchers.Main) { thumbPaused = action != "pause" } }
        }
    }

    fun fetchThumbnailAudit() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (apiBase.isBlank()) return@launch
                val apiUrl = "$apiBase/api/thumb/status"
                val user = com.nas.naswebdav.SecurePrefsHelper.getUser(NasApplication.instance.applicationContext)
                val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(NasApplication.instance.applicationContext)
                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val obj = org.json.JSONObject(body)
                        // so thumbnail thuc te tren dia (khong phai con tro quet nen)
                        val actualGenerated = obj.optInt("actual_generated", obj.optInt("generated", 0))
                        val actualTotal = obj.optInt("actual_total", obj.optInt("total_media", 0))
                        val actualMissing = obj.optInt("actual_missing",
                            if (actualTotal > actualGenerated) actualTotal - actualGenerated else 0)
                        // tien trinh quet nen (con tro quet), duoc liet ke roi bo qua neu da co thumb
                        val workObj = obj.optJSONObject("work")
                        val scanProgress = workObj?.optInt("completed", 0) ?: obj.optInt("scan_cursor", 0)
                        val scanTotal = obj.optInt("scan_total", workObj?.optInt("total", 0) ?: 0)
                        val actualStatus = obj.optString("actual_status", "")
                        _thumbnailAudit.value = ThumbnailAuditData(
                            total = actualTotal,
                            thumbnailed = actualGenerated,
                            missing = actualMissing,
                            running = obj.optBoolean("running", false),
                            paused = obj.optBoolean("paused", false),
                            errors = obj.optInt("errors", 0),
                            scanProgress = scanProgress,
                            scanTotal = scanTotal,
                            actualStatus = actualStatus
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("SmartToolsVM", "fetchThumbnailAudit failed", e)
            }
        }
    }

    fun triggerThumbnailScan() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (apiBase.isBlank()) return@launch
                val apiUrl = "$apiBase/api/thumb/control"
                val user = com.nas.naswebdav.SecurePrefsHelper.getUser(NasApplication.instance.applicationContext)
                val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(NasApplication.instance.applicationContext)
                val body = "{\"action\": \"resume\"}".toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { }
                kotlinx.coroutines.delay(1000)
                fetchThumbnailAudit()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("SmartToolsVM", "triggerThumbnailScan failed", e)
            }
        }
    }

    fun smartOrganizeScan(filter: OrganizerFilter, path: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    organizerScanning = true; organizerScanResult = null; organizerResult = null; organizerError = null
                }
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val filterStr = when (filter) { OrganizerFilter.IMAGE -> "image"; OrganizerFilter.VIDEO -> "video"; OrganizerFilter.ALL -> "all" }
                val body = org.json.JSONObject().apply {
                    put("filter", filterStr)
                    if (path.isNotBlank()) put("path", path)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("$apiBase/api/tools/smart_organize/scan").post(body).let(WebDavManager::tagCurrentAuth).build()
                val scanClient = NasApplication.instance.fastApiClient.newBuilder().readTimeout(3, java.util.concurrent.TimeUnit.MINUTES).build()
                scanClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful && responseBody != null) {
                            try {
                                val json = org.json.JSONObject(responseBody)
                                organizerTotalFiles = json.optInt("total", 0)
                                val groupsArr = json.optJSONArray("groups") ?: org.json.JSONArray()
                                val groups = mutableListOf<com.nas.naswebdav.OrganizerGroup>()
                                for (i in 0 until groupsArr.length()) {
                                    val g = groupsArr.getJSONObject(i)
                                    val samples = mutableListOf<String>()
                                    val sf = g.optJSONArray("sample_files") ?: org.json.JSONArray()
                                    for (j in 0 until sf.length()) samples.add(sf.getJSONObject(j).optString("name", ""))
                                    groups.add(com.nas.naswebdav.OrganizerGroup(label = g.optString("label", "?"), count = g.optInt("count", 0), size = g.optLong("size", 0L), sampleFiles = samples))
                                }
                                organizerScanResult = groups
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { organizerError = "Lỗi phân tích: ${e.message}" }
                        } else { organizerError = "Lỗi NAS: ${response.code}" }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { withContext(Dispatchers.Main) { organizerError = "Lỗi kết nối: ${e.message}" } }
            finally { withContext(Dispatchers.Main) { organizerScanning = false } }
        }
    }

    fun smartOrganizeExecute(filter: OrganizerFilter, path: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { organizerExecuting = true; organizerResult = null; organizerError = null }
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val filterStr = when (filter) { OrganizerFilter.IMAGE -> "image"; OrganizerFilter.VIDEO -> "video"; OrganizerFilter.ALL -> "all" }
                val body = org.json.JSONObject().apply {
                    put("filter", filterStr)
                    if (path.isNotBlank()) put("path", path)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("$apiBase/api/tools/smart_organize/execute").post(body).let(WebDavManager::tagCurrentAuth).build()
                val execClient = NasApplication.instance.fastApiClient.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
                execClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: throw Exception("Empty response body")
                    val json = org.json.JSONObject(responseBody)
                    val queued = response.code == 202 || json.optBoolean("queued", false)
                    if (queued) {
                        val jobId = json.optString("job_id", "")
                        if (jobId.isEmpty()) throw Exception("Missing job_id")
                        withContext(Dispatchers.Main) { organizerResult = "Đang sắp xếp..."; organizerScanResult = null }
                        val finalJson = pollSmartOrganizeJob(apiBase, jobId)
                        val finalStatus = finalJson.optString("status", "")
                        val movedCount = finalJson.optInt("moved_count", 0)
                        val errorCount = finalJson.optInt("error_count", 0)
                        val errorMessage = finalJson.optString("error", "")
                        withContext(Dispatchers.Main) {
                            organizerResult = when (finalStatus) {
                                "finished" -> "Hoàn tất — $movedCount tệp đã sắp xếp"
                                "finished_with_errors" -> "Hoàn tất — $movedCount tệp ($errorCount lỗi)"
                                else -> if (errorMessage.isNotEmpty()) errorMessage else "Smart Organizer thất bại"
                            }
                            if (finalStatus == "failed" || finalStatus == "aborted") { organizerError = organizerResult; organizerResult = null }
                            organizerScanResult = null
                        }
                    } else if (response.isSuccessful) {
                        withContext(Dispatchers.Main) { organizerResult = "Hoàn tất — ${json.optInt("moved_count", 0)} tệp"; organizerScanResult = null }
                    } else { throw Exception("Lỗi NAS: ${response.code}") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { withContext(Dispatchers.Main) { organizerError = "Lỗi kết nối: ${e.message}" } }
            finally { withContext(Dispatchers.Main) { organizerExecuting = false } }
        }
    }

    private suspend fun pollSmartOrganizeJob(apiBase: String, jobId: String): org.json.JSONObject {
        var delayMs = 1000L; val deadline = System.currentTimeMillis() + 20 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            val request = okhttp3.Request.Builder().url("$apiBase/api/tools/smart_organize/status/$jobId").get().let(WebDavManager::tagCurrentAuth).build()
            NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) throw Exception("Lỗi NAS: ${response.code}")
                val json = org.json.JSONObject(responseBody)
                when (json.optString("status", "")) {
                    "queued", "running" -> Unit
                    "finished", "finished_with_errors", "failed", "aborted" -> return json
                    else -> throw Exception("Trạng thái không hợp lệ")
                }
            }
            kotlinx.coroutines.delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(5000L)
        }
        throw java.util.concurrent.TimeoutException("Smart organizer timed out")
    }

    fun organizeLegacyVideos() {
        organizingLegacyRunning = true
        organizingLegacyResult = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (apiBase.isBlank()) {
                    withContext(Dispatchers.Main) {
                        organizingLegacyResult = "Chưa có địa chỉ NAS"
                    }
                    return@launch
                }
                val req = okhttp3.Request.Builder()
                    .url("$apiBase/api/tools/organize_legacy_videos")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    val ok = resp.isSuccessful && json.optBoolean("success", false)
                    val moved = json.optInt("moved_count", 0)
                    val errCount = json.optJSONArray("errors")?.length() ?: 0
                    val msg = if (ok) {
                        if (errCount > 0) "Đã di chuyển $moved video, $errCount lỗi" else "Đã di chuyển $moved video vào /Other Video/"
                    } else {
                        "Lỗi server: ${json.optString("message", body)}"
                    }
                    withContext(Dispatchers.Main) { organizingLegacyResult = msg }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    organizingLegacyResult = "Lỗi: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { organizingLegacyRunning = false }
            }
        }
    }
    fun resetOrganizingLegacy() { organizingLegacyRunning = false; organizingLegacyResult = null }
}
