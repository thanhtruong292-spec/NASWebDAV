package com.nas.naswebdav

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private var catalogue=""
    private val mutations=AtomicInteger()
    private val catalogueReads=AtomicInteger()
    private val checkpoints=CopyOnWriteArrayList<String>()
    private var claimStatus=200
    private var checkpointStatus=200
    private val account get()="catalogue-user@127.0.0.1:${server.port}/webdav"
    private lateinit var originalTimeZone: TimeZone
    private lateinit var workerContext: Context

    @Before fun setup() {
        originalTimeZone=TimeZone.getDefault()
        val utcHour=Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
        val offset=(3-utcHour+36)%24-12
        TimeZone.setDefault(TimeZone.getTimeZone(java.time.ZoneOffset.ofHours(offset)))
        val strings=spyk(context.resources)
        every {strings.getString(any())} returns "catalogue fixture"
        every {strings.getString(any(), *anyVararg())} returns "catalogue fixture"
        workerContext=object: ContextWrapper(context) {
            override fun getResources(): Resources = strings
            override fun getApplicationContext(): Context = this
        }
        server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when(request.requestUrl!!.encodedPath) {
                "/api/system/idle" -> MockResponse().setBody("{\"idle\":true}")
                "/api/disk/fast_index" -> {catalogueReads.incrementAndGet();MockResponse().setBody(catalogue)}
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
    private fun document(total:Int)= """{"catalogue_protocol":1,"source":"file_index","index_ready":true,"index_generation":7,"catalogue_revision":11,"catalogue_updated_at":1234,"truncated":false,"total":$total,"files":[{"path":"/webdav/ordinary + %.txt","name":"ordinary + %.txt","size":123,"mtime":1}]}"""

    @Test(timeout=30000) fun completeCatalogueMirrorsOrdinaryFilesWithoutFilesystemWalk() {
        catalogue=document(1)
        assertEquals(ListenableWorker.Result.success(),scan())
        assertEquals(0,mutations.get())
        assertEquals("complete",checkpoints.last())
        val db=context.database
        runBlocking {withContext(Dispatchers.IO) {
            val receipt=db.catalogueReceiptDao().get(account)
            assertNotNull(receipt)
            assertEquals(1,receipt!!.fileCount)
        }}
    }

    @Test(timeout=30000) fun partialCatalogueRetriesBeforeHashOrCleanup() {
        catalogue=document(2)
        assertEquals(ListenableWorker.Result.retry(),scan())
        assertEquals(0,mutations.get())
        assertEquals("retry",checkpoints.last())
        runBlocking {withContext(Dispatchers.IO) {assertNull(context.database.catalogueReceiptDao().get(account))}}
    }

    @Test(timeout=30000) fun busyNasRetriesBeforeReadingCatalogue() {
        claimStatus=423
        catalogue=document(1)
        assertEquals(ListenableWorker.Result.retry(),scan())
        assertEquals(0,catalogueReads.get())
        assertEquals(0,mutations.get())
        assertTrue(checkpoints.isEmpty())
    }

    @Test(timeout=30000) fun lostLeaseRetriesWithoutCommittingReceiptOrCleanup() {
        checkpointStatus=409
        catalogue=document(1)
        assertEquals(ListenableWorker.Result.retry(),scan())
        assertEquals(0,mutations.get())
        assertFalse(checkpoints.contains("complete"))
        runBlocking {withContext(Dispatchers.IO) {assertNull(context.database.catalogueReceiptDao().get(account))}}
    }
}
