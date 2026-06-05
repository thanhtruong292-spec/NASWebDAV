package com.nas.naswebdav

import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import com.nas.naswebdav.ui.screens.VideoPlayerScreen
import android.app.PictureInPictureParams
import android.util.Rational

class VideoPlayerActivity : ComponentActivity() {

    var videoAspectRatio = Rational(16, 9)
    var isPlayingVideo = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: return finish()

        // FIX B2: Đọc credentials từ SecurePrefsHelper thay vì Intent extras.
        // Intent extras có thể bị logcat ghi lại hoặc bị app thứ ba đọc qua ActivityManager.
        // URL vẫn nhận qua Intent vì nó không chứa thông tin xác thực nhạy cảm.
        val authData = SecurePrefsHelper.readEncrypted(this)
        val user: String
        val pass: String
        if (authData is SecurePrefsHelper.AuthData.Valid) {
            user = String(authData.user)
            pass = String(authData.pass)
            authData.clear()
        } else {
            user = ""
            pass = ""
        }

        // FIX A1: Thay systemUiVisibility (deprecated từ API 30) bằng WindowInsetsController.
        // Khởi tạo window.decorView trước để tránh NullPointerException do mDecor chưa được sinh ra.
        val decorView = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            val hideUiFlags = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = hideUiFlags
        }

        setContent {
            MaterialTheme {
                // FIX A1b: Xóa LocalRippleTheme deprecated (Material 3 không còn hỗ trợ).
                // NoRippleIndication vẫn giữ để tắt ripple effect toàn bộ VideoPlayer.
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.LocalIndication provides com.nas.naswebdav.ui.theme.NoRippleIndication
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
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(videoAspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                android.util.Log.w("VideoPlayerActivity", "PiP không khả dụng: ${e.message}", e)
            }
        }
    }
}
