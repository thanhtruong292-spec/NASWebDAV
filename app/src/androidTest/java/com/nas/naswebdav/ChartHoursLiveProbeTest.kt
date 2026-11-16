package com.nas.naswebdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nas.naswebdav.monitor.SystemMonitorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only integration probe for a configured NAS with a day of recorded metrics. */
@RunWith(AndroidJUnit4::class)
class ChartHoursLiveProbeTest {
    @Test fun switchingHoursReplacesTheWholeSeries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = SmartNetworkManager.getVerifiedBaseUrl(context)
        assertTrue("No authenticated NAS route", base.isNotBlank())
        WebDavManager.connect(base, SecurePrefsHelper.getUser(context), SecurePrefsHelper.getPass(context))
        val vm = SystemMonitorViewModel(WebDavRepository(WebDavManager, NasApplication.instance.database))
        withContext(Dispatchers.Main) {
            for (hours in listOf(24, 1, 6, 1)) {
                vm.selectMetricsHours(hours)
                assertTrue(vm.metricsHistory.isEmpty())
                vm.fetchMetricsHistory(hours)
                withTimeout(45000) { while (vm.isLoadingMetrics) delay(50) }
                assertNull(vm.metricsError)
                val points = vm.metricsHistory.toList()
                assertTrue(points.size > 1)
                val span = java.time.Duration.between(
                    java.time.LocalDateTime.parse(points.first().timestamp),
                    java.time.LocalDateTime.parse(points.last().timestamp)
                ).toMinutes()
                assertTrue("Selected " + hours + "h, received " + span + " min",
                    span in (hours * 60L - 20)..(hours * 60L))
            }
        }
    }
}
