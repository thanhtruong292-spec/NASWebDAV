package com.nas.naswebdav.device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.DockerContainer
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.OmvOverview
import com.nas.naswebdav.SmartInfo
import com.nas.naswebdav.SpeedTestResult
import com.nas.naswebdav.StorageFolderUsage
import com.nas.naswebdav.SystemLog
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.toApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

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

    // ═══ WIRED METHODS — Phase 2b: delegation pattern ═══

    /**
     * Toggle SMB server — delegates to WebDavManager API.
     * Pattern: VM gọi API, update state. Facade calls this method.
     */
    fun toggleSmb(context: android.content.Context, enabled: Boolean) {
        isSmbEnabled = enabled
        isLoadingSmb = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("enabled", enabled).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("$apiBase/api/smb/toggle")
                    .post(body)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    isLoadingSmb = false
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "toggleSmb failed: ${e.message}")
                isSmbEnabled = !enabled // revert
                isLoadingSmb = false
            }
        }
    }

    /** Load Docker container list — Phase 2b wired */
    fun loadDockerContainers() {
        isFetchingDocker = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val request = okhttp3.Request.Builder()
                    .url("$apiBase/api/docker/containers")
                    .get().build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "[]"
                        val arr = org.json.JSONArray(body)
                        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                            DockerContainer(
                                id = it.optString("id", ""),
                                name = it.optString("name", ""),
                                status = it.optString("status", "")
                            )
                        }
                        withContext(Dispatchers.Main) {
                            dockerContainers = list
                            isDockerRunning = list.any { it.status == "running" }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "loadDockerContainers: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingDocker = false }
            }
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