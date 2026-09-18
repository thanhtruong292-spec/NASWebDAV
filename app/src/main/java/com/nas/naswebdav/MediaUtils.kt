package com.nas.naswebdav

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val VIDEO_CACHE_DIR = "video_cache"

fun openLocalFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mpg", "mpeg" -> "video/mpeg"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2ts"
            "webm" -> "video/webm"
            else -> "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Chọn trình phát video")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("MediaUtils", "Cannot open file: ${e.message}")
    }
}

fun downloadAndPlay(
    scope: CoroutineScope,
    context: Context,
    url: String,
    user: String,
    pass: String,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    onReady: () -> Unit = {},
    onError: (String) -> Unit = {}
): Job {
    return scope.launch(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
            if (!cacheDir.exists()) cacheDir.mkdirs()
            cacheDir.listFiles()?.forEach { it.delete() }

            val fileName = url.substringAfterLast('/').substringBefore('?')
                .let { java.net.URLDecoder.decode(it, "UTF-8") }
                .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val targetFile = File(cacheDir, fileName)
            Log.i("MediaUtils", "Đang tải: $url → ${targetFile.absolutePath}")

            val request = okhttp3.Request.Builder()
                .url(url)
                // AUTH FIX: videoStreamingClient injects UTF-8 Authorization header centrally.
                .build()

            // REVIEW-R6: shared videoStreamingClient + withTimeout (het leak pool).
            kotlinx.coroutines.withTimeout(610_000L) {
            NasApplication.instance.videoStreamingClient
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            onError("NAS trả về lỗi: ${response.code}")
                        }
                        return@use
                    }
                    val totalBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    var downloadedBytes = 0L

                    response.body?.byteStream()?.use { input ->
                        targetFile.outputStream().use { output ->
                            val buffer = ByteArray(131072)
                            var bytesRead: Int
                            var lastProgressTime = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (!isActive) {
                                    targetFile.delete()
                                    return@use
                                }
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastProgressTime > 150L) {
                                    lastProgressTime = currentTime
                                    withContext(Dispatchers.Main) {
                                        onProgress(downloadedBytes, totalBytes)
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) { onProgress(downloadedBytes, totalBytes) }
                        }
                    }
                    Log.i("MediaUtils", "Tải xuống hoàn tất: ${downloadedBytes / 1024}KB")
                    withContext(Dispatchers.Main) {
                        onReady()
                        openLocalFile(context, targetFile)
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d("MediaUtils", "Đã hủy tải xuống")
        } catch (e: Exception) {
            Log.e("MediaUtils", "Tải xuống thất bại: ${e.message}")
            withContext(Dispatchers.Main) { onError("Lỗi tải video: ${e.message}") }
        }
    }
}
