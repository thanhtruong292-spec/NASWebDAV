package com.nas.naswebdav.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive SharedPreferences cho UI settings.
 *
 * Vấn đề cũ: composable gọi getSharedPreferences trực tiếp trong remember —
 * giá trị stale khi setting đổi từ nơi khác, và state remember mất khi
 * activity recreate (xoay màn hình). Repository này expose StateFlow để
 * ViewModel giữ, UI collect — survive rotation, reactive khi đổi.
 */
class PreferencesRepository(context: Context) {

    private val appContext = context.applicationContext

    private fun prefs(name: String): SharedPreferences =
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ─── Browser: sort mode ───
    private val _fileSort = MutableStateFlow(
        prefs("browser_prefs").getString(KEY_FILE_SORT, "name_asc") ?: "name_asc"
    )
    val fileSort: StateFlow<String> = _fileSort.asStateFlow()

    fun setFileSort(mode: String) {
        prefs("browser_prefs").edit().putString(KEY_FILE_SORT, mode).apply()
        _fileSort.value = mode
    }

    // ─── Browser: view mode ───
    private val _viewMode = MutableStateFlow(
        prefs("browser_prefs").getString(KEY_VIEW_MODE, "ICON") ?: "ICON"
    )
    val viewMode: StateFlow<String> = _viewMode.asStateFlow()

    fun setViewMode(mode: String) {
        prefs("browser_prefs").edit().putString(KEY_VIEW_MODE, mode).apply()
        _viewMode.value = mode
    }

    fun refreshFromDisk() {
        _fileSort.value = prefs("browser_prefs").getString(KEY_FILE_SORT, "name_asc") ?: "name_asc"
        _viewMode.value = prefs("browser_prefs").getString(KEY_VIEW_MODE, "ICON") ?: "ICON"
        _autoCleanEnabled.value = prefs("nas_prefs").getBoolean(KEY_AUTO_CLEAN, false)
    }

    // ─── SmartTools: auto clean ───
    private val _autoCleanEnabled = MutableStateFlow(
        prefs("nas_prefs").getBoolean(KEY_AUTO_CLEAN, false)
    )
    val autoCleanEnabled: StateFlow<Boolean> = _autoCleanEnabled.asStateFlow()

    fun setAutoCleanEnabled(enabled: Boolean) {
        prefs("nas_prefs").edit().putBoolean(KEY_AUTO_CLEAN, enabled).apply()
        _autoCleanEnabled.value = enabled
    }

    companion object {
        const val KEY_FILE_SORT = "file_sort"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_AUTO_CLEAN = "auto_clean_enabled"

        @Volatile
        private var instance: PreferencesRepository? = null

        fun get(context: Context): PreferencesRepository =
            instance ?: synchronized(this) {
                instance ?: PreferencesRepository(context).also { instance = it }
            }
    }
}
