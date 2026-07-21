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
import com.nas.naswebdav.DiskHealthSample
import com.nas.naswebdav.InsightAction
import com.nas.naswebdav.InsightFlowTask
import com.nas.naswebdav.NasInsights
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
 *          Fan mode, Network ping, NasConfigBackups, NasInsights, System processes.
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

    var diskHealthCurrent by androidx.compose.runtime.mutableStateOf<DiskHealthSample?>(null)
        internal set
    var diskHealthHistory by androidx.compose.runtime.mutableStateOf<List<DiskHealthSample>>(emptyList())
        internal set
    var isFetchingDiskHealth by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var lastDiskHealthRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set


    // ═══ NAS INSIGHTS ═══

    var nasInsights by androidx.compose.runtime.mutableStateOf(NasInsights())
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
            var consecutiveFails = 0
            while (true) {
                val ok = fetchStatusNow()
                if (ok) { consecutiveFails = 0; delay(60_000L) }
                else { consecutiveFails++; delay((60_000L + consecutiveFails.coerceAtMost(20) * 5_000L).coerceAtMost(120_000L)) }
            }
        }
    }

    private suspend fun fetchStatusNow(): Boolean {
        return try {
            val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
            // Dùng /api/status thay vì /api/status/realtime vì cần full data (disk, uptime, string formats)
            val req = okhttp3.Request.Builder().url("$apiBase/api/status").get().let(WebDavManager::tagCurrentAuth).build()
            NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    
                    val diskPartsArr = json.optJSONArray("disk_parts")
                    val diskPartList = mutableListOf<com.nas.naswebdav.DiskPart>()
                    if (diskPartsArr != null) {
                        for (i in 0 until diskPartsArr.length()) {
                            val dObj = diskPartsArr.getJSONObject(i)
                            diskPartList.add(com.nas.naswebdav.DiskPart(
                                mount = dObj.optString("mount", "/"),
                                percent = dObj.optDouble("percent", 0.0).toFloat(),
                                total = dObj.optString("total", "0GB"),
                                used = dObj.optString("used", "0GB")
                            ))
                        }
                    }

                    val torrentsArr = json.optJSONArray("torrents")
                    val torrentList = mutableListOf<com.nas.naswebdav.TorrentInfo>()
                    if (torrentsArr != null) {
                        for (i in 0 until torrentsArr.length()) {
                            val tObj = torrentsArr.getJSONObject(i)
                            torrentList.add(com.nas.naswebdav.TorrentInfo(
                                name = tObj.optString("name", "Đang tải..."),
                                progress = tObj.optDouble("progress", 0.0).toFloat(),
                                speed = tObj.optString("speed", "0 B/s"),
                                hash = tObj.optString("hash", ""),
                                state = tObj.optString("state", ""),
                                savePath = tObj.optString("save_path", "")
                            ))
                        }
                    }

                    val topProcsArr = json.optJSONArray("top_processes")
                    val topProcs = mutableListOf<Pair<String, Float>>()
                    if (topProcsArr != null) {
                        for (i in 0 until topProcsArr.length()) {
                            val pObj = topProcsArr.getJSONObject(i)
                            topProcs.add(Pair(pObj.optString("name", "?"), pObj.optDouble("cpu", 0.0).toFloat()))
                        }
                    }

                    val rawDisk = json.optString("disk", "--%|")
                    val diskPartsStrArr = rawDisk.split("|")
                    val diskStr = diskPartsStrArr[0]
                    val diskCapacity = diskPartsStrArr.getOrNull(1) ?: ""
                    
                    val fanRpmRaw = json.opt("fan_rpm")
                    val fanRpm = if (fanRpmRaw != null && fanRpmRaw != org.json.JSONObject.NULL) (fanRpmRaw as? Int) else null

                    withContext(Dispatchers.Main) {
                        systemStatus = NasSystemStatus(
                            temp = json.optString("temperature", "--°C"),
                            cpu = json.optString("cpu", "--%"),
                            cpuTemp = json.optString("cpu_temp", "--°C"),
                            ram = json.optString("ram", "--"),
                            disk = diskStr,
                            diskCapacity = diskCapacity,
                            netRx = json.optString("net_rx", "0 B/s"),
                            netTx = json.optString("net_tx", "0 B/s"),
                            uptime = json.optString("uptime", "--"),
                            status = "Đã kết nối",
                            ramPercent = json.optString("ram_percent", "0"),
                            torrents = torrentList,
                            diskParts = diskPartList,
                            fanStatus = json.optString("fan_status", "--"),
                            fanMode = json.optString("fan_mode", "auto"),
                            fanOnTemp = json.optDouble("fan_on_temp", 50.0).toFloat(),
                            fanOffTemp = json.optDouble("fan_off_temp", 40.0).toFloat(),
                            fanRpm = fanRpm,
                            topProcesses = topProcs
                        )
                        lastStatusRefreshAt = System.currentTimeMillis()

                        val tempRaw = systemStatus.temp
                        val cpuTemp = systemStatus.cpuTemp
                        val hddVal = tempRaw.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                        val cpuVal = cpuTemp.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                        
                        if (hddVal > 0f || cpuVal > 0f) {
                            temperatureHistory.add(Pair(cpuVal, hddVal))
                            while (temperatureHistory.size > 40) temperatureHistory.removeAt(0)
                        }
                    }
                    true
                } else false
            }
        } catch (e: Exception) {
            android.util.Log.w("SysMonitor", "fetchStatusNow: ${e.message}")
            false
        }
    }

    fun launchMetricsPolling() {
        metricsPollingJob?.cancel()
        metricsPollingJob = viewModelScope.launch(Dispatchers.IO) {
            var consecutiveFails = 0
            var lastSize = metricsHistory.size
            while (true) {
                fetchRealtimeMetricPoint()
                val changed = metricsHistory.size > lastSize
                lastSize = metricsHistory.size
                if (changed) { consecutiveFails = 0; delay(30_000L) }
                else { consecutiveFails++; delay((30_000L + consecutiveFails.coerceAtMost(20) * 5_000L).coerceAtMost(120_000L)) }
            }
        }
    }

    fun fetchMetricsHistory(hours: Int = 1) {
        if (isLoadingMetrics) return
        isLoadingMetrics = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/metrics/history?hours=$hours").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    // Server trả về columnar format: {timestamps:[...], cpu_percent:[...], ...}
                    // Không phải mảng objects, phải parse ra từng array riêng
                    val json = org.json.JSONObject(body)
                    val timestamps = json.optJSONArray("timestamps")
                    val cpuArr     = json.optJSONArray("cpu_percent")
                    val ramArr     = json.optJSONArray("ram_percent")
                    val cpuTempArr = json.optJSONArray("cpu_temp")
                    val hddTempArr = json.optJSONArray("hdd_temp")
                    val netRxArr   = json.optJSONArray("net_rx_kbps")
                    val netTxArr   = json.optJSONArray("net_tx_kbps")
                    val count = timestamps?.length() ?: 0
                    withContext(Dispatchers.Main) {
                        metricsHours = hours
                        metricsHistory.clear()
                        for (i in 0 until count) {
                            metricsHistory.add(MetricsSnapshot(
                                timestamp = timestamps?.optString(i, "") ?: "",
                                cpuTemp   = cpuTempArr?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                                cpuPercent = cpuArr?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                                ramPercent = ramArr?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                                hddTemp    = hddTempArr?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                                netRxKbps  = netRxArr?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                                netTxKbps  = netTxArr?.optDouble(i, 0.0)?.toFloat() ?: 0f
                            ))
                        }
                        lastMetricsRefreshAt = System.currentTimeMillis()
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
                // FIX: endpoint đúng cho realtime metrics là /api/status/realtime,
                // không phải /api/process_state (là worker cursor data, không phải metrics)
                val req = okhttp3.Request.Builder().url("$apiBase/api/status/realtime").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        val point = MetricsSnapshot(
                            timestamp  = System.currentTimeMillis().toString(),
                            cpuTemp    = json.optDouble("cpu_temp", 0.0).toFloat(),
                            cpuPercent = json.optDouble("cpu_percent", 0.0).toFloat(),
                            ramPercent = json.optDouble("ram_percent", 0.0).toFloat(),
                            hddTemp    = json.optDouble("hdd_temp", 0.0).toFloat(),
                            netRxKbps  = json.optDouble("net_rx_kbps", 0.0).toFloat(),
                            netTxKbps  = json.optDouble("net_tx_kbps", 0.0).toFloat()
                        )
                        metricsHistory.add(point)
                        if (metricsHistory.size > 1000) {
                            metricsHistory.removeRange(0, 200)
                        }
                        lastMetricsRefreshAt = System.currentTimeMillis()
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
                val dateParam = if (date.isNotBlank()) "?date=$date" else ""
                val req = okhttp3.Request.Builder().url("$apiBase/api/report/daily$dateParam").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/disk/health").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        diskHealthCurrent = DiskHealthSample(
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/disk/health/history?days=$days").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "[]"
                    val arr = org.json.JSONArray(body)
                    withContext(Dispatchers.Main) {
                        diskHealthHistory = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                            DiskHealthSample(
                                ts = it.optLong("ts", 0L),
                                datetime = it.optString("datetime", ""),
                                score = it.optInt("score", 0),
                                smartStatus = it.optString("smart_status", "unknown"),
                                tempC = it.optInt("temp_c", 0).takeIf { v -> v > 0 },
                                powerOnHours = it.optInt("power_on_hours", 0).takeIf { v -> v > 0 },
                                reallocatedSectors = it.optJSONObject("watch_fields")?.optInt("reallocated_sectors"),
                                pendingSectors = it.optJSONObject("watch_fields")?.optInt("pending_sectors"),
                                offlineUncorrectable = it.optJSONObject("watch_fields")?.optInt("offline_uncorrectable"),
                                udmaCrcErr = it.optJSONObject("watch_fields")?.optInt("udma_crc_err"),
                                commandTimeout = it.optJSONObject("watch_fields")?.optInt("command_timeout"),
                                ext4ErrorsRecent = it.optInt("ext4_errors_recent", 0),
                                sataResetsRecent = it.optInt("sata_resets_recent", 0),
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        val health = json.optJSONObject("health_trend") ?: org.json.JSONObject()
                        val workload = json.optJSONObject("workload") ?: org.json.JSONObject()
                        val emmc = json.optJSONObject("emmc_guard") ?: org.json.JSONObject()
                        val rootEmmc = emmc.optJSONObject("root") ?: org.json.JSONObject()
                        val logEmmc = emmc.optJSONObject("log") ?: org.json.JSONObject()
                        val flow = json.optJSONObject("data_flow") ?: org.json.JSONObject()
                        
                        val emmcWarningsList: List<String> = emmc.optJSONArray("warnings")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it) } } ?: emptyList()
                        val emmcRecsList: List<String> = emmc.optJSONArray("recommendations")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it) } } ?: emptyList()
                        val workloadReasonsList: List<String> = workload.optJSONArray("reasons")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it) } } ?: emptyList()

                        val maintenanceArr = json.optJSONArray("maintenance")
                        val parsedActions: List<InsightAction> = maintenanceArr?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                                InsightAction(
                                    priority = it.optString("priority", ""),
                                    title = it.optString("title", ""),
                                    detail = it.optString("detail", "")
                                )
                            }
                        } ?: emptyList()

                        val tasksArr = flow.optJSONArray("tasks")
                        val parsedTasks: List<InsightFlowTask> = tasksArr?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                                InsightFlowTask(
                                    label = it.optString("label", ""),
                                    file = it.optString("file", "")
                                )
                            }
                        } ?: emptyList()

                        nasInsights = NasInsights(
                            hddScore = health.optInt("score", 0),
                            hddStatusText = health.optString("status_text", health.optString("smart_status", "")),
                            hddTempC = health.optInt("temp_c", 0),
                            hddMinScore = health.optInt("min_score", 0),
                            hddScoreDelta = health.optInt("score_delta", 0),
                            workloadMode = workload.optString("mode", "normal"),
                            workloadPressure = workload.optInt("pressure", 0),
                            workloadRecommendation = workload.optString("recommendation", ""),
                            workloadReasons = workloadReasonsList,
                            emmcRootPercent = rootEmmc.optInt("percent", 0),
                            emmcLogPercent = logEmmc.optInt("percent", 0),
                            emmcWarnings = emmcWarningsList,
                            emmcRecommendations = emmcRecsList,
                            diskReadBps = flow.optLong("disk_read_bps", 0L),
                            diskWriteBps = flow.optLong("disk_write_bps", 0L),
                            netRxBps = flow.optLong("net_rx_bps", 0L),
                            netTxBps = flow.optLong("net_tx_bps", 0L),
                            flowTasks = parsedTasks,
                            maintenanceActions = parsedActions,
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/processes").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val obj = org.json.JSONObject(body)
                    val arr = obj.optJSONArray("data") ?: org.json.JSONArray()
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

    fun killSystemProcess(context: Context, pid: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val json = org.json.JSONObject().put("pid", pid).toString()
                val req = okhttp3.Request.Builder()
                    .url("$apiBase/api/processes/kill")
                    .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            android.widget.Toast.makeText(context, "Đã kill tiến trình PID $pid", android.widget.Toast.LENGTH_SHORT).show()
                            fetchSystemProcesses(context)
                        } else {
                            val err = org.json.JSONObject(body).optString("error", "Lỗi kill tiến trình")
                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Lỗi kết nối: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
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