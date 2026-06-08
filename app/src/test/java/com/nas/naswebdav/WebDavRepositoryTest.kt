package com.nas.naswebdav

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric: WebDavRepository orchestration trên Room in-memory, WebDavManager được mock.
 * Trọng tâm: searchGlobal escape wildcard LIKE (chống injection) + removeCachedPath xóa cascade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: WebDavRepository

    private fun cf(path: String, name: String, parent: String, size: Long = 4096) =
        CachedFile(path, name, false, null, parent, size, 0L)

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = WebDavRepository(mockk(relaxed = true), db)
        QueryCache.invalidate("duplicates")
    }

    @After fun tearDown() = db.close()

    @Test fun searchGlobal_escapesPercentWildcard() = runTest {
        db.fileDao().insertFiles(listOf(
            cf("/a/50%off.txt", "50%off.txt", "/a"),
            cf("/a/discount.txt", "discount.txt", "/a")
        ))
        // "%" phải được hiểu literal, không phải wildcard match-all
        val hits = repo.searchGlobal("%")
        assertEquals(1, hits.size)
        assertEquals("/a/50%off.txt", hits.first().path)
    }

    @Test fun searchGlobal_plainKeywordMatches() = runTest {
        db.fileDao().insertFiles(listOf(
            cf("/a/discount.txt", "discount.txt", "/a"),
            cf("/a/other.txt", "other.txt", "/a")
        ))
        assertEquals("/a/discount.txt", repo.searchGlobal("disc").single().path)
    }

    @Test fun searchGlobal_underscoreIsLiteral() = runTest {
        db.fileDao().insertFiles(listOf(
            cf("/a/a_b.txt", "a_b.txt", "/a"),
            cf("/a/axb.txt", "axb.txt", "/a")
        ))
        // "_" literal → chỉ khớp file có dấu gạch dưới, không khớp "axb"
        assertEquals("/a/a_b.txt", repo.searchGlobal("a_b").single().path)
    }

    @Test fun removeCachedPath_directoryCascadeDeletesSubtree() = runTest {
        db.fileDao().insertFiles(listOf(
            cf("http://x/a/", "a", "http://x"),
            cf("http://x/a/f1", "f1", "http://x/a"),
            cf("http://x/a/sub/f2", "f2", "http://x/a/sub"),
            cf("http://x/b/f3", "f3", "http://x/b")
        ))
        repo.removeCachedPath("http://x/a/")
        assertFalse(db.fileDao().exists("http://x/a/"))
        assertFalse(db.fileDao().exists("http://x/a/f1"))
        assertFalse(db.fileDao().exists("http://x/a/sub/f2"))
        // file ngoài subtree phải còn nguyên
        assertTrue(db.fileDao().exists("http://x/b/f3"))
    }

    @Test fun removeCachedPath_singleFileLeavesSiblings() = runTest {
        db.fileDao().insertFiles(listOf(
            cf("http://x/a/f1", "f1", "http://x/a"),
            cf("http://x/a/f2", "f2", "http://x/a")
        ))
        repo.removeCachedPath("http://x/a/f1")
        assertFalse(db.fileDao().exists("http://x/a/f1"))
        assertTrue(db.fileDao().exists("http://x/a/f2"))
    }

    @Test fun systemLog_roundTrip() = runTest {
        repo.addSystemLog("INFO", "Test", "hello")
        val logs = repo.getSystemLogs()
        assertEquals(1, logs.size)
        assertEquals("hello", logs.first().message)
        repo.clearSystemLogs()
        assertTrue(repo.getSystemLogs().isEmpty())
    }
}
