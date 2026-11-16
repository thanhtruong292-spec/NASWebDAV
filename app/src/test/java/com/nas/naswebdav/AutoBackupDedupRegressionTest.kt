package com.nas.naswebdav

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26], application=TestNasApplication::class)
class AutoBackupDedupRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<TestNasApplication>()
    private lateinit var server: MockWebServer
    private lateinit var provider: SyntheticBackupMedia
    private val files=ConcurrentHashMap<String,ByteArray>()
    private val proofs=ConcurrentHashMap<String,String>()
    private val puts=AtomicInteger()
    private val contentReads=AtomicInteger()
    private var nativeAvailable=true
    private var nativeMetadataAvailable=true
    private var conflictFirstMove=false
    private var indexRefreshResponses=0
    private val bytes="same-media-content".toByteArray()
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun json(value: JSONObject)=MockResponse().setBody(value.toString()).addHeader("Content-Type","application/json")

    @Before fun setup() {
        provider=SyntheticBackupMedia(File(context.cacheDir,"synthetic-media").apply {writeBytes(bytes)})
        provider.attachInfo(context,ProviderInfo().apply {authority="media"})
        ShadowContentResolver.registerProviderInternal("media",provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path=URI(request.requestUrl!!.toString()).path
                if(path=="/api/thumb/activity") return MockResponse().setBody("{}")
                if(path=="/api/duplicates/capabilities") return if(nativeAvailable)
                    json(JSONObject().put("backup_protocol",1).put("shared_hash_catalogue",true)) else MockResponse().setResponseCode(404)
                if(path=="/api/duplicates/find") {
                    if(indexRefreshResponses-- > 0) return json(JSONObject().put("matches",JSONArray())
                        .put("unverified",JSONArray()).put("index_ready",false).put("next_cursor",""))
                    val body=JSONObject(request.body.readUtf8())
                    val digest=body.getString("sha256");val size=body.getLong("size")
                    val matching=files.keys.filter { !it.substringAfterLast('/').startsWith(".") && files[it]!!.size.toLong()==size }
                    return json(JSONObject().put("matches",JSONArray(matching.filter {proofs[it]==digest}))
                        .put("unverified",JSONArray(matching.filter {proofs[it]==null})).put("index_ready",true).put("next_cursor",""))
                }
                if(path.startsWith("/api/duplicates/")) {
                    val body=JSONObject(request.body.readUtf8());val target=body.getString("path")
                    val data=files[target] ?: return MockResponse().setResponseCode(409)
                    val version=sha(data+"version".toByteArray())
                    if(path.endsWith("/file") && !nativeMetadataAvailable) return MockResponse().setResponseCode(503)
                    if(path.endsWith("/file")) return json(JSONObject().put("size",data.size).put("version",version)
                        .put("file_id","1:${target.hashCode()}").put("sha256",proofs[target] ?: ""))
                    if(path.endsWith("/content")) {
                        contentReads.incrementAndGet()
                        return MockResponse().setBody(okio.Buffer().write(data)).addHeader("X-NAS-Version",version)
                            .addHeader("X-NAS-File-ID","1:${target.hashCode()}")
                    }
                    if(path.endsWith("/hash")) {
                        if(body.getString("sha256")!=sha(data)) return MockResponse().setResponseCode(409)
                        proofs[target]=sha(data)
                        return json(JSONObject().put("saved",true))
                    }
                }
                val relative=path.removePrefix("/dav/")
                return when(request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "HEAD" -> files[relative]?.let { MockResponse().addHeader("Content-Length",it.size) }
                        ?: MockResponse().setResponseCode(404)
                    "PUT" -> { puts.incrementAndGet();files[relative]=request.body.readByteArray();MockResponse().setResponseCode(201) }
                    "MOVE" -> {
                        val target=URI(request.getHeader("Destination")).path.removePrefix("/dav/")
                        if(conflictFirstMove) {
                            conflictFirstMove=false
                            files[target]="old".toByteArray()
                        }
                        if(files.containsKey(target)) MockResponse().setResponseCode(412)
                        else { files[target]=files.remove(relative) ?: return MockResponse().setResponseCode(404);MockResponse().setResponseCode(201) }
                    }
                    "DELETE" -> { files.remove(relative);MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base=server.url("/dav/").toString()
        mockkObject(SecurePrefsHelper,SmartNetworkManager)
        every {SecurePrefsHelper.getUser(any())} returns "u"
        every {SecurePrefsHelper.getPass(any())} returns "p"
        every {SecurePrefsHelper.getUrl(any())} returns base
        every {SecurePrefsHelper.getUrlList(any())} returns listOf(base)
        every {SecurePrefsHelper.getTailscaleBaseUrl(any())} returns ""
        every {SecurePrefsHelper.getSettingsPrefs(any())} returns context.getSharedPreferences("backup-test",0).apply {
            edit().putBoolean("delete_after_backup",true).commit()
        }
        coEvery {SmartNetworkManager.getActiveBaseUrl(any())} returns base
        every {SmartNetworkManager.invalidateCache()} just Runs
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every {any<String>().toApiBaseUrl()} answers {firstArg<String>().substringBefore("/dav/")}
    }

    @After fun cleanup() {
        unmockkObject(SecurePrefsHelper,SmartNetworkManager)
        unmockkStatic("com.nas.naswebdav.NasApplicationKt")
        server.close()
    }

    private fun backup()=runBlocking {TestListenableWorkerBuilder<AutoBackupWorker>(context).build().doWork()}

    @Test(timeout=30000) fun repeatedBackupAndDifferentMediaIdsUploadOnlyOneExactCopyWithoutEtags() {
        assertEquals(ListenableWorker.Result.success(),backup())
        assertEquals(1,puts.get())
        assertEquals(1,files.size)
        assertEquals(2,AutoBackupState.resultSkipped.value)
        assertEquals(0,AutoBackupState.resultFailed.value)
        assertEquals(ListenableWorker.Result.success(),backup())
        assertEquals(1,puts.get())
        assertEquals(3,AutoBackupState.resultSkipped.value)
        assertEquals(0,provider.deletes)
        assertTrue(files.keys.single().contains("Phone + Pictures"))
    }

    @Test(timeout=30000) fun existingUnhashedNasCopyWithAnotherNameIsVerifiedBeforeAnyPut() {
        files["Other/another-name.mp4"]=bytes
        assertEquals(ListenableWorker.Result.success(),backup())
        assertEquals(0,puts.get())
        assertEquals(1,contentReads.get())
        assertEquals(3,AutoBackupState.resultSkipped.value)
        assertEquals(1,files.size)
        assertEquals(0,provider.deletes)
    }

    @Test(timeout=30000) fun unsupportedBackendStopsBeforeUploadingUnverifiableCopies() {
        nativeAvailable=false
        assertEquals(ListenableWorker.Result.failure(),backup())
        assertEquals(0,puts.get())
        assertEquals(0,provider.deletes)
    }

    @Test(timeout=30000) fun differentSizeWithoutNativeProofDoesNotCreateVersion() {
        provider.singleImage=true
        files["AutoBackup/Phone + Pictures/image1__id1.jpg"]="old".toByteArray()
        nativeMetadataAvailable=false
        backup()
        assertEquals(0,puts.get())
        assertEquals(0,provider.deletes)
    }

    @Test(timeout=30000) fun moveConflictReusesCompletedTemporaryWithoutSecondUpload() {
        provider.singleImage=true
        conflictFirstMove=true
        assertEquals(ListenableWorker.Result.success(),backup())
        assertEquals(1,puts.get())
        assertEquals(2,files.size)
        assertTrue(files.values.any {it.contentEquals(bytes)})
        assertTrue(files.values.any {it.contentEquals("old".toByteArray())})
        assertFalse(files.keys.any {it.substringAfterLast('/').startsWith(".")})
    }

    @Test(timeout=30000) fun routineIndexRefreshWaitsWithoutFailingOrCreatingDuplicates() {
        indexRefreshResponses=1
        assertEquals(ListenableWorker.Result.success(),backup())
        assertEquals(1,puts.get())
        assertEquals(0,AutoBackupState.resultFailed.value)
        assertEquals(0,provider.deletes)
    }
}

private class SyntheticBackupMedia(private val file: File): ContentProvider() {
    var deletes=0
    var singleImage=false
    override fun onCreate()=true
    override fun getType(uri: Uri)=if(uri.toString().contains("/video/")) "video/mp4" else "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns=projection ?: arrayOf("_id","_display_name","_data","_size")
        val cursor=MatrixCursor(columns)
        val video=uri.toString().contains("/video/")
        for(id in 1..(if(singleImage) {if(video) 0 else 1} else if(video) 1 else 2)) {
            val name=if(video) "copy.mp4" else "image$id.jpg"
            cursor.addRow(columns.map {when(it) {
                "_id" -> id
                "_display_name" -> name
                "_data" -> android.os.Environment.getExternalStorageDirectory().absolutePath+"/Phone + Pictures/"+name
                "_size" -> file.length()
                else -> null
            }})
        }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String)=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {deletes++;return 1}
    override fun insert(uri: Uri, values: ContentValues?): Uri?=null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?)=0
}
