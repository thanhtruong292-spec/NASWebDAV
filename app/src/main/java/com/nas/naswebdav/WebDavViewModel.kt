package com.nas.naswebdav

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Stack
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import android.app.ActivityManager
import android.net.TrafficStats
import android.os.Process
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import com.nas.naswebdav.utils.ImageFingerprint
import androidx.work.WorkManager

// FIX ERROR HANDLING: Chuyển lỗi kỹ thuật thành thông báo dễ hiểu
private fun friendlyError(e: Exception): String = when (e) {
    is java.net.SocketTimeoutException -> "Kết nối tới NAS quá chậm hoặc NAS không phản hồi. Vui lòng kiểm tra mạng."
    is java.net.ConnectException -> "Không thể kết nối tới NAS. Kiểm tra NAS đã bật và cùng mạng WiFi."
    is java.net.UnknownHostException -> "Địa chỉ NAS không hợp lệ hoặc mất kết nối mạng."
    is javax.net.ssl.SSLException -> "Lỗi bảo mật kết nối. Kiểm tra cấu hình SSL/TLS của NAS."
    else -> e.message ?: "Lỗi không xác định"
}

// THÊM DATA CLASS CHO TORRENT
data class TorrentInfo(
    val name: String,
    val progress: Float,
    val speed: String,
    val hash: String = "",
    val state: String = "",
    val savePath: String = ""
)

// DATA CLASS CHO SMART VÀ SPEED TEST
data class SmartInfo(val status: String, val temperature: String, val rawLog: String)
data class SpeedTestResult(val writeSpeed: String, val readSpeed: String)

// DATA CLASS CHO OMV OVERVIEW
data class OmvServiceInfo(val name: String, val title: String, val enabled: Boolean, val running: Boolean)
data class OmvNetworkInfo(val name: String, val address: String, val mac: String, val speed: Int, val state: String, val gateway: String, val wol: Boolean)
data class OmvFilesystem(val device: String, val label: String, val mountpoint: String, val used: String, val sizeBytes: Long, val percentage: Int, val description: String)
data class OmvDiskInfo(val name: String, val model: String, val serial: String, val size: String, val isRoot: Boolean)
data class OmvOverview(
    val hostname: String = "", val omvVersion: String = "", val kernel: String = "",
    val services: List<OmvServiceInfo> = emptyList(),
    val network: List<OmvNetworkInfo> = emptyList(),
    val filesystems: List<OmvFilesystem> = emptyList(),
    val disks: List<OmvDiskInfo> = emptyList(),
    val powerBtnAction: String = ""
)

// DATA CLASS CHO DOCKER
data class DockerContainer(val id: String, val name: String, val status: String)

// DATA CLASS CHO SMART ORGANIZER
data class OrganizerGroup(
    val label: String,
    val count: Int,
    val size: Long,
    val sampleFiles: List<String>
)

enum class OrganizerFilter { ALL, IMAGE, VIDEO }

// DATA CLASS CHO PHÂN TÍCH Ổ ĐĨA
data class DiskPart(
    val mount: String,
    val percent: Float,
    val total: String,
    val used: String
)

// Data class lưu trữ trạng thái hệ thống qua Local API
data class NasSystemStatus(
    val temp: String = "--°C",
    val cpu: String = "--%",
    val cpuTemp: String = "--°C",
    val ram: String = "--",
    val disk: String = "--%",
    val diskCapacity: String = "",
    val netRx: String = "0 B/s",
    val netTx: String = "0 B/s",
    val uptime: String = "--:--",
    val status: String = "Đang kết nối...",
    val ramPercent: String = "0",
    val torrents: List<TorrentInfo> = emptyList(),
    val diskParts: List<DiskPart> = emptyList(),
    val fanStatus: String = "--",  // Trạng thái quạt (Dừng / Đang chạy)
    val fanMode: String = "auto",  // auto, on, off, custom
    val fanOnTemp: Float = 65f,
    val fanOffTemp: Float = 55f,
    val fanRpm: Int? = null,       // Số vòng quạt (nếu có)
    val topProcesses: List<Pair<String, Float>> = emptyList() // Top tiến trình ăn CPU
)

// DATA CLASS CHO GUEST PASS
data class GuestPassInfo(
    val username: String,
    val password: String,
    val host: String,
    val ftpPort: Int,
    val expiresAt: Long    // Unix timestamp milliseconds
)

// DATA CLASS CHO PROCESS LIST
data class SystemProcess(
    val pid: Int,
    val name: String,
    val user: String,
    val status: String,
    val cpu: Float,
    val mem: Float
)

// DATA CLASS CHO SOCIAL DOWNLOAD HISTORY
data class SocialDownloadItem(
    val url: String,
    val platform: String,
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// DATA CLASS CHO BIỂU ĐỒ GIÁM SÁT
data class MetricsSnapshot(
    val timestamp: String = "",
    val cpuPercent: Float = 0f,
    val ramPercent: Float = 0f,
    val cpuTemp: Float = 0f,
    val hddTemp: Float = 0f,
    val netRxKbps: Float = 0f,
    val netTxKbps: Float = 0f
)

data class DailyReportData(
    val date: String = "",
    val healthScore: Int = 0,
    val cpuAvg: Float = 0f,
    val cpuPeak: Float = 0f,
    val ramAvg: Float = 0f,
    val ramPeak: Float = 0f,
    val cpuTempAvg: Float = 0f,
    val cpuTempPeak: Float = 0f,
    val hddTempAvg: Float = 0f,
    val hddTempPeak: Float = 0f,
    val downloadMb: Float = 0f,
    val uploadMb: Float = 0f,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val samples: Int = 0
)

/**
 * Kiểm tra URL có trỏ đến một địa chỉ Tailscale hay không.
 * Tailscale dùng dải CGNAT 100.64.0.0/10 (octet 2 từ 64 đến 127).
 * VD: 100.90.135.102 → Tailscale ✅
 *     192.168.100.5  → LAN bình thường ✅ (KHÔNG bị nhầm)
 *     100.20.1.1     → LAN bình thường (ngoài dải Tailscale) ✅
 */
fun isTailscaleUrl(url: String): Boolean {
    if (url.isBlank()) return false
    // Kiểm tra từ khóa "tailscale" trong URL (cho hostname dạng tailscale)
    if (url.contains("tailscale", ignoreCase = true)) return true
    return try {
        val host = java.net.URL(url).host ?: return false
        val parts = host.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            // Dải Tailscale: 100.64.x.x – 100.127.x.x
            a == 100 && b in 64..127
        } else false
    } catch (_: Exception) { false }
}

class WebDavViewModel(val webDavManager: WebDavManager, val repository: WebDavRepository) : ViewModel() {


