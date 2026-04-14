package com.nas.naswebdav.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.SystemLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// FormatUtils — định dạng thời gian, dung lượng, URL
// ─────────────────────────────────────────────────────────────────────────────
object FormatUtils {
    private val dateTimeFormat = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }
    private val shortDateTimeFormat = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    private val shortTimeFormat = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun formatDateTime(timestamp: Long): String = dateTimeFormat.get()?.format(Date(timestamp)) ?: ""
    fun formatShortDateTime(timestamp: Long): String = shortDateTimeFormat.get()?.format(Date(timestamp)) ?: ""
    fun formatShortTime(timestamp: Long): String = shortTimeFormat.get()?.format(Date(timestamp)) ?: ""

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    fun ensureTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"
}

// ─────────────────────────────────────────────────────────────────────────────
// HashUtils — MD5 hashing
// ─────────────────────────────────────────────────────────────────────────────
object HashUtils {
    fun md5(input: String): String = try {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { input.hashCode().toString() }

    fun md5Bytes(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): String = try {
        val md = MessageDigest.getInstance("MD5")
        md.update(buffer, offset, length)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "error_hash" }
}

// ─────────────────────────────────────────────────────────────────────────────
// MediaUtils — nhận diện loại file media
// ─────────────────────────────────────────────────────────────────────────────
object MediaUtils {
    private val VIDEO_EXTENSIONS = setOf(".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v")
    private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif", ".bmp")

    fun isVideo(fileName: String): Boolean = VIDEO_EXTENSIONS.any { fileName.lowercase().endsWith(it) }
    fun isImage(fileName: String): Boolean = IMAGE_EXTENSIONS.any { fileName.lowercase().endsWith(it) }
}

// ─────────────────────────────────────────────────────────────────────────────
// SystemLogger — ghi log vào Room DB qua application scope (không dùng GlobalScope)
// ─────────────────────────────────────────────────────────────────────────────
object SystemLogger {
    fun log(type: String, module: String, message: String) {
        NasApplication.applicationScope.launch(Dispatchers.IO) {
            try {
                NasApplication.instance.database.logDao().insertLog(
                    SystemLog(type = type, module = module, message = message)
                )
            } catch (e: Exception) {
                android.util.Log.e("SystemLogger", "Lỗi ghi log: ${e.message}")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WolUtil — Wake-on-LAN qua UDP broadcast
// ─────────────────────────────────────────────────────────────────────────────
object WolUtil {
    data class WolResult(val success: Boolean, val method: String, val message: String)

    fun sendMagicPacket(macAddress: String): Boolean {
        return try {
            val cleanMac = macAddress.replace(":", "").replace("-", "")
            if (cleanMac.length != 12) return false

            val macBytes = ByteArray(6) { i -> cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            val magicPacket = ByteArray(6 + 16 * 6).also { pkt ->
                for (i in 0..5) pkt[i] = 0xFF.toByte()
                var offset = 6
                repeat(16) { System.arraycopy(macBytes, 0, pkt, offset, 6); offset += 6 }
            }

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(magicPacket, magicPacket.size, InetAddress.getByName("255.255.255.255"), 9))
            }
            true
        } catch (_: Exception) { false }
    }

    fun smartWakeOnLan(macAddress: String): WolResult =
        if (sendMagicPacket(macAddress)) WolResult(true, "LAN", "Đã gửi Magic Packet qua LAN")
        else WolResult(false, "NONE", "Không thể gửi WoL — kiểm tra kết nối mạng")
}

// ─────────────────────────────────────────────────────────────────────────────
// ImageFingerprint — Average Hash (aHash) cho ảnh
// ─────────────────────────────────────────────────────────────────────────────
object ImageFingerprint {
    private const val HASH_SIZE = 8

    fun computeAHash(bitmap: Bitmap): String? {
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
            try {
                val pixels = IntArray(HASH_SIZE * HASH_SIZE)
                small.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
                val grayValues = pixels.map { pixel -> val r = (pixel shr 16) and 0xFF; val g = (pixel shr 8) and 0xFF; val b = pixel and 0xFF; (0.299 * r + 0.587 * g + 0.114 * b) }
                val average = grayValues.average()
                val hashBits = grayValues.map { if (it >= average) 1L else 0L }
                var hashValue = 0L
                for (i in hashBits.indices) hashValue = hashValue or (hashBits[i] shl (63 - i))
                String.format("%016x", hashValue)
            } finally { if (small != bitmap) small.recycle() }
        } catch (_: Exception) { null }
    }

    fun hammingDistance(hash1: String, hash2: String): Int {
        if (hash1.length != 16 || hash2.length != 16) return 64
        // FIX: Không dùng toLong() vì sẽ overflow âm thầm với hash ≥ 0x8000000000000000
        // BigInteger.xor().bitCount() đảm bảo đúng với toàn bộ không gian 64-bit
        return try { java.math.BigInteger(hash1, 16).xor(java.math.BigInteger(hash2, 16)).bitCount() } catch (_: Exception) { 64 }
    }

    fun isSimilar(hash1: String, hash2: String, threshold: Int = 5): Boolean = hammingDistance(hash1, hash2) <= threshold

    fun computeFromUri(context: Context, uri: Uri): String? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    var sampleSize = 1
                    while (info.size.width / sampleSize > 300 || info.size.height / sampleSize > 300) sampleSize *= 2
                    val targetSize = android.util.Size(info.size.width / sampleSize, info.size.height / sampleSize)
                    decoder.setTargetSize(targetSize.width.coerceAtLeast(1), targetSize.height.coerceAtLeast(1))
                    try { decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_HARDWARE } catch (_: Exception) { decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                var sampleSize = 1
                while (options.outWidth / sampleSize > 512 || options.outHeight / sampleSize > 512) sampleSize *= 2
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565 }) }
            }
            bitmap?.let { computeAHash(it) }
        } catch (_: Exception) { null } finally { bitmap?.recycle() }
    }

    fun computeFromFile(file: File): String? {
        var bitmap: Bitmap? = null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            var sampleSize = 1
            while (options.outWidth / sampleSize > 512 || options.outHeight / sampleSize > 512) sampleSize *= 2
            bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565 })
            bitmap?.let { computeAHash(it) }
        } catch (_: Exception) { null } finally { bitmap?.recycle() }
    }

    fun computeFromBytes(data: ByteArray): String? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = 4; inPreferredConfig = Bitmap.Config.RGB_565 })
            bitmap?.let { computeAHash(it) }
        } catch (_: Exception) { null } finally { bitmap?.recycle() }
    }
}
