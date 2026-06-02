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

// FIX ERROR HANDLING: Chuyá»ƒn lá»—i ká»¹ thuáº­t thÃ nh thÃ´ng bÃ¡o dá»… hiá»ƒu
private fun friendlyError(e: Exception): String = when (e) {
    is java.net.SocketTimeoutException -> "Káº¿t ná»‘i tá»›i NAS quÃ¡ cháº­m hoáº·c NAS khÃ´ng pháº£n há»“i. Vui lÃ²ng kiá»ƒm tra máº¡ng."
    is java.net.ConnectException -> "KhÃ´ng thá»ƒ káº¿t ná»‘i tá»›i NAS. Kiá»ƒm tra NAS Ä‘Ã£ báº­t vÃ  cÃ¹ng máº¡ng WiFi."
    is java.net.UnknownHostException -> "Äá»‹a chá»‰ NAS khÃ´ng há»£p lá»‡ hoáº·c máº¥t káº¿t ná»‘i máº¡ng."
    is javax.net.ssl.SSLException -> "Lá»—i báº£o máº­t káº¿t ná»‘i. Kiá»ƒm tra cáº¥u hÃ¬nh SSL/TLS cá»§a NAS."
    else -> e.message ?: "Lá»—i khÃ´ng xÃ¡c Ä‘á»‹nh"
}

