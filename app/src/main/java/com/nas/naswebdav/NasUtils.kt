package com.nas.naswebdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit

/**
 * NasUtils.kt — Top-level utility functions extracted from WebDavViewModel
 *                in Phase 7d.7 (VM facade deletion).
 *
 * These were previously standalone declarations in the same file as the
 * WebDavViewModel class. Moving them here lets other files continue
 * importing them after the facade file is deleted.
 */

// ─── Error Handling ──────────────────────────────────────────────────────

// Regex for parsing WebDAV error messages like "GET failed: 401 - Unauthorized"
private val WEB_DAV_HTTP_FAILURE_REGEX = Regex("""^([A-Z]+) failed: (\d{3})(?: - (.*))?$""")

// Safe URL host extraction — avoids MalformedURLException when URL is empty/malformed
internal fun safeUrlHost(url: String): String = try {
    java.net.URL(url).host ?: ""
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }

// Convert technical errors into user-friendly messages
internal fun friendlyError(e: Exception): String = when (e) {
    is java.net.SocketTimeoutException -> "Kết nối tới NAS quá chậm hoặc NAS không phản hồi. Vui lòng kiểm tra mạng."
    is java.net.ConnectException -> "Không thể kết nối tới NAS. Kiểm tra NAS đã bật và cùng mạng WiFi."
    is java.net.UnknownHostException -> "Địa chỉ NAS không hợp lệ hoặc mất kết nối mạng."
    is javax.net.ssl.SSLException -> "Lỗi bảo mật kết nối. Kiểm tra cấu hình SSL/TLS của NAS."
    is java.io.IOException -> {
        val match = WEB_DAV_HTTP_FAILURE_REGEX.find(e.message.orEmpty())
        if (match != null) {
            val method = match.groupValues[1]
            val code = match.groupValues[2]
            val detail = match.groupValues.getOrNull(3)?.trim().orEmpty()
            when (code) {
                "401" -> "NAS từ chối xác thực (HTTP 401). Kiểm tra tài khoản/mật khẩu."
                "403" -> "NAS từ chối quyền thao tác (HTTP 403)."
                "404" -> "Không tìm thấy file/thư mục trên NAS (HTTP 404)."
                "405" -> "WebDAV không hỗ trợ lệnh $method (HTTP 405)."
                "409" -> "Xung đột trên NAS (HTTP 409)."
                "423" -> "Đối tượng đang bị khóa trên NAS (HTTP 423)."
                else -> "NAS trả về lỗi HTTP $code${if (detail.isNotBlank()) ": $detail" else ""}"
            }
        } else {
            e.message ?: "Lỗi không xác định"
        }
    }
    else -> e.message ?: "Lỗi không xác định"
}

// Check if a throwable represents a transient network failure
internal fun Throwable.isTransientNetworkFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.io.InterruptedIOException -> return true
        }
        val message = current.message.orEmpty()
        if (WEB_DAV_HTTP_FAILURE_REGEX.containsMatchIn(message)) return false
        val lower = message.lowercase()
        if (listOf(
                "timeout", "timed out", "failed to connect",
                "connection refused", "connection reset",
                "network is unreachable", "no route to host",
                "broken pipe", "socket closed", "unexpected end of stream",
                "unable to resolve host", "name not resolved"
            ).any { it in lower }) {
            return true
        }
        current = current.cause
    }
    return false
}

// ─── URL / Offline Queue ────────────────────────────────────────────────

private fun String.toOfflineQueuePath(baseUrl: String): String {
    val normalizedBase = baseUrl.trimEnd('/')
    return if (startsWith(normalizedBase)) {
        removePrefix(normalizedBase).trimStart('/')
    } else {
        this
    }
}

// ─── URL / Timeout Utilities ─────────────────────────────────────────────

/**
 * Kiểm tra URL có trỏ đến một địa chỉ Tailscale hay không.
 * Tailscale dùng dải CGNAT 100.64.0.0/10 (octet 2 từ 64 đến 127).
 */
fun isTailscaleUrl(url: String): Boolean {
    if (url.isBlank()) return false
    if (url.contains("tailscale", ignoreCase = true)) return true
    return try {
        val host = safeUrlHost(url)
        if (host.isBlank()) return false
        val parts = host.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            a == 100 && b in 64..127
        } else false
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
}

