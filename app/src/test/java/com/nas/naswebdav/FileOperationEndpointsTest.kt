package com.nas.naswebdav

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileOperationEndpointsTest {
    @Test
    fun `MKCOL and MOVE verbs are dispatched to WebDAV server`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(201).setBody("Created"))
            enqueue(MockResponse().setResponseCode(201).setBody("Moved"))
            start()
        }
        server.use {
            val client = okhttp3.OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val src = okhttp3.Request.Builder()
                .url(it.url("/dav/Photos/New Album/"))
                .method("MKCOL", "".toRequestBody(null))
                .build()
            val dst = okhttp3.Request.Builder()
                .url(it.url("/dav/Photos/Alpine/IMG_0001.jpg"))
                .header("Destination", it.url("/dav/Photos/Alpine/IMG_0001.jpg").toString())
                .header("Overwrite", "F")
                .method("MOVE", "".toRequestBody(null))
                .build()
            client.newCall(src).execute().use { assertEquals(201, it.code) }
            client.newCall(dst).execute().use { assertEquals(201, it.code) }
            assertEquals("MKCOL", it.takeRequest().method)
            assertEquals("MOVE", it.takeRequest().method)
        }
    }

    @Test
    fun `connection drop during PUT is distinguishable from successful upload`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            start()
        }
        server.use {
            val client = okhttp3.OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url(it.url("/dav/upload.bin"))
                .put("hello".toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .build()
            val thrown = try { client.newCall(request).execute().use { it.code } }
            catch (e: IOException) { -1 }
            assertTrue("expected PUT to fail with disconnect but got code=$thrown", thrown == -1)
        }
    }
}
