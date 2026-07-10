package com.nas.naswebdav

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// FIX ERROR HANDLING: Chuyển lỗi kỹ thuật thành thông báo dễ hiểu
private val WEB_DAV_HTTP_FAILURE_REGEX = Regex("""^([A-Z]+) failed: (\d{3})(?: - (.*))?$""")

// FIX ERROR HANDLING: Convert technical errors into readable messages
private fun friendlyError(e: Exception): String = when (e) {
    is java.net.SocketTimeoutException -> "Kết nối tới NAS quá chậm hoặc NAS không phản hồi. Vui lòng kiểm tra mạng."
    is java.net.ConnectException -> "Không thể kết nối tới NAS. Kiểm tra NAS đã bật và cùng mạng WiFi."
    is java.net.UnknownHostException -> "Địa chỉ NAS không hợp lệ hoặc mất kết nối mạng."
    is javax.net.ssl.SSLException -> "Lỗi bảo mật kết nối. Kiểm tra cấu hình SSL/TLS của NAS."
    is java.io.IOException -> {
        val match = WEB_DAV_HTTP_FAILURE_REGEX.find(e.message.orEmpty())
        if (match != null) {
            val method = match.groupValues[1]
            val code = match.groupValues[2]
            val detail = match.groupValues.getOrNull(3)?.trim().orEmpty()
            when (code) {
                "401" -> "NAS từ chối xác thực (HTTP 401). Kiểm tra tài khoản/mật khẩu."
                "403" -> "NAS từ chối quyền thao tác (HTTP 403)."
                "404" -> "Không tìm thấy file/thư mục trên NAS (HTTP 404)."
                "405" -> "WebDAV không hỗ trợ lệnh $method (HTTP 405)."
                "409" -> "Xung đột trên NAS (HTTP 409)."
                "423" -> "Đối tượng đang bị khóa trên NAS (HTTP 423)."
                else -> "NAS trả về lỗi HTTP $code${if (detail.isNotBlank()) ": $detail" else ""}"
            }
        } else {
            e.message ?: "Lỗi không xác định"
        }
    }
    else -> e.message ?: "Lỗi không xác định"
}

private fun Throwable.isTransientNetworkFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.io.InterruptedIOException -> return true
        }
        val message = current.message.orEmpty()
        if (WEB_DAV_HTTP_FAILURE_REGEX.containsMatchIn(message)) return false
        val lower = message.lowercase()
        if (listOf(
                "timeout",
                "timed out",
                "failed to connect",
                "connection refused",
                "connection reset",
                "network is unreachable",
                "no route to host",
                "broken pipe",
                "socket closed",
                "unexpected end of stream",
                "unable to resolve host",
                "name not resolved"
            ).any { it in lower }) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun String.toOfflineQueuePath(baseUrl: String): String {
    val normalizedBase = baseUrl.trimEnd('/')
    return if (startsWith(normalizedBase)) {
        removePrefix(normalizedBase).trimStart('/')
    } else {
        this
    }
}

