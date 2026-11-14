package com.nas.naswebdav

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SystemMonitorRequestThrottleTest {
    private lateinit var server: MockWebServer
    private lateinit var viewModel: com.nas.naswebdav.monitor.SystemMonitorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        val apiBase = server.url("/").toString().trimEnd('/')
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every { any<String>().toApiBaseUrl() } returns apiBase
        WebDavManager.connect("http://nas.local/webdav/", "u", "p")
        viewModel = com.nas.naswebdav.monitor.SystemMonitorViewModel(mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        server.shutdown()
        WebDavManager.disconnect()
        unmockkStatic("com.nas.naswebdav.NasApplicationKt")
        Dispatchers.resetMain()
    }

    @Test
    fun `daily report throttles displayed date but reloads A after B`() = runTest {
        server.enqueue(reportResponse("A"))
        viewModel.fetchDailyReport("A")
        assertEquals("/api/report/daily?date=A", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        awaitReport("A")

        viewModel.fetchDailyReport("A")
        assertEquals(1, server.requestCount)

        server.enqueue(reportResponse("B"))
        viewModel.fetchDailyReport("B")
        assertEquals("/api/report/daily?date=B", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        awaitReport("B")

        server.enqueue(reportResponse("A"))
        viewModel.fetchDailyReport("A")
        assertEquals("/api/report/daily?date=A", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        awaitReport("A")
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `latest selected report wins when earlier response completes late`() = runTest {
        server.enqueue(reportResponse("A").setBodyDelay(500, TimeUnit.MILLISECONDS))
        viewModel.fetchDailyReport("A")
        assertEquals("/api/report/daily?date=A", server.takeRequest(2, TimeUnit.SECONDS)?.path)

        server.enqueue(reportResponse("B"))
        viewModel.fetchDailyReport("B")
        assertEquals("/api/report/daily?date=B", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        awaitReport("B")

        awaitCondition { server.requestCount >= 2 && !viewModel.isDailyReportLoading }
        assertEquals("B", viewModel.dailyReport?.date)
    }

    @Test
    fun `reselecting in flight A prevents later B from replacing it`() = runTest {
        server.enqueue(reportResponse("A").setBodyDelay(500, TimeUnit.MILLISECONDS))
        viewModel.fetchDailyReport("A")
        assertEquals("/api/report/daily?date=A", server.takeRequest(2, TimeUnit.SECONDS)?.path)

        server.enqueue(reportResponse("B"))
        viewModel.fetchDailyReport("B")
        assertEquals("/api/report/daily?date=B", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        viewModel.fetchDailyReport("A")

        awaitReport("A")
        awaitCondition { !viewModel.isDailyReportLoading }
        assertEquals("A", viewModel.dailyReport?.date)
    }

    @Test
    fun `NAS insights honors interval and zero forces a request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        viewModel.fetchNasInsights(minIntervalMs = 30_000L)
        assertEquals("/api/system/insights", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        awaitCondition { !viewModel.isFetchingNasInsights }

        viewModel.fetchNasInsights(minIntervalMs = 30_000L)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        viewModel.fetchNasInsights(minIntervalMs = 0L)
        assertEquals("/api/system/insights", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        awaitCondition { !viewModel.isFetchingNasInsights }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cached partial storage keeps original scan time and throttles refetch`() = runTest {
        val scannedAtSeconds = 1_700_000_000L
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"total_files":42,"total_bytes":1234,"partial":true,"scanned_at":$scannedAtSeconds,"cached":true,"rate_limited":true}"""
            )
        )
        viewModel.fetchStorageSummary(force = true)
        assertEquals("/api/storage/summary?force=1", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        awaitCondition { !viewModel.isFetchingStorageSummary }

        assertEquals(scannedAtSeconds * 1_000L, viewModel.storageSummary.updatedAt)
        assertTrue(viewModel.storageSummary.partial)
        viewModel.fetchStorageSummary(minIntervalMs = 30_000L)
        assertEquals(1, server.requestCount)
    }

    private suspend fun awaitReport(date: String) {
        awaitCondition { viewModel.dailyReport?.date == date }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(3_000) {
                while (!condition()) delay(10)
            }
        }
    }

    private fun reportResponse(date: String) = MockResponse()
        .setResponseCode(200)
        .setBody("""{"date":"$date"}""")
}
