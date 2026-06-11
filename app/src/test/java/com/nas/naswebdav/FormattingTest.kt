package com.nas.naswebdav

import com.nas.naswebdav.ui.dialogs.formatLogMessage
import com.nas.naswebdav.ui.screens.formatElapsedTimeUI
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric (formatLogMessage dùng org.json) cho 2 hàm format hiển thị.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FormattingTest {

    @Test fun elapsed_zeroAndNegative() {
        assertEquals("0 giây", formatElapsedTimeUI(0))
        assertEquals("0 giây", formatElapsedTimeUI(-100))
    }

    @Test fun elapsed_secondsOnly() = assertEquals("5 giây", formatElapsedTimeUI(5_000))

    @Test fun elapsed_minutesSeconds() = assertEquals("1 phút, 5 giây", formatElapsedTimeUI(65_000))

    @Test fun elapsed_daysHoursMinutesSeconds() =
        // 1 ngày + 1 giờ + 1 phút + 1 giây = 90061s
        assertEquals("1 ngày, 1 giờ, 1 phút, 1 giây", formatElapsedTimeUI(90_061_000))

    @Test fun elapsed_dropsZeroParts() =
        // đúng 2 giờ → không hiện phút/giây
        assertEquals("2 giờ", formatElapsedTimeUI(2L * 3600 * 1000))

    @Test fun logMessage_plainPassThrough() =
        assertEquals("just a plain line", formatLogMessage("just a plain line"))

    @Test fun logMessage_webdavSuccessFormatted() {
        val raw = """{"event":"WEBDAV_SUCCESS","device":"Phone","ip":"10.0.0.5","mac":"AA:BB"}"""
        assertEquals("Phone (IP: 10.0.0.5 - MAC: AA:BB) đã kết nối NAS.", formatLogMessage(raw))
    }

    @Test fun logMessage_unknownEventReturnsRaw() {
        val raw = """{"event":"SOMETHING_ELSE","x":1}"""
        assertEquals(raw, formatLogMessage(raw))
    }

    @Test fun logMessage_malformedJsonReturnsRaw() =
        assertEquals("{not valid json", formatLogMessage("{not valid json"))
}
