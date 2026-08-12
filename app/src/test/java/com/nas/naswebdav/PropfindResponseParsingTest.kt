package com.nas.naswebdav

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PropfindResponseParsingTest {
    private val xmlMediaType = "application/xml; charset=utf-8".toMediaTypeOrNull()

    @Test
    fun `propfind depth header is set`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(207).setBody("""
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/remote.php/dav/files/admin/Documents/</D:href>
                    <D:propstat>
                      <D:prop><D:getlastmodified>Mon, 12 Aug 2026 10:00:00 GMT</D:getlastmodified></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
            """.trimIndent()))
            start()
        }
        server.use {
            val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            val request = Request.Builder()
                .url(it.url("/remote.php/dav/files/admin/"))
                .header("Depth", "1")
                .method("PROPFIND", "".toRequestBody(xmlMediaType))
                .build()
            val response = client.newCall(request).execute()
            assertEquals(207, response.code)
            val received = it.takeRequest()
            assertEquals("PROPFIND", received.method)
            assertNotEquals(null, received.getHeader("Depth"))
        }
    }

    @Test
    fun `unauthorized propfind surfaces 401 to caller`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
            start()
        }
        server.use {
            val client = OkHttpClient.Builder().build()
            val request = Request.Builder()
                .url(it.url("/remote.php/dav/files/admin/"))
                .method("PROPFIND", "".toRequestBody(xmlMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(401, response.code)
                assertTrue(!response.isSuccessful)
            }
        }
    }
}
