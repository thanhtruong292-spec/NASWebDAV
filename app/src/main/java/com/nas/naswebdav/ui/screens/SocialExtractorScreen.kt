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

// ════════════════════════════════════════════════════════════════════════════
// SocialExtractorScreen.kt
// ════════════════════════════════════════════════════════════════════════════

// ── Bảng màu ─────────────────────────────────────────────────────────────────
private val SeDarkBg        = Color.Black
private val SeDarkCard      = Color(0xFF0A0A0A)
private val SeDarkCardAlt   = Color(0xFF0A0A0A)
private val SeAccentGreen   = Color(0xFF00E676)
private val SeAccentOrange  = Color(0xFFFF9100)
private val SeAccentRed     = Color(0xFFFF1744)
private val SeAccentCyan    = Color(0xFF00D2FF)
private val SeAccentPink    = Color(0xFFFF6EC7)
private val SeAccentPurple  = Color(0xFFE040FB)
private val SeTextPrimary   = Color(0xFFE8E8E8)
private val SeTextSecondary = Color(0xFF8892B0)

/**
 * SocialExtractorScreen – Màn hình Stream Piping thực sự.
 *
 * ── Luồng hoạt động:
 *  1. User dán link TikTok/Facebook/YouTube
 *  2. WebView ẩn load trang → JS Injection bóc link MP4 CDN
 *  3. OkHttp mở stream từ link CDN → bơm thẳng qua WebDAV PUT lên NAS
 *  4. Điện thoại chỉ làm "ống nước" (pipe) – KHÔNG lưu 1 byte nào xuống bộ nhớ
 *  5. Progress realtime: bytes đã truyền, tốc độ MB/s, ETA
 *
 * ── Fallback (nếu WebView không bóc được link):
 *  Gửi URL về NAS → NAS tự dùng yt-dlp xử lý
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialExtractorScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val livestreamVM = LocalLivestreamVM.current

    // ── State cục bộ ─────────────────────────────────────────────────────────
    var linkInput by remember {
        val clip = clipboardManager.getText()?.text ?: ""
        mutableStateOf(if (isSocialUrl(clip)) clip else "")
    }
    // Chế độ: true = Stream Pipe (điện thoại bơm), false = yt-dlp (NAS tự tải)
    var usePipeMode by remember { mutableStateOf(true) }
    // Kết quả URL video đã bóc được từ WebView
    var extractedVideoUrl by remember { mutableStateOf<String?>(null) }
    // Trạng thái WebView extraction
    var webViewStatus by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    // WebView instance để có thể inject JS sau khi load xong
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Trigger để WebView bắt đầu extract (thay đổi URL này = load trang mới)
    var extractTriggerUrl by remember { mutableStateOf("") }

    // ── Tự động Pipe khi đã bóc được link MP4 ───────────────────────────────
    LaunchedEffect(extractedVideoUrl) {
        val mp4Url = extractedVideoUrl ?: return@LaunchedEffect
        if (mp4Url.isNotEmpty() && usePipeMode) {
            val platform = detectPlatform(linkInput)
            val fileName = "social_${platform}_${System.currentTimeMillis()}.mp4"
            livestreamVM.startStreamPipe(mp4Url, fileName)
            extractedVideoUrl = null
            webViewStatus = ""
            isExtracting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Social Extractor", fontWeight = FontWeight.Bold, color = SeTextPrimary)
                        Text("Stream Piping – 0MB điện thoại", fontSize = 11.sp, color = SeTextSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        livestreamVM.cancelStreamPipe()
                        onBack()
                    }) { Icon(Icons.Default.ArrowBack, null, tint = SeTextPrimary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SeDarkBg)
            )
        },
        containerColor = SeDarkBg
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Giải thích 2 chế độ ──────────────────────────────────────────
            ModeExplainCard(usePipeMode)
            Spacer(Modifier.height(12.dp))

            // ── Chuyển chế độ ────────────────────────────────────────────────
            ModeSwitchRow(
                isPipeMode = usePipeMode,
                onToggle = {
                    usePipeMode = !usePipeMode
                    extractedVideoUrl = null
                    webViewStatus = ""
                }
            )
            Spacer(Modifier.height(12.dp))

            // ── Input link ───────────────────────────────────────────────────
            LinkInputCard(
                link = linkInput,
                onLinkChange = {
                    linkInput = it
                    extractedVideoUrl = null
                    webViewStatus = ""
                },
                onPasteClipboard = {
                    val clip = clipboardManager.getText()?.text ?: ""
                    if (clip.isNotBlank()) linkInput = clip
                }
            )
            Spacer(Modifier.height(12.dp))

            // ── Thư mục đích ─────────────────────────────────────────────────
            DestFolderRow()
            Spacer(Modifier.height(12.dp))

            // ── Trạng thái WebView extraction ────────────────────────────────
            AnimatedVisibility(visible = webViewStatus.isNotEmpty() || isExtracting) {
                ExtractionStatusCard(webViewStatus, isExtracting)
                Spacer(Modifier.height(10.dp))
            }

            // ── Stream Pipe Progress ──────────────────────────────────────────
            AnimatedVisibility(visible = livestreamVM.streamPipeStatus.isNotEmpty()) {
                StreamPipeProgressCard()
                Spacer(Modifier.height(10.dp))
            }

            // ── Trạng thái yt-dlp (fallback mode) ────────────────────────────
            AnimatedVisibility(visible = !usePipeMode && livestreamVM.socialExtractStatus.isNotEmpty()) {
                SocialStatusCard(livestreamVM.socialExtractStatus, livestreamVM.isSocialExtracting)
                Spacer(Modifier.height(10.dp))
            }

            // ── Lịch sử ──────────────────────────────────────────────────────
            if (livestreamVM.socialDownloadHistory.isNotEmpty()) {
                HistoryCard(livestreamVM.socialDownloadHistory)
                Spacer(Modifier.height(12.dp))
            }

            // ── Nút hành động chính ───────────────────────────────────────────
            val isWorking = livestreamVM.isSocialExtracting || livestreamVM.isStreamPiping || isExtracting
            MainActionButton(
                isPipeMode = usePipeMode,
                isWorking = isWorking,
                isEnabled = linkInput.isNotBlank(),
                onStart = {
                    if (usePipeMode) {
                        // Chế độ truyền trực tiếp: dùng WebView lấy liên kết trước
                        extractedVideoUrl = null
                        webViewStatus = "🔍 Đang tải trang để bóc link video..."
                        isExtracting = true
                        extractTriggerUrl = linkInput.trim()
                    } else {
                        // Chế độ NAS tự tải: gửi URL thẳng về NAS qua yt-dlp
                        livestreamVM.requestSocialDownload(linkInput.trim(), AppConfig.SOCIAL_DOWNLOAD_FOLDER)
                        linkInput = ""
                    }
                },
                onCancel = {
                    livestreamVM.cancelStreamPipe()
                    isExtracting = false
                    webViewStatus = ""
                    extractTriggerUrl = ""
                }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                if (usePipeMode)
                    "Truyền trực tiếp: điện thoại chuyển dữ liệu từ CDN về NAS."
                else
                    "NAS tự tải: NAS dùng yt-dlp và lưu trực tiếp vào ổ cứng.",
                fontSize = 11.sp, color = SeTextSecondary, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // ── WebView ẨN – Chỉ hoạt động khi có trigger URL ────────────────────────
    // View.GONE → không chiếm không gian màn hình nhưng JS vẫn chạy bình thường
    if (extractTriggerUrl.isNotEmpty()) {
        HiddenExtractorWebView(
            targetUrl = extractTriggerUrl,
            onStatusUpdate = { msg -> webViewStatus = msg },
            onVideoUrlFound = { mp4Url ->
                extractedVideoUrl = mp4Url
                extractTriggerUrl = ""  // Dừng WebView sau khi bóc xong
                webViewStatus = "✅ Đã lấy được liên kết video HD. Đang truyền về NAS..."
            },
            onLivestreamFound = { liveUrl, referer, userAgent ->
                livestreamVM.startLivestreamRecord(liveUrl, "best", referer, userAgent)
                extractTriggerUrl = ""
                webViewStatus = "✅ Đã bắt được luồng Livestream (M3U8)! Đang ra lệnh NAS ghi hình..."
                isExtracting = false
            },
            onFailure = { reason ->
                isExtracting = false
                extractTriggerUrl = ""
                webViewStatus = "⚠️ Không lấy được liên kết tự động ($reason). Đang chuyển sang chế độ NAS tự tải..."
                // Tự động fallback sang yt-dlp
                livestreamVM.requestSocialDownload(linkInput.trim(), AppConfig.SOCIAL_DOWNLOAD_FOLDER)
            },
            onWebViewReady = { wv -> webViewRef = wv }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// WebView ẨN – Bóc link MP4 bằng JS Injection
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * HiddenExtractorWebView – WebView kích thước 0x0, không hiển thị trên màn hình.
 *
 * Cơ chế:
 *  1. Load trang HTML gốc của TikTok/Facebook (có User-Agent giả Mobile)
 *  2. Inject JS: Quét toàn bộ DOM, video tags, JSON-LD, OG tags → tìm link .mp4
 *  3. Giao tiếp qua JavascriptInterface: onVideoFound(url) / onFailed(reason)
 *  4. Retry tự động: Re-inject JS sau 2s nếu lần đầu chưa thấy (trang chưa load xong)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HiddenExtractorWebView(
    targetUrl: String,
    onStatusUpdate: (String) -> Unit,
    onVideoUrlFound: (String) -> Unit,
    onLivestreamFound: (url: String, referer: String, userAgent: String) -> Unit,
    onFailure: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit
) {
    // Hiển thị dưới dạng 0x0 invisible view
    AndroidView(
        modifier = Modifier.size(0.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // User-Agent thật của Chrome Mobile để vượt bot-detection
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = false  // Tiết kiệm RAM
                    blockNetworkImage = true           // KHÔNG tải ảnh
                }

                // Cho phép cookie (cần cho TikTok/Facebook session)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // JavascriptInterface: Cổng giao tiếp JS → Kotlin
                addJavascriptInterface(
                    object : Any() {
                        @JavascriptInterface
                        fun onVideoFound(url: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (url.isNotBlank() && url.startsWith("http")) {
                                    onVideoUrlFound(url)
                                }
                            }
                        }
                        @JavascriptInterface
                        fun onStatusUpdate(msg: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onStatusUpdate("🔍 $msg")
                            }
                        }
                        @JavascriptInterface
                        fun onFailed(reason: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onFailure(reason)
                            }
                        }
                    },
                    "NasExtractor"
                )

                // ── MỘT webViewClient duy nhất: vừa chặn tài nguyên, vừa inject JS ──
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var timeoutRunnable: Runnable? = null

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val isMain = request?.isForMainFrame == true
                        
                        // --- Bắt luồng livestream (M3U8 / FLV / MPD / TS) ---
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".mpd") || reqUrl.contains(".flv")) {
                            // Bỏ qua các file phân mảnh con, chỉ bắt file master manifest
                            if (!reqUrl.contains("chunk") && !reqUrl.contains("segment")) {
                                val referer = request?.requestHeaders?.get("Referer") ?: targetUrl
                                val ua = request?.requestHeaders?.get("User-Agent") ?: view?.settings?.userAgentString ?: ""
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onLivestreamFound(reqUrl, referer, ua)
                                }
                                // Trả về block để dừng load stream tren dien thoai
                                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                        }

                        if (!isMain && (
                            reqUrl.endsWith(".png") || reqUrl.endsWith(".jpg") ||
                            reqUrl.endsWith(".gif") || reqUrl.endsWith(".webp") ||
                            reqUrl.endsWith(".woff") || reqUrl.endsWith(".woff2") ||
                            reqUrl.contains("analytics") || reqUrl.contains("/ads/") ||
                            reqUrl.contains("tracking") || reqUrl.contains("pixel")
                        )) {
                            return WebResourceResponse("text/plain", "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        // CHỈ tiêm JS bóc link (lộ cầu nối NasExtractor) khi trang hiện
                        // tại vẫn nằm trong allowlist. Nếu trang đã redirect ra domain lạ
                        // thì không inject để tránh trao cầu nối JS cho site không tin cậy.
                        if (url == null || !isAllowedSocialHost(url)) {
                            timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                            onFailure("Trang đã chuyển hướng ra ngoài nền tảng được hỗ trợ")
                            return
                        }

                        onStatusUpdate("📄 Trang load xong. Đang tiêm JS bóc link...")

                        // Xoá timeout cũ, đặt lại 10 giây
                        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                        timeoutRunnable = Runnable {
                            onFailure("JS không bóc được link trong 10 giây")
                        }.also { timeoutHandler.postDelayed(it, 10_000) }

                        // Inject lần 1
                        view?.evaluateJavascript(buildExtractorJs(), null)
                        // Retry lần 2 sau 2.5s (trang lazy-load JS)
                        timeoutHandler.postDelayed({
                            if (view != null && isAllowedSocialHost(view.url ?: "")) {
                                view.evaluateJavascript(buildExtractorJs(), null)
                            }
                        }, 2_500)
                    }

                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                            onFailure("Lỗi tải trang: ${error?.description}")
                        }
                    }
                }

                onWebViewReady(this)
                loadUrl(targetUrl)
            }
        },
        update = { wv ->
            // Nếu URL thay đổi thì load lại
            if (wv.url != targetUrl && targetUrl.isNotEmpty()) {
                wv.loadUrl(targetUrl)
            }
        }
    )
}

/**
 * buildExtractorJs() – Script đa chiến lược tìm link video MP4/CDN.
 *
 * Chiến lược (theo thứ tự ưu tiên):
 *  1. <video src="...">, <source src="...">     ← Trực tiếp nhất
 *  2. JSON-LD (script[type=application/ld+json]) ← TikTok dùng cách này
 *  3. OG Tags (meta[property=og:video])          ← Facebook, Instagram
 *  4. window.__INITIAL_STATE__ / window.SIGI_STATE (TikTok SPA)
 *  5. Script JSON rò rỉ (tìm regex playAddr/download_addr trong mọi script)
 */
