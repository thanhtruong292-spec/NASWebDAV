package com.nas.naswebdav

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateFromVersion1To15_preservesCachedFilesAndValidatesSchema() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO files_cache(
                    path, name, isDirectory, contentType, parentPath, contentLength, timestamp
                ) VALUES('/video.mp4', 'video.mp4', 0, 'video/mp4', '/', 12345, 1700000000000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            15,
            true,
            MIGRATION_1_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15
        )

        db.query("SELECT path, name, contentLength, lastModified FROM files_cache").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("/video.mp4", cursor.getString(0))
            assertEquals("video.mp4", cursor.getString(1))
            assertEquals(12345L, cursor.getLong(2))
            assertEquals(0L, cursor.getLong(3))
        }
    }

    @Test
    fun migrateFromVersion13To15_validatesSchema() {
        helper.createDatabase(TEST_DB + "_13_15", 13).close()

        helper.runMigrationsAndValidate(
            TEST_DB + "_13_15",
            15,
            true,
            MIGRATION_13_14,
            MIGRATION_14_15
        )
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
