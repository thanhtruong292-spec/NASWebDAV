package com.nas.naswebdav.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.SecurePrefsHelper
import com.nas.naswebdav.SmartNetworkManager
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.adaptiveTimeoutMs
import com.nas.naswebdav.buildLoginFailureMessage
import com.nas.naswebdav.isTailscaleUrl
import com.nas.naswebdav.recordLatency
import com.nas.naswebdav.safeUrlHost
import com.nas.naswebdav.shared.ConnectionStatus
import com.nas.naswebdav.shared.SharedStateHolder
import com.nas.naswebdav.toApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AuthSessionViewModel — quản lý login, smart-network switch, offline queue.
 *
 * State shared qua [SharedStateHolder]:
 *  - currentUrl, connectionStatus, isOnLan, errorMessage
 *
 * State nội bộ auth:
 *  - loginJob, lastErrorDetail
 */
class AuthSessionViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ─── INTERNAL STATE ─────────────────────────────────────────────────────

    private var loginJob: Job? = null

    // ─── SHARED STATE (đọc từ UI via SharedStateHolder) ──────────────────────

    /** Lấy currentUrl từ SharedStateHolder — UI đọc trực tiếp hoặc qua facade */
    val currentUrl: String get() = SharedStateHolder.currentUrl.value

    /** Lấy connectionStatus từ SharedStateHolder */
    val connectionStatus: String get() = SharedStateHolder.connectionStatus.value.message

    val isOnLan: Boolean get() = SharedStateHolder.isOnLan.value

    val errorMessage: String? get() = SharedStateHolder.errorMessage.value

    /** True while a connect() call is in flight — backed by Compose State so LoginScreen recomposes. */
    var isLoading: Boolean by androidx.compose.runtime.mutableStateOf(false)
        internal set

    // ─── AUTH FUNCTIONS ─────────────────────────────────────────────────────

    fun cancelLogin() {
        loginJob?.cancel()
        WebDavManager.cancelActiveCalls()
        loginJob = null
        isLoading = false
        SharedStateHolder.updateConnectionStatus(ConnectionStatus.Cancelled)
    }

    /**
     * Kết nối WebDAV — ping tuần tự từng URL (LAN → Tailscale).
     *
     * SP5 FIX: Channel là bounded buffer (capacity=1) để tránh suspend vĩnh viễn
     * nếu job gửi kết quả trước khi receiver gọi receive().
     * Mỗi URL gửi 1 tin nhắn, receiver nhận 1 tin → capacity=1 đủ.
     */
    fun connect(
        urlList: List<String>,
        user: String,
        pass: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        loginJob?.cancel()
        isLoading = true
        loginJob = viewModelScope.launch {
            var lastErrorDetail = "Không rõ"

            withContext(Dispatchers.Main) {
                SharedStateHolder.updateConnectionStatus(ConnectionStatus.CheckingLan)
                // Clear urlStack trong facade — connect() reset điều hướng
                SharedStateHolder.updateCurrentUrl("")
            }

            // S3 FIX: KHÔNG lưu credentials TRƯỚC khi ping — chỉ lưu SAU khi có URL thành công
            val errorDetails = mutableListOf<String>()
            var result2 = false

            result2 = withContext(Dispatchers.IO) {
                if (urlList.isEmpty()) {
                    lastErrorDetail = "Không có URL để kết nối"
                    return@withContext false
                }

                // SP5 FIX: Channel.UNLIMITED thay vì default (rendezvous 0) — tránh suspend vĩnh viễn
                val channel = Channel<Pair<Boolean, String>>(Channel.UNLIMITED)
                val jobs = urlList.map { activeUrl ->
                    launch(Dispatchers.IO) {
                        if (activeUrl.isBlank()) {
                            channel.send(Pair(false, ""))
                            return@launch
                        }

                        val safeUrl = if (activeUrl.isNotEmpty() && !activeUrl.endsWith("/")) "$activeUrl/" else activeUrl

                        withContext(Dispatchers.Main) {
                            SharedStateHolder.updateConnectionStatus(ConnectionStatus.Connecting(safeUrl))
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
                                    channel.send(Pair(false, "$activeUrl: WebDAV từ chối xác thực (HTTP ${response.code})"))
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("NAS_AUTH", "Lỗi kết nối $activeUrl: ${e.message}")
                            channel.send(Pair(false, "$activeUrl: ${e.message ?: "Mạng quá hạn"}"))
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
                        SharedStateHolder.updateCurrentUrl(successUrl)
                        SharedStateHolder.updateConnectionStatus(ConnectionStatus.Connected(successUrl))
                        SharedStateHolder.updateIsOnLan(!isTailscaleUrl(successUrl))
                    }

                    WebDavManager.connect(successUrl, user, pass)
                    SecurePrefsHelper.saveCredentials(NasApplication.instance, urlList, user, pass)
                    repository.addSystemLog("SUCCESS", "Network", "Truy cập WebDAV thành công qua User '$user' tại IP: $successUrl")

                    // Gọi authorize API phụ
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
                                android.util.Log.w("NAS_AUTH", "API Phụ Warning: ${e.message}")
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
                    SharedStateHolder.updateConnectionStatus(ConnectionStatus.Authenticated)
                    onSuccess()
                } else {
                    SharedStateHolder.updateConnectionStatus(ConnectionStatus.AuthFailed)
                    onError(lastErrorDetail)
                }
            }

            // Gọi refresh() từ facade WebDavViewModel — không gọi trực ti���p ở đây
            // vì refresh() thuộc FileBrowser domain (Phase 5)
            if (result2) {
                // Signal cho facade biết cần refresh — facade sẽ gọi refresh() sau
                _authSuccessSignal.value = true
            }
            loginJob = null
        }
    }

    // ─── SIGNAL cho facade ───────────────────────────────────────────────────
    // AuthSessionVM không gọi refresh() trực tiếp — facade WebDavViewModel sẽ observe signal này

    private val _authSuccessSignal = MutableStateFlow(false)
    val authSuccessSignal: StateFlow<Boolean> = _authSuccessSignal.asStateFlow()

    fun consumeAuthSuccessSignal() {
        _authSuccessSignal.value = false
    }

    // ─── SMART NETWORK ──────────────────────────────────────────────────────

    fun checkSmartNetwork(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                SmartNetworkManager.invalidateCache()
                val activeUrl = SmartNetworkManager.getActiveBaseUrl(context)
                if (activeUrl.isEmpty()) return@launch

                val onLan = !isTailscaleUrl(activeUrl)
                withContext(Dispatchers.Main) {
                    SharedStateHolder.updateIsOnLan(onLan)
                }

                val currentBase = WebDavManager.currentBaseUrl
                val safeActive = if (activeUrl.endsWith("/")) activeUrl else "$activeUrl/"

                if (safeActive != currentBase && currentBase.isNotEmpty()) {
                    val user = WebDavManager.currentUser
                    val pass = WebDavManager.currentPass
                    withContext(Dispatchers.Main) {
                        SharedStateHolder.updateConnectionStatus(
                            if (onLan) ConnectionStatus.SwitchedToLan(safeActive)
                            else ConnectionStatus.SwitchedToTailscale(safeActive)
                        )
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            WebDavManager.connect(safeActive, user, pass)
                            WebDavManager.initConnection()

                            val host = safeUrlHost(safeActive)
                            if (host.isNotEmpty()) {
                                val authHeader = okhttp3.Credentials.basic(user, pass)
                                val authRequest = okhttp3.Request.Builder()
                                    .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/auth/authorize")
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
                        SharedStateHolder.updateCurrentUrl(safeActive)
                        SharedStateHolder.updateConnectionStatus(
                            if (onLan) ConnectionStatus.OnLan(safeActive)
                            else ConnectionStatus.OnTailscale(safeActive)
                        )
                    }
                    repository.addSystemLog(
                        "INFO", "SmartSwitch",
                        "Chuyển mạng: ${if (onLan) "LAN" else "Tailscale"} ($safeActive)"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("SmartSwitch", "Lỗi kiểm tra mạng thông minh: ${e.message}")
            }
        }
    }

    // ─── OFFLINE QUEUE ──────────────────────────────────────────────────────

    fun enqueueOfflineAction(context: android.content.Context, actionType: String, sourcePath: String, destPath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as NasApplication).database
                val queueBaseUrl = WebDavManager.currentBaseUrl
                val normalizedSourcePath = sourcePath.toOfflineQueuePath(queueBaseUrl)
                val normalizedDestPath = destPath?.toOfflineQueuePath(queueBaseUrl)
                db.syncActionDao().insert(
                    com.nas.naswebdav.SyncAction(
                        actionType = actionType,
                        sourcePath = normalizedSourcePath,
                        destPath = normalizedDestPath
                    )
                )

                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val request = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    com.nas.naswebdav.OfflineSyncWorker.UNIQUE_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )

                withContext(Dispatchers.Main) {
                    SharedStateHolder.updateErrorMessage("Không có kết nối. Lệnh '$actionType' đã được đưa vào hàng đợi ngoại tuyến.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    SharedStateHolder.updateErrorMessage("Lỗi khi lưu hàng đợi ngoại tuyến: ${e.message}")
                }
            }
        }
    }

    // ─── HELPERS ────────────────────────────────────────────────────────────

    private fun String.toOfflineQueuePath(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return if (this.startsWith(normalized)) {
            this.removePrefix(normalized).ifEmpty { "/" }
        } else {
            this
        }
    }

    // ─── GUEST PASS (Phase 7d.3 — moved from facade) ────────────────────────

    var activeGuestPass by androidx.compose.runtime.mutableStateOf<com.nas.naswebdav.GuestPassInfo?>(null)
        internal set

    var isGuestPassLoading by androidx.compose.runtime.mutableStateOf(false)
        internal set

    var guestPassError by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set

    /**
     * Gọi POST /api/guest/create → NAS trả về thông tin user/host/FTP cho khách.
     */
    fun createGuestPass(durationMinutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isGuestPassLoading = true
                guestPassError = null
            }
            try {
                val baseUrl = WebDavManager.currentBaseUrl
                val host = safeUrlHost(baseUrl)
                val json = org.json.JSONObject().apply {
                    put("duration_minutes", durationMinutes)
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${baseUrl.toApiBaseUrl()}/api/guest/create")
                    .post(body)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val res = org.json.JSONObject(resp.body?.string() ?: "{}")
                        val expiresAtUnix = res.optLong("expires_at_unix", 0L)
                        val pass = com.nas.naswebdav.GuestPassInfo(
                            username  = res.optString("username", "guest"),
                            password  = res.optString("password", ""),
                            host      = res.optString("host", host),
                            ftpPort   = res.optInt("ftp_port", 21),
                            expiresAt = if (expiresAtUnix > 0) expiresAtUnix * 1000L
                                        else System.currentTimeMillis() + durationMinutes * 60_000L
                        )
                        withContext(Dispatchers.Main) { activeGuestPass = pass }
                        repository.addSystemLog(
                            "SUCCESS", "GuestPass",
                            "Đã cấp Guest FTP: user='${pass.username}', hết hạn sau $durationMinutes phút"
                        )
                    } else {
                        val errBody = resp.body?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            guestPassError = "NAS từ chối (${resp.code}): $errBody"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guestPassError = "Lỗi kết nối API: ${e.message}"
                }
                repository.addSystemLog("ERROR", "GuestPass", "Tạo Guest Pass lỗi: ${e.message?.take(80)}")
            } finally {
                withContext(Dispatchers.Main) { isGuestPassLoading = false }
            }
        }
    }

    /**
     * Gọi POST /api/guest/revoke → NAS xóa FTP user tạm thời.
     */
    fun revokeGuestPass(
        onSuccess: (String) -> Unit = {},
        onWarning: (String) -> Unit = {}
    ) {
        val pass = activeGuestPass ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isGuestPassLoading = true }
            try {
                val baseUrl = WebDavManager.currentBaseUrl
                val json = org.json.JSONObject().put("username", pass.username)
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url("${baseUrl.toApiBaseUrl()}/api/guest/revoke")
                    .post(body)
                    .build()
                NasApplication.instance.fastApiClient.newCall(request).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            activeGuestPass = null
                            guestPassError = null
                            onSuccess("Đã thu hồi Guest Pass của '${pass.username}' thành công!")
                            repository.addSystemLog("INFO", "GuestPass", "Đã thu hồi Guest FTP user '${pass.username}'")
                        } else {
                            guestPassError = "Thu hồi thất bại: HTTP ${resp.code}"
                            onWarning("Thu hồi thất bại (HTTP ${resp.code}). Pass được giữ lại để thử lại.")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guestPassError = "Lỗi thu hồi: ${e.message}"
                    onWarning("Lỗi mạng khi thu hồi Guest Pass. Pass được giữ lại để thử lại.")
                }
            } finally {
                withContext(Dispatchers.Main) { isGuestPassLoading = false }
            }
        }
    }
}
