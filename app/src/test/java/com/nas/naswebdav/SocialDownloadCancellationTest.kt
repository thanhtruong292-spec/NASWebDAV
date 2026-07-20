package com.nas.naswebdav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for the WorkManager cancellation bug:
 * `SocialDownloadWorker.executeDownload` used to catch CancellationException in its
 * generic `catch (Exception)` block and convert it into a retry/failure result,
 * defeating cooperative cancellation from WorkManager.
 *
 * After the fix, the catch block re-throws CancellationException so structured
 * cancellation propagates correctly.
 *
 * This uses real `runBlocking` + `Dispatchers.Default` (not the coroutines-test
 * virtual scheduler) because we deliberately block a real thread inside the OkHttp
 * stub to exercise thread-interruption-driven cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocialDownloadCancellationTest {

    @Test
    fun cancellationException_propagatesInsteadOfBecomingRetry() = runBlocking {
        val enteredNetwork = CountDownLatch(1)
        val releasedNetwork = CountDownLatch(1)

        val cancellableCallFactory: Call.Factory = Call.Factory { request: Request ->
            object : Call {
                override fun execute(): Response {
                    enteredNetwork.countDown()
                    // Block the real IO thread. When the coroutine is cancelled, the
                    // thread stays here until the latch is released; the coroutine
                    // machinery throws CancellationException at the next suspension /
                    // withContext boundary once this returns.
                    releasedNetwork.await(10, TimeUnit.SECONDS)
                    throw IOException("stub released without cancellation")
                }

                override fun enqueue(cb: okhttp3.Callback) = throw UnsupportedOperationException()
                override fun isExecuted() = false
                override fun isCanceled() = false
                override fun cancel() = throw UnsupportedOperationException()
                override fun clone() = throw UnsupportedOperationException()
                override fun request() = request
                override fun timeout() = throw UnsupportedOperationException()
            }
        }

        val deferred = async(Dispatchers.Default) {
            SocialDownloadWorker.executeDownload(
                socialUrl = "https://www.facebook.com/reel/123/",
                configuredBaseUrl = "http://10.0.0.1:5050/",
                apiBaseUrl = "http://10.0.0.1:5050/api",
                authHeader = "Basic test-token",
                runAttemptCount = 0,
                callFactory = cancellableCallFactory
            )
        }

        // Wait until the stub is actively running on the IO thread.
        assertTrue(
            "network stub did not enter execute()",
            enteredNetwork.await(5, TimeUnit.SECONDS)
        )

        // Cancel the coroutine, then release the blocked thread.
        deferred.cancel(CancellationException("WorkManager stopped the worker"))
        releasedNetwork.countDown()

        // Join and assert the coroutine ended in Cancelled, i.e. CancellationException
        // propagated out of executeDownload rather than being swallowed into retry.
        val cancelled = withTimeoutOrNull(5_000) {
            try {
                deferred.await()
                false // completed normally => cancellation was swallowed
            } catch (e: CancellationException) {
                true
            }
        }

        assertTrue(
            "executeDownload must propagate cancellation, not swallow it into retry",
            cancelled == true
        )
    }

    @Test
    fun plainNetworkException_stillYieldsRetry() = runBlocking {
        val failCall: Call.Factory = Call.Factory { request: Request ->
            object : Call {
                override fun execute(): Response = throw IOException("server down")
                override fun enqueue(cb: okhttp3.Callback) = throw UnsupportedOperationException()
                override fun isExecuted() = false
                override fun isCanceled() = false
                override fun cancel() = throw UnsupportedOperationException()
                override fun clone() = throw UnsupportedOperationException()
                override fun request() = request
                override fun timeout() = throw UnsupportedOperationException()
            }
        }

        val result = SocialDownloadWorker.executeDownload(
            socialUrl = "https://www.facebook.com/reel/123/",
            configuredBaseUrl = "http://10.0.0.1:5050/",
            apiBaseUrl = "http://10.0.0.1:5050/api",
            authHeader = "Basic test-token",
            runAttemptCount = 0,
            callFactory = failCall
        )

        assertTrue("IOException must still trigger retry on first attempt", result.isRetry)
    }
}