private fun buildLoginFailureMessage(urlList: List<String>, errorDetails: List<String>): String {
    if (errorDetails.isEmpty()) {
        return "Không đăng nhập được. Kiểm tra tài khoản, mật khẩu hoặc dịch vụ WebDAV."
    }
    // Hiển thị TẤT CẢ các URL đã thử (LAN + Tailscale) kèm lý do từng URL,
    // tránh hiểu nhầm chỉ một URL được thử khi nhiều URL cùng fail.
    val lines = errorDetails.map { detail ->
        val colonIdx = detail.indexOf(": ")
        val rawUrl = if (colonIdx > 0) detail.take(colonIdx) else detail
        val rawReason = if (colonIdx > 0) detail.substring(colonIdx + 2).take(110).trim() else "không xác định"
        val host = runCatching {
            val u = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
            java.net.URL(u).host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: rawUrl
        val niceReason = when {
            rawReason.contains("WebDAV", ignoreCase = true) -> "WebDAV quá hạn hoặc chưa xác thực"
            rawReason.contains("timeout", ignoreCase = true) || rawReason.contains("quá hạn", ignoreCase = true) || rawReason.contains("timed out", ignoreCase = true) -> "Mạng quá hạn / không phản hồi"
            rawReason.contains("Unable to resolve", ignoreCase = true) || rawReason.contains("UnknownHost", ignoreCase = true) -> "Không tìm thấy host"
            rawReason.contains("ECONNREFUSED", ignoreCase = true) || rawReason.contains("refused", ignoreCase = true) -> "Kết nối bị từ chối"
            rawReason.contains("ENETUNREACH", ignoreCase = true) || rawReason.contains("unreachable", ignoreCase = true) -> "Mạng không thể tiếp cận"
            else -> rawReason.trimEnd('.')
        }
        "• $host — $niceReason"
    }
    val header = if (lines.size > 1) "Không đăng nhập được NAS (đã thử ${lines.size} địa chỉ):" else "Không đăng nhập được NAS:"
    return "$header\n" + lines.joinToString("\n")
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
data class ThumbnailAuditData(val total: Int = 0, val thumbnailed: Int = 0, val missing: Int = 0, val running: Boolean = false, val paused: Boolean = false, val errors: Int = 0)
data class OmvServiceInfo(val name: String, val title: String, val enabled: Boolean, val running: Boolean, val effectiveEnabled: Boolean = enabled && running)
data class OmvNetworkInfo(val name: String, val address: String, val mac: String, val speed: Int, val state: String, val gateway: String, val wol: Boolean)
data class OmvFilesystem(val device: String, val label: String, val mountpoint: String, val used: String, val sizeBytes: Long, val percentage: Int, val description: String)
data class OmvDiskInfo(
    val name: String,
    val model: String,
    val serial: String,
    val size: String,
    val isRoot: Boolean,
    val device: String = "",
    val isTargetHdd: Boolean = false,
    val isUsbImport: Boolean = false
)
data class OmvOverview(
    val hostname: String = "", val omvVersion: String = "", val kernel: String = "",
    val services: List<OmvServiceInfo> = emptyList(),
    val network: List<OmvNetworkInfo> = emptyList(),
    val filesystems: List<OmvFilesystem> = emptyList(),
    val disks: List<OmvDiskInfo> = emptyList(),
    val powerBtnAction: String = ""
)

private fun normalizeWakeOnLanMac(raw: String): String? {
    val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.uppercase()
    if (hex.length != 12) return null
    return hex.chunked(2).joinToString(":")
}

private fun parseOmvNetwork(netArr: org.json.JSONArray): List<OmvNetworkInfo> =
    (0 until netArr.length()).map { i ->
        val n = netArr.getJSONObject(i)
        OmvNetworkInfo(
            n.optString("name"),
            n.optString("address"),
            n.optString("mac"),
            n.optInt("speed", -1),
            n.optString("state"),
            n.optString("gateway"),
            n.optBoolean("wol")
        )
    }

private fun persistDetectedWakeOnLanMac(network: List<OmvNetworkInfo>): String? {
    val adapter = network.firstOrNull {
        it.name != "lo" && it.wol && normalizeWakeOnLanMac(it.mac) != null
    } ?: network.firstOrNull {
        it.name != "lo" && normalizeWakeOnLanMac(it.mac) != null
    } ?: return null
    val detectedMac = normalizeWakeOnLanMac(adapter.mac) ?: return null
    val prefs = NasApplication.instance.applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
    val currentMac = normalizeWakeOnLanMac(prefs.getString("mac_address", "") ?: "")
    if (currentMac != detectedMac) {
        prefs.edit().putString("mac_address", detectedMac).apply()
    }
    return detectedMac
}

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

data class StorageFolderUsage(
    val name: String,
    val path: String,
    val size: String,
    val sizeBytes: Long,
    val files: Int,
    val partial: Boolean
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
    val fanOnTemp: Float = 45f,
    val fanOffTemp: Float = 40f,
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
            // Fix API auth header: attach Authorization to every Local API request
            .addInterceptor { chain ->
                val authHeader = chain.request().tag(WebDavManager.AuthState::class.java)?.authHeader
                    ?: WebDavManager.currentAuthHeader().takeIf {
                        WebDavManager.currentUser.isNotEmpty() || WebDavManager.currentPass.isNotEmpty()
                    }
                    ?: return@addInterceptor chain.proceed(chain.request())
                val requestBuilder = chain.request().newBuilder()
                requestBuilder.header("Authorization", authHeader)
                chain.proceed(requestBuilder.build())
            }
            .build()
    }
    // Biến lưu trữ trạng thái giám sát hệ thống (Local API)
    var systemStatus by mutableStateOf(NasSystemStatus())
    var temperatureHistory = androidx.compose.runtime.mutableStateListOf<Pair<Float, Float>>()

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
    private var dashboardRealtimeJob: kotlinx.coroutines.Job? = null
    internal var statusJob: kotlinx.coroutines.Job? = null
    private val realtimeMetricInFlight = AtomicBoolean(false)
    private val realtimeMetricNextAllowedAt = AtomicLong(0L)
    private val realtimeMetricBackoffMs = AtomicLong(5_000L)

    // TÍNH NĂNG 4.H: Lắng nghe trạng thái mạng Ping (ms)
    var networkPingMs by mutableStateOf<Long?>(null)
    var lastStatusRefreshAt by mutableStateOf(0L)
    var lastMetricsRefreshAt by mutableStateOf(0L)
    var lastStorageRefreshAt by mutableStateOf(0L)
    var lastSmartRefreshAt by mutableStateOf(0L)
    var lastLogsRefreshAt by mutableStateOf(0L)
    var apiLatencyMs by mutableStateOf<Long?>(null)
    var apiFailureCount by mutableIntStateOf(0)

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
    // NAS Config Backup/Restore state
    data class NasConfigBackup(
        val filename: String,
        val sizeBytes: Long,
        val sizeHuman: String,
        val createdAt: String,
        val mtime: Double,
    )
    var nasConfigBackups by mutableStateOf<List<NasConfigBackup>>(emptyList())
    var isCreatingNasConfigBackup by mutableStateOf(false)
    var isRestoringNasConfigBackup by mutableStateOf(false)
    var nasConfigBackupMessage by mutableStateOf("")

    // Disk Health Monitor state
    data class DiskHealthSample(
        val ts: Long,
        val datetime: String,
        val score: Int,
        val smartStatus: String,
        val tempC: Int?,
        val powerOnHours: Int?,
        val reallocatedSectors: Int?,
        val pendingSectors: Int?,
        val offlineUncorrectable: Int?,
        val udmaCrcErr: Int?,
        val commandTimeout: Int?,
        val ext4ErrorsRecent: Int,
        val sataResetsRecent: Int,
        val ioErrorsRecent: Int,
        val warnings: List<String>,
    )
    var diskHealthCurrent by mutableStateOf<DiskHealthSample?>(null)
    var diskHealthHistory by mutableStateOf<List<DiskHealthSample>>(emptyList())
    var isFetchingDiskHealth by mutableStateOf(false)
    var lastDiskHealthRefreshAt by mutableStateOf(0L)
    var storageFolderUsage by mutableStateOf<List<StorageFolderUsage>>(emptyList())
    var isFetchingStorageUsage by mutableStateOf(false)
    var isFetchingOmvOverview by mutableStateOf(false)

    // TÍNH NĂNG SMB
    var isSmbEnabled by mutableStateOf(false)
    var isLoadingSmb by mutableStateOf(false)

    // Scheduled backup state
    data class BackupSchedule(
        val enabled: Boolean = false,
        val frequency: String = "weekly",
        val hour: Int = 3,
        val retentionCount: Int = 7,
        val rcloneRemote: String = "",
        val rclonePath: String = "/NASBackup/",
        val lastRunTs: Long = 0L,
        val lastRunResult: String = "",
        val lastRunFile: String = "",
    )
    var backupSchedule by mutableStateOf(BackupSchedule())
    var backupScheduleMessage by mutableStateOf("")

    // USB Import state (NAS-side daemon)
    data class UsbImportSettings(
        val enabled: Boolean = true,
        val destFolder: String = "USB Import",
        val copyMode: String = "new_only",
        val autoMount: Boolean = true,
        val mountReadonly: Boolean = true,
        val pollSeconds: Int = 15,
        val resumeEnabled: Boolean = true,
        val verifyChecksum: Boolean = false,
    )
    data class UsbImportConflict(
        val rel: String = "",
        val sourceName: String = "",
        val destName: String = "",
        val sourceSize: Long = 0L,
        val destSize: Long = 0L,
    )
    data class UsbImportState(
        val enabled: Boolean = true,
        val status: String = "idle",
        val message: String = "",
        val activeDevice: String = "",
        val activeMount: String = "",
        val destDir: String = "",
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val filesTotal: Int = 0,
        val filesDone: Int = 0,
        val filesSkipped: Int = 0,
        val filesFailed: Int = 0,
        val bytesDone: Long = 0L,
        val bytesProcessed: Long = 0L,
        val bytesTotal: Long = 0L,
        val currentFile: String = "",
        val currentSource: String = "",
        val currentDest: String = "",
        val currentFileBytesDone: Long = 0L,
        val currentFileBytesTotal: Long = 0L,
        val copySpeedBps: Long = 0L,
        val etaSeconds: Long = 0L,
        val lastProgressAt: Long = 0L,
        val lastError: String = "",
        val settings: UsbImportSettings = UsbImportSettings(),
        val detectedDevicesInfo: String = "",
        val needsAction: Boolean = false,
        val pendingConflictsCount: Int = 0,
        val pendingErrorsCount: Int = 0,
        val pendingConflicts: List<UsbImportConflict> = emptyList(),
    )
    var usbImportState by mutableStateOf(UsbImportState())
    var usbImportMessage by mutableStateOf("")
    var isUsbImportLoading by mutableStateOf(false)
    private var lastUsbImportStatusFetchAt = 0L
    private val usbImportStatusInFlight = AtomicBoolean(false)

    data class InsightAction(val priority: String = "", val title: String = "", val detail: String = "")
    data class InsightFlowTask(
        val type: String = "",
        val label: String = "",
        val file: String = "",
        val source: String = "",
        val dest: String = "",
        val speedBps: Long = 0L,
        val progress: Int = 0,
    )
    data class NasInsights(
        val hddScore: Int = 0,
        val hddStatusText: String = "",
        val hddTempC: Int = 0,
        val hddMinScore: Int = 0,
        val hddScoreDelta: Int = 0,
        val workloadMode: String = "normal",
        val workloadPressure: Int = 0,
        val workloadRecommendation: String = "",
        val workloadReasons: List<String> = emptyList(),
        val emmcRootPercent: Int = 0,
        val emmcLogPercent: Int = 0,
        val emmcWarnings: List<String> = emptyList(),
        val emmcRecommendations: List<String> = emptyList(),
        val diskReadBps: Long = 0L,
        val diskWriteBps: Long = 0L,
        val netRxBps: Long = 0L,
        val netTxBps: Long = 0L,
        val flowTasks: List<InsightFlowTask> = emptyList(),
        val maintenanceActions: List<InsightAction> = emptyList(),
        val usbHistoryCount: Int = 0,
        val updatedAt: Long = 0L,
    )
    var nasInsights by mutableStateOf(NasInsights())
    var isFetchingNasInsights by mutableStateOf(false)
    private var lastNasInsightsFetchAt = 0L

    fun resetDashboardRefreshGuards() {
        lastDailyReportFetchAt = 0L
        lastDiskHealthRefreshAt = 0L
        lastLogsRefreshAt = 0L
        lastNasInsightsFetchAt = 0L
        lastOmvOverviewFetchAt = 0L
        lastSmartRefreshAt = 0L
        lastSmartRefreshAtVm = 0L
        lastStorageRefreshAt = 0L
    }

    // Sleep Schedule (HDD spindown / suspend) state
    data class SleepSchedule(
        val enabled: Boolean = false,
        val mode: String = "spindown",     // spindown | suspend
        val startHour: Int = 23,
        val endHour: Int = 7,
        val idleOnly: Boolean = true,
        val currentHddState: String = "unknown",
        val inWindowNow: Boolean = false,
        val lastActionTs: Long = 0L,
        val lastActionState: String = "",
    )
    var sleepSchedule by mutableStateOf(SleepSchedule())
    var sleepScheduleMessage by mutableStateOf("")

    // Biometric lock: cho phep BiometricSettingsDialog yeu cau lock ngay
    var lockNowRequested by mutableStateOf(false)

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

    private val _thumbnailAudit = MutableStateFlow<ThumbnailAuditData?>(null)
    val thumbnailAudit = _thumbnailAudit.asStateFlow()

    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var connectionStatus by mutableStateOf("Đang kết nối...")
    private val knownLatencyMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun adaptiveTimeoutMs(url: String): Long {
        val host = runCatching { java.net.URL(if (url.endsWith("/")) url else "$url/").host }.getOrNull() ?: ""
        val saved = knownLatencyMs[host]
        if (saved != null) return (saved * 4).coerceIn(500, 15_000)
        return if (isTailscaleUrl(url)) 6_000L else 3_000L
    }
    private fun recordLatency(url: String, ms: Long) {
        val host = runCatching { java.net.URL(if (url.endsWith("/")) url else "$url/").host }.getOrNull() ?: return
        knownLatencyMs[host] = ms
    }

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
        scanJob?.cancel() // Huỷ luồng theo dõi trạng thái Worker
        isScanningDuplicates = false // Đóng panel tiến trình
        duplicateFilesList = emptyList() // Xoá danh sách kết quả (nếu có) để ẩn card Tác vụ nền
        
        // Reset trạng thái tiến trình
        DuplicateProgressState.stage.value = "Khởi động..."
        DuplicateProgressState.percent.value = 0f
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
    // FIX: tranh reconnect storm — track so lan thu lai de exponential backoff
    // va co flag chong reconnect tu nhieu listener onFailure cu va race nhau.
    @Volatile internal var wsReconnectAttempt: Int = 0
    @Volatile internal var wsReconnectScheduled: Boolean = false

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

    fun logUserAction(module: String, message: String, type: String = "INFO") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addSystemLog(type, module, "Người dùng: $message")
                withContext(Dispatchers.Main) { loadSystemLogs() }
            } catch (e: Exception) {
                android.util.Log.w("UserActionLog", "log failed: ${e.message}")
            }
        }
    }

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

                        val appContext = com.nas.naswebdav.NasApplication.instance.applicationContext
                        if (thumbRunning && thumbTotal > 0) {
                            val percent = if (thumbTotal > 0) (thumbGenerated * 100 / thumbTotal) else 0
                            showSystemNotification(appContext, 9011, "Đang tạo Thumbnail (" + thumbGenerated + " / " + thumbTotal + ")", "File hiện tại: " + thumbLastFile, percent)
                        } else {
                            cancelSystemNotification(appContext, 9011)
                        }
                        }                    }
                }
            } catch (e: Exception) {
                Log.w("WebDavViewModel", "fetchThumbStatus failed", e)
        }
        }
    }

    private fun showSystemNotification(context: android.content.Context, id: Int, title: String, content: String, progress: Int? = null) {
        val channelId = "nas_background_tasks"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Tiến trình ngầm NAS", android.app.NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            context.getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(content).setOngoing(true).setSilent(true).setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
        if (progress != null) { builder.setProgress(100, progress, false) } else { builder.setProgress(0, 0, true) }
        try { androidx.core.app.NotificationManagerCompat.from(context).notify(id, builder.build()) } catch (_: SecurityException) {}
    }
    
    private fun cancelSystemNotification(context: android.content.Context, id: Int) {
        try { androidx.core.app.NotificationManagerCompat.from(context).cancel(id) } catch (_: SecurityException) {}
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
            } catch (_: Exception) { }
        }
    }

    fun fetchThumbnailAudit() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (apiBase.isBlank()) return@launch
                val apiUrl = "$apiBase/api/thumb/status"
                
                val user = com.nas.naswebdav.SecurePrefsHelper.getUser(NasApplication.instance.applicationContext)
                val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(NasApplication.instance.applicationContext)
                
                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .build()
                localApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val obj = org.json.JSONObject(body)
                        val total = obj.optInt("total_media", 0)
                        val generated = obj.optInt("generated", 0)
                        _thumbnailAudit.value = ThumbnailAuditData(
                            total = total,
                            thumbnailed = generated,
                            missing = if (total > generated) total - generated else 0,
                            running = obj.optBoolean("running", false),
                            paused = obj.optBoolean("paused", false),
                            errors = obj.optInt("errors", 0)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("WebDavViewModel", "fetchThumbnailAudit failed", e)
            }
        }
    }

    fun triggerThumbnailScan() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (apiBase.isBlank()) return@launch
                val apiUrl = "$apiBase/api/thumb/control"
                
                val user = com.nas.naswebdav.SecurePrefsHelper.getUser(NasApplication.instance.applicationContext)
                val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(NasApplication.instance.applicationContext)
                
                val body = "{\"action\": \"resume\"}".toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .build()
                localApiClient.newCall(request).execute().use { }
                kotlinx.coroutines.delay(1000)
                fetchThumbnailAudit()
            } catch (e: Exception) {
                Log.w("WebDavViewModel", "triggerThumbnailScan failed", e)
            }
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
                            throw CancellationException("Unzip WorkInfo collector finished")
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = message.ifEmpty { "Giải nén thất bại!" }
                            showCommonDialog = true
                            throw CancellationException("Unzip WorkInfo collector finished")
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
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                val text = localApiClient.newCall(request).execute().use { it.body?.string() ?: "" }
                withContext(Dispatchers.Main) {
                    val o = try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
                    commonDialogMessage = if (o.optString("result") == "ok")
                        "✅ Đã gửi link cho qBittorrent. Theo dõi tiến trình ở Dashboard."
                    else "❌ Lỗi: ${o.optString("error", "không phản hồi")}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                    showCommonDialog = true
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogMessage = "❌ Lỗi mạng: ${e.message?.take(120)}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            }
        }
    }

    /** Upload 1 file .torrent len NAS → qBittorrent.
     *  Goi tu Dispatchers.IO se OK; method nay tu launch coroutine. */
    fun uploadTorrentFile(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                // Tim ten file (DISPLAY_NAME)
                var fileName = "uploaded.torrent"
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cur ->
                        val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cur.moveToFirst() && idx >= 0) {
                            val n = cur.getString(idx)
                            if (!n.isNullOrBlank()) fileName = n
                        }
                    }
                } catch (_: Exception) {}
                // Sanitize ten file
                val safeName = fileName.replace('/', '_').replace('\\', '_').take(200)
                    .let { if (it.lowercase().endsWith(".torrent")) it else "$it.torrent" }

                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Không đọc được nội dung file")
                if (bytes.size < 64) throw IllegalArgumentException("File .torrent quá nhỏ")
                if (bytes[0].toInt().toChar() != 'd') throw IllegalArgumentException("File không phải định dạng torrent hợp lệ")

                val mediaType = "application/x-bittorrent".toMediaTypeOrNull()
                val filePart = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", safeName, bytes.toRequestBody(mediaType, 0, bytes.size))
                    .build()

                val req = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/torrent/add_file")
                    .post(filePart)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                val client = localApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val text = client.newCall(req).execute().use { it.body?.string() ?: "" }
                val o = try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
                withContext(Dispatchers.Main) {
                    commonDialogMessage = if (o.optString("result") == "ok")
                        "✅ Đã gửi $safeName cho qBittorrent (${o.optInt("size")} bytes)"
                    else "❌ Lỗi: ${o.optString("error", "không phản hồi")}"
                    commonDialogType = if (o.optString("result") == "ok") com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS else com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogMessage = "❌ Lỗi upload torrent: ${e.message?.take(120)}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
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
        var startedTs: Long = 0,
        var speed: String = "",
        var outputFile: String = "",
        // TikTok username (de UI hien "@user" thay vi job_id/filename rac roi).
        // Rong khi job khong gan voi user nao (vd ghi facebook/youtube).
        var watchUsername: String = ""
    )

    private fun livestreamJobSizeBytes(fileSize: String): Long {
        val value = fileSize.replace(",", ".")
            .replace(Regex("[^0-9.]"), "")
            .toDoubleOrNull() ?: return 0L
        val unit = fileSize.uppercase(java.util.Locale.US)
        val multiplier = when {
            "TB" in unit || "TIB" in unit -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            "GB" in unit || "GIB" in unit -> 1024.0 * 1024.0 * 1024.0
            "MB" in unit || "MIB" in unit -> 1024.0 * 1024.0
            "KB" in unit || "KIB" in unit -> 1024.0
            else -> 1.0
        }
        return (value * multiplier).toLong().coerceAtLeast(0L)
    }

    private fun livestreamDisplayKey(job: LivestreamJob): String {
        val user = job.watchUsername.trim().removePrefix("@").lowercase(java.util.Locale.US)
        if (user.isNotBlank()) return "user:$user"
        val fileStem = job.outputFile.trim().substringBeforeLast('.').lowercase(java.util.Locale.US)
        return if (fileStem.isNotBlank()) "file:$fileStem" else "job:${job.jobId}"
    }

    fun dedupeLivestreamJobsForDisplay(jobs: List<LivestreamJob>): List<LivestreamJob> {
        return jobs
            .filter { it.status == "recording" && it.jobId.isNotBlank() }
            .groupBy { livestreamDisplayKey(it) }
            .mapNotNull { (_, group) ->
                group.maxWithOrNull(
                    compareBy<LivestreamJob> { livestreamJobSizeBytes(it.fileSize) }
                        .thenBy { if (it.outputFile.isNotBlank()) 1 else 0 }
                        .thenBy { if (it.speed.isNotBlank() && it.speed != "—") 1 else 0 }
                        .thenBy { it.durationSeconds }
                        .thenBy { it.startedTs }
                )
            }
            .filter { job ->
                job.outputFile.isNotBlank() || livestreamJobSizeBytes(job.fileSize) > 0L
            }
            .sortedByDescending { it.startedTs }
    }

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
    internal var lastLivestreamServerSyncAt = 0L
    internal var lastLivestreamServerRecordingIds: Set<String> = emptySet()
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

    // Trang thai cookies.txt TikTok do NAS bao cao:
    // "valid" / "missing" / "expired" / "revoked" / "unknown"
    var tiktokCookiesStatus by mutableStateOf("unknown")
        private set
    var tiktokCookiesMessage by mutableStateOf("")
        private set
    var tiktokWatchDaemonRunning by mutableStateOf(false)
        private set
    var tiktokWatchDaemonLastTick by mutableStateOf("")
        private set
    var tiktokWatchDaemonSummary by mutableStateOf("")
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
        tiktokCookiesStatus = json.optString("cookies_status", "unknown")
        tiktokCookiesMessage = json.optString("cookies_message", "")
        json.optJSONObject("daemon")?.let { daemon ->
            val serverRunning = daemon.optBoolean("running", false)
            val hasFreshHeartbeat = daemon.optLong("heartbeat_age_seconds", Long.MAX_VALUE) < 600L
            val hasActiveWatchRecord = tiktokLiveWatchUsers.any { it.status == "recording" || it.jobId.isNotBlank() }
            tiktokWatchDaemonRunning = serverRunning || hasFreshHeartbeat || hasActiveWatchRecord
            tiktokWatchDaemonLastTick = daemon.optString("last_tick", "")
            tiktokWatchDaemonSummary = daemon.optString("last_summary", "")
        } ?: run {
            tiktokWatchDaemonRunning = false
            tiktokWatchDaemonLastTick = ""
            tiktokWatchDaemonSummary = ""
        }
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
                throw IllegalStateException(result.optString("error", "NAS từ chối (${response.code})"))
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
                repository.addSystemLog("INFO", "TikTokWatch", "Người dùng: thêm tài khoản theo dõi live @$clean.")
                // Bắt job ngay nếu user vừa thêm đang live - không chờ 15p chu kỳ Discovery.
                syncLivestreamStateWithServer(context)
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "Người dùng: thêm tài khoản @$clean thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lỗi: ${e.message?.take(80) ?: "Không thêm được người dùng"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "Người dùng: xoá tài khoản theo dõi live @$username.")
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "Người dùng: xoá tài khoản @$username thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lỗi: ${e.message?.take(80) ?: "Không xoá được người dùng"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "Người dùng: cập nhật khung loại trừ TikTok Watch (${if (enabled) "bật" else "tắt"}, $start-$end).")
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "Người dùng: cập nhật cấu hình TikTok Watch thất bại: ${e.message?.take(120)}")
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
                // Boc tach TikTok username tu URL de:
                // (1) gan vao body de NAS luu vao job dict -> /api/livestream/status tra "watch_username"
                // (2) sau khi POST OK, tu dong them user vao danh sach theo doi
                // Regex cho phep dau cham + dau gach (TikTok username "cao-xinh.005").
                val tiktokUsername: String = if (url.contains("tiktok", ignoreCase = true)) {
                    Regex("tiktok\\.com/@([\\w.\\-]+)").find(url)?.groupValues?.get(1) ?: ""
                } else ""

                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = org.json.JSONObject().apply {
                    put("url", url)
                    put("quality", quality)
                    put("referer", referer)
                    put("user_agent", userAgent)
                    if (tiktokUsername.isNotBlank()) put("watch_username", tiktokUsername)
                }.toString().toRequestBody(jsonMediaType)

                val apiBaseUrl = webDavManager.currentBaseUrl.toApiBaseUrl()
                    .ifBlank { currentUrl.toApiBaseUrl() }
                if (apiBaseUrl.isBlank()) {
                    throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                }
                val requestBuilder = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/livestream/record")
                    .post(body)

                val user = SecurePrefsHelper.getUser(context)
                val pass = SecurePrefsHelper.getPass(context)
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                }

                // FIX: dùng client có timeout dài hơn (60s) chỉ riêng cho call này —
                // preflight check TikTok có thể tốn 20-30s (curl HTML + probe FLV).
                // Không tăng timeout của client mặc định vì các endpoint khác phải
                // trả kết quả nhanh.
                val recordClient = localApiClient.newBuilder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                recordClient.newCall(requestBuilder.build()).execute().use { response ->
                    val rawBody = response.body?.string() ?: "{}"
                    val json = try { org.json.JSONObject(rawBody) } catch (_: Exception) { org.json.JSONObject() }
                    if (response.isSuccessful) {
                        val jobId    = json.optString("job_id", "")
                        val platform = json.optString("platform", "")
                        repository.addSystemLog("INFO", "Livestream", "Người dùng: bắt đầu ghi livestream ${tiktokUsername.ifBlank { url.take(80) }} chất lượng $quality.")

                        // Thêm vào danh sách active (mặc định trạng thái recording)
                        withContext(Dispatchers.Main) {
                            if (activeLivestreams.none { it.jobId == jobId }) {
                                activeLivestreams.add(WebDavViewModel.LivestreamJob(jobId, platform, watchUsername = tiktokUsername))
                            }
                            livestreamMessage   = json.optString("message", "Đang khởi động ghi hình...")
                        }

                        // Khởi động Foreground Worker độc lập với vòng đời app
                        LivestreamMonitorWorker.enqueue(context, jobId, host, platform)

                        // Observe tiến trình từ Worker để cập nhật UI
                        observeLivestreamWorker(context)

                        // FIX: Tự động thêm username vào watcher list nếu chưa có —
                        // đảm bảo mỗi lúc user ghi 1 live mới qua link, lần sau watcher
                        // sẽ tự phát hiện và auto-record. Không dựa vào logic ở Dialog
                        // (để robust trong mọi flow gọi startLivestreamRecord).
                        if (tiktokUsername.isNotBlank() &&
                            tiktokLiveWatchUsers.none { it.username.equals(tiktokUsername, ignoreCase = true) }) {
                            addTikTokLiveWatchUser(context, tiktokUsername)
                        }
                    } else {
                        // FIX: server tra error CU THE qua field "error" + "reason"
                        // Map HTTP code de hien icon/mau dialog hop ly.
                        val errMsg = json.optString("error", "").ifBlank {
                            "Lỗi NAS (HTTP ${response.code}): ${rawBody.take(150)}"
                        }
                        withContext(Dispatchers.Main) {
                            livestreamMessage = errMsg
                            commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = errMsg
                            showCommonDialog    = true
                        }
                        repository.addSystemLog("WARNING", "Livestream", "Người dùng: bắt đầu ghi livestream thất bại: ${errMsg.take(120)}")
                    }
                }
            } catch (e: Exception) {
                // FIX: phan loai exception cu the thay vi "Lỗi kết nối NAS: null"
                val errMsg = when (e) {
                    is java.net.SocketTimeoutException ->
                        "NAS chưa trả kết quả kịp khi phân tích link TikTok. Không tạo thêm phiên trùng; hãy chờ trạng thái ghi cập nhật rồi thử lại nếu chưa thấy chạy."
                    is java.net.ConnectException ->
                        "Không kết nối được NAS. Kiểm tra: NAS có đang chạy không? Tailscale có bật không?"
                    is java.net.UnknownHostException ->
                        "Không tìm thấy NAS (DNS/Tailscale lỗi). Kiểm tra lại địa chỉ kết nối."
                    is javax.net.ssl.SSLException ->
                        "Lỗi SSL: ${e.message ?: "Chứng chỉ NAS không hợp lệ"}"
                    else -> {
                        val raw = e.message?.take(200)
                        if (raw.isNullOrBlank()) "Lỗi ${e.javaClass.simpleName} không có chi tiết"
                        else "Lỗi: $raw"
                    }
                }
                withContext(Dispatchers.Main) {
                    livestreamMessage   = errMsg
                    commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = errMsg
                    showCommonDialog    = true
                }
                repository.addSystemLog("WARNING", "Livestream", "Người dùng: bắt đầu ghi livestream thất bại: ${errMsg.take(120)}")
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
            val serverSnapshotIsFresh = System.currentTimeMillis() - lastLivestreamServerSyncAt < 15_000L
            if (serverSnapshotIsFresh && lastLivestreamServerRecordingIds.isEmpty()) {
                LivestreamMonitorWorker.cancelAll(context)
                withContext(Dispatchers.Main) {
                    activeLivestreams.clear()
                }
                return@launch
            }

            val workInfos = androidx.work.WorkManager.getInstance(context)
                .getWorkInfosByTag("LIVESTREAM_ALL").get()
            
            val activeWorks = workInfos.filter {
                it.state == androidx.work.WorkInfo.State.RUNNING ||
                it.state == androidx.work.WorkInfo.State.ENQUEUED
            }
            
            val restoredJobs = mutableListOf<LivestreamJob>()
            for (work in activeWorks) {
                val progress = work.progress
                // Worker vua enqueue chua kip setProgress -> progress rong.
                // Fallback parse jobId tu tag "LIVESTREAM_MONITOR_<jobId>" de
                // khong miss job vua khoi (vd: do Discovery Worker hoac sync).
                val jobId = progress.getString(LivestreamMonitorWorker.OUT_JOB_ID)
                    ?: work.tags.firstOrNull { it.startsWith(LivestreamMonitorWorker.WORK_NAME_PREFIX) }
                        ?.removePrefix(LivestreamMonitorWorker.WORK_NAME_PREFIX)
                    ?: continue

                val workerStatus = progress.getString(LivestreamMonitorWorker.OUT_STATUS)
                restoredJobs.add(
                    LivestreamJob(
                        jobId = jobId,
                        platform = "",
                        status = workerStatus ?: "pending",
                        fileSize = progress.getString(LivestreamMonitorWorker.OUT_FILE_SIZE) ?: "0 B",
                        duration = progress.getString(LivestreamMonitorWorker.OUT_DURATION) ?: "0h00m00s",
                        durationSeconds = progress.getLong(LivestreamMonitorWorker.OUT_DURATION_SECONDS, 0L),
                        startedTs = progress.getLong(LivestreamMonitorWorker.OUT_STARTED_TS, 0L),
                        speed = progress.getString(LivestreamMonitorWorker.OUT_SPEED) ?: "",
                        outputFile = progress.getString(LivestreamMonitorWorker.OUT_OUTPUT_FILE) ?: "",
                        watchUsername = progress.getString(LivestreamMonitorWorker.OUT_WATCH_USER) ?: ""
                    )
                )
            }

            val displayJobs = dedupeLivestreamJobsForDisplay(restoredJobs)
            withContext(Dispatchers.Main) {
                activeLivestreams.clear()
                activeLivestreams.addAll(displayJobs)

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
                        val serverRecordingIds = mutableSetOf<String>()
                        val serverJobs = mutableListOf<LivestreamJob>()
                        for (i in 0 until jobsArray.length()) {
                            val jobObj = jobsArray.getJSONObject(i)
                            val status = jobObj.optString("status", "")
                            val jobId = jobObj.optString("job_id", "")
                            val platform = jobObj.optString("platform", "")
                            
                            if (status == "recording" && jobId.isNotEmpty()) {
                                serverRecordingIds.add(jobId)
                                val watchUser = jobObj.optString("watch_username", "")
                                val durationSeconds = jobObj.optLong("duration_seconds", 0L)
                                val startedTs = jobObj.optLong("started_ts", 0L)
                                serverJobs.add(
                                    LivestreamJob(
                                        jobId = jobId,
                                        platform = platform,
                                        status = status,
                                        watchUsername = watchUser,
                                        durationSeconds = durationSeconds,
                                        startedTs = startedTs,
                                        fileSize = jobObj.optString("file_size", "0 B"),
                                        duration = jobObj.optString("duration_display", "0h00m00s"),
                                        speed = jobObj.optString("avg_speed", "—"),
                                        outputFile = jobObj.optString("output_file", "")
                                    )
                                )
                            } else if (jobId.isNotEmpty()) {
                                LivestreamMonitorWorker.cancelJob(context, jobId)
                            }
                        }
                        val displayJobs = dedupeLivestreamJobsForDisplay(serverJobs)
                        val displayJobIds = displayJobs.map { it.jobId }.toSet()
                        for (job in displayJobs) {
                            val jobId = job.jobId
                            val platform = job.platform
                            val watchUser = job.watchUsername
                            val durationSeconds = job.durationSeconds
                            val startedTs = job.startedTs
                                // Nếu tiến trình đang chạy trên NAS nhưng điện thoại không biết (hoặc bị xoá cache data)
                                val alreadyTracked = activeLivestreams.any { it.jobId == jobId }
                                if (!alreadyTracked) {
                                    val host = java.net.URL(currentUrl).host
                                    withContext(Dispatchers.Main) {
                                        activeLivestreams.add(job)
                                    }
                                    LivestreamMonitorWorker.enqueue(context, jobId, host, platform)
                                    hasNewJobs = true
                                } else if (watchUser.isNotEmpty() || durationSeconds > 0 || startedTs > 0) {
                                    // Cap nhat watchUsername cho job da co
                                    withContext(Dispatchers.Main) {
                                        val idx = activeLivestreams.indexOfFirst { it.jobId == jobId }
                                        if (idx >= 0) {
                                            val existing = activeLivestreams[idx]
                                            activeLivestreams[idx] = existing.copy(
                                                watchUsername = if (watchUser.isNotEmpty()) watchUser else existing.watchUsername,
                                                durationSeconds = if (durationSeconds > 0) durationSeconds else existing.durationSeconds,
                                                startedTs = if (startedTs > 0) startedTs else existing.startedTs,
                                                fileSize = job.fileSize,
                                                duration = job.duration,
                                                speed = job.speed,
                                                outputFile = job.outputFile
                                            )
                                        }
                                    }
                                }
                        }
                        lastLivestreamServerSyncAt = System.currentTimeMillis()
                        lastLivestreamServerRecordingIds = displayJobIds
                        withContext(Dispatchers.Main) {
                            activeLivestreams.removeAll { it.jobId !in displayJobIds }
                        }
                        if (displayJobs.isEmpty()) {
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
                android.util.Log.e("LivestreamSync", "Lỗi đồng bộ trạng thái livestream: ${e.message}")
                restoreLivestreamStateIfRunning(context)
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
                                        val reason = progress.getString("error_reason")
                                            ?: info.outputData.getString("error_reason")
                                            ?: ""
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
                                val durSec = progress.getLong(LivestreamMonitorWorker.OUT_DURATION_SECONDS, -1L)
                                val startedTs = progress.getLong(LivestreamMonitorWorker.OUT_STARTED_TS, -1L)
                                val spd = progress.getString(LivestreamMonitorWorker.OUT_SPEED)
                                val outF = progress.getString(LivestreamMonitorWorker.OUT_OUTPUT_FILE)
                                val wu  = progress.getString(LivestreamMonitorWorker.OUT_WATCH_USER)

                                val updated = existing.copy(
                                    fileSize = if (!fs.isNullOrEmpty()) fs else existing.fileSize,
                                    duration = if (!dur.isNullOrEmpty()) dur else existing.duration,
                                    durationSeconds = if (durSec >= 0L) durSec else existing.durationSeconds,
                                    startedTs = if (startedTs > 0L) startedTs else existing.startedTs,
                                    speed = if (!spd.isNullOrEmpty()) spd else existing.speed,
                                    outputFile = if (!outF.isNullOrEmpty()) outF else existing.outputFile,
                                    watchUsername = if (!wu.isNullOrEmpty()) wu else existing.watchUsername
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
                repository.addSystemLog("INFO", "Livestream", "Người dùng: dừng ghi livestream job $jobId.")
                withContext(Dispatchers.Main) {
                    activeLivestreams.removeAll { it.jobId == jobId }
                    livestreamMessage   = "⏹ Đã dừng ghi hình. File đang được xử lý..."
                }
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "Livestream", "Người dùng: dừng ghi livestream job $jobId thất bại: ${e.message?.take(120)}")
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
                val onLan = !isTailscaleUrl(currentUrl)
                connectionStatus = if (nasHost.isNotEmpty()) {
                    "Đã kết nối ${if (onLan) "LAN" else "Tailscale"}: $nasHost"
                } else {
                    "Đã kết nối ${if (onLan) "LAN" else "Tailscale"}"
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
    internal fun enqueueOfflineAction(context: Context, actionType: String, sourcePath: String, destPath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as NasApplication).database
                val queueBaseUrl = webDavManager.currentBaseUrl
                val normalizedSourcePath = sourcePath.toOfflineQueuePath(queueBaseUrl)
                val normalizedDestPath = destPath?.toOfflineQueuePath(queueBaseUrl)
                db.syncActionDao().insert(SyncAction(
                    actionType = actionType,
                    sourcePath = normalizedSourcePath,
                    destPath = normalizedDestPath
                ))

                // B?o WorkManager ch?y khi c? m?ng
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val request = androidx.work.OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    OfflineSyncWorker.UNIQUE_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )

                // Hi?n th? Dialog b?o cho User
                withContext(Dispatchers.Main) {
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING
                    commonDialogMessage = "Kh?ng c? k?t n?i. L?nh '$actionType' ?? ???c ??a v?o h?ng ??i ngo?i tuy?n."
                    showCommonDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "L?i khi l?u h?ng ??i ngo?i tuy?n: ${e.message}" }
            }
        }
    }

    fun deleteFile(context: Context, file: NasFile) {
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }

        viewModelScope.launch(Dispatchers.IO) {
            val trashMetaDao = NasApplication.instance.database.trashMetaDao()
            val isInTrash = file.path.contains(TRASH_FOLDER_NAME)
            val trashFolderUrl = buildWebDavTrashTargetUrl(
                webDavManager.currentBaseUrl,
                file.path,
                "",
                false
            )
            val trashTargetUrl = if (!isInTrash) buildWebDavTrashTargetUrl(
                webDavManager.currentBaseUrl,
                file.path,
                file.name,
                file.isDirectory
            ) else file.path

            try {
                if (isInTrash) {
                    webDavManager.deleteFile(file.path, file.isDirectory)
                    trashMetaDao.deleteByTrashPath(file.path)
                    repository.addSystemLog("WARNING", "File Ops", "Đã xóa vĩnh viễn tệp '${file.name}'.")
                } else {
                    try { webDavManager.createFolder(trashFolderUrl) } catch (_: Exception) {}
                    webDavManager.renameFile(file.path, trashTargetUrl)
                    trashMetaDao.insert(TrashMeta(trashPath = trashTargetUrl, originalPath = file.path))
                    repository.addSystemLog("WARNING", "File Ops", "Đã di chuyển tệp '${file.name}' vào Thùng rác.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList }

                val message = friendlyError(e)
                if (e.isTransientNetworkFailure()) {
                    repository.addSystemLog("WARNING", "File Ops", "Xóa tệp '${file.name}' thất bại, đã được đưa vào hàng đợi ngoại tuyến: ${message.take(80)}")
                    if (isInTrash) {
                        enqueueOfflineAction(context, "DELETE", file.path)
                    } else {
                        enqueueOfflineAction(context, "RENAME", file.path, trashTargetUrl)
                    }
                } else {
                    repository.addSystemLog("ERROR", "File Ops", "Xóa tệp '${file.name}' thất bại: ${message.take(120)}")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                        commonDialogMessage = "Không thể xóa '${file.name}': $message"
                        showCommonDialog = true
                    }
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
                            val failCount = workInfo.progress.getInt("failCount", 0)
                            val shouldRefresh = when (operation) {
                                "COPY", "DELETE", "RESTORE" -> true
                                "MOVE" -> destUrl.startsWith(currentUrl) || failCount > 0
                                else -> false
                            }
                            if (shouldRefresh) {
                                refresh()
                            }
                            throw CancellationException("Batch WorkInfo collector finished")
                        }
                    }
                }
        }
    }

    fun restoreFile(context: Context, file: NasFile) {
        // Optimistic UI: keep instant feel while remote move happens in background.
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }
        viewModelScope.launch(Dispatchers.IO) {
            val trashMetaDao = NasApplication.instance.database.trashMetaDao()
            val meta = runCatching { trashMetaDao.findByTrashPath(file.path) }.getOrNull()
            val targetUrl = meta?.originalPath ?: buildWebDavRestoreTargetUrl(
                webDavManager.currentBaseUrl,
                file.path,
                file.name,
                file.isDirectory
            )
            try {
                webDavManager.renameFile(file.path, targetUrl)
                trashMetaDao.deleteByTrashPath(file.path)
                repository.addSystemLog("INFO", "File Ops", "Đã khôi phục tệp '${file.name}' từ Thùng rác.")
                // Keep current list; no full refresh needed here.
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList }
                val message = friendlyError(e)
                if (e.isTransientNetworkFailure()) {
                    repository.addSystemLog("WARNING", "File Ops", "Khôi phục tệp '${file.name}' thất bại, đã được đưa vào hàng đợi ngoại tuyến: ${message.take(80)}")
                    enqueueOfflineAction(context, "RENAME", file.path, targetUrl)
                } else {
                    repository.addSystemLog("ERROR", "File Ops", "Khôi phục tệp '${file.name}' thất bại: ${message.take(120)}")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                        commonDialogMessage = "Không thể khôi phục '${file.name}': $message"
                        showCommonDialog = true
                    }
                }
            }
        }
    }

    fun restoreMultipleFiles(context: Context, filesToRestore: List<NasFile>) {
        if (filesToRestore.isEmpty()) return

        // Optimistic UI for bulk restore.
        val pathsToRestore = filesToRestore.map { it.path }.toSet()
        fileList = fileList.filter { it.path !in pathsToRestore }

        // Push heavy work to Foreground Worker.
        enqueueBatchOperation(context, "RESTORE", filesToRestore, "")
    }

    fun renameFile(context: Context, file: NasFile, newName: String) {
        // Optimistic UI: update RAM first, commit to NAS in background.
        val oldList = fileList
        var newUrl = currentUrl + encodeWebDavSegment(newName)
        if (file.isDirectory && !newUrl.endsWith("/")) newUrl += "/"
        val renamedFile = file.copy(name = newName, path = newUrl)
        fileList = oldList.map { if (it.path == file.path) renamedFile else it }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDavManager.renameFile(file.path, newUrl)
                repository.addSystemLog("INFO", "File Ops", "Đổi tên tệp '${file.name}' thành '${newName}'.")
                // No refresh() to keep UI smooth.
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList } // rollback
                val message = friendlyError(e)
                if (e.isTransientNetworkFailure()) {
                    repository.addSystemLog("WARNING", "File Ops", "Đổi tên '${file.name}' thất bại, đã được đưa vào hàng đợi ngoại tuyến: ${message.take(80)}")
                    enqueueOfflineAction(context, "RENAME", file.path, newUrl)
                } else {
                    repository.addSystemLog("ERROR", "File Ops", "Đổi tên '${file.name}' thất bại: ${message.take(120)}")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                        commonDialogMessage = "Không thể đổi tên '${file.name}': $message"
                        showCommonDialog = true
                    }
                }
            }
        }
    }

    fun createFolder(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                // Keep URL segment encoded to avoid MOVE/MKCOL failures on spaces/unicode.
                val newFolderUrl = currentUrl + encodeWebDavSegment(folderName) + "/"
                webDavManager.createFolder(newFolderUrl)
                repository.addSystemLog("SUCCESS", "File Ops", "Đã tạo thư mục mới: '$folderName'")
                withContext(Dispatchers.Main) { refresh() } // reload after success
            } catch (e: Exception) {
                val message = friendlyError(e)
                if (e.isTransientNetworkFailure()) {
                    repository.addSystemLog("WARNING", "File Ops", "Tạo thư mục '$folderName' thất bại, đã được đưa vào hàng đợi ngoại tuyến: ${message.take(80)}")
                    val newFolderUrl = currentUrl + encodeWebDavSegment(folderName) + "/"
                    enqueueOfflineAction(context, "CREATE_FOLDER", newFolderUrl)
                } else {
                    repository.addSystemLog("ERROR", "File Ops", "Tạo thư mục '$folderName' thất bại: ${message.take(120)}")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                        commonDialogMessage = "Không thể tạo thư mục '$folderName': $message"
                        showCommonDialog = true
                    }
                }
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
                        connectionStatus = if (onLan) "Chuyển sang LAN - Gigabit" else "Chuyển sang Tailscale VPN"
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
                        connectionStatus = if (onLan) "LAN - Gigabit" else "Tailscale VPN"
                    }
                    repository.addSystemLog(
                        "INFO", "SmartSwitch",
                        "Chuyển mạng: ${if (onLan) "LAN" else "Tailscale"} ($safeActive)"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("SmartSwitch", "Lỗi kiểm tra mạng thông minh: ${e.message}")
            }
        }
    }

    private var lastForegroundRefreshAt = 0L
    private var foregroundRefreshJob: Job? = null
    private var lastForegroundHeavyRefreshAt = 0L

    fun refreshNasStateOnForeground(context: android.content.Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastForegroundRefreshAt < 2500L) return
        lastForegroundRefreshAt = now
        foregroundRefreshJob?.cancel()

        foregroundRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (webDavManager.currentBaseUrl.isEmpty()) {
                    val savedUrl = SmartNetworkManager.getActiveBaseUrl(context)
                        .ifEmpty { SecurePrefsHelper.getUrl(context) }
                    val savedUser = SecurePrefsHelper.getUser(context)
                    val savedPass = SecurePrefsHelper.getPass(context)
                    if (savedUrl.isNotBlank() && savedUser.isNotBlank()) {
                        runCatching { webDavManager.connect(if (savedUrl.endsWith("/")) savedUrl else "$savedUrl/", savedUser, savedPass) }
                    }
                }
            } catch (_: Exception) {}

            val shouldRunHeavyRefresh = force || now - lastForegroundHeavyRefreshAt > 15_000L
            if (shouldRunHeavyRefresh) lastForegroundHeavyRefreshAt = now

            withContext(Dispatchers.Main) {
                AppConfig.IS_APP_FOREGROUND = true
                checkSmartNetwork(context)
                fetchSmartData()
            }

            delay(200L)
            withContext(Dispatchers.Main) {
                startDashboardMonitoring(resetStatusPoll = false)
                fetchThumbStatus()
            }

            delay(500L)
            withContext(Dispatchers.Main) {
                restoreLivestreamStateIfRunning(context)
                fetchLivestreamStatusOnly(context)
                fetchTikTokLiveWatch(context)
                refresh()
            }

            if (!shouldRunHeavyRefresh) return@launch

            delay(300L)
            withContext(Dispatchers.Main) {
                fetchUsbImportStatus()
                syncLivestreamStateWithServer(context)
            }

            delay(700L)
            withContext(Dispatchers.Main) {
                fetchNasInsights()
            }

            delay(1000L)
            withContext(Dispatchers.Main) {
                fetchOmvOverview()
                fetchStorageUsage()
                loadSystemLogs()
            }
        }
    }

    private var loginJob: Job? = null

    fun cancelLogin() {
        loginJob?.cancel()
        webDavManager.cancelActiveCalls()
        loginJob = null
        isLoading = false
        connectionStatus = "Đã dừng đăng nhập"
    }

    fun connect(urlList: List<String>, user: String, pass: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            var lastErrorDetail = "Không rõ"

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
                if (urlList.isEmpty()) {
                    lastErrorDetail = "Không có URL để kết nối"
                    return@withContext false
                }

                val channel = kotlinx.coroutines.channels.Channel<Pair<Boolean, String>>()
                val jobs = urlList.map { activeUrl ->
                    launch(Dispatchers.IO) {
                        if (activeUrl.isBlank()) {
                            channel.send(Pair(false, ""))
                            return@launch
                        }

                        val safeUrl = if (activeUrl.isNotEmpty() && !activeUrl.endsWith("/")) "$activeUrl/" else activeUrl

                        withContext(Dispatchers.Main) {
                            connectionStatus = "Đang kết nối: $safeUrl"
                        }

                        try {
                            val timeoutMs = adaptiveTimeoutMs(safeUrl)
                            val pingClient = NasApplication.instance.sharedHttpClient.newBuilder()
                                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .build()

                            val request = okhttp3.Request.Builder()
                                .url("${safeUrl.toApiBaseUrl()}/api/ping")
                                .head()
                                .header("Authorization", okhttp3.Credentials.basic(user, pass))
                                .build()

                            val t0 = android.os.SystemClock.elapsedRealtime()
                            pingClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    recordLatency(safeUrl, android.os.SystemClock.elapsedRealtime() - t0)
                                    channel.send(Pair(true, safeUrl))
                                } else {
                                    channel.send(Pair(false, "$activeUrl: WebDAV từ chối xác thực (HTTP ${response.code})"))
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("NAS_AUTH", "Lỗi kết nối $activeUrl: ${e.message}")
                            channel.send(Pair(false, "$activeUrl: ${e.message ?: "Mạng quá hạn"}"))
                        }
                    }
                }

                var successUrl = ""
                var failCount = 0
                val totalJobs = urlList.size

                while (failCount < totalJobs) {
                    val res = channel.receive()
                    if (res.first) {
                        successUrl = res.second
                        break
                    } else {
                        if (res.second.isNotEmpty()) {
                            errorDetails.add(res.second)
                        }
                        failCount++
                    }
                }

                jobs.forEach { it.cancel() }
                channel.close()

                if (successUrl.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        currentUrl = successUrl
                        connectionStatus = "Đã kết nối: $successUrl"
                        isOnLan = !isTailscaleUrl(successUrl)
                    }

                    webDavManager.connect(successUrl, user, pass)
                    SecurePrefsHelper.saveCredentials(NasApplication.instance, urlList, user, pass)
                    repository.addSystemLog("SUCCESS", "Network", "Truy cập WebDAV thành công qua User '$user' tại IP: $successUrl")

                    connectedUrl = successUrl

                    val parsedUrl = try { java.net.URL(successUrl) } catch (_: Exception) { null }
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
                                    .url("${successUrl.toApiBaseUrl()}/api/auth/authorize")
                                    .header("Authorization", authHeader)
                                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                                    .build()
                                cleanClient.newCall(request).execute().use { }
                            } catch (e: Exception) {
                                android.util.Log.w("NAS_AUTH", "API Phụ Warning: ${e.message}")
                            }
                        }
                    }
                    true
                } else {
                    lastErrorDetail = buildLoginFailureMessage(urlList, errorDetails)
                    false
                }
            }

            withContext(Dispatchers.Main) {
                isLoading = false
                if (result2) {
                    connectionStatus = "Đã xác thực thành công"
                    onSuccess()
                } else {
                    connectionStatus = "Lỗi xác thực"
                    onError(lastErrorDetail)
                }
            }

            if (result2) { refresh() }
            loginJob = null
        }
    }

    suspend fun pingUrlsForDisplay(urlList: List<String>, user: String, pass: String): Map<String, Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        coroutineScope {
            urlList.distinct().map { url ->
                async(Dispatchers.IO) {
                    url to try {
                        val safeUrl = if (url.endsWith("/")) url else "$url/"
                        val timeoutMs = adaptiveTimeoutMs(safeUrl).toInt()
                        val uri = java.net.URI(safeUrl)
                        val host = uri.host ?: return@async url to -1L
                        val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80

                        var best = Long.MAX_VALUE
                        repeat(if (isTailscaleUrl(url)) 1 else 3) {
                            val start = android.os.SystemClock.elapsedRealtime()
                            try {
                                val socket = java.net.Socket()
                                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                                socket.close()
                                best = minOf(best, android.os.SystemClock.elapsedRealtime() - start)
                            } catch (e: Exception) {
                                // Ignore individual failures
                            }
                        }
                        if (best == Long.MAX_VALUE) -1L else {
                            recordLatency(url, best)
                            best
                        }
                    } catch (_: Exception) {
                        -1L
                    }
                }
            }.associate { it.await() }
        }
    }

    init {
        // Cập nhật trạng thái Auto Backup từ WorkManager
        viewModelScope.launch {
            try {
                androidx.work.WorkManager.getInstance(NasApplication.instance.applicationContext)
                    .getWorkInfosByTagFlow("com.nas.naswebdav.AutoBackupWorker").collect { workInfos ->
                        val workInfo = workInfos.find {
                            it.state == androidx.work.WorkInfo.State.RUNNING
                        } ?: workInfos.find {
                            it.state == androidx.work.WorkInfo.State.ENQUEUED &&
                                it.tags.contains("MANUAL_AUTO_BACKUP")
                        }
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
    
        startDashboardMonitoring(resetStatusPoll = false)

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
                arrayOf<Any?>(stage, folderUrl, percent, scanned, elapsed)
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

                // FIX (BUG: dialog tự pop-up lại khi user bấm Thu nhỏ):
                // Chỉ cập nhật isWorkerRunning — KHÔNG tự động set isScanningDuplicates = true.
                // Dialog hiển thị do user chủ động mở (qua nút Quét hoặc chip "Thu nhỏ").
                // Trước đây, mỗi tick progress collector đặt isScanningDuplicates=true ->
                // user không thể Thu nhỏ/Hủy/Tạm dừng được vì dialog tự bật lại 30ms sau.
                val stage = scanDuplicatesStage
                if (stage != "Hoàn tất" && stage.isNotEmpty() && stage != "Khởi động...") {
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
                    val intervalMs = if (AppConfig.IS_APP_FOREGROUND) 3000L else 30_000L
                    kotlinx.coroutines.delay(intervalMs)
                } else {
                    // Nếu chưa Login xong thì đợi 1s hỏi lại, tránh việc bắt User đợi tận 30s mới chọc Ping
                    kotlinx.coroutines.delay(1000)
                }
            }
        }

        // Dashboard monitoring is started once above; later foreground/resume events call
        // startDashboardMonitoring(), whose guard keeps the single polling job alive.
    }

    // Dọn các listener (nếu có)

    // =======================================================
    // ======== BIỂU ĐỒ GIÁM SÁT + BÁO CÁO NGÀY ============
    // =======================================================

    fun startDashboardMonitoring(resetStatusPoll: Boolean = false) {
        android.util.Log.d("DashboardMonitor", "startDashboardMonitoring resetStatusPoll=$resetStatusPoll statusActive=${statusJob?.isActive}")
        if (!resetStatusPoll && statusJob?.isActive == true) return
        listenToLocalNasApi(forceRestart = resetStatusPoll)
    }

    fun launchMetricsPolling() {
        listenToLocalNasApi(forceRestart = false)
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
                        lastMetricsRefreshAt = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { metricsError = "Nhấn Làm mới để thử lại: ${e.message?.take(80)}" }
            } finally {
                withContext(Dispatchers.Main) { isLoadingMetrics = false }
            }
        }
    }

    fun fetchRealtimeMetricPoint() {
        val now = System.currentTimeMillis()
        if (now < realtimeMetricNextAllowedAt.get()) return
        if (!realtimeMetricInFlight.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = webDavManager.currentBaseUrl
            if (baseUrl.isEmpty()) {
                realtimeMetricInFlight.set(false)
                return@launch
            }
            try {
                val request = okhttp3.Request.Builder()
                    .url("${baseUrl.toApiBaseUrl()}/api/status/realtime")
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val json = org.json.JSONObject(raw)
                    val snap = MetricsSnapshot(
                        timestamp = json.optString("timestamp", ""),
                        cpuPercent = json.optDouble("cpu_percent", 0.0).toFloat(),
                        ramPercent = json.optDouble("ram_percent", 0.0).toFloat(),
                        cpuTemp = json.optDouble("cpu_temp", 0.0).toFloat(),
                        hddTemp = json.optDouble("hdd_temp", 0.0).toFloat(),
                        netRxKbps = json.optDouble("net_rx_kbps", 0.0).toFloat(),
                        netTxKbps = json.optDouble("net_tx_kbps", 0.0).toFloat()
                    )
                    withContext(Dispatchers.Main) {
                        if (snap.timestamp.isNotBlank() && metricsHistory.lastOrNull()?.timestamp != snap.timestamp) {
                            metricsHistory.add(snap)
                            val maxPoints = (metricsHours.coerceAtLeast(1) * 3600 / 5).coerceAtLeast(720)
                            while (metricsHistory.size > maxPoints) metricsHistory.removeAt(0)
                        }
                        if (snap.cpuTemp > 0f || snap.hddTemp > 0f) {
                            temperatureHistory.add(Pair(snap.cpuTemp, snap.hddTemp))

                            while (temperatureHistory.size > 40) temperatureHistory.removeAt(0)

                        }
                        metricsError = null
                        lastMetricsRefreshAt = System.currentTimeMillis()
                    }
                    realtimeMetricBackoffMs.set(5_000L)
                    realtimeMetricNextAllowedAt.set(0L)
                }
            } catch (e: Exception) {
                android.util.Log.w("MetricsRealtime", "Realtime metric failed: ${e.message}")
                val backoffMs = realtimeMetricBackoffMs.get()
                realtimeMetricNextAllowedAt.set(System.currentTimeMillis() + backoffMs)
                realtimeMetricBackoffMs.set((backoffMs * 2).coerceAtMost(60_000L))
            } finally {
                realtimeMetricInFlight.set(false)
            }
        }
    }

    private var lastDailyReportFetchAt = 0L
    fun fetchDailyReport(date: String = "", minIntervalMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastDailyReportFetchAt < minIntervalMs) return
        lastDailyReportFetchAt = now
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
            finally {
                withContext(Dispatchers.Main) { isDailyReportLoading = false }
            }
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
                            val urls = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }
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
                val json = org.json.JSONObject().put("username", pass.username)
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/guest/revoke")
                    .post(body)
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            activeGuestPass = null
                            guestPassError = null
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                            commonDialogMessage = "Da thu hoi Guest Pass cua '${pass.username}' thanh cong!"
                            repository.addSystemLog("INFO", "GuestPass", "Da thu hoi Guest FTP user '${pass.username}'")
                        } else {
                            guestPassError = "Thu hoi that bai: HTTP ${resp.code}"
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING
                            commonDialogMessage = "Thu hoi that bai (HTTP ${resp.code}). Pass duoc giu lai de thu lai."
                        }
                        showCommonDialog = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guestPassError = "Loi thu hoi: ${e.message}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING
                    commonDialogMessage = "Loi mang khi thu hoi Guest Pass. Pass duoc giu lai de thu lai."
                    showCommonDialog = true
                }
            } finally {
                withContext(Dispatchers.Main) { isGuestPassLoading = false }
            }
        }
    }

    // ============ SOCIAL EXTRACTOR (yt-dlp qua NAS API) ============
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
                        // FIX: capped O(1) prepend thay vi concat O(n). Cap 50 item de tranh growth vo tan.
                        socialDownloadHistory = (listOf(histItem) + socialDownloadHistory).take(50)
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
                    socialDownloadHistory = (listOf(SocialDownloadItem(url, "Không rõ", false)) + socialDownloadHistory).take(50)
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
            val statusUrl = "http://$host:${com.nas.naswebdav.AppConfig.API_PORT}/api/ytdlp/status"
            var isFinished = false
            var consecutiveErrors = 0
            while (!isFinished && consecutiveErrors < 12) { // ~1 phut error -> tu dong huy
                delay(5000L) // Poll every 5 seconds
                try {
                    val requestBuilder = okhttp3.Request.Builder().url(statusUrl)
                    val user = com.nas.naswebdav.SecurePrefsHelper.getUser(NasApplication.instance)
                    val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(NasApplication.instance)
                    if (user.isNotEmpty() && pass.isNotEmpty()) {
                        requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                    }
                    // FIX: dung .use {} de close response (truoc day leak connection moi 5s)
                    val bodyStr = localApiClient.newCall(requestBuilder.build()).execute().use { resp ->
                        resp.body?.string() ?: "{}"
                    }
                    consecutiveErrors = 0
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
                            commonDialogMessage = "✅ Tải video ($platform) hoàn tất!\nĐã tải xong và lưu vào thư mục $saveFolder"
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                            showCommonDialog = true
                        }
                        repository.addSystemLog("SUCCESS", "SocialDownload", "Tải video $platform hoàn tất. Lưu tại: $saveFolder ($url)")
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
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
            else -> "Khác"
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
        streamPipeStatus = "⏳ Đang truyền video qua tác vụ nền..."

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
                            socialDownloadHistory = (listOf(SocialDownloadItem(sourceUrl, platform, true)) + socialDownloadHistory).take(50)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            isStreamPiping = false
                            streamPipeStatus = "❌ ${message.ifEmpty { "Lỗi truyền video" }}"
                        } else if (workInfo.state == androidx.work.WorkInfo.State.CANCELLED) {
                            isStreamPiping = false
                            streamPipeStatus = "🛑 Đã hủy bởi người dùng"
                            streamPipeProgress = 0f
                        }
                        if (workInfo.state.isFinished) {
                            throw CancellationException("StreamPipe WorkInfo collector finished")
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

                            apiResponse.body?.byteStream()?.use { input ->
                                java.io.FileOutputStream(thumbFile).use { out -> input.copyTo(out) }
                            } ?: return@withContext false
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
    // BO QUÉT RÁC khoi flow nay theo yeu cau user — Sync Anh chi nen chay AutoBackup
    // (upload anh moi). Quet trung lap la tac vu nang ca cho phone va NAS, chi chay
    // tu dong theo lich tuan tai 3h sang khi NAS ranh, hoac do user chu dong khoi.
    fun triggerManualBackup(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        isAutoBackupRunning = true
        autoBackupProgress = 0f
        autoBackupCurrentFile = "Đang xếp hàng đồng bộ..."
        autoBackupSourcePath = "Thiết bị máy trạm"
        autoBackupDestPath = ""
        autoBackupProcessedCount = 0
        autoBackupTotalCount = 0
        autoBackupElapsedTime = 0L

        // Kích hoạt AutoBackup ngay lập tức (upload anh dien thoai len NAS)
        val backupRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.AutoBackupWorker>()
            .addTag("com.nas.naswebdav.AutoBackupWorker")
            .addTag("MANUAL_AUTO_BACKUP")
            .build()
        workManager.enqueueUniqueWork("ManualAutoBackupWork", androidx.work.ExistingWorkPolicy.REPLACE, backupRequest)
        logUserAction("AutoBackup", "chạy đồng bộ ảnh thủ công lên NAS.")
        // Cập nhật Toast hoặc Trạng thái UI để User biết
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "Đã ra lệnh đồng bộ ảnh lên NAS!"
        showCommonDialog = true
    }

    fun toggleAutoBackupPause() {
        val newState = !AutoBackupState.isPaused.value
        AutoBackupState.isPaused.value = newState
        autoBackupIsPaused = newState
        logUserAction("AutoBackup", if (newState) "tam dung Auto-Backup." else "Tiếp tục Đồng bộ tự động.")
    }

    // ==========================================
    // THIẾT LẬP HOẠT ĐỘNG QUẠT (FAN CONTROL)
    // ==========================================
    fun setFanMode(mode: String, onTemp: Float? = null, offTemp: Float? = null) {
        if (isFanModeUpdating) return
        var optimisticStatus = systemStatus.copy(
            fanMode = mode,
            fanStatus = when (mode) {
                "off" -> "Dung"
                "on" -> "Dang chay 100%"
                else -> systemStatus.fanStatus
            }
        )
        if (mode == "custom" && onTemp != null && offTemp != null) {
            optimisticStatus = optimisticStatus.copy(fanOnTemp = onTemp, fanOffTemp = offTemp)
        }
        val oldStatus = systemStatus
        systemStatus = optimisticStatus
        isFanModeUpdating = true
        var requestSucceeded = false
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
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                NasApplication.instance.sharedHttpClient.newCall(request).execute().use { response ->
                    val tempPart = if (mode == "custom" && onTemp != null && offTemp != null) " (${onTemp.toInt()}C/${offTemp.toInt()}C)" else ""
                    val resultPart = if (response.isSuccessful) "thanh cong" else "that bai HTTP ${response.code}"
                    repository.addSystemLog(
                        if (response.isSuccessful) "INFO" else "WARNING",
                        "Fan",
                        "Nguoi dung: dat che do quat '$mode'$tempPart $resultPart."
                    )
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) { systemStatus = oldStatus }
                        android.util.Log.e("NasAPI", "Khong dat duoc che do quat: HTTP " + response.code)
                        return@use
                    }
                    requestSucceeded = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { systemStatus = oldStatus }
                repository.addSystemLog("WARNING", "Fan", "Nguoi dung: dat che do quat '$mode' that bai: ${e.message?.take(120) ?: ""}")
                android.util.Log.e("NasAPI", "Khong dat duoc che do quat: " + (e.message ?: ""))
            } finally {
                withContext(Dispatchers.Main) {
                    isFanModeUpdating = false
                    if (requestSucceeded) {
                        lastFanModeSettingTime = System.currentTimeMillis()
                    } else {
                        systemStatus = oldStatus
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        metricsPollingJob?.cancel()
        dashboardRealtimeJob?.cancel()
        statusJob?.cancel()
        foregroundRefreshJob?.cancel()
        streamPipeJob?.cancel()
        livestreamObserverJob?.cancel()
        try { webSocket?.close(1000, "ViewModel cleared") } catch (_: Exception) {}
    }

    // ============== NAS CONFIG BACKUP / RESTORE ==============
    fun fetchNasConfigBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val request = okhttp3.Request.Builder()
                    .url("$base/api/backup/list")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@use
                    val arr = org.json.JSONObject(body).optJSONArray("backups") ?: return@use
                    val list = mutableListOf<NasConfigBackup>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(NasConfigBackup(
                            filename = o.optString("filename"),
                            sizeBytes = o.optLong("size", 0L),
                            sizeHuman = o.optString("size_human", ""),
                            createdAt = o.optString("created_at", ""),
                            mtime = o.optDouble("mtime", 0.0),
                        ))
                    }
                    withContext(Dispatchers.Main) { nasConfigBackups = list }
                }
            } catch (e: Exception) {
                android.util.Log.w("NasBackup", "list err: ${e.message}")
            }
        }
    }

    fun createNasConfigBackup() {
        if (isCreatingNasConfigBackup) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isCreatingNasConfigBackup = true
                nasConfigBackupMessage = "Đang tạo backup..."
            }
            try {
                val base = currentUrl.toApiBaseUrl()
                // Empty JSON body so server accepts POST
                val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/backup/create")
                    .post(body)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                // dung client co read timeout dai (60s) vi tar.gz nhieu file co the cham
                val client = localApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(text)
                    val msg = if (resp.isSuccessful) {
                        "Đã tạo: ${json.optString("filename")} (${json.optString("size_human")})"
                    } else {
                        "Lỗi tạo backup: ${json.optString("error", "HTTP ${resp.code}")}"
                    }
                    repository.addSystemLog(if (resp.isSuccessful) "SUCCESS" else "WARNING", "NasBackup", "Người dùng: tạo backup cấu hình NAS ${if (resp.isSuccessful) "thành công ${json.optString("filename")}" else "thất bại HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) { nasConfigBackupMessage = msg }
                }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "NasBackup", "Người dùng: tạo backup cấu hình NAS thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isCreatingNasConfigBackup = false }
            }
        }
    }

    fun deleteNasConfigBackup(filename: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("filename", filename).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/backup/delete")
                    .post(body)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "NasBackup", "Người dùng: xoá backup cấu hình '$filename' ${if (resp.isSuccessful) "thành công" else "thất bại HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) {
                        nasConfigBackupMessage = if (resp.isSuccessful) "Đã xoá $filename"
                        else "Lỗi xoá: ${org.json.JSONObject(text).optString("error","HTTP ${resp.code}")}"
                    }
                }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "NasBackup", "Người dùng: xoá backup '$filename' thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lỗi xoá: ${e.message}" }
            }
        }
    }

    fun restoreNasConfigBackup(filename: String) {
        if (isRestoringNasConfigBackup) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isRestoringNasConfigBackup = true
                nasConfigBackupMessage = "Đang khôi phục..."
            }
            try {
                val base = currentUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("filename", filename).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/backup/restore")
                    .post(body)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                // Restore goi systemctl restart nen co the mat 10-20s
                val client = localApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    val json = try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
                    val msg = if (resp.isSuccessful) {
                        "Đã khôi phục ${json.optInt("restored_count")} file. Services restart: " +
                            (json.optJSONArray("services_restarted")?.toString() ?: "(none)")
                    } else {
                        "Lỗi khôi phục: ${json.optString("error", "HTTP ${resp.code}")}"
                    }
                    repository.addSystemLog(if (resp.isSuccessful) "WARNING" else "ERROR", "NasBackup", "Người dùng: khôi phục cấu hình từ '$filename' ${if (resp.isSuccessful) "thành công" else "thất bại HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) { nasConfigBackupMessage = msg }
                }
            } catch (e: Exception) {
                repository.addSystemLog("ERROR", "NasBackup", "Người dùng: khôi phục cấu hình từ '$filename' thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lỗi khôi phục: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isRestoringNasConfigBackup = false }
            }
        }
    }

    // ============== DISK HEALTH ==============

    private fun jsonToDiskHealth(o: org.json.JSONObject): DiskHealthSample {
        val warnArr = o.optJSONArray("warnings")
        val warnList = mutableListOf<String>()
        if (warnArr != null) {
            for (i in 0 until warnArr.length()) warnList.add(warnArr.optString(i))
        }
        fun nullableInt(key: String): Int? = if (o.isNull(key)) null else o.optInt(key)
        return DiskHealthSample(
            ts = o.optLong("ts"),
            datetime = o.optString("datetime"),
            score = o.optInt("score", 0),
            smartStatus = o.optString("smart_status", "Unknown"),
            tempC = nullableInt("temp_c"),
            powerOnHours = nullableInt("power_on_hours"),
            reallocatedSectors = nullableInt("reallocated_sectors"),
            pendingSectors = nullableInt("pending_sectors"),
            offlineUncorrectable = nullableInt("offline_uncorrectable"),
            udmaCrcErr = nullableInt("udma_crc_err"),
            commandTimeout = nullableInt("command_timeout"),
            ext4ErrorsRecent = o.optInt("ext4_errors_recent", 0),
            sataResetsRecent = o.optInt("sata_resets_recent", 0),
            ioErrorsRecent = o.optInt("io_errors_recent", 0),
            warnings = warnList,
        )
    }

    fun fetchDiskHealth(minIntervalMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastDiskHealthRefreshAt < minIntervalMs) return
        if (isFetchingDiskHealth) return
        lastDiskHealthRefreshAt = now
        isFetchingDiskHealth = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/disk/health")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@use
                    val current = org.json.JSONObject(body).optJSONObject("current") ?: return@use
                    val sample = jsonToDiskHealth(current)
                    withContext(Dispatchers.Main) {
                        diskHealthCurrent = sample
                        lastSmartRefreshAt = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DiskHealth", "fetch err: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingDiskHealth = false }
            }
        }
    }

    fun fetchDiskHealthHistory(days: Int = 7) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/disk/health/history?days=$days")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@use
                    val arr = org.json.JSONObject(body).optJSONArray("samples") ?: return@use
                    val list = mutableListOf<DiskHealthSample>()
                    for (i in 0 until arr.length()) {
                        list.add(jsonToDiskHealth(arr.getJSONObject(i)))
                    }
                    withContext(Dispatchers.Main) { diskHealthHistory = list }
                }
            } catch (e: Exception) {
                android.util.Log.w("DiskHealth", "history err: ${e.message}")
            }
        }
    }

    private fun jsonStringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    fun fetchNasInsights(minIntervalMs: Long = 10_000L) {
        if (isFetchingNasInsights) return
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastNasInsightsFetchAt < minIntervalMs) return
        lastNasInsightsFetchAt = now
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isFetchingNasInsights = true }
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/system/insights")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@use
                    val root = org.json.JSONObject(body)
                    val health = root.optJSONObject("health_trend") ?: org.json.JSONObject()
                    val workload = root.optJSONObject("workload") ?: org.json.JSONObject()
                    val emmc = root.optJSONObject("emmc_guard") ?: org.json.JSONObject()
                    val dataFlow = root.optJSONObject("data_flow") ?: org.json.JSONObject()
                    val maintenance = root.optJSONObject("maintenance") ?: org.json.JSONObject()
                    val usb = root.optJSONObject("usb_import") ?: org.json.JSONObject()
                    val rootUsage = emmc.optJSONObject("root") ?: org.json.JSONObject()
                    val logUsage = emmc.optJSONObject("log") ?: org.json.JSONObject()
                    val tasksArr = dataFlow.optJSONArray("current_tasks")
                    val tasks = mutableListOf<InsightFlowTask>()
                    if (tasksArr != null) {
                        for (i in 0 until tasksArr.length()) {
                            val o = tasksArr.optJSONObject(i) ?: continue
                            tasks.add(
                                InsightFlowTask(
                                    type = o.optString("type"),
                                    label = o.optString("label"),
                                    file = o.optString("file"),
                                    source = o.optString("source"),
                                    dest = o.optString("dest"),
                                    speedBps = o.optLong("speed_bps", 0L),
                                    progress = o.optInt("progress", 0)
                                )
                            )
                        }
                    }
                    val actionsArr = maintenance.optJSONArray("actions")
                    val actions = mutableListOf<InsightAction>()
                    if (actionsArr != null) {
                        for (i in 0 until actionsArr.length()) {
                            val o = actionsArr.optJSONObject(i) ?: continue
                            actions.add(InsightAction(o.optString("priority"), o.optString("title"), o.optString("detail")))
                        }
                    }
                    val insights = NasInsights(
                        hddScore = health.optInt("score", 0),
                        hddStatusText = health.optString("status_text", ""),
                        hddTempC = health.optInt("temp_c", 0),
                        hddMinScore = health.optInt("min_score", 0),
                        hddScoreDelta = health.optInt("score_delta", 0),
                        workloadMode = workload.optString("mode", "normal"),
                        workloadPressure = workload.optInt("pressure", 0),
                        workloadRecommendation = workload.optString("recommendation", ""),
                        workloadReasons = jsonStringList(workload.optJSONArray("reasons")),
                        emmcRootPercent = rootUsage.optInt("percent", 0),
                        emmcLogPercent = logUsage.optInt("percent", 0),
                        emmcWarnings = jsonStringList(emmc.optJSONArray("warnings")),
                        emmcRecommendations = jsonStringList(emmc.optJSONArray("recommendations")),
                        diskReadBps = dataFlow.optLong("disk_read_bps", 0L),
                        diskWriteBps = dataFlow.optLong("disk_write_bps", 0L),
                        netRxBps = dataFlow.optLong("net_rx_bps", 0L),
                        netTxBps = dataFlow.optLong("net_tx_bps", 0L),
                        flowTasks = tasks,
                        maintenanceActions = actions,
                        usbHistoryCount = usb.optJSONArray("history")?.length() ?: 0,
                        updatedAt = System.currentTimeMillis()
                    )
                    val insightDiskHealth = DiskHealthSample(
                        ts = System.currentTimeMillis() / 1000L,
                        datetime = "",
                        score = health.optInt("score", 0),
                        smartStatus = health.optString("smart_status", "Unknown"),
                        tempC = if (health.isNull("temp_c")) null else health.optInt("temp_c"),
                        powerOnHours = if (health.isNull("power_on_hours")) null else health.optInt("power_on_hours"),
                        reallocatedSectors = health.optJSONObject("watch_fields")?.optInt("reallocated_sectors"),
                        pendingSectors = health.optJSONObject("watch_fields")?.optInt("pending_sectors"),
                        offlineUncorrectable = health.optJSONObject("watch_fields")?.optInt("offline_uncorrectable"),
                        udmaCrcErr = health.optJSONObject("watch_fields")?.optInt("udma_crc_err"),
                        commandTimeout = health.optJSONObject("watch_fields")?.optInt("command_timeout"),
                        ext4ErrorsRecent = diskHealthCurrent?.ext4ErrorsRecent ?: 0,
                        sataResetsRecent = diskHealthCurrent?.sataResetsRecent ?: 0,
                        ioErrorsRecent = diskHealthCurrent?.ioErrorsRecent ?: 0,
                        warnings = jsonStringList(health.optJSONArray("warnings"))
                    )
                    withContext(Dispatchers.Main) {
                        nasInsights = insights
                        if (insightDiskHealth.score > 0 || insightDiskHealth.tempC != null) {
                            diskHealthCurrent = insightDiskHealth
                            lastSmartRefreshAt = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NasInsights", "fetch err: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingNasInsights = false }
            }
        }
    }

    fun launchDashboardRealtimeScheduler() {
        listenToLocalNasApi(forceRestart = false)
    }

    fun fetchStorageUsage(minIntervalMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastStorageRefreshAt < minIntervalMs) return
        if (isFetchingStorageUsage) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isFetchingStorageUsage = true }
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/storage/usage")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@use
                    val arr = org.json.JSONObject(body).optJSONArray("folders") ?: return@use
                    val list = mutableListOf<StorageFolderUsage>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            StorageFolderUsage(
                                name = o.optString("name"),
                                path = o.optString("path"),
                                size = o.optString("size", "0 B"),
                                sizeBytes = o.optLong("size_bytes", 0L),
                                files = o.optInt("files", 0),
                                partial = o.optBoolean("partial", false)
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        storageFolderUsage = list
                        lastStorageRefreshAt = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StorageUsage", "fetch err: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingStorageUsage = false }
            }
        }
    }

    // ============== BACKUP SCHEDULE ==============
    fun fetchBackupSchedule() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/backup/schedule")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@use
                    val o = org.json.JSONObject(body)
                    val s = BackupSchedule(
                        enabled = o.optBoolean("enabled"),
                        frequency = o.optString("frequency", "weekly"),
                        hour = o.optInt("hour", 3),
                        retentionCount = o.optInt("retention_count", 7),
                        rcloneRemote = o.optString("rclone_remote", ""),
                        rclonePath = o.optString("rclone_path", "/NASBackup/"),
                        lastRunTs = o.optLong("last_run_ts", 0L),
                        lastRunResult = o.optString("last_run_result", ""),
                        lastRunFile = o.optString("last_run_file", ""),
                    )
                    withContext(Dispatchers.Main) { backupSchedule = s }
                }
            } catch (e: Exception) {
                android.util.Log.w("BackupSchedule", "fetch err: ${e.message}")
            }
        }
    }

    fun saveBackupSchedule(newSchedule: BackupSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val jsonBody = org.json.JSONObject().apply {
                    put("enabled", newSchedule.enabled)
                    put("frequency", newSchedule.frequency)
                    put("hour", newSchedule.hour)
                    put("retention_count", newSchedule.retentionCount)
                    put("rclone_remote", newSchedule.rcloneRemote)
                    put("rclone_path", newSchedule.rclonePath)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/backup/schedule")
                    .post(jsonBody)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val ok = resp.isSuccessful && org.json.JSONObject(body).optBoolean("saved", false)
                    repository.addSystemLog(
                        if (ok) "INFO" else "WARNING",
                        "BackupSchedule",
                        "Người dùng: ${if (ok) "lưu" else "lưu thất bại"} lịch backup (${if (newSchedule.enabled) "bật" else "tắt"}, ${newSchedule.frequency}, ${newSchedule.hour}h, giữ ${newSchedule.retentionCount} bản)."
                    )
                    withContext(Dispatchers.Main) {
                        backupScheduleMessage = if (ok) "Đã lưu lịch backup" else "Lỗi lưu"
                    }
                }
                fetchBackupSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "BackupSchedule", "Người dùng: lưu lịch backup thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { backupScheduleMessage = "Lỗi: ${e.message}" }
            }
        }
    }

    // ============== USB IMPORT ==============
    private fun parseUsbImportState(o: org.json.JSONObject): UsbImportState {
        val settingsJson = o.optJSONObject("settings") ?: org.json.JSONObject()
        val settings = UsbImportSettings(
            enabled = settingsJson.optBoolean("enabled", o.optBoolean("enabled", true)),
            destFolder = settingsJson.optString("dest_folder", "USB Import"),
            copyMode = settingsJson.optString("copy_mode", "new_only"),
            autoMount = settingsJson.optBoolean("auto_mount", true),
            mountReadonly = settingsJson.optBoolean("mount_readonly", true),
            pollSeconds = settingsJson.optInt("poll_seconds", 15),
            resumeEnabled = settingsJson.optBoolean("resume_enabled", true),
            verifyChecksum = settingsJson.optBoolean("verify_checksum", false),
        )
        return UsbImportState(
            enabled = o.optBoolean("enabled", settings.enabled),
            status = o.optString("status", "idle"),
            message = o.optString("message", ""),
            activeDevice = o.optString("active_device", ""),
            activeMount = o.optString("active_mount", ""),
            destDir = o.optString("dest_dir", ""),
            startedAt = o.optLong("started_at", 0L),
            finishedAt = o.optLong("finished_at", 0L),
            filesTotal = o.optInt("files_total", 0),
            filesDone = o.optInt("files_done", 0),
            filesSkipped = o.optInt("files_skipped", 0),
            filesFailed = o.optInt("files_failed", 0),
            bytesDone = o.optLong("bytes_done", 0L),
            bytesProcessed = o.optLong("bytes_processed", o.optLong("bytes_done", 0L)),
            bytesTotal = o.optLong("bytes_total", 0L),
            currentFile = o.optString("current_file", ""),
            currentSource = o.optString("current_source", ""),
            currentDest = o.optString("current_dest", ""),
            currentFileBytesDone = o.optLong("current_file_bytes_done", 0L),
            currentFileBytesTotal = o.optLong("current_file_bytes_total", 0L),
            copySpeedBps = o.optLong("copy_speed_bps", 0L),
            etaSeconds = o.optLong("eta_seconds", 0L),
            lastProgressAt = o.optLong("last_progress_at", 0L),
            lastError = o.optString("last_error", ""),
            settings = settings,
            detectedDevicesInfo = parseDetectedDevicesInfo(o.optJSONArray("detected_devices")),
            needsAction = o.optBoolean("needs_action", false),
            pendingConflictsCount = o.optInt("pending_conflicts_count", 0),
            pendingErrorsCount = o.optInt("pending_errors_count", 0),
            pendingConflicts = parseUsbImportConflicts(o.optJSONArray("pending_conflicts")),
        )
    }

    private fun parseUsbImportConflicts(arr: org.json.JSONArray?): List<UsbImportConflict> {
        if (arr == null) return emptyList()
        val out = mutableListOf<UsbImportConflict>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.add(
                UsbImportConflict(
                    rel = item.optString("rel", ""),
                    sourceName = item.optString("source_name", ""),
                    destName = item.optString("dest_name", ""),
                    sourceSize = item.optLong("source_size", 0L),
                    destSize = item.optLong("dest_size", 0L),
                )
            )
        }
        return out
    }

    private fun parseDetectedDevicesInfo(arr: org.json.JSONArray?): String {
        if (arr == null) return ""
        val devices = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val dev = arr.optJSONObject(i) ?: continue
            val reason = dev.optString("reason", "")
            if (reason == "hop le" || reason.startsWith("hop le")) {
                val label = dev.optString("label", "")
                val path = dev.optString("path", "")
                val name = label.ifBlank { path }.ifBlank { "USB Không tên" }
                devices.add(name)
            }
        }
        return devices.joinToString(", ")
    }

    internal suspend fun fetchUsbImportStatusSuspend(compact: Boolean = false, minIntervalMs: Long = 0L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastUsbImportStatusFetchAt < minIntervalMs) return
        if (!usbImportStatusInFlight.compareAndSet(false, true)) return
        lastUsbImportStatusFetchAt = now
        withContext(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { if (!compact) isUsbImportLoading = true }
                val base = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val path = if (compact) "/api/usb_import/status?compact=1" else "/api/usb_import/status"
                val req = okhttp3.Request.Builder()
                    .url("$base$path")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lỗi tải USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val state = parseUsbImportState(org.json.JSONObject(body))
                    withContext(Dispatchers.Main) {
                        usbImportState = state
                        usbImportMessage = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
            } finally {
                usbImportStatusInFlight.set(false)
                withContext(Dispatchers.Main) { if (!compact) isUsbImportLoading = false }
            }
        }
    }

    fun fetchUsbImportStatus(compact: Boolean = false, minIntervalMs: Long = 0L) {
        viewModelScope.launch { fetchUsbImportStatusSuspend(compact = compact, minIntervalMs = minIntervalMs) }
    }

    fun saveUsbImportSettings(settings: UsbImportSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val body = org.json.JSONObject().apply {
                    put("enabled", settings.enabled)
                    put("dest_folder", settings.destFolder)
                    put("copy_mode", settings.copyMode)
                    put("auto_mount", settings.autoMount)
                    put("mount_readonly", settings.mountReadonly)
                    put("poll_seconds", settings.pollSeconds)
                    put("resume_enabled", settings.resumeEnabled)
                    put("verify_checksum", settings.verifyChecksum)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/settings")
                    .post(body)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lỗi lưu USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val ok = resp.isSuccessful && o.optBoolean("saved", false)
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(
                        if (ok) "INFO" else "WARNING",
                        "USBImport",
                        "Người dùng: ${if (ok) "lưu" else "lưu thất bại"} cấu hình USB Import (${if (settings.enabled) "bật" else "tắt"}, ${settings.copyMode}, đích '${settings.destFolder}', readonly=${settings.mountReadonly})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = if (ok) "Đã lưu cấu hình USB Import" else "Lỗi lưu USB Import"
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "Người dùng: lưu cấu hình USB Import thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
            }
        }
    }

    fun startUsbImportNow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isUsbImportLoading = true }
                val base = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/start")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lỗi bắt đầu USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "USBImport", "Người dùng: yêu cầu copy USB ngay (${o.optString("message", "không có phản hồi")}).")
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = o.optString("message", if (resp.isSuccessful) "Đã bắt đầu copy USB" else "Không bắt đầu được")
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "Người dùng: yêu cầu copy USB ngay thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isUsbImportLoading = false }
            }
        }
    }

    fun cancelUsbImport() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isUsbImportLoading = true }
                val base = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/cancel")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lỗi huỷ USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "USBImport", "Người dùng: gửi lệnh hủy USB Import (${if (resp.isSuccessful) "đã gửi" else "thất bại"}).")
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = if (resp.isSuccessful) "Đã gửi lệnh hủy" else "Không hủy được"
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "Người dùng: hủy USB Import thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isUsbImportLoading = false }
            }
        }
    }

    fun resolveUsbImportConflicts(action: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isUsbImportLoading = true }
                val base = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val body = org.json.JSONObject().apply {
                    put("action", action)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/resolve_conflicts")
                    .post(body)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(
                        if (resp.isSuccessful) "INFO" else "WARNING",
                        "USBImport",
                        "Người dùng: xử lý file trùng USB Import bằng $action (${o.optString("message", "không có phản hồi")})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = o.optString("message", if (resp.isSuccessful) "Đã gửi lệnh xử lý file trùng" else "Không xử lý được file trùng")
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "Người dùng: xử lý file trùng USB Import thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isUsbImportLoading = false }
            }
        }
    }

    // ============== SLEEP SCHEDULE ==============
    fun fetchSleepSchedule() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/system/sleep_schedule")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@use
                    val o = org.json.JSONObject(body)
                    val s = SleepSchedule(
                        enabled = o.optBoolean("enabled"),
                        mode = o.optString("mode", "spindown"),
                        startHour = o.optInt("start_hour", 23),
                        endHour = o.optInt("end_hour", 7),
                        idleOnly = o.optBoolean("idle_only", true),
                        currentHddState = o.optString("current_hdd_state", "unknown"),
                        inWindowNow = o.optBoolean("in_window_now"),
                        lastActionTs = o.optLong("last_action_ts", 0L),
                        lastActionState = o.optString("last_action_state", ""),
                    )
                    withContext(Dispatchers.Main) { sleepSchedule = s }
                }
            } catch (e: Exception) {
                android.util.Log.w("SleepSchedule", "fetch err: ${e.message}")
            }
        }
    }

    fun saveSleepSchedule(newSchedule: SleepSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val jsonBody = org.json.JSONObject().apply {
                    put("enabled", newSchedule.enabled)
                    put("mode", newSchedule.mode)
                    put("start_hour", newSchedule.startHour)
                    put("end_hour", newSchedule.endHour)
                    put("idle_only", newSchedule.idleOnly)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/system/sleep_schedule")
                    .post(jsonBody)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val ok = resp.isSuccessful && org.json.JSONObject(body).optBoolean("saved", false)
                    repository.addSystemLog(
                        if (ok) "INFO" else "WARNING",
                        "SleepSchedule",
                        "Người dùng: ${if (ok) "lưu" else "lưu thất bại"} lịch ngủ NAS (${if (newSchedule.enabled) "bật" else "tắt"}, ${newSchedule.mode}, ${newSchedule.startHour}h-${newSchedule.endHour}h, idleOnly=${newSchedule.idleOnly})."
                    )
                    withContext(Dispatchers.Main) {
                        sleepScheduleMessage = if (ok) "Đã lưu lịch ngủ NAS" else "Lỗi lưu"
                    }
                }
                fetchSleepSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "SleepSchedule", "Người dùng: lưu lịch ngủ NAS thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { sleepScheduleMessage = "Lỗi: ${e.message}" }
            }
        }
    }

    fun spindownHddNow(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/system/hdd_spindown_now")
                    .post("".toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val o = org.json.JSONObject(body)
                    val ok = o.optBoolean("ok", false)
                    val msg = o.optString("msg", "")
                    repository.addSystemLog(if (ok) "INFO" else "WARNING", "SleepSchedule", "Người dùng: yêu cầu HDD spindown ngay (${if (ok) "thành công" else "thất bại"}: $msg).")
                    withContext(Dispatchers.Main) { onDone(ok, msg) }
                }
                fetchSleepSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "SleepSchedule", "Người dùng: yêu cầu HDD spindown ngay thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { onDone(false, e.message ?: "error") }
            }
        }
    }

    /** Download backup .tar.gz xuong cacheDir va tra ve File de share/save.
     *  Goi tu Dispatchers.IO. Tra null neu loi. */
    suspend fun downloadNasConfigBackup(context: Context, filename: String): java.io.File? {
        return withContext(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                val url = "$base/api/backup/download?filename=$encoded"
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                val client = localApiClient.newBuilder()
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val src = resp.body?.byteStream() ?: return@withContext null
                    val outDir = java.io.File(context.cacheDir, "nas_backups").apply { mkdirs() }
                    val safe = filename.replace("/", "_").replace("\\", "_")
                    val out = java.io.File(outDir, safe)
                    out.outputStream().use { o -> src.copyTo(o) }
                    out
                }
            } catch (e: Exception) {
                android.util.Log.w("NasBackup", "download err: ${e.message}")
                null
            }
        }
    }
} // end class WebDavViewModel

