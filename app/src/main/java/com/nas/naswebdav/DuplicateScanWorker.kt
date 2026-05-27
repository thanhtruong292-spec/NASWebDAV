package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object DuplicateProgressState {
    val isPaused = MutableStateFlow(false)
    val stage = MutableStateFlow("Khởi động...")
    val currentFolder = MutableStateFlow("")
    val itemName = MutableStateFlow("")
    val isFolder = MutableStateFlow(false)
    val scannedCount = MutableStateFlow(0)
    val foundCount = MutableStateFlow(0)
    val hashCount = MutableStateFlow(0)
    val totalHashes = MutableStateFlow(0)
    val percent = MutableStateFlow(0f)
    val currentStagePercent = MutableStateFlow(0f)
    val currentFolderUrl = MutableStateFlow("")
    val stageNumber = MutableStateFlow(1)
    val totalStages = MutableStateFlow(4)
    val stageDescription = MutableStateFlow("")
    val elapsedTime = MutableStateFlow(0L)
    val estimatedTimeRemaining = MutableStateFlow(-1L)
}

object AutoBackupState {
    val isPaused = MutableStateFlow(false)
    val showResultDialog = MutableStateFlow(false)
    val resultTotal = MutableStateFlow(0)
    val resultSuccess = MutableStateFlow(0)
    val resultSkipped = MutableStateFlow(0)
    val resultFailed = MutableStateFlow(0)
}
class DuplicateScanWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {

