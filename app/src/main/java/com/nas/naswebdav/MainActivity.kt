package com.nas.naswebdav



// Import cÃ¡c Composable Ä‘Ã£ tÃ¡ch file

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

import androidx.navigation.compose.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.launch

import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.Dispatchers



class MainActivity : androidx.fragment.app.FragmentActivity() {



    // FIX MEMORY LEAK: Loáº¡i bá» companion object (static state), dÃ¹ng biáº¿n instance thÃ´ng thÆ°á»ng

    var isPlayingVideo = false

    var videoAspectRatio = android.util.Rational(16, 9)

    

    // LÆ°u ViewModel cáº¥p Ä‘á»™ Activity Ä‘á»ƒ nháº­n Intent khi sá»‘ng ná»n

    private val viewModelFactory by lazy(LazyThreadSafetyMode.NONE) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(WebDavViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return WebDavViewModel(
                        WebDavManager,
                        WebDavRepository(WebDavManager, NasApplication.instance.database)
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
    private val viewModel: WebDavViewModel by viewModels { viewModelFactory }
    private lateinit var screenCaptureLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
    private lateinit var notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var overlayPermissionLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>



    override fun onUserLeaveHint() {

        super.onUserLeaveHint()

        // Tá»° Äá»˜NG THU NHá»Ž VIDEO: KÃ­ch hoáº¡t PiP khi ngÆ°á»i dÃ¹ng báº¥m phÃ­m Home

        if (isPlayingVideo && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {

            val params = android.app.PictureInPictureParams.Builder()

                // Sá»­a viá»n Ä‘en: DÃ¹ng tá»‰ lá»‡ gá»‘c cá»§a video (set tá»« VideoPlayerScreen) thay vÃ¬ 16:9 cá»©ng

                .setAspectRatio(videoAspectRatio)

                .build()

            try {

                enterPictureInPictureMode(params)

            } catch (e: IllegalStateException) {

                android.util.Log.w("MainActivity", "PiP unavailable on user leave", e)

            }

        }

    }

    override fun onTrimMemory(level: Int) {

        super.onTrimMemory(level)

        // Khi há»‡ thá»‘ng thiáº¿u RAM, chá»§ Ä‘á»™ng giáº£i phÃ³ng bá»™ nhá»› Ä‘á»‡m hÃ¬nh áº£nh

        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {

            coil.Coil.imageLoader(this).memoryCache?.clear()

            // FIX BUG #6: XÃ³a System.gc() â€” khÃ´ng hiá»‡u quáº£, gÃ¢y GC pause

        }

        // FIX IMAGE CACHE LEAK: Dá»n disk cache khi bá»™ nhá»› tháº¥p

        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {

            // FIX BUG #6: DÃ¹ng lifecycleScope thay vÃ¬ GlobalScope â€” trÃ¡nh rÃ² rá»‰ khi Activity bá»‹ há»§y

            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {

                coil.Coil.imageLoader(this@MainActivity).diskCache?.clear()

            }

        }

    }



    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // YÃªu cáº§u quyá»n truy cáº­p toÃ n bá»™ táº­p tin (All Files Access) tá»« Android 11+ (API 30+)

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
        requestMediaReadPermissionsIfNeeded()

        screenCaptureLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                lifecycleScope.launch {
                    val activeBaseUrl = SmartNetworkManager.getActiveBaseUrl(this@MainActivity)
                        .ifBlank { viewModel.webDavManager.currentBaseUrl }
                    val serviceIntent = android.content.Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_START
                        putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                        putExtra(ScreenRecordService.EXTRA_API_BASE, activeBaseUrl.toApiBaseUrl())
                        putExtra(ScreenRecordService.EXTRA_USER, viewModel.webDavManager.currentUser)
                        putExtra(ScreenRecordService.EXTRA_PASS, viewModel.webDavManager.currentPass)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                }
            }
        }
        notificationPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ ->
            requestScreenRecordPermission()
        }
        overlayPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {
            if (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)) {
                requestScreenRecordPermissionActual()
            } else {
                android.widget.Toast.makeText(
                    this,
                    "ChÆ°a cÃ³ quyá»n hiá»ƒn thá»‹ trÃªn cÃ¹ng nÃªn chÆ°a thá»ƒ hiá»‡n REC khi quay.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }



        // Báº¯t Intent khá»Ÿi Ä‘á»™ng á»©ng dá»¥ng tá»« tÃ­nh nÄƒng Tá»± Ä‘á»™ng ThÃ´ng bÃ¡o RÃ¡c

        handleIncomingIntent(intent)

        val dispatcher = okhttp3.Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 4 }

        val customClient = NasApplication.instance.sharedHttpClient.newBuilder()

            .dispatcher(dispatcher)

            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

            .build()



        val imageLoaderInstance = coil.ImageLoader.Builder(applicationContext)

            .okHttpClient(customClient)

            .memoryCache {

            // FIX BUG #1: Cache cá»‘ Ä‘á»‹nh theo MB thay vÃ¬ % â€” trÃ¡nh OOM trÃªn thiáº¿t bá»‹ yáº¿u

                val maxHeap = Runtime.getRuntime().maxMemory()

                val heapMb = maxHeap / (1024L * 1024L)

                val cacheMb = when {

                    heapMb < 128L -> 50L

                    heapMb > 512L -> 200L

                    else -> (heapMb * 15 / 100)  // 15% nhÆ°ng trong bounds an toÃ n

                }

                coil.memory.MemoryCache.Builder(applicationContext)

                    .maxSizeBytes((cacheMb * 1024 * 1024).toInt())

                    .build()

            }

            .diskCache {

                coil.disk.DiskCache.Builder()

                    .directory(cacheDir.resolve("image_cache"))

                    .maxSizeBytes(800L * 1024 * 1024) // FIX IMAGE CACHE LEAK: TÄƒng lÃªn 800MB (tá»‘i Æ°u cho thumbnail nhiá»u)

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
                        Box(modifier = Modifier.fillMaxSize()) {
                            NasAppNavigation(viewModel, onStartScreenRecord = { requestScreenRecordPermission() })

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
            viewModel.shouldAutoOpenDuplicates = true
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

        viewModel.webDavManager.connect(savedUrl, savedUser, savedPass)

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
                        viewModel.webDavManager.uploadFile(destUrl, temp, mimeType)
                    }
                } catch (e: Exception) {
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
            android.os.Build.VERSION.SDK_INT >= 23 -> arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
            else -> emptyArray()
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
        if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            android.widget.Toast.makeText(
                this,
                "Báº­t quyá»n hiá»ƒn thá»‹ trÃªn cÃ¹ng Ä‘á»ƒ tháº¥y REC vÃ  thá»i gian khi quay mÃ n hÃ¬nh.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        requestScreenRecordPermissionActual()
    }

    private fun requestScreenRecordPermissionActual() {
        val manager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

}



/// --- NAVIGATION ---

@OptIn(ExperimentalFoundationApi::class)

@Composable

fun NasAppNavigation(viewModel: WebDavViewModel, onStartScreenRecord: () -> Unit = {}) {

    val mContext = androidx.compose.ui.platform.LocalContext.current

    val sharedPrefs = mContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.lockNowRequested = false
    }
    var showBiometricLock by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var hasCompletedFirstResume by remember { mutableStateOf(false) }
    var requireBiometricOnReturn by remember { mutableStateOf(false) }

    var mediaUrl by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }



    // NAVIGATION COMPOSE CHUáº¨N

    val navController = androidx.navigation.compose.rememberNavController()



    // KhÃ³a láº¡i ngay khi ngÆ°á»i dÃ¹ng rá»i app. Bá» qua mÃ n Ä‘Äƒng nháº­p vÃ  láº§n resume Ä‘áº§u khi app vá»«a khá»Ÿi Ä‘á»™ng.

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







    // ============ DIALOG PHÃŠ DUYá»†T IP Láº  (TOÃ€N Cá»¤C - HIá»‚N THá»Š TRÃŠN Má»ŒI SCREEN) ============

    if (viewModel.showApprovalDialog) {

        com.nas.naswebdav.ui.dialogs.IpApprovalDialog(

            viewModel = viewModel,

            onDismiss = { viewModel.showApprovalDialog = false }

        )

    }



    // Dialog thÃ´ng bÃ¡o chung tá»« ViewModel (hiá»ƒn thá»‹ toÃ n cá»¥c)

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

            // VÃ” HIá»†U HÃ“A BACK Cá»¨NG: Cháº·n thoÃ¡t app tá»« Menu chÃ­nh

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

                    viewModel.openSpecificUrl(trashUrl, "ThÃ¹ng rÃ¡c")

                    navController.navigate("browser")

                },

