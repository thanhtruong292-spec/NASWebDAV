package com.nas.naswebdav.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsTest {
    @Test
    fun `formatBytes handles byte and binary unit boundaries`() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("1023 B", FormatUtils.formatBytes(1023))
        // Format is locale-dependent ("%.1f KB" uses JVM default locale).
        val kb = FormatUtils.formatBytes(1024)
        assertTrue("expected KB unit, got <$kb>", kb.endsWith(" KB"))
        assertEquals(1.0, kb.removeSuffix(" KB").replace(',', '.').toDouble(), 0.05)
        val mb = FormatUtils.formatBytes(1024 * 1024)
        assertTrue("expected MB unit, got <$mb>", mb.endsWith(" MB"))
        assertEquals(1.0, mb.removeSuffix(" MB").replace(',', '.').toDouble(), 0.05)
    }

    @Test
    fun `formatElapsedTime handles negative and minute boundaries`() {
        assertEquals("--:--", FormatUtils.formatElapsedTime(-1))
        assertEquals("00:00", FormatUtils.formatElapsedTime(0))
        assertEquals("01:05", FormatUtils.formatElapsedTime(65_000))
    }

    @Test
    fun `formatPlayerTime supports hours`() {
        assertEquals("0:00", FormatUtils.formatPlayerTime(0))
        assertEquals("1:05", FormatUtils.formatPlayerTime(65_000))
        assertEquals("1:01:05", FormatUtils.formatPlayerTime(3_665_000))
    }

    @Test
    fun `sanitizeFileName removes unsafe characters and preserves extension`() {
        val result = FormatUtils.sanitizeFileName("Ảnh:*? demo.mp4", maxLen = 14)
        assertFalse(result.any { it in "\\/:*?\"<>|#%+" })
        assertTrue(result.endsWith(".mp4"))
        assertTrue(result.length <= 14)
    }

    @Test
    fun `media classification is case insensitive`() {
        assertTrue(MediaUtils.isVideo("clip.MP4"))
        assertTrue(MediaUtils.isImage("photo.JpG"))
        assertFalse(MediaUtils.isVideo("photo.txt"))
        assertFalse(MediaUtils.isImage("clip.mp4"))
    }

    @Test
    fun `hash utils returns deterministic md5`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtils.md5("hello"))
    }

    @Test
    fun `escapeLike neutralizes wildcards`() {
        // R4-P2: /foo_bar/ khong khop /fooXbar/ sau escape.
        val bs = "\\"
        assertEquals("foo" + bs + "_bar", FormatUtils.escapeLike("foo_bar"))
        assertEquals("100" + bs + "%", FormatUtils.escapeLike("100%"))
        assertEquals("a" + bs + bs + "b", FormatUtils.escapeLike("a" + bs + "b"))
    }

    @Test
    fun `matchesFileQuery keeps accent-insensitive offline search`() {
        // R4-P2: "bao cao" khop "Báo cáo.pdf" khi offline (SQL LIKE tho khong lam duoc).
        assertTrue(FormatUtils.matchesFileQuery("Báo cáo.pdf", "bao cao"))
        assertTrue(FormatUtils.matchesFileQuery("IMG_2024_Alpine.jpg", "alpine"))
        assertTrue(FormatUtils.matchesFileQuery("Tai lieu bao cao.pdf", "bao cao tai lieu"))
        assertTrue(!FormatUtils.matchesFileQuery("img1.jpg", "img2"))
    }

    @Test
    fun `shouldCompress detects text formats`() {
        assertTrue(HashUtils.shouldCompress("text/plain"))
        assertTrue(HashUtils.shouldCompress("application/json"))
        assertFalse(HashUtils.shouldCompress("video/mp4"))
    }
}
