package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object SocialDownloadRequestFactory {
    fun create(apiBaseUrl: String, authHeader: String, socialUrl: String): Request {
        val payloadJson =
            "{\"url\":${jsonString(socialUrl)},\"folder\":${jsonString(AppConfig.SOCIAL_DOWNLOAD_FOLDER)}}"
        val payload = payloadJson.toRequestBody("application/json".toMediaTypeOrNull())

        return Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/api/social/download")
            .header("Authorization", authHeader)
            .post(payload)
            .build()
    }

    fun acceptedJobId(statusCode: Int, responseBody: String): String? {
        if (statusCode != 202) return null
        return Regex("\\\"job_id\\\"\\s*:\\s*\\\"([a-zA-Z0-9_-]+)\\\"")
            .find(responseBody)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(String::isNotBlank)
    }

    fun shouldRetry(statusCode: Int, runAttemptCount: Int): Boolean =
        (statusCode == 429 || statusCode in 500..599) && runAttemptCount < 2

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

/**
 * Testable result of a single social-download attempt, decoupled from WorkManager.
 */
internal data class SocialDownloadResult(
    val outputData: androidx.work.Data,
    val isRetry: Boolean
) {
    val error get() = outputData.getString("error")
    val jobId get() = outputData.getString("jobId")
}

class SocialDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val INPUT_URL = "socialUrl"
        private const val UNIQUE_WORK_PREFIX = "social_share_download"
        private const val CHANNEL_ID = "social_download_channel"
        private const val NOTIFICATION_BASE_ID = 9200

        fun enqueue(context: Context, socialUrl: String) {
            val request = OneTimeWorkRequestBuilder<SocialDownloadWorker>()
                .setInputData(workDataOf(INPUT_URL to socialUrl))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK_PREFIX)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_WORK_PREFIX:${Integer.toHexString(socialUrl.hashCode())}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Core download logic — pure function, no Android/Worker dependencies.
         * Takes [authHeader] and [apiBaseUrl] pre-computed so tests never touch
         * [WebDavManager], [SecurePrefsHelper], or any Android-only code.
         * Production calls via [NasApplication.instance.fastApiClient]; tests swap
         * in a stub [callFactory].
         */
        internal suspend fun executeDownload(
            socialUrl: String,
            configuredBaseUrl: String,
            apiBaseUrl: String,
            authHeader: String,
            runAttemptCount: Int,
            callFactory: okhttp3.Call.Factory
        ): SocialDownloadResult = withContext(Dispatchers.IO) {
            if (configuredBaseUrl.isBlank()) {
                return@withContext SocialDownloadResult(
                    workDataOf("error" to "Hãy đăng nhập NASWebDAV trước khi sử dụng Chia sẻ."),
                    false
                )
            }

            if (apiBaseUrl.isBlank()) {
                return@withContext SocialDownloadResult(
                    workDataOf("error" to "Địa chỉ NAS không hợp lệ."),
                    false
                )
            }

            val request = SocialDownloadRequestFactory.create(apiBaseUrl, authHeader, socialUrl)

            try {
                callFactory.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val jobId = SocialDownloadRequestFactory.acceptedJobId(
                        response.code,
                        responseBody
                    )
                    if (jobId != null) {
                        return@withContext SocialDownloadResult(
                            workDataOf("jobId" to jobId),
                            false
                        )
                    }

                    if (SocialDownloadRequestFactory.shouldRetry(response.code, runAttemptCount)) {
                        return@withContext SocialDownloadResult(
                            workDataOf(),
                            true
                        )
                    }

                    val serverMessage = runCatching {
                        JSONObject(responseBody).optString("error")
                    }.getOrDefault("").orEmpty()
                    return@withContext SocialDownloadResult(
                        workDataOf(
                            "error" to serverMessage
                                .ifBlank { "NAS từ chối yêu cầu (HTTP ${response.code})." }
                                .take(500)
                        ),
                        false
                    )
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (runAttemptCount < 2) {
                    return@withContext SocialDownloadResult(workDataOf(), true)
                }
                return@withContext SocialDownloadResult(
                    workDataOf(
                        "error" to "Không kết nối được NAS. Vui lòng thử lại sau.".take(500)
                    ),
                    false
                )
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val socialUrl = SocialShareParser.extractSupportedUrl(inputData.getString(INPUT_URL))
            ?: return@withContext fail("Liên kết chia sẻ không được hỗ trợ.")

        val configuredBaseUrl = SecurePrefsHelper.getUrl(applicationContext)
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        val activeBaseUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifBlank { configuredBaseUrl }
        val apiBaseUrl = activeBaseUrl.toApiBaseUrl()
        val authHeader = WebDavManager.AuthState(activeBaseUrl, user, pass).authHeader

        val dlResult = executeDownload(
            socialUrl = socialUrl,
            configuredBaseUrl = configuredBaseUrl,
            apiBaseUrl = apiBaseUrl,
            authHeader = authHeader,
            runAttemptCount = runAttemptCount,
            callFactory = NasApplication.instance.fastApiClient
        )

        if (dlResult.jobId != null) {
            notifyResult(
                title = "NAS đã nhận liên kết",
                message = "Video đang được tải vào ${AppConfig.SOCIAL_DOWNLOAD_FOLDER}",
                isSuccess = true,
                notificationKey = socialUrl
            )
            return@withContext Result.success(dlResult.outputData)
        }
        if (dlResult.isRetry) return@withContext Result.retry()

        notifyResult("Không thể tải video", dlResult.error.orEmpty(), false, socialUrl)
        return@withContext Result.failure(dlResult.outputData)
    }

    private fun fail(message: String, notificationKey: String = message): Result {
        notifyResult("Không thể tải video", message, false, notificationKey)
        return Result.failure(workDataOf("error" to message.take(500)))
    }

    private fun notifyResult(
        title: String,
        message: String,
        isSuccess: Boolean,
        notificationKey: String
    ) {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                if (isSuccess) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(
                NOTIFICATION_BASE_ID + (notificationKey.hashCode() and 0x0FFF),
                notification
            )
        } catch (_: SecurityException) {
            // Android 13+: the work still succeeds when notification permission is denied.
        }
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Tải video mạng xã hội",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Kết quả tải link được chia sẻ tới NASWebDAV"
            }
        )
    }
}
