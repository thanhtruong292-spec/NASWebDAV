package com.nas.naswebdav

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialDownloadRequestFactoryTest {

    @Test
    fun buildsAuthenticatedSocialDownloadRequest() {
        val request = SocialDownloadRequestFactory.create(
            apiBaseUrl = "http://100.90.135.102:5050/",
            authHeader = "Basic test-token",
            socialUrl = "https://www.facebook.com/reel/123/"
        )

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val payload = buffer.readUtf8()

        assertEquals("http://100.90.135.102:5050/api/social/download", request.url.toString())
        assertEquals("Basic test-token", request.header("Authorization"))
        assertEquals(
            "{\"url\":\"https://www.facebook.com/reel/123/\",\"folder\":\"${AppConfig.SOCIAL_DOWNLOAD_FOLDER}\"}",
            payload
        )
    }

    @Test
    fun acceptsOnlyA202ResponseWithJobId() {
        assertEquals(
            "job-123",
            SocialDownloadRequestFactory.acceptedJobId(202, "{\"job_id\":\"job-123\"}")
        )
        assertNull(SocialDownloadRequestFactory.acceptedJobId(200, "{\"job_id\":\"job-123\"}"))
        assertNull(SocialDownloadRequestFactory.acceptedJobId(202, "{}"))
        assertNull(SocialDownloadRequestFactory.acceptedJobId(202, "not-json"))
    }

    @Test
    fun retriesOnlyTransientResponsesAndCapsAttempts() {
        assertTrue(SocialDownloadRequestFactory.shouldRetry(429, 0))
        assertTrue(SocialDownloadRequestFactory.shouldRetry(503, 1))
        assertFalse(SocialDownloadRequestFactory.shouldRetry(400, 0))
        assertFalse(SocialDownloadRequestFactory.shouldRetry(429, 2))
    }
}
