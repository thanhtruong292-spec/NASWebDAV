package com.nas.naswebdav

/**
 * NasModels.kt — Top-level domain data classes extracted from WebDavViewModel
 *                in Phase 7d.7 (VM facade deletion).
 *
 * These were previously nested inside the `WebDavViewModel` facade. Moving
 * them to a top-level package makes them directly importable by domain VMs
 * and UI files without going through the facade.
 */

// ─── Inner data classes of WebDavViewModel (Phase 7d.7 extraction) ─────

/**
 * File metadata for a NAS config backup (.tar.gz snapshot of the server's
 * config). Surfaced in the DiskHealth / Backup dialogs.
 */
data class NasConfigBackup(
    val filename: String,
    val sizeBytes: Long,
    val sizeHuman: String,
    val createdAt: String,
    val mtime: Double,
)


/**
 * A single SMART/disk-health sample — either current snapshot or one history
 * point. Many nullables because not every SMART counter is reported on every
 * drive; eMMC vs HDD vs SSD have different attribute sets.
 */
data class DiskHealthSample(
    val ts: Long,
    val datetime: String,
    val score: Int,
    val smartStatus: String,
    val tempC: Int?,
    val powerOnHours: Int?,
    val reallocatedSectors: Int?,
    val pendingSectors: Int?,
    val offlineUncorrectable: Int?,
    val udmaCrcErr: Int?,
    val commandTimeout: Int?,
    val ext4ErrorsRecent: Int,
    val sataResetsRecent: Int,
    val ioErrorsRecent: Int,
    val warnings: List<String>,
)

/**
 * User-configurable schedule for automatic rclone-based NAS→cloud backups.
 * Matches the JSON contract exposed by the server's /api/backup/schedule.
 */
data class BackupSchedule(
    val enabled: Boolean = false,
    val frequency: String = "weekly",
    val hour: Int = 3,
    val retentionCount: Int = 7,
    val rcloneRemote: String = "",
    val rclonePath: String = "/NASBackup/",
    val lastRunTs: Long = 0L,
    val lastRunResult: String = "",
    val lastRunFile: String = "",
)

/**
 * Lightweight view of an in-flight copy/move task in the NAS Insights panel.
 * Backend serializes this as a list under `insights.flow_tasks`.
 */
data class InsightFlowTask(
    val type: String = "",
    val label: String = "",
    val file: String = "",
    val source: String = "",
    val dest: String = "",
    val speedBps: Long = 0L,
    val progress: Int = 0,
)

/**
 * Aggregate of all NAS Insights widgets on the SystemMonitorCard.
 * Backed by GET /api/insights; refreshed on app foreground.
 */
data class NasInsights(
    val hddScore: Int = 0,
    val hddStatusText: String = "",
    val hddTempC: Int = 0,
    val hddMinScore: Int = 0,
    val hddScoreDelta: Int = 0,
    val workloadMode: String = "normal",
    val workloadPressure: Int = 0,
    val workloadRecommendation: String = "",
    val workloadReasons: List<String> = emptyList(),
    val emmcRootPercent: Int = 0,
    val emmcLogPercent: Int = 0,
    val emmcWarnings: List<String> = emptyList(),
    val emmcRecommendations: List<String> = emptyList(),
    val diskReadBps: Long = 0L,
    val diskWriteBps: Long = 0L,
    val netRxBps: Long = 0L,
    val netTxBps: Long = 0L,
    val flowTasks: List<InsightFlowTask> = emptyList(),
    val maintenanceActions: List<InsightAction> = emptyList(),
    val usbHistoryCount: Int = 0,
    val updatedAt: Long = 0L,
)

/** A single recommended maintenance action surfaced in the insights panel. */
data class InsightAction(val priority: String = "", val title: String = "", val detail: String = "")

/**
 * HDD spindown / suspend schedule. Sent to GET/POST /api/system/sleep_schedule.
 * `mode` is "spindown" (park heads + stop rotation) or "suspend" (full SCSI stop).
 */
data class SleepSchedule(
    val enabled: Boolean = false,
    val mode: String = "spindown",     // spindown | suspend
    val startHour: Int = 23,
    val endHour: Int = 7,
    val idleOnly: Boolean = true,
    val currentHddState: String = "unknown",
    val inWindowNow: Boolean = false,
    val lastActionTs: Long = 0L,
    val lastActionState: String = "",
)

/**
 * A single in-flight recording/streaming job. `var` fields are mutated live as
 * the stream runs (status changes, size grows, duration ticks). Three platforms:
 * TikTok, Facebook, YouTube — `watchUsername` is set for TikTok only.
 */
data class LivestreamJob(
    val jobId: String,
    val platform: String,
    var status: String = "recording",
    var fileSize: String = "0 B",
    var duration: String = "0h00m00s",
    var durationSeconds: Long = 0,
    var startedTs: Long = 0,
    var speed: String = "",
    var outputFile: String = "",
    // TikTok username (de UI hien "@user" thay vi job_id/filename rac roi).
    // Rong khi job khong gan voi user nao (vd ghi facebook/youtube).
    var watchUsername: String = ""
)

/**
 * Backend's view of a TikTok user we're monitoring for live streams. The local
 * daemon polls these and surfaces "watching/last error/etc" in the UI.
 */
data class TikTokLiveWatchUser(
    val username: String,
    val status: String = "watching",
    val lastCheck: String = "",
    val lastLive: String = "",
    val lastError: String = "",
    val jobId: String = ""
)

// ─── Pre-existing top-level data classes (Phase 7d.7 extraction from facade file) ───

