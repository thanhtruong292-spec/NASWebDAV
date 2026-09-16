package com.nas.naswebdav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_13_14, MIGRATION_14_15)
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `file dao orders directories first and supports duplicate grouping`() {
        database.fileDao().insertFiles(listOf(
            CachedFile("/docs/file.txt", "file.txt", false, "text/plain", "/docs", 8192, 1),
            CachedFile("/docs/folder", "folder", true, null, "/docs", 0, 2),
            CachedFile("/docs/copy.txt", "copy.txt", false, "text/plain", "/docs", 8192, 3),
            CachedFile("/docs/tiny.txt", "tiny.txt", false, "text/plain", "/docs", 2, 4)
        ))

        assertEquals(listOf("folder", "copy.txt", "file.txt", "tiny.txt"),
            database.fileDao().getFiles("/docs").map { it.name })
        assertEquals(2, database.fileDao().countDuplicateFiles())
        assertTrue(database.fileDao().getDuplicateSizes().contains(8192L))
    }

    @Test
    fun `scoped search respects root prefix and 200 limit`() {
        val dao = database.fileDao()
        val rows = mutableListOf<CachedFile>()
        repeat(250) { i ->
            rows.add(CachedFile("/photos/img_$i.jpg", "img_$i.jpg", false, "image/jpeg", "/photos", 5000, i.toLong()))
        }
        repeat(10) { i ->
            rows.add(CachedFile("/docs/img_$i.jpg", "img_$i.jpg", false, "image/jpeg", "/docs", 5000, i.toLong()))
        }
        dao.insertFiles(rows)

        val scoped = dao.searchFilesUnder("/photos/", "img_")
        assertEquals(200, scoped.size)
        assertTrue(scoped.all { it.path.startsWith("/photos/") })

        val docs = dao.searchFilesUnder("/docs/", "img_")
        assertEquals(10, docs.size)
    }

    @Test
    fun `sync queue and trash metadata round trip`() {
        database.syncActionDao().insert(SyncAction(actionType = "UPLOAD", sourcePath = "content://photo"))
        assertEquals(1, database.syncActionDao().countAll())

        val meta = TrashMeta("/.trash/photo", "/photos/photo.jpg")
        database.trashMetaDao().insert(meta)
        assertEquals(meta, database.trashMetaDao().findByOriginalPath("/photos/photo.jpg"))
    }

    @Test
    fun `parked rows leave batch and unpark restores them`() {
        // R2-P2: row hỏng park sau N lỗi — batch sau bỏ qua, đuôi hợp lệ vét được.
        val dao = database.syncActionDao()
        dao.insert(SyncAction(actionType = "UPLOAD", sourcePath = "bad://1"))
        dao.insert(SyncAction(actionType = "UPLOAD", sourcePath = "good://2"))
        val bad = dao.getAllPendingActions().first { it.sourcePath == "bad://1" }
        repeat(5) { dao.bumpFail(bad.id, System.currentTimeMillis()) }
        assertEquals(5, dao.getFailCount(bad.id))
        dao.park(bad.id)
        // Batch chỉ còn good; count loại parked.
        assertEquals(listOf("good://2"), dao.getAllPendingActions().map { it.sourcePath })
        assertEquals(1, dao.countAll())
        assertEquals(1, dao.getParked().size)
        // Retry tay mở park.
        dao.unpark(bad.id, System.currentTimeMillis())
        assertEquals(2, dao.countAll())
        assertEquals(0, dao.getFailCount(bad.id))
    }

    @Test
    fun `verified duplicate count matches UI grouping definition`() {
        // R2 (S7): countVerifiedDuplicates cùng định nghĩa với UI group —
        // hash thật, loại LGH_ legacy và null.
        val dao = database.fileDao()
        dao.insertFiles(listOf(
            CachedFile("/a/1.jpg", "1.jpg", false, "image/jpeg", "/a/", 100L, 1L, partialHash = "h1"),
            CachedFile("/a/2.jpg", "2.jpg", false, "image/jpeg", "/a/", 100L, 2L, partialHash = "h1"),
            CachedFile("/a/3.jpg", "3.jpg", false, "image/jpeg", "/a/", 100L, 3L, partialHash = "LGH_100_3"),
            CachedFile("/a/4.jpg", "4.jpg", false, "image/jpeg", "/a/", 100L, 4L, partialHash = null),
        ))
        // Chỉ 2 file hash thật h1 tạo 1 nhóm → count = 2.
        assertEquals(2, dao.countVerifiedDuplicates())
    }
}
