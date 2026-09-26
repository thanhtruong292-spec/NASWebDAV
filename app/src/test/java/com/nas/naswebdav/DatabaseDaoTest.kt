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
    fun `files cache is scoped by account key`() {
        val dao = database.fileDao()
        dao.insertFiles(listOf(
            CachedFile("/docs/a.txt", "a.txt", false, "text/plain", "/docs", 100, 1, accountKey = "u1@h:1/r"),
            CachedFile("/docs/b.txt", "b.txt", false, "text/plain", "/docs", 100, 2, accountKey = "u2@h:1/r"),
            CachedFile("/docs/c.txt", "c.txt", false, "text/plain", "/docs", 100, 3)
        ))
        // Phien u1: thay a.txt (key minh) + c.txt (row cu chua key).
        assertEquals(listOf("a.txt", "c.txt"),
            dao.getFiles("/docs", "u1@h:1/r").map { it.name }.sorted())
        // Phien u2: thay b.txt + c.txt, khong thay a.txt.
        assertEquals(listOf("b.txt", "c.txt"),
            dao.getFiles("/docs", "u2@h:1/r").map { it.name }.sorted())
        // searchFiles cung scope.
        assertEquals(listOf("a.txt"),
            dao.searchFiles("a.txt", "u1@h:1/r").map { it.name })
        assertTrue(dao.searchFiles("a.txt", "u2@h:1/r").isEmpty())
    }
}
