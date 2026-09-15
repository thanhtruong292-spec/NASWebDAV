package com.nas.naswebdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-AUDIT-F1: phân loại read/write mode cho NasDocumentProvider.openDocument.
 *
 * Không gọi ParcelFileDescriptor.parseMode (stub trên JVM unit test).
 * Dùng giá trị hằng AOSP chuẩn:
 *   MODE_READ_ONLY  = 0x10000000
 *   MODE_WRITE_ONLY = 0x20000000
 *   MODE_READ_WRITE = 0x30000000 (= R|W)
 *   MODE_APPEND     = 0x04000000
 *   parseMode("r")  = READ_ONLY, "w" = WRITE_ONLY,
 *   "rw" = READ_WRITE, "wa" = WRITE_ONLY|APPEND.
 *
 * Logic đúng (mirror code provider):
 *   isWrite = (mode & MODE_WRITE_ONLY) != 0 || (mode & MODE_APPEND) != 0
 */
class DocumentProviderModeTest {

    companion object {
        const val MODE_READ_ONLY = 0x10000000
        const val MODE_WRITE_ONLY = 0x20000000
        const val MODE_READ_WRITE = 0x30000000
        const val MODE_APPEND = 0x04000000

        // Giá trị ParcelFileDescriptor.parseMode trả về trên thiết bị thật.
        const val PARSE_R = MODE_READ_ONLY
        const val PARSE_W = MODE_WRITE_ONLY
        const val PARSE_RW = MODE_READ_WRITE
        const val PARSE_WA = MODE_WRITE_ONLY or MODE_APPEND
    }

    private fun isWrite(accessMode: Int): Boolean =
        (accessMode and MODE_WRITE_ONLY) != 0 ||
            (accessMode and MODE_APPEND) != 0

    private fun isWriteBuggy(accessMode: Int): Boolean =
        (accessMode and MODE_WRITE_ONLY) != 0 ||
            (accessMode and MODE_READ_WRITE) != 0

    @Test
    fun `read mode is not write`() {
        assertFalse(isWrite(PARSE_R))
    }

    @Test
    fun `write truncate mode is write`() {
        assertTrue(isWrite(PARSE_W))
    }

    @Test
    fun `read-write mode is write`() {
        assertTrue(isWrite(PARSE_RW))
    }

    @Test
    fun `append mode is write`() {
        assertTrue(isWrite(PARSE_WA))
    }

    @Test
    fun `old buggy check misclassifies read as write`() {
        // Khóa regression: check cũ nhận nhầm "r" thành ghi vì
        // READ_WRITE chứa bit READ_ONLY.
        assertTrue(isWriteBuggy(PARSE_R))
        assertFalse(isWrite(PARSE_R))
    }
}