private fun buildLoginFailureMessage(urlList: List<String>, errorDetails: List<String>): String {
    if (errorDetails.isEmpty()) {
        return "KhÃ´ng Ä‘Äƒng nháº­p Ä‘Æ°á»£c. Kiá»ƒm tra tÃ i khoáº£n, máº­t kháº©u hoáº·c dá»‹ch vá»¥ WebDAV."
    }
    // Hiá»ƒn thá»‹ Táº¤T Cáº¢ cÃ¡c URL Ä‘Ã£ thá»­ (LAN + Tailscale) kÃ¨m lÃ½ do tá»«ng URL,
    // trÃ¡nh hiá»ƒu nháº§m chá»‰ má»™t URL Ä‘Æ°á»£c thá»­ khi nhiá»u URL cÃ¹ng fail.
    val lines = errorDetails.map { detail ->
        val colonIdx = detail.indexOf(": ")
        val rawUrl = if (colonIdx > 0) detail.take(colonIdx) else detail
        val rawReason = if (colonIdx > 0) detail.substring(colonIdx + 2).take(110).trim() else "khÃ´ng xÃ¡c Ä‘á»‹nh"
        val host = runCatching {
            val u = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
            java.net.URL(u).host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: rawUrl
        val niceReason = when {
            rawReason.contains("WebDAV", ignoreCase = true) -> "WebDAV quÃ¡ háº¡n hoáº·c chÆ°a xÃ¡c thá»±c"
            rawReason.contains("timeout", ignoreCase = true) || rawReason.contains("quÃ¡ háº¡n", ignoreCase = true) || rawReason.contains("timed out", ignoreCase = true) -> "Máº¡ng quÃ¡ háº¡n / khÃ´ng pháº£n há»“i"
            rawReason.contains("Unable to resolve", ignoreCase = true) || rawReason.contains("UnknownHost", ignoreCase = true) -> "KhÃ´ng tÃ¬m tháº¥y host"
            rawReason.contains("ECONNREFUSED", ignoreCase = true) || rawReason.contains("refused", ignoreCase = true) -> "Káº¿t ná»‘i bá»‹ tá»« chá»‘i"
            rawReason.contains("ENETUNREACH", ignoreCase = true) || rawReason.contains("unreachable", ignoreCase = true) -> "Máº¡ng khÃ´ng thá»ƒ tiáº¿p cáº­n"
            else -> rawReason.trimEnd('.')
        }
        "â€¢ $host â€” $niceReason"
    }
    val header = if (lines.size > 1) "KhÃ´ng Ä‘Äƒng nháº­p Ä‘Æ°á»£c NAS (Ä‘Ã£ thá»­ ${lines.size} Ä‘á»‹a chá»‰):" else "KhÃ´ng Ä‘Äƒng nháº­p Ä‘Æ°á»£c NAS:"
    return "$header\n" + lines.joinToString("\n")
}

// THÃŠM DATA CLASS CHO TORRENT
data class TorrentInfo(
    val name: String,
    val progress: Float,
    val speed: String,
    val hash: String = "",
    val state: String = "",
    val savePath: String = ""
)

// DATA CLASS CHO SMART VÃ€ SPEED TEST
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

// DATA CLASS CHO PHÃ‚N TÃCH á»” ÄÄ¨A
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

// Data class lÆ°u trá»¯ tráº¡ng thÃ¡i há»‡ thá»‘ng qua Local API
data class NasSystemStatus(
    val temp: String = "--Â°C",
    val cpu: String = "--%",
    val cpuTemp: String = "--Â°C",
    val ram: String = "--",
    val disk: String = "--%",
    val diskCapacity: String = "",
    val netRx: String = "0 B/s",
    val netTx: String = "0 B/s",
    val uptime: String = "--:--",
    val status: String = "Äang káº¿t ná»‘i...",
    val ramPercent: String = "0",
    val torrents: List<TorrentInfo> = emptyList(),
    val diskParts: List<DiskPart> = emptyList(),
    val fanStatus: String = "--",  // Tráº¡ng thÃ¡i quáº¡t (Dá»«ng / Äang cháº¡y)
    val fanMode: String = "auto",  // auto, on, off, custom
    val fanOnTemp: Float = 45f,
    val fanOffTemp: Float = 40f,
    val fanRpm: Int? = null,       // Sá»‘ vÃ²ng quáº¡t (náº¿u cÃ³)
    val topProcesses: List<Pair<String, Float>> = emptyList() // Top tiáº¿n trÃ¬nh Äƒn CPU
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

// DATA CLASS CHO BIá»‚U Äá»’ GIÃM SÃT
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
 * Kiá»ƒm tra URL cÃ³ trá» Ä‘áº¿n má»™t Ä‘á»‹a chá»‰ Tailscale hay khÃ´ng.
 * Tailscale dÃ¹ng dáº£i CGNAT 100.64.0.0/10 (octet 2 tá»« 64 Ä‘áº¿n 127).
 * VD: 100.90.135.102 â†’ Tailscale âœ…
 *     192.168.100.5  â†’ LAN bÃ¬nh thÆ°á»ng âœ… (KHÃ”NG bá»‹ nháº§m)
 *     100.20.1.1     â†’ LAN bÃ¬nh thÆ°á»ng (ngoÃ i dáº£i Tailscale) âœ…
 */
fun isTailscaleUrl(url: String): Boolean {
    if (url.isBlank()) return false
    // Kiá»ƒm tra tá»« khÃ³a "tailscale" trong URL (cho hostname dáº¡ng tailscale)
    if (url.contains("tailscale", ignoreCase = true)) return true
    return try {
        val host = java.net.URL(url).host ?: return false
        val parts = host.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            // Dáº£i Tailscale: 100.64.x.x â€“ 100.127.x.x
            a == 100 && b in 64..127
        } else false
    } catch (_: Exception) { false }
}

class WebDavViewModel(val webDavManager: WebDavManager, val repository: WebDavRepository) : ViewModel() {


    // CHá»NG RÃ’ Rá»ˆ THREAD VÃ€ Bá»˜ NHá»š: DÃ¹ng chung má»™t OkHttpClient duy nháº¥t cho toÃ n bá»™ cÃ¡c truy váº¥n Local API
    internal val localApiClient: okhttp3.OkHttpClient by lazy {
        NasApplication.instance.fastApiClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Báº¢N VÃ Lá»–I API Tá»ª CHá»I: Tá»± Ä‘á»™ng Ä‘Ã­nh kÃ¨m Header Authorization cho Táº¤T Cáº¢ cÃ¡c request Local API
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
    // Biáº¿n lÆ°u trá»¯ tráº¡ng thÃ¡i giÃ¡m sÃ¡t há»‡ thá»‘ng (Local API)
    var systemStatus by mutableStateOf(NasSystemStatus())
    var temperatureHistory = androidx.compose.runtime.mutableStateListOf<Pair<Float, Float>>()

    // â”€â”€â”€ BIá»‚U Äá»’ GIÃM SÃT REAL-TIME â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var metricsHistory = androidx.compose.runtime.mutableStateListOf<MetricsSnapshot>()
    var metricsHours by mutableIntStateOf(1)         // 1 / 6 / 24 giá»
    var metricsChartTab by mutableIntStateOf(0)       // 0=Nhiá»‡t Ä‘á»™, 1=TÃ i nguyÃªn, 2=Máº¡ng
    var isLoadingMetrics by mutableStateOf(false)
    var metricsError by mutableStateOf<String?>(null)  // Náº¿u cÃ³ lá»—i, hiá»ƒn thá»‹ thay vÃ¬ spinner vÃ´ háº¡n
    var dailyReport by mutableStateOf<DailyReportData?>(null)
    var isDailyReportLoading by mutableStateOf(false)

    // STATE CHO TIáº¾N TRÃŒNH Há»† THá»NG
    var systemProcesses by mutableStateOf<List<SystemProcess>>(emptyList())
    var isLoadingProcesses by mutableStateOf(false)
    private var metricsPollingJob: kotlinx.coroutines.Job? = null
    private var dashboardRealtimeJob: kotlinx.coroutines.Job? = null
    internal var statusJob: kotlinx.coroutines.Job? = null
    private val realtimeMetricInFlight = AtomicBoolean(false)
    private val realtimeMetricNextAllowedAt = AtomicLong(0L)
    private val realtimeMetricBackoffMs = AtomicLong(5_000L)

    // TÃNH NÄ‚NG 4.H: Láº¯ng nghe tráº¡ng thÃ¡i máº¡ng Ping (ms)
    var networkPingMs by mutableStateOf<Long?>(null)
    var lastStatusRefreshAt by mutableStateOf(0L)
    var lastMetricsRefreshAt by mutableStateOf(0L)
    var lastStorageRefreshAt by mutableStateOf(0L)
    var lastSmartRefreshAt by mutableStateOf(0L)
    var lastLogsRefreshAt by mutableStateOf(0L)
    var apiLatencyMs by mutableStateOf<Long?>(null)
    var apiFailureCount by mutableIntStateOf(0)

    // â”€â”€â”€ SMART NETWORK â€“ tráº¡ng thÃ¡i Ä‘ang dÃ¹ng LAN hay Tailscale â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var isOnLan by mutableStateOf(true) // true = LAN, false = Tailscale

    // â”€â”€â”€ GUEST PASS STATE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var activeGuestPass by mutableStateOf<GuestPassInfo?>(null)
    var isGuestPassLoading by mutableStateOf(false)
    var guestPassError by mutableStateOf<String?>(null)

    // â”€â”€â”€ SOCIAL EXTRACTOR STATE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var socialExtractStatus by mutableStateOf("")
    var isSocialExtracting by mutableStateOf(false)
    var socialDownloadHistory by mutableStateOf<List<SocialDownloadItem>>(emptyList())

    // â”€â”€â”€ STREAM PIPE STATE (Äiá»‡n thoáº¡i bÆ¡m CDN â†’ NAS trá»±c tiáº¿p) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var isStreamPiping      by mutableStateOf(false)     // Äang bÆ¡m stream
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
    var storageFolderUsage by mutableStateOf<List<StorageFolderUsage>>(emptyList())
    var isFetchingStorageUsage by mutableStateOf(false)

    // TÃNH NÄ‚NG SMB
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

    var streamPipeStatus    by mutableStateOf("")        // MÃ´ táº£ tráº¡ng thÃ¡i hiá»‡n táº¡i
    var streamPipeProgress  by mutableFloatStateOf(0f)   // 0.0 â†’ 1.0 (náº¿u biáº¿t size)
    var streamPipeSpeedStr  by mutableStateOf("-- MB/s") // Tá»‘c Ä‘á»™ dáº¡ng text
    var streamPipeEtaStr    by mutableStateOf("--")      // ETA dáº¡ng text
    private var streamPipeJob: kotlinx.coroutines.Job? = null
    private var _activeStreamPipeWorkId: java.util.UUID? = null
    private var livestreamObserverJob: kotlinx.coroutines.Job? = null

    // LOáº I Bá»Ž fileList GÃ‚Y OOM, THAY Báº°NG PAGING DATA FLOW
    var fileList by mutableStateOf<List<NasFile>>(emptyList()) // Giá»¯ láº¡i dá»± phÃ²ng cho tÃ­nh nÄƒng tÃ¬m kiáº¿m/Ä‘áº·c biá»‡t

    private val _pagedFilesFlow = MutableStateFlow<Flow<PagingData<NasFile>>>(emptyFlow())
    val pagedFilesFlow = _pagedFilesFlow.asStateFlow()

    private val _thumbnailAudit = MutableStateFlow<ThumbnailAuditData?>(null)
    val thumbnailAudit = _thumbnailAudit.asStateFlow()

    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var connectionStatus by mutableStateOf("Äang káº¿t ná»‘i...")
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

    // BIáº¾N CHO BATCH COPY / MOVE
    var isBatchProcessing by mutableStateOf(false)
    var batchProcessType by mutableStateOf("") // "COPY" hoáº·c "MOVE"
    var batchProcessProgress by mutableFloatStateOf(0f)
    var batchProcessCurrentFile by mutableStateOf("")

    // TÃNH NÄ‚NG 7.M: Tráº¡ng thÃ¡i chá»©a dá»¯ liá»‡u Text Preview
    var textPreviewContent by mutableStateOf<String?>(null)

    fun fetchTextPreview(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; textPreviewContent = null }
            val content = webDavManager.readFileText(url)
            withContext(Dispatchers.Main) { textPreviewContent = content; isLoading = false }
        }
    }

    // Tiáº¿n trÃ¬nh táº£i thumbnail
    var totalImagesInFolder by mutableIntStateOf(0)
    var loadedImagesCount by mutableIntStateOf(0)
    val imageLoadProgress: Float get() = if (totalImagesInFolder > 0) loadedImagesCount.toFloat() / totalImagesInFolder else 0f
    // Biáº¿n tráº¡ng thÃ¡i cho tiáº¿n trÃ¬nh Auto Backup
    var isAutoBackupRunning by mutableStateOf(false)
    var autoBackupCurrentFile by mutableStateOf("")
    var autoBackupSourcePath by mutableStateOf("")
    var autoBackupDestPath by mutableStateOf("")
    var autoBackupProgress by mutableFloatStateOf(0f)
    var autoBackupProcessedCount by mutableIntStateOf(0)
    var autoBackupTotalCount by mutableIntStateOf(0)
    var autoBackupElapsedTime by mutableLongStateOf(0L)
    var autoBackupIsPaused by mutableStateOf(false)

    // === ÄÃ£ gá»¡ bá» tÃ­nh nÄƒng Äá»“ng bá»™ thÆ° má»¥c ===

    // Biáº¿n tráº¡ng thÃ¡i cho tÃ­nh nÄƒng QuÃ©t vÃ  XÃ³a file trÃ¹ng láº·p
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
    var scanDuplicatesPercent by mutableFloatStateOf(0f) // Thanh tá»•ng
    var scanDuplicatesCurrentStagePercent by mutableFloatStateOf(0f) // Thanh hiá»‡n táº¡i
    var scanDuplicatesElapsedTime by mutableLongStateOf(0L) // Thá»i gian Ä‘Ã£ cháº¡y
    var scanDuplicatesEstimatedTimeRemaining by mutableLongStateOf(-1L) // Thá»i gian cÃ²n láº¡i dá»± kiáº¿n
    var scanDuplicatesIsFolder by mutableStateOf(false)
    var scanDuplicatesStage by mutableStateOf("Khá»Ÿi Ä‘á»™ng...") // PHASE 4: Giai Ä‘oáº¡n hiá»‡n táº¡i
    var scanDuplicatesStageNumber by mutableIntStateOf(1)      // Sá»‘ thá»© tá»± giai Ä‘oáº¡n (1-4)
    var scanDuplicatesTotalStages by mutableIntStateOf(4)      // Tá»•ng sá»‘ giai Ä‘oáº¡n
    var scanDuplicatesStageDescription by mutableStateOf("")   // MÃ´ táº£ chi tiáº¿t giai Ä‘oáº¡n
    internal var scanJob: kotlinx.coroutines.Job? = null
    
    // ÄIá»€U KHIá»‚N QUÃ‰T RÃC
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
        scanJob?.cancel() // Huá»· luá»“ng theo dÃµi tráº¡ng thÃ¡i Worker
        isScanningDuplicates = false // ÄÃ³ng panel tiáº¿n trÃ¬nh
        duplicateFilesList = emptyList() // XoÃ¡ danh sÃ¡ch káº¿t quáº£ (náº¿u cÃ³) Ä‘á»ƒ áº©n card TÃ¡c vá»¥ ná»n
        
        // Reset tráº¡ng thÃ¡i tiáº¿n trÃ¬nh
        DuplicateProgressState.stage.value = "Khá»Ÿi Ä‘á»™ng..."
        DuplicateProgressState.percent.value = 0f
    }
    
    // TÃNH NÄ‚NG AUTO-CLEAN DUPLICATES
    var autoCleanEnabled by mutableStateOf(false)
    fun toggleAutoClean(context: Context, enabled: Boolean) {
        autoCleanEnabled = enabled
        // LÆ°u SharedPreferences
        context.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE).edit().putBoolean("auto_clean_enabled", enabled).apply()
        
        val workManager = androidx.work.WorkManager.getInstance(context)
        if (enabled) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) // Cáº§n Wifi
                .build()
                
            val req = androidx.work.PeriodicWorkRequestBuilder<AutoDuplicateScanWorker>(7, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork("AutoCleanDuplicates", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            workManager.cancelUniqueWork("AutoCleanDuplicates")
        }
    }

    // --- QUáº¢N LÃ Báº¢O Máº¬T & PHÃŠ DUYá»†T (DEVICE APPROVAL) ---
    var showApprovalDialog by mutableStateOf(false)
    var pendingIpAddress by mutableStateOf("")
    var approvalMessage by mutableStateOf("")
    var pendingCountryCode by mutableStateOf("VN")
    var weeklyReportText by mutableStateOf("Äang táº£i dá»¯ liá»‡u...")

    internal var webSocket: okhttp3.WebSocket? = null
    // FIX: tranh reconnect storm â€” track so lan thu lai de exponential backoff
    // va co flag chong reconnect tu nhieu listener onFailure cu va race nhau.
    @Volatile internal var wsReconnectAttempt: Int = 0
    @Volatile internal var wsReconnectScheduled: Boolean = false

    // TRÃCH XUáº¤T HOST CHUáº¨N Äá»‚ FIX Lá»–I CRASH PORT (8822:5050)

    // --- QUáº¢N LÃ NHáº¬T KÃ Há»† THá»NG ---
    var showLogDialog by mutableStateOf(false)
    var systemLogsList by mutableStateOf<List<SystemLog>>(emptyList())

    // Tráº¡ng thÃ¡i cho cháº¿ Ä‘á»™ xem Ä‘áº·c biá»‡t (áº¢nh má»›i/Video gáº§n Ä‘Ã¢y)
    var isSpecialMode by mutableStateOf(false)
    var specialTitle by mutableStateOf("")

    // STATE CHO DIALOG THÃ”NG BÃO CHUNG Tá»ª VIEWMODEL
    var commonDialogMessage by mutableStateOf("")
    var commonDialogType by mutableStateOf(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS)
    var showCommonDialog by mutableStateOf(false)

    fun logUserAction(module: String, message: String, type: String = "INFO") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addSystemLog(type, module, "NgÆ°á»i dÃ¹ng: $message")
                withContext(Dispatchers.Main) { loadSystemLogs() }
            } catch (e: Exception) {
                android.util.Log.w("UserActionLog", "log failed: ${e.message}")
            }
        }
    }

    // FIX Lá»–I 5: Debounce â€“ chá»‰ hiá»ƒn thá»‹ dialog lá»—i máº¥t máº¡ng má»—i 2 phÃºt, trÃ¡nh spam
    private var lastNetworkErrorDialogAt = 0L
    internal var lastFanModeSettingTime = 0L
    var isFanModeUpdating by mutableStateOf(false)
    private val NETWORK_ERROR_DIALOG_COOLDOWN_MS = 2 * 60 * 1000L // 2 phÃºt

    // STATE CHO SMART DIALOG VÃ€ SPEED TEST
    var showSmartDialog by mutableStateOf(false)
    var smartInfo by mutableStateOf(SmartInfo("Äang táº£i...", "--", ""))
    var speedTestResult by mutableStateOf(SpeedTestResult("--", "--"))
    var isTestingSpeed by mutableStateOf(false)
    var lastAutoSpeedTime by mutableStateOf("")

    // STATE CHO DOCKER POWER
    var isDockerRunning by mutableStateOf(false)
    var isTogglingDocker by mutableStateOf(false)

    // STATE CHO DOCKER MANAGER
    var showDockerDialog by mutableStateOf(false)
    var dockerContainers by mutableStateOf<List<DockerContainer>>(emptyList())
    // Quáº£n lÃ½ Nháº­t kÃ½ há»‡ thá»‘ng
    var systemLogs by mutableStateOf(listOf<SystemLog>())
    var isFetchingDocker by mutableStateOf(false)

    // STATE CHO OMV OVERVIEW
    var omvOverview by mutableStateOf(OmvOverview())

    // STATE CHO LAN WHITELIST (tÃ¡ch logic ra khá»i UI)
    var lanWhitelistIps by mutableStateOf<List<String>>(emptyList())
    var lanWhitelistSubnets by mutableStateOf<List<String>>(emptyList())
    var lanWhitelistLoading by mutableStateOf(true)
    var lanWhitelistError by mutableStateOf("")
    var lanWhitelistStatus by mutableStateOf("")

    // STATE CHO SMART ORGANIZER (tÃ¡ch logic ra khá»i UI)
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
                            showSystemNotification(appContext, 9011, "Äang táº¡o Thumbnail (" + thumbGenerated + " / " + thumbTotal + ")", "File hiá»‡n táº¡i: " + thumbLastFile, percent)
                        } else {
                            cancelSystemNotification(appContext, 9011)
                        }
                        }                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showSystemNotification(context: android.content.Context, id: Int, title: String, content: String, progress: Int? = null) {
        val channelId = "nas_background_tasks"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Tiáº¿n trÃ¬nh ngáº§m NAS", android.app.NotificationManager.IMPORTANCE_LOW)
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
        // Optimistic UI: cáº­p nháº­t tráº¡ng thÃ¡i ngay láº­p tá»©c Ä‘á»ƒ nÃºt pháº£n há»“i tá»©c thÃ¬
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
                        // Rollback náº¿u server tá»« chá»‘i
                        withContext(Dispatchers.Main) {
                            thumbPaused = action != "pause"
                        }
                    }
                }
                // Äá»£i server xá»­ lÃ½ xong rá»“i má»›i refresh (trÃ¡nh race condition)
                kotlinx.coroutines.delay(1500)
                fetchThumbStatus()
            } catch (e: Exception) {
                // Rollback + log lá»—i
                withContext(Dispatchers.Main) {
                    thumbPaused = action != "pause"
                    repository.addSystemLog("WARNING", "Thumbnail", "Toggle pause tháº¥t báº¡i: ${e.message?.take(80)}")
                }
            }
        }
    }

    // Äá»ŠNH NGHÄ¨A THÆ¯ Má»¤C THÃ™NG RÃC (Dáº¥u cháº¥m á»Ÿ Ä‘áº§u Ä‘á»ƒ áº©n thÆ° má»¥c trÃªn NAS)
    internal val TRASH_FOLDER_NAME = ".trash/"

    internal val urlStack = Stack<String>()

    var currentUrl by mutableStateOf("")
    // HÃ€M CONNECT_AND_LOAD Bá»Š XÃ“A Bá»Ž VÃŒ DÆ¯ THá»ªA. Sáº¼ DÃ™NG HÃ€M CONNECT CHÃNH THá»¨C Náº°M á»ž CUá»I FILE.

    fun openFolder(file: NasFile) {
        urlStack.push(currentUrl)
        currentUrl = if (file.path.endsWith("/")) file.path else "${file.path}/"

        // Sá»¬A Lá»–I: XÃ³a tráº¯ng mÃ n hÃ¬nh láº­p tá»©c Ä‘á»ƒ dá»n luá»“ng máº¡ng vÃ  báº¯t Ä‘áº§u táº£i giao diá»‡n má»›i trÆ¡n tru
        fileList = emptyList()
        isLoading = true

        // LOG: Ghi nháº­t kÃ½ má»Ÿ thÆ° má»¥c
        viewModelScope.launch(Dispatchers.IO) {
            // Removed folder navigation log
        }

        loadCurrentUrl()
    }
    fun openSpecificUrl(url: String, title: String) {
        // Fix cÃº phÃ¡p vÃ  Ä‘á»“ng bá»™ tiÃªu Ä‘á» Sub-menu
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
            if (title == "ThÃ¹ng rÃ¡c") {
                try { webDavManager.createFolder(targetUrl) } catch(e: Exception) {}
            }
            withContext(Dispatchers.Main) { loadCurrentUrl() }
        }
    }

    fun refresh() {
        // Sá»¬A Lá»–I REFRESH: PhÃ¢n loáº¡i Ä‘á»ƒ gá»i Ä‘Ãºng hÃ m truy váº¥n DB cho sub-menu
        if (isSpecialMode) {
            when (specialTitle) {
                "áº¢nh má»›i nháº¥t" -> showLatestPhotos()
                "Video gáº§n Ä‘Ã¢y" -> showRecentVideos()
                else -> loadCurrentUrl(forceRefresh = true) // Cho ThÃ¹ng rÃ¡c
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

            // Sá»¬A Lá»–I: NhÆ°á»ng toÃ n bá»™ bÄƒng thÃ´ng cho lá»‡nh lÃ¹i thÆ° má»¥c
            fileList = emptyList()
            isLoading = true

            // LOG: Ghi nháº­t kÃ½ lÃ¹i thÆ° má»¥c
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
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "áº¢nh má»›i nháº¥t" }
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            val photos = repository.getLatestPhotos()
            withContext(Dispatchers.Main) { fileList = photos; isLoading = false }
        }
    }

    fun showRecentVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Video gáº§n Ä‘Ã¢y" }
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            val videos = repository.getRecentVideos()
            withContext(Dispatchers.Main) { fileList = videos; isLoading = false }
        }
    }
    // TÃNH NÄ‚NG TÃŒM KIáº¾M TOÃ€N Cáº¦U
    fun searchGlobal(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "TÃ¬m kiáº¿m: $keyword"; urlStack.clear() }
            val results = try { repository.searchGlobal(keyword) } catch(e: Exception) { emptyList() }
            withContext(Dispatchers.Main) { fileList = results; isLoading = false }
        }
    }
    // TÃNH NÄ‚NG ÄIá»€U KHIá»‚N NGUá»’N VÃ€ Dá»ŠCH Vá»¤

    // Gá»¬I LINK Táº¢I XUá»NG Tá»ª XA CHO NAS (QBITTORRENT / WGET)
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
            } catch (e: Exception) { e.printStackTrace() }
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
            } catch (e: Exception) { e.printStackTrace() }
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

        // BÃ¡o UI Ä‘ang xá»­ lÃ½ thÃ´ng qua Notification do cháº¡y ngáº§m
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "TÃ¡c vá»¥ giáº£i nÃ©n ($fileName) Ä‘ang cháº¡y ngáº§m trÃªn NAS!"
        showCommonDialog = true

        // KIáº¾N TRÃšC Má»šI: Äáº©y sang LongRunningApiWorker (Foreground Service)
        // â†’ Táº¯t App váº«n cháº¡y, hiá»ƒn thá»‹ Notification tiáº¿n trÃ¬nh
        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "UNZIP")
            .putString("apiUrl", "${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/file/unzip")
            .putString("jsonBody", jsonBody)
            .putString("taskLabel", "Giáº£i nÃ©n $fileName")
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<LongRunningApiWorker>()
            .setInputData(inputData)
            .addTag("LONG_RUNNING_API")
            .build()

        val context = NasApplication.instance.applicationContext
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork("Unzip_$fileName", androidx.work.ExistingWorkPolicy.REPLACE, workRequest)

        // Láº¯ng nghe káº¿t quáº£ tá»« Worker
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
                            commonDialogMessage = message.ifEmpty { "Giáº£i nÃ©n thÃ nh cÃ´ng!" }
                            showCommonDialog = true
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = message.ifEmpty { "Giáº£i nÃ©n tháº¥t báº¡i!" }
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                val text = localApiClient.newCall(request).execute().use { it.body?.string() ?: "" }
                withContext(Dispatchers.Main) {
                    val o = try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
                    commonDialogMessage = if (o.optString("result") == "ok")
                        "âœ… ÄÃ£ gá»­i link cho qBittorrent. Theo dÃµi tiáº¿n trÃ¬nh á»Ÿ Dashboard."
                    else "âŒ Lá»—i: ${o.optString("error", "khÃ´ng pháº£n há»“i")}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                    showCommonDialog = true
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogMessage = "âŒ Lá»—i máº¡ng: ${e.message?.take(120)}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            }
        }
    }

    /** Upload 1 file .torrent len NAS â†’ qBittorrent.
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
                    ?: throw IllegalArgumentException("KhÃ´ng Ä‘á»c Ä‘Æ°á»£c ná»™i dung file")
                if (bytes.size < 64) throw IllegalArgumentException("File .torrent quÃ¡ nhá»")
                if (bytes[0].toInt().toChar() != 'd') throw IllegalArgumentException("File khÃ´ng pháº£i Ä‘á»‹nh dáº¡ng torrent há»£p lá»‡")

                val mediaType = "application/x-bittorrent".toMediaTypeOrNull()
                val filePart = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", safeName, bytes.toRequestBody(mediaType, 0, bytes.size))
                    .build()

                val req = okhttp3.Request.Builder()
                    .url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/torrent/add_file")
                    .post(filePart)
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                val client = localApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val text = client.newCall(req).execute().use { it.body?.string() ?: "" }
                val o = try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
                withContext(Dispatchers.Main) {
                    commonDialogMessage = if (o.optString("result") == "ok")
                        "âœ… ÄÃ£ gá»­i $safeName cho qBittorrent (${o.optInt("size")} bytes)"
                    else "âŒ Lá»—i: ${o.optString("error", "khÃ´ng pháº£n há»“i")}"
                    commonDialogType = if (o.optString("result") == "ok") com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS else com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogMessage = "âŒ Lá»—i upload torrent: ${e.message?.take(120)}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            }
        }
    }

    // ============ GHI HÃŒNH LIVESTREAM (TikTok / Facebook / YouTube) ============
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
                        .thenBy { if (it.speed.isNotBlank() && it.speed != "â€”") 1 else 0 }
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
    
    // Danh sÃ¡ch cÃ¡c stream Ä‘ang ghi
    var activeLivestreams = androidx.compose.runtime.mutableStateListOf<LivestreamJob>()
        private set
    internal var lastLivestreamServerSyncAt = 0L
    internal var lastLivestreamServerRecordingIds: Set<String> = emptySet()
    var livestreamMessage by mutableStateOf("")
        private set

    /** UI gá»i Ä‘á»ƒ xÃ³a message lá»—i, hiá»‡n láº¡i nÃºt "Báº®T Äáº¦U GHI" */
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
                throw IllegalStateException(result.optString("error", "NAS tá»« chá»‘i (${response.code})"))
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
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lá»—i: ${e.message?.take(80) ?: "ChÆ°a káº¿t ná»‘i NAS"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "NgÆ°á»i dÃ¹ng: thÃªm tÃ i khoáº£n theo dÃµi live @$clean.")
                // Báº¯t job ngay náº¿u user vá»«a thÃªm Ä‘ang live - khÃ´ng chá» 15p chu ká»³ Discovery.
                syncLivestreamStateWithServer(context)
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "NgÆ°á»i dÃ¹ng: thÃªm tÃ i khoáº£n @$clean tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lá»—i: ${e.message?.take(80) ?: "KhÃ´ng thÃªm Ä‘Æ°á»£c ngÆ°á»i dÃ¹ng"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "NgÆ°á»i dÃ¹ng: xoÃ¡ tÃ i khoáº£n theo dÃµi live @$username.")
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "NgÆ°á»i dÃ¹ng: xoÃ¡ tÃ i khoáº£n @$username tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lá»—i: ${e.message?.take(80) ?: "KhÃ´ng xoÃ¡ Ä‘Æ°á»£c ngÆ°á»i dÃ¹ng"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "NgÆ°á»i dÃ¹ng: cáº­p nháº­t khung loáº¡i trá»« TikTok Watch (${if (enabled) "báº­t" else "táº¯t"}, $start-$end).")
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "NgÆ°á»i dÃ¹ng: cáº­p nháº­t cáº¥u hÃ¬nh TikTok Watch tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "Lá»—i: ${e.message?.take(80) ?: "KhÃ´ng lÆ°u Ä‘Æ°á»£c cáº¥u hÃ¬nh"}" }
            }
        }
    }

    fun startLivestreamRecord(context: Context, url: String, quality: String = "best", referer: String = "", userAgent: String = "") {
        isStartingLivestream = true
        livestreamMessage = "Äang phÃ¢n tÃ­ch liÃªn káº¿t & káº¿t ná»‘i..."
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
                    throw IllegalStateException("ChÆ°a cÃ³ Ä‘á»‹a chá»‰ NAS há»£p lá»‡")
                }
                val requestBuilder = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/livestream/record")
                    .post(body)

                val user = SecurePrefsHelper.getUser(context)
                val pass = SecurePrefsHelper.getPass(context)
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                }

                // FIX: dÃ¹ng client cÃ³ timeout dÃ i hÆ¡n (60s) chá»‰ riÃªng cho call nÃ y â€”
                // preflight check TikTok cÃ³ thá»ƒ tá»‘n 20-30s (curl HTML + probe FLV).
                // KhÃ´ng tÄƒng timeout cá»§a client máº·c Ä‘á»‹nh vÃ¬ cÃ¡c endpoint khÃ¡c pháº£i
                // tráº£ káº¿t quáº£ nhanh.
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
                        repository.addSystemLog("INFO", "Livestream", "NgÆ°á»i dÃ¹ng: báº¯t Ä‘áº§u ghi livestream ${tiktokUsername.ifBlank { url.take(80) }} cháº¥t lÆ°á»£ng $quality.")

                        // ThÃªm vÃ o danh sÃ¡ch active (máº·c Ä‘á»‹nh tráº¡ng thÃ¡i recording)
                        withContext(Dispatchers.Main) {
                            if (activeLivestreams.none { it.jobId == jobId }) {
                                activeLivestreams.add(WebDavViewModel.LivestreamJob(jobId, platform, watchUsername = tiktokUsername))
                            }
                            livestreamMessage   = json.optString("message", "Äang khá»Ÿi Ä‘á»™ng ghi hÃ¬nh...")
                        }

                        // Khá»Ÿi Ä‘á»™ng Foreground Worker Ä‘á»™c láº­p vá»›i vÃ²ng Ä‘á»i app
                        LivestreamMonitorWorker.enqueue(context, jobId, host, platform)

                        // Observe tiáº¿n trÃ¬nh tá»« Worker Ä‘á»ƒ cáº­p nháº­t UI
                        observeLivestreamWorker(context)

                        // FIX: Tá»± Ä‘á»™ng thÃªm username vÃ o watcher list náº¿u chÆ°a cÃ³ â€”
                        // Ä‘áº£m báº£o má»—i lÃºc user ghi 1 live má»›i qua link, láº§n sau watcher
                        // sáº½ tá»± phÃ¡t hiá»‡n vÃ  auto-record. KhÃ´ng dá»±a vÃ o logic á»Ÿ Dialog
                        // (Ä‘á»ƒ robust trong má»i flow gá»i startLivestreamRecord).
                        if (tiktokUsername.isNotBlank() &&
                            tiktokLiveWatchUsers.none { it.username.equals(tiktokUsername, ignoreCase = true) }) {
                            addTikTokLiveWatchUser(context, tiktokUsername)
                        }
                    } else {
                        // FIX: server tra error CU THE qua field "error" + "reason"
                        // Map HTTP code de hien icon/mau dialog hop ly.
                        val errMsg = json.optString("error", "").ifBlank {
                            "Lá»—i NAS (HTTP ${response.code}): ${rawBody.take(150)}"
                        }
                        withContext(Dispatchers.Main) {
                            livestreamMessage = errMsg
                            commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = errMsg
                            showCommonDialog    = true
                        }
                        repository.addSystemLog("WARNING", "Livestream", "NgÆ°á»i dÃ¹ng: báº¯t Ä‘áº§u ghi livestream tháº¥t báº¡i: ${errMsg.take(120)}")
                    }
                }
            } catch (e: Exception) {
                // FIX: phan loai exception cu the thay vi "Lá»—i káº¿t ná»‘i NAS: null"
                val errMsg = when (e) {
                    is java.net.SocketTimeoutException ->
                        "NAS chÆ°a tráº£ káº¿t quáº£ ká»‹p khi phÃ¢n tÃ­ch link TikTok. KhÃ´ng táº¡o thÃªm phiÃªn trÃ¹ng; hÃ£y chá» tráº¡ng thÃ¡i ghi cáº­p nháº­t rá»“i thá»­ láº¡i náº¿u chÆ°a tháº¥y cháº¡y."
                    is java.net.ConnectException ->
                        "KhÃ´ng káº¿t ná»‘i Ä‘Æ°á»£c NAS. Kiá»ƒm tra: NAS cÃ³ Ä‘ang cháº¡y khÃ´ng? Tailscale cÃ³ báº­t khÃ´ng?"
                    is java.net.UnknownHostException ->
                        "KhÃ´ng tÃ¬m tháº¥y NAS (DNS/Tailscale lá»—i). Kiá»ƒm tra láº¡i Ä‘á»‹a chá»‰ káº¿t ná»‘i."
                    is javax.net.ssl.SSLException ->
                        "Lá»—i SSL: ${e.message ?: "Chá»©ng chá»‰ NAS khÃ´ng há»£p lá»‡"}"
                    else -> {
                        val raw = e.message?.take(200)
                        if (raw.isNullOrBlank()) "Lá»—i ${e.javaClass.simpleName} khÃ´ng cÃ³ chi tiáº¿t"
                        else "Lá»—i: $raw"
                    }
                }
                withContext(Dispatchers.Main) {
                    livestreamMessage   = errMsg
                    commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = errMsg
                    showCommonDialog    = true
                }
                repository.addSystemLog("WARNING", "Livestream", "NgÆ°á»i dÃ¹ng: báº¯t Ä‘áº§u ghi livestream tháº¥t báº¡i: ${errMsg.take(120)}")
            } finally {
                withContext(Dispatchers.Main) {
                    isStartingLivestream = false
                }
            }
        }
    }

    /** Gá»i 1 láº§n khi app má»Ÿ láº¡i â€” tá»± Ä‘á»“ng bá»™ láº¡i tráº¡ng thÃ¡i tá»« cÃ¡c Worker Ä‘ang cháº¡y ngáº§m */
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

    /** Gá»i ngáº§m Ä‘á»ƒ quÃ©t cÃ¡c luá»“ng Livestream bá»‹ "bá» quÃªn" (zombie streams) trÃªn NAS */
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
                                        speed = jobObj.optString("avg_speed", "â€”"),
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
                                // Náº¿u tiáº¿n trÃ¬nh Ä‘ang cháº¡y trÃªn NAS nhÆ°ng Ä‘iá»‡n thoáº¡i khÃ´ng biáº¿t (hoáº·c bá»‹ xoÃ¡ cache data)
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
                android.util.Log.e("LivestreamSync", "Lá»—i Ä‘á»“ng bá»™ tráº¡ng thÃ¡i livestream: ${e.message}")
                restoreLivestreamStateIfRunning(context)
            }
        }
    }

    private fun observeLivestreamWorker(context: Context) {
        // FIX: Cancel collector cÅ© trÆ°á»›c khi táº¡o má»›i, trÃ¡nh tÃ­ch lÅ©y N collectors cháº¡y song song
        // gÃ¢y thrashing UI khi má»—i collector Ä‘á»u process toÃ n bá»™ workInfoList
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
                            
                            // Worker hoÃ n táº¥t
                            if (info.state.isFinished || status !in listOf(null, "recording")) {
                                activeLivestreams.removeAt(index)
                                livestreamMessage = when (status) {
                                    "finished" -> "âœ… Ghi hÃ¬nh hoÃ n táº¥t!"
                                    "stopped"  -> "â¹ ÄÃ£ dá»«ng ghi hÃ¬nh"
                                    "timeout"  -> "â° Tá»± Ä‘á»™ng dá»«ng (quÃ¡ 12 giá»)"
                                    "error"    -> {
                                        val reason = progress.getString("error_reason")
                                            ?: info.outputData.getString("error_reason")
                                            ?: ""
                                        if (reason.isNotEmpty()) "Lá»—i: $reason" else "Lá»—i: Nguá»“n Stream bá»‹ ngáº¯t / File quÃ¡ nhá»!"
                                    }
                                    else       -> "Tráº¡ng thÃ¡i bÃ¡o cÃ¡o: $status"
                                }
                            } else {
                                // FIX: Táº¡o copy vá»›i tham sá»‘ má»›i thay vÃ¬ mutate var sau copy()
                                // Mutate var sau copy() khÃ´ng trigger Compose recomposition vÃ¬
                                // mutableStateListOf so sÃ¡nh object identity, khÃ´ng deep-compare
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
                // Cancel Worker trÆ°á»›c
                LivestreamMonitorWorker.cancelJob(context, jobId)

                // Gá»i NAS stop API
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
                repository.addSystemLog("INFO", "Livestream", "NgÆ°á»i dÃ¹ng: dá»«ng ghi livestream job $jobId.")
                withContext(Dispatchers.Main) {
                    activeLivestreams.removeAll { it.jobId == jobId }
                    livestreamMessage   = "â¹ ÄÃ£ dá»«ng ghi hÃ¬nh. File Ä‘ang Ä‘Æ°á»£c xá»­ lÃ½..."
                }
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "Livestream", "NgÆ°á»i dÃ¹ng: dá»«ng ghi livestream job $jobId tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { livestreamMessage = "Lá»—i dá»«ng ghi: ${e.message}" }
            }
        }
    }


    // ÄÃNH THá»¨C NAS Báº°NG WAKE-ON-LAN (MAGIC PACKET)
    
    private fun loadCurrentUrl(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            errorMessage = null
            
            // Sá»¬A Lá»–I CHÃ Máº NG Tá»ª PHASE 1: LUÃ”N LUÃ”N Káº¾T Ná»I UI Vá»šI CSDL TRÆ¯á»šC TIÃŠN!
            // Khi Paging Flow trÃ³i buá»™c vÃ o Room DB, má»i thay Ä‘á»•i dá»¯ liá»‡u tá»« NAS táº£i vá» sáº½ láº­p tá»©c báº¯n lÃªn UI má»™t cÃ¡ch Auto!
            _pagedFilesFlow.value = repository.getFilesStream(currentUrl).cachedIn(viewModelScope)

            // Láº¥y danh sÃ¡ch tÄ©nh Ä‘á»ƒ phá»¥c vá»¥ ImageViewerScreen
            val cached = repository.getCachedFiles(currentUrl)
            fileList = cached.map { 
                NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) 
            }.filter { !it.name.startsWith(".") || isSpecialMode }

            // Tá»I Æ¯U SMART REFRESH: Náº¿u khÃ´ng Ã©p buá»™c Refresh vÃ  Cache Ä‘Ã£ cÃ³ sáºµn dá»¯ liá»‡u thÃ¬ xong luÃ´n!
            if (!forceRefresh && cached.isNotEmpty()) {
                isLoading = false
                // Cháº¡y ngáº§m viá»‡c kiá»ƒm tra cáº­p nháº­t mÃ  khÃ´ng lÃ m treo UI
                launch(Dispatchers.IO) {
                    try { repository.getRemoteFilesAndCache(currentUrl) } catch (e: Exception) {}
                }
                return@launch
            }

            // Náº¿u lÃ  Force Refresh (vd: Vá»«a Login xong) hoáº·c Láº§n Ä‘áº§u vÃ o thÆ° má»¥c chÆ°a cÃ³ Cache -> Pháº£i Äá»£i
            isLoading = true

            try {
                // 3 & 4. Uá»· quyá»n cho Repository táº£i luá»“ng NAS vÃ  chÃ¨n toÃ n bá»™ vÃ o Room DB
                repository.getRemoteFilesAndCache(currentUrl)
                // Láº­p tá»©c Cáº­p nháº­t láº¡i FileList tÄ©nh cho cháº¿ Ä‘á»™ xem áº£nh Full-Screen
                val refreshedCached = repository.getCachedFiles(currentUrl)
                fileList = refreshedCached.map { 
                    NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) 
                }.filter { !it.name.startsWith(".") || isSpecialMode }

                // LOG + IP: Hiá»ƒn thá»‹ IP NAS sau tráº¡ng thÃ¡i káº¿t ná»‘i
                val nasHost = try { java.net.URL(currentUrl).host } catch (_: Exception) { "" }
                connectionStatus = if (nasHost.isNotEmpty()) "ÄÃ£ káº¿t ná»‘i LAN: $nasHost" else "ÄÃ£ káº¿t ná»‘i LAN"
            } catch (e: Exception) {
                connectionStatus = "Lá»—i káº¿t ná»‘i" // Ã‰p cáº­p nháº­t tráº¡ng thÃ¡i lá»—i ngay láº­p tá»©c dÃ¹ cÃ³ Cache hay khÃ´ng
                viewModelScope.launch(Dispatchers.IO) {
                    repository.addSystemLog("ERROR", "Browser", "Lá»—i táº£i danh sÃ¡ch: ${e.message?.take(100)}")
                }
                if (fileList.isEmpty()) {
                    errorMessage = friendlyError(e)
                }
                // FIX Lá»–I 5: Debounce - chá»‰ báº­t dialog lá»—i máº¡ng náº¿u cÃ¡ch láº§n trÆ°á»›c hÆ¡n 2 phÃºt
                val now = System.currentTimeMillis()
                if (now - lastNetworkErrorDialogAt > NETWORK_ERROR_DIALOG_COOLDOWN_MS) {
                    lastNetworkErrorDialogAt = now
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = "Máº¥t káº¿t ná»‘i dá»¯ liá»‡u mÃ¡y chá»§ NAS:\n${e.message}"
                    showCommonDialog = true
                }
            } finally {
                isLoading = false
            }
        }
    }

    // HELPER: ChÃ¨n TÃ¡c vá»¥ vÃ o HÃ ng Ä‘á»£i Offline WorkManager (TÃNH NÄ‚NG 5.I)
    private fun enqueueOfflineAction(context: Context, actionType: String, sourcePath: String, destPath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as NasApplication).database
                db.syncActionDao().insert(SyncAction(
                    actionType = actionType,
                    sourcePath = sourcePath,
                    destPath = destPath
                ))
                
                // BÃ¡o WorkManager cháº¡y khi cÃ³ máº¡ng
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val request = androidx.work.OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueue(request)
                
                // Hiá»ƒn thá»‹ Dialog bÃ¡o cho User
                withContext(Dispatchers.Main) {
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING
                    commonDialogMessage = "KhÃ´ng cÃ³ káº¿t ná»‘i. Lá»‡nh '$actionType' Ä‘Ã£ Ä‘Æ°á»£c Ä‘Æ°a vÃ o hÃ ng Ä‘á»£i ngoáº¡i tuyáº¿n."
                    showCommonDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lá»—i khi lÆ°u hÃ ng Ä‘á»£i ngoáº¡i tuyáº¿n: ${e.message}" }
            }
        }
    }

    fun deleteFile(context: Context, file: NasFile) {
        // Tá»I Æ¯U Cá»°C Äáº I: UI Láº¡c quan (Optimistic UI)
        // áº¨n file ngay láº­p tá»©c khá»i biáº¿n RAM mÃ  CHÆ¯A Cáº¦N Ä‘á»£i NAS pháº£n há»“i -> XÃ³a "Tá»©c thÃ¬" (0ms)
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }

        // BÃ“C TÃCH: Äáº©y viá»‡c liÃªn láº¡c máº¡ng NAS (cháº­m) vÃ o luá»“ng ngáº§m I/O, giáº£i phÃ³ng luá»“ng mÃ n hÃ¬nh UI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. TÃ¬m Ä‘Æ°á»ng dáº«n gá»‘c cá»§a á»• Ä‘Ä©a (VD: /Data N300/)
                val relativePath = file.path.removePrefix(webDavManager.currentBaseUrl).trimStart('/')
                val driveName = relativePath.substringBefore('/')
                val trashUrl = webDavManager.currentBaseUrl + driveName + "/" + TRASH_FOLDER_NAME

                // 2. Cháº·n xoÃ¡ vÄ©nh viá»…n náº¿u chÆ°a náº±m trong thÃ¹ng rÃ¡c
                if (!file.path.contains(TRASH_FOLDER_NAME)) {
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) {}
                    val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    var targetUrl = if (trashUrl.endsWith("/")) trashUrl + encodedName else "$trashUrl/$encodedName"
                    if (file.isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                    webDavManager.renameFile(file.path, targetUrl)
                    repository.addSystemLog("WARNING", "File Ops", "ÄÃ£ di chuyá»ƒn tá»‡p '${file.name}' vÃ o ThÃ¹ng rÃ¡c á»• $driveName.")
                } else {
                    webDavManager.deleteFile(file.path)
                    repository.addSystemLog("WARNING", "File Ops", "ÄÃ£ XÃ“A VÄ¨NH VIá»„N tá»‡p '${file.name}'.")
                }
                // TRIá»†T TIÃŠU refresh() VÄ¨NH VIá»„N: TrÃ¡nh táº£i láº¡i 5000 file chá»‰ vÃ¬ xÃ³a 1 tháº»
            } catch (e: Exception) {
                // Nhá»“i láº¡i file vÃ o giao diá»‡n náº¿u rá»›t máº¡ng
                withContext(Dispatchers.Main) { fileList = oldList }
                
                // TÃNH NÄ‚NG 5.I: Báº«y lá»—i vÃ  tá»‘ng vÃ o HÃ ng Äá»£i Offline
                repository.addSystemLog("WARNING", "File Ops", "XÃ³a tá»‡p '${file.name}' tháº¥t báº¡i, Ä‘Ã£ Ä‘Æ°a vÃ o hÃ ng Ä‘á»£i ngoáº¡i tuyáº¿n: ${e.message?.take(80)}")
                val relativePath = file.path.removePrefix(webDavManager.currentBaseUrl).trimStart('/')
                val driveName = relativePath.substringBefore('/')
                val trashUrl = webDavManager.currentBaseUrl + driveName + "/" + TRASH_FOLDER_NAME
                
                if (!file.path.contains(TRASH_FOLDER_NAME)) {
                    val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    var targetUrl = if (trashUrl.endsWith("/")) trashUrl + encodedName else "$trashUrl/$encodedName"
                    if (file.isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                    enqueueOfflineAction(context, "RENAME", file.path, targetUrl)
                } else {
                    enqueueOfflineAction(context, "DELETE", file.path)
                }
            }
        }
    }

    fun deleteMultipleFiles(context: Context, filesToDelete: List<NasFile>) {
        if (filesToDelete.isEmpty()) return

        // Tá»I Æ¯U Cá»°C Äáº I: UI Láº¡c quan cho HÃ€NG LOáº T FILE
        // CÃ¹ng lÃºc bá»‘c hÆ¡i 100+ file ra khá»i List Ä‘á»ƒ giao diá»‡n trá»‘ng ngay trong 0 mili-giÃ¢y!
        val pathsToDelete = filesToDelete.map { it.path }.toSet()
        fileList = fileList.filter { it.path !in pathsToDelete }

        // KIáº¾N TRÃšC Má»šI: Äáº©y toÃ n bá»™ tÃ¡c vá»¥ sang BatchOperationWorker (Foreground Service)
        // â†’ Tiáº¿n trÃ¬nh KHÃ”NG Bá»Š Há»¦Y khi App táº¯t, hiá»ƒn thá»‹ trÃªn Notification Bar
        enqueueBatchOperation(context, "DELETE", filesToDelete, "")
    }

    fun batchCopyFiles(context: Context, filesToCopy: List<NasFile>, destUrl: String) {
        if (filesToCopy.isEmpty()) return

        // KIáº¾N TRÃšC Má»šI: Äáº©y tÃ¡c vá»¥ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "COPY", filesToCopy, destUrl)
    }

    fun batchMoveFiles(context: Context, filesToMove: List<NasFile>, destUrl: String) {
        if (filesToMove.isEmpty()) return

        // Tá»‘i Æ°u UI láº¡c quan: Giáº¥u file ngay láº­p tá»©c náº¿u di chuyá»ƒn ra khá»i thÆ° má»¥c hiá»‡n táº¡i
        if (!destUrl.startsWith(currentUrl)) {
            val pathsToMove = filesToMove.map { it.path }.toSet()
            fileList = fileList.filter { it.path !in pathsToMove }
        }

        // KIáº¾N TRÃšC Má»šI: Äáº©y tÃ¡c vá»¥ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "MOVE", filesToMove, destUrl)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // DISPATCH ENGINE: Äáº©y tÃ¡c vá»¥ náº·ng sang Foreground Worker
    // Worker cháº¡y Ä‘á»™c láº­p vá»›i Activity â€” Táº¯t App váº«n hoáº¡t Ä‘á»™ng
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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
            errorMessage = "KhÃ´ng thá»ƒ chuáº©n bá»‹ tÃ¡c vá»¥ hÃ ng loáº¡t: ${e.message}"
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

        // Láº¯ng nghe tiáº¿n trÃ¬nh tá»« Worker Ä‘á»ƒ cáº­p nháº­t UI (náº¿u App Ä‘ang má»Ÿ)
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
                            // LÃ m má»›i danh sÃ¡ch file sau khi Worker hoÃ n táº¥t
                            if (operation == "COPY" || (operation == "MOVE" && destUrl.startsWith(currentUrl))) {
                                refresh()
                            }
                        }
                    }
                }
        }
    }

    fun restoreFile(context: Context, file: NasFile) {
        // Tá»I Æ¯U Cá»°C Äáº I: XÃ³a áº£o tá»©c thÃ¬ khá»i giao diá»‡n ThÃ¹ng rÃ¡c
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // KHÃ”I PHá»¤C: Di chuyá»ƒn file tá»« rÃ¡c vá» thÆ° má»¥c gá»‘c cá»§a NAS
                val targetUrl = webDavManager.currentBaseUrl + file.name
                webDavManager.renameFile(file.path, targetUrl)
                repository.addSystemLog("INFO", "File Ops", "ÄÃ£ khÃ´i phá»¥c tá»‡p '${file.name}' tá»« ThÃ¹ng rÃ¡c.")
                // Bá» refresh()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList }
                repository.addSystemLog("WARNING", "File Ops", "KhÃ´i phá»¥c tá»‡p '${file.name}' tháº¥t báº¡i, Ä‘Ã£ Ä‘Æ°a vÃ o hÃ ng Ä‘á»£i ngoáº¡i tuyáº¿n: ${e.message?.take(80)}")
                val targetUrl = webDavManager.currentBaseUrl + file.name
                enqueueOfflineAction(context, "RENAME", file.path, targetUrl)
            }
        }
    }

    fun restoreMultipleFiles(context: Context, filesToRestore: List<NasFile>) {
        if (filesToRestore.isEmpty()) return

        // Tá»I Æ¯U Cá»°C Äáº I: UI Láº¡c quan cho HÃ€NG LOáº T FILE
        val pathsToRestore = filesToRestore.map { it.path }.toSet()
        fileList = fileList.filter { it.path !in pathsToRestore }

        // KIáº¾N TRÃšC Má»šI: Äáº©y tÃ¡c vá»¥ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "RESTORE", filesToRestore, "")
    }
    fun renameFile(context: Context, file: NasFile, newName: String) {
        // Tá»I Æ¯U Cá»°C Äáº I: Äá»•i tÃªn áº£o trÃªn bá»™ nhá»› RAM -> Tá»‘c Ä‘á»™ hiá»ƒn thá»‹ 0s
        val oldList = fileList
        val newUrl = currentUrl + newName
        val renamedFile = file.copy(name = newName, path = newUrl)
        fileList = oldList.map { if (it.path == file.path) renamedFile else it }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDavManager.renameFile(file.path, newUrl)
                repository.addSystemLog("INFO", "File Ops", "Äá»•i tÃªn tá»‡p '${file.name}' thÃ nh '${newName}'.")
                // KhÃ´ng refresh() Ä‘á»ƒ chá»‘ng khá»±ng giao diá»‡n
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList } // HoÃ n nguyÃªn tÃªn cÅ©
                repository.addSystemLog("WARNING", "File Ops", "Äá»•i tÃªn '${file.name}' tháº¥t báº¡i, Ä‘Ã£ Ä‘Æ°a vÃ o hÃ ng Ä‘á»£i ngoáº¡i tuyáº¿n: ${e.message?.take(80)}")
                enqueueOfflineAction(context, "RENAME", file.path, newUrl)
            }
        }
    }
    fun createFolder(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                // Äáº£m báº£o URL thÆ° má»¥c má»›i káº¿t thÃºc báº±ng dáº¥u gáº¡ch chÃ©o '/'
                val newFolderUrl = currentUrl + folderName + "/"
                webDavManager.createFolder(newFolderUrl)
                repository.addSystemLog("SUCCESS", "File Ops", "ÄÃ£ táº¡o thÆ° má»¥c má»›i: '$folderName'")
                withContext(Dispatchers.Main) { refresh() } // Táº£i láº¡i danh sÃ¡ch sau khi táº¡o thÃ nh cÃ´ng
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "File Ops", "Táº¡o thÆ° má»¥c '$folderName' tháº¥t báº¡i, Ä‘Ã£ Ä‘Æ°a vÃ o hÃ ng Ä‘á»£i ngoáº¡i tuyáº¿n: ${e.message?.take(80)}")
                val newFolderUrl = currentUrl + folderName + "/"
                enqueueOfflineAction(context, "CREATE_FOLDER", newFolderUrl)
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }
    // === ÄÃ£ gá»¡ bá» tÃ­nh nÄƒng Upload láº» táº» vÃ  Äá»“ng bá»™ ===

    /**
     * checkSmartNetwork() â€“ Tá»± Ä‘á»™ng phÃ¡t hiá»‡n máº¡ng vÃ  chuyá»ƒn URL NAS phÃ¹ há»£p.
     *
     * Gá»i hÃ m nÃ y khi:
     *  - User vÃ o MainMenuScreen (resume app)
     *  - Dashboard refresh
     *  - User báº¥m nÃºt refresh thá»§ cÃ´ng
     *
     * CÆ¡ cháº¿:
     *  1. Ping gateway LAN (ASUS RT-N12: 192.168.100.254 port 80)
     *  2. Náº¿u PASS â†’ Äang á»Ÿ LAN â†’ reconnect báº±ng URL LAN (Gigabit nhanh)
     *  3. Náº¿u FAIL â†’ Ra ngoÃ i â†’ reconnect báº±ng URL Tailscale (100.90.135.102)
     */
    fun checkSmartNetwork(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Invalidate cache Ä‘á»ƒ buá»™c kiá»ƒm tra thá»±c sá»± (khÃ´ng dÃ¹ng káº¿t quáº£ cÅ©)
                SmartNetworkManager.invalidateCache()
                val activeUrl = SmartNetworkManager.getActiveBaseUrl(context)
                if (activeUrl.isEmpty()) return@launch
                
                val onLan = !isTailscaleUrl(activeUrl)
                withContext(Dispatchers.Main) {
                    isOnLan = onLan
                }

                // Náº¿u URL thá»±c táº¿ khÃ¡c URL Ä‘ang dÃ¹ng â†’ tá»± Ä‘á»™ng reconnect mÆ°á»£t
                val currentBase = webDavManager.currentBaseUrl
                val safeActive = if (activeUrl.endsWith("/")) activeUrl else "$activeUrl/"
                if (safeActive != currentBase && currentBase.isNotEmpty()) {
                    val user = webDavManager.currentUser
                    val pass = webDavManager.currentPass
                    withContext(Dispatchers.Main) {
                        connectionStatus = if (onLan) "Chuyá»ƒn sang LAN - Gigabit" else "Chuyá»ƒn sang Tailscale VPN"
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            webDavManager.connect(safeActive, user, pass)
                            webDavManager.initConnection()
                            
                            // Gá»i authorize Ä‘á»ƒ IP má»›i Ä‘Æ°á»£c thÃªm vÃ o whitelist/iptables trÃªn NAS
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
                        "Chuyá»ƒn máº¡ng: ${if (onLan) "LAN" else "Tailscale"} ($safeActive)"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("SmartSwitch", "Lá»—i kiá»ƒm tra máº¡ng thÃ´ng minh: ${e.message}")
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
                listenToLocalNasApi()
                launchDashboardRealtimeScheduler()
                launchMetricsPolling()
                restoreLivestreamStateIfRunning(context)
                fetchSmartData()
                fetchThumbStatus()
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
        connectionStatus = "ÄÃ£ dá»«ng Ä‘Äƒng nháº­p"
    }

    fun connect(urlList: List<String>, user: String, pass: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            var lastErrorDetail = "KhÃ´ng rÃµ"

            withContext(Dispatchers.Main) {
                isLoading = true
                connectionStatus = "Äang kiá»ƒm tra mÃ´i trÆ°á»ng LAN..."
                urlStack.clear()
            }

            // FIX Lá»–I 7 B: Äá»c credentials cÅ© trÆ°á»›c bÆ°á»›c lÆ°u táº¡m, Ä‘á»ƒ cÃ³ thá»ƒ REVERT náº¿u handshake tháº¥t báº¡i
            val context = NasApplication.instance
            val oldUrlList = SecurePrefsHelper.getUrlList(context)
            val oldUser = SecurePrefsHelper.getUser(context)
            val oldPass = SecurePrefsHelper.getPass(context)

            withContext(Dispatchers.IO) {
                SecurePrefsHelper.saveCredentials(context, urlList, user, pass)
            }

            // FIX: Thá»­ láº§n lÆ°á»£t tá»«ng URL (LAN â†’ Tailscale) mÃ  khÃ´ng gÃ¢y race condition
            // VÃ²ng láº·p tuáº§n tá»± trÃ¡nh lá»—i split-tunneling cache cá»§a Android
            val errorDetails = mutableListOf<String>()
            var connectedUrl = ""
            var result = false

            val result2 = withContext(Dispatchers.IO) {
                if (urlList.isEmpty()) {
                    lastErrorDetail = "KhÃ´ng cÃ³ URL Ä‘á»ƒ káº¿t ná»‘i"
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
                            connectionStatus = "Äang káº¿t ná»‘i: $safeUrl"
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
                                    channel.send(Pair(false, "$activeUrl: WebDAV tá»« chá»‘i xÃ¡c thá»±c (HTTP ${response.code})"))
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("NAS_AUTH", "Lá»—i káº¿t ná»‘i $activeUrl: ${e.message}")
                            channel.send(Pair(false, "$activeUrl: ${e.message ?: "Máº¡ng quÃ¡ háº¡n"}"))
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
                        connectionStatus = "ÄÃ£ káº¿t ná»‘i: $successUrl"
                        isOnLan = !isTailscaleUrl(successUrl)
                    }

                    webDavManager.connect(successUrl, user, pass)
                    SecurePrefsHelper.saveCredentials(NasApplication.instance, urlList, user, pass)
                    repository.addSystemLog("SUCCESS", "Network", "Truy cáº­p WebDAV thÃ nh cÃ´ng qua User '$user' táº¡i IP: $successUrl")

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
                                android.util.Log.w("NAS_AUTH", "API Phá»¥ Warning: ${e.message}")
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
                    connectionStatus = "ÄÃ£ xÃ¡c thá»±c thÃ nh cÃ´ng"
                    onSuccess()
                } else {
                    connectionStatus = "Lá»—i xÃ¡c thá»±c"
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
        // Cáº­p nháº­t tráº¡ng thÃ¡i Auto Backup tá»« WorkManager
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
                            autoBackupCurrentFile = workInfo.progress.getString("fileName") ?: "Äang sao lÆ°u..."
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

        // KIáº¾N TRÃšC Má»šI: Äá»“ng bá»™ hÃ³a khÃ©p kÃ­n (KhÃ´i phá»¥c UI State khi App tÃ¡i khá»Ÿi Ä‘á»™ng tá»« cÃµi cháº¿t)
        val workManager = androidx.work.WorkManager.getInstance(NasApplication.instance.applicationContext)
        
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("BATCH_OPERATION").collect { workInfos ->
                val active = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                if (active != null) {
                    isBatchProcessing = true
                    batchProcessProgress = active.progress.getInt("percent", 0).toFloat() / 100f
                    batchProcessCurrentFile = active.progress.getString("currentFile") ?: "KhÃ´i phá»¥c Ä‘á»“ng bá»™..."
                } else if (isBatchProcessing) {
                    isBatchProcessing = false
                    refresh() // Cáº­p nháº­t láº¡i danh sÃ¡ch file khi Background Worker vá»«a hoÃ n táº¥t
                }
            }
        }

        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("STREAM_PIPE_TASK").collect { workInfos ->
                val active = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                if (active != null) {
                    isStreamPiping = true
                    streamPipeStatus = "KhÃ´i phá»¥c Ä‘á»“ng bá»™: " + (active.progress.getString("status") ?: "Äang táº£i ngáº§m...")
                    streamPipeProgress = active.progress.getInt("progress", 0).toFloat() / 100f
                    streamPipeSpeedStr = active.progress.getString("speedStr") ?: "Äá»“ng bá»™..."
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
                        organizingLegacyResult = active.progress.getString("status") ?: "Äang gom video ngáº§m..."
                    }
                }
            }
        }
    
        listenToLocalNasApi()

        // FIX A2: Thay vÃ²ng láº·p polling while(true){delay(32)} báº±ng combine() trÃªn StateFlow.
        // CÅ©: VÃ²ng láº·p cháº¡y liÃªn tá»¥c @30fps ká»ƒ cáº£ khi khÃ´ng scan â†’ tiÃªu hao CPU/pin vÃ´ Ã­ch.
        // Má»›i: Chá»‰ emit khi má»™t trong cÃ¡c StateFlow thá»±c sá»± thay Ä‘á»•i â†’ 0% CPU khi idle.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                DuplicateProgressState.stage,
                DuplicateProgressState.currentFolderUrl,
                DuplicateProgressState.percent,
                DuplicateProgressState.scannedCount,
                DuplicateProgressState.elapsedTime
            ) { stage, folderUrl, percent, scanned, elapsed ->
                // Tráº£ vá» tuple Ä‘á»ƒ trigger collector khi Báº¤T Ká»² field nÃ o thay Ä‘á»•i
                arrayOf<Any?>(stage, folderUrl, percent, scanned, elapsed)
            }.collect {
                // Äá»“ng bá»™ toÃ n bá»™ state tá»« DuplicateProgressState â†’ ViewModel state
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

                // FIX (BUG: dialog tá»± pop-up láº¡i khi user báº¥m Thu nhá»):
                // Chá»‰ cáº­p nháº­t isWorkerRunning â€” KHÃ”NG tá»± Ä‘á»™ng set isScanningDuplicates = true.
                // Dialog hiá»ƒn thá»‹ do user chá»§ Ä‘á»™ng má»Ÿ (qua nÃºt QuÃ©t hoáº·c chip "Thu nhá»").
                // TrÆ°á»›c Ä‘Ã¢y, má»—i tick progress collector Ä‘áº·t isScanningDuplicates=true ->
                // user khÃ´ng thá»ƒ Thu nhá»/Há»§y/Táº¡m dá»«ng Ä‘Æ°á»£c vÃ¬ dialog tá»± báº­t láº¡i 30ms sau.
                val stage = scanDuplicatesStage
                if (stage != "HoÃ n táº¥t" && stage.isNotEmpty() && stage != "Khá»Ÿi Ä‘á»™ng...") {
                    isWorkerRunning = true
                } else if (stage == "HoÃ n táº¥t") {
                    isWorkerRunning = false
                }
            }
        }

        // Khá»Ÿi Ä‘á»™ng vÃ²ng láº·p kiá»ƒm tra sá»©c khoáº» máº¡ng (Ping ICMP siÃªu nháº¹)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                if (webDavManager.currentBaseUrl.isNotEmpty()) {
                    val ms = webDavManager.checkPingServer()
                    withContext(Dispatchers.Main) { networkPingMs = ms }
                    // Giao thá»©c ICMP Ping tá»‘n háº§u nhÆ° khÃ´ng Ä‘Ã¡ng biá»ƒu Ä‘á»“ mÃ¡y, cho phÃ©p quÃ©t 3s/láº§n!
                    kotlinx.coroutines.delay(3000)
                } else {
                    // Náº¿u chÆ°a Login xong thÃ¬ Ä‘á»£i 1s há»i láº¡i, trÃ¡nh viá»‡c báº¯t User Ä‘á»£i táº­n 30s má»›i chá»c Ping
                    kotlinx.coroutines.delay(1000)
                }
            }
        }

        // Khá»Ÿi Ä‘á»™ng vÃ²ng láº·p láº¥y metrics biá»ƒu Ä‘á»“:
        // Chá» cho URL sáºµn sÃ ng rá»“i má»›i fetch láº§n Ä‘áº§u, sau Ä‘Ã³ poll má»—i 30s
        launchMetricsPolling()
        launchDashboardRealtimeScheduler()
    }

    // Dá»n cÃ¡c listener (náº¿u cÃ³)

    // =======================================================
    // ======== BIá»‚U Äá»’ GIÃM SÃT + BÃO CÃO NGÃ€Y ============
    // =======================================================

    fun launchMetricsPolling() {
        metricsPollingJob?.cancel()
        metricsPollingJob = viewModelScope.launch(Dispatchers.IO) {
            // Chá» tá»‘i Ä‘a 60s cho Ä‘áº¿n khi URL sáºµn sÃ ng (trÃ¡nh fetch khi chÆ°a login)
            var waited = 0
            while (isActive && webDavManager.currentBaseUrl.isEmpty() && waited < 60) {
                delay(1_000L)
                waited++
            }
            // Láº¥y láº§n Ä‘áº§u ngay sau khi URL sáºµn sÃ ng
            if (isActive && webDavManager.currentBaseUrl.isNotEmpty()) {
                fetchMetricsHistory(metricsHours)
                startRealtimeAlerts()
            }
            // Lich su bieu do chi nap nen. Diem realtime duoc append rieng tu cache nhe.
            while (isActive) {
                delay(if (AppConfig.IS_APP_FOREGROUND) 600_000L else 1_800_000L)
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
                            metricsError = "Lá»—i HTTP ${resp.code}: $bodyStr"
                        }
                        return@launch
                    }
                    val json = org.json.JSONObject(if (bodyStr.isEmpty()) "{}" else bodyStr)
                    if (json.has("error")) {
                        withContext(Dispatchers.Main) { metricsError = json.optString("error") }
                        return@launch
                    }
                    val timestamps = json.optJSONArray("timestamps") ?: run {
                        withContext(Dispatchers.Main) { metricsError = "Server tráº£ vá» dá»¯ liá»‡u khÃ´ng há»£p lá»‡" }
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
                withContext(Dispatchers.Main) { metricsError = "Nháº¥n LÃ m má»›i Ä‘á»ƒ thá»­ láº¡i: ${e.message?.take(80)}" }
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
            finally {
                withContext(Dispatchers.Main) { isDailyReportLoading = false }
            }
        }
    }

    // =======================================================
    // ======== CÃC HÃ€M Xá»¬ LÃ API Ná»˜I Bá»˜ (LOCAL NAS API) ======
    // =======================================================

    // CÃ¡c hÃ m láº¯ng nghe System Monitor Ä‘Ã£ Ä‘Æ°á»£c chuyá»ƒn ra SystemMonitorHelper.kt

    // ============ AI SMART PHOTOS ============
    var aiCategories by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var aiTotal by mutableStateOf(0)
    var aiLastScan by mutableStateOf("")
    var aiRunning by mutableStateOf(false)
    var aiStatus by mutableStateOf("ChÆ°a cÃ³ dá»¯ liá»‡u")
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
                withContext(Dispatchers.Main) { aiStatus = "Lá»—i káº¿t ná»‘i: ${e.message?.take(60)}" }
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
                    val msg = json.optString("message", "Äang quÃ©t phÃ¢n loáº¡i áº£nh...")
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
                    val msg = res.optString("message", "HoÃ n táº¥t dá»n ThÃ¹ng rÃ¡c!")
                    repository.addSystemLog("INFO", "File Ops", "NgÆ°á»i dÃ¹ng Ä‘Ã£ thá»±c hiá»‡n XÃ“A THÃ™NG RÃC: $msg")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                        commonDialogMessage = msg
                        showCommonDialog = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = "Lá»—i dá»n rÃ¡c: ${e.message}"
                    showCommonDialog = true
                }
            }
        }
    }

    // ============ GUEST PASS ============

    /**
     * Gá»i POST /api/guest/create â†’ NAS táº¡o FTP user táº¡m thá»i read-only.
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
                            "ÄÃ£ cáº¥p Guest FTP: user='${pass.username}', háº¿t háº¡n sau $durationMinutes phÃºt")
                    } else {
                        val errBody = resp.body?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            guestPassError = "NAS tá»« chá»‘i (${resp.code}): $errBody"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guestPassError = "Lá»—i káº¿t ná»‘i API: ${e.message}"
                }
                repository.addSystemLog("ERROR", "GuestPass", "Táº¡o Guest Pass lá»—i: ${e.message?.take(80)}")
            } finally {
                withContext(Dispatchers.Main) { isGuestPassLoading = false }
            }
        }
    }

    /**
     * Gá»i POST /api/guest/revoke â†’ NAS xÃ³a FTP user táº¡m thá»i.
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
     * Gá»­i link video tá»›i NAS â†’ NAS cháº¡y yt-dlp ngáº§m â†’ lÆ°u vÃ o Downloads/social/.
     * Äiá»‡n thoáº¡i KHÃ”NG tá»‘n 1MB dung lÆ°á»£ng.
     */
    fun requestSocialDownload(url: String, saveFolder: String = AppConfig.SOCIAL_DOWNLOAD_FOLDER) {
        if (url.isBlank() || isSocialExtracting) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isSocialExtracting = true
                socialExtractStatus = "Äang gá»­i lá»‡nh tá»›i NAS..."
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

                // Timeout dÃ i hÆ¡n vÃ¬ NAS cáº§n phÃ¢n giáº£i tÃªn miá»n + báº¯t link
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
                            isOk -> "âœ… NAS Ä‘Ã£ nháº­n lá»‡nh táº£i video!\nVideo sáº½ Ä‘Æ°á»£c táº£i ngáº§m vÃ  lÆ°u vÃ o $saveFolder."
                            else -> "âŒ Lá»—i (${resp.code}): ${msg.take(100)}"
                        }
                        socialExtractStatus = statusText
                        val histItem = SocialDownloadItem(url, platform, isOk)
                        // FIX: capped O(1) prepend thay vi concat O(n). Cap 50 item de tranh growth vo tan.
                        socialDownloadHistory = (listOf(histItem) + socialDownloadHistory).take(50)
                    }
                    repository.addSystemLog(
                        if (isOk) "SUCCESS" else "ERROR",
                        "SocialExtract",
                        "[$platform] $url â†’ ${if (isOk) "OK" else "Lá»—i ${resp.code}"}"
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
                    socialExtractStatus = "âŒ Lá»—i káº¿t ná»‘i API: ${e.message?.take(80)}"
                    socialDownloadHistory = (listOf(SocialDownloadItem(url, "KhÃ´ng rÃµ", false)) + socialDownloadHistory).take(50)
                }
                repository.addSystemLog("ERROR", "SocialExtract", "Lá»—i gá»­i yt-dlp: ${e.message?.take(80)}")
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
                        // Khi job_id khÃ´ng cÃ²n trong list, tÃ¡c vá»¥ táº£i Ä‘Ã£ hoÃ n thÃ nh
                        withContext(Dispatchers.Main) {
                            commonDialogMessage = "âœ… Táº£i video ($platform) hoÃ n táº¥t!\nÄÃ£ táº£i xong vÃ  lÆ°u vÃ o thÆ° má»¥c $saveFolder"
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                            showCommonDialog = true
                        }
                        repository.addSystemLog("SUCCESS", "SocialDownload", "Táº£i video $platform hoÃ n táº¥t. LÆ°u táº¡i: $saveFolder ($url)")
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
            else -> "KhÃ¡c"
        }
    }

    // ============ STREAM PIPING ENGINE ============
    //
    // Triáº¿t lÃ½: Äiá»‡n thoáº¡i = á»ng nÆ°á»›c (Pipe).
    //   CDN Server â”€â”€[OkHttp GET stream]â”€â”€â–¶ Phone RAM buffer â”€â”€[WebDAV PUT]â”€â”€â–¶ NAS HDD
    //
    // Äiá»‡n thoáº¡i KHÃ”NG lÆ°u file. Má»—i chunk 128KB Ä‘á»c xong bÆ¡m lÃªn ngay.
    // Tá»•ng RAM dÃ¹ng: ~256KB (2 buffer chunk) báº¥t ká»ƒ video to bao nhiÃªu.

    /**
     * Báº¯t Ä‘áº§u Stream Piping tá»« [sourceUrl] (link MP4 CDN Ä‘Ã£ bÃ³c) â†’ WebDAV NAS.
     *
     * @param sourceUrl  Link video CDN trá»±c tiáº¿p (Ä‘Ã£ giáº£i mÃ£, cÃ³ thá»ƒ stream)
     * @param fileName   TÃªn file lÆ°u trÃªn NAS
     */
    fun startStreamPipe(sourceUrl: String, fileName: String) {
        streamPipeJob?.cancel()

        isStreamPiping = true
        streamPipeProgress = 0f
        streamPipeSpeedStr = "Äang káº¿t ná»‘i..."
        streamPipeEtaStr = "--"
        streamPipeStatus = "â³ Äang truyá»n video qua tÃ¡c vá»¥ ná»n..."

        // KIáº¾N TRÃšC Má»šI: Äáº©y sang StreamPipeWorker (Foreground Service)
        // â†’ Táº¯t App váº«n bÆ¡m video liÃªn tá»¥c, Notification hiá»ƒn thá»‹ % tiáº¿n trÃ¬nh
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
            streamPipeStatus = "KhÃ´ng thá»ƒ chuáº©n bá»‹ táº£i video: ${e.message}"
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

        // Láº¯ng nghe tiáº¿n trÃ¬nh tá»« Worker
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
                            streamPipeStatus = "ðŸ“¡ ${formatFileSize(bytesRead)} / ${if (totalBytes > 0) formatFileSize(totalBytes) else "?"}"
                        }

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            streamPipeProgress = 1f
                            isStreamPiping = false
                            streamPipeStatus = message.ifEmpty { "âœ… HoÃ n táº¥t!" }
                            // LÆ°u lá»‹ch sá»­
                            val platform = detectSocialPlatform(sourceUrl)
                            socialDownloadHistory = (listOf(SocialDownloadItem(sourceUrl, platform, true)) + socialDownloadHistory).take(50)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            isStreamPiping = false
                            streamPipeStatus = "âŒ ${message.ifEmpty { "Lá»—i truyá»n video" }}"
                        } else if (workInfo.state == androidx.work.WorkInfo.State.CANCELLED) {
                            isStreamPiping = false
                            streamPipeStatus = "ðŸ›‘ ÄÃ£ há»§y bá»Ÿi ngÆ°á»i dÃ¹ng"
                            streamPipeProgress = 0f
                        }
                    }
                }
        }
    }

    /** Há»§y Stream Pipe Worker Ä‘ang cháº¡y giá»¯a chá»«ng. */
    fun cancelStreamPipe() {
        val context = NasApplication.instance.applicationContext
        _activeStreamPipeWorkId?.let { id ->
            androidx.work.WorkManager.getInstance(context).cancelWorkById(id)
        }
        streamPipeJob?.cancel()
        isStreamPiping = false
        streamPipeStatus = "ðŸ›‘ ÄÃ£ há»§y"
        streamPipeProgress = 0f
    }

    // â”€â”€ Format helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun formatFileSize(bytes: Long): String = com.nas.naswebdav.utils.FormatUtils.formatBytes(bytes)

    private fun formatEta(seconds: Long): String = when {
        seconds <= 0  -> "--"
        seconds < 60  -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    // ==========================================
    // TRáº NG THÃI GIAO DIá»†N SMART SYNC
    // (ÄÃ£ xÃ³a SmartSync theo yÃªu cáº§u táº­p trung Auto-Backup)

    // ==========================================
    // PHÃ‚N LOáº I VIDEO CÅ¨ (Legacy Videos)
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

        // KIáº¾N TRÃšC Má»šI: Äáº©y sang LongRunningApiWorker (Foreground Service)
        val host = try { java.net.URL(webDavManager.currentBaseUrl).host } catch (_: Exception) {
            organizingLegacyRunning = false
            organizingLegacyResult = "Lá»—i: ChÆ°a káº¿t ná»‘i NAS"
            return
        }

        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "ORGANIZE")
            .putString("apiUrl", "${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/tools/organize_legacy_videos")
            .putString("jsonBody", "")
            .putString("taskLabel", "Gom video cÅ©")
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
                            organizingLegacyResult = message.ifEmpty { "HoÃ n táº¥t!" }
                            refresh()
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            organizingLegacyRunning = false
                            organizingLegacyResult = message.ifEmpty { "Lá»—i gom video" }
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
                            // CHáº¶N Bá»˜ Lá»ŒC RÃC: Náº¿u NAS tráº£ vá» tá»‡p < 2KB thÃ¬ 99% Ä‘Ã³ lÃ  Icon Play bÃ¡o lá»—i, ta tá»« chá»‘i!
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
    // TÃNH NÄ‚NG: Cáº­p nháº­t thá»§ cÃ´ng (Manual Sync)
    // BO QUÃ‰T RÃC khoi flow nay theo yeu cau user â€” Sync Anh chi nen chay AutoBackup
    // (upload anh moi). Quet trung lap la tac vu nang ca cho phone va NAS, chi chay
    // tu dong theo lich tuan tai 3h sang khi NAS ranh, hoac do user chu dong khoi.
    fun triggerManualBackup(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        isAutoBackupRunning = true
        autoBackupProgress = 0f
        autoBackupCurrentFile = "Äang xáº¿p hÃ ng Ä‘á»“ng bá»™..."
        autoBackupSourcePath = "Thiáº¿t bá»‹ mÃ¡y tráº¡m"
        autoBackupDestPath = ""
        autoBackupProcessedCount = 0
        autoBackupTotalCount = 0
        autoBackupElapsedTime = 0L

        // KÃ­ch hoáº¡t AutoBackup ngay láº­p tá»©c (upload anh dien thoai len NAS)
        val backupRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.AutoBackupWorker>()
            .addTag("com.nas.naswebdav.AutoBackupWorker")
            .addTag("MANUAL_AUTO_BACKUP")
            .build()
        workManager.enqueueUniqueWork("ManualAutoBackupWork", androidx.work.ExistingWorkPolicy.REPLACE, backupRequest)
        logUserAction("AutoBackup", "cháº¡y Ä‘á»“ng bá»™ áº£nh thá»§ cÃ´ng lÃªn NAS.")
        // Cáº­p nháº­t Toast hoáº·c Tráº¡ng thÃ¡i UI Ä‘á»ƒ User biáº¿t
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "ÄÃ£ ra lá»‡nh Ä‘á»“ng bá»™ áº£nh lÃªn NAS!"
        showCommonDialog = true
    }

    fun toggleAutoBackupPause() {
        val newState = !AutoBackupState.isPaused.value
        AutoBackupState.isPaused.value = newState
        autoBackupIsPaused = newState
        logUserAction("AutoBackup", if (newState) "tam dung Auto-Backup." else "Tiáº¿p tá»¥c Äá»“ng bá»™ tá»± Ä‘á»™ng.")
    }

    // ==========================================
    // THIáº¾T Láº¬P HOáº T Äá»˜NG QUáº T (FAN CONTROL)
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
                nasConfigBackupMessage = "Äang táº¡o backup..."
            }
            try {
                val base = currentUrl.toApiBaseUrl()
                // Empty JSON body so server accepts POST
                val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/backup/create")
                    .post(body)
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                // dung client co read timeout dai (60s) vi tar.gz nhieu file co the cham
                val client = localApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(text)
                    val msg = if (resp.isSuccessful) {
                        "ÄÃ£ táº¡o: ${json.optString("filename")} (${json.optString("size_human")})"
                    } else {
                        "Lá»—i táº¡o backup: ${json.optString("error", "HTTP ${resp.code}")}"
                    }
                    repository.addSystemLog(if (resp.isSuccessful) "SUCCESS" else "WARNING", "NasBackup", "NgÆ°á»i dÃ¹ng: táº¡o backup cáº¥u hÃ¬nh NAS ${if (resp.isSuccessful) "thÃ nh cÃ´ng ${json.optString("filename")}" else "tháº¥t báº¡i HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) { nasConfigBackupMessage = msg }
                }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "NasBackup", "NgÆ°á»i dÃ¹ng: táº¡o backup cáº¥u hÃ¬nh NAS tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lá»—i: ${e.message}" }
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "NasBackup", "NgÆ°á»i dÃ¹ng: xoÃ¡ backup cáº¥u hÃ¬nh '$filename' ${if (resp.isSuccessful) "thÃ nh cÃ´ng" else "tháº¥t báº¡i HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) {
                        nasConfigBackupMessage = if (resp.isSuccessful) "ÄÃ£ xoÃ¡ $filename"
                        else "Lá»—i xoÃ¡: ${org.json.JSONObject(text).optString("error","HTTP ${resp.code}")}"
                    }
                }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "NasBackup", "NgÆ°á»i dÃ¹ng: xoÃ¡ backup '$filename' tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lá»—i xoÃ¡: ${e.message}" }
            }
        }
    }

    fun restoreNasConfigBackup(filename: String) {
        if (isRestoringNasConfigBackup) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isRestoringNasConfigBackup = true
                nasConfigBackupMessage = "Äang khÃ´i phá»¥c..."
            }
            try {
                val base = currentUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("filename", filename).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/backup/restore")
                    .post(body)
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                // Restore goi systemctl restart nen co the mat 10-20s
                val client = localApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    val json = try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
                    val msg = if (resp.isSuccessful) {
                        "ÄÃ£ khÃ´i phá»¥c ${json.optInt("restored_count")} file. Services restart: " +
                            (json.optJSONArray("services_restarted")?.toString() ?: "(none)")
                    } else {
                        "Lá»—i khÃ´i phá»¥c: ${json.optString("error", "HTTP ${resp.code}")}"
                    }
                    repository.addSystemLog(if (resp.isSuccessful) "WARNING" else "ERROR", "NasBackup", "NgÆ°á»i dÃ¹ng: khÃ´i phá»¥c cáº¥u hÃ¬nh tá»« '$filename' ${if (resp.isSuccessful) "thÃ nh cÃ´ng" else "tháº¥t báº¡i HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) { nasConfigBackupMessage = msg }
                }
            } catch (e: Exception) {
                repository.addSystemLog("ERROR", "NasBackup", "NgÆ°á»i dÃ¹ng: khÃ´i phá»¥c cáº¥u hÃ¬nh tá»« '$filename' tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lá»—i khÃ´i phá»¥c: ${e.message}" }
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

    fun fetchDiskHealth() {
        if (isFetchingDiskHealth) return
        isFetchingDiskHealth = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/disk/health")
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
        dashboardRealtimeJob?.cancel()
        dashboardRealtimeJob = viewModelScope.launch(Dispatchers.IO) {
            var waited = 0
            while (isActive && webDavManager.currentBaseUrl.isEmpty() && waited < 60) {
                delay(1_000L)
                waited++
            }
            var lastHeavyRefresh = 0L
            var lastStorageRefresh = 0L
            var lastLogRefresh = 0L
            var lastSmartRefresh = 0L
            var lastInsightsRefresh = 0L
            var lastRealtimeMetric = 0L
            while (isActive) {
                val hasUrl = webDavManager.currentBaseUrl.isNotEmpty()
                if (hasUrl) {
                    val now = System.currentTimeMillis()
                    val realtimeInterval = if (AppConfig.IS_APP_FOREGROUND) 5_000L else 20_000L
                    if (now - lastRealtimeMetric >= realtimeInterval) {
                        fetchRealtimeMetricPoint()
                        lastRealtimeMetric = now
                    }
                    if (lastHeavyRefresh == 0L || now - lastHeavyRefresh >= 300_000L) {
                        fetchOmvOverview()
                        fetchDailyReport()
                        lastHeavyRefresh = now
                    }
                    if (now - lastLogRefresh >= if (AppConfig.IS_APP_FOREGROUND) 15_000L else 60_000L) {
                        loadSystemLogs()
                        lastLogRefresh = now
                    }
                    if (now - lastStorageRefresh >= if (AppConfig.IS_APP_FOREGROUND) 60_000L else 180_000L) {
                        fetchStorageUsage()
                        lastStorageRefresh = now
                    }
                    if (now - lastSmartRefresh >= 300_000L) {
                        fetchSmartData()
                        lastSmartRefresh = now
                    }
                    if (now - lastInsightsRefresh >= if (AppConfig.IS_APP_FOREGROUND) 15_000L else 60_000L) {
                        fetchNasInsights()
                        lastInsightsRefresh = now
                    }
                }
                delay(if (AppConfig.IS_APP_FOREGROUND) 2_000L else 10_000L)
            }
        }
    }

    fun fetchStorageUsage() {
        if (isFetchingStorageUsage) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isFetchingStorageUsage = true }
            try {
                val base = currentUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$base/api/storage/usage")
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val ok = resp.isSuccessful && org.json.JSONObject(body).optBoolean("saved", false)
                    repository.addSystemLog(
                        if (ok) "INFO" else "WARNING",
                        "BackupSchedule",
                        "NgÆ°á»i dÃ¹ng: ${if (ok) "lÆ°u" else "lÆ°u tháº¥t báº¡i"} lá»‹ch backup (${if (newSchedule.enabled) "báº­t" else "táº¯t"}, ${newSchedule.frequency}, ${newSchedule.hour}h, giá»¯ ${newSchedule.retentionCount} báº£n)."
                    )
                    withContext(Dispatchers.Main) {
                        backupScheduleMessage = if (ok) "ÄÃ£ lÆ°u lá»‹ch backup" else "Lá»—i lÆ°u"
                    }
                }
                fetchBackupSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "BackupSchedule", "NgÆ°á»i dÃ¹ng: lÆ°u lá»‹ch backup tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { backupScheduleMessage = "Lá»—i: ${e.message}" }
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
                val name = label.ifBlank { path }.ifBlank { "USB KhÃ´ng tÃªn" }
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
                if (base.isBlank()) throw IllegalStateException("ChÆ°a cÃ³ Ä‘á»‹a chá»‰ NAS há»£p lá»‡")
                val path = if (compact) "/api/usb_import/status?compact=1" else "/api/usb_import/status"
                val req = okhttp3.Request.Builder()
                    .url("$base$path")
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i táº£i USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val state = parseUsbImportState(org.json.JSONObject(body))
                    withContext(Dispatchers.Main) {
                        usbImportState = state
                        usbImportMessage = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i: ${e.message}" }
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
                if (base.isBlank()) throw IllegalStateException("ChÆ°a cÃ³ Ä‘á»‹a chá»‰ NAS há»£p lá»‡")
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i lÆ°u USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val ok = resp.isSuccessful && o.optBoolean("saved", false)
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(
                        if (ok) "INFO" else "WARNING",
                        "USBImport",
                        "NgÆ°á»i dÃ¹ng: ${if (ok) "lÆ°u" else "lÆ°u tháº¥t báº¡i"} cáº¥u hÃ¬nh USB Import (${if (settings.enabled) "báº­t" else "táº¯t"}, ${settings.copyMode}, Ä‘Ã­ch '${settings.destFolder}', readonly=${settings.mountReadonly})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = if (ok) "ÄÃ£ lÆ°u cáº¥u hÃ¬nh USB Import" else "Lá»—i lÆ°u USB Import"
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÆ°á»i dÃ¹ng: lÆ°u cáº¥u hÃ¬nh USB Import tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i: ${e.message}" }
            }
        }
    }

    fun startUsbImportNow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isUsbImportLoading = true }
                val base = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (base.isBlank()) throw IllegalStateException("ChÆ°a cÃ³ Ä‘á»‹a chá»‰ NAS há»£p lá»‡")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/start")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i báº¯t Ä‘áº§u USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "USBImport", "NgÆ°á»i dÃ¹ng: yÃªu cáº§u copy USB ngay (${o.optString("message", "khÃ´ng cÃ³ pháº£n há»“i")}).")
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = o.optString("message", if (resp.isSuccessful) "ÄÃ£ báº¯t Ä‘áº§u copy USB" else "KhÃ´ng báº¯t Ä‘áº§u Ä‘Æ°á»£c")
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÆ°á»i dÃ¹ng: yÃªu cáº§u copy USB ngay tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i: ${e.message}" }
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
                if (base.isBlank()) throw IllegalStateException("ChÆ°a cÃ³ Ä‘á»‹a chá»‰ NAS há»£p lá»‡")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/cancel")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i huá»· USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "USBImport", "NgÆ°á»i dÃ¹ng: gá»­i lá»‡nh há»§y USB Import (${if (resp.isSuccessful) "Ä‘Ã£ gá»­i" else "tháº¥t báº¡i"}).")
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = if (resp.isSuccessful) "ÄÃ£ gá»­i lá»‡nh há»§y" else "KhÃ´ng há»§y Ä‘Æ°á»£c"
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÆ°á»i dÃ¹ng: há»§y USB Import tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i: ${e.message}" }
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
                if (base.isBlank()) throw IllegalStateException("ChÆ°a cÃ³ Ä‘á»‹a chá»‰ NAS há»£p lá»‡")
                val body = org.json.JSONObject().apply {
                    put("action", action)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/resolve_conflicts")
                    .post(body)
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(
                        if (resp.isSuccessful) "INFO" else "WARNING",
                        "USBImport",
                        "NgÆ°á»i dÃ¹ng: xá»­ lÃ½ file trÃ¹ng USB Import báº±ng $action (${o.optString("message", "khÃ´ng cÃ³ pháº£n há»“i")})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = o.optString("message", if (resp.isSuccessful) "ÄÃ£ gá»­i lá»‡nh xá»­ lÃ½ file trÃ¹ng" else "KhÃ´ng xá»­ lÃ½ Ä‘Æ°á»£c file trÃ¹ng")
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÆ°á»i dÃ¹ng: xá»­ lÃ½ file trÃ¹ng USB Import tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lá»—i: ${e.message}" }
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val ok = resp.isSuccessful && org.json.JSONObject(body).optBoolean("saved", false)
                    repository.addSystemLog(
                        if (ok) "INFO" else "WARNING",
                        "SleepSchedule",
                        "NgÆ°á»i dÃ¹ng: ${if (ok) "lÆ°u" else "lÆ°u tháº¥t báº¡i"} lá»‹ch ngá»§ NAS (${if (newSchedule.enabled) "báº­t" else "táº¯t"}, ${newSchedule.mode}, ${newSchedule.startHour}h-${newSchedule.endHour}h, idleOnly=${newSchedule.idleOnly})."
                    )
                    withContext(Dispatchers.Main) {
                        sleepScheduleMessage = if (ok) "ÄÃ£ lÆ°u lá»‹ch ngá»§ NAS" else "Lá»—i lÆ°u"
                    }
                }
                fetchSleepSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "SleepSchedule", "NgÆ°á»i dÃ¹ng: lÆ°u lá»‹ch ngá»§ NAS tháº¥t báº¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { sleepScheduleMessage = "Lá»—i: ${e.message}" }
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val o = org.json.JSONObject(body)
                    val ok = o.optBoolean("ok", false)
                    val msg = o.optString("msg", "")
                    repository.addSystemLog(if (ok) "INFO" else "WARNING", "SleepSchedule", "NgÆ°á»i dÃ¹ng: yÃªu cáº§u HDD spindown ngay (${if (ok) "thÃ nh cÃ´ng" else "tháº¥t báº¡i"}: $msg).")
                    withContext(Dispatchers.Main) { onDone(ok, msg) }
                }
                fetchSleepSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "SleepSchedule", "NgÆ°á»i dÃ¹ng: yÃªu cáº§u HDD spindown ngay tháº¥t báº¡i: ${e.message?.take(120)}")
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
                    .header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass))
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

