package com.nas.naswebdav



// Import các Composable đã tách file

import com.nas.naswebdav.ui.screens.MainMenuScreen

import com.nas.naswebdav.ui.screens.BrowserScreen

import com.nas.naswebdav.ui.screens.LoginScreen

import com.nas.naswebdav.ui.screens.VideoPlayerScreen

import com.nas.naswebdav.ui.screens.ImageViewerScreen

import com.nas.naswebdav.ui.dialogs.BiometricLockScreen

import com.nas.naswebdav.ui.screens.GuestPassScreen

import com.nas.naswebdav.ui.screens.SocialExtractorScreen

import com.nas.naswebdav.ui.screens.SmartOrganizerScreen



import android.os.Bundle

import androidx.activity.compose.BackHandler

import androidx.activity.compose.setContent

import androidx.compose.foundation.ExperimentalFoundationApi

import androidx.compose.material3.*

import androidx.compose.runtime.*

import coil.decode.VideoFrameDecoder

import androidx.navigation.compose.*

import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.launch

import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.Dispatchers



class MainActivity : androidx.fragment.app.FragmentActivity() {



    // FIX MEMORY LEAK: Loại bỏ companion object (static state), dùng biến instance thông thường

    var isPlayingVideo = false

    var videoAspectRatio = android.util.Rational(16, 9)

    

    // Lưu ViewModel cấp độ Activity để nhận Intent khi sống nền

    private lateinit var viewModel: WebDavViewModel



    override fun onUserLeaveHint() {

        super.onUserLeaveHint()

        // TỰ ĐỘNG THU NHỎ VIDEO: Kích hoạt PiP khi người dùng bấm phím Home

        if (isPlayingVideo) {

            val params = android.app.PictureInPictureParams.Builder()

                // Sửa viền đen: Dùng tỉ lệ gốc của video (set từ VideoPlayerScreen) thay vì 16:9 cứng

                .setAspectRatio(videoAspectRatio)

                .build()

            enterPictureInPictureMode(params)

        }

    }

    override fun onTrimMemory(level: Int) {

        super.onTrimMemory(level)

        // Khi hệ thống thiếu RAM, chủ động giải phóng bộ nhớ đệm hình ảnh

        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {

            coil.Coil.imageLoader(this).memoryCache?.clear()

            // FIX BUG #6: Xóa System.gc() — không hiệu quả, gây GC pause

        }

        // FIX IMAGE CACHE LEAK: Dọn disk cache khi bộ nhớ thấp

        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {

            // FIX BUG #6: Dùng lifecycleScope thay vì GlobalScope — tránh rò rỉ khi Activity bị hủy

            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {

                coil.Coil.imageLoader(this@MainActivity).diskCache?.clear()

            }

        }

    }



    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // Yêu cầu quyền truy cập toàn bộ tập tin (All Files Access) từ Android 11+ (API 30+)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {

