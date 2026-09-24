package com.nas.naswebdav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
class BackupContentVerificationRegressionTest {
    private fun serverFor(bytes: ByteArray, etag: String = "\"v1\"", changedAtEnd: Boolean = false,
                          getStatus: Int = 200): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            var heads = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    heads++
                    return MockResponse().setResponseCode(200)
                        .addHeader("Content-Length", bytes.size)
                        .addHeader("ETag", if (changedAtEnd && heads > 1) "\"v2\"" else etag)
                }
                return MockResponse().setResponseCode(getStatus).addHeader("ETag", etag)
                    .setBody(Buffer().write(bytes))
            }
        }
        start()
        WebDavManager.connect(url("/dav/").toString(), "u", "p")
    }

    @Test
    fun `matching complete content uses conditional GET and rechecks version`() = runTest {
        val bytes = "complete backup".toByteArray()
        serverFor(bytes).use { server ->
            assertTrue(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { bytes.inputStream() })
            val requests = List(server.requestCount) { server.takeRequest() }
            assertEquals(listOf("HEAD", "GET", "HEAD"), requests.map { it.method })
            assertEquals("\"v1\"", requests[1].getHeader("If-Match"))
            assertNull(requests[1].getHeader("Range"))
        }
    }

    @Test
    fun `equal size and equal first megabyte do not permit deleting different source`() = runTest {
        val source = ByteArray(1048577) { 3 }
        val remote = source.copyOf().also { it[it.lastIndex] = 4 }
        serverFor(remote).use { server ->
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), source.size.toLong()) { source.inputStream() })
        }
    }

    @Test
    fun `changed version after matching download keeps source`() = runTest {
        val bytes = "same bytes".toByteArray()
        serverFor(bytes, changedAtEnd = true).use { server ->
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { bytes.inputStream() })
        }
    }

    @Test
    fun `weak version cannot authorize deletion`() = runTest {
        val bytes = "bytes".toByteArray()
        serverFor(bytes, etag = "W/\"v1\"").use { server ->
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { bytes.inputStream() })
        }
    }

    @Test
    fun `failed conditional download keeps source`() = runTest {
        val bytes = "bytes".toByteArray()
        serverFor(bytes, getStatus = 412).use { server ->
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { bytes.inputStream() })
        }
    }

    @Test
    fun `unreadable source cannot fall back to metadata verification`() = runTest {
        val bytes = "bytes".toByteArray()
        serverFor(bytes).use { server ->
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { null })
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { throw IOException("source unavailable") })
        }
    }

    @Test
    fun `source must contain exactly the expected bytes`() = runTest {
        val bytes = "bytes".toByteArray()
        serverFor(bytes).use { server ->
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { bytes.copyOf(4).inputStream() })
            assertFalse(WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { (bytes + 1.toByte()).inputStream() })
        }
    }

    @Test
    fun `source cancellation propagates instead of becoming a verification result`() = runTest {
        val bytes = "bytes".toByteArray()
        serverFor(bytes).use { server ->
            try {
                WebDavManager.verifyBackupContent(server.url("/dav/a").toString(), bytes.size.toLong()) { throw CancellationException("cancel verification") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
        }
    }
}
