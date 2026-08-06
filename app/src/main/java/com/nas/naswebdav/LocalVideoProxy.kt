package com.nas.naswebdav

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * LocalVideoProxy — HTTP proxy cục bộ chạy trên localhost
 *
 * Kiến trúc:
 *   VLC / MX Player → http://127.0.0.1:<port>/<encoded_url>
 *   → LocalVideoProxy → NAS WebDAV with Authorization header
 *
 * Tại sao cần proxy thay vì nhúng auth vào URL?
 * - VLC/MX Player không hỗ trợ URL có dạng http://user:pass@host/... một cách đáng tin cậy
 * - Nhúng password vào URL có thể bị lộ qua log của player
 * - Proxy cho phép truyền header Authorization chuẩn HTTP, hỗ trợ mọi player
 *
 * Vòng đời:
 * - [start] tạo ServerSocket trên port ngẫu nhiên, spawn thread nhận request, trả về localUrl
 * - ServerSocket tự đóng sau khi phục vụ 1 request hoặc sau timeout 30 giây
 * - Không cần [stop] thủ công — nếu app bị kill, socket sẽ tự đóng theo process
 *
 * @param user  WebDAV username (dùng để tạo Basic Auth header)
 * @param pass  WebDAV password
 */
class LocalVideoProxy(private val user: String, private val pass: String) {

    companion object {
        private const val TAG = "LocalVideoProxy"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val SERVER_IDLE_TIMEOUT_MS = 10 * 60_000L // Giữ proxy sống theo phiên phát để seek lại sau pause dài
        private const val BUFFER_SIZE = 65_536 // 64KB buffer cho stream
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var lastActivityAtMs: Long = 0L

    /**
     * Khởi động proxy và trả về URL localhost để VLC kết nối.
     *
     * @param nasUrl URL gốc của file trên NAS (có dạng http://192.168.x.x:5005/webdav/...)
     * @return URL localhost dạng http://127.0.0.1:<port>/ mà VLC sẽ mở
     */
    fun start(nasUrl: String): String {
        // FIX: đóng proxy cũ (nếu có) trước khi tạo mới để tránh leak
        // thread/socket khi caller goi start() nhieu lan tren cung instance.
        stop()
        // Mở ServerSocket chỉ trên loopback (127.0.0.1) — tránh LAN peer dùng proxy
        // để forward NAS Basic Auth và đọc dữ liệu trái phép.
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also { serverSocket = it }
        val port = server.localPort
        lastActivityAtMs = System.currentTimeMillis()

        // Redact any userinfo before logging the URL.
        val safeUrl = nasUrl.replace(Regex("(?i)(://)([^/]+)@"), "$1***@")
        Log.d(TAG, "Proxy started on port $port -> $safeUrl")

        // Thread daemon: tự kill khi app process chết
        val proxyThread = Thread(null, {
            try {
                // Poll bằng timeout ngắn để chỉ đóng khi thật sự idle quá lâu.
                server.soTimeout = 15_000

                while (!server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        lastActivityAtMs = System.currentTimeMillis()
                        // Spawn thread riêng để xử lý từng request (VLC có thể gọi HEAD + GET)
                        Thread(null, {
                            handleRequest(clientSocket, nasUrl)
                        }, "LocalVideoProxy-handler").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        val idleMs = System.currentTimeMillis() - lastActivityAtMs
                        if (idleMs >= SERVER_IDLE_TIMEOUT_MS) {
                            Log.d(TAG, "Proxy idle too long, shutting down")
                            break
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        if (!server.isClosed) {
                            Log.w(TAG, "Lỗi nhận kết nối: ${e.message}")
                            continue
                        }
                        break
                    }
                }
            } finally {
                runCatching { server.close() }
                Log.d(TAG, "Proxy server closed")
            }
        }, "LocalVideoProxy-server").apply {
            isDaemon = true
            start()
        }

        // Trả về URL để VLC mở — player sẽ kết nối tới proxy trên localhost
        return "http://127.0.0.1:$port/"
    }

    /**
     * Xử lý 1 request từ VLC:
     * 1. Đọc request line + headers từ client
     * 2. Mở kết nối tới NAS với Authorization header
     * 3. Stream response body từ NAS xuống client (hỗ trợ Range requests để seek video)
     */
    private fun handleRequest(clientSocket: Socket, nasUrl: String) {
        var nasConnection: java.net.HttpURLConnection? = null
        try {
            clientSocket.use { client ->
                lastActivityAtMs = System.currentTimeMillis()
                val input = client.getInputStream().bufferedReader()
                val output = client.getOutputStream()

                // Đọc request line (ví dụ: "GET / HTTP/1.1" hoặc "HEAD / HTTP/1.1")
                val requestLine = input.readLine() ?: return
                Log.d(TAG, "Proxy got: $requestLine")

                // Đọc tất cả headers từ VLC (cần lấy Range header để hỗ trợ seek)
                val clientHeaders = mutableMapOf<String, String>()
                var line = input.readLine()
                while (!line.isNullOrBlank()) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val key = line.substring(0, colonIdx).trim()
                        val value = line.substring(colonIdx + 1).trim()
                        clientHeaders[key.lowercase()] = value
                    }
                    line = input.readLine()
                }

                val method = requestLine.split(" ").firstOrNull() ?: "GET"

                // Kết nối tới NAS
                nasConnection = URL(nasUrl).openConnection() as java.net.HttpURLConnection
                nasConnection!!.apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true

                    // Xác thực với NAS
                    setRequestProperty(
                        "Authorization",
                        WebDavManager.AuthState(user = user, pass = pass).authHeader
                    )

                    // Chuyển tiếp Range header từ VLC để hỗ trợ seek
                    clientHeaders["range"]?.let { setRequestProperty("Range", it) }

                    // Headers chuẩn
                    setRequestProperty("User-Agent", "NASWebDAV-Proxy/1.0")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "keep-alive")
                }

