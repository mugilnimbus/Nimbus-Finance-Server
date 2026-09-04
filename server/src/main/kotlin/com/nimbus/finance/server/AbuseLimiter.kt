package com.nimbus.finance.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

internal data class RateLimitPolicy(val requests: Int, val windowSeconds: Long) {
    init {
        require(requests > 0)
        require(windowSeconds > 0)
    }
}

/**
 * Small in-process fixed-window limiter for the private single-node API.
 * Keys are hashed before storage so usernames, invitations, and tokens never
 * appear in operational memory snapshots or diagnostics.
 */
internal class AbuseLimiter(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Window(var startedAt: Long, var requests: Int)

    private val windows = ConcurrentHashMap<String, Window>()
    private val checks = AtomicLong()

    fun retryAfterSeconds(namespace: String, sensitiveKey: String, policy: RateLimitPolicy): Long? {
        val now = nanoTime()
        val duration = policy.windowSeconds * 1_000_000_000L
        val key = "$namespace:${sha256(sensitiveKey)}"
        val window = windows.computeIfAbsent(key) { Window(now, 0) }
        val retry = synchronized(window) {
            if (now - window.startedAt >= duration || now < window.startedAt) {
                window.startedAt = now
                window.requests = 0
            }
            if (window.requests >= policy.requests) {
                ceil((duration - (now - window.startedAt)).coerceAtLeast(1L) / 1_000_000_000.0).toLong()
            } else {
                window.requests += 1
                null
            }
        }
        if (checks.incrementAndGet() % 1_024L == 0L) {
            windows.entries.removeIf { now - it.value.startedAt > duration.coerceAtLeast(3_600_000_000_000L) }
        }
        return retry
    }
}

internal val AUTH_IP_POLICY = RateLimitPolicy(20, 60)
internal val AUTH_IDENTITY_POLICY = RateLimitPolicy(8, 300)
internal val ADMIN_POLICY = RateLimitPolicy(6, 900)
internal val PUBLIC_LOOKUP_POLICY = RateLimitPolicy(40, 60)
internal val SYNC_POLICY = RateLimitPolicy(180, 60)
internal val INFERENCE_POLICY = RateLimitPolicy(60, 60)
