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



// Äá»™ng cÆ¡ WebDAV hiá»‡u suáº¥t cao - KhÃ´ng sá»­ dá»¥ng Sardine

object WebDavManager {

    internal data class AuthState(
        val baseUrl: String = "",
        val user: String = "",
        val pass: String = ""
    ) {
        val authHeader: String
            get() = okhttp3.Credentials.basic(user, pass)
    }

    @Volatile
    private var authState = AuthState()

    val currentBaseUrl: String
        get() = authState.baseUrl

    val currentUser: String
        get() = authState.user

    val currentPass: String
        get() = authState.pass

    fun currentAuthHeader(): String = authState.authHeader

    private fun Request.Builder.withAuth(auth: AuthState): Request.Builder {
        return tag(AuthState::class.java, auth)
    }

    fun tagCurrentAuth(builder: Request.Builder): Request.Builder {
        return builder.tag(AuthState::class.java, authState)
    }

    fun Request.Builder.withCurrentAuth(): Request.Builder {
        return tag(AuthState::class.java, authState)
    }

    // Káº¿ thá»«a káº¿t ná»‘i (Connection Pooling) & Keep-Alive

    private val optimizedClient: OkHttpClient by lazy {

        val dispatcher = Dispatcher().apply {

            maxRequests = 16 // FIX BUG #7: Giáº£m tá»« 64 xuá»‘ng 16 â€” trÃ¡nh quÃ¡ táº£i NAS yáº¿u (Rockchip, Chainedbox)

            maxRequestsPerHost = 8

        }

        NasApplication.instance.sharedHttpClient.newBuilder()

            .followRedirects(false) // FIX Lá»–I P0 CAO Cáº¤P: KhÃ´ng cho phÃ©p tá»± Ã½ chuyá»ƒn PROPFIND thÃ nh GET khi NAS (ngu ngá»‘c) tráº£ vá» HTTP 301 Redirect.

            .followSslRedirects(false)

            .dispatcher(dispatcher)

            .addInterceptor { chain ->

                val auth = chain.request().tag(AuthState::class.java) ?: authState
                val credential = auth.authHeader

                val request = chain.request().newBuilder()
                    .header("Authorization", credential) // BÆ¡m tháº³ng Preemptive Auth, cá»±c hiá»‡u quáº£ chá»‘ng NAS Server treo khi thiáº¿u Auth

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



    // MÃY CHá»¦ SARDINE Äá»˜C Láº¬P (PHÃM 15): KhÃ´ng chia sáº» Client vá»›i há»‡ thá»‘ng Upload/Download

    // Nháº±m giáº£i phÃ³ng Sardine khá»i Cáº¥m Redirect vÃ  ChÃ¨n Header BasicAuth sai lá»‡ch

    private val sardineClient: OkHttpClient by lazy {

        NasApplication.instance.sharedHttpClient.newBuilder()

            .followRedirects(true) // Sardine Ráº¤T Cáº¦N TÃNH NÄ‚NG NÃ€Y (Äá»ƒ báº¯t 301 chuyá»ƒn hÆ°á»›ng thÆ° má»¥c WebDAV)

            .followSslRedirects(true)

            .retryOnConnectionFailure(true)

            .addInterceptor { chain ->

                // PHASE 17 Tá»I QUAN TRá»ŒNG: Preemptive Authentication (XÃ¡c thá»±c vÆ°á»£t cáº¥p)

                // NAS KHÃ”NG tráº£ vá» 401 Ä‘á»ƒ kÃ­ch hoáº¡t Sardine Authenticator, mÃ  nÃ³ tráº£ vá» thÆ° má»¥c TRá»NG náº¿u khÃ´ng cÃ³ máº­t kháº©u ngay tá»« Ä‘áº§u!

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

                // Khi khÃ´ng báº­t Cert Pinning â†’ sá»­ dá»¥ng há»‡ thá»‘ng Trust Manager máº·c Ä‘á»‹nh (Android CA Store)

                // KHÃ”NG bypass SSL verification Ä‘á»ƒ trÃ¡nh táº¥n cÃ´ng Man-in-the-Middle

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



    // TÃNH NÄ‚NG 4.H: Äo lÆ°á»ng Sá»©c Khoáº» Máº¡ng báº±ng HTTP OPTIONS cá»±c nháº¹

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

        } catch (e: Exception) {

            return@withContext -1L // Cháº¿t máº¡ng hoáº·c bá»‹ cháº·n

        }

    }



    // KIáº¾N TRÃšC DOANH NGHIá»†P: Truy váº¥n thÃ´ng sá»‘ tá»‡p (KÃ­ch thÆ°á»›c, ETag) an toÃ n, ÄÃ“NG káº¿t ná»‘i ngay Ä‘á»ƒ trÃ¡nh sáº­p Connection Pool cá»§a OkHttp

    suspend fun headFileHeaders(url: String): okhttp3.Headers? = withContext(Dispatchers.IO) {

        try {

            val request = Request.Builder().withAuth(authState)

                .url(url)

                .head() // Lá»‡nh HTTP HEAD nhanh chÃ³ng

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



    // PHASE 18: KHAI Tá»¬ SARDINE NATIVE. 

    // ThÆ° viá»‡n Sardine-Android quÃ¡ cÅ© (0.8) vÃ  kháº¯t khe vá»›i WebDAV XML Namespace, khiáº¿n NAS tráº£ vá» XML há»£p lá»‡ nhÆ°ng Sardine láº¡i ngáº­m miá»‡ng lá» Ä‘i, táº¡o ra ThÆ° má»¥c Trá»‘ng!

    // Trá»Ÿ láº¡i TrÃ¬nh phÃ¢n tÃ­ch XML tuá»³ chá»‰nh siÃªu cáº¥p (Custom PullParser) gáº¯n vÃ o sardineClient.

    suspend fun listFiles(url: String): List<NasFile> = withContext(Dispatchers.IO) {

        val safeUrl = if (url.endsWith("/")) url else "$url/"

        

        // 1. DÃ¹ng sardineClient (Ä‘Ã£ gáº¯n sáºµn Basic Auth)

        val request = okhttp3.Request.Builder().withAuth(authState)

            .url(safeUrl)

            .method("PROPFIND", ByteArray(0).toRequestBody(null, 0, 0))

            .header("Depth", "1")

            .build()

            

        val xmlString = sardineClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {

                val errorBody = response.body?.string()?.take(200) ?: ""

                throw Exception("MÃ£ lá»—i NAS: ${response.code} - $errorBody")

            }

            response.body?.string() ?: throw Exception("NAS tráº£ vá» dá»¯ liá»‡u rá»—ng")

        }



        val result = mutableListOf<NasFile>()

        

        // 2. PhÃ¢n tÃ­ch XML báº±ng tay - Cá»±c ká»³ khoan dung vá»›i má»i loáº¡i NAS

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

                                        // Xá»­ lÃ½ absolute mapping

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

                                        

                                        // Lá»c bá» gá»‘c rá»…

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

            android.util.Log.e("NAS_XML", "Lá»—i phÃ¢n tÃ­ch XML thá»§ cÃ´ng", e)

            throw Exception("NAS tráº£ vá» cáº¥u trÃºc XML láº¡ khÃ´ng thá»ƒ Ä‘á»c: ${e.message}")

        }

        

        result

    }



    // Táº£i lÃªn trá»±c tiáº¿p luá»“ng (Streaming Upload), khÃ´ng náº¡p file vÃ o RAM

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

                    var readCount = 0L

                    val bufferSize = 8192L

                    // PHASE 4.D: Bandwidth Throttling

                    val speedLimit = AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC

                    val throttleStartTime = System.currentTimeMillis()



                    while (source.read(sink.buffer, bufferSize).also { readCount = it } != -1L) {

                        sink.emit()

                        totalBytesRead += readCount

                        onProgress(totalBytesRead, totalContentLength)



                        // Throttle: náº¿u Ä‘ang vÆ°á»£t tá»‘c, chá» cho ká»‹p

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



        val request = Request.Builder().withAuth(authState).url(fileUrl).put(requestBody).build()

        optimizedClient.newBuilder()

            .writeTimeout(0, TimeUnit.SECONDS) // VÃ´ hiá»‡u hÃ³a timeout cho tá»‡p tin siÃªu lá»›n

            .build()

            .newCall(request).execute().use { response ->

                if (!response.isSuccessful) throw Exception("NAS tá»« chá»‘i tá»‡p: ${response.code}")

            }

    }



    // TÃNH NÄ‚NG Má»šI 3.A: GZIP Streaming Upload (NÃ©n luá»“ng thá»i gian thá»±c)

    // Tá»± Ä‘á»™ng bÃ³p mÃ©o luá»“ng dá»¯ liá»‡u thÃ nh Ä‘á»‹nh dáº¡ng GZIP thu nhá» Ä‘áº¿n 80% dung lÆ°á»£ng máº¡ng trÆ°á»›c khi lÃªn sÃ³ng.

    suspend fun uploadCompressedStream(

        fileUrl: String, inputStream: InputStream, totalUncompressedLength: Long,

        contentType: String, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit

    ) = withContext(Dispatchers.IO) {

        val requestBody = object : RequestBody() {

            override fun contentType() = contentType.toMediaTypeOrNull()

            // Chunked transfer encoding vÃ¬ Ä‘á»™ dÃ i GZIP bá»‹ thay Ä‘á»•i liÃªn tá»¥c, khÃ´ng thá»ƒ biáº¿t trÆ°á»›c khá»‘i lÆ°á»£ng cuá»‘i cÃ¹ng

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

                        // Cáº­p nháº­t tháº» Progress theo má»‘c dung lÆ°á»£ng gá»‘c (Uncompressed)

                        onProgress(totalBytesRead, totalUncompressedLength)

                    }

                    bufferedGzip.flush()

                }

                bufferedGzip.close()

            }

        }