    // CHỐNG RÒ RỈ THREAD VÀ BỘ NHỚ: Dùng chung một OkHttpClient duy nhất cho toàn bộ các truy vấn Local API
    internal val localApiClient: okhttp3.OkHttpClient by lazy {
        NasApplication.instance.fastApiClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // BẢN VÁ LỖI API TỪ CHỐI: Tự động đính kèm Header Authorization cho TẤT CẢ các request Local API
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                val user = webDavManager.currentUser
                val pass = webDavManager.currentPass
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    val credential = okhttp3.Credentials.basic(user, pass)
                    requestBuilder.header("Authorization", credential)
                }
                chain.proceed(requestBuilder.build())
            }
            .build()
    }
    // Biến lưu trữ trạng thái giám sát hệ thống (Local API)
    var systemStatus by mutableStateOf(NasSystemStatus())
    var temperatureHistory by mutableStateOf(kotlin.collections.ArrayDeque<Pair<Float, Float>>())

    // ─── BIỂU ĐỒ GIÁM SÁT REAL-TIME ──────────────────────────────────────────────
    var metricsHistory = androidx.compose.runtime.mutableStateListOf<MetricsSnapshot>()
    var metricsHours by mutableIntStateOf(1)         // 1 / 6 / 24 giờ
    var metricsChartTab by mutableIntStateOf(0)       // 0=Nhiệt độ, 1=Tài nguyên, 2=Mạng
    var isLoadingMetrics by mutableStateOf(false)
    var metricsError by mutableStateOf<String?>(null)  // Nếu có lỗi, hiển thị thay vì spinner vô hạn
    var dailyReport by mutableStateOf<DailyReportData?>(null)
    var isDailyReportLoading by mutableStateOf(false)

    // STATE CHO TIẾN TRÌNH HỆ THỐNG
    var systemProcesses by mutableStateOf<List<SystemProcess>>(emptyList())
    var isLoadingProcesses by mutableStateOf(false)
    private var metricsPollingJob: kotlinx.coroutines.Job? = null
    internal var statusJob: kotlinx.coroutines.Job? = null

    // TÍNH NĂNG 4.H: Lắng nghe trạng thái mạng Ping (ms)
    var networkPingMs by mutableStateOf<Long?>(null)

    // ─── SMART NETWORK – trạng thái đang dùng LAN hay Tailscale ───────────────
    var isOnLan by mutableStateOf(true) // true = LAN, false = Tailscale

    // ─── GUEST PASS STATE ─────────────────────────────────────────────────────
    var activeGuestPass by mutableStateOf<GuestPassInfo?>(null)
    var isGuestPassLoading by mutableStateOf(false)
    var guestPassError by mutableStateOf<String?>(null)

    // ─── SOCIAL EXTRACTOR STATE ───────────────────────────────────────────────
    var socialExtractStatus by mutableStateOf("")
    var isSocialExtracting by mutableStateOf(false)
    var socialDownloadHistory by mutableStateOf<List<SocialDownloadItem>>(emptyList())

    // ─── STREAM PIPE STATE (Điện thoại bơm CDN → NAS trực tiếp) ──────────────
    var isStreamPiping      by mutableStateOf(false)     // Đang bơm stream
    var streamPipeStatus    by mutableStateOf("")        // Mô tả trạng thái hiện tại
    var streamPipeProgress  by mutableFloatStateOf(0f)   // 0.0 → 1.0 (nếu biết size)
    var streamPipeSpeedStr  by mutableStateOf("-- MB/s") // Tốc độ dạng text
    var streamPipeEtaStr    by mutableStateOf("--")      // ETA dạng text
    private var streamPipeJob: kotlinx.coroutines.Job? = null
    private var _activeStreamPipeWorkId: java.util.UUID? = null
    private var livestreamObserverJob: kotlinx.coroutines.Job? = null

    // LOẠI BỎ fileList GÂY OOM, THAY BẰNG PAGING DATA FLOW
    var fileList by mutableStateOf<List<NasFile>>(emptyList()) // Giữ lại dự phòng cho tính năng tìm kiếm/đặc biệt

    private val _pagedFilesFlow = MutableStateFlow<Flow<PagingData<NasFile>>>(emptyFlow())
    val pagedFilesFlow = _pagedFilesFlow.asStateFlow()

    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var connectionStatus by mutableStateOf("Đang kết nối...")

    // BIẾN CHO BATCH COPY / MOVE
    var isBatchProcessing by mutableStateOf(false)
    var batchProcessType by mutableStateOf("") // "COPY" hoặc "MOVE"
    var batchProcessProgress by mutableFloatStateOf(0f)
    var batchProcessCurrentFile by mutableStateOf("")

    // TÍNH NĂNG 7.M: Trạng thái chứa dữ liệu Text Preview
    var textPreviewContent by mutableStateOf<String?>(null)

    fun fetchTextPreview(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; textPreviewContent = null }
            val content = webDavManager.readFileText(url)
            withContext(Dispatchers.Main) { textPreviewContent = content; isLoading = false }
        }
    }

    // Tiến trình tải thumbnail
    var totalImagesInFolder by mutableIntStateOf(0)
    var loadedImagesCount by mutableIntStateOf(0)
    val imageLoadProgress: Float get() = if (totalImagesInFolder > 0) loadedImagesCount.toFloat() / totalImagesInFolder else 0f
    // Biến trạng thái cho tiến trình Auto Backup
    var isAutoBackupRunning by mutableStateOf(false)
    var autoBackupCurrentFile by mutableStateOf("")
    var autoBackupSourcePath by mutableStateOf("")
    var autoBackupDestPath by mutableStateOf("")
    var autoBackupProgress by mutableFloatStateOf(0f)
    var autoBackupProcessedCount by mutableIntStateOf(0)
    var autoBackupTotalCount by mutableIntStateOf(0)
    var autoBackupElapsedTime by mutableLongStateOf(0L)
    var autoBackupIsPaused by mutableStateOf(false)

    // === Đã gỡ bỏ tính năng Đồng bộ thư mục ===

    // Biến trạng thái cho tính năng Quét và Xóa file trùng lặp
    var isShowingDuplicates by mutableStateOf(false)
    var shouldAutoOpenDuplicates by mutableStateOf(false)
    var duplicateFilesList by mutableStateOf<List<NasFile>>(emptyList())
    var selectedDuplicates = androidx.compose.runtime.mutableStateListOf<NasFile>()
    var isScanningDuplicates by mutableStateOf(false)
    var isWorkerRunning by mutableStateOf(false)
    var scanDuplicatesCurrentFolderUrl by mutableStateOf("")
    var scanDuplicatesCurrentItemName by mutableStateOf("")
    var scanDuplicatesTotalScanned by mutableIntStateOf(0)
    var scanDuplicatesFound by mutableIntStateOf(0)
    var scanDuplicatesPercent by mutableFloatStateOf(0f) // Thanh tổng
    var scanDuplicatesCurrentStagePercent by mutableFloatStateOf(0f) // Thanh hiện tại
    var scanDuplicatesElapsedTime by mutableLongStateOf(0L) // Thời gian đã chạy
    var scanDuplicatesEstimatedTimeRemaining by mutableLongStateOf(-1L) // Thời gian còn lại dự kiến
    var scanDuplicatesIsFolder by mutableStateOf(false)
    var scanDuplicatesStage by mutableStateOf("Khởi động...") // PHASE 4: Giai đoạn hiện tại
    var scanDuplicatesStageNumber by mutableIntStateOf(1)      // Số thứ tự giai đoạn (1-4)
    var scanDuplicatesTotalStages by mutableIntStateOf(4)      // Tổng số giai đoạn
    var scanDuplicatesStageDescription by mutableStateOf("")   // Mô tả chi tiết giai đoạn
    internal var scanJob: kotlinx.coroutines.Job? = null
    
    // ĐIỀU KHIỂN QUÉT RÁC
    var scanDuplicatesIsPaused by mutableStateOf(false)
    fun togglePauseDuplicateScan() {
        scanDuplicatesIsPaused = !scanDuplicatesIsPaused
        DuplicateProgressState.isPaused.value = scanDuplicatesIsPaused
    }
    
    fun cancelDuplicateScan(context: Context) {
        DuplicateProgressState.isPaused.value = false
        scanDuplicatesIsPaused = false
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("Unique_Scan_V3")
        isWorkerRunning = false
    }
    
    // TÍNH NĂNG AUTO-CLEAN DUPLICATES
    var autoCleanEnabled by mutableStateOf(false)
    fun toggleAutoClean(context: Context, enabled: Boolean) {
        autoCleanEnabled = enabled
        // Lưu SharedPreferences
        context.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE).edit().putBoolean("auto_clean_enabled", enabled).apply()
        
        val workManager = androidx.work.WorkManager.getInstance(context)
        if (enabled) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) // Cần Wifi
                .build()
                
            val req = androidx.work.PeriodicWorkRequestBuilder<AutoDuplicateScanWorker>(7, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork("AutoCleanDuplicates", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            workManager.cancelUniqueWork("AutoCleanDuplicates")
        }
    }

    // --- QUẢN LÝ BẢO MẬT & PHÊ DUYỆT (DEVICE APPROVAL) ---
    var showApprovalDialog by mutableStateOf(false)
    var pendingIpAddress by mutableStateOf("")
    var approvalMessage by mutableStateOf("")
    var pendingCountryCode by mutableStateOf("VN")
    var weeklyReportText by mutableStateOf("Đang tải dữ liệu...")

    internal var webSocket: okhttp3.WebSocket? = null

    // TRÍCH XUẤT HOST CHUẨN ĐỂ FIX LỖI CRASH PORT (8822:5050)

    // --- QUẢN LÝ NHẬT KÝ HỆ THỐNG ---
    var showLogDialog by mutableStateOf(false)
    var systemLogsList by mutableStateOf<List<SystemLog>>(emptyList())

    // Trạng thái cho chế độ xem đặc biệt (Ảnh mới/Video gần đây)
    var isSpecialMode by mutableStateOf(false)
    var specialTitle by mutableStateOf("")

    // STATE CHO DIALOG THÔNG BÁO CHUNG TỪ VIEWMODEL
    var commonDialogMessage by mutableStateOf("")
    var commonDialogType by mutableStateOf(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS)
    var showCommonDialog by mutableStateOf(false)
    // FIX LỖI 5: Debounce – chỉ hiển thị dialog lỗi mất mạng mỗi 2 phút, tránh spam
    private var lastNetworkErrorDialogAt = 0L
    internal var lastFanModeSettingTime = 0L
    var isFanModeUpdating by mutableStateOf(false)
    private val NETWORK_ERROR_DIALOG_COOLDOWN_MS = 2 * 60 * 1000L // 2 phút

    // STATE CHO SMART DIALOG VÀ SPEED TEST
    var showSmartDialog by mutableStateOf(false)
    var smartInfo by mutableStateOf(SmartInfo("Đang tải...", "--", ""))
    var speedTestResult by mutableStateOf(SpeedTestResult("--", "--"))
    var isTestingSpeed by mutableStateOf(false)
    var lastAutoSpeedTime by mutableStateOf("")

    // STATE CHO DOCKER POWER
    var isDockerRunning by mutableStateOf(false)
    var isTogglingDocker by mutableStateOf(false)

    // STATE CHO DOCKER MANAGER
    var showDockerDialog by mutableStateOf(false)
    var dockerContainers by mutableStateOf<List<DockerContainer>>(emptyList())
    // Quản lý Nhật ký hệ thống
    var systemLogs by mutableStateOf(listOf<SystemLog>())
    var isFetchingDocker by mutableStateOf(false)

    // STATE CHO OMV OVERVIEW
    var omvOverview by mutableStateOf(OmvOverview())

    // STATE CHO LAN WHITELIST (tách logic ra khỏi UI)
    var lanWhitelistIps by mutableStateOf<List<String>>(emptyList())
    var lanWhitelistSubnets by mutableStateOf<List<String>>(emptyList())
    var lanWhitelistLoading by mutableStateOf(true)
    var lanWhitelistError by mutableStateOf("")
    var lanWhitelistStatus by mutableStateOf("")

    // STATE CHO SMART ORGANIZER (tách logic ra khỏi UI)
    var organizerScanning by mutableStateOf(false)
    var organizerExecuting by mutableStateOf(false)
    var organizerScanResult by mutableStateOf<List<OrganizerGroup>?>(null)
    var organizerTotalFiles by mutableIntStateOf(0)
    var organizerResult by mutableStateOf<String?>(null)
    var organizerError by mutableStateOf<String?>(null)

    // STATE CHO THUMBNAIL STATUS (API /api/thumb/status)
    var thumbGenerated by mutableStateOf(0)
    var thumbTotal by mutableStateOf(0)
    var thumbErrors by mutableStateOf(0)
    var thumbRunning by mutableStateOf(false)
    var thumbLastFile by mutableStateOf("")
    var thumbElapsed by mutableStateOf(0)
    var thumbEta by mutableStateOf(-1)
    var thumbElapsedFmt by mutableStateOf("00:00")
    var thumbEtaFmt by mutableStateOf("--:--")
    var thumbPaused by mutableStateOf(false)

    fun fetchThumbStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/status")
                    .build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string() ?: "{}")
                        withContext(Dispatchers.Main) {

                            thumbGenerated = json.optInt("generated", 0)
                        thumbTotal = json.optInt("total_media", 0)
                        thumbErrors = json.optInt("errors", 0)
                        thumbRunning = json.optBoolean("running", false)
                        thumbPaused = json.optBoolean("paused", false)
                        thumbLastFile = json.optString("last_file", "")
                        thumbElapsed = json.optInt("elapsed_seconds", 0)
                        thumbEta = json.optInt("eta_seconds", -1)
                        thumbElapsedFmt = json.optString("elapsed_fmt", "00:00")
                        thumbEtaFmt = json.optString("eta_fmt", "--:--")

                        }                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleThumbPause() {
        val action = if (thumbPaused) "resume" else "pause"
        // Optimistic UI: cập nhật trạng thái ngay lập tức để nút phản hồi tức thì
        thumbPaused = action == "pause"
        if (thumbPaused) thumbRunning = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val body = org.json.JSONObject().put("action", action)
                    .toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/control")
                    .post(body)
                    .build()
                localApiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Rollback nếu server từ chối
                        withContext(Dispatchers.Main) {
                            thumbPaused = action != "pause"
                        }
                    }
                }
                // Đợi server xử lý xong rồi mới refresh (tránh race condition)
                kotlinx.coroutines.delay(1500)
                fetchThumbStatus()
            } catch (e: Exception) {
                // Rollback + log lỗi
                withContext(Dispatchers.Main) {
                    thumbPaused = action != "pause"
                    repository.addSystemLog("WARNING", "Thumbnail", "Toggle pause thất bại: ${e.message?.take(80)}")
                }
            }
        }
    }

    // ĐỊNH NGHĨA THƯ MỤC THÙNG RÁC (Dấu chấm ở đầu để ẩn thư mục trên NAS)
    internal val TRASH_FOLDER_NAME = ".trash/"

    internal val urlStack = Stack<String>()

    var currentUrl by mutableStateOf("")
    // HÀM CONNECT_AND_LOAD BỊ XÓA BỎ VÌ DƯ THỪA. SẼ DÙNG HÀM CONNECT CHÍNH THỨC NẰM Ở CUỐI FILE.

    fun openFolder(file: NasFile) {
        urlStack.push(currentUrl)
        currentUrl = if (file.path.endsWith("/")) file.path else "${file.path}/"

        // SỬA LỖI: Xóa trắng màn hình lập tức để dọn luồng mạng và bắt đầu tải giao diện mới trơn tru
        fileList = emptyList()
        isLoading = true

        // LOG: Ghi nhật ký mở thư mục
        viewModelScope.launch(Dispatchers.IO) {
            // Removed folder navigation log
        }

        loadCurrentUrl()
    }
    fun openSpecificUrl(url: String, title: String) {
        // Fix cú pháp và đồng bộ tiêu đề Sub-menu
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
                try { webDavManager.createFolder(targetUrl) } catch(e: Exception) {}
            }
            withContext(Dispatchers.Main) { loadCurrentUrl() }
        }
    }

    fun refresh() {
        // SỬA LỖI REFRESH: Phân loại để gọi đúng hàm truy vấn DB cho sub-menu
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
        currentUrl = webDavManager.currentBaseUrl
        fileList = emptyList()
        isLoading = true
        loadCurrentUrl()
    }
    fun resetToRoot() = resetToDefaultMode()
    fun navigateToUrl(url: String) {
        currentUrl = url
        fileList = emptyList()
        isLoading = true
        loadCurrentUrl()
    }
    fun goBack(): Boolean {
        if (urlStack.isNotEmpty()) {
            currentUrl = urlStack.pop()

            // SỬA LỖI: Nhường toàn bộ băng thông cho lệnh lùi thư mục
            fileList = emptyList()
            isLoading = true

            // LOG: Ghi nhật ký lùi thư mục
            viewModelScope.launch(Dispatchers.IO) {
                // Removed back navigation log
            }

            loadCurrentUrl()
            return true
        }
        return false
    }
    fun showLatestPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Ảnh mới nhất" }
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            val photos = repository.getLatestPhotos()
            withContext(Dispatchers.Main) { fileList = photos; isLoading = false }
        }
    }

    fun showRecentVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Video gần đây" }
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            val videos = repository.getRecentVideos()
            withContext(Dispatchers.Main) { fileList = videos; isLoading = false }
        }
    }
    // TÍNH NĂNG TÌM KIẾM TOÀN CẦU
    fun searchGlobal(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Tìm kiếm: $keyword"; urlStack.clear() }
            val results = try { repository.searchGlobal(keyword) } catch(e: Exception) { emptyList() }
            withContext(Dispatchers.Main) { fileList = results; isLoading = false }
        }
    }
    // TÍNH NĂNG ĐIỀU KHIỂN NGUỒN VÀ DỊCH VỤ

    // GỬI LINK TẢI XUỐNG TỪ XA CHO NAS (QBITTORRENT / WGET)
    fun controlTorrent(action: String, hash: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val json = org.json.JSONObject().apply {
                    put("action", action)
                    put("hash", hash)
                }
                val requestBody = json.toString().toRequestBody(jsonMediaType)
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/torrent/control")
                    .post(requestBody)
                    .build()
                localApiClient.newCall(request).execute().use { }
            } catch(e: Exception) {}
        }
    }

    fun unzipFile(filePath: String) {
        val host = try { java.net.URL(webDavManager.currentBaseUrl).host } catch (_: Exception) { return }
        val uri = java.net.URI(filePath)
        val relativePath = uri.path.substringAfter("/webdav")
        val fileName = filePath.substringAfterLast("/")
        val jsonBody = org.json.JSONObject().apply {
            put("file_path", relativePath)
        }.toString()

        // Báo UI đang xử lý thông qua Notification do chạy ngầm
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "Tác vụ giải nén ($fileName) đang chạy ngầm trên NAS!"
        showCommonDialog = true

        // KIẾN TRÚC MỚI: Đẩy sang LongRunningApiWorker (Foreground Service)
        // → Tắt App vẫn chạy, hiển thị Notification tiến trình
        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "UNZIP")
            .putString("apiUrl", "${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/file/unzip")
            .putString("jsonBody", jsonBody)
            .putString("taskLabel", "Giải nén $fileName")
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<LongRunningApiWorker>()
            .setInputData(inputData)
            .addTag("LONG_RUNNING_API")
            .build()

        val context = NasApplication.instance.applicationContext
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork("Unzip_$fileName", androidx.work.ExistingWorkPolicy.REPLACE, workRequest)

        // Lắng nghe kết quả từ Worker
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { workInfo ->
                    if (workInfo != null) {
                        val status = workInfo.progress.getString("status") ?: ""
                        val message = workInfo.progress.getString("message") ?: ""

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            refresh()
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                            commonDialogMessage = message.ifEmpty { "Giải nén thành công!" }
                            showCommonDialog = true
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = message.ifEmpty { "Giải nén thất bại!" }
                            showCommonDialog = true
                        }
                    }
                }
        }
    }

    fun sendDownloadLink(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val jsonBody = org.json.JSONObject().apply { put("url", url) }.toString()
                val requestBody = jsonBody.toRequestBody(jsonMediaType)

                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/download")
                    .post(requestBody)
                    .build()
                localApiClient.newCall(request).execute().use { }
            } catch(e: Exception) {
                // Bỏ qua lỗi mạng nếu API chưa kịp phản hồi
            }
        }
    }

    // ============ GHI HÌNH LIVESTREAM (TikTok / Facebook / YouTube) ============
    data class LivestreamJob(
        val jobId: String,
        val platform: String,
        var status: String = "recording",
        var fileSize: String = "0 B",
        var duration: String = "0h00m00s",
        var durationSeconds: Long = 0,
        var speed: String = "",
        var outputFile: String = ""
    )

    data class TikTokLiveWatchUser(
        val username: String,
        val status: String = "watching",
        val lastCheck: String = "",
        val lastLive: String = "",
        val lastError: String = "",
        val jobId: String = ""
    )
    
    // Danh sách các stream đang ghi
    var activeLivestreams = androidx.compose.runtime.mutableStateListOf<LivestreamJob>()
        private set
    var livestreamMessage by mutableStateOf("")
        private set

    /** UI gọi để xóa message lỗi, hiện lại nút "BẮT ĐẦU GHI" */
    fun clearLivestreamMessage() { livestreamMessage = "" }

    var tiktokLiveWatchUsers = androidx.compose.runtime.mutableStateListOf<TikTokLiveWatchUser>()
        private set
    var tiktokLiveWatchError by mutableStateOf("")
        private set
    var tiktokExcludeEnabled by mutableStateOf(false)
        private set
    var tiktokExcludeStart by mutableStateOf("23:00")
        private set
    var tiktokExcludeEnd by mutableStateOf("07:00")
        private set
    var isLoadingTikTokWatch by mutableStateOf(false)
        private set

    var isStartingLivestream by mutableStateOf(false)
        private set

    private fun applyTikTokWatchJson(json: org.json.JSONObject) {
        tiktokLiveWatchUsers.clear()
        val arr = json.optJSONArray("users") ?: org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            tiktokLiveWatchUsers.add(
                TikTokLiveWatchUser(
                    username = obj.optString("username", ""),
                    status = obj.optString("status", "watching"),
                    lastCheck = obj.optString("last_check", ""),
                    lastLive = obj.optString("last_live", ""),
                    lastError = obj.optString("last_error", ""),
                    jobId = obj.optString("job_id", "")
                )
            )
        }
        tiktokExcludeEnabled = json.optBoolean("exclude_enabled", false)
        tiktokExcludeStart = json.optString("exclude_start", "23:00")
        tiktokExcludeEnd = json.optString("exclude_end", "07:00")
        tiktokLiveWatchError = ""
    }

    private fun tiktokWatchRequest(context: Context, path: String, body: org.json.JSONObject? = null): org.json.JSONObject {
        val requestBuilder = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}$path")
        if (body != null) {
            requestBuilder.post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
        }
        val user = SecurePrefsHelper.getUser(context)
        val pass = SecurePrefsHelper.getPass(context)
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
        }
        localApiClient.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string() ?: "{}"
            val result = org.json.JSONObject(text)
            if (!response.isSuccessful) {
                throw IllegalStateException(result.optString("error", "NAS tu choi (${response.code})"))
            }
            return result
        }
    }

    fun fetchTikTokLiveWatch(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoadingTikTokWatch = true }
            try {
                val json = tiktokWatchRequest(context, "/api/tiktok/live_watch")
                withContext(Dispatchers.Main) { applyTikTokWatchJson(json) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lỗi: ${e.message?.take(80) ?: "Chưa kết nối NAS"}" }
            } finally {
                withContext(Dispatchers.Main) { isLoadingTikTokWatch = false }
            }
        }
    }

    fun addTikTokLiveWatchUser(context: Context, username: String) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoadingTikTokWatch = true }
            try {
                val json = tiktokWatchRequest(context, "/api/tiktok/live_watch/add", org.json.JSONObject().put("username", clean))
                withContext(Dispatchers.Main) { applyTikTokWatchJson(json) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lỗi: ${e.message?.take(80) ?: "Không thêm được user"}" }
            } finally {
                withContext(Dispatchers.Main) { isLoadingTikTokWatch = false }
            }
        }
    }

    fun removeTikTokLiveWatchUser(context: Context, username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = tiktokWatchRequest(context, "/api/tiktok/live_watch/remove", org.json.JSONObject().put("username", username))
                withContext(Dispatchers.Main) { applyTikTokWatchJson(json) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lỗi: ${e.message?.take(80) ?: "Không xóa được user"}" }
            }
        }
    }

    fun updateTikTokLiveWatchSettings(context: Context, enabled: Boolean, start: String = tiktokExcludeStart, end: String = tiktokExcludeEnd) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = org.json.JSONObject().apply {
                    put("exclude_enabled", enabled)
                    put("exclude_start", start)
                    put("exclude_end", end)
                }
                val json = tiktokWatchRequest(context, "/api/tiktok/live_watch/settings", body)
                withContext(Dispatchers.Main) { applyTikTokWatchJson(json) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lỗi: ${e.message?.take(80) ?: "Không lưu được cấu hình"}" }
            }
        }
    }

    fun startLivestreamRecord(context: Context, url: String, quality: String = "best", referer: String = "", userAgent: String = "") {
        isStartingLivestream = true
        livestreamMessage = "Đang phân tích liên kết & kết nối..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = org.json.JSONObject().apply {
                    put("url", url)
                    put("quality", quality)
                    put("referer", referer)
                    put("user_agent", userAgent)
                }.toString().toRequestBody(jsonMediaType)

                val requestBuilder = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/livestream/record")
                    .post(body)

                val user = SecurePrefsHelper.getUser(context)
                val pass = SecurePrefsHelper.getPass(context)
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                }

                localApiClient.newCall(requestBuilder.build()).execute().use { response ->
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    if (response.isSuccessful) {
                        val jobId    = json.optString("job_id", "")
                        val platform = json.optString("platform", "")
                        
                        // Thêm vào danh sách active (mặc định trạng thái recording)
                        withContext(Dispatchers.Main) {
                            if (activeLivestreams.none { it.jobId == jobId }) {
                                activeLivestreams.add(LivestreamJob(jobId, platform))
                            }
                            livestreamMessage   = json.optString("message", "Đang khởi động ghi hình...")
                        }

                        // Khởi động Foreground Worker độc lập với vòng đời app
                        LivestreamMonitorWorker.enqueue(context, jobId, host, platform)

                        // Observe tiến trình từ Worker để cập nhật UI
                        observeLivestreamWorker(context)
                    } else {
                        val errMsg = json.optString("error", "Lỗi không xác định")
                        withContext(Dispatchers.Main) {
                            livestreamMessage = errMsg
                            commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = errMsg
                            showCommonDialog    = true
                        }
                    }
                }
            } catch (e: Exception) {
                val errMsg = "Lỗi kết nối NAS: ${e.message}"
                withContext(Dispatchers.Main) {
                    livestreamMessage   = errMsg
                    commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = errMsg
                    showCommonDialog    = true
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isStartingLivestream = false
                }
            }
        }
    }

    /** Gọi 1 lần khi app mở lại — tự đồng bộ lại trạng thái từ các Worker đang chạy ngầm */
    fun restoreLivestreamStateIfRunning(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val workInfos = androidx.work.WorkManager.getInstance(context)
                .getWorkInfosByTag("LIVESTREAM_ALL").get()
            
            val activeWorks = workInfos.filter {
                it.state == androidx.work.WorkInfo.State.RUNNING ||
                it.state == androidx.work.WorkInfo.State.ENQUEUED
            }
            
            withContext(Dispatchers.Main) {
                activeLivestreams.clear() // Xóa list cũ, nạp lại từ Worker
                
                for (work in activeWorks) {
                    val progress = work.progress
                    val jobId = progress.getString(LivestreamMonitorWorker.OUT_JOB_ID) ?: continue
                    
                    // Xây dựng lại data class
                    val job = LivestreamJob(
                        jobId = jobId,
                        platform = "", // Platform worker không trả ra (trừ khi format lại), nhưng UI sẽ có thể hiện placeholder icon
                        status = progress.getString(LivestreamMonitorWorker.OUT_STATUS) ?: "recording",
                        fileSize = progress.getString(LivestreamMonitorWorker.OUT_FILE_SIZE) ?: "0 B",
                        duration = progress.getString(LivestreamMonitorWorker.OUT_DURATION) ?: "0h00m00s",
                        speed = progress.getString(LivestreamMonitorWorker.OUT_SPEED) ?: "",
                        outputFile = progress.getString(LivestreamMonitorWorker.OUT_OUTPUT_FILE) ?: ""
                    )
                    
                    if (job.status == "recording") {
                        activeLivestreams.add(job)
                    }
                }
                
                if (activeWorks.isNotEmpty()) {
                    observeLivestreamWorker(context)
                }
            }
        }
    }

    /** Gọi ngầm để quét các luồng Livestream bị "bỏ quên" (zombie streams) trên NAS */
    fun syncLivestreamStateWithServer(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Phục hồi từ WorkManager trước (như bình thường)
                restoreLivestreamStateIfRunning(context)
                kotlinx.coroutines.delay(1000) // Đợi load local xong

                val apiBaseUrl = currentUrl.toApiBaseUrl()
                val requestBuilder = okhttp3.Request.Builder().url("$apiBaseUrl/api/livestream/status")
                
                val user = com.nas.naswebdav.SecurePrefsHelper.getUser(context)
                val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(context)
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                }
                
                localApiClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseStr = response.body?.string() ?: "{}"
                        val json = org.json.JSONObject(responseStr)
                        val jobsArray = json.optJSONArray("jobs") ?: org.json.JSONArray()
                        
                        var hasNewJobs = false
                        var hasRecordingJobs = false
                        for (i in 0 until jobsArray.length()) {
                            val jobObj = jobsArray.getJSONObject(i)
                            val status = jobObj.optString("status", "")
                            val jobId = jobObj.optString("job_id", "")
                            val platform = jobObj.optString("platform", "")
                            
                            if (status == "recording" && jobId.isNotEmpty()) {
                                hasRecordingJobs = true
                                // Nếu tiến trình đang chạy trên NAS nhưng điện thoại không biết (hoặc bị xoá cache data)
                                val alreadyTracked = activeLivestreams.any { it.jobId == jobId }
                                if (!alreadyTracked) {
                                    val host = java.net.URL(currentUrl).host
                                    withContext(Dispatchers.Main) {
                                        activeLivestreams.add(LivestreamJob(jobId, platform))
                                    }
                                    LivestreamMonitorWorker.enqueue(context, jobId, host, platform)
                                    hasNewJobs = true
                                }
                            } else if (jobId.isNotEmpty()) {
                                LivestreamMonitorWorker.cancelJob(context, jobId)
                            }
                        }
                        if (!hasRecordingJobs) {
                            LivestreamMonitorWorker.cancelAll(context)
                            withContext(Dispatchers.Main) {
                                activeLivestreams.clear()
                            }
                        }

                        if (hasNewJobs) {
                            withContext(Dispatchers.Main) { observeLivestreamWorker(context) }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LivestreamSync", "Failed to sync livestream states: ${e.message}")
            }
        }
    }

    private fun observeLivestreamWorker(context: Context) {
        // FIX: Cancel collector cũ trước khi tạo mới, tránh tích lũy N collectors chạy song song
        // gây thrashing UI khi mỗi collector đều process toàn bộ workInfoList
        livestreamObserverJob?.cancel()
        livestreamObserverJob = viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow("LIVESTREAM_ALL")
                .collect { workInfoList ->
                    for (info in workInfoList) {
                        val progress = info.progress
                        val jobId = progress.getString(LivestreamMonitorWorker.OUT_JOB_ID)
                            ?: info.outputData.getString(LivestreamMonitorWorker.OUT_JOB_ID)
                            ?: continue
                            
                        val status = progress.getString(LivestreamMonitorWorker.OUT_STATUS)
                            ?: info.outputData.getString(LivestreamMonitorWorker.OUT_STATUS)
                            
                        val index = activeLivestreams.indexOfFirst { it.jobId == jobId }
                        if (index != -1) {
                            val existing = activeLivestreams[index]
                            
                            // Worker hoàn tất
                            if (info.state.isFinished || status !in listOf(null, "recording")) {
                                activeLivestreams.removeAt(index)
                                livestreamMessage = when (status) {
                                    "finished" -> "✅ Ghi hình hoàn tất!"
                                    "stopped"  -> "⏹ Đã dừng ghi hình"
                                    "timeout"  -> "⏰ Tự động dừng (quá 12 giờ)"
                                    "error"    -> {
                                        val reason = progress.getString("error_reason") ?: ""
                                        if (reason.isNotEmpty()) "Lỗi: $reason" else "Lỗi: Nguồn Stream bị ngắt / File quá nhỏ!"
                                    }
                                    else       -> "Trạng thái báo cáo: $status"
                                }
                            } else {
                                // FIX: Tạo copy với tham số mới thay vì mutate var sau copy()
                                // Mutate var sau copy() không trigger Compose recomposition vì
                                // mutableStateListOf so sánh object identity, không deep-compare
                                val fs = progress.getString(LivestreamMonitorWorker.OUT_FILE_SIZE)
                                val dur = progress.getString(LivestreamMonitorWorker.OUT_DURATION)
                                val spd = progress.getString(LivestreamMonitorWorker.OUT_SPEED)
                                val outF = progress.getString(LivestreamMonitorWorker.OUT_OUTPUT_FILE)
                                
                                val updated = existing.copy(
                                    fileSize = if (!fs.isNullOrEmpty()) fs else existing.fileSize,
                                    duration = if (!dur.isNullOrEmpty()) dur else existing.duration,
                                    speed = if (!spd.isNullOrEmpty()) spd else existing.speed,
                                    outputFile = if (!outF.isNullOrEmpty()) outF else existing.outputFile
                                )
                                
                                activeLivestreams[index] = updated
                            }
                        }
                    }
                }
        }
    }

    fun stopLivestreamRecord(context: Context, jobId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Cancel Worker trước
                LivestreamMonitorWorker.cancelJob(context, jobId)

                // Gọi NAS stop API
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = org.json.JSONObject().apply {
                    put("job_id", jobId)
                }.toString().toRequestBody(jsonMediaType)

                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/livestream/stop")
                    .post(body)
                    .build()

                localApiClient.newCall(request).execute().use { }
                withContext(Dispatchers.Main) {
                    activeLivestreams.removeAll { it.jobId == jobId }
                    livestreamMessage   = "⏹ Đã dừng ghi hình. File đang được xử lý..."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { livestreamMessage = "Lỗi dừng ghi: ${e.message}" }
            }
        }
    }


    // ĐÁNH THỨC NAS BẰNG WAKE-ON-LAN (MAGIC PACKET)
    
    private fun loadCurrentUrl(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            errorMessage = null
            
            // SỬA LỖI CHÍ MẠNG TỪ PHASE 1: LUÔN LUÔN KẾT NỐI UI VỚI CSDL TRƯỚC TIÊN!
            // Khi Paging Flow trói buộc vào Room DB, mọi thay đổi dữ liệu từ NAS tải về sẽ lập tức bắn lên UI một cách Auto!
            _pagedFilesFlow.value = repository.getFilesStream(currentUrl).cachedIn(viewModelScope)

            // Lấy danh sách tĩnh để phục vụ ImageViewerScreen
            val cached = repository.getCachedFiles(currentUrl)
            fileList = cached.map { 
                NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) 
            }.filter { !it.name.startsWith(".") || isSpecialMode }

            // TỐI ƯU SMART REFRESH: Nếu không ép buộc Refresh và Cache đã có sẵn dữ liệu thì xong luôn!
            if (!forceRefresh && cached.isNotEmpty()) {
                isLoading = false
                // Chạy ngầm việc kiểm tra cập nhật mà không làm treo UI
                launch(Dispatchers.IO) {
                    try { repository.getRemoteFilesAndCache(currentUrl) } catch (e: Exception) {}
                }
                return@launch
            }

            // Nếu là Force Refresh (vd: Vừa Login xong) hoặc Lần đầu vào thư mục chưa có Cache -> Phải Đợi
            isLoading = true

            try {
                // 3 & 4. Uỷ quyền cho Repository tải luồng NAS và chèn toàn bộ vào Room DB
                repository.getRemoteFilesAndCache(currentUrl)
                // Lập tức Cập nhật lại FileList tĩnh cho chế độ xem ảnh Full-Screen
                val refreshedCached = repository.getCachedFiles(currentUrl)
                fileList = refreshedCached.map { 
                    NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) 
                }.filter { !it.name.startsWith(".") || isSpecialMode }

                // LOG + IP: Hiển thị IP NAS sau trạng thái kết nối
                val nasHost = try { java.net.URL(currentUrl).host } catch (_: Exception) { "" }
                connectionStatus = if (nasHost.isNotEmpty()) "Đã kết nối LAN: $nasHost" else "Đã kết nối LAN"
                viewModelScope.launch(Dispatchers.IO) {
                    // Removed redundant fetch log
                }
            } catch (e: Exception) {
                connectionStatus = "Lỗi kết nối" // Ép cập nhật trạng thái lỗi ngay lập tức dù có Cache hay không
                viewModelScope.launch(Dispatchers.IO) {
                    repository.addSystemLog("ERROR", "Browser", "Lỗi tải danh sách: ${e.message?.take(100)}")
                }
                if (fileList.isEmpty()) {
                    errorMessage = friendlyError(e)
                }
                // FIX LỖI 5: Debounce - chỉ bật dialog lỗi mạng nếu cách lần trước hơn 2 phút
                val now = System.currentTimeMillis()
                if (now - lastNetworkErrorDialogAt > NETWORK_ERROR_DIALOG_COOLDOWN_MS) {
                    lastNetworkErrorDialogAt = now
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = "Mất kết nối dữ liệu máy chủ NAS:\n${e.message}"
                    showCommonDialog = true
                }
            } finally {
                isLoading = false
            }
        }
    }

    // HELPER: Chèn Tác vụ vào Hàng đợi Offline WorkManager (TÍNH NĂNG 5.I)
    private fun enqueueOfflineAction(context: Context, actionType: String, sourcePath: String, destPath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as NasApplication).database
                db.syncActionDao().insert(SyncAction(
                    actionType = actionType,
                    sourcePath = sourcePath,
                    destPath = destPath
                ))
                
                // Báo WorkManager chạy khi có mạng
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val request = androidx.work.OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueue(request)
                
                // Hiển thị Dialog báo cho User
                withContext(Dispatchers.Main) {
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING
                    commonDialogMessage = "Không có kết nối. Lệnh '$actionType' đã được đưa vào kho lưu ngầm (Offline Queue)!"
                    showCommonDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lỗi khi lưu Offline Queue: ${e.message}" }
            }
        }
    }

    fun deleteFile(context: Context, file: NasFile) {
        // TỐI ƯU CỰC ĐẠI: UI Lạc quan (Optimistic UI) 
        // Ẩn file ngay lập tức khỏi biến RAM mà CHƯA CẦN đợi NAS phản hồi -> Xóa "Tức thì" (0ms)
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }

        // BÓC TÁCH: Đẩy việc liên lạc mạng NAS (chậm) vào luồng ngầm I/O, giải phóng luồng màn hình UI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trashUrl = webDavManager.currentBaseUrl + TRASH_FOLDER_NAME

                // CHẶN XOÁ VĨNH VIỄN: Chỉ cho phép di chuyển vào thùng rác
                if (!file.path.contains(TRASH_FOLDER_NAME)) {
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) {}
                    val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    webDavManager.renameFile(file.path, trashUrl + encodedName)
                    repository.addSystemLog("WARNING", "File Ops", "Đã di chuyển tệp '${file.name}' vào Thùng rác.")
                } else {
                    webDavManager.deleteFile(file.path)
                    repository.addSystemLog("WARNING", "File Ops", "Đã XÓA VĨNH VIỄN tệp '${file.name}'.")
                }
                // TRIỆT TIÊU refresh() VĨNH VIỄN: Tránh tải lại 5000 file chỉ vì xóa 1 thẻ
            } catch (e: Exception) {
                // Nhồi lại file vào giao diện nếu rớt mạng
                withContext(Dispatchers.Main) { fileList = oldList }
                
                // TÍNH NĂNG 5.I: Bẫy lỗi và tống vào Hàng Đợi Offline
                repository.addSystemLog("WARNING", "File Ops", "Xóa tệp '${file.name}' thất bại, đã đưa vào Offline Queue: ${e.message?.take(80)}")
                val trashUrl = webDavManager.currentBaseUrl + TRASH_FOLDER_NAME
                if (!file.path.contains(TRASH_FOLDER_NAME)) {
                    val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    enqueueOfflineAction(context, "RENAME", file.path, trashUrl + encodedName)
                } else {
                    enqueueOfflineAction(context, "DELETE", file.path)
                }
            }
        }
    }

    fun deleteMultipleFiles(context: Context, filesToDelete: List<NasFile>) {
        if (filesToDelete.isEmpty()) return

        // TỐI ƯU CỰC ĐẠI: UI Lạc quan cho HÀNG LOẠT FILE
        // Cùng lúc bốc hơi 100+ file ra khỏi List để giao diện trống ngay trong 0 mili-giây!
        val pathsToDelete = filesToDelete.map { it.path }.toSet()
        fileList = fileList.filter { it.path !in pathsToDelete }

        // KIẾN TRÚC MỚI: Đẩy toàn bộ tác vụ sang BatchOperationWorker (Foreground Service)
        // → Tiến trình KHÔNG BỊ HỦY khi App tắt, hiển thị trên Notification Bar
        enqueueBatchOperation(context, "DELETE", filesToDelete, "")
    }

    fun batchCopyFiles(context: Context, filesToCopy: List<NasFile>, destUrl: String) {
        if (filesToCopy.isEmpty()) return

        // KIẾN TRÚC MỚI: Đẩy tác vụ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "COPY", filesToCopy, destUrl)
    }

    fun batchMoveFiles(context: Context, filesToMove: List<NasFile>, destUrl: String) {
        if (filesToMove.isEmpty()) return

        // Tối ưu UI lạc quan: Giấu file ngay lập tức nếu di chuyển ra khỏi thư mục hiện tại
        if (!destUrl.startsWith(currentUrl)) {
            val pathsToMove = filesToMove.map { it.path }.toSet()
            fileList = fileList.filter { it.path !in pathsToMove }
        }

        // KIẾN TRÚC MỚI: Đẩy tác vụ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "MOVE", filesToMove, destUrl)
    }

    // ═══════════════════════════════════════════════════════
    // DISPATCH ENGINE: Đẩy tác vụ nặng sang Foreground Worker
    // Worker chạy độc lập với Activity — Tắt App vẫn hoạt động
    // ═══════════════════════════════════════════════════════
    private fun enqueueBatchOperation(context: Context, operation: String, files: List<NasFile>, destUrl: String) {
        isBatchProcessing = true
        batchProcessType = operation
        batchProcessProgress = 0f

        val payloadFile = try {
            val payloadDir = File(context.cacheDir, "batch_payloads").apply { mkdirs() }
            val file = File(payloadDir, "batch_${operation}_${System.currentTimeMillis()}.json")
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
            .putString("baseUrl", webDavManager.currentBaseUrl)
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<BatchOperationWorker>()
            .setInputData(inputData)
            .addTag("BATCH_OPERATION")
            .build()

        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork("BatchOperation_$operation", androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)

        // Lắng nghe tiến trình từ Worker để cập nhật UI (nếu App đang mở)
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { workInfo ->
                    if (workInfo != null) {
                        val completed = workInfo.progress.getInt("completed", 0)
                        val total = workInfo.progress.getInt("total", files.size)
                        batchProcessCurrentFile = workInfo.progress.getString("currentFile") ?: ""
                        batchProcessProgress = if (total > 0) completed.toFloat() / total else 0f

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED ||
                            workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            batchProcessProgress = 1f
                            isBatchProcessing = false
                            // Làm mới danh sách file sau khi Worker hoàn tất
                            if (operation == "COPY" || (operation == "MOVE" && destUrl.startsWith(currentUrl))) {
                                refresh()
                            }
                        }
                    }
                }
        }
    }

    fun restoreFile(context: Context, file: NasFile) {
        // TỐI ƯU CỰC ĐẠI: Xóa ảo tức thì khỏi giao diện Thùng rác
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // KHÔI PHỤC: Di chuyển file từ rác về thư mục gốc của NAS
                val targetUrl = webDavManager.currentBaseUrl + file.name
                webDavManager.renameFile(file.path, targetUrl)
                repository.addSystemLog("INFO", "File Ops", "Đã khôi phục tệp '${file.name}' từ Thùng rác.")
                // Bỏ refresh()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList }
                repository.addSystemLog("WARNING", "File Ops", "Khôi phục tệp '${file.name}' thất bại, Offline Queue: ${e.message?.take(80)}")
                val targetUrl = webDavManager.currentBaseUrl + file.name
                enqueueOfflineAction(context, "RENAME", file.path, targetUrl)
            }
        }
    }

    fun restoreMultipleFiles(context: Context, filesToRestore: List<NasFile>) {
        if (filesToRestore.isEmpty()) return

        // TỐI ƯU CỰC ĐẠI: UI Lạc quan cho HÀNG LOẠT FILE
        val pathsToRestore = filesToRestore.map { it.path }.toSet()
        fileList = fileList.filter { it.path !in pathsToRestore }

        // KIẾN TRÚC MỚI: Đẩy tác vụ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "RESTORE", filesToRestore, "")
    }
    fun renameFile(context: Context, file: NasFile, newName: String) {
        // TỐI ƯU CỰC ĐẠI: Đổi tên ảo trên bộ nhớ RAM -> Tốc độ hiển thị 0s
        val oldList = fileList
        val newUrl = currentUrl + newName
        val renamedFile = file.copy(name = newName, path = newUrl)
        fileList = oldList.map { if (it.path == file.path) renamedFile else it }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDavManager.renameFile(file.path, newUrl)
                repository.addSystemLog("INFO", "File Ops", "Đổi tên tệp '${file.name}' thành '${newName}'.")
                // Không refresh() để chống khựng giao diện
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList } // Hoàn nguyên tên cũ
                repository.addSystemLog("WARNING", "File Ops", "Đổi tên '${file.name}' thất bại, Offline Queue: ${e.message?.take(80)}")
                enqueueOfflineAction(context, "RENAME", file.path, newUrl)
            }
        }
    }
    fun createFolder(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                // Đảm bảo URL thư mục mới kết thúc bằng dấu gạch chéo '/'
                val newFolderUrl = currentUrl + folderName + "/"
                webDavManager.createFolder(newFolderUrl)
                repository.addSystemLog("SUCCESS", "File Ops", "Đã tạo thư mục mới: '$folderName'")
                withContext(Dispatchers.Main) { refresh() } // Tải lại danh sách sau khi tạo thành công
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "File Ops", "Tạo thư mục '$folderName' thất bại, Offline Queue: ${e.message?.take(80)}")
                val newFolderUrl = currentUrl + folderName + "/"
                enqueueOfflineAction(context, "CREATE_FOLDER", newFolderUrl)
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }
    // === Đã gỡ bỏ tính năng Upload lẻ tẻ và Đồng bộ ===

    /**
     * checkSmartNetwork() – Tự động phát hiện mạng và chuyển URL NAS phù hợp.
     *
     * Gọi hàm này khi:
     *  - User vào MainMenuScreen (resume app)
     *  - Dashboard refresh
     *  - User bấm nút refresh thủ công
     *
     * Cơ chế:
     *  1. Ping gateway LAN (ASUS RT-N12: 192.168.100.254 port 80)
     *  2. Nếu PASS → Đang ở LAN → reconnect bằng URL LAN (Gigabit nhanh)
     *  3. Nếu FAIL → Ra ngoài → reconnect bằng URL Tailscale (100.90.135.102)
     */
    fun checkSmartNetwork(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Invalidate cache để buộc kiểm tra thực sự (không dùng kết quả cũ)
                SmartNetworkManager.invalidateCache()
                val activeUrl = SmartNetworkManager.getActiveBaseUrl(context)
                if (activeUrl.isEmpty()) return@launch
                
                val onLan = !isTailscaleUrl(activeUrl)
                withContext(Dispatchers.Main) {
                    isOnLan = onLan
                }

                // Nếu URL thực tế khác URL đang dùng → tự động reconnect mượt
                val currentBase = webDavManager.currentBaseUrl
                val safeActive = if (activeUrl.endsWith("/")) activeUrl else "$activeUrl/"
                if (safeActive != currentBase && currentBase.isNotEmpty()) {
                    val user = webDavManager.currentUser
                    val pass = webDavManager.currentPass
                    withContext(Dispatchers.Main) {
                        connectionStatus = if (onLan) "🏠 Chuyển sang LAN – Gigabit" else "🌐 Chuyển sang Tailscale VPN"
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            webDavManager.connect(safeActive, user, pass)
                            webDavManager.initConnection()
                            
                            // Gọi authorize để IP mới được thêm vào whitelist/iptables trên NAS
                            val host = java.net.URL(safeActive).host
                            if (!host.isNullOrEmpty()) {
                                val authHeader = okhttp3.Credentials.basic(user, pass)
                                val authRequest = okhttp3.Request.Builder()
                                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/auth/authorize")
                                    .header("Authorization", authHeader)
                                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                                    .build()
                                try {
                                    NasApplication.instance.fastApiClient.newCall(authRequest).execute().use { }
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        currentUrl = safeActive
                        connectionStatus = if (onLan) "🏠 LAN – Gigabit" else "🌐 Tailscale VPN"
                    }
                    repository.addSystemLog(
                        "INFO", "SmartSwitch",
                        "Chuyển mạng: ${if (onLan) "LAN" else "Tailscale"} ($safeActive)"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("SmartSwitch", "checkSmartNetwork error: ${e.message}")
            }
        }
    }

    fun connect(urlList: List<String>, user: String, pass: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            var lastErrorDetail = "Unknown"

            withContext(Dispatchers.Main) {
                isLoading = true
                connectionStatus = "Đang kiểm tra môi trường LAN..."
                urlStack.clear()
            }

            // FIX LỖI 7 B: Đọc credentials cũ trước bước lưu tạm, để có thể REVERT nếu handshake thất bại
            val context = NasApplication.instance
            val oldUrlList = SecurePrefsHelper.getUrlList(context)
            val oldUser = SecurePrefsHelper.getUser(context)
            val oldPass = SecurePrefsHelper.getPass(context)

            withContext(Dispatchers.IO) {
                SecurePrefsHelper.saveCredentials(context, urlList, user, pass)
            }

            // FIX: Thử lần lượt từng URL (LAN → Tailscale) mà không gây race condition
            // Vòng lặp tuần tự tránh lỗi split-tunneling cache của Android
            val errorDetails = mutableListOf<String>()
            var connectedUrl = ""
            var result = false

            val result2 = withContext(Dispatchers.IO) {
                for (activeUrl in urlList) {
                    if (activeUrl.isBlank()) continue

                    val safeUrl = if (activeUrl.isNotEmpty() && !activeUrl.endsWith("/")) "$activeUrl/" else activeUrl

                    withContext(Dispatchers.Main) {
                        currentUrl = safeUrl
                        connectionStatus = "Đang kết nối: $safeUrl"
                        isOnLan = !isTailscaleUrl(safeUrl)
                    }

                    try {
                        webDavManager.connect(safeUrl, user, pass)

                        // BƯỚC 1: XÁC THỰC BẰNG CHÍNH WEBDAV (CỔNG CHÍNH 8822/80)
                        val pingResult = webDavManager.checkPingServer()
                        if (pingResult == null || pingResult < 0) {
                            errorDetails.add("$activeUrl: WebDAV timeout/unauthorized")
                            continue
                        }

                        // ✅ Kết nối thành công! Ghi nhận và thoát khỏi vòng lặp
                        SecurePrefsHelper.saveCredentials(NasApplication.instance, urlList, user, pass)
                        repository.addSystemLog("SUCCESS", "Network", "Truy cập WebDAV thành công qua User '$user' tại IP: $activeUrl")

                        connectedUrl = activeUrl

                        // BƯỚC 2: XÁC THỰC API PHỤ (Port 5050 cho System Stats)
                        val parsedUrl = try { java.net.URL(safeUrl) } catch (_: Exception) { null }
                        val host = parsedUrl?.host
                        if (!host.isNullOrEmpty()) {
                            NasApplication.applicationScope.launch(Dispatchers.IO) {
                                try {
                                    val authHeader = okhttp3.Credentials.basic(user, pass)
                                    val cleanClient = NasApplication.instance.fastApiClient.newBuilder()
                                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    val request = okhttp3.Request.Builder()
                                        .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/auth/authorize")
                                        .header("Authorization", authHeader)
                                        .post(ByteArray(0).toRequestBody(null, 0, 0))
                                        .build()
                                    cleanClient.newCall(request).execute().use { }
                                } catch (e: Exception) {
                                    android.util.Log.w("NAS_AUTH", "API Phụ ${AppConfig.API_PORT} Warning: ${e.message}")
                                }
                            }
                        }
                        return@withContext true
                    } catch (e: Exception) {
                        android.util.Log.e("NAS_AUTH", "Lỗi kết nối $activeUrl: ${e.message}")
                        errorDetails.add("$activeUrl: ${e.message ?: "Network Timeout"}")
                    }
                }
                // Tất cả URL đều thất bại
                lastErrorDetail = if (errorDetails.isNotEmpty()) errorDetails.joinToString(", ") else "Không kết nối được NAS"
                false
            }

            withContext(Dispatchers.Main) {
                isLoading = false
                if (result2) {
                    connectionStatus = "Đã xác thực thành công"
                    onSuccess()
                } else {
                    connectionStatus = "Lỗi xác thực"
                    onError("Thất bại. Vui lòng kiểm tra lại. \\nMã lỗi: $lastErrorDetail")
                }
            }

            if (result2) { refresh() }
        }
    }

    suspend fun pingUrlsForDisplay(urlList: List<String>, user: String, pass: String): Map<String, Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        urlList.associate { url ->
            url to try {
                val isTailscale = isTailscaleUrl(url)
                val timeoutSec = if (isTailscale) 5L else 2L
                val pingClient = NasApplication.instance.sharedHttpClient.newBuilder()
                    .connectTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val safeUrl = if (url.endsWith("/")) url else "$url/"
                val start = System.currentTimeMillis()
                val request = okhttp3.Request.Builder()
                    .url(safeUrl)
                    .method("OPTIONS", null)
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .build()

                val response = pingClient.newCall(request).execute()
                response.use {
                    System.currentTimeMillis() - start
                }
            } catch (_: Exception) {
                -1L
            }
        }
    }

    init {
        // Cập nhật trạng thái Auto Backup từ WorkManager
        viewModelScope.launch {
            try {
                androidx.work.WorkManager.getInstance(NasApplication.instance.applicationContext)
                    .getWorkInfosByTagFlow("com.nas.naswebdav.AutoBackupWorker").collect { workInfos ->
                        val workInfo = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING }
                        if (workInfo != null) {
                            isAutoBackupRunning = true
                            autoBackupProgress = workInfo.progress.getFloat("progress", 0f)
                            autoBackupCurrentFile = workInfo.progress.getString("fileName") ?: "Đang sao lưu..."
                            autoBackupSourcePath = workInfo.progress.getString("sourcePath") ?: ""
                            autoBackupDestPath = workInfo.progress.getString("destPath") ?: ""
                            autoBackupProcessedCount = workInfo.progress.getInt("processedCount", 0)
                            autoBackupTotalCount = workInfo.progress.getInt("totalCount", 0)
                            autoBackupElapsedTime = workInfo.progress.getLong("elapsedTime", 0L)
                            autoBackupIsPaused = AutoBackupState.isPaused.value
                        } else {
                            isAutoBackupRunning = false
                        }
                    }
            } catch (e: Exception) {}
        }

        // KIẾN TRÚC MỚI: Đồng bộ hóa khép kín (Khôi phục UI State khi App tái khởi động từ cõi chết)
        val workManager = androidx.work.WorkManager.getInstance(NasApplication.instance.applicationContext)
        
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("BATCH_OPERATION").collect { workInfos ->
                val active = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                if (active != null) {
                    isBatchProcessing = true
                    batchProcessProgress = active.progress.getInt("percent", 0).toFloat() / 100f
                    batchProcessCurrentFile = active.progress.getString("currentFile") ?: "Khôi phục đồng bộ..."
                } else if (isBatchProcessing) {
                    isBatchProcessing = false
                    refresh() // Cập nhật lại danh sách file khi Background Worker vừa hoàn tất
                }
            }
        }

        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("STREAM_PIPE_TASK").collect { workInfos ->
                val active = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                if (active != null) {
                    isStreamPiping = true
                    streamPipeStatus = "Khôi phục đồng bộ: " + (active.progress.getString("status") ?: "Đang tải ngầm...")
                    streamPipeProgress = active.progress.getInt("progress", 0).toFloat() / 100f
                    streamPipeSpeedStr = active.progress.getString("speedStr") ?: "Đồng bộ..."
                    streamPipeEtaStr = if (active.progress.getLong("etaSec", 0L) > 0) "${active.progress.getLong("etaSec", 0L)}s" else "--"
                }
            }
        }

        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("LONG_RUNNING_API").collect { workInfos ->
                val active = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                if (active != null) {
                    val taskLabel = active.progress.getString("taskLabel") ?: ""
                    if (taskLabel.contains("Gom video")) {
                        organizingLegacyRunning = true
                        organizingLegacyResult = active.progress.getString("status") ?: "Đang gom video ngầm..."
                    }
                }
            }
        }
    
        listenToLocalNasApi()

        // FIX A2: Thay vòng lặp polling while(true){delay(32)} bằng combine() trên StateFlow.
        // Cũ: Vòng lặp chạy liên tục @30fps kể cả khi không scan → tiêu hao CPU/pin vô ích.
        // Mới: Chỉ emit khi một trong các StateFlow thực sự thay đổi → 0% CPU khi idle.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                DuplicateProgressState.stage,
                DuplicateProgressState.currentFolderUrl,
                DuplicateProgressState.percent,
                DuplicateProgressState.scannedCount,
                DuplicateProgressState.elapsedTime
            ) { stage, folderUrl, percent, scanned, elapsed ->
                // Trả về tuple để trigger collector khi BẤT KỲ field nào thay đổi
                arrayOf(stage, folderUrl, percent, scanned, elapsed)
            }.collect {
                // Đồng bộ toàn bộ state từ DuplicateProgressState → ViewModel state
                scanDuplicatesCurrentFolderUrl = DuplicateProgressState.currentFolderUrl.value
                scanDuplicatesCurrentItemName  = DuplicateProgressState.itemName.value
                scanDuplicatesStage            = DuplicateProgressState.stage.value
                scanDuplicatesIsFolder         = DuplicateProgressState.isFolder.value
                scanDuplicatesTotalScanned     = DuplicateProgressState.scannedCount.value
                scanDuplicatesFound            = DuplicateProgressState.foundCount.value
                scanDuplicatesPercent          = DuplicateProgressState.percent.value
                scanDuplicatesCurrentStagePercent = DuplicateProgressState.currentStagePercent.value
                scanDuplicatesStageNumber      = DuplicateProgressState.stageNumber.value
                scanDuplicatesTotalStages      = DuplicateProgressState.totalStages.value
                scanDuplicatesStageDescription = DuplicateProgressState.stageDescription.value
                scanDuplicatesElapsedTime      = DuplicateProgressState.elapsedTime.value
                scanDuplicatesEstimatedTimeRemaining = DuplicateProgressState.estimatedTimeRemaining.value

                // Tự động bật Panel nếu Worker ngầm đang chạy (AutoScan / MainMenu)
                val stage = scanDuplicatesStage
                if (stage != "Hoàn tất" && stage.isNotEmpty() && stage != "Khởi động...") {
                    isScanningDuplicates = true
                    isWorkerRunning = true
                } else if (stage == "Hoàn tất") {
                    isWorkerRunning = false
                }
            }
        }

        // Khởi động vòng lặp kiểm tra sức khoẻ mạng (Ping ICMP siêu nhẹ)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                if (webDavManager.currentBaseUrl.isNotEmpty()) {
                    val ms = webDavManager.checkPingServer()
                    withContext(Dispatchers.Main) { networkPingMs = ms }
                    // Giao thức ICMP Ping tốn hầu như không đáng biểu đồ máy, cho phép quét 3s/lần!
                    kotlinx.coroutines.delay(3000)
                } else {
                    // Nếu chưa Login xong thì đợi 1s hỏi lại, tránh việc bắt User đợi tận 30s mới chọc Ping
                    kotlinx.coroutines.delay(1000)
                }
            }
        }

        // Khởi động vòng lặp lấy metrics biểu đồ:
        // Chờ cho URL sẵn sàng rồi mới fetch lần đầu, sau đó poll mỗi 30s
        launchMetricsPolling()
    }

    // Dọn các listener (nếu có)

    // =======================================================
    // ======== BIỂU ĐỒ GIÁM SÁT + BÁO CÁO NGÀY ============
    // =======================================================

    fun launchMetricsPolling() {
        metricsPollingJob?.cancel()
        metricsPollingJob = viewModelScope.launch(Dispatchers.IO) {
            // Chờ tối đa 60s cho đến khi URL sẵn sàng (tránh fetch khi chưa login)
            var waited = 0
            while (isActive && webDavManager.currentBaseUrl.isEmpty() && waited < 60) {
                delay(1_000L)
                waited++
            }
            // Lấy lần đầu ngay sau khi URL sẵn sàng
            if (isActive && webDavManager.currentBaseUrl.isNotEmpty()) {
                fetchMetricsHistory(metricsHours)
                startRealtimeAlerts()
            }
            // Sau đó poll mỗi 30 giây
            while (isActive) {
                delay(30_000L)
                if (webDavManager.currentBaseUrl.isNotEmpty()) {
                    fetchMetricsHistory(metricsHours)
                }
            }
        }
    }

    fun fetchMetricsHistory(hours: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = webDavManager.currentBaseUrl
            if (baseUrl.isEmpty()) return@launch
            withContext(Dispatchers.Main) {

                isLoadingMetrics = true

                metricsError = null

            }
            try {
                val apiBase = baseUrl.toApiBaseUrl()
                val url = "$apiBase/api/metrics/history?hours=$hours"
                val request = okhttp3.Request.Builder().url(url).build()
                localApiClient.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            metricsError = "Lỗi HTTP ${resp.code}: $bodyStr"
                        }
                        return@launch
                    }
                    val json = org.json.JSONObject(if (bodyStr.isEmpty()) "{}" else bodyStr)
                    if (json.has("error")) {
                        withContext(Dispatchers.Main) { metricsError = json.optString("error") }
                        return@launch
                    }
                    val timestamps = json.optJSONArray("timestamps") ?: run {
                        withContext(Dispatchers.Main) { metricsError = "Server trả về dữ liệu không hợp lệ" }
                        return@launch
                    }
                    val cpuArr   = json.optJSONArray("cpu_percent")
                    val ramArr   = json.optJSONArray("ram_percent")
                    val cptArr   = json.optJSONArray("cpu_temp")
                    val hdtArr   = json.optJSONArray("hdd_temp")
                    val rxArr    = json.optJSONArray("net_rx_kbps")
                    val txArr    = json.optJSONArray("net_tx_kbps")
                    val snaps = mutableListOf<MetricsSnapshot>()
                    for (i in 0 until timestamps.length()) {
                        snaps.add(MetricsSnapshot(
                            timestamp  = timestamps.optString(i),
                            cpuPercent = cpuArr?.optDouble(i)?.toFloat() ?: 0f,
                            ramPercent = ramArr?.optDouble(i)?.toFloat() ?: 0f,
                            cpuTemp    = cptArr?.optDouble(i)?.toFloat() ?: 0f,
                            hddTemp    = hdtArr?.optDouble(i)?.toFloat() ?: 0f,
                            netRxKbps  = rxArr?.optDouble(i)?.toFloat() ?: 0f,
                            netTxKbps  = txArr?.optDouble(i)?.toFloat() ?: 0f
                        ))
                    }
                    withContext(Dispatchers.Main) {
                        metricsHistory.clear()
                        metricsHistory.addAll(snaps)
                        metricsHours = hours
                        metricsError = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { metricsError = "Ấn Refresh để thử lại: ${e.message?.take(80)}" }
            } finally {
                withContext(Dispatchers.Main) { isLoadingMetrics = false }
            }
        }
    }

    fun fetchDailyReport(date: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = webDavManager.currentBaseUrl
            if (baseUrl.isEmpty()) return@launch
            withContext(Dispatchers.Main) { isDailyReportLoading = true }
            try {
                val apiBase = baseUrl.toApiBaseUrl()
                val dateParam = if (date.isNotEmpty()) "?date=$date" else ""
                val url = "$apiBase/api/report/daily$dateParam"
                val request = okhttp3.Request.Builder().url(url).build()
                localApiClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    val j = org.json.JSONObject(resp.body?.string() ?: "{}")
                    if (j.has("error")) return@launch
                    val cpu = j.optJSONObject("cpu")
                    val ram = j.optJSONObject("ram")
                    val ct  = j.optJSONObject("cpu_temp")
                    val ht  = j.optJSONObject("hdd_temp")
                    val net = j.optJSONObject("network")
                    val al  = j.optJSONObject("alerts")
                    withContext(Dispatchers.Main) {
                        dailyReport = DailyReportData(
                            date         = j.optString("date"),
                            healthScore  = j.optInt("health_score"),
                            cpuAvg       = cpu?.optDouble("avg")?.toFloat() ?: 0f,
                            cpuPeak      = cpu?.optDouble("peak")?.toFloat() ?: 0f,
                            ramAvg       = ram?.optDouble("avg")?.toFloat() ?: 0f,
                            ramPeak      = ram?.optDouble("peak")?.toFloat() ?: 0f,
                            cpuTempAvg   = ct?.optDouble("avg")?.toFloat() ?: 0f,
                            cpuTempPeak  = ct?.optDouble("peak")?.toFloat() ?: 0f,
                            hddTempAvg   = ht?.optDouble("avg")?.toFloat() ?: 0f,
                            hddTempPeak  = ht?.optDouble("peak")?.toFloat() ?: 0f,
                            downloadMb   = net?.optDouble("total_download_mb")?.toFloat() ?: 0f,
                            uploadMb     = net?.optDouble("total_upload_mb")?.toFloat() ?: 0f,
                            errorCount   = al?.optInt("errors") ?: 0,
                            warningCount = al?.optInt("warnings") ?: 0,
                            samples      = j.optInt("samples")
                        )
                    }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { isDailyReportLoading = false }
        }
    }

    // =======================================================
    // ======== CÁC HÀM XỬ LÝ API NỘI BỘ (LOCAL NAS API) ======
    // =======================================================

    // Các hàm lắng nghe System Monitor đã được chuyển ra SystemMonitorHelper.kt

    // ============ AI SMART PHOTOS ============
    var aiCategories by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var aiTotal by mutableStateOf(0)
    var aiLastScan by mutableStateOf("")
    var aiRunning by mutableStateOf(false)
    var aiStatus by mutableStateOf("Chưa có dữ liệu")
    var isLoadingAiTags by mutableStateOf(false)

    fun fetchAiTags() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoadingAiTags = true }
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/ai/tags")
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = org.json.JSONObject(resp.body?.string() ?: "{}")
                        val cats = json.optJSONObject("categories")
                        val result = mutableMapOf<String, List<String>>()
                        cats?.keys()?.forEach { key ->
                            val arr = cats.optJSONArray(key)
                            val urls = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
                            if (urls.isNotEmpty()) result[key] = urls
                        }
                        withContext(Dispatchers.Main) {
                            aiCategories = result
                            aiTotal = json.optInt("total", 0)
                            aiLastScan = json.optString("last_scan", "")
                            aiRunning = json.optBoolean("ai_running", false)
                            aiStatus = json.optString("status", "ok")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { aiStatus = "Lỗi kết nối: ${e.message?.take(60)}" }
            } finally {
                withContext(Dispatchers.Main) { isLoadingAiTags = false }
            }
        }
    }

    fun triggerAiScan(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/ai/trigger")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    val json = org.json.JSONObject(resp.body?.string() ?: "{}")
                    val msg = json.optString("message", "Đang quét phân loại ảnh...")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                        commonDialogMessage = msg
                        showCommonDialog = true
                    }
                }
            } catch (e: Exception) {}
        }
    }

    // ============ CRON / AUTOMATION ============
    fun cleanTrashOnDemand(context: Context, maxAgeDays: Int = 30) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val json = org.json.JSONObject().put("max_age_days", maxAgeDays)
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/cron/trash/clean")
                    .post(body)
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    val res = org.json.JSONObject(resp.body?.string() ?: "{}")
                    val msg = res.optString("message", "Hoàn tất dọn Thùng rác!")
                    repository.addSystemLog("INFO", "File Ops", "Người dùng đã thực hiện XÓA THÙNG RÁC: $msg")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                        commonDialogMessage = msg
                        showCommonDialog = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = "Lỗi dọn rác: ${e.message}"
                    showCommonDialog = true
                }
            }
        }
    }

    // ============ GUEST PASS ============

    /**
     * Gọi POST /api/guest/create → NAS tạo FTP user tạm thời read-only.
     * Response JSON: { username, password, host, ftp_port, expires_at_unix }
     */
    fun createGuestPass(durationMinutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isGuestPassLoading = true
                guestPassError = null
            }
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val json = org.json.JSONObject().apply {
                    put("duration_minutes", durationMinutes)
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/guest/create")
                    .post(body)
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val res = org.json.JSONObject(resp.body?.string() ?: "{}")
                        val expiresAtUnix = res.optLong("expires_at_unix", 0L)
                        val pass = GuestPassInfo(
                            username  = res.optString("username", "guest"),
                            password  = res.optString("password", ""),
                            host      = res.optString("host", host),
                            ftpPort   = res.optInt("ftp_port", 21),
                            expiresAt = if (expiresAtUnix > 0) expiresAtUnix * 1000L
                                        else System.currentTimeMillis() + durationMinutes * 60_000L
                        )
                        withContext(Dispatchers.Main) { activeGuestPass = pass }
                        repository.addSystemLog("SUCCESS", "GuestPass",
                            "Đã cấp Guest FTP: user='${pass.username}', hết hạn sau $durationMinutes phút")
                    } else {
                        val errBody = resp.body?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            guestPassError = "NAS từ chối (${resp.code}): $errBody"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guestPassError = "Lỗi kết nối API: ${e.message}"
                }
                repository.addSystemLog("ERROR", "GuestPass", "Tạo Guest Pass lỗi: ${e.message?.take(80)}")
            } finally {
                withContext(Dispatchers.Main) { isGuestPassLoading = false }
            }
        }
    }

    /**
     * Gọi POST /api/guest/revoke → NAS xóa FTP user tạm thời.
     */
    fun revokeGuestPass() {
        val pass = activeGuestPass ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isGuestPassLoading = true }
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val json = org.json.JSONObject().put("username", pass.username)
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/guest/revoke")
                    .post(body)
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        activeGuestPass = null
                        guestPassError = null
                        commonDialogType = if (resp.isSuccessful)
                            com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                        else
                            com.nas.naswebdav.ui.dialogs.DialogType.WARNING
                        commonDialogMessage = if (resp.isSuccessful)
                            "Đã thu hồi Guest Pass của '${pass.username}' thành công!"
                        else
                            "Thu hồi có lỗi (${resp.code}), nhưng Pass đã bị xóa khỏi app."
                        showCommonDialog = true
                    }
                }
                repository.addSystemLog("INFO", "GuestPass", "Đã thu hồi Guest FTP user '${pass.username}'")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activeGuestPass = null // Dù lỗi vẫn xóa khỏi app, người dùng biết phải xóa tay
                    guestPassError = "Lỗi thu hồi: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { isGuestPassLoading = false }
            }
        }
    }

    // ============ SOCIAL EXTRACTOR (yt-dlp qua NAS API) ============

    /**
     * Gửi link video tới NAS → NAS chạy yt-dlp ngầm → lưu vào Downloads/social/.
     * Điện thoại KHÔNG tốn 1MB dung lượng.
     */
    fun requestSocialDownload(url: String, saveFolder: String = AppConfig.SOCIAL_DOWNLOAD_FOLDER) {
        if (url.isBlank() || isSocialExtracting) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isSocialExtracting = true
                socialExtractStatus = "Đang gửi lệnh tới NAS..."
            }
            try {
                val host = java.net.URL(webDavManager.currentBaseUrl).host
                val platform = detectSocialPlatform(url)
                val json = org.json.JSONObject().apply {
                    put("url", url)
                    put("save_folder", saveFolder)
                    put("quality", "best")
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/ytdlp/download")
                    .post(body)
                    .build()

                // Timeout dài hơn vì NAS cần phân giải tên miền + bắt link
                val ytdlpClient = localApiClient.newBuilder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                ytdlpClient.newCall(request).execute().use { resp ->
                    val resBody = resp.body?.string() ?: "{}"
                    val resJson = try { org.json.JSONObject(resBody) } catch (_: Exception) { org.json.JSONObject() }
                    val msg = resJson.optString("message", "")
                    val isOk = resp.isSuccessful

                    withContext(Dispatchers.Main) {
                        val statusText = when {
                            isOk -> "✅ NAS đã nhận lệnh tải video!\nVideo sẽ được tải ngầm và lưu vào $saveFolder."
                            else -> "❌ Lỗi (${resp.code}): ${msg.take(100)}"
                        }
                        socialExtractStatus = statusText
                        val histItem = SocialDownloadItem(url, platform, isOk)
                        socialDownloadHistory = socialDownloadHistory + histItem
                    }
                    repository.addSystemLog(
                        if (isOk) "SUCCESS" else "ERROR",
                        "SocialExtract",
                        "[$platform] $url → ${if (isOk) "OK" else "Lỗi ${resp.code}"}"
                    )

                    if (isOk) {
                        val jobId = resJson.optString("job_id", "")
                        if (jobId.isNotEmpty()) {
                            monitorYtdlpJob(jobId, url, platform, saveFolder)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    socialExtractStatus = "❌ Lỗi kết nối API: ${e.message?.take(80)}"
                    socialDownloadHistory = socialDownloadHistory + SocialDownloadItem(url, "Unknown", false)
                }
                repository.addSystemLog("ERROR", "SocialExtract", "Lỗi gửi yt-dlp: ${e.message?.take(80)}")
            } finally {
                withContext(Dispatchers.Main) { isSocialExtracting = false }
            }
        }
    }

    private fun monitorYtdlpJob(jobId: String, url: String, platform: String, saveFolder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val host = try { java.net.URL(webDavManager.currentBaseUrl).host } catch(e: Exception) { return@launch }
            val statusUrl = "http://$host:5050/api/ytdlp/status"
            var isFinished = false
            while (!isFinished) {
                delay(5000L) // Poll every 5 seconds
                try {
                    val requestBuilder = okhttp3.Request.Builder().url(statusUrl)
                    val user = com.nas.naswebdav.SecurePrefsHelper.getUser(NasApplication.instance)
                    val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(NasApplication.instance)
                    if (user.isNotEmpty() && pass.isNotEmpty()) {
                        requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                    }
                    val resp = localApiClient.newCall(requestBuilder.build()).execute()
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(bodyStr)
                    val jobs = json.optJSONArray("jobs") ?: continue

                    var found = false
                    for (i in 0 until jobs.length()) {
                        val job = jobs.getJSONObject(i)
                        if (job.optString("job_id") == jobId) {
                            found = true
                            break
                        }
                    }

                    if (!found) {
                        isFinished = true
                        // Khi job_id không còn trong list, tác vụ tải đã hoàn thành
                        withContext(Dispatchers.Main) {
                            commonDialogMessage = "✅ Bơm Video ($platform) HOÀN TẤT!\nĐã tải xong và lưu vào thư mục $saveFolder"
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                            showCommonDialog = true
                        }
                        repository.addSystemLog("SUCCESS", "SocialDownload", "Tải video $platform hoàn tất. Lưu tại: $saveFolder ($url)")
                    }
                } catch (e: Exception) {
                    // Ignore transient network errors
                }
            }
        }
    }

    private fun detectSocialPlatform(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("tiktok") -> "TikTok"
            lower.contains("facebook") || lower.contains("fb.watch") -> "Facebook"
            lower.contains("youtube") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("instagram") -> "Instagram"
            else -> "Other"
        }
    }

    // ============ STREAM PIPING ENGINE ============
    //
    // Triết lý: Điện thoại = Ống nước (Pipe).
    //   CDN Server ──[OkHttp GET stream]──▶ Phone RAM buffer ──[WebDAV PUT]──▶ NAS HDD
    //
    // Điện thoại KHÔNG lưu file. Mỗi chunk 128KB đọc xong bơm lên ngay.
    // Tổng RAM dùng: ~256KB (2 buffer chunk) bất kể video to bao nhiêu.

    /**
     * Bắt đầu Stream Piping từ [sourceUrl] (link MP4 CDN đã bóc) → WebDAV NAS.
     *
     * @param sourceUrl  Link video CDN trực tiếp (đã giải mã, có thể stream)
     * @param fileName   Tên file lưu trên NAS
     */
    fun startStreamPipe(sourceUrl: String, fileName: String) {
        streamPipeJob?.cancel()

        isStreamPiping = true
        streamPipeProgress = 0f
        streamPipeSpeedStr = "Đang kết nối..."
        streamPipeEtaStr = "--"
        streamPipeStatus = "⏳ Đang bơm stream qua Worker ngầm..."

        // KIẾN TRÚC MỚI: Đẩy sang StreamPipeWorker (Foreground Service)
        // → Tắt App vẫn bơm video liên tục, Notification hiển thị % tiến trình
        val payloadFile = try {
            val payloadDir = File(NasApplication.instance.cacheDir, "stream_pipe_payloads").apply { mkdirs() }
            val file = File(payloadDir, "stream_${System.currentTimeMillis()}.json")
            file.writeText(
                org.json.JSONObject()
                    .put("sourceUrl", sourceUrl)
                    .put("fileName", fileName)
                    .toString(),
                Charsets.UTF_8
            )
            file
        } catch (e: Exception) {
            isStreamPiping = false
            streamPipeStatus = "Không thể chuẩn bị tải video: ${e.message}"
            return
        }
        val inputData = androidx.work.Data.Builder()
            .putString("payloadFile", payloadFile.absolutePath)
            .putString("baseUrl", webDavManager.currentBaseUrl)
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<StreamPipeWorker>()
            .setInputData(inputData)
            .addTag("STREAM_PIPE_TASK")
            .build()

        _activeStreamPipeWorkId = workRequest.id

        val context = NasApplication.instance.applicationContext
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork("StreamPipe", androidx.work.ExistingWorkPolicy.REPLACE, workRequest)

        // Lắng nghe tiến trình từ Worker
        streamPipeJob = viewModelScope.launch {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { workInfo ->
                    if (workInfo != null) {
                        val status = workInfo.progress.getString("status") ?: ""
                        val progress = workInfo.progress.getInt("progress", 0)
                        val speedStr = workInfo.progress.getString("speedStr") ?: ""
                        val etaSec = workInfo.progress.getLong("etaSec", 0L)
                        val message = workInfo.progress.getString("message") ?: ""
                        val bytesRead = workInfo.progress.getLong("bytesRead", 0L)
                        val totalBytes = workInfo.progress.getLong("totalBytes", 0L)

                        if (status == "streaming") {
                            streamPipeProgress = progress.toFloat() / 100f
                            streamPipeSpeedStr = speedStr
                            streamPipeEtaStr = formatEta(etaSec)
                            streamPipeStatus = "📡 ${formatFileSize(bytesRead)} / ${if (totalBytes > 0) formatFileSize(totalBytes) else "?"}"
                        }

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            streamPipeProgress = 1f
                            isStreamPiping = false
                            streamPipeStatus = message.ifEmpty { "✅ Hoàn tất!" }
                            // Lưu lịch sử
                            val platform = detectSocialPlatform(sourceUrl)
                            socialDownloadHistory = socialDownloadHistory + SocialDownloadItem(sourceUrl, platform, true)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            isStreamPiping = false
                            streamPipeStatus = "❌ ${message.ifEmpty { "Lỗi bơm stream" }}"
                        } else if (workInfo.state == androidx.work.WorkInfo.State.CANCELLED) {
                            isStreamPiping = false
                            streamPipeStatus = "🛑 Đã hủy bởi người dùng"
                            streamPipeProgress = 0f
                        }
                    }
                }
        }
    }

    /** Hủy Stream Pipe Worker đang chạy giữa chừng. */
    fun cancelStreamPipe() {
        val context = NasApplication.instance.applicationContext
        _activeStreamPipeWorkId?.let { id ->
            androidx.work.WorkManager.getInstance(context).cancelWorkById(id)
        }
        streamPipeJob?.cancel()
        isStreamPiping = false
        streamPipeStatus = "🛑 Đã hủy"
        streamPipeProgress = 0f
    }

    // ── Format helpers ────────────────────────────────────────────────────────
    private fun formatFileSize(bytes: Long): String = com.nas.naswebdav.utils.FormatUtils.formatBytes(bytes)

    private fun formatEta(seconds: Long): String = when {
        seconds <= 0  -> "--"
        seconds < 60  -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    // ==========================================
    // TRẠNG THÁI GIAO DIỆN SMART SYNC
    // (Đã xóa SmartSync theo yêu cầu tập trung Auto-Backup)

    // ==========================================
    // PHÂN LOẠI VIDEO CŨ (Legacy Videos)
    // ==========================================
    var organizingLegacyRunning by mutableStateOf(false)
        private set
    var organizingLegacyResult by mutableStateOf<String?>(null)
        private set

    fun resetOrganizingLegacy() {
        organizingLegacyRunning = false
        organizingLegacyResult = null
    }

    fun organizeLegacyVideos() {
        if (organizingLegacyRunning) return
        organizingLegacyRunning = true
        organizingLegacyResult = null

        // KIẾN TRÚC MỚI: Đẩy sang LongRunningApiWorker (Foreground Service)
        val host = try { java.net.URL(webDavManager.currentBaseUrl).host } catch (_: Exception) {
            organizingLegacyRunning = false
            organizingLegacyResult = "Lỗi: Chưa kết nối NAS"
            return
        }

        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "ORGANIZE")
            .putString("apiUrl", "${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/tools/organize_legacy_videos")
            .putString("jsonBody", "")
            .putString("taskLabel", "Gom video cũ")
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<LongRunningApiWorker>()
            .setInputData(inputData)
            .addTag("LONG_RUNNING_API")
            .build()

        val context = NasApplication.instance.applicationContext
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork("OrganizeLegacy", androidx.work.ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { workInfo ->
                    if (workInfo != null) {
                        val message = workInfo.progress.getString("message") ?: ""

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            organizingLegacyRunning = false
                            organizingLegacyResult = message.ifEmpty { "Hoàn tất!" }
                            refresh()
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            organizingLegacyRunning = false
                            organizingLegacyResult = message.ifEmpty { "Lỗi gom video" }
                        }
                    }
                }
        }
    }

    // ==========================================
    // THUMBNAIL CACHING
    // ==========================================
    suspend fun downloadThumbnailFromNas(url: String, thumbFile: java.io.File, auth: String, isVideo: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val parsedUrl = java.net.URL(url)
                val nasHost = parsedUrl.host
                val webdavPath = parsedUrl.path ?: url.substringAfter(nasHost ?: "", "")
                val apiThumbUrl = "${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb?path=${java.net.URLEncoder.encode(webdavPath, "UTF-8")}"

                val apiRequest = okhttp3.Request.Builder()
                    .url(apiThumbUrl)
                    .header("Authorization", auth)
                    .build()

                NasApplication.instance.thumbnailApiClient.newCall(apiRequest).execute().use { apiResponse ->
                        val contentLength = apiResponse.header("Content-Length")?.toLongOrNull() ?: 0L

                        if (apiResponse.isSuccessful && apiResponse.body != null) {
                            // CHẶN BỘ LỌC RÁC: Nếu NAS trả về tệp < 2KB thì 99% đó là Icon Play báo lỗi, ta từ chối!
                            if (!isVideo && contentLength in 1L..2000L) {
                                return@withContext false
                            }

                            apiResponse.body!!.byteStream().use { input ->
                                java.io.FileOutputStream(thumbFile).use { out -> input.copyTo(out) }
                            }
                            return@withContext (thumbFile.length() > 0)
                        } else {
                            return@withContext false
                        }
                }
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }
    // TÍNH NĂNG: Cập nhật thủ công (Manual Sync)
    fun triggerManualBackup(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        
        // Kích hoạt quét rác song song (tuỳ chọn)
        val inputData = androidx.work.Data.Builder().putString("currentUrl", currentUrl).build()
        val duplicateScanRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.DuplicateScanWorker>()
            .setInputData(inputData)
            .build()
        workManager.enqueueUniqueWork("ManualDuplicateScan", androidx.work.ExistingWorkPolicy.REPLACE, duplicateScanRequest)

        // Kích hoạt AutoBackup ngay lập tức
        val backupRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.AutoBackupWorker>()
            .build()
        workManager.enqueueUniqueWork("ManualAutoBackupWork", androidx.work.ExistingWorkPolicy.REPLACE, backupRequest)
        // Cập nhật Toast hoặc Trạng thái UI để User biết
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "Đã ra lệnh Đồng bộ + Quét rác ngay lập tức!"
        showCommonDialog = true
    }

    fun toggleAutoBackupPause() {
        val newState = !AutoBackupState.isPaused.value
        AutoBackupState.isPaused.value = newState
        autoBackupIsPaused = newState
    }

    // ==========================================
    // THIẾT LẬP HOẠT ĐỘNG QUẠT (FAN CONTROL)
    // ==========================================
    fun setFanMode(mode: String, onTemp: Float? = null, offTemp: Float? = null) {
        if (isFanModeUpdating) return
        
        // Cập nhật Optimistic UI ngay lập tức trên Main Thread để tránh delay 1-5 frames gây chớp (bounce)
        var optimisticStatus = systemStatus.copy(
            fanMode = mode,
            fanStatus = when (mode) {
                "off" -> "Dừng"
                "on" -> "Đang chạy 100%"
                else -> systemStatus.fanStatus
            }
        )
        if (mode == "custom" && onTemp != null && offTemp != null) {
            optimisticStatus = optimisticStatus.copy(fanOnTemp = onTemp, fanOffTemp = offTemp)
        }
        val oldStatus = systemStatus
        systemStatus = optimisticStatus
        isFanModeUpdating = true
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = webDavManager.currentBaseUrl.toApiBaseUrl() + "/api/fan/control"
                val jsonBody = org.json.JSONObject().put("mode", mode)
                if (mode == "custom" && onTemp != null && offTemp != null) {
                    jsonBody.put("on_temp", onTemp)
                    jsonBody.put("off_temp", offTemp)
                }
                
                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()

                NasApplication.instance.sharedHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.e("NasAPI", "Failed to set fan mode: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NasAPI", "Failed to set fan mode: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { 
                    isFanModeUpdating = false 
                    // Chống bounce: bắt đầu đếm 4s SAU KHI API thực sự chạy xong
                    lastFanModeSettingTime = System.currentTimeMillis()
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        try { webSocket?.close(1000, "ViewModel cleared") } catch (_: Exception) {}
    }
} // end class WebDavViewModel