// LỚP PHỤ TRỢ: Bộ đếm Rate Limiter (2.C)
// FIX: dung ArrayDeque thay vi MutableList. removeAll{} cu phai duyet toan
// bo list moi lan goi (O(n)); thay bang pollFirst() khi gia tri dau qua han,
// chi cham vao timestamp con song -> O(k) voi k la so item bi prune.
class RateLimiter(private val maxRequestsPerMinute: Int) {
    private val requestTimestamps = ArrayDeque<Long>()

    @Synchronized
    fun isAllowed(): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L
        while (requestTimestamps.isNotEmpty() && requestTimestamps.first() < cutoff) {
            requestTimestamps.removeFirst()
        }

        if (requestTimestamps.size >= maxRequestsPerMinute) return false
        requestTimestamps.addLast(now)
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
                        errorMessage = "NAS đang gọn gàng. Không có tệp trùng lặp."
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
            val appContext = NasApplication.instance.applicationContext
            val trashMetaDao = NasApplication.instance.database.trashMetaDao()
            val isInTrash = file.path.contains(TRASH_FOLDER_NAME)
            val trashFolderUrl = buildWebDavTrashTargetUrl(
                webDavManager.currentBaseUrl,
                file.path,
                "",
                false
            )
            val trashTargetUrl = if (!isInTrash) buildWebDavTrashTargetUrl(
                webDavManager.currentBaseUrl,
                file.path,
                file.name,
                file.isDirectory
            ) else file.path

