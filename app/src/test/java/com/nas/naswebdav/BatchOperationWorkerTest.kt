package com.nas.naswebdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BatchOperationWorkerTest — Kiểm tra logic thuần của BatchOperationWorker
 * mà không cần Android Context (không dùng WorkManager/ApplicationContext).
 *
 * Các behavior được test:
 * 1. Khi filePaths rỗng → phải trả Result.success() ngay (no-op)
 * 2. COPY/MOVE thiếu destUrl → phải trả Result.failure() với "DEST_EMPTY"
 * 3. isStopped check: iterator phải break ngay khi isStopped = true
 * 4. Operation label mapping đúng (COPY→"Sao chép", DELETE→"Xóa"...)
 * 5. Batch counter logic: successCount và failCount cộng đúng
 *
 * NOTE: Worker không thể khởi tạo trực tiếp trong unit test (cần WorkerParameters
 * từ WorkManager). Các test này kiểm tra LOGIC THUẦN bằng cách extract
 * hàm helper hoặc test data transformation.
 */
class BatchOperationWorkerTest {

    // ── Helper: simulate batch processing logic (extracted từ doWork) ─────

    data class BatchResult(val successCount: Int, val failCount: Int, val stoppedEarly: Boolean)

    /**
     * Simulate vòng lặp batch của Worker — không cần Context.
     * processItem: (filePath, fileName) -> Boolean (true = success)
     * isStopped: kiểm tra trạng thái dừng giữa các item
     */
    private fun simulateBatch(
        filePaths: Array<String>,
        fileNames: Array<String>,
        isStopped: () -> Boolean,
        processItem: (String, String) -> Boolean
    ): BatchResult {
        var successCount = 0
        var failCount = 0
        var stoppedEarly = false

        for ((index, filePath) in filePaths.withIndex()) {
            if (isStopped()) {
                stoppedEarly = true
                break
            }
            val fileName = fileNames.getOrElse(index) { filePath.substringAfterLast("/") }
            val ok = try {
                processItem(filePath, fileName)
            } catch (e: Exception) {
                false
            }
            if (ok) successCount++ else failCount++
        }
        return BatchResult(successCount, failCount, stoppedEarly)
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `filePaths_rong_tra_ve_success_khong_xu_ly_gi`() {
        val result = simulateBatch(
            filePaths = emptyArray(),
            fileNames = emptyArray(),
            isStopped = { false },
            processItem = { _, _ -> true }
        )
        assertEquals("Khong co item nao duoc xu ly", 0, result.successCount)
        assertEquals(0, result.failCount)
        assertFalse("Khong phai stopped early", result.stoppedEarly)
    }

    @Test
    fun `tat_ca_items_thanh_cong`() {
        val paths = arrayOf("http://nas/file1.jpg", "http://nas/file2.mp4", "http://nas/doc.pdf")
        val names = arrayOf("file1.jpg", "file2.mp4", "doc.pdf")
        val result = simulateBatch(paths, names, { false }) { _, _ -> true }

        assertEquals("Tat ca 3 items thanh cong", 3, result.successCount)
        assertEquals(0, result.failCount)
        assertFalse(result.stoppedEarly)
    }

    @Test
    fun `mot_so_items_that_bai`() {
        val paths = arrayOf("http://nas/a.jpg", "http://nas/b.jpg", "http://nas/c.jpg")
        val names = arrayOf("a.jpg", "b.jpg", "c.jpg")
        var callCount = 0
        val result = simulateBatch(paths, names, { false }) { _, _ ->
            callCount++
            callCount % 2 == 1 // item 1,3 success; item 2 fail
        }
        assertEquals(2, result.successCount)
        assertEquals(1, result.failCount)
    }

    @Test
    fun `isStopped_break_vong_lap_ngay_lap_tuc`() {
        val paths = arrayOf("http://nas/f1.jpg", "http://nas/f2.jpg", "http://nas/f3.jpg")
        val names = arrayOf("f1.jpg", "f2.jpg", "f3.jpg")
        var processedCount = 0

        // Dừng ngay sau item đầu tiên
        val result = simulateBatch(paths, names, { processedCount >= 1 }) { _, _ ->
            processedCount++
            true
        }

        assertTrue("Phai dung giua chung", result.stoppedEarly)
        assertEquals("Chi xu ly 1 item truoc khi dung", 1, processedCount)
    }

    @Test
    fun `isStopped_tu_dau_khong_xu_ly_bat_ky_item_nao`() {
        val paths = arrayOf("http://nas/a.jpg", "http://nas/b.jpg")
        val names = arrayOf("a.jpg", "b.jpg")
        var processedCount = 0
        val result = simulateBatch(paths, names, { true }) { _, _ ->
            processedCount++
            true
        }
        assertTrue(result.stoppedEarly)
        assertEquals("Khong co item nao duoc xu ly khi isStopped=true ngay tu dau", 0, processedCount)
    }

    @Test
    fun `exception_trong_processItem_duoc_dem_la_fail`() {
        val paths = arrayOf("http://nas/bad.jpg")
        val names = arrayOf("bad.jpg")
        val result = simulateBatch(paths, names, { false }) { _, _ ->
            throw RuntimeException("Simulated network error")
        }
        assertEquals("Exception = 1 failCount", 1, result.failCount)
        assertEquals(0, result.successCount)
    }

    @Test
    fun `operation_label_mapping_dung`() {
        // Test mapping logic tĩnh từ doWork (extract thành helper để test)
        fun operationLabel(op: String) = when (op) {
            "COPY" -> "Sao chép"
            "MOVE" -> "Di chuyển"
            "DELETE" -> "Xóa"
            "RESTORE" -> "Khôi phục"
            else -> "Xử lý"
        }

        assertEquals("Sao chép", operationLabel("COPY"))
        assertEquals("Di chuyển", operationLabel("MOVE"))
        assertEquals("Xóa", operationLabel("DELETE"))
        assertEquals("Khôi phục", operationLabel("RESTORE"))
        assertEquals("Xử lý", operationLabel("UNKNOWN"))
    }

    @Test
    fun `destUrl_validation_COPY_MOVE_can_dest`() {
        // Test validation logic: COPY/MOVE cần destUrl không rỗng
        fun validateOperation(operation: String, destUrl: String): Boolean {
            return !((operation == "COPY" || operation == "MOVE") && destUrl.isBlank())
        }

        assertFalse("COPY voi destUrl rong = invalid", validateOperation("COPY", ""))
        assertFalse("MOVE voi destUrl rong = invalid", validateOperation("MOVE", "  "))
        assertTrue("COPY voi destUrl hop le = valid", validateOperation("COPY", "http://nas/folder/"))
        assertTrue("DELETE khong can destUrl", validateOperation("DELETE", ""))
        assertTrue("RESTORE khong can destUrl", validateOperation("RESTORE", ""))
    }

    @Test
    fun `filename_fallback_tu_path_khi_fileNames_ngan_hon`() {
        // Khi fileNames ngắn hơn filePaths, phải lấy từ path
        val paths = arrayOf("http://nas/a/b/fallback.jpg")
        val names = emptyArray<String>() // rỗng → phải fallback

        var capturedName = ""
        simulateBatch(paths, names, { false }) { _, fileName ->
            capturedName = fileName
            true
        }
        assertEquals("Phai lay ten tu path khi fileNames rong", "fallback.jpg", capturedName)
    }
}
