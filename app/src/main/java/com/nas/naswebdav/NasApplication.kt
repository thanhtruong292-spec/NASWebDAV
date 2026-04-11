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

/**
 * Application class cung cấp singleton Database và OkHttpClient cho toàn bộ ứng dụng.
 * Tránh tạo nhiều Database/OkHttpClient instance trong mỗi Worker/Activity.
 */
class NasApplication : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "nas-db"
        )
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
            .fallbackToDestructiveMigration()
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
            .build()
    }

    /** OkHttpClient cho Local API calls nhanh (sync check, hash batch, docker) — timeout ngắn */
    val fastApiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(AppConfig.FAST_API_POOL_SIZE, AppConfig.FAST_API_POOL_KEEPALIVE_MINUTES, TimeUnit.MINUTES))
            .build()
    }

    /** OkHttpClient CHUYÊN DỤNG cho Video Streaming — connection pool lớn, timeout dài, keep-alive */
    val videoStreamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Bandwidth meter toàn cục — đo tốc độ mạng qua nhiều phiên xem video */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val bandwidthMeter: DefaultBandwidthMeter by lazy {
        DefaultBandwidthMeter.Builder(this)
            .setResetOnNetworkTypeChange(false) // Giữ lại dữ liệu tốc độ khi đổi mạng
            .build()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val videoCache: SimpleCache by lazy {
        val cacheDirectory = File(cacheDir, "exoplayer_video_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024) // 2GB Cache
        val databaseProvider = StandaloneDatabaseProvider(this)
        SimpleCache(cacheDirectory, evictor, databaseProvider)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun buildCacheDataSourceFactory(user: String, pass: String): CacheDataSource.Factory {
        // TỐI ƯU: Dùng OkHttpDataSource thay vì DefaultHttpDataSource
        // → Tái sử dụng Connection Pool (15 kết nối, keep-alive 5 phút)
        // → Giảm latency handshake HTTP từ ~200ms xuống ~0ms cho request thứ 2+
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(videoStreamingClient)
            .setDefaultRequestProperties(mapOf("Authorization" to okhttp3.Credentials.basic(user, pass)))

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

        /**
         * FIX P12: Application-scoped CoroutineScope thay thế GlobalScope trong SystemLogger.
         * SupervisorJob đảm bảo một coroutine lỗi không huỷ các coroutine khác.
         * Scope này tồn tại suốt vòng đời tiến trình App.
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // TÍNH NĂNG 4.G: Global Crash Handler (Lưu log trước khi văng chết ngất)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                database.logDao().insertLog(
                    SystemLog(
                    type = "CRASH",
                    module = "CrashHandler",
                    message = "${exception.javaClass.simpleName}: ${exception.message}"
                ))
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, exception)
        }
            // TÍNH NĂNG 1.B: Auto dọn rác Thumbnail Coil (Tuổi thọ > 7 ngày)
        Thread {
            try {
                val coilCacheDir = java.io.File(cacheDir, "image_cache")
                val maxAge = 7 * 24 * 60 * 60 * 1000L
                val now = System.currentTimeMillis()
                coilCacheDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > maxAge) file.delete()
                }
            } catch (e: Exception) {}
        }.start()
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

    const val HASH_HAMMING_THRESHOLD = 5

    const val ENABLE_CERT_PINNING = false
    const val CERT_PINNING_HOST = "nas.example.com"
    const val CERT_PINNING_HASH = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    const val SMART_SWITCH_PING_TIMEOUT_MS = 2000

    const val SOCIAL_DOWNLOAD_FOLDER = "Downloads/social/"

    const val GUEST_PASS_DEFAULT_MINUTES = 60
}

fun String.toApiBaseUrl(): String {
    val p = java.net.URL(this)
    return "${p.protocol}://${p.host}:5050"
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

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: run {
                var prefs: SharedPreferences? = null
                try {
                    val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                    prefs = EncryptedSharedPreferences.create(
                        PREFS_NAME,
                        masterKey,
                        context.applicationContext,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: Exception) {
                    android.util.Log.e("SecurePrefs", "Lỗi tạo KeyStore, chuyển sang SharedPreferences thường", e)
                    prefs = context.getSharedPreferences("nas_prefs_fallback", Context.MODE_PRIVATE)
                }
                migrateOldCredentialsIfNeeded(context, prefs)
                securePrefs = prefs
                prefs!!
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
            securePrefs.edit()
                .putString(KEY_URL, oldUrl)
                .putString(KEY_USER, oldUser ?: "")
                .putString(KEY_PASS, oldPass ?: "")
                .apply()
            oldPrefs.edit().remove(KEY_URL).remove(KEY_USER).remove(KEY_PASS).apply()
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
            getSecurePrefs(context).edit()
                .putString(KEY_URL_LIST, jsonArray.toString())
                .putString(KEY_URL, urlList.firstOrNull() ?: "")
                .putString(KEY_USER, user)
                .putString(KEY_PASS, pass)
                .apply()
        } catch (e: Exception) {}
    }

    fun saveCredentialsAsync(context: Context, urlList: List<String>, user: String, pass: String, onComplete: () -> Unit = {}) {
        Thread {
            try {
                val prefs = getSecurePrefs(context)
                val jsonArray = org.json.JSONArray()
                urlList.forEach { jsonArray.put(it) }
                prefs.edit()
                    .putString(KEY_URL_LIST, jsonArray.toString())
                    .putString(KEY_URL, urlList.firstOrNull() ?: "")
                    .putString(KEY_USER, user)
                    .putString(KEY_PASS, pass)
                    .apply()
            } catch (e: Exception) {}
            android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete() }
        }.start()
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
            } catch (e: Exception) {}
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
            val now = System.currentTimeMillis()
            
            // Nếu cache còn hạn, trả về kết quả đã đánh giá ĐÚNG NHẤT thay vì lấy đại urlList.first()
            if (now - lastPingTime < CACHE_DURATION_MS && lastPingResult && cachedActiveUrl != null) {
                return@withContext cachedActiveUrl!!
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
                
                lastPingResult = true; lastPingTime = now
                cachedActiveUrl = bestResult
                
                android.util.Log.i(TAG, "✅ Chọn mạng tốt nhất: $bestResult")
                return@withContext bestResult
            }
            lastPingResult = false
            cachedActiveUrl = null
            // Tất cả URL đều không phản hồi → thử dùng URL đầu tiên nhưng log cảnh báo
            android.util.Log.w(TAG, "⚠️ Không ping được bất kỳ URL NAS nào! Fallback: ${urlList.first()}")
            urlList.first()
        }

    suspend fun getActiveApiHost(context: Context): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { java.net.URL(getActiveBaseUrl(context)).host } catch (_: Exception) { "" }
        }

    private fun checkNasReachabilityQuickly(lanUrl: String, user: String, pass: String): Boolean {
        if (lanUrl.isEmpty()) return false
        return try {
            val parsedHost = java.net.URL(lanUrl).host ?: return false
            val apiUrl = "http://$parsedHost:5050/api/status"
            
            // Tailscale relay cần 3-5 giây cho lần handshake đầu tiên → tăng timeout
            val isTailscale = com.nas.naswebdav.isTailscaleUrl(lanUrl)
            val timeoutSec = if (isTailscale) 5L else 2L
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                
            val request = okhttp3.Request.Builder()
                .url(apiUrl)
                .header("Authorization", okhttp3.Credentials.basic(user, pass))
                .build()
                
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
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
            tile.updateTile()
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
                tile.updateTile()

                CoroutineScope(Dispatchers.IO).launch {
                    WolUtil.smartWakeOnLan(macStr)

                    // Giữ đèn báo sáng 1.5 giây rồi tắt (Mô phỏng như nút khởi động xe hơi)
                    kotlinx.coroutines.delay(1500)
                    tile.state = Tile.STATE_INACTIVE
                    tile.updateTile()
                }
            } else {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
            }
        }
    }
}
