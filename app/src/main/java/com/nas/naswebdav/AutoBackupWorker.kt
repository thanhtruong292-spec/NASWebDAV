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
import com.nas.naswebdav.utils.PreferencesRepository
import com.nas.naswebdav.utils.StorageUtils
import com.nas.naswebdav.utils.SystemLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object AutoBackupState {
    val isPaused = MutableStateFlow(false)
    val activeMode = MutableStateFlow("both")
    val deleteAfter = MutableStateFlow(false)
    val showResultDialog = MutableStateFlow(false)
    val resultTotal = MutableStateFlow(0)
    val resultSuccess = MutableStateFlow(0)
    val resultSkipped = MutableStateFlow(0)
    val resultFailed = MutableStateFlow(0)
}

internal data class PendingTarBackupItem(
    val uri: Uri,
    val id: Long,
    val mediaUri: Uri,
    val fileName: String,
    val dataPath: String,
    val targetFileNasPath: String,
    val targetFolder: String,
    val stableRemoteName: String,
    val parentRelativePath: String,
    val fileSize: Long,
    val mtimeSec: Long,
    val sourceProof: BackupSourceDigest,
    val sourceKey: String
)

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
        val prefsRepo = PreferencesRepository.get(applicationContext)
        val initialDeleteAfter = if (inputData.keyValueMap.containsKey("delete_after_backup")) {
            inputData.getBoolean("delete_after_backup", false)
        } else {
            prefsRepo.isDeleteAfterBackup() || settingsPrefs.getBoolean("delete_after_backup", false)
        }
        AutoBackupState.deleteAfter.value = initialDeleteAfter
        val rawBackupMode = inputData.getString("backup_mode") ?: prefsRepo.getBackupMode()
        val initialBackupMode = if (rawBackupMode == "image" || rawBackupMode == "video" || rawBackupMode == "both") rawBackupMode else "both"
        AutoBackupState.activeMode.value = initialBackupMode
        fun isPipelineAllowed(isImage: Boolean): Boolean {
            val m = AutoBackupState.activeMode.value
            return if (isImage) (m == "both" || m == "image") else (m == "both" || m == "video")
        }
        val deviceId = inputData.getString("device_id")?.takeIf { it.isNotBlank() } ?: prefsRepo.getDeviceId(applicationContext)
        val deviceName = inputData.getString("device_name")?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        val modeLabel = when (initialBackupMode) { "image" -> "Chỉ ảnh"; "video" -> "Chỉ video"; else -> "Ảnh & Video" }
        SystemLogger.log(
            "INFO",
            "AutoBackup",
            "Khởi động sao lưu nền [Máy: $deviceName ($deviceId), Nội dung: $modeLabel, Chế độ: ${if (initialDeleteAfter) "Sao lưu & Xóa nguồn (Move)" else "Chỉ sao lưu (Copy)"}, Full Access: $hasAllFilesAccess]"
        )
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
            val createdFolders = java.util.Collections.synchronizedSet(hashSetOf<String>())
            createdFolders.add(backupFolderBase)
            val folderMutex = Mutex()
            val dbMutex = Mutex()
            val progressMutex = Mutex()

            // Hàm đệ quy tạo thư mục cha trên NAS
            suspend fun ensureFolderExists(nasRelativePath: String): String {
                val segments = nasRelativePath.split("/").filter { it.isNotEmpty() }
                var currentPath = backupFolderBase
                for (segment in segments) {
                    currentPath += java.net.URLEncoder.encode(segment,"UTF-8").replace("+","%20") + "/"
                    folderMutex.withLock {
                        if (!createdFolders.contains(currentPath)) {
                            try {
                                webDavManager.createFolder(currentPath)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                                // Ignored (thư mục có thể đã tồn tại)
                            }
                            createdFolders.add(currentPath)
                        }
                    }
                }
                return currentPath
            }

            val backupCount = AtomicInteger(0)
            val skippedCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val deletedCount = AtomicInteger(0)
            val isDeletePermissionWarned = AtomicBoolean(false)
            val storageFreedBytes = AtomicLong(0L)
            val nasSlotGranted = AtomicBoolean(true)
            val nasQueuePosition = AtomicInteger(0)
            val nasRecommendedPaceMs = AtomicLong(0L)
            val lastHeartbeatMs = AtomicLong(0L)
            val apiBaseUrl = baseUrl.toApiBaseUrl()

            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            )

            var totalImageFiles = 0
            var totalVideoFiles = 0
            var initialTotalImageBytes = 0L
            var initialTotalVideoBytes = 0L
            try {
                applicationContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                    totalImageFiles = cursor.count
                    val szIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (szIdx >= 0) {
                        while (cursor.moveToNext()) {
                            initialTotalImageBytes += cursor.getLong(szIdx).coerceAtLeast(0L)
                        }
                    }
                }
            } catch(e: Exception) {
                android.util.Log.w("AutoBackup", "Image media query failed: ${e.message}")
            }
            try {
                applicationContext.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                    totalVideoFiles = cursor.count
                    val szIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (szIdx >= 0) {
                        while (cursor.moveToNext()) {
                            initialTotalVideoBytes += cursor.getLong(szIdx).coerceAtLeast(0L)
                        }
                    }
                }
            } catch(e: Exception) {
                android.util.Log.w("AutoBackup", "Video media query failed: ${e.message}")
            }
            val totalFilesToProcess = (if (isPipelineAllowed(true)) totalImageFiles else 0) + (if (isPipelineAllowed(false)) totalVideoFiles else 0)
            val startTime = System.currentTimeMillis()

            val imageProcessedCount = AtomicInteger(0)
            val imageTotalCount = AtomicInteger(totalImageFiles)
            val imageSpeedBps = AtomicLong(0L)
            val imageCurrentFile = AtomicReference<String>("Sẵn sàng...")
            val imageProgress = AtomicReference<Float>(0f)
            val imageBytesCompleted = AtomicLong(0L)
            val imageBytesInFlight = AtomicLong(0L)
            val imageTotalBytes = AtomicLong(initialTotalImageBytes)

            val videoProcessedCount = AtomicInteger(0)
            val videoTotalCount = AtomicInteger(totalVideoFiles)
            val videoSpeedBps = AtomicLong(0L)
            val videoCurrentFile = AtomicReference<String>("Sẵn sàng...")
            val videoProgress = AtomicReference<Float>(0f)
            val videoFileBytesWritten = AtomicLong(0L)
            val videoFileBytesTotal = AtomicLong(0L)
            val videoFileProgress = AtomicReference<Float>(0f)
            val videoBytesCompleted = AtomicLong(0L)
            val videoTotalBytes = AtomicLong(initialTotalVideoBytes)

            val shaMutexMap = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
            val queuedDigests = java.util.concurrent.ConcurrentHashMap<String, String>()

            suspend fun sendNasSessionHeartbeat(status: String, force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastHeartbeatMs.get() < 3000L) return
                lastHeartbeatMs.set(now)
                runCatching {
                    val curMode = AutoBackupState.activeMode.value
                    val curRunImage = curMode == "both" || curMode == "image"
                    val curRunVideo = curMode == "both" || curMode == "video"
                    val (phoneFree, phoneTotal) = StorageUtils.getPhoneStorageBytes()
                    val activeImgTotal = if (curRunImage) imageTotalCount.get() else 0
                    val activeVidTotal = if (curRunVideo) videoTotalCount.get() else 0
                    val activeImgProc = if (curRunImage) minOf(imageProcessedCount.get(), activeImgTotal) else 0
                    val activeVidProc = if (curRunVideo) minOf(videoProcessedCount.get(), activeVidTotal) else 0
                    val payload = org.json.JSONObject().apply {
                        put("device_id", deviceId)
                        put("device_name", deviceName)
                        put("backup_mode", curMode)
                        put("status", status)
                        put("processed", minOf(activeImgProc + activeVidProc, activeImgTotal + activeVidTotal))
                        put("total", activeImgTotal + activeVidTotal)
                        put("image_processed", activeImgProc)
                        put("image_total", activeImgTotal)
                        put("video_processed", activeVidProc)
                        put("video_total", activeVidTotal)
                        put("speed_bps", (if (curRunImage) imageSpeedBps.get() else 0L) + (if (curRunVideo) videoSpeedBps.get() else 0L))
                        put("current_file", (if (curRunImage) imageCurrentFile.get() else "").ifEmpty { if (curRunVideo) videoCurrentFile.get() else "" }.take(120))
                        put("freed_bytes", storageFreedBytes.get())
                        put("phone_free_bytes", phoneFree)
                        put("phone_total_bytes", phoneTotal)
                    }
                    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val req = okhttp3.Request.Builder()
                        .url("$apiBaseUrl/api/backup/session/heartbeat")
                        .post(body)
                        .let(webDavManager::tagCurrentAuth)
                        .build()
                    NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val json = org.json.JSONObject(resp.body?.string() ?: "{}")
                            nasSlotGranted.set(json.optBoolean("slot_granted", true))
                            nasQueuePosition.set(json.optInt("queue_position", 0))
                            nasRecommendedPaceMs.set(json.optLong("recommended_pace_ms", 0L).coerceIn(0L, 3000L))
                        }
                    }
                }
            }

            fun sendNasSessionFinish() {
                runCatching {
                    val body = org.json.JSONObject().apply { put("device_id", deviceId) }
                        .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val req = okhttp3.Request.Builder()
                        .url("$apiBaseUrl/api/backup/session/finish")
                        .post(body)
                        .let(webDavManager::tagCurrentAuth)
                        .build()
                    NasApplication.instance.fastApiClient.newCall(req).execute().close()
                }
            }

            var lastProgressPushTime = 0L

            suspend fun pushProgress(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastProgressPushTime < 250) return
                progressMutex.withLock {
                    if (!force && now - lastProgressPushTime < 250) return
                    lastProgressPushTime = now

                    val curMode = AutoBackupState.activeMode.value
                    val curRunImage = curMode == "both" || curMode == "image"
                    val curRunVideo = curMode == "both" || curMode == "video"
                    if (!curRunImage) imageSpeedBps.set(0L)
                    if (!curRunVideo) {
                        videoSpeedBps.set(0L)
                        videoFileBytesWritten.set(0L)
                        videoFileBytesTotal.set(0L)
                        videoFileProgress.set(0f)
                    }
                    val activeImgTotal = if (curRunImage) imageTotalCount.get() else 0
                    val activeVidTotal = if (curRunVideo) videoTotalCount.get() else 0
                    val imgProc = if (curRunImage) minOf(imageProcessedCount.get(), activeImgTotal) else 0
                    val vidProc = if (curRunVideo) minOf(videoProcessedCount.get(), activeVidTotal) else 0
                    val totalAll = activeImgTotal + activeVidTotal
                    val totalProc = minOf(imgProc + vidProc, totalAll)
                    val overallPercent = if (totalAll > 0) minOf(1f, totalProc.toFloat() / totalAll) else 0f
                    val imgSpd = if (curRunImage) imageSpeedBps.get() else 0L
                    val vidSpd = if (curRunVideo) videoSpeedBps.get() else 0L
                    val combinedSpeed = imgSpd + vidSpd

                    val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.5)
                    val imgTotBytes = if (curRunImage) imageTotalBytes.get() else 0L
                    val imgUpBytes = if (curRunImage) minOf(imageBytesCompleted.get() + imageBytesInFlight.get(), imgTotBytes) else 0L
                    val imgRemBytes = (imgTotBytes - imgUpBytes).coerceAtLeast(0L)
                    val imgEffRate = if (imgSpd > 0L) imgSpd.toDouble() else if (imgUpBytes > 0L) (imgUpBytes / elapsedSec) else 0.0
                    val imgEtaSec = if (imgRemBytes <= 0L) 0L else if (imgEffRate > 1.0) (imgRemBytes / imgEffRate).toLong().coerceAtLeast(1L) else -1L

                    val vidTotBytes = if (curRunVideo) videoTotalBytes.get() else 0L
                    val vidUpBytes = if (curRunVideo) minOf(videoBytesCompleted.get() + videoFileBytesWritten.get(), vidTotBytes) else 0L
                    val vidRemBytes = (vidTotBytes - vidUpBytes).coerceAtLeast(0L)
                    val vidEffRate = if (vidSpd > 0L) vidSpd.toDouble() else if (vidUpBytes > 0L) (vidUpBytes / elapsedSec) else 0.0
                    val vidEtaSec = if (vidRemBytes <= 0L) 0L else if (vidEffRate > 1.0) (vidRemBytes / vidEffRate).toLong().coerceAtLeast(1L) else -1L

                    val totalUpBytes = imgUpBytes + vidUpBytes
                    val totalPendBytes = imgTotBytes + vidTotBytes
                    val totalRemBytes = (totalPendBytes - totalUpBytes).coerceAtLeast(0L)
                    val totalEffRate = if (combinedSpeed > 0L) combinedSpeed.toDouble() else if (totalUpBytes > 0L) (totalUpBytes / elapsedSec) else 0.0
                    val totalEtaSec = if (totalRemBytes <= 0L) 0L else if (totalEffRate > 1.0) (totalRemBytes / totalEffRate).toLong().coerceAtLeast(1L) else -1L

                    val imgFile = imageCurrentFile.get().ifEmpty { "Đang đợi..." }
                    val vidFile = videoCurrentFile.get().ifEmpty { "Đang đợi..." }
                    val qPos = nasQueuePosition.get()
                    val displayFileName = when {
                        !nasSlotGranted.get() && qPos > 0 -> "Đang xếp hàng trên NAS (vị trí #$qPos)..."
                        curRunImage && curRunVideo && activeImgTotal > 0 && activeVidTotal > 0 -> "Ảnh: $imgFile | Video: $vidFile"
                        curRunImage -> imgFile
                        else -> vidFile
                    }

                    setProgressAsync(workDataOf(
                        "fileName" to safeWorkerText(displayFileName, 180),
                        "sourcePath" to "Thiết bị máy trạm",
                        "destPath" to safeWorkerText(backupFolderBase, 220),
                        "progress" to overallPercent,
                        "processedCount" to totalProc,
                        "totalCount" to totalAll,
                        "elapsedTime" to (now - startTime),
                        "uploadSpeedBps" to combinedSpeed,
                        "bytesWritten" to totalUpBytes,
                        "bytesTotal" to totalPendBytes,
                        "bytesRemaining" to totalRemBytes,
                        "etaSeconds" to totalEtaSec,

                        // Chế độ, thiết bị, dung lượng đã giải phóng & điều phối đa máy
                        "backupMode" to curMode,
                        "deviceId" to safeWorkerText(deviceId, 80),
                        "deviceName" to safeWorkerText(deviceName, 80),
                        "storageFreedBytes" to storageFreedBytes.get(),
                        "nasQueuePosition" to qPos,

                        // Thống kê riêng cho Ảnh (Photo Pipeline)
                        "imageFileName" to safeWorkerText(if (curRunImage) imgFile else "Đã tắt", 180),
                        "imageProgress" to (if (curRunImage) imageProgress.get() else 0f),
                        "imageProcessedCount" to imgProc,
                        "imageTotalCount" to activeImgTotal,
                        "imageSpeedBps" to imgSpd,
                        "imageBytesUploaded" to imgUpBytes,
                        "imageBytesTotal" to imgTotBytes,
                        "imageBytesRemaining" to imgRemBytes,
                        "imageEtaSeconds" to imgEtaSec,

                        // Thống kê riêng cho Video (Video Pipeline)
                        "videoFileName" to safeWorkerText(if (curRunVideo) vidFile else "Đã tắt", 180),
                        "videoProgress" to (if (curRunVideo) videoProgress.get() else 0f),
                        "videoFileProgress" to (if (curRunVideo) videoFileProgress.get() else 0f),
                        "videoFileBytesWritten" to (if (curRunVideo) videoFileBytesWritten.get() else 0L),
                        "videoFileBytesTotal" to (if (curRunVideo) videoFileBytesTotal.get() else 0L),
                        "videoProcessedCount" to vidProc,
                        "videoTotalCount" to activeVidTotal,
                        "videoSpeedBps" to vidSpd,
                        "videoBytesUploaded" to vidUpBytes,
                        "videoBytesTotalPending" to vidTotBytes,
                        "videoBytesRemaining" to vidRemBytes,
                        "videoEtaSeconds" to vidEtaSec
                    ))

                    sendNasSessionHeartbeat(
                        status = when {
                            AutoBackupState.isPaused.value -> "paused"
                            !nasSlotGranted.get() -> "queued"
                            else -> "running"
                        },
                        force = force
                    )

                    try {
                        val progressInt = (overallPercent * 100).toInt()
                        val formattedSpeed = com.nas.naswebdav.utils.FormatUtils.formatBytes(combinedSpeed)
                        val speedNotice = if (combinedSpeed > 0) " ($formattedSpeed/s)" else ""
                        val notifContent = "Ảnh: ${imageProcessedCount.get()}/${imageTotalCount.get()} • Video: ${videoProcessedCount.get()}/${videoTotalCount.get()}"
                        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, "auto_backup_channel")
                            .setSmallIcon(android.R.drawable.ic_menu_upload)
                            .setContentTitle("Đang sao lưu song song: $progressInt%$speedNotice")
                            .setContentText(safeWorkerText(notifContent, 120))
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

            // Đẩy trạng thái ban đầu để UI không bị kẹt ở "0 / 0 tệp"
            pushProgress(force = true)

            val canBatchStream = webDavManager.supportsBatchStream()
            val canResumableUpload = webDavManager.supportsResumableUpload()

            fun isWifiConnected(): Boolean {
                return runCatching {
                    val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                        ?: return@runCatching true
                    val active = cm.activeNetwork ?: return@runCatching false
                    val caps = cm.getNetworkCapabilities(active) ?: return@runCatching false
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }.getOrDefault(true)
            }

            suspend fun waitForStableWifiAndNas(fileTarget: AtomicReference<String>, speedTarget: AtomicLong, reason: String) {
                var notified = false
                while (!isStopped) {
                    val wifiOk = isWifiConnected()
                    val pingMs = if (wifiOk) runCatching { webDavManager.checkPingServer() ?: -1L }.getOrDefault(-1L) else -1L
                    if (wifiOk && pingMs >= 0L) {
                        if (notified) {
                            SystemLogger.log("INFO", "AutoBackup", "Kết nối Wi-Fi tới NAS đã ổn định (${pingMs}ms) — tự động nối lại đồng bộ.")
                        }
                        return
                    }
                    if (!notified) {
                        notified = true
                        speedTarget.set(0L)
                        fileTarget.set("Tạm dừng: chờ Wi-Fi ổn định...")
                        SystemLogger.log("WARNING", "AutoBackup", "Mạng không ổn định ($reason) — tạm dừng đồng bộ, tự nối lại khi có Wi-Fi tới NAS.")
                        pushProgress(force = true)
                    }
                    kotlinx.coroutines.delay(5000L)
                }
            }

            suspend fun uploadSingleFileDirect(item: PendingTarBackupItem, isImage: Boolean) {
                val mimeType = try {
                    applicationContext.contentResolver.getType(ContentUris.withAppendedId(item.mediaUri, item.id)) ?: "application/octet-stream"
                } catch (e: Exception) {
                    SystemLogger.log("WARNING", "AutoBackup", "getType contentResolver fallback: ${e.message}")
                    "application/octet-stream"
                }
                var effectiveTarget = item.targetFileNasPath
                var lastSpeedCalcTime = System.currentTimeMillis()
                var lastSpeedCalcBytes = 0L

                val speedTarget = if (isImage) imageSpeedBps else videoSpeedBps
                val progressTarget = if (isImage) imageProgress else videoProgress
                val fileTarget = if (isImage) imageCurrentFile else videoCurrentFile

                if (!isImage) {
                    videoFileBytesWritten.set(0L)
                    videoFileBytesTotal.set(item.fileSize)
                    videoFileProgress.set(0f)
                }

                fileTarget.set(item.fileName)

                // Video / tệp lớn (> 512MB): ưu tiên Resumable Chunked Upload (16MB/chunk, rớt mạng nối lại từ đúng offset)
                val resumableThresholdBytes = 512L * 1024 * 1024
                var usedResumableVerified = false
                if (!isImage && canResumableUpload && item.fileSize > resumableThresholdBytes && item.sourceProof.sha256.length == 64) {
                    val decodedRemoteName = runCatching {
                        java.net.URLDecoder.decode(item.stableRemoteName, "UTF-8")
                    }.getOrDefault(item.stableRemoteName)
                    val onResumableProgress: (Long, Long) -> Unit = { bytesWritten, totalBytes ->
                        if (isStopped || !isPipelineAllowed(isImage)) {
                            speedTarget.set(0L)
                            throw kotlinx.coroutines.CancellationException("Pipeline stopped")
                        }
                        val now = System.currentTimeMillis()
                        val dt = (now - lastSpeedCalcTime) / 1000.0
                        if (dt >= 0.4 || bytesWritten == totalBytes) {
                            val delta = (bytesWritten - lastSpeedCalcBytes).coerceAtLeast(0L)
                            if (dt > 0) speedTarget.set((delta / dt).toLong().coerceAtLeast(0L))
                            lastSpeedCalcTime = now
                            lastSpeedCalcBytes = bytesWritten
                        }
                        val percent = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                        progressTarget.set(percent)
                        videoFileBytesWritten.set(bytesWritten)
                        videoFileBytesTotal.set(totalBytes)
                        videoFileProgress.set(percent)
                        runBlocking { pushProgress() }
                    }
                    val resumableTargetUrl = webDavManager.uploadLargeFileResumable(
                        context = applicationContext,
                        uri = item.uri,
                        targetFolder = item.targetFolder,
                        decodedFileName = decodedRemoteName,
                        totalSize = item.fileSize,
                        expectedSha256 = item.sourceProof.sha256,
                        onProgress = onResumableProgress,
                        isCancelled = { isStopped || !isPipelineAllowed(isImage) },
                        waitForStableNetwork = { reason ->
                            waitForStableWifiAndNas(fileTarget, speedTarget, reason)
                            fileTarget.set(item.fileName)
                        }
                    )
                    if (resumableTargetUrl != null) {
                        effectiveTarget = resumableTargetUrl
                        videoFileBytesWritten.set(item.fileSize)
                        videoFileBytesTotal.set(item.fileSize)
                        videoFileProgress.set(1f)
                        usedResumableVerified = true
                    }
                }

                if (!usedResumableVerified) run {
                    if (isStopped) throw kotlinx.coroutines.CancellationException("User cancelled upload")
                    val tmpRemoteName = ".nas-backup-" + java.util.UUID.randomUUID() + ".tmp"
                    val tmpNasPath = if (item.targetFolder.endsWith("/")) item.targetFolder + tmpRemoteName else "${item.targetFolder}/$tmpRemoteName"
                    try {
                        for (webDavAttempt in 1..3) {
                            try {
                                val rawStream = applicationContext.contentResolver.openInputStream(ContentUris.withAppendedId(item.mediaUri, item.id))
                                    ?: error("Không đọc được file ${item.fileName} (ContentResolver trả null)")
                                rawStream.use { input2 ->
                                    val onUploadProgress: (Long, Long) -> Unit = { bytesWritten, totalBytes ->
                                        if (isStopped || !isPipelineAllowed(isImage)) {
                                            speedTarget.set(0L)
                                            throw kotlinx.coroutines.CancellationException("Pipeline stopped")
                                        }
                                        val now = System.currentTimeMillis()
                                        val dt = (now - lastSpeedCalcTime) / 1000.0
                                        if (dt >= 0.4 || bytesWritten == totalBytes) {
                                            if (dt > 0) speedTarget.set(((bytesWritten - lastSpeedCalcBytes) / dt).toLong().coerceAtLeast(0L))
                                            lastSpeedCalcTime = now
                                            lastSpeedCalcBytes = bytesWritten
                                        }
                                        val percent = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                                        progressTarget.set(percent)
                                        if (!isImage) {
                                            videoFileBytesWritten.set(bytesWritten)
                                            videoFileBytesTotal.set(totalBytes)
                                            videoFileProgress.set(percent)
                                        }
                                        runBlocking { pushProgress() }
                                    }
                                    webDavManager.uploadStreamWithProgress(tmpNasPath, input2, item.fileSize, mimeType, onUploadProgress, null)
                                }
                                if (!isImage) {
                                    videoFileBytesWritten.set(item.fileSize)
                                    videoFileBytesTotal.set(item.fileSize)
                                    videoFileProgress.set(1f)
                                }
                                try {
                                    webDavManager.moveFileNoOverwrite(tmpNasPath, item.targetFileNasPath)
                                } catch (e: Exception) {
                                    val isConflict = (e.message ?: "").contains("412")
                                    if (!isConflict) {
                                        try { webDavManager.deleteFile(tmpNasPath, false) } catch (e: Exception) { SystemLogger.log("WARNING", "AutoBackup", "deleteFile tmpNasPath SMB: ${e.message}") }
                                        throw e
                                    }
                                    val destination = webDavManager.inspectBackupDestination(item.targetFileNasPath, item.fileSize, item.sourceProof.sha256)
                                    if (destination == BackupContentState.MATCH) {
                                        SystemLogger.log("INFO", "AutoBackup", "Đích đã đúng nội dung — không tạo bản trùng.")
                                        break
                                    }
                                    if (destination != BackupContentState.DIFFERENT) {
                                        throw java.io.IOException("Đích chưa xác minh được; không tạo thêm phiên bản")
                                    }
                                    val dotIdx2 = item.stableRemoteName.lastIndexOf('.')
                                    val ts = System.currentTimeMillis()
                                    val versionedName = if (dotIdx2 > 0) {
                                        item.stableRemoteName.substring(0, dotIdx2) + "__v" + ts + item.stableRemoteName.substring(dotIdx2)
                                    } else {
                                        item.stableRemoteName + "__v" + ts
                                    }
                                    var versionedPath = if (item.targetFolder.endsWith("/")) item.targetFolder + versionedName else "${item.targetFolder}/$versionedName"
                                    SystemLogger.log("INFO", "AutoBackup", "Nguồn ${item.fileName} đã đổi so với bản backup — lưu phiên bản mới thay vì ghi đè.")
                                    var versionedOk = false
                                    for (vAttempt in 1..2) {
                                        try {
                                            webDavManager.moveFileNoOverwrite(tmpNasPath, versionedPath)
                                            versionedOk = true
                                            break
                                        } catch (ve: Exception) {
                                            if ((ve.message ?: "").contains("412")) {
                                                val conflict = webDavManager.inspectBackupDestination(versionedPath, item.fileSize, item.sourceProof.sha256)
                                                if (conflict == BackupContentState.MATCH) { versionedOk = true; break }
                                                if (conflict != BackupContentState.DIFFERENT || vAttempt >= 2) {
                                                    throw java.io.IOException("Không xác minh được xung đột phiên bản; không tạo thêm bản sao", ve)
                                                }
                                                val ts2 = System.currentTimeMillis()
                                                val dot2 = versionedName.lastIndexOf('.')
                                                val altName = if (dot2 > 0) {
                                                    versionedName.substring(0, dot2) + "_$ts2" + versionedName.substring(dot2)
                                                } else {
                                                    versionedName + "_$ts2"
                                                }
                                                versionedPath = if (item.targetFolder.endsWith("/")) item.targetFolder + altName else "${item.targetFolder}/$altName"
                                                kotlinx.coroutines.delay(5)
                                                continue
                                            } else throw ve
                                        }
                                    }
                                    if (!versionedOk) throw IllegalStateException("Không lưu được phiên bản mới cho ${item.fileName}")
                                    effectiveTarget = versionedPath
                                    break
                                }
                                break
                            } catch (e: Exception) {
                                val msg = e.message ?: ""
                                val isNetErr = e is java.net.SocketTimeoutException ||
                                    e is java.net.ConnectException ||
                                    e is java.net.UnknownHostException ||
                                    e is java.net.SocketException ||
                                    e is java.io.EOFException
                                val isServerError = msg.contains("500") || msg.contains("502") || msg.contains("503")
                                if ((isNetErr || isServerError) && webDavAttempt < 3) {
                                    android.util.Log.w("AutoBackup", "WebDAV attempt $webDavAttempt failed ($msg), waiting for stable network...")
                                    if (isNetErr) {
                                        waitForStableWifiAndNas(fileTarget, speedTarget, msg.ifBlank { e.javaClass.simpleName })
                                        fileTarget.set(item.fileName)
                                    } else {
                                        kotlinx.coroutines.delay(webDavAttempt * 2000L)
                                    }
                                } else {
                                    throw e
                                }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            try { webDavManager.cleanupBackupTemporary(tmpNasPath, backupAuth) }
                            catch (_: Exception) { SystemLogger.log("WARNING", "AutoBackup", "Chưa dọn được file tạm của phiên backup") }
                        }
                    }
                }

                val verifyTarget = effectiveTarget
                val uploadVerified = backupSourceStillCurrent(applicationContext, item.uri, item.sourceProof) &&
                    webDavManager.inspectBackupDestination(verifyTarget, item.fileSize, item.sourceProof.sha256) == BackupContentState.MATCH
                if (!uploadVerified) {
                    SystemLogger.log("WARNING", "AutoBackup", "Bản tải lên chưa xác minh đầy đủ; giữ nguồn: ${item.fileName}")
                    failedCount.incrementAndGet()
                    return
                }

                dbMutex.withLock {
                    db.fingerprintDao().insertFingerprint(FileFingerprint(
                        filePath = verifyTarget, hash = "", fileName = item.fileName,
                        fileSize = item.fileSize, accountKey = backupAccountKey, sourceKey = item.sourceKey,
                        contentHash = item.sourceProof.sha256
                    ))
                    db.verifiedHashDao().put(VerifiedHashCache(
                        accountKey = backupAccountKey, path = verifyTarget, contentLength = item.fileSize,
                        version = item.sourceProof.version ?: "", sha256 = item.sourceProof.sha256, fileId = ""
                    ))
                }
                if (AutoBackupState.deleteAfter.value) {
                    if (deleteVerifiedBackupSource(applicationContext, item.uri, item.dataPath, item.sourceProof)) {
                        deletedCount.incrementAndGet()
                        storageFreedBytes.addAndGet(item.fileSize)
                        prefsRepo.addBackupFreedBytes(item.fileSize)
                        android.util.Log.i("AutoBackup", "Đã xóa nguồn sau khi tải trực tiếp: ${item.fileName}")
                    } else if (isDeletePermissionWarned.compareAndSet(false, true) && Build.VERSION.SDK_INT >= 27) {
                        SystemLogger.log("WARNING", "AutoBackup", "Đã xác minh backup nhưng chưa xóa được nguồn (${item.fileName}) — hãy cấp quyền Quản lý tất cả tệp trong Cài đặt Android.")
                    }
                }
                if (isImage) imageBytesCompleted.addAndGet(item.fileSize) else videoBytesCompleted.addAndGet(item.fileSize)
                backupCount.incrementAndGet()
            }

            suspend fun processPipeline(isImage: Boolean, mediaUri: Uri) {
                val processedCounter = if (isImage) imageProcessedCount else videoProcessedCount
                val totalCounter = if (isImage) imageTotalCount else videoTotalCount
                val speedTarget = if (isImage) imageSpeedBps else videoSpeedBps
                val progressTarget = if (isImage) imageProgress else videoProgress
                val fileTarget = if (isImage) imageCurrentFile else videoCurrentFile
                val bytesCompletedTarget = if (isImage) imageBytesCompleted else videoBytesCompleted

                val pendingTarBatch = mutableListOf<PendingTarBackupItem>()
                var pendingTarBatchBytes = 0L
                val pendingBatchDigests = mutableMapOf<String, String>()

                suspend fun flushTarBatch() {
                    if (pendingTarBatch.isEmpty()) return
                    val batch = pendingTarBatch.toList()
                    pendingTarBatch.clear()
                    pendingTarBatchBytes = 0L
                    pendingBatchDigests.clear()

                    val byFolder = batch.groupBy { it.targetFolder }
                    for ((folder, items) in byFolder) {
                        if (isStopped) break
                        val batchBytesTotal = items.sumOf { it.fileSize }
                        if (isImage) {
                            fileTarget.set("Gom Stream Rust: ${items.size} ảnh")
                            imageBytesInFlight.set(0L)
                        } else {
                            fileTarget.set(if (items.size == 1) items.first().fileName else "Gom Stream Rust: ${items.size} video")
                            videoFileBytesWritten.set(0L)
                            videoFileBytesTotal.set(batchBytesTotal)
                            videoFileProgress.set(0f)
                        }
                        val tarFiles = items.map { itm: PendingTarBackupItem ->
                            val rawRel = if (itm.targetFileNasPath.startsWith(itm.targetFolder)) {
                                itm.targetFileNasPath.removePrefix(itm.targetFolder).removePrefix("/")
                            } else {
                                itm.targetFileNasPath.removePrefix("/")
                            }
                            val relPathInTar = runCatching { java.net.URLDecoder.decode(rawRel, "UTF-8") }.getOrDefault(rawRel)
                            TarBatchFileItem(
                                uri = itm.uri,
                                relativePath = relPathInTar,
                                size = itm.fileSize,
                                mtimeSec = itm.mtimeSec,
                                sourceProofSha256 = itm.sourceProof.sha256,
                                dataPath = itm.dataPath
                            )
                        }
                        val tarBody = TarStreamRequestBody(
                            context = applicationContext,
                            items = tarFiles,
                            onProgress = { bytesWritten: Long, totalBytes: Long, speedBps: Long ->
                                if (isStopped || !isPipelineAllowed(isImage)) {
                                    speedTarget.set(0L)
                                    if (isImage) imageBytesInFlight.set(0L) else videoFileBytesWritten.set(0L)
                                    throw kotlinx.coroutines.CancellationException("Pipeline stopped")
                                }
                                speedTarget.set(speedBps)
                                val percent = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                                progressTarget.set(percent)
                                if (isImage) {
                                    imageBytesInFlight.set(bytesWritten.coerceAtMost(batchBytesTotal))
                                } else {
                                    videoFileBytesWritten.set(bytesWritten.coerceAtMost(batchBytesTotal))
                                    videoFileBytesTotal.set(batchBytesTotal)
                                    videoFileProgress.set(percent)
                                }
                                runBlocking { pushProgress() }
                            }
                        )
                        val res = try {
                            webDavManager.uploadBatchTarStream(folder, tarBody)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            null
                        } finally {
                            if (isImage) imageBytesInFlight.set(0L) else videoFileBytesWritten.set(0L)
                        }
                        if (res != null && res.ok) {
                            val serverMap = res.items.associateBy { it.path }
                            for (itm in items) {
                                val rawRel = if (itm.targetFileNasPath.startsWith(itm.targetFolder)) {
                                    itm.targetFileNasPath.removePrefix(itm.targetFolder).removePrefix("/")
                                } else {
                                    itm.targetFileNasPath.removePrefix("/")
                                }
                                val rel = runCatching { java.net.URLDecoder.decode(rawRel, "UTF-8") }.getOrDefault(rawRel)
                                val srv = serverMap[rel] ?: serverMap[rawRel]
                                if (srv != null && srv.sha256.equals(itm.sourceProof.sha256, ignoreCase = true)) {
                                    dbMutex.withLock {
                                        db.fingerprintDao().insertFingerprint(FileFingerprint(
                                            filePath = itm.targetFileNasPath, hash = "", fileName = itm.fileName,
                                            fileSize = itm.fileSize, accountKey = backupAccountKey, sourceKey = itm.sourceKey,
                                            contentHash = itm.sourceProof.sha256
                                        ))
                                        db.verifiedHashDao().put(VerifiedHashCache(
                                            accountKey = backupAccountKey, path = itm.targetFileNasPath, contentLength = itm.fileSize,
                                            version = itm.sourceProof.version ?: "", sha256 = itm.sourceProof.sha256, fileId = ""
                                        ))
                                    }
                                    bytesCompletedTarget.addAndGet(itm.fileSize)
                                    if (AutoBackupState.deleteAfter.value) {
                                        if (deleteVerifiedBackupSource(applicationContext, itm.uri, itm.dataPath, itm.sourceProof)) {
                                            deletedCount.incrementAndGet()
                                            storageFreedBytes.addAndGet(itm.fileSize)
                                            prefsRepo.addBackupFreedBytes(itm.fileSize)
                                            pushProgress(force = true)
                                            android.util.Log.i("AutoBackup", "Đã xóa nguồn sau Rust batch upload: ${itm.fileName}")
                                        } else if (isDeletePermissionWarned.compareAndSet(false, true) && Build.VERSION.SDK_INT >= 27) {
                                            SystemLogger.log("WARNING", "AutoBackup", "Đã xác minh backup nhưng chưa xóa được nguồn (${itm.fileName}) — hãy cấp quyền Quản lý tất cả tệp trong Cài đặt Android.")
                                        }
                                    }
                                    backupCount.incrementAndGet()
                                } else {
                                    try {
                                        uploadSingleFileDirect(itm, isImage = isImage)
                                    } catch (e: Exception) {
                                        failedCount.incrementAndGet()
                                    }
                                }
                            }
                        } else {
                            for (itm in items) {
                                if (isStopped) break
                                val cached = db.verifiedHashDao().get(backupAccountKey, itm.targetFileNasPath)
                                if (cached != null && cached.sha256.equals(itm.sourceProof.sha256, ignoreCase = true)) {
                                    bytesCompletedTarget.addAndGet(itm.fileSize)
                                    skippedCount.incrementAndGet()
                                    continue
                                }
                                try {
                                    uploadSingleFileDirect(itm, isImage = isImage)
                                } catch (e: Exception) {
                                    failedCount.incrementAndGet()
                                }
                            }
                        }
                    }
                }

                val checkpointName = if (isImage) "AutoBackup_Image" else "AutoBackup_Video"
                val savedCheckpoint = runCatching {
                    dbMutex.withLock { db.checkpointDao().getCheckpoint(checkpointName) }
                }.getOrNull()

                val resumeTargetFolder = savedCheckpoint?.lastProcessedFolder ?: ""
                val resumeTargetId = resumeTargetFolder.substringBefore(":").toLongOrNull() ?: -1L
                var hasResumed = (savedCheckpoint == null || savedCheckpoint.scannedCount <= 0 || resumeTargetId <= 0L)

                if (!hasResumed && savedCheckpoint != null) {
                    val initialCount = minOf(savedCheckpoint.scannedCount, totalCounter.get())
                    fileTarget.set("Tiếp tục: $initialCount/${totalCounter.get()}")
                    pushProgress(force = true)
                }

                // Video ưu tiên dung lượng thấp đồng bộ trước (SIZE ASC), Ảnh theo thời gian mới nhất trước (DATE_ADDED DESC)
                val sortOrder = if (isImage) {
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                } else {
                    "${MediaStore.MediaColumns.SIZE} ASC, ${MediaStore.MediaColumns.DATE_ADDED} DESC"
                }
                applicationContext.contentResolver.query(mediaUri, projection, null, null, sortOrder)?.use { cursor ->
                    totalCounter.set(cursor.count)
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateModifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                    while (cursor.moveToNext() && !isStopped) {
                        if (!isPipelineAllowed(isImage)) {
                            speedTarget.set(0L)
                            throw kotlinx.coroutines.CancellationException("Pipeline stopped by mode switch")
                        }
                        while (AutoBackupState.isPaused.value && !isStopped) { kotlinx.coroutines.delay(500) }
                        if (!isWifiConnected() && !isStopped) {
                            waitForStableWifiAndNas(fileTarget, speedTarget, "mất Wi-Fi")
                        }
                        while (!nasSlotGranted.get() && !isStopped) {
                            pushProgress(force = true)
                            kotlinx.coroutines.delay(nasRecommendedPaceMs.get().coerceIn(1000L, 3000L))
                            sendNasSessionHeartbeat("queued", force = true)
                        }
                        val paceMs = nasRecommendedPaceMs.get()
                        if (paceMs in 1L..1000L && !isStopped) {
                            kotlinx.coroutines.delay(paceMs)
                        }

                        val currentCount = minOf(cursor.position + 1, totalCounter.get())
                        processedCounter.set(currentCount)

                        val rawFileName = cursor.getString(nameIndex) ?: continue
                        val fileName = com.nas.naswebdav.utils.FormatUtils.sanitizeFileName(rawFileName)
                        val dataPath = cursor.getString(dataIndex) ?: continue
                        val id = cursor.getLong(idIndex)

                        val externalStorageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
                        val cleanRelativePath = if (dataPath.startsWith(externalStorageRoot)) {
                            dataPath.substring(externalStorageRoot.length).trimStart('/')
                        } else {
                            val segments = dataPath.split("/")
                            if (segments.size >= 2) "${segments[segments.size - 2]}/${segments.last()}" else fileName
                        }

                        val parentRelativePath = cleanRelativePath.substringBeforeLast("/", "")
                        val targetFolder = if (parentRelativePath.isNotEmpty()) {
                            ensureFolderExists(parentRelativePath)
                        } else {
                            backupFolderBase
                        }

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
                            skippedCount.incrementAndGet()
                            continue
                        }
                        val fileUri = android.content.ContentUris.withAppendedId(mediaUri, id)

                        if (!hasResumed) {
                            if (id == resumeTargetId) {
                                hasResumed = true
                            }
                            val currentSourceVer = sourceFileVersion(applicationContext, fileUri)
                            val localCachedFast = dbMutex.withLock { db.verifiedHashDao().get(backupAccountKey, targetFileNasPath) }
                            val isLocallyVerifiedMatchFast = localCachedFast != null && localCachedFast.contentLength == fileSize &&
                                localCachedFast.sha256.isNotBlank() && currentSourceVer != null && localCachedFast.version == currentSourceVer.value

                            if (isLocallyVerifiedMatchFast) {
                                if (AutoBackupState.deleteAfter.value) {
                                    val sourceProof = BackupSourceDigest(localCachedFast!!.sha256, currentSourceVer!!.value)
                                    if (deleteVerifiedBackupSource(applicationContext, fileUri, dataPath, sourceProof)) {
                                        deletedCount.incrementAndGet()
                                        storageFreedBytes.addAndGet(fileSize)
                                        prefsRepo.addBackupFreedBytes(fileSize)
                                        android.util.Log.i("AutoBackup", "Đã xóa nguồn khớp cục bộ (fast-path): $fileName")
                                    }
                                }
                                bytesCompletedTarget.addAndGet(fileSize)
                                pushProgress()
                                continue
                            } else {
                                hasResumed = true
                            }
                        }

                        if (currentCount % 500 == 0) {
                            val currentUser = SecurePrefsHelper.getUser(applicationContext)
                            val currentPass = SecurePrefsHelper.getPass(applicationContext)
                            val currentCandidateUrls = (SecurePrefsHelper.getUrlList(applicationContext) +
                                listOfNotNull(
                                    SecurePrefsHelper.getTailscaleBaseUrl(applicationContext).takeIf { it.isNotBlank() },
                                    SecurePrefsHelper.getUrl(applicationContext).takeIf { it.isNotBlank() }
                                ))
                                .map { it.trimEnd('/').removeSuffix("/webdav").trimEnd('/') }
                                .filter { it.isNotBlank() }

                            val activeBase = backupAuth.baseUrl.trimEnd('/').removeSuffix("/webdav").trimEnd('/')
                            if (currentUser != backupAuth.user || currentPass != backupAuth.pass ||
                                (currentCandidateUrls.isNotEmpty() && currentCandidateUrls.none { it.equals(activeBase, ignoreCase = true) })) {
                                SystemLogger.log("WARNING", "AutoBackup", "Tài khoản sao lưu thay đổi (user/pass/host) — dừng worker")
                                throw CancellationException("Backup account changed")
                            }
                        }

                        fileTarget.set(fileName)
                        pushProgress()

                        try {
                            val localCachedFast = dbMutex.withLock { db.verifiedHashDao().get(backupAccountKey, targetFileNasPath) }
                            val currentSourceVer = sourceFileVersion(applicationContext, fileUri)
                            val isLocallyVerifiedMatchFast = localCachedFast != null && localCachedFast.contentLength == fileSize &&
                                localCachedFast.sha256.isNotBlank() && currentSourceVer != null && localCachedFast.version == currentSourceVer.value

                            val sourceProof = if (isLocallyVerifiedMatchFast) {
                                BackupSourceDigest(localCachedFast!!.sha256, currentSourceVer!!.value)
                            } else {
                                backupSourceDigest(applicationContext, fileUri, fileSize)
                            }
                            val sourceKey = fileUri.toString()

                            val shaMutex = shaMutexMap.computeIfAbsent(sourceProof.sha256) { Mutex() }
                            shaMutex.withLock {
                                val localCached = dbMutex.withLock { db.verifiedHashDao().get(backupAccountKey, targetFileNasPath) }
                                val isLocallyVerifiedMatch = localCached != null && localCached.contentLength == fileSize &&
                                    localCached.sha256.isNotBlank() && currentSourceVer != null && localCached.version == currentSourceVer.value

                                val queuedTarget = if (canBatchStream && isImage) pendingBatchDigests[sourceProof.sha256] else queuedDigests[sourceProof.sha256]
                                val plan = if (isLocallyVerifiedMatch || queuedTarget != null) {
                                    BackupTargetPlan(queuedTarget ?: targetFileNasPath, true)
                                } else {
                                    webDavManager.planBackupTarget(targetFileNasPath, fileSize, sourceProof.sha256, backupAccountKey)
                                }
                                targetFileNasPath = plan.path
                                if (!backupSourceStillCurrent(applicationContext, fileUri, sourceProof)) {
                                    throw java.io.IOException("Nguồn thay đổi trong lúc kiểm tra backup")
                                }
                                val isSkipped = plan.skip

                                if (isSkipped) {
                                    if (!isLocallyVerifiedMatch) {
                                        dbMutex.withLock {
                                            db.verifiedHashDao().put(VerifiedHashCache(
                                                accountKey = backupAccountKey,
                                                path = plan.path,
                                                contentLength = fileSize,
                                                version = sourceProof.version ?: "",
                                                sha256 = sourceProof.sha256,
                                                fileId = ""
                                            ))
                                            if (plan.path != targetFileNasPath) {
                                                db.verifiedHashDao().put(VerifiedHashCache(
                                                    accountKey = backupAccountKey,
                                                    path = targetFileNasPath,
                                                    contentLength = fileSize,
                                                    version = sourceProof.version ?: "",
                                                    sha256 = sourceProof.sha256,
                                                    fileId = ""
                                                ))
                                            }
                                            db.fingerprintDao().insertFingerprint(FileFingerprint(
                                                filePath = plan.path,
                                                hash = "",
                                                fileName = fileName,
                                                fileSize = fileSize,
                                                accountKey = backupAccountKey,
                                                sourceKey = sourceKey,
                                                contentHash = sourceProof.sha256
                                            ))
                                        }
                                    }
                                    var deletedExisting = false
                                    if (AutoBackupState.deleteAfter.value) {
                                        if (deleteVerifiedBackupSource(applicationContext, fileUri, dataPath, sourceProof)) {
                                            deletedExisting = true
                                            deletedCount.incrementAndGet()
                                            storageFreedBytes.addAndGet(fileSize)
                                            prefsRepo.addBackupFreedBytes(fileSize)
                                            android.util.Log.i("AutoBackup", "Đã xóa nguồn đã có trên NAS: $fileName")
                                        } else if (isDeletePermissionWarned.compareAndSet(false, true) && Build.VERSION.SDK_INT >= 27) {
                                            SystemLogger.log("WARNING", "AutoBackup", "Không thể xóa tệp đã có trên NAS ($fileName) — hãy cấp quyền Quản lý tất cả tệp trong Cài đặt Android.")
                                        }
                                    }
                                    bytesCompletedTarget.addAndGet(fileSize)
                                    skippedCount.incrementAndGet()
                                    fileTarget.set(if (deletedExisting) "Đã giải phóng: $fileName" else "Bỏ qua: $fileName")
                                    pushProgress(force = deletedExisting)
                                    return@withLock
                                }

                                val mtimeSec = if (dateModifiedIndex >= 0) {
                                    val dm = cursor.getLong(dateModifiedIndex)
                                    if (dm > 0L) dm else System.currentTimeMillis() / 1000L
                                } else {
                                    System.currentTimeMillis() / 1000L
                                }
                                val pendingItem = PendingTarBackupItem(
                                    uri = fileUri,
                                    id = id,
                                    mediaUri = mediaUri,
                                    fileName = fileName,
                                    dataPath = dataPath,
                                    targetFileNasPath = targetFileNasPath,
                                    targetFolder = targetFolder,
                                    stableRemoteName = stableRemoteName,
                                    parentRelativePath = parentRelativePath,
                                    fileSize = fileSize,
                                    mtimeSec = mtimeSec,
                                    sourceProof = sourceProof,
                                    sourceKey = sourceKey
                                )

                                queuedDigests[sourceProof.sha256] = targetFileNasPath

                                // 100% Ảnh và Video (tới 8GB - giới hạn chuẩn POSIX ustar) đều đi qua đường ống Rust TAR Stream (/api/backup/batch_stream -> nas_hasher_extract_tar_batch_json)
                                val maxRustStreamFileBytes = 8L * 1024 * 1024 * 1024
                                val batchFlushByteThreshold = if (isImage) (100L * 1024 * 1024) else (64L * 1024 * 1024)
                                val batchFlushCountThreshold = if (isImage) 100 else 10
                                if (canBatchStream && fileSize in 1L..maxRustStreamFileBytes && sourceProof.sha256.isNotBlank()) {
                                    pendingTarBatch.add(pendingItem)
                                    pendingTarBatchBytes += fileSize
                                    pendingBatchDigests[sourceProof.sha256] = targetFileNasPath
                                    if (pendingTarBatch.size >= batchFlushCountThreshold || pendingTarBatchBytes >= batchFlushByteThreshold) {
                                        flushTarBatch()
                                    }
                                } else {
                                    if (pendingTarBatch.isNotEmpty()) {
                                        flushTarBatch()
                                    }
                                    uploadSingleFileDirect(pendingItem, isImage = isImage)
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            val isAccessDenied = e is SecurityException
                                || (e.message ?: "").contains("has no access")
                                || (e.message ?: "").contains("Permission denied")
                            if (isAccessDenied) {
                                skippedCount.incrementAndGet()
                            } else {
                                failedCount.incrementAndGet()
                                if (e !is java.io.FileNotFoundException && !(e.message ?: "").contains("Missing file")) {
                                    if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.SocketException) {
                                        SmartNetworkManager.invalidateCache()
                                        SystemLogger.log("INFO", "AutoBackup", "Mạng chập chờn, tạm dừng chờ Wi-Fi ổn định sau lỗi file $fileName: ${e.message}")
                                        waitForStableWifiAndNas(fileTarget, speedTarget, e.message ?: e.javaClass.simpleName)
                                    } else {
                                        SystemLogger.log("WARNING", "AutoBackup", "Lỗi sao lưu tập tin $fileName: ${e.message}")
                                    }
                                }
                            }
                        }
                        pushProgress()
                        kotlinx.coroutines.yield()
                        if (currentCount % 25 == 0) {
                            runCatching {
                                dbMutex.withLock {
                                    db.checkpointDao().saveCheckpoint(
                                        ScanCheckpoint(
                                            workerName = checkpointName,
                                            lastProcessedFolder = "$id:$dataPath",
                                            scannedCount = currentCount,
                                            foundCount = backupCount.get()
                                        )
                                    )
                                }
                            }.onFailure { e -> SystemLogger.log("WARNING", "AutoBackup", "saveCheckpoint runCatching: ${e.message}") }
                        }
                    }
                }

                if (pendingTarBatch.isNotEmpty()) {
                    flushTarBatch()
                }

                if (!isStopped) {
                    processedCounter.set(totalCounter.get())
                    if (!isImage) {
                        videoFileBytesWritten.set(0L)
                        videoFileBytesTotal.set(0L)
                        videoFileProgress.set(1f)
                    }
                    pushProgress(force = true)
                    runCatching {
                        dbMutex.withLock { db.checkpointDao().clearCheckpoint(checkpointName) }
                    }.onFailure { e -> SystemLogger.log("WARNING", "AutoBackup", "clearCheckpoint runCatching: ${e.message}") }
                }
            }

            // Đăng ký phiên với NAS để điều phối nhiều thiết bị song song (fail-open nếu NAS bản cũ)
            sendNasSessionHeartbeat("running", force = true)

            // Điều phối động 2 pipeline theo chế độ người dùng chọn realtime (both / image / video):
            // Chọn "Chỉ ảnh" -> dừng ngay tiến trình Video; chọn "Chỉ video" -> dừng ngay tiến trình Ảnh.
            val imageCompleted = AtomicBoolean(totalImageFiles == 0)
            val videoCompleted = AtomicBoolean(totalVideoFiles == 0)
            try {
                supervisorScope {
                    var imageJob: Deferred<Unit>? = null
                    var videoJob: Deferred<Unit>? = null
                    while (!isStopped) {
                        val allowImg = isPipelineAllowed(true)
                        val allowVid = isPipelineAllowed(false)

                        if (!allowImg && imageJob?.isActive == true) {
                            imageJob?.cancel(kotlinx.coroutines.CancellationException("Switched away from image pipeline"))
                            imageSpeedBps.set(0L)
                            pushProgress(force = true)
                        }
                        if (!allowVid && videoJob?.isActive == true) {
                            videoJob?.cancel(kotlinx.coroutines.CancellationException("Switched away from video pipeline"))
                            videoSpeedBps.set(0L)
                            videoFileBytesWritten.set(0L)
                            videoFileBytesTotal.set(0L)
                            videoFileProgress.set(0f)
                            pushProgress(force = true)
                        }

                        if (allowImg && !imageCompleted.get() && imageJob?.isActive != true) {
                            imageJob = async(Dispatchers.IO) {
                                try {
                                    processPipeline(isImage = true, mediaUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                    if (!isStopped && isPipelineAllowed(true)) imageCompleted.set(true)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    imageSpeedBps.set(0L)
                                    if (isStopped) throw e
                                } catch (e: Exception) {
                                    SystemLogger.log("ERROR", "AutoBackup", "Lỗi pipeline ảnh: ${e.message}")
                                    imageCompleted.set(true)
                                }
                            }
                        }
                        if (allowVid && !videoCompleted.get() && videoJob?.isActive != true) {
                            videoJob = async(Dispatchers.IO) {
                                try {
                                    processPipeline(isImage = false, mediaUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                                    if (!isStopped && isPipelineAllowed(false)) videoCompleted.set(true)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    videoSpeedBps.set(0L)
                                    videoFileBytesWritten.set(0L)
                                    videoFileBytesTotal.set(0L)
                                    videoFileProgress.set(0f)
                                    if (isStopped) throw e
                                } catch (e: Exception) {
                                    SystemLogger.log("ERROR", "AutoBackup", "Lỗi pipeline video: ${e.message}")
                                    videoCompleted.set(true)
                                }
                            }
                        }

                        val imgDoneOrOff = !allowImg || (imageCompleted.get() && imageJob?.isActive != true)
                        val vidDoneOrOff = !allowVid || (videoCompleted.get() && videoJob?.isActive != true)
                        if (imgDoneOrOff && vidDoneOrOff) break
                        kotlinx.coroutines.delay(150L)
                    }
                    imageJob?.cancel()
                    videoJob?.cancel()
                }
            } finally {
                sendNasSessionFinish()
            }

            val bCount = backupCount.get()
            val sCount = skippedCount.get()
            val fCount = failedCount.get()
            val dCount = deletedCount.get()

            val deletedSuffix = if (dCount > 0) ", Đã giải phóng trên điện thoại: $dCount tệp" else ""
            val logMessage = "Đồng bộ khép kín song song: Thành công $bCount tệp, Bỏ qua $sCount tệp tập tin trùng lặp, Thất bại: $fCount tệp$deletedSuffix."
            if (bCount > 0 || fCount > 0) {
                SystemLogger.log(if (fCount > 0) "WARNING" else "SUCCESS", "AutoBackup", logMessage)
            } else {
                SystemLogger.log("INFO", "AutoBackup", logMessage)
            }
            SystemLogger.log("INFO", "AutoBackup", "Hoàn tất đồng bộ nền song song (success=$bCount skipped=$sCount failed=$fCount)")

            if (totalFilesToProcess == 0) {
                runCatching {
                    dbMutex.withLock {
                        db.checkpointDao().clearCheckpoint("AutoBackup")
                        db.checkpointDao().clearCheckpoint("AutoBackup_Image")
                        db.checkpointDao().clearCheckpoint("AutoBackup_Video")
                    }
                }
                AutoBackupState.resultTotal.value = 0
                AutoBackupState.resultSuccess.value = 0
                AutoBackupState.resultSkipped.value = 0
                AutoBackupState.resultFailed.value = 0
                AutoBackupState.showResultDialog.value = true
                return@withContext Result.success()
            }

            AutoBackupState.resultTotal.value = totalFilesToProcess
            AutoBackupState.resultSuccess.value = bCount
            AutoBackupState.resultSkipped.value = sCount
            AutoBackupState.resultFailed.value = fCount
            AutoBackupState.showResultDialog.value = true
            // CC-005 P1: xong -> xóa checkpoint (lần sau quét mới từ đầu).
            runCatching {
                dbMutex.withLock {
                    db.checkpointDao().clearCheckpoint("AutoBackup")
                    db.checkpointDao().clearCheckpoint("AutoBackup_Image")
                    db.checkpointDao().clearCheckpoint("AutoBackup_Video")
                }
            }.onFailure { e -> SystemLogger.log("WARNING", "AutoBackup", "clearCheckpoint runCatching: ${e.message}") }

            SystemLogger.log("INFO", "AutoBackup", "Hoàn tất đồng bộ nền (trả Result.success)")
            return@withContext Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
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