// Lá»šP PHá»¤ TRá»¢: Bá»™ Ä‘áº¿m Rate Limiter (2.C)
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
        commonDialogMessage = "ÄÃ£ nháº­n lá»‡nh! Äang khá»Ÿi Ä‘á»™ng trÃ¬nh quÃ©t rÃ¡c..."
        showCommonDialog = true

        isScanningDuplicates = true
        viewModelScope.launch {
            repository.addSystemLog("INFO", "DuplicateScan", "Há»‡ thá»‘ng: NgÆ°á»i dÃ¹ng Ä‘Ã£ phÃ¢n cÃ´ng quÃ©t thá»§ cÃ´ng trÃ¹ng láº·p")
        }
        if (isWorkerRunning) return

        isWorkerRunning = true
        scanDuplicatesCurrentFolderUrl = "Äang káº¿t ná»‘i..."
        scanDuplicatesCurrentItemName = "Khá»Ÿi táº¡o..."
        scanDuplicatesTotalScanned = 0
        scanDuplicatesFound = 0
        scanDuplicatesStage = "Khá»Ÿi Ä‘á»™ng..."

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

                // Luá»“ng: Láº¯ng nghe tráº¡ng thÃ¡i Worker (ThÃ nh cÃ´ng, Tháº¥t báº¡i)
                workManager.getWorkInfoByIdFlow(scanWorkRequest.id).collect { workInfo ->
                    if (workInfo != null) {
                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            scanDuplicatesStage = "HoÃ n táº¥t"
                            scanDuplicatesCurrentFolderUrl = "HoÃ n táº¥t!"
                            scanDuplicatesCurrentItemName = "ÄÃ£ quÃ©t xong toÃ n bá»™."
                            scanDuplicatesPercent = 1f
                            scanDuplicatesCurrentStagePercent = 1f
                            scanDuplicatesStageNumber = 4
                            scanDuplicatesStageDescription = "ÄÃ£ quÃ©t xong toÃ n bá»™."
                            isWorkerRunning = false
                            loadDuplicateResultsFromCache(context)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            scanDuplicatesCurrentFolderUrl = "Gáº·p lá»—i há»‡ thá»‘ng!"
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
                        errorMessage = "NAS Ä‘ang gá»n gÃ ng. KhÃ´ng cÃ³ tá»‡p trÃ¹ng láº·p."
                    } else {
                        errorMessage = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lá»—i náº¡p danh sÃ¡ch tá»« DB: ${e.message}" }
            }
        }
    }

