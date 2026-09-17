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
fun DialogsAppStatusDialog(type: DialogType, message: String, onConfirm: (() -> Unit)? = null, onDismiss: () -> Unit) {
    AppStatusDialog(type, message, onConfirm, onDismiss)
}

// ════════════════════════════════════════════════════════════════════════════
// SharedComponents — BiometricLockScreen + NotificationDialog
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun DialogsBiometricLockScreen(activity: androidx.fragment.app.FragmentActivity, onAuthenticated: () -> Unit, onFallbackToLogin: () -> Unit) {
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
                Icon(Icons.Default.Fingerprint, contentDescription = "Quét vân tay để mở khóa", modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (authError.isNotEmpty()) { Spacer(Modifier.height(16.dp)); Text(authError, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        }
    }
}

@Composable
fun DialogsNotificationDialog(title: String, message: String, icon: ImageVector, iconColor: Color, onDismiss: () -> Unit) {
    LaunchedEffect(key1 = title, key2 = message) { kotlinx.coroutines.delay(3000); onDismiss() }
    val type = when {
        title.contains("Lỗi", true) || title.contains("Thất bại", true) -> DialogType.ERROR
        title.contains("Cảnh báo", true) -> DialogType.WARNING
        else -> DialogType.SUCCESS
    }
    DialogsAppStatusDialog(
        type = type,
        message = message,
        onDismiss = onDismiss
    )
}

// ════════════════════════════════════════════════════════════════════════════
// IpApprovalDialog — Cảnh báo bảo mật IP lạ
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun DialogsIpApprovalDialog(
    onDismiss: () -> Unit
) {
    // pendingIpAddress/approvalMessage/pendingCountryCode → DeviceMgmtVM
    val deviceVM = LocalDeviceManagementVM.current
    val ip = deviceVM.pendingIpAddress; val message = deviceVM.approvalMessage; val countryCode = deviceVM.pendingCountryCode
    val infiniteTransition = rememberInfiniteTransition(label = "shield_pulse")
    val pulseScale by infiniteTransition.animateFloat(1f, 1.15f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(0.7f, 1f, infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse), label = "alpha")
    val isLocal = ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
    val riskColor = if (isLocal) AccentOrange else MaterialTheme.colorScheme.error
    val riskLabel = if (isLocal) "Mạng nội bộ" else "IP ngoài ($countryCode)"
    AlertDialog(onDismissRequest = {}, containerColor = DarkCardHover, shape = RoundedCornerShape(24.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(64.dp).scale(pulseScale).clip(CircleShape).background(Brush.radialGradient(listOf(riskColor.copy(alpha = pulseAlpha * 0.3f), riskColor.copy(alpha = 0.05f)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, null, tint = riskColor.copy(alpha = pulseAlpha), modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("⚠️ CẢNH BÁO BẢO MẬT", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = riskColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Phát hiện thiết bị lạ kết nối", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(colors = CardDefaults.cardColors(containerColor = DarkCard), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ĐỊA CHỈ IP", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(ip.ifEmpty { "Không xác định" }, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Surface(color = riskColor.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) { Text(riskLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = riskColor) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (message.isNotBlank()) { Text(message, fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 18.sp); Spacer(Modifier.height(12.dp)) }
                Card(colors = CardDefaults.cardColors(containerColor = DarkElevated.copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Cho phép: Thêm vào whitelist, cho truy cập NAS", fontSize = 11.sp, color = TextSecondary) }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Chặn: Ban IP vĩnh viễn bằng iptables", fontSize = 11.sp, color = TextSecondary) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { deviceVM.approveDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary, AccentGreen)), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cho phép", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            Button(onClick = { deviceVM.denyDeviceIp(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
                modifier = Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.error, AccentRed)), RoundedCornerShape(24.dp))) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Chặn IP", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
// LanWhitelistDialog — Quản lý danh sách IP/Subnet được truy cập nội bộ
// ════════════════════════════════════════════════════════════════════════════

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogsLanWhitelistDialog(
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
                Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.minimumInteractiveComponentSize())
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("LAN Whitelist", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("IP truy cập không cần Tailscale", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.nas.naswebdav.ui.components.CompactTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it },
                    placeholder = "192.168.1.0/24",
                    accentColor = MaterialTheme.colorScheme.tertiary,
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
                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp)) }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(statusMessage, fontSize = 12.sp, color = if (statusMessage.startsWith("✅")) MaterialTheme.colorScheme.tertiary else if (statusMessage.startsWith("❌")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    NasLoadingSpinner(size = 24.dp, color = MaterialTheme.colorScheme.tertiary, strokeWidth = 3.dp)
                }
            } else if (errorMessage.isNotBlank()) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            } else if (subnetList.isEmpty() && ipList.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                    Text("Chưa có IP/subnet nào. Thêm để cho phép truy cập LAN.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            } else {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (subnetList.isNotEmpty()) {
                        Text("SUBNET", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        subnetList.forEach { subnet ->
                            key("subnet-$subnet") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { deviceVM.removeLanWhitelistEntry(subnet, true) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(DarkCardHover, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(subnet, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                    if (ipList.isNotEmpty()) {
                        if (subnetList.isNotEmpty()) Spacer(Modifier.height(12.dp))
                        Text("IP", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        ipList.forEach { ip ->
                            key("ip-$ip") {
                                com.nas.naswebdav.ui.components.SwipeDeleteRow(
                                    onDelete = { deviceVM.removeLanWhitelistEntry(ip, false) },
                                    shape = RoundedCornerShape(6.dp),
                                    backgroundPaddingHorizontal = 8.dp,
                                    iconSize = 18.dp
                                ) {
                                    Row(Modifier.fillMaxWidth().background(DarkCardHover, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(ip, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ĐÓNG", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// (Đã xoá SmartSyncDialog theo yêu cầu)

// ====================================================================
// DIALOG CAU HINH KHOA SINH TRAC HOC
// Truoc day chi co toggle on/off + delay hardcode 60s -> user thay nhu
// khong tac dung. Dialog moi:
//   - Check biometric availability (BiometricManager.canAuthenticate)
//   - Toggle enable + delay picker (0s instant / 5s / 30s / 1m / 5m)
//   - Nut "Khoa ngay" de test khong can doi
// ====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsBiometricSettingsDialog(
    prefsRepo: com.nas.naswebdav.utils.PreferencesRepository,
    onDismiss: () -> Unit
) {
    val deviceVM = LocalDeviceManagementVM.current
    val autoBackupVM = LocalAutoBackupVM.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(prefsRepo.isBiometricEnabled()) }
    var delaySec by remember { mutableStateOf(prefsRepo.getBiometricLockDelaySec()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Check biometric availability
    val bioStatus = remember {
        try {
            val bm = androidx.biometric.BiometricManager.from(context)
            val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            when (bm.canAuthenticate(auth)) {
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "available"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hw_unavailable"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
                else -> "unknown"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "error: ${e.message}" }
    }

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
                Icon(Icons.Default.Lock, null, tint = AccentPurple, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Khóa Sinh trắc học", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            // Availability badge
            val (bioColor, bioText) = when (bioStatus) {
                "available" -> MaterialTheme.colorScheme.tertiary to "Sinh trắc học sẵn sàng (vân tay/khuôn mặt đã đăng ký)"
                "no_hardware" -> MaterialTheme.colorScheme.error to "Thiết bị không hỗ trợ sinh trắc"
                "hw_unavailable" -> MaterialTheme.colorScheme.error to "Phần cứng sinh trắc tạm thời không khả dụng"
                "none_enrolled" -> MaterialTheme.colorScheme.error to "Chưa đăng ký vân tay/khuôn mặt nào. Vào Cài đặt → Sinh trắc để thêm."
                else -> MaterialTheme.colorScheme.onSurfaceVariant to "Trạng thái: $bioStatus"
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(bioColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .border(1.dp, bioColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (bioStatus) {
                        "available" -> Icons.Default.CheckCircle
                        else -> Icons.Default.Warning
                    },
                    null, tint = bioColor, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(bioText, color = bioColor, fontSize = 12.sp, lineHeight = 15.sp)
            }

            // Enable toggle
            Spacer(Modifier.height(5.dp))
            HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(
                    enabled = bioStatus == "available",
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    enabled = !enabled
                }
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = bioStatus == "available",
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentPurple, checkedTrackColor = AccentPurple.copy(alpha = 0.3f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bật khoá sinh trắc", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Khoá khi app vào nền theo thời gian dưới"
                        else "Tắt — app không bao giờ tự khoá",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                    )
                }
            }

            // Delay picker
            Spacer(Modifier.height(5.dp))
            Text("THỜI GIAN CHỜ KHOÁ (sau khi app vào nền)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            val delayOptions = listOf(
                0 to "Khoá NGAY",
                5 to "5 giây",
                30 to "30 giây",
                60 to "1 phút",
                300 to "5 phút",
                600 to "10 phút",
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                delayOptions.forEach { (sec, label) ->
                    val isSel = delaySec == sec
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .height(44.dp)
                            .background(
                                if (isSel) AccentPurple.copy(alpha = 0.15f) else DarkCardHover,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { delaySec = sec }
                            .padding(horizontal = 8.dp, vertical = 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = { delaySec = sec },
                            enabled = enabled,
                            modifier = Modifier.scale(0.7f),
                            colors = RadioButtonDefaults.colors(selectedColor = AccentPurple)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            label,
                            color = when {
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                isSel -> AccentPurple
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NasGradientButton(
                    onClick = {
                        prefsRepo.setBiometricEnabled(enabled)
                        prefsRepo.setBiometricLockDelaySec(delaySec)
                        deviceVM.logUserAction("Security","cập nhật khóa sinh trắc (${if (enabled) "bật" else "tắt"}, trễ ${delaySec}s).")
                        onDismiss()
                    },
                    text = "LƯU",
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    shape = RoundedCornerShape(10.dp),
                    gradientColors = listOf(AccentPurple, AccentPurple.copy(alpha = 0.8f), AccentCyan),
                )
                OutlinedButton(
                    onClick = {
                        // Save first, then trigger lock
                        prefsRepo.setBiometricEnabled(true)
                        prefsRepo.setBiometricLockDelaySec(delaySec)
                        deviceVM.logUserAction("Security","Kích hoạt khoá sinh trắc học cục bộ.")
                        autoBackupVM.lockNowRequested = true
                        onDismiss()
                    },
                    enabled = bioStatus == "available",
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
                ) { Text("KHOÁ NGAY", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Lưu ý: \"Khoá NGAY\" trong delay = không có buffer khi switch app/đọc thông báo. Đề xuất 5-30 giây.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
