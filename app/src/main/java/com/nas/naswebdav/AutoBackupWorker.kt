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
    override suspend fun doWork(): Result {
        val auth = withContext(Dispatchers.IO) {
            val user = SecurePrefsHelper.getUser(applicationContext)
            val pass = SecurePrefsHelper.getPass(applicationContext)
            val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
                .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
            if (user != SecurePrefsHelper.getUser(applicationContext) || pass != SecurePrefsHelper.getPass(applicationContext)) null
            else WebDavManager.AuthState(baseUrl=url,user=user,pass=pass)
        } ?: return Result.retry()
        if (auth.baseUrl.isBlank() || auth.user.isBlank()) return Result.failure()
        return performBackup(auth)
    }

    private suspend fun performBackup(backupAuth: WebDavManager.AuthState): Result =
        withContext(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(backupAuth)) {
        SystemLogger.log("INFO", "AutoBackup", "Bắt đầu tiến trình đồng bộ nền (Worker khởi động).")
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
        try {
        // FIX D2b: Đã trong withContext(IO) → gọi suspend fun trực tiếp, không cần runBlocking
        val baseUrl = backupAuth.baseUrl
        // P2-muc3+muc4: accountKey + sourceKey on dinh cho fingerprint scope.
        val backupUser = backupAuth.user
        val backupAccountKey = runCatching {
            val u = java.net.URL(baseUrl)
            val host = u.host ?: baseUrl
            val port = u.port.takeIf { it > 0 } ?: u.defaultPort
            val root = u.path.trimEnd('/').ifEmpty { "/" }
            "$backupUser@$host:$port$root"
        }.getOrDefault("$backupUser@$baseUrl")
        val settingsPrefs = SecurePrefsHelper.getSettingsPrefs(applicationContext)
        val deleteAfterBackup = settingsPrefs.getBoolean("delete_after_backup", false)
        val webDavManager = WebDavManager
        if (runAttemptCount >= 3) {
            SystemLogger.log("WARNING", "AutoBackup", "Đã ghi nhận $runAttemptCount lần thực thi thất bại.")
            return@withContext Result.failure()
        }

        val db = NasApplication.instance.database
        webDavManager.requireBackupProtocol()
            val backupFolderBase = if (baseUrl.endsWith("/")) "${baseUrl}AutoBackup/" else "$baseUrl/AutoBackup/"
            try { webDavManager.createFolder(backupFolderBase) } catch (e: Exception) { SystemLogger.log("WARNING", "AutoBackup", "Tạo thư mục backup thất bại: ${e.message}") }
            
            // Cache để lưu các thư mục đã tạo nhằm tránh gọi MKCOL liên tục
            val createdFolders = hashSetOf<String>()
            createdFolders.add(backupFolderBase)

            // Hàm đệ quy tạo thư mục cha trên NAS
            suspend fun ensureFolderExists(nasRelativePath: String): String {
                val segments = nasRelativePath.split("/").filter { it.isNotEmpty() }
                var currentPath = backupFolderBase
                for (segment in segments) {
                    currentPath += java.net.URLEncoder.encode(segment,"UTF-8").replace("+","%20") + "/"
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
            var deletedCount = 0
            var deletePermissionWarned = false
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
                        if (SecurePrefsHelper.getUser(applicationContext) != backupAuth.user ||
                            SecurePrefsHelper.getPass(applicationContext) != backupAuth.pass ||
                            (SecurePrefsHelper.getUrlList(applicationContext) + SecurePrefsHelper.getTailscaleBaseUrl(applicationContext))
                                .none { it.trimEnd('/') == backupAuth.baseUrl.trimEnd('/') }) {
                            throw CancellationException("Backup account changed")
                        }
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
                        var targetFileNasPath = if (targetFolder.endsWith("/")) targetFolder + stableRemoteName else "$targetFolder/$stableRemoteName"

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
                            val sourceProof = backupSourceDigest(applicationContext, fileUri, fileSize)
                            val sourceKey = fileUri.toString()
                            var fileHash: String? = null
                            val plan = webDavManager.planBackupTarget(targetFileNasPath,fileSize,sourceProof.sha256,backupAccountKey)
                            targetFileNasPath = plan.path
                            if (!backupSourceStillCurrent(applicationContext,fileUri,sourceProof)) {
                                throw java.io.IOException("Nguồn thay đổi trong lúc kiểm tra backup")
                            }
                            val isSkipped = plan.skip

                            if (isSkipped) {
                                if (deleteAfterBackup) {
                                    if (deleteVerifiedBackupSource(applicationContext, fileUri, dataPath, sourceProof)) {
                                        deletedCount++
                                    } else if (!deletePermissionWarned && Build.VERSION.SDK_INT >= 27) {
                                        deletePermissionWarned = true
                                        SystemLogger.log("WARNING", "AutoBackup", "Không thể xóa tệp đã có trên NAS ($fileName) — hãy cấp quyền Quản lý tất cả tệp trong Cài đặt Android.")
                                    }
                                }
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
                            
                            val mimeType = try { applicationContext.contentResolver.getType(ContentUris.withAppendedId(mediaUri, id)) ?: "application/octet-stream" } catch (e: Exception) { SystemLogger.log("WARNING", "AutoBackup", "getType contentResolver fallback: ${e.message}"); "application/octet-stream" }
                            // P2-2: khai bao som de nhanh 412 dung duoc.
                            // P2-2: dich verify/fingerprint theo ban that su duoc
                            // dung (mac dinh = dich on dinh; doi sang versioned
                            // khi phat hien nguon da sua).
                            var effectiveTarget = targetFileNasPath
                            
                            // fileSize đã lấy từ cursor ở trên
                            var lastProgressTime = 0L
                            var lastSpeedCalcTime = System.currentTimeMillis()
                            var lastSpeedCalcBytes = 0L
                            var currentSpeedBps = 0L

                            // Use the same authenticated namespace for staging and verification.
                            run {
                                if (isStopped) throw kotlinx.coroutines.CancellationException("User cancelled upload")
                                // FIX-REVIEW-24/09-#5: luan create-only temp+MOVE.
                                // Upload len duong tam duy nhat (__upload_<id>_<ts>),
                                // roi MOVE khong overwrite (Overwrite: F) ve dich.
                                // Dich da ton tai (nguoi dung/file khac) -> 412,
                                // giu nguyen ban co san, KHONG ghi de. 412 khong
                                // duoc bien thanh lay ETag moi roi ghi de.
                                val tmpRemoteName = ".nas-backup-" + java.util.UUID.randomUUID() + ".tmp"
                                val tmpNasPath = if (targetFolder.endsWith("/")) targetFolder + tmpRemoteName else "$targetFolder/$tmpRemoteName"
                                var lastWebDavException: Exception? = null
                                try {
                                for (webDavAttempt in 1..3) {
                                    try {
                                        val rawStream = applicationContext.contentResolver.openInputStream(ContentUris.withAppendedId(mediaUri, id))
                                            ?: error("Không đọc được file $fileName (ContentResolver trả null)")
                                        rawStream.use { input2 ->
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
                                                        if (Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                            androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(9903, notificationBuilder.build())
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                            webDavManager.uploadStreamWithProgress(tmpNasPath,input2,fileSize,mimeType,onUploadProgress,null)
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
                                            if (!isConflict) {
                                                try { webDavManager.deleteFile(tmpNasPath, false) } catch (e: Exception) { SystemLogger.log("WARNING", "AutoBackup", "deleteFile tmpNasPath SMB: ${e.message}") }
                                                throw e
                                            }
                                            val destination = webDavManager.inspectBackupDestination(targetFileNasPath, fileSize, sourceProof.sha256)
                                            if (destination == BackupContentState.MATCH) {
                                                SystemLogger.log("INFO", "AutoBackup", "Đích đã đúng nội dung — không tạo bản trùng.")
                                                break
                                            }
                                            if (destination != BackupContentState.DIFFERENT) {
                                                throw java.io.IOException("Đích chưa xác minh được; không tạo thêm phiên bản")
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
                                            // Reuse the complete staged bytes; never publish a partial version.
                                            var versionedOk = false
                                            for (vAttempt in 1..2) {
                                                try {
                                                    webDavManager.moveFileNoOverwrite(tmpNasPath, versionedPath)
                                                    versionedOk = true
                                                    break
                                                } catch (ve: Exception) {
                                                    if ((ve.message ?: "").contains("412")) {
                                                        val conflict = webDavManager.inspectBackupDestination(versionedPath,fileSize,sourceProof.sha256)
                                                        if (conflict == BackupContentState.MATCH) { versionedOk = true; break }
                                                        if (conflict != BackupContentState.DIFFERENT || vAttempt >= 2) {
                                                            throw java.io.IOException("Không xác minh được xung đột phiên bản; không tạo thêm bản sao",ve)
                                                        }
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
                                } finally {
                                    withContext(NonCancellable) {
                                        try { webDavManager.cleanupBackupTemporary(tmpNasPath,backupAuth) }
                                        catch (_: Exception) { SystemLogger.log("WARNING","AutoBackup","Chưa dọn được file tạm của phiên backup") }
                                    }
                                }
                            }

                            // FIX-THUMB-DELEGATION: phone MUST NOT decode video/images for thumbnails.
                            // NAS daemon handles ALL thumbnail generation (idle 24/7 + 5-min rescan +
                            // on-demand /api/thumb). Phone only uploads the file and lets NAS pick it up.
                            // Store the digest actually verified against the native remote version.
                            val verifyTarget = effectiveTarget
                            val uploadVerified = backupSourceStillCurrent(applicationContext, fileUri, sourceProof) &&
                                webDavManager.inspectBackupDestination(verifyTarget,fileSize,sourceProof.sha256) == BackupContentState.MATCH
                            if (!uploadVerified) {
                                SystemLogger.log("WARNING", "AutoBackup", "Bản tải lên chưa xác minh đầy đủ; giữ nguồn: $fileName")
                                failedCount++
                                continue
                            }
                            // SEPARATION-OF-CONCERNS: Không decode Bitmap tính aHash trên CPU điện thoại khi backup.
                            // NAS Rust NEON (/api/fingerprint/compute) tự tính & lưu aHash; dedup backup đã dùng contentHash (SHA-256).
                            fileHash = ""
                            db.fingerprintDao().insertFingerprint(FileFingerprint(
                                filePath=verifyTarget, hash=fileHash, fileName=fileName,
                                fileSize=fileSize, accountKey=backupAccountKey, sourceKey=sourceKey,
                                contentHash=sourceProof.sha256
                            ))
                            if (deleteAfterBackup) {
                                if (deleteVerifiedBackupSource(applicationContext, fileUri, dataPath, sourceProof)) {
                                    deletedCount++
                                } else if (!deletePermissionWarned && Build.VERSION.SDK_INT >= 27) {
                                    deletePermissionWarned = true
                                    SystemLogger.log("WARNING", "AutoBackup", "Đã xác minh backup nhưng chưa xóa được nguồn ($fileName) — hãy cấp quyền Quản lý tất cả tệp trong Cài đặt Android.")
                                }
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
                        // Nhường luồng coroutine mà không ngủ 100ms lãng phí sau mỗi tệp
                        kotlinx.coroutines.yield()
                        // CC-005 P1: checkpoint mỗi 25 file — worker bị kill thì lần
                        // sau resume từ checkpoint (qua checkpointDao), không quét lại từ đầu.
                        if (processedFilesCount % 25 == 0) {
                            runCatching {
                                db.checkpointDao().saveCheckpoint(
                                    ScanCheckpoint(
                                        workerName = "AutoBackup",
                                        lastProcessedFolder = fileName,
                                        scannedCount = processedFilesCount,
                                        foundCount = backupCount
                                    )
                                )
                            }.onFailure { e -> SystemLogger.log("WARNING", "AutoBackup", "saveCheckpoint runCatching: ${e.message}") }
                        }
                    }
                }
            }
            val deletedSuffix = if (deletedCount > 0) ", Đã giải phóng trên điện thoại: $deletedCount tệp" else ""
            val logMessage = "Đồng bộ khép kín: Thành công $backupCount tệp, Bỏ qua $skippedCount tệp tập tin trùng lặp, Thất bại: $failedCount tệp$deletedSuffix."
            if (backupCount > 0 || failedCount > 0) {
                SystemLogger.log(if (failedCount > 0) "WARNING" else "SUCCESS", "AutoBackup", logMessage)
            } else {
                SystemLogger.log("INFO", "AutoBackup", logMessage)
            }
            SystemLogger.log("INFO", "AutoBackup", "Hoàn tất đồng bộ nền (success=$backupCount skipped=$skippedCount failed=$failedCount)")
            
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
            // CC-005 P1: xong -> xóa checkpoint (lần sau quét mới từ đầu).
            runCatching { db.checkpointDao().clearCheckpoint("AutoBackup") }.onFailure { e -> SystemLogger.log("WARNING", "AutoBackup", "clearCheckpoint runCatching: ${e.message}") }

            SystemLogger.log("INFO", "AutoBackup", "Hoàn tất đồng bộ nền (trả Result.success)")
            return@withContext Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStopped) {
                // P0-4: hệ thống dừng worker → retry theo backoff, KHÔNG trả failure
                // (system stop ≠ fatal error, WorkManager sẽ reschedule sau backoff)
                SystemLogger.log("WARNING", "AutoBackup", "Hệ thống dừng worker — sẽ retry: ${e.message}")
                SystemLogger.log("INFO", "AutoBackup", "Hoàn tất đồng bộ nền (trả Result.retry do hệ thống dừng)")
                return@withContext Result.retry()
            }
            val isTransient = e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException
            SystemLogger.log(
                if (isTransient) "WARNING" else "ERROR",
                "AutoBackup",
                "Lỗi luồng xử lý Đồng bộ tự động (AutoBackup): ${e.message}"
            )
            SystemLogger.log("INFO", "AutoBackup", "Hoàn tất đồng bộ nền (trả ${if (isTransient && runAttemptCount < 3) "Result.retry()" else "Result.failure()"} do lỗi: ${e.message})")
            return@withContext if (isTransient && runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            setThumbnailActivity("autobackup", false)
            if (wakeLock.isHeld) wakeLock.release()
            // Thay NotificationManagerCompat.cancel(9903) bằng notification hoàn tất
            // (ongoing=false, autoCancel=true) để user biết kết quả.
            try {
                val doneBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, "auto_backup_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("Sao lưu hoàn tất")
                    .setContentText("Đồng bộ đã kết thúc.")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                if (Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(9903, doneBuilder.build())
                }
            } catch (e: Exception) { SystemLogger.log("WARNING", "AutoBackup", "final done notification build/notify: ${e.message}") }
        }
    }
}
