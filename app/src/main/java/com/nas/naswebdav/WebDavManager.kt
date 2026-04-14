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
object WebDavManager {
    var currentBaseUrl: String = ""
    var currentUser: String = ""
    var currentPass: String = ""

    // Kế thừa kết nối (Connection Pooling) & Keep-Alive
    val optimizedClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 16 // FIX BUG #7: Giảm từ 64 xuống 16 - tránh quá tải NAS yếu (Rockchip, Chainedbox)
            maxRequestsPerHost = 8
        }
        NasApplication.instance.sharedHttpClient.newBuilder()
            .followRedirects(false) // FIX LỖI P0 CAO CẤP: Không cho phép tự ý chuyển PROPFIND thành GET khi NAS (ngu ngốc) trả về HTTP 301 Redirect.
            .followSslRedirects(false)
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                val credential = okhttp3.Credentials.basic(currentUser, currentPass)
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
                val credential = okhttp3.Credentials.basic(currentUser, currentPass)
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

    fun connect(url: String, user: String, pass: String) {
        val safeUrl = if (url.isNotEmpty() && !url.endsWith("/")) "$url/" else url
        currentBaseUrl = safeUrl
        currentUser = user
        currentPass = pass
    }

    // TÍNH NĂNG 4.H: Đo lường Sức Khoẻ Mạng bằng ICMP PING (Native Ping) cực nhẹ
    suspend fun checkPingServer(): Long? = withContext(Dispatchers.IO) {
        if (currentBaseUrl.isEmpty()) return@withContext null
        try {
            val host = java.net.URL(currentBaseUrl).host
            if (host.isEmpty()) return@withContext null
            
            // Dùng lệnh ping gốc của Android (ICMP) thay vì gửi gói HTTP cấu trúc cồng kềnh
            val start = System.currentTimeMillis()
            val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $host")
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                // Phân tích stdout để lấy số mili-giây chuẩn xác từ lõi Linux, loại trừ độ trễ của máy ảo Java
                // VD: "64 bytes from 192.168.1.5: icmp_seq=1 ttl=64 time=3.45 ms"
                val reader = process.inputStream.bufferedReader()
                var ms = -1L
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.contains("time=")) {
                        try {
                            val timeStr = line!!.substringAfter("time=").substringBefore(" ms")
                            ms = timeStr.toDouble().toLong()
                        } catch (e: Exception) {}
                    }
                }
                reader.close()
                if (ms >= 0) return@withContext ms
                return@withContext System.currentTimeMillis() - start
            } else {
                // Nhỡ ICMP bị firewall chặn, dự phòng bằng HTTP Options (tốn thời gian hơn chút xíu)
                val request = Request.Builder().url(currentBaseUrl).method("OPTIONS", null).build()
                val quickClient = optimizedClient.newBuilder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                quickClient.newCall(request).execute().use { }
                return@withContext System.currentTimeMillis() - start
            }
        } catch (e: Exception) {
            return@withContext -1L // Chết mạng
        }
    }

    // KIẾN TRÚC DOANH NGHIỆP: Truy vấn thông số tệp (Kích thước, ETag) an toàn, ĐÓNG kết nối ngay để tránh sập Connection Pool của OkHttp
    suspend fun headFileHeaders(url: String): okhttp3.Headers? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .head() // Lệnh HTTP HEAD nhanh chóng
                .build()
            optimizedClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext response.headers
                }
                return@withContext null
            }
        } catch (e: Exception) {
            return@withContext null
        }
    }

    // PHASE 18: KHAI TỬ SARDINE NATIVE. 
    // Thư viện Sardine-Android quá cũ (0.8) và khắt khe với WebDAV XML Namespace, khiến NAS trả về XML hợp lệ nhưng Sardine lại ngậm miệng lờ đi, tạo ra Thư mục Trống!
    // Trở lại Trình phân tích XML tuỳ chỉnh siêu cấp (Custom PullParser) gắn vào sardineClient.
    suspend fun listFiles(url: String): List<NasFile> = withContext(Dispatchers.IO) {
        val safeUrl = if (url.endsWith("/")) url else "$url/"
        
        // 1. Dùng sardineClient (đã gắn sẵn Basic Auth)
        val request = okhttp3.Request.Builder()
            .url(safeUrl)
            .method("PROPFIND", ByteArray(0).toRequestBody(null, 0, 0))
            .header("Depth", "1")
            .build()
            
        val xmlString = sardineClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(200) ?: ""
                throw Exception("Mã lỗi NAS: ${response.code} - $errorBody")
            }
            response.body?.string() ?: throw Exception("NAS trả về dữ liệu rỗng")
        }

        val result = mutableListOf<NasFile>()
        
        // 2. Phân tích XML bằng tay - Cực kỳ khoan dung với mọi loại NAS
        try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(xmlString))

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
                                    // Parse HTTP Date Format: Sun, 01 Jan 2023 12:00:00 GMT
                                    try {
                                        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                                        format.timeZone = java.util.TimeZone.getTimeZone("GMT")
                                        currentModTime = format.parse(textBuffer.trim())?.time ?: 0L
                                    } catch (e: Exception) { currentModTime = 0L }
                                }
                                "response" -> {
                                    if (currentHref.isNotEmpty()) {
                                        // Xử lý absolute mapping
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
                                        
                                        // Lọc bỏ gốc rễ
                                        if (fullUri.trimEnd('/') != safeUrl.trimEnd('/')) {
                                            var extractedName = ""
                                            try {
                                                val decoded = java.net.URLDecoder.decode(fullUri.trimEnd('/'), "UTF-8")
                                                extractedName = decoded.substringAfterLast('/')
                                            } catch (e: Exception) {
                                                extractedName = fullUri.trimEnd('/').substringAfterLast('/')
                                            }
                                            
                                            result.add(NasFile(extractedName, fullUri, isDir, currentType, currentLength, currentModTime))
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
        } catch (e: Exception) {
            android.util.Log.e("NAS_XML", "Lỗi phân tích XML thủ công", e)
            throw Exception("NAS trả về cấu trúc XML lạ không thể đọc: ${e.message}")
        }
        
        result
    }

    // Tải lên trực tiếp luồng (Streaming Upload), không nạp file vào RAM
    suspend fun uploadStreamWithProgress(
        fileUrl: String, inputStream: InputStream, totalContentLength: Long,
        contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val requestBody = object : RequestBody() {
            override fun contentType() = contentType.toMediaTypeOrNull()
            override fun contentLength() = -1L
            override fun writeTo(sink: BufferedSink) {
                inputStream.source().use { source ->
                    var totalBytesRead = 0L
                    var readCount = 0L
                    val bufferSize = 8192L
                    // PHASE 4.D: Bandwidth Throttling
                    val speedLimit = AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC
                    val throttleStartTime = System.currentTimeMillis()

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
                                Thread.sleep(delayMs.coerceAtMost(2000))
                            }
                        }
                    }
                }
            }
        }

        val request = Request.Builder().url(fileUrl).put(requestBody).build()
        optimizedClient.newBuilder()
            .writeTimeout(0, TimeUnit.SECONDS) // Vô hiệu hóa timeout cho tệp tin siêu lớn
            .build()
            .newCall(request).execute().use { response ->
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
                bufferedGzip.close()
            }
        }

        val request = Request.Builder()
            .url(fileUrl)
            .put(requestBody)
            .header("Content-Encoding", "gzip") // Kích hoạt vòi xả GZIP phía Server NGINX / NAS
            .build()

        optimizedClient.newBuilder()
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("NAS từ chối tệp nén GZIP: ${response.code}")
            }
    }
    suspend fun resumeUploadStreamWithProgress(
        fileUrl: String, inputStream: InputStream, totalContentLength: Long,
        uploadedBytes: Long, contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        inputStream.skip(uploadedBytes)
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

        val request = Request.Builder()
            .url(fileUrl)
            .header("Content-Range", "bytes $uploadedBytes-${totalContentLength - 1}/$totalContentLength")
            .put(requestBody)
            .build()

        optimizedClient.newBuilder()
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw Exception("NAS từ chối Resume: ${response.code}")
                }
            }
    }

    suspend fun getPartialHashStream(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1048575")
                .build()
            optimizedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) return@withContext null
                val buffer = response.body?.bytes() ?: return@withContext null
                com.nas.naswebdav.utils.HashUtils.md5Bytes(buffer)
            }
        } catch (e: Exception) { null }
    }

    // TÍNH NĂNG 7.M: Đọc lướt nội dung File Text giới hạn dòng (Tránh lag RAM)
    suspend fun readFileText(url: String, maxLines: Int = 100): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
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
        } catch (e: Exception) {
            return@withContext null
        }
    }

    suspend fun createFolder(url: String) = withContext(Dispatchers.IO) {
        val safeUrl = if (url.endsWith("/")) url else "$url/"
        val request = Request.Builder().url(safeUrl).method("MKCOL", null).build()
        // FIX POOL EXHAUSTION: Dùng use{} để đảm bảo body được consume và connection trả về pool
        optimizedClient.newCall(request).execute().use { response ->
            response.body?.close()
        }
    }

    suspend fun deleteFile(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method("DELETE", null).build()
        // FIX POOL EXHAUSTION: Consume body trước khi đóng
        optimizedClient.newCall(request).execute().use { response ->
            response.body?.close()
        }
    }

    suspend fun renameFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(oldUrl).method("MOVE", null).header("Destination", newUrl).build()
        // FIX POOL EXHAUSTION: Consume body trước khi đóng
        optimizedClient.newCall(request).execute().use { response ->
            response.body?.close()
        }
    }

    suspend fun copyFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(oldUrl).method("COPY", null).header("Destination", newUrl).build()
        optimizedClient.newCall(request).execute().use { response ->
            response.body?.close()
        }
    }
    // Hàm hỗ trợ tương thích ngược cho AutoBackupWorker
    suspend fun uploadFile(fileUrl: String, file: java.io.File, contentType: String) = withContext(Dispatchers.IO) {
        java.io.FileInputStream(file).use { inputStream ->
            uploadStreamWithProgress(fileUrl, inputStream, file.length(), contentType) { _, _ -> }
        }
    }

    // Hàm hỗ trợ tương thích ngược cho WebDavViewModel
    fun initConnection() {
        // Không cần làm gì: Kiến trúc OkHttp mới dùng Lazy Loading và tự quản lý Connection Pool an toàn
    }

    suspend fun downloadFile(url: String, destFile: java.io.File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Lỗi download: ${response.code}")
            val body = response.body ?: throw Exception("Empty body")
            java.io.FileOutputStream(destFile).use { fos ->
                body.byteStream().copyTo(fos)
            }
        }
    }
    suspend fun createEmptyFile(url: String) = withContext(Dispatchers.IO) {
        val requestBody = ByteArray(0).toRequestBody(null, 0, 0)
        val request = Request.Builder().url(url).put(requestBody).build()
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
    suspend fun <T> cached(key: String, loader: suspend () -> T): T {
        val entry = cache[key]
        if (entry != null && System.currentTimeMillis() - entry.first < CACHE_DURATION_MS) {
            @Suppress("UNCHECKED_CAST") return entry.second as T
        }
        val value = loader()
        cache[key] = Pair(System.currentTimeMillis(), value as Any)
        return value
    }
}