            try {
                withContext(Dispatchers.Main) { isLoading = true }
                if (isInTrash) {
                    webDavManager.deleteFile(file.path, file.isDirectory)
                    trashMetaDao.deleteByTrashPath(file.path)
                    repository.addSystemLog("WARNING", "DuplicateScan", "Đã xóa vĩnh viễn duplicate '${file.name}'.")
                } else {
                    try { webDavManager.createFolder(trashFolderUrl) } catch (_: Exception) {}
                    webDavManager.renameFile(file.path, trashTargetUrl)
                    trashMetaDao.insert(TrashMeta(trashPath = trashTargetUrl, originalPath = file.path))
                    repository.addSystemLog("WARNING", "DuplicateScan", "Đã chuyển duplicate '${file.name}' vào Thùng rác.")
                }

                repository.removeDuplicateFromDb(file.path)
                withContext(Dispatchers.Main) {
                    duplicateFilesList = duplicateFilesList.filter { it.path != file.path }
                    refresh()
                }
            } catch (e: Exception) {
                val message = friendlyError(e)
                if (e.isTransientNetworkFailure()) {
                    repository.addSystemLog("WARNING", "DuplicateScan", "Xóa duplicate '${file.name}' thất bại, đã được đưa vào hàng đợi ngoại tuyến: ${message.take(80)}")
                    if (isInTrash) {
                        enqueueOfflineAction(appContext, "DELETE", file.path)
                    } else {
                        enqueueOfflineAction(appContext, "RENAME", file.path, trashTargetUrl)
                    }
                    repository.removeDuplicateFromDb(file.path)
                    withContext(Dispatchers.Main) {
                        duplicateFilesList = duplicateFilesList.filter { it.path != file.path }
                        refresh()
                    }
                } else {
                    repository.addSystemLog("ERROR", "DuplicateScan", "Xóa duplicate '${file.name}' thất bại: ${message.take(120)}")
                    withContext(Dispatchers.Main) { errorMessage = "Xóa duplicate '${file.name}' thất bại: $message" }
                }
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

fun WebDavViewModel.deleteSelectedDuplicates() {

        val filesToDelete = selectedDuplicates.toList()
        if (filesToDelete.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val appContext = NasApplication.instance.applicationContext
            val trashMetaDao = NasApplication.instance.database.trashMetaDao()
            val completedPaths = linkedSetOf<String>()
            var hardError: String? = null
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                var processed = 0
                for (file in filesToDelete) {
                    val isInTrash = file.path.contains(TRASH_FOLDER_NAME)
                    val trashFolderUrl = buildWebDavTrashTargetUrl(
                        webDavManager.currentBaseUrl,
                        file.path,
                        "",
                        false
                    )
                    val trashTargetUrl = if (!isInTrash) buildWebDavTrashTargetUrl(
                        webDavManager.currentBaseUrl,
                        file.path,
                        file.name,
                        file.isDirectory
                    ) else file.path

                    try {
                        if (isInTrash) {
                            webDavManager.deleteFile(file.path, file.isDirectory)
                            trashMetaDao.deleteByTrashPath(file.path)
                            repository.addSystemLog("WARNING", "DuplicateScan", "Đã xóa vĩnh viễn duplicate '${file.name}'.")
                        } else {
                            try { webDavManager.createFolder(trashFolderUrl) } catch (_: Exception) {}
                            webDavManager.renameFile(file.path, trashTargetUrl)
                            trashMetaDao.insert(TrashMeta(trashPath = trashTargetUrl, originalPath = file.path))
                            repository.addSystemLog("WARNING", "DuplicateScan", "Đã chuyển duplicate '${file.name}' vào Thùng rác.")
                        }
                        completedPaths += file.path
                    } catch (e: Exception) {
                        val message = friendlyError(e)
                        if (e.isTransientNetworkFailure()) {
                            repository.addSystemLog("WARNING", "DuplicateScan", "Xóa duplicate '${file.name}' thất bại, đã được đưa vào hàng đợi ngoại tuyến: ${message.take(80)}")
                            if (isInTrash) {
                                enqueueOfflineAction(appContext, "DELETE", file.path)
                            } else {
                                enqueueOfflineAction(appContext, "RENAME", file.path, trashTargetUrl)
                            }
                            completedPaths += file.path
                        } else {
                            hardError = message
                            repository.addSystemLog("ERROR", "DuplicateScan", "Xóa duplicate '${file.name}' thất bại: ${message.take(120)}")
                        }
                    }

                    processed++
                    if (processed % 5 == 0) kotlinx.coroutines.delay(10)
                }

                val deletedPaths = completedPaths.toSet()
                for (path in deletedPaths) { repository.removeDuplicateFromDb(path) }

                withContext(Dispatchers.Main) {
                    duplicateFilesList = duplicateFilesList.filter { it.path !in deletedPaths }
                    selectedDuplicates.clear()
                    refresh()
                }

                if (hardError != null) {
                    withContext(Dispatchers.Main) { errorMessage = "Có lỗi khi xử lý một số duplicate: $hardError" }
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

                Log.i(TAG, "Đang tải: $url → ${targetFile.absolutePath}")

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

                        Log.i(TAG, "Tải xuống hoàn tất: ${downloadedBytes / 1024}KB")

                        // 5. Mở file cục bộ bằng trình phát video
                        withContext(Dispatchers.Main) {
                            onReady()
                            openLocalFile(context, targetFile)
                        }
                    }

            } catch (e: CancellationException) {
                Log.d(TAG, "Đã hủy tải xuống")
            } catch (e: Exception) {
                Log.e(TAG, "Tải xuống thất bại: ${e.message}")
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

fun WebDavViewModel.listenToLocalNasApi(forceRestart: Boolean = false) {
    if (!forceRestart && statusJob?.isActive == true) {
        android.util.Log.d("DashboardMonitor", "listenToLocalNasApi skip - already active")
        return
    }
    android.util.Log.d("DashboardMonitor", "listenToLocalNasApi start forceRestart=$forceRestart")
    statusJob?.cancel()
    statusJob = viewModelScope.launch(Dispatchers.IO) {
        var currentDelayMs = 5000L
        var lastRealtimeMetricAt = 0L
        var lastMetricsHistoryAt = 0L
        var lastHeavyRefreshAt = 0L
        var lastLogsRefreshAt = 0L
        var lastStorageRefreshAt = 0L
        var lastSmartRefreshAt = 0L
        var lastInsightsRefreshAt = 0L
        var alertsStartedForBaseUrl = ""

        while (isActive) {
            try {
                val baseUrl = webDavManager.currentBaseUrl
                if (baseUrl.isNotEmpty()) {
                    val host = java.net.URL(baseUrl).host
                    val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/status").build()
                    val startedAt = System.currentTimeMillis()
                    localApiClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            currentDelayMs = 5000L
                            val latency = System.currentTimeMillis() - startedAt
                            val jsonObject = org.json.JSONObject(response.body?.string() ?: "{}")
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
                            val fanOnTemp = jsonObject.optDouble("fan_on_temp", 45.0).toFloat()
                            val fanOffTemp = jsonObject.optDouble("fan_off_temp", 40.0).toFloat()
                            val fanRpmRaw = jsonObject.opt("fan_rpm"); val fanRpm = if (fanRpmRaw != null && fanRpmRaw != org.json.JSONObject.NULL) (fanRpmRaw as? Int) else null
                            val topProcs = mutableListOf<Pair<String, Float>>()
                            jsonObject.optJSONArray("top_processes")?.let { arr -> for (i in 0 until arr.length()) { val p = arr.getJSONObject(i); topProcs.add(Pair(p.optString("name", "?"), p.optDouble("cpu", 0.0).toFloat())) } }
                            val newStatus = NasSystemStatus(temp, cpu, cpuTemp, ram, disk, diskCapacity, netRx, netTx, uptime, status, ramPercent, torrentList, diskPartList, fanStatus, fanMode, fanOnTemp, fanOffTemp, fanRpm, topProcs)
                            val hddVal = tempRaw.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                            val cpuVal = cpuTemp.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                            withContext(Dispatchers.Main) {
                                apiLatencyMs = latency
                                apiFailureCount = 0
                                lastStatusRefreshAt = System.currentTimeMillis()
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
                                    // FIX: temperatureHistory boc trong mutableStateOf — in-place
                                    // addLast khong trigger recompose. Phai reassign de Compose biet.
                                    temperatureHistory.add(Pair(cpuVal, hddVal))

                                    while (temperatureHistory.size > 40) temperatureHistory.removeAt(0)

                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) { apiFailureCount += 1; systemStatus = systemStatus.copy(status = "API từ chối") }
                            currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(60_000L)
                        }
                    }
                }
            } catch (e: Exception) {
                val isTimeout = e is java.net.SocketTimeoutException || e is java.net.ConnectException
                val msg = if (isTimeout) "Mất kết nối API (${e.javaClass.simpleName})" else "API: ${e.javaClass.simpleName}"
                android.util.Log.w("NAS_API", "Theo dõi ping thất bại: ${e.message}")
                withContext(Dispatchers.Main) { apiFailureCount += 1; systemStatus = systemStatus.copy(status = msg) }
                currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(60_000L)
            }
            val activeBaseUrl = webDavManager.currentBaseUrl
            if (activeBaseUrl.isBlank()) {
                alertsStartedForBaseUrl = ""
            } else {
                if (alertsStartedForBaseUrl != activeBaseUrl) {
                    alertsStartedForBaseUrl = activeBaseUrl
                    startRealtimeAlerts()
                    resetDashboardRefreshGuards()
                    lastRealtimeMetricAt = 0L
                    lastMetricsHistoryAt = 0L
                    lastHeavyRefreshAt = 0L
                    lastLogsRefreshAt = 0L
                    lastStorageRefreshAt = 0L
                    lastSmartRefreshAt = 0L
                    lastInsightsRefreshAt = 0L
                }

                val tickNow = System.currentTimeMillis()
                val foreground = AppConfig.IS_APP_FOREGROUND
                val realtimeInterval = if (foreground) 5_000L else 20_000L
                if (lastRealtimeMetricAt == 0L || tickNow - lastRealtimeMetricAt >= realtimeInterval) {
                    fetchRealtimeMetricPoint()
                    lastRealtimeMetricAt = tickNow
                }

                val metricsHistoryInterval = if (foreground) 600_000L else 1_800_000L
                if (lastMetricsHistoryAt == 0L || tickNow - lastMetricsHistoryAt >= metricsHistoryInterval) {
                    fetchMetricsHistory(metricsHours)
                    lastMetricsHistoryAt = tickNow
                }

                if (lastHeavyRefreshAt == 0L || tickNow - lastHeavyRefreshAt >= 300_000L) {
                    fetchOmvOverview()
                    fetchDailyReport()
                    lastHeavyRefreshAt = tickNow
                }

                val logInterval = if (foreground) 15_000L else 60_000L
                if (lastLogsRefreshAt == 0L || tickNow - lastLogsRefreshAt >= logInterval) {
                    loadSystemLogs()
                    lastLogsRefreshAt = tickNow
                }

                val storageInterval = if (foreground) 60_000L else 180_000L
                if (lastStorageRefreshAt == 0L || tickNow - lastStorageRefreshAt >= storageInterval) {
                    fetchStorageUsage()
                    lastStorageRefreshAt = tickNow
                }

                if (lastSmartRefreshAt == 0L || tickNow - lastSmartRefreshAt >= 300_000L) {
                    fetchSmartData()
                    lastSmartRefreshAt = tickNow
                }

                val insightsInterval = if (foreground) 15_000L else 60_000L
                if (lastInsightsRefreshAt == 0L || tickNow - lastInsightsRefreshAt >= insightsInterval) {
                    fetchNasInsights()
                    lastInsightsRefreshAt = tickNow
                }
            }

            // FIX (audit #11): app background -> tang delay them de tiet kiem battery.
            // Khong tat han vi WebSocket alerts (security ban) van can biet API con song.
            val effective = if (AppConfig.IS_APP_FOREGROUND) currentDelayMs
                            else (currentDelayMs * 4).coerceAtMost(60_000L)
            delay(effective)
        }
    }
}

fun WebDavViewModel.startRealtimeAlerts() {
    // FIX: dong socket cu truoc khi tao moi de tranh leak. Reset flag scheduled
    // de cho phep startRealtimeAlerts() goi truc tiep (vd tu UI) khong bi block
    // boi flag con sot lai tu lan failure truoc.
    webSocket?.close(1000, "Restarting")
    webSocket = null
    wsReconnectScheduled = false
    try {
        val url = webDavManager.currentBaseUrl
        if (url.isBlank()) return
        val host = java.net.URL(url).host
        // nas_api_server.py chay Tornado WebSocket tren cong AppConfig.WS_PORT (5051)
        val wsUrl = "ws://$host:${com.nas.naswebdav.AppConfig.WS_PORT}/ws/alerts"
        val wsRequest = okhttp3.Request.Builder().url(wsUrl).let(WebDavManager::tagCurrentAuth).build()
        val client = localApiClient.newBuilder()
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        webSocket = client.newWebSocket(wsRequest, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                // FIX: reset backoff khi ket noi thanh cong de lan failure ke tiep
                // bat dau lai tu 5s thay vi tang luong cu (vd 120s).
                wsReconnectAttempt = 0
            }
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
                // FIX: exponential backoff voi jitter, va flag chong nhieu listener
                // cu cung schedule reconnect mot luc -> hammering NAS.
                if (wsReconnectScheduled) return
                wsReconnectScheduled = true
                val attempt = (++wsReconnectAttempt).coerceAtMost(8)
                val base = com.nas.naswebdav.AppConfig.WS_RECONNECT_MIN_MS shl (attempt - 1).coerceAtMost(5)
                val capped = base.coerceAtMost(com.nas.naswebdav.AppConfig.WS_RECONNECT_MAX_MS)
                val jitter = (Math.random() * 1500).toLong()
                val delayMs = capped + jitter
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        kotlinx.coroutines.delay(delayMs)
                        wsReconnectScheduled = false
                        if (webDavManager.currentBaseUrl.isNotEmpty()) startRealtimeAlerts()
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        wsReconnectScheduled = false
                    }
                }
            }
        })
    } catch (e: Exception) {
        wsReconnectScheduled = false
    }
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

