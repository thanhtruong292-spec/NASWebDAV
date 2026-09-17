package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nas.naswebdav.utils.SystemLogger

/**
 * Task 7: worker kiểm tra ngưỡng cảnh báo định kỳ (CPU/RAM theo AlertRules).
 * Đọc /api/status/realtime, vượt ngưỡng → notification + log có traceId.
 * Schedule: xem scheduleAlertCheck() trong PowerActions (periodic 1h, cần mạng).
 */
class AlertCheckWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "AlertCheckWorker"
        const val CHANNEL_ID = "nas_alerts"
    }

    override suspend fun doWork(): Result {
        val traceId = SystemLogger.newTraceId()
        return try {
            val prefs = com.nas.naswebdav.utils.PreferencesRepository
                .get(applicationContext)
            val rules = prefs.getAlertRules()
            if (!rules.enabled) return Result.success()
            val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
                .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
            val user = SecurePrefsHelper.getUser(applicationContext)
            val pass = SecurePrefsHelper.getPass(applicationContext)
            if (url.isEmpty() || user.isEmpty()) return Result.success()
            val req = okhttp3.Request.Builder()
                .url("${url.toApiBaseUrl()}/api/status/realtime")
                .header("Authorization", WebDavManager.AuthState(url, user, pass).authHeader)
                .build()
            val body = NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.success()
                resp.body?.string() ?: return Result.success()
            }
            val json = org.json.JSONObject(body)
            val cpu = json.optDouble("cpu", -1.0)
            val ram = json.optDouble("ram", -1.0)
            val fired = mutableListOf<String>()
            if (cpu >= 0 && cpu >= rules.cpuThreshold) fired.add("CPU ${cpu.toInt()}% ≥ ${rules.cpuThreshold}%")
            if (ram >= 0 && ram >= rules.ramThreshold) fired.add("RAM ${ram.toInt()}% ≥ ${rules.ramThreshold}%")
            if (fired.isNotEmpty()) {
                val msg = fired.joinToString("; ")
                SystemLogger.log("WARNING", "AlertCheck", msg, traceId)
                notifyAlert(msg)
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            SystemLogger.log("WARNING", "AlertCheck", "Kiểm tra thất bại: ${e.message}", traceId)
            Result.success()
        }
    }

    private fun notifyAlert(msg: String) {
        try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cảnh báo NAS", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Cảnh báo NAS")
                .setContentText(msg)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(9904, notif)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
    }
}
