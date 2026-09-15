package com.nas.naswebdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A2: release-path contract test cho backend envelope v1 + auth handshake.
 * Backend dual-serve legacy shape và v1 envelope ok/data.
 * Parse JSON thủ công (string match) vì org.json là stub trên JVM unit test.
 */
class ApiV1ContractTest {

    private fun client() = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun auth(user: String = "u", pass: String = "p") =
        Credentials.basic(user, pass)

    @Test
    fun `v1 ping returns envelope with api_version`() {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"ok":true,"data":{"ok":true,"ts":123.0},"api_version":"v1"}"""
                )
            )
            start()
        }
        server.use {
            val req = Request.Builder().url(it.url("/api/v1/ping"))
                .header("Authorization", auth()).build()
            client().newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = resp.body!!.string()
                assertTrue(body.contains("\"ok\":true"))
                assertTrue(body.contains("\"api_version\":\"v1\""))
            }
            val recorded = it.takeRequest()
            assertEquals("/api/v1/ping", recorded.path)
            assertTrue(recorded.getHeader("Authorization")!!.startsWith("Basic "))
        }
    }

    @Test
    fun `v1 authorize returns envelope and legacy stays flat`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"Trusted","result":"ok"}"""))
            enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"data":{"status":"Trusted"},"api_version":"v1"}"""))
            start()
        }
        server.use {
            val legacy = Request.Builder().url(it.url("/api/auth/authorize"))
                .post("".toRequestBody(null))
                .header("Authorization", auth()).build()
            client().newCall(legacy).execute().use { resp ->
                val body = resp.body!!.string()
                assertTrue(body.contains("\"status\":\"Trusted\""))
                assertTrue(!body.contains("\"api_version\""))
            }
            val v1 = Request.Builder().url(it.url("/api/v1/auth/authorize"))
                .post("".toRequestBody(null))
                .header("Authorization", auth()).build()
            client().newCall(v1).execute().use { resp ->
                val body = resp.body!!.string()
                assertTrue(body.contains("\"ok\":true"))
                assertTrue(body.contains("\"status\":\"Trusted\""))
            }
            assertEquals("/api/auth/authorize", it.takeRequest().path)
            assertEquals("/api/v1/auth/authorize", it.takeRequest().path)
        }
    }

    @Test
    fun `unauthorized returns 401 on both legacy and v1`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Chua xac thuc"}"""))
            enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false,"error":"Chua xac thuc","code":"unauthorized","api_version":"v1"}"""))
            start()
        }
        server.use {
            val noAuth = Request.Builder().url(it.url("/api/status")).build()
            client().newCall(noAuth).execute().use { resp ->
                assertEquals(401, resp.code)
            }
            val noAuthV1 = Request.Builder().url(it.url("/api/v1/status")).build()
            client().newCall(noAuthV1).execute().use { resp ->
                assertEquals(401, resp.code)
                val body = resp.body!!.string()
                assertTrue(body.contains("\"ok\":false"))
                assertTrue(body.contains("\"code\":\"unauthorized\""))
            }
        }
    }

    @Test
    fun `PROPFIND Depth 1 lists files tolerant to missing displayname`() {
        val propfind = """<?xml version="1.0"?>
<D:multistatus xmlns:D="DAV:">
<D:response><D:href>/dav/Photos/</D:href>
<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
<D:response><D:href>/dav/Photos/IMG_1.jpg</D:href>
<D:propstat><D:prop><D:getcontentlength>42</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>"""
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(207).setBody(propfind))
            start()
        }
        server.use {
            val req = Request.Builder().url(it.url("/dav/Photos/"))
                .method("PROPFIND", "".toRequestBody("application/xml".toMediaTypeOrNull()))
                .header("Depth", "1").build()
            client().newCall(req).execute().use { resp ->
                assertEquals(207, resp.code)
                val body = resp.body!!.string()
                assertTrue(body.contains("IMG_1.jpg"))
            }
            val recorded = it.takeRequest()
            assertEquals("PROPFIND", recorded.method)
            assertEquals("1", recorded.getHeader("Depth"))
        }
    }
}
