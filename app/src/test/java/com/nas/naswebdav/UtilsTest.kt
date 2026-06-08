package com.nas.naswebdav

import com.nas.naswebdav.utils.FormatUtils
import com.nas.naswebdav.utils.HashUtils
import com.nas.naswebdav.utils.ImageFingerprint
import com.nas.naswebdav.utils.MediaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test JVM cho các hàm logic thuần trong Utils.kt.
 * Không phụ thuộc Android framework → chạy bằng testDebugUnitTest.
 */
class UtilsTest {

    // ----- FormatUtils.formatBytes (nhánh số nguyên không phụ thuộc locale) -----
    @Test fun formatBytes_zero() = assertEquals("0 B", FormatUtils.formatBytes(0))
    @Test fun formatBytes_bytes() = assertEquals("512 B", FormatUtils.formatBytes(512))
    @Test fun formatBytes_maxBytes() = assertEquals("1023 B", FormatUtils.formatBytes(1023))
    @Test fun formatBytes_kb_unit() = assertTrue(FormatUtils.formatBytes(1024).endsWith("KB"))
    @Test fun formatBytes_mb_unit() = assertTrue(FormatUtils.formatBytes(1024L * 1024).endsWith("MB"))
    @Test fun formatBytes_gb_unit() = assertTrue(FormatUtils.formatBytes(1024L * 1024 * 1024).endsWith("GB"))

    // ----- FormatUtils.ensureTrailingSlash -----
    @Test fun trailingSlash_added() = assertEquals("http://x/", FormatUtils.ensureTrailingSlash("http://x"))
    @Test fun trailingSlash_kept() = assertEquals("http://x/", FormatUtils.ensureTrailingSlash("http://x/"))

    // ----- HashUtils.md5 (vector chuẩn) -----
    @Test fun md5_knownVector() =
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtils.md5("hello"))

    @Test fun md5_emptyString() =
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", HashUtils.md5(""))

    @Test fun md5Bytes_matchesString() =
        assertEquals(HashUtils.md5("hello"), HashUtils.md5Bytes("hello".toByteArray()))

    // ----- MediaUtils -----
    @Test fun isVideo_caseInsensitive() = assertTrue(MediaUtils.isVideo("clip.MP4"))
    @Test fun isVideo_negative() = assertFalse(MediaUtils.isVideo("note.txt"))
    @Test fun isImage_caseInsensitive() = assertTrue(MediaUtils.isImage("photo.JPG"))
    @Test fun isImage_negative() = assertFalse(MediaUtils.isImage("clip.mp4"))

    // ----- ImageFingerprint.hammingDistance / isSimilar -----
    @Test fun hamming_identical() =
        assertEquals(0, ImageFingerprint.hammingDistance("00000000000000ff", "00000000000000ff"))

    @Test fun hamming_fourBits() =
        assertEquals(4, ImageFingerprint.hammingDistance("0000000000000000", "000000000000000f"))

    @Test fun hamming_highBitNoOverflow() =
        // hash ≥ 0x8000... từng gây overflow khi dùng toLong(); BigInteger phải trả 1
        assertEquals(1, ImageFingerprint.hammingDistance("8000000000000000", "0000000000000000"))

    @Test fun hamming_invalidLength() =
        assertEquals(64, ImageFingerprint.hammingDistance("abc", "def"))

    @Test fun isSimilar_withinThreshold() =
        assertTrue(ImageFingerprint.isSimilar("0000000000000000", "000000000000000f", threshold = 5))

    @Test fun isSimilar_overThreshold() =
        assertFalse(ImageFingerprint.isSimilar("0000000000000000", "00000000000000ff", threshold = 5))
}
