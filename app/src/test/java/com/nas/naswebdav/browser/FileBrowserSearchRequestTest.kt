package com.nas.naswebdav.browser

import androidx.paging.PagingData
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.NasFile
import com.nas.naswebdav.TestNasApplication
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.WebDavRepository
import com.nas.naswebdav.toApiBaseUrl
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserSearchRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: WebDavRepository
    private lateinit var viewModel: FileBrowserViewModel
    private val base = "http://nas.local/webdav/"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        val api = server.url("/").toString().trimEnd('/')
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every { any<String>().toApiBaseUrl() } returns api
        mockkObject(WebDavManager)
        every { WebDavManager.cancelActiveCalls(any()) } just Runs
        coEvery { WebDavManager.listFiles(any(), any()) } returns emptyList()
        WebDavManager.connect(base, "u", "p")
        repository = mockk(relaxed = true)
        coEvery { repository.searchCacheUnder(any(), any()) } returns emptyList()
        coEvery { repository.getCachedFiles(any()) } returns emptyList()
        coEvery { repository.getRemoteFilesAndCache(any()) } returns emptyList()
        every { repository.getFilesStream(any()) } returns flowOf(PagingData.empty())
        viewModel = FileBrowserViewModel(repository)
    }

    @After
    fun tearDown() {
        viewModel.clearSearch()
        server.shutdown()
        WebDavManager.disconnect()
        unmockkObject(WebDavManager)
        unmockkStatic("com.nas.naswebdav.NasApplicationKt")
        Dispatchers.resetMain()
    }

    @Test
    fun `completed index empty reply avoids recursive WebDAV searches`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"index_ready":true,"results":[]}"""))
        viewModel.searchGlobal("missing")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        awaitCondition { !viewModel.isSearchActive.value }
        assertTrue(viewModel.searchResults.value.isEmpty())
        coVerify(exactly = 0) { WebDavManager.listFiles(any(), any()) }
    }

    @Test
    fun `routine index refresh retries without recursive WebDAV discovery`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"index_ready":false,"index_incomplete_reason":"scan_in_progress","results":[]}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"index_ready":true,"results":[]}"""))
        viewModel.searchGlobal("missing")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        awaitCondition { !viewModel.isSearchActive.value }
        assertTrue(viewModel.searchResults.value.isEmpty())
        assertEquals(2, server.requestCount)
        coVerify(exactly = 0) { WebDavManager.listFiles(any(), any()) }
    }

    @Test
    fun `empty unfinished first build stays partial without another crawl`() = runBlocking {
        repeat(5) { server.enqueue(MockResponse().setBody("""{"success":true,"index_ready":false,"index_incomplete_reason":"scan_in_progress","results":[]}""")) }
        val found = NasFile("needle.txt", base + "needle.txt", false, "text/plain", 3)
        coEvery { WebDavManager.listFiles(any(), any()) } returns listOf(found)
        viewModel.searchGlobal("needle")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        awaitCondition { !viewModel.isSearchActive.value }
        assertTrue(viewModel.searchResults.value.isEmpty())
        assertTrue(viewModel.searchCatalogueIncomplete)
        coVerify(exactly = 0) { WebDavManager.listFiles(any(), any()) }
    }

    @Test
    fun `old server empty reply still uses the discovery fallback`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"results":[]}"""))
        val found = NasFile("needle.txt", base + "needle.txt", false, "text/plain", 3)
        coEvery { WebDavManager.listFiles(any(), any()) } returns listOf(found)
        viewModel.searchGlobal("needle")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        awaitCondition { !viewModel.isSearchActive.value }
        assertEquals(listOf(found), viewModel.searchResults.value)
        coVerify(exactly = 1) { WebDavManager.listFiles(base, match { it.startsWith("file-search:") }) }
    }

    @Test
    fun `partial index matches stay visible without discovery`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"success":true,"index_ready":false,"results":[{"name":"needle-indexed.txt","path":"${base}needle-indexed.txt","isDirectory":false}]}"""))
        val discovered = NasFile("needle-new.txt", base + "needle-new.txt", false, "text/plain", 3)
        coEvery { WebDavManager.listFiles(any(), any()) } returns listOf(discovered)
        viewModel.searchGlobal("needle")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        awaitCondition { !viewModel.isSearchActive.value }
        assertTrue(viewModel.searchCatalogueIncomplete)
        assertEquals(setOf("needle-indexed.txt"),
            viewModel.searchResults.value.map { it.name }.toSet())
        coVerify(exactly = 0) { WebDavManager.listFiles(any(), any()) }
    }

    @Test
    fun `indexed descendants are cached under their actual parent folders`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"success":true,"index_ready":true,"results":[{"name":"needle.txt","path":"${base}nested/needle.txt","isDirectory":false},{"name":"needle-folder","path":"${base}other/needle-folder/","isDirectory":true}]}"""))
        viewModel.searchGlobal("needle")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        awaitCondition { !viewModel.isSearchActive.value }
        coVerify { repository.saveDiscoveredFiles(match { it.single().name == "needle.txt" }, base + "nested/") }
        coVerify { repository.saveDiscoveredFiles(match { it.single().name == "needle-folder" }, base + "other/") }
        coVerify(exactly = 0) { repository.saveDiscoveredFiles(any(), base) }
    }

    @Test
    fun `folder scope decodes spaces and accents while preserving a literal plus`() = runBlocking {
        val folder = base + "T%C3%A0i%20li%E1%BB%87u+%25_/"
        server.enqueue(MockResponse().setBody("""{"ok":true,"items":[]}"""))
        viewModel.openSpecificUrl(folder, "Documents")
        val listing = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/files/list", listing?.requestUrl?.encodedPath)
        assertEquals("Tài liệu+%_", listing?.requestUrl?.queryParameter("path"))
        awaitCondition { viewModel.currentUrl == folder && !viewModel.isLoading }
        server.enqueue(MockResponse().setBody("""{"success":true,"index_ready":true,"results":[]}"""))
        viewModel.searchGlobal("anh")
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("Tài liệu+%_", request?.requestUrl?.queryParameter("root"))
        awaitCondition { !viewModel.isSearchActive.value }
    }

    @Test
    fun `root directory uses JSON and caches literal names for a second visit`() = runBlocking {
        val db = NasApplication.instance.database
        val realRepo = WebDavRepository(WebDavManager, db)
        val browser = FileBrowserViewModel(realRepo)
        val name = "a+ b#%25?.jpg"
        val url = base + "a%2B%20b%23%2525%3F.jpg"
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"items":[{"name":"$name","is_dir":false,"size":17,"mtime":100,"mime":"image/jpeg"}]}"""))
        browser.resetToDefaultMode()
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/files/list", request?.requestUrl?.encodedPath)
        assertEquals("", request?.requestUrl?.queryParameter("path"))
        awaitCondition { db.fileDao().getFiles(base, com.nas.naswebdav.currentAccountKey()).any { it.path == url } }
        assertEquals(url, browser.fileList.single().path)

        server.enqueue(MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("""{"ok":true,"items":[]}"""))
        browser.resetToDefaultMode()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(url, browser.fileList.single().path)
        awaitCondition { db.fileDao().getFiles(base, com.nas.naswebdav.currentAccountKey()).isEmpty() }
        assertTrue(browser.fileList.isEmpty())
        coVerify(exactly = 0) { WebDavManager.listFiles(any(), any()) }
    }

    @Test
    fun `cold name listing publishes a preview while the rest is still downloading`() = runBlocking {
        val folder = base + "large/"
        viewModel.setFileSort("name_asc")
        server.enqueue(streamedDirectoryResponse(DIRECTORY_PREVIEW_SIZE + 1))
        viewModel.navigateToUrl(folder)
        awaitCondition { viewModel.fileList.size == DIRECTORY_PREVIEW_SIZE && viewModel.isLoading }
        coVerify(exactly = 0) { repository.cacheDirectoryListing(any(), any(), any(), any()) }
        awaitCondition { !viewModel.isLoading && viewModel.fileList.size == DIRECTORY_PREVIEW_SIZE + 1 }
        coVerify(timeout = 1000) { repository.cacheDirectoryListing(folder, match { it.size == DIRECTORY_PREVIEW_SIZE + 1 }, any(), false) }
    }

    @Test
    fun `rejected streamed listing removes preview before WebDAV fallback`() = runBlocking {
        val folder = base + "incomplete/"
        val fallback = kotlinx.coroutines.CompletableDeferred<List<NasFile>>()
        coEvery { repository.getRemoteFilesAndCache(folder) } coAnswers { fallback.await() }
        viewModel.setFileSort("name_asc")
        server.enqueue(streamedDirectoryResponse(DIRECTORY_PREVIEW_SIZE + 2))
        viewModel.navigateToUrl(folder)
        awaitCondition { viewModel.fileList.size == DIRECTORY_PREVIEW_SIZE }
        awaitCondition { viewModel.isLoading && viewModel.fileList.isEmpty() }
        fallback.complete(emptyList())
        awaitCondition { !viewModel.isLoading }
        coVerify(exactly = 0) { repository.cacheDirectoryListing(any(), any(), any(), any()) }
    }

    private fun streamedDirectoryResponse(total: Int): MockResponse {
        fun row(n: Int) = """{"name":"$n.jpg","size":10,"is_dir":false,"mtime":12}"""
        val first = (1..DIRECTORY_PREVIEW_SIZE).joinToString(",") { row(it) }
        val prefix = """{"items":[$first,"""
        val tail = row(DIRECTORY_PREVIEW_SIZE + 1) + """],"ok":true,"total":$total}"""
        return MockResponse().setBody(prefix + " ".repeat(4000) + tail)
            .throttleBody((prefix.length + 2000).toLong(), 800, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `leaving a directory cancels its network request without starting PROPFIND`() = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("""{"ok":true,"items":[]}"""))
        viewModel.navigateToUrl(base + "first/")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("""{"ok":true,"items":[]}"""))
        viewModel.navigateToUrl(base + "next/")
        val next = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("next", next?.requestUrl?.queryParameter("path"))
        awaitCondition(1000) {
            !viewModel.isLoading && NasApplication.instance.fastApiClient.dispatcher.runningCalls()
                .none { it.request().url.queryParameter("path") == "first" }
        }
        coVerify(exactly = 0) { repository.getRemoteFilesAndCache(any()) }
    }

    @Test
    fun `clearing search cancels the pending response body and keeps results empty`() = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody(
            """{"success":true,"index_ready":true,"results":[{"name":"late.txt","path":"${base}late.txt","isDirectory":false}]}"""))
        viewModel.searchGlobal("late")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        viewModel.clearSearch()
        awaitCondition(700) {
            NasApplication.instance.fastApiClient.dispatcher.runningCalls()
                .none { it.request().url.encodedPath == "/api/search" }
        }
        assertFalse(viewModel.isSearchActive.value)
        assertTrue(viewModel.searchResults.value.isEmpty())
        coVerify(exactly = 0) { WebDavManager.listFiles(any(), any()) }
    }

    @Test
    fun `scoped Room cache is visible before a delayed API reply`() = runBlocking {
        val cached = NasFile("cached.txt", base + "cached.txt", false, "text/plain", 2)
        coEvery { repository.searchCacheUnder(any(), any()) } returns listOf(cached)
        server.enqueue(MockResponse().setBodyDelay(700, TimeUnit.MILLISECONDS).setBody(
            """{"success":true,"index_ready":true,"results":[]}"""))
        viewModel.searchGlobal("cached")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(listOf(cached), viewModel.searchResults.value)
        awaitCondition { !viewModel.isSearchActive.value }
        assertTrue(viewModel.searchResults.value.isEmpty())
    }

    private suspend fun awaitCondition(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs) { while (!condition()) delay(10) }
        }
    }
}
