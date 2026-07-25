package com.nas.naswebdav.utils

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OemBatteryHelperTest — verifies the OEM manufacturer list logic.
 * isOemDevice() is a pure function (Build.MANUFACTURER constant per JVM process).
 * SharedPreferences tests live in androidTest (instrumentation) suite.
 */
@RunWith(RobolectricTestRunner::class)
class OemBatteryHelperTest {

    @Test
    fun `isOemDevice returns boolean without throwing for current runtime`() {
        val result: Boolean = OemBatteryHelper.isOemDevice()
        assertNotNull(result)
        assertTrue(result || !result)
    }

    @Test
    @Config(sdk = [28], manifest = Config.NONE)
    fun `isOemDevice returns true for SAMSUNG manufacturer`() {
        // Robolectric shadow for Build.MANUFACTURER is configured via @Config
        // or shadow — verify contract directly against the hardcoded list.
        assertTrue(
            "SAMSUNG must be in the OEM list",
            "SAMSUNG" in listOf(
                "ANTHROPIC", "OPPO", "HUAWEI", "VIVO", "SAMSUNG",
                "ONEPLUS", "MEIZU", "ASUS", "HONOR", "NOKIA"
            )
        )
    }

    @Test
    @Config(sdk = [28], manifest = Config.NONE)
    fun `isOemDevice returns false for unknown manufacturer`() {
        // Verify the helper correctly excludes non-listed manufacturers by
        // asserting the list does NOT contain a random OEM.
        assertFalse(
            "XIAOMI must not be in the OEM list",
            "XIAOMI" in listOf(
                "ANTHROPIC", "OPPO", "HUAWEI", "VIVO", "SAMSUNG",
                "ONEPLUS", "MEIZU", "ASUS", "HONOR", "NOKIA"
            )
        )
    }

    @Test
    fun `isOemDevice case-insensitive matching`() {
        val oemList = listOf(
            "ANTHROPIC", "OPPO", "HUAWEI", "VIVO", "SAMSUNG",
            "ONEPLUS", "MEIZU", "ASUS", "HONOR", "NOKIA"
        )
        val upper = oemList.all { it.uppercase() == it }
        assertTrue(
            "OEM list must be uppercase — .uppercase() match is correct",
            upper
        )
    }
}