            if (!android.os.Environment.isExternalStorageManager()) {

                try {

                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)

                    intent.addCategory("android.intent.category.DEFAULT")

                    intent.data = android.net.Uri.parse(String.format("package:%s", packageName))

                    startActivity(intent)

                } catch (e: Exception) {

                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

                    startActivity(intent)

                }

            }

        }

        val db = NasApplication.instance.database

        val webDavManager = WebDavManager

        val repository = WebDavRepository(webDavManager, db)

        viewModel = WebDavViewModel(webDavManager, repository)



        // Bắt Intent khởi động ứng dụng từ tính năng Tự động Thông báo Rác

        if (intent?.getBooleanExtra("SHOW_DUPLICATES", false) == true) {

            viewModel.shouldAutoOpenDuplicates = true

        }



        // TÍNH NĂNG SHARE TO APP: Xử lý tệp chia sẻ từ ứng dụng khác

        if (intent?.action == android.content.Intent.ACTION_SEND || intent?.action == android.content.Intent.ACTION_SEND_MULTIPLE) {

            val sharedUris = mutableListOf<android.net.Uri>()

            if (intent.action == android.content.Intent.ACTION_SEND) {

                intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { sharedUris.add(it) }

            } else {

                intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { sharedUris.addAll(it) }

            }



            if (sharedUris.isNotEmpty()) {

                // BẢO MẬT: Đọc credentials qua SecurePrefsHelper (AES-256 singleton)

                val savedUrl = SecurePrefsHelper.getUrl(applicationContext)

                val savedUser = SecurePrefsHelper.getUser(applicationContext)

                val savedPass = SecurePrefsHelper.getPass(applicationContext)



                if (savedUrl.isNotEmpty()) {

                    viewModel.webDavManager.connect(savedUrl, savedUser, savedPass)

                    // FIX: uploadFile() cần context và Uri — gọi qua lifecycleScope vì là suspend fun
                    sharedUris.forEach { uri ->
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val rawName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                                } ?: uri.lastPathSegment ?: "upload_${System.currentTimeMillis()}"
                                // FIX: sanitize tên file để tránh path traversal khi ghi tempFile vào cacheDir.
                                // Loại bỏ '/' '\' và '..' segment vì DISPLAY_NAME có thể là malicious.
                                val fileName = rawName
                                    .replace('/', '_').replace('\\', '_')
                                    .replace("..", "_")
                                    .ifBlank { "upload_${System.currentTimeMillis()}" }
                                    .take(200)
                                // FIX #17: URL-encode tên file để tránh lỗi với dấu cách/kí tự đặc biệt
                                val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                                val destUrl = savedUrl.trimEnd('/') + "/$encodedName"
                                contentResolver.openInputStream(uri)?.use { inputStream ->
                                    val tempFile = java.io.File(cacheDir, fileName)
                                    // Phòng hộ thêm: đảm bảo path cuối cùng nằm trong cacheDir
                                    if (!tempFile.canonicalPath.startsWith(cacheDir.canonicalPath)) {
                                        throw SecurityException("Tên file độc hại: $rawName")
                                    }
                                    tempFile.outputStream().use { inputStream.copyTo(it) }
                                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                                    viewModel.webDavManager.uploadFile(destUrl, tempFile, mimeType)
                                    tempFile.delete()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ShareUpload", "Tải lên thất bại: ${e.message}")
                            }
                        }
                    }

                }

            }

        }



        // CẤU HÌNH TỐI ƯU CHO NAS YẾU (Chainedbox, Rockchip rk3328, v.v...)

        // Giảm luồng song song xuống thấp (4 luồng/host) để không làm treo ổ cứng NAS khi vừa load ảnh vừa xem Video

        val dispatcher = okhttp3.Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 4 }

        val customClient = NasApplication.instance.sharedHttpClient.newBuilder()

            .dispatcher(dispatcher)

            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

            .build()



        val imageLoaderInstance = coil.ImageLoader.Builder(applicationContext)

            .okHttpClient(customClient)

            .memoryCache {

            // FIX BUG #1: Cache cố định theo MB thay vì % — tránh OOM trên thiết bị yếu

                val maxHeap = Runtime.getRuntime().maxMemory()

                val heapMb = maxHeap / (1024L * 1024L)

                val cacheMb = when {

                    heapMb < 128L -> 50L

                    heapMb > 512L -> 200L

                    else -> (heapMb * 15 / 100)  // 15% nhưng trong bounds an toàn

                }

                coil.memory.MemoryCache.Builder(applicationContext)

                    .maxSizeBytes((cacheMb * 1024 * 1024).toInt())

                    .build()

            }

            .diskCache {

                coil.disk.DiskCache.Builder()

                    .directory(cacheDir.resolve("image_cache"))

                    .maxSizeBytes(800L * 1024 * 1024) // FIX IMAGE CACHE LEAK: Tăng lên 800MB (tối ưu cho thumbnail nhiều)

                    .build()

            }

            .components { add(VideoFrameDecoder.Factory()) }

            .build()

        coil.Coil.setImageLoader(imageLoaderInstance)



        setContent {

            val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme, typography = com.nas.naswebdav.ui.theme.AppTypography) {
                CompositionLocalProvider(
                    androidx.compose.foundation.LocalIndication provides com.nas.naswebdav.ui.theme.NoRippleIndication
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        NasAppNavigation(viewModel)
                    }
                }
            }

        }

    }



    override fun onNewIntent(intent: android.content.Intent) {

        super.onNewIntent(intent)

        // Bắt Intent khi App đang chạy trong bộ nhớ nền 

        if (intent.getBooleanExtra("SHOW_DUPLICATES", false)) {

            if (::viewModel.isInitialized) {

                viewModel.shouldAutoOpenDuplicates = true

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

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.lockNowRequested = false
    }
    var showBiometricLock by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var hasCompletedFirstResume by remember { mutableStateOf(false) }
    var requireBiometricOnReturn by remember { mutableStateOf(false) }

    var mediaUrl by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }



    // NAVIGATION COMPOSE CHUẨN

    val navController = androidx.navigation.compose.rememberNavController()



    // Khóa lại ngay khi người dùng rời app. Bỏ qua màn đăng nhập và lần resume đầu khi app vừa khởi động.

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, navController) {

        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->

            when (event) {

                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {

                    com.nas.naswebdav.AppConfig.IS_APP_FOREGROUND = false
                    val isLoginScreen = navController.currentDestination?.route == "login" ||
                        navController.currentDestination == null
                    val biometricEnabled = sharedPrefs.getBoolean("biometric_enabled", false)
                    if (biometricEnabled && !isLoginScreen && !showBiometricLock) {
                        requireBiometricOnReturn = true
                    }

                }

                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {

                    com.nas.naswebdav.AppConfig.IS_APP_FOREGROUND = true
                    val isLoginScreen = navController.currentDestination?.route == "login" ||
                        navController.currentDestination == null
                    if (!isLoginScreen) {
                        viewModel.refreshNasStateOnForeground(mContext.applicationContext, force = true)
                    }
                    if (!hasCompletedFirstResume) {
                        hasCompletedFirstResume = true
                        return@LifecycleEventObserver
                    }
                    if (requireBiometricOnReturn) {
                        if (!isLoginScreen && sharedPrefs.getBoolean("biometric_enabled", false)) {
                            showBiometricLock = true
                        }
                        requireBiometricOnReturn = false
                    }

                }

                else -> {}

            }

        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {

            lifecycleOwner.lifecycle.removeObserver(observer)

        }

    }







    // ============ DIALOG PHÊ DUYỆT IP LẠ (TOÀN CỤC - HIỂN THỊ TRÊN MỌI SCREEN) ============

    if (viewModel.showApprovalDialog) {

        com.nas.naswebdav.ui.dialogs.IpApprovalDialog(

            viewModel = viewModel,

            onDismiss = { viewModel.showApprovalDialog = false }

        )

    }



    // Dialog thông báo chung từ ViewModel (hiển thị toàn cục)

    if (viewModel.showCommonDialog) {

        com.nas.naswebdav.ui.dialogs.AppStatusDialog(

            type = viewModel.commonDialogType,

            message = viewModel.commonDialogMessage,

            onDismiss = { viewModel.showCommonDialog = false }

        )

    }



    androidx.navigation.compose.NavHost(navController = navController, startDestination = "login") {

        composable("login") {

            LoginScreen(viewModel) {

                navController.navigate("main_menu") {

                    popUpTo("login") { inclusive = true }

                }

            }

        }

        

        composable("main_menu") {

            // VÔ HIỆU HÓA BACK CỨNG: Chặn thoát app từ Menu chính

            androidx.activity.compose.BackHandler { /* Do nothing */ }

            com.nas.naswebdav.ui.screens.MainMenuScreen(

                viewModel = viewModel,

                onOpenFiles = {

                    viewModel.resetToDefaultMode()

                    navController.navigate("browser")

                },

                onOpenFolder = { webdavPath ->

                    viewModel.openSpecificUrl(webdavPath, "Downloads")

                    navController.navigate("browser")

                },

                onGlobalSearch = { keyword ->

                    viewModel.searchGlobal(keyword)

                    navController.navigate("browser")

                },

                onOpenLatestPhotos = {

                    viewModel.showLatestPhotos()

                    navController.navigate("browser")

                },

                onOpenRecentVideos = {

                    viewModel.showRecentVideos()

                    navController.navigate("browser")

                },

                onOpenTrash = {

                    val trashUrl = viewModel.webDavManager.currentBaseUrl + ".trash/"

                    viewModel.openSpecificUrl(trashUrl, "Thùng rác")

                    navController.navigate("browser")

                },

                onOpenPerformance = {

                    navController.navigate("performance")

                },

                onLogout = {

                    viewModel.viewModelScope.launch {

                        viewModel.repository.addSystemLog("INFO", "Network", "Người dùng '${viewModel.webDavManager.currentUser}' đã chủ động Đăng xuất.")

                    }

                    navController.navigate("login") {

                        popUpTo(0)

                    }

                },

                // ── TÍNH NĂNG MỚI ─────────────────────────────────────────────────

                onOpenOrganizer = { navController.navigate("smart_organizer") },

                onOpenGuestPass = { navController.navigate("guest_pass") },

                onOpenSocialExtractor = { navController.navigate("social_extractor") }

            )

        }



        composable("browser") {

            com.nas.naswebdav.ui.screens.BrowserScreen(

                viewModel = viewModel,

                onVideo = { url -> 

                    mediaUrl = url

                    navController.navigate("video") 

                },

                onImage = { url -> 

                    mediaUrl = url

                    navController.navigate("image") 

                },

                onLogout = {

                    viewModel.viewModelScope.launch {

                        viewModel.repository.addSystemLog("INFO", "Network", "Người dùng '${viewModel.webDavManager.currentUser}' đã chủ động Đăng xuất.")

                    }

                    navController.navigate("login") {

                        popUpTo(0)

                    }

                },

                onBackToMenu = {

                    viewModel.resetToDefaultMode()

                    navController.navigate("main_menu") {

                        popUpTo("main_menu") { inclusive = true }

                    }

                }

            )

        }



        composable("performance") {

            com.nas.naswebdav.ui.screens.PerformanceScreen(

                onBack = { navController.popBackStack() }

            )

        }



        composable("video") {

            com.nas.naswebdav.ui.screens.VideoPlayerScreen(

                url = mediaUrl,

                user = viewModel.webDavManager.currentUser,

                pass = viewModel.webDavManager.currentPass,

                viewModel = viewModel,

                onBack = { navController.popBackStack() }

            )

        }



        composable("image") {

            com.nas.naswebdav.ui.screens.ImageViewerScreen(

                initialUrl = mediaUrl,

                viewModel = viewModel,

                user = viewModel.webDavManager.currentUser,

                pass = viewModel.webDavManager.currentPass,

                onBack = { navController.popBackStack() }

            )

        }



        // ─── TÍNH NĂNG MỚI: Guest Pass ────────────────────────────────────

        composable("guest_pass") {

            GuestPassScreen(

                viewModel = viewModel,

                onBack = { navController.popBackStack() }

            )

        }



        // ─── TÍNH NĂNG MỚI: Social Extractor ──────────────────────────────

        composable("social_extractor") {

            SocialExtractorScreen(

                viewModel = viewModel,

                onBack = { navController.popBackStack() }

            )

        }



        // ─── TÍNH NĂNG MỚI: Smart Organizer ────────────────────────────────

        composable("smart_organizer") {

            SmartOrganizerScreen(

                viewModel = viewModel,

                onBack = { navController.popBackStack() }

            )

        }

    }



    // Hiển thị lớp Khóa Sinh trắc học đè lên trên mọi giao diện

    if (showBiometricLock) {

        BiometricLockScreen(

            activity = mContext as androidx.fragment.app.FragmentActivity,

            onAuthenticated = {

                showBiometricLock = false
                requireBiometricOnReturn = false

                // BỎ QUA LOGIN: Nếu vừa khởi động app và quét vân tay đúng, tự động kết nối luôn

                if (navController.currentDestination?.route == "login" || navController.currentDestination == null) {

                    val urlList = SecurePrefsHelper.getUrlList(mContext)

                    val user = SecurePrefsHelper.getUser(mContext)

                    val pass = SecurePrefsHelper.getPass(mContext)

                    if (urlList.isNotEmpty() && user.isNotEmpty()) {

                        viewModel.connect(urlList, user, pass)

                        viewModel.scheduleIdleDuplicateScan(mContext)

                        viewModel.scheduleIdleSpeedTest(mContext)

                        viewModel.scheduleFingerprintWorker(mContext)



                        navController.navigate("main_menu") {

                            popUpTo("login") { inclusive = true }

                        }

                    }

                }

            },

            onFallbackToLogin = {

                showBiometricLock = false
                requireBiometricOnReturn = false

                navController.navigate("login") {

                    popUpTo(0)

                }

            }

        )

    }

}





