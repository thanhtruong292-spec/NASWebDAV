package com.nas.naswebdav.monitor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.DailyReportData
import com.nas.naswebdav.MetricsSnapshot
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.NasSystemStatus
import com.nas.naswebdav.SystemProcess
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.WebDavViewModel
import com.nas.naswebdav.toApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * SystemMonitorViewModel — Phase 4 của VM Split.
 *
 * Quản lý: System status, Metrics history, Daily report, Disk health, SMART,
 *          Fan mode, Network ping, WebDavViewModel.NasConfigBackups, WebDavViewModel.NasInsights, System processes.
 *
 * Phase 4 skeleton: state declarations + placeholder methods.
 * Function bodies sẽ được move từ facade trong Phase 4b.
 */
class SystemMonitorViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ SYSTEM STATUS ═══

    var systemStatus by androidx.compose.runtime.mutableStateOf(NasSystemStatus())
        internal set
    var temperatureHistory = androidx.compose.runtime.mutableStateListOf<Pair<Float, Float>>()
        internal set

    // ═══ METRICS ═══

    var metricsHistory = androidx.compose.runtime.mutableStateListOf<MetricsSnapshot>()
        internal set
    var metricsHours by androidx.compose.runtime.mutableIntStateOf(1)
        internal set
    var metricsChartTab by androidx.compose.runtime.mutableIntStateOf(0)
        internal set
    var isLoadingMetrics by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var metricsError by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set
    var dailyReport by androidx.compose.runtime.mutableStateOf<DailyReportData?>(null)
        internal set
    var isDailyReportLoading by androidx.compose.runtime.mutableStateOf(false)
        internal set

    // ═══ SYSTEM PROCESSES ═══

    var systemProcesses by androidx.compose.runtime.mutableStateOf<List<SystemProcess>>(emptyList())
        internal set
    var isLoadingProcesses by androidx.compose.runtime.mutableStateOf(false)
        internal set

    // ═══ NETWORK HEALTH ═══

    var networkPingMs by androidx.compose.runtime.mutableStateOf<Long?>(null)
        internal set
    var lastStatusRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var lastMetricsRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var lastStorageRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var lastSmartRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var lastLogsRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var apiLatencyMs by androidx.compose.runtime.mutableStateOf<Long?>(null)
        internal set
    var apiFailureCount by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    // ═══ DISK HEALTH ═══

    var diskHealthCurrent by androidx.compose.runtime.mutableStateOf<WebDavViewModel.DiskHealthSample?>(null)
        internal set
    var diskHealthHistory by androidx.compose.runtime.mutableStateOf<List<WebDavViewModel.DiskHealthSample>>(emptyList())
        internal set
    var isFetchingDiskHealth by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var lastDiskHealthRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set

    // ═══ NAS CONFIG BACKUPS ═══

    var nasConfigBackups by androidx.compose.runtime.mutableStateOf<List<WebDavViewModel.NasConfigBackup>>(emptyList())
        internal set
    var isCreatingNasConfigBackup by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isRestoringNasConfigBackup by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var nasConfigBackupMessage by androidx.compose.runtime.mutableStateOf("")
        internal set

    // ═══ NAS INSIGHTS ═══

    var nasInsights by androidx.compose.runtime.mutableStateOf(WebDavViewModel.NasInsights())
        internal set
    var isFetchingNasInsights by androidx.compose.runtime.mutableStateOf(false)
        internal set

    // ═══ POLLING JOBS — cancel previous on restart ═══

    private var statusPollingJob: Job? = null
    private var metricsPollingJob: Job? = null

    // ═══ WIRED METHODS — Phase 4b ═══

    fun startDashboardMonitoring(resetStatusPoll: Boolean = false) {
        statusPollingJob?.cancel()
        statusPollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchStatusNow()
                delay(15_000L) // poll every 15s
            }
        }
    }

    private suspend fun fetchStatusNow() {
        try {
            val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
            val req = okhttp3.Request.Builder().url("$apiBase/api/status/realtime").get().build()
            NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        systemStatus = NasSystemStatus(
                            temp = json.optString("temp", "--°C"),
                            cpu = json.optString("cpu", "--%"),
                            cpuTemp = json.optString("cpu_temp", "--°C"),
                            ram = json.optString("mem", "--"),
                            disk = json.optString("disk", "--%"),
                            fanMode = json.optString("fan_mode", "auto"),
                            fanStatus = json.optString("fan_status", "--"),
                            uptime = json.optString("uptime", "--"),
                            status = "Đã kết nối",
                            ramPercent = json.optString("mem_percent", "0")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SysMonitor", "fetchStatusNow: ${e.message}")
        }
    }

    fun launchMetricsPolling() {
        metricsPollingJob?.cancel()
        metricsPollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchRealtimeMetricPoint()
                delay(5_000L)
            }
        }
    }

    fun fetchMetricsHistory(hours: Int = 1) {
        if (isLoadingMetrics) return
        isLoadingMetrics = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/metrics/history?hours=$hours").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "[]"
                    val arr = org.json.JSONArray(body)
                    withContext(Dispatchers.Main) {
                        metricsHours = hours
                        metricsHistory.clear()
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.forEach { json ->
                            metricsHistory.add(MetricsSnapshot(
                                timestamp = json.optString("timestamp", System.currentTimeMillis().toString()),
                                cpuTemp = json.optDouble("cpu_temp", 0.0).toFloat(),
                                cpuPercent = json.optDouble("cpu_load", 0.0).toFloat(),
                                ramPercent = json.optDouble("mem_used_pct", 0.0).toFloat(),
                                netRxKbps = json.optDouble("net_in_kbps", 0.0).toFloat(),
                                netTxKbps = json.optDouble("net_out_kbps", 0.0).toFloat()
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { metricsError = e.message }
            } finally {
                withContext(Dispatchers.Main) { isLoadingMetrics = false }
            }
        }
    }

    fun fetchRealtimeMetricPoint() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/metrics/realtime").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        val point = MetricsSnapshot(
                            timestamp = System.currentTimeMillis().toString(),
                            cpuTemp = json.optDouble("cpu_temp", 0.0).toFloat(),
                            cpuPercent = json.optDouble("cpu_load", 0.0).toFloat(),
                            ramPercent = json.optDouble("mem_used_pct", 0.0).toFloat(),
                            netRxKbps = json.optDouble("net_in_kbps", 0.0).toFloat(),
                            netTxKbps = json.optDouble("net_out_kbps", 0.0).toFloat()
                        )
                        metricsHistory.add(point)
                        if (metricsHistory.size > 1000) {
                            metricsHistory.removeRange(0, 200)
                        }
                    }
                }
            } catch (_: Exception) { /* silent for realtime */ }
        }
    }

    fun fetchDailyReport(date: String = "", minIntervalMs: Long = 30_000L) {
        isDailyReportLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val dateParam = if (date.isNotBlank()) "&date=$date" else ""
                val req = okhttp3.Request.Builder().url("$apiBase/api/daily-report?$dateParam").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        dailyReport = DailyReportData(
                            date = json.optString("date", ""),
                            healthScore = json.optInt("health_score", 0),
                            cpuAvg = json.optDouble("cpu_avg", 0.0).toFloat(),
                            cpuPeak = json.optDouble("cpu_peak", 0.0).toFloat(),
                            ramAvg = json.optDouble("ram_avg", 0.0).toFloat(),
                            ramPeak = json.optDouble("ram_peak", 0.0).toFloat(),
                            downloadMb = json.optDouble("download_mb", 0.0).toFloat(),
                            uploadMb = json.optDouble("upload_mb", 0.0).toFloat(),
                            errorCount = json.optInt("error_count", 0),
                            warningCount = json.optInt("warning_count", 0)
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SysMonitor", "fetchDailyReport: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isDailyReportLoading = false }
            }
        }
    }

    fun fetchDiskHealth(minIntervalMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (now - lastDiskHealthRefreshAt < minIntervalMs) return
        isFetchingDiskHealth = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/smart/health").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        diskHealthCurrent = WebDavViewModel.DiskHealthSample(
                            ts = System.currentTimeMillis(),
                            datetime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                            score = json.optInt("score", 0),
                            smartStatus = json.optString("smart_status", "unknown"),
                            tempC = json.optInt("temperature", 0).takeIf { it > 0 },
                            powerOnHours = json.optInt("power_on_hours", 0).takeIf { it > 0 },
                            reallocatedSectors = json.optJSONObject("watch_fields")?.optInt("reallocated_sectors"),
                            pendingSectors = json.optJSONObject("watch_fields")?.optInt("pending_sectors"),
                            offlineUncorrectable = json.optJSONObject("watch_fields")?.optInt("offline_uncorrectable"),
                            udmaCrcErr = json.optJSONObject("watch_fields")?.optInt("udma_crc_err"),
                            commandTimeout = json.optJSONObject("watch_fields")?.optInt("command_timeout"),
                            ext4ErrorsRecent = diskHealthCurrent?.ext4ErrorsRecent ?: 0,
                            sataResetsRecent = diskHealthCurrent?.sataResetsRecent ?: 0,
                            ioErrorsRecent = diskHealthCurrent?.ioErrorsRecent ?: 0,
                            warnings = (json.optJSONArray("warnings")?.let { arr ->
                                (0 until arr.length()).mapNotNull { arr.optString(it) }
                            } ?: emptyList())
                        )
                        lastDiskHealthRefreshAt = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SysMonitor", "fetchDiskHealth: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingDiskHealth = false }
            }
        }
    }

    fun fetchDiskHealthHistory(days: Int = 7) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/smart/history?days=$days").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "[]"
                    val arr = org.json.JSONArray(body)
                    withContext(Dispatchers.Main) {
                        diskHealthHistory = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                            WebDavViewModel.DiskHealthSample(
                                ts = it.optLong("ts", 0L),
                                datetime = it.optString("datetime", ""),
                                score = it.optInt("score", 0),
                                smartStatus = it.optString("smart_status", "unknown"),
                                tempC = it.optInt("temp_c", 0).takeIf { v -> v > 0 },
                                powerOnHours = it.optInt("power_on_hours", 0).takeIf { v -> v > 0 },
                                reallocatedSectors = null,
                                pendingSectors = null,
                                offlineUncorrectable = null,
                                udmaCrcErr = null,
                                commandTimeout = null,
                                ext4ErrorsRecent = 0,
                                sataResetsRecent = 0,
                                ioErrorsRecent = 0,
                                warnings = emptyList()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SysMonitor", "fetchDiskHealthHistory: ${e.message}")
            }
        }
    }

    fun fetchNasConfigBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/config/backups").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "[]"
                    val arr = org.json.JSONArray(body)
                    withContext(Dispatchers.Main) {
                        nasConfigBackups = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                            WebDavViewModel.NasConfigBackup(
                                filename = it.optString("filename", ""),
                                createdAt = it.optString("created_at", ""),
                                sizeBytes = it.optLong("size_bytes", 0L),
                                sizeHuman = it.optString("size_human", "--"),
                                mtime = it.optDouble("mtime", 0.0)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SysMonitor", "fetchNasConfigBackups: ${e.message}")
            }
        }
    }

    fun createNasConfigBackup() {
        isCreatingNasConfigBackup = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/config/backup")
                    .post(ByteArray(0).toRequestBody(null, 0, 0)).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        nasConfigBackupMessage = if (resp.isSuccessful) "Đã tạo backup" else "Lỗi tạo backup"
                    }
                }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isCreatingNasConfigBackup = false }
            }
        }
    }

    fun deleteNasConfigBackup(filename: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("filename", filename).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/config/backup").delete(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
                fetchNasConfigBackups()
            } catch (e: Exception) {
                android.util.Log.w("SysMonitor", "deleteNasConfigBackup: ${e.message}")
            }
        }
    }

    fun restoreNasConfigBackup(filename: String) {
        isRestoringNasConfigBackup = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("filename", filename).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/config/restore").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        nasConfigBackupMessage = if (resp.isSuccessful) "Đã restore" else "Lỗi restore"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { nasConfigBackupMessage = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isRestoringNasConfigBackup = false }
            }
        }
    }

    fun fetchNasInsights(minIntervalMs: Long = 10_000L) {
        if (isFetchingNasInsights) return
        isFetchingNasInsights = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/insights").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        nasInsights = WebDavViewModel.NasInsights(
                            hddScore = json.optInt("hdd_score", 0),
                            hddStatusText = json.optString("hdd_status", ""),
                            hddTempC = json.optInt("hdd_temp_c", 0),
                            emmcRootPercent = json.optInt("emmc_root_percent", 0),
                            emmcLogPercent = json.optInt("emmc_log_percent", 0),
                            maintenanceActions = json.optJSONArray("recommendations")?.let { arr ->
                                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                                    WebDavViewModel.InsightAction(
                                        priority = it.optString("priority", ""),
                                        title = it.optString("title", ""),
                                        detail = it.optString("detail", "")
                                    )
                                }
                            } ?: emptyList(),
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SysMonitor", "fetchNasInsights: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingNasInsights = false }
            }
        }
    }

    fun fetchSystemProcesses(context: Context) {
        isLoadingProcesses = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/system/processes").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "[]"
                    val arr = org.json.JSONArray(body)
                    withContext(Dispatchers.Main) {
                        systemProcesses = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                            SystemProcess(
                                pid = it.optInt("pid", 0),
                                name = it.optString("name", ""),
                                user = it.optString("user", "root"),
                                status = it.optString("status", "running"),
                                cpu = it.optDouble("cpu", 0.0).toFloat(),
                                mem = it.optDouble("mem", 0.0).toFloat()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SysMonitor", "fetchSystemProcesses: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isLoadingProcesses = false }
            }
        }
    }

    fun pingNas() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val t0 = System.currentTimeMillis()
                val req = okhttp3.Request.Builder().url("$apiBase/api/ping").head().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - t0
                    withContext(Dispatchers.Main) {
                        networkPingMs = latency
                        apiLatencyMs = latency
                        apiFailureCount = if (resp.isSuccessful) 0 else (apiFailureCount + 1)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    apiFailureCount++
                    networkPingMs = null
                }
            }
        }
    }
}