package com.nas.naswebdav

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = TestNasApplication::class)
class FileMutationSyncReplayTest {
    @Test(timeout = 30000)
    fun rootRetryReplaysOnlyCurrentAccountAndIncludesLegacyRoot() {
        val context = ApplicationProvider.getApplicationContext<TestNasApplication>()
        val received = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "PUT") {
                        received.add("PUT " + request.path + " " + request.body.readUtf8())
                    }
                    if (request.path == "/api/files/sync_mutation") {
                        received.add(JSONObject(request.body.readUtf8()).getString("old_path"))
                    }
                    return MockResponse().setBody("""{"ok":true}""")
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            val dao = context.database.syncActionDao()
            mockkObject(SecurePrefsHelper, SmartNetworkManager)
            mockkStatic("com.nas.naswebdav.NasApplicationKt")
            try {
                every { SecurePrefsHelper.getUser(any()) } returns "fixture-user"
                every { SecurePrefsHelper.getPass(any()) } returns "fixture-pass"
                every { SecurePrefsHelper.getUrl(any()) } returns base
                coEvery { SmartNetworkManager.getActiveBaseUrl(any()) } returns base
                every { any<String>().toApiBaseUrl() } returns base

                FileMutationSync.enqueueRetry(context, base, "fixture-user", "upload", "$base/a+b.txt")
                val row = dao.getAllPendingActionsScoped(
                    server.hostName, "fixture-user", server.port, "/", 10
                ).single()
                assertEquals("/", row.nasRoot)
                dao.insert(row.copy(id = 0, sourcePath = "upload|$base/legacy.txt", nasRoot = ""))
                dao.insert(row.copy(id = 0, sourcePath = "upload|$base/foreign.txt", nasUser = "other"))
                dao.insert(row.copy(id = 0, sourcePath = "upload|$base/nested.txt", nasRoot = "/nested"))

                val upload = java.io.File.createTempFile("offline-upload", ".txt", context.cacheDir)
                upload.writeText("synthetic upload")
                dao.insert(row.copy(
                    id = 0,
                    actionType = "UPLOAD_FAILED",
                    sourcePath = upload.absolutePath,
                    destPath = "$base/recovered+name.txt"
                ))

                val worker = TestListenableWorkerBuilder<OfflineSyncWorker>(context).build()
                assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
                assertEquals(setOf("a+b.txt", "legacy.txt"), received.take(2).toSet())
                assertEquals(listOf("PUT /recovered%2Bname.txt synthetic upload", "recovered+name.txt"),
                    received.drop(2))
                assertFalse(upload.exists())
                assertEquals(2, dao.countAll())
            } finally {
                unmockkStatic("com.nas.naswebdav.NasApplicationKt")
                unmockkObject(SecurePrefsHelper, SmartNetworkManager)
            }
        }
    }
}
