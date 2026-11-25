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
import com.nas.naswebdav.prioritizeConnectionUrls
import com.nas.naswebdav.recordLatency
import com.nas.naswebdav.safeUrlHost
import com.nas.naswebdav.shared.ConnectionStatus
import com.nas.naswebdav.shared.SharedStateHolder
import com.nas.naswebdav.toApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
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
    private val smartNetworkCheckMutex = Mutex()

    // ─── SHARED STATE (đọc từ UI via SharedStateHolder) ──────────────────────

    /** Lấy currentUrl từ SharedStateHolder — UI đọc trực tiếp hoặc qua facade */
    val currentUrl: String get() = SharedStateHolder.currentUrl.value

    /** Lấy connectionStatus từ SharedStateHolder */
    val connectionStatus: String get() = SharedStateHolder.connectionStatus.value.message

    val isOnLan: Boolean get() = SharedStateHolder.isOnLan.value

    val errorMessage: String? get() = SharedStateHolder.errorMessage.value

    /** True while a connect() call is in flight — backed by Compose State so LoginScreen recomposes. */
    var isLoading: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set

    // ─── AUTH FUNCTIONS ─────────────────────────────────────────────────────

    fun cancelLogin() {
        loginJob?.cancel()
        WebDavManager.cancelActiveCalls(WebDavManager.CALL_GROUP_LOGIN)
        loginJob = null
        isLoading = false
        SharedStateHolder.updateConnectionStatus(ConnectionStatus.Cancelled)
    }

    /** Kết nối WebDAV, ưu tiên LAN và chỉ dùng Tailscale làm đường dự phòng. */
    fun connect(
        urlList: List<String>,
        user: String,
        pass: String,
        onSuccess: () -> Unit = {},
        onFailure: ((String, Boolean) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        loginJob?.cancel()
        isLoading = true
        loginJob = viewModelScope.launch {
            var lastErrorDetail = "Không rõ"
            var rejectedCredentials = false

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

                var successUrl = ""
                // Do not race candidate probes: their completion order is not route
                // priority and used to let Tailscale overwrite a healthy LAN route.
                val candidates = prioritizeConnectionUrls(urlList, SecurePrefsHelper.getLastVerifiedUrl(NasApplication.instance))
                for (activeUrl in candidates) {
                    val safeUrl = if (activeUrl.endsWith("/")) activeUrl else "$activeUrl/"
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
                            .tag(String::class.java, "login")
                            .url("${safeUrl.toApiBaseUrl()}/api/ping")
                            .head()
                            .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                            .build()
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        pingClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                recordLatency(safeUrl, android.os.SystemClock.elapsedRealtime() - t0)
                                successUrl = safeUrl
                            } else {
                                if (response.code == 401) rejectedCredentials = true
                                errorDetails.add("$activeUrl: WebDAV từ chối xác thực (HTTP ${response.code})")
                            }
                        }
                        if (successUrl.isNotEmpty()) break
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        android.util.Log.d("NAS_AUTH", "Probe thất bại $activeUrl: ${e.message}")
                        errorDetails.add("$activeUrl: ${e.message ?: "Mạng quá hạn"}")
                    }
                }

                if (successUrl.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        SharedStateHolder.updateCurrentUrl(successUrl)
                        SharedStateHolder.updateConnectionStatus(ConnectionStatus.Connected(successUrl))
                        SharedStateHolder.updateIsOnLan(!isTailscaleUrl(successUrl))
                    }

                    WebDavManager.connect(successUrl, user, pass)
                    SecurePrefsHelper.saveCredentials(NasApplication.instance, urlList, user, pass)
                    SecurePrefsHelper.rememberVerifiedUrl(NasApplication.instance, successUrl)
                    val safeHostLabel = safeUrlHost(successUrl).ifEmpty { "NAS" }
                    repository.addSystemLog("SUCCESS", "Network", "Truy cập WebDAV thành công qua User '$user' tại $safeHostLabel")

                    // Gọi authorize API phụ
                    val parsedUrl = try { java.net.URL(successUrl) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                    val host = parsedUrl?.host
                    if (!host.isNullOrEmpty()) {
                        NasApplication.applicationScope.launch(Dispatchers.IO) {
                            try {
                                val authHeader = WebDavManager.AuthState(user = user, pass = pass).authHeader
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
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                android.util.Log.w("NAS_AUTH", "API Phụ Warning: ${e.message}")
                            }
                        }
                    }
                    true
                } else {
                    lastErrorDetail = buildLoginFailureMessage(urlList, errorDetails)
                    // Chi ghi ERROR mot lan khi toan bo URL that bai — tranh spam
                    // 5x Log.e cho moi probe (ping LAN + Tailscale deu mat 100%).
                    android.util.Log.e("NAS_AUTH", "Tat ca ${urlList.size} URL that bai: $lastErrorDetail")
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
                    if (onFailure != null) onFailure(lastErrorDetail, rejectedCredentials)
                    else onError(lastErrorDetail)
                }
            }

            // AuthSessionVM không gọi refresh() — FileBrowserVM owns that,
            // triggered via onSuccess() callback ở caller (xem LoginScreen).
            loginJob = null
        }
    }

    // ─── AUTH SUCCESS SIGNAL REMOVED ─────────────────────────────────────
    // _authSuccessSignal / authSuccessSignal / consumeAuthSuccessSignal đã bị xóa:
    // grep authSuccessSignal trong toàn project chỉ match chính file này — không có
    // consumer nào. onSuccess() callback của connect() đã đủ cho orchestrator.

    // ─── SMART NETWORK ──────────────────────────────────────────────────────

    fun checkSmartNetwork(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureSmartNetwork(context)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("SmartSwitch", "Lỗi kiểm tra mạng thông minh: ${e.message}")
            }
        }
    }

    /**
     * CODEX 09/10 (rusk URL cu): ban cu `checkSmartNetwork` la fire-and-forget —
     * MainMenu goi xong fetch ngay voi base URL cu (LAN chet) nen storage/SMART
     * trang, sau khi chuyen sang Tailscale khong ai fetch lai. Ham suspend nay
     * lam cung logic nhung AWAIT duoc: caller (cold-open / pull-refresh) doi
     * xong moi fetch, dam bao dung URL moi. Tra ve safeActive (co dau "/").
     */
    suspend fun ensureSmartNetwork(context: android.content.Context): String {
        val session=WebDavManager.captureAuthSession()
        return smartNetworkCheckMutex.withLock {ensureSmartNetworkLocked(context,session)}
    }

    private suspend fun publishIfSessionCurrent(session: WebDavManager.AuthState, update: () -> Unit): Boolean =
        withContext(Dispatchers.Main.immediate) {
            synchronized(WebDavManager) {
                coroutineContext.ensureActive()
                if(WebDavManager.captureAuthSession() !== session) false else {update();true}
            }
        }

    private suspend fun ensureSmartNetworkLocked(context: android.content.Context, initialSession: WebDavManager.AuthState): String {
        var session=initialSession
        if(WebDavManager.captureAuthSession() !== session) return WebDavManager.currentBaseUrl
        val previousStatus=SharedStateHolder.connectionStatus.value
        val previousUrl=SharedStateHolder.currentUrl.value
        val previousOnLan=SharedStateHolder.isOnLan.value
        try {
            if(session.baseUrl.isBlank()) publishIfSessionCurrent(session) {
                SharedStateHolder.updateConnectionStatus(ConnectionStatus.CheckingLan)
            }
            val activeUrl = withContext(Dispatchers.IO) {
                SmartNetworkManager.getVerifiedBaseUrl(context)
            }
            if(WebDavManager.captureAuthSession() !== session) return WebDavManager.currentBaseUrl
        if (activeUrl.isEmpty()) {
            publishIfSessionCurrent(session) {SharedStateHolder.updateConnectionStatus(ConnectionStatus.NetworkError)}
            return WebDavManager.currentBaseUrl
        }

        val onLan = !isTailscaleUrl(activeUrl)

        val currentBase = session.baseUrl
        val safeActive = if (activeUrl.endsWith("/")) activeUrl else "$activeUrl/"

        var user = session.user
        var pass = session.pass
        if (user.isEmpty() || pass.isEmpty()) {
            val appCtx = context.applicationContext
            user = runCatching { SecurePrefsHelper.getUser(appCtx) }.getOrDefault("")
            pass = runCatching { SecurePrefsHelper.getPass(appCtx) }.getOrDefault("")
        }

        val needsConnect = currentBase.isEmpty() || safeActive != currentBase
        if (needsConnect && (user.isEmpty() || pass.isEmpty())) {
            publishIfSessionCurrent(session) {SharedStateHolder.updateConnectionStatus(ConnectionStatus.AuthFailed)}
            return currentBase
        }
        if (needsConnect) {
            val switched=publishIfSessionCurrent(session) {
                WebDavManager.connect(safeActive,user,pass)
                session=WebDavManager.captureAuthSession()
                SharedStateHolder.updateCurrentUrl(safeActive)
                SharedStateHolder.updateIsOnLan(onLan)
                SharedStateHolder.updateConnectionStatus(
                    if (onLan) ConnectionStatus.SwitchedToLan(safeActive)
                    else ConnectionStatus.SwitchedToTailscale(safeActive)
                )
            }
            if(!switched) return WebDavManager.currentBaseUrl
            withContext(Dispatchers.IO) {
                if(WebDavManager.captureAuthSession() !== session) return@withContext
                try {
                    val host = safeUrlHost(safeActive)
                    if (host.isNotEmpty()) {
                        val authRequest = okhttp3.Request.Builder()
                            .url("${session.baseUrl.toApiBaseUrl()}/api/auth/authorize")
                            .tag(WebDavManager.AuthState::class.java,session)
                            .header("Authorization", session.authHeader)
                            .post(ByteArray(0).toRequestBody(null, 0, 0))
                            .build()
                        try {
                            NasApplication.instance.fastApiClient.newCall(authRequest).execute().use { }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            }
            val safeHostLabel = safeUrlHost(safeActive).ifEmpty { "NAS" }
            repository.addSystemLog(
                "INFO", "SmartSwitch",
                "Chuyển mạng: ${if (onLan) "LAN" else "Tailscale"} ($safeHostLabel)"
            )
        }
        val published=publishIfSessionCurrent(session) {
            SharedStateHolder.updateIsOnLan(onLan)
            SharedStateHolder.updateCurrentUrl(safeActive)
            SharedStateHolder.updateConnectionStatus(
            if (onLan) ConnectionStatus.OnLan(safeActive)
            else ConnectionStatus.OnTailscale(safeActive)
            )
        }
        return if(published) safeActive else WebDavManager.currentBaseUrl
        } catch (error: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                publishIfSessionCurrent(session) {
                    val status=SharedStateHolder.connectionStatus.value
                    if(status == ConnectionStatus.CheckingLan || status is ConnectionStatus.SwitchedToLan || status is ConnectionStatus.SwitchedToTailscale) {
                        SharedStateHolder.updateCurrentUrl(session.baseUrl.ifBlank {previousUrl})
                        SharedStateHolder.updateIsOnLan(if(session.baseUrl.isNotBlank()) !isTailscaleUrl(session.baseUrl) else previousOnLan)
                        SharedStateHolder.updateConnectionStatus(
                            if(session.baseUrl.isNotBlank()) {
                                if(isTailscaleUrl(session.baseUrl)) ConnectionStatus.OnTailscale(session.baseUrl)
                                else ConnectionStatus.OnLan(session.baseUrl)
                            } else if(previousStatus == ConnectionStatus.CheckingLan) ConnectionStatus.Idle else previousStatus
                        )
                    }
                }
            }
            throw error
        } catch(error: Exception) {
            publishIfSessionCurrent(session) {SharedStateHolder.updateConnectionStatus(ConnectionStatus.NetworkError)}
            throw error
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
                val queueHost = runCatching { java.net.URL(queueBaseUrl).host ?: "" }.getOrDefault("")
                val queueUser = com.nas.naswebdav.SecurePrefsHelper.getUser(context.applicationContext)
                // FIX-REVIEW-24/09-#6: luu endpoint DAY DU (port + root).
                val queuePort = runCatching { java.net.URL(queueBaseUrl).port.takeIf { it > 0 } ?: java.net.URL(queueBaseUrl).defaultPort }.getOrDefault(-1)
                val queueRoot = runCatching {
                    val p = java.net.URL(queueBaseUrl).path.trimEnd('/')
                    if (p.isEmpty()) "/" else p
                }.getOrDefault("/")
                db.syncActionDao().insert(
                    com.nas.naswebdav.SyncAction(
                        actionType = actionType,
                        sourcePath = normalizedSourcePath,
                        destPath = normalizedDestPath,
                        nasHost = queueHost,
                        nasUser = queueUser,
                        nasPort = queuePort,
                        nasRoot = queueRoot
                    )
                )

                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
                val request = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15L, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    com.nas.naswebdav.OfflineSyncWorker.UNIQUE_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )

                withContext(Dispatchers.Main) {
                    SharedStateHolder.updateErrorMessage("Không có kết nối. Lệnh '$actionType' đã được đưa vào hàng đợi ngoại tuyến.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
        private set

    var isGuestPassLoading by androidx.compose.runtime.mutableStateOf(false)
        private set

    var guestPassError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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
