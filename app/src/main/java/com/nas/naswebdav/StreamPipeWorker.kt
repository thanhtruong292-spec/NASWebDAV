package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File

/**
 * StreamPipeWorker — Foreground Worker bơm video từ CDN → NAS WebDAV.
 *
 * Luồng hoạt động:
 * 1. HEAD CDN → lấy kích thước video
 * 2. GET CDN (stream) → đọc từng chunk 128KB
 * 3. PUT NAS WebDAV → ghi từng chunk lên NAS
 * Tổng RAM sử dụng: ~256KB bất kể video lớn bao nhiêu.
 *
 * Input Data:
 *   - "sourceUrl"  : Link video CDN trực tiếp
 *   - "fileName"   : Tên file lưu trên NAS
 *   - "baseUrl"    : WebDAV base URL hiện tại
 *   - "user"       : WebDAV username
 *   - "pass"       : WebDAV password
 */
class StreamPipeWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "stream_pipe_channel"
        const val NOTIFICATION_ID = 9012
        private const val TAG = "StreamPipe"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Truyền video về NAS",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Truyền video từ Internet về NAS"
                    setShowBadge(false)
                }
                (context.getSystemService(NotificationManager::class.java))
                    ?.createNotificationChannel(channel)
            }
        }
    }

    private fun safeDataText(value: String, maxChars: Int = 512): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "..."
    }

    private fun loadPipePayload(): Pair<String, String> {
        val payloadFile = inputData.getString("payloadFile") ?: ""
        if (payloadFile.isNotEmpty()) {
            try {
                val file = File(payloadFile)
                if (file.exists()) {
                    val json = JSONObject(file.readText())
                    return json.optString("sourceUrl", "") to json.optString("fileName", "")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Cannot read stream payload", e)
            }
        }
        return (inputData.getString("sourceUrl") ?: "") to (inputData.getString("fileName") ?: "")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val (sourceUrl, fileName) = loadPipePayload()
        if (sourceUrl.isEmpty() || fileName.isEmpty()) return@withContext Result.failure()

        // FIX D6: Đọc credentials từ SecurePrefsHelper (EncryptedSharedPreferences) thay vì inputData.
        // WorkData được lưu vào SQLite KHÔNG mã hóa của WorkManager → có thể bị đọc bởi root/backup tools.
        // BaseUrl được chọn thông minh qua SmartNetworkManager (ưu tiên LAN, fallback Tailscale).
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        val baseUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }

        if (user.isEmpty() || pass.isEmpty() || baseUrl.isEmpty()) return@withContext Result.failure()

        createChannel(applicationContext)

        val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Truyền video về NAS")
            .setContentText(fileName)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            // Từ Android 10 (Q) trở lên bắt buộc khai báo foregroundServiceType
            // khớp manifest, nếu không sẽ ném MissingForegroundServiceTypeException.
            setForeground(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ForegroundInfo(
                        NOTIFICATION_ID, notificationBuilder.build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build())
                }
            )
        } catch (_: Exception) {}

        setProgress(workDataOf("status" to "connecting", "progress" to 0))

        try {
            val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

            // FIX #21: Tự động trích xuất Referer từ sourceUrl thay vì hardcode tiktok.com
            val refererUrl = try { java.net.URL(sourceUrl).let { "${it.protocol}://${it.host}/" } } catch (_: Exception) { "" }

            // ── 1. HEAD → kích thước file ──
            val headRequest = okhttp3.Request.Builder()
                .url(sourceUrl).head()
                .header("User-Agent", ua)
                .apply { if (refererUrl.isNotEmpty()) header("Referer", refererUrl) }
                .build()

            val totalBytes: Long = try {
                NasApplication.instance.fastApiClient.newCall(headRequest).execute().use { resp ->
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
            } catch (_: Exception) { -1L }

            // ── 2. GET stream từ CDN ──
            val getRequest = okhttp3.Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", ua)
                .header("Range", "bytes=0-")
                .build()

            val pipeClient = NasApplication.instance.fastApiClient.newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            pipeClient.newCall(getRequest).execute().use { cdnResponse ->
                if (!cdnResponse.isSuccessful && cdnResponse.code != 206) {
                    throw Exception("CDN từ chối (HTTP ${cdnResponse.code})")
                }

                val cdnBody = cdnResponse.body
                    ?: throw Exception("CDN trả về body rỗng")
                val actualTotal = cdnBody.contentLength().takeIf { it > 0 } ?: totalBytes

                // ── 3. Tạo URL đích trên NAS ──
                val socialFolder = if (baseUrl.endsWith("/")) baseUrl + AppConfig.SOCIAL_DOWNLOAD_FOLDER
                                   else "$baseUrl/${AppConfig.SOCIAL_DOWNLOAD_FOLDER}"

                try { WebDavManager.createFolder(socialFolder) } catch (_: Exception) {}

                val safeFileName = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val safeFileNameEncoded = java.net.URLEncoder.encode(safeFileName, "UTF-8").replace("+", "%20")
                val destUrl = if (socialFolder.endsWith("/")) "$socialFolder$safeFileNameEncoded"
                              else "$socialFolder/$safeFileNameEncoded"

                // ── 4. Streaming body CDN→NAS ──
                var totalBytesRead = 0L
                val startTime = System.currentTimeMillis()
                var lastNotifyUpdate = startTime

                val streamBody = object : okhttp3.RequestBody() {
                    override fun contentType() = "video/mp4".toMediaTypeOrNull()
                    // Dùng -1L để kích hoạt Chunked Transfer-Encoding, tránh lỗi 500 khi CDN không trả Content-Length chính xác
                    override fun contentLength() = -1L

                    override fun writeTo(sink: okio.BufferedSink) {
                        val bufferSize = AppConfig.PROXY_BUFFER_SIZE.toLong()
                        cdnBody.source().use { cdnSource ->
                            var readCount: Long
                            while (cdnSource.read(sink.buffer, bufferSize)
                                       .also { readCount = it } != -1L) {
                                if (isStopped) throw Exception("Người dùng đã hủy tác vụ")
                                sink.emit()
                                totalBytesRead += readCount

                                val now = System.currentTimeMillis()
                                // Giảm throttle từ 1000ms xuống 200ms để Notification Bar mượt hơn
                                if (now - lastNotifyUpdate >= 200 || totalBytesRead == actualTotal) {
                                    val percent = if (actualTotal > 0) ((totalBytesRead.toFloat() / actualTotal) * 100).toInt() else 0
                                    val elapsedSec = (now - startTime) / 1000.0
                                    val speedBps = if (elapsedSec > 0) (totalBytesRead / elapsedSec).toLong() else 0L
                                    val etaSec = if (speedBps > 0 && actualTotal > 0) (actualTotal - totalBytesRead) / speedBps else 0L

                                    // Cập nhật Notification
                                    val sizeStr = com.nas.naswebdav.utils.FormatUtils.formatBytes(totalBytesRead)
                                    val totalStr = if (actualTotal > 0) com.nas.naswebdav.utils.FormatUtils.formatBytes(actualTotal) else "?"
                                    val speedStr = com.nas.naswebdav.utils.FormatUtils.formatBytes(speedBps) + "/s"

                                    notificationBuilder
                                        .setContentTitle("📡 $sizeStr / $totalStr ($speedStr)")
                                        .setContentText(safeFileName)
                                        .setProgress(100, percent.coerceIn(0, 100), false)
                                    try {
                                        NotificationManagerCompat.from(applicationContext)
                                            .notify(NOTIFICATION_ID, notificationBuilder.build())
                                    } catch (_: SecurityException) {}

                                    // Báo UI
                                    setProgressBlocking(workDataOf(
                                        "status" to "streaming",
                                        "progress" to percent,
                                        "bytesRead" to totalBytesRead,
                                        "totalBytes" to actualTotal,
                                        "speedStr" to speedStr,
                                        "etaSec" to etaSec
                                    ))
                                    lastNotifyUpdate = now
                                }
                            }
                        }
                    }
                }

                // ── 5. PUT lên NAS WebDAV ──
                val putRequest = okhttp3.Request.Builder()
                    .url(destUrl)
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .put(streamBody)
                    .build()

                val nasClient = NasApplication.instance.fastApiClient.newBuilder()
                    .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                nasClient.newCall(putRequest).execute().use { putResp ->
                    if (!putResp.isSuccessful) {
                        throw Exception("WebDAV PUT thất bại (HTTP ${putResp.code})")
                    }
                }

                // ── 6. Thành công ──
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val avgSpeed = if (elapsed > 0) (totalBytesRead / elapsed).toLong() else 0L
                val resultMsg = "✅ ${com.nas.naswebdav.utils.FormatUtils.formatBytes(totalBytesRead)} trong ${elapsed.toInt()}s"

                setProgress(workDataOf(
                    "status" to "success",
                    "progress" to 100,
                    "message" to resultMsg,
                    "bytesRead" to totalBytesRead,
                    "fileName" to safeDataText(safeFileName, 180)
                ))

                // Log
                try {
                    NasApplication.instance.database.logDao().insertLog(SystemLog(
                        type = "SUCCESS", module = "StreamPipe",
                        message = "Đã truyền ${com.nas.naswebdav.utils.FormatUtils.formatBytes(totalBytesRead)} về NAS: $safeFileName (${elapsed.toInt()} giây, trung bình ${com.nas.naswebdav.utils.FormatUtils.formatBytes(avgSpeed)}/s)"
                    ))
                } catch (_: Exception) {}

                // Notification hoàn tất
                val doneNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Truyền video thành công")
                    .setContentText("$safeFileName — ${com.nas.naswebdav.utils.FormatUtils.formatBytes(totalBytesRead)}")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                try {
                    NotificationManagerCompat.from(applicationContext)
                        .notify(NOTIFICATION_ID, doneNotification.build())
                } catch (_: SecurityException) {}
            }

            return@withContext Result.success()

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Lỗi truyền stream", e)
            val errMsg = e.message?.take(100) ?: "Lỗi không xác định"

            setProgress(workDataOf(
                "status" to "error",
                "message" to safeDataText(errMsg)
            ))

            // Notification lỗi
            val errorNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Truyền video thất bại")
                .setContentText(errMsg)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            try {
                NotificationManagerCompat.from(applicationContext)
                    .notify(NOTIFICATION_ID, errorNotification.build())
            } catch (_: SecurityException) {}

            try {
                NasApplication.instance.database.logDao().insertLog(SystemLog(
                    type = "ERROR", module = "StreamPipe",
                    message = "Lỗi: $errMsg"
                ))
            } catch (_: Exception) {}

            return@withContext Result.failure()
        } finally {
            try {
                inputData.getString("payloadFile")?.let { File(it).delete() }
            } catch (_: Exception) {}
        }
    }

    // Helper: setProgress từ non-suspend context (writeTo) — bridge qua runBlocking
    private fun setProgressBlocking(data: androidx.work.Data) {
        try {
            kotlinx.coroutines.runBlocking {
                setProgress(data)
            }
        } catch (_: Exception) {}
    }
}
