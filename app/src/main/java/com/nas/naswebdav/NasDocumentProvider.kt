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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asContextElement
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
                    val headers = webDavManager.headFileHeaders(url)
                    val len = headers?.get("Content-Length")?.toLongOrNull() ?: 0L
                    includeFile(result, NasFile(name, url, isDir, headers?.get("Content-Type"), len), baseUrl)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "queryDocument timeout sau 10s")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
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
                    val files = webDavManager.listFiles(url)
                    for (file in files) {
                        includeFile(result, file, baseUrl)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("NasDocProvider", "queryChildDocuments timeout sau 10s — trả về cursor rỗng")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
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
        val isWrite = (accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 ||
                (accessMode and ParcelFileDescriptor.MODE_READ_WRITE) != 0

        val fileExtension = targetId.trimEnd('/').substringAfterLast('.', "")

        if (isWrite) {
            val tempFile = File(context?.cacheDir, "nas_write_${System.currentTimeMillis()}.$fileExtension")
            tempFile.createNewFile()

            val handler = Handler(Looper.getMainLooper())
            return ParcelFileDescriptor.open(tempFile, accessMode, handler) { err ->
                if (err == null) {
                    NasApplication.applicationScope.launch(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(authState)) {
                        try {
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(fileExtension.lowercase())
                                ?: "application/octet-stream"
                            webDavManager.uploadFile(url, tempFile, mime)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            android.util.Log.e("NasDocProvider", "Upload thất bại: ${e.message}")
                        } finally {
                            tempFile.delete()
                        }
                    }
                } else {
                    tempFile.delete()
                }
            }
        } else {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            NasApplication.applicationScope.launch(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(authState)) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .let(WebDavManager::tagCurrentAuth)
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
                        webDavManager.createFolder(url)
                    } else {
                        webDavManager.createEmptyFile(url)
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
            runBlocking(WebDavManager.threadLocalAuth.asContextElement(authState)) {
                kotlinx.coroutines.withTimeout(10_000L) {
                    webDavManager.deleteFile(url, url.endsWith("/"))
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw FileNotFoundException("deleteDocument timed out")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throw FileNotFoundException("deleteDocument failed: ${e.message ?: "unknown error"}")
        }
    }

}