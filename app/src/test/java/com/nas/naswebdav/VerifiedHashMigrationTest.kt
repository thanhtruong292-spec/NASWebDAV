package com.nas.naswebdav

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=TestNasApplication::class)
class VerifiedHashMigrationTest {
    @Test fun `upgrade preserves existing data and isolates equal paths by account`() {
        val context=RuntimeEnvironment.getApplication()
        val name="verified-hash-migration-test"
        fun helper(version: Int)=FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object: SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE existing_metadata(name TEXT)")
                        db.execSQL("INSERT INTO existing_metadata VALUES('preserved')")
                        db.execSQL("CREATE TABLE files_cache(accountKey TEXT,fullHash TEXT,contentLength INTEGER)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_19_20.migrate(db)
                    }
                }).build())
        helper(19).use { it.writableDatabase }
        helper(20).use { migrated ->
            val db=migrated.writableDatabase
            db.query("SELECT name FROM existing_metadata").use { cursor ->
                cursor.moveToFirst(); assertEquals("preserved",cursor.getString(0))
            }
            db.execSQL("INSERT INTO verified_hash_cache VALUES('one','/same',3,'v1','hash1','1:1')")
            db.execSQL("INSERT INTO verified_hash_cache VALUES('two','/same',3,'v1','hash2','1:1')")
            db.query("SELECT COUNT(*) FROM verified_hash_cache").use { cursor ->
                cursor.moveToFirst();assertEquals(2,cursor.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }
    @Test fun `physical aliases are not copies and trash is not a candidate`() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val db = NasApplication.instance.database
            val dao = db.fileDao()
            val hash = "a".repeat(64)
            val key = "physical-alias-test"
            fun row(name: String, size: Long = 3, parent: String = "http://nas/webdav/") =
                CachedFile(parent+name,name,false,null,parent,size,1,fullHash=hash,accountKey=key)
            val original = row("a")
            val alias = row("alias")
            val copy = row("copy")
            dao.insertFiles(listOf(original,alias))
            for (file in listOf(original,alias)) {
                db.verifiedHashDao().put(VerifiedHashCache(key,file.path,3,"version",hash,"1:1"))
            }
            org.junit.Assert.assertTrue(dao.getVerifiedDuplicateFiles(key).isEmpty())
            assertEquals(0,dao.countVerifiedDuplicateFiles(key))
            dao.insertFiles(listOf(copy,row("single",17),row("trash",17,"http://nas/webdav/.trash/")))
            db.verifiedHashDao().put(VerifiedHashCache(key,copy.path,3,"version",hash,"1:2"))
            assertEquals(setOf(original.path,copy.path),dao.getVerifiedDuplicateFiles(key).map { it.path }.toSet())
            assertEquals(2,dao.countVerifiedDuplicateFiles(key))
            org.junit.Assert.assertFalse(dao.getDuplicateSizes(key).contains(17L))
            assertEquals(3,dao.countDuplicateFiles(key))
            dao.deleteFileForAccount(copy.path,"different-account")
            org.junit.Assert.assertNotNull(dao.getFileByPath(copy.path,key))
        }
    }

}
