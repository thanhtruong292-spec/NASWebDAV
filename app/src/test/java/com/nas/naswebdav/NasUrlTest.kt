package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test JVM cho String.toApiBaseUrl() — chuyển base WebDAV sang base API (port 5050).
 * Thuần java.net.URL, không chạm Android framework.
 */
class NasUrlTest {

    @Test fun apiBase_fromHttpWithPort() =
        assertEquals("http://192.168.1.5:5050", "http://192.168.1.5:5005/webdav".toApiBaseUrl())

    @Test fun apiBase_addsSchemeWhenMissing() =
        assertEquals("http://192.168.1.5:5050", "192.168.1.5".toApiBaseUrl())

    @Test fun apiBase_keepsHttps() =
        assertEquals("https://nas.ts.net:5050", "https://nas.ts.net/dav/".toApiBaseUrl())

    @Test fun apiBase_emptyInput() = assertEquals("", "".toApiBaseUrl())

    @Test fun apiBase_rejectsLeadingSlash() = assertEquals("", "/relative/path".toApiBaseUrl())

    @Test fun apiBase_trimsTrailingSlash() =
        assertEquals("http://host:5050", "http://host/".toApiBaseUrl())
}
