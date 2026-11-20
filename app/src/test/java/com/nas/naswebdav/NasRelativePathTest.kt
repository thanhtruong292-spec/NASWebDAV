package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Test

class NasRelativePathTest {

    @Test
    fun `empty and blank strings return empty`() {
        assertEquals("", "".toNasRelativePath())
        assertEquals("", "   ".toNasRelativePath())
    }

    @Test
    fun `webdav full urls with port and scheme`() {
        assertEquals(
            "DCIM/Camera/IMG_2026.jpg",
            "http://192.168.100.254:8822/webdav/DCIM/Camera/IMG_2026.jpg".toNasRelativePath()
        )
        assertEquals(
            "DCIM/Camera/IMG_2026.jpg",
            "https://nas.example.com/webdav/DCIM/Camera/IMG_2026.jpg".toNasRelativePath()
        )
    }

    @Test
    fun `urls without webdav prefix in path`() {
        assertEquals(
            "New folder/document.pdf",
            "http://192.168.100.254:8822/New folder/document.pdf".toNasRelativePath()
        )
    }

    @Test
    fun `encoded and unencoded special characters and unicode`() {
        assertEquals(
            "Thư mục ảnh/Ảnh đẹp 2026.png",
            "http://192.168.100.254:8822/webdav/Th%C6%B0%20m%E1%BB%A5c%20%E1%BA%A3nh/%E1%BA%A2nh%20%C4%91%E1%BA%B9p%202026.png".toNasRelativePath()
        )
        assertEquals(
            "Ảnh Gia Đình/Tết 2026.jpg",
            "/webdav/Ảnh Gia Đình/Tết 2026.jpg".toNasRelativePath()
        )
    }

    @Test
    fun `relative paths with leading slash or without`() {
        assertEquals("Backup/files/data.bin", "/webdav/Backup/files/data.bin".toNasRelativePath())
        assertEquals("Backup/files/data.bin", "webdav/Backup/files/data.bin".toNasRelativePath())
        assertEquals("Backup/files/data.bin", "/Backup/files/data.bin".toNasRelativePath())
        assertEquals("Backup/files/data.bin", "Backup/files/data.bin".toNasRelativePath())
    }

    @Test
    fun `urls with query parameters and fragments stripped`() {
        assertEquals(
            "DCIM/video.mp4",
            "http://192.168.100.254:8822/webdav/DCIM/video.mp4?auth=token123&ts=999#player".toNasRelativePath()
        )
    }
}
