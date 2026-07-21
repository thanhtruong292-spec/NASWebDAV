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
            for (file in filesToProcess) {
                if (isStopped) break
                try {
                    // Phone-side: tải raw image 512KB đầu tiên qua WebDAV Range,
                    // decode bitmap + tính aHash trên phone CPU — KHÔNG gọi NAS /api/thumb
                    val authHeader = okhttp3.Credentials.basic(savedUser, savedPass)
                    val thumbRequest = okhttp3.Request.Builder()
                        .url(file.path)
                        .header("Range", "bytes=0-524287") // 512KB — đủ cho aHash
                        .header("Authorization", authHeader)
                        .build()
                    NasApplication.instance.sharedHttpClient.newCall(thumbRequest).execute().use { resp ->
                        if (resp.isSuccessful || resp.code == 206) {
                            val imageBytes = resp.body?.bytes() ?: return@use run { failCount++ }
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
                        } else {
                            db.fileDao().updateImageFingerprint(file.path, "NOT_SUPPORTED"); failCount++
                        }
                    }
                    delay(200)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { failCount++; delay(1000) }
            }
            SystemLogger.log("SUCCESS", "FingerprintWorker", "Hoàn tất quá trình cấp phát chữ ký số. Thành công: $successCount, Thất bại: $failCount")
            return@withContext Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { SystemLogger.log("ERROR", "FingerprintWorker", "Lỗi: ${e.message}"); return@withContext Result.failure() }
    }
}
