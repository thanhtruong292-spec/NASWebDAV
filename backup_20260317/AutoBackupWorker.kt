package com.nas.naswebdav

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("nas_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("server_url", "") ?: return@withContext Result.failure()
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        val deleteAfterBackup = prefs.getBoolean("delete_after_backup", false)

        if (baseUrl.isEmpty()) return@withContext Result.failure()

        val webDavManager = WebDavManager()
        webDavManager.connect(baseUrl, username, password)

        val db = NasApplication.instance.database

        try {
            val backupFolder = if (baseUrl.endsWith("/")) "${baseUrl}AutoBackup/" else "$baseUrl/AutoBackup/"
            try {
                webDavManager.createFolder(backupFolder)
            } catch (e: Exception) {
                // Bỏ qua nếu thư mục đã tồn tại
            }

            // KIẾN TRÚC MỚI: Tối ưu hóa O(1) Network Request
            // Fetch toàn bộ danh sách tệp trên NAS 1 lần duy nhất để đối chiếu, triệt tiêu DDoS NAS
            val existingRemoteFiles = try {
                webDavManager.listFiles(backupFolder).map { it.name }.toHashSet()
            } catch (e: Exception) {
                hashSetOf<String>() // Lỗi mạng thì coi như NAS đang rỗng, tiến hành upload đè
            }

            var backupCount = 0

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA
            )

            // Truy vấn thư viện ảnh
            applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext() && !isStopped) {
                    val fileName = cursor.getString(nameIndex) ?: continue
                    val dataPath = cursor.getString(dataIndex) ?: continue
                    val id = cursor.getLong(idIndex)

                    // KIỂM TRA O(1) TRONG RAM: Không gọi WebDAV để ping từng file
                    if (!existingRemoteFiles.contains(fileName)) {
                        val localFile = File(dataPath)
                        if (localFile.exists()) {
                            try {
                                // Sử dụng hàm uploadFile (File) đã được bổ sung trước đó để tối ưu bộ nhớ
                                webDavManager.uploadFile(backupFolder + fileName, localFile, "image/*")

                                if (deleteAfterBackup) {
                                    val uri = ContentUris.withAppendedId(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        id
                                    )
                                    applicationContext.contentResolver.delete(uri, null, null)
                                }
                                backupCount++
                            } catch (e: Exception) {
                                db.logDao().insertLog(
                                    SystemLog(
                                        type = "WARNING",
                                        module = "AutoBackup",
                                        message = "Lỗi tải tệp $fileName: ${e.message}"
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (backupCount > 0) {
                db.logDao().insertLog(
                    SystemLog(
                        type = "SUCCESS",
                        module = "AutoBackup",
                        message = "Đã sao lưu tự động $backupCount ảnh mới."
                    )
                )
            }
            return@withContext Result.success()
        } catch (e: Exception) {
            db.logDao().insertLog(
                SystemLog(
                    type = "ERROR",
                    module = "AutoBackup",
                    message = "Lỗi luồng AutoBackup: ${e.message}"
                )
            )
            return@withContext Result.retry()
        }
    }
}