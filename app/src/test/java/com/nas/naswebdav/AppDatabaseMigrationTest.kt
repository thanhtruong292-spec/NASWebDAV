package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AppDatabaseMigrationTest — Unit tests verifying Room database migrations (v1 -> v15).
 */
class AppDatabaseMigrationTest {

    @Test
    fun `Verify migration definitions exist and match version requirements`() {
        assertNotNull(MIGRATION_1_10)
        assertNotNull(MIGRATION_2_10)
        assertNotNull(MIGRATION_3_10)
        assertNotNull(MIGRATION_4_10)
        assertNotNull(MIGRATION_5_10)
        assertNotNull(MIGRATION_6_10)
        assertNotNull(MIGRATION_7_10)
        assertNotNull(MIGRATION_8_10)
        assertNotNull(MIGRATION_9_10)
        assertNotNull(MIGRATION_10_11)
        assertNotNull(MIGRATION_11_12)
        assertNotNull(MIGRATION_12_13)
        assertNotNull(MIGRATION_13_14)
        assertNotNull(MIGRATION_14_15)

        assertEquals(1, MIGRATION_1_10.startVersion)
        assertEquals(10, MIGRATION_1_10.endVersion)

        assertEquals(10, MIGRATION_10_11.startVersion)
        assertEquals(11, MIGRATION_10_11.endVersion)

        assertEquals(11, MIGRATION_11_12.startVersion)
        assertEquals(12, MIGRATION_11_12.endVersion)

        assertEquals(12, MIGRATION_12_13.startVersion)
        assertEquals(13, MIGRATION_12_13.endVersion)

        assertEquals(13, MIGRATION_13_14.startVersion)
        assertEquals(14, MIGRATION_13_14.endVersion)

        assertEquals(14, MIGRATION_14_15.startVersion)
        assertEquals(15, MIGRATION_14_15.endVersion)
    }

    @Test
    fun `Verify CachedFile entity index configuration`() {
        val cachedFileClass = CachedFile::class.java
        assertNotNull(cachedFileClass)
        val entityAnnotation = cachedFileClass.getAnnotation(androidx.room.Entity::class.java)
        assertNotNull(entityAnnotation)
        assertEquals("files_cache", entityAnnotation.tableName)
        assertEquals(4, entityAnnotation.indices.size)
    }

    @Test
    fun `Verify TrashMeta entity index configuration`() {
        val trashMetaClass = TrashMeta::class.java
        assertNotNull(trashMetaClass)
        val entityAnnotation = trashMetaClass.getAnnotation(androidx.room.Entity::class.java)
        assertNotNull(entityAnnotation)
        assertEquals("trash_meta", entityAnnotation.tableName)
        assertEquals(1, entityAnnotation.indices.size)
        assertEquals("originalPath", entityAnnotation.indices[0].value.first())
    }
}
