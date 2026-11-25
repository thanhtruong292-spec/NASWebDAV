package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nas.naswebdav.utils.HashUtils
import com.nas.naswebdav.utils.ImageFingerprint
import com.nas.naswebdav.utils.SystemLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object DuplicateProgressState {
    val isPaused = MutableStateFlow(false)
    val stage = MutableStateFlow("Khởi động...")
    val currentFolder = MutableStateFlow("")
    val itemName = MutableStateFlow("")
    val isFolder = MutableStateFlow(false)
    val scannedCount = MutableStateFlow(0)
    val foundCount = MutableStateFlow(0)
    val hashCount = MutableStateFlow(0)
    val totalHashes = MutableStateFlow(0)
    val percent = MutableStateFlow(0f)
    val currentStagePercent = MutableStateFlow(0f)
    val currentFolderUrl = MutableStateFlow("")
    val stageNumber = MutableStateFlow(1)
    val totalStages = MutableStateFlow(3)
    val stageDescription = MutableStateFlow("")
    val elapsedTime = MutableStateFlow(0L)
    val estimatedTimeRemaining = MutableStateFlow(-1L)
    val lastRustResults = MutableStateFlow<List<NasFile>>(emptyList())
}

private val rustSha256Regex = Regex("^[0-9a-f]{64}$")

internal fun requireCompleteDuplicateInventory(report: JSONObject): JSONObject {
    val inventory = report.optJSONObject("inventory")
        ?: throw IncompleteNasCatalogueException("Báo cáo thiếu danh mục inventory")
    val files = report.optInt("total_files_scanned", -1)
    check(inventory.optInt("protocol", -1) == 1 &&
        inventory.optString("source") == "file_index" &&
        inventory.optBoolean("complete") &&
        inventory.optLong("generation", -1) >= 0 &&
        inventory.optLong("catalogue_revision", -1) >= 0 &&
        (files < 0 || inventory.optInt("total", -1) == files) &&
        report.optInt("read_errors", 0) == 0) { "Danh mục quét trùng trên NAS chưa đầy đủ" }
    return inventory
}

internal fun duplicateReportNotice(report: JSONObject): String {
    val listed = report.optJSONArray("groups")?.length() ?: return "NAS chưa gửi danh sách nhóm."
    val total = report.optInt("total_groups", -1)
    val errors = report.optInt("read_errors", 0)
    val notices = mutableListOf<String>()
    if (total >= 0 && listed < total) {
        notices += "Đã nhận $listed/$total nhóm trùng từ NAS."
    } else if (total < 0 && listed > 0) {
        notices += "Đã nhận $listed nhóm. NAS chưa gửi tổng số nhóm."
    }
    if (errors > 0) notices += "NAS ghi nhận $errors lỗi đọc."
    return notices.joinToString(" ")
}

internal fun parseRustDuplicateGroups(resultJson: JSONObject, webdavBaseUrl: String): List<NasFile> {
    val groups = resultJson.optJSONArray("groups") ?: return emptyList()
    val webdavRoot = java.net.URI(webdavBaseUrl).path.trimEnd('/')
    val out = mutableListOf<NasFile>()
    for (i in 0 until groups.length()) {
        val g = groups.optJSONObject(i) ?: continue
        val sha = g.optString("sha256", "").trim().lowercase()
        if (!rustSha256Regex.matches(sha)) continue
        val count = g.optInt("count", 0)
        val perFileSize = g.optLong("size", -1L).takeIf { it >= 0L }
            ?: if (count > 1) g.optLong("wasted_bytes", 0L) / (count - 1) else 0L
        val filesArr = g.optJSONArray("files") ?: continue
        if (filesArr.length() < 2) continue
        val groupFiles = ArrayList<NasFile>(filesArr.length())
        for (j in 0 until filesArr.length()) {
            val relPath = filesArr.optString(j, "")
            if (relPath.isEmpty()) continue
            val cleanRel = relPath.removePrefix("/webdav/").removePrefix("/")
            val fullUrl = runCatching { catalogueFileUrl(webdavBaseUrl, "$webdavRoot/$cleanRel") }.getOrNull() ?: continue
            val name = cleanRel.substringAfterLast('/')
            groupFiles.add(
                NasFile(
                    name = name,
                    path = fullUrl,
                    isDirectory = false,
                    contentType = null,
                    contentLength = perFileSize,
                    lastModified = 0L,
                    partialHash = sha
                )
            )
        }
        if (groupFiles.size >= 2) {
            out.addAll(groupFiles)
        }
    }
    return out
}

class DuplicateScanWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {

    private val notificationId = 999
    private val channelId = "scan_channel"

    override suspend fun doWork(): Result {
        SystemLogger.log("INFO", "DuplicateScan", "Bắt đầu quét tệp trùng lặp 100% bằng Rust trên NAS")
        val currentUrl = inputData.getString("currentUrl")
        if (currentUrl.isNullOrBlank()) {
            SystemLogger.log("WARNING", "DuplicateScan", "Thiếu currentUrl — kết thúc thất bại")
            return Result.failure()
        }
        val forceRestart = inputData.getBoolean("forceRestart", false)
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (user.isEmpty() || pass.isEmpty()) {
            SystemLogger.log("WARNING", "DuplicateScan", "Thiếu credentials NAS — kết thúc thất bại")
            return Result.failure()
        }

        val auth = WebDavManager.AuthState(currentUrl, user, pass)
        return withContext(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(auth)) {
            try {
                setForeground(makeForegroundInfo(channelId, "Quét trùng lặp Rust", notificationId, "Đang gửi lệnh quét Rust tới NAS..."))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                val exName = e.javaClass.name
                val isBgRestriction = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    && exName.contains("ForegroundServiceStartNotAllowed")
                val isMissingType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && (exName.contains("MissingForegroundServiceType") || exName.contains("ForegroundServiceType"))
                when {
                    isBgRestriction -> android.util.Log.w("DuplicateScan", "Background restriction: skip foreground", e)
                    isMissingType -> {
                        SystemLogger.log("ERROR", "DuplicateScan", "Lỗi thiếu foregroundServiceType: ${e.message}")
                        return@withContext Result.failure()
                    }
                    else -> android.util.Log.w("DuplicateScan", "setForeground non-fatal: ${e.javaClass.simpleName}", e)
                }
            }

            setThumbnailActivity("sync", true)
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(android.R.drawable.ic_search_category_default)
                .setOnlyAlertOnce(true)
                .setOngoing(true)

            val apiBaseUrl = currentUrl.toApiBaseUrl()
            val client = NasApplication.instance.fastApiClient
            val startTime = System.currentTimeMillis()
            var lastSmoothedEtaMs = -1L
            val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()

            var ownedScanId = 0L
            var cancelToken = ""
            suspend fun cancelOwnedScan() = withContext<Unit>(NonCancellable) {
                if (cancelToken.isBlank()) return@withContext
                runCatching {
                    val body = JSONObject().put("job_id", ownedScanId).put("cancel_token", cancelToken)
                    val req = okhttp3.Request.Builder()
                        .url("$apiBaseUrl/api/disk/scan_duplicates/cancel")
                        .header("Authorization", auth.authHeader)
                        .post(body.toString().toRequestBody(jsonMedia))
                        .build()
                    val call = client.newCall(req)
                    call.timeout().timeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    call.execute().close()
                }
            }

            try {
                DuplicateProgressState.stage.value = "Khởi động Rust trên NAS..."
                DuplicateProgressState.stageNumber.value = 1
                DuplicateProgressState.totalStages.value = 3
                DuplicateProgressState.percent.value = 0.05f
                DuplicateProgressState.currentStagePercent.value = 0.2f
                DuplicateProgressState.currentFolder.value = "Rust NAS (kích thước → băm nhanh 64 KiB đầu/cuối → SHA-256)"
                DuplicateProgressState.currentFolderUrl.value = "NAS Rust Engine"
                DuplicateProgressState.itemName.value = "Đang kích hoạt..."
                DuplicateProgressState.stageDescription.value = "Đang kích hoạt trình quét Rust đa luồng trên NAS..."
                DuplicateProgressState.scannedCount.value = 0
                DuplicateProgressState.foundCount.value = 0

                val startBody = JSONObject()
                    .put("async", true)
                    .put("min_size", 0)
                    .put("limit", 0)
                    .put("force", forceRestart)
                    .toString()
                    .toRequestBody(jsonMedia)
                val startReq = okhttp3.Request.Builder()
                    .url("$apiBaseUrl/api/disk/scan_duplicates")
                    .header("Authorization", auth.authHeader)
                    .post(startBody)
                    .build()

                val startResp = client.newCall(startReq).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val errMsg = runCatching { JSONObject(bodyStr).optString("error") }.getOrDefault("")
                            .ifBlank { "HTTP ${resp.code}" }
                        throw IllegalStateException("Rust NAS từ chối quét: $errMsg")
                    }
                    JSONObject(bodyStr)
                }

                ownedScanId = startResp.optLong("job_id")
                cancelToken = startResp.optString("cancel_token")
                var status = startResp.optString("status", "running")
                var lastJson = startResp
                var pollTicks = 0
                var consecutivePollErrors = 0

                while (status == "running" || status == "cancelling") {
                    if (isStopped) {
                        cancelOwnedScan()
                        return@withContext Result.failure()
                    }
                    delay(1500)
                    pollTicks++
                    if (pollTicks % 120 == 0) {
                        setThumbnailActivity("sync", true)
                    }

                    val pollReq = okhttp3.Request.Builder()
                        .url("$apiBaseUrl/api/disk/scan_duplicates/status")
                        .header("Authorization", auth.authHeader)
                        .get()
                        .build()
                    val polled = try {
                        client.newCall(pollReq).execute().use { resp ->
                            val bodyStr = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) {
                                throw IllegalStateException("HTTP ${resp.code}")
                            }
                            JSONObject(bodyStr)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutivePollErrors++
                        if (consecutivePollErrors >= 6) {
                            throw IllegalStateException("Mất kết nối NAS khi kiểm tra tiến độ Rust: ${e.message}")
                        }
                        delay(2000)
                        continue
                    }
                    consecutivePollErrors = 0
                    check(polled.optInt("job_id") == startResp.optInt("job_id")) { "Phiên quét trên NAS đã thay đổi" }
                    lastJson = polled
                    status = lastJson.optString("status", "error")
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val elapsedSec = lastJson.optDouble("elapsed_sec", elapsedMs / 1000.0)
                    val stageNum = lastJson.optInt("stage_num", 1).coerceIn(1, 3)
                    val stageName = lastJson.optString("stage_name", "").ifBlank {
                        "Rust NAS đang quét 3 tầng (${elapsedSec.toInt()}s)..."
                    }
                    val scannedReal = lastJson.optInt("scanned", 0)
                    val doneReal = lastJson.optInt("done", 0)
                    val totalReal = lastJson.optInt("total", 0)
                    val candidatesReal = lastJson.optInt("candidates", 0)
                    val pctReal = (lastJson.optDouble("percent", 0.0).toFloat() / 100f).coerceIn(0.01f, 0.99f)
                    val curRel = lastJson.optString("current_file", "")
                    val curFolder = if (curRel.contains('/')) "/" + curRel.substringBeforeLast('/') else "/"
                    val curFile = if (curRel.isNotBlank()) curRel.substringAfterLast('/') else "Đang duyệt dữ liệu trên ổ cứng NAS..."
                    val desc = if (totalReal > 0) {
                        "$stageName • ${elapsedSec.toInt()}s"
                    } else {
                        "$stageName • Đã duyệt $scannedReal tệp (${elapsedSec.toInt()}s)"
                    }

                    val effectiveElapsedMs = (elapsedSec * 1000.0).toLong().coerceAtLeast(elapsedMs)
                    val rawEtaMs = if (pctReal > 0.03f && effectiveElapsedMs >= 3000L) {
                        ((effectiveElapsedMs / pctReal) - effectiveElapsedMs).toLong().coerceAtLeast(0L)
                    } else -1L
                    if (rawEtaMs > 0L) {
                        lastSmoothedEtaMs = if (lastSmoothedEtaMs <= 0L) rawEtaMs else ((lastSmoothedEtaMs * 3 + rawEtaMs) / 4)
                    } else {
                        lastSmoothedEtaMs = -1L
                    }
                    DuplicateProgressState.elapsedTime.value = effectiveElapsedMs
                    DuplicateProgressState.estimatedTimeRemaining.value = lastSmoothedEtaMs
                    DuplicateProgressState.stage.value = stageName
                    DuplicateProgressState.stageNumber.value = stageNum
                    DuplicateProgressState.stageDescription.value = desc
                    DuplicateProgressState.scannedCount.value = scannedReal
                    DuplicateProgressState.foundCount.value = candidatesReal
                    DuplicateProgressState.hashCount.value = doneReal
                    DuplicateProgressState.totalHashes.value = totalReal
                    DuplicateProgressState.percent.value = pctReal
                    DuplicateProgressState.currentStagePercent.value = pctReal
                    DuplicateProgressState.currentFolder.value = curFolder
                    DuplicateProgressState.currentFolderUrl.value = curFolder
                    DuplicateProgressState.itemName.value = curFile

                    if (pollTicks % 2 == 0) {
                        val pctInt = (pctReal * 100).toInt()
                        setProgress(workDataOf(
                            "stage" to stageName,
                            "stageDescription" to desc,
                            "scannedCount" to scannedReal,
                            "foundCount" to candidatesReal,
                            "percent" to pctReal,
                            "stageNumber" to stageNum,
                            "totalStages" to 3,
                            "elapsedTime" to effectiveElapsedMs
                        ))
                        runCatching {
                            notificationBuilder
                                .setContentTitle("Quét trùng lặp Rust ($pctInt%)")
                                .setContentText(desc)
                                .setProgress(100, pctInt, false)
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        }
                    }
                }

                if (status != "done") {
                    val errMsg = lastJson.optString("error", "").ifBlank { "Trạng thái: $status" }
                    throw IllegalStateException("Quét trùng lặp Rust không hoàn tất: $errMsg")
                }

                val resultObj = lastJson.optJSONObject("result")
                    ?: throw IllegalStateException("Rust trả về kết quả rỗng")
                if (resultObj.has("inventory")) {
                    requireCompleteDuplicateInventory(resultObj)
                }
                val notice = duplicateReportNotice(resultObj)
                val parsedFiles = parseRustDuplicateGroups(resultObj, currentUrl)
                val totalScanned = resultObj.optInt("total_files_scanned", 0)
                val totalGroups = resultObj.optInt("total_groups", 0)
                val totalWasted = resultObj.optLong("total_wasted_bytes", 0L)
                val detectedGroups = resultObj.optInt("detected_groups", totalGroups)
                val isPartial = resultObj.optInt("total_groups", -1) > (resultObj.optJSONArray("groups")?.length() ?: 0)
                val stageLabel = if (isPartial) "Có kết quả từng phần" else "Hoàn tất"
                val summary = if (notice.isNotEmpty()) notice else "Đã duyệt $totalScanned tệp. Phát hiện $detectedGroups nhóm trong phạm vi WebDAV. Báo cáo còn $totalGroups nhóm, dư ${com.nas.naswebdav.utils.FormatUtils.formatBytes(totalWasted)}."

                DuplicateProgressState.lastRustResults.value = parsedFiles
                DuplicateProgressState.scannedCount.value = totalScanned
                DuplicateProgressState.foundCount.value = parsedFiles.size
                DuplicateProgressState.stage.value = stageLabel
                DuplicateProgressState.stageNumber.value = 3
                DuplicateProgressState.stageDescription.value = summary
                DuplicateProgressState.percent.value = 1f
                DuplicateProgressState.currentStagePercent.value = 1f
                DuplicateProgressState.itemName.value = stageLabel
                DuplicateProgressState.currentFolderUrl.value = "$stageLabel ($totalScanned tệp)"

                setProgress(workDataOf(
                    "stage" to stageLabel,
                    "itemName" to stageLabel,
                    "scannedCount" to totalScanned,
                    "foundCount" to parsedFiles.size,
                    "percent" to 1f,
                    "currentStagePercent" to 1f,
                    "stageNumber" to 3,
                    "totalStages" to 3,
                    "stageDescription" to summary
                ))

                if (parsedFiles.isNotEmpty()) {
                    UploadNotificationHelper.showDuplicateFoundNotification(applicationContext, parsedFiles.size)
                } else {
                    notificationBuilder
                        .setContentTitle(stageLabel)
                        .setContentText(summary)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }

                SystemLogger.log(if (notice.isEmpty()) "SUCCESS" else "WARN", "DuplicateScan", summary)
                return@withContext Result.success(workDataOf(
                    "stageDescription" to summary,
                    "scannedCount" to totalScanned,
                    "foundCount" to parsedFiles.size
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelOwnedScan()
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Lỗi không xác định"
                DuplicateProgressState.stage.value = "Lỗi quét Rust"
                DuplicateProgressState.stageDescription.value = msg
                DuplicateProgressState.percent.value = 0f
                SystemLogger.log("ERROR", "DuplicateScan", "Quét trùng lặp Rust thất bại (không fallback): $msg")
                return@withContext Result.failure(workDataOf("error" to msg))
            } finally {
                setThumbnailActivity("sync", false)
            }
        }
    }
}
// ════════════════════════════════════════════════════════════════════════════
// NasWorker — Lớp cha chung cho tất cả Worker giao tiếp NAS
// ════════════════════════════════════════════════════════════════════════════

