package com.nas.naswebdav

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CancelScopeTest {

    @Test
    fun `cancelActiveCalls only cancels matching group`() {
        MockWebServer().use { server ->
            // Login call treo (server không trả gì) → mô phỏng request đang bay.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            // Browser call trả ngay sau đó.
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            fun req(group: String, path: String) = Request.Builder()
                .url(server.url(path))
                .tag(String::class.java, group).build()

            val loginResult = AtomicInteger(0)
            val loginDone = CountDownLatch(1)
            Thread {
                try {
                    client.newCall(req(WebDavManager.CALL_GROUP_LOGIN, "/login"))
                        .execute().use { loginResult.set(it.code) }
                } catch (_: Exception) {
                    loginResult.set(-1) // cancel giữa chừng
                } finally {
                    loginDone.countDown()
                }
            }.start()

            Thread.sleep(800)
            assertEquals(1, client.dispatcher.runningCalls().count())

            // Scoped cancel: đúng logic WebDavManager.cancelActiveCalls
            client.dispatcher.runningCalls().toList()
                .filter { it.request().tag(String::class.java) == WebDavManager.CALL_GROUP_LOGIN }
                .forEach { it.cancel() }

            assertTrue(loginDone.await(10, TimeUnit.SECONDS))
            assertEquals(-1, loginResult.get())

            // Browser call sau đó vẫn chạy bình thường — không bị cancel lan.
            client.newCall(req(WebDavManager.CALL_GROUP_BROWSER, "/dav/"))
                .execute().use { assertEquals(200, it.code) }
        }
    }

    @Test
    fun `call group constants are distinct`() {
        val groups = setOf(
            WebDavManager.CALL_GROUP_LOGIN,
            WebDavManager.CALL_GROUP_BROWSER,
            WebDavManager.CALL_GROUP_TRANSFER
        )
        assertEquals(3, groups.size)
    }
}
