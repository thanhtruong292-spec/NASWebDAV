package com.nas.naswebdav

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.File
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nas.naswebdav.utils.WolUtil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Application class cung cấp singleton Database và OkHttpClient cho toàn bộ ứng dụng.
 * Tránh tạo nhiều Database/OkHttpClient instance trong mỗi Worker/Activity.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
open class NasApplication : Application(), ImageLoaderFactory {

    // ════════════════════════════════════════════════════════════════════════════
    // Timber Tree ghi log vào Room DB — hiển thị trong phần System Log trên app
    // ════════════════════════════════════════════════════════════════════════════
    private inner class DatabaseLogTree : timber.log.Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Luôn in ra Logcat như bình thường
            super.log(priority, tag, message, t)
            // Chỉ ghi vào DB các log WARN trở lên để tránh spam
            if (priority >= android.util.Log.WARN) {
                try {
                    val type = when (priority) {
                        android.util.Log.WARN -> "WARN"
                        android.util.Log.ERROR -> "ERROR"
                        else -> "INFO"
                    }
                    val fullMsg = if (t != null) "$message\n${t.stackTraceToString()}" else message
                    applicationScope.launch(Dispatchers.IO) {
                        try {
                            database.logDao().insertLog(
                                SystemLog(type = type, module = tag ?: "Timber", message = fullMsg)
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.35) // Tăng RAM cache từ 15% lên 35%
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(500L * 1024 * 1024) // 500MB Disk Cache vĩnh viễn (thay vì 2% ~10MB)
                    .build()
            }
            .respectCacheHeaders(false) // Cố định cache trên máy, không bắt load lại từ mạng
            .components { add(VideoFrameDecoder.Factory()) }
            // FIX-COIL-TIMEOUT: use thumbnailApiClient (60s read timeout) instead of
            // fastApiClient (30s). Slow NAS thumb endpoint on RK3328 should not show
            // infinite spinner — give Coil enough time before falling through to ERROR.
            .callFactory { request -> thumbnailApiClient.newCall(request) }
            .build()
    }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "nas-db"
        )
            .addMigrations(
                MIGRATION_1_10,
                MIGRATION_2_10,
                MIGRATION_3_10,
                MIGRATION_4_10,
                MIGRATION_5_10,
                MIGRATION_6_10,
                MIGRATION_7_10,
                MIGRATION_8_10,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17
            )
            // FIX F1 CRITICAL: Không dùng fallbackToDestructiveMigration() nữa.
            // Migration v13→v14→v15 là no-op (cùng identityHash) — explicit migrations
            // giữ toàn bộ dữ liệu user (sync_queue, trash_meta, scan_checkpoints, logs).
            // Đã gỡ bỏ enableMultiInstanceInvalidation() vì nó có nguy cơ gây deadlock Binder IPC 
            // khiến các tác vụ database.withTransaction() bị treo vĩnh viễn (quay vòng vòng trên UI).
            .build()
    }