// ════════════════════════════════════════════════════════════════════════════
// LocalVideoProxy (từ LocalVideoProxy.kt)
// ════════════════════════════════════════════════════════════════════════════

/**
 * HTTP Proxy cục bộ trên điện thoại — giúp VLC/MX Player phát video từ NAS có xác thực.
 *
 * Luồng hoạt động:
 * 1. VLC gửi GET request → Proxy chuyển tiếp sang NAS (có kèm Authorization)
 * 2. VLC gửi Range request (tua) → Proxy chuyển tiếp Range tới NAS → trả 206 + Content-Range
 * 3. Proxy giữ ServerSocket sống → VLC có thể tạo nhiều kết nối cho seek
 */
class LocalVideoProxy(private val user: String, private val pass: String) {

    private var serverSocket: java.net.ServerSocket? = null
    private var proxyThread: Thread? = null
    private var targetUrl: String = ""
    // FIX MEMORY LEAK: Dùng thread pool thay vì raw Thread — tự động thu hồi khi stop()
    private var clientExecutor: java.util.concurrent.ExecutorService? = null
    // THEO DÕI SOCKET VÀ GIẢI PHÓNG MA (GHOST CONNECTIONS)
    private val activeSockets = java.util.concurrent.ConcurrentHashMap<String, java.net.Socket>()

