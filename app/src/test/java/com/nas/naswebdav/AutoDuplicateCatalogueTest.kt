package com.nas.naswebdav

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26], application=TestNasApplication::class)
class AutoDuplicateCatalogueTest {
    private val context get()=ApplicationProvider.getApplicationContext<TestNasApplication>()
    private lateinit var server: MockWebServer
    private var scanStartStatus=202
    private var scanStatusBody="""{"status":"done","engine":"rust_rayon","result":{"groups":[],"total_groups":0,"total_files_scanned":10,"read_errors":0,"total_wasted_bytes":0,"inventory":{"protocol":1,"source":"file_index","complete":true,"total":10,"generation":3,"catalogue_revision":11}}}"""
    private val mutations=AtomicInteger()
    private val rustScanCalls=AtomicInteger()
    private val checkpoints=CopyOnWriteArrayList<String>()
    private var claimStatus=200
    private var checkpointStatus=200
    private lateinit var originalTimeZone: TimeZone
    private lateinit var workerContext: Context

    @Before fun setup() {
        originalTimeZone=TimeZone.getDefault()
        val utcHour=Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
        val offset=(3-utcHour+36)%24-12
        TimeZone.setDefault(TimeZone.getTimeZone(java.time.ZoneOffset.ofHours(offset)))
        val strings=spyk(context.resources)
        every {strings.getString(any())} returns "rust fixture"
        every {strings.getString(any(), *anyVararg())} returns "rust fixture"
        workerContext=object: ContextWrapper(context) {
            override fun getResources(): Resources = strings
            override fun getApplicationContext(): Context = this
        }
        server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when(request.requestUrl!!.encodedPath) {
                "/api/system/idle" -> MockResponse().setBody("{\"idle\":true}")
                "/api/disk/scan_duplicates" -> {
                    rustScanCalls.incrementAndGet()
                    val lease = JSONObject(request.body.readUtf8()).getJSONObject("work_lease")
                    check(lease.getString("name") == "duplicates")
                    check(lease.getString("owner").startsWith("client:android-"))
                    check(lease.getString("token") == "owned-fixture")
                    MockResponse().setResponseCode(scanStartStatus).setBody(
                        if (scanStartStatus in 200..299) scanStatusBody else """{"error":"Rust chưa sẵn sàng"}"""
                    )
                }
                "/api/disk/scan_duplicates/status" -> MockResponse().setBody(scanStatusBody)
                "/api/work/request" -> MockResponse().setBody("{}")
                "/api/work/claim" -> {
                    val owner=JSONObject(request.body.readUtf8()).getString("owner")
                    MockResponse().setResponseCode(claimStatus).setBody(JSONObject().put("owner","client:"+owner)
                        .put("token","owned-fixture").put("revision",2).put("catalogue_revision",11).toString())
                }
                "/api/work/checkpoint" -> {
                    checkpoints.add(JSONObject(request.body.readUtf8()).getString("state"))
                    MockResponse().setResponseCode(checkpointStatus).setBody("{\"accepted\":true}")
                }
                "/api/thumb/activity" -> MockResponse().setBody("{}")
                else -> {mutations.incrementAndGet();MockResponse().setResponseCode(500)}
            }
        }
        server.start()
        val base=server.url("/webdav/").toString()
        mockkObject(SecurePrefsHelper,SmartNetworkManager)
        every {SecurePrefsHelper.getUser(any())} returns "catalogue-user"
        every {SecurePrefsHelper.getPass(any())} returns "fixture"
        every {SecurePrefsHelper.getUrl(any())} returns base
        coEvery {SmartNetworkManager.getActiveBaseUrl(any())} returns base
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every {any<String>().toApiBaseUrl()} answers {firstArg<String>().substringBefore("/webdav/")}
    }

    @After fun cleanup() {
        TimeZone.setDefault(originalTimeZone)
        unmockkStatic("com.nas.naswebdav.NasApplicationKt")
        unmockkObject(SecurePrefsHelper,SmartNetworkManager)
        server.close()
    }

    private fun scan()=runBlocking {TestListenableWorkerBuilder<AutoDuplicateScanWorker>(workerContext).build().doWork()}

    @Test(timeout=30000) fun rustScanSucceedsWithoutLegacyFastIndexFallback() {
        assertEquals(ListenableWorker.Result.success(),scan())
        assertEquals(1,rustScanCalls.get())
        assertEquals(0,mutations.get())
        assertEquals("complete",checkpoints.last())
    }

    @Test(timeout=30000) fun rustUnavailableRetriesWithoutFallback() {
        scanStartStatus=503
        assertEquals(ListenableWorker.Result.retry(),scan())
        assertEquals(1,rustScanCalls.get())
        assertEquals(0,mutations.get())
        assertEquals("retry",checkpoints.last())
    }

    @Test(timeout=30000) fun changedCatalogueRevisionRetriesBeforeDeleting() {
        scanStatusBody = scanStatusBody.replace("\"catalogue_revision\":11", "\"catalogue_revision\":12")
        assertEquals(ListenableWorker.Result.retry(), scan())
        assertEquals(1, rustScanCalls.get())
        assertEquals(0, mutations.get())
        assertEquals("retry", checkpoints.last())
    }

    @Test(timeout=30000) fun busyNasRetriesBeforeCallingRustScan() {
        claimStatus=423
        assertEquals(ListenableWorker.Result.retry(),scan())
        assertEquals(0,rustScanCalls.get())
        assertEquals(0,mutations.get())
        assertTrue(checkpoints.isEmpty())
    }
}
