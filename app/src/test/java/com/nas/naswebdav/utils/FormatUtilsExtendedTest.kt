package com.nas.naswebdav.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatUtilsExtendedTest {

    @Test
    fun `formatBytes handles exact boundaries`() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("1023 B", FormatUtils.formatBytes(1023))
        assertEquals("1.0 KB", FormatUtils.formatBytes(1024))
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024 * 1024))
    }

    @Test
    fun `formatElapsedTime formats hours minutes seconds`() {
        assertEquals("00:00", FormatUtils.formatElapsedTime(0))
        assertEquals("01:05", FormatUtils.formatElapsedTime(65_000))
        assertEquals("61:05", FormatUtils.formatElapsedTime(3_665_000))
        assertEquals("--:--", FormatUtils.formatElapsedTime(-1))
    }

    @Test
    fun `sanitizeFileName preserves extension and truncates`() {
        val result = FormatUtils.sanitizeFileName("Ảnh:*? demo.mp4", maxLen = 14)
        assertTrue(result.endsWith(".mp4"))
        assertTrue(result.length <= 14)
        assertFalse(result.any { it in "\\/:*?\"<>|#%+" })
    }

    @Test
    fun `hash utils md5 is deterministic`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtils.md5("hello"))
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtils.md5("hello"))
    }

    @Test
    fun `shouldCompress detects text formats and rejects media`() {
        assertTrue(HashUtils.shouldCompress("text/plain"))
        assertTrue(HashUtils.shouldCompress("application/json"))
        assertTrue(HashUtils.shouldCompress("text/html"))
        assertFalse(HashUtils.shouldCompress("video/mp4"))
        assertFalse(HashUtils.shouldCompress("image/jpeg"))
        assertFalse(HashUtils.shouldCompress("application/octet-stream"))
    }
}
