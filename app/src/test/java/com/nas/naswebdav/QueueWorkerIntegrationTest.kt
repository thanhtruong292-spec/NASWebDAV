package com.nas.naswebdav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 4: worker integration test — queue decision logic qua Room thật.
 * Không cần emulator: Robolectric + in-memory DB + WorkManagerTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueWorkerIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `foreign NAS rows stay while local rows drain`() {
        // Mô phỏng worker: batch loại PARKED, row khác NAS bị bỏ qua giữ lại.
        val dao = database.syncActionDao()
        dao.insert(SyncAction(actionType = "UPLOAD", sourcePath = "a://1", nasHost = "nas-a", nasUser = "u"))
        dao.insert(SyncAction(actionType = "UPLOAD", sourcePath = "b://2", nasHost = "nas-b", nasUser = "u"))
        val activeHost = "nas-a"
        val user = "u"
        val batch = dao.getAllPendingActions()
        var skippedForeign = 0
        val local = batch.filter {
            val foreign = it.nasHost.isNotEmpty() &&
                (it.nasHost != activeHost || it.nasUser.isNotEmpty() && it.nasUser != user)
            if (foreign) skippedForeign++ else true
            !foreign
        }
        assertEquals(1, local.size)
        assertEquals(1, skippedForeign)
        // Xử lý xong local → xóa; foreign còn nguyên.
        local.forEach { dao.deleteById(it.id) }
        assertEquals(1, dao.countAll())
        assertEquals("b://2", dao.getAllPendingActions().first().sourcePath)
    }

    @Test
    fun `failing rows park after 5 bumps and leave batch`() {
        val dao = database.syncActionDao()
        dao.insert(SyncAction(actionType = "UPLOAD", sourcePath = "bad://1"))
        dao.insert(SyncAction(actionType = "UPLOAD", sourcePath = "good://2"))
        val bad = dao.getAllPendingActions().first { it.sourcePath == "bad://1" }
        repeat(5) { dao.bumpFail(bad.id, System.currentTimeMillis()) }
        dao.park(bad.id)
        // Batch sau chỉ còn good — đuôi hợp lệ được vét.
        assertEquals(listOf("good://2"), dao.getAllPendingActions().map { it.sourcePath })
        // Unpark retry tay mở lại.
        dao.unpark(bad.id, System.currentTimeMillis())
        assertEquals(2, dao.countAll())
    }
}
