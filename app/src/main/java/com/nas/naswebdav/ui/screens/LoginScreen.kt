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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.core.content.edit

// ============ LoginScreen (tách cơ học từ MainMenuScreen.kt — không đổi logic) ============

// CC-005 P1: URL defaults gom vào AppConfig (WEBDAV_PORT 8822, DEFAULT_NAS_IPS).
private fun ipToFullUrl(ip: String): String {
    val trimmed = ip.trim()
    // Không phá URL user đã nhập scheme — giữ nguyên.
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val prefix = "${com.nas.naswebdav.AppConfig.WEBDAV_SCHEME_DEFAULT}://"
    return if (trimmed.contains(":")) "${prefix}$trimmed${com.nas.naswebdav.AppConfig.WEBDAV_ROOT_PATH}"
        else "${prefix}$trimmed${com.nas.naswebdav.AppConfig.WEBDAV_URL_SUFFIX}"
}

/** Với IP trần, thử HTTPS trước HTTP; login sẽ ưu tiên nhóm LAN trước Tailscale. */
private fun bareIpToLoginCandidates(ip: String): List<String> {
    val trimmed = ip.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return listOf(trimmed)
    val secure = "${com.nas.naswebdav.AppConfig.WEBDAV_SCHEME_SECURE}://$trimmed${com.nas.naswebdav.AppConfig.WEBDAV_URL_SUFFIX}"
    return listOf(secure, ipToFullUrl(trimmed))
}

