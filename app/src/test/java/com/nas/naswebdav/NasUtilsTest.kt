package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NasUtilsTest — Unit tests for pure utility functions in NasUtils.kt.
 *
 * Seams under test:
 * - URL parsing: safeUrlHost, isTailscaleUrl, adaptiveTimeoutMs
 * - Error classification: friendlyError, isTransientNetworkFailure
 * - Message building: buildLoginFailureMessage
 * - Wake-on-LAN: normalizeWakeOnLanMac
 */
class NasUtilsTest {

    // ─── safeUrlHost ────────────────────────────────────────────────────────────

    @Test
    fun `safeUrlHost tra ve host chinh xac`() {
        assertEquals("192.168.1.1", safeUrlHost("http://192.168.1.1:8080/path"))
        assertEquals("nas.local", safeUrlHost("https://nas.local/share"))
        assertEquals("example.com", safeUrlHost("http://example.com:443/"))
    }

    @Test
    fun `safeUrlHost tra ve rong voi URL khong hop le`() {
        assertEquals("", safeUrlHost(""))
        assertEquals("", safeUrlHost("not a url"))
        assertEquals("", safeUrlHost("http://"))
    }

    // ─── isTailscaleUrl ────────────────────────────────────────────────────────

    @Test
    fun `isTailscaleUrl tra ve true cho URL chua tailscale`() {
        assertTrue(isTailscaleUrl("https://nas.tail0001.tailscale.com/share"))
        assertTrue(isTailscaleUrl("http://my-nas.tailscale.com"))
        assertTrue(isTailscaleUrl("https://tailscale.com/device/abc"))
    }

    @Test
    fun `isTailscaleUrl tra ve true cho CGNAT 100_64_0_0_10`() {
        assertTrue(isTailscaleUrl("http://100.64.1.1"))
        assertTrue(isTailscaleUrl("http://100.127.255.254"))
    }

    @Test
    fun `isTailscaleUrl tra ve false cho LAN IP thuong`() {
        assertFalse(isTailscaleUrl("http://192.168.1.1"))
        assertFalse(isTailscaleUrl("http://10.0.0.5"))
        assertFalse(isTailscaleUrl("http://172.16.0.1"))
    }

    @Test
    fun `isTailscaleUrl tra ve false cho internet IP`() {
        assertFalse(isTailscaleUrl("http://8.8.8.8"))
        assertFalse(isTailscaleUrl("http://1.1.1.1"))
        assertFalse(isTailscaleUrl("http://1.2.3.4"))
    }

    @Test
    fun `isTailscaleUrl tra ve false cho CGNAT ngoai 100_64_0_0_10`() {
        assertFalse(isTailscaleUrl("http://100.63.255.255"))
        assertFalse(isTailscaleUrl("http://100.128.0.0"))
    }

    @Test
    fun `isTailscaleUrl tra ve false cho URL rong`() {
        assertFalse(isTailscaleUrl(""))
        assertFalse(isTailscaleUrl("   "))
    }

    // ─── normalizeWakeOnLanMac ─────────────────────────────────────────────────

    @Test
    fun `normalizeWakeOnLanMac tra ve MAC chuan voi dau gach ngang`() {
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeWakeOnLanMac("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeWakeOnLanMac("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeWakeOnLanMac("AABBCCDDEEFF"))
    }

    @Test
    fun `normalizeWakeOnLanMac tra ve null voi chuoi qua ngan`() {
        assertNull(normalizeWakeOnLanMac("aa:bb:cc"))
        assertNull(normalizeWakeOnLanMac(""))
        assertNull(normalizeWakeOnLanMac("aabbccddeeff00extra"))
    }

    @Test
    fun `normalizeWakeOnLanMac tra ve null voi ky tu khong phai hex`() {
        assertNull(normalizeWakeOnLanMac("gg:hh:ii:jj:kk:ll"))
        assertNull(normalizeWakeOnLanMac("aa:bb:cc:dd:ee:gx"))
    }

    // ─── friendlyError ─────────────────────────────────────────────────────────