// LỚP PHỤ TRỢ: Bộ đếm Rate Limiter (2.C)
class RateLimiter(private val maxRequestsPerMinute: Int) {
    private val requestTimestamps = mutableListOf<Long>()
    
    @Synchronized
    fun isAllowed(): Boolean {
        val now = System.currentTimeMillis()
        requestTimestamps.removeAll { now - it > 60000L }
        
        if (requestTimestamps.size >= maxRequestsPerMinute) return false
        requestTimestamps.add(now)
        return true
    }
}


fun WebDavViewModel.startBackgroundDuplicateScan(context: android.content.Context, forceRestart: Boolean = false, lightningMode: Boolean = true) {
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "Đã nhận lệnh! Đang khởi động trình quét rác..."
        showCommonDialog = true

        isScanningDuplicates = true
        viewModelScope.launch {
            repository.addSystemLog("INFO", "DuplicateScan", "Hệ thống: Người dùng đã phân công quét thủ công trùng lặp")
        }
        if (isWorkerRunning) return

        isWorkerRunning = true
        scanDuplicatesCurrentFolderUrl = "Đang kết nối..."
        scanDuplicatesCurrentItemName = "Khởi tạo..."
        scanDuplicatesTotalScanned = 0
        scanDuplicatesFound = 0
        scanDuplicatesStage = "Khởi động..."

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(500)
            try {
                val workManager = androidx.work.WorkManager.getInstance(context)
                val inputData = androidx.work.workDataOf(
                    "currentUrl" to currentUrl,
                    "forceRestart" to forceRestart,
                    "lightningMode" to lightningMode
                )

                val scanWorkRequest = androidx.work.OneTimeWorkRequestBuilder<DuplicateScanWorker>()
                    .setInputData(inputData)
                    .build()

                workManager.enqueueUniqueWork("Unique_Scan_V3", androidx.work.ExistingWorkPolicy.REPLACE, scanWorkRequest)

                // Luồng: Lắng nghe trạng thái Worker (Thành công, Thất bại)
                workManager.getWorkInfoByIdFlow(scanWorkRequest.id).collect { workInfo ->
                    if (workInfo != null) {
                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            scanDuplicatesStage = "Hoàn tất"
                            scanDuplicatesCurrentFolderUrl = "Hoàn tất!"
                            scanDuplicatesCurrentItemName = "Đã quét xong toàn bộ."
                            scanDuplicatesPercent = 1f
                            scanDuplicatesCurrentStagePercent = 1f
                            scanDuplicatesStageNumber = 4
                            scanDuplicatesStageDescription = "Đã quét xong toàn bộ."
                            isWorkerRunning = false
                            loadDuplicateResultsFromCache(context)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            scanDuplicatesCurrentFolderUrl = "Gặp lỗi hệ thống!"
                            isWorkerRunning = false
                        } else if (workInfo.state == androidx.work.WorkInfo.State.CANCELLED) {
                            isWorkerRunning = false
                        }
                    }
                }
            } catch (e: Exception) {
                isWorkerRunning = false
            }
        }
    }

