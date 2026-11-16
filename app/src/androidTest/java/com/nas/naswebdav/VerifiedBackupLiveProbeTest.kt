package com.nas.naswebdav

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Explicit live probe: only creates and removes its own UUID-named fixture. */
@RunWith(AndroidJUnit4::class)
class VerifiedBackupLiveProbeTest {
    @Test(timeout = 120000)
    fun stagedBackupReusesVerifiedContentAcrossNamesAndRoomCacheMisses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = SmartNetworkManager.getVerifiedBaseUrl(context)
        assertTrue("Authenticated NAS route unavailable", base.isNotBlank())
        val auth = WebDavManager.AuthState(baseUrl = base,
            user = SecurePrefsHelper.getUser(context), pass = SecurePrefsHelper.getPass(context))
        WebDavManager.connect(base, auth.user, auth.pass)
        withContext(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(auth)) {
            WebDavManager.requireBackupProtocol()
            val id = UUID.randomUUID().toString()
            val folder = base.trimEnd('/') + "/NAS-Backup-Probe-" + id + "/"
            val stable = folder + "original.bin"
            val temp = folder + ".nas-backup-" + UUID.randomUUID() + ".tmp"
            val source = File.createTempFile("nas-backup-live-", ".bin", context.cacheDir)
            val uri = Uri.fromFile(source)
            val account = currentAccountKey()
            var folderCreated = false
            try {
                val bytes = ("Owned backup content " + id).toByteArray()
                source.writeBytes(bytes)
                val proof = backupSourceDigest(context, uri, bytes.size.toLong())
                WebDavManager.createFolder(folder)
                folderCreated = true
                WebDavManager.uploadStreamIfAbsent(temp, ByteArrayInputStream(bytes), bytes.size.toLong(),
                    "application/octet-stream", { _, _ -> })
                WebDavManager.moveFileNoOverwrite(temp, stable)
                assertEquals(BackupContentState.MATCH,
                    WebDavManager.inspectBackupDestination(stable, bytes.size.toLong(), proof.sha256))
                val plan = WebDavManager.planBackupTarget(folder + "different-name.bin", bytes.size.toLong(), proof.sha256, account)
                assertTrue("Identical bytes must be skipped under another name", plan.skip)
                assertEquals(stable, plan.path)
                NasApplication.instance.database.verifiedHashDao().remove(account, stable)
                assertEquals("NAS catalogue must survive a phone cache miss", stable,
                    WebDavManager.findSharedBackup(bytes.size.toLong(), proof.sha256))
                assertTrue(source.exists())
            } finally {
                try {
                    if (folderCreated) {
                        try { WebDavManager.cleanupBackupTemporary(temp, auth) }
                        finally { WebDavManager.deleteFile(folder, true) }
                    }
                } finally {
                    source.delete()
                    NasApplication.instance.database.verifiedHashDao().remove(account, stable)
                    NasApplication.instance.database.verifiedHashDao().remove("@local-backup-source-v1", uri.toString())
                }
            }
        }
    }
}
