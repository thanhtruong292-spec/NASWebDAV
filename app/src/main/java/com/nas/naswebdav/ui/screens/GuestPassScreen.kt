@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.screens

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.dialogs.AppStatusDialog
import com.nas.naswebdav.ui.dialogs.DialogType
import com.nas.naswebdav.ui.dialogs.*

import android.content.Context

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import androidx.compose.runtime.collectAsState

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import coil.compose.AsyncImage
import com.nas.naswebdav.NasFile
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue

// ============ LOCAL GUEST PASS (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

private val GpDarkSurface   = Color.Black
private val GpDarkCard      = Color(0xFF0F0F0F)
private val GpAccentGreen   = Color(0xFF00E676)
private val GpAccentOrange  = Color(0xFFFF9100)
private val GpAccentRed     = Color(0xFFFF1744)
private val GpAccentCyan    = Color(0xFF00D2FF)
private val GpAccentPurple  = Color(0xFFBB86FC)
private val GpTextPrimary   = Color(0xFFE8E8E8)
private val GpTextSecondary = Color(0xFF8892B0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestPassScreen(viewModel: WebDavViewModel, onBack: () -> Unit) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var durationMinutes by remember { mutableIntStateOf(AppConfig.GUEST_PASS_DEFAULT_MINUTES) }
    var copiedField by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("Local Guest Pass", fontWeight = FontWeight.Bold, color = GpTextPrimary); Text("Cấp vé FTP tạm thời cho khách", fontSize = 11.sp, color = GpTextSecondary) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = GpTextPrimary) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = GpDarkSurface))
    }, containerColor = GpDarkSurface) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpAccentPurple.copy(alpha = 0.08f)), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = GpAccentPurple, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Text("NAS sẽ tự động tạo một tài khoản FTP tạm thời với quyền Chỉ đọc (Read-Only). Khách dùng FTP client (FileZilla, ES File Explorer...) để kết nối vào kho phim. Tài khoản tự xóa sau thời hạn.", fontSize = 12.sp, color = GpTextSecondary, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpDarkCard), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Thời hạn Guest Pass", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GpTextPrimary); Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30 to "30 phút", 60 to "1 giờ", 180 to "3 giờ", 1440 to "1 ngày").forEach { (min, label) ->
                            FilterChip(selected = durationMinutes == min, onClick = { durationMinutes = min }, label = { Text(label, fontSize = 11.sp) }, modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GpAccentCyan.copy(alpha = 0.2f), selectedLabelColor = GpAccentCyan, containerColor = GpDarkSurface, labelColor = GpTextSecondary))
                        }
                    }
                    Spacer(Modifier.height(8.dp)); Text("Thời hạn đã chọn: $durationMinutes phút (${durationMinutes / 60} giờ ${durationMinutes % 60} phút)", fontSize = 12.sp, color = GpAccentCyan)
                }
            }
            Spacer(Modifier.height(12.dp))
            val pass = viewModel.activeGuestPass
            AnimatedVisibility(visible = pass != null) {
                pass?.let { gp ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpDarkCard), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, GpAccentGreen.copy(alpha = 0.4f))) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = GpAccentGreen, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Guest Pass đang hoạt động", fontWeight = FontWeight.Bold, color = GpAccentGreen) }
                            Spacer(Modifier.height(14.dp))
                            GuestInfoRow("Host", gp.host, clipboardManager, copiedField, "host") { copiedField = "host" }; Spacer(Modifier.height(8.dp))
                            GuestInfoRow("Port FTP", gp.ftpPort.toString(), clipboardManager, copiedField, "port") { copiedField = "port" }; Spacer(Modifier.height(8.dp))
                            GuestInfoRow("Username", gp.username, clipboardManager, copiedField, "user") { copiedField = "user" }; Spacer(Modifier.height(8.dp))
                            GuestInfoRow("Password", gp.password, clipboardManager, copiedField, "pass") { copiedField = "pass" }; Spacer(Modifier.height(8.dp))
                            val expiresMs = gp.expiresAt - System.currentTimeMillis(); val expiresMin = (expiresMs / 60000).coerceAtLeast(0)
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Timer, null, tint = if (expiresMin < 10) GpAccentOrange else GpTextSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(if (expiresMin > 0) "Hết hạn sau $expiresMin phút" else "⚠️ Sắp hết hạn / Đã hết hạn", fontSize = 12.sp, color = if (expiresMin < 10) GpAccentOrange else GpTextSecondary) }
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { viewModel.revokeGuestPass() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !viewModel.isGuestPassLoading, colors = ButtonDefaults.buttonColors(containerColor = GpAccentRed.copy(alpha = 0.8f))) { Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Thu hồi ngay", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            viewModel.guestPassError?.let { err -> Spacer(Modifier.height(10.dp)); Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GpAccentRed.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Error, null, tint = GpAccentRed, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(err, color = GpAccentRed, fontSize = 12.sp) } } }
            Spacer(Modifier.height(14.dp))
            if (pass == null) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (!viewModel.isGuestPassLoading) Brush.horizontalGradient(listOf(GpAccentPurple, Color(0xFF6200EA))) else Brush.horizontalGradient(listOf(GpTextSecondary.copy(alpha=0.2f), GpTextSecondary.copy(alpha=0.2f)))).clickable(
                    enabled = !viewModel.isGuestPassLoading,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { viewModel.createGuestPass(durationMinutes) }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    if (viewModel.isGuestPassLoading) { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = GpTextPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Đang tạo tài khoản...", color = GpTextPrimary, fontWeight = FontWeight.Bold) } }
                    else { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PersonAdd, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text("Cấp Guest Pass ($durationMinutes phút)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) } }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GuestInfoRow(label: String, value: String, clipboardManager: androidx.compose.ui.platform.ClipboardManager, copiedField: String, fieldKey: String, onCopied: () -> Unit) {
    val isCopied = copiedField == fieldKey
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F3460).copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 10.sp, color = Color(0xFF8892B0), fontWeight = FontWeight.Bold); Text(value, fontSize = 14.sp, color = Color(0xFFE8E8E8), fontWeight = FontWeight.SemiBold) }
        IconButton(onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(value)); onCopied() }, modifier = Modifier.size(32.dp)) {
            Icon(if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy, null, tint = if (isCopied) Color(0xFF00E676) else Color(0xFF8892B0), modifier = Modifier.size(16.dp))
        }
    }
}
