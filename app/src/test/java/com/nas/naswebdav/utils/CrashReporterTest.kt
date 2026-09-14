package com.nas.naswebdav.utils

import io.sentry.SentryEvent
import io.sentry.protocol.Message
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReporterTest {

    @Test
    fun `sanitize strips credentials from message`() {
        val event = SentryEvent().apply {
            message = Message().apply {
                formatted = "GET http://admin:s3cret@192.168.1.10/api failed"
            }
        }
        val out = CrashReporter.sanitizeEvent(event)
        val text = out.message?.formatted ?: ""
        assertFalse(text.contains("s3cret"))
        assertTrue(text.contains("***@"))
    }

    @Test
    fun `sanitize strips auth headers from exception values`() {
        val event = SentryEvent().apply {
            exceptions = listOf(
                io.sentry.protocol.SentryException().apply {
                    value = "401 Authorization: Basic dXNlcjpwYXNz"
                }
            )
        }
        val out = CrashReporter.sanitizeEvent(event)
        val text = out.exceptions?.firstOrNull()?.value ?: ""
        assertFalse(text.contains("dXNlcjpwYXNz"))
    }

    @Test
    fun `sanitize clears server name`() {
        val event = SentryEvent().apply { serverName = "nas.local" }
        assertTrue(CrashReporter.sanitizeEvent(event).serverName == null)
    }
}
