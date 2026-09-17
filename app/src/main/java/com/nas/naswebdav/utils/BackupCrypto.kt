package com.nas.naswebdav.utils

import android.content.Context
import com.nas.naswebdav.NasApplication
import com.nas.naswebdav.SecurePrefsHelper
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Task 9: mã hóa file backup AES-256-GCM (opt-in, mặc định TẮT).
 * Khóa 256-bit sinh một lần, lưu EncryptedSharedPreferences (KeyStore).
 * Định dạng file: [12 byte IV][ciphertext][16 byte GCM tag].
 * Giải mã khi tải về qua cùng khóa máy — đổi máy/mất khóa thì không đọc được.
 */
object BackupCrypto {
    private const val KEY_ALIAS_PREF = "backup_crypto_key_b64"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    const val ENCRYPTED_SUFFIX = ".nasenc"

    fun isEnabled(context: Context): Boolean =
        SecurePrefsHelper.getSettingsPrefs(context).getBoolean("backup_encrypt_enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        SecurePrefsHelper.getSettingsPrefs(context).edit().putBoolean("backup_encrypt_enabled", enabled).apply()
    }

    private fun getOrCreateKey(context: Context): SecretKeySpec {
        val prefs = SecurePrefsHelper.getSettingsPrefs(context)
        // Khóa nằm trong EncryptedSharedPreferences (đã mã hóa bởi KeyStore).
        val existing = runCatching { prefs.getString(KEY_ALIAS_PREF, null) }.getOrNull()
        if (!existing.isNullOrEmpty()) {
            return SecretKeySpec(android.util.Base64.decode(existing, android.util.Base64.NO_WRAP), "AES")
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(
            KEY_ALIAS_PREF,
            android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        ).apply()
        return SecretKeySpec(raw, "AES")
    }

    /** Bọc OutputStream để ghi ciphertext (IV ghi trước). */
    fun encryptingStream(context: Context, out: OutputStream): OutputStream {
        val key = getOrCreateKey(context)
        val iv = ByteArray(GCM_IV_LEN).also { SecureRandom().nextBytes(it) }
        out.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return CipherOutputStream(out, cipher)
    }

    /** Bọc InputStream để đọc plaintext (đọc IV đầu file). */
    fun decryptingStream(context: Context, input: InputStream): InputStream {
        val key = getOrCreateKey(context)
        val iv = ByteArray(GCM_IV_LEN)
        var read = 0
        while (read < GCM_IV_LEN) {
            val n = input.read(iv, read, GCM_IV_LEN - read)
            if (n < 0) throw java.io.IOException("File mã hóa cụt IV")
            read += n
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return CipherInputStream(input, cipher)
    }

    fun encryptedName(original: String): String =
        if (original.endsWith(ENCRYPTED_SUFFIX)) original else original + ENCRYPTED_SUFFIX
}
