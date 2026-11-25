package com.nas.naswebdav.ui.html5

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nas.naswebdav.*
import com.nas.naswebdav.utils.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

private fun ipToFullUrl(ip: String): String {
    val trimmed = ip.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val prefix = "${AppConfig.WEBDAV_SCHEME_DEFAULT}://"
    return if (trimmed.contains(":")) "$prefix$trimmed${AppConfig.WEBDAV_ROOT_PATH}"
    else "$prefix$trimmed${AppConfig.WEBDAV_URL_SUFFIX}"
}

private fun bareIpToLoginCandidates(ip: String): List<String> {
    val trimmed = ip.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return listOf(trimmed)
    val secure = "${AppConfig.WEBDAV_SCHEME_SECURE}://$trimmed${AppConfig.WEBDAV_URL_SUFFIX}"
    return listOf(secure, ipToFullUrl(trimmed))
}

private fun loginUrlToHost(url: String): String =
    try { URL(url).host } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { url }

/**
 * Màn hình Đăng nhập HTML5 (Lucian UX/UI Dark Slate + Glassmorphism).
 * Giữ nguyên toàn bộ logic AuthSessionViewModel, Ping real-time, Biometric, WoL và Reboot.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NasHtmlLoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authVM = LocalAuthSessionVM.current
    val prefsRepo = remember(context) { PreferencesRepository.get(context) }

    val rawHistory = remember { runCatching { SecurePrefsHelper.getUrlList(context) }.getOrElse { emptyList() } }
    var historyIps by remember {
        mutableStateOf((AppConfig.DEFAULT_NAS_IPS + rawHistory.map { loginUrlToHost(it) }).distinct().filter { it.isNotEmpty() })
    }
    var ipInput by remember { mutableStateOf(historyIps.firstOrNull() ?: "") }
    var userInput by remember { mutableStateOf(runCatching { SecurePrefsHelper.getUser(context) }.getOrElse { "" }) }
    var passInput by remember { mutableStateOf(runCatching { SecurePrefsHelper.getPass(context) }.getOrElse { "" }) }

    var ipPingStatus by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var macAddress by remember(prefsRepo) { mutableStateOf(prefsRepo.getMacAddress()) }
    var emergencyMsg by remember { mutableStateOf("") }
    var emergencyIsError by remember { mutableStateOf(false) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

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
            val bm = BiometricManager.from(context)
            val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            bm.canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // Real-time ping loop (every 2s)
    LaunchedEffect(historyIps, ipInput, userInput, passInput) {
        delay(500L)
        while (isActive) {
            try {
                val fullUrls = (historyIps + ipInput).distinct().filter { it.isNotBlank() }.map { ipToFullUrl(it) }
                ipPingStatus = if (fullUrls.isNotEmpty()) pingUrlsForDisplay(fullUrls, userInput, passInput) else emptyMap()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                ipPingStatus = emptyMap()
            }
            delay(2000L)
        }
    }

    val bestIp = remember(ipPingStatus) {
        ipPingStatus.filterValues { it > 0L }.minByOrNull { it.value }?.key?.let { loginUrlToHost(it) } ?: ""
    }

    fun pushState() {
        val wv = webViewRef ?: return
        if (!pageLoaded) return
        val pingsJson = JSONObject()
        historyIps.forEach { ip ->
            val rtt = ipPingStatus[ipToFullUrl(ip)] ?: -2L
            pingsJson.put(ip, rtt)
        }
        val payload = JSONObject().apply {
            put("ip", ipInput)
            put("bestIp", bestIp)
            put("user", userInput)
            put("pass", passInput)
            put("historyIps", JSONArray(historyIps))
            put("pings", pingsJson)
            put("isLoading", authVM.isLoading)
            put("showBio", biometricEnabled && hasSavedCreds && biometricAvailable)
            put("mac", macAddress)
            put("emergMsg", emergencyMsg)
            put("emergErr", emergencyIsError)
            put("version", BuildConfig.VERSION_NAME)
        }.toString()
        wv.evaluateJavascript("window.NasLoginUI && window.NasLoginUI.updateState($payload);", null)
    }

    LaunchedEffect(pageLoaded, historyIps, ipPingStatus, authVM.isLoading, emergencyMsg, emergencyIsError, macAddress) {
        pushState()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.parseColor("#080d1a"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                isVerticalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onFieldChange(ip: String, user: String, pass: String) {
                        post {
                            ipInput = ip
                            userInput = user
                            passInput = pass
                        }
                    }

                    @JavascriptInterface
                    fun removeHistoryIp(ip: String, user: String, pass: String) {
                        post {
                            historyIps = historyIps.filter { it != ip }
                            SecurePrefsHelper.saveCredentialsAsync(context, historyIps.map { ipToFullUrl(it) }, user, pass)
                        }
                    }

                    @JavascriptInterface
                    fun connect(ip: String, user: String, pass: String) {
                        post {
                            if (ip.isBlank() || user.isBlank() || pass.isBlank()) return@post
                            val typedCandidates = bareIpToLoginCandidates(ip)
                            val fullUrl = typedCandidates.firstOrNull() ?: ipToFullUrl(ip)
                            val currentIp = loginUrlToHost(fullUrl)
                            val reachableUrls = ipPingStatus.filterValues { it > 0L }.entries.sortedBy { it.value }.map { it.key }
                            val allUrls = (historyIps + currentIp).distinct().filter { it.isNotEmpty() }.map { ipToFullUrl(it) }
                            val fullUrlList = (typedCandidates + reachableUrls + allUrls).distinct()
                            historyIps = fullUrlList.map { loginUrlToHost(it) }.distinct().filter { it.isNotEmpty() }
                            authVM.connect(
                                fullUrlList.map { it.trim() },
                                user.trim(),
                                pass.trim(),
                                onSuccess = {
                                    scheduleIdleDuplicateScan(context, fullUrlList.first())
                                    scheduleIdleSpeedTest(context, fullUrlList.first())
                                    scheduleFingerprintWorker(context)
                                    evaluateJavascript("window.NasLoginUI && window.NasLoginUI.showLoginSuccess()") { result ->
                                        postDelayed({ onLoginSuccess() }, result?.toLongOrNull() ?: 0L)
                                    }
                                },
                                onFailure = { err, rejectedCredentials ->
                                    val message = if (rejectedCredentials) "Kiểm tra tên đăng nhập và mật khẩu." else err
                                    evaluateJavascript(
                                        "window.NasLoginUI && window.NasLoginUI.showLoginError(${JSONObject.quote(message)}, $rejectedCredentials)",
                                        null
                                    )
                                }
                            )
                        }
                    }

                    @JavascriptInterface
                    fun cancelLogin() {
                        post {
                            authVM.cancelLogin()
                            evaluateJavascript("window.NasLoginUI && window.NasLoginUI.showLoginCancelled()", null)
                        }
                    }

                    @JavascriptInterface
                    fun biometricLogin() {
                        post {
                            fun updateBiometric(stage: String, text: String, callback: ((String?) -> Unit)? = null) {
                                val script = "window.NasLoginUI && window.NasLoginUI.updateBiometric(${JSONObject.quote(stage)}, ${JSONObject.quote(text)})"
                                evaluateJavascript(script) { callback?.invoke(it) }
                            }
                            val activity = context as? FragmentActivity
                            if (activity == null || authVM.isLoading) {
                                updateBiometric("idle", "Chưa thể mở xác thực sinh trắc học.")
                                return@post
                            }
                            val executor = ContextCompat.getMainExecutor(activity)
                            val prompt = BiometricPrompt(
                                activity,
                                executor,
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                        updateBiometric("idle", errString.toString())
                                    }

                                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                        super.onAuthenticationSucceeded(result)
                                        val urlList = runCatching { SecurePrefsHelper.getUrlList(context) }.getOrElse { emptyList() }
                                        val u = runCatching { SecurePrefsHelper.getUser(context) }.getOrElse { "" }
                                        val p = runCatching { SecurePrefsHelper.getPass(context) }.getOrElse { "" }
                                        updateBiometric("scan", "Đã xác minh. Đang kết nối NAS")
                                        authVM.connect(
                                            urlList,
                                            u,
                                            p,
                                            onSuccess = {
                                                scheduleIdleDuplicateScan(context, urlList.firstOrNull() ?: "")
                                                scheduleIdleSpeedTest(context, urlList.firstOrNull() ?: "")
                                                scheduleFingerprintWorker(context)
                                                updateBiometric("ok", "Đã kết nối NAS") { delayMillis ->
                                                    postDelayed({ onLoginSuccess() }, delayMillis?.toLongOrNull() ?: 0L)
                                                }
                                            },
                                            onError = { msg -> updateBiometric("idle", msg) }
                                        )
                                    }
                                }
                            )
                            val info = BiometricPrompt.PromptInfo.Builder()
                                .setTitle("Đăng nhập NAS")
                                .setSubtitle("Dùng vân tay/khuôn mặt để đăng nhập nhanh")
                                .setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                ).build()
                            prompt.authenticate(info)
                        }
                    }

                    @JavascriptInterface
                    fun sendWol(mac: String, ip: String) {
                        post {
                            val wolMac = mac.trim()
                            if (wolMac.isBlank()) return@post
                            macAddress = wolMac
                            prefsRepo.setMacAddress(wolMac)
                            emergencyIsError = false
                            emergencyMsg = "Đang gửi Wake-on-LAN..."
                            sendWakeOnLan(scope, wolMac, ip) { res ->
                                emergencyIsError = !res.success
                                emergencyMsg = res.message
                            }
                        }
                    }

                    @JavascriptInterface
                    fun sendReboot(ip: String, user: String, pass: String) {
                        post {
                            sendPowerCommandFromLogin(
                                scope = scope,
                                ipInput = ip,
                                user = user.trim(),
                                pass = pass.trim(),
                                endpoint = "power/reboot",
                                onResult = { ok, msg ->
                                    emergencyIsError = !ok
                                    emergencyMsg = msg
                                }
                            )
                        }
                    }
                }, "NasLoginBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageLoaded = true
                        pushState()
                    }
                }
                webViewRef = this
                loadUrl("file:///android_asset/html5/login.html")
            }
        }
    )
}
