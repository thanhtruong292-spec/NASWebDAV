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

// FIX ERROR HANDLING: ChuyÃ¡Â»Æ’n lÃ¡Â»â€”i kÃ¡Â»Â¹ thuÃ¡ÂºÂ­t thÃƒÂ nh thÃƒÂ´ng bÃƒÂ¡o dÃ¡Â»â€¦ hiÃ¡Â»Æ’u
private fun friendlyError(e: Exception): String = when (e) {
    is java.net.SocketTimeoutException -> "KÃ¡ÂºÂ¿t nÃ¡Â»â€˜i tÃ¡Â»â€ºi NAS quÃƒÂ¡ chÃ¡ÂºÂ­m hoÃ¡ÂºÂ·c NAS khÃƒÂ´ng phÃ¡ÂºÂ£n hÃ¡Â»â€œi. Vui lÃƒÂ²ng kiÃ¡Â»Æ’m tra mÃ¡ÂºÂ¡ng."
    is java.net.ConnectException -> "KhÃƒÂ´ng thÃ¡Â»Æ’ kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i tÃ¡Â»â€ºi NAS. KiÃ¡Â»Æ’m tra NAS Ã„â€˜ÃƒÂ£ bÃ¡ÂºÂ­t vÃƒÂ  cÃƒÂ¹ng mÃ¡ÂºÂ¡ng WiFi."
    is java.net.UnknownHostException -> "Ã„ÂÃ¡Â»â€¹a chÃ¡Â»â€° NAS khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡ hoÃ¡ÂºÂ·c mÃ¡ÂºÂ¥t kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i mÃ¡ÂºÂ¡ng."
    is javax.net.ssl.SSLException -> "LÃ¡Â»â€”i bÃ¡ÂºÂ£o mÃ¡ÂºÂ­t kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i. KiÃ¡Â»Æ’m tra cÃ¡ÂºÂ¥u hÃƒÂ¬nh SSL/TLS cÃ¡Â»Â§a NAS."
    else -> e.message ?: "LÃ¡Â»â€”i khÃƒÂ´ng xÃƒÂ¡c Ã„â€˜Ã¡Â»â€¹nh"
}