fun WebDavViewModel.deleteDuplicateFile(file: NasFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                val relativePath = file.path.removePrefix(webDavManager.currentBaseUrl).trimStart('/')
                val driveName = relativePath.substringBefore('/')
                val trashUrl = webDavManager.currentBaseUrl + driveName + "/" + TRASH_FOLDER_NAME

                // 1. Kiá»ƒm tra náº¿u file Ä‘ang á»Ÿ trong thÃ¹ng rÃ¡c rá»“i thÃ¬ xoÃ¡ vÄ©nh viá»…n
                if (file.path.contains(TRASH_FOLDER_NAME)) {
                    webDavManager.deleteFile(file.path)
                } else {
                    // 2. Náº¿u chÆ°a, hÃ£y Ä‘áº£m báº£o thÆ° má»¥c thÃ¹ng rÃ¡c tá»“n táº¡i vÃ  di chuyá»ƒn vÃ o Ä‘Ã³
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) { /* ÄÃ£ tá»“n táº¡i */ }

                    val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    var targetUrl = if (trashUrl.endsWith("/")) trashUrl + encodedName else "$trashUrl/$encodedName"
                    if (file.isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                    webDavManager.renameFile(file.path, targetUrl)
                }

                repository.removeDuplicateFromDb(file.path)
                withContext(Dispatchers.Main) {
                    duplicateFilesList = duplicateFilesList.filter { it.path != file.path }
                    refresh()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Lá»—i xá»­ lÃ½ thÃ¹ng rÃ¡c: ${e.message}" }
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
                var processed = 0
                for (file in filesToDelete) {
                    val relativePath = file.path.removePrefix(webDavManager.currentBaseUrl).trimStart('/')
                    val driveName = relativePath.substringBefore('/')
                    val trashUrl = webDavManager.currentBaseUrl + driveName + "/" + TRASH_FOLDER_NAME
                    
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) { }
                    
                    if (file.path.contains(TRASH_FOLDER_NAME)) {
                        webDavManager.deleteFile(file.path)
                    } else {
                        val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                        var targetUrl = if (trashUrl.endsWith("/")) trashUrl + encodedName else "$trashUrl/$encodedName"
                        if (file.isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
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
                withContext(Dispatchers.Main) { errorMessage = "Lá»—i xá»­ lÃ½ hÃ ng loáº¡t: ${e.message}" }
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
            .setRequiresDeviceIdle(true) // ÄIá»€U KIá»†N 1: Äiá»‡n thoáº¡i Ä‘ang táº¯t mÃ n hÃ¬nh, khÃ´ng sá»­ dá»¥ng
            .setRequiresCharging(true)   // ÄIá»€U KIá»†N 2: Äang cáº¯m sáº¡c (Äáº£m báº£o an toÃ n pin)
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) // ÄIá»€U KIá»†N 3: CÃ³ Wi-Fi
            .build()

        val inputData = androidx.work.workDataOf(
            "currentUrl" to currentUrl
        )

        // CHU Ká»² Báº¢O Vá»† á»” Cá»¨NG: Chá»‰ lÃ©n cháº¡y Stress Test 30 ngÃ y 1 láº§n Ä‘á»ƒ khÃ´ng lÃ m giáº£m tuá»•i thá» á»• Ä‘Ä©a
        val periodicSpeedTestRequest = androidx.work.PeriodicWorkRequestBuilder<IdleSpeedTestWorker>(
            30, java.util.concurrent.TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "Auto_Idle_Speed_Test",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, // Giá»¯ nguyÃªn lá»‹ch trÃ¬nh cÅ© náº¿u Ä‘Ã£ tá»“n táº¡i
            periodicSpeedTestRequest
        )
    }

// PHASE 5.B: LÃªn lá»‹ch cho FingerprintWorker cháº¡y má»“i vÃ¢n tay ngáº§m
fun WebDavViewModel.scheduleFingerprintWorker(context: android.content.Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val constraints = androidx.work.Constraints.Builder()
        .setRequiresDeviceIdle(true) // Táº¯t mÃ n hÃ¬nh
        .setRequiresCharging(true)   // Äang sáº¡c
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED) // CÃ³ máº¡ng
        .build()

    // Cháº¡y má»—i 24 tiáº¿ng Ä‘á»ƒ táº¡o vÃ¢n tay cho cÃ¡c file áº£nh/video vá»«a upload
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
 * Táº£i video tá»« NAS vá» cache rá»“i má»Ÿ báº±ng trÃ¬nh phÃ¡t cá»¥c bá»™.
 * Äáº£m báº£o má»i Ä‘á»‹nh dáº¡ng (.mpg, .avi, .wmv, .flv, ...) Ä‘á»u phÃ¡t Ä‘Æ°á»£c
 * vÃ¬ file cá»¥c bá»™ khÃ´ng cÃ³ váº¥n Ä‘á» auth hay streaming.
 */
object VideoDownloadHelper {

    private const val TAG = "VideoDownloadHelper"
    private const val VIDEO_CACHE_DIR = "video_temp"

    /**
     * Táº£i video vá» cache vÃ  má»Ÿ báº±ng trÃ¬nh phÃ¡t bÃªn ngoÃ i.
     * Hiá»ƒn thá»‹ progress qua callback.
     *
     * @param onProgress Callback (bytesDownloaded, totalBytes) Ä‘á»ƒ cáº­p nháº­t UI
     * @param onReady Callback khi file Ä‘Ã£ sáºµn sÃ ng phÃ¡t
     * @param onError Callback khi cÃ³ lá»—i
     */
    // FIX A3a: Nháº­n CoroutineScope tá»« caller thay vÃ¬ tá»± táº¡o CoroutineScope(IO) riÃªng.
    // Scope rá»i ráº¡c sáº½ khÃ´ng bao giá» bá»‹ cancel khi ViewModel bá»‹ destroy â†’ memory leak.
    // Caller (thÆ°á»ng lÃ  ViewModel) pháº£i truyá»n viewModelScope Ä‘á»ƒ lifecycle Ä‘Æ°á»£c quáº£n lÃ½ Ä‘Ãºng.
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
                // 1. Táº¡o thÆ° má»¥c cache cho video
                val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
                if (!cacheDir.exists()) cacheDir.mkdirs()

                // XÃ³a file cÅ© Ä‘á»ƒ giáº£i phÃ³ng bá»™ nhá»› (chá»‰ giá»¯ file má»›i nháº¥t)
                cacheDir.listFiles()?.forEach { it.delete() }

                // 2. Láº¥y tÃªn file tá»« URL
                val fileName = url.substringAfterLast('/').substringBefore('?')
                    .let { java.net.URLDecoder.decode(it, "UTF-8") }
                    .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val targetFile = File(cacheDir, fileName)

                Log.i(TAG, "Äang táº£i: $url â†’ ${targetFile.absolutePath}")

                // 3. Táº£i file tá»« NAS vá»›i xÃ¡c thá»±c
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .build()

                // FIX A3b: Bá»c response trong use {} Ä‘á»ƒ Ä‘áº£m báº£o body luÃ´n Ä‘Æ°á»£c Ä‘Ã³ng,
                // ká»ƒ cáº£ khi exception xáº£y ra giá»¯a chá»«ng (trÃ¡nh connection pool exhaustion).
                NasApplication.instance.videoStreamingClient
                    .newBuilder()
                    .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS) // 10 phÃºt cho file lá»›n
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            withContext(Dispatchers.Main) {
                                onError("NAS tráº£ vá» lá»—i: ${response.code}")
                            }
                            return@use
                        }

                        val totalBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        var downloadedBytes = 0L

                        // 4. Ghi file ra cache vá»›i progress
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

                        Log.i(TAG, "Táº£i xuá»‘ng hoÃ n táº¥t: ${downloadedBytes / 1024}KB")

                        // 5. Má»Ÿ file cá»¥c bá»™ báº±ng trÃ¬nh phÃ¡t video
                        withContext(Dispatchers.Main) {
                            onReady()
                            openLocalFile(context, targetFile)
                        }
                    }

            } catch (e: CancellationException) {
                Log.d(TAG, "ÄÃ£ há»§y táº£i xuá»‘ng")
            } catch (e: Exception) {
                Log.e(TAG, "Táº£i xuá»‘ng tháº¥t báº¡i: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("Lá»—i táº£i video: ${e.message}")
                }
            }
        }
    }


    /** Má»Ÿ file video cá»¥c bá»™ báº±ng trÃ¬nh phÃ¡t cÃ i trÃªn mÃ¡y */
    private fun openLocalFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // XÃ¡c Ä‘á»‹nh MIME type phÃ¹ há»£p
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

            val chooser = Intent.createChooser(intent, "Chá»n trÃ¬nh phÃ¡t video")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open file: ${e.message}")
        }
    }
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// SystemMonitorHelper â€” Extension functions cho WebDavViewModel
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