/** Stable LAN-first order for connection probes; preserve order within each route type. */
internal fun prioritizeConnectionUrls(urls: List<String>, preferredUrl: String? = null): List<String> =
    urls.filter { it.isNotBlank() }.distinct()
        .sortedWith(compareBy<String>({ isTailscaleUrl(it) }, { it != preferredUrl }))

private val knownLatencyMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

internal fun adaptiveTimeoutMs(url: String): Long {
    val host = safeUrlHost(url)
    val saved = knownLatencyMs[host]
    if (saved != null) return (saved * 3).coerceIn(400, 3_500)
    return if (isTailscaleUrl(url)) 2_500L else 1_500L
}

internal fun recordLatency(url: String, ms: Long) {
    val host = safeUrlHost(url)
    if (host.isBlank()) return
    knownLatencyMs[host] = ms
}

// ─── Login Failure Message ───────────────────────────────────────────────

internal fun buildLoginFailureMessage(urlList: List<String>, errorDetails: List<String>): String {
    if (errorDetails.isEmpty()) {
        return "Không đăng nhập được. Kiểm tra tài khoản, mật khẩu hoặc dịch vụ WebDAV."
    }
    val lines = errorDetails.map { detail ->
        val colonIdx = detail.indexOf(": ")
        val rawUrl = if (colonIdx > 0) detail.take(colonIdx) else detail
        val rawReason = if (colonIdx > 0) detail.substring(colonIdx + 2).take(110).trim() else "không xác định"
        val host = safeUrlHost(rawUrl).takeIf { it.isNotBlank() } ?: rawUrl
        val niceReason = when {
            rawReason.contains("WebDAV", ignoreCase = true) -> "WebDAV quá hạn hoặc chưa xác thực"
            rawReason.contains("timeout", ignoreCase = true) || rawReason.contains("quá hạn", ignoreCase = true) || rawReason.contains("timed out", ignoreCase = true) -> "Mạng quá hạn / không phản hồi"
            rawReason.contains("Unable to resolve", ignoreCase = true) || rawReason.contains("UnknownHost", ignoreCase = true) -> "Không tìm thấy host"
            rawReason.contains("ECONNREFUSED", ignoreCase = true) || rawReason.contains("refused", ignoreCase = true) -> "Kết nối bị từ chối"
            rawReason.contains("ENETUNREACH", ignoreCase = true) || rawReason.contains("unreachable", ignoreCase = true) -> "Mạng không thể tiếp cận"
            else -> rawReason.trimEnd('.')
        }
        "• $host — $niceReason"
    }
    val header = if (lines.size > 1) "Không đăng nhập được NAS (đã thử ${lines.size} địa chỉ):" else "Không đăng nhập được NAS:"
    return "$header\n" + lines.joinToString("\n")
}

// ─── Wake-on-LAN / MAC Utilities ────────────────────────────────────────

internal fun normalizeWakeOnLanMac(raw: String): String? {
    val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.uppercase()
    if (hex.length != 12) return null
    return hex.chunked(2).joinToString(":")
}

internal fun parseOmvNetwork(netArr: org.json.JSONArray): List<OmvNetworkInfo> =
    (0 until netArr.length()).map { i ->
        val n = netArr.getJSONObject(i)
        OmvNetworkInfo(
            n.optString("name"),
            n.optString("address"),
            n.optString("mac"),
            n.optInt("speed", -1),
            n.optString("state"),
            n.optString("gateway"),
            n.optBoolean("wol")
        )
    }

internal fun persistDetectedWakeOnLanMac(network: List<OmvNetworkInfo>): String? {
    val adapter = network.firstOrNull {
        it.name != "lo" && it.wol && normalizeWakeOnLanMac(it.mac) != null
    } ?: network.firstOrNull {
        it.name != "lo" && normalizeWakeOnLanMac(it.mac) != null
    } ?: return null
    val detectedMac = normalizeWakeOnLanMac(adapter.mac) ?: return null
    val prefs = NasApplication.instance.applicationContext.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
    val currentMac = normalizeWakeOnLanMac(prefs.getString("mac_address", "") ?: "")
    if (currentMac != detectedMac) {
        prefs.edit { putString("mac_address", detectedMac) }
    }
    return detectedMac
}

// ─── Thumbnail Download ──────────────────────────────────────────────────