abstract class NasWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    protected fun makeForegroundInfo(
        channelId: String, channelName: String, notificationId: Int, title: String
    ): ForegroundInfo {
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title).setSmallIcon(android.R.drawable.ic_popup_sync).setOngoing(true).build()
        // Tu Android 10 (Q) tro len, neu manifest da khai bao foregroundServiceType
        // thi ForegroundInfo BAT BUOC phai truyen service type tuong ung - thieu
        // se nem MissingForegroundServiceTypeException khi setForeground() chay,
        // crash sync worker giua chung.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    protected fun safeWorkerText(value: String, maxChars: Int = 512): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "..."
    }

    // FIX D2: Convert thành suspend fun để loại bỏ runBlocking không cần thiết.
    // SmartNetworkManager.getActiveBaseUrl() là suspend fun — khi loadWebDavManager() là suspend,
    // ta có thể gọi trực tiếp mà không cần runBlocking wrapper.
    // Tất cả callsite đều nằm trong withContext(IO) nên đã là suspend context.
    protected suspend fun loadWebDavManager(): WebDavManager? {
        val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (url.isEmpty() || user.isEmpty()) return null
        val manager = WebDavManager
        manager.connect(url, user, pass)
        return manager
    }

    protected suspend fun setThumbnailActivity(source: String, active: Boolean) = withContext(Dispatchers.IO) {
        val url = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) return@withContext
        try {
            val body = JSONObject()
                .put("source", source)
                .put("active", active)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("${url.toApiBaseUrl()}/api/thumb/activity")
                .post(body)
                .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                .build()
            NasApplication.instance.sharedHttpClient.newCall(request).execute().use { }
        } catch (e: Exception) {
            android.util.Log.w("DuplicateScan", "thumb activity heartbeat failed: ${e.message}")
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// UploadWorker + UploadNotificationHelper
// ════════════════════════════════════════════════════════════════════════════

    // Đã xoá UploadWorker theo yêu cầu

object UploadNotificationHelper {
    private const val CHANNEL_ID = "upload_progress"
    private const val NOTIFICATION_ID = 9001
    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Tiến trình tải lên", NotificationManager.IMPORTANCE_LOW).apply { description = "Hiển thị tiến trình tải tệp lên NAS"; setShowBadge(false) }
        (context.getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
    }
    fun showProgress(context: Context, fileName: String, completed: Int, total: Int, currentPercent: Int = -1) {
        createChannel(context)
        val filePercent = if (total > 0) (completed * 100 / total) else 0
        val text = if (currentPercent >= 0) "Đang tải: $currentPercent% - $fileName" else "$fileName ($completed/$total)"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Đang tải lên NAS...").setContentText(text).setProgress(100, if (currentPercent >= 0) currentPercent else filePercent, false).setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) {}
    }
    fun showComplete(context: Context, successCount: Int, failCount: Int) {
        createChannel(context)
        val text = if (failCount > 0) "Thành công: $successCount | Lỗi: $failCount" else "Tất cả $successCount tệp đã tải lên thành công!"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Tải lên hoàn tất ✅").setContentText(text).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) {}
    }
    fun dismiss(context: Context) { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    fun showDuplicateFoundNotification(context: Context, duplicateCount: Int) {
        createChannel(context)
        val intent = android.content.Intent(context, MainActivity::class.java).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK; putExtra("SHOW_DUPLICATES", true) }
        val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Phát hiện tệp trùng lặp").setContentText("Tìm thấy $duplicateCount nhóm tệp trùng trên NAS. Nhấn để dọn dẹp và giải phóng dung lượng.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent).setAutoCancel(true)
        try { NotificationManagerCompat.from(context).notify(9002, builder.build()) } catch (_: SecurityException) {}
    }
}

// ════════════════════════════════════════════════════════════════════════════
// AutoBackupWorker + FingerprintWorker
// ════════════════════════════════════════════════════════════════════════════



// ════════════════════════════════════════════════════════════════════════════
// AutoDuplicateScanWorker — Dọn rác tự động định kỳ
// ════════════════════════════════════════════════════════════════════════════


    // (Đã xóa SmartSyncWorker theo yêu cầu)

// ════════════════════════════════════════════════════════════════════════════
// IdleSpeedTestWorker — Đo tốc độ đĩa ngầm
// ════════════════════════════════════════════════════════════════════════════

