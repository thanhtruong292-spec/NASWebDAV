package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDavUrlTest — Unit tests for pure URL-construction helpers in WebDavManager.
 *
 * These functions are critical for trash and restore flows; bugs in URL
 * encoding break the entire .trash/ round-trip.
 */
class WebDavUrlTest {

    // ─── encodeWebDavSegment ───────────────────────────────────────────────────

    @Test
    fun `encodeWebDavSegment encode space va ky tu dac biet`() {
        assertEquals("hello%20world", encodeWebDavSegment("hello world"))
        assertEquals("file%2Fname", encodeWebDavSegment("file/name"))
    }

    @Test
    fun `encodeWebDavSegment giu nguyen ky tu Unicode nhu tieng Viet`() {
        // URLEncoder sẽ encode UTF-8 theo dạng %E1%BA%AD — verify byte length
        val encoded = encodeWebDavSegment("tập tin")
        assertTrue("Encoded phai chua % chu khong phai ky tu goc", encoded.contains("%"))
    }

    @Test
    fun `encodeWebDavSegment tra ve rong voi chuoi rong`() {
        assertEquals("", encodeWebDavSegment(""))
    }

    // ─── buildWebDavTrashTargetUrl ────────────────────────────────────────────

    @Test
    fun `buildWebDavTrashTargetUrl tao URL trash cho file tren NAS`() {
        val result = buildWebDavTrashTargetUrl(
            baseUrl = "http://nas.local:8080",
            sourcePath = "http://nas.local:8080/hdd1/folder/file.txt",
            fileName = "file.txt",
            isDirectory = false
        )
        assertEquals("http://nas.local:8080/hdd1/.trash/file.txt", result)
    }

    @Test
    fun `buildWebDavTrashTargetUrl them trailing slash cho directory`() {
        val result = buildWebDavTrashTargetUrl(
            baseUrl = "http://nas.local",
            sourcePath = "http://nas.local/ssd1/mydir",
            fileName = "mydir",
            isDirectory = true
        )
        assertTrue("Directory must end with /", result.endsWith("/"))
        assertTrue("Must include .trash/", result.contains("/.trash/"))
    }

    @Test
    fun `buildWebDavTrashTargetUrl encode ten file co ky tu dac biet`() {
        val result = buildWebDavTrashTargetUrl(
            baseUrl = "http://nas.local",
            sourcePath = "http://nas.local/hdd1/file.txt",
            fileName = "file ảnh.png",
            isDirectory = false
        )
        assertTrue("Path must contain encoded space", result.contains("%20"))
        assertTrue("Path must point to .trash/", result.contains("/.trash/"))
    }

    // ─── buildWebDavRestoreTargetUrl ──────────────────────────────────────────

    @Test
    fun `buildWebDavRestoreTargetUrl tao URL goc (khong qua trash) cho file`() {
        val result = buildWebDavRestoreTargetUrl(
            baseUrl = "http://nas.local",
            sourcePath = "http://nas.local/hdd1/.trash/file.txt",
            fileName = "file.txt",
            isDirectory = false
        )
        assertEquals("http://nas.local/hdd1/file.txt", result)
    }

    @Test
    fun `buildWebDavRestoreTargetUrl giu trailing slash cho directory`() {
        val result = buildWebDavRestoreTargetUrl(
            baseUrl = "http://nas.local",
            sourcePath = "http://nas.local/ssd1/.trash/mydir",
            fileName = "mydir",
            isDirectory = true
        )
        assertTrue("Directory must end with /", result.endsWith("/"))
        assertTrue("Must not include .trash/", !result.contains("/.trash/"))
    }
}
