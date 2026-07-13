package com.nas.naswebdav.livestream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavViewModel
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
        internal set
    var isStartingLivestream by androidx.compose.runtime.mutableStateOf(false)
        internal set
    internal var lastLivestreamServerSyncAt = 0L
    internal var lastLivestreamServerRecordingIds: Set<String> = emptySet()

    /** Danh sách các stream đang ghi — dùng mutableStateListOf cho Compose */
    var activeLivestreams = androidx.compose.runtime.mutableStateListOf<WebDavViewModel.LivestreamJob>()
        private set

    // ═══ STREAM PIPE (CDN → NAS) ═══

    var isStreamPiping by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var streamPipeStatus by androidx.compose.runtime.mutableStateOf("")
        internal set
    var streamPipeProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        internal set
    var streamPipeSpeedStr by androidx.compose.runtime.mutableStateOf("-- MB/s")
        internal set
    var streamPipeEtaStr by androidx.compose.runtime.mutableStateOf("--")
        internal set

    // ═══ SOCIAL EXTRACTOR ═══

    var socialExtractStatus by androidx.compose.runtime.mutableStateOf("")
        internal set
    var isSocialExtracting by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var socialDownloadHistory by androidx.compose.runtime.mutableStateOf<List<SocialDownloadItem>>(emptyList())
        internal set

    // ═══ TIKTOK LIVE WATCH ═══

    var tiktokWatchDaemonRunning by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var tiktokWatchDaemonLastTick by androidx.compose.runtime.mutableStateOf("")
        internal set
    var tiktokWatchDaemonSummary by androidx.compose.runtime.mutableStateOf("")
        internal set
    var tiktokLiveWatchError by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set
    var tiktokExcludeEnabled by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var tiktokExcludeStart by androidx.compose.runtime.mutableStateOf("23:00")
        internal set
    var tiktokExcludeEnd by androidx.compose.runtime.mutableStateOf("07:00")
        internal set
    var isLoadingTikTokWatch by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var tiktokCookiesStatus by androidx.compose.runtime.mutableStateOf("unknown")
        internal set
    var tiktokCookiesMessage by androidx.compose.runtime.mutableStateOf("")
        internal set

    // ═══ PLACEHOLDER METHODS — implement Phase 3b ═══

    fun startLivestreamRecord(url: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun stopLivestreamRecord(context: android.content.Context, jobId: String) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun syncLivestreamStateWithServer(context: android.content.Context) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun restoreLivestreamStateIfRunning() {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun observeLivestreamWorker(context: android.content.Context) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun monitorYtdlpJob() { /* TODO Phase 3b */ }

    fun startStreamPipe(url: String, format: String, outputPath: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun cancelStreamPipe(onError: (String) -> Unit = {}) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun requestSocialDownload(url: String, format: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun fetchTikTokLiveWatch() {
        isLoadingTikTokWatch = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/tiktok/watch/list").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        tiktokWatchDaemonRunning = json.optBoolean("daemon_running", false)
                        tiktokWatchDaemonLastTick = json.optString("last_tick", "")
                        tiktokWatchDaemonSummary = json.optString("summary", "")
                        tiktokExcludeEnabled = json.optBoolean("exclude_enabled", false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Livestream", "fetchTikTokLiveWatch: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isLoadingTikTokWatch = false }
            }
        }
    }

    fun addTikTokLiveWatchUser(username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("username", username).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/tiktok/watch/add").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: Exception) {
                android.util.Log.w("Livestream", "addTikTokLiveWatchUser: ${e.message}")
            }
        }
    }

    fun removeTikTokLiveWatchUser(username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().put("username", username).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/tiktok/watch/remove").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: Exception) {
                android.util.Log.w("Livestream", "removeTikTokLiveWatchUser: ${e.message}")
            }
        }
    }

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
                val req = okhttp3.Request.Builder().url("$apiBase/api/tiktok/watch/settings").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: Exception) {
                android.util.Log.w("Livestream", "updateTikTokLiveWatchSettings: ${e.message}")
            }
        }
    }

    fun startLivestreamRecord(url: String, quality: String = "best", referer: String = "", userAgent: String = "") {
        isStartingLivestream = true
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/livestream/record").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
                withContext(Dispatchers.Main) { livestreamMessage = "✅ Đã gửi lệnh ghi" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { livestreamMessage = "Lỗi: ${e.message}" }
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/livestream/stop").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
                withContext(Dispatchers.Main) {
                    activeLivestreams.removeAll { it.jobId == jobId }
                    livestreamMessage = "⏹ Đã dừng ghi hình"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { livestreamMessage = "Lỗi dừng ghi: ${e.message}" }
            }
        }
    }

    fun syncLivestreamStateWithServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/livestream/status").get().build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    val jobsArr = json.optJSONArray("jobs")
                    if (jobsArr != null) {
                        val ids = mutableSetOf<String>()
                        for (i in 0 until jobsArr.length()) {
                            val j = jobsArr.optJSONObject(i) ?: continue
                            val id = j.optString("job_id", "")
                            if (id.isNotBlank()) ids.add(id)
                        }
                        lastLivestreamServerRecordingIds = ids
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Livestream", "syncState: ${e.message}")
            }
        }
    }

    fun startStreamPipe(sourceUrl: String, fileName: String) {
        isStreamPiping = true
        streamPipeStatus = "Đang bắt đầu..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = org.json.JSONObject().put("url", sourceUrl).put("filename", fileName).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/stream/pipe").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: Exception) {
                streamPipeStatus = "Lỗi: ${e.message}"
            } finally {
                withContext(Dispatchers.Main) { isStreamPiping = false }
            }
        }
    }

    fun cancelStreamPipe() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/stream/cancel").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: Exception) {
                android.util.Log.w("Livestream", "cancelStreamPipe: ${e.message}")
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
                val req = okhttp3.Request.Builder().url("$apiBase/api/social/download").post(body).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    withContext(Dispatchers.Main) {
                        socialExtractStatus = if (resp.isSuccessful) "✅ Đã lưu vào $saveFolder" else "Lỗi"
                        if (resp.isSuccessful) {
                            socialDownloadHistory = socialDownloadHistory + SocialDownloadItem(
                                url = url, platform = "direct", isSuccess = true, timestamp = System.currentTimeMillis()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { socialExtractStatus = "Lỗi: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isSocialExtracting = false }
            }
        }
    }

    fun dedupeLivestreamJobsForDisplay(jobs: List<WebDavViewModel.LivestreamJob>): List<WebDavViewModel.LivestreamJob> {
        // SP3 fix already applied in WebDavViewModel facade — keep behavior identical
        return jobs
    }
}