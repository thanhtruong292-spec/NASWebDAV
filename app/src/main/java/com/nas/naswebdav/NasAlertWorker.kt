package com.nas.naswebdav

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.nas.naswebdav.utils.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NasAlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = PreferencesRepository.get(applicationContext)
        val rules = prefs.getAlertRules()
        if (!rules.enabled) return@withContext Result.success()

        val base = WebDavManager.currentBaseUrl.ifBlank { SecurePrefsHelper.getUrl(applicationContext) }
        if (base.isBlank()) return@withContext Result.success()
        val apiBase = base.toApiBaseUrl()

        try {
            val req = Request.Builder()
                .url("$apiBase/api/status")
                .let(WebDavManager::tagCurrentAuth)
                .build()
            NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.success()
                val body = resp.body?.string() ?: return@withContext Result.success()
                val j = JSONObject(body)
                val cpuPct = j.optDouble("cpu_percent", 0.0)
                val hddTemp = j.optDouble("hdd_temp", 0.0)
                val diskPct = j.optDouble("disk_percent", 0.0)
                val smartStatus = j.optString("smart_status", "")
                NasAlertEvaluator.evaluateAndNotify(applicationContext, cpuPct, hddTemp, diskPct, smartStatus)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        Result.success()
    }
}

object NasAlertEvaluator {
    private const val CHANNEL_ID = "nas_health_alerts"
    private const val COOLDOWN_MS = 30 * 60 * 1000L

    fun evaluateAndNotify(
        context: Context,
        cpuPct: Double,
        hddTempC: Double,
        diskPct: Double,
        smartStatus: String
    ) {
        val prefs = PreferencesRepository.get(context)
        val rules = prefs.getAlertRules()
        if (!rules.enabled) return

        val alerts = mutableListOf<Pair<String, String>>()
        if (hddTempC > 0 && hddTempC >= rules.hddTempThreshold) {
            alerts.add("hdd" to "Nhiệt độ ổ cứng cao: ${hddTempC.toInt()}°C (ngưỡng ${rules.hddTempThreshold}°C)")
        }
        if (cpuPct >= rules.cpuThreshold) {
            alerts.add("cpu" to "CPU NAS đang quá tải: ${cpuPct.toInt()}% (ngưỡng ${rules.cpuThreshold}%)")
        }
        if (diskPct >= rules.diskThreshold) {
            alerts.add("disk" to "Dung lượng ổ đĩa sắp đầy: ${diskPct.toInt()}% (ngưỡng ${rules.diskThreshold}%)")
        }
        if (rules.smartAlert && smartStatus.uppercase().contains("FAILED")) {
            alerts.add("smart" to "Cảnh báo S.M.A.R.T ổ cứng: $smartStatus — sao lưu dữ liệu ngay!")
        }
        if (alerts.isEmpty()) return

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)
        val now = System.currentTimeMillis()
        alerts.forEachIndexed { idx, (key, msg) ->
            val last = prefs.getLastAlertTimestamp(key)
            if (now - last >= COOLDOWN_MS) {
                prefs.setLastAlertTimestamp(key, now)
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Cảnh báo sức khỏe NAS")
                    .setContentText(msg)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(context).notify(4100 + idx + key.hashCode(), notif)
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Cảnh báo hệ thống NAS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Thông báo khi nhiệt độ, CPU, dung lượng hoặc S.M.A.R.T vượt ngưỡng" }
                mgr.createNotificationChannel(ch)
            }
        }
    }
}

fun scheduleNasAlertWorker(context: Context) {
    val rules = PreferencesRepository.get(context).getAlertRules()
    val wm = WorkManager.getInstance(context)
    if (!rules.enabled) {
        wm.cancelUniqueWork("nas_health_alert_worker")
        return
    }
    val req = PeriodicWorkRequestBuilder<NasAlertWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    wm.enqueueUniquePeriodicWork("nas_health_alert_worker", ExistingPeriodicWorkPolicy.UPDATE, req)
}
