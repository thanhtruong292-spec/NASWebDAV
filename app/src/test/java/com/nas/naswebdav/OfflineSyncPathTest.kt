package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test JVM cho resolveQueuedWebDavPath — rebind path đã xếp hàng (offline queue)
 * sang base URL ĐANG hoạt động. Quan trọng khi IP NAS đổi (LAN↔Tailscale) giữa lúc
 * xếp hàng và lúc đồng bộ: nếu rebind sai → thao tác gửi nhầm host.
 */
class OfflineSyncPathTest {

    @Test fun absolute_rebindsToActiveAuthorityKeepsPath() =
        assertEquals(
            "http://10.0.0.2:6000/dav/file.txt",
            resolveQueuedWebDavPath("http://192.168.1.1:5005/dav/file.txt", "http://10.0.0.2:6000/dav")
        )

    @Test fun absolute_preservesQuery() =
        assertEquals(
            "http://new/dav/f?a=1",
            resolveQueuedWebDavPath("http://old/dav/f?a=1", "http://new")
        )

    @Test fun relative_withLeadingSlash() =
        assertEquals(
            "http://h/base/dav/x",
            resolveQueuedWebDavPath("/dav/x", "http://h/base/")
        )

    @Test fun relative_withoutLeadingSlash() =
        assertEquals(
            "http://h/base/sub/x",
            resolveQueuedWebDavPath("sub/x", "http://h/base")
        )

    @Test fun absolute_malformedActiveFallsBackToRaw() =
        // activeBaseUrl không parse được URL → trả nguyên rawPath
        assertEquals(
            "http://old/dav/f",
            resolveQueuedWebDavPath("http://old/dav/f", "not a url")
        )
}
