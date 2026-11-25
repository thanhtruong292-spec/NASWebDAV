package com.nas.naswebdav.ui.html5

import android.net.Uri
import android.webkit.WebResourceRequest
import com.nas.naswebdav.TestNasApplication
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.toApiBaseUrl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
class DashboardMediaLoaderTest {
    private lateinit var server: MockWebServer
    private lateinit var origin: String
    private val auth = WebDavManager.AuthState("http://nas.local/webdav/", "user", "pass")

    @Before fun start() {
        server = MockWebServer().apply { start() }
        origin = server.url("/").toString().trimEnd('/')
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every { auth.baseUrl.toApiBaseUrl() } returns origin
        WebDavManager.threadLocalAuth.set(auth)
    }

    @After fun stop() {
        WebDavManager.threadLocalAuth.remove()
        unmockkStatic("com.nas.naswebdav.NasApplicationKt")
        server.shutdown()
    }

    private fun req(url: String, verb: String = "GET", headers: Map<String, String> = emptyMap()) =
        mockk<WebResourceRequest> {
            every { getUrl() } returns Uri.parse(url)
            every { method } returns verb
            every { requestHeaders } returns headers
        }

    private fun jpeg(marker: Int) = byteArrayOf(-1, -40, marker.toByte(), -1, -39)
    private fun reply(marker: Int) = MockResponse().setHeader("Content-Type", "image/jpeg")
        .setBody(Buffer().write(jpeg(marker)))

    @Test fun `same version hits cache while source changes and new session fetch again`() {
        for (marker in 1..3) server.enqueue(reply(marker))
        val path = "$origin/api/thumb?path=one.jpg&auth=outdated&v="
        fun get(version: String) = DashboardMediaLoader.intercept(req(path + version,
            headers = mapOf("Authorization" to "untrusted")))!!.data.use { it.readBytes() }
        assertArrayEquals(jpeg(1), get("1:40"))
        assertArrayEquals(jpeg(1), get("1:40"))
        assertEquals(1, server.requestCount)
        val sent = server.takeRequest()
        assertEquals(auth.authHeader, sent.getHeader("Authorization"))
        assertNull(sent.requestUrl!!.queryParameter("auth"))
        assertArrayEquals(jpeg(2), get("2:40"))
        WebDavManager.threadLocalAuth.set(auth.copy())
        assertArrayEquals(jpeg(3), get("2:40"))
        assertEquals(3, server.requestCount)
    }

    @Test fun `media range and HEAD reach exact origin and redirects are closed`() {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/8").setBody("ab"))
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        val url = "$origin/api/media?path=one.mp4"
        val ranged = DashboardMediaLoader.intercept(req(url, headers = mapOf("Range" to "bytes=0-1")))!!
        assertEquals(206, ranged.statusCode)
        assertEquals("ab", ranged.data.bufferedReader().use { it.readText() })
        assertEquals("bytes=0-1", server.takeRequest().getHeader("Range"))
        assertNull(DashboardMediaLoader.intercept(req(url, "HEAD"))!!.data)
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals(502, DashboardMediaLoader.intercept(req(url))!!.statusCode)
        assertNull(DashboardMediaLoader.intercept(req("http://localhost:1/api/media?path=one.mp4")))
        assertNull(DashboardMediaLoader.intercept(req("$origin/api/media/status")))
        assertEquals(3, server.requestCount)
    }
}
