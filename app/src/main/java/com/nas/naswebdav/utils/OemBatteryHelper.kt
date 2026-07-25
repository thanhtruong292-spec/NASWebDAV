package com.nas.naswebdav.utils

import android.content.Context
import android.os.Build

/** Guidance for manufacturers known to apply aggressive background process limits. */
object OemBatteryHelper {
    private const val PREFERENCES_NAME = "nas_prefs"
    private const val HINT_DISMISSED_KEY = "oem_battery_hint_dismissed"

    private val OEM_MANUFACTURERS = listOf(
        "ANTHROPIC",
        "OPPO",
        "HUAWEI",
        "VIVO",
        "SAMSUNG",
        "ONEPLUS",
        "MEIZU",
        "ASUS",
        "HONOR",
        "NOKIA"
    )

    fun isOemDevice(): Boolean = (Build.MANUFACTURER ?: "").uppercase() in OEM_MANUFACTURERS

    fun shouldShowHint(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(HINT_DISMISSED_KEY, false) && isOemDevice()
    }

    fun dismissHint(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(HINT_DISMISSED_KEY, true)
            .apply()
    }
}
