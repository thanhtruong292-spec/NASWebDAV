package com.nas.naswebdav.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive SharedPreferences cho UI settings.
 *
 * Composable đọc prefs trực tiếp trong remember cho giá trị stale và mất
 * state khi activity recreate. Repository expose StateFlow để ViewModel giữ,
 * UI collect — survive rotation, reactive khi đổi.
 */
class PreferencesRepository(context: Context) {

    private val appContext = context.applicationContext

    private fun prefs(name: String): SharedPreferences =
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ─── Browser: sort mode ───
    private val _fileSort = MutableStateFlow(
        prefs(PREFS_BROWSER).getString(KEY_FILE_SORT, "name_asc") ?: "name_asc"
    )
    val fileSort: StateFlow<String> = _fileSort.asStateFlow()

    fun setFileSort(mode: String) {
        prefs(PREFS_BROWSER).edit().putString(KEY_FILE_SORT, mode).apply()
        _fileSort.value = mode
    }

    // ─── Browser: view mode ───
    private val _viewMode = MutableStateFlow(
        prefs(PREFS_BROWSER).getString(KEY_VIEW_MODE, "ICON") ?: "ICON"
    )
    val viewMode: StateFlow<String> = _viewMode.asStateFlow()

    fun setViewMode(mode: String) {
        prefs(PREFS_BROWSER).edit().putString(KEY_VIEW_MODE, mode).apply()
        _viewMode.value = mode
    }

    fun refreshFromDisk() {
        _fileSort.value = prefs(PREFS_BROWSER).getString(KEY_FILE_SORT, "name_asc") ?: "name_asc"
        _viewMode.value = prefs(PREFS_BROWSER).getString(KEY_VIEW_MODE, "ICON") ?: "ICON"
        _autoCleanEnabled.value = prefs(PREFS_NAS).getBoolean(KEY_AUTO_CLEAN, false)
        _viewedFiles.value = readViewedSet()
    }

    // ─── Browser: viewed files (red-dot "new" indicator, cap 5000) ───
    private val _viewedFiles = MutableStateFlow(readViewedSet())
    val viewedFiles: StateFlow<Set<String>> = _viewedFiles.asStateFlow()

    private fun readViewedSet(): Set<String> =
        prefs(PREFS_BROWSER).getStringSet(KEY_VIEWED_FILES, emptySet()) ?: emptySet()

    fun isViewed(path: String): Boolean = _viewedFiles.value.contains(path)

    fun markViewed(paths: Collection<String>) {
        val clean = paths.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        val p = prefs(PREFS_BROWSER)
        synchronized(p) {
            val viewed = readViewedSet().toMutableSet()
            val order = readViewedOrder().toMutableList()
            clean.forEach { path ->
                if (viewed.add(path)) order.add(path)
            }
            val trimmed = if (order.size > VIEWED_FILES_LIMIT) {
                val drop = order.size - VIEWED_FILES_LIMIT
                order.subList(0, drop).forEach { viewed.remove(it) }
                order.subList(drop, order.size).toList()
            } else order
            p.edit {
                putStringSet(KEY_VIEWED_FILES, viewed)
                putString(KEY_VIEWED_ORDER, org.json.JSONArray(trimmed).toString())
            }
            _viewedFiles.value = viewed
        }
    }