private fun buildExtractorJs(): String = """
(function() {
    var found = null;
    var host = window.location.host || '';
    
    // --- Chiến lược 1: Video/Source tags trực tiếp (chắc nhất) ---
    var videos = document.querySelectorAll('video[src], source[src]');
    for (var i = 0; i < videos.length; i++) {
        var s = videos[i].src || videos[i].getAttribute('src') || '';
        if (s && (s.includes('.mp4') || s.includes('video') || s.includes('media'))) {
            if (!s.startsWith('blob:')) { found = s; break; }
        }
    }
    
    // --- Chiến lược 2: JSON-LD (TikTok, YouTube) ---
    if (!found) {
        var jsonLds = document.querySelectorAll('script[type="application/ld+json"]');
        for (var i = 0; i < jsonLds.length; i++) {
            try {
                var d = JSON.parse(jsonLds[i].textContent || '{}');
                var contentUrl = d.contentUrl || (d.video && d.video.contentUrl) || '';
                if (contentUrl && contentUrl.includes('http')) { found = contentUrl; break; }
            } catch(e) {}
        }
    }
    
    // --- Chiến lược 3: OG:Video Meta Tag (Facebook, Instagram) ---
    if (!found) {
        var ogVideo = document.querySelector('meta[property="og:video:secure_url"], meta[property="og:video"]');
        if (ogVideo) { found = ogVideo.getAttribute('content') || null; }
    }
    
    // --- Chiến lược 4: TikTok SPA state (SIGI_STATE / __INITIAL_STATE__) ---
    if (!found && (host.includes('tiktok') || host.includes('douyin'))) {
        var keys = ['__INITIAL_STATE__', 'SIGI_STATE', '__UNIVERSAL_DATA__'];
        for (var k = 0; k < keys.length; k++) {
            try {
                var st = window[keys[k]];
                if (!st) continue;
                var str = JSON.stringify(st);
                // Tìm playAddr (link CDN nhanh) hoặc download_addr
                var re = /"playAddr"\s*:\s*"([^"]+)"/;
                var m = re.exec(str);
                if (m) { found = m[1].replace(/\\\\u002F/g, '/'); break; }
                re = /"downloadAddr"\s*:\s*"([^"]+)"/;
                m = re.exec(str);
                if (m) { found = m[1].replace(/\\\\u002F/g, '/'); break; }
            } catch(e) {}
        }
    }
    
    // --- Chiến lược 5: Quet toan bo script tags tim link MP4 ---
    if (!found) {
        var scripts = document.querySelectorAll('script:not([src])');
        for (var i = 0; i < scripts.length; i++) {
            var txt = scripts[i].textContent || '';
            var regs = [
                /"(https?:[^"]*\.mp4[^"]*?)"/,
                /"playUrl"\s*:\s*"([^"]+)"/,
                /"videoUrl"\s*:\s*"([^"]+)"/,
                /playAddr['"]\s*:\s*['"]([^'"]+)['"]/
            ];
            for (var r = 0; r < regs.length; r++) {
                var match = regs[r].exec(txt);
                if (match && match[1].includes('http')) {
                    found = match[1].replace(/\\\//g, '/');
                    break;
                }
            }
            if (found) break;
        }
    }
    
    // --- Gửi kết quả về Kotlin ---
    if (found) {
        NasExtractor.onVideoFound(found);
    } else {
        var counts = 'video=' + document.querySelectorAll('video').length;
        NasExtractor.onStatusUpdate('Chưa tìm thấy (' + counts + ')');
    }
})();
""".trimIndent()

