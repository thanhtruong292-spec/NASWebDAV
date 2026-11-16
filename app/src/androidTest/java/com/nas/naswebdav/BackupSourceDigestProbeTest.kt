package com.nas.naswebdav

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BackupSourceDigestProbeTest {
    @Test fun nativeSourceHashCacheDetectsSameSizeEditAndKeepsSource() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File.createTempFile("nas-source-proof-",".bin",context.cacheDir)
        val uri=Uri.fromFile(source)
        try {
            source.writeBytes("abc".toByteArray())
            val first=backupSourceDigest(context,uri,3)
            assertNotNull(first.version)
            assertEquals(first,backupSourceDigest(context,uri,3))
            val originalMtime=source.lastModified()
            source.writeBytes("xyz".toByteArray())
            source.setLastModified(originalMtime)
            assertFalse(backupSourceStillCurrent(context,uri,first))
            val changed=backupSourceDigest(context,uri,3)
            val expected=MessageDigest.getInstance("SHA-256").digest("xyz".toByteArray()).joinToString("") {"%02x".format(it)}
            assertEquals(expected,changed.sha256)
            assertNotEquals(first.version,changed.version)
            assertTrue(source.exists())
        } finally {
            source.delete()
            NasApplication.instance.database.verifiedHashDao().remove("@local-backup-source-v1",uri.toString())
        }
    }
}
