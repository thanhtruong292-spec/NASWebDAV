@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import com.nas.naswebdav.*
import com.nas.naswebdav.R
import com.nas.naswebdav.ui.dialogs.*
import com.nas.naswebdav.utils.FormatUtils
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.session.MediaSession
import android.app.PictureInPictureParams
import android.util.Rational
import coil.compose.AsyncImage
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

// ════════════════════════════════════════════════════════════════════════════
// VideoPlayerScreen.kt
// ════════════════════════════════════════════════════════════════════════════

// Định nghĩa hằng số Action cho PiP Broadcast
private const val PIP_ACTION_REWIND = "com.nas.naswebdav.PIP_REWIND"
private const val PIP_ACTION_PLAY_PAUSE = "com.nas.naswebdav.PIP_PLAY_PAUSE"
private const val PIP_ACTION_FAST_FORWARD = "com.nas.naswebdav.PIP_FAST_FORWARD"

private fun inferWebDavBaseUrl(rawUrl: String): String {
    return runCatching {
        val parsed = java.net.URL(rawUrl)
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        val path = parsed.path ?: ""
        val webDavRoot = "/webdav/"
        val basePath = if (path.contains(webDavRoot)) {
            path.substringBefore(webDavRoot) + webDavRoot
        } else {
            path.substringBeforeLast("/", "").ifBlank { "" } + "/"
        }
        "${parsed.protocol}://${parsed.host}$port$basePath"
    }.getOrDefault("")
}

private fun mediaWebDavBaseForActiveHost(originalUrl: String, activeBaseUrl: String): String {
    return runCatching {
        val original = java.net.URL(originalUrl)
        val active = activeBaseUrl.trimEnd('/').takeIf { it.isNotBlank() }?.let { java.net.URL(it) }
        val host = active?.host?.takeIf { it.isNotBlank() } ?: original.host
        val protocol = active?.protocol?.takeIf { it == "http" || it == "https" } ?: original.protocol
        // Preserve the discovered/explicit WebDAV port. Never replace it with a hardcoded
        // port: LAN and Tailscale deployments can expose WebDAV on different ports.
        val portNumber = active?.port?.takeIf { it > 0 } ?: original.port
        val port = if (portNumber > 0) ":$portNumber" else ""
        val path = original.path ?: ""
        val webDavRoot = "/webdav/"
        val basePath = if (path.contains(webDavRoot)) {
            path.substringBefore(webDavRoot) + webDavRoot
        } else {
            "/webdav/"
        }
        "$protocol://$host$port$basePath"
    }.getOrDefault(inferWebDavBaseUrl(originalUrl))
}