    private val notificationId = 999
    private val channelId = "scan_channel"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(makeForegroundInfo(channelId, "Quét dọn hệ thống", notificationId, "Đang quét dữ liệu trùng lặp..."))
        } catch (_: Exception) {}

        val currentUrl = inputData.getString("currentUrl") ?: return@withContext Result.failure()
        val forceRestart = inputData.getBoolean("forceRestart", false)
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (user.isEmpty() || pass.isEmpty()) return@withContext Result.failure()

        val webDavManager = WebDavManager
        webDavManager.connect(currentUrl, user, pass)
        setThumbnailActivity("sync", true)
        val db = NasApplication.instance.database

        // KHỞI TẠO HỆ THỐNG THÔNG BÁO ĐỘNG (DYNAMIC NOTIFICATION)
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_search_category_default)
            .setOnlyAlertOnce(true) // Quan trọng: Tránh rung/kêu liên tục khi cập nhật
            .setOngoing(true)

        val isUiUpdating = java.util.concurrent.atomic.AtomicBoolean(true)
        var uiUpdaterJob: kotlinx.coroutines.Job? = null
        // FIX D1: Khai báo uiScope ở ngoài inner try {} để finally có thể gọi .cancel() dọn dẹp bộ nhớ
        // nếu Worker bị kill trong trường hợp bất ngờ (exception trước khi isUiUpdating.set(false))
        val uiScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
        )
        try {
            // --- TÍNH NĂNG TỰ ĐỘNG DỌN RÁC SAU 7 NGÀY ---
            // FIX #18: Lấy root WebDAV URL một cách an toàn từ SecurePrefs thay vì hardcode substring("/webdav/")
            val rootWebDavUrl = SecurePrefsHelper.getUrl(applicationContext).trimEnd('/')
            val trashUrl = "$rootWebDavUrl/.trash/"
            val now = System.currentTimeMillis()
            val sevenDaysInMillis = 7 * 24 * 60 * 60 * 1000L

            // Xóa thumbnail cũ hơn 30 ngày
            try {
                val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000L
                db.thumbnailDao().clearOldThumbnails(thirtyDaysAgo)
            } catch (e: Exception) { }

            try {
                val trashItems = webDavManager.listFiles(trashUrl)
                for (item in trashItems) {
                    if (!isActive) break
                    if (now - item.lastModified > sevenDaysInMillis) {
                        webDavManager.deleteFile(item.path)
                    }
                }
            } catch (e: Exception) { }

            // ═══════════════════════════════════════════════════
            // BIẾN THEO DÕI TIẾN TRÌNH CHÍNH XÁC
            // ═══════════════════════════════════════════════════
            val totalFilesIndexed = AtomicInteger(0)    // Tổng file đã index (Stage 1)
            val duplicateGroupsFound = AtomicInteger(0) // Số nhóm trùng lặp tìm thấy
            val hashesComputed = AtomicInteger(0)       // Số hash đã tính (Stage 2)
            val totalHashesNeeded = AtomicInteger(0)    // Tổng hash cần tính

            val currentStage = AtomicReference("Khởi động...")      // Giai đoạn hiện tại
            val currentFolder = AtomicReference("")                  // Thư mục đang quét
            val currentFileName = AtomicReference("")                // File đang xử lý
            val progressPercent = AtomicReference(0f)                // % TỔNG (0-100%)
            val currentStagePercent = AtomicReference(0f)            // % GIAI ĐOẠN HIỆN TẠI (0-100% của Stage này)
            val stageNumber = AtomicInteger(1)                       // Số thứ tự giai đoạn (1-4)
            val totalStages = 4                                      // Tổng số giai đoạn
            val stageDescription = AtomicReference("Đang kết nối tới NAS...") // Mô tả chi tiết giai đoạn

            val mediaExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "mp4", "mkv", "mov", "avi")
            val bufferMutex = Mutex()

            // FIX D9: uiScope được khai báo bên ngoài try để có thể cancel() trong finally
            uiUpdaterJob = uiScope.launch {
                val startTime = System.currentTimeMillis()
                var ticks = 0
                while (isUiUpdating.get() && isActive) {
                    val now = System.currentTimeMillis()
                    val elapsedMs = now - startTime
                    DuplicateProgressState.elapsedTime.value = elapsedMs
                    
                    val pct = progressPercent.get()
                    if (pct > 0.01f) {
                        val totalEstimatedMs = (elapsedMs / pct).toLong()
                        DuplicateProgressState.estimatedTimeRemaining.value = maxOf(0L, totalEstimatedMs - elapsedMs)
                    } else {
                        DuplicateProgressState.estimatedTimeRemaining.value = -1L
                    }

                    DuplicateProgressState.stage.value = currentStage.get()
                    DuplicateProgressState.currentFolder.value = currentFolder.get()
                    DuplicateProgressState.currentFolderUrl.value = currentFolder.get()
                    DuplicateProgressState.itemName.value = currentFileName.get()
                    DuplicateProgressState.scannedCount.value = totalFilesIndexed.get()
                    DuplicateProgressState.foundCount.value = duplicateGroupsFound.get()
                    DuplicateProgressState.hashCount.value = hashesComputed.get()
                    DuplicateProgressState.totalHashes.value = totalHashesNeeded.get()
                    DuplicateProgressState.percent.value = progressPercent.get()
                    DuplicateProgressState.currentStagePercent.value = currentStagePercent.get()
                    DuplicateProgressState.stageNumber.value = stageNumber.get()
                    DuplicateProgressState.stageDescription.value = stageDescription.get()

                    if (ticks % 20 == 0) {
                        try {
                            setProgress(workDataOf(
                                "stage" to safeWorkerText(currentStage.get()),
                                "currentFolder" to safeWorkerText(currentFolder.get(), 220),
                                "itemName" to safeWorkerText(currentFileName.get(), 180),
                                "isFolder" to false,
                                "scannedCount" to totalFilesIndexed.get(),
                                "foundCount" to duplicateGroupsFound.get(),
                                "hashCount" to hashesComputed.get(),
                                "totalHashes" to totalHashesNeeded.get(),
                                "percent" to progressPercent.get(),
                                "currentStagePercent" to currentStagePercent.get(),
                                "currentFolderUrl" to safeWorkerText(currentFolder.get(), 220),
                                "stageNumber" to stageNumber.get(),
                                "totalStages" to totalStages,
                                "stageDescription" to safeWorkerText(stageDescription.get()),
                                "elapsedTime" to DuplicateProgressState.elapsedTime.value,
                                "estimatedTimeRemaining" to DuplicateProgressState.estimatedTimeRemaining.value
                            ))
                            
                            // CẬP NHẬT THÔNG BÁO HỆ THỐNG (NOTIFICATION BAR) MỖI GIÂY
                            val pct = (progressPercent.get() * 100).toInt()
                            notificationBuilder
                                .setContentTitle("Quét rác: ${currentStage.get()} (${pct}%)")
                                .setContentText(stageDescription.get())
                                .setProgress(100, pct, false)
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        } catch (e: Exception) {}
                    }
                    ticks++
                    // FIX #11: Tăng tử 50ms lên 250ms (giảm từ 20fps xuống 4fps)
                    // 95% vòng lặp trước đây chỉ cập nhật StateFlow mà không setProgress() → lãng phí CPU
                    delay(250)
                }
            }

            coroutineScope {
                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 1: THU THẬP FILE INDEX (0% → 50%)
                // ═══════════════════════════════════════════════════
                currentStage.set("Thu thập danh sách file")
                stageNumber.set(1)
                stageDescription.set("Quét toàn bộ cây thư mục trên NAS để lập danh sách file")
                var isFastPathSuccess = false
                val apiBaseUrl = currentUrl.toApiBaseUrl()

                try {
                    val forceParam = if (forceRestart) "1" else "0"
                    val request = okhttp3.Request.Builder()
                        .url("$apiBaseUrl/api/disk/fast_index?force=$forceParam")
                        .header("Authorization", okhttp3.Credentials.basic(user, pass))
                        .build()
                    val client = NasApplication.instance.sharedHttpClient
                    val call = client.newCall(request)
                    val cancelJob = launch {
                        while (isActive) {
                            if (isStopped) { call.cancel(); break }
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    try {
                        call.execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            try {
                                val reader = android.util.JsonReader(java.io.InputStreamReader(response.body?.byteStream() ?: return@use, "UTF-8"))
                                // android.util.JsonReader KHÔNG có isLenient — bỏ qua

                                val batchBuffer = mutableListOf<CachedFile>()

                                // Xóa DB cũ sạch sẽ bằng clearAllFiles() để tránh rác cộng dồn lầm file (Lỗi 571k Nhóm trùng)
                                db.withTransaction {
                                    db.fileDao().clearAllFiles()
                                    db.checkpointDao().clearCheckpoint("DuplicateScan")
                                }

                                currentStage.set("Đang nhận dữ liệu từ NAS")
                                stageDescription.set("Nhận danh sách file nhanh qua API nội bộ NAS")
                                currentFolder.set("Fast-Path API (ổ cứng NAS)")

                                reader.beginObject()
                                var totalExpectedFiles = 0f
                                while (reader.hasNext()) {
                                    val nextKey = reader.nextName()
                                    if (nextKey == "total") {
                                        // FIX BUG #8: Type-safe parsing — handle non-int values
                                        try {
                                            totalExpectedFiles = reader.nextInt().toFloat().coerceAtLeast(1f)
                                        } catch (e: Exception) {
                                            reader.skipValue()
                                        }
                                    } else if (nextKey == "files") {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            if (!isActive) break // Thoát ngay nếu bị huỷ
                                            reader.beginObject()
                                            var name = ""
                                            var path = ""
                                            var size = 0L
                                            var mtime = 0L
                                            while (reader.hasNext()) {
                                                when (reader.nextName()) {
                                                    "name" -> name = reader.nextString()
                                                    "path" -> path = reader.nextString()
                                                    "size" -> size = reader.nextLong()
                                                    "mtime" -> mtime = reader.nextLong()
                                                    else -> reader.skipValue()
                                                }
                                            }
                                            reader.endObject()

                                            val parsedCurrentUrl = java.net.URL(currentUrl)
                                            val webDavBaseUrl = "${parsedCurrentUrl.protocol}://${parsedCurrentUrl.authority}"
                                            val encodedPath = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                                            val fullUrl = webDavBaseUrl + (if (encodedPath.startsWith("/")) encodedPath else "/$encodedPath")
                                            val parentUrl = fullUrl.substringBeforeLast("/") + "/"
                                            val parentFolderName = parentUrl.trimEnd('/').substringAfterLast("/")

                                            bufferMutex.withLock {
                                                batchBuffer.add(
                                                    CachedFile(
                                                        path = fullUrl, name = name, isDirectory = false,
                                                        contentType = "application/octet-stream", parentPath = parentUrl,
                                                        contentLength = size, lastModified = mtime
                                                    )
                                                )
                                            }

                                            val count = totalFilesIndexed.incrementAndGet()
                                            // Cập nhật tên file và thư mục đang xử lý
                                            currentFileName.set(name)
                                            currentFolder.set(parentFolderName)
                                            
                                            // Progress Stage 1: Nếu Server gửi "total", chia tỷ lệ thật xác 100%. Nếu không, dùng công thức dự phòng
                                            val stage1Progress = if (totalExpectedFiles > 0f) {
                                                (count / totalExpectedFiles).coerceIn(0f, 1f)
                                            } else {
                                                (1f - 1f / (1f + count / 500f)).coerceAtMost(0.99f) // Logarithmic fallback
                                            }
                                            currentStagePercent.set(stage1Progress)
                                            progressPercent.set(stage1Progress * 0.5f)

                                            // Flush batch mỗi 2000 files
                                            val flushBatch = mutableListOf<CachedFile>()
                                            bufferMutex.withLock {
                                                if (batchBuffer.size >= 2000) {
                                                    flushBatch.addAll(batchBuffer)
                                                    batchBuffer.clear()
                                                }
                                            }
                                            if (flushBatch.isNotEmpty()) {
                                                db.withTransaction { db.fileDao().insertFiles(flushBatch) }
                                            }
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                reader.endObject()

                                val finalFlush = mutableListOf<CachedFile>()
                                bufferMutex.withLock {
                                    if (batchBuffer.isNotEmpty()) {
                                        finalFlush.addAll(batchBuffer)
                                        batchBuffer.clear()
                                    }
                                }
                                if (finalFlush.isNotEmpty()) {
                                    db.withTransaction { db.fileDao().insertFiles(finalFlush) }
                                }

                                isFastPathSuccess = true
                                progressPercent.set(0.5f) // Stage 1 hoàn tất: 50%
                                SystemLogger.log("SUCCESS", "IndexEngine", "Hoàn tất phân tích ${totalFilesIndexed.get()} tập tin thông qua Local API.")
                            } catch (e: java.io.EOFException) {
                                SystemLogger.log("WARNING", "IndexEngine", "Luồng dữ liệu API bị ngắt kết nối: ${e.message}")
                                if (totalFilesIndexed.get() > 100) isFastPathSuccess = true // Vẫn dùng data đã nhận
                            } catch (e: Exception) {
                                SystemLogger.log("WARNING", "IndexEngine", "Lỗi giải mã luồng JSON: ${e.message}")
                            }
                        }
                    }
                    } finally {
                        cancelJob.cancel()
                    }
                } catch (e: Exception) {
                    SystemLogger.log("WARNING", "IndexEngine", "Phương thức Fast-Path không khả dụng, chuyển sang dự phòng WebDAV: ${e.message}")
                }

                // ═══════════════════════════════════════════════════
                // FALLBACK: WEBDAV CRAWLER (Nếu API nội bộ chết)
                // ═══════════════════════════════════════════════════
                if (!isFastPathSuccess) {
                    currentStage.set("Quét qua WebDAV")
                    stageDescription.set("API nội bộ không khả dụng, quét từng thư mục bằng giao thức Webdav")
                    val folderQueue = java.util.concurrent.LinkedBlockingQueue<String>()
                    val checkpoint = db.checkpointDao().getCheckpoint("DuplicateScan")

                    if (checkpoint != null && !forceRestart) {
                        folderQueue.put(checkpoint.lastProcessedFolder)
                        totalFilesIndexed.set(checkpoint.scannedCount)
                        SystemLogger.log("INFO", "DuplicateScan", "Khôi phục phiên quét dữ liệu từ đường dẫn: ${checkpoint.lastProcessedFolder}")
                    } else {
                        folderQueue.put(currentUrl)
                        db.checkpointDao().clearCheckpoint("DuplicateScan")
                    }

                    val batchBuffer = mutableListOf<CachedFile>()
                    val foldersScanned = AtomicInteger(0)
                    val totalFoldersDiscovered = AtomicInteger(1) // Bắt đầu = 1 (thư mục gốc)

                    // FIX C2 + FIX #20: Biến đếm checkpoint — chỉ ghi DB mỗi 10 thư mục hoặc mỗi 30 giây
                    // Dùng Atomic để tránh Data Race từ 3 luồng BFS chạy song song
                    val foldersSinceLastCheckpoint = java.util.concurrent.atomic.AtomicInteger(0)
                    val lastCheckpointTime = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                    val CHECKPOINT_FOLDER_INTERVAL = 10
                    val CHECKPOINT_TIME_INTERVAL_MS = 30_000L

                    val semaphore = kotlinx.coroutines.sync.Semaphore(3)  // 3 luồng BFS song song (cân bằng NAS ARM)

                    // Thuật toán duyệt BFS Đa Luồng (PHASE 8.C)
                    while (isActive) {
                        while (DuplicateProgressState.isPaused.value) { delay(500) }
                        
                        val currentFolders = mutableListOf<String>()
                        while (folderQueue.isNotEmpty()) {
                        if (!isActive) break
                            folderQueue.poll()?.let { currentFolders.add(it) }
                        }
                        
                        if (currentFolders.isEmpty()) break
                        
                        kotlinx.coroutines.coroutineScope {
                            val deferreds = currentFolders.map { folder ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        val scanned = foldersScanned.incrementAndGet()
                                        val folderDisplayName = java.net.URLDecoder.decode(folder.trimEnd('/').substringAfterLast("/"), "UTF-8")
                                        currentFolder.set(folderDisplayName)
                                        
                                        try {
                                            val files = webDavManager.listFiles(folder)
                                            val localBatch = mutableListOf<CachedFile>()
                                            
                                            for (file in files) {
                            if (!isActive) break
                                                totalFilesIndexed.incrementAndGet()
                                                currentFileName.set(file.name)
        
                                                if (file.isDirectory) {
                                                    if (file.path.trimEnd('/') != folder.trimEnd('/')) {
                                                        folderQueue.offer(file.path)
                                                        totalFoldersDiscovered.incrementAndGet()
                                                    }
                                                } else {
                                                    val ext = file.name.lowercase().substringAfterLast('.', "")
                                                    if (mediaExtensions.contains(ext)) {
                                                        localBatch.add(
                                                            CachedFile(
                                                                path = file.path, name = file.name, isDirectory = false,
                                                                contentType = file.contentType, parentPath = folder,
                                                                contentLength = file.contentLength, lastModified = file.lastModified
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                            
                                            bufferMutex.withLock {
                                                batchBuffer.addAll(localBatch)
                                            }

                                            // Cập nhật progress per-folder (mượt hơn per-BFS-level)
                                            val total = totalFoldersDiscovered.get()
                                            val stage1Prog = if (total > 0) (scanned.toFloat() / total).coerceAtMost(0.99f) else 0f
                                            currentStagePercent.set(stage1Prog)
                                            progressPercent.set(stage1Prog * 0.5f)

                                            // FIX C2 + FIX #20: Checkpoint thông minh — thread-safe
                                            val currentFoldersSince = foldersSinceLastCheckpoint.incrementAndGet()
                                            val now = System.currentTimeMillis()
                                            val lastTime = lastCheckpointTime.get()
                                            if (currentFoldersSince >= CHECKPOINT_FOLDER_INTERVAL
                                                || now - lastTime >= CHECKPOINT_TIME_INTERVAL_MS
                                            ) {
                                                db.checkpointDao().saveCheckpoint(
                                                    ScanCheckpoint("DuplicateScan", folder, totalFilesIndexed.get(), 0)
                                                )
                                                foldersSinceLastCheckpoint.set(0)
                                                lastCheckpointTime.set(now)
                                            }
                                            
                                        } catch (e: Exception) {}
                                    }
                                }
                            }
                            deferreds.awaitAll()
                        }
                        
                        // Xả Batch vào DB sau mỗi lớp BFS Level
                        val flushBatch: List<CachedFile>
                        bufferMutex.withLock {
                            flushBatch = batchBuffer.toList()
                            batchBuffer.clear()
                        }
                        if (flushBatch.isNotEmpty()) {
                            db.withTransaction { db.fileDao().insertFiles(flushBatch) }
                        }
                    }
                    
                    progressPercent.set(0.5f) // Stage 1 hoàn tất
                    currentStagePercent.set(1f)
                }

                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 2: TÌM FILE TRÙNG (50% → 60%)
                // TỐI ƯU: Dùng COUNT query + getDuplicateSizes() thay vì load toàn bộ vào RAM
                // ═══════════════════════════════════════════════════
                currentStage.set("Phân tích tệp trùng lặp (1/2)")
                stageNumber.set(2)
                stageDescription.set("Đang phân lớp sơ bộ các tệp có cùng kích thước...")
                currentStagePercent.set(0f)
                progressPercent.set(0.50f)

                // CHỈ đếm số lượng, KHÔNG load object vào RAM
                val actualDuplicatesCount = db.fileDao().countDuplicateFiles()
                // Lấy danh sách kích thước trùng (chỉ là List<Long>, rất nhẹ)
                val duplicateSizes = db.fileDao().getDuplicateSizes()
                
                if (actualDuplicatesCount > 0) {
                    currentFileName.set("Tìm thấy $actualDuplicatesCount file nghi ngờ trùng lặp (${duplicateSizes.size} nhóm kích thước)")
                    duplicateGroupsFound.set(actualDuplicatesCount)
                } else {
                    currentFileName.set("Không tìm thấy tệp trùng lặp tiềm năng.")
                    delay(500)
                    currentStagePercent.set(1f)
                    progressPercent.set(0.95f)
                }
                
                duplicateGroupsFound.set(actualDuplicatesCount)

                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 3: HASH CHÍNH XÁC (Trải đều Bước 2 & 3 từ 50% → 95%)
                // Mới: Gộp Stage 2 và Stage 3 để chia đều thanh tiến trình. Nửa đầu số nhóm tính cho Stage 2, nửa sau cho Stage 3.
                // ═══════════════════════════════════════════════════
                if (duplicateSizes.isNotEmpty()) {
                    totalHashesNeeded.set(actualDuplicatesCount)
                    val client = NasApplication.instance.fastApiClient
                    val isLightningMode = inputData.getBoolean("lightningMode", true)

                    val totalSizeGroups = duplicateSizes.size
                    var processedGroups = 0
                    // SỐ LƯỢNG KẾT NỐI DB TỐI ƯU (Batch 1000)
                    val pendingHashUpdates = mutableListOf<Pair<String, String>>()
                    val pendingHashCacheUpdates = mutableListOf<HashCache>()
                    val hashBufferMutex = Mutex()

                    // BATCH 50 NHÓM MỘT LÚC CHO CSDL & MẠNG LƯỚI
                    val BATCH_SIZE = 50
                    val chunkedSizes = duplicateSizes.chunked(BATCH_SIZE)
                    
                    for (batchSizes in chunkedSizes) {
                        if (!isActive) break
                        while (DuplicateProgressState.isPaused.value) { delay(500) }
                        
                        // 1. Tải 50 nhóm file trong 1 truy vấn SQL duy nhất (Giảm 50x CSDL)
                        val batchFiles = db.fileDao().getFilesBySizes(batchSizes).groupBy { it.contentLength }
                        
                        val filesNeedHash = mutableListOf<CachedFile>()
                        var currentBatchGroupsSize = 0
                        
                        // 2. Phân loại nội bộ từng nhóm
                        for (size in batchSizes) {
                            val group = batchFiles[size]
                            if (group == null || group.size < 2) continue
                            
                            currentBatchGroupsSize += group.size
                            
                            val anchorFile = group.firstOrNull { it.imageFingerprint != null && it.imageFingerprint != "NOT_SUPPORTED" }
                            if (anchorFile != null) {
                                val anchorFP = anchorFile.imageFingerprint ?: continue
                                for (file in group) {
                                if (!isActive) break
                                    val cachedHash = db.hashCacheDao().getHash(file.path, file.contentLength, file.lastModified)
                                    if (cachedHash != null) {
                                        pendingHashUpdates.add(Pair(file.path, cachedHash))
                                        continue
                                    }
                                    if (file.imageFingerprint != null && file.imageFingerprint != "NOT_SUPPORTED") {
                                        if (ImageFingerprint.isSimilar(anchorFP, file.imageFingerprint)) {
                                            pendingHashUpdates.add(Pair(file.path, "FINGERPRINT_$anchorFP"))
                                        } else {
                                            filesNeedHash.add(file)
                                        }
                                    } else {
                                        filesNeedHash.add(file)
                                    }
                                }
                            } else {
                                for (file in group) {
                                    val cachedHash = db.hashCacheDao().getHash(file.path, file.contentLength, file.lastModified)
                                    if (cachedHash != null) {
                                        pendingHashUpdates.add(Pair(file.path, cachedHash))
                                    } else {
                                        filesNeedHash.add(file)
                                    }
                                }
                            }
                        }

                        // 3. XỬ LÝ HASH MẠNG LƯỚI CHO TOÀN BỘ 50 NHÓM (CẢ TRĂM FILE) GỘP TRONG 1 REQUEST (Giảm 50x Network)
                        if (filesNeedHash.isNotEmpty()) {
                            if (isLightningMode) {
                                for (file in filesNeedHash) {
                            if (!isActive) break
                                    val pseudoHash = "LGH_${file.contentLength}_${file.lastModified}"
                                    pendingHashUpdates.add(Pair(file.path, pseudoHash))
                                }
                            } else {
                                try {
                                    val jsonArray = org.json.JSONArray()
                                    filesNeedHash.forEach { file ->
                                        val rootWebDav = currentUrl.trimEnd('/')
                                        val relativePath = if (file.path.startsWith(rootWebDav)) file.path.substring(rootWebDav.length) else file.path
                                        val fileObj = org.json.JSONObject().apply {
                                            put("path", file.path)
                                            put("local_path", relativePath)
                                        }
                                        jsonArray.put(fileObj)
                                    }
                                    val jsonString = org.json.JSONObject().put("files", jsonArray).toString()
                                    val requestBody = jsonString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                                    // FIX #14: Thêm Authorization header — hash_batch API yêu cầu auth
                                    val request = okhttp3.Request.Builder()
                                        .url("$apiBaseUrl/api/disk/hash_batch")
                                        .header("Authorization", okhttp3.Credentials.basic(user, pass))
                                        .post(requestBody)
                                        .build()
                                    val call = client.newCall(request)
                                    val cancelJob = launch {
                                        while (isActive) {
                                            if (isStopped) { call.cancel(); break }
                                            kotlinx.coroutines.delay(1000)
                                        }
                                    }
                                    try {
                                        call.execute().use { response ->
                                        if (response.isSuccessful) {
                                            val responseBody = response.body?.string() ?: "{}"
                                            val resultObj = org.json.JSONObject(responseBody)
                                            filesNeedHash.forEach { file ->
                                                val hash: String = resultObj.optString(file.path, "")
                                                if (hash.isNotEmpty()) {
                                                    pendingHashUpdates.add(Pair(file.path, hash))
                                                    pendingHashCacheUpdates.add(HashCache(file.path, file.contentLength, file.lastModified, hash))
                                                }
                                            }
                                        } else {
                                            // SONG SONG HOA: Hash qua WebDAV voi 3 coroutine dong thoi
                                            val hashSemaphore = kotlinx.coroutines.sync.Semaphore(3)
                                            kotlinx.coroutines.coroutineScope {
                                                filesNeedHash.map { dup ->
                                                    async(Dispatchers.IO) {
                                                        hashSemaphore.withPermit {
                                                            hashViaWebDavBuffered(webDavManager, pendingHashUpdates, pendingHashCacheUpdates, dup, hashBufferMutex)
                                                        }
                                                    }
                                                }.awaitAll()
                                            }
                                        }
                                    }
                                    } finally {
                                        cancelJob.cancel()
                                    }
                                } catch (e: Exception) {
                                    // SONG SONG HOA fallback
                                    val hashSemaphore = kotlinx.coroutines.sync.Semaphore(3)
                                    kotlinx.coroutines.coroutineScope {
                                        filesNeedHash.map { dup ->
                                            async(Dispatchers.IO) {
                                                hashSemaphore.withPermit {
                                                    hashViaWebDavBuffered(webDavManager, pendingHashUpdates, pendingHashCacheUpdates, dup, hashBufferMutex)
                                                }
                                            }
                                        }.awaitAll()
                                    }
                                }
                            }
                        }
                        
                        // KÍCH HOẠT FLUSH BUFFER VÀO DB NẾU ĐẠT 1000 file (Rút ngắn thời gian từ 30 phút xuống 10 giây)
                        if (pendingHashUpdates.size >= 1000) {
                            val chunk = pendingHashUpdates.toList()
                            val cacheChunk = pendingHashCacheUpdates.toList()
                            pendingHashUpdates.clear()
                            pendingHashCacheUpdates.clear()
                            db.withTransaction {
                                for (update in chunk) {
                                    db.fileDao().updatePartialHash(update.first, update.second)
                                }
                                if (cacheChunk.isNotEmpty()) {
                                    db.hashCacheDao().insertHashes(cacheChunk)
                                }
                            }
                        }

                        // Cập nhật progress động cho Bước 2 và Bước 3
                        processedGroups += batchSizes.size
                        hashesComputed.addAndGet(currentBatchGroupsSize)
                        
                        val isStage2 = processedGroups <= (totalSizeGroups / 2).coerceAtLeast(1)
                        if (isStage2) {
                            currentStage.set("Phân tích file lặp (1/2)")
                            stageNumber.set(2)
                            stageDescription.set("Phân lớp sơ bộ nhóm dữ liệu ${processedGroups}/${totalSizeGroups / 2}...")
                            
                            val halfGroupTarget = (totalSizeGroups / 2).coerceAtLeast(1).toFloat()
                            currentStagePercent.set((processedGroups / halfGroupTarget).coerceIn(0f, 1f))
                        } else {
                            currentStage.set("Xác minh mã băm (2/2)")
                            stageNumber.set(3)
                            stageDescription.set("Xác nhận mã băm an toàn nhóm thứ ${processedGroups - totalSizeGroups / 2}...")
                            
                            val stage3Target = (totalSizeGroups - totalSizeGroups / 2).coerceAtLeast(1).toFloat()
                            val processedInStage3 = (processedGroups - totalSizeGroups / 2).toFloat()
                            currentStagePercent.set((processedInStage3 / stage3Target).coerceIn(0f, 1f))
                        }

                        val progress = processedGroups.toFloat() / totalSizeGroups
                        progressPercent.set(0.5f + progress * 0.45f)
                        currentFileName.set("Đang xử lý ${processedGroups}/$totalSizeGroups nhóm...")
                    }
                    
                    // XỬ LÝ NỐT PHẦN BUFFER CÒN SÓT LẠI TRONG RAM KHI VÒNG LẶP KẾT THÚC
                    if (pendingHashUpdates.isNotEmpty()) {
                        db.withTransaction {
                            for (update in pendingHashUpdates) {
                                db.fileDao().updatePartialHash(update.first, update.second)
                            }
                            if (pendingHashCacheUpdates.isNotEmpty()) {
                                db.hashCacheDao().insertHashes(pendingHashCacheUpdates)
                            }
                        }
                    }

                    currentStagePercent.set(1f)
                    progressPercent.set(0.95f)
                    if (isLightningMode) {
                        SystemLogger.log("INFO", "HashEngine", "Chế độ tối ưu (Lightning Mode): Phân tích băm (hash) tốc độ cao cho ${actualDuplicatesCount} tệp.")
                    }
                }

                // SỬA LỖI: Không dùng cancel() để dừng job vì nó sẽ để lại một Future Pending của WorkManager
                isUiUpdating.set(false)
                uiUpdaterJob.join()

                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 4: TỔNG HỢP KẾT QUẢ (95% → 100%)
                // Hiển thị UI rõ ràng cho người dùng thấy đang tổng hợp
                // ═══════════════════════════════════════════════════
                currentStage.set("Đang tổng hợp kết quả...")
                stageNumber.set(4)
                stageDescription.set("Đang tổng hợp và đếm kết quả từ cơ sở dữ liệu...")
                currentStagePercent.set(0f)
                progressPercent.set(0.95f)
                DuplicateProgressState.stage.value = "Đang tổng hợp kết quả..."
                DuplicateProgressState.stageNumber.value = 4
                DuplicateProgressState.stageDescription.value = "Đang tổng hợp và đếm kết quả từ cơ sở dữ liệu..."
                delay(100) // Cho UI kịp vẽ ra bước mới

                // Đếm lại duplicate groups chính xác từ DB (CHỈ ĐẾM, không load object)
                val finalDuplicateCount = db.fileDao().countDuplicateFiles()
                
                currentStage.set("Hoàn tất")
                stageDescription.set("Hoàn tất. Đã quét ${totalFilesIndexed.get()} tệp, tìm thấy $finalDuplicateCount tệp trùng.")
                progressPercent.set(1f)
                currentStagePercent.set(1f)
                DuplicateProgressState.stage.value = "Hoàn tất"
                DuplicateProgressState.stageDescription.value = "Hoàn tất!"

                setProgress(workDataOf(
                    "stage" to "Hoàn tất",
                    "itemName" to "Quét xong!",
                    "scannedCount" to totalFilesIndexed.get(),
                    "foundCount" to finalDuplicateCount,
                    "percent" to 1f,
                    "currentStagePercent" to 1f,
                    "currentFolderUrl" to "Hoàn tất quét ${totalFilesIndexed.get()} tệp",
                    "stageNumber" to 4,
                    "totalStages" to totalStages,
                    "stageDescription" to "Hoàn tất! Đã quét ${totalFilesIndexed.get()} tệp."
                ))
                
                // PHASE 6: Hiển thị Push Notification nếu có rác
                if (finalDuplicateCount > 0) {
                    UploadNotificationHelper.showDuplicateFoundNotification(applicationContext, finalDuplicateCount)
                } else {
                    notificationBuilder
                        .setContentTitle("Dọn rác hoàn tất")
                        .setContentText("Không tìm thấy tệp trùng lặp mới.")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }
            }

            db.checkpointDao().clearCheckpoint("DuplicateScan")
            return@withContext Result.success()

        } catch (e: Exception) {
            try {
                SystemLogger.log("ERROR", "DuplicateScan", "Lỗi tiến trình quét dữ liệu: ${e.message}")
            } catch (ex: Exception) {}
            return@withContext Result.failure()
        } finally {
            isUiUpdating.set(false)
            // Chờ UI updater tự thoát sau khi isUiUpdating = false (vòng lặp kiểm tra flag này)
            uiUpdaterJob?.join()
            // FIX D1: Thực sự cancel uiScope để giải phóng tất cả coroutine trong scope
            uiScope.cancel()
        }
    }

    /** Helper: Hash từng file qua WebDAV (ETag hoặc partial hash) TỐI ƯU HÓA BẰNG BUFFER RAM
     *  Thread-safe: dùng Mutex để đồng bộ ghi vào buffer khi chạy song song */
    private suspend fun hashViaWebDavBuffered(webDavManager: WebDavManager, buffer: MutableList<Pair<String,String>>, bufferHashCache: MutableList<HashCache>, dup: CachedFile, mutex: Mutex? = null) {
        try {
            val headHeaders = webDavManager.headFileHeaders(dup.path)
            val eTag = headHeaders?.get("ETag")?.replace("\"", "")
            val finalHash = if (!eTag.isNullOrEmpty() && eTag.length >= 8) eTag else webDavManager.getPartialHashStream(dup.path)
            
            if (finalHash != null) {
                if (mutex != null) {
                    mutex.withLock {
                        buffer.add(Pair(dup.path, finalHash))
                        bufferHashCache.add(HashCache(dup.path, dup.contentLength, dup.lastModified, finalHash))
                    }
                } else {
                    buffer.add(Pair(dup.path, finalHash))
                    bufferHashCache.add(HashCache(dup.path, dup.contentLength, dup.lastModified, finalHash))
                }
            }
        } catch (_: Exception) {}
    }

}

// ════════════════════════════════════════════════════════════════════════════
// NasWorker — Lớp cha chung cho tất cả Worker giao tiếp NAS
// ════════════════════════════════════════════════════════════════════════════

abstract class NasWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    protected fun makeForegroundInfo(
        channelId: String, channelName: String, notificationId: Int, title: String
    ): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title).setSmallIcon(android.R.drawable.ic_popup_sync).setOngoing(true).build()
        // Tu Android 10 (Q) tro len, neu manifest da khai bao foregroundServiceType
        // thi ForegroundInfo BAT BUOC phai truyen service type tuong ung - thieu
        // se nem MissingForegroundServiceTypeException khi setForeground() chay,
        // crash sync worker giua chung.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    protected fun safeWorkerText(value: String, maxChars: Int = 512): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "..."
    }

    // FIX D2: Convert thành suspend fun để loại bỏ runBlocking không cần thiết.
    // SmartNetworkManager.getActiveBaseUrl() là suspend fun — khi loadWebDavManager() là suspend,
    // ta có thể gọi trực tiếp mà không cần runBlocking wrapper.
    // Tất cả callsite đều nằm trong withContext(IO) nên đã là suspend context.
    protected suspend fun loadWebDavManager(): WebDavManager? {
        val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (url.isEmpty() || user.isEmpty()) return null
        val manager = WebDavManager
        manager.connect(url, user, pass)
        return manager
    }

    protected suspend fun setThumbnailActivity(source: String, active: Boolean) = withContext(Dispatchers.IO) {
        val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) return@withContext
        try {
            val body = JSONObject()
                .put("source", source)
                .put("active", active)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("${url.toApiBaseUrl()}/api/thumb/activity")
                .post(body)
                .header("Authorization", okhttp3.Credentials.basic(user, pass))
                .build()
            NasApplication.instance.sharedHttpClient.newCall(request).execute().use { }
        } catch (_: Exception) {
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// OfflineSyncWorker — Xử lý thao tác offline đã xếp hàng
// ════════════════════════════════════════════════════════════════════════════

class OfflineSyncWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = (applicationContext as NasApplication).database
        val pendingActions = db.syncActionDao().getAllPendingActions()
        if (pendingActions.isEmpty()) return@withContext Result.success()
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        // FIX D2b: Đã trong withContext(IO) → gọi suspend fun trực tiếp, không cần runBlocking
        val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        if (user.isEmpty() || pass.isEmpty() || url.isEmpty()) return@withContext Result.failure()
        val webDavManager = WebDavManager.apply { connect(url, user, pass) }
        setThumbnailActivity("sync", true)
        try {
            var allSuccess = true
            for (action in pendingActions) {
                try {
                    when (action.actionType) {
                        "DELETE" -> webDavManager.deleteFile(action.sourcePath)
                        "CREATE_FOLDER" -> webDavManager.createFolder(action.sourcePath)
                        "RENAME", "MOVE" -> {
                            if (action.destPath != null) {
                                val encodedDest = action.destPath.split("/").joinToString("/") { segment ->
                                    if (segment.isEmpty() || segment.contains(":")) segment
                                    else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                                }
                                webDavManager.renameFile(action.sourcePath, encodedDest)
                            }
                        }
                        "UPLOAD" -> {
                            if (action.destPath != null) {
                                val file = java.io.File(action.sourcePath)
                                if (file.exists()) {
                                    val ext = file.extension.lowercase()
                                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                                    val encodedDest = action.destPath.split("/").joinToString("/") { segment ->
                                        if (segment.isEmpty() || segment.contains(":")) segment
                                        else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                                    }
                                    webDavManager.uploadFile(encodedDest, file, mime)
                                }
                            }
                        }
                    }
                    db.syncActionDao().deleteById(action.id)
                } catch (_: Exception) {
                    allSuccess = false
                }
            }
            if (allSuccess) Result.success() else Result.retry()
        } finally {
            setThumbnailActivity("sync", false)
        }
    }
}
// ════════════════════════════════════════════════════════════════════════════
// UploadWorker + UploadNotificationHelper
// ════════════════════════════════════════════════════════════════════════════

    // Đã xoá UploadWorker theo yêu cầu

