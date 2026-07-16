package com.nas.naswebdav.backup

// Re-export data classes — Phase 7d.7: data classes now live top-level in
// com.nas.naswebdav.NasModels.kt (extracted from WebDavViewModel).
//
// We keep these local aliases so callers in this package can keep using the
// short names (`BackupSchedule`, `SleepSchedule`, `UsbImportState`, etc.) when
// importing com.nas.naswebdav.backup.* without fully qualifying every use.

typealias BackupSchedule = com.nas.naswebdav.BackupSchedule
typealias SleepSchedule = com.nas.naswebdav.SleepSchedule
typealias UsbImportState = com.nas.naswebdav.UsbImportState
typealias UsbImportSettings = com.nas.naswebdav.UsbImportSettings
typealias UsbImportConflict = com.nas.naswebdav.UsbImportConflict
