@file:Suppress("DEPRECATION")
package com.nas.naswebdav

import android.content.Context
import com.nas.naswebdav.utils.WolUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * PowerActions — extracted from WebDavViewModel facade (Phase 7d.4).
 *
 * Wake-on-LAN + Power commands (shutdown/reboot/suspend) require their own
 * CoroutineScope. Pass any active scope (e.g. rememberCoroutineScope()).
 */

private fun extractHost(url: String): String? = try { URL(url).host } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

fun scheduleIdleDuplicateScan(context: Context, currentUrl: String) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val constraints = androidx.work.Constraints.Builder()
        .setRequiresDeviceIdle(true)
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
        .build()
    val inputData = androidx.work.workDataOf("currentUrl" to currentUrl)
    val periodicScanRequest = androidx.work.PeriodicWorkRequestBuilder<DuplicateScanWorker>(
        168, java.util.concurrent.TimeUnit.HOURS
    ).setConstraints(constraints).setInputData(inputData).build()
    workManager.enqueueUniquePeriodicWork(
        "Auto_Idle_Duplicate_Scan",
        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
        periodicScanRequest
    )
}

fun scheduleIdleSpeedTest(context: Context, currentUrl: String) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val constraints = androidx.work.Constraints.Builder()
        .setRequiresDeviceIdle(true)
        .setRequiresCharging(true)
        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
        .build()
    val inputData = androidx.work.workDataOf("currentUrl" to currentUrl)
    val periodicSpeedTestRequest = androidx.work.PeriodicWorkRequestBuilder<IdleSpeedTestWorker>(
        30, java.util.concurrent.TimeUnit.DAYS
    ).setConstraints(constraints).setInputData(inputData).build()
    workManager.enqueueUniquePeriodicWork(
        "Auto_Idle_Speed_Test",
        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
        periodicSpeedTestRequest
    )
}

fun scheduleFingerprintWorker(context: Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val constraints = androidx.work.Constraints.Builder()
        .setRequiresDeviceIdle(true)
        .setRequiresCharging(true)
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
        .build()
    val periodicRequest = androidx.work.PeriodicWorkRequestBuilder<FingerprintWorker>(
        168, java.util.concurrent.TimeUnit.HOURS
    ).setConstraints(constraints).build()
    workManager.enqueueUniquePeriodicWork(
        "Auto_Fingerprint_Worker",
        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
        periodicRequest
    )
}

suspend fun pingUrlsForDisplay(urlList: List<String>, user: String, pass: String): Map<String, Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    coroutineScope {
        urlList.distinct().map { url ->
            async(Dispatchers.IO) {
                url to try {
                    val safeUrl = if (url.endsWith("/")) url else "$url/"
                    val timeoutMs = adaptiveTimeoutMs(safeUrl).toInt()
                    val uri = java.net.URI(safeUrl)
                    val host = uri.host ?: return@async url to -1L
                    val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
                    var best = Long.MAX_VALUE
                    repeat(if (isTailscaleUrl(url)) 1 else 3) {
                        val start = SystemClock.elapsedRealtime()
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                            socket.close()
                            best = minOf(best, SystemClock.elapsedRealtime() - start)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                    }
                    if (best == Long.MAX_VALUE) -1L else { recordLatency(url, best); best }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { -1L }
            }
        }.associate { it.await() }
    }
}

// ─── Send Power Command to NAS ─────────────────────────────────────────────────

fun sendPowerCommandToNas(
    scope: CoroutineScope,
    endpoint: String,
    onResult: ((Boolean, String) -> Unit)? = null
) {
    scope.launch(Dispatchers.IO) {
        try {
            val isSleepCommand = endpoint.contains("shutdown") || endpoint.contains("suspend")
            val cmdName = when {
                endpoint.contains("reboot") -> "Khởi động lại"
                isSleepCommand -> "Ngủ"
                else -> endpoint
            }
            val host = try { java.net.URI(WebDavManager.currentBaseUrl).host } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "?" }
            Log.w("Power", "Gửi lệnh $cmdName đến $host/api/$endpoint")
            val request = okhttp3.Request.Builder()
                .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/$endpoint")
                .post(ByteArray(0).toRequestBody(null, 0, 0))
                .build()
            NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                withContext(Dispatchers.Main) {
                    onResult?.invoke(ok, if (ok) "Đã gửi lệnh $cmdName NAS." else "NAS từ chối lệnh $cmdName (HTTP ${response.code}).")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult?.invoke(false, "Không gửi được lệnh nguồn: ${e.message ?: "lỗi mạng"}")
            }
        }
    }
}

// ─── Send Download Link to qBittorrent ────────────────────────────────────────

