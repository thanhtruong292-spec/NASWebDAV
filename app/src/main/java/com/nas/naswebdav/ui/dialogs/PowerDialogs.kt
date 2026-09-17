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
fun RebootConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppStatusDialog(
        type = DialogType.WARNING,
        message = stringResource(R.string.reboot_confirm_message),
        onConfirm = { onDismiss(); onConfirm() },
        onDismiss = onDismiss
    )
}

// ====================================================================
// DIALOG XÁC NHẬN NGỦ NAS
// ====================================================================
@Composable
fun ShutdownConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppStatusDialog(
        type = DialogType.WARNING,
        message = stringResource(R.string.shutdown_confirm_message),
        onConfirm = { onDismiss(); onConfirm() },
        onDismiss = onDismiss
    )
}

// ====================================================================
// DIALOG TẢI XUỐNG TỪ XA
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDialog(
    downloadLink: String,
    onLinkChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onPickTorrentFile: () -> Unit = {}
) {
    var tabIndex by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dl_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0 to ("🔗" to stringResource(R.string.dl_link_tab)),
                    1 to ("📁" to stringResource(R.string.dl_torrent_tab)),
                ).forEach { (idx, pair) ->
                    val (emoji, label) = pair
                    val selected = tabIndex == idx
                    FilterChip(
                        selected = selected,
                        onClick = { tabIndex = idx },
                        label = { Text("$emoji $label", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).height(34.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when (tabIndex) {
                0 -> {
                    Text(stringResource(R.string.dl_link_description), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    com.nas.naswebdav.ui.components.CompactTextField(
                        value = downloadLink,
                        onValueChange = onLinkChange,
                        placeholder = stringResource(R.string.dl_link_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    com.nas.naswebdav.ui.components.NasGradientButton(
                        onClick = onConfirm,
                        text = stringResource(R.string.dl_add_queue),
                        enabled = downloadLink.isNotBlank(),
                        height = 40.dp,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                1 -> {
                    Text(stringResource(R.string.dl_pick_torrent_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onPickTorrentFile() },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dl_pick_torrent_button), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        stringResource(R.string.dl_pick_torrent_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { Text(stringResource(R.string.dl_cancel), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ====================================================================
// DIALOG WAKE-ON-LAN
// ====================================================================
@Composable
fun WolDialog(
    macAddress: String,
    onMacChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    NasAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.wol_title),
        content = {
            Text(stringResource(R.string.wol_description), fontSize = 13.sp, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            com.nas.naswebdav.ui.components.CompactTextField(
                value = macAddress,
                onValueChange = onMacChange,
                placeholder = stringResource(R.string.wol_mac_placeholder),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmText = stringResource(R.string.wol_confirm),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
    )
}

// ====================================================================
// DIALOG S.M.A.R.T VÀ TEST TỐC ĐỘ Ổ CỨNG
// ====================================================================