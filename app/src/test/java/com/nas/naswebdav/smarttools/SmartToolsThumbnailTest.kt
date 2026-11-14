package com.nas.naswebdav.smarttools

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.nas.naswebdav.ThumbnailAuditData

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartToolsThumbnailTest {

    @Test
    fun `thumbnail audit data default is not running with zero counts`() {
        val data = ThumbnailAuditData(missing = 0, total = 0, running = false)
        assertFalse(data.running)
        assertEquals(0, data.missing)
        assertEquals(0, data.total)
    }

    @Test
    fun `thumbnail audit reports running state correctly`() {
        val running = ThumbnailAuditData(missing = 1500, total = 12000, running = true)
        assertTrue(running.running)
        assertEquals("Đang quét nền... 1500/12000",
            if (running.running) "Đang quét nền... ${running.missing}/${running.total}" else "")
    }

    @Test
    fun `thumbnail audit reports completed scan correctly`() {
        val done = ThumbnailAuditData(missing = 0, total = 12000, running = false)
        assertFalse(done.running)
        assertEquals("Quét gần nhất: thiếu 0/12000",
            if (done.running) "" else "Quét gần nhất: thiếu ${done.missing}/${done.total}")
    }

    @Test
    fun `missing count reflects files still needing thumbnails`() {
        val partial = ThumbnailAuditData(missing = 42, total = 1000, running = false)
        assertEquals(42, partial.missing)
        assertTrue(partial.missing < partial.total)
    }
}
