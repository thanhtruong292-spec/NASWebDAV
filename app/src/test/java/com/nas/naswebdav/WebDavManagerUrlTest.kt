package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavManagerUrlTest {

    @Test
    fun `call group constants are distinct`() {
        val groups = setOf(
            WebDavManager.CALL_GROUP_LOGIN,
            WebDavManager.CALL_GROUP_BROWSER,
            WebDavManager.CALL_GROUP_TRANSFER
        )
        assertEquals(3, groups.size)
    }

    @Test
    fun `MAX_PROPFIND_BYTES and MAX_ERROR_BODY_BYTES are sane`() {
        assertTrue(WebDavManager.MAX_PROPFIND_BYTES >= 1024 * 1024)
        assertEquals(8 * 1024, WebDavManager.MAX_ERROR_BODY_BYTES)
    }

    @Test
    fun `connection candidates prefer LAN and retain order within route`() {
        assertEquals(
            listOf("http://192.168.100.254:8822/webdav/", "http://nas.local:8822/webdav/", "http://100.90.135.102:8822/webdav/"),
            prioritizeConnectionUrls(listOf(
                "http://100.90.135.102:8822/webdav/",
                "",
                "http://192.168.100.254:8822/webdav/",
                "http://nas.local:8822/webdav/",
                "http://192.168.100.254:8822/webdav/"
            ))
        )
    }
}
