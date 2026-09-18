package com.nas.naswebdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLDecoder

/** Generates a thumbnail only for a media item currently being displayed. */
object OnDemandThumbGenerator {
    private const val TAG = "OnDemandThumb"
    private const val IMAGE_LIMIT_BYTES = 10L * 1024 * 1024
    private const val VIDEO_LIMIT_BYTES = 100L * 1024 * 1024
    private const val MAX_DOWNLOAD_BUFFER = 131072

    suspend fun generateAndUpload(
        sourceUrl: String,
        auth: String,
        isVideo: Boolean,
        context: Context,
        maxImageBytes: Long = IMAGE_LIMIT_BYTES,
        maxVideoBytes: Long = VIDEO_LIMIT_BYTES
    ): File? = withContext(Dispatchers.IO) {
        // FIX-SYNC-S3: ranh giới phone/NAS — NAS daemon là nguồn thumb chính.
        // Phone chỉ decode khi NAS fail. Bỏ qua ngay codec phone không decode
        // được (AV1 — cả ffmpeg NAS 3.2 lẫn MediaMetadataRetriever cũ đều fail),
        // khỏi tải tối đa 100MB vô ích rồi mới fail ở decodeVideo.
        if (isVideo) {
            val lower = sourceUrl.substringBefore('?').lowercase()
            if (lower.endsWith(".av1") || lower.endsWith(".avif")) return@withContext null
        }
        val limit = if (isVideo) maxVideoBytes else maxImageBytes
        val safeHash = Integer.toHexString(sourceUrl.hashCode())
        val thumbDir = context.getDir("persistent_thumbnails", Context.MODE_PRIVATE)
        val thumbFile = File(thumbDir, "thumb_$safeHash.jpg")
        val tempFile = File.createTempFile("thumb_source_", ".media", context.cacheDir)
        var bitmap: Bitmap? = null
        try {
            val sourceRequest = Request.Builder()
                .url(sourceUrl.replace(" ", "%20"))
                .header("Authorization", auth)
                .head()
                .build()
            val client = NasApplication.instance.thumbnailApiClient
            val contentLength = client.newCall(sourceRequest).execute().use { response ->
                if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() else null
            }
            if (contentLength != null && contentLength > limit) return@withContext null

            val downloadRequest = Request.Builder()
                .url(sourceUrl.replace(" ", "%20"))
                .header("Authorization", auth)
                .build()
            client.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val responseLength = body.contentLength()
                if (responseLength >= 0L && responseLength > limit) return@withContext null
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(MAX_DOWNLOAD_BUFFER)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > limit) return@withContext null
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }

            bitmap = if (isVideo) decodeVideo(tempFile) else decodeImage(tempFile)
            val decoded = bitmap ?: return@withContext null
            if (!thumbDir.exists()) thumbDir.mkdirs()
            FileOutputStream(thumbFile).use { output ->
                if (!decoded.compress(Bitmap.CompressFormat.JPEG, AppConfig.THUMBNAIL_QUALITY, output)) {
                    return@withContext null
                }
            }
            if (thumbFile.length() == 0L) return@withContext null

            uploadToNas(sourceUrl, auth, thumbFile)
            thumbFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "On-demand thumbnail failed for ${sourceUrl.substringAfterLast('/')}:", e)
            null
        } finally {
            bitmap?.recycle()
            tempFile.delete()
        }
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)?.let(::fitToThumbnail)
    }

    private fun decodeVideo(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::fitToThumbnail)
        } finally {
            retriever.release()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > AppConfig.THUMBNAIL_SIZE_PX * 2 || height / sample > AppConfig.THUMBNAIL_SIZE_PX * 2) {
            sample *= 2
        }
        return sample
    }

    private fun fitToThumbnail(source: Bitmap): Bitmap {
        val max = AppConfig.THUMBNAIL_SIZE_PX
        if (source.width <= max && source.height <= max) return source
        val scale = minOf(max.toFloat() / source.width, max.toFloat() / source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun uploadToNas(sourceUrl: String, auth: String, thumbFile: File) {
        val parsed = URL(sourceUrl)
        val path = URLDecoder.decode(parsed.path, "UTF-8")
        val apiUrl = "${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/upload"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("path", path)
            .addFormDataPart("thumb", thumbFile.name, thumbFile.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", auth)
            .post(body)
            .build()
        NasApplication.instance.thumbnailApiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "NAS thumbnail upload failed: HTTP ${response.code}")
            }
        }
    }
}
