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
import java.net.URL
import java.io.File

/**
 * BatchOperationWorker �?? Foreground Worker chạy ngầm cho các tác vụ Copy/Move/Delete/Restore hàng loạt.
 *
 * Ưu �?i�?m so v�?i viewModelScope.launch:
 * - Tiến trình KH�?NG B�? HỦY khi người dùng tắt App hoặc thu nhỏ ứng dụng.
 * - Hi�?n th�? thanh tiến trình trên Notification Bar (Thanh thông báo) theo thời gian thực.
 * - H�? �?iều hành Android cấp phát ưu tiên cao (Foreground Service) �?? Tránh b�? OOM Killer xóa s�?.
 *
 * Input Data:
 *   - "operation" : "COPY" | "MOVE" | "DELETE" | "RESTORE"
 *   - "filePaths" : String[] �?? danh sách �?ường dẫn WebDAV �?ầy �?ủ (source)
 *   - "fileNames" : String[] �?? tên hi�?n th�? tương ứng
 *   - "destUrl"   : String   �?? thư mục �?ích (cho COPY/MOVE, không cần cho DELETE)
 *   - "baseUrl"   : String   �?? WebDAV base URL hi�?n tại (�?�? tính trash path)
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
                    description = "Hi�?n th�? tiến trình Copy/Move/Delete file trên NAS"
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

    private fun resolveBatchWebDavPath(rawPath: String, activeBaseUrl: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val active = runCatching { URL(activeBaseUrl) }.getOrNull() ?: return trimmed
            val raw = runCatching { URL(trimmed) }.getOrNull() ?: return trimmed
            return "${active.protocol}://${active.authority}${raw.path}" + (raw.query?.let { "?$it" } ?: "") + (raw.ref?.let { "#$it" } ?: "")
        }
        val base = activeBaseUrl.trimEnd('/')
        return if (trimmed.startsWith('/')) base + trimmed else "$base/$trimmed"
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

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operation = inputData.getString("operation") ?: return@withContext Result.failure()
        val (filePaths, fileNames) = loadBatchFiles()
        val destUrl = inputData.getString("destUrl") ?: ""
        if (filePaths.isEmpty()) return@withContext Result.success()

        // Kết n�?i WebDAV �?? sử dụng SmartNetworkManager �?�? chọn URL �?ang hoạt �?�?ng (LAN hoặc Tailscale)
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        // FIX D3: Đã trong withContext(IO) �?? gọi suspend fun trực tiếp, không cần runBlocking
        val savedUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        if (savedUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) return@withContext Result.failure()

        val webDavManager = WebDavManager
        webDavManager.connect(savedUrl, user, pass)
        val activeBaseUrl = savedUrl
        val db = NasApplication.instance.database
        val trashMetaDao = db.trashMetaDao()

        // Tạo Foreground Notification
        createChannel(applicationContext)
        val operationLabel = when (operation) {
            "COPY" -> "Sao chép"
            "MOVE" -> "Di chuy�?n"
            "DELETE" -> "Xóa"
            "RESTORE" -> "Khôi phục"
            else -> "Xử lý"
        }

        val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$operationLabel ${filePaths.size} t�?p")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            // Từ Android 10 (Q) tr�? lên bắt bu�?c khai báo foregroundServiceType
            // kh�?p manifest, nếu không sẽ ném MissingForegroundServiceTypeException.
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
        } catch (e: Exception) {
            // FIX CRITICAL: Không fallback sang NotificationManagerCompat.notify()
            // Android 14+ Worker sẽ bị kill nếu không được setForeground đúng cách.
            val isFatal = when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    e.javaClass.name.contains("ForegroundService") || e.javaClass.name.contains("ForegroundServiceType")
                else -> false
            }
            if (isFatal) {
                android.util.Log.e(TAG, "setForeground failed (fatal)", e)
                return@withContext Result.failure()
            }
        }

        val total = filePaths.size
        var successCount = 0
        var failCount = 0
        val trashFolderName = ".trash/"

        // Bỏ vi�?c chuẩn b�? Thùng rác dùng chung �? �?ây vì Trash giờ phụ thu�?c từng �? �?ĩa
        
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

                // Báo cáo tiến trình cho UI (nếu App �?ang m�?)
                setProgress(workDataOf(
                    "completed" to index,
                    "total" to total,
                    "currentFile" to displayName,
                    "operation" to operation,
                    "percent" to percentDone
                ))
            }

            try {
                val sourceUrl = resolveBatchWebDavPath(filePath, activeBaseUrl)
                val normalizedDestUrl = if (destUrl.isNotBlank()) resolveBatchWebDavPath(destUrl, activeBaseUrl) else ""
                val isDirectory = sourceUrl.endsWith("/")
                val isInTrash = sourceUrl.contains(trashFolderName)
                when (operation) {
                    "COPY" -> {
                        val safeDestUrl = if (normalizedDestUrl.endsWith("/")) normalizedDestUrl else "${normalizedDestUrl}/"
                        val encodedName = encodeWebDavSegment(fileName)
                        var targetUrl = safeDestUrl + encodedName
                        if (isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                        webDavManager.copyFile(sourceUrl, targetUrl)
                        successCount++
                    }
                    "MOVE" -> {
                        val safeDestUrl = if (normalizedDestUrl.endsWith("/")) normalizedDestUrl else "${normalizedDestUrl}/"
                        val encodedName = encodeWebDavSegment(fileName)
                        var targetUrl = safeDestUrl + encodedName
                        if (isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                        webDavManager.renameFile(sourceUrl, targetUrl)
                        // DB write riêng — nếu WebDAV thành công mà DB fail,
                        // vẫn count success (NAS file đã di chuyển).
                        try {
                            if (sourceUrl.contains(trashFolderName) && !targetUrl.contains(trashFolderName)) {
                                trashMetaDao.deleteByTrashPath(sourceUrl)
                            } else if (!sourceUrl.contains(trashFolderName) && targetUrl.contains(trashFolderName)) {
                                trashMetaDao.insert(TrashMeta(trashPath = targetUrl, originalPath = sourceUrl))
                            }
                        } catch (dbEx: Exception) {
                            android.util.Log.w(TAG, "DB sync failed after MOVE $fileName (NAS OK)", dbEx)
                        }
                        successCount++
                    }
                    "DELETE" -> {
                        if (!isInTrash) {
                            val trashFolderUrl = buildWebDavTrashTargetUrl(activeBaseUrl, sourceUrl, "", false)
                            val targetUrl = buildWebDavTrashTargetUrl(activeBaseUrl, sourceUrl, fileName, isDirectory)
                            try { webDavManager.createFolder(trashFolderUrl) } catch (_: Exception) {}
                            webDavManager.renameFile(sourceUrl, targetUrl)
                            try {
                                trashMetaDao.insert(TrashMeta(trashPath = targetUrl, originalPath = sourceUrl))
                            } catch (dbEx: Exception) {
                                android.util.Log.w(TAG, "DB sync failed after DELETE $fileName (NAS OK)", dbEx)
                            }
                        } else {
                            webDavManager.deleteFile(sourceUrl, isDirectory)
                            try {
                                trashMetaDao.deleteByTrashPath(sourceUrl)
                            } catch (dbEx: Exception) {
                                android.util.Log.w(TAG, "DB sync failed after DELETE (permanent) $fileName", dbEx)
                            }
                        }
                        successCount++
                    }
                    "RESTORE" -> {
                        val targetUrl = try {
                            trashMetaDao.findByTrashPath(sourceUrl)?.originalPath
                        } catch (_: Exception) {
                            null
                        } ?: buildWebDavRestoreTargetUrl(activeBaseUrl, sourceUrl, fileName, isDirectory)
                        webDavManager.renameFile(sourceUrl, targetUrl)
                        try {
                            trashMetaDao.deleteByTrashPath(sourceUrl)
                        } catch (dbEx: Exception) {
                            android.util.Log.w(TAG, "DB sync failed after RESTORE $fileName (NAS OK)", dbEx)
                        }
                        successCount++
                    }
                    else -> {
                        // Unknown operation ? DO NOT increment successCount here.
                        // Only the 4 valid branches above (COPY/MOVE/DELETE/RESTORE) count as success.
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "L�?i $operation file: $fileName", e)
                failCount++
            }
            
            // UX-01: Delay 100ms to prevent NAS WebDAV daemon from hanging during mass I/O
            kotlinx.coroutines.delay(100L)
        }

        // Báo cáo kết quả cu�?i cùng cho UI
        setProgress(workDataOf(
            "completed" to total,
            "total" to total,
            "currentFile" to "Hoàn tất",
            "operation" to operation,
            "percent" to 100,
            "successCount" to successCount,
            "failCount" to failCount
        ))

        // Ghi log h�? th�?ng
        try {
            val db = NasApplication.instance.database
            val logType = if (failCount == 0) "SUCCESS" else "WARNING"
            val logMsg = if (failCount == 0) {
                "$operationLabel thành công $successCount/$total t�?p."
            } else {
                "$operationLabel: $successCount thành công, $failCount thất bại."
            }
            db.logDao().insertLog(SystemLog(type = logType, module = "Hàng loạt", message = logMsg))
        } catch (_: Exception) {}

        // Hi�?n th�? thông báo hoàn tất (không còn ongoing)
        val resultText = if (failCount == 0) {
            "Hoàn tất $operationLabel $successCount t�?p �??"
        } else {
            "$operationLabel: $successCount thành công, $failCount l�?i"
        }
        val doneNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(resultText)
            .setContentText(if (failCount > 0) "M�?t s�? t�?p không th�? xử lý." else "Tất cả t�?p �?ã �?ược xử lý thành công!")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, doneNotification.build())
        } catch (_: SecurityException) {}

        try {
            inputData.getString("payloadFile")?.let { File(it).delete() }
        } catch (_: Exception) {}

        // FIX: Nếu có file thất bại, return Result.failure(workData) để WorkManager biết
        // operation không hoàn tất. UI sẽ nhận được `failCount > 0` qua setProgress ở trên.
        // Lưu ý: failure chỉ retry khi worker có retry policy; với batch đã chạy gần hết
        // thì retry sẽ duplicate work — caller nên check failCount.
        return@withContext if (failCount > 0) {
            Result.failure(workDataOf(
                "completed" to total,
                "successCount" to successCount,
                "failCount" to failCount
            ))
        } else {
            Result.success(workDataOf(
                "completed" to total,
                "successCount" to successCount,
                "failCount" to 0
            ))
        }
    }
}
