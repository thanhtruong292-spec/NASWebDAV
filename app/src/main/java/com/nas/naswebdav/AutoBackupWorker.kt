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
        // FIX: Kiểm tra quyền truy cập Media ngay đầu Worker.
        // Android 14+: partial access (READ_MEDIA_VISUAL_USER_SELECTED) khiến MediaStore
        // trả về tất cả ảnh nhưng openInputStream() fail → mass "has no access" errors.
        // Worker phải có FULL access (READ_MEDIA_IMAGES/VIDEO) mới chạy được an toàn.
        val permCtx = applicationContext
        if (com.nas.naswebdav.utils.MediaPermissionHelper.hasNoMediaAccess(permCtx)) {
            SystemLogger.log("ERROR", "AutoBackup", "Không có quyền truy cập Media — Worker dừng lại. Vui lòng cấp quyền READ_MEDIA_IMAGES/VIDEO.")
            return@withContext Result.failure()
        }
        if (!com.nas.naswebdav.utils.MediaPermissionHelper.canRunPeriodicBackup(permCtx)) {
            // Partial access — cho phép chạy nhưng chỉ skip ảnh không truy cập được
            SystemLogger.log("WARNING", "AutoBackup",
                "Chỉ có quyền truy cập ảnh một phần (partial access). Một số ảnh có thể bị bỏ qua.")
        }

        SystemLogger.log("INFO", "AutoBackup", "Bắt đầu tiến trình đồng bộ nền (Worker khởi động).")
        // FIX: Xử lý 2 loại lỗi foreground service khác nhau:
        // 1. Android 12+ (API 31): ForegroundServiceStartNotAllowedException — app đang ở
        //    background nên mAllowStartForeground=false → Worker vẫn chạy bình thường, chỉ
        //    không có notification foreground → log WARNING, KHÔNG return failure.
        // 2. Android 14+ (API 34): MissingForegroundServiceTypeException — thiếu
        //    foregroundServiceType trong manifest → Worker bị system kill → log ERROR, return failure.
        try {
            setForeground(makeForegroundInfo("auto_backup_channel", "Auto Backup", 9903, "Auto Backup đang chạy..."))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            val exName = e.javaClass.name
            val isBgRestriction = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && exName.contains("ForegroundServiceStartNotAllowed")
            val isMissingType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && (exName.contains("MissingForegroundServiceType") || exName.contains("ForegroundServiceType"))
            when {
                isBgRestriction -> {
                    // Android 12+: app đang background → skip foreground, worker chạy tiếp
                    android.util.Log.w("AutoBackup", "Background restriction: skip foreground (worker continues)", e)
                    SystemLogger.log("WARNING", "AutoBackup",
                        "Ứng dụng đang chạy nền — bỏ qua thông báo Foreground. Đồng bộ vẫn tiếp tục.")
                }
                isMissingType -> {
                    // Android 14+: thiếu foregroundServiceType → fatal
                    android.util.Log.e("AutoBackup", "setForeground failed: missing foregroundServiceType", e)
                    SystemLogger.log("ERROR", "AutoBackup",
                        "Lỗi thiếu foregroundServiceType (Android 14+): ${e.message}")
                    return@withContext Result.failure()
                }
                else -> {
                    // Lỗi khác (notification channel chưa tạo, v.v.) — bỏ qua
                    android.util.Log.w("AutoBackup", "setForeground non-fatal: ${e.javaClass.simpleName}", e)
                }
            }
        }
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NASWebDAV:AutoBackupWakeLock")
        // FIX-AUDIT-#2: non-reference-counted de acquire/release don khoi bi
        // double-count (acquire 1 lan, release 1 lan trong finally).
        wakeLock.setReferenceCounted(false)
        // FIX-THUMB-DELEGATION: own gate key "autobackup" so it doesn't collide with
        // DuplicateScan/OfflineSync "sync" — each worker manages its own block.
        setThumbnailActivity("autobackup", true)
        // FIX #23: Giảm WakeLock từ 3 tiếng xuống 60 phút — backup tối đa 1 giờ là hợp lý
        // Nếu upload bị trẾ (server không phản hồi), thiết bị ko bị hao pin đến 3 tiếng
        wakeLock.acquire(60 * 60 * 1000L)
        // FIX D2b: Đã trong withContext(IO) → gọi suspend fun trực tiếp, không cần runBlocking
        val baseUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        // P2-muc3+muc4: accountKey + sourceKey on dinh cho fingerprint scope.
        val backupUser = SecurePrefsHelper.getUser(applicationContext)
        val backupAccountKey = runCatching {
            val u = java.net.URL(baseUrl)
            val host = u.host ?: baseUrl
            val port = u.port.takeIf { it > 0 } ?: u.defaultPort
            val root = u.path.trimEnd('/').ifEmpty { "/" }
            "$backupUser@$host:$port$root"
        }.getOrDefault("$backupUser@$baseUrl")
        val settingsPrefs = SecurePrefsHelper.getSettingsPrefs(applicationContext)
        val deleteAfterBackup = settingsPrefs.getBoolean("delete_after_backup", false)
        val webDavManager = loadWebDavManager() ?: run {
            SystemLogger.log("WARNING", "AutoBackup", "Lỗi cấu hình NAS: URL hoặc tài khoản trống.")
            return@withContext Result.failure()
        }
        if (runAttemptCount >= 3) {
            SystemLogger.log("WARNING", "AutoBackup", "Đã ghi nhận $runAttemptCount lần thực thi thất bại.")
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
                } catch(e: Exception) {
                    android.util.Log.w("AutoBackup", "Media query failed: ${e.message}")
                }
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
                        
                        // FIX-REVIEW-24/09-#5/#12: remote name on dinh theo NGUON + giu
                        // extension dung (suffix truoc extension). Ban cu
                        // `photo.jpg__id42` lam hong nhan dien extension phia
                        // scanner/thumbnail (phan loai theo extension). Dinh dang
                        // moi: `photo__id42.jpg` — vua tach biet vua giu type.
                        val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        val dotIdx = encodedFileName.lastIndexOf('.')
                        val stableRemoteName = if (dotIdx > 0) {
                            encodedFileName.substring(0, dotIdx) + "__id" + id + encodedFileName.substring(dotIdx)
                        } else {
                            encodedFileName + "__id" + id
                        }
                        val targetFileNasPath = if (targetFolder.endsWith("/")) targetFolder + stableRemoteName else "$targetFolder/$stableRemoteName"

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
                            // F5+F6 + P2-muc3+muc4: skip phai chung minh noi dung
                            // KHONG DOI tren DUNG NAS/tai khoan.
                            // - Ung vien: fingerprint cung aHash+size+accountKey
                            //   (khong dung ban NAS A cho NAS B) + fallback theo
                            //   sourceKey khi khong aHash.
                            // - Moi ung vien: HEAD size -> 1MB loc nhanh ->
                            //   full-hash chot. Khop bat ky ban nao -> skip.
                            // - Khong aHash: tim theo sourceKey+account, so
                            //   contentHash da luu voi full-hash hien tai + verify
                            //   remote; khop -> skip (khong tao versioned moi).
                            val sourceKey = "media:$id"
                            var isSkipped = false
                            val fpDao = db.fingerprintDao()
                            val candidates: List<FileFingerprint> = if (fileHash != null) {
                                fpDao.findByHashSizeAccount(fileHash, fileSize, backupAccountKey)
                            } else {
                                fpDao.findBySource(sourceKey, backupAccountKey)
                            }
                            for (fp in candidates) {
                                if (fp.filePath.isEmpty()) continue
                                // P2-muc4 (khong aHash): so contentHash da luu voi
                                // full-hash hien tai cua nguon truoc — khac nhau
                                // nghia la nguon da doi tu luc luu -> khong skip
                                // bang ban cu (di tiep upload/versioned).
                                var localFull: String? = null
                                if (fileHash == null) {
                                    if (fp.contentHash.isEmpty()) continue
                                    localFull = try {
                                        applicationContext.contentResolver.openInputStream(fileUri)?.use { ins ->
                                            com.nas.naswebdav.utils.HashUtils.computeSha256OnPhone(ins)
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (_: Exception) { null }
                                    if (localFull == null || localFull != fp.contentHash) continue
                                }
                                val headers = try {
                                    webDavManager.headFileHeaders(fp.filePath)
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                                val remoteLen = headers?.get("Content-Length")?.toLongOrNull()
                                if (headers == null || remoteLen != fileSize) continue
                                val remotePartial = try {
                                    webDavManager.getSha256PhoneStream(fp.filePath)
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                                val localPartial = try {
                                    applicationContext.contentResolver.openInputStream(fileUri)?.use { ins ->
                                        com.nas.naswebdav.utils.HashUtils.computeSha256Partial(ins, 1048576L)
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                                if (remotePartial == null || localPartial == null || remotePartial != localPartial) continue
                                // Vong 2: full-hash remote vs local (tan dung
                                // localFull da tinh o nhanh khong-aHash).
                                val remoteFull = try {
                                    webDavManager.getFullSha256PhoneStream(fp.filePath, fileSize)
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                                if (localFull == null) {
                                    localFull = try {
                                        applicationContext.contentResolver.openInputStream(fileUri)?.use { ins ->
                                            com.nas.naswebdav.utils.HashUtils.computeSha256OnPhone(ins)
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (_: Exception) { null }
                                }
                                if (remoteFull != null && localFull != null && remoteFull == localFull) {
                                    isSkipped = true; break
                                }
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
                            // P2-2: khai bao som de nhanh 412 dung duoc.
                            val isCompressedEarly = com.nas.naswebdav.utils.HashUtils.shouldCompress(mimeType)
                            // P2-2: dich verify/fingerprint theo ban that su duoc
                            // dung (mac dinh = dich on dinh; doi sang versioned
                            // khi phat hien nguon da sua).
                            var effectiveTarget = targetFileNasPath
                            
                            // fileSize đã lấy từ cursor ở trên
                            var lastProgressTime = 0L
                            var lastSpeedCalcTime = System.currentTimeMillis()
                            var lastSpeedCalcBytes = 0L
                            var currentSpeedBps = 0L

                            // ── Upload: SMB (with retry) → WebDAV fallback (with retry) ──
                            var smbUploadOk = false
                            val smbRemotePath = targetFileNasPath.removePrefix(backupFolderBase)
                            // FIX-REVIEW-193369e-#5: SMB cung temp+commit khong
                            // overwrite (nhu WebDAV). Dich co san -> conflict,
                            // giu ban cu, fallback WebDAV xu ly tiep.
                            val smbTmpPath = smbRemotePath + "__upload" + id + "_" + System.currentTimeMillis()
                            if (smbEnabled && smbHost.isNotBlank()) {
                                // FIX-LARGE-FILE-SMB: retry ở caller — mỗi lượt mở InputStream mới.
                                // SmbManager.uploadFile throw exception khi fail → catch ở đây, fallback WebDAV.
                                for (smbAttempt in 1..3) {
                                    try {
                                        val smbInput = applicationContext.contentResolver.openInputStream(
                                            ContentUris.withAppendedId(mediaUri, id)
                                        ) ?: error("ContentResolver trả null cho $fileName")
                                        smbUploadOk = smbInput.use { input ->
                                            com.nas.naswebdav.SmbManager.uploadFileIfAbsent(
                                                host = smbHost,
                                                user = smbUser,
                                                pass = smbPass,
                                                share = smbShare,
                                                remotePath = smbTmpPath,
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
                                        if (smbUploadOk) {
                                            // Commit tmp ve dich (khong overwrite).
                                            // Dich co san -> false -> giu ban cu,
                                            // fallback WebDAV xu ly tiep.
                                            smbUploadOk = com.nas.naswebdav.SmbManager.moveNoOverwrite(
                                                host = smbHost,
                                                user = smbUser,
                                                pass = smbPass,
                                                share = smbShare,
                                                oldPath = smbTmpPath,
                                                newPath = smbRemotePath
                                            )
                                            if (!smbUploadOk) {
                                                android.util.Log.w("AutoBackup", "SMB dich da ton tai, giu ban cu: $fileName")
                                            }
                                            break
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
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
                                // FIX-REVIEW-24/09-#5: luan create-only temp+MOVE.
                                // Upload len duong tam duy nhat (__upload_<id>_<ts>),
                                // roi MOVE khong overwrite (Overwrite: F) ve dich.
                                // Dich da ton tai (nguoi dung/file khac) -> 412,
                                // giu nguyen ban co san, KHONG ghi de. 412 khong
                                // duoc bien thanh lay ETag moi roi ghi de.
                                val tmpRemoteName = stableRemoteName + "__upload" + id + "_" + System.currentTimeMillis()
                                val tmpNasPath = if (targetFolder.endsWith("/")) targetFolder + tmpRemoteName else "$targetFolder/$tmpRemoteName"
                                var lastWebDavException: Exception? = null
                                for (webDavAttempt in 1..3) {
                                    try {
                                        val rawStream = applicationContext.contentResolver.openInputStream(ContentUris.withAppendedId(mediaUri, id))
                                            ?: error("Không đọc được file $fileName (ContentResolver trả null)")
                                        rawStream.use { input2 ->
                                            val useCompression = com.nas.naswebdav.utils.HashUtils.shouldCompress(mimeType)
                                            val onUploadProgress: (Long, Long) -> Unit = { bytesWritten, totalBytes ->
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
                                            if (useCompression) {
                                                webDavManager.uploadCompressedStream(tmpNasPath, input2, fileSize, mimeType, onUploadProgress)
                                            } else {
                                                // FIX-REVIEW-24/09-#5: PUT len DUONG TAM (khong
                                                // If-Match len dich — tranh thay the file co san).
                                                webDavManager.uploadStreamWithProgress(tmpNasPath, input2, fileSize, mimeType, onUploadProgress, null)
                                            }
                                        }
                                        // MOVE khong overwrite ve dich chinh.
                                        // P2-2: dich da co (412) co 2 nghia: (a) file
                                        // khong doi — da backup dung, khong can upload;
                                        // (b) file da sua — can phien ban moi. Phan biet
                                        // bang verifyBackupContent: khop -> success
                                        // (ghi fingerprint); khac -> upload phien ban
                                        // moi (them __v<ts>), khong ghi de ban cu.
                                        // Temp thua duoc don o finally duoi.
                                        try {
                                            webDavManager.moveFileNoOverwrite(tmpNasPath, targetFileNasPath)
                                        } catch (e: Exception) {
                                            val isConflict = (e.message ?: "").contains("412")
                                            if (!isConflict || isCompressedEarly) {
                                                try { webDavManager.deleteFile(tmpNasPath, false) } catch (_: Exception) {}
                                                throw e
                                            }
                                            val destMatches = try {
                                                webDavManager.verifyBackupContent(targetFileNasPath, fileSize) {
                                                    applicationContext.contentResolver.openInputStream(
                                                        ContentUris.withAppendedId(mediaUri, id))
                                                }
                                            } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                                            catch (_: Exception) { false }
                                            try { webDavManager.deleteFile(tmpNasPath, false) } catch (_: Exception) {}
                                            if (destMatches) {
                                                SystemLogger.log("INFO", "AutoBackup",
                                                    "Đích $fileName đã đúng nội dung — bỏ qua upload, không tạo bản trùng.")
                                                break // coi nhu backup xong
                                            }
                                            // File da sua -> phien ban moi, khong de ban cu.
                                            val dotIdx2 = stableRemoteName.lastIndexOf('.')
                                            val ts = System.currentTimeMillis()
                                            val versionedName = if (dotIdx2 > 0) {
                                                stableRemoteName.substring(0, dotIdx2) + "__v" + ts + stableRemoteName.substring(dotIdx2)
                                            } else {
                                                stableRemoteName + "__v" + ts
                                            }
                                            var versionedPath = if (targetFolder.endsWith("/")) targetFolder + versionedName else "$targetFolder/$versionedName"
                                            SystemLogger.log("INFO", "AutoBackup",
                                                "Nguồn $fileName đã đổi so với bản backup — lưu phiên bản mới thay vì ghi đè.")
                                            // F4: upload versioned CREATE-ONLY (If-None-Match:
                                            // *): dich da ton tai -> 412, khong ghi de.
                                            // Ban cu PUT khong dieu kien -> co the de
                                            // ban co san. 412 cuc hiem -> doi ts.
                                            var versionedOk = false
                                            for (vAttempt in 1..2) {
                                                try {
                                                    val rawV = applicationContext.contentResolver.openInputStream(ContentUris.withAppendedId(mediaUri, id))
                                                        ?: error("Không đọc được file $fileName")
                                                    rawV.use { inv ->
                                                        webDavManager.uploadStreamIfAbsent(versionedPath, inv, fileSize, mimeType, { _, _ -> })
                                                    }
                                                    versionedOk = true
                                                    break
                                                } catch (ve: Exception) {
                                                    if ((ve.message ?: "").contains("412") && vAttempt < 2) {
                                                        val ts2 = System.currentTimeMillis()
                                                        val dot2 = versionedName.lastIndexOf('.')
                                                        val altName = if (dot2 > 0) {
                                                            versionedName.substring(0, dot2) + "_$ts2" + versionedName.substring(dot2)
                                                        } else {
                                                            versionedName + "_$ts2"
                                                        }
                                                        versionedPath = if (targetFolder.endsWith("/")) targetFolder + altName else "$targetFolder/$altName"
                                                        kotlinx.coroutines.delay(5)
                                                        continue
                                                    } else throw ve
                                                }
                                            }
                                            if (!versionedOk) throw IllegalStateException("Không lưu được phiên bản mới cho $fileName")
                                            effectiveTarget = versionedPath
                                            break // versioned xong -> qua buoc verify/fingerprint
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
                            // P1-1: verify TOAN BO noi dung + phien ban truoc khi
                            // xoa nguon — fail-closed, khong fallback metadata.
                            // - File thuong: WebDavManager.verifyBackupContent
                            //   (HEAD size+strong ETag -> GET If-Match -> stream
                            //   hash-compare voi nguon -> HEAD recheck ETag).
                            // - File NEN (gzip tren duong truyen): remote khac byte
                            //   voi local nen KHONG the hash-compare; chi verify
                            //   ton tai + ETag va KHONG xoa nguon (giu de user tu
                            //   quyet dinh), ghi log CANH BAO ro rang.
                            var uploadVerified = false
                            val isCompressed = com.nas.naswebdav.utils.HashUtils.shouldCompress(mimeType)
                            // P2-2: verify/fingerprint theo dich that su duoc dung
                            // (effectiveTarget — versioned khi nguon da sua).
                            val verifyTarget = effectiveTarget
                            if (isCompressed) {
                                val headers = try { webDavManager.headFileHeaders(verifyTarget) } catch (_: Exception) { null }
                                val remoteLen = headers?.get("Content-Length")?.toLongOrNull()
                                val remoteETag = headers?.get("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                                if (remoteLen != null && remoteLen > 0 && remoteETag != null) {
                                    SystemLogger.log("WARNING", "AutoBackup",
                                        "File nen ($fileName) chi xac minh ton tai+ETag, KHONG du bang chung noi dung — giu nguon, khong xoa.")
                                } else {
                                    SystemLogger.log("WARNING", "AutoBackup",
                                        "File nen ($fileName) chua xac minh duoc — giu nguon, khong xoa.")
                                }
                                uploadVerified = false // Bao gio cung giu nguon voi file nen.
                            } else {
                                uploadVerified = try {
                                    webDavManager.verifyBackupContent(verifyTarget, fileSize) {
                                        applicationContext.contentResolver.openInputStream(fileUri)
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { false }
                                if (!uploadVerified) {
                                    SystemLogger.log("WARNING", "AutoBackup",
                                        "Noi dung/phien ban khong khop hoac khong du bang chung ($fileName) — giu nguon, khong xoa.")
                                }
                            }
                            // P2-muc3+muc4: luu kem accountKey + sourceKey + contentHash.
                            // contentHash = full SHA-256 nguon sau verify (tinh 1 lan
                            // o day de vong skip sau so truc tiep, ke ca khi khong
                            // aHash). Ghi ca khi fileHash null (hash="").
                            if (uploadVerified) {
                                val verifiedContentHash = try {
                                    applicationContext.contentResolver.openInputStream(fileUri)?.use { ins ->
                                        com.nas.naswebdav.utils.HashUtils.computeSha256OnPhone(ins)
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                                db.fingerprintDao().insertFingerprint(FileFingerprint(
                                    filePath = verifyTarget,
                                    hash = fileHash ?: "",
                                    fileName = fileName,
                                    fileSize = fileSize,
                                    accountKey = backupAccountKey,
                                    sourceKey = sourceKey,
                                    contentHash = verifiedContentHash ?: ""
                                ))
                            }
                            if (deleteAfterBackup && uploadVerified) applicationContext.contentResolver.delete(ContentUris.withAppendedId(mediaUri, id), null, null)
                            if (!uploadVerified && !smbUploadOk) {
                                SystemLogger.log("WARNING", "AutoBackup",
                                    "Upload chua xac minh duoc noi dung/phien ban ($fileName) — giu nguon, khong xoa.")
                            }
                            backupCount++
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            // FIX: partial access trên Android 14+ khiến openInputStream() hoặc
                            // ImageFingerprint.computeFromUri() throw SecurityException /
                            // FileNotFoundException với "has no access" message.
                            // Skip file thay vì count as failed — tránh log spam 161+ lỗi.
                            val isAccessDenied = e is SecurityException
                                || (e.message ?: "").contains("has no access")
                                || (e.message ?: "").contains("Permission denied")
                            if (isAccessDenied) {
                                skippedCount++ // skip, NOT fail
                            } else {
                                failedCount++
                                if (e !is java.io.FileNotFoundException && !(e.message ?: "").contains("Missing file")) {
                                    if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                                        SmartNetworkManager.invalidateCache()
                                        SystemLogger.log("INFO", "AutoBackup", "Mạng chập chờn, tải file $fileName lỗi: ${e.message}")
                                    } else {
                                        SystemLogger.log("WARNING", "AutoBackup", "Lỗi tải xuống tập tin $fileName: ${e.message}")
                                    }
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
                // P0-4: user cancel → thất bại, KHÔNG retry (user đã chủ động dừng)
                SystemLogger.log("WARNING", "AutoBackup", "Worker bị dừng bởi user — kết thúc: ${e.message}")
                return@withContext Result.failure()
            }
            val isTransient = e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException
            SystemLogger.log(
                if (isTransient) "WARNING" else "ERROR",
                "AutoBackup",
                "Lỗi luồng xử lý Đồng bộ tự động (AutoBackup): ${e.message}"
            )
            return@withContext if (isTransient && runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            setThumbnailActivity("autobackup", false)
            if (wakeLock.isHeld) wakeLock.release()
            try { androidx.core.app.NotificationManagerCompat.from(applicationContext).cancel(9903) } catch (_: Exception) {}
        }
    }
}