        val request = Request.Builder().withAuth(authState)

            .url(fileUrl)

            .put(requestBody)

            .header("Content-Encoding", "gzip") // KÃ­ch hoáº¡t vÃ²i xáº£ GZIP phÃ­a Server NGINX / NAS

            .build()



        optimizedClient.newBuilder()

            .writeTimeout(0, TimeUnit.SECONDS)

            .build()

            .newCall(request).execute().use { response ->

                if (!response.isSuccessful) throw Exception("NAS tá»« chá»‘i tá»‡p nÃ©n GZIP: ${response.code}")

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



        optimizedClient.newBuilder()

            .writeTimeout(0, TimeUnit.SECONDS)

            .build()

            .newCall(request).execute().use { response ->

                if (!response.isSuccessful && response.code != 206) {

                    throw Exception("NAS tá»« chá»‘i Resume: ${response.code}")

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

                val buffer = response.body?.bytes() ?: return@withContext null

                com.nas.naswebdav.utils.HashUtils.md5Bytes(buffer)

            }

        } catch (e: Exception) { null }

    }



    // TÃNH NÄ‚NG 7.M: Äá»c lÆ°á»›t ná»™i dung File Text giá»›i háº¡n dÃ²ng (TrÃ¡nh lag RAM)

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

                    if (!source.exhausted()) sb.append("\n... (ÄÃ£ Cáº¯t Bá»›t Ná»™i Dung VÃ¬ QuÃ¡ DÃ i)")

