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
            // Incremental index — KHÔNG clearAllFiles() vì Fast-Path API dùng INSERT OR REPLACE,
            // avoids DB churn (hàng triệu row xóa + chèn lại mỗi lần auto-scan).
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
                val hashResult = mutableMapOf<String, String>()
                // Phone CPU computes SHA-256 over first 1MB (WebDAV Range) — NAS chỉ serve bytes
                try {
                    for (file in group) {
                        if (!isActive) break
                        val phoneHash = webDavManager.getSha256PhoneStream(file.path)
                        if (!phoneHash.isNullOrEmpty()) {
                            hashResult[file.path] = phoneHash
                        } else {
                            // Fallback khi phone không tải được: dùng size+mtime pseudo-hash
                            hashResult[file.path] = "LGH_${file.contentLength}_${file.lastModified}"
                        }
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
            val rootUrl = manager.currentBaseUrl.trimEnd('/')
            val authHeader = okhttp3.Credentials.basic(user, pass)
            val fileName = sourceUrl.substringAfterLast("/")
            val trashFolderUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, "", false)
            val destUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, fileName, false)
            try { NasApplication.instance.sharedHttpClient.newCall(okhttp3.Request.Builder().url(trashFolderUrl).method("MKCOL", null).header("Authorization", authHeader).build()).execute().use {} } catch (_: Exception) {}
            val success = NasApplication.instance.sharedHttpClient.newCall(okhttp3.Request.Builder().url(sourceUrl).method("MOVE", null).header("Destination", destUrl).header("Overwrite", "F").header("Authorization", authHeader).build()).execute().use { it.isSuccessful }
            if (success) {
                try {
                    NasApplication.instance.database.trashMetaDao().insert(
                        TrashMeta(trashPath = destUrl, originalPath = sourceUrl)
                    )
                } catch (_: Exception) {}
            }
            success
        } catch (_: Exception) { false }
    }
}
