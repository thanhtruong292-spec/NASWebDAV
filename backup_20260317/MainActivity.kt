package com.nas.naswebdav

import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.room.Room
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import java.io.File
import java.io.FileOutputStream
import android.app.PictureInPictureParams
import android.util.Rational
import androidx.media3.session.MediaSession
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll

class MainActivity : androidx.fragment.app.FragmentActivity() {

    // FIX MEMORY LEAK: Loại bỏ companion object (static state), dùng biến instance thông thường
    var isPlayingVideo = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // TỰ ĐỘNG THU NHỎ VIDEO: Kích hoạt PiP khi người dùng bấm phím Home
        if (isPlayingVideo) {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Khi hệ thống thiếu RAM, chủ động giải phóng bộ nhớ đệm hình ảnh
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            coil.Coil.imageLoader(this).memoryCache?.clear()
            System.gc() // Gợi ý hệ thống dọn rác sớm
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Khởi tạo Database và Repository (Chuẩn Clean Architecture)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "nas-db")
            .fallbackToDestructiveMigration()
            .build()
        val webDavManager = WebDavManager()
        val repository = WebDavRepository(webDavManager, db)
        val viewModel = WebDavViewModel(webDavManager, repository)

        // TÍNH NĂNG SHARE TO APP: Xử lý tệp chia sẻ từ ứng dụng khác
        if (intent?.action == android.content.Intent.ACTION_SEND || intent?.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
            val sharedUris = mutableListOf<android.net.Uri>()
            if (intent.action == android.content.Intent.ACTION_SEND) {
                intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { sharedUris.add(it) }
            } else {
                intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { sharedUris.addAll(it) }
            }

            if (sharedUris.isNotEmpty()) {
                // SỬ DỤNG ENCRYPTED SHARED PREFERENCES: Bảo vệ mật khẩu NAS khỏi các ứng dụng độc hại khác trên máy
                val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
                val securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    "NasSecurePrefs", masterKeyAlias, applicationContext,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                val savedUrl = securePrefs.getString("nas_url", "") ?: ""
                val savedUser = securePrefs.getString("nas_user", "") ?: ""
                val savedPass = securePrefs.getString("nas_pass", "") ?: ""

                if (savedUrl.isNotEmpty()) {
                    viewModel.webDavManager.connect(savedUrl, savedUser, savedPass)
                    sharedUris.forEach { uri -> viewModel.uploadFile(applicationContext, uri) }
                }
            }
        }

        // Cấu hình mạng 30 luồng song song để tải thumbnail siêu tốc
        val dispatcher = okhttp3.Dispatcher().apply { maxRequests = 100; maxRequestsPerHost = 30 }
        val customClient = okhttp3.OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val imageLoaderInstance = coil.ImageLoader.Builder(applicationContext)
            .okHttpClient(customClient)
            .memoryCache {
                coil.memory.MemoryCache.Builder(applicationContext)
                    .maxSizePercent(0.20) // Giảm xuống 20% để nhường RAM cho Pipeline quét tệp
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(1024 * 1024 * 1024) // Tăng lên 1GB để lưu Thumbnail cho hàng triệu file
                    .build()
            }
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.25).build() }
            .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(512 * 1024 * 1024).build() }
            .build()
        coil.Coil.setImageLoader(imageLoaderInstance)

        setContent {
            MaterialTheme {
                Surface {
                    NasAppNavigation(viewModel)
                }
            }
        }
    }
}

/// --- NAVIGATION ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NasAppNavigation(viewModel: WebDavViewModel) {
    val mContext = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
    val isBiometricEnabled = sharedPrefs.getBoolean("biometric_enabled", false)

// KIẾN TRÚC MỚI: Dùng rememberSaveable để CHỐNG MẤT TRẠNG THÁI khi xoay màn hình hoặc gập điện thoại
    var currentScreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("login") }
    var showBiometricLock by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(isBiometricEnabled) }
    var mediaUrl by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    // 2. Lắng nghe vòng đời Ứng dụng: Khóa lại ngay khi chuyển App hoặc về Home (ON_STOP)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (sharedPrefs.getBoolean("biometric_enabled", false)) {
                    showBiometricLock = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 3. Hiển thị lớp Khóa Sinh trắc học
    if (showBiometricLock) {
        BiometricLockScreen(
            activity = mContext as androidx.fragment.app.FragmentActivity,
            onAuthenticated = {
                showBiometricLock = false
                // BỎ QUA LOGIN: Nếu vừa khởi động app và quét vân tay đúng, tự động kết nối luôn
                if (currentScreen == "login") {
                    val url = sharedPrefs.getString("nas_url", "") ?: ""
                    val user = sharedPrefs.getString("nas_user", "") ?: ""
                    val pass = sharedPrefs.getString("nas_pass", "") ?: ""
                    if (url.isNotEmpty() && user.isNotEmpty()) {
                        viewModel.connectAndLoad(url, user, pass)
                        viewModel.scheduleIdleDuplicateScan(mContext)
                        viewModel.scheduleIdleSpeedTest(mContext)

                        // CHÚ Ý: Nếu màn hình chính của bạn khai báo tên khác, hãy đổi chữ "browser" này
                        currentScreen = "browser"
                    }
                }
            },
            onFallbackToLogin = {
                showBiometricLock = false
                currentScreen = "login" // Sai 3 lần -> Ép văng ra màn hình Đăng nhập bằng tay
            }
        )
    }

    when (currentScreen) {
        "login" -> LoginScreen(viewModel) { currentScreen = "main_menu" }
        "main_menu" -> {
            // VÔ HIỆU HÓA BACK CỨNG: Chặn thoát app từ Menu chính
            BackHandler { /* Do nothing */ }
            MainMenuScreen(
                viewModel = viewModel,
                onOpenFiles = {
                    viewModel.resetToDefaultMode()
                    currentScreen = "browser"
                },
                onGlobalSearch = { keyword ->
                    viewModel.searchGlobal(keyword)
                    currentScreen = "browser"
                },
                onOpenLatestPhotos = {
                    viewModel.showLatestPhotos()
                    currentScreen = "browser"
                },
                onOpenRecentVideos = {
                    viewModel.showRecentVideos()
                    currentScreen = "browser"
                },
                onOpenTrash = {
                    val trashUrl = viewModel.webDavManager.currentBaseUrl + ".trash/"
                    viewModel.openSpecificUrl(trashUrl, "Thùng rác")
                    currentScreen = "browser"
                },
                onLogout = { currentScreen = "login" }
            )
        }
        "browser" -> BrowserScreen(
            viewModel = viewModel,
            onVideo = { url -> mediaUrl = url; currentScreen = "video" },
            onImage = { url -> mediaUrl = url; currentScreen = "image" },
            onLogout = { currentScreen = "login" },
            onBackToMenu = {
                viewModel.resetToDefaultMode()
                currentScreen = "main_menu"
            }
        )
        // Cập nhật: Truyền thêm user và pass để ExoPlayer có thể tải video từ NAS
        "video" -> VideoPlayerScreen(mediaUrl, viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) { currentScreen = "browser" }
        // Truyền tham số định danh rõ ràng để chặn đứng mọi lỗi Type Mismatch
        "image" -> ImageViewerScreen(
            initialUrl = mediaUrl,
            viewModel = viewModel,
            user = viewModel.webDavManager.currentUser,
            pass = viewModel.webDavManager.currentPass,
            onBack = { currentScreen = "browser" }
        )
    }
}

// --- BROWSER SCREEN ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    viewModel: WebDavViewModel,
    onOpenFiles: () -> Unit,
    onGlobalSearch: (String) -> Unit,
    onOpenLatestPhotos: () -> Unit,
    onOpenRecentVideos: () -> Unit,
    onOpenTrash: () -> Unit,
    onLogout: () -> Unit
) {
    // TỐI ƯU CONTEXT: Sử dụng mContext để tránh xung đột từ khóa hệ thống
    val mContext = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)

    // STATE CHO POPUP TẢI TỪ XA
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadLink by remember { mutableStateOf("") }

    // STATE CHO WAKE-ON-LAN
    var showWolDialog by remember { mutableStateOf(false) }
    var macAddress by remember { mutableStateOf(sharedPrefs.getString("mac_address", "") ?: "") }