private fun fullUrlToIp(url: String): String = try { java.net.URL(url).host } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { url }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val loginCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    // ═══ PHASE 7c: Read auth state from Domain VM ═══
    // connect/cancelLogin now route through AuthSessionViewModel (single owner).
    // Shared state (isLoading, errorMessage, connectionStatus) comes from SharedStateHolder
    // which AuthSessionViewModel writes to. Reading here via authVM getters keeps
    // LoginScreen reactive to auth state without touching the facade's mutable vars.
    val authVM = LocalAuthSessionVM.current
    val globalUiVM = LocalGlobalUiVM.current
    val density = LocalDensity.current
    // Khi bàn phím mở, thu gọn phần nhận diện để vùng nhập và nút kết nối
    // vẫn nằm trong viewport. Không tự focus hay tự bật bàn phím khi mở app.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val userFocusRequester = remember { FocusRequester() }
    val rawHistory = remember {
        runCatching { SecurePrefsHelper.getUrlList(context) }.getOrElse { emptyList() }
    }
    var historyIps by remember {
        mutableStateOf((com.nas.naswebdav.AppConfig.DEFAULT_NAS_IPS + rawHistory.map { fullUrlToIp(it) }).distinct().filter { it.isNotEmpty() })
    }
    var ipInput by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(historyIps.firstOrNull() ?: "") }

    // Do not open the keyboard automatically on cold start. The user needs to
    // see the whole connection form first; tapping the username field still
    // uses the requester for an explicit focus action.
    var user by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(runCatching { SecurePrefsHelper.getUser(context) }.getOrElse { "" })
    }
    var pass by remember {
        mutableStateOf(runCatching { SecurePrefsHelper.getPass(context) }.getOrElse { "" })
    }
    var passwordVisible by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    var ipFieldWidthPx by remember { mutableIntStateOf(0) }

    // State cho 2 nút khẩn cấp (WoL + Restart) hiện trên login screen — dùng khi
    // NAS bị lỗi không đăng nhập được. MAC lưu qua PreferencesRepository.
    val prefsRepo = remember(context) {
        com.nas.naswebdav.utils.PreferencesRepository.get(context)
    }
    var macAddress by remember(prefsRepo) { mutableStateOf(prefsRepo.getMacAddress()) }
    var showWolDialog by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var emergencyMsg by remember { mutableStateOf("") }
    var emergencyIsError by remember { mutableStateOf(false) }

    // Trạng thái ping real-time cho các IP: URL → RTT (ms), -1 = unreachable
    var ipPingStatus by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var isCheckingPings by remember { mutableStateOf(false) }

    // Debounced ping: only restart when endpoint or credentials change, then refresh
    // every 2 seconds while the same login form remains active.
    LaunchedEffect(historyIps, ipInput, user, pass) {
        kotlinx.coroutines.delay(600L)
        while (isActive) {
            isCheckingPings = true
            try {
                val fullUrls = (historyIps + ipInput).distinct().filter { it.isNotBlank() }.map { ipToFullUrl(it) }
                if (fullUrls.isNotEmpty()) {
                    ipPingStatus = com.nas.naswebdav.pingUrlsForDisplay(fullUrls, user, pass)
                } else {
                    ipPingStatus = emptyMap()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                ipPingStatus = emptyMap()
            } finally {
                isCheckingPings = false
            }
            kotlinx.coroutines.delay(2000L)
        }
    }

    LaunchedEffect(ipPingStatus) {
        val bestUrl = ipPingStatus
            .filterValues { it > 0L }
            .minByOrNull { it.value }
            ?.key
        if (!bestUrl.isNullOrBlank() && ipInput.isBlank()) {
            ipInput = fullUrlToIp(bestUrl)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(DarkSurface, DarkCard, Color(0xFF101B2E))
                )
            )
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = if (imeVisible) 8.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(if (imeVisible) 4.dp else 20.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "NASWEB",
                    color = AccentCyan,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
                Spacer(Modifier.height(4.dp))
                Text("Kết nối máy chủ", color = TextPrimary,
                    style = if (imeVisible) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                if (!imeVisible) {
                    Spacer(Modifier.height(4.dp))
                    Text("Truy cập an toàn vào WebDAV của bạn", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!imeVisible) {
                Box(
                    Modifier.size(56.dp).background(AccentCyan.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Dns, contentDescription = "Máy chủ NAS", tint = AccentCyan, modifier = Modifier.size(30.dp))
                }
            }
        }
        Spacer(Modifier.height(if (imeVisible) 8.dp else 24.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Dialog,
            color = DarkCard.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
        Column(Modifier.padding(if (imeVisible) 14.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(if (imeVisible) 30.dp else 36.dp)
                        .background(InfoContainer, AppShapes.Input),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Dns, contentDescription = "DNS", tint = AccentCyan, modifier = Modifier.size(if (imeVisible) 18.dp else 22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Đường truyền & tài khoản", color = TextPrimary, fontWeight = FontWeight.Bold)
                    if (!imeVisible) Text("Chọn máy chủ nhanh nhất", color = TextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(if (imeVisible) 10.dp else 18.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                label = { Text("Địa chỉ IP / DDNS của NAS") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { ipFieldWidthPx = it.size.width }
                    .menuAnchor(),
                singleLine = true,
                shape = AppShapes.Input,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = OutlineVariantDark,
                    focusedLabelColor = AccentCyan,
                    unfocusedLabelColor = TextSecondary,
                    cursorColor = AccentCyan
                ),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            if (historyIps.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = if (ipFieldWidthPx > 0) {
                        Modifier.width(with(density) { ipFieldWidthPx.toDp() })
                    } else {
                        Modifier.fillMaxWidth()
                    }
                ) {
                    historyIps.forEach { ipOption ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    // Hiển thị chỉ báo ping: ● xanh = OK, ● đỏ = fail, ● xám = checking
                                    val fullUrl = ipToFullUrl(ipOption)
                                    val rtt = ipPingStatus[fullUrl] ?: -2L
                                    val indicatorColor = when {
                                        rtt > 0 -> AccentGreen      // Xanh: kết nối được
                                        rtt == -1L -> AccentRed   // Đỏ: không kết nối được
                                        else -> if (isCheckingPings) TextTertiary else TextTertiary  // Xám: checking hoặc chưa check
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
                                        Text("${rtt}ms", fontSize = 12.sp, color = TextTertiary)
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
        Spacer(Modifier.height(if (imeVisible) 8.dp else 12.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Tên đăng nhập") },
            modifier = Modifier.fillMaxWidth().focusRequester(userFocusRequester),
            singleLine = true,
            shape = AppShapes.Input,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = OutlineVariantDark,
                focusedLabelColor = AccentCyan,
                unfocusedLabelColor = TextSecondary,
                cursorColor = AccentCyan
            )
        )
        Spacer(Modifier.height(if (imeVisible) 8.dp else 12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Mật khẩu") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = AppShapes.Input,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                autoCorrect = false
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = OutlineVariantDark,
                focusedLabelColor = AccentCyan,
                unfocusedLabelColor = TextSecondary,
                cursorColor = AccentCyan
            ),
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                val description = if (passwordVisible) "Ẩn mật khẩu" else "Hiện mật khẩu"
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(imageVector = image, contentDescription = description)
                }
            }
        )
        Spacer(Modifier.height(if (imeVisible) 10.dp else 18.dp))
        val interactionSource = remember { MutableInteractionSource() }
        val btnContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            if (authVM.isLoading) {
                Icon(Icons.Default.Stop, contentDescription = "Dừng đăng nhập", tint = TextPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Dừng đăng nhập", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("Kết nối NAS", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        com.nas.naswebdav.ui.components.NasGradientButton(
            onClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                if (authVM.isLoading) {
                    authVM.cancelLogin()
                } else {
                    // CC-005 P1: IP trần -> thử https trước http (connect đua song song, https thắng nếu mở).
                    val typedCandidates = bareIpToLoginCandidates(ipInput)
                    val fullUrl = typedCandidates.firstOrNull() ?: ipToFullUrl(ipInput)
                    val currentIp = fullUrlToIp(fullUrl)
                    val reachableUrls = ipPingStatus.filterValues { it > 0L }.entries.sortedBy { it.value }.map { it.key }
                    val allUrls = (historyIps + currentIp).distinct().filter { it.isNotEmpty() }.map { ipToFullUrl(it) }
                    val fullUrlList = (typedCandidates + reachableUrls + allUrls).distinct()
                    historyIps = fullUrlList.map { fullUrlToIp(it) }.distinct().filter { it.isNotEmpty() }
                    authVM.connect(fullUrlList.map { it.trim() }, user.trim(), pass.trim(), onSuccess = {
                        com.nas.naswebdav.scheduleIdleDuplicateScan(context, fullUrlList.first()); com.nas.naswebdav.scheduleIdleSpeedTest(context, fullUrlList.first()); com.nas.naswebdav.scheduleFingerprintWorker(context); onLoginSuccess()
                    }, onError = { errorMsg -> globalUiVM.show(DialogType.ERROR, errorMsg) })
                }
            },
            text = "Kết nối NAS",
            enabled = authVM.isLoading || ipInput.isNotEmpty(),
            height = 50.dp,
            interactionSource = interactionSource,
            customContent = btnContent
        )
        }
        }

        // ── BIOMETRIC QUICK-LOGIN: chi hien khi biometric_enabled + co credentials da luu ──
        val biometricEnabled = remember(prefsRepo) { prefsRepo.isBiometricEnabled() }
        val hasSavedCreds = remember {
            runCatching {
                SecurePrefsHelper.getUser(context).isNotEmpty() &&
                    SecurePrefsHelper.getPass(context).isNotEmpty() &&
                    SecurePrefsHelper.getUrlList(context).isNotEmpty()
            }.getOrElse { false }
        }
        val biometricAvailable = remember {
            try {
                val bm = androidx.biometric.BiometricManager.from(context)
                val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                bm.canAuthenticate(auth) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        }
        if (biometricEnabled && hasSavedCreds && biometricAvailable) {
            Spacer(Modifier.height(12.dp))
            val activity = context as? androidx.fragment.app.FragmentActivity
            val triggerBiometric: () -> Unit = {
                if (activity != null) {
                    val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
                    val prompt = androidx.biometric.BiometricPrompt(activity, executor,
                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                val urlList = runCatching { SecurePrefsHelper.getUrlList(context) }.getOrElse { emptyList() }
                                val u = runCatching { SecurePrefsHelper.getUser(context) }.getOrElse { "" }
                                val p = runCatching { SecurePrefsHelper.getPass(context) }.getOrElse { "" }
                                authVM.connect(urlList, u, p, onSuccess = {
                                    com.nas.naswebdav.scheduleIdleDuplicateScan(context, urlList.firstOrNull() ?: "")
                                    com.nas.naswebdav.scheduleIdleSpeedTest(context, urlList.firstOrNull() ?: "")
                                    com.nas.naswebdav.scheduleFingerprintWorker(context)
                                    onLoginSuccess()
                                }, onError = { msg ->
                                    globalUiVM.show(DialogType.ERROR, msg)
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
            // BO AUTO-TRIGGER: mo app mac dinh dung o man hinh dang nhap.
            // User bam nut "Dang nhap bang van tay" moi hien prompt.
            OutlinedButton(
                onClick = triggerBiometric,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, AccentPurple),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
            ) {
                Icon(Icons.Default.Fingerprint, null, tint = AccentPurple, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Đăng nhập bằng vân tay", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── KHU VUC NUT KHAN CAP: Bat nguon (WoL) + Khoi dong lai NAS ────────────
        // Cho phep dieu khien NAS khi khong dang nhap duoc (vd NAS treo, mat ket noi).
        Spacer(Modifier.height(12.dp))
        Text(
            "Điều khiển từ xa (không cần đăng nhập)",
            fontSize = 12.sp,
            color = TextTertiary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // NUT 1: WoL — bat nguon NAS qua magic packet, chi can MAC address
            OutlinedButton(
                onClick = {
                    macAddress = prefsRepo.getMacAddress().ifBlank { macAddress }
                    showWolDialog = true
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan)
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bật nguồn", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            // NUT 2: Restart NAS — POST /api/power/reboot truc tiep voi IP + auth tu form
            OutlinedButton(
                onClick = { showRebootConfirm = true },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange)
            ) {
                Icon(Icons.Default.RestartAlt, null, tint = AccentOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Khởi động lại", color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (emergencyMsg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                emergencyMsg,
                fontSize = 12.sp,
                color = if (emergencyIsError) AccentRed else AccentGreen,
                fontWeight = FontWeight.Medium
            )
        }

        // ── Nhãn phiên bản (auto theo build) — để phân biệt rõ bản đang chạy ────
        Spacer(Modifier.height(12.dp))
        Text(
            "Phiên bản ${com.nas.naswebdav.BuildConfig.VERSION_NAME}",
            fontSize = 12.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
    } // end Box

    // ── DIALOGS cho khu vuc khan cap ────────────────────────────────────────
    if (showWolDialog) {
        com.nas.naswebdav.ui.dialogs.WolDialog(
            macAddress = macAddress,
            onMacChange = { macAddress = it },
            onConfirm = {
                val wolMac = macAddress.trim()
                if (wolMac.isNotBlank()) {
                    prefsRepo.setMacAddress(wolMac)
                    showWolDialog = false
                    emergencyIsError = false
                    emergencyMsg = "Đang gửi Wake-on-LAN..."
                    com.nas.naswebdav.sendWakeOnLan(loginCoroutineScope, wolMac, ipInput) { result ->
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
                com.nas.naswebdav.sendPowerCommandFromLogin(
                    scope = loginCoroutineScope,
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