object UploadNotificationHelper {
    private const val CHANNEL_ID = "upload_progress"
    private const val NOTIFICATION_ID = 9001
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tiến trình tải lên", NotificationManager.IMPORTANCE_LOW).apply { description = "Hiển thị tiến trình tải tệp lên NAS"; setShowBadge(false) }
            (context.getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }
    }
    fun showProgress(context: Context, fileName: String, completed: Int, total: Int, currentPercent: Int = -1) {
        createChannel(context)
        val filePercent = if (total > 0) (completed * 100 / total) else 0
        val text = if (currentPercent >= 0) "Đang tải: $currentPercent% - $fileName" else "$fileName ($completed/$total)"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Đang tải lên NAS...").setContentText(text).setProgress(100, if (currentPercent >= 0) currentPercent else filePercent, false).setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) {}
    }
    fun showComplete(context: Context, successCount: Int, failCount: Int) {
        createChannel(context)
        val text = if (failCount > 0) "Thành công: $successCount | Lỗi: $failCount" else "Tất cả $successCount tệp đã tải lên thành công!"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Tải lên hoàn tất ✅").setContentText(text).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) {}
    }
    fun dismiss(context: Context) { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    fun showDuplicateFoundNotification(context: Context, duplicateCount: Int) {
        createChannel(context)
        val intent = android.content.Intent(context, MainActivity::class.java).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK; putExtra("SHOW_DUPLICATES", true) }
        val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Phát hiện tệp trùng lặp").setContentText("Tìm thấy $duplicateCount nhóm tệp trùng trên NAS. Nhấn để dọn dẹp và giải phóng dung lượng.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent).setAutoCancel(true)
        try { NotificationManagerCompat.from(context).notify(9002, builder.build()) } catch (_: SecurityException) {}
    }
}

