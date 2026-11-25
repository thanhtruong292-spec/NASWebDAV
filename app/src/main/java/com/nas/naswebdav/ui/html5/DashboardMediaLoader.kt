package com.nas.naswebdav.ui.html5

import android.os.SystemClock
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.toApiBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal object DashboardMediaLoader {
    private const val MAX_JPEG_BYTES = 512 * 1024
    private const val TTL_MS = 120_000L
    private data class Thumbnail(val bytes: ByteArray, val expires: Long, val headers: Map<String, String>) {
        fun response() = WebResourceResponse("image/jpeg", null, 200, "OK", headers, bytes.inputStream())
    }

    private val thumbs = object : LruCache<String, Thumbnail>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Thumbnail) = value.bytes.size + 512
    }
    private var session: WebDavManager.AuthState? = null
    private fun OkHttpClient.withoutRedirects() = newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val thumbClient by lazy { NasApplication.instance.thumbnailApiClient.withoutRedirects() }
    private val mediaClient by lazy { NasApplication.instance.videoStreamingClient.withoutRedirects() }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.encodedPath != "/api/thumb" && uri.encodedPath != "/api/media") return null
        val auth = WebDavManager.currentAuthState()
        val origin = auth.baseUrl.toApiBaseUrl().toHttpUrlOrNull() ?: return null
        val target = uri.toString().toHttpUrlOrNull() ?: return null
        if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port ||
            target.encodedUsername.isNotEmpty() || target.encodedPassword.isNotEmpty()) return null
        val method = request.method
        if (method != "GET" && method != "HEAD") return failure(405, "Method Not Allowed")
        val url = target.newBuilder().removeAllQueryParameters("auth").fragment(null).build()
        val isThumb = target.encodedPath == "/api/thumb"
        val builder = Request.Builder().url(url).method(method, null)
        if (auth.user.isNotEmpty()) builder.header("Authorization", auth.authHeader)
        val headers = request.requestHeaders.orEmpty()
        for (header in listOf("Range", "If-Range", "Accept")) {
            headers.entries.firstOrNull { it.key.equals(header, ignoreCase = true) }?.let { builder.header(header, it.value) }
        }
        val outbound = builder.build()
        val cacheable = isThumb && method == "GET" && outbound.header("Range") == null && !url.queryParameter("v").isNullOrBlank()
        val key = url.toString()
        if (cacheable) synchronized(thumbs) {
            if (session !== auth) {
                thumbs.evictAll()
                session = auth
            }
            val hit = thumbs.get(key)
            if (hit != null && hit.expires > SystemClock.elapsedRealtime()) return hit.response()
            if (hit != null) thumbs.remove(key)
        }
        return try {
            val client = if (isThumb || auth.user.isEmpty()) thumbClient else mediaClient
            val response = client.newCall(outbound).execute()
            var transferred = false
            try {
                if (response.code in 300..399) return failure(502, "Unexpected Redirect")
                if (WebDavManager.currentAuthState() !== auth) return failure(401, "Session Changed")
                val body = response.body
                val type = body?.contentType()
                val mime = if (type == null) "application/octet-stream" else "${type.type}/${type.subtype}"
                val responseHeaders = response.headers.names().associateWith { response.header(it).orEmpty() }
                if (cacheable && response.code == 200 && mime == "image/jpeg" && !response.cacheControl.noStore &&
                    body != null && body.contentLength() in 4L..MAX_JPEG_BYTES.toLong()) {
                    val bytes = body.bytes()
                    val jpeg = Thumbnail(bytes, SystemClock.elapsedRealtime() + TTL_MS, responseHeaders)
                    if (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                        bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) {
                        synchronized(thumbs) { if (session === auth) thumbs.put(key, jpeg) }
                    }
                    return jpeg.response()
                }
                val stream = if (method == "HEAD") null else body?.byteStream()
                val result = WebResourceResponse(mime, type?.charset()?.name(), response.code,
                    response.message.takeIf { it.isNotBlank() && it.all { ch -> ch.code in 32..126 } } ?: "HTTP ${response.code}",
                    responseHeaders, stream)
                transferred = stream != null // WebView closes the response body via this stream.
                result
            } finally {
                if (!transferred) response.close()
            }
        } catch (_: IOException) {
            failure(502, "NAS Unavailable")
        }
    }

    private fun failure(code: Int, reason: String) =
        WebResourceResponse("text/plain", "UTF-8", code, reason, mapOf("Cache-Control" to "no-store"), null)
}
