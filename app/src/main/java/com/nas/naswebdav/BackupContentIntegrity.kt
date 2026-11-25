package com.nas.naswebdav

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.security.MessageDigest

internal enum class BackupContentState { MISSING, MATCH, DIFFERENT, UNVERIFIABLE }
internal data class BackupSourceDigest(val sha256: String, val version: String?)
internal data class BackupTargetPlan(val path: String, val skip: Boolean, val isContentMatch: Boolean = true)
internal data class SourceFileVersion(val size: Long, val fileId: String, val value: String)
private const val LOCAL_BACKUP_HASH_ACCOUNT = "@local-backup-source-v1"

internal fun sourceFileVersion(context: Context, uri: Uri): SourceFileVersion? {
    if (Build.VERSION.SDK_INT < 27) return null
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val stat = android.system.Os.fstat(pfd.fileDescriptor)
            if (!android.system.OsConstants.S_ISREG(stat.st_mode)) return null
            val fileId = if (stat.st_ino != 0L) "${stat.st_dev}:${stat.st_ino}" else "${stat.st_dev}:${stat.st_size}:${stat.st_mtim.tv_sec}"
            val versionVal = "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
            SourceFileVersion(stat.st_size, fileId, versionVal)
        }
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { null }
}

internal suspend fun backupSourceDigest(context: Context, uri: Uri, size: Long): BackupSourceDigest {
    val before = sourceFileVersion(context, uri)
    if (before != null && before.size != size) throw IOException("Source size changed before backup")
    val dao = NasApplication.instance.database.verifiedHashDao()
    val cached = dao.get(LOCAL_BACKUP_HASH_ACCOUNT, uri.toString())
    if (before != null && reusableVerifiedHash(cached, LOCAL_BACKUP_HASH_ACCOUNT, uri.toString(), size, before.value, before.fileId)) {
        return BackupSourceDigest(cached!!.sha256, before.value)
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Source unavailable")
    input.use {
        val buffer = ByteArray(65536)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = it.read(buffer)
            if (read < 0) break
            count += read
            if (count > size) throw IOException("Source grew during backup verification")
            digest.update(buffer, 0, read)
        }
    }
    if (count != size) throw IOException("Source length changed during backup verification")
    val after = sourceFileVersion(context, uri)
    if (before != after) throw IOException("Source changed during backup verification")
    val sha = digest.digest().joinToString("") { "%02x".format(it) }
    if (before != null) dao.put(VerifiedHashCache(LOCAL_BACKUP_HASH_ACCOUNT, uri.toString(), size, before.value, sha, before.fileId))
    return BackupSourceDigest(sha, before?.value)
}

private val backupHeadClient by lazy {
    NasApplication.instance.sharedHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
}

private val backupCleanupClient by lazy {
    NasApplication.instance.sharedHttpClient.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
}

internal fun backupSourceStillCurrent(context: Context, uri: Uri, proof: BackupSourceDigest): Boolean =
    proof.version == null || sourceFileVersion(context, uri)?.value == proof.version

private fun sourceMatchesDigestSync(context: Context, uri: Uri, expectedSha256: String): Boolean = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
    input.use { stream ->
        val buffer = ByteArray(65536)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256
}.getOrDefault(false)

/**
 * Xóa tệp nguồn trên điện thoại CHỈ KHI tệp đã được xác minh khớp SHA-256 với NAS
 * và chưa hề bị sửa đổi (kiểm tra fstat version trên API 27+, hoặc băm lại SHA-256 nếu
 * FUSE/MediaStore không cung cấp st_ino). Trên API < 27 giữ nguyên nguồn.
 */
internal fun deleteVerifiedBackupSource(
    context: Context,
    uri: Uri,
    dataPath: String,
    proof: BackupSourceDigest
): Boolean {
    if (Build.VERSION.SDK_INT < 27) return false
    val stillMatches = if (proof.version != null && sourceFileVersion(context, uri)?.value == proof.version) {
        true
    } else {
        sourceMatchesDigestSync(context, uri, proof.sha256)
    }
    if (!stillMatches) return false
    val deletedByResolver = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
    if (deletedByResolver) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        android.os.Environment.isExternalStorageManager() &&
        dataPath.isNotBlank()
    ) {
        val recheck = if (proof.version != null && sourceFileVersion(context, uri)?.value == proof.version) {
            true
        } else {
            sourceMatchesDigestSync(context, uri, proof.sha256)
        }
        if (!recheck) return false
        val targetFile = java.io.File(dataPath)
        if (targetFile.isFile && targetFile.delete()) {
            runCatching {
                context.contentResolver.delete(uri, null, null)
            }
            runCatching {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(dataPath), null, null)
            }
            return true
        }
    }
    return false
}

