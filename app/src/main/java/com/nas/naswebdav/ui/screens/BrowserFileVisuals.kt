package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.nas.naswebdav.NasFile
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.shared.ConnectionStatus
import com.nas.naswebdav.utils.FormatUtils
import com.nas.naswebdav.utils.MediaUtils

internal object BrowserDensity {
    val PageInset = 16.dp
    val EntryGap = 10.dp
    val FolderHeight = 76.dp
    val RowHeight = 64.dp
}

internal val FolderBadgeBg = AccentOrange.copy(alpha = 0.16f)
internal val FolderIconTint = Color(0xFFFBBF24)
private val FileDocTint = AccentCyan
private val FileVideoTint = AccentPink
private val FileImageTint = AccentGreen
private val FileArchiveTint = AccentPurple

/** Modern file-manager entry — kiểu File Manager hiện đại:
 *  folder = card nền nổi + badge tint màu + chữ trắng đậm + subtitle ngày tháng,
 *  file = preview bo góc + tên đậm + dung lượng rõ. Không còn kiểu phẳng xám. */
@Composable
internal fun BrowserEntryTile(
    file: NasFile,
    selectionMode: Boolean,
    isSelected: Boolean,
    isNewFile: Boolean,
    isTrash: Boolean,
) {
    // GRID: folder icon trần — nền trong suốt, không card/viền nên nhìn là lưới
    // icon ngay. Chỉ khi chọn mới highlight. File giữ card preview.
    val isFolder = file.isDirectory
    val bg = when {
        isSelected -> AccentCyan.copy(alpha = 0.22f)
        isFolder -> Color.Transparent
        else -> DarkCard
    }
    val borderColor = when {
        isSelected -> AccentCyan
        isFolder -> Color.Transparent
        else -> DarkCardBorder
    }
    val entryModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(bg)
        .then(if (isFolder && !isSelected) Modifier else Modifier.border(1.dp, borderColor, RoundedCornerShape(16.dp)))
        .semantics { selected = isSelected }

    Box(entryModifier) {
        if (file.isDirectory) {
            // Compact folder tile: more rows fit without reducing the touch area below 48dp.
            Column(
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier.size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) AccentCyan.copy(alpha = 0.22f) else FolderBadgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = if (isSelected) AccentCyan else FolderIconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    file.name,
                    style = AppTypography.BodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            Column {
                BrowserEntryPreview(
                    file,
                    Modifier.fillMaxWidth().aspectRatio(1.35f)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        file.name,
                        style = AppTypography.BodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        FormatUtils.formatBytes(file.contentLength),
                        style = AppTypography.BodySmall.copy(fontSize = 12.sp),
                        color = TextSecondary
                    )
                }
            }
        }

        if (selectionMode) {
            Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Đã chọn" else "Chưa chọn",
                tint = if (isSelected) AccentCyan else TextTertiary,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp)
                    .background(DarkSurface.copy(alpha = 0.85f), CircleShape),
            )
        } else if (!file.isDirectory && isTrash && file.lastModified > 0L) {
            val days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - file.lastModified)
            Text(
                "${(30 - days).coerceAtLeast(0)} ngày",
                style = AppTypography.BodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(AccentRed.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        } else if (isNewFile && !file.isDirectory) {
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(8.dp).background(AccentCyan, CircleShape))
        }
    }
}

private fun folderSubtitle(file: NasFile): String {
    if (file.lastModified > 0L) {
        return try {
            val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            "Thư mục • ${fmt.format(java.util.Date(file.lastModified))}"
        } catch (_: Exception) {
            "Thư mục"
        }
    }
    return "Thư mục"
}

internal fun fileBadgeColors(name: String): Pair<Color, Color> {
    return when {
        MediaUtils.isVideo(name) -> FileVideoTint.copy(alpha = 0.16f) to FileVideoTint
        MediaUtils.isImage(name) -> FileImageTint.copy(alpha = 0.16f) to FileImageTint
        name.lowercase().endsWith(".zip") || name.lowercase().endsWith(".rar") ||
            name.lowercase().endsWith(".7z") -> FileArchiveTint.copy(alpha = 0.16f) to FileArchiveTint
        else -> InfoContainer to FileDocTint
    }
}

