package com.nas.naswebdav



import android.util.Xml

import kotlinx.coroutines.Dispatchers

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

    val currentBaseUrl: String
        get() = authState.baseUrl

    val currentUser: String
        get() = authState.user

    val currentPass: String
        get() = authState.pass

    fun currentAuthHeader(): String = authState.authHeader

    fun currentAuthState(): AuthState = authState

    private fun Request.Builder.withAuth(auth: AuthState): Request.Builder {
        return tag(AuthState::class.java, auth)
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

        authState = AuthState(safeUrl, user, pass)

    }

    fun cancelActiveCalls() {
        optimizedClient.dispatcher.cancelAll()
        sardineClient.dispatcher.cancelAll()
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
                    .url("${auth.baseUrl.toApiBaseUrl()}/api/ping")
                    .head()
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

        val safeUrl = if (url.endsWith("/")) url else "$url/"

        

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

            .build()

            

        val result = mutableListOf<NasFile>()

        sardineClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(200) ?: ""
                throw Exception("Mã lỗi NAS: ${response.code} - $errorBody")
            }

            val byteStream = response.body?.byteStream() ?: throw Exception("NAS trả về dữ liệu rỗng")

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

                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            val name = parser.name.lowercase()
                            if (name == "response") {
                                insideResponse = true
                                currentHref = ""
                                isDir = false
                                currentType = ""
                                currentLength = 0L
                                currentModTime = 0L
                            } else if (name == "collection") {
                                isDir = true
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            textBuffer = parser.text
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            val name = parser.name.lowercase()
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
                                            val fullUri = if (currentHref.startsWith("http")) {
                                                currentHref
                                            } else {
                                                val baseUri = java.net.URI(safeUrl)
                                                val scheme = baseUri.scheme
                                                val host = baseUri.host
                                                val portStr = if (baseUri.port != -1) ":${baseUri.port}" else ""
                                                val hrefClean = if (currentHref.startsWith("/")) currentHref else "/$currentHref"
                                                "$scheme://$host$portStr$hrefClean"
                                            }
                                            
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
                                            }
                                        }
                                        insideResponse = false
                                    }
                                }
                            }
                        }
                    }
                    eventType = parser.next()
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

        contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit

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



        val request = Request.Builder().withAuth(authState).url(fileUrl).put(requestBody).build()

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
        while (remainingToSkip > 0) {
            val step = inputStream.skip(remainingToSkip)
            if (step <= 0L) {
                if (inputStream.read() == -1) break
                remainingToSkip--
            } else {
                remainingToSkip -= step
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
            val request = Request.Builder().withAuth(authState)
                .url(url)
                .header("Range", "bytes=0-1048575")
                .build()
            optimizedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) return@withContext null
                val stream = response.body?.byteStream() ?: return@withContext null
                stream.use { com.nas.naswebdav.utils.HashUtils.computeSha256Partial(it, 1048576L) }
                    .takeIf { it.isNotEmpty() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }

    /**
     * Full-content SHA-256 on phone. Streams the entire file via WebDAV GET and hashes
     * locally — pushes work OFF the NAS CPU. Use only for files small enough to download.
     */
    suspend fun getFullSha256PhoneStream(url: String, totalSize: Long): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().withAuth(authState).url(url).build()
            optimizedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val stream = response.body?.byteStream() ?: return@withContext null
                stream.use { com.nas.naswebdav.utils.HashUtils.computeSha256OnPhone(it) }
                    .takeIf { it.isNotEmpty() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
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
                val errorBody = response.body?.string()?.take(200)?.trim().orEmpty()
                val suffix = if (errorBody.isNotBlank()) " - $errorBody" else ""
                android.util.Log.w("WebDAV", "DELETE failed: ${response.code}$suffix")
                throw java.io.IOException("DELETE failed: ${response.code}$suffix")
            }
        }
    }

    suspend fun renameFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(oldUrl).method("MOVE", null).header("Destination", newUrl).build()

        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(200)?.trim().orEmpty()
                val suffix = if (errorBody.isNotBlank()) " - $errorBody" else ""
                android.util.Log.w("WebDAV", "MOVE failed: ${response.code}$suffix")
                throw java.io.IOException("MOVE failed: ${response.code}$suffix")
            }
        }
    }

    suspend fun copyFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(oldUrl).method("COPY", null).header("Destination", newUrl).build()

        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(200)?.trim().orEmpty()
                val suffix = if (errorBody.isNotBlank()) " - $errorBody" else ""
                android.util.Log.w("WebDAV", "COPY failed: ${response.code}$suffix")
                throw java.io.IOException("COPY failed: ${response.code}$suffix")
            }
        }
    }

    suspend fun uploadFile(fileUrl: String, file: java.io.File, contentType: String) = withContext(Dispatchers.IO) {


        java.io.FileInputStream(file).use { inputStream ->

            uploadStreamWithProgress(fileUrl, inputStream, file.length(), contentType) { _, _ -> }

        }

    }



    suspend fun downloadFile(url: String, destFile: java.io.File) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(url).build()

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

}



// ════════════════════════════════════════════════════════════════════════════

// WebDavRepository — Truy vấn dữ liệu qua WebDAV + Room Cache

// ════════════════════════════════════════════════════════════════════════════



class WebDavRepository(

    private val webDavManager: WebDavManager,

    private val database: AppDatabase

) {

    suspend fun getCachedFiles(url: String): List<CachedFile> = withContext(Dispatchers.IO) {

        database.fileDao().getFiles(url)

    }



    fun getFilesStream(url: String): Flow<PagingData<NasFile>> {

        return Pager(

            config = PagingConfig(pageSize = 50, enablePlaceholders = false, prefetchDistance = 20, initialLoadSize = 150),

            pagingSourceFactory = { database.fileDao().getFilesPaged(url) }

        ).flow.map { pagingData ->

            pagingData.map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }

        }

    }



    suspend fun getRemoteFilesAndCache(url: String): List<NasFile> = withContext(Dispatchers.IO) {

        val remoteFiles = kotlinx.coroutines.withTimeout(120000L) { webDavManager.listFiles(url) }

        kotlinx.coroutines.withTimeout(45000L) {

            database.withTransaction {

                database.fileDao().deleteByParentPath(url)

                remoteFiles.chunked(500).forEach { batch ->

                    database.fileDao().insertFiles(batch.map {

                        CachedFile(path = it.path, name = it.name, isDirectory = it.isDirectory, contentType = it.contentType, parentPath = url, contentLength = it.contentLength, lastModified = it.lastModified)

                    })

                }

            }

        }

        remoteFiles

    }



    suspend fun getDuplicateFiles(): List<NasFile> = withContext(Dispatchers.IO) {

        QueryCache.cached("duplicates") {

            database.fileDao().getDuplicateFiles().map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified, it.partialHash) }

        }

    }



    suspend fun getLatestPhotos(): List<NasFile> = withContext(Dispatchers.IO) {

        database.fileDao().getLatestPhotos().map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }

    }



    suspend fun getRecentVideos(): List<NasFile> = withContext(Dispatchers.IO) {

        database.fileDao().getRecentVideos().map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }

    }



    suspend fun searchGlobal(keyword: String): List<NasFile> = withContext(Dispatchers.IO) {

        database.fileDao().searchFiles(keyword).map { NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified) }

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