                val nasResponseCode = try {
                    nasConnection!!.responseCode
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    Log.e(TAG, "Cannot connect to NAS: ${e.message}")
                    sendErrorResponse(output, 502, "Lỗi proxy video: ${e.message}")
                    return
                }

                // Build HTTP response header cho VLC
                val responseHeaders = StringBuilder()
                responseHeaders.append("HTTP/1.1 $nasResponseCode ${nasConnection!!.responseMessage}\r\n")

                // Chuyển tiếp các headers quan trọng từ NAS sang VLC
                val importantHeaders = listOf(
                    "content-type", "content-length", "content-range",
                    "accept-ranges", "last-modified", "etag", "cache-control"
                )

                for (header in importantHeaders) {
                    nasConnection!!.getHeaderField(header)?.let { value ->
                        responseHeaders.append("${header.replaceFirstChar { it.uppercase() }}: $value\r\n")
                    }
                }
                responseHeaders.append("Connection: close\r\n")
                responseHeaders.append("\r\n")

                output.write(responseHeaders.toString().toByteArray(Charsets.US_ASCII))
                output.flush()

                // Stream body (chỉ với GET, không với HEAD)
                if (method != "HEAD" && nasResponseCode in 200..299) {
                    try {
                        nasConnection!!.inputStream.use { nasBody ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (nasBody.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                lastActivityAtMs = System.currentTimeMillis()
                            }
                            output.flush()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        // Client ngắt kết nối khi seek — đây là bình thường với VLC
                        Log.d(TAG, "Stream end (client disconnect): ${e.message}")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Lỗi xử lý yêu cầu: ${e.message}")
        } finally {
            runCatching { nasConnection?.disconnect() }
        }
    }
    private fun sendErrorResponse(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 $code $message\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }

    /** Dừng proxy thủ công (tùy chọn — proxy tự đóng sau timeout) */
    fun stop() {
        runCatching { serverSocket?.close() }
    }
}
