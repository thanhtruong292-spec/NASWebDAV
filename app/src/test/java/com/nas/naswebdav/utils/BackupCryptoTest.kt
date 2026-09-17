package com.nas.naswebdav.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Task 9: round-trip mã hóa backup AES-256-GCM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCryptoTest {
    private fun ctx(): Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun `encrypt then decrypt returns original`() {
        val c = ctx()
        val plain = "backup payload tiếng Việt 123".toByteArray(Charsets.UTF_8)
        val encOut = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(c, encOut).use { it.write(plain) }
        val enc = encOut.toByteArray()
        assertTrue(enc.size > plain.size)
        val dec = BackupCrypto.decryptingStream(c, ByteArrayInputStream(enc)).readBytes()
        assertArrayEquals(plain, dec)
    }

    @Test
    fun `encrypted name appends suffix once`() {
        assertEquals("a.jpg.nasenc", BackupCrypto.encryptedName("a.jpg"))
        assertEquals("a.jpg.nasenc", BackupCrypto.encryptedName("a.jpg.nasenc"))
    }

    @Test
    fun `toggle persists default off`() {
        val c = ctx()
        BackupCrypto.setEnabled(c, false)
        assertEquals(false, BackupCrypto.isEnabled(c))
        BackupCrypto.setEnabled(c, true)
        assertEquals(true, BackupCrypto.isEnabled(c))
        BackupCrypto.setEnabled(c, false)
    }
}
