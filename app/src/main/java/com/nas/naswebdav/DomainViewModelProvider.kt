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
 * DomainViewModelProvider — Phase 7b.1 (manual DI).
 *
 * Holds the 7 Domain VMs that back Phase 7c UI migration. Single source for
 * both the Facade (WebDavViewModel's `authSession`/`fileBrowser`/... lazy
 * properties) and the CompositionLocals consumed by individual screens.
 *
 * Critical: this provider creates each VM exactly ONCE per Activity. Passing
 * the same instances into both WebDavViewModel's constructor and the
 * CompositionLocalProvider guarantees that state mutations through either path
 * stay in sync — preventing the drift that would occur if both created their
 * own copies.
 */
class DomainViewModelProvider(
    val repository: WebDavRepository
) : ViewModelProvider.Factory {

    val authSession: AuthSessionViewModel by lazy { AuthSessionViewModel(repository) }
    val fileBrowser: FileBrowserViewModel by lazy { FileBrowserViewModel(repository) }
    val systemMonitor: SystemMonitorViewModel by lazy { SystemMonitorViewModel(repository) }
    val deviceManagement: DeviceManagementViewModel by lazy { DeviceManagementViewModel(repository) }
    val smartTools: SmartToolsViewModel by lazy { SmartToolsViewModel(repository) }
    val livestream: LivestreamViewModel by lazy { LivestreamViewModel(repository) }
    val autoBackup: AutoBackupViewModel by lazy { AutoBackupViewModel(repository) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(AuthSessionViewModel::class.java) -> authSession as T
        modelClass.isAssignableFrom(FileBrowserViewModel::class.java) -> fileBrowser as T
        modelClass.isAssignableFrom(SystemMonitorViewModel::class.java) -> systemMonitor as T
        modelClass.isAssignableFrom(DeviceManagementViewModel::class.java) -> deviceManagement as T
        modelClass.isAssignableFrom(SmartToolsViewModel::class.java) -> smartTools as T
        modelClass.isAssignableFrom(LivestreamViewModel::class.java) -> livestream as T
        modelClass.isAssignableFrom(AutoBackupViewModel::class.java) -> autoBackup as T
        else -> throw IllegalArgumentException(
            "DomainViewModelProvider: unknown VM class ${modelClass.name}"
        )
    }
}