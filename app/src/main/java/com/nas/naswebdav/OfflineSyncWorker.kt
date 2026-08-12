package com.nas.naswebdav

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nas.naswebdav.utils.SystemLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.TimeUnit

private fun resolveQueuedWebDavPath(rawPath: String, activeBaseUrl: String): String {
    val trimmed = rawPath.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        val active = runCatching { URL(activeBaseUrl) }.getOrNull() ?: return trimmed
        val raw = runCatching { URL(trimmed) }.getOrNull() ?: return trimmed
        return "${active.protocol}://${active.authority}${raw.path}" + (raw.query?.let { "?$it" } ?: "") + (raw.ref?.let { "#$it" } ?: "")
    }
    val base = activeBaseUrl.trimEnd('/')
    return if (trimmed.startsWith('/')) base + trimmed else "$base/$trimmed"
}


// ════════════════════════════════════════════════════════════════════════════
// OfflineSyncWorker — Xử lý thao tác offline đã xếp hàng
// ════════════════════════════════════════════════════════════════════════════

class OfflineSyncWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    companion object {
        const val UNIQUE_WORK_NAME = "OfflineSyncWorker"
        /** inputData key: action id của UPLOAD_FAILED row được yêu cầu retry ưu tiên. */
        const val KEY_RETRY_ACTION_ID = "retryActionId"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = (applicationContext as NasApplication).database
        val trashMetaDao = db.trashMetaDao()

        // P1: Nếu user bấm retry một upload cụ thể, ưu tiên xử lý row đó trước
        val retryActionId = inputData.getInt(KEY_RETRY_ACTION_ID, 0)
        var priorityAction: com.nas.naswebdav.SyncAction? = null
        if (retryActionId > 0) {
            priorityAction = db.syncActionDao().getAllPendingActions().find { it.id == retryActionId }
            if (priorityAction == null) {
                SystemLogger.log("WARNING", "OfflineSync",
                    "Retry request id=$retryActionId: row đã bị xóa hoặc không tồn tại")
                return@withContext Result.success()
            }
        }

        val pendingActions = db.syncActionDao().getAllPendingActions()
        if (pendingActions.isEmpty() && priorityAction == null) return@withContext Result.success()