private fun rewriteMediaUrlToActiveBase(originalUrl: String, currentBaseUrl: String, activeBaseUrl: String): String {
    val targetBase = mediaWebDavBaseForActiveHost(originalUrl, activeBaseUrl).trimEnd()
        .takeIf { it.isNotBlank() } ?: return originalUrl
    val sourceBase = currentBaseUrl.trimEnd('/').ifBlank { inferWebDavBaseUrl(originalUrl).trimEnd('/') }
    if (sourceBase.isBlank() || sourceBase == targetBase) return originalUrl

    return runCatching {
        val base = sourceBase.trimEnd('/')
        val relativePath = if (originalUrl.startsWith(base)) {
            originalUrl.substring(base.length).trimStart('/')
        } else {
            java.net.URL(originalUrl).path?.removePrefix(java.net.URL(base).path ?: "/") ?: ""
        }
        relativePath.trimStart('/').ifBlank { originalUrl }?.let { "$targetBase/$it" } ?: originalUrl
    }.getOrDefault(originalUrl)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ExoPlayerScreen(url: String, user: String, pass: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val fileBrowserVM = LocalFileBrowserVM.current
    val globalUiVM = LocalGlobalUiVM.current
    val resolvedAuth = remember(user, pass) {
        if (user.isNotBlank() && pass.isNotBlank()) {
            user to pass
        } else {
            val authData = SecurePrefsHelper.readEncrypted(context.applicationContext)
            if (authData is SecurePrefsHelper.AuthData.Valid) {
                val savedUser = String(authData.user)
                val savedPass = String(authData.pass)
                authData.clear()
                savedUser to savedPass
            } else {
                user to pass
            }
        }
    }
    val resolvedUser = resolvedAuth.first
    val resolvedPass = resolvedAuth.second
    var playbackUrl by remember(url) { mutableStateOf(url) }

    LaunchedEffect(url) {
        val activeBaseUrl = withContext(Dispatchers.IO) {
            SmartNetworkManager.getActiveBaseUrl(context.applicationContext)
        }
        val currentBaseUrl = WebDavManager.currentBaseUrl.orEmpty()
        playbackUrl = rewriteMediaUrlToActiveBase(url, currentBaseUrl, activeBaseUrl)
    }

    // Trạng thái theo dõi chế độ Popup (PiP)
    var isInPiP by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    // Gắn theo dõi trạng thái hiển thị của thanh ExoPlayer Controller
    var isControllerVisible by remember { mutableStateOf(true) }
    
    // Trạng thái riêng cho các nút overlay Compose (Mute, PiP, Repeat...)
    // Luôn hiện khi chạm màn hình, tự ẩn sau 3 giây
    var showOverlayButtons by remember { mutableStateOf(true) }
    
    // Trạng thái mới: Tắt tiếng (Mute) và Lặp lại (Repeat)
    var isMuted by remember { mutableStateOf(false) }
    var isRepeat by remember { mutableStateOf(false) }
    var playerIsPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }

    // Cập nhật trạng thái hiển thị overlay lập tức theo ExoPlayer, bỏ delay
    LaunchedEffect(isControllerVisible) {
        showOverlayButtons = isControllerVisible
    }

    // Always try the original WebDAV/nginx media URL first.
    // That keeps NAS work to simple sendfile/range reads; decoding stays on the phone.
    val isLegacyFormat = remember(playbackUrl) {
        val urlLower = playbackUrl.lowercase()
        urlLower.endsWith(".mpg") || urlLower.endsWith(".mpeg") ||
                urlLower.endsWith(".avi") || urlLower.endsWith(".wmv") ||
                urlLower.endsWith(".flv") || urlLower.endsWith(".asf")
    }

    val effectiveUrl = remember(playbackUrl, isLegacyFormat) {
        if (isLegacyFormat) {
            val uri = playbackUrl.toUri()
            val relativePath = uri.path?.substringAfter("/webdav") ?: ""
            val encodedPath = java.net.URLEncoder.encode(relativePath, "UTF-8")
            "${playbackUrl.toApiBaseUrl()}/api/stream/transcode?path=$encodedPath"
        } else {
            playbackUrl.toFastMediaUrl() // MP4, MKV, MOV, TS, WEBM → /api/media endpoint
        }
    }

    val isTranscoding = isLegacyFormat

    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPiP = info.isInPictureInPictureMode
        }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            if (activity is VideoPlayerActivity) {
                activity.isPlayingVideo = false
            }
            activity?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    // Track the previous ExoPlayer instance so we can release it before creating a new one.
    // Without this, when effectiveUrl changes (WiFi→Tailscale), the old basePlayer is orphaned
    // and never released → 2 players × 16-64MB buffers each on a 1GB device.
    var prevExoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }

    // ═══ KHỞI TẠO EXOPLAYER TỐI ƯU CỰC ĐẠI CHO NAS STREAMING ═══
    val exoPlayer = remember(effectiveUrl, resolvedUser, resolvedPass) {
        // Release previous instance BEFORE creating new one to prevent double-buffer OOM.
        prevExoPlayer?.release()
        prevExoPlayer = null

        val app = NasApplication.instance

        // 1. LOAD CONTROL — Chống OOM (Tràn RAM) khi Stream Video dung lượng khủng
        // Gốc (250s) gây crash Out Of Memory lập tức với video 4K/bluray. Giữ mức tối đa 50s.
        // TÍNH TOÁN RAM ĐỘNG CHO EXO PLAYER
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        // Dành 15% RAM trống hiện tại làm bộ đệm video. Tối thiểu 50MB, tối đa 500MB để tránh OOM.
        val dynamicBufferBytes = (memoryInfo.availMem * 0.15).toLong()
            .coerceIn(50L * 1024 * 1024, 500L * 1024 * 1024).toInt()

        android.util.Log.i("VideoPlayer", "Dynamic Buffer allocated: ${dynamicBufferBytes / 1024 / 1024} MB")

        val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(true, androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                10_000,   // Min buffer: nạp 10s là đủ để duy trì mượt
                120_000,  // Max buffer: nạp trước tối đa 2 phút (đủ xem xuyên qua mạng chập chờn)
                250,      // Ngưỡng mới play cực thấp (0.25s) để play ngay lập tức
                500       // Ngưỡng re-buffer cực thấp
            )
            .setTargetBufferBytes(dynamicBufferBytes) // Dùng cấp phát RAM động
            .setBackBuffer(30_000, true)  // Giữ 30s lui để tua lại mượt hơn
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // 2. RENDERERS
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // 3. DATA SOURCE — BỎ HOÀN TOÀN DISK CACHE (Zero Disk IO)
        // Khi dùng mạng LAN ổ NAS quay siêu nhanh, nếu ép điện thoại chép Video vào bộ nhớ Flash 
        // lúc tua (Seeking) sẽ bị thắt cổ chai vòng quay (Flash memory Write Speed quá thấp). 
        // -> Đọc thẳng luồng stream từ NAS đổ vào RAM hiển thị luôn!
        // AUTH FIX: use the resolved credential snapshot with UTF-8 Base64. The client interceptor
        // supplies current auth only when no explicit header is present.
        val authHeader = WebDavManager.AuthState(user = resolvedUser, pass = resolvedPass).authHeader
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(app.videoStreamingClient)
            .setDefaultRequestProperties(mapOf("Authorization" to authHeader))

        // 4. EXTRACTORS - Tối ưu mạnh mẽ để quét được độ dài (00:00 bug fix)
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            // Tắt ConstantBitrateSeeking vì nó chặn tua MP4 bị lỗi header
            .setConstantBitrateSeekingEnabled(false)
            // Quét sâu tới 5MB cuối file thay vì 1MB để chắc chắn moi được Metadata thời lượng (chữa lỗi 00:00)
            .setTsExtractorTimestampSearchBytes(5_000_000)

        // 5. MEDIA SOURCE FACTORY
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // 6. MIME TYPE cho format gốc (nếu không dùng transcode)
        val mimeType = if (isTranscoding) {
            androidx.media3.common.MimeTypes.APPLICATION_M3U8  // Transcode only for legacy formats
        } else {
            null  // Let ExoPlayer auto-detect for all modern formats (mp4, mkv, mov, webm, ts)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(effectiveUrl)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        // 7. BUILD EXOPLAYER BẢN GỐC
        val basePlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(app.bandwidthMeter)
            .build()
            .apply {
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val invalidResponseCode = generateSequence(error.cause) { it.cause }
                            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()
                            ?.responseCode
                        android.util.Log.e("VideoPlayer", "Lỗi: ${error.errorCodeName} - ${error.message}, HTTP=$invalidResponseCode")
                        // Bắt lỗi khi phần cứng điện thoại (Hardware Decoder) KHÔNG HỖ TRỢ định dạng 
                        // Ví dụ: Video 4K HDR HEVC trên máy tính bảng cũ, hoặc Âm thanh Dolby AC3 trong file MKV.
                        // Tại đây, ta báo cho giao diện bật Dialog hỏi chuyển sang VLC
                        (activity as? MainActivity)?.runOnUiThread {
                            val msg = if (invalidResponseCode == 416) {
                                "Tệp MP4 này bị hỏng hoặc chưa được hoàn tất metadata (HTTP 416, ${error.errorCodeName}).\n\nNAS sẽ tự ẩn các bản ghi livestream thiếu moov atom sau khi dọn nền. Vui lòng chọn một bản ghi khác hoặc ghi lại livestream."
                            } else if (invalidResponseCode != null) {
                                "Không thể tải luồng video từ NAS (HTTP $invalidResponseCode, ${error.errorCodeName}).\n\nVui lòng thử lại sau vài giây hoặc nhấn nút [Mở bằng ứng dụng ngoài] (biểu tượng mũi tên) để xem bằng VLC/MX Player qua proxy cục bộ."
                            } else {
                                "Thiết bị của bạn không hỗ trợ giải mã định dạng phim này (Lỗi: ${error.errorCodeName}).\n\nVui lòng nhấn nút [Mở bằng ứng dụng ngoài] (biểu tượng mũi tên) để xem bằng VLC hoặc MX Player."
                            }
                            globalUiVM.show(DialogType.ERROR, msg)
                        }
                    }
                    
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        super.onVideoSizeChanged(videoSize)
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val act = activity as? VideoPlayerActivity
                            if (act != null) {
                                // 1. Truyền tỉ lệ thực tế về VideoPlayerActivity để nó dùng khi gọi từ phím Home (onUserLeaveHint)
                                act.videoAspectRatio = android.util.Rational(videoSize.width, videoSize.height)
                                
                                // 2. Lập tức cập nhật System PiP Params trên Android 12+ (Auto-PiP behavior)
                                updatePipActions(act, this@apply)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        super.onIsPlayingChanged(isPlaying)
                        playerIsPlaying = isPlaying
                        // Khóa sáng màn hình khi ĐANG PHÁT, tự động cho ngủ màn hình khi PAUSE
                        if (isPlaying) {
                            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        
                        // Bắt buộc: Tính năng PiP khi vuốt Home chỉ kích hoạt nếu video ĐANG PHÁT.
                        // Tránh lỗi khi người dùng ấn "Mở bằng VLC", ứng dụng này bị văng ra sau (onUserLeaveHint) 
                        // và vô tình bật PiP đè lên cả VLC.
                        (activity as? VideoPlayerActivity)?.isPlayingVideo = isPlaying
                    }
                })
                setMediaItem(mediaItem)
                
                // Đồng bộ Volume và Repeat Mode khởi tạo
                volume = if (isMuted) 0f else 1f
                repeatMode = if (isRepeat) androidx.media3.common.Player.REPEAT_MODE_ALL else androidx.media3.common.Player.REPEAT_MODE_OFF
                
                prepare()
                playWhenReady = true
            }

        // 8. BỌC EXOPLAYER TRONG FORWARDING PLAYER ĐỂ TÙY DỤNG GIA TỐC TUA THEO ĐỘ DÀI TRUYỆN/VIDEO
        object : androidx.media3.common.ForwardingPlayer(basePlayer) {
            private fun getDynamicSeekIncrement(): Long {
                val dur = duration
                return when {
                    dur == androidx.media3.common.C.TIME_UNSET -> 15000L
                    dur > 3600_000L -> 60000L // Dài hơn 1 tiếng -> tua 60s
                    dur > 1800_000L -> 30000L // Dài hơn 30 phút -> tua 30s
                    dur < 300_000L -> 5000L   // Ngắn hơn 5 phút -> tua 5s
                    else -> 15000L            // Mặc định 15s
                }
            }

            override fun getSeekForwardIncrement(): Long = getDynamicSeekIncrement()
            override fun getSeekBackIncrement(): Long = getDynamicSeekIncrement()

            override fun seekForward() {
                seekTo((currentPosition + getDynamicSeekIncrement()).coerceAtMost(duration.coerceAtLeast(0L)))
            }

            override fun seekBack() {
                seekTo((currentPosition - getDynamicSeekIncrement()).coerceAtLeast(0L))
            }
        }
    }

    // Effect để lắng nghe thay đổi trạng thái và áp dụng ngay lập tức
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }
    LaunchedEffect(isRepeat) {
        exoPlayer.repeatMode = if (isRepeat) androidx.media3.common.Player.REPEAT_MODE_ALL else androidx.media3.common.Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(exoPlayer, isInPiP) {
        while (isActive) {
            playbackPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            playbackDurationMs = exoPlayer.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            delay(if (isInPiP) 2000L else 500L)
        }
    }

    // MediaSession đơn giản — chỉ cần cho Android biết đang phát media
    val mediaSession = remember {
        MediaSession.Builder(context, exoPlayer).build()
    }

    // Lưu giữ PlayerView reference để cập nhật trạng thái PiP
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }

    // ============ BROADCAST RECEIVER CHO NÚT PIP ============
    // Đăng ký BroadcastReceiver để lắng nghe lệnh từ nút PiP của HĐH
    DisposableEffect(exoPlayer) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    PIP_ACTION_REWIND -> {
                        exoPlayer.seekBack()
                    }
                    PIP_ACTION_PLAY_PAUSE -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        // Cập nhật lại icon Play/Pause trên PiP
                        activity?.let { updatePipActions(it, exoPlayer) }
                    }
                    PIP_ACTION_FAST_FORWARD -> {
                        exoPlayer.seekForward()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(PIP_ACTION_REWIND)
            addAction(PIP_ACTION_PLAY_PAUSE)
            addAction(PIP_ACTION_FAST_FORWARD)
        }
        // QUAN TRỌNG: Phải dùng RECEIVER_EXPORTED để HĐH Android gửi được Broadcast vào App
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.d("VideoPlayer", "Receiver đã được gỡ hoặc không tồn tại: ${e.message}")
            }
            exoPlayer.release()
            mediaSession.release()
        }
    }

    // TỐI ƯU HÓA 12.F: Sát thủ rò rỉ âm thanh (Ghost Audio)
    // QUAN TRỌNG: Lấy LifecycleOwner bằng thư viện chuẩn của Compose UI
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val act = context as? android.app.Activity
                val pip = act?.isInPictureInPictureMode ?: false
                if (!pip) {
                    exoPlayer.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true // NGĂN TẮT MÀN HÌNH KHI PHÁT VIDEO
                    // Ép video scale lấp đầy khung hình (Xóa viền đen 2 bên)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    post {
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_center_controls)?.apply {
                            visibility = android.view.View.GONE
                            background = null
                        }
                    }
                    
                    playerViewRef.value = this
                }
            },
            update = { view ->
                view.useController = false
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay buttons nằm trên PlayerView nhưng KHÔNG chặn touch (chỉ đọc)
        Box(Modifier.fillMaxSize()) {

        // Chỉ hiển thị các nút điều khiển khi KHÔNG ở chế độ Popup
        androidx.compose.animation.AnimatedVisibility(
            visible = !isInPiP && showOverlayButtons,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Tiêu đề Video & Nút Back
                val fileName = url.substringAfterLast("/").let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { it }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                        .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.minimumInteractiveComponentSize().size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = fileName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                        )
                    )
                    .padding(start = 10.dp, end = 10.dp, bottom = 12.dp, top = 18.dp)
            ) {
                Slider(
                    value = if (playbackDurationMs > 0L) {
                        playbackPositionMs.coerceIn(0L, playbackDurationMs).toFloat()
                    } else {
                        0f
                    },
                    onValueChange = { value ->
                        if (playbackDurationMs > 0L) {
                            exoPlayer.seekTo(value.toLong().coerceIn(0L, playbackDurationMs))
                        }
                    },
                    valueRange = 0f..playbackDurationMs.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = Color(0xFFFFC7B2),
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { exoPlayer.seekTo(0L) }, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                            Icon(Icons.Default.SkipPrevious, stringResource(R.string.cd_skip_previous), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { exoPlayer.seekBack() }, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                            Icon(Icons.Default.Replay30, stringResource(R.string.cd_seek_backward), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            modifier = Modifier.minimumInteractiveComponentSize().size(34.dp)
                        ) {
                            Icon(
                                if (playerIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                stringResource(if (playerIsPlaying) R.string.cd_pause else R.string.cd_play),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                        IconButton(onClick = { exoPlayer.seekForward() }, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                            Icon(Icons.Default.Forward30, stringResource(R.string.cd_seek_forward), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                val duration = exoPlayer.duration
                                if (duration > 0L && duration != androidx.media3.common.C.TIME_UNSET) {
                                    exoPlayer.seekTo(duration)
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, stringResource(R.string.cd_skip_next), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = "${FormatUtils.formatPlayerTime(playbackPositionMs)} / ${FormatUtils.formatPlayerTime(playbackDurationMs)}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isMuted = !isMuted }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                "Âm lượng",
                                tint = if (isMuted) Color(0xFFFF8A80) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { isRepeat = !isRepeat }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Repeat,
                                "Lặp lại",
                                tint = if (isRepeat) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { activity?.let { act -> enterPipMode(act, exoPlayer) } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.PictureInPictureAlt, "Popup", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                exoPlayer.pause()
                                openExternalVideoPlayer(
                                    context = context,
                                    // Keep the original WebDAV URL so the external-player helper
                                    // can apply its existing /webdav/ -> /media/ rewrite.
                                    url = playbackUrl,
                                    user = resolvedUser,
                                    pass = resolvedPass,
                                    onError = { android.util.Log.e("VideoPlayer", "Không mở được trình phát ngoài") }
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, "Mở bằng ứng dụng ngoài", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "Xóa video", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Dialog xác nhận xóa video
            if (showDeleteDialog) {
                val fileName = url.substringAfterLast("/").let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { it }
                }
                AppStatusDialog(
                    type = DialogType.WARNING,
                    message = "Bạn có chắc muốn xóa\n\"$fileName\"?\n\nVideo sẽ được chuyển vào Thùng rác.",
                    onConfirm = {
                        showDeleteDialog = false
                        exoPlayer.pause()
                        val fileToDelete = NasFile(fileName, url, false, "video/*", 0, 0)
                        fileBrowserVM.deleteFile(context, fileToDelete)
                        onBack()
                    },
                    onDismiss = { showDeleteDialog = false }
                )
            }
            } // Close Box (AnimatedVisibility content)
        } // Close AnimatedVisibility
        } // Close touch-intercept Box
    } // Close outer Box
} // Close VideoPlayerScreen
// ============ HÀM TẠO NÚT PIP ============

/** Tạo danh sách 3 nút: Tua lùi 15s | Play/Pause | Tua tới 15s */
private fun buildPipActions(
    context: android.content.Context,
    isPlaying: Boolean
): List<android.app.RemoteAction> {
    // Nút 1: Tua lùi 15s
    val rewindAction = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_rew),
        "Tua lùi", "Tua lùi 15 giây",
        android.app.PendingIntent.getBroadcast(
            context, 1,
            android.content.Intent(PIP_ACTION_REWIND).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    // Nút 2: Play/Pause (Đổi icon phù hợp trạng thái hiện tại)
    val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
    val playPauseLabel = if (isPlaying) "Tạm dừng" else "Phát"
    val playPauseAction = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(context, playPauseIcon),
        playPauseLabel, playPauseLabel,
        android.app.PendingIntent.getBroadcast(
            context, 2,
            android.content.Intent(PIP_ACTION_PLAY_PAUSE).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    // Nút 3: Tua tới 15s
    val forwardAction = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_ff),
        "Tua tới", "Tua tới 15 giây",
        android.app.PendingIntent.getBroadcast(
            context, 3,
            android.content.Intent(PIP_ACTION_FAST_FORWARD).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    return listOf(rewindAction, playPauseAction, forwardAction)
}
/** Clamp tỉ lệ PiP để không bị crash IllegalArgumentException */
private fun getSafePipRatio(width: Int, height: Int): Rational {
    if (width <= 0 || height <= 0) return Rational(16, 9)
    val ratio = width.toFloat() / height.toFloat()
    return when {
        ratio > 2.38f -> Rational(238, 100) // Tối đa 2.39:1
        ratio < 0.42f -> Rational(100, 238) // Tối thiểu 1:2.39 (0.4184)
        else -> Rational(width, height)
    }
}

/** Vào chế độ PiP với 3 nút điều khiển và tỉ lệ khung hình tự động */
private fun enterPipMode(activity: ComponentActivity, exoPlayer: androidx.media3.common.Player) {
    try {
        val videoSize = exoPlayer.videoSize
        // Tự động phát hiện tỉ lệ khung hình thực tế của video (thay vì hardcode 16:9)
        val aspectRatio = getSafePipRatio(videoSize.width, videoSize.height)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(buildPipActions(activity, exoPlayer.isPlaying))
            .build()
        activity.enterPictureInPictureMode(params)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        android.util.Log.e("VideoPlayer", "Thiết bị không hỗ trợ PiP: ${e.message}")
    }
}

/** Cập nhật các nút PiP (Ví dụ: đổi icon Play → Pause sau khi bấm) */
private fun updatePipActions(activity: ComponentActivity, exoPlayer: androidx.media3.common.Player) {
    try {
        val videoSize = exoPlayer.videoSize
        val aspectRatio = getSafePipRatio(videoSize.width, videoSize.height)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(buildPipActions(activity, exoPlayer.isPlaying))
            .build()
        activity.setPictureInPictureParams(params)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        // Ignored: PiP unsupported or disabled globally
    }
}
