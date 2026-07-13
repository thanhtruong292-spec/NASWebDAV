package com.nas.naswebdav.monitor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.DailyReportData
import com.nas.naswebdav.MetricsSnapshot
import com.nas.naswebdav.NasSystemStatus
import com.nas.naswebdav.SystemProcess
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.WebDavViewModel
import kotlinx.coroutines.launch

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

    // ═══ PLACEHOLDER METHODS — implement Phase 4b ═══

    fun startDashboardMonitoring(resetStatusPoll: Boolean = false) {
        viewModelScope.launch { /* TODO Phase 4b */ }
    }

    fun launchMetricsPolling() { viewModelScope.launch { /* TODO Phase 4b */ } }
    fun fetchMetricsHistory(hours: Int = 1) { viewModelScope.launch { /* TODO Phase 4b */ } }
    fun fetchRealtimeMetricPoint() { viewModelScope.launch { /* TODO Phase 4b */ } }
    fun fetchDailyReport(date: String = "", minIntervalMs: Long = 30_000L) { viewModelScope.launch { /* TODO Phase 4b */ } }

    fun fetchDiskHealth(minIntervalMs: Long = 30_000L) { viewModelScope.launch { /* TODO Phase 4b */ } }
    fun fetchDiskHealthHistory(days: Int = 7) { viewModelScope.launch { /* TODO Phase 4b */ } }

    fun fetchNasConfigBackups() { viewModelScope.launch { /* TODO Phase 4b */ } }
    fun createNasConfigBackup() { viewModelScope.launch { /* TODO Phase 4b */ } }
    fun deleteNasConfigBackup(filename: String) { viewModelScope.launch { /* TODO Phase 4b */ } }
    fun restoreNasConfigBackup(filename: String) { viewModelScope.launch { /* TODO Phase 4b */ } }

    fun fetchNasInsights(minIntervalMs: Long = 10_000L) { viewModelScope.launch { /* TODO Phase 4b */ } }

    fun fetchSystemProcesses(context: Context) { viewModelScope.launch { /* TODO Phase 4b */ } }

    fun pingNas() { viewModelScope.launch { /* TODO Phase 4b */ } }
}