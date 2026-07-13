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

    /** Toggle Docker daemon on/off — Phase 2b wired */
    fun toggleDockerPower(action: String) {
        isTogglingDocker = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("action", action).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$apiBase/api/docker/power").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
                loadDockerContainers()
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "toggleDockerPower: ${e.message}")
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) { isTogglingDocker = false }
            }
        }
    }

    /** Load LAN whitelist — Phase 2b wired */
    fun loadLanWhitelist() {
        lanWhitelistLoading = true
        lanWhitelistError = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/whitelist/list").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    val ipsArr = json.optJSONArray("ips")
                    val subnetsArr = json.optJSONArray("subnets")
                    withContext(Dispatchers.Main) {
                        lanWhitelistIps = (0 until (ipsArr?.length() ?: 0)).mapNotNull { ipsArr?.optString(it) }
                        lanWhitelistSubnets = (0 until (subnetsArr?.length() ?: 0)).mapNotNull { subnetsArr?.optString(it) }
                        lanWhitelistStatus = json.optString("status", "")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { lanWhitelistError = e.message ?: "Lỗi" }
            } finally {
                withContext(Dispatchers.Main) { lanWhitelistLoading = false }
            }
        }
    }

    private fun modifyWhitelist(action: String, kind: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("action", action).put("kind", kind).put("value", value).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/whitelist/modify").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
                loadLanWhitelist()
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "modifyWhitelist: ${e.message}")
            }
        }
    }
    fun addLanWhitelistIp(ip: String) = modifyWhitelist("add", "ip", ip)
    fun removeLanWhitelistIp(ip: String) = modifyWhitelist("remove", "ip", ip)
    fun addLanWhitelistSubnet(subnet: String) = modifyWhitelist("add", "subnet", subnet)
    fun removeLanWhitelistSubnet(subnet: String) = modifyWhitelist("remove", "subnet", subnet)

    /** Load OMV overview — Phase 2b wired */
    fun loadOmvOverview() {
        isFetchingOmvOverview = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/omv/overview").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        omvOverview = OmvOverview(
                            hostname = json.optString("hostname", ""),
                            omvVersion = json.optString("version", ""),
                            kernel = json.optString("kernel", ""),
                            powerBtnAction = json.optString("power_btn_action", "")
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "loadOmvOverview: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingOmvOverview = false }
            }
        }
    }

    /** Run SMART info — Phase 2b wired */
    fun runSmartInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/smart/info").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        smartInfo = SmartInfo(
                            status = json.optString("status", "OK"),
                            temperature = json.optString("temperature", "--"),
                            rawLog = json.optString("raw", "")
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "runSmartInfo: ${e.message}")
            }
        }
    }

    fun showSmartInfo() { showSmartDialog = true }
    fun hideSmartInfo() { showSmartDialog = false }

    /** Run network speedtest — Phase 2b wired */
    fun runNetworkSpeedTest() {
        isTestingSpeed = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/network/speedtest").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        speedTestResult = SpeedTestResult(
                            writeSpeed = json.optString("write_speed", "--"),
                            readSpeed = json.optString("read_speed", "--")
                        )
                        lastAutoSpeedTime = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "runNetworkSpeedTest: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isTestingSpeed = false }
            }
        }
    }

    /** Set fan mode (on/off/auto/custom) — Phase 2b wired */
    fun setFanMode(mode: String, onTemp: Float? = null, offTemp: Float? = null) {
        if (isFanModeUpdating) return
        isFanModeUpdating = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val jsonBody = org.json.JSONObject().put("mode", mode)
                if (mode == "custom" && onTemp != null && offTemp != null) {
                    jsonBody.put("on_temp", onTemp)
                    jsonBody.put("off_temp", offTemp)
                }
                val req = okhttp3.Request.Builder()
                    .url("$apiBase/api/fan/control")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "setFanMode: ${e.message}")
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) { isFanModeUpdating = false }
            }
        }
    }

    /** Fetch storage folder usage — Phase 2b wired */
    fun fetchStorageUsage(minIntervalMs: Long = 30_000L) {
        if (isFetchingStorageUsage) return
        isFetchingStorageUsage = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/storage/usage").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "[]"
                    val arr = org.json.JSONArray(body)
                    withContext(Dispatchers.Main) {
                        storageFolderUsage = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                            StorageFolderUsage(
                                name = it.optString("name", ""),
                                path = it.optString("path", ""),
                                size = it.optString("size", "--"),
                                sizeBytes = it.optLong("size_bytes", 0L),
                                files = it.optInt("files", 0),
                                partial = it.optBoolean("partial", false)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "fetchStorageUsage: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingStorageUsage = false }
            }
        }
    }

    fun showSystemLogs() { showLogDialog = true }
    fun fetchSystemLogs() {
        isFetchingLogs = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = NasApplication.instance.database
                val logs = db.logDao().getRecentLogs()
                withContext(Dispatchers.Main) {
                    systemLogsList = logs
                    systemLogs = logs
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "fetchSystemLogs: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isFetchingLogs = false }
            }
        }
    }
    fun clearSystemLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = NasApplication.instance.database
                db.logDao().clearAllLogs()
                withContext(Dispatchers.Main) {
                    systemLogsList = emptyList()
                    systemLogs = emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "clearSystemLogs: ${e.message}")
            }
        }
    }
}