package com.nas.naswebdav

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Worker dinh ky poll /api/livestream/status va enqueue LivestreamMonitorWorker
 * cho moi job NAS bao "recording" ma may chua co Worker theo doi (vi dieu kien
 * job do TikTok watchdog tu khoi khi app dang dong / da bi kill).
 *
 * Chu ky toi thieu cua WorkManager la 15 phut. Khi user mo app, ViewModel cung
 * goi syncLivestreamStateWithServer() ngay -> bat job ngay khong can cho 15p.
 */
class LivestreamDiscoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val user = SecurePrefsHelper.getUser(applicationContext)
            val pass = SecurePrefsHelper.getPass(applicationContext)
            val baseUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            if (baseUrl.isEmpty()) return@withContext Result.success()

            val apiUrl = baseUrl.toApiBaseUrl() + "/api/livestream/status"
            val reqBuilder = Request.Builder().url(apiUrl)
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                reqBuilder.header("Authorization", Credentials.basic(user, pass))
            }
            val client = NasApplication.instance.fastApiClient
            val bodyStr = client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.success()
                resp.body?.string() ?: return@withContext Result.success()
            }

            val json = JSONObject(bodyStr)
            val jobs = json.optJSONArray("jobs") ?: return@withContext Result.success()
            val host = java.net.URL(baseUrl).host

            val wm = WorkManager.getInstance(applicationContext)
            for (i in 0 until jobs.length()) {
                val job = jobs.optJSONObject(i) ?: continue
                if (job.optString("status") != "recording") continue
                val jobId = job.optString("job_id", "")
                if (jobId.isEmpty()) continue
                val platform = job.optString("platform", "livestream")

                // LivestreamMonitorWorker.enqueue da dung enqueueUniqueWork(KEEP)
                // theo work name "LIVESTREAM_MONITOR_<jobId>" - tu chan trung lap.
                LivestreamMonitorWorker.enqueue(applicationContext, jobId, host, platform)
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Không retry đám đám nếu network lỗi - cho tick sau (15p).
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "LIVESTREAM_DISCOVERY"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LivestreamDiscoveryWorker>(
                60, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
