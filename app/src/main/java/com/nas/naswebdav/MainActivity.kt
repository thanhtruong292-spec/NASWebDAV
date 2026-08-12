@file:OptIn(ExperimentalCoilApi::class)
package com.nas.naswebdav




// Import các Composable đã tách file

import com.nas.naswebdav.ui.screens.MainMenuScreen

import com.nas.naswebdav.ui.screens.BrowserScreen
import com.nas.naswebdav.ui.screens.openExternalVideoPlayer

import com.nas.naswebdav.ui.screens.LoginScreen

import com.nas.naswebdav.ui.screens.ExoPlayerScreen

import com.nas.naswebdav.ui.screens.ImageViewerScreen

import com.nas.naswebdav.ui.dialogs.BiometricLockScreen

import com.nas.naswebdav.ui.screens.GuestPassScreen

import com.nas.naswebdav.ui.screens.SocialExtractorScreen

import com.nas.naswebdav.ui.screens.SmartOrganizerScreen

import com.nas.naswebdav.ui.screens.NasTheme
import com.nas.naswebdav.ui.screens.RecordingOverlayBg
import com.nas.naswebdav.ui.screens.RecordingOverlayBorder

import com.nas.naswebdav.ui.screens.DarkSurface



import android.os.Bundle

import androidx.activity.compose.BackHandler

import androidx.activity.compose.setContent
import androidx.activity.viewModels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.foundation.background

import coil.decode.VideoFrameDecoder
import coil.annotation.ExperimentalCoilApi

import androidx.navigation.compose.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.launch

import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.nas.naswebdav.auth.AuthSessionViewModel
import com.nas.naswebdav.browser.FileBrowserViewModel
import com.nas.naswebdav.device.DeviceManagementViewModel
import com.nas.naswebdav.livestream.LivestreamViewModel
import com.nas.naswebdav.backup.AutoBackupViewModel
import com.nas.naswebdav.monitor.SystemMonitorViewModel
import com.nas.naswebdav.smarttools.SmartToolsViewModel
import androidx.core.net.toUri



class MainActivity : androidx.fragment.app.FragmentActivity() {



    // FIX MEMORY LEAK: Loại bỏ companion object (static state), dùng biến instance thông thường

    var isPlayingVideo = false

    var videoAspectRatio = android.util.Rational(16, 9)

    

    // Lưu ViewModel cấp độ Activity để nhận Intent khi sống nền

