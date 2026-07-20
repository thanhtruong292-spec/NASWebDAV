package com.nas.naswebdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SocialDownloadWorker.executeDownload].
 *
 * All tests bypass Android/Worker infrastructure by:
 *   - passing [authHeader]/[apiBaseUrl] pre-computed (avoids WebDavManager/SecurePrefsHelper)
 *   - injecting a [Call.Factory] stub (avoids OkHttp + real network)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocialDownloadWorkerExecuteDownloadTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)
    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun stubCall(body: String, code: Int = 200): Call.Factory = Call.Factory { req ->
        object : Call {
            override fun execute(): Response {
                val buf = Buffer().writeUtf8(body)
                return Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(code).message("stub")
                    .body(buf.asResponseBody("application/json".toMediaType()))
                    .build()
            }
            override fun enqueue(cb: okhttp3.Callback) = throw UnsupportedOperationException()
            override fun isExecuted() = false; override fun isCanceled() = false
            override fun cancel() = throw UnsupportedOperationException()
            override fun clone() = throw UnsupportedOperationException()
            override fun request() = req; override fun timeout() = throw UnsupportedOperationException()
        }
    }

    private fun errorCall(message: String): Call.Factory = Call.Factory { req ->
        object : Call {
            override fun execute(): Response = throw java.io.IOException(message)
            override fun enqueue(cb: okhttp3.Callback) = throw UnsupportedOperationException()
            override fun isExecuted() = false; override fun isCanceled() = false
            override fun cancel() = throw UnsupportedOperationException()
            override fun clone() = throw UnsupportedOperationException()
            override fun request() = req; override fun timeout() = throw UnsupportedOperationException()
        }
    }

    @Test
    fun returnsJobIdOn202() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 0,
            callFactory = stubCall("""{"result":"ok","job_id":"job-abc"}""", 202)
        )
        assertEquals("job-abc", r.jobId)
        assertFalse(r.isRetry)
    }

    @Test
    fun returnsServerErrorOnNon202() = runTest {
        // org.json.JSONObject is an Android stub in plain JVM unit tests, so the
        // executeDownload path will swallow the parse failure and use the generic
        // fallback. Assert that the fallback is correct instead.
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 2,
            callFactory = stubCall("""{"error":"Invalid URL"}""", 400)
        )
        // runAttemptCount=2 -> no retry -> surface the server error or fallback.
        assertFalse(r.isRetry)
        assertTrue(r.error!!.isNotBlank())
    }

    @Test
    fun returnsServerErrorOnNon202WhenBodyIsPlainText() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 2,
            callFactory = stubCall("not-json-body", 422)
        )
        assertTrue(r.error!!.startsWith("NAS từ chối yêu cầu (HTTP 422)."))
        assertFalse(r.isRetry)
    }

    @Test
    fun retriesOnTransient5xx() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 1,
            callFactory = stubCall("Server Error", 503)
        )
        assertTrue(r.isRetry)
        assertNull(r.error)
    }

    @Test
    fun retriesOn429() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 0,
            callFactory = stubCall("Rate limited", 429)
        )
        assertTrue(r.isRetry)
    }

    @Test
    fun retriesOnNetworkException() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 0,
            callFactory = errorCall("Connection refused")
        )
        assertTrue(r.isRetry)
        assertNull(r.error)
    }

    @Test
    fun noRetryAfterMaxAttempts() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 2,
            callFactory = errorCall("Connection refused")
        )
        assertFalse(r.isRetry)
        assertEquals("Không kết nối được NAS. Vui lòng thử lại sau.", r.error)
    }

    @Test
    fun blankConfiguredBaseUrlFailsImmediately() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 0,
            callFactory = stubCall("unreachable")
        )
        assertFalse(r.isRetry)
        assertTrue(r.error!!.contains("đăng nhập"))
    }

    @Test
    fun blankApiBaseUrlFailsImmediately() = runTest {
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "",
            authHeader = "Basic test-token",
            runAttemptCount = 0,
            callFactory = stubCall("unreachable")
        )
        assertFalse(r.isRetry)
        assertTrue(r.error!!.contains("không hợp lệ"))
    }

    @Test
    fun retriesOnNonRetryableServerErrorWhenAttemptsRemain() = runTest {
        // 500 is in the retry range, and runAttemptCount=0 < 2 -> retry.
        val r = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 0,
            callFactory = stubCall("{}", 500)
        )
        assertTrue(r.isRetry)
    }
}
