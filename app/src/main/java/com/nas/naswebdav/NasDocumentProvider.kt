package com.nas.naswebdav

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import androidx.annotation.GuardedBy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asContextElement
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class NasDocumentProvider : DocumentsProvider() {

    private val DEFAULT_ROOT_ID = "nas_root"
    private val ROOT_DOC_ID = "/"

    private lateinit var webDavManager: WebDavManager

    override fun onCreate(): Boolean {
        webDavManager = WebDavManager
        return true
    }

    /**
     * FIX LỖI 1+B: Đọc credentials MỘT LẦN DUY NHẤT và cache lại trong một lần query.
     * Không còn đọc SecurePrefs lặp lại cho từng tệp trong danh sách.
     */
    private fun initAndGetBaseUrl(): Pair<String, WebDavManager.AuthState?> {
        val ctx = context ?: return Pair("", null)
        val authData = SecurePrefsHelper.readEncrypted(ctx)
        if (authData is SecurePrefsHelper.AuthData.Valid) {
            val url = String(authData.url)
            val user = String(authData.user)
            val pass = String(authData.pass)
            val authState = WebDavManager.AuthState(url, user, pass)
            authData.clear()
            return Pair(url.trimEnd('/'), authState)
        }
        return Pair("", null)
    }

    /**
     * FIX LỖI 3: Toàn bộ resolveDocumentUrl đều trả về nullable thay vì throw,
     * xử lý null ở nơi gọi.
     */
    private fun resolveDocumentUrl(documentId: String, baseUrl: String): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        return if (documentId == ROOT_DOC_ID) {
            "$cleanBaseUrl/"
        } else {
            val path = if (documentId.startsWith("/")) documentId else "/$documentId"
            val encodedPath = path.split("/").joinToString("/") { segment -> 
                if (segment.isEmpty()) "" else java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            "$cleanBaseUrl$encodedPath"
        }
    }

    /**
     * FIX LỖI 1: Nhận baseUrl được cache từ ngoài, không gọi lại SecurePrefs.
     */
    private fun toDocumentId(absoluteUrl: String, baseUrl: String): String {
        return if (absoluteUrl.startsWith(baseUrl)) {
            val relativePath = absoluteUrl.substring(baseUrl.length)
            if (relativePath.isEmpty()) ROOT_DOC_ID else relativePath.split("/").joinToString("/") { segment ->
                if (segment.isEmpty()) "" else java.net.URLDecoder.decode(segment, "UTF-8")
            }
        } else {
            ROOT_DOC_ID
        }
    }
    private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    )

    private fun includeFile(result: MatrixCursor, file: NasFile, baseUrl: String) {
        val row = result.newRow()
        // FIX LỖI 1: truyền baseUrl đã cache vào toDocumentId
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, toDocumentId(file.path, baseUrl))
        val displayName = file.name.removeSuffix("/")
        row.add(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            if (displayName.isEmpty()) "NAS Drive"
            else try { java.net.URLDecoder.decode(displayName, "UTF-8") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { displayName }
        )

        var flags = 0
        if (file.isDirectory) {
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            val extension = file.name.substringAfterLast('.', "")
            val mimeType = file.contentType?.takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                ?: "application/octet-stream"
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            row.add(DocumentsContract.Document.COLUMN_SIZE, file.contentLength)
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(
            projection ?: arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
            )
        )

        // FIX LỖI B: Chỉ gọi initAndGetBaseUrl 1 lần
        val (baseUrl, _) = initAndGetBaseUrl()
        if (baseUrl.isEmpty()) return result

        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, DEFAULT_ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "NAS WebDAV")
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        )
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 1_099_511_627_776L)

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (baseUrl, authState) = initAndGetBaseUrl()
        if (baseUrl.isEmpty() || authState == null) return result
        
        val url = resolveDocumentUrl(documentId ?: ROOT_DOC_ID, baseUrl)

        runBlocking(WebDavManager.threadLocalAuth.asContextElement(authState)) {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    val isDir = (documentId ?: ROOT_DOC_ID).endsWith("/")
                    val name = (documentId ?: ROOT_DOC_ID).trimEnd('/').substringAfterLast('/')
                    val headers = webDavManager.headFileHeaders(url, authState)
                    val len = headers?.get("Content-Length")?.toLongOrNull() ?: 0L
                    includeFile(result, NasFile(name, url, isDir, headers?.get("Content-Type"), len), baseUrl)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "queryDocument timeout sau 10s")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("NasDocProvider", "queryDocument failed: ${e.message}")
            }
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val (baseUrl, authState) = initAndGetBaseUrl()
        if (baseUrl.isEmpty() || authState == null) return result

        runBlocking(WebDavManager.threadLocalAuth.asContextElement(authState)) {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    val url = resolveDocumentUrl(parentDocumentId ?: ROOT_DOC_ID, baseUrl)
                    val files = webDavManager.listFiles(url, authState)
                    for (file in files) {
                        includeFile(result, file, baseUrl)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "queryChildDocuments timeout sau 10s — trả về cursor rỗng")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("NasDocProvider", "queryChildDocuments failed: ${e.message}")
            }
        }
        return result
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val targetId = documentId ?: throw FileNotFoundException("Document ID rỗng")

        val (baseUrl, authState) = initAndGetBaseUrl()
        if (baseUrl.isEmpty() || authState == null) throw FileNotFoundException("Chưa đăng nhập")
        val url = resolveDocumentUrl(targetId, baseUrl)

        val accessMode = ParcelFileDescriptor.parseMode(mode ?: "r")
        // FIX-AUDIT-F1: bitmask cũ `(mode & READ_WRITE) != 0` luôn true với "r"
        // vì MODE_READ_WRITE (0x30000000) chứa bit MODE_READ_ONLY (0x10000000).
        // Phân loại đúng: chỉ WRITE_ONLY hoặc APPEND mới là ghi.
        val isWrite = (accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 ||
                (accessMode and ParcelFileDescriptor.MODE_APPEND) != 0
        val isAppend = (accessMode and ParcelFileDescriptor.MODE_APPEND) != 0
        // FIX-REVIEW-S2: "w" = truncate (temp rỗng đúng); "wa"/"rw" giữ nội dung gốc
        // nên phải preload từ NAS. Mode write lạ khác → từ chối thay vì đoán.
        val rawMode = (mode ?: "r").lowercase()
        // R2-P2: "rwt" cũng truncate (t trong mode = truncate) — không preload.
        val isTruncate = rawMode == "w" || rawMode == "wt" || rawMode == "rwt"
        if (isWrite && !isTruncate && !isAppend && rawMode != "rw" && rawMode != "rwt") {
            throw FileNotFoundException("Chế độ ghi không hỗ trợ: $mode")
        }
        val needPreload = isWrite && !isTruncate

        val fileExtension = targetId.trimEnd('/').substringAfterLast('.', "")

        if (isWrite) {
            // DocumentsProvider write path design constraint:
            //   - Caller CẦN PFD ngay để bắt đầu ghi → KHÔNG được block openDocument()
            //     chờ upload (sẽ deadlock: callback chỉ chạy sau khi caller đóng PFD).
            //   - Sau khi caller đóng PFD, HTTP status chỉ về SAU → caller đã thấy
            //     "ghi thành công" và không nhận được exception qua return value.
            //
            // Fix: fire-and-forget upload qua applicationScope. Upload failure
            // được báo qua 3 kênh (1) System log (SystemLogger), (2) system
            // notification cho user, (3) file temp được giữ lại trong cacheDir
            // với tên có timestamp để user/developer recover thủ công.
            val tempFile = File(context?.cacheDir, "nas_write_${System.currentTimeMillis()}.$fileExtension")
            tempFile.createNewFile()
            // FIX-REVIEW-S2: wa/rw phải bắt đầu từ nội dung gốc trên NAS.
            // Không preload được (file chưa tồn tại → tạo mới OK; lỗi mạng → từ chối
            // thay vì trả temp rỗng rồi PUT đè 0 byte khi đóng).
            if (needPreload) {
                // Preload inline với header trực tiếp (không qua downloadFile vì
                // hàm đó dùng singleton authState, còn đây là authState đã chụp).
                try {
                    val preloadReq = okhttp3.Request.Builder()
                        .url(url)
                        .header("Authorization", authState.authHeader)
                        .build()
                    NasApplication.instance.sharedHttpClient.newCall(preloadReq).execute().use { resp ->
                        if (resp.code == 404) return@use  // file chưa tồn tại → tạo mới
                        if (!resp.isSuccessful) throw FileNotFoundException("Không tải được nội dung gốc: HTTP ${resp.code}")
                        val body = resp.body ?: throw FileNotFoundException("Nội dung gốc rỗng")
                        body.byteStream().use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: FileNotFoundException) {
                    throw e
                } catch (e: Exception) {
                    tempFile.delete()
                    throw FileNotFoundException("Không tải được nội dung gốc: ${e.message}")
                }
            }

            val handler = Handler(Looper.getMainLooper())
            return ParcelFileDescriptor.open(tempFile, accessMode, handler) { err ->
                // Callback chạy SAU khi caller đóng fd → file đã có đủ dữ liệu.
                if (err == null) {
                    NasApplication.applicationScope.launch(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(authState)) {
                        val uploadSuccess = try {
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(fileExtension.lowercase())
                                ?: "application/octet-stream"
                            // R4-P1: PUT với auth + URL ĐÃ CHỤP lúc mở file.
                            // uploadFile dùng singleton authState — đổi tài khoản giữa
                            // mở và đóng sẽ upload bằng credentials B tới máy A.
                            // REVIEW-R2: streaming body, không readBytes (OOM file lớn).
                            val putBody = object : okhttp3.RequestBody() {
                                override fun contentType() = mime.toMediaTypeOrNull()
                                override fun contentLength() = tempFile.length()
                                override fun writeTo(sink: okio.BufferedSink) {
                                    tempFile.inputStream().use { input ->
                                        sink.writeAll(input.source().buffer())
                                    }
                                }
                            }
                            val putReq = okhttp3.Request.Builder()
                                .url(url)
                                .header("Authorization", authState.authHeader)
                                .put(putBody)
                                .build()
                            NasApplication.instance.sharedHttpClient.newCall(putReq).execute().use { resp ->
                                if (!resp.isSuccessful) throw java.io.IOException("Upload: HTTP ${resp.code}")
                            }
                            true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("NasDocProvider", "Upload thất bại: ${e.message}")
                            // Persist recoverable metadata into SyncAction table (đã tồn tại)
                            // → user có thể browse failed uploads trong app và retry thủ công.
                            // sourcePath = temp file (còn trong cacheDir cho đến khi OS dọn);
                            // destPath = NAS URL; actionType phân biệt với offline sync queue.
                            try {
                                val app = NasApplication.instance
                                val db = app.database
                                db.syncActionDao().insert(
                                    com.nas.naswebdav.SyncAction(
                                        actionType = "UPLOAD_FAILED",
                                        sourcePath = tempFile.absolutePath,
                                        destPath = url,
                                        status = "FAILED",
                                        nasHost = runCatching { java.net.URL(baseUrl).host ?: baseUrl }.getOrDefault(baseUrl),
                                        nasUser = authState.user
                                    )
                                )
                                // Kích hoạt OfflineSyncWorker retry ngay khi có mạng.
                                // APPEND_OR_REPLACE: nếu worker đang chờ thì enqueue lại đảm bảo chạy.
                                val ctx = context ?: return@launch
                                val syncConstraints = Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                                val syncRequest = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                                    .setConstraints(syncConstraints)
                                    .setBackoffCriteria(
                                        androidx.work.BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS
                                    )
                                    .build()
                                WorkManager.getInstance(ctx).enqueueUniqueWork(
                                    OfflineSyncWorker.UNIQUE_WORK_NAME,
                                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                                    syncRequest
                                )
                            } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 }
                            catch (_: Exception) {}
                            // Ghi vào SystemLog để dev thấy trong UI + ADB log
                            try { com.nas.naswebdav.utils.SystemLogger.log("ERROR", "NasDocProvider",
                                "Upload thất bại: ${targetId.substringAfterLast('/')} → ${e.message} (file=${tempFile.absolutePath})") }
                            catch (e2: kotlinx.coroutines.CancellationException) { throw e2 }
                            catch (_: Exception) {}
                            // Notification chỉ gửi nếu permission đã cấp (API 33+);
                            // thiếu permission thì skip silently — user vẫn thấy trong System Logs.
                            try { notifyUploadFailure(tempFile.name) }
                            catch (e2: kotlinx.coroutines.CancellationException) { throw e2 }
                            catch (_: Exception) {}
                            false
                        }
                        if (uploadSuccess) {
                            tempFile.delete()
                        } else {
                            // File vẫn còn + có metadata trong SyncAction để recovery
                        }
                    }
                } else {
                    // Caller đóng fd với error → xóa ngay (không có data hợp lệ)
                    tempFile.delete()
                }
            }
        } else {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            NasApplication.applicationScope.launch(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(authState)) {
                try {
                    // FIX-REVIEW-S1: sharedHttpClient không có interceptor auth
                    // (tag AuthState bị bỏ qua) → đọc ngoài luôn 401. Chèn header
                    // trực tiếp từ AuthState đã chụp, không qua tag.
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("Authorization", authState.authHeader)
                        .build()
                    NasApplication.instance.sharedHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            try { writeFd.closeWithError("Lỗi kết nối NAS: ${response.code}") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                            return@launch
                        }
                        val body = response.body
                        if (body == null) {
                            try { writeFd.closeWithError("Phản hồi từ NAS bị rỗng") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                            return@launch
                        }
                        ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { fos ->
                            body.byteStream().copyTo(fos)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    try { writeFd.closeWithError(e.message ?: "Mất kết nối stream") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                }
            }

            return readFd
        }
    }

    /**
     * Báo upload failure qua system notification channel.
     * DocumentsProvider architecture không cho phép throw exception về caller
     * sau khi fd đã đóng ��� đây là cách duy nhất để user biết upload thất bại.
     */
    private fun notifyUploadFailure(fileName: String) {
        val ctx = context ?: return
        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        val channelId = "nas_doc_provider_uploads"
        val channel = android.app.NotificationChannel(
            channelId, "Lỗi upload DocumentsProvider",
            android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Báo lỗi khi upload file qua DocumentsProvider thất bại" }
        nm.createNotificationChannel(channel)
        val notif = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Upload lên NAS thất bại")
            .setContentText("$fileName không upload được. Xem System Logs để retry.")
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        // API 33+: POST_NOTIFICATIONS runtime permission — skip nếu chưa cấp;
        // SystemLogger.log vẫn có thể đọc được trong UI.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                nm.notify(fileName.hashCode(), notif)
            }
        } else {
            nm.notify(fileName.hashCode(), notif)
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val (baseUrl, authState) = initAndGetBaseUrl()
        if (baseUrl.isEmpty() || authState == null) throw FileNotFoundException("Chưa đăng nhập")

        var baseId = parentDocumentId.trimEnd('/')
        if (baseId == ROOT_DOC_ID) baseId = ""

        val newId = "$baseId/${displayName}"
        val url = resolveDocumentUrl(newId, baseUrl)

        try {
            runBlocking(WebDavManager.threadLocalAuth.asContextElement(authState)) {
                kotlinx.coroutines.withTimeout(10_000L) {
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        webDavManager.createFolder(url, authState)
                    } else {
                        webDavManager.createEmptyFile(url, authState)
                    }
                }
            }
            return newId
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw FileNotFoundException("createDocument timed out")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throw FileNotFoundException("createDocument failed: ${e.message ?: "unknown error"}")
        }
    }
    override fun deleteDocument(documentId: String?) {
        val targetId = documentId ?: throw FileNotFoundException("Document ID rỗng")
        val (baseUrl, authState) = initAndGetBaseUrl()
        if (baseUrl.isEmpty() || authState == null) throw FileNotFoundException("Chưa đăng nhập")
        val url = resolveDocumentUrl(targetId, baseUrl)

        try {
            runBlocking {
                kotlinx.coroutines.withTimeout(10_000L) {
                    // REVIEW-R5: DELETE với auth đã chụp, không qua deleteFile
                    // (hàm đó dùng singleton authState — đổi user giữa chừng sẽ
                    // xóa bằng credentials sai, giống bug S1 ở GET/PUT).
                    val normUrl = if (url.endsWith("/")) url else {
                        // Giữ nguyên URL file; thư mục cần trailing slash + Depth.
                        url
                    }
                    val builder = okhttp3.Request.Builder()
                        .url(normUrl)
                        .header("Authorization", authState.authHeader)
                        .method("DELETE", null)
                    if (url.endsWith("/")) builder.header("Depth", "Infinity")
                    NasApplication.instance.sharedHttpClient.newCall(builder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("DELETE: HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw FileNotFoundException("deleteDocument timed out")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throw FileNotFoundException("deleteDocument failed: ${e.message ?: "unknown error"}")
        }
    }

}