// ═══════════════════════════════════════════════════════════════════════════════
// Sub-Components UI
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ModeExplainCard(isPipeMode: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPipeMode) SeAccentPink.copy(alpha = 0.07f)
                             else SeAccentCyan.copy(alpha = 0.07f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPipeMode) Icons.Default.Bolt else Icons.Default.Cloud,
                    null,
                    tint = if (isPipeMode) SeAccentPink else SeAccentCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isPipeMode) "Chế độ truyền trực tiếp" else "Chế độ NAS tự tải",
                    fontWeight = FontWeight.Bold,
                    color = if (isPipeMode) SeAccentPink else SeAccentCyan,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (isPipeMode)
                    "WebView ẩn lấy liên kết → điện thoại truyền dữ liệu CDN về WebDAV NAS.\nĐiện thoại không lưu tệp cục bộ. Tốc độ phụ thuộc mạng LAN/Tailscale."
                else
                    "Gửi liên kết về NAS → NAS dùng yt-dlp tải nền → lưu vào ổ cứng 4TB.\nĐiện thoại không cần tiếp tục chạy sau khi NAS nhận lệnh.",
                fontSize = 12.sp,
                color = SeTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ModeSwitchRow(isPipeMode: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Truyền trực tiếp
        ModeTab(
            label = "⚡ Truyền trực tiếp",
            desc = "Điện thoại truyền",
            selected = isPipeMode,
            gradientColors = listOf(SeAccentPink, SeAccentPurple),
            modifier = Modifier.weight(1f),
            onClick = { if (!isPipeMode) onToggle() }
        )
        // NAS tự tải
        ModeTab(
            label = "☁️ yt-dlp",
            desc = "NAS tự tải",
            selected = !isPipeMode,
            gradientColors = listOf(SeAccentCyan, Color(0xFF0097A7)),
            modifier = Modifier.weight(1f),
            onClick = { if (isPipeMode) onToggle() }
        )
    }
}