        // Sắp xếp: priority action (nếu có) xếp đầu, các action khác theo thứ tự bình thường
        val sortedActions = if (priorityAction != null) {
            val rest = pendingActions.filter { it.id != priorityAction.id }
            listOf(priorityAction) + rest
        } else {
            pendingActions
        }

        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        // FIX D2b: Đã trong withContext(IO) → gọi suspend fun trực tiếp, không cần runBlocking
        val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        if (user.isEmpty() || pass.isEmpty() || url.isEmpty()) return@withContext Result.failure()
        val webDavManager = WebDavManager.apply { connect(url, user, pass) }
        setThumbnailActivity("sync", true)
        try {
            var allSuccess = true
            for (action in sortedActions) {
                try {
                    var handled = false
                    when (action.actionType) {
                        "DELETE" -> {
                            val sourceUrl = resolveQueuedWebDavPath(action.sourcePath, url)
                            webDavManager.deleteFile(sourceUrl, sourceUrl.endsWith("/"))
                            trashMetaDao.deleteByTrashPath(sourceUrl)
                            handled = true
                        }
                        "CREATE_FOLDER" -> {
                            webDavManager.createFolder(resolveQueuedWebDavPath(action.sourcePath, url))
                            handled = true
                        }
                        "RENAME", "MOVE" -> {
                            if (action.destPath != null) {
                                val sourceUrl = resolveQueuedWebDavPath(action.sourcePath, url)
                                val destUrl = resolveQueuedWebDavPath(action.destPath, url)
                                val encodedDest = destUrl.split("/").joinToString("/") { segment ->
                                    if (segment.isEmpty() || segment.contains(":")) segment
                                    else encodeWebDavSegment(segment)
                                }
                                webDavManager.renameFile(sourceUrl, encodedDest)
                                if (sourceUrl.contains(".trash/") && !encodedDest.contains(".trash/")) {
                                    trashMetaDao.deleteByTrashPath(sourceUrl)
                                } else if (!sourceUrl.contains(".trash/") && encodedDest.contains(".trash/")) {
                                    trashMetaDao.insert(TrashMeta(trashPath = encodedDest, originalPath = sourceUrl))
                                }
                                handled = true
                            }
                        }
                        "UPLOAD" -> {
                            if (action.destPath != null) {
                                val file = java.io.File(action.sourcePath)
                                if (file.exists()) {
                                    val ext = file.extension.lowercase()
                                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                                    val destUrl = resolveQueuedWebDavPath(action.destPath, url)
                                    val encodedDest = destUrl.split("/").joinToString("/") { segment ->
                                        if (segment.isEmpty() || segment.contains(":")) segment
                                        else encodeWebDavSegment(segment)
                                    }
                                    webDavManager.uploadFile(encodedDest, file, mime)
                                    // FIX-THUMB-DELEGATION: phone MUST NOT decode video/images.
                                    // NAS daemon handles thumbnail generation (idle 24/7 + on-demand /api/thumb).
                                }
                                handled = true
                            }
                        }
                        // UPLOAD_FAILED = metadata ghi bởi NasDocumentProvider.
                        // Retry upload nếu file temp vẫn còn trong cacheDir.
                        // Sau khi upload thành công → xóa row + file temp.
                        // File không còn → xóa row (OS đã dọn cache).
                        "UPLOAD_FAILED" -> {
                            val tempPath = action.sourcePath
                            if (tempPath != null && action.destPath != null) {
                                val file = java.io.File(tempPath)
                                if (file.exists()) {
                                    val ext = file.extension.lowercase()
                                    val mime = android.webkit.MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                                    val destUrl = resolveQueuedWebDavPath(action.destPath, url)
                                    val encodedDest = destUrl.split("/").joinToString("/") { segment ->
                                        if (segment.isEmpty() || segment.contains(":")) segment
                                        else encodeWebDavSegment(segment)
                                    }
                                    webDavManager.uploadFile(encodedDest, file, mime)
                                    file.delete() // dọn temp sau khi upload thành công
                                }
                            }
                            // Luôn xóa row: upload thành công hoặc file đã bị OS xóa
                            handled = true
                        }
                        // Action không nhận ra → bỏ qua (không xóa để user/dev inspect qua System Logs)
                        else -> { /* skip unknown action */ }
                    }
                    if (handled) db.syncActionDao().deleteById(action.id)
                } catch (e: Exception) {
                    // Guideline: isStopped || CancellationException → Result.retry() (không nuốt cancellation)
                    if (isStopped || e is kotlinx.coroutines.CancellationException) {
                        return@withContext Result.retry()
                    }
                    SystemLogger.log("WARNING", "OfflineSync",
                        "Action ${action.id} (${action.actionType}) failed: ${e.message} — row giữ lại để retry")
                    allSuccess = false
                }
            }
            val result = if (allSuccess) Result.success() else {
                // Tránh retry vô hạn: sau 3 lần thất bại liên tiếp, báo failure hẳn
                // thay vì để WorkManager exponential-backoff retry mãi mãi.
                if (runAttemptCount >= 3) Result.failure() else Result.retry()
            }

            // P2 Continuation: nếu queue còn nhiều hơn 200 action, enqueue worker tiếp theo.
            // Tránh backlog tích lũy vĩnh viễn khi normal actions liên tục được thêm vào.
            try {
                val remaining = db.syncActionDao().countAll()
                if (remaining > 200) {
                    SystemLogger.log("INFO", "OfflineSync",
                        "Queue còn $remaining action — enqueue continuation work")
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                    val continuationRequest = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                            androidx.work.BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS
                        )
                        .build()
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, continuationRequest
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                SystemLogger.log("WARNING", "OfflineSync",
                    "Enqueue continuation work thất bại: ${e.message}")
            }
            result
        } finally {
            setThumbnailActivity("sync", false)
        }
    }
}