fun WebDavViewModel.listenToLocalNasApi() {
    statusJob?.cancel()
    statusJob = viewModelScope.launch(Dispatchers.IO) {
        var currentDelayMs = 5000L
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
                            val tempRaw = jsonObject.optString("temperature", "--Â°C")
                            val temp = if (tempRaw != "--Â°C" && !tempRaw.contains("Â°")) "${tempRaw}Â°C" else tempRaw
                            val cpu = jsonObject.optString("cpu", "--%")
                            val cpuTemp = jsonObject.optString("cpu_temp", "--Â°C")
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
                            jsonObject.optJSONArray("torrents")?.let { arr -> for (i in 0 until arr.length()) { val tObj = arr.getJSONObject(i); torrentList.add(TorrentInfo(tObj.optString("name", "Äang táº£i..."), tObj.optDouble("progress", 0.0).toFloat(), tObj.optString("speed", "0 B/s"), tObj.optString("hash", ""), tObj.optString("state", ""), tObj.optString("save_path", ""))) } }
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
                                // CHá»NG BOUNCE (Debounce): Bá» qua cáº­p nháº­t tráº¡ng thÃ¡i quáº¡t tá»« API náº¿u Ä‘ang gá»­i lá»‡nh HOáº¶C vá»«a set thá»§ cÃ´ng < 15s (Ä‘á»ƒ chá» NAS xá»­ lÃ½ service tá»‘n thá»i gian)
                                if (isFanModeUpdating || System.currentTimeMillis() - lastFanModeSettingTime < 15000L) {
                                    systemStatus = newStatus.copy(
                                        fanMode = systemStatus.fanMode,
                                        fanStatus = systemStatus.fanStatus, // Báº£o toÃ n chuá»—i tráº¡ng thÃ¡i tá»‘c Ä‘á»™ quáº¡t áº£o
                                        fanOnTemp = systemStatus.fanOnTemp,
                                        fanOffTemp = systemStatus.fanOffTemp
                                    )
                                } else {
                                    systemStatus = newStatus
                                }
                                
                                if (hddVal > 0f || cpuVal > 0f) {
                                    // FIX: temperatureHistory boc trong mutableStateOf â€” in-place
                                    // addLast khong trigger recompose. Phai reassign de Compose biet.
                                    temperatureHistory.add(Pair(cpuVal, hddVal))

                                    while (temperatureHistory.size > 40) temperatureHistory.removeAt(0)

                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) { apiFailureCount += 1; systemStatus = systemStatus.copy(status = "API tá»« chá»‘i") }
                            currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(60_000L)
                        }
                    }
                }
            } catch (e: Exception) {
                val isTimeout = e is java.net.SocketTimeoutException || e is java.net.ConnectException
                val msg = if (isTimeout) "Máº¥t káº¿t ná»‘i API (${e.javaClass.simpleName})" else "API: ${e.javaClass.simpleName}"
                android.util.Log.w("NAS_API", "Theo dÃµi ping tháº¥t báº¡i: ${e.message}")
                withContext(Dispatchers.Main) { apiFailureCount += 1; systemStatus = systemStatus.copy(status = msg) }
                currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(60_000L)
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
        val wsRequest = okhttp3.Request.Builder().url(wsUrl).header("Authorization", okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass)).build()
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
                    withContext(Dispatchers.Main) { weeklyReportText = "Tuáº§n qua: Cháº·n ${json.optInt("banned_count", 0)} IP táº¥n cÃ´ng. Dá»n rÃ¡c giáº£i phÃ³ng ${json.optString("freed_space", "0 MB")}." }
                }
            }
        } catch (_: Exception) { withContext(Dispatchers.Main) { weeklyReportText = "ChÆ°a cÃ³ bÃ¡o cÃ¡o tuáº§n nÃ y." } }
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
            android.util.Log.e("NasAPI", "KhÃ´ng táº£i Ä‘Æ°á»£c nháº­t kÃ½ tá»« NAS: ${e.message}")
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
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        android.util.Log.e("NasAPI", "Lá»—i xÃ³a server logs: ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NasAPI", "KhÃ´ng xÃ³a Ä‘Æ°á»£c nháº­t kÃ½ trÃªn NAS: ${e.message}")
        }
        withContext(Dispatchers.Main) { 
            systemLogsList = emptyList()
            commonDialogMessage = "ÄÃ£ dá»n sáº¡ch nháº­t kÃ½ há»‡ thá»‘ng."
            showCommonDialog = true 
        } 
    } 
}

