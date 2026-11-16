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
    val totalStages = MutableStateFlow(4)
    val stageDescription = MutableStateFlow("")
    val elapsedTime = MutableStateFlow(0L)
    val estimatedTimeRemaining = MutableStateFlow(-1L)
}

class DuplicateScanWorker(appContext: Context, workerParams: WorkerParameters) : NasWorker(appContext, workerParams) {

    private val notificationId = 999
    private val channelId = "scan_channel"

    override suspend fun doWork(): Result {
        SystemLogger.log("INFO", "DuplicateScan", "Bắt đầu quét tệp trùng lặp trên NAS")
        val currentUrl = inputData.getString("currentUrl")
        if (currentUrl.isNullOrBlank()) { SystemLogger.log("WARNING", "DuplicateScan", "Thiếu currentUrl — kết thúc thất bại"); return Result.failure() }
        val forceRestart = inputData.getBoolean("forceRestart", false)
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        if (user.isEmpty() || pass.isEmpty()) { SystemLogger.log("WARNING", "DuplicateScan", "Thiếu credentials NAS — kết thúc thất bại"); return Result.failure() }

        return withContext(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(WebDavManager.AuthState(currentUrl, user, pass))) {
            try {
                setForeground(makeForegroundInfo(channelId, "Quét dọn hệ thống", notificationId, "Đang quét dữ liệu trùng lặp..."))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                val exName = e.javaClass.name
                val isBgRestriction = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    && exName.contains("ForegroundServiceStartNotAllowed")
                val isMissingType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && (exName.contains("MissingForegroundServiceType") || exName.contains("ForegroundServiceType"))
                when {
                    isBgRestriction -> {
                        android.util.Log.w("DuplicateScan", "Background restriction: skip foreground (worker continues)", e)
                    }
                    isMissingType -> {
                        android.util.Log.e("DuplicateScan", "setForeground failed: missing foregroundServiceType", e)
                        SystemLogger.log("ERROR", "DuplicateScan", "Lỗi thiếu foregroundServiceType (Android 14+): ${e.message}")
                        return@withContext Result.failure()
                    }
                    else -> android.util.Log.w("DuplicateScan", "setForeground non-fatal: ${e.javaClass.simpleName}", e)
                }
            }

            val webDavManager = WebDavManager
        setThumbnailActivity("sync", true)
        val db = NasApplication.instance.database

        // KHỞI TẠO HỆ THỐNG THÔNG BÁO ĐỘNG (DYNAMIC NOTIFICATION)
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_search_category_default)
            .setOnlyAlertOnce(true) // Quan trọng: Tránh rung/kêu liên tục khi cập nhật
            .setOngoing(true)

        val isUiUpdating = java.util.concurrent.atomic.AtomicBoolean(true)
        var uiUpdaterJob: kotlinx.coroutines.Job? = null
        // FIX D1: Khai báo uiScope ở ngoài inner try {} để finally có thể gọi .cancel() dọn dẹp bộ nhớ
        // nếu Worker bị kill trong trường hợp bất ngờ (exception trước khi isUiUpdating.set(false))
        val uiScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
        )
        var lease: NasDuplicateLease? = null
        try {
            val activeLease=NasDuplicateLease.acquire(WebDavManager.currentAuthState())
            lease=activeLease
            activeLease.start(this)
            // ═══════════════════════════════════════════════════
            // BIẾN THEO DÕI TIẾN TRÌNH CHÍNH XÁC
            // ═══════════════════════════════════════════════════
            val totalFilesIndexed = AtomicInteger(0)    // Tổng file đã index (Stage 1)
            val duplicateGroupsFound = AtomicInteger(0) // Số nhóm trùng lặp tìm thấy
            val hashesComputed = AtomicInteger(0)       // Số hash đã tính (Stage 2)
            val totalHashesNeeded = AtomicInteger(0)    // Tổng hash cần tính

            val currentStage = AtomicReference("Khởi động...")      // Giai đoạn hiện tại
            val currentFolder = AtomicReference("")                  // Thư mục đang quét
            val currentFileName = AtomicReference("")                // File đang xử lý
            val progressPercent = AtomicReference(0f)                // % TỔNG (0-100%)
            val currentStagePercent = AtomicReference(0f)            // % GIAI ĐOẠN HIỆN TẠI (0-100% của Stage này)
            val stageNumber = AtomicInteger(1)                       // Số thứ tự giai đoạn (1-4)
            val totalStages = 4                                      // Tổng số giai đoạn
            val stageDescription = AtomicReference("Đang kết nối tới NAS...") // Mô tả chi tiết giai đoạn

            val mediaExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "mp4", "mkv", "mov", "avi")
            val bufferMutex = Mutex()

