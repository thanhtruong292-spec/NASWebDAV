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
                                // P2-8: drain flag doi-phien 1 lan truoc batch dau
                                // (worker co the chay lech phien voi UI).
                                var drainedOnce = false
                                while (reader.hasNext()) {
                                    reader.beginObject(); var name = ""; var path = ""; var size = 0L; var mtime = 0L
                                    while (reader.hasNext()) { when (reader.nextName()) { "name" -> name = reader.nextString(); "path" -> path = reader.nextString(); "size" -> size = reader.nextLong(); "mtime" -> mtime = reader.nextLong(); else -> reader.skipValue() } }
                                    reader.endObject()
                                    val rootUrl = url.trimEnd('/'); val absolutePath = rootUrl + (if (path.startsWith("/")) path else "/$path"); val parentUrl = absolutePath.substringBeforeLast("/") + "/"
                                    batch.add(CachedFile(path = absolutePath, name = name, isDirectory = false, contentType = "application/octet-stream", parentPath = parentUrl, contentLength = size, lastModified = mtime, accountKey = currentAccountKey()))
                                    totalFiles++
                                    if (!drainedOnce) { drainedOnce = true; WebDavManager.drainPendingCacheClear() }
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
            val duplicateSizes = db.fileDao().getDuplicateSizes(currentAccountKey())
            var movedCount = 0; var savedBytes = 0L; var totalDuplicatesFound = 0
            for (size in duplicateSizes) {
                val group = db.fileDao().getFilesBySize(size, currentAccountKey())
                if (group.size < 2 || group.first().contentLength < 1024L) continue
                // FIX-AUDIT-D3: khử alias endpoint — cùng file vật lý qua LAN và
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
                // FIX-AUDIT-D3: partial hash (1MB đầu) + fallback size+mtime chỉ là
                // LỌC ỨNG VIÊN, không đủ kết luận trùng để xóa. File hash lỗi
                // (null) bị bỏ qua thay vì gán pseudo-hash LGH_ rồi xóa nhầm.
                try {
                    for (file in uniqueGroup) {
                        if (!isActive) break
                        // Tôn trọng pause của user trong vòng hash/MOVE.
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
                        // TOCTOU: chụp size+mtime lúc hash, HEAD lại ngay trước MOVE.
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
                            // FIX-REVIEW-193369e-#10: rang buoc SURVIVOR (ban giu
                            // lai = sorted.first()). Ban cu chi HEAD victim; neu
                            // survivor bi sua/xoa sau hash ma van MOVE victim thi
                            // ban can giu co the mat. Quy tac: HEAD survivor phai
                            // khop snapshot hash; survivor doi/mat -> giu ca nhom.
                            // P1-2: so FULL HASH that, khong chi size/partial.
                            // Ban cu: `remotePartial != null` la du (khong so voi
                            // hash da snapshot), file lon chi kiem size -> survivor
                            // doi thanh file cung size van lot. Quy tac moi: lay
                            // lai full hash hien tai cua survivor (getFull... da
                            // siet: HTTP 200, du byte, tu choi 206) va SO SANH voi
                            // fullHashes da verify trong nhom. Khac/null -> bo nhom.
                            val survivor = sorted.first()
                            val survivorSnap = hashSnapshot[survivor.path]
                            val survivorHeaders = try {
                                webDavManager.headFileHeaders(survivor.path)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { null }
                            val survivorLen = survivorHeaders?.get("Content-Length")?.toLongOrNull()
                            val sizeOk = survivorSnap != null && survivorLen != null && survivorLen == survivorSnap.first
                            val survivorOk = if (sizeOk) {
                                val expectedHash = fullHashes[survivor.path]
                                if (expectedHash.isNullOrEmpty()) {
                                    false
                                } else {
                                    val freshHash = try {
                                        webDavManager.getFullSha256PhoneStream(survivor.path, survivorSnap.first)
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (_: Exception) { null }
                                    // Hash hien tai phai KHOP hash da verify; null/
                                    // khac -> survivor da doi/mat -> bo qua nhom.
                                    freshHash != null && freshHash == expectedHash
                                }
                            } else {
                                false
                            }
                            if (!survivorOk) {
                                SystemLogger.log("WARNING", "AutoClean",
                                    "Bỏ qua nhóm (survivor đổi/mất sau hash): ${survivor.path}")
                                continue
                            }
                            val filesToTrash = sorted.drop(1); totalDuplicatesFound += filesToTrash.size
                            val authCtx = WebDavAuthContext(webDavManager, user, pass)
                            for (trashFile in filesToTrash) {
                                if (!isActive) break
                                while (DuplicateProgressState.isPaused.value && isActive) {
                                    kotlinx.coroutines.delay(500)
                                }
                                if (!isActive) break
                                // R3: rang buoc PHIEN BAN victim. Ban cu chi check size
                                // + mtime (±2s, chap nhan mtime thieu) -> file sua sau
                                // hash nhung giu size, khong vuot mtime van bi MOVE.
                                // Quy tac moi: ETag phai ton tai + strong; luu ETag
                                // de MOVE dung If-Match; thieu ETag -> bo qua.
                                val snap = hashSnapshot[trashFile.path]
                                val freshHeaders = try {
                                    webDavManager.headFileHeaders(trashFile.path)
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                                val freshLen = freshHeaders?.get("Content-Length")?.toLongOrNull()
                                val freshEtag = freshHeaders?.get("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                                val freshMod = try {
                                    freshHeaders?.get("Last-Modified")?.let {
                                        java.text.SimpleDateFormat(
                                            "EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).apply {
                                            timeZone = java.util.TimeZone.getTimeZone("GMT")
                                        }.parse(it)?.time
                                    }
                                } catch (_: Exception) { null }
                                val modChanged = snap != null && snap.second > 0L && freshMod != null &&
                                    kotlin.math.abs(freshMod - snap.second) > 2000L
                                val victimEtagOk = freshEtag != null && !freshEtag.startsWith("W/")
                                if (snap == null || freshLen == null || freshLen != snap.first || modChanged || !victimEtagOk) {
                                    SystemLogger.log("WARNING", "AutoClean",
                                        "Bỏ qua (đổi sau hash / thiếu ETag): ${trashFile.path}")
                                    continue
                                }
                                val expectedVictimHash = fullHashes[trashFile.path]
                                if (expectedVictimHash.isNullOrEmpty()) {
                                    SystemLogger.log("WARNING", "AutoClean",
                                        "Bỏ qua (thiếu hash verify): ${trashFile.path}")
                                    continue
                                }
                                if (moveFileToTrash(authCtx, trashFile.path, freshEtag, expectedVictimHash)) { movedCount++; savedBytes += trashFile.contentLength }
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

    // R3: MOVE rang buoc phien ban + verify sau MOVE.
    // - Gui If-Match voi ETag lay luc revalidate: server doi version giua HEAD
    //   va MOVE -> 412 -> giu file. (Server khong ho tro If-Match tren MOVE thi
    //   header bi bo qua — buoc verify duoi van bat duoc.)
    // - Sau MOVE thanh cong: doc lai full-hash cua file TRONG TRASH va so voi
    //   hash da verify. Khac nhau (race ma ca hai lop phong thu lot) -> bao loi
    //   ro, khong tinh la thanh cong am tham.
    private suspend fun moveFileToTrash(
        ctx: WebDavAuthContext,
        sourceUrl: String,
        ifMatchEtag: String? = null,
        expectedHash: String? = null,
    ): Boolean {
        try {
            val rootUrl = ctx.manager.currentBaseUrl.trimEnd('/')
            val fileName = sourceUrl.substringAfterLast("/")
            val trashFolderUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, "", false)
            var destUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, fileName, false)
            executeWebDavRequest(trashFolderUrl, "MKCOL", ctx.authHeader)
            val versionHeaders: Array<Pair<String, String>> = if (!ifMatchEtag.isNullOrEmpty()) {
                arrayOf("Destination" to destUrl, "Overwrite" to "F", "If-Match" to ifMatchEtag)
            } else {
                arrayOf("Destination" to destUrl, "Overwrite" to "F")
            }
            var success = executeWebDavRequest(sourceUrl, "MOVE", ctx.authHeader, *versionHeaders)
            // FIX-AUDIT-D3: đích trash trùng tên (412) → đổi tên duy nhất + timestamp,
            // không ghi đè bản trash cũ. Giành lại If-Match cho đích mới.
            if (!success) {
                val dot = fileName.lastIndexOf('.')
                val unique = if (dot > 0) {
                    fileName.substring(0, dot) + "_" + System.currentTimeMillis() + fileName.substring(dot)
                } else {
                    fileName + "_" + System.currentTimeMillis()
                }
                destUrl = buildWebDavTrashTargetUrl(rootUrl, sourceUrl, unique, false)
                val retryHeaders: Array<Pair<String, String>> = if (!ifMatchEtag.isNullOrEmpty()) {
                    arrayOf("Destination" to destUrl, "Overwrite" to "F", "If-Match" to ifMatchEtag)
                } else {
                    arrayOf("Destination" to destUrl, "Overwrite" to "F")
                }
                success = executeWebDavRequest(sourceUrl, "MOVE", ctx.authHeader, *retryHeaders)
            }
            if (success) {
                // R3: verify noi dung trong trash khop hash da verify. Neu server
                // khong ap If-Match tren MOVE ma file doi giua chung, buoc nay bat.
                if (!expectedHash.isNullOrEmpty()) {
                    val trashLen = try {
                        ctx.manager.headFileHeaders(destUrl)?.get("Content-Length")?.toLongOrNull()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                    val verifiedHash = if (trashLen != null && trashLen > 0) {
                        try { ctx.manager.getFullSha256PhoneStream(destUrl, trashLen) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { null }
                    } else null
                    if (verifiedHash == null || verifiedHash != expectedHash) {
                        android.util.Log.w("AutoCleanWorker", "Trash verify that bai cho $destUrl — noi dung trash khac hash da duyet, can kiem tra thu cong")
                        SystemLogger.log("WARNING", "AutoClean",
                            "Nội dung trong trash khác hash đã duyệt ($destUrl) — cần kiểm tra thủ công, không tự xóa thêm.")
                        // Van ghi metadata de user thay + khoi phuc; khong coi la that bai MOVE.
                    }
                }
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

        // FIX-AUDIT-D3: KHÔNG fallback DELETE vĩnh viễn khi MOVE trash fail.
        // File trùng đã full-verify vẫn phải giữ để xử lý sau (log + retry vòng
        // sau) — xóa vĩnh viễn tự động là mất dữ liệu khi trash gặp sự cố.
        android.util.Log.w("AutoCleanWorker", "MOVE to .trash failed for $sourceUrl — giữ file, thử lại vòng sau")
        SystemLogger.log("WARNING", "AutoClean",
            "Không chuyển được vào trash (giữ file): $sourceUrl")
        return false
    }

    // FIX-AUDIT-D3: chuẩn hóa URL NAS về canonical path để khử alias endpoint
    // (LAN vs Tailscale cùng trỏ 1 file vật lý). Chỉ dùng cho nội bộ worker này.
    private fun canonicalNasPath(urlOrPath: String): String {
        val trimmed = urlOrPath.trimEnd('/')
        // Bỏ scheme://host[:port] và /AutoBackup/ prefix, giữ phần path sau.
        val noScheme = trimmed.substringAfter("://").substringAfter("/")
        return noScheme.lowercase().replace(Regex("/+"), "/").trimStart('/')
    }
}
