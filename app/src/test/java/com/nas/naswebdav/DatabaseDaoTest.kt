package com.nas.naswebdav

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Room in-memory: kiểm tra logic SQL thật của các DAO,
 * trọng tâm là duplicate detection (CTE, ngưỡng 4096B), global search (lọc trash),
 * và ordering. Không cần thiết bị.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var fileDao: FileDao

    private fun file(
        path: String,
        name: String,
        parent: String,
        size: Long,
        isDir: Boolean = false,
        modified: Long = 0L
    ) = CachedFile(
        path = path, name = name, isDirectory = isDir, contentType = null,
        parentPath = parent, contentLength = size, lastModified = modified
    )

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileDao = db.fileDao()
    }

    @After fun tearDown() = db.close()

    @Test fun getFiles_ordersDirsFirstThenNameCaseInsensitive() {
        fileDao.insertFiles(listOf(
            file("/p/a.txt", "a.txt", "/p", 10),
            file("/p/C.txt", "C.txt", "/p", 10),
            file("/p/sub", "sub", "/p", 0, isDir = true)
        ))
        val names = fileDao.getFiles("/p").map { it.name }
        assertEquals(listOf("sub", "a.txt", "C.txt"), names)
    }

    @Test fun duplicateDetection_ignoresSmallAndUniqueSizes() {
        fileDao.insertFiles(listOf(
            // cặp trùng size hợp lệ (>=4096)
            file("/a/x1", "x1", "/a", 5000),
            file("/a/x2", "x2", "/a", 5000),
            // size duy nhất → không tính
            file("/a/y", "y", "/a", 8000),
            // cặp trùng nhưng <4096 → bị loại
            file("/a/s1", "s1", "/a", 100),
            file("/a/s2", "s2", "/a", 100)
        ))
        assertEquals(2, fileDao.countDuplicateFiles())
        assertEquals(listOf(5000L), fileDao.getDuplicateSizes())
        assertEquals(2, fileDao.getDuplicateFiles().size)
        assertEquals(2, fileDao.getStage1Duplicates().size)
    }

    @Test fun searchFiles_excludesTrashAndMatchesKeyword() {
        fileDao.insertFiles(listOf(
            file("/docs/report.pdf", "report.pdf", "/docs", 4096),
            file("/x/.trash/report.pdf", "report.pdf", "/x/.trash", 4096),
            file("/docs/notes.txt", "notes.txt", "/docs", 4096)
        ))
        val hits = fileDao.searchFiles("report")
        assertEquals(1, hits.size)
        assertEquals("/docs/report.pdf", hits.first().path)
    }

    @Test fun exists_and_getFileByPath() {
        fileDao.insertFiles(listOf(file("/a/f", "f", "/a", 10)))
        assertTrue(fileDao.exists("/a/f"))
        assertEquals("f", fileDao.getFileByPath("/a/f")?.name)
        assertNull(fileDao.getFileByPath("/nope"))
    }

    @Test fun deleteByParentPath_removesOnlyThatFolder() {
        fileDao.insertFiles(listOf(
            file("/a/f1", "f1", "/a", 10),
            file("/b/f2", "f2", "/b", 10)
        ))
        val removed = fileDao.deleteByParentPath("/a")
        assertEquals(1, removed)
        assertTrue(fileDao.exists("/b/f2"))
        assertTrue(!fileDao.exists("/a/f1"))
    }

    @Test fun updateImageFingerprint_persists() {
        fileDao.insertFiles(listOf(file("/a/img.jpg", "img.jpg", "/a", 5000)))
        fileDao.updateImageFingerprint("/a/img.jpg", "00ff00ff00ff00ff")
        assertEquals("00ff00ff00ff00ff", fileDao.getFileByPath("/a/img.jpg")?.imageFingerprint)
    }

    @Test fun logDao_recentLogsOrderedDesc() {
        val logDao = db.logDao()
        logDao.insertLog(SystemLog(timestamp = 100, type = "INFO", module = "M", message = "old"))
        logDao.insertLog(SystemLog(timestamp = 200, type = "ERROR", module = "M", message = "new"))
        val logs = logDao.getRecentLogs()
        assertEquals("new", logs.first().message)
        assertEquals(2, logDao.getLogsByModule("M", 10).size)
    }

    @Test fun checkpointDao_saveGetClear() {
        val dao = db.checkpointDao()
        dao.saveCheckpoint(ScanCheckpoint("DupScan", "/folder", 50, 3, 1000))
        assertEquals(50, dao.getCheckpoint("DupScan")?.scannedCount)
        dao.clearCheckpoint("DupScan")
        assertNull(dao.getCheckpoint("DupScan"))
    }

    @Test fun fingerprintDao_exactHashLookupAndCount() {
        val dao = db.fingerprintDao()
        dao.insertAll(listOf(
            FileFingerprint("/a/1.jpg", "aaaa", "1.jpg", 10),
            FileFingerprint("/a/2.jpg", "bbbb", "2.jpg", 20)
        ))
        assertEquals(2, dao.countFingerprints())
        assertEquals("/a/1.jpg", dao.findByExactHash("aaaa")?.filePath)
        assertNull(dao.findByExactHash("cccc"))
    }
}
