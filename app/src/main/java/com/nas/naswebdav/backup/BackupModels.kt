package com.nas.naswebdav.backup

// Re-export data classes from WebDavViewModel — local aliases để compile độc lập
// Khi Phase 7 hoàn tất, các class này sẽ được move ra top-level files riêng

typealias BackupSchedule = com.nas.naswebdav.WebDavViewModel.BackupSchedule
typealias SleepSchedule = com.nas.naswebdav.WebDavViewModel.SleepSchedule
typealias UsbImportState = com.nas.naswebdav.WebDavViewModel.UsbImportState
typealias UsbImportSettings = com.nas.naswebdav.WebDavViewModel.UsbImportSettings
typealias UsbImportConflict = com.nas.naswebdav.WebDavViewModel.UsbImportConflict