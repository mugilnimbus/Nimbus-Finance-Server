package com.nimbus.finance.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbuseLimiterTest {
    @Test
    fun rejectsRequestsAfterBoundAndResetsAfterWindow() {
        var now = 0L
        val limiter = AbuseLimiter { now }
        val policy = RateLimitPolicy(2, 10)

        assertNull(limiter.retryAfterSeconds("login", "alice", policy))
        assertNull(limiter.retryAfterSeconds("login", "alice", policy))
        assertEquals(10, limiter.retryAfterSeconds("login", "alice", policy))

        now = 10_000_000_001L
        assertNull(limiter.retryAfterSeconds("login", "alice", policy))
    }

    @Test
    fun namespacesAndSensitiveKeysAreIndependent() {
        val limiter = AbuseLimiter { 0L }
        val one = RateLimitPolicy(1, 60)
        assertNull(limiter.retryAfterSeconds("login", "alice", one))
        assertTrue(limiter.retryAfterSeconds("login", "alice", one)!! > 0)
        assertNull(limiter.retryAfterSeconds("login", "bob", one))
        assertNull(limiter.retryAfterSeconds("register", "alice", one))
    }
}