    companion object {
        private const val TAG = "LocalVideoProxy"
        private var currentInstance: LocalVideoProxy? = null

        fun stopCurrent() {
            currentInstance?.stop()
            currentInstance = null
        }
    }

    fun start(nasUrl: String): String {
        stopCurrent()
        targetUrl = nasUrl

        serverSocket = java.net.ServerSocket(0).also { it.soTimeout = 60_000 } // SỬA LỖI 13.02: Timeout 60s an toàn 
        val port = serverSocket!!.localPort
        android.util.Log.i(TAG, "Proxy started on port $port → $nasUrl")

        // SỬA LỖI 13.02: Giới hạn 3 luồng và gán ThreadFactory chống Memory Leak
        clientExecutor = java.util.concurrent.Executors.newFixedThreadPool(3, java.util.concurrent.ThreadFactory { r ->
            Thread(r).apply { 
                isDaemon = true
                name = "VideoProxy-Worker-$id"
            }
        })

        proxyThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val client = serverSocket!!.accept()
                    clientExecutor?.submit { handleClient(client) }
                } catch (e: java.net.SocketTimeoutException) {
                    // Chờ Accept quá 60s -> Loop tiếp an toàn
                } catch (e: Exception) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "VideoProxy-Server"
            start()
        }
        currentInstance = this

        val ext = nasUrl.substringAfterLast('.', "mp4").substringBefore('?')
        return "http://127.0.0.1:$port/video.$ext"
    }

    private fun handleClient(client: java.net.Socket) {
        val socketId = java.util.UUID.randomUUID().toString()
        activeSockets[socketId] = client
        try {
            client.soTimeout = 60_000

            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            android.util.Log.d(TAG, "Request: $requestLine")

            // Đọc tất cả headers từ VLC
            var rangeHeader: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line!!.substringAfter(":").trim()
                }
            }

            // Tạo request tới NAS — CHỈ thêm Range nếu VLC yêu cầu
            val nasRequestBuilder = okhttp3.Request.Builder()
                .url(targetUrl)
                .header("Authorization", okhttp3.Credentials.basic(user, pass))

            if (rangeHeader != null) {
                nasRequestBuilder.header("Range", rangeHeader)
                android.util.Log.d(TAG, "Forwarding Range: $rangeHeader")
            }

            // FIX MEMORY LEAK: Dùng chung videoStreamingClient, KHÔNG tạo client mới cho mỗi request
            val nasClient = NasApplication.instance.videoStreamingClient

            // FIX MEMORY LEAK: Dùng use {} để đảm bảo response body luôn được đóng
            nasClient.newCall(nasRequestBuilder.build()).execute().use { nasResponse ->
                val output = client.getOutputStream()

                // Chuyển tiếp ĐÚNG status code từ NAS
                val statusCode = nasResponse.code
                val statusText = when (statusCode) {
                    200 -> "OK"
                    206 -> "Partial Content"
                    else -> "OK"
                }
                output.writeHeader("HTTP/1.1 $statusCode $statusText")

                // Chuyển tiếp các header quan trọng từ NAS → VLC
                nasResponse.header("Content-Type")?.let { output.writeHeader("Content-Type: $it") }
                    ?: output.writeHeader("Content-Type: video/mpeg")
                nasResponse.header("Content-Length")?.let { output.writeHeader("Content-Length: $it") }
                nasResponse.header("Content-Range")?.let {
                    output.writeHeader("Content-Range: $it")
                    android.util.Log.d(TAG, "Content-Range: $it")
                }
                nasResponse.header("Accept-Ranges")?.let { output.writeHeader("Accept-Ranges: $it") }
                    ?: output.writeHeader("Accept-Ranges: bytes")

                output.writeHeader("Connection: close")
                output.write("\r\n".toByteArray())
                output.flush()

                // Stream dữ liệu video từ NAS → VLC
                nasResponse.body?.use { body ->
                    body.byteStream().use { bodyStream ->
                        val buffer = ByteArray(131072) // 128KB chunks cho tốc độ cao
                        var bytesRead: Int
                        while (bodyStream.read(buffer).also { bytesRead = it } != -1) {
                            try {
                                output.write(buffer, 0, bytesRead)
                            } catch (e: java.io.IOException) {
                                break // VLC đóng kết nối (seek/thoát) — bình thường
                            }
                        }
                        try { output.flush() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.d(TAG, "Session ended: ${e.message}")
        } finally {
            activeSockets.remove(socketId)
            // FIX MEMORY LEAK: Luôn đóng client socket trong finally
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun java.io.OutputStream.writeHeader(header: String) {
        write("$header\r\n".toByteArray())
    }

    fun stop() {
        // FIX BUG #5: Đóng resource theo đúng thứ tự — tránh ghost thread + memory leak
        try {
            // 1. Dừng accept connection
            proxyThread?.interrupt()
            proxyThread?.join(2000) // Chờ thread kết thúc

            // 2. Shutdown executor (đợi task hiện tại xong)
            clientExecutor?.shutdown()
            if (clientExecutor?.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) != true) {
                clientExecutor?.shutdownNow()
            }

            // 3. Ép đóng tất cả Socket sống dai (tàn dư của VLC Seek)
            activeSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
            activeSockets.clear()

            // 4. Đóng ServerSocket (cuối cùng)
            serverSocket?.close()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Error during stop", e)
        }

        serverSocket = null
        proxyThread = null
        clientExecutor = null
    }
}
