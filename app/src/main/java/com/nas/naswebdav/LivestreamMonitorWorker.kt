package com.nas.naswebdav

import com.nas.naswebdav.toApiBaseUrl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * LivestreamMonitorWorker — Foreground Worker theo dõi tiến trình ghi livestream trên NAS.
 *
 * Chạy hoàn toàn độc lập với vòng đời app:
 *  - Khi user ẩn app  → vẫn chạy, vẫn hiện notification
 *  - Khi user thoát app → vẫn chạy, WorkManager giữ lại
 *  - Khi stream kết thúc → tự thoát, thông báo "Hoàn tất"
 *
 * Input:
 *   KEY_JOB_ID    : job_id từ NAS API
 *   KEY_NAS_HOST  : IP của NAS (vd: 192.168.100.254)
 *   KEY_PLATFORM  : tiktok | facebook | youtube | ...
 */
class LivestreamMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID     = "livestream_recording_channel"
        const val NOTIFICATION_BASE_ID = 9020   // ID cơ sở, mỗi job +1
        const val WORK_NAME_PREFIX = "LIVESTREAM_MONITOR_"

        const val KEY_JOB_ID   = "job_id"
        const val KEY_NAS_HOST = "nas_host"
        const val KEY_PLATFORM = "platform"
        const val KEY_NOTIF_ID = "notif_id"

        // Output keys
        const val OUT_STATUS      = "status"
        const val OUT_FILE_SIZE   = "file_size"
        const val OUT_DURATION    = "duration"
        const val OUT_DURATION_SECONDS = "duration_seconds"
        const val OUT_STARTED_TS   = "started_ts"
        const val OUT_SPEED       = "speed"
        const val OUT_OUTPUT_FILE = "output_file"
        const val OUT_JOB_ID      = "job_id_out"
        const val OUT_WATCH_USER  = "watch_username"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghi hình Livestream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo tiến trình ghi livestream về NAS"
                setShowBadge(true)
                setSound(null, null)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        private fun notificationIdForJob(jobId: String): Int {
            return NOTIFICATION_BASE_ID + (Math.abs(jobId.hashCode()) % 100)
        }

        fun enqueue(context: Context, jobId: String, nasHost: String, platform: String): androidx.work.Operation {
            createChannel(context)
            // Mỗi job có notifId riêng (9020, 9021, 9022...)
            val notifId = notificationIdForJob(jobId)
            val request = OneTimeWorkRequestBuilder<LivestreamMonitorWorker>()
                .setInputData(workDataOf(
                    KEY_JOB_ID   to jobId,
                    KEY_NAS_HOST to nasHost,
                    KEY_PLATFORM to platform,
                    KEY_NOTIF_ID to notifId
                ))
                .addTag(WORK_NAME_PREFIX + jobId)
                .addTag("LIVESTREAM_ALL")  // Tag chung để query tất cả
                .build()
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_PREFIX + jobId, ExistingWorkPolicy.KEEP, request)
        }

        fun cancelJob(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PREFIX + jobId)
            clearJobNotifications(context, jobId)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("LIVESTREAM_ALL")
            clearAllNotifications(context)
        }

        fun clearJobNotifications(context: Context, jobId: String) {
            val notifId = notificationIdForJob(jobId)
            try {
                androidx.core.app.NotificationManagerCompat.from(context).cancel(notifId)
                androidx.core.app.NotificationManagerCompat.from(context).cancel(notifId + 1000)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }

        fun clearAllNotifications(context: Context) {
            val nm = androidx.core.app.NotificationManagerCompat.from(context)
            for (notifId in NOTIFICATION_BASE_ID until NOTIFICATION_BASE_ID + 100) {
                try {
                    nm.cancel(notifId)
                    nm.cancel(notifId + 1000)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    // FIX: Dùng fastApiClient từ NasApplication thay vì tạo OkHttpClient riêng cho mỗi Worker.
    // Mỗi client riêng có connection pool riêng → tốn RAM không cần thiết khi có nhiều stream cùng lúc.
    // fastApiClient đã được cấu hình với timeout phù hợp (connectTimeout=15s, readTimeout=30s).
    private val httpClient get() = NasApplication.instance.fastApiClient

    private fun safeDataText(value: String, maxChars: Int = 512): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "..."
    }

    private fun notificationsEnabled(): Boolean {
        return try {
            androidx.core.app.NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            false
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifId = inputData.getInt(KEY_NOTIF_ID, NOTIFICATION_BASE_ID)
        return buildForegroundInfo(notifId, "Đang ghi hình...", "Đang khởi động...")
    }

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId    = inputData.getString(KEY_JOB_ID)    ?: return@withContext Result.failure()
        val nasHost  = inputData.getString(KEY_NAS_HOST)  ?: return@withContext Result.failure()
        val platform = inputData.getString(KEY_PLATFORM)  ?: "livestream"

        val platformLabel = when (platform) {
            "tiktok"   -> "TikTok"
            "facebook" -> "Facebook"
            "youtube"  -> "YouTube"
            "shopee"   -> "Shopee"
            else       -> "Livestream"
        }
        val platformIcon = when (platform) {
            "tiktok"   -> "\uD83C\uDFB5"  // 🎵
            "facebook" -> "\uD83D\uDCD8"  // 📘
            "youtube"  -> "▶"
            else       -> "\uD83D\uDCF9"  // 📹
        }

        val notifId = inputData.getInt(KEY_NOTIF_ID, NOTIFICATION_BASE_ID)

        createChannel(applicationContext)

        // CRITICAL FIX: Dùng setForegroundAsync() thay vì NotificationManagerCompat.notify()
        // trực tiếp. Android 14+ yêu cầu foreground service phải được khởi tạo qua
        // setForegroundAsync() — nếu không Worker sẽ bị kill sau vài giây chạy nền.
        try {
            setForegroundAsync(buildForegroundInfo(
                notifId = notifId,
                title   = "$platformIcon Đang ghi livestream $platformLabel",
                content = "Đang kết nối..."
            ))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            val exName = e.javaClass.name
            val isBgRestriction = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && exName.contains("ForegroundServiceStartNotAllowed")
            val isMissingType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && (exName.contains("MissingForegroundServiceType") || exName.contains("ForegroundServiceType"))
            when {
                isBgRestriction -> {
                    // Android 12+: app đang background → skip foreground, worker chạy tiếp
                    android.util.Log.w("LivestreamMonitor", "Background restriction: skip foreground (worker continues)", e)
                }
                isMissingType -> {
                    android.util.Log.e("LivestreamMonitor", "setForeground failed: missing foregroundServiceType", e)
                    return@withContext Result.failure()
                }
                else -> {
                    android.util.Log.w("LivestreamMonitor", "setForeground non-fatal: ${e.javaClass.simpleName}", e)
                }
            }
        }

        // Lấy credentials

        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)

        val activeBaseUrl = com.nas.naswebdav.SmartNetworkManager.getActiveBaseUrl(applicationContext)
        val statusUrl = (if (activeBaseUrl.isNotEmpty()) activeBaseUrl else "http://$nasHost").toApiBaseUrl() + "/api/livestream/status"
        var consecutiveErrors = 0
        var finalStatus = "recording"
        var finalErrorReason = ""

        while (!isStopped) {
            // FIX: backoff khi lien tiep loi (NAS reboot, mat mang). Khoe thi
            // poll 3s; loi >=2 -> 6s, 9s, 12s, capped 30s. Tranh hammering khi
            // co nhieu Worker cung loi luc.
            val pollDelay = when {
                consecutiveErrors == 0 -> 3_000L
                else -> (3_000L * (consecutiveErrors + 1)).coerceAtMost(30_000L)
            }
            delay(pollDelay)

            try {
                val requestBuilder = Request.Builder().url(statusUrl)
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    val credential = WebDavManager.AuthState(user = user, pass = pass).authHeader
                    requestBuilder.header("Authorization", credential)
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                val bodyStr = response.use { resp ->
                    if (!resp.isSuccessful) {
                        consecutiveErrors++
                        // DEAD CODE FIX: bỏ `if (consecutiveErrors >= 5) return@use null` ngay trước return@use null
                        // — if vô dụng vì đằng nào cũng return null. Logic ngắt vòng lặp nằm ở `if (bodyStr == null)` bên ngoài.
                        return@use null
                    }
                    resp.body?.string() ?: "{}"
                }
                if (bodyStr == null) {
                    // NAS loi lien tuc (>=5): khong de finalStatus o "recording"
                    // roi xuong nhanh success ao ben duoi.
                    if (consecutiveErrors >= 5) {
                        finalStatus = "error"
                        finalErrorReason = "Mất kết nối NAS khi theo dõi (lỗi liên tục)"
                        break
                    }
                    continue
                }
                val json = JSONObject(bodyStr)
                val jobs = json.optJSONArray("jobs") ?: continue

                // Tìm job của chúng ta
                var found = false
                for (i in 0 until jobs.length()) {
                    val job = jobs.optJSONObject(i) ?: continue
                    if (job.optString("job_id") != jobId) continue
                    found = true
                    finalStatus = job.optString("status", "recording")

                    val fileSize  = job.optString("file_size", "0 B")
                    val duration  = job.optString("duration_display", "0h00m00s")
                    val durationSeconds = job.optLong("duration_seconds", 0L)
                    val startedTs = job.optLong("started_ts", 0L)
                    val speed     = job.optString("avg_speed", "")
                    val outFile   = safeDataText(job.optString("output_file", ""), 180)
                    val errorReason = safeDataText(job.optString("error_reason", ""), 512)
                    val watchUser = safeDataText(job.optString("watch_username", ""), 64)
                    if (errorReason.isNotEmpty()) {
                        finalErrorReason = errorReason
                    }
                    consecutiveErrors = 0

                    // Cập nhật notification
                    val contentLine = buildString {
                        append("⏱ $duration  •  💾 $fileSize")
                        if (speed.isNotEmpty()) append("  •  📡 $speed")
                    }
                    // Cập nhật notification qua NotificationManager để bypass giới hạn setForeground throttling của Android 12+
                    val foregroundInfo = buildForegroundInfo(
                        notifId = notifId,
                        title   = "$platformIcon Đang ghi livestream $platformLabel",
                        content = contentLine,
                        subText = if (outFile.isNotEmpty()) outFile else null
                    )
                    if (notificationsEnabled()) {
                    try {
                        androidx.core.app.NotificationManagerCompat.from(applicationContext)
                            .notify(notifId, foregroundInfo.notification)
                    } catch (_: SecurityException) {}
                    }

                    // Cập nhật output data để ViewModel observe được
                    setProgress(workDataOf(
                        OUT_JOB_ID      to jobId,
                        OUT_STATUS      to finalStatus,
                        OUT_FILE_SIZE   to fileSize,
                        OUT_DURATION    to duration,
                        OUT_DURATION_SECONDS to durationSeconds,
                        OUT_STARTED_TS   to startedTs,
                        OUT_SPEED       to speed,
                        OUT_OUTPUT_FILE to outFile,
                        OUT_WATCH_USER  to watchUser,
                        "error_reason"  to errorReason
                    ))

                    // Nếu stream kết thúc → thoát vòng lặp
                    if (finalStatus !in listOf("recording")) {
                        break
                    }
                }

                if (!found) {
                    // Job không còn trên NAS → coi như xong
                    consecutiveErrors++
                    if (consecutiveErrors >= 3) {
                        finalStatus = "error"
                        break
                    }
                } else if (finalStatus !in listOf("recording")) {
                    break
                }

            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                consecutiveErrors++
                android.util.Log.w("LivestreamMonitor", "Poll error ($consecutiveErrors/10): ${e.message}")
                if (consecutiveErrors >= 10) {
                    finalStatus = "error"
                    finalErrorReason = "Lỗi polling liên tục: ${e.message}"
                    break
                }
            }
        }

        if (isStopped) {
            return@withContext Result.failure()
        }

        // Hiện thông báo hoàn tất tùy theo kết quả
        clearJobNotifications(applicationContext, jobId)

        if (finalStatus == "error") {
            val msg = if (finalErrorReason.isNotEmpty()) "Lỗi: $finalErrorReason" else "Lỗi: Không tải được video. Nguồn livestream rỗng hoặc yt-dlp báo lỗi."
            showCompletionNotification(notifId, platformLabel, "⚠️", msg)
            return@withContext Result.failure(workDataOf(OUT_JOB_ID to jobId, OUT_STATUS to "error", "error_reason" to safeDataText(finalErrorReason, 512)))
        } else {
            showCompletionNotification(notifId, platformLabel, platformIcon, "Video đã được lưu vào thư mục Livestream/ trên NAS")
            return@withContext Result.success(workDataOf(OUT_JOB_ID to jobId, OUT_STATUS to "finished"))
        }
    }

    private fun buildForegroundInfo(
        notifId: Int,
        title: String,
        content: String,
        subText: String? = null
    ): ForegroundInfo {
        // PendingIntent mở lại app khi bấm notification
        val openIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val openPending = PendingIntent.getActivity(
            applicationContext, 0, openIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(content)
            .apply { if (subText != null) setSubText(subText) }
            .setOngoing(true)          // Không thể vuốt bỏ
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        // Từ Android 10 (Q) trở lên bắt buộc khai báo foregroundServiceType khớp
        // manifest, nếu không sẽ ném MissingForegroundServiceTypeException -> crash
        // worker khi NAS chưa ghi xong livestream.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notifId, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    private fun showCompletionNotification(baseNotifId: Int, platformLabel: String, icon: String, contentText: String) {
        val openIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val openPending = PendingIntent.getActivity(
            applicationContext, 1, openIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tính tiêu đề phù hợp (Nếu có icon cảnh báo => lỗi)
        val titleText = if (icon == "⚠️") "$icon Ghi hình $platformLabel thất bại!" else "$icon Ghi hình $platformLabel hoàn tất!"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(if (icon == "⚠️") android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(titleText)
            .setContentText(safeDataText(contentText, 180))
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            if (notificationsEnabled()) {
                applicationContext.getSystemService(NotificationManager::class.java)
                    ?.notify(baseNotifId + 1000, notification)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    }
}
