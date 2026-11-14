package com.nas.naswebdav

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nas.naswebdav.utils.SystemLogger
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CookiesExpiryWorker — kiểm tra cookies TikTok mỗi giờ, kể cả khi app đóng.
 *
 * Gọi /api/tiktok/live_watch, đọc cookies_status/expiry. Nếu xấu
 * (missing/expired/expiring_soon/revoked) thì bắn notification qua
 * CookiesExpiryNotifier (chống spam 24h). Nếu valid thì xoá latch để
 * lần hết sau vẫn báo lại.
 *
 * POST_NOTIFICATIONS đã khai báo trong Manifest; worker tự bỏ qua nếu
 * user tắt notification.
 */
class CookiesExpiryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SystemLogger.log("INFO", "CookiesExpiry", "Bắt đầu kiểm tra cookies TikTok")
        try {
            val user = SecurePrefsHelper.getUser(applicationContext)
            val pass = SecurePrefsHelper.getPass(applicationContext)
            val baseUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            if (baseUrl.isEmpty()) { SystemLogger.log("INFO", "CookiesExpiry", "Hoàn tất kiểm tra cookies TikTok (chưa có URL)"); return@withContext Result.success() }

            val apiUrl = baseUrl.toApiBaseUrl() + AppConfig.ApiEndpoints.TIKTOK_LIVE_WATCH
            val reqBuilder = Request.Builder().url(apiUrl).get()
                .let(WebDavManager::tagCurrentAuth)
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                reqBuilder.header(
                    "Authorization",
                    WebDavManager.AuthState(user = user, pass = pass).authHeader
                )
            }
            val bodyStr = NasApplication.instance.fastApiClient
                .newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) { SystemLogger.log("INFO", "CookiesExpiry", "Hoàn tất kiểm tra cookies TikTok (NAS trả HTTP ${resp.code})"); return@withContext Result.success() }
                    resp.body?.string() ?: run { SystemLogger.log("INFO", "CookiesExpiry", "Hoàn tất kiểm tra cookies TikTok (trả body rỗng)"); return@withContext Result.success() }
                }
            val json = JSONObject(bodyStr)
            val status = json.optString("cookies_status", "unknown")
            val message = json.optString("cookies_message", "")
            val expiryStr = json.optString("cookies_expiry_str", "")
            val daysLeft = json.optDouble("cookies_days_left", -1.0)

            if (status == "valid") {
                CookiesExpiryNotifier.clearLatch(applicationContext)
            } else {
                CookiesExpiryNotifier.checkAndNotify(
                    applicationContext, status, message, expiryStr, daysLeft
                )
            }
            SystemLogger.log("INFO", "CookiesExpiry", "Hoàn tất kiểm tra cookies TikTok (status=$status)")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // P0-4: hệ thống dừng worker → retry theo backoff, KHÔNG trả failure
            if (isStopped) {
                SystemLogger.log("WARNING", "CookiesExpiry", "Hệ thống dừng worker — sẽ retry theo backoff")
                return@withContext Result.retry()
            }
            android.util.Log.w("CookiesExpiry", "tick failed: ${e.message}")
            SystemLogger.log("WARNING", "CookiesExpiry", "Kết thúc kiểm tra cookies TikTok với lỗi: ${e.message}")
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "COOKIES_EXPIRY_CHECK"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // WorkManager min 15p — chọn 60p để nhẹ pin, backend cache sẵn 10p.
            val request = PeriodicWorkRequestBuilder<CookiesExpiryWorker>(
                60, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30L,
                    TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
