package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class TarStreamRequestBodyTest {

    @Test
    fun testBuildUstarHeader_validBlockSizeAndMagic() {
        val header = TarStreamRequestBody.buildUstarHeader(
            path = "DCIM/Camera/IMG_20261120_123456.jpg",
            size = 1048576L,
            mtimeSec = 1732100000L
        )

        // Block size bắt buộc là 512 bytes
        assertEquals(512, header.size)

        // Magic "ustar\0" ở offset 257..262
        val magic = String(header, 257, 6, Charsets.US_ASCII)
        assertEquals("ustar\u0000", magic)

        // Version "00" ở offset 263..264
        val version = String(header, 263, 2, Charsets.US_ASCII)
        assertEquals("00", version)

        // Typeflag '0' ở offset 156
        assertEquals('0'.code.toByte(), header[156])

        // Verify checksum
        val chksumString = String(header, 148, 8, Charsets.US_ASCII).trim { it <= ' ' }
        val chksumParsed = java.lang.Long.parseLong(chksumString, 8)

        var calculatedChksum = 0L
        for (i in header.indices) {
            val b = if (i in 148..155) ' '.code.toByte() else header[i]
            calculatedChksum += (b.toInt() and 0xFF)
        }
        assertEquals(calculatedChksum, chksumParsed)
    }

    @Test
    fun testBuildUstarHeader_longPathPrefixSplitting() {
        val longPath = "DCIM/Very/Long/Directory/Path/That/Exceeds/One/Hundred/Characters/For/Testing/Purpose/SubFolder/ImageFile.jpg"
        val header = TarStreamRequestBody.buildUstarHeader(
            path = longPath,
            size = 512L,
            mtimeSec = 1732100000L
        )

        assertEquals(512, header.size)
        val prefix = String(header, 345, 155, Charsets.UTF_8).trimEnd { it == '\u0000' }
        val name = String(header, 0, 100, Charsets.UTF_8).trimEnd { it == '\u0000' }

        assertTrue(prefix.isNotEmpty())
        assertEquals("ImageFile.jpg", name)
        assertEquals(longPath, "$prefix/$name")
    }

    @Test
    fun testBuildEntryHeaderBlocks_gnuLongNameWhenFilenameExceeds100Bytes() {
        val longFileName = "AQM2fyAKy6pwC8bMJ5Cerah-VHRo0jro86eStuQGMU0uhtKJaBQ7ApVi_T0FIv3E_uahfqt3uPXK7eR2tssnIFC-s4zTL8ON__id1000508116.mp4"
        assertTrue(longFileName.toByteArray(Charsets.UTF_8).size > 100)

        val blocks = TarStreamRequestBody.buildEntryHeaderBlocks(
            path = longFileName,
            size = 4096L,
            mtimeSec = 1732100000L
        )

        // 512 (GNU 'L' header) + 512 (long name payload) + 512 (regular '0' header) = 1536 bytes
        assertEquals(1536, blocks.size)
        assertEquals('L'.code.toByte(), blocks[156])
        val extractedLongName = String(blocks, 512, longFileName.length, Charsets.UTF_8)
        assertEquals(longFileName, extractedLongName)
        assertEquals('0'.code.toByte(), blocks[1024 + 156])
    }
}
