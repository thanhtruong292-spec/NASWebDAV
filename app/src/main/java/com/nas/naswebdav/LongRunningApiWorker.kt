package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LongRunningApiWorker — Foreground Worker cho các tác vụ API gọi NAS kéo dài (Unzip, Organize...).
 *
 * Những API này có thể mất 1-10 phút để NAS xử lý xong.
 * Nếu chạy trên viewModelScope, App bị tắt → mất kết quả + user không biết xong chưa.
 * Worker này giữ notification "Đang giải nén..." và bắn kết quả khi xong.
 *
 * Input Data:
 *   - "taskType"  : "UNZIP" | "ORGANIZE"
 *   - "apiUrl"    : URL API đầy đủ (ví dụ: http://192.168.100.114:5050/api/file/unzip)
 *   - "jsonBody"  : JSON payload gửi kèm POST (optional)
 *   - "taskLabel" : Tên hiển thị trên notification (ví dụ: "Giải nén file.zip")
 */
class LongRunningApiWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "long_running_api_channel"
        const val NOTIFICATION_ID = 9011
        private const val TAG = "LongRunAPI"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Tác vụ NAS nặng",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Hiển thị tiến trình giải nén/sắp xếp file trên NAS"
                    setShowBadge(false)
                }
                (context.getSystemService(NotificationManager::class.java))
                    ?.createNotificationChannel(channel)
            }
        }
    }

    private fun safeDataText(value: String, maxChars: Int = 512): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "..."
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskType = inputData.getString("taskType") ?: return@withContext Result.failure()
        val apiUrl = inputData.getString("apiUrl") ?: return@withContext Result.failure()
        val jsonBody = inputData.getString("jsonBody") ?: ""
        val taskLabel = inputData.getString("taskLabel") ?: "Đang xử lý..."

        // Kết nối xác thực
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (user.isEmpty() || pass.isEmpty()) return@withContext Result.failure()

        // Tạo Foreground Notification (indeterminate progress)
        createChannel(applicationContext)
        val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(taskLabel)
            .setContentText("Đang chờ NAS xử lý...")
            .setProgress(0, 0, true) // Indeterminate (vòng xoay)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            // Tu Android 10 (Q) tro len bat buoc khai bao foregroundServiceType
            // khop manifest, neu khong se nem MissingForegroundServiceTypeException.
            setForeground(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ForegroundInfo(
                        NOTIFICATION_ID, notificationBuilder.build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build())
                }
            )
        } catch (e: Exception) {
            try {
                androidx.core.app.NotificationManagerCompat.from(applicationContext)
                    .notify(NOTIFICATION_ID, notificationBuilder.build())
            } catch (_: Exception) {}
        }

        // Báo trạng thái cho UI
        setProgress(workDataOf("status" to "processing", "taskType" to taskType))

        try {
            // Xây dựng request
            val requestBody = if (jsonBody.isNotEmpty()) {
                jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            } else {
                ByteArray(0).toRequestBody(null, 0, 0)
            }

            val request = okhttp3.Request.Builder()
                .url(apiUrl)
                .post(requestBody)
                .header("Authorization", okhttp3.Credentials.basic(user, pass))
                .build()

            // Client với timeout cực lớn cho tác vụ NAS nặng, Fix Bug #41: dùng singleton client
            val longClient = NasApplication.instance.longRunningApiClient

            longClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val isSuccess = response.isSuccessful

                // Phân tích kết quả
                val resultMessage = try {
                    val json = org.json.JSONObject(body)
                    when (taskType) {
                        "UNZIP" -> {
                            if (isSuccess) "Giải nén thành công! ✅"
                            else "Giải nén thất bại: ${json.optString("error", "Mã ${response.code}")}"
                        }
                        "ORGANIZE" -> {
                            val count = json.optInt("moved_count", 0)
                            if (isSuccess) "Hoàn tất! Đã gom $count video. ✅"
                            else "Lỗi sắp xếp: ${json.optString("error", "Mã ${response.code}")}"
                        }
                        else -> if (isSuccess) "Hoàn tất! ✅" else "Lỗi: Mã ${response.code}"
                    }
                } catch (_: Exception) {
                    if (isSuccess) "Hoàn tất! ✅" else "Lỗi: Mã ${response.code}"
                }

                // Báo cáo kết quả cho UI
                setProgress(workDataOf(
                    "status" to if (isSuccess) "success" else "error",
                    "taskType" to taskType,
                    "message" to safeDataText(resultMessage),
                    "responseBody" to safeDataText(body),
                    "responseCode" to response.code
                ))

                // Ghi log hệ thống
                try {
                    val db = NasApplication.instance.database
                    db.logDao().insertLog(SystemLog(
                        type = if (isSuccess) "SUCCESS" else "ERROR",
                        module = "NAS API",
                        message = resultMessage
                    ))
                } catch (_: Exception) {}

                // Notification hoàn tất
                val doneNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(
                        if (isSuccess) android.R.drawable.stat_sys_upload_done
                        else android.R.drawable.stat_notify_error
                    )
                    .setContentTitle(resultMessage)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                try {
                    NotificationManagerCompat.from(applicationContext)
                        .notify(NOTIFICATION_ID, doneNotification.build())
                } catch (_: SecurityException) {}

                return@withContext if (isSuccess) Result.success() else Result.failure()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Lỗi API tác vụ dài: ${e.message}", e)

            setProgress(workDataOf(
                "status" to "error",
                "taskType" to taskType,
                "message" to safeDataText("Lỗi kết nối: ${e.message}")
            ))

            // Notification lỗi
            val errorNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("$taskLabel — Thất bại")
                .setContentText("Lỗi: ${e.message?.take(100)}")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            try {
                NotificationManagerCompat.from(applicationContext)
                    .notify(NOTIFICATION_ID, errorNotification.build())
            } catch (_: SecurityException) {}

            // Ghi log
            try {
                NasApplication.instance.database.logDao().insertLog(SystemLog(
                    type = "ERROR", module = "NAS API",
                    message = "$taskLabel thất bại: ${e.message?.take(100)}"
                ))
            } catch (_: Exception) {}

            return@withContext Result.failure()
        }
    }
}
