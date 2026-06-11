@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.session.MediaSession
import android.app.PictureInPictureParams
import android.util.Rational
import coil.compose.AsyncImage
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Màu dùng chung (nhân bản private từ MediaScreens.kt để giữ self-contained, không đổi giá trị) ──
private val DarkSurface = Color.Black
private val DarkCard    = Color.Black
private val AccentCyan  = Color(0xFF00D2FF)
private val TextPrimary = Color(0xFFE8E8E8)
private val TextSecondary = Color(0xFF8892B0)

// ════════════════════════════════════════════════════════════════════════════
// SmartOrganizerScreen.kt
// ════════════════════════════════════════════════════════════════════════════

// ── Bảng màu bổ sung cho SmartOrganizer ──
private val AccentGreen = Color(0xFF00E676)
private val AccentOrange = Color(0xFFFF9100)
private val AccentRed = Color(0xFFFF1744)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartOrganizerScreen(
    viewModel: WebDavViewModel,
    onBack: () -> Unit
) {
    // ── STATE ──
    val sourceUrl = viewModel.webDavManager.currentBaseUrl
    var selectedFilter by remember { mutableStateOf(OrganizerFilter.ALL) }
    val isScanning = viewModel.organizerScanning
    val isOrganizing = viewModel.organizerExecuting
    val scanResult = viewModel.organizerScanResult
    val totalFiles = viewModel.organizerTotalFiles
    val organizeResult = viewModel.organizerResult
    val errorMessage = viewModel.organizerError

    // ── Hàm quét ──
    val scanAndPreview: () -> Unit = {
        viewModel.smartOrganizeScan(selectedFilter)
    }

    // ── Hàm thực thi sắp xếp ──
    val startOrganize: () -> Unit = {
        viewModel.smartOrganizeExecute(selectedFilter)
    }

    // ── GIAO DIỆN ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Smart Organizer", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                        Text(
                            "Tự động phân loại tệp theo năm / tháng",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Quay lại", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkSurface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ THẺ THƯ MỤC NGUỒN ═══
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Thư mục nguồn", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))

                    val context = androidx.compose.ui.platform.LocalContext.current
                    // URL WebDAV
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0D1B2A))
                            .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clickable {
                                android.widget.Toast.makeText(context, "Sẽ sớm hỗ trợ chọn thư mục con!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFFFFCA28), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            sourceUrl.ifEmpty { "Chưa kết nối" },
                            fontSize = 14.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ═══ BỘ LỌC ═══
                    Text("Loại file", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data class FilterOpt(val filter: OrganizerFilter, val label: String)
                        val filters = listOf(
                            FilterOpt(OrganizerFilter.ALL, "Tất cả"),
                            FilterOpt(OrganizerFilter.IMAGE, "Chỉ ảnh"),
                            FilterOpt(OrganizerFilter.VIDEO, "Chỉ video")
                        )
                        filters.forEach { opt ->
                            FilterChip(
                                selected = selectedFilter == opt.filter,
                                onClick = { selectedFilter = opt.filter },
                                label = { Text(opt.label, fontSize = 13.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = AccentCyan
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selectedFilter == opt.filter,
                                    borderColor = TextSecondary.copy(alpha = 0.3f),
                                    selectedBorderColor = AccentCyan.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ═══ NÚT QUÉT ═══
                    Button(
                        onClick = { scanAndPreview() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !isScanning && !isOrganizing && sourceUrl.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCyan,
                            disabledContainerColor = AccentCyan.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = DarkSurface.copy(alpha = 0.8f),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Đang quét...", color = DarkSurface.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Search, null, tint = DarkSurface, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Quét & Xem trước", color = DarkSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ═══ PROGRESS BAR QUÉT ═══
            if (isScanning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Phân tích cấu trúc thư mục NAS...", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentCyan,
                            trackColor = DarkSurface
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ═══ HIỂN THỊ LỖI ═══
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = AccentRed, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(errorMessage!!, color = AccentRed, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ═══ HOÀN TẤT ═══
            if (organizeResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(organizeResult!!, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.organizerResult = null
                                viewModel.organizerScanResult = null
                                viewModel.organizerError = null
                            },
                            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sắp xếp thư mục khác", color = AccentCyan)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ═══ ĐANG SẮP XẾP ═══
            if (isOrganizing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Đang di chuyển tệp vào đúng thư mục...", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentCyan,
                            trackColor = DarkSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Quá trình này tùy thuộc vào số lượng và dung lượng tệp",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ═══ KẾT QUẢ QUÉT (Preview nhóm tháng) ═══
            if (scanResult != null && !isOrganizing && organizeResult == null) {
                val groups = scanResult!!

                // Header
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Bản xem trước — $totalFiles tệp",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    "${groups.size} nhóm",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            if (totalFiles > 0) {
                                Icon(Icons.Default.FolderSpecial, null, tint = AccentOrange, modifier = Modifier.size(28.dp))
                            }
                        }

                        if (totalFiles == 0) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "✅ Tất cả tệp đã được sắp xếp đúng thư mục!",
                                fontSize = 14.sp,
                                color = AccentGreen,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Danh sách nhóm
                groups.forEach { group ->
                    OrganizerGroupCard(group)
                    Spacer(Modifier.height(8.dp))
                }

                // Nút "Bắt đầu sắp xếp"
                if (totalFiles > 0) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { startOrganize() },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        enabled = !isScanning && !isOrganizing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            disabledContainerColor = AccentGreen.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.DriveFileMove, null, tint = DarkSurface, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Bắt đầu sắp xếp", color = DarkSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))

                    // Nút hủy
                    TextButton(
                        onClick = { viewModel.organizerScanResult = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Hủy tác vụ", color = TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Card hiển thị nhóm Năm/Tháng ──
@Composable
private fun OrganizerGroupCard(group: OrganizerGroup) {
    val monthNames = mapOf(
        "01" to "Tháng 1", "02" to "Tháng 2", "03" to "Tháng 3", "04" to "Tháng 4",
        "05" to "Tháng 5", "06" to "Tháng 6", "07" to "Tháng 7", "08" to "Tháng 8",
        "09" to "Tháng 9", "10" to "Tháng 10", "11" to "Tháng 11", "12" to "Tháng 12"
    )

    val parts = group.label.split("/")
    val year = parts.getOrElse(0) { "?" }
    val monthNum = parts.getOrElse(1) { "?" }
    val monthLabel = monthNames[monthNum] ?: "Tháng $monthNum"

    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon ngày tháng
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF667EEA), Color(0xFF764BA2)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(monthNum, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(year, fontSize = 8.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "$monthLabel $year",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${group.count} tệp · ${formatSize(group.size)}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    // Sample file names
                    if (!expanded && group.sampleFiles.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            group.sampleFiles.take(3).joinToString(", "),
                            fontSize = 10.sp,
                            color = TextSecondary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Badge count
                Surface(
                    color = AccentCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "${group.count}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 74.dp, end = 14.dp, bottom = 14.dp)) {
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), modifier = Modifier.padding(bottom = 8.dp))
                    group.sampleFiles.forEach { fileName ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.InsertDriveFile, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                fileName,
                                fontSize = 11.sp,
                                color = TextSecondary.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (group.count > group.sampleFiles.size) {
                        Text(
                            "... và ${group.count - group.sampleFiles.size} tệp khác",
                            fontSize = 11.sp,
                            color = AccentCyan,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = com.nas.naswebdav.utils.FormatUtils.formatBytes(bytes)