fun WebDavViewModel.loadSystemLogs(minIntervalMs: Long = 15_000L) {
    val now = System.currentTimeMillis()
    if (minIntervalMs > 0L && now - lastLogsRefreshAt < minIntervalMs) return
    lastLogsRefreshAt = now
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
            android.util.Log.e("NasAPI", "Không tải được nhật ký từ NAS: ${e.message}")
        }
        allLogs.sortByDescending { it.timestamp }
        withContext(Dispatchers.Main) { 
            systemLogsList = allLogs.take(200) 
            lastLogsRefreshAt = System.currentTimeMillis()
        }
    }
}
fun WebDavViewModel.clearSystemLogs() { 
    viewModelScope.launch(Dispatchers.IO) { 
        repository.clearSystemLogs()
        try {
            if (webDavManager.currentBaseUrl.isNotEmpty()) {
                val req = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/system_logs/clear")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        android.util.Log.e("NasAPI", "Lỗi xóa server logs: ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NasAPI", "Không xóa được nhật ký trên NAS: ${e.message}")
        }
        withContext(Dispatchers.Main) { 
            systemLogsList = emptyList()
            commonDialogMessage = "Đã dọn sạch nhật ký hệ thống."
            showCommonDialog = true 
        } 
    } 
}

private var lastSmartRefreshAtVm = 0L
fun WebDavViewModel.fetchSmartData(minIntervalMs: Long = 15_000L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastSmartRefreshAtVm < minIntervalMs) return
        lastSmartRefreshAtVm = now
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/smart").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "")
                    withContext(Dispatchers.Main) {
                        smartInfo = SmartInfo(status = json.optString("status", "Không rõ"), temperature = run { val rawTemp = json.optString("temperature", "--"); if (rawTemp != "--" && !rawTemp.contains("°")) "${rawTemp}°C" else rawTemp }, rawLog = json.optString("raw_log", ""))
                        lastSmartRefreshAt = System.currentTimeMillis()
                    }
                } else withContext(Dispatchers.Main) { smartInfo = SmartInfo("Lỗi kết nối", "--", "Mã lỗi: ${response.code}") }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { smartInfo = SmartInfo("Không thể kết nối", "--", e.message ?: "") } }
    }
}

