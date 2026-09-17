@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.R
import com.nas.naswebdav.ui.components.NasModalBottomSheet
import com.nas.naswebdav.ui.components.NasBottomSheetHandle
import com.nas.naswebdav.ui.components.NasGradientButton
import com.nas.naswebdav.ui.components.NasAlertDialog
import com.nas.naswebdav.ui.components.NasLoadingSpinner
import com.nas.naswebdav.ui.screens.WebDavCachedThumbnail
import com.nas.naswebdav.ui.screens.AccentOrange
import com.nas.naswebdav.ui.screens.AccentRed
import com.nas.naswebdav.ui.screens.AccentGreen
import com.nas.naswebdav.ui.screens.AccentBlue
import com.nas.naswebdav.ui.screens.AccentCyan
import com.nas.naswebdav.ui.screens.AccentPurple
import com.nas.naswebdav.ui.screens.AccentPink
import com.nas.naswebdav.ui.screens.DarkCard
import com.nas.naswebdav.ui.screens.DarkCardHover
import com.nas.naswebdav.ui.screens.DarkSurface
import com.nas.naswebdav.ui.screens.DarkElevated
import com.nas.naswebdav.ui.screens.TextPrimary
import com.nas.naswebdav.ui.screens.TextSecondary
import com.nas.naswebdav.ui.screens.TextTertiary

/**
 * Dialogs.kt — Phase 7c.3 file-level provenance.
 *
 * All 30+ dialog composables in this file read state via `viewModel.xxx` which
 * delegates to the appropriate Domain VM (Phase 7a):
 *   - AutoBackupDialog / UsbImportDialog / SleepScheduleDialog → AutoBackupVM
 *   - DuplicateConfigDialog / FilesDialog / OrganizeLegacyDialog / ConfigDialog → SmartToolsVM
 *   - DiskHealthDialog / NasInsightsDialog / FilePropertiesDialog → SystemMonitorVM
 *   - LanWhitelistDialog / DockerDialog / SmartDiskDialog / BandwidthDialog → DeviceMgmtVM
 *   - LivestreamRecordDialog → LivestreamVM
 *   - SystemLogDialog → DeviceMgmtVM (systemLogsList)
 *
 * Direct migration to LocalXxxVM.current will happen in the Group 3 cleanup pass.
 * For now, additive annotation only — minimal blast radius for the largest dialog file.
 */

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import com.nas.naswebdav.utils.CrashLogExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit


/**
 * Tất cả Dialog composable dùng trong MainMenuScreen.
 * Tách riêng để giảm complexity và tăng readable.
 */

// ====================================================================
// DIALOG XÁC NHẬN REBOOT
// ====================================================================

// Task 8: tach tu Dialogs.kt — khong doi logic.

@Composable
fun DialogsCreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    NasAlertDialog(
        onDismissRequest = onDismiss,
        title = "Thư mục mới",
        content = {
            com.nas.naswebdav.ui.components.CompactTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = "Nhập tên thư mục",
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmText = "Tạo",
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { onConfirm(folderName) },
    )
}

// ====================================================================
// DIALOG XÓA NHIỀU TỆP CÙNG LÚC
// ====================================================================
@Composable
fun DialogsMultiDeleteDialog(
    selectedCount: Int,
    isTrash: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    DialogsAppStatusDialog(
        type = DialogType.WARNING,
        message = if (isTrash) "Bạn có chắc chắn muốn xóa vĩnh viễn $selectedCount tệp này không? Hành động này không thể hoàn tác." else "Bạn có chắc chắn muốn đưa $selectedCount tệp này vào Thùng rác?",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

// ════════════════════════════════════════════════════════════════════════════
// DialogsLivestreamRecordDialog — Ghi hinh Livestream TikTok / Facebook / YouTube
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogsFilePropertiesDialog(
    file: com.nas.naswebdav.NasFile,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Tinh toan cac field hien thi
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val mime = remember(file.name, file.isDirectory) {
        if (file.isDirectory) "Thư mục" else {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: file.contentType ?: "application/octet-stream"
        }
    }
    val sizeFormatted = remember(file.contentLength) {
        if (file.isDirectory) "—" else com.nas.naswebdav.utils.FormatUtils.formatBytes(file.contentLength)
    }
    val sizeRaw = if (file.isDirectory) "" else " (${"%,d".format(file.contentLength)} bytes)"
    val modifiedStr = remember(file.lastModified) {
        if (file.lastModified <= 0L) "—" else {
            try {
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(file.lastModified))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "—" }
        }
    }

    // Hash MD5/aHash tu DB fingerprint (neu da scan duplicate truoc do).
    // Dung LaunchedEffect tra cuu o background, KHONG block UI.
    var fingerprintHash by remember(file.path) { mutableStateOf<String?>(null) }
    var fingerprintLoading by remember(file.path) { mutableStateOf(true) }
    LaunchedEffect(file.path) {
        if (file.isDirectory) {
            fingerprintLoading = false
            return@LaunchedEffect
        }
        try {
            val db = com.nas.naswebdav.NasApplication.instance.database
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // FingerprintDao chi co findByExactHash(hash) — query full table de tim filePath khong toi uu.
                // Thay vao do dung FileDao.partialHash / fullHash (CachedFile co san).
                db.fileDao().getFileByPath(file.path)
            }
            fingerprintHash = result?.fullHash ?: result?.partialHash ?: result?.imageFingerprint
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            fingerprintHash = null
        } finally {
            fingerprintLoading = false
        }
    }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = if (file.isDirectory) AccentOrange else AccentGreen,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thuộc tính",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))

            // Cac dong field — label trai, value phai, value selectable de copy
            DialogsPropertyRow("Tên", file.name, selectable = true)
            DialogsPropertyRow("Đường dẫn", file.path, selectable = true, monospace = true)
            DialogsPropertyRow("Loại", mime)
            if (!file.isDirectory) {
                DialogsPropertyRow("Phần mở rộng", if (ext.isEmpty()) "—" else ".$ext")
                DialogsPropertyRow("Kích thước", "$sizeFormatted$sizeRaw")
            }
            DialogsPropertyRow("Sửa lần cuối", modifiedStr)
            val hashDisplay = when {
                file.isDirectory -> "—"
                fingerprintLoading -> "Đang tra cứu..."
                fingerprintHash.isNullOrEmpty() -> "— (chưa quét fingerprint)"
                else -> fingerprintHash!!
            }
            DialogsPropertyRow("Hash", hashDisplay, selectable = true, monospace = true)

            Spacer(Modifier.height(8.dp))

            // Nut dong
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Button(
                onClick = onDismiss,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary, AccentGreen)
                        ),
                        shape = RoundedCornerShape(23.dp)
                    )
            ) {
                Text("Đóng", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
