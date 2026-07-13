package com.nas.naswebdav

import androidx.compose.runtime.staticCompositionLocalOf
import com.nas.naswebdav.auth.AuthSessionViewModel
import com.nas.naswebdav.backup.AutoBackupViewModel
import com.nas.naswebdav.browser.FileBrowserViewModel
import com.nas.naswebdav.device.DeviceManagementViewModel
import com.nas.naswebdav.livestream.LivestreamViewModel
import com.nas.naswebdav.monitor.SystemMonitorViewModel
import com.nas.naswebdav.smarttools.SmartToolsViewModel

/**
 * CompositionLocals for the 7 Domain ViewModels — Phase 7b.
 *
 * Provides type-safe injection points for Composables during Phase 7 UI migration.
 * MainActivity's setContent wraps NasTheme + NavHost in CompositionLocalProvider
 * that supplies the SAME instances referenced by WebDavViewModel's facade mirror
 * properties — preventing state drift between facade and direct CompositionLocal
 * readers.
 *
 * `staticCompositionLocalOf` is used (not `compositionLocalOf`) because:
 *   - These are root-level singletons; only `setContent` provides them once.
 *   - Reading a static local skips Compose's state tracking, which is correct
 *     here because the VMs themselves own Compose State.
 *   - Trade-off: changing the provided VM after first composition throws
 *     IllegalStateException — which is exactly what we want (lock the topology).
 */
val LocalAuthSessionVM = staticCompositionLocalOf<AuthSessionViewModel> {
    error("AuthSessionViewModel not provided — wrap setContent in CompositionLocalProvider")
}

val LocalFileBrowserVM = staticCompositionLocalOf<FileBrowserViewModel> {
    error("FileBrowserViewModel not provided — wrap setContent in CompositionLocalProvider")
}

val LocalSystemMonitorVM = staticCompositionLocalOf<SystemMonitorViewModel> {
    error("SystemMonitorViewModel not provided — wrap setContent in CompositionLocalProvider")
}

val LocalDeviceManagementVM = staticCompositionLocalOf<DeviceManagementViewModel> {
    error("DeviceManagementViewModel not provided — wrap setContent in CompositionLocalProvider")
}

val LocalSmartToolsVM = staticCompositionLocalOf<SmartToolsViewModel> {
    error("SmartToolsViewModel not provided — wrap setContent in CompositionLocalProvider")
}

val LocalLivestreamVM = staticCompositionLocalOf<LivestreamViewModel> {
    error("LivestreamViewModel not provided — wrap setContent in CompositionLocalProvider")
}

val LocalAutoBackupVM = staticCompositionLocalOf<AutoBackupViewModel> {
    error("AutoBackupViewModel not provided — wrap setContent in CompositionLocalProvider")
}