    @Test
    fun `friendlyError tra ve message phu hop voi SocketTimeoutException`() {
        val ex = java.net.SocketTimeoutException("Read timed out")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("quá chậm") || msg.contains("không phản hồi"))
    }

    @Test
    fun `friendlyError tra ve message phu hop voi ConnectException`() {
        val ex = java.net.ConnectException("Connection refused")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("không thể kết nối") || msg.contains("Kiểm tra"))
    }

    @Test
    fun `friendlyError tra ve message phu hop voi UnknownHostException`() {
        val ex = java.net.UnknownHostException("Unable to resolve host nas.local")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("không hợp lệ") || msg.contains("mất kết nối"))
    }

    @Test
    fun `friendlyError tra ve message phu hop voi HTTP 401`() {
        val ex = java.io.IOException("GET failed: 401 - Unauthorized")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("401") && msg.contains("xác thực"))
    }

    @Test
    fun `friendlyError tra ve message phu hop voi HTTP 403`() {
        val ex = java.io.IOException("PUT failed: 403 - Forbidden")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("403") && msg.contains("quyền"))
    }

    @Test
    fun `friendlyError tra ve message phu hop voi HTTP 404`() {
        val ex = java.io.IOException("GET failed: 404 - Not Found")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("404") && msg.contains("Không tìm thấy"))
    }

    @Test
    fun `friendlyError tra ve message voi HTTP 405 va ten phuong thuc`() {
        val ex = java.io.IOException("POST failed: 405 - Method Not Allowed")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("405") && msg.contains("POST"))
    }

    @Test
    fun `friendlyError tra ve message voi HTTP 409`() {
        val ex = java.io.IOException("MKCOL failed: 409 - Conflict")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("409") && msg.contains("Xung đột"))
    }

    @Test
    fun `friendlyError tra ve message voi HTTP 423`() {
        val ex = java.io.IOException("LOCK failed: 423 - Locked")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("423") && msg.contains("khóa"))
    }

    @Test
    fun `friendlyError tra ve raw message voi HTTP code khong ro`() {
        val ex = java.io.IOException("GET failed: 999 - Unknown error")
        val msg = friendlyError(ex)
        assertTrue(msg.contains("999"))
    }

    @Test
    fun `friendlyError tra ve raw message khi khong co regex match`() {
        val ex = java.io.IOException("Something went wrong")
        val msg = friendlyError(ex)
        assertEquals("Something went wrong", msg)
    }

    // ─── isTransientNetworkFailure ─────────────────────────────────────────────

    @Test
    fun `isTransientNetworkFailure tra ve true cho SocketTimeoutException`() {
        assertTrue(java.net.SocketTimeoutException().isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve true cho ConnectException`() {
        assertTrue(java.net.ConnectException().isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve true cho UnknownHostException`() {
        assertTrue(java.net.UnknownHostException().isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve true cho InterruptedIOException`() {
        assertTrue(java.io.InterruptedIOException().isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve true cho message chua timeout`() {
        assertTrue(RuntimeException("connection timeout").isTransientNetworkFailure())
        assertTrue(RuntimeException("Read timed out").isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve true cho message chua connection reset`() {
        assertTrue(RuntimeException("Connection reset").isTransientNetworkFailure())
        assertTrue(RuntimeException("connection refused").isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve false cho message chua HTTP error`() {
        assertFalse(RuntimeException("GET failed: 401").isTransientNetworkFailure())
        assertFalse(RuntimeException("POST failed: 500").isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve false cho SSLException`() {
        val sslEx = javax.net.ssl.SSLException("SSL handshake failed")
        assertFalse(sslEx.isTransientNetworkFailure())
    }

    @Test
    fun `isTransientNetworkFailure tra ve true khi cause la transient`() {
        val cause = java.net.SocketTimeoutException()
        val ex = RuntimeException("wrapper", cause)
        assertTrue(ex.isTransientNetworkFailure())
    }

    // ─── buildLoginFailureMessage ──────────────────────────────────────────────

    @Test
    fun `buildLoginFailureMessage tra ve message mac dinh khi errorDetails rong`() {
        val msg = buildLoginFailureMessage(emptyList(), emptyList())
        assertTrue(msg.contains("Không đăng nhập được"))
    }

    @Test
    fun `buildLoginFailureMessage tra ve message voi mot loi`() {
        val msg = buildLoginFailureMessage(listOf("http://nas.local"), listOf("http://nas.local: timeout"))
        assertTrue(msg.contains("Không đăng nhập được NAS:"))
        assertTrue(msg.contains("nas.local"))
    }

    @Test
    fun `buildLoginFailureMessage tra ve message voi nhieu loi`() {
        val msg = buildLoginFailureMessage(
            listOf("http://nas1.local", "http://nas2.local"),
            listOf("http://nas1.local: timeout", "http://nas2.local: Connection refused")
        )
        assertTrue(msg.contains("đã thử 2 địa chỉ"))
        assertTrue(msg.contains("nas1.local"))
        assertTrue(msg.contains("nas2.local"))
    }

    @Test
    fun `buildLoginFailureMessage phan loai WebDAV error dung`() {
        val msg = buildLoginFailureMessage(
            listOf("http://nas.local"),
            listOf("http://nas.local: WebDAV error")
        )
        assertTrue(msg.contains("WebDAV quá hạn") || msg.contains("xác thực"))
    }

    @Test
    fun `buildLoginFailureMessage phan loai timeout error dung`() {
        val msg = buildLoginFailureMessage(
            listOf("http://nas.local"),
            listOf("http://nas.local: timeout exceeded")
        )
        assertTrue(msg.contains("quá hạn") || msg.contains("phản hồi"))
    }
}
