package com.nas.naswebdav.ui.screens

import android.app.PictureInPictureParams
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.nas.naswebdav.MainActivity
import okhttp3.Credentials

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, user: String, pass: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // Cập nhật trạng thái cho Activity biết để kích hoạt PiP (An toàn bộ nhớ)
    DisposableEffect(Unit) {
        (context as? MainActivity)?.isPlayingVideo = true
        onDispose { (context as? MainActivity)?.isPlayingVideo = false }
    }

    var isInPiP by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPiP = info.isInPictureInPictureMode
        }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            activity?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build().apply {
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(mapOf("Authorization" to Credentials.basic(user, pass)))

                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))

                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
    }

    val mediaSession = remember { MediaSession.Builder(context, exoPlayer).build() }

    DisposableEffect(Unit) {
        onDispose {
            mediaSession.release()
            exoPlayer.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Ẩn nút Back và Nút Popup khi video đang thu nhỏ (PiP)
        if (!isInPiP) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 32.dp, start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }

            IconButton(
                onClick = {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    activity?.enterPictureInPictureMode(params)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.PictureInPictureAlt, "Popup", tint = Color.White)
            }
        }
    }
}
