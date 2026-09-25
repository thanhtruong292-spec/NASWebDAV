package com.nas.naswebdav



import android.util.Xml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import okhttp3.*

import okhttp3.MediaType.Companion.toMediaTypeOrNull

import okhttp3.RequestBody.Companion.toRequestBody

import org.xmlpull.v1.XmlPullParser

import java.io.InputStream

import java.net.URL

import java.util.concurrent.TimeUnit

import java.util.concurrent.ConcurrentHashMap

import okio.BufferedSink

import okio.buffer

import okio.source

import androidx.room.withTransaction

import androidx.paging.Pager

import androidx.paging.PagingConfig

import androidx.paging.PagingData

import androidx.paging.map

import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.map

import kotlinx.coroutines.sync.Mutex

import kotlinx.coroutines.sync.withLock



// @Immutable: toàn val immutable — Compose skip recomposition khi equals không đổi.
@androidx.compose.runtime.Immutable
data class NasFile(

    val name: String,

    val path: String,

    val isDirectory: Boolean,

    val contentType: String?,

    val contentLength: Long,

    val lastModified: Long = 0L,

    val partialHash: String? = null

)



// Động cơ WebDAV hiệu suất cao - Không sử dụng Sardine

private fun decodeWebDavSegment(segment: String): String {
    return runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
}

internal fun encodeWebDavSegment(segment: String): String {
    return java.net.URLEncoder.encode(decodeWebDavSegment(segment), "UTF-8").replace("+", "%20")
}

internal fun toValidUrl(rawUrl: String): String {
    if (rawUrl.isBlank()) return rawUrl
    val safe = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
    return runCatching {
        val uri = java.net.URI(safe)
        if (uri.isAbsolute && uri.host != null && !safe.contains(" ")) {
            uri.toASCIIString()
        } else throw IllegalArgumentException("Need manual encode")
    }.getOrElse {
        try {
            val schemeAndHost = if (safe.contains("/webdav")) safe.substringBefore("/webdav") else ""
            val relativePath = if (schemeAndHost.isNotEmpty()) safe.substringAfter("/webdav") else safe
            val encodedPath = relativePath.split("/").joinToString("/") { segment ->
                if (segment.isEmpty()) "" else encodeWebDavSegment(segment)
            }
            if (schemeAndHost.isNotEmpty()) "$schemeAndHost/webdav$encodedPath" else encodedPath
        } catch (_: Exception) {
            safe.replace(" ", "%20")
        }
    }
}