/** Active torrent the NAS reports via /api/torrent/list. */
data class TorrentInfo(
    val name: String,
    val progress: Float,
    val speed: String,
    val hash: String = "",
    val state: String = "",
    val savePath: String = ""
)

/** SMART drive diagnostics snapshot. */
data class SmartInfo(val status: String, val temperature: String, val rawLog: String)

/** Result of a NAS disk speed test (write+read). */
data class SpeedTestResult(val writeSpeed: String, val readSpeed: String)

/** Result of thumbnail audit (how many files are thumbnailed, missing, errors, etc.). */
data class ThumbnailAuditData(
    val total: Int = 0,
    val thumbnailed: Int = 0,
    val missing: Int = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
    val errors: Int = 0
)

/** A single OMV systemd service in the OMV overview panel. */
data class OmvServiceInfo(
    val name: String,
    val title: String,
    val enabled: Boolean,
    val running: Boolean,
    val effectiveEnabled: Boolean = enabled && running
)

/** Network adapter info surfaced in OMV overview. */
data class OmvNetworkInfo(
    val name: String,
    val address: String,
    val mac: String,
    val speed: Int,
    val state: String,
    val gateway: String,
    val wol: Boolean
)

/** Mount point / filesystem usage. */
data class OmvFilesystem(
    val device: String,
    val label: String,
    val mountpoint: String,
    val used: String,
    val sizeBytes: Long,
    val percentage: Int,
    val description: String
)

/** A physical disk reported by OMV (and meta: isTargetHdd, isUsbImport). */
data class OmvDiskInfo(
    val name: String,
    val model: String,
    val serial: String,
    val size: String,
    val isRoot: Boolean,
    val device: String = "",
    val isTargetHdd: Boolean = false,
    val isUsbImport: Boolean = false
)

/** OMV system overview — services, network, filesystems, disks, power button action. */
data class OmvOverview(
    val hostname: String = "",
    val omvVersion: String = "",
    val kernel: String = "",
    val services: List<OmvServiceInfo> = emptyList(),
    val network: List<OmvNetworkInfo> = emptyList(),
    val filesystems: List<OmvFilesystem> = emptyList(),
    val disks: List<OmvDiskInfo> = emptyList(),
    val powerBtnAction: String = ""
)

/** A single container in the Docker manager UI. */
data class DockerContainer(val id: String, val name: String, val status: String)

/** A grouping of files by category in the Smart Organizer result. */
data class OrganizerGroup(
    val label: String,
    val count: Int,
    val size: Long,
    val sampleFiles: List<String>
)

/** Smart Organizer filter mode. */
enum class OrganizerFilter { ALL, IMAGE, VIDEO }

/** One mount point in the DiskPart breakdown. */
data class DiskPart(
    val mount: String,
    val percent: Float,
    val total: String,
    val used: String
)

/** Per-folder storage usage stat used by the storage breakdown card. */
data class StorageFolderUsage(
    val name: String,
    val path: String,
    val size: String,
    val sizeBytes: Long,
    val files: Int,
    val partial: Boolean
)

/** Top-level system status snapshot from /api/status/realtime. */
data class NasSystemStatus(
    val temp: String = "--°C",
    val cpu: String = "--%",
    val cpuTemp: String = "--°C",
    val ram: String = "--",
    val disk: String = "--%",
    val diskCapacity: String = "",
    val netRx: String = "0 B/s",
    val netTx: String = "0 B/s",
    val uptime: String = "--:--",
    val status: String = "Đang kết nối...",
    val ramPercent: String = "0",
    val torrents: List<TorrentInfo> = emptyList(),
    val diskParts: List<DiskPart> = emptyList(),
    val fanStatus: String = "--",
    val fanMode: String = "auto",
    val fanOnTemp: Float = 45f,
    val fanOffTemp: Float = 40f,
    val fanRpm: Int? = null,
    val topProcesses: List<Pair<String, Float>> = emptyList()
)

/** Guest pass credential with expiry, returned by /api/guest_pass/create. */
data class GuestPassInfo(
    val username: String,
    val password: String,
    val host: String,
    val ftpPort: Int,
    val expiresAt: Long
)

/** One process row in the System / Process list. */
data class SystemProcess(
    val pid: Int,
    val name: String,
    val user: String,
    val status: String,
    val cpu: Float,
    val mem: Float,
    val isSystem: Boolean = false
)

/** An entry in the Social Download history (TikTok / Facebook / YouTube). */
data class SocialDownloadItem(
    val url: String,
    val platform: String,
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/** A single CPU/RAM/Net metrics snapshot for the realtime metrics graph. */
data class MetricsSnapshot(
    val timestamp: String = "",
    val cpuPercent: Float = 0f,
    val ramPercent: Float = 0f,
    val cpuTemp: Float = 0f,
    val hddTemp: Float = 0f,
    val netRxKbps: Float = 0f,
    val netTxKbps: Float = 0f
)

/** Aggregate stats for one day, returned by /api/daily-report. */
data class DailyReportData(
    val date: String = "",
    val healthScore: Int = 0,
    val cpuAvg: Float = 0f,
    val cpuPeak: Float = 0f,
    val ramAvg: Float = 0f,
    val ramPeak: Float = 0f,
    val cpuTempAvg: Float = 0f,
    val cpuTempPeak: Float = 0f,
    val hddTempAvg: Float = 0f,
    val hddTempPeak: Float = 0f,
    val downloadMb: Float = 0f,
    val uploadMb: Float = 0f,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val samples: Int = 0
)
