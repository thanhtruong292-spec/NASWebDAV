package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nas.naswebdav.utils.HashUtils
import com.nas.naswebdav.utils.ImageFingerprint
import com.nas.naswebdav.utils.SystemLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object AutoBackupState {
    val isPaused = MutableStateFlow(false)
    val showResultDialog = MutableStateFlow(false)
    val resultTotal = MutableStateFlow(0)
    val resultSuccess = MutableStateFlow(0)
    val resultSkipped = MutableStateFlow(0)
    val resultFailed = MutableStateFlow(0)
}

class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SystemLogger.log("INFO", "AutoBackup", "Bắt đầu tiến trình đồng bộ nền (Worker khởi động).")
        // FIX CRITICAL: On Android 14+ (API 34), setForeground() throws
        // MissingForegroundServiceTypeException if manifest or code omits the correct
        // foregroundServiceType. Swallowing the exception silently causes the Worker to
        // run as background work → killed by system within seconds.
        try {
            setForeground(makeForegroundInfo("auto_backup_channel", "Auto Backup", 9903, "Auto Backup đang chạy..."))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            val isFatal = when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    e.javaClass.name.contains("ForegroundService") || e.javaClass.name.contains("ForegroundServiceType")
                else -> false
            }
            if (isFatal) {
                android.util.Log.e("AutoBackup", "setForeground failed (fatal)", e)
                SystemLogger.log("ERROR", "AutoBackup", "Lỗi khởi động nền (Foreground): ${e.message}")
                return@withContext Result.failure()
            }
            // Non-fatal — continue (e.g. notification channel not yet created)
        }
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NASWebDAV:AutoBackupWakeLock")
        // FIX-THUMB-DELEGATION: own gate key "autobackup" so it doesn't collide with
        // DuplicateScan/OfflineSync "sync" — each worker manages its own block.
        setThumbnailActivity("autobackup", true)
        // FIX #23: Giảm WakeLock từ 3 tiếng xuống 60 phút — backup tối đa 1 giờ là hợp lý
        // Nếu upload bị trẾ (server không phản hồi), thiết bị ko bị hao pin đến 3 tiếng
        wakeLock.acquire(60 * 60 * 1000L)
        // FIX D2b: Đã trong withContext(IO) → gọi suspend fun trực tiếp, không cần runBlocking
        val baseUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val settingsPrefs = SecurePrefsHelper.getSettingsPrefs(applicationContext)
        val deleteAfterBackup = settingsPrefs.getBoolean("delete_after_backup", false)
        val webDavManager = loadWebDavManager() ?: run {
            SystemLogger.log("ERROR", "AutoBackup", "Lỗi cấu hình NAS: URL hoặc tài khoản trống.")
            return@withContext Result.failure()
        }
        if (runAttemptCount >= 3) {
            SystemLogger.log("ERROR", "AutoBackup", "Đã ghi nhận $runAttemptCount lần thực thi thất bại.")
            return@withContext Result.failure()
        }

        // ── SMB probe: check if SMB is enabled on NAS (one-time) ──
        val smbUser = SecurePrefsHelper.getUser(applicationContext)
        val smbPass = SecurePrefsHelper.getPass(applicationContext)
        val smbHost = try { URI(baseUrl).host ?: "" } catch (_: Exception) { "" }
        val smbShare = "NAS_Data"
        var smbEnabled = false
        try {
            val apiBaseUrl = if (baseUrl.contains("/api/")) baseUrl.substringBeforeLast("/api/") + "/api/"
                              else if (baseUrl.endsWith("/")) "${baseUrl}api/" else "$baseUrl/api/"
            // RESOURCE LEAK FIX: dùng NasApplication.instance.fastApiClient thay vì tạo OkHttpClient mới.
            // Trước đây: tạo client tạm mỗi lần → idle connections kẹt Connection Pool 5 phút + threads không shutdown.
            // Giờ: tái sử dụng connection pool có sẵn (pool size 3, keep-alive 3 phút).
            val request = okhttp3.Request.Builder().url("${apiBaseUrl}smb/status").build()
            NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val obj = JSONObject(body)
                        smbEnabled = obj.optBoolean("effective_enabled",
                            obj.optBoolean("enabled", false) && obj.optBoolean("active", false))
                    }
                }
            }
        } catch (_: Exception) { /* SMB unavailable — WebDAV fallback */ }
        if (smbEnabled && smbHost.isNotBlank()) {
            SystemLogger.log("INFO", "AutoBackup", "SMB enabled — host=$smbHost share=$smbShare, uploading via SMB3")
        }

        val db = NasApplication.instance.database
        try {
            val backupFolderBase = if (baseUrl.endsWith("/")) "${baseUrl}AutoBackup/" else "$baseUrl/AutoBackup/"
            try { webDavManager.createFolder(backupFolderBase) } catch (_: Exception) {}
            
            // Cache để lưu các thư mục đã tạo nhằm tránh gọi MKCOL liên tục
            val createdFolders = hashSetOf<String>()
            createdFolders.add(backupFolderBase)

            // Hàm đệ quy tạo thư mục cha trên NAS
            suspend fun ensureFolderExists(nasRelativePath: String): String {
                val segments = nasRelativePath.split("/").filter { it.isNotEmpty() }
                var currentPath = backupFolderBase
                for (segment in segments) {
                    currentPath += "$segment/"
                    if (!createdFolders.contains(currentPath)) {
                        try {
                            webDavManager.createFolder(currentPath)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                            // Ignored (thư mục có thể đã tồn tại)
                        }
                        createdFolders.add(currentPath)
                    }
                }
                return currentPath
            }

            var backupCount = 0
            var skippedCount = 0
            var failedCount = 0
            val urisToQuery = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE)
            
            var totalFilesToProcess = 0
            for (mediaUri in urisToQuery) {
                try {
                    applicationContext.contentResolver.query(mediaUri, projection, null, null, null)?.use { cursor ->
                        totalFilesToProcess += cursor.count
                    }
                } catch(e: Exception) {}
            }
            var processedFilesCount = 0
            val startTime = System.currentTimeMillis()
            
            // Đẩy trạng thái ban đầu để UI không bị kẹt ở "0 / 0 tệp"
            setProgressAsync(workDataOf(
                "fileName" to "Đang chuẩn bị danh sách...",
                "sourcePath" to "Thiết bị máy trạm",
                "destPath" to safeWorkerText(backupFolderBase, 220),
                "progress" to 0f,
                "processedCount" to 0,
                "totalCount" to totalFilesToProcess,
                "elapsedTime" to 0L
            ))
            
            for (mediaUri in urisToQuery) {
                if (isStopped) break
                applicationContext.contentResolver.query(mediaUri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    var lastSkipProgressTime = 0L
                    
                    while (cursor.moveToNext() && !isStopped) {
                        while (AutoBackupState.isPaused.value && !isStopped) { kotlinx.coroutines.delay(500) }
                        processedFilesCount++
                        val rawFileName = cursor.getString(nameIndex) ?: continue
                        val fileName = com.nas.naswebdav.utils.FormatUtils.sanitizeFileName(rawFileName)
                        val dataPath = cursor.getString(dataIndex) ?: continue
                        val id = cursor.getLong(idIndex)
                        
                        // Loại bỏ tiền tố /storage/emulated/0/ để lấy đường dẫn tương đối đẹp nhất
                        val externalStorageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
                        val cleanRelativePath = if (dataPath.startsWith(externalStorageRoot)) {
                            dataPath.substring(externalStorageRoot.length).trimStart('/')
                        } else {
                            // Fallback nếu không thuộc emulated storage (ít gặp)
                            val segments = dataPath.split("/")
                            if (segments.size >= 2) "${segments[segments.size - 2]}/${segments.last()}" else fileName
                        }
                        
                        // Ví dụ: DCIM/Camera/IMG_123.jpg
                        val parentRelativePath = cleanRelativePath.substringBeforeLast("/", "")
                        val targetFolder = if (parentRelativePath.isNotEmpty()) {
                            ensureFolderExists(parentRelativePath)
                        } else {
                            backupFolderBase
                        }
                        
                        val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        val targetFileNasPath = if (targetFolder.endsWith("/")) targetFolder + encodedFileName else "$targetFolder/$encodedFileName"
                        
                        // Bỏ qua kiểm tra existingRemoteFiles dạng list toàn bộ vì giờ cấu trúc thành dạng Tree,
                        // thay vào đó chúng ta sẽ rely vào Database / Hash hoặc Head Request để tránh trùng

                        val fileSize = cursor.getLong(sizeIndex)
                        if (fileSize == 0L) {
                            // FIX counter sum: file rong duoc bo qua phai duoc cong vao skipped
                            // de tong (success + skipped + failed) khop voi totalFilesToProcess
                            // trong dialog ket qua cuoi.
                            skippedCount++
                            continue
                        }
                        val fileUri = android.content.ContentUris.withAppendedId(mediaUri, id)
                        try {
                            val fileHash: String? = try { com.nas.naswebdav.utils.ImageFingerprint.computeFromUri(applicationContext, fileUri) } catch (_: Exception) { null }
                            var isSkipped = false
                            if (fileHash != null) { 
                                val existingFp = db.fingerprintDao().findByExactHash(fileHash); 
                                if (existingFp != null) isSkipped = true 
                            }
                            
                            if (isSkipped) {
                                val now = System.currentTimeMillis()
                                if (now - lastSkipProgressTime > 300) {
                                    lastSkipProgressTime = now
                                    val percent = if (totalFilesToProcess > 0) processedFilesCount.toFloat() / totalFilesToProcess else 0f
                                    setProgressAsync(workDataOf(
                                        "fileName" to safeWorkerText("Bỏ qua (đã đồng bộ): $fileName", 180),
                                        "sourcePath" to safeWorkerText(dataPath, 220),
                                        "destPath" to safeWorkerText(targetFileNasPath, 220),
                                        "progress" to 1f,
                                        "processedCount" to processedFilesCount,
                                        "totalCount" to totalFilesToProcess,
                                        "elapsedTime" to (now - startTime)
                                    ))
                                }
                                skippedCount++
                                continue
                            }
                            
                            val mimeType = try { applicationContext.contentResolver.getType(ContentUris.withAppendedId(mediaUri, id)) ?: "application/octet-stream" } catch (_: Exception) { "application/octet-stream" }
                            
                            // fileSize đã lấy từ cursor ở trên
                            var lastProgressTime = 0L
                            var lastSpeedCalcTime = System.currentTimeMillis()
                            var lastSpeedCalcBytes = 0L
                            var currentSpeedBps = 0L

                            // ── Upload: SMB (with retry) → WebDAV fallback (with retry) ──
                            var smbUploadOk = false
                            val smbRemotePath = targetFileNasPath.removePrefix(backupFolderBase)
                            if (smbEnabled && smbHost.isNotBlank()) {
                                // FIX-LARGE-FILE-SMB: retry ở caller — mỗi lượt mở InputStream mới.
                                // SmbManager.uploadFile throw exception khi fail → catch ở đây, fallback WebDAV.
                                for (smbAttempt in 1..3) {
                                    try {
                                        val smbInput = applicationContext.contentResolver.openInputStream(
                                            ContentUris.withAppendedId(mediaUri, id)
                                        ) ?: error("ContentResolver trả null cho $fileName")
                                        smbUploadOk = smbInput.use { input ->
                                            com.nas.naswebdav.SmbManager.uploadFile(
                                                host = smbHost,
                                                user = smbUser,
                                                pass = smbPass,
                                                share = smbShare,
                                                remotePath = smbRemotePath,
                                                inputStream = input,
                                                totalSize = fileSize
                                            ) { bytesWritten, totalBytes ->
                                                if (isStopped) throw kotlinx.coroutines.CancellationException("User cancelled upload")
                                                val now = System.currentTimeMillis()
                                                val dt = (now - lastSpeedCalcTime) / 1000.0
                                                if (dt >= 0.4 || bytesWritten == totalBytes) {
                                                    if (dt > 0) currentSpeedBps = ((bytesWritten - lastSpeedCalcBytes) / dt).toLong().coerceAtLeast(0L)
                                                    lastSpeedCalcTime = now
                                                    lastSpeedCalcBytes = bytesWritten
                                                }
                                                if (now - lastProgressTime > 200 || bytesWritten == totalBytes) {
                                                    lastProgressTime = now
                                                    val percent = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f
                                                    val formattedSpeed = com.nas.naswebdav.utils.FormatUtils.formatBytes(currentSpeedBps)
                                                    setProgressAsync(workDataOf(
                                                        "fileName" to safeWorkerText(fileName, 180),
                                                        "sourcePath" to safeWorkerText(dataPath, 220),
                                                        "destPath" to safeWorkerText(targetFileNasPath, 220),
                                                        "progress" to percent,
                                                        "processedCount" to processedFilesCount,
                                                        "totalCount" to totalFilesToProcess,
                                                        "elapsedTime" to (now - startTime),
                                                        "bytesWritten" to bytesWritten,
                                                        "bytesTotal" to totalBytes,
                                                        "uploadSpeedBps" to currentSpeedBps
                                                    ))
                                                    try {
                                                        val progressInt = (percent * 100).toInt()
                                                        val speedNotice = if (currentSpeedBps > 0) " ($formattedSpeed/s)" else ""
                                                        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, "auto_backup_channel")
                                                            .setSmallIcon(android.R.drawable.ic_menu_upload)
                                                            .setContentTitle("Đang sao lưu lên NAS (SMB): $progressInt%$speedNotice")
                                                            .setContentText(safeWorkerText("$fileName\n$parentRelativePath", 120))
                                                            .setProgress(100, progressInt, false)
                                                            .setOnlyAlertOnce(true)
                                                            .setSilent(true)
                                                            .setOngoing(true)
                                                        androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(9903, notificationBuilder.build())
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                        if (smbUploadOk) break
                                    } catch (e: Exception) {
                                        android.util.Log.w("AutoBackup", "SMB attempt $smbAttempt failed for $fileName: ${e.message}")
                                    }
                                }
                            }

                            if (!smbUploadOk) {
                                if (isStopped) throw kotlinx.coroutines.CancellationException("User cancelled upload")
                                if (smbEnabled && smbHost.isNotBlank()) {
                                    android.util.Log.w("AutoBackup", "SMB upload failed for $fileName — falling back to WebDAV")
                                }
                                // FIX-LARGE-FILE-2: retry WebDAV upload lên đến 3 lần khi gặp lỗi 500/502/503.
                                // Mỗi lần retry cần mở lại InputStream vì stream cũ đã bị consume.
                                var lastWebDavException: Exception? = null
                                for (webDavAttempt in 1..3) {
                                    try {
                                        val rawStream = applicationContext.contentResolver.openInputStream(ContentUris.withAppendedId(mediaUri, id))
                                            ?: error("Không đọc được file $fileName (ContentResolver trả null)")
                                        rawStream.use { input2 ->
                                            val useCompression = com.nas.naswebdav.utils.HashUtils.shouldCompress(mimeType)
                                            val uploadCall: suspend (
                                                String, java.io.InputStream, Long, String,
                                                (Long, Long) -> Unit
                                            ) -> Unit = if (useCompression)
                                                webDavManager::uploadCompressedStream else webDavManager::uploadStreamWithProgress
                                            uploadCall(targetFileNasPath, input2, fileSize, mimeType) { bytesWritten, totalBytes ->
                                                if (isStopped) throw kotlinx.coroutines.CancellationException("User cancelled upload")
                                                val now = System.currentTimeMillis()
                                                val dt = (now - lastSpeedCalcTime) / 1000.0
                                                if (dt >= 0.4 || bytesWritten == totalBytes) {
                                                    if (dt > 0) currentSpeedBps = ((bytesWritten - lastSpeedCalcBytes) / dt).toLong().coerceAtLeast(0L)
                                                    lastSpeedCalcTime = now
                                                    lastSpeedCalcBytes = bytesWritten
                                                }
                                                if (now - lastProgressTime > 200 || bytesWritten == totalBytes) {
                                                    lastProgressTime = now
                                                    val percent = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f
                                                    val formattedSpeed = com.nas.naswebdav.utils.FormatUtils.formatBytes(currentSpeedBps)
                                                    setProgressAsync(workDataOf(
                                                        "fileName" to safeWorkerText(fileName, 180),
                                                        "sourcePath" to safeWorkerText(dataPath, 220),
                                                        "destPath" to safeWorkerText(targetFileNasPath, 220),
                                                        "progress" to percent,
                                                        "processedCount" to processedFilesCount,
                                                        "totalCount" to totalFilesToProcess,
                                                        "elapsedTime" to (now - startTime),
                                                        "bytesWritten" to bytesWritten,
                                                        "bytesTotal" to totalBytes,
                                                        "uploadSpeedBps" to currentSpeedBps
                                                    ))
                                                    try {
                                                        val progressInt = (percent * 100).toInt()
                                                        val speedNotice = if (currentSpeedBps > 0) " ($formattedSpeed/s)" else ""
                                                        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, "auto_backup_channel")
                                                            .setSmallIcon(android.R.drawable.ic_menu_upload)
                                                            .setContentTitle("Đang sao lưu lên NAS: $progressInt%$speedNotice")
                                                            .setContentText(safeWorkerText("$fileName\n$parentRelativePath", 120))
                                                            .setProgress(100, progressInt, false)
                                                            .setOnlyAlertOnce(true)
                                                            .setSilent(true)
                                                            .setOngoing(true)
                                                        androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(9903, notificationBuilder.build())
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                        break // upload thành công → thoát retry loop
                                    } catch (e: Exception) {
                                        lastWebDavException = e
                                        val msg = e.message ?: ""
                                        val isServerError = msg.contains("500") || msg.contains("502") || msg.contains("503")
                                        if (isServerError && webDavAttempt < 3) {
                                            android.util.Log.w("AutoBackup", "WebDAV attempt $webDavAttempt failed ($msg), retrying in ${webDavAttempt * 2}s...")
                                            kotlinx.coroutines.delay(webDavAttempt * 2000L)
                                        } else {
                                            throw e
                                        }
                                    }
                                }
                            }

                            // FIX-THUMB-DELEGATION: phone MUST NOT decode video/images for thumbnails.
                            // NAS daemon handles ALL thumbnail generation (idle 24/7 + 5-min rescan +
                            // on-demand /api/thumb). Phone only uploads the file and lets NAS pick it up.
                            val uploadVerified = if (smbUploadOk) true else try { webDavManager.headFileHeaders(targetFileNasPath) != null } catch (_: Exception) { false }
                            if (fileHash != null && uploadVerified) db.fingerprintDao().insertFingerprint(FileFingerprint(filePath = targetFileNasPath, hash = fileHash, fileName = fileName, fileSize = fileSize))
                            if (deleteAfterBackup && uploadVerified) applicationContext.contentResolver.delete(ContentUris.withAppendedId(mediaUri, id), null, null)
                            backupCount++
                        } catch (e: Exception) { 
                            failedCount++
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            if (e !is java.io.FileNotFoundException && !(e.message ?: "").contains("Missing file")) {
                                if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                                    SmartNetworkManager.invalidateCache()
                                    SystemLogger.log("INFO", "AutoBackup", "Mạng chập chờn, tải file $fileName lỗi: ${e.message}")
                                } else {
                                    SystemLogger.log("WARNING", "AutoBackup", "Lỗi tải xuống tập tin $fileName: ${e.message}") 
                                }
                            }
                        }
                        // Nhường luồng cho CPU — thêm delay 100ms để tránh đập NAS liên tục
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
            val logMessage = "Đồng bộ khép kín: Thành công $backupCount tệp, Bỏ qua $skippedCount tệp tập tin trùng lặp, Thất bại: $failedCount tệp."
            if (backupCount > 0 || failedCount > 0) {
                SystemLogger.log(if (failedCount > 0) "WARNING" else "SUCCESS", "AutoBackup", logMessage)
            } else {
                SystemLogger.log("INFO", "AutoBackup", logMessage)
            }
            
            if (totalFilesToProcess == 0) {
                AutoBackupState.resultTotal.value = 0
                AutoBackupState.resultSuccess.value = 0
                AutoBackupState.resultSkipped.value = 0
                AutoBackupState.resultFailed.value = 0
                AutoBackupState.showResultDialog.value = true
                return@withContext Result.success()
            }
            
            AutoBackupState.resultTotal.value = totalFilesToProcess
            AutoBackupState.resultSuccess.value = backupCount
            AutoBackupState.resultSkipped.value = skippedCount
            AutoBackupState.resultFailed.value = failedCount
            AutoBackupState.showResultDialog.value = true
            
            return@withContext Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) {
                return@withContext Result.retry()
            }
            SystemLogger.log("ERROR", "AutoBackup", "Lỗi luồng xử lý Đồng bộ tự động (AutoBackup): ${e.message}")
            val isTransient = e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException
            return@withContext if (isTransient && runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            setThumbnailActivity("autobackup", false)
            if (wakeLock.isHeld) wakeLock.release()
            try { androidx.core.app.NotificationManagerCompat.from(applicationContext).cancel(9903) } catch (_: Exception) {}
        }
    }
}