private var lastOmvOverviewFetchAt = 0L
fun WebDavViewModel.fetchOmvOverview(minIntervalMs: Long = 15_000L) {
    val now = System.currentTimeMillis()
    if (minIntervalMs > 0L && now - lastOmvOverviewFetchAt < minIntervalMs) return
    if (isFetchingOmvOverview) return
    lastOmvOverviewFetchAt = now
    isFetchingOmvOverview = true
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
                        val enabled = s.optBoolean("enabled")
                        val running = s.optBoolean("running")
                        OmvServiceInfo(
                            s.optString("name"),
                            s.optString("title"),
                            enabled,
                            running,
                            s.optBoolean("effective_enabled", enabled && running)
                        )
                    }
                    val network = parseOmvNetwork(netArr)
                    persistDetectedWakeOnLanMac(network)
                    val filesystems = (0 until fsArr.length()).map { i ->
                        val f = fsArr.getJSONObject(i)
                        OmvFilesystem(f.optString("device"), f.optString("label"), f.optString("mountpoint"), f.optString("used"), f.optLong("size_bytes"), f.optInt("percentage"), f.optString("description"))
                    }
                    val disks = (0 until diskArr.length()).map { i ->
                        val d = diskArr.getJSONObject(i)
                        OmvDiskInfo(
                            d.optString("name"),
                            d.optString("model"),
                            d.optString("serial"),
                            d.optString("size"),
                            d.optBoolean("is_root"),
                            d.optString("device"),
                            d.optBoolean("is_target_hdd"),
                            d.optBoolean("is_usb_import")
                        )
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
        finally { withContext(Dispatchers.Main) { isFetchingOmvOverview = false } }
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

fun WebDavViewModel.sendWakeOnLan(
    macStr: String,
    targetHost: String? = null,
    onResult: ((com.nas.naswebdav.utils.WolUtil.WolResult) -> Unit)? = null
) {
    viewModelScope.launch(Dispatchers.IO) {
        val preferredHost = targetHost?.trim()?.takeIf { it.isNotBlank() } ?: runCatching {
            java.net.URL(webDavManager.currentBaseUrl).host
        }.getOrNull()
        val result = com.nas.naswebdav.utils.WolUtil.smartWakeOnLan(macStr, preferredHost)
        val logType = if (result.success) "INFO" else "ERROR"
        val logMessage = if (result.success) {
            "Người dùng đã gửi Wake-on-LAN đánh thức NAS tại MAC ${macStr.trim()}: ${result.message}"
        } else {
            "Gửi Wake-on-LAN tới MAC ${macStr.trim()} thất bại: ${result.message}"
        }
        repository.addSystemLog(logType, "Power", logMessage)
        withContext(Dispatchers.Main) { onResult?.invoke(result) }
    }
}

private suspend fun WebDavViewModel.refreshWakeOnLanMacFromNas(): String? {
    return try {
        val apiBaseUrl = webDavManager.currentBaseUrl.toApiBaseUrl()
        val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/omv/overview").build()
        localApiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = org.json.JSONObject(response.body?.string() ?: "{}")
            val network = parseOmvNetwork(json.optJSONArray("network") ?: org.json.JSONArray())
            persistDetectedWakeOnLanMac(network)
        }
    } catch (e: Exception) {
        android.util.Log.w("WOL", "Không thể lấy MAC Wake-on-LAN trước khi tắt nguồn: ${e.message}")
        null
    }
}

