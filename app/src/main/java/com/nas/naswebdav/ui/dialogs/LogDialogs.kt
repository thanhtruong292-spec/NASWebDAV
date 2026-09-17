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

data class LogGroup(
    val module: String,
    val count: Int,
    val lastType: String,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val logs: List<SystemLog>
)

fun groupConsecutiveLogs(logs: List<SystemLog>): List<LogGroup> {
    if (logs.isEmpty()) return emptyList()
    val groups = mutableListOf<LogGroup>()
    var currentLogs = mutableListOf(logs.first())
    for (i in 1 until logs.size) {
        val log = logs[i]
        if (log.module == currentLogs.last().module) {
            currentLogs.add(log)
        } else {
            groups.add(
                LogGroup(
                    module = currentLogs.first().module,
                    count = currentLogs.size,
                    lastType = currentLogs.last().type,
                    firstTimestamp = currentLogs.first().timestamp,
                    lastTimestamp = currentLogs.last().timestamp,
                    logs = currentLogs.toList()
                )
            )
            currentLogs = mutableListOf(log)
        }
    }
    groups.add(
        LogGroup(
            module = currentLogs.first().module,
            count = currentLogs.size,
            lastType = currentLogs.last().type,
            firstTimestamp = currentLogs.first().timestamp,
            lastTimestamp = currentLogs.last().timestamp,
            logs = currentLogs.toList()
        )
    )
    return groups
}

fun formatLogMessage(raw: String): String {
    if (raw.trim().startsWith("{")) {
        try {
            val j = org.json.JSONObject(raw)
            when (j.optString("event")) {
                "WEBDAV_SUCCESS" -> return "${j.optString("device")} (IP: ${j.optString("ip")} - MAC: ${j.optString("mac")}) đã kết nối NAS."
                "SSH_FAIL" -> return "Cảnh báo: IP ${j.optString("ip")} đang phản hồi sai mật khẩu SSH khi cố đăng nhập user: ${j.optString("user")}!"
                "SSH_SUCCESS" -> return "Đã đăng nhập SSH thành công từ IP ${j.optString("ip")} (Tài khoản: ${j.optString("user")}, Phương thức: ${j.optString("method")})."
                "CPU_TEMP_WARN" -> return "Nhiệt độ CPU hiện tại đang vượt ngưỡng an toàn! Vui lòng kiểm tra tản nhiệt."
                "SMART_WARN" -> return "Phát hiện lỗi phần cứng trên phân vùng ${j.optString("device")}: ${j.optString("error")}. Đề xuất sao lưu dữ liệu ngay lập tức!"
                else -> return raw
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return raw
        }
    }
    return raw
}

// ====================================================================
// DIALOG NHẬT KÝ HỆ THỐNG
// ====================================================================
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SystemLogDialog(
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Icon(Icons.Default.Assignment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nhật ký hệ thống", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        shareScope.launch {
                            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                CrashLogExporter.exportToFile(context, com.nas.naswebdav.NasApplication.instance.database)
                            }
                            if (path == null) {
                                Toast.makeText(context, "Không thể xuất log lỗi", Toast.LENGTH_SHORT).show()
                            } else {
                                val file = java.io.File(path)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    context.packageName + ".fileprovider",
                                    file
                                )
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "NAS WebDAV crash log")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = Intent.createChooser(sendIntent, "Chia sẻ log lỗi").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooser)
                            }
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Chia sẻ log lỗi", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                if (deviceVM.systemLogsList.isNotEmpty()) {
                    IconButton(onClick = { deviceVM.clearSystemLogs() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            CrashReportingSection()

            if (deviceVM.systemLogsList.isEmpty()) {
                Text("Chưa có dữ liệu nhật ký nào.", modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
            } else {
                val logGroups = groupConsecutiveLogs(deviceVM.systemLogsList)
                val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = logGroups, key = { "${it.module}_${it.firstTimestamp}" }) { group ->
                        val groupColor = when (group.lastType) {
                            "SUCCESS" -> MaterialTheme.colorScheme.tertiary
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARNING" -> AccentOrange
                            else -> MaterialTheme.colorScheme.primary
                        }
                        val groupIcon = when (group.lastType) {
                            "SUCCESS" -> Icons.Default.CheckCircle
                            "ERROR" -> Icons.Default.Error
                            "WARNING" -> Icons.Default.Warning
                            else -> Icons.Default.Info
                        }
                        val isExpanded = expandedGroups["${group.module}_${group.firstTimestamp}"] == true
                        val timeRange = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(group.firstTimestamp) +
                            if (group.count > 1) " – " + com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(group.lastTimestamp) else ""
                        val lastMsg = formatLogMessage(group.logs.last().message)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { expandedGroups["${group.module}_${group.firstTimestamp}"] = !isExpanded }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(groupIcon, null, tint = groupColor, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(group.module, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = groupColor)
                                                if (group.count > 1) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Surface(
                                                        color = groupColor.copy(alpha = 0.15f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Text(
                                                            "(x${group.count} thông báo)",
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                            fontSize = 9.sp,
                                                            color = groupColor,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            Text(timeRange, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(lastMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.85f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    if (group.count > 1) {
                                        Icon(
                                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = TextSecondary,
                                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                                        )
                                    }
                                }
                                if (isExpanded && group.count > 1) {
                                    HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                                    group.logs.reversed().forEach { log ->
                                        val subColor = when (log.type) {
                                            "SUCCESS" -> MaterialTheme.colorScheme.tertiary
                                            "ERROR" -> MaterialTheme.colorScheme.error
                                            "WARNING" -> AccentOrange
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                        val subIcon = when (log.type) {
                                            "SUCCESS" -> Icons.Default.CheckCircle
                                            "ERROR" -> Icons.Default.Error
                                            "WARNING" -> Icons.Default.Warning
                                            else -> Icons.Default.Info
                                        }
                                        val subTime = com.nas.naswebdav.utils.FormatUtils.formatShortDateTime(log.timestamp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(subIcon, null, tint = subColor, modifier = Modifier.size(12.dp).padding(top = 3.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Column {
                                                Text(subTime, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                                Text(formatLogMessage(log.message), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.75f))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ====================================================================
// DIALOG QUẢN LÝ DOCKER
// ====================================================================