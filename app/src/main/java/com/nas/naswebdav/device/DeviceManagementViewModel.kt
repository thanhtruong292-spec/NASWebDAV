package com.nas.naswebdav.device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.DockerContainer
import com.nas.naswebdav.OmvDiskInfo
import com.nas.naswebdav.OmvFilesystem
import com.nas.naswebdav.OmvNetworkInfo
import com.nas.naswebdav.OmvServiceInfo
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.OmvOverview
import com.nas.naswebdav.SmartInfo
import com.nas.naswebdav.SpeedTestResult
import com.nas.naswebdav.StorageFolderUsage
import com.nas.naswebdav.SystemLog
import com.nas.naswebdav.UsbImportSettings
import com.nas.naswebdav.UsbImportState
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.toApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.core.content.edit

/**
 * DeviceManagementViewModel — Primary owner of device-management state.
 *
 * Quản lý: SMB server toggle, LAN whitelist, Docker, OMV, SMART info, Speedtest,
 *          Fan mode, Storage usage, System logs, USB import,
 *          Rules config, Device approval.
 *
 * Constructor: (repository) — WebDavManager là singleton, dùng trực tiếp.
 * State: giữ mutableStateOf (Compose snapshot) cho UI read trực tiếp.
 *
 * Phase 2: initial skeleton. Phase 7d.x: migrated from WebDavViewModel facade.
 * UI now reads state directly from this VM.
 */
