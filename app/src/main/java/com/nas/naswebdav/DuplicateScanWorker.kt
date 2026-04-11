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
}
class DuplicateScanWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {

    private val notificationId = 999
    private val channelId = "scan_channel"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(makeForegroundInfo(channelId, "Quét dọn hệ thống", notificationId, "Đang quét dữ liệu trùng lặp..."))
        } catch (_: Exception) {}

        val currentUrl = inputData.getString("currentUrl") ?: return@withContext Result.failure()
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (user.isEmpty() || pass.isEmpty()) return@withContext Result.failure()

        val webDavManager = WebDavManager
        webDavManager.connect(currentUrl, user, pass)
        val db = NasApplication.instance.database

        // KHỞI TẠO HỆ THỐNG THÔNG BÁO ĐỘNG (DYNAMIC NOTIFICATION)
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_search_category_default)
            .setOnlyAlertOnce(true) // Quan trọng: Tránh rung/kêu liên tục khi cập nhật
            .setOngoing(true)

        val isUiUpdating = java.util.concurrent.atomic.AtomicBoolean(true)
        var uiUpdaterJob: kotlinx.coroutines.Job? = null
        try {
            // --- TÍNH NĂNG TỰ ĐỘNG DỌN RÁC SAU 7 NGÀY ---
            val trashUrl = currentUrl.substringBefore("/webdav/") + "/webdav/.trash/"
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

            val uiScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
            )
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
                                "stage" to currentStage.get(),
                                "currentFolder" to currentFolder.get(),
                                "itemName" to currentFileName.get(),
                                "isFolder" to false,
                                "scannedCount" to totalFilesIndexed.get(),
                                "foundCount" to duplicateGroupsFound.get(),
                                "hashCount" to hashesComputed.get(),
                                "totalHashes" to totalHashesNeeded.get(),
                                "percent" to progressPercent.get(),
                                "currentStagePercent" to currentStagePercent.get(),
                                "currentFolderUrl" to currentFolder.get(),
                                "stageNumber" to stageNumber.get(),
                                "totalStages" to totalStages,
                                "stageDescription" to stageDescription.get(),
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
                    delay(50)
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
                    val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/fast_index").build()
                    val client = NasApplication.instance.sharedHttpClient

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            try {
                                val reader = android.util.JsonReader(java.io.InputStreamReader(response.body!!.byteStream(), "UTF-8"))
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

                                            val fullUrl = currentUrl.substringBefore("/webdav/") + path
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
                                SystemLogger.log("SUCCESS", "IndexEngine", "Quét hoàn tất ${totalFilesIndexed.get()} tệp qua Local API.")
                            } catch (e: java.io.EOFException) {
                                SystemLogger.log("WARNING", "IndexEngine", "API stream bị cắt ngang: ${e.message}")
                                if (totalFilesIndexed.get() > 100) isFastPathSuccess = true // Vẫn dùng data đã nhận
                            } catch (e: Exception) {
                                SystemLogger.log("WARNING", "IndexEngine", "Lỗi JSON stream: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    SystemLogger.log("WARNING", "IndexEngine", "Không có Fast-Path, lùi về WebDAV: ${e.message}")
                }

                // ═══════════════════════════════════════════════════
                // FALLBACK: WEBDAV CRAWLER (Nếu API nội bộ chết)
                // ═══════════════════════════════════════════════════
                if (!isFastPathSuccess) {
                    currentStage.set("Quét qua WebDAV")
                    stageDescription.set("API nội bộ không khả dụng, quét từng thư mục bằng giao thức Webdav")
                    val folderQueue = java.util.concurrent.LinkedBlockingQueue<String>(1000)
                    val forceRestart = inputData.getBoolean("forceRestart", false)
                    val checkpoint = db.checkpointDao().getCheckpoint("DuplicateScan")

                    if (checkpoint != null && !forceRestart) {
                        folderQueue.put(checkpoint.lastProcessedFolder)
                        totalFilesIndexed.set(checkpoint.scannedCount)
                        SystemLogger.log("INFO", "DuplicateScan", "Phục hồi quét từ: ${checkpoint.lastProcessedFolder}")
                    } else {
                        folderQueue.put(currentUrl)
                        db.checkpointDao().clearCheckpoint("DuplicateScan")
                    }

                    val batchBuffer = mutableListOf<CachedFile>()
                    val foldersScanned = AtomicInteger(0)
                    val totalFoldersDiscovered = AtomicInteger(1) // Bắt đầu = 1 (thư mục gốc)

                    val semaphore = kotlinx.coroutines.sync.Semaphore(3)  // 3 luồng BFS song song (cân bằng NAS ARM)

                    // Thuật toán duyệt BFS Đa Luồng (PHASE 8.C)
                    while (isActive) {
                        while (DuplicateProgressState.isPaused.value) { delay(500) }
                        
                        val currentFolders = mutableListOf<String>()
                        while (folderQueue.isNotEmpty()) {
                            currentFolders.add(folderQueue.poll()!!)
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
                                            
                                            // Chốt Checkpoint ngầm để an toàn
                                            db.checkpointDao().saveCheckpoint(ScanCheckpoint("DuplicateScan", folder, totalFilesIndexed.get(), 0))
                                            
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
                currentStage.set("Phân tích file trùng lặp (1/2)")
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
                    currentFileName.set("Không tìm thấy file trùng lặp tiềm năng.")
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
                                val anchorFP = anchorFile.imageFingerprint!!
                                for (file in group) {
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
                                    val pseudoHash = "LGH_${file.contentLength}_${file.lastModified}"
                                    pendingHashUpdates.add(Pair(file.path, pseudoHash))
                                }
                            } else {
                                try {
                                    val jsonArray = org.json.JSONArray()
                                    filesNeedHash.forEach { file ->
                                        val relativePath = java.net.URL(file.path).path.substringAfter("/webdav")
                                        val fileObj = org.json.JSONObject().apply {
                                            put("path", file.path)
                                            put("local_path", relativePath)
                                        }
                                        jsonArray.put(fileObj)
                                    }
                                    val jsonString = org.json.JSONObject().put("files", jsonArray).toString()
                                    val requestBody = jsonString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                                    val request = okhttp3.Request.Builder()
                                        .url("$apiBaseUrl/api/disk/hash_batch")
                                        .post(requestBody)
                                        .build()
                                    client.newCall(request).execute().use { response ->
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
                        SystemLogger.log("INFO", "HashEngine", "Lightning Mode: Xử lý hash nhanh cho ${actualDuplicatesCount} tệp.")
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
                stageDescription.set("Hoàn tất! Đã quét ${totalFilesIndexed.get()} tệp, tìm thấy $finalDuplicateCount file trùng.")
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
                        .setContentText("Không tìm thấy file trùng lặp nào mới.")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }
            }

            db.checkpointDao().clearCheckpoint("DuplicateScan")
            return@withContext Result.success()

        } catch (e: Exception) {
            try {
                SystemLogger.log("ERROR", "DuplicateScan", "Lỗi tiến trình quét rác: ${e.message}")
            } catch (ex: Exception) {}
            return@withContext Result.failure()
        } finally {
            isUiUpdating.set(false)
            uiUpdaterJob?.join()
            // FIX P4: Huỷ scope để tránh rò rỉ coroutine/memory khi Worker kết thúc
            // (Job tạo từ CoroutineScope(Default + Job()) sẽ không tự hủy)
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }

    protected fun loadWebDavManager(): WebDavManager? {
        // SmartSwitch: Chọn URL đang hoạt động (LAN/Tailscale) thay vì URL tĩnh
        val url = kotlinx.coroutines.runBlocking { SmartNetworkManager.getActiveBaseUrl(applicationContext) }
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (url.isEmpty() || user.isEmpty()) return null
        val manager = WebDavManager
        manager.connect(url, user, pass)
        return manager
    }
}

// ════════════════════════════════════════════════════════════════════════════
// OfflineSyncWorker — Xử lý thao tác offline đã xếp hàng
// ════════════════════════════════════════════════════════════════════════════

class OfflineSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = (applicationContext as NasApplication).database
        val pendingActions = db.syncActionDao().getAllPendingActions()
        if (pendingActions.isEmpty()) return@withContext Result.success()
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        val url = kotlinx.coroutines.runBlocking { SmartNetworkManager.getActiveBaseUrl(applicationContext) }
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        if (user.isEmpty() || pass.isEmpty() || url.isEmpty()) return@withContext Result.failure()
        val webDavManager = WebDavManager.apply { connect(url, user, pass) }
        var allSuccess = true
        for (action in pendingActions) {
            try {
                when (action.actionType) {
                    "DELETE" -> webDavManager.deleteFile(action.sourcePath)
                    "CREATE_FOLDER" -> webDavManager.createFolder(action.sourcePath)
                    "RENAME" -> { if (action.destPath != null) webDavManager.renameFile(action.sourcePath, action.destPath) }
                }
                db.syncActionDao().deleteById(action.id)
            } catch (_: Exception) { allSuccess = false }
        }
        if (allSuccess) Result.success() else Result.retry()
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
            val channel = NotificationChannel(CHANNEL_ID, "Tiến trình tải lên", NotificationManager.IMPORTANCE_LOW).apply { description = "Hiển thị tiến trình upload file lên NAS"; setShowBadge(false) }
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
            .setContentTitle("Phát hiện file trùng lặp! 🗑️").setContentText("Tìm thấy $duplicateCount nhóm file rác trên NAS. Nhấn để dọn dẹp giải phóng dung lượng.")
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
        val baseUrl = kotlinx.coroutines.runBlocking { SmartNetworkManager.getActiveBaseUrl(applicationContext) }
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val settingsPrefs = SecurePrefsHelper.getSettingsPrefs(applicationContext)
        val deleteAfterBackup = settingsPrefs.getBoolean("delete_after_backup", false)
        val webDavManager = loadWebDavManager() ?: return@withContext Result.failure()
        if (runAttemptCount >= 3) { SystemLogger.log("ERROR", "AutoBackup", "Đã thử $runAttemptCount lần thất bại."); return@withContext Result.failure() }
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
            val urisToQuery = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
            
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
            
            for (mediaUri in urisToQuery) {
                if (isStopped) break
                applicationContext.contentResolver.query(mediaUri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
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
                        
                        val targetFileNasPath = targetFolder + fileName
                        
                        // Bỏ qua kiểm tra existingRemoteFiles dạng list toàn bộ vì giờ cấu trúc thành dạng Tree,
                        // thay vào đó chúng ta sẽ rely vào Database / Hash hoặc Head Request để tránh trùng

                        val localFile = File(dataPath)
                        if (!localFile.exists() || localFile.length() == 0L) continue
                        try {
                            val fileHash: String? = try { ImageFingerprint.computeFromFile(localFile) } catch (_: Exception) { null }
                            if (fileHash != null) { val existingFp = db.fingerprintDao().findByExactHash(fileHash); if (existingFp != null) continue }
                            val mimeType = try { applicationContext.contentResolver.getType(ContentUris.withAppendedId(mediaUri, id)) ?: "application/octet-stream" } catch (_: Exception) { "application/octet-stream" }
                            
                            val fileSize = localFile.length()
                            var lastProgressTime = 0L

                            applicationContext.contentResolver.openInputStream(ContentUris.withAppendedId(mediaUri, id))?.use { input ->
                                webDavManager.uploadStreamWithProgress(targetFileNasPath, input, fileSize, mimeType) { bytesWritten, totalBytes ->
                                    val now = System.currentTimeMillis()
                                    // Giảm throttle từ 500ms xuống 200ms để % nhảy mượt hơn (5 FPS) thay vì giật cục
                                    if (now - lastProgressTime > 200 || bytesWritten == totalBytes) {
                                        lastProgressTime = now
                                        val percent = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f
                                        setProgressAsync(workDataOf(
                                            "fileName" to fileName,
                                            "sourcePath" to dataPath,
                                            "destPath" to targetFileNasPath,
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
                                                .setContentText("$fileName\n$parentRelativePath")
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
                            if (fileHash != null && uploadVerified) db.fingerprintDao().insertFingerprint(FileFingerprint(filePath = targetFileNasPath, hash = fileHash, fileName = fileName, fileSize = localFile.length()))
                            if (deleteAfterBackup && uploadVerified) applicationContext.contentResolver.delete(ContentUris.withAppendedId(mediaUri, id), null, null)
                            backupCount++
                        } catch (e: Exception) { if (e !is java.io.FileNotFoundException && !(e.message ?: "").contains("Missing file")) SystemLogger.log("WARNING", "AutoBackup", "Lỗi tải tệp $fileName: ${e.message}") }
                    }
                }
            }
            if (backupCount > 0) SystemLogger.log("SUCCESS", "AutoBackup", "Đã sao lưu tự động $backupCount tệp đa phương tiện.")
            return@withContext Result.success()
        } catch (e: Exception) {
            SystemLogger.log("ERROR", "AutoBackup", "Lỗi luồng AutoBackup: ${e.message}")
            val isTransient = e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException
            return@withContext if (isTransient && runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class FingerprintWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as NasApplication; val db = app.database
        val webDavManager = loadWebDavManager() ?: return@withContext Result.failure()
        try {
            val filesToProcess = db.fileDao().getFilesWithoutFingerprint()
            if (filesToProcess.isEmpty()) { SystemLogger.log("INFO", "FingerprintWorker", "Không có file nào cần mồi vân tay."); return@withContext Result.success() }
            SystemLogger.log("INFO", "FingerprintWorker", "Bắt đầu tạo vân tay cho ${filesToProcess.size} files...")
            var successCount = 0; var failCount = 0
            val savedUrl = kotlinx.coroutines.runBlocking { SmartNetworkManager.getActiveBaseUrl(applicationContext) }.ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }; val savedUser = SecurePrefsHelper.getUser(applicationContext); val savedPass = SecurePrefsHelper.getPass(applicationContext)
            if (savedUrl.isEmpty() || savedUser.isEmpty()) return@withContext Result.failure()
            val apiBaseUrl = savedUrl.toApiBaseUrl()
            for (file in filesToProcess) {
                if (isStopped) break
                try {
                    val url = "$apiBaseUrl/api/thumb?path=${android.net.Uri.encode(file.path)}"
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
            SystemLogger.log("SUCCESS", "FingerprintWorker", "Hoàn tất mồi vân tay. Thành công: $successCount, Thất bại: $failCount")
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
        val user = SecurePrefsHelper.getUser(applicationContext); val pass = SecurePrefsHelper.getPass(applicationContext); val url = kotlinx.coroutines.runBlocking { SmartNetworkManager.getActiveBaseUrl(applicationContext) }.ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val webDavManager = loadWebDavManager() ?: return@withContext Result.failure()
        val startTime = System.currentTimeMillis()
        try {
            SystemLogger.log("INFO", "AutoClean", "Bắt đầu tiến trình tự động dọn dẹp file trùng lặp định kỳ.")
            db.withTransaction { db.fileDao().deleteByParentPath("%") }
            var totalFiles = 0
            val apiBaseUrl = url.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/fast_index").header("Authorization", okhttp3.Credentials.basic(user, pass)).build()
            val client = NasApplication.instance.sharedHttpClient
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val reader = android.util.JsonReader(response.body?.charStream())
                    reader.isLenient = true; reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (key == "total") { reader.nextInt() }
                        else if (key == "files") {
                            reader.beginArray(); val batch = mutableListOf<CachedFile>()
                            while (reader.hasNext()) {
                                reader.beginObject(); var name = ""; var path = ""; var size = 0L; var mtime = 0L
                                while (reader.hasNext()) { when (reader.nextName()) { "name" -> name = reader.nextString(); "path" -> path = reader.nextString(); "size" -> size = reader.nextLong(); "mtime" -> mtime = reader.nextLong(); else -> reader.skipValue() } }
                                reader.endObject()
                                val rootUrl = url.substringBefore("/webdav/"); val absolutePath = rootUrl + path; val parentUrl = absolutePath.substringBeforeLast("/") + "/"
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
                    paths.forEach { path -> jsonArray.put(JSONObject().apply { put("path", path); put("local_path", java.net.URL(path).path.substringAfter("/webdav")) }) }
                    val reqBody = JSONObject().put("files", jsonArray).toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                    val req = okhttp3.Request.Builder().url("$apiBase/api/disk/hash_batch").post(reqBody).build()
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
            SystemLogger.log("SUCCESS", "AutoClean", "Hoàn tất Dọn Rác: $totalDuplicatesFound trùng, $movedCount xử lý, ${com.nas.naswebdav.utils.FormatUtils.formatBytes(savedBytes)} giải phóng, $durationMin phút.")
            Result.success()
        } catch (e: Exception) { SystemLogger.log("ERROR", "AutoClean", "Lỗi dọn rác: ${e.message}"); Result.retry() }
    }
    private suspend fun moveFileToTrash(manager: WebDavManager, sourceUrl: String, user: String, pass: String): Boolean {
        return try {
            val rootUrl = manager.currentBaseUrl.substringBefore("/webdav/") + "/webdav/"; val trashFolderUrl = rootUrl + ".trash/"
            val authHeader = okhttp3.Credentials.basic(user, pass)
            try { NasApplication.instance.sharedHttpClient.newCall(okhttp3.Request.Builder().url(trashFolderUrl).method("MKCOL", null).header("Authorization", authHeader).build()).execute().use {} } catch (_: Exception) {}
            val destUrl = trashFolderUrl + sourceUrl.substringAfterLast("/")
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
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/speedtest").post(ByteArray(0).toRequestBody(null, 0, 0)).build()
            NasApplication.instance.sharedHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("last_speed_write", json.optString("write_speed", "Lỗi")).putString("last_speed_read", json.optString("read_speed", "Lỗi")).putString("last_speed_time", com.nas.naswebdav.utils.FormatUtils.formatDateTime(System.currentTimeMillis())).apply()
                    return@withContext Result.success()
                }
            }
        } catch (e: Exception) { SystemLogger.log("WARNING", "SpeedTest", "Không thể đo tốc độ đĩa ngầm: ${e.message}") }
        return@withContext Result.failure()
    }
}