    /** Repository */
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        WebDavRepository(WebDavManager, NasApplication.instance.database)
    }

    /** GlobalUiVM is shared */
    private val globalUiVM by lazy(LazyThreadSafetyMode.NONE) { com.nas.naswebdav.GlobalUiViewModel() }

    private val viewModelFactory by lazy(LazyThreadSafetyMode.NONE) {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(com.nas.naswebdav.auth.AuthSessionViewModel::class.java) -> com.nas.naswebdav.auth.AuthSessionViewModel(repository) as T
                modelClass.isAssignableFrom(com.nas.naswebdav.browser.FileBrowserViewModel::class.java) -> com.nas.naswebdav.browser.FileBrowserViewModel(repository) as T
                modelClass.isAssignableFrom(com.nas.naswebdav.monitor.SystemMonitorViewModel::class.java) -> com.nas.naswebdav.monitor.SystemMonitorViewModel(repository) as T
                modelClass.isAssignableFrom(com.nas.naswebdav.GlobalUiViewModel::class.java) -> globalUiVM as T
                modelClass.isAssignableFrom(com.nas.naswebdav.device.DeviceManagementViewModel::class.java) -> com.nas.naswebdav.device.DeviceManagementViewModel(repository, globalUiVM) as T
                modelClass.isAssignableFrom(com.nas.naswebdav.smarttools.SmartToolsViewModel::class.java) -> com.nas.naswebdav.smarttools.SmartToolsViewModel(repository) as T
                modelClass.isAssignableFrom(com.nas.naswebdav.livestream.LivestreamViewModel::class.java) -> com.nas.naswebdav.livestream.LivestreamViewModel(repository) as T
                modelClass.isAssignableFrom(com.nas.naswebdav.backup.AutoBackupViewModel::class.java) -> com.nas.naswebdav.backup.AutoBackupViewModel(repository) as T
                else -> throw IllegalArgumentException("Unknown VM class")
            }
        }
    }

    private val authSessionVM: com.nas.naswebdav.auth.AuthSessionViewModel by viewModels { viewModelFactory }
    private val fileBrowserVM: com.nas.naswebdav.browser.FileBrowserViewModel by viewModels { viewModelFactory }
    private val systemMonitorVM: com.nas.naswebdav.monitor.SystemMonitorViewModel by viewModels { viewModelFactory }
    private val deviceManagementVM: com.nas.naswebdav.device.DeviceManagementViewModel by viewModels { viewModelFactory }
    private val smartToolsVM: com.nas.naswebdav.smarttools.SmartToolsViewModel by viewModels { viewModelFactory }
    private val livestreamVM: com.nas.naswebdav.livestream.LivestreamViewModel by viewModels { viewModelFactory }
    private val autoBackupVM: com.nas.naswebdav.backup.AutoBackupViewModel by viewModels { viewModelFactory }
    private val globalUiViewModel: com.nas.naswebdav.GlobalUiViewModel by viewModels { viewModelFactory }

    /** Single source for all 7 Domain VMs — Phase 7b manual DI now properly managed by ViewModelStore */
    private val domainProvider by lazy(LazyThreadSafetyMode.NONE) {
        DomainViewModelProvider(
            authSession = authSessionVM,
            fileBrowser = fileBrowserVM,
            systemMonitor = systemMonitorVM,
            globalUi = globalUiViewModel,
            deviceManagement = deviceManagementVM,
            smartTools = smartToolsVM,
            livestream = livestreamVM,
            autoBackup = autoBackupVM,
            repository = repository
        )
    }
    private lateinit var screenCaptureLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
    private lateinit var notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>



    override fun onUserLeaveHint() {

        super.onUserLeaveHint()

        // TỰ ĐỘNG THU NHỎ VIDEO: Kích hoạt PiP khi người dùng bấm phím Home

        if (isPlayingVideo && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {

            val params = android.app.PictureInPictureParams.Builder()

                // Sửa viền đen: Dùng tỉ lệ gốc của video (set từ VideoPlayerScreen) thay vì 16:9 cứng

                .setAspectRatio(videoAspectRatio)

                .build()

            try {

                enterPictureInPictureMode(params)

            } catch (e: IllegalStateException) {

                android.util.Log.w("MainActivity", "PiP unavailable on user leave", e)

            }

        }

    }

    @Suppress("DEPRECATION")
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



    @OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // AutoBackup dùng READ_MEDIA_* + SAF (P0-6). Không cần MANAGE_EXTERNAL_STORAGE nữa.

        requestMediaReadPermissionsIfNeeded()

        screenCaptureLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                lifecycleScope.launch {
                    val activeBaseUrl = SmartNetworkManager.getActiveBaseUrl(this@MainActivity)
                        .ifBlank { WebDavManager.currentBaseUrl }
                    val serviceIntent = android.content.Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_START
                        putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                        putExtra(ScreenRecordService.EXTRA_API_BASE, activeBaseUrl.toApiBaseUrl())
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                }
            }
        }
        notificationPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                requestScreenRecordPermissionActual()
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Chưa cấp quyền thông báo: vẫn tiếp tục chuẩn bị quay màn hình, nhưng thông báo nền có thể không hiển thị đầy đủ.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                requestScreenRecordPermissionActual()
            }
        }
        handleIncomingIntent(intent)

        // S8 FIX: KHÔNG setImageLoader ở MainActivity — để Coil tự lấy từ NasApplication.newImageLoader().
        // Trước đây mỗi Activity recreate (rotate/dark mode) đều tạo OkHttpClient + disk cache mới,
        // làm leak memory cache cũ. NasApplication.newImageLoader() đã cấu hình đủ (memory, disk, VideoFrameDecoder).



        setContent {
            // ═══ PHASE 7b: Wrap CompositionLocalProvider around NasTheme ═══
            // The same Domain VM instances owned by `viewModel` are exposed via
            // static CompositionLocals so individual screens can subscribe directly
            // during Phase 7c UI migration without prop-drilling through every
            // composable in the call chain.
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAuthSessionVM provides domainProvider.authSession,
                LocalFileBrowserVM provides domainProvider.fileBrowser,
                LocalSystemMonitorVM provides domainProvider.systemMonitor,
                LocalDeviceManagementVM provides domainProvider.deviceManagement,
                LocalSmartToolsVM provides domainProvider.smartTools,
                LocalLivestreamVM provides domainProvider.livestream,
                LocalAutoBackupVM provides domainProvider.autoBackup,
                LocalGlobalUiVM provides domainProvider.globalUi,
            ) {
                com.nas.naswebdav.ui.screens.NasTheme {
                    Surface(color = DarkSurface) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NasAppNavigation(domainProvider, onStartScreenRecord = { requestScreenRecordPermission() })

                            // Floating Screen Recording overlay (global)
                            ScreenRecordFloatingOverlay()
                        }
                    }
                }
            }
        }

    }



    override fun onNewIntent(intent: android.content.Intent) {

        super.onNewIntent(intent)

        setIntent(intent)
        handleIncomingIntent(intent)

    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        val incomingIntent = intent ?: return

        if (incomingIntent.getBooleanExtra("SHOW_DUPLICATES", false)) {
            domainProvider.smartTools.shouldAutoOpenDuplicates = true
        }

        handleShareIntent(incomingIntent)
    }

    private fun handleShareIntent(intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_SEND && intent.action != android.content.Intent.ACTION_SEND_MULTIPLE) return

        val sharedUris = mutableListOf<android.net.Uri>()
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                val sharedUri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                }
                sharedUri?.let(sharedUris::add)
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                val sharedUriList = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                }
                sharedUriList?.let(sharedUris::addAll)
            }
        }

        if (sharedUris.isEmpty()) return

        val savedUrl = SecurePrefsHelper.getUrl(applicationContext)
        val savedUser = SecurePrefsHelper.getUser(applicationContext)
        val savedPass = SecurePrefsHelper.getPass(applicationContext)
        if (savedUrl.isBlank()) return

        WebDavManager.connect(savedUrl, savedUser, savedPass)

        sharedUris.forEach { uri ->
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                var tempFile: java.io.File? = null
                try {
                    val rawName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                    } ?: uri.lastPathSegment ?: "upload_${System.currentTimeMillis()}"

                    // Keep the temp file name local to cacheDir and strip path separators.
                    val fileName = rawName
                        .replace('/', '_').replace('\\', '_')
                        .replace("..", "_")
                        .ifBlank { "upload_${System.currentTimeMillis()}" }
                        .take(200)

                    val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                    val destUrl = savedUrl.trimEnd('/') + "/$encodedName"

                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val temp = java.io.File(cacheDir, fileName)
                        tempFile = temp
                        if (!temp.canonicalPath.startsWith(cacheDir.canonicalPath)) {
                            throw SecurityException("Invalid temp file name: $rawName")
                        }
                        temp.outputStream().use { inputStream.copyTo(it) }
                        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                        WebDavManager.uploadFile(destUrl, temp, mimeType)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.e("ShareUpload", "Upload failed: ${e.message}")
                } finally {
                    tempFile?.delete()
                }
            }
        }
    }

    private fun requestMediaReadPermissionsIfNeeded() {
        val permissions = when {
            android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 4102)
        }
    }

    private fun requestScreenRecordPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
                return
            }
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(
                this,
                "Không có quyền hiển thị trên cùng: chip REC sẽ không hiện, quay vẫn hoạt động.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        requestScreenRecordPermissionActual()
    }

    private fun requestScreenRecordPermissionActual() {
        val manager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

}