fun WebDavViewModel.sendCommandToNas(
    endpoint: String,
    onResult: ((Boolean, String) -> Unit)? = null
) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val host = java.net.URL(webDavManager.currentBaseUrl).host
            val isSleepCommand = endpoint.contains("shutdown") || endpoint.contains("suspend")
            val cmdName = when { endpoint.contains("reboot") -> "Khởi động lại"; isSleepCommand -> "Ngủ"; else -> endpoint }
            val savedMac = if (isSleepCommand) refreshWakeOnLanMacFromNas() else null
            if (savedMac != null) {
                repository.addSystemLog("INFO", "Power", "Đã lưu MAC Wake-on-LAN $savedMac trước khi đưa NAS vào chế độ ngủ")
            }
            repository.addSystemLog("WARNING", "Power", "Đã gửi lệnh $cmdName NAS tại $host")
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/$endpoint").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            localApiClient.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                val suffix = if (isSleepCommand && savedMac != null) " MAC WOL: $savedMac." else ""
                withContext(Dispatchers.Main) {
                    onResult?.invoke(ok, if (ok) "Đã gửi lệnh $cmdName NAS.$suffix" else "NAS từ chối lệnh $cmdName (HTTP ${response.code}).")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult?.invoke(false, "Không gửi được lệnh nguồn: ${e.message ?: "lỗi mạng"}")
            }
        }
    }
}

