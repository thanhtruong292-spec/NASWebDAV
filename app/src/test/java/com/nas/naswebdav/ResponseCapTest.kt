package com.nas.naswebdav

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ResponseCapTest {

    private fun cappedGet(server: MockWebServer, maxBytes: Int): String? {
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(server.url("/api/x")).build()
        client.newCall(request).execute().use { response ->
            return WebDavManager.readCappedBody(response, maxBytes)
        }
    }

    @Test
    fun `small body passes through`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            server.start()
            assertEquals("{\"ok\":true}", cappedGet(server, 1024))
        }
    }

    @Test
    fun `oversized body returns null instead of OOM`() {
        MockWebServer().use { server ->
            val big = Buffer().apply {
                repeat(3000) { writeUtf8("0123456789ABCDEF") } // ~48KB
            }
            server.enqueue(MockResponse().setResponseCode(200).setBody(big))
            server.start()
            assertNull(cappedGet(server, 1024))
        }
    }

    @Test
    fun `error body capped at 8KB`() {
        assertEquals(8 * 1024, WebDavManager.MAX_ERROR_BODY_BYTES)
        assertTrue(WebDavManager.MAX_PROPFIND_BYTES >= 1024 * 1024)
        assertTrue(WebDavManager.MAX_PROPFIND_ENTRIES >= 1000)
    }
}
