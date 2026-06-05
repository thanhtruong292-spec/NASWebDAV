package com.nas.naswebdav

import android.content.Context
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


// ════════════════════════════════════════════════════════════════════════════
// OfflineSyncWorker — Xử lý thao tác offline đã xếp hàng
// ════════════════════════════════════════════════════════════════════════════

class OfflineSyncWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {
    companion object {
        const val UNIQUE_WORK_NAME = "OfflineSyncWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = (applicationContext as NasApplication).database
        val pendingActions = db.syncActionDao().getAllPendingActions()
        if (pendingActions.isEmpty()) return@withContext Result.success()
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
            for (action in pendingActions) {
                try {
                    when (action.actionType) {
                        "DELETE" -> webDavManager.deleteFile(action.sourcePath, action.sourcePath.endsWith("/"))
                        "CREATE_FOLDER" -> webDavManager.createFolder(action.sourcePath)
                        "RENAME", "MOVE" -> {
                            if (action.destPath != null) {
                                val encodedDest = action.destPath.split("/").joinToString("/") { segment ->
                                    if (segment.isEmpty() || segment.contains(":")) segment
                                    else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                                }
                                webDavManager.renameFile(action.sourcePath, encodedDest)
                            }
                        }
                        "UPLOAD" -> {
                            if (action.destPath != null) {
                                val file = java.io.File(action.sourcePath)
                                if (file.exists()) {
                                    val ext = file.extension.lowercase()
                                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                                    val encodedDest = action.destPath.split("/").joinToString("/") { segment ->
                                        if (segment.isEmpty() || segment.contains(":")) segment
                                        else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                                    }
                                    webDavManager.uploadFile(encodedDest, file, mime)
                                }
                            }
                        }
                    }
                    db.syncActionDao().deleteById(action.id)
                } catch (_: Exception) {
                    allSuccess = false
                }
            }
            if (allSuccess) Result.success() else Result.retry()
        } finally {
            setThumbnailActivity("sync", false)
        }
    }
}

