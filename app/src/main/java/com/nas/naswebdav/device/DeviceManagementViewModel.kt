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
    private val repository: WebDavRepository,
    private val injectedGlobalUi: com.nas.naswebdav.GlobalUiViewModel? = null,
) : ViewModel() {
    private val _globalUi: com.nas.naswebdav.GlobalUiViewModel by lazy {
        injectedGlobalUi ?: com.nas.naswebdav.GlobalUiViewModel()
    }
    private val globalUi get() = _globalUi

    // ═══ STATE — mirror của WebDavViewModel để UI không break ═══

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
    var showSmartDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var speedTestResult by androidx.compose.runtime.mutableStateOf(SpeedTestResult("--", "--"))
        internal set
    var isTestingSpeed by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var lastAutoSpeedTime by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set

    var isFanModeUpdating by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var storageFolderUsage by androidx.compose.runtime.mutableStateOf<List<StorageFolderUsage>>(emptyList())
        internal set
    var isFetchingStorageUsage by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var systemLogs by androidx.compose.runtime.mutableStateOf(listOf<SystemLog>())
        internal set
    var systemLogsList by androidx.compose.runtime.mutableStateOf<List<SystemLog>>(emptyList())
        internal set
    var showLogDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var isFetchingLogs by androidx.compose.runtime.mutableStateOf(false)
        internal set

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
        } catch (e: Exception) {
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
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
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
                    val o = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
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

    // ═══ TELEGRAM CONFIG (Phase 7d.3 — moved from WebDavViewModel facade) ═══

    fun loadTelegramConfig(onResult: (enabled: Boolean, chatId: String, hasToken: Boolean) -> Unit) {
        val p = NasApplication.instance.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
        val enabled = p.getBoolean("telegram_enabled", false)
        val chatId = p.getString("telegram_chat_id", "") ?: ""
        val token = p.getString("telegram_bot_token", "") ?: ""
        onResult(enabled, chatId, token.isNotBlank())
    }

    fun saveTelegramConfig(
        enabled: Boolean,
        botToken: String,
        chatId: String,
        test: Boolean,
        onResult: (Boolean, String) -> Unit,
    ) {
        val p = NasApplication.instance.applicationContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
        p.edit()
            .putBoolean("telegram_enabled", enabled)
            .putString("telegram_bot_token", botToken)
            .putString("telegram_chat_id", chatId)
            .apply()
        if (test) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val base = WebDavManager.currentBaseUrl.toApiBaseUrl()
                    if (base.isBlank()) { withContext(Dispatchers.Main) { onResult(false, "Chưa có địa chỉ NAS") }; return@launch }
                    val jsonBody = org.json.JSONObject().apply {
                        put("token", botToken)
                        put("chat_id", chatId)
                    }.toString()
                    val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                    val request = okhttp3.Request.Builder().url("$base/api/telegram/test").post(body).build()
                    val response = WebDavManager.optimizedClient.newCall(request).execute()
                    val bodyStr = response.body?.string() ?: ""
                    withContext(Dispatchers.Main) {
                        onResult(response.isSuccessful, if (response.isSuccessful) "Tin nhắn test đã gửi thành công!" else "Lỗi: $bodyStr")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onResult(false, "Lỗi gửi test: ${e.message}") }
                }
            }
        } else {
            onResult(true, "Đã lưu cấu hình Telegram")
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
        p.edit()
            .putBoolean("alert_enabled", enabled)
            .putBoolean("alert_pause_disk_low", pauseOnDiskLow)
            .putBoolean("alert_pause_heat", pauseOnHeat)
            .putInt("alert_cpu_threshold", cpuThreshold)
            .putInt("alert_ram_threshold", ramThreshold)
            .apply()
        onResult(true, "Đã lưu quy tắc cảnh báo")
    }

    // ═══ DEVICE APPROVAL (Phase 7d.3 — moved from WebDavViewModel facade) ═══

    var showApprovalDialog by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var pendingIpAddress by androidx.compose.runtime.mutableStateOf("")
        internal set
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
                WebDavManager.optimizedClient.newCall(request).execute().use { }
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.SUCCESS, "Đã cấp quyền truy cập cho IP: $ip")
                }
            } catch (_: Exception) {}
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
                WebDavManager.optimizedClient.newCall(request).execute().use { }
                withContext(Dispatchers.Main) {
                    globalUi.show(com.nas.naswebdav.ui.dialogs.DialogType.WARNING, "Đã chặn quyền truy cập của IP: $ip")
                }
            } catch (_: Exception) {}
        }
    }
}