fun sendDownloadLinkToQbittorrent(
    scope: CoroutineScope,
    url: String,
    onResult: ((Boolean, String) -> Unit)? = null
) {
    scope.launch(Dispatchers.IO) {
        try {
            val jsonBody = org.json.JSONObject().apply { put("url", url) }.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/download")
                .post(jsonBody)
                .build()
            val text = NasApplication.instance.fastApiClient.newCall(request).execute().use { it.body?.string() ?: "" }
            val o = try { org.json.JSONObject(text) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { org.json.JSONObject() }
            withContext(Dispatchers.Main) {
                val ok = o.optString("result") == "ok"
                onResult?.invoke(ok, if (ok) "✅ Đã gửi link cho qBittorrent." else "❌ Lỗi: ${o.optString("error", "không phản hồi")}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult?.invoke(false, "❌ Lỗi mạng: ${e.message?.take(120)}")
            }
        }
    }
}

// ─── Upload Torrent File to NAS/qBittorrent ────────────────────────────────────

fun uploadTorrentFileToNas(
    scope: CoroutineScope,
    context: android.content.Context,
    uri: android.net.Uri,
    onResult: ((Boolean, String) -> Unit)? = null
) {
    scope.launch(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            var fileName = "uploaded.torrent"
            contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cur.moveToFirst() && idx >= 0) {
                    val n = cur.getString(idx)
                    if (!n.isNullOrBlank()) fileName = n
                }
            }
            val safeName = fileName.replace('/', '_').replace('\\', '_').take(200)
                .let { if (it.lowercase().endsWith(".torrent")) it else "$it.torrent" }

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Không đọc được nội dung file")
            if (bytes.size < 64) throw IllegalArgumentException("File .torrent quá nhỏ")
            if (bytes[0].toInt().toChar() != 'd') throw IllegalArgumentException("File không phải định dạng torrent hợp lệ")

            val mediaType = "application/x-bittorrent".toMediaTypeOrNull()
            val filePart = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", safeName, bytes.toRequestBody(mediaType, 0, bytes.size))
                .build()

            val req = okhttp3.Request.Builder()
                .url("${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/torrent/add_file")
                .post(filePart)
                .build()
            val client = NasApplication.instance.fastApiClient.newBuilder()
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            val text = client.newCall(req).execute().use { it.body?.string() ?: "" }
            val o = try { org.json.JSONObject(text) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { org.json.JSONObject() }
            withContext(Dispatchers.Main) {
                val ok = o.optString("result") == "ok"
                onResult?.invoke(ok, if (ok) "✅ Đã gửi $safeName cho qBittorrent (${o.optInt("size")} bytes)" else "❌ Lỗi: ${o.optString("error", "không phản hồi")}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult?.invoke(false, "❌ Lỗi: ${e.message?.take(120)}")
            }
        }
    }
}

// ─── Send WOL ──────────────────────────────────────────────────────────────────

fun sendWakeOnLanFromMenu(
    scope: CoroutineScope,
    macStr: String,
    onResult: ((WolUtil.WolResult) -> Unit)? = null
) {
    sendWakeOnLan(scope, macStr, null, onResult)
}

fun sendWakeOnLan(
    scope: CoroutineScope,
    macStr: String,
    targetHost: String? = null,
    onResult: ((WolUtil.WolResult) -> Unit)? = null
): Job {
    return scope.launch(Dispatchers.IO) {
        val preferredHost = targetHost?.trim()?.takeIf { it.isNotBlank() } ?: runCatching {
            extractHost(WebDavManager.currentBaseUrl)
        }.getOrNull()
        val result = WolUtil.smartWakeOnLan(macStr, preferredHost)
        Log.i("Power", if (result.success) "WOL success: ${result.message}" else "WOL failed: ${result.message}")
        withContext(Dispatchers.Main) { onResult?.invoke(result) }
    }
}

fun sendPowerCommandFromLogin(
    scope: CoroutineScope,
    ipInput: String,
    user: String,
    pass: String,
    endpoint: String,
    onResult: (Boolean, String) -> Unit
): Job {
    return scope.launch(Dispatchers.IO) {
        try {
            val trimmed = ipInput.trim()
            if (trimmed.isEmpty()) {
                withContext(Dispatchers.Main) { onResult(false, "Vui lòng nhập IP của NAS") }
                return@launch
            }
            val host = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                if (trimmed.startsWith("https://")) trimmed.removePrefix("https://") else trimmed.removePrefix("http://")
            } else trimmed.substringBefore(":")
            val apiUrl = "http://$host:${AppConfig.API_PORT}/api/$endpoint"
            val cmdName = when {
                endpoint.contains("reboot") -> "Khởi động lại"
                endpoint.contains("suspend") -> "Ngủ"
                endpoint.contains("shutdown") -> "Tắt nguồn"
                else -> endpoint
            }
            val reqBuilder = okhttp3.Request.Builder()
                .url(apiUrl)
                .post(ByteArray(0).toRequestBody(null, 0, 0))
            if (user.isNotBlank() && pass.isNotBlank()) {
                reqBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
            }
            val client = NasApplication.instance.fastApiClient.newBuilder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val ok = resp.isSuccessful
                val code = resp.code
                withContext(Dispatchers.Main) {
                    if (ok) onResult(true, "Đã gửi lệnh $cmdName NAS!")
                    else onResult(false, "NAS từ chối (HTTP $code) — kiểm tra IP/tài khoản/mật khẩu")
                }
                Log.w("Power", "LoginScreen: gửi $cmdName NAS tại $host (HTTP $code)")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(false, "Không kết nối được NAS: ${e.message?.take(80) ?: "lỗi mạng"}")
            }
        }
    }
}