private fun buildLoginFailureMessage(urlList: List<String>, errorDetails: List<String>): String {
    if (errorDetails.isEmpty()) {
        return "KhÃƒÂ´ng Ã„â€˜Ã„Æ’ng nhÃ¡ÂºÂ­p Ã„â€˜Ã†Â°Ã¡Â»Â£c. KiÃ¡Â»Æ’m tra tÃƒÂ i khoÃ¡ÂºÂ£n, mÃ¡ÂºÂ­t khÃ¡ÂºÂ©u hoÃ¡ÂºÂ·c dÃ¡Â»â€¹ch vÃ¡Â»Â¥ WebDAV."
    }
    // HiÃ¡Â»Æ’n thÃ¡Â»â€¹ TÃ¡ÂºÂ¤T CÃ¡ÂºÂ¢ cÃƒÂ¡c URL Ã„â€˜ÃƒÂ£ thÃ¡Â»Â­ (LAN + Tailscale) kÃƒÂ¨m lÃƒÂ½ do tÃ¡Â»Â«ng URL,
    // trÃƒÂ¡nh hiÃ¡Â»Æ’u nhÃ¡ÂºÂ§m chÃ¡Â»â€° mÃ¡Â»â„¢t URL Ã„â€˜Ã†Â°Ã¡Â»Â£c thÃ¡Â»Â­ khi nhiÃ¡Â»Âu URL cÃƒÂ¹ng fail.
    val lines = errorDetails.map { detail ->
        val colonIdx = detail.indexOf(": ")
        val rawUrl = if (colonIdx > 0) detail.take(colonIdx) else detail
        val rawReason = if (colonIdx > 0) detail.substring(colonIdx + 2).take(110).trim() else "khÃƒÂ´ng xÃƒÂ¡c Ã„â€˜Ã¡Â»â€¹nh"
        val host = runCatching {
            val u = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
            java.net.URL(u).host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: rawUrl
        val niceReason = when {
            rawReason.contains("WebDAV", ignoreCase = true) -> "WebDAV quÃƒÂ¡ hÃ¡ÂºÂ¡n hoÃ¡ÂºÂ·c chÃ†Â°a xÃƒÂ¡c thÃ¡Â»Â±c"
            rawReason.contains("timeout", ignoreCase = true) || rawReason.contains("quÃƒÂ¡ hÃ¡ÂºÂ¡n", ignoreCase = true) || rawReason.contains("timed out", ignoreCase = true) -> "MÃ¡ÂºÂ¡ng quÃƒÂ¡ hÃ¡ÂºÂ¡n / khÃƒÂ´ng phÃ¡ÂºÂ£n hÃ¡Â»â€œi"
            rawReason.contains("Unable to resolve", ignoreCase = true) || rawReason.contains("UnknownHost", ignoreCase = true) -> "KhÃƒÂ´ng tÃƒÂ¬m thÃ¡ÂºÂ¥y host"
            rawReason.contains("ECONNREFUSED", ignoreCase = true) || rawReason.contains("refused", ignoreCase = true) -> "KÃ¡ÂºÂ¿t nÃ¡Â»â€˜i bÃ¡Â»â€¹ tÃ¡Â»Â« chÃ¡Â»â€˜i"
            rawReason.contains("ENETUNREACH", ignoreCase = true) || rawReason.contains("unreachable", ignoreCase = true) -> "MÃ¡ÂºÂ¡ng khÃƒÂ´ng thÃ¡Â»Æ’ tiÃ¡ÂºÂ¿p cÃ¡ÂºÂ­n"
            else -> rawReason.trimEnd('.')
        }
        "Ã¢â‚¬Â¢ $host Ã¢â‚¬â€ $niceReason"
    }
    val header = if (lines.size > 1) "KhÃƒÂ´ng Ã„â€˜Ã„Æ’ng nhÃ¡ÂºÂ­p Ã„â€˜Ã†Â°Ã¡Â»Â£c NAS (Ã„â€˜ÃƒÂ£ thÃ¡Â»Â­ ${lines.size} Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€°):" else "KhÃƒÂ´ng Ã„â€˜Ã„Æ’ng nhÃ¡ÂºÂ­p Ã„â€˜Ã†Â°Ã¡Â»Â£c NAS:"
    return "$header\n" + lines.joinToString("\n")
}

// THÃƒÅ M DATA CLASS CHO TORRENT
data class TorrentInfo(
    val name: String,
    val progress: Float,
    val speed: String,
    val hash: String = "",
    val state: String = "",
    val savePath: String = ""
)

// DATA CLASS CHO SMART VÃƒâ‚¬ SPEED TEST
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

// DATA CLASS CHO PHÃƒâ€šN TÃƒÂCH Ã¡Â»â€ Ã„ÂÃ„Â¨A
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

// Data class lÃ†Â°u trÃ¡Â»Â¯ trÃ¡ÂºÂ¡ng thÃƒÂ¡i hÃ¡Â»â€¡ thÃ¡Â»â€˜ng qua Local API
data class NasSystemStatus(
    val temp: String = "--Ã‚Â°C",
    val cpu: String = "--%",
    val cpuTemp: String = "--Ã‚Â°C",
    val ram: String = "--",
    val disk: String = "--%",
    val diskCapacity: String = "",
    val netRx: String = "0 B/s",
    val netTx: String = "0 B/s",
    val uptime: String = "--:--",
    val status: String = "Ã„Âang kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i...",
    val ramPercent: String = "0",
    val torrents: List<TorrentInfo> = emptyList(),
    val diskParts: List<DiskPart> = emptyList(),
    val fanStatus: String = "--",  // TrÃ¡ÂºÂ¡ng thÃƒÂ¡i quÃ¡ÂºÂ¡t (DÃ¡Â»Â«ng / Ã„Âang chÃ¡ÂºÂ¡y)
    val fanMode: String = "auto",  // auto, on, off, custom
    val fanOnTemp: Float = 45f,
    val fanOffTemp: Float = 40f,
    val fanRpm: Int? = null,       // SÃ¡Â»â€˜ vÃƒÂ²ng quÃ¡ÂºÂ¡t (nÃ¡ÂºÂ¿u cÃƒÂ³)
    val topProcesses: List<Pair<String, Float>> = emptyList() // Top tiÃ¡ÂºÂ¿n trÃƒÂ¬nh Ã„Æ’n CPU
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

// DATA CLASS CHO BIÃ¡Â»â€šU Ã„ÂÃ¡Â»â€™ GIÃƒÂM SÃƒÂT
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
 * KiÃ¡Â»Æ’m tra URL cÃƒÂ³ trÃ¡Â»Â Ã„â€˜Ã¡ÂºÂ¿n mÃ¡Â»â„¢t Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° Tailscale hay khÃƒÂ´ng.
 * Tailscale dÃƒÂ¹ng dÃ¡ÂºÂ£i CGNAT 100.64.0.0/10 (octet 2 tÃ¡Â»Â« 64 Ã„â€˜Ã¡ÂºÂ¿n 127).
 * VD: 100.90.135.102 Ã¢â€ â€™ Tailscale Ã¢Å“â€¦
 *     192.168.100.5  Ã¢â€ â€™ LAN bÃƒÂ¬nh thÃ†Â°Ã¡Â»Âng Ã¢Å“â€¦ (KHÃƒâ€NG bÃ¡Â»â€¹ nhÃ¡ÂºÂ§m)
 *     100.20.1.1     Ã¢â€ â€™ LAN bÃƒÂ¬nh thÃ†Â°Ã¡Â»Âng (ngoÃƒÂ i dÃ¡ÂºÂ£i Tailscale) Ã¢Å“â€¦
 */
fun isTailscaleUrl(url: String): Boolean {
    if (url.isBlank()) return false
    // KiÃ¡Â»Æ’m tra tÃ¡Â»Â« khÃƒÂ³a "tailscale" trong URL (cho hostname dÃ¡ÂºÂ¡ng tailscale)
    if (url.contains("tailscale", ignoreCase = true)) return true
    return try {
        val host = java.net.URL(url).host ?: return false
        val parts = host.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            // DÃ¡ÂºÂ£i Tailscale: 100.64.x.x Ã¢â‚¬â€œ 100.127.x.x
            a == 100 && b in 64..127
        } else false
    } catch (_: Exception) { false }
}

class WebDavViewModel(val webDavManager: WebDavManager, val repository: WebDavRepository) : ViewModel() {


    // CHÃ¡Â»ÂNG RÃƒâ€™ RÃ¡Â»Ë† THREAD VÃƒâ‚¬ BÃ¡Â»Ëœ NHÃ¡Â»Å¡: DÃƒÂ¹ng chung mÃ¡Â»â„¢t OkHttpClient duy nhÃ¡ÂºÂ¥t cho toÃƒÂ n bÃ¡Â»â„¢ cÃƒÂ¡c truy vÃ¡ÂºÂ¥n Local API
    internal val localApiClient: okhttp3.OkHttpClient by lazy {
        NasApplication.instance.fastApiClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Fix API auth header: attach Authorization to every Local API request
            .addInterceptor { chain ->
                val auth = chain.request().tag(WebDavManager.AuthState::class.java) ?: return@addInterceptor chain.proceed(chain.request())
                val requestBuilder = chain.request().newBuilder()
                requestBuilder.header("Authorization", auth.authHeader)
                chain.proceed(requestBuilder.build())
            }
            .build()
    }
    // BiÃ¡ÂºÂ¿n lÃ†Â°u trÃ¡Â»Â¯ trÃ¡ÂºÂ¡ng thÃƒÂ¡i giÃƒÂ¡m sÃƒÂ¡t hÃ¡Â»â€¡ thÃ¡Â»â€˜ng (Local API)
    var systemStatus by mutableStateOf(NasSystemStatus())
    var temperatureHistory = androidx.compose.runtime.mutableStateListOf<Pair<Float, Float>>()

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ BIÃ¡Â»â€šU Ã„ÂÃ¡Â»â€™ GIÃƒÂM SÃƒÂT REAL-TIME Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    var metricsHistory = androidx.compose.runtime.mutableStateListOf<MetricsSnapshot>()
    var metricsHours by mutableIntStateOf(1)         // 1 / 6 / 24 giÃ¡Â»Â
    var metricsChartTab by mutableIntStateOf(0)       // 0=NhiÃ¡Â»â€¡t Ã„â€˜Ã¡Â»â„¢, 1=TÃƒÂ i nguyÃƒÂªn, 2=MÃ¡ÂºÂ¡ng
    var isLoadingMetrics by mutableStateOf(false)
    var metricsError by mutableStateOf<String?>(null)  // NÃ¡ÂºÂ¿u cÃƒÂ³ lÃ¡Â»â€”i, hiÃ¡Â»Æ’n thÃ¡Â»â€¹ thay vÃƒÂ¬ spinner vÃƒÂ´ hÃ¡ÂºÂ¡n
    var dailyReport by mutableStateOf<DailyReportData?>(null)
    var isDailyReportLoading by mutableStateOf(false)

    // STATE CHO TIÃ¡ÂºÂ¾N TRÃƒÅ’NH HÃ¡Â»â€  THÃ¡Â»ÂNG
    var systemProcesses by mutableStateOf<List<SystemProcess>>(emptyList())
    var isLoadingProcesses by mutableStateOf(false)
    private var metricsPollingJob: kotlinx.coroutines.Job? = null
    private var dashboardRealtimeJob: kotlinx.coroutines.Job? = null
    internal var statusJob: kotlinx.coroutines.Job? = null
    private val realtimeMetricInFlight = AtomicBoolean(false)
    private val realtimeMetricNextAllowedAt = AtomicLong(0L)
    private val realtimeMetricBackoffMs = AtomicLong(5_000L)

    // TÃƒÂNH NÃ„â€šNG 4.H: LÃ¡ÂºÂ¯ng nghe trÃ¡ÂºÂ¡ng thÃƒÂ¡i mÃ¡ÂºÂ¡ng Ping (ms)
    var networkPingMs by mutableStateOf<Long?>(null)
    var lastStatusRefreshAt by mutableStateOf(0L)
    var lastMetricsRefreshAt by mutableStateOf(0L)
    var lastStorageRefreshAt by mutableStateOf(0L)
    var lastSmartRefreshAt by mutableStateOf(0L)
    var lastLogsRefreshAt by mutableStateOf(0L)
    var apiLatencyMs by mutableStateOf<Long?>(null)
    var apiFailureCount by mutableIntStateOf(0)

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ SMART NETWORK Ã¢â‚¬â€œ trÃ¡ÂºÂ¡ng thÃƒÂ¡i Ã„â€˜ang dÃƒÂ¹ng LAN hay Tailscale Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    var isOnLan by mutableStateOf(true) // true = LAN, false = Tailscale

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ GUEST PASS STATE Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    var activeGuestPass by mutableStateOf<GuestPassInfo?>(null)
    var isGuestPassLoading by mutableStateOf(false)
    var guestPassError by mutableStateOf<String?>(null)

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ SOCIAL EXTRACTOR STATE Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    var socialExtractStatus by mutableStateOf("")
    var isSocialExtracting by mutableStateOf(false)
    var socialDownloadHistory by mutableStateOf<List<SocialDownloadItem>>(emptyList())

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ STREAM PIPE STATE (Ã„ÂiÃ¡Â»â€¡n thoÃ¡ÂºÂ¡i bÃ†Â¡m CDN Ã¢â€ â€™ NAS trÃ¡Â»Â±c tiÃ¡ÂºÂ¿p) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    var isStreamPiping      by mutableStateOf(false)     // Ã„Âang bÃ†Â¡m stream
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

    // TÃƒÂNH NÃ„â€šNG SMB
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

    var streamPipeStatus    by mutableStateOf("")        // MÃƒÂ´ tÃ¡ÂºÂ£ trÃ¡ÂºÂ¡ng thÃƒÂ¡i hiÃ¡Â»â€¡n tÃ¡ÂºÂ¡i
    var streamPipeProgress  by mutableFloatStateOf(0f)   // 0.0 Ã¢â€ â€™ 1.0 (nÃ¡ÂºÂ¿u biÃ¡ÂºÂ¿t size)
    var streamPipeSpeedStr  by mutableStateOf("-- MB/s") // TÃ¡Â»â€˜c Ã„â€˜Ã¡Â»â„¢ dÃ¡ÂºÂ¡ng text
    var streamPipeEtaStr    by mutableStateOf("--")      // ETA dÃ¡ÂºÂ¡ng text
    private var streamPipeJob: kotlinx.coroutines.Job? = null
    private var _activeStreamPipeWorkId: java.util.UUID? = null
    private var livestreamObserverJob: kotlinx.coroutines.Job? = null

    // LOÃ¡ÂºÂ I BÃ¡Â»Å½ fileList GÃƒâ€šY OOM, THAY BÃ¡ÂºÂ°NG PAGING DATA FLOW
    var fileList by mutableStateOf<List<NasFile>>(emptyList()) // GiÃ¡Â»Â¯ lÃ¡ÂºÂ¡i dÃ¡Â»Â± phÃƒÂ²ng cho tÃƒÂ­nh nÃ„Æ’ng tÃƒÂ¬m kiÃ¡ÂºÂ¿m/Ã„â€˜Ã¡ÂºÂ·c biÃ¡Â»â€¡t

    private val _pagedFilesFlow = MutableStateFlow<Flow<PagingData<NasFile>>>(emptyFlow())
    val pagedFilesFlow = _pagedFilesFlow.asStateFlow()

    private val _thumbnailAudit = MutableStateFlow<ThumbnailAuditData?>(null)
    val thumbnailAudit = _thumbnailAudit.asStateFlow()

    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var connectionStatus by mutableStateOf("Ã„Âang kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i...")
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

    // BIÃ¡ÂºÂ¾N CHO BATCH COPY / MOVE
    var isBatchProcessing by mutableStateOf(false)
    var batchProcessType by mutableStateOf("") // "COPY" hoÃ¡ÂºÂ·c "MOVE"
    var batchProcessProgress by mutableFloatStateOf(0f)
    var batchProcessCurrentFile by mutableStateOf("")

    // TÃƒÂNH NÃ„â€šNG 7.M: TrÃ¡ÂºÂ¡ng thÃƒÂ¡i chÃ¡Â»Â©a dÃ¡Â»Â¯ liÃ¡Â»â€¡u Text Preview
    var textPreviewContent by mutableStateOf<String?>(null)

    fun fetchTextPreview(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; textPreviewContent = null }
            val content = webDavManager.readFileText(url)
            withContext(Dispatchers.Main) { textPreviewContent = content; isLoading = false }
        }
    }

    // TiÃ¡ÂºÂ¿n trÃƒÂ¬nh tÃ¡ÂºÂ£i thumbnail
    var totalImagesInFolder by mutableIntStateOf(0)
    var loadedImagesCount by mutableIntStateOf(0)
    val imageLoadProgress: Float get() = if (totalImagesInFolder > 0) loadedImagesCount.toFloat() / totalImagesInFolder else 0f
    // BiÃ¡ÂºÂ¿n trÃ¡ÂºÂ¡ng thÃƒÂ¡i cho tiÃ¡ÂºÂ¿n trÃƒÂ¬nh Auto Backup
    var isAutoBackupRunning by mutableStateOf(false)
    var autoBackupCurrentFile by mutableStateOf("")
    var autoBackupSourcePath by mutableStateOf("")
    var autoBackupDestPath by mutableStateOf("")
    var autoBackupProgress by mutableFloatStateOf(0f)
    var autoBackupProcessedCount by mutableIntStateOf(0)
    var autoBackupTotalCount by mutableIntStateOf(0)
    var autoBackupElapsedTime by mutableLongStateOf(0L)
    var autoBackupIsPaused by mutableStateOf(false)

    // === Ã„ÂÃƒÂ£ gÃ¡Â»Â¡ bÃ¡Â»Â tÃƒÂ­nh nÃ„Æ’ng Ã„ÂÃ¡Â»â€œng bÃ¡Â»â„¢ thÃ†Â° mÃ¡Â»Â¥c ===

    // BiÃ¡ÂºÂ¿n trÃ¡ÂºÂ¡ng thÃƒÂ¡i cho tÃƒÂ­nh nÃ„Æ’ng QuÃƒÂ©t vÃƒÂ  XÃƒÂ³a file trÃƒÂ¹ng lÃ¡ÂºÂ·p
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
    var scanDuplicatesPercent by mutableFloatStateOf(0f) // Thanh tÃ¡Â»â€¢ng
    var scanDuplicatesCurrentStagePercent by mutableFloatStateOf(0f) // Thanh hiÃ¡Â»â€¡n tÃ¡ÂºÂ¡i
    var scanDuplicatesElapsedTime by mutableLongStateOf(0L) // ThÃ¡Â»Âi gian Ã„â€˜ÃƒÂ£ chÃ¡ÂºÂ¡y
    var scanDuplicatesEstimatedTimeRemaining by mutableLongStateOf(-1L) // ThÃ¡Â»Âi gian cÃƒÂ²n lÃ¡ÂºÂ¡i dÃ¡Â»Â± kiÃ¡ÂºÂ¿n
    var scanDuplicatesIsFolder by mutableStateOf(false)
    var scanDuplicatesStage by mutableStateOf("KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng...") // PHASE 4: Giai Ã„â€˜oÃ¡ÂºÂ¡n hiÃ¡Â»â€¡n tÃ¡ÂºÂ¡i
    var scanDuplicatesStageNumber by mutableIntStateOf(1)      // SÃ¡Â»â€˜ thÃ¡Â»Â© tÃ¡Â»Â± giai Ã„â€˜oÃ¡ÂºÂ¡n (1-4)
    var scanDuplicatesTotalStages by mutableIntStateOf(4)      // TÃ¡Â»â€¢ng sÃ¡Â»â€˜ giai Ã„â€˜oÃ¡ÂºÂ¡n
    var scanDuplicatesStageDescription by mutableStateOf("")   // MÃƒÂ´ tÃ¡ÂºÂ£ chi tiÃ¡ÂºÂ¿t giai Ã„â€˜oÃ¡ÂºÂ¡n
    internal var scanJob: kotlinx.coroutines.Job? = null
    
    // Ã„ÂIÃ¡Â»â‚¬U KHIÃ¡Â»â€šN QUÃƒâ€°T RÃƒÂC
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
        scanJob?.cancel() // HuÃ¡Â»Â· luÃ¡Â»â€œng theo dÃƒÂµi trÃ¡ÂºÂ¡ng thÃƒÂ¡i Worker
        isScanningDuplicates = false // Ã„ÂÃƒÂ³ng panel tiÃ¡ÂºÂ¿n trÃƒÂ¬nh
        duplicateFilesList = emptyList() // XoÃƒÂ¡ danh sÃƒÂ¡ch kÃ¡ÂºÂ¿t quÃ¡ÂºÂ£ (nÃ¡ÂºÂ¿u cÃƒÂ³) Ã„â€˜Ã¡Â»Æ’ Ã¡ÂºÂ©n card TÃƒÂ¡c vÃ¡Â»Â¥ nÃ¡Â»Ân
        
        // Reset trÃ¡ÂºÂ¡ng thÃƒÂ¡i tiÃ¡ÂºÂ¿n trÃƒÂ¬nh
        DuplicateProgressState.stage.value = "KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng..."
        DuplicateProgressState.percent.value = 0f
    }
    
    // TÃƒÂNH NÃ„â€šNG AUTO-CLEAN DUPLICATES
    var autoCleanEnabled by mutableStateOf(false)
    fun toggleAutoClean(context: Context, enabled: Boolean) {
        autoCleanEnabled = enabled
        // LÃ†Â°u SharedPreferences
        context.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE).edit().putBoolean("auto_clean_enabled", enabled).apply()
        
        val workManager = androidx.work.WorkManager.getInstance(context)
        if (enabled) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) // CÃ¡ÂºÂ§n Wifi
                .build()
                
            val req = androidx.work.PeriodicWorkRequestBuilder<AutoDuplicateScanWorker>(7, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork("AutoCleanDuplicates", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            workManager.cancelUniqueWork("AutoCleanDuplicates")
        }
    }

    // --- QUÃ¡ÂºÂ¢N LÃƒÂ BÃ¡ÂºÂ¢O MÃ¡ÂºÂ¬T & PHÃƒÅ  DUYÃ¡Â»â€ T (DEVICE APPROVAL) ---
    var showApprovalDialog by mutableStateOf(false)
    var pendingIpAddress by mutableStateOf("")
    var approvalMessage by mutableStateOf("")
    var pendingCountryCode by mutableStateOf("VN")
    var weeklyReportText by mutableStateOf("Ã„Âang tÃ¡ÂºÂ£i dÃ¡Â»Â¯ liÃ¡Â»â€¡u...")

    internal var webSocket: okhttp3.WebSocket? = null
    // FIX: tranh reconnect storm Ã¢â‚¬â€ track so lan thu lai de exponential backoff
    // va co flag chong reconnect tu nhieu listener onFailure cu va race nhau.
    @Volatile internal var wsReconnectAttempt: Int = 0
    @Volatile internal var wsReconnectScheduled: Boolean = false

    // TRÃƒÂCH XUÃ¡ÂºÂ¤T HOST CHUÃ¡ÂºÂ¨N Ã„ÂÃ¡Â»â€š FIX LÃ¡Â»â€“I CRASH PORT (8822:5050)

    // --- QUÃ¡ÂºÂ¢N LÃƒÂ NHÃ¡ÂºÂ¬T KÃƒÂ HÃ¡Â»â€  THÃ¡Â»ÂNG ---
    var showLogDialog by mutableStateOf(false)
    var systemLogsList by mutableStateOf<List<SystemLog>>(emptyList())

    // TrÃ¡ÂºÂ¡ng thÃƒÂ¡i cho chÃ¡ÂºÂ¿ Ã„â€˜Ã¡Â»â„¢ xem Ã„â€˜Ã¡ÂºÂ·c biÃ¡Â»â€¡t (Ã¡ÂºÂ¢nh mÃ¡Â»â€ºi/Video gÃ¡ÂºÂ§n Ã„â€˜ÃƒÂ¢y)
    var isSpecialMode by mutableStateOf(false)
    var specialTitle by mutableStateOf("")

    // STATE CHO DIALOG THÃƒâ€NG BÃƒÂO CHUNG TÃ¡Â»Âª VIEWMODEL
    var commonDialogMessage by mutableStateOf("")
    var commonDialogType by mutableStateOf(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS)
    var showCommonDialog by mutableStateOf(false)

    fun logUserAction(module: String, message: String, type: String = "INFO") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addSystemLog(type, module, "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: $message")
                withContext(Dispatchers.Main) { loadSystemLogs() }
            } catch (e: Exception) {
                android.util.Log.w("UserActionLog", "log failed: ${e.message}")
            }
        }
    }

    // FIX LÃ¡Â»â€“I 5: Debounce Ã¢â‚¬â€œ chÃ¡Â»â€° hiÃ¡Â»Æ’n thÃ¡Â»â€¹ dialog lÃ¡Â»â€”i mÃ¡ÂºÂ¥t mÃ¡ÂºÂ¡ng mÃ¡Â»â€”i 2 phÃƒÂºt, trÃƒÂ¡nh spam
    private var lastNetworkErrorDialogAt = 0L
    internal var lastFanModeSettingTime = 0L
    var isFanModeUpdating by mutableStateOf(false)
    private val NETWORK_ERROR_DIALOG_COOLDOWN_MS = 2 * 60 * 1000L // 2 phÃƒÂºt

    // STATE CHO SMART DIALOG VÃƒâ‚¬ SPEED TEST
    var showSmartDialog by mutableStateOf(false)
    var smartInfo by mutableStateOf(SmartInfo("Ã„Âang tÃ¡ÂºÂ£i...", "--", ""))
    var speedTestResult by mutableStateOf(SpeedTestResult("--", "--"))
    var isTestingSpeed by mutableStateOf(false)
    var lastAutoSpeedTime by mutableStateOf("")

    // STATE CHO DOCKER POWER
    var isDockerRunning by mutableStateOf(false)
    var isTogglingDocker by mutableStateOf(false)

    // STATE CHO DOCKER MANAGER
    var showDockerDialog by mutableStateOf(false)
    var dockerContainers by mutableStateOf<List<DockerContainer>>(emptyList())
    // QuÃ¡ÂºÂ£n lÃƒÂ½ NhÃ¡ÂºÂ­t kÃƒÂ½ hÃ¡Â»â€¡ thÃ¡Â»â€˜ng
    var systemLogs by mutableStateOf(listOf<SystemLog>())
    var isFetchingDocker by mutableStateOf(false)

    // STATE CHO OMV OVERVIEW
    var omvOverview by mutableStateOf(OmvOverview())

    // STATE CHO LAN WHITELIST (tÃƒÂ¡ch logic ra khÃ¡Â»Âi UI)
    var lanWhitelistIps by mutableStateOf<List<String>>(emptyList())
    var lanWhitelistSubnets by mutableStateOf<List<String>>(emptyList())
    var lanWhitelistLoading by mutableStateOf(true)
    var lanWhitelistError by mutableStateOf("")
    var lanWhitelistStatus by mutableStateOf("")

    // STATE CHO SMART ORGANIZER (tÃƒÂ¡ch logic ra khÃ¡Â»Âi UI)
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
                            showSystemNotification(appContext, 9011, "Ã„Âang tÃ¡ÂºÂ¡o Thumbnail (" + thumbGenerated + " / " + thumbTotal + ")", "File hiÃ¡Â»â€¡n tÃ¡ÂºÂ¡i: " + thumbLastFile, percent)
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
            val channel = android.app.NotificationChannel(channelId, "TiÃ¡ÂºÂ¿n trÃƒÂ¬nh ngÃ¡ÂºÂ§m NAS", android.app.NotificationManager.IMPORTANCE_LOW)
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
        // Optimistic UI: cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t trÃ¡ÂºÂ¡ng thÃƒÂ¡i ngay lÃ¡ÂºÂ­p tÃ¡Â»Â©c Ã„â€˜Ã¡Â»Æ’ nÃƒÂºt phÃ¡ÂºÂ£n hÃ¡Â»â€œi tÃ¡Â»Â©c thÃƒÂ¬
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
                        // Rollback nÃ¡ÂºÂ¿u server tÃ¡Â»Â« chÃ¡Â»â€˜i
                        withContext(Dispatchers.Main) {
                            thumbPaused = action != "pause"
                        }
                    }
                }
                // Ã„ÂÃ¡Â»Â£i server xÃ¡Â»Â­ lÃƒÂ½ xong rÃ¡Â»â€œi mÃ¡Â»â€ºi refresh (trÃƒÂ¡nh race condition)
                kotlinx.coroutines.delay(1500)
                fetchThumbStatus()
            } catch (e: Exception) {
                // Rollback + log lÃ¡Â»â€”i
                withContext(Dispatchers.Main) {
                    thumbPaused = action != "pause"
                    repository.addSystemLog("WARNING", "Thumbnail", "Toggle pause thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(80)}")
                }
            }
        }
    }

    // Ã„ÂÃ¡Â»Å NH NGHÃ„Â¨A THÃ†Â¯ MÃ¡Â»Â¤C THÃƒâ„¢NG RÃƒÂC (DÃ¡ÂºÂ¥u chÃ¡ÂºÂ¥m Ã¡Â»Å¸ Ã„â€˜Ã¡ÂºÂ§u Ã„â€˜Ã¡Â»Æ’ Ã¡ÂºÂ©n thÃ†Â° mÃ¡Â»Â¥c trÃƒÂªn NAS)
    internal val TRASH_FOLDER_NAME = ".trash/"

    internal val urlStack = Stack<String>()

    var currentUrl by mutableStateOf("")
    // HÃƒâ‚¬M CONNECT_AND_LOAD BÃ¡Â»Å  XÃƒâ€œA BÃ¡Â»Å½ VÃƒÅ’ DÃ†Â¯ THÃ¡Â»ÂªA. SÃ¡ÂºÂ¼ DÃƒâ„¢NG HÃƒâ‚¬M CONNECT CHÃƒÂNH THÃ¡Â»Â¨C NÃ¡ÂºÂ°M Ã¡Â»Å¾ CUÃ¡Â»ÂI FILE.

    fun openFolder(file: NasFile) {
        urlStack.push(currentUrl)
        currentUrl = if (file.path.endsWith("/")) file.path else "${file.path}/"

        // SÃ¡Â»Â¬A LÃ¡Â»â€“I: XÃƒÂ³a trÃ¡ÂºÂ¯ng mÃƒÂ n hÃƒÂ¬nh lÃ¡ÂºÂ­p tÃ¡Â»Â©c Ã„â€˜Ã¡Â»Æ’ dÃ¡Â»Ân luÃ¡Â»â€œng mÃ¡ÂºÂ¡ng vÃƒÂ  bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u tÃ¡ÂºÂ£i giao diÃ¡Â»â€¡n mÃ¡Â»â€ºi trÃ†Â¡n tru
        fileList = emptyList()
        isLoading = true

        // LOG: Ghi nhÃ¡ÂºÂ­t kÃƒÂ½ mÃ¡Â»Å¸ thÃ†Â° mÃ¡Â»Â¥c
        viewModelScope.launch(Dispatchers.IO) {
            // Removed folder navigation log
        }

        loadCurrentUrl()
    }
    fun openSpecificUrl(url: String, title: String) {
        // Fix cÃƒÂº phÃƒÂ¡p vÃƒÂ  Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢ tiÃƒÂªu Ã„â€˜Ã¡Â»Â Sub-menu
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
            if (title == "ThÃƒÂ¹ng rÃƒÂ¡c") {
                try { webDavManager.createFolder(targetUrl) } catch(e: Exception) {}
            }
            withContext(Dispatchers.Main) { loadCurrentUrl() }
        }
    }

    fun refresh() {
        // SÃ¡Â»Â¬A LÃ¡Â»â€“I REFRESH: PhÃƒÂ¢n loÃ¡ÂºÂ¡i Ã„â€˜Ã¡Â»Æ’ gÃ¡Â»Âi Ã„â€˜ÃƒÂºng hÃƒÂ m truy vÃ¡ÂºÂ¥n DB cho sub-menu
        if (isSpecialMode) {
            when (specialTitle) {
                "Ã¡ÂºÂ¢nh mÃ¡Â»â€ºi nhÃ¡ÂºÂ¥t" -> showLatestPhotos()
                "Video gÃ¡ÂºÂ§n Ã„â€˜ÃƒÂ¢y" -> showRecentVideos()
                else -> loadCurrentUrl(forceRefresh = true) // Cho ThÃƒÂ¹ng rÃƒÂ¡c
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

            // SÃ¡Â»Â¬A LÃ¡Â»â€“I: NhÃ†Â°Ã¡Â»Âng toÃƒÂ n bÃ¡Â»â„¢ bÃ„Æ’ng thÃƒÂ´ng cho lÃ¡Â»â€¡nh lÃƒÂ¹i thÃ†Â° mÃ¡Â»Â¥c
            fileList = emptyList()
            isLoading = true

            // LOG: Ghi nhÃ¡ÂºÂ­t kÃƒÂ½ lÃƒÂ¹i thÃ†Â° mÃ¡Â»Â¥c
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
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Ã¡ÂºÂ¢nh mÃ¡Â»â€ºi nhÃ¡ÂºÂ¥t" }
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            val photos = repository.getLatestPhotos()
            withContext(Dispatchers.Main) { fileList = photos; isLoading = false }
        }
    }

    fun showRecentVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "Video gÃ¡ÂºÂ§n Ã„â€˜ÃƒÂ¢y" }
            try { repository.getRemoteFilesAndCache(webDavManager.currentBaseUrl) } catch(e: Exception) {}
            val videos = repository.getRecentVideos()
            withContext(Dispatchers.Main) { fileList = videos; isLoading = false }
        }
    }
    // TÃƒÂNH NÃ„â€šNG TÃƒÅ’M KIÃ¡ÂºÂ¾M TOÃƒâ‚¬N CÃ¡ÂºÂ¦U
    fun searchGlobal(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; isSpecialMode = true; specialTitle = "TÃƒÂ¬m kiÃ¡ÂºÂ¿m: $keyword"; urlStack.clear() }
            val results = try { repository.searchGlobal(keyword) } catch(e: Exception) { emptyList() }
            withContext(Dispatchers.Main) { fileList = results; isLoading = false }
        }
    }
    // TÃƒÂNH NÃ„â€šNG Ã„ÂIÃ¡Â»â‚¬U KHIÃ¡Â»â€šN NGUÃ¡Â»â€™N VÃƒâ‚¬ DÃ¡Â»Å CH VÃ¡Â»Â¤

    // GÃ¡Â»Â¬I LINK TÃ¡ÂºÂ¢I XUÃ¡Â»ÂNG TÃ¡Â»Âª XA CHO NAS (QBITTORRENT / WGET)
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

        // BÃƒÂ¡o UI Ã„â€˜ang xÃ¡Â»Â­ lÃƒÂ½ thÃƒÂ´ng qua Notification do chÃ¡ÂºÂ¡y ngÃ¡ÂºÂ§m
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "TÃƒÂ¡c vÃ¡Â»Â¥ giÃ¡ÂºÂ£i nÃƒÂ©n ($fileName) Ã„â€˜ang chÃ¡ÂºÂ¡y ngÃ¡ÂºÂ§m trÃƒÂªn NAS!"
        showCommonDialog = true

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡ÂºÂ©y sang LongRunningApiWorker (Foreground Service)
        // Ã¢â€ â€™ TÃ¡ÂºÂ¯t App vÃ¡ÂºÂ«n chÃ¡ÂºÂ¡y, hiÃ¡Â»Æ’n thÃ¡Â»â€¹ Notification tiÃ¡ÂºÂ¿n trÃƒÂ¬nh
        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "UNZIP")
            .putString("apiUrl", "${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/file/unzip")
            .putString("jsonBody", jsonBody)
            .putString("taskLabel", "GiÃ¡ÂºÂ£i nÃƒÂ©n $fileName")
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<LongRunningApiWorker>()
            .setInputData(inputData)
            .addTag("LONG_RUNNING_API")
            .build()

        val context = NasApplication.instance.applicationContext
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork("Unzip_$fileName", androidx.work.ExistingWorkPolicy.REPLACE, workRequest)

        // LÃ¡ÂºÂ¯ng nghe kÃ¡ÂºÂ¿t quÃ¡ÂºÂ£ tÃ¡Â»Â« Worker
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
                            commonDialogMessage = message.ifEmpty { "GiÃ¡ÂºÂ£i nÃƒÂ©n thÃƒÂ nh cÃƒÂ´ng!" }
                            showCommonDialog = true
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = message.ifEmpty { "GiÃ¡ÂºÂ£i nÃƒÂ©n thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i!" }
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
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                val text = localApiClient.newCall(request).execute().use { it.body?.string() ?: "" }
                withContext(Dispatchers.Main) {
                    val o = try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
                    commonDialogMessage = if (o.optString("result") == "ok")
                        "Ã¢Å“â€¦ Ã„ÂÃƒÂ£ gÃ¡Â»Â­i link cho qBittorrent. Theo dÃƒÂµi tiÃ¡ÂºÂ¿n trÃƒÂ¬nh Ã¡Â»Å¸ Dashboard."
                    else "Ã¢ÂÅ’ LÃ¡Â»â€”i: ${o.optString("error", "khÃƒÂ´ng phÃ¡ÂºÂ£n hÃ¡Â»â€œi")}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                    showCommonDialog = true
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogMessage = "Ã¢ÂÅ’ LÃ¡Â»â€”i mÃ¡ÂºÂ¡ng: ${e.message?.take(120)}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            }
        }
    }

    /** Upload 1 file .torrent len NAS Ã¢â€ â€™ qBittorrent.
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
                    ?: throw IllegalArgumentException("KhÃƒÂ´ng Ã„â€˜Ã¡Â»Âc Ã„â€˜Ã†Â°Ã¡Â»Â£c nÃ¡Â»â„¢i dung file")
                if (bytes.size < 64) throw IllegalArgumentException("File .torrent quÃƒÂ¡ nhÃ¡Â»Â")
                if (bytes[0].toInt().toChar() != 'd') throw IllegalArgumentException("File khÃƒÂ´ng phÃ¡ÂºÂ£i Ã„â€˜Ã¡Â»â€¹nh dÃ¡ÂºÂ¡ng torrent hÃ¡Â»Â£p lÃ¡Â»â€¡")

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
                        "Ã¢Å“â€¦ Ã„ÂÃƒÂ£ gÃ¡Â»Â­i $safeName cho qBittorrent (${o.optInt("size")} bytes)"
                    else "Ã¢ÂÅ’ LÃ¡Â»â€”i: ${o.optString("error", "khÃƒÂ´ng phÃ¡ÂºÂ£n hÃ¡Â»â€œi")}"
                    commonDialogType = if (o.optString("result") == "ok") com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS else com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogMessage = "Ã¢ÂÅ’ LÃ¡Â»â€”i upload torrent: ${e.message?.take(120)}"
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    showCommonDialog = true
                }
            }
        }
    }

    // ============ GHI HÃƒÅ’NH LIVESTREAM (TikTok / Facebook / YouTube) ============
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
                        .thenBy { if (it.speed.isNotBlank() && it.speed != "Ã¢â‚¬â€") 1 else 0 }
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
    
    // Danh sÃƒÂ¡ch cÃƒÂ¡c stream Ã„â€˜ang ghi
    var activeLivestreams = androidx.compose.runtime.mutableStateListOf<LivestreamJob>()
        private set
    internal var lastLivestreamServerSyncAt = 0L
    internal var lastLivestreamServerRecordingIds: Set<String> = emptySet()
    var livestreamMessage by mutableStateOf("")
        private set

    /** UI gÃ¡Â»Âi Ã„â€˜Ã¡Â»Æ’ xÃƒÂ³a message lÃ¡Â»â€”i, hiÃ¡Â»â€¡n lÃ¡ÂºÂ¡i nÃƒÂºt "BÃ¡ÂºÂ®T Ã„ÂÃ¡ÂºÂ¦U GHI" */
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
                throw IllegalStateException(result.optString("error", "NAS tÃ¡Â»Â« chÃ¡Â»â€˜i (${response.code})"))
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
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "LÃ¡Â»â€”i: ${e.message?.take(80) ?: "ChÃ†Â°a kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i NAS"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: thÃƒÂªm tÃƒÂ i khoÃ¡ÂºÂ£n theo dÃƒÂµi live @$clean.")
                // BÃ¡ÂºÂ¯t job ngay nÃ¡ÂºÂ¿u user vÃ¡Â»Â«a thÃƒÂªm Ã„â€˜ang live - khÃƒÂ´ng chÃ¡Â»Â 15p chu kÃ¡Â»Â³ Discovery.
                syncLivestreamStateWithServer(context)
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: thÃƒÂªm tÃƒÂ i khoÃ¡ÂºÂ£n @$clean thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "LÃ¡Â»â€”i: ${e.message?.take(80) ?: "KhÃƒÂ´ng thÃƒÂªm Ã„â€˜Ã†Â°Ã¡Â»Â£c ngÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: xoÃƒÂ¡ tÃƒÂ i khoÃ¡ÂºÂ£n theo dÃƒÂµi live @$username.")
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: xoÃƒÂ¡ tÃƒÂ i khoÃ¡ÂºÂ£n @$username thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "LÃ¡Â»â€”i: ${e.message?.take(80) ?: "KhÃƒÂ´ng xoÃƒÂ¡ Ã„â€˜Ã†Â°Ã¡Â»Â£c ngÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng"}" }
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
                repository.addSystemLog("INFO", "TikTokWatch", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t khung loÃ¡ÂºÂ¡i trÃ¡Â»Â« TikTok Watch (${if (enabled) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"}, $start-$end).")
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "TikTokWatch", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t cÃ¡ÂºÂ¥u hÃƒÂ¬nh TikTok Watch thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { tiktokLiveWatchError = "LÃ¡Â»â€”i: ${e.message?.take(80) ?: "KhÃƒÂ´ng lÃ†Â°u Ã„â€˜Ã†Â°Ã¡Â»Â£c cÃ¡ÂºÂ¥u hÃƒÂ¬nh"}" }
            }
        }
    }

    fun startLivestreamRecord(context: Context, url: String, quality: String = "best", referer: String = "", userAgent: String = "") {
        isStartingLivestream = true
        livestreamMessage = "Ã„Âang phÃƒÂ¢n tÃƒÂ­ch liÃƒÂªn kÃ¡ÂºÂ¿t & kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i..."
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
                    throw IllegalStateException("ChÃ†Â°a cÃƒÂ³ Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° NAS hÃ¡Â»Â£p lÃ¡Â»â€¡")
                }
                val requestBuilder = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/livestream/record")
                    .post(body)

                val user = SecurePrefsHelper.getUser(context)
                val pass = SecurePrefsHelper.getPass(context)
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                }

                // FIX: dÃƒÂ¹ng client cÃƒÂ³ timeout dÃƒÂ i hÃ†Â¡n (60s) chÃ¡Â»â€° riÃƒÂªng cho call nÃƒÂ y Ã¢â‚¬â€
                // preflight check TikTok cÃƒÂ³ thÃ¡Â»Æ’ tÃ¡Â»â€˜n 20-30s (curl HTML + probe FLV).
                // KhÃƒÂ´ng tÃ„Æ’ng timeout cÃ¡Â»Â§a client mÃ¡ÂºÂ·c Ã„â€˜Ã¡Â»â€¹nh vÃƒÂ¬ cÃƒÂ¡c endpoint khÃƒÂ¡c phÃ¡ÂºÂ£i
                // trÃ¡ÂºÂ£ kÃ¡ÂºÂ¿t quÃ¡ÂºÂ£ nhanh.
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
                        repository.addSystemLog("INFO", "Livestream", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u ghi livestream ${tiktokUsername.ifBlank { url.take(80) }} chÃ¡ÂºÂ¥t lÃ†Â°Ã¡Â»Â£ng $quality.")

                        // ThÃƒÂªm vÃƒÂ o danh sÃƒÂ¡ch active (mÃ¡ÂºÂ·c Ã„â€˜Ã¡Â»â€¹nh trÃ¡ÂºÂ¡ng thÃƒÂ¡i recording)
                        withContext(Dispatchers.Main) {
                            if (activeLivestreams.none { it.jobId == jobId }) {
                                activeLivestreams.add(WebDavViewModel.LivestreamJob(jobId, platform, watchUsername = tiktokUsername))
                            }
                            livestreamMessage   = json.optString("message", "Ã„Âang khÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng ghi hÃƒÂ¬nh...")
                        }

                        // KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng Foreground Worker Ã„â€˜Ã¡Â»â„¢c lÃ¡ÂºÂ­p vÃ¡Â»â€ºi vÃƒÂ²ng Ã„â€˜Ã¡Â»Âi app
                        LivestreamMonitorWorker.enqueue(context, jobId, host, platform)

                        // Observe tiÃ¡ÂºÂ¿n trÃƒÂ¬nh tÃ¡Â»Â« Worker Ã„â€˜Ã¡Â»Æ’ cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t UI
                        observeLivestreamWorker(context)

                        // FIX: TÃ¡Â»Â± Ã„â€˜Ã¡Â»â„¢ng thÃƒÂªm username vÃƒÂ o watcher list nÃ¡ÂºÂ¿u chÃ†Â°a cÃƒÂ³ Ã¢â‚¬â€
                        // Ã„â€˜Ã¡ÂºÂ£m bÃ¡ÂºÂ£o mÃ¡Â»â€”i lÃƒÂºc user ghi 1 live mÃ¡Â»â€ºi qua link, lÃ¡ÂºÂ§n sau watcher
                        // sÃ¡ÂºÂ½ tÃ¡Â»Â± phÃƒÂ¡t hiÃ¡Â»â€¡n vÃƒÂ  auto-record. KhÃƒÂ´ng dÃ¡Â»Â±a vÃƒÂ o logic Ã¡Â»Å¸ Dialog
                        // (Ã„â€˜Ã¡Â»Æ’ robust trong mÃ¡Â»Âi flow gÃ¡Â»Âi startLivestreamRecord).
                        if (tiktokUsername.isNotBlank() &&
                            tiktokLiveWatchUsers.none { it.username.equals(tiktokUsername, ignoreCase = true) }) {
                            addTikTokLiveWatchUser(context, tiktokUsername)
                        }
                    } else {
                        // FIX: server tra error CU THE qua field "error" + "reason"
                        // Map HTTP code de hien icon/mau dialog hop ly.
                        val errMsg = json.optString("error", "").ifBlank {
                            "LÃ¡Â»â€”i NAS (HTTP ${response.code}): ${rawBody.take(150)}"
                        }
                        withContext(Dispatchers.Main) {
                            livestreamMessage = errMsg
                            commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            commonDialogMessage = errMsg
                            showCommonDialog    = true
                        }
                        repository.addSystemLog("WARNING", "Livestream", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u ghi livestream thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${errMsg.take(120)}")
                    }
                }
            } catch (e: Exception) {
                // FIX: phan loai exception cu the thay vi "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i NAS: null"
                val errMsg = when (e) {
                    is java.net.SocketTimeoutException ->
                        "NAS chÃ†Â°a trÃ¡ÂºÂ£ kÃ¡ÂºÂ¿t quÃ¡ÂºÂ£ kÃ¡Â»â€¹p khi phÃƒÂ¢n tÃƒÂ­ch link TikTok. KhÃƒÂ´ng tÃ¡ÂºÂ¡o thÃƒÂªm phiÃƒÂªn trÃƒÂ¹ng; hÃƒÂ£y chÃ¡Â»Â trÃ¡ÂºÂ¡ng thÃƒÂ¡i ghi cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t rÃ¡Â»â€œi thÃ¡Â»Â­ lÃ¡ÂºÂ¡i nÃ¡ÂºÂ¿u chÃ†Â°a thÃ¡ÂºÂ¥y chÃ¡ÂºÂ¡y."
                    is java.net.ConnectException ->
                        "KhÃƒÂ´ng kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i Ã„â€˜Ã†Â°Ã¡Â»Â£c NAS. KiÃ¡Â»Æ’m tra: NAS cÃƒÂ³ Ã„â€˜ang chÃ¡ÂºÂ¡y khÃƒÂ´ng? Tailscale cÃƒÂ³ bÃ¡ÂºÂ­t khÃƒÂ´ng?"
                    is java.net.UnknownHostException ->
                        "KhÃƒÂ´ng tÃƒÂ¬m thÃ¡ÂºÂ¥y NAS (DNS/Tailscale lÃ¡Â»â€”i). KiÃ¡Â»Æ’m tra lÃ¡ÂºÂ¡i Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i."
                    is javax.net.ssl.SSLException ->
                        "LÃ¡Â»â€”i SSL: ${e.message ?: "ChÃ¡Â»Â©ng chÃ¡Â»â€° NAS khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡"}"
                    else -> {
                        val raw = e.message?.take(200)
                        if (raw.isNullOrBlank()) "LÃ¡Â»â€”i ${e.javaClass.simpleName} khÃƒÂ´ng cÃƒÂ³ chi tiÃ¡ÂºÂ¿t"
                        else "LÃ¡Â»â€”i: $raw"
                    }
                }
                withContext(Dispatchers.Main) {
                    livestreamMessage   = errMsg
                    commonDialogType    = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = errMsg
                    showCommonDialog    = true
                }
                repository.addSystemLog("WARNING", "Livestream", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u ghi livestream thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${errMsg.take(120)}")
            } finally {
                withContext(Dispatchers.Main) {
                    isStartingLivestream = false
                }
            }
        }
    }

    /** GÃ¡Â»Âi 1 lÃ¡ÂºÂ§n khi app mÃ¡Â»Å¸ lÃ¡ÂºÂ¡i Ã¢â‚¬â€ tÃ¡Â»Â± Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢ lÃ¡ÂºÂ¡i trÃ¡ÂºÂ¡ng thÃƒÂ¡i tÃ¡Â»Â« cÃƒÂ¡c Worker Ã„â€˜ang chÃ¡ÂºÂ¡y ngÃ¡ÂºÂ§m */
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

    /** GÃ¡Â»Âi ngÃ¡ÂºÂ§m Ã„â€˜Ã¡Â»Æ’ quÃƒÂ©t cÃƒÂ¡c luÃ¡Â»â€œng Livestream bÃ¡Â»â€¹ "bÃ¡Â»Â quÃƒÂªn" (zombie streams) trÃƒÂªn NAS */
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
                                        speed = jobObj.optString("avg_speed", "Ã¢â‚¬â€"),
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
                                // NÃ¡ÂºÂ¿u tiÃ¡ÂºÂ¿n trÃƒÂ¬nh Ã„â€˜ang chÃ¡ÂºÂ¡y trÃƒÂªn NAS nhÃ†Â°ng Ã„â€˜iÃ¡Â»â€¡n thoÃ¡ÂºÂ¡i khÃƒÂ´ng biÃ¡ÂºÂ¿t (hoÃ¡ÂºÂ·c bÃ¡Â»â€¹ xoÃƒÂ¡ cache data)
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
                android.util.Log.e("LivestreamSync", "LÃ¡Â»â€”i Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢ trÃ¡ÂºÂ¡ng thÃƒÂ¡i livestream: ${e.message}")
                restoreLivestreamStateIfRunning(context)
            }
        }
    }

    private fun observeLivestreamWorker(context: Context) {
        // FIX: Cancel collector cÃ…Â© trÃ†Â°Ã¡Â»â€ºc khi tÃ¡ÂºÂ¡o mÃ¡Â»â€ºi, trÃƒÂ¡nh tÃƒÂ­ch lÃ…Â©y N collectors chÃ¡ÂºÂ¡y song song
        // gÃƒÂ¢y thrashing UI khi mÃ¡Â»â€”i collector Ã„â€˜Ã¡Â»Âu process toÃƒÂ n bÃ¡Â»â„¢ workInfoList
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
                            
                            // Worker hoÃƒÂ n tÃ¡ÂºÂ¥t
                            if (info.state.isFinished || status !in listOf(null, "recording")) {
                                activeLivestreams.removeAt(index)
                                livestreamMessage = when (status) {
                                    "finished" -> "Ã¢Å“â€¦ Ghi hÃƒÂ¬nh hoÃƒÂ n tÃ¡ÂºÂ¥t!"
                                    "stopped"  -> "Ã¢ÂÂ¹ Ã„ÂÃƒÂ£ dÃ¡Â»Â«ng ghi hÃƒÂ¬nh"
                                    "timeout"  -> "Ã¢ÂÂ° TÃ¡Â»Â± Ã„â€˜Ã¡Â»â„¢ng dÃ¡Â»Â«ng (quÃƒÂ¡ 12 giÃ¡Â»Â)"
                                    "error"    -> {
                                        val reason = progress.getString("error_reason")
                                            ?: info.outputData.getString("error_reason")
                                            ?: ""
                                        if (reason.isNotEmpty()) "LÃ¡Â»â€”i: $reason" else "LÃ¡Â»â€”i: NguÃ¡Â»â€œn Stream bÃ¡Â»â€¹ ngÃ¡ÂºÂ¯t / File quÃƒÂ¡ nhÃ¡Â»Â!"
                                    }
                                    else       -> "TrÃ¡ÂºÂ¡ng thÃƒÂ¡i bÃƒÂ¡o cÃƒÂ¡o: $status"
                                }
                            } else {
                                // FIX: TÃ¡ÂºÂ¡o copy vÃ¡Â»â€ºi tham sÃ¡Â»â€˜ mÃ¡Â»â€ºi thay vÃƒÂ¬ mutate var sau copy()
                                // Mutate var sau copy() khÃƒÂ´ng trigger Compose recomposition vÃƒÂ¬
                                // mutableStateListOf so sÃƒÂ¡nh object identity, khÃƒÂ´ng deep-compare
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
                // Cancel Worker trÃ†Â°Ã¡Â»â€ºc
                LivestreamMonitorWorker.cancelJob(context, jobId)

                // GÃ¡Â»Âi NAS stop API
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
                repository.addSystemLog("INFO", "Livestream", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: dÃ¡Â»Â«ng ghi livestream job $jobId.")
                withContext(Dispatchers.Main) {
                    activeLivestreams.removeAll { it.jobId == jobId }
                    livestreamMessage   = "Ã¢ÂÂ¹ Ã„ÂÃƒÂ£ dÃ¡Â»Â«ng ghi hÃƒÂ¬nh. File Ã„â€˜ang Ã„â€˜Ã†Â°Ã¡Â»Â£c xÃ¡Â»Â­ lÃƒÂ½..."
                }
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "Livestream", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: dÃ¡Â»Â«ng ghi livestream job $jobId thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { livestreamMessage = "LÃ¡Â»â€”i dÃ¡Â»Â«ng ghi: ${e.message}" }
            }
        }
    }


    // Ã„ÂÃƒÂNH THÃ¡Â»Â¨C NAS BÃ¡ÂºÂ°NG WAKE-ON-LAN (MAGIC PACKET)
    
    private fun loadCurrentUrl(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            errorMessage = null
            
            // SÃ¡Â»Â¬A LÃ¡Â»â€“I CHÃƒÂ MÃ¡ÂºÂ NG TÃ¡Â»Âª PHASE 1: LUÃƒâ€N LUÃƒâ€N KÃ¡ÂºÂ¾T NÃ¡Â»ÂI UI VÃ¡Â»Å¡I CSDL TRÃ†Â¯Ã¡Â»Å¡C TIÃƒÅ N!
            // Khi Paging Flow trÃƒÂ³i buÃ¡Â»â„¢c vÃƒÂ o Room DB, mÃ¡Â»Âi thay Ã„â€˜Ã¡Â»â€¢i dÃ¡Â»Â¯ liÃ¡Â»â€¡u tÃ¡Â»Â« NAS tÃ¡ÂºÂ£i vÃ¡Â»Â sÃ¡ÂºÂ½ lÃ¡ÂºÂ­p tÃ¡Â»Â©c bÃ¡ÂºÂ¯n lÃƒÂªn UI mÃ¡Â»â„¢t cÃƒÂ¡ch Auto!
            _pagedFilesFlow.value = repository.getFilesStream(currentUrl).cachedIn(viewModelScope)

            // LÃ¡ÂºÂ¥y danh sÃƒÂ¡ch tÃ„Â©nh Ã„â€˜Ã¡Â»Æ’ phÃ¡Â»Â¥c vÃ¡Â»Â¥ ImageViewerScreen
            val cached = repository.getCachedFiles(currentUrl)
            fileList = cached.map { 
                NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) 
            }.filter { !it.name.startsWith(".") || isSpecialMode }

            // TÃ¡Â»ÂI Ã†Â¯U SMART REFRESH: NÃ¡ÂºÂ¿u khÃƒÂ´ng ÃƒÂ©p buÃ¡Â»â„¢c Refresh vÃƒÂ  Cache Ã„â€˜ÃƒÂ£ cÃƒÂ³ sÃ¡ÂºÂµn dÃ¡Â»Â¯ liÃ¡Â»â€¡u thÃƒÂ¬ xong luÃƒÂ´n!
            if (!forceRefresh && cached.isNotEmpty()) {
                isLoading = false
                // ChÃ¡ÂºÂ¡y ngÃ¡ÂºÂ§m viÃ¡Â»â€¡c kiÃ¡Â»Æ’m tra cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t mÃƒÂ  khÃƒÂ´ng lÃƒÂ m treo UI
                launch(Dispatchers.IO) {
                    try { repository.getRemoteFilesAndCache(currentUrl) } catch (e: Exception) {}
                }
                return@launch
            }

            // NÃ¡ÂºÂ¿u lÃƒÂ  Force Refresh (vd: VÃ¡Â»Â«a Login xong) hoÃ¡ÂºÂ·c LÃ¡ÂºÂ§n Ã„â€˜Ã¡ÂºÂ§u vÃƒÂ o thÃ†Â° mÃ¡Â»Â¥c chÃ†Â°a cÃƒÂ³ Cache -> PhÃ¡ÂºÂ£i Ã„ÂÃ¡Â»Â£i
            isLoading = true

            try {
                // 3 & 4. UÃ¡Â»Â· quyÃ¡Â»Ân cho Repository tÃ¡ÂºÂ£i luÃ¡Â»â€œng NAS vÃƒÂ  chÃƒÂ¨n toÃƒÂ n bÃ¡Â»â„¢ vÃƒÂ o Room DB
                repository.getRemoteFilesAndCache(currentUrl)
                // LÃ¡ÂºÂ­p tÃ¡Â»Â©c CÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t lÃ¡ÂºÂ¡i FileList tÃ„Â©nh cho chÃ¡ÂºÂ¿ Ã„â€˜Ã¡Â»â„¢ xem Ã¡ÂºÂ£nh Full-Screen
                val refreshedCached = repository.getCachedFiles(currentUrl)
                fileList = refreshedCached.map { 
                    NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) 
                }.filter { !it.name.startsWith(".") || isSpecialMode }

                // LOG + IP: HiÃ¡Â»Æ’n thÃ¡Â»â€¹ IP NAS sau trÃ¡ÂºÂ¡ng thÃƒÂ¡i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i
                val nasHost = try { java.net.URL(currentUrl).host } catch (_: Exception) { "" }
                connectionStatus = if (nasHost.isNotEmpty()) "Ã„ÂÃƒÂ£ kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i LAN: $nasHost" else "Ã„ÂÃƒÂ£ kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i LAN"
            } catch (e: Exception) {
                connectionStatus = "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i" // Ãƒâ€°p cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t trÃ¡ÂºÂ¡ng thÃƒÂ¡i lÃ¡Â»â€”i ngay lÃ¡ÂºÂ­p tÃ¡Â»Â©c dÃƒÂ¹ cÃƒÂ³ Cache hay khÃƒÂ´ng
                viewModelScope.launch(Dispatchers.IO) {
                    repository.addSystemLog("ERROR", "Browser", "LÃ¡Â»â€”i tÃ¡ÂºÂ£i danh sÃƒÂ¡ch: ${e.message?.take(100)}")
                }
                if (fileList.isEmpty()) {
                    errorMessage = friendlyError(e)
                }
                // FIX LÃ¡Â»â€“I 5: Debounce - chÃ¡Â»â€° bÃ¡ÂºÂ­t dialog lÃ¡Â»â€”i mÃ¡ÂºÂ¡ng nÃ¡ÂºÂ¿u cÃƒÂ¡ch lÃ¡ÂºÂ§n trÃ†Â°Ã¡Â»â€ºc hÃ†Â¡n 2 phÃƒÂºt
                val now = System.currentTimeMillis()
                if (now - lastNetworkErrorDialogAt > NETWORK_ERROR_DIALOG_COOLDOWN_MS) {
                    lastNetworkErrorDialogAt = now
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = "MÃ¡ÂºÂ¥t kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i dÃ¡Â»Â¯ liÃ¡Â»â€¡u mÃƒÂ¡y chÃ¡Â»Â§ NAS:\n${e.message}"
                    showCommonDialog = true
                }
            } finally {
                isLoading = false
            }
        }
    }

    // HELPER: ChÃƒÂ¨n TÃƒÂ¡c vÃ¡Â»Â¥ vÃƒÂ o HÃƒÂ ng Ã„â€˜Ã¡Â»Â£i Offline WorkManager (TÃƒÂNH NÃ„â€šNG 5.I)
    private fun enqueueOfflineAction(context: Context, actionType: String, sourcePath: String, destPath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as NasApplication).database
                db.syncActionDao().insert(SyncAction(
                    actionType = actionType,
                    sourcePath = sourcePath,
                    destPath = destPath
                ))
                
                // BÃƒÂ¡o WorkManager chÃ¡ÂºÂ¡y khi cÃƒÂ³ mÃ¡ÂºÂ¡ng
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
                
                // HiÃ¡Â»Æ’n thÃ¡Â»â€¹ Dialog bÃƒÂ¡o cho User
                withContext(Dispatchers.Main) {
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING
                    commonDialogMessage = "KhÃƒÂ´ng cÃƒÂ³ kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i. LÃ¡Â»â€¡nh '$actionType' Ã„â€˜ÃƒÂ£ Ã„â€˜Ã†Â°Ã¡Â»Â£c Ã„â€˜Ã†Â°a vÃƒÂ o hÃƒÂ ng Ã„â€˜Ã¡Â»Â£i ngoÃ¡ÂºÂ¡i tuyÃ¡ÂºÂ¿n."
                    showCommonDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "LÃ¡Â»â€”i khi lÃ†Â°u hÃƒÂ ng Ã„â€˜Ã¡Â»Â£i ngoÃ¡ÂºÂ¡i tuyÃ¡ÂºÂ¿n: ${e.message}" }
            }
        }
    }

    fun deleteFile(context: Context, file: NasFile) {
        // TÃ¡Â»ÂI Ã†Â¯U CÃ¡Â»Â°C Ã„ÂÃ¡ÂºÂ I: UI LÃ¡ÂºÂ¡c quan (Optimistic UI)
        // Ã¡ÂºÂ¨n file ngay lÃ¡ÂºÂ­p tÃ¡Â»Â©c khÃ¡Â»Âi biÃ¡ÂºÂ¿n RAM mÃƒÂ  CHÃ†Â¯A CÃ¡ÂºÂ¦N Ã„â€˜Ã¡Â»Â£i NAS phÃ¡ÂºÂ£n hÃ¡Â»â€œi -> XÃƒÂ³a "TÃ¡Â»Â©c thÃƒÂ¬" (0ms)
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }

        // BÃƒâ€œC TÃƒÂCH: Ã„ÂÃ¡ÂºÂ©y viÃ¡Â»â€¡c liÃƒÂªn lÃ¡ÂºÂ¡c mÃ¡ÂºÂ¡ng NAS (chÃ¡ÂºÂ­m) vÃƒÂ o luÃ¡Â»â€œng ngÃ¡ÂºÂ§m I/O, giÃ¡ÂºÂ£i phÃƒÂ³ng luÃ¡Â»â€œng mÃƒÂ n hÃƒÂ¬nh UI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. TÃƒÂ¬m Ã„â€˜Ã†Â°Ã¡Â»Âng dÃ¡ÂºÂ«n gÃ¡Â»â€˜c cÃ¡Â»Â§a Ã¡Â»â€¢ Ã„â€˜Ã„Â©a (VD: /Data N300/)
                val relativePath = file.path.removePrefix(webDavManager.currentBaseUrl).trimStart('/')
                val driveName = relativePath.substringBefore('/')
                val trashUrl = webDavManager.currentBaseUrl + driveName + "/" + TRASH_FOLDER_NAME

                // 2. ChÃ¡ÂºÂ·n xoÃƒÂ¡ vÃ„Â©nh viÃ¡Â»â€¦n nÃ¡ÂºÂ¿u chÃ†Â°a nÃ¡ÂºÂ±m trong thÃƒÂ¹ng rÃƒÂ¡c
                if (!file.path.contains(TRASH_FOLDER_NAME)) {
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) {}
                    val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    var targetUrl = if (trashUrl.endsWith("/")) trashUrl + encodedName else "$trashUrl/$encodedName"
                    if (file.isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                    webDavManager.renameFile(file.path, targetUrl)
                    repository.addSystemLog("WARNING", "File Ops", "Ã„ÂÃƒÂ£ di chuyÃ¡Â»Æ’n tÃ¡Â»â€¡p '${file.name}' vÃƒÂ o ThÃƒÂ¹ng rÃƒÂ¡c Ã¡Â»â€¢ $driveName.")
                } else {
                    webDavManager.deleteFile(file.path)
                    repository.addSystemLog("WARNING", "File Ops", "Ã„ÂÃƒÂ£ XÃƒâ€œA VÃ„Â¨NH VIÃ¡Â»â€žN tÃ¡Â»â€¡p '${file.name}'.")
                }
                // TRIÃ¡Â»â€ T TIÃƒÅ U refresh() VÃ„Â¨NH VIÃ¡Â»â€žN: TrÃƒÂ¡nh tÃ¡ÂºÂ£i lÃ¡ÂºÂ¡i 5000 file chÃ¡Â»â€° vÃƒÂ¬ xÃƒÂ³a 1 thÃ¡ÂºÂ»
            } catch (e: Exception) {
                // NhÃ¡Â»â€œi lÃ¡ÂºÂ¡i file vÃƒÂ o giao diÃ¡Â»â€¡n nÃ¡ÂºÂ¿u rÃ¡Â»â€ºt mÃ¡ÂºÂ¡ng
                withContext(Dispatchers.Main) { fileList = oldList }
                
                // TÃƒÂNH NÃ„â€šNG 5.I: BÃ¡ÂºÂ«y lÃ¡Â»â€”i vÃƒÂ  tÃ¡Â»â€˜ng vÃƒÂ o HÃƒÂ ng Ã„ÂÃ¡Â»Â£i Offline
                repository.addSystemLog("WARNING", "File Ops", "XÃƒÂ³a tÃ¡Â»â€¡p '${file.name}' thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i, Ã„â€˜ÃƒÂ£ Ã„â€˜Ã†Â°a vÃƒÂ o hÃƒÂ ng Ã„â€˜Ã¡Â»Â£i ngoÃ¡ÂºÂ¡i tuyÃ¡ÂºÂ¿n: ${e.message?.take(80)}")
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

        // TÃ¡Â»ÂI Ã†Â¯U CÃ¡Â»Â°C Ã„ÂÃ¡ÂºÂ I: UI LÃ¡ÂºÂ¡c quan cho HÃƒâ‚¬NG LOÃ¡ÂºÂ T FILE
        // CÃƒÂ¹ng lÃƒÂºc bÃ¡Â»â€˜c hÃ†Â¡i 100+ file ra khÃ¡Â»Âi List Ã„â€˜Ã¡Â»Æ’ giao diÃ¡Â»â€¡n trÃ¡Â»â€˜ng ngay trong 0 mili-giÃƒÂ¢y!
        val pathsToDelete = filesToDelete.map { it.path }.toSet()
        fileList = fileList.filter { it.path !in pathsToDelete }

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡ÂºÂ©y toÃƒÂ n bÃ¡Â»â„¢ tÃƒÂ¡c vÃ¡Â»Â¥ sang BatchOperationWorker (Foreground Service)
        // Ã¢â€ â€™ TiÃ¡ÂºÂ¿n trÃƒÂ¬nh KHÃƒâ€NG BÃ¡Â»Å  HÃ¡Â»Â¦Y khi App tÃ¡ÂºÂ¯t, hiÃ¡Â»Æ’n thÃ¡Â»â€¹ trÃƒÂªn Notification Bar
        enqueueBatchOperation(context, "DELETE", filesToDelete, "")
    }

    fun batchCopyFiles(context: Context, filesToCopy: List<NasFile>, destUrl: String) {
        if (filesToCopy.isEmpty()) return

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡ÂºÂ©y tÃƒÂ¡c vÃ¡Â»Â¥ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "COPY", filesToCopy, destUrl)
    }

    fun batchMoveFiles(context: Context, filesToMove: List<NasFile>, destUrl: String) {
        if (filesToMove.isEmpty()) return

        // TÃ¡Â»â€˜i Ã†Â°u UI lÃ¡ÂºÂ¡c quan: GiÃ¡ÂºÂ¥u file ngay lÃ¡ÂºÂ­p tÃ¡Â»Â©c nÃ¡ÂºÂ¿u di chuyÃ¡Â»Æ’n ra khÃ¡Â»Âi thÃ†Â° mÃ¡Â»Â¥c hiÃ¡Â»â€¡n tÃ¡ÂºÂ¡i
        if (!destUrl.startsWith(currentUrl)) {
            val pathsToMove = filesToMove.map { it.path }.toSet()
            fileList = fileList.filter { it.path !in pathsToMove }
        }

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡ÂºÂ©y tÃƒÂ¡c vÃ¡Â»Â¥ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "MOVE", filesToMove, destUrl)
    }

    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
    // DISPATCH ENGINE: Ã„ÂÃ¡ÂºÂ©y tÃƒÂ¡c vÃ¡Â»Â¥ nÃ¡ÂºÂ·ng sang Foreground Worker
    // Worker chÃ¡ÂºÂ¡y Ã„â€˜Ã¡Â»â„¢c lÃ¡ÂºÂ­p vÃ¡Â»â€ºi Activity Ã¢â‚¬â€ TÃ¡ÂºÂ¯t App vÃ¡ÂºÂ«n hoÃ¡ÂºÂ¡t Ã„â€˜Ã¡Â»â„¢ng
    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
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
            errorMessage = "KhÃƒÂ´ng thÃ¡Â»Æ’ chuÃ¡ÂºÂ©n bÃ¡Â»â€¹ tÃƒÂ¡c vÃ¡Â»Â¥ hÃƒÂ ng loÃ¡ÂºÂ¡t: ${e.message}"
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

        // LÃ¡ÂºÂ¯ng nghe tiÃ¡ÂºÂ¿n trÃƒÂ¬nh tÃ¡Â»Â« Worker Ã„â€˜Ã¡Â»Æ’ cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t UI (nÃ¡ÂºÂ¿u App Ã„â€˜ang mÃ¡Â»Å¸)
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
                            // LÃƒÂ m mÃ¡Â»â€ºi danh sÃƒÂ¡ch file sau khi Worker hoÃƒÂ n tÃ¡ÂºÂ¥t
                            if (operation == "COPY" || (operation == "MOVE" && destUrl.startsWith(currentUrl))) {
                                refresh()
                            }
                        }
                    }
                }
        }
    }

    fun restoreFile(context: Context, file: NasFile) {
        // TÃ¡Â»ÂI Ã†Â¯U CÃ¡Â»Â°C Ã„ÂÃ¡ÂºÂ I: XÃƒÂ³a Ã¡ÂºÂ£o tÃ¡Â»Â©c thÃƒÂ¬ khÃ¡Â»Âi giao diÃ¡Â»â€¡n ThÃƒÂ¹ng rÃƒÂ¡c
        val oldList = fileList
        fileList = oldList.filter { it.path != file.path }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // KHÃƒâ€I PHÃ¡Â»Â¤C: Di chuyÃ¡Â»Æ’n file tÃ¡Â»Â« rÃƒÂ¡c vÃ¡Â»Â thÃ†Â° mÃ¡Â»Â¥c gÃ¡Â»â€˜c cÃ¡Â»Â§a NAS
                val targetUrl = webDavManager.currentBaseUrl + file.name
                webDavManager.renameFile(file.path, targetUrl)
                repository.addSystemLog("INFO", "File Ops", "Ã„ÂÃƒÂ£ khÃƒÂ´i phÃ¡Â»Â¥c tÃ¡Â»â€¡p '${file.name}' tÃ¡Â»Â« ThÃƒÂ¹ng rÃƒÂ¡c.")
                // BÃ¡Â»Â refresh()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList }
                repository.addSystemLog("WARNING", "File Ops", "KhÃƒÂ´i phÃ¡Â»Â¥c tÃ¡Â»â€¡p '${file.name}' thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i, Ã„â€˜ÃƒÂ£ Ã„â€˜Ã†Â°a vÃƒÂ o hÃƒÂ ng Ã„â€˜Ã¡Â»Â£i ngoÃ¡ÂºÂ¡i tuyÃ¡ÂºÂ¿n: ${e.message?.take(80)}")
                val targetUrl = webDavManager.currentBaseUrl + file.name
                enqueueOfflineAction(context, "RENAME", file.path, targetUrl)
            }
        }
    }

    fun restoreMultipleFiles(context: Context, filesToRestore: List<NasFile>) {
        if (filesToRestore.isEmpty()) return

        // TÃ¡Â»ÂI Ã†Â¯U CÃ¡Â»Â°C Ã„ÂÃ¡ÂºÂ I: UI LÃ¡ÂºÂ¡c quan cho HÃƒâ‚¬NG LOÃ¡ÂºÂ T FILE
        val pathsToRestore = filesToRestore.map { it.path }.toSet()
        fileList = fileList.filter { it.path !in pathsToRestore }

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡ÂºÂ©y tÃƒÂ¡c vÃ¡Â»Â¥ sang BatchOperationWorker (Foreground Service)
        enqueueBatchOperation(context, "RESTORE", filesToRestore, "")
    }
    fun renameFile(context: Context, file: NasFile, newName: String) {
        // TÃ¡Â»ÂI Ã†Â¯U CÃ¡Â»Â°C Ã„ÂÃ¡ÂºÂ I: Ã„ÂÃ¡Â»â€¢i tÃƒÂªn Ã¡ÂºÂ£o trÃƒÂªn bÃ¡Â»â„¢ nhÃ¡Â»â€º RAM -> TÃ¡Â»â€˜c Ã„â€˜Ã¡Â»â„¢ hiÃ¡Â»Æ’n thÃ¡Â»â€¹ 0s
        val oldList = fileList
        val newUrl = currentUrl + newName
        val renamedFile = file.copy(name = newName, path = newUrl)
        fileList = oldList.map { if (it.path == file.path) renamedFile else it }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDavManager.renameFile(file.path, newUrl)
                repository.addSystemLog("INFO", "File Ops", "Ã„ÂÃ¡Â»â€¢i tÃƒÂªn tÃ¡Â»â€¡p '${file.name}' thÃƒÂ nh '${newName}'.")
                // KhÃƒÂ´ng refresh() Ã„â€˜Ã¡Â»Æ’ chÃ¡Â»â€˜ng khÃ¡Â»Â±ng giao diÃ¡Â»â€¡n
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileList = oldList } // HoÃƒÂ n nguyÃƒÂªn tÃƒÂªn cÃ…Â©
                repository.addSystemLog("WARNING", "File Ops", "Ã„ÂÃ¡Â»â€¢i tÃƒÂªn '${file.name}' thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i, Ã„â€˜ÃƒÂ£ Ã„â€˜Ã†Â°a vÃƒÂ o hÃƒÂ ng Ã„â€˜Ã¡Â»Â£i ngoÃ¡ÂºÂ¡i tuyÃ¡ÂºÂ¿n: ${e.message?.take(80)}")
                enqueueOfflineAction(context, "RENAME", file.path, newUrl)
            }
        }
    }
    fun createFolder(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isLoading = true }
                // Ã„ÂÃ¡ÂºÂ£m bÃ¡ÂºÂ£o URL thÃ†Â° mÃ¡Â»Â¥c mÃ¡Â»â€ºi kÃ¡ÂºÂ¿t thÃƒÂºc bÃ¡ÂºÂ±ng dÃ¡ÂºÂ¥u gÃ¡ÂºÂ¡ch chÃƒÂ©o '/'
                val newFolderUrl = currentUrl + folderName + "/"
                webDavManager.createFolder(newFolderUrl)
                repository.addSystemLog("SUCCESS", "File Ops", "Ã„ÂÃƒÂ£ tÃ¡ÂºÂ¡o thÃ†Â° mÃ¡Â»Â¥c mÃ¡Â»â€ºi: '$folderName'")
                withContext(Dispatchers.Main) { refresh() } // TÃ¡ÂºÂ£i lÃ¡ÂºÂ¡i danh sÃƒÂ¡ch sau khi tÃ¡ÂºÂ¡o thÃƒÂ nh cÃƒÂ´ng
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "File Ops", "TÃ¡ÂºÂ¡o thÃ†Â° mÃ¡Â»Â¥c '$folderName' thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i, Ã„â€˜ÃƒÂ£ Ã„â€˜Ã†Â°a vÃƒÂ o hÃƒÂ ng Ã„â€˜Ã¡Â»Â£i ngoÃ¡ÂºÂ¡i tuyÃ¡ÂºÂ¿n: ${e.message?.take(80)}")
                val newFolderUrl = currentUrl + folderName + "/"
                enqueueOfflineAction(context, "CREATE_FOLDER", newFolderUrl)
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }
    // === Ã„ÂÃƒÂ£ gÃ¡Â»Â¡ bÃ¡Â»Â tÃƒÂ­nh nÃ„Æ’ng Upload lÃ¡ÂºÂ» tÃ¡ÂºÂ» vÃƒÂ  Ã„ÂÃ¡Â»â€œng bÃ¡Â»â„¢ ===

    /**
     * checkSmartNetwork() Ã¢â‚¬â€œ TÃ¡Â»Â± Ã„â€˜Ã¡Â»â„¢ng phÃƒÂ¡t hiÃ¡Â»â€¡n mÃ¡ÂºÂ¡ng vÃƒÂ  chuyÃ¡Â»Æ’n URL NAS phÃƒÂ¹ hÃ¡Â»Â£p.
     *
     * GÃ¡Â»Âi hÃƒÂ m nÃƒÂ y khi:
     *  - User vÃƒÂ o MainMenuScreen (resume app)
     *  - Dashboard refresh
     *  - User bÃ¡ÂºÂ¥m nÃƒÂºt refresh thÃ¡Â»Â§ cÃƒÂ´ng
     *
     * CÃ†Â¡ chÃ¡ÂºÂ¿:
     *  1. Ping gateway LAN (ASUS RT-N12: 192.168.100.254 port 80)
     *  2. NÃ¡ÂºÂ¿u PASS Ã¢â€ â€™ Ã„Âang Ã¡Â»Å¸ LAN Ã¢â€ â€™ reconnect bÃ¡ÂºÂ±ng URL LAN (Gigabit nhanh)
     *  3. NÃ¡ÂºÂ¿u FAIL Ã¢â€ â€™ Ra ngoÃƒÂ i Ã¢â€ â€™ reconnect bÃ¡ÂºÂ±ng URL Tailscale (100.90.135.102)
     */
    fun checkSmartNetwork(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Invalidate cache Ã„â€˜Ã¡Â»Æ’ buÃ¡Â»â„¢c kiÃ¡Â»Æ’m tra thÃ¡Â»Â±c sÃ¡Â»Â± (khÃƒÂ´ng dÃƒÂ¹ng kÃ¡ÂºÂ¿t quÃ¡ÂºÂ£ cÃ…Â©)
                SmartNetworkManager.invalidateCache()
                val activeUrl = SmartNetworkManager.getActiveBaseUrl(context)
                if (activeUrl.isEmpty()) return@launch
                
                val onLan = !isTailscaleUrl(activeUrl)
                withContext(Dispatchers.Main) {
                    isOnLan = onLan
                }

                // NÃ¡ÂºÂ¿u URL thÃ¡Â»Â±c tÃ¡ÂºÂ¿ khÃƒÂ¡c URL Ã„â€˜ang dÃƒÂ¹ng Ã¢â€ â€™ tÃ¡Â»Â± Ã„â€˜Ã¡Â»â„¢ng reconnect mÃ†Â°Ã¡Â»Â£t
                val currentBase = webDavManager.currentBaseUrl
                val safeActive = if (activeUrl.endsWith("/")) activeUrl else "$activeUrl/"
                if (safeActive != currentBase && currentBase.isNotEmpty()) {
                    val user = webDavManager.currentUser
                    val pass = webDavManager.currentPass
                    withContext(Dispatchers.Main) {
                        connectionStatus = if (onLan) "ChuyÃ¡Â»Æ’n sang LAN - Gigabit" else "ChuyÃ¡Â»Æ’n sang Tailscale VPN"
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            webDavManager.connect(safeActive, user, pass)
                            webDavManager.initConnection()
                            
                            // GÃ¡Â»Âi authorize Ã„â€˜Ã¡Â»Æ’ IP mÃ¡Â»â€ºi Ã„â€˜Ã†Â°Ã¡Â»Â£c thÃƒÂªm vÃƒÂ o whitelist/iptables trÃƒÂªn NAS
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
                        "ChuyÃ¡Â»Æ’n mÃ¡ÂºÂ¡ng: ${if (onLan) "LAN" else "Tailscale"} ($safeActive)"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("SmartSwitch", "LÃ¡Â»â€”i kiÃ¡Â»Æ’m tra mÃ¡ÂºÂ¡ng thÃƒÂ´ng minh: ${e.message}")
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
                startDashboardMonitoring(resetStatusPoll = false)
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
        connectionStatus = "Ã„ÂÃƒÂ£ dÃ¡Â»Â«ng Ã„â€˜Ã„Æ’ng nhÃ¡ÂºÂ­p"
    }

    fun connect(urlList: List<String>, user: String, pass: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            var lastErrorDetail = "KhÃƒÂ´ng rÃƒÂµ"

            withContext(Dispatchers.Main) {
                isLoading = true
                connectionStatus = "Ã„Âang kiÃ¡Â»Æ’m tra mÃƒÂ´i trÃ†Â°Ã¡Â»Âng LAN..."
                urlStack.clear()
            }

            // FIX LÃ¡Â»â€“I 7 B: Ã„ÂÃ¡Â»Âc credentials cÃ…Â© trÃ†Â°Ã¡Â»â€ºc bÃ†Â°Ã¡Â»â€ºc lÃ†Â°u tÃ¡ÂºÂ¡m, Ã„â€˜Ã¡Â»Æ’ cÃƒÂ³ thÃ¡Â»Æ’ REVERT nÃ¡ÂºÂ¿u handshake thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i
            val context = NasApplication.instance
            val oldUrlList = SecurePrefsHelper.getUrlList(context)
            val oldUser = SecurePrefsHelper.getUser(context)
            val oldPass = SecurePrefsHelper.getPass(context)

            withContext(Dispatchers.IO) {
                SecurePrefsHelper.saveCredentials(context, urlList, user, pass)
            }

            // FIX: ThÃ¡Â»Â­ lÃ¡ÂºÂ§n lÃ†Â°Ã¡Â»Â£t tÃ¡Â»Â«ng URL (LAN Ã¢â€ â€™ Tailscale) mÃƒÂ  khÃƒÂ´ng gÃƒÂ¢y race condition
            // VÃƒÂ²ng lÃ¡ÂºÂ·p tuÃ¡ÂºÂ§n tÃ¡Â»Â± trÃƒÂ¡nh lÃ¡Â»â€”i split-tunneling cache cÃ¡Â»Â§a Android
            val errorDetails = mutableListOf<String>()
            var connectedUrl = ""
            var result = false

            val result2 = withContext(Dispatchers.IO) {
                if (urlList.isEmpty()) {
                    lastErrorDetail = "KhÃƒÂ´ng cÃƒÂ³ URL Ã„â€˜Ã¡Â»Æ’ kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i"
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
                            connectionStatus = "Ã„Âang kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i: $safeUrl"
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
                                    channel.send(Pair(false, "$activeUrl: WebDAV tÃ¡Â»Â« chÃ¡Â»â€˜i xÃƒÂ¡c thÃ¡Â»Â±c (HTTP ${response.code})"))
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("NAS_AUTH", "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i $activeUrl: ${e.message}")
                            channel.send(Pair(false, "$activeUrl: ${e.message ?: "MÃ¡ÂºÂ¡ng quÃƒÂ¡ hÃ¡ÂºÂ¡n"}"))
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
                        connectionStatus = "Ã„ÂÃƒÂ£ kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i: $successUrl"
                        isOnLan = !isTailscaleUrl(successUrl)
                    }

                    webDavManager.connect(successUrl, user, pass)
                    SecurePrefsHelper.saveCredentials(NasApplication.instance, urlList, user, pass)
                    repository.addSystemLog("SUCCESS", "Network", "Truy cÃ¡ÂºÂ­p WebDAV thÃƒÂ nh cÃƒÂ´ng qua User '$user' tÃ¡ÂºÂ¡i IP: $successUrl")

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
                                android.util.Log.w("NAS_AUTH", "API PhÃ¡Â»Â¥ Warning: ${e.message}")
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
                    connectionStatus = "Ã„ÂÃƒÂ£ xÃƒÂ¡c thÃ¡Â»Â±c thÃƒÂ nh cÃƒÂ´ng"
                    onSuccess()
                } else {
                    connectionStatus = "LÃ¡Â»â€”i xÃƒÂ¡c thÃ¡Â»Â±c"
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
        // CÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t trÃ¡ÂºÂ¡ng thÃƒÂ¡i Auto Backup tÃ¡Â»Â« WorkManager
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
                            autoBackupCurrentFile = workInfo.progress.getString("fileName") ?: "Ã„Âang sao lÃ†Â°u..."
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

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡Â»â€œng bÃ¡Â»â„¢ hÃƒÂ³a khÃƒÂ©p kÃƒÂ­n (KhÃƒÂ´i phÃ¡Â»Â¥c UI State khi App tÃƒÂ¡i khÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng tÃ¡Â»Â« cÃƒÂµi chÃ¡ÂºÂ¿t)
        val workManager = androidx.work.WorkManager.getInstance(NasApplication.instance.applicationContext)
        
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("BATCH_OPERATION").collect { workInfos ->
                val active = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                if (active != null) {
                    isBatchProcessing = true
                    batchProcessProgress = active.progress.getInt("percent", 0).toFloat() / 100f
                    batchProcessCurrentFile = active.progress.getString("currentFile") ?: "KhÃƒÂ´i phÃ¡Â»Â¥c Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢..."
                } else if (isBatchProcessing) {
                    isBatchProcessing = false
                    refresh() // CÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t lÃ¡ÂºÂ¡i danh sÃƒÂ¡ch file khi Background Worker vÃ¡Â»Â«a hoÃƒÂ n tÃ¡ÂºÂ¥t
                }
            }
        }

        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("STREAM_PIPE_TASK").collect { workInfos ->
                val active = workInfos.find { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                if (active != null) {
                    isStreamPiping = true
                    streamPipeStatus = "KhÃƒÂ´i phÃ¡Â»Â¥c Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢: " + (active.progress.getString("status") ?: "Ã„Âang tÃ¡ÂºÂ£i ngÃ¡ÂºÂ§m...")
                    streamPipeProgress = active.progress.getInt("progress", 0).toFloat() / 100f
                    streamPipeSpeedStr = active.progress.getString("speedStr") ?: "Ã„ÂÃ¡Â»â€œng bÃ¡Â»â„¢..."
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
                        organizingLegacyResult = active.progress.getString("status") ?: "Ã„Âang gom video ngÃ¡ÂºÂ§m..."
                    }
                }
            }
        }
    
        startDashboardMonitoring(resetStatusPoll = false)

        // FIX A2: Thay vÃƒÂ²ng lÃ¡ÂºÂ·p polling while(true){delay(32)} bÃ¡ÂºÂ±ng combine() trÃƒÂªn StateFlow.
        // CÃ…Â©: VÃƒÂ²ng lÃ¡ÂºÂ·p chÃ¡ÂºÂ¡y liÃƒÂªn tÃ¡Â»Â¥c @30fps kÃ¡Â»Æ’ cÃ¡ÂºÂ£ khi khÃƒÂ´ng scan Ã¢â€ â€™ tiÃƒÂªu hao CPU/pin vÃƒÂ´ ÃƒÂ­ch.
        // MÃ¡Â»â€ºi: ChÃ¡Â»â€° emit khi mÃ¡Â»â„¢t trong cÃƒÂ¡c StateFlow thÃ¡Â»Â±c sÃ¡Â»Â± thay Ã„â€˜Ã¡Â»â€¢i Ã¢â€ â€™ 0% CPU khi idle.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                DuplicateProgressState.stage,
                DuplicateProgressState.currentFolderUrl,
                DuplicateProgressState.percent,
                DuplicateProgressState.scannedCount,
                DuplicateProgressState.elapsedTime
            ) { stage, folderUrl, percent, scanned, elapsed ->
                // TrÃ¡ÂºÂ£ vÃ¡Â»Â tuple Ã„â€˜Ã¡Â»Æ’ trigger collector khi BÃ¡ÂºÂ¤T KÃ¡Â»Â² field nÃƒÂ o thay Ã„â€˜Ã¡Â»â€¢i
                arrayOf<Any?>(stage, folderUrl, percent, scanned, elapsed)
            }.collect {
                // Ã„ÂÃ¡Â»â€œng bÃ¡Â»â„¢ toÃƒÂ n bÃ¡Â»â„¢ state tÃ¡Â»Â« DuplicateProgressState Ã¢â€ â€™ ViewModel state
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

                // FIX (BUG: dialog tÃ¡Â»Â± pop-up lÃ¡ÂºÂ¡i khi user bÃ¡ÂºÂ¥m Thu nhÃ¡Â»Â):
                // ChÃ¡Â»â€° cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t isWorkerRunning Ã¢â‚¬â€ KHÃƒâ€NG tÃ¡Â»Â± Ã„â€˜Ã¡Â»â„¢ng set isScanningDuplicates = true.
                // Dialog hiÃ¡Â»Æ’n thÃ¡Â»â€¹ do user chÃ¡Â»Â§ Ã„â€˜Ã¡Â»â„¢ng mÃ¡Â»Å¸ (qua nÃƒÂºt QuÃƒÂ©t hoÃ¡ÂºÂ·c chip "Thu nhÃ¡Â»Â").
                // TrÃ†Â°Ã¡Â»â€ºc Ã„â€˜ÃƒÂ¢y, mÃ¡Â»â€”i tick progress collector Ã„â€˜Ã¡ÂºÂ·t isScanningDuplicates=true ->
                // user khÃƒÂ´ng thÃ¡Â»Æ’ Thu nhÃ¡Â»Â/HÃ¡Â»Â§y/TÃ¡ÂºÂ¡m dÃ¡Â»Â«ng Ã„â€˜Ã†Â°Ã¡Â»Â£c vÃƒÂ¬ dialog tÃ¡Â»Â± bÃ¡ÂºÂ­t lÃ¡ÂºÂ¡i 30ms sau.
                val stage = scanDuplicatesStage
                if (stage != "HoÃƒÂ n tÃ¡ÂºÂ¥t" && stage.isNotEmpty() && stage != "KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng...") {
                    isWorkerRunning = true
                } else if (stage == "HoÃƒÂ n tÃ¡ÂºÂ¥t") {
                    isWorkerRunning = false
                }
            }
        }

        // KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng vÃƒÂ²ng lÃ¡ÂºÂ·p kiÃ¡Â»Æ’m tra sÃ¡Â»Â©c khoÃ¡ÂºÂ» mÃ¡ÂºÂ¡ng (Ping ICMP siÃƒÂªu nhÃ¡ÂºÂ¹)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                if (webDavManager.currentBaseUrl.isNotEmpty()) {
                    val ms = webDavManager.checkPingServer()
                    withContext(Dispatchers.Main) { networkPingMs = ms }
                    // Giao thÃ¡Â»Â©c ICMP Ping tÃ¡Â»â€˜n hÃ¡ÂºÂ§u nhÃ†Â° khÃƒÂ´ng Ã„â€˜ÃƒÂ¡ng biÃ¡Â»Æ’u Ã„â€˜Ã¡Â»â€œ mÃƒÂ¡y, cho phÃƒÂ©p quÃƒÂ©t 3s/lÃ¡ÂºÂ§n!
                    kotlinx.coroutines.delay(3000)
                } else {
                    // NÃ¡ÂºÂ¿u chÃ†Â°a Login xong thÃƒÂ¬ Ã„â€˜Ã¡Â»Â£i 1s hÃ¡Â»Âi lÃ¡ÂºÂ¡i, trÃƒÂ¡nh viÃ¡Â»â€¡c bÃ¡ÂºÂ¯t User Ã„â€˜Ã¡Â»Â£i tÃ¡ÂºÂ­n 30s mÃ¡Â»â€ºi chÃ¡Â»Âc Ping
                    kotlinx.coroutines.delay(1000)
                }
            }
        }

        // KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng vÃƒÂ²ng lÃ¡ÂºÂ·p lÃ¡ÂºÂ¥y metrics biÃ¡Â»Æ’u Ã„â€˜Ã¡Â»â€œ:
        // ChÃ¡Â»Â cho URL sÃ¡ÂºÂµn sÃƒÂ ng rÃ¡Â»â€œi mÃ¡Â»â€ºi fetch lÃ¡ÂºÂ§n Ã„â€˜Ã¡ÂºÂ§u, sau Ã„â€˜ÃƒÂ³ poll mÃ¡Â»â€”i 30s
        startDashboardMonitoring(resetStatusPoll = false)
    }

    // DÃ¡Â»Ân cÃƒÂ¡c listener (nÃ¡ÂºÂ¿u cÃƒÂ³)

    // =======================================================
    // ======== BIÃ¡Â»â€šU Ã„ÂÃ¡Â»â€™ GIÃƒÂM SÃƒÂT + BÃƒÂO CÃƒÂO NGÃƒâ‚¬Y ============
    // =======================================================

    fun startDashboardMonitoring(resetStatusPoll: Boolean = false) {
        android.util.Log.d("DashboardMonitor", "startDashboardMonitoring resetStatusPoll=$resetStatusPoll statusActive=${statusJob?.isActive} dashboardActive=${dashboardRealtimeJob?.isActive} metricsActive=${metricsPollingJob?.isActive}")
        listenToLocalNasApi(forceRestart = resetStatusPoll)
        launchDashboardRealtimeScheduler()
        launchMetricsPolling()
    }

    fun launchMetricsPolling() {
        if (metricsPollingJob?.isActive == true) {
            android.util.Log.d("DashboardMonitor", "launchMetricsPolling skip - already active")
            return
        }
        metricsPollingJob?.cancel()
        metricsPollingJob = viewModelScope.launch(Dispatchers.IO) {
            // ChÃ¡Â»Â tÃ¡Â»â€˜i Ã„â€˜a 60s cho Ã„â€˜Ã¡ÂºÂ¿n khi URL sÃ¡ÂºÂµn sÃƒÂ ng (trÃƒÂ¡nh fetch khi chÃ†Â°a login)
            var waited = 0
            while (isActive && webDavManager.currentBaseUrl.isEmpty() && waited < 60) {
                delay(1_000L)
                waited++
            }
            // LÃ¡ÂºÂ¥y lÃ¡ÂºÂ§n Ã„â€˜Ã¡ÂºÂ§u ngay sau khi URL sÃ¡ÂºÂµn sÃƒÂ ng
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
                            metricsError = "LÃ¡Â»â€”i HTTP ${resp.code}: $bodyStr"
                        }
                        return@launch
                    }
                    val json = org.json.JSONObject(if (bodyStr.isEmpty()) "{}" else bodyStr)
                    if (json.has("error")) {
                        withContext(Dispatchers.Main) { metricsError = json.optString("error") }
                        return@launch
                    }
                    val timestamps = json.optJSONArray("timestamps") ?: run {
                        withContext(Dispatchers.Main) { metricsError = "Server trÃ¡ÂºÂ£ vÃ¡Â»Â dÃ¡Â»Â¯ liÃ¡Â»â€¡u khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡" }
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
                withContext(Dispatchers.Main) { metricsError = "NhÃ¡ÂºÂ¥n LÃƒÂ m mÃ¡Â»â€ºi Ã„â€˜Ã¡Â»Æ’ thÃ¡Â»Â­ lÃ¡ÂºÂ¡i: ${e.message?.take(80)}" }
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
    // ======== CÃƒÂC HÃƒâ‚¬M XÃ¡Â»Â¬ LÃƒÂ API NÃ¡Â»ËœI BÃ¡Â»Ëœ (LOCAL NAS API) ======
    // =======================================================

    // CÃƒÂ¡c hÃƒÂ m lÃ¡ÂºÂ¯ng nghe System Monitor Ã„â€˜ÃƒÂ£ Ã„â€˜Ã†Â°Ã¡Â»Â£c chuyÃ¡Â»Æ’n ra SystemMonitorHelper.kt

    // ============ AI SMART PHOTOS ============
    var aiCategories by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var aiTotal by mutableStateOf(0)
    var aiLastScan by mutableStateOf("")
    var aiRunning by mutableStateOf(false)
    var aiStatus by mutableStateOf("ChÃ†Â°a cÃƒÂ³ dÃ¡Â»Â¯ liÃ¡Â»â€¡u")
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
                withContext(Dispatchers.Main) { aiStatus = "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i: ${e.message?.take(60)}" }
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
                    val msg = json.optString("message", "Ã„Âang quÃƒÂ©t phÃƒÂ¢n loÃ¡ÂºÂ¡i Ã¡ÂºÂ£nh...")
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
                    val msg = res.optString("message", "HoÃƒÂ n tÃ¡ÂºÂ¥t dÃ¡Â»Ân ThÃƒÂ¹ng rÃƒÂ¡c!")
                    repository.addSystemLog("INFO", "File Ops", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng Ã„â€˜ÃƒÂ£ thÃ¡Â»Â±c hiÃ¡Â»â€¡n XÃƒâ€œA THÃƒâ„¢NG RÃƒÂC: $msg")
                    withContext(Dispatchers.Main) {
                        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                        commonDialogMessage = msg
                        showCommonDialog = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                    commonDialogMessage = "LÃ¡Â»â€”i dÃ¡Â»Ân rÃƒÂ¡c: ${e.message}"
                    showCommonDialog = true
                }
            }
        }
    }

    // ============ GUEST PASS ============

    /**
     * GÃ¡Â»Âi POST /api/guest/create Ã¢â€ â€™ NAS tÃ¡ÂºÂ¡o FTP user tÃ¡ÂºÂ¡m thÃ¡Â»Âi read-only.
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
                            "Ã„ÂÃƒÂ£ cÃ¡ÂºÂ¥p Guest FTP: user='${pass.username}', hÃ¡ÂºÂ¿t hÃ¡ÂºÂ¡n sau $durationMinutes phÃƒÂºt")
                    } else {
                        val errBody = resp.body?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            guestPassError = "NAS tÃ¡Â»Â« chÃ¡Â»â€˜i (${resp.code}): $errBody"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guestPassError = "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i API: ${e.message}"
                }
                repository.addSystemLog("ERROR", "GuestPass", "TÃ¡ÂºÂ¡o Guest Pass lÃ¡Â»â€”i: ${e.message?.take(80)}")
            } finally {
                withContext(Dispatchers.Main) { isGuestPassLoading = false }
            }
        }
    }

    /**
     * GÃ¡Â»Âi POST /api/guest/revoke Ã¢â€ â€™ NAS xÃƒÂ³a FTP user tÃ¡ÂºÂ¡m thÃ¡Â»Âi.
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
     * GÃ¡Â»Â­i link video tÃ¡Â»â€ºi NAS Ã¢â€ â€™ NAS chÃ¡ÂºÂ¡y yt-dlp ngÃ¡ÂºÂ§m Ã¢â€ â€™ lÃ†Â°u vÃƒÂ o Downloads/social/.
     * Ã„ÂiÃ¡Â»â€¡n thoÃ¡ÂºÂ¡i KHÃƒâ€NG tÃ¡Â»â€˜n 1MB dung lÃ†Â°Ã¡Â»Â£ng.
     */
    fun requestSocialDownload(url: String, saveFolder: String = AppConfig.SOCIAL_DOWNLOAD_FOLDER) {
        if (url.isBlank() || isSocialExtracting) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isSocialExtracting = true
                socialExtractStatus = "Ã„Âang gÃ¡Â»Â­i lÃ¡Â»â€¡nh tÃ¡Â»â€ºi NAS..."
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

                // Timeout dÃƒÂ i hÃ†Â¡n vÃƒÂ¬ NAS cÃ¡ÂºÂ§n phÃƒÂ¢n giÃ¡ÂºÂ£i tÃƒÂªn miÃ¡Â»Ân + bÃ¡ÂºÂ¯t link
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
                            isOk -> "Ã¢Å“â€¦ NAS Ã„â€˜ÃƒÂ£ nhÃ¡ÂºÂ­n lÃ¡Â»â€¡nh tÃ¡ÂºÂ£i video!\nVideo sÃ¡ÂºÂ½ Ã„â€˜Ã†Â°Ã¡Â»Â£c tÃ¡ÂºÂ£i ngÃ¡ÂºÂ§m vÃƒÂ  lÃ†Â°u vÃƒÂ o $saveFolder."
                            else -> "Ã¢ÂÅ’ LÃ¡Â»â€”i (${resp.code}): ${msg.take(100)}"
                        }
                        socialExtractStatus = statusText
                        val histItem = SocialDownloadItem(url, platform, isOk)
                        // FIX: capped O(1) prepend thay vi concat O(n). Cap 50 item de tranh growth vo tan.
                        socialDownloadHistory = (listOf(histItem) + socialDownloadHistory).take(50)
                    }
                    repository.addSystemLog(
                        if (isOk) "SUCCESS" else "ERROR",
                        "SocialExtract",
                        "[$platform] $url Ã¢â€ â€™ ${if (isOk) "OK" else "LÃ¡Â»â€”i ${resp.code}"}"
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
                    socialExtractStatus = "Ã¢ÂÅ’ LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i API: ${e.message?.take(80)}"
                    socialDownloadHistory = (listOf(SocialDownloadItem(url, "KhÃƒÂ´ng rÃƒÂµ", false)) + socialDownloadHistory).take(50)
                }
                repository.addSystemLog("ERROR", "SocialExtract", "LÃ¡Â»â€”i gÃ¡Â»Â­i yt-dlp: ${e.message?.take(80)}")
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
                        // Khi job_id khÃƒÂ´ng cÃƒÂ²n trong list, tÃƒÂ¡c vÃ¡Â»Â¥ tÃ¡ÂºÂ£i Ã„â€˜ÃƒÂ£ hoÃƒÂ n thÃƒÂ nh
                        withContext(Dispatchers.Main) {
                            commonDialogMessage = "Ã¢Å“â€¦ TÃ¡ÂºÂ£i video ($platform) hoÃƒÂ n tÃ¡ÂºÂ¥t!\nÃ„ÂÃƒÂ£ tÃ¡ÂºÂ£i xong vÃƒÂ  lÃ†Â°u vÃƒÂ o thÃ†Â° mÃ¡Â»Â¥c $saveFolder"
                            commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
                            showCommonDialog = true
                        }
                        repository.addSystemLog("SUCCESS", "SocialDownload", "TÃ¡ÂºÂ£i video $platform hoÃƒÂ n tÃ¡ÂºÂ¥t. LÃ†Â°u tÃ¡ÂºÂ¡i: $saveFolder ($url)")
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
            else -> "KhÃƒÂ¡c"
        }
    }

    // ============ STREAM PIPING ENGINE ============
    //
    // TriÃ¡ÂºÂ¿t lÃƒÂ½: Ã„ÂiÃ¡Â»â€¡n thoÃ¡ÂºÂ¡i = Ã¡Â»Âng nÃ†Â°Ã¡Â»â€ºc (Pipe).
    //   CDN Server Ã¢â€â‚¬Ã¢â€â‚¬[OkHttp GET stream]Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€“Â¶ Phone RAM buffer Ã¢â€â‚¬Ã¢â€â‚¬[WebDAV PUT]Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€“Â¶ NAS HDD
    //
    // Ã„ÂiÃ¡Â»â€¡n thoÃ¡ÂºÂ¡i KHÃƒâ€NG lÃ†Â°u file. MÃ¡Â»â€”i chunk 128KB Ã„â€˜Ã¡Â»Âc xong bÃ†Â¡m lÃƒÂªn ngay.
    // TÃ¡Â»â€¢ng RAM dÃƒÂ¹ng: ~256KB (2 buffer chunk) bÃ¡ÂºÂ¥t kÃ¡Â»Æ’ video to bao nhiÃƒÂªu.

    /**
     * BÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u Stream Piping tÃ¡Â»Â« [sourceUrl] (link MP4 CDN Ã„â€˜ÃƒÂ£ bÃƒÂ³c) Ã¢â€ â€™ WebDAV NAS.
     *
     * @param sourceUrl  Link video CDN trÃ¡Â»Â±c tiÃ¡ÂºÂ¿p (Ã„â€˜ÃƒÂ£ giÃ¡ÂºÂ£i mÃƒÂ£, cÃƒÂ³ thÃ¡Â»Æ’ stream)
     * @param fileName   TÃƒÂªn file lÃ†Â°u trÃƒÂªn NAS
     */
    fun startStreamPipe(sourceUrl: String, fileName: String) {
        streamPipeJob?.cancel()

        isStreamPiping = true
        streamPipeProgress = 0f
        streamPipeSpeedStr = "Ã„Âang kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i..."
        streamPipeEtaStr = "--"
        streamPipeStatus = "Ã¢ÂÂ³ Ã„Âang truyÃ¡Â»Ân video qua tÃƒÂ¡c vÃ¡Â»Â¥ nÃ¡Â»Ân..."

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡ÂºÂ©y sang StreamPipeWorker (Foreground Service)
        // Ã¢â€ â€™ TÃ¡ÂºÂ¯t App vÃ¡ÂºÂ«n bÃ†Â¡m video liÃƒÂªn tÃ¡Â»Â¥c, Notification hiÃ¡Â»Æ’n thÃ¡Â»â€¹ % tiÃ¡ÂºÂ¿n trÃƒÂ¬nh
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
            streamPipeStatus = "KhÃƒÂ´ng thÃ¡Â»Æ’ chuÃ¡ÂºÂ©n bÃ¡Â»â€¹ tÃ¡ÂºÂ£i video: ${e.message}"
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

        // LÃ¡ÂºÂ¯ng nghe tiÃ¡ÂºÂ¿n trÃƒÂ¬nh tÃ¡Â»Â« Worker
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
                            streamPipeStatus = "Ã°Å¸â€œÂ¡ ${formatFileSize(bytesRead)} / ${if (totalBytes > 0) formatFileSize(totalBytes) else "?"}"
                        }

                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            streamPipeProgress = 1f
                            isStreamPiping = false
                            streamPipeStatus = message.ifEmpty { "Ã¢Å“â€¦ HoÃƒÂ n tÃ¡ÂºÂ¥t!" }
                            // LÃ†Â°u lÃ¡Â»â€¹ch sÃ¡Â»Â­
                            val platform = detectSocialPlatform(sourceUrl)
                            socialDownloadHistory = (listOf(SocialDownloadItem(sourceUrl, platform, true)) + socialDownloadHistory).take(50)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            isStreamPiping = false
                            streamPipeStatus = "Ã¢ÂÅ’ ${message.ifEmpty { "LÃ¡Â»â€”i truyÃ¡Â»Ân video" }}"
                        } else if (workInfo.state == androidx.work.WorkInfo.State.CANCELLED) {
                            isStreamPiping = false
                            streamPipeStatus = "Ã°Å¸â€ºâ€˜ Ã„ÂÃƒÂ£ hÃ¡Â»Â§y bÃ¡Â»Å¸i ngÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng"
                            streamPipeProgress = 0f
                        }
                    }
                }
        }
    }

    /** HÃ¡Â»Â§y Stream Pipe Worker Ã„â€˜ang chÃ¡ÂºÂ¡y giÃ¡Â»Â¯a chÃ¡Â»Â«ng. */
    fun cancelStreamPipe() {
        val context = NasApplication.instance.applicationContext
        _activeStreamPipeWorkId?.let { id ->
            androidx.work.WorkManager.getInstance(context).cancelWorkById(id)
        }
        streamPipeJob?.cancel()
        isStreamPiping = false
        streamPipeStatus = "Ã°Å¸â€ºâ€˜ Ã„ÂÃƒÂ£ hÃ¡Â»Â§y"
        streamPipeProgress = 0f
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬ Format helpers Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    private fun formatFileSize(bytes: Long): String = com.nas.naswebdav.utils.FormatUtils.formatBytes(bytes)

    private fun formatEta(seconds: Long): String = when {
        seconds <= 0  -> "--"
        seconds < 60  -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    // ==========================================
    // TRÃ¡ÂºÂ NG THÃƒÂI GIAO DIÃ¡Â»â€ N SMART SYNC
    // (Ã„ÂÃƒÂ£ xÃƒÂ³a SmartSync theo yÃƒÂªu cÃ¡ÂºÂ§u tÃ¡ÂºÂ­p trung Auto-Backup)

    // ==========================================
    // PHÃƒâ€šN LOÃ¡ÂºÂ I VIDEO CÃ…Â¨ (Legacy Videos)
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

        // KIÃ¡ÂºÂ¾N TRÃƒÅ¡C MÃ¡Â»Å¡I: Ã„ÂÃ¡ÂºÂ©y sang LongRunningApiWorker (Foreground Service)
        val host = try { java.net.URL(webDavManager.currentBaseUrl).host } catch (_: Exception) {
            organizingLegacyRunning = false
            organizingLegacyResult = "LÃ¡Â»â€”i: ChÃ†Â°a kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i NAS"
            return
        }

        val inputData = androidx.work.Data.Builder()
            .putString("taskType", "ORGANIZE")
            .putString("apiUrl", "${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/tools/organize_legacy_videos")
            .putString("jsonBody", "")
            .putString("taskLabel", "Gom video cÃ…Â©")
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
                            organizingLegacyResult = message.ifEmpty { "HoÃƒÂ n tÃ¡ÂºÂ¥t!" }
                            refresh()
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            organizingLegacyRunning = false
                            organizingLegacyResult = message.ifEmpty { "LÃ¡Â»â€”i gom video" }
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
                            // CHÃ¡ÂºÂ¶N BÃ¡Â»Ëœ LÃ¡Â»Å’C RÃƒÂC: NÃ¡ÂºÂ¿u NAS trÃ¡ÂºÂ£ vÃ¡Â»Â tÃ¡Â»â€¡p < 2KB thÃƒÂ¬ 99% Ã„â€˜ÃƒÂ³ lÃƒÂ  Icon Play bÃƒÂ¡o lÃ¡Â»â€”i, ta tÃ¡Â»Â« chÃ¡Â»â€˜i!
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
    // TÃƒÂNH NÃ„â€šNG: CÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t thÃ¡Â»Â§ cÃƒÂ´ng (Manual Sync)
    // BO QUÃƒâ€°T RÃƒÂC khoi flow nay theo yeu cau user Ã¢â‚¬â€ Sync Anh chi nen chay AutoBackup
    // (upload anh moi). Quet trung lap la tac vu nang ca cho phone va NAS, chi chay
    // tu dong theo lich tuan tai 3h sang khi NAS ranh, hoac do user chu dong khoi.
    fun triggerManualBackup(context: android.content.Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        isAutoBackupRunning = true
        autoBackupProgress = 0f
        autoBackupCurrentFile = "Ã„Âang xÃ¡ÂºÂ¿p hÃƒÂ ng Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢..."
        autoBackupSourcePath = "ThiÃ¡ÂºÂ¿t bÃ¡Â»â€¹ mÃƒÂ¡y trÃ¡ÂºÂ¡m"
        autoBackupDestPath = ""
        autoBackupProcessedCount = 0
        autoBackupTotalCount = 0
        autoBackupElapsedTime = 0L

        // KÃƒÂ­ch hoÃ¡ÂºÂ¡t AutoBackup ngay lÃ¡ÂºÂ­p tÃ¡Â»Â©c (upload anh dien thoai len NAS)
        val backupRequest = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.AutoBackupWorker>()
            .addTag("com.nas.naswebdav.AutoBackupWorker")
            .addTag("MANUAL_AUTO_BACKUP")
            .build()
        workManager.enqueueUniqueWork("ManualAutoBackupWork", androidx.work.ExistingWorkPolicy.REPLACE, backupRequest)
        logUserAction("AutoBackup", "chÃ¡ÂºÂ¡y Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢ Ã¡ÂºÂ£nh thÃ¡Â»Â§ cÃƒÂ´ng lÃƒÂªn NAS.")
        // CÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t Toast hoÃ¡ÂºÂ·c TrÃ¡ÂºÂ¡ng thÃƒÂ¡i UI Ã„â€˜Ã¡Â»Æ’ User biÃ¡ÂºÂ¿t
        commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS
        commonDialogMessage = "Ã„ÂÃƒÂ£ ra lÃ¡Â»â€¡nh Ã„â€˜Ã¡Â»â€œng bÃ¡Â»â„¢ Ã¡ÂºÂ£nh lÃƒÂªn NAS!"
        showCommonDialog = true
    }

    fun toggleAutoBackupPause() {
        val newState = !AutoBackupState.isPaused.value
        AutoBackupState.isPaused.value = newState
        autoBackupIsPaused = newState
        logUserAction("AutoBackup", if (newState) "tam dung Auto-Backup." else "TiÃ¡ÂºÂ¿p tÃ¡Â»Â¥c Ã„ÂÃ¡Â»â€œng bÃ¡Â»â„¢ tÃ¡Â»Â± Ã„â€˜Ã¡Â»â„¢ng.")
    }

    // ==========================================
    // THIÃ¡ÂºÂ¾T LÃ¡ÂºÂ¬P HOÃ¡ÂºÂ T Ã„ÂÃ¡Â»ËœNG QUÃ¡ÂºÂ T (FAN CONTROL)
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
                nasConfigBackupMessage = "Ã„Âang tÃ¡ÂºÂ¡o backup..."
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
                        "Ã„ÂÃƒÂ£ tÃ¡ÂºÂ¡o: ${json.optString("filename")} (${json.optString("size_human")})"
                    } else {
                        "LÃ¡Â»â€”i tÃ¡ÂºÂ¡o backup: ${json.optString("error", "HTTP ${resp.code}")}"
                    }
                    repository.addSystemLog(if (resp.isSuccessful) "SUCCESS" else "WARNING", "NasBackup", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: tÃ¡ÂºÂ¡o backup cÃ¡ÂºÂ¥u hÃƒÂ¬nh NAS ${if (resp.isSuccessful) "thÃƒÂ nh cÃƒÂ´ng ${json.optString("filename")}" else "thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) { nasConfigBackupMessage = msg }
                }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "NasBackup", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: tÃ¡ÂºÂ¡o backup cÃ¡ÂºÂ¥u hÃƒÂ¬nh NAS thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "LÃ¡Â»â€”i: ${e.message}" }
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
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "NasBackup", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: xoÃƒÂ¡ backup cÃ¡ÂºÂ¥u hÃƒÂ¬nh '$filename' ${if (resp.isSuccessful) "thÃƒÂ nh cÃƒÂ´ng" else "thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) {
                        nasConfigBackupMessage = if (resp.isSuccessful) "Ã„ÂÃƒÂ£ xoÃƒÂ¡ $filename"
                        else "LÃ¡Â»â€”i xoÃƒÂ¡: ${org.json.JSONObject(text).optString("error","HTTP ${resp.code}")}"
                    }
                }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "NasBackup", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: xoÃƒÂ¡ backup '$filename' thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "LÃ¡Â»â€”i xoÃƒÂ¡: ${e.message}" }
            }
        }
    }

    fun restoreNasConfigBackup(filename: String) {
        if (isRestoringNasConfigBackup) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isRestoringNasConfigBackup = true
                nasConfigBackupMessage = "Ã„Âang khÃƒÂ´i phÃ¡Â»Â¥c..."
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
                        "Ã„ÂÃƒÂ£ khÃƒÂ´i phÃ¡Â»Â¥c ${json.optInt("restored_count")} file. Services restart: " +
                            (json.optJSONArray("services_restarted")?.toString() ?: "(none)")
                    } else {
                        "LÃ¡Â»â€”i khÃƒÂ´i phÃ¡Â»Â¥c: ${json.optString("error", "HTTP ${resp.code}")}"
                    }
                    repository.addSystemLog(if (resp.isSuccessful) "WARNING" else "ERROR", "NasBackup", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: khÃƒÂ´i phÃ¡Â»Â¥c cÃ¡ÂºÂ¥u hÃƒÂ¬nh tÃ¡Â»Â« '$filename' ${if (resp.isSuccessful) "thÃƒÂ nh cÃƒÂ´ng" else "thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i HTTP ${resp.code}"}.")
                    withContext(Dispatchers.Main) { nasConfigBackupMessage = msg }
                }
            } catch (e: Exception) {
                repository.addSystemLog("ERROR", "NasBackup", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: khÃƒÂ´i phÃ¡Â»Â¥c cÃ¡ÂºÂ¥u hÃƒÂ¬nh tÃ¡Â»Â« '$filename' thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "LÃ¡Â»â€”i khÃƒÂ´i phÃ¡Â»Â¥c: ${e.message}" }
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
        if (dashboardRealtimeJob?.isActive == true) {
            android.util.Log.d("DashboardMonitor", "launchDashboardRealtimeScheduler skip - already active")
            return
        }
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
                        "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: ${if (ok) "lÃ†Â°u" else "lÃ†Â°u thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i"} lÃ¡Â»â€¹ch backup (${if (newSchedule.enabled) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"}, ${newSchedule.frequency}, ${newSchedule.hour}h, giÃ¡Â»Â¯ ${newSchedule.retentionCount} bÃ¡ÂºÂ£n)."
                    )
                    withContext(Dispatchers.Main) {
                        backupScheduleMessage = if (ok) "Ã„ÂÃƒÂ£ lÃ†Â°u lÃ¡Â»â€¹ch backup" else "LÃ¡Â»â€”i lÃ†Â°u"
                    }
                }
                fetchBackupSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "BackupSchedule", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: lÃ†Â°u lÃ¡Â»â€¹ch backup thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { backupScheduleMessage = "LÃ¡Â»â€”i: ${e.message}" }
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
                val name = label.ifBlank { path }.ifBlank { "USB KhÃƒÂ´ng tÃƒÂªn" }
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
                if (base.isBlank()) throw IllegalStateException("ChÃ†Â°a cÃƒÂ³ Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° NAS hÃ¡Â»Â£p lÃ¡Â»â€¡")
                val path = if (compact) "/api/usb_import/status?compact=1" else "/api/usb_import/status"
                val req = okhttp3.Request.Builder()
                    .url("$base$path")
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i tÃ¡ÂºÂ£i USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val state = parseUsbImportState(org.json.JSONObject(body))
                    withContext(Dispatchers.Main) {
                        usbImportState = state
                        usbImportMessage = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i: ${e.message}" }
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
                if (base.isBlank()) throw IllegalStateException("ChÃ†Â°a cÃƒÂ³ Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° NAS hÃ¡Â»Â£p lÃ¡Â»â€¡")
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
                        withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i lÃ†Â°u USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val ok = resp.isSuccessful && o.optBoolean("saved", false)
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(
                        if (ok) "INFO" else "WARNING",
                        "USBImport",
                        "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: ${if (ok) "lÃ†Â°u" else "lÃ†Â°u thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i"} cÃ¡ÂºÂ¥u hÃƒÂ¬nh USB Import (${if (settings.enabled) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"}, ${settings.copyMode}, Ã„â€˜ÃƒÂ­ch '${settings.destFolder}', readonly=${settings.mountReadonly})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = if (ok) "Ã„ÂÃƒÂ£ lÃ†Â°u cÃ¡ÂºÂ¥u hÃƒÂ¬nh USB Import" else "LÃ¡Â»â€”i lÃ†Â°u USB Import"
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: lÃ†Â°u cÃ¡ÂºÂ¥u hÃƒÂ¬nh USB Import thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i: ${e.message}" }
            }
        }
    }

    fun startUsbImportNow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isUsbImportLoading = true }
                val base = webDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { currentUrl.toApiBaseUrl() }
                if (base.isBlank()) throw IllegalStateException("ChÃ†Â°a cÃƒÂ³ Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° NAS hÃ¡Â»Â£p lÃ¡Â»â€¡")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/start")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "USBImport", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: yÃƒÂªu cÃ¡ÂºÂ§u copy USB ngay (${o.optString("message", "khÃƒÂ´ng cÃƒÂ³ phÃ¡ÂºÂ£n hÃ¡Â»â€œi")}).")
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = o.optString("message", if (resp.isSuccessful) "Ã„ÂÃƒÂ£ bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u copy USB" else "KhÃƒÂ´ng bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u Ã„â€˜Ã†Â°Ã¡Â»Â£c")
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: yÃƒÂªu cÃ¡ÂºÂ§u copy USB ngay thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i: ${e.message}" }
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
                if (base.isBlank()) throw IllegalStateException("ChÃ†Â°a cÃƒÂ³ Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° NAS hÃ¡Â»Â£p lÃ¡Â»â€¡")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/cancel")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                localApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i huÃ¡Â»Â· USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(if (resp.isSuccessful) "INFO" else "WARNING", "USBImport", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: gÃ¡Â»Â­i lÃ¡Â»â€¡nh hÃ¡Â»Â§y USB Import (${if (resp.isSuccessful) "Ã„â€˜ÃƒÂ£ gÃ¡Â»Â­i" else "thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i"}).")
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = if (resp.isSuccessful) "Ã„ÂÃƒÂ£ gÃ¡Â»Â­i lÃ¡Â»â€¡nh hÃ¡Â»Â§y" else "KhÃƒÂ´ng hÃ¡Â»Â§y Ã„â€˜Ã†Â°Ã¡Â»Â£c"
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: hÃ¡Â»Â§y USB Import thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i: ${e.message}" }
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
                if (base.isBlank()) throw IllegalStateException("ChÃ†Â°a cÃƒÂ³ Ã„â€˜Ã¡Â»â€¹a chÃ¡Â»â€° NAS hÃ¡Â»Â£p lÃ¡Â»â€¡")
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
                        "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: xÃ¡Â»Â­ lÃƒÂ½ file trÃƒÂ¹ng USB Import bÃ¡ÂºÂ±ng $action (${o.optString("message", "khÃƒÂ´ng cÃƒÂ³ phÃ¡ÂºÂ£n hÃ¡Â»â€œi")})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = o.optString("message", if (resp.isSuccessful) "Ã„ÂÃƒÂ£ gÃ¡Â»Â­i lÃ¡Â»â€¡nh xÃ¡Â»Â­ lÃƒÂ½ file trÃƒÂ¹ng" else "KhÃƒÂ´ng xÃ¡Â»Â­ lÃƒÂ½ Ã„â€˜Ã†Â°Ã¡Â»Â£c file trÃƒÂ¹ng")
                    }
                }
                fetchUsbImportStatus()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: xÃ¡Â»Â­ lÃƒÂ½ file trÃƒÂ¹ng USB Import thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "LÃ¡Â»â€”i: ${e.message}" }
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
                        "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: ${if (ok) "lÃ†Â°u" else "lÃ†Â°u thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i"} lÃ¡Â»â€¹ch ngÃ¡Â»Â§ NAS (${if (newSchedule.enabled) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"}, ${newSchedule.mode}, ${newSchedule.startHour}h-${newSchedule.endHour}h, idleOnly=${newSchedule.idleOnly})."
                    )
                    withContext(Dispatchers.Main) {
                        sleepScheduleMessage = if (ok) "Ã„ÂÃƒÂ£ lÃ†Â°u lÃ¡Â»â€¹ch ngÃ¡Â»Â§ NAS" else "LÃ¡Â»â€”i lÃ†Â°u"
                    }
                }
                fetchSleepSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "SleepSchedule", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: lÃ†Â°u lÃ¡Â»â€¹ch ngÃ¡Â»Â§ NAS thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { sleepScheduleMessage = "LÃ¡Â»â€”i: ${e.message}" }
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
                    repository.addSystemLog(if (ok) "INFO" else "WARNING", "SleepSchedule", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: yÃƒÂªu cÃ¡ÂºÂ§u HDD spindown ngay (${if (ok) "thÃƒÂ nh cÃƒÂ´ng" else "thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i"}: $msg).")
                    withContext(Dispatchers.Main) { onDone(ok, msg) }
                }
                fetchSleepSchedule()
            } catch (e: Exception) {
                repository.addSystemLog("WARNING", "SleepSchedule", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: yÃƒÂªu cÃ¡ÂºÂ§u HDD spindown ngay thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
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

// LÃ¡Â»Å¡P PHÃ¡Â»Â¤ TRÃ¡Â»Â¢: BÃ¡Â»â„¢ Ã„â€˜Ã¡ÂºÂ¿m Rate Limiter (2.C)
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
        commonDialogMessage = "Ã„ÂÃƒÂ£ nhÃ¡ÂºÂ­n lÃ¡Â»â€¡nh! Ã„Âang khÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng trÃƒÂ¬nh quÃƒÂ©t rÃƒÂ¡c..."
        showCommonDialog = true

        isScanningDuplicates = true
        viewModelScope.launch {
            repository.addSystemLog("INFO", "DuplicateScan", "HÃ¡Â»â€¡ thÃ¡Â»â€˜ng: NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng Ã„â€˜ÃƒÂ£ phÃƒÂ¢n cÃƒÂ´ng quÃƒÂ©t thÃ¡Â»Â§ cÃƒÂ´ng trÃƒÂ¹ng lÃ¡ÂºÂ·p")
        }
        if (isWorkerRunning) return

        isWorkerRunning = true
        scanDuplicatesCurrentFolderUrl = "Ã„Âang kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i..."
        scanDuplicatesCurrentItemName = "KhÃ¡Â»Å¸i tÃ¡ÂºÂ¡o..."
        scanDuplicatesTotalScanned = 0
        scanDuplicatesFound = 0
        scanDuplicatesStage = "KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng..."

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

                // LuÃ¡Â»â€œng: LÃ¡ÂºÂ¯ng nghe trÃ¡ÂºÂ¡ng thÃƒÂ¡i Worker (ThÃƒÂ nh cÃƒÂ´ng, ThÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i)
                workManager.getWorkInfoByIdFlow(scanWorkRequest.id).collect { workInfo ->
                    if (workInfo != null) {
                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            scanDuplicatesStage = "HoÃƒÂ n tÃ¡ÂºÂ¥t"
                            scanDuplicatesCurrentFolderUrl = "HoÃƒÂ n tÃ¡ÂºÂ¥t!"
                            scanDuplicatesCurrentItemName = "Ã„ÂÃƒÂ£ quÃƒÂ©t xong toÃƒÂ n bÃ¡Â»â„¢."
                            scanDuplicatesPercent = 1f
                            scanDuplicatesCurrentStagePercent = 1f
                            scanDuplicatesStageNumber = 4
                            scanDuplicatesStageDescription = "Ã„ÂÃƒÂ£ quÃƒÂ©t xong toÃƒÂ n bÃ¡Â»â„¢."
                            isWorkerRunning = false
                            loadDuplicateResultsFromCache(context)
                        } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                            scanDuplicatesCurrentFolderUrl = "GÃ¡ÂºÂ·p lÃ¡Â»â€”i hÃ¡Â»â€¡ thÃ¡Â»â€˜ng!"
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
                        errorMessage = "NAS Ã„â€˜ang gÃ¡Â»Ân gÃƒÂ ng. KhÃƒÂ´ng cÃƒÂ³ tÃ¡Â»â€¡p trÃƒÂ¹ng lÃ¡ÂºÂ·p."
                    } else {
                        errorMessage = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "LÃ¡Â»â€”i nÃ¡ÂºÂ¡p danh sÃƒÂ¡ch tÃ¡Â»Â« DB: ${e.message}" }
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

                // 1. KiÃ¡Â»Æ’m tra nÃ¡ÂºÂ¿u file Ã„â€˜ang Ã¡Â»Å¸ trong thÃƒÂ¹ng rÃƒÂ¡c rÃ¡Â»â€œi thÃƒÂ¬ xoÃƒÂ¡ vÃ„Â©nh viÃ¡Â»â€¦n
                if (file.path.contains(TRASH_FOLDER_NAME)) {
                    webDavManager.deleteFile(file.path)
                } else {
                    // 2. NÃ¡ÂºÂ¿u chÃ†Â°a, hÃƒÂ£y Ã„â€˜Ã¡ÂºÂ£m bÃ¡ÂºÂ£o thÃ†Â° mÃ¡Â»Â¥c thÃƒÂ¹ng rÃƒÂ¡c tÃ¡Â»â€œn tÃ¡ÂºÂ¡i vÃƒÂ  di chuyÃ¡Â»Æ’n vÃƒÂ o Ã„â€˜ÃƒÂ³
                    try { webDavManager.createFolder(trashUrl) } catch(e: Exception) { /* Ã„ÂÃƒÂ£ tÃ¡Â»â€œn tÃ¡ÂºÂ¡i */ }

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
                withContext(Dispatchers.Main) { errorMessage = "LÃ¡Â»â€”i xÃ¡Â»Â­ lÃƒÂ½ thÃƒÂ¹ng rÃƒÂ¡c: ${e.message}" }
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
                withContext(Dispatchers.Main) { errorMessage = "LÃ¡Â»â€”i xÃ¡Â»Â­ lÃƒÂ½ hÃƒÂ ng loÃ¡ÂºÂ¡t: ${e.message}" }
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
            .setRequiresDeviceIdle(true) // Ã„ÂIÃ¡Â»â‚¬U KIÃ¡Â»â€ N 1: Ã„ÂiÃ¡Â»â€¡n thoÃ¡ÂºÂ¡i Ã„â€˜ang tÃ¡ÂºÂ¯t mÃƒÂ n hÃƒÂ¬nh, khÃƒÂ´ng sÃ¡Â»Â­ dÃ¡Â»Â¥ng
            .setRequiresCharging(true)   // Ã„ÂIÃ¡Â»â‚¬U KIÃ¡Â»â€ N 2: Ã„Âang cÃ¡ÂºÂ¯m sÃ¡ÂºÂ¡c (Ã„ÂÃ¡ÂºÂ£m bÃ¡ÂºÂ£o an toÃƒÂ n pin)
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) // Ã„ÂIÃ¡Â»â‚¬U KIÃ¡Â»â€ N 3: CÃƒÂ³ Wi-Fi
            .build()

        val inputData = androidx.work.workDataOf(
            "currentUrl" to currentUrl
        )

        // CHU KÃ¡Â»Â² BÃ¡ÂºÂ¢O VÃ¡Â»â€  Ã¡Â»â€ CÃ¡Â»Â¨NG: ChÃ¡Â»â€° lÃƒÂ©n chÃ¡ÂºÂ¡y Stress Test 30 ngÃƒÂ y 1 lÃ¡ÂºÂ§n Ã„â€˜Ã¡Â»Æ’ khÃƒÂ´ng lÃƒÂ m giÃ¡ÂºÂ£m tuÃ¡Â»â€¢i thÃ¡Â»Â Ã¡Â»â€¢ Ã„â€˜Ã„Â©a
        val periodicSpeedTestRequest = androidx.work.PeriodicWorkRequestBuilder<IdleSpeedTestWorker>(
            30, java.util.concurrent.TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "Auto_Idle_Speed_Test",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, // GiÃ¡Â»Â¯ nguyÃƒÂªn lÃ¡Â»â€¹ch trÃƒÂ¬nh cÃ…Â© nÃ¡ÂºÂ¿u Ã„â€˜ÃƒÂ£ tÃ¡Â»â€œn tÃ¡ÂºÂ¡i
            periodicSpeedTestRequest
        )
    }

// PHASE 5.B: LÃƒÂªn lÃ¡Â»â€¹ch cho FingerprintWorker chÃ¡ÂºÂ¡y mÃ¡Â»â€œi vÃƒÂ¢n tay ngÃ¡ÂºÂ§m
fun WebDavViewModel.scheduleFingerprintWorker(context: android.content.Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val constraints = androidx.work.Constraints.Builder()
        .setRequiresDeviceIdle(true) // TÃ¡ÂºÂ¯t mÃƒÂ n hÃƒÂ¬nh
        .setRequiresCharging(true)   // Ã„Âang sÃ¡ÂºÂ¡c
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED) // CÃƒÂ³ mÃ¡ÂºÂ¡ng
        .build()

    // ChÃ¡ÂºÂ¡y mÃ¡Â»â€”i 24 tiÃ¡ÂºÂ¿ng Ã„â€˜Ã¡Â»Æ’ tÃ¡ÂºÂ¡o vÃƒÂ¢n tay cho cÃƒÂ¡c file Ã¡ÂºÂ£nh/video vÃ¡Â»Â«a upload
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
 * TÃ¡ÂºÂ£i video tÃ¡Â»Â« NAS vÃ¡Â»Â cache rÃ¡Â»â€œi mÃ¡Â»Å¸ bÃ¡ÂºÂ±ng trÃƒÂ¬nh phÃƒÂ¡t cÃ¡Â»Â¥c bÃ¡Â»â„¢.
 * Ã„ÂÃ¡ÂºÂ£m bÃ¡ÂºÂ£o mÃ¡Â»Âi Ã„â€˜Ã¡Â»â€¹nh dÃ¡ÂºÂ¡ng (.mpg, .avi, .wmv, .flv, ...) Ã„â€˜Ã¡Â»Âu phÃƒÂ¡t Ã„â€˜Ã†Â°Ã¡Â»Â£c
 * vÃƒÂ¬ file cÃ¡Â»Â¥c bÃ¡Â»â„¢ khÃƒÂ´ng cÃƒÂ³ vÃ¡ÂºÂ¥n Ã„â€˜Ã¡Â»Â auth hay streaming.
 */
object VideoDownloadHelper {

    private const val TAG = "VideoDownloadHelper"
    private const val VIDEO_CACHE_DIR = "video_temp"

    /**
     * TÃ¡ÂºÂ£i video vÃ¡Â»Â cache vÃƒÂ  mÃ¡Â»Å¸ bÃ¡ÂºÂ±ng trÃƒÂ¬nh phÃƒÂ¡t bÃƒÂªn ngoÃƒÂ i.
     * HiÃ¡Â»Æ’n thÃ¡Â»â€¹ progress qua callback.
     *
     * @param onProgress Callback (bytesDownloaded, totalBytes) Ã„â€˜Ã¡Â»Æ’ cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t UI
     * @param onReady Callback khi file Ã„â€˜ÃƒÂ£ sÃ¡ÂºÂµn sÃƒÂ ng phÃƒÂ¡t
     * @param onError Callback khi cÃƒÂ³ lÃ¡Â»â€”i
     */
    // FIX A3a: NhÃ¡ÂºÂ­n CoroutineScope tÃ¡Â»Â« caller thay vÃƒÂ¬ tÃ¡Â»Â± tÃ¡ÂºÂ¡o CoroutineScope(IO) riÃƒÂªng.
    // Scope rÃ¡Â»Âi rÃ¡ÂºÂ¡c sÃ¡ÂºÂ½ khÃƒÂ´ng bao giÃ¡Â»Â bÃ¡Â»â€¹ cancel khi ViewModel bÃ¡Â»â€¹ destroy Ã¢â€ â€™ memory leak.
    // Caller (thÃ†Â°Ã¡Â»Âng lÃƒÂ  ViewModel) phÃ¡ÂºÂ£i truyÃ¡Â»Ân viewModelScope Ã„â€˜Ã¡Â»Æ’ lifecycle Ã„â€˜Ã†Â°Ã¡Â»Â£c quÃ¡ÂºÂ£n lÃƒÂ½ Ã„â€˜ÃƒÂºng.
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
                // 1. TÃ¡ÂºÂ¡o thÃ†Â° mÃ¡Â»Â¥c cache cho video
                val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
                if (!cacheDir.exists()) cacheDir.mkdirs()

                // XÃƒÂ³a file cÃ…Â© Ã„â€˜Ã¡Â»Æ’ giÃ¡ÂºÂ£i phÃƒÂ³ng bÃ¡Â»â„¢ nhÃ¡Â»â€º (chÃ¡Â»â€° giÃ¡Â»Â¯ file mÃ¡Â»â€ºi nhÃ¡ÂºÂ¥t)
                cacheDir.listFiles()?.forEach { it.delete() }

                // 2. LÃ¡ÂºÂ¥y tÃƒÂªn file tÃ¡Â»Â« URL
                val fileName = url.substringAfterLast('/').substringBefore('?')
                    .let { java.net.URLDecoder.decode(it, "UTF-8") }
                    .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val targetFile = File(cacheDir, fileName)

                Log.i(TAG, "Ã„Âang tÃ¡ÂºÂ£i: $url Ã¢â€ â€™ ${targetFile.absolutePath}")

                // 3. TÃ¡ÂºÂ£i file tÃ¡Â»Â« NAS vÃ¡Â»â€ºi xÃƒÂ¡c thÃ¡Â»Â±c
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .build()

                // FIX A3b: BÃ¡Â»Âc response trong use {} Ã„â€˜Ã¡Â»Æ’ Ã„â€˜Ã¡ÂºÂ£m bÃ¡ÂºÂ£o body luÃƒÂ´n Ã„â€˜Ã†Â°Ã¡Â»Â£c Ã„â€˜ÃƒÂ³ng,
                // kÃ¡Â»Æ’ cÃ¡ÂºÂ£ khi exception xÃ¡ÂºÂ£y ra giÃ¡Â»Â¯a chÃ¡Â»Â«ng (trÃƒÂ¡nh connection pool exhaustion).
                NasApplication.instance.videoStreamingClient
                    .newBuilder()
                    .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS) // 10 phÃƒÂºt cho file lÃ¡Â»â€ºn
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            withContext(Dispatchers.Main) {
                                onError("NAS trÃ¡ÂºÂ£ vÃ¡Â»Â lÃ¡Â»â€”i: ${response.code}")
                            }
                            return@use
                        }

                        val totalBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        var downloadedBytes = 0L

                        // 4. Ghi file ra cache vÃ¡Â»â€ºi progress
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

                        Log.i(TAG, "TÃ¡ÂºÂ£i xuÃ¡Â»â€˜ng hoÃƒÂ n tÃ¡ÂºÂ¥t: ${downloadedBytes / 1024}KB")

                        // 5. MÃ¡Â»Å¸ file cÃ¡Â»Â¥c bÃ¡Â»â„¢ bÃ¡ÂºÂ±ng trÃƒÂ¬nh phÃƒÂ¡t video
                        withContext(Dispatchers.Main) {
                            onReady()
                            openLocalFile(context, targetFile)
                        }
                    }

            } catch (e: CancellationException) {
                Log.d(TAG, "Ã„ÂÃƒÂ£ hÃ¡Â»Â§y tÃ¡ÂºÂ£i xuÃ¡Â»â€˜ng")
            } catch (e: Exception) {
                Log.e(TAG, "TÃ¡ÂºÂ£i xuÃ¡Â»â€˜ng thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("LÃ¡Â»â€”i tÃ¡ÂºÂ£i video: ${e.message}")
                }
            }
        }
    }


    /** MÃ¡Â»Å¸ file video cÃ¡Â»Â¥c bÃ¡Â»â„¢ bÃ¡ÂºÂ±ng trÃƒÂ¬nh phÃƒÂ¡t cÃƒÂ i trÃƒÂªn mÃƒÂ¡y */
    private fun openLocalFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // XÃƒÂ¡c Ã„â€˜Ã¡Â»â€¹nh MIME type phÃƒÂ¹ hÃ¡Â»Â£p
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

            val chooser = Intent.createChooser(intent, "ChÃ¡Â»Ân trÃƒÂ¬nh phÃƒÂ¡t video")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open file: ${e.message}")
        }
    }
}

// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
// SystemMonitorHelper Ã¢â‚¬â€ Extension functions cho WebDavViewModel
// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

fun WebDavViewModel.listenToLocalNasApi(forceRestart: Boolean = false) {
    if (!forceRestart && statusJob?.isActive == true) {
        android.util.Log.d("DashboardMonitor", "listenToLocalNasApi skip - already active")
        return
    }
    android.util.Log.d("DashboardMonitor", "listenToLocalNasApi start forceRestart=$forceRestart")
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
                            val tempRaw = jsonObject.optString("temperature", "--Ã‚Â°C")
                            val temp = if (tempRaw != "--Ã‚Â°C" && !tempRaw.contains("Ã‚Â°")) "${tempRaw}Ã‚Â°C" else tempRaw
                            val cpu = jsonObject.optString("cpu", "--%")
                            val cpuTemp = jsonObject.optString("cpu_temp", "--Ã‚Â°C")
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
                            jsonObject.optJSONArray("torrents")?.let { arr -> for (i in 0 until arr.length()) { val tObj = arr.getJSONObject(i); torrentList.add(TorrentInfo(tObj.optString("name", "Ã„Âang tÃ¡ÂºÂ£i..."), tObj.optDouble("progress", 0.0).toFloat(), tObj.optString("speed", "0 B/s"), tObj.optString("hash", ""), tObj.optString("state", ""), tObj.optString("save_path", ""))) } }
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
                                // CHÃ¡Â»ÂNG BOUNCE (Debounce): BÃ¡Â»Â qua cÃ¡ÂºÂ­p nhÃ¡ÂºÂ­t trÃ¡ÂºÂ¡ng thÃƒÂ¡i quÃ¡ÂºÂ¡t tÃ¡Â»Â« API nÃ¡ÂºÂ¿u Ã„â€˜ang gÃ¡Â»Â­i lÃ¡Â»â€¡nh HOÃ¡ÂºÂ¶C vÃ¡Â»Â«a set thÃ¡Â»Â§ cÃƒÂ´ng < 15s (Ã„â€˜Ã¡Â»Æ’ chÃ¡Â»Â NAS xÃ¡Â»Â­ lÃƒÂ½ service tÃ¡Â»â€˜n thÃ¡Â»Âi gian)
                                if (isFanModeUpdating || System.currentTimeMillis() - lastFanModeSettingTime < 15000L) {
                                    systemStatus = newStatus.copy(
                                        fanMode = systemStatus.fanMode,
                                        fanStatus = systemStatus.fanStatus, // BÃ¡ÂºÂ£o toÃƒÂ n chuÃ¡Â»â€”i trÃ¡ÂºÂ¡ng thÃƒÂ¡i tÃ¡Â»â€˜c Ã„â€˜Ã¡Â»â„¢ quÃ¡ÂºÂ¡t Ã¡ÂºÂ£o
                                        fanOnTemp = systemStatus.fanOnTemp,
                                        fanOffTemp = systemStatus.fanOffTemp
                                    )
                                } else {
                                    systemStatus = newStatus
                                }
                                
                                if (hddVal > 0f || cpuVal > 0f) {
                                    // FIX: temperatureHistory boc trong mutableStateOf Ã¢â‚¬â€ in-place
                                    // addLast khong trigger recompose. Phai reassign de Compose biet.
                                    temperatureHistory.add(Pair(cpuVal, hddVal))

                                    while (temperatureHistory.size > 40) temperatureHistory.removeAt(0)

                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) { apiFailureCount += 1; systemStatus = systemStatus.copy(status = "API tÃ¡Â»Â« chÃ¡Â»â€˜i") }
                            currentDelayMs = (currentDelayMs * 1.5).toLong().coerceAtMost(60_000L)
                        }
                    }
                }
            } catch (e: Exception) {
                val isTimeout = e is java.net.SocketTimeoutException || e is java.net.ConnectException
                val msg = if (isTimeout) "MÃ¡ÂºÂ¥t kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i API (${e.javaClass.simpleName})" else "API: ${e.javaClass.simpleName}"
                android.util.Log.w("NAS_API", "Theo dÃƒÂµi ping thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message}")
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
                    withContext(Dispatchers.Main) { weeklyReportText = "TuÃ¡ÂºÂ§n qua: ChÃ¡ÂºÂ·n ${json.optInt("banned_count", 0)} IP tÃ¡ÂºÂ¥n cÃƒÂ´ng. DÃ¡Â»Ân rÃƒÂ¡c giÃ¡ÂºÂ£i phÃƒÂ³ng ${json.optString("freed_space", "0 MB")}." }
                }
            }
        } catch (_: Exception) { withContext(Dispatchers.Main) { weeklyReportText = "ChÃ†Â°a cÃƒÂ³ bÃƒÂ¡o cÃƒÂ¡o tuÃ¡ÂºÂ§n nÃƒÂ y." } }
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
            android.util.Log.e("NasAPI", "KhÃƒÂ´ng tÃ¡ÂºÂ£i Ã„â€˜Ã†Â°Ã¡Â»Â£c nhÃ¡ÂºÂ­t kÃƒÂ½ tÃ¡Â»Â« NAS: ${e.message}")
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
                        android.util.Log.e("NasAPI", "LÃ¡Â»â€”i xÃƒÂ³a server logs: ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NasAPI", "KhÃƒÂ´ng xÃƒÂ³a Ã„â€˜Ã†Â°Ã¡Â»Â£c nhÃ¡ÂºÂ­t kÃƒÂ½ trÃƒÂªn NAS: ${e.message}")
        }
        withContext(Dispatchers.Main) { 
            systemLogsList = emptyList()
            commonDialogMessage = "Ã„ÂÃƒÂ£ dÃ¡Â»Ân sÃ¡ÂºÂ¡ch nhÃ¡ÂºÂ­t kÃƒÂ½ hÃ¡Â»â€¡ thÃ¡Â»â€˜ng."
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
                        smartInfo = SmartInfo(status = json.optString("status", "KhÃƒÂ´ng rÃƒÂµ"), temperature = run { val rawTemp = json.optString("temperature", "--"); if (rawTemp != "--" && !rawTemp.contains("Ã‚Â°")) "${rawTemp}Ã‚Â°C" else rawTemp }, rawLog = json.optString("raw_log", ""))
                        lastSmartRefreshAt = System.currentTimeMillis()
                    }
                } else withContext(Dispatchers.Main) { smartInfo = SmartInfo("LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i", "--", "MÃƒÂ£ lÃ¡Â»â€”i: ${response.code}") }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { smartInfo = SmartInfo("KhÃƒÂ´ng thÃ¡Â»Æ’ kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i", "--", e.message ?: "") } }
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
    if (isTestingSpeed) return; isTestingSpeed = true; speedTestResult = SpeedTestResult("Ã„Âang Ã„â€˜o...", "Ã„Âang Ã„â€˜o...")
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/speedtest").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            val speedTestClient = localApiClient.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            speedTestClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) { val json = org.json.JSONObject(response.body?.string() ?: ""); withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult(json.optString("write_speed", "LÃ¡Â»â€”i"), json.optString("read_speed", "LÃ¡Â»â€”i")) } }
                else withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult("ThÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i", "ThÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i") }
            }
        } catch (e: Exception) { withContext(Dispatchers.Main) { speedTestResult = SpeedTestResult("LÃ¡Â»â€”i", "LÃ¡Â»â€”i") }; repository.addSystemLog("ERROR", "SpeedTest", "Ã„Âo tÃ¡Â»â€˜c Ã„â€˜Ã¡Â»â„¢ thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(80)}") }
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
            "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng Ã„â€˜ÃƒÂ£ gÃ¡Â»Â­i Wake-on-LAN Ã„â€˜ÃƒÂ¡nh thÃ¡Â»Â©c NAS tÃ¡ÂºÂ¡i MAC ${macStr.trim()}: ${result.message}"
        } else {
            "GÃ¡Â»Â­i Wake-on-LAN tÃ¡Â»â€ºi MAC ${macStr.trim()} thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${result.message}"
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
        android.util.Log.w("WOL", "KhÃƒÂ´ng thÃ¡Â»Æ’ lÃ¡ÂºÂ¥y MAC Wake-on-LAN trÃ†Â°Ã¡Â»â€ºc khi tÃ¡ÂºÂ¯t nguÃ¡Â»â€œn: ${e.message}")
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
            val cmdName = when { endpoint.contains("reboot") -> "KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng lÃ¡ÂºÂ¡i"; isSleepCommand -> "NgÃ¡Â»Â§"; else -> endpoint }
            val savedMac = if (isSleepCommand) refreshWakeOnLanMacFromNas() else null
            if (savedMac != null) {
                repository.addSystemLog("INFO", "Power", "Ã„ÂÃƒÂ£ lÃ†Â°u MAC Wake-on-LAN $savedMac trÃ†Â°Ã¡Â»â€ºc khi Ã„â€˜Ã†Â°a NAS vÃƒÂ o chÃ¡ÂºÂ¿ Ã„â€˜Ã¡Â»â„¢ ngÃ¡Â»Â§")
            }
            repository.addSystemLog("WARNING", "Power", "Ã„ÂÃƒÂ£ gÃ¡Â»Â­i lÃ¡Â»â€¡nh $cmdName NAS tÃ¡ÂºÂ¡i $host")
            val request = okhttp3.Request.Builder().url("${webDavManager.currentBaseUrl.toApiBaseUrl()}/api/$endpoint").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            localApiClient.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                val suffix = if (isSleepCommand && savedMac != null) " MAC WOL: $savedMac." else ""
                withContext(Dispatchers.Main) {
                    onResult?.invoke(ok, if (ok) "Ã„ÂÃƒÂ£ gÃ¡Â»Â­i lÃ¡Â»â€¡nh $cmdName NAS.$suffix" else "NAS tÃ¡Â»Â« chÃ¡Â»â€˜i lÃ¡Â»â€¡nh $cmdName (HTTP ${response.code}).")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult?.invoke(false, "KhÃƒÂ´ng gÃ¡Â»Â­i Ã„â€˜Ã†Â°Ã¡Â»Â£c lÃ¡Â»â€¡nh nguÃ¡Â»â€œn: ${e.message ?: "lÃ¡Â»â€”i mÃ¡ÂºÂ¡ng"}")
            }
        }
    }
}