                    return@withContext sb.toString()

                }

            }

        } catch (e: Exception) {

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

    suspend fun deleteFile(url: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(url).method("DELETE", null).build()

        // FIX POOL EXHAUSTION: Consume body trÆ°á»›c khi Ä‘Ã³ng

        optimizedClient.newCall(request).execute().use { response ->

            response.body?.close()

        }

    }



    suspend fun renameFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(oldUrl).method("MOVE", null).header("Destination", newUrl).build()

        // FIX POOL EXHAUSTION: Consume body trÆ°á»›c khi Ä‘Ã³ng

        optimizedClient.newCall(request).execute().use { response ->

            response.body?.close()

        }

    }



    suspend fun copyFile(oldUrl: String, newUrl: String) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(oldUrl).method("COPY", null).header("Destination", newUrl).build()

        optimizedClient.newCall(request).execute().use { response ->

            response.body?.close()

        }

    }

    // HÃ m há»— trá»£ tÆ°Æ¡ng thÃ­ch ngÆ°á»£c cho AutoBackupWorker

    suspend fun uploadFile(fileUrl: String, file: java.io.File, contentType: String) = withContext(Dispatchers.IO) {

        java.io.FileInputStream(file).use { inputStream ->

            uploadStreamWithProgress(fileUrl, inputStream, file.length(), contentType) { _, _ -> }

        }

    }



    // HÃ m há»— trá»£ tÆ°Æ¡ng thÃ­ch ngÆ°á»£c cho WebDavViewModel

    fun initConnection() {

        // KhÃ´ng cáº§n lÃ m gÃ¬: Kiáº¿n trÃºc OkHttp má»›i dÃ¹ng Lazy Loading vÃ  tá»± quáº£n lÃ½ Connection Pool an toÃ n

    }



    suspend fun downloadFile(url: String, destFile: java.io.File) = withContext(Dispatchers.IO) {

        val request = Request.Builder().withAuth(authState).url(url).build()

        optimizedClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) throw Exception("Lá»—i táº£i xuá»‘ng: ${response.code}")

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

            if (!response.isSuccessful) throw Exception("Lá»—i táº¡o file: ${response.code}")

        }

    }

}



// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

// WebDavRepository â€” Truy váº¥n dá»¯ liá»‡u qua WebDAV + Room Cache

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•



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