@Composable
private fun BrowserEntryPreview(file: NasFile, modifier: Modifier) {
    val isVideo = MediaUtils.isVideo(file.name)
    val isImage = MediaUtils.isImage(file.name)
    val (badgeBg, badgeTint) = fileBadgeColors(file.name)
    Box(modifier.background(DarkInput), contentAlignment = Alignment.Center) {
        if (isVideo || isImage) {
            var thumbnailState by remember(file.path) { mutableStateOf<ThumbState?>(null) }
            WebDavCachedThumbnail(
                url = file.path,
                auth = WebDavManager.currentAuthState().authHeader,
                isVideo = isVideo,
                modifier = Modifier.fillMaxSize(),
                onStateChange = { thumbnailState = it },
            )
            if (isVideo && thumbnailState == ThumbState.SUCCESS) {
                Box(
                    Modifier.size(40.dp).background(DarkSurface.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(badgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            file.name.lowercase().endsWith(".pdf") -> Icons.Default.PictureAsPdf
                            file.name.lowercase().endsWith(".zip") || file.name.lowercase().endsWith(".rar") ->
                                Icons.Default.FolderZip
                            else -> Icons.Default.Description
                        },
                        null, tint = badgeTint, modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    file.name.substringAfterLast('.', "FILE").uppercase().take(8),
                    style = AppTypography.LabelMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
internal fun BrowserEntryRow(file: NasFile, selectionMode: Boolean, isSelected: Boolean) {
    val bg = if (isSelected) AccentCyan.copy(alpha = 0.22f) else DarkCard
    val borderColor = if (isSelected) AccentCyan else DarkCardBorder
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = BrowserDensity.RowHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { selected = isSelected },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                if (isSelected) "Đã chọn" else "Chưa chọn",
                tint = if (isSelected) AccentCyan else TextTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
        if (file.isDirectory) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(FolderBadgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Folder, null, tint = FolderIconTint, modifier = Modifier.size(26.dp))
            }
        } else {
            val isVideo = MediaUtils.isVideo(file.name)
            val isImage = MediaUtils.isImage(file.name)
            if (isVideo || isImage) {
                BrowserEntryPreview(file, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                val (badgeBg, badgeTint) = fileBadgeColors(file.name)
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(badgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Description, null, tint = badgeTint, modifier = Modifier.size(24.dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                file.name,
                style = AppTypography.BodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (file.isDirectory) folderSubtitle(file) else FormatUtils.formatBytes(file.contentLength),
                style = AppTypography.BodySmall.copy(fontSize = 12.sp),
                color = TextSecondary,
            )
        }
        if (file.isDirectory) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun BrowserConnectionLabel(status: ConnectionStatus) {
    val online = status is ConnectionStatus.Connected || status is ConnectionStatus.Authenticated ||
        status is ConnectionStatus.OnLan || status is ConnectionStatus.OnTailscale ||
        status is ConnectionStatus.SwitchedToLan || status is ConnectionStatus.SwitchedToTailscale
    val checking = status is ConnectionStatus.CheckingLan || status is ConnectionStatus.Connecting
    val failed = status is ConnectionStatus.NetworkError || status is ConnectionStatus.AuthFailed
    val color = when { online -> AccentGreen; checking -> AccentOrange; failed -> AccentRed; else -> TextTertiary }
    val label = when {
        status is ConnectionStatus.NetworkError -> "Lỗi kết nối"
        status is ConnectionStatus.AuthFailed -> "Lỗi xác thực"
        status is ConnectionStatus.Cancelled -> "Đã dừng kết nối"
        status is ConnectionStatus.OnTailscale || status is ConnectionStatus.SwitchedToTailscale -> "Tailscale"
        status is ConnectionStatus.OnLan || status is ConnectionStatus.SwitchedToLan -> "Mạng LAN"
        online -> "Đã kết nối"
        checking -> "Đang kết nối"
        else -> "Chưa kết nối"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clip(RoundedCornerShape(50.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, style = AppTypography.BodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = color)
    }
}

@Composable
internal fun BrowserFilterTab(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    activeTint: Color,
    onClick: () -> Unit,
) {
    val bg = if (selected) activeTint.copy(alpha = 0.22f) else DarkCard
    val borderColor = if (selected) activeTint else DarkCardBorder
    Row(
        modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50.dp)).clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .background(bg, RoundedCornerShape(50.dp))
            .border(1.dp, borderColor, RoundedCornerShape(50.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { this.selected = selected },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon LUÔN có màu phân loại — chip chưa chọn vẫn sống động, hết kiểu chữ xám chán đời
        Icon(icon, null, tint = activeTint, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = AppTypography.LabelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) TextPrimary else TextSecondary,
            maxLines = 1
        )
    }
}
