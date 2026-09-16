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
            SystemLogger.log("INFO", "AutoClean", applicationContext.getString(R.string.autodup_maintenance_window, nowHour))
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
                    .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                    .build()
                val idleResp = NasApplication.instance.fastApiClient.newCall(idleReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: break
                val idleJson = org.json.JSONObject(idleResp)
                if (idleJson.optBoolean("idle", false)) {
                    idleOk = true; break
                }
                val reason = idleJson.optString("reason", "không rõ")
                SystemLogger.log("INFO", "AutoClean", applicationContext.getString(R.string.autodup_system_busy, reason, attempts))
                kotlinx.coroutines.delay(5 * 60 * 1000L)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                SystemLogger.log("WARNING", "AutoClean", applicationContext.getString(R.string.autodup_idle_check_error, e.message.orEmpty()))
                break
            }
        }
        if (!idleOk) {
            SystemLogger.log("INFO", "AutoClean", applicationContext.getString(R.string.autodup_not_idle_cancel))
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
                        .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                        .build()
                    val body = NasApplication.instance.fastApiClient.newCall(idleReq).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                    if (body != null) {
                        val isIdle = org.json.JSONObject(body).optBoolean("idle", false)
                        if (!isIdle && !DuplicateProgressState.isPaused.value) {
                            DuplicateProgressState.isPaused.value = true
                            wasAutoPaused = true
                            SystemLogger.log("INFO", "AutoClean", applicationContext.getString(R.string.autodup_busy_pause))
                        } else if (isIdle && DuplicateProgressState.isPaused.value && wasAutoPaused) {
                            DuplicateProgressState.isPaused.value = false
                            wasAutoPaused = false
                            SystemLogger.log("INFO", "AutoClean", applicationContext.getString(R.string.autodup_idle_resume))
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.w("AutoDuplicate", "idle poll failed: ${e.message}")
                }
                kotlinx.coroutines.delay(60 * 1000L)
            }
        }

        try {
            SystemLogger.log("INFO", "AutoClean", applicationContext.getString(R.string.autodup_launch_cleanup))
            db.logDao().insertLog(
                SystemLog(
                    type = "INFO",
                    module = "DuplicateScan",
                    message = applicationContext.getString(R.string.autodup_auto_schedule_log)
                )
            )
            // Incremental index — KHÔNG clearAllFiles() vì Fast-Path API dùng INSERT OR REPLACE,
            // avoids DB churn (hàng triệu row xóa + chèn lại mỗi lần auto-scan).
            var totalFiles = 0
            val apiBaseUrl = url.toApiBaseUrl()
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/fast_index").header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader).build()
            val client = NasApplication.instance.sharedHttpClient
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    android.util.JsonReader(response.body?.charStream()).use { reader ->
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
                    }
                } else throw Exception(applicationContext.getString(R.string.autodup_no_fastpath))
            }
            val duplicateSizes = db.fileDao().getDuplicateSizes()
            var movedCount = 0; var savedBytes = 0L; var totalDuplicatesFound = 0
            for (size in duplicateSizes) {
                val group = db.fileDao().getFilesBySize(size)
                if (group.size < 2 || group.first().contentLength < 1024L) continue
                // FIX-REVIEW-S3: khử alias endpoint — cùng file vật lý qua LAN và
                // Tailscale là 2 row khác URL nhưng cùng canonical path. Giữ một
                // đại diện mỗi identity TRƯỚC khi hash, nếu không full-hash khớp
                // tất yếu rồi MOVE alias xóa bản duy nhất.
                val deduped = group.groupBy { canonicalNasPath(it.path) }
                    .mapNotNull { (canonical, rows) ->
                        if (canonical.isEmpty()) null
                        else rows.minByOrNull { it.path.length }
                    }
                val aliasSkipped = group.size - deduped.size
                if (aliasSkipped > 0) {
                    SystemLogger.log("INFO", "AutoClean",
                        "Bỏ qua $aliasSkipped alias endpoint (cùng file vật lý)")
                }
                val uniqueGroup = deduped
                if (uniqueGroup.size < 2) continue
                val hashResult = mutableMapOf<String, String>()
                // FIX-AUDIT-F2: partial hash (1MB đầu) + fallback size+mtime chỉ là
                // LỌC ỨNG VIÊN, không đủ kết luận trùng để xóa. File hash lỗi
                // (null) bị bỏ qua thay vì gán pseudo-hash LGH_ rồi xóa nhầm.
                // Phone CPU computes SHA-256 over first 1MB (WebDAV Range) — NAS chỉ serve bytes
                try {
                    for (file in uniqueGroup) {
                        if (!isActive) break
                        // R3-P2: tôn trọng pause của user (togglePauseDuplicateScan)
                        // trong vòng hash/MOVE — trước đây báo tạm dừng nhưng vẫn làm.
                        while (DuplicateProgressState.isPaused.value && isActive) {
                            kotlinx.coroutines.delay(500)
                        }
                        if (!isActive) break
                        val phoneHash = webDavManager.getSha256PhoneStream(file.path)
                        if (!phoneHash.isNullOrEmpty()) {
                            hashResult[file.path] = phoneHash
                        } else {
                            SystemLogger.log("WARNING", "AutoClean",
                                "Bỏ qua ứng viên trùng (không hash được): ${file.path}")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* bỏ qua group, không gán LGH_ */ }
                val hashGroups = mutableMapOf<String, MutableList<CachedFile>>()
                for (file in uniqueGroup) { val hash = hashResult[file.path]; if (!hash.isNullOrEmpty()) hashGroups.getOrPut(hash) { mutableListOf() }.add(file) }
                for ((_, identicalFiles) in hashGroups) {
                    if (identicalFiles.size > 1) {
                        // Xác minh toàn bộ nội dung trước hành động phá hủy:
                        // full SHA-256 khớp mới xóa, một file fail full-hash thì giữ cả nhóm.
                        // R4-P1 TOCTOU: chụp size+mtime lúc hash, HEAD lại ngay trước MOVE.
                        // Client khác sửa file giữa hai bước → size/mtime lệch → bỏ qua,
                        // không xóa bản mới chưa kiểm tra.
                        val fullHashes = mutableMapOf<String, String>()
                        val hashSnapshot = mutableMapOf<String, Pair<Long, Long>>()
                        var fullOk = true
                        for (file in identicalFiles) {
                            if (!isActive) { fullOk = false; break }
                            val full = webDavManager.getFullSha256PhoneStream(file.path, file.contentLength)
                            if (full.isNullOrEmpty()) {
                                fullOk = false
                                SystemLogger.log("WARNING", "AutoClean",
                                    "Bỏ qua nhóm trùng (không verify full được): ${file.path}")
                                break
                            }
                            fullHashes[file.path] = full
                            hashSnapshot[file.path] = Pair(file.contentLength, file.lastModified)
                        }
                        if (!fullOk) continue
                        val verified = fullHashes.entries.groupBy({ it.value }, { it.key })
                            .filter { it.value.size > 1 }
                        for ((_, paths) in verified) {
                            val verifiedFiles = identicalFiles.filter { it.path in paths }
                            if (verifiedFiles.size < 2) continue
                            val sorted = verifiedFiles.sortedWith(compareBy({ it.path.length }, { it.lastModified }))
                            val filesToTrash = sorted.drop(1); totalDuplicatesFound += filesToTrash.size
                            val authCtx = WebDavAuthContext(webDavManager, user, pass)
                            for (trashFile in filesToTrash) {
                                if (!isActive) break
                                while (DuplicateProgressState.isPaused.value && isActive) {
                                    kotlinx.coroutines.delay(500)
                                }
                                if (!isActive) break
                                // R4-P1 TOCTOU revalidate: HEAD size phải khớp snapshot lúc
                                // hash. File bị sửa sau hash → bỏ qua, không MOVE.
                                val snap = hashSnapshot[trashFile.path]
                                val freshLen = try {
                                    webDavManager.headFileHeaders(trashFile.path)?.get("Content-Length")?.toLongOrNull()
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                                if (snap == null || freshLen == null || freshLen != snap.first) {
                                    SystemLogger.log("WARNING", "AutoClean",
                                        "Bỏ qua (đổi sau hash): ${trashFile.path}")
                                    continue
                                }
                                if (moveFileToTrash(authCtx, trashFile.path)) { movedCount++; savedBytes += trashFile.contentLength }
                            }
                        }
                    }
                }
            }
            val durationMin = (System.currentTimeMillis() - startTime) / 60000
            SystemLogger.log(
                "SUCCESS",
                "AutoClean",
                applicationContext.getString(
                    R.string.autodup_maintenance_complete,
                    totalDuplicatesFound,
                    movedCount,
                    com.nas.naswebdav.utils.FormatUtils.formatBytes(savedBytes),
                    durationMin
                )
            )
            throttleJob.cancel()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throttleJob.cancel()
            if (isStopped) return@withContext Result.failure()
            // P1-14: Cap retries at 3 to prevent infinite retry loop
            if (runAttemptCount < 3) {
                SystemLogger.log("ERROR", "AutoClean", "Lỗi tiến trình dọn dẹp: ${e.message}")
                return@withContext Result.retry()
            } else {
                SystemLogger.log("ERROR", "AutoClean", "Dọn dẹp thất bại sau 3 lần thử: ${e.message}")
                return@withContext Result.failure()
            }
        }
    }

    private data class WebDavAuthContext(val manager: WebDavManager, val user: String, val pass: String) {
        val authHeader: String get() = WebDavManager.AuthState(user = user, pass = pass).authHeader
    }

    private fun executeWebDavRequest(url: String, method: String, authHeader: String, vararg extraHeaders: Pair<String, String>): Boolean {
        return try {
            val builder = okhttp3.Request.Builder().url(url).method(method, null).header("Authorization", authHeader)
            for ((k, v) in extraHeaders) builder.header(k, v)
            NasApplication.instance.sharedHttpClient.newCall(builder.build()).execute().use { it.isSuccessful }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun moveFileToTrash(ctx: WebDavAuthContext, sourceUrl: String): Boolean {
        try {
            val rootUrl = ctx.manager.currentBaseUrl.trimEnd('/')
            val fileName = sourceUrl.substringAfterLast("/")
            val trashFolderUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, "", false)
            var destUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, fileName, false)
            executeWebDavRequest(trashFolderUrl, "MKCOL", ctx.authHeader)
            var success = executeWebDavRequest(sourceUrl, "MOVE", ctx.authHeader, "Destination" to destUrl, "Overwrite" to "F")
            // R2-P1: đích trash trùng tên (412) → đổi tên duy nhất + timestamp,
            // không ghi đè bản trash cũ.
            if (!success) {
                val dot = fileName.lastIndexOf('.')
                val unique = if (dot > 0) {
                    fileName.substring(0, dot) + "_" + System.currentTimeMillis() + fileName.substring(dot)
                } else {
                    fileName + "_" + System.currentTimeMillis()
                }
                destUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, unique, false)
                success = executeWebDavRequest(sourceUrl, "MOVE", ctx.authHeader, "Destination" to destUrl, "Overwrite" to "F")
            }
            if (success) {
                try {
                    NasApplication.instance.database.trashMetaDao().insert(
                        TrashMeta(trashPath = destUrl, originalPath = sourceUrl)
                    )
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                return true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AutoCleanWorker", "MOVE to .trash failed: ${e.message}")
        }

        // FIX-REVIEW-S3: KHÔNG fallback DELETE vĩnh viễn khi MOVE trash fail.
        // File trùng đã full-verify vẫn phải giữ để xử lý sau (log + retry vòng
        // sau) — xóa vĩnh viễn tự động là mất dữ liệu khi trash gặp sự cố.
        android.util.Log.w("AutoCleanWorker", "MOVE to .trash failed for $sourceUrl — giữ file, thử lại vòng sau")
        SystemLogger.log("WARNING", "AutoClean",
            "Không chuyển được vào trash (giữ file): $sourceUrl")
        return false
    }
}