/**
 * Gui lenh power (reboot/shutdown) tu man LoginScreen — KHONG yeu cau da connect.
 * Dung khi NAS bi loi khong dang nhap duoc nhung van phai khoi dong lai duoc tu xa.
 * Truyen truc tiep IP + user + pass tu form, tu build URL va header auth, khong dua
 * vao webDavManager.currentBaseUrl (luc nay co the rong vi chua connect).
 */
fun WebDavViewModel.sendPowerCommandFromLogin(
    ipInput: String,
    user: String,
    pass: String,
    endpoint: String,
    onResult: (Boolean, String) -> Unit
) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val trimmed = ipInput.trim()
            if (trimmed.isEmpty()) {
                withContext(Dispatchers.Main) { onResult(false, "Vui lòng nhập IP của NAS") }
                return@launch
            }
            // Tu IP -> http://<ip>:<API_PORT>
            val host = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                java.net.URL(trimmed).host
            } else trimmed.substringBefore(":")
            val apiUrl = "http://$host:${AppConfig.API_PORT}/api/$endpoint"
            val cmdName = when {
                endpoint.contains("reboot") -> "Khởi động lại"
                endpoint.contains("suspend") -> "Ngủ"
                endpoint.contains("shutdown") -> "Tắt nguồn"
                else -> endpoint
            }
            val reqBuilder = okhttp3.Request.Builder()
                .url(apiUrl)
                .post(ByteArray(0).toRequestBody(null, 0, 0))
            if (user.isNotBlank() && pass.isNotBlank()) {
                reqBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
            }
            // Dung fastApiClient cua NasApplication (KHONG dung localApiClient
            // vi localApiClient co interceptor doc webDavManager.currentUser/pass
            // — luc nay con rong vi chua connect)
            val client = NasApplication.instance.fastApiClient.newBuilder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val ok = resp.isSuccessful
                val code = resp.code
                withContext(Dispatchers.Main) {
                    if (ok) onResult(true, "Đã gửi lệnh $cmdName NAS!")
                    else onResult(false, "NAS từ chối (HTTP $code) — kiểm tra IP/tài khoản/mật khẩu")
                }
                try { repository.addSystemLog("WARNING", "Power", "LoginScreen: gửi $cmdName NAS tại $host (HTTP $code)") } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(false, "Không kết nối được NAS: ${e.message?.take(80) ?: "lỗi mạng"}")
            }
        }
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
            client.newCall(request).execute().use { response ->
                repository.addSystemLog(if (response.isSuccessful) "INFO" else "WARNING", "Docker", "Người dùng: ${if (turnOn) "bật" else "tắt"} Docker/qBittorrent ${if (response.isSuccessful) "thành công" else "thất bại HTTP ${response.code}"}.")
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    val running = json.optBoolean("running", json.optBoolean("effective_running", false))
                    withContext(Dispatchers.Main) { isDockerRunning = running }
                }
            }
            checkDockerStatus()
        } catch (e: Exception) {
            repository.addSystemLog("WARNING", "Docker", "Người dùng: ${if (turnOn) "bật" else "tắt"} Docker/qBittorrent thất bại: ${e.message?.take(120)}")
        }
        withContext(Dispatchers.Main) { isTogglingDocker = false }
    }
}

fun WebDavViewModel.toggleOmvService(serviceName: String, enable: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject()
                .put("name", serviceName)
                .put("enable", enable)
                .toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/service/toggle")
                .post(body)
                .build()
            localApiClient.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                withContext(Dispatchers.Main) {
                    commonDialogType = if (ok) com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS else com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = if (ok) "Đã ${if (enable) "bật" else "tắt"} dịch vụ ${serviceName.uppercase()}." else "Không thể ${if (enable) "bật" else "tắt"} dịch vụ ${serviceName.uppercase()} (HTTP ${response.code})."
                    showCommonDialog = true
                }
            }
            fetchOmvOverview()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                commonDialogMessage = "Lỗi điều khiển dịch vụ: ${e.message?.take(120)}"
                showCommonDialog = true
            }
        }
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
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS; commonDialogMessage = "Đã cấp quyền truy cập cho IP: $ip"; showCommonDialog = true }
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
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING; commonDialogMessage = "Đã chặn quyền truy cập của IP: $ip"; showCommonDialog = true }
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
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
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

private suspend fun WebDavViewModel.pollSmartOrganizeJob(apiBase: String, jobId: String): org.json.JSONObject {
    val pollClient = localApiClient.newBuilder()
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    var delayMs = 1000L
    val deadline = System.currentTimeMillis() + 20 * 60 * 1000L
    while (System.currentTimeMillis() < deadline) {
        val request = okhttp3.Request.Builder()
            .url("$apiBase/api/tools/smart_organize/status/$jobId")
            .get()
            .build()

        pollClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody == null) {
                throw Exception("Loi NAS: ${response.code}")
            }

            val json = org.json.JSONObject(responseBody)
            when (json.optString("status", "")) {
                "queued", "running" -> Unit
                "finished", "finished_with_errors", "failed", "aborted" -> return json
                else -> throw Exception("Trang thai tac vu khong hop le: ${json.optString("status")}")
            }
        }

        kotlinx.coroutines.delay(delayMs)
        delayMs = (delayMs * 2).coerceAtMost(5000L)
    }

    throw java.util.concurrent.TimeoutException("Smart organizer timed out")
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
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            execClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (responseBody == null) {
                    throw Exception("Empty response body")
                }

                val json = org.json.JSONObject(responseBody)
                val queued = response.code == 202 || json.optBoolean("queued", false)
                if (queued) {
                    val jobId = json.optString("job_id", "")
                    if (jobId.isEmpty()) {
                        throw Exception("Missing job_id from smart organize execute response")
                    }

                    withContext(Dispatchers.Main) {
                        organizerResult = "Dang sap xep..."
                        organizerScanResult = null
                    }

                    val finalJson = pollSmartOrganizeJob(apiBase, jobId)
                    val finalStatus = finalJson.optString("status", "")
                    val movedCount = finalJson.optInt("moved_count", 0)
                    val errorCount = finalJson.optInt("error_count", 0)
                    val errorMessage = finalJson.optString("error", "")

                    withContext(Dispatchers.Main) {
                        when (finalStatus) {
                            "finished" -> organizerResult = "Hoan tat ? $movedCount tep da sap xep"
                            "finished_with_errors" -> organizerResult = "Hoan tat ? $movedCount tep da sap xep ($errorCount loi)"
                            "failed", "aborted" -> organizerError = if (errorMessage.isNotEmpty()) errorMessage else "Smart Organizer that bai"
                            else -> organizerResult = "Hoan tat ? $movedCount tep da sap xep"
                        }
                        organizerScanResult = null
                    }

                    if (finalStatus == "finished" || finalStatus == "finished_with_errors") {
                        refresh()
                    }
                } else if (response.isSuccessful) {
                    val count = json.optInt("moved_count", 0)
                    withContext(Dispatchers.Main) {
                        organizerResult = "Hoan tat ? $count tep da sap xep"
                        organizerScanResult = null
                    }
                    refresh()
                } else {
                    throw Exception("Loi NAS: ${response.code}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { organizerError = "Loi ket noi: ${e.message}" }
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
    private val isMonitoring = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun startMonitoring(context: Context) = withContext(Dispatchers.IO) {
        if (!isMonitoring.compareAndSet(false, true)) return@withContext
        val appContext = context.applicationContext ?: context
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val uid = Process.myUid()
        try {
            while (isActive && isMonitoring.get()) {
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
                    if (diskCacheCheckCounter % 60 == 0) { val cacheDir = File(appContext.cacheDir, "image_cache"); lastDiskCacheSizeMb = if (cacheDir.exists()) (getFolderSize(cacheDir) / 1048576L).toInt() else 0 }
                    diskCacheCheckCounter++
                    _metricsFlow.value = SystemMetrics(totalRam, freeRam, usedRamPercent, calculateCpuUsage(), rxSpeed, txSpeed, lastDiskCacheSizeMb, maxJvm, usedJvm)
                } catch (_: Exception) {}
                // FIX (audit #11): khi app vao background, gian poll 1s -> 5s
                // -> giam CPU/battery dang ke khi user khong xem panel debug.
                delay(if (AppConfig.IS_APP_FOREGROUND) 1000L else 5000L)
            }
        } finally { 
            previousRx = 0L; previousTx = 0L
            isMonitoring.set(false)
        }
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
            Log.w("WebDavViewModel", "fetchSystemProcesses failed", e)
        } finally {
            withContext(Dispatchers.Main) { isLoadingProcesses = false }
        }
    }
}


fun WebDavViewModel.fetchLivestreamStatusOnly(context: android.content.Context) {
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            if (apiBaseUrl.isBlank()) return@launch
            val requestBuilder = okhttp3.Request.Builder().url(apiBaseUrl + "/api/livestream/status")
            val user = com.nas.naswebdav.SecurePrefsHelper.getUser(context)
            val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(context)
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
            }
            localApiClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use
                val responseStr = response.body?.string() ?: "{}"
                val json = org.json.JSONObject(responseStr)
                val jobsArray = json.optJSONArray("jobs") ?: org.json.JSONArray()
                
                val newJobs = mutableListOf<WebDavViewModel.LivestreamJob>()
                val serverRecordingIds = mutableSetOf<String>()
                for (i in 0 until jobsArray.length()) {
                    val jobObj = jobsArray.getJSONObject(i)
                    val status = jobObj.optString("status", "")
                    val jobId = jobObj.optString("job_id", "")
                    val platform = jobObj.optString("platform", "")
                    val watchUser = jobObj.optString("watch_username", "")
                    if (status == "recording" && jobId.isNotEmpty()) {
                        serverRecordingIds.add(jobId)
                        newJobs.add(
                            WebDavViewModel.LivestreamJob(
                                jobId = jobId,
                                platform = platform,
                                status = status,
                                watchUsername = watchUser,
                                durationSeconds = jobObj.optLong("duration_seconds", 0L),
                                startedTs = jobObj.optLong("started_ts", 0L),
                                fileSize = jobObj.optString("file_size", "0 B"),
                                duration = jobObj.optString("duration_display", "0h00m00s"),
                                speed = jobObj.optString("avg_speed", "—"),
                                outputFile = jobObj.optString("output_file", "")
                            )
                        )
                    }
                }
                lastLivestreamServerSyncAt = System.currentTimeMillis()
                lastLivestreamServerRecordingIds = serverRecordingIds.toSet()
                if (serverRecordingIds.isEmpty()) {
                    LivestreamMonitorWorker.cancelAll(context)
                }
                val displayJobs = dedupeLivestreamJobsForDisplay(newJobs)
                lastLivestreamServerRecordingIds = displayJobs.map { it.jobId }.toSet()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (activeLivestreams.size != displayJobs.size || activeLivestreams != displayJobs) {
                        activeLivestreams.clear()
                        activeLivestreams.addAll(displayJobs)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WebDavViewModel", "fetchLivestreamStatusOnly failed", e)
        }
    }
}

fun WebDavViewModel.fetchSmbStatus() {
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/smb/status").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val obj = org.json.JSONObject(body)
                        val enabled = obj.optBoolean(
                            "effective_enabled",
                            obj.optBoolean("enabled", false) && obj.optBoolean("active", false)
                        )
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isSmbEnabled = enabled
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WebDavViewModel", "fetchSmbStatus failed", e)
        }
    }
}

fun WebDavViewModel.toggleSmbShare(enable: Boolean, onResult: (Boolean, String) -> Unit) {
    if (isLoadingSmb) return
    isLoadingSmb = true
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val json = org.json.JSONObject().put("enable", enable)
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/smb/toggle").post(body).build()
            localApiClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                val responseJson = try { org.json.JSONObject(responseBody) } catch (_: Exception) { org.json.JSONObject() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isLoadingSmb = false
                    if (response.isSuccessful) {
                        val effectiveEnabled = responseJson.optBoolean(
                            "effective_enabled",
                            responseJson.optBoolean("enabled", false) && responseJson.optBoolean("active", false)
                        )
                        isSmbEnabled = effectiveEnabled
                        onResult(true, if (effectiveEnabled) "SMB đang bật thực tế" else "SMB đang tắt thực tế")
                    } else {
                        onResult(false, "Lỗi: $responseBody")
                    }
                }
                fetchSmbStatus()
                fetchOmvOverview()
            }
        } catch (e: Exception) {
            Log.w("WebDavViewModel", "toggleSmbShare failed", e)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                isLoadingSmb = false
                onResult(false, "Lỗi kết nối: ${e.message}")
            }
        }
    }
}