fun WebDavViewModel.fetchSmartData() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/smart").build()
            localApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "")
                    withContext(Dispatchers.Main) {
                        smartInfo = SmartInfo(status = json.optString("status", "KhÃ´ng rÃµ"), temperature = run { val rawTemp = json.optString("temperature", "--"); if (rawTemp != "--" && !rawTemp.contains("Â°")) "${rawTemp}Â°C" else rawTemp }, rawLog = json.optString("raw_log", ""))
                        lastSmartRefreshAt = System.currentTimeMillis()
                    }
                } else withContext(Dispatchers.Main) { smartInfo = SmartInfo("Lá»—i káº¿t ná»‘i", "--", "MÃ£ lá»—i: ${response.code}") }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { smartInfo = SmartInfo("KhÃ´ng thá»ƒ káº¿t ná»‘i", "--", e.message ?: "") } }
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
    }
}

fun WebDavViewModel.runSpeedTest() {
    if (isTestingSpeed) return; isTestingSpeed = true; speedTestResult = SpeedTestResult("Äang Ä‘o...", "Äang Ä‘o...")
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/speedtest").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            val speedTestClient = localApiClient.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            speedTestClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) { val json = org.json.JSONObject(response.body?.string() ?: ""); withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult(json.optString("write_speed", "Lá»—i"), json.optString("read_speed", "Lá»—i")) } }
                else withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult("Tháº¥t báº¡i", "Tháº¥t báº¡i") }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult("Lá»—i", "Lá»—i") }; repository.addSystemLog("ERROR", "SpeedTest", "Äo tá»‘c Ä‘á»™ tháº¥t báº¡i: ${e.message?.take(80)}") }
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
            "NgÆ°á»i dÃ¹ng Ä‘Ã£ gá»­i Wake-on-LAN Ä‘Ã¡nh thá»©c NAS táº¡i MAC ${macStr.trim()}: ${result.message}"
        } else {
            "Gá»­i Wake-on-LAN tá»›i MAC ${macStr.trim()} tháº¥t báº¡i: ${result.message}"
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
        android.util.Log.w("WOL", "KhÃ´ng thá»ƒ láº¥y MAC Wake-on-LAN trÆ°á»›c khi táº¯t nguá»“n: ${e.message}")
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
            val cmdName = when { endpoint.contains("reboot") -> "Khá»Ÿi Ä‘á»™ng láº¡i"; isSleepCommand -> "Ngá»§"; else -> endpoint }
            val savedMac = if (isSleepCommand) refreshWakeOnLanMacFromNas() else null
            if (savedMac != null) {
                repository.addSystemLog("INFO", "Power", "ÄÃ£ lÆ°u MAC Wake-on-LAN $savedMac trÆ°á»›c khi Ä‘Æ°a NAS vÃ o cháº¿ Ä‘á»™ ngá»§")
            }
            repository.addSystemLog("WARNING", "Power", "ÄÃ£ gá»­i lá»‡nh $cmdName NAS táº¡i $host")
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/$endpoint").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            localApiClient.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                val suffix = if (isSleepCommand && savedMac != null) " MAC WOL: $savedMac." else ""
                withContext(Dispatchers.Main) {
                    onResult?.invoke(ok, if (ok) "ÄÃ£ gá»­i lá»‡nh $cmdName NAS.$suffix" else "NAS tá»« chá»‘i lá»‡nh $cmdName (HTTP ${response.code}).")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult?.invoke(false, "KhÃ´ng gá»­i Ä‘Æ°á»£c lá»‡nh nguá»“n: ${e.message ?: "lá»—i máº¡ng"}")
            }
        }
    }
}