fun WebDavViewModel.loadDuplicateResultsFromCache(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val duplicates = repository.getDuplicateFiles()
                withContext(Dispatchers.Main) {
                    duplicateFilesList = duplicates
                    isShowingDuplicates = true
                    if (duplicateFilesList.isEmpty()) {
                        errorMessage = "NAS của bạn rất gọn gàng! Không có file trùng lặp."
                    } else {
                        errorMessage = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lỗi nạp danh sách từ DB: ${e.message}" }
            }
        }
    }

fun WebDavViewModel.deleteDuplicateFile(file: NasFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                val trashUrl = webDavManager.currentBaseUrl + TRASH_FOLDER_NAME

                // 1. Kiểm tra nếu file đang ở trong thùng rác rồi thì xoá vĩnh viễn
                if (file.path.contains(TRASH_FOLDER_NAME)) {
                    webDavManager.deleteFile(file.path)
                } else {
                    // 2. Nếu chưa, hãy đảm bảo thư mục thùng rác tồn tại và di chuyển vào đó
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) { /* Đã tồn tại */ }

                    val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    val targetUrl = if (trashUrl.endsWith("/")) trashUrl + encodedName else "$trashUrl/$encodedName"
                    webDavManager.renameFile(file.path, targetUrl)
                }

                repository.removeDuplicateFromDb(file.path)
                withContext(Dispatchers.Main) {
                    duplicateFilesList = duplicateFilesList.filter { it.path != file.path }
                    refresh()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lỗi xử lý thùng rác: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

fun WebDavViewModel.deleteSelectedDuplicates() {
        val filesToDelete = selectedDuplicates.toList()
        if (filesToDelete.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                val trashUrl = webDavManager.currentBaseUrl + TRASH_FOLDER_NAME
                try { webDavManager.createFolder(trashUrl) } catch(e: Exception) { }

                var processed = 0
                for (file in filesToDelete) {
                    if (file.path.contains(TRASH_FOLDER_NAME)) {
                        webDavManager.deleteFile(file.path)
                    } else {
                        val targetUrl = trashUrl + file.name
                        webDavManager.renameFile(file.path, targetUrl)
                    }
                    processed++
                    if (processed % 5 == 0) kotlinx.coroutines.delay(10)
                }

                val deletedPaths = filesToDelete.map { it.path }.toSet()
                for (path in deletedPaths) { repository.removeDuplicateFromDb(path) }

                withContext(Dispatchers.Main) {
                    duplicateFilesList = duplicateFilesList.filter { it.path !in deletedPaths }
                    selectedDuplicates.clear()
                    refresh()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lỗi xử lý hàng loạt: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

fun WebDavViewModel.scheduleIdleDuplicateScan(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        val constraints = androidx.work.Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
            .build()

        val inputData = androidx.work.workDataOf(
            "currentUrl" to currentUrl
        )

        val periodicScanRequest = androidx.work.PeriodicWorkRequestBuilder<DuplicateScanWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "Auto_Idle_Duplicate_Scan",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            periodicScanRequest
        )
    }

fun WebDavViewModel.scheduleIdleSpeedTest(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        val constraints = androidx.work.Constraints.Builder()
            .setRequiresDeviceIdle(true) // ĐIỀU KIỆN 1: Điện thoại đang tắt màn hình, không sử dụng
            .setRequiresCharging(true)   // ĐIỀU KIỆN 2: Đang cắm sạc (Đảm bảo an toàn pin)
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) // ĐIỀU KIỆN 3: Có Wi-Fi
            .build()

        val inputData = androidx.work.workDataOf(
            "currentUrl" to currentUrl
        )

        // CHU KỲ BẢO VỆ Ổ CỨNG: Chỉ lén chạy Stress Test 30 ngày 1 lần để không làm giảm tuổi thọ ổ đĩa
        val periodicSpeedTestRequest = androidx.work.PeriodicWorkRequestBuilder<IdleSpeedTestWorker>(
            30, java.util.concurrent.TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "Auto_Idle_Speed_Test",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, // Giữ nguyên lịch trình cũ nếu đã tồn tại
            periodicSpeedTestRequest
        )
    }

// PHASE 5.B: Lên lịch cho FingerprintWorker chạy mồi vân tay ngầm
fun WebDavViewModel.scheduleFingerprintWorker(context: android.content.Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val constraints = androidx.work.Constraints.Builder()
        .setRequiresDeviceIdle(true) // Tắt màn hình
        .setRequiresCharging(true)   // Đang sạc
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED) // Có mạng
        .build()

    // Chạy mỗi 24 tiếng để tạo vân tay cho các file ảnh/video vừa upload
    val periodicRequest = androidx.work.PeriodicWorkRequestBuilder<FingerprintWorker>(
        24, java.util.concurrent.TimeUnit.HOURS
    )
        .setConstraints(constraints)
        .build()

    workManager.enqueueUniquePeriodicWork(
        "Auto_Fingerprint_Worker",
        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
        periodicRequest
    )
}
/**
 * Tải video từ NAS về cache rồi mở bằng trình phát cục bộ.
 * Đảm bảo mọi định dạng (.mpg, .avi, .wmv, .flv, ...) đều phát được
 * vì file cục bộ không có vấn đề auth hay streaming.
 */
object VideoDownloadHelper {

    private const val TAG = "VideoDownloadHelper"
    private const val VIDEO_CACHE_DIR = "video_temp"

    /**
     * Tải video về cache và mở bằng trình phát bên ngoài.
     * Hiển thị progress qua callback.
     *
     * @param onProgress Callback (bytesDownloaded, totalBytes) để cập nhật UI
     * @param onReady Callback khi file đã sẵn sàng phát
     * @param onError Callback khi có lỗi
     */
    // FIX A3a: Nhận CoroutineScope từ caller thay vì tự tạo CoroutineScope(IO) riêng.
    // Scope rời rạc sẽ không bao giờ bị cancel khi ViewModel bị destroy → memory leak.
    // Caller (thường là ViewModel) phải truyền viewModelScope để lifecycle được quản lý đúng.
    fun downloadAndPlay(
        scope: CoroutineScope,
        context: Context,
        url: String,
        user: String,
        pass: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                // 1. Tạo thư mục cache cho video
                val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
                if (!cacheDir.exists()) cacheDir.mkdirs()

                // Xóa file cũ để giải phóng bộ nhớ (chỉ giữ file mới nhất)
                cacheDir.listFiles()?.forEach { it.delete() }

                // 2. Lấy tên file từ URL
                val fileName = url.substringAfterLast('/').substringBefore('?')
                    .let { java.net.URLDecoder.decode(it, "UTF-8") }
                    .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val targetFile = File(cacheDir, fileName)

                Log.i(TAG, "Downloading: $url → ${targetFile.absolutePath}")

                // 3. Tải file từ NAS với xác thực
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .build()

                // FIX A3b: Bọc response trong use {} để đảm bảo body luôn được đóng,
                // kể cả khi exception xảy ra giữa chừng (tránh connection pool exhaustion).
                NasApplication.instance.videoStreamingClient
                    .newBuilder()
                    .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS) // 10 phút cho file lớn
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            withContext(Dispatchers.Main) {
                                onError("NAS trả về lỗi: ${response.code}")
                            }
                            return@use
                        }

                        val totalBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        var downloadedBytes = 0L

                        // 4. Ghi file ra cache với progress
                        response.body?.byteStream()?.use { input ->
                            targetFile.outputStream().use { output ->
                                val buffer = ByteArray(131072) // 128KB buffer
                                var bytesRead: Int
                                var lastProgressTime = 0L
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (!isActive) {
                                        targetFile.delete()
                                        return@use
                                    }
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastProgressTime > 150L) {
                                        lastProgressTime = currentTime
                                        withContext(Dispatchers.Main) {
                                            onProgress(downloadedBytes, totalBytes)
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    onProgress(downloadedBytes, totalBytes)
                                }
                            }
                        }

                        Log.i(TAG, "Download complete: ${downloadedBytes / 1024}KB")

                        // 5. Mở file cục bộ bằng trình phát video
                        withContext(Dispatchers.Main) {
                            onReady()
                            openLocalFile(context, targetFile)
                        }
                    }

            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("Lỗi tải video: ${e.message}")
                }
            }
        }
    }


    /** Mở file video cục bộ bằng trình phát cài trên máy */
    private fun openLocalFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // Xác định MIME type phù hợp
            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mpg", "mpeg" -> "video/mpeg"
                "wmv" -> "video/x-ms-wmv"
                "flv" -> "video/x-flv"
                "mov" -> "video/quicktime"
                "ts" -> "video/mp2ts"
                "webm" -> "video/webm"
                else -> "video/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Chọn trình phát video")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open file: ${e.message}")
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SystemMonitorHelper — Extension functions cho WebDavViewModel
// ════════════════════════════════════════════════════════════════════════════

