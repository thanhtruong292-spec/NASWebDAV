package com.nas.naswebdav.backup

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.WebDavViewModel
import kotlinx.coroutines.launch

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

    var backupSchedule by androidx.compose.runtime.mutableStateOf(WebDavViewModel.BackupSchedule())
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

    var sleepSchedule by androidx.compose.runtime.mutableStateOf(WebDavViewModel.SleepSchedule())
        internal set
    var sleepScheduleMessage by androidx.compose.runtime.mutableStateOf("")
        internal set
    var lockNowRequested by androidx.compose.runtime.mutableStateOf(false)
        internal set

    // ═══ PLACEHOLDER METHODS — implement Phase 3b ═══

    fun triggerManualBackup(context: Context) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun cancelAutoBackup(context: Context) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun toggleAutoBackupPause() { /* TODO Phase 3b */ }

    fun fetchBackupSchedule() {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun saveBackupSchedule(schedule: WebDavViewModel.BackupSchedule) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun fetchUsbImportStatus() {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun saveUsbImportSettings(settings: WebDavViewModel.UsbImportSettings) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun startUsbImportNow() {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun cancelUsbImport() {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun resolveUsbImportConflicts(conflicts: List<WebDavViewModel.UsbImportConflict>) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun fetchSleepSchedule() {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun saveSleepSchedule(schedule: WebDavViewModel.SleepSchedule) {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun spindownHddNow() {
        viewModelScope.launch { /* TODO Phase 3b */ }
    }

    fun requestLockNow() {
        lockNowRequested = true
    }
}