package com.nas.naswebdav

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.Locale

/**
 * Mục dữ liệu tệp trong một lô truyền TAR stream.
 */
data class TarBatchFileItem(
    val uri: Uri,
    val relativePath: String,
    val size: Long,
    val mtimeSec: Long = System.currentTimeMillis() / 1000L,
    val sourceProofSha256: String = "",
    val dataPath: String = ""
)

/**
 * RequestBody truyền luồng TAR (POSIX ustar) trực tiếp từ MediaStore ra OkHttp socket,
 * không tạo file nén tạm trên bộ nhớ máy (zero-disk-footprint), buffer 64KB.
 */
class TarStreamRequestBody(
    private val context: Context,
    private val items: List<TarBatchFileItem>,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long, speedBps: Long) -> Unit)? = null
) : RequestBody() {

    private val contentType = "application/x-tar".toMediaType()

    private val calculatedContentLength: Long by lazy {
        var len = 0L
        for (item in items) {
            len += 512L // Header
            len += item.size // Payload
            val pad = (512L - (item.size % 512L)) % 512L
            len += pad // 512-byte block padding
        }
        len += 1024L // End of archive blocks (2 x 512 bytes zeros)
        len
    }

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = calculatedContentLength

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = calculatedContentLength
        var bytesWritten = 0L
        val startTime = System.currentTimeMillis()
        var lastProgressTime = startTime
        val buffer = ByteArray(65536)

        for (item in items) {
            val header = buildUstarHeader(item.relativePath, item.size, item.mtimeSec)
            sink.write(header)
            bytesWritten += header.size

            var stream: InputStream? = null
            try {
                stream = context.contentResolver.openInputStream(item.uri)
                if (stream != null) {
                    var read: Int
                    var fileRemaining = item.size
                    while (fileRemaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), fileRemaining).toInt()
                        read = stream.read(buffer, 0, toRead)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        bytesWritten += read
                        fileRemaining -= read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime >= 200) {
                            val elapsedSec = (now - startTime) / 1000.0
                            val speed = if (elapsedSec > 0.05) (bytesWritten / elapsedSec).toLong() else 0L
                            lastProgressTime = now
                            onProgress?.invoke(bytesWritten, totalBytes, speed)
                        }
                    }
                }
            } finally {
                stream?.close()
            }

            // Ghi padding cho tròn khối 512 bytes
            val padding = (512L - (item.size % 512L)) % 512L
            if (padding > 0) {
                val padBytes = ByteArray(padding.toInt())
                sink.write(padBytes)
                bytesWritten += padding
            }
        }

        // Ghi 1024 bytes kết thúc lưu trữ TAR
        val endOfArchive = ByteArray(1024)
        sink.write(endOfArchive)
        bytesWritten += 1024L

        val totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        val finalSpeed = if (totalElapsedSec > 0.05) (bytesWritten / totalElapsedSec).toLong() else 0L
        onProgress?.invoke(bytesWritten, totalBytes, finalSpeed)
    }

    companion object {
        /**
         * Sinh Header 512 bytes chuẩn POSIX ustar (IEEE 1003.1-1988).
         */
        fun buildUstarHeader(path: String, size: Long, mtimeSec: Long): ByteArray {
            val header = ByteArray(512)
            val normalizedPath = path.replace('\\', '/').trimStart('/')

            var namePart = normalizedPath
            var prefixPart = ""
            if (namePart.toByteArray(Charsets.UTF_8).size > 100) {
                val lastSlash = normalizedPath.lastIndexOf('/')
                if (lastSlash in 1..154) {
                    prefixPart = normalizedPath.substring(0, lastSlash)
                    namePart = normalizedPath.substring(lastSlash + 1)
                }
            }

            // 0..99: name
            writeAsciiString(header, 0, 100, namePart)
            // 100..107: mode (0644 in octal = 420 decimal)
            writeOctal(header, 100, 8, 420L)
            // 108..115: uid (0)
            writeOctal(header, 108, 8, 0L)
            // 116..123: gid (0)
            writeOctal(header, 116, 8, 0L)
            // 124..135: size (11 octal digits + null/space)
            writeOctal(header, 124, 12, size)
            // 136..147: mtime
            writeOctal(header, 136, 12, mtimeSec)
            // 156: typeflag '0' (Regular file)
            header[156] = '0'.code.toByte()
            // 257..262: magic "ustar\0"
            System.arraycopy("ustar\u0000".toByteArray(Charsets.US_ASCII), 0, header, 257, 6)
            // 263..264: version "00"
            header[263] = '0'.code.toByte()
            header[264] = '0'.code.toByte()
            // 265..296: uname
            writeAsciiString(header, 265, 32, "nas")
            // 297..328: gname
            writeAsciiString(header, 297, 32, "nas")
            // 345..499: prefix
            if (prefixPart.isNotEmpty()) {
                writeAsciiString(header, 345, 155, prefixPart)
            }

            // 148..155: chksum (tính tổng khi 8 bytes chksum là spaces 0x20)
            for (i in 148..155) {
                header[i] = ' '.code.toByte()
            }
            var checksum = 0L
            for (b in header) {
                checksum += (b.toInt() and 0xFF)
            }
            writeOctal(header, 148, 7, checksum)
            header[155] = ' '.code.toByte()

            return header
        }

        private fun writeAsciiString(target: ByteArray, offset: Int, maxLen: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val len = minOf(bytes.size, maxLen)
            System.arraycopy(bytes, 0, target, offset, len)
        }

        private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
            val octal = java.lang.Long.toOctalString(value)
            val padded = octal.padStart(length - 1, '0')
            val bytes = padded.toByteArray(Charsets.US_ASCII)
            val copyLen = minOf(bytes.size, length - 1)
            val startOffset = offset + (length - 1 - copyLen)
            System.arraycopy(bytes, bytes.size - copyLen, target, startOffset, copyLen)
            target[offset + length - 1] = 0 // Null terminate
        }
    }
}
