package com.nas.naswebdav.device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.DockerContainer
import com.nas.naswebdav.OmvOverview
import com.nas.naswebdav.SmartInfo
import com.nas.naswebdav.SpeedTestResult
import com.nas.naswebdav.StorageFolderUsage
import com.nas.naswebdav.SystemLog
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import kotlinx.coroutines.launch

/**
 * DeviceManagementViewModel — Phase 2 của VM Split.
 *
 * Quản lý: SMB server toggle, LAN whitelist, Docker, OMV, SMART info, Speedtest,
 *          Fan mode, Storage usage, System logs.
 *
 * Constructor: (repository) — WebDavManager là singleton, dùng trực tiếp.
 * State: giữ mutableStateOf (Compose snapshot) cho UI read trực tiếp.
 *
 * Phase 2 chỉ tạo skeleton — sẽ migrate function bodies từ facade trong các
 * commits tiếp theo. Trước mắt, facade WebDavViewModel forward calls sang
 * WebDavManager/repository trực tiếp để không break UI.
 */
class DeviceManagementViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ STATE — mirror của WebDavViewModel để UI không break ═══

    var isSmbEnabled by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isLoadingSmb by androidx.compose.runtime.mutableStateOf(false)
        private set

    var lanWhitelistIps by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set
    var lanWhitelistSubnets by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set
    var lanWhitelistLoading by androidx.compose.runtime.mutableStateOf(true)
        private set
    var lanWhitelistError by androidx.compose.runtime.mutableStateOf("")
        private set
    var lanWhitelistStatus by androidx.compose.runtime.mutableStateOf("")
        private set

    var dockerContainers by androidx.compose.runtime.mutableStateOf<List<DockerContainer>>(emptyList())
        private set
    var isFetchingDocker by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isDockerRunning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isTogglingDocker by androidx.compose.runtime.mutableStateOf(false)
        private set

    var omvOverview by androidx.compose.runtime.mutableStateOf(OmvOverview())
        private set
    var isFetchingOmvOverview by androidx.compose.runtime.mutableStateOf(false)
        private set

    var smartInfo by androidx.compose.runtime.mutableStateOf(SmartInfo("Đang tải...", "--", ""))
        private set
    var showSmartDialog by androidx.compose.runtime.mutableStateOf(false)
        private set

    var speedTestResult by androidx.compose.runtime.mutableStateOf(SpeedTestResult("--", "--"))
        private set
    var isTestingSpeed by androidx.compose.runtime.mutableStateOf(false)
        private set
    var lastAutoSpeedTime by androidx.compose.runtime.mutableLongStateOf(0L)
        private set

    var isFanModeUpdating by androidx.compose.runtime.mutableStateOf(false)
        private set

    var storageFolderUsage by androidx.compose.runtime.mutableStateOf<List<StorageFolderUsage>>(emptyList())
        private set
    var isFetchingStorageUsage by androidx.compose.runtime.mutableStateOf(false)
        private set

    var systemLogs by androidx.compose.runtime.mutableStateOf(listOf<SystemLog>())
        private set
    var systemLogsList by androidx.compose.runtime.mutableStateOf<List<SystemLog>>(emptyList())
        private set
    var showLogDialog by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isFetchingLogs by androidx.compose.runtime.mutableStateOf(false)
        private set

    // ═══ API placeholders — implement ở Phase 2b ═══

    /** Toggle SMB server — Phase 2b sẽ move body từ facade */
    fun toggleSmb(enabled: Boolean) {
        viewModelScope.launch {
            // TODO Phase 2b: move body từ WebDavViewModel.toggleSmbServer
        }
    }

    /** Load Docker container list — Phase 2b */
    fun loadDockerContainers() {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    /** Toggle Docker daemon on/off — Phase 2b */
    fun toggleDockerPower(action: String) {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    /** Load LAN whitelist — Phase 2b */
    fun loadLanWhitelist() {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    fun addLanWhitelistIp(ip: String) { /* TODO Phase 2b */ }
    fun removeLanWhitelistIp(ip: String) { /* TODO Phase 2b */ }
    fun addLanWhitelistSubnet(subnet: String) { /* TODO Phase 2b */ }
    fun removeLanWhitelistSubnet(subnet: String) { /* TODO Phase 2b */ }

    /** Load OMV overview — Phase 2b */
    fun loadOmvOverview() {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    /** Run SMART info — Phase 2b */
    fun runSmartInfo() {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    fun showSmartInfo() { showSmartDialog = true }
    fun hideSmartInfo() { showSmartDialog = false }

    /** Run network speedtest — Phase 2b */
    fun runNetworkSpeedTest() {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    /** Set fan mode (on/off/auto) — Phase 2b */
    fun setFanMode(mode: String, onTemp: Float? = null, offTemp: Float? = null) {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    /** Fetch storage folder usage — Phase 2b */
    fun fetchStorageUsage(minIntervalMs: Long = 30_000L) {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }

    fun showSystemLogs() { showLogDialog = true }
    fun fetchSystemLogs() {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }
    fun clearSystemLogs() {
        viewModelScope.launch {
            // TODO Phase 2b
        }
    }
}