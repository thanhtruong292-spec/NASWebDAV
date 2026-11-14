package com.nas.naswebdav

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=TestNasApplication::class)
class VerifiedDuplicateRequestsTest {
    private val sha=MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).joinToString("") {"%02x".format(it)}
    private val version="a".repeat(64)
    @Before fun configureApi() {
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every { any<String>().toApiBaseUrl() } answers { firstArg<String>().substringBefore("/dav/") }
    }
    @After fun clearApi() { unmockkStatic("com.nas.naswebdav.NasApplicationKt") }
    private fun info(id: String="1:1", stamp: String=version, hash: String="") =
        MockResponse().setBody(JSONObject().put("file_id",id).put("version",stamp).put("size",3).put("sha256",hash).toString())

    @Test fun completeHashPersistsAndRepeatDoesNotDownload() = runBlocking {
        withContext(Dispatchers.IO) {
            MockWebServer().use { server ->
                server.start()
                val base=server.url("/dav/").toString();val path=base+"a"
                WebDavManager.connect(base,"u","p")
                server.enqueue(info());server.enqueue(MockResponse().setBody("abc").addHeader("X-NAS-Version",version).addHeader("X-NAS-File-ID","1:1"))
                server.enqueue(info());server.enqueue(MockResponse().setBody("""{"saved":true}"""))
                server.enqueue(info(hash=sha))
                assertEquals(sha to version,WebDavManager.getCachedVerifiedSha256(path,3))
                assertEquals(sha to version,WebDavManager.getCachedVerifiedSha256(path,3))
                assertEquals(listOf("POST","POST","POST","POST","POST"),(1..5).map {server.takeRequest().method})
                assertEquals(sha,NasApplication.instance.database.verifiedHashDao().get(currentAccountKey(),path)?.sha256)
            }
        }
    }

    @Test fun changedVersionDuringDownloadIsNotCached() = runBlocking {
        withContext(Dispatchers.IO) {
            MockWebServer().use { server ->
                server.start();val base=server.url("/dav/").toString()
                WebDavManager.connect(base,"u","p")
                server.enqueue(info());server.enqueue(MockResponse().setBody("abc").addHeader("X-NAS-Version",version).addHeader("X-NAS-File-ID","1:1"));server.enqueue(info(stamp="b".repeat(64)))
                assertNull(WebDavManager.getCachedVerifiedSha256(base+"a",3))
                assertNull(NasApplication.instance.database.verifiedHashDao().get(currentAccountKey(),base+"a"))
                assertEquals(3,server.requestCount)
            }
        }
    }

    @Test fun rejectedCachedProofInvalidatesPublishedHash() = runBlocking {
        withContext(Dispatchers.IO) {
            MockWebServer().use { server ->
                server.start()
                val base=server.url("/dav/").toString()
                val path=base+"cached"
                WebDavManager.connect(base,"u","p")
                val account=currentAccountKey()
                val db=NasApplication.instance.database
                db.fileDao().insertFiles(listOf(CachedFile(path,"cached",false,null,base,3,1,fullHash=sha,accountKey=account)))
                db.verifiedHashDao().put(VerifiedHashCache(account,path,3,version,sha,"1:1"))
                server.enqueue(info())
                server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
                assertNull(WebDavManager.getCachedVerifiedSha256(path,3))
                assertNull(db.fileDao().getFileByPath(path,account)?.fullHash)
                assertEquals(2,server.requestCount)
            }
        }
    }

    @Test fun foreignOriginIsRejectedBeforeSendingCredentials() = runBlocking {
        withContext(Dispatchers.IO) {
            WebDavManager.connect("http://nas.local/dav/","u","p")
            assertNull(WebDavManager.getCachedVerifiedSha256("http://other.local/dav/a",3))
            assertNull(relativeDuplicatePath("http://nas.local/dav/","http://nas.local/dav/%2e%2e/a"))
        }
    }

    private suspend fun cleanupCase(status: Int) {
        MockWebServer().use { server ->
            server.start();val base=server.url("/dav/").toString()
            WebDavManager.connect(base,"u","p")
            val keeper=CachedFile(base+"keep","keep",false,null,base,3,1,fullHash=sha)
            val victim=keeper.copy(path=base+"copy",name="copy")
            server.enqueue(info(hash=sha))
            server.enqueue(info(id="1:2",stamp="b".repeat(64),hash=sha))
            server.enqueue(MockResponse().setResponseCode(status)
                .addHeader("Location",base+"keep")
                .setBody("""{"deleted":true,"path":"copy","keeper":"keep"}"""))
            if(status==302) server.enqueue(MockResponse().setBody("{}"))
            assertEquals(status==200,WebDavManager.deleteVerifiedDuplicate(victim,keeper,sha))
            val requests=(1..server.requestCount).map {server.takeRequest()}
            assertEquals(3,requests.size)
            assertTrue(requests.all {it.method=="POST"})
            assertEquals("/api/duplicates/cleanup",requests.last().path)
            val command=JSONObject(requests.last().body.readUtf8())
            assertEquals("keep",command.getString("keeper"))
            assertEquals("copy",command.getString("victim"))
            val dao=NasApplication.instance.database.verifiedHashDao()
            assertNotNull(dao.get(currentAccountKey(),keeper.path))
            if(status==200) assertNull(dao.get(currentAccountKey(),victim.path))
            else assertNotNull(dao.get(currentAccountKey(),victim.path))
        }
    }
    @Test fun cleanupUsesCoordinatorInsteadOfRawDelete() = runBlocking {withContext(Dispatchers.IO) {cleanupCase(200)}}
    @Test fun serverConflictPreservesCachedRecord() = runBlocking {withContext(Dispatchers.IO) {cleanupCase(409)}}
    @Test fun cleanupRefusesRedirect() = runBlocking {withContext(Dispatchers.IO) {cleanupCase(302)}}
}