/// --- NAVIGATION ---

/**
 * Phase 7d.7: refreshNasStateOnForeground — replaces the deprecated
 * WebDavViewModel facade method. Orchestrates refresh of all domain VMs on
 * app foreground (e.g. via OnLifecycleEvent callback).
 */
private fun refreshNasStateOnForeground(
    domainProvider: DomainViewModelProvider,
    context: android.content.Context,
    force: Boolean = false
) {
    // Smart network check (was on AuthSessionVM facade)
    domainProvider.authSession.checkSmartNetwork(context)
    // Dashboard monitoring (was on SystemMonitorVM facade)
    domainProvider.systemMonitor.startDashboardMonitoring(resetStatusPoll = false)
    // Thumbnail status (was on SmartToolsVM facade)
    domainProvider.smartTools.fetchThumbStatus()
    // Live data (was on LivestreamVM facade)
    domainProvider.livestream.fetchLivestreamStatusOnly(context)
    domainProvider.livestream.fetchTikTokLiveWatch(context)
    // USB import + storage (was on AutoBackup/DeviceMgmt)
    domainProvider.autoBackup.fetchUsbImportStatus()
    domainProvider.systemMonitor.fetchNasInsights()
    // Heavy refresh: OMV overview, storage, logs
    domainProvider.deviceManagement.fetchOmvOverview()
    domainProvider.deviceManagement.fetchStorageUsage()
    domainProvider.deviceManagement.loadSystemLogs()
}