internal fun buildWebDavTrashTargetUrl(baseUrl: String, sourcePath: String, fileName: String, isDirectory: Boolean): String {
    val normalizedBase = baseUrl.trimEnd('/')
    val relativePath = sourcePath.removePrefix(baseUrl).removePrefix(normalizedBase).trimStart('/')
    val driveName = relativePath.substringBefore('/')
    val encodedDriveName = encodeWebDavSegment(driveName)
    val encodedName = encodeWebDavSegment(fileName)
    var targetUrl = "$normalizedBase/$encodedDriveName/.trash/$encodedName"
    if (isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
    return targetUrl
}

internal fun buildWebDavRestoreTargetUrl(baseUrl: String, sourcePath: String, fileName: String, isDirectory: Boolean): String {
    val normalizedBase = baseUrl.trimEnd('/')
    val relativePath = sourcePath.removePrefix(baseUrl).removePrefix(normalizedBase).trimStart('/')
    val driveName = relativePath.substringBefore('/')
    val encodedDriveName = encodeWebDavSegment(driveName)
    val encodedName = encodeWebDavSegment(fileName)
    var targetUrl = "$normalizedBase/$encodedDriveName/$encodedName"
    if (isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
    return targetUrl
}

/**
 * P2-8 (lop 2): dinh danh phien cho files_cache (user@host:port/root).
 * Top-level de ca WebDavManager (object) va WebDavRepository (class) deu dung.
 */
internal fun currentAccountKey(): String {
    val a = WebDavManager.currentAuthState()
    if (a.baseUrl.isBlank()) return ""
    val host = runCatching { java.net.URL(a.baseUrl).host ?: a.baseUrl }.getOrDefault(a.baseUrl)
    val port = runCatching { java.net.URL(a.baseUrl).port.takeIf { it > 0 } ?: java.net.URL(a.baseUrl).defaultPort }.getOrDefault(-1)
    val root = runCatching {
        val p = java.net.URL(a.baseUrl).path.trimEnd('/')
        if (p.isEmpty()) "/" else p
    }.getOrDefault("/")
    return "${a.user}@$host:$port$root"
}

object WebDavManager {

    data class AuthState(
        val baseUrl: String = "",
        val user: String = "",
        val pass: String = ""
    ) {
        val authHeader: String
            // FIX C5: OkHttp Credentials.basic() dùng ISO-8859-1, không hỗ trợ Unicode
            // (password chứa dấu tiếng Việt sẽ bị corrupt → NAS reject 401).
            // Encode thủ công bằng UTF-8 Base64 để đảm bảo đúng.
            get() = "Basic " + android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
    }

    val threadLocalAuth = ThreadLocal<AuthState>()

    @Volatile
    private var authState = AuthState()

    // P2-8: flag xoa cache khi doi phien. Duoc drain dong bo o dau moi lan
    // doc/ghi files_cache (da o IO context) — khong launch coroutine le.
    private val pendingCacheClear = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Drain flag doi-phien: xoa sach files_cache neu phien da doi. Goi o dau moi DB access (IO). */
    internal fun drainPendingCacheClear() {
        if (pendingCacheClear.compareAndSet(true, false)) {
            try {
                NasApplication.instance.database.fileDao().clearAllFiles()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                // Xoa that bai -> dat lai flag de lan sau thu lai (khong de lo).
                pendingCacheClear.set(true)
            }
        }
    }

    val currentBaseUrl: String
        get() = authState.baseUrl

    val currentUser: String
        get() = authState.user

    val currentPass: String
        get() = authState.pass

    fun currentAuthHeader(): String = authState.authHeader

    fun currentAuthState(): AuthState = authState

    private const val LOGIN_CALL_GROUP = "login"
    const val CALL_GROUP_LOGIN = "login"
    const val CALL_GROUP_BROWSER = "browser"
    const val CALL_GROUP_TRANSFER = "transfer"

    // OOM guard: giới hạn kích thước body đọc vào RAM cho PROPFIND/error/JSON nhỏ.
    // PROPFIND Depth:1 một thư mục thường < 1MB; 8MB đủ cho ~20k entries.
    const val MAX_PROPFIND_BYTES = 8L * 1024 * 1024
    const val MAX_PROPFIND_ENTRIES = 20_000
    const val MAX_PROPFIND_PARSE_STEPS = 500_000
    const val MAX_ERROR_BODY_BYTES = 8 * 1024
    const val MAX_JSON_BODY_BYTES = 256 * 1024

    /**
     * Đọc response body tối đa [maxBytes], tự đóng stream.
     * Trả null khi body rỗng hoặc vượt cap (tránh OOM thư viện lớn).
     */
    fun readCappedBody(response: okhttp3.Response, maxBytes: Int): String? {
        val body = response.body ?: return null
        body.byteStream().use { stream ->
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            return out.toString("UTF-8")
        }
    }

    private fun Request.Builder.withAuth(auth: AuthState): Request.Builder {
        return tag(AuthState::class.java, auth)
    }

    private fun Request.Builder.withCallGroup(group: String): Request.Builder {
        return tag(String::class.java, group)
    }

    fun tagCurrentAuth(builder: Request.Builder): Request.Builder {
        val currentAuth = threadLocalAuth.get() ?: authState
        return builder.tag(AuthState::class.java, currentAuth)
    }

    fun Request.Builder.withCurrentAuth(): Request.Builder {
        val currentAuth = threadLocalAuth.get() ?: authState
        return this.tag(AuthState::class.java, currentAuth)
    }

    // Kế thừa kết nối (Connection Pooling) & Keep-Alive

    internal val optimizedClient: OkHttpClient by lazy {

        val dispatcher = Dispatcher().apply {

            maxRequests = 32       // OPTIMIZE: cho phép HTTP/2 multiplexing trên LAN

            maxRequestsPerHost = 8  // STD-1 fix: giảm 16 → 8. Chia sẻ quota với sharedHttpClient + fastApiClient (cùng NAS host). NAS RK3328 yếu, 16 concurrent gây TCP retransmit.

        }

        NasApplication.instance.sharedHttpClient.newBuilder()

            .followRedirects(false) // FIX LỖI P0 CAO CẤP: Không cho phép tự ý chuyển PROPFIND thành GET khi NAS (ngu ngốc) trả về HTTP 301 Redirect.

            .followSslRedirects(false)

            .writeTimeout(30, TimeUnit.MINUTES) // FIX C2: giới hạn 30 phút thay vì 0 (vô hạn) — nếu NAS ngừng ACK giữa chừng, không treo vĩnh viễn

            .dispatcher(dispatcher)

            .addInterceptor { chain ->

                val auth = chain.request().tag(AuthState::class.java) ?: authState
                val credential = auth.authHeader

                val request = chain.request().newBuilder()
                    .header("Authorization", credential) // Bơm thẳng Preemptive Auth, cực hiệu quả chống NAS Server treo khi thiếu Auth

                    .build()

                chain.proceed(request)

            }

            .apply {

                if (AppConfig.ENABLE_CERT_PINNING) {

                    certificatePinner(

                        CertificatePinner.Builder()

                            .add(AppConfig.CERT_PINNING_HOST, AppConfig.CERT_PINNING_HASH)

                            .build()

                    )

                }

            }

            .build()

    }



    // MÁY CHỦ SARDINE ĐỘC LẬP (PHÍM 15): Không chia sẻ Client với hệ thống Upload/Download

    // Nhằm giải phóng Sardine khỏi Cấm Redirect và Chèn Header BasicAuth sai lệch

    private val sardineClient: OkHttpClient by lazy {

        NasApplication.instance.sharedHttpClient.newBuilder()

            .followRedirects(true) // Sardine RẤT CẦN TÍNH NĂNG NÀY (Để bắt 301 chuyển hướng thư mục WebDAV)

            .followSslRedirects(true)

            .retryOnConnectionFailure(true)

            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)

            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

            .addInterceptor { chain ->

                // PHASE 17 TỐI QUAN TRỌNG: Preemptive Authentication (Xác thực vượt cấp)

                // NAS KHÔNG trả về 401 để kích hoạt Sardine Authenticator, mà nó trả về thư mục TRỐNG nếu không có mật khẩu ngay từ đầu!

                val auth = chain.request().tag(AuthState::class.java) ?: authState
                val credential = auth.authHeader

                val request = chain.request().newBuilder()
                    .header("Authorization", credential)
                    .build()

                chain.proceed(request)

            }
            .apply {

                if (AppConfig.ENABLE_CERT_PINNING) {

                    certificatePinner(

                        CertificatePinner.Builder()

                            .add(AppConfig.CERT_PINNING_HOST, AppConfig.CERT_PINNING_HASH)

                            .build()

                    )

                }

                // Khi không bật Cert Pinning → sử dụng hệ thống Trust Manager mặc định (Android CA Store)

                // KHÔNG bypass SSL verification để tránh tấn công Man-in-the-Middle

            }

            .build()

    }



    @Synchronized
    fun connect(url: String, user: String, pass: String) {

        val safeUrl = if (url.isNotEmpty() && !url.endsWith("/")) "$url/" else url

        // P2-8 (lop 1): doi phien (NAS/user khac) -> xoa cache file cu + reset
        // QueryCache de phien sau khong thay ten/path cua phien truoc (lo metadata).
        // Xoa async (khong block login); insert sau cua phien moi se nap lai.
        val prev = authState
        val identityChanged = prev.baseUrl.isNotEmpty() &&
            (prev.baseUrl != safeUrl || prev.user != user)
        // F7: lan connect DAU TIEN cua process (prev rong) cung drain — row
        // legacy key rong tu DB cu khong ro chu, khong de doc chung. Cache
        // rebuild sau vai giay duyet.
        val firstConnect = prev.baseUrl.isEmpty()
        authState = AuthState(safeUrl, user, pass)
        if (identityChanged || firstConnect) {
            QueryCache.clear()
            // P2-8: danh dau dirty thay vi launch coroutine xoa DB (coroutine le
            // gay race voi Robolectric SQLite trong unit test + kho kiem soat
            // thread). Lan doc/ghi DB tiep theo (da o IO) se xoa dong bo truoc.
            pendingCacheClear.set(true)
        }

    }

    /**
     * Cancel calls thuộc [group] (CALL_GROUP_*). Không default param —
     * caller phải chỉ định explicit để tránh cancel nhầm scope.
     */
    fun cancelLoginCalls() = cancelActiveCalls(CALL_GROUP_LOGIN)
    fun cancelBrowserCalls() = cancelActiveCalls(CALL_GROUP_BROWSER)
    fun cancelTransferCalls() = cancelActiveCalls(CALL_GROUP_TRANSFER)
    fun cancelActiveCalls(group: String) {
        fun cancelMatching(calls: List<okhttp3.Call>) {
            calls.filter { it.request().tag(String::class.java) == group }
                .forEach { it.cancel() }
        }
        cancelMatching(optimizedClient.dispatcher.queuedCalls())
        cancelMatching(optimizedClient.dispatcher.runningCalls())
        cancelMatching(sardineClient.dispatcher.queuedCalls())
        cancelMatching(sardineClient.dispatcher.runningCalls())
        cancelMatching(NasApplication.instance.sharedHttpClient.dispatcher.queuedCalls())
        cancelMatching(NasApplication.instance.sharedHttpClient.dispatcher.runningCalls())
    }



    // TÍNH NĂNG 4.H: Đo lường Sức Khoẻ Mạng bằng HTTP OPTIONS cực nhẹ

    suspend fun checkPingServer(): Long? = withContext(Dispatchers.IO) {

        val auth = authState

        if (auth.baseUrl.isEmpty()) return@withContext null

        try {

            val isTailscale = isTailscaleUrl(auth.baseUrl)
            val timeoutMs = if (isTailscale) 2500L else 800L
            val pingClient = optimizedClient.newBuilder()
                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

            var best = Long.MAX_VALUE
            repeat(if (isTailscale) 1 else 3) {
                val requestBuilder = Request.Builder().withAuth(auth)
                    .withCallGroup(LOGIN_CALL_GROUP)
                    .url("${auth.baseUrl.toApiBaseUrl()}/api/ping")
                    .head()
                if (auth.user.isNotEmpty() || auth.pass.isNotEmpty()) {
                    requestBuilder.header("Authorization", auth.authHeader)
                }
                if (auth.user.isNotEmpty() || auth.pass.isNotEmpty()) {
                    requestBuilder.header("Authorization", auth.authHeader)
                }
                val start = android.os.SystemClock.elapsedRealtime()
                pingClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        best = minOf(best, android.os.SystemClock.elapsedRealtime() - start)
                    }
                }
            }

            return@withContext if (best == Long.MAX_VALUE) -1L else best

        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {

            return@withContext -1L // Chết mạng hoặc bị chặn

        }

    }



    // KIẾN TRÚC DOANH NGHIỆP: Truy vấn thông số tệp (Kích thước, ETag) an toàn, ĐÓNG kết nối ngay để tránh sập Connection Pool của OkHttp

    /** ETag hiện tại của file trên NAS (null nếu chưa tồn tại/lỗi) — dùng cho If-Match khi upload. */
    suspend fun getFileETag(url: String): String? =
        headFileHeaders(url)?.get("ETag")?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun headFileHeaders(url: String): okhttp3.Headers? = withContext(Dispatchers.IO) {

        try {

            val request = Request.Builder().withAuth(authState)

                .url(url)

                .head() // Lệnh HTTP HEAD nhanh chóng

                .build()

            optimizedClient.newCall(request).execute().use { response ->

                if (response.isSuccessful) {

                    return@withContext response.headers

                }

                return@withContext null

            }

        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {

            return@withContext null

        }

    }



    // PHASE 18: KHAI TỬ SARDINE NATIVE.

    // Thư viện Sardine-Android quá cũ (0.8) và khắt khe với WebDAV XML Namespace, khiến NAS trả về XML hợp lệ nhưng Sardine lại ngậm miệng lờ đi, tạo ra Thư mục Trống!

    // Trở lại Trình phân tích XML tuỳ chỉnh siêu cấp (Custom PullParser) gắn vào sardineClient.

    suspend fun listFiles(url: String): List<NasFile> = withContext(Dispatchers.IO) {

        val safeUrl = toValidUrl(if (url.endsWith("/")) url else "$url/")

        

        // 1. Dùng sardineClient (đã gắn sẵn Basic Auth)

                // FIX H5: Gửi body PROPFIND explicit thay vì body rỗng.
        // RFC 4918 §9.1 nói empty body = "all properties" nhưng một số firmware NAS
        // (Synology older, router mini-NAS) trả về propstat rỗng khi body trống.
        val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop>
  <D:getcontentlength/><D:getlastmodified/><D:getcontenttype/>
  <D:resourcetype/><D:getetag/>
</D:prop></D:propfind>""".toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
        val request = okhttp3.Request.Builder().withAuth(authState)
            .url(safeUrl)
            .method("PROPFIND", propfindBody)
            .header("Depth", "1")
            .withCallGroup(CALL_GROUP_BROWSER)
            .build()

            

        val result = mutableListOf<NasFile>()

        sardineClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readCappedBody(response, MAX_ERROR_BODY_BYTES)?.take(200) ?: ""
                throw Exception("Mã lỗi NAS: ${response.code} - $errorBody")
            }

            // OOM guard: tu choi PROPFIND qua lon truoc khi parse.
            val declaredLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            if (declaredLength > MAX_PROPFIND_BYTES) {
                throw Exception("Thư mục quá lớn (${declaredLength / 1024 / 1024}MB), dùng tìm kiếm hoặc chia nhỏ thư mục")
            }

            val rawStream = response.body?.byteStream() ?: throw Exception("NAS trả về dữ liệu rỗng")
            // Chan byte thuc doc — chunked vuot cap khong bi lot.
            var bytesRead = 0L
            val byteStream = object : java.io.FilterInputStream(rawStream) {
                override fun read(): Int {
                    val b = super.read()
                    if (b >= 0) {
                        bytesRead++
                        if (bytesRead > MAX_PROPFIND_BYTES) throw Exception("Thư mục quá lớn, dùng tìm kiếm hoặc chia nhỏ thư mục")
                    }
                    return b
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        bytesRead += n
                        if (bytesRead > MAX_PROPFIND_BYTES) throw Exception("Thư mục quá lớn, dùng tìm kiếm hoặc chia nhỏ thư mục")
                    }
                    return n
                }
            }

            // 2. Phân tích XML bằng tay - Cực kỳ khoan dung với mọi loại NAS (Sử dụng luồng trực tiếp để chống OOM)
            try {
                val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = true
                val parser = factory.newPullParser()
                parser.setInput(byteStream, "UTF-8")

                var eventType = parser.eventType
                var currentHref = ""
                var isDir = false
                var currentType = ""
                var currentLength = 0L
                var currentModTime = 0L
                var insideResponse = false
                var textBuffer = ""
                // Tolerant: NAS firmware lạ có thể trả XML phình to mà
                // Content-Length/chunked — giới hạn vòng lặp parse.
                var parseSteps = 0

                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (++parseSteps > MAX_PROPFIND_PARSE_STEPS) {
                        throw Exception("Thư mục quá nhiều file, dùng tìm kiếm hoặc chia nhỏ thư mục")
                    }
                    // Tolerant: tag lạ/namespace prefix bất thường → bỏ qua tag đó.
                    val tagName = runCatching { parser.name?.lowercase()?.substringAfter(':') ?: "" }.getOrDefault("")
                    when (eventType) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            val name = tagName
                            if (name == "response") {
                                insideResponse = true
                                currentHref = ""
                                isDir = false
                                currentType = ""
                                currentLength = 0L
                                currentModTime = 0L
                                textBuffer = "" // FIX Bug 5: reset to prevent stale data from previous response
                            } else if (name == "collection") {
                                isDir = true
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            // OOM guard: text node đơn (vd. href dài bất thường) không tràn RAM.
                            val text = parser.text ?: ""
                            textBuffer = if (text.length > 8192) text.take(8192) else text
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            val name = tagName
                            if (insideResponse) {
                                when (name) {
                                    "href" -> currentHref = textBuffer.trim()
                                    "getcontenttype" -> currentType = textBuffer.trim()
                                    "getcontentlength" -> currentLength = textBuffer.toLongOrNull() ?: 0L
                                    "getlastmodified" -> {
                                        try {
                                            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                                            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
                                            currentModTime = format.parse(textBuffer.trim())?.time ?: 0L
                                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { currentModTime = 0L }
                                    }
                                    "response" -> {
                                        if (currentHref.isNotEmpty()) {
                                            val rawUri = if (currentHref.startsWith("http", ignoreCase = true)) {
                                                currentHref
                                            } else {
                                                val baseUri = java.net.URI(safeUrl)
                                                val hrefUri = java.net.URI(currentHref)
                                                // Resolve relative hrefs against the current directory, not host root.
                                                baseUri.resolve(hrefUri).toString()
                                            }
                                            // FIX Bug 2: normalize Unicode/spaces in href before reuse by BFS.
                                            val fullUri = runCatching { java.net.URI(rawUri).toASCIIString() }
                                                .getOrDefault(rawUri)

                                            if (fullUri.trimEnd('/') != safeUrl.trimEnd('/')) {
                                                var extractedName = ""
                                                try {
                                                    val decoded = java.net.URLDecoder.decode(fullUri.trimEnd('/'), "UTF-8")
                                                    extractedName = decoded.substringAfterLast('/')
                                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                                    extractedName = fullUri.trimEnd('/').substringAfterLast('/')
                                                }
                                                val dirPath = if (isDir && !fullUri.endsWith("/")) "$fullUri/" else fullUri
                                                result.add(NasFile(extractedName, dirPath, isDir, currentType, currentLength, currentModTime))
                                                if (result.size > MAX_PROPFIND_ENTRIES) {
                                                    throw Exception("Thư mục quá nhiều file (>${MAX_PROPFIND_ENTRIES}), dùng tìm kiếm hoặc chia nhỏ thư mục")
                                                }
                                            }
                                        }
                                        insideResponse = false
                                    }
                                }
                            }
                        }
                    }
                    eventType = runCatching { parser.next() }.getOrElse {
                        // XML hong giua chung -> throw de caller giu cache cu.
                        // Ban cu nuot partial roi thay cache bang danh sach thieu.
                        throw Exception("Danh sách thư mục bị cắt giữa chừng, giữ cache cũ và thử tải lại")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.e("NAS_XML", "Lỗi phân tích XML thủ công", e)
                throw Exception("NAS trả về cấu trúc XML lạ không thể đọc: ${e.message}")
            }
        }

        

        result

    }



    // Tải lên trực tiếp luồng (Streaming Upload), không nạp file vào RAM

    suspend fun uploadStreamWithProgress(

        fileUrl: String, inputStream: InputStream, totalContentLength: Long,

        contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,

        currentETag: String? = null

    ) = withContext(Dispatchers.IO) {

        val uploadStartMs = System.currentTimeMillis()
        val requestBody = object : RequestBody() {

            override fun contentType() = contentType.toMediaTypeOrNull()

            override fun contentLength() = totalContentLength

            override fun writeTo(sink: BufferedSink) {

                inputStream.source().use { source ->

                    // OPTIMIZE: 256KB buffer cho LAN throughput (1Gbps = ~125MB/s,
                    // 8KB chunks = 15000 syscalls/MB). Giữ 8KB khi speed-limit đang active
                    // để throttle math chính xác hơn (smaller chunks = tighter rate control).
                    val speedLimit = AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC
                    val bufferSize = if (speedLimit == 0L) 262144L else 8192L

                    var totalBytesRead = 0L
                    var readCount = 0L

                    // Throttle timing chỉ cần thiết khi speed-limit > 0
                    val throttleStartTime = if (speedLimit > 0) System.currentTimeMillis() else 0L

                    while (source.read(sink.buffer, bufferSize).also { readCount = it } != -1L) {

                        sink.emit()

                        totalBytesRead += readCount
                        onProgress(totalBytesRead, totalContentLength)

                        // Throttle: nếu đang vượt tốc, chờ cho kịp
                        if (speedLimit > 0) {

                            val elapsedMs = System.currentTimeMillis() - throttleStartTime

                            val expectedMs = (totalBytesRead * 1000L) / speedLimit

                            val delayMs = expectedMs - elapsedMs

                            if (delayMs > 10) {
                                // Thread.sleep trong writeTo callback (non-suspend) — giữ nguyên
                                Thread.sleep(delayMs.coerceAtMost(2000))
                            }

                        }

                    }

                }

            }

        }



        val requestBuilder = Request.Builder().withAuth(authState).url(fileUrl).put(requestBody)
        currentETag?.let { requestBuilder.header("If-Match", it) }
        requestBuilder.withCallGroup(CALL_GROUP_TRANSFER)
        val request = requestBuilder.build()

        // OPTIMIZE: dùng thẳng optimizedClient — không tạo builder mới mỗi lần upload
        // để tái sử dụng Connection Pool, Dispatcher, Interceptors, HTTP/2 streams.
        // writeTimeout = 30 phút (FIX C2) — nếu NAS ngừng ACK, upload timeout thay vì treo vĩnh viễn.
        optimizedClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) throw Exception("NAS từ chối tệp: ${response.code}")

        }

    }



    // TÍNH NĂNG MỚI 3.A: GZIP Streaming Upload (Nén luồng thời gian thực)

    // Tự động bóp méo luồng dữ liệu thành định dạng GZIP thu nhỏ đến 80% dung lượng mạng trước khi lên sóng.

    suspend fun uploadCompressedStream(

        fileUrl: String, inputStream: InputStream, totalUncompressedLength: Long,

        contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit

    ) = withContext(Dispatchers.IO) {

        val requestBody = object : RequestBody() {

            override fun contentType() = contentType.toMediaTypeOrNull()

            // Chunked transfer encoding vì độ dài GZIP bị thay đổi liên tục, không thể biết trước khối lượng cuối cùng

            override fun contentLength() = -1L

            

            override fun writeTo(sink: BufferedSink) {
                val gzipSink = okio.GzipSink(sink)
                val bufferedGzip = gzipSink.buffer()
                try {
                    inputStream.source().use { source ->
                        var totalBytesRead = 0L
                        var readCount = 0L
                        val bufferSize = 8192L
                        while (source.read(bufferedGzip.buffer, bufferSize).also { readCount = it } != -1L) {
                            bufferedGzip.emit()
                            totalBytesRead += readCount
                            // Cập nhật thẻ Progress theo mốc dung lượng gốc (Uncompressed)
                            onProgress(totalBytesRead, totalUncompressedLength)
                        }
                        bufferedGzip.flush()
                    }
                } finally {
                    // FIX C4: flush + close trong finally để gzip footer (CRC32+ISIZE)
                    // luôn được ghi — nếu source.read throw, server sẽ nhận truncated gzip
                    // mà không có footer → decompression fail thay vì silent corruption.
                    try { bufferedGzip.close() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                }
            }

        }



        val request = Request.Builder().withAuth(authState)

            .url(fileUrl)

            .put(requestBody)

            .header("Content-Encoding", "gzip") // Kích hoạt vòi xả GZIP phía Server NGINX / NAS

            .build()



        // FIX C3: optimizedClient đã có writeTimeout 30 phút (xem C2 fix).
        // Dùng trực tiếp newCall() để giữ connection pool / dispatcher singleton.
        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("NAS từ chối tệp nén GZIP: ${response.code}")
        }

    }

    suspend fun resumeUploadStreamWithProgress(

        fileUrl: String, inputStream: InputStream, totalContentLength: Long,

        uploadedBytes: Long, contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit

    ) = withContext(Dispatchers.IO) {

        var remainingToSkip = uploadedBytes
        val skipBuffer = ByteArray(8192)
        while (remainingToSkip > 0) {
            var step = inputStream.skip(remainingToSkip)
            if (step <= 0L) {
                // Some streams transiently return 0; retry once before falling back.
                step = inputStream.skip(remainingToSkip)
            }
            if (step > 0L) {
                remainingToSkip -= step
            } else {
                val toRead = minOf(remainingToSkip, skipBuffer.size.toLong()).toInt()
                val read = inputStream.read(skipBuffer, 0, toRead)
                if (read <= 0) break
                remainingToSkip -= read
            }
        }

        val remainingBytes = totalContentLength - uploadedBytes



        val requestBody = object : RequestBody() {

            override fun contentType() = contentType.toMediaTypeOrNull()

            override fun contentLength() = remainingBytes

            override fun writeTo(sink: BufferedSink) {

                inputStream.source().use { source ->

                    var currentTotalRead = uploadedBytes

                    var readCount = 0L

                    val bufferSize = 8192L

                    var lastUpdate = 0L

                    while (source.read(sink.buffer, bufferSize).also { readCount = it } != -1L) {

                        sink.emit()

                        currentTotalRead += readCount

                        val now = System.currentTimeMillis()

                        if (now - lastUpdate > 200) {

                            onProgress(currentTotalRead, totalContentLength)

                            lastUpdate = now

                        }

                    }

                    onProgress(currentTotalRead, totalContentLength)

                }

            }

        }



        val request = Request.Builder().withAuth(authState)

            .url(fileUrl)

            .header("Content-Range", "bytes $uploadedBytes-${totalContentLength - 1}/$totalContentLength")

            .put(requestBody)

            .build()



        // FIX C3: optimizedClient đã có writeTimeout 30 phút (xem C2 fix).
        // Dùng trực tiếp newCall() để giữ connection pool / dispatcher singleton.
        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("NAS từ chối Resume: ${response.code}")
            }
        }

    }



    suspend fun getPartialHashStream(url: String): String? = withContext(Dispatchers.IO) {

        try {

            val request = Request.Builder().withAuth(authState)

                .url(url)

                .header("Range", "bytes=0-1048575")

                .withCallGroup(CALL_GROUP_TRANSFER)

                .build()

            optimizedClient.newCall(request).execute().use { response ->

                if (!response.isSuccessful && response.code != 206) return@withContext null

                val byteStream = response.body?.byteStream() ?: return@withContext null
                val md = java.security.MessageDigest.getInstance("MD5")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (byteStream.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
                md.digest().joinToString("") { "%02x".format(it) }

            }

        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    }

    /**
     * SHA-256 phone-side: download first 1MB then compute SHA-256 on phone CPU.
     * Cheaper than full-file hash for duplicate detection, while still using phone CPU.
     */
    suspend fun getSha256PhoneStream(url: String): String? = withContext(Dispatchers.IO) {
        try {
            // Timeout 30s — tránh block hash stage vô hạn khi NAS không phản hồi
            kotlinx.coroutines.withTimeout(30_000L) {
                val request = Request.Builder().withAuth(authState)
                    .url(url)
                    .header("Range", "bytes=0-1048575")
                    .withCallGroup(CALL_GROUP_TRANSFER)
                    .build()
                optimizedClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) return@withTimeout null
                    val stream = response.body?.byteStream() ?: return@withTimeout null
                    stream.use { com.nas.naswebdav.utils.HashUtils.computeSha256Partial(it, 1048576L) }
                        .takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("WebDavManager", "getSha256PhoneStream timeout 30s: $url")
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }

    /**
     * Full-content SHA-256 on phone. Streams the entire file via WebDAV GET and hashes
     * locally — pushes work OFF the NAS CPU. Use only for files small enough to download.
     *
     * P1-2: nghiem ngat ve so byte va status. Ban cu chap nhan moi 2xx (ke ca
     * 206 Partial) va khong dem byte -> noi dung thieu van cho ra hash "full".
     * Quy tac moi: chi HTTP 200; so byte doc duoc phai DUNG totalSize
     * (thieu/thua -> null); loi server -> null. Khong gui Range.
     */
    suspend fun getFullSha256PhoneStream(url: String, totalSize: Long): String? = withContext(Dispatchers.IO) {
        getFullSha256WithEtag(url, totalSize)?.first
    }

    /**
     * F3: full-hash GAN VOI ETAG. Quy trinh: HEAD lay ETag (+ size) TRUOC ->
     * GET toan bo voi If-Match ETag do -> HEAD lai, ETag doi -> null.
     * Hash tra ve chi co nghia voi dung phien ban ETag kem theo; caller dung
     * CHINH ETag nay cho MOVE If-Match. Thieu/yeu ETag -> null (bo qua).
     *
     * @return Pair(hash, etag) hoac null khi khong du bang chung phien ban.
     */
    suspend fun getFullSha256WithEtag(url: String, totalSize: Long): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val head1 = try { headFileHeaders(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                ?: return@withContext null
            val remoteLen = head1["Content-Length"]?.toLongOrNull() ?: return@withContext null
            if (remoteLen != totalSize) return@withContext null
            val etag = head1["ETag"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
            if (etag.startsWith("W/")) return@withContext null
            val request = Request.Builder().withAuth(authState).url(url)
                .header("If-Match", etag)
                .build()
            val hash = optimizedClient.newCall(request).execute().use { response ->
                // P1-2: tu choi 206 Partial — hash chi co nghia khi la toan bo noi dung.
                if (response.code != 200) return@withContext null
                val stream = response.body?.byteStream() ?: return@withContext null
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                var totalRead = 0L
                stream.use {
                    while (true) {
                        val n = it.read(buffer)
                        if (n == -1) break
                        totalRead += n
                        if (totalRead > totalSize) return@withContext null // thua byte
                        digest.update(buffer, 0, n)
                    }
                }
                if (totalRead != totalSize) return@withContext null // thieu byte
                digest.digest().joinToString("") { "%02x".format(it) }
                    .takeIf { it.isNotEmpty() }
            } ?: return@withContext null
            // ETag phai giu nguyen sau download — doi giua chung -> bo.
            val head2 = try { headFileHeaders(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                ?: return@withContext null
            val etag2 = head2["ETag"]?.trim() ?: return@withContext null
            if (etag2 != etag) return@withContext null
            Pair(hash, etag)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    /**
     * P1-1: xac minh TOAN BO noi dung backup truoc khi cho phep xoa nguon.
     *
     * Quy trinh (fail-closed, khong fallback metadata):
     * 1) HEAD lay size + ETag. Size khac expectedSize -> false. ETag yeu
     *    (W/...) hoac trong -> false (khong du bang chung phien ban).
     * 2) GET toan bo voi `If-Match: <etag>` (conditional download, KHONG Range).
     *    412/that bai -> false (phien ban da doi hoac khong doc duoc).
     * 3) Stream so sanh byte-by-byte voi source (khong load het RAM).
     *    Source null/throw (trừ Cancellation) -> false. So byte phai dung
     *    expectedSize. CancellationException LUON propagate (khong bien thanh false).
     * 4) HEAD lai sau download: ETag phai giu nguyen -> chong thay doi giua chung.
     *
     * @param sourceProvider mo InputStream cua file nguon moi lan goi. Co the
     * tra null hoac throw khi khong doc duoc -> verify that bai (false).
     * @return true chi khi toan bo noi dung khop + phien ban on dinh.
     */
    suspend fun verifyBackupContent(
        url: String,
        expectedSize: Long,
        sourceProvider: () -> java.io.InputStream?,
    ): Boolean = withContext(Dispatchers.IO) {
        // 1) HEAD: size + strong ETag.
        val head1 = try { headFileHeaders(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            ?: return@withContext false
        val remoteLen = head1["Content-Length"]?.toLongOrNull() ?: return@withContext false
        if (remoteLen != expectedSize) return@withContext false
        val etag = head1["ETag"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
        if (etag.startsWith("W/")) return@withContext false // ETag yeu: khong du bang chung phien ban

        // 2) GET conditional toan bo (khong Range): stream hash remote, dem byte.
        // P1-1: hash streaming (khong nap het file vao RAM) de file lon khong OOM.
        val request = Request.Builder().withAuth(authState).url(url)
            .header("If-Match", etag)
            .build()
        val remoteDigest: String = try {
            optimizedClient.newCall(request).execute().use { response ->
                if (response.code != 200) return@withContext false // 412 = phien ban doi
                val body = response.body ?: return@withContext false
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(65536)
                var total = 0L
                body.byteStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n == -1) break
                        total += n
                        if (total > expectedSize) return@withContext false
                        digest.update(buf, 0, n)
                    }
                }
                if (total != expectedSize) return@withContext false
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return@withContext false }

        // 3) Hash source (stream). Source null/throw -> false. Count phai dung.
        // CancellationException LUON propagate (khong bien thanh false).
        val sourceDigest: String = try {
            val src = try { sourceProvider() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                ?: return@withContext false
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            var total = 0L
            src.use {
                while (true) {
                    val n = it.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > expectedSize) return@withContext false
                    digest.update(buf, 0, n)
                }
            }
            if (total != expectedSize) return@withContext false
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return@withContext false }
        if (sourceDigest != remoteDigest) return@withContext false

        // 4) HEAD lai: ETag phai giu nguyen sau download (chong thay doi giua chung).
        val head2 = try { headFileHeaders(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            ?: return@withContext false
        val etag2 = head2["ETag"]?.trim() ?: return@withContext false
        etag2 == etag
    }



    // TÍNH NĂNG 7.M: Đọc lướt nội dung File Text giới hạn dòng (Tránh lag RAM)

    suspend fun readFileText(url: String, maxLines: Int = 100): String? = withContext(Dispatchers.IO) {

        try {

            val request = Request.Builder().withAuth(authState).url(url).build()

            optimizedClient.newCall(request).execute().use { response ->

                if (!response.isSuccessful) return@withContext null

                val body = response.body ?: return@withContext null

                body.source().use { source ->

                    val sb = StringBuilder()

                    var linesRead = 0

                    while (linesRead < maxLines) {

                        val line = source.readUtf8Line() ?: break

                        sb.append(line).append("\n")

                        linesRead++

                    }

                    if (!source.exhausted()) sb.append("\n... (Đã Cắt Bớt Nội Dung Vì Quá Dài)")

                    return@withContext sb.toString()

                }

            }

        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {

            return@withContext null

        }

    }



    suspend fun createFolder(url: String) = withContext(Dispatchers.IO) {

        val safeUrl = if (url.endsWith("/")) url else "$url/"

        val request = Request.Builder().withAuth(authState).url(safeUrl).method("MKCOL", null).build()

        // Fail fast so callers can detect folder-create errors.
        optimizedClient.newCall(request).execute().use { response ->

            response.body?.close()
            if (!response.isSuccessful) {
                throw java.io.IOException("MKCOL failed: ${response.code}")
            }
        }

    }

    suspend fun deleteFile(url: String, isDirectory: Boolean = false) = withContext(Dispatchers.IO) {
        val actualIsDir = isDirectory || url.endsWith("/")
        val normUrl = if (actualIsDir && !url.endsWith("/")) "$url/" else url
        val builder = Request.Builder().withAuth(authState).url(normUrl).method("DELETE", null)
        if (actualIsDir) {
            builder.header("Depth", "Infinity")
        }
        val request = builder.build()

        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readCappedBody(response, MAX_ERROR_BODY_BYTES)?.take(200)?.trim().orEmpty()
                val suffix = if (errorBody.isNotBlank()) " - $errorBody" else ""
                android.util.Log.w("WebDAV", "DELETE failed: ${response.code}$suffix")
                throw java.io.IOException("DELETE failed: ${response.code}$suffix")
            }
        }
    }

    // P1-4: MOVE mac dinh KHONG ghi de (Overwrite: F). Ban cu khong dat
    // header -> server WebDAV ghi de am tham file dich cung ten. Tat ca caller
    // (rename/move/restore/trash) deu khong muon ghi de am tham; dich ton tai
    // -> 412 de caller giai quyet xung dot (doi ten duy nhat).
    suspend fun renameFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(oldUrl).method("MOVE", null)
            .header("Destination", newUrl).header("Overwrite", "F").build()

        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readCappedBody(response, MAX_ERROR_BODY_BYTES)?.take(200)?.trim().orEmpty()
                val suffix = if (errorBody.isNotBlank()) " - $errorBody" else ""
                android.util.Log.w("WebDAV", "MOVE failed: ${response.code}$suffix")
                throw java.io.IOException("MOVE failed: ${response.code}$suffix")
            }
        }
    }

    // FIX-REVIEW-24/09-#5: MOVE khong ghi de (Overwrite: F) — dung cho luan
    // temp+MOVE create-only cua backup: dich da ton tai -> 412, giu nguyen ban
    // co san thay vi ghi de mat du lieu nguoi dung.
    suspend fun moveFileNoOverwrite(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(oldUrl).method("MOVE", null)
            .header("Destination", newUrl).header("Overwrite", "F").build()

        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readCappedBody(response, MAX_ERROR_BODY_BYTES)?.take(200)?.trim().orEmpty()
                val suffix = if (errorBody.isNotBlank()) " - $errorBody" else ""
                throw java.io.IOException("MOVE failed: ${response.code}$suffix")
            }
        }
    }

    // FIX-REVIEW-24/09-#5: PUT create-only (If-None-Match: *) — dich da ton tai
    // -> 412 Precondition Failed, khong ghi de. Dung de danh dich truoc khi MOVE.
    suspend fun uploadStreamIfAbsent(
        fileUrl: String, inputStream: InputStream, totalContentLength: Long,
        contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val requestBody = object : RequestBody() {
            override fun contentType() = contentType.toMediaTypeOrNull()
            override fun contentLength() = totalContentLength
            override fun writeTo(sink: BufferedSink) {
                inputStream.source().use { source ->
                    var totalBytesRead = 0L
                    var readCount = 0L
                    while (source.read(sink.buffer, 262144L).also { readCount = it } != -1L) {
                        sink.emit()
                        totalBytesRead += readCount
                        onProgress(totalBytesRead, totalContentLength)
                    }
                }
            }
        }
        val request = Request.Builder().withAuth(authState).url(fileUrl).put(requestBody)
            .header("If-None-Match", "*")
            .withCallGroup(CALL_GROUP_TRANSFER)
            .build()
        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("NAS từ chối tệp: ${response.code}")
        }
    }

    // P1-4: COPY mac dinh KHONG ghi de (Overwrite: F). Ly do nhu renameFile.
    suspend fun copyFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(oldUrl).method("COPY", null)
            .header("Destination", newUrl).header("Overwrite", "F").build()

        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readCappedBody(response, MAX_ERROR_BODY_BYTES)?.take(200)?.trim().orEmpty()
                val suffix = if (errorBody.isNotBlank()) " - $errorBody" else ""
                android.util.Log.w("WebDAV", "COPY failed: ${response.code}$suffix")
                throw java.io.IOException("COPY failed: ${response.code}$suffix")
            }
        }
    }

    suspend fun uploadFile(fileUrl: String, file: java.io.File, contentType: String) = withContext(Dispatchers.IO) {


        java.io.FileInputStream(file).use { inputStream ->

            uploadStreamWithProgress(fileUrl, inputStream, file.length(), contentType, { _, _ -> })

        }

    }



    suspend fun downloadFile(url: String, destFile: java.io.File) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(url)
            .withCallGroup(CALL_GROUP_TRANSFER).build()

        optimizedClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) throw Exception("Lỗi tải xuống: ${response.code}")

            val body = response.body ?: throw Exception("Empty body")

            java.io.FileOutputStream(destFile).use { fos ->

                body.byteStream().copyTo(fos)

            }

        }

    }

    suspend fun createEmptyFile(url: String) = withContext(Dispatchers.IO) {

        val requestBody = ByteArray(0).toRequestBody(null, 0, 0)

        val request = Request.Builder().withAuth(authState).url(url).put(requestBody).build()

        optimizedClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) throw Exception("Lỗi tạo file: ${response.code}")

        }

    }

    fun extractApiError(response: okhttp3.Response): String? {
        return runCatching {
            val bodyStr = readCappedBody(response, MAX_JSON_BODY_BYTES) ?: ""
            if (bodyStr.isNotBlank()) {
                val jsonErr = runCatching { org.json.JSONObject(bodyStr).optString("error", "").ifBlank { null } }.getOrNull()
                if (!jsonErr.isNullOrBlank()) return@runCatching jsonErr

                val match = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(bodyStr)
                match?.groupValues?.get(1)?.ifBlank { null }
            } else null
        }.getOrNull()
    }

}



// ════════════════════════════════════════════════════════════════════════════

// WebDavRepository — Truy vấn dữ liệu qua WebDAV + Room Cache

// ════════════════════════════════════════════════════════════════════════════



class WebDavRepository(

    private val webDavManager: WebDavManager,

    private val database: AppDatabase

) {

    suspend fun getCachedFiles(url: String): List<CachedFile> = withContext(Dispatchers.IO) {

        WebDavManager.drainPendingCacheClear()
        database.fileDao().getFiles(url, currentAccountKey())

    }



    fun getFilesStream(url: String): Flow<PagingData<NasFile>> {

        val key = currentAccountKey()
        return Pager(

            config = PagingConfig(pageSize = 50, enablePlaceholders = false, prefetchDistance = 20, initialLoadSize = 150),

            pagingSourceFactory = { database.fileDao().getFilesPaged(url, key) }

        ).flow.map { pagingData ->

            pagingData.map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }

        }

    }



    suspend fun getRemoteFilesAndCache(url: String): List<NasFile> = withContext(Dispatchers.IO) {

        // F7: chup key TRUOC request — doi phien giua chung thi du lieu van gan
        // key phien da gui request (khong luu nham sang phien moi). Neu key doi
        // giua chung, ket qua van nhat quan voi phien cu.
        val key = currentAccountKey()
        val remoteFiles = kotlinx.coroutines.withTimeout(120000L) { webDavManager.listFiles(url) }

        // P2-8: ghi kem accountKey cua phien hien tai; xoa cu cung scope.
        kotlinx.coroutines.withTimeout(45000L) {

            database.withTransaction {

                WebDavManager.drainPendingCacheClear()
                database.fileDao().deleteByParentPath(url, key)

                remoteFiles.chunked(500).forEach { batch ->

                    database.fileDao().insertFiles(batch.map {

                        CachedFile(path = it.path, name = it.name, isDirectory = it.isDirectory, contentType = it.contentType, parentPath = url, contentLength = it.contentLength, lastModified = it.lastModified, accountKey = key)

                    })

                }

            }

        }

        remoteFiles

    }



    suspend fun getDuplicateFiles(): List<NasFile> = withContext(Dispatchers.IO) {

        QueryCache.cached("duplicates") {

            database.fileDao().getDuplicateFiles(currentAccountKey()).map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified, it.partialHash) }

        }

    }



    suspend fun getLatestPhotos(): List<NasFile> = withContext(Dispatchers.IO) {

        database.fileDao().getLatestPhotos(currentAccountKey()).map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }

    }



    suspend fun getRecentVideos(): List<NasFile> = withContext(Dispatchers.IO) {

        database.fileDao().getRecentVideos(currentAccountKey()).map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }

    }



    suspend fun searchGlobal(keyword: String): List<NasFile> = withContext(Dispatchers.IO) {
        database.fileDao().searchFiles(keyword, currentAccountKey()).map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }
    }

    // Tìm kiếm cache giới hạn trong root — thay full-table scan 25k rows.
    suspend fun searchCacheUnder(rootPrefix: String, keyword: String): List<NasFile> = withContext(Dispatchers.IO) {
        database.fileDao().searchFilesUnder(rootPrefix, keyword, currentAccountKey()).map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }
    }

    @Deprecated("Dùng searchCacheUnder() — full-table scan gây OOM thư viện lớn")
    suspend fun getAllFilesForMap(): List<NasFile> = withContext(Dispatchers.IO) {
        database.fileDao().getAllFilesForMap().map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }
    }

    suspend fun saveDiscoveredFiles(files: List<NasFile>, parentUrl: String) = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext
        // P2-8: ghi kem accountKey phien hien tai.
        val key = currentAccountKey()
        try {
            database.fileDao().insertFiles(files.map {
                CachedFile(path = it.path, name = it.name, isDirectory = it.isDirectory, contentType = it.contentType, parentPath = parentUrl, contentLength = it.contentLength, lastModified = it.lastModified, accountKey = key)
            })
        } catch (_: Exception) {}
    }



    suspend fun getSystemLogs(): List<SystemLog> = withContext(Dispatchers.IO) { database.logDao().getRecentLogs() }

    suspend fun addSystemLog(type: String, module: String, message: String) = withContext(Dispatchers.IO) {

        database.logDao().insertLog(SystemLog(type = type, module = module, message = message))

    }

    suspend fun clearSystemLogs() = withContext(Dispatchers.IO) { database.logDao().clearAllLogs() }

    suspend fun removeDuplicateFromDb(path: String) = withContext(Dispatchers.IO) { database.fileDao().deleteFileByPath(path) }

}



object QueryCache {

    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    private const val CACHE_DURATION_MS = 5000L

    // FIX #9: Dung Mutex de ngan check-then-act race condition.
    // Khi hai coroutine dong thoi thay cache miss -> deu goi loader() -> duplicate work.
    // ConcurrentHashMap chi an toan cho single operations, khong cho compound check-then-put.
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** P2-8: xoa toan bo cache (goi khi doi phien dang nhap). */
    fun clear() {
        cache.clear()
    }

    suspend fun <T> cached(key: String, loader: suspend () -> T): T {

        // Fast-path: kiem tra ngoai lock de tranh overhead Mutex khi cache hit
        val entry = cache[key]

        if (entry != null && System.currentTimeMillis() - entry.first < CACHE_DURATION_MS) {

            @Suppress("UNCHECKED_CAST") return entry.second as T

        }

        // Slow-path: serialize bang Mutex de chi 1 coroutine goi loader()
        return mutex.withLock {

            // Double-check sau khi lay lock (coroutine khac co the da load xong)
            val entryNow = cache[key]

            if (entryNow != null && System.currentTimeMillis() - entryNow.first < CACHE_DURATION_MS) {

                @Suppress("UNCHECKED_CAST") return@withLock entryNow.second as T

            }

            val value = loader()

            cache[key] = Pair(System.currentTimeMillis(), value as Any)

            value

        }

    }

}