                onOpenPerformance = {

                    navController.navigate("performance")

                },

                onLogout = {

                    viewModel.viewModelScope.launch {

                        viewModel.repository.addSystemLog("INFO", "Network", "NgÆ°á»i dÃ¹ng '${viewModel.webDavManager.currentUser}' Ä‘Ã£ chá»§ Ä‘á»™ng ÄÄƒng xuáº¥t.")

                    }

                    navController.navigate("login") {

                        popUpTo(0)

                    }

                },

                // â”€â”€ TÃNH NÄ‚NG Má»šI â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

                onOpenOrganizer = { navController.navigate("smart_organizer") },

                onOpenGuestPass = { navController.navigate("guest_pass") },

                onOpenSocialExtractor = { navController.navigate("social_extractor") },

                onStartScreenRecord = onStartScreenRecord

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

                        viewModel.repository.addSystemLog("INFO", "Network", "NgÆ°á»i dÃ¹ng '${viewModel.webDavManager.currentUser}' Ä‘Ã£ chá»§ Ä‘á»™ng ÄÄƒng xuáº¥t.")

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



        // â”€â”€â”€ TÃNH NÄ‚NG Má»šI: Guest Pass â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

        composable("guest_pass") {

            GuestPassScreen(

                viewModel = viewModel,

                onBack = { navController.popBackStack() }

            )

        }



