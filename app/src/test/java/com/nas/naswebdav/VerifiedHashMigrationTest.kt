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

    @Test fun `digest lookup indexes migrate without losing existing proofs`() {
        val context=RuntimeEnvironment.getApplication()
        val name="backup-hash-index-upgrade"
        fun helper(version:Int)=FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object:SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db:SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE verified_hash_cache(accountKey TEXT,path TEXT,contentLength INTEGER,version TEXT,sha256 TEXT,fileId TEXT)")
                        db.execSQL("CREATE TABLE file_fingerprints(accountKey TEXT,contentHash TEXT,fileSize INTEGER)")
                        db.execSQL("INSERT INTO verified_hash_cache VALUES('account','/keep',3,'version','digest','1:1')")
                    }
                    override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) { MIGRATION_20_21.migrate(db) }
                }).build())
        helper(20).use {it.writableDatabase}
        helper(21).use { upgraded ->
            val db=upgraded.writableDatabase
            db.query("SELECT path,sha256 FROM verified_hash_cache").use { cursor ->
                assertEquals(1,cursor.count);cursor.moveToFirst();assertEquals("/keep",cursor.getString(0));assertEquals("digest",cursor.getString(1))
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN ('index_verified_hash_cache_accountKey_sha256_contentLength','index_file_fingerprints_accountKey_contentHash_fileSize')").use { cursor ->
                cursor.moveToFirst();assertEquals(2,cursor.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun `catalogue receipt migration preserves existing metadata and hash proofs`() {
        val context=RuntimeEnvironment.getApplication()
        val name="catalogue-receipt-migration-test"
        fun helper(version: Int)=FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object: SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE existing_metadata(name TEXT)")
                        db.execSQL("INSERT INTO existing_metadata VALUES('preserved')")
                        db.execSQL("CREATE TABLE verified_hash_cache(accountKey TEXT,path TEXT,sha256 TEXT)")
                        db.execSQL("INSERT INTO verified_hash_cache VALUES('owner','/keep','verified-digest')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_21_22.migrate(db)
                    }
                }).build())
        try {
            helper(21).use {it.writableDatabase}
            helper(22).use {
                val db=it.writableDatabase
                db.query("SELECT sha256 FROM verified_hash_cache WHERE path='/keep'").use { row ->
                    row.moveToFirst();assertEquals("verified-digest",row.getString(0))
                }
                db.query("SELECT name FROM existing_metadata").use { row ->
                    row.moveToFirst();assertEquals("preserved",row.getString(0))
                }
                db.query("SELECT COUNT(*) FROM catalogue_receipts").use { row ->
                    row.moveToFirst();assertEquals(0,row.getInt(0))
                }
            }
        } finally {context.deleteDatabase(name)}
    }
}