fun WebDavViewModel.listenToLocalNasApi() {
    statusJob?.cancel()
    statusJob = viewModelScope.launch(Dispatchers.IO) {
        var currentDelayMs = 3000L
        while (isActive) {
            try {
                val baseUrl = webDavManager.currentBaseUrl
                if (baseUrl.isNotEmpty()) {
                    val host = java.net.URL(baseUrl).host
                    val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/status").build()
                    localApiClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            currentDelayMs = 3000L
                            val jsonObject = org.json.JSONObject(response.body!!.string())
                            val tempRaw = jsonObject.optString("temperature", "--°C")
                            val temp = if (tempRaw != "--°C" && !tempRaw.contains("°")) "${tempRaw}°C" else tempRaw
                            val cpu = jsonObject.optString("cpu", "--%")
                            val cpuTemp = jsonObject.optString("cpu_temp", "--°C")
                            var ram = jsonObject.optString("ram", "--")
                            if (ram == "--" || ram.isBlank()) {
                                val u = jsonObject.optString("ram_used", "").ifBlank { jsonObject.optString("mem_used", "") }
                                val t = jsonObject.optString("ram_total", "").ifBlank { jsonObject.optString("mem_total", "") }
                                if (u.isNotBlank() && t.isNotBlank()) ram = "$u / $t"
                            }
                            val rawDisk = jsonObject.optString("disk", "--%|"); val diskPartsArr = rawDisk.split("|")
                            val disk = diskPartsArr[0]; val diskCapacity = diskPartsArr.getOrNull(1) ?: ""
                            val netRx = jsonObject.optString("net_rx", "0 B/s"); val netTx = jsonObject.optString("net_tx", "0 B/s")
                            val uptime = jsonObject.optString("uptime", "--"); val status = jsonObject.optString("status", "Online")
                            var ramPercent = jsonObject.optString("ram_percent", "0")
                            if ((ramPercent == "0" || ramPercent.isBlank()) && ram.contains("/")) { val memPercentApi = jsonObject.optString("mem_percent", ""); if (memPercentApi.isNotBlank()) ramPercent = memPercentApi }
                            val torrentList = mutableListOf<TorrentInfo>()
                            jsonObject.optJSONArray("torrents")?.let { arr -> for (i in 0 until arr.length()) { val tObj = arr.getJSONObject(i); torrentList.add(TorrentInfo(tObj.optString("name", "Đang tải..."), tObj.optDouble("progress", 0.0).toFloat(), tObj.optString("speed", "0 B/s"), tObj.optString("hash", ""), tObj.optString("state", ""), tObj.optString("save_path", ""))) } }
                            val diskPartList = mutableListOf<DiskPart>()
                            jsonObject.optJSONArray("disk_parts")?.let { arr -> for (i in 0 until arr.length()) { val dObj = arr.getJSONObject(i); diskPartList.add(DiskPart(dObj.optString("mount", "/"), dObj.optDouble("percent", 0.0).toFloat(), dObj.optString("total", "0GB"), dObj.optString("used", "0GB"))) } }
                            val fanStatus = jsonObject.optString("fan_status", "--")
                            val fanMode = jsonObject.optString("fan_mode", "auto")
                            val fanOnTemp = jsonObject.optDouble("fan_on_temp", 65.0).toFloat()
                            val fanOffTemp = jsonObject.optDouble("fan_off_temp", 55.0).toFloat()
                            val fanRpmRaw = jsonObject.opt("fan_rpm"); val fanRpm = if (fanRpmRaw != null && fanRpmRaw != org.json.JSONObject.NULL) (fanRpmRaw as? Int) else null
                            val topProcs = mutableListOf<Pair<String, Float>>()
                            jsonObject.optJSONArray("top_processes")?.let { arr -> for (i in 0 until arr.length()) { val p = arr.getJSONObject(i); topProcs.add(Pair(p.optString("name", "?"), p.optDouble("cpu", 0.0).toFloat())) } }
                            val newStatus = NasSystemStatus(temp, cpu, cpuTemp, ram, disk, diskCapacity, netRx, netTx, uptime, status, ramPercent, torrentList, diskPartList, fanStatus, fanMode, fanOnTemp, fanOffTemp, fanRpm, topProcs)
                            val hddVal = tempRaw.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                            val cpuVal = cpuTemp.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                            withContext(Dispatchers.Main) {
                                // CHỐNG BOUNCE (Debounce): Bỏ qua cập nhật trạng thái quạt từ API nếu đang gửi lệnh HOẶC vừa set thủ công < 15s (để chờ NAS xử lý service tốn thời gian)
                                if (isFanModeUpdating || System.currentTimeMillis() - lastFanModeSettingTime < 15000L) {
                                    systemStatus = newStatus.copy(
                                        fanMode = systemStatus.fanMode,
                                        fanStatus = systemStatus.fanStatus, // Bảo toàn chuỗi trạng thái tốc độ quạt ảo
                                        fanOnTemp = systemStatus.fanOnTemp,
                                        fanOffTemp = systemStatus.fanOffTemp
                                    )
                                } else {
                                    systemStatus = newStatus
                                }
                                
                                if (hddVal > 0f || cpuVal > 0f) {
                                    temperatureHistory.addLast(Pair(cpuVal, hddVal))
                                    if (temperatureHistory.size > 40) temperatureHistory.removeFirst()
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) { systemStatus = systemStatus.copy(status = "API từ chối") }
                            currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(60_000L)
                        }
                    }
                }
            } catch (e: Exception) {
                val isTimeout = e is java.net.SocketTimeoutException || e is java.net.ConnectException
                val msg = if (isTimeout) "Mất kết nối API (${e.javaClass.simpleName})" else "API: ${e.javaClass.simpleName}"
                android.util.Log.w("NAS_API", "Monitor ping failed: ${e.message}")
                withContext(Dispatchers.Main) { systemStatus = systemStatus.copy(status = msg) }
                currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(60_000L)
            }
            delay(currentDelayMs)
        }
    }
}