/**
 * Gui lenh power (reboot/shutdown) tu man LoginScreen Ã¢â‚¬â€ KHONG yeu cau da connect.
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
                withContext(Dispatchers.Main) { onResult(false, "Vui lÃƒÂ²ng nhÃ¡ÂºÂ­p IP cÃ¡Â»Â§a NAS") }
                return@launch
            }
            // Tu IP -> http://<ip>:<API_PORT>
            val host = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                java.net.URL(trimmed).host
            } else trimmed.substringBefore(":")
            val apiUrl = "http://$host:${AppConfig.API_PORT}/api/$endpoint"
            val cmdName = when {
                endpoint.contains("reboot") -> "KhÃ¡Â»Å¸i Ã„â€˜Ã¡Â»â„¢ng lÃ¡ÂºÂ¡i"
                endpoint.contains("suspend") -> "NgÃ¡Â»Â§"
                endpoint.contains("shutdown") -> "TÃ¡ÂºÂ¯t nguÃ¡Â»â€œn"
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
            // Ã¢â‚¬â€ luc nay con rong vi chua connect)
            val client = NasApplication.instance.fastApiClient.newBuilder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val ok = resp.isSuccessful
                val code = resp.code
                withContext(Dispatchers.Main) {
                    if (ok) onResult(true, "Ã„ÂÃƒÂ£ gÃ¡Â»Â­i lÃ¡Â»â€¡nh $cmdName NAS!")
                    else onResult(false, "NAS tÃ¡Â»Â« chÃ¡Â»â€˜i (HTTP $code) Ã¢â‚¬â€ kiÃ¡Â»Æ’m tra IP/tÃƒÂ i khoÃ¡ÂºÂ£n/mÃ¡ÂºÂ­t khÃ¡ÂºÂ©u")
                }
                try { repository.addSystemLog("WARNING", "Power", "LoginScreen: gÃ¡Â»Â­i $cmdName NAS tÃ¡ÂºÂ¡i $host (HTTP $code)") } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(false, "KhÃƒÂ´ng kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i Ã„â€˜Ã†Â°Ã¡Â»Â£c NAS: ${e.message?.take(80) ?: "lÃ¡Â»â€”i mÃ¡ÂºÂ¡ng"}")
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
                repository.addSystemLog(if (response.isSuccessful) "INFO" else "WARNING", "Docker", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: ${if (turnOn) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"} Docker/qBittorrent ${if (response.isSuccessful) "thÃƒÂ nh cÃƒÂ´ng" else "thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i HTTP ${response.code}"}.")
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    val running = json.optBoolean("running", json.optBoolean("effective_running", false))
                    withContext(Dispatchers.Main) { isDockerRunning = running }
                }
            }
            checkDockerStatus()
        } catch (e: Exception) {
            repository.addSystemLog("WARNING", "Docker", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng: ${if (turnOn) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"} Docker/qBittorrent thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i: ${e.message?.take(120)}")
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
                    commonDialogMessage = if (ok) "Ã„ÂÃƒÂ£ ${if (enable) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"} dÃ¡Â»â€¹ch vÃ¡Â»Â¥ ${serviceName.uppercase()}." else "KhÃƒÂ´ng thÃ¡Â»Æ’ ${if (enable) "bÃ¡ÂºÂ­t" else "tÃ¡ÂºÂ¯t"} dÃ¡Â»â€¹ch vÃ¡Â»Â¥ ${serviceName.uppercase()} (HTTP ${response.code})."
                    showCommonDialog = true
                }
            }
            fetchOmvOverview()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                commonDialogMessage = "LÃ¡Â»â€”i Ã„â€˜iÃ¡Â»Âu khiÃ¡Â»Æ’n dÃ¡Â»â€¹ch vÃ¡Â»Â¥: ${e.message?.take(120)}"
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
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS; commonDialogMessage = "Ã„ÂÃƒÂ£ cÃ¡ÂºÂ¥p quyÃ¡Â»Ân truy cÃ¡ÂºÂ­p cho IP: $ip"; showCommonDialog = true }
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
            withContext(Dispatchers.Main) { commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.WARNING; commonDialogMessage = "Ã„ÂÃƒÂ£ chÃ¡ÂºÂ·n quyÃ¡Â»Ân truy cÃ¡ÂºÂ­p cÃ¡Â»Â§a IP: $ip"; showCommonDialog = true }
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

// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
// LAN WHITELIST API Ã¢â‚¬â€ TÃƒÂ¡ch logic mÃ¡ÂºÂ¡ng ra khÃ¡Â»Âi @Composable
// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

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
                    withContext(Dispatchers.Main) { lanWhitelistError = "LÃ¡Â»â€”i: ${response.code}" }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistError = "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i: ${e.message}" }
        } finally {
            withContext(Dispatchers.Main) { lanWhitelistLoading = false }
        }
    }
}

fun WebDavViewModel.addLanWhitelistEntry(entry: String) {
    viewModelScope.launch(Dispatchers.IO) {
        var isSuccessLocally = false
        try {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Ã„Âang thÃƒÂªm..." }
            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            val isSubnet = entry.contains("/")
            // FIX B3: DÃƒÂ¹ng JSONObject.put() thay vÃƒÂ¬ string interpolation Ã„â€˜Ã¡Â»Æ’ trÃƒÂ¡nh JSON injection
            // nÃ¡ÂºÂ¿u entry chÃ¡Â»Â©a kÃƒÂ½ tÃ¡Â»Â± Ã„â€˜Ã¡ÂºÂ·c biÃ¡Â»â€¡t nhÃ†Â° dÃ¡ÂºÂ¥u ngoÃ¡ÂºÂ·c kÃƒÂ©p hoÃ¡ÂºÂ·c backslash.
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
                        lanWhitelistStatus = "Ã¢Å“â€¦ Ã„ÂÃƒÂ£ thÃƒÂªm $entry"
                        repository.addSystemLog("INFO", "Network", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng Ã„â€˜ÃƒÂ£ THÃƒÅ M IP/Subnet '$entry' vÃƒÂ o danh sÃƒÂ¡ch LAN Whitelist.")
                    } else {
                        lanWhitelistStatus = "Ã¢ÂÅ’ LÃ¡Â»â€”i: ${response.code}"
                        repository.addSystemLog("WARNING", "Network", "CÃ¡Â»â€˜ gÃ¡ÂºÂ¯ng thÃƒÂªm IP/Subnet '$entry' vÃƒÂ o LAN Whitelist thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i.")
                    }
                }
            }
            if (isSuccessLocally) loadLanWhitelist()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Ã¢ÂÅ’ ${e.message}" }
        }
    }
}

fun WebDavViewModel.removeLanWhitelistEntry(entry: String, isSubnet: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        var isSuccessLocally = false
        try {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Ã„Âang xÃƒÂ³a..." }
            val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
            // FIX B3: DÃƒÂ¹ng JSONObject.put() thay vÃƒÂ¬ string interpolation.
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
                        lanWhitelistStatus = "Ã¢Å“â€¦ Ã„ÂÃƒÂ£ xÃƒÂ³a $entry"
                        repository.addSystemLog("INFO", "Network", "NgÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng Ã„â€˜ÃƒÂ£ XÃƒâ€œA IP/Subnet '$entry' khÃ¡Â»Âi danh sÃƒÂ¡ch LAN Whitelist.")
                    } else {
                        lanWhitelistStatus = "Ã¢ÂÅ’ LÃ¡Â»â€”i: ${response.code}"
                        repository.addSystemLog("WARNING", "Network", "CÃ¡Â»â€˜ gÃ¡ÂºÂ¯ng xÃƒÂ³a IP/Subnet '$entry' khÃ¡Â»Âi LAN Whitelist thÃ¡ÂºÂ¥t bÃ¡ÂºÂ¡i.")
                    }
                }
            }
            if (isSuccessLocally) loadLanWhitelist()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lanWhitelistStatus = "Ã¢ÂÅ’ ${e.message}" }
        }
    }
}

// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
// SMART ORGANIZER API Ã¢â‚¬â€ TÃƒÂ¡ch logic mÃ¡ÂºÂ¡ng ra khÃ¡Â»Âi @Composable
// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

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
                            organizerError = "LÃ¡Â»â€”i phÃƒÂ¢n tÃƒÂ­ch: ${e.message}"
                        }
                    } else {
                        organizerError = "LÃ¡Â»â€”i NAS: ${response.code}"
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { organizerError = "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i: ${e.message}" }
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

// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
// PerformanceMonitor Ã¢â‚¬â€ GiÃƒÂ¡m sÃƒÂ¡t hiÃ¡Â»â€¡u nÃ„Æ’ng Ã¡Â»Â©ng dÃ¡Â»Â¥ng
// Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

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
                                speed = jobObj.optString("avg_speed", "Ã¢â‚¬â€"),
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
                        onResult(true, if (effectiveEnabled) "SMB Ã„â€˜ang bÃ¡ÂºÂ­t thÃ¡Â»Â±c tÃ¡ÂºÂ¿" else "SMB Ã„â€˜ang tÃ¡ÂºÂ¯t thÃ¡Â»Â±c tÃ¡ÂºÂ¿")
                    } else {
                        onResult(false, "LÃ¡Â»â€”i: $responseBody")
                    }
                }
                fetchSmbStatus()
                fetchOmvOverview()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                isLoadingSmb = false
                onResult(false, "LÃ¡Â»â€”i kÃ¡ÂºÂ¿t nÃ¡Â»â€˜i: ${e.message}")
            }
        }
    }
}