    /** OkHttpClient dùng chung cho tác vụ nặng (speed test, streaming, file index) — timeout dài */
    val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(AppConfig.MAIN_CONNECTION_POOL_SIZE, AppConfig.MAIN_CONNECTION_KEEPALIVE_MINUTES, TimeUnit.MINUTES))
            // OPTIMIZE: tăng maxRequestsPerHost để upload nhiều file song song (mặc định OkHttp = 5)
            // STD-1 fix: giảm 16 → 8. NAS RK3328 yếu, 16 concurrent WebDAV requests gây TCP retransmit tăng.
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            })
            .build()
    }

    val thumbnailApiClient: OkHttpClient by lazy {
        // Video thumbnails are generated by ffmpeg on the NAS. Large/legacy files
        // can legitimately take tens of seconds on the RK3328 CPU.
        sharedHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** OkHttpClient cho Local API calls nhanh (sync check, hash batch, docker) — timeout ngắn */
    val fastApiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(AppConfig.FAST_API_POOL_SIZE, AppConfig.FAST_API_POOL_KEEPALIVE_MINUTES, TimeUnit.MINUTES))
            // OPTIMIZE: tăng maxRequestsPerHost để song song hóa các call API lên cùng NAS
            // STD-1 fix: giảm 16 → 8 — chia sẻ quota với sharedHttpClient (cùng NAS host).
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            })
            // AUTH FIX: Interceptor inject Authorization header vào mọi API call dùng fastApiClient.
            // tagCurrentAuth() chỉ gắn AuthState làm OkHttp tag — không có interceptor này thì
            // header không bao giờ được gửi đi (fastApiClient khác với optimizedClient của WebDavManager).
            .addInterceptor { chain ->
                val authState = WebDavManager.currentAuthState()
                val req = if (authState.user.isNotEmpty()) {
                    chain.request().newBuilder()
                        .header("Authorization", authState.authHeader)
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(req)
            }
            .build()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Debug HTTP Client — Clone từ fastApiClient, gắn Chucker để soi API trên điện thoại.
    // Client chính HOÀN TOÀN KHÔNG BỊ ĐỤNG. Chucker chỉ chạy trong bản Debug.
    // ════════════════════════════════════════════════════════════════════════════
    val debugHttpClient: OkHttpClient by lazy {
        val debugPrefs = getSharedPreferences("nas_debug", Context.MODE_PRIVATE)
        if (!debugPrefs.getBoolean("network_inspector_enabled", false)) {
            fastApiClient
        } else {
            fastApiClient.newBuilder()
                .addInterceptor(
                com.chuckerteam.chucker.api.ChuckerInterceptor.Builder(applicationContext)
                    .collector(
                        com.chuckerteam.chucker.api.ChuckerCollector(
                            context = applicationContext,
                            showNotification = true,
                            retentionPeriod = com.chuckerteam.chucker.api.RetentionManager.Period.ONE_HOUR
                        )
                    )
                    .maxContentLength(250_000L)
                    .redactHeaders("Authorization") // Ẩn mật khẩu trong Chucker UI
                    .alwaysReadResponseBody(true)
                    .build()
                )
                .build()
        }
    }

    val longRunningApiClient: OkHttpClient by lazy {
        sharedHttpClient.newBuilder()
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** OkHttpClient CHUYÊN DỤNG cho Video Streaming — connection pool lớn, timeout dài, keep-alive */
    val videoStreamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // AUTH FIX: Inject UTF-8 Basic Auth header theo cùng pattern fastApiClient.
            // Trước đây caller tự gọi okhttp3.Credentials.basic() (ISO-8859-1) → password chứa
            // dấu tiếng Việt bị corrupt → NAS 401 chỉ riêng với video. authHeader dùng
            // Base64 UTF-8 (giống WebDavManager) để đảm bảo thống nhất với phần còn lại của app.
            // Chỉ inject khi request CHƯA có Authorization — nếu caller explicit set header
            // (ví dụ credential snapshot) thì giữ nguyên.
            .addInterceptor { chain ->
                val request = chain.request()
                val req = if (request.header("Authorization") != null) {
                    // Caller explicitly provided auth — respect it (e.g., snapshot creds).
                    request
                } else {
                    val authState = WebDavManager.currentAuthState()
                    if (authState.user.isNotEmpty()) {
                        request.newBuilder()
                            .header("Authorization", authState.authHeader)
                            .build()
                    } else {
                        request
                    }
                }
                chain.proceed(req)
            }
            .build()
    }

    /** Bandwidth meter toàn cục — đo tốc độ mạng qua nhiều phiên xem video */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    val bandwidthMeter: DefaultBandwidthMeter by lazy {
        DefaultBandwidthMeter.Builder(this)
            .setResetOnNetworkTypeChange(false) // Giữ lại dữ liệu tốc độ khi đổi mạng
            .build()
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    val videoCache: SimpleCache by lazy {
        val cacheDirectory = File(cacheDir, "exoplayer_video_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024) // 500MB - bảo vệ RAM 1GB device
        val databaseProvider = StandaloneDatabaseProvider(this)
        SimpleCache(cacheDirectory, evictor, databaseProvider)
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun buildCacheDataSourceFactory(user: String, pass: String): CacheDataSource.Factory {
        // TỐI ƯU: Dùng OkHttpDataSource thay vì DefaultHttpDataSource
        // → Tái sử dụng Connection Pool (15 kết nối, keep-alive 5 phút)
        // → Giảm latency handshake HTTP từ ~200ms xuống ~0ms cho request thứ 2+
        // AUTH FIX: inject UTF-8 Base64 header explicitly so logout/credential-snapshot
        // paths stay consistent with the rest of the app.
        val authHeader = WebDavManager.AuthState(user = user, pass = pass).authHeader
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(videoStreamingClient)
            .setDefaultRequestProperties(mapOf("Authorization" to authHeader))

        return CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(okHttpDataSourceFactory)
            // TỐI ƯU CỰC ĐẠI: BỎ FLAG_BLOCK_ON_CACHE. Cho phép video phát ngay lập tức từ luồng mạng OkHttp 
            // thay vì bị khựng (block) chờ ghi từng chunk vào ổ cứng Flash rùa bò của điện thoại.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    companion object {
        lateinit var instance: NasApplication
            private set

        /** Test hook: gán instance nhẹ cho Robolectric test cần lazy clients. */
        fun setInstanceForTest(app: NasApplication) {
            instance = app
        }

        /**
         * FIX P12: Application-scoped CoroutineScope thay thế GlobalScope trong SystemLogger.
         * SupervisorJob đảm bảo một coroutine lỗi không huỷ các coroutine khác.
         * Scope này tồn tại suốt vòng đời tiến trình App.
         *
         * FIX (audit #23): Gắn CoroutineExceptionHandler để uncaught exception trong
         * applicationScope không silent crash. Log ra logcat + SystemLogger để debug.
         */
        private val applicationExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { ctx, throwable ->
            android.util.Log.e(
                "AppScope",
                "Uncaught exception trong applicationScope (job=${ctx[kotlinx.coroutines.CoroutineName]?.name ?: "?"}): ${throwable.message}",
                throwable
            )
            // Không cho exception lỗi tàn nát đi vào crash handler — chỉ log
        }
        val applicationScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + applicationExceptionHandler
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // SharedStateHolder: singleton Application-scoped cho shared state giữa domain VMs
        com.nas.naswebdav.shared.SharedStateHolder.init(this)

        val debugPrefs = getSharedPreferences("nas_debug", Context.MODE_PRIVATE)
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            debugPrefs.getBoolean("database_logging_enabled", false)
        ) {
            timber.log.Timber.plant(DatabaseLogTree())
            timber.log.Timber.i("Timber đã khởi động - log WARN/ERROR sẽ ghi vào DB.")
        }

        // TÍNH NĂNG 4.G: Global Crash Handler (Lưu log trước khi văng chết ngất)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val logResult = executor.submit<Boolean> {
                        try {
                            database.logDao().insertLog(
                                SystemLog(
                                type = "CRASH",
                                module = "CrashHandler",
                                message = "${exception.javaClass.simpleName}: ${exception.message}"
                            ))
                            true
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
                    }
                    // Chờ tối đa 500ms — đủ để ghi log nhưng không ANR
                    try { logResult.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
                } finally {
                    executor.shutdown()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, exception)
        }

        // Remote crash reporting (Sentry self-hosted, opt-in).
        // Local Room CrashHandler ở trên luôn chạy — Sentry chỉ gửi khi user bật.
        com.nas.naswebdav.utils.CrashReporter.initIfOptedIn(this)

        // Network listener app-scoped — SmartNetworkManager bỏ ping khi offline.
        NetworkMonitor.start(this)
            // TÍNH NĂNG 1.B: Auto dọn rác Thumbnail Coil (Tuổi thọ > 7 ngày)
        // FIX: Thay Thread {} bằng applicationScope.launch(IO) — lifecycle-aware,
        // exception được SupervisorJob xử lý thay vì crash silent.
        applicationScope.launch(Dispatchers.IO) {
            try {
                val coilCacheDir = java.io.File(cacheDir, "image_cache")
                val maxAge = 7 * 24 * 60 * 60 * 1000L
                val now = System.currentTimeMillis()
                coilCacheDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > maxAge) file.delete()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
        }

        // Dang ky Discovery Worker dinh ky de bat cac job livestream do NAS tu
        // khoi (TikTok watcher auto-record). Khong co worker nay thi khi user
        // dong app, may dien thoai khong bao gio biet co job ngam dang chay.
        LivestreamDiscoveryWorker.schedule(this)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// AppConfig — Hằng số cấu hình tập trung
// ════════════════════════════════════════════════════════════════════════════

object AppConfig {
    const val CONNECT_TIMEOUT = 15L
    const val READ_TIMEOUT = 60L
    const val WRITE_TIMEOUT = 60L
    const val FAST_API_CONNECT_TIMEOUT = 15L
    const val FAST_API_READ_TIMEOUT = 30L
    const val SPEED_TEST_READ_TIMEOUT = 60L

    // FIX D11: Port API tập trung vào một constant, tránh hardcode":5050" rải rác
    const val API_PORT = 5050
    // WebSocket port (cảnh báo realtime, log streaming)
    const val WS_PORT = 5051

    // Polling/reconnect intervals — gom vao day de tinh chinh tap trung
    const val WS_RECONNECT_MIN_MS = 5_000L      // backoff bat dau o 5s
    const val WS_RECONNECT_MAX_MS = 120_000L    // cap o 2 phut
    const val METRICS_POLL_INTERVAL_MS = 30_000L
    const val LIVESTREAM_POLL_INTERVAL_MS = 3_000L
    const val YTDLP_POLL_INTERVAL_MS = 5_000L
    const val PING_POLL_INTERVAL_MS = 3_000L

    const val MAIN_CONNECTION_POOL_SIZE = 15
    const val MAIN_CONNECTION_KEEPALIVE_MINUTES = 5L
    const val FAST_API_POOL_SIZE = 3
    const val FAST_API_POOL_KEEPALIVE_MINUTES = 3L
    const val VIDEO_POOL_SIZE = 15
    const val VIDEO_POOL_KEEPALIVE_MINUTES = 5L

    const val PAGE_SIZE = 50
    const val PREFETCH_DISTANCE = 20
    const val INITIAL_LOAD_SIZE = 150

    const val SCAN_BATCH_SIZE = 500
    const val SEARCH_RESULT_LIMIT = 200
    const val RECENT_PHOTOS_LIMIT = 100
    const val RECENT_VIDEOS_LIMIT = 100
    const val RECENT_LOGS_LIMIT = 200

    const val DISK_CACHE_MAX_BYTES = 512L * 1024 * 1024
    const val VIDEO_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val THUMBNAIL_EXPIRE_DAYS = 30L

    const val MAX_BACKUP_RETRIES = 3
    const val STATUS_POLL_INTERVAL_MS = 3000L

    const val MAX_FILENAME_LENGTH = 200
    const val PROXY_BUFFER_SIZE = 131072
    const val THUMBNAIL_SIZE_PX = 300
    const val THUMBNAIL_QUALITY = 80

    var UPLOAD_SPEED_LIMIT_BYTES_PER_SEC: Long = 0L

    // FIX (audit #11/#18): Co the dung de pause cac polling loop nang khi
    // app o background -> tiet kiem CPU/battery/data. MainActivity cap nhat
    // qua ProcessLifecycleOwner. @Volatile vi duoc doc tu nhieu coroutine.
    @Volatile var IS_APP_FOREGROUND: Boolean = true

    const val HASH_HAMMING_THRESHOLD = 5

    const val ENABLE_CERT_PINNING = false
    const val CERT_PINNING_HOST = "nas.example.com"
    const val CERT_PINNING_HASH = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    const val SMART_SWITCH_PING_TIMEOUT_MS = 2000

    const val SOCIAL_DOWNLOAD_FOLDER = "Downloads/social/"

    const val GUEST_PASS_DEFAULT_MINUTES = 60
}

fun String.toApiBaseUrl(): String {
    val raw = trim().trimEnd('/')
    if (raw.isEmpty() || raw.startsWith("/")) return ""
    val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val p = try { java.net.URL(normalized) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return "" }
    return "${p.protocol}://${p.host}:${AppConfig.API_PORT}"
}

fun String.toFastMediaUrl(): String {
    val apiBase = toApiBaseUrl()
    if (apiBase.isEmpty()) return this

    val trimmed = trim()
    val parsedPath = runCatching { trimmed.toUri().path }.getOrNull()
        ?: runCatching { java.net.URL(trimmed).path }.getOrNull()
        ?: return this

    if (parsedPath.isBlank()) return this

    val encodedPath = java.net.URLEncoder.encode(parsedPath, "UTF-8").replace("+", "%20")
    return "$apiBase/api/media?path=$encodedPath"
}

// ════════════════════════════════════════════════════════════════════════════
// SecurePrefsHelper — Quản lý EncryptedSharedPreferences
// ════════════════════════════════════════════════════════════════════════════

object SecurePrefsHelper {

    private const val PREFS_NAME = "NasSecurePrefs"
    private const val KEY_URL = "nas_url"
    private const val KEY_URL_LIST = "nas_url_list"
    private const val KEY_TAILSCALE_URL = "tailscale_url"
    private const val KEY_USER = "nas_user"
    private const val KEY_PASS = "nas_pass"
    private const val SETTINGS_PREFS = "nas_prefs"

    @Volatile
    private var securePrefs: SharedPreferences? = null

    var isUsingInsecurePrefs = false

    // FIX #16: Flag để UI có thể hiển thị cảnh báo bảo mật khi KeyStore lỗi
    // và credential bị lưu plaintext trong SharedPreferences không mã hóa
    @Volatile
    var isUsingFallbackPrefs: Boolean = false
        private set

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: run {
                val prefs = try {
                    val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                    EncryptedSharedPreferences.create(
                        PREFS_NAME,
                        masterKey,
                        context.applicationContext,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.e("SecurePrefs", "EncryptedSharedPreferences init failed; refusing plaintext credential fallback", e)
                    isUsingFallbackPrefs = true
                    throw IllegalStateException("Secure credential storage is unavailable", e)
                }
                migrateOldCredentialsIfNeeded(context, prefs)
                securePrefs = prefs
                prefs
            }
        }
    }

    private fun migrateOldCredentialsIfNeeded(context: Context, securePrefs: SharedPreferences) {
        val oldPrefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val oldUrl = oldPrefs.getString(KEY_URL, null)
        val oldUser = oldPrefs.getString(KEY_USER, null)
        val oldPass = oldPrefs.getString(KEY_PASS, null)
        val newUrl = securePrefs.getString(KEY_URL, null)
        if (newUrl.isNullOrEmpty() && !oldUrl.isNullOrEmpty()) {
            securePrefs.edit {
                putString(KEY_URL, oldUrl)
                putString(KEY_USER, oldUser ?: "")
                putString(KEY_PASS, oldPass ?: "")
            }
            oldPrefs.edit {
                remove(KEY_URL)
                remove(KEY_USER)
                remove(KEY_PASS)
            }
            android.util.Log.i("SecurePrefs", "Đã di dời thành công thiết lập mạng cũ sang bộ nhớ bảo mật AES-256.")
        }
    }

    fun getUrl(context: Context): String =
        getSecurePrefs(context).getString(KEY_URL, "") ?: ""

    fun getUser(context: Context): String =
        getSecurePrefs(context).getString(KEY_USER, "") ?: ""

    fun getPass(context: Context): String =
        getSecurePrefs(context).getString(KEY_PASS, "") ?: ""

    fun saveCredentials(context: Context, urlList: List<String>, user: String, pass: String) {
        try {
            val jsonArray = org.json.JSONArray()
            urlList.forEach { jsonArray.put(it) }
            getSecurePrefs(context).edit {
                putString(KEY_URL_LIST, jsonArray.toString())
                putString(KEY_URL, urlList.firstOrNull() ?: "")
                putString(KEY_USER, user)
                putString(KEY_PASS, pass)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
    }

    fun saveCredentialsAsync(context: Context, urlList: List<String>, user: String, pass: String, onComplete: () -> Unit = {}) {
        // FIX: Thay Thread {} + Handler(Looper.getMainLooper()) bằng applicationScope.launch(IO)
        // để đưa callback lên Main thread qua withContext(Main). An toàn hơn vì
        // applicationScope được quản lý bởi SupervisorJob, không leak sự kiện nếu app bg.
        NasApplication.applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSecurePrefs(context)
                val jsonArray = org.json.JSONArray()
                urlList.forEach { jsonArray.put(it) }
                prefs.edit {
                    putString(KEY_URL_LIST, jsonArray.toString())
                    putString(KEY_URL, urlList.firstOrNull() ?: "")
                    putString(KEY_USER, user)
                    putString(KEY_PASS, pass)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { android.util.Log.e("NasApp", "Lưu credentials thất bại", e) }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun getUrlList(context: Context): List<String> {
        val prefs = getSecurePrefs(context)
        val listStr = prefs.getString(KEY_URL_LIST, null)
        if (listStr != null) {
            try {
                val array = org.json.JSONArray(listStr)
                val result = mutableListOf<String>()
                for (i in 0 until array.length()) result.add(array.getString(i))
                return result
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
        }
        val result = mutableListOf<String>()
        val lan = prefs.getString(KEY_URL, "") ?: ""
        val tail = prefs.getString(KEY_TAILSCALE_URL, "") ?: ""
        if (lan.isNotEmpty()) result.add(lan)
        if (tail.isNotEmpty() && tail != lan) result.add(tail)
        return result
    }

    sealed class AuthData {
        class Valid(val url: CharArray, val user: CharArray, val pass: CharArray) : AuthData() {
            override fun clear() { url.fill('\u0000'); user.fill('\u0000'); pass.fill('\u0000') }
        }
        object Invalid : AuthData() { override fun clear() {} }
        abstract fun clear()
    }

    fun readEncrypted(context: Context): AuthData {
        val prefs = getSecurePrefs(context)
        val url = getUrlList(context).firstOrNull() ?: ""
        val user = prefs.getString(KEY_USER, "") ?: ""
        val pass = prefs.getString(KEY_PASS, "") ?: ""
        return if (url.isNotEmpty()) AuthData.Valid(url.toCharArray(), user.toCharArray(), pass.toCharArray())
        else AuthData.Invalid
    }

    fun getSettingsPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    fun getTailscaleBaseUrl(context: Context): String {
        return getSecurePrefs(context).getString(KEY_TAILSCALE_URL, "") ?: ""
    }
}

// ════════════════════════════════════════════════════════════════════════════
// NetworkMonitor — ConnectivityManager listener (app-scoped).
// SmartNetworkManager đọc isOnline để bỏ qua ping khi offline thay vì
// retry mù: offline → trả URL đầu tiên ngay, không tốn timeout.
// ════════════════════════════════════════════════════════════════════════════

object NetworkMonitor {
    private val _isOnline = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isOnline: kotlinx.coroutines.flow.StateFlow<Boolean> = _isOnline

    @Volatile private var registered = false

    fun start(context: Context) {
        if (registered) return
        registered = true
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        // Trạng thái đầu: có network active không.
        _isOnline.value = cm.activeNetwork != null
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                _isOnline.value = true
                SmartNetworkManager.invalidateCache()
            }

            override fun onLost(network: android.net.Network) {
                // FIX-AUDIT-F6: bản cũ `isOnline = (activeNetwork == null)` đảo cả
                // hai nhánh (mất hết mạng → true, còn mạng khác → false).
                // onLost chỉ nghĩa là network này mất — online còn hay không phải
                // hỏi activeNetwork != null.
                _isOnline.value = cm.activeNetwork != null
                SmartNetworkManager.invalidateCache()
            }
        })
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SmartNetworkManager — Tự động chọn URL NAS nhanh nhất
// ════════════════════════════════════════════════════════════════════════════

object SmartNetworkManager {
    private const val TAG = "SmartNetwork"
    @Volatile private var lastPingResult: Boolean = false
    @Volatile private var lastPingTime: Long = 0L
    private const val CACHE_DURATION_MS = 5_000L

    @Volatile private var cachedActiveUrl: String? = null

    suspend fun getActiveBaseUrl(context: Context): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val urlList = SecurePrefsHelper.getUrlList(context)
            if (urlList.isEmpty()) return@withContext ""
            if (urlList.size == 1) return@withContext urlList[0]
            // Offline: không ping mù — trả URL đầu tiên ngay.
            if (!NetworkMonitor.isOnline.value) return@withContext urlList.first()
            val now = System.currentTimeMillis()
            
            // Nếu cache còn hạn, trả về kết quả đã đánh giá ĐÚNG NHẤT thay vì lấy đại urlList.first()
            if (now - lastPingTime < CACHE_DURATION_MS && lastPingResult && cachedActiveUrl != null) {
                return@withContext cachedActiveUrl ?: urlList.first()
            }
            
            val user = SecurePrefsHelper.getUser(context)
            val pass = SecurePrefsHelper.getPass(context)
            val reachableUrls = coroutineScope {
                val deferreds = urlList.map { url -> async(kotlinx.coroutines.Dispatchers.IO) { if (checkNasReachabilityQuickly(url, user, pass)) url else null } }
                deferreds.mapNotNull { it.await() }
            }
            if (reachableUrls.isNotEmpty()) {
                // TÍNH NĂNG: Ưu tiên mạng LAN - Tốc độ Gigabit (Nếu có cả LAN và Tailscale đều kết nối thành công, Tự Động Gạch Bỏ Tailscale)
                val lanResult = reachableUrls.find { !com.nas.naswebdav.isTailscaleUrl(it) }
                val bestResult = lanResult ?: reachableUrls.first()
                
                synchronized(this) {
                    lastPingResult = true; lastPingTime = now
                    cachedActiveUrl = bestResult
                }
                
                android.util.Log.i(TAG, "✅ Chọn mạng tốt nhất: $bestResult")
                return@withContext bestResult
            }
            synchronized(this) {
                lastPingResult = false
                cachedActiveUrl = null
            }
            // Tất cả URL đều không phản hồi → thử dùng URL đầu tiên nhưng log cảnh báo
            android.util.Log.w(TAG, "⚠️ Không ping được bất kỳ URL NAS nào! Fallback: ${urlList.first()}")
            urlList.first()
        }

    suspend fun getActiveApiHost(context: Context): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { java.net.URL(getActiveBaseUrl(context)).host } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
        }

    // FIX D7: Cache hai OkHttpClient thay vì tạo mới cho mỗi lần ping.
    // Mỗi OkHttpClient có connection pool riêng → tạo mới liên tục tốn RAM, không tái dụng connection.
    // LAN client: timeout 2s. Tailscale client: timeout 5s (relay cần handshake lâu hơn).
    private val lanPingClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(1, 30, java.util.concurrent.TimeUnit.SECONDS))
        .build()

    private val tailscalePingClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(1, 30, java.util.concurrent.TimeUnit.SECONDS))
        .build()

    private fun checkNasReachabilityQuickly(lanUrl: String, user: String, pass: String): Boolean {
        if (lanUrl.isEmpty()) return false
        return try {
            val parsedHost = java.net.URL(lanUrl).host ?: return false
            val apiUrl = "http://$parsedHost:${AppConfig.API_PORT}/api/ping"

            // Tailscale relay cần 3-5 giây cho lần handshake đầu tiên → dùng tailscalePingClient
            val isTailscale = com.nas.naswebdav.isTailscaleUrl(lanUrl)
            val client = if (isTailscale) tailscalePingClient else lanPingClient

            val request = okhttp3.Request.Builder()
                .url(apiUrl)
                .head()
                .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
    }

    suspend fun forceCheckAndGetStatus(context: Context): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val lanUrl = SecurePrefsHelper.getUrl(context)
        if (lanUrl.isEmpty()) return@withContext false
        val user = SecurePrefsHelper.getUser(context)
        val pass = SecurePrefsHelper.getPass(context)
        val result = checkNasReachabilityQuickly(lanUrl, user, pass)
        lastPingResult = result; lastPingTime = System.currentTimeMillis()
        result
    }

    fun invalidateCache() { lastPingTime = 0L }
}

// ════════════════════════════════════════════════════════════════════════════
// WolTileService (từ WolTileService.kt)
// ════════════════════════════════════════════════════════════════════════════

class WolTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
        val macStr = prefs.getString("mac_address", "") ?: ""

        val tile = qsTile
        if (tile != null) {
            // Nút chỉ sáng lên cho phép bấm nếu bạn đã từng lưu địa chỉ MAC NAS trong App
            tile.state = if (macStr.isNotBlank()) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            android.os.Handler(android.os.Looper.getMainLooper()).post { tile.updateTile() }
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
        val macStr = prefs.getString("mac_address", "") ?: ""
        val tile = qsTile ?: return

        if (macStr.isNotBlank()) {
            if (macStr.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})"))) {
                // Chớp sáng nút lên để tạo phản hồi thị giác
                tile.state = Tile.STATE_ACTIVE
                android.os.Handler(android.os.Looper.getMainLooper()).post { tile.updateTile() }

                // FIX D4: Thay CoroutineScope(IO).launch (bị leak vì không có lifecycle) bằng
                // applicationScope — tồn tại suốt vòng đời app, được quản lý bởi NasApplication.
                // WakeOnLan là fire-and-forget nên applicationScope là phù hợp nhất.
                NasApplication.applicationScope.launch(Dispatchers.IO) {
                    val result = WolUtil.smartWakeOnLan(macStr)

                    // Giữ đèn báo sáng 1.5 giây rồi tắt (Mô phỏng như nút khởi động xe hơi)
                    kotlinx.coroutines.delay(1500)
                    tile.state = if (result.success) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
                    android.os.Handler(android.os.Looper.getMainLooper()).post { tile.updateTile() }
                }
            } else {
                tile.state = Tile.STATE_UNAVAILABLE
                android.os.Handler(android.os.Looper.getMainLooper()).post { tile.updateTile() }
            }
        }
    }
}
