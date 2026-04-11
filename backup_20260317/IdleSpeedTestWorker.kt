package com.nas.naswebdav

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class IdleSpeedTestWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val currentUrl = inputData.getString("currentUrl") ?: return@withContext Result.failure()
        try {
            val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"
            val request = okhttp3.Request.Builder()
                .url("$apiBaseUrl/api/disk/speedtest")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()

            // Cấu hình Connection Pool cho tác vụ ngầm
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = org.json.JSONObject(body)
                    val wSpeed = json.optString("write_speed", "Lỗi")
                    val rSpeed = json.optString("read_speed", "Lỗi")

                    val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
                    val time = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    prefs.edit()
                        .putString("last_speed_write", wSpeed)
                        .putString("last_speed_read", rSpeed)
                        .putString("last_speed_time", time)
                        .apply()

                    return@withContext Result.success()
                }
            }
        } catch (e: Exception) {
            val db = NasApplication.instance.database
            db.logDao().insertLog(SystemLog(type = "WARNING", module = "SpeedTest", message = "Không thể đo tốc độ đĩa ngầm: ${e.message}"))
        }
        return@withContext Result.failure()
    }
}