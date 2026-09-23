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
            priorityAction = db.syncActionDao().getById(retryActionId)
            if (priorityAction == null) {
                SystemLogger.log("WARNING", "OfflineSync",
                    "Retry request id=$retryActionId: row đã bị xóa hoặc không tồn tại")
                return@withContext Result.success()
            }
        }

        // FIX-REVIEW-24/09-#6/#13: identity DAY DU account+endpoint (host, user,
        // port, root) de loc row foreign trong SQL truoc LIMIT. Legacy khong xac
        // dinh nguon chi duoc chay cho tac vu KHONG pha huy; DELETE/MOVE/RENAME
        // legacy phai cho xac nhan (park + log), khong tu rebind sang endpoint moi.
        val preUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val activeHost = runCatching { java.net.URL(preUrl).host ?: preUrl }.getOrDefault(preUrl)
        val activePort = runCatching { java.net.URL(preUrl).port.takeIf { it > 0 } ?: java.net.URL(preUrl).defaultPort }.getOrDefault(-1)
        val activeRoot = runCatching {
            val p = java.net.URL(preUrl).path.trimEnd('/')
            if (p.isEmpty()) "/" else p
        }.getOrDefault("/")
        val preUser = SecurePrefsHelper.getUser(applicationContext)

        val pendingActions = db.syncActionDao().getAllPendingActionsScoped(activeHost, preUser, activePort, activeRoot, 200)
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
        val url = preUrl
        if (user.isEmpty() || pass.isEmpty() || url.isEmpty()) return@withContext Result.failure()
        // R4-P1: bỏ qua row của NAS/user khác — không phát lại thao tác sang máy
        // khác khi đổi endpoint. Row cũ (nasHost rỗng, trước v17) vẫn xử lý để
        // tương thích, nhưng KHÔNG rewrite authority sang host hiện tại.
        val webDavManager = WebDavManager.apply { connect(url, user, pass) }
        setThumbnailActivity("sync", true)
        try {
            var allSuccess = true
            var skippedForeign = 0
            for (action in sortedActions) {
                // FIX-REVIEW-24/09-#6/#13: SQL da loc account+endpoint truoc
                // LIMIT; day chi con luoi phong thu + legacy destructive phai
                // PARK cho xac nhan (khong tu rebind tac vu pha huy sang
                // endpoint moi).
                val isLegacy = action.nasHost.isEmpty() && action.nasUser.isEmpty() && action.nasPort == -1 && action.nasRoot.isEmpty()
                val isDestructive = action.actionType == "DELETE" || action.actionType == "RENAME" || action.actionType == "MOVE"
                if (isLegacy && isDestructive) {
                    try { db.syncActionDao().park(action.id) } catch (_: Exception) {}
                    SystemLogger.log("WARNING", "OfflineSync",
                        "Action ${action.id} (${action.actionType}) legacy khong ro nguon — park cho xac nhan, khong tu chay.")
                    skippedForeign++
                    continue
                }
                if (action.nasHost.isNotEmpty() &&
                    (action.nasHost != activeHost || (action.nasUser.isNotEmpty() && action.nasUser != user))) {
                    skippedForeign++
                    continue
                }
                // FIX-REVIEW-24/09-#13: phan biet outcomes — completed chi khi
                // handled thanh cong; parked (mat temp/du nguong) GIU ban ghi,
                // KHONG xoa. trackedOutcome=null nghia la retry (gi row).
                var trackedOutcome: String? = null // "completed" | "parked" | null=retry
                try {
                    var handled = false
                    var parkedHere = false
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
                        // FIX-REVIEW-24/09-D6: file khong con (OS don cache) →
                        // PARK + GIU ban ghi (trackedOutcome="parked"), KHONG
                        // handled=true roi delete (ban cu park xong van xoa).
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
                                    handled = true
                                } else {
                                    // Temp bị OS dọn — park + log trạng thái cuối, giữ bản ghi.
                                    try { db.syncActionDao().park(action.id) } catch (_: Exception) {}
                                    com.nas.naswebdav.utils.SystemLogger.log("WARNING", "OfflineSync",
                                        "Dữ liệu nguồn đã mất (cache bị dọn): ${action.destPath} — giữ bản ghi để kiểm tra")
                                    parkedHere = true
                                }
                            } else {
                                handled = true
                            }
                        }
                        // Action không nhận ra → bỏ qua (không xóa để user/dev inspect qua System Logs)
                        else -> { /* skip unknown action */ }
                    }
                    // FIX-REVIEW-24/09-#13: chi xoa row khi completed that su.
                    // Parked giu ban ghi; loi thuong (handled=false) gi row + pushBack.
                    if (parkedHere) {
                        trackedOutcome = "parked"
                    } else if (handled) {
                        db.syncActionDao().deleteById(action.id)
                        trackedOutcome = "completed"
                    } else {
                        try { db.syncActionDao().pushBack(action.id, System.currentTimeMillis()) } catch (_: Exception) {}
                        trackedOutcome = null
                        allSuccess = false
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // P0-4: user cancel → failure, KHÔNG retry
                    if (isStopped) {
                        return@withContext Result.failure()
                    }
                    // FIX-REVIEW-24/09-#13: pushBack MOI loi thuong (khong chi khi
                    // metadata fail) de batch sau vet muc khac truoc. Park sau 5
                    // loi lien tiep. trackedOutcome=null=retry (giu row).
                    try {
                        val now = System.currentTimeMillis()
                        db.syncActionDao().bumpFail(action.id, now)
                        try { db.syncActionDao().pushBack(action.id, now) } catch (_: Exception) {}
                        val fails = db.syncActionDao().getFailCount(action.id) ?: 0
                        if (fails >= 5) {
                            db.syncActionDao().park(action.id)
                            SystemLogger.log("WARNING", "OfflineSync",
                                "Action ${action.id} (${action.actionType}) park sau $fails lỗi — không chặn queue")
                            trackedOutcome = "parked"
                        } else {
                            trackedOutcome = null
                        }
                    } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 }
                    catch (_: Exception) {
                        trackedOutcome = null
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

            // FIX-REVIEW-24/09-#13: continuation dung countLocal DUNG scope
            // account+endpoint (khong phai global tru batch-skip). Chi enqueue
            // khi batch SACH (allSuccess); khi co loi, Result.retry() quay lai
            // vet tiep (muc loi da pushBack xuong cuoi).
            try {
                val localRemaining = db.syncActionDao().countLocal(activeHost, user, activePort, activeRoot)
                if (localRemaining > 0 && allSuccess) {
                    SystemLogger.log("INFO", "OfflineSync",
                        "Queue còn $localRemaining action local ($skippedForeign foreign/legacy-park giữ lại) — enqueue continuation work")
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
            // R4-P1: row khác NAS/user bị bỏ qua (giữ lại, không xóa) — log để user
            // biết khi về đúng NAS chúng sẽ được xử lý.
            if (skippedForeign > 0) {
                com.nas.naswebdav.utils.SystemLogger.log("INFO", "OfflineSync",
                    "$skippedForeign action thuộc NAS khác — giữ lại, xử lý khi về đúng NAS")
            }
            result
        } finally {
            setThumbnailActivity("sync", false)
        }
    }
}

