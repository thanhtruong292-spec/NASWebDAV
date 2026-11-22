package com.nas.naswebdav.backup

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.UsbImportConflict
import com.nas.naswebdav.UsbImportSettings
import com.nas.naswebdav.toApiBaseUrl
import com.nas.naswebdav.utils.PreferencesRepository
import com.nas.naswebdav.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

data class NasBackupSession(
    val deviceId: String,
    val deviceName: String,
    val backupMode: String,
    val status: String,
    val processed: Int,
    val total: Int,
    val speedBps: Long,
    val currentFile: String,
    val freedBytes: Long,
    val phoneFreeBytes: Long,
    val phoneTotalBytes: Long,
    val slotGranted: Boolean,
    val queuePosition: Int
)

/**
 * AutoBackupViewModel — Phase 3 của VM Split.
 *
 * Quản lý: Auto backup progress, Backup schedule, USB import, Sleep schedule.
 *
 * Independent domain — chỉ cần auth URL từ SharedStateHolder.currentUrl.
 *
 * Phase 3 skeleton: state declarations + placeholder methods.
 */
class AutoBackupViewModel(
    private val repository: WebDavRepository
) : ViewModel() {

    // ═══ AUTO BACKUP STATE ═══

    var isAutoBackupRunning by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var autoBackupCurrentFile by androidx.compose.runtime.mutableStateOf("")
        private set
    var autoBackupSourcePath by androidx.compose.runtime.mutableStateOf("")
        private set
    var autoBackupDestPath by androidx.compose.runtime.mutableStateOf("")
        private set
    var autoBackupProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var autoBackupProcessedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var autoBackupTotalCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var autoBackupElapsedTime by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupIsPaused by androidx.compose.runtime.mutableStateOf(false)
        private set
    var autoBackupFileBytesTotal by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupFileBytesWritten by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupFileBytesRemaining by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupEtaSeconds by androidx.compose.runtime.mutableLongStateOf(-1L)
        private set
    var autoBackupUploadSpeedBps by androidx.compose.runtime.mutableLongStateOf(0L)
        private set

    // ═══ DUAL PIPELINE PARALLEL STATES (IMAGE & VIDEO) ═══

    var autoBackupImageProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var autoBackupImageCurrentFile by androidx.compose.runtime.mutableStateOf("")
        private set
    var autoBackupImageProcessedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var autoBackupImageTotalCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var autoBackupImageSpeedBps by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupImageBytesUploaded by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupImageBytesTotal by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupImageBytesRemaining by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupImageEtaSeconds by androidx.compose.runtime.mutableLongStateOf(-1L)
        private set

    var autoBackupVideoProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var autoBackupVideoCurrentFile by androidx.compose.runtime.mutableStateOf("")
        private set
    var autoBackupVideoProcessedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var autoBackupVideoTotalCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var autoBackupVideoSpeedBps by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupVideoBytesWritten by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupVideoBytesTotal by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupVideoFileProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var autoBackupVideoBytesUploaded by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupVideoBytesTotalPending by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupVideoBytesRemaining by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var autoBackupVideoEtaSeconds by androidx.compose.runtime.mutableLongStateOf(-1L)
        private set

    // ═══ CHẾ ĐỘ, BỘ NHỚ ĐIỆN THOẠI & ĐA THIẾT BỊ ═══

    var autoBackupMode by androidx.compose.runtime.mutableStateOf("both")
        private set
    var phoneDeviceId by androidx.compose.runtime.mutableStateOf("")
        private set
    var phoneDeviceName by androidx.compose.runtime.mutableStateOf(android.os.Build.MODEL ?: "Android")
        private set
    var phoneStorageFreeBytes by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var phoneStorageTotalBytes by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var storageFreedSessionBytes by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var storageFreedTotalBytes by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var nasQueuePosition by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var nasMaxConcurrentDevices by androidx.compose.runtime.mutableIntStateOf(2)
        private set
    var nasBackupSessions by androidx.compose.runtime.mutableStateOf<List<NasBackupSession>>(emptyList())
        private set

    // ═══ BACKUP SCHEDULE ═══

    var backupSchedule by androidx.compose.runtime.mutableStateOf(BackupSchedule())
        private set
    var backupScheduleMessage by androidx.compose.runtime.mutableStateOf("")
        private set

    // ═══ USB IMPORT ═══

    var usbImportState by androidx.compose.runtime.mutableStateOf(UsbImportState())
        private set
    var usbImportMessage by androidx.compose.runtime.mutableStateOf("")
        private set
    var isUsbImportLoading by androidx.compose.runtime.mutableStateOf(false)
        private set

    // ═══ SLEEP SCHEDULE ═══

    var sleepSchedule by androidx.compose.runtime.mutableStateOf(SleepSchedule())
        private set
    var sleepScheduleMessage by androidx.compose.runtime.mutableStateOf("")
        private set
    var lockNowRequested by androidx.compose.runtime.mutableStateOf(false)
        internal set

    init {
        refreshPhoneStorage()
        // Cập nhật trạng thái Auto Backup từ WorkManager
        viewModelScope.launch {
            try {
                androidx.work.WorkManager.getInstance(NasApplication.instance.applicationContext)
                    .getWorkInfosByTagFlow("com.nas.naswebdav.AutoBackupWorker").collect { workInfos ->
                        val workInfo = workInfos.find {
                            it.state == androidx.work.WorkInfo.State.RUNNING
                        } ?: workInfos.find {
                            it.state == androidx.work.WorkInfo.State.ENQUEUED &&
                                it.tags.contains("MANUAL_AUTO_BACKUP")
                        }
                        if (workInfo != null) {
                            isAutoBackupRunning = true
                            autoBackupProgress = workInfo.progress.getFloat("progress", 0f)
                            autoBackupCurrentFile = workInfo.progress.getString("fileName") ?: "Đang sao lưu..."
                            autoBackupSourcePath = workInfo.progress.getString("sourcePath") ?: ""
                            autoBackupDestPath = workInfo.progress.getString("destPath") ?: ""
                            autoBackupProcessedCount = workInfo.progress.getInt("processedCount", 0)
                            autoBackupTotalCount = workInfo.progress.getInt("totalCount", 0)
                            autoBackupElapsedTime = workInfo.progress.getLong("elapsedTime", 0L)
                            autoBackupFileBytesWritten = workInfo.progress.getLong("bytesWritten", 0L)
                            autoBackupFileBytesTotal = workInfo.progress.getLong("bytesTotal", 0L)
                            autoBackupFileBytesRemaining = workInfo.progress.getLong("bytesRemaining", 0L)
                            autoBackupEtaSeconds = workInfo.progress.getLong("etaSeconds", -1L)
                            autoBackupUploadSpeedBps = workInfo.progress.getLong("uploadSpeedBps", 0L)

                            // Cập nhật trạng thái chi tiết 2 pipeline song song
                            autoBackupImageProgress = workInfo.progress.getFloat("imageProgress", 0f)
                            autoBackupImageCurrentFile = workInfo.progress.getString("imageFileName") ?: ""
                            autoBackupImageProcessedCount = workInfo.progress.getInt("imageProcessedCount", 0)
                            autoBackupImageTotalCount = workInfo.progress.getInt("imageTotalCount", 0)
                            autoBackupImageSpeedBps = workInfo.progress.getLong("imageSpeedBps", 0L)
                            autoBackupImageBytesUploaded = workInfo.progress.getLong("imageBytesUploaded", 0L)
                            autoBackupImageBytesTotal = workInfo.progress.getLong("imageBytesTotal", 0L)
                            autoBackupImageBytesRemaining = workInfo.progress.getLong("imageBytesRemaining", 0L)
                            autoBackupImageEtaSeconds = workInfo.progress.getLong("imageEtaSeconds", -1L)

                            autoBackupVideoProgress = workInfo.progress.getFloat("videoProgress", 0f)
                            autoBackupVideoCurrentFile = workInfo.progress.getString("videoFileName") ?: ""
                            autoBackupVideoProcessedCount = workInfo.progress.getInt("videoProcessedCount", 0)
                            autoBackupVideoTotalCount = workInfo.progress.getInt("videoTotalCount", 0)
                            autoBackupVideoSpeedBps = workInfo.progress.getLong("videoSpeedBps", 0L)
                            autoBackupVideoBytesWritten = workInfo.progress.getLong("videoFileBytesWritten", 0L)
                            autoBackupVideoBytesTotal = workInfo.progress.getLong("videoFileBytesTotal", 0L)
                            autoBackupVideoFileProgress = workInfo.progress.getFloat("videoFileProgress", 0f)
                            autoBackupVideoBytesUploaded = workInfo.progress.getLong("videoBytesUploaded", 0L)
                            autoBackupVideoBytesTotalPending = workInfo.progress.getLong("videoBytesTotalPending", 0L)
                            autoBackupVideoBytesRemaining = workInfo.progress.getLong("videoBytesRemaining", 0L)
                            autoBackupVideoEtaSeconds = workInfo.progress.getLong("videoEtaSeconds", -1L)

                            val workerMode = workInfo.progress.getString("backupMode")
                            val liveMode = com.nas.naswebdav.AutoBackupState.activeMode.value
                            autoBackupMode = if (liveMode == "image" || liveMode == "video" || liveMode == "both") liveMode else (workerMode ?: autoBackupMode)
                            val freedNow = workInfo.progress.getLong("storageFreedBytes", 0L)
                            if (freedNow != storageFreedSessionBytes) {
                                storageFreedSessionBytes = freedNow
                                refreshPhoneStorage()
                            }
                            nasQueuePosition = workInfo.progress.getInt("nasQueuePosition", 0)

                            autoBackupIsPaused = com.nas.naswebdav.AutoBackupState.isPaused.value
                        } else {
                            isAutoBackupRunning = false
                            nasQueuePosition = 0
                            refreshPhoneStorage()
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "WorkManager observe error: ${e.message}")
            }
        }
    }

    fun setBackupModeImmediate(mode: String) {
        val clean = if (mode == "image" || mode == "video" || mode == "both") mode else "both"
        autoBackupMode = clean
        com.nas.naswebdav.AutoBackupState.activeMode.value = clean
        if (clean == "image") {
            autoBackupVideoSpeedBps = 0L
            autoBackupVideoBytesWritten = 0L
            autoBackupVideoBytesTotal = 0L
            autoBackupVideoFileProgress = 0f
        } else if (clean == "video") {
            autoBackupImageSpeedBps = 0L
        }
    }

    fun refreshPhoneStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ctx = NasApplication.instance.applicationContext
                val prefs = PreferencesRepository.get(ctx)
                val (freeBytes, totalBytes) = StorageUtils.getPhoneStorageBytes()
                val devId = prefs.getDeviceId(ctx)
                val mode = prefs.getBackupMode()
                val totalFreed = prefs.getTotalBackupFreedBytes()
                withContext(Dispatchers.Main) {
                    phoneStorageFreeBytes = freeBytes
                    phoneStorageTotalBytes = totalBytes
                    phoneDeviceId = devId
                    autoBackupMode = mode
                    storageFreedTotalBytes = totalFreed
                }
            }
        }
    }

    fun fetchNasBackupSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val base = WebDavManager.currentBaseUrl
                if (base.isBlank()) return@runCatching
                val apiBase = base.toApiBaseUrl()
                val req = okhttp3.Request.Builder()
                    .url("$apiBase/api/backup/sessions")
                    .get()
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = org.json.JSONObject(resp.body?.string() ?: "{}")
                    val maxConc = json.optInt("max_concurrent", 2)
                    val arr = json.optJSONArray("sessions")
                    val list = mutableListOf<NasBackupSession>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            list.add(
                                NasBackupSession(
                                    deviceId = o.optString("device_id", ""),
                                    deviceName = o.optString("device_name", "Android"),
                                    backupMode = o.optString("backup_mode", "both"),
                                    status = o.optString("status", "running"),
                                    processed = o.optInt("processed", 0),
                                    total = o.optInt("total", 0),
                                    speedBps = o.optLong("speed_bps", 0L),
                                    currentFile = o.optString("current_file", ""),
                                    freedBytes = o.optLong("freed_bytes", 0L),
                                    phoneFreeBytes = o.optLong("phone_free_bytes", 0L),
                                    phoneTotalBytes = o.optLong("phone_total_bytes", 0L),
                                    slotGranted = o.optBoolean("slot_granted", true),
                                    queuePosition = o.optInt("queue_position", 0)
                                )
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        nasMaxConcurrentDevices = maxConc
                        nasBackupSessions = list
                    }
                }
            }
        }
    }

    fun triggerManualBackup(context: Context, onResult: (String) -> Unit) {
        // P0-6: Dùng READ_MEDIA_* + SAF (Storage Access Framework).
        // AutoBackupWorker đọc media qua MediaStore ContentResolver + SAF fallback.
        // KHÔNG cần MANAGE_EXTERNAL_STORAGE (Google Play policy violation).
        // FIX Android 14+: Xử lý partial access (READ_MEDIA_VISUAL_USER_SELECTED).
        val perm = com.nas.naswebdav.utils.MediaPermissionHelper
        val sdk = android.os.Build.VERSION.SDK_INT

        if (perm.hasNoMediaAccess(context)) {
            // Không có quyền → mở Settings để cấp quyền
            onResult("Thiếu quyền truy cập Media. Đang mở cài đặt để bạn cấp quyền...")
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.e("AutoBackup", "Failed to open settings", e)
            }
            return
        }

        val isPartialAccess = sdk >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            && perm.hasPartialVisualAccess(context) && !perm.canRunPeriodicBackup(context)

        viewModelScope.launch(Dispatchers.IO) {
            val url = com.nas.naswebdav.SmartNetworkManager.getActiveBaseUrl(context)
                .ifEmpty { com.nas.naswebdav.SecurePrefsHelper.getUrl(context) }
            if (url.isEmpty()) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onResult("Chưa kết nối NAS. Vui lòng đăng nhập NAS trước khi đồng bộ!")
                }
                return@launch
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                // FIX:partial access → hiển thị cảnh báo thay vì "đầy đủ quyền"
                if (isPartialAccess) {
                    onResult("Chế độ ảnh một phần: Worker sẽ bỏ qua ảnh ngoài danh sách chọn.")
                } else {
                    onResult("Quyền đã được cấp đầy đủ! Đang bắt đầu đồng bộ nền...")
                }
                isAutoBackupRunning = true
                autoBackupCurrentFile = "Đang xếp hàng đồng bộ..."
            }
            try {
                val workManager = androidx.work.WorkManager.getInstance(context)
                val prefs = PreferencesRepository.get(context)
                val delAfter = prefs.isDeleteAfterBackup()
                val mode = prefs.getBackupMode()
                val devId = prefs.getDeviceId(context)
                val devName = android.os.Build.MODEL ?: "Android"
                val request = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.AutoBackupWorker>()
                    .addTag("com.nas.naswebdav.AutoBackupWorker")
                    .addTag("MANUAL_AUTO_BACKUP")
                    .setInputData(
                        androidx.work.workDataOf(
                            "delete_after_backup" to delAfter,
                            "backup_mode" to mode,
                            "device_id" to devId,
                            "device_name" to devName
                        )
                    )
                    .build()
                workManager.enqueueUniqueWork("ManualAutoBackupWork",
                    androidx.work.ExistingWorkPolicy.REPLACE, request)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "trigger: ${e.message}")
            }
        }
    }

    fun cancelAutoBackup(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val workManager = androidx.work.WorkManager.getInstance(context)
                workManager.cancelUniqueWork("ManualAutoBackupWork")
                workManager.cancelUniqueWork("AutoBackupWork")
                workManager.cancelAllWorkByTag("com.nas.naswebdav.AutoBackupWorker")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "cancel: ${e.message}")
            }
        }
    }

    fun toggleAutoBackupPause() {
        autoBackupIsPaused = !autoBackupIsPaused
        com.nas.naswebdav.AutoBackupState.isPaused.value = autoBackupIsPaused
    }

    fun fetchBackupSchedule() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/backup/schedule").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        backupSchedule = BackupSchedule(
                            enabled = json.optBoolean("enabled", false),
                            frequency = json.optString("frequency", "weekly"),
                            hour = json.optInt("hour", 3),
                            retentionCount = json.optInt("retention_count", 7),
                            rcloneRemote = json.optString("rclone_remote", ""),
                            rclonePath = json.optString("rclone_path", "/NASBackup/")
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) { backupScheduleMessage = "Lỗi: ${e.message}" }
            }
        }
    }

    fun saveBackupSchedule(newSchedule: BackupSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().apply {
                    put("enabled", newSchedule.enabled)
                    put("frequency", newSchedule.frequency)
                    put("hour", newSchedule.hour)
                    put("retention_count", newSchedule.retentionCount)
                    put("rclone_remote", newSchedule.rcloneRemote)
                    put("rclone_path", newSchedule.rclonePath)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/backup/schedule").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        backupScheduleMessage = if (resp.isSuccessful) "Đã lưu lịch backup" else "Lỗi lưu"
                    }
                }
                fetchBackupSchedule()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) { backupScheduleMessage = "Lỗi: ${e.message}" }
            }
        }
    }

    fun fetchUsbImportStatus() {
        isUsbImportLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/usb_import/status").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        usbImportMessage = json.optString("message", "")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "fetchUsbImportStatus: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isUsbImportLoading = false }
            }
        }
    }

    fun saveUsbImportSettings(settings: UsbImportSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().apply {
                    put("enabled", settings.enabled)
                    put("dest_folder", settings.destFolder)
                    put("copy_mode", settings.copyMode)
                    put("auto_mount", settings.autoMount)
                    put("mount_readonly", settings.mountReadonly)
                    put("poll_seconds", settings.pollSeconds)
                    put("resume_enabled", settings.resumeEnabled)
                    put("verify_checksum", settings.verifyChecksum)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/usb_import/settings").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "saveUsbImportSettings: ${e.message}")
            }
        }
    }

    fun startUsbImportNow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/usb_import/start")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "startUsbImportNow: ${e.message}")
            }
        }
    }

    fun cancelUsbImport() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/usb_import/cancel")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "cancelUsbImport: ${e.message}")
            }
        }
    }

    fun resolveUsbImportConflicts(conflicts: List<UsbImportConflict>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val arr = org.json.JSONArray()
                conflicts.forEach { c ->
                    arr.put(org.json.JSONObject()
                        .put("rel", c.rel)
                        .put("source_name", c.sourceName)
                        .put("dest_name", c.destName))
                }
                val body = org.json.JSONObject().put("conflicts", arr).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/usb_import/resolve_conflicts").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "resolveUsbImportConflicts: ${e.message}")
            }
        }
    }

    fun fetchSleepSchedule() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/system/sleep_schedule").get().let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    withContext(Dispatchers.Main) {
                        sleepSchedule = SleepSchedule(
                            enabled = json.optBoolean("enabled", false),
                            mode = json.optString("mode", "spindown"),
                            startHour = json.optInt("start_hour", 23),
                            endHour = json.optInt("end_hour", 7),
                            idleOnly = json.optBoolean("idle_only", true)
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "fetchSleepSchedule: ${e.message}")
            }
        }
    }

    fun saveSleepSchedule(newSchedule: SleepSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val body = org.json.JSONObject().apply {
                    put("enabled", newSchedule.enabled)
                    put("mode", newSchedule.mode)
                    put("start_hour", newSchedule.startHour)
                    put("end_hour", newSchedule.endHour)
                    put("idle_only", newSchedule.idleOnly)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$apiBase/api/system/sleep_schedule").post(body).let(WebDavManager::tagCurrentAuth).build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        sleepScheduleMessage = if (resp.isSuccessful) "Đã lưu lịch ngủ" else "Lỗi lưu"
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) { sleepScheduleMessage = "Lỗi: ${e.message}" }
            }
        }
    }

    fun spindownHddNow(onDone: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
                val req = okhttp3.Request.Builder().url("$apiBase/api/system/hdd_spindown_now")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .let(WebDavManager::tagCurrentAuth)
                    .build()
                NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) { onDone?.invoke(resp.isSuccessful, if (resp.isSuccessful) "OK" else "HTTP ${resp.code}") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "spindownHddNow: ${e.message}")
                withContext(Dispatchers.Main) { onDone?.invoke(false, e.message ?: "Lỗi") }
            }
        }
    }

    fun requestLockNow() {
        lockNowRequested = true
    }
}