@Composable
private fun ModeTab(
    label: String, desc: String, selected: Boolean,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) Brush.horizontalGradient(gradientColors)
                else Brush.horizontalGradient(listOf(SeDarkCard, SeDarkCard))
            )
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else SeTextSecondary.copy(0.2f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else SeTextSecondary)
            Text(desc, fontSize = 10.sp,
                color = if (selected) Color.White.copy(0.75f) else SeTextSecondary.copy(0.6f))
        }
    }
}

@Composable
private fun LinkInputCard(
    link: String,
    onLinkChange: (String) -> Unit,
    onPasteClipboard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SeDarkCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Link video", fontSize = 12.sp, color = SeTextSecondary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = link,
                onValueChange = onLinkChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Dán link TikTok / Facebook / YouTube...", color = SeTextSecondary, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Link, null, tint = SeTextSecondary, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (link.isNotEmpty()) {
                        IconButton(onClick = { onLinkChange("") }) {
                            Icon(Icons.Default.Clear, null, tint = SeTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        IconButton(onClick = onPasteClipboard) {
                            Icon(Icons.Default.ContentPaste, null, tint = SeAccentCyan, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                minLines = 2, maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SeAccentCyan,
                    unfocusedBorderColor = SeTextSecondary.copy(alpha = 0.3f),
                    cursorColor = SeAccentCyan,
                    focusedTextColor = SeTextPrimary,
                    unfocusedTextColor = SeTextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )
            if (link.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                val platform = detectPlatform(link)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(platformColor(platform).copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (platform != "Không rõ") "▶ $platform" else "⚠️ URL không nhận dạng được",
                        fontSize = 10.sp,
                        color = if (platform != "Không rõ") SeAccentCyan else SeAccentOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DestFolderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SeDarkCardAlt.copy(0.5f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = Color(0xFFFFCA28), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Lưu vào NAS:", fontSize = 10.sp, color = SeTextSecondary)
            Text(
                WebDavManager.currentBaseUrl + AppConfig.SOCIAL_DOWNLOAD_FOLDER,
                fontSize = 11.sp, color = SeAccentCyan,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExtractionStatusCard(status: String, isLoading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E3F)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = SeAccentPink, modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.FindInPage, null, tint = SeAccentPink, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(status, color = SeTextPrimary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun StreamPipeProgressCard() {
    val livestreamVM = LocalLivestreamVM.current
    val status = livestreamVM.streamPipeStatus
    val isError = status.contains("Lỗi", ignoreCase = true)
    val isDone  = status.contains("Hoàn tất", ignoreCase = true)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> SeAccentRed.copy(0.1f)
                isDone  -> SeAccentGreen.copy(0.1f)
                else    -> SeAccentOrange.copy(0.08f)
            }
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when { isError -> SeAccentRed.copy(0.3f); isDone -> SeAccentGreen.copy(0.3f); else -> SeAccentOrange.copy(0.2f) }
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (livestreamVM.isStreamPiping) {
                    CircularProgressIndicator(
                        color = SeAccentOrange, modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                        null,
                        tint = if (isError) SeAccentRed else SeAccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(status, color = SeTextPrimary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            }

            // Progress bar
            if (livestreamVM.isStreamPiping && livestreamVM.streamPipeProgress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { livestreamVM.streamPipeProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = SeAccentOrange,
                    trackColor = SeTextSecondary.copy(0.2f)
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${(livestreamVM.streamPipeProgress * 100).toInt()}% — ${livestreamVM.streamPipeSpeedStr}",
                        fontSize = 11.sp, color = SeTextSecondary
                    )
                    Text(
                        "ETA: ${livestreamVM.streamPipeEtaStr}",
                        fontSize = 11.sp, color = SeTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialStatusCard(status: String, isLoading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.contains("Lỗi", ignoreCase = true))
                SeAccentRed.copy(0.1f) else SeAccentCyan.copy(0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) CircularProgressIndicator(color = SeAccentCyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Cloud, null, tint = SeAccentCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(status, color = SeTextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun HistoryCard(history: List<SocialDownloadItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SeDarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Lịch sử gần đây", fontSize = 12.sp, color = SeTextSecondary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            history.takeLast(5).reversed().forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (item.isSuccess) SeAccentGreen else SeAccentRed)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.url.take(48) + if (item.url.length > 48) "…" else "",
                            fontSize = 11.sp, color = SeTextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            Text(item.platform, fontSize = 10.sp, color = platformColor(item.platform))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (item.isSuccess) "✓ OK" else "✗ Thất bại",
                                fontSize = 10.sp,
                                color = if (item.isSuccess) SeAccentGreen else SeAccentRed
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActionButton(
    isPipeMode: Boolean,
    isWorking: Boolean,
    isEnabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val gradient = if (isPipeMode)
        Brush.horizontalGradient(listOf(SeAccentPink, SeAccentPurple))
    else
        Brush.horizontalGradient(listOf(SeAccentCyan, Color(0xFF0097A7)))

    val disabledGradient = Brush.horizontalGradient(
        listOf(SeTextSecondary.copy(0.2f), SeTextSecondary.copy(0.2f))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isEnabled || isWorking) gradient else disabledGradient)
            .clickable(
                enabled = isEnabled || isWorking,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isWorking) onCancel() else onStart()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isWorking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Đang xử lý... (Nhấn để Hủy)", color = Color.White, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPipeMode) Icons.Default.SwapHoriz else Icons.Default.CloudUpload,
                    null, tint = Color.White, modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isPipeMode) "⚡ Bắt đầu truyền" else "☁️ Gửi về NAS",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────
// Allowlist host (suffix) cho cac nen tang ho tro. Dung de:
//  - validate link nguoi dung dan (chong URL gia mao kieu evil.com/?x=tiktok.com)
//  - gioi han pham vi tiem JS / cau noi NasExtractor chi tren domain hop le
private val SOCIAL_HOST_ALLOWLIST = listOf(
    "tiktok.com", "fb.watch", "facebook.com", "youtube.com", "youtu.be", "instagram.com"
)

private fun extractHost(url: String): String? {
    val raw = url.trim()
    if (raw.isEmpty()) return null
    return try {
        var host = java.net.URI(raw).host
        if (host.isNullOrBlank() && !raw.contains("://")) {
            // Nguoi dung dan link khong co scheme -> them https:// roi parse lai
            host = java.net.URI("https://$raw").host
        }
        host?.lowercase()
    } catch (e: Exception) {
        null
    }
}

/** Host co thuoc allowlist khong (khop chinh xac hoac la subdomain). */
fun isAllowedSocialHost(url: String): Boolean {
    val host = extractHost(url) ?: return false
    return SOCIAL_HOST_ALLOWLIST.any { host == it || host.endsWith(".$it") }
}

fun isSocialUrl(url: String): Boolean = isAllowedSocialHost(url)

private fun detectPlatform(url: String): String {
    val l = url.lowercase()
    return when {
        l.contains("tiktok") || l.contains("vm.tiktok") -> "TikTok"
        l.contains("facebook") || l.contains("fb.watch") -> "Facebook"
        l.contains("youtube") || l.contains("youtu.be") -> "YouTube"
        l.contains("instagram") -> "Instagram"
        l.startsWith("http") -> "Khác"
        else -> "Không rõ"
    }
}

private fun platformColor(platform: String): Color = when (platform) {
    "TikTok"   -> Color(0xFF69C9D0)
    "Facebook" -> Color(0xFF1877F2)
    "YouTube"  -> Color(0xFFFF0000)
    "Instagram"-> Color(0xFFE1306C)
    else       -> Color(0xFF8892B0)
}
