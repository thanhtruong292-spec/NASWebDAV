package com.nas.naswebdav

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class SmartNetworkReachabilityTest {
    @Test
    fun `healthy WebDAV avoids waiting for or requesting slow API probe`() {
        MockWebServer().use { apiServer ->
            MockWebServer().use { webDavServer ->
                apiServer.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setResponseCode(503))
                webDavServer.enqueue(MockResponse().setResponseCode(207))

                assertTrue(isApiOrWebDavReachable(
                    OkHttpClient(), apiServer.url("/api/ping").toString(),
                    webDavServer.url("/webdav/").toString(), "Basic test"
                ))

                val request = webDavServer.takeRequest()
                assertEquals("PROPFIND", request.method)
                assertEquals("0", request.getHeader("Depth"))
                assertEquals("Basic test", request.getHeader("Authorization"))
                assertEquals(0, apiServer.requestCount)
            }
        }
    }

    @Test
    fun `API health remains fallback when WebDAV probe fails`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(204))

            assertTrue(isApiOrWebDavReachable(
                OkHttpClient(), server.url("/api/ping").toString(),
                server.url("/webdav/").toString(), "Basic test"
            ))

            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("HEAD", server.takeRequest().method)
        }
    }

    @Test
    fun `WebDAV auth failure is not treated as reachable`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))

            assertFalse(isApiOrWebDavReachable(
                OkHttpClient(), server.url("/api/ping").toString(),
                server.url("/webdav/").toString(), "Basic test"
            ))
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("HEAD", server.takeRequest().method)
        }
    }

    @Test
    fun `redirect is not treated as a successful health check`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login"))

            val client = OkHttpClient.Builder().followRedirects(false).build()
            assertFalse(isApiOrWebDavReachable(
                client, server.url("/api/ping").toString(),
                server.url("/webdav/").toString(), "Basic test"
            ))
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("HEAD", server.takeRequest().method)
        }
    }

    @Test
    fun `WebDAV remains reachable when API connection fails`() {
        val apiServer = MockWebServer()
        apiServer.start()
        val apiUrl = apiServer.url("/api/ping").toString()
        apiServer.shutdown()

        MockWebServer().use { webDavServer ->
            webDavServer.enqueue(MockResponse().setResponseCode(207))

            assertTrue(isApiOrWebDavReachable(
                OkHttpClient(), apiUrl, webDavServer.url("/webdav/").toString(), "Basic test"
            ))
        }
    }

    @Test
    fun `healthy LAN returns before touching a slow Tailscale candidate`() = runBlocking {
        val lanUrl = "https://nas.lan:8822/webdav/"
        val tailscaleUrl = "http://100.90.135.102:8822/webdav/"
        val visited = mutableListOf<String>()

        val selected = withTimeoutOrNull(2_000) {
            selectFirstReachableConnectionUrl(listOf(tailscaleUrl, lanUrl)) { candidate ->
                visited += candidate
                if (candidate == tailscaleUrl) awaitCancellation()
                candidate == lanUrl
            }
        }

        assertEquals(lanUrl, selected)
        assertEquals(listOf(lanUrl), visited)
    }

    @Test
    fun `failed LAN falls back to Tailscale and retains exact URL`() = runBlocking {
        val lanUrl = "https://nas.lan:8822/webdav/"
        val tailscaleUrl = "http://100.90.135.102:8822/webdav/"
        val visited = mutableListOf<String>()

        val selected = selectFirstReachableConnectionUrl(listOf(tailscaleUrl, lanUrl)) { candidate ->
            visited += candidate
            candidate == tailscaleUrl
        }

        assertEquals(tailscaleUrl, selected)
        assertEquals(listOf(lanUrl, tailscaleUrl), visited)
    }

    @Test
    fun `unreachable routes return null after checking LAN then Tailscale`() = runBlocking {
        val lanUrl = "http://nas.lan:8822/webdav/"
        val tailscaleUrl = "http://100.90.135.102:8822/webdav/"
        val visited = mutableListOf<String>()

        val selected = selectFirstReachableConnectionUrl(listOf(tailscaleUrl, lanUrl)) { candidate ->
            visited += candidate
            false
        }

        assertNull(selected)
        assertEquals(listOf(lanUrl, tailscaleUrl), visited)
    }
}
