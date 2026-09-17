package com.nas.naswebdav.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocaleParityTest {

    private fun keysOf(file: File): Set<String> {
        val names = Regex("""name="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
        return names
    }

    private fun resDir(name: String): File {
        // app/src/test chạy với workingDir = module dir (app/)
        val candidates = listOf(
            File("src/main/res/$name/strings.xml"),
            File("app/src/main/res/$name/strings.xml")
        )
        return candidates.first { it.exists() }
    }

    @Test
    fun `values values-en values-vi share identical keys`() {
        val base = keysOf(resDir("values"))
        val en = keysOf(resDir("values-en"))
        val vi = keysOf(resDir("values-vi"))
        assertEquals(base, en)
        assertEquals(base, vi)
    }

    @Test
    fun `values-vi contains Vietnamese content`() {
        val viText = resDir("values-vi").readText()
        // Spot-check vài chuỗi tiếng Việt có dấu đặc trưng.
        assertTrue(viText.contains("Tải xuống"))
        assertTrue(viText.contains("Xác nhận"))
    }

    @Test
    fun `values-en differs from Vietnamese default`() {
        val base = resDir("values").readText()
        val en = resDir("values-en").readText()
        assertTrue(en.contains("Download"))
        assertTrue(!en.contains("Tải xuống"))
        assertTrue(base.contains("Tải xuống"))
    }
}
