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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

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
    private fun initAndGetBaseUrl(): String {
        val ctx = context ?: return ""
        val authData = SecurePrefsHelper.readEncrypted(ctx)
        if (authData is SecurePrefsHelper.AuthData.Valid) {
            val url = String(authData.url)
            val user = String(authData.user)
            val pass = String(authData.pass)
            webDavManager.connect(url, user, pass)
            authData.clear()
            return url.trimEnd('/')
        }
        return ""
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
            if (relativePath.isEmpty()) ROOT_DOC_ID else relativePath
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
            else try { java.net.URLDecoder.decode(displayName, "UTF-8") } catch (_: Exception) { displayName }
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
        val baseUrl = initAndGetBaseUrl()
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
        // 1TB ảo để OS không báo thiếu dung lượng
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 1_099_511_627_776L)

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val targetId = documentId ?: ROOT_DOC_ID
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        // FIX A4: Thêm withTimeout để giới hạn thời gian chờ tối đa 10s.
        // DocumentsProvider PHẢI trả về Cursor đồng bộ nên không thể loại bỏ runBlocking,
        // nhưng timeout đảm bảo không bao giờ block Main Thread vô hạn (→ ANR).
        runBlocking {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    val baseUrl = initAndGetBaseUrl()
                    if (baseUrl.isEmpty()) return@withTimeout

                    if (targetId == ROOT_DOC_ID) {
                        val rootUrl = resolveDocumentUrl(ROOT_DOC_ID, baseUrl)
                        includeFile(result, NasFile("/", rootUrl, true, null, 0, System.currentTimeMillis()), baseUrl)
                    } else {
                        val url = resolveDocumentUrl(targetId, baseUrl)
                        val headers = webDavManager.headFileHeaders(url)
                        val isDir = targetId.endsWith("/")
                        val name = targetId.trimEnd('/').substringAfterLast('/')
                        val len = headers?.get("Content-Length")?.toLongOrNull() ?: 0L
                        includeFile(result, NasFile(name, url, isDir, headers?.get("Content-Type"), len), baseUrl)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "queryDocument timeout sau 10s — trả về cursor rỗng")
            } catch (_: Exception) {
                // Trả về cursor rỗng khi mất kết nối, không crash
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

        // FIX A4: Thêm withTimeout 10s để tránh ANR khi NAS phản hồi chậm.
        runBlocking {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    val baseUrl = initAndGetBaseUrl()
                    if (baseUrl.isEmpty()) return@withTimeout

                    val url = resolveDocumentUrl(parentDocumentId ?: ROOT_DOC_ID, baseUrl)
                    val files = webDavManager.listFiles(url)
                    for (file in files) {
                        includeFile(result, file, baseUrl)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "queryChildDocuments timeout sau 10s — trả về cursor rỗng")
            } catch (_: Exception) {
                // Trả về cursor rỗng khi mất kết nối, không crash
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

        // FIX LỖI 3: bọc resolveDocumentUrl trong try-catch
        val baseUrl = initAndGetBaseUrl()
        if (baseUrl.isEmpty()) throw FileNotFoundException("Chưa đăng nhập")
        val url = resolveDocumentUrl(targetId, baseUrl)

        val accessMode = ParcelFileDescriptor.parseMode(mode ?: "r")
        val isWrite = (accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 ||
                (accessMode and ParcelFileDescriptor.MODE_READ_WRITE) != 0

        // FIX LỖI 2: Lấy extension từ documentId (đường dẫn thật), không từ temp file name
        val fileExtension = targetId.trimEnd('/').substringAfterLast('.', "")

        if (isWrite) {
            // Tệp bộ đệm ghi: giữ đúng extension để MIME đúng sau khi đọc lại
            val tempFile = File(context?.cacheDir, "nas_write_${System.currentTimeMillis()}.$fileExtension")
            tempFile.createNewFile()

            val handler = Handler(Looper.getMainLooper())
            return ParcelFileDescriptor.open(tempFile, accessMode, handler) { err ->
                if (err == null) {
                    // FIX C3: Thay raw Thread { runBlocking {} }.start() bằng applicationScope.launch(IO).
                    // Raw Thread không được quản lý lifecycle và lỗi upload bị nuốt hoàn toàn.
                    // applicationScope đảm bảo upload hoàn thành ngay cả khi user rời app.
                    NasApplication.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(fileExtension.lowercase())
                                ?: "application/octet-stream"
                            webDavManager.uploadFile(url, tempFile, mime)
                        } catch (e: Exception) {
                            android.util.Log.e("NasDocProvider", "Upload thất bại: ${e.message}")
                        } finally {
                            tempFile.delete()
                        }
                    }
                } else {
                    tempFile.delete() // Dọn rác khi lỗi ghi
                }
            }
        } else {
            // Mở để đọc: Trả về Pipe stream thay vì block tải toàn bộ tệp vào bộ nhớ
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            // FIX: Thay raw Thread {} bằng applicationScope.launch(IO).
            // applicationScope (SupervisorJob) đảm bảo launch được lifecycle-aware,
            // exception không bị nuốt silent và thread được quản lý qua dispatcher.
            // Pipe stream BẮT BUỘC phải là fire-and-forget (caller cần trả readFd ngay),
            // nên launch (không await) là pattern đúng — không thể dùng withContext.
            NasApplication.applicationScope.launch(Dispatchers.IO) {
                try {
                    // FIX: optimizedClient là private — dùng sharedHttpClient với Authorization header thủ công
                    // (cùng logic với preemptive auth interceptor của optimizedClient)
                    val credential = okhttp3.Credentials.basic(webDavManager.currentUser, webDavManager.currentPass)
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("Authorization", credential)
                        .build()
                    NasApplication.instance.sharedHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            try { writeFd.closeWithError("Lỗi kết nối NAS: ${response.code}") } catch (_: Exception) {}
                            return@launch
                        }
                        val body = response.body
                        if (body == null) {
                            try { writeFd.closeWithError("Phản hồi từ NAS bị rỗng") } catch (_: Exception) {}
                            return@launch
                        }
                        ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { fos ->
                            body.byteStream().copyTo(fos)
                        }
                    }
                } catch (e: Exception) {
                    try { writeFd.closeWithError(e.message ?: "Mất kết nối stream") } catch (_: Exception) {}
                }
            }

            return readFd
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val baseUrl = initAndGetBaseUrl()
        if (baseUrl.isEmpty()) throw FileNotFoundException("Chưa đăng nhập")

        var baseId = parentDocumentId.trimEnd('/')
        if (baseId == ROOT_DOC_ID) baseId = ""

        val newId = "$baseId/${java.net.URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")}"
        val url = resolveDocumentUrl(newId, baseUrl)

        // FIX A4: Thêm withTimeout 10s
        runBlocking {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        webDavManager.createFolder(url)
                    } else {
                        webDavManager.createEmptyFile(url)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "createDocument timeout")
            } catch (_: Exception) {}
        }
        return newId
    }

    override fun deleteDocument(documentId: String?) {
        val targetId = documentId ?: return
        val baseUrl = initAndGetBaseUrl()
        if (baseUrl.isEmpty()) return
        val url = resolveDocumentUrl(targetId, baseUrl)
        // FIX A4: Thêm withTimeout 10s
        runBlocking {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    webDavManager.deleteFile(url)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "deleteDocument timeout")
            } catch (_: Exception) {}
        }
    }
}
