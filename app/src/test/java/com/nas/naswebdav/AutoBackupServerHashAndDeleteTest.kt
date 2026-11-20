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
@Config(sdk = [34], application = TestNasApplication::class)
class AutoBackupServerHashAndDeleteTest {
    private val context get() = ApplicationProvider.getApplicationContext<TestNasApplication>()
    private lateinit var server: MockWebServer
    private lateinit var provider: DeletableBackupMediaProvider
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val proofs = ConcurrentHashMap<String, String>()
    private val puts = AtomicInteger()
    private val contentReads = AtomicInteger()
    private val serverComputes = AtomicInteger()
    private var deleteAfterBackup = true
    private val bytes = "fast-nas-backup-content-2026".toByteArray()

    private fun sha(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun json(value: JSONObject) =
        MockResponse().setBody(value.toString()).addHeader("Content-Type", "application/json")

    @Before
    fun setup() {
        val tempFile = File(context.cacheDir, "server-hash-delete-media").apply { writeBytes(bytes) }
        provider = DeletableBackupMediaProvider(tempFile)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = URI(request.requestUrl!!.toString()).path
                if (path == "/api/thumb/activity") return MockResponse().setBody("{}")
                if (path == "/api/duplicates/capabilities") {
                    return json(
                        JSONObject()
                            .put("backup_protocol", 1)
                            .put("shared_hash_catalogue", true)
                            .put("server_side_hash", true)
                    )
                }
                if (path == "/api/duplicates/find") {
                    val body = JSONObject(request.body.readUtf8())
                    val digest = body.getString("sha256")
                    val size = body.getLong("size")
                    val matching = files.keys.filter { !it.substringAfterLast('/').startsWith(".") && files[it]!!.size.toLong() == size }
                    return json(
                        JSONObject()
                            .put("matches", JSONArray(matching.filter { proofs[it] == digest }))
                            .put("unverified", JSONArray(matching.filter { proofs[it] == null }))
                            .put("index_ready", true)
                            .put("next_cursor", "")
                    )
                }
                if (path.startsWith("/api/duplicates/")) {
                    val body = JSONObject(request.body.readUtf8())
                    val target = body.getString("path")
                    val data = files[target] ?: return MockResponse().setResponseCode(409)
                    val version = sha(data + "version".toByteArray())
                    val fileId = "1:${target.hashCode()}"
                    if (path.endsWith("/file")) {
                        return json(
                            JSONObject()
                                .put("size", data.size)
                                .put("version", version)
                                .put("file_id", fileId)
                                .put("sha256", proofs[target] ?: "")
                                .put("server_side_hash", true)
                        )
                    }
                    if (path.endsWith("/compute_hash")) {
                        serverComputes.incrementAndGet()
                        val computed = sha(data)
                        proofs[target] = computed
                        return json(
                            JSONObject()
                                .put("path", target)
                                .put("size", data.size)
                                .put("version", version)
                                .put("file_id", fileId)
                                .put("sha256", computed)
                        )
                    }
                    if (path.endsWith("/content")) {
                        contentReads.incrementAndGet()
                        return MockResponse().setBody(okio.Buffer().write(data))
                            .addHeader("X-NAS-Version", version)
                            .addHeader("X-NAS-File-ID", fileId)
                    }
                    if (path.endsWith("/hash")) {
                        proofs[target] = sha(data)
                        return json(JSONObject().put("saved", true))
                    }
                }
                val relative = path.removePrefix("/dav/")
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "HEAD" -> files[relative]?.let { MockResponse().addHeader("Content-Length", it.size) }
                        ?: MockResponse().setResponseCode(404)
                    "PUT" -> {
                        puts.incrementAndGet()
                        files[relative] = request.body.readByteArray()
                        MockResponse().setResponseCode(201)
                    }
                    "MOVE" -> {
                        val target = URI(request.getHeader("Destination")).path.removePrefix("/dav/")
                        if (files.containsKey(target)) MockResponse().setResponseCode(412)
                        else {
                            files[target] = files.remove(relative) ?: return MockResponse().setResponseCode(404)
                            MockResponse().setResponseCode(201)
                        }
                    }
                    "DELETE" -> {
                        files.remove(relative)
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/dav/").toString()
        mockkObject(SecurePrefsHelper, SmartNetworkManager)
        every { SecurePrefsHelper.getUser(any()) } returns "u"
        every { SecurePrefsHelper.getPass(any()) } returns "p"
        every { SecurePrefsHelper.getUrl(any()) } returns base
        every { SecurePrefsHelper.getUrlList(any()) } returns listOf(base)
        every { SecurePrefsHelper.getTailscaleBaseUrl(any()) } returns ""
        every { SecurePrefsHelper.getSettingsPrefs(any()) } answers {
            context.getSharedPreferences("backup-fast-test", 0).apply {
                edit().putBoolean("delete_after_backup", deleteAfterBackup).commit()
            }
        }
        coEvery { SmartNetworkManager.getActiveBaseUrl(any()) } returns base
        every { SmartNetworkManager.invalidateCache() } just Runs
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every { any<String>().toApiBaseUrl() } answers { firstArg<String>().substringBefore("/dav/") }
    }

    @After
    fun cleanup() {
        unmockkObject(SecurePrefsHelper, SmartNetworkManager)
        unmockkStatic("com.nas.naswebdav.NasApplicationKt")
        server.close()
    }

    private fun runBackup() = runBlocking {
        TestListenableWorkerBuilder<AutoBackupWorker>(context).build().doWork()
    }

    @Test(timeout = 30000)
    fun `server side hash verifies with zero reverse download and deletes uploaded plus existing sources`() {
        deleteAfterBackup = true
        assertEquals(ListenableWorker.Result.success(), runBackup())
        // 1 file uploaded, 2nd identical file skipped; both verified via NAS compute_hash with 0 reverse download!
        assertEquals(1, puts.get())
        assertEquals(0, contentReads.get())
        assertTrue("Expected NAS compute_hash to be used", serverComputes.get() >= 1)
        // Both uploaded source and skipped-already-on-NAS source must be deleted when deleteAfterBackup=true
        assertEquals(2, provider.deletes)
    }

    @Test(timeout = 30000)
    fun `copy mode keeps local source files intact`() {
        deleteAfterBackup = false
        files["Other/already-on-nas.jpg"] = bytes
        assertEquals(ListenableWorker.Result.success(), runBackup())
        assertEquals(0, puts.get())
        assertEquals(0, contentReads.get())
        assertEquals(0, provider.deletes)
    }
}

private class DeletableBackupMediaProvider(private val file: File) : ContentProvider() {
    var deletes = 0
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = projection ?: arrayOf("_id", "_display_name", "_data", "_size")
        val cursor = MatrixCursor(columns)
        if (uri.toString().contains("/video/")) return cursor
        for (id in 1..2) {
            val name = "photo$id.jpg"
            cursor.addRow(columns.map {
                when (it) {
                    "_id" -> id
                    "_display_name" -> name
                    "_data" -> file.absolutePath
                    "_size" -> file.length()
                    else -> null
                }
            })
        }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deletes++
        return 1
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
