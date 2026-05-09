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
import org.json.JSONObject
import java.io.File

/**
 * BatchOperationWorker — Foreground Worker chạy ngầm cho các tác vụ Copy/Move/Delete/Restore hàng loạt.
 *
 * Ưu điểm so với viewModelScope.launch:
 * - Tiến trình KHÔNG BỊ HỦY khi người dùng tắt App hoặc thu nhỏ ứng dụng.
 * - Hiển thị thanh tiến trình trên Notification Bar (Thanh thông báo) theo thời gian thực.
 * - Hệ điều hành Android cấp phát ưu tiên cao (Foreground Service) → Tránh bị OOM Killer xóa sổ.
 *
 * Input Data:
 *   - "operation" : "COPY" | "MOVE" | "DELETE" | "RESTORE"
 *   - "filePaths" : String[] — danh sách đường dẫn WebDAV đầy đủ (source)
 *   - "fileNames" : String[] — tên hiển thị tương ứng
 *   - "destUrl"   : String   — thư mục đích (cho COPY/MOVE, không cần cho DELETE)
 *   - "baseUrl"   : String   — WebDAV base URL hiện tại (để tính trash path)
 */
class BatchOperationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "batch_operation_channel"
        const val NOTIFICATION_ID = 9010
        private const val TAG = "BatchOp"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Tác vụ hàng loạt",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Hiển thị tiến trình Copy/Move/Delete file trên NAS"
                    setShowBadge(false)
                }
                (context.getSystemService(NotificationManager::class.java))
                    ?.createNotificationChannel(channel)
            }
        }
    }

    private fun safeDataText(value: String, maxChars: Int = 180): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "..."
    }

    private fun loadBatchFiles(): Pair<Array<String>, Array<String>> {
        val payloadFile = inputData.getString("payloadFile") ?: ""
        if (payloadFile.isNotEmpty()) {
            try {
                val file = File(payloadFile)
                if (file.exists()) {
                    val items = JSONObject(file.readText()).optJSONArray("files")
                    if (items != null) {
                        val paths = ArrayList<String>(items.length())
                        val names = ArrayList<String>(items.length())
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val path = item.optString("path", "")
                            if (path.isEmpty()) continue
                            paths.add(path)
                            names.add(item.optString("name", path.substringAfterLast("/")))
                        }
                        return paths.toTypedArray() to names.toTypedArray()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Cannot read batch payload", e)
            }
        }
        val filePaths = inputData.getStringArray("filePaths") ?: emptyArray()
        val fileNames = inputData.getStringArray("fileNames") ?: emptyArray()
        return filePaths to fileNames
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operation = inputData.getString("operation") ?: return@withContext Result.failure()
        val (filePaths, fileNames) = loadBatchFiles()
        val destUrl = inputData.getString("destUrl") ?: ""
        val baseUrl = inputData.getString("baseUrl") ?: ""

        if (filePaths.isEmpty()) return@withContext Result.success()

        // Kết nối WebDAV — sử dụng SmartNetworkManager để chọn URL đang hoạt động (LAN hoặc Tailscale)
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        // FIX D3: Đã trong withContext(IO) → gọi suspend fun trực tiếp, không cần runBlocking
        val savedUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        if (savedUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) return@withContext Result.failure()

        val webDavManager = WebDavManager
        webDavManager.connect(savedUrl, user, pass)

        // Tạo Foreground Notification
        createChannel(applicationContext)
        val operationLabel = when (operation) {
            "COPY" -> "Sao chép"
            "MOVE" -> "Di chuyển"
            "DELETE" -> "Xóa"
            "RESTORE" -> "Khôi phục"
            else -> "Xử lý"
        }

        val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$operationLabel ${filePaths.size} tệp")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            setForeground(
                ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build())
            )
        } catch (e: Exception) {
            try {
                androidx.core.app.NotificationManagerCompat.from(applicationContext)
                    .notify(NOTIFICATION_ID, notificationBuilder.build())
            } catch (_: Exception) {}
        }

        val total = filePaths.size
        var successCount = 0
        var failCount = 0
        val trashFolderName = ".trash/"

        // Chuẩn bị Thùng rác (chỉ cho DELETE)
        if (operation == "DELETE" && baseUrl.isNotEmpty()) {
            val trashUrl = baseUrl + trashFolderName
            if (filePaths.any { !it.contains(trashFolderName) }) {
                try { webDavManager.createFolder(trashUrl) } catch (_: Exception) {}
            }
        }

        var lastNotifyUpdate = 0L

        for ((index, filePath) in filePaths.withIndex()) {
            if (isStopped) break

            val fileName = fileNames.getOrElse(index) { filePath.substringAfterLast("/") }
            val displayName = safeDataText(fileName)
            val percentDone = ((index.toFloat() / total) * 100).toInt()

            val now = System.currentTimeMillis()
            if (now - lastNotifyUpdate > 200 || index == 0 || index == total - 1) {
                lastNotifyUpdate = now

                // Cập nhật Notification Bar
                notificationBuilder
                    .setContentTitle("$operationLabel (${ index + 1 }/$total)")
                    .setContentText(displayName)
                    .setProgress(100, percentDone, false)
                try {
                    NotificationManagerCompat.from(applicationContext)
                        .notify(NOTIFICATION_ID, notificationBuilder.build())
                } catch (_: SecurityException) {}

                // Báo cáo tiến trình cho UI (nếu App đang mở)
                setProgress(workDataOf(
                    "completed" to index,
                    "total" to total,
                    "currentFile" to displayName,
                    "operation" to operation,
                    "percent" to percentDone
                ))
            }

            try {
                when (operation) {
                    "COPY" -> {
                        val safeDestUrl = if (destUrl.endsWith("/")) destUrl else "$destUrl/"
                        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        webDavManager.copyFile(filePath, safeDestUrl + encodedName)
                        successCount++
                    }
                    "MOVE" -> {
                        val safeDestUrl = if (destUrl.endsWith("/")) destUrl else "$destUrl/"
                        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        webDavManager.renameFile(filePath, safeDestUrl + encodedName)
                        successCount++
                    }
                    "DELETE" -> {
                        if (!filePath.contains(trashFolderName)) {
                            // Di chuyển vào Thùng rác
                            val trashUrl = baseUrl + trashFolderName
                            val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                            webDavManager.renameFile(filePath, trashUrl + encodedName)
                        } else {
                            // Đã ở trong Thùng rác → Xóa vĩnh viễn
                            webDavManager.deleteFile(filePath)
                        }
                        successCount++
                    }
                    "RESTORE" -> {
                        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        val targetUrl = baseUrl + encodedName
                        webDavManager.renameFile(filePath, targetUrl)
                        successCount++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Lỗi $operation file: $fileName", e)
                failCount++
            }
        }

        // Báo cáo kết quả cuối cùng cho UI
        setProgress(workDataOf(
            "completed" to total,
            "total" to total,
            "currentFile" to "Hoàn tất",
            "operation" to operation,
            "percent" to 100,
            "successCount" to successCount,
            "failCount" to failCount
        ))

        // Ghi log hệ thống
        try {
            val db = NasApplication.instance.database
            val logType = if (failCount == 0) "SUCCESS" else "WARNING"
            val logMsg = if (failCount == 0) {
                "$operationLabel thành công $successCount/$total tệp."
            } else {
                "$operationLabel: $successCount thành công, $failCount thất bại."
            }
            db.logDao().insertLog(SystemLog(type = logType, module = "Hàng loạt", message = logMsg))
        } catch (_: Exception) {}

        // Hiển thị thông báo hoàn tất (không còn ongoing)
        val resultText = if (failCount == 0) {
            "Hoàn tất $operationLabel $successCount tệp ✅"
        } else {
            "$operationLabel: $successCount thành công, $failCount lỗi"
        }
        val doneNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(resultText)
            .setContentText(if (failCount > 0) "Một số tệp không thể xử lý." else "Tất cả tệp đã được xử lý thành công!")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, doneNotification.build())
        } catch (_: SecurityException) {}

        try {
            inputData.getString("payloadFile")?.let { File(it).delete() }
        } catch (_: Exception) {}

        return@withContext Result.success()
    }
}
