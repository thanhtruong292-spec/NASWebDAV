package com.nas.naswebdav

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val fullDigestPattern = Regex("[0-9a-f]{64}")

// Chỉ encode các byte non-ASCII (>= 0x80, ví dụ chữ tiếng Việt) sang %XX để
// java.net.URI (chỉ chấp nhận ASCII) không ném IllegalArgumentException. ASCII
// (bao gồm cả chuỗi đã %-encode sẵn) giữ nguyên -> không bị mã hóa kép.
private fun encodeNonAscii(s: String): String {
    val sb = StringBuilder()
    for (byte in s.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        if (b < 0x80) sb.append(byte.toChar())
        else sb.append("%${b.toString(16).uppercase().padStart(2, '0')}")
    }
    return sb.toString()
}

internal fun reusableVerifiedHash(value: VerifiedHashCache?, account: String, path: String, size: Long, version: String, fileId: String): Boolean =
    value != null && account.isNotBlank() && version.isNotBlank() && fileId.isNotBlank() &&
        value.accountKey == account && value.path == path && value.contentLength == size &&
        value.version == version && value.fileId == fileId && fullDigestPattern.matches(value.sha256)

internal fun relativeDuplicatePath(base: String, url: String): String? = runCatching {
    val root = java.net.URI(encodeNonAscii(base)).normalize()
    val target = java.net.URI(encodeNonAscii(url)).normalize()
    val prefix = root.path.trimEnd('/') + "/"
    if (root.scheme != target.scheme || root.host != target.host || root.port != target.port ||
        target.userInfo != null || target.query != null || target.fragment != null || !target.path.startsWith(prefix)) return null
    target.path.removePrefix(prefix).takeIf { path ->
        path.isNotBlank() && '\\' !in path && path.split('/').none { it == "." || it == ".." || it.isEmpty() }
    }
}.getOrNull()

private val duplicateRpcClient by lazy {
    NasApplication.instance.fastApiClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
}