@OptIn(ExperimentalFoundationApi::class)

@Composable

fun NasAppNavigation(domainProvider: DomainViewModelProvider, onStartScreenRecord: () -> Unit = {}) {

    val mContext = androidx.compose.ui.platform.LocalContext.current

    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)

    // Phase 7d.7: NasAppNavigation reads directly from domain VMs via the CompositionLocals
    // that MainActivity's setContent provides — no more facade pass-through.
    val autoBackupVM = LocalAutoBackupVM.current
    val deviceMgmtVM = LocalDeviceManagementVM.current
    val fileBrowserVM = LocalFileBrowserVM.current
    val livestreamVM = LocalLivestreamVM.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        autoBackupVM.lockNowRequested = false
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
                        refreshNasStateOnForeground(domainProvider, mContext.applicationContext, force = true)
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

    if (deviceMgmtVM.showApprovalDialog) {

        com.nas.naswebdav.ui.dialogs.IpApprovalDialog(

            onDismiss = { deviceMgmtVM.showApprovalDialog = false }

        )

    }



    // Dialog thông báo chung từ GlobalUiVM (Phase 7d.3)
    val globalUiVM = LocalGlobalUiVM.current
    if (globalUiVM.showCommonDialog) {

        com.nas.naswebdav.ui.dialogs.AppStatusDialog(

            type = globalUiVM.commonDialogType,

            message = globalUiVM.commonDialogMessage,

            onDismiss = { globalUiVM.dismiss() }

        )

    }



    androidx.navigation.compose.NavHost(navController = navController, startDestination = "login") {

        composable("login") {

            LoginScreen {

                navController.navigate("main_menu") {

                    popUpTo("login") { inclusive = true }

                }

            }

        }

        

        composable("main_menu") {

            // VÔ HIỆU HÓA BACK CỨNG: Chặn thoát app từ Menu chính

            androidx.activity.compose.BackHandler { /* Do nothing */ }

            com.nas.naswebdav.ui.screens.MainMenuScreen(

                onOpenFiles = {

                    fileBrowserVM.resetToDefaultMode()

                    navController.navigate("browser")

                },

                onOpenFolder = { webdavPath ->

                    fileBrowserVM.openSpecificUrl(webdavPath, "Downloads")

                    navController.navigate("browser")

                },

                onGlobalSearch = { keyword ->

                    fileBrowserVM.searchGlobal(keyword)

                    navController.navigate("browser")

                },

                onOpenLatestPhotos = {

                    fileBrowserVM.showLatestPhotos()

                    navController.navigate("browser")

                },

                onOpenRecentVideos = {

                    fileBrowserVM.showRecentVideos()

                    navController.navigate("browser")

                },

                onOpenTrash = {

                    val trashUrl = buildWebDavTrashTargetUrl(
                        WebDavManager.currentBaseUrl,
                        fileBrowserVM.currentUrl.ifBlank { WebDavManager.currentBaseUrl },
                        "",
                        false
                    )

                    fileBrowserVM.openSpecificUrl(trashUrl, "Thùng rác")

                    navController.navigate("browser")

                },

                onOpenPerformance = {

                    navController.navigate("performance")

                },

                onLogout = {

                    domainProvider.livestream.viewModelScope.launch {

                        domainProvider.repository.addSystemLog("INFO", "Network", "Người dùng '${WebDavManager.currentUser}' đã chủ động Đăng xuất.")

                    }

                    navController.navigate("login") {

                        popUpTo(0)

                    }

                },

                // ── TÍNH NĂNG MỚI ─────────────────────────────────────────────────

                onOpenOrganizer = { navController.navigate("smart_organizer") },

                onOpenGuestPass = { navController.navigate("guest_pass") },

                onOpenSocialExtractor = { navController.navigate("social_extractor") },

                onStartScreenRecord = onStartScreenRecord

            )

        }



        composable("browser") {

            com.nas.naswebdav.ui.screens.BrowserScreen(

                onVideo = { url ->
                    val auth = WebDavManager.currentAuthState()
                    openExternalVideoPlayer(
                        context = mContext,
                        url = url,
                        user = auth.user,
                        pass = auth.pass,
                        onError = {
                            mediaUrl = url
                            navController.navigate("video")
                        }
                    )
                },

                onImage = { url ->

                    mediaUrl = url

                    navController.navigate("image")

                },

                onLogout = {

                    domainProvider.livestream.viewModelScope.launch {

                        domainProvider.repository.addSystemLog("INFO", "Network", "Người dùng '${WebDavManager.currentUser}' đã chủ động Đăng xuất.")

                    }

                    navController.navigate("login") {

                        popUpTo(0)

                    }

                },

                onBackToMenu = {

                    fileBrowserVM.resetToDefaultMode()

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

            // AUTH FIX: snapshot credentials at composition time so the in-app player
            // matches the external player path (which already snapshots tap-time auth).
            val auth = remember { WebDavManager.currentAuthState() }

            com.nas.naswebdav.ui.screens.ExoPlayerScreen(

                url = mediaUrl,

                user = auth.user,

                pass = auth.pass,

                onBack = { navController.popBackStack() }

            )

        }



        composable("image") {

            com.nas.naswebdav.ui.screens.ImageViewerScreen(

                initialUrl = mediaUrl,

                user = WebDavManager.currentUser,

                pass = WebDavManager.currentPass,

                onBack = { navController.popBackStack() }

            )

        }



        // ─── TÍNH NĂNG MỚI: Guest Pass ────────────────────────────────────

        composable("guest_pass") {

            GuestPassScreen(

                onBack = { navController.popBackStack() }

            )

        }



        // ─── TÍNH NĂNG MỚI: Social Extractor ──────────────────────────────

        composable("social_extractor") {

            SocialExtractorScreen(

                onBack = { navController.popBackStack() }

            )

        }



        // ─── TÍNH NĂNG MỚI: Smart Organizer ────────────────────────────────

        composable("smart_organizer") {

            SmartOrganizerScreen(

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

                        domainProvider.authSession.connect(urlList, user, pass)

                        com.nas.naswebdav.scheduleIdleDuplicateScan(mContext, urlList.firstOrNull() ?: "")

                        com.nas.naswebdav.scheduleIdleSpeedTest(mContext, urlList.firstOrNull() ?: "")

                        com.nas.naswebdav.scheduleFingerprintWorker(mContext)



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

@Composable
fun ScreenRecordFloatingOverlay() {
    val isRecordingScreen by remember { ScreenRecordService.isRecordingState }
    val elapsedSec by remember { ScreenRecordService.elapsedSecondsState }
    val segIdx by remember { ScreenRecordService.segmentIndexState }
    val uploadedSegments by remember { ScreenRecordService.uploadedSegmentsState }
    val pendingSegments by remember { ScreenRecordService.pendingSegmentsState }
    val networkMode by remember { ScreenRecordService.networkModeState }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (isRecordingScreen) {
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val timeString = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)

        // Pulsating animation for the red dot
        val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = RecordingOverlayBg.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RecordingOverlayBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(shadowElevation = 8f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Pulsating red dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(RecordingOverlayBorder.copy(alpha = alpha))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Đang quay màn hình: $timeString",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${networkMode.ifBlank { "NAS" }} - đoạn ${segIdx + 1}, đã gửi $uploadedSegments, chờ $pendingSegments",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Button(
                        onClick = {
                            val stopIntent = android.content.Intent(context, ScreenRecordService::class.java).apply {
                                action = ScreenRecordService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RecordingOverlayBorder),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Dừng",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}



