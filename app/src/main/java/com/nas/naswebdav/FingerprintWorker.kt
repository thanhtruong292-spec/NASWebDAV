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
        SystemLogger.log("INFO", "FingerprintWorker", "Bắt đầu tạo chữ ký số (fingerprint) cho tập tin")
        // AUDIT-20261104: worker quet hang tram file (>10s) phai setForeground truoc —
        // neu khong Android 12+ kill worker giua chung. Loi foreground thi tiep tuc chay.
        try {
            setForeground(makeForegroundInfo("fingerprint_channel", "Fingerprint", 1002, "Đang tạo chữ ký số..."))
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
            val filesToProcess = db.fileDao().getFilesWithoutFingerprint(acctKey)
            if (filesToProcess.isEmpty()) { SystemLogger.log("INFO", "FingerprintWorker", "Không phát hiện tập tin yêu cầu tạo chữ ký số (fingerprint)."); return@withContext Result.success() }
            SystemLogger.log("INFO", "FingerprintWorker", "Khởi tạo quá trình cấp phát chữ ký số cho ${filesToProcess.size} tập tin...")
            var successCount = 0; var failCount = 0
            // FIX D2c: Đã trong withContext(IO) → gọi suspend fun trực tiếp
            val savedUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
                .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
            val savedUser = SecurePrefsHelper.getUser(applicationContext)
            val savedPass = SecurePrefsHelper.getPass(applicationContext)
            if (savedUrl.isEmpty() || savedUser.isEmpty() || savedPass.isEmpty()) return@withContext Result.failure()
            // Track IO/5xx errors riêng để quyết định retry ở cuối loop
            var retryableErrors = 0
            for (file in filesToProcess) {
                if (isStopped) break
                try {
                    // Phone-side: tải raw image 512KB đầu tiên qua WebDAV Range,
                    // decode bitmap + tính aHash trên phone CPU — KHÔNG gọi NAS /api/thumb
                    // CC-005 P0: validate url host == saved host (chong SSRF via DB path bi nhiem)
                    val reqHost = runCatching { java.net.URI(file.path).host.orEmpty().lowercase() }.getOrDefault("")
                    val savedHost = runCatching { java.net.URI(savedUrl).host.orEmpty().lowercase() }.getOrDefault("")
                    if (reqHost.isNotEmpty() && savedHost.isNotEmpty() && reqHost != savedHost) { failCount++; continue }
                    val authHeader = WebDavManager.AuthState(user = savedUser, pass = savedPass).authHeader
                    val thumbRequest = okhttp3.Request.Builder()
                        .url(file.path)
                        .header("Range", "bytes=0-524287") // 512KB — đủ cho aHash
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
                                            db.fileDao().updateImageFingerprint(file.path, aHash); successCount++
                                        } else failCount++
                                    } finally { bitmap.recycle() }
                                } else failCount++
                            }
                            // 5xx: transient server error — không gắn NOT_SUPPORTED, retry sau
                            resp.code in 500..599 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 408/429: transient (timeout / rate-limit) → retry, KHÔNG gắn NOT_SUPPORTED
                            resp.code == 408 || resp.code == 429 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 401/403: auth thay đổi → retry thử lại sau khi refresh credentials
                            resp.code == 401 || resp.code == 403 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 404/410: file biến mất trên NAS (race với delete) — không phải lỗi file
                            resp.code == 404 || resp.code == 410 -> {
                                // Không gắn NOT_SUPPORTED (file có thể quay lại), không tính retryable
                                // → success sẽ skip file này vĩnh viễn trong pass hiện tại.
                                failCount++
                            }
                            // 409 Conflict: race với concurrent write — retry
                            resp.code == 409 -> {
                                retryableErrors++
                                failCount++
                            }
                            // 4xx khác (400/451…): terminal client error → NOT_SUPPORTED
                            else -> {
                                db.fileDao().updateImageFingerprint(file.path, "NOT_SUPPORTED"); failCount++
                            }
                        }
                    }
                    delay(200)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || isStopped) {
                        // Guideline: CancellationException / isStopped → Result.retry() (không nuốt thành success)
                        retryableErrors++
                        break
                    }
                    // Network/IO exception = transient → count là retryable
                    retryableErrors++
                    failCount++
                    delay(1000)
                }
            }
            // Quy tắc theo guideline repo: isStopped → retry (không success để khỏi nuốt cancellation)
            // Log INFO tổng kết trước, decision + log kết quả thật phía dưới
            // CC-005 P0: bound retry runAttemptCount<3 (truoc retry vo han khi NAS 5xx lien tuc).
            return@withContext when {
                isStopped -> {
                    SystemLogger.log("WARNING", "FingerprintWorker",
                        "Worker bị stop giữa chừng — retry pass (success=$successCount fail=$failCount retryable=$retryableErrors)")
                    Result.retry()
                }
                retryableErrors > 0 && runAttemptCount < 3 -> {
                    SystemLogger.log("WARNING", "FingerprintWorker",
                        "Có $retryableErrors retryable error(s) — sẽ retry (success=$successCount fail=$failCount)")
                    Result.retry()
                }
                retryableErrors > 0 -> {
                    SystemLogger.log("WARNING", "FingerprintWorker",
                        "Retryable qua 3 lan — dung lai, success tam (success=$successCount fail=$failCount)")
                    Result.success()
                }
                else -> {
                    SystemLogger.log("SUCCESS", "FingerprintWorker",
                        "Hoàn tất: success=$successCount fail=$failCount (retryable=$retryableErrors)")
                    Result.success()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // P0-4: user cancel → failure, KHÔNG retry
            if (isStopped) return@withContext Result.failure()
            SystemLogger.log("ERROR", "FingerprintWorker", "Lỗi: ${e.message}")
            return@withContext Result.retry()
        }
    }
}
