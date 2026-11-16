package com.nas.naswebdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/** Generates a thumbnail only for a media item currently being displayed. */
object OnDemandThumbGenerator {
    private const val TAG = "OnDemandThumb"
    private const val IMAGE_LIMIT_BYTES = 10L * 1024 * 1024
    private const val VIDEO_LIMIT_BYTES = 100L * 1024 * 1024
    private const val MAX_DOWNLOAD_BUFFER = 131072
    // CC-005 P1: Semaphore giới hạn 2 generate song song — tránh OOM khi grid
    // scroll nhanh spawn hàng chục coroutine decode cùng lúc.
    private val generateSemaphore = kotlinx.coroutines.sync.Semaphore(2)

    /** CC-005 P1: encode URL theo segment (giữ '/') — dùng chung encodeWebDavSegment. */
    private fun encodeThumbUrl(rawUrl: String): String {
        val qIdx = rawUrl.indexOf('?')
        val base = if (qIdx >= 0) rawUrl.substring(0, qIdx) else rawUrl
        val query = if (qIdx >= 0) rawUrl.substring(qIdx) else ""
        val schemeIdx = base.indexOf("://")
        if (schemeIdx < 0) return rawUrl
        val afterScheme = base.indexOf('/', schemeIdx + 3)
        if (afterScheme < 0) return rawUrl
        val prefix = base.substring(0, afterScheme)
        val path = base.substring(afterScheme).split("/").joinToString("/") { seg ->
            if (seg.isEmpty()) seg else encodeWebDavSegment(seg)
        }
        return prefix + path + query
    }

    /** Chi tu render khi mang khong tinh phi (Wi-Fi/LAN). 4G thi bo qua de
     * NAS xu luc idle — tranh dot data di dong tai file 10-100MB. */
    fun isUnmetered(context: Context): Boolean {
        val cm = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        }.getOrNull() ?: return true
        return runCatching { cm.isActiveNetworkMetered == false }.getOrDefault(true)
    }

    suspend fun generateAndUpload(
        sourceUrl: String,
        auth: String,
        isVideo: Boolean,
        context: Context,
        maxImageBytes: Long = IMAGE_LIMIT_BYTES,
        maxVideoBytes: Long = VIDEO_LIMIT_BYTES
    ): File? = withContext(Dispatchers.IO) {
        // CC-005 P1: giới hạn song song bằng Semaphore.
        generateSemaphore.withPermit {
            generateAndUploadInner(sourceUrl, auth, isVideo, context, maxImageBytes, maxVideoBytes)
        }
    }

    private suspend fun generateAndUploadInner(
        sourceUrl: String,
        auth: String,
        isVideo: Boolean,
        context: Context,
        maxImageBytes: Long = IMAGE_LIMIT_BYTES,
        maxVideoBytes: Long = VIDEO_LIMIT_BYTES
    ): File? = withContext(Dispatchers.IO) {
        val limit = if (isVideo) maxVideoBytes else maxImageBytes
        val safeHash = Integer.toHexString(sourceUrl.hashCode())
        val thumbDir = context.getDir("persistent_thumbnails", Context.MODE_PRIVATE)
        val thumbFile = File(thumbDir, "thumb_$safeHash.jpg")
        val tempFile = File.createTempFile("thumb_source_", ".media", context.cacheDir)
        val stagedThumb = File.createTempFile("thumb_", ".jpg", thumbDir)
        var bitmap: Bitmap? = null
        try {
            // CC-005 P1: encode theo segment (tránh 404 với tên file có dấu/cách).
            val encodedUrl = encodeThumbUrl(sourceUrl)
            val path = android.net.Uri.decode(URL(encodedUrl).path)
            val apiBase = WebDavManager.currentBaseUrl.toApiBaseUrl()
            val client = NasApplication.instance.thumbnailApiClient
            val check = Request.Builder().url("$apiBase/api/thumb/exists").header("Authorization", auth)
                .post(JSONObject().put("paths", JSONArray().put(path)).toString()
                    .toRequestBody("application/json".toMediaType())).build()
            val sourceVersion = client.newCall(check).execute().use { response ->
                if (response.code in listOf(404, 405, 501)) return@use null
                if (!response.isSuccessful) return@withContext null
                val state = JSONObject(response.body?.string() ?: return@withContext null)
                if (state.getJSONArray("missing").length() == 0) return@withContext null
                val versions = state.optJSONObject("source_versions") ?: return@use null
                versions.optString(path).takeIf { it.isNotEmpty() } ?: return@withContext null
            }
            val sourceRequest = Request.Builder()
                .url(encodedUrl)
                .header("Authorization", auth)
                .head()
                .build()
            val contentLength = client.newCall(sourceRequest).execute().use { response ->
                if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() else null
            }
            if (contentLength != null && contentLength > limit) return@withContext null

            val downloadRequest = Request.Builder()
                .url(encodedUrl)
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
            FileOutputStream(stagedThumb).use { output ->
                if (!decoded.compress(Bitmap.CompressFormat.JPEG, AppConfig.THUMBNAIL_QUALITY, output)) {
                    return@withContext null
                }
            }
            if (stagedThumb.length() == 0L || !uploadToNas(apiBase, path, auth, stagedThumb, sourceVersion) ||
                !stagedThumb.renameTo(thumbFile)) return@withContext null
            thumbFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "On-demand thumbnail failed for ${sourceUrl.substringAfterLast('/')}:", e)
            null
        } finally {
            bitmap?.recycle()
            tempFile.delete()
            stagedThumb.delete()
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

    private fun uploadToNas(apiBase: String, path: String, auth: String, thumbFile: File, sourceVersion: String?): Boolean {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("path", path)
            .apply { if (sourceVersion != null) addFormDataPart("source_version", sourceVersion) }
            .addFormDataPart("thumb", thumbFile.name, thumbFile.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$apiBase/api/thumb/upload")
            .header("Authorization", auth)
            .post(body)
            .build()
        return NasApplication.instance.thumbnailApiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "NAS thumbnail upload failed: HTTP ${response.code}")
            }
            response.isSuccessful
        }
    }
}
