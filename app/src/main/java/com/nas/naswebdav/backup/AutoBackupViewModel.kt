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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

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
        internal set
    var autoBackupSourcePath by androidx.compose.runtime.mutableStateOf("")
        internal set
    var autoBackupDestPath by androidx.compose.runtime.mutableStateOf("")
        internal set
    var autoBackupProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
        internal set
    var autoBackupProcessedCount by androidx.compose.runtime.mutableIntStateOf(0)
        internal set
    var autoBackupTotalCount by androidx.compose.runtime.mutableIntStateOf(0)
        internal set
    var autoBackupElapsedTime by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var autoBackupIsPaused by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var autoBackupFileBytesTotal by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var autoBackupFileBytesWritten by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set
    var autoBackupUploadSpeedBps by androidx.compose.runtime.mutableLongStateOf(0L)
        internal set

    // ═══ BACKUP SCHEDULE ═══

    var backupSchedule by androidx.compose.runtime.mutableStateOf(BackupSchedule())
        internal set
    var backupScheduleMessage by androidx.compose.runtime.mutableStateOf("")
        internal set

    // ═══ USB IMPORT ═══

    var usbImportState by androidx.compose.runtime.mutableStateOf(UsbImportState())
        internal set
    var usbImportMessage by androidx.compose.runtime.mutableStateOf("")
        internal set
    var isUsbImportLoading by androidx.compose.runtime.mutableStateOf(false)
        internal set

    // ═══ SLEEP SCHEDULE ═══

    var sleepSchedule by androidx.compose.runtime.mutableStateOf(SleepSchedule())
        internal set
    var sleepScheduleMessage by androidx.compose.runtime.mutableStateOf("")
        internal set
    var lockNowRequested by androidx.compose.runtime.mutableStateOf(false)
        internal set

    init {
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
                            autoBackupUploadSpeedBps = workInfo.progress.getLong("uploadSpeedBps", 0L)
                            autoBackupIsPaused = com.nas.naswebdav.AutoBackupState.isPaused.value
                        } else {
                            isAutoBackupRunning = false
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("AutoBackup", "WorkManager observe error: ${e.message}")
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
                val request = androidx.work.OneTimeWorkRequestBuilder<com.nas.naswebdav.AutoBackupWorker>()
                    .addTag("com.nas.naswebdav.AutoBackupWorker")
                    .addTag("MANUAL_AUTO_BACKUP").build()
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
                    .post(ByteArray(0).toRequestBody(null, 0, 0)).build()
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
                    .post(ByteArray(0).toRequestBody(null, 0, 0)).build()
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
                    .post(ByteArray(0).toRequestBody(null, 0, 0)).build()
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