// STATE CHO DIALOG THÔNG BÁO (THAY THẾ TOAST)
    var commonDialogMessage by remember { mutableStateOf("") }
    var commonDialogIcon by remember { mutableStateOf(Icons.Default.Info) }
    var commonDialogColor by remember { mutableStateOf(Color.Gray) }
    var showCommonDialog by remember { mutableStateOf(false) }

    // STATE CHO XÁC NHẬN NGUỒN
    var showRebootConfirm by remember { mutableStateOf(false) }
    var showShutdownConfirm by remember { mutableStateOf(false) }

    // STATE CHO AUTO-BACKUP
    var showAutoBackupDialog by remember { mutableStateOf(false) }
    var isAutoBackupEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_backup", false)) }
    var deleteAfterBackup by remember { mutableStateOf(sharedPrefs.getBoolean("delete_after_backup", false)) }

    // DIALOG XÁC NHẬN REBOOT
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RestartAlt, null, tint = Color(0xFFFB8C00), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Xác nhận Reboot", fontWeight = FontWeight.Bold)
                }
            },
            text = { Text("Bạn có chắc chắn muốn khởi động lại NAS Chainedbox? Mọi tiến trình đang chạy sẽ bị dừng lại.", fontSize = 14.sp) },
            confirmButton = {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Button(
                    onClick = {
                        viewModel.sendCommandToNas("power/reboot")
                        commonDialogIcon = Icons.Default.RestartAlt; commonDialogColor = Color(0xFFFB8C00); commonDialogMessage = "Đã gửi lệnh khởi động lại NAS!"; showCommonDialog = true
                        showRebootConfirm = false
                    },
                    interactionSource = interactionSource, // Loại bỏ nền xám gợn sóng
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    modifier = Modifier.background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4)) // Mesh Gradient Xanh Ngọc áp dụng thẳng cho bề mặt nút
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Khởi động lại", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showRebootConfirm = false }) { Text("Hủy", color = Color(0xFF00897B)) } },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // DIALOG XÁC NHẬN SHUTDOWN
    if (showShutdownConfirm) {
        AlertDialog(
            onDismissRequest = { showShutdownConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PowerSettingsNew, null, tint = Color(0xFFE53935), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Xác nhận Shutdown", fontWeight = FontWeight.Bold)
                }
            },
            text = { Text("Bạn có chắc chắn muốn tắt nguồn máy chủ không? Bạn phải dùng Wake-on-LAN để bật lại máy từ xa.", fontSize = 14.sp) },
            confirmButton = {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Button(
                    onClick = {
                        viewModel.sendCommandToNas("power/shutdown")
                        commonDialogIcon = Icons.Default.PowerSettingsNew; commonDialogColor = Color(0xFFE53935); commonDialogMessage = "Đã gửi lệnh tắt nguồn NAS!"; showCommonDialog = true
                        showShutdownConfirm = false
                    },
                    interactionSource = interactionSource, // Loại bỏ nền xám gợn sóng
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    modifier = Modifier.background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4)) // Mesh Gradient Xanh Ngọc
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Tắt nguồn", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showShutdownConfirm = false }) { Text("Hủy", color = Color(0xFF00897B)) } },
            shape = RoundedCornerShape(16.dp)
        )
    }
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Tải xuống từ xa", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Nhập Magnet Link hoặc HTTP URL để NAS tự động tải ngầm qua qBittorrent.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = downloadLink,
                        onValueChange = { downloadLink = it },
                        placeholder = { Text("https://... hoặc magnet:?...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (downloadLink.isNotBlank()) {
                        viewModel.sendDownloadLink(downloadLink)
                        showDownloadDialog = false
                        downloadLink = ""
                    }
                }) { Text("Tải về NAS") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) { Text("Hủy") }
            }
        )
    }
    if (showWolDialog) {
        AlertDialog(
            onDismissRequest = { showWolDialog = false },
            title = { Text("Đánh thức NAS (WOL)", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Nhập địa chỉ MAC của cổng mạng NAS (VD: 00:1A:2B:3C:4D:5E). Ứng dụng sẽ lưu lại cho các lần sau và bắn tín hiệu đánh thức qua mạng LAN.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = macAddress,
                        onValueChange = { macAddress = it },
                        placeholder = { Text("Địa chỉ MAC") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (macAddress.isNotBlank()) {
                        sharedPrefs.edit().putString("mac_address", macAddress).apply()
                        viewModel.sendWakeOnLan(macAddress)
                        showWolDialog = false

                        commonDialogMessage = "Đã bắn tín hiệu Wake-on-LAN!"
                        commonDialogIcon = Icons.Default.FlashOn // Icon sấm sét cho WOL
                        commonDialogColor = Color(0xFF00897B) // Màu xanh ngọc
                        showCommonDialog = true
                    }
                }) { Text("Gửi tín hiệu Bật") }
            },
            dismissButton = {
                TextButton(onClick = { showWolDialog = false }) { Text("Hủy") }
            }
        )
    }

    // DIALOG S.M.A.R.T VÀ TEST TỐC ĐỘ Ổ CỨNG
    if (viewModel.showSmartDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showSmartDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HealthAndSafety, null, tint = Color(0xFF43A047), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Chẩn đoán Ổ cứng", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Thông tin S.M.A.R.T
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                            Text("Sức khỏe S.M.A.R.T", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Trạng thái:", fontSize = 13.sp)
                                Text(viewModel.smartInfo.status, color = if (viewModel.smartInfo.status == "PASSED") Color(0xFF43A047) else Color.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Nhiệt độ đĩa:", fontSize = 13.sp)
                                Text(viewModel.smartInfo.temperature, color = Color(0xFFFB8C00), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    // Test tốc độ Read/Write
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                            Text("Đo tốc độ Đọc/Ghi thực tế", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Ghi (Write):", fontSize = 13.sp)
                                Text(viewModel.speedTestResult.writeSpeed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF8E24AA))
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Đọc (Read):", fontSize = 13.sp)
                                Text(viewModel.speedTestResult.readSpeed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1E88E5))
                            }

                            // Hiển thị mốc thời gian Worker chạy ngầm (Nếu có)
                            if (viewModel.lastAutoSpeedTime.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = viewModel.lastAutoSpeedTime,
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            Button(
                                onClick = { viewModel.runSpeedTest() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = if (viewModel.isTestingSpeed) listOf(Color.Gray, Color.LightGray) else listOf(Color(0xFF00897B), Color(0xFF26A69A))
                                        ),
                                        shape = RoundedCornerShape(24.dp)
                                    ),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
                                contentPadding = PaddingValues(),
                                interactionSource = interactionSource,
                                enabled = !viewModel.isTestingSpeed
                            ) {
                                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (viewModel.isTestingSpeed) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(if (viewModel.isTestingSpeed) "Đang Stress Test..." else "Bắt đầu đo tốc độ", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.showSmartDialog = false }) {
                    Text("Đóng", color = Color(0xFF00897B))
                }
            }
        )
    }

    // DIALOG CẤU HÌNH AUTO-BACKUP TÙY CHỌN CHẾ ĐỘ COPY/MOVE
    if (showAutoBackupDialog) {
        AlertDialog(
            onDismissRequest = { showAutoBackupDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sync, null, tint = Color(0xFF43A047), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cấu hình Auto-Backup", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("Tự động sao lưu ảnh lên NAS mỗi khi cắm sạc và có kết nối Wi-Fi.", fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))

                    // Công tắc Bật/Tắt Auto Backup
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { isAutoBackupEnabled = !isAutoBackupEnabled }) {
                        Switch(
                            checked = isAutoBackupEnabled,
                            onCheckedChange = { isAutoBackupEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00897B), checkedTrackColor = Color(0xFF80CBC4))
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(if (isAutoBackupEnabled) "Đang hoạt động ngầm" else "Đã tắt", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isAutoBackupEnabled) Color(0xFF00897B) else Color.Gray)
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                    Spacer(Modifier.height(16.dp))

                    Text("Chế độ sao lưu:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))

                    // Lựa chọn Copy
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { deleteAfterBackup = false }) {
                        RadioButton(
                            selected = !deleteAfterBackup,
                            onClick = { deleteAfterBackup = false },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00897B))
                        )
                        Column {
                            Text("Chỉ Sao lưu (Copy)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Giữ lại ảnh gốc trên điện thoại.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Lựa chọn Move
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { deleteAfterBackup = true }) {
                        RadioButton(
                            selected = deleteAfterBackup,
                            onClick = { deleteAfterBackup = true },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFE53935))
                        )
                        Column {
                            Text("Sao lưu & Giải phóng (Move)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                            Text("Tự động xóa ảnh trên điện thoại sau khi lên NAS.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            },
            confirmButton = {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Button(
                    onClick = {
                        // Lưu cài đặt
                        sharedPrefs.edit()
                            .putBoolean("auto_backup", isAutoBackupEnabled)
                            .putBoolean("delete_after_backup", deleteAfterBackup)
                            .apply()

                        // Áp dụng Worker
                        if (isAutoBackupEnabled) {
                            val constraints = androidx.work.Constraints.Builder()
                                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                                .setRequiresCharging(true)
                                .build()
                            val backupWorkRequest = androidx.work.PeriodicWorkRequestBuilder<AutoBackupWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                                .setConstraints(constraints)
                                .build()
                            androidx.work.WorkManager.getInstance(mContext).enqueueUniquePeriodicWork(
                                "AutoBackupWork",
                                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                                backupWorkRequest
                            )
                            commonDialogMessage = "Đã lưu cấu hình Auto-Backup!"
                            commonDialogIcon = Icons.Default.CheckCircle
                            commonDialogColor = Color(0xFF43A047)
                            showCommonDialog = true
                        } else {
                            androidx.work.WorkManager.getInstance(mContext).cancelUniqueWork("AutoBackupWork")
                        }

                        showAutoBackupDialog = false
                    },
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    modifier = Modifier.background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))),
                        shape = RoundedCornerShape(24.dp)
                    )
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Lưu", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showAutoBackupDialog = false }) { Text("Hủy", color = Color(0xFF00897B)) } },
            shape = RoundedCornerShape(16.dp)
        )
    }

