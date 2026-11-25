package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test cho {@link FileMutationSync.toRelativePath} (MSG-0004 #1):
 * - Giu nguyen dau "+" literal (khong doi thanh khoang trang nhu URLDecoder.decode).
 * - Phan biet duong da URL-encoded (%HH) voi duong da decode (chua co %HH).
 */
class FileMutationSyncPathTest {

    @Test
    fun `encoded sentinel text stays literal alongside a plus`() {
        assertEquals("a+__NAS_PLUS_SENTINEL__.txt",
            FileMutationSync.fromUrl("https://nas.invalid/webdav/a+%5F_NAS_PLUS_SENTINEL__.txt"))
        assertEquals("webdav-archive/a+b.txt",
            FileMutationSync.fromUrl("https://nas.invalid/webdav-archive/a+b.txt"))
    }

    @Test
    fun `toRelativePath keeps '+' literal instead of converting to space`() {
        // "+" la ki tu literal cua ten file, phai duoc bao toan (khong duoc decode thanh ' ')
        assertEquals("Thư+mục/Ảnh+đẹp.jpg",
            FileMutationSync.toRelativePath("http://192.168.100.254:8822/webdav/Th%C6%B0+m%E1%BB%A5c/Ảnh+%C4%91%E1%BA%B9p.jpg"))
        assertEquals("file+name.txt", FileMutationSync.toRelativePath("file+name.txt"))
        assertEquals("a+b", FileMutationSync.toRelativePath("/webdav/a+b"))
    }

    @Test
    fun `toRelativePath keeps raw '+' together with percent 20 in same URL`() {
        // URL chua cung luc dau '+' thoa va ma %20: '%20' -> khoang trang, '+' giu nguyen.
        assertEquals("Thu muc+ten/ten+file.txt",
            FileMutationSync.toRelativePath("http://nas.local/webdav/Thu%20muc%2Bten/ten%2Bfile.txt"))
        assertEquals("a b+c", FileMutationSync.toRelativePath("a%20b+c"))
    }

    @Test
    fun `toRelativePath decodes percent-encoded bytes but leaves already-decoded path intact`() {
        // %HH duoc giai ma lai ky tu thuc
        assertEquals("Thư mục ảnh/Ảnh đẹp 2026.png",
            FileMutationSync.toRelativePath("http://192.168.100.254:8822/webdav/Th%C6%B0%20m%E1%BB%A5c%20%E1%BA%A3nh/%E1%BA%A2nh%20%C4%91%E1%BA%B9p%202026.png"))
        // Duong da decode (chua co %) tra lai nguyen van, bao toan dau '+'
        assertEquals("Ảnh + Tết.jpg", FileMutationSync.toRelativePath("/webdav/Ảnh + Tết.jpg"))
    }

    @Test
    fun `toRelativePath strips webdav prefix and leading trailing slashes`() {
        assertEquals("DCIM/Camera/IMG_2026.jpg",
            FileMutationSync.toRelativePath("http://192.168.100.254:8822/webdav/DCIM/Camera/IMG_2026.jpg"))
        assertEquals("Backup/files/data.bin", FileMutationSync.toRelativePath("/webdav/Backup/files/data.bin"))
        assertEquals("Backup/files/data.bin", FileMutationSync.toRelativePath("webdav/Backup/files/data.bin"))
        assertEquals("Backup/files/data.bin", FileMutationSync.toRelativePath("/Backup/files/data.bin"))
        assertEquals("Backup/files/data.bin", FileMutationSync.toRelativePath("Backup/files/data.bin"))
    }

    @Test
    fun `toRelativePath strips query and fragment`() {
        assertEquals("DCIM/video.mp4",
            FileMutationSync.toRelativePath("http://192.168.100.254:8822/webdav/DCIM/video.mp4?auth=token123&ts=999#player"))
    }

    @Test
    fun `toRelativePath handles literal percent and sentinel string safely`() {
        // Tên file chứa chuỗi __NAS_PLUS__ hoặc kí tự % literal (ví dụ Báo cáo 50%.pdf)
        assertEquals("Báo cáo 50%.pdf", FileMutationSync.toRelativePath("Báo cáo 50%.pdf"))
        assertEquals("prefix__NAS_PLUS__suffix.txt", FileMutationSync.toRelativePath("/webdav/prefix__NAS_PLUS__suffix.txt"))
        assertEquals("A+B__NAS_PLUS__C.jpg", FileMutationSync.toRelativePath("A+B__NAS_PLUS__C.jpg"))
    }

    @Test
    fun `toRelativePath empty and blank return empty`() {
        assertEquals("", FileMutationSync.toRelativePath(""))
        assertEquals("", FileMutationSync.toRelativePath("   "))
    }

    @Test
    fun `fromRawPath preserves literal question mark, hash, percent, plus and backslash`() {
        // Tên file thô chứa ký tự ? và # (không bị cắt như URL query/fragment)
        assertEquals("Báo cáo?#1.pdf", FileMutationSync.fromRawPath("Báo cáo?#1.pdf"))
        // Tên file thô chứa %20 (không bị decode nhầm thành khoảng trắng)
        assertEquals("Sale 20% Off.txt", FileMutationSync.fromRawPath("Sale 20% Off.txt"))
        assertEquals("Tiến độ 100%.xlsx", FileMutationSync.fromRawPath("/webdav/Tiến độ 100%.xlsx"))
        // Dấu gạch chéo ngược Windows được chuẩn hóa sang '/'
        assertEquals("Folder/Sub/File+Test#2.mp4", FileMutationSync.fromRawPath("Folder\\Sub\\File+Test#2.mp4"))
    }

    @Test
    fun `fromUrl decodes percent sequences and strips query and fragment`() {
        assertEquals("Folder/File Name+1.txt",
            FileMutationSync.fromUrl("http://nas.local:8822/webdav/Folder/File%20Name%2B1.txt?token=secret#section"))
        assertEquals("Photos/Đẹp.png",
            FileMutationSync.fromUrl("https://nas.local/webdav/Photos/%C4%90%E1%BA%B9p.png?v=2"))
    }
}
