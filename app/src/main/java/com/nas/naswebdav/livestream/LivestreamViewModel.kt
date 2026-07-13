package com.nas.naswebdav.livestream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.WebDavViewModel
import com.nas.naswebdav.SocialDownloadItem
import com.nas.naswebdav.WebDavRepository
import kotlinx.coroutines.launch

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
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun addTikTokLiveWatchUser(username: String) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun removeTikTokLiveWatchUser(username: String) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun updateTikTokLiveWatchSettings(enabled: Boolean, start: String, end: String) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun dedupeLivestreamJobsForDisplay(jobs: List<WebDavViewModel.LivestreamJob>): List<WebDavViewModel.LivestreamJob> = jobs
}