/**
 * Gui lenh power (reboot/shutdown) tu man LoginScreen â€” KHONG yeu cau da connect.
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
                withContext(Dispatchers.Main) { onResult(false, "Vui lÃ²ng nháº­p IP cá»§a NAS") }
                return@launch
            }
            // Tu IP -> http://<ip>:<API_PORT>
            val host = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                java.net.URL(trimmed).host
            } else trimmed.substringBefore(":")
            val apiUrl = "http://$host:${AppConfig.API_PORT}/api/$endpoint"
            val cmdName = when {
                endpoint.contains("reboot") -> "Khá»Ÿi Ä‘á»™ng láº¡i"
                endpoint.contains("suspend") -> "Ngá»§"
                endpoint.contains("shutdown") -> "Táº¯t nguá»“n"
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
            // â€” luc nay con rong vi chua connect)
            val client = NasApplication.instance.fastApiClient.newBuilder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val ok = resp.isSuccessful
                val code = resp.code
                withContext(Dispatchers.Main) {
                    if (ok) onResult(true, "ÄÃ£ gá»­i lá»‡nh $cmdName NAS!")
                    else onResult(false, "NAS tá»« chá»‘i (HTTP $code) â€” kiá»ƒm tra IP/tÃ i khoáº£n/máº­t kháº©u")
                }
                try { repository.addSystemLog("WARNING", "Power", "LoginScreen: gá»­i $cmdName NAS táº¡i $host (HTTP $code)") } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(false, "KhÃ´ng káº¿t ná»‘i Ä‘Æ°á»£c NAS: ${e.message?.take(80) ?: "lá»—i máº¡ng"}")
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
                repository.addSystemLog(if (response.isSuccessful) "INFO" else "WARNING", "Docker", "NgÆ°á»i dÃ¹ng: ${if (turnOn) "báº­t" else "táº¯t"} Docker/qBittorrent ${if (response.isSuccessful) "thÃ nh cÃ´ng" else "tháº¥t báº¡i HTTP ${response.code}"}.")
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    val running = json.optBoolean("running", json.optBoolean("effective_running", false))
                    withContext(Dispatchers.Main) { isDockerRunning = running }
                }
            }
            checkDockerStatus()
        } catch (e: Exception) {
            repository.addSystemLog("WARNING", "Docker", "NgÆ°á»i dÃ¹ng: ${if (turnOn) "báº­t" else "táº¯t"} Docker/qBittorrent tháº¥t báº¡i: ${e.message?.take(120)}")
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
                    commonDialogMessage = if (ok) "ÄÃ£ ${if (enable) "báº­t" else "táº¯t"} dá»‹ch vá»¥ ${serviceName.uppercase()}." else "KhÃ´ng thá»ƒ ${if (enable) "báº­t" else "táº¯t"} dá»‹ch vá»¥ ${serviceName.uppercase()} (HTTP ${response.code})."
                    showCommonDialog = true
                }
            }
            fetchOmvOverview()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                commonDialogMessage = "Lá»—i Ä‘iá»u khiá»ƒn dá»‹ch vá»¥: ${e.message?.take(120)}"
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
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS; commonDialogMessage = "ÄÃ£ cáº¥p quyá»n truy cáº­p cho IP: $ip"; showCommonDialog = true }
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
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING; commonDialogMessage = "ÄÃ£ cháº·n quyá»n truy cáº­p cá»§a IP: $ip"; showCommonDialog = true }
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

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// LAN WHITELIST API â€” TÃ¡ch logic máº¡ng ra khá»i @Composable
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

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
                    withContext(Dispatchers.Main) { lanWhitelistError = "Lá»—i: ${response.code}" }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistError = "Lá»—i káº¿t ná»‘i: ${e.message}" }
        } finally {
            withContext(Dispatchers.Main) { lanWhitelistLoading = false }
        }
    }
}

fun WebDavViewModel.addLanWhitelistEntry(entry: String) {
    viewModelScope.launch(Dispatchers.IO) {
        var isSuccessLocally = false
        try {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Äang thÃªm..." }
            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            val isSubnet = entry.contains("/")
            // FIX B3: DÃ¹ng JSONObject.put() thay vÃ¬ string interpolation Ä‘á»ƒ trÃ¡nh JSON injection
            // náº¿u entry chá»©a kÃ½ tá»± Ä‘áº·c biá»‡t nhÆ° dáº¥u ngoáº·c kÃ©p hoáº·c backslash.
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
                        lanWhitelistStatus = "âœ… ÄÃ£ thÃªm $entry"
                        repository.addSystemLog("INFO", "Network", "NgÆ°á»i dÃ¹ng Ä‘Ã£ THÃŠM IP/Subnet '$entry' vÃ o danh sÃ¡ch LAN Whitelist.")
                    } else {
                        lanWhitelistStatus = "âŒ Lá»—i: ${response.code}"
                        repository.addSystemLog("WARNING", "Network", "Cá»‘ gáº¯ng thÃªm IP/Subnet '$entry' vÃ o LAN Whitelist tháº¥t báº¡i.")
                    }
                }
            }
            if (isSuccessLocally) loadLanWhitelist()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "âŒ ${e.message}" }
        }
    }
}

fun WebDavViewModel.removeLanWhitelistEntry(entry: String, isSubnet: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        var isSuccessLocally = false
        try {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Äang xÃ³a..." }
            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            // FIX B3: DÃ¹ng JSONObject.put() thay vÃ¬ string interpolation.
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
                        lanWhitelistStatus = "âœ… ÄÃ£ xÃ³a $entry"
                        repository.addSystemLog("INFO", "Network", "NgÆ°á»i dÃ¹ng Ä‘Ã£ XÃ“A IP/Subnet '$entry' khá»i danh sÃ¡ch LAN Whitelist.")
                    } else {
                        lanWhitelistStatus = "âŒ Lá»—i: ${response.code}"
                        repository.addSystemLog("WARNING", "Network", "Cá»‘ gáº¯ng xÃ³a IP/Subnet '$entry' khá»i LAN Whitelist tháº¥t báº¡i.")
                    }
                }
            }
            if (isSuccessLocally) loadLanWhitelist()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "âŒ ${e.message}" }
        }
    }
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// SMART ORGANIZER API â€” TÃ¡ch logic máº¡ng ra khá»i @Composable
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

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
                            organizerError = "Lá»—i phÃ¢n tÃ­ch: ${e.message}"
                        }
                    } else {
                        organizerError = "Lá»—i NAS: ${response.code}"
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { organizerError = "Lá»—i káº¿t ná»‘i: ${e.message}" }
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
                            organizerResult = "HoÃ n táº¥t Â· $count tá»‡p Ä‘Ã£ sáº¯p xáº¿p"
                            organizerScanResult = null
                        } catch (e: Exception) {
                            organizerError = "Lá»—i pháº£n há»“i: ${e.message}"
                        }
                    } else {
                        organizerError = "Lá»—i NAS: ${response.code}"
                    }
                }
            }
            // Refresh file list sau khi sáº¯p xáº¿p xong
            refresh()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { organizerError = "Lá»—i káº¿t ná»‘i: ${e.message}" }
        } finally {
            withContext(Dispatchers.Main) { organizerExecuting = false }
        }
    }


}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// PerformanceMonitor â€” GiÃ¡m sÃ¡t hiá»‡u nÄƒng á»©ng dá»¥ng
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

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
            e.printStackTrace()
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
                                speed = jobObj.optString("avg_speed", "â€”"),
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
        } catch (_: Exception) {}
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
            e.printStackTrace()
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
                        onResult(true, if (effectiveEnabled) "SMB Ä‘ang báº­t thá»±c táº¿" else "SMB Ä‘ang táº¯t thá»±c táº¿")
                    } else {
                        onResult(false, "Lá»—i: $responseBody")
                    }
                }
                fetchSmbStatus()
                fetchOmvOverview()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                isLoadingSmb = false
                onResult(false, "Lá»—i káº¿t ná»‘i: ${e.message}")
            }
        }
    }
}