internal suspend fun WebDavManager.inspectBackupDestination(url: String, size: Long, digest: String): BackupContentState {
    val auth = currentAuthState()
    if (relativeDuplicatePath(auth.baseUrl, url) == null) return BackupContentState.UNVERIFIABLE
    try {
        val request = okhttp3.Request.Builder().url(url).head()
            .tag(WebDavManager.AuthState::class.java, auth).header("Authorization", auth.authHeader).build()
        backupHeadClient.newCall(request).execute().use { response ->
            if (response.code == 404) return BackupContentState.MISSING
            if (!response.isSuccessful) return BackupContentState.UNVERIFIABLE
            val remoteSize = response.header("Content-Length")?.toLongOrNull() ?: return BackupContentState.UNVERIFIABLE
            if (remoteSize != size) {
                val path = relativeDuplicatePath(auth.baseUrl,url) ?: return BackupContentState.UNVERIFIABLE
                val native = duplicateRpc("file",org.json.JSONObject().put("path",path),auth)
                    ?: return BackupContentState.UNVERIFIABLE
                return if (native.optLong("size",-1) == remoteSize &&
                    native.optString("version").matches(Regex("[0-9a-f]{64}")) && native.optString("file_id").isNotBlank())
                    BackupContentState.DIFFERENT else BackupContentState.UNVERIFIABLE
            }
        }
        val fp = NasApplication.instance.database.fingerprintDao().getFingerprint(url)
        if (fp != null && fp.contentHash == digest && fp.fileSize == size) {
            return BackupContentState.MATCH
        }
        val vh = NasApplication.instance.database.verifiedHashDao().get(currentAccountKey(), url)
        if (vh != null && vh.sha256 == digest && vh.contentLength == size) {
            return BackupContentState.MATCH
        }
        val proof = getCachedVerifiedSha256(url, size)
        if (proof != null) {
            return if (proof.first == digest) BackupContentState.MATCH else BackupContentState.DIFFERENT
        }
        return BackupContentState.UNVERIFIABLE
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { return BackupContentState.UNVERIFIABLE }
}

internal suspend fun WebDavManager.findExistingBackup(size: Long, digest: String, account: String): String? {
    val dao = NasApplication.instance.database.verifiedHashDao()
    var after = ""
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = dao.findBackupCandidates(account, size, digest, after, 64)
        if (page.isEmpty()) return null
        for (path in page) {
            if (relativeDuplicatePath(currentAuthState().baseUrl,path) != null) {
                when (inspectBackupDestination(path,size,digest)) {
                    BackupContentState.MATCH -> return path
                    BackupContentState.UNVERIFIABLE -> continue
                    else -> Unit
                }
            }
            after = path
        }
    }
}

internal suspend fun WebDavManager.requireBackupProtocol() {
    val capabilities = duplicateRpc("capabilities",org.json.JSONObject(),currentAuthState())
        ?: throw IOException("Backend chưa hỗ trợ backup xác minh nội dung; cần cập nhật NAS")
    if (capabilities.optInt("backup_protocol",0) != 1 || !capabilities.optBoolean("shared_hash_catalogue")) {
        throw IOException("Backend chưa hỗ trợ backup xác minh nội dung; cần cập nhật NAS trước khi sao lưu")
    }
}

internal suspend fun WebDavManager.supportsBatchStream(): Boolean {
    return try {
        val capabilities = duplicateRpc("capabilities", org.json.JSONObject(), currentAuthState())
        capabilities?.optBoolean("batch_stream", false) ?: false
    } catch (_: Exception) {
        false
    }
}

internal suspend fun WebDavManager.supportsResumableUpload(): Boolean {
    return try {
        val capabilities = duplicateRpc("capabilities", org.json.JSONObject(), currentAuthState())
        capabilities?.optBoolean("resumable_upload", false) ?: false
    } catch (_: Exception) {
        false
    }
}

internal suspend fun WebDavManager.findSharedBackup(size: Long, digest: String): String? {
    val auth = currentAuthState()
    var after = ""
    val cursors = mutableSetOf<String>()
    var refreshAttempts = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = duplicateRpc("find",org.json.JSONObject().put("sha256",digest).put("size",size).put("after",after),auth)
            ?: return null
        val matches = result.optJSONArray("matches") ?: return null
        val unverified = result.optJSONArray("unverified") ?: return null
        for (values in listOf(matches,unverified)) {
            for (index in 0 until values.length()) {
                val path = values.getString(index)
                val url = auth.baseUrl.trimEnd('/') + "/" + path.split('/').joinToString("/") {
                    java.net.URLEncoder.encode(it,"UTF-8").replace("+","%20")
                }
                when (inspectBackupDestination(url,size,digest)) {
                    BackupContentState.MATCH -> return url
                    BackupContentState.UNVERIFIABLE -> continue
                    else -> Unit
                }
            }
        }
        val next = result.optString("next_cursor")
        if (next.isEmpty()) {
            return null
        }
        if (!cursors.add(next)) return null
        after = next
    }
}

internal suspend fun WebDavManager.planBackupTarget(stable: String, size: Long, digest: String, account: String): BackupTargetPlan {
    findExistingBackup(size, digest, account)?.let { return BackupTargetPlan(it, true) }
    findSharedBackup(size,digest)?.let { return BackupTargetPlan(it,true) }
    return when (inspectBackupDestination(stable, size, digest)) {
        BackupContentState.MATCH -> BackupTargetPlan(stable, true)
        BackupContentState.MISSING -> BackupTargetPlan(stable, false)
        BackupContentState.UNVERIFIABLE -> throw IOException("Không đủ bằng chứng xác minh đích; giữ nguồn và không tạo thêm bản sao")
        BackupContentState.DIFFERENT -> {
            // Yêu cầu: Cấm tuyệt đối đổi tên file khi upload lên NAS, tất cả phải giữ nguyên tên gốc, trùng tên không tải lên
            BackupTargetPlan(stable, skip = true, isContentMatch = false)
        }
    }
}

internal suspend fun WebDavManager.cleanupBackupTemporary(url: String, auth: WebDavManager.AuthState) {
    val path = relativeDuplicatePath(auth.baseUrl, url) ?: return
    val name = path.substringAfterLast('/')
    if (!name.startsWith(".nas-backup-") || !name.endsWith(".tmp")) return
    if (runCatching { java.util.UUID.fromString(name.removePrefix(".nas-backup-").removeSuffix(".tmp")) }.isFailure) return
    val request = okhttp3.Request.Builder().url(url).delete()
        .tag(WebDavManager.AuthState::class.java, auth).header("Authorization", auth.authHeader).build()
    backupCleanupClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful && response.code != 404) throw IOException("Temporary backup cleanup HTTP ${response.code}")
    }
}
