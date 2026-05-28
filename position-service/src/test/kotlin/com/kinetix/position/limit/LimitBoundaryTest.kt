package com.kinetix.position.limit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The classic limit-check edge case: when the trader's quantity is
 * EXACTLY equal to the configured limit, has the limit been breached
 * or not? The platform's convention is that the limit value itself
 * is permitted (`<=` not `<`), so a 1,000,000-share limit accepts a
 * 1,000,000-share position. One more share is the breach.
 */
class LimitBoundaryTest : FunSpec({

    test("quantity below the limit is allowed") {
        checkPositionLimit(quantity = 999_999, limit = 1_000_000) shouldBe LimitCheck.WithinLimit
    }

    test("quantity exactly at the limit is allowed (<= inclusive)") {
        checkPositionLimit(1_000_000, 1_000_000) shouldBe LimitCheck.WithinLimit
    }

    test("quantity one above the limit is a breach") {
        checkPositionLimit(1_000_001, 1_000_000) shouldBe LimitCheck.Breach(overage = 1L)
    }

    test("breach overage is reported as quantity - limit") {
        checkPositionLimit(1_500_000, 1_000_000) shouldBe LimitCheck.Breach(overage = 500_000L)
    }

    test("zero quantity is always allowed (no position)") {
        checkPositionLimit(0, 1_000_000) shouldBe LimitCheck.WithinLimit
    }

    test("negative limit is invalid input") {
        runCatching { checkPositionLimit(100, -1) }.isFailure shouldBe true
    }
})
