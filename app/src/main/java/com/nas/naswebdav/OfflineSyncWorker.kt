package com.nas.naswebdav

import android.content.Context
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

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
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = (applicationContext as NasApplication).database
        val trashMetaDao = db.trashMetaDao()
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
                        "DELETE" -> {
                            val sourceUrl = resolveQueuedWebDavPath(action.sourcePath, url)
                            webDavManager.deleteFile(sourceUrl, sourceUrl.endsWith("/"))
                            trashMetaDao.deleteByTrashPath(sourceUrl)
                        }
                        "CREATE_FOLDER" -> webDavManager.createFolder(resolveQueuedWebDavPath(action.sourcePath, url))
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