// DIALOG CẤU HÌNH CẢNH BÁO TELEGRAM
    if (viewModel.showTelegramDialog) {
        // TỐI ƯU HÓA: Dùng tham số viewModel.tg... làm "chìa khóa" để ép giao diện tự động cập nhật ngay khi NAS trả về kết quả
        var tempToken by remember(viewModel.tgToken) { mutableStateOf(if (viewModel.tgToken.isBlank()) "7568182275:AAFqC4JsMxM6czS5jW-U71dxk5QzwNaf0Wo" else viewModel.tgToken) }
        var tempChatId by remember(viewModel.tgChatId) { mutableStateOf(if (viewModel.tgChatId.isBlank()) "8385294946" else viewModel.tgChatId) }
        var tempEnabled by remember(viewModel.tgEnabled) { mutableStateOf(viewModel.tgEnabled) }

        AlertDialog(
            onDismissRequest = { viewModel.showTelegramDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Send, null, tint = Color(0xFF29B6F6), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Trung tâm Cảnh báo", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("Nhận thông báo khẩn cấp (Nhiệt độ > 75°C, RAM > 90%, Ổ đĩa > 90%) trực tiếp qua Telegram.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = tempToken,
                        onValueChange = { tempToken = it },
                        label = { Text("Bot API Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempChatId,
                        onValueChange = { tempChatId = it },
                        label = { Text("Your Chat ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { tempEnabled = !tempEnabled }) {
                        Checkbox(
                            checked = tempEnabled,
                            onCheckedChange = { tempEnabled = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00897B))
                        )
                        Text("Kích hoạt tự động cảnh báo 24/7", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Button(
                    onClick = {
                        viewModel.saveTelegramConfig(tempToken, tempChatId, tempEnabled)
                        viewModel.showTelegramDialog = false
                    },
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    modifier = Modifier.background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Lưu cấu hình", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showTelegramDialog = false }) {
                    Text("Hủy", color = Color(0xFF00897B))
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

// DIALOG NHẬT KÝ HỆ THỐNG
    if (viewModel.showLogDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showLogDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Assignment, null, tint = Color(0xFF00ACC1), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Nhật ký chạy ngầm", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                if (viewModel.systemLogsList.isEmpty()) {
                    Text("Chưa có dữ liệu nhật ký nào.", modifier = Modifier.padding(16.dp), color = Color.Gray)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = viewModel.systemLogsList, key = { it.id }) { log ->
                            val logColor = when (log.type) {
                                "SUCCESS" -> Color(0xFF43A047)
                                "ERROR" -> Color(0xFFE53935)
                                "WARNING" -> Color(0xFFFB8C00)
                                else -> Color(0xFF1E88E5)
                            }
                            val logIcon = when (log.type) {
                                "SUCCESS" -> Icons.Default.CheckCircle
                                "ERROR" -> Icons.Default.Error
                                "WARNING" -> Icons.Default.Warning
                                else -> Icons.Default.Info
                            }
                            val timeStr = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                    Icon(logIcon, null, tint = logColor, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(log.module, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                            Text(timeStr, fontSize = 10.sp, color = Color.Gray)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(log.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.showLogDialog = false }) {
                    Text("Đóng", color = Color(0xFF00ACC1), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (viewModel.systemLogsList.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearSystemLogs() }) {
                        Text("Xóa lịch sử", color = Color.Red)
                    }
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
// DIALOG QUẢN LÝ DOCKER CONTAINER
    if (viewModel.showDockerDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showDockerDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ViewInAr, null, tint = Color(0xFF1E88E5), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Quản lý Docker", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (viewModel.isFetchingDocker) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF1E88E5), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { viewModel.fetchDockerContainers() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Refresh, "Làm mới", tint = Color.Gray)
                        }
                    }
                }
            },
            text = {
                if (viewModel.dockerContainers.isEmpty() && !viewModel.isFetchingDocker) {
                    Text("Không tìm thấy Container nào đang tồn tại.", modifier = Modifier.padding(16.dp), color = Color.Gray)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = viewModel.dockerContainers, key = { it.id }) { container ->
                            val isRunning = container.status.lowercase() == "running"
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Chấm trạng thái màu xanh/đỏ
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (isRunning) Color(0xFF43A047) else Color(0xFFE53935))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(container.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                        Text(if (isRunning) "Đang chạy" else "Đã dừng", fontSize = 11.sp, color = if (isRunning) Color(0xFF43A047) else Color.Gray)
                                    }

                                    // Cụm nút bấm tương tác (Play / Stop / Restart)
                                    if (isRunning) {
                                        IconButton(onClick = { viewModel.controlDockerContainer("restart", container.name) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.RestartAlt, "Khởi động lại", tint = Color(0xFFFB8C00), modifier = Modifier.size(20.dp))
                                        }
                                        IconButton(onClick = { viewModel.controlDockerContainer("stop", container.name) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Stop, "Dừng", tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                                        }
                                    } else {
                                        IconButton(onClick = { viewModel.controlDockerContainer("start", container.name) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.PlayArrow, "Bật", tint = Color(0xFF43A047), modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.showDockerDialog = false }) {
                    Text("Đóng", color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp) // TỐI ƯU UI: Giảm lề ngang để thẻ rộng rãi hơn
            .verticalScroll(androidx.compose.foundation.rememberScrollState()), // CHỐNG TRÀN: Kích hoạt cuộn dọc cho màn hình nhỏ
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top // Đẩy nội dung lên trên thay vì căn giữa
    ) {
        Spacer(Modifier.height(32.dp)) // Khoảng cách an toàn với mép trên màn hình
        Icon(
            Icons.Default.CloudQueue,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "Trung tâm dữ liệu NAS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 24.dp)
        )

        // THANH TÌM KIẾM TOÀN CẦU (GLOBAL SEARCH)
        var globalSearchQuery by remember { mutableStateOf("") }
        OutlinedTextField(
            value = globalSearchQuery,
            onValueChange = { globalSearchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            placeholder = { Text("Tìm kiếm nhanh toàn bộ NAS...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = {
                    if (globalSearchQuery.isNotBlank()) onGlobalSearch(globalSearchQuery)
                }
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            shape = RoundedCornerShape(12.dp)
        )
        // --- BẢNG ĐIỀU KHIỂN HỆ THỐNG NAS CHAINEDBOX (Real-time Local API) ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), // Ép sát thẻ Menu bên dưới
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) { // Giảm lề trong suốt từ 16 xuống 12
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trung tâm điều khiển Chainedbox", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp)) // Thu hẹp khoảng trống tiêu đề

                // Dòng 1: Nhiệt độ, CPU, RAM, Ổ đĩa (Bố cục 4 cột)
                Row(Modifier.fillMaxWidth()) {
                    SystemStatusItem("Nhiệt độ", viewModel.systemStatus.temp, Icons.Default.Thermostat, getStatusColor("Nhiệt độ", viewModel.systemStatus.temp), Modifier.weight(1f))
                    SystemStatusItem("CPU", viewModel.systemStatus.cpu, Icons.Default.DeveloperBoard, getStatusColor("CPU", viewModel.systemStatus.cpu), Modifier.weight(1f)) // Đổi sang DeveloperBoard cho CPU
                    SystemStatusItem("RAM", viewModel.systemStatus.ram.substringBefore(" ("), Icons.Default.Memory, getStatusColor("RAM", viewModel.systemStatus.ram, viewModel.systemStatus.ramPercent), Modifier.weight(1f)) // Giữ Memory (bộ nhớ) cho RAM
                    SystemStatusItem("Ổ đĩa", viewModel.systemStatus.disk, Icons.Default.Storage, getStatusColor("Ổ đĩa", viewModel.systemStatus.disk), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))

                // Dòng 2: Tải xuống, Tải lên, Uptime, Trạng thái (Bố cục 4 cột)
                Row(Modifier.fillMaxWidth()) {
                    SystemStatusItem("Tải xuống", viewModel.systemStatus.netRx, Icons.Default.Download, Color(0xFF1E88E5), Modifier.weight(1f))
                    SystemStatusItem("Tải lên", viewModel.systemStatus.netTx, Icons.Default.Upload, Color(0xFF8E24AA), Modifier.weight(1f))
                    SystemStatusItem("Uptime", viewModel.systemStatus.uptime, Icons.Default.Schedule, Color(0xFF00897B), Modifier.weight(1f))

                    val currentStatus = viewModel.systemStatus.status
                    val statusIcon = when {
                        currentStatus.contains("Chờ", ignoreCase = true) -> Icons.Default.Help
                        currentStatus.contains("Mất", ignoreCase = true) || currentStatus.contains("Lỗi", ignoreCase = true) || currentStatus.contains("Offline", ignoreCase = true) -> Icons.Default.Cancel
                        else -> Icons.Default.CheckCircle
                    }
                    val statusColor = when {
                        currentStatus.contains("Chờ", ignoreCase = true) -> Color(0xFF1E88E5) // Xanh dương
                        currentStatus.contains("Mất", ignoreCase = true) || currentStatus.contains("Lỗi", ignoreCase = true) || currentStatus.contains("Offline", ignoreCase = true) -> Color(0xFFE53935) // Đỏ
                        else -> Color(0xFF43A047) // Xanh lá
                    }
                    SystemStatusItem("Trạng thái", currentStatus, statusIcon, statusColor, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                Spacer(Modifier.height(4.dp))

                // HIỂN THỊ TIẾN TRÌNH TORRENT ĐANG TẢI (Tối đa 3 mục)
                if (viewModel.systemStatus.torrents.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Đang tải xuống (${viewModel.systemStatus.torrents.size}) - Nhấn giữ để quản lý", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    viewModel.systemStatus.torrents.take(3).forEach { torrent ->
                        var showTorrentMenu by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(Unit) {
                                        detectTapGestures(onLongPress = { showTorrentMenu = true })
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(torrent.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(torrent.speed, fontSize = 10.sp, color = Color(0xFF1E88E5), modifier = Modifier.padding(start = 8.dp))
                                }
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { torrent.progress },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp),
                                    color = Color(0xFF43A047),
                                    trackColor = Color.LightGray.copy(alpha = 0.5f)
                                )
                            }

                            DropdownMenu(expanded = showTorrentMenu, onDismissRequest = { showTorrentMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Tạm dừng (Pause)") },
                                    leadingIcon = { Icon(Icons.Default.Pause, null, tint = Color(0xFFFB8C00)) },
                                    onClick = { showTorrentMenu = false; viewModel.controlTorrent("pause", torrent.hash) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Tiếp tục (Resume)") },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF43A047)) },
                                    onClick = { showTorrentMenu = false; viewModel.controlTorrent("resume", torrent.hash) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Xóa (Delete)", color = Color.Red) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) },
                                    onClick = { showTorrentMenu = false; viewModel.controlTorrent("delete", torrent.hash) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                    Spacer(Modifier.height(4.dp))
                }

                // TRUNG TÂM ĐIỀU KHIỂN: Sắp xếp 4 cột đồng bộ với các thẻ thông số
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { showRebootConfirm = true }, // KÍCH HOẠT DIALOG XÁC NHẬN THAY VÌ CHẠY TRỰC TIẾP
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() } // Xóa bỏ hiệu ứng gợn sóng nền xám
                        ) {
                            Icon(Icons.Default.RestartAlt, null, tint = Color(0xFFFB8C00), modifier = Modifier.size(22.dp))
                        }
                        Text("Reboot", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { showShutdownConfirm = true }, // KÍCH HOẠT DIALOG XÁC NHẬN THAY VÌ CHẠY TRỰC TIẾP
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() } // Xóa bỏ hiệu ứng gợn sóng nền xám
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, null, tint = Color(0xFFE53935), modifier = Modifier.size(22.dp))
                        }
                        Text("Shutdown", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // BỔ SUNG: Nút kiểm tra sức khỏe ổ cứng S.M.A.R.T
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                viewModel.fetchSmartData()
                                viewModel.loadLastAutoSpeedTest(mContext) // Tải lên dữ liệu đo ngầm mới nhất
                                viewModel.showSmartDialog = true
                            },
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) {
                            Icon(Icons.Default.HealthAndSafety, null, tint = Color(0xFF43A047), modifier = Modifier.size(22.dp))
                        }
                        Text("Khám đĩa", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // BỔ SUNG: Nút Quản lý Docker Container cấp cao
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                viewModel.fetchDockerContainers()
                                viewModel.showDockerDialog = true
                            },
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) {
                            Icon(Icons.Default.ViewInAr, null, tint = Color(0xFF1E88E5), modifier = Modifier.size(22.dp))
                        }
                        Text("Docker", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
// TÍNH NĂNG BẢO MẬT SINH TRẮC HỌC
        var isBiometricEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("biometric_enabled", false)) }
        MenuCard(
            title = "Khóa Sinh trắc học",
            subtitle = "Yêu cầu Vân tay/FaceID khi mở ứng dụng",
            icon = Icons.Default.Lock,
            color = if (isBiometricEnabled) Color(0xFF8E24AA) else Color.Gray,
            checked = isBiometricEnabled,
            onClick = {
                val newValue = !isBiometricEnabled
                sharedPrefs.edit().putBoolean("biometric_enabled", newValue).apply()
                isBiometricEnabled = newValue

                commonDialogMessage = if (newValue) "Đã BẬT khóa bảo mật Sinh trắc học!" else "Đã TẮT khóa bảo mật Sinh trắc học!"
                commonDialogIcon = Icons.Default.Lock
                commonDialogColor = Color(0xFF8E24AA)
                showCommonDialog = true
            }
        )
        Spacer(Modifier.height(16.dp))

// TÍNH NĂNG TỰ ĐỘNG SAO LƯU (AUTO-BACKUP)
        MenuCard(
            title = "Auto-Backup",
            subtitle = if (deleteAfterBackup) "Sao lưu & Giải phóng bộ nhớ (Move)" else "Chỉ sao lưu (Copy)",
            icon = Icons.Default.Sync,
            color = if (isAutoBackupEnabled) Color(0xFF43A047) else Color.Gray,
            checked = isAutoBackupEnabled,
            onClick = { showAutoBackupDialog = true }
        )
        Spacer(Modifier.height(16.dp))
// MỤC MỚI: Cảnh báo Telegram
        MenuCard(
            title = "Cảnh báo Telegram",
            subtitle = "Gửi thông báo khi NAS quá nhiệt hoặc lỗi",
            icon = Icons.Default.Send,
            color = if (viewModel.tgEnabled) Color(0xFF29B6F6) else Color.Gray,
            checked = viewModel.tgEnabled,
            onClick = {
                viewModel.fetchTelegramConfig()
                viewModel.showTelegramDialog = true
            }
        )

        Spacer(Modifier.height(16.dp))

        // MỤC MỚI: Nhật ký hệ thống (Audit Log)
        MenuCard(
            title = "Nhật ký hệ thống",
            subtitle = "Xem lịch sử các tiến trình chạy ngầm",
            icon = Icons.Default.Assignment,
            color = Color(0xFF00ACC1),
            onClick = { viewModel.loadSystemLogs() }
        )

        Spacer(Modifier.height(16.dp))

        // Mục 1: NAS File
        MenuCard(
            title = "NAS File",
            subtitle = "Truy cập tệp tin và thư mục",
            icon = Icons.Default.Folder,
            color = Color(0xFFFFCA28),
            onClick = onOpenFiles
        )

        Spacer(Modifier.height(16.dp))
// Mục: Ảnh mới nhất
        MenuCard(
            title = "Ảnh mới nhất",
            subtitle = "Bộ sưu tập hình ảnh vừa quét",
            icon = Icons.Default.Collections,
            color = Color(0xFF42A5F5),
            onClick = onOpenLatestPhotos
        )

        Spacer(Modifier.height(16.dp))

        // Mục: Video gần đây
        MenuCard(
            title = "Video gần đây",
            subtitle = "Các thước phim mới nhất",
            icon = Icons.Default.VideoLibrary,
            color = Color(0xFF66BB6A),
            onClick = onOpenRecentVideos
        )

        Spacer(Modifier.height(16.dp))
        // Mục 2: Thùng rác
        MenuCard(
            title = "Thùng rác",
            subtitle = "Xem và khôi phục file đã xóa",
            icon = Icons.Default.Delete,
            color = Color(0xFFEF5350),
            onClick = onOpenTrash
        )

        // HIỂN THỊ THÔNG BÁO DẠNG DIALOG CÓ ICON
        if (showCommonDialog) {
            NotificationDialog(
                title = "Thông báo hệ thống",
                message = commonDialogMessage,
                icon = commonDialogIcon,
                iconColor = commonDialogColor,
                onDismiss = { showCommonDialog = false }
            )
        }
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = onLogout) {
            Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Color.Red)
            Spacer(Modifier.width(8.dp))
            Text("Đăng xuất tài khoản", color = Color.Red, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
fun SystemStatusItem(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    // ÉP CÂN UI: Giảm kích thước icon và text để thông số gọn gàng hơn
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = title, fontSize = 10.sp, color = Color.Gray, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
// HÀM TÍNH TOÁN MÀU SẮC THÔNG MINH DỰA TRÊN THÔNG SỐ
fun getStatusColor(title: String, value: String, rawPercent: String = ""): Color {
    try {
        // TỐI ƯU HÓA: Dùng Regex chỉ giữ lại số và dấu chấm, tránh lỗi parse khi chuỗi API trả về có chứa dấu cách thừa
        val extractNumber = { str: String -> Regex("[^0-9.]").replace(str, "").toFloatOrNull() ?: 0f }

        return when (title) {
            "Nhiệt độ" -> {
                val t = extractNumber(value)
                when {
                    t >= 75f -> Color(0xFFE53935) // Đỏ (Quá nhiệt)
                    t >= 60f -> Color(0xFFFB8C00) // Cam (Nóng)
                    t > 0f -> Color(0xFF43A047)   // Xanh (Mát)
                    else -> Color.Gray            // Xám nếu chưa load được
                }
            }
            "CPU", "Ổ đĩa" -> {
                val p = extractNumber(value)
                when {
                    p >= 90f -> Color(0xFFE53935) // Đỏ (Quá tải)
                    p >= 75f -> Color(0xFFFB8C00) // Cam (Cảnh báo)
                    p > 0f -> Color(0xFF43A047)   // Xanh (Ổn định)
                    else -> Color.Gray
                }
            }
            "RAM" -> {
                val p = if (rawPercent.isNotBlank()) extractNumber(rawPercent) else extractNumber(value)
                when {
                    p >= 90f -> Color(0xFFE53935)
                    p >= 75f -> Color(0xFFFB8C00)
                    p > 0f -> Color(0xFF43A047)
                    else -> Color.Gray
                }
            }
            "Trạng thái" -> if (value == "Online") Color(0xFF43A047) else Color(0xFFE53935)
            else -> Color.Gray
        }
    } catch (e: Exception) {
        return Color.Gray
    }
}
@Composable
fun MenuCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    checked: Boolean? = null, // Thêm thông số nhận diện công tắc
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp) // ÉP CÂN MENU: Giảm từ 80dp xuống 68dp để mỏng nhẹ và hiện đại hơn
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp), // Thu hẹp lề trong
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp) // Nhỏ nền icon
                    .background(color.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) // Nhỏ icon
            }
            Spacer(Modifier.width(12.dp))
            // Dùng Modifier.weight(1f) để cột Text chiếm hết không gian rảnh, ép Switch sang sát mép phải
            Column(modifier = Modifier.weight(1f)) {
                // Tối ưu cỡ chữ để vừa vặn trong thẻ mỏng
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Vẽ Switch nếu tính năng này có thể Bật/Tắt
            if (checked != null) {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = { onClick() }, // Chạm vào Switch cũng mở hộp thoại/chuyển đổi y hệt như chạm vào thẻ
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00897B),
                        checkedTrackColor = Color(0xFF80CBC4)
                    ),
                    modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f } // Thu nhỏ Switch lại cho thanh thoát với thẻ mỏng
                )
            }
        }
    }
}
// --- BROWSER SCREEN ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: WebDavViewModel,
    onVideo: (String) -> Unit,
    onImage: (String) -> Unit,
    onLogout: () -> Unit,
    onBackToMenu: () -> Unit // Thêm tham số này
) {
    // Trạng thái thanh tìm kiếm
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // XỬ LÝ NÚT BACK THÔNG MINH: Đồng bộ giữa Folder và Sub-menu
    // BACK CỨNG THÔNG MINH: Luôn ưu tiên quay về Main Menu
    BackHandler {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else if (viewModel.isSpecialMode) {
            onBackToMenu() // Quay về Menu từ Ảnh/Video/Rác
        } else {
            // Thử lùi thư mục, nếu đang ở gốc thì về Menu
            if (!viewModel.goBack()) {
                onBackToMenu()
            }
        }
    }

    val animatedProgress by animateFloatAsState(targetValue = viewModel.imageLoadProgress, animationSpec = tween(500), label = "Progress")

    // Trạng thái hiển thị hộp thoại tạo thư mục
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
// Trạng thái hiển thị menu 3 chấm trên TopAppBar
    var showMoreMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Khởi tạo Trình chọn THƯ MỤC để Đồng bộ (Sync)
    val syncFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.syncLocalFolder(context, uri)
        }
    }

    // Khởi tạo Trình chọn tệp (File Picker) của hệ thống Android
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.uploadFile(context, uri)
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Tạo thư mục mới") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Tên thư mục") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        viewModel.createFolder(newFolderName)
                        newFolderName = "" // Reset tên
                    }
                    showCreateFolderDialog = false
                }) { Text("Tạo") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Hủy") } }
        )
    }
    // 2. Hộp thoại Quét Rác độc lập (Sửa lỗi nút Chạy ngầm và thêm % ProgressBar)
    if (viewModel.isScanningDuplicates) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Trình quét rác hệ thống", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    // Hiển thị trực tiếp vì dữ liệu từ Worker đã được giải mã chuẩn xác
                    val decodedPath = viewModel.scanDuplicatesCurrentFolderUrl

                    Text(text = "Đang lướt qua: $decodedPath", color = Color(0xFF5C6BC0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))

                    Text(text = if (viewModel.scanDuplicatesIsFolder) "Thư mục:" else "Tệp tin:", fontSize = 12.sp)
                    Text(text = viewModel.scanDuplicatesCurrentItemName, color = if (viewModel.scanDuplicatesIsFolder) Color(0xFF5C6BC0) else Color(0xFFEF6C00), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    Spacer(Modifier.height(16.dp))

                    // HIỂN THỊ % TIẾN TRÌNH THỰC TẾ
                    val progressValue = viewModel.scanDuplicatesPercent
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { progressValue.coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                            color = Color(0xFF2196F3)
                        )
                        Text("${(progressValue * 100).toInt()}%", modifier = Modifier.padding(start = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Đã quét: ${viewModel.scanDuplicatesTotalScanned}", fontSize = 12.sp)
                        Text("Trùng: ${viewModel.scanDuplicatesFound}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Tắt hộp thoại, nhưng Worker vẫn chạy ngầm
                    viewModel.isScanningDuplicates = false
                }) {
                    Text(if (viewModel.isWorkerRunning) "Chạy ngầm" else "Đóng")
                }
            }
        )
    }
