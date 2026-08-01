package com.nas.naswebdav

// Top-level model classes for USB Import (Phase 7d.3)
// Moved out of WebDavViewModel inner classes to allow domain VMs to reference them.

data class UsbImportSettings(
    val enabled: Boolean = true,
    val destFolder: String = "USB Import",
    val copyMode: String = "new_only",
    val autoMount: Boolean = true,
    val mountReadonly: Boolean = true,
    val pollSeconds: Int = 15,
    val resumeEnabled: Boolean = true,
    val verifyChecksum: Boolean = false,
)

data class UsbImportConflict(
    val rel: String = "",
    val sourceName: String = "",
    val destName: String = "",
    val sourceSize: Long = 0L,
    val destSize: Long = 0L,
)

data class UsbImportState(
    val enabled: Boolean = true,
    val status: String = "idle",
    val message: String = "",
    val activeDevice: String = "",
    val activeMount: String = "",
    val destDir: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    val filesSkipped: Int = 0,
    val filesFailed: Int = 0,
    val bytesDone: Long = 0L,
    val bytesProcessed: Long = 0L,
    val bytesTotal: Long = 0L,
    val currentFile: String = "",
    val currentSource: String = "",
    val currentDest: String = "",
    val currentFileBytesDone: Long = 0L,
    val currentFileBytesTotal: Long = 0L,
    val copySpeedBps: Long = 0L,
    val etaSeconds: Long = 0L,
    val lastProgressAt: Long = 0L,
    val lastError: String = "",
    val settings: UsbImportSettings = UsbImportSettings(),
    val detectedDevicesInfo: String = "",
    val needsAction: Boolean = false,
    val pendingConflictsCount: Int = 0,
    val pendingErrorsCount: Int = 0,
    val pendingConflicts: List<UsbImportConflict> = emptyList(),
)
