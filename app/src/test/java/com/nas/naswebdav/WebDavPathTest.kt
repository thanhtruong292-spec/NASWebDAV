package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test JVM cho các hàm xử lý path/URL WebDAV (internal top-level trong WebDavManager.kt)
 * và phân loại URL tin cậy cleartext. Đây là logic đúng-sai ảnh hưởng bảo mật + chính xác thao tác file.
 * Toàn bộ thuần JVM (URL/URI/URLEncoder/InetAddress với IP literal — không chạm DNS).
 */
class WebDavPathTest {

    // ----- encodeWebDavSegment -----
    @Test fun encode_plain() = assertEquals("hello", encodeWebDavSegment("hello"))
    @Test fun encode_space() = assertEquals("a%20b", encodeWebDavSegment("a b"))
    @Test fun encode_idempotentOnEncoded() = assertEquals("a%20b", encodeWebDavSegment("a%20b"))

    // ----- normalizeWebDavResourcePath: cắt query/fragment + trim -----
    @Test fun resourcePath_stripsQueryFragment() =
        assertEquals("http://x/a", normalizeWebDavResourcePath("  http://x/a?q=1#frag "))

    // ----- normalizeWebDavFolderUrl: đảm bảo trailing slash -----
    @Test fun folderUrl_addsSlash() = assertEquals("http://x/d/", normalizeWebDavFolderUrl("http://x/d"))
    @Test fun folderUrl_keepsSlash() = assertEquals("http://x/d/", normalizeWebDavFolderUrl("http://x/d/"))
    @Test fun folderUrl_blank() = assertEquals("", normalizeWebDavFolderUrl("   "))

    // ----- normalizeWebDavRelativePath: bỏ base, encode từng segment -----
    @Test fun relative_underBase() =
        assertEquals("folder/file.txt", normalizeWebDavRelativePath("http://x/dav", "http://x/dav/folder/file.txt"))
    @Test fun relative_encodesSpaces() =
        assertEquals("a%20b/c", normalizeWebDavRelativePath("http://x/dav", "http://x/dav/a b/c"))
    @Test fun relative_noBaseUsesPath() =
        assertEquals("dav/f", normalizeWebDavRelativePath("", "http://x/dav/f"))

    // ----- buildWebDavTrashRootUrl -----
    @Test fun trashRoot_fromSlash() = assertEquals("http://x/dav/.trash/", buildWebDavTrashRootUrl("http://x/dav/"))
    @Test fun trashRoot_fromNoSlash() = assertEquals("http://x/dav/.trash/", buildWebDavTrashRootUrl("http://x/dav"))

    // ----- webDavParentFolderUrl -----
    @Test fun parent_ofFile() = assertEquals("http://x/a/b/", webDavParentFolderUrl("http://x/a/b/c.txt"))
    @Test fun parent_blank() = assertEquals("", webDavParentFolderUrl(""))

    // ----- escapeSqlLikePrefix: escape \ % _ đúng thứ tự -----
    @Test fun sqlEscape_all() = assertEquals("a\\_b\\%c\\\\d", escapeSqlLikePrefix("a_b%c\\d"))
    @Test fun sqlEscape_noop() = assertEquals("plain", escapeSqlLikePrefix("plain"))

    // ----- webDavSubtreePrefix -----
    @Test fun subtreePrefix() = assertEquals("http://x/a/%", webDavSubtreePrefix("http://x/a/"))

    // ----- isLanOrTailscaleWebDavUrl: BẢO MẬT — chỉ tin LAN/Tailscale/HTTPS -----
    @Test fun trust_https() = assertTrue(isLanOrTailscaleWebDavUrl("https://anything.example"))
    @Test fun trust_localhost() = assertTrue(isLanOrTailscaleWebDavUrl("http://localhost:5005"))
    @Test fun trust_loopbackIp() = assertTrue(isLanOrTailscaleWebDavUrl("http://127.0.0.1"))
    @Test fun trust_tsNet() = assertTrue(isLanOrTailscaleWebDavUrl("http://nas.ts.net/dav"))
    @Test fun trust_private10() = assertTrue(isLanOrTailscaleWebDavUrl("http://10.1.2.3:5005/dav"))
    @Test fun trust_private192() = assertTrue(isLanOrTailscaleWebDavUrl("http://192.168.100.254"))
    @Test fun trust_private172() = assertTrue(isLanOrTailscaleWebDavUrl("http://172.16.0.1"))
    @Test fun trust_tailscaleCgnat() = assertTrue(isLanOrTailscaleWebDavUrl("http://100.90.135.102"))

    @Test fun reject_publicIp() = assertFalse(isLanOrTailscaleWebDavUrl("http://8.8.8.8"))
    @Test fun reject_172OutOfRange() = assertFalse(isLanOrTailscaleWebDavUrl("http://172.32.0.1"))
    @Test fun reject_blank() = assertFalse(isLanOrTailscaleWebDavUrl(""))
    @Test fun reject_garbage() = assertFalse(isLanOrTailscaleWebDavUrl("not a url"))
}