private fun executeThumbDownload(apiThumbUrl: String, auth: String, thumbFile: java.io.File, isVideo: Boolean): Boolean {
    val apiRequest = okhttp3.Request.Builder()
        .url(apiThumbUrl)
        .header("Authorization", auth)
        .build()
    NasApplication.instance.thumbnailApiClient.newCall(apiRequest).execute().use { apiResponse ->
        // FIX-THUMB-SPINNER: server returns application/json for errors (503 busy,
        // 400 missing path, 404 not found). Only accept image responses — prevents
        // writing error JSON to thumbFile which then blocks retry via exists() check.
        val contentType = apiResponse.header("Content-Type") ?: ""
        if (!apiResponse.isSuccessful || apiResponse.body == null) return false
        if (!contentType.contains("image/")) return false

        apiResponse.body?.byteStream()?.use { input ->
            java.io.FileOutputStream(thumbFile).use { out -> input.copyTo(out) }
        } ?: return false
        return thumbFile.length() > 0
    }
}

suspend fun downloadThumbnailFromNas(url: String, thumbFile: java.io.File, auth: String, isVideo: Boolean): Boolean = withContext(Dispatchers.IO) {
    try {
        // CC-004 P1-3: encode theo segment (giu '/') thay vi URLEncoder.encode
        // ca path (-> %2F) + replace space tho (vo voi '#?&%'). Khop backend
        // quote(rel, safe="/") va toValidUrl/encodeWebDavSegment.
        val normalized = com.nas.naswebdav.toValidUrl(url)
        val webdavPath = runCatching { java.net.URL(normalized).path }.getOrNull()
            ?: runCatching { android.net.Uri.parse(normalized).path }.getOrNull()
            ?: normalized.substringAfter("8822", "").substringAfter("5050", "")
        val encodedPath = (webdavPath ?: "").split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else runCatching {
                java.net.URLEncoder.encode(
                    runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment),
                    "UTF-8"
                ).replace("+", "%20")
            }.getOrDefault(segment)
        }
        val apiThumbUrl = "${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb?path=$encodedPath"

        // CRITICAL FIX: giam toi da xuong 2 lan thu. Lan thu 1 bat dau tao anh thu nho,
        // lan thu 2 kiem tra da co chua. Khong bao giu retry ngay lap tuc khi NAS tra
        // 503/retry_later -> phai poll /api/thumb/status thay vi goi lai /api/thumb.
        val maxAttempts = 2
        var retryDelayMs = 4000L
        for (attempt in 1..maxAttempts) {
            if (executeThumbDownload(apiThumbUrl, auth, thumbFile, isVideo)) return@withContext true
            if (attempt < maxAttempts) {
                // Neu server tra 503 co retry_later -> poll /api/thumb/status thay vi retry ngay.
                val shouldPoll = runCatching {
                    val body = org.json.JSONObject(responseBodyOf(apiThumbUrl, auth))
                    body.optBoolean("retry_later", false)
                }.getOrDefault(false)
                if (shouldPoll) {
                    // Poll trang thai den 2 lan, cach nhau 3s, neu xong moi goi lai /api/thumb.
                    for (poll in 1..2) {
                        kotlinx.coroutines.delay(3000L)
                        val statusOk = runCatching {
                            val body = org.json.JSONObject(responseBodyOf("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/status", auth))
                            body.optBoolean("running", false)
                        }.getOrDefault(false)
                        if (!statusOk) break // daemon da dung -> co the co anh thu nho moi
                    }
                } else {
                    kotlinx.coroutines.delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(8000L)
                }
            }
        }
        false
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
}

/** GET no body tra ve chuoi response (dung cho /api/thumb/status trong retry flow). */
private fun responseBodyOf(apiUrl: String, auth: String): String {
    val request = okhttp3.Request.Builder()
        .url(apiUrl)
        .header("Authorization", auth)
        .build()
    return NasApplication.instance.thumbnailApiClient.newCall(request).execute().use { response ->
        response.body?.string() ?: ""
    }
}

// ─── Rate Limiter ────────────────────────────────────────────────────────

class RateLimiter(private val maxRequestsPerMinute: Int) {
    private val requestTimestamps = ArrayDeque<Long>()

    @Synchronized
    fun isAllowed(): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L
        while (requestTimestamps.isNotEmpty() && requestTimestamps.first() < cutoff) {
            requestTimestamps.removeFirst()
        }
        if (requestTimestamps.size >= maxRequestsPerMinute) return false
        requestTimestamps.addLast(now)
        return true
    }
}