            // FIX D9: uiScope được khai báo bên ngoài try để có thể cancel() trong finally
            uiUpdaterJob = uiScope.launch {
                val startTime = System.currentTimeMillis()
                var ticks = 0
                while (isUiUpdating.get() && isActive) {
                    ticks++
                    // FIX-THUMB-STUCK: heartbeat mỗi 3 phút — nếu app alive, server giữ block.
                    // Nếu app die, server auto-clear block sau 10 phút (TTL).
                    if (ticks % 180 == 0) {
                        setThumbnailActivity("sync", true)
                    }
                    val now = System.currentTimeMillis()
                    val elapsedMs = now - startTime
                    DuplicateProgressState.elapsedTime.value = elapsedMs
                    
                    val pct = progressPercent.get()
                    if (pct > 0.01f) {
                        val totalEstimatedMs = (elapsedMs / pct).toLong()
                        DuplicateProgressState.estimatedTimeRemaining.value = maxOf(0L, totalEstimatedMs - elapsedMs)
                    } else {
                        DuplicateProgressState.estimatedTimeRemaining.value = -1L
                    }

                    DuplicateProgressState.stage.value = currentStage.get()
                    DuplicateProgressState.currentFolder.value = currentFolder.get()
                    DuplicateProgressState.currentFolderUrl.value = currentFolder.get()
                    DuplicateProgressState.itemName.value = currentFileName.get()
                    DuplicateProgressState.scannedCount.value = totalFilesIndexed.get()
                    DuplicateProgressState.foundCount.value = duplicateGroupsFound.get()
                    DuplicateProgressState.hashCount.value = hashesComputed.get()
                    DuplicateProgressState.totalHashes.value = totalHashesNeeded.get()
                    DuplicateProgressState.percent.value = progressPercent.get()
                    DuplicateProgressState.currentStagePercent.value = currentStagePercent.get()
                    DuplicateProgressState.stageNumber.value = stageNumber.get()
                    DuplicateProgressState.stageDescription.value = stageDescription.get()

                    if (ticks % 20 == 0) {
                        try {
                            setProgress(workDataOf(
                                "stage" to safeWorkerText(currentStage.get()),
                                "currentFolder" to safeWorkerText(currentFolder.get(), 220),
                                "itemName" to safeWorkerText(currentFileName.get(), 180),
                                "isFolder" to false,
                                "scannedCount" to totalFilesIndexed.get(),
                                "foundCount" to duplicateGroupsFound.get(),
                                "hashCount" to hashesComputed.get(),
                                "totalHashes" to totalHashesNeeded.get(),
                                "percent" to progressPercent.get(),
                                "currentStagePercent" to currentStagePercent.get(),
                                "currentFolderUrl" to safeWorkerText(currentFolder.get(), 220),
                                "stageNumber" to stageNumber.get(),
                                "totalStages" to totalStages,
                                "stageDescription" to safeWorkerText(stageDescription.get()),
                                "elapsedTime" to DuplicateProgressState.elapsedTime.value,
                                "estimatedTimeRemaining" to DuplicateProgressState.estimatedTimeRemaining.value
                            ))
                            
                            // CẬP NHẬT THÔNG BÁO HỆ THỐNG (NOTIFICATION BAR) MỖI GIÂY
                            val pct = (progressPercent.get() * 100).toInt()
                            notificationBuilder
                                .setContentTitle("Quét rác: ${currentStage.get()} (${pct}%)")
                                .setContentText(stageDescription.get())
                                .setProgress(100, pct, false)
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        } catch (e: Exception) {
                            android.util.Log.w("DuplicateScan", "notification update failed: ${e.message}")
                        }
                    }
                    ticks++
                    // FIX #11: Tăng tử 50ms lên 250ms (giảm từ 20fps xuống 4fps)
                    // 95% vòng lặp trước đây chỉ cập nhật StateFlow mà không setProgress() → lãng phí CPU
                    delay(250)
                }
            }