// Hộp thoại Hiển thị danh sách File Trùng Lặp
    if (viewModel.isShowingDuplicates) {
        AlertDialog(
            onDismissRequest = { viewModel.isShowingDuplicates = false },
            title = {
                Column {
                    Text("Danh sách file trùng lặp", style = MaterialTheme.typography.titleMedium, color = Color.Red)
                    // BỔ SUNG: Hiển thị tổng số file rác phát hiện được nếu danh sách không trống
                    if (viewModel.duplicateFilesList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Đã phát hiện ${viewModel.duplicateFilesList.size} file rác",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            text = {
                if (viewModel.duplicateFilesList.isEmpty()) {
                    Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = Color.Green)
                } else {
                    // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                    val groupedDuplicates = remember(viewModel.duplicateFilesList) {
                        viewModel.duplicateFilesList.groupBy { it.contentLength }.values.toList()
                    }

                    Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                        // Nút Tự động chọn thông minh (Giữ lại 1 bản, tick chọn xóa các bản copy)
                        TextButton(
                            onClick = {
                                viewModel.selectedDuplicates.clear()
                                groupedDuplicates.forEach { group ->
                                    // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                    // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                    val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                    viewModel.selectedDuplicates.addAll(filesToDelete)
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF2196F3))
                            Spacer(Modifier.width(4.dp))
                            Text("Tự động chọn bản sao", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                        }

                        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                            items(items = groupedDuplicates, key = { it.first().contentLength }) { group ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Nhóm ${group.size} tệp trùng lặp (${group.first().contentLength / 1024} KB)",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        // Hiển thị danh sách file trong nhóm bằng Cuộn Ngang (LazyRow)
                                        androidx.compose.foundation.lazy.LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(items = group, key = { it.path }) { dupFile ->
                                                val isSelected = viewModel.selectedDuplicates.contains(dupFile)
                                                val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                                val isVideo = dupFile.name.lowercase().run { endsWith(".mp4") || endsWith(".mkv") || endsWith(".avi") || endsWith(".mov") }
                                                val auth = remember { okhttp3.Credentials.basic(viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) }

                                                Box(
                                                    modifier = Modifier
                                                        .size(110.dp) // Kích thước Thumbnail to rõ ràng
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) Color.Red.copy(alpha = 0.2f) else Color.Black)
                                                        .clickable {
                                                            if (isSelected) viewModel.selectedDuplicates.remove(dupFile)
                                                            else viewModel.selectedDuplicates.add(dupFile)
                                                        }
                                                ) {
                                                    // 1. Lớp Ảnh Nền
                                                    if (isImage) {
                                                        coil.compose.AsyncImage(
                                                            model = coil.request.ImageRequest.Builder(LocalContext.current)
                                                                .data(dupFile.path)
                                                                .addHeader("Authorization", auth)
                                                                .build(),
                                                            contentDescription = null,
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                    } else if (isVideo) {
                                                        Box(modifier = Modifier.fillMaxSize()) {
                                                            WebDavVideoThumbnail(url = dupFile.path, auth = auth, modifier = Modifier.fillMaxSize())
                                                        }
                                                    } else {
                                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                                    }

                                                    // 2. Lớp phủ đỏ mờ nếu đang được tick chọn xóa
                                                    if (isSelected) {
                                                        Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.4f)))
                                                    }

                                                    // 3. Checkbox nằm góc trên phải
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = {
                                                            if (it) viewModel.selectedDuplicates.add(dupFile)
                                                            else viewModel.selectedDuplicates.remove(dupFile)
                                                        },
                                                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                        colors = CheckboxDefaults.colors(checkedColor = Color.Red, uncheckedColor = Color.White)
                                                    )

                                                    // 4. Đường dẫn thư mục đè ở dưới cùng (Để phân biệt các file)
                                                    val parentFolder = dupFile.path.substringBeforeLast("/").substringAfterLast("/")
                                                    Text(
                                                        text = parentFolder,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .fillMaxWidth()
                                                            .background(Color.Black.copy(alpha = 0.7f))
                                                            .padding(horizontal = 4.dp, vertical = 4.dp),
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    // Hiển thị nút Xóa hàng loạt màu đỏ nổi bật nếu có file đang được tick
                    if (viewModel.selectedDuplicates.isNotEmpty()) {
                        TextButton(onClick = { viewModel.deleteSelectedDuplicates() }) {
                            Text("Xóa (${viewModel.selectedDuplicates.size}) mục", color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = {
                        viewModel.isShowingDuplicates = false
                        viewModel.selectedDuplicates.clear() // Xóa danh sách tick chọn tạm thời khi đóng hộp thoại
                    }) { Text("Đóng") }
                }
            }
        )
    }

    // TÍNH NĂNG MỚI: THANH TIẾN TRÌNH NỔI (FLOATING TRANSFER BAR) KHÔNG CHẶN MÀN HÌNH
    Scaffold(
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(
                visible = viewModel.isUploading,
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f).padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { if (viewModel.uploadTotalBytes > 0) viewModel.uploadBytes.toFloat() / viewModel.uploadTotalBytes.toFloat() else 0f },
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                                strokeWidth = 4.dp
                            )
                            Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (viewModel.isSyncing) viewModel.syncStatusText else "Đang tải lên NAS...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = viewModel.uploadFileName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        IconButton(onClick = { viewModel.isUploading = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Ẩn", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        },
        topBar = {
            if (isSearching) {
            TopAppBar(
                title = {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Tìm kiếm tệp...", color = Color.Gray) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Đóng")
                    }
                },
                actions = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Xóa")
                        }
                    }
                }
            )
        } else {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.isSpecialMode) onBackToMenu()
                        else if (!viewModel.goBack()) onBackToMenu()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                title = {
                    val displayTitle = if (viewModel.isSpecialMode) viewModel.specialTitle
                    else "NAS: ${try { java.net.URI(viewModel.currentUrl).path } catch(e:Exception) { "" }}"
                    Column {
                        Text(displayTitle, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    // Thiết kế Chip trạng thái kết nối
                    val rawStatus = viewModel.connectionStatus
                        val displayStatus = if (rawStatus.contains("Cache", ignoreCase = true)) "Cache" else rawStatus

                        val chipIcon = when {
                            displayStatus.contains("Chờ", ignoreCase = true) -> Icons.Default.Help
                            displayStatus.contains("Lỗi", ignoreCase = true) || displayStatus.contains("Mất", ignoreCase = true) -> Icons.Default.Cancel
                            else -> Icons.Default.CheckCircle
                        }

                        val chipColor = when {
                            displayStatus.contains("Chờ", ignoreCase = true) -> Color(0xFF1E88E5) // Xanh dương
                            displayStatus.contains("Lỗi", ignoreCase = true) || displayStatus.contains("Mất", ignoreCase = true) -> Color(0xFFE53935) // Đỏ
                            displayStatus.contains("Cache", ignoreCase = true) -> Color(0xFFF57C00) // Cam
                            else -> Color(0xFF43A047) // Xanh lá
                        }

                        Surface(
                            color = chipColor.copy(alpha = 0.15f), // Nền mờ bao quanh tinh tế hơn
                            shape = RoundedCornerShape(percent = 50),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = chipIcon,
                                    contentDescription = null,
                                    tint = chipColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = displayStatus,
                                    color = chipColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
            }, actions = {
                // Giữ lại 2 nút quan trọng nhất hiển thị trực tiếp
                IconButton(onClick = { isSearching = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Tìm kiếm")
                }
                IconButton(onClick = { uploadLauncher.launch("*/*") }) {
                    Icon(Icons.Default.Upload, contentDescription = "Tải tệp lên")
                }

                // Gom các nút còn lại vào Menu 3 chấm để giải phóng không gian màn hình
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Tùy chọn khác")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Đồng bộ thư mục") },
                            leadingIcon = { Icon(Icons.Default.Sync, null) },
                            onClick = { showMoreMenu = false; syncFolderLauncher.launch(null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Tìm file trùng lặp") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { showMoreMenu = false; viewModel.startBackgroundDuplicateScan(context) }
                        )
                        DropdownMenuItem(
                            text = { Text("Tạo thư mục") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { showMoreMenu = false; showCreateFolderDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Làm mới") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { showMoreMenu = false; viewModel.refresh() }
                        )
                        DropdownMenuItem(
                            text = { Text("Ảnh ngẫu nhiên") },
                            leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                            onClick = {
                                showMoreMenu = false
                                val images = viewModel.fileList.filter { it.name.lowercase().run { endsWith(".jpg") || endsWith(".png") } }
                                images.randomOrNull()?.let { onImage(it.path) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Đăng xuất", color = Color.Red) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Color.Red) },
                            onClick = { showMoreMenu = false; onLogout() }
                        )
                    }
                }
            })
        }
    }) { padding ->
        // Logic lọc danh sách file theo từ khóa tìm kiếm (bỏ qua viết hoa/viết thường)
        val displayedFiles = remember(viewModel.fileList, searchQuery) {
            if (searchQuery.isBlank()) {
                viewModel.fileList
            } else {
                viewModel.fileList.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

        // KIẾN TRÚC MỚI: Khởi tạo dữ liệu Paging 3 (Thu thập luồng)
        // FIX LỖI TYPE MISMATCH: Giải nén StateFlow thành Flow cơ bản trước khi nạp vào Paging
        val currentPagingFlow by viewModel.pagedFilesFlow.collectAsState()
        val pagedFiles = currentPagingFlow.collectAsLazyPagingItems()

        Column(Modifier.fillMaxSize().padding(padding)) {
            if (animatedProgress > 0f && animatedProgress < 1f) LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())

            // TÍNH NĂNG MỚI: Trạng thái Kéo để làm mới (Pull-to-Refresh) CHUẨN ĐỒNG BỘ
            val pullToRefreshState = rememberPullToRefreshState()

            // 1. Kích hoạt tải dữ liệu khi người dùng kéo xuống
            if (pullToRefreshState.isRefreshing) {
                LaunchedEffect(true) {
                    viewModel.refresh()
                }
            }

            // 2. Tự động thu hồi vòng xoay mượt mà khi ViewModel tải xong
            LaunchedEffect(viewModel.isLoading) {
                if (!viewModel.isLoading && pullToRefreshState.isRefreshing) {
                    pullToRefreshState.endRefresh()
                }
            }

            // SỬA LỖI: Sử dụng trực tiếp nestedScroll() sau khi đã import
            Box(Modifier.fillMaxSize().nestedScroll(pullToRefreshState.nestedScrollConnection)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Ưu tiên hiển thị file tìm kiếm/đặc biệt nếu có, ngược lại dùng Paging 3
                    if (isSearching || viewModel.isSpecialMode) {
                        items(items = displayedFiles, key = { it.path }) { file ->
                            FileItemGridCell(file = file, viewModel = viewModel, onClick = {
                                if (file.isDirectory) viewModel.openFolder(file)
                                else if (file.name.lowercase().run { endsWith(".mp4") || endsWith(".mkv") || endsWith(".avi") || endsWith(".mov") }) onVideo(file.path)
                                else if (file.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }) onImage(file.path)
                            })
                        }
                    } else {
                        // TỐI ƯU HÓA RENDER PAGING 3: Tự động nạp/huỷ file khi cuộn qua
                        items(
                            count = pagedFiles.itemCount,
                            key = pagedFiles.itemKey { it.path },
                            contentType = pagedFiles.itemContentType { "NasFile" }
                        ) { index ->
                            val file = pagedFiles[index]
                            if (file != null) {
                                FileItemGridCell(file = file, viewModel = viewModel, onClick = {
                                    if (file.isDirectory) viewModel.openFolder(file)
                                    else if (file.name.lowercase().run { endsWith(".mp4") || endsWith(".mkv") || endsWith(".avi") || endsWith(".mov") }) onVideo(file.path)
                                    else if (file.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }) onImage(file.path)
                                })
                            }
                        }
                    }
                }

                // Vòng xoay khi người dùng vuốt từ trên xuống (Đã phối màu Xanh Ngọc đồng bộ)
                PullToRefreshContainer(
                    state = pullToRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color(0xFF00897B) // Trùng màu Mesh Gradient
                )

                // Chỉ hiện vòng xoay giữa màn hình khi KHÔNG PHẢI đang kéo tay làm mới
                if (viewModel.isLoading && !pullToRefreshState.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFF00897B) // Đồng bộ màu Xanh Ngọc
                    )
                }
                if (!viewModel.errorMessage.isNullOrEmpty()) {
                    Text(viewModel.errorMessage!!, color = Color.Red, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }

        // LẮNG NGHE VÀ HIỂN THỊ DIALOG TỪ VIEWMODEL TRÊN BROWSER SCREEN
        if (viewModel.showCommonDialog) {
            NotificationDialog(
                title = "Thông báo hệ thống",
                message = viewModel.commonDialogMessage,
                icon = viewModel.commonDialogIcon,
                iconColor = viewModel.commonDialogColor,
                onDismiss = { viewModel.showCommonDialog = false }
            )
        }
    }
}

