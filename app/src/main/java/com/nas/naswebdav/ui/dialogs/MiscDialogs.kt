@file:Suppress("DEPRECATION")
package com.nas.naswebdav.ui.dialogs

import com.nas.naswebdav.*
import com.nas.naswebdav.ui.components.NasModalBottomSheet
import com.nas.naswebdav.ui.components.NasBottomSheetHandle
import com.nas.naswebdav.ui.components.NasGradientButton
import com.nas.naswebdav.ui.components.NasAlertDialog
import com.nas.naswebdav.ui.screens.*
import com.nas.naswebdav.ui.screens.WebDavCachedThumbnail
import okhttp3.MediaType.Companion.toMediaTypeOrNull

import android.content.Context
import kotlinx.coroutines.delay
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit

// ============ Misc dialogs: folder picker, status, biometric lock, notification, IP/LAN, create/delete (tách từ Dialogs.kt) ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    startingUrl: String,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    var currentUrl by remember { mutableStateOf(startingUrl) }
    var folderList by remember { mutableStateOf<List<NasFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(currentUrl) {
        isLoading = true
        try {
            val items = WebDavManager.listFiles(currentUrl)
            folderList = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            folderList = emptyList()
        }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Chọn thư mục đích", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val decoded = try { java.net.URLDecoder.decode(currentUrl, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { currentUrl }
                val relativePath = decoded.removePrefix(WebDavManager.currentBaseUrl)
                Text(
                    text = if (relativePath.isEmpty()) "/ (Thư mục gốc)" else relativePath,
                    fontSize = 12.sp, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (currentUrl.trimEnd('/') != WebDavManager.currentBaseUrl.trimEnd('/')) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val parentUrl = currentUrl.trimEnd('/').substringBeforeLast('/') + "/"
                                            currentUrl = if (parentUrl.length < WebDavManager.currentBaseUrl.length)
                                                WebDavManager.currentBaseUrl else parentUrl
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(16.dp))
                                    Text(".. (Quay lại)", fontWeight = FontWeight.Medium)
                                }
                                HorizontalDivider(color = TextSecondary.copy(alpha = 0.3f))
                            }
                        }

                        if (folderList.isEmpty()) {
                            item {
                                Text(
                                    text = "(Thư mục trống)",
                                    color = TextTertiary,
                                    modifier = Modifier.padding(16.dp).fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                                )
                            }
                        } else {
                            lazyItems(folderList) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentUrl = folder.path }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = "Thư mục", tint = AccentOrange)
                                    Spacer(Modifier.width(16.dp))
                                    Text(folder.name, fontWeight = FontWeight.Medium)
                                }
                                HorizontalDivider(color = TextSecondary.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            NasGradientButton(
                onClick = { onFolderSelected(currentUrl) },
                text = "Chép/Di chuyển vào đây",
                gradientColors = listOf(AccentGreen, AccentGreen.copy(alpha = 0.8f), AccentCyan),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy", color = TextTertiary)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}


// ════════════════════════════════════════════════════════════════════════════
// AppStatusDialog + DialogType enum (từ ui.components.AppStatusDialog)
// ════════════════════════════════════════════════════════════════════════════

enum class DialogType { SUCCESS, ERROR, WARNING, CONFIRM }

@Composable
fun AppStatusDialog(type: DialogType, message: String, onConfirm: (() -> Unit)? = null, onDismiss: () -> Unit) {
    if (message.isBlank()) return

    val (icon, color, title) = when (type) {
        DialogType.SUCCESS -> Triple(Icons.Default.Check, AccentGreen, "Thành công")
        DialogType.ERROR -> Triple(Icons.Default.Close, AccentRed, "Thất bại")
        DialogType.WARNING -> Triple(Icons.Default.Warning, AccentOrange, "Cảnh báo")
        DialogType.CONFIRM -> Triple(Icons.Default.HelpOutline, AccentBlue, "Xác nhận")
    }
    val isDark = isSystemInDarkTheme()
    val dialogBg = if (isDark) DarkElevated else TextPrimary
    val textColor = if (isDark) TextPrimary else TextSecondary
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.fillMaxWidth().background(dialogBg, shape = RoundedCornerShape(14.dp)).border(1.dp, TextPrimary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(18.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(56.dp).background(color.copy(0.1f), CircleShape).border(2.dp, color.copy(0.2f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = message, fontSize = 16.sp, color = textColor, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).height(44.dp)) { Text("Đóng", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    if (onConfirm != null) { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = AccentRed), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).height(44.dp)) { Text("Xác nhận", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp) } }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SharedComponents — BiometricLockScreen + NotificationDialog
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun BiometricLockScreen(activity: androidx.fragment.app.FragmentActivity, onAuthenticated: () -> Unit, onFallbackToLogin: () -> Unit) {
    val executor = remember { androidx.core.content.ContextCompat.getMainExecutor(activity) }
    val currentOnAuthenticated by rememberUpdatedState(onAuthenticated)
    val currentOnFallbackToLogin by rememberUpdatedState(onFallbackToLogin)
    var authError by remember { mutableStateOf("") }
    var failCount by remember { mutableStateOf(0) }
    var authInFlight by remember { mutableStateOf(false) }
    var activePrompt by remember { mutableStateOf<androidx.biometric.BiometricPrompt?>(null) }
    val clearActivePrompt = {
        authInFlight = false
        activePrompt = null
    }
    val authenticate = authenticate@{
        if (authInFlight) return@authenticate
        authInFlight = true
        try {
            val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Khóa bảo mật NAS").setSubtitle("Vui lòng xác thực vân tay/khuôn mặt để truy cập dữ liệu")
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
            val biometricPrompt = androidx.biometric.BiometricPrompt(activity, executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { super.onAuthenticationSucceeded(result); clearActivePrompt(); failCount = 0; currentOnAuthenticated() }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        clearActivePrompt()
                        if (errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED || errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) currentOnFallbackToLogin()
                        else { failCount++; authError = "Lỗi: $errString (Sai $failCount/3 lần)"; if (failCount >= 3) currentOnFallbackToLogin() }
                    }
                    override fun onAuthenticationFailed() { super.onAuthenticationFailed(); failCount++; authError = "Vân tay không khớp! (Sai $failCount/3 lần)"; if (failCount >= 3) { clearActivePrompt(); currentOnFallbackToLogin() } }
                })
            activePrompt = biometricPrompt
            biometricPrompt.authenticate(promptInfo)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            clearActivePrompt()
            authError = "Lỗi: ${e.message ?: "Không mở được quét vân tay"}"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activePrompt?.cancelAuthentication()
            activePrompt = null
            authInFlight = false
        }
    }
    var hasStartedAuth by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!hasStartedAuth) { hasStartedAuth = true; authenticate() } }
    Box(modifier = Modifier.fillMaxSize().background(DarkSurface).pointerInput(Unit) { detectTapGestures { authenticate() } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = authenticate, modifier = Modifier.size(140.dp)) {
                Icon(Icons.Default.Fingerprint, contentDescription = "Quét vân tay để mở khóa", modifier = Modifier.size(120.dp), tint = AccentCyan)
            }
            if (authError.isNotEmpty()) { Spacer(Modifier.height(16.dp)); Text(authError, color = AccentRed, fontSize = 14.sp) }
        }
    }
}

