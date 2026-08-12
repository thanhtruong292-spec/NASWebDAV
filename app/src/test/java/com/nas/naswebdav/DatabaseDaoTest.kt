package com.nas.naswebdav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
    fun `sync queue and trash metadata round trip`() {
        database.syncActionDao().insert(SyncAction(actionType = "UPLOAD", sourcePath = "content://photo"))
        assertEquals(1, database.syncActionDao().countAll())

        val meta = TrashMeta("/.trash/photo", "/photos/photo.jpg")
        database.trashMetaDao().insert(meta)
        assertEquals(meta, database.trashMetaDao().findByOriginalPath("/photos/photo.jpg"))
    }
}
