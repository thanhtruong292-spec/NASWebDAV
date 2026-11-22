package com.nas.naswebdav.livestream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.AppConfig
import com.nas.naswebdav.LivestreamQueueEntry
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.LivestreamMonitorWorker
import com.nas.naswebdav.LivestreamJob
import com.nas.naswebdav.TikTokLiveWatchUser
import com.nas.naswebdav.SocialDownloadItem
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.toApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * LivestreamViewModel — Phase 3 của VM Split.
 *
 * Quản lý: Livestream recording (jobs, status), TikTok watch daemon, Social extractor,
 *          StreamPipe (CDN → NAS).
 *
 * ZERO cross-deps với FileBrowser — pure isolated domain.
 *
 * Phase 3 skeleton: state declarations + placeholder methods.
 * Function bodies sẽ được move từ facade trong Phase 3b.
 */
class LivestreamViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ LIVESTREAM RECORDING ═══

    var livestreamMessage by androidx.compose.runtime.mutableStateOf("")
        private set
    var isStartingLivestream by androidx.compose.runtime.mutableStateOf(false)
        private set
    // SPEC_LIVESTREAM_LIMITS §4.1: cap/active tu server (GET /api/admin/limits),
    // fallback 2 khi server cu. Pre-flight dung 2 bien nay de chan som.
    var serverLivestreamCap by androidx.compose.runtime.mutableStateOf(2)
        private set
    var serverLivestreamActive by androidx.compose.runtime.mutableStateOf(0)
        private set
    // SPEC §4.2: ma loi co cau truc thay prefix tieng Viet gion.
    var livestreamErrorCode by androidx.compose.runtime.mutableStateOf("")
        private set
    var livestreamErrorRetryAfterS by androidx.compose.runtime.mutableStateOf(0L)
        private set
    var isLivestreamError by androidx.compose.runtime.mutableStateOf(false)
        private set
    // SPEC §4.1: hang doi server-side (client chi hien thi, khong giu local).
    var livestreamQueue by androidx.compose.runtime.mutableStateOf<List<LivestreamQueueEntry>>(emptyList())
        private set
    var livestreamQueueCount by androidx.compose.runtime.mutableStateOf(0)
        private set
    var livestreamQueueCap by androidx.compose.runtime.mutableStateOf(20)
        private set
    private var lastLivestreamServerSyncAt = 0L
    private var lastLivestreamServerRecordingIds: Set<String> = emptySet()

    /** Danh sách các stream đang ghi — dùng mutableStateListOf cho Compose */
    var activeLivestreams = androidx.compose.runtime.mutableStateListOf<LivestreamJob>()
        private set

    // ═══ STREAM PIPE (CDN → NAS) ═══

    var isStreamPiping by androidx.compose.runtime.mutableStateOf(false)
        private set
    var streamPipeStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    var streamPipeProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var streamPipeSpeedStr by androidx.compose.runtime.mutableStateOf("-- MB/s")
        private set
    var streamPipeEtaStr by androidx.compose.runtime.mutableStateOf("--")
        private set

    // ═══ SOCIAL EXTRACTOR ═══

    var socialExtractStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    var isSocialExtracting by androidx.compose.runtime.mutableStateOf(false)
        private set
    var socialDownloadHistory by androidx.compose.runtime.mutableStateOf<List<SocialDownloadItem>>(emptyList())
        private set

    // ═══ TIKTOK LIVE WATCH ═══

    var tiktokLiveWatchUsers by androidx.compose.runtime.mutableStateOf<List<TikTokLiveWatchUser>>(emptyList())
        private set

    var tiktokWatchDaemonRunning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var tiktokWatchDaemonLastTick by androidx.compose.runtime.mutableStateOf("")
        private set
    var tiktokWatchDaemonSummary by androidx.compose.runtime.mutableStateOf("")
        private set
    var tiktokLiveWatchError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var tiktokExcludeEnabled by androidx.compose.runtime.mutableStateOf(false)
        private set
    var tiktokExcludeStart by androidx.compose.runtime.mutableStateOf("23:00")
        private set
    var tiktokExcludeEnd by androidx.compose.runtime.mutableStateOf("07:00")
        private set
    var isLoadingTikTokWatch by androidx.compose.runtime.mutableStateOf(false)
        private set
    var tiktokCookiesStatus by androidx.compose.runtime.mutableStateOf("unknown")
        private set
    var tiktokCookiesMessage by androidx.compose.runtime.mutableStateOf("")
        private set
    var tiktokCookiesExpiryStr by androidx.compose.runtime.mutableStateOf("")
        private set
    var tiktokCookiesDaysLeft by androidx.compose.runtime.mutableStateOf(-1.0)
        private set



    fun fetchLivestreamStatusOnly(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBaseUrl = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (apiBaseUrl.isBlank()) return@launch
                val reqBuilder = okhttp3.Request.Builder().url("$apiBaseUrl${AppConfig.ApiEndpoints.LIVESTREAM_STATUS}")
                val user = com.nas.naswebdav.SecurePrefsHelper.getUser(context)
                val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(context)
                if (user.isNotEmpty() && pass.isNotEmpty()) reqBuilder.header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                NasApplication.instance.fastApiClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = org.json.JSONObject(response.body?.string() ?: "{}")
                    val jobsArray = json.optJSONArray("jobs") ?: org.json.JSONArray()
                    val newJobs = mutableListOf<LivestreamJob>()
                    val serverRecordingIds = mutableSetOf<String>()
                    for (i in 0 until jobsArray.length()) {
                        val obj = jobsArray.getJSONObject(i)
                        val status = obj.optString("status", "")
                        val jobId = obj.optString("job_id", "")
                        // FIX-REVIEW-193369e-#13: giu stopping/finalizing tren UI
                        // (flush/finalize), khong chi recording. Chi terminal
                        // (finished/error/stopped/cancelled) moi roi khoi list.
                        if ((status == "recording" || status == "stopping" || status == "finalizing" || status == "starting") && jobId.isNotEmpty()) {
                            serverRecordingIds.add(jobId)
                            newJobs.add(LivestreamJob(jobId = jobId, platform = obj.optString("platform", ""), status = status, watchUsername = obj.optString("watch_username", ""), durationSeconds = obj.optLong("duration_seconds", 0L), startedTs = obj.optLong("started_ts", 0L), fileSize = obj.optString("file_size", "0 B"), duration = obj.optString("duration_display", "0h00m00s"), speed = obj.optString("avg_speed", "—"), outputFile = obj.optString("output_file", "")))
                        }
                    }
                    lastLivestreamServerSyncAt = System.currentTimeMillis()
                    if (serverRecordingIds.isEmpty()) {
                        LivestreamMonitorWorker.cancelAll(context)
                    } else {
                        val host = try { java.net.URL(WebDavManager.currentBaseUrl).host } catch (_: Exception) { "" }
                        if (host.isNotBlank()) {
                            for (job in newJobs) {
                                if (job.status == "recording" || job.status == "starting") {
                                    LivestreamMonitorWorker.enqueue(context, job.jobId, host, job.platform)
                                }
                            }
                        }
                    }
                    val displayJobs = dedupeLivestreamJobsForDisplay(newJobs)
                    lastLivestreamServerRecordingIds = displayJobs.map { it.jobId }.toSet()
                    withContext(Dispatchers.Main) {
                        serverLivestreamActive = displayJobs.size
                        if (activeLivestreams.size != displayJobs.size || activeLivestreams.toList() != displayJobs) {
                            activeLivestreams.clear(); activeLivestreams.addAll(displayJobs)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("Livestream", "fetchLivestreamStatusOnly: ${e.message}") }
        }
    }



    fun clearLivestreamMessage() {
        livestreamMessage = ""
        isLivestreamError = false
        livestreamErrorCode = ""
        livestreamErrorRetryAfterS = 0L
    }

    private fun setLivestreamStructuredError(code: String, message: String, retryAfterS: Long = 0L) {
        livestreamErrorCode = code
        livestreamErrorRetryAfterS = retryAfterS
        isLivestreamError = true
        livestreamMessage = message
    }

    // SPEC_LIVESTREAM_LIMITS §4.1: refresh cap dong tu GET /api/admin/limits.
    // Server cu (404) -> giu fallback cap=2, khong crash.
    fun fetchAdminLimits() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (apiBase.isBlank()) return@launch
                val req = okhttp3.Request.Builder()
                    .url("$apiBase${AppConfig.ApiEndpoints.ADMIN_LIMITS}").get()
                    .let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    val cap = json.optInt("livestream_max_concurrent", -1)
                    withContext(Dispatchers.Main) {
                        if (cap in 1..8) serverLivestreamCap = cap
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "fetchAdminLimits: ${e.message}")
            }
        }
    }

    fun fetchLivestreamQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                if (apiBase.isBlank()) return@launch
                val req = okhttp3.Request.Builder()
                    .url("$apiBase${AppConfig.ApiEndpoints.LIVESTREAM_QUEUE}").get()
                    .let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    val arr = json.optJSONArray("queue")
                    val items = mutableListOf<LivestreamQueueEntry>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            items.add(
                                LivestreamQueueEntry(
                                    queueId = o.optString("queue_id", ""),
                                    url = o.optString("url", ""),
                                    quality = o.optString("quality", "best"),
                                    watchUsername = o.optString("watch_username", ""),
                                    position = o.optInt("position", i + 1),
                                    status = o.optString("status", "queued"),
                                    lastError = o.optString("last_error", "")
                                )
                            )
                        }
                    }
                    val count = json.optInt("count", items.size)
                    val capQ = json.optInt("cap", 20)
                    withContext(Dispatchers.Main) {
                        livestreamQueue = items
                        livestreamQueueCount = count
                        livestreamQueueCap = if (capQ > 0) capQ else 20
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "fetchQueue: ${e.message}")
            }
        }
    }

    fun enqueueLivestreamQueue(url: String, quality: String = "best", watchUsername: String = "") {
        isStartingLivestream = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val bodyJson = org.json.JSONObject().apply {
                    put("url", url)
                    put("quality", quality)
                    if (watchUsername.isNotBlank()) put("watch_username", watchUsername)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$apiBase${AppConfig.ApiEndpoints.LIVESTREAM_QUEUE}").post(bodyJson)
                    .let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val respBody = try { resp.body?.string() ?: "{}" } catch (_: Exception) { "{}" }
                    val json = runCatching { org.json.JSONObject(respBody) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            val pos = json?.optInt("position", -1) ?: -1
                            isLivestreamError = false
                            livestreamErrorCode = ""
                            livestreamMessage = if (pos > 0) "✅ Đã cho vào hàng đợi (vị trí $pos)" else "✅ Đã cho vào hàng đợi"
                        } else {
                            val code = json?.optString("code", "") ?: ""
                            val msg = json?.optString("error", "")?.ifBlank { "Lỗi hàng đợi: HTTP ${resp.code}" } ?: "Lỗi hàng đợi"
                            setLivestreamStructuredError(code, msg)
                        }
                    }
                }
                fetchLivestreamQueue()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) { setLivestreamStructuredError("", "Lỗi hàng đợi: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { isStartingLivestream = false }
            }
        }
    }

    fun cancelLivestreamQueue(queueId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("queue_id", queueId).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder()
                    .url("$apiBase${AppConfig.ApiEndpoints.LIVESTREAM_QUEUE}?queue_id=$queueId")
                    .delete(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
                fetchLivestreamQueue()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "cancelQueue: ${e.message}")
            }
        }
    }

    // SPEC §4.3: 1 diem refresh — vua enqueue worker vua update list + cap + queue.
    // Goi khi mo dialog / app foreground (thay cho sync rieng le cu).
    fun refreshLivestreamState(context: android.content.Context) {
        fetchLivestreamStatusOnly(context)
        fetchAdminLimits()
        fetchLivestreamQueue()
    }

    fun fetchTikTokLiveWatch(context: android.content.Context) { fetchTikTokLiveWatch() }

    fun fetchTikTokLiveWatch() {
        isLoadingTikTokWatch = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.TIKTOK_LIVE_WATCH}").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        tiktokWatchDaemonRunning = json.optBoolean("daemon_running", false)
                        tiktokWatchDaemonLastTick = json.optString("last_tick", "")
                        tiktokWatchDaemonSummary = json.optString("summary", "")
                        tiktokExcludeEnabled = json.optBoolean("exclude_enabled", false)
                        // FIX-AUDIT-L7: hydrate ca gio bat dau/ket thuc (truoc day
                        // fetch thieu -> mo lai man hinh hien gia tri cu du backend
                        // da luu moi).
                        tiktokExcludeStart = json.optString("exclude_start", "23:00")
                        tiktokExcludeEnd = json.optString("exclude_end", "07:00")
                        tiktokLiveWatchError = json.optString("error").ifEmpty { null }
                        tiktokCookiesStatus = json.optString("cookies_status", "unknown")
                        tiktokCookiesMessage = json.optString("cookies_message", "")
                        // Cookies expiry alert: backend tra ngay het han + so ngay con lai
                        // de UI hien banner truoc 3 ngay, khong doi chet moi biet.
                        tiktokCookiesExpiryStr = json.optString("cookies_expiry_str", "")
                        tiktokCookiesDaysLeft = json.optDouble("cookies_days_left", -1.0)
                        com.nas.naswebdav.CookiesExpiryNotifier.checkAndNotify(
                            NasApplication.instance,
                            tiktokCookiesStatus,
                            tiktokCookiesMessage,
                            tiktokCookiesExpiryStr,
                            tiktokCookiesDaysLeft
                        )
                        val usersArr = json.optJSONArray("users")
                        tiktokLiveWatchUsers = if (usersArr != null) {
                            (0 until usersArr.length()).mapNotNull { idx ->
                                val u = usersArr.optJSONObject(idx) ?: return@mapNotNull null
                                TikTokLiveWatchUser(
                                    username = u.optString("username", ""),
                                    status = u.optString("status", ""),
                                    lastCheck = u.optString("last_check", ""),
                                    lastLive = u.optString("last_live", ""),
                                    lastError = u.optString("last_error", ""),
                                    jobId = u.optString("job_id", "")
                                )
                            }
                        } else emptyList()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "fetchTikTokLiveWatch: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isLoadingTikTokWatch = false }
            }
        }
    }

    private fun throwOnUnsuccessfulResponse(resp: okhttp3.Response) {
        if (!resp.isSuccessful) {
            val structured = WebDavManager.extractApiErrorStructured(resp)
            val msg = structured?.message?.ifBlank { null }
                ?: "Lỗi NAS: HTTP ${resp.code}"
            throw LivestreamApiException(
                msg,
                structured?.code ?: "",
                structured?.cap ?: -1,
                structured?.active ?: -1,
                structured?.retryAfterS ?: 0L
            )
        }
    }

    /** SPEC §4.2: exception giu code/cap/active de dialog render CTA. */
    class LivestreamApiException(
        message: String,
        val code: String = "",
        val cap: Int = -1,
        val active: Int = -1,
        val retryAfterS: Long = 0L
    ) : Exception(message)

    fun addTikTokLiveWatchUser(context: android.content.Context, username: String) { addTikTokLiveWatchUser(username) }

    fun addTikTokLiveWatchUser(username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("username", username).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.TIKTOK_LIVE_WATCH}/add").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    throwOnUnsuccessfulResponse(resp)
                }
                fetchTikTokLiveWatch()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "addTikTokLiveWatchUser: ${e.message}")
            }
        }
    }

    fun removeTikTokLiveWatchUser(context: android.content.Context, username: String) { removeTikTokLiveWatchUser(username) }

    fun removeTikTokLiveWatchUser(username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("username", username).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.TIKTOK_LIVE_WATCH}/remove").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    throwOnUnsuccessfulResponse(resp)
                }
                fetchTikTokLiveWatch()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "removeTikTokLiveWatchUser: ${e.message}")
            }
        }
    }

    fun updateTikTokLiveWatchSettings(context: android.content.Context, enabled: Boolean) { updateTikTokLiveWatchSettings(enabled, tiktokExcludeStart, tiktokExcludeEnd) }

    fun updateTikTokLiveWatchSettings(context: android.content.Context, enabled: Boolean, start: String, end: String) { updateTikTokLiveWatchSettings(enabled, start, end) }

    fun updateTikTokLiveWatchSettings(enabled: Boolean, start: String, end: String) {
        tiktokExcludeEnabled = enabled
        tiktokExcludeStart = start
        tiktokExcludeEnd = end
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject()
                    .put("enabled", enabled).put("start", start).put("end", end).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.TIKTOK_LIVE_WATCH}/settings").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    throwOnUnsuccessfulResponse(resp)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "updateTikTokLiveWatchSettings: ${e.message}")
            }
        }
    }

    fun startLivestreamRecord(url: String, quality: String = "best", referer: String = "", userAgent: String = "") {
        // SPEC §4.1 pre-flight: chan som khi active >= cap, khong POST thua.
        // Van cho phep [Cho vao hang doi] tu dialog (qua enqueueLivestreamQueue).
        val knownActive = serverLivestreamActive.takeIf { it > 0 } ?: activeLivestreams.size
        if (knownActive >= serverLivestreamCap && serverLivestreamCap > 0) {
            setLivestreamStructuredError(
                "MAX_STREAMS",
                "NAS đang bận ($knownActive/$serverLivestreamCap) — cho vào hàng đợi để tự ghi khi có slot.",
                60L
            )
            fetchLivestreamQueue()
            return
        }
        isStartingLivestream = true
        isLivestreamError = false
        livestreamErrorCode = ""
        livestreamMessage = "Đang phân tích liên kết & kết nối..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tiktokUsername: String = if (url.contains("tiktok", ignoreCase = true)) {
                    Regex("tiktok\\.com/@([\\w.\\-]+)").find(url)?.groupValues?.get(1) ?: ""
                } else ""
                val body = org.json.JSONObject().apply {
                    put("url", url)
                    put("quality", quality)
                    put("referer", referer)
                    put("user_agent", userAgent)
                    if (tiktokUsername.isNotBlank()) put("watch_username", tiktokUsername)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.LIVESTREAM_RECORD}").post(body).let(WebDavManager::tagCurrentAuth).build()
                var newJobId = ""
                var detectedPlatform = "livestream"
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    throwOnUnsuccessfulResponse(resp)
                    val respBody = resp.body?.string() ?: "{}"
                    val respJson = org.json.JSONObject(respBody)
                    newJobId = respJson.optString("job_id", "")
                    detectedPlatform = respJson.optString("platform", "livestream")
                }
                if (newJobId.isNotBlank()) {
                    val host = try { java.net.URL(WebDavManager.currentBaseUrl).host } catch (_: Exception) { "" }
                    if (host.isNotBlank()) {
                        LivestreamMonitorWorker.enqueue(NasApplication.instance.applicationContext, newJobId, host, detectedPlatform)
                    }
                }
                withContext(Dispatchers.Main) {
                    // Da chuyen sang HTML5 Dashboard (khong con ExclusivePanelState Compose)
                    livestreamMessage = "✅ Đã gửi lệnh ghi"
                }
                fetchLivestreamStatusOnly(NasApplication.instance.applicationContext)
                fetchAdminLimits()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                val apiEx = e as? LivestreamApiException
                withContext(Dispatchers.Main) {
                    if (apiEx != null) {
                        if (apiEx.cap in 1..8) serverLivestreamCap = apiEx.cap
                        if (apiEx.active >= 0) serverLivestreamActive = apiEx.active
                        setLivestreamStructuredError(
                            apiEx.code.ifBlank { "API_ERROR" },
                            apiEx.message ?: "Lỗi: ${e.message}",
                            apiEx.retryAfterS
                        )
                        if (apiEx.code == "MAX_STREAMS") fetchLivestreamQueue()
                    } else {
                        setLivestreamStructuredError("", "Lỗi: ${e.message}")
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { isStartingLivestream = false }
            }
        }
    }

    fun stopLivestreamRecord(jobId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = org.json.JSONObject().put("job_id", jobId).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.LIVESTREAM_STOP}").post(body).let(WebDavManager::tagCurrentAuth).build()
                // FIX-REVIEW-193369e-#13: doc body response, giu job toi terminal.
                // Ban cu bo body, xoa job va bao "da dung" du backend tra stopping.
                // Quy tac: stopping/finalizing -> doi polling xac nhan terminal;
                // phan biet "da gui lenh" voi "hoan tat".
                var respStatus = ""
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    throwOnUnsuccessfulResponse(resp)
                    val respBody = resp.body?.string() ?: "{}"
                    respStatus = org.json.JSONObject(respBody).optString("status", "")
                }
                withContext(Dispatchers.Main) {
                    if (respStatus == "stopping" || respStatus == "finalizing") {
                        livestreamMessage = "⏳ Đã gửi lệnh dừng — đang flush/finalize, chờ hoàn tất..."
                    } else {
                        activeLivestreams.removeAll { it.jobId == jobId }
                        livestreamMessage = "⏹ Đã dừng ghi hình"
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) { livestreamMessage = "Lỗi dừng ghi: ${e.message}" }
            }
        }
    }

    fun syncLivestreamStateWithServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.LIVESTREAM_STATUS}").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    val jobsArr = json.optJSONArray("jobs")
                    if (jobsArr != null) {
                        val ids = mutableSetOf<String>()
                        val host = try { java.net.URL(WebDavManager.currentBaseUrl).host } catch (_: Exception) { "" }
                        val ctx = NasApplication.instance.applicationContext
                        for (i in 0 until jobsArr.length()) {
                            val j = jobsArr.optJSONObject(i) ?: continue
                            val id = j.optString("job_id", "")
                            val status = j.optString("status", "")
                            val platform = j.optString("platform", "livestream")
                            if (id.isNotBlank()) {
                                ids.add(id)
                                if ((status == "recording" || status == "starting") && host.isNotBlank()) {
                                    LivestreamMonitorWorker.enqueue(ctx, id, host, platform)
                                }
                            }
                        }
                        if (ids.isEmpty()) {
                            LivestreamMonitorWorker.cancelAll(ctx)
                        }
                        lastLivestreamServerRecordingIds = ids
                        // SPEC §4.1: sync le cung cap nhat active de pre-flight chinh xac.
                        serverLivestreamActive = ids.size
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Livestream", "syncState: ${e.message}")
            }
        }
    }

    fun requestSocialDownload(url: String, saveFolder: String) {
        isSocialExtracting = true
        socialExtractStatus = "Đang tải..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = org.json.JSONObject().put("url", url).put("folder", saveFolder).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase${AppConfig.ApiEndpoints.SOCIAL_DOWNLOAD}").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    val socialCode = runCatching { org.json.JSONObject(respBody).optString("code", "") }.getOrDefault("")
                    val socialErr = runCatching { org.json.JSONObject(respBody).optString("error", "") }.getOrDefault("")
                    withContext(Dispatchers.Main) {
                        socialExtractStatus = if (resp.isSuccessful) "✅ Đã lưu vào $saveFolder" else if (socialCode == "MAX_DOWNLOADS" && socialErr.isNotBlank()) socialErr else if (socialErr.isNotBlank()) socialErr else "Lỗi"
                        if (resp.isSuccessful) {
                            socialDownloadHistory = socialDownloadHistory + SocialDownloadItem(
                                url = url, platform = "direct", isSuccess = true, timestamp = System.currentTimeMillis()
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) { socialExtractStatus = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isSocialExtracting = false }
            }
        }
    }

    fun dedupeLivestreamJobsForDisplay(jobs: List<LivestreamJob>): List<LivestreamJob> {
        return jobs.distinctBy { it.jobId.ifBlank { "${it.platform}_${it.watchUsername}_${it.outputFile}" } }
    }
}