            coroutineScope {
                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 1: THU THẬP FILE INDEX (0% → 50%)
                // ═══════════════════════════════════════════════════
                currentStage.set("Thu thập danh sách file")
                stageNumber.set(1)
                stageDescription.set("Nhận danh mục file dùng chung từ NAS")
                var isFastPathSuccess = false
                val apiBaseUrl = currentUrl.toApiBaseUrl()

                try {
                    val forceParam = if (forceRestart) "1" else "0"
                    val request = okhttp3.Request.Builder()
                        .url("$apiBaseUrl/api/disk/fast_index?force=$forceParam")
                        .tag(WebDavManager.AuthState::class.java, WebDavManager.currentAuthState())
                        .header("Authorization", WebDavManager.AuthState(user = user, pass = pass).authHeader)
                        .build()
                    val client = NasApplication.instance.sharedHttpClient
                    val call = client.newCall(request)
                    val cancelJob = launch {
                        while (isActive) {
                            if (isStopped) { call.cancel(); break }
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    try {
                        call.execute().use { response ->
                            if (response.code in listOf(401,403,409,503)) {
                                throw IncompleteNasCatalogueException("Danh mục NAS chưa sẵn sàng; chờ thử lại")
                            }
                            val body = response.body
                            if (response.isSuccessful && body != null) {
                                val mirrorStarted = System.currentTimeMillis()
                                val scanAccount = currentAccountKey()
                                val snapshot = readNasCatalogue(body.charStream(),currentUrl,scanAccount) { batch, received, expected ->
                                    if (isStopped || user != SecurePrefsHelper.getUser(applicationContext) || pass != SecurePrefsHelper.getPass(applicationContext)) {
                                        throw CancellationException("NAS account changed or scan stopped")
                                    }
                                    activeLease.progress("catalogue:$received",received,expected)
                                    WebDavManager.drainPendingCacheClear()
                                    db.withTransaction { db.fileDao().insertFiles(batch) }
                                    totalFilesIndexed.set(received)
                                    currentStage.set("Nhận danh mục NAS dùng chung")
                                    currentFolder.set("Danh mục file NAS")
                                    stageDescription.set("Đã nhận $received/$expected tệp đã lập chỉ mục")
                                    currentStagePercent.set(received.toFloat()/expected.coerceAtLeast(1))
                                    progressPercent.set(currentStagePercent.get()*0.5f)
                                }
                                require(snapshot.revision == activeLease.revision) {"Danh mục NAS đã thay đổi; thử lại"}
                                activeLease.progress("catalogue-complete",snapshot.total,snapshot.total)
                                finishNasCatalogueMirror(db,currentUrl,scanAccount,mirrorStarted,snapshot)
                                db.checkpointDao().clearCheckpoint("DuplicateScan")
                                totalFilesIndexed.set(snapshot.total)
                                isFastPathSuccess = true
                                progressPercent.set(0.5f)
                                SystemLogger.log("SUCCESS","IndexEngine","Đã nhận đủ ${snapshot.total} tệp từ catalogue NAS")
                            }
                        }
                    } finally {
                        cancelJob.cancel()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || e is IncompleteNasCatalogueException) throw e
                    throw IncompleteNasCatalogueException("Không kết nối được catalogue NAS; chưa quét lại filesystem",e)
                }

                if (!isFastPathSuccess) {
                    throw IncompleteNasCatalogueException("Không nhận được catalogue NAS đầy đủ; chờ thử lại")
                }

                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 2: TÌM FILE TRÙNG (50% → 60%)
                // TỐI ƯU: Dùng COUNT query + getDuplicateSizes() thay vì load toàn bộ vào RAM
                // ═══════════════════════════════════════════════════
                currentStage.set("Phân tích tệp trùng lặp (1/2)")
                stageNumber.set(2)
                stageDescription.set("Đang phân lớp sơ bộ các tệp có cùng kích thước...")
                currentStagePercent.set(0f)
                progressPercent.set(0.50f)

                // CHỈ đếm số lượng, KHÔNG load object vào RAM
                // P2-8: scope theo phien hien tai.
                val scanKey = currentAccountKey()
                val actualDuplicatesCount = db.fileDao().countDuplicateFiles(scanKey)
                // Lấy danh sách kích thước trùng (chỉ là List<Long>, rất nhẹ)
                val duplicateSizes = db.fileDao().getDuplicateSizes(scanKey)
                
                if (actualDuplicatesCount > 0) {
                    currentFileName.set("Tìm thấy $actualDuplicatesCount file nghi ngờ trùng lặp (${duplicateSizes.size} nhóm kích thước)")
                    duplicateGroupsFound.set(actualDuplicatesCount)
                } else {
                    currentFileName.set("Không tìm thấy tệp trùng lặp tiềm năng.")
                    delay(500)
                    currentStagePercent.set(1f)
                    progressPercent.set(0.95f)
                }
                
                duplicateGroupsFound.set(actualDuplicatesCount)

                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 3: HASH CHÍNH XÁC (Trải đều Bước 2 & 3 từ 50% → 95%)
                // Mới: Gộp Stage 2 và Stage 3 để chia đều thanh tiến trình. Nửa đầu số nhóm tính cho Stage 2, nửa sau cho Stage 3.
                // ═══════════════════════════════════════════════════
                if (duplicateSizes.isNotEmpty()) {
                    totalHashesNeeded.set(actualDuplicatesCount)
                    val hashSemaphore = Semaphore(3)
                    for ((groupIndex, size) in duplicateSizes.withIndex()) {
                        var after = ""
                        while (isActive) {
                            while (DuplicateProgressState.isPaused.value) {activeLease.ensureValid();delay(500)}
                            val page = db.fileDao().getFilesBySizePage(size, currentAccountKey(), after, 128)
                            if (page.isEmpty()) break
                            coroutineScope {
                                page.map { file -> async(Dispatchers.IO) {
                                    hashSemaphore.withPermit {
                                        activeLease.ensureValid()
                                        if (verifyDuplicateFile(webDavManager, file)) hashesComputed.incrementAndGet()
                                        activeLease.ensureValid()
                                    }
                                } }.awaitAll()
                            }
                            after = page.last().path
                            activeLease.checkpoint("hash:${hashesComputed.get()}")
                            currentStage.set("Xác minh nội dung đầy đủ")
                            stageNumber.set(3)
                            stageDescription.set("Đã xác minh ${hashesComputed.get()}/${totalHashesNeeded.get()} tệp ứng viên")
                            currentStagePercent.set(hashesComputed.get().toFloat() / totalHashesNeeded.get().coerceAtLeast(1))
                        }
                        progressPercent.set(0.5f + (groupIndex + 1f) / duplicateSizes.size * 0.45f)
                    }
                    currentStagePercent.set(1f)
                }
                // SỬA LỖI: Không dùng cancel() để dừng job vì nó sẽ để lại một Future Pending của WorkManager
                isUiUpdating.set(false)
                uiUpdaterJob.join()

                // ═══════════════════════════════════════════════════
                // GIAI ĐOẠN 4: TỔNG HỢP KẾT QUẢ (95% → 100%)
                // Hiển thị UI rõ ràng cho người dùng thấy đang tổng hợp
                // ═══════════════════════════════════════════════════
                currentStage.set("Đang tổng hợp kết quả...")
                stageNumber.set(4)
                stageDescription.set("Đang tổng hợp và đếm kết quả từ cơ sở dữ liệu...")
                currentStagePercent.set(0f)
                progressPercent.set(0.95f)
                DuplicateProgressState.stage.value = "Đang tổng hợp kết quả..."
                DuplicateProgressState.stageNumber.value = 4
                DuplicateProgressState.stageDescription.value = "Đang tổng hợp và đếm kết quả từ cơ sở dữ liệu..."
                delay(100) // Cho UI kịp vẽ ra bước mới

                // Đếm lại duplicate groups chính xác từ DB (CHỈ ĐẾM, không load object)
                val finalDuplicateCount = db.fileDao().countVerifiedDuplicateFiles(currentAccountKey())
                
                currentStage.set("Hoàn tất")
                val unverified = maxOf(0, totalHashesNeeded.get() - hashesComputed.get())
                val summary = "Đã quét ${totalFilesIndexed.get()} tệp; $finalDuplicateCount tệp trùng đã xác minh; $unverified tệp chưa xác minh."
                stageDescription.set(summary)
                progressPercent.set(1f)
                currentStagePercent.set(1f)
                DuplicateProgressState.stage.value = "Hoàn tất"
                DuplicateProgressState.stageDescription.value = "Hoàn tất!"

                setProgress(workDataOf(
                    "stage" to "Hoàn tất",
                    "itemName" to "Quét xong!",
                    "scannedCount" to totalFilesIndexed.get(),
                    "foundCount" to finalDuplicateCount,
                    "percent" to 1f,
                    "currentStagePercent" to 1f,
                    "currentFolderUrl" to "Hoàn tất quét ${totalFilesIndexed.get()} tệp",
                    "stageNumber" to 4,
                    "totalStages" to totalStages,
                    "stageDescription" to summary,
                    "unverifiedCount" to unverified
                ))
                
                // PHASE 6: Hiển thị Push Notification nếu có rác
                if (finalDuplicateCount > 0) {
                    UploadNotificationHelper.showDuplicateFoundNotification(applicationContext, finalDuplicateCount)
                } else {
                    notificationBuilder
                        .setContentTitle("Dọn rác hoàn tất")
                        .setContentText(if (unverified > 0) "Còn $unverified tệp chưa xác minh; chưa thể kết luận không trùng." else "Không tìm thấy tệp trùng lặp mới.")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }
            }

            db.checkpointDao().clearCheckpoint("DuplicateScan")
            SystemLogger.log("SUCCESS", "DuplicateScan", "Hoàn tất quét tệp trùng lặp")
            activeLease.complete()
            return@withContext Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // P0-4: hệ thống dừng worker → retry theo backoff, KHÔNG trả failure
            if (isStopped) return@withContext Result.retry()
            SystemLogger.log("WARNING", "DuplicateScan", "Lỗi tiến trình quét dữ liệu: ${e.message}")
            return@withContext Result.retry()
        } finally {
            lease?.retryAndRelease()
            isUiUpdating.set(false)
            // Chờ UI updater tự thoát sau khi isUiUpdating = false (vòng lặp kiểm tra flag này)
            uiUpdaterJob?.join()
            // FIX D1: Thực sự cancel uiScope để giải phóng tất cả coroutine trong scope
            uiScope.cancel()
            // FIX-THUMB-STUCK: luôn giải phóng block "sync" — DuplicateScanWorker
            // không gọi setThumbnailActivity("sync", false) trước đây, nên NAS
            // daemon bị pause vĩnh viễn (block_reasons={'sync'}) cho đến khi restart.
            setThumbnailActivity("sync", false)
        }
    }
    }

    private suspend fun verifyDuplicateFile(manager: WebDavManager, file: CachedFile): Boolean {
        return try {
            manager.getCachedVerifiedSha256(file.path, file.contentLength) != null
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            android.util.Log.w("DuplicateScan", "File could not be verified: ${file.path}", error)
            false
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

