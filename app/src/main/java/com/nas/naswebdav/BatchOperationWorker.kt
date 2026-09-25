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
import kotlinx.coroutines.asContextElement
import org.json.JSONObject
import java.net.URL
import java.io.File

/**
 * BatchOperationWorker — Foreground Worker chạy ngầm cho các tác vụ Copy/Move/Delete/Restore hàng loạt.
 *
 * Ưu điểm so với viewModelScope.launch:
 * - Tiến trình KHÔNG BỊ HỦY khi người dùng tắt App hoặc thu nhỏ ứng dụng.
 * - Hiển thị thanh tiến trình trên Notification Bar (Thanh thông báo) theo thời gian thực.
 * - Hệ điều hành Android cấp phát ưu tiên cao (Foreground Service) — Tránh bị OOM Killer xóa sạch.
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
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.batch_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.batch_channel_description)
                setShowBadge(false)
            }
            (context.getSystemService(NotificationManager::class.java))
                ?.createNotificationChannel(channel)
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.e(TAG, "Cannot read batch payload", e)
            }
        }
        val filePaths = inputData.getStringArray("filePaths") ?: emptyArray()
        val fileNames = inputData.getStringArray("fileNames") ?: emptyArray()
        return filePaths to fileNames
    }

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val operation = inputData.getString("operation") ?: return Result.failure()
        val (filePaths, fileNames) = loadBatchFiles()
        val destUrl = inputData.getString("destUrl") ?: ""
        if (filePaths.isEmpty()) return Result.success()

        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        val savedUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        if (savedUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) return Result.failure()

        return withContext(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(WebDavManager.AuthState(savedUrl, user, pass))) {
            val webDavManager = WebDavManager
            val activeBaseUrl = savedUrl
            val db = NasApplication.instance.database
            val trashMetaDao = db.trashMetaDao()

            createChannel(applicationContext)
            val operationLabel = when (operation) {
                "COPY" -> applicationContext.getString(R.string.batch_operation_copy)
                "MOVE" -> applicationContext.getString(R.string.batch_operation_move)
                "DELETE" -> applicationContext.getString(R.string.batch_operation_delete)
                "RESTORE" -> applicationContext.getString(R.string.batch_operation_restore)
                else -> applicationContext.getString(R.string.batch_operation_process)
            }

            val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(applicationContext.getString(R.string.batch_operation_count, operationLabel, filePaths.size))
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            try {
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                val exName = e.javaClass.name
                val isBgRestriction = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    && exName.contains("ForegroundServiceStartNotAllowed")
                val isMissingType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && (exName.contains("MissingForegroundServiceType") || exName.contains("ForegroundServiceType"))
                when {
                    isBgRestriction -> {
                        android.util.Log.w(TAG, "Background restriction: skip foreground (worker continues)", e)
                    }
                    isMissingType -> {
                        android.util.Log.e(TAG, "setForeground failed: missing foregroundServiceType", e)
                        return@withContext Result.failure()
                    }
                    else -> android.util.Log.w(TAG, "setForeground non-fatal: ${e.javaClass.simpleName}", e)
                }
            }

            val total = filePaths.size
            var successCount = 0
            var failCount = 0
            val trashFolderName = ".trash/"

            var lastNotifyUpdate = 0L

            for ((index, filePath) in filePaths.withIndex()) {
                if (isStopped) break

                val fileName = fileNames.getOrElse(index) { filePath.substringAfterLast("/") }
                val displayName = safeDataText(fileName)
                val percentDone = ((index.toFloat() / total) * 100).toInt()

                val now = System.currentTimeMillis()
                if (now - lastNotifyUpdate > 200 || index == 0 || index == total - 1) {
                    lastNotifyUpdate = now

                    notificationBuilder
                        .setContentTitle(applicationContext.getString(R.string.batch_operation_progress, operationLabel, index + 1, total))
                        .setContentText(displayName)
                        .setProgress(100, percentDone, false)
                    try {
                        NotificationManagerCompat.from(applicationContext)
                            .notify(NOTIFICATION_ID, notificationBuilder.build())
                    } catch (_: SecurityException) {}

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
                            try {
                                if (sourceUrl.contains(trashFolderName) && !targetUrl.contains(trashFolderName)) {
                                    trashMetaDao.deleteByTrashPath(sourceUrl)
                                } else if (!sourceUrl.contains(trashFolderName) && targetUrl.contains(trashFolderName)) {
                                    trashMetaDao.insert(TrashMeta(trashPath = targetUrl, originalPath = sourceUrl))
                                }
                            } catch (dbEx: kotlinx.coroutines.CancellationException) { throw dbEx } catch (dbEx: Exception) {
                                android.util.Log.w(TAG, "DB sync failed after MOVE $fileName (NAS OK)", dbEx)
                            }
                            successCount++
                        }
                        "DELETE" -> {
                            if (!isInTrash) {
                                val trashFolderUrl = buildWebDavTrashTargetUrl(activeBaseUrl, sourceUrl, "", false)
                                val targetUrl = buildWebDavTrashTargetUrl(activeBaseUrl, sourceUrl, fileName, isDirectory)
                                try { webDavManager.createFolder(trashFolderUrl) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                                try {
                                    webDavManager.renameFile(sourceUrl, targetUrl)
                                    try {
                                        trashMetaDao.insert(TrashMeta(trashPath = targetUrl, originalPath = sourceUrl))
                                    } catch (dbEx: kotlinx.coroutines.CancellationException) { throw dbEx } catch (dbEx: Exception) {
                                        android.util.Log.w(TAG, "DB sync failed after DELETE $fileName (NAS OK)", dbEx)
                                    }
                                } catch (moveEx: kotlinx.coroutines.CancellationException) {
                                    throw moveEx
                                } catch (moveEx: Exception) {
                                    // P1-3: MOVE trash that bai -> GIU FILE + bao loi,
                                    // KHONG fallback DELETE vinh vien. Loi quyen
                                    // thu muc dich hoac su co trash ma tu xoa vinh
                                    // vien thi mat du lieu khong khoi phuc duoc.
                                    android.util.Log.w(TAG, "MOVE to .trash failed for $fileName, giu file: ${moveEx.message}")
                                    com.nas.naswebdav.utils.SystemLogger.log("WARNING", "BatchOperation",
                                        "Khong chuyen duoc vao trash (giu file): $sourceUrl — ${moveEx.message}")
                                    throw moveEx
                                }
                            } else {
                                webDavManager.deleteFile(sourceUrl, isDirectory)
                                try {
                                    trashMetaDao.deleteByTrashPath(sourceUrl)
                                } catch (dbEx: kotlinx.coroutines.CancellationException) { throw dbEx } catch (dbEx: Exception) {
                                    android.util.Log.w(TAG, "DB sync failed after DELETE (permanent) $fileName", dbEx)
                                }
                            }
                            successCount++
                        }
                        "RESTORE" -> {
                            val targetUrl = try {
                                trashMetaDao.findByTrashPath(sourceUrl)?.originalPath
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                                null
                            } ?: buildWebDavRestoreTargetUrl(activeBaseUrl, sourceUrl, fileName, isDirectory)
                            webDavManager.renameFile(sourceUrl, targetUrl)
                            try {
                                trashMetaDao.deleteByTrashPath(sourceUrl)
                            } catch (dbEx: kotlinx.coroutines.CancellationException) { throw dbEx } catch (dbEx: Exception) {
                                android.util.Log.w(TAG, "DB sync failed after RESTORE $fileName (NAS OK)", dbEx)
                            }
                            successCount++
                        }
                        else -> {
                            // Unknown operation — DO NOT increment successCount here.
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.w(TAG, "Lỗi $operation file: $fileName", e)
                    failCount++
                }

                kotlinx.coroutines.delay(100L)
            }

            if (isStopped) {
                android.util.Log.w(TAG, "Batch operation $operation was stopped/cancelled before completion ($successCount/$total succeeded)")
                return@withContext Result.failure()
            }

            setProgress(workDataOf(
                "completed" to total,
                "total" to total,
                "currentFile" to applicationContext.getString(R.string.batch_operation_complete),
                "operation" to operation,
                "percent" to 100,
                "successCount" to successCount,
                "failCount" to failCount
            ))

            try {
                val db = NasApplication.instance.database
                val logType = if (failCount == 0) "SUCCESS" else "WARNING"
                val logMsg = if (failCount == 0) {
                    applicationContext.getString(
                        R.string.batch_operation_success_log,
                        operationLabel,
                        successCount,
                        total
                    )
                } else {
                    applicationContext.getString(
                        R.string.batch_operation_partial_log,
                        operationLabel,
                        successCount,
                        failCount
                    )
                }
                db.logDao().insertLog(
                    SystemLog(
                        type = logType,
                        module = applicationContext.getString(R.string.batch_operation_module),
                        message = logMsg
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}

            val resultText = if (failCount == 0) {
                applicationContext.getString(
                    R.string.batch_operation_complete_success,
                    operationLabel,
                    successCount
                )
            } else {
                applicationContext.getString(
                    R.string.batch_operation_complete_partial,
                    operationLabel,
                    successCount,
                    failCount
                )
            }
            val doneNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(resultText)
                .setContentText(
                    if (failCount > 0)
                        applicationContext.getString(R.string.batch_operation_some_failed)
                    else
                        applicationContext.getString(R.string.batch_operation_all_success)
                )
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            try {
                NotificationManagerCompat.from(applicationContext)
                    .notify(NOTIFICATION_ID, doneNotification.build())
            } catch (_: SecurityException) {}

            try {
                inputData.getString("payloadFile")?.let { File(it).delete() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}

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
}
