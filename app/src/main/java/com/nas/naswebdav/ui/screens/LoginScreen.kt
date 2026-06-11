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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import coil.compose.AsyncImage
import com.nas.naswebdav.NasFile
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue

// ============ LoginScreen (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

private const val URL_PREFIX = "http://"
private const val URL_SUFFIX = ":8822/webdav/"

private fun ipToFullUrl(ip: String): String {
    val trimmed = ip.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return if (trimmed.contains(":")) "${URL_PREFIX}$trimmed/webdav/" else "${URL_PREFIX}$trimmed${URL_SUFFIX}"
}

private fun fullUrlToIp(url: String): String = try { java.net.URL(url).host } catch (_: Exception) { url }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: WebDavViewModel, onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val rawHistory = remember { SecurePrefsHelper.getUrlList(context) }
    var historyIps by remember { mutableStateOf(rawHistory.map { fullUrlToIp(it) }.distinct().filter { it.isNotEmpty() }) }
    var ipInput by remember { mutableStateOf(historyIps.firstOrNull() ?: "") }
    var user by remember { mutableStateOf(SecurePrefsHelper.getUser(context).ifEmpty { "admin" }) }
    var pass by remember { mutableStateOf(SecurePrefsHelper.getPass(context)) }
    var expanded by remember { mutableStateOf(false) }

    // State cho 2 nút khẩn cấp (WoL + Restart) hiện trên login screen — dùng khi
    // NAS bị lỗi không đăng nhập được.
    val sharedPrefs = remember { context.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE) }
    var macAddress by remember { mutableStateOf(sharedPrefs.getString("mac_address", "") ?: "") }
    var showWolDialog by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var emergencyMsg by remember { mutableStateOf("") }
    var emergencyIsError by remember { mutableStateOf(false) }

    // Trạng thái ping real-time cho các IP: URL → RTT (ms), -1 = unreachable
    var ipPingStatus by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var isCheckingPings by remember { mutableStateOf(false) }

    // Khởi động vòng lặp ping thực tế khi LoginScreen hiển thị
    LaunchedEffect(Unit) {
        while (isActive) {
            isCheckingPings = true
            try {
                val fullUrls = (historyIps + ipInput).distinct().filter { it.isNotBlank() }.map { ipToFullUrl(it) }
                if (fullUrls.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) {
                    val results = viewModel.pingUrlsForDisplay(fullUrls, user, pass)
                    ipPingStatus = results
                }
            } catch (_: Exception) {}
            isCheckingPings = false
            kotlinx.coroutines.delay(2000)
        }
    }

    LaunchedEffect(ipPingStatus) {
        val bestUrl = ipPingStatus
            .filterValues { it > 0L }
            .minByOrNull { it.value }
            ?.key
        if (!bestUrl.isNullOrBlank()) {
            ipInput = fullUrlToIp(bestUrl)
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Storage, contentDescription = "NAS", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Kết nối NAS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(value = ipInput, onValueChange = { ipInput = it }, label = { Text("Địa chỉ IP / DDNS của NAS") }, modifier = Modifier.fillMaxWidth().menuAnchor(), singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) })
            if (historyIps.isNotEmpty()) {
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    historyIps.forEach { ipOption ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    // Hiển thị chỉ báo ping: ● xanh = OK, ● đỏ = fail, ● xám = checking
                                    val fullUrl = ipToFullUrl(ipOption)
                                    val rtt = ipPingStatus[fullUrl] ?: -2L
                                    val indicatorColor = when {
                                        rtt > 0 -> Color(0xFF00E676)      // Xanh: kết nối được
                                        rtt == -1L -> Color(0xFFE53935)   // Đỏ: không kết nối được
                                        else -> if (isCheckingPings) Color(0xFF8892B0) else Color(0xFF8892B0)  // Xám: checking hoặc chưa check
                                    }
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(indicatorColor)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(ipOption, modifier = Modifier.weight(1f))
                                    // Hiển thị ping time (nếu có)
                                    if (rtt > 0) {
                                        Text("${rtt}ms", fontSize = 11.sp, color = Color(0xFF8892B0))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                }
                            },
                            onClick = { ipInput = ipOption; expanded = false },
                            trailingIcon = {
                                IconButton(onClick = {
                                    historyIps = historyIps.filter { it != ipOption }
                                    com.nas.naswebdav.SecurePrefsHelper.saveCredentialsAsync(context, historyIps.map { ipToFullUrl(it) }, user, pass)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Xóa", modifier = Modifier.size(20.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Tên đăng nhập") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Mật khẩu") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(24.dp))
        val interactionSource = remember { MutableInteractionSource() }
        Button(onClick = {
            if (viewModel.isLoading) {
                viewModel.cancelLogin()
            } else {
                val fullUrl = ipToFullUrl(ipInput); val currentIp = fullUrlToIp(fullUrl)
                val reachableUrls = ipPingStatus.filterValues { it > 0L }.entries.sortedBy { it.value }.map { it.key }
                val allUrls = (historyIps + currentIp).distinct().filter { it.isNotEmpty() }.map { ipToFullUrl(it) }
                val fullUrlList = (listOf(fullUrl) + reachableUrls + allUrls).distinct()
                historyIps = fullUrlList.map { fullUrlToIp(it) }.distinct().filter { it.isNotEmpty() }
                viewModel.connect(fullUrlList.map { it.trim() }, user.trim(), pass.trim(), onSuccess = {
                    viewModel.scheduleIdleDuplicateScan(context); viewModel.scheduleIdleSpeedTest(context); viewModel.scheduleFingerprintWorker(context); onLoginSuccess()
                }, onError = { errorMsg -> viewModel.commonDialogType = DialogType.ERROR; viewModel.commonDialogMessage = errorMsg; viewModel.showCommonDialog = true })
            }
        }, enabled = viewModel.isLoading || ipInput.isNotEmpty(), interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(),
            modifier = Modifier.fillMaxWidth().height(50.dp).background(brush = Brush.linearGradient(listOf(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))), shape = RoundedCornerShape(24.dp))
        ) {
            if (viewModel.isLoading) { Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Dừng đăng nhập", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            else Text("Kết nối NAS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        // ── BIOMETRIC QUICK-LOGIN: chi hien khi biometric_enabled + co credentials da luu ──
        val biometricEnabled = sharedPrefs.getBoolean("biometric_enabled", false)
        val hasSavedCreds = remember {
            SecurePrefsHelper.getUser(context).isNotEmpty() &&
                SecurePrefsHelper.getPass(context).isNotEmpty() &&
                SecurePrefsHelper.getUrlList(context).isNotEmpty()
        }
        val biometricAvailable = remember {
            try {
                val bm = androidx.biometric.BiometricManager.from(context)
                val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                bm.canAuthenticate(auth) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
            } catch (_: Exception) { false }
        }
        if (biometricEnabled && hasSavedCreds && biometricAvailable) {
            Spacer(Modifier.height(12.dp))
            val activity = context as? androidx.fragment.app.FragmentActivity
            var autoTriggered by remember { mutableStateOf(false) }
            val triggerBiometric: () -> Unit = {
                if (activity != null) {
                    val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
                    val prompt = androidx.biometric.BiometricPrompt(activity, executor,
                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                val urlList = SecurePrefsHelper.getUrlList(context)
                                val u = SecurePrefsHelper.getUser(context)
                                val p = SecurePrefsHelper.getPass(context)
                                viewModel.connect(urlList, u, p, onSuccess = {
                                    viewModel.scheduleIdleDuplicateScan(context)
                                    viewModel.scheduleIdleSpeedTest(context)
                                    viewModel.scheduleFingerprintWorker(context)
                                    onLoginSuccess()
                                }, onError = { msg ->
                                    viewModel.commonDialogType = DialogType.ERROR
                                    viewModel.commonDialogMessage = msg
                                    viewModel.showCommonDialog = true
                                })
                            }
                        })
                    val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Đăng nhập NAS")
                        .setSubtitle("Dùng vân tay/khuôn mặt để đăng nhập nhanh")
                        .setAllowedAuthenticators(
                            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        ).build()
                    prompt.authenticate(info)
                }
            }
            // Auto-trigger 1 lan khi screen vua compose (chi khi user chua login va da co credentials)
            LaunchedEffect(Unit) {
                if (!autoTriggered && !viewModel.isLoading) {
                    autoTriggered = true
                    kotlinx.coroutines.delay(300)  // cho UI settle
                    triggerBiometric()
                }
            }
            OutlinedButton(
                onClick = triggerBiometric,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF9C27B0)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9C27B0))
            ) {
                Icon(Icons.Default.Fingerprint, null, tint = Color(0xFF9C27B0), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Đăng nhập bằng vân tay", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── KHU VUC NUT KHAN CAP: Bat nguon (WoL) + Khoi dong lai NAS ────────────
        // Cho phep dieu khien NAS khi khong dang nhap duoc (vd NAS treo, mat ket noi).
        Spacer(Modifier.height(20.dp))
        Text(
            "Điều khiển từ xa (không cần đăng nhập)",
            fontSize = 11.sp,
            color = Color(0xFF8892B0),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // NUT 1: WoL — bat nguon NAS qua magic packet, chi can MAC address
            OutlinedButton(
                onClick = {
                    macAddress = sharedPrefs.getString("mac_address", macAddress) ?: macAddress
                    showWolDialog = true
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26A69A))
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, tint = Color(0xFF26A69A), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bật nguồn", color = Color(0xFF26A69A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            // NUT 2: Restart NAS — POST /api/power/reboot truc tiep voi IP + auth tu form
            OutlinedButton(
                onClick = { showRebootConfirm = true },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFB8C00))
            ) {
                Icon(Icons.Default.RestartAlt, null, tint = Color(0xFFFB8C00), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Khởi động lại", color = Color(0xFFFB8C00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (emergencyMsg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                emergencyMsg,
                fontSize = 12.sp,
                color = if (emergencyIsError) Color(0xFFE53935) else Color(0xFF00E676),
                fontWeight = FontWeight.Medium
            )
        }
    }

    // ── DIALOGS cho khu vuc khan cap ────────────────────────────────────────
    if (showWolDialog) {
        com.nas.naswebdav.ui.dialogs.WolDialog(
            macAddress = macAddress,
            onMacChange = { macAddress = it },
            onConfirm = {
                val wolMac = macAddress.trim()
                if (wolMac.isNotBlank()) {
                    sharedPrefs.edit().putString("mac_address", wolMac).apply()
                    showWolDialog = false
                    emergencyIsError = false
                    emergencyMsg = "Đang gửi Wake-on-LAN..."
                    viewModel.sendWakeOnLan(wolMac, ipInput) { result ->
                        emergencyIsError = !result.success
                        emergencyMsg = result.message
                    }
                }
            },
            onDismiss = { showWolDialog = false }
        )
    }
    if (showRebootConfirm) {
        com.nas.naswebdav.ui.dialogs.RebootConfirmDialog(
            onConfirm = {
                showRebootConfirm = false
                viewModel.sendPowerCommandFromLogin(
                    ipInput = ipInput,
                    user = user.trim(),
                    pass = pass.trim(),
                    endpoint = "power/reboot",
                    onResult = { ok, msg ->
                        emergencyIsError = !ok
                        emergencyMsg = msg
                    }
                )
            },
            onDismiss = { showRebootConfirm = false }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════
// GuestPassScreen (từ GuestPassScreen.kt)
// ════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════