class DeviceManagementViewModel(
    private val repository: WebDavRepository,
    private val injectedGlobalUi: com.nas.naswebdav.GlobalUiViewModel? = null,
) : ViewModel() {
    private val _globalUi: com.nas.naswebdav.GlobalUiViewModel by lazy {
        injectedGlobalUi ?: com.nas.naswebdav.GlobalUiViewModel()
    }
    private val globalUi get() = _globalUi

    // ═══ STATE — owned by DeviceManagementViewModel ═══

    var isSmbEnabled by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isLoadingSmb by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var lanWhitelistIps by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        internal set
    var lanWhitelistSubnets by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        internal set
    var lanWhitelistLoading by androidx.compose.runtime.mutableStateOf(true)
        internal set
    var lanWhitelistError by androidx.compose.runtime.mutableStateOf("")
        internal set
    var lanWhitelistStatus by androidx.compose.runtime.mutableStateOf("")
        internal set

    var dockerContainers by androidx.compose.runtime.mutableStateOf<List<DockerContainer>>(emptyList())
        internal set
    var isFetchingDocker by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isDockerRunning by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isTogglingDocker by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var omvOverview by androidx.compose.runtime.mutableStateOf(OmvOverview())
        internal set
    var isFetchingOmvOverview by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var smartInfo by androidx.compose.runtime.mutableStateOf(SmartInfo("Đang tải...", "--", ""))
        internal set
    var lastSmartRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    private var lastOmvOverviewFetchAt = 0L
    var showSmartDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var showDockerDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var speedTestResult by androidx.compose.runtime.mutableStateOf(SpeedTestResult("--", "--"))
        internal set
    var isTestingSpeed by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var lastAutoSpeedTime by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set

    val isOnLan: Boolean
        get() {
            val currentUrl = com.nas.naswebdav.WebDavManager.currentBaseUrl
            if (currentUrl.isNotBlank()) return !com.nas.naswebdav.isTailscaleUrl(currentUrl)
            return com.nas.naswebdav.shared.SharedStateHolder.isOnLan.value
        }

    var isFanModeUpdating by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var storageFolderUsage by androidx.compose.runtime.mutableStateOf<List<StorageFolderUsage>>(readCachedStorageUsage())
        internal set
    var isFetchingStorageUsage by androidx.compose.runtime.mutableStateOf(false)
        internal set
    private var storageUsageUpdatedAt = readCachedStorageUsageAt()

    companion object {
        private const val STORAGE_USAGE_INTERVAL_MS = 30_000L

        private fun readCachedStorageUsage(): List<StorageFolderUsage> {
            return try {
                val raw = com.nas.naswebdav.utils.PreferencesRepository
                    .get(com.nas.naswebdav.NasApplication.instance)
                    .getStorageUsageJson()
                if (raw.isBlank()) return emptyList()
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                    StorageFolderUsage(
                        name = it.optString("name", ""),
                        path = it.optString("path", ""),
                        size = it.optString("size", "--"),
                        sizeBytes = it.optLong("size_bytes", 0L),
                        files = it.optInt("files", 0),
                        partial = it.optBoolean("partial", false)
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

        private fun readCachedStorageUsageAt(): Long {
            return try {
                val raw = com.nas.naswebdav.utils.PreferencesRepository
                    .get(com.nas.naswebdav.NasApplication.instance)
                    .getStorageUsageJson()
                if (raw.isBlank()) return 0L
                org.json.JSONArray(raw).optJSONObject(0)?.optLong("cached_at", 0L) ?: 0L
            } catch (_: Exception) { 0L }
        }
    }

    var systemLogs by androidx.compose.runtime.mutableStateOf(listOf<SystemLog>())
        internal set
    var systemLogsList by androidx.compose.runtime.mutableStateOf<List<SystemLog>>(emptyList())
    var lastLogsRefreshAt by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var showLogDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isFetchingLogs by androidx.compose.runtime.mutableStateOf(false)
        internal set

    // Failed uploads từ SyncAction table (actionType=UPLOAD_FAILED) — cho UI retry/delete
    var failedUploads by androidx.compose.runtime.mutableStateOf<List<com.nas.naswebdav.SyncAction>>(emptyList())
        internal set
    var showFailedUploadsDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isLoadingFailedUploads by androidx.compose.runtime.mutableStateOf(false)
        internal set

    /**
     * Tải danh sách UPLOAD_FAILED rows từ SyncAction table.
     * Gọi khi user mở dialog hoặc pull-to-refresh.
     */
    fun loadFailedUploads(context: android.content.Context) {
        isLoadingFailedUploads = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as NasApplication).database
                failedUploads = db.syncActionDao().getAllUploadFailed()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.nas.naswebdav.utils.SystemLogger.log("WARNING", "DeviceMgmt",
                    "Không tải được failed uploads: ${e.message}")
            } finally {
                isLoadingFailedUploads = false
            }
        }
    }

    /**
     * Retry một UPLOAD_FAILED row cụ thể: enqueue OfflineSyncWorker với inputData
     * chứa actionId. Worker sẽ ưu tiên xử lý đúng row đó trước khi đến các
     * action khác trong queue.
     */
    fun retryFailedUpload(context: android.content.Context, actionId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val inputData = androidx.work.workDataOf(
                    com.nas.naswebdav.OfflineSyncWorker.KEY_RETRY_ACTION_ID to actionId
                )
                val request = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(inputData)
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL, 15L, java.util.concurrent.TimeUnit.SECONDS
                    )
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    com.nas.naswebdav.OfflineSyncWorker.UNIQUE_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
                com.nas.naswebdav.utils.SystemLogger.log("INFO", "DeviceMgmt",
                    "Enqueue retry cho failed upload id=$actionId (priority)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.nas.naswebdav.utils.SystemLogger.log("WARNING", "DeviceMgmt",
                    "Enqueue retry thất bại cho id=$actionId: ${e.message}")
            }
        }
    }

    /**
     * Xóa row UPLOAD_FAILED khỏi queue (user chấp nhận mất file, không muốn retry).
     * Cũng xóa temp file nếu còn trong cacheDir.
     */
    fun deleteFailedUpload(context: android.content.Context, action: com.nas.naswebdav.SyncAction) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as NasApplication).database
                try { java.io.File(action.sourcePath).delete() } catch (_: Exception) {}
                db.syncActionDao().deleteById(action.id)
                failedUploads = db.syncActionDao().getAllUploadFailed()
                com.nas.naswebdav.utils.SystemLogger.log("INFO", "DeviceMgmt",
                    "Đã xóa failed upload id=${action.id} (${action.sourcePath})")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.nas.naswebdav.utils.SystemLogger.log("WARNING", "DeviceMgmt",
                    "Xóa failed upload thất bại: ${e.message}")
            }
        }
    }

    /** Retry tất cả failed uploads cùng lúc */
    fun retryAllFailedUploads(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val request = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL, 15L, java.util.concurrent.TimeUnit.SECONDS
                    )
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    com.nas.naswebdav.OfflineSyncWorker.UNIQUE_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
                com.nas.naswebdav.utils.SystemLogger.log("INFO", "DeviceMgmt",
                    "Enqueue retry-all failed uploads")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.nas.naswebdav.utils.SystemLogger.log("WARNING", "DeviceMgmt",
                    "Retry-all failed: ${e.message}")
            }
        }
    }

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
                NasApplication.instance.fastApiClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    isLoadingSmb = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "toggleSmb failed: ${e.message}")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    isSmbEnabled = !enabled // revert
                    isLoadingSmb = false
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR, "Bật/tắt SMB thất bại: ${e.message}")
                }
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "toggleDockerPower: ${e.message}")
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) { isTogglingDocker = false }
            }
        }
    }

    fun checkDockerStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/docker/power")
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string() ?: "{}")
                        withContext(Dispatchers.Main) {
                            isDockerRunning = json.optBoolean("running", false)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    fun fetchSmbStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/smb/status").build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val obj = org.json.JSONObject(body)
                            val enabled = obj.optBoolean(
                                "effective_enabled",
                                obj.optBoolean("enabled", false) && obj.optBoolean("active", false)
                            )
                            withContext(Dispatchers.Main) { isSmbEnabled = enabled }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "fetchSmbStatus failed", e)
            }
        }
    }

    fun fetchSmartData(minIntervalMs: Long = 15_000L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastSmartRefreshAt < minIntervalMs) return
        lastSmartRefreshAt = now
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/smart").build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string() ?: "")
                        withContext(Dispatchers.Main) {
                            smartInfo = SmartInfo(
                                status = json.optString("status", "Không rõ"),
                                temperature = run {
                                    val rawTemp = json.optString("temperature", "--")
                                    if (rawTemp != "--" && !rawTemp.contains("°")) "${rawTemp}°C" else rawTemp
                                },
                                rawLog = json.optString("raw_log", "")
                            )
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            smartInfo = SmartInfo("Lỗi kết nối", "--", "Mã lỗi: ${response.code}")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    smartInfo = SmartInfo("Không thể kết nối", "--", e.message ?: "")
                }
            }
        }
    }

    fun fetchOmvOverview(minIntervalMs: Long = 15_000L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastOmvOverviewFetchAt < minIntervalMs) return
        if (isFetchingOmvOverview) return
        lastOmvOverviewFetchAt = now
        isFetchingOmvOverview = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/omv/overview").build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            finally { withContext(Dispatchers.Main) { isFetchingOmvOverview = false } }
        }
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

    /** Load LAN whitelist — Phase 2b wired */
    fun loadLanWhitelist() {
        lanWhitelistLoading = true
        lanWhitelistError = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/lan/whitelist").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/lan/whitelist").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
                loadLanWhitelist()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "modifyWhitelist: ${e.message}")
            }
        }
    }
    fun addLanWhitelistIp(ip: String) = modifyWhitelist("add", "ip", ip)
    fun removeLanWhitelistIp(ip: String) = modifyWhitelist("remove", "ip", ip)
    fun addLanWhitelistSubnet(subnet: String) = modifyWhitelist("add", "subnet", subnet)
    fun removeLanWhitelistSubnet(subnet: String) = modifyWhitelist("remove", "subnet", subnet)

    fun addLanWhitelistEntry(entry: String) {
        if (entry.contains("/")) addLanWhitelistSubnet(entry) else addLanWhitelistIp(entry)
    }
    fun removeLanWhitelistEntry(entry: String, isSubnet: Boolean) {
        if (isSubnet) removeLanWhitelistSubnet(entry) else removeLanWhitelistIp(entry)
    }

    /** Load OMV overview — Phase 2b wired */
    fun loadOmvOverview() {
        isFetchingOmvOverview = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/omv/overview").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    val sys = json.optJSONObject("system")
                    withContext(Dispatchers.Main) {
                        omvOverview = OmvOverview(
                            hostname = sys?.optString("hostname", "") ?: "",
                            omvVersion = sys?.optString("omv_version", "") ?: "",
                            kernel = sys?.optString("kernel", "") ?: "",
                            powerBtnAction = sys?.optString("powerbtn", "") ?: ""
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/disk/smart").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/disk/speedtest").post(okhttp3.RequestBody.create(null, ByteArray(0))).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "runNetworkSpeedTest: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isTestingSpeed = false }
            }
        }
    }

    /** Set fan mode (on/off/auto/custom) — Phase 2b wired */
    fun setFanMode(mode: String, onTemp: Float? = null, offTemp: Float? = null, onSuccess: (() -> Unit)? = null) {
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
                    .let(WebDavManager::tagCurrentAuth)  // BUG FIX: thieu auth header -> 401 Unauthorized
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onSuccess?.invoke()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "setFanMode: ${e.message}")
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) { isFanModeUpdating = false }
            }
        }
    }

    /** Fetch storage folder usage — Phase 2b wired.
     * Mở app hiện cache cũ ngay, chỉ fetch nền khi quá 30s (trừ khi force bằng
     * minIntervalMs = 0L từ kéo-refresh tay). Fetch xong lưu cache cho lần sau. */
    fun fetchStorageUsage(minIntervalMs: Long = STORAGE_USAGE_INTERVAL_MS) {
        if (isFetchingStorageUsage) return
        if (minIntervalMs > 0 && System.currentTimeMillis() - storageUsageUpdatedAt < minIntervalMs) return
        isFetchingStorageUsage = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/storage/usage").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "[]"
                    // Backend tra object {"folders":[...]} (khong phai array tran).
                    val arr = runCatching { org.json.JSONObject(body).optJSONArray("folders") }
                        .getOrNull() ?: runCatching { org.json.JSONArray(body) }.getOrNull()
                        ?: org.json.JSONArray()
                    val now = System.currentTimeMillis()
                    try {
                        val cacheArr = org.json.JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            o.put("cached_at", now)
                            cacheArr.put(o)
                        }
                        com.nas.naswebdav.utils.PreferencesRepository
                            .get(com.nas.naswebdav.NasApplication.instance)
                            .setStorageUsageJson(cacheArr.toString())
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) {
                        storageUsageUpdatedAt = now
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "clearSystemLogs: ${e.message}")
            }
        }
    }

    fun logUserAction(module: String, message: String, type: String = "INFO") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addSystemLog(type, module, "Người dùng: $message")
                loadSystemLogs()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("UserActionLog", "log failed: ${e.message}")
            }
        }
    }

    fun loadSystemLogs(minIntervalMs: Long = 15_000L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastLogsRefreshAt < minIntervalMs) return
        lastLogsRefreshAt = now
        viewModelScope.launch(Dispatchers.IO) {
            val allLogs = NasApplication.instance.database.logDao().getRecentLogs().toMutableList()
            try {
                if (WebDavManager.currentBaseUrl.isNotEmpty()) {
                    val req = okhttp3.Request.Builder().url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/system_logs").build()
                    NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (body != null) {
                                val json = org.json.JSONObject(body)
                                if (json.optString("status") == "success") {
                                    val logsArray = json.optJSONArray("logs")
                                    if (logsArray != null) {
                                        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                        for (i in 0 until logsArray.length()) {
                                            val obj = logsArray.getJSONObject(i)
                                            val ts = obj.optString("timestamp")
                                            val timestamp = try { format.parse(ts)?.time ?: System.currentTimeMillis() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { System.currentTimeMillis() }
                                            val remoteMessage = obj.optString("message")
                                            val remoteType = obj.optString("type")
                                            val remoteLog = SystemLog(id = -(obj.optInt("id")), type = remoteType, module = obj.optString("module"), message = remoteMessage, timestamp = timestamp)
                                            if (allLogs.none { it.message == remoteMessage && it.type == remoteType }) allLogs.add(remoteLog)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { android.util.Log.e("DevMgmt", "loadSystemLogs: ${e.message}") }
            allLogs.sortByDescending { it.timestamp }
            withContext(Dispatchers.Main) {
                systemLogsList = allLogs.take(200)
                systemLogs = systemLogsList
                lastLogsRefreshAt = System.currentTimeMillis()
            }
        }
    }

    // ═══ TRASH CLEAN (Phase 7d.6 — moved from WebDavViewModel facade) ═══

    fun cleanTrashOnDemand(context: android.content.Context, maxAgeDays: Int = 30) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = org.json.JSONObject().put("max_age_days", maxAgeDays)
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/cron/trash/clean")
                    .post(body)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { resp ->
                    val res = org.json.JSONObject(resp.body?.string() ?: "{}")
                    val msg = res.optString("message", "Hoàn tất dọn Thùng rác!")
                    repository.addSystemLog("INFO", "File Ops", "Người dùng đã thực hiện XÓA THÙNG RÁC: $msg")
                    withContext(Dispatchers.Main) {
                        globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS, msg)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR, "Lỗi dọn rác: ${e.message}")
                }
            }
        }
    }

    // ═══ OMV / TORRENT (Phase 7d.6 — moved from WebDavViewModel facade) ═══

    /** Run disk speed test (write + read) — moved from WebDavViewModel.runSpeedTest */
    fun runSpeedTest() {
        if (isTestingSpeed) return
        isTestingSpeed = true
        speedTestResult = SpeedTestResult("Đang đo...", "Đang đo...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val request = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/disk/speedtest").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
                val speedTestClient = NasApplication.instance.fastApiClient.newBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                speedTestClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string() ?: "")
                        withContext(Dispatchers.Main) {
                            speedTestResult = SpeedTestResult(
                                json.optString("write_speed", "Lỗi"),
                                json.optString("read_speed", "Lỗi")
                            )
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            speedTestResult = SpeedTestResult("Thất bại", "Thất bại")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    speedTestResult = SpeedTestResult("Lỗi", "Lỗi")
                }
                repository.addSystemLog("ERROR", "SpeedTest", "Đo tốc độ thất bại: ${e.message?.take(80)}")
            } finally {
                withContext(Dispatchers.Main) { isTestingSpeed = false }
            }
        }
    }

    /** Control a single Docker container (start/stop/restart) */
    fun controlDockerContainer(action: String, containerName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val json = org.json.JSONObject().apply {
                    put("action", action)
                    put("container", containerName)
                }
                val body = json.toString().toRequestBody(jsonMediaType)
                val req = okhttp3.Request.Builder()
                    .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/docker/control")
                    .post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
                loadDockerContainers()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "controlDockerContainer $action failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR,
                        "Điều khiển Docker ($action) thất bại: ${e.message}")
                }
            }
        }
    }

    fun toggleOmvService(serviceName: String, enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = org.json.JSONObject()
                    .put("name", serviceName)
                    .put("enable", enable)
                    .toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/service/toggle")
                    .post(body)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                    val ok = response.isSuccessful
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        globalUi.show(
                            if (ok) com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS else com.nas.naswebdav.ui.dialogs.DialogType.ERROR,
                            if (ok) "Đã ${if (enable) "bật" else "tắt"} dịch vụ ${serviceName.uppercase()}." else "Không thể ${if (enable) "bật" else "tắt"} dịch vụ ${serviceName.uppercase()} (HTTP ${response.code})."
                        )
                    }
                }
                loadOmvOverview()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    globalUi.show(
                        com.nas.naswebdav.ui.dialogs.DialogType.ERROR,
                        "Lỗi điều khiển dịch vụ: ${e.message?.take(120)}"
                    )
                }
            }
        }
    }

    fun controlTorrent(action: String, hash: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val json = org.json.JSONObject().apply {
                    put("action", action)
                    put("hash", hash)
                }
                val requestBody = json.toString().toRequestBody(jsonMediaType)
                val request = okhttp3.Request.Builder()
                    .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/torrent/control")
                    .post(requestBody)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "controlTorrent failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR, "Điều khiển torrent thất bại: ${e.message}")
                }
            }
        }
    }

    // ═══ USB IMPORT (Phase 7d.3 — moved from WebDavViewModel facade) ═══

    var usbImportState by androidx.compose.runtime.mutableStateOf(UsbImportState())
        internal set
    var usbImportMessage by androidx.compose.runtime.mutableStateOf("")
        internal set
    var isUsbImportLoading by androidx.compose.runtime.mutableStateOf(false)
        internal set

    private val lastUsbImportStatusFetchAt = AtomicLong(0L)
    private val usbImportStatusInFlight = AtomicBoolean(false)

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
            needsAction = o.optBoolean("needs_action", false),
        )
    }

    private val usbImportApiClient: okhttp3.OkHttpClient by lazy {
        NasApplication.instance.fastApiClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val authHeader = com.nas.naswebdav.WebDavManager.currentAuthHeader().takeIf {
                    com.nas.naswebdav.WebDavManager.currentUser.isNotEmpty() ||
                    com.nas.naswebdav.WebDavManager.currentPass.isNotEmpty()
                }
                val request = if (authHeader != null) {
                    chain.request().newBuilder().header("Authorization", authHeader).build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()
    }

    internal suspend fun fetchUsbImportStatusSuspend(compact: Boolean = false, minIntervalMs: Long = 0L) {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0L && now - lastUsbImportStatusFetchAt.get() < minIntervalMs) return
        if (!usbImportStatusInFlight.compareAndSet(false, true)) return
        lastUsbImportStatusFetchAt.set(now)
        try {
            withContext(Dispatchers.Main) { if (!compact) isUsbImportLoading = true }
            val base = WebDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { "" }
            if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
            val path = if (compact) "/api/usb_import/status?compact=1" else "/api/usb_import/status"
            val req = okhttp3.Request.Builder()
                .url("$base$path")
                .let(WebDavManager::tagCurrentAuth)
                .build()
            usbImportApiClient.newCall(req).execute().use { resp ->
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
        } finally {
            usbImportStatusInFlight.set(false)
            withContext(Dispatchers.Main) { if (!compact) isUsbImportLoading = false }
        }
    }

    fun fetchUsbImportStatus(compact: Boolean = false, minIntervalMs: Long = 0L) {
        viewModelScope.launch { fetchUsbImportStatusSuspend(compact = compact, minIntervalMs = minIntervalMs) }
    }

    fun saveUsbImportSettings(settings: UsbImportSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = WebDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { "" }
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
                usbImportApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lỗi lưu USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { org.json.JSONObject() }
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "Người dùng: lưu cấu hình USB Import thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
            }
        }
    }

    fun startUsbImportNow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { isUsbImportLoading = true }
                val base = WebDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { "" }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/start")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                usbImportApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lỗi bắt đầu USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(
                        if (resp.isSuccessful) "INFO" else "WARNING",
                        "USBImport",
                        "Người dùng: yêu cầu copy USB ngay (${o.optString("message", "không có phản hồi")})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = o.optString("message", if (resp.isSuccessful) "Đã bắt đầu copy USB" else "Không bắt đầu được")
                    }
                }
                fetchUsbImportStatus()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
                val base = WebDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { "" }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/cancel")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                usbImportApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { usbImportMessage = "Lỗi huỷ USB Import: HTTP ${resp.code}" }
                        return@use
                    }
                    val o = try { org.json.JSONObject(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { org.json.JSONObject() }
                    val stateJson = o.optJSONObject("state")
                    repository.addSystemLog(
                        if (resp.isSuccessful) "INFO" else "WARNING",
                        "USBImport",
                        "Người dùng: gửi lệnh hủy USB Import (${if (resp.isSuccessful) "đã gửi" else "thất bại"})."
                    )
                    withContext(Dispatchers.Main) {
                        if (stateJson != null) usbImportState = parseUsbImportState(stateJson)
                        usbImportMessage = if (resp.isSuccessful) "Đã gửi lệnh hủy" else "Không hủy được"
                    }
                }
                fetchUsbImportStatus()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
                val base = WebDavManager.currentBaseUrl.toApiBaseUrl().ifBlank { "" }
                if (base.isBlank()) throw IllegalStateException("Chưa có địa chỉ NAS hợp lệ")
                val body = org.json.JSONObject().apply {
                    put("action", action)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$base/api/usb_import/resolve_conflicts")
                    .post(body)
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                usbImportApiClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "{}"
                    val o = try { org.json.JSONObject(raw) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { org.json.JSONObject() }
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                repository.addSystemLog("WARNING", "USBImport", "Người dùng: xử lý file trùng USB Import thất bại: ${e.message?.take(120)}")
                withContext(Dispatchers.Main) { usbImportMessage = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isUsbImportLoading = false }
            }
        }
    }

    // ═══ RULES CONFIG (Phase 7d.3 — moved from WebDavViewModel facade) ═══

    fun loadRulesConfig(
        onResult: (enabled: Boolean, pauseOnDiskLow: Boolean, pauseOnHeat: Boolean, cpuThreshold: Int, ramThreshold: Int) -> Unit,
    ) {
        val p = NasApplication.instance.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
        onResult(
            p.getBoolean("alert_enabled", true),
            p.getBoolean("alert_pause_disk_low", true),
            p.getBoolean("alert_pause_heat", false),
            p.getInt("alert_cpu_threshold", 90),
            p.getInt("alert_ram_threshold", 85),
        )
    }

    fun saveRulesConfig(
        enabled: Boolean,
        pauseOnDiskLow: Boolean,
        pauseOnHeat: Boolean,
        cpuThreshold: Int,
        ramThreshold: Int,
        onResult: (Boolean, String) -> Unit,
    ) {
        val p = NasApplication.instance.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
        p.edit {
            putBoolean("alert_enabled", enabled)
            putBoolean("alert_pause_disk_low", pauseOnDiskLow)
            putBoolean("alert_pause_heat", pauseOnHeat)
            putInt("alert_cpu_threshold", cpuThreshold)
            putInt("alert_ram_threshold", ramThreshold)
        }
        onResult(true, "Đã lưu quy tắc cảnh báo")
    }

    // ═══ DEVICE APPROVAL (Phase 7d.3 — moved from WebDavViewModel facade) ═══

    var showApprovalDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var pendingIpAddress by androidx.compose.runtime.mutableStateOf("")
        internal set
    var pendingCountryCode by androidx.compose.runtime.mutableStateOf("VN")
    var approvalMessage by androidx.compose.runtime.mutableStateOf("")
        internal set

    fun approveDeviceIp(ip: String) {
        showApprovalDialog = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (base.isBlank()) return@launch
                val body = org.json.JSONObject().apply {
                    put("ip", ip); put("approved", true)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("$base/api/auth/approve_ip").post(body).build()
                WebDavManager.optimizedClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS, "Đã cấp quyền truy cập cho IP: $ip")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "approveDeviceIp failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR, "Cấp quyền IP thất bại: ${e.message}")
                }
            }
        }
    }

    fun denyDeviceIp(ip: String) {
        showApprovalDialog = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (base.isBlank()) return@launch
                val body = org.json.JSONObject().apply {
                    put("ip", ip); put("approved", false)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("$base/api/auth/approve_ip").post(body).build()
                WebDavManager.optimizedClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.WARNING, "Đã chặn quyền truy cập của IP: $ip")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("DeviceMgmt", "denyDeviceIp failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.ERROR, "Chặn IP thất bại: ${e.message}")
                }
            }
        }
    }
}