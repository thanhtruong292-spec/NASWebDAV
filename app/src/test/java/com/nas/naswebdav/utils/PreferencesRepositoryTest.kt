package com.nas.naswebdav.utils

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesRepositoryTest {

    private fun freshRepo(): PreferencesRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Xóa prefs để mỗi test độc lập (singleton giữ instance, refresh lại từ disk)
        context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.getSharedPreferences("nas_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val repo = PreferencesRepository.get(context)
        repo.refreshFromDisk()
        return repo
    }

    @Test
    fun `sort mode persists and emits`() {
        val repo = freshRepo()
        assertEquals("name_asc", repo.fileSort.value)
        repo.setFileSort("date_desc")
        assertEquals("date_desc", repo.fileSort.value)
    }

    @Test
    fun `view mode persists and emits`() {
        val repo = freshRepo()
        assertEquals("ICON", repo.viewMode.value)
        repo.setViewMode("LIST")
        assertEquals("LIST", repo.viewMode.value)
    }

    @Test
    fun `auto clean flag persists and emits`() {
        val repo = freshRepo()
        assertEquals(false, repo.autoCleanEnabled.value)
        repo.setAutoCleanEnabled(true)
        assertEquals(true, repo.autoCleanEnabled.value)
    }
}
