package com.nas.naswebdav

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
class EtagPreconditionTest {

    @Before
    fun ensureAppInstance() {
        // TestNasApplication.onCreate đã gán instance (không chạy worker schedule).
        runCatching { NasApplication.instance }.getOrNull()
            ?: error("TestNasApplication chưa gán instance")
    }

    @Test
    fun `getFileETag reads ETag header and upload sends If-Match`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"abc123\""))
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()

            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            val fileUrl = server.url("/dav/photo.jpg").toString()

            val etag = WebDavManager.getFileETag(fileUrl)
            assertEquals("\"abc123\"", etag)
            server.takeRequest() // HEAD

            WebDavManager.uploadStreamWithProgress(
                fileUrl, "data".byteInputStream(), 4, "text/plain",
                { _, _ -> }, etag
            )
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("\"abc123\"", put.getHeader("If-Match"))
        }
    }

    @Test
    fun `missing ETag uploads without If-Match`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()

            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            val fileUrl = server.url("/dav/new.jpg").toString()

            val etag = WebDavManager.getFileETag(fileUrl)
            assertEquals(null, etag)
            server.takeRequest() // HEAD

            WebDavManager.uploadStreamWithProgress(
                fileUrl, "data".byteInputStream(), 4, "text/plain",
                { _, _ -> }, etag
            )
            val put = server.takeRequest()
            assertEquals(null, put.getHeader("If-Match"))
        }
    }
}
