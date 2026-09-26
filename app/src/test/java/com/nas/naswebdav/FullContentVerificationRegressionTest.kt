package com.nas.naswebdav

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Exercises the production GET/hash seam used by duplicate cleanup, not a copied predicate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
class FullContentVerificationRegressionTest {
    @Test
    fun `short successful response is not evidence for expected complete file`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("prefix"))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            assertNull(WebDavManager.getFullSha256PhoneStream(server.url("/dav/a").toString(), 100))
        }
    }

    @Test
    fun `overlong successful response is not evidence for expected complete file`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("longer"))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            assertNull(WebDavManager.getFullSha256PhoneStream(server.url("/dav/a").toString(), 3))
        }
    }

    @Test
    fun `partial content response is rejected even when its bytes match expected size`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-5/100").setBody("prefix"))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            assertNull(WebDavManager.getFullSha256PhoneStream(server.url("/dav/a").toString(), 6))
        }
    }

    @Test
    fun `full hash distinguishes equal size files whose first megabyte matches`() = runTest {
        val first = ByteArray(1048577) { 7 }
        val second = first.copyOf().also { it[it.lastIndex] = 8 }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(first)))
            server.enqueue(MockResponse().setBody(Buffer().write(second)))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            val a = WebDavManager.getFullSha256PhoneStream(server.url("/dav/a").toString(), first.size.toLong())
            val b = WebDavManager.getFullSha256PhoneStream(server.url("/dav/b").toString(), second.size.toLong())
            val expected = MessageDigest.getInstance("SHA-256").digest(first).joinToString("") { "%02x".format(it) }
            assertEquals(expected, a)
            assertNotEquals(a, b)
            assertNull(server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun `server failure never produces a verification hash`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            assertNull(WebDavManager.getFullSha256PhoneStream(server.url("/dav/a").toString(), 11))
        }
    }

    // F3: hash gan ETag — HEAD truoc (size+ETag) -> GET If-Match -> HEAD recheck.
    @Test
    fun `etag-bound hash returns hash with matching etag`() = runTest {
        val bytes = "versioned content".toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .addHeader("Content-Length", bytes.size).addHeader("ETag", "\"v9\""))
            server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"v9\"")
                .setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setResponseCode(200)
                .addHeader("Content-Length", bytes.size).addHeader("ETag", "\"v9\""))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            val result = WebDavManager.getFullSha256WithEtag(server.url("/dav/a").toString(), bytes.size.toLong())
            assertEquals("\"v9\"", result?.second)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, result?.first)
            val requests = List(server.requestCount) { server.takeRequest() }
            assertEquals(listOf("HEAD", "GET", "HEAD"), requests.map { it.method })
            assertEquals("\"v9\"", requests[1].getHeader("If-Match"))
        }
    }

    @Test
    fun `etag-bound hash rejects changed version after download`() = runTest {
        val bytes = "versioned content".toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .addHeader("Content-Length", bytes.size).addHeader("ETag", "\"v9\""))
            server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"v9\"")
                .setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setResponseCode(200)
                .addHeader("Content-Length", bytes.size).addHeader("ETag", "\"v10\""))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            assertNull(WebDavManager.getFullSha256WithEtag(server.url("/dav/a").toString(), bytes.size.toLong()))
        }
    }

    @Test
    fun `etag-bound hash rejects weak etag`() = runTest {
        val bytes = "bytes".toByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .addHeader("Content-Length", bytes.size).addHeader("ETag", "W/\"v9\""))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            assertNull(WebDavManager.getFullSha256WithEtag(server.url("/dav/a").toString(), bytes.size.toLong()))
        }
    }
}
