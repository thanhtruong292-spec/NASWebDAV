package com.nas.naswebdav.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import android.net.Uri
import android.net.wifi.WifiManager
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.SystemLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.URI
import java.io.InputStream
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
        val gb = mb / 1024.0
        if (gb < 1024) return "%.2f GB".format(gb)
        val tb = gb / 1024.0
        if (tb < 1024) return "%.2f TB".format(tb)
        return "%.2f PB".format(tb / 1024.0)
    }

    fun ensureTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    fun stripTrailingSlash(url: String): String = url.trimEnd('/')

    /** Format milliseconds as MM:SS or HH:MM:SS (for duplicate-scan ETA / elapsed). */
    fun formatElapsedTime(ms: Long): String {
        if (ms < 0) return "--:--"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Format milliseconds as M:SS or H:MM:SS (for video playback time). */
    fun formatPlayerTime(positionMs: Long): String {
        val safeMs = positionMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * Tự động chuẩn hóa tên tệp:
     * 1. Chuyển tiếng Việt có dấu sang không dấu.
     * 2. Loại bỏ ký tự đặc biệt nguy hiểm (: * ? " < > | \ / # % +).
     * 3. Rút ngắn tên nếu vượt quá maxLen (mặc định 100 ký tự) nhưng giữ nguyên phần mở rộng (extension).
     */
    fun sanitizeFileName(fileName: String, maxLen: Int = 100): String {
        if (fileName.isBlank()) return "file_${System.currentTimeMillis()}"
        
        // 1. Chuyển tiếng Việt sang không dấu
        val nfdNormalized = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFD)
        val diacriticalRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
        var clean = diacriticalRegex.replace(nfdNormalized, "")
            .replace('đ', 'd').replace('Đ', 'D')
            
        // 2. Loại bỏ các ký tự đặc biệt không hợp lệ trên WebDAV / File system
        clean = clean.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim()
        clean = clean.replace(Regex(" +"), " ")
        
        if (clean.isBlank()) return "file_${System.currentTimeMillis()}"
        
        // 3. Rút ngắn tên nếu vượt quá maxLen
        if (clean.length > maxLen) {
            val dotIndex = clean.lastIndexOf('.')
            if (dotIndex > 0 && dotIndex < clean.length - 1 && (clean.length - dotIndex) <= 10) {
                val ext = clean.substring(dotIndex)
                val base = clean.substring(0, dotIndex)
                val maxBaseLen = maxLen - ext.length
                clean = if (maxBaseLen > 0) base.take(maxBaseLen).trim() + ext else clean.take(maxLen)
            } else {
                clean = clean.take(maxLen).trim()
            }
        }
        return clean
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HashUtils — MD5 & SHA-256 hashing (runs on PHONE CPU, not NAS)
// ─────────────────────────────────────────────────────────────────────────────
object HashUtils {
    fun md5(input: String): String = try {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { input.hashCode().toString() }

    fun md5Bytes(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): String = try {
        val md = MessageDigest.getInstance("MD5")
        md.update(buffer, offset, length)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "error_hash" }

    /**
     * Compute SHA-256 hash from an InputStream on the phone CPU.
     * Reads in 64KB chunks to avoid memory spikes. Call on Dispatchers.IO.
     */
    fun computeSha256OnPhone(inputStream: InputStream): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536) // 64KB chunks
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }

    /**
     * Compute SHA-256 from a limited number of bytes (for Range-based partial hashing).
     * Used when we download only the first N bytes of a file for speed.
     */
    fun computeSha256Partial(inputStream: InputStream, maxBytes: Long): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var totalRead = 0L
        while (totalRead < maxBytes) {
            val toRead = minOf(buffer.size.toLong(), maxBytes - totalRead).toInt()
            val bytesRead = inputStream.read(buffer, 0, toRead)
            if (bytesRead == -1) break
            digest.update(buffer, 0, bytesRead)
            totalRead += bytesRead
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }

    /**
     * Whether a MIME type should be gzip-compressed (text-like content).
     */
    fun shouldCompress(mimeType: String): Boolean {
        val mt = mimeType.lowercase()
        return mt.startsWith("text/") || mt.contains("json") || mt.contains("xml")
                || mt.contains("javascript") || mt.contains("css") || mt.contains("html")
                || mt.contains("csv") || mt.contains("xml") || mt.contains("yaml")
    }
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
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

    private val wolPorts = intArrayOf(9, 7)

    fun sendMagicPacket(macAddress: String, targetHost: String? = null): Boolean =
        smartWakeOnLan(macAddress, targetHost).success

    fun smartWakeOnLan(macAddress: String, targetHost: String? = null): WolResult {
        val macBytes = parseMacAddress(macAddress)
            ?: return WolResult(false, "NONE", "Địa chỉ MAC không hợp lệ. Ví dụ đúng: AA:BB:CC:DD:EE:FF")
        val magicPacket = buildMagicPacket(macBytes)
        val targets = discoverBroadcastAddresses(targetHost)
        val errors = mutableListOf<String>()
        var sentCount = 0

        return try {
            withWifiMulticastLock {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.reuseAddress = true
                    repeat(3) { round ->
                        targets.forEach { address ->
                            wolPorts.forEach { port ->
                                try {
                                    socket.send(DatagramPacket(magicPacket, magicPacket.size, address, port))
                                    sentCount++
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                    if (errors.size < 3) errors += "${address.hostAddress}:$port - ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                        }
                        if (round < 2) Thread.sleep(80)
                    }
                }
            }

            if (sentCount > 0) {
                val targetText = targets.joinToString(", ") { it.hostAddress ?: it.hostName }
                WolResult(true, "LAN", "Đã phát gói Wake-on-LAN qua LAN tới $targetText ($sentCount gói).")
            } else {
                val detail = errors.firstOrNull()?.let { ": $it" } ?: "."
                WolResult(false, "NONE", "Không thể gửi Wake-on-LAN qua mạng LAN$detail")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            WolResult(false, "NONE", "Không thể gửi Wake-on-LAN: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun parseMacAddress(macAddress: String): ByteArray? {
        val cleanMac = macAddress.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (cleanMac.length != 12) return null
        return try {
            ByteArray(6) { index -> cleanMac.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            null
        }
    }

    private fun buildMagicPacket(macBytes: ByteArray): ByteArray =
        ByteArray(6 + 16 * 6).also { packet ->
            for (index in 0 until 6) packet[index] = 0xFF.toByte()
            var offset = 6
            repeat(16) {
                System.arraycopy(macBytes, 0, packet, offset, macBytes.size)
                offset += macBytes.size
            }
        }

    private fun discoverBroadcastAddresses(targetHost: String? = null): List<InetAddress> {
        val targetAddress = resolveIpv4Host(targetHost)
        val addresses = linkedSetOf<InetAddress>()
        targetAddress?.let { fallbackCidr24Broadcast(it)?.let(addresses::add) }
        runCatching { addresses += InetAddress.getByName("255.255.255.255") }
        targetAddress?.let(addresses::add)

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val localAddress = interfaceAddress.address as? Inet4Address ?: return@forEach
                    if (!isPrivateIpv4(localAddress) && !localAddress.isLinkLocalAddress) return@forEach
                    if (targetAddress != null && !isSameNetwork(targetAddress, interfaceAddress)) return@forEach

                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && broadcast.address.size == 4) {
                        addresses += broadcast
                    } else {
                        derivedBroadcastAddress(interfaceAddress)?.let { addresses += it }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("WolUtil", "Không thể đọc broadcast LAN: ${e.message}")
        }

        return addresses.toList()
    }

    private fun resolveIpv4Host(targetHost: String?): Inet4Address? {
        val raw = targetHost?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val host = try {
            val normalized = if (raw.contains("://")) raw else "http://$raw"
            URI(normalized).host ?: raw
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            raw
        }
            .trim('[', ']')
            .substringBefore('/')
            .substringBefore(':')
            .takeIf { it.isNotBlank() }
            ?: return null

        return runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull()
    }

    private fun fallbackCidr24Broadcast(address: Inet4Address): InetAddress? {
        if (!isPrivateIpv4(address)) return null
        val bytes = address.address.copyOf()
        bytes[3] = 0xFF.toByte()
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    private fun isSameNetwork(targetAddress: Inet4Address, interfaceAddress: InterfaceAddress): Boolean {
        val localAddress = interfaceAddress.address as? Inet4Address ?: return false
        val prefixLength = interfaceAddress.networkPrefixLength.toInt()
        if (prefixLength !in 0..32) return false
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return (ipv4ToLong(targetAddress) and mask) == (ipv4ToLong(localAddress) and mask)
    }

    private fun ipv4ToLong(address: Inet4Address): Long =
        address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF).toLong() }

    private fun isPrivateIpv4(address: Inet4Address): Boolean {
        val bytes = address.address.map { it.toInt() and 0xFF }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }

    private fun derivedBroadcastAddress(interfaceAddress: InterfaceAddress): InetAddress? {
        val address = interfaceAddress.address as? Inet4Address ?: return null
        val prefixLength = interfaceAddress.networkPrefixLength.toInt()
        if (prefixLength !in 0..31) return null

        val ip = ipv4ToLong(address)
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val broadcast = (ip and mask) or (mask xor 0xFFFFFFFFL)
        val bytes = byteArrayOf(
            ((broadcast ushr 24) and 0xFF).toByte(),
            ((broadcast ushr 16) and 0xFF).toByte(),
            ((broadcast ushr 8) and 0xFF).toByte(),
            (broadcast and 0xFF).toByte()
        )
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    private inline fun <T> withWifiMulticastLock(block: () -> T): T {
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifiManager = NasApplication.instance.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifiManager?.createMulticastLock("NASWebDAV:WakeOnLan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("WolUtil", "Không thể giữ khóa multicast Wi-Fi: ${e.message}")
        }

        return try {
            block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ImageFingerprint — Average Hash (aHash) cho ảnh
// ─────────────────────────────────────────────────────────────────────────────
object ImageFingerprint {
    private const val HASH_SIZE = 8

    fun computeAHash(bitmap: Bitmap): String? {
        return try {
            val small = bitmap.scale(HASH_SIZE, HASH_SIZE)
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    fun hammingDistance(hash1: String, hash2: String): Int {
        if (hash1.length != 16 || hash2.length != 16) return 64
        // FIX: Không dùng toLong() vì sẽ overflow âm thầm với hash ≥ 0x8000000000000000
        // BigInteger.xor().bitCount() đảm bảo đúng với toàn bộ không gian 64-bit
        return try { java.math.BigInteger(hash1, 16).xor(java.math.BigInteger(hash2, 16)).bitCount() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { 64 }
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
                    try { decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_HARDWARE } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                var sampleSize = 1
                while (options.outWidth / sampleSize > 512 || options.outHeight / sampleSize > 512) sampleSize *= 2
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565 }) }
            }
            bitmap?.let { computeAHash(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } finally { bitmap?.recycle() }
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } finally { bitmap?.recycle() }
    }

    fun computeFromBytes(data: ByteArray): String? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = 4; inPreferredConfig = Bitmap.Config.RGB_565 })
            bitmap?.let { computeAHash(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } finally { bitmap?.recycle() }
    }
}
