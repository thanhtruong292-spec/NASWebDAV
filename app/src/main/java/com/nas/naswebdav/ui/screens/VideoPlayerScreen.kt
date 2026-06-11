@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*
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

// ════════════════════════════════════════════════════════════════════════════
// VideoPlayerScreen.kt
// ════════════════════════════════════════════════════════════════════════════

// Định nghĩa hằng số Action cho PiP Broadcast
private const val PIP_ACTION_REWIND = "com.nas.naswebdav.PIP_REWIND"
private const val PIP_ACTION_PLAY_PAUSE = "com.nas.naswebdav.PIP_PLAY_PAUSE"
private const val PIP_ACTION_FAST_FORWARD = "com.nas.naswebdav.PIP_FAST_FORWARD"

private fun formatPlayerTime(positionMs: Long): String {
    val safeMs = positionMs.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, user: String, pass: String, viewModel: WebDavViewModel? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
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

    // ═══ PHÁT HIỆN ĐỊNH DẠNG LEGACY NGAY LÚC MỞ PLAYER ═══
    // Formats ExoPlayer KHÔNG decode được (MPEG-2, WMV, v.v.)
    // → Chuyển thẳng sang URL transcode trên NAS (FFmpeg → MP4)
    val isLegacyFormat = remember(url) {
        val urlLower = url.lowercase()
        urlLower.endsWith(".mpg") || urlLower.endsWith(".mpeg") ||
                urlLower.endsWith(".avi") || urlLower.endsWith(".wmv") ||
                urlLower.endsWith(".flv") || urlLower.endsWith(".asf")
    }

    val effectiveUrl = remember(url, isLegacyFormat) {
        if (isLegacyFormat) {
            // Xây dựng URL transcode: http://host:5050/api/stream/transcode?path=/đường/dẫn/file
            // FIX: Dùng android.net.Uri thay vì java.net.URI để tránh crash URISyntaxException khi có khoảng trắng
            val uri = android.net.Uri.parse(url)
            val relativePath = uri.path?.substringAfter("/webdav") ?: ""
            val encodedPath = java.net.URLEncoder.encode(relativePath, "UTF-8")
            val transcodeUrl = "${url.toApiBaseUrl()}/api/stream/transcode?path=$encodedPath"
            android.util.Log.i("VideoPlayer", "Legacy format → transcode: $transcodeUrl")
            transcodeUrl
        } else {
            url.toFastMediaUrl() // MP4, MKV, MOV, TS, WEBM → endpoint Range/ETag tối ưu LAN
        }
    }

    // Chỉ coi là transcode khi thật sự đi vào endpoint transcode/HLS.
    // /api/media cho MP4 thường cũng đổi URL nhưng vẫn là progressive stream, không phải manifest.
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

    // ═══ KHỞI TẠO EXOPLAYER TỐI ƯU CỰC ĐẠI CHO NAS STREAMING ═══
    val exoPlayer = remember {
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
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(app.videoStreamingClient)
            .setDefaultRequestProperties(mapOf("Authorization" to okhttp3.Credentials.basic(resolvedUser, resolvedPass)))

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
                            viewModel?.showCommonDialog = true
                            viewModel?.commonDialogType = com.nas.naswebdav.ui.dialogs.DialogType.ERROR
                            viewModel?.commonDialogMessage = if (invalidResponseCode == 416) {
                                "Tệp MP4 này bị hỏng hoặc chưa được hoàn tất metadata (HTTP 416, ${error.errorCodeName}).\n\nNAS sẽ tự ẩn các bản ghi livestream thiếu moov atom sau khi dọn nền. Vui lòng chọn một bản ghi khác hoặc ghi lại livestream."
                            } else if (invalidResponseCode != null) {
                                "Không thể tải luồng video từ NAS (HTTP $invalidResponseCode, ${error.errorCodeName}).\n\nVui lòng thử lại sau vài giây hoặc nhấn nút [Mở bằng ứng dụng ngoài] (biểu tượng mũi tên) để xem bằng VLC/MX Player qua proxy cục bộ."
                            } else {
                                "Thiết bị của bạn không hỗ trợ giải mã định dạng phim này (Lỗi: ${error.errorCodeName}).\n\nVui lòng nhấn nút [Mở bằng ứng dụng ngoài] (biểu tượng mũi tên) để xem bằng VLC hoặc MX Player."
                            }
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
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            playbackPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            playbackDurationMs = exoPlayer.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            delay(500)
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
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {
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
                val pip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) act?.isInPictureInPictureMode ?: false else false
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
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
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
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = fileName,
                        color = Color.White,
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
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFFC7B2),
                        inactiveTrackColor = Color.White.copy(alpha = 0.42f)
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
                        IconButton(onClick = { exoPlayer.seekTo(0L) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Về đầu", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { exoPlayer.seekBack() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Replay30, "Tua lùi", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                if (playerIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Phát / tạm dừng",
                                tint = Color.White,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                        IconButton(onClick = { exoPlayer.seekForward() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Forward30, "Tua tới", tint = Color.White, modifier = Modifier.size(20.dp))
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
                            Icon(Icons.Default.SkipNext, "Tới cuối", tint = Color.White.copy(alpha = 0.65f), modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = "${formatPlayerTime(playbackPositionMs)} / ${formatPlayerTime(playbackDurationMs)}",
                            color = Color.White,
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
                                tint = if (isMuted) Color(0xFFFF8A80) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { isRepeat = !isRepeat }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Repeat,
                                "Lặp lại",
                                tint = if (isRepeat) Color(0xFF00E676) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { activity?.let { act -> enterPipMode(act, exoPlayer) } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.PictureInPictureAlt, "Popup", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                exoPlayer.pause()
                                openExternalVideoPlayer(
                                    context = context,
                                    url = effectiveUrl,
                                    user = resolvedUser,
                                    pass = resolvedPass,
                                    onError = { android.util.Log.e("VideoPlayer", "Không mở được trình phát ngoài") }
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, "Mở bằng ứng dụng ngoài", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        if (viewModel != null) {
                            IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "Xóa video", tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // Dialog xác nhận xóa video
            if (showDeleteDialog && viewModel != null) {
                val fileName = url.substringAfterLast("/").let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                }
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    icon = { Icon(Icons.Default.DeleteForever, null, tint = Color(0xFFEF5350)) },
                    title = { Text("Xóa video?", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                    text = { Text("Bạn có chắc muốn xóa\n\"$fileName\"?\n\nVideo sẽ được chuyển vào Thùng rác.") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteDialog = false
                                exoPlayer.pause()
                                val fileToDelete = NasFile(fileName, url, false, "video/*", 0, 0)
                                viewModel.deleteFile(context, fileToDelete)
                                onBack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                        ) {
                            Text("Xóa")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text("Hủy")
                        }
                    }
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
    } catch (e: Exception) {
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
    } catch (e: Exception) {
        // Ignored: PiP unsupported or disabled globally
    }
}
