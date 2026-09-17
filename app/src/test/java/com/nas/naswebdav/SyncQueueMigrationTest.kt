package com.nas.naswebdav

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3: migration test v15 -> v16 -> v17 cho sync_queue.
 * Chạy SQL migration thật trên SQLite JVM (không cần emulator):
 * tạo bảng v15 tối thiểu, migrate, verify cột + dữ liệu cũ còn nguyên.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class SyncQueueMigrationTest {

    private fun v15Db(): SupportSQLiteDatabase {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE `sync_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`actionType` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, " +
                                "`destPath` TEXT, `status` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)"
                        )
                    }
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                    }
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun columns(db: SupportSQLiteDatabase): Set<String> {
        val out = mutableSetOf<String>()
        db.query("PRAGMA table_info(`sync_queue`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                out.add(c.getString(nameIdx))
            }
        }
        return out
    }

    @Test
    fun `migrate 15 to 17 keeps rows and adds columns`() {
        val db = v15Db()
        db.execSQL(
            "INSERT INTO `sync_queue` (actionType, sourcePath, destPath, status, timestamp) " +
                "VALUES ('UPLOAD', 'content://a', '/dav/a', 'PENDING', 123)"
        )
        MIGRATION_15_16.migrate(db)
        assertTrue(columns(db).contains("failCount"))
        MIGRATION_16_17.migrate(db)
        val cols = columns(db)
        assertTrue(cols.contains("nasHost"))
        assertTrue(cols.contains("nasUser"))
        db.query("SELECT actionType, sourcePath, failCount, nasHost FROM `sync_queue`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("UPLOAD", c.getString(0))
            assertEquals("content://a", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals("", c.getString(3))
        }
        db.close()
    }
}
