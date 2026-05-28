package com.kinetix.gateway.middleware

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The gateway sits in front of every downstream service and is the place
 * to enforce a per-trader rate limit. After enforcement, the gateway
 * should also propagate the standard rate-limit response headers
 * (X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset) back to
 * the client so the client can back off proactively instead of hammering
 * until it hits a 429. This test pins the header-extraction contract:
 * given a [RateLimitState], the header map carries exactly those three
 * headers with sensible string values.
 */
class RateLimitHeaderPropagationTest : FunSpec({

    test("a non-throttled state emits limit/remaining/reset headers") {
        val state = RateLimitState(limit = 100, remaining = 95, resetEpochSecond = 1_716_900_000)
        rateLimitHeaders(state) shouldBe mapOf(
            "X-RateLimit-Limit" to "100",
            "X-RateLimit-Remaining" to "95",
            "X-RateLimit-Reset" to "1716900000",
        )
    }

    test("zero remaining is still emitted (so clients can read \"throttled\" from the header)") {
        val state = RateLimitState(limit = 100, remaining = 0, resetEpochSecond = 1_716_900_000)
        rateLimitHeaders(state)["X-RateLimit-Remaining"] shouldBe "0"
    }

    test("custom limit values pass through verbatim") {
        val state = RateLimitState(limit = 1, remaining = 0, resetEpochSecond = 0L)
        rateLimitHeaders(state)["X-RateLimit-Limit"] shouldBe "1"
    }

    test("the header map is small (no leakage of internal state)") {
        val state = RateLimitState(limit = 100, remaining = 95, resetEpochSecond = 1L)
        rateLimitHeaders(state).keys shouldBe setOf(
            "X-RateLimit-Limit",
            "X-RateLimit-Remaining",
            "X-RateLimit-Reset",
        )
    }

    test("negative remaining is clamped to 0 (defensive — pool drift)") {
        val state = RateLimitState(limit = 100, remaining = -5, resetEpochSecond = 0L)
        rateLimitHeaders(state)["X-RateLimit-Remaining"] shouldBe "0"
    }
})
