package com.nas.naswebdav.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashLogExporterTest {

    @Test
    fun `redactUrlUserinfo strips userinfo from https URL`() {
        val redacted = CrashLogExporter.redactUrlUserinfo("Connect to https://daica:s3cr3t@nas.local/webdav")
        assertFalse("password should be removed", redacted.contains("s3cr3t"))
        assertTrue("scheme should remain", redacted.contains("https://"))
        assertTrue(redacted.contains("***@"))
    }

    @Test
    fun `redactUrlUserinfo strips only-username URL form`() {
        val redacted = CrashLogExporter.redactUrlUserinfo("Tried https://daica@nas.local/webdav")
        assertFalse(redacted.contains("daica"))
        assertTrue(redacted.contains("***@nas.local"))
    }

    @Test
    fun `redactUrlUserinfo redacts labeled password`() {
        val redacted = CrashLogExporter.redactUrlUserinfo("login password=SuperSecret123 user=daica")
        assertFalse(redacted.contains("SuperSecret123"))
        assertTrue(redacted.contains("password=***"))
    }

    @Test
    fun `redactUrlUserinfo redacts token api-key and secret labels`() {
        val msg = """
            token=abc.def.ghi
            api_key=apikeyvalue123
            secret=hunter2
            refresh-token=rt-789
        """.trimIndent()
        val redacted = CrashLogExporter.redactUrlUserinfo(msg)
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("apikeyvalue123"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("rt-789"))
    }

    @Test
    fun `redactUrlUserinfo redacts Authorization header`() {
        val redacted = CrashLogExporter.redactUrlUserinfo("Authorization: Bearer eyJhbGciOi.payload.sig")
        assertFalse(redacted.contains("eyJhbGciOi"))
        assertTrue(redacted.contains("Bearer ***"))
    }

    @Test
    fun `redactUrlUserinfo leaves non-credential content intact`() {
        val safe = "Backup started on 2026-08-01 with 12 files"
        val redacted = CrashLogExporter.redactUrlUserinfo(safe)
        assertEquals(safe, redacted)
    }

    @Test
    fun `pruneOldExports keeps at most maxFiles newest`() {
        val tempDir = kotlin.io.path.createTempDirectory().toFile()
        try {
            repeat(5) { i ->
                File(tempDir, "crash-export-100${i}000.txt").apply {
                    writeText("dummy")
                    setLastModified(100_000L + i.toLong() * 1_000L)
                }
            }
            CrashLogExporter.pruneOldExports(tempDir, maxFiles = 2)
            val remaining = tempDir.listFiles()?.filter {
                it.name.startsWith("crash-export-") && it.name.endsWith(".txt")
            } ?: emptyList()
            assertEquals(2, remaining.size)
            // Newest two should be kept: timestamps 104000 and 103000
            val kept = remaining.sortedByDescending { it.lastModified() }
            assertEquals(104_000L, kept[0].lastModified())
            assertEquals(103_000L, kept[1].lastModified())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