// ════════════════════════════════════════════════════════════════════════════
// AutoBackupWorker + FingerprintWorker
// ════════════════════════════════════════════════════════════════════════════

class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try { setForeground(makeForegroundInfo("auto_backup_channel", "Auto Backup", 9903, "Auto Backup đang chạy...")) } catch (_: Exception) {}
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NASWebDAV:AutoBackupWakeLock")
        // FIX #23: Giảm WakeLock từ 3 tiếng xuống 60 phút — backup tối đa 1 giờ là hợp lý
        // Nếu upload bị trẾ (server không phản hồi), thiết bị ko bị hao pin đến 3 tiếng
        wakeLock.acquire(60 * 60 * 1000L)
        // FIX D2b: Đã trong withContext(IO) → gọi suspend fun trực tiếp, không cần runBlocking
        val baseUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val settingsPrefs = SecurePrefsHelper.getSettingsPrefs(applicationContext)
        val deleteAfterBackup = settingsPrefs.getBoolean("delete_after_backup", false)
        val webDavManager = loadWebDavManager() ?: run {
            // FIX leak: tra wakelock truoc khi return som -> tranh giu pin 60' khi NAS offline.
            if (wakeLock.isHeld) wakeLock.release()
            return@withContext Result.failure()
        }
        if (runAttemptCount >= 3) {
            SystemLogger.log("ERROR", "AutoBackup", "Đã ghi nhận $runAttemptCount lần thực thi thất bại.")
            // FIX leak: tra wakelock truoc khi return som khi het quota retry.
            if (wakeLock.isHeld) wakeLock.release()
            return@withContext Result.failure()
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
                        } catch (e: Exception) {
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
                        val fileName = cursor.getString(nameIndex) ?: continue
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

                            applicationContext.contentResolver.openInputStream(ContentUris.withAppendedId(mediaUri, id))?.use { input ->
                                webDavManager.uploadStreamWithProgress(targetFileNasPath, input, fileSize, mimeType) { bytesWritten, totalBytes ->
                                    val now = System.currentTimeMillis()
                                    // Giảm throttle từ 500ms xuống 200ms để % nhảy mượt hơn (5 FPS) thay vì giật cục
                                    if (now - lastProgressTime > 200 || bytesWritten == totalBytes) {
                                        lastProgressTime = now
                                        val percent = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f
                                        setProgressAsync(workDataOf(
                                            "fileName" to safeWorkerText(fileName, 180),
                                            "sourcePath" to safeWorkerText(dataPath, 220),
                                            "destPath" to safeWorkerText(targetFileNasPath, 220),
                                            "progress" to percent,
                                            "processedCount" to processedFilesCount,
                                            "totalCount" to totalFilesToProcess,
                                            "elapsedTime" to (now - startTime)
                                        ))
                                        // Update Foreground Notification Progress
                                        try {
                                            val progressInt = (percent * 100).toInt()
                                            val notificationBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, "auto_backup_channel")
                                                .setSmallIcon(android.R.drawable.ic_menu_upload)
                                                .setContentTitle("Đang sao lưu lên NAS: $progressInt%")
                                                .setContentText(safeWorkerText("$fileName\n$parentRelativePath", 120))
                                                .setProgress(100, progressInt, false)
                                                .setOnlyAlertOnce(true)
                                                .setSilent(true)
                                                .setOngoing(true)
                                                
                                            // Sử dụng NotificationManager thay vì setForegroundAsync để cập nhật nhanh theo thời gian thực (tránh delay của WorkManager)
                                            androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(9903, notificationBuilder.build())
                                        } catch (_: Exception) {}
                                    }
                                }
                            }

                            val uploadVerified = try { webDavManager.headFileHeaders(targetFileNasPath) != null } catch (_: Exception) { false }
                            if (fileHash != null && uploadVerified) db.fingerprintDao().insertFingerprint(FileFingerprint(filePath = targetFileNasPath, hash = fileHash, fileName = fileName, fileSize = fileSize))
                            if (deleteAfterBackup && uploadVerified) applicationContext.contentResolver.delete(ContentUris.withAppendedId(mediaUri, id), null, null)
                            backupCount++
                        } catch (e: Exception) { 
                            failedCount++
                            if (e !is java.io.FileNotFoundException && !(e.message ?: "").contains("Missing file")) SystemLogger.log("WARNING", "AutoBackup", "Lỗi tải xuống tập tin $fileName: ${e.message}") 
                        }
                        // Nhường luồng cho CPU để tránh văng app do tác vụ I/O nặng
                        kotlinx.coroutines.yield()
                    }
                }
            }
            val logMessage = "Đồng bộ khép kín: Thành công $backupCount tệp, Bỏ qua $skippedCount tệp tập tin trùng lặp, Thất bại: $failedCount tệp."
            if (backupCount > 0 || failedCount > 0) {
                SystemLogger.log(if (failedCount > 0) "WARNING" else "SUCCESS", "AutoBackup", logMessage)
            } else {
                SystemLogger.log("INFO", "AutoBackup", logMessage)
            }
            
            AutoBackupState.resultTotal.value = totalFilesToProcess
            AutoBackupState.resultSuccess.value = backupCount
            AutoBackupState.resultSkipped.value = skippedCount
            AutoBackupState.resultFailed.value = failedCount
            AutoBackupState.showResultDialog.value = true
            
            return@withContext Result.success()
        } catch (e: Exception) {
            SystemLogger.log("ERROR", "AutoBackup", "Lỗi luồng xử lý Đồng bộ tự động (AutoBackup): ${e.message}")
            val isTransient = e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException
            return@withContext if (isTransient && runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            setThumbnailActivity("sync", false)
            if (wakeLock.isHeld) wakeLock.release()
            try { androidx.core.app.NotificationManagerCompat.from(applicationContext).cancel(9903) } catch (_: Exception) {}
        }
    }
}

class FingerprintWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as NasApplication; val db = app.database
        val webDavManager = loadWebDavManager() ?: return@withContext Result.failure()
        try {
            val filesToProcess = db.fileDao().getFilesWithoutFingerprint()
            if (filesToProcess.isEmpty()) { SystemLogger.log("INFO", "FingerprintWorker", "Không phát hiện tập tin yêu cầu tạo chữ ký số (fingerprint)."); return@withContext Result.success() }
            SystemLogger.log("INFO", "FingerprintWorker", "Khởi tạo quá trình cấp phát chữ ký số cho ${filesToProcess.size} tập tin...")
            var successCount = 0; var failCount = 0
            // FIX D2c: Đã trong withContext(IO) → gọi suspend fun trực tiếp
            val savedUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
                .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
            val savedUser = SecurePrefsHelper.getUser(applicationContext)
            val savedPass = SecurePrefsHelper.getPass(applicationContext)
            if (savedUrl.isEmpty() || savedUser.isEmpty()) return@withContext Result.failure()
            val apiBaseUrl = savedUrl.toApiBaseUrl()
            for (file in filesToProcess) {
                if (isStopped) break
                try {
                    // FIX #15: Uri.encode(file.path) encode toàn bộ URL thành http%3A%2F%2F...
                    // API /api/thumb?path= chỉ cần phần path (/webdav/img.jpg), không phải full URL.
                    // Tách path từ URL đầy đủ, sau đó encode chỉ phần path đó.
                    val pathOnly = try {
                        java.net.URL(file.path).path  // "/webdav/photos/img.jpg"
                    } catch (_: Exception) {
                        file.path  // fallback nếu đã là path thuần
                    }
                    val encodedPath = java.net.URLEncoder.encode(pathOnly, "UTF-8").replace("+", "%20")
                    val url = "$apiBaseUrl/api/thumb?path=$encodedPath"
                    val request = okhttp3.Request.Builder().url(url).header("Authorization", okhttp3.Credentials.basic(savedUser, savedPass)).build()
                    app.fastApiClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { inputStream ->
                                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                                if (bitmap != null) { try { val aHash = ImageFingerprint.computeAHash(bitmap); if (aHash != null) { db.fileDao().updateImageFingerprint(file.path, aHash); successCount++ } else failCount++ } finally { bitmap.recycle() } }
                                else failCount++
                            } ?: run { failCount++ }
                        } else { db.fileDao().updateImageFingerprint(file.path, "NOT_SUPPORTED"); failCount++ }
                    }
                    delay(200)
                } catch (_: Exception) { failCount++; delay(1000) }
            }
            SystemLogger.log("SUCCESS", "FingerprintWorker", "Hoàn tất quá trình cấp phát chữ ký số. Thành công: $successCount, Thất bại: $failCount")
            return@withContext Result.success()
        } catch (e: Exception) { SystemLogger.log("ERROR", "FingerprintWorker", "Lỗi: ${e.message}"); return@withContext Result.failure() }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// AutoDuplicateScanWorker — Dọn rác tự động định kỳ
