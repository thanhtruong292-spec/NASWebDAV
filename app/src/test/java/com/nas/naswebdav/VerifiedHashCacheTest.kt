package com.nas.naswebdav

import org.junit.Assert.*
import org.junit.Test

class VerifiedHashCacheTest {
    private fun file(path: String, hash: String = "a".repeat(64), size: Long = 10) =
        CachedFile(path, path.substringAfterLast('/'), false, null, "http://nas/", size, 1, fullHash=hash, accountKey="account")

    @Test fun `selecting every duplicate still preserves exactly one survivor`() {
        val files = listOf(file("http://nas/a"), file("http://nas/b"), file("http://nas/c"))
        val plan = planVerifiedDuplicateCleanup(files, files.map { it.path }.toSet())
        assertEquals(2, plan.size)
        assertEquals(1, plan.map { it.second.path }.toSet().size)
        assertFalse(plan.any { it.first.path == it.second.path })
        assertFalse(plan.map { it.first.path }.contains(plan.first().second.path))
    }

    @Test fun `partial hashes aliases different sizes and case sensitive paths are safe`() {
        val alias = listOf(file("http://nas/a"), file("http://tailscale/a"))
        assertTrue(planVerifiedDuplicateCleanup(alias, alias.map { it.path }.toSet()).isEmpty())
        val different = listOf(file("http://nas/a"),file("http://nas/b",size=11),file("http://nas/c",hash="partial"))
        assertTrue(planVerifiedDuplicateCleanup(different, different.map { it.path }.toSet()).isEmpty())
        val caseSensitive = listOf(file("http://nas/A"),file("http://nas/a"))
        assertEquals(1,planVerifiedDuplicateCleanup(caseSensitive, caseSensitive.map { it.path }.toSet()).size)
    }

    @Test fun `unicode paths retain original characters`() {
        assertEquals("ảnh/😀.jpg", relativeDuplicatePath("http://nas/webdav/", "http://nas/webdav/ảnh/😀.jpg"))
    }

    @Test fun `cache is bound to account path size strong version and complete SHA256`() {
        val entry = VerifiedHashCache("account", "http://nas/a", 10, "\"v1\"", "a".repeat(64), "1:1")
        assertTrue(reusableVerifiedHash(entry, "account", "http://nas/a", 10, "\"v1\"", "1:1"))
        assertFalse(reusableVerifiedHash(entry, entry.accountKey, entry.path, 10, entry.version, "2:2"))
        assertFalse(reusableVerifiedHash(entry, "other", entry.path, 10, entry.version, "1:1"))
        assertFalse(reusableVerifiedHash(entry, entry.accountKey, "http://nas/b", 10, entry.version, "1:1"))
        assertFalse(reusableVerifiedHash(entry, entry.accountKey, entry.path, 11, entry.version, "1:1"))
        assertFalse(reusableVerifiedHash(entry, entry.accountKey, entry.path, 10, "\"v2\"", "1:1"))
        assertFalse(reusableVerifiedHash(entry, entry.accountKey, entry.path, 10, "other-version", "1:1"))
        assertFalse(reusableVerifiedHash(entry.copy(sha256="partial"), entry.accountKey, entry.path, 10, entry.version, "1:1"))
    }
}
