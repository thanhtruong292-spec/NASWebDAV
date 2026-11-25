package com.nas.naswebdav

import com.nas.naswebdav.smarttools.duplicateScanReadout
import org.json.JSONObject
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26], application=TestNasApplication::class)
class NasCatalogueTest {
    @Test fun `legacy subfolder result describes its scope and missing read evidence`() {
        val snapshot = JSONObject("""{"path":"fixture","result":{"total_files_scanned":2}}""")
        val label = duplicateScanReadout(snapshot)
        assertEquals("Phạm vi một thư mục con. Đã quét 2 tệp. NAS chưa cung cấp số lỗi đọc.", label)
    }

    @Test fun `full root report exposes actual read errors`() {
        val snapshot = JSONObject("""{"path":"","result":{"total_files_scanned":34,"read_errors":1}}""")
        assertEquals("Phạm vi toàn thư mục WebDAV. Đã quét 34 tệp. Có 1 lỗi đọc.", duplicateScanReadout(snapshot))
    }

    private val base="http://nas.local/webdav/"
    private val account="u@nas.local:80/webdav"
    private fun document(rows: String, total: Int, ready: Boolean=true, truncated: Boolean=false) =
        """{"catalogue_protocol":1,"source":"file_index","index_generation":7,"catalogue_updated_at":1234,"index_ready":$ready,"truncated":$truncated,"total":$total,"files":[$rows]}"""
    private fun row(name: String) = """{"name":"${name.substringAfterLast('/')}","path":"/webdav/$name","size":3,"mtime":123}"""

