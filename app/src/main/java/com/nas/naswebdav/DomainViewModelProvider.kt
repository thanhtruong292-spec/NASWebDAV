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
 *
 * Holds the 7 Domain VMs. Single source for the CompositionLocals
 * consumed by individual screens. Creates each VM exactly ONCE per Activity.
 */
class DomainViewModelProvider(
    val repository: WebDavRepository
) : ViewModelProvider.Factory {

    val authSession: AuthSessionViewModel by lazy { AuthSessionViewModel(repository) }
    val fileBrowser: FileBrowserViewModel by lazy { FileBrowserViewModel(repository) }
    val systemMonitor: SystemMonitorViewModel by lazy { SystemMonitorViewModel(repository) }
    val globalUi: GlobalUiViewModel by lazy { GlobalUiViewModel() }
    val deviceManagement: DeviceManagementViewModel by lazy { DeviceManagementViewModel(repository, globalUi) }
    val smartTools: SmartToolsViewModel by lazy { SmartToolsViewModel(repository) }
    val livestream: LivestreamViewModel by lazy { LivestreamViewModel(repository) }
    val autoBackup: AutoBackupViewModel by lazy { AutoBackupViewModel(repository) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(AuthSessionViewModel::class.java) -> authSession as T
        modelClass.isAssignableFrom(FileBrowserViewModel::class.java) -> fileBrowser as T
        modelClass.isAssignableFrom(SystemMonitorViewModel::class.java) -> systemMonitor as T
        modelClass.isAssignableFrom(GlobalUiViewModel::class.java) -> globalUi as T
        modelClass.isAssignableFrom(DeviceManagementViewModel::class.java) -> deviceManagement as T
        modelClass.isAssignableFrom(SmartToolsViewModel::class.java) -> smartTools as T
        modelClass.isAssignableFrom(LivestreamViewModel::class.java) -> livestream as T
        modelClass.isAssignableFrom(AutoBackupViewModel::class.java) -> autoBackup as T
        else -> throw IllegalArgumentException(
            "DomainViewModelProvider: unknown VM class ${modelClass.name}"
        )
    }
}