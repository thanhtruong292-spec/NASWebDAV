package com.nas.naswebdav.utils

import android.app.Application
import com.nas.naswebdav.SystemLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * CrashLogExporterTest — verifies crash-export privacy and retention rules.
 *
 * Seams under test:
 * 1. buildCrashLogText() — pure function (Context unused), must redact URL userinfo.
 * 2. pruneOldExports() — file-system side effect, bounded to cache dir.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogExporterTest {

    private val context: Application
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    // ── Redaction tests ───────────────────────────────────────────────

    @Test
    fun `redacts userinfo from http URLs in log messages`() {
        val logs = listOf(
            SystemLog(
                type = "CRASH",
                module = "CrashHandler",
                message = "ConnectException: http://admin:s3cret@192.168.1.10:5050/api/ping"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)
        assertFalse("URL userinfo must not appear in output", text.contains("s3cret"))
        assertFalse("Username must also be redacted", text.contains("admin:"))
        assertTrue("Redacted marker must appear", text.contains("***@"))
        assertTrue("Host path must be preserved", text.contains("192.168.1.10:5050/api/ping"))
    }

    @Test
    fun `redacts userinfo from https URLs in log messages`() {
        val logs = listOf(
            SystemLog(
                type = "ERROR",
                module = "WebDavManager",
                message = "TLS handshake failed for https://root:p%40ss@nas.local/dav/"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)
        assertFalse("URL userinfo must not appear in output", text.contains("p%40ss"))
        assertFalse("Username must also be redacted", text.contains("root:"))
        assertTrue("Redacted marker must appear", text.contains("***@"))
        assertTrue("Host path must be preserved", text.contains("nas.local/dav/"))
    }

    @Test
    fun `preserves URLs without userinfo`() {
        val logs = listOf(
            SystemLog(
                type = "CRASH",
                module = "CrashHandler",
                message = "IOException: http://192.168.1.10:5050/api/files"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)
        assertTrue(
            "URL without userinfo must be preserved",
            text.contains("http://192.168.1.10:5050/api/files")
        )
    }

    @Test
    fun `redacts userinfo in multiple URLs within a single message`() {
        val logs = listOf(
            SystemLog(
                type = "ERROR",
                module = "SyncWorker",
                message = "Failed http://user1:pass1@host1/a and http://user2:pass2@host2/b"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)
        assertFalse(text.contains("pass1"))
        assertFalse(text.contains("pass2"))
        assertFalse(text.contains("user1:"))
        assertFalse(text.contains("user2:"))
        assertTrue("Redacted markers must appear", text.contains("***@"))
    }

    @Test
    fun `redacts userinfo with URL-encoded special characters`() {
        val logs = listOf(
            SystemLog(
                type = "CRASH",
                module = "CrashHandler",
                message = "error at http://admin:p%40ss%3Aword@10.0.0.1/data"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)
        assertFalse(text.contains("p%40ss%3Aword"))
        assertFalse(text.contains("admin:"))
        assertTrue("Redacted marker must appear", text.contains("***@"))
    }

    @Test
    fun `redacts bare username before host`() {
        val logs = listOf(
            SystemLog(
                type = "CRASH",
                module = "CrashHandler",
                message = "error at http://myuser@nas.local/path"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)
        assertFalse(
            "bare-user URL must be sanitized",
            text.contains("myuser@nas.local")
        )
        assertTrue(text.contains("***@nas.local"))
    }

    // ── Retention tests ───────────────────────────────────────────────

    @Test
    fun `pruneOldExports retains at most MAX_CRASH_EXPORTS files`() {
        val dir = createTempDir("crash-retention-test")
        try {
            repeat(10) { i ->
                File(dir, "crash-export-${1000 + i}.txt").writeText("old-$i")
            }
            CrashLogExporter.pruneOldExports(dir)
            val remaining = dir.listFiles()
                ?.filter { it.name.startsWith("crash-export-") }
                ?: emptyList()
            assertTrue(
                "Should retain at most ${CrashLogExporter.MAX_CRASH_EXPORTS}, got ${remaining.size}",
                remaining.size <= CrashLogExporter.MAX_CRASH_EXPORTS
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pruneOldExports does not delete non-crash-export files`() {
        val dir = createTempDir("crash-misc-test")
        try {
            File(dir, "other-file.txt").writeText("keep me")
            File(dir, "crash-export-1000.txt").writeText("old1")
            File(dir, "crash-export-1001.txt").writeText("old2")
            CrashLogExporter.pruneOldExports(dir)
            assertTrue(
                "non-crash-export file must survive",
                File(dir, "other-file.txt").exists()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pruneOldExports handles missing directory gracefully`() {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "nonexistent-crash-dir-${System.nanoTime()}"
        )
        // must not throw
        CrashLogExporter.pruneOldExports(dir)
    }

    @Test
    fun `pruneOldExports deletes oldest files first`() {
        val dir = createTempDir("crash-order-test")
        try {
            // Create 4 old files with older timestamps
            repeat(4) { i ->
                val f = File(dir, "crash-export-${1000 + i}.txt")
                f.writeText("content-$i")
                f.setLastModified(1_000_000L + i)
            }
            // Newest file
            val newFile = File(dir, "crash-export-2000.txt")
            newFile.writeText("newest")
            newFile.setLastModified(System.currentTimeMillis())

            CrashLogExporter.pruneOldExports(dir)

            assertTrue("newest file must survive", newFile.exists())
            val remaining = dir.listFiles()
                ?.filter { it.name.startsWith("crash-export-") }
                ?: emptyList()
            assertTrue(
                "at least one old file should be pruned",
                remaining.size <= CrashLogExporter.MAX_CRASH_EXPORTS
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── Header / structural test ──────────────────────────────────────

    @Test
    fun `header does not contain raw credential fragments`() {
        val logs = listOf(
            SystemLog(
                type = "CRASH",
                module = "CrashHandler",
                message = "user:pass@host crash"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)
        assertFalse("header must not contain raw password", text.contains("pass@host"))
    }

    // ── Header / structural contract (release-blocking) ───────────────

    @Test
    fun `buildCrashLogText includes header and key type markers`() {
        val logs = listOf(
            SystemLog(
                type = "CRASH",
                module = "WebDavManager",
                message = "upload failed"
            ),
            SystemLog(
                type = "ERROR",
                module = "AutoBackup",
                message = "retry queued"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)

        assertTrue("header must be present", text.contains("NAS WebDAV crash and error log"))
        assertTrue("CRASH type must be rendered", text.contains("[CRASH] WebDavManager: upload failed"))
        assertTrue("ERROR type must be rendered", text.contains("[ERROR] AutoBackup: retry queued"))
        assertTrue("module must be rendered", text.contains("WebDavManager"))
        assertTrue("module must be rendered", text.contains("AutoBackup"))
    }

    @Test
    fun `buildCrashLogText hides password and token fragments in messages`() {
        val logs = listOf(
            SystemLog(
                type = "CRASH",
                module = "AuthSession",
                message = "auth failed: password=hunter2 token=abc.def.ghi"
            )
        )
        val text = CrashLogExporter.buildCrashLogText(context, logs)

        assertFalse("raw password must not leak", text.contains("hunter2"))
        assertFalse("raw JWT token must not leak", text.contains("abc.def.ghi"))
    }
}
