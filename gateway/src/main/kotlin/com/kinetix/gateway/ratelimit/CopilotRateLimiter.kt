package com.kinetix.gateway.ratelimit

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-user, fixed-window rate limiter for the AI Copilot routes (AI-v2 PR 10.2).
 *
 * The Copilot streaming endpoints (`POST /api/v1/insights/chat` and
 * `POST /api/v1/insights/queries/{id}/run`) are capped at
 * [maxRequestsPerWindow] requests per user per [windowMillis]. The limiter is
 * keyed by the user's identity (the JWT `sub` claim), so two distinct users
 * never share a bucket — exhausting one user's allowance leaves every other
 * user's allowance untouched.
 *
 * A fixed-window counter is used rather than the token-bucket
 * [TokenBucketRateLimiter] because the requirement is expressed as a whole
 * number of requests over a whole minute ("10 requests / user / minute"), which
 * a window counter models exactly without fractional refill rates. The window
 * resets the first time a request arrives after the previous window has
 * elapsed; this is deliberately the simplest correct model for a coarse
 * abuse-prevention limit and matches the granularity the requirement specifies.
 *
 * Thread-safe: each per-user [Window] is mutated under its own monitor.
 */
class CopilotRateLimiter(
    private val maxRequestsPerWindow: Int = DEFAULT_MAX_REQUESTS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Atomically records one request for [userKey] and reports whether it is
     * within the allowance. Returns `true` when the request is permitted and
     * `false` when the user has already used their full quota for the current
     * window.
     */
    fun tryAcquire(userKey: String): Boolean {
        val window = windows.computeIfAbsent(userKey) { Window(clock.millis()) }
        return window.tryAcquire()
    }

    private inner class Window(private var windowStart: Long) {
        private var count: Int = 0

        @Synchronized
        fun tryAcquire(): Boolean {
            val now = clock.millis()
            if (now - windowStart >= windowMillis) {
                windowStart = now
                count = 0
            }
            return if (count < maxRequestsPerWindow) {
                count += 1
                true
            } else {
                false
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_REQUESTS: Int = 10
        const val DEFAULT_WINDOW_MILLIS: Long = 60_000L

        /** `Retry-After` value (seconds) advertised on a 429 response. */
        const val RETRY_AFTER_SECONDS: Int = 60
    }
}