    private fun readViewedOrder(): List<String> {
        val raw = prefs(PREFS_BROWSER).getString(KEY_VIEWED_ORDER, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            List(arr.length()) { arr.optString(it, "") }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    // ─── Search history (cap 15) ───
    fun getSearchHistory(): List<Pair<String, Long>> {
        val raw = prefs(PREFS_SEARCH).getString(KEY_SEARCH_HISTORY, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                o.getString("query") to o.getLong("timestamp")
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveSearchQuery(query: String) {
        if (query.isBlank()) return
        val p = prefs(PREFS_SEARCH)
        val updated = (listOf(query to System.currentTimeMillis()) +
            getSearchHistory().filter { it.first != query }).take(SEARCH_HISTORY_LIMIT)
        val arr = org.json.JSONArray()
        updated.forEach { (q, ts) ->
            arr.put(org.json.JSONObject().put("query", q).put("timestamp", ts))
        }
        p.edit { putString(KEY_SEARCH_HISTORY, arr.toString()) }
    }

    // ─── SmartTools: auto clean ───
    private val _autoCleanEnabled = MutableStateFlow(
        prefs(PREFS_NAS).getBoolean(KEY_AUTO_CLEAN, false)
    )
    val autoCleanEnabled: StateFlow<Boolean> = _autoCleanEnabled.asStateFlow()

    fun setAutoCleanEnabled(enabled: Boolean) {
        prefs(PREFS_NAS).edit().putBoolean(KEY_AUTO_CLEAN, enabled).apply()
        _autoCleanEnabled.value = enabled
    }

    // ─── Login: WoL MAC ───
    fun getMacAddress(): String =
        prefs(PREFS_NAS).getString(KEY_MAC_ADDRESS, "") ?: ""

    fun setMacAddress(mac: String) {
        prefs(PREFS_NAS).edit().putString(KEY_MAC_ADDRESS, mac).apply()
    }

    // ─── Login: biometric quick-login flag ───
    fun isBiometricEnabled(): Boolean =
        prefs(PREFS_NAS).getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs(PREFS_NAS).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getBiometricLockDelaySec(default: Int = 10): Int =
        prefs(PREFS_NAS).getInt(KEY_BIOMETRIC_DELAY, default)

    fun setBiometricLockDelaySec(delaySec: Int) {
        prefs(PREFS_NAS).edit().putInt(KEY_BIOMETRIC_DELAY, delaySec).apply()
    }

    // ─── Alert rules (MiscDialogs) ───
    data class AlertRules(
        val enabled: Boolean = true,
        val pauseOnDiskLow: Boolean = true,
        val cpuThreshold: Int = 90,
        val ramThreshold: Int = 85
    )

    fun getAlertRules(): AlertRules {
        val p = prefs(PREFS_NAS)
        return AlertRules(
            enabled = p.getBoolean(KEY_ALERT_ENABLED, true),
            pauseOnDiskLow = p.getBoolean(KEY_ALERT_PAUSE_DISK_LOW, true),
            cpuThreshold = p.getInt(KEY_ALERT_CPU, 90),
            ramThreshold = p.getInt(KEY_ALERT_RAM, 85)
        )
    }

    fun setAlertRules(rules: AlertRules) {
        prefs(PREFS_NAS).edit {
            putBoolean(KEY_ALERT_ENABLED, rules.enabled)
            putBoolean(KEY_ALERT_PAUSE_DISK_LOW, rules.pauseOnDiskLow)
            putInt(KEY_ALERT_CPU, rules.cpuThreshold)
            putInt(KEY_ALERT_RAM, rules.ramThreshold)
        }
    }

    // ─── Coil cache clear timestamp ───
    fun getLastCacheClear(): Long =
        prefs(PREFS_CACHE).getLong(KEY_LAST_CACHE_CLEAR, 0L)

    fun setLastCacheClear(now: Long) {
        prefs(PREFS_CACHE).edit().putLong(KEY_LAST_CACHE_CLEAR, now).apply()
    }

    // ─── Hardware profile (DiskProfileScreen) ───
    fun getHardwareProfile(key: String, default: String = ""): String =
        prefs(PREFS_HW).getString(key, default) ?: default

    fun setHardwareProfile(key: String, value: String) {
        prefs(PREFS_HW).edit().putString(key, value).apply()
    }

    fun getHardwareProfileLong(key: String, default: Long = 0L): Long =
        prefs(PREFS_HW).getLong(key, default)

    fun setHardwareProfileLong(key: String, value: Long) {
        prefs(PREFS_HW).edit().putLong(key, value).apply()
    }

    fun removeHardwareProfile(vararg keys: String) {
        prefs(PREFS_HW).edit {
            keys.forEach { remove(it) }
        }
    }

    // ─── Main menu: feature flags & slots ───
    fun isAutoBackupFlag(): Boolean =
        prefs(PREFS_NAS).getBoolean(KEY_AUTO_BACKUP, false)

    fun setAutoBackupFlag(enabled: Boolean) {
        prefs(PREFS_NAS).edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    fun isDeleteAfterBackup(): Boolean =
        prefs(PREFS_NAS).getBoolean(KEY_DELETE_AFTER_BACKUP, false)

    fun setDeleteAfterBackup(enabled: Boolean) {
        prefs(PREFS_NAS).edit().putBoolean(KEY_DELETE_AFTER_BACKUP, enabled).apply()
    }

    fun getUploadSpeedLimit(): Long =
        prefs(PREFS_NAS).getLong(KEY_UPLOAD_SPEED_LIMIT, 0L)

    fun setUploadSpeedLimit(bps: Long) {
        prefs(PREFS_NAS).edit().putLong(KEY_UPLOAD_SPEED_LIMIT, bps).apply()
    }

    fun getQuickSlot(key: String, default: String): String =
        prefs(PREFS_NAS).getString(key, default) ?: default

    fun setQuickSlot(key: String, value: String) {
        prefs(PREFS_NAS).edit().putString(key, value).apply()
    }

    fun isScreenRecordQuickAdded(): Boolean =
        prefs(PREFS_NAS).getBoolean(KEY_SCREEN_RECORD_QUICK, false)

    fun setScreenRecordQuickAdded(added: Boolean) {
        prefs(PREFS_NAS).edit().putBoolean(KEY_SCREEN_RECORD_QUICK, added).apply()
    }

    // UX3: che do dashboard Tinh gon (chi file/backup/media) vs Chuyen gia
    // (day du widget sysadmin). Luu prefs de giu lua chon qua session.
    fun isDashboardSimpleMode(): Boolean =
        prefs(PREFS_NAS).getBoolean("dashboard_simple_mode", false)

    fun setDashboardSimpleMode(simple: Boolean) {
        prefs(PREFS_NAS).edit().putBoolean("dashboard_simple_mode", simple).apply()
    }

    companion object {
        const val PREFS_BROWSER = "browser_prefs"
        const val PREFS_NAS = "nas_prefs"
        const val PREFS_SEARCH = "search_history"
        const val PREFS_CACHE = "nas_cache"
        const val PREFS_HW = "nas_hardware_profile"

        const val KEY_FILE_SORT = "file_sort"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_AUTO_CLEAN = "auto_clean_enabled"
        const val KEY_VIEWED_FILES = "viewed_files"
        const val KEY_VIEWED_ORDER = "viewed_files_order"
        const val KEY_SEARCH_HISTORY = "history"
        const val KEY_MAC_ADDRESS = "mac_address"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_BIOMETRIC_DELAY = "biometric_lock_delay_sec"
        const val KEY_AUTO_BACKUP = "auto_backup"
        const val KEY_DELETE_AFTER_BACKUP = "delete_after_backup"
        const val KEY_UPLOAD_SPEED_LIMIT = "upload_speed_limit_bps"
        const val KEY_SCREEN_RECORD_QUICK = "screen_record_quick_added"
        const val KEY_ALERT_ENABLED = "alert_enabled"
        const val KEY_ALERT_PAUSE_DISK_LOW = "alert_pause_disk_low"
        const val KEY_ALERT_CPU = "alert_cpu_threshold"
        const val KEY_ALERT_RAM = "alert_ram_threshold"
        const val KEY_LAST_CACHE_CLEAR = "last_cache_clear"
        const val VIEWED_FILES_LIMIT = 5000
        const val SEARCH_HISTORY_LIMIT = 15

        @Volatile
        private var instance: PreferencesRepository? = null

        fun get(context: Context): PreferencesRepository =
            instance ?: synchronized(this) {
                instance ?: PreferencesRepository(context).also { instance = it }
            }
    }
}