// ════════════════════════════════════════════════════════════════════════════

class AutoDuplicateScanWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = NasApplication.instance.database
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        // FIX D2c: Đã trong withContext(IO) → gọi suspend fun trực tiếp
        val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }

        // ── GATE 1: KHUNG GIO ──
        // Chi cho phep auto-scan trong khung 2h-5h sang (sat 3 AM). Ngoai khung
        // -> retry sau ~1h. NAS user dang ngu, network/CPU thuong ranh.
        val nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (nowHour !in 2..5) {
            SystemLogger.log("INFO", "AutoClean", "Tạm hoãn: Ngoài khung giờ bảo trì 2h-5h sáng (hiện tại ${nowHour}h). Sẽ thử lại sau.")
            return@withContext Result.retry()
        }

        // ── GATE 2: NAS RANH ──
        // Goi /api/system/idle de check CPU+load+RAM+livestream. Neu khong ranh ->
        // poll tiep moi 5 phut, max 25 phut. Sau do retry.
        val apiBaseForIdle = url.toApiBaseUrl()
        var idleOk = false
        var attempts = 0
        while (attempts < 5 && !isStopped) {
            attempts++
            try {
                val idleReq = okhttp3.Request.Builder()
                    .url("$apiBaseForIdle/api/system/idle")
                    .header("Authorization", okhttp3.Credentials.basic(user, pass))
                    .build()
                val idleResp = NasApplication.instance.fastApiClient.newCall(idleReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: break
                val idleJson = org.json.JSONObject(idleResp)
                if (idleJson.optBoolean("idle", false)) {
                    idleOk = true; break
                }
                val reason = idleJson.optString("reason", "không rõ")
                SystemLogger.log("INFO", "AutoClean", "Hệ thống đang chịu tải (${reason}) — tạm hoãn 5 phút, tiến hành kiểm tra lại (lần thứ $attempts/5)")
                kotlinx.coroutines.delay(5 * 60 * 1000L)
            } catch (e: Exception) {
                SystemLogger.log("WARNING", "AutoClean", "Lỗi kết nối /api/system/idle: ${e.message}")
                break
            }
        }
        if (!idleOk) {
            SystemLogger.log("INFO", "AutoClean", "Hệ thống không đạt trạng thái rảnh sau 25 phút chờ — phiên bảo trì bị huỷ bỏ, sẽ thực thi ở chu kỳ kế tiếp.")
            return@withContext Result.retry()
        }

        val webDavManager = loadWebDavManager() ?: return@withContext Result.failure()
        val startTime = System.currentTimeMillis()

        // ── LIVE THROTTLE ──
        // Background job kiem tra /api/system/idle moi 60s. Neu NAS tro nen ban
        // (vd user mo Plex stream, livestream khoi, etc.) -> auto-pause quet de
        // tranh tranh CPU/IO. Khi NAS ranh lai -> auto-resume.
        // Co ghi nho `wasAutoPaused` de KHONG resume khi user manually paused.
        var wasAutoPaused = false
        val throttleJob = launch(Dispatchers.IO) {
            while (isActive && !isStopped) {
                try {
                    val idleReq = okhttp3.Request.Builder()
                        .url("$apiBaseForIdle/api/system/idle")
                        .header("Authorization", okhttp3.Credentials.basic(user, pass))
                        .build()
                    val body = NasApplication.instance.fastApiClient.newCall(idleReq).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                    if (body != null) {
                        val isIdle = org.json.JSONObject(body).optBoolean("idle", false)
                        if (!isIdle && !DuplicateProgressState.isPaused.value) {
                            DuplicateProgressState.isPaused.value = true
                            wasAutoPaused = true
                            SystemLogger.log("INFO", "AutoClean", "Hệ thống đang chịu tải — tạm dừng tiến trình quét tự động")
                        } else if (isIdle && DuplicateProgressState.isPaused.value && wasAutoPaused) {
                            DuplicateProgressState.isPaused.value = false
                            wasAutoPaused = false
                            SystemLogger.log("INFO", "AutoClean", "Hệ thống đạt trạng thái rảnh — tiếp tục phiên quét")
                        }
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(60 * 1000L)
            }
        }

        try {
            SystemLogger.log("INFO", "AutoClean", "Khởi chạy tiến trình phân tích và dọn dẹp tập tin trùng lặp định kỳ.")
            db.logDao().insertLog(SystemLog(type = "INFO", module = "DuplicateScan", message = "Hệ thống đã tự động chạy lịch dọn dẹp trùng lặp định kỳ"))
            // FIX #24: deleteByParentPath("%") không xóa gì vì WHERE parentPath = '%' chỉ khớp
            // row có parentPath đúng bằng chuỗi %, không phải LIKE. Dùng clearAllFiles() để xóa sạch.
            db.withTransaction { db.fileDao().clearAllFiles() }
            var totalFiles = 0
            val apiBaseUrl = url.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/fast_index").header("Authorization", okhttp3.Credentials.basic(user, pass)).build()
            val client = NasApplication.instance.sharedHttpClient
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val reader = android.util.JsonReader(response.body?.charStream())
                    // FIX #8: android.util.JsonReader KHÔNG có isLenient public trên mọi API level
                    // Comment ở DuplicateScanWorker line 220 đã xác nhận việc này — xóa dòng tránh crash
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (key == "total") { reader.nextInt() }
                        else if (key == "files") {
                            reader.beginArray(); val batch = mutableListOf<CachedFile>()
                            while (reader.hasNext()) {
                                reader.beginObject(); var name = ""; var path = ""; var size = 0L; var mtime = 0L
                                while (reader.hasNext()) { when (reader.nextName()) { "name" -> name = reader.nextString(); "path" -> path = reader.nextString(); "size" -> size = reader.nextLong(); "mtime" -> mtime = reader.nextLong(); else -> reader.skipValue() } }
                                reader.endObject()
                                val rootUrl = url.trimEnd('/'); val absolutePath = rootUrl + (if (path.startsWith("/")) path else "/$path"); val parentUrl = absolutePath.substringBeforeLast("/") + "/"
                                batch.add(CachedFile(path = absolutePath, name = name, isDirectory = false, contentType = "application/octet-stream", parentPath = parentUrl, contentLength = size, lastModified = mtime))
                                totalFiles++
                                if (batch.size >= 2000) { db.withTransaction { db.fileDao().insertFiles(batch) }; batch.clear() }
                            }
                            reader.endArray()
                            if (batch.isNotEmpty()) db.withTransaction { db.fileDao().insertFiles(batch) }
                        } else reader.skipValue()
                    }
                    reader.endObject()
                } else throw Exception("Không thể kết nối FastPath API")
            }
            val duplicateSizes = db.fileDao().getDuplicateSizes()
            var movedCount = 0; var savedBytes = 0L; var totalDuplicatesFound = 0
            for (size in duplicateSizes) {
                val group = db.fileDao().getFilesBySize(size)
                if (group.size < 2 || group.first().contentLength < 1024L) continue
                val paths = group.map { it.path }; val hashResult = mutableMapOf<String, String>()
                try {
                    val fastClient = NasApplication.instance.fastApiClient; val jsonArray = JSONArray()
                    val apiBase = webDavManager.currentBaseUrl.toApiBaseUrl()
                    val rootWebDav = webDavManager.currentBaseUrl.trimEnd('/')
                    paths.forEach { path -> jsonArray.put(JSONObject().apply { put("path", path); put("local_path", if (path.startsWith(rootWebDav)) path.substring(rootWebDav.length) else path) }) }
                    val reqBody = JSONObject().put("files", jsonArray).toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                    val req = okhttp3.Request.Builder().url("$apiBase/api/disk/hash_batch").post(reqBody).header("Authorization", okhttp3.Credentials.basic(user, pass)).build()
                    fastClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) { val resultObj = JSONObject(resp.body?.string() ?: "{}"); for (path in paths) { val hash = resultObj.optString(path, ""); if (hash.isNotEmpty()) hashResult[path] = hash } }
                        else throw Exception("Non 2xx")
                    }
                } catch (_: Exception) { for (file in group) hashResult[file.path] = "LGH_${file.contentLength}_${file.lastModified}" }
                val hashGroups = mutableMapOf<String, MutableList<CachedFile>>()
                for (file in group) { val hash = hashResult[file.path]; if (!hash.isNullOrEmpty()) hashGroups.getOrPut(hash) { mutableListOf() }.add(file) }
                for ((_, identicalFiles) in hashGroups) {
                    if (identicalFiles.size > 1) {
                        val sorted = identicalFiles.sortedWith(compareBy({ it.path.length }, { it.lastModified }))
                        val filesToTrash = sorted.drop(1); totalDuplicatesFound += filesToTrash.size
                        for (trashFile in filesToTrash) { if (moveFileToTrash(webDavManager, trashFile.path, user, pass)) { movedCount++; savedBytes += trashFile.contentLength } }
                    }
                }
            }
            val durationMin = (System.currentTimeMillis() - startTime) / 60000
            SystemLogger.log("SUCCESS", "AutoClean", "Hoàn tất bảo trì: Phát hiện $totalDuplicatesFound tập tin trùng lặp, $movedCount đã được xử lý, ${com.nas.naswebdav.utils.FormatUtils.formatBytes(savedBytes)} dung lượng được giải phóng, hoàn tất trong $durationMin phút.")
            throttleJob.cancel()
            Result.success()
        } catch (e: Exception) {
            throttleJob.cancel()
            SystemLogger.log("ERROR", "AutoClean", "Lỗi tiến trình dọn dẹp: ${e.message}"); Result.retry()
        }
    }
    private suspend fun moveFileToTrash(manager: WebDavManager, sourceUrl: String, user: String, pass: String): Boolean {
        return try {
            // FIX #19: Không dùng substringBefore cắt chuỗi cứng, dùng currentBaseUrl của WebDavManager
            val rootUrl = manager.currentBaseUrl.trimEnd('/')
            val trashFolderUrl = "$rootUrl/.trash"
            val authHeader = okhttp3.Credentials.basic(user, pass)
            try { NasApplication.instance.sharedHttpClient.newCall(okhttp3.Request.Builder().url(trashFolderUrl).method("MKCOL", null).header("Authorization", authHeader).build()).execute().use {} } catch (_: Exception) {}
            // FIX #19: URL-encode tên file để không bị 400 Bad Request
            val fileName = sourceUrl.substringAfterLast("/")
            val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            val destUrl = "$trashFolderUrl/$encodedName"
            NasApplication.instance.sharedHttpClient.newCall(okhttp3.Request.Builder().url(sourceUrl).method("MOVE", null).header("Destination", destUrl).header("Overwrite", "F").header("Authorization", authHeader).build()).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }
}

    // (Đã xóa SmartSyncWorker theo yêu cầu)