    @Test fun `raw names retain webdav root and encode once`() = runBlocking {
        val received=mutableListOf<CachedFile>()
        val snapshot=readNasCatalogue(StringReader(document(row("sub/Ảnh + #%.bin"),1)),base,account) { batch,_,_->received.addAll(batch) }
        assertEquals(NasCatalogueSnapshot(7,1,1234000),snapshot)
        assertEquals("/webdav/sub/Ảnh + #%.bin",URI(received.single().path).path)
        assertEquals("sub/Ảnh + #%.bin",relativeDuplicatePath(base,received.single().path))
        assertEquals(account,received.single().accountKey)
        assertEquals(base+"sub/",received.single().parentPath)
        for (path in listOf("/private/file.bin","/webdav/../private.bin","//other/webdav/a")) {
            try { catalogueFileUrl(base,path);fail("Unsafe path accepted") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `partial report preserves received groups and explains the count`() {
        val group = org.json.JSONObject().put("sha256", "a".repeat(64)).put("size", 2)
            .put("files", org.json.JSONArray(listOf("one.bin", "copy.bin")))
        val report = org.json.JSONObject().put("groups", org.json.JSONArray().put(group))
            .put("total_groups", 21407).put("read_errors", 0)
        assertEquals("Đã nhận 1/21407 nhóm trùng từ NAS.", duplicateReportNotice(report))
        assertEquals(2, parseRustDuplicateGroups(report, base).size)
        report.put("total_groups", 1)
        assertEquals("", duplicateReportNotice(report))
        report.remove("read_errors")
        assertEquals("", duplicateReportNotice(report))
    }

    @Test fun `Rust duplicate names survive URL conversion for cleanup`() {
        val names = listOf("webdav/a#b.bin", "a?b+%20.bin")
        val group = org.json.JSONObject()
            .put("sha256", "a".repeat(64))
            .put("size", 2048)
            .put("files", org.json.JSONArray(names))
        val report = org.json.JSONObject().put("groups", org.json.JSONArray().put(group))
        val files = parseRustDuplicateGroups(report, base)
        assertEquals(names, files.map { relativeDuplicatePath(base, it.path) })
    }

    @Test fun `Rust duplicate groups safely ignore malformed paths without aborting`() {
        val group = org.json.JSONObject()
            .put("sha256", "b".repeat(64))
            .put("size", 100)
            .put("files", org.json.JSONArray(listOf("valid1.txt", "valid2.txt", "../escaped.txt", "//double//slash.txt")))
        val report = org.json.JSONObject().put("groups", org.json.JSONArray().put(group))
        val files = parseRustDuplicateGroups(report, base)
        assertEquals(2, files.size)
        assertEquals(listOf("valid1.txt", "valid2.txt"), files.map { relativeDuplicatePath(base, it.path) })
    }

    @Test fun `batching includes non media without retaining the full catalogue`() = runBlocking {
        val counts=mutableListOf<Int>()
        val rows=(0..2000).joinToString(",") {row("$it.txt")}
        val result=readNasCatalogue(StringReader(document(rows,2001)),base,account) { batch,_,_-> counts.add(batch.size) }
        assertEquals(listOf(2000,1),counts)
        assertEquals(2001,result.total)
    }

    @Test fun `incomplete truncated legacy or mismatched inventory never completes`() = runBlocking {
        val bad=listOf(document("",0,ready=false),document("",0,truncated=true),
            document(row("a.bin"),2),document(row("a.bin"),1).dropLast(2),
            """{"files":[],"total":0}""")
        for (input in bad) {
            try { readNasCatalogue(StringReader(input),base,account) {_,_,_->};fail("Incomplete inventory accepted") }
            catch (_: IncompleteNasCatalogueException) {}
        }
    }

    @Test fun `completed mirror prunes stale metadata and commits receipt without deleting proof cache`() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val now=System.currentTimeMillis()
            fun cached(path:String,key:String=account,time:Long=now-120000)=CachedFile(path,"cached",false,"text/plain",path.substringBeforeLast('/')+"/",3,123,timestamp=time,accountKey=key)
            db.fileDao().insertFiles(listOf(cached(base+"old.txt"),cached("http://nas.local/wrong-root.txt"),
                cached(base+"other-account.txt","other"),cached(base+"recent.txt",time=now+1000)))
            db.verifiedHashDao().put(VerifiedHashCache(account,base+"proof.txt",3,"version","a".repeat(64),"1:1"))
            val snapshot=readNasCatalogue(StringReader(document(row("new.txt"),1)),base,account) { batch,_,_->db.withTransaction {db.fileDao().insertFiles(batch)} }
            finishNasCatalogueMirror(db,base,account,now,snapshot)
            assertNull(db.fileDao().getFileByPath(base+"old.txt",account))
            assertNull(db.fileDao().getFileByPath("http://nas.local/wrong-root.txt",account))
            assertNotNull(db.fileDao().getFileByPath(base+"other-account.txt","other"))
            assertNotNull(db.fileDao().getFileByPath(base+"recent.txt",account))
            assertNotNull(db.verifiedHashDao().get(account,base+"proof.txt"))
            assertEquals(7L,db.catalogueReceiptDao().get(account)!!.generation)
            assertEquals(1,db.catalogueReceiptDao().get(account)!!.fileCount)
            val oldReceipt=db.catalogueReceiptDao().get(account)
            try {
                val incomplete=readNasCatalogue(StringReader(document(row("bad.bin"),2)),base,account) { batch,_,_-> db.withTransaction {db.fileDao().insertFiles(batch)} }
                finishNasCatalogueMirror(db,base,account,now,incomplete)
                fail("Incomplete receipt committed")
            } catch (_: IncompleteNasCatalogueException) {}
            assertEquals(oldReceipt,db.catalogueReceiptDao().get(account))
        } finally {db.close()}
    }

    @Test fun `cancellation stays cancellation and does not become a successful partial scan`() = runBlocking {
        val input=document((0..1999).joinToString(",") {row("$it.bin")},2000)
        try {
            readNasCatalogue(StringReader(input),base,account) {_,_,_->throw CancellationException("stopped")}
            fail("Cancellation swallowed")
        } catch (_: CancellationException) {}
    }
}
