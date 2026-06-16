package com.nas.naswebdav

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * LocalVideoProxy - HTTP proxy cục bộ chạy trên localhost
 *
 * Kiến trúc:
 *   VLC / MX Player -> http://127.0.0.1:<port>/<encoded_url>
 *   -> LocalVideoProxy -> NAS WebDAV with Authorization header
 *
 * Vòng đời:
 * - [start] tạo ServerSocket trên port ngẫu nhiên, spawn thread nhận request, trả về localUrl
 * - ServerSocket tự đóng sau khi phục vụ 1 request hoặc sau timeout 10 phút
 * - [stop] đóng socket + dọn pool handler để tránh leak thread khi start() lặp lại
 *
 * @param user  WebDAV username (dùng để tạo Basic Auth header)
 * @param pass  WebDAV password
 */
class LocalVideoProxy(private val user: String, private val pass: String) {

    companion object {
        private const val TAG = "LocalVideoProxy"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val SERVER_IDLE_TIMEOUT_MS = 10 * 60_000L
        private const val BUFFER_SIZE = 65_536
        private const val MAX_HANDLER_THREADS = 4
        private const val MAX_HANDLER_QUEUE = 8
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var requestExecutor: ThreadPoolExecutor? = null
    @Volatile private var lastActivityAtMs: Long = 0L

    private fun createRequestExecutor(): ThreadPoolExecutor {
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "LocalVideoProxy-handler").apply { isDaemon = true }
        }
        return ThreadPoolExecutor(
            2,
            MAX_HANDLER_THREADS,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_HANDLER_QUEUE),
            factory,
            ThreadPoolExecutor.AbortPolicy()
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    /**
     * Khởi động proxy và trả về URL localhost để VLC kết nối.
     *
     * @param nasUrl URL gốc của file trên NAS (có dạng http://192.168.x.x:5005/webdav/...)
     * @return URL localhost dạng http://127.0.0.1:<port>/ mà VLC sẽ mở
     */
    fun start(nasUrl: String): String {
        stop()

        val server = ServerSocket(0).also { serverSocket = it }
        val port = server.localPort
        lastActivityAtMs = System.currentTimeMillis()
        requestExecutor = createRequestExecutor()

        Log.d(TAG, "Proxy started on port $port ? $nasUrl")

        Thread(null, {
            try {
                server.soTimeout = 15_000

                while (!server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        lastActivityAtMs = System.currentTimeMillis()

                        val executor = requestExecutor
                        if (executor == null || executor.isShutdown) {
                            runCatching { clientSocket.close() }
                            continue
                        }

                        try {
                            executor.execute {
                                handleRequest(clientSocket, nasUrl)
                            }
                        } catch (_: RejectedExecutionException) {
                            Log.w(TAG, "Proxy overload, reject client")
                            runCatching { clientSocket.close() }
                        }
                    } catch (_: SocketTimeoutException) {
                        val idleMs = System.currentTimeMillis() - lastActivityAtMs
                        if (idleMs >= SERVER_IDLE_TIMEOUT_MS) {
                            Log.d(TAG, "Proxy idle too long, shutting down")
                            break
                        }
                    } catch (e: Exception) {
                        if (!server.isClosed) {
                            Log.w(TAG, "Lỗi nhận kết nối: ${e.message}")
                            continue
                        }
                        break
                    }
                }
            } finally {
                runCatching { server.close() }
                runCatching { requestExecutor?.shutdownNow() }
                serverSocket = null
                requestExecutor = null
                Log.d(TAG, "Proxy server closed")
            }
        }, "LocalVideoProxy-server").apply {
            isDaemon = true
            start()
        }

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

                val requestLine = input.readLine() ?: return
                Log.d(TAG, "Proxy got: $requestLine")

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

                nasConnection = URL(nasUrl).openConnection() as java.net.HttpURLConnection
                nasConnection!!.apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Authorization", okhttp3.Credentials.basic(user, pass))
                    clientHeaders["range"]?.let { setRequestProperty("Range", it) }
                    setRequestProperty("User-Agent", "NASWebDAV-Proxy/1.0")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "keep-alive")
                }

                val nasResponseCode = try {
                    nasConnection!!.responseCode
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot connect to NAS: ${e.message}")
                    sendErrorResponse(output, 502, "Lỗi proxy video: ${e.message}")
                    return
                }

                val responseHeaders = StringBuilder()
                responseHeaders.append("HTTP/1.1 $nasResponseCode ${nasConnection!!.responseMessage}\r\n")

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
                    } catch (e: Exception) {
                        Log.d(TAG, "Stream end (client disconnect): ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
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

    /** Dừng proxy thủ công (tùy chọn - proxy tự động đóng sau timeout) */
    fun stop() {
        runCatching { serverSocket?.close() }
        runCatching { requestExecutor?.shutdownNow() }
        serverSocket = null
        requestExecutor = null
    }
}
