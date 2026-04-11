package com.nas.naswebdav

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.room.withTransaction
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class DuplicateScanWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val notificationId = 999
    private val channelId = "scan_channel"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val currentUrl = inputData.getString("currentUrl") ?: return@withContext Result.failure()
        val user = inputData.getString("user") ?: return@withContext Result.failure()
        val pass = inputData.getString("pass") ?: return@withContext Result.failure()

        val webDavManager = WebDavManager()
        webDavManager.connect(currentUrl, user, pass)
        val db = NasApplication.instance.database

        try {
            // --- TÍNH NĂNG TỰ ĐỘNG DỌN RÁC SAU 7 NGÀY ---
            val trashUrl = currentUrl.substringBefore("/webdav/") + "/webdav/.trash/"
            val now = System.currentTimeMillis()
            val sevenDaysInMillis = 7 * 24 * 60 * 60 * 1000L

            try {
                val trashItems = webDavManager.listFiles(trashUrl)
                for (item in trashItems) {
                    if (now - item.lastModified > sevenDaysInMillis) {
                        webDavManager.deleteFile(item.path)
                    }
                }
            } catch (e: Exception) { }

            val scannedCount = AtomicInteger(0)
            val foundCount = AtomicInteger(0)
            val mediaExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "mp4", "mkv", "mov", "avi")
            val currentScanningItem = AtomicReference("Đang khởi động Index Engine...")
            val currentIsFolder = AtomicReference(true)

            coroutineScope {
                val uiUpdaterJob = launch {
                    while (isActive) {
                        setProgress(workDataOf(
                            "itemName" to currentScanningItem.get(),
                            "isFolder" to currentIsFolder.get(),
                            "scannedCount" to scannedCount.get(),
                            "foundCount" to foundCount.get(),
                            "percent" to 0.3f,
                            "currentFolderUrl" to "Cơ sở dữ liệu đang nạp"
                        ))
                        delay(1000)
                    }
                }

                // =========================================================================
                // GIAI ĐOẠN 1: NAS FAST-PATH INDEX ENGINE (STREAMING JSON)
                // =========================================================================
                var isFastPathSuccess = false
                val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"

                try {
                    val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/fast_index").build()
                    val client = okhttp3.OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            val reader = android.util.JsonReader(java.io.InputStreamReader(response.body!!.byteStream(), "UTF-8"))
                            val batchBuffer = mutableListOf<CachedFile>()

                            // Xóa DB cũ để nạp bản đồ mới tinh từ NAS
                            db.withTransaction {
                                db.fileDao().deleteByParentPath("%")
                                db.checkpointDao().clearCheckpoint("DuplicateScan")
                            }

                            currentScanningItem.set("Đang stream dữ liệu từ ổ cứng NAS...")

                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "files") {
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

                                        // Tái cấu trúc WebDAV URL để UI đọc được
                                        val fullUrl = currentUrl.substringBefore("/webdav/") + path
                                        val parentUrl = fullUrl.substringBeforeLast("/") + "/"

                                        batchBuffer.add(
                                            CachedFile(
                                                path = fullUrl, name = name, isDirectory = false,
                                                contentType = "application/octet-stream", parentPath = parentUrl,
                                                contentLength = size, lastModified = mtime
                                            )
                                        )
                                        scannedCount.incrementAndGet()
                                        foundCount.incrementAndGet()

                                        // Ghi đĩa Batch cực lớn để đạt tốc độ cao nhất
                                        if (batchBuffer.size >= 2000) {
                                            db.withTransaction { db.fileDao().insertFiles(batchBuffer.toList()) }
                                            batchBuffer.clear()
                                        }
                                    }
                                    reader.endArray()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (batchBuffer.isNotEmpty()) {
                                db.withTransaction { db.fileDao().insertFiles(batchBuffer.toList()) }
                            }

                            isFastPathSuccess = true
                            db.logDao().insertLog(SystemLog(type = "SUCCESS", module = "IndexEngine", message = "Quét thần tốc ${scannedCount.get()} tệp qua Local API."))
                        }
                    }
                } catch (e: Exception) {
                    db.logDao().insertLog(SystemLog(type = "WARNING", module = "IndexEngine", message = "Không có Fast-Path, lùi về WebDAV: ${e.message}"))
                }

                // =========================================================================
                // FALLBACK: WEBDAV CRAWLER (Nếu API nội bộ chết)
                // =========================================================================
                if (!isFastPathSuccess) {
                    val folderQueue = java.util.concurrent.LinkedBlockingQueue<String>(1000)
                    val forceRestart = inputData.getBoolean("forceRestart", false)
                    val checkpoint = db.checkpointDao().getCheckpoint("DuplicateScan")

                    if (checkpoint != null && !forceRestart) {
                        folderQueue.put(checkpoint.lastProcessedFolder)
                        scannedCount.set(checkpoint.scannedCount)
                        foundCount.set(checkpoint.foundCount)
                        db.logDao().insertLog(SystemLog(type = "INFO", module = "DuplicateScan", message = "Phục hồi quét từ: ${checkpoint.lastProcessedFolder}"))
                    } else {
                        folderQueue.put(currentUrl)
                        db.checkpointDao().clearCheckpoint("DuplicateScan")
                    }

                    val batchBuffer = mutableListOf<CachedFile>()

                    while (folderQueue.isNotEmpty() && isActive) {
                        val folder = folderQueue.poll() ?: break
                        currentScanningItem.set(folder.substringAfterLast("/", folder))
                        try {
                            val files = webDavManager.listFiles(folder)
                            db.withTransaction {
                                for (file in files) {
                                    scannedCount.incrementAndGet()
                                    if (file.isDirectory) {
                                        if (file.path.trimEnd('/') != folder.trimEnd('/')) {
                                            folderQueue.offer(file.path)
                                        }
                                    } else {
                                        val ext = file.name.lowercase().substringAfterLast('.', "")
                                        if (mediaExtensions.contains(ext)) {
                                            batchBuffer.add(
                                                CachedFile(
                                                    path = file.path, name = file.name, isDirectory = false,
                                                    contentType = file.contentType, parentPath = folder,
                                                    contentLength = file.contentLength, lastModified = file.lastModified
                                                )
                                            )
                                            foundCount.incrementAndGet()
                                        }
                                    }

                                    if (batchBuffer.size >= 500) {
                                        db.fileDao().insertFiles(batchBuffer.toList())
                                        batchBuffer.clear()
                                        db.checkpointDao().saveCheckpoint(ScanCheckpoint("DuplicateScan", folder, scannedCount.get(), foundCount.get()))
                                    }
                                }
                                if (batchBuffer.isNotEmpty()) {
                                    db.fileDao().insertFiles(batchBuffer)
                                    batchBuffer.clear()
                                }
                                db.checkpointDao().saveCheckpoint(ScanCheckpoint("DuplicateScan", folder, scannedCount.get(), foundCount.get()))
                            }
                        } catch (e: Exception) { }
                    }
                }

                uiUpdaterJob.cancel()

                // =========================================================================
                // GIAI ĐOẠN 2 & 3: HASH DELEGATION & LỌC TRÙNG LẶP CHÍNH XÁC
                // =========================================================================
                setProgress(workDataOf("itemName" to "Phân tích Hash...", "percent" to 0.9f))

                val stage1Duplicates = db.fileDao().getStage1Duplicates()
                if (stage1Duplicates.isNotEmpty()) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    stage1Duplicates.chunked(100).forEach { batch ->
                        try {
                            val jsonArray = org.json.JSONArray()
                            batch.forEach { file ->
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

                                    db.withTransaction {
                                        batch.forEach { file ->
                                            val hash = resultObj.optString(file.path, null)
                                            if (!hash.isNullOrEmpty()) {
                                                db.fileDao().updatePartialHash(file.path, hash)
                                            }
                                        }
                                    }
                                } else {
                                    batch.forEach { dup ->
                                        val partialHash = webDavManager.getPartialHashStream(dup.path)
                                        if (partialHash != null) db.fileDao().updatePartialHash(dup.path, partialHash)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            batch.forEach { dup ->
                                val partialHash = webDavManager.getPartialHashStream(dup.path)
                                if (partialHash != null) db.fileDao().updatePartialHash(dup.path, partialHash)
                            }
                        }
                    }
                }
            }

            db.checkpointDao().clearCheckpoint("DuplicateScan")
            return@withContext Result.success()

        } catch (e: Exception) {
            try {
                db.logDao().insertLog(SystemLog(type = "ERROR", module = "DuplicateScan", message = "Lỗi tiến trình quét rác: ${e.message}"))
            } catch (ex: Exception) {}
            return@withContext Result.failure()
        }
    }
}