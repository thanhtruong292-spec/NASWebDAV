package com.nas.naswebdav.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Icon size tokens. Visual glyph uses these sizes; interactive containers
 * should still be at least 48.dp to satisfy Material touch-target minimum.
 */
internal object AppIconSize {
    val Small = 18.dp
    val Medium = 24.dp
    val Large = 32.dp
    val Empty = 48.dp
    val Hero = 120.dp
}

/**
 * Semantic tint tokens for icons. Use these instead of raw Color() so
 * icons stay consistent across screens and theme variants.
 */
internal object IconTint {
    val Primary = TextPrimary
    val Secondary = TextSecondary
    val Muted = TextTertiary
    val Info = AccentCyan
    val Success = AccentGreen
    val Warning = AccentOrange
    val Error = AccentRed
}

/**
 * File-type color coding. Apply via FileTypeColors.image / .video / etc.
 */
internal object FileTypeColors {
    val Folder = AccentBlue
    val Document = AccentOrange
    val Image = AccentPurple
    val Video = AccentPink
    val Archive = TextTertiary
    val Code = AccentCyan
    val Generic = TextSecondary
}

/**
 * Single source of truth for icon choices. Screens should import from
 * here rather than writing Icons.Default.Foo directly. Splitting between
 * Filled (primary / status / active) and Outlined (file rows / secondary)
 * enforces a consistent visual hierarchy.
 */
internal object AppIcons {
    // Navigation
    val Home = Icons.Filled.Home
    val Menu = Icons.Filled.Menu
    val Dashboard = Icons.Filled.Dashboard
    val Back = Icons.AutoMirrored.Filled.ArrowBack
    val Close = Icons.Filled.Close
    val More = Icons.Filled.MoreVert
    val ExpandMore = Icons.Filled.ExpandMore
    val ExpandLess = Icons.Filled.ExpandLess
    val Launch = Icons.Filled.Launch
    val OpenInNew = Icons.Filled.OpenInNew

    // File / folder
    val Folder = Icons.Outlined.Folder
    val FolderOpen = Icons.Outlined.FolderOpen
    val File = Icons.Outlined.InsertDriveFile
    val FileDocument = Icons.Outlined.Description
    val FileImage = Icons.Outlined.Image
    val FileVideo = Icons.Outlined.VideoLibrary
    val FileArchive = Icons.Outlined.Archive
    val FileCode = Icons.Outlined.Code
    val FileFilled = Icons.Filled.Description
    val ImageFilled = Icons.Filled.Image
    val VideoFilled = Icons.Filled.VideoLibrary

    // Action
    val Add = Icons.Filled.Add
    val Edit = Icons.Filled.Edit
    val Delete = Icons.Filled.Delete
    val Search = Icons.Filled.Search
    val SearchOff = Icons.Outlined.SearchOff
    val Sort = Icons.AutoMirrored.Filled.Sort
    val Refresh = Icons.Filled.Refresh
    val Copy = Icons.Filled.ContentCopy
    val Move = Icons.AutoMirrored.Filled.DriveFileMove
    val Restore = Icons.Filled.Restore
    val Save = Icons.Filled.Save
    val Download = Icons.Filled.Download
    val Upload = Icons.Filled.CloudUpload
    val DownloadCloud = Icons.Filled.CloudDownload
    val CreateFolder = Icons.Filled.CreateNewFolder

    // Status / feedback
    val Success = Icons.Filled.CheckCircle
    val Check = Icons.Filled.Check
    val Done = Icons.Filled.Done
    val Clear = Icons.Filled.Clear
    val Warning = Icons.Filled.Warning
    val Error = Icons.Filled.Error
    val Info = Icons.Filled.Info
    val Sync = Icons.Filled.Sync
    val SyncDone = Icons.Filled.CloudDone
    val CloudOff = Icons.Filled.CloudOff
    val Cloud = Icons.Filled.Cloud

    // Media control
    val Play = Icons.Filled.PlayArrow
    val Pause = Icons.Filled.Pause
    val Stop = Icons.Filled.Stop