@Composable
fun NotificationDialog(title: String, message: String, icon: ImageVector, iconColor: Color, onDismiss: () -> Unit) {
    LaunchedEffect(key1 = title, key2 = message) { kotlinx.coroutines.delay(3000); onDismiss() }
    val type = when {
        title.contains("Lỗi", true) || title.contains("Thất bại", true) -> DialogType.ERROR
        title.contains("Cảnh báo", true) -> DialogType.WARNING
        else -> DialogType.SUCCESS
    }
    AppStatusDialog(
        type = type,
        message = message,
        onDismiss = onDismiss
    )
}

// ════════════════════════════════════════════════════════════════════════════
// IpApprovalDialog — Cảnh báo bảo mật IP lạ
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun IpApprovalDialog(onDismiss: () -> Unit) {
    val deviceVM = LocalDeviceManagementVM.current
    val ip = deviceVM.pendingIpAddress; val message = deviceVM.approvalMessage; val countryCode = deviceVM.pendingCountryCode
    val infiniteTransition = rememberInfiniteTransition(label = "shield_pulse")
    val pulseScale by infiniteTransition.animateFloat(1f, 1.15f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(0.7f, 1f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "alpha")
    val isLocal = ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
    val riskColor = if (isLocal) AccentOrange else AccentRed
    val riskLabel = if (isLocal) "Mạng nội bộ" else "IP ngoài ($countryCode)"
    AlertDialog(onDismissRequest = {}, containerColor = DarkElevated, shape = RoundedCornerShape(24.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(64.dp).scale(pulseScale).clip(CircleShape).background(Brush.radialGradient(listOf(riskColor.copy(alpha = pulseAlpha * 0.3f), riskColor.copy(alpha = 0.05f)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, null, tint = riskColor.copy(alpha = pulseAlpha), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("⚠️ CẢNH BÁO BẢO MẬT", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = riskColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Phát hiện thiết bị lạ kết nối", fontSize = 13.sp, color = TextTertiary, textAlign = TextAlign.Center)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(colors = CardDefaults.cardColors(containerColor = DarkCard), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ĐỊA CHỈ IP", fontSize = 10.sp, color = TextTertiary, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(ip.ifEmpty { "Không xác định" }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Surface(color = riskColor.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) { Text(riskLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = riskColor) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (message.isNotBlank()) { Text(message, fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 18.sp); Spacer(Modifier.height(12.dp)) }
                Card(colors = CardDefaults.cardColors(containerColor = DarkElevated.copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Cho phép: Thêm vào whitelist, cho truy cập NAS", fontSize = 11.sp, color = TextSecondary) }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = AccentRed, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Chặn: Ban IP vĩnh viễn bằng iptables", fontSize = 11.sp, color = TextSecondary) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { deviceVM.approveDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(AccentCyan, AccentCyan, AccentGreen)), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = TextPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cho phép", color = TextPrimary, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            Button(onClick = { deviceVM.denyDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(AccentRed, AccentRed)), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = TextPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Chặn IP", color = TextPrimary, fontWeight = FontWeight.Bold) }
            }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
// LanWhitelistDialog — Quản lý danh sách IP/Subnet được truy cập nội bộ
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LanWhitelistDialog(
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val ipList = deviceVM.lanWhitelistIps
    val subnetList = deviceVM.lanWhitelistSubnets
    val isLoading = deviceVM.lanWhitelistLoading
    val errorMessage = deviceVM.lanWhitelistError
    val statusMessage = deviceVM.lanWhitelistStatus

    var newEntry by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { deviceVM.loadLanWhitelist() }

    NasModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).heightIn(max = 600.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Icon(Icons.Default.Wifi, null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("LAN Whitelist", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("IP truy cập không cần Tailscale", color = TextTertiary, fontSize = 11.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it },
                    placeholder = "192.168.1.0/24",
                    accentColor = AccentGreen,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newEntry.isNotBlank()) {
                            deviceVM.addLanWhitelistEntry(newEntry.trim())
                            newEntry = ""
                        }
                    },
                    modifier = Modifier.size(40.dp).background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) { Icon(Icons.Default.Add, null, tint = AccentGreen, modifier = Modifier.size(18.dp)) }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(statusMessage, fontSize = 12.sp, color = if (statusMessage.startsWith("✅")) AccentGreen else if (statusMessage.startsWith("❌")) AccentRed else TextTertiary)
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentGreen, modifier = Modifier.size(28.dp))
                }
            } else if (errorMessage.isNotBlank()) {
                Text(errorMessage, color = AccentRed, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            } else if (subnetList.isEmpty() && ipList.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                    Text("Chưa có IP/subnet nào. Thêm để cho phép truy cập LAN.", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            } else {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (subnetList.isNotEmpty()) {
                        Text("SUBNET", fontSize = 11.sp, color = TextTertiary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        subnetList.forEach { subnet ->
                            key("subnet-$subnet") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { deviceVM.removeLanWhitelistEntry(subnet, true) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(DarkSurface, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Hub, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(subnet, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                    if (ipList.isNotEmpty()) {
                        if (subnetList.isNotEmpty()) Spacer(Modifier.height(12.dp))
                        Text("IP", fontSize = 11.sp, color = TextTertiary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        ipList.forEach { ip ->
                            key("ip-$ip") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { deviceVM.removeLanWhitelistEntry(ip, false) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(DarkSurface, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Computer, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(ip, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ĐÓNG", color = AccentGreen, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// (Đã xoá SmartSyncDialog theo yêu cầu)
@Composable
fun OrganizeLegacyDialog(onDismiss: () -> Unit) {
    val smartVM = LocalSmartToolsVM.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!smartVM.organizingLegacyRunning) onDismiss() },
        title = { Text("Phân loại video cũ") },
        text = {
            Column {
                if (smartVM.organizingLegacyRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), color = AccentCyan, trackColor = Color.Transparent)
                    Text("Đang ra lệnh cho NAS dọn dẹp nội bộ...")
                } else if (smartVM.organizingLegacyResult != null) {
                    Text(smartVM.organizingLegacyResult!!)
                } else {
                    Text("Bạn có chắc chắn muốn NAS quét và di chuyển toàn bộ video không phải MP4 (như mpg, flv, mkv, avi...) vào thư mục 'Other Video' không? Thao tác này giúp danh sách video gọn hơn và được xử lý trực tiếp trên NAS.")
                }
            }
        },
        containerColor = DarkCard,
        confirmButton = {
            if (!smartVM.organizingLegacyRunning && smartVM.organizingLegacyResult == null) {
                TextButton(onClick = { smartVM.organizeLegacyVideos() }) { Text("Chạy NAS") }
            } else if (smartVM.organizingLegacyResult != null) {
                TextButton(onClick = { smartVM.resetOrganizingLegacy(); onDismiss() }) { Text("Đóng") }
            }
        },
        dismissButton = {
            if (!smartVM.organizingLegacyRunning && smartVM.organizingLegacyResult == null) {
                TextButton(onClick = onDismiss) { Text("Hủy") }
            }
        }
    )
}

// ====================================================================
// DIALOG TẠO THƯ MỤC MỚI
// ====================================================================
@Composable
fun CreateFolderDialog(
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
        dismissText = "Hủy",
        onConfirm = { onConfirm(folderName) },
    )
}

// ====================================================================
// DIALOG XÓA NHIỀU TỆP CÙNG LÚC
// ====================================================================
@Composable
fun MultiDeleteDialog(
    selectedCount: Int,
    isTrash: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppStatusDialog(
        type = DialogType.WARNING,
        message = if (isTrash) "Bạn có chắc chắn muốn xóa vĩnh viễn $selectedCount tệp này không? Hành động này không thể hoàn tác." else "Bạn có chắc chắn muốn đưa $selectedCount tệp này vào Thùng rác?",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}


// ============ Quy tắc cảnh báo (Rules engine #6) ============
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesSettingsDialog(onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(true) }
    var pause by remember { mutableStateOf(false) }
    var disk by remember { mutableStateOf("90") }
    var cpuTemp by remember { mutableStateOf("80") }
    var hddTemp by remember { mutableStateOf("55") }
    var ram by remember { mutableStateOf("96") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val p = NasApplication.instance.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
        enabled = p.getBoolean("alert_enabled", true)
        pause = p.getBoolean("alert_pause_disk_low", true)
        disk = p.getInt("alert_ram_threshold", 85).toString()
        cpuTemp = p.getInt("alert_cpu_threshold", 90).toString()
        loading = false
    }
    val numKb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NasModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Icon(Icons.Default.Tune, null, tint = AccentOrange, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Quy tắc cảnh báo", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 17.sp)
            }
            Text("NAS tự kiểm tra mỗi 60s và cảnh báo (Telegram + nhật ký) khi vượt ngưỡng.",
                fontSize = 12.sp, color = TextTertiary, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bật quy tắc", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !loading && !busy)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ổ đầy → tạm dừng ghi mới", fontSize = 14.sp, color = TextPrimary)
                    Text("Bản đang ghi vẫn tiếp tục", fontSize = 10.sp, color = TextTertiary)
                }
                Switch(checked = pause, onCheckedChange = { pause = it }, enabled = !loading && !busy)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = disk, onValueChange = { disk = it.filter(Char::isDigit) },
                label = { Text("Ngưỡng ổ cứng (%)") }, singleLine = true, keyboardOptions = numKb, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = cpuTemp, onValueChange = { cpuTemp = it.filter(Char::isDigit) },
                label = { Text("Ngưỡng nhiệt CPU (°C)") }, singleLine = true, keyboardOptions = numKb, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = hddTemp, onValueChange = { hddTemp = it.filter(Char::isDigit) },
                label = { Text("Ngưỡng nhiệt HDD (°C)") }, singleLine = true, keyboardOptions = numKb, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ram, onValueChange = { ram = it.filter(Char::isDigit) },
                label = { Text("Ngưỡng RAM (%)") }, singleLine = true, keyboardOptions = numKb, modifier = Modifier.fillMaxWidth())
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, fontSize = 12.sp, color = if (msg.contains("✓")) AccentGreen else AccentOrange)
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Hủy", color = TextTertiary) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !loading && !busy, onClick = {
                    busy = true; msg = "Đang lưu..."
                    val p = NasApplication.instance.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
                    p.edit {
                        putBoolean("alert_enabled", enabled)
                        putBoolean("alert_pause_disk_low", pause)
                        putBoolean("alert_pause_heat", false)
                        putInt("alert_cpu_threshold", cpuTemp.toIntOrNull() ?: 90)
                        putInt("alert_ram_threshold", ram.toIntOrNull() ?: 85)
                    }
                    busy = false; msg = "Đã lưu quy tắc cảnh báo ✓"
                    onDismiss()
                }) { Text("Lưu") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

