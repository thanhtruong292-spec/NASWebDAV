package com.nas.naswebdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nas.naswebdav.auth.AuthSessionViewModel
import com.nas.naswebdav.backup.AutoBackupViewModel
import com.nas.naswebdav.browser.FileBrowserViewModel
import com.nas.naswebdav.device.DeviceManagementViewModel
import com.nas.naswebdav.livestream.LivestreamViewModel
import com.nas.naswebdav.monitor.SystemMonitorViewModel
import com.nas.naswebdav.smarttools.SmartToolsViewModel

/**
 * DomainViewModelProvider — manual DI for 7 Domain ViewModels.
 * Now acts strictly as a dependency container record. 
 * ViewModels are managed by MainActivity's ViewModelStore.
 */
data class DomainViewModelProvider(
    val authSession: AuthSessionViewModel,
    val fileBrowser: FileBrowserViewModel,
    val systemMonitor: SystemMonitorViewModel,
    val globalUi: GlobalUiViewModel,
    val deviceManagement: DeviceManagementViewModel,
    val smartTools: SmartToolsViewModel,
    val livestream: LivestreamViewModel,
    val autoBackup: AutoBackupViewModel,
    val repository: WebDavRepository
)