    // System / device
    val Storage = Icons.Filled.Storage
    val Memory = Icons.Filled.Memory
    val Network = Icons.Filled.NetworkWifi
    val Wifi = Icons.Filled.Wifi
    val Speed = Icons.Filled.Speed
    val Usb = Icons.Filled.Usb
    val Lock = Icons.Filled.Lock
    val Fingerprint = Icons.Filled.Fingerprint
    val History = Icons.Filled.History
    val Settings = Icons.Filled.Settings
    val Build = Icons.Filled.Build
    val Help = Icons.AutoMirrored.Filled.Help
    val Person = Icons.Filled.Person
    val Account = Icons.Filled.AccountCircle
    val ArrowUp = Icons.Filled.ArrowUpward
    val ArrowDown = Icons.Filled.ArrowDownward
}

/**
 * Decide a file-row icon based on extension. Returns an ImageVector only —
 * callers pair with FileTypeColors and apply tint themselves.
 */
internal fun fileRowIcon(name: String, isDirectory: Boolean): ImageVector = when {
    isDirectory -> AppIcons.FolderOpen
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
        name.endsWith(".png", true) || name.endsWith(".webp", true) ||
        name.endsWith(".heic", true) || name.endsWith(".gif", true) ||
        name.endsWith(".bmp", true) -> AppIcons.FileImage
    name.endsWith(".mp4", true) || name.endsWith(".mkv", true) ||
        name.endsWith(".mov", true) || name.endsWith(".avi", true) ||
        name.endsWith(".webm", true) -> AppIcons.FileVideo
    name.endsWith(".zip", true) || name.endsWith(".tar", true) ||
        name.endsWith(".gz", true) || name.endsWith(".7z", true) ||
        name.endsWith(".rar", true) -> AppIcons.FileArchive
    name.endsWith(".kt", true) || name.endsWith(".java", true) ||
        name.endsWith(".py", true) || name.endsWith(".js", true) ||
        name.endsWith(".ts", true) || name.endsWith(".json", true) ||
        name.endsWith(".xml", true) || name.endsWith(".log", true) ||
        name.endsWith(".md", true) || name.endsWith(".txt", true) -> AppIcons.FileCode
    name.endsWith(".pdf", true) || name.endsWith(".doc", true) ||
        name.endsWith(".docx", true) || name.endsWith(".xls", true) ||
        name.endsWith(".xlsx", true) -> AppIcons.FileDocument
    else -> AppIcons.File
}

internal fun fileRowTint(name: String, isDirectory: Boolean): Color = when {
    isDirectory -> FileTypeColors.Folder
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
        name.endsWith(".png", true) || name.endsWith(".webp", true) ||
        name.endsWith(".heic", true) || name.endsWith(".gif", true) ||
        name.endsWith(".bmp", true) -> FileTypeColors.Image
    name.endsWith(".mp4", true) || name.endsWith(".mkv", true) ||
        name.endsWith(".mov", true) || name.endsWith(".avi", true) ||
        name.endsWith(".webm", true) -> FileTypeColors.Video
    name.endsWith(".zip", true) || name.endsWith(".tar", true) ||
        name.endsWith(".gz", true) || name.endsWith(".7z", true) ||
        name.endsWith(".rar", true) -> FileTypeColors.Archive
    name.endsWith(".kt", true) || name.endsWith(".java", true) ||
        name.endsWith(".py", true) || name.endsWith(".js", true) ||
        name.endsWith(".ts", true) || name.endsWith(".json", true) ||
        name.endsWith(".xml", true) || name.endsWith(".log", true) ||
        name.endsWith(".md", true) || name.endsWith(".txt", true) -> FileTypeColors.Code
    name.endsWith(".pdf", true) || name.endsWith(".doc", true) ||
        name.endsWith(".docx", true) || name.endsWith(".xls", true) ||
        name.endsWith(".xlsx", true) -> FileTypeColors.Document
    else -> FileTypeColors.Generic
}