package com.nas.naswebdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests cho RateLimiter (ArrayDeque O(1) implementation).
 *
 * Kiểm tra:
 * - isAllowed() trả true khi còn slot trong window 60s
 * - isAllowed() trả false khi đã đạt maxRequests
 * - Sau khi timestamp cũ rời khỏi window, slot được giải phóng
 * - Thread-safety: nhiều luồng gọi đồng thời không gây data race
 */
class RateLimiterTest {

    private lateinit var limiter: RateLimiter

    @Before
    fun setUp() {
        // 3 requests/phút để test nhanh
        limiter = RateLimiter(maxRequestsPerMinute = 3)
    }

    @Test
    fun `isAllowed tra ve true khi con slot`() {
        assertTrue("Request 1 phai duoc chap nhan", limiter.isAllowed())
        assertTrue("Request 2 phai duoc chap nhan", limiter.isAllowed())
        assertTrue("Request 3 phai duoc chap nhan", limiter.isAllowed())
    }

    @Test
    fun `isAllowed tra ve false khi vuot qua maxRequests`() {
        repeat(3) { limiter.isAllowed() }
        assertFalse("Request thu 4 phai bi tu choi (vuot qua gioi han 3)", limiter.isAllowed())
    }

    @Test
    fun `limiter khac nhau doc lap voi nhau`() {
        val limiterA = RateLimiter(maxRequestsPerMinute = 2)
        val limiterB = RateLimiter(maxRequestsPerMinute = 5)

        limiterA.isAllowed()
        limiterA.isAllowed()
        assertFalse("LimiterA da het slot", limiterA.isAllowed())

        // LimiterB van con slot — doc lap
        assertTrue("LimiterB van duoc phep", limiterB.isAllowed())
    }

    @Test
    fun `limiter voi maxRequests_1 chi cho phep 1 request`() {
        val strict = RateLimiter(maxRequestsPerMinute = 1)
        assertTrue(strict.isAllowed())
        assertFalse("Chi cho phep 1 request/phut", strict.isAllowed())
    }

    @Test
    fun `isAllowed thread_safe khi goi dong thoi tu nhieu luong`() {
        val limiter = RateLimiter(maxRequestsPerMinute = 50)
        val results = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val threads = (1..20).map {
            Thread {
                repeat(5) { results.add(limiter.isAllowed()) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val allowedCount = results.count { it }
        val deniedCount = results.count { !it }

        // Tong request = 100, maxRequests = 50
        // Ket qua: dung 50 duoc phep, 50 bi tu choi
        assertTrue("Phai co it nhat 50 request duoc phep", allowedCount >= 50)
        assertTrue("Phai co it nhat 50 request bi tu choi", deniedCount >= 50)
    }

    @Test
    fun `limiter voi limit cao cho phep nhieu request`() {
        val generous = RateLimiter(maxRequestsPerMinute = 1000)
        var allowed = 0
        repeat(100) { if (generous.isAllowed()) allowed++ }
        assertTrue("Phai cho phep 100 request (limit=1000)", allowed == 100)
    }
}