fun WebDavViewModel.startRealtimeAlerts() {
    webSocket?.close(1000, "Restarting")
    try {
        val url = webDavManager.currentBaseUrl
        if (url.isBlank()) return
        val host = java.net.URL(url).host
        // nas_api_server.py chạy Tornado WebSocket trên Cổng 5051
        val wsUrl = "ws://$host:5051/ws/alerts"
        val wsRequest = okhttp3.Request.Builder().url(wsUrl).header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass)).build()
        val client = localApiClient.newBuilder()
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        webSocket = client.newWebSocket(wsRequest, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val json = try { org.json.JSONObject(text) } catch (e: Exception) { null }
                if (json == null) return
                
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    when (json.optString("type")) {
                        "SECURITY_BAN" -> {
                            val msg = json.optString("message")
                            repository.addSystemLog("ERROR", "Security", msg)
                            commonDialogMessage = msg
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            showCommonDialog = true
                        }
                        "ACCESS_LOG" -> {
                            val msg = json.optString("message")
                            repository.addSystemLog("INFO", "AccessLog", msg)
                        }
                    }
                }
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {}
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                // Tự động kết nối lại ngầm sau 15 giây nếu rớt mạng
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.delay(15000)
                    if (webDavManager.currentBaseUrl.isNotEmpty()) startRealtimeAlerts()
                }
            }
        })
    } catch (e: Exception) { }
}


