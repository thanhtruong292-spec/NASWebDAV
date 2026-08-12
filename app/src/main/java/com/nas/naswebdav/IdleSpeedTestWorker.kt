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
import androidx.core.content.edit



class IdleSpeedTestWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Terminal config: thiếu URL/credentials là config bug → failure, không retry vô hạn
        val currentUrl = inputData.getString("currentUrl") ?: return@withContext Result.failure()
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (user.isEmpty() || pass.isEmpty()) return@withContext Result.failure()

        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val request = okhttp3.Request.Builder()
                .url("$apiBaseUrl/api/disk/speedtest")
                .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                .post(ByteArray(0).toRequestBody(null, 0, 0))
                .build()
            NasApplication.instance.sharedHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val json = JSONObject(response.body?.string() ?: "")
                        val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
                        prefs.edit {
                            putString("last_speed_write", json.optString("write_speed", "Lỗi"))
                            putString("last_speed_read", json.optString("read_speed", "Lỗi"))
                            putString("last_speed_time", com.nas.naswebdav.utils.FormatUtils.formatDateTime(System.currentTimeMillis()))
                        }
                        return@withContext Result.success()
                    }
                    // 408/429 = transient (timeout/rate limit) → retry
                    response.code == 408 || response.code == 429 -> {
                        SystemLogger.log("WARNING", "SpeedTest",
                            "Speed test endpoint ${response.code} — sẽ retry (transient)")
                        return@withContext Result.retry()
                    }
                    // 4xx khác: auth/permission/route không tồn tại → không tự hồi phục
                    response.code in 400..499 -> {
                        SystemLogger.log("WARNING", "SpeedTest",
                            "Speed test endpoint trả về ${response.code} — không retry (client error)")
                        return@withContext Result.failure()
                    }
                    // 5xx + IO errors: transient → retry theo backoff
                    else -> {
                        SystemLogger.log("WARNING", "SpeedTest",
                            "Speed test endpoint trả về ${response.code} — sẽ retry")
                        return@withContext Result.retry()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // P0-4: user cancel → failure, KHÔNG retry
            if (isStopped) {
                return@withContext Result.failure()
            }
            SystemLogger.log("WARNING", "SpeedTest",
                "Không thể thực thi speed test: ${e.message} — sẽ retry")
            return@withContext Result.retry()
        }
    }
}