private val duplicateComputeHashClient by lazy {
    NasApplication.instance.fastApiClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

internal suspend fun WebDavManager.duplicateRpc(route: String, body: JSONObject, auth: WebDavManager.AuthState): JSONObject? {
    currentCoroutineContext().ensureActive()
    val base = auth.baseUrl.toApiBaseUrl()
    if (base.isBlank()) return null
    val request = okhttp3.Request.Builder().url(base.trimEnd('/') + "/api/duplicates/" + route)
        .tag(WebDavManager.AuthState::class.java, auth)
        .header("Authorization", auth.authHeader)
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    return try {
        duplicateRpcClient.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            JSONObject(response.body?.string() ?: return null)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("DuplicateRpc", "RPC $route failed: ${e.message}")
        null
    }
}

private suspend fun WebDavManager.serverSideSha256(path: String, size: Long, version: String, fileId: String, auth: WebDavManager.AuthState): String? {
    currentCoroutineContext().ensureActive()
    val base = auth.baseUrl.toApiBaseUrl()
    if (base.isBlank()) return null
    val request = okhttp3.Request.Builder()
        .url(base.trimEnd('/') + "/api/duplicates/compute_hash")
        .tag(WebDavManager.AuthState::class.java, auth)
        .header("Authorization", auth.authHeader)
        .post(JSONObject().put("path", path).put("version", version).toString().toRequestBody("application/json".toMediaType()))
        .build()
    return try {
        duplicateComputeHashClient.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            val json = runCatching { JSONObject(response.body?.string() ?: return null) }.getOrNull() ?: return null
            if (json.optString("version") != version || json.optString("file_id") != fileId || json.optLong("size", -1) != size) return null
            json.optString("sha256").takeIf(fullDigestPattern::matches)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("DuplicateRpc", "serverSideSha256 failed: ${e.message}")
        null
    }
}

internal suspend fun hashCompleteResponse(response: okhttp3.Response, size: Long): String? {
    if (response.code != 200) return null
    val stream = response.body?.byteStream() ?: return null
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    var count = 0L
    stream.use {
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = it.read(buffer)
            if (read == -1) break
            count += read
            if (count > size) return null
            digest.update(buffer, 0, read)
        }
    }
    if (count != size) return null
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal suspend fun WebDavManager.getCachedVerifiedSha256(url: String, size: Long): Pair<String, String>? {
    val auth = currentAuthState()
    val account = currentAccountKey()
    if (auth != currentAuthState()) return null
    val path = relativeDuplicatePath(auth.baseUrl, url) ?: return null
    if (account.isBlank()) return null
    val db = NasApplication.instance.database
    fun invalidate() {
        db.fileDao().invalidateVerifiedHash(url, account)
        QueryCache.invalidate("duplicates")
    }
    val before = duplicateRpc("file", JSONObject().put("path", path), auth) ?: run { invalidate(); return null }
    val version = before.optString("version")
    val fileId = before.optString("file_id")
    if (before.optLong("size", -1) != size || !fullDigestPattern.matches(version) || fileId.isBlank()) { invalidate(); return null }
    val cached = db.verifiedHashDao().get(account, url)
    var hash = before.optString("sha256").takeIf(fullDigestPattern::matches)
    var savedOnServer = hash != null
    if (hash == null && reusableVerifiedHash(cached, account, url, size, version, fileId)) hash = cached!!.sha256
    if (hash == null) {
        invalidate()
        val serverHash = if (before.optBoolean("server_side_hash", false)) {
            serverSideSha256(path, size, version, fileId, auth)
        } else null
        if (serverHash != null) {
            hash = serverHash
            savedOnServer = true
        } else {
            return null
        }
    }
    if (!savedOnServer && before.optString("sha256") != hash) {
        val saved = duplicateRpc("hash", JSONObject().put("path",path).put("version",version).put("sha256",hash), auth)
        if (saved?.optBoolean("saved") != true) { invalidate(); return null }
    }
    if (currentAccountKey() != account) return null
    if (!reusableVerifiedHash(cached, account, url, size, version, fileId) || cached?.sha256 != hash) {
        db.verifiedHashDao().put(VerifiedHashCache(account,url,size,version,hash,fileId))
    }
    val file = db.fileDao().getFileByPath(url, account)
    if (file != null && file.contentLength == size && file.fullHash != hash) {
        db.fileDao().updateVerifiedFullHash(url,hash,size,file.lastModified,account)
        QueryCache.invalidate("duplicates")
    }
    return hash to version
}

internal fun canonicalDuplicatePath(path: String): String = runCatching { java.net.URI(path).normalize().path }.getOrNull().orEmpty()

internal fun planVerifiedDuplicateCleanup(files: List<CachedFile>, selected: Set<String>): List<Pair<CachedFile,CachedFile>> =
    files.filter { !it.isDirectory && it.fullHash?.let(fullDigestPattern::matches) == true }
        .distinctBy { canonicalDuplicatePath(it.path) }
        .filter { canonicalDuplicatePath(it.path).isNotBlank() && ".trash" !in canonicalDuplicatePath(it.path).split('/') }
        .groupBy { Triple(it.accountKey,it.fullHash,it.contentLength) }.values.flatMap { group ->
            val keeper = group.sortedWith(compareBy<CachedFile>({ it.path in selected },{ it.path.length },{ it.path })).first()
            group.filter { it.path in selected && it.path != keeper.path }.map { it to keeper }
        }

internal suspend fun WebDavManager.deleteVerifiedDuplicate(victim: CachedFile, keeper: CachedFile, expectedHash: String, maintenance: NasDuplicateLease? = null): Boolean {
    if (!fullDigestPattern.matches(expectedHash) || victim.contentLength != keeper.contentLength ||
        canonicalDuplicatePath(victim.path) == canonicalDuplicatePath(keeper.path)) return false
    val auth = currentAuthState()
    val base = auth.baseUrl
    val victimPath = relativeDuplicatePath(base,victim.path) ?: return false
    val keeperPath = relativeDuplicatePath(base,keeper.path) ?: return false
    val account = currentAccountKey()
    maintenance?.ensureValid()
    val keeperVersion = getCachedVerifiedSha256(keeper.path,keeper.contentLength) ?: return false
    maintenance?.ensureValid()
    val victimVersion = getCachedVerifiedSha256(victim.path,victim.contentLength) ?: return false
    maintenance?.ensureValid()
    if (keeperVersion.first != expectedHash || victimVersion.first != expectedHash || currentAccountKey() != account) return false
    val body = JSONObject().put("victim",victimPath).put("keeper",keeperPath)
        .put("victim_version",victimVersion.second).put("keeper_version",keeperVersion.second).put("sha256",expectedHash)
    maintenance?.let {body.put("work_lease",it.cleanupPermit())}
    val result = duplicateRpc("cleanup",body, auth) ?: return false
    if (!result.optBoolean("deleted") || result.optString("path") != victimPath || result.optString("keeper") != keeperPath) return false
    NasApplication.instance.database.fileDao().deleteFileForAccount(victim.path,account)
    NasApplication.instance.database.verifiedHashDao().remove(account,victim.path)
    QueryCache.invalidate("duplicates")
    return true
}