fun WebDavViewModel.fetchWeeklyReport() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val host = java.net.URL(currentUrl).host
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/system/weekly_report").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    withContext(Dispatchers.Main) { weeklyReportText = "Tuần qua: Chặn ${json.optInt("banned_count", 0)} IP tấn công. Dọn rác giải phóng ${json.optString("freed_space", "0 MB")}." }
                }
            }
        } catch (_: Exception) { withContext(Dispatchers.Main) { weeklyReportText = "Chưa có báo cáo tuần này." } }
    }
}

fun WebDavViewModel.loadSystemLogs() {
    viewModelScope.launch(Dispatchers.IO) {
        val localLogs = repository.getSystemLogs()
        val allLogs = localLogs.toMutableList()
        try {
            if (webDavManager.currentBaseUrl.isNotEmpty()) {
                val req = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/system_logs").build()
                localApiClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                    if (body != null) {
                        val json = org.json.JSONObject(body)
                        if (json.optString("status") == "success") {
                            val logsArray = json.optJSONArray("logs")
                            if (logsArray != null) {
                                val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                format.timeZone = java.util.TimeZone.getTimeZone("UTC") // Database uses CURRENT_TIMESTAMP (UTC)
                                for (i in 0 until logsArray.length()) {
                                    val obj = logsArray.getJSONObject(i)
                                    val timestampStr = obj.optString("timestamp")
                                    val timestamp = try { format.parse(timestampStr)?.time ?: System.currentTimeMillis() } catch (e: Exception) { System.currentTimeMillis() }
                                    val remoteMessage = obj.optString("message")
                                    val remoteType = obj.optString("type")
                                    val remoteLog = SystemLog(
                                        id = -(obj.optInt("id")),
                                        type = remoteType,
                                        module = obj.optString("module"),
                                        message = remoteMessage,
                                        timestamp = timestamp
                                    )
                                    if (allLogs.none { it.message == remoteMessage && it.type == remoteType }) {
                                        allLogs.add(remoteLog)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NasAPI", "Fetch remote logs failed: ${e.message}")
        }
        allLogs.sortByDescending { it.timestamp }
        withContext(Dispatchers.Main) { 
            systemLogsList = allLogs.take(200) 
        }
    }
}
fun WebDavViewModel.clearSystemLogs() { viewModelScope.launch(Dispatchers.IO) { repository.clearSystemLogs(); withContext(Dispatchers.Main) { systemLogsList = emptyList(); commonDialogMessage = "Đã dọn sạch nhật ký hệ thống."; showCommonDialog = true } } }

fun WebDavViewModel.fetchSmartData() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/smart").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "")
                    withContext(Dispatchers.Main) {
                        smartInfo = SmartInfo(status = json.optString("status", "Unknown"), temperature = run { val rawTemp = json.optString("temperature", "--"); if (rawTemp != "--" && !rawTemp.contains("°")) "${rawTemp}°C" else rawTemp }, rawLog = json.optString("raw_log", ""))
                    }
                } else withContext(Dispatchers.Main) { smartInfo = SmartInfo("Lỗi kết nối", "--", "Mã lỗi: ${response.code}") }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { smartInfo = SmartInfo("Không thể kết nối", "--", e.message ?: "") } }
    }
}

fun WebDavViewModel.fetchOmvOverview() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/omv/overview").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    val sys = json.optJSONObject("system")
                    val svcArr = json.optJSONArray("services") ?: org.json.JSONArray()
                    val netArr = json.optJSONArray("network") ?: org.json.JSONArray()
                    val fsArr = json.optJSONArray("filesystems") ?: org.json.JSONArray()
                    val diskArr = json.optJSONArray("disks") ?: org.json.JSONArray()
                    val pwr = json.optJSONObject("power")

                    val services = (0 until svcArr.length()).map { i ->
                        val s = svcArr.getJSONObject(i)
                        OmvServiceInfo(s.optString("name"), s.optString("title"), s.optBoolean("enabled"), s.optBoolean("running"))
                    }
                    val network = (0 until netArr.length()).map { i ->
                        val n = netArr.getJSONObject(i)
                        OmvNetworkInfo(n.optString("name"), n.optString("address"), n.optString("mac"), n.optInt("speed", -1), n.optString("state"), n.optString("gateway"), n.optBoolean("wol"))
                    }
                    val filesystems = (0 until fsArr.length()).map { i ->
                        val f = fsArr.getJSONObject(i)
                        OmvFilesystem(f.optString("device"), f.optString("label"), f.optString("mountpoint"), f.optString("used"), f.optLong("size_bytes"), f.optInt("percentage"), f.optString("description"))
                    }
                    val disks = (0 until diskArr.length()).map { i ->
                        val d = diskArr.getJSONObject(i)
                        OmvDiskInfo(d.optString("name"), d.optString("model"), d.optString("serial"), d.optString("size"), d.optBoolean("is_root"))
                    }
                    withContext(Dispatchers.Main) {
                        omvOverview = OmvOverview(
                            hostname = sys?.optString("hostname", "") ?: "",
                            omvVersion = sys?.optString("omv_version", "") ?: "",
                            kernel = sys?.optString("kernel", "") ?: "",
                            services = services, network = network,
                            filesystems = filesystems, disks = disks,
                            powerBtnAction = pwr?.optString("powerbtn", "") ?: ""
                        )
                    }
                }
            }
        } catch (_: Exception) { }
    }
}

