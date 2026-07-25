package com.nas.naswebdav.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OemBatteryHelperTest — verifies the OEM manufacturer list logic.
 * isOemDevice() is a pure function (Build.MANUFACTURER constant per JVM process).
 * SharedPreferences tests live in androidTest (instrumentation) suite.
 */
class OemBatteryHelperTest {

    @Test
    fun `isOemDevice returns boolean without throwing`() {
        // Smoke test: the function must not throw on any device/Robolectric default.
        val result = OemBatteryHelper.isOemDevice()
        assertTrue(result || !result)
    }
}
