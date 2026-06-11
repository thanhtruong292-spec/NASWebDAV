package com.nas.naswebdav

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + MockWebServer: kiểm tra full-path WebDavManager.listFiles —
 * gửi PROPFIND thật qua OkHttp client production rồi parse XML Multi-Status.
 * Trọng tâm: lọc entry gốc, phân biệt thư mục/tệp, decode tên, contentLength, lỗi HTTP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PropfindListFilesTest {

    private lateinit var server: MockWebServer

    @Before fun setup() {
        // Đảm bảo NasApplication (cung cấp sharedHttpClient) đã được tạo
        ApplicationProvider.getApplicationContext<android.content.Context>()
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private val multiStatusXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/folder/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/folder/sub/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
              <D:getlastmodified>Sun, 01 Jan 2023 12:00:00 GMT</D:getlastmodified>
            </D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/folder/report%20final.txt</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontenttype>text/plain</D:getcontenttype>
              <D:getcontentlength>2048</D:getcontentlength>
              <D:getlastmodified>Mon, 02 Jan 2023 08:30:00 GMT</D:getlastmodified>
            </D:prop></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test fun listFiles_parsesDirAndFile_filtersRoot() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multiStatusXml))
        val base = server.url("/dav/").toString()
        WebDavManager.connect(base, "user", "pass")

        val files = runBlocking { WebDavManager.listFiles(server.url("/dav/folder/").toString()) }

        // root /dav/folder/ bị lọc → còn 2 entry
        assertEquals(2, files.size)

        val dir = files.first { it.name == "sub" }
        assertTrue(dir.isDirectory)
        assertTrue(dir.path.endsWith("/dav/folder/sub/"))

        val file = files.first { it.name == "report final.txt" } // %20 đã decode
        assertFalse(file.isDirectory)
        assertEquals(2048L, file.contentLength)
        assertEquals("text/plain", file.contentType)
        assertTrue(file.lastModified > 0L)
    }

    @Test fun listFiles_sendsPropfindWithDepth1() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multiStatusXml))
        val base = server.url("/dav/").toString()
        WebDavManager.connect(base, "user", "pass")

        runBlocking { WebDavManager.listFiles(server.url("/dav/folder/").toString()) }

        val recorded = server.takeRequest()
        assertEquals("PROPFIND", recorded.method)
        assertEquals("1", recorded.getHeader("Depth"))
        // preemptive Basic auth được bơm sẵn
        assertTrue(recorded.getHeader("Authorization")?.startsWith("Basic ") == true)
    }

    @Test(expected = Exception::class)
    fun listFiles_throwsOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val base = server.url("/dav/").toString()
        WebDavManager.connect(base, "user", "pass")
        runBlocking { WebDavManager.listFiles(server.url("/dav/folder/").toString()) }
    }

    @Test fun listFiles_emptyMultistatusReturnsEmpty() {
        server.enqueue(MockResponse().setResponseCode(207)
            .setBody("""<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"></D:multistatus>"""))
        val base = server.url("/dav/").toString()
        WebDavManager.connect(base, "user", "pass")
        val files = runBlocking { WebDavManager.listFiles(server.url("/dav/folder/").toString()) }
        assertTrue(files.isEmpty())
    }
}
