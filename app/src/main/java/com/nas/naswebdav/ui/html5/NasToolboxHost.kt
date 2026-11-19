package com.nas.naswebdav.ui.html5

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.work.*
import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*
import com.nas.naswebdav.ui.screens.*
import com.nas.naswebdav.utils.MediaPermissionHelper
import com.nas.naswebdav.utils.PreferencesRepository
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NasToolboxDispatcher(
    private val onDispatch: (String) -> Boolean
) {
    fun dispatch(action: String): Boolean = onDispatch(action)
}

/**
 * Shared Compose host for all native NAS toolbox dialogs (Auto Backup, Livestream,
 * Duplicate Scan, USB Import, SMB, NAS Config Backup, Sleep Schedule, Bandwidth,
 * Download, LAN Whitelist, Logs, Docker, Failed Uploads) so HTML5 tabs retain 100%
 * feature parity with the original MainMenuScreen.
 */
@Composable
fun rememberNasToolboxHost(
    context: Context,
    onOpenFiles: () -> Unit,
    onOpenLatestPhotos: () -> Unit,
    onOpenRecentVideos: () -> Unit,
    onOpenTrash: () -> Unit,
): NasToolboxDispatcher {
    val scope = rememberCoroutineScope()
    val prefsRepo = remember(context) { PreferencesRepository.get(context) }
    val deviceVM = LocalDeviceManagementVM.current
    val sysMonitorVM = LocalSystemMonitorVM.current
    val smartToolsVM = LocalSmartToolsVM.current
    val autoBackupVM = LocalAutoBackupVM.current

    var showToolboxDialog by remember { mutableStateOf(false) }
    var showAutoBackupDialog by remember { mutableStateOf(false) }
    var isAutoBackupEnabled by remember { mutableStateOf(prefsRepo.isAutoBackupFlag()) }
    var deleteAfterBackup by remember { mutableStateOf(prefsRepo.isDeleteAfterBackup()) }
    var showLivestreamDialog by remember { mutableStateOf(false) }
    var showDuplicateScanDialog by remember { mutableStateOf(false) }
    var showUsbImportDialog by remember { mutableStateOf(false) }
    var showSmbDialog by remember { mutableStateOf(false) }
    var showNasBackupDialog by remember { mutableStateOf(false) }
    var showDiskHealthDialog by remember { mutableStateOf(false) }
    var showSleepScheduleDialog by remember { mutableStateOf(false) }
    var showBandwidthDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadLink by remember { mutableStateOf("") }
    var showLanWhitelistDialog by remember { mutableStateOf(false) }
    var showCommonDialog by remember { mutableStateOf(false) }
    var commonDialogType by remember { mutableStateOf(DialogType.SUCCESS) }
    var commonDialogMessage by remember { mutableStateOf("") }
    var pendingSettingsRedirect by remember { mutableStateOf(false) }

    val torrentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            uploadTorrentFileToNas(scope, context, uri)
            showDownloadDialog = false
            downloadLink = ""
        }
    }

    val dispatcher = remember {
        NasToolboxDispatcher { act ->
            when (act) {
                "toolbox" -> { showToolboxDialog = true; true }
                "sync", "backup" -> {
                    isAutoBackupEnabled = prefsRepo.isAutoBackupFlag()
                    deleteAfterBackup = prefsRepo.isDeleteAfterBackup()
                    showAutoBackupDialog = true
                    true
                }
                "livestream", "stream" -> { showLivestreamDialog = true; true }
                "duplicate" -> {
                    when {
                        smartToolsVM.isWorkerRunning || smartToolsVM.isScanningDuplicates -> smartToolsVM.isScanningDuplicates = true
                        smartToolsVM.duplicateFilesList.isNotEmpty() -> smartToolsVM.isShowingDuplicates = true
                        else -> showDuplicateScanDialog = true
                    }
                    true
                }
                "usbImport" -> { deviceVM.fetchUsbImportStatus(); showUsbImportDialog = true; true }
                "smb" -> { deviceVM.fetchSmbStatus(); showSmbDialog = true; true }
                "nasBackup" -> { sysMonitorVM.fetchNasConfigBackups(); showNasBackupDialog = true; true }
                "sleepSchedule" -> { showSleepScheduleDialog = true; true }
                "bandwidth" -> { showBandwidthDialog = true; true }
                "download" -> { showDownloadDialog = true; true }
                "lanWhitelist" -> { showLanWhitelistDialog = true; true }
                "logs" -> { deviceVM.loadSystemLogs(minIntervalMs = 0L); deviceVM.showLogDialog = true; true }
                "failedUploads" -> { deviceVM.loadFailedUploads(context); deviceVM.showFailedUploadsDialog = true; true }
                "docker" -> { deviceVM.showDockerDialog = true; true }
                else -> false
            }
        }
    }

    if (showToolboxDialog) {
        ToolboxDialog(
            prefsRepo = prefsRepo,
            context = context,
            onDismiss = { showToolboxDialog = false },
            onOpenLatestPhotos = onOpenLatestPhotos,
            onOpenRecentVideos = onOpenRecentVideos,
            onOpenTrash = onOpenTrash,
            showAutoBackupDialog = { dispatcher.dispatch("backup") },
            showLanWhitelistDialog = { showLanWhitelistDialog = true },
            showLivestreamDialog = { showLivestreamDialog = true },
            showNasBackupDialog = { sysMonitorVM.fetchNasConfigBackups(); showNasBackupDialog = true },
            showDiskHealthDialog = { showDiskHealthDialog = true },
            showSleepScheduleDialog = { showSleepScheduleDialog = true },
            showBandwidthDialog = { showBandwidthDialog = true },
            showUsbImportDialog = { deviceVM.fetchUsbImportStatus(); showUsbImportDialog = true },
            showDownloadDialog = { showDownloadDialog = true },
            showSmbDialog = { deviceVM.fetchSmbStatus(); showSmbDialog = true },
            showDuplicateScanDialog = { dispatcher.dispatch("duplicate") }
        )
    }

    if (showAutoBackupDialog) {
        AutoBackupDialog(
            context = context,
            isAutoBackupEnabled = isAutoBackupEnabled,
            isAutoBackupRunning = autoBackupVM.isAutoBackupRunning,
            onAutoBackupEnabledChange = { isAutoBackupEnabled = it },
            deleteAfterBackup = deleteAfterBackup,
            onDeleteAfterBackupChange = { deleteAfterBackup = it },
            onSaveAndSchedule = {
                if (isAutoBackupEnabled) {
                    val hasFullAccess = MediaPermissionHelper.canRunPeriodicBackup(context)
                    if (!hasFullAccess) {
                        commonDialogType = DialogType.WARNING
                        commonDialogMessage = if (MediaPermissionHelper.hasNoMediaAccess(context)) {
                            "Chưa cấp quyền truy cập Media. Bấm Đồng ý để mở Cài đặt cấp quyền READ_MEDIA_IMAGES/VIDEO."
                        } else {
                            "Đang ở chế độ ảnh một phần (chỉ ảnh đã chọn). Bấm Đồng ý để mở Cài đặt và chọn \"Cho phép tất cả\"."
                        }
                        pendingSettingsRedirect = true
                        showCommonDialog = true
                    } else {
                        prefsRepo.setAutoBackupFlag(true)
                        prefsRepo.setDeleteAfterBackup(deleteAfterBackup)
                        val constraints = Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresCharging(true)
                            .build()
                        val backupReq = PeriodicWorkRequestBuilder<AutoBackupWorker>(12, TimeUnit.HOURS)
                            .setConstraints(constraints)
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                            .addTag("com.nas.naswebdav.AutoBackupWorker")
                            .build()
                        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                            "AutoBackupWork",
                            ExistingPeriodicWorkPolicy.REPLACE,
                            backupReq
                        )
                        commonDialogType = DialogType.SUCCESS
                        commonDialogMessage = "Đã lưu cấu hình Auto-Backup!"
                        showCommonDialog = true
                    }
                } else {
                    prefsRepo.setAutoBackupFlag(false)
                    prefsRepo.setDeleteAfterBackup(deleteAfterBackup)
                    WorkManager.getInstance(context).cancelUniqueWork("AutoBackupWork")
                }
                showAutoBackupDialog = false
            },
            onTriggerManualSync = {
                autoBackupVM.triggerManualBackup(context) { msg ->
                    commonDialogType = DialogType.SUCCESS
                    commonDialogMessage = msg
                    showCommonDialog = true
                }
                showAutoBackupDialog = false
            },
            onCancelSync = {
                autoBackupVM.cancelAutoBackup(context)
                showAutoBackupDialog = false
            },
            onDismiss = { showAutoBackupDialog = false }
        )
    }

    val showBackupResult by AutoBackupState.showResultDialog.collectAsState()
    val backupResultTotal by AutoBackupState.resultTotal.collectAsState()
    val backupResultSuccess by AutoBackupState.resultSuccess.collectAsState()
    val backupResultSkipped by AutoBackupState.resultSkipped.collectAsState()
    val backupResultFailed by AutoBackupState.resultFailed.collectAsState()
    if (showBackupResult) {
        AlertDialog(
            onDismissRequest = { AutoBackupState.showResultDialog.value = false },
            title = { Text("Kết quả đồng bộ", fontWeight = FontWeight.Bold) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Tổng tệp đã quét: $backupResultTotal", fontSize = 14.sp)
                    Text("Thành công: $backupResultSuccess", color = AccentGreen, fontWeight = FontWeight.Bold)
                    Text("Bỏ qua: $backupResultSkipped", color = TextSecondary)
                    Text("Thất bại: $backupResultFailed", color = if (backupResultFailed > 0) AccentRed else TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = { AutoBackupState.showResultDialog.value = false }) {
                    Text("Đóng", color = AccentCyan)
                }
            },
            containerColor = DarkCard,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    if (showLivestreamDialog) LivestreamRecordDialog(onDismiss = { showLivestreamDialog = false })
    if (showDuplicateScanDialog) {
        DuplicateConfigDialog(
            context = context,
            onStartScan = { forceRestart, lightningMode ->
                smartToolsVM.startBackgroundDuplicateScan(context, forceRestart = forceRestart, lightningMode = lightningMode)
                smartToolsVM.isScanningDuplicates = true
            },
            onDismiss = { showDuplicateScanDialog = false }
        )
    }
    if (showUsbImportDialog) UsbImportDialog(onDismiss = { showUsbImportDialog = false })
    if (showSmbDialog) MainMenuBottomSheetSmbBottomSheet(onDismiss = { showSmbDialog = false })
    if (showNasBackupDialog) NasConfigBackupDialog(onDismiss = { showNasBackupDialog = false })
    if (showDiskHealthDialog) DiskProfileBottomSheet(onDismiss = { showDiskHealthDialog = false })
    if (showSleepScheduleDialog) SleepScheduleDialog(onDismiss = { showSleepScheduleDialog = false })
    if (showBandwidthDialog) BandwidthThrottleDialog(prefsRepo = prefsRepo, onDismiss = { showBandwidthDialog = false })
    if (showLanWhitelistDialog) LanWhitelistDialog(onDismiss = { showLanWhitelistDialog = false })
    if (showDownloadDialog) {
        DownloadDialog(
            downloadLink = downloadLink,
            onLinkChange = { downloadLink = it },
            onConfirm = {
                if (downloadLink.isNotBlank()) {
                    sendDownloadLinkToQbittorrent(scope, downloadLink)
                    showDownloadDialog = false
                    downloadLink = ""
                }
            },
            onDismiss = { showDownloadDialog = false },
            onPickTorrentFile = {
                torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
            }
        )
    }
    if (deviceVM.showLogDialog) SystemLogDialog(onDismiss = { deviceVM.showLogDialog = false })
    if (deviceVM.showFailedUploadsDialog) {
        FailedUploadsDialog(
            failedUploads = deviceVM.failedUploads,
            isLoading = deviceVM.isLoadingFailedUploads,
            onRetry = { deviceVM.retryFailedUpload(context, it.id) },
            onRetryAll = { deviceVM.retryAllFailedUploads(context) },
            onDelete = { deviceVM.deleteFailedUpload(context, it) },
            onDismiss = { deviceVM.showFailedUploadsDialog = false }
        )
    }
    if (deviceVM.showDockerDialog) DockerDialog(onDismiss = { deviceVM.showDockerDialog = false })

    if (showCommonDialog) {
        AppStatusDialog(
            type = commonDialogType,
            message = commonDialogMessage,
            onDismiss = {
                showCommonDialog = false
                if (pendingSettingsRedirect) {
                    pendingSettingsRedirect = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        )
    }

    MainMenuBottomSheetDuplicateScanGlobalUI(context)
    return dispatcher
}
