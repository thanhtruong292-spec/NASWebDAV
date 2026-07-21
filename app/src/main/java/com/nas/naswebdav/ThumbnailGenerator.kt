package com.nas.naswebdav

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder

object ThumbnailGenerator {
    private const val TAG = "ThumbnailGenerator"
    private const val THUMB_SIZE = NasApplication.THUMBNAIL_SIZE_PX
    private const val THUMB_QUALITY = NasApplication.THUMBNAIL_QUALITY

    suspend fun generateAndUploadThumbnail(localFile: File, remoteWebDavPath: String, auth: String) = withContext(Dispatchers.IO) {
        try {
            val isVideo = localFile.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "ts", "webm")
            val isImage = localFile.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "heic")

            if (!isVideo && !isImage) return@withContext

            val bitmap = if (isVideo) generateVideoThumb(localFile) else generateImageThumb(localFile)

            if (bitmap != null) {
                uploadThumbnail(bitmap, remoteWebDavPath, auth)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate/upload thumbnail for ${localFile.name}", e)
        }
    }

    private fun generateImageThumb(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            
            var scale = 1
            while (options.outWidth / scale / 2 >= THUMB_SIZE &&
                   options.outHeight / scale / 2 >= THUMB_SIZE) {
                scale *= 2
            }
            
            options.inJustDecodeBounds = false
            options.inSampleSize = scale
            
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            
            val aspect = decoded.width.toFloat() / decoded.height.toFloat()
            val w = if (aspect > 1) THUMB_SIZE else (THUMB_SIZE * aspect).toInt()
            val h = if (aspect > 1) (THUMB_SIZE / aspect).toInt() else THUMB_SIZE
            
            Bitmap.createScaledBitmap(decoded, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
        } catch (e: Exception) {
            null
        }
    }

    private fun generateVideoThumb(file: File): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            
            if (bitmap != null) {
                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                val w = if (aspect > 1) THUMB_SIZE else (THUMB_SIZE * aspect).toInt()
                val h = if (aspect > 1) (THUMB_SIZE / aspect).toInt() else THUMB_SIZE
                Bitmap.createScaledBitmap(bitmap, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun uploadThumbnail(bitmap: Bitmap, remoteWebDavPath: String, auth: String) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, stream)
        val byteArray = stream.toByteArray()
        
        val url = "${WebDavManager.currentBaseUrl.toApiBaseUrl()}/api/thumb/upload?path=${URLEncoder.encode(remoteWebDavPath, "UTF-8")}"
        
        val requestBody = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .post(requestBody)
            .build()
            
        try {
            NasApplication.instance.fastApiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Upload thumbnail failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception uploading thumbnail", e)
        }
    }
}