        // â”€â”€â”€ TÃNH NÄ‚NG Má»šI: Social Extractor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

        composable("social_extractor") {

            SocialExtractorScreen(

                viewModel = viewModel,

                onBack = { navController.popBackStack() }

            )

        }



        // â”€â”€â”€ TÃNH NÄ‚NG Má»šI: Smart Organizer â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

        composable("smart_organizer") {

            SmartOrganizerScreen(

                viewModel = viewModel,

                onBack = { navController.popBackStack() }

            )

        }

    }



    // Hiá»ƒn thá»‹ lá»›p KhÃ³a Sinh tráº¯c há»c Ä‘Ã¨ lÃªn trÃªn má»i giao diá»‡n

    if (showBiometricLock) {

        BiometricLockScreen(

            activity = mContext as androidx.fragment.app.FragmentActivity,

            onAuthenticated = {

                showBiometricLock = false
                requireBiometricOnReturn = false

                // Bá»Ž QUA LOGIN: Náº¿u vá»«a khá»Ÿi Ä‘á»™ng app vÃ  quÃ©t vÃ¢n tay Ä‘Ãºng, tá»± Ä‘á»™ng káº¿t ná»‘i luÃ´n

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
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F).copy(alpha = 0.95f)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF1744)),
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
                                .background(Color(0xFFFF1744).copy(alpha = alpha))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Äang quay mÃ n hÃ¬nh: $timeString",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${networkMode.ifBlank { "NAS" }} - Ä‘oáº¡n ${segIdx + 1}, Ä‘Ã£ gá»­i $uploadedSegments, chá» $pendingSegments",
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Dá»«ng",
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




