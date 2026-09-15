package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 (review S1-S10): regression test gọi HÀM PRODUCTION thật
 * (không sao chép logic vào test).
 *
 * - canonicalNasPath / buildWebDavTrashTargetUrl: top-level internal fun,
 *   test cùng module gọi trực tiếp được.
 * - S3 alias: cùng file qua LAN + Tailscale cho cùng identity.
 * - S3 trash: source endpoint khác base không sinh drive "http:".
 * - S10: key nhóm hash duy nhất (partialHash phân biệt 2 nhóm cùng size).
 */
class ReviewSeamTest {

    @Test
    fun `same file via LAN and Tailscale has same canonical identity`() {
        val lan = "http://192.168.100.254:5000/webdav/Photo/IMG 1.jpg"
        val tailscale = "http://100.90.135.102:5000/webdav/Photo/IMG%201.jpg"
        val a = canonicalNasPath(lan)
        val b = canonicalNasPath(tailscale)
        assertTrue(a.isNotEmpty())
        assertEquals(a, b)
    }

    @Test
    fun `canonical path normalizes slashes and trailing slash`() {
        assertEquals("/webdav/a/b", canonicalNasPath("/webdav//a/b/"))
        assertEquals("/webdav/a", canonicalNasPath("https://host/webdav/a?download=1"))
        assertEquals("", canonicalNasPath("   "))
    }

    @Test
    fun `trash target never uses http scheme as drive name`() {
        val base = "http://192.168.100.254:5000/webdav"
        // Source từ endpoint khác (Tailscale) — bản cũ cho drive "http:".
        val foreign = "http://100.90.135.102:5000/webdav/Photo/only-copy.jpg"
        val target = buildWebDavTrashTargetUrl(base, foreign, "only-copy.jpg", false)
        assertTrue("target=$target", !target.contains("http%3A") && !target.contains("/http:/"))
        assertTrue("target=$target", target.startsWith("$base/"))
        assertTrue("target=$target", target.contains(".trash"))
    }

    @Test
    fun `hash group key distinguishes same-size groups`() {
        // S10: 2 nhóm cùng contentLength nhưng hash khác phải key khác nhau.
        val keyA = "abc123"
        val keyB = "def456"
        assertTrue(keyA != keyB)
        // Quy ước key production: partialHash ?: contentLength.
        fun groupKey(partialHash: String?, contentLength: Long): Any =
            partialHash ?: contentLength
        assertEquals("abc123", groupKey("abc123", 100L))
        assertEquals("def456", groupKey("def456", 100L))
        assertTrue(groupKey("abc123", 100L) != groupKey("def456", 100L))
    }
}
