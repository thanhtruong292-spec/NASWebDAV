package com.nas.naswebdav

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongRunningApiWorkerTest {
    @Test
    fun `transient network errors retry before third attempt`() {
        assertTrue(LongRunningApiWorker.shouldRetry(IOException("connection reset"), 0))
        assertTrue(LongRunningApiWorker.shouldRetry(ConnectException("refused"), 1))
        assertTrue(LongRunningApiWorker.shouldRetry(SocketTimeoutException("timeout"), 2))
        assertFalse(LongRunningApiWorker.shouldRetry(UnknownHostException("offline"), 3))
    }

    @Test
    fun `non transient errors never retry`() {
        assertFalse(LongRunningApiWorker.shouldRetry(IllegalStateException("bad payload"), 0))
        assertFalse(LongRunningApiWorker.shouldRetry(IllegalArgumentException("bad request"), 1))
    }
}