// --- FILE ITEM GRID CELL ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemGridCell(file: NasFile, viewModel: WebDavViewModel, onClick: () -> Unit) {
    val isVideo = file.name.lowercase().run { endsWith(".mp4") || endsWith(".mkv") || endsWith(".avi") || endsWith(".mov") }
    val isImage = file.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
    val isMedia = isVideo || isImage
    val auth = remember { Credentials.basic(viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) }

    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf(file.name) }

    // STATE CHO DIALOG THÔNG BÁO TẠI ĐÂY (THAY THẾ TOAST)
    var commonDialogMessage by remember { mutableStateOf("") }
    var commonDialogIcon by remember { mutableStateOf(Icons.Default.Info) }
    var commonDialogColor by remember { mutableStateOf(Color.Gray) }
    var showCommonDialog by remember { mutableStateOf(false) }

    if (showCommonDialog) {
        NotificationDialog(
            title = "Thông báo",
            message = commonDialogMessage,
            icon = commonDialogIcon,
            iconColor = commonDialogColor,
            onDismiss = { showCommonDialog = false }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Xác nhận xóa") },
            text = { Text("Bạn có chắc chắn muốn xóa '${file.name}' không? Hành động này không thể hoàn tác trên NAS.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; viewModel.deleteFile(file) }) { Text("Xóa", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Hủy") } }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Đổi tên tệp") },
            text = {
                OutlinedTextField(value = newFileName, onValueChange = { newFileName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank() && newFileName != file.name) viewModel.renameFile(file, newFileName)
                    showRenameDialog = false
                }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Hủy") } }
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally // CHUẨN HOÁ: Căn giữa mọi thứ trong Cột
    ) {
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("Tải về máy") }, onClick = {
                showMenu = false
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(file.path))
                    .setTitle(file.name)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, file.name)
                    .addRequestHeader("Authorization", auth)
                (context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(request)

                commonDialogIcon = Icons.Default.Download
                commonDialogColor = Color(0xFF1E88E5)
                commonDialogMessage = "Đã bắt đầu tải về: ${file.name}"
                showCommonDialog = true
            })
            DropdownMenuItem(text = { Text("Sao chép liên kết") }, onClick = {
                showMenu = false
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NAS Link", file.path))

                commonDialogIcon = Icons.Default.ContentCopy
                commonDialogColor = Color(0xFF43A047)
                commonDialogMessage = "Đã sao chép liên kết tệp!"
                showCommonDialog = true
            })
            // Chỉ hiện nút Khôi phục nếu đang đứng trong Thùng rác
            if (viewModel.isSpecialMode && viewModel.specialTitle == "Thùng rác") {
                DropdownMenuItem(text = { Text("Khôi phục tệp") }, onClick = {
                    showMenu = false
                    viewModel.restoreFile(file)
                })
            }

            // TÍNH NĂNG MỚI: Giải nén tại NAS
            if (file.name.lowercase().endsWith(".zip")) {
                DropdownMenuItem(
                    text = { Text("Giải nén tại NAS", color = Color(0xFF8E24AA), fontWeight = FontWeight.Bold) },
                    onClick = {
                        showMenu = false
                        viewModel.unzipFile(file.path)
                    }
                )
            }

            DropdownMenuItem(text = { Text("Đổi tên") }, onClick = { showMenu = false; newFileName = file.name; showRenameDialog = true })
            DropdownMenuItem(text = { Text("Xóa tệp", color = Color.Red) }, onClick = { showMenu = false; showDeleteDialog = true })
        }

        // --- KHUNG HIỂN THỊ CHÍNH (Đồng bộ tuyệt đối 1:1 cho mọi loại File) ---
        Box(
            contentAlignment = Alignment.BottomCenter, // BƯỚC 1: Căn đáy để Icon thư mục bị ép sát xuống dưới cùng
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f) // Vẫn giữ khung vuông để ảnh và thư mục cao bằng nhau (không bị lệch dòng)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        file.isDirectory -> Color.Transparent // BƯỚC 2: XÓA NỀN màu be để loại bỏ hoàn toàn cảm giác khoảng trống
                        isMedia -> Color.DarkGray
                        else -> Color(0xFFF5F5F5)
                    }
                )
        ) {
            if (isMedia) {
                if (isVideo) {
                    WebDavVideoThumbnail(url = file.path, auth = auth, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(LocalContext.current).data(file.path).addHeader("Authorization", auth).crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // Nhãn MP4/JPG góc dưới phải
                val ext = file.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() } ?: "FILE"
                val sizeMb = file.contentLength / (1024.0 * 1024.0)
                val displaySize = if (sizeMb < 1) "${file.contentLength / 1024} KB" else String.format("%.1f MB", sizeMb)

                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ext, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text(displaySize, color = Color.LightGray, fontSize = 8.sp)
                }
            } else {
                // Hiển thị Folder và File thường
                val icon = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile
                val tint = if (file.isDirectory) Color(0xFFFFCA28) else Color.Gray

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    // BƯỚC 3: Phóng to Icon (lên 80%). Nhờ lệnh Alignment.BottomCenter ở trên, đáy của Icon sẽ chạm rịt vào đáy Box!
                    modifier = Modifier.fillMaxSize(0.8f)
                )

                if (!file.isDirectory) {
                    val sizeMb = file.contentLength / (1024.0 * 1024.0)
                    val displaySize = if (sizeMb < 1) "${file.contentLength / 1024} KB" else String.format("%.1f MB", sizeMb)
                    Text(
                        text = displaySize,
                        fontSize = 9.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (file.isDirectory) {
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = file.name,
                maxLines = 2, // Cho phép xuống tối đa 2 dòng
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp, // Kích thước chữ vừa vặn
                    lineHeight = 14.sp, // Khoảng cách giữa 2 dòng
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center // CĂN GIỮA cho đẹp
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
// --- VIDEO THUMBNAIL TỐI ƯU HOÁ ---
private val videoThumbClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 100; maxRequestsPerHost = 30 })
        .build()
}

@Composable
fun WebDavVideoThumbnail(url: String, auth: String, modifier: Modifier) {
    val context = LocalContext.current
    var localThumbPath by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val thumbFile = File(context.cacheDir, "thumb_${url.hashCode()}.jpg")
                if (thumbFile.exists() && thumbFile.length() > 0) {
                    localThumbPath = thumbFile.absolutePath
                    return@withContext
                }

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", auth)
                    .header("Range", "bytes=0-2097152")
                    .build()

                videoThumbClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val tempVideo = File.createTempFile("temp_vid_${url.hashCode()}", ".mp4", context.cacheDir)
                        try {
                            response.body!!.byteStream().use { input ->
                                FileOutputStream(tempVideo).use { output ->
                                    input.copyTo(output)
                                }
                            }

                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(tempVideo.absolutePath)
                                val seed = url.hashCode().toLong()
                                val randomTimeUs = (java.util.Random(seed).nextInt(2000) + 1000) * 1000L

                                var bitmap = retriever.getFrameAtTime(randomTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                if (bitmap == null) {
                                    bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                }

                                if (bitmap != null) {
                                    FileOutputStream(thumbFile).use { out ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                    }
                                    localThumbPath = thumbFile.absolutePath
                                } else {
                                    isError = true
                                }
                            } finally {
                                retriever.release()
                            }
                        } finally {
                            tempVideo.delete()
                        }
                    } else {
                        isError = true
                    }
                }
            } catch (e: Exception) {
                isError = true
            }
        }
    }

    if (localThumbPath != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(File(localThumbPath!!)).crossfade(true).build(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (isError) {
                Icon(Icons.Default.PlayCircle, null, tint = Color.LightGray, modifier = Modifier.size(32.dp))
            } else {
                CircularProgressIndicator(color = Color(0xFF2196F3), modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
fun LoginScreen(viewModel: WebDavViewModel, onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    // BẢO MẬT: Mở kho lưu trữ EncryptedSharedPreferences (Chuẩn API 1.0.0 Stable)
    val prefs = remember {
        val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
        androidx.security.crypto.EncryptedSharedPreferences.create(
            "NasSecurePrefs",
            masterKeyAlias,
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Đọc dữ liệu đã lưu từ lần mở app trước (nếu có)
    var url by remember { mutableStateOf(prefs.getString("nas_url", "http://192.168.1.1:8080/webdav/") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("nas_user", "admin") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("nas_pass", "") ?: "") }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Storage, contentDescription = "NAS", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Kết nối máy chủ NAS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Đường dẫn (WebDAV URL)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Tài khoản (Username)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Mật khẩu (Password)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation() // Ẩn mật khẩu dạng dấu sao
        )
        Spacer(Modifier.height(24.dp))
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Button(
            onClick = {
                // 1. Lưu đè thông tin mới nhất vào bộ nhớ điện thoại
                prefs.edit()
                    .putString("nas_url", url)
                    .putString("nas_user", user)
                    .putString("nas_pass", pass)
                    .apply()

                // 2. SỬA LỖI: Gọi đúng hàm connectAndLoad gốc để không bị mất Base URL
                viewModel.connectAndLoad(url, user, pass)

                // Kích hoạt tính năng lập lịch tự động quét rác ngầm khi rảnh rỗi
                viewModel.scheduleIdleDuplicateScan(context)

                // Kích hoạt tính năng đo tốc độ ổ cứng ngầm khi rảnh rỗi
                viewModel.scheduleIdleSpeedTest(context)

                onLoginSuccess()
            },
            interactionSource = interactionSource, // Loại bỏ hiệu ứng nền xám mặc định
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4)) // Hiệu ứng Mesh Gradient Xanh Ngọc
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Text("Kết nối an toàn", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, user: String, pass: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // Trạng thái theo dõi chế độ Popup (PiP)
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

    // Khởi tạo ExoPlayer và cấu hình kết nối WebDAV
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to Credentials.basic(user, pass)))

            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))

            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    // Tích hợp MediaSession để hệ thống Android nhận diện và cung cấp nút Play/Pause/Tua cho Popup
    val mediaSession = remember {
        MediaSession.Builder(context, exoPlayer).build()
    }

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
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Chỉ hiển thị các nút điều khiển khi KHÔNG ở chế độ Popup
        if (!isInPiP) {
            // Nút Back thoát video
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 32.dp, start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }

            // Nút thu nhỏ thành Popup (PiP) giống YouTube
            IconButton(
                onClick = {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9)) // Tỉ lệ khung hình video chuẩn
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(initialUrl: String, viewModel: WebDavViewModel, user: String, pass: String, onBack: () -> Unit) {
    val imageFiles = remember(viewModel.fileList) {
        viewModel.fileList.filter { it.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") } }
    }

    val initialPage = remember(imageFiles, initialUrl) {
        val index = imageFiles.indexOfFirst { it.path == initialUrl }
        if (index >= 0) index else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { imageFiles.size }
    )

    val context = LocalContext.current
    // TỐI ƯU 3: Giải phóng toàn bộ bộ nhớ RAM nặng nề của ảnh gốc ngay khi bạn thoát màn hình xem ảnh
    DisposableEffect(Unit) {
        onDispose {
            coil.Coil.imageLoader(context).memoryCache?.clear()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondBoundsPageCount = 1, // TỐI ƯU 4: Tự động Pre-load (tải ngầm) 1 ảnh trước và 1 ảnh sau
            key = { imageFiles[it].path } // TỐI ƯU HOÁ: Giúp Pager nhớ chuẩn xác từng ảnh không bị lú
        ) { page ->
            val file = imageFiles[page]

            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            // Tự động Reset Zoom khi vuốt sang ảnh mới
            LaunchedEffect(pagerState.currentPage) {
                scale = 1f
                offset = Offset.Zero
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                if (scale > 1f) {
                                    // Đang thu phóng -> Chạm đúp để reset về ban đầu
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // Phóng to ngay đúng vị trí ngón tay chạm bằng thuật toán tịnh tiến Vector
                                    scale = 2.5f
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val targetOffset = (center - tapOffset) * (scale - 1f)

                                    // Giới hạn biên giới để ảnh không bị kéo văng ra khỏi màn hình
                                    val extraW = (scale - 1) * 1000f
                                    val extraH = (scale - 1) * 1000f

                                    offset = Offset(
                                        x = targetOffset.x.coerceIn(-extraW, extraW),
                                        y = targetOffset.y.coerceIn(-extraH, extraH)
                                    )
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            do {
                                val event = awaitPointerEvent()
                                val pointers = event.changes.size

                                // THUẬT TOÁN ĐỘC QUYỀN: Phân xử quyền Vuốt vs Zoom
                                // Chỉ chặn vuốt (Pager) khi người dùng dùng 2 ngón tay hoặc ảnh đang được phóng to
                                if (pointers >= 2 || scale > 1f) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        val extraW = (scale - 1) * 1000f
                                        val extraH = (scale - 1) * 1000f
                                        offset = Offset(
                                            x = (offset.x + panChange.x).coerceIn(-extraW, extraW),
                                            y = (offset.y + panChange.y).coerceIn(-extraH, extraH)
                                        )
                                    } else {
                                        offset = Offset.Zero
                                    }
                                    // Chặn đứng sự kiện chạm, không cho Pager cướp lấy
                                    event.changes.forEach { it.consume() }
                                }
                                // Nếu dùng 1 ngón tay và ảnh đang thu nhỏ (scale = 1f) -> Mặc kệ cho Pager tự vuốt!
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(file.path)
                        .addHeader("Authorization", okhttp3.Credentials.basic(user, pass))
                        // TỐI ƯU 5: Gọi lại Cache Đĩa đã lưu từ lúc tải Thumbnail để khỏi tốn mạng, nhưng bung kích thước GỐC
                        .diskCacheKey(file.path)
                        .memoryCacheKey(file.path + "_full")
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .size(coil.size.Size.ORIGINAL)
                        .crossfade(true)
                        .build(),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Header hiển thị Nút Back và Số thứ tự
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(top = 32.dp, bottom = 16.dp, start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (imageFiles.isNotEmpty()) "${pagerState.currentPage + 1} / ${imageFiles.size} - ${imageFiles[pagerState.currentPage].name}" else "",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Composable
    fun VideoPlayerScreen(url: String, username: String, pass: String, onBack: () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current

        // Cập nhật trạng thái cho Activity biết để kích hoạt PiP (An toàn bộ nhớ)
        DisposableEffect(Unit) {
            (context as? MainActivity)?.isPlayingVideo = true
            onDispose { (context as? MainActivity)?.isPlayingVideo = false }
        }

        val exoPlayer = remember {
            androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setSeekBackIncrementMs(10000) // TUA LÙI 10 GIÂY
                .setSeekForwardIncrementMs(10000) // TUA TỚI 10 GIÂY
                .build().apply {
                    // Tích hợp tài khoản WebDAV để phát trực tiếp phim từ NAS mà không cần tải về
                    val dataSourceFactory =
                        androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(
                                mapOf(
                                    "Authorization" to okhttp3.Credentials.basic(
                                        username,
                                        pass
                                    )
                                )
                            )

                    val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                        dataSourceFactory
                    )
                        .createMediaSource(androidx.media3.common.MediaItem.fromUri(url))

                    setMediaSource(source)
                    prepare()
                    playWhenReady = true
                }
        }

        DisposableEffect(Unit) {
            onDispose { exoPlayer.release() }
        }

        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            IconButton(onClick = onBack, modifier = Modifier.padding(16.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Quay lại",
                    tint = Color.White
                )
            }
            androidx.compose.ui.viewinterop.AndroidView(
                factory = {
                    androidx.media3.ui.PlayerView(context).apply {
                        player = exoPlayer
                        useController = true
                        setShowFastForwardButton(true) // ÉP HIỆN NÚT TUA TỚI
                        setShowRewindButton(true) // ÉP HIỆN NÚT TUA LÙI
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// GIAO DIỆN KHÓA SINH TRẮC HỌC
@Composable
fun BiometricLockScreen(activity: androidx.fragment.app.FragmentActivity, onAuthenticated: () -> Unit, onFallbackToLogin: () -> Unit) {
    val executor = remember { androidx.core.content.ContextCompat.getMainExecutor(activity) }
    var authError by remember { mutableStateOf("") }
    var failCount by remember { mutableStateOf(0) }

    val authenticate = {
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Khóa bảo mật NAS")
            .setSubtitle("Vui lòng xác thực vân tay/khuôn mặt để truy cập dữ liệu")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        val biometricPrompt = androidx.biometric.BiometricPrompt(activity, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    failCount = 0
                    onAuthenticated() // Mở khóa thành công
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED && errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        failCount++
                        authError = "Lỗi: $errString (Sai $failCount/3 lần)"
                        if (failCount >= 3) onFallbackToLogin()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    failCount++
                    authError = "Vân tay không khớp! (Sai $failCount/3 lần)"
                    if (failCount >= 3) onFallbackToLogin()
                }
            })
        biometricPrompt.authenticate(promptInfo)
    }

    // Tự động gọi popup quét vân tay ngay khi vừa mở màn hình
    LaunchedEffect(Unit) {
        authenticate()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, contentDescription = "Lock", modifier = Modifier.size(64.dp), tint = Color(0xFF00897B))
            Spacer(Modifier.height(16.dp))
            Text("Ứng dụng đang khóa", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (authError.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(authError, color = Color.Red, fontSize = 14.sp)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = authenticate,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
            ) {
                Text("Chạm để mở khóa", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// HÀM HIỂN THỊ THÔNG BÁO CHUNG CHO DỰ ÁN
@Composable
fun NotificationDialog(title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconColor: Color, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đã hiểu", fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }
        },
        text = { Text(message, fontSize = 14.sp) },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.primary,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}
