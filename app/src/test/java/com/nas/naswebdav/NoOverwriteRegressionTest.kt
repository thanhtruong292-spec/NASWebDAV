package com.nas.naswebdav

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Khoa hoi quy cho cac fix P1-4 / R4 / R5:
 * - renameFile / copyFile / moveFileNoOverwrite LUON gui Overwrite: F
 *   (khong ghi de ngam file dich cung ten).
 * - deleteFile gui DELETE truc tiep (khong MOVE) — hanh vi xoa vinh vien
 *   ma deletePermanently() can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
class NoOverwriteRegressionTest {
    @Test
    fun `renameFile sends Overwrite F`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            WebDavManager.renameFile(
                server.url("/dav/a.txt").toString(),
                server.url("/dav/b.txt").toString()
            )
            val req = server.takeRequest()
            assertEquals("MOVE", req.method)
            assertEquals("F", req.getHeader("Overwrite"))
        }
    }

    @Test
    fun `copyFile sends Overwrite F`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            WebDavManager.copyFile(
                server.url("/dav/a.txt").toString(),
                server.url("/dav/b.txt").toString()
            )
            val req = server.takeRequest()
            assertEquals("COPY", req.method)
            assertEquals("F", req.getHeader("Overwrite"))
        }
    }

    @Test
    fun `moveFileNoOverwrite sends Overwrite F`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            WebDavManager.moveFileNoOverwrite(
                server.url("/dav/a.txt").toString(),
                server.url("/dav/b.txt").toString()
            )
            val req = server.takeRequest()
            assertEquals("MOVE", req.method)
            assertEquals("F", req.getHeader("Overwrite"))
        }
    }

    @Test
    fun `deleteFile sends DELETE not MOVE`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            WebDavManager.connect(server.url("/dav/").toString(), "u", "p")
            WebDavManager.deleteFile(server.url("/dav/.trash/a.txt").toString(), false)
            val req = server.takeRequest()
            assertEquals("DELETE", req.method)
        }
    }
}
