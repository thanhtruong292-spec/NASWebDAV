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


class FingerprintWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SystemLogger.log("INFO", "FingerprintWorker", "Bat dau tao chu ky so (fingerprint) cho tap tin")
        // AUDIT-20261104: worker quet hang tram file (>10s) phai setForeground truoc —
        // neu khong Android 12+ kill worker giua chung. Loi foreground thi tiep tuc chay.
        try {
            setForeground(makeForegroundInfo("fingerprint_channel", "Fingerprint", 1002, "Dang tao chu ky so..."))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("FingerprintWorker", "setForeground non-fatal: ${e.javaClass.simpleName}", e)
        }
        val app = applicationContext as NasApplication; val db = app.database
        val webDavManager = loadWebDavManager() ?: return@withContext Result.failure()
        try {
            // CODEX P1: truyen accountKey cua phien hien tai — ban cu goi key=""
            // nen file cache moi (mang key tai khoan) khong duoc chon, worker
            // bao hoan tat gia. currentAccountKey() dung AuthState vua connect.
            val acctKey = currentAccountKey()
            val savedUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
                .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
            val savedUser = SecurePrefsHelper.getUser(applicationContext)
            val savedPass = SecurePrefsHelper.getPass(applicationContext)
            if (savedUrl.isEmpty() || savedUser.isEmpty() || savedPass.isEmpty()) return@withContext Result.failure()

            // SEPARATION-OF-CONCERNS: Ưu tiên đẩy 100% việc tính aHash 64-bit về NAS Rust NEON SIMD
            // (/api/fingerprint/compute) — điện thoại KHÔNG tải 512KB ảnh hay decode Bitmap nữa.
            val apiBaseUrl = savedUrl.toApiBaseUrl()
            val serverResult = computeFingerprintsOnNas(apiBaseUrl, savedUser, savedPass)
            if (serverResult != null) {
                val (computed, remaining) = serverResult
                SystemLogger.log(
                    "SUCCESS",
                    "FingerprintWorker",
                    "NAS Rust da tinh aHash: computed=$computed, remaining_pending=$remaining (0KB tai ve phone)"
                )
                return@withContext if (isStopped) Result.retry() else Result.success()
            }

            // Fallback cho NAS đời cũ chưa có endpoint /api/fingerprint/compute
            val pending = fetchPendingFingerprints(apiBaseUrl, savedUser, savedPass)
            if (pending.isEmpty()) {
                SystemLogger.log("INFO", "FingerprintWorker", "Khong phat hien tap tin yeu cau tao chu ky so (fingerprint).")
                return@withContext Result.success()
            }
            SystemLogger.log("INFO", "FingerprintWorker", "Khoi tao qua trinh cap phat chu ky so cho ${pending.size} tap tin...")
            var successCount = 0; var failCount = 0
            // Track IO/5xx errors rieng de quyet dinh retry o cuoi loop
            var retryableErrors = 0
            val batch = mutableListOf<Map<String, Any>>()
            for (file in pending) {
                if (isStopped) break
                try {
                    // Phone-side: tai raw image 512KB dau tien qua WebDAV Range,
                    // decode bitmap + tinh aHash tren phone CPU — KHONG goi NAS /api/thumb
                    val filePath = file["path"] as? String ?: ""
                    val reqHost = runCatching { java.net.URI(filePath).host.orEmpty().lowercase() }.getOrDefault("")
                    val savedHost = runCatching { java.net.URI(savedUrl).host.orEmpty().lowercase() }.getOrDefault("")
                    if (reqHost.isNotEmpty() && savedHost.isNotEmpty() && reqHost != savedHost) { failCount++; continue }
                    val authHeader = WebDavManager.AuthState(user = savedUser, pass = savedPass).authHeader
                    val thumbRequest = okhttp3.Request.Builder()
                        .url(filePath)
                        .header("Range", "bytes=0-524287") // 512KB — du cho aHash
                        .header("Authorization", authHeader)
                        .build()
                    NasApplication.instance.sharedHttpClient.newCall(thumbRequest).execute().use { resp ->
                        when {
                            resp.isSuccessful || resp.code == 206 -> {
                                // CC-005 P0: capped body 1MB (server co the bo qua Range -> tra full file OOM)
                                val imageBytes: ByteArray? = run {
                                    val stream = resp.body?.byteStream() ?: return@use run { failCount++ }
                                    val out = java.io.ByteArrayOutputStream(524288)
                                    val buf = ByteArray(8192)
                                    var tot = 0
                                    stream.use { ins ->
                                        while (true) {
                                            if (isStopped) { retryableErrors++; failCount++; return@run null }
                                            val r = ins.read(buf)
                                            if (r <= 0) break
                                            tot += r
                                            if (tot > 1048576) { failCount++; return@run null }
                                            out.write(buf, 0, r)
                                        }
                                    }
                                    out.toByteArray()
                                }
                                if (imageBytes == null || imageBytes.isEmpty()) return@use run { failCount++ }
                                // Peek full dimensions to set inSampleSize
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
                                var sampleSize = 1
                                while (opts.outWidth / sampleSize > 512 || opts.outHeight / sampleSize > 512) sampleSize *= 2
                                val decodeOpts = BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                }
                                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
                                if (bitmap != null) {
                                    try {
                                        val aHash = ImageFingerprint.computeAHash(bitmap)
                                        if (aHash != null) {
                                            // Luu vao Room (giu logic cu) VA dong bo len NAS API
                                            db.fileDao().updateImageFingerprint(filePath, aHash)
                                            batch.add(mapOf(
                                                "path" to filePath,
                                                "source_version" to ("android-" + Build.VERSION.SDK_INT),
                                                "ahash" to aHash,
                                                "size" to ((file["size"] as? Number)?.toLong() ?: 0L),
                                                "mtime" to ((file["mtime"] as? Number)?.toLong() ?: 0L)
                                            ))
                                            successCount++
                                        } else failCount++
                                    } finally { bitmap.recycle() }
                                } else failCount++
                            }
                            // 5xx: transient server error — khong gắn NOT_SUPPORTED, retry sau
                            resp.code in 500..599 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 408/429: transient (timeout / rate-limit) → retry, KHONG gắn NOT_SUPPORTED
                            resp.code == 408 || resp.code == 429 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 401/403: auth thay doi → retry thu lai sau khi refresh credentials
                            resp.code == 401 || resp.code == 403 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 404/410: file bien mat tren NAS (race voi delete) — khong phai loi file
                            resp.code == 404 || resp.code == 410 -> {
                                // Khong gắn NOT_SUPPORTED (file co the quay lai), khong tinh retryable
                                // → success se skip file nay vin vi trong pass hien tai.
                                failCount++
                            }
                            // 409 Conflict: race voi concurrent write — retry
                            resp.code == 409 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 4xx khac (400/451…): terminal client error → NOT_SUPPORTED
                            else -> {
                                db.fileDao().updateImageFingerprint(filePath, "NOT_SUPPORTED"); failCount++
                            }
                        }
                    }
                    delay(200)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || isStopped) {
                        // Guideline: CancellationException / isStopped → Result.retry() (khong nuot thanh success)
                        retryableErrors++
                        break
                    }
                    // Network/IO exception = transient → count la retryable
                    retryableErrors++
                    failCount++
                    delay(1000)
                }
            }

            // Dong bo batch aHash len NAS (neu co). Client khac tai su dung thay tinh toan lai.
            if (batch.isNotEmpty()) {
                postFingerprintsToNas(apiBaseUrl, savedUser, savedPass, batch)
            }

            // Quy tuc theo guideline repo: isStopped → retry (khong success de khoi nuot cancellation)
            // Log INFO tong ket truoc, decision + log ket qua thay phia duoi
            // CC-005 P0: bound retry runAttemptCount<3 (truoc retry vo han khi NAS 5xx lien tuc).
            return@withContext when {
                isStopped -> {
                    SystemLogger.log("WARNING", "FingerprintWorker",
                        "Worker bi stop giua chung — retry pass (success=$successCount fail=$failCount retryable=$retryableErrors)")
                    Result.retry()
                }
                retryableErrors > 0 && runAttemptCount < 3 -> {
                    SystemLogger.log("WARNING", "FingerprintWorker",
                        "Co $retryableErrors retryable error(s) — se retry (success=$successCount fail=$failCount)")
                    Result.retry()
                }
                retryableErrors > 0 -> {
                    SystemLogger.log("WARNING", "FingerprintWorker",
                        "Retryable qua 3 lan — dung lai, success tam (success=$successCount fail=$failCount)")
                    Result.success()
                }
                else -> {
                    SystemLogger.log("SUCCESS", "FingerprintWorker",
                        "Hoan tat: success=$successCount fail=$failCount (retryable=$retryableErrors)")
                    Result.success()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // P0-4: user cancel → failure, KHONG retry
            if (isStopped) return@withContext Result.failure()
            SystemLogger.log("ERROR", "FingerprintWorker", "Loi: ${e.message}")
            return@withContext Result.retry()
        }
    }

    // ========================================================================
    // NAS API integration: Ưu tiên NAS Rust /api/fingerprint/compute (NEON SIMD)
    // ========================================================================

    /**
     * Yêu cầu NAS tự tính aHash 64-bit bằng Rust NEON SIMD đa luồng và lưu trực tiếp vào SQLite.
     * Trả về Pair(totalComputed, remainingPending) nếu NAS hỗ trợ, hoặc null nếu cần fallback.
     */
    private suspend fun computeFingerprintsOnNas(apiBaseUrl: String, user: String, pass: String): Pair<Int, Int>? {
        val authHeader = WebDavManager.AuthState(user = user, pass = pass).authHeader
        val url = apiBaseUrl.trimEnd('/') + "/api/fingerprint/compute"
        var totalComputed = 0
        var lastRemaining = 0
        val maxBatches = 5
        for (batchIdx in 0 until maxBatches) {
            if (isStopped) break
            try {
                val payload = JSONObject().apply {
                    put("limit", 100)
                    put("workers", 4)
                }.toString()
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val batchRes = NasApplication.instance.sharedHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 404) return null // NAS cũ chưa có endpoint Rust compute -> fallback
                    if (!resp.isSuccessful) {
                        SystemLogger.log("WARNING", "FingerprintWorker", "NAS /api/fingerprint/compute HTTP ${resp.code}")
                        return if (batchIdx > 0) Pair(totalComputed, lastRemaining) else null
                    }
                    val body = resp.body?.string() ?: return Pair(totalComputed, lastRemaining)
                    val json = JSONObject(body)
                    Pair(json.optInt("computed", 0), json.optInt("remaining_pending", 0))
                }
                totalComputed += batchRes.first
                lastRemaining = batchRes.second
                if (lastRemaining <= 0 || batchRes.first <= 0) break
                delay(200)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SystemLogger.log("WARNING", "FingerprintWorker", "NAS /api/fingerprint/compute loi: ${e.message}")
                return if (batchIdx > 0) Pair(totalComputed, lastRemaining) else null
            }
        }
        return Pair(totalComputed, lastRemaining)
    }

    private suspend fun fetchPendingFingerprints(baseUrl: String, user: String, pass: String): List<Map<String, Any>> {
        return try {
            val authHeader = WebDavManager.AuthState(user = user, pass = pass).authHeader
            val url = baseUrl.toString().trimEnd { it == '/' } + "/api/fingerprint/pending?limit=512"
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .build()
            NasApplication.instance.sharedHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string() ?: return@use emptyList()
                val json = JSONObject(body)
                val files = json.optJSONArray("files")
                if (files == null) return@use emptyList()
                val result = mutableListOf<Map<String, Any>>()
                for (i in 0 until files.length()) {
                    val o = files.getJSONObject(i)
                    result += mapOf(
                        "path" to o.optString("path", ""),
                        "name" to o.optString("name", ""),
                        "size" to o.optLong("size", 0L),
                        "mtime" to o.optLong("mtime", 0L)
                    )
                }
                result
            }
        } catch (e: Exception) {
            SystemLogger.log("WARNING", "FingerprintWorker", "Khong lay duoc danh sach pending fingerprint tu NAS: ${e.message}")
            emptyList()
        }
    }

    private suspend fun postFingerprintsToNas(baseUrl: String, user: String, pass: String, items: List<Map<String, Any>>) {
        return try {
            val authHeader = WebDavManager.AuthState(user = user, pass = pass).authHeader
            val url = baseUrl.toString().trimEnd { it == '/' } + "/api/fingerprint/save"
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                o.put("path", it["path"] ?: "")
                o.put("source_version", it["source_version"] ?: "android")
                o.put("ahash", it["ahash"] ?: "")
                o.put("size", it["size"] ?: 0)
                o.put("mtime", it["mtime"] ?: 0)
                arr.put(o)
            }
            val jsonBody = JSONObject().put("items", arr)
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            NasApplication.instance.sharedHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    SystemLogger.log("WARNING", "FingerprintWorker", "POST aHash len NAS that bai: HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            SystemLogger.log("WARNING", "FingerprintWorker", "Khong POST duoc aHash len NAS: ${e.message}")
        }
    }
}