fun WebDavViewModel.runSpeedTest() {
    if (isTestingSpeed) return; isTestingSpeed = true; speedTestResult = SpeedTestResult("Đang đo...", "Đang đo...")
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/speedtest").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            val speedTestClient = localApiClient.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            speedTestClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) { val json = org.json.JSONObject(response.body?.string() ?: ""); withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult(json.optString("write_speed", "Lỗi"), json.optString("read_speed", "Lỗi")) } }
                else withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult("Thất bại", "Thất bại") }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult("Lỗi", "Lỗi") }; repository.addSystemLog("ERROR", "SpeedTest", "Đo tốc độ thất bại: ${e.message?.take(80)}") }
        finally { withContext(Dispatchers.Main) { isTestingSpeed = false } }
    }
}

fun WebDavViewModel.sendWakeOnLan(macStr: String) { if (macStr.isNotBlank() && macStr.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})"))) viewModelScope.launch(Dispatchers.IO) { com.nas.naswebdav.utils.WolUtil.sendMagicPacket(macStr); repository.addSystemLog("INFO", "Power", "Người dùng đã gửi gói tin Wake-on-LAN đánh thức NAS tại định danh MAC: $macStr") } }

fun WebDavViewModel.sendCommandToNas(endpoint: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val host = java.net.URL(webDavManager.currentBaseUrl).host
            val cmdName = when { endpoint.contains("reboot") -> "Khởi động lại"; endpoint.contains("shutdown") -> "Tắt nguồn"; else -> endpoint }
            repository.addSystemLog("WARNING", "Power", "Đã gửi lệnh $cmdName NAS tại $host")
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/$endpoint").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            localApiClient.newCall(request).execute().use { }
        } catch (_: Exception) {}
    }
}

fun WebDavViewModel.checkDockerStatus() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val host = java.net.URL(webDavManager.currentBaseUrl).host
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/docker/power").build()
            localApiClient.newCall(request).execute().use { response -> if (response.isSuccessful) { val json = org.json.JSONObject(response.body?.string() ?: "{}"); withContext(Dispatchers.Main) { isDockerRunning = json.optBoolean("running", false) } } }
        } catch (_: Exception) {}
    }
}

fun WebDavViewModel.toggleDockerPower(turnOn: Boolean) {
    if (isTogglingDocker) return; isTogglingDocker = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val host = java.net.URL(webDavManager.currentBaseUrl).host
            val body = org.json.JSONObject().put("action", if (turnOn) "start" else "stop").toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/docker/power").post(body).build()
            val client = localApiClient.newBuilder().readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response -> if (response.isSuccessful) withContext(Dispatchers.Main) { isDockerRunning = turnOn } }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) { isTogglingDocker = false }
    }
}

fun WebDavViewModel.approveDeviceIp(ip: String) {
    showApprovalDialog = false
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val host = java.net.URL(webDavManager.currentBaseUrl).host
            val body = org.json.JSONObject().apply { put("ip", ip); put("approved", true) }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/auth/approve_ip").post(body).build()
            localApiClient.newCall(request).execute().use { }
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS; commonDialogMessage = "Đã CẤP QUYỀN cho IP: $ip"; showCommonDialog = true }
        } catch (_: Exception) {}
    }
}

fun WebDavViewModel.denyDeviceIp(ip: String) {
    showApprovalDialog = false
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val host = java.net.URL(webDavManager.currentBaseUrl).host
            val body = org.json.JSONObject().apply { put("ip", ip); put("approved", false) }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/auth/approve_ip").post(body).build()
            localApiClient.newCall(request).execute().use { }
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING; commonDialogMessage = "Đã CHẶN VĨNH VIỄN IP: $ip bằng iptables"; showCommonDialog = true }
        } catch (_: Exception) {}
    }
}

fun WebDavViewModel.fetchDockerContainers() {
    if (isFetchingDocker) return; isFetchingDocker = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder().url("${currentUrl.toApiBaseUrl()}/api/docker/containers").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val list = mutableListOf<DockerContainer>()
                    try { val array = org.json.JSONArray(response.body?.string() ?: "[]"); for (i in 0 until array.length()) { val obj = array.getJSONObject(i); list.add(DockerContainer(obj.optString("id"), obj.optString("name"), obj.optString("status"))) } } catch (_: org.json.JSONException) {}
                    withContext(Dispatchers.Main) { dockerContainers = list }
                }
            }
        } catch (_: Exception) {} finally { withContext(Dispatchers.Main) { isFetchingDocker = false } }
    }
}

fun WebDavViewModel.controlDockerContainer(action: String, containerName: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject().apply { put("action", action); put("container", containerName) }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder().url("${currentUrl.toApiBaseUrl()}/api/docker/control").post(body).build()
            localApiClient.newCall(request).execute().use { if (it.isSuccessful) { delay(1500); withContext(Dispatchers.Main) { isFetchingDocker = false }; fetchDockerContainers() } }
        } catch (_: Exception) {}
    }
}

// ════════════════════════════════════════════════════════════════════════════
// LAN WHITELIST API — Tách logic mạng ra khỏi @Composable
// ════════════════════════════════════════════════════════════════════════════

fun WebDavViewModel.loadLanWhitelist() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                lanWhitelistLoading = true
                lanWhitelistError = ""
            }
            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBase/api/lan/whitelist").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val json = org.json.JSONObject(response.body!!.string())
                    val ips = mutableListOf<String>()
                    val subnets = mutableListOf<String>()
                    val ipsArr = json.optJSONArray("ips")
                    val subsArr = json.optJSONArray("subnets")
                    if (ipsArr != null) { for (i in 0 until ipsArr.length()) ips.add(ipsArr.getString(i)) }
                    if (subsArr != null) { for (i in 0 until subsArr.length()) subnets.add(subsArr.getString(i)) }
                    withContext(Dispatchers.Main) { lanWhitelistIps = ips; lanWhitelistSubnets = subnets }
                } else {
                    withContext(Dispatchers.Main) { lanWhitelistError = "Lỗi: ${response.code}" }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistError = "Lỗi kết nối: ${e.message}" }
        } finally {
            withContext(Dispatchers.Main) { lanWhitelistLoading = false }
        }
    }
}

fun WebDavViewModel.addLanWhitelistEntry(entry: String) {
    viewModelScope.launch(Dispatchers.IO) {
        var isSuccessLocally = false
        try {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Đang thêm..." }
            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            val isSubnet = entry.contains("/")
            // FIX B3: Dùng JSONObject.put() thay vì string interpolation để tránh JSON injection
            // nếu entry chứa ký tự đặc biệt như dấu ngoặc kép hoặc backslash.
            val bodyJson = org.json.JSONObject().apply {
                if (isSubnet) put("subnet", entry) else put("ip", entry)
            }.toString()
            val request = okhttp3.Request.Builder()
                .url("$apiBase/api/lan/whitelist")
                .post(bodyJson.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            localApiClient.newCall(request).execute().use { response ->
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        isSuccessLocally = true
                        lanWhitelistStatus = "✅ Đã thêm $entry"
                        repository.addSystemLog("INFO", "Network", "Người dùng đã THÊM IP/Subnet '$entry' vào danh sách LAN Whitelist.")
                    } else {
                        lanWhitelistStatus = "❌ Lỗi: ${response.code}"
                        repository.addSystemLog("WARNING", "Network", "Cố gắng thêm IP/Subnet '$entry' vào LAN Whitelist thất bại.")
                    }
                }
            }
            if (isSuccessLocally) loadLanWhitelist()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "❌ ${e.message}" }
        }
    }
}

fun WebDavViewModel.removeLanWhitelistEntry(entry: String, isSubnet: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        var isSuccessLocally = false
        try {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Đang xóa..." }
            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            // FIX B3: Dùng JSONObject.put() thay vì string interpolation.
            val bodyJson = org.json.JSONObject().apply {
                if (isSubnet) put("subnet", entry) else put("ip", entry)
            }.toString()
            val request = okhttp3.Request.Builder()
                .url("$apiBase/api/lan/whitelist")
                .delete(bodyJson.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            localApiClient.newCall(request).execute().use { response ->
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        isSuccessLocally = true
                        lanWhitelistStatus = "✅ Đã xóa $entry"
                        repository.addSystemLog("INFO", "Network", "Người dùng đã XÓA IP/Subnet '$entry' khỏi danh sách LAN Whitelist.")
                    } else {
                        lanWhitelistStatus = "❌ Lỗi: ${response.code}"
                        repository.addSystemLog("WARNING", "Network", "Cố gắng xóa IP/Subnet '$entry' khỏi LAN Whitelist thất bại.")
                    }
                }
            }
            if (isSuccessLocally) loadLanWhitelist()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "❌ ${e.message}" }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SMART ORGANIZER API — Tách logic mạng ra khỏi @Composable
// ════════════════════════════════════════════════════════════════════════════

fun WebDavViewModel.smartOrganizeScan(filter: OrganizerFilter) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                organizerScanning = true
                organizerScanResult = null
                organizerResult = null
                organizerError = null
            }

            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            val filterStr = when (filter) {
                OrganizerFilter.IMAGE -> "image"
                OrganizerFilter.VIDEO -> "video"
                OrganizerFilter.ALL -> "all"
            }
            val body = org.json.JSONObject().apply { put("filter", filterStr) }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("$apiBase/api/tools/smart_organize/scan")
                .post(body)
                .build()

            val scanClient = localApiClient.newBuilder()
                .readTimeout(3, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            scanClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = org.json.JSONObject(responseBody)
                            organizerTotalFiles = json.optInt("total", 0)
                            val groupsArr = json.optJSONArray("groups") ?: org.json.JSONArray()
                            val groups = mutableListOf<OrganizerGroup>()
                            for (i in 0 until groupsArr.length()) {
                                val g = groupsArr.getJSONObject(i)
                                val samples = mutableListOf<String>()
                                val sf = g.optJSONArray("sample_files") ?: org.json.JSONArray()
                                for (j in 0 until sf.length()) {
                                    samples.add(sf.getJSONObject(j).optString("name", ""))
                                }
                                groups.add(OrganizerGroup(
                                    label = g.optString("label", "?"),
                                    count = g.optInt("count", 0),
                                    size = g.optLong("size", 0L),
                                    sampleFiles = samples
                                ))
                            }
                            organizerScanResult = groups
                        } catch (e: Exception) {
                            organizerError = "Lỗi phân tích: ${e.message}"
                        }
                    } else {
                        organizerError = "Lỗi NAS: ${response.code}"
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { organizerError = "Lỗi kết nối: ${e.message}" }
        } finally {
            withContext(Dispatchers.Main) { organizerScanning = false }
        }
    }
}

fun WebDavViewModel.smartOrganizeExecute(filter: OrganizerFilter) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                organizerExecuting = true
                organizerResult = null
                organizerError = null
            }

            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            val filterStr = when (filter) {
                OrganizerFilter.IMAGE -> "image"
                OrganizerFilter.VIDEO -> "video"
                OrganizerFilter.ALL -> "all"
            }
            val body = org.json.JSONObject().apply { put("filter", filterStr) }.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("$apiBase/api/tools/smart_organize/execute")
                .post(body)
                .build()

            val execClient = localApiClient.newBuilder()
                .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            execClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = org.json.JSONObject(responseBody)
                            val count = json.optInt("moved_count", 0)
                            organizerResult = "Hoàn tất · $count tệp đã sắp xếp"
                            organizerScanResult = null
                        } catch (e: Exception) {
                            organizerError = "Lỗi phản hồi: ${e.message}"
                        }
                    } else {
                        organizerError = "Lỗi NAS: ${response.code}"
                    }
                }
            }
            // Refresh file list sau khi sắp xếp xong
            refresh()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { organizerError = "Lỗi kết nối: ${e.message}" }
        } finally {
            withContext(Dispatchers.Main) { organizerExecuting = false }
        }
    }


}

// ════════════════════════════════════════════════════════════════════════════
// PerformanceMonitor — Giám sát hiệu năng ứng dụng
// ════════════════════════════════════════════════════════════════════════════

data class SystemMetrics(
    val totalRamMb: Int = 0, val freeRamMb: Int = 0, val ramUsagePercent: Int = 0,
    val cpuUsagePercent: Int = 0, val rxSpeedKbps: Int = 0, val txSpeedKbps: Int = 0,
    val diskCacheSizeMb: Int = 0, val maxJvmMemoryMb: Int = 0, val usedJvmMemoryMb: Int = 0
)

object PerformanceMonitor {
    private val _metricsFlow = MutableStateFlow(SystemMetrics())
    val metricsFlow: StateFlow<SystemMetrics> = _metricsFlow
    private var previousRx = 0L; private var previousTx = 0L
    private var lastDiskCacheSizeMb = 0; private var diskCacheCheckCounter = 0

    suspend fun startMonitoring(context: Context) = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val uid = Process.myUid()
        try {
            while (isActive) {
                try {
                    am.getMemoryInfo(memoryInfo)
                    val totalRam = (memoryInfo.totalMem / 1048576L).toInt(); val freeRam = (memoryInfo.availMem / 1048576L).toInt()
                    val usedRamPercent = ((totalRam - freeRam).toFloat() / totalRam * 100).roundToInt()
                    val maxJvm = (Runtime.getRuntime().maxMemory() / 1048576L).toInt()
                    val usedJvm = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L).toInt()
                    val currentRx = TrafficStats.getUidRxBytes(uid); val currentTx = TrafficStats.getUidTxBytes(uid)
                    var rxSpeed = 0; var txSpeed = 0
                    if (previousRx > 0 && previousTx > 0) { rxSpeed = ((currentRx - previousRx) / 1024L).toInt(); txSpeed = ((currentTx - previousTx) / 1024L).toInt() }
                    previousRx = currentRx; previousTx = currentTx
                    if (diskCacheCheckCounter % 60 == 0) { val cacheDir = File(context.cacheDir, "image_cache"); lastDiskCacheSizeMb = if (cacheDir.exists()) (getFolderSize(cacheDir) / 1048576L).toInt() else 0 }
                    diskCacheCheckCounter++
                    _metricsFlow.value = SystemMetrics(totalRam, freeRam, usedRamPercent, calculateCpuUsage(), rxSpeed, txSpeed, lastDiskCacheSizeMb, maxJvm, usedJvm)
                } catch (_: Exception) {}
                delay(1000)
            }
        } finally { previousRx = 0L; previousTx = 0L }
    }

    private fun getFolderSize(dir: File): Long { var size = 0L; dir.listFiles()?.forEach { size += if (it.isDirectory) getFolderSize(it) else it.length() }; return size }
    private var lastProcessCpuTime = 0L; private var lastSystemUptime = 0L
    private fun calculateCpuUsage(): Int {
        try {
            val statFile = File("/proc/self/stat"); if (!statFile.exists()) return 0
            val stats = statFile.readText().split(" ")
            if (stats.size > 14) {
                val processCpuTime = stats[13].toLong() + stats[14].toLong()
                val systemUptime = android.os.SystemClock.elapsedRealtime()
                if (lastSystemUptime > 0) {
                    val uptimeDiff = systemUptime - lastSystemUptime; val cpuDiff = processCpuTime - lastProcessCpuTime
                    val hz = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
                    if (uptimeDiff > 0 && hz > 0) { val usage = (cpuDiff.toFloat() / hz * 1000f / uptimeDiff * 100).roundToInt(); lastProcessCpuTime = processCpuTime; lastSystemUptime = systemUptime; return usage.coerceIn(0, 100) }
                }
                lastProcessCpuTime = processCpuTime; lastSystemUptime = systemUptime
            }
        } catch (_: Exception) {}
        return 0
    }


}

fun WebDavViewModel.fetchSystemProcesses(sortBy: String = "cpu") {
    if (isLoadingProcesses) return
    isLoadingProcesses = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/processes?sort=$sortBy&limit=100").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    if (json.optString("status") == "success") {
                        val arr = json.optJSONArray("data")
                        val list = mutableListOf<SystemProcess>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(SystemProcess(
                                    pid = obj.optInt("pid", 0),
                                    name = obj.optString("name", "unknown"),
                                    user = obj.optString("user", "root"),
                                    status = obj.optString("status", "-"),
                                    cpu = obj.optDouble("cpu", 0.0).toFloat(),
                                    mem = obj.optDouble("mem", 0.0).toFloat()
                                ))
                            }
                        }
                        withContext(Dispatchers.Main) { systemProcesses = list }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            withContext(Dispatchers.Main) { isLoadingProcesses = false }
        }
    }
}
