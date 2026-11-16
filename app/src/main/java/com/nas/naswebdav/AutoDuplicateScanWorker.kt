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
    override suspend fun doWork(): Result {
        val auth = withContext(Dispatchers.IO) {
            val user = SecurePrefsHelper.getUser(applicationContext)
            val pass = SecurePrefsHelper.getPass(applicationContext)
            val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
                .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
            if (user != SecurePrefsHelper.getUser(applicationContext) || pass != SecurePrefsHelper.getPass(applicationContext)) null
            else WebDavManager.AuthState(url,user,pass)
        } ?: return Result.retry()
        if (auth.baseUrl.isBlank() || auth.user.isBlank()) return Result.failure()
        return runAutoScan(auth)
    }

    private suspend fun runAutoScan(auth: WebDavManager.AuthState): Result =
        withContext(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(auth)) {
        SystemLogger.log("INFO", "AutoClean", "Bắt đầu dọn dẹp trùng lặp tự động (Worker khởi động).")
        // AUDIT-20261104: worker quet lau (>10s) phai setForeground truoc —
        // neu khong Android 12+ kill worker giua chung. Loi foreground thi tiep tuc chay.
        try {
            setForeground(makeForegroundInfo("autodup_channel", "AutoClean", 1003, "Đang dọn dẹp trùng lặp..."))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("AutoDuplicateScanWorker", "setForeground non-fatal: ${e.javaClass.simpleName}", e)
        }
        val db = NasApplication.instance.database
        val user = auth.user
        val pass = auth.pass
        val url = auth.baseUrl

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

        val webDavManager = WebDavManager
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

        var lease: NasDuplicateLease? = null
        try {
            SystemLogger.log("INFO", "AutoClean", applicationContext.getString(R.string.autodup_launch_cleanup))
            val activeLease=NasDuplicateLease.acquire(auth)
            lease=activeLease
            activeLease.start(this)
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
            val request = okhttp3.Request.Builder().url("$apiBaseUrl/api/disk/fast_index").tag(WebDavManager.AuthState::class.java,auth).header("Authorization",auth.authHeader).build()
            val client = NasApplication.instance.sharedHttpClient
            val mirrorStarted = System.currentTimeMillis()
            val scanAccount = currentAccountKey()
            val snapshot = client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    throw IncompleteNasCatalogueException("Danh mục NAS chưa sẵn sàng; chưa dọn file")
                }
                readNasCatalogue(body.charStream(),url,scanAccount) { batch, received, expected ->
                    if (isStopped || user != SecurePrefsHelper.getUser(applicationContext) || pass != SecurePrefsHelper.getPass(applicationContext)) {
                        throw CancellationException("NAS account changed or scan stopped")
                    }
                    activeLease.progress("catalogue:$received",received,expected)
                    WebDavManager.drainPendingCacheClear()
                    db.withTransaction { db.fileDao().insertFiles(batch) }
                }
            }
            require(snapshot.revision == activeLease.revision) {"Danh mục NAS đã thay đổi; chưa dọn file"}
            activeLease.progress("catalogue-complete",snapshot.total,snapshot.total)
            finishNasCatalogueMirror(db,url,scanAccount,mirrorStarted,snapshot)
            totalFiles = snapshot.total
            val key = currentAccountKey()
            val duplicateSizes = db.fileDao().getDuplicateSizes(key)
            var movedCount = 0
            var savedBytes = 0L
            var totalDuplicatesFound = 0
            var checked = 0
            for (size in duplicateSizes) {
                var after = ""
                while (isActive) {
                    val page = db.fileDao().getFilesBySizePage(size, key, after, 128)
                    if (page.isEmpty()) break
                    for (file in page) {
                        currentCoroutineContext().ensureActive()
                        if (user != SecurePrefsHelper.getUser(applicationContext) || pass != SecurePrefsHelper.getPass(applicationContext)) {
                            throw CancellationException("NAS account changed")
                        }
                        while (DuplicateProgressState.isPaused.value) {activeLease.ensureValid();delay(500)}
                        activeLease.ensureValid()
                        val proof = try { webDavManager.getCachedVerifiedSha256(file.path, size) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { null }
                        activeLease.ensureValid()
                        checked++
                        if(checked%25==0) activeLease.checkpoint("hash:$checked")
                        if (proof != null) {
                            val keeper = db.fileDao().getVerifiedKeeper(proof.first, size, key)
                            if (keeper != null && canonicalDuplicatePath(keeper.path) != canonicalDuplicatePath(file.path)) {
                                totalDuplicatesFound++
                                activeLease.checkpoint("cleanup:$checked")
                                val deleted = try { webDavManager.deleteVerifiedDuplicate(file, keeper, proof.first,activeLease) }
                                catch (error: CancellationException) { throw error }
                                catch (_: Exception) { false }
                                activeLease.ensureValid()
                                if (deleted) { movedCount++; savedBytes += size }
                            }
                        }
                        after = file.path
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
            activeLease.complete()
            SystemLogger.log("SUCCESS", "AutoClean", "Hoàn tất dọn dẹp trùng lặp tự động")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throttleJob.cancel()
            if (isStopped) return@withContext Result.retry()
            // P1-14: Cap retries at 3 to prevent infinite retry loop
            if (runAttemptCount < 3) {
                SystemLogger.log("ERROR", "AutoClean", "Lỗi tiến trình dọn dẹp: ${e.message}")
                return@withContext Result.retry()
            } else {
                SystemLogger.log("ERROR", "AutoClean", "Dọn dẹp thất bại sau 3 lần thử: ${e.message}")
                return@withContext Result.failure()
            }
        } finally {
            throttleJob.cancel()
            lease?.retryAndRelease()
        }
    }
}
