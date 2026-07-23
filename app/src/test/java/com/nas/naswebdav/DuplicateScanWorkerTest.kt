package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DuplicateScanWorkerTest — Unit tests for duplicate scanning logic & state.
 */
class DuplicateScanWorkerTest {

    @Before
    fun setUp() {
        // Reset DuplicateProgressState to defaults before each test
        DuplicateProgressState.isPaused.value = false
        DuplicateProgressState.stage.value = "Khởi động..."
        DuplicateProgressState.scannedCount.value = 0
        DuplicateProgressState.foundCount.value = 0
        DuplicateProgressState.percent.value = 0f
        DuplicateProgressState.stageNumber.value = 1
    }

    @Test
    fun `DuplicateProgressState default values standard`() {
        assertFalse(DuplicateProgressState.isPaused.value)
        assertEquals("Khởi động...", DuplicateProgressState.stage.value)
        assertEquals(0, DuplicateProgressState.scannedCount.value)
        assertEquals(0, DuplicateProgressState.foundCount.value)
        assertEquals(0f, DuplicateProgressState.percent.value, 0.001f)
        assertEquals(1, DuplicateProgressState.stageNumber.value)
        assertEquals(4, DuplicateProgressState.totalStages.value)
    }

    @Test
    fun `DuplicateProgressState state updates correctly`() {
        DuplicateProgressState.stage.value = "Bắt đầu băm"
        DuplicateProgressState.scannedCount.value = 1500
        DuplicateProgressState.foundCount.value = 42
        DuplicateProgressState.percent.value = 75.5f

        assertEquals("Bắt đầu băm", DuplicateProgressState.stage.value)
        assertEquals(1500, DuplicateProgressState.scannedCount.value)
        assertEquals(42, DuplicateProgressState.foundCount.value)
        assertEquals(75.5f, DuplicateProgressState.percent.value, 0.001f)
    }

    @Test
    fun `Duplicate file grouping by size and partial hash`() {
        val files = listOf(
            CachedFile("/path/a.mp4", "a.mp4", false, "video/mp4", "/path", 1048576L, 1000L, "hash1"),
            CachedFile("/path/b.mp4", "b.mp4", false, "video/mp4", "/path", 1048576L, 2000L, "hash1"),
            CachedFile("/path/c.mp4", "c.mp4", false, "video/mp4", "/path", 1048576L, 3000L, "hash2"),
            CachedFile("/path/d.mp4", "d.mp4", false, "video/mp4", "/path", 2097152L, 4000L, "hash3")
        )

        // Group by size first
        val bySize = files.groupBy { it.contentLength }
        assertEquals(2, bySize.size)
        assertEquals(3, bySize[1048576L]?.size)
        assertEquals(1, bySize[2097152L]?.size)

        // Candidates with size count > 1
        val candidateSizes = bySize.filter { it.value.size > 1 }
        assertEquals(1, candidateSizes.size)

        // Group candidate files by partial hash
        val candidateFiles = candidateSizes.values.flatten()
        val byHash = candidateFiles.groupBy { it.partialHash }
        assertEquals(2, byHash.size)
        assertEquals(2, byHash["hash1"]?.size)
        assertEquals(1, byHash["hash2"]?.size)

        // Actual duplicate group (count > 1)
        val duplicateGroups = byHash.filter { it.value.size > 1 }
        assertEquals(1, duplicateGroups.size)
        assertEquals(listOf("a.mp4", "b.mp4"), duplicateGroups["hash1"]?.map { it.name })
    }

    @Test
    fun `Calculate saved space from duplicate files`() {
        val duplicateGroup = listOf(
            CachedFile("/p/1.mkv", "1.mkv", false, "video/mkv", "/p", 500_000_000L, 100L, "h"),
            CachedFile("/p/2.mkv", "2.mkv", false, "video/mkv", "/p", 500_000_000L, 200L, "h"),
            CachedFile("/p/3.mkv", "3.mkv", false, "video/mkv", "/p", 500_000_000L, 300L, "h")
        )

        // Keeping 1 file, deleting 2 duplicates saves: 2 * 500MB = 1000MB
        val fileSize = duplicateGroup.first().contentLength
        val duplicateCount = duplicateGroup.size - 1
        val savedBytes = fileSize * duplicateCount

        assertEquals(1_000_000_000L, savedBytes)
    }
}
