package com.nas.naswebdav

import com.nas.naswebdav.utils.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * FormatUtilsTest — Unit tests for pure utility functions in utils/FormatUtils.
 *
 * Seams under test:
 * - Byte size formatting (B/KB/MB/GB/TB/PB)
 * - URL slash normalization
 * - Elapsed time formatting
 *
 * NOTE: formatBytes uses String.format() which respects system locale.
 * Tests below check for unit suffix presence and correct magnitude,
 * not exact decimal strings, to avoid locale-dependent failures.
 */
class FormatUtilsTest {

    // ─── formatBytes — unit & magnitude ────────────────────────────────────────

    @Test
    fun `formatBytes tra ve B cho nho hon 1024`() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("100 B", FormatUtils.formatBytes(100))
        assertEquals("1023 B", FormatUtils.formatBytes(1023))
    }

    @Test
    fun `formatBytes tra ve KB cho 1-1023 KB`() {
        val result = FormatUtils.formatBytes(1024)
        assertTrue("Expected KB unit but got: $result", result.contains("KB"))
    }

    @Test
    fun `formatBytes tra ve MB cho 1-1023 MB`() {
        val result = FormatUtils.formatBytes(1024L * 1024 * 5)
        assertTrue("Expected MB unit but got: $result", result.contains("MB"))
    }

    @Test
    fun `formatBytes tra ve GB cho 1-1023 GB`() {
        val result = FormatUtils.formatBytes(1024L * 1024 * 1024 * 3)
        assertTrue("Expected GB unit but got: $result", result.contains("GB"))
    }

    @Test
    fun `formatBytes tra ve TB cho 1-1023 TB`() {
        val result = FormatUtils.formatBytes(1024L * 1024 * 1024 * 1024 * 2)
        assertTrue("Expected TB unit but got: $result", result.contains("TB"))
    }

    @Test
    fun `formatBytes tra ve PB cho tren 1024 TB`() {
        val result = FormatUtils.formatBytes(1024L * 1024 * 1024 * 1024 * 1024 * 3)
        assertTrue("Expected PB unit but got: $result", result.contains("PB"))
    }

    // ─── ensureTrailingSlash / stripTrailingSlash ─────────────────────────────

    @Test
    fun `ensureTrailingSlash them slash neu chua co`() {
        assertEquals("http://nas.local/", FormatUtils.ensureTrailingSlash("http://nas.local"))
        assertEquals("http://nas.local/", FormatUtils.ensureTrailingSlash("http://nas.local/"))
    }

    @Test
    fun `stripTrailingSlash xoa slash cuoi`() {
        assertEquals("http://nas.local", FormatUtils.stripTrailingSlash("http://nas.local/"))
        assertEquals("http://nas.local", FormatUtils.stripTrailingSlash("http://nas.local"))
    }

    // ─── formatElapsedTime ─────────────────────────────────────────────────────

    @Test
    fun `formatElapsedTime tra ve MMSS dung cho 30 giay`() {
        assertEquals("00:30", FormatUtils.formatElapsedTime(30_000))
    }

    @Test
    fun `formatElapsedTime tra ve MMSS dung cho 5 phut`() {
        assertEquals("05:00", FormatUtils.formatElapsedTime(5 * 60_000L))
    }

    @Test
    fun `formatElapsedTime tra ve MMSS dung cho 59 phut 59 giay`() {
        assertEquals("59:59", FormatUtils.formatElapsedTime(59 * 60_000L + 59_000L))
    }

    @Test
    fun `formatElapsedTime tra ve dash cho gia tri am`() {
        assertEquals("--:--", FormatUtils.formatElapsedTime(-1))
        assertEquals("--:--", FormatUtils.formatElapsedTime(-1000))
    }
}
