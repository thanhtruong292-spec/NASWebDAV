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
import okio.BufferedSink
import okio.source

data class NasFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val contentType: String?,
    val contentLength: Long,
    val lastModified: Long = 0L
)

// Động cơ WebDAV hiệu suất cao - Không sử dụng Sardine
class WebDavManager {
    var currentBaseUrl: String = ""
    var currentUser: String = ""
    var currentPass: String = ""

    // Kế thừa kết nối (Connection Pooling) & Keep-Alive
    private val optimizedClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 8
        }
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Connection", "Keep-Alive")
                    .build()
                chain.proceed(request)
            }
            .authenticator { _, response ->
                val credential = Credentials.basic(currentUser, currentPass)
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
    }

    fun connect(url: String, user: String, pass: String) {
        currentBaseUrl = url
        currentUser = user
        currentPass = pass
    }

    // PROPFIND tốc độ cao bằng XmlPullParser (Streaming Parsing)
    suspend fun listFiles(url: String): List<NasFile> = withContext(Dispatchers.IO) {
        val requestBody = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:displayname/>
                    <D:resourcetype/>
                    <D:getcontenttype/>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                </D:prop>
            </D:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", requestBody)
            .header("Depth", "1")
            .build()

        val result = mutableListOf<NasFile>()

        optimizedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Lỗi truy xuất thư mục: ${response.code}")

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(response.body?.charStream())

            var eventType = parser.eventType
            var currentName = ""
            var currentHref = ""
            var isDir = false
            var currentType = ""
            var currentLength = 0L
            var currentModTime = 0L

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val nodeName = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (nodeName.lowercase()) {
                            "d:href", "href" -> currentHref = parser.nextText()
                            "d:displayname", "displayname" -> currentName = parser.nextText()
                            "d:getcontentlength", "getcontentlength" -> currentLength = parser.nextText().toLongOrNull() ?: 0L
                            "d:getcontenttype", "getcontenttype" -> currentType = parser.nextText()
                            "d:collection", "collection" -> isDir = true
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (nodeName.lowercase() == "d:response" || nodeName.lowercase() == "response") {
                            val fullPath = if (currentHref.startsWith("http")) currentHref else URL(URL(url), currentHref).toString()
                            if (fullPath.trimEnd('/') != url.trimEnd('/')) {
                                result.add(NasFile(currentName, fullPath, isDir, currentType, currentLength, currentModTime))
                            }
                            // Reset state
                            currentName = ""; currentHref = ""; isDir = false; currentType = ""; currentLength = 0L
                        }
                    }
                }
                eventType = parser.next()
            }
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
            override fun contentLength() = totalContentLength
            override fun writeTo(sink: BufferedSink) {
                inputStream.source().use { source ->
                    var totalBytesRead = 0L
                    var readCount: Long
                    val bufferSize = 8192L
                    while (source.read(sink.buffer, bufferSize).also { readCount = it } != -1L) {
                        sink.emit()
                        totalBytesRead += readCount
                        onProgress(totalBytesRead, totalContentLength)
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
                    var readCount: Long
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
                val md = java.security.MessageDigest.getInstance("MD5")
                val digest = md.digest(buffer)
                digest.joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) { null }
    }
    suspend fun createFolder(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method("MKCOL", null).build()
        optimizedClient.newCall(request).execute().close()
    }

    suspend fun deleteFile(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method("DELETE", null).build()
        optimizedClient.newCall(request).execute().close()
    }

    suspend fun renameFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(oldUrl).method("MOVE", null).header("Destination", newUrl).build()
        optimizedClient.newCall(request).execute().close()
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
}