// ════════════════════════════════════════════════════════════════════════════
// IdleSpeedTestWorker — Đo tốc độ đĩa ngầm
// ════════════════════════════════════════════════════════════════════════════

class IdleSpeedTestWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val currentUrl = inputData.getString("currentUrl") ?: return@withContext Result.failure()
        // FIX #16: Load credentials từ SecurePrefsHelper
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (user.isEmpty() || pass.isEmpty()) return@withContext Result.failure()

        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            // FIX #16: Thêm Authorization header để không bị server từ chối 401
            val request = okhttp3.Request.Builder()
                .url("$apiBaseUrl/api/disk/speedtest")
                .header("Authorization", okhttp3.Credentials.basic(user, pass))
                .post(ByteArray(0).toRequestBody(null, 0, 0))
                .build()
            NasApplication.instance.sharedHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("last_speed_write", json.optString("write_speed", "Lỗi")).putString("last_speed_read", json.optString("read_speed", "Lỗi")).putString("last_speed_time", com.nas.naswebdav.utils.FormatUtils.formatDateTime(System.currentTimeMillis())).apply()
                    return@withContext Result.success()
                }
            }
        } catch (e: Exception) { SystemLogger.log("WARNING", "SpeedTest", "Không thể thực thi tiến trình chẩn đoán tốc độ ổ đĩa nền: ${e.message}") }
        return@withContext Result.failure()
    }
}
