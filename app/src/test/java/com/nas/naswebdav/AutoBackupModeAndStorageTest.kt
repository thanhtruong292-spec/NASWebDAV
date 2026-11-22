package com.nas.naswebdav

import androidx.test.core.app.ApplicationProvider
import com.nas.naswebdav.utils.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoBackupModeAndStorageTest {

    @Test
    fun backupMode_persistsAndRejectsInvalidValues() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = PreferencesRepository(ctx)

        assertEquals("both", repo.getBackupMode())
        repo.setBackupMode("image")
        assertEquals("image", repo.getBackupMode())
        repo.setBackupMode("video")
        assertEquals("video", repo.getBackupMode())
        repo.setBackupMode("invalid_mode")
        assertEquals("both", repo.getBackupMode())
    }

    @Test
    fun deviceId_isStableAndNonBlank() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = PreferencesRepository(ctx)

        val id1 = repo.getDeviceId(ctx)
        val id2 = repo.getDeviceId(ctx)
        assertTrue(id1.isNotBlank())
        assertEquals(id1, id2)
    }

    @Test
    fun totalFreedBytes_accumulatesPositiveOnly() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = PreferencesRepository(ctx)

        val before = repo.getTotalBackupFreedBytes()
        repo.addBackupFreedBytes(-500L)
        assertEquals(before, repo.getTotalBackupFreedBytes())
        repo.addBackupFreedBytes(1024L * 1024L)
        repo.addBackupFreedBytes(512L * 1024L)
        assertEquals(before + 1536L * 1024L, repo.getTotalBackupFreedBytes())
    }
}
