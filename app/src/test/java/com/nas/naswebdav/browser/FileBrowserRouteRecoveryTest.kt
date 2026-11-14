package com.nas.naswebdav.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileBrowserRouteRecoveryTest {
    @Test
    fun `route change preserves current folder and encoded path`() {
        assertEquals(
            "http://100.90.135.102:8822/webdav/Photos/My%20Album/",
            rebaseWebDavUrl(
                "http://192.168.100.254:8822/webdav/Photos/My%20Album/",
                "http://192.168.100.254:8822/webdav/",
                "http://100.90.135.102:8822/webdav/"
            )
        )
    }

    @Test
    fun `url outside old WebDAV root is not rebased`() {
        assertNull(
            rebaseWebDavUrl(
                "https://cdn.example/video.mp4",
                "http://192.168.100.254:8822/webdav/",
                "http://100.90.135.102:8822/webdav/"
            )
        )
    }
}
