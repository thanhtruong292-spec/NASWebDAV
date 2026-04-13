package com.nas.naswebdav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.nas.naswebdav.ui.screens.VideoPlayerScreen
import android.app.PictureInPictureParams
import android.util.Rational

class VideoPlayerActivity : ComponentActivity() {

    var videoAspectRatio = Rational(16, 9)
    var isPlayingVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: return finish()
        val user = intent.getStringExtra("user") ?: ""
        val pass = intent.getStringExtra("pass") ?: ""

        val hideUiFlags = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        window.decorView.systemUiVisibility = hideUiFlags

        setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.LocalIndication provides com.nas.naswebdav.ui.theme.NoRippleIndication,
                    androidx.compose.material.ripple.LocalRippleTheme provides com.nas.naswebdav.ui.theme.NoRippleTheme
                ) {
                    Surface {
                        VideoPlayerScreen(
                            url = url,
                            user = user,
                            pass = pass,
                            viewModel = null,
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isPlayingVideo) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(videoAspectRatio)
                .build()
            enterPictureInPictureMode(params)
        }
    }
}
