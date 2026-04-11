package com.nas.naswebdav

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    // Tạo chữ ký thông minh (Fast Signature) cho file
    private suspend fun generateFileSignature(context: Context, documentFile: DocumentFile): String = withContext(Dispatchers.IO) {
        val size = documentFile.length()
        val lastModified = documentFile.lastModified()
        var partialHash = ""

        try {
            context.contentResolver.openInputStream(documentFile.uri)?.use { input ->
                val buffer = ByteArray(1048576) // Đọc 1MB đầu tiên
                val bytesRead = input.read(buffer)
                if (bytesRead > 0) {
                    val md = MessageDigest.getInstance("MD5")
                    md.update(buffer, 0, bytesRead)
                    partialHash = md.digest().joinToString("") { "%02x".format(it) }
                }
            }
        } catch (e: Exception) {
            partialHash = "error_hash"
        }

        // Chữ ký gồm: Size_ModifiedTime_PartialHash
        return@withContext "${size}_${lastModified}_$partialHash"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val treeUriStr = inputData.getString("treeUri") ?: return@withContext Result.failure()
        val currentUrl = inputData.getString("currentUrl") ?: return@withContext Result.failure()
        val user = inputData.getString("user") ?: return@withContext Result.failure()
        val pass = inputData.getString("pass") ?: return@withContext Result.failure()

        val treeUri = android.net.Uri.parse(treeUriStr)
        val webDavManager = WebDavManager()
        webDavManager.connect(currentUrl, user, pass)

        val db = NasApplication.instance.database
        val fastApiClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            // Xin quyền đọc vĩnh viễn
            applicationContext.contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val documentTree = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?: throw Exception("Không thể đọc thư mục đã chọn")

            val targetFolderName = (documentTree.name ?: "SyncFolder").replace(" ", "_")
            val targetFolderUrl = if (currentUrl.endsWith("/")) "$currentUrl$targetFolderName/" else "$currentUrl/$targetFolderName/"

            try { webDavManager.createFolder(targetFolderUrl) } catch (e: Exception) { /* Bỏ qua nếu đã tồn tại */ }

            val localFiles = documentTree.listFiles().filter { it.isFile && it.name != null }
            if (localFiles.isEmpty()) return@withContext Result.success()

            setProgress(workDataOf("status" to "Đang phân tích và băm ${localFiles.size} tệp tin..."))

            // BƯỚC 1: XÂY DỰNG CHỮ KÝ CỤC BỘ (LOCAL SIGNATURES)
            val localSignatures = mutableMapOf<String, DocumentFile>()
            val signatureArray = JSONArray()

            for (local in localFiles) {
                val signature = generateFileSignature(applicationContext, local)
                localSignatures[signature] = local

                val fileObj = JSONObject().apply {
                    put("name", local.name)
                    put("signature", signature)
                    put("size", local.length())
                }
                signatureArray.put(fileObj)
            }

            // BƯỚC 2: GỬI CHỮ KÝ LÊN NAS ĐỂ ĐỐI CHIẾU (O(1) FAST CHECK)
            val apiBaseUrl = currentUrl.substringBefore("/webdav/").substringBeforeLast(":") + ":5000"
            val relativeTargetFolder = java.net.URL(targetFolderUrl).path.substringAfter("/webdav")

            val requestJson = JSONObject().apply {
                put("target_folder", relativeTargetFolder)
                put("files", signatureArray)
            }

            val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("$apiBaseUrl/api/sync/check_delta")
                .post(requestBody)
                .build()

            val filesToUpload = mutableListOf<DocumentFile>()
            val filesToResume = mutableListOf<Pair<DocumentFile, Long>>()

            var isApiSuccess = false
            try {
                fastApiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        isApiSuccess = true
                        val responseObj = JSONObject(response.body?.string() ?: "{}")
                        val decisions = responseObj.optJSONArray("decisions")

                        if (decisions != null) {
                            for (i in 0 until decisions.length()) {
                                val item = decisions.getJSONObject(i)
                                val sig = item.getString("signature")
                                val action = item.getString("action") // "SKIP", "UPLOAD", "RESUME"
                                val uploadedBytes = item.optLong("uploaded_bytes", 0L)

                                val localFile = localSignatures[sig] ?: continue

                                when (action) {
                                    "UPLOAD" -> filesToUpload.add(localFile)
                                    "RESUME" -> filesToResume.add(Pair(localFile, uploadedBytes))
                                    // "SKIP" -> Bỏ qua, file đã đồng bộ hoàn hảo
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                db.logDao().insertLog(SystemLog(type = "WARNING", module = "SyncWorker", message = "API Delta Sync thất bại, lùi về WebDAV tiêu chuẩn: ${e.message}"))
            }

            // BƯỚC 2 FALLBACK: NẾU API CHẾT, DÙNG PHƯƠNG PHÁP WEBDAV CŨ
            if (!isApiSuccess) {
                val remoteFiles = try { webDavManager.listFiles(targetFolderUrl) } catch(e: Exception) { emptyList() }
                val remoteFilesMap = remoteFiles.associate { it.name to it.contentLength }

                for (local in localFiles) {
                    val localSize = local.length()
                    val remoteSize = remoteFilesMap[local.name]

                    if (remoteSize == null || remoteSize > localSize) {
                        filesToUpload.add(local)
                    } else if (remoteSize < localSize) {
                        filesToResume.add(Pair(local, remoteSize))
                    }
                }
            }

            val totalTasks = filesToUpload.size + filesToResume.size
            if (totalTasks == 0) {
                db.logDao().insertLog(SystemLog(type = "INFO", module = "SyncWorker", message = "Mọi thứ đã được đồng bộ, không có tệp mới."))
                setProgress(workDataOf("status" to "Đã đồng bộ xong!"))
                return@withContext Result.success()
            }

            var currentIndex = 0

            // BƯỚC 3: THỰC THI ĐỒNG BỘ
            for (localFile in filesToUpload) {
                if (isStopped) break
                currentIndex++
                val fileName = localFile.name!!
                val fileSize = localFile.length()
                val mimeType = applicationContext.contentResolver.getType(localFile.uri) ?: "application/octet-stream"

                setProgress(workDataOf(
                    "status" to "Đang đồng bộ ($currentIndex/$totalTasks)...",
                    "fileName" to fileName
                ))

                applicationContext.contentResolver.openInputStream(localFile.uri)?.let { inputStream ->
                    webDavManager.uploadStreamWithProgress(targetFolderUrl + fileName, inputStream, fileSize, mimeType) { _, _ -> }
                }
            }

            for ((localFile, uploadedBytes) in filesToResume) {
                if (isStopped) break
                currentIndex++
                val fileName = localFile.name!!
                val fileSize = localFile.length()
                val mimeType = applicationContext.contentResolver.getType(localFile.uri) ?: "application/octet-stream"

                setProgress(workDataOf(
                    "status" to "Đang tải tiếp ($currentIndex/$totalTasks)...",
                    "fileName" to fileName
                ))

                try {
                    applicationContext.contentResolver.openInputStream(localFile.uri)?.let { inputStream ->
                        webDavManager.resumeUploadStreamWithProgress(
                            fileUrl = targetFolderUrl + fileName,
                            inputStream = inputStream,
                            totalContentLength = fileSize,
                            uploadedBytes = uploadedBytes,
                            contentType = mimeType
                        ) { _, _ -> }
                    }
                } catch (e: Exception) {
                    // Nếu Resume thất bại (do NAS không hỗ trợ Content-Range), upload lại từ đầu
                    applicationContext.contentResolver.openInputStream(localFile.uri)?.let { inputStream ->
                        webDavManager.uploadStreamWithProgress(targetFolderUrl + fileName, inputStream, fileSize, mimeType) { _, _ -> }
                    }
                }
            }

            db.logDao().insertLog(SystemLog(type = "SUCCESS", module = "SyncWorker", message = "Hoàn tất đồng bộ $totalTasks tệp."))
            return@withContext Result.success()

        } catch (e: Exception) {
            db.logDao().insertLog(SystemLog(type = "ERROR", module = "SyncWorker", message = "Lỗi nghiêm trọng: ${e.message}"))
            return@withContext Result.failure()
        } finally {
            try {
                applicationContext.contentResolver.releasePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {}
        }
    }
}