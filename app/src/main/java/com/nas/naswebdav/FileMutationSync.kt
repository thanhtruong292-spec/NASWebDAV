package com.nas.naswebdav

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL

/**
 * Dong bo mutation tep (create/upload/copy/rename/move/trash/restore/delete) len catalogue NAS
 * qua POST /api/files/sync_mutation; neu mang mat hoac NAS loi thi luu vao SyncActionDao
 * de OfflineSyncWorker tu retry sau khi co mang hoac khoi dong lai app.
 */
object FileMutationSync {
    internal fun normalizeRoot(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    const val ACTION_TYPE = "SYNC_MUTATION"

    /**
     * Contract danh cho URL (WebDAV / HTTP / HTTPS):
     * - Tach scheme, host, port.
     * - Cat query '?' va fragment '#'.
     * - Bo tien to '/webdav/'.
     * - Decode ma %HH (UTF-8), bao toan dau '+' literal cua path.
     */
    fun fromUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val pathPart = if (withoutQuery.contains("://")) {
            val afterScheme = withoutQuery.substringAfter("://")
            val slashIdx = afterScheme.indexOf('/')
            if (slashIdx != -1) afterScheme.substring(slashIdx) else ""
        } else {
            withoutQuery
        }
        var clean = pathPart.trim()
        if (clean.startsWith("/webdav/")) clean = clean.substring("/webdav/".length)
        else if (clean.startsWith("webdav/")) clean = clean.substring("webdav/".length)
        else if (clean == "/webdav") clean = ""
        if (clean.isEmpty()) return ""
        if (!clean.contains('%')) {
            return clean.trimStart('/').trimEnd('/')
        }
        val result = runCatching {
            java.net.URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(clean)
        return result.trimStart('/').trimEnd('/')
    }

    /**
     * Contract danh cho duong dan tho (raw path tu he thong tep, Room DB, hoac bao cao quet trung):
     * - TUYET DOI KHONG cat '?' hay '#' (day la ky tu literal hop le cua ten tep).
     * - TUYET DOI KHONG decode ma '%' (ky tu '%' nhu '50%.pdf' hay 'Sale 20% Off' la chuoi literal, khong phai URL encoding).
     * - Chuan hoa dau phan cach '\\' thanh '/'.
     * - Bo tien to '/webdav/' neu co va cat bo cac dau gach chao o dau/cuoi.
     */
    fun fromRawPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return ""
        var clean = trimmed.replace('\\', '/')
        if (clean.startsWith("/webdav/")) clean = clean.substring("/webdav/".length)
        else if (clean.startsWith("webdav/")) clean = clean.substring("webdav/".length)
        else if (clean == "/webdav") clean = ""
        return clean.trimStart('/').trimEnd('/')
    }

    /**
     * Contract dieu huong:
     * - Khi isRaw = false (mac dinh): coi la URL/WebDAV path, cat query/fragment va giai ma %HH.
     * - Khi isRaw = true: coi la duong dan thô, bao toan 100% literal ?, #, %, +.
     */
    fun toRelativePath(rawUrlOrPath: String, isRaw: Boolean = false): String {
        return if (isRaw) fromRawPath(rawUrlOrPath) else fromUrl(rawUrlOrPath)
    }

    fun sendNow(
        baseUrl: String,
        authHeader: String,
        action: String,
        oldUrl: String,
        newUrl: String? = null
    ): Boolean {
        val apiBase = baseUrl.toApiBaseUrl()
        if (apiBase.isBlank()) return false
        val oldRel = toRelativePath(oldUrl)
        val newRel = newUrl?.let { toRelativePath(it) }
        if (oldRel.isBlank() && newRel.isNullOrBlank()) return true
        return try {
            val bodyJson = JSONObject()
                .put("action", action)
                .put("old_path", oldRel.ifBlank { newRel ?: "" })
            if (!newRel.isNullOrBlank()) bodyJson.put("new_path", newRel)
            val req = okhttp3.Request.Builder()
                .url("$apiBase/api/files/sync_mutation")
                .header("Authorization", authHeader)
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()
            NasApplication.instance.fastApiClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                val okJson = runCatching { JSONObject(bodyStr).optBoolean("ok", false) }.getOrDefault(false)
                if (!resp.isSuccessful || !okJson) {
                    android.util.Log.w("FileMutationSync", "sync_mutation ($action $oldRel) failed: HTTP ${resp.code}")
                    false
                } else {
                    true
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FileMutationSync", "sync_mutation ($action $oldRel) error: ${e.message}")
            false
        }
    }

    fun notifyOrEnqueue(
        context: Context,
        baseUrl: String,
        authHeader: String,
        action: String,
        oldUrl: String,
        newUrl: String? = null
    ) {
        if (sendNow(baseUrl, authHeader, action, oldUrl, newUrl)) return
        val user = runCatching {
            require(authHeader.startsWith("Basic ", ignoreCase = true))
            val encoded = authHeader.substring(6)
            val credentials = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                .toString(Charsets.UTF_8)
            credentials.substringBefore(':', "").also { require(it.isNotEmpty()) }
        }.getOrElse {
            android.util.Log.w("FileMutationSync", "Cannot scope catalogue retry to an authenticated account")
            return
        }
        enqueueRetry(context, baseUrl, user, action, oldUrl, newUrl)
    }

    internal fun enqueueRetry(
        context: Context,
        baseUrl: String,
        user: String,
        action: String,
        oldUrl: String,
        newUrl: String? = null
    ) {
        runCatching {
            val parsed = URL(baseUrl)
            val host = parsed.host
            val port = if (parsed.port > 0) parsed.port else parsed.defaultPort
            require(host.isNotEmpty() && user.isNotEmpty() && port > 0)
            val rawPath = parsed.path
            // Chuong trinh chuan hoa root: URL khong co path (chi co host/port) duoc dua ve "/"
            // de khop voi OfflineSyncWorker.activeRoot, dam bao DAO lọc theo scope co the
            // tim lai row duoc enqueue o day va replay chinh xac tai khoan/endpoint.
            val root = normalizeRoot(rawPath)
            NasApplication.instance.database.syncActionDao().insert(
                SyncAction(
                    actionType = ACTION_TYPE,
                    sourcePath = "$action|$oldUrl",
                    destPath = newUrl,
                    nasHost = host,
                    nasUser = user,
                    nasPort = port,
                    nasRoot = root
                )
            )
            OfflineSyncWorker.schedule(context)
        }.onFailure { e ->
            android.util.Log.w("FileMutationSync", "Failed to enqueue SYNC_MUTATION retry: ${e.message}